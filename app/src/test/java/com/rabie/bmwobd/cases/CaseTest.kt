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

    /** Las medidas que solo se piden con [case] puesto. */
    private fun own(case: Case) = (case.watch + case.also).filter { it in extraIds }.toSet()

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
        assertEquals(base.map { it.id }.toSet() + own(Case.FILTER), shown)

        val watch = Case.FILTER.watch
        val read = turns.ids(watch.size)
        // En cada vuelta una de las vigiladas, todas distintas, y ninguna peticion repetida.
        assertEquals(watch, read.map { turn -> turn.single { it in watch } })
        for (turn in read) assertEquals(turn.size, turn.toSet().size)

        val later = turns.ids(600).flatten()
        assertEquals((base.filter { it.tier != Tier.ONCE }.map { it.id } + own(Case.FILTER)).toSet(), (read.flatten() + later).toSet())
        // Las vigiladas ya no gastan ademas su turno lento.
        assertEquals(600, later.count { it == Pids.BMW_SOOT_MEASURED } * watch.size)
    }

    @Test
    fun `al quitar el caso se dejan de pedir sus medidas y durante un barrido espera`() {
        val turns = Turns(base, Pids.bmwCases)
        turns.use(Case.FILTER)
        assertTrue(turns.ids(40, scanning = true).all { turn -> turn.count { it in Case.FILTER.watch } <= 1 })
        assertFalse(turns.ids(3, scanning = true).flatten().any { it == Pids.bmw(0x041E) })

        // De un caso a otro solo quedan las del nuevo.
        assertEquals(base.map { it.id }.toSet() + own(Case.INJECTORS), turns.use(Case.INJECTORS))
        val injectors = turns.ids(800).flatten().toSet()
        assertTrue(injectors.containsAll(own(Case.INJECTORS)))
        assertTrue(injectors.none { it in own(Case.FILTER) })

        val shown = turns.use(Case.NORMAL)
        assertEquals(base.map { it.id }.toSet(), shown)
        assertTrue(turns.ids(400).flatten().none { it in extraIds })
    }

    @Test
    fun `la tarjeta de inyectores con lo que marco el coche dice que van parejos`() {
        // Ultima lectura del barrido del 11 de octubre.
        val read = Pids.BMW_CYLINDER_CORRECTIONS.zip(listOf(0.624, -0.486, 0.405, -0.627)).toMap() +
            (Pids.bmw(0x14D3) to -0.44) +
            Pids.BMW_INJECTOR_TIMES[0].zip(listOf(35.0, 66.3, 63.4, 65.1)).toMap() +
            Pids.BMW_INJECTOR_TIMES[2].zip(listOf(13.0, 24.2, 18.2, 10.3)).toMap()
        val card = CaseCards.card(Case.INJECTORS, read)!!
        assertEquals(HealthLevel.OK, card.level)
        assertEquals("Los cuatro inyectores van parejos.", card.headline)
        assertEquals("Lo que corrige a cada inyector para igualar los cilindros: 1: +0,6 · 2: -0,5 · 3: +0,4 · 4: -0,6 mg.", card.facts[0])
        assertEquals("Corrección general de la cantidad: -0,44 mg.", card.facts[1])
        assertEquals("Tiempo de más que ha aprendido a darles, punto 1: 1: 35 · 2: 66 · 3: 63 · 4: 65 µs.", card.facts[2])
        // El punto 2 no se ha leido entero todavia: no sale a medias.
        assertEquals("Tiempo de más que ha aprendido a darles, punto 3: 1: 13 · 2: 24 · 3: 18 · 4: 10 µs.", card.facts[3])

        val apart = CaseCards.card(Case.INJECTORS, read + (Pids.BMW_CYLINDER_CORRECTIONS[2] to 2.6))!!
        assertEquals(HealthLevel.WATCH, apart.level)
        assertEquals("El inyector 3 se aparta de los demás: le corrige +2,6 mg.", apart.headline)
        assertEquals(HealthLevel.BAD, CaseCards.card(Case.INJECTORS, read + (Pids.BMW_CYLINDER_CORRECTIONS[1] to -4.5))!!.level)
        assertEquals(HealthLevel.UNKNOWN, CaseCards.card(Case.INJECTORS, emptyMap())!!.level)
    }

    @Test
    fun `la tarjeta del turbo cuenta lo que pide y lo que tiene`() {
        // Lecturas del barrido del 11 de octubre a 113 km/h; 0C86 contesto 6C6D en su maximo.
        assertEquals(105_886.0, Pids.byId.getValue(Pids.bmw(0x0C86)).decodeOrNull(intArrayOf(0x6C, 0x6D))!!, 1.0)
        val read = mapOf(
            Pids.bmw(0x01F4) to 169.0, Pids.MAP to 167.0, Pids.BAROMETRIC to 93.0, Pids.bmw(0x01F9) to 5.0,
            Pids.bmw(0x0BEB) to 85.9, Pids.bmw(0x0C86) to 98_737.0, Pids.bmw(0x0709) to 513.3, Pids.bmw(0x07A2) to 2.3,
            Pids.bmw(0x0BB4) to 28.8, Pids.bmw(0x07AD) to 10.5, Pids.bmw(0x0C87) to 79.4, Pids.bmw(0x076F) to 32.6,
        )
        val card = CaseCards.card(Case.TURBO, read)!!
        assertEquals(HealthLevel.OK, card.level)
        assertEquals("Soplando: pide 169 kPa y tiene 167.", card.headline)
        assertEquals(
            listOf(
                "Diferencia que ve la centralita entre lo pedido y lo real: 5 hPa.",
                "Actuador del turbo al 86 %; el turbo gira a unas 98.737 rpm.",
                "Aire por cilindro: 513 mg, a 2,3 mg de lo pedido.",
                "EGR: válvula al 29 %; recircula el 11 % del gas.",
                "El aire sale del compresor a 79 °C y del intercooler a 33 °C.",
            ),
            card.facts,
        )
        val idle = CaseCards.card(Case.TURBO, read + (Pids.bmw(0x01F4) to 100.0) + (Pids.MAP to 99.0))!!
        assertEquals("Sin exigirle al turbo: pide 100 kPa y tiene 99.", idle.headline)
        assertEquals(HealthLevel.UNKNOWN, CaseCards.card(Case.TURBO, emptyMap())!!.level)
    }

    @Test
    fun `la tarjeta del arranque en frio dice si los calentadores trabajan y si su mando ve un fallo`() {
        // En el barrido del 11 de octubre, durante la regeneracion: 1609 = 1390 W y 160B contesto 31BB.
        assertEquals(1000.0, Pids.byId.getValue(Pids.bmw(0x160B)).decodeOrNull(intArrayOf(0x31, 0xBB))!!, 0.05)
        val clean = Pids.BMW_GLOW_FAULTS.associate { it.first to 0.0 }
        val glowing = clean + mapOf(
            Pids.bmw(0x1609) to 1390.0, Pids.bmw(0x160B) to 1000.0, Pids.COOLANT to 92.0,
            Pids.bmw(0x012C) to 13.94, Pids.bmw(0x01B1) to 39.4,
        )
        val on = CaseCards.card(Case.COLD_START, glowing)!!
        assertEquals("Calentadores encendidos.", on.headline)
        assertEquals(
            listOf(
                "Gastan 1390 W y están a unos 1000 °C.",
                "Motor a 92 °C.",
                "Batería: 13,9 V; margen para arrancar 39 (más alto, mejor).",
                "Su mando no marca ningún fallo ahora.",
            ),
            on.facts,
        )

        val off = glowing + (Pids.bmw(0x1609) to 0.0) + (Pids.bmw(0x160B) to -0.04)
        assertEquals("Calentadores apagados: con el motor caliente no hacen falta.", CaseCards.card(Case.COLD_START, off)!!.headline)
        assertEquals("Calentadores apagados.", CaseCards.card(Case.COLD_START, off + (Pids.COOLANT to 20.0))!!.headline)

        val broken = CaseCards.card(Case.COLD_START, off + (Pids.bmw(0x1604) to 1.0))!!
        assertEquals(HealthLevel.WATCH, broken.level)
        assertEquals("El mando de los calentadores ve un fallo: salida cortada (código 1).", broken.headline)
        assertFalse(broken.facts.any { it.startsWith("Su mando") })
        assertEquals(HealthLevel.UNKNOWN, CaseCards.card(Case.COLD_START, emptyMap())!!.level)
    }

    @Test
    fun `la tarjeta de conduccion dice la marcha, la fuerza y el control de crucero`() {
        // Ultima lectura del barrido del 11 de octubre: sexta a 113 km/h con el crucero fijado en 119,86.
        assertEquals(119.86, Pids.byId.getValue(Pids.bmw(0x0DE2)).decodeOrNull(intArrayOf(0x2E, 0xD2))!!, 0.001)
        val read = mapOf(
            Pids.bmw(0x0EA6) to 6.0, Pids.SPEED to 113.0, Pids.RPM to 1949.0, Pids.bmw(0x07D1) to 210.9,
            Pids.bmw(0x0DE3) to 2.0, Pids.bmw(0x0DE2) to 119.86, Pids.BMW_INJECTION to 27.0,
        )
        val card = CaseCards.card(Case.DRIVING, read)!!
        assertEquals("6ª a 113 km/h, 1.949 rpm.", card.headline)
        assertEquals(
            listOf("El motor da 211 Nm, unos 59 CV ahora.", "Consumo: 6,7 L/100.", "Control de crucero llevando el coche a 120 km/h."),
            card.facts,
        )
        val waiting = CaseCards.card(Case.DRIVING, read + (Pids.bmw(0x0DE3) to 1.0) + (Pids.bmw(0x0DE2) to 84.46) + (Pids.bmw(0x07D1) to -26.0))!!
        assertEquals("El motor no empuja: retiene con 26 Nm.", waiting.facts[0])
        assertEquals("Control de crucero en espera; velocidad guardada 84 km/h.", waiting.facts[2])
        val stopped = CaseCards.card(Case.DRIVING, mapOf(Pids.bmw(0x0EA6) to 0.0, Pids.SPEED to 0.0, Pids.RPM to 850.0, Pids.bmw(0x0DE3) to 0.0))!!
        assertEquals("Punto muerto, 0 km/h, 850 rpm.", stopped.headline)
        assertEquals(listOf("Control de crucero apagado."), stopped.facts)
        assertEquals(HealthLevel.UNKNOWN, CaseCards.card(Case.DRIVING, emptyMap())!!.level)
    }

    @Test
    fun `la tarjeta electrica dice si carga y solo avisa con tension baja en marcha`() {
        // Lecturas del barrido del 11 de octubre: 01B2 contesto 09F7 (4,08 A) y 01B9 contesto 89E4 (14,83 V).
        assertEquals(4.08, Pids.byId.getValue(Pids.bmw(0x01B2)).decodeOrNull(intArrayOf(0x09, 0xF7))!!, 0.001)
        assertEquals(14.825, Pids.byId.getValue(Pids.bmw(0x01B9)).decodeOrNull(intArrayOf(0x89, 0xE4))!!, 0.001)
        val read = mapOf(
            Pids.RPM to 1950.0, Pids.bmw(0x01B2) to 4.08, Pids.bmw(0x01B9) to 14.83, Pids.bmw(0x01AF) to 14.8,
            Pids.bmw(0x0186) to 18.7, Pids.bmw(0x0E43) to 56.0, Pids.bmw(0x01B1) to 39.4, Pids.bmw(0x01B8) to 25.5,
            Pids.bmw(0x10CE) to 617.0, Pids.bmw(0x10D5) to 82.16,
        )
        val card = CaseCards.card(Case.ELECTRIC, read)!!
        assertEquals("La batería está cargando: entran 4,1 A.", card.headline)
        assertEquals(
            listOf(
                "Tensión: 14,8 V; el coche le pide 14,8 V al alternador.",
                "Alternador al 19 % de carga, a 56 °C.",
                "Margen de la batería para arrancar: 39 (más alto, mejor); batería a 26 °C.",
                "Antes de este arranque estuvo aparcado 10,3 h.",
                "Energía recuperada en retenciones: 82,2 Ah.",
            ),
            card.facts,
        )
        val giving = CaseCards.card(Case.ELECTRIC, read + (Pids.bmw(0x01B2) to -6.0) + (Pids.bmw(0x01B8) to -50.0))!!
        assertEquals(HealthLevel.OK, giving.level)
        assertEquals("La batería está dando 6,0 A. En marcha es normal a ratos: carga en las retenciones.", giving.headline)
        assertEquals("Margen de la batería para arrancar: 39 (más alto, mejor).", giving.facts[2])
        assertEquals("Batería en equilibrio: ni carga ni descarga.", CaseCards.card(Case.ELECTRIC, read + (Pids.bmw(0x01B2) to -0.4))!!.headline)

        val low = CaseCards.card(Case.ELECTRIC, read + (Pids.bmw(0x01B9) to 11.9))!!
        assertEquals(HealthLevel.WATCH, low.level)
        assertEquals("Solo 11,9 V con el motor en marcha: el alternador no está cargando.", low.headline)
        // Con el motor parado esa tension no es un aviso.
        assertEquals(HealthLevel.OK, CaseCards.card(Case.ELECTRIC, read + (Pids.bmw(0x01B9) to 11.9) + (Pids.RPM to 0.0))!!.level)
        assertEquals(HealthLevel.UNKNOWN, CaseCards.card(Case.ELECTRIC, emptyMap())!!.level)
    }

    @Test
    fun `las medidas de inyectores salen con la formula de la tabla`() {
        // 1462 contesto 81B2 en el barrido.
        assertEquals(0.62, Pids.byId.getValue(Pids.BMW_CYLINDER_CORRECTIONS[0]).decodeOrNull(intArrayOf(0x80, 0xCA))!!, 0.01)
        assertEquals(66.3, Pids.byId.getValue(Pids.BMW_INJECTOR_TIMES[0][1]).decodeOrNull(intArrayOf(0x81, 0xB2))!!, 0.05)
        assertEquals(Pids.all.size, Pids.byId.size)
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
        // Con un 1 la tiene pendiente pero no ha empezado: asi estuvo veinte minutos el 12 de octubre.
        val waiting = during + (Pids.BMW_REGEN_REQUEST to 1.0)
        assertEquals("La centralita tiene una regeneración pendiente.", CaseCards.card(Case.FILTER, waiting)!!.headline)

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
