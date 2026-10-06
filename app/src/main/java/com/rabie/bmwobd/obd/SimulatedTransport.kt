package com.rabie.bmwobd.obd

import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Un ELM327 de mentira: contesta a los comandos AT, a los PIDs del modo 01 con los datos de
 * [SimulatedEngine] y a las consultas de averias, con el mismo formato que deja la configuracion
 * de [Elm327] (sin eco, sin espacios, sin cabeceras). Los mensajes largos salen troceados como
 * los da el adaptador de verdad.
 */
class SimulatedTransport(
    private val latencyMs: Long = 35,
    private val clock: () -> Long = System::currentTimeMillis,
) : ObdTransport {

    private var startMs = 0L
    private var pending = ""
    private var protocolFound = false

    // Dos averias guardadas y una pendiente, inventadas, para tener algo que leer y borrar.
    private var stored = listOf(0x04 to 0x01, 0x02 to 0x99)
    private var waiting = listOf(0x24 to 0x63)

    // Al borrar averias las autocomprobaciones vuelven a empezar, como en un coche de verdad.
    private var cleared = false

    override suspend fun open() {
        startMs = clock()
        protocolFound = false
    }

    override suspend fun write(text: String) {
        pending = text.trim().uppercase().replace(" ", "")
    }

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        if (latencyMs > 0) delay(latencyMs)
        val command = pending
        pending = ""
        return respond(command) + "\r\r"
    }

    override fun close() = Unit

    private fun respond(command: String): String = when {
        command == "ATZ" || command == "ATI" -> "ELM327 v1.5 simulado"
        command == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        command == "ATRV" -> "%.1fV".format(java.util.Locale.US, engine().voltage)
        command.startsWith("AT") -> "OK"
        else -> obd(command)?.let(::frames)?.let(::withSearch) ?: if (isHex(command)) "NO DATA" else "?"
    }

    /** Los bytes de la respuesta completa, o null si el coche no contestaria. */
    private fun obd(request: String): IntArray? {
        // Una cifra suelta al final es el numero de respuestas que se esperan: no cambia la consulta.
        val command = if (request.length % 2 == 1) request.dropLast(1) else request
        if (!isHex(command) || command.isEmpty()) return null
        val bytes = IntArray(command.length / 2) { command.substring(it * 2, it * 2 + 2).toInt(16) }
        return when {
            bytes.size == 2 && bytes[0] == 0x01 -> mode01(bytes[1])?.let { intArrayOf(0x41, bytes[1]) + it }
            bytes.size == 3 && bytes[0] == 0x02 -> freeze(bytes[1])?.let { intArrayOf(0x42, bytes[1], 0x00) + it }
            bytes.size == 1 && bytes[0] == 0x03 -> intArrayOf(0x43, stored.size) + flat(stored)
            bytes.size == 1 && bytes[0] == 0x07 -> intArrayOf(0x47, waiting.size) + flat(waiting)
            bytes.size == 1 && bytes[0] == 0x0A -> intArrayOf(0x4A, 0x00)
            bytes.size == 1 && bytes[0] == 0x04 -> {
                stored = emptyList()
                waiting = emptyList()
                cleared = true
                intArrayOf(0x44)
            }
            bytes.size == 2 && bytes[0] == 0x06 -> monitor(bytes[1])?.let { intArrayOf(0x46) + it }
            bytes.size == 2 && bytes[0] == 0x09 && bytes[1] == 0x02 -> intArrayOf(0x49, 0x02, 0x01) + ascii(VIN, 17)
            bytes.size == 2 && bytes[0] == 0x09 && bytes[1] == 0x04 -> intArrayOf(0x49, 0x04, 0x01) + ascii(CALIBRATION, 16)
            else -> null
        }
    }

    private fun mode01(pid: Int): IntArray? = when {
        pid % 0x20 == 0 -> supportMask(pid)
        pid == 0x01 -> intArrayOf(
            (if (stored.isEmpty()) 0 else 0x80) or stored.size,
            READINESS_CONTINUOUS or (if (cleared) 0x70 else 0),
            READINESS_AVAILABLE,
            if (cleared) READINESS_AVAILABLE else READINESS_PENDING,
        )
        else -> encode(pid, engine(), seconds())
    }

    /**
     * Modo 06: en los bloques (00, 20, 40...) la lista de monitores que existen; en un monitor,
     * sus pruebas con valor, minimo y maximo. La del filtro de particulas sale fuera de limites, a
     * juego con la averia pendiente inventada.
     */
    private fun monitor(mid: Int): IntArray? {
        if (mid % 0x20 == 0) {
            val mask = IntArray(4)
            for (bit in 0 until 32) {
                val id = mid + bit + 1
                val isNextBlock = bit == 31 && MONITORS.keys.any { it > id }
                if (id in MONITORS || isNextBlock) mask[bit / 8] = mask[bit / 8] or (1 shl (7 - bit % 8))
            }
            return if (mask.all { it == 0 }) null else intArrayOf(mid) + mask
        }
        return MONITORS[mid]
    }

    /** La foto de la averia: solo existe mientras hay una averia guardada. */
    private fun freeze(pid: Int): IntArray? {
        val first = stored.firstOrNull() ?: return if (pid == 0x02) intArrayOf(0, 0) else null
        return if (pid == 0x02) intArrayOf(first.first, first.second) else encode(pid, SimulatedEngine.at(FREEZE_SECOND), FREEZE_SECOND)
    }

    /** Hasta siete bytes caben en una linea; mas, van con la longitud delante y en trozos numerados. */
    private fun frames(message: IntArray): String {
        if (message.size <= 7) return hex(message)
        val lines = mutableListOf("%03X".format(message.size), "0:" + hex(message.copyOfRange(0, 6)))
        var index = 1
        var from = 6
        while (from < message.size) {
            val chunk = message.copyOfRange(from, minOf(from + 7, message.size))
            lines += "%X:".format(index % 16) + hex(chunk) + "AA".repeat(7 - chunk.size)
            index++
            from += 7
        }
        return lines.joinToString("\r")
    }

    private fun withSearch(response: String): String {
        if (protocolFound) return response
        protocolFound = true
        return "SEARCHING...\r$response"
    }

    private fun seconds(): Double = (clock() - startMs) / 1000.0

    private fun engine(): EngineState = SimulatedEngine.at(seconds())

    /** 32 bits: los PIDs del bloque que existen y, en el ultimo, si hay un bloque siguiente. */
    private fun supportMask(base: Int): IntArray? {
        val data = IntArray(4)
        for (bit in 0 until 32) {
            val pid = base + bit + 1
            val isNextBlock = bit == 31 && SUPPORTED.any { it > pid }
            if (pid in SUPPORTED || isNextBlock) data[bit / 8] = data[bit / 8] or (1 shl (7 - bit % 8))
        }
        return if (data.all { it == 0 }) null else data
    }

    private companion object {
        const val VIN = "WBASIMULADO000000"
        const val CALIBRATION = "SIM-N47D20C-01"
        const val FREEZE_SECOND = 68.0

        // Diesel con las tres comprobaciones continuas; catalizador, turbo, filtro y EGR disponibles;
        // de salida solo falta por terminar la del filtro de particulas.
        const val READINESS_CONTINUOUS = 0x0F
        const val READINESS_AVAILABLE = 0xC9
        const val READINESS_PENDING = 0x40

        // Monitor, prueba, unidad, valor, minimo y maximo, de dos en dos bytes los tres ultimos.
        val MONITORS: Map<Int, IntArray> = mapOf(
            0x31 to intArrayOf(0x31, 0x80, 0x2F, 0x04, 0xB0, 0x01, 0xF4, 0x0F, 0xA0),
            0x85 to intArrayOf(0x85, 0x81, 0x17, 0x01, 0x2C, 0x00, 0x00, 0x05, 0xDC),
            0xB2 to intArrayOf(0xB2, 0x82, 0x17, 0x06, 0x72, 0x00, 0x00, 0x05, 0xDC, 0xB2, 0x83, 0x96, 0xFF, 0x9C, 0xFE, 0x0C, 0x01, 0xF4),
        )

        private val sample = SimulatedEngine.at(0.0)

        val SUPPORTED: Set<Int> =
            Pids.all.map { it.pid }.filter { it >= 0 && encode(it, sample, 0.0) != null }.toSet() + 0x01

        fun encode(pid: Int, s: EngineState, seconds: Double): IntArray? {
            val boost = s.mapKpa - s.baroKpa
            val egt = 150.0 + s.load * 4.2
            val filterDrop = 0.3 + s.load * 0.08
            return when (pid) {
                0x04 -> percent(s.load)
                0x05 -> byte(s.coolant + 40)
                0x0B -> byte(s.mapKpa)
                0x0C -> word(s.rpm * 4)
                0x0D -> byte(s.speed)
                0x0F -> byte(s.intakeTemp + 40)
                0x10 -> word(s.maf * 100)
                0x11 -> percent(88.0)
                0x1F -> word(seconds)
                0x21 -> word(35.0)
                0x23 -> word(s.railBar * 10)
                0x2C -> percent(s.egr)
                0x2D -> byte((s.egrError + 100) * 128 / 100)
                0x2F -> percent(s.fuelLevel)
                0x30 -> byte(57.0)
                0x31 -> word(1240.0)
                0x33 -> byte(s.baroKpa)
                0x3C -> wideTemp(egt - 40)
                0x3E -> wideTemp(egt - 110)
                0x42 -> word(s.voltage * 1000)
                0x43 -> word(s.load * 0.9 * 255 / 100)
                0x44 -> word(1.4 * 32768)
                0x45 -> percent(85.0)
                0x46 -> byte(s.ambient + 40)
                0x49 -> percent(s.load)
                0x4C -> percent(88.0)
                0x4D -> word(90.0)
                0x4E -> word(4000.0)
                0x5A -> percent(s.load)
                0x5C -> byte(s.oil + 40)
                0x5D -> word((2.0 + 210) * 128)
                0x5E -> word(s.fuelRate * 20)
                0x61 -> byte(s.load + 125)
                0x62 -> byte(s.load * 0.95 + 125)
                0x63 -> word(300.0)
                0x69 -> intArrayOf(0x07) + percent(s.egr) + percent((s.egr - s.egrError).coerceIn(0.0, 100.0)) +
                    byte((s.egrError + 100) * 128 / 100) + IntArray(3)
                0x6D -> intArrayOf(0x07) + word(s.railBar * 10 + 30) + word(s.railBar * 10) + byte(45.0 + 40) + IntArray(5)
                0x6F -> intArrayOf(0x01) + byte(s.baroKpa - 1) + IntArray(1)
                0x70 -> intArrayOf(0x03) + word((s.mapKpa + 3) * 32) + word(s.mapKpa * 32) + IntArray(5)
                0x71 -> intArrayOf(0x03) + percent(85 - s.load * 0.5) + percent(83 - s.load * 0.5) + IntArray(3)
                0x74 -> intArrayOf(0x01) + word((20_000 + boost * 1100) / 10) + IntArray(2)
                0x75 -> intArrayOf(0x0F) + byte(s.ambient + 5 + 40) + byte(s.intakeTemp + 55 + 40) +
                    wideTemp(egt + 60) + wideTemp(egt)
                0x77 -> intArrayOf(0x01) + byte(s.intakeTemp + 40) + IntArray(3)
                0x73 -> intArrayOf(0x01) + word((s.baroKpa + s.load * 0.6) * 100) + IntArray(2)
                0x78 -> intArrayOf(0x0F) + wideTemp(egt) + wideTemp(egt - 70) + wideTemp(egt - 90) + wideTemp(egt - 130)
                0x7A -> intArrayOf(0x07) + word(filterDrop * 100) + word((s.baroKpa + filterDrop) * 100) +
                    word(s.baroKpa * 100)
                0x7C -> intArrayOf(0x03) + wideTemp(egt - 90) + wideTemp(egt - 120) + IntArray(4)
                0x7F -> intArrayOf(0x01) + long(4200.0 * 3600 + seconds) + IntArray(8)
                0x83 -> intArrayOf(0x01) + word(40 + s.load * 3) + IntArray(6)
                0xA6 -> long(214_530.0 * 10)
                else -> null
            }
        }

        fun byte(value: Double) = intArrayOf(value.roundToInt().coerceIn(0, 255))

        fun percent(value: Double) = byte(value * 255 / 100)

        fun word(value: Double): IntArray {
            val w = value.roundToInt().coerceIn(0, 0xFFFF)
            return intArrayOf(w shr 8, w and 0xFF)
        }

        fun long(value: Double): IntArray {
            val v = value.toLong()
            return intArrayOf((v shr 24).toInt() and 0xFF, (v shr 16).toInt() and 0xFF, (v shr 8).toInt() and 0xFF, v.toInt() and 0xFF)
        }

        fun wideTemp(celsius: Double) = word((celsius + 40) * 10)

        fun flat(codes: List<Pair<Int, Int>>): IntArray = codes.flatMap { listOf(it.first, it.second) }.toIntArray()

        fun ascii(text: String, length: Int) = IntArray(length) { if (it < text.length) text[it].code else 0 }

        fun hex(data: IntArray) = data.joinToString("") { "%02X".format(it) }

        fun isHex(text: String) = text.isNotEmpty() && text.all { it in '0'..'9' || it in 'A'..'F' }
    }
}
