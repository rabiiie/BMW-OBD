package com.rabie.bmwobd.obd

import java.util.Locale

/**
 * Lo que se le pregunta a la centralita del motor de un BMW de la serie F (F20 118d, motor B47):
 * si el adaptador llega y la tabla es la suya, y de una vez kilometros, horas, cambio y estado del
 * filtro, regeneraciones, turbo, aire, EGR, inyeccion y averias. Esas centralitas hablan UDS, no el
 * KWP2000 del N47: las medidas se piden con 22 y dos bytes de identificador y contestan 62 con el
 * identificador y el valor. Los identificadores y las formulas son los de la tabla SG_FUNKTIONEN
 * de la descripcion d70bx7a0 (N47O1, N57O1, B47 U0/O0; DDE 7.01 y 7.41). Solo son lecturas.
 */
object BmwUds {

    class Query(val request: String, val name: String)

    /**
     * Una medida de la tabla: identificador, nombre, unidad y formula (valor x [factor] + [offset]),
     * con [size] bytes, el mayor primero. Las de metros o segundos van pasadas a km u horas en el factor.
     */
    private class Measure(val id: Int, val name: String, val unit: String, val factor: Double, val offset: Double = 0.0, val size: Int = 2)

    private const val METERS_TO_KM = 0.001
    private const val SECONDS_TO_HOURS = 1.0 / 3600.0

    private val MEASURES = listOf(
        // Refrigerante y revoluciones salen tambien por OBD y sirven para comparar.
        Measure(0x461B, "Refrigerante", "°C", 0.01, -100.0),
        Measure(0x427F, "Revoluciones", "rpm", 0.091554),
        Measure(0x46B1, "Cuentakilómetros", "km", 1.0, size = 4),
        Measure(0x4BC4, "Horas de motor", "h", SECONDS_TO_HOURS, size = 4),
        Measure(0x4C79, "Km desde el último cambio del filtro de partículas", "km", 10.0, size = 4),
        Measure(0x44D6, "Consumo medio desde el último cambio del filtro", "l/100 km", 0.05),
        Measure(0x44B8, "Regeneraciones completas", "", 1.0),
        Measure(0x44C7, "Km medios entre regeneraciones", "km", 1.0),
        Measure(0x44BF, "Km desde la última regeneración completa", "km", METERS_TO_KM, size = 4),
        Measure(0x44D1, "Cuentakilómetros en la última regeneración", "km", METERS_TO_KM, size = 4),
        Measure(0x44D2, "Cuentakilómetros en la penúltima regeneración", "km", METERS_TO_KM, size = 4),
        Measure(0x44D3, "Cuentakilómetros en la tercera regeneración hacia atrás", "km", METERS_TO_KM, size = 4),
        Measure(0x44D4, "Cuentakilómetros en la cuarta regeneración hacia atrás", "km", METERS_TO_KM, size = 4),
        Measure(0x44D5, "Cuentakilómetros en la quinta regeneración hacia atrás", "km", METERS_TO_KM, size = 4),
        Measure(0x44A9, "Horas regenerando en total", "h", 23.002976 * SECONDS_TO_HOURS),
        Measure(0x44BC, "Gasóleo desde la última regeneración completa", "l", 0.01),
        Measure(0x44BE, "Hollín (el que usa la centralita)", "g", 0.015259),
        Measure(0x44C1, "Hollín calculado (modelo)", "g", 0.01),
        Measure(0x44B9, "Hollín medido (corregido)", "g", 0.01),
        Measure(0x44BD, "Ceniza de aceite", "g", 0.015259),
        Measure(0x44C5, "Resistencia del filtro (filtrada)", "hPa/(m³/h)", 0.0001),
        Measure(0x44ED, "Presión diferencial del filtro", "hPa", 0.045777, -1000.0),
        Measure(0x44EF, "Escape antes del filtro", "°C", 0.031281, -50.0),
        Measure(0x44BB, "Petición de regeneración (código)", "", 1.0, size = 1),
        Measure(0x44C0, "Interrupción de la regeneración (código)", "", 1.0),
        Measure(0x44C2, "Bloqueo de la regeneración (código)", "", 1.0, size = 1),
        Measure(0x4520, "Aceite: km del contador de servicio", "km", 10.0),
        Measure(0x4517, "Aceite: temperatura", "°C", 0.01, -100.0),
        Measure(0x42C8, "Presión turbo pedida", "hPa", 0.091554),
        Measure(0x4841, "Presión turbo real", "hPa", 0.15259),
        Measure(0x4CC4, "Actuador del turbo: posición", "%", 0.004578, -100.0),
        Measure(0x47DD, "Aire por cilindro", "mg", 0.038911),
        Measure(0x4843, "Aire tras el intercooler", "°C", 0.01, -100.0),
        Measure(0x4C97, "EGR: posición", "%", 0.004578, -100.0),
        Measure(0x45D4, "Gasóleo inyectado", "mg", 0.003052, -100.0),
        Measure(0x4748, "Presión del raíl", "bar", 0.045777),
    )

    private val BY_ID = MEASURES.associateBy { it.id }

    /** Primero la identificacion; si no contesta, esa forma de llegar no sirve. Las averias al final. */
    val QUERIES = listOf(Query(IDENT, "Identificación (índice de la descripción)")) +
        MEASURES.map { Query("22%04X".format(it.id), it.name) } +
        Query(FAULTS, "Averías presentes y guardadas")

    const val IDENT = "22F150"
    const val FAULTS = "19020C"

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
        val measure = BY_ID[id] ?: return hex(data) + "  [" + ascii(data) + "]"
        if (data.size < measure.size) return "sin dato: " + hex(data)
        val raw = data.take(measure.size).fold(0L) { acc, byte -> acc * 256 + byte }
        val value = String.format(Locale.US, "%.2f", raw * measure.factor + measure.offset)
        return "%s -> %s".format(hex(data), if (measure.unit.isEmpty()) value else "$value ${measure.unit}")
    }

    /**
     * 59 02, la mascara que se pidio y, por averia, tres bytes de codigo y uno de estado. El bit 0
     * del estado es que falla ahora; el 3, que esta confirmada y guardada.
     */
    private fun faultsText(payload: IntArray): String {
        val records = payload.drop(3).chunked(4).filter { it.size == 4 }
        if (records.isEmpty()) return "ninguna"
        return "${records.size}: " + records.joinToString(", ") { r ->
            val code = "%02X%02X%02X".format(r[0], r[1], r[2])
            "$code ${if (r[3] and 0x01 != 0) "presente" else "guardada"}" + (FAULT_TEXTS[code]?.let { " ($it)" } ?: "")
        }
    }

    /** Los textos de averia de la tabla FORTTEXTE de la d70bx7a0, en aleman como los da BMW. */
    private val FAULT_TEXTS: Map<String, String> by lazy {
        val stream = BmwUds::class.java.getResourceAsStream(FAULT_FILE) ?: return@lazy emptyMap()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line -> line.split('\t').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toMap()
        }
    }

    private const val FAULT_FILE = "/averias_b47.tsv"

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
