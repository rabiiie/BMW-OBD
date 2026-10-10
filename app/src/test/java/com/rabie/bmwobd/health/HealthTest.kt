package com.rabie.bmwobd.health

import com.rabie.bmwobd.advice.Advisor
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.trips.TripCsv
import com.rabie.bmwobd.trips.TripData
import com.rabie.bmwobd.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {

    private val bmw = Vehicle.GENERIC.copy(name = "118d N47", make = "BMW", displacementLiters = 1.995)

    private fun real(name: String): TripData {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(name))
        return stream.bufferedReader().useLines { TripCsv.parse(it) }
    }

    /** Un trayecto de [seconds] segundos con las mismas lecturas, o las que de [at] para cada segundo. */
    private fun trip(seconds: Int, at: (Int) -> Map<Int, Double>): TripData {
        val columns = at(0).keys.toList()
        val lines = sequence {
            yield(TripCsv.header(columns))
            for (s in 0..seconds) yield(TripCsv.row(s * 1000L, columns, at(s)))
        }
        return TripCsv.parse(lines)
    }

    private fun List<HealthCard>.of(system: HealthSystem) = first { it.system == system }

    private val cruising = mapOf(
        Pids.RPM to 1800.0, Pids.SPEED to 100.0, Pids.COOLANT to 90.0, Pids.LOAD to 45.0,
        Pids.MAP to 150.0, Pids.BAROMETRIC to 95.0, Moment.MODULE_VOLTAGE to 13.9, Moment.RAIL to 600.0,
    )

    @Test
    fun `el trayecto real con regeneracion se cuenta como lo que fue`() {
        // 72 min del 118d el 9 de octubre, una fila de cada ocho: regenero del minuto 45 al 57.
        val data = real("trayecto_real_regeneracion.csv")
        val stats = TripCsv.stats(data)
        val cards = Health.cards(data, Advisor.review(data, bmw))
        val summary = Health.summary(data, stats, cards)
        println(summary.joinToString("\n"))
        for (card in cards) println("${card.system.title} [${card.level}] ${card.headline}\n  " + (card.facts + listOfNotNull(card.advice)).joinToString("\n  "))

        val regen = Health.regeneration(data)!!
        assertEquals(true, regen.finished)
        assertTrue("minutos de regeneración: ${regen.seconds / 60}", regen.seconds in 8 * 60L..14 * 60L)
        assertTrue(regen.startMs in 43 * 60_000L..47 * 60_000L)

        assertEquals(HealthLevel.WATCH, cards.of(HealthSystem.FILTER).level)
        assertEquals(HealthLevel.OK, cards.of(HealthSystem.TURBO).level)
        assertEquals(HealthLevel.OK, cards.of(HealthSystem.INJECTION).level)
        assertEquals(HealthLevel.OK, cards.of(HealthSystem.COOLING).level)
        assertEquals(HealthLevel.OK, cards.of(HealthSystem.ELECTRIC).level)
        assertTrue(summary.any { it.contains("regeneró") && it.contains("terminó") })
        assertEquals("A mirar: filtro de partículas.", summary.last())
    }

    @Test
    fun `un trayecto sin medidas de la marca no opina de lo que no ve`() {
        val data = real("trayecto_real_acelerones.csv")
        val cards = Health.cards(data, Advisor.review(data, bmw))
        assertEquals(HealthLevel.UNKNOWN, cards.of(HealthSystem.FILTER).level)
        assertEquals(HealthLevel.UNKNOWN, cards.of(HealthSystem.INJECTION).level)
        assertEquals(HealthLevel.UNKNOWN, cards.of(HealthSystem.TURBO).level)
        assertEquals(HealthLevel.OK, cards.of(HealthSystem.COOLING).level)
        assertNull(Health.regeneration(data))
        assertTrue(Health.summary(data, TripCsv.stats(data), cards).last().startsWith("Nada fuera de lo normal"))
    }

    @Test
    fun `con los datos fijos del coche salen su ficha, el aceite, las averias y el ritmo de regeneraciones`() {
        // Lo que dio el 118d el 10 de octubre.
        val fixed = mapOf(
            Pids.bmw(Pids.BMW_ODOMETER_ADDRESS) to 378_420.0, Pids.bmw(0x0AF0) to 7_237.0,
            Pids.bmw(Pids.BMW_TOTAL_FUEL_ADDRESS) to 18_970.0, Pids.bmw(Pids.BMW_TANK_ADDRESS) to 21.0,
            Pids.bmw(Pids.BMW_FAULTS_ADDRESS) to 7.0, Pids.bmw(Pids.BMW_OIL_KM_ADDRESS) to 9_360.0,
            Pids.bmw(Pids.BMW_OIL_LEVEL_ADDRESS) to 56.0, Pids.bmw(0x03F3) to 517.0, Pids.bmw(0x0407) to 373.0,
            Pids.bmw(0x0BA5) to 287_450.0, Pids.bmw(0x03E9) to 21.1, Pids.bmw(0x042E) to -40.05,
            Pids.bmw(0x03FD) to 376_222.1, Pids.bmw(0x03FE) to 376_087.7, Pids.bmw(0x03FF) to 375_998.3,
            Pids.bmw(0x0400) to 375_793.8, Pids.bmw(0x0401) to 375_534.8,
        )
        val data = trip(120) { cruising + fixed }
        val cards = Health.cards(data, emptyList())
        for (card in cards) println("${card.system.title} [${card.level}] ${card.headline} >> " + (card.facts + listOfNotNull(card.advice)).joinToString(" >> "))
        val car = Health.carFacts(data)
        println(car.joinToString(" >> ") { "${it.first}: ${it.second}" })

        val filter = cards.of(HealthSystem.FILTER)
        assertEquals(HealthLevel.WATCH, filter.level)
        assertEquals("Regenera mucho más seguido que su media.", filter.headline)
        assertTrue(filter.facts.any { it.contains("cada 134, 89, 205, 259 km") && it.contains("517") })
        assertTrue(filter.facts.any { it.contains("-40 °C") })
        assertEquals(HealthLevel.OK, cards.of(HealthSystem.OIL).level)
        assertTrue(cards.of(HealthSystem.OIL).facts.first().contains("9.360 km"))
        assertEquals(HealthLevel.WATCH, cards.of(HealthSystem.FAULTS).level)
        assertTrue(cards.of(HealthSystem.FAULTS).headline.contains("guarda 7"))
        assertEquals("378.420 km", car.first { it.first == "Kilómetros" }.second)
        assertEquals("5,0 L/100 (18.970 L)", car.first { it.first.startsWith("Consumo medio") }.second)
    }

    @Test
    fun `un coche que no da esos datos no saca tarjetas vacias`() {
        val cards = Health.cards(trip(60) { cruising }, emptyList())
        assertTrue(cards.none { it.system == HealthSystem.OIL || it.system == HealthSystem.FAULTS })
        assertTrue(Health.carFacts(trip(60) { cruising }).isEmpty())
        // Con regeneraciones al ritmo de su media no hay aviso.
        val steady = mapOf(
            Pids.bmw(0x03F3) to 500.0, Pids.bmw(0x03FD) to 10_000.0, Pids.bmw(0x03FE) to 9_520.0,
            Pids.bmw(0x03FF) to 9_010.0, Pids.bmw(0x0400) to 8_480.0, Pids.bmw(0x0401) to 8_000.0,
        )
        assertEquals(HealthLevel.OK, Health.cards(trip(60) { cruising + steady }, emptyList()).of(HealthSystem.FILTER).level)
    }

    @Test
    fun `turbo que no llega a lo pedido`() {
        val asking = cruising + (Pids.bmw(0x01F4) to 220.0) + (Pids.LOAD to 95.0)
        fun level(actual: Double) =
            Health.cards(trip(60) { asking + (Pids.MAP to actual) }, emptyList()).of(HealthSystem.TURBO).level
        assertEquals(HealthLevel.OK, level(216.0))
        assertEquals(HealthLevel.WATCH, level(195.0))
        assertEquals(HealthLevel.BAD, level(170.0))
        // Al ralenti lo pedido es la presion ambiente: no hay nada que juzgar.
        val idle = trip(60) { cruising + (Pids.bmw(0x01F4) to 99.0) + (Pids.MAP to 95.0) }
        assertEquals(HealthLevel.UNKNOWN, Health.cards(idle, emptyList()).of(HealthSystem.TURBO).level)
    }

    @Test
    fun `rail que no sigue a lo pedido`() {
        fun level(actual: Double) = Health.cards(
            trip(120) { cruising + (Pids.bmw(0x0641) to 600.0) + (Moment.RAIL to actual) }, emptyList(),
        ).of(HealthSystem.INJECTION).level
        assertEquals(HealthLevel.OK, level(590.0))
        assertEquals(HealthLevel.WATCH, level(520.0))
        assertEquals(HealthLevel.BAD, level(450.0))
    }

    @Test
    fun `apagar a media regeneracion se dice`() {
        val data = trip(600) { s ->
            val regenerating = s >= 300
            cruising + (Moment.CATALYST_TEMP to if (regenerating) 580.0 else 250.0) +
                (Pids.LAMBDA to if (regenerating) 1.3 else 2.0) +
                (Pids.bmw(0x03EB) to 400.0 + s / 36.0) +
                (Pids.BMW_SOOT_MEASURED to 28.0) + (Pids.BMW_SOOT_MODEL to 26.0)
        }
        val regen = Health.regeneration(data)
        assertNotNull(regen)
        assertEquals(false, regen!!.finished)
        val card = Health.cards(data, emptyList()).of(HealthSystem.FILTER)
        assertEquals(HealthLevel.WATCH, card.level)
        assertEquals("Se apagó el motor a media regeneración.", card.headline)
    }

    @Test
    fun `a fondo el escape caliente no es una regeneracion`() {
        val data = trip(300) { cruising + (Pids.LOAD to 100.0) + (Moment.CATALYST_TEMP to 600.0) + (Pids.LAMBDA to 1.15) }
        assertNull(Health.regeneration(data))
    }
}
