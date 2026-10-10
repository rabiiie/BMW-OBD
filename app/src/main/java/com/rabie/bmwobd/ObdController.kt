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
import com.rabie.bmwobd.obd.Commands
import com.rabie.bmwobd.obd.DiagnosticsReport
import com.rabie.bmwobd.obd.MonitorTest
import com.rabie.bmwobd.settings.SettingsStore
import com.rabie.bmwobd.obd.ObdSession
import com.rabie.bmwobd.obd.ObdTransport
import com.rabie.bmwobd.obd.PidDef
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.Tier
import com.rabie.bmwobd.scan.ScanPlan
import com.rabie.bmwobd.scan.ScanProgress
import com.rabie.bmwobd.scan.ScanStore
import com.rabie.bmwobd.scan.ScanWriter
import com.rabie.bmwobd.trips.TripRecorder
import com.rabie.bmwobd.trips.TripStore
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

/** Una peticion al coche y las medidas que salen de su respuesta. */
private class Request(val pid: Int, val defs: List<PidDef>) {
    val tier: Tier = defs.minOf { it.tier }

    /** Los PIDs de una sola medida caben en una trama y admiten la lectura rapida. */
    val singleFrame: Boolean = !Pids.isBmw(pid) && defs.all { it.pid == it.id }
}

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
    private var scan: ScanRun? = null

    private class ScanRun(val vehicleKey: String, val plan: ScanPlan, val writer: ScanWriter) {
        var sinceSave = 0
    }

    private val _diagnostics = MutableStateFlow(DiagnosticsState())
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()

    // Lo que se pide a mano (consola, averias) espera aqui y se atiende entre dos vueltas de lectura.
    private val tasks = Channel<suspend (ObdSession) -> Unit>(Channel.UNLIMITED)
    private var job: Job? = null

    fun connect(transport: ObdTransport, simulated: Boolean) {
        job?.cancel()
        while (tasks.tryReceive().isSuccess) Unit
        logBuffer.clear()
        _log.value = emptyList()
        _probe.update { it.copy(running = false) }
        _diagnostics.value = DiagnosticsState()
        _state.value = LiveState(phase = Phase.CONNECTING, simulated = simulated)
        startService()
        job = scope.launch {
            val session = ObdSession(transport, ::appendLog)
            var recorder: TripRecorder? = null
            try {
                val info = session.connect()
                val vehicle = vehicles.resolve(info.vin, info.diesel)
                val standard = Pids.all.filter { it.pid == Pids.ADAPTER_PID || it.pid in info.supportedPids }
                val taken = standard.map { it.id }.toSet()
                val own = if (Pids.hasBmwMeasures(vehicle.make, vehicle.diesel)) {
                    session.discoverBmw(Pids.bmwDiesel.filter { it.id !in taken })
                } else {
                    emptyList()
                }
                val defs = standard + own
                appendLog("## Coche: ${vehicle.name} · ${if (vehicle.diesel) "diésel" else "gasolina"} · bastidor ${info.vin ?: "no leído"}")
                val started = withContext(Dispatchers.IO) {
                    TripRecorder(trips.newFile(simulated, vehicle.key), defs.map { it.id })
                }
                recorder = started
                _state.update {
                    it.copy(
                        phase = Phase.LIVE,
                        vehicle = vehicle,
                        adapterInfo = info.summary,
                        supported = defs.map { def -> def.id }.toSet(),
                        liveSinceMillis = System.currentTimeMillis(),
                    )
                }
                if (canScan(vehicle) && scans.load(vehicle.key).active) openScan(vehicle.key)
                poll(session, defs, started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("!! ${e.message}")
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

    /** El barrido solo sabe preguntar a la centralita del motor de un BMW diesel. */
    fun canScan(vehicle: Vehicle) = Pids.hasBmwMeasures(vehicle.make, vehicle.diesel)

    /** El fichero con lo que ha contestado el coche conectado, o el ultimo que se conecto. */
    fun scanFile(): java.io.File? {
        val key = _state.value.vehicle.takeIf { isLive() }?.key ?: vehicles.last()?.key ?: return null
        return scans.file(key).takeIf { it.exists() }
    }

    /**
     * Arranca o para el barrido. Arrancado, sigue solo en las conexiones siguientes por donde iba
     * hasta que se pare. La primera vez mira antes que centralitas contestan.
     */
    fun toggleScan() {
        val vehicle = _state.value.vehicle
        if (!isLive() || !canScan(vehicle)) return
        tasks.trySend { session ->
            if (scan != null) {
                closeScan(keepActive = false)
            } else {
                if (scans.load(vehicle.key).fresh) {
                    logBuffer.pin()
                    try {
                        session.scanReach()
                    } catch (e: IOException) {
                        appendLog("!! Escaneo interrumpido: ${e.message}")
                    } finally {
                        logBuffer.unpin()
                    }
                }
                openScan(vehicle.key)
            }
        }
    }

    /** Borra lo barrido de este coche para empezar de cero. */
    fun resetScan() {
        val vehicle = _state.value.vehicle
        if (!isLive() || scan != null) return
        scans.reset(vehicle.key)
        _scan.value = ScanProgress()
    }

    private fun openScan(vehicleKey: String) {
        val saved = scans.load(vehicleKey)
        val run = ScanRun(vehicleKey, ScanPlan(saved.next, saved.found, saved.pass), scans.writer(vehicleKey))
        scans.save(vehicleKey, run.plan, active = true)
        scan = run
        publishScan(run)
    }

    private fun closeScan(keepActive: Boolean) {
        val run = scan ?: return
        scan = null
        run.writer.close()
        scans.save(run.vehicleKey, run.plan, keepActive)
        _scan.update { it.copy(active = false) }
    }

    private fun publishScan(run: ScanRun) {
        _scan.value = ScanProgress(true, run.plan.pass, run.plan.done, run.plan.total, run.plan.answering)
    }

    /** Unas pocas consultas del barrido, intercaladas con la lectura normal. */
    private suspend fun scanStep(session: ObdSession, values: Map<Int, Double>) {
        val run = scan ?: return
        repeat(SCAN_PER_TURN) {
            val query = run.plan.take() ?: return@repeat
            val answer = session.ask(query, ScanPlan.answerPrefix(query)) ?: return@repeat
            run.plan.answered()
            withContext(Dispatchers.IO) { run.writer.append(run.plan.pass, query, answer, values) }
        }
        run.sinceSave += SCAN_PER_TURN
        if (run.sinceSave >= SCAN_SAVE_EVERY) {
            run.sinceSave = 0
            withContext(Dispatchers.IO) { scans.save(run.vehicleKey, run.plan, active = true) }
        }
        publishScan(run)
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
                val report = session.diagnostics()
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

    private suspend fun poll(session: ObdSession, defs: List<PidDef>, recorder: TripRecorder) {
        val requests = defs.groupBy { it.pid }.map { Request(it.key, it.value) }
        val fast = requests.filter { it.tier == Tier.FAST }
        val medium = requests.filter { it.tier == Tier.MEDIUM }
        val slow = requests.filter { it.tier == Tier.SLOW }
        val values = HashMap<Int, Double>()
        val advisor = Advisor()
        var soundedAlerts = emptySet<String>()

        for (request in requests) read(session, request, values, first = true)
        var lastAnswerNanos = System.nanoTime()
        var windowStartNanos = lastAnswerNanos
        var cyclesInWindow = 0
        var rate = 0.0
        var mediumIndex = 0
        var slowIndex = 0
        var cycle = 0
        // Con muchas medidas lentas se lee una cada dos vueltas, para que no tarden en repetirse.
        val slowEvery = if (slow.size > MANY_SLOW) SLOW_EVERY / 2 else SLOW_EVERY

        while (currentCoroutineContext().isActive) {
            while (true) {
                val task = tasks.tryReceive().getOrNull() ?: break
                task(session)
            }

            var answered = false
            val turn = fast.toMutableList()
            if (medium.isNotEmpty()) turn += medium[mediumIndex++ % medium.size]
            if (slow.isNotEmpty() && (cycle++ % slowEvery == 0 || medium.isEmpty())) turn += slow[slowIndex++ % slow.size]
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
            val advice = advisor.update(now / 1_000_000, snapshot, _state.value.vehicle)
            val alerts = advice.filter { it.severity == Severity.ALERT }.map { it.id }.toSet()
            if ((alerts - soundedAlerts).isNotEmpty()) beep()
            soundedAlerts = alerts
            _state.update { it.copy(values = snapshot, cyclesPerSecond = rate, advice = advice) }
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
            session.adapterVoltage()?.let { values[Pids.ADAPTER_VOLTAGE] = it }
            return false
        }
        val data = (if (first) session.readFirst(request.pid) else session.read(request.pid, request.singleFrame))
            ?: return false
        for (def in request.defs) def.decodeOrNull(data)?.let { values[def.id] = it }
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

    private companion object {
        const val IDLE_DELAY_MS = 200L
        const val SLOW_EVERY = 4
        const val SCAN_PER_TURN = 3
        const val SCAN_SAVE_EVERY = 150
        const val MANY_SLOW = 16
        const val BEEP_MS = 600
        const val SILENCE_LIMIT_NANOS = 10_000_000_000L
        const val RATE_WINDOW_NANOS = 2_000_000_000L
    }
}
