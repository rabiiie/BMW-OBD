package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwUdsTest {

    private fun bytes(hex: String) = IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }

    @Test
    fun `las medidas se pasan con la formula de la tabla d70bx7a0`() {
        // 0x39FE como en el N47: 14846 x 0,01 - 100 = 48,46 °C.
        assertEquals("39FE -> 48.46 °C", BmwUds.describe("22461B", bytes("62461B39FE")))
        // 9180 x 0,091554 = 840,47 rpm.
        assertEquals("23DC -> 840.47 rpm", BmwUds.describe("22427F", bytes("62427F23DC")))
        // La identificacion va tal cual, con su texto.
        assertEquals("4142  [AB]", BmwUds.describe("22F150", bytes("62F1504142")))
    }

    @Test
    fun `kilometros, horas y filtro salen con su tamaño y en km u horas`() {
        // Cuatro bytes: 0x0002E62F = 189.999 km.
        assertEquals("0002E62F -> 189999.00 km", BmwUds.describe("2246B1", bytes("6246B10002E62F")))
        // 36.000.000 s son 10.000 h.
        assertEquals("02255100 -> 10000.00 h", BmwUds.describe("224BC4", bytes("624BC402255100")))
        // De 10 en 10 km: 12.000 x 10.
        assertEquals("00002EE0 -> 120000.00 km", BmwUds.describe("224C79", bytes("624C7900002EE0")))
        // En metros: 512.300 m son 512,3 km.
        assertEquals("0007D12C -> 512.30 km", BmwUds.describe("2244BF", bytes("6244BF0007D12C")))
        // Un byte y sin unidad.
        assertEquals("05 -> 5.00", BmwUds.describe("2244BB", bytes("6244BB05")))
        // Si llegan menos bytes de los que tiene la medida, no se inventa.
        assertEquals("sin dato: 0002", BmwUds.describe("2246B1", bytes("6246B10002")))
    }

    @Test
    fun `una averia con texto en la tabla lo lleva`() {
        val text = BmwUds.describe("19020C", bytes("59020C" + "24530009"))
        assertEquals("1: 245300 presente (Abgasdifferenzdrucksensor Dieselpartikelfilter, Plausibilität: Schlauchleitung abgefallen oder undicht)", text)
    }

    @Test
    fun `un rechazo dice su motivo y lo que no es de esta consulta no cuenta`() {
        assertEquals("rechazada, código 31 (identificador fuera de rango)", BmwUds.describe("22461B", bytes("7F2231")))
        // Respuesta a otro identificador, o de otro servicio: no es la suya.
        assertNull(BmwUds.describe("22461B", bytes("62427F23DC")))
        assertNull(BmwUds.describe("22461B", bytes("410C1AF8")))
        assertNull(BmwUds.describe("22461B", IntArray(0)))
    }

    @Test
    fun `las averias dicen su codigo y si fallan ahora`() {
        val text = BmwUds.describe("19020C", bytes("59020C" + "4A6E0009" + "480A0008"))
        assertEquals("2: 4A6E00 presente, 480A00 guardada", text)
        assertEquals("ninguna", BmwUds.describe("19020C", bytes("59020C")))
    }

    @Test
    fun `con el coche simulado, que es el E87, la prueba no oye nada y deja la lectura normal`() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        val (heard, tried) = session.probeBmwUds()
        assertEquals(0, heard)
        assertEquals(4, tried)
        assertTrue(log.any { it.contains("no ha contestado en ninguna forma") })
        // El adaptador vuelve a leer el OBD normal.
        assertTrue(session.raw("010C").contains("41"))
    }
}
