package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwCarInfoTest {

    // Lo que contesto el coche el 11 de octubre, con el cuentakilometros en 378.649 km.
    private val first = unhex(
        "A9218B5B000C1389227EB340323E0009485565900A26002493E893DD93D193BE93B793B33EFD" +
            "0000000000002E1400040440400000000000000000000000FFFF00057BFF00FF00950000",
    )
    private val second = unhex("5121B010A50001000000004C4C4C4C4C4CB0B3C5A7A7AEFF055A000000")

    @Test
    fun `la bateria del coche`() {
        val battery = BmwBattery.parse(first, second)!!
        // Los km coinciden con el cuentakilometros de ese dia: las posiciones son buenas.
        assertEquals(listOf(378_640, 378_530, 378_410, 378_220, 378_150, 378_110), battery.kmDays)
        assertEquals(161_250, battery.lastChange)
        assertEquals(88.0, battery.charge, 0.0)
        assertEquals(listOf(89.5, 98.5, 83.5, 83.5, 87.0), battery.chargeDays)
        assertEquals(38.0, battery.startLimit, 0.0)
        assertEquals(16, battery.seasonTemperature)
        assertEquals(0.75, battery.hoursByTemperature[0], 0.001)
        assertEquals(1543.08, battery.hoursByTemperature[1], 0.01)
    }

    @Test
    fun `sin las dos respuestas enteras no hay bateria`() {
        assertNull(BmwBattery.parse(first, null))
        assertNull(BmwBattery.parse(first.copyOf(20), second))
    }

    @Test
    fun `los codigos de los inyectores`() {
        val refused = BmwIma.parse("7F3012")!!
        assertEquals(0x12, refused.refusal)
        val text = BmwIma.parse("70BB01" + "ABCD".repeat(4).map { "%02X".format(it.code) }.joinToString(""))!!
        assertTrue(text.text)
        assertEquals(listOf("ABCD", "ABCD", "ABCD", "ABCD"), text.codes)
        val raw = BmwIma.parse("70BB0101020304050607")!!
        assertEquals(listOf("01 02 03 04 05 06 07"), raw.codes)
        assertNull(BmwIma.parse("NO DATA"))
    }

    @Test
    fun `al conectar se leen la bateria y los codigos`() = runBlocking<Unit> {
        val log = mutableListOf<String>()
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        val car = session.carInfo()
        assertNotNull(car.battery)
        assertEquals(0x12, car.ima?.refusal)
        assertTrue(log.any { it.contains("último cambio registrado a los 161250 km") })
    }

    private fun unhex(text: String) = IntArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16) }
}
