package com.rabie.bmwobd.cases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColdStartFaultsTest {

    @Test
    fun `tras arrancar en frio se leen las averias a los 60 y a los 120 segundos`() {
        val cold = ColdStartFaults()
        assertNull(cold.due(0, 0.0, 12.0))
        assertNull(cold.due(10_000, 900.0, 12.0))
        assertEquals(12.0, cold.coolantAtStart!!, 0.0)
        assertNull(cold.due(69_000, 850.0, 20.0))
        assertEquals(60, cold.due(70_000, 850.0, 20.0))
        assertNull(cold.due(71_000, 850.0, 20.0))
        assertEquals(120, cold.due(130_500, 850.0, 30.0))
        assertNull(cold.due(400_000, 850.0, 50.0))
    }

    @Test
    fun `con el motor caliente o ya en marcha al conectar no se lee nada de mas`() {
        val warm = ColdStartFaults()
        warm.due(0, 0.0, 70.0)
        warm.due(5_000, 850.0, 70.0)
        assertNull(warm.due(65_000, 850.0, 70.0))
        assertNull(warm.coolantAtStart)

        val running = ColdStartFaults()
        running.due(0, 850.0, 15.0)
        assertNull(running.due(65_000, 850.0, 15.0))
    }
}
