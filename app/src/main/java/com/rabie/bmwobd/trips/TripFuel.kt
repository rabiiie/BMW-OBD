package com.rabie.bmwobd.trips

import com.rabie.bmwobd.obd.Pids
import java.util.Locale
import kotlin.math.abs

/**
 * Suma el gasoleo gastado a partir del contador de la centralita "gasoleo desde la ultima
 * regeneracion", que va de centesima en centesima de litro. En los dos trayectos del 12 de octubre
 * dio 3,59 y 3,84 L, lo mismo que bajo el deposito. Una regeneracion completa lo pone a cero: lo
 * que marque despues es lo gastado desde entonces. Un salto que no puede ser se descarta.
 */
class FuelCounter {

    private var last: Double? = null
    private var doubted: Double? = null

    var liters = 0.0
        private set

    /** Si el contador ha dado ya alguna lectura. */
    val seen: Boolean get() = last != null

    fun add(value: Double?) {
        if (value == null || value.isNaN()) return
        val before = last
        val pending = doubted
        doubted = null
        when {
            before == null -> last = value
            value >= before && value - before <= MAX_STEP -> count(value - before, value)
            value < before && value <= AFTER_RESET -> count(value, value)
            // Un salto que no puede ser es una lectura cruzada si viene solo. Si la lectura
            // siguiente dice lo mismo, era de verdad: el contador llevaba un rato sin leerse.
            pending != null && value >= pending && value - pending <= MAX_STEP ->
                count(if (value > before) value - before else value, value)
            else -> doubted = value
        }
    }

    private fun count(spent: Double, value: Double) {
        liters += spent
        last = value
    }

    private companion object {
        const val MAX_STEP = 1.0
        const val AFTER_RESET = 1.0
    }
}

/** Una parte del trayecto por su velocidad, con lo que se anduvo y lo que se gasto en ella. */
data class SpeedBand(val name: String, val km: Double, val liters: Double) {
    val per100: Double? get() = if (km >= MIN_BAND_KM) liters / km * 100.0 else null

    private companion object {
        const val MIN_BAND_KM = 1.0
    }
}

/** El consumo a una velocidad mantenida: [seconds] es el rato que se llevo asi. */
data class SteadySpeed(val speed: Int, val per100: Double, val seconds: Long)

/**
 * El gasto de un trayecto. [real] dice que los litros salen del contador de la centralita; si no,
 * son la estimacion de la app. El reparto por velocidades usa la estimacion fila a fila, ajustada
 * para que su suma de los litros del contador.
 */
data class TripFuel(
    val liters: Double,
    val km: Double,
    val real: Boolean,
    val estimatedLiters: Double?,
    val bands: List<SpeedBand>,
    val steady: List<SteadySpeed>,
) {
    val per100: Double? get() = if (km >= MIN_KM) liters / km * 100.0 else null

    fun cost(price: Double?): Double? = price?.let { liters * it }

    /** Los datos, ya en texto, para la ficha del trayecto. Sin [price] no hay cuenta en euros. */
    fun facts(price: Double?): List<Pair<String, String>> {
        val mark = if (real) "" else "≈ "
        val lines = ArrayList<Pair<String, String>>()
        lines += "Gasóleo gastado" to mark + n(liters, 2) + " L en " + n(km, 1) + " km" +
            if (real) ", según el contador de la centralita" else ", estimado por la app"
        per100?.let { lines += "Consumo medio" to mark + n(it, 1) + " L/100" }
        if (price != null) {
            lines += "Coste" to mark + n(liters * price, 2) + " €, a " + n(price, 3) + " €/L" +
                (per100?.let { " (" + n(it * price, 2) + " € cada 100 km)" } ?: "")
        } else {
            lines += "Coste" to "pon el precio del litro en Ajustes para verlo"
        }
        for (band in bands.filter { it.km >= 0.1 }) {
            val share = if (km > 0) band.km / km * 100.0 else 0.0
            lines += band.name to n(band.km, 1) + " km (" + n(share) + " %), " + n(band.liters, 2) + " L" +
                (band.per100?.let { ", " + n(it, 1) + " L/100" } ?: "")
        }
        if (steady.isNotEmpty()) {
            lines += "A velocidad mantenida" to steady.joinToString("; ") { "a ${it.speed}: ${n(it.per100, 1)} L/100 en ${(it.seconds + 30) / 60} min" } +
                ". Depende de la pendiente y del viento de esos ratos; con pocos minutos vale poco."
        }
        if (real && estimatedLiters != null && estimatedLiters > 0) {
            lines += "La estimación de la app" to "daba " + n(estimatedLiters, 2) + " L: " +
                signed((estimatedLiters / liters - 1) * 100.0) + " % respecto al contador"
        }
        return lines
    }

    private fun n(value: Double, decimals: Int = 0) = String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')

    private fun signed(value: Double) = n(value).let { if (value > 0 && it.any { c -> c in '1'..'9' }) "+$it" else it }

    companion object {

        /** El gasto de [data], o null si no hay ni contador ni con que estimar. */
        fun of(data: TripData, diesel: Boolean = true): TripFuel? {
            val speed = data.series[Pids.SPEED] ?: return null
            val counter = FuelCounter()
            data.series[FUEL_SINCE_REGEN]?.forEach(counter::add)
            val inputs = Pids.FUEL_INPUTS.mapNotNull { id -> data.series[id]?.let { id to it } }

            val bandKm = DoubleArray(BANDS.size)
            val bandLiters = DoubleArray(BANDS.size)
            val steadyKm = DoubleArray(STEADY.size)
            val steadyLiters = DoubleArray(STEADY.size)
            val steadyMs = LongArray(STEADY.size)
            val steadySince = LongArray(STEADY.size) { -1L }
            var km = 0.0
            var estimated = 0.0
            var rated = false
            for (i in 1 until data.size) {
                val dt = (data.tMs[i] - data.tMs[i - 1]).coerceIn(0L, MAX_GAP_MS)
                val now = speed[i - 1].takeIf { !it.isNaN() } ?: continue
                val values = HashMap<Int, Double>()
                for ((id, series) in inputs) series[i - 1].takeIf { !it.isNaN() }?.let { values[id] = it }
                val rate = Pids.fuelRate(values, diesel)
                if (rate != null) rated = true
                val rowKm = now * dt / MS_PER_HOUR
                val rowLiters = (rate ?: 0.0) * dt / MS_PER_HOUR
                km += rowKm
                estimated += rowLiters
                val band = BANDS.indexOfFirst { now <= it.second }
                bandKm[band] += rowKm
                bandLiters[band] += rowLiters
                STEADY.forEachIndexed { index, target ->
                    if (abs(now - target) > STEADY_MARGIN) {
                        steadySince[index] = -1L
                        return@forEachIndexed
                    }
                    if (steadySince[index] < 0) steadySince[index] = data.tMs[i - 1]
                    // Los primeros segundos no cuentan: todavia esta llegando a esa velocidad.
                    if (data.tMs[i - 1] - steadySince[index] >= STEADY_SETTLE_MS) {
                        steadyKm[index] += rowKm
                        steadyLiters[index] += rowLiters
                        steadyMs[index] += dt
                    }
                }
            }
            val real = counter.seen && counter.liters > 0
            if (!real && !rated) return null
            val liters = if (real) counter.liters else estimated
            // Lo estimado fila a fila se ajusta para que sume lo que dice el contador.
            val scale = if (real && estimated > 0) liters / estimated else 1.0
            return TripFuel(
                liters = liters,
                km = km,
                real = real,
                estimatedLiters = estimated.takeIf { rated },
                bands = BANDS.mapIndexed { index, (name, _) -> SpeedBand(name, bandKm[index], bandLiters[index] * scale) },
                steady = STEADY.indices.filter { steadyMs[it] >= STEADY_MIN_MS && steadyKm[it] > 0 }.map {
                    SteadySpeed(STEADY[it], steadyLiters[it] * scale / steadyKm[it] * 100.0, steadyMs[it] / 1000)
                },
            )
        }

        val FUEL_SINCE_REGEN = Pids.bmw(0x03E8)
        private val BANDS = listOf("Ciudad (hasta 50 km/h)" to 50.0, "Carretera (de 50 a 100)" to 100.0, "Autovía (más de 100)" to Double.MAX_VALUE)
        private val STEADY = listOf(90, 100, 110, 120)
        private const val STEADY_MARGIN = 3.0
        private const val STEADY_SETTLE_MS = 15_000L
        private const val STEADY_MIN_MS = 180_000L
        private const val MAX_GAP_MS = 5_000L
        private const val MS_PER_HOUR = 3_600_000.0
        private const val MIN_KM = 0.5
    }
}
