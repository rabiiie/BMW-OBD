package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordCheckTest {

    private val faults = listOf(BmwFault(0x498E, 0x61), BmwFault(0x4D00, 0x21))
    private val info = listOf(BmwFault(0x4CE0, 0x21), BmwFault(0x44CF, 0x21))

    @Test
    fun `falta un detalle y la comprobacion lo dice`() {
        val report = DiagnosticsReport(bmwFaults = faults, bmwInfo = info, bmwHistory = emptyList(), bmwDetailed = setOf(0x498E, 0x4CE0, 0x44CF))
        assertEquals(listOf(0x4D00), RecordCheck.missingDetails(report))
        val detail = RecordCheck.lines(report, null).first { it.label.startsWith("Detalle") }
        assertFalse(detail.ok)
        assertEquals("3 de 4; falta 4D00", detail.detail)
    }

    @Test
    fun `con todo leido la comprobacion sale completa`() {
        val report = DiagnosticsReport(bmwFaults = faults, bmwInfo = info, bmwHistory = emptyList(), bmwDetailed = setOf(0x498E, 0x4D00, 0x4CE0, 0x44CF))
        val filter = BmwService.parse(BmwService.FILTER_QUERY, "70 50 01 " + "00 ".repeat(64))
        val service = BmwService.QUERIES.map { if (it === BmwService.FILTER_QUERY) filter else ServiceRead(it, null, null) }
        val lines = RecordCheck.lines(report, service)
        assertTrue(lines.all { it.ok })
        assertEquals("${BmwService.QUERIES.size} pedidas, 1 contestadas", lines.first { it.label == "Lecturas de servicio" }.detail)
        assertTrue(RecordCheck.missingDetails(report).isEmpty())
    }

    @Test
    fun `lecturas de servicio a medias no cuentan como completas`() {
        val service = BmwService.QUERIES.take(5).map { ServiceRead(it, null, null) }
        val lines = RecordCheck.lines(null, service)
        assertFalse(lines.first { it.label == "Lecturas de servicio" }.ok)
        assertFalse(lines.first { it.label.startsWith("Datos del filtro") }.ok)
    }

    @Test
    fun `la lectura de averias dice de que codigos llego el detalle`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) {}
        session.connect()
        val report = session.diagnostics(bmw = true)
        assertEquals(setOf(0x498E, 0x4A6E, 0x4CE0, 0x44CF), report.bmwDetailed)
        assertTrue(RecordCheck.missingDetails(report).isEmpty())
    }
}
