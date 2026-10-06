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
import com.rabie.bmwobd.obd.DiagnosticsReport
import com.rabie.bmwobd.obd.ObdSession
import com.rabie.bmwobd.obd.ObdTransport
import com.rabie.bmwobd.obd.PidDef
import com.rabie.bmwobd.obd.Pids
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
)

/** Una peticion al coche y las medidas que salen de su respuesta. */
private class Request(val pid: Int, val defs: List<PidDef>) {
    val fast: Boolean = defs.any { it.fast }
}

/**
 * La conexion con el coche. Vive en la aplicacion y no en una pantalla, para que la lectura y la
 * grabacion sigan con la pantalla apagada o con otra app delante.
 */
class ObdController(
    private val context: Context,
    private val trips: TripStore,
    private val vehicles: VehicleStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsState())
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()

    // Lo que se pide a mano (consola, averias) espera aqui y se atiende entre dos vueltas de lectura.
    private val tasks = Channel<suspend (ObdSession) -> Unit>(Channel.UNLIMITED)
    private var job: Job? = null

    fun connect(transport: ObdTransport, simulated: Boolean) {
        job?.cancel()
        while (tasks.tryReceive().isSuccess) Unit
        _log.value = emptyList()
        _diagnostics.value = DiagnosticsState()
        _state.value = LiveState(phase = Phase.CONNECTING, simulated = simulated)
        startService()
        job = scope.launch {
            val session = ObdSession(transport, ::appendLog)
            var recorder: TripRecorder? = null
            try {
                val info = session.connect()
                val defs = Pids.all.filter { it.pid == Pids.ADAPTER_PID || it.pid in info.supportedPids }
                val vehicle = vehicles.resolve(info.vin, info.diesel)
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
                poll(session, defs, started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("!! ${e.message}")
                _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: "Error de conexión") }
            } finally {
                session.close()
                recorder?.close()
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
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
        tasks.trySend { session ->
            try {
                session.raw(text)
            } catch (e: IOException) {
                appendLog("!! Sin respuesta a $text")
            }
        }
    }

    /** Lanza el sondeo de consultas de respuesta desconocida; el resultado queda en el registro. */
    fun probe() {
        if (!isLive()) return
        tasks.trySend { session ->
            try {
                session.probe()
            } catch (e: IOException) {
                appendLog("!! Sondeo interrumpido: ${e.message}")
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
                _diagnostics.value = DiagnosticsState(
                    report = session.diagnostics(),
                    readAtMillis = System.currentTimeMillis(),
                    message = message,
                )
            } catch (e: IOException) {
                appendLog("!! ${e.message}")
                _diagnostics.update { it.copy(busy = false, message = "El adaptador no ha contestado.") }
            }
        }
    }

    private fun isLive() = _state.value.phase == Phase.LIVE

    private suspend fun poll(session: ObdSession, defs: List<PidDef>, recorder: TripRecorder) {
        val requests = defs.groupBy { it.pid }.map { Request(it.key, it.value) }
        val fast = requests.filter { it.fast }
        val slow = requests.filter { !it.fast }
        val values = HashMap<Int, Double>()
        val advisor = Advisor()
        var soundedAlerts = emptySet<String>()

        for (request in requests) read(session, request, values)
        var lastAnswerNanos = System.nanoTime()
        var windowStartNanos = lastAnswerNanos
        var cyclesInWindow = 0
        var rate = 0.0
        var slowIndex = 0

        while (currentCoroutineContext().isActive) {
            while (true) {
                val task = tasks.tryReceive().getOrNull() ?: break
                task(session)
            }

            var answered = false
            val turn = if (slow.isEmpty()) fast else fast + slow[slowIndex++ % slow.size]
            for (request in turn) {
                if (read(session, request, values)) answered = true
            }
            if (turn.isEmpty()) delay(IDLE_DELAY_MS)

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

    /** Devuelve si ha contestado el coche. La tension del adaptador no cuenta: contesta siempre. */
    private suspend fun read(session: ObdSession, request: Request, values: MutableMap<Int, Double>): Boolean {
        if (request.pid == Pids.ADAPTER_PID) {
            session.adapterVoltage()?.let { values[Pids.ADAPTER_VOLTAGE] = it }
            return false
        }
        val data = session.read(request.pid) ?: return false
        for (def in request.defs) def.decodeOrNull(data)?.let { values[def.id] = it }
        return true
    }

    /** Pitido cuando aparece un rojo nuevo, para no tener que mirar la pantalla conduciendo. */
    private fun beep() {
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
        _log.update { lines ->
            val all = lines + line
            if (all.size <= MAX_LOG_LINES) all else all.take(HEAD_LOG_LINES) + all.takeLast(MAX_LOG_LINES - HEAD_LOG_LINES)
        }
    }

    private companion object {
        // Del registro se conserva siempre el principio, que es donde esta la negociacion con el coche.
        const val MAX_LOG_LINES = 800
        const val HEAD_LOG_LINES = 120
        const val IDLE_DELAY_MS = 200L
        const val BEEP_MS = 600
        const val SILENCE_LIMIT_NANOS = 10_000_000_000L
        const val RATE_WINDOW_NANOS = 2_000_000_000L
    }
}
