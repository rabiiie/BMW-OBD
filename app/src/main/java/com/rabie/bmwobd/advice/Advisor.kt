package com.rabie.bmwobd.advice

import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.trips.TripData
import com.rabie.bmwobd.vehicle.EngineReference
import com.rabie.bmwobd.vehicle.Vehicle

enum class Severity { INFO, WARN, ALERT }

/** Un indicio para que el conductor investigue. No es un diagnostico. */
data class Advice(val id: String, val severity: Severity, val title: String, val detail: String)

/** Un indicio visto durante un trayecto y cuanto tiempo estuvo activo. */
data class TripFinding(val advice: Advice, val seconds: Long)

/**
 * Umbrales de partida. Son prudentes a proposito: el aviso sale cuando algo se aparta de lo
 * normal y el rojo solo cuando se aparta mucho. Hay que ajustarlos con lecturas reales del coche.
 */
object Limits {
    const val WARM_COOLANT = 75.0
    const val IDLE_MAX_RPM = 1100.0
    const val IDLE_MAX_SPEED = 2.0

    const val IDLE_LOAD_WARN = 45.0
    const val IDLE_LOAD_ALERT = 65.0
    const val IDLE_RPM_LOW = 650.0
    const val IDLE_RPM_HIGH = 900.0

    // Margenes alrededor de la referencia del motor: aviso al salirse un poco, rojo al salirse mucho.
    const val IDLE_LOAD_MARGIN_WARN = 10.0
    const val IDLE_LOAD_MARGIN_ALERT = 30.0
    const val IDLE_RAIL_MARGIN_WARN = 40.0
    const val IDLE_RAIL_MARGIN_ALERT = 100.0
    const val IDLE_AIR_MARGIN_WARN = 1.5
    const val IDLE_AIR_MARGIN_ALERT = 4.0
    const val IDLE_AIR_MIN_EGR = 15.0
    const val FULL_LOAD_MIN_RPM = 2000.0
    const val FULL_LOAD_MIN_LOAD = 99.0
    const val FULL_LOAD_MAP_WARN_BELOW = 20.0
    const val FULL_LOAD_MAP_ALERT_BELOW = 45.0
    const val RAIL_OVER_MAX_ALERT = 150.0

    // El caudal de aire de referencia es a 4000 rpm; mas abajo entra menos y no se compara.
    const val FULL_LOAD_AIR_MIN_RPM = 3500.0
    const val FULL_LOAD_AIR_WARN_BELOW = 15.0
    const val FULL_LOAD_AIR_ALERT_BELOW = 30.0

    const val OIL_WARN = 125.0
    const val OIL_ALERT = 135.0
    const val INTAKE_WARN = 65.0
    const val INTAKE_ALERT = 80.0
    // El termostato del N47 abre hacia los 88 °C: en carretera no deberia bajar de 80.
    const val THERMOSTAT_MIN_COOLANT = 80.0
    const val THERMOSTAT_DRIVING_MS = 15 * 60_000L
    const val ROAD_SPEED = 50.0

    // Una vez que el motor ha llegado a su temperatura, en carretera no deberia volver a enfriarse.
    const val OPERATING_COOLANT = 86.0
    const val COOLED_BACK_COOLANT = 80.0

    // Con recuperacion de energia el alternador se desconecta a proposito y la tension baja a
    // 12,2-12,4 V en marcha, y en retencion sube hasta unos 15 V. Nada de eso es averia.
    const val VOLTAGE_LOW_WARN = 11.8
    const val VOLTAGE_LOW_ALERT = 11.4
    const val VOLTAGE_HIGH_ALERT = 15.4
    const val CHARGING_HARD_VOLTAGE = 14.4

    // Presion diferencial del filtro de particulas, en kPa (1 kPa = 10 mbar).
    const val FILTER_IDLE_WARN_KPA = 2.0
    const val FILTER_IDLE_ALERT_KPA = 4.5
    const val FILTER_LOAD_WARN_KPA = 25.0
    const val FILTER_LOAD_ALERT_KPA = 50.0
    const val FILTER_LOAD_MIN_RPM = 3500.0

    const val EXHAUST_WARN = 700.0
    const val EXHAUST_ALERT = 750.0

    // Aire de admision respecto al exterior, rodando estable: mide lo que enfria el intercooler.
    const val INTAKE_DELTA_WARN = 25.0
    const val INTAKE_DELTA_ALERT = 40.0
    const val INTAKE_DELTA_MIN_SPEED = 80.0
    const val INTAKE_DELTA_MAX_LOAD = 70.0

    const val PULL_MIN_LOAD = 80.0
    const val PULL_MIN_RPM = 1800.0
    const val PULL_MAX_RPM = 3500.0
    const val BOOST_DEFICIT_WARN_KPA = 20.0
    const val BOOST_DEFICIT_ALERT_KPA = 40.0
    const val BOOST_EXCESS_ALERT_KPA = 30.0
    const val BOOST_MIN_AT_FULL_LOAD_KPA = 50.0
    const val RAIL_DEVIATION_WARN = 0.10
    const val RAIL_DEVIATION_ALERT = 0.20
    const val EGR_ERROR_WARN = 15.0
    const val EGR_MIN_COMMANDED = 5.0
    const val AIR_RATIO_WARN = 0.70
    const val AIR_RATIO_ALERT = 0.55

    const val LUGGING_MAX_RPM = 1300.0
    const val COLD_OIL = 60.0
    const val COLD_MAX_RPM = 3000.0
    const val HOT_STOP_AVG_LOAD = 50.0
    const val HOT_STOP_EXHAUST = 350.0
    const val HOT_STOP_WINDOW_MS = 120_000L
}

/** Lo que se sabe del coche en un instante, con el contexto ya deducido. */
class Moment(
    val values: Map<Int, Double>,
    val drivingMs: Long,
    val recentLoad: Double?,
    val vehicle: Vehicle,
    /** Lo mas caliente que ha llegado a estar el refrigerante en lo que va de trayecto. */
    val peakCoolant: Double? = null,
) {
    /** La referencia del motor de este coche, si se conoce. */
    val reference: EngineReference? = EngineReference.of(vehicle)

    val rpm = values[Pids.RPM]
    val speed = values[Pids.SPEED]
    val load = values[Pids.LOAD]
    val coolant = values[Pids.COOLANT]
    val oil = values[Pids.OIL]

    val running = (rpm ?: 0.0) > 400.0
    val warm = (coolant ?: 0.0) >= Limits.WARM_COOLANT
    val idle = running && (rpm ?: 0.0) < Limits.IDLE_MAX_RPM && (speed ?: 99.0) < Limits.IDLE_MAX_SPEED

    /** Acelerando a fondo en la zona donde el turbo ya deberia soplar. */
    /**
     * Carga alta de verdad. En un diesel la carga del OBD tambien sube con poco pedal si la EGR va
     * abierta, porque entra menos aire fresco; cuando se le pide fuerza, la centralita la cierra.
     * Sin el dato de la EGR se da por cerrada.
     */
    val demanding = (load ?: 0.0) >= Limits.PULL_MIN_LOAD &&
        (values[EGR_COMMANDED] ?: 0.0) < Limits.EGR_MIN_COMMANDED

    val pull = demanding && (rpm ?: 0.0) in Limits.PULL_MIN_RPM..Limits.PULL_MAX_RPM

    /**
     * Aire que cabe en el motor a estas revoluciones, presion y temperatura, en g/s. Sin la
     * cilindrada del perfil no se puede calcular.
     */
    val theoreticalAir: Double?
        get() {
            val liters = vehicle.displacementLiters ?: return null
            val r = rpm ?: return null
            val map = values[Pids.MAP] ?: return null
            val intake = values[INTAKE_TEMP] ?: return null
            val density = map * 1000.0 / (287.05 * (intake + 273.15))
            return density * liters * r / 120.0
        }

    /**
     * Carga corregida por la EGR. En este tipo de centralita diesel la carga del OBD sube cuando
     * entra menos aire fresco: con la EGR abierta marca el doble que con ella cerrada, con el motor
     * haciendo el mismo trabajo. Multiplicada por la parte del cilindro que se llena de aire
     * fresco, deja de depender de la EGR y se puede comparar con una referencia.
     */
    val egrFreeLoad: Double?
        get() {
            val raw = load ?: return null
            val air = values[MAF] ?: return null
            val full = theoreticalAir?.takeIf { it > 0 } ?: return null
            return raw * air / full
        }

    companion object {
        const val INTAKE_TEMP = 0x0F
        const val MAF = 0x10
        const val EGR_COMMANDED = 0x2C
        const val EGR_ERROR = 0x2D
        const val MODULE_VOLTAGE = 0x42
        const val AMBIENT_TEMP = 0x46
        val FILTER_PRESSURE = Pids.part(0x7A, 0)
        const val CATALYST_TEMP = 0x3C
        const val RAIL = 0x23

        // La temperatura del catalizador es la unica de escape que dan muchos coches.
        val EXHAUST_TEMPS = (0..3).map { Pids.part(0x78, it) } + CATALYST_TEMP
        val BOOST_COMMANDED = Pids.part(0x70, 0)
        val BOOST_ACTUAL = Pids.part(0x70, 1)
        val RAIL_COMMANDED = Pids.part(0x6D, 0)
        val RAIL_ACTUAL = Pids.part(0x6D, 1)
    }
}

private class Rule(val id: String, val holdMs: Long, val check: (Moment) -> Advice?)

/**
 * Mira las lecturas en su contexto (ralenti, en caliente, acelerando) y saca indicios. Cada regla
 * tiene que cumplirse un rato seguido para no saltar con un pico suelto. Hay que llamarlo con el
 * tiempo creciente; guarda memoria entre llamadas.
 */
class Advisor {

    private val since = HashMap<String, Long>()
    private val loads = ArrayDeque<Pair<Long, Double>>()
    private var drivingMs = 0L
    private var lastMs: Long? = null
    private var peakCoolant: Double? = null

    fun update(tMs: Long, values: Map<Int, Double>, vehicle: Vehicle = Vehicle.GENERIC): List<Advice> {
        val dt = lastMs?.let { (tMs - it).coerceIn(0L, MAX_GAP_MS) } ?: 0L
        lastMs = tMs
        if ((values[Pids.SPEED] ?: 0.0) > Limits.ROAD_SPEED) drivingMs += dt

        values[Pids.LOAD]?.let { loads.addLast(tMs to it) }
        while (loads.isNotEmpty() && tMs - loads.first().first > Limits.HOT_STOP_WINDOW_MS) loads.removeFirst()
        val windowFull = loads.isNotEmpty() && tMs - loads.first().first > Limits.HOT_STOP_WINDOW_MS / 2
        val recentLoad = if (windowFull) loads.sumOf { it.second } / loads.size else null

        values[Pids.COOLANT]?.let { if (it > (peakCoolant ?: -273.0)) peakCoolant = it }
        val moment = Moment(values, drivingMs, recentLoad, vehicle, peakCoolant)
        val active = mutableListOf<Advice>()
        for (rule in RULES) {
            val advice = rule.check(moment)
            val alertKey = rule.id + ALERT_SUFFIX
            if (advice == null) {
                since.remove(rule.id)
                since.remove(alertKey)
                continue
            }
            val start = since.getOrPut(rule.id) { tMs }
            // El rojo tambien tiene que mantenerse: un pico suelto dentro de un aviso se queda en aviso.
            val held = if (advice.severity == Severity.ALERT) {
                val alertStart = since.getOrPut(alertKey) { tMs }
                if (tMs - alertStart >= rule.holdMs) advice else advice.copy(severity = Severity.WARN)
            } else {
                since.remove(alertKey)
                advice
            }
            if (tMs - start >= rule.holdMs) active += held
        }
        return active.sortedByDescending { it.severity }
    }

    companion object {
        private const val MAX_GAP_MS = 5_000L
        private const val ALERT_SUFFIX = "#rojo"

        /** Pasa las reglas por un trayecto grabado: que indicios salieron y cuanto duraron. */
        fun review(data: TripData, vehicle: Vehicle = Vehicle.GENERIC): List<TripFinding> {
            val advisor = Advisor()
            val worst = LinkedHashMap<String, Advice>()
            val millis = HashMap<String, Long>()
            var previous = 0L
            for (i in 0 until data.size) {
                val values = HashMap<Int, Double>()
                for ((id, series) in data.series) if (!series[i].isNaN()) values[id] = series[i]
                val dt = (data.tMs[i] - previous).coerceIn(0L, MAX_GAP_MS)
                previous = data.tMs[i]
                for (advice in advisor.update(data.tMs[i], values, vehicle)) {
                    val known = worst[advice.id]
                    if (known == null || advice.severity > known.severity) worst[advice.id] = advice
                    millis[advice.id] = (millis[advice.id] ?: 0L) + dt
                }
            }
            return worst.values
                .map { TripFinding(it, (millis[it.id] ?: 0L) / 1000) }
                .sortedByDescending { it.advice.severity }
        }

        private fun level(value: Double, warn: Double, alert: Double): Severity? = when {
            value >= alert -> Severity.ALERT
            value >= warn -> Severity.WARN
            else -> null
        }

        /** Aviso si [value] se sale del rango mas un margen, y rojo si se sale con el margen grande. */
        private fun outside(value: Double, range: ClosedFloatingPointRange<Double>, warn: Double, alert: Double): Severity? =
            level(maxOf(range.start - value, value - range.endInclusive), warn, alert)

        private fun n(value: Double, decimals: Int = 0) = "%.${decimals}f".format(value)

        private val RULES: List<Rule> = listOf(
            Rule("coolant_hot", 5_000) { m ->
                val t = m.coolant ?: return@Rule null
                val severity = level(t, m.vehicle.coolantWarn, m.vehicle.coolantAlert) ?: return@Rule null
                Advice(
                    "coolant_hot", severity, "Refrigerante a ${n(t)} °C",
                    if (severity == Severity.ALERT) {
                        "Muy por encima de lo normal. Levanta el pie y, si sigue subiendo, para en cuanto puedas."
                    } else {
                        "Por encima de lo habitual. Conviene mirar nivel de refrigerante, ventilador y radiador."
                    },
                )
            },
            Rule("oil_hot", 10_000) { m ->
                val t = m.oil ?: return@Rule null
                val severity = level(t, Limits.OIL_WARN, Limits.OIL_ALERT) ?: return@Rule null
                Advice("oil_hot", severity, "Aceite a ${n(t)} °C", "Temperatura de aceite alta. Baja el ritmo hasta que se recupere.")
            },
            Rule("intake_hot", 20_000) { m ->
                val t = m.values[Moment.INTAKE_TEMP] ?: return@Rule null
                val severity = level(t, Limits.INTAKE_WARN, Limits.INTAKE_ALERT) ?: return@Rule null
                Advice(
                    "intake_hot", severity, "Aire de admisión a ${n(t)} °C",
                    "El aire entra muy caliente. Puede ser calor acumulado tras parar; si pasa en marcha, mira el intercooler.",
                )
            },
            Rule("thermostat", 60_000) { m ->
                val t = m.coolant ?: return@Rule null
                if (m.drivingMs < Limits.THERMOSTAT_DRIVING_MS || t >= Limits.THERMOSTAT_MIN_COOLANT) return@Rule null
                Advice(
                    "thermostat", Severity.WARN, "El motor no coge temperatura",
                    "Tras ${m.drivingMs / 60_000} min en carretera sigue a ${n(t)} °C. Suele ser el termostato abierto, " +
                        "y por debajo de esa temperatura el filtro de partículas no regenera.",
                )
            },
            Rule("thermostat_drop", 60_000) { m ->
                val t = m.coolant ?: return@Rule null
                val peak = m.peakCoolant ?: return@Rule null
                val onRoad = (m.speed ?: 0.0) >= Limits.ROAD_SPEED
                if (peak < Limits.OPERATING_COOLANT || t >= Limits.COOLED_BACK_COOLANT || !onRoad) return@Rule null
                Advice(
                    "thermostat_drop", Severity.WARN, "El motor se enfría en marcha",
                    "Llegó a ${n(peak)} °C y en carretera ha vuelto a ${n(t)} °C. Un termostato sano lo mantiene; " +
                        "si se repite, suele ser que no cierra del todo.",
                )
            },
            Rule("filter_idle", 20_000) { m ->
                val drop = m.values[Moment.FILTER_PRESSURE] ?: return@Rule null
                if (!m.idle || !m.warm) return@Rule null
                val severity = level(drop, Limits.FILTER_IDLE_WARN_KPA, Limits.FILTER_IDLE_ALERT_KPA) ?: return@Rule null
                Advice(
                    "filter_idle", severity, "Filtro de partículas: ${n(drop * 10)} mbar al ralentí",
                    "Contrapresión alta en parado. El filtro va cargado; conviene un trayecto largo de carretera " +
                        "y, si no baja, revisarlo.",
                )
            },
            Rule("filter_load", 3_000) { m ->
                val drop = m.values[Moment.FILTER_PRESSURE] ?: return@Rule null
                if ((m.rpm ?: 0.0) < Limits.FILTER_LOAD_MIN_RPM || (m.load ?: 0.0) < Limits.PULL_MIN_LOAD) return@Rule null
                val severity = level(drop, Limits.FILTER_LOAD_WARN_KPA, Limits.FILTER_LOAD_ALERT_KPA) ?: return@Rule null
                Advice(
                    "filter_load", severity, "Filtro de partículas: ${n(drop * 10)} mbar a fondo",
                    "Contrapresión alta a plena carga. Puede estar saturado de ceniza, que no se quema regenerando.",
                )
            },
            Rule("exhaust_hot", 10_000) { m ->
                val t = Moment.EXHAUST_TEMPS.mapNotNull { m.values[it] }.maxOrNull() ?: return@Rule null
                val severity = level(t, Limits.EXHAUST_WARN, Limits.EXHAUST_ALERT) ?: return@Rule null
                Advice(
                    "exhaust_hot", severity, "Gases de escape a ${n(t)} °C",
                    "Temperatura de escape muy alta y sostenida. Levanta el pie; castiga la turbina del turbo.",
                )
            },
            Rule("intake_delta", 60_000) { m ->
                val intake = m.values[Moment.INTAKE_TEMP] ?: return@Rule null
                val ambient = m.values[Moment.AMBIENT_TEMP] ?: return@Rule null
                if ((m.speed ?: 0.0) < Limits.INTAKE_DELTA_MIN_SPEED || (m.load ?: 100.0) > Limits.INTAKE_DELTA_MAX_LOAD) {
                    return@Rule null
                }
                val delta = intake - ambient
                val severity = level(delta, Limits.INTAKE_DELTA_WARN, Limits.INTAKE_DELTA_ALERT) ?: return@Rule null
                Advice(
                    "intake_delta", severity, "Admisión ${n(delta)} °C por encima del exterior",
                    "Rodando estable el intercooler debería enfriar más. Puede estar sucio por fuera o con aceite por dentro.",
                )
            },
            Rule("idle_load", 20_000) { m ->
                val load = m.load ?: return@Rule null
                if (!m.idle || !m.warm) return@Rule null
                val normal = m.reference?.idleLoad
                if (normal != null) {
                    // Con referencia se compara la carga corregida: la cruda cambia al doble con la EGR.
                    val corrected = m.egrFreeLoad ?: return@Rule null
                    val severity = level(
                        corrected,
                        normal.endInclusive + Limits.IDLE_LOAD_MARGIN_WARN,
                        normal.endInclusive + Limits.IDLE_LOAD_MARGIN_ALERT,
                    ) ?: return@Rule null
                    return@Rule Advice(
                        "idle_load", severity, "Carga corregida del ${n(corrected)} % al ralentí",
                        "En caliente y parado el motor trabaja más de lo normal (referencia " +
                            "${n(normal.start)}–${n(normal.endInclusive)} % sin consumidores; el OBD marca ${n(load)} %). " +
                            "Puede ser un consumidor grande (aire acondicionado, alternador cargando) o algo que lo frena.",
                    )
                }
                val severity = level(load, Limits.IDLE_LOAD_WARN, Limits.IDLE_LOAD_ALERT) ?: return@Rule null
                Advice(
                    "idle_load", severity, "Carga del ${n(load)} % al ralentí",
                    "En caliente y parado el motor trabaja más de lo normal. Puede ser un consumidor grande " +
                        "(aire acondicionado, alternador cargando) o algo que lo frena.",
                )
            },
            Rule("idle_rail", 20_000) { m ->
                val normal = m.reference?.idleRailBar ?: return@Rule null
                val rail = m.values[Moment.RAIL_ACTUAL] ?: m.values[Moment.RAIL] ?: return@Rule null
                if (!m.idle || !m.warm) return@Rule null
                val severity = outside(rail, normal, Limits.IDLE_RAIL_MARGIN_WARN, Limits.IDLE_RAIL_MARGIN_ALERT)
                    ?: return@Rule null
                Advice(
                    "idle_rail", severity, "Raíl a ${n(rail)} bar al ralentí",
                    "La referencia de este motor es ${n(normal.start)}–${n(normal.endInclusive)} bar. " +
                        "Conviene mirar filtro de gasóleo, regulador de presión y bomba.",
                )
            },
            Rule("idle_air", 20_000) { m ->
                val normal = m.reference?.idleAirGramsPerSecond ?: return@Rule null
                val air = m.values[Moment.MAF] ?: return@Rule null
                // La referencia es con la EGR abierta: con ella cerrada entra el doble y es normal.
                val egr = m.values[Moment.EGR_COMMANDED] ?: 0.0
                if (!m.idle || !m.warm || egr < Limits.IDLE_AIR_MIN_EGR) return@Rule null
                val severity = outside(air, normal, Limits.IDLE_AIR_MARGIN_WARN, Limits.IDLE_AIR_MARGIN_ALERT)
                    ?: return@Rule null
                val hint = if (air > normal.endInclusive) {
                    "Si entra de más, la EGR recircula menos de lo que se le pide."
                } else {
                    "Si entra de menos, mira caudalímetro, filtro de aire y fugas."
                }
                Advice(
                    "idle_air", severity, "Aire de ${n(air, 1)} g/s al ralentí",
                    "La referencia con la EGR abierta es ${n(normal.start, 1)}–${n(normal.endInclusive, 1)} g/s. $hint",
                )
            },
            Rule("rail_high", 2_000) { m ->
                val max = m.reference?.maxRailBar ?: return@Rule null
                val rail = m.values[Moment.RAIL_ACTUAL] ?: m.values[Moment.RAIL] ?: return@Rule null
                if (rail < max + Limits.RAIL_OVER_MAX_ALERT) return@Rule null
                Advice(
                    "rail_high", Severity.ALERT, "Raíl a ${n(rail)} bar",
                    "Por encima del máximo de este motor (${n(max)} bar). Conviene revisar el regulador de presión.",
                )
            },
            Rule("idle_rpm", 30_000) { m ->
                val rpm = m.rpm ?: return@Rule null
                if (!m.idle || !m.warm || rpm in Limits.IDLE_RPM_LOW..Limits.IDLE_RPM_HIGH) return@Rule null
                // Ralenti subido y tension de carga alta a la vez: el coche lo sube a proposito para
                // recargar la bateria. No es averia del motor, pero dice algo de la bateria.
                val volts = m.values[Moment.MODULE_VOLTAGE] ?: m.values[Pids.ADAPTER_VOLTAGE]
                if (rpm > Limits.IDLE_RPM_HIGH && volts != null && volts >= Limits.CHARGING_HARD_VOLTAGE) {
                    return@Rule Advice(
                        "idle_rpm", Severity.INFO, "Ralentí subido para cargar la batería",
                        "A ${n(rpm)} rpm con ${n(volts, 1)} V. El coche sube el ralentí para recargar. " +
                            "Si pasa a menudo, la batería está baja o envejecida, o se hacen muchos trayectos cortos.",
                    )
                }
                Advice(
                    "idle_rpm", Severity.WARN, "Ralentí a ${n(rpm)} rpm en caliente",
                    "Fuera de lo habitual. Un ralentí alto y sostenido también pasa durante una regeneración del filtro.",
                )
            },
            Rule("voltage", 30_000) { m ->
                val v = m.values[Moment.MODULE_VOLTAGE] ?: m.values[Pids.ADAPTER_VOLTAGE] ?: return@Rule null
                if (!m.running) return@Rule null
                when {
                    v >= Limits.VOLTAGE_HIGH_ALERT -> Advice(
                        "voltage", Severity.ALERT, "Tensión de ${n(v, 1)} V",
                        "Carga demasiado alta con el motor en marcha. Conviene revisar el regulador del alternador.",
                    )
                    v <= Limits.VOLTAGE_LOW_ALERT -> Advice(
                        "voltage", Severity.ALERT, "Tensión de ${n(v, 1)} V",
                        "Muy baja con el motor en marcha: el alternador no está cargando.",
                    )
                    v <= Limits.VOLTAGE_LOW_WARN -> Advice(
                        "voltage", Severity.WARN, "Tensión de ${n(v, 1)} V",
                        "Baja con el motor en marcha. Si se repite, mira alternador y batería.",
                    )
                    else -> null
                }
            },
            Rule("boost_low", 3_000) { m ->
                if (!m.pull) return@Rule null
                val actual = m.values[Moment.BOOST_ACTUAL]
                val commanded = m.values[Moment.BOOST_COMMANDED]
                if (actual != null && commanded != null) {
                    val deficit = commanded - actual
                    val severity = level(deficit, Limits.BOOST_DEFICIT_WARN_KPA, Limits.BOOST_DEFICIT_ALERT_KPA)
                        ?: return@Rule null
                    return@Rule Advice(
                        "boost_low", severity, "Faltan ${n(deficit / 100, 2)} bar de turbo",
                        "La centralita pide más presión de la que llega. Mira manguitos, intercooler y la geometría del turbo.",
                    )
                }
                val normal = m.reference?.fullLoadMapKpa
                val map = m.values[Pids.MAP]
                if (normal != null && map != null) {
                    val fullLoad = m.demanding && (m.rpm ?: 0.0) > Limits.FULL_LOAD_MIN_RPM &&
                        (m.load ?: 0.0) >= Limits.FULL_LOAD_MIN_LOAD
                    if (!fullLoad) return@Rule null
                    val severity = level(normal.start - map, Limits.FULL_LOAD_MAP_WARN_BELOW, Limits.FULL_LOAD_MAP_ALERT_BELOW)
                        ?: return@Rule null
                    return@Rule Advice(
                        "boost_low", severity, "Turbo de ${n(map)} kPa a plena carga",
                        "La referencia de este motor a fondo es ${n(normal.start)}–${n(normal.endInclusive)} kPa. " +
                            "Mira manguitos, intercooler y la geometría del turbo.",
                    )
                }
                val boost = (Pids.boostBar(m.values) ?: return@Rule null) * 100
                if (boost >= Limits.BOOST_MIN_AT_FULL_LOAD_KPA) return@Rule null
                Advice(
                    "boost_low", Severity.WARN, "Poco turbo acelerando a fondo",
                    "Solo ${n(boost / 100, 2)} bar con carga alta. Puede haber una fuga en la admisión.",
                )
            },
            Rule("air_full", 3_000) { m ->
                val normal = m.reference?.fullLoadAirGramsPerSecond ?: return@Rule null
                val air = m.values[Moment.MAF] ?: return@Rule null
                val fullLoad = m.demanding && (m.rpm ?: 0.0) >= Limits.FULL_LOAD_AIR_MIN_RPM &&
                    (m.load ?: 0.0) >= Limits.FULL_LOAD_MIN_LOAD
                if (!fullLoad) return@Rule null
                val severity = level(normal.start - air, Limits.FULL_LOAD_AIR_WARN_BELOW, Limits.FULL_LOAD_AIR_ALERT_BELOW)
                    ?: return@Rule null
                Advice(
                    "air_full", severity, "Aire de ${n(air)} g/s a plena carga",
                    "La referencia de este motor a fondo es ${n(normal.start)}–${n(normal.endInclusive)} g/s. " +
                        "Mira filtro de aire, caudalímetro y fugas en la admisión.",
                )
            },
            Rule("boost_high", 2_000) { m ->
                val actual = m.values[Moment.BOOST_ACTUAL] ?: return@Rule null
                val commanded = m.values[Moment.BOOST_COMMANDED] ?: return@Rule null
                if (actual - commanded < Limits.BOOST_EXCESS_ALERT_KPA) return@Rule null
                Advice(
                    "boost_high", Severity.ALERT, "Sobra presión de turbo",
                    "Llegan ${n((actual - commanded) / 100, 2)} bar más de lo pedido. La geometría variable puede estar agarrotada.",
                )
            },
            Rule("rail", 3_000) { m ->
                val actual = m.values[Moment.RAIL_ACTUAL] ?: return@Rule null
                val commanded = m.values[Moment.RAIL_COMMANDED] ?: return@Rule null
                if (!m.running || commanded <= 0) return@Rule null
                val deviation = (commanded - actual) / commanded
                val severity = level(kotlin.math.abs(deviation), Limits.RAIL_DEVIATION_WARN, Limits.RAIL_DEVIATION_ALERT)
                    ?: return@Rule null
                Advice(
                    "rail", severity,
                    if (deviation > 0) "Presión de raíl por debajo de lo pedido" else "Presión de raíl por encima de lo pedido",
                    "Pide ${n(commanded)} bar y hay ${n(actual)}. Conviene mirar filtro de gasóleo, regulador y bomba.",
                )
            },
            Rule("egr", 15_000) { m ->
                val error = m.values[Moment.EGR_ERROR] ?: return@Rule null
                // Con la EGR mandada cerrada el coche da el error como -100 %: no significa nada.
                val commanded = m.values[Moment.EGR_COMMANDED] ?: return@Rule null
                if (commanded < Limits.EGR_MIN_COMMANDED) return@Rule null
                if (!m.warm || kotlin.math.abs(error) < Limits.EGR_ERROR_WARN) return@Rule null
                Advice(
                    "egr", Severity.WARN, "La EGR no sigue lo que se le pide",
                    "Error del ${n(error)} % sostenido. Suele ser la válvula sucia o agarrotada.",
                )
            },
            Rule("air", 3_000) { m ->
                if (!m.pull) return@Rule null
                if ((m.values[Moment.EGR_COMMANDED] ?: 0.0) > 5.0) return@Rule null
                val maf = m.values[Moment.MAF] ?: return@Rule null
                val theoretical = m.theoreticalAir ?: return@Rule null
                val ratio = maf / theoretical
                val severity = when {
                    ratio <= Limits.AIR_RATIO_ALERT -> Severity.ALERT
                    ratio <= Limits.AIR_RATIO_WARN -> Severity.WARN
                    else -> return@Rule null
                }
                Advice(
                    "air", severity, "Entra menos aire del que cabe",
                    "El caudalímetro marca ${n(maf)} g/s y a estas revoluciones y presión deberían ser unos ${n(theoretical)}. " +
                        "Puede ser el caudalímetro sucio, la EGR abierta o una fuga.",
                )
            },
            Rule("lugging", 3_000) { m ->
                val rpm = m.rpm ?: return@Rule null
                if (!m.demanding || rpm > Limits.LUGGING_MAX_RPM || m.idle) return@Rule null
                Advice(
                    "lugging", Severity.INFO, "Motor ahogado",
                    "Mucha carga a ${n(rpm)} rpm. Reduce una marcha: castiga el volante bimasa y la cadena.",
                )
            },
            Rule("cold_push", 2_000) { m ->
                val cold = m.oil ?: m.coolant ?: return@Rule null
                if (cold >= Limits.COLD_OIL) return@Rule null
                if ((m.rpm ?: 0.0) < Limits.COLD_MAX_RPM && !m.demanding) return@Rule null
                Advice(
                    "cold_push", Severity.INFO, "Motor frío",
                    "Está a ${n(cold)} °C. Mejor no exigirle hasta que el aceite pase de ${n(Limits.COLD_OIL)} °C.",
                )
            },
            Rule("hot_stop", 0) { m ->
                if (!m.idle) return@Rule null
                // Con temperatura de escape se mira esa, que es lo que calienta el turbo; la carga
                // media solo vale de aproximacion cuando el coche no la da.
                val exhaust = Moment.EXHAUST_TEMPS.mapNotNull { m.values[it] }.maxOrNull()
                if (exhaust != null) {
                    if (exhaust < Limits.HOT_STOP_EXHAUST) return@Rule null
                } else {
                    val recent = m.recentLoad ?: return@Rule null
                    if (recent < Limits.HOT_STOP_AVG_LOAD) return@Rule null
                }
                Advice(
                    "hot_stop", Severity.INFO, "Deja enfriar el turbo",
                    "Vienes de exigirle al motor. Espera medio minuto al ralentí antes de apagar.",
                )
            },
        )
    }
}
