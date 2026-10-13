package com.rabie.bmwobd.cases

/**
 * El primer arranque de una conexion. Si al conectar el motor esta parado, las medidas del
 * arranque se leen solas, sin elegir el caso, hasta un minuto despues de arrancar: la prueba del
 * arranque en frio pide dejar el coche parado dias y no puede depender de acordarse de un boton.
 * Solo el primero: las paradas de despues, las del semaforo, no cuentan.
 */
class FirstStart {

    private var state = UNKNOWN
    private var untilMs = 0L

    /** Si ahora toca leer las medidas del arranque. [rpm] es null mientras no se haya leido. */
    fun watching(nowMs: Long, rpm: Double?): Boolean {
        when (state) {
            UNKNOWN -> if (rpm != null) state = if (rpm < RUNNING_RPM) STOPPED else DONE
            STOPPED -> if (rpm != null && rpm >= RUNNING_RPM) {
                state = STARTED
                untilMs = nowMs + AFTER_START_MS
            }
            STARTED -> if (nowMs >= untilMs) state = DONE
        }
        return state == STOPPED || state == STARTED
    }

    private companion object {
        const val UNKNOWN = 0
        const val STOPPED = 1
        const val STARTED = 2
        const val DONE = 3
        const val RUNNING_RPM = 400.0
        const val AFTER_START_MS = 60_000L
    }
}
