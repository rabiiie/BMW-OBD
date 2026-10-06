package com.rabie.bmwobd.trips

import com.rabie.bmwobd.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCsvTest {

    private val columns = listOf(Pids.SPEED, Pids.FUEL_RATE, Pids.MAP, Pids.BAROMETRIC)

    private fun lines(rows: Int, values: Map<Int, Double>): Sequence<String> = sequence {
        yield(TripCsv.header(columns))
        for (i in 0 until rows) yield(TripCsv.row(i * 1000L, columns, values))
    }

    @Test
    fun `la cabecera lleva los pids en hexadecimal`() {
        assertEquals("t_ms,0D,5E,0B,33", TripCsv.header(columns))
    }

    @Test
    fun `una fila deja vacio lo que no se conoce y usa punto decimal`() {
        assertEquals("1500,87.00,5.25,,", TripCsv.row(1500, columns, mapOf(Pids.SPEED to 87.0, Pids.FUEL_RATE to 5.25)))
    }

    @Test
    fun `una hora a 100 con 6 litros hora son 100 km a 6 a los cien`() {
        val values = mapOf(Pids.SPEED to 100.0, Pids.FUEL_RATE to 6.0, Pids.MAP to 180.0, Pids.BAROMETRIC to 100.0)
        val stats = TripCsv.stats(TripCsv.parse(lines(3601, values)))
        assertEquals(3_600_000L, stats.durationMs)
        assertEquals(100.0, stats.distanceKm!!, 0.01)
        assertEquals(100.0, stats.avgSpeed!!, 0.01)
        assertEquals(6.0, stats.fuelLiters!!, 0.01)
        assertEquals(6.0, stats.litersPer100Km!!, 0.01)
        assertEquals(0.8, stats.maxBoostBar!!, 0.001)
        assertNull(stats.maxRpm)
    }

    @Test
    fun `los valores que faltan son NaN y la fila a medias se salta`() {
        val text = sequenceOf("t_ms,0D,5E", "0,50.00,", "1000,60.00,4.00", "2000,70")
        val data = TripCsv.parse(text)
        assertEquals(2, data.size)
        assertTrue(data.series.getValue(Pids.FUEL_RATE)[0].isNaN())
        assertEquals(60.0, data.series.getValue(Pids.SPEED)[1], 0.001)
    }

    @Test
    fun `parado no hay consumo a los cien`() {
        val values = mapOf(Pids.SPEED to 0.0, Pids.FUEL_RATE to 0.7)
        assertNull(TripCsv.stats(TripCsv.parse(lines(600, values))).litersPer100Km)
    }
}
