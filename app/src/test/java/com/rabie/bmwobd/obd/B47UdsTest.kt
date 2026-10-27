package com.rabie.bmwobd.obd

import com.rabie.bmwobd.vehicle.B47Fingerprint
import com.rabie.bmwobd.vehicle.EngineProfile
import com.rabie.bmwobd.vehicle.Vehicle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un B47 detras de un ELM327: el OBD normal contesta por la direccion a todas (7DF) y las medidas
 * de BMW solo por la pasarela a la centralita 12 (6F1, direccion extendida 12, respuestas en 612),
 * como en el registro del 24 de octubre, donde por 7DF solo contestaron la identificacion y las averias.
 */
private class B47Car(private val withTesterByte: Boolean = false) : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    private var pending = ""
    private var header = "7DF"
    private var extended = false
    val sent = mutableListOf<String>()

    // Refrigerante 84,5 °C, 1800 rpm, 14,2 V, hollin usado 12,5 g y kilometros desde regenerar 76,5.
    private val answers = mapOf(
        0x461B to listOf(0x48, 0x14),
        0x427F to listOf(0x4C, 0xD3),
        0x4200 to listOf(0x8E, 0x8A),
        0x44BE to listOf(0x03, 0x33),
        0x44BF to listOf(0x00, 0x01, 0x2A, 0xD4),
    )

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        pending = text.trim()
        sent += pending
        when {
            pending.startsWith("ATSH") -> header = pending.removePrefix("ATSH")
            pending == "ATCEA" -> extended = false
            pending.startsWith("ATCEA") -> extended = true
            pending == "ATZ" -> {
                header = "7DF"
                extended = false
            }
        }
        car.write(text)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val answer = car.readUntilPrompt(timeoutMs)
        if (pending.startsWith("AT")) return answer
        val gateway = header == "6F1" && extended
        if (pending.startsWith("22") && pending.length == 6) {
            val did = pending.substring(2).toInt(16)
            val data = answers[did] ?: return "NO DATA\r\r"
            if (!gateway) return "NO DATA\r\r"
            val text = (if (withTesterByte) "F1" else "") + "62%04X".format(did) + data.joinToString("") { "%02X".format(it) }
            return "$text\r\r"
        }
        return if (gateway) "NO DATA\r\r" else answer
    }
}

class B47UdsTest {

    private val log = mutableListOf<String>()

    @Test
    fun `las medidas del B47 tienen la formula de su tabla`() {
        val soot = Pids.byId.getValue(Pids.uds(0x44BE))
        assertEquals(12.5, soot.decodeOrNull(intArrayOf(0x03, 0x33))!!, 0.01)
        // Un tamaño que no es el suyo es la respuesta de otra medida.
        assertNull(soot.decodeOrNull(intArrayOf(0x03, 0x33, 0x00)))
        val km = Pids.byId.getValue(Pids.uds(0x44BF))
        assertEquals(76.5, km.decodeOrNull(intArrayOf(0x00, 0x01, 0x2A, 0xD4))!!, 0.01)
        assertTrue(Pids.b47.all { Pids.isUds(it.pid) && Pids.isBmw(it.pid) })
        assertFalse(Pids.bmwDiesel.any { Pids.isUds(it.pid) })
    }

    @Test
    fun `un BMW diesel se comprueba como N47 y despues como B47`() {
        val bmw = Vehicle.GENERIC.copy(make = "BMW")
        assertEquals(listOf(EngineProfile.BMW_N47, EngineProfile.BMW_B47), EngineProfile.candidates(bmw))
        assertTrue(EngineProfile.BMW_B47.uds)
        assertEquals(Pids.b47, EngineProfile.BMW_B47.measures)
        assertFalse(EngineProfile.BMW_B47.ownDiagnostics)
    }

    @Test
    fun `la centralita del B47 se reconoce con dos de tres`() {
        val ids = B47Fingerprint.checks.map { it.def.id }
        val obd = mapOf(Pids.COOLANT to 85.0, Pids.RPM to 1790.0, 0x42 to 14.1)
        assertTrue(B47Fingerprint.fits(mapOf(ids[0] to 85.0, ids[1] to 1800.0, ids[2] to 14.2), obd))
        assertTrue(B47Fingerprint.fits(mapOf(ids[0] to 85.0, ids[1] to 3000.0, ids[2] to 14.2), obd))
        assertFalse(B47Fingerprint.fits(mapOf(ids[0] to 85.0), obd))
    }

    @Test
    fun `las medidas van por la pasarela y el OBD normal sigue contestando despues`() = runBlocking {
        val car = B47Car()
        val session = ObdSession(car) { log += it }
        session.connect()
        val found = session.discoverUds(Pids.b47)
        assertEquals(listOf(Pids.uds(0x44BE), Pids.uds(0x44BF)), found.map { it.id })
        assertEquals(12.5, session.read(Pids.uds(0x44BE))?.let(found[0]::decodeOrNull)!!, 0.01)
        // Vuelve a la direccion normal para el OBD, y a la pasarela otra vez solo cuando hace falta.
        assertTrue(session.read(Pids.RPM) != null)
        val before = car.sent.count { it == "ATCEA12" }
        session.read(Pids.uds(0x44BE))
        session.read(Pids.uds(0x44BF))
        assertEquals(before + 1, car.sent.count { it == "ATCEA12" })
        val own = session.readUds(B47Fingerprint.checks.map { it.def })!!
        assertEquals(84.52, own.getValue(Pids.uds(0x461B)), 0.01)
        assertEquals(14.2, own.getValue(Pids.uds(0x4200)), 0.1)
    }

    @Test
    fun `se entiende la respuesta tambien si el adaptador deja el byte F1 delante`() = runBlocking {
        val session = ObdSession(B47Car(withTesterByte = true)) { log += it }
        session.connect()
        assertEquals(2, session.discoverUds(Pids.b47).size)
    }
}
