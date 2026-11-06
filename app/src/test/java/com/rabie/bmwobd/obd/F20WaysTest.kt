package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un F20 detras de un ELM327 que acepta la direccion extendida y no la usa, como el del E87: la
 * pasarela 6F1 -> 12 no llega. La identificacion contesta a todas (7DF) y por la direccion del
 * motor (7E0); las medidas, solo por la del motor. Preguntando a todas, una medida que no tiene
 * se calla, como manda UDS; por la del motor contesta "no existe" (7F 22 31).
 */
private class F20Car(private val measuresBy: String, private val answers: Map<Int, List<Int>>) : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    private var pending = ""
    private var header = "7DF"
    private var extended = false

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        pending = text.trim()
        when {
            pending.startsWith("ATSH") -> header = pending.removePrefix("ATSH")
            pending == "ATCEA" -> extended = false
            pending.startsWith("ATCEA") -> extended = true
            pending == "ATZ" || pending == "ATD" -> {
                header = "7DF"
                extended = false
            }
        }
        car.write(text)
    }

    private val way: String
        get() = when {
            header == "6F1" && extended -> if (measuresBy == GATEWAY) GATEWAY else LOST
            header == "7E0" -> PHYSICAL
            header == "7DF" -> FUNCTIONAL
            else -> LOST
        }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val answer = car.readUntilPrompt(timeoutMs)
        if (pending.startsWith("AT")) return answer
        if (way == LOST) return "NO DATA\r\r"
        if (pending == "19020C") return "59020C2834002F\r\r"
        if (pending.startsWith("22") && pending.length == 6) {
            val did = pending.substring(2).toInt(16)
            if (did == 0xF150) return "62F1500F1F90\r\r"
            val data = answers[did]
            return when {
                data != null && way == measuresBy -> "62%04X".format(did) + data.joinToString("") { "%02X".format(it) } + "\r\r"
                way == FUNCTIONAL -> "NO DATA\r\r"
                else -> "7F2231\r\r"
            }
        }
        return answer
    }

    companion object {
        const val GATEWAY = "gateway"
        const val PHYSICAL = "physical"
        const val FUNCTIONAL = "functional"
        const val LOST = "lost"
    }
}

class F20WaysTest {

    private val log = mutableListOf<String>()

    // Refrigerante 84,5 °C, 1800 rpm, 14,2 V, 189.999 km, hollin usado 12,5 g y km desde regenerar 76,5.
    private val table = mapOf(
        0x461B to listOf(0x48, 0x14),
        0x427F to listOf(0x4C, 0xD3),
        0x4200 to listOf(0x8E, 0x8A),
        0x46B1 to listOf(0x00, 0x02, 0xE6, 0x2F),
        0x44BE to listOf(0x03, 0x33),
        0x44BF to listOf(0x00, 0x01, 0x2A, 0xD4),
    )

    @Test
    fun `la prueba no se queda en la primera forma que identifica y sigue por la que da medidas`() = runBlocking {
        val session = ObdSession(F20Car(F20Car.PHYSICAL, table)) { log += it }
        session.connect()
        val (heard, tried) = session.probeBmwUds()
        assertEquals(1, heard)
        assertEquals(3, tried)
        assertTrue(log.any { it == "## BMW serie F: se sigue por dirección OBD del motor (7E0, contesta 7E8)" })
        assertTrue(log.any { it.startsWith("## Refrigerante (22461B): 4814 -> 84.52 °C") })
        assertTrue(log.any { it.startsWith("## Cuentakilómetros (2246B1): 0002E62F -> 189999.00 km") })
        assertFalse(log.any { it.startsWith("## Búsqueda de medidas") })
        assertTrue(session.raw("010C").contains("41"))
    }

    @Test
    fun `si llega y ninguna medida de la tabla contesta, las busca una a una`() = runBlocking {
        val other = mapOf(0x4A10 to listOf(0x01, 0x02), 0x5123 to listOf(0x33))
        val session = ObdSession(F20Car(F20Car.PHYSICAL, other)) { log += it }
        session.connect()
        val (heard, _) = session.probeBmwUds()
        assertEquals(1, heard)
        assertTrue(log.any { it.startsWith("## Búsqueda: 224A10 -> 0102") })
        assertTrue(log.any { it.startsWith("## Búsqueda: 225123 -> 33") })
        assertTrue(log.any { it == "## Búsqueda: fin; 2 con dato, 0 rechazadas" })
        // Los "no existe" de las 8.190 restantes no llenan el registro.
        assertTrue(log.count { it.startsWith("## Búsqueda: 22") } == 2)
        assertTrue(log.any { it.startsWith("## Averías presentes y guardadas (19020C): 1: 283400") })
        assertTrue(session.raw("010C").contains("41"))
    }

    @Test
    fun `la lectura normal del B47 encuentra la direccion del motor si la pasarela no llega`() = runBlocking {
        val session = ObdSession(F20Car(F20Car.PHYSICAL, table)) { log += it }
        session.connect()
        val own = session.readUds(Pids.b47Checks)!!
        assertEquals(84.52, own.getValue(Pids.uds(0x461B)), 0.01)
        assertTrue(log.any { it == "## BMW (UDS): la centralita contesta por la dirección OBD del motor (7E0)" })
        val found = session.discoverUds(Pids.b47)
        assertEquals(listOf(Pids.uds(0x44BE), Pids.uds(0x44BF), Pids.uds(0x46B1)), found.map { it.id })
        // El OBD normal sigue contestando entre medias, y las medidas del B47 tambien.
        assertTrue(session.read(Pids.RPM) != null)
        assertEquals(12.5, session.read(Pids.uds(0x44BE))?.let(found[0]::decodeOrNull)!!, 0.01)
    }

    @Test
    fun `con la pasarela funcionando se queda en la pasarela`() = runBlocking {
        val session = ObdSession(F20Car(F20Car.GATEWAY, table)) { log += it }
        session.connect()
        assertEquals(3, session.discoverUds(Pids.b47).size)
        assertTrue(log.any { it == "## BMW (UDS): la centralita contesta por la pasarela de BMW a la centralita 12" })
    }
}
