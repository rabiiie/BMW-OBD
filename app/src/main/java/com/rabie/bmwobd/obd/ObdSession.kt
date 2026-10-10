package com.rabie.bmwobd.obd

import java.io.IOException
import java.net.SocketTimeoutException

data class SessionInfo(
    val summary: String,
    val supportedPids: Set<Int>,
    val vin: String?,
    val diesel: Boolean,
)

/** Una conexion con el coche: abre el canal, configura el adaptador, descubre los PIDs y los lee. */
class ObdSession(
    private val transport: ObdTransport,
    private val log: (String) -> Unit,
) {
    private val elm = Elm327(transport, log)
    private var timeouts = 0
    private var quick = false

    // La centralita solo contesta a las consultas de BMW si el adaptador espera un tiempo fijo.
    private var fixedWait = false

    // PIDs cuya respuesta no cabe en una trama: con lectura rapida dejarian tramas pendientes.
    private val longAnswers = mutableSetOf<Int>()

    suspend fun connect(): SessionInfo {
        transport.open()
        val version = elm.initialize()
        val supported = scanSupported()
        if (supported.isEmpty()) {
            throw IOException("El coche no responde. ¿Contacto puesto o motor en marcha?")
        }
        logSupport(supported)
        quick = detectQuick(supported)
        val protocol = elm.send("ATDP").lines().lastOrNull().orEmpty()
        val vin = text("0902", "4902")?.takeIf { it.length == VIN_LENGTH }
        return SessionInfo("$version · $protocol", supported, vin, isDiesel(supported))
    }

    /**
     * El coche puede decir su combustible (PID 51: 1 gasolina, 4 diesel). Si no lo dice, se
     * deduce: las correcciones de mezcla (PID 06) solo las da un motor de gasolina.
     */
    private suspend fun isDiesel(supported: Set<Int>): Boolean {
        val declared = if (FUEL_TYPE in supported) read(FUEL_TYPE)?.firstOrNull() else null
        return when (declared) {
            FUEL_DIESEL -> true
            FUEL_GASOLINE -> false
            else -> SHORT_FUEL_TRIM !in supported
        }
    }

    /**
     * Bytes de datos de un PID del modo 01, o null si el coche no contesta en este momento. Un
     * silencio suelto se tolera; varios seguidos significan que el adaptador se ha ido.
     */
    suspend fun read(pid: Int, singleFrame: Boolean = false): IntArray? {
        val quickly = quick && singleFrame && pid !in longAnswers && !Pids.isBmw(pid)
        val response = request(pid, quickly) ?: return null
        val data = dataOf(response, pid)
        if (!quickly) return data

        // Con la lectura rapida el adaptador entrega una sola trama. Si la respuesta era larga, o
        // llega la de otra pregunta, quedan tramas pendientes y todo lo siguiente sale desplazado.
        // Releer sin lectura rapida las vacia: el adaptador espera y entrega todo lo que tenga.
        val long = data != null && ObdParser.isMultiFrame(response)
        if (long) {
            longAnswers += pid
            log("## %02X contesta con un mensaje largo: se leerá sin lectura rápida".format(pid))
        }
        if (!long && (data != null || !ObdParser.isForeignAnswer(response))) return data
        if (!long) log("## Desfase preguntando %02X: se relee sin lectura rápida".format(pid))
        val again = request(pid, quickly = false) ?: return data
        return dataOf(again, pid) ?: data
    }

    /**
     * Primera lectura de una medida, sin lectura rapida. De paso apunta las que contestan con un
     * mensaje largo, que no se pueden pedir con lectura rapida.
     */
    suspend fun readFirst(pid: Int): IntArray? {
        val response = request(pid, quickly = false) ?: return null
        val data = dataOf(response, pid)
        if (data != null && ObdParser.isMultiFrame(response) && longAnswers.add(pid)) {
            log("## %02X contesta con un mensaje largo: se leerá sin lectura rápida".format(pid))
        }
        return data
    }

    /**
     * Los bytes del valor en la respuesta. Una medida de BMW contesta 6C 10 y el valor, sin
     * repetir su direccion: por eso se pide siempre sin lectura rapida, que es donde hay desfases.
     */
    private fun dataOf(response: String, pid: Int): IntArray? =
        if (Pids.isBmw(pid)) {
            // Si el adaptador traia pendiente la respuesta de una consulta anterior, la de esta es la ultima.
            ObdParser.payloads(response, BMW_ANSWER).lastOrNull { it.isNotEmpty() }
        } else {
            ObdParser.dataBytes(response, pid)
        }

    private fun queryOf(pid: Int): String =
        if (Pids.isBmw(pid)) BMW_QUERY + "%04X".format(pid - Pids.BMW_BASE) else "01%02X".format(pid)

    /**
     * Las medidas propias de BMW que contesta esta centralita, de entre [candidates]. Primero se
     * comprueba que atiende ese protocolo, con una medida que tambien sale por OBD. Las que no
     * contestan se descartan.
     */
    suspend fun discoverBmw(candidates: List<PidDef>): List<PidDef> {
        log("## BMW: se piden las medidas propias de la centralita del motor")
        attempt(BMW_IDENT)
        if (!bmwAnswers()) return emptyList()
        val found = mutableListOf<PidDef>()
        for (def in candidates) {
            val value = readFirst(def.pid)?.let(def::decodeOrNull) ?: continue
            log("## BMW: %s = %.2f %s".format(java.util.Locale.US, def.name, value, def.unit))
            found += def
        }
        log("## BMW: contestan ${found.size} de ${candidates.size} medidas")
        return found
    }

    /**
     * Si la centralita atiende las consultas de BMW. Si calla con la espera adaptativa del
     * adaptador se prueba con una espera fija mas larga, que se queda puesta. Si tampoco, se
     * repite con la configuracion del sondeo que funciono, solo para dejarlo en el registro.
     */
    private suspend fun bmwAnswers(): Boolean {
        if (readFirst(Pids.BMW_CHECK) != null) return true
        log("## BMW: sin respuesta con la espera adaptativa; se prueba con espera fija")
        fixedWait = true
        applyWait()
        if (readFirst(Pids.BMW_CHECK) != null) return true
        fixedWait = false
        log("## BMW: tampoco; se prueba la configuración del sondeo")
        for (command in BMW_PROBE_SETUP) attempt(command)
        val works = readFirst(Pids.BMW_CHECK) != null
        attempt("010C")
        restore()
        log("## BMW: " + if (works) "contesta solo con la configuración del sondeo; no se usa todavía" else "no contesta")
        return false
    }

    private suspend fun applyWait() {
        if (fixedWait) for (command in FIXED_WAIT) attempt(command)
    }

    /** Reinicia el adaptador y lo deja como lo espera la lectura normal. */
    private suspend fun restore() {
        elm.initialize()
        attempt("0100", FIRST_QUERY_TIMEOUT_MS)
        applyWait()
    }

    /** Envia la consulta de una medida. Devuelve null si el adaptador no contesta a tiempo. */
    private suspend fun request(pid: Int, quickly: Boolean): String? {
        val response = try {
            elm.send(queryOf(pid) + if (quickly) QUICK_SUFFIX else "", READ_TIMEOUT_MS)
        } catch (e: SocketTimeoutException) {
            if (++timeouts > MAX_TIMEOUTS) throw IOException("El adaptador ha dejado de responder.")
            return null
        }
        timeouts = 0
        return response
    }

    /**
     * Un 1 al final del comando le dice al adaptador que solo espere una respuesta, y contesta en
     * cuanto llega en vez de agotar su temporizador. No todos los clones lo entienden: se prueba
     * una vez con las revoluciones y solo se usa si la respuesta sale bien.
     */
    private suspend fun detectQuick(supported: Set<Int>): Boolean {
        if (Pids.RPM !in supported) return false
        val works = try {
            ObdParser.dataBytes(elm.send("01%02X".format(Pids.RPM) + QUICK_SUFFIX, READ_TIMEOUT_MS), Pids.RPM) != null
        } catch (e: SocketTimeoutException) {
            false
        }
        log("## Lectura rápida: " + if (works) "sí" else "no la admite el adaptador")
        return works
    }

    /** Tension en el conector OBD medida por el adaptador. Contesta aunque el coche este apagado. */
    suspend fun adapterVoltage(): Double? =
        elm.send("ATRV").lines().lastOrNull()?.removeSuffix("V")?.trim()?.toDoubleOrNull()

    /** Comando escrito a mano desde la consola. */
    suspend fun raw(command: String): String = elm.send(command, READ_TIMEOUT_MS)

    /**
     * Sondeo para la visita al coche: deja en el registro lo que contesta la centralita del motor
     * de BMW por su protocolo propio. Antes lee por OBD las medidas que luego sirven para comparar.
     * Al acabar reinicia el adaptador y lo deja como lo espera la lectura normal.
     */
    suspend fun probe() {
        log("## Sondeo: inicio")
        for (command in PROBE_PLAIN) attempt(command)
        try {
            BmwProbe(elm, log).run()
        } finally {
            restore()
        }
        log("## Sondeo: fin")
    }

    /**
     * Una consulta del barrido, sin rastro en el registro. Devuelve los bytes que siguen a
     * [answerPrefix] en la respuesta, o null si la centralita calla o contesta otra cosa.
     */
    suspend fun ask(query: String, answerPrefix: String): IntArray? {
        // La centralita contesta a toda direccion de medida con una sola trama, asi que se puede
        // pedir con lectura rapida: el adaptador vuelve en cuanto llega en vez de agotar su espera.
        val quickly = quick && answerPrefix == BMW_ANSWER
        val response = scanSend(query + if (quickly) QUICK_SUFFIX else "") ?: return null
        val payload = ObdParser.payloads(response, answerPrefix).lastOrNull()
        if (payload != null || !quickly || !ObdParser.isForeignAnswer(response)) return payload
        // Ha llegado la respuesta de otra pregunta: sin lectura rapida el adaptador entrega lo pendiente.
        val again = scanSend(query) ?: return null
        return ObdParser.payloads(again, answerPrefix).lastOrNull()
    }

    /**
     * Un silencio aqui no cuenta para dar el adaptador por perdido: son consultas a ciegas, y si
     * de verdad se ha ido lo notara la lectura normal.
     */
    private suspend fun scanSend(command: String): String? = try {
        elm.send(command, SCAN_TIMEOUT_MS, quiet = true)
    } catch (e: SocketTimeoutException) {
        null
    }

    /**
     * Antes de un barrido nuevo: que centralitas contestan por la direccion normal, con las
     * cabeceras a la vista, y un intento de hablar con otras mandando las tramas tal cual, sin la
     * cifra de respuestas esperadas que el adaptador no entendio en el sondeo. Todo queda en el
     * registro. Al acabar reinicia el adaptador.
     */
    suspend fun scanReach() {
        log("## Escaneo: quién contesta por la dirección normal")
        try {
            for (command in listOf("ATH1", "0100", BMW_IDENT)) attempt(command)
            log("## Escaneo: tramas crudas a motor, cuadro, llave y frenos")
            for (command in RAW_SETUP) attempt(command)
            for (target in RAW_TARGETS) attempt("%02X021A8000000000".format(target), RAW_TIMEOUT_MS)
        } finally {
            restoreOrFail()
        }
        log("## Escaneo: empieza el barrido")
    }

    /**
     * Deja el adaptador como lo espera la lectura normal y comprueba que el coche vuelve a
     * contestar. Si no lo consigue en dos intentos corta la conexion: es mejor reconectar, que
     * reinicia el adaptador desde cero, que seguir leyendo con el a medio configurar.
     */
    private suspend fun restoreOrFail() {
        repeat(2) {
            try {
                restore()
                if (readFirst(Pids.RPM) != null) return
            } catch (e: SocketTimeoutException) {
                log("!! El adaptador no contesta al reiniciarlo")
            }
        }
        throw IOException("El adaptador no ha vuelto a la lectura normal. Vuelve a conectar.")
    }

    private suspend fun attempt(command: String, timeoutMs: Long = READ_TIMEOUT_MS) {
        try {
            elm.send(command, timeoutMs)
        } catch (e: SocketTimeoutException) {
            log("!! Sin respuesta a $command")
        }
    }

    suspend fun diagnostics(): DiagnosticsReport {
        val status = ObdParser.payloads(elm.send("0101", READ_TIMEOUT_MS), "4101").firstOrNull { it.isNotEmpty() }
        val stored = dtcs("03", "43")
        val freezeDtc = if (stored.isNullOrEmpty()) null else freezeDtc()
        return DiagnosticsReport(
            milOn = status?.let { it[0] and 0x80 != 0 },
            dtcCount = status?.let { it[0] and 0x7F },
            stored = stored,
            pending = dtcs("07", "47"),
            permanent = dtcs("0A", "4A"),
            freezeDtc = freezeDtc,
            freeze = if (freezeDtc == null) emptyMap() else freezeFrame(),
            vin = text("0902", "4902"),
            calibration = text("0904", "4904"),
            readiness = status?.let(Readiness::parse),
        )
    }

    /**
     * Las pruebas internas del coche con sus limites (modo 06). Primero se pregunta que monitores
     * existen, igual que con los PIDs, y luego se pide cada uno.
     */
    suspend fun monitorTests(): List<MonitorTest> {
        val monitors = mutableSetOf<Int>()
        var base = 0x00
        while (base <= LAST_MONITOR_BLOCK) {
            val request = "06%02X".format(base)
            val data = ObdParser.payloads(elm.send(request, READ_TIMEOUT_MS), "4" + request.substring(1))
                .firstOrNull { it.size >= 4 } ?: break
            val found = ObdParser.supportedFrom(base, data)
            monitors += found
            if ((base + 0x20) !in found) break
            base += 0x20
        }
        val tests = mutableListOf<MonitorTest>()
        for (monitor in monitors.filter { it % 0x20 != 0 }.sorted()) {
            val response = elm.send("06%02X".format(monitor), READ_TIMEOUT_MS)
            for (payload in ObdParser.payloads(response, "46")) tests += Mode06.parse(payload)
        }
        return tests
    }

    /** Borra las averias y los datos asociados. Devuelve si el coche lo ha aceptado. */
    suspend fun clearDtcs(): Boolean =
        ObdParser.payloads(elm.send("04", READ_TIMEOUT_MS), "44").isNotEmpty()

    fun close() = transport.close()

    private suspend fun dtcs(mode: String, prefix: String): List<String>? {
        val payloads = ObdParser.payloads(elm.send(mode, READ_TIMEOUT_MS), prefix)
        return if (payloads.isEmpty()) null else Dtcs.parse(payloads)
    }

    /** La averia que provoco la foto del modo 02, si hay alguna. */
    private suspend fun freezeDtc(): String? {
        val data = ObdParser.payloads(elm.send("020200", READ_TIMEOUT_MS), "420200").firstOrNull() ?: return null
        if (data.size < 2 || (data[0] == 0 && data[1] == 0)) return null
        return Dtcs.code(data[0], data[1])
    }

    private suspend fun freezeFrame(): Map<Int, Double> {
        val values = LinkedHashMap<Int, Double>()
        for (id in FREEZE_PIDS) {
            val def = Pids.byId.getValue(id)
            val request = "02%02X00".format(id)
            val data = ObdParser.payloads(elm.send(request, READ_TIMEOUT_MS), "4" + request.substring(1))
                .firstOrNull() ?: continue
            def.decodeOrNull(data)?.let { values[id] = it }
        }
        return values
    }

    /** Texto de una respuesta del modo 09: tras el prefijo va un contador y luego caracteres. */
    private suspend fun text(request: String, prefix: String): String? {
        val data = ObdParser.payloads(elm.send(request, READ_TIMEOUT_MS), prefix).firstOrNull() ?: return null
        val text = data.drop(1).filter { it in 0x20..0x7E }.joinToString("") { it.toChar().toString() }.trim()
        return text.ifEmpty { null }
    }

    /** Pregunta por 0100, 0120, 0140... mientras cada bloque anuncie que existe el siguiente. */
    private suspend fun scanSupported(): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= LAST_SUPPORT_BLOCK) {
            val timeout = if (base == 0x00) FIRST_QUERY_TIMEOUT_MS else READ_TIMEOUT_MS
            val response = elm.send("01%02X".format(base), timeout)
            val data = ObdParser.dataBytes(response, base) ?: break
            val found = ObdParser.supportedFrom(base, data)
            supported += found
            if ((base + 0x20) !in found) break
            base += 0x20
        }
        return supported
    }

    /** Deja en el registro que anuncia el coche y que parte de eso la app no sabe leer todavia. */
    private fun logSupport(supported: Set<Int>) {
        val measures = supported.filter { it % 0x20 != 0 }.sorted()
        val known = Pids.all.map { it.pid }.toSet()
        log("## El coche anuncia: " + measures.joinToString(" ") { "%02X".format(it) })
        log("## Sin fórmula en la app: " + measures.filter { it !in known }.joinToString(" ") { "%02X".format(it) })
    }

    private companion object {
        // La primera consulta incluye la busqueda de protocolo del adaptador, que tarda varios segundos.
        const val FIRST_QUERY_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 3_000L
        const val LAST_SUPPORT_BLOCK = 0xC0
        const val MAX_TIMEOUTS = 3
        const val LAST_MONITOR_BLOCK = 0xE0
        const val QUICK_SUFFIX = "1"
        const val VIN_LENGTH = 17
        const val FUEL_TYPE = 0x51
        const val FUEL_GASOLINE = 1
        const val FUEL_DIESEL = 4
        const val SHORT_FUEL_TRIM = 0x06
        const val BMW_QUERY = "2C10"
        const val BMW_ANSWER = "6C10"
        const val BMW_IDENT = "1A80"

        val RAW_SETUP = listOf("ATSP6", "ATCAF0", "ATSH6F1", "ATCF600", "ATCM700", "ATAT0", "ATSTFF")
        val RAW_TARGETS = listOf(0x12, 0x60, 0x40, 0x29)
        const val RAW_TIMEOUT_MS = 2_500L
        const val SCAN_TIMEOUT_MS = 1_500L

        // Espera fija de 400 ms en vez de la adaptativa.
        val FIXED_WAIT = listOf("ATAT0", "ATST64")

        // Lo que estaba puesto en el sondeo cuando la centralita contesto por primera vez.
        val BMW_PROBE_SETUP = listOf(
            "ATSP6", "ATSH6F1", "ATCEA12", "ATFCSH6F1", "ATFCSD123000", "ATFCSM1", "ATCRA612", "ATAT0", "ATSTFF",
        )

        val FREEZE_PIDS = listOf(Pids.RPM, Pids.SPEED, Pids.LOAD, Pids.COOLANT, Pids.MAP)

        // Version del adaptador y, por OBD, refrigerante, tension, rail, admision y revoluciones:
        // lo que el sondeo vuelve a pedir por el protocolo de BMW para comparar.
        val PROBE_PLAIN = listOf("ATI", "AT@1", "ATRV", "ATDPN", "0105", "0142", "0123", "010B", "010C")
    }
}
