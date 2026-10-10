package com.rabie.bmwobd.scan

import com.rabie.bmwobd.obd.ObdSession
import com.rabie.bmwobd.obd.ObdTransport
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.SimulatedTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

/** Las direcciones de medidas que conoce el coche simulado. */
private val SIMULATED = listOf(
    0x01F4, 0x0385, 0x03EA, 0x03EB, 0x03ED, 0x03F3, 0x0424, 0x0432, 0x041B, 0x0500, 0x0547, 0x0641, 0x0BEB, 0x0D16, 0x16B2,
)

/**
 * Un adaptador con el defecto visto en el de verdad: de vez en cuando entrega otra vez la
 * respuesta anterior en lugar de la que toca.
 */
private class Stuttering(private val every: Int) : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    private var last = ""
    private var count = 0
    private var pending = ""

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        pending = text.trim()
        car.write(text)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val real = car.readUntilPrompt(timeoutMs)
        if (!pending.startsWith("2C10")) return real
        val answer = if (++count % every == 0 && last.isNotEmpty()) last else real
        if (!real.startsWith("NO DATA")) last = real
        return answer
    }
}

/** Un adaptador que deja de contestar en cuanto se le cambia el formato de las tramas. */
private class Stuck : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    private var dead = false

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        if (text.trim() == "ATCAF0") dead = true
        car.write(text)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val answer = car.readUntilPrompt(timeoutMs)
        if (dead) throw java.net.SocketTimeoutException("sin respuesta")
        return answer
    }
}

class ScanTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val log = mutableListOf<String>()

    private fun rows(store: ScanStore, key: String = "coche") = store.file(key).readLines().drop(1).map { it.split(',') }

    @Test
    fun `la primera pasada pregunta por todas las direcciones y despues por los servicios`() {
        assertEquals("2C100000", ScanPlan.query(0))
        assertEquals("2C100547", ScanPlan.query(0x0547))
        assertEquals("2C101BFF", ScanPlan.query(ScanPlan.MEASURES - 1))
        assertEquals("1A80", ScanPlan.query(ScanPlan.MEASURES))
        assertEquals("22F19F", ScanPlan.query(ScanPlan.SIZE - 1))
        // Solo servicios de lectura: ninguno que escriba, borre, active o cambie de sesion.
        assertTrue(ScanPlan.SERVICES.all { it.take(2) in setOf("1A", "18", "21", "22") })
        assertEquals(ScanPlan.SIZE, (0 until ScanPlan.SIZE).map(ScanPlan::query).toSet().size)
        assertTrue((0 until ScanPlan.SIZE).all { ScanPlan.query(it).length % 2 == 0 && ScanPlan.query(it).length <= 14 })
    }

    @Test
    fun `la respuesta afirmativa se reconoce por el servicio mas cuarenta`() {
        assertEquals("6C10", ScanPlan.answerPrefix("2C100547"))
        assertEquals("5A80", ScanPlan.answerPrefix("1A80"))
        assertEquals("6105", ScanPlan.answerPrefix("2105"))
        assertEquals("624021", ScanPlan.answerPrefix("224021"))
        assertEquals("58", ScanPlan.answerPrefix("1802FFFF"))
    }

    @Test
    fun `tras la primera pasada repite solo lo que contesto`() {
        val plan = ScanPlan(next = ScanPlan.SIZE - 3, found = listOf(0x0547, ScanPlan.MEASURES))
        assertEquals("22F19D", plan.take())
        plan.answered()
        assertEquals("22F19E", plan.take())
        assertEquals("22F19F", plan.take())
        assertEquals(listOf("2C100547", "1A80", "22F19D"), List(3) { plan.take() })
        assertEquals(2, plan.pass)
        assertEquals("2C100547", plan.take())
        assertEquals(3, plan.pass)
        assertEquals(3, plan.answering)
    }

    @Test
    fun `el fichero lleva la respuesta y como iba el motor`() {
        val store = ScanStore(folder.root)
        val writer = store.writer("coche")
        writer.append(1, "2C100547", intArrayOf(0x39, 0xFE), mapOf(0x0C to 842.0, 0x05 to 48.0), Date(0))
        writer.close()
        store.writer("coche").close()
        val lines = store.file("coche").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("fecha,pasada,consulta,respuesta,rpm,velocidad,carga,refrigerante"))
        assertTrue(lines[1].endsWith(",1,2C100547,39FE,842.0,,,48.0,,,,,,,"))
    }

    @Test
    fun `un barrido entero contra el coche simulado encuentra justo lo que hay y lo repite`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        log.clear()
        val runner = ScanRunner(store, "coche", SIMULATED) { log += it }
        val values = mapOf(0x0C to 840.0, 0x05 to 90.0)

        var turns = 0
        while (runner.step(3, values, session::ask)) turns++

        val progress = runner.progress()
        assertTrue(progress.finished)
        assertEquals(SIMULATED.size + 1, progress.answering)
        assertTrue(turns > ScanPlan.SIZE / 3)

        val rows = rows(store)
        val firstPass = rows.filter { it[1] == "1" }.map { it[2] }
        assertEquals((SIMULATED.sorted().map(ScanPlan::measure) + "1A80"), firstPass)
        // Cada consulta que contesto se repite en todas las pasadas siguientes.
        assertEquals(ScanRunner.MAX_PASSES, rows.count { it[2] == "2C100547" })
        assertEquals("840.0", rows.first()[4])
        assertTrue(log.any { it == "## Escaneo: de las ${SIMULATED.size} medidas ya conocidas han contestado ${SIMULATED.size}" })
        assertEquals("## Escaneo: terminado", log.last())
        // Nada del barrido ensucia el registro salvo sus avisos.
        assertTrue(log.all { it.startsWith("## Escaneo") })

        runner.close(keepActive = true)
        assertFalse(store.load("coche").active)
    }

    @Test
    fun `las medidas que la app ya lee estan dentro de lo que barre`() {
        val known = Pids.bmwDiesel.map { it.pid - Pids.BMW_BASE }
        assertTrue(known.all { it in ScanPlan.FIRST_ADDRESS..ScanPlan.LAST_ADDRESS })
    }

    @Test
    fun `se corta a medias y sigue por donde iba sin repetir ni saltarse nada`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { }
        session.connect()

        val first = ScanRunner(store, "coche")
        repeat(500) { first.step(3, emptyMap(), session::ask) }
        first.close(keepActive = true)
        val saved = store.load("coche")
        assertTrue(saved.active)
        assertEquals(1500, saved.next)

        val second = ScanRunner(store, "coche")
        assertEquals(1500, second.progress().done)
        while (second.progress().pass == 1) second.step(3, emptyMap(), session::ask)
        second.close(keepActive = true)

        val firstPass = rows(store).filter { it[1] == "1" }.map { it[2] }
        assertEquals(SIMULATED.sorted().map(ScanPlan::measure) + "1A80", firstPass)
        assertTrue(store.load("coche").active)
    }

    @Test
    fun `si la app muere sin cerrar se pierden como mucho unas consultas, no el barrido`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { }
        session.connect()
        val runner = ScanRunner(store, "coche")
        repeat(120) { runner.step(3, emptyMap(), session::ask) }

        val saved = store.load("coche")
        assertTrue(saved.active)
        assertTrue(saved.next in 360 - ScanRunner.SAVE_EVERY..360)
    }

    @Test
    fun `una respuesta repetida por el adaptador no se apunta a la direccion equivocada`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val session = ObdSession(Stuttering(every = 7)) { }
        session.connect()
        val runner = ScanRunner(store, "coche")
        while (runner.progress().pass == 1 && runner.progress().done < ScanPlan.MEASURES) {
            runner.step(3, emptyMap(), session::ask)
        }
        runner.close(keepActive = false)

        val found = rows(store).filter { it[1] == "1" && it[2].startsWith(ScanPlan.MEASURE) }.map { it[2] }.toSet()
        val real = SIMULATED.map(ScanPlan::measure).toSet()
        assertTrue("direcciones inventadas: ${found - real}", (found - real).isEmpty())
        assertTrue("se han encontrado ${found.size} de ${real.size}", found.size >= real.size - 3)
    }

    @Test
    fun `la prueba de alcance deja el adaptador listo para seguir leyendo`() = runBlocking<Unit> {
        val session = ObdSession(SimulatedTransport(latencyMs = 0)) { log += it }
        session.connect()
        session.scanReach()
        assertEquals("## Escaneo: empieza el barrido", log.last())
        assertNotNull(session.read(Pids.RPM, singleFrame = true))
        assertNotNull(session.ask("2C100547", "6C10"))
    }

    @Test
    fun `si el adaptador no vuelve tras la prueba de alcance se corta la conexion en vez de leer a medias`() = runBlocking<Unit> {
        val session = ObdSession(Stuck()) { log += it }
        session.connect()
        val failure = runCatching { session.scanReach() }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(failure!!.message!!.contains("Vuelve a conectar"))
    }

    @Test
    fun `sin nada que conteste termina solo`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val runner = ScanRunner(store, "coche") { log += it }
        while (runner.step(50, emptyMap()) { _, _ -> null }) Unit
        assertTrue(runner.progress().finished)
        assertEquals("## Escaneo: la centralita no ha contestado a nada", log.last())
        assertNull(ScanPlan(next = ScanPlan.SIZE).take())
    }

    @Test
    fun `la prueba de alcance se recuerda aunque se borre el barrido`() {
        val store = ScanStore(folder.root)
        assertFalse(store.load("coche").reached)
        store.save("coche", ScanSaved(reached = true))
        assertTrue(store.load("coche").reached)
        assertNotNull(store.load("otro"))
        assertFalse(store.load("otro").reached)
    }

    @Test
    fun `un fichero de avance estropeado no rompe nada`() {
        val store = ScanStore(folder.root)
        folder.root.resolve("estado_coche.txt").writeText("siguiente=999999\npasada=-4\ncontestan=12,zz,99999999\nbasura")
        val saved = store.load("coche")
        assertEquals(ScanPlan.SIZE, saved.next)
        assertEquals(1, saved.pass)
        assertEquals(listOf(12), saved.found)
    }
}
