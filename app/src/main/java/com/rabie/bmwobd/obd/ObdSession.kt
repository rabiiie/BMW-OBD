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

    suspend fun connect(): SessionInfo {
        transport.open()
        val version = elm.initialize()
        val supported = scanSupported()
        if (supported.isEmpty()) {
            throw IOException("El coche no responde. ¿Contacto puesto o motor en marcha?")
        }
        logSupport(supported)
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
    suspend fun read(pid: Int): IntArray? {
        val response = try {
            elm.send("01%02X".format(pid), READ_TIMEOUT_MS)
        } catch (e: SocketTimeoutException) {
            if (++timeouts > MAX_TIMEOUTS) throw IOException("El adaptador ha dejado de responder.")
            return null
        }
        timeouts = 0
        return ObdParser.dataBytes(response, pid)
    }

    /** Tension en el conector OBD medida por el adaptador. Contesta aunque el coche este apagado. */
    suspend fun adapterVoltage(): Double? =
        elm.send("ATRV").lines().lastOrNull()?.removeSuffix("V")?.trim()?.toDoubleOrNull()

    /** Comando escrito a mano desde la consola. */
    suspend fun raw(command: String): String = elm.send(command, READ_TIMEOUT_MS)

    /**
     * Sondeo para la visita al coche: manda consultas cuya respuesta no se conoce y deja lo que
     * conteste en el registro. Son todas de lectura. Las direcciones del modo 22 son candidatas
     * sin contrastar; se prueban primero tal cual y luego hablando solo con la centralita del
     * motor de BMW (direccion 12). Al acabar deja el adaptador como estaba.
     */
    suspend fun probe() {
        log("## Sondeo: inicio")
        for (command in PROBE_PLAIN + PROBE_MODE_22) attempt(command)
        log("## Sondeo: direccionamiento BMW")
        try {
            for (command in BMW_ADDRESSING + PROBE_MODE_22) attempt(command)
        } finally {
            attempt("ATD")
            elm.configure()
        }
        log("## Sondeo: fin")
    }

    private suspend fun attempt(command: String) {
        try {
            elm.send(command, READ_TIMEOUT_MS)
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
        )
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
        const val VIN_LENGTH = 17
        const val FUEL_TYPE = 0x51
        const val FUEL_GASOLINE = 1
        const val FUEL_DIESEL = 4
        const val SHORT_FUEL_TRIM = 0x06

        val FREEZE_PIDS = listOf(Pids.RPM, Pids.SPEED, Pids.LOAD, Pids.COOLANT, Pids.MAP)

        // Version y tension del adaptador, y si acepta el numero de respuestas esperadas al final
        // del comando, que evita esperar al temporizador en cada lectura.
        val PROBE_PLAIN = listOf("ATI", "ATRV", "ATDPN", "010C", "010C1", "0902")

        // Candidatas vistas fuera de la documentacion de BMW: aceite, presion diferencial del
        // filtro, km desde la ultima regeneracion y gases antes del filtro.
        val PROBE_MODE_22 = listOf("222020", "222A0A", "222A10", "22280A")

        val BMW_ADDRESSING = listOf("ATSH6F1", "ATCEA12", "ATFCSH6F1", "ATFCSD123000", "ATFCSM1", "ATCRA612")
    }
}
