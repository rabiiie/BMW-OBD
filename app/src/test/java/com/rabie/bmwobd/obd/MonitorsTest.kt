package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorsTest {

    @Test
    fun `un diesel con todo completo salvo el filtro de particulas`() {
        val readiness = Readiness.parse(intArrayOf(0x00, 0x0F, 0xC9, 0x40))!!
        assertTrue(readiness.diesel)
        assertEquals(7, readiness.available.size)
        assertEquals(listOf("Filtro de partículas"), readiness.pending.map { it.name })
    }

    @Test
    fun `un gasolina recien borrado tiene todo pendiente`() {
        val readiness = Readiness.parse(intArrayOf(0x00, 0x77, 0xE5, 0xE5))!!
        assertFalse(readiness.diesel)
        assertTrue(readiness.available.any { it.name == "Sonda lambda" })
        assertEquals(readiness.available.size, readiness.pending.size)
        assertNull(Readiness.parse(intArrayOf(0x00, 0x07)))
    }

    @Test
    fun `una prueba interna con su escala y sus limites`() {
        val tests = Mode06.parse(intArrayOf(0x31, 0x80, 0x2F, 0x04, 0xB0, 0x01, 0xF4, 0x0F, 0xA0))
        val test = tests.single()
        assertEquals(0x31, test.mid)
        assertEquals(12.0, test.value, 0.001)
        assertEquals(5.0, test.min, 0.001)
        assertEquals(40.0, test.max, 0.001)
        assertEquals("%", test.unit)
        assertTrue(test.passed)
        assertEquals("EGR", Mode06.midName(test.mid))
    }

    @Test
    fun `las unidades con signo y los registros a medias`() {
        val signed = Mode06.parse(intArrayOf(0xB2, 0x83, 0x96, 0xFF, 0x9C, 0xFE, 0x0C, 0x01, 0xF4, 0xB2, 0x84)).single()
        assertEquals(-10.0, signed.value, 0.001)
        assertEquals(-50.0, signed.min, 0.001)
        assertEquals(50.0, signed.max, 0.001)
        assertTrue(signed.passed)
        val unknown = Mode06.parse(intArrayOf(0xE1, 0x90, 0x7F, 0x00, 0x10, 0x00, 0x20, 0x00, 0x30)).single()
        assertFalse(unknown.unitKnown)
        assertFalse(unknown.passed)
    }

    @Test
    fun `el simulador da los monitores y una prueba fuera de limites`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) {}
        session.connect()
        assertEquals(listOf("Filtro de partículas"), session.diagnostics().readiness!!.pending.map { it.name })

        val tests = session.monitorTests()
        assertEquals(listOf(0x31, 0x85, 0xB2, 0xB2), tests.map { it.mid })
        assertEquals(listOf(0xB2), tests.filter { !it.passed }.map { it.mid })

        session.clearDtcs()
        val after = session.diagnostics().readiness!!
        assertEquals(after.available.size, after.pending.size)
    }
}
