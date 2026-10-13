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
data class EngineStart(val seconds: Double, val restVolts: Double?, val coolant: Double?)

/**
 * La presion del turbo frente a la que pide la centralita, en los ratos en que las dos estan
 * quietas. [median] negativa es que da menos de lo pedido; [low] es el decil mas bajo.
 */
data class Boost(val median: Double, val low: Double, val samples: Int)

/** Lo que un trayecto dice de la salud del coche. Lo que no se pudo medir queda en null. */
data class TripHealth(val warmUp: WarmUp?, val start: EngineStart?, val boost: Boost?) {

    /** Los datos, ya en texto, para la ficha del trayecto. */
    fun facts(): List<Pair<String, String>> = listOfNotNull(
        warmUp?.minutesTo80?.let {
            "Calentamiento: de ${n(warmUp.startCoolant)} °C a 80 °C" to
                n(it, 1) + " min" + (warmUp.outside?.let { out -> ", con ${n(out)} °C fuera" } ?: "")
        },
        warmUp?.steady?.let { "Temperatura del motor ya caliente (mediana)" to n(it) + " °C" },
        start?.let { "Arranque: de parado a ralentí" to "≈ " + n(it.seconds, 1) + " s" + (it.coolant?.let { c -> ", motor a ${n(c)} °C" } ?: "") },
        start?.restVolts?.let { "Batería antes de arrancar, con el contacto puesto" to n(it, 1) + " V" },
        boost?.let {
            "Turbo: presión real menos la pedida" to
                "${signed(it.median)} kPa de mediana, ${signed(it.low)} en el peor décimo (${it.samples} lecturas)"
        },
        "Cómo leerlo".takeIf { warmUp != null || start != null || boost != null }?.let {
            it to "no hay límites de fábrica: vale por comparación con los trayectos anteriores de este mismo coche"
        },
    )

    private fun n(value: Double, decimals: Int = 0) = String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')

    private fun signed(value: Double) = n(value, 1).let { if (value > 0 && it.any { c -> c in '1'..'9' }) "+$it" else it }

    companion object {

        /** Lo que se puede sacar de [data], o null si no da para nada de esto. */
        fun of(data: TripData): TripHealth? {
            val health = TripHealth(warmUp(data), start(data), boost(data))
            return health.takeIf { it.warmUp != null || it.start != null || it.boost != null }
        }

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
            return EngineStart(seconds, volts, coolant)
        }

        private fun boost(data: TripData): Boost? {
            val asked = data.series[BOOST_ASKED] ?: return null
            val real = data.series[MAP] ?: return null
            val gaps = ArrayList<Double>()
            for (last in BOOST_WINDOW - 1 until data.size) {
                var askedMin = Double.MAX_VALUE
                var askedMax = -Double.MAX_VALUE
                var realMin = Double.MAX_VALUE
                var realMax = -Double.MAX_VALUE
                var whole = true
                for (i in last - BOOST_WINDOW + 1..last) {
                    if (asked[i].isNaN() || real[i].isNaN()) {
                        whole = false
                        break
                    }
                    askedMin = minOf(askedMin, asked[i])
                    askedMax = maxOf(askedMax, asked[i])
                    realMin = minOf(realMin, real[i])
                    realMax = maxOf(realMax, real[i])
                }
                // Solo cuando sopla y las dos llevan un rato quietas: en un aceleron la pedida, que
                // se lee menos seguido, va por detras y la diferencia no dice nada.
                if (whole && askedMin > BOOSTING_KPA && askedMax - askedMin < BOOST_STILL_KPA && realMax - realMin < BOOST_STILL_KPA) {
                    gaps += real[last] - asked[last]
                }
            }
            if (gaps.size < BOOST_MIN_SAMPLES) return null
            gaps.sort()
            return Boost(gaps[gaps.size / 2], gaps[gaps.size / 10], gaps.size)
        }

        private const val OUTSIDE = 0x46
        private const val MODULE_VOLTS = 0x42
        private const val MAP = 0x0B
        private val BOOST_ASKED = Pids.bmw(0x01F4)
        private const val RUNNING_RPM = 400.0
        private const val IDLE_RPM = 800.0
        private const val MAX_START_SECONDS = 15.0
        private const val COLD_BELOW = 60.0
        private const val HOT = 80.0
        private const val STEADY_AFTER_MS = 15 * 60_000L
        private const val STEADY_MIN_MS = 5 * 60_000L
        private const val BOOST_WINDOW = 5
        private const val BOOSTING_KPA = 130.0
        private const val BOOST_STILL_KPA = 5.0
        private const val BOOST_MIN_SAMPLES = 30
    }
}
