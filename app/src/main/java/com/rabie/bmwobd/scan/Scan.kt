package com.rabie.bmwobd.scan

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeSet

/**
 * El barrido de todo lo que la centralita del motor de BMW contesta por el adaptador. Tiene tres
 * fases, que aqui se llaman pasadas:
 *
 * 1. Pregunta por todas las direcciones de medidas y por las demas consultas de lectura conocidas.
 * 2. Repasa las direcciones de medidas que no dieron dato, por si alguna se perdio en un desfase.
 * 3. Y siguientes: repite una y otra vez solo las que dieron dato, para ver como cambian.
 *
 * Las consultas se numeran por su posicion en la primera pasada. [resume] es la ultima consulta
 * hecha en el repaso, para retomarlo sin empezar de nuevo.
 */
class ScanPlan(next: Int = 0, found: Collection<Int> = emptyList(), pass: Int = 1, resume: Int = -1) {

    var next: Int = next
        private set
    var pass: Int = pass
        private set
    var asked: Int = if (pass == REVIEW) resume else -1
        private set

    private val found = TreeSet(found)
    private var order: List<Int> = when {
        pass == REVIEW -> missing().filter { it > resume }
        pass > REVIEW -> this.found.toList()
        else -> emptyList()
    }
    private var cursor = 0
    private val reviewed: Int = if (pass == REVIEW) missing().size - order.size else 0

    val answering: Int get() = found.size
    val total: Int get() = if (pass == 1) SIZE else order.size + reviewed
    val done: Int get() = if (pass == 1) next else cursor + reviewed
    fun found(): List<Int> = found.toList()

    private fun missing(): List<Int> = (0 until MEASURES).filter { it !in found }

    /** La siguiente consulta, o null si ya no queda nada que preguntar. */
    fun take(): String? {
        if (pass == 1) {
            if (next < SIZE) {
                asked = next++
                return query(asked)
            }
            pass = REVIEW
            order = missing()
            cursor = 0
        }
        if (pass == REVIEW && cursor >= order.size) {
            pass = REVIEW + 1
            order = found.toList()
            cursor = 0
        }
        if (order.isEmpty()) return null
        if (cursor >= order.size) {
            pass++
            cursor = 0
        }
        asked = order[cursor++]
        return query(asked)
    }

    /** La ultima consulta entregada ha dado un dato. */
    fun answered() {
        if (asked >= 0) found += asked
    }

    companion object {
        const val FIRST_ADDRESS = 0x0000
        const val LAST_ADDRESS = 0x1BFF
        const val MEASURES = LAST_ADDRESS - FIRST_ADDRESS + 1
        const val MEASURE = "2C10"

        /** La pasada que repasa lo que no dio dato; a partir de la siguiente se repite lo encontrado. */
        const val REVIEW = 2

        /**
         * Otras consultas de lectura del protocolo de la centralita: identificacion (1A), bloques
         * de datos por identificador corto (21) y largo (22), y averias por estado (18). Ninguna
         * escribe, borra ni activa nada.
         */
        val SERVICES: List<String> =
            (0x80..0x9F).map { "1A%02X".format(it) } +
                listOf("1802FFFF", "1800FFFF") +
                (0x00..0xFF).map { "21%02X".format(it) } +
                (0x00..0xFF).map { "2240%02X".format(it) } +
                (0x00..0xFF).map { "2225%02X".format(it) } +
                (0x80..0x9F).map { "22F1%02X".format(it) }

        val SIZE: Int = MEASURES + SERVICES.size

        /** Primero las medidas, que son lo que mas interesa, y despues las demas consultas. */
        fun query(index: Int): String =
            if (index < MEASURES) measure(FIRST_ADDRESS + index) else SERVICES[index - MEASURES]

        fun measure(address: Int): String = MEASURE + "%04X".format(address)

        /** Como empieza la respuesta afirmativa a [query]: el servicio mas 0x40 y lo que repite. */
        fun answerPrefix(query: String): String {
            val service = query.take(2)
            val positive = "%02X".format(service.toInt(16) + 0x40)
            return positive + when (service) {
                "2C" -> "10"
                "21", "1A" -> query.substring(2, 4)
                "22" -> query.substring(2, 6)
                else -> ""
            }
        }
    }
}

/**
 * Lo guardado de un barrido. [reached] dice que ya se hizo la prueba de que centralitas contestan,
 * que cambia la configuracion del adaptador y no se repite.
 */
data class ScanSaved(
    val active: Boolean = false,
    val next: Int = 0,
    val pass: Int = 1,
    val found: List<Int> = emptyList(),
    val reached: Boolean = false,
    val resume: Int = -1,
)

data class ScanProgress(
    val active: Boolean = false,
    val pass: Int = 1,
    val done: Int = 0,
    val total: Int = 0,
    val answering: Int = 0,
    val finished: Boolean = false,
)

/** Donde se guardan el resultado del barrido de cada coche y por donde va. */
class ScanStore(private val dir: File) {

    fun file(vehicleKey: String) = File(dir, "escaneo_$vehicleKey.csv")

    private fun stateFile(vehicleKey: String) = File(dir, "estado_$vehicleKey.txt")

    fun load(vehicleKey: String): ScanSaved {
        val lines = runCatching { stateFile(vehicleKey).readLines() }.getOrDefault(emptyList())
        val values = lines.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val saved = ScanSaved(
            active = values["activo"] == YES,
            next = values["siguiente"]?.toIntOrNull()?.coerceIn(0, ScanPlan.SIZE) ?: 0,
            pass = values["pasada"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            found = values["contestan"]?.split(',')?.mapNotNull { it.toIntOrNull() }
                ?.filter { it in 0 until ScanPlan.SIZE }.orEmpty(),
            reached = values["alcance"] == YES,
            resume = values["repaso"]?.toIntOrNull() ?: -1,
        )
        if (values["version"] == VERSION || lines.isEmpty()) return saved
        return upgrade(vehicleKey, saved)
    }

    /**
     * El primer barrido daba por buena una respuesta sin dato, y la centralita contesta asi a
     * cualquier direccion que no existe. Lo que vale de aquel avance son las consultas cuya fila
     * del resultado lleva dato; el punto por donde iba se conserva, y el fichero se limpia.
     */
    private fun upgrade(vehicleKey: String, old: ScanSaved): ScanSaved {
        val index = (0 until ScanPlan.SIZE).associateBy { ScanPlan.query(it) }
        val file = file(vehicleKey)
        val rows = runCatching { file.readLines() }.getOrDefault(emptyList())
        val kept = rows.filterIndexed { i, row -> i == 0 || row.split(',').getOrNull(ANSWER_COLUMN).orEmpty().isNotEmpty() }
        val found = kept.drop(1).mapNotNull { index[it.split(',').getOrNull(QUERY_COLUMN)] }.distinct().sorted()
        if (rows.isNotEmpty()) file.writeText(kept.joinToString("\n") + "\n")
        val saved = old.copy(pass = 1, found = found, resume = -1)
        save(vehicleKey, saved)
        return saved
    }

    fun save(vehicleKey: String, saved: ScanSaved) {
        dir.mkdirs()
        // Se escribe aparte y se cambia de nombre: si la app muere a medias queda el fichero anterior.
        val target = stateFile(vehicleKey)
        val draft = File(dir, target.name + ".tmp")
        draft.writeText(
            listOf(
                "activo=" + if (saved.active) YES else NO,
                "siguiente=${saved.next}",
                "pasada=${saved.pass}",
                "alcance=" + if (saved.reached) YES else NO,
                "repaso=${saved.resume}",
                "version=$VERSION",
                "contestan=" + saved.found.joinToString(","),
            ).joinToString("\n"),
        )
        if (!draft.renameTo(target)) {
            target.delete()
            draft.renameTo(target)
        }
    }

    /** Borra el resultado y el punto de avance, para empezar el barrido de cero. */
    fun reset(vehicleKey: String) {
        file(vehicleKey).delete()
        stateFile(vehicleKey).delete()
    }

    fun writer(vehicleKey: String): ScanWriter {
        dir.mkdirs()
        return ScanWriter(file(vehicleKey))
    }

    private companion object {
        const val YES = "si"
        const val NO = "no"
        const val VERSION = "2"
        const val QUERY_COLUMN = 2
        const val ANSWER_COLUMN = 3
    }
}

/**
 * Una fila por respuesta: cuando, en que pasada, la consulta, lo que contesto y como iba el motor
 * en ese momento. Con eso se puede deducir despues que mide cada direccion.
 */
class ScanWriter(file: File) {

    private val isNew = !file.exists() || file.length() == 0L
    private val writer = BufferedWriter(FileWriter(file, true))
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    init {
        if (isNew) {
            writer.write("fecha,pasada,consulta,respuesta," + CONTEXT.joinToString(",") { it.second })
            writer.newLine()
        }
    }

    fun append(pass: Int, query: String, answer: IntArray, values: Map<Int, Double>, now: Date = Date()) {
        val line = StringBuilder()
            .append(clock.format(now)).append(',').append(pass).append(',').append(query).append(',')
            .append(answer.joinToString("") { "%02X".format(it) })
        for ((id, _) in CONTEXT) {
            line.append(',')
            values[id]?.let { line.append(String.format(Locale.US, "%.1f", it)) }
        }
        writer.write(line.toString())
        writer.newLine()
    }

    /** Lleva al disco lo escrito, para que el fichero se pueda enviar con el barrido en marcha. */
    fun flush() {
        runCatching { writer.flush() }
    }

    fun close() {
        runCatching { writer.close() }
    }

    companion object {
        // Medidas del OBD estandar que acompañan a cada respuesta.
        val CONTEXT = listOf(
            0x0C to "rpm", 0x0D to "velocidad", 0x04 to "carga", 0x05 to "refrigerante", 0x49 to "pedal",
            0x0B to "admision_kpa", 0x23 to "rail_bar", 0x10 to "aire_gs", 0x2C to "egr", 0x3C to "escape",
            0x42 to "tension",
        )
    }
}

/**
 * Un barrido en marcha: reparte las consultas en tandas cortas para intercalarlas con la lectura
 * normal, apunta lo que da dato y guarda el avance para seguir en la conexion siguiente.
 *
 * La centralita contesta a cualquier direccion de medida, exista o no: a las que no existen, con
 * una respuesta sin dato. Solo cuenta la que trae dato. Y como la respuesta no dice de que
 * direccion es y el adaptador a veces entrega una con retraso, al buscar cada medida se pregunta
 * dos veces y vale la segunda. Lo que aun asi se pierda lo recoge el repaso.
 */
class ScanRunner(
    private val store: ScanStore,
    val vehicleKey: String,
    private val knownAddresses: List<Int> = emptyList(),
    private val log: (String) -> Unit = {},
) {
    private val saved = store.load(vehicleKey)
    private val plan = ScanPlan(saved.next, saved.found, saved.pass, saved.resume)
    private val writer = store.writer(vehicleKey)
    private var sinceSave = 0
    private var checked = saved.pass > ScanPlan.REVIEW

    var finished = saved.pass > MAX_PASSES
        private set

    init {
        persist(active = !finished)
    }

    fun progress() = ScanProgress(!finished, plan.pass, plan.done, plan.total, plan.answering, finished)

    /** Si ya solo repite lo encontrado: entonces cada consulta contesta y se pueden hacer mas por tanda. */
    val repeating: Boolean get() = plan.pass > ScanPlan.REVIEW

    /**
     * Hace hasta [count] consultas con [ask], que devuelve los bytes de la respuesta o null.
     * [values] son las lecturas normales de este momento. Devuelve false cuando ya no queda nada
     * que hacer.
     */
    suspend fun step(count: Int, values: Map<Int, Double>, ask: suspend (query: String, prefix: String) -> IntArray?): Boolean {
        if (finished) return false
        repeat(count) {
            val query = plan.take()
            if (repeating && !checked) selfCheck()
            if (query == null || plan.pass > MAX_PASSES) {
                finished = true
                writer.flush()
                log(if (plan.answering == 0) "## Escaneo: la centralita no ha dado ningún dato" else "## Escaneo: terminado")
                persist(active = false)
                return false
            }
            val prefix = ScanPlan.answerPrefix(query)
            val first = ask(query, prefix)
            // Buscando, cada medida se pregunta dos veces y vale la segunda respuesta: si el
            // adaptador viene con una respuesta de retraso, la primera es de la consulta anterior
            // y la segunda es la de esta. Sin retraso las dos son la de esta.
            val answer = (if (!repeating && query.startsWith(ScanPlan.MEASURE)) ask(query, prefix) else first)
                ?.takeIf { it.isNotEmpty() } ?: return@repeat
            plan.answered()
            writer.append(plan.pass, query, answer, values)
        }
        writer.flush()
        sinceSave += count
        if (sinceSave >= SAVE_EVERY) persist(active = true)
        return true
    }

    /**
     * Al acabar de buscar, las medidas que la app ya leia tienen que estar entre las encontradas.
     * Si falta alguna, el barrido se ha dejado direcciones y hay que saberlo.
     */
    private fun selfCheck() {
        checked = true
        val found = plan.found().toSet()
        val missing = knownAddresses.filter { (it - ScanPlan.FIRST_ADDRESS) !in found }
        log("## Escaneo: búsqueda completa, dan dato ${plan.answering} consultas")
        if (knownAddresses.isNotEmpty()) {
            log(
                "## Escaneo: de las ${knownAddresses.size} medidas ya conocidas han salido ${knownAddresses.size - missing.size}" +
                    if (missing.isEmpty()) "" else "; faltan " + missing.joinToString(" ") { "%04X".format(it) },
            )
        }
        persist(active = true)
    }

    fun close(keepActive: Boolean) {
        writer.close()
        persist(active = keepActive && !finished)
    }

    private fun persist(active: Boolean) {
        sinceSave = 0
        store.save(vehicleKey, ScanSaved(active, plan.next, plan.pass, plan.found(), saved.reached, plan.asked))
    }

    companion object {
        const val SAVE_EVERY = 150

        // Busqueda, repaso y quince repeticiones de cada consulta con dato.
        const val MAX_PASSES = ScanPlan.REVIEW + 15
    }
}
