package com.rabie.bmwobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PidsTest {

    private fun decode(id: Int, vararg bytes: Int): Double? =
        Pids.all.first { it.id == id }.decodeOrNull(bytes)

    @Test
    fun `rpm`() = assertEquals(1726.0, decode(0x0C, 0x1A, 0xF8)!!, 0.001)

    @Test
    fun `refrigerante`() = assertEquals(83.0, decode(0x05, 0x7B)!!, 0.001)

    @Test
    fun `tension del modulo`() = assertEquals(14.412, decode(0x42, 0x38, 0x4C)!!, 0.001)

    @Test
    fun `presion del rail en bar`() = assertEquals(256.0, decode(0x23, 0x0A, 0x00)!!, 0.001)

    @Test
    fun `error de EGR centrado en cero`() = assertEquals(0.0, decode(0x2D, 0x80)!!, 0.001)

    @Test
    fun `datos insuficientes devuelven null`() = assertNull(decode(0x0C, 0x1A))

    @Test
    fun `boost es admision menos barometrica`() {
        val values = mapOf(Pids.MAP to 180.0, Pids.BAROMETRIC to 100.0)
        assertEquals(0.8, Pids.boostBar(values)!!, 0.001)
        assertNull(Pids.boostBar(mapOf(Pids.MAP to 180.0)))
    }

    @Test
    fun `rangos`() {
        val coolant = DefaultRanges.byPid.getValue(0x05)
        assertEquals(Status.OK, coolant.evaluate(90.0))
        assertEquals(Status.NEUTRAL, coolant.evaluate(40.0))
        assertEquals(Status.WARN, coolant.evaluate(105.0))
        assertEquals(Status.ALERT, coolant.evaluate(115.0))
    }
}
