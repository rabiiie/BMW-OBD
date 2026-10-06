package com.rabie.bmwobd.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `un mensaje troceado se junta y se le quita el relleno`() {
        val response = "00B\n0:417803114C0E\n1:D800000000AAAA"
        val data = ObdParser.dataBytes(response, 0x78)!!
        assertArrayEquals(intArrayOf(0x03, 0x11, 0x4C, 0x0E, 0xD8, 0, 0, 0, 0), data)
        assertEquals(402.8, Pids.byId.getValue(Pids.part(0x78, 0)).decodeOrNull(data)!!, 0.001)
        assertEquals(null, Pids.byId.getValue(Pids.part(0x78, 2)).decodeOrNull(data))
    }

    @Test
    fun `dos centralitas contestando dan dos mensajes`() {
        val payloads = ObdParser.payloads("43010401\n4300", "43")
        assertEquals(2, payloads.size)
        assertEquals(listOf("P0401"), Dtcs.parse(payloads))
    }

    @Test
    fun `codigos con letra y cifras hexadecimales`() {
        assertEquals("P0401", Dtcs.code(0x04, 0x01))
        assertEquals("P242F", Dtcs.code(0x24, 0x2F))
        assertEquals("C0035", Dtcs.code(0x40, 0x35))
        assertEquals("U0100", Dtcs.code(0xC1, 0x00))
    }

    @Test
    fun `sin el byte de cuenta de los protocolos antiguos tambien se leen`() {
        val payloads = ObdParser.payloads("430401029900 00", "43")
        assertEquals(listOf("P0401", "P0299"), Dtcs.parse(payloads))
    }

    @Test
    fun `sin averias la lista sale vacia`() {
        assertTrue(Dtcs.parse(ObdParser.payloads("4300", "43")).isEmpty())
        assertTrue(ObdParser.payloads("NO DATA", "43").isEmpty())
    }

    @Test
    fun `descripcion conocida o familia del codigo`() {
        assertEquals("EGR: caudal insuficiente", Dtcs.describe("P0401"))
        assertEquals("Código propio del fabricante", Dtcs.describe("P1234"))
        assertEquals("Comunicación entre centralitas", Dtcs.describe("U0100"))
    }
}
