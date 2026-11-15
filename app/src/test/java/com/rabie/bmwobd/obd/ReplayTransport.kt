package com.rabie.bmwobd.obd

import java.net.SocketTimeoutException

/**
 * Un adaptador que contesta lo que contesto el coche de verdad, sacado de un registro de la app
 * (`>> consulta` y `<< respuesta`). Cada respuesta se guarda con la direccion que tenia puesta el
 * adaptador en ese momento (cabecera ATSH, direccion extendida ATCEA y protocolo de usuario B), porque
 * la misma consulta contesta distinto por la pasarela, por la direccion del motor o a todas.
 *
 * Si una consulta se repite en el registro, las respuestas salen en el mismo orden y la ultima se
 * repite. Lo que el registro no tiene se anota en [unknown]: un comando AT contesta OK y una consulta
 * NO DATA, como una centralita que no la conoce. Una consulta que en el registro agoto la espera
 * ("!! Sin respuesta a") la agota tambien aqui.
 *
 * Las lineas de la busqueda de medidas del B47 (`## Búsqueda: 22XXXX -> datos`) se van sin `>>`
 * porque se piden en silencio: se cargan como respuestas `62XXXX datos` por la direccion en que se
 * hizo la busqueda (la ultima forma con la que siguio la prueba).
 */
class ReplayTransport(log: List<String>) : ObdTransport {

    private data class State(val header: String, val extended: String, val user: Boolean)

    private class Answers(val list: MutableList<String?> = mutableListOf(), var next: Int = 0)

    private val answers = HashMap<Pair<State, String>, Answers>()
    private var state = START
    private var pending: String? = null
    val unknown = mutableListOf<String>()
    val asked = mutableListOf<String>()

    init {
        var recording = START
        var lastQuery: String? = null
        var sweepState: State? = null
        for (raw in log) {
            val line = raw.trimEnd()
            when {
                line.startsWith(">> ") -> {
                    val command = line.removePrefix(">> ").trim().uppercase()
                    recording = next(recording, command)
                    lastQuery = command
                    if (command.startsWith("ATST19")) sweepState = recording
                }
                line.startsWith("<< ") && lastQuery != null -> {
                    val response = line.removePrefix("<< ").split(" | ").joinToString("\r")
                    add(recording, lastQuery, response)
                    lastQuery = null
                }
                line.startsWith("!! Sin respuesta a ") -> {
                    val command = line.removePrefix("!! Sin respuesta a ").trim().uppercase()
                    add(recording, command, null)
                    if (command == lastQuery) lastQuery = null
                }
                line.startsWith("## Búsqueda: 22") && line.contains(" -> ") -> {
                    val query = line.removePrefix("## Búsqueda: ").substringBefore(" -> ").trim()
                    val data = line.substringAfter(" -> ").trim().substringBefore(" ")
                    if (query.length == 6 && data.all { it.isLetterOrDigit() }) {
                        add(sweepState ?: recording, query, "62" + query.drop(2) + data)
                    }
                }
            }
        }
    }

    private fun add(at: State, command: String, response: String?) {
        answers.getOrPut(at to command) { Answers() }.list += response
    }

    override suspend fun open() = Unit
    override fun close() = Unit

    override suspend fun write(text: String) {
        val command = text.trim().uppercase().replace(" ", "")
        state = next(state, command)
        pending = command
        asked += command
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val command = pending ?: throw SocketTimeoutException("nada pendiente")
        pending = null
        val recorded = answers[state to command]
        if (recorded == null) {
            unknown += command
            return if (command.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }
        val index = minOf(recorded.next, recorded.list.size - 1)
        recorded.next++
        return (recorded.list[index] ?: throw SocketTimeoutException("sin respuesta en el registro")) + "\r\r"
    }

    private companion object {
        val START = State(header = "7DF", extended = "", user = false)

        fun next(current: State, command: String): State = when {
            command == "ATZ" || command == "ATD" || command == "ATWS" -> START
            command == "ATSPB" -> current.copy(user = true)
            command.startsWith("ATSP") -> current.copy(user = false)
            command.startsWith("ATSH") -> current.copy(header = command.removePrefix("ATSH"))
            command.startsWith("ATCEA") -> current.copy(extended = command.removePrefix("ATCEA"))
            else -> current
        }
    }
}
