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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.advice.Advisor
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.report.ReportSection
import com.rabie.bmwobd.report.TripReport
import com.rabie.bmwobd.trips.TripData
import com.rabie.bmwobd.trips.TripEntry
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
fun TripsScreen(store: TripStore, vehicles: VehicleStore, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<TripEntry?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val trips by produceState<List<TripEntry>?>(initialValue = null, version) {
        value = withContext(Dispatchers.IO) { store.list() }
    }

    val current = selected
    if (current == null) {
        TripList(trips, vehicles, onOpen = { selected = it }, modifier = modifier)
    } else {
        BackHandler { selected = null }
        TripDetail(
            store = store,
            vehicle = current.vehicleKey?.let(vehicles::byKey) ?: Vehicle.GENERIC,
            entry = current,
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
                items(trips, key = { it.file.name }) { trip ->
                    val name = remember(trip.vehicleKey) { trip.vehicleKey?.let(vehicles::byKey)?.name }
                    TripRow(trip, name, onOpen)
                }
            }
        }
    }
}

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
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val data by produceState<TripData?>(initialValue = null, entry.file) {
        value = withContext(Dispatchers.IO) { store.load(entry.file) }
    }
    var channel by remember { mutableIntStateOf(Pids.RPM) }

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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (pid in trip.columns) {
                    val def = Pids.byId[pid] ?: continue
                    Chip(def.name, selected = pid == channel) { channel = pid }
                }
            }
            val series = trip.series[channel]
            val def = Pids.byId[channel]
            if (series != null && def != null) TripChart(trip.tMs, series, def.unit, def.decimals)

            val findings = remember(trip, vehicle) { Advisor.review(trip, vehicle) }
            Label("Indicios del trayecto", Modifier.padding(top = 6.dp))
            if (findings.isEmpty()) {
                Text("Nada fuera de lo normal.", color = Bmw.TextDim)
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
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
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

/** La medida elegida a lo largo del trayecto, con su minimo y su maximo. */
@Composable
private fun TripChart(tMs: LongArray, values: DoubleArray, unit: String, decimals: Int) {
    val valid = values.filter { !it.isNaN() }
    if (valid.isEmpty() || tMs.isEmpty()) {
        Text("Sin datos de esta medida.", color = Bmw.TextDim)
        return
    }
    val min = valid.min()
    val max = valid.max()
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    val total = tMs.last().coerceAtLeast(1L).toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label("Máx ${formatValue(max, decimals)} $unit")
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val step = (tMs.size / size.width.toInt().coerceAtLeast(1)).coerceAtLeast(1)
            val path = Path()
            var started = false
            var i = 0
            while (i < tMs.size) {
                val v = values[i]
                if (!v.isNaN()) {
                    val x = tMs[i] / total * size.width
                    val y = size.height - ((v - min) / span).toFloat() * size.height
                    if (started) path.lineTo(x, y) else path.moveTo(x, y)
                    started = true
                }
                i += step
            }
            drawLine(Bmw.Line, Offset(0f, size.height), Offset(size.width, size.height))
            drawPath(path, Bmw.Accent, style = Stroke(width = 2.dp.toPx()))
        }
        Row {
            Label("Mín ${formatValue(min, decimals)} $unit", Modifier.weight(1f))
            Label(formatDuration(tMs.last()))
        }
    }
}

private fun statCells(stats: TripStats): List<Pair<String, String>> = listOfNotNull(
    "Duración" to formatDuration(stats.durationMs),
    stats.distanceKm?.let { "Distancia" to formatValue(it, 1) + " km" },
    stats.avgSpeed?.let { "Velocidad media" to formatValue(it, 0) + " km/h" },
    stats.maxSpeed?.let { "Velocidad máx" to formatValue(it, 0) + " km/h" },
    stats.litersPer100Km?.let { "Consumo medio" to formatValue(it, 1) + " L/100" },
    stats.fuelLiters?.let { "Gasóleo" to formatValue(it, 2) + " L" },
    stats.maxRpm?.let { "RPM máx" to formatValue(it, 0) },
    stats.maxBoostBar?.let { "Turbo máx" to formatValue(it, 2) + " bar" },
    stats.maxCoolant?.let { "Refrigerante máx" to formatValue(it, 0) + " °C" },
    stats.maxOil?.let { "Aceite máx" to formatValue(it, 0) + " °C" },
)

/** Nombre con el que sale el CSV al compartirlo: con la fecha y sin el bastidor del coche. */
private fun exportName(entry: TripEntry): String =
    "trayecto_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(entry.startMillis)) + ".csv"

private fun formatStart(millis: Long): String =
    SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(millis))
