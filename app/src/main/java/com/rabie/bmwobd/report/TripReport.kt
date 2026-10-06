package com.rabie.bmwobd.report

import com.rabie.bmwobd.advice.Limits
import com.rabie.bmwobd.advice.Moment
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.advice.TripFinding
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.trips.TripData
import com.rabie.bmwobd.trips.TripStats
import com.rabie.bmwobd.vehicle.EngineReference
import com.rabie.bmwobd.vehicle.Vehicle

data class ReportLine(val label: String, val value: String)

data class ReportSection(val title: String, val lines: List<ReportLine>, val note: String? = null)

/** Una aceleracion a fondo: lo que pidio la centralita y lo que llego, de media y de pico. */
data class Pull(
    val startMs: Long,
    val durationMs: Long,
    val rpmFrom: Double,
    val rpmTo: Double,
    val boostPeakBar: Double?,
    val boostCommandedBar: Double?,
    val boostActualBar: Double?,
    val railCommanded: Double?,
    val railActual: Double?,
    val mafPeak: Double?,
    val airRatio: Double?,
)

/**
 * Informe de un trayecto grabado: no solo lo que se salio de lo normal, tambien las cifras de
 * cada situacion (calentamiento, ralenti, aceleraciones a fondo, crucero) para poder compararlas
 * entre trayectos o enseñarlas a quien entienda. Son hechos calculados, no un diagnostico.
 */
object TripReport {

    private const val MAX_GAP_MS = 5_000L
    private const val WARM_TARGET = 80.0
    private const val PULL_MIN_RPM = 1500.0
    private const val PULL_MIN_MS = 2_000L
    private const val PULLS_SHOWN = 6
    private const val CRUISE_MIN_SPEED = 80.0
    private const val CRUISE_MAX_LOAD = 70.0
    private const val MIN_SECONDS = 10.0

    /** Media ponderada por el tiempo que duro cada muestra, con minimo y maximo. */
    private class Acc {
        var sum = 0.0
        var weight = 0.0
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE

        fun add(value: Double?, dtMs: Long) {
            if (value == null) return
            val w = dtMs.coerceAtLeast(1L).toDouble()
            sum += value * w
            weight += w
            if (value < min) min = value
            if (value > max) max = value
        }

        val avg: Double? get() = if (weight > 0) sum / weight else null
        val peak: Double? get() = if (weight > 0) max else null
        val low: Double? get() = if (weight > 0) min else null
    }

    private class Rows(val data: TripData) {
        fun at(id: Int, i: Int): Double? = data.series[id]?.get(i)?.takeIf { !it.isNaN() }
        fun dt(i: Int): Long = if (i == 0) 0L else (data.tMs[i] - data.tMs[i - 1]).coerceIn(0L, MAX_GAP_MS)

        /** Turbo en bar sobre la presion atmosferica, a partir de una presion absoluta en kPa. */
        fun relativeBar(absoluteKpa: Double?, i: Int): Double? {
            val baro = at(Pids.BAROMETRIC, i) ?: return null
            return absoluteKpa?.let { (it - baro) / 100.0 }
        }

        fun boostActual(i: Int): Double? = relativeBar(at(Moment.BOOST_ACTUAL, i) ?: at(Pids.MAP, i), i)
        fun boostCommanded(i: Int): Double? = relativeBar(at(Moment.BOOST_COMMANDED, i), i)
        fun voltage(i: Int): Double? = at(Moment.MODULE_VOLTAGE, i) ?: at(Pids.ADAPTER_VOLTAGE, i)
    }

    fun build(data: TripData, vehicle: Vehicle, stats: TripStats, findings: List<TripFinding>): List<ReportSection> =
        listOfNotNull(
            summary(stats),
            warmUp(data),
            idle(data, vehicle),
            pullsSection(data, vehicle),
            cruise(data),
            electric(data),
            exhaust(data),
            findingsSection(findings),
        )

    fun text(title: String, vehicle: Vehicle, sections: List<ReportSection>): String = buildString {
        appendLine("INFORME DE TRAYECTO · $title")
        appendLine(
            listOfNotNull(
                vehicle.name,
                if (vehicle.diesel) "diésel" else "gasolina",
                vehicle.displacementLiters?.let { "${n(it, 2)} L" },
            ).joinToString(" · "),
        )
        for (section in sections) {
            appendLine()
            appendLine("== ${section.title} ==")
            for (line in section.lines) appendLine("${line.label}: ${line.value}")
            section.note?.let { appendLine("Nota: $it") }
        }
        appendLine()
        append("Cifras calculadas de la grabación por OBD. Son pistas para investigar, no un diagnóstico.")
    }

    /** Milisegundos hasta que el refrigerante llega a 80 °C; 0 si ya salio caliente, null si no llego. */
    fun warmUpMs(data: TripData): Long? {
        val rows = Rows(data)
        for (i in 0 until data.size) {
            val t = rows.at(Pids.COOLANT, i) ?: continue
            if (t >= WARM_TARGET) return data.tMs[i]
        }
        return null
    }

    /** Tramos seguidos con carga alta y el motor ya en la zona del turbo. */
    fun pulls(data: TripData, vehicle: Vehicle): List<Pull> {
        val rows = Rows(data)
        val result = mutableListOf<Pull>()
        var start = -1
        for (i in 0..data.size) {
            val inPull = i < data.size &&
                (rows.at(Pids.LOAD, i) ?: 0.0) >= Limits.PULL_MIN_LOAD &&
                (rows.at(Pids.RPM, i) ?: 0.0) >= PULL_MIN_RPM
            if (inPull && start < 0) start = i
            if (!inPull && start >= 0) {
                val end = i - 1
                if (data.tMs[end] - data.tMs[start] >= PULL_MIN_MS) result += pull(rows, vehicle, start, end)
                start = -1
            }
        }
        return result
    }

    private fun pull(rows: Rows, vehicle: Vehicle, start: Int, end: Int): Pull {
        val boost = Acc()
        val commanded = Acc()
        val actualWhenCommanded = Acc()
        val railCommanded = Acc()
        val railActual = Acc()
        val maf = Acc()
        val ratio = Acc()
        for (i in start..end) {
            val dt = rows.dt(i)
            boost.add(rows.boostActual(i), dt)
            val wanted = rows.boostCommanded(i)
            if (wanted != null) {
                commanded.add(wanted, dt)
                actualWhenCommanded.add(rows.boostActual(i), dt)
            }
            railCommanded.add(rows.at(Moment.RAIL_COMMANDED, i), dt)
            railActual.add(rows.at(Moment.RAIL_ACTUAL, i) ?: rows.at(Moment.RAIL, i), dt)
            val air = rows.at(Moment.MAF, i)
            maf.add(air, dt)
            val values = rows.data.series.mapNotNull { (id, s) -> s[i].takeIf { !it.isNaN() }?.let { id to it } }.toMap()
            val theoretical = Moment(values, 0, null, vehicle).theoreticalAir
            if (air != null && theoretical != null && theoretical > 0) ratio.add(air / theoretical, dt)
        }
        return Pull(
            startMs = rows.data.tMs[start],
            durationMs = rows.data.tMs[end] - rows.data.tMs[start],
            rpmFrom = rows.at(Pids.RPM, start) ?: 0.0,
            rpmTo = rows.at(Pids.RPM, end) ?: 0.0,
            boostPeakBar = boost.peak,
            boostCommandedBar = commanded.avg,
            boostActualBar = actualWhenCommanded.avg,
            railCommanded = railCommanded.avg,
            railActual = railActual.avg,
            mafPeak = maf.peak,
            airRatio = ratio.avg,
        )
    }

    private fun summary(stats: TripStats) = ReportSection(
        "Resumen",
        listOfNotNull(
            ReportLine("Duración", clock(stats.durationMs)),
            stats.distanceKm?.let { ReportLine("Distancia", "${n(it, 1)} km") },
            stats.avgSpeed?.let { ReportLine("Velocidad media", "${n(it)} km/h") },
            stats.maxSpeed?.let { ReportLine("Velocidad máxima", "${n(it)} km/h") },
            stats.litersPer100Km?.let { ReportLine("Consumo medio", "${n(it, 1)} L/100 km") },
            stats.fuelLiters?.let { ReportLine("Combustible gastado", "${n(it, 2)} L") },
            stats.maxRpm?.let { ReportLine("RPM máximas", n(it)) },
        ),
    )

    private fun warmUp(data: TripData): ReportSection? {
        val rows = Rows(data)
        val first = (0 until data.size).firstNotNullOfOrNull { rows.at(Pids.COOLANT, it) } ?: return null
        val reached = warmUpMs(data)
        val stable = Acc()
        val all = Acc()
        val oil = Acc()
        var oilFirst: Double? = null
        for (i in 0 until data.size) {
            val coolant = rows.at(Pids.COOLANT, i)
            all.add(coolant, rows.dt(i))
            if (reached != null && data.tMs[i] >= reached && (rows.at(Pids.SPEED, i) ?: 0.0) >= Limits.ROAD_SPEED) {
                stable.add(coolant, rows.dt(i))
            }
            val o = rows.at(Pids.OIL, i)
            if (oilFirst == null) oilFirst = o
            oil.add(o, rows.dt(i))
        }
        return ReportSection(
            "Calentamiento",
            listOfNotNull(
                ReportLine("Refrigerante al salir", "${n(first)} °C"),
                ReportLine(
                    "Tiempo hasta 80 °C",
                    when (reached) {
                        null -> "no llegó en todo el trayecto"
                        0L -> "ya estaba caliente"
                        else -> clock(reached)
                    },
                ),
                stable.avg?.let { ReportLine("Refrigerante en carretera, ya caliente", "${n(it)} °C de media (${n(stable.low!!)}–${n(stable.peak!!)})") },
                all.peak?.let { ReportLine("Refrigerante máximo", "${n(it)} °C") },
                oilFirst?.let { ReportLine("Aceite al salir", "${n(it)} °C") },
                oil.peak?.let { ReportLine("Aceite máximo", "${n(it)} °C") },
            ),
        )
    }

    private fun idle(data: TripData, vehicle: Vehicle): ReportSection? {
        val rows = Rows(data)
        val rpm = Acc()
        val load = Acc()
        val maf = Acc()
        val rail = Acc()
        val volts = Acc()
        val filter = Acc()
        for (i in 0 until data.size) {
            val r = rows.at(Pids.RPM, i) ?: continue
            val isIdle = r > 400 && r < Limits.IDLE_MAX_RPM &&
                (rows.at(Pids.SPEED, i) ?: 99.0) < Limits.IDLE_MAX_SPEED &&
                (rows.at(Pids.COOLANT, i) ?: 0.0) >= Limits.WARM_COOLANT
            if (!isIdle) continue
            val dt = rows.dt(i)
            rpm.add(r, dt)
            load.add(rows.at(Pids.LOAD, i), dt)
            maf.add(rows.at(Moment.MAF, i), dt)
            rail.add(rows.at(Moment.RAIL_ACTUAL, i) ?: rows.at(Moment.RAIL, i), dt)
            volts.add(rows.voltage(i), dt)
            filter.add(rows.at(Moment.FILTER_PRESSURE, i), dt)
        }
        if (rpm.weight / 1000 < MIN_SECONDS) return null
        return ReportSection(
            "Ralentí en caliente",
            listOfNotNull(
                ReportLine("Tiempo al ralentí", clock(rpm.weight.toLong())),
                rpm.avg?.let { ReportLine("Revoluciones", "${n(it)} rpm (${n(rpm.low!!)}–${n(rpm.peak!!)})") },
                load.avg?.let { ReportLine("Carga", "${n(it)} %") },
                maf.avg?.let { air ->
                    // mg por embolada es la unidad en que lo dan las herramientas de taller.
                    val perStroke = rpm.avg?.takeIf { it > 0 }?.let { air / (it / 60.0 * 2.0) * 1000.0 }
                    ReportLine("Caudal de aire", "${n(air, 1)} g/s" + (perStroke?.let { " (${n(it)} mg por embolada)" } ?: ""))
                },
                rail.avg?.let { ReportLine("Presión de raíl", "${n(it)} bar") },
                volts.avg?.let { ReportLine("Tensión", "${n(it, 1)} V") },
                filter.avg?.let { ReportLine("Presión diferencial del filtro", "${n(it * 10)} mbar") },
            ),
            EngineReference.of(vehicle)?.let {
                "Referencia ${it.name} (${it.source}): ${n(it.idleRpm)} rpm, " +
                    "carga ${n(it.idleLoad.start)}–${n(it.idleLoad.endInclusive)} %, " +
                    "aire ${n(it.idleAirGramsPerSecond.start, 1)}–${n(it.idleAirGramsPerSecond.endInclusive, 1)} g/s con la EGR abierta, " +
                    "raíl ${n(it.idleRailBar.start)}–${n(it.idleRailBar.endInclusive)} bar."
            },
        )
    }

    private fun pullsSection(data: TripData, vehicle: Vehicle): ReportSection? {
        val all = pulls(data, vehicle)
        if (all.isEmpty()) return null
        val lines = mutableListOf(ReportLine("Aceleraciones a fondo", all.size.toString()))
        val peak = all.mapNotNull { it.boostPeakBar }.maxOrNull()
        peak?.let { lines += ReportLine("Turbo máximo del trayecto", "${n(it, 2)} bar") }
        for (pull in all.sortedByDescending { it.durationMs }.take(PULLS_SHOWN).sortedBy { it.startMs }) {
            val parts = listOfNotNull(
                "${n(pull.rpmFrom)}→${n(pull.rpmTo)} rpm",
                pull.boostCommandedBar?.let { wanted ->
                    pull.boostActualBar?.let { got -> "turbo pedido ${n(wanted, 2)} / real ${n(got, 2)} bar" }
                } ?: pull.boostPeakBar?.let { "turbo máx ${n(it, 2)} bar" },
                pull.railCommanded?.let { wanted ->
                    pull.railActual?.let { got -> "raíl pedido ${n(wanted)} / real ${n(got)} bar" }
                } ?: pull.railActual?.let { "raíl ${n(it)} bar" },
                pull.mafPeak?.let { "aire máx ${n(it)} g/s" },
                pull.airRatio?.let { "llenado ${n(it * 100)} %" },
            )
            lines += ReportLine("Min ${clock(pull.startMs)}, ${n(pull.durationMs / 1000.0, 1)} s", parts.joinToString(" · "))
        }
        return ReportSection(
            "Aceleraciones a fondo",
            lines,
            "Si el turbo pedido también es bajo, es la centralita la que limita. Si pide mucho y el real no llega, falta aire: fuga, turbo o actuador." +
                (EngineReference.of(vehicle)?.let {
                    " Referencia ${it.name} a fondo y 4000 rpm (${it.source}): " +
                        "${n(it.fullLoadMapKpa.start)}–${n(it.fullLoadMapKpa.endInclusive)} kPa de admisión, " +
                        "${n(it.fullLoadAirGramsPerSecond.start)}–${n(it.fullLoadAirGramsPerSecond.endInclusive)} g/s de aire, " +
                        "raíl hasta ${n(it.maxRailBar)} bar."
                } ?: ""),
        )
    }

    private fun cruise(data: TripData): ReportSection? {
        val rows = Rows(data)
        val speed = Acc()
        val rpm = Acc()
        val rate = Acc()
        val delta = Acc()
        for (i in 0 until data.size) {
            val v = rows.at(Pids.SPEED, i) ?: continue
            if (v < CRUISE_MIN_SPEED || (rows.at(Pids.LOAD, i) ?: 100.0) > CRUISE_MAX_LOAD) continue
            val dt = rows.dt(i)
            speed.add(v, dt)
            rpm.add(rows.at(Pids.RPM, i), dt)
            rate.add(rows.at(Pids.FUEL_RATE, i), dt)
            val intake = rows.at(Moment.INTAKE_TEMP, i)
            val ambient = rows.at(Moment.AMBIENT_TEMP, i)
            if (intake != null && ambient != null) delta.add(intake - ambient, dt)
        }
        if (speed.weight / 1000 < MIN_SECONDS) return null
        val avgSpeed = speed.avg ?: return null
        return ReportSection(
            "Crucero (más de ${n(CRUISE_MIN_SPEED)} km/h, sin exigir)",
            listOfNotNull(
                ReportLine("Tiempo", clock(speed.weight.toLong())),
                ReportLine("Velocidad", "${n(avgSpeed)} km/h"),
                rpm.avg?.let { ReportLine("Revoluciones", "${n(it)} rpm") },
                rate.avg?.let { ReportLine("Consumo", "${n(it / avgSpeed * 100, 1)} L/100 km") },
                delta.avg?.let { ReportLine("Admisión sobre el exterior", "+${n(it)} °C de media, +${n(delta.peak!!)} de máximo") },
            ),
        )
    }

    private fun electric(data: TripData): ReportSection? {
        val rows = Rows(data)
        val running = Acc()
        for (i in 0 until data.size) {
            if ((rows.at(Pids.RPM, i) ?: 0.0) > 400) running.add(rows.voltage(i), rows.dt(i))
        }
        val avg = running.avg ?: return null
        return ReportSection(
            "Tensión con el motor en marcha",
            listOf(
                ReportLine("Media", "${n(avg, 1)} V"),
                ReportLine("Mínima y máxima", "${n(running.low!!, 1)} – ${n(running.peak!!, 1)} V"),
            ),
        )
    }

    private fun exhaust(data: TripData): ReportSection? {
        val rows = Rows(data)
        val filter = Acc()
        val gases = Acc()
        for (i in 0 until data.size) {
            filter.add(rows.at(Moment.FILTER_PRESSURE, i), rows.dt(i))
            gases.add(Moment.EXHAUST_TEMPS.mapNotNull { rows.at(it, i) }.maxOrNull(), rows.dt(i))
        }
        val lines = listOfNotNull(
            filter.avg?.let { ReportLine("Presión diferencial del filtro", "${n(it * 10)} mbar de media, ${n(filter.peak!! * 10)} de máximo") },
            gases.avg?.let { ReportLine("Gases de escape", "${n(it)} °C de media, ${n(gases.peak!!)} de máximo") },
        )
        return if (lines.isEmpty()) null else ReportSection("Escape y filtro de partículas", lines)
    }

    private fun findingsSection(findings: List<TripFinding>): ReportSection {
        val lines = findings.map {
            val level = when (it.advice.severity) {
                Severity.ALERT -> "ROJO"
                Severity.WARN -> "Aviso"
                Severity.INFO -> "Consejo"
            }
            ReportLine("$level · ${it.advice.title} (${clock(it.seconds * 1000)})", it.advice.detail)
        }
        val none = ReportLine("Ninguno", "ninguna regla ha saltado; no significa que el coche esté revisado")
        return ReportSection("Indicios", lines.ifEmpty { listOf(none) })
    }

    private fun n(value: Double, decimals: Int = 0) = "%.${decimals}f".format(value)

    private fun clock(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds >= 3600) {
            "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        } else {
            "%02d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
