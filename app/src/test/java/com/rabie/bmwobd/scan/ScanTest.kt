package com.rabie.bmwobd.scan

import com.rabie.bmwobd.obd.ObdSession
import com.rabie.bmwobd.obd.SimulatedTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

class ScanTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val measures = ScanPlan.SERVICES.size

    @Test
    fun `la primera pasada pregunta por los servicios y por todas las direcciones`() {
        assertEquals("1A80", ScanPlan.query(0))
        assertEquals("2C100000", ScanPlan.query(measures))
        assertEquals("2C100547", ScanPlan.query(measures + 0x0547))
        assertEquals("2C101BFF", ScanPlan.query(ScanPlan.SIZE - 1))
        assertTrue(ScanPlan.SERVICES.all { it.take(2) in setOf("1A", "18", "21", "22") })
    }

    @Test
    fun `la respuesta afirmativa se reconoce por el servicio mas cuarenta`() {
        assertEquals("6C10", ScanPlan.answerPrefix("2C100547"))
        assertEquals("5A80", ScanPlan.answerPrefix("1A80"))
        assertEquals("6105", ScanPlan.answerPrefix("2105"))
        assertEquals("624021", ScanPlan.answerPrefix("224021"))
        assertEquals("58", ScanPlan.answerPrefix("1802FFFF"))
    }

    @Test
    fun `tras la primera pasada repite solo lo que contesto`() {
        val plan = ScanPlan(next = ScanPlan.SIZE - 3, found = listOf(0, measures + 0x0547))
        assertEquals(1, plan.pass)
        assertEquals("2C101BFD", plan.take())
        plan.answered()
        assertEquals("2C101BFE", plan.take())
        assertEquals("2C101BFF", plan.take())
        // Ya no queda nada nuevo: empieza a repetir las tres que contestan.
        assertEquals(listOf("1A80", "2C100547", "2C101BFD"), List(3) { plan.take() })
        assertEquals(2, plan.pass)
        assertEquals("1A80", plan.take())
        assertEquals(3, plan.pass)
        assertEquals(3, plan.answering)
    }

    @Test
    fun `si nada contesta el barrido se queda sin preguntas`() {
        val plan = ScanPlan(next = ScanPlan.SIZE)
        assertNull(plan.take())
    }

    @Test
    fun `el avance se guarda y se retoma en la conexion siguiente`() {
        val store = ScanStore(folder.root)
        assertTrue(store.load("coche").fresh)
        assertFalse(store.load("coche").active)

        val plan = ScanPlan()
        repeat(40) { plan.take() }
        plan.answered()
        store.save("coche", plan, active = true)

        val saved = store.load("coche")
        assertTrue(saved.active)
        assertEquals(40, saved.next)
        assertEquals(listOf(39), saved.found)
        assertEquals(ScanPlan.query(40), ScanPlan(saved.next, saved.found, saved.pass).take())

        store.reset("coche")
        assertTrue(store.load("coche").fresh)
    }

    @Test
    fun `el fichero lleva la respuesta y como iba el motor`() {
        val store = ScanStore(folder.root)
        val writer = store.writer("coche")
        writer.append(1, "2C100547", intArrayOf(0x39, 0xFE), mapOf(0x0C to 842.0, 0x05 to 48.0), Date(0))
        writer.close()
        // Al reabrir no repite la cabecera.
        store.writer("coche").close()
        val lines = store.file("coche").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("fecha,pasada,consulta,respuesta,rpm,velocidad,carga,refrigerante"))
        assertTrue(lines[1].endsWith(",1,2C100547,39FE,842.0,,,48.0,,,,,,,"))
    }

    @Test
    fun `contra el coche simulado contestan las medidas y la identificacion, y nada mas`() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        log.clear()

        val answered = mutableMapOf<String, IntArray>()
        val queries = listOf("1A80", "2105", "224021", "2C100547", "2C100500", "2C107777")
        for (query in queries) session.ask(query, ScanPlan.answerPrefix(query))?.let { answered[query] = it }

        assertEquals(setOf("1A80", "2C100547", "2C100500"), answered.keys)
        assertEquals(2, answered.getValue("2C100547").size)
        // El barrido no llena el registro.
        assertTrue(log.isEmpty())
    }
}
