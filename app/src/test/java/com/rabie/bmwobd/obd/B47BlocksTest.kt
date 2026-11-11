package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Los bloques de la DDE del F20 con las respuestas que dio el coche de verdad el 7-11-2026
 * (registro_bmwobd_20261107_2352.txt, busqueda 22 4000-4AB4 por la direccion a todas, 7DF).
 */
class B47BlocksTest {

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16) }

    // CBS_RESET_DATEN_LESEN: dia, mes, año, deposito, km del cuadro dos veces, horas de motor...
    private val cbs = "2650070B07EA050C1C00030DDB00030DDB0000E464300C1C4E2F00030DDB05787864643B0000C2C3004624F7004624F70110070B07EA01008D0002013600000000"

    // STATUS_MESSWERTE_IBS: temperatura, tension y corriente del sensor de bateria, y 22 bytes de estado.
    private val ibs = "00D25F700A9500000B00000300020000000000000000000000000000"

    // STATUS_CBSMOTOROELHIST: consumo medio, km y litros que cuenta la centralita.
    private val oil = "0000C2C300030C1C00107FB6"

    private val real = mapOf(0x4050 to bytes(cbs), 0x402B to bytes(ibs), 0x404F to bytes(oil))

    private fun value(did: Int, name: String): Double? {
        val def = Pids.b47Blocks.first { it.pid == Pids.uds(did) && it.name == name }
        return def.decodeOrNull(real.getValue(did).toIntArray())
    }

    @Test
    fun `los bloques del coche tienen el tamaño que se espera`() {
        assertEquals(65, real.getValue(0x4050).size)
        assertEquals(28, real.getValue(0x402B).size)
        assertEquals(12, real.getValue(0x404F).size)
    }

    @Test
    fun `del bloque de servicio salen los km del cuadro, las horas y el deposito`() {
        assertEquals(200155.0, value(0x4050, "Cuentakilómetros (cuadro)")!!, 0.0)
        assertEquals(4157.7, value(0x4050, "Horas de motor (escala sin confirmar)")!!, 0.1)
        assertEquals(31.0, value(0x4050, "Depósito (escala sin confirmar)")!!, 0.01)
    }

    @Test
    fun `del sensor de bateria salen temperatura, tension y corriente`() {
        assertEquals(21.0, value(0x402B, "Batería: temperatura (sensor)")!!, 0.01)
        // El adaptador midio 12,4 a 12,06 V con el contacto puesto durante la busqueda.
        assertEquals(12.11, value(0x402B, "Batería: tensión (sensor)")!!, 0.01)
        assertEquals(16.72, value(0x402B, "Batería: corriente (signo sin confirmar)")!!, 0.01)
    }

    @Test
    fun `del historial de consumo salen los km y los litros`() {
        assertEquals(199708.0, value(0x404F, "Consumo: km que cuenta la centralita")!!, 0.0)
        assertEquals(10812.7, value(0x404F, "Consumo: gasóleo que cuenta la centralita")!!, 0.01)
    }

    @Test
    fun `un bloque de otro tamaño no da valores`() {
        val def = Pids.b47Blocks.first { it.pid == Pids.uds(0x4050) }
        assertNull(def.decodeOrNull(real.getValue(0x4050).dropLast(1).toIntArray()))
    }

    @Test
    fun `una temperatura bajo cero sale con su signo`() {
        val def = Pids.b47Blocks.first { it.name == "Batería: temperatura (sensor)" }
        val cold = bytes("FFCE" + ibs.drop(4)).toIntArray()
        assertEquals(-5.0, def.decodeOrNull(cold)!!, 0.01)
    }

    @Test
    fun `cada medida de bloque tiene su propio identificador`() {
        val ids = Pids.b47Blocks.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it in Pids.b47.filter { def -> def !in Pids.b47Blocks }.map { def -> def.id } })
        assertTrue(Pids.b47Blocks.all { it in Pids.b47 && Pids.byId[it.id] == it })
    }

    @Test
    fun `el F20 se identifica con el indice de d70bx7a0`() = runBlocking {
        val car = F20LikeCar(real)
        val session = ObdSession(car) { }
        session.connect()
        val index = session.readUds(listOf(Pids.B47_IDENT))!![Pids.B47_IDENT.id]!!.toInt()
        assertEquals(0x0F1F90, index)
        assertTrue(index in Pids.B47_INDEXES)
    }

    @Test
    fun `como el F20 real, sin medidas sueltas, la lectura encuentra los bloques por la direccion a todas`() = runBlocking {
        val log = mutableListOf<String>()
        val session = ObdSession(F20LikeCar(real)) { log += it }
        session.connect()
        val found = session.discoverUds(Pids.b47)
        assertEquals(Pids.b47Blocks.map { it.id }.toSet(), found.map { it.id }.toSet())
        assertTrue(log.any { it == "## BMW (UDS): la centralita contesta por la dirección OBD a todas (7DF)" })
        val km = found.first { it.name == "Cuentakilómetros (cuadro)" }
        assertEquals(200155.0, session.read(km.pid)?.let(km::decodeOrNull)!!, 0.0)
        // El OBD normal sigue contestando despues.
        assertTrue(session.read(Pids.RPM) != null)
    }
}

/**
 * Un F20 como el del 7-11-2026: la pasarela 6F1 -> 12 no llega, la identificacion contesta por las
 * direcciones OBD y los bloques solo se vieron por la direccion a todas (7DF). Las medidas sueltas
 * no contestan: por la del motor "no existe", por la de todas silencio.
 */
private class F20LikeCar(private val blocks: Map<Int, List<Int>>) : ObdTransport {
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

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val answer = car.readUntilPrompt(timeoutMs)
        if (pending.startsWith("AT")) return answer
        if (header == "6F1" && extended) return "NO DATA\r\r"
        if (pending.startsWith("22") && pending.length == 6) {
            val did = pending.substring(2).toInt(16)
            if (did == 0xF150) return "62F1500F1F90\r\r"
            val data = blocks[did]
            return when {
                data != null && header == "7DF" -> "62%04X".format(did) + data.joinToString("") { "%02X".format(it) } + "\r\r"
                header == "7DF" -> "NO DATA\r\r"
                else -> "7F2231\r\r"
            }
        }
        return answer
    }
}
