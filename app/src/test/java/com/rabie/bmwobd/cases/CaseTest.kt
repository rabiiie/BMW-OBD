package com.rabie.bmwobd.cases

import com.rabie.bmwobd.Turns
import com.rabie.bmwobd.advice.Advisor
import com.rabie.bmwobd.health.Health
import com.rabie.bmwobd.health.HealthLevel
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.Tier
import com.rabie.bmwobd.trips.TripCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseTest {

    private val base = Pids.all.filter { it !in Pids.bmwCases }
    private val extraIds = Pids.bmwCases.map { it.id }.toSet()

    private fun Turns.ids(turns: Int, scanning: Boolean = false): List<List<Int>> =
        List(turns) { next(scanning).flatMap { request -> request.defs.map { it.id } } }

    @Test
    fun `todas las medidas de los casos existen y las propias no estan en la lectura normal`() {
        for (case in Case.entries) {
            for (id in case.watch + case.also) assertTrue("%X".format(id), id in Pids.byId)
            assertTrue(case.watch.intersect(case.also.toSet()).isEmpty())
        }
        assertTrue(Pids.bmwDiesel.none { it.id in extraIds })
        assertEquals(extraIds, Case.entries.flatMap { it.watch + it.also }.filter { it in extraIds }.toSet())
    }

    @Test
    fun `sin caso no se pide ninguna medida propia de los casos`() {
        val turns = Turns(base, Pids.bmwCases)
        assertTrue(turns.requests.none { request -> request.defs.any { it.id in extraIds } })
        val read = turns.ids(400).flatten().toSet()
        assertTrue(read.none { it in extraIds })
        // Y lo de siempre se sigue leyendo entero, salvo lo que se lee una vez.
        assertEquals(base.filter { it.tier != Tier.ONCE }.map { it.id }.toSet(), read)
    }

    @Test
    fun `con el caso del filtro sus medidas se leen una por vuelta y las demas siguen`() {
        val turns = Turns(base, Pids.bmwCases)
        val shown = turns.use(Case.FILTER)
        assertEquals(base.map { it.id }.toSet() + extraIds, shown)

        val watch = Case.FILTER.watch
        val read = turns.ids(watch.size)
        // En cada vuelta una de las vigiladas, todas distintas, y ninguna peticion repetida.
        assertEquals(watch, read.map { turn -> turn.single { it in watch } })
        for (turn in read) assertEquals(turn.size, turn.toSet().size)

        val later = turns.ids(600).flatten()
        assertEquals((base.filter { it.tier != Tier.ONCE }.map { it.id } + extraIds).toSet(), (read.flatten() + later).toSet())
        // Las vigiladas ya no gastan ademas su turno lento.
        assertEquals(600, later.count { it == Pids.BMW_SOOT_MEASURED } * watch.size)
    }

    @Test
    fun `al quitar el caso se dejan de pedir sus medidas y durante un barrido espera`() {
        val turns = Turns(base, Pids.bmwCases)
        turns.use(Case.FILTER)
        assertTrue(turns.ids(40, scanning = true).all { turn -> turn.count { it in Case.FILTER.watch } <= 1 })
        assertFalse(turns.ids(3, scanning = true).flatten().any { it == Pids.bmw(0x041E) })

        val shown = turns.use(Case.NORMAL)
        assertEquals(base.map { it.id }.toSet(), shown)
        assertTrue(turns.ids(400).flatten().none { it in extraIds })
    }

    @Test
    fun `un coche sin medidas de BMW no cambia con el caso puesto`() {
        val standard = base.filter { !Pids.isBmw(it.pid) }
        val turns = Turns(standard, emptyList())
        val before = turns.ids(50)
        val other = Turns(standard, emptyList())
        assertEquals(standard.map { it.id }.toSet(), other.use(Case.FILTER))
        assertEquals(before, other.ids(50))
    }

    @Test
    fun `la tarjeta del filtro dice si regenera con lo que marco el coche`() {
        // Lecturas del 11 de octubre: regenerando a las 12:14 y ya terminada a las 12:26.
        val during = mapOf(
            Pids.BMW_REGEN_REQUEST to 5.0, Pids.BMW_SOOT_MEASURED to 22.9, Pids.BMW_SOOT_MODEL to 22.8,
            Pids.bmw(0x03EB) to 311.0, Pids.bmw(0x0408) to 376.4, Pids.bmw(0x041E) to 413.1, Pids.bmw(0x0434) to 446.9,
            Pids.bmw(0x0424) to 198.0, Pids.bmw(0x03F1) to 23.9, Pids.bmw(0x05AA) to 0x02010002.toDouble(),
        )
        val card = CaseCards.card(Case.FILTER, during)!!
        assertEquals("Está regenerando.", card.headline)
        assertEquals(HealthLevel.WATCH, card.level)
        assertEquals("Hollín: 22,9 g medidos por la presión y 22,8 g calculados por el uso.", card.facts[0])
        assertEquals("Última regeneración: hace 311 km y 376 min de motor.", card.facts[1])
        assertEquals("Escape: 413 °C antes del catalizador y 447 °C antes del filtro.", card.facts[2])
        assertEquals("Al escape le cuesta 198 hPa cruzar el filtro; resistencia 23,9.", card.facts[3])

        val after = during + (Pids.BMW_REGEN_REQUEST to 0.0) + (Pids.bmw(0x05AA) to 0x00100001.toDouble())
        assertEquals("No está regenerando.", CaseCards.card(Case.FILTER, after)!!.headline)
        val waiting = during + (Pids.bmw(0x05AA) to 0x00100001.toDouble())
        assertEquals("La centralita quiere regenerar y todavía no ha empezado.", CaseCards.card(Case.FILTER, waiting)!!.headline)

        assertEquals(HealthLevel.UNKNOWN, CaseCards.card(Case.FILTER, emptyMap())!!.level)
        assertNull(CaseCards.card(Case.NORMAL, during))
    }

    @Test
    fun `si la centralita dice que regenera no hace falta deducirlo por la temperatura`() {
        val cruising = mapOf(Pids.RPM to 1900.0, Pids.SPEED to 80.0, Pids.LOAD to 40.0, Pids.COOLANT to 90.0)
        fun trip(asked: (Int) -> Double, distance: (Int) -> Double) = TripCsv.parse(
            sequence {
                val columns = cruising.keys.toList() + Pids.BMW_REGEN_REQUEST + Pids.bmw(0x03EB)
                yield(TripCsv.header(columns))
                for (s in 0..900) {
                    yield(TripCsv.row(s * 1000L, columns, cruising + (Pids.BMW_REGEN_REQUEST to asked(s)) + (Pids.bmw(0x03EB) to distance(s))))
                }
            },
        )

        val whole = Health.regeneration(trip({ if (it in 100..700) 5.0 else 0.0 }, { if (it <= 700) 300.0 + it / 60.0 else (it - 700) / 60.0 }))!!
        assertEquals(100_000L, whole.startMs)
        assertEquals(601L, whole.seconds)
        assertEquals(true, whole.finished)

        val cut = Health.regeneration(trip({ if (it >= 500) 5.0 else 0.0 }, { 300.0 + it / 60.0 }))!!
        assertEquals(false, cut.finished)

        assertNull(Health.regeneration(trip({ 0.0 }, { 300.0 + it / 60.0 })))

        // En directo: con la peticion a cero, el escape caliente ya no se toma por una regeneracion.
        val advisor = Advisor()
        val hot = cruising + (0x3C to 600.0) + (Pids.LAMBDA to 1.3)
        var advice = advisor.update(0, hot + (Pids.BMW_REGEN_REQUEST to 0.0))
        for (s in 1..90) advice = advisor.update(s * 1000L, hot + (Pids.BMW_REGEN_REQUEST to 0.0))
        assertTrue(advice.none { it.id == "regeneration" })
        for (s in 91..180) advice = advisor.update(s * 1000L, cruising + (Pids.BMW_REGEN_REQUEST to 5.0))
        assertEquals("El filtro está regenerando", advice.first { it.id == "regeneration" }.title)
    }
}
