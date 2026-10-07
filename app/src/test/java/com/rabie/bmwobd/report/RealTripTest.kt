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

    private fun trip(name: String = "trayecto_real_corto.csv"): TripData {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(name))
        return stream.bufferedReader().useLines { TripCsv.parse(it) }
    }

    @Test
    fun `ir con poco pedal y la EGR abierta no cuenta como acelerar a fondo`() {
        // En este trayecto hay un tramo a 1600-1700 rpm con el 30 % de pedal, la EGR abierta y la
        // carga del OBD por encima del 80 %, y despues una aceleracion de verdad hasta 227 kPa.
        val data = trip("trayecto_real_acelerones.csv")
        val pulls = TripReport.pulls(data, vehicle)
        println("Aceleraciones: " + pulls.map { "${it.startMs / 1000}s ${it.boostPeakBar}" })
        assertTrue(pulls.isNotEmpty())
        assertTrue(pulls.all { it.boostPeakBar!! > 0.9 })
        assertEquals(1.33, pulls.maxOf { it.boostPeakBar!! }, 0.02)
        val findings = Advisor.review(data, Vehicle.GENERIC.copy(name = "118d N47", make = "BMW", displacementLiters = 1.995))
        println("Indicios: " + findings.map { "${it.advice.severity} ${it.advice.id} ${it.seconds}s" })
        assertTrue(findings.none { it.advice.severity != Severity.INFO })
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
    fun `con la referencia del N47 la carga corregida al ralenti queda dentro y no hay avisos`() {
        val bmw = Vehicle.GENERIC.copy(name = "118d N47", make = "BMW", displacementLiters = 1.995)
        val data = trip()
        val findings = Advisor.review(data, bmw)
        println("Indicios con referencia N47: " + findings.map { "${it.advice.severity} ${it.advice.id} ${it.seconds}s" })
        println(TripReport.text("real con referencia", bmw, TripReport.build(data, bmw, TripCsv.stats(data), findings)))
        assertTrue(findings.none { it.advice.severity != Severity.INFO })
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
