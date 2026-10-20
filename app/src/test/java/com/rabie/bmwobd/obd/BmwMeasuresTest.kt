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
    fun `una respuesta de otro tamaño no vale para una medida de BMW`() {
        // Trayecto del 12 de octubre: el crucero (un byte) recibio 6172, que era la presion del filtro.
        val cruise = Pids.byId.getValue(Pids.bmw(0x0839))
        assertNull(cruise.decodeOrNull(intArrayOf(0x61, 0x72)))
        assertEquals(1.0, cruise.decodeOrNull(intArrayOf(0x01))!!, 0.0)
        // Y el aire del intercooler (dos bytes) recibio los cuatro de la distancia desde regenerar.
        val air = Pids.byId.getValue(Pids.bmw(0x076F))
        assertNull(air.decodeOrNull(intArrayOf(0x00, 0x01, 0xC2, 0x40)))
        // Las del OBD normal siguen aceptando respuestas mas largas que su dato.
        assertEquals(90.0, Pids.byId.getValue(Pids.COOLANT).decodeOrNull(intArrayOf(130, 0, 0))!!, 0.0)
    }

    @Test
    fun `las post-inyecciones salen con lo que contesto el coche regenerando`() {
        // Barrido del 11 de octubre a las 12:14, con la peticion en 5 y el modo 0x02010002:
        // 2C1004B5 contesto 030D y 2C1004B6 contesto 0625.
        assertEquals(312.4, Pids.byId.getValue(Pids.bmw(0x04B5)).decodeOrNull(intArrayOf(0x03, 0x0D))!!, 0.01)
        assertEquals(629.2, Pids.byId.getValue(Pids.bmw(0x04B6)).decodeOrNull(intArrayOf(0x06, 0x25))!!, 0.01)
        assertTrue(Pids.bmw(0x04B5) in com.rabie.bmwobd.cases.Case.FILTER.watch)
    }

    @Test
    fun `el desfase de la distribucion sale con lo que contesto el coche`() {
        // Barrido del 11 de octubre: 2C1015E5 contesto 7FD3 en las siete lecturas.
        val def = Pids.byId.getValue(Pids.bmw(Pids.BMW_TIMING_OFFSET_ADDRESS))
        assertEquals(-0.129, def.decodeOrNull(intArrayOf(0x7F, 0xD3))!!, 0.002)
        assertEquals(com.rabie.bmwobd.obd.Tier.SLOW, def.tier)
    }

    @Test
    fun `al conectar se quedan las medidas que contestan y una sola de cada identificador`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        val found = session.discoverBmw(candidates())

        assertEquals(candidates().size, found.size)
        assertTrue(found.any { it.id == Pids.BMW_INJECTION })
        // Una direccion por consulta: con dos, la centralita de verdad solo contesta bien a la primera.
        assertTrue(log.filter { it.startsWith(">> 2C10") }.all { it.length == ">> 2C10".length + 4 })
        assertTrue(log.any { it.startsWith("## BMW: contestan ${found.size} de") })
        assertFalse(log.any { it.contains("espera fija") })
    }

    @Test
    fun `las medidas se leen sin lectura rapida y con su formula`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        val soot = candidates().first { it.id == Pids.BMW_SOOT_MEASURED }
        val distance = candidates().first { it.id == Pids.bmw(0x03EB) }
        log.clear()

        assertEquals(14.2, soot.decodeOrNull(session.read(soot.pid, singleFrame = true)!!)!!, 0.05)
        assertEquals(183.4, distance.decodeOrNull(session.read(distance.pid)!!)!!, 0.01)
        assertEquals(listOf(">> 2C1003EA", ">> 2C1003EB"), log.filter { it.startsWith(">>") })
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
    fun `las medidas de BMW solo se piden a un BMW diesel`() {
        assertEquals(Pids.all.size, Pids.byId.size)
        assertTrue(Pids.hasBmwMeasures("BMW", diesel = true))
        assertFalse(Pids.hasBmwMeasures("BMW", diesel = false))
        assertFalse(Pids.hasBmwMeasures("Volkswagen", diesel = true))
        assertFalse(Pids.hasBmwMeasures(null, diesel = true))
    }

    @Test
    fun `el filtro por el OBD estandar, para los coches que no son BMW`() {
        // 7A: soporta la diferencial, 0x0096 = 150 centesimas de kPa = 1,5 kPa = 15 hPa.
        assertEquals(1.5, Pids.byId.getValue(Pids.OBD_FILTER_PRESSURE).decodeOrNull(intArrayOf(0x01, 0x00, 0x96, 0, 0, 0, 0))!!, 0.001)
        // 8B: soporta el estado y la distancia media; regenerando, y 517 km entre regeneraciones.
        val status = intArrayOf(0x41, 0x01, 0x00, 0x00, 0x00, 0x02, 0x05)
        assertEquals(1.0, Pids.byId.getValue(Pids.OBD_REGENERATING).decodeOrNull(status)!!, 0.0)
        assertEquals(517.0, Pids.byId.getValue(Pids.part(0x8B, 1)).decodeOrNull(status)!!, 0.0)
        assertTrue(Pids.regenerating(mapOf(Pids.OBD_REGENERATING to 1.0)))
        assertFalse(Pids.regenerating(mapOf(Pids.OBD_REGENERATING to 0.0)))
        // Sin ese dato, un coche que no publica nada no regenera para la app.
        assertFalse(Pids.regenerating(mapOf(Pids.RPM to 850.0)))
    }
}
