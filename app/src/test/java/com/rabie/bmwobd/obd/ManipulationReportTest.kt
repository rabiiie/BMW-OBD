package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManipulationReportTest {

    private fun word(value: Int) = intArrayOf(value shr 8, value and 0xFF)

    @Test
    fun `el historial se lee con su escala y avisa de km por encima del actual y de probabilidad`() = runBlocking<Unit> {
        // Dos anotaciones: 150.000 km sin sospecha y 390.000 km con 50 %; el resto vacias.
        val answers = mutableMapOf<Int, IntArray>()
        for (slot in 0 until ManipulationReport.SLOTS) {
            answers[ManipulationReport.HIST_KM + slot] = word(0)
            answers[ManipulationReport.HIST_TW + slot] = intArrayOf(0)
        }
        answers[ManipulationReport.HIST_KM + 3] = word(15_000)
        answers[ManipulationReport.HIST_KM + 4] = word(39_000)
        answers[ManipulationReport.HIST_TW + 4] = intArrayOf(128)
        answers[ManipulationReport.TUNING_MAX] = word(0)
        val report = ManipulationReport.read(380_182.0) { answers[it] }!!
        assertEquals(listOf(150_000.0, 390_000.0), report.entries.map { it.km })
        assertEquals(50.2, report.entries[1].probability!!, 0.1)
        assertEquals(listOf(390_000.0), report.aheadOfOdometer.map { it.km })
        val lines = report.lines()
        assertTrue(lines.any { it.warn && it.text.contains("cuentakilómetros puede haberse bajado") })
        assertTrue(lines.any { it.warn && it.text.contains("Probabilidad de manipulación distinta de 0") })
    }

    @Test
    fun `sin respuesta no hay informe y un historial limpio no avisa`() = runBlocking<Unit> {
        assertNull(ManipulationReport.read(100_000.0) { null })
        val clean = ManipulationReport.read(100_000.0) { address ->
            if (address in ManipulationReport.HIST_KM until ManipulationReport.HIST_KM + 2) word(5_000) else intArrayOf(0, 0)
        }!!
        assertEquals(2, clean.entries.size)
        assertFalse(clean.lines().any { it.warn })
    }
}
