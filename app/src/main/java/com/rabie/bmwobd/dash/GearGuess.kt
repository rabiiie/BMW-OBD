package com.rabie.bmwobd.dash

import kotlin.math.abs

/**
 * La marcha engranada, deducida en cada vuelta de lo que corre el coche por cada mil vueltas del
 * motor. La centralita la da, pero se lee de tarde en tarde y enseñaria la de hace un rato: sus
 * lecturas solo sirven aqui para aprender que relacion tiene cada marcha. Una relacion se da por
 * buena cuando dos lecturas seguidas de la misma marcha coinciden, para no aprender de una
 * tomada a medio cambio. Mientras una marcha no se ha aprendido vale la vista en el 118d.
 */
class GearGuess {

    private val learned = HashMap<Int, Double>()
    private val pending = HashMap<Int, Double>()

    /** Una lectura de la marcha [gear] hecha con el coche a [speed] km/h y el motor a [rpm]. */
    fun learn(gear: Int, speed: Double?, rpm: Double?) {
        if (gear !in 1..MAX_GEAR || speed == null || rpm == null || speed < MIN_SPEED || rpm < MIN_RPM) return
        val ratio = speed / rpm * 1000.0
        val before = pending.put(gear, ratio) ?: return
        if (abs(ratio / before - 1) <= SAME) learned[gear] = (learned[gear] ?: ratio) * KEEP + ratio * (1 - KEEP)
    }

    /** La marcha de ahora: 0 parado, null si la relacion no cuadra con ninguna (embrague pisado o punto muerto). */
    fun now(speed: Double?, rpm: Double?): Int? {
        if (speed == null || rpm == null) return null
        if (speed < STOPPED) return 0
        if (rpm < IDLE_RPM) return null
        val ratio = speed / rpm * 1000.0
        return nearest(learned, ratio) ?: nearest(SEEN.filterKeys { it !in learned }, ratio)
    }

    /** Los km/h por cada mil vueltas de [gear], aprendidos o vistos; null si no se conocen. */
    fun ratio(gear: Int): Double? = learned[gear] ?: SEEN[gear]

    private fun nearest(ratios: Map<Int, Double>, ratio: Double): Int? =
        ratios.minByOrNull { abs(it.value / ratio - 1) }?.takeIf { abs(it.value / ratio - 1) <= TOLERANCE }?.key

    private companion object {
        // Km/h por cada mil vueltas, del barrido del 11 de octubre en el 118d de seis marchas.
        val SEEN = mapOf(4 to 37.0, 5 to 47.5, 6 to 57.9)
        const val MAX_GEAR = 8
        const val MIN_SPEED = 12.0
        const val MIN_RPM = 1000.0
        const val IDLE_RPM = 600.0
        const val STOPPED = 3.0
        const val SAME = 0.05
        const val TOLERANCE = 0.06
        const val KEEP = 0.7
    }
}
