package com.rabie.bmwobd.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La app contra lo que contestaron los coches de verdad: el E87 (N47) el 5-11-2026 y el F20 (B47) el
 * 7-11-2026. Los valores esperados son los que la app dejo en esos mismos registros, leidos con la
 * version de entonces. Si un cambio en como se pregunta o se decodifica los altera, falla aqui.
 */
class ReplayTest {

    private fun log(name: String): List<String> =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader(Charsets.UTF_8).readLines()

    private fun value(session: ObdSession, def: PidDef): Double? = runBlocking { session.read(def.pid)?.let(def::decodeOrNull) }

    @Test
    fun `N47 del 5 de noviembre, contadores del coche iguales a los del registro`() = runBlocking<Unit> {
        val car = ReplayTransport(log("registro_real_n47_20261105.txt"))
        val messages = mutableListOf<String>()
        val session = ObdSession(car) { messages += it }
        val info = session.connect()
        assertTrue(Pids.RPM in info.supportedPids)
        val found = session.discoverBmw(Pids.bmwDiesel)
        // El valor de cada una al descubrirla, como lo deja la app en el registro.
        fun read(name: String) = messages.firstOrNull { it.startsWith("## BMW: $name = ") }
            ?.substringAfter(" = ")?.substringBefore(" ")?.toDouble()
        // Lo que dijo el registro de esa conexion para cada una.
        val expected = mapOf(
            "Cuentakilómetros" to 381969.0,
            "Horas de motor" to 7290.8,
            "Gasóleo inyectado en total" to 19140.0,
            "Filtro: regeneraciones hechas" to 385.0,
            "Filtro: km desde su cambio" to 287450.0,
            "Filtro: intervalo medio entre regeneraciones" to 519.0,
            "Aceite: km desde el cambio" to 2070.0,
            "Filtro: hollín (el que usa la centralita)" to 5.37,
        )
        for ((name, wanted) in expected) {
            assertNotNull("no se encontro $name", found.firstOrNull { it.name == name })
            assertEquals(name, wanted, read(name)!!, 0.05)
        }
        // Mas de la mitad de la lista contesto aquel dia; si ahora salen muchas menos, algo se rompio al preguntar.
        assertTrue("solo contestan ${found.size}", found.size >= Pids.bmwDiesel.size / 2)
    }

    @Test
    fun `N47 del 5 de noviembre, bateria, inyectores y averias como en el registro`() = runBlocking<Unit> {
        val car = ReplayTransport(log("registro_real_n47_20261105.txt"))
        val session = ObdSession(car) { }
        session.connect()
        session.discoverBmw(Pids.bmwChecks)
        val faults = session.ownFaults()!!
        assertEquals(listOf(0x498E, 0x4A6E, 0x3EFC), faults.map { it.code })
        val info = session.carInfo()
        assertEquals(77.5, info.battery!!.charge, 0.01)
        assertEquals(38.5, info.battery!!.startLimit, 0.01)
        assertEquals(4, info.ima!!.codes.size)
    }

    @Test
    fun `F20 del 7 de noviembre, se identifica y da los bloques por la direccion a todas sin cortar la conexion`() = runBlocking<Unit> {
        val car = ReplayTransport(log("registro_real_f20_20261107.txt"))
        val messages = mutableListOf<String>()
        // Aquel dia el primer intento dio UNABLE TO CONNECT y la app volvio a conectar: aqui igual.
        val first = ObdSession(car) { messages += it }
        val failed = runCatching { first.connect() }.isFailure
        assertTrue(failed)
        val session = ObdSession(car) { messages += it }
        session.connect()
        val index = session.readUds(listOf(Pids.B47_IDENT))!![Pids.B47_IDENT.id]!!.toInt()
        assertEquals(0x0F1F90, index)
        val found = session.discoverUds(Pids.b47)
        assertTrue(messages.any { it == "## BMW (UDS): la centralita contesta por la dirección OBD a todas (7DF)" })
        val km = found.first { it.name == "Cuentakilómetros (cuadro)" }
        assertEquals(200155.0, value(session, km)!!, 0.0)
        // El registro tiene dos busquedas con el sensor de bateria: 12,11 V y 12,23 V. Las tres medidas
        // del bloque se descubren con una pregunta cada una, asi que la tension sale de una de las dos.
        assertTrue(found.any { it.name == "Batería: tensión (sensor)" })
        val volts = messages.first { it.startsWith("## BMW (UDS): Batería: tensión (sensor) = ") }
            .substringAfter(" = ").substringBefore(" ").toDouble()
        assertTrue("tension $volts", listOf(12.11, 12.23).any { kotlin.math.abs(it - volts) < 0.01 })
        // El OBD normal sigue contestando como aquel dia.
        assertTrue(session.read(Pids.RPM) != null)
    }
}
