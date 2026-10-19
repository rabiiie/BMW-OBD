package com.rabie.bmwobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwServiceTest {

    private fun query(lid: String) = BmwService.QUERIES.first { it.query == lid }

    @Test
    fun `todas las lecturas son de solo lectura`() {
        // Servicio 22 lee; el 30 solo con 01, "dame el estado actual".
        assertTrue(BmwService.QUERIES.all { it.query.startsWith("22") || (it.query.startsWith("30") && it.query.endsWith("01") && it.query.length == 6) })
    }

    @Test
    fun `una correccion sin corregir sale 1`() {
        val read = BmwService.parse(query("30A401"), "70 A4 01 20 00")
        assertEquals(1.0, read.value!!, 0.001)
        assertEquals("70A401".length, read.query.answer.length)
        assertEquals("20 00", read.raw)
    }

    @Test
    fun `el ralenti lleva signo`() {
        assertEquals(-10.0, BmwService.parse(query("30A301"), "70 A3 01 FF EC").value!!, 0.001)
    }

    @Test
    fun `un rechazo da su motivo y no hay valor`() {
        val read = BmwService.parse(query("305B01"), "7F 30 22")
        assertEquals(0x22, read.refusal)
        assertNull(read.value)
        assertEquals("Rechazado: no se dan las condiciones", read.status)
    }

    @Test
    fun `los bits del aprendizaje de la EGR`() {
        val read = BmwService.parse(query("305B01"), "70 5B 01 02 12")
        assertEquals(listOf("válvula que se agarrota", "aprendizaje terminado", "cable cortado"), read.activeBits)
    }

    @Test
    fun `la orden del ventilador al 30 por ciento`() {
        assertEquals("30C707099A", BmwService.fanCommand(30.0))
        assertEquals("30C701", BmwService.FAN_READ)
        assertEquals("30C700", BmwService.FAN_RELEASE)
        assertEquals(30.0, BmwService.fanPercent(listOf(0x09, 0x9A))!!, 0.01)
    }
}
