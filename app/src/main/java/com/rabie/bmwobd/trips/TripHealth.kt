package com.rabie.bmwobd.trips

import com.rabie.bmwobd.obd.Pids
import java.util.Locale

/**
 * Como se calento el motor. [minutesTo80] solo existe si arranco frio; [steady] es la temperatura
 * en la que se quedo, la mediana pasado un cuarto de hora de marcha.
 */
data class WarmUp(val startCoolant: Double, val outside: Double?, val minutesTo80: Double?, val steady: Double?)

/**
 * El primer arranque grabado. [seconds] va de la ultima lectura con el motor parado a la primera
 * ya a ralenti: con dos lecturas por segundo, tiene medio segundo de margen.
 */
data class EngineStart(val seconds: Double, val restVolts: Double?, val coolant: Double?, val parkedMinutes: Double? = null)

/**
 * Lo que da el coche frente a lo que pide la centralita, en los ratos en que las dos cosas estan
 * quietas. [median] negativa es que da menos de lo pedido; [low] es el decil mas bajo.
 */
data class Gap(val median: Double, val low: Double, val samples: Int)

/** El ralenti con el motor caliente y el coche parado: a cuantas vueltas va y cuanto baila (del 5 % al 95 % de las lecturas). */
data class Idle(val rpm: Double, val spread: Double, val samples: Int)

/** La tension con el motor en marcha: la mediana y el decil mas bajo. */
data class Charge(val volts: Double, val low: Double)

/**
 * El filtro de particulas: la presion que le cuesta al escape pasar por el a ralenti en caliente,
 * y los kilometros que hubo entre las dos ultimas regeneraciones.
 */
data class FilterCheck(val idlePressure: Double?, val lastInterval: Double?)

/** Lo que un trayecto dice de la salud del coche. Lo que no se pudo medir queda en null. */
data class TripHealth(
    val warmUp: WarmUp?,
    val start: EngineStart?,
    val boost: Gap?,
    val rail: Gap? = null,
    val egr: Gap? = null,
    val idle: Idle? = null,
    val charge: Charge? = null,
    val filter: FilterCheck? = null,
) {

    private val empty: Boolean
        get() = listOf(warmUp, start, boost, rail, egr, idle, charge, filter).all { it == null }

    /** Los datos, ya en texto, para la ficha del trayecto. */
    fun facts(): List<Pair<String, String>> = listOfNotNull(
        warmUp?.minutesTo80?.let {
            "Calentamiento: de ${n(warmUp.startCoolant)} °C a 80 °C" to
                n(it, 1) + " min" + (warmUp.outside?.let { out -> ", con ${n(out)} °C fuera" } ?: "")
        },
        warmUp?.steady?.let { "Temperatura del motor ya caliente (mediana)" to n(it) + " °C" },
        start?.let { "Arranque: de parado a ralentí" to "≈ " + n(it.seconds, 1) + " s" + (it.coolant?.let { c -> ", motor a ${n(c)} °C" } ?: "") },
        start?.parkedMinutes?.let { "Tiempo parado antes de este arranque" to parked(it) },
        start?.restVolts?.let { "Batería antes de arrancar, con el contacto puesto" to n(it, 1) + " V" },
        boost?.let {
            "Turbo: presión real menos la pedida" to
                "${signed(it.median)} kPa de mediana, ${signed(it.low)} en el peor décimo (${it.samples} lecturas)"
        },
        rail?.let {
            "Raíl: presión real menos la pedida" to
                "${signed(it.median)} bar de mediana, ${signed(it.low)} en el peor décimo (${it.samples} lecturas)"
        },
        egr?.let { "EGR: lo que abre de más o de menos sobre lo mandado" to "${signed(it.median)} % de mediana (${it.samples} lecturas)" },
        idle?.let { "Ralentí en caliente" to "${n(it.rpm)} rpm, baila ${n(it.spread)} rpm (${it.samples} lecturas)" },
        charge?.let { "Tensión con el motor en marcha" to "${n(it.volts, 1)} V de mediana, ${n(it.low, 1)} V en el décimo más bajo" },
        filter?.idlePressure?.let { "Filtro de partículas: presión a ralentí en caliente" to n(it) + " hPa" },
        filter?.lastInterval?.let { "Filtro de partículas: entre las dos últimas regeneraciones" to n(it) + " km" },
        "Cómo leerlo".takeIf { !empty }?.let {
            it to "no hay límites de fábrica: vale por comparación con los trayectos anteriores de este mismo coche"
        },
    )

    private fun parked(minutes: Double) = when {
        minutes < 120 -> n(minutes) + " min"
        minutes < 48 * 60 -> n(minutes / 60, 1) + " h"
        else -> n(minutes / 1440, 1) + " días"
    }

    private fun n(value: Double, decimals: Int = 0) = String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')

    private fun signed(value: Double) = n(value, 1).let { if (value > 0 && it.any { c -> c in '1'..'9' }) "+$it" else it }

    companion object {

        /** Lo que se puede sacar de [data], o null si no da para nada de esto. */
        fun of(data: TripData): TripHealth? {
            val health = TripHealth(
                warmUp = warmUp(data),
                start = start(data),
                boost = gap(data, BOOST_ASKED, MAP, BOOST_STILL_KPA) { BOOSTING_KPA },
                rail = gap(data, RAIL_ASKED, RAIL, RAIL_STILL_BAR) { 0.0 },
                egr = egr(data),
                idle = idle(data),
                charge = charge(data),
                filter = filter(data),
            )
            return health.takeUnless { it.empty }
        }

        /** Las filas con el coche parado desde hace unos segundos, el motor caliente a ralenti y sin regenerar. */
        private fun warmIdleRows(data: TripData): List<Int> {
            val rpm = data.series[Pids.RPM] ?: return emptyList()
            val speed = data.series[Pids.SPEED] ?: return emptyList()
            val coolant = data.series[Pids.COOLANT] ?: return emptyList()
            val regen = data.series[Pids.BMW_REGEN_REQUEST]
            val rows = ArrayList<Int>()
            var stoppedSince = -1L
            for (i in 0 until data.size) {
                if (speed[i] != 0.0) {
                    stoppedSince = -1L
                    continue
                }
                if (stoppedSince < 0) stoppedSince = data.tMs[i]
                if (data.tMs[i] - stoppedSince < IDLE_SETTLE_MS) continue
                val regenerating = regen != null && regen[i] >= Pids.BMW_REGEN_ACTIVE
                if (coolant[i] >= HOT && rpm[i] >= IDLE_FROM && rpm[i] <= IDLE_TO && !regenerating) rows += i
            }
            return rows
        }

        private fun idle(data: TripData): Idle? {
            val rpm = data.series[Pids.RPM] ?: return null
            val read = warmIdleRows(data).map { rpm[it] }.sorted()
            if (read.size < MIN_LONG_SAMPLES) return null
            return Idle(read[read.size / 2], at(read, 0.95) - at(read, 0.05), read.size)
        }

        private fun filter(data: TripData): FilterCheck? {
            val pressure = data.series[FILTER_PRESSURE]
            val atIdle = if (pressure == null) emptyList() else warmIdleRows(data).map { pressure[it] }.filter { !it.isNaN() }.sorted()
            val marks = Pids.BMW_REGENERATION_ADDRESSES.take(2).map { address ->
                data.series[Pids.bmw(address)]?.lastOrNull { !it.isNaN() }
            }
            val interval = marks[0]?.let { newer -> marks[1]?.let { older -> newer - older } }?.takeIf { it > 0 }
            val check = FilterCheck(atIdle.takeIf { it.size >= MIN_SAMPLES }?.let { it[it.size / 2] }, interval)
            return check.takeIf { it.idlePressure != null || it.lastInterval != null }
        }

        /** Pasado minuto y medio desde que el motor gira: antes, la tension leida puede ser todavia la de parado. */
        private fun charge(data: TripData): Charge? {
            val rpm = data.series[Pids.RPM] ?: return null
            val volts = data.series[MODULE_VOLTS] ?: return null
            val read = ArrayList<Double>()
            var since = -1L
            for (i in 0 until data.size) {
                if (!(rpm[i] >= RUNNING_RPM)) {
                    since = -1L
                    continue
                }
                if (since < 0) since = data.tMs[i]
                if (data.tMs[i] - since >= CHARGE_AFTER_MS && !volts[i].isNaN()) read += volts[i]
            }
            if (read.size < MIN_LONG_SAMPLES) return null
            read.sort()
            return Charge(read[read.size / 2], at(read, 0.1))
        }

        /** El error de la EGR que da el coche, solo mientras se le manda abrir y el mando lleva un rato quieto. */
        private fun egr(data: TripData): Gap? {
            val rpm = data.series[Pids.RPM] ?: return null
            val sent = data.series[EGR_SENT] ?: return null
            val error = data.series[EGR_ERROR] ?: return null
            val read = ArrayList<Double>()
            for (last in WINDOW - 1 until data.size) {
                if (!(rpm[last] >= RUNNING_RPM) || error[last].isNaN()) continue
                var low = Double.MAX_VALUE
                var high = -Double.MAX_VALUE
                var whole = true
                for (i in last - WINDOW + 1..last) {
                    if (sent[i].isNaN()) {
                        whole = false
                        break
                    }
                    low = minOf(low, sent[i])
                    high = maxOf(high, sent[i])
                }
                if (whole && low >= EGR_OPEN && high - low < EGR_STILL) read += error[last]
            }
            if (read.size < MIN_SAMPLES) return null
            read.sort()
            return Gap(read[read.size / 2], at(read, 0.1), read.size)
        }

        private fun at(sorted: List<Double>, share: Double) = sorted[(sorted.size * share).toInt().coerceAtMost(sorted.size - 1)]

        private fun warmUp(data: TripData): WarmUp? {
            val coolant = data.series[Pids.COOLANT] ?: return null
            val rpm = data.series[Pids.RPM] ?: return null
            val running = (0 until data.size).firstOrNull { rpm[it] >= RUNNING_RPM } ?: return null
            val start = (running until data.size).firstOrNull { !coolant[it].isNaN() }?.let { coolant[it] } ?: return null
            val hot = if (start >= COLD_BELOW) null else (running until data.size).firstOrNull { coolant[it] >= HOT }
            val late = (running until data.size)
                .filter { data.tMs[it] - data.tMs[running] >= STEADY_AFTER_MS && !coolant[it].isNaN() }
                .map { coolant[it] }
                .sorted()
            val enough = late.isNotEmpty() && data.tMs.last() - data.tMs[running] >= STEADY_AFTER_MS + STEADY_MIN_MS
            val outside = data.series[OUTSIDE]?.firstOrNull { !it.isNaN() }
            if (hot == null && !enough) return null
            return WarmUp(
                startCoolant = start,
                outside = outside,
                minutesTo80 = hot?.let { (data.tMs[it] - data.tMs[running]) / 60_000.0 },
                steady = if (enough) late[late.size / 2] else null,
            )
        }

        private fun start(data: TripData): EngineStart? {
            val rpm = data.series[Pids.RPM] ?: return null
            val turning = (0 until data.size).firstOrNull { rpm[it] > 0.0 } ?: return null
            // Sin una lectura con el motor parado justo antes, el arranque no quedo grabado.
            val stopped = (turning - 1).takeIf { it >= 0 && rpm[it] == 0.0 } ?: return null
            val idling = (turning until data.size).firstOrNull { rpm[it] >= IDLE_RPM } ?: return null
            val seconds = (data.tMs[idling] - data.tMs[stopped]) / 1000.0
            if (seconds > MAX_START_SECONDS) return null
            val volts = (data.series[MODULE_VOLTS] ?: data.series[Pids.ADAPTER_VOLTAGE])?.get(stopped)?.takeIf { !it.isNaN() }
            val coolant = data.series[Pids.COOLANT]?.get(stopped)?.takeIf { !it.isNaN() }
            // Lo que diga la centralita en los minutos siguientes al arranque, por si con el motor
            // parado todavia daba el del arranque anterior.
            val parked = data.series[Pids.BMW_PARKED_MINUTES]?.let { series ->
                (0 until data.size).lastOrNull { !series[it].isNaN() && data.tMs[it] - data.tMs[idling] <= PARKED_WITHIN_MS }?.let { series[it] }
            }
            return EngineStart(seconds, volts, coolant, parked)
        }

        /**
         * La diferencia entre lo real y lo pedido, solo cuando las dos medidas llevan un rato
         * quietas (menos de [still] de vaiven) y lo pedido pasa de [from]: en un aceleron la
         * pedida, que se lee menos seguido, va por detras y la diferencia no dice nada.
         */
        private fun gap(data: TripData, askedId: Int, realId: Int, still: Double, from: () -> Double): Gap? {
            val asked = data.series[askedId] ?: return null
            val real = data.series[realId] ?: return null
            val rpm = data.series[Pids.RPM]
            val gaps = ArrayList<Double>()
            for (last in WINDOW - 1 until data.size) {
                if (rpm != null && !(rpm[last] >= RUNNING_RPM)) continue
                var askedMin = Double.MAX_VALUE
                var askedMax = -Double.MAX_VALUE
                var realMin = Double.MAX_VALUE
                var realMax = -Double.MAX_VALUE
                var whole = true
                for (i in last - WINDOW + 1..last) {
                    if (asked[i].isNaN() || real[i].isNaN()) {
                        whole = false
                        break
                    }
                    askedMin = minOf(askedMin, asked[i])
                    askedMax = maxOf(askedMax, asked[i])
                    realMin = minOf(realMin, real[i])
                    realMax = maxOf(realMax, real[i])
                }
                if (whole && askedMin > from() && askedMax - askedMin < still && realMax - realMin < still) {
                    gaps += real[last] - asked[last]
                }
            }
            if (gaps.size < MIN_SAMPLES) return null
            gaps.sort()
            return Gap(gaps[gaps.size / 2], gaps[gaps.size / 10], gaps.size)
        }

        private const val OUTSIDE = 0x46
        private const val MODULE_VOLTS = 0x42
        private const val MAP = 0x0B
        private val BOOST_ASKED = Pids.bmw(0x01F4)
        private const val RUNNING_RPM = 400.0
        private const val IDLE_RPM = 800.0
        private const val MAX_START_SECONDS = 15.0
        private const val PARKED_WITHIN_MS = 180_000L
        private const val COLD_BELOW = 60.0
        private const val HOT = 80.0
        private const val STEADY_AFTER_MS = 15 * 60_000L
        private const val STEADY_MIN_MS = 5 * 60_000L
        private const val WINDOW = 5
        private const val BOOSTING_KPA = 130.0
        private const val BOOST_STILL_KPA = 5.0
        private const val RAIL_STILL_BAR = 20.0
        private const val MIN_SAMPLES = 30
        private const val MIN_LONG_SAMPLES = 60
        private const val RAIL = 0x23
        private val RAIL_ASKED = Pids.bmw(0x0641)
        private const val EGR_SENT = 0x2C
        private const val EGR_ERROR = 0x2D
        private const val EGR_OPEN = 5.0
        private const val EGR_STILL = 5.0
        private val FILTER_PRESSURE = Pids.bmw(0x0424)
        private const val IDLE_SETTLE_MS = 5_000L
        private const val IDLE_FROM = 600.0
        private const val IDLE_TO = 1100.0
        private const val CHARGE_AFTER_MS = 90_000L
    }
}
