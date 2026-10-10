package com.rabie.bmwobd.scan

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeSet

/**
 * El barrido de todo lo que la centralita del motor de BMW contesta por el adaptador. La primera
 * pasada pregunta por todas las consultas de lectura conocidas y por todas las direcciones de
 * medidas; las siguientes repiten una y otra vez solo las que contestaron, para ver como cambian
 * con el motor. Las consultas se numeran por su posicion en la primera pasada.
 */
class ScanPlan(next: Int = 0, found: Collection<Int> = emptyList(), pass: Int = 1) {

    var next: Int = next
        private set
    var pass: Int = pass
        private set

    private val found = TreeSet(found)
    private var order: List<Int> = if (pass > 1) this.found.toList() else emptyList()
    private var cursor = 0
    private var asked = -1

    val answering: Int get() = found.size
    val total: Int get() = if (pass == 1) SIZE else order.size
    val done: Int get() = if (pass == 1) next else cursor
    fun found(): List<Int> = found.toList()

    /** La siguiente consulta, o null si la primera pasada acabo sin que contestara ninguna. */
    fun take(): String? {
        if (pass == 1) {
            if (next < SIZE) {
                asked = next++
                return query(asked)
            }
            pass = 2
            order = found.toList()
            cursor = 0
        }
        if (order.isEmpty()) return null
        if (cursor >= order.size) {
            pass++
            cursor = 0
        }
        asked = order[cursor++]
        return query(asked)
    }

    /** La ultima consulta entregada ha contestado. */
    fun answered() {
        if (asked >= 0) found += asked
    }

    companion object {
        const val FIRST_ADDRESS = 0x0000
        const val LAST_ADDRESS = 0x1BFF

        /**
         * Otras consultas de lectura del protocolo de la centralita: identificacion (1A), bloques
         * de datos por identificador corto (21) y largo (22), y averias por estado (18). Ninguna
         * escribe, borra ni activa nada.
         */
        val SERVICES: List<String> =
            (0x80..0x9F).map { "1A%02X".format(it) } +
                listOf("1802FFFF", "1800FFFF") +
                (0x00..0xFF).map { "21%02X".format(it) } +
                (0x00..0xFF).map { "2240%02X".format(it) } +
                (0x00..0xFF).map { "2225%02X".format(it) } +
                (0x80..0x9F).map { "22F1%02X".format(it) }

        val SIZE: Int = SERVICES.size + (LAST_ADDRESS - FIRST_ADDRESS + 1)

        fun query(index: Int): String =
            if (index < SERVICES.size) SERVICES[index] else MEASURE + "%04X".format(FIRST_ADDRESS + index - SERVICES.size)

        const val MEASURE = "2C10"

        /** Como empieza la respuesta afirmativa a [query]: el servicio mas 0x40 y lo que repite. */
        fun answerPrefix(query: String): String {
            val service = query.take(2)
            val positive = "%02X".format(service.toInt(16) + 0x40)
            return positive + when (service) {
                "2C" -> "10"
                "21", "1A" -> query.substring(2, 4)
                "22" -> query.substring(2, 6)
                else -> ""
            }
        }
    }
}

data class ScanSaved(val active: Boolean, val next: Int, val pass: Int, val found: List<Int>) {
    val fresh: Boolean get() = next == 0 && found.isEmpty()
}

data class ScanProgress(
    val active: Boolean = false,
    val pass: Int = 1,
    val done: Int = 0,
    val total: Int = 0,
    val answering: Int = 0,
)

/** Donde se guardan el resultado del barrido de cada coche y por donde va. */
class ScanStore(private val dir: File) {

    fun file(vehicleKey: String) = File(dir, "escaneo_$vehicleKey.csv")

    private fun stateFile(vehicleKey: String) = File(dir, "estado_$vehicleKey.txt")

    fun load(vehicleKey: String): ScanSaved {
        val lines = runCatching { stateFile(vehicleKey).readLines() }.getOrDefault(emptyList())
        val values = lines.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return ScanSaved(
            active = values["activo"] == "si",
            next = values["siguiente"]?.toIntOrNull() ?: 0,
            pass = values["pasada"]?.toIntOrNull() ?: 1,
            found = values["contestan"]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
        )
    }

    fun save(vehicleKey: String, plan: ScanPlan, active: Boolean) {
        dir.mkdirs()
        stateFile(vehicleKey).writeText(
            listOf(
                "activo=" + if (active) "si" else "no",
                "siguiente=${plan.next}",
                "pasada=${plan.pass}",
                "contestan=" + plan.found().joinToString(","),
            ).joinToString("\n"),
        )
    }

    /** Borra el resultado y el punto de avance, para empezar el barrido de cero. */
    fun reset(vehicleKey: String) {
        file(vehicleKey).delete()
        stateFile(vehicleKey).delete()
    }

    fun writer(vehicleKey: String): ScanWriter {
        dir.mkdirs()
        return ScanWriter(file(vehicleKey))
    }
}

/**
 * Una fila por respuesta: cuando, en que pasada, la consulta, lo que contesto y como iba el motor
 * en ese momento. Con eso se puede deducir despues que mide cada direccion.
 */
class ScanWriter(file: File) {

    private val isNew = !file.exists() || file.length() == 0L
    private val writer = BufferedWriter(FileWriter(file, true))
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var rows = 0

    init {
        if (isNew) {
            writer.write("fecha,pasada,consulta,respuesta," + CONTEXT.joinToString(",") { it.second })
            writer.newLine()
        }
    }

    fun append(pass: Int, query: String, answer: IntArray, values: Map<Int, Double>, now: Date = Date()) {
        val line = StringBuilder()
            .append(clock.format(now)).append(',').append(pass).append(',').append(query).append(',')
            .append(answer.joinToString("") { "%02X".format(it) })
        for ((id, _) in CONTEXT) {
            line.append(',')
            values[id]?.let { line.append(String.format(Locale.US, "%.1f", it)) }
        }
        writer.write(line.toString())
        writer.newLine()
        if (++rows % FLUSH_EVERY == 0) writer.flush()
    }

    fun close() {
        runCatching { writer.close() }
    }

    companion object {
        private const val FLUSH_EVERY = 20

        // Medidas del OBD estandar que acompañan a cada respuesta.
        val CONTEXT = listOf(
            0x0C to "rpm", 0x0D to "velocidad", 0x04 to "carga", 0x05 to "refrigerante", 0x49 to "pedal",
            0x0B to "admision_kpa", 0x23 to "rail_bar", 0x10 to "aire_gs", 0x2C to "egr", 0x3C to "escape",
            0x42 to "tension",
        )
    }
}
