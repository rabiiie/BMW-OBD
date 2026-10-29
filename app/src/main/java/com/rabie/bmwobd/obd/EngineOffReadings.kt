package com.rabie.bmwobd.obd

/**
 * Medidas que con el motor parado dan un valor que en marcha seria una alarma. La vigilancia del
 * turbo (01FB) da 3 con el motor parado y 0 en marcha: si la ultima lectura es de antes de
 * arrancar, al arrancar se quita, y hasta que se vuelve a leer no hay valor.
 */
internal class EngineOffReadings(private val ids: Set<Int> = setOf(Pids.BMW_BOOST_WATCH_CUT)) {

    private var running = false

    /** Despues de cada vuelta, con las medidas leidas en ella. Las leidas en la vuelta del arranque ya son en marcha. */
    fun after(values: MutableMap<Int, Double>, readNow: Set<Int>) {
        val now = (values[Pids.RPM] ?: 0.0) > RUNNING_RPM
        if (now && !running) values.keys.removeAll(ids - readNow)
        running = now
    }

    private companion object {
        const val RUNNING_RPM = 400.0
    }
}
