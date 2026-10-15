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
            // Una respuesta que llega tarde no es una medida que no exista: se pregunta otra vez
            // antes de dejarla fuera de todo el trayecto.
            val value = (readFirst(def.pid) ?: readFirst(def.pid))?.let(def::decodeOrNull) ?: continue
            log("## BMW: %s = %.2f %s".format(java.util.Locale.US, def.name, value, def.unit))
            found += def
        }
        log("## BMW: contestan ${found.size} de ${candidates.size} medidas")
        return found
    }

    /**
     * Lee una vez cada medida de [defs] por el protocolo de BMW y devuelve lo que valen. Null si
     * la centralita no atiende ese protocolo. Sirve para comprobar de que centralita se trata.
     */
    suspend fun readBmw(defs: List<PidDef>): Map<Int, Double>? {
        attempt(BMW_IDENT)
        if (!bmwAnswers()) return null
        return defs.mapNotNull { def -> (readFirst(def.pid) ?: readFirst(def.pid))?.let(def::decodeOrNull)?.let { def.id to it } }.toMap()
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

    // Una consulta de BMW se quedo sin respuesta y puede llegar tarde.
    private var bmwLate = false
    private var lateFlushes = 0

    private suspend fun applyWait() {
        if (fixedWait) for (command in FIXED_WAIT) attempt(command)
    }

    /** Reinicia el adaptador y lo deja como lo espera la lectura normal. */
    private suspend fun restore() {
        elm.drain()
        elm.initialize()
        attempt("0100", FIRST_QUERY_TIMEOUT_MS)
        applyWait()
    }

    /** Envia la consulta de una medida. Devuelve null si el adaptador no contesta a tiempo. */
    private suspend fun request(pid: Int, quickly: Boolean): String? {
        val own = Pids.isBmw(pid)
        if (own) flushLate()
        val response = try {
            elm.send(queryOf(pid) + if (quickly) QUICK_SUFFIX else "", READ_TIMEOUT_MS)
        } catch (e: SocketTimeoutException) {
            if (own) bmwLate = true
            if (++timeouts > MAX_TIMEOUTS) throw IOException("El adaptador ha dejado de responder.")
            return null
        }
        timeouts = 0
        if (own && dataOf(response, pid) == null) bmwLate = true
        return response
    }

    /**
     * Las respuestas de BMW no dicen de que medida son. Si la centralita tarda y el adaptador da
     * la consulta por perdida, esa respuesta sale despues como si fuera la de la consulta
     * siguiente: una medida recibia el valor de otra (visto en el coche el 12 de octubre). Por eso,
     * tras una consulta de BMW sin respuesta, antes de la siguiente se pide algo por el OBD normal
     * sin lectura rapida: el adaptador entrega ahi lo que tuviera atrasado y queda limpio.
     */
    private suspend fun flushLate() {
        if (!bmwLate) return
        bmwLate = false
        lateFlushes++
        if (lateFlushes <= LOGGED_FLUSHES || lateFlushes % FLUSH_LOG_EVERY == 0) {
            log("## BMW: una consulta quedó sin respuesta; se vacía el adaptador antes de la siguiente ($lateFlushes veces)")
        }
        try {
            elm.send(FLUSH_QUERY, READ_TIMEOUT_MS, quiet = true)
        } catch (e: SocketTimeoutException) {
            // Si tampoco contesta a esto, la lectura normal notara el silencio.
        }
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
        // Nunca con lectura rapida. Con la cifra de respuestas esperadas detras de una consulta de
        // cuatro bytes, este adaptador manda la direccion sin su ultimo byte y la centralita contesta
        // por otra medida: visto en el coche, todo un bloque de 256 direcciones daba el mismo valor.
        // Si traia pendiente la respuesta de una consulta anterior, la de esta es la ultima.
        val own = query.startsWith(BMW_QUERY)
        if (own) flushLate()
        val response = scanSend(query)
        val payload = response?.let { ObdParser.payloads(it, answerPrefix).lastOrNull() }
        // Una direccion que no existe contesta sin dato, y eso es una respuesta: solo cuenta el silencio.
        if (own && payload == null) bmwLate = true
        return payload
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
     * Pregunta a otras centralitas del coche, no a la del motor, quienes son y que configuracion
     * guardan. Todo son consultas: identidad (1A 80), averias (18 02) y bloques de configuracion
     * (22 30xx). No escribe nada. Cada centralita tiene su direccion y el adaptador se prepara
     * para hablar con ella de la forma en que contesto la del motor en el primer sondeo. Lo que
     * contesten queda en el registro tal cual. Al acabar el adaptador vuelve a la lectura normal.
     */
    suspend fun probeModules(): Pair<Int, Int> = askModules("## Centralitas: inicio", MODULES.map { (address, name) ->
        Triple("%02X".format(address), name, listOf(
            "ATSP6", "ATSH6F1", "ATCEA%02X".format(address), "ATFCSH6F1", "ATFCSD%02X3000".format(address),
            "ATFCSM1", "ATCRA6%02X".format(address), "ATAT0", "ATSTFF",
        ))
    }, MODULE_QUERIES)

    /**
     * Prepara el adaptador para cada centralita y le hace las consultas. La primera es siempre la
     * del motor, que contesta seguro: si ni ella contesta, este adaptador no admite esa forma de
     * preguntar y no se sigue. Devuelve cuantas contestaron de las preguntadas.
     */
    private suspend fun askModules(title: String, modules: List<Triple<String, String, List<String>>>, queries: List<String>): Pair<Int, Int> {
        log(title)
        var answered = 0
        var asked = 0
        try {
            for ((index, module) in modules.withIndex()) {
                val (address, name, setup) = module
                log("## Centralita $address: $name")
                for (command in setup) attempt(command)
                asked++
                var heard = false
                for (query in queries) if (answers(tryAsk(query))) heard = true
                if (heard) answered++
                if (index == 0 && !heard) {
                    log("## Ni la centralita del motor contesta así: este adaptador no admite esta forma de preguntar. No se sigue.")
                    break
                }
            }
        } finally {
            restoreOrFail()
        }
        log("## Centralitas: fin")
        return answered to asked
    }

    /**
     * La otra forma de preguntar a las centralitas de BMW: el adaptador manda las tramas CAN tal
     * cual, sin darles formato, y la app pone la direccion de la centralita en el primer byte, la
     * longitud y el control de flujo. Es como se hablo con la del motor en las primeras pruebas, y
     * sirve con adaptadores que no admiten la direccion por centralita. Solo consultas.
     */
    suspend fun probeModulesRaw(): Pair<Int, Int> {
        log("## Centralitas, con tramas en crudo: inicio")
        var answered = 0
        var asked = 0
        try {
            for (command in RAW_MODULE_SETUP) attempt(command)
            for ((index, module) in MODULES.withIndex()) {
                val (address, name) = module
                log("## Centralita %02X: %s".format(address, name))
                asked++
                var heard = false
                for (query in RAW_MODULE_QUERIES) {
                    val data = rawExchange(address, query) ?: continue
                    heard = true
                    log("## Contesta: " + data.joinToString("") { "%02X".format(it) })
                }
                if (heard) answered++
                if (index == 0 && !heard) {
                    log("## Ni la centralita del motor contesta en crudo: este adaptador tampoco admite esta forma. No se sigue.")
                    break
                }
            }
        } finally {
            restoreOrFail()
        }
        log("## Centralitas: fin")
        return answered to asked
    }

    /**
     * Una consulta en crudo a la centralita [target] y su respuesta entera. Si viene troceada, se
     * pide el resto con el control de flujo. Null si no contesta.
     */
    private suspend fun rawExchange(target: Int, request: IntArray): IntArray? {
        val from = RAW_ANSWER_BASE + target
        fun mine(frames: List<BmwProbe.Frame>) = frames.filter { it.id == from && it.bytes.size >= 2 && it.bytes[0] == TESTER }
        val first = mine(rawSend(intArrayOf(target, request.size) + request) ?: return null)
        val head = first.firstOrNull() ?: return null
        return when (head.bytes[1] shr 4) {
            SINGLE_FRAME -> head.bytes.drop(2).take(head.bytes[1] and 0x0F).toIntArray()
            FIRST_FRAME -> {
                if (head.bytes.size < 3) return null
                val total = (head.bytes[1] and 0x0F) * 256 + head.bytes[2]
                val data = head.bytes.drop(3).toMutableList()
                val rest = (first - head) + mine(rawSend(intArrayOf(target, FLOW_CONTROL_GO, 0x00, 0x00)).orEmpty())
                for (frame in rest) if (frame.bytes[1] shr 4 == CONSECUTIVE_FRAME) data += frame.bytes.drop(2)
                data.take(total).toIntArray()
            }
            else -> null
        }
    }

    private suspend fun rawSend(bytes: IntArray): List<BmwProbe.Frame>? {
        val text = (bytes.toList() + List(RAW_FRAME_BYTES) { 0 }).take(RAW_FRAME_BYTES).joinToString("") { "%02X".format(it) }
        return tryAsk(text)?.let(BmwProbe::frames)
    }

    private suspend fun tryAsk(command: String): String? = try {
        elm.send(command, RAW_TIMEOUT_MS)
    } catch (e: SocketTimeoutException) {
        log("!! Sin respuesta a $command")
        null
    }

    /** Si en la respuesta hay datos de la centralita, aunque sea una negativa: eso ya es que contesta. */
    private fun answers(response: String?): Boolean =
        response?.lines()?.any { line -> ANSWER_LINE.matches(line.trim().uppercase()) } == true

    /**
     * Pregunta a otras centralitas de un Ford quienes son y que averias guardan. Ford usa el
     * protocolo estandar: cada centralita tiene su direccion de envio y la de respuesta, ocho mas
     * alta, y el adaptador hace solo el control de flujo. Todo son consultas: referencia y version
     * (22 F113, 22 F188) y averias (19 02). El bastidor no se pide. Al acabar el adaptador vuelve a
     * la lectura normal.
     */
    suspend fun probeFordModules(): Pair<Int, Int> = askModules("## Centralitas Ford: inicio", FORD_MODULES.map { (address, name) ->
        Triple("%03X".format(address), name, listOf("ATSP6", "ATSH%03X".format(address), "ATCRA%03X".format(address + FORD_ANSWER_OFFSET), "ATAT0", "ATSTFF"))
    }, FORD_QUERIES)

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

    /** Con [bmw] se piden tambien las averias de la memoria propia de la centralita de BMW. */
    suspend fun diagnostics(bmw: Boolean = false): DiagnosticsReport {
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
        ).let { report ->
            if (!bmw) return@let report
            val faults = bmwFaults()
            report.copy(bmwFaults = faults, bmwInfo = bmwInfo(), bmwFaultKm = faults.orEmpty().mapNotNull { fault -> faultKm(fault.code)?.let { fault.code to it } }.toMap())
        }
    }

    private suspend fun bmwInfo(): List<BmwFault>? = bmwAsk(BmwFault.INFO_QUERY, BmwFault.INFO_ANSWER)?.let(BmwFault::parse)

    private suspend fun faultKm(code: Int): Int? = bmwAsk(BmwFault.detailQuery(code), BmwFault.DETAIL_ANSWER)?.let { BmwFault.detailKm(it, code) }

    /** Una consulta de la memoria de averias de BMW. Si no contesta a tiempo, su respuesta podria llegar tarde y cruzarse. */
    private suspend fun bmwAsk(query: String, answer: String): IntArray? = try {
        ObdParser.payloads(elm.send(query, READ_TIMEOUT_MS), answer).lastOrNull()
    } catch (e: SocketTimeoutException) {
        null
    } finally {
        bmwLate = true
    }

    /** Las averias de la memoria propia de BMW, o null si la centralita no las da. */
    suspend fun ownFaults(): List<BmwFault>? = try {
        bmwFaults()
    } catch (e: SocketTimeoutException) {
        null
    }

    private suspend fun bmwFaults(): List<BmwFault>? =
        ObdParser.payloads(elm.send(BmwFault.QUERY, READ_TIMEOUT_MS), BmwFault.ANSWER).lastOrNull()?.let(BmwFault::parse)

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
        const val FLUSH_QUERY = "010C"
        const val LOGGED_FLUSHES = 5
        const val FLUSH_LOG_EVERY = 50
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

        // Las direcciones son las de los BMW de esta epoca. La primera es la del motor, que ya
        // se sabe que contesta: sirve de control de que la forma de preguntar es buena.
        val MODULES = listOf(
            0x12 to "motor (de control)",
            0x72 to "luces e intermitentes, en el hueco de los pies",
            0x00 to "caja de conexiones, que hace de pasarela",
            0x60 to "cuadro de instrumentos",
            0x40 to "llaves y arranque",
        )

        // Solo servicios de lectura: identidad, averias y los cuatro primeros bloques de configuracion.
        val MODULE_QUERIES = listOf("1A80", "1802FFFF", "223000", "223001", "223002", "223003")

        // En crudo: sin formato, cabecera del equipo de diagnosis y que pasen las respuestas 6xx.
        val RAW_MODULE_SETUP = listOf("ATSP6", "ATCAF0", "ATSH6F1", "ATCF600", "ATCM700", "ATH1", "ATS0", "ATAL", "ATAT0", "ATSTFF")
        val RAW_MODULE_QUERIES = listOf(intArrayOf(0x1A, 0x80), intArrayOf(0x18, 0x02, 0xFF, 0xFF), intArrayOf(0x22, 0x30, 0x00))
        const val RAW_ANSWER_BASE = 0x600
        const val RAW_FRAME_BYTES = 8
        const val TESTER = 0xF1
        const val SINGLE_FRAME = 0
        const val FIRST_FRAME = 1
        const val CONSECUTIVE_FRAME = 2
        const val FLOW_CONTROL_GO = 0x30

        // Las direcciones de envio de un Focus de esa epoca, de memoria: hay que confirmarlas con lo
        // que contesten. La primera es la del motor, que contesta seguro: sirve de control.
        val FORD_MODULES = listOf(
            0x7E0 to "motor (de control)",
            0x726 to "carrocería, que recibe los sensores de presión de las ruedas",
            0x760 to "ABS, que en algunas versiones vigila las ruedas por su giro",
            0x720 to "cuadro de instrumentos",
        )
        const val FORD_ANSWER_OFFSET = 8

        // Una linea con bytes: la de una trama troceada ("0: 5A80...") o una respuesta entera.
        private val ANSWER_LINE = Regex("^([0-9A-F]:)?[0-9A-F]{4,}$")

        // Referencia de la pieza, version del programa, averias guardadas con cualquier estado y los
        // kilometros que guarda cada centralita (DD01, de memoria: hay que confirmarlo con lo que
        // contesten). Compararlos entre centralitas dice si alguien cambio el del cuadro.
        val FORD_QUERIES = listOf("22F113", "22F188", "1902FF", "22DD01")
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
