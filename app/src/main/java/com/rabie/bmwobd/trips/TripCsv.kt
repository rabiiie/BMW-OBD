package com.rabie.bmwobd.trips

import com.rabie.bmwobd.obd.Pids
import java.util.Locale

/** Un trayecto en memoria: una columna por PID, con NaN donde no habia valor. */
class TripData(
    val columns: List<Int>,
    val tMs: LongArray,
    val series: Map<Int, DoubleArray>,
) {
    val size: Int get() = tMs.size
    val durationMs: Long get() = if (tMs.isEmpty()) 0 else tMs.last()
}

data class TripStats(
    val durationMs: Long,
    val distanceKm: Double?,
    val avgSpeed: Double?,
    val maxSpeed: Double?,
    val maxRpm: Double?,
    val maxBoostBar: Double?,
    val maxCoolant: Double?,
    val maxOil: Double?,
    val fuelLiters: Double?,
    val litersPer100Km: Double?,
)

/**
 * Formato del fichero de un trayecto: cabecera `t_ms,0C,0D,...` con los PIDs en hexadecimal y
 * una fila por vuelta de lectura con los milisegundos desde el inicio y el ultimo valor de cada PID.
 */
object TripCsv {

    private const val TIME_COLUMN = "t_ms"
    private const val MAX_GAP_MS = 5_000L
    private const val MS_PER_HOUR = 3_600_000.0
    private const val MIN_KM_FOR_CONSUMPTION = 0.5

    fun header(columns: List<Int>): String =
        TIME_COLUMN + columns.joinToString("") { ",%02X".format(it) }

    fun row(tMs: Long, columns: List<Int>, values: Map<Int, Double>): String {
        val line = StringBuilder().append(tMs)
        for (column in columns) {
            line.append(',')
            values[column]?.let { line.append(String.format(Locale.US, "%.2f", it)) }
        }
        return line.toString()
    }

    /** Las filas que no se entienden se saltan: la ultima puede estar a medias si la app murio. */
    fun parse(lines: Sequence<String>): TripData {
        val iterator = lines.iterator()
        if (!iterator.hasNext()) return TripData(emptyList(), LongArray(0), emptyMap())
        val columns = iterator.next().split(',').drop(1).mapNotNull { it.trim().toIntOrNull(16) }
        val times = ArrayList<Long>()
        val values = List(columns.size) { ArrayList<Double>() }
        while (iterator.hasNext()) {
            val cells = iterator.next().split(',')
            val t = cells[0].trim().toLongOrNull() ?: continue
            if (cells.size != columns.size + 1) continue
            times.add(t)
            for (i in columns.indices) values[i].add(cells[i + 1].toDoubleOrNull() ?: Double.NaN)
        }
        val series = columns.indices.associate { columns[it] to values[it].toDoubleArray() }
        return TripData(columns, times.toLongArray(), series)
    }

    fun stats(data: TripData): TripStats {
        val distance = integratePerHour(data, Pids.SPEED)
        val fuel = integratePerHour(data, Pids.FUEL_RATE)
        val hours = data.durationMs / MS_PER_HOUR
        return TripStats(
            durationMs = data.durationMs,
            distanceKm = distance,
            avgSpeed = distance?.takeIf { hours > 0 }?.let { it / hours },
            maxSpeed = max(data.series[Pids.SPEED]),
            maxRpm = max(data.series[Pids.RPM]),
            maxBoostBar = maxBoost(data),
            maxCoolant = max(data.series[Pids.COOLANT]),
            maxOil = max(data.series[Pids.OIL]),
            fuelLiters = fuel,
            litersPer100Km = if (fuel != null && distance != null && distance >= MIN_KM_FOR_CONSUMPTION) {
                fuel / distance * 100.0
            } else {
                null
            },
        )
    }

    /**
     * Suma una magnitud "por hora" (km/h, L/h) a lo largo del trayecto. Cada tramo vale lo que
     * marcaba la muestra anterior; un hueco largo entre muestras cuenta como mucho [MAX_GAP_MS].
     */
    private fun integratePerHour(data: TripData, pid: Int): Double? {
        val series = data.series[pid] ?: return null
        var total = 0.0
        var seen = false
        for (i in 1 until data.size) {
            val value = series[i - 1]
            if (value.isNaN()) continue
            seen = true
            val dt = (data.tMs[i] - data.tMs[i - 1]).coerceIn(0L, MAX_GAP_MS)
            total += value * dt / MS_PER_HOUR
        }
        return if (seen) total else null
    }

    private fun max(series: DoubleArray?): Double? =
        series?.filter { !it.isNaN() }?.maxOrNull()

    private fun maxBoost(data: TripData): Double? {
        val map = data.series[Pids.MAP] ?: return null
        val baro = data.series[Pids.BAROMETRIC] ?: return null
        var best: Double? = null
        for (i in 0 until data.size) {
            if (map[i].isNaN() || baro[i].isNaN()) continue
            val boost = (map[i] - baro[i]) / 100.0
            if (best == null || boost > best) best = boost
        }
        return best
    }
}
