package com.rabie.bmwobd.obd

import java.net.SocketTimeoutException
import java.util.Locale

/**
 * Sondeo de la centralita del motor de un BMW con D-CAN por su protocolo propio, para saber en
 * una sola visita al coche si el adaptador llega y como hay que pedir las medidas.
 *
 * La forma de hablar es la de EdiabasLib (Deep OBD) con un ELM327: el adaptador manda tramas CAN
 * tal cual y la app pone la direccion de la centralita, la longitud y el control de flujo. Si el
 * adaptador no admite ese modo se prueban otros dos. Las direcciones de las medidas salen de la
 * tabla MESSWERTETAB de la descripcion de la DDE 7.0 del N47 (d70n47b0); refrigerante, bateria,
 * rail y admision se piden tambien por OBD justo antes, para comparar.
 *
 * Solo pide datos. La consulta 2C define en la centralita una lista temporal de medidas, que es
 * lo que hace cualquier programa de diagnostico al enseñar valores en vivo.
 */
class BmwProbe(private val elm: Elm327, private val log: (String) -> Unit) {

    class Frame(val id: Int, val bytes: IntArray)

    private class Measure(
        val id: Int,
        val name: String,
        val unit: String,
        val factor: Double,
        val offset: Double = 0.0,
    )

    private var countSuffix = true
    private var heard = false

    suspend fun run() {
        for (link in LINKS) {
            log("## Sondeo BMW: ${link.name}")
            if (!setUp(link)) {
                log("## El adaptador no admite este modo")
                continue
            }
            heard = false
            countSuffix = link.raw
            if (link.raw) talk() else talkFormatted()
            if (heard) return
            log("## Sin respuesta de la centralita en este modo")
        }
        log("## Sondeo BMW: la centralita no ha contestado en ningún modo")
    }

    private suspend fun setUp(link: Link): Boolean {
        var accepted = true
        for (command in link.commands) {
            val answer = command(command)
            if (command in link.required && !answer.contains("OK")) accepted = false
        }
        return accepted
    }

    private suspend fun talk() {
        val ident = exchange(IDENT)
        describe("Identificación", ident)
        val coolant = measure(MEASURES.first())
        if (!heard) return
        val directWorks = coolant != null && coolant.size >= 2 && coolant[0] == POSITIVE_DEFINE
        if (directWorks) {
            for (measure in MEASURES.drop(1)) measure(measure)
            describe("Refrigerante y batería juntos", exchange(DEFINE_DIRECT + word(0x0547) + word(0x012C)))
        } else {
            log("## La forma directa (2C 10) no vale: se prueba definir y leer (2C F0, 21 F0)")
            for (measure in MEASURES.take(DEFINED_MEASURES)) defineAndRead(measure)
        }
    }

    /** El modo en el que el adaptador pone la direccion y trocea: solo dice si hay comunicacion. */
    private suspend fun talkFormatted() {
        for (request in listOf("1A80", "2C100547")) {
            val answer = command(request)
            if (frames(answer).isNotEmpty() || HEX.matches(answer.lines().firstOrNull().orEmpty())) heard = true
        }
    }

    private suspend fun measure(measure: Measure): IntArray? {
        val answer = exchange(DEFINE_DIRECT + word(measure.id))
        if (answer != null && answer.size >= 3 && answer[0] == POSITIVE_DEFINE) {
            report(measure, answer.drop(2))
        } else {
            describe("%04X %s".format(measure.id, measure.name), answer)
        }
        return answer
    }

    /**
     * La otra forma que da la documentacion: borrar la lista, definirla con la medida y leerla.
     * La definicion no cabe en una trama; se prueba con y sin el byte de posicion en el registro.
     */
    private suspend fun defineAndRead(measure: Measure) {
        for (tail in listOf(intArrayOf(0x01), intArrayOf())) {
            describe("Borrar lista", exchange(intArrayOf(0x2C, 0xF0, 0x04)))
            val define = intArrayOf(0x2C, 0xF0, 0x02, 0x01, 0x02) + word(measure.id) + tail
            val defined = exchangeLong(define)
            describe("Definir %04X (%d bytes)".format(measure.id, define.size), defined)
            if (defined == null || defined.firstOrNull() != POSITIVE_DEFINE) continue
            val read = exchange(intArrayOf(0x21, 0xF0))
            if (read != null && read.size >= 4 && read[0] == POSITIVE_READ) {
                report(measure, read.drop(2))
                return
            }
            describe("Leer %04X".format(measure.id), read)
        }
    }

    private fun report(measure: Measure, data: List<Int>) {
        val raw = data.fold(0L) { acc, byte -> acc * 256 + byte }
        val value = raw * measure.factor + measure.offset
        log(
            "## %04X %s: %s -> %s %s".format(
                measure.id, measure.name, hex(data), String.format(Locale.US, "%.2f", value), measure.unit,
            ),
        )
    }

    private fun describe(what: String, payload: IntArray?) {
        val text = when {
            payload == null -> "sin respuesta"
            payload.firstOrNull() == NEGATIVE -> "rechazada: " + hex(payload.toList())
            else -> hex(payload.toList()) + "  [" + ascii(payload) + "]"
        }
        log("## $what: $text")
    }

    /** Manda una consulta que cabe en una trama y devuelve la respuesta entera, ya recompuesta. */
    private suspend fun exchange(request: IntArray): IntArray? {
        require(request.size <= MAX_SINGLE) { "consulta demasiado larga para una trama" }
        return receive(send(intArrayOf(ECU, request.size) + request, single = true))
    }

    /** Manda una consulta de dos tramas: primera, espera del control de flujo y segunda. */
    private suspend fun exchangeLong(request: IntArray): IntArray? {
        require(request.size in (MAX_SINGLE + 1)..(FIRST_DATA + MAX_SINGLE)) { "solo dos tramas" }
        val first = intArrayOf(ECU, 0x10, request.size) + request.take(FIRST_DATA)
        val flow = fromEcu(send(first, single = true)).firstOrNull { it.bytes[1] shr 4 == FLOW_CONTROL }
        if (flow == null) return null
        return receive(send(intArrayOf(ECU, 0x21) + request.drop(FIRST_DATA), single = true))
    }

    private suspend fun receive(frames: List<Frame>): IntArray? {
        val first = fromEcu(frames).firstOrNull { it.bytes[1] shr 4 <= FIRST_FRAME } ?: return null
        if (first.bytes[1] shr 4 == SINGLE_FRAME) {
            return first.bytes.drop(2).take(first.bytes[1] and 0x0F).toIntArray()
        }
        val total = (first.bytes[1] and 0x0F) * 256 + first.bytes[2]
        val data = first.bytes.drop(3).toMutableList()
        // Control de flujo: que mande el resto seguido. El adaptador escucha hasta agotar su espera.
        val rest = fromEcu(frames - first) + fromEcu(send(intArrayOf(ECU, 0x30, 0x00, 0x00), single = false))
        for (frame in rest) if (frame.bytes[1] shr 4 == CONSECUTIVE) data += frame.bytes.drop(2)
        return data.take(total).toIntArray()
    }

    private fun fromEcu(frames: List<Frame>): List<Frame> {
        val mine = frames.filter { it.id == ECU_ID && it.bytes.size >= 3 && it.bytes[0] == TESTER }
        if (mine.isNotEmpty()) heard = true
        return mine
    }

    /**
     * Manda una trama de 8 bytes. Con [single] se pide al adaptador que vuelva en cuanto llegue
     * una respuesta, con la cifra al final; si no la admite en este modo, se deja de usar.
     */
    private suspend fun send(bytes: IntArray, single: Boolean): List<Frame> {
        val text = hex((bytes.toList() + List(FRAME_BYTES) { 0 }).take(FRAME_BYTES))
        val answer = command(if (single && countSuffix) text + "1" else text)
        if (single && countSuffix && answer.contains("?")) {
            countSuffix = false
            log("## El adaptador no admite la cifra de respuestas esperadas en este modo")
            return frames(command(text))
        }
        return frames(answer)
    }

    private suspend fun command(text: String): String = try {
        elm.send(text, TIMEOUT_MS)
    } catch (e: SocketTimeoutException) {
        log("!! Sin respuesta a $text")
        ""
    }

    private class Link(val name: String, val raw: Boolean, val commands: List<String>, val required: Set<String>)

    companion object {
        private const val ECU = 0x12
        private const val TESTER = 0xF1
        private const val ECU_ID = 0x600 + ECU
        private const val TIMEOUT_MS = 4_000L
        private const val FRAME_BYTES = 8
        private const val MAX_SINGLE = 6
        private const val FIRST_DATA = 5
        private const val SINGLE_FRAME = 0
        private const val FIRST_FRAME = 1
        private const val CONSECUTIVE = 2
        private const val FLOW_CONTROL = 3
        private const val NEGATIVE = 0x7F
        private const val POSITIVE_DEFINE = 0x6C
        private const val POSITIVE_READ = 0x61
        private const val DEFINED_MEASURES = 4

        private val HEX = Regex("^[0-9A-F]{4,}$")
        private val FRAME = Regex("^[0-9A-F]{9,19}$")

        private val IDENT = intArrayOf(0x1A, 0x80)
        private val DEFINE_DIRECT = intArrayOf(0x2C, 0x10)

        private val COMMON = listOf("ATAT0", "ATSTFF", "ATAL", "ATH1", "ATS0", "ATL0")

        private val LINKS = listOf(
            Link(
                "tramas crudas, protocolo de usuario (como Deep OBD)",
                raw = true,
                commands = listOf("ATD", "ATE0", "ATSH6F1", "ATCF600", "ATCM700", "ATPBC001", "ATSPB") + COMMON,
                required = setOf("ATPBC001", "ATSPB"),
            ),
            Link(
                "tramas crudas sobre el protocolo CAN normal",
                raw = true,
                commands = listOf("ATD", "ATE0", "ATSP6", "ATCAF0", "ATSH6F1", "ATCF600", "ATCM700") + COMMON,
                required = setOf("ATCAF0"),
            ),
            Link(
                "direccionamiento a cargo del adaptador, con espera larga",
                raw = false,
                commands = listOf(
                    "ATD", "ATE0", "ATSP6", "ATSH6F1", "ATCEA12", "ATFCSH6F1", "ATFCSD123000", "ATFCSM1", "ATCRA612",
                ) + COMMON,
                required = setOf("ATCEA12"),
            ),
        )

        // La primera es la de comprobacion: el refrigerante tambien se lee por OBD.
        private val MEASURES = listOf(
            Measure(0x0547, "Refrigerante", "°C", 0.01, -100.0),
            Measure(0x012C, "Batería", "mV", 0.389105),
            Measure(0x0674, "Raíl real", "bar", 0.045777),
            Measure(0x076D, "Presión de admisión", "hPa", 0.091554),
            Measure(0x0A8C, "Aceite (cárter)", "°C", 0.01, -100.0),
            Measure(0x0458, "Aceite filtrada", "°C", 0.01, -100.0),
            Measure(0x044F, "Nivel de aceite", "mm", 0.292969),
            Measure(0x03EA, "Hollín en el filtro", "g", 0.015259),
            Measure(0x03EB, "Desde la última regeneración", "m", 1.0),
            Measure(0x03F3, "Intervalo medio entre regeneraciones", "km", 1.0),
            Measure(0x043C, "Presión diferencial del filtro", "hPa", 0.045777, -1000.0),
            Measure(0x05AA, "Estado de regeneración", "", 1.0),
            Measure(0x041B, "Escape antes del filtro", "(crudo)", 1.0),
            Measure(0x0500, "Cantidad inyectada", "mg/emb", 0.003052, -100.0),
            Measure(0x0641, "Raíl pedido", "bar", 0.045777),
            Measure(0x01F4, "Turbo pedido", "hPa", 0.091554),
            Measure(0x0BF0, "Actuador del turbo", "%", 0.001526),
            Measure(0x0385, "Temperatura del gasóleo", "°C", 0.01, -50.0),
            Measure(0x0D16, "Depósito", "(crudo)", 1.0),
            Measure(0x0402, "Consumo medio desde el cambio de filtro", "L/100", 0.05),
            Measure(0x16B2, "Kilómetros", "km", 1.0),
        )

        private fun word(value: Int) = intArrayOf(value shr 8, value and 0xFF)

        private fun hex(bytes: List<Int>) = bytes.joinToString("") { "%02X".format(it) }

        private fun ascii(bytes: IntArray) =
            bytes.joinToString("") { if (it in 0x20..0x7E) it.toChar().toString() else "." }

        /** Las tramas de una respuesta con cabeceras y sin espacios: tres cifras de id y los bytes. */
        fun frames(response: String): List<Frame> = response.lines().mapNotNull { line ->
            val text = line.trim()
            if (!FRAME.matches(text) || text.length % 2 == 0) return@mapNotNull null
            val body = text.drop(3)
            Frame(text.take(3).toInt(16), IntArray(body.length / 2) { body.substring(it * 2, it * 2 + 2).toInt(16) })
        }
    }
}
