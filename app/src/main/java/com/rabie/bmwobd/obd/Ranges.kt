package com.rabie.bmwobd.obd

enum class Status { OK, WARN, ALERT, NEUTRAL }

/**
 * Rango optimo de una medida. Dentro de [okMin, okMax] es OK; fuera pero dentro de
 * [warnMin, warnMax] es aviso; mas alla es alerta. Con [lowIsNeutral], por debajo del optimo
 * no se avisa (motor frio).
 */
data class Range(
    val okMin: Double,
    val okMax: Double,
    val warnMin: Double,
    val warnMax: Double,
    val lowIsNeutral: Boolean = false,
) {
    fun evaluate(value: Double): Status = when {
        value in okMin..okMax -> Status.OK
        value < okMin && lowIsNeutral -> Status.NEUTRAL
        value in warnMin..warnMax -> Status.WARN
        else -> Status.ALERT
    }
}

/** Rangos de partida del 118d N47. Sin rango no hay semaforo: se calibran con trayectos propios. */
object DefaultRanges {
    val byPid: Map<Int, Range> = mapOf(
        0x05 to Range(80.0, 100.0, 80.0, 108.0, lowIsNeutral = true),
        0x5C to Range(85.0, 115.0, 85.0, 125.0, lowIsNeutral = true),
        0x0F to Range(-40.0, 50.0, -40.0, 65.0),
        // Ancho a proposito: con recuperacion de energia la tension va de 12,2 a 15 V en marcha.
        0x42 to Range(12.1, 15.0, 11.5, 15.4),
        Pids.ADAPTER_VOLTAGE to Range(12.1, 15.0, 11.5, 15.4),
    )
}
