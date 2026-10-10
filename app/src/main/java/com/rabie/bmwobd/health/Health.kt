package com.rabie.bmwobd.health

import com.rabie.bmwobd.advice.Limits
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.advice.TripFinding
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.trips.TripData
import com.rabie.bmwobd.trips.TripStats
import java.util.Locale

enum class HealthLevel { UNKNOWN, OK, WATCH, BAD }

enum class HealthSystem(val title: String) {
    FILTER("Filtro de partículas"),
    TURBO("Turbo"),
    INJECTION("Inyección"),
    COOLING("Refrigeración"),
    ELECTRIC("Batería y carga"),
    OIL("Aceite"),
    FAULTS("Averías guardadas"),
}

/**
 * Como esta un sistema del coche segun un trayecto, dicho sin jerga: una frase, los datos que la
 * sostienen y, si hay algo que mirar, por donde empezar. Es una pista, no un diagnostico.
 */
data class HealthCard(
    val system: HealthSystem,
    val level: HealthLevel,
    val headline: String,
    val facts: List<String>,
    val advice: String? = null,
)

/** Una regeneracion del filtro vista en un trayecto. [finished] es null si no se puede saber. */
data class Regeneration(val startMs: Long, val seconds: Long, val finished: Boolean?)

object Health {

    private const val MAX_GAP_MS = 5_000L
    private const val MIN_REGEN_SECONDS = 120L
    private const val REGEN_RESET_KM = 1.0
    private const val REGEN_PAUSE_MS = 4 * 60_000L
    private const val STILL_ACTIVE_MS = 90_000L
    private const val MIN_SOOT_SECONDS = 300L
    private const val MIN_BOOST_SECONDS = 20L
    private const val BOOST_ASKED_OVER_BARO_KPA = 30.0
    private const val MIN_RAIL_SECONDS = 60L
    private const val RUNNING_RPM = 400.0
    private const val FILTER_DISTANCE = 0x03EB
    private const val BOOST_ASKED = 0x01F4
    private const val RAIL_ASKED = 0x0641
    private const val ENGINE_HOURS = 0x0AF0
    private const val FILTER_REGENERATIONS = 0x0407
    private const val FILTER_KM = 0x0BA5
    private const val FILTER_ASH = 0x03E9
    private const val FILTER_USUAL_INTERVAL = 0x03F3
    private const val FILTER_TEMP_SENSOR = 0x042E
    private const val SENSOR_OPEN_BELOW = -35.0
    private const val RECENT_REGENERATIONS = 3
    private const val SHORT_INTERVAL_SHARE = 0.5
    private const val OIL_CHANGE_KM = 30_000.0

    private class Rows(val data: TripData) {
        fun at(id: Int, i: Int): Double? = data.series[id]?.get(i)?.takeIf { !it.isNaN() }
        fun dt(i: Int): Long = if (i == 0) 0L else (data.tMs[i] - data.tMs[i - 1]).coerceIn(0L, MAX_GAP_MS)
        fun running(i: Int) = (at(Pids.RPM, i) ?: 0.0) > RUNNING_RPM
        fun exhaust(i: Int): Double? = Moment.EXHAUST_TEMPS.mapNotNull { at(it, i) }.maxOrNull()
        fun demanding(i: Int) = (at(Pids.LOAD, i) ?: 0.0) >= Limits.PULL_MIN_LOAD &&
            (at(Moment.EGR_COMMANDED, i) ?: 0.0) < Limits.EGR_MIN_COMMANDED
    }

    /** Valores con el tiempo que estuvo cada uno, para sacar medianas que no dependan del ritmo de lectura. */
    private class Weighted {
        private val items = ArrayList<Pair<Double, Long>>()
        var millis = 0L
            private set

        fun add(value: Double?, dt: Long) {
            if (value == null || dt <= 0) return
            items += value to dt
            millis += dt
        }

        val seconds: Long get() = millis / 1000

        fun median(): Double? {
            if (items.isEmpty()) return null
            var passed = 0L
            for ((value, dt) in items.sortedBy { it.first }) {
                passed += dt
                if (passed * 2 >= millis) return value
            }
            return items.last().first
        }

        fun min(): Double? = items.minOfOrNull { it.first }
        fun max(): Double? = items.maxOfOrNull { it.first }
    }

    fun cards(data: TripData, findings: List<TripFinding>): List<HealthCard> = listOfNotNull(
        filter(data),
        turbo(data),
        injection(data),
        cooling(data, findings),
        electric(data, findings),
        oil(data),
        faults(data),
    )

    /** Los datos fijos del coche que la centralita da al conectar: etiqueta y valor ya escritos. */
    fun carFacts(data: TripData): List<Pair<String, String>> {
        val km = data.last(Pids.bmw(Pids.BMW_ODOMETER_ADDRESS))
        val fuel = data.last(Pids.bmw(Pids.BMW_TOTAL_FUEL_ADDRESS))
        return listOfNotNull(
            km?.let { "Kilómetros" to thousands(it) + " km" },
            data.last(Pids.bmw(ENGINE_HOURS))?.let { "Horas de motor" to thousands(it) + " h" },
            if (km != null && fuel != null && km > 1000) {
                "Consumo medio de toda su vida" to n(fuel / km * 100, 1) + " L/100 (" + thousands(fuel) + " L)"
            } else {
                null
            },
            data.last(Pids.bmw(Pids.BMW_TANK_ADDRESS))?.let { "Depósito al acabar" to n(it, 1) + " L" },
            data.last(Pids.bmw(FILTER_REGENERATIONS))?.let { "Regeneraciones del filtro en su vida" to thousands(it) },
            data.last(Pids.bmw(FILTER_KM))?.let { "Km con este filtro de partículas, según el coche" to thousands(it) + " km" },
        )
    }

    private fun TripData.last(id: Int): Double? = series[id]?.lastOrNull { !it.isNaN() }

    /** Los kilometros entre cada regeneracion y la anterior, de la mas reciente a la mas antigua. */
    private fun regenerationIntervals(data: TripData): List<Double> {
        val marks = Pids.BMW_REGENERATION_ADDRESSES.map { data.last(Pids.bmw(it)) }
        return marks.zipWithNext().mapNotNull { (newer, older) ->
            if (newer == null || older == null) null else (newer - older).takeIf { it > 0 }
        }
    }

    private fun oil(data: TripData): HealthCard? {
        val km = data.last(Pids.bmw(Pids.BMW_OIL_KM_ADDRESS))
        val level = data.last(Pids.bmw(Pids.BMW_OIL_LEVEL_ADDRESS))
        if (km == null && level == null) return null
        val facts = listOfNotNull(
            km?.let { "Lleva ${thousands(it)} km desde el cambio." },
            level?.let { "Nivel que mide el coche: ${n(it)} mm." },
            "Este motor no da temperatura de aceite: la que publica la centralita repite la del refrigerante.",
        )
        return if (km != null && km >= OIL_CHANGE_KM) {
            HealthCard(
                HealthSystem.OIL, HealthLevel.WATCH, "Toca cambiar el aceite.", facts,
                "Con muchos kilómetros el aceite pierde protección, y en este motor el aceite gastado acorta la vida de la cadena.",
            )
        } else {
            HealthCard(HealthSystem.OIL, HealthLevel.OK, "Sin indicios.", facts)
        }
    }

    private fun faults(data: TripData): HealthCard? {
        val count = data.last(Pids.bmw(Pids.BMW_FAULTS_ADDRESS)) ?: return null
        if (count < 1) return HealthCard(HealthSystem.FAULTS, HealthLevel.OK, "La centralita del motor no guarda ninguna.", emptyList())
        return HealthCard(
            HealthSystem.FAULTS, HealthLevel.WATCH,
            "La centralita del motor guarda ${n(count)} que la lectura de averías normal no enseña.",
            listOf("Son averías de la memoria propia de BMW. Pueden ser antiguas y estar ya resueltas."),
            "El escaneo las lee al llegar a esa consulta. Hasta entonces no se sabe cuáles son.",
        )
    }

    /** Dos o tres frases que cuentan el trayecto. */
    fun summary(data: TripData, stats: TripStats, cards: List<HealthCard>): List<String> {
        val lines = mutableListOf<String>()
        val minutes = stats.durationMs / 60_000
        val distance = stats.distanceKm?.takeIf { it >= 0.1 }
        val consumption = stats.litersPer100Km?.let {
            ", a ${n(it, 1)} L/100" + if (stats.fuelEstimated) " (estimado)" else ""
        }.orEmpty()
        lines += if (distance != null) "${n(distance, 1)} km en $minutes min$consumption." else "$minutes min sin moverse."

        regeneration(data)?.let { regen ->
            val length = "durante unos ${(regen.seconds + 30) / 60} min"
            lines += when (regen.finished) {
                true -> "El filtro de partículas regeneró $length y terminó."
                false -> "El filtro de partículas estaba regenerando al apagar y no había terminado."
                null -> "Parece que el filtro de partículas regeneró $length."
            }
        }

        val toCheck = cards.filter { it.level == HealthLevel.WATCH || it.level == HealthLevel.BAD }
        lines += if (toCheck.isEmpty()) {
            "Nada fuera de lo normal en lo que se ha podido medir."
        } else {
            "A mirar: " + toCheck.joinToString(", ") { it.system.title.lowercase(Locale.ROOT) } + "."
        }
        return lines
    }

    /**
     * Regenerando, el escape pasa de 480 °C sin que se le pida fuerza al motor y con la mezcla mas
     * rica de lo normal. Si el coche da la distancia desde la ultima regeneracion, que vuelva a
     * cero dice que termino.
     */
    fun regeneration(data: TripData): Regeneration? {
        val rows = Rows(data)
        // Una regeneracion es un rato seguido con esas condiciones; se interrumpen en las
        // retenciones y en las paradas, asi que se juntan los tramos separados por pocos minutos.
        var best: IntArray? = null
        var bestMs = 0L
        var start = -1
        var last = -1
        var activeMs = 0L
        var reset = false
        var previousDistance: Double? = null
        fun close() {
            if (start >= 0 && activeMs > bestMs) {
                best = intArrayOf(start, last)
                bestMs = activeMs
            }
        }
        for (i in 0 until data.size) {
            val exhaust = rows.exhaust(i)
            val lambda = rows.at(Pids.LAMBDA, i)
            val rich = lambda == null || lambda in 0.5..Limits.REGEN_MAX_LAMBDA
            if (rows.running(i) && !rows.demanding(i) && exhaust != null && exhaust >= Limits.REGEN_EXHAUST && rich) {
                if (start < 0 || data.tMs[i] - data.tMs[last] > REGEN_PAUSE_MS) {
                    close()
                    start = i
                    activeMs = 0L
                }
                last = i
                activeMs += rows.dt(i)
            }
            val distance = rows.at(Pids.bmw(FILTER_DISTANCE), i)
            if (distance != null && previousDistance != null && previousDistance - distance > REGEN_RESET_KM) reset = true
            if (distance != null) previousDistance = distance
        }
        close()
        val (from, to) = best?.takeIf { bestMs / 1000 >= MIN_REGEN_SECONDS } ?: return null
        val stillActive = data.tMs.last() - data.tMs[to] < STILL_ACTIVE_MS
        val finished = when {
            reset -> true
            previousDistance != null -> if (stillActive) false else null
            else -> null
        }
        return Regeneration(data.tMs[from], (data.tMs[to] - data.tMs[from]) / 1000, finished)
    }

    private fun filter(data: TripData): HealthCard {
        val rows = Rows(data)
        val measuredId = Pids.BMW_SOOT_MEASURED
        val modelId = Pids.BMW_SOOT_MODEL
        val gap = Weighted()
        var measured: Double? = null
        var model: Double? = null
        var distance: Double? = null
        for (i in 0 until data.size) {
            val a = rows.at(measuredId, i)
            val b = rows.at(modelId, i)
            if (a != null && b != null && rows.running(i)) gap.add(a - b, rows.dt(i))
            measured = a ?: measured
            model = b ?: model
            distance = rows.at(Pids.bmw(FILTER_DISTANCE), i) ?: distance
        }
        val regen = regeneration(data)
        val intervals = regenerationIntervals(data)
        val usual = data.last(Pids.bmw(FILTER_USUAL_INTERVAL))
        val sensor = data.last(Pids.bmw(FILTER_TEMP_SENSOR))
        val facts = listOfNotNull(
            if (measured != null && model != null) {
                "Hollín al acabar: ${n(measured, 1)} g medidos por la presión y ${n(model, 1)} g calculados por el uso."
            } else {
                measured?.let { "Hollín al acabar: ${n(it, 1)} g." }
            },
            distance?.let { "Última regeneración: hace ${n(it, if (it < 10) 1 else 0)} km." },
            regen?.let {
                when (it.finished) {
                    true -> "Regeneró en este trayecto, unos ${(it.seconds + 30) / 60} min, y terminó."
                    false -> "Estaba regenerando al apagar el motor."
                    null -> "Parece que regeneró en este trayecto, unos ${(it.seconds + 30) / 60} min."
                }
            },
            if (intervals.isEmpty()) {
                null
            } else {
                "Las últimas regeneraciones llegaron cada " + intervals.joinToString(", ") { n(it) } +
                    " km, de la más reciente a la más antigua" + (usual?.let { "; su media guardada es ${n(it)} km." } ?: ".")
            },
            data.last(Pids.bmw(FILTER_ASH))?.let { "Ceniza que calcula la centralita: ${n(it)} g." },
            sensor?.takeIf { it <= SENSOR_OPEN_BELOW }?.let {
                "El sensor de temperatura antes del filtro marca ${n(it)} °C: desconectado, roto, o esta versión no lo monta."
            },
        )
        if (facts.isEmpty()) {
            return HealthCard(HealthSystem.FILTER, HealthLevel.UNKNOWN, "Este coche no da datos del filtro.", emptyList())
        }
        val medianGap = gap.median()?.takeIf { gap.seconds >= MIN_SOOT_SECONDS }
        val recent = intervals.take(RECENT_REGENERATIONS).sorted().let { if (it.size < 2) null else it[it.size / 2] }
        val ashAdvice = "Suele ser ceniza acumulada, que no se quema al regenerar y aparece con los kilómetros, o el sensor " +
            "de presión del filtro o sus tubos. En el taller: que lean la masa de ceniza y los valores del sensor."
        return when {
            recent != null && usual != null && usual > 0 && recent < usual * SHORT_INTERVAL_SHARE -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "Regenera mucho más seguido que su media.",
                facts,
                ashAdvice,
            )
            medianGap != null && medianGap >= Limits.SOOT_GAP_WARN -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "Se llena más deprisa de lo que calcula la centralita.",
                facts + "Durante el trayecto la presión veía ${n(medianGap)} g más de hollín que el cálculo.",
                ashAdvice,
            )
            regen?.finished == false -> HealthCard(
                HealthSystem.FILTER, HealthLevel.WATCH,
                "Se apagó el motor a media regeneración.",
                facts,
                "No pasa nada por una vez: lo volverá a intentar. Si se repite, el filtro se va cargando; " +
                    "conviene un trayecto de carretera de veinte minutos.",
            )
            else -> HealthCard(HealthSystem.FILTER, HealthLevel.OK, "Sin indicios.", facts)
        }
    }

    private fun turbo(data: TripData): HealthCard {
        val rows = Rows(data)
        val difference = Weighted()
        var peak: Double? = null
        for (i in 0 until data.size) {
            val actual = rows.at(Moment.BOOST_ACTUAL, i) ?: rows.at(Pids.MAP, i)
            val baro = rows.at(Pids.BAROMETRIC, i)
            if (actual != null && baro != null && (peak == null || actual - baro > peak)) peak = actual - baro
            val asked = rows.at(Moment.BOOST_COMMANDED, i) ?: rows.at(Pids.bmw(BOOST_ASKED), i)
            // Solo cuenta mientras se le pide soplar de verdad: al ralenti no hay nada que comparar.
            if (actual == null || asked == null || baro == null || asked < baro + BOOST_ASKED_OVER_BARO_KPA) continue
            difference.add(actual - asked, rows.dt(i))
        }
        val peakFact = peak?.takeIf { it > 0 }?.let { "Máximo del trayecto: ${n(it / 100, 2)} bar de soplado." }
        val median = difference.median()?.takeIf { difference.seconds >= MIN_BOOST_SECONDS }
            ?: return HealthCard(
                HealthSystem.TURBO, HealthLevel.UNKNOWN,
                if (peakFact == null) "Sin datos de turbo." else "En este trayecto no se le ha pedido lo bastante para juzgarlo.",
                listOfNotNull(peakFact),
            )
        val fact = "Cuando se le pide turbo da ${signed(median)} kPa respecto a lo pedido (${difference.seconds} s medidos)."
        val facts = listOfNotNull(fact, peakFact)
        return when {
            median <= -Limits.BOOST_DEFICIT_ALERT_KPA -> HealthCard(
                HealthSystem.TURBO, HealthLevel.BAD, "Da bastante menos presión de la que se le pide.", facts,
                "Falta aire: lo habitual es una fuga en los manguitos o el intercooler, el actuador del turbo o el propio turbo.",
            )
            median <= -Limits.BOOST_DEFICIT_WARN_KPA -> HealthCard(
                HealthSystem.TURBO, HealthLevel.WATCH, "Se queda algo corto respecto a lo que se le pide.", facts,
                "Conviene mirar manguitos y abrazaderas del circuito de aire antes de pensar en el turbo.",
            )
            median >= Limits.BOOST_EXCESS_ALERT_KPA -> HealthCard(
                HealthSystem.TURBO, HealthLevel.WATCH, "Da más presión de la que se le pide.", facts,
                "Puede ser el actuador o la geometría del turbo agarrada.",
            )
            else -> HealthCard(HealthSystem.TURBO, HealthLevel.OK, "Da la presión que se le pide.", facts)
        }
    }

    private fun injection(data: TripData): HealthCard {
        val rows = Rows(data)
        val deviation = Weighted()
        val bar = Weighted()
        for (i in 0 until data.size) {
            if (!rows.running(i)) continue
            val asked = rows.at(Moment.RAIL_COMMANDED, i) ?: rows.at(Pids.bmw(RAIL_ASKED), i)
            val actual = rows.at(Moment.RAIL_ACTUAL, i) ?: rows.at(Moment.RAIL, i)
            if (asked == null || actual == null || asked <= 0) continue
            deviation.add((actual - asked) / asked, rows.dt(i))
            bar.add(actual - asked, rows.dt(i))
        }
        val median = deviation.median()?.takeIf { deviation.seconds >= MIN_RAIL_SECONDS }
            ?: return HealthCard(HealthSystem.INJECTION, HealthLevel.UNKNOWN, "Sin datos de la presión de gasóleo pedida.", emptyList())
        val facts = listOf("La presión del raíl va a ${signed(bar.median() ?: 0.0)} bar de la pedida, de media.")
        val away = kotlin.math.abs(median)
        return when {
            away >= Limits.RAIL_DEVIATION_ALERT -> HealthCard(
                HealthSystem.INJECTION, HealthLevel.BAD, "La presión de gasóleo se aparta mucho de la pedida.", facts,
                "Lo habitual: filtro de gasóleo, bomba de alta, regulador de presión o un inyector que pierde.",
            )
            away >= Limits.RAIL_DEVIATION_WARN -> HealthCard(
                HealthSystem.INJECTION, HealthLevel.WATCH, "La presión de gasóleo no sigue bien a la pedida.", facts,
                "Empieza por lo barato: el filtro de gasóleo.",
            )
            else -> HealthCard(HealthSystem.INJECTION, HealthLevel.OK, "La presión de gasóleo sigue a la pedida.", facts)
        }
    }

    private fun cooling(data: TripData, findings: List<TripFinding>): HealthCard {
        val rows = Rows(data)
        var warmAt: Long? = null
        var operating = false
        val afterOperating = Weighted()
        var peak: Double? = null
        for (i in 0 until data.size) {
            val t = rows.at(Pids.COOLANT, i) ?: continue
            if (peak == null || t > peak) peak = t
            if (warmAt == null && t >= Limits.THERMOSTAT_MIN_COOLANT) warmAt = data.tMs[i]
            if (t >= Limits.OPERATING_COOLANT) operating = true
            if (operating) afterOperating.add(t, rows.dt(i))
        }
        val top = peak ?: return HealthCard(HealthSystem.COOLING, HealthLevel.UNKNOWN, "Sin datos de temperatura.", emptyList())
        val facts = listOfNotNull(
            warmAt?.let { if (it < 30_000) "Salió ya caliente." else "Llegó a ${n(Limits.THERMOSTAT_MIN_COOLANT)} °C en ${it / 60_000} min." },
            afterOperating.min()?.let { "Ya caliente se mantuvo entre ${n(it)} y ${n(afterOperating.max()!!)} °C." },
        )
        val hot = findings.worst("coolant_hot")
        val thermostat = findings.worst("thermostat") ?: findings.worst("thermostat_drop")
        return when {
            hot == Severity.ALERT -> HealthCard(
                HealthSystem.COOLING, HealthLevel.BAD, "Se ha calentado demasiado: ${n(top)} °C.", facts,
                "No lo fuerces. Mira el nivel de refrigerante en frío, el ventilador y que el radiador no esté tapado.",
            )
            hot != null -> HealthCard(
                HealthSystem.COOLING, HealthLevel.WATCH, "Ha subido más de lo habitual: ${n(top)} °C.", facts,
                "Mira el nivel de refrigerante en frío y si el ventilador entra.",
            )
            thermostat != null -> HealthCard(
                HealthSystem.COOLING, HealthLevel.WATCH, "No coge o no mantiene la temperatura.", facts,
                "Suele ser el termostato, que se queda abierto. Con el motor frío gasta más y el filtro no regenera.",
            )
            warmAt == null -> HealthCard(
                HealthSystem.COOLING, HealthLevel.UNKNOWN, "Trayecto corto: no llegó a calentar (${n(top)} °C).", facts,
            )
            else -> HealthCard(HealthSystem.COOLING, HealthLevel.OK, "Calienta y se mantiene.", facts)
        }
    }

    private fun electric(data: TripData, findings: List<TripFinding>): HealthCard {
        val rows = Rows(data)
        val volts = Weighted()
        for (i in 0 until data.size) {
            if (!rows.running(i)) continue
            volts.add(rows.at(Moment.MODULE_VOLTAGE, i) ?: rows.at(Pids.ADAPTER_VOLTAGE, i), rows.dt(i))
        }
        val low = volts.min() ?: return HealthCard(HealthSystem.ELECTRIC, HealthLevel.UNKNOWN, "Sin datos de tensión.", emptyList())
        val facts = listOf("Con el motor en marcha: entre ${n(low, 1)} y ${n(volts.max()!!, 1)} V, ${n(volts.median()!!, 1)} lo habitual.")
        return when (findings.worst("voltage")) {
            Severity.ALERT -> HealthCard(
                HealthSystem.ELECTRIC, HealthLevel.BAD, "La tensión se sale de lo normal.", facts,
                "Que midan el alternador y la batería antes de que te deje tirado.",
            )
            Severity.WARN -> HealthCard(
                HealthSystem.ELECTRIC, HealthLevel.WATCH, "La tensión baja más de lo habitual.", facts,
                "Puede ser la batería ya cansada. Una prueba de batería en el taller sale de dudas.",
            )
            else -> HealthCard(HealthSystem.ELECTRIC, HealthLevel.OK, "El alternador carga con normalidad.", facts)
        }
    }

    private fun List<TripFinding>.worst(id: String): Severity? =
        filter { it.advice.id == id }.maxOfOrNull { it.advice.severity }

    private fun n(value: Double, decimals: Int = 0) = String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')

    private fun thousands(value: Double) = String.format(Locale.US, "%,.0f", value).replace(',', '.')

    private fun signed(value: Double) = (if (value >= 0.5) "+" else "") + n(value)
}
