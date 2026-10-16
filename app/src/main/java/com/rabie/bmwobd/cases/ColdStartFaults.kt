package com.rabie.bmwobd.cases

/**
 * Cuando releer las averias tras un arranque en frio. El mando de los calentadores solo vuelve a
 * comprobar una averia mientras calienta, y tras arrancar con el motor frio sigue calentando un
 * rato (el postcalentamiento). Leerlas a los [READ_AT_S] segundos dice si la del calentador sigue
 * presente o se quedo solo guardada. Hace falta ver el arranque: si al conectar el motor ya
 * estaba en marcha no se sabe cuando arranco y no se lee nada.
 */
class ColdStartFaults {

    private var state = UNKNOWN
    private var startMs = 0L
    private var next = 0

    /** El motor al arrancar, en °C; null mientras no haya arrancado en frio. */
    var coolantAtStart: Double? = null
        private set

    /** Los segundos desde el arranque si ahora toca leer las averias; null si no. */
    fun due(nowMs: Long, rpm: Double?, coolant: Double?): Int? {
        when (state) {
            UNKNOWN -> if (rpm != null) state = if (rpm < RUNNING_RPM) STOPPED else DONE
            STOPPED -> if (rpm != null && rpm >= RUNNING_RPM) {
                if (coolant != null && coolant < COLD_COOLANT) {
                    state = STARTED
                    startMs = nowMs
                    coolantAtStart = coolant
                } else {
                    state = DONE
                }
            }
            STARTED -> if (nowMs - startMs >= READ_AT_S[next] * 1000L) {
                val at = READ_AT_S[next++]
                if (next == READ_AT_S.size) state = DONE
                return at
            }
        }
        return null
    }

    companion object {
        /** Por debajo de esto se da por arranque en frio: los calentadores siguen tras arrancar. */
        const val COLD_COOLANT = 40.0
        val READ_AT_S = listOf(60, 120)

        private const val UNKNOWN = 0
        private const val STOPPED = 1
        private const val STARTED = 2
        private const val DONE = 3
        private const val RUNNING_RPM = 400.0
    }
}
