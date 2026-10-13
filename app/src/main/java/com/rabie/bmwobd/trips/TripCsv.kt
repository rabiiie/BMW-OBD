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
    /** El consumo sale de la estimacion con aire y lambda, no del caudal que da el coche. */
    val fuelEstimated: Boolean = false,
    /** Kilometros en los que habia dato de consumo; menos que la distancia si falto un rato. */
    val fuelKm: Double? = null,
)

/**
 * Formato del fichero de un trayecto: cabecera `t_ms,0C,0D,...` con los PIDs en hexadecimal y
 * una fila por vuelta de lectura con los milisegundos desde el inicio y el ultimo valor de cada PID.
 */
/**
 * El desfase de la distribucion en un trayecto: la mediana de lo leido, entre que valores se
 * movio y a que temperatura arranco el motor, para comparar frio con frio y caliente con caliente.
 */
data class TimingOffset(val median: Double, val min: Double, val max: Double, val startCoolant: Double?) {
    /** Si todas las lecturas del trayecto dieron lo mismo, a la centesima. */
    val steady: Boolean get() = max - min < 0.01
}

/**
 * Lo que la centralita llevaba aprendido en un trayecto para compensar el desgaste. Todo en
 * desviacion respecto a una pieza nueva, salvo el enfriador, que es su rendimiento.
 */
data class Wear(
    val railValve: Double?,
    val lambda: Double?,
    val egrZero: Double?,
    val filterZero: Double?,
    val egrCooler: Double?,
    /** El tiempo de mas aprendido para cada inyector: una lista por cilindro, con sus tres puntos de presion. */
    val injectorTimes: List<List<Double?>> = emptyList(),
) {
    val empty: Boolean get() = listOf(railValve, lambda, egrZero, filterZero, egrCooler).all { it == null } && largestInjectorTime == null

    /** El mayor tiempo aprendido de un inyector, sin signo: el que mas ha habido que corregir. */
    val largestInjectorTime: Double? get() = injectorTimes.flatten().filterNotNull().maxOfOrNull { kotlin.math.abs(it) }
}

object TripCsv {

    /** El desgaste aprendido que quedo grabado en un trayecto; el enfriador, por su mediana. */
    fun wear(data: TripData): Wear? {
        fun last(id: Int) = data.series[id]?.lastOrNull { !it.isNaN() }
        val cooler = whileRunning(data, Pids.BMW_EGR_COOLER).filter { it > 0.0 && it < 2.0 }.sorted()
        val wear = Wear(
            railValve = last(Pids.BMW_RAIL_VALVE_LEARNED),
            lambda = last(Pids.BMW_LAMBDA_LEARNED),
            egrZero = last(Pids.BMW_EGR_ZERO_LAST),
            filterZero = last(Pids.BMW_FILTER_ZERO),
            egrCooler = cooler.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
            injectorTimes = (0..3).map { cylinder -> Pids.BMW_INJECTOR_TIMES.map { point -> last(point[cylinder]) } },
        )
        return wear.takeUnless { it.empty }
    }

    /**
     * El desfase de la distribucion de un trayecto, o null si no se leyo. La mediana hace que una
     * lectura suelta equivocada no cuente, y lo que no puede ser un desfase se descarta antes.
     */
    fun timing(data: TripData): TimingOffset? {
        val read = whileRunning(data, TIMING_OFFSET).filter { it > -MAX_TIMING_OFFSET && it < MAX_TIMING_OFFSET }
        if (read.isEmpty()) return null
        val sorted = read.sorted()
        val coolant = data.series[Pids.COOLANT]?.firstOrNull { !it.isNaN() }
        return TimingOffset(sorted[sorted.size / 2], sorted.first(), sorted.last(), coolant)
    }

    /**
     * Lo leido de una medida con el motor en marcha. Con el motor parado el desfase da 0 y el
     * enfriador 1, que no son medidas (visto en el coche el 13 de octubre). Como cada fila repite
     * lo ultimo leido, lo que venia de motor parado tampoco cuenta hasta que cambia, o hasta que
     * pasa un minuto en marcha y ya tiene que ser una lectura nueva. Sin revoluciones grabadas no
     * se puede saber y vale todo.
     */
    private fun whileRunning(data: TripData, id: Int): List<Double> {
        val series = data.series[id] ?: return emptyList()
        val rpm = data.series[Pids.RPM] ?: return series.filter { !it.isNaN() }
        val read = ArrayList<Double>()
        var startedMs = -1L
        var stopped = Double.NaN
        for (i in 0 until data.size) {
            val value = series[i]
            if (!(rpm[i] >= RUNNING_RPM)) {
                startedMs = -1L
                if (!value.isNaN()) stopped = value
                continue
            }
            if (startedMs < 0) startedMs = data.tMs[i]
            if (value.isNaN()) continue
            if (value == stopped && data.tMs[i] - startedMs < STALE_AFTER_START_MS) continue
            stopped = Double.NaN
            read += value
        }
        return read
    }

    private const val RUNNING_RPM = 400.0
    private const val STALE_AFTER_START_MS = 60_000L
    private val TIMING_OFFSET = Pids.bmw(Pids.BMW_TIMING_OFFSET_ADDRESS)
    private const val MAX_TIMING_OFFSET = 30.0

    private const val TIME_COLUMN = "t_ms"
    private const val MAX_GAP_MS = 5_000L
    private const val MS_PER_HOUR = 3_600_000.0
    private const val MIN_KM_FOR_CONSUMPTION = 0.5
    private const val FULL_COVERAGE = 0.95

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

    fun stats(data: TripData, diesel: Boolean = true): TripStats {
        val distance = integratePerHour(data, Pids.SPEED)
        val fuel = fuel(data, diesel)
        val whole = fuel != null && (distance == null || fuel.km >= distance * FULL_COVERAGE)
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
            fuelLiters = fuel?.liters?.takeIf { whole },
            litersPer100Km = fuel?.takeIf { it.km >= MIN_KM_FOR_CONSUMPTION }?.let { it.liters / it.km * 100.0 },
            fuelEstimated = fuel != null && Pids.FUEL_RATE !in data.series && Pids.BMW_INJECTION !in data.series,
            fuelKm = fuel?.km,
        )
    }

    private class Fuel(val liters: Double, val km: Double)

    /**
     * Combustible gastado y kilometros hechos mientras habia dato de consumo. Los tramos sin dato
     * (la sonda lambda fria, si el consumo es estimado) no cuentan en ninguno de los dos.
     */
    private fun fuel(data: TripData, diesel: Boolean): Fuel? {
        val inputs = Pids.FUEL_INPUTS.mapNotNull { id -> data.series[id]?.let { id to it } }
        val speed = data.series[Pids.SPEED]
        var liters = 0.0
        var km = 0.0
        var seen = false
        for (i in 1 until data.size) {
            val values = HashMap<Int, Double>()
            for ((id, series) in inputs) series[i - 1].takeIf { !it.isNaN() }?.let { values[id] = it }
            val rate = Pids.fuelRate(values, diesel) ?: continue
            seen = true
            val dt = (data.tMs[i] - data.tMs[i - 1]).coerceIn(0L, MAX_GAP_MS)
            liters += rate * dt / MS_PER_HOUR
            speed?.get(i - 1)?.takeIf { !it.isNaN() }?.let { km += it * dt / MS_PER_HOUR }
        }
        return if (seen) Fuel(liters, km) else null
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
