package com.rabie.bmwobd.obd

import com.rabie.bmwobd.advice.Advisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineOffReadingsTest {

    private val cut = Pids.BMW_BOOST_WATCH_CUT

    @Test
    fun `el 3 leido con el motor parado no llega al arrancar y no avisa`() {
        // Lo del 29 de octubre: al conectar, motor parado y vigilancia a 3; luego en marcha sin volver a leerla.
        val values = hashMapOf(Pids.RPM to 0.0, cut to 3.0)
        val readings = EngineOffReadings()
        readings.after(values, values.keys.toSet())
        assertEquals(3.0, values[cut]!!, 0.0)

        values[Pids.RPM] = 836.0
        readings.after(values, setOf(Pids.RPM))
        assertNull(values[cut])

        val advisor = Advisor(live = true)
        for (s in 0..30) assertNull(advisor.update(s * 1_000L, values).find { it.id == "boost_cut" })
    }

    @Test
    fun `una lectura hecha ya en marcha se queda`() {
        val values = hashMapOf(Pids.RPM to 0.0, cut to 3.0)
        val readings = EngineOffReadings()
        readings.after(values, values.keys.toSet())
        values[Pids.RPM] = 836.0
        values[cut] = 4.0
        readings.after(values, setOf(Pids.RPM, cut))
        assertEquals(4.0, values[cut]!!, 0.0)
        readings.after(values, setOf(Pids.RPM))
        assertEquals(4.0, values[cut]!!, 0.0)
    }

    @Test
    fun `si al conectar ya estaba en marcha la lectura vale`() {
        val values = hashMapOf(Pids.RPM to 850.0, cut to 4.0)
        val readings = EngineOffReadings()
        readings.after(values, values.keys.toSet())
        readings.after(values, setOf(Pids.RPM))
        assertEquals(4.0, values[cut]!!, 0.0)
    }
}
