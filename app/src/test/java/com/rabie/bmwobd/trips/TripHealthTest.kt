package com.rabie.bmwobd.trips

import com.rabie.bmwobd.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripHealthTest {

    private fun real(name: String): TripData {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(name))
        return stream.bufferedReader().useLines { TripCsv.parse(it) }
    }

    @Test
    fun `el trayecto del 12 de octubre, calienta en ocho minutos y medio y el turbo da lo pedido`() {
        val health = TripHealth.of(real("trayecto_real_calentamiento_y_turbo.csv"))!!

        val warmUp = health.warmUp!!
        assertEquals(24.0, warmUp.startCoolant, 0.001)
        assertEquals(25.0, warmUp.outside!!, 0.001)
        assertEquals(8.5, warmUp.minutesTo80!!, 0.1)
        assertEquals(91.0, warmUp.steady!!, 0.001)

        val boost = health.boost!!
        assertEquals(241, boost.samples)
        assertEquals(-3.2, boost.median, 0.05)
        assertEquals(-14.4, boost.low, 0.05)

        // Se conecto con el motor ya en marcha: no hay arranque que medir.
        assertNull(health.start)
        assertTrue(health.facts().first().first.startsWith("Calentamiento: de 24 °C a 80 °C"))
    }

    @Test
    fun `la grabacion del 13 de octubre, arranque templado en un segundo con la bateria a 12,2 V`() {
        val health = TripHealth.of(real("trayecto_real_arranque_templado.csv"))!!

        val start = health.start!!
        assertEquals(1.1, start.seconds, 0.05)
        assertEquals(12.16, start.restVolts!!, 0.001)
        assertEquals(43.0, start.coolant!!, 0.001)

        // Dos minutos a ralenti: ni llega a calentarse ni sopla el turbo.
        assertNull(health.warmUp)
        assertNull(health.boost)
    }

    @Test
    fun `sin revoluciones grabadas no se saca nada`() {
        val columns = listOf(Pids.COOLANT)
        val data = TripCsv.parse(sequenceOf(TripCsv.header(columns), TripCsv.row(0, columns, mapOf(Pids.COOLANT to 90.0))))
        assertNull(TripHealth.of(data))
    }
}
