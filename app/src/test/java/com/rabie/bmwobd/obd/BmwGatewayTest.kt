package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un coche con un adaptador que solo llega a la pasarela de BMW (6F1, direccion extendida) con el
 * protocolo de usuario B y tramas de longitud variable (PB C1 01), como lo hacen Deep OBD y
 * live-obd; con ATSP6 la pasarela calla. Opcionalmente, la centralita del motor solo da sus medidas
 * por la direccion del motor (7E0) dentro de la sesion ampliada (10 03).
 */
private class GatewayCar(
    private val modules: Map<Int, Map<String, String>>,
    private val extendedOnly: Map<String, String> = emptyMap(),
) : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    private var pending = ""
    private var header = "7DF"
    private var target: Int? = null
    private var pb = ""
    private var userProtocol = false
    private var extendedSession = false
    val sent = mutableListOf<String>()

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        pending = text.trim().uppercase().replace(" ", "")
        sent += pending
        when {
            pending.startsWith("ATPB") -> pb = pending.removePrefix("ATPB")
            pending == "ATSPB" -> userProtocol = true
            pending.startsWith("ATSP") || pending == "ATZ" || pending == "ATD" -> {
                userProtocol = false
                if (pending == "ATZ" || pending == "ATD") {
                    header = "7DF"
                    target = null
                }
            }
            pending.startsWith("ATSH") -> header = pending.removePrefix("ATSH")
            pending == "ATCEA" -> target = null
            pending.startsWith("ATCEA") -> target = pending.removePrefix("ATCEA").toInt(16)
        }
        car.write(text)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val answer = car.readUntilPrompt(timeoutMs)
        val query = pending
        if (query.startsWith("AT")) return answer
        val gateway = header == "6F1" && target != null
        if (gateway) {
            val reply = modules[target]?.get(query)
            return if (userProtocol && pb == "C101" && reply != null) "F1$reply\r\r" else "NO DATA\r\r"
        }
        if (header == "7E0") {
            if (query == "1003") {
                extendedSession = true
                return "5003001E00C8\r\r"
            }
            if (query == "1001") {
                extendedSession = false
                return "5001001E00C8\r\r"
            }
            extendedOnly[query]?.let { return if (extendedSession) "$it\r\r" else "7F2231\r\r" }
        }
        return answer
    }
}

class BmwGatewayTest {

    private val log = mutableListOf<String>()

    @Test
    fun `las centralitas del E87 contestan por la pasarela con tramas de longitud variable`() = runBlocking {
        val car = GatewayCar(
            mapOf(
                0x12 to mapOf("1A80" to "5A80000008512499"),
                0x60 to mapOf("1A80" to "5A80000009123456"),
            ),
        )
        val session = ObdSession(car) { log += it }
        session.connect()
        val (answered, asked) = session.probeModules()
        assertEquals(2, answered)
        assertEquals(5, asked)
        assertTrue(log.any { it == "## Centralitas, tramas de longitud variable: inicio" })
        // Antes de cada centralita el adaptador se pone como live-obd: protocolo B, longitud variable y sin comprobar el bus.
        val setup = car.sent.dropWhile { it != "ATPBC101" }.take(10)
        assertEquals(listOf("ATPBC101", "ATSPB", "ATBI", "ATAL", "ATSH6F1", "ATFCSH6F1", "ATFCSD12300002", "ATFCSM1", "ATCEA12", "ATCRA612"), setup)
        // Al acabar vuelve a la lectura normal.
        assertTrue(session.read(Pids.RPM) != null)
    }

    @Test
    fun `el B47 se lee entero por la pasarela con tramas de longitud variable y el OBD sigue entre medias`() = runBlocking {
        val engine = mapOf(
            "22461B" to "62461B4814",
            "22F150" to "62F1500F1F90",
            "2244BE" to "6244BE0333",
            "2246B1" to "6246B10002E62F",
        )
        val session = ObdSession(GatewayCar(mapOf(0x12 to engine))) { log += it }
        session.connect()
        val found = session.discoverUds(Pids.b47)
        assertTrue(log.any { it == "## BMW (UDS): la centralita contesta por la pasarela de BMW a la centralita 12, tramas de longitud variable" })
        assertEquals(listOf(Pids.uds(0x44BE), Pids.uds(0x46B1)), found.map { it.id })
        // Ida y vuelta: la medida de BMW por la pasarela, el OBD por el protocolo normal.
        assertTrue(session.read(Pids.RPM) != null)
        assertEquals(12.5, session.read(Pids.uds(0x44BE))?.let(found[0]::decodeOrNull)!!, 0.01)
        assertTrue(session.read(Pids.RPM) != null)
        assertEquals(189999.0, session.read(Pids.uds(0x46B1))?.let(found[1]::decodeOrNull)!!, 0.0)
    }

    @Test
    fun `si la centralita solo da sus medidas en sesion ampliada, se lee en ella y se pide al volver`() = runBlocking {
        val car = GatewayCar(emptyMap(), extendedOnly = mapOf("22461B" to "62461B4814", "2244BE" to "6244BE0333"))
        val session = ObdSession(car) { log += it }
        session.connect()
        val found = session.discoverUds(Pids.b47)
        assertTrue(log.any { it == "## BMW (UDS): la centralita contesta por la dirección OBD del motor (7E0), en sesión de diagnóstico ampliada" })
        assertEquals(listOf(Pids.uds(0x44BE)), found.map { it.id })
        assertTrue(session.read(Pids.RPM) != null)
        val before = car.sent.count { it == "1003" }
        assertEquals(12.5, session.read(Pids.uds(0x44BE))?.let(found[0]::decodeOrNull)!!, 0.01)
        // Al volver del OBD a la centralita se vuelve a pedir la sesion, que ella cierra sola.
        assertEquals(before + 1, car.sent.count { it == "1003" })
    }

    @Test
    fun `la prueba del F20 quita el F1 de la pasarela y prueba las otras formas si las medidas no contestan`() = runBlocking {
        val engine = mapOf("22F150" to "62F1500F1F90", "22461B" to "62461B4814")
        val session = ObdSession(GatewayCar(mapOf(0x12 to engine))) { log += it }
        session.connect()
        session.probeBmwUds()
        assertTrue(log.any { it.startsWith("## Forma pasarela de BMW a la centralita 12, tramas de longitud variable: identificación sí; 1 de 4 medidas con dato") })
        assertTrue(log.any { it == "## BMW serie F: se sigue por pasarela de BMW a la centralita 12, tramas de longitud variable" })
        assertTrue(log.any { it.startsWith("## Refrigerante (22461B): 4814 -> 84.52 °C") })
    }
}
