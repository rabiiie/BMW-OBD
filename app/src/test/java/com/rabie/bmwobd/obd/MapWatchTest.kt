package com.rabie.bmwobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapWatchTest {

    @Test
    fun `al ralenti una caida por debajo de la presion de la calle se cuenta una vez`() {
        val watch = MapWatch(baro = 95.0)
        watch.rpm(860.0)
        assertFalse(watch.sample(0, 95.0))
        assertTrue(watch.sample(60, 44.0))
        assertFalse(watch.sample(120, 46.0))
        assertFalse(watch.sample(180, 95.0))
        assertTrue(watch.sample(240, 60.0))
        assertEquals(2, watch.now.drops)
        assertEquals(44.0, watch.now.min!!, 0.0)
        assertEquals(95.0, watch.now.max!!, 0.0)
        assertEquals(5, watch.now.samples)
    }

    @Test
    fun `acelerando o sin saber las revoluciones no se cuentan caidas`() {
        val watch = MapWatch(baro = 95.0)
        assertFalse(watch.sample(0, 44.0))
        watch.rpm(2200.0)
        assertFalse(watch.sample(60, 44.0))
        assertEquals(0, watch.now.drops)
    }

    @Test
    fun `cuenta las lecturas por segundo`() {
        val watch = MapWatch(baro = 95.0)
        for (i in 0..20) watch.sample(i * 50L, 95.0)
        assertEquals(20.0, watch.now.perSecond, 0.1)
    }
}
