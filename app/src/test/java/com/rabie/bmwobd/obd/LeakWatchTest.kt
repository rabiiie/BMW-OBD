package com.rabie.bmwobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakWatchTest {

    // A 2.000 rpm con 20 mg por inyeccion entran 1,333 g/s de gasoleo; con lambda 1,5 hacen falta 29 g/s de aire.
    private fun sample(t: Long, kpa: Double, maf: Double, lambda: Double = 1.5, egr: Double = 0.0, coolant: Double = 90.0, mg: Double = 20.0) =
        LeakSample(t, 2000.0, kpa, maf, lambda, mg, egr, coolant)

    @Test
    fun `sin fuga la lambda del caudalimetro coincide con la de la sonda`() {
        assertEquals(1.0, LeakWatch.ratioOf(sample(0, 120.0, 29.0))!!, 0.01)
    }

    @Test
    fun `motor frio, EGR abierta o poca carga no valen`() {
        assertNull(LeakWatch.ratioOf(sample(0, 120.0, 29.0, coolant = 50.0)))
        assertNull(LeakWatch.ratioOf(sample(0, 120.0, 29.0, egr = 30.0)))
        assertNull(LeakWatch.ratioOf(sample(0, 120.0, 29.0, mg = 5.0)))
    }

    @Test
    fun `si la relacion sube al soplar el turbo apunta a fuga`() {
        val watch = LeakWatch()
        for (i in 0 until 6) watch.sample(sample(i * 500L, 100.0, 29.0))
        for (i in 0 until 6) watch.sample(sample(3_000L + i * 500L, 200.0, 29.0 * 1.2))
        assertTrue(watch.now.verdict!!.startsWith("Posible fuga"))
        assertEquals(12, watch.now.valid)
    }

    @Test
    fun `si se mantiene no hay senal y sin turbo faltan lecturas`() {
        val watch = LeakWatch()
        for (i in 0 until 6) watch.sample(sample(i * 500L, 100.0, 29.0))
        assertTrue(watch.now.verdict!!.startsWith("Faltan lecturas"))
        for (i in 0 until 6) watch.sample(sample(3_000L + i * 500L, 200.0, 29.0 * 1.03))
        assertTrue(watch.now.verdict!!.startsWith("Sin señal"))
    }
}
