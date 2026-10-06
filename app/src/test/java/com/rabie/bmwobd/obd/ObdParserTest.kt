package com.rabie.bmwobd.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {

    @Test
    fun `respuesta sin espacios`() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdParser.dataBytes("410C1AF8", 0x0C))
    }

    @Test
    fun `respuesta con espacios`() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ObdParser.dataBytes("41 0C 1A F8", 0x0C))
    }

    @Test
    fun `ignora la linea de busqueda de protocolo`() {
        assertArrayEquals(intArrayOf(0x7B), ObdParser.dataBytes("SEARCHING...\n41057B", 0x05))
    }

    @Test
    fun `sin datos devuelve null`() {
        assertNull(ObdParser.dataBytes("NO DATA", 0x0C))
        assertNull(ObdParser.dataBytes("UNABLE TO CONNECT", 0x0C))
    }

    @Test
    fun `respuesta de otro pid devuelve null`() {
        assertNull(ObdParser.dataBytes("410D32", 0x0C))
    }

    @Test
    fun `respuesta truncada devuelve null`() {
        assertNull(ObdParser.dataBytes("410C1A F", 0x0C))
        assertNull(ObdParser.dataBytes("410C", 0x0C))
    }

    @Test
    fun `mascara de pids soportados`() {
        val supported = ObdParser.supportedFrom(0x00, intArrayOf(0xBE, 0x1F, 0xA8, 0x13))
        assertTrue(supported.containsAll(listOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07)))
        assertTrue(supported.containsAll(listOf(0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20)))
        assertFalse(0x02 in supported)
        assertEquals(17, supported.size)
    }

    @Test
    fun `mascara en un bloque posterior suma el desplazamiento`() {
        val supported = ObdParser.supportedFrom(0x20, intArrayOf(0x80, 0x00, 0x00, 0x01))
        assertEquals(setOf(0x21, 0x40), supported)
    }
}
