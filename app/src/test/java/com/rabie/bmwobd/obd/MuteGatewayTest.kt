package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Un F20 detras de un clon que se queda mudo en el protocolo de usuario B (ATSPB): cualquier
 * consulta agota la espera hasta que se reinicia o se vuelve a ATSP6. Los bloques de la DDE
 * contestan por la direccion a todas (7DF), como el 7-11-2026.
 */
private class MuteUserProtocolCar(private val blocks: Map<Int, String>) : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    private var pending = ""
    private var header = "7DF"
    private var extended = false
    private var userProtocol = false
    var silences = 0

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        pending = text.trim().uppercase().replace(" ", "")
        when {
            pending == "ATSPB" -> userProtocol = true
            pending.startsWith("ATSP") || pending == "ATZ" || pending == "ATD" -> {
                userProtocol = false
                if (pending == "ATZ" || pending == "ATD") {
                    header = "7DF"
                    extended = false
                }
            }
            pending.startsWith("ATSH") -> header = pending.removePrefix("ATSH")
            pending == "ATCEA" -> extended = false
            pending.startsWith("ATCEA") -> extended = true
        }
        car.write(text)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val answer = car.readUntilPrompt(timeoutMs)
        val query = pending
        if (query.startsWith("AT")) return answer
        if (userProtocol) {
            silences++
            throw SocketTimeoutException("sin respuesta")
        }
        if (header == "6F1" && extended) return "NO DATA\r\r"
        if (query.startsWith("22") && query.length == 6) {
            val did = query.substring(2).toInt(16)
            if (did == 0xF150) return "62F1500F1F90\r\r"
            val data = blocks[did]
            return when {
                data != null && header == "7DF" -> "62%04X%s\r\r".format(did, data)
                header == "7DF" -> "NO DATA\r\r"
                else -> "7F2231\r\r"
            }
        }
        return answer
    }
}

class MuteGatewayTest {

    private val cbs = "2650070B07EA050C1C00030DDB00030DDB0000E464300C1C4E2F00030DDB05787864643B0000C2C3004624F7004624F70110070B07EA01008D0002013600000000"

    @Test
    fun `un adaptador mudo en el protocolo B no corta la conexion y el B47 se encuentra por la direccion a todas`() = runBlocking {
        val log = mutableListOf<String>()
        val car = MuteUserProtocolCar(mapOf(0x4050 to cbs))
        val session = ObdSession(car) { log += it }
        session.connect()
        val index = session.readUds(listOf(Pids.B47_IDENT))!![Pids.B47_IDENT.id]!!.toInt()
        assertEquals(0x0F1F90, index)
        val found = session.discoverUds(Pids.b47)
        assertTrue(car.silences > 0)
        assertTrue(log.any { it.startsWith("## BMW (UDS): el adaptador no contesta por la pasarela de BMW a la centralita 12, tramas de longitud variable") })
        assertTrue(log.any { it == "## BMW (UDS): la centralita contesta por la dirección OBD a todas (7DF)" })
        val km = found.first { it.name == "Cuentakilómetros (cuadro)" }
        assertEquals(200155.0, session.read(km.pid)?.let(km::decodeOrNull)!!, 0.0)
        // La lectura normal sigue.
        assertTrue(session.read(Pids.RPM) != null)
        assertTrue(session.read(Pids.RPM) != null)
    }
}
