package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un adaptador con una centralita de BMW detras, tal como se espera que conteste: tramas con
 * cabecera 612, primer byte F1 y las respuestas largas troceadas a la espera del control de flujo.
 */
private class FakeDde(
    private val rejected: Set<String> = emptySet(),
    private val directReads: Boolean = true,
    private val countSuffix: Boolean = true,
) : ObdTransport {

    val sent = mutableListOf<String>()
    private var pending = ""
    private var rawMode = false
    private var consecutive = emptyList<String>()

    override suspend fun open() = Unit
    override fun close() = Unit

    override suspend fun write(text: String) {
        pending = text.trim()
        sent += pending
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String = respond(pending) + "\r\r"

    private fun respond(command: String): String = when {
        command in rejected -> "?"
        command == "ATZ" -> "ELM327 v1.5".also { rawMode = false }
        command == "ATSPB" || command == "ATCAF0" -> "OK".also { rawMode = true }
        command.startsWith("AT") -> "OK"
        command == "0100" -> "4100983B801B"
        !rawMode -> "NO DATA"
        command.length == 17 && !countSuffix -> "?"
        else -> ecu(command.take(16).chunked(2).map { it.toInt(16) })
    }

    private fun ecu(frame: List<Int>): String {
        if (frame[0] != 0x12) return "NO DATA"
        val data = frame.drop(2)
        return when {
            frame[1] == 0x30 -> consecutive.joinToString("\r").also { consecutive = emptyList() }
            frame[1] == 0x10 -> "612F1300000"
            frame[1] == 0x21 -> "612F1026CF0"
            data.take(2) == listOf(0x1A, 0x80) -> {
                consecutive = listOf("612F1213132333435", "612F122444445373021")
                "612F110105A80373831"
            }
            data.take(2) == listOf(0x2C, 0x10) && !directReads -> "612F1037F2C12"
            data.take(4) == listOf(0x2C, 0x10, 0x05, 0x47) && frame[1] == 4 -> "612F1046C1032C8FFFF"
            data.take(2) == listOf(0x2C, 0x10) && frame[1] == 6 -> "612F1066C1032C87D00"
            data.take(4) == listOf(0x2C, 0x10, 0x04, 0x4F) -> "612F1036C10C8"
            data.take(2) == listOf(0x2C, 0x10) -> "612F1046C100100"
            data.take(3) == listOf(0x2C, 0xF0, 0x04) -> "612F1026CF0"
            data.take(2) == listOf(0x21, 0xF0) -> "612F10461F032C8"
            else -> "NO DATA"
        }
    }
}

class BmwProbeTest {

    private val log = mutableListOf<String>()

    private fun notes() = log.filter { it.startsWith("##") }

    private fun probe(transport: ObdTransport) = runBlocking {
        val session = ObdSession(transport) { log += it }
        session.probe()
    }

    @Test
    fun `las tramas con cabecera se separan en identificador y bytes`() {
        val frames = BmwProbe.frames("612F1046C1032C8\nNO DATA\n410C0000\n612F110105A80373831")
        assertEquals(2, frames.size)
        assertEquals(0x612, frames[0].id)
        assertEquals(listOf(0xF1, 0x04, 0x6C, 0x10, 0x32, 0xC8), frames[0].bytes.toList())
    }

    @Test
    fun `con la centralita contestando lee la identificacion larga y las medidas`() {
        val dde = FakeDde()
        probe(dde)
        assertTrue(notes().any { it.contains("Identificación: 5A80373831313233343544444537") })
        assertTrue(notes().any { it == "## 0547 Refrigerante: 32C8 -> 30.00 °C" })
        assertTrue(notes().any { it == "## 044F Nivel de aceite: C8 -> 58.59 mm" })
        assertTrue(notes().any { it.contains("Refrigerante y batería juntos: 6C1032C87D00") })
        assertFalse(notes().any { it.contains("CAN normal") })
        assertEquals("## Sondeo: fin", log.last())
        // Al acabar el adaptador vuelve a quedar como lo espera la lectura normal.
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0", "0100"), dde.sent.takeLast(8))
    }

    @Test
    fun `si la forma directa se rechaza prueba a definir la lista y leerla`() {
        probe(FakeDde(directReads = false))
        assertTrue(notes().any { it.contains("0547 Refrigerante: rechazada: 7F2C12") })
        assertTrue(notes().any { it == "## 0547 Refrigerante: 32C8 -> 30.00 °C" })
    }

    @Test
    fun `sin el protocolo de usuario pasa a las tramas crudas sobre el protocolo normal`() {
        probe(FakeDde(rejected = setOf("ATPBC001", "ATSPB")))
        assertTrue(notes().any { it == "## El adaptador no admite este modo" })
        assertTrue(notes().any { it == "## 0547 Refrigerante: 32C8 -> 30.00 °C" })
    }

    @Test
    fun `si el adaptador no admite la cifra de respuestas la deja de usar`() {
        probe(FakeDde(countSuffix = false))
        assertTrue(notes().any { it.contains("no admite la cifra") })
        assertTrue(notes().any { it == "## 0547 Refrigerante: 32C8 -> 30.00 °C" })
    }

    @Test
    fun `con un coche que no contesta acaba y deja el adaptador como estaba`() {
        probe(SimulatedTransport(latencyMs = 0))
        assertTrue(notes().any { it.contains("no ha contestado en ningún modo") })
        assertEquals("## Sondeo: fin", log.last())
    }
}
