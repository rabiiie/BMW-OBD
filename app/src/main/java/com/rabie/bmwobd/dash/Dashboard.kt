package com.rabie.bmwobd.dash

import com.rabie.bmwobd.advice.Advice
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.cases.Case
import com.rabie.bmwobd.obd.Pids
import java.util.Locale

/** Un dato del panel apaisado, ya escrito. */
data class DashTile(val title: String, val value: String, val unit: String)

/**
 * Los grupos de datos que rotan a los lados del cuadro. [case] es el caso que se pone solo cuando
 * un aviso cae en el grupo, para que sus medidas se lean mas seguido mientras dura.
 */
enum class DashGroup(val title: String, val case: Case) {
    FILTER("Filtro", Case.FILTER),
    TURBO("Turbo", Case.TURBO),
    ENGINE("Motor", Case.DRIVING),
    INJECTORS("Inyectores", Case.INJECTORS),
    ELECTRIC("Eléctrico", Case.ELECTRIC),
    AIR("Aire", Case.TURBO),
}

object Dashboard {

    private val SOOT = Pids.BMW_SOOT_MEASURED
    private val SINCE_REGEN = Pids.bmw(0x03EB)
    private val BEFORE_CATALYST = Pids.bmw(0x041E)
    private val BOOST_ASKED = Pids.bmw(0x01F4)
    private val TURBO_SPEED = Pids.bmw(0x0C86)
    private val TURBO_ACTUATOR = Pids.bmw(0x0BEB)
    private val TORQUE = Pids.bmw(0x07D1)
    private val BATTERY_VOLTS = Pids.bmw(0x01B9)
    private val BATTERY_CURRENT = Pids.bmw(0x01B2)
    private val ALTERNATOR_LOAD = Pids.bmw(0x0186)
    private val AIR_PER_CYLINDER = Pids.bmw(0x0709)
    private val EGR_RATE = Pids.bmw(0x07AD)
    private val AFTER_INTERCOOLER = Pids.bmw(0x076F)
    private val TANK = Pids.bmw(Pids.BMW_TANK_ADDRESS)
    private val ODOMETER = Pids.bmw(Pids.BMW_ODOMETER_ADDRESS)
    private val TOTAL_FUEL = Pids.bmw(Pids.BMW_TOTAL_FUEL_ADDRESS)
    private val RAIL = listOf(Moment.RAIL_ACTUAL, Moment.RAIL)

    val GEAR = Pids.bmw(0x0EA6)

    /** Las medidas del panel que solo existen en los casos: se leen siempre, por el turno lento. */
    val background: List<Int> =
        listOf(BEFORE_CATALYST, TURBO_SPEED, TORQUE, BATTERY_VOLTS, ALTERNATOR_LOAD, AIR_PER_CYLINDER, EGR_RATE, GEAR) +
            Pids.BMW_CYLINDER_CORRECTIONS

    fun tiles(group: DashGroup, values: Map<Int, Double>): List<DashTile> = when (group) {
        DashGroup.FILTER -> listOf(
            tile("Hollín medido", values[SOOT], 1, "g"),
            tile("Desde regenerar", values[SINCE_REGEN], 0, "km"),
            tile("Escape", values[BEFORE_CATALYST] ?: Moment.EXHAUST_TEMPS.mapNotNull { values[it] }.maxOrNull(), 0, "°C"),
        )
        DashGroup.TURBO -> listOf(
            DashTile("Pide / tiene", pair(values[BOOST_ASKED], values[Pids.MAP], 0, " / ", plus = false), "kPa"),
            tile("Giro", values[TURBO_SPEED], 0, "rpm", thousands = true),
            tile("Actuador", values[TURBO_ACTUATOR], 0, "%"),
        )
        DashGroup.ENGINE -> listOf(
            tile("Par", values[TORQUE], 0, "Nm"),
            tile("Potencia ahora", horsepower(values), 0, "CV"),
            tile("Inyecta", values[Pids.BMW_INJECTION], 1, "mg"),
        )
        DashGroup.INJECTORS -> {
            val c = Pids.BMW_CYLINDER_CORRECTIONS.map { values[it] }
            listOf(
                DashTile("Cil. 1 · 2", pair(c[0], c[1], 1, " · ", plus = true), "mg"),
                DashTile("Cil. 3 · 4", pair(c[2], c[3], 1, " · ", plus = true), "mg"),
                tile("Raíl", RAIL.firstNotNullOfOrNull { values[it] }, 0, "bar"),
            )
        }
        DashGroup.ELECTRIC -> listOf(
            tile("Batería", values[BATTERY_VOLTS] ?: values[Moment.MODULE_VOLTAGE] ?: values[Pids.ADAPTER_VOLTAGE], 1, "V"),
            DashTile("Corriente", values[BATTERY_CURRENT]?.let { signed(it, 1) } ?: MISSING, "A"),
            tile("Alternador", values[ALTERNATOR_LOAD], 0, "%"),
        )
        DashGroup.AIR -> listOf(
            tile("Por cilindro", values[AIR_PER_CYLINDER], 0, "mg"),
            tile("EGR", values[EGR_RATE], 0, "%"),
            tile("Intercooler", values[AFTER_INTERCOOLER], 0, "°C"),
        )
    }

    /** El grupo al que toca un aviso: es el que se queda fijo mientras dura. */
    fun group(advice: Advice): DashGroup? = when (advice.id) {
        "regeneration", "regen_stop", "soot_gap", "filter_idle", "filter_load", "exhaust_hot" -> DashGroup.FILTER
        "boost_low", "boost_high", "air_full" -> DashGroup.TURBO
        "rail", "rail_high", "idle_rail" -> DashGroup.INJECTORS
        "voltage" -> DashGroup.ELECTRIC
        "egr", "air", "idle_air", "intake_hot", "intake_delta" -> DashGroup.AIR
        "coolant_hot", "oil_hot", "thermostat", "thermostat_drop", "idle_load", "idle_rpm",
        "lugging", "cold_push", "hot_stop", "long_idle",
        -> DashGroup.ENGINE
        else -> null
    }

    /** La potencia que entrega el motor en este instante: par por revoluciones. */
    fun horsepower(values: Map<Int, Double>): Double? {
        val torque = values[TORQUE] ?: return null
        val rpm = values[Pids.RPM] ?: return null
        return (torque * rpm / RPM_NM_PER_KW * HP_PER_KW).coerceAtLeast(0.0)
    }

    /**
     * Kilometros que quedan con lo que hay en el deposito. Es una cuenta: los litros entre el
     * consumo medio del trayecto, o entre el de toda la vida del coche mientras el trayecto es corto.
     */
    fun rangeKm(values: Map<Int, Double>, tripAverage: Double?): Double? {
        val tank = values[TANK] ?: return null
        val average = tripAverage ?: lifetimeAverage(values) ?: return null
        return if (average >= MIN_AVERAGE) tank / average * 100.0 else null
    }

    private fun lifetimeAverage(values: Map<Int, Double>): Double? {
        val km = values[ODOMETER]?.takeIf { it > 1000 } ?: return null
        val fuel = values[TOTAL_FUEL] ?: return null
        return fuel / km * 100.0
    }

    private fun tile(title: String, value: Double?, decimals: Int, unit: String, thousands: Boolean = false) =
        DashTile(title, value?.let { if (thousands) thousands(it) else n(it, decimals) } ?: MISSING, unit)

    private fun pair(a: Double?, b: Double?, decimals: Int, separator: String, plus: Boolean): String {
        fun text(value: Double?) = value?.let { if (plus) signed(it, decimals) else n(it, decimals) } ?: MISSING
        return text(a) + separator + text(b)
    }

    fun n(value: Double, decimals: Int = 0) = String.format(Locale.US, "%.${decimals}f", value).replace('.', ',')

    private fun thousands(value: Double) = String.format(Locale.US, "%,.0f", value).replace(',', '.')

    private fun signed(value: Double, decimals: Int) = n(value, decimals).let { if (value > 0 && it.any { c -> c in '1'..'9' }) "+$it" else it }

    const val MISSING = "—"
    private const val RPM_NM_PER_KW = 9549.0
    private const val HP_PER_KW = 1.36
    private const val MIN_AVERAGE = 2.0
}
