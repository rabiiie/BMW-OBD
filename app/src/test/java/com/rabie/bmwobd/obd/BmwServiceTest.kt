package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwServiceTest {

    private fun query(lid: String) = BmwService.QUERIES.first { it.query == lid }

    @Test
    fun `todas las lecturas son de solo lectura`() {
        // Servicio 22 lee; el 30 solo con 01, "dame el estado actual".
        // Y 2C 10 con una medida, la lectura normal de la app.
        assertTrue(
            BmwService.QUERIES.all {
                it.query.startsWith("22") || (it.query.startsWith("30") && it.query.endsWith("01") && it.query.length == 6) ||
                    (it.query.startsWith("2C10") && it.query.length == 8)
            },
        )
    }

    @Test
    fun `una correccion sin corregir sale 1`() {
        val read = BmwService.parse(query("30A401"), "70 A4 01 20 00")
        assertEquals(1.0, read.value!!, 0.001)
        assertEquals("70A401".length, read.query.answer.length)
        assertEquals("20 00", read.raw)
    }

    @Test
    fun `el ralenti lleva signo`() {
        assertEquals(-10.0, BmwService.parse(query("30A301"), "70 A3 01 FF EC").value!!, 0.001)
    }

    @Test
    fun `un rechazo da su motivo y no hay valor`() {
        val read = BmwService.parse(query("305B01"), "7F 30 22")
        assertEquals(0x22, read.refusal)
        assertNull(read.value)
        assertEquals("Rechazado: no se dan las condiciones", read.status)
    }

    @Test
    fun `los bits del aprendizaje de la EGR`() {
        val read = BmwService.parse(query("305B01"), "70 5B 01 02 12")
        assertEquals(listOf("válvula que se agarrota", "aprendizaje terminado", "cable cortado"), read.activeBits)
    }

    @Test
    fun `los datos del filtro en las posiciones de CSF_WERTE`() {
        // Bytes numerados tras el eco, como en la prueba con el .prg: el byte i vale i + 3.
        val data = (3..70).joinToString(" ") { "%02X".format(it) }
        val read = BmwService.parse(query("305001"), "70 50 01 $data")
        val values = read.fieldTexts.toMap()
        assertEquals("1286 km", values["Km desde el último cambio de filtro"])
        assertEquals("12594", values["Regeneraciones completas"])
        assertEquals("13108", values["Regeneraciones pedidas"])
        assertEquals("1397,5 °C", values["Máxima antes del filtro en regeneración"])
    }

    @Test
    fun `la correccion de un inyector lleva signo`() {
        val read = BmwService.parse(query("305701"), "70 57 01 " + "FF F0 ".repeat(20))
        assertEquals("-6,4", read.fieldTexts.toMap()["Cilindro 1 a 400 bar"])
    }

    @Test
    fun `la regeneracion segun ISTA`() {
        assertEquals(IstaRegen(requested = true, running = true, locked = false), IstaRegen.of(5.0, 0x02010002.toDouble(), 0.0))
        assertEquals(IstaRegen(requested = true, running = false, locked = false), IstaRegen.of(5.0, 0x00100001.toDouble(), 0.0))
        // La proteccion cuenta como en marcha y la peticion 7 no cuenta como pedida.
        assertEquals(IstaRegen(requested = false, running = true, locked = true), IstaRegen.of(7.0, 0x00210002.toDouble(), 1.0))
        assertEquals(IstaRegen(requested = false, running = false, locked = false), IstaRegen.of(1.0, 0x80100001.toDouble(), 0.0))
        assertNull(IstaRegen.of(null, 1.0, 0.0))
    }

    @Test
    fun `la prueba de la EGR pasa por los dos valores y devuelve el control`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) {}
        session.connect()
        val egr = BmwService.ACTUATORS.first()
        val test = session.actuatorTest(Actuator(egr.name, egr.lid, egr.percents, holdMs = 1, feedback = egr.feedback))
        val sent = test.steps.map { it.query }
        assertEquals(listOf("30C201", "2C100BB4", "30C2070666", "2C100BB4", "30C2071999", "2C100BB4", "30C200", "30C201", "2C100BB4"), sent)
        assertEquals(true, test.accepted)
        assertEquals(true, test.released)
    }

    @Test
    fun `la prueba de cilindros vuelve siempre a lo normal`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) {}
        session.connect()
        val test = BmwService.ROUTINES.first()
        val result = session.runRoutine(ServiceRoutine(test.name, test.need, test.description, test.start, holdMs = 1, reads = test.reads, finish = test.finish))
        val sent = result.steps.map { it.query }
        assertEquals("318501", sent.first())
        assertEquals("3285", sent.last())
        assertEquals(8, sent.count { it.startsWith("2C1014") })
        assertEquals(true, result.accepted)
        assertEquals(true, result.released)
    }

    @Test
    fun `los servicios son los telegramas del prg y caben en un mensaje`() {
        val sent = BmwService.ROUTINES.flatMap { it.start + it.finish }
        assertTrue(sent.containsAll(listOf("318501", "3285", "30B3070960", "30B3070000", "31E001", "31E010", "3130001000", "307A04", "30F60804", "30F60802")))
        // El ELM327 manda como mucho siete bytes de datos en un mensaje.
        assertTrue(sent.all { it.length <= 14 })
    }

    @Test
    fun `la orden del ventilador al 30 por ciento`() {
        // Lo que manda STEUERN_SELECTIV del d70n47b0.prg con ELU al 30 %.
        assertEquals("30C7070999", BmwService.fanCommand(30.0))
        // Y lo que manda con la EGR al 20 y al 80 %.
        assertEquals("30C2070666", BmwService.command(0xC2, 20.0))
        assertEquals("30C2071999", BmwService.command(0xC2, 80.0))
        assertEquals(listOf(0xC2, 0xCE, 0xCD, 0xC6), BmwService.ACTUATORS.map { it.lid })
        assertEquals("30C701", BmwService.FAN_READ)
        assertEquals("30C700", BmwService.FAN_RELEASE)
        assertEquals(30.0, BmwService.fanPercent(listOf(0x09, 0x99))!!, 0.02)
    }
}
