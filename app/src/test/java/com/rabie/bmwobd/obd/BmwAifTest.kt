package com.rabie.bmwobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwAifTest {

    private val vin = "WBAXX00000AB12345"
    private fun ascii(text: String) = text.map { it.code }.toIntArray()

    @Test
    fun `el bastidor entero y sus 7 ultimos se tapan y se dice que estaba`() {
        val data = intArrayOf(0x01, 0x02) + ascii(vin) + intArrayOf(0x10) + ascii("AB12345") + intArrayOf(0x20)
        val read = BmwAif.masked(data, vin)!!
        assertEquals(true, read.vinFound)
        // Ni el bastidor ni su final quedan en el texto.
        val shown = read.hex.split(" ").map { it.toInt(16) }
        assertFalse(shown.windowed(7).any { window -> window == ascii("AB12345").toList() })
        assertTrue(read.hex.startsWith("01 02 2A 2A"))
        assertTrue(read.hex.endsWith("10 2A 2A 2A 2A 2A 2A 2A 20"))
    }

    @Test
    fun `sin el bastidor del coche no se enseña la respuesta y sin respuesta no hay nada`() {
        val read = BmwAif.masked(ascii(vin), null)!!
        assertNull(read.vinFound)
        assertFalse(read.hex.contains("57 42 41"))
        assertNull(BmwAif.masked(null, vin))
        assertEquals(false, BmwAif.masked(intArrayOf(0x00, 0x01), vin)!!.vinFound)
    }
}
