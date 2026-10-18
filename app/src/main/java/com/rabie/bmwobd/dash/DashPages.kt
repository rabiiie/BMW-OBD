package com.rabie.bmwobd.dash

import com.rabie.bmwobd.advice.Advice
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.obd.BmwFault
import com.rabie.bmwobd.obd.Pids

/**
 * Una tarjeta grande del panel apaisado: un tema entero. [status] es la linea de arriba, con el
 * aviso de ese tema si hay uno ([alert] su gravedad) o su estado si no; [note] lo que hay que hacer.
 */
data class DashPage(
    val group: DashGroup?,
    val title: String,
    val status: String?,
    val alert: Severity?,
    val cells: List<DashItem>,
    val note: String?,
)

/** Un dato de una tarjeta: que es ([name]), la cifra y su unidad, que va aparte junto a la cifra. */
data class DashItem(val name: String, val value: String, val unit: String)

/** Lo que el panel sabe del trayecto en curso, para la tarjeta del trayecto. */
data class TripNow(
    val km: Double,
    val elapsedMs: Long,
    val average: Double?,
    val diesel: Boolean,
    val liters: Double?,
    val real: Boolean,
    val price: Double?,
)

object DashPages {

    /** El orden de las tarjetas. El aire va con el turbo: es el mismo sistema. */
    private val ORDER: List<DashGroup?> = listOf(null, DashGroup.FILTER, DashGroup.TURBO, DashGroup.ENGINE, DashGroup.INJECTORS, DashGroup.ELECTRIC)

    /** Sin las medidas propias de BMW solo hay datos para estas. */
    private val GENERIC: List<DashGroup?> = listOf(null, DashGroup.TURBO, DashGroup.ENGINE, DashGroup.ELECTRIC)

    private fun pageGroup(group: DashGroup): DashGroup = if (group == DashGroup.AIR) DashGroup.TURBO else group

    /** En que tarjeta de [pages] cae un aviso del grupo [group]; null si en ninguna. */
    fun indexOf(pages: List<DashPage>, group: DashGroup): Int? = pages.indexOfFirst { it.group == pageGroup(group) }.takeIf { it >= 0 }

    fun pages(values: Map<Int, Double>, trip: TripNow, advice: List<Advice>, bmw: Boolean): List<DashPage> =
        (if (bmw) ORDER else GENERIC).map { group ->
            val top = advice.filter { item -> Dashboard.group(item)?.let(::pageGroup) == group && group != null }
                .maxByOrNull { it.severity.ordinal }
            when (group) {
                null -> DashPage(null, "Trayecto", null, null, tripCells(values, trip), null)
                DashGroup.FILTER -> page(group, "Filtro de partículas", top, filterStatus(values), filterCells(values))
                DashGroup.TURBO, DashGroup.AIR -> page(DashGroup.TURBO, "Turbo y aire", top, null, turboCells(values))
                DashGroup.ENGINE -> page(group, "Motor", top, null, engineCells(values))
                DashGroup.INJECTORS -> page(group, "Inyectores", top, null, injectorCells(values))
                DashGroup.ELECTRIC -> page(group, "Eléctrico", top, null, electricCells(values))
            }
        }

    private fun page(group: DashGroup, title: String, top: Advice?, status: String?, cells: List<DashItem>) =
        DashPage(group, title, top?.title ?: status, top?.severity, cells, top?.detail)

    private fun filterStatus(values: Map<Int, Double>): String? {
        val request = values[Pids.BMW_REGEN_REQUEST]?.let { "Petición ${Dashboard.n(it)}: ${Pids.regenRequestText(it)}" }
        val faults = values[Pids.BMW_FILTER_FAULTS]?.takeIf { it >= 1 }?.let(BmwFault::filterLevelText)
        return listOfNotNull(request, faults).joinToString(" · ").ifEmpty { null }
    }

    private fun tripCells(values: Map<Int, Double>, trip: TripNow): List<DashItem> =
        Dashboard.cards(values, trip.km, trip.elapsedMs, trip.average, trip.diesel, trip.liters, trip.real, trip.price).first().map {
            val (name, unit) = TRIP_NAMES[it.tag] ?: (it.tag to "")
            DashItem(name, it.value, unit)
        } + listOf(cell(values[Pids.COOLANT], 0, "Motor", "°C"), cell(values[OUTSIDE_TEMP], 0, "Fuera", "°C"))

    /** Los datos del trayecto vienen del panel vertical con la unidad por nombre; aqui se separan. */
    private val TRIP_NAMES = mapOf(
        "km" to ("Distancia" to "km"),
        "h:min" to ("Tiempo" to "h:min"),
        "Ø L/100" to ("Consumo medio" to "L/100"),
        "L/100" to ("Consumo ahora" to "L/100"),
        "L/h" to ("Consumo ahora" to "L/h"),
        "L" to ("Gasóleo gastado" to "L"),
        "€" to ("Coste" to "€"),
    )

    private fun filterCells(values: Map<Int, Double>): List<DashItem> {
        val pressure = values[FILTER_PRESSURE]
        val flow = values[FILTER_FLOW]
        val boost = values[Pids.MAP]
        val turbine = values[BEFORE_TURBINE]
        // La peticion va en su celda: la linea de estado la tapa cualquier aviso del filtro.
        val request = values[Pids.BMW_REGEN_REQUEST]
        return listOf(
            cell(request, 0, "Regeneración", Pids.regenNow(values).orEmpty()),
            DashItem("Avería filtro", filterFault(values[Pids.BMW_FILTER_FAULTS]), ""),
            cell(values[Pids.BMW_SOOT_MEASURED], 1, "Hollín medido", "g"),
            cell(values[Pids.BMW_SOOT_MODEL], 1, "Hollín calculado", "g"),
            cell(values[SINCE_REGEN], 0, "Sin regenerar", "km"),
            cell(exhaust(values), 0, "Escape", "°C"),
            cell(pressure, 0, "Presión diferencial", "hPa"),
            cell(flow, 0, "Caudal de gas", "m³/h"),
            calculated(if (pressure != null && flow != null && flow >= MIN_FLOW) pressure / flow * PER_100 else null, 1, "Resistencia", ""),
            calculated(if (turbine != null && boost != null && boost >= BOOSTING_KPA) turbine / (boost * HPA_PER_KPA) else null, 2, "Escape / admisión", ""),
        )
    }

    /** La mas grave de 480A y 481A presente, o ninguna; sin leer, el guion de siempre. */
    private fun filterFault(level: Double?): String = when {
        level == null -> Dashboard.MISSING
        level >= 2 -> "481A"
        level >= 1 -> "480A"
        else -> "ninguna"
    }

    private fun turboCells(values: Map<Int, Double>): List<DashItem> {
        val asked = values[Moment.BOOST_COMMANDED] ?: values[BOOST_ASKED]
        val real = values[Moment.BOOST_ACTUAL] ?: values[Pids.MAP]
        return listOf(
            cell(asked, 0, "Presión pedida", "kPa"),
            cell(real, 0, "Presión real", "kPa"),
            cell(values[TURBO_SPEED]?.let { it / 1000.0 }, 0, "Giro del turbo", "mil rpm"),
            cell(values[TURBO_ACTUATOR], 0, "Actuador", "%"),
            cell(values[BEFORE_TURBINE], 0, "Antes de la turbina", "hPa"),
            if (values[AIR_PER_CYLINDER] != null) cell(values[AIR_PER_CYLINDER], 0, "Aire por cilindro", "mg") else cell(values[Pids.MAF], 0, "Aire", "g/s"),
            cell(values[EGR_RATE], 0, "EGR", "%"),
            cell(values[AFTER_INTERCOOLER] ?: values[Moment.INTAKE_TEMP], 0, "Aire de admisión", "°C"),
        )
    }

    private fun engineCells(values: Map<Int, Double>): List<DashItem> = listOf(
        cell(values[Pids.COOLANT], 0, "Motor", "°C"),
        cell(exhaust(values), 0, "Escape", "°C"),
        cell(values[TORQUE], 0, "Par", "Nm"),
        calculated(Dashboard.horsepower(values), 0, "Potencia", "CV"),
        cell(values[Pids.LOAD], 0, "Carga", "%"),
        cell(values[FUEL_TEMP], 0, "Gasóleo", "°C"),
        // Parado la centralita no tiene desfase y da su valor de sin dato, que sale como 0.
        cell(values[Pids.bmw(Pids.BMW_TIMING_OFFSET_ADDRESS)]?.takeIf { (values[Pids.RPM] ?: 0.0) > 0 }, 2, "Desfase de cadena", "°"),
        cell(values[SMOKE_LIMIT], 1, "Límite de humo", "mg"),
    )

    private fun injectorCells(values: Map<Int, Double>): List<DashItem> =
        Pids.BMW_CYLINDER_CORRECTIONS.mapIndexed { index, id -> signedCell(values[id], 2, "Cilindro ${index + 1}", "mg") } + listOf(
            cell(values[Pids.BMW_INJECTION], 1, "Inyecta", "mg"),
            signedCell(values[GENERAL_CORRECTION], 2, "Corrección general", "mg"),
            cell(values[Moment.RAIL_ACTUAL] ?: values[Moment.RAIL], 0, "Raíl", "bar"),
            cell(values[RAIL_ASKED], 0, "Raíl pedido", "bar"),
        )

    private fun electricCells(values: Map<Int, Double>): List<DashItem> = listOf(
        cell(values[BATTERY_VOLTS] ?: values[Moment.MODULE_VOLTAGE] ?: values[Pids.ADAPTER_VOLTAGE], 1, "Batería", "V"),
        signedCell(values[BATTERY_CURRENT], 1, "Corriente batería", "A"),
        cell(values[ALTERNATOR_ASKED], 1, "Pide al alternador", "V"),
        cell(values[ALTERNATOR_LOAD], 0, "Carga alternador", "%"),
        cell(values[ALTERNATOR_TEMP], 0, "Alternador", "°C"),
        cell(values[RECOVERED], 1, "Recuperados", "Ah"),
    )

    private fun exhaust(values: Map<Int, Double>): Double? = values[BEFORE_CATALYST] ?: Moment.EXHAUST_TEMPS.mapNotNull { values[it] }.maxOrNull()

    private fun cell(value: Double?, decimals: Int, name: String, unit: String) =
        DashItem(name, value?.let { Dashboard.n(it, decimals) } ?: Dashboard.MISSING, unit)

    private fun calculated(value: Double?, decimals: Int, name: String, unit: String) =
        DashItem(name, value?.let { Dashboard.CALCULATED + Dashboard.n(it, decimals) } ?: Dashboard.MISSING, unit)

    private fun signedCell(value: Double?, decimals: Int, name: String, unit: String) = DashItem(
        name,
        value?.let { Dashboard.n(it, decimals).let { text -> if (it > 0 && text.any { c -> c in '1'..'9' }) "+$text" else text } } ?: Dashboard.MISSING,
        unit,
    )

    private const val OUTSIDE_TEMP = 0x46
    private val SINCE_REGEN = Pids.bmw(Pids.BMW_SINCE_REGEN_ADDRESS)
    private val FILTER_PRESSURE = Pids.bmw(0x0424)
    private val FILTER_FLOW = Pids.bmw(0x0C83)
    private val BEFORE_TURBINE = Pids.bmw(0x03E0)
    private val BEFORE_CATALYST = Pids.bmw(0x041E)
    private val BOOST_ASKED = Pids.bmw(0x01F4)
    private val TURBO_SPEED = Pids.bmw(0x0C86)
    private val TURBO_ACTUATOR = Pids.bmw(0x0BEB)
    private val AIR_PER_CYLINDER = Pids.bmw(0x0709)
    private val EGR_RATE = Pids.bmw(0x07AD)
    private val AFTER_INTERCOOLER = Pids.bmw(0x076F)
    private val TORQUE = Pids.bmw(0x07D1)
    private val FUEL_TEMP = Pids.bmw(0x0385)
    private val SMOKE_LIMIT = Pids.bmw(0x06C2)
    private val GENERAL_CORRECTION = Pids.bmw(0x14D3)
    private val RAIL_ASKED = Pids.bmw(0x0641)
    private val BATTERY_VOLTS = Pids.bmw(0x01B9)
    private val BATTERY_CURRENT = Pids.bmw(0x01B2)
    private val ALTERNATOR_ASKED = Pids.bmw(0x01AF)
    private val ALTERNATOR_LOAD = Pids.bmw(0x0186)
    private val ALTERNATOR_TEMP = Pids.bmw(0x0E43)
    private val RECOVERED = Pids.bmw(0x10D5)
    private const val MIN_FLOW = 150.0
    private const val PER_100 = 100.0
    private const val BOOSTING_KPA = 130.0
    private const val HPA_PER_KPA = 10.0
}
