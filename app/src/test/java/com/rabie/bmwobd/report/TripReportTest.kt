package com.rabie.bmwobd.report

import com.rabie.bmwobd.advice.Advisor
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.SimulatedEngine
import com.rabie.bmwobd.trips.TripCsv
import com.rabie.bmwobd.trips.TripData
import com.rabie.bmwobd.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReportTest {

    private val vehicle = Vehicle.GENERIC.copy(name = "Prueba", displacementLiters = 1.995)

    /** Diez minutos del coche simulado, una fila por segundo. */
    private fun simulatedTrip(): TripData {
        val columns = listOf(
            Pids.RPM, Pids.SPEED, Pids.LOAD, Pids.COOLANT, Pids.OIL, Pids.MAP, Pids.BAROMETRIC,
            Moment.INTAKE_TEMP, Moment.MAF, Moment.MODULE_VOLTAGE, Moment.AMBIENT_TEMP, Pids.FUEL_RATE,
            Moment.BOOST_COMMANDED, Moment.BOOST_ACTUAL,
        )
        val lines = sequence {
            yield(TripCsv.header(columns))
            for (s in 0..600) {
                val e = SimulatedEngine.at(s.toDouble())
                val values = columns.zip(
                    listOf(
                        e.rpm, e.speed, e.load, e.coolant, e.oil, e.mapKpa, e.baroKpa, e.intakeTemp, e.maf,
                        e.voltage, e.ambient, e.fuelRate, e.mapKpa + 3, e.mapKpa,
                    ),
                ).toMap()
                yield(TripCsv.row(s * 1000L, columns, values))
            }
        }
        return TripCsv.parse(lines)
    }

    @Test
    fun `el calentamiento dice cuanto tardo en llegar a 80 grados`() {
        val ms = TripReport.warmUpMs(simulatedTrip())!!
        assertTrue("tardó $ms ms", ms in 150_000L..230_000L)
    }

    @Test
    fun `encuentra las aceleraciones a fondo y compara turbo pedido y real`() {
        val pulls = TripReport.pulls(simulatedTrip(), vehicle)
        assertTrue(pulls.size >= 4)
        val longest = pulls.maxBy { it.durationMs }
        assertTrue(longest.durationMs >= 10_000)
        assertEquals(0.03, longest.boostCommandedBar!! - longest.boostActualBar!!, 0.005)
        assertTrue(longest.boostPeakBar!! > 0.9)
        assertTrue("llenado ${longest.airRatio}", longest.airRatio!! in 0.8..1.0)
    }

    @Test
    fun `el informe completo sale con sus secciones y se puede pasar a texto`() {
        val trip = simulatedTrip()
        val sections = TripReport.build(trip, vehicle, TripCsv.stats(trip), Advisor.review(trip, vehicle))
        val titles = sections.map { it.title }
        assertTrue(titles.containsAll(listOf("Resumen", "Calentamiento", "Ralentí en caliente", "Aceleraciones a fondo", "Indicios")))
        val text = TripReport.text("martes", vehicle, sections)
        assertTrue(text.startsWith("INFORME DE TRAYECTO · martes"))
        assertTrue("turbo pedido" in text)
        assertTrue("no un diagnóstico" in text)
    }
}
