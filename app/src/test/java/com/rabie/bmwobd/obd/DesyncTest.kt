package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El caso visto en un BMW real: el PID 4C contesta con un mensaje de tres tramas. Pedido con
 * lectura rapida, el adaptador entrega la primera y las otras dos salen en las dos consultas
 * siguientes, que reciben asi la respuesta de otra pregunta.
 */
class DesyncTest {

    private val log = mutableListOf<String>()

    private fun session() = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }

    private fun sent() = log.filter { it.startsWith(">> ") }.map { it.removePrefix(">> ") }

    @Test
    fun `las respuestas pegadas de un registro real se reconocen`() {
        assertTrue(ObdParser.isMultiFrame("010\n0: 414C00000000"))
        assertTrue(ObdParser.isForeignAnswer("000\n1: 000000000000"))
        assertTrue(ObdParser.isForeignAnswer("410B5E"))
        assertFalse(ObdParser.isMultiFrame("410B5E"))
        assertFalse(ObdParser.isForeignAnswer("NO DATA"))
        assertFalse(ObdParser.isForeignAnswer("?"))
        assertFalse(ObdParser.isForeignAnswer("STOPPED"))
    }

    @Test
    fun `tras la primera lectura un mensaje largo ya no se pide con lectura rapida`() = runBlocking<Unit> {
        val session = session()
        session.connect()
        assertNotNull(session.readFirst(0x4C))
        assertNotNull(session.readFirst(Pids.MAP))
        log.clear()

        assertNotNull(session.read(0x4C, singleFrame = true))
        assertNotNull(session.read(Pids.MAP, singleFrame = true))
        assertNotNull(session.read(Pids.RPM, singleFrame = true))

        assertEquals(listOf("014C", "010B1", "010C1"), sent())
        assertTrue(log.none { it.contains("Desfase") })
    }

    @Test
    fun `si aun asi se pide rapido, lo detecta, vacia lo pendiente y no vuelve a hacerlo`() = runBlocking<Unit> {
        val session = session()
        session.connect()
        log.clear()

        // Sin primera lectura la sesion no sabe todavia que 4C es largo.
        val throttle = session.read(0x4C, singleFrame = true)
        assertEquals(88.0, Pids.byId.getValue(0x4C).decodeOrNull(throttle!!)!!, 1.0)
        assertTrue(log.any { it.contains("4C contesta con un mensaje largo") })

        // Lo que viene detras llega bien y sin desfase, porque la relectura vacio las tramas.
        log.clear()
        assertNotNull(session.read(Pids.MAP, singleFrame = true))
        assertNotNull(session.read(Pids.RPM, singleFrame = true))
        assertNotNull(session.read(0x4C, singleFrame = true))
        assertEquals(listOf("010B1", "010C1", "014C"), sent())
        assertTrue(log.none { it.contains("Desfase") })
    }

    @Test
    fun `una respuesta de otra pregunta se relee en vez de arrastrar el desfase`() = runBlocking<Unit> {
        val transport = SimulatedTransport(latencyMs = 0)
        val session = ObdSession(transport) { log += it }
        session.connect()
        // Se dejan dos tramas pendientes a espaldas de la sesion, pidiendo el mensaje largo con lectura rapida.
        transport.write("014C1")
        transport.readUntilPrompt(0)
        log.clear()

        val rpm = session.read(Pids.RPM, singleFrame = true)
        assertNotNull(rpm)
        assertTrue(log.any { it.contains("Desfase preguntando 0C") })

        log.clear()
        assertNotNull(session.read(Pids.MAP, singleFrame = true))
        assertTrue(log.none { it.contains("Desfase") })
    }
}
