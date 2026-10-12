package com.rabie.bmwobd.dash

import com.rabie.bmwobd.Turns
import com.rabie.bmwobd.advice.Advice
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.cases.Case
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTest {

    private val base = Pids.all.filter { it !in Pids.bmwCases }
    private val extraIds = Pids.bmwCases.map { it.id }.toSet()

    private fun Turns.ids(turns: Int) = List(turns) { next().flatMap { request -> request.defs.map { it.id } } }.flatten()

    @Test
    fun `las medidas del panel se leen siempre, sin caso y por el turno lento`() {
        assertTrue(Dashboard.background.all { it in extraIds })
        val turns = Turns(base, Pids.bmwCases, Dashboard.background)
        assertEquals(base.map { it.id }.toSet() + Dashboard.background, turns.shown)

        val read = turns.ids(600)
        assertEquals((base.filter { it.tier != Tier.ONCE }.map { it.id } + Dashboard.background).toSet(), read.toSet())
        // Ninguna gasta mas de un sitio por ronda del turno lento.
        assertTrue(Dashboard.background.all { id -> read.count { it == id } <= read.count { it == Pids.COOLANT } + 1 })
    }

    @Test
    fun `con un caso puesto las del panel siguen y al quitarlo no se pierden`() {
        val turns = Turns(base, Pids.bmwCases, Dashboard.background)
        val shown = turns.use(Case.FILTER)
        assertTrue(shown.containsAll(Dashboard.background))
        val during = turns.ids(800).toSet()
        assertTrue(during.containsAll(Dashboard.background))
        assertTrue(during.containsAll(Case.FILTER.watch))

        assertEquals(base.map { it.id }.toSet() + Dashboard.background, turns.use(Case.NORMAL))
        val after = turns.ids(600).toSet()
        assertTrue(after.containsAll(Dashboard.background))
        assertTrue(after.none { it in extraIds && it !in Dashboard.background })
    }

    @Test
    fun `lo que esta en pantalla se lee una medida por vuelta y nada se pide dos veces`() {
        val turns = Turns(base, Pids.bmwCases, Dashboard.background)
        val looking = Dashboard.watched(DashGroup.ENGINE) + Dashboard.watched(DashGroup.ELECTRIC)
        turns.use(Case.NORMAL, looking)
        val rounds = List(looking.size * 3) { turns.next().flatMap { request -> request.defs.map { it.id } } }
        for (turn in rounds) assertEquals(turn.size, turn.toSet().size)
        // Cada medida a la vista sale una vez por ronda, no cada minuto.
        for (id in looking) assertEquals("%X".format(id), 3, rounds.count { id in it })

        // Con un aviso que pone su caso, se suman las del caso sin repetirse.
        turns.use(Case.FILTER, Dashboard.watched(DashGroup.FILTER) + Dashboard.watched(DashGroup.TURBO))
        val mixed = List(60) { turns.next().flatMap { request -> request.defs.map { it.id } } }
        for (turn in mixed) assertEquals(turn.size, turn.toSet().size)
        assertTrue(mixed.flatten().containsAll(Case.FILTER.watch + Dashboard.watched(DashGroup.TURBO).filter { it in Pids.byId && it in turns.shown }))

        // Una medida que ya se lee en cada vuelta no ocupa ademas un sitio de las vigiladas.
        turns.use(Case.NORMAL, listOf(Pids.RPM, Pids.MAP))
        val fast = List(4) { turns.next().flatMap { request -> request.defs.map { it.id } } }
        for (turn in fast) assertEquals(1, turn.count { it == Pids.RPM })
    }

    @Test
    fun `una medida se da por callada a los pocos silencios seguidos y vuelve al contestar`() {
        val turns = Turns(base, Pids.bmwCases, Dashboard.background)
        val slow = turns.requests.first { it.pid == Pids.COOLANT }
        val fast = turns.requests.first { it.pid == Pids.RPM }
        val silence = com.rabie.bmwobd.Silence()

        assertEquals(listOf(false, true, false), List(3) { silence.missed(slow) })
        // Un silencio suelto entre respuestas no cuenta.
        silence.answered(slow)
        assertEquals(false, silence.missed(slow))
        silence.answered(slow)
        assertEquals(listOf(false, true), List(2) { silence.missed(slow) })
        // Las que se leen en cada vuelta aguantan uno mas.
        assertEquals(listOf(false, false, true, false), List(4) { silence.missed(fast) })
    }

    @Test
    fun `las tarjetas del centro son cuatro numeros con su unidad`() {
        val values = mapOf(
            Pids.RPM to 1949.0, Pids.SPEED to 113.0, Pids.BMW_INJECTION to 21.0, Pids.COOLANT to 91.0,
            Pids.bmw(0x041E) to 440.4, Pids.bmw(0x076F) to 32.6, Pids.bmw(0x0E43) to 56.0, Pids.bmw(0x07D1) to 210.9,
            Pids.MAP to 167.0, Pids.BAROMETRIC to 93.0, 0x23 to 720.0,
        )
        val (trip, temperatures, engine) = Dashboard.cards(values, tripKm = 42.63, elapsedMs = 48 * 60_000L + 12_000, average = 5.42, diesel = true)
        assertEquals(listOf(DashCell("42,6", "km"), DashCell("0:48", "h:min"), DashCell("≈ 5,4", "Ø L/100"), DashCell("≈ 5,2", "L/100")), trip)
        assertEquals(
            listOf(DashCell("91", "°C agua"), DashCell("440", "°C escape"), DashCell("33", "°C aire"), DashCell("56", "°C alternador")),
            temperatures,
        )
        assertEquals(listOf(DashCell("211", "Nm"), DashCell("≈ 59", "CV"), DashCell("0,74", "bar turbo"), DashCell("720", "bar raíl")), engine)

        // Parado el consumo sale por hora; sin datos, rayas; y pasada la hora, el tiempo con horas.
        val stopped = Dashboard.cards(values + (Pids.SPEED to 0.0) + (Pids.RPM to 800.0), 0.0, 75 * 60_000L, null, true)
        assertEquals(listOf(DashCell("0,0", "km"), DashCell("1:15", "h:min"), DashCell("—", "Ø L/100"), DashCell("≈ 2,4", "L/h")), stopped[0])
        val empty = Dashboard.cards(emptyMap(), 0.0, 0L, null, true)
        assertEquals(3, empty.size)
        assertTrue(empty.drop(1).flatten().all { it.value == Dashboard.MISSING })
    }

    @Test
    fun `la presion del turbo sale de un mismo mensaje cuando el coche lo da`() {
        val own = mapOf(Pids.bmw(0x01F4) to 169.0, Pids.MAP to 150.0)
        assertEquals("169 / 150", Dashboard.tiles(DashGroup.TURBO, own).first().value)
        val together = own + (com.rabie.bmwobd.advice.Moment.BOOST_COMMANDED to 168.0) + (com.rabie.bmwobd.advice.Moment.BOOST_ACTUAL to 166.0)
        assertEquals("168 / 166", Dashboard.tiles(DashGroup.TURBO, together).first().value)
    }

    @Test
    fun `cada grupo da tres datos escritos y una raya en lo que falta`() {
        val values = mapOf(
            Pids.RPM to 1949.0, Pids.MAP to 167.0, Pids.BMW_SOOT_MEASURED to 21.1, Pids.bmw(0x03EB) to 31.95,
            Pids.bmw(0x041E) to 440.8, Pids.bmw(0x01F4) to 169.0, Pids.bmw(0x0C86) to 98_737.0, Pids.bmw(0x0BEB) to 85.9,
            Pids.bmw(0x07D1) to 210.9, Pids.BMW_INJECTION to 27.0, Pids.bmw(0x01B9) to 14.11, Pids.bmw(0x01B2) to 4.08,
            Pids.bmw(0x0E43) to 56.0,
        ) + Pids.BMW_CYLINDER_CORRECTIONS.zip(listOf(0.62, -0.49, 0.41, -0.63)).toMap()

        assertEquals(
            listOf(DashTile("Hollín medido", "21,1", "g"), DashTile("Desde regenerar", "32", "km"), DashTile("Escape", "441", "°C")),
            Dashboard.tiles(DashGroup.FILTER, values),
        )
        assertEquals(
            listOf(DashTile("Pide / tiene", "169 / 167", "kPa"), DashTile("Giro", "98.737", "rpm"), DashTile("Actuador", "86", "%")),
            Dashboard.tiles(DashGroup.TURBO, values),
        )
        assertEquals(
            listOf(DashTile("Par", "211", "Nm"), DashTile("Potencia", "≈ 59", "CV"), DashTile("Inyecta", "27,0", "mg")),
            Dashboard.tiles(DashGroup.ENGINE, values),
        )
        assertEquals(
            listOf(DashTile("Cil. 1 · 2", "+0,6 · -0,5", "mg"), DashTile("Cil. 3 · 4", "+0,4 · -0,6", "mg"), DashTile("Raíl", "—", "bar")),
            Dashboard.tiles(DashGroup.INJECTORS, values),
        )
        assertEquals(
            listOf(DashTile("Batería", "14,1", "V"), DashTile("Corriente", "+4,1", "A"), DashTile("Alternador", "56", "°C")),
            Dashboard.tiles(DashGroup.ELECTRIC, values),
        )
        for (group in DashGroup.entries) {
            val empty = Dashboard.tiles(group, emptyMap())
            assertEquals(3, empty.size)
            assertTrue(empty.all { Dashboard.MISSING in it.value })
        }
    }

    @Test
    fun `un aviso fija su grupo y los kilometros que quedan salen de una cuenta`() {
        fun advice(id: String) = Advice(id, Severity.WARN, "", "")
        assertEquals(DashGroup.FILTER, Dashboard.group(advice("regen_stop")))
        assertEquals(DashGroup.ENGINE, Dashboard.group(advice("cold_push")))
        assertEquals(DashGroup.TURBO, Dashboard.group(advice("boost_low")))
        assertEquals(DashGroup.ELECTRIC, Dashboard.group(advice("voltage")))
        assertNull(Dashboard.group(advice("otra_cosa")))

        val tank = Pids.bmw(Pids.BMW_TANK_ADDRESS) to 31.0
        assertEquals(596.0, Dashboard.rangeKm(mapOf(tank), 5.2)!!, 0.5)
        // Sin media del trayecto todavia, la de toda la vida del coche: 18.970 L en 378.420 km.
        val life = mapOf(tank, Pids.bmw(Pids.BMW_ODOMETER_ADDRESS) to 378_420.0, Pids.bmw(Pids.BMW_TOTAL_FUEL_ADDRESS) to 18_970.0)
        assertEquals(618.0, Dashboard.rangeKm(life, null)!!, 1.0)
        assertNull(Dashboard.rangeKm(mapOf(tank), null))
        assertNull(Dashboard.rangeKm(emptyMap(), 5.2))
    }
}
