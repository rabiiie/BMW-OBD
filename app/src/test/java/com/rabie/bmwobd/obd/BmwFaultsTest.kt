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
    fun `con el sensor de nivel de aceite averiado no se leen ni el nivel ni la temperatura de aceite`() {
        val level = Pids.bmw(Pids.BMW_OIL_LEVEL_ADDRESS)
        // Lo que tenia el coche el 11 de octubre: las dos del sensor de aceite presentes.
        val present = BmwFault.parse(ObdParser.payloads("58" + "04498E614A6E613EFC61480A21", "58").first())
        assertEquals(setOf(level, Pids.OIL), BmwFault.unreadable(present))
        // Solo guardadas, o con otras averias, el nivel se sigue leyendo.
        val stored = BmwFault.parse(ObdParser.payloads("58" + "02498E213EFC21", "58").first())
        assertTrue(BmwFault.unreadable(stored).isEmpty())
        assertTrue(BmwFault.unreadable(listOf(BmwFault(0x4A6E, 0x61))).isEmpty())
        assertTrue(BmwFault.unreadable(null).isEmpty())
        assertTrue(BmwFault.unreadable(emptyList()).isEmpty())
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

    @Test
    fun `los avisos de la memoria de informacion tambien piden su detalle`() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        log.clear()

        val report = session.diagnostics(bmw = true)
        assertEquals(listOf("4CE0", "44CF"), report.bmwInfo!!.map { it.label })
        assertEquals("Se ha activado la regeneración de protección del motor (filtro muy cargado)", report.bmwInfo!![0].kind)
        assertEquals("Salto de contrapresión de escape no creíble", report.bmwInfo!![1].kind)
        // Un detalle por codigo: las dos averias y los dos avisos.
        for (code in listOf("498E", "4A6E", "4CE0", "44CF")) assertEquals(1, log.count { it == ">> 17$code" })
    }

    @Test
    fun `el detalle de cada averia da el kilometraje en que se apunto`() {
        // Respuestas del coche el 14 de octubre, tal como las dio el adaptador.
        fun detail(text: String, code: Int) = BmwFault.detailKm(ObdParser.payloads(text, BmwFault.DETAIL_ANSWER).last(), code)
        val oil = "04A\n0: 5701498E6100\n1: 0000FF01AE0500\n2: 005F0000003C7D\n3: 91040000004719\n4: 000000285C2100\n" +
            "5: 00801000015300\n6: FFB91206CF5A2B\n7: 1C293C89901000\n8: 00524AE664000C\n9: 28461C00000010\nA: 00014E00CAAAAA"
        assertEquals(356_392, detail(oil, 0x498E))
        val back = "058\n0: 57014D002100\n1: 00000428B92311\n2: A784A585B68F8A\n3: 9B103800100001\n4: DDDDDB07390F83\n" +
            "5: 0304FF000480FF\n6: 007D487F057362\n7: 7AB93818CE81C6\n8: 88A2868B98103B\n9: 00210002E7E7E5\nA: 07510F83071FF2\n" +
            "B: 00071598005F48\nC: EE0575AF51AAAA"
        assertEquals(379_160, detail(back, 0x4D00))
        // La respuesta de otra averia no vale para esta.
        assertNull(detail(back, 0x498E))
    }

    @Test
    fun `la memoria de informacion se lee como la de averias`() {
        val info = BmwFault.parse(ObdParser.payloads("622000014CE021", BmwFault.INFO_ANSWER).last())
        assertEquals(listOf(BmwFault(0x4CE0, 0x21)), info)
        assertEquals("Sistema del filtro de partículas", info.single().text)
    }

    @Test
    fun `como estan 480A y 481A`() {
        assertEquals(null, BmwFault.filterLevel(null))
        assertEquals(0.0, BmwFault.filterLevel(listOf(BmwFault(0x480A, 0x21), BmwFault(0x498E, 0x61)))!!, 0.0)
        assertEquals(1.0, BmwFault.filterLevel(listOf(BmwFault(0x480A, 0x61), BmwFault(0x481A, 0x21)))!!, 0.0)
        assertEquals(2.0, BmwFault.filterLevel(listOf(BmwFault(0x480A, 0x61), BmwFault(0x481A, 0x61)))!!, 0.0)
    }
    @Test
    fun `las averias de calentadores dicen el cilindro y si estan presentes o solo guardadas`() {
        // 4A6E (cilindro 1, cortado) presente y 4A3E (cilindro 4) solo guardada; 480A no es de calentadores.
        val present = BmwFault.parse(intArrayOf(3, 0x4A, 0x6E, 0x61, 0x4A, 0x3E, 0x21, 0x48, 0x0A, 0x61))
        assertEquals(1.0, BmwFault.glowCylinders(present)!!, 0.0)
        assertEquals(listOf("4A6E", "4A3E"), BmwFault.glow(present).map { it.label })
        assertTrue(BmwFault.glowText(present).startsWith("4A6E presente (Calentador del cilindro 1"))
        assertTrue(BmwFault.glowText(present).contains("4A3E guardada, no presente"))

        val stored = BmwFault.parse(intArrayOf(1, 0x4A, 0x6E, 0x21))
        assertEquals(0.0, BmwFault.glowCylinders(stored)!!, 0.0)
        assertEquals(0.0, BmwFault.appValues(stored).getValue(Pids.BMW_GLOW_FAULT_COUNT), 0.0)
        assertNull(BmwFault.glowCylinders(null))
        assertEquals("ninguna avería de calentadores, ni presente ni guardada", BmwFault.glowText(emptyList()))
    }
    @Test
    fun `se puede borrar una sola averia de BMW y las demas se quedan`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) {}
        session.connect()
        assertEquals("14480A", BmwFault.clearQuery(BmwFault.FILTER_HIGH))
        assertEquals(true, session.clearBmwFault(0x4A6E))
        assertEquals(listOf("498E"), session.diagnostics(bmw = true).bmwFaults!!.map { it.label })
    }
}
