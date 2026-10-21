package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseCheckTest {

    private val dieselDone = Readiness.parse(intArrayOf(0x00, 0x0F, 0xC9, 0x00))!!
    private val dieselCleared = Readiness.parse(intArrayOf(0x00, 0x7F, 0xC9, 0xC9))!!

    private fun texts(report: DiagnosticsReport) = PurchaseCheck.lines(report)

    @Test
    fun `un borrado reciente sale en los contadores y en las autocomprobaciones`() {
        val report = DiagnosticsReport(
            milOn = false,
            stored = emptyList(),
            pending = emptyList(),
            readiness = dieselCleared,
            counters = mapOf(
                PurchaseCheck.KM_SINCE_CLEAR to 23.0,
                PurchaseCheck.WARMUPS_SINCE_CLEAR to 2.0,
                PurchaseCheck.MINUTES_SINCE_CLEAR to 50.0,
            ),
        )
        val first = texts(report).first()
        assertTrue(first.warn)
        assertTrue(first.text.startsWith("Averías borradas hace 23 km, 2 arranques en caliente, 1 h de motor."))
        assertTrue(first.text.contains("encaja con un borrado reciente"))
    }

    @Test
    fun `sin borrar en mucho tiempo el contador esta en su tope`() {
        val report = DiagnosticsReport(readiness = dieselDone, counters = mapOf(PurchaseCheck.KM_SINCE_CLEAR to 65535.0))
        val first = texts(report).first()
        assertFalse(first.warn)
        assertTrue(first.text.contains("65.535 km"))
    }

    @Test
    fun `bastidor, cuentakilometros, testigo y filtro`() {
        val report = DiagnosticsReport(
            vin = "WVWZZZ1KZBW000000",
            readiness = dieselDone,
            counters = mapOf(PurchaseCheck.KM_WITH_MIL to 120.0, Pids.ODOMETER to 301_250.0),
            permanent = listOf("P2002"),
        )
        val lines = texts(report)
        assertTrue(lines.any { it.warn && it.text == "Se han hecho 120 km con el testigo de avería encendido." })
        assertTrue(lines.any { it.warn && it.text.startsWith("1 avería(s) permanente(s)") })
        assertTrue(lines.any { it.text.startsWith("Bastidor que da la centralita: WVWZZZ1KZBW000000.") })
        assertTrue(lines.any { it.text.startsWith("Cuentakilómetros según la centralita: 301.250 km.") })
        assertTrue(lines.any { it.text.startsWith("No da la presión del filtro de partículas") })
    }

    @Test
    fun `un gasolina antiguo no da cuentakilometros ni linea del filtro`() {
        val petrol = Readiness.parse(intArrayOf(0x00, 0x07, 0x65, 0x00))!!
        val lines = texts(DiagnosticsReport(readiness = petrol))
        assertTrue(lines.any { it.text.startsWith("Este coche no da el cuentakilómetros por OBD") })
        assertFalse(lines.any { it.text.contains("filtro de partículas") })
    }

    @Test
    fun `el simulador pone los contadores a cero al borrar`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) {}
        session.connect()
        val before = session.diagnostics()
        assertEquals(1240.0, before.counters[PurchaseCheck.KM_SINCE_CLEAR]!!, 0.0)
        assertEquals(214_530.0, before.counters[Pids.ODOMETER]!!, 0.0)
        assertTrue(before.filterPressure)

        session.clearDtcs()
        val after = session.diagnostics()
        assertEquals(0.0, after.counters[PurchaseCheck.KM_SINCE_CLEAR]!!, 0.0)
        assertEquals(214_530.0, after.counters[Pids.ODOMETER]!!, 0.0)
        val first = PurchaseCheck.lines(after).first()
        assertTrue(first.warn)
        assertTrue(first.text.startsWith("Averías borradas hace 0 km, 0 arranques en caliente, 0 h de motor."))
    }
}
