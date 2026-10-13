package com.rabie.bmwobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.advice.Advisor
import com.rabie.bmwobd.health.Health
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.report.ReportLine
import com.rabie.bmwobd.report.ReportSection
import com.rabie.bmwobd.report.TripReport
import com.rabie.bmwobd.trips.TripData
import com.rabie.bmwobd.trips.TripEntry
import com.rabie.bmwobd.trips.TripFuel
import com.rabie.bmwobd.trips.TripHealth
import com.rabie.bmwobd.trips.TripStats
import com.rabie.bmwobd.trips.TripStore
import com.rabie.bmwobd.vehicle.Vehicle
import com.rabie.bmwobd.vehicle.VehicleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen(store: TripStore, vehicles: VehicleStore, fuelPrice: Double? = null, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<TripEntry?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val trips by produceState<List<TripEntry>?>(initialValue = null, version) {
        value = withContext(Dispatchers.IO) { store.list() }
    }

    val current = selected
    if (current == null) {
        TripList(trips, vehicles, fuelPrice, onOpen = { selected = it }, modifier = modifier)
    } else {
        BackHandler { selected = null }
        TripDetail(
            store = store,
            vehicle = current.vehicleKey?.let(vehicles::byKey) ?: Vehicle.GENERIC,
            entry = current,
            fuelPrice = fuelPrice,
            onBack = { selected = null },
            onDelete = {
                store.delete(current.file)
                selected = null
                version++
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun TripList(
    trips: List<TripEntry>?,
    vehicles: VehicleStore,
    fuelPrice: Double?,
    onOpen: (TripEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("Trayectos", color = Bmw.Text)
        when {
            trips == null -> Text("Cargando…", color = Bmw.TextDim)
            trips.isEmpty() -> Text(
                "Todavía no hay trayectos. Cada conexión se graba sola; prueba con el coche simulado.",
                color = Bmw.TextDim,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val timed = trips.filter { !it.simulated && it.timing != null }.take(TIMING_ROWS)
                if (timed.isNotEmpty()) item(key = "distribucion") { TimingTable(timed) }
                val worn = trips.filter { !it.simulated && it.wear != null }.take(TIMING_ROWS)
                if (worn.isNotEmpty()) item(key = "desgaste") { WearTable(worn) }
                val checked = trips.filter { !it.simulated && it.health != null }.take(TIMING_ROWS)
                if (checked.isNotEmpty()) item(key = "salud") { HealthTable(checked) }
                if (checked.isNotEmpty()) item(key = "salud2") { RunningTable(checked) }
                val spent = trips.filter { !it.simulated && (it.fuel?.km ?: 0.0) >= 1.0 }.take(TIMING_ROWS)
                if (spent.isNotEmpty()) item(key = "consumo") { FuelTable(spent, fuelPrice) }
                items(trips, key = { it.file.name }) { trip ->
                    val name = remember(trip.vehicleKey) { trip.vehicleKey?.let(vehicles::byKey)?.name }
                    TripRow(trip, name, onOpen)
                }
            }
        }
    }
}

/**
 * El desfase de la distribucion trayecto a trayecto, del mas reciente al mas antiguo, con la
 * temperatura a la que arranco el motor. Lo que importa es si crece con el tiempo.
 */
@Composable
private fun TimingTable(trips: List<TripEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Label("Cadena de distribución: desfase por trayecto")
        for (trip in trips) {
            val timing = trip.timing ?: continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatStart(trip.startMillis), color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                timing.startCoolant?.let { Text(formatValue(it, 0) + " °C", color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.padding(end = 14.dp)) }
                Text(
                    "%.2f°".format(timing.median) + if (timing.steady) "" else " *",
                    color = Bmw.Text,
                    fontSize = 15.sp,
                )
            }
        }
        Text("Con asterisco, el valor se movió durante el trayecto: se enseña la mediana.", color = Bmw.TextDim, fontSize = 12.sp)
    }
}

/** El desgaste aprendido trayecto a trayecto: lo que importa es si alguna columna va creciendo. */
@Composable
private fun WearTable(trips: List<TripEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Label("Desgaste aprendido por trayecto")
        @Composable
        fun cells(date: String, values: List<String>, color: Color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1.1f), maxLines = 1)
                for (value in values) Text(value, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
        cells("", listOf("Raíl", "Lambda", "EGR", "Enfr.", "Filtro", "Iny."), Bmw.TextDim)
        for (trip in trips) {
            val wear = trip.wear ?: continue
            fun text(value: Double?, decimals: Int) = value?.let { "%.${decimals}f".format(it) } ?: "—"
            cells(
                formatDay(trip.startMillis),
                listOf(
                    text(wear.railValve, 1), text(wear.lambda, 1), text(wear.egrZero, 2), text(wear.egrCooler, 2),
                    text(wear.filterZero, 1), text(wear.largestInjectorTime, 0),
                ),
                Bmw.Text,
            )
        }
        Text(
            "Raíl, lambda y EGR en %. Enfriador: rendimiento de 0 a 1. Filtro en hPa. Iny.: el mayor tiempo aprendido de un inyector, en µs.",
            color = Bmw.TextDim,
            fontSize = 12.sp,
        )
    }
}

/**
 * Lo que cada trayecto dice del calentamiento, el arranque y el turbo. No hay limites de fabrica:
 * lo que delata un fallo es que una columna cambie respecto a los trayectos de antes.
 */
@Composable
private fun HealthTable(trips: List<TripEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Label("Salud por trayecto")
        @Composable
        fun cells(date: String, values: List<String>, color: Color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1.1f), maxLines = 1)
                for (value in values) Text(value, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
        cells("", listOf("A 80 °C", "Caliente", "Parado", "Arranq.", "Batería", "Turbo"), Bmw.TextDim)
        for (trip in trips) {
            val health = trip.health ?: continue
            fun text(value: Double?, decimals: Int) = value?.let { "%.${decimals}f".format(it) } ?: "—"
            cells(
                formatDay(trip.startMillis),
                listOf(
                    text(health.warmUp?.minutesTo80, 1), text(health.warmUp?.steady, 0),
                    text(health.start?.parkedMinutes?.let { it / 60 }, 0), text(health.start?.seconds, 1),
                    text(health.start?.restVolts, 1), text(health.boost?.median, 1),
                ),
                Bmw.Text,
            )
        }
        Text(
            "A 80 °C: minutos desde un arranque en frío. Caliente: °C ya en marcha. Parado: horas sin moverse antes de arrancar. Arranque: segundos de parado a ralentí. " +
                "Batería: V antes de arrancar. Turbo: kPa de más o de menos sobre lo pedido. Con raya, ese trayecto no lo pudo medir.",
            color = Bmw.TextDim,
            fontSize = 12.sp,
        )
    }
}

/** La segunda mitad de la salud por trayecto: inyeccion, EGR, ralenti, carga y filtro. */
@Composable
private fun RunningTable(trips: List<TripEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Label("Salud por trayecto: en marcha")
        @Composable
        fun cells(date: String, values: List<String>, color: Color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1.1f), maxLines = 1)
                for (value in values) Text(value, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
        cells("", listOf("Raíl", "EGR", "Ralentí", "Carga", "Filtro", "Regen."), Bmw.TextDim)
        for (trip in trips) {
            val health = trip.health ?: continue
            fun text(value: Double?, decimals: Int) = value?.let { "%.${decimals}f".format(it) } ?: "—"
            cells(
                formatDay(trip.startMillis),
                listOf(
                    text(health.rail?.median, 1), text(health.egr?.median, 1), text(health.idle?.spread, 0),
                    text(health.charge?.volts, 1), text(health.filter?.idlePressure, 0), text(health.filter?.lastInterval, 0),
                ),
                Bmw.Text,
            )
        }
        Text(
            "Raíl: bar de más o de menos sobre lo pedido. EGR: % de más o de menos sobre lo mandado. Ralentí: rpm que baila en caliente. " +
                "Carga: V en marcha. Filtro: hPa a ralentí en caliente. Regen.: km entre las dos últimas regeneraciones.",
            color = Bmw.TextDim,
            fontSize = 12.sp,
        )
    }
}

/** Lo que gasto cada trayecto. Con el signo de aproximado, los litros son una estimacion de la app. */
@Composable
private fun FuelTable(trips: List<TripEntry>, price: Double?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Label("Consumo por trayecto")
        @Composable
        fun cells(date: String, values: List<String>, color: Color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1.1f), maxLines = 1)
                for (value in values) Text(value, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
        cells("", listOf("km", "L", "L/100", "€", "Frío"), Bmw.TextDim)
        for (trip in trips) {
            val fuel = trip.fuel ?: continue
            val mark = if (fuel.real) "" else "≈"
            fun text(value: Double?, decimals: Int) = value?.let { mark + "%.${decimals}f".format(it) } ?: "—"
            cells(
                formatDay(trip.startMillis),
                listOf(
                    "%.0f".format(fuel.km), text(fuel.liters, 1), text(fuel.per100, 1), text(fuel.cost(price), 2),
                    trip.health?.coldPushSeconds?.let { "$it s" } ?: "—",
                ),
                Bmw.Text,
            )
        }
        Text(
            (if (price == null) "Para ver los euros, pon el precio del litro en Ajustes." else "Con el precio del litro que hay ahora en Ajustes.") +
                " Frío: segundos pidiéndole fuerza al motor por debajo de 60 °C; con raya, arrancó ya caliente.",
            color = Bmw.TextDim,
            fontSize = 12.sp,
        )
    }
}

private const val TIMING_ROWS = 12

@Composable
private fun TripRow(trip: TripEntry, vehicleName: String?, onOpen: (TripEntry) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .clickable { onOpen(trip) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatStart(trip.startMillis), color = Bmw.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (trip.simulated) Label("Simulado", color = Bmw.MBlueLight)
        }
        val stats = trip.stats
        val parts = listOfNotNull(
            vehicleName,
            formatDuration(stats.durationMs),
            stats.distanceKm?.let { formatValue(it, 1) + " km" },
            stats.litersPer100Km?.let { formatValue(it, 1) + " L/100" },
        )
        Text(parts.joinToString("  ·  "), color = Bmw.TextDim, fontSize = 13.sp)
    }
}

@Composable
private fun TripDetail(
    store: TripStore,
    vehicle: Vehicle,
    entry: TripEntry,
    fuelPrice: Double?,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val data by produceState<TripData?>(initialValue = null, entry.file) {
        value = withContext(Dispatchers.IO) { store.load(entry.file) }
    }
    var channel by remember { mutableIntStateOf(Pids.RPM) }
    var compare by remember { mutableIntStateOf(NO_COMPARE) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Trayectos") }
            Spacer(Modifier.weight(1f))
            if (entry.simulated) Label("Simulado", color = Bmw.MBlueLight)
        }
        Text(formatStart(entry.startMillis), color = Bmw.Text, fontSize = 24.sp, fontWeight = FontWeight.Light)
        Label(vehicle.name)
        MStripe()

        for (pair in statCells(entry.stats).chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((title, value) in pair) StatCell(title, value, Modifier.weight(1f))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        val trip = data
        if (trip == null) {
            Text("Cargando…", color = Bmw.TextDim)
        } else {
            val reviewed = remember(trip, vehicle) { Advisor.review(trip, vehicle) }
            val health = remember(trip, reviewed) { Health.cards(trip, reviewed) }
            TripSummary(remember(trip, health) { Health.summary(trip, entry.stats, health) })
            Label("Salud del coche en este trayecto", Modifier.padding(top = 6.dp))
            for (card in health) HealthCardView(card)
            Text("Son pistas sacadas de lo grabado, no un diagnóstico.", color = Bmw.TextDim, fontSize = 13.sp)
            val car = remember(trip) { Health.carFacts(trip) }
            if (car.isNotEmpty()) {
                ReportCard(ReportSection("Ficha del coche", car.map { (label, value) -> ReportLine(label, value) }))
            }
            val fuel = remember(trip, vehicle, fuelPrice) { TripFuel.of(trip, vehicle.diesel)?.facts(fuelPrice).orEmpty() }
            if (fuel.isNotEmpty()) {
                ReportCard(ReportSection("Consumo y coste", fuel.map { (label, value) -> ReportLine(label, value) }))
            }
            val regens = remember(trip) { Health.regenerationFacts(trip) }
            if (regens.isNotEmpty()) {
                ReportCard(ReportSection("Regeneraciones del filtro", regens.map { (label, value) -> ReportLine(label, value) }))
            }
            val checks = remember(trip) { TripHealth.of(trip)?.facts().orEmpty() }
            if (checks.isNotEmpty()) {
                ReportCard(ReportSection("Salud del trayecto", checks.map { (label, value) -> ReportLine(label, value) }))
            }
            val wear = remember(trip) { Health.wearFacts(trip) }
            if (wear.isNotEmpty()) {
                ReportCard(ReportSection("Desgaste aprendido por la centralita", wear.map { (label, value) -> ReportLine(label, value) }))
            }
            val timing = remember(trip) { Health.timingFacts(trip) }
            if (timing.isNotEmpty()) {
                ReportCard(ReportSection("Cadena de distribución", timing.map { (label, value) -> ReportLine(label, value) }))
            }

            Label("Gráfica", Modifier.padding(top = 6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (pid in trip.columns) {
                    val def = Pids.byId[pid] ?: continue
                    Chip(def.name, selected = pid == channel) { channel = pid }
                }
            }
            Label("Comparar con")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("Nada", selected = compare == NO_COMPARE) { compare = NO_COMPARE }
                for (pid in trip.columns) {
                    val def = Pids.byId[pid] ?: continue
                    if (pid != channel) Chip(def.name, selected = pid == compare) { compare = pid }
                }
            }
            val main = remember(trip, channel) { chartSeries(trip, channel) }
            val second = remember(trip, compare, channel) { if (compare == channel) null else chartSeries(trip, compare) }
            if (main != null) TripChart(trip.tMs, main, second)

            val findings = reviewed
            Label("Indicios del trayecto", Modifier.padding(top = 6.dp))
            if (findings.isEmpty()) {
                Text("Ninguna regla ha saltado. No significa que el coche esté revisado.", color = Bmw.TextDim)
            } else {
                Text("Son pistas para investigar, no un diagnóstico.", color = Bmw.TextDim, fontSize = 13.sp)
                for (finding in findings) {
                    AdviceCard(finding.advice, note = "Durante " + formatDuration(finding.seconds * 1000))
                }
            }

            val report = remember(trip, vehicle) { TripReport.build(trip, vehicle, entry.stats, findings) }
            Label("Informe", Modifier.padding(top = 6.dp))
            // Los indicios ya se ven arriba como tarjetas; en el informe compartido van tambien.
            for (section in report.dropLast(1)) ReportCard(section)
            OutlinedButton(
                onClick = {
                    val title = formatStart(entry.startMillis)
                    shareText(context, "Informe $title", TripReport.text(title, vehicle, report))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Compartir informe") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { shareFile(context, entry.file, "text/csv", exportName(entry)) }, modifier = Modifier.weight(1f)) {
                Text("Exportar CSV")
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                Text("Borrar", color = Bmw.MRed)
            }
        }
    }
}

@Composable
private fun ReportCard(section: ReportSection) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(section.title, color = Bmw.Accent)
        for (line in section.lines) {
            Column {
                Text(line.label, color = Bmw.TextDim, fontSize = 12.sp)
                Text(line.value, color = Bmw.Text, fontSize = 15.sp)
            }
        }
        section.note?.let { Text(it, color = Bmw.TextDim, fontSize = 12.sp) }
    }
}

@Composable
private fun StatCell(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Label(title)
        Text(value, color = Bmw.Text, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Bmw.Background else Bmw.Text,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Bmw.Accent else Bmw.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private const val NO_COMPARE = -1

private fun chartSeries(trip: TripData, id: Int): ChartSeries? {
    val def = Pids.byId[id] ?: return null
    val values = trip.series[id] ?: return null
    return ChartSeries(def.name, def.unit, def.decimals, values)
}

private fun statCells(stats: TripStats): List<Pair<String, String>> = listOfNotNull(
    "Duración" to formatDuration(stats.durationMs),
    stats.distanceKm?.let { "Distancia" to formatValue(it, 1) + " km" },
    stats.avgSpeed?.let { "Velocidad media" to formatValue(it, 0) + " km/h" },
    stats.maxSpeed?.let { "Velocidad máx" to formatValue(it, 0) + " km/h" },
    stats.litersPer100Km?.let {
        (if (stats.fuelEstimated) "Consumo estimado" else "Consumo medio") to formatValue(it, 1) + " L/100"
    },
    stats.fuelLiters?.let { "Gasóleo" to formatValue(it, 2) + " L" },
    stats.maxRpm?.let { "RPM máx" to formatValue(it, 0) },
    stats.maxBoostBar?.let { "Turbo máx" to formatValue(it, 2) + " bar" },
    stats.maxCoolant?.let { "Refrigerante máx" to formatValue(it, 0) + " °C" },
    stats.maxOil?.let { "Aceite máx" to formatValue(it, 0) + " °C" },
)

/** Nombre con el que sale el CSV al compartirlo: con la fecha y sin el bastidor del coche. */
private fun exportName(entry: TripEntry): String =
    "trayecto_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(entry.startMillis)) + ".csv"

private fun formatDay(millis: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))

private fun formatStart(millis: Long): String =
    SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(millis))
