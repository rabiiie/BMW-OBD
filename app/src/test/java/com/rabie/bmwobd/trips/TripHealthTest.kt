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
        val health = TripHealth.of(real("trayecto_real_12oct_ida.csv"))!!

        val warmUp = health.warmUp!!
        assertEquals(24.0, warmUp.startCoolant, 0.001)
        assertEquals(25.0, warmUp.outside!!, 0.001)
        assertEquals(8.5, warmUp.minutesTo80!!, 0.1)
        assertEquals(91.0, warmUp.steady!!, 0.001)

        val boost = health.boost!!
        assertEquals(241, boost.samples)
        assertEquals(-3.2, boost.median, 0.05)
        assertEquals(-14.4, boost.low, 0.05)

        val rail = health.rail!!
        assertEquals(2438, rail.samples)
        assertEquals(-2.75, rail.median, 0.01)

        val egr = health.egr!!
        assertEquals(3302, egr.samples)
        assertEquals(-1.56, egr.median, 0.01)

        val idle = health.idle!!
        assertEquals(201, idle.samples)
        assertEquals(841.0, idle.rpm, 0.001)
        assertEquals(15.0, idle.spread, 0.001)

        assertEquals(13.84, health.charge!!.volts, 0.001)
        assertEquals(13.78, health.charge!!.low, 0.001)

        assertEquals(14.97, health.filter!!.idlePressure!!, 0.01)
        assertEquals(114.89, health.filter!!.lastInterval!!, 0.01)

        // Arranco a 24 grados y no se le pidio fuerza hasta pasar de 60.
        assertEquals(0L, health.coldPushSeconds)
        // Ni se llevo ahogado: ningun rato de tres segundos con carga alta por debajo de 1.300 vueltas.
        assertEquals(0L, health.luggingSeconds)
        val performance = health.performance!!
        assertEquals(258.08, performance.torque, 0.01)
        assertEquals(111.0, performance.horsepower, 0.5)
        // Ninguna subida de 80 a 120 fue con el pedal a fondo.
        assertNull(performance.from80To120)

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
        // Esa grabacion es de antes de leer el tiempo aparcado.
        assertNull(start.parkedMinutes)

        // Dos minutos a ralenti con el motor a 43 grados: ni llega a calentarse ni sopla el turbo,
        // y el ralenti y el filtro solo se miran en caliente.
        assertNull(health.warmUp)
        assertNull(health.boost)
        assertNull(health.idle)
        assertNull(health.filter!!.idlePressure)
        assertEquals(-3.68, health.rail!!.median, 0.01)
        // La tension de los primeros noventa segundos no cuenta: todavia salia la de parado, 12,16 V.
        assertTrue(health.charge == null || health.charge!!.low > 14.0)
    }

    @Test
    fun `el tiempo aparcado es el que da la centralita tras el arranque`() {
        val columns = listOf(Pids.RPM, Pids.BMW_PARKED_MINUTES)
        val data = TripCsv.parse(
            sequence {
                yield(TripCsv.header(columns))
                for (i in 0 until 40) {
                    // Parado diez segundos con el dato del arranque anterior, y al arrancar el nuevo: 617 min vio el coche.
                    val running = i >= 10
                    yield(TripCsv.row(i * 1000L, columns, mapOf(Pids.RPM to if (running) 850.0 else 0.0, Pids.BMW_PARKED_MINUTES to if (running) 2890.0 else 617.0)))
                }
            },
        )
        val health = TripHealth.of(data)!!
        assertEquals(2890.0, health.start!!.parkedMinutes!!, 0.001)
        assertTrue(health.facts().any { it == ("Tiempo parado antes de este arranque" to "2,0 días") })
    }

    @Test
    fun `sin revoluciones grabadas no se saca nada`() {
        val columns = listOf(Pids.COOLANT)
        val data = TripCsv.parse(sequenceOf(TripCsv.header(columns), TripCsv.row(0, columns, mapOf(Pids.COOLANT to 90.0))))
        assertNull(TripHealth.of(data))
    }

    @Test
    fun `resistencia del filtro y escape frente al turbo, con el caudal que da la centralita`() {
        val n = 60
        val flow = DoubleArray(n) { if (it < 10) 80.0 else 250.0 }
        val pressure = DoubleArray(n) { if (it < 10) 20.0 else 100.0 }
        val map = DoubleArray(n) { if (it < 10) 100.0 else 180.0 }
        val turbine = DoubleArray(n) { if (it < 10) 1050.0 else 2340.0 }
        val data = TripData(
            listOf(Pids.bmw(0x0424), Pids.bmw(0x0C83), 0x0B, Pids.bmw(0x03E0)),
            LongArray(n) { it * 1000L },
            mapOf(Pids.bmw(0x0424) to pressure, Pids.bmw(0x0C83) to flow, 0x0B to map, Pids.bmw(0x03E0) to turbine),
        )
        val filter = TripHealth.of(data)!!.filter!!
        // Solo cuentan las filas con caudal de carretera: 100 hPa con 250 m³/h son 40 por cada 100.
        assertEquals(40.0, filter.resistance!!, 0.001)
        // 2.340 hPa antes de la turbina con 180 kPa de turbo: 1,3 veces.
        assertEquals(1.3, filter.turbineRatio!!, 0.001)
    }

    @Test
    fun `lo que pide el boletin de BMW para salir de 480A y 481A`() {
        // Diez minutos a 95 km/h con el escape a 300 °C, luego dos a 40 km/h, luego tres a 100 km/h a 550 °C.
        val seconds = 15 * 60
        val speed = DoubleArray(seconds) { if (it < 600) 95.0 else if (it < 720) 40.0 else 100.0 }
        val exhaust = DoubleArray(seconds) { if (it < 720) 300.0 else 550.0 }
        val faults = DoubleArray(seconds) { if (it < 800) 2.0 else 0.0 }
        val data = TripData(
            listOf(Pids.SPEED, 0x3C, Pids.BMW_FILTER_FAULTS),
            LongArray(seconds) { it * 1000L },
            mapOf(Pids.SPEED to speed, 0x3C to exhaust, Pids.BMW_FILTER_FAULTS to faults),
        )
        val filter = TripHealth.of(data)!!.filter!!
        assertEquals(10.0, filter.continuousMinutes!!, 0.05)
        assertEquals(3.0, filter.hotMinutes!!, 0.05)
        // Los dos minutos a 40 km/h cortan el tramo: el mas largo es el primero.
        assertEquals(10.0, filter.longestFastMinutes!!, 0.05)
        assertEquals(2.0, filter.faultsStart!!, 0.0)
        assertEquals(0.0, filter.faultsEnd!!, 0.0)
        val text = TripHealth.of(data)!!.facts().toMap()
        assertEquals("481A presente: filtro por encima del máximo al empezar; 480A y 481A no presentes al acabar", text["Averías del filtro"])
    }

    @Test
    fun `las causas de BMW solo salen con el filtro cargado`() {
        val n = 40
        fun trip(request: Double) = TripData(
            listOf(Pids.BMW_REGEN_REQUEST, Pids.BMW_GLOW_FAULT_COUNT),
            LongArray(n) { it * 1000L },
            mapOf(Pids.BMW_REGEN_REQUEST to DoubleArray(n) { request }, Pids.BMW_GLOW_FAULT_COUNT to DoubleArray(n) { 1.0 }),
        )
        val loaded = TripHealth.of(trip(7.0))!!.causes().toMap()
        assertEquals("1 averías de calentadores presentes: míralas en Averías", loaded["Precalentamiento"])
        assertEquals("la app no lo mide", loaded["Mariposas y vacío"])
        assertTrue(TripHealth.of(trip(0.0))?.causes().orEmpty().isEmpty())
    }
}
