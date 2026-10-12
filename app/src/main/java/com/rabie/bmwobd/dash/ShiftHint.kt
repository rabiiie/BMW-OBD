package com.rabie.bmwobd.dash

/**
 * La recomendacion de cambio de marcha. La del coche no se puede leer, asi que esta es una cuenta
 * de la app, mas simple: subir cuando el motor va holgado y en la marcha siguiente seguiria por
 * encima de las vueltas en que tira bien; bajar cuando va demasiado bajo de vueltas para lo que
 * se le pide. Los limites son orientativos para un diesel como el N47, no los de BMW.
 */
class ShiftHint {

    private var candidate = NONE
    private var sinceMs = 0L

    /** Lo que se enseña: [UP], [DOWN] o [NONE]. */
    var shown = NONE
        private set

    /**
     * [gear] es la marcha de ahora (0 parado, null si no se sabe) y [nextRpm] las vueltas a las
     * que caeria el motor en la siguiente, si se conoce su relacion. [demanding] dice si se le
     * esta pidiendo fuerza de verdad. Una recomendacion tarda un momento en salir, para que no
     * parpadee en cada golpe de pedal, y se quita en cuanto deja de valer.
     */
    fun update(tMs: Long, speed: Double?, rpm: Double?, coolant: Double?, gear: Int?, nextRpm: Double?, demanding: Boolean): Int {
        val wanted = suggest(speed, rpm, coolant, gear, nextRpm, demanding)
        if (wanted != candidate) {
            candidate = wanted
            sinceMs = tMs
        }
        shown = if (wanted == NONE || tMs - sinceMs >= HOLD_MS) wanted else NONE
        return shown
    }

    private fun suggest(speed: Double?, rpm: Double?, coolant: Double?, gear: Int?, nextRpm: Double?, demanding: Boolean): Int {
        if (speed == null || rpm == null || gear == null || gear < 1 || speed < MIN_SPEED) return NONE
        val cold = coolant != null && coolant < COLD_COOLANT
        if (gear >= 2 && (rpm < TOO_LOW_RPM || (demanding && rpm < PULL_MIN_RPM))) return DOWN
        val enough = if (cold) COLD_UP_RPM else UP_RPM
        val floor = if (cold) COLD_NEXT_MIN_RPM else NEXT_MIN_RPM
        val nextHolds = nextRpm == null || nextRpm >= floor
        return if (gear < TOP_GEAR && !demanding && rpm >= enough && nextHolds) UP else NONE
    }

    companion object {
        const val NONE = 0
        const val UP = 1
        const val DOWN = -1

        private const val HOLD_MS = 1_500L
        private const val MIN_SPEED = 10.0
        private const val TOP_GEAR = 6
        private const val COLD_COOLANT = 60.0
        private const val TOO_LOW_RPM = 1_150.0
        private const val PULL_MIN_RPM = 1_400.0
        private const val UP_RPM = 2_000.0
        private const val COLD_UP_RPM = 2_400.0
        private const val NEXT_MIN_RPM = 1_300.0
        private const val COLD_NEXT_MIN_RPM = 1_500.0
    }
}
