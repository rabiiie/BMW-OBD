package com.rabie.bmwobd.obd

import java.util.Locale

/**
 * Lecturas de servicio de la centralita del motor que ISTA hace con sus trabajos de d70n47b0 y la
 * lectura normal no: la codificacion (LESEN_CODIERWERTE, servicio 22 con 3900 y 3901), las
 * correcciones guardadas (tabla ABGLEICH), el aprendizaje de la EGR y de las mariposas de
 * turbulencia (STATUS_OFFSETLERNEN), la correccion de cada inyector (ABGLEICH_NMK_LESEN) y la
 * vigilancia del enfriador de la EGR (ABGLEICH_LESEN_AGRKUEHLER). Todas son de solo lectura: el
 * servicio 22 lee, y el 30 va con 01, "dame el estado actual", que es como la documentacion pide
 * NMK. La documentacion da el identificador y la escala, pero no donde empieza el dato en la
 * respuesta: lo que no esta comprobado con el coche lo dice su nota, y siempre va la respuesta tal cual.
 */
class ServiceQuery(
    val name: String,
    val query: String,
    val answer: String,
    /** De la tabla de BMW: cuantos bytes ocupa el valor, la escala y la unidad. Null si no se interpreta. */
    val bytes: Int? = null,
    val factor: Double = 1.0,
    val unit: String = "",
    val signed: Boolean = false,
    val note: String = "",
    /** Bits con nombre del primer y segundo byte, como DIG_OFFSET_LERNFUNKTIONEN. */
    val bits: List<Triple<Int, Int, String>> = emptyList(),
)

/** Lo que contesto la centralita a una [ServiceQuery]. [data] va sin el eco de la consulta. */
class ServiceRead(val query: ServiceQuery, val data: List<Int>?, val refusal: Int?) {

    val raw: String get() = data?.joinToString(" ") { "%02X".format(it) } ?: ""

    /** El valor con la escala de BMW si la respuesta trae al menos esos bytes. */
    val value: Double?
        get() {
            val size = query.bytes ?: return null
            val d = data?.takeIf { it.size >= size } ?: return null
            var v = d.take(size).fold(0L) { acc, b -> acc * 256 + b }
            if (query.signed && v >= 1L shl (8 * size - 1)) v -= 1L shl (8 * size)
            return v * query.factor
        }

    val valueText: String?
        get() = value?.let { String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.').replace('.', ',') + if (query.unit.isEmpty()) "" else " ${query.unit}" }

    /** Los bits con nombre que estan a 1. */
    val activeBits: List<String>
        get() = query.bits.filter { (byte, mask, _) -> data?.getOrNull(byte)?.let { it and mask != 0 } == true }.map { it.third }

    val status: String
        get() = when {
            refusal != null -> "Rechazado: " + BmwIma.refusalText(refusal)
            data == null -> "Sin respuesta"
            data.isEmpty() -> "Contesta sin dato"
            else -> raw
        }
}

object BmwService {

    private const val ADJUST = 0.00012207

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

    private const val BITS_NOTE = "Los bits salen de la tabla de BMW, contando el byte 0 como el primero tras el eco; sin comprobar con el coche."
    private const val FACTOR_NOTE = "Factor de corrección: 1 es sin corregir. El valor toma los primeros bytes tras el eco; si sale cerca de 1, la posición es buena."

    private fun adjust(name: String, lid: Int, bytes: Int = 2, factor: Double = ADJUST, unit: String = "", signed: Boolean = false, note: String = FACTOR_NOTE) =
        ServiceQuery(name, "30%02X01".format(lid), "70%02X01".format(lid), bytes, factor, unit, signed, note)

    val QUERIES: List<ServiceQuery> = listOf(
        ServiceQuery(
            "Codificación (con límite de tiempo)", "223900", "623900",
            note = "Según BMW lleva la lámpara de avería, el par en el embrague, el par interno, la velocidad máxima y la clase de potencia, " +
                "en las posiciones 4 a 8 de su tabla. No sé desde qué byte cuenta: va tal cual.",
        ),
        ServiceQuery(
            "Codificación (sin límite de tiempo)", "223901", "623901",
            note = "Según BMW: factor del intervalo de aceite, regulación del alternador, indicador de cambio de marcha y arranque y parada. Va tal cual.",
        ),
        ServiceQuery(
            "Aprendizaje de la EGR", "305B01", "705B01",
            note = BITS_NOTE + " Tras los bits, según BMW: los valores aprendidos en las dos posiciones y los arranques desde el último aprendizaje.",
            bits = OFFSET_BITS,
        ),
        ServiceQuery(
            "Aprendizaje de las mariposas de turbulencia", "305A01", "705A01",
            note = BITS_NOTE,
            bits = OFFSET_BITS,
        ),
        adjust("Corrección de la EGR (normal)", 0xA4),
        adjust("Corrección de la EGR (en regeneración)", 0xE4),
        adjust("Corrección del caudalímetro al ralentí", 0xAE),
        adjust("Corrección del caudalímetro en carga", 0xAF),
        adjust("Corrección del ralentí", 0xA3, factor = 0.5, unit = "rpm", signed = true, note = "En rpm; con signo (creo)."),
        adjust("Corrección del par máximo", 0xA2, bytes = 4, factor = 0.012207, unit = "%", note = "Según BMW, en %."),
        adjust("Corrección del consumo", 0xB0, factor = 0.012207, unit = "%", note = "Según BMW, en %."),
        adjust("Filtro: corrección de la vida restante", 0x79),
        adjust("Filtro: corrección de la distancia máxima entre regeneraciones", 0xE6),
        adjust("Filtro: corrección del gasóleo entre regeneraciones", 0xE5),
        adjust("Filtro: tiempo de regeneración", 0xF7, bytes = 4, factor = 0.01, unit = "s", note = "Según BMW, en segundos."),
        ServiceQuery(
            "Corrección de cada inyector (NMK)", "305701", "705701",
            note = "Según BMW: contadores de aprendizaje a 400, 700 y 1.000 bar y la corrección del tiempo de inyección de cada cilindro en esos tres puntos. Va tal cual.",
        ),
        ServiceQuery(
            "Enfriador de la EGR (5C)", "305C01", "705C01",
            note = "Según BMW, cuatro factores de eficiencia del enfriador (valor / 8192). No sé cuál de los dos, 5C o 5D, es el de este motor. Va tal cual.",
        ),
        ServiceQuery("Enfriador de la EGR (5D)", "305D01", "705D01", note = "Ver 5C."),
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

    // La prueba del motorventilador (ELU de la tabla STELLER): el mismo trabajo, STEUERN_SELECTIV,
    // mueve la EGR, las mariposas y el turbo, asi que si esto funciona sabemos como se manda lo demas.
    // 07 es "ajuste temporal" y 00 "devuelve el control a la centralita" en KWP2000: creo que son los
    // que usa ISTA, pero la documentacion no da los bytes.
    // Apagada: en d70n47b0.prg el byte de control del servicio 30 cambia segun el trabajo (00, 07,
    // 08) y no coincide con el estandar. Hasta sacar del .prg los bytes de STEUERN_SELECTIV no se manda.
    const val FAN_TEST_READY = false

    const val FAN_LID = 0xC7
    const val FAN_PERCENT = 30.0
    const val FAN_HOLD_MS = 5_000L
    private const val PERCENT_PER_BIT = 0.01220703125

    val FAN_READ = "30%02X01".format(FAN_LID)
    val FAN_RELEASE = "30%02X00".format(FAN_LID)
    val FAN_ANSWER = "70%02X".format(FAN_LID)

    /** 30 C7 07 y el valor en dos bytes: 30 % son 099A. */
    fun fanCommand(percent: Double): String = "30%02X07%04X".format(FAN_LID, Math.round(percent / PERCENT_PER_BIT))

    /** El % de un valor en dos bytes de la respuesta, si los trae. */
    fun fanPercent(data: List<Int>): Double? = if (data.size >= 2) (data[0] * 256 + data[1]) * PERCENT_PER_BIT else null
}

/** Un paso de la prueba del ventilador: que se mando, que contesto y que significa. */
class ServiceStep(val label: String, val query: String, val response: String?, val meaning: String)

/**
 * [accepted]: true si la centralita acepto mover el ventilador, false si lo rechazo y null si no
 * contesto. [released]: si acepto devolverle el control.
 */
class FanTest(val steps: List<ServiceStep>, val accepted: Boolean?, val released: Boolean?)
