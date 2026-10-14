package com.rabie.bmwobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlausibleTest {

    @Test
    fun `las marcas de sin dato se quitan y las medidas se quedan`() {
        val lambda = Pids.bmw(0x0A29)
        val inlet = Pids.bmw(0x03E0)
        // Lo que dio el coche el 12 y el 13 de octubre.
        val marks = hashMapOf(lambda to 32.77, 0x2D to -100.0, 0x2C to 0.0, inlet to 3000.03)
        Plausible.clean(marks)
        assertEquals(setOf(0x2C), marks.keys)

        val real = hashMapOf(lambda to 1.38, 0x2D to -1.56, 0x2C to 27.06, inlet to 1530.99)
        Plausible.clean(real)
        assertEquals(4, real.size)
        // Con la EGR mandada abierta, -100 % es un fallo de verdad y se queda.
        val shut = hashMapOf(0x2D to -100.0, 0x2C to 40.0)
        Plausible.clean(shut)
        assertEquals(-100.0, shut[0x2D]!!, 0.0)
    }

    @Test
    fun `un salto que vuelve se apunta con su trama y con quien coincide`() {
        val turbo = Pids.bmw(0x0C86)
        val air = Pids.bmw(0x0709)
        val jumps = Jumps { if (it == turbo) "Turbo: giro calculado" else "Aire por cilindro" }
        // El 12 de octubre, a velocidad constante: 123.666 -> 42.847 -> 125.467 rpm.
        assertNull(jumps.seen(air, 805.0, "80E2"))
        assertNull(jumps.seen(turbo, 123_666.0, "7E9A"))
        assertNull(jumps.seen(turbo, 42_847.0, "2BE4"))
        assertNull(jumps.seen(air, 450.0, "2BE4"))
        val line = jumps.seen(turbo, 125_467.0, "8080")!!
        assertTrue(line, line.startsWith("## Salto suelto en Turbo: giro calculado: 123666.00 -> 42847.00 -> 125467.00"))
        assertTrue(line, line.endsWith("Coincide con la última de: Aire por cilindro."))
        assertEquals(1, jumps.found.size)
        // Un cambio que se queda no es un salto.
        assertNull(jumps.seen(air, 900.0, "9000"))
        assertNull(jumps.seen(air, 905.0, "9010"))
        assertEquals(1, jumps.found.size)
    }
}
