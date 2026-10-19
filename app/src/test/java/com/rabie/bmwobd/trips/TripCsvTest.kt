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
        // Sin revoluciones grabadas: un solo arranque. Una lectura al conectar, como el 12 de octubre.
        val once = TripCsv.timing(trip(List(20) { -0.08 }))!!.starts.single()
        assertEquals(-0.08, once.degrees, 0.001)
        assertEquals(24.0, once.coolant!!, 0.001)
        assertFalse(once.warm)

        // Muchas lecturas con una cruzada de otra medida (-94) y otra algo distinta: la mediana.
        val many = TripCsv.timing(trip(listOf(null, -0.13, -0.13, -0.13, -94.43, -0.13, -0.20, -0.13, -0.13)))!!
        assertEquals(-0.13, many.starts.single().degrees, 0.001)

        // Sin esa medida, o solo con valores imposibles, no hay desfase que contar.
        assertNull(TripCsv.timing(trip(List(5) { null })))
        assertNull(TripCsv.timing(trip(listOf(-94.0, 80.0))))
    }

    @Test
    fun `el desgaste aprendido sale con lo que contesto el coche`() {
        // Barrido del 11 de octubre: 0258 = 82F3, 0A40 = 828B, 0BBE = 7FFF, 0428 = 7F7B, 0C99 = 1AB5.
        fun value(address: Int, vararg bytes: Int) = Pids.byId.getValue(Pids.bmw(address)).decodeOrNull(bytes)!!
        assertEquals(3.92, value(0x0258, 0x82, 0xF3), 0.01)
        assertEquals(-0.26, value(0x0A40, 0x82, 0x8B), 0.01)
        assertEquals(0.0, value(0x0BBE, 0x7F, 0xFF), 0.01)
        assertEquals(-4.05, value(0x0428, 0x7F, 0x7B), 0.01)
        assertEquals(0.83, value(0x0C99, 0x1A, 0xB5), 0.01)

        val columns = listOf(Pids.BMW_RAIL_VALVE_LEARNED, Pids.BMW_FILTER_ZERO, Pids.BMW_EGR_COOLER)
        val coolers = listOf(0.70, 0.83, 0.87, 0.83, 0.71)
        val data = TripCsv.parse(
            sequence {
                yield(TripCsv.header(columns))
                coolers.forEachIndexed { i, cooler ->
                    yield(TripCsv.row(i * 1000L, columns, mapOf(columns[0] to 3.92, columns[1] to -4.05, columns[2] to cooler)))
                }
            },
        )
        val wear = TripCsv.wear(data)!!
        assertEquals(3.92, wear.railValve!!, 0.001)
        assertEquals(-4.05, wear.filterZero!!, 0.001)
        assertEquals(0.83, wear.egrCooler!!, 0.001)
        assertNull(wear.lambda)
        assertNull(wear.largestInjectorTime)

        // Los tiempos de los inyectores, con lo que dio el coche en el barrido del 11 de octubre.
        val times = Pids.BMW_INJECTOR_TIMES.flatten()
        val seen = listOf(35.0, 66.3, 63.4, 65.1, 26.6, 31.5, 32.3, 20.7, 13.0, 24.2, 18.2, 10.3)
        val learned = TripCsv.wear(TripCsv.parse(sequenceOf(TripCsv.header(times), TripCsv.row(0, times, times.zip(seen).toMap()))))!!
        assertEquals(listOf(35.0, 26.6, 13.0), learned.injectorTimes[0])
        assertEquals(listOf(65.1, 20.7, 10.3), learned.injectorTimes[3])
        assertEquals(66.3, learned.largestInjectorTime!!, 0.001)
        assertNull(TripCsv.wear(TripCsv.parse(sequenceOf(TripCsv.header(listOf(Pids.RPM)), TripCsv.row(0, listOf(Pids.RPM), mapOf(Pids.RPM to 800.0))))))
    }

    @Test
    fun `lo leido con el motor parado no cuenta para el desfase ni para el enfriador`() {
        // Como el 13 de octubre: contacto puesto once segundos, arranque, y la primera lectura
        // de verdad llega un rato despues; hasta entonces las filas repiten la de motor parado.
        val timing = Pids.bmw(Pids.BMW_TIMING_OFFSET_ADDRESS)
        val columns = listOf(Pids.RPM, Pids.COOLANT, timing, Pids.BMW_EGR_COOLER)
        val data = TripCsv.parse(
            sequence {
                yield(TripCsv.header(columns))
                for (i in 0 until 120) {
                    val running = i >= 11
                    val fresh = i >= 27
                    yield(
                        TripCsv.row(
                            i * 1000L, columns,
                            mapOf(
                                Pids.RPM to if (running) 850.0 else 0.0,
                                Pids.COOLANT to 43.0,
                                timing to if (fresh) -0.81 else 0.0,
                                Pids.BMW_EGR_COOLER to if (fresh) 0.81 else 1.0,
                            ),
                        ),
                    )
                }
            },
        )
        val offset = TripCsv.timing(data)!!.starts.single()
        assertEquals(-0.81, offset.degrees, 0.001)
        assertEquals(43.0, offset.coolant!!, 0.001)
        assertEquals(0.81, TripCsv.wear(data)!!.egrCooler!!, 0.001)
    }

    @Test
    fun `el desfase cuenta una vez por arranque y no lo que da arrancando`() {
        // Como el 19 de octubre a las 13:21: -1,51 a 118 rpm y -2,30 ya en marcha. Luego una parada
        // con el motor caliente: las filas repiten -2,30 hasta que se lee el del nuevo arranque.
        val timing = Pids.bmw(Pids.BMW_TIMING_OFFSET_ADDRESS)
        val columns = listOf(Pids.RPM, Pids.COOLANT, timing)
        val rows = listOf(
            Triple(0.0, 29.0, 0.0), Triple(118.0, 29.0, -1.51), Triple(940.0, 29.0, -1.51), Triple(940.0, 29.0, -2.30),
            Triple(1500.0, 60.0, -2.30), Triple(0.0, 75.0, -2.30), Triple(300.0, 75.0, -2.30), Triple(850.0, 75.0, -2.30),
            Triple(850.0, 75.0, -2.30), Triple(850.0, 76.0, -1.12), Triple(2000.0, 85.0, -1.12),
        )
        val data = TripCsv.parse(
            sequence {
                yield(TripCsv.header(columns))
                rows.forEachIndexed { i, (rpm, coolant, value) ->
                    yield(TripCsv.row(i * 1000L, columns, mapOf(Pids.RPM to rpm, Pids.COOLANT to coolant, timing to value)))
                }
            },
        )
        val starts = TripCsv.timing(data)!!.starts
        assertEquals(listOf(-2.30, -1.12), starts.map { it.degrees })
        assertEquals(listOf(29.0, 75.0), starts.map { it.coolant })
        assertEquals(listOf(false, true), starts.map { it.warm })
    }
    @Test
    fun `el margen sale del actuador con el turbo estable y del aire frente al que cabe`() {
        val cols = listOf(
            Pids.RPM, Pids.COOLANT, Pids.MAP, Pids.MAF, Pids.bmw(0x01F4), Pids.bmw(0x0BEB),
            Pids.bmw(0x0500), Pids.bmw(0x0BB4), Pids.bmw(0x076F),
        )
        // 2000 rpm, 140 kPa estables, 40 °C en el colector: caben ≈ 51,8 g/s; entran 42,5 g/s, ≈ 82 %.
        val steady = mapOf(
            Pids.RPM to 2000.0, Pids.COOLANT to 90.0, Pids.MAP to 140.0, Pids.MAF to 42.5, Pids.bmw(0x01F4) to 141.0,
            Pids.bmw(0x0BEB) to 74.0, Pids.bmw(0x0500) to 30.0, Pids.bmw(0x0BB4) to 0.0, Pids.bmw(0x076F) to 40.0,
        )
        val data = TripCsv.parse(sequence {
            yield(TripCsv.header(cols))
            for (i in 0 until 30) yield(TripCsv.row(i * 1000L, cols, steady))
            // Turbo lejos de lo pedido y motor frio: no cuentan.
            for (i in 30 until 60) yield(TripCsv.row(i * 1000L, cols, steady + (Pids.bmw(0x01F4) to 200.0) + (Pids.bmw(0x0BEB) to 95.0)))
            for (i in 60 until 90) yield(TripCsv.row(i * 1000L, cols, steady + (Pids.COOLANT to 40.0) + (Pids.bmw(0x0BEB) to 20.0)))
        })
        val margin = TripCsv.margin(data)!!
        assertEquals(74.0, margin.turboActuator!!, 0.001)
        assertEquals(82.1, margin.filling!!, 0.2)
        // Sin bastantes lecturas en esas condiciones no hay margen.
        assertNull(TripCsv.margin(TripCsv.parse(sequence { yield(TripCsv.header(cols)); yield(TripCsv.row(0, cols, steady)) })))
    }
}
