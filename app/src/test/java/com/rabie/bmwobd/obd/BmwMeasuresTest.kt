package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un adaptador que no contesta a las consultas de BMW hasta que se le pone la espera fija. */
private class SlowDde : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    private var pending = ""
    private var fixedWait = false

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        pending = text.trim()
        when (pending) {
            "ATST64" -> fixedWait = true
            "ATAT1", "ATZ" -> fixedWait = false
        }
        car.write(text)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val answer = car.readUntilPrompt(timeoutMs)
        return if (pending.startsWith("2C10") && !fixedWait) "NO DATA\r\r" else answer
    }
}

class BmwMeasuresTest {

    private val log = mutableListOf<String>()

    private fun candidates() = Pids.bmwDiesel

    @Test
    fun `la respuesta vista en el coche da la temperatura que marcaba el OBD`() {
        // Sondeo del 9 de octubre: 2C100547 contesto 6C1039FE con el refrigerante en 48 °C.
        val data = ObdParser.payloads("6C1039FE", "6C10").first()
        val coolant = data[0] * 256 + data[1]
        assertEquals(48.46, coolant * 0.01 - 100.0, 0.001)
    }

    @Test
    fun `al conectar se quedan las medidas que contestan y una sola de cada identificador`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        val found = session.discoverBmw(candidates())

        assertEquals(found.size, found.map { it.id }.distinct().size)
        assertEquals(Pids.bmw(0x0458), found.first { it.id == Pids.OIL }.pid)
        assertTrue(found.any { it.id == Pids.BMW_INJECTION })
        assertTrue(log.any { it.startsWith("## BMW: contestan ${found.size} de") })
        assertFalse(log.any { it.contains("espera fija") })
    }

    @Test
    fun `las medidas se leen sin lectura rapida y con su formula`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        val soot = candidates().first { it.id == Pids.bmw(0x03EA) }
        log.clear()

        val value = soot.decodeOrNull(session.read(soot.pid, singleFrame = true)!!)!!

        assertEquals(14.2, value, 0.05)
        assertEquals(listOf(">> 2C1003EA"), log.filter { it.startsWith(">>") })
    }

    @Test
    fun `una direccion que la centralita no conoce se descarta`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        val unknown = PidDef(Pids.bmw(0x7777), "Inventada", "", 2, 0) { 1.0 }
        assertTrue(session.discoverBmw(listOf(unknown)).isEmpty())
        assertNull(session.read(unknown.pid))
    }

    @Test
    fun `si calla con la espera adaptativa se pone la espera fija y se queda puesta`() = runBlocking<Unit> {
        val session = ObdSession(SlowDde()) { log += it }
        session.connect()
        val found = session.discoverBmw(candidates())

        assertTrue(found.isNotEmpty())
        assertTrue(log.any { it.contains("espera fija") })
        assertNotNull(session.read(Pids.BMW_INJECTION))
    }

    @Test
    fun `el consumo sale de la cantidad inyectada cuando la centralita la da`() {
        // 20 mg por embolada a 2000 rpm: 4000 inyecciones por minuto, 4,8 kg/h, 5,75 L/h.
        val values = mapOf(Pids.BMW_INJECTION to 20.0, Pids.RPM to 2000.0, Pids.SPEED to 100.0, Pids.MAF to 29.0, Pids.LAMBDA to 1.25)
        assertEquals(5.75, Pids.fuelRate(values)!!, 0.01)
        assertEquals(5.75, Pids.litersPer100Km(values)!!, 0.01)
        // Un valor imposible no se usa: se vuelve a la estimacion con la lambda.
        assertEquals(6.9, Pids.fuelRate(values + (Pids.BMW_INJECTION to 400.0))!!, 0.05)
        assertFalse(Pids.fuelRateIsEstimated(setOf(Pids.BMW_INJECTION, Pids.MAF, Pids.LAMBDA)))
        assertTrue(Pids.fuelRateIsEstimated(setOf(Pids.MAF, Pids.LAMBDA)))
    }

    @Test
    fun `las medidas de BMW no pisan a las estandar del mismo identificador`() {
        assertEquals(Pids.OIL, Pids.byId.getValue(Pids.OIL).pid)
        assertEquals(Pids.ODOMETER, Pids.byId.getValue(Pids.ODOMETER).pid)
        assertTrue(Pids.hasBmwMeasures("BMW", diesel = true))
        assertFalse(Pids.hasBmwMeasures("BMW", diesel = false))
        assertFalse(Pids.hasBmwMeasures("Volkswagen", diesel = true))
        assertFalse(Pids.hasBmwMeasures(null, diesel = true))
    }
}
