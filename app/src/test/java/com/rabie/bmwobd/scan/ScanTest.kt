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

/** Las direcciones de medidas que dan dato en el coche simulado. */
private val SIMULATED = com.rabie.bmwobd.obd.SimulatedDde.ADDRESSES

/**
 * Un adaptador con el defecto visto en el de verdad. De vez en cuando, a una consulta pedida con
 * lectura rapida le entrega la respuesta que tenia de antes, y la de verdad se queda pendiente:
 * desde ahi cada consulta rapida recibe la respuesta de la anterior. Una consulta sin lectura
 * rapida lo arregla, porque el adaptador entrega lo pendiente y despues la suya.
 */
private val FRAMES = Regex("[0-9A-F:\\s]+")

private class Shifting(private val every: Int) : ObdTransport {
    private val car = SimulatedTransport(latencyMs = 0)
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
        car.write(text)
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
            ++quickReads % every == 0 && previous.isNotEmpty() -> {
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

    private fun rows(store: ScanStore, key: String = "coche") = store.file(key).readLines().drop(1).map { it.split(',') }

    private fun expected() = SIMULATED.sorted().map(ScanPlan::measure) + "1A80"

    private suspend fun session(transport: ObdTransport = SimulatedTransport(latencyMs = 0)): ObdSession {
        val session = ObdSession(transport) { log += it }
        session.connect()
        log.clear()
        return session
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
    fun `tras la busqueda repasa lo que no dio dato y despues repite solo lo que si`() {
        val plan = ScanPlan(next = ScanPlan.SIZE - 2, found = (0 until ScanPlan.MEASURES).filter { it !in listOf(5, 9) } )
        assertEquals("22F19E", plan.take())
        assertEquals("22F19F", plan.take())
        assertEquals(1, plan.pass)
        // Repaso: solo las dos direcciones que no dieron dato.
        assertEquals("2C100005", plan.take())
        assertEquals(2, plan.pass)
        assertEquals(2, plan.total)
        plan.answered()
        assertEquals("2C100009", plan.take())
        // Repeticion: todo lo que dio dato, incluida la que salio en el repaso.
        assertEquals("2C100000", plan.take())
        assertEquals(3, plan.pass)
        assertEquals(ScanPlan.MEASURES - 1, plan.total)
        repeat(4) { plan.take() }
        assertEquals("2C100005", plan.take())
    }

    @Test
    fun `el repaso se retoma donde se quedo`() {
        val found = (0 until ScanPlan.MEASURES).filter { it % 100 != 0 }
        val plan = ScanPlan(next = ScanPlan.SIZE, found = found)
        assertEquals("2C100000", plan.take())
        assertEquals("2C100064", plan.take())
        assertEquals(0x64, plan.asked)
        val resumed = ScanPlan(ScanPlan.SIZE, found, pass = 2, resume = plan.asked)
        assertEquals(2, resumed.done)
        assertEquals("2C1000C8", resumed.take())
        assertEquals(plan.total, resumed.total)
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
    fun `una respuesta sin dato no cuenta como medida`() = runBlocking<Unit> {
        // La centralita de verdad contesta a cualquier direccion; a las que no existen, sin dato.
        val session = session()
        assertEquals(0, session.ask("2C107777", "6C10")!!.size)
        assertEquals(2, session.ask("2C100547", "6C10")!!.size)
        val store = ScanStore(folder.root)
        val runner = ScanRunner(store, "coche")
        repeat(100) { runner.step(3, emptyMap(), session::ask) }
        assertEquals(0, runner.progress().answering)
        assertTrue(rows(store).isEmpty())
    }

    @Test
    fun `las medidas se piden con lectura rapida y los servicios no`() = runBlocking<Unit> {
        val sent = mutableListOf<String>()
        val transport = object : ObdTransport {
            val car = SimulatedTransport(latencyMs = 0)
            override suspend fun open() = car.open()
            override fun close() = Unit
            override suspend fun write(text: String) {
                sent += text.trim()
                car.write(text)
            }
            override suspend fun readUntilPrompt(timeoutMs: Long) = car.readUntilPrompt(timeoutMs)
        }
        val session = session(transport)
        sent.clear()
        assertNotNull(session.ask("2C100547", "6C10"))
        assertNotNull(session.ask("1A80", "5A80"))
        assertEquals(listOf("2C1005471", "1A80"), sent)
    }

    @Test
    fun `un barrido entero contra el coche simulado encuentra justo lo que hay y lo repite`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val session = session()
        val runner = ScanRunner(store, "coche", SIMULATED) { log += it }
        val values = mapOf(0x0C to 840.0, 0x05 to 90.0)

        var turns = 0
        while (runner.step(3, values, session::ask)) turns++

        val progress = runner.progress()
        assertTrue(progress.finished)
        assertEquals(SIMULATED.size + 1, progress.answering)
        assertTrue(turns > ScanPlan.SIZE / 3)

        val rows = rows(store)
        assertEquals(expected(), rows.filter { it[1] == "1" }.map { it[2] })
        assertTrue(rows.none { it[1] == "2" })
        assertTrue(rows.none { it[3].isEmpty() })
        // Una fila de la busqueda y una por cada repeticion.
        assertEquals(1 + ScanRunner.MAX_PASSES - ScanPlan.REVIEW, rows.count { it[2] == "2C100547" })
        assertEquals("840.0", rows.first()[4])
        assertTrue(log.any { it == "## Escaneo: de las ${SIMULATED.size} medidas ya conocidas han salido ${SIMULATED.size}" })
        assertEquals("## Escaneo: terminado", log.last())
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
    fun `se corta a medias en cada fase y sigue por donde iba`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val session = session()
        var runner = ScanRunner(store, "coche")
        var reopened = 0
        var turns = 0
        // Se cierra y se vuelve a abrir cada 700 tandas, que cae en la busqueda, en el repaso y repitiendo.
        while (runner.step(3, emptyMap(), session::ask)) {
            if (++turns % 700 == 0) {
                val before = runner.progress()
                runner.close(keepActive = true)
                assertTrue(store.load("coche").active)
                runner = ScanRunner(store, "coche")
                assertEquals(before.pass, runner.progress().pass)
                if (before.pass <= ScanPlan.REVIEW) assertEquals(before.done, runner.progress().done)
                reopened++
            }
        }
        assertTrue(reopened >= 5)
        val rows = rows(store)
        assertEquals(expected(), rows.filter { it[1] == "1" }.map { it[2] })
        assertEquals(expected().toSet(), rows.map { it[2] }.toSet())
    }

    @Test
    fun `si la app muere sin cerrar se pierden como mucho unas consultas, no el barrido`() = runBlocking<Unit> {
        val store = ScanStore(folder.root)
        val session = session()
        val runner = ScanRunner(store, "coche")
        repeat(120) { runner.step(3, emptyMap(), session::ask) }

        val saved = store.load("coche")
        assertTrue(saved.active)
        assertTrue(saved.next in 360 - ScanRunner.SAVE_EVERY..360)
    }

    @Test
    fun `con un adaptador que desplaza respuestas no se inventa ninguna direccion ni se pierde ninguna`() = runBlocking<Unit> {
        for (every in listOf(3, 5, 11, 37)) {
            val dir = folder.newFolder("cada$every")
            val store = ScanStore(dir)
            val transport = Shifting(every)
            val session = session(transport)
            val runner = ScanRunner(store, "coche")
            // Como en la app: tras cada tanda del barrido va una lectura normal con lectura rapida.
            while (!runner.repeating && runner.step(3, emptyMap(), session::ask)) {
                assertNotNull(session.read(Pids.RPM, singleFrame = true))
            }
            runner.close(keepActive = false)

            assertTrue("el adaptador simulado no ha desplazado nada", transport.shifts > 50)
            val found = rows(store).map { it[2] }.toSet()
            assertEquals("cada $every consultas", expected().toSet(), found)
        }
    }

    @Test
    fun `tras un desfase del barrido una medida normal no se queda con la respuesta pendiente`() = runBlocking<Unit> {
        val session = session(Shifting(every = 2))
        val soot = Pids.bmwDiesel.first { it.id == Pids.BMW_SOOT_MEASURED }
        repeat(50) {
            session.ask("2C100547", "6C10")
            session.ask("2C100500", "6C10")
            val value = soot.decodeOrNull(session.read(soot.pid)!!)!!
            assertEquals(14.2, value, 0.05)
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
        val runner = ScanRunner(store, "coche") { log += it }
        while (runner.step(50, emptyMap()) { _, _ -> IntArray(0) }) Unit
        assertTrue(runner.progress().finished)
        assertEquals("## Escaneo: la centralita no ha dado ningún dato", log.last())
        assertNull(ScanPlan(ScanPlan.SIZE, emptyList(), pass = 3).take())
    }

    @Test
    fun `la prueba de alcance se recuerda aunque se borre el barrido`() {
        val store = ScanStore(folder.root)
        assertFalse(store.load("coche").reached)
        store.save("coche", ScanSaved(reached = true))
        assertTrue(store.load("coche").reached)
        assertFalse(store.load("otro").reached)
    }

    @Test
    fun `un fichero de avance estropeado no rompe nada`() {
        val store = ScanStore(folder.root)
        folder.root.resolve("estado_coche.txt").writeText("version=2\nsiguiente=999999\npasada=-4\ncontestan=12,zz,99999999\nbasura")
        val saved = store.load("coche")
        assertEquals(ScanPlan.SIZE, saved.next)
        assertEquals(1, saved.pass)
        assertEquals(listOf(12), saved.found)
    }

    @Test
    fun `el primer barrido hecho en el coche se aprovecha y se limpia de respuestas sin dato`() = runBlocking<Unit> {
        // El fichero real del 10 de octubre: 3.109 filas hasta la direccion 0C43, de las que 2.540
        // eran respuestas sin dato que aquella version daba por buenas.
        val store = ScanStore(folder.root)
        val real = checkNotNull(javaClass.classLoader?.getResourceAsStream("escaneo_real_primera_version.csv")).bufferedReader().readText()
        store.file("coche").writeText(real)
        val oldFound = real.lines().drop(1).filter { it.isNotBlank() }.map { ScanPlan.FIRST_ADDRESS + it.split(',')[2].substring(4).toInt(16) }
        folder.root.resolve("estado_coche.txt").writeText(
            "activo=si\nsiguiente=3000\npasada=1\nalcance=si\ncontestan=" + oldFound.joinToString(","),
        )

        val saved = store.load("coche")
        assertTrue(saved.active)
        assertTrue(saved.reached)
        assertEquals(3000, saved.next)
        assertEquals(1, saved.pass)
        assertEquals(569, saved.found.size)
        assertTrue(0x0547 in saved.found && 0x0500 in saved.found && 0x03EA in saved.found)
        assertFalse(0x0002 in saved.found)
        val rows = rows(store)
        assertEquals(569, rows.size)
        assertTrue(rows.none { it[3].isEmpty() })
        // La segunda vez ya no hay nada que convertir.
        assertEquals(saved, store.load("coche"))

        // Y el barrido sigue desde ahi sin tropezar.
        val runner = ScanRunner(store, "coche")
        assertEquals(569, runner.progress().answering)
        assertEquals(3000, runner.progress().done)
        val session = session()
        repeat(50) { runner.step(3, emptyMap(), session::ask) }
        assertEquals(3150, runner.progress().done)
    }
}
