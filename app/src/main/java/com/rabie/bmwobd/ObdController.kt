package com.rabie.bmwobd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.content.ContextCompat
import com.rabie.bmwobd.advice.Advice
import com.rabie.bmwobd.advice.Advisor
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.cases.Case
import com.rabie.bmwobd.cases.FirstStart
import com.rabie.bmwobd.dash.Dashboard
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.dash.GearGuess
import com.rabie.bmwobd.dash.ShiftHint
import com.rabie.bmwobd.obd.BmwFault
import com.rabie.bmwobd.obd.Commands
import com.rabie.bmwobd.obd.DiagnosticsReport
import com.rabie.bmwobd.obd.MonitorTest
import com.rabie.bmwobd.settings.SettingsStore
import com.rabie.bmwobd.obd.ObdSession
import com.rabie.bmwobd.obd.ObdTransport
import com.rabie.bmwobd.obd.PidDef
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.scan.ScanProgress
import com.rabie.bmwobd.scan.ScanRunner
import com.rabie.bmwobd.scan.ScanSaved
import com.rabie.bmwobd.scan.ScanStore
import com.rabie.bmwobd.trips.FuelCounter
import com.rabie.bmwobd.trips.TripFuel
import com.rabie.bmwobd.trips.TripRecorder
import com.rabie.bmwobd.trips.TripStore
import com.rabie.bmwobd.vehicle.EngineProfile
import com.rabie.bmwobd.vehicle.N47Fingerprint
import com.rabie.bmwobd.vehicle.Vehicle
import com.rabie.bmwobd.vehicle.VehicleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class Phase { IDLE, CONNECTING, LIVE, ERROR }

data class LiveState(
    val phase: Phase = Phase.IDLE,
    val simulated: Boolean = false,
    val adapterInfo: String = "",
    val supported: Set<Int> = emptySet(),
    val values: Map<Int, Double> = emptyMap(),
    val cyclesPerSecond: Double = 0.0,
    val liveSinceMillis: Long = 0,
    val advice: List<Advice> = emptyList(),
    val vehicle: Vehicle = Vehicle.GENERIC,
    /** Si a este coche se le pueden poner casos, y el que tiene puesto. */
    val cases: Boolean = false,
    val case: Case = Case.NORMAL,
    /** Consumo medio de lo que va de trayecto, en L/100; null hasta llevar unos kilometros. */
    val averageL100: Double? = null,
    /** Kilometros de este trayecto, sumados de la velocidad. */
    val tripKm: Double = 0.0,
    /** Litros gastados en este trayecto; con [tripLitersReal], los del contador de la centralita y no una estimacion. */
    val tripLiters: Double? = null,
    val tripLitersReal: Boolean = false,
    /** El precio del litro puesto en los ajustes, para la cuenta en euros. */
    val fuelPrice: Double? = null,
    /** La marcha de ahora, deducida de velocidad y revoluciones; 0 parado, null si no se sabe. */
    val gear: Int? = null,
    /** Recomendacion de cambio calculada por la app: [ShiftHint.UP], [ShiftHint.DOWN] o ninguna. */
    val shift: Int = ShiftHint.NONE,
    val error: String? = null,
)

data class DiagnosticsState(
    val busy: Boolean = false,
    val report: DiagnosticsReport? = null,
    val readAtMillis: Long = 0,
    val message: String? = null,
    val tests: List<MonitorTest>? = null,
)

/** [finished] cuenta los sondeos terminados, para que la pantalla sepa cuando acaba uno. */
data class ProbeState(val running: Boolean = false, val finished: Int = 0)

/**
 * La conexion con el coche. Vive en la aplicacion y no en una pantalla, para que la lectura y la
 * grabacion sigan con la pantalla apagada o con otra app delante.
 */
class ObdController(
    private val context: Context,
    private val trips: TripStore,
    private val vehicles: VehicleStore,
    private val settings: SettingsStore,
    private val scans: ScanStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val logBuffer = LogBuffer()

    private val _probe = MutableStateFlow(ProbeState())
    val probeState: StateFlow<ProbeState> = _probe.asStateFlow()

    private val _scan = MutableStateFlow(ScanProgress())
    val scanState: StateFlow<ScanProgress> = _scan.asStateFlow()

    // El barrido en curso. Solo se toca desde la lectura.
    private var scan: ScanRunner? = null
    private var scanPerTurn = SCAN_PER_TURN

    private val _diagnostics = MutableStateFlow(DiagnosticsState())
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()

    // Lo que se pide a mano (consola, averias) espera aqui y se atiende entre dos vueltas de lectura.
    private val tasks = Channel<suspend (ObdSession) -> Unit>(Channel.UNLIMITED)

    // Las medidas que la pantalla enseña ahora mismo.
    @Volatile
    private var focus: List<Int> = emptyList()

    // Solo se toca desde la lectura.
    private var silence = Silence()
    private var lastError: String? = null
    private var job: Job? = null

    /**
     * Con [keepLog] el registro no se vacia: es un intento automatico, y lo ultimo que contesto el
     * coche tiene que seguir ahi aunque la app lo reintente sola.
     */
    fun connect(transport: ObdTransport, simulated: Boolean, keepLog: Boolean = false) {
        job?.cancel()
        while (tasks.tryReceive().isSuccess) Unit
        if (!keepLog) {
            logBuffer.clear()
            _log.value = emptyList()
            lastError = null
        }
        _probe.update { it.copy(running = false) }
        _diagnostics.value = DiagnosticsState()
        _state.value = LiveState(phase = Phase.CONNECTING, simulated = simulated)
        startService()
        job = scope.launch {
            val session = ObdSession(transport, ::appendLog)
            var recorder: TripRecorder? = null
            try {
                val info = session.connect()
                val vehicle = withProfile(session, vehicles.resolve(info.vin, info.diesel))
                val profile = EngineProfile.of(vehicle)
                val standard = Pids.all.filter { it.pid == Pids.ADAPTER_PID || it.pid in info.supportedPids }
                val taken = standard.map { it.id }.toSet()
                val own = if (profile.measures.isEmpty()) emptyList() else session.discoverBmw(profile.measures.filter { it.id !in taken })
                // Lo que una averia presente deja sin lectura de verdad no se enseña ni se graba.
                val ownFaults = if (profile.ownDiagnostics) session.ownFaults() else null
                val blind = BmwFault.unreadable(ownFaults)
                if (ownFaults != null) {
                    // Fijo en el registro: las lecturas de despues lo echarian fuera en segundos.
                    logBuffer.pin()
                    try {
                        session.probeFaultMemory(ownFaults)
                    } finally {
                        logBuffer.unpin()
                    }
                    // A un fichero: el registro vive en memoria y se pierde si la app se cierra.
                    val answered = logBuffer.pinned()
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val file = java.io.File(context.filesDir, PROBE_FILE)
                            file.parentFile?.mkdirs()
                            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
                            file.writeText((listOf("## Prueba guardada de la conexión del $stamp") + answered).joinToString("\n"))
                        }
                    }
                }
                if (blind.isNotEmpty()) appendLog("## Aceite: el sensor de nivel tiene una avería presente; no se enseñan su nivel ni su temperatura")
                val defs = (standard + own).filterNot { it.id in blind }
                // Las medidas de los casos tienen su columna desde el principio, aunque no se pidan.
                val extras = if (own.isEmpty()) emptyList() else profile.caseMeasures
                appendLog("## Coche: ${vehicle.name} · ${if (vehicle.diesel) "diésel" else "gasolina"} · bastidor ${info.vin ?: "no leído"}")
                appendLog("## Perfil: ${profile.title}")
                val started = withContext(Dispatchers.IO) {
                    TripRecorder(trips.newFile(simulated, vehicle.key), (defs + extras).map { it.id }.distinct())
                }
                recorder = started
                lastError = null
                _state.update {
                    it.copy(
                        phase = Phase.LIVE,
                        vehicle = vehicle,
                        adapterInfo = info.summary,
                        supported = defs.map { def -> def.id }.toSet(),
                        cases = extras.isNotEmpty(),
                        liveSinceMillis = System.currentTimeMillis(),
                    )
                }
                if (canScan(vehicle) && scans.load(vehicle.key).active) {
                    appendLog("## Escaneo: sigue por donde iba")
                    openScan(vehicle.key)
                }
                poll(session, defs, extras, started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un reintento automatico que falla igual que el anterior no vuelve a apuntarlo.
                if (!keepLog || e.message != lastError) appendLog("!! ${e.message}")
                lastError = e.message
                _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: "Error de conexión") }
            } finally {
                closeScan(keepActive = true)
                session.close()
                recorder?.close()
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _scan.update { it.copy(active = false) }
        _probe.update { it.copy(running = false) }
        _state.value = LiveState()
    }

    /** La pantalla dice que medidas enseña, para que se lean mas seguido mientras esten a la vista. */
    fun watch(ids: List<Int>) {
        focus = ids
    }

    /** Guarda los cambios del usuario en el perfil del coche conectado. */
    fun updateVehicle(vehicle: Vehicle) {
        vehicles.save(vehicle)
        _state.update { if (it.vehicle.key == vehicle.key) it.copy(vehicle = vehicle) else it }
    }

    /** Comando escrito a mano; la respuesta sale en el registro. */
    fun sendCommand(command: String) {
        val text = command.trim()
        if (text.isEmpty() || !isLive()) return
        if (!settings.state.value.expertConsole && !Commands.isReadOnly(text)) {
            appendLog("!! $text no es una consulta. Para enviarlo, activa el modo experto en Ajustes.")
            return
        }
        tasks.trySend { session ->
            try {
                session.raw(text)
            } catch (e: IOException) {
                appendLog("!! Sin respuesta a $text")
            }
        }
    }

    /** El barrido y la memoria de averias propia son de los motores con perfil que los tenga. */
    fun canScan(vehicle: Vehicle) = EngineProfile.of(vehicle).ownDiagnostics

    /**
     * El coche con su perfil. El que ya lo tiene guardado lo conserva sin volver a preguntar. Al
     * que no, y solo si su marca lo hace posible, se le comprueba la centralita: si encaja, el
     * perfil se guarda con el coche; si no, sigue como generico y se vuelve a mirar otro dia.
     */
    private suspend fun withProfile(session: ObdSession, vehicle: Vehicle): Vehicle {
        if (EngineProfile.of(vehicle) != EngineProfile.GENERIC) return vehicle
        val candidate = EngineProfile.candidate(vehicle) ?: return vehicle
        // Las dos lecturas no son del mismo instante: si no cuadran a la primera se repite una vez,
        // para no dejar un trayecto sin perfil por un acelerón al conectar.
        var fits = false
        for (attempt in 1..PROFILE_ATTEMPTS) {
            val own = session.readBmw(N47Fingerprint.checks.map { it.def }) ?: break
            val obd = N47Fingerprint.checks.mapNotNull { check ->
                val def = Pids.byId[check.obd] ?: return@mapNotNull null
                session.read(check.obd)?.let(def::decodeOrNull)?.let { check.obd to it }
            }.toMap()
            fits = N47Fingerprint.fits(own, obd)
            if (fits) break
            appendLog("## Perfil: intento $attempt sin coincidencia. Propias ${own.values.map { "%.1f".format(it) }}, OBD ${obd.values.map { "%.1f".format(it) }}")
        }
        if (!fits) {
            appendLog("## Perfil: la centralita no encaja con ${candidate.title}; el coche se trata como genérico")
            return vehicle
        }
        appendLog("## Perfil: centralita reconocida como ${candidate.title}; queda guardado con el coche")
        return vehicle.copy(profile = candidate.key).also(vehicles::save)
    }

    /** El fichero con lo que ha contestado el coche conectado, o el ultimo que se conecto. */
    fun scanFile(): java.io.File? {
        val key = _state.value.vehicle.takeIf { isLive() }?.key ?: vehicles.last()?.key ?: return null
        return scans.file(key).takeIf { it.exists() }
    }

    /**
     * Arranca o para el barrido. Arrancado, sigue solo en las conexiones siguientes por donde iba
     * hasta que se pare o termine. La primera vez, y solo con el coche parado, mira antes que
     * centralitas contestan: esa prueba cambia la configuracion del adaptador y no se repite.
     */
    fun toggleScan() {
        val vehicle = _state.value.vehicle
        if (!isLive() || !canScan(vehicle)) return
        tasks.trySend { session ->
            if (scan != null) {
                closeScan(keepActive = false)
                return@trySend
            }
            val saved = scans.load(vehicle.key)
            val stopped = (_state.value.values[Pids.SPEED] ?: 0.0) < 1.0
            if (!saved.reached && stopped) {
                // Se da por hecha antes de empezar: si deja el adaptador mal, al reconectar no se repite.
                scans.save(vehicle.key, saved.copy(reached = true))
                logBuffer.pin()
                try {
                    session.scanReach()
                } finally {
                    logBuffer.unpin()
                }
            }
            openScan(vehicle.key)
        }
    }

    /** Borra lo barrido de este coche para empezar de cero. */
    fun resetScan() {
        val vehicle = _state.value.vehicle
        if (!isLive() || scan != null) return
        val reached = scans.load(vehicle.key).reached
        scans.reset(vehicle.key)
        scans.save(vehicle.key, ScanSaved(reached = reached))
        _scan.value = ScanProgress()
    }

    private fun openScan(vehicleKey: String) {
        val known = Pids.bmwDiesel.map { it.pid - Pids.BMW_BASE }
        val run = ScanRunner(scans, vehicleKey, known, ::appendLog)
        scan = run
        scanPerTurn = SCAN_PER_TURN
        _scan.value = run.progress()
    }

    private fun closeScan(keepActive: Boolean) {
        val run = scan ?: return
        scan = null
        run.close(keepActive)
        _scan.value = run.progress().copy(active = false)
    }

    /** Unas pocas consultas del barrido, intercaladas con la lectura normal. */
    private suspend fun scanStep(session: ObdSession, values: Map<Int, Double>) {
        val run = scan ?: return
        val started = System.nanoTime()
        val count = scanPerTurn
        val going = withContext(Dispatchers.IO) { run.step(count, values.toMap(), session::ask) }
        // Cada consulta tarda lo que espera el adaptador, unas tres decimas. Si tarda mucho mas,
        // se pregunta de una en una para que la lectura normal no se quede sin sitio.
        val slow = System.nanoTime() - started > SCAN_SLOW_QUERY_NANOS * count
        if (slow && scanPerTurn > 1) appendLog("## Escaneo: el adaptador va lento; se pregunta de una en una")
        if (slow) scanPerTurn = 1
        _scan.value = run.progress()
        if (!going) closeScan(keepActive = false)
    }

    /**
     * Lanza el sondeo de consultas de respuesta desconocida. Mientras dura no hay lecturas
     * normales; lo que conteste el coche queda fijo en el registro.
     */
    fun probe() {
        if (!isLive() || _probe.value.running) return
        _probe.update { it.copy(running = true) }
        tasks.trySend { session ->
            logBuffer.pin()
            // Si se desconecta a medias no cuenta como terminado.
            var ended = false
            try {
                session.probe()
                ended = true
            } catch (e: IOException) {
                appendLog("!! Sondeo interrumpido: ${e.message}")
                ended = true
            } finally {
                logBuffer.unpin()
                _probe.update { ProbeState(running = false, finished = it.finished + if (ended) 1 else 0) }
            }
        }
    }

    fun readDiagnostics() = diagnose(clearFirst = false)

    fun clearDtcs() = diagnose(clearFirst = true)

    private fun diagnose(clearFirst: Boolean) {
        if (!isLive() || _diagnostics.value.busy) return
        _diagnostics.update { it.copy(busy = true, message = null) }
        tasks.trySend { session ->
            try {
                val message = when {
                    !clearFirst -> null
                    session.clearDtcs() -> "Averías borradas."
                    else -> "El coche no ha aceptado el borrado. Prueba con el contacto puesto y el motor parado."
                }
                val report = session.diagnostics(bmw = canScan(_state.value.vehicle))
                _diagnostics.update {
                    it.copy(busy = false, report = report, readAtMillis = System.currentTimeMillis(), message = message)
                }
            } catch (e: IOException) {
                appendLog("!! ${e.message}")
                _diagnostics.update { it.copy(busy = false, message = "El adaptador no ha contestado.") }
            }
        }
    }

    /** Lee las pruebas internas del coche con sus limites. Tarda unos segundos. */
    fun readTests() {
        if (!isLive() || _diagnostics.value.busy) return
        _diagnostics.update { it.copy(busy = true, message = null) }
        tasks.trySend { session ->
            try {
                val tests = session.monitorTests()
                val message = if (tests.isEmpty()) "El coche no publica pruebas internas por el OBD estándar." else null
                _diagnostics.update { it.copy(busy = false, tests = tests, message = message) }
            } catch (e: IOException) {
                appendLog("!! ${e.message}")
                _diagnostics.update { it.copy(busy = false, message = "El adaptador no ha contestado.") }
            }
        }
    }

    private fun isLive() = _state.value.phase == Phase.LIVE

    private suspend fun poll(session: ObdSession, defs: List<PidDef>, extras: List<PidDef>, recorder: TripRecorder) {
        val turns = Turns(defs, extras, Dashboard.background)
        silence = Silence()
        val values = HashMap<Int, Double>()
        val advisor = Advisor()
        var soundedAlerts = emptySet<String>()
        var advice = emptyList<Advice>()
        var liters = 0.0
        var km = 0.0
        var lastSumNanos = System.nanoTime()
        val gears = GearGuess()
        val shifts = ShiftHint()
        val firstStart = FirstStart()
        val fuelCounter = FuelCounter()
        var looked = emptyList<Int>()
        _state.update { it.copy(supported = turns.shown) }

        for (request in turns.requests) read(session, request, values, first = true)
        var lastAnswerNanos = System.nanoTime()
        var windowStartNanos = lastAnswerNanos
        var cyclesInWindow = 0
        var rate = 0.0

        while (currentCoroutineContext().isActive) {
            while (true) {
                val task = tasks.tryReceive().getOrNull() ?: break
                task(session)
            }

            // El caso se puede cambiar en marcha. Lo que solo leia el anterior se quita para que
            // no quede en la grabacion un valor que ya no se actualiza.
            // Sin caso elegido a mano, lo pone el aviso mas grave: sus medidas se leen mas seguido mientras dura.
            val chosen = settings.state.value.case
            val urgent = advice.firstOrNull { it.severity != Severity.INFO } ?: advice.firstOrNull()
            val wanted = when {
                extras.isEmpty() -> Case.NORMAL
                // El primer arranque se mira solo, se haya elegido el caso que se haya elegido.
                firstStart.watching(System.nanoTime() / 1_000_000, values[Pids.RPM]) -> Case.COLD_START
                chosen != Case.NORMAL -> chosen
                else -> urgent?.let(Dashboard::group)?.case ?: Case.NORMAL
            }
            val looking = focus
            if (wanted != turns.case || looking != looked) {
                if (wanted != turns.case) appendLog("## Caso: ${wanted.title}")
                looked = looking
                val shown = turns.use(wanted, looking)
                values.keys.retainAll(shown)
                _state.update { it.copy(case = wanted, supported = shown) }
            }

            var answered = false
            val turn = turns.next(scanning = scan != null)
            for (request in turn) {
                if (read(session, request, values)) answered = true
            }
            if (turn.isEmpty()) delay(IDLE_DELAY_MS)
            scanStep(session, values)

            val now = System.nanoTime()
            if (answered) lastAnswerNanos = now
            if (now - lastAnswerNanos > SILENCE_LIMIT_NANOS) {
                throw IOException("El coche ha dejado de responder. ¿Contacto quitado?")
            }

            cyclesInWindow++
            if (now - windowStartNanos >= RATE_WINDOW_NANOS) {
                rate = cyclesInWindow * 1e9 / (now - windowStartNanos)
                windowStartNanos = now
                cyclesInWindow = 0
            }

            val snapshot = values.toMap()
            advice = advisor.update(now / 1_000_000, snapshot, _state.value.vehicle)
            val hours = (now - lastSumNanos).coerceIn(0L, MAX_SUM_GAP_NANOS) / NANOS_PER_HOUR
            lastSumNanos = now
            km += (snapshot[Pids.SPEED] ?: 0.0) * hours
            liters += (Pids.fuelRate(snapshot, _state.value.vehicle.diesel) ?: 0.0) * hours
            // Con el contador de la centralita, los litros son los suyos; sin el, la estimacion.
            fuelCounter.add(snapshot[TripFuel.FUEL_SINCE_REGEN])
            val spent = if (fuelCounter.seen) fuelCounter.liters else liters
            val average = if (km >= MIN_AVERAGE_KM) spent / km * 100.0 else null
            if (turn.any { it.pid == Dashboard.GEAR }) {
                snapshot[Dashboard.GEAR]?.let { gears.learn(it.toInt(), snapshot[Pids.SPEED], snapshot[Pids.RPM]) }
            }
            val gear = if (extras.isEmpty()) null else gears.now(snapshot[Pids.SPEED], snapshot[Pids.RPM])
            val nextRpm = gear?.let { gears.ratio(it + 1) }?.let { ratio -> snapshot[Pids.SPEED]?.let { it / ratio * 1000.0 } }
            val shift = shifts.update(
                now / 1_000_000, snapshot[Pids.SPEED], snapshot[Pids.RPM], snapshot[Pids.COOLANT], gear, nextRpm,
                Moment(snapshot, 0L, null, _state.value.vehicle).demanding,
            )
            val alerts = advice.filter { it.severity == Severity.ALERT }.map { it.id }.toSet()
            if ((alerts - soundedAlerts).isNotEmpty()) beep()
            soundedAlerts = alerts
            _state.update {
                it.copy(
                    values = snapshot, cyclesPerSecond = rate, advice = advice, averageL100 = average, tripKm = km, gear = gear, shift = shift,
                    tripLiters = spent, tripLitersReal = fuelCounter.seen, fuelPrice = settings.state.value.fuelPrice,
                )
            }
            if (answered) withContext(Dispatchers.IO) { recorder.append(snapshot) }
        }
    }

    /**
     * Devuelve si ha contestado el coche. La tension del adaptador no cuenta: contesta siempre.
     * Con [first] la lectura va sin lectura rapida, para que la sesion vea como contesta cada medida.
     */
    private suspend fun read(
        session: ObdSession,
        request: Request,
        values: MutableMap<Int, Double>,
        first: Boolean = false,
    ): Boolean {
        if (request.pid == Pids.ADAPTER_PID) {
            val volts = session.adapterVoltage()
            if (volts != null) {
                values[Pids.ADAPTER_VOLTAGE] = volts
                silence.answered(request)
            } else if (silence.missed(request)) {
                values.remove(Pids.ADAPTER_VOLTAGE)
            }
            return false
        }
        val data = if (first) session.readFirst(request.pid) else session.read(request.pid, request.singleFrame)
        if (data == null) {
            // Lo que deja de contestar no se sigue enseñando ni grabando con su ultimo valor.
            if (silence.missed(request) && request.defs.any { it.id in values }) {
                for (def in request.defs) values.remove(def.id)
                appendLog("## ${request.defs.first().name} ha dejado de contestar: deja de enseñarse hasta que vuelva")
            }
            return false
        }
        silence.answered(request)
        for (def in request.defs) {
            val value = def.decodeOrNull(data)
            if (value != null) values[def.id] = value else values.remove(def.id)
        }
        return true
    }

    /** Pitido cuando aparece un rojo nuevo, para no tener que mirar la pantalla conduciendo. */
    private fun beep() {
        if (!settings.state.value.sound) return
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_MS)
            scope.launch {
                delay(BEEP_MS + 200L)
                tone.release()
            }
        } catch (e: RuntimeException) {
            appendLog("!! Sin sonido de aviso: ${e.message}")
        }
    }

    /**
     * El servicio mantiene viva la app en segundo plano, tambien con el coche simulado. Android
     * solo lo deja arrancar con el permiso de Bluetooth concedido; sin el, se sigue sin servicio.
     */
    private fun startService() {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
        } catch (e: Exception) {
            appendLog("!! Sin servicio en segundo plano: ${e.message}")
        }
    }

    private fun appendLog(line: String) {
        _log.value = logBuffer.add(line)
    }

    companion object {
        /** Donde queda la ultima respuesta a la prueba de la memoria de averias. */
        const val PROBE_FILE = "logs/prueba_averias.txt"
        private const val PROBE_END = "## Prueba: fin"

        /** [lines] con la ultima prueba guardada detras, si no la llevan ya. */
        fun withSavedProbe(lines: List<String>, saved: List<String>): List<String> =
            if (saved.isEmpty() || lines.any { it.startsWith(PROBE_END) }) lines else lines + saved

        private const val IDLE_DELAY_MS = 200L
        private const val SCAN_PER_TURN = 3
        private const val SCAN_SLOW_QUERY_NANOS = 700_000_000L
        private const val BEEP_MS = 600
        private const val SILENCE_LIMIT_NANOS = 10_000_000_000L
        private const val RATE_WINDOW_NANOS = 2_000_000_000L
        private const val MAX_SUM_GAP_NANOS = 5_000_000_000L
        private const val NANOS_PER_HOUR = 3.6e12
        private const val MIN_AVERAGE_KM = 2.0
        private const val PROFILE_ATTEMPTS = 2
    }
}
