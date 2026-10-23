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
    fun `los datos del filtro que dio el coche el 19 de octubre`() {
        val response = "063\n0: 705001070000\n1: 00000000001674\n2: CB870000000000\n3: 01194000000000\n4: 018DE59C000000\n" +
            "5: 00000000000000\n6: 000000000D5914\n7: EE01781CD10001\n8: 0007CEBB231B23\n9: AF2366245E0000\nA: 00000000000000\n" +
            "B: 00000000000000\nC: 00000000000000\nD: 00000000000000\nE: 0000AAAAAAAAAA"
        val values = BmwService.parse(query("305001"), response).fieldTexts.toMap()
        // Cuadran con 03FD (376.753,03 km), 0407 (376), 03E8 (53,58 l) y 03ED (34,17 g) leidas a la vez.
        assertEquals("376753,03 km", values["Cuentakilómetros en la última regeneración completa"])
        assertEquals("376", values["Regeneraciones completas"])
        assertEquals("53,58 l", values["Gasóleo desde la última regeneración"])
        assertEquals("34,17 g", values["Hollín (cálculo continuo)"])
        assertEquals("0 s", values["Tiempo de bloqueo que queda"])
        assertEquals("657,9 °C", values["Máxima antes del filtro en regeneración"])
    }

    @Test
    fun `los datos del filtro van a sus columnas del CSV`() {
        val response = "063\n0: 705001070000\n1: 00000000001674\n2: CB870000000000\n3: 01194000000000\n4: 018DE59C000000\n" +
            "5: 00000000000000\n6: 000000000D5914\n7: EE01781CD10001\n8: 0007CEBB231B23\n9: AF2366245E0000\nA: 00000000000000\n" +
            "B: 00000000000000\nC: 00000000000000\nD: 00000000000000\nE: 0000AAAAAAAAAA"
        val values = BmwService.filterValues(BmwService.parse(BmwService.FILTER_QUERY, response))
        assertEquals(376.0, values.getValue(Pids.BMW_ISTA_REGENS_DONE), 0.0)
        assertEquals(7377.0, values.getValue(Pids.BMW_ISTA_REGENS_ASKED), 0.0)
        assertEquals(1.0, values.getValue(Pids.BMW_ISTA_REGEN_UNFINISHED), 0.0)
        assertEquals(0.0, values.getValue(Pids.BMW_ISTA_LOCK_SECONDS), 0.0)
        assertEquals(657.9, values.getValue(Pids.BMW_ISTA_MAX_REGEN_TEMP), 0.01)
        assertEquals(640.4, values.getValue(Pids.BMW_ISTA_MAX_TEMP), 0.01)
        // 00 01 19 40: lo que leyo ISTA el 19 de octubre a las 14:22.
        assertEquals(72000.0, values.getValue(Pids.BMW_ISTA_PROTECTION_SECONDS), 0.0)
        assertTrue(BmwService.filterValues(BmwService.parse(BmwService.FILTER_QUERY, null)).isEmpty())
    }

    @Test
    fun `la correccion del sensor de presion es negativa`() {
        // Cuadra con el cero aprendido 0428: -8,04 hPa.
        val values = BmwService.parse(query("220107"), "620107FFF8FFF9").fieldTexts.toMap()
        assertEquals("-8 hPa", values["Valor 1"])
        assertEquals("-7 hPa", values["Valor 2"])
    }

    @Test
    fun `la NMK del coche coincide con los tiempos aprendidos`() {
        val response = "031\n0: 705701060000\n1: 0F0B2B0B2C0B2C\n2: 006700DE00A900\n3: AB003E0036005A\n4: 0053002D001B00\n" +
            "5: 37003900000000\n6: 000000000D5914\n7: EEAAAAAAAAAAAA"
        val values = BmwService.parse(query("305701"), response).fieldTexts.toMap()
        // 1461 a 1464 dieron 41,12, 67,51, 68,28 y 88,57 µs: el mismo juego en otro orden.
        assertEquals("41,2", values["Cilindro 1 a 400 bar"])
        assertEquals("88,8", values["Cilindro 2 a 400 bar"])
        assertEquals("67,6", values["Cilindro 3 a 400 bar"])
        assertEquals("68,4", values["Cilindro 4 a 400 bar"])
    }

    @Test
    fun `el historial de averias`() {
        // Lo que dio el coche el 19 de octubre tras 62 21 00: cuantas y, por cada una, codigo y estado.
        val payload = ("0E 49 33 01 3F F1 01 3F F0 01 4B 10 01 48 1A 11 4D 00 21 40 10 01 4B 11 01 3F 03 01 " +
            "48 0A 11 48 0A 11 48 0A 11 48 0A 11 48 0A 11").split(' ').map { it.toInt(16) }.toIntArray()
        val history = BmwFault.parseHistory(payload)
        assertEquals(14, history.size)
        assertEquals(listOf(0x4933, 0x3FF1, 0x3FF0, 0x4B10, 0x481A, 0x4D00), history.take(6).map { it.code })
        assertEquals(5, history.count { it.code == 0x480A })
        assertEquals(0x21, history.first { it.code == 0x4D00 }.status)
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
        val test = session.actuatorTest(Actuator(egr.name, egr.lid, egr.values, holdMs = 1, feedback = egr.feedback))
        val sent = test.steps.map { it.query }
        assertEquals(
            listOf(
                "30C201", "2C100BB4",
                "30C2070666", "30C201", "2C100BB4",
                "30C2071999", "30C201", "2C100BB4",
                "30C200", "30C201", "2C100BB4",
            ),
            sent,
        )
        assertEquals(true, test.accepted)
        assertEquals(true, test.released)
        // El coche simulado no mueve nada: aceptada, pero sin que se mueva ninguna lectura.
        assertEquals(false, test.confirmed)
        assertEquals(TestOutcome.DOUBTFUL, TestOutcome.of(test))
    }

    @Test
    fun `una pieza de encender y apagar manda un byte y se da por buena si lo dice`() = runBlocking<Unit> {
        val glow = BmwService.ACTUATORS.first { it.lid == 0xC4 }
        assertEquals("30C40701", BmwService.command(glow, 1.0))
        assertEquals(1.0, BmwService.stateValue(glow, "70C40101")!!, 1e-9)
        // La bomba de combustible va en l/h de 0,01 en 0,01: 50 l/h son 5.000.
        val pump = BmwService.ACTUATORS.first { it.lid == 0xCA }
        assertEquals("30CA071388", BmwService.command(pump, 50.0))
        // Toda la tabla STELLER salvo la mariposa repetida (DRO, la misma CD que LDK).
        assertEquals(26, (listOf(BmwService.FAN) + BmwService.ACTUATORS).map { it.lid }.distinct().size)
        // Una prueba aceptada, devuelta y con la lectura movida es correcta; rechazada, rechazada.
        assertEquals(TestOutcome.OK, TestOutcome.of(FanTest("x", emptyList(), accepted = true, released = true, confirmed = true)))
        assertEquals(TestOutcome.OK, TestOutcome.of(FanTest("x", emptyList(), accepted = true, released = true, confirmed = null)))
        assertEquals(TestOutcome.REFUSED, TestOutcome.of(FanTest("x", emptyList(), accepted = false, released = null)))
        assertEquals(TestOutcome.SILENT, TestOutcome.of(FanTest("x", emptyList(), accepted = null, released = null)))
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
        assertEquals(listOf(0xC2, 0xDC, 0xCE, 0xCD, 0xC6), BmwService.ACTUATORS.take(5).map { it.lid })
        assertEquals("30C701", BmwService.FAN_READ)
        assertEquals("30C700", BmwService.FAN_RELEASE)
        assertEquals(30.0, BmwService.fanPercent(listOf(0x09, 0x99))!!, 0.02)
    }
}
