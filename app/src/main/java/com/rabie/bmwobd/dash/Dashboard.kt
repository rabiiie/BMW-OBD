package com.rabie.bmwobd.dash

import com.rabie.bmwobd.advice.Advice
import com.rabie.bmwobd.advice.Limits
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.cases.Case
import com.rabie.bmwobd.obd.Pids
import java.util.Locale

/** Un dato de una tarjeta del centro: el numero y, debajo, lo minimo para saber que es. */
data class DashCell(val value: String, val tag: String)

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

/** Si se puede apagar ya el motor sin castigar el turbo, o conviene esperar a que baje el escape. */
enum class Shutdown(val label: String) { OK("OK"), COOLING("ENFRIANDO"), WAIT("ESPERA") }

object Dashboard {

    /**
     * Si conviene esperar antes de apagar. Sale de la temperatura del escape, con el mismo limite
     * que el aviso de dejar enfriar el turbo, y de si el filtro esta regenerando. No hay cifra de
     * fabrica: es un criterio de la app. Sin temperatura de escape no se dice nada.
     */
    fun shutdown(values: Map<Int, Double>): Shutdown? {
        if (Pids.regenerating(values)) return Shutdown.WAIT
        val exhaust = (Moment.EXHAUST_TEMPS.mapNotNull { values[it] } + listOfNotNull(values[BEFORE_CATALYST])).maxOrNull() ?: return null
        return when {
            exhaust >= Limits.HOT_STOP_EXHAUST -> Shutdown.WAIT
            exhaust >= COOLING_EXHAUST -> Shutdown.COOLING
            else -> Shutdown.OK
        }
    }

    private const val COOLING_EXHAUST = 250.0

    private val SOOT = Pids.BMW_SOOT_USED
    private val SINCE_REGEN = Pids.bmw(0x03EB)
    private val BEFORE_CATALYST = Pids.bmw(0x041E)
    private val BOOST_ASKED = Pids.bmw(0x01F4)
    private val TURBO_SPEED = Pids.bmw(0x0C86)
    private val TURBO_ACTUATOR = Pids.bmw(0x0BEB)
    private val TORQUE = Pids.bmw(0x07D1)
    private val BATTERY_VOLTS = Pids.bmw(0x01B9)
    private val BATTERY_CURRENT = Pids.bmw(0x01B2)
    private val ALTERNATOR_TEMP = Pids.bmw(0x0E43)
    private val AIR_PER_CYLINDER = Pids.bmw(0x0709)
    private val EGR_RATE = Pids.bmw(0x07AD)
    private val EGR_ASKED = Pids.bmw(0x0793)
    private val AFTER_INTERCOOLER = Pids.bmw(0x076F)
    private val TANK = Pids.bmw(Pids.BMW_TANK_ADDRESS)
    private val FUEL_TEMP = Pids.bmw(0x0385)
    private const val OUTSIDE_TEMP = 0x46
    private val ODOMETER = Pids.bmw(Pids.BMW_ODOMETER_ADDRESS)
    private val TOTAL_FUEL = Pids.bmw(Pids.BMW_TOTAL_FUEL_ADDRESS)
    private val RAIL = listOf(Moment.RAIL_ACTUAL, Moment.RAIL)

    val GEAR = Pids.bmw(0x0EA6)

    /** Estado del control de crucero (2 = llevando el coche, 1 = en espera) y la velocidad fijada. */
    val CRUISE_STATE = Pids.bmw(0x0DE3)
    val CRUISE_SPEED = Pids.bmw(0x0DE2)
    const val CRUISE_DRIVING = 2
    const val CRUISE_WAITING = 1

    /** Lo que el panel tiene siempre a la vista, este el grupo que este. */
    val alwaysWatched: List<Int> = listOf(GEAR, CRUISE_STATE, CRUISE_SPEED)

    /** Las medidas del panel que solo existen en los casos: se leen siempre, por el turno lento. */
    val background: List<Int> =
        listOf(BEFORE_CATALYST, TURBO_SPEED, TORQUE, BATTERY_VOLTS, ALTERNATOR_TEMP, AIR_PER_CYLINDER, EGR_RATE, EGR_ASKED, GEAR) +
            listOf(CRUISE_STATE, CRUISE_SPEED) + Pids.BMW_CYLINDER_CORRECTIONS

    /**
     * Las que se leen en todas las vueltas: la EGR pedida y la que recircula. Juntas en cada
     * vuelta dicen si la EGR cierra tarde al acelerar.
     */
    val everyTurn: List<Int> = listOf(EGR_ASKED, EGR_RATE)

    /**
     * Las medidas de [group] que cambian deprisa y no se leen ya en cada vuelta: mientras el
     * grupo esta en pantalla se piden mas seguido. Las correcciones de los inyectores no: son
     * valores aprendidos que apenas se mueven.
     */
    fun watched(group: DashGroup): List<Int> = when (group) {
        DashGroup.FILTER -> listOf(SOOT, SINCE_REGEN, BEFORE_CATALYST, Pids.BMW_SOOT_PRESSURE)
        DashGroup.TURBO -> listOf(Moment.BOOST_COMMANDED, BOOST_ASKED, TURBO_SPEED, TURBO_ACTUATOR)
        DashGroup.ENGINE -> listOf(TORQUE, Pids.BMW_INJECTION)
        DashGroup.INJECTORS -> emptyList()
        DashGroup.ELECTRIC -> listOf(BATTERY_VOLTS, BATTERY_CURRENT, ALTERNATOR_TEMP)
        DashGroup.AIR -> listOf(AIR_PER_CYLINDER, EGR_RATE, AFTER_INTERCOOLER)
    }

    /**
     * La presion pedida al turbo y la real. Si el coche da las dos en un mismo mensaje se usan
     * esas, que son del mismo instante; si no, la pedida de BMW con la de admision, que puede
     * llevarle unos segundos de adelanto.
     */
    private fun boost(values: Map<Int, Double>): Pair<Double?, Double?> {
        val asked = values[Moment.BOOST_COMMANDED]
        val real = values[Moment.BOOST_ACTUAL]
        return if (asked != null && real != null) asked to real else values[BOOST_ASKED] to values[Pids.MAP]
    }

    fun tiles(group: DashGroup, values: Map<Int, Double>): List<DashTile> = when (group) {
        DashGroup.FILTER -> listOf(
            tile("Hollín", values[SOOT], 1, "g"),
            tile("Desde regenerar", values[SINCE_REGEN], 0, "km"),
            tile("Escape", values[BEFORE_CATALYST] ?: Moment.EXHAUST_TEMPS.mapNotNull { values[it] }.maxOrNull(), 0, "°C"),
        )
        DashGroup.TURBO -> listOf(
            boost(values).let { (asked, real) -> DashTile("Pide / tiene", pair(asked, real, 0, " / ", plus = false), "kPa") },
            tile("Giro", values[TURBO_SPEED], 0, "rpm", thousands = true),
            tile("Actuador", values[TURBO_ACTUATOR], 0, "%"),
        )
        DashGroup.ENGINE -> listOf(
            tile("Par", values[TORQUE], 0, "Nm"),
            DashTile("Potencia", horsepower(values)?.let { CALCULATED + n(it) } ?: MISSING, "CV"),
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
            tile("Alternador", values[ALTERNATOR_TEMP], 0, "°C"),
        )
        DashGroup.AIR -> listOf(
            tile("Por cilindro", values[AIR_PER_CYLINDER], 0, "mg"),
            tile("EGR", values[EGR_RATE], 0, "%"),
            tile("Intercooler", values[AFTER_INTERCOOLER], 0, "°C"),
        )
    }

    /**
     * Las tarjetas del centro del panel, que se pasan deslizando: el trayecto, las temperaturas y
     * el motor. Seis datos cada una, sin mas texto que la unidad. Con [real] los litros y el
     * consumo medio salen del contador de la centralita y no llevan la marca de calculado.
     */
    fun cards(
        values: Map<Int, Double>,
        tripKm: Double,
        elapsedMs: Long,
        average: Double?,
        diesel: Boolean,
        liters: Double? = null,
        real: Boolean = false,
        price: Double? = null,
    ): List<List<DashCell>> {
        val mark = if (real) "" else CALCULATED
        val per100 = Pids.litersPer100Km(values, diesel)
        val perHour = Pids.fuelRate(values, diesel)
        val minutes = elapsedMs / 60_000
        val now = when {
            per100 != null -> DashCell(CALCULATED + n(per100, 1), "L/100")
            perHour != null -> DashCell(CALCULATED + n(perHour, 1), "L/h")
            else -> DashCell(MISSING, "L/100")
        }
        return listOf(
            listOf(
                DashCell(n(tripKm, 1), "km"),
                DashCell("%d:%02d".format(minutes / 60, minutes % 60), "h:min"),
                DashCell(average?.let { mark + n(it, 1) } ?: MISSING, "Ø L/100"),
                now,
                DashCell(liters?.let { mark + n(it, 1) } ?: MISSING, "L"),
                DashCell(if (liters != null && price != null) mark + n(liters * price, 2) else MISSING, "€"),
            ),
            listOf(
                cell(values[Pids.COOLANT], 0, "°C agua"),
                cell(values[BEFORE_CATALYST] ?: Moment.EXHAUST_TEMPS.mapNotNull { values[it] }.maxOrNull(), 0, "°C escape"),
                cell(values[AFTER_INTERCOOLER] ?: values[Moment.INTAKE_TEMP], 0, "°C aire"),
                cell(values[ALTERNATOR_TEMP], 0, "°C alternador"),
                cell(values[FUEL_TEMP], 0, "°C gasóleo"),
                cell(values[OUTSIDE_TEMP], 0, "°C fuera"),
            ),
            listOf(
                cell(values[TORQUE], 0, "Nm"),
                DashCell(horsepower(values)?.let { CALCULATED + n(it) } ?: MISSING, "CV"),
                cell(Pids.boostBar(values), 2, "bar turbo"),
                cell(RAIL.firstNotNullOfOrNull { values[it] }, 0, "bar raíl"),
                cell(values[Pids.LOAD], 0, "% carga"),
                cell(values[EGR_RATE], 0, "% EGR"),
            ),
        )
    }

    private fun cell(value: Double?, decimals: Int, tag: String) = DashCell(value?.let { n(it, decimals) } ?: MISSING, tag)

    /** El grupo al que toca un aviso: es el que se queda fijo mientras dura. */
    fun group(advice: Advice): DashGroup? = when (advice.id) {
        "regeneration", "regen_stop", "regen_pending", "regen_cut", "regen_done", "rich_mix", "filter_faults", "soot_gap", "filter_idle", "filter_load", "exhaust_hot" -> DashGroup.FILTER
        "boost_low", "boost_high", "boost_uncontrolled", "air_full" -> DashGroup.TURBO
        "rail", "rail_high", "idle_rail" -> DashGroup.INJECTORS
        "voltage" -> DashGroup.ELECTRIC
        "egr", "air", "idle_air", "intake_hot", "intake_delta" -> DashGroup.AIR
        "coolant_hot", "oil_hot", "thermostat", "thermostat_drop", "idle_load", "idle_rpm",
        "lugging", "cold_push", "hot_stop", "long_idle",
        -> DashGroup.ENGINE
        else -> null
    }

    /** La potencia que entrega el motor: el ultimo par leido por las revoluciones de ahora. */
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

    /** Va delante de lo que no es una lectura del coche sino una cuenta de la app. */
    const val CALCULATED = "≈ "
    private const val RPM_NM_PER_KW = 9549.0
    private const val HP_PER_KW = 1.36
    private const val MIN_AVERAGE = 2.0
}
