package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una centralita que a veces tarda mas de lo que espera el adaptador con la espera adaptativa,
 * como la del Ford del 15 de octubre: con ella pierde una de cada tres respuestas del OBD
 * estandar. Con la espera fija contesta a todo.
 */
private class SlowEcu(private val loseEvery: Int = 3) : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    private var pending = ""
    private var count = 0
    var fixedWait = false
        private set
    val sent = mutableListOf<String>()

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        pending = text.trim()
        sent += pending
        when (pending) {
            "ATST64" -> fixedWait = true
            "ATAT1", "ATZ" -> fixedWait = false
        }
        car.write(text)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val answer = car.readUntilPrompt(timeoutMs)
        val standard = !pending.startsWith("AT") && !pending.startsWith("2C10") && !pending.startsWith("0100")
        return if (standard && !fixedWait && ++count % loseEvery == 0) "NO DATA\r\r" else answer
    }
}

class SlowAnswersTest {

    private val log = mutableListOf<String>()

    @Test
    fun `si se pierden muchas respuestas se pasa a la espera fija y dejan de perderse`() = runBlocking<Unit> {
        val car = SlowEcu()
        val session = ObdSession(car) { log += it }
        session.connect()
        repeat(40) { session.read(Pids.RPM, singleFrame = true) }

        assertTrue(car.fixedWait)
        assertEquals(1, log.count { it.contains("espera fija de 400 ms") })
        repeat(20) { assertNotNull(session.read(Pids.RPM, singleFrame = true)) }
        assertFalse(log.any { it.contains("se deja la lectura rápida") })
    }

    @Test
    fun `un coche que contesta a todo sigue con la espera adaptativa`() = runBlocking<Unit> {
        val car = SlowEcu(loseEvery = Int.MAX_VALUE)
        val session = ObdSession(car) { log += it }
        session.connect()
        repeat(60) { session.read(Pids.RPM, singleFrame = true) }

        assertFalse(car.fixedWait)
        assertFalse(log.any { it.contains("espera fija") })
    }

    @Test
    fun `las averias se leen con espera fija y luego se vuelve a la adaptativa`() = runBlocking<Unit> {
        val car = SlowEcu(loseEvery = 1)
        val session = ObdSession(car) { log += it }
        session.connect()
        val report = session.diagnostics()

        assertNotNull(report.stored)
        assertNotNull(report.readiness)
        assertFalse(car.fixedWait)
        val sent = car.sent.dropWhile { it != "ATST64" }
        assertTrue(sent.indexOf("03") in 0 until sent.indexOf("ATAT1"))
    }
}
