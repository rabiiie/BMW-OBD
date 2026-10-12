package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwFaultsTest {

    @Test
    fun `la respuesta leida en el coche trae cuatro averias, dos presentes y dos guardadas`() {
        // Lo que contesto el 118d a 1802FFFF el 10 de octubre, tras el 58.
        val payload = ObdParser.payloads("58" + "04498E614A6E213EFC61480A21", "58").first()
        val faults = BmwFault.parse(payload)

        assertEquals(listOf("498E", "4A6E", "3EFC", "480A"), faults.map { it.label })
        assertEquals(listOf("498E", "3EFC"), faults.filter { it.active }.map { it.label })
        assertEquals("Sensor de nivel de aceite", faults[0].text)
        assertEquals("Sistema del filtro de partículas", faults[3].text)
        assertFalse(faults[1].active)
        // Cada una dice tambien que le pasa, segun la lista de la centralita.
        assertEquals("Calentador del cilindro 1, mando", faults[1].text)
        assertEquals("Circuito abierto (cable o pieza cortados)", faults[1].kind)
        assertEquals("Sin comunicación", faults[0].kind)
        assertEquals("Filtro de partículas muy cargado (contrapresión de escape alta)", faults[3].kind)
        assertEquals(718, BmwFaultTexts.size)
    }

    @Test
    fun `una respuesta corta o vacia no rompe nada`() {
        assertTrue(BmwFault.parse(IntArray(0)).isEmpty())
        assertTrue(BmwFault.parse(intArrayOf(0)).isEmpty())
        // Dice que hay tres y solo trae una entera.
        assertEquals(1, BmwFault.parse(intArrayOf(3, 0x49, 0x8E, 0x61, 0x4A)).size)
        assertNull(BmwFault(0x1234, 0x21).text)
    }

    @Test
    fun `las averias de BMW solo se piden a un BMW y salen junto a las del OBD`() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        log.clear()

        assertNull(session.diagnostics().bmwFaults)
        assertFalse(log.any { it == ">> 1802FFFF" })

        val report = session.diagnostics(bmw = true)
        assertEquals(listOf("498E", "4A6E"), report.bmwFaults!!.map { it.label })
        assertTrue(report.bmwFaults!!.first().active)
        // Las del OBD estandar siguen saliendo como siempre.
        assertEquals(2, report.stored!!.size)
    }
}
