package com.rabie.bmwobd.obd

import java.util.Locale

/**
 * Lecturas de servicio de la centralita del motor que ISTA hace con sus trabajos de d70n47b0 y la
 * lectura normal no: la codificacion (LESEN_CODIERWERTE), las correcciones guardadas (ABGLEICH_LESEN),
 * el aprendizaje de la EGR y de las mariposas de turbulencia (STATUS_OFFSETLERNEN), la correccion de
 * cada inyector (ABGLEICH_NMK_LESEN), la vigilancia del enfriador de la EGR (ABGLEICH_LESEN_AGRKUEHLER)
 * y los datos del filtro (ABGLEICH_CSF_LESEN).
 *
 * Los telegramas y donde va cada valor salen de ejecutar esos trabajos del d70n47b0.prg con EdiabasLib
 * contra un coche falso (docs/jobtrace): son los mismos bytes que manda ISTA. Todas son de lectura.
 */
class ServiceQuery(
    val name: String,
    val query: String,
    val answer: String,
    /** Un solo valor al principio de la respuesta: cuantos bytes, la escala y la unidad. */
    val bytes: Int? = null,
    val factor: Double = 1.0,
    val unit: String = "",
    val note: String = "",
    /** Bits con nombre: el byte 0 es el penultimo de la respuesta y el 1 el ultimo, como los lee ISTA. */
    val bits: List<Triple<Int, Int, String>> = emptyList(),
    /** Varios valores en la misma respuesta. */
    val fields: List<ServiceField> = emptyList(),
)

/** Un valor dentro de una respuesta: desde que byte tras el eco, cuantos, su escala y si lleva signo. */
class ServiceField(
    val name: String,
    val at: Int,
    val bytes: Int,
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String = "",
    val signed: Boolean = false,
) {
    fun value(data: List<Int>): Double? {
        if (data.size < at + bytes) return null
        var v = data.subList(at, at + bytes).fold(0L) { acc, b -> acc * 256 + b }
        if (signed && v >= 1L shl (8 * bytes - 1)) v -= 1L shl (8 * bytes)
        return v * factor + offset
    }
}

/** Lo que contesto la centralita a una [ServiceQuery]. [data] va sin el eco de la consulta. */
class ServiceRead(val query: ServiceQuery, val data: List<Int>?, val refusal: Int?) {

    val raw: String get() = data?.joinToString(" ") { "%02X".format(it) } ?: ""

    /** El valor de [ServiceQuery.bytes], con signo como ISTA. */
    val value: Double?
        get() {
            val size = query.bytes ?: return null
            return data?.let { ServiceField("", 0, size, query.factor, signed = true).value(it) }
        }

    val valueText: String?
        get() = value?.let { format(it, query.unit, 4) }

    /** Los valores de [ServiceQuery.fields] con su unidad, o vacio si la respuesta no los trae. */
    val fieldTexts: List<Pair<String, String>>
        get() {
            val d = data ?: return emptyList()
            return query.fields.mapNotNull { f -> f.value(d)?.let { f.name to format(it, f.unit, 2) } }
        }

    private fun format(v: Double, unit: String, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", v).trimEnd('0').trimEnd('.').replace('.', ',') + if (unit.isEmpty()) "" else " $unit"

    /** Los bits con nombre que estan a 1. */
    val activeBits: List<String>
        get() {
            val d = data?.takeIf { it.size >= 2 } ?: return emptyList()
            return query.bits.filter { (byte, mask, _) -> d[d.size - 2 + byte] and mask != 0 }.map { it.third }
        }

    val status: String
        get() = when {
            refusal != null -> "Rechazado: " + BmwIma.refusalText(refusal)
            data == null -> "Sin respuesta"
            data.isEmpty() -> "Contesta sin dato"
            else -> raw
        }
}

/**
 * El estado de la regeneracion como lo da ISTA (STATUS_REGENERATION_CSF), calculado de las mismas
 * medidas: pedida si 0404 es 4, 5 o 6; en marcha si el byte bajo del modo del motor (05AA) es 2 o
 * mas, lo que incluye el modo de proteccion 0x00210002; bloqueada si 03EE no es 0. Sacado del .prg
 * probando todos los valores contra el trabajo.
 */
data class IstaRegen(val requested: Boolean, val running: Boolean, val locked: Boolean) {
    companion object {
        fun of(request: Double?, mode: Double?, lock: Double?): IstaRegen? {
            if (request == null || mode == null || lock == null || request.isNaN() || mode.isNaN() || lock.isNaN()) return null
            return IstaRegen(
                requested = request.toInt() in 4..6,
                running = (mode.toLong() and 0xFF) >= 2,
                locked = lock.toInt() != 0,
            )
        }
    }
}

object BmwService {

    private const val ADJUST = 0.00012207
    private const val PERCENT = 0.012207

    private val OFFSET_BITS = listOf(
        Triple(1, 0x01, "aprendizaje en marcha"),
        Triple(1, 0x02, "válvula que se agarrota"),
        Triple(1, 0x04, "deriva a largo plazo"),
        Triple(1, 0x08, "deriva a corto plazo"),
        Triple(1, 0x10, "aprendizaje terminado"),
        Triple(1, 0x20, "últimos valores guardados"),
        Triple(1, 0x40, "primeros valores guardados"),
        Triple(1, 0x80, "aprendizaje cortado al apagar"),
        Triple(0, 0x01, "fallo de la pieza"),
        Triple(0, 0x02, "cable cortado"),
    )

    private val OFFSET_FIELDS = listOf(
        ServiceField("Valor aprendido, primero", 0, 2, PERCENT, unit = "%", signed = true),
        ServiceField("Valor aprendido, último", 2, 2, PERCENT, unit = "%", signed = true),
        ServiceField("Arranques desde el último aprendizaje", 4, 2, signed = true),
        ServiceField("Estado del aprendizaje (código)", 6, 2, signed = true),
    )

    private const val FACTOR_NOTE = "Factor de corrección: 1 es sin corregir."

    private fun adjust(name: String, lid: Int, bytes: Int = 2, factor: Double = ADJUST, unit: String = "", note: String = FACTOR_NOTE) =
        ServiceQuery(name, "30%02X01".format(lid), "70%02X01".format(lid), bytes, factor, unit, note)

    private fun nmk(): List<ServiceField> {
        val fields = ArrayList<ServiceField>()
        listOf(400, 700, 1000).forEachIndexed { i, bar -> fields += ServiceField("Ciclos de aprendizaje a $bar bar", 4 + 2 * i, 2, signed = true) }
        listOf(400, 700, 1000).forEachIndexed { i, bar ->
            for (cyl in 1..4) fields += ServiceField("Cilindro $cyl a $bar bar", 10 + 8 * i + 2 * (cyl - 1), 2, 0.4, signed = true)
        }
        return fields
    }

    // Las posiciones de la tabla CSF_WERTE de BMW cuentan desde la cabecera: tras el eco es POS - 4.
    private val FILTER_FIELDS = listOf(
        ServiceField("Km desde el último cambio de filtro", 2, 2, unit = "km"),
        ServiceField("Cuentakilómetros en la última regeneración completa", 8, 4, 0.001, unit = "km"),
        ServiceField("Tiempo para pedir regeneración por contador", 12, 4, 0.01, unit = "s"),
        ServiceField("Tiempo para pedir regeneración por protección del motor", 16, 4, unit = "s"),
        ServiceField("Regeneración que faltó en el último ciclo", 20, 4, unit = "s"),
        ServiceField("Momento de la última regeneración completa", 24, 4, unit = "s"),
        ServiceField("Tiempo de bloqueo que queda", 36, 4, 0.01, unit = "s"),
        ServiceField("Hollín (cálculo por tramos)", 40, 2, 0.01, unit = "g"),
        ServiceField("Hollín (cálculo continuo)", 42, 2, 0.01, unit = "g"),
        ServiceField("Gasóleo desde la última regeneración", 44, 2, 0.01, unit = "l"),
        ServiceField("Regeneraciones completas", 46, 2),
        ServiceField("Regeneraciones pedidas", 48, 2),
        ServiceField("Regeneración sin terminar (código)", 50, 2),
        ServiceField("Gasóleo desde el cambio de filtro", 52, 4, 0.01, unit = "l"),
        ServiceField("Máxima antes del catalizador", 56, 2, 0.1, -273.1, "°C"),
        ServiceField("Máxima antes del filtro", 58, 2, 0.1, -273.1, "°C"),
        ServiceField("Máxima antes del catalizador en regeneración", 60, 2, 0.1, -273.1, "°C"),
        ServiceField("Máxima antes del filtro en regeneración", 62, 2, 0.1, -273.1, "°C"),
    )

    val QUERIES: List<ServiceQuery> = listOf(
        ServiceQuery(
            "Datos del filtro (los de ISTA)", "305001", "705001",
            fields = FILTER_FIELDS,
            note = "Las máximas son las más altas que ha visto la centralita; no sé desde cuándo cuentan.",
        ),
        ServiceQuery(
            "Codificación (con límite de tiempo)", "223900", "623900",
            fields = listOf(
                ServiceField("Lámpara de avería", 0, 1),
                ServiceField("Par en el embrague", 1, 1),
                ServiceField("Par interno", 2, 1),
                ServiceField("Velocidad máxima", 3, 1),
                ServiceField("Clase de potencia", 4, 1),
            ),
            note = "Son códigos: BMW no dice en este fichero a qué potencia corresponde cada uno.",
        ),
        ServiceQuery(
            "Codificación (sin límite de tiempo)", "223901", "623901",
            fields = listOf(
                ServiceField("Factor del intervalo de aceite", 0, 2),
                ServiceField("Regulación del alternador", 10, 1),
                ServiceField("Indicador de cambio de marcha", 12, 1),
                ServiceField("Arranque y parada", 13, 1),
                ServiceField("Corte con el contacto", 14, 1),
                ServiceField("Tipo de alternador", 15, 1),
                ServiceField("Ayuda de arranque en cuesta", 16, 1),
            ),
        ),
        ServiceQuery("Aprendizaje de la EGR", "305B01", "705B01", bits = OFFSET_BITS, fields = OFFSET_FIELDS),
        ServiceQuery("Aprendizaje de las mariposas de turbulencia", "305A01", "705A01", bits = OFFSET_BITS, fields = OFFSET_FIELDS),
        adjust("Corrección de la EGR (normal)", 0xA4),
        adjust("Corrección de la EGR (en regeneración)", 0xE4),
        adjust("Corrección del caudalímetro al ralentí", 0xAE),
        adjust("Corrección del caudalímetro en carga", 0xAF),
        adjust("Corrección del ralentí", 0xA3, factor = 0.5, unit = "rpm", note = ""),
        adjust("Corrección del par máximo", 0xA2, bytes = 4, factor = PERCENT, unit = "%", note = ""),
        adjust("Corrección del consumo", 0xB0, factor = PERCENT, unit = "%", note = ""),
        adjust("Filtro: corrección de la vida restante", 0x79),
        adjust("Filtro: corrección de la distancia máxima entre regeneraciones", 0xE6),
        adjust("Filtro: corrección del gasóleo entre regeneraciones", 0xE5),
        adjust("Filtro: tiempo total de regeneración", 0xF7, bytes = 4, factor = 0.01, unit = "s", note = ""),
        ServiceQuery(
            "Corrección de cada inyector (NMK)", "305701", "705701",
            fields = nmk(),
            note = "Corrección del tiempo de inyección de cada cilindro; BMW no da la unidad (creo que µs).",
        ),
        ServiceQuery(
            "Mapa aprendido del actuador del turbo", "307A01", "707A01",
            fields = (0 until 36).map { ServiceField("Fila ${it / 6 + 1}, columna ${it % 6 + 1}", 2 * it, 2, PERCENT, unit = "%", signed = true) },
            note = "Lo que la centralita ha aprendido a corregir el actuador del turbo, en 6 × 6 puntos. BMW no da los ejes del mapa.",
        ),
        ServiceQuery(
            "Calidad del gasóleo", "30E301", "70E301",
            fields = listOf(
                ServiceField("Índice de calidad", 0, 2, 0.00006104, signed = true),
                ServiceField("Irregularidad del motor por el gasóleo", 2, 2, 0.00305344, unit = "rpm"),
                ServiceField("Contador 1", 4, 2, signed = true),
                ServiceField("Contador 2", 6, 2, signed = true),
                ServiceField("Contador 3", 8, 2, signed = true),
                ServiceField("Distancia desde que se reiniciaron", 10, 4, 0.001, unit = "km", signed = true),
            ),
            note = "El módulo de calidad del combustible de la centralita. BMW no dice qué cuenta cada contador.",
        ),
        // STATUS_LAUFUNRUHE_*: medidas de la lectura normal de BMW, una a una (el ELM no manda la consulta de varias).
        // El orden es el de encendido; en un 4 cilindros en linea es 1-3-4-2 (creo).
        *listOf(1, 3, 4, 2).mapIndexed { slot, cyl ->
            ServiceQuery(
                "Cilindro $cyl: revoluciones propias", feedbackQuery(0x1496 + slot), FEEDBACK_ANSWER,
                fields = listOf(ServiceField("Revoluciones", 0, 2, 0.091554, unit = "rpm")),
            )
        }.toTypedArray(),
        *listOf(1, 3, 4, 2).mapIndexed { slot, cyl ->
            ServiceQuery(
                "Cilindro $cyl: compensación de gasóleo", feedbackQuery(0x149C + slot), FEEDBACK_ANSWER,
                fields = listOf(ServiceField("Compensación", 0, 2, 0.003052, -100.0, "mg")),
                note = if (slot == 0) "Al ralentí y caliente es cuando dice algo: un cilindro que pide mucho más o mucho menos que los otros apunta a ese inyector." else "",
            )
        }.toTypedArray(),
        ServiceQuery(
            "Aceite: temperatura", feedbackQuery(0x0458), FEEDBACK_ANSWER,
            fields = listOf(ServiceField("Temperatura", 0, 2, 0.01, -100.0, "°C")),
        ),
        ServiceQuery(
            "Aceite: nivel (dibujo del cuadro)", feedbackQuery(0x0454), FEEDBACK_ANSWER,
            fields = listOf(ServiceField("Código", 0, 1)),
            note = "Es el dibujo que pondría el cuadro, como código; BMW no da su tabla en este fichero.",
        ),
        ServiceQuery(
            "Enfriador de la EGR", "305C01", "705C01",
            fields = (1..4).map { ServiceField("Factor $it", 2 * (it - 1), 2, 1.0 / 8192, signed = true) },
            note = "Factores de eficiencia del enfriador que vigila la centralita.",
        ),
    )

    /** La respuesta a [query], o null si no contesta. */
    fun parse(query: ServiceQuery, response: String?): ServiceRead {
        if (response == null) return ServiceRead(query, null, null)
        refusal(response, query.query.substring(0, 2))?.let { return ServiceRead(query, null, it) }
        val data = ObdParser.payloads(response, query.answer).lastOrNull()
        return ServiceRead(query, data?.toList(), null)
    }

    /** El motivo si la centralita contesta 7F a ese servicio. */
    fun refusal(response: String, service: String): Int? =
        ObdParser.payloads(response, "7F$service").firstOrNull { it.isNotEmpty() }?.get(0)

    // Las pruebas de piezas de la tabla STELLER. Los telegramas son los del .prg, iguales para todas:
    // STEUERN_WERT_LESEN manda 30 <id> 01, STEUERN_SELECTIV manda 30 <id> 07 y el % en dos bytes
    // (ELU al 30 % es 30 C7 07 09 99, AGR al 20 % es 30 C2 07 06 66) y STEUERN_ENDE_SELECTIV 30 <id> 00.
    /** El ventilador va primero: si en el coche hace lo esperado, se encienden las demas. */
    const val FAN_TEST_READY = true

    /** EGR, mariposas y turbo: apagadas hasta que la prueba del ventilador salga bien en el coche. */
    const val ACTUATORS_READY = false

    const val FAN_LID = 0xC7
    const val FAN_PERCENT = 30.0
    private const val PERCENT_PER_BIT = 0.01220703125

    val FAN = Actuator("Ventilador", FAN_LID, listOf(FAN_PERCENT), holdMs = 5_000L, feedback = null)

    /**
     * La EGR, las mariposas y el turbo, del 20 al 80 % y vuelta, con su sensor de posicion (las
     * medidas "Stellgliedposition" de BMW). El turbo no tiene sensor entre las medidas: solo se oye.
     */
    val ACTUATORS = listOf(
        Actuator("EGR", 0xC2, listOf(20.0, 80.0), feedback = 0x0BB4),
        Actuator("Mariposas de turbulencia", 0xCE, listOf(20.0, 80.0), feedback = 0x0808),
        Actuator("Mariposa de admisión", 0xCD, listOf(20.0, 80.0), feedback = 0x0B1F),
        Actuator("Actuador del turbo", 0xC6, listOf(20.0, 80.0), feedback = null),
    )

    val FAN_READ = read(FAN_LID)
    val FAN_RELEASE = release(FAN_LID)

    fun read(lid: Int) = "30%02X01".format(lid)
    fun release(lid: Int) = "30%02X00".format(lid)
    fun answer(lid: Int) = "70%02X".format(lid)

    /** 30 <id> 07 y el valor en dos bytes, cortado como ISTA: 30 % son 0999. */
    fun command(lid: Int, percent: Double): String = "30%02X07%04X".format(lid, (percent / PERCENT_PER_BIT).toInt())

    fun fanCommand(percent: Double): String = command(FAN_LID, percent)

    /** El % de un valor en dos bytes de la respuesta, si los trae. */
    fun fanPercent(data: List<Int>): Double? = if (data.size >= 2) (data[0] * 256 + data[1]) * PERCENT_PER_BIT else null

    /** La consulta de la posicion: la misma lectura de medidas de BMW que usa la app. */
    fun feedbackQuery(id: Int) = "2C10%04X".format(id)
    const val FEEDBACK_ANSWER = "6C10"
    const val FEEDBACK_FACTOR = 0.001526
}

/**
 * Una pieza de la tabla STELLER: su id, los % a los que se pone, cuanto tiempo cada uno y la medida
 * que dice donde esta de verdad (null si no hay).
 */
class Actuator(val name: String, val lid: Int, val percents: List<Double>, val holdMs: Long = 3_000L, val feedback: Int?)

/** Un paso de la prueba del ventilador: que se mando, que contesto y que significa. */
class ServiceStep(val label: String, val query: String, val response: String?, val meaning: String)

/**
 * La prueba de una pieza. [accepted]: true si la centralita acepto moverla, false si lo rechazo y
 * null si no contesto. [released]: si acepto devolverle el control.
 */
class FanTest(val name: String, val steps: List<ServiceStep>, val accepted: Boolean?, val released: Boolean?)
