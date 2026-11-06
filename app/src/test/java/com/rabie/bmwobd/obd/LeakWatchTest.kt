package com.rabie.bmwobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakWatchTest {

    // A 2.000 rpm con 20 mg por inyeccion entran 1,333 g/s de gasoleo; con lambda 1,5 hacen falta 29 g/s de aire.
    private fun sample(
        t: Long,
        kpa: Double,
        maf: Double,
        rpm: Double = 2000.0,
        egr: Double = 25.0,
        coolant: Double = 90.0,
        mg: Double = 20.0,
        mode: Int? = Pids.MODE_NORMAL,
    ) = LeakSample(t, rpm, kpa, maf, 1.5, mg, egr, coolant, mode)

    /** Varias vueltas estables seguidas a la misma presion. */
    private fun LeakWatch.steady(from: Long, kpa: Double, maf: Double, count: Int = 8) {
        for (i in 0 until count) sample(sample(from + i * 600L, kpa, maf))
    }

    @Test
    fun `sin fuga la lambda del caudalimetro coincide con la de la sonda, con la EGR abierta`() {
        assertEquals(1.0, LeakWatch.ratioOf(sample(0, 120.0, 29.0, egr = 25.0))!!, 0.01)
    }

    @Test
    fun `motor frio, poca carga o regenerando no valen`() {
        assertNull(LeakWatch.ratioOf(sample(0, 120.0, 29.0, coolant = 50.0)))
        assertNull(LeakWatch.ratioOf(sample(0, 120.0, 29.0, mg = 5.0)))
        assertNull(LeakWatch.ratioOf(sample(0, 120.0, 29.0, mode = Pids.MODE_REGENERATING)))
    }

    @Test
    fun `un cambio brusco no cuenta hasta que se estabiliza`() {
        val watch = LeakWatch()
        assertNull(watch.sample(sample(0, 120.0, 29.0)))
        assertNull(watch.sample(sample(600, 120.0, 29.0)))
        assertEquals(1.0, watch.sample(sample(1200, 120.0, 29.0))!!, 0.01)
        assertNull(watch.sample(sample(1800, 120.0, 29.0, rpm = 2400.0)))
    }

    @Test
    fun `si la relacion sube al soplar el turbo apunta a fuga`() {
        val watch = LeakWatch()
        watch.steady(0, 120.0, 29.0)
        watch.steady(10_000, 190.0, 29.0 * 1.15)
        assertTrue(watch.now.verdict!!, watch.now.verdict!!.startsWith("Posible fuga"))
    }

    @Test
    fun `si baja como en octubre no hay senal y sin presion faltan lecturas`() {
        val watch = LeakWatch()
        watch.steady(0, 120.0, 29.0)
        assertTrue(watch.now.verdict!!.startsWith("Faltan lecturas estables por encima"))
        watch.steady(10_000, 160.0, 29.0 * 0.90)
        assertTrue(watch.now.verdict!!, watch.now.verdict!!.startsWith("Sin señal"))
    }

    @Test
    fun `sin lambda lo dice en vez de pedir mas conduccion`() {
        val watch = LeakWatch()
        for (i in 0 until 25) watch.sample(LeakSample(i * 600L, 2000.0, 150.0, 29.0, null, 20.0, 0.0, 90.0))
        assertTrue(watch.now.verdict!!.startsWith("El coche no está dando la lambda"))
    }
}
