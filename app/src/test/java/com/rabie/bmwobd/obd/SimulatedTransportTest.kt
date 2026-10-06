package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedTransportTest {

    private var now = 0L

    private fun session() = ObdSession(SimulatedTransport(latencyMs = 0, clock = { now })) {}

    private suspend fun ObdSession.value(id: Int): Double? {
        val def = Pids.byId.getValue(id)
        return read(def.pid)?.let(def::decodeOrNull)
    }

    @Test
    fun `la sesion descubre los pids del simulador`() = runBlocking<Unit> {
        val info = session().connect()
        assertTrue(listOf(Pids.RPM, Pids.OIL, 0x70, 0x7A, 0xA6).all { it in info.supportedPids })
        assertTrue("CAN" in info.summary)
    }

    @Test
    fun `al arrancar esta al ralenti en frio`() = runBlocking<Unit> {
        val session = session()
        session.connect()
        assertEquals(950.0, session.value(Pids.RPM)!!, 1.0)
        assertEquals(0.0, session.value(Pids.SPEED)!!, 0.5)
        assertEquals(18.0, session.value(Pids.COOLANT)!!, 1.0)
    }

    @Test
    fun `en autovia va a 120 en sexta con el turbo soplando`() = runBlocking<Unit> {
        val session = session()
        session.connect()
        now = 90_000
        assertEquals(120.0, session.value(Pids.SPEED)!!, 0.5)
        assertEquals(2460.0, session.value(Pids.RPM)!!, 1.0)
        val map = session.value(Pids.MAP)!!
        val baro = session.value(Pids.BAROMETRIC)!!
        assertTrue(Pids.boostBar(mapOf(Pids.MAP to map, Pids.BAROMETRIC to baro))!! > 0.2)
        // El mismo dato por el PID largo, que llega troceado en varias tramas.
        assertEquals(map, session.value(Pids.part(0x70, 1))!!, 1.0)
    }

    @Test
    fun `todas las medidas que anuncia el simulador se saben leer`() = runBlocking<Unit> {
        val session = session()
        val supported = session.connect().supportedPids
        now = 90_000
        for (def in Pids.all.filter { it.pid in supported }) {
            assertNotNull("${def.name} (%02X)".format(def.pid), session.value(def.id))
        }
        assertEquals(214_530.0, session.value(0xA6)!!, 0.1)
        assertEquals(4200.0, session.value(Pids.part(0x7F, 0))!!, 0.1)
        assertEquals(14.1, session.adapterVoltage()!!, 0.3)
    }

    @Test
    fun `lee las averias y al borrarlas desaparecen`() = runBlocking<Unit> {
        val session = session()
        session.connect()

        val before = session.diagnostics()
        assertEquals(true, before.milOn)
        assertEquals(2, before.dtcCount)
        assertEquals(listOf("P0401", "P0299"), before.stored)
        assertEquals(listOf("P2463"), before.pending)
        assertEquals(emptyList<String>(), before.permanent)
        assertEquals("P0401", before.freezeDtc)
        assertTrue(before.freeze.getValue(Pids.SPEED) > 50)
        assertEquals("WBASIMULADO000000", before.vin)
        assertEquals("SIM-N47D20C-01", before.calibration)

        assertTrue(session.clearDtcs())
        val after = session.diagnostics()
        assertEquals(false, after.milOn)
        assertEquals(emptyList<String>(), after.stored)
        assertNull(after.freezeDtc)
    }

    @Test
    fun `lo que no entiende o no tiene lo dice como el adaptador`() = runBlocking<Unit> {
        val session = session()
        session.connect()
        assertEquals("?", session.raw("HOLA"))
        assertEquals("NO DATA", session.raw("2201"))
        assertNull(session.read(0x99))
    }
}
