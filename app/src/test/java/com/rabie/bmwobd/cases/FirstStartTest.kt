package com.rabie.bmwobd.cases

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstStartTest {

    @Test
    fun `con el motor parado al conectar se leen las medidas del arranque hasta un minuto despues`() {
        // Como el 13 de octubre: once segundos de contacto, arranque, y a ralenti.
        val start = FirstStart()
        assertFalse(start.watching(0, null))
        assertTrue(start.watching(1_000, 0.0))
        assertTrue(start.watching(11_000, 0.0))
        assertTrue(start.watching(11_500, 279.5))
        assertTrue(start.watching(12_000, 1035.0))
        assertTrue(start.watching(71_000, 850.0))
        assertFalse(start.watching(72_500, 850.0))
        // Una parada de despues, la del semaforo, ya no cuenta.
        assertFalse(start.watching(600_000, 0.0))
        assertFalse(start.watching(605_000, 850.0))
    }

    @Test
    fun `si al conectar el motor ya esta en marcha no hay arranque que mirar`() {
        val start = FirstStart()
        assertFalse(start.watching(0, 850.0))
        assertFalse(start.watching(5_000, 0.0))
    }
}
