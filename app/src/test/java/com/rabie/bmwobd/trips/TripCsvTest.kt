package com.rabie.bmwobd.trips

import com.rabie.bmwobd.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    fun `sin caudal de combustible el consumo se estima con el aire y la lambda`() {
        // 29 g/s de aire con lambda 1,25 en un diesel: 1,6 g/s de gasoleo, 6,9 L/h.
        val values = mapOf(Pids.MAF to 29.0, Pids.LAMBDA to 1.25, Pids.SPEED to 100.0, Pids.PEDAL to 30.0, Pids.RPM to 2000.0)
        assertEquals(6.9, Pids.fuelRate(values)!!, 0.05)
        assertEquals(6.9, Pids.litersPer100Km(values)!!, 0.05)
        assertNull(Pids.fuelRate(values + (Pids.LAMBDA to 0.0)))
        assertEquals(0.0, Pids.fuelRate(values + mapOf(Pids.LAMBDA to 2.0, Pids.PEDAL to 0.0))!!, 0.0)
        assertTrue(Pids.fuelRate(values + mapOf(Pids.LAMBDA to 2.0, Pids.PEDAL to 0.0, Pids.RPM to 850.0))!! > 0)
        assertEquals(5.0, Pids.fuelRate(values + (Pids.FUEL_RATE to 5.0))!!, 0.0)
    }

    @Test
    fun `el consumo estimado solo cuenta los kilometros con la sonda dando lectura`() {
        val ids = listOf(Pids.SPEED, Pids.MAF, Pids.LAMBDA)
        val cold = mapOf(Pids.SPEED to 100.0, Pids.MAF to 29.0, Pids.LAMBDA to 0.0)
        val warm = cold + (Pids.LAMBDA to 1.25)
        val text = sequence {
            yield(TripCsv.header(ids))
            for (i in 0..3600) yield(TripCsv.row(i * 1000L, ids, if (i < 1800) cold else warm))
        }
        val stats = TripCsv.stats(TripCsv.parse(text))
        assertEquals(100.0, stats.distanceKm!!, 0.01)
        assertEquals(50.0, stats.fuelKm!!, 0.05)
        assertEquals(6.9, stats.litersPer100Km!!, 0.05)
        assertNull(stats.fuelLiters)
        assertTrue(stats.fuelEstimated)
    }

    @Test
    fun `parado no hay consumo a los cien`() {
        val values = mapOf(Pids.SPEED to 0.0, Pids.FUEL_RATE to 0.7)
        assertNull(TripCsv.stats(TripCsv.parse(lines(600, values))).litersPer100Km)
    }

    @Test
    fun `el desfase de la distribucion es la mediana y una lectura mala no cuenta`() {
        val timing = Pids.bmw(Pids.BMW_TIMING_OFFSET_ADDRESS)
        val columns = listOf(Pids.COOLANT, timing)
        fun trip(values: List<Double?>) = TripCsv.parse(
            sequence {
                yield(TripCsv.header(columns))
                values.forEachIndexed { i, value ->
                    yield(TripCsv.row(i * 1000L, columns, listOfNotNull(Pids.COOLANT to 24.0 + i, value?.let { timing to it }).toMap()))
                }
            },
        )
        // Una sola lectura al conectar, como en los trayectos del 12 de octubre.
        val once = TripCsv.timing(trip(List(20) { -0.08 }))!!
        assertEquals(-0.08, once.median, 0.001)
        assertTrue(once.steady)
        assertEquals(24.0, once.startCoolant!!, 0.001)

        // Muchas lecturas con una cruzada de otra medida (-94) y otra algo distinta.
        val many = TripCsv.timing(trip(listOf(null, -0.13, -0.13, -0.13, -94.43, -0.13, -0.20, -0.13, -0.13)))!!
        assertEquals(-0.13, many.median, 0.001)
        assertEquals(-0.20, many.min, 0.001)
        assertEquals(-0.13, many.max, 0.001)
        assertFalse(many.steady)

        // Sin esa medida, o solo con valores imposibles, no hay desfase que contar.
        assertNull(TripCsv.timing(trip(List(5) { null })))
        assertNull(TripCsv.timing(trip(listOf(-94.0, 80.0))))
    }
}
