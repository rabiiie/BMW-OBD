package com.rabie.bmwobd.scan

import com.rabie.bmwobd.obd.ObdSession
import com.rabie.bmwobd.obd.ObdTransport
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.SimulatedDde
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

private val FRAMES = Regex("[0-9A-F:\\s]+")

/**
 * El adaptador de verdad, con sus dos defectos vistos en el coche.
 *
 * Uno: si a una consulta de cuatro bytes se le añade la cifra de respuestas esperadas, manda la
 * direccion sin su ultimo byte y la centralita contesta por la direccion acabada en 00. Asi, un
 * bloque entero de 256 direcciones dio el mismo valor.
 *
 * Dos: de vez en cuando, a una consulta con lectura rapida le entrega la respuesta que tenia de
 * antes, y la de verdad se queda pendiente: desde ahi cada consulta rapida recibe la de la
 * anterior, hasta que una sin lectura rapida hace que entregue lo pendiente y despues la suya.
 */
private class RealAdapter(private val shiftEvery: Int = 0) : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
    val sent = mutableListOf<String>()
    private var command = ""
    private var previous = ""
    private var held: String? = null
    private var quickReads = 0
    var shifts = 0
        private set

    override suspend fun open() = car.open()
    override fun close() = Unit

    override suspend fun write(text: String) {
        command = text.trim()
        sent += command
        val cut = command.length == 9 && command.startsWith("2C10")
        car.write(if (cut) command.take(6) + "00" else text)
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val real = car.readUntilPrompt(timeoutMs)
        if (command.startsWith("AT")) return real
        val quick = command.length % 2 == 1
        val pending = held
        val delivered = when {
            !quick -> {
                held = null
                if (pending == null) real else pending.trimEnd('\r') + "\r" + real
            }
            pending != null -> {
                held = real
                pending
            }
            shiftEvery > 0 && ++quickReads % shiftEvery == 0 && previous.isNotEmpty() -> {
                shifts++
                held = real
                previous
            }
            else -> real
        }
        // Solo se puede quedar atras una trama del coche, no un mensaje del propio adaptador.
        if (FRAMES.matches(real.trim())) previous = real
        return delivered
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

    /** Lo que da dato en el coche simulado, como consultas. */
    private val simulated = SimulatedDde.ADDRESSES.map(ScanPlan::measure)

    private fun rows(store: ScanStore, key: String = "coche") =
        store.file(key).takeIf { it.exists() }?.readLines().orEmpty().drop(1).map { it.split(',') }

    private suspend fun session(transport: ObdTransport = SimulatedTransport(latencyMs = 0)): ObdSession {
        val session = ObdSession(transport) { log += it }
        session.connect()
        log.clear()
        return session
    }

    /** Un barrido desde cero, sin lo que se da por sabido, para probar la busqueda entera. */
    private fun fromScratch(store: ScanStore, key: String = "coche") {
        store.load(key)
        store.save(key, ScanSaved())
    }

    @Test
    fun `la busqueda pregunta por todas las direcciones y despues por los servicios`() {
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
    fun `tras la busqueda repite solo lo que dio dato`() {
        val plan = ScanPlan(next = ScanPlan.SIZE - 2, found = listOf(0x0547, ScanPlan.MEASURES))
        assertEquals("22F19E", plan.take())
        plan.answered()
        assertEquals("22F19F", plan.take())
        assertEquals(1, plan.pass)
        assertEquals(listOf("2C100547", "1A80", "22F19E"), List(3) { plan.take() })
        assertEquals(3, plan.pass)
        assertEquals(3, plan.total)
        assertEquals("2C100547", plan.take())
        assertEquals(4, plan.pass)
        assertNull(ScanPlan(ScanPlan.SIZE, emptyList(), pass = 3).take())
    }

    @Test
    fun `un avance guardado en el repaso antiguo pasa a repetir`() {
        val plan = ScanPlan(ScanPlan.SIZE, listOf(5, 9), pass = 2, resume = 7)
        assertEquals("2C100005", plan.take())
        assertEquals(3, plan.pass)
    }

    @Test
    fun `el fichero lleva la respuesta y como iba el motor, y no lleva el bastidor`() {
        val store = ScanStore(folder.root)
        store.load("coche")
        val writer = store.writer("coche")
        writer.append(1, "2C100547", intArrayOf(0x39, 0xFE), mapOf(0x0C to 842.0, 0x05 to 48.0), Date(0))
        writer.append(1, "1A90", intArrayOf(0x50, 0x34, 0x34), emptyMap(), Date(0))
        writer.append(1, "1A86", intArrayOf(0x57, 0x42, 0x41), emptyMap(), Date(0))
        writer.close()
        store.writer("coche").close()
        val lines = store.file("coche").readLines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("fecha,pasada,consulta,respuesta,rpm,velocidad,carga,refrigerante"))
        assertTrue(lines[1].endsWith(",1,2C100547,39FE,842.0,,,48.0,,,,,,,"))
        assertTrue(lines[2].contains(",1A90,oculto,"))
        assertTrue(lines[3].contains(",1A86,oculto,"))
    }

    @Test
    fun `una respuesta sin dato no cuenta como medida`() = runBlocking<Unit> {
        // La centralita de verdad contesta a cualquier direccion; a las que no existen, sin dato.
        val session = session()
        assertEquals(0, session.ask("2C107777", "6C10")!!.size)
        assertEquals(2, session.ask("2C100547", "6C10")!!.size)
        val store = ScanStore(folder.root)
        fromScratch(store)
        val runner = ScanRunner(store, "coche")
        repeat(100) { runner.step(3, emptyMap(), session::ask) }
        assertEquals(0, runner.progress().answering)
        assertTrue(rows(store).isEmpty())
    }

    @Test
    fun `ninguna consulta del barrido lleva la cifra de respuestas esperadas`() = runBlocking<Unit> {
        val adapter = RealAdapter()
        val session = session(adapter)
        val store = ScanStore(folder.root)
        val runner = ScanRunner(store, "coche")
        adapter.sent.clear()
        repeat(400) { runner.step(3, emptyMap(), session::ask) }
        assertEquals(1200, adapter.sent.size)
        assertTrue(adapter.sent.all { it.length % 2 == 0 })
    }

    @Test
    fun `con el defecto del adaptador de verdad, pedir con lectura rapida daria un bloque entero falso`() = runBlocking<Unit> {
        // Lo que paso en el coche el 10 de octubre: 0547 pedido con la cifra detras contesto con
        // el valor de 0500, y lo mismo las 256 direcciones del bloque.
        val adapter = RealAdapter()
        val session = session(adapter)
        val injection = session.ask("2C100500", "6C10")!!.toList()
        assertEquals(injection.size, 2)
        adapter.write("2C1005471\r")
        val wrong = adapter.readUntilPrompt(0).trim()
        adapter.write("2C100547\r")
        val right = adapter.readUntilPrompt(0).trim()
        assertTrue(wrong.startsWith("6C10") && right.startsWith("6C10"))
        assertFalse(wrong == right)
    }

    @Test
    fun `un barrido entero con el adaptador de verdad encuentra justo lo que hay y lo repite`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        fromScratch(store)
        val adapter = RealAdapter(shiftEvery = 7)
        val session = session(adapter)
        val known = SimulatedDde.ADDRESSES
        val runner = ScanRunner(store, "coche", known) { log += it }
        val values = mapOf(0x0C to 840.0, 0x05 to 90.0)

        var turns = 0
        while (runner.step(3, values, session::ask)) {
            // Como en la app: entre tanda y tanda va la lectura normal, con lectura rapida.
            assertNotNull(session.read(Pids.RPM, singleFrame = true))
            turns++
        }

        assertTrue("el adaptador simulado no ha desplazado nada", adapter.shifts > 50)
        val progress = runner.progress()
        assertTrue(progress.finished)
        assertEquals(simulated.size + 1, progress.answering)
        assertTrue(turns > ScanPlan.SIZE / 3)

        val rows = rows(store)
        assertEquals(simulated + "1A80", rows.filter { it[1] == "1" }.map { it[2] })
        assertTrue(rows.none { it[1] == "2" })
        assertTrue(rows.none { it[3].isEmpty() })
        // Una fila de la busqueda y una por cada repeticion, siempre con su propio valor.
        val coolant = rows.filter { it[2] == "2C100547" }
        assertEquals(1 + ScanRunner.REPEATS, coolant.size)
        val soot = rows.filter { it[2] == "2C1003EA" }.map { it[3] }.toSet()
        assertEquals(setOf("03A3"), soot)
        assertEquals("840.0", rows.first()[4])
        assertTrue(log.any { it == "## Escaneo: de las ${known.size} medidas ya conocidas han salido ${known.size}" })
        assertEquals("## Escaneo: terminado", log.last())
        assertTrue(log.filter { it.startsWith("##") }.all { it.startsWith("## Escaneo") || it.startsWith("## Desfase") })

        runner.close(keepActive = true)
        assertFalse(store.load("coche").active)
    }

    @Test
    fun `lo que la app ya lee y lo que se da por sabido estan dentro de lo que barre`() {
        val known = Pids.bmwDiesel.map { it.pid - Pids.BMW_BASE }
        assertTrue(known.all { it in ScanPlan.FIRST_ADDRESS..ScanPlan.LAST_ADDRESS })
        assertEquals(569, ScanSeed.FOUND.size)
        assertEquals(ScanSeed.FOUND.sorted(), ScanSeed.FOUND)
        assertTrue(ScanSeed.FOUND.all { it < ScanSeed.NEXT })
        assertEquals("2C100C44", ScanPlan.query(ScanSeed.NEXT))
        // Las medidas de la lectura normal que caen en lo ya barrido salieron en aquel barrido.
        assertTrue(known.filter { it <= ScanSeed.SWEPT_UP_TO }.all { it in ScanSeed.FOUND })
    }

    @Test
    fun `un barrido nuevo arranca de lo que ya se sabe del coche`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val saved = store.load("coche")
        assertFalse(saved.active)
        assertEquals(ScanSeed.NEXT, saved.next)
        assertEquals(569, saved.found.size)

        val runner = ScanRunner(store, "coche")
        assertEquals(569, runner.progress().answering)
        assertEquals(ScanSeed.NEXT, runner.progress().done)
        val asked = mutableListOf<String>()
        runner.step(2, emptyMap()) { query, _ -> asked += query; null }
        assertEquals(listOf("2C100C44", "2C100C45"), asked)
    }

    @Test
    fun `el avance de las versiones anteriores se descarta y su fichero se borra`() {
        // Lo que habia en el movil tras el trayecto del 10 de octubre: un barrido hecho con lectura
        // rapida, con bloques enteros de direcciones falsas.
        val store = ScanStore(folder.root)
        store.file("coche").writeText("fecha,pasada,consulta,respuesta\n2026-10-10 20:02:41,1,2C100401,1666285E\n")
        for (version in listOf("", "version=2\n")) {
            folder.root.resolve("estado_coche.txt").writeText(
                version + "activo=si\nsiguiente=8034\npasada=2\nalcance=si\nrepaso=120\ncontestan=" + (1024..1535).joinToString(","),
            )
            val saved = store.load("coche")
            assertTrue(saved.active)
            assertTrue(saved.reached)
            assertEquals(1, saved.pass)
            assertEquals(ScanSeed.NEXT, saved.next)
            assertEquals(ScanSeed.FOUND, saved.found)
            assertFalse(store.file("coche").exists())
            // La segunda vez ya no hay nada que convertir.
            assertEquals(saved, store.load("coche"))
        }
    }

    @Test
    fun `se corta a medias en cada fase y sigue por donde iba`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        fromScratch(store)
        val session = session()
        var runner = ScanRunner(store, "coche")
        var reopened = 0
        var turns = 0
        while (runner.step(3, emptyMap(), session::ask)) {
            if (++turns % 700 == 0) {
                val before = runner.progress()
                runner.close(keepActive = true)
                assertTrue(store.load("coche").active)
                runner = ScanRunner(store, "coche")
                assertEquals(before.pass, runner.progress().pass)
                if (before.pass == 1) assertEquals(before.done, runner.progress().done)
                reopened++
            }
        }
        assertTrue(reopened >= 3)
        val rows = rows(store)
        assertEquals(simulated + "1A80", rows.filter { it[1] == "1" }.map { it[2] })
        assertEquals((simulated + "1A80").toSet(), rows.map { it[2] }.toSet())
    }

    @Test
    fun `si la app muere sin cerrar se pierden como mucho unas consultas, no el barrido`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        fromScratch(store)
        val session = session()
        val runner = ScanRunner(store, "coche")
        repeat(120) { runner.step(3, emptyMap(), session::ask) }

        val saved = store.load("coche")
        assertTrue(saved.active)
        assertTrue(saved.next in 360 - ScanRunner.SAVE_EVERY..360)
    }

    @Test
    fun `tras un desfase de la lectura normal el barrido y las medidas leen su propio valor`() = runBlocking<Unit> {
        val session = session(RealAdapter(shiftEvery = 2))
        val soot = Pids.bmwDiesel.first { it.id == Pids.BMW_SOOT_MEASURED }
        repeat(50) {
            session.read(Pids.RPM, singleFrame = true)
            session.read(Pids.MAP, singleFrame = true)
            assertEquals(listOf(0x03, 0xA3), session.ask("2C1003EA", "6C10")!!.toList())
            assertEquals(14.2, soot.decodeOrNull(session.read(soot.pid)!!)!!, 0.05)
        }
    }

    @Test
    fun `la prueba de alcance deja el adaptador listo para seguir leyendo`() = runBlocking<Unit> {
        val session = session()
        session.scanReach()
        assertEquals("## Escaneo: empieza el barrido", log.last())
        assertNotNull(session.read(Pids.RPM, singleFrame = true))
        assertEquals(2, session.ask("2C100547", "6C10")!!.size)
    }

    @Test
    fun `si el adaptador no vuelve tras la prueba de alcance se corta la conexion en vez de leer a medias`() = runBlocking<Unit> {
        val session = session(Stuck())
        val failure = runCatching { session.scanReach() }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(failure!!.message!!.contains("Vuelve a conectar"))
    }

    @Test
    fun `sin nada que de dato termina solo`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        fromScratch(store)
        val runner = ScanRunner(store, "coche") { log += it }
        while (runner.step(50, emptyMap()) { _, _ -> IntArray(0) }) Unit
        assertTrue(runner.progress().finished)
        assertEquals("## Escaneo: la centralita no ha dado ningún dato", log.last())
    }

    @Test
    fun `borrar vuelve al punto de partida y recuerda la prueba de alcance`() {
        val store = ScanStore(folder.root)
        store.save("coche", ScanSaved(active = true, next = 5000, pass = 1, found = listOf(1, 2, 3), reached = true))
        store.writer("coche").close()
        store.reset("coche")
        val saved = store.load("coche")
        assertFalse(saved.active)
        assertTrue(saved.reached)
        assertEquals(ScanSeed.NEXT, saved.next)
        assertFalse(store.file("coche").exists())
        assertFalse(store.load("otro").reached)
    }

    @Test
    fun `un fichero de avance estropeado no rompe nada`() {
        val store = ScanStore(folder.root)
        folder.root.resolve("estado_coche.txt").writeText("version=3\nsiguiente=999999\npasada=-4\ncontestan=12,zz,99999999\nbasura")
        val saved = store.load("coche")
        assertEquals(ScanPlan.SIZE, saved.next)
        assertEquals(1, saved.pass)
        assertEquals(listOf(12), saved.found)
    }
}
