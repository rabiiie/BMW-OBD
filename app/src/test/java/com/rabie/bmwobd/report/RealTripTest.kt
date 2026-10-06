package com.rabie.bmwobd.report

import com.rabie.bmwobd.advice.Advisor
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.trips.TripCsv
import com.rabie.bmwobd.trips.TripData
import com.rabie.bmwobd.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un trayecto corto grabado en un coche de verdad (diesel de 2 litros, seis minutos, salida a
 * 65 °C). Sirve para que las reglas y el informe se prueben con lecturas reales y no solo con las
 * del simulador.
 */
class RealTripTest {

    private val vehicle = Vehicle.GENERIC.copy(name = "Real", displacementLiters = 1.995)

    private fun trip(): TripData {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("trayecto_real_corto.csv"))
        return stream.bufferedReader().useLines { TripCsv.parse(it) }
    }

    @Test
    fun `se lee entero y las cifras basicas cuadran`() {
        val data = trip()
        val stats = TripCsv.stats(data)
        assertEquals(507, data.size)
        assertEquals(3.2, stats.distanceKm!!, 0.1)
        assertEquals(85.0, stats.maxSpeed!!, 0.1)
        assertEquals(0.85, stats.maxBoostBar!!, 0.02)
        assertEquals(91.0, stats.maxCoolant!!, 0.1)
    }

    @Test
    fun `con la EGR cerrada el error de menos cien no se toma por averia`() {
        val findings = Advisor.review(trip(), vehicle)
        println("Indicios del trayecto real: " + findings.map { "${it.advice.severity} ${it.advice.id} ${it.seconds}s" })
        assertFalse(findings.any { it.advice.id == "egr" && it.seconds > 30 })
        assertTrue(findings.none { it.advice.severity == Severity.ALERT })
    }

    @Test
    fun `el informe sale con calentamiento y ralenti`() {
        val data = trip()
        val sections = TripReport.build(data, vehicle, TripCsv.stats(data), Advisor.review(data, vehicle))
        println(TripReport.text("real", vehicle, sections))
        assertTrue(sections.map { it.title }.containsAll(listOf("Resumen", "Calentamiento", "Ralentí en caliente")))
        assertEquals(153_210L, TripReport.warmUpMs(data))
    }
}
