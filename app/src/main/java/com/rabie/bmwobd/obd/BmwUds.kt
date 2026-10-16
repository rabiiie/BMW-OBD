package com.rabie.bmwobd.obd

import java.util.Locale

/**
 * Lo que se le pregunta a la centralita del motor de un BMW de la serie F (F20 118d, motor B47)
 * para saber si el adaptador llega y si la tabla es la suya. Esas centralitas hablan UDS, no el
 * KWP2000 del N47: las medidas se piden con 22 y dos bytes de identificador y contestan 62 con el
 * identificador y el valor. Los identificadores y las formulas son los de la tabla SG_FUNKTIONEN
 * de la descripcion d70bx7a0 (N47O1, N57O1, B47 U0/O0; DDE 7.01 y 7.41). Solo son lecturas.
 */
object BmwUds {

    class Query(val request: String, val name: String)

    /** Primero la identificacion; refrigerante y revoluciones salen tambien por OBD y sirven para comparar. */
    val QUERIES = listOf(
        Query(IDENT, "Identificación (índice de la descripción)"),
        Query("22461B", "Refrigerante"),
        Query("22427F", "Revoluciones"),
        Query("2244BE", "Hollín"),
        Query("2244ED", "Presión diferencial del filtro"),
        Query(FAULTS, "Averías presentes y guardadas"),
    )

    const val IDENT = "22F150"
    const val FAULTS = "19020C"

    private class Formula(val unit: String, val factor: Double, val offset: Double)

    private val FORMULAS = mapOf(
        0x461B to Formula("°C", 0.01, -100.0),
        0x427F to Formula("rpm", 0.091554, 0.0),
        0x44BE to Formula("g", 0.015259, 0.0),
        0x44ED to Formula("hPa", 0.045777, -1000.0),
    )

    /**
     * Lo que dice una respuesta, en una linea, a partir de sus bytes desde el servicio. Null si no
     * es respuesta a esa consulta.
     */
    fun describe(request: String, payload: IntArray): String? {
        val service = request.take(2).toInt(16)
        if (payload.size >= 3 && payload[0] == NEGATIVE && payload[1] == service) {
            return "rechazada, código %02X (%s)".format(payload[2], NRC[payload[2]] ?: "sin texto")
        }
        if (payload.isEmpty() || payload[0] != service + POSITIVE_OFFSET) return null
        return when (service) {
            READ -> readText(request, payload)
            DTC -> faultsText(payload)
            else -> hex(payload)
        }
    }

    private fun readText(request: String, payload: IntArray): String? {
        val id = request.drop(2).toInt(16)
        if (payload.size < 3 || payload[1] * 256 + payload[2] != id) return null
        val data = payload.drop(3)
        val formula = FORMULAS[id] ?: return hex(data) + "  [" + ascii(data) + "]"
        if (data.size < 2) return "sin dato: " + hex(data)
        val raw = data.take(2).fold(0L) { acc, byte -> acc * 256 + byte }
        return "%s -> %s %s".format(hex(data), String.format(Locale.US, "%.2f", raw * formula.factor + formula.offset), formula.unit)
    }

    /**
     * 59 02, la mascara que se pidio y, por averia, tres bytes de codigo y uno de estado. El bit 0
     * del estado es que falla ahora; el 3, que esta confirmada y guardada.
     */
    private fun faultsText(payload: IntArray): String {
        val records = payload.drop(3).chunked(4).filter { it.size == 4 }
        if (records.isEmpty()) return "ninguna"
        return "${records.size}: " + records.joinToString(", ") { r ->
            "%02X%02X%02X %s".format(r[0], r[1], r[2], if (r[3] and 0x01 != 0) "presente" else "guardada")
        }
    }

    private fun hex(bytes: List<Int>) = bytes.joinToString("") { "%02X".format(it) }
    private fun hex(bytes: IntArray) = hex(bytes.toList())

    private fun ascii(bytes: List<Int>) = bytes.joinToString("") { if (it in 0x20..0x7E) it.toChar().toString() else "." }

    private const val READ = 0x22
    private const val DTC = 0x19
    private const val NEGATIVE = 0x7F
    private const val POSITIVE_OFFSET = 0x40

    /** Los codigos de rechazo de UDS que pueden salir aqui. */
    private val NRC = mapOf(
        0x11 to "servicio no admitido",
        0x12 to "subfunción no admitida",
        0x13 to "longitud o formato incorrectos",
        0x22 to "condiciones no correctas",
        0x31 to "identificador fuera de rango",
        0x33 to "acceso de seguridad denegado",
        0x7E to "subfunción no admitida en esta sesión",
        0x7F to "servicio no admitido en esta sesión",
    )
}
