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
    val cells: List<DashCell>,
    val note: String?,
)

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

    private fun page(group: DashGroup, title: String, top: Advice?, status: String?, cells: List<DashCell>) =
        DashPage(group, title, top?.title ?: status, top?.severity, cells, top?.detail)

    private fun filterStatus(values: Map<Int, Double>): String? {
        val request = values[Pids.BMW_REGEN_REQUEST]?.let { "Petición ${Dashboard.n(it)}: ${Pids.regenRequestText(it)}" }
        val faults = values[Pids.BMW_FILTER_FAULTS]?.takeIf { it >= 1 }?.let(BmwFault::filterLevelText)
        return listOfNotNull(request, faults).joinToString(" · ").ifEmpty { null }
    }

    private fun tripCells(values: Map<Int, Double>, trip: TripNow): List<DashCell> =
        Dashboard.cards(values, trip.km, trip.elapsedMs, trip.average, trip.diesel, trip.liters, trip.real, trip.price).first() +
            listOf(cell(values[Pids.COOLANT], 0, "°C motor"), cell(values[OUTSIDE_TEMP], 0, "°C fuera"))

    private fun filterCells(values: Map<Int, Double>): List<DashCell> {
        val pressure = values[FILTER_PRESSURE]
        val flow = values[FILTER_FLOW]
        val boost = values[Pids.MAP]
        val turbine = values[BEFORE_TURBINE]
        return listOf(
            cell(values[Pids.BMW_SOOT_MEASURED], 1, "g hollín medido"),
            cell(values[Pids.BMW_SOOT_MODEL], 1, "g hollín calculado"),
            cell(values[SINCE_REGEN], 0, "km sin regenerar"),
            cell(exhaust(values), 0, "°C escape"),
            cell(pressure, 0, "hPa en el filtro"),
            cell(flow, 0, "m³/h de gas"),
            calculated(if (pressure != null && flow != null && flow >= MIN_FLOW) pressure / flow * PER_100 else null, 1, "resistencia"),
            calculated(if (turbine != null && boost != null && boost >= BOOSTING_KPA) turbine / (boost * HPA_PER_KPA) else null, 2, "escape / turbo"),
        )
    }

    private fun turboCells(values: Map<Int, Double>): List<DashCell> {
        val asked = values[Moment.BOOST_COMMANDED] ?: values[BOOST_ASKED]
        val real = values[Moment.BOOST_ACTUAL] ?: values[Pids.MAP]
        return listOf(
            cell(asked, 0, "kPa pide"),
            cell(real, 0, "kPa tiene"),
            cell(values[TURBO_SPEED]?.let { it / 1000.0 }, 0, "mil rpm turbo"),
            cell(values[TURBO_ACTUATOR], 0, "% actuador"),
            cell(values[BEFORE_TURBINE], 0, "hPa antes turbina"),
            cell(values[AIR_PER_CYLINDER] ?: values[Pids.MAF], 0, if (values[AIR_PER_CYLINDER] != null) "mg aire / cil." else "g/s aire"),
            cell(values[EGR_RATE], 0, "% EGR"),
            cell(values[AFTER_INTERCOOLER] ?: values[Moment.INTAKE_TEMP], 0, "°C aire admisión"),
        )
    }

    private fun engineCells(values: Map<Int, Double>): List<DashCell> = listOf(
        cell(values[Pids.COOLANT], 0, "°C motor"),
        cell(exhaust(values), 0, "°C escape"),
        cell(values[TORQUE], 0, "Nm par"),
        calculated(Dashboard.horsepower(values), 0, "CV"),
        cell(values[Pids.LOAD], 0, "% carga"),
        cell(values[FUEL_TEMP], 0, "°C gasóleo"),
        cell(values[Pids.bmw(Pids.BMW_TIMING_OFFSET_ADDRESS)]?.takeIf { it != 0.0 }, 2, "° cadena"),
        cell(values[SMOKE_LIMIT], 1, "mg límite humo"),
    )

    private fun injectorCells(values: Map<Int, Double>): List<DashCell> =
        Pids.BMW_CYLINDER_CORRECTIONS.mapIndexed { index, id -> signedCell(values[id], 2, "mg cil. ${index + 1}") } + listOf(
            cell(values[Pids.BMW_INJECTION], 1, "mg inyecta"),
            signedCell(values[GENERAL_CORRECTION], 2, "mg corrección"),
            cell(values[Moment.RAIL_ACTUAL] ?: values[Moment.RAIL], 0, "bar raíl"),
            cell(values[RAIL_ASKED], 0, "bar pide"),
        )

    private fun electricCells(values: Map<Int, Double>): List<DashCell> = listOf(
        cell(values[BATTERY_VOLTS] ?: values[Moment.MODULE_VOLTAGE] ?: values[Pids.ADAPTER_VOLTAGE], 1, "V batería"),
        signedCell(values[BATTERY_CURRENT], 1, "A batería"),
        cell(values[ALTERNATOR_ASKED], 1, "V pide alternador"),
        cell(values[ALTERNATOR_LOAD], 0, "% alternador"),
        cell(values[ALTERNATOR_TEMP], 0, "°C alternador"),
        cell(values[RECOVERED], 1, "Ah recuperados"),
    )

    private fun exhaust(values: Map<Int, Double>): Double? = values[BEFORE_CATALYST] ?: Moment.EXHAUST_TEMPS.mapNotNull { values[it] }.maxOrNull()

    private fun cell(value: Double?, decimals: Int, tag: String) = DashCell(value?.let { Dashboard.n(it, decimals) } ?: Dashboard.MISSING, tag)

    private fun calculated(value: Double?, decimals: Int, tag: String) =
        DashCell(value?.let { Dashboard.CALCULATED + Dashboard.n(it, decimals) } ?: Dashboard.MISSING, tag)

    private fun signedCell(value: Double?, decimals: Int, tag: String) = DashCell(
        value?.let { Dashboard.n(it, decimals).let { text -> if (it > 0 && text.any { c -> c in '1'..'9' }) "+$text" else text } } ?: Dashboard.MISSING,
        tag,
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
