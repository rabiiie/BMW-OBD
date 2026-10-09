package com.rabie.bmwobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rabie.bmwobd.advice.Severity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rabie.bmwobd.vehicle.Vehicle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.LiveState
import com.rabie.bmwobd.obd.DefaultRanges
import com.rabie.bmwobd.obd.PidGroup
import com.rabie.bmwobd.obd.Pids
import com.rabie.bmwobd.obd.Status
import kotlinx.coroutines.delay

private data class Reading(val title: String, val value: String, val unit: String, val status: Status?)

private val BOOST_LABELS = listOf("0", "0.5", "1", "1.5", "2")
private const val BOOST_MAX_BAR = 2.0
private val SPEED_LABELS = listOf("0", "40", "80", "120", "160", "200", "240")
private const val SPEED_MAX = 240.0
private val COOLANT_LABELS = listOf("40", "70", "100", "130")
private const val COOLANT_MIN = 40.0
private const val COOLANT_MAX = 130.0
private const val COOLANT_RED_FROM = 0.75f

// Lo que ya se ve en un reloj no se repite en las tarjetas.
private val ON_GAUGES = setOf(Pids.RPM, Pids.SPEED, Pids.COOLANT, Pids.MAP, Pids.BAROMETRIC)

@Composable
fun LiveScreen(
    state: LiveState,
    keepScreenOn: Boolean,
    bubbleEnabled: Boolean,
    onToggleBubble: () -> Unit,
    onSaveVehicle: (Vehicle) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (keepScreenOn) KeepScreenOn()
    val values = state.values
    val vehicle = state.vehicle
    val context = LocalContext.current
    var editingVehicle by remember { mutableStateOf(false) }
    if (editingVehicle) {
        VehicleDialog(
            vehicle = vehicle,
            onSave = {
                onSaveVehicle(it)
                editingVehicle = false
            },
            onDismiss = { editingVehicle = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusRow(state, onDisconnect)
        Row(
            modifier = Modifier.fillMaxWidth().clickable { editingVehicle = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(vehicle.name, color = Bmw.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Label(if (vehicle.displacementLiters == null) "Completar perfil" else "Editar", color = Bmw.Accent)
        }
        for (advice in state.advice) {
            AdviceCard(advice, Modifier.clickable { searchWeb(context, "${vehicle.searchTerms} ${advice.title}") })
        }

        val rpm = values[Pids.RPM]
        val speed = values[Pids.SPEED]
        MCluster(
            speedFraction = ((speed ?: 0.0) / SPEED_MAX).toFloat(),
            rpmFraction = ((rpm ?: 0.0) / vehicle.rpmMax).toFloat(),
            speedLabels = SPEED_LABELS,
            rpmLabels = vehicle.rpmLabels,
            rpmRedFrom = vehicle.rpmRedFrom,
            modifier = Modifier.fillMaxWidth().aspectRatio(CLUSTER_ASPECT),
            left = {
                Text(speed?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = 44.sp, fontWeight = FontWeight.Light)
                Label("km/h")
            },
            center = {
                CenterReadout(state, 22.sp)
            },
            right = {
                Text(rpm?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = 34.sp, fontWeight = FontWeight.Light)
                Label("rpm")
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val boost = Pids.boostBar(values)
            SmallGauge(
                title = "Turbo",
                value = boost?.let { formatValue(it, 2) },
                unit = "bar",
                fraction = ((boost ?: 0.0) / BOOST_MAX_BAR).toFloat(),
                labels = BOOST_LABELS,
                modifier = Modifier.weight(1f),
            )
            val coolant = values[Pids.COOLANT]
            SmallGauge(
                title = "Refrigerante",
                value = coolant?.let { formatValue(it, 0) },
                unit = "°C",
                fraction = (((coolant ?: COOLANT_MIN) - COOLANT_MIN) / (COOLANT_MAX - COOLANT_MIN)).toFloat(),
                labels = COOLANT_LABELS,
                redFrom = COOLANT_RED_FROM,
                modifier = Modifier.weight(1f),
            )
        }

        for ((group, readings) in tiles(state)) {
            Label(group.title, Modifier.padding(top = 6.dp))
            for (pair in readings.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (reading in pair) {
                        Tile(
                            reading,
                            Modifier.weight(1f).clickable {
                                val query = "${vehicle.searchTerms} ${reading.title} ${reading.value} ${reading.unit} valor normal"
                                searchWeb(context, query)
                            },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text("Toca una lectura para buscar en internet su valor normal en este coche.", color = Bmw.TextDim, fontSize = 12.sp)

        OutlinedButton(onClick = { openWaze(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir Waze")
        }
        OutlinedButton(onClick = onToggleBubble, modifier = Modifier.fillMaxWidth()) {
            Text(if (bubbleEnabled) "Burbuja sobre el navegador: activada" else "Burbuja sobre el navegador: desactivada")
        }
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.simulated) "Salir de la simulación" else "Desconectar y cerrar trayecto")
        }
    }
}

/**
 * Panel con el movil apaisado en el soporte: el cuadro a toda la altura, cuatro lecturas a los
 * lados y, arriba, el indicio mas grave. Sin barras del sistema ni pestañas; [onMenu] vuelve a
 * la app normal.
 */
@Composable
fun LandscapePanel(state: LiveState, keepScreenOn: Boolean, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    if (keepScreenOn) KeepScreenOn()
    HideSystemBars()
    val values = state.values
    val vehicle = state.vehicle
    val rpm = values[Pids.RPM]
    val speed = values[Pids.SPEED]

    val sides = sideReadouts(state)

    BoxWithConstraints(modifier.fillMaxSize().background(Bmw.Background).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Con sitio a lo ancho, las lecturas van a los lados. En una ventana mas cuadrada (pantalla
        // partida con el navegador arriba) van debajo y el cuadro se encoge para caber.
        val wide = maxWidth >= maxHeight * WIDE_RATIO
        val clusterWidth = if (wide) {
            maxHeight * CLUSTER_ASPECT
        } else {
            minOf(maxWidth, (maxHeight - READOUTS_HEIGHT).coerceAtLeast(MIN_CLUSTER_HEIGHT) * CLUSTER_ASPECT)
        }
        val scale = clusterWidth / FULL_CLUSTER_WIDTH
        val cluster: @Composable () -> Unit = {
            MCluster(
                speedFraction = ((speed ?: 0.0) / SPEED_MAX).toFloat(),
                rpmFraction = ((rpm ?: 0.0) / vehicle.rpmMax).toFloat(),
                speedLabels = SPEED_LABELS,
                rpmLabels = vehicle.rpmLabels,
                rpmRedFrom = vehicle.rpmRedFrom,
                modifier = Modifier.width(clusterWidth).aspectRatio(CLUSTER_ASPECT),
                left = {
                    Text(speed?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = (72 * scale).sp, fontWeight = FontWeight.Light)
                    Label("km/h")
                },
                center = {
                    CenterReadout(state, (34 * scale).sp)
                },
                right = {
                    Text(rpm?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = (54 * scale).sp, fontWeight = FontWeight.Light)
                    Label("rpm")
                },
            )
        }

        if (wide) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // Las lecturas se reparten alternando: la primera a la izquierda, la segunda a la derecha.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (side in sides.filterIndexed { index, _ -> index % 2 == 0 }) Readout(side)
                }
                cluster()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (side in sides.filterIndexed { index, _ -> index % 2 == 1 }) Readout(side)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                cluster()
                Row(Modifier.fillMaxWidth()) {
                    for (side in sides.take(NARROW_READOUTS)) Readout(side, Modifier.weight(1f))
                }
            }
        }

        val top = state.advice.firstOrNull { it.severity != Severity.INFO } ?: state.advice.firstOrNull()
        if (top != null) {
            Text(
                top.title,
                color = Bmw.Background,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(50))
                    .background(severityColor(top.severity))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1000)
            }
        }
        Label(
            "REC " + formatDuration((now - state.liveSinceMillis).coerceAtLeast(0)),
            Modifier.align(Alignment.BottomStart).padding(10.dp),
        )
        Label("Menú", Modifier.align(Alignment.BottomEnd).clickable(onClick = onMenu).padding(10.dp), color = Bmw.Accent)
    }
}

/**
 * La cifra del hueco central del cuadro: el consumo instantaneo, medido o estimado, y si el
 * coche no da con que calcularlo, el turbo. Parado el consumo sale en litros por hora.
 */
@Composable
private fun CenterReadout(state: LiveState, size: TextUnit) {
    if (Pids.hasFuelRate(state.supported)) {
        val diesel = state.vehicle.diesel
        val perHour = Pids.fuelRate(state.values, diesel)
        val per100 = Pids.litersPer100Km(state.values, diesel)
        Text((per100 ?: perHour)?.let { formatValue(it, 1) } ?: "—", color = Bmw.Accent, fontSize = size)
        Label(if (per100 == null && perHour != null) "L/h" else "L/100")
    } else {
        val boost = Pids.boostBar(state.values)
        Text(boost?.let { formatValue(it, 2) } ?: "—", color = Bmw.Accent, fontSize = size)
        Label("bar")
    }
}

/**
 * Las lecturas que acompañan al cuadro apaisado, por orden de interes y solo las que da este
 * coche. El turbo no se repite si ya ocupa el hueco central.
 */
private fun sideReadouts(state: LiveState): List<Side> {
    val values = state.values
    val supported = state.supported
    val voltageId = if (values[MODULE_VOLTAGE] != null) MODULE_VOLTAGE else Pids.ADAPTER_VOLTAGE
    val exhaustIds = EXHAUST_IDS.filter { it in supported }
    val railId = RAIL_IDS.firstOrNull { it in supported }
    return listOfNotNull(
        Side("Turbo", Pids.boostBar(values), 2, "bar", null).takeIf { Pids.hasFuelRate(supported) },
        Side("Agua", values[Pids.COOLANT], 0, "°C", Pids.COOLANT),
        Side("Aceite", values[Pids.OIL], 0, "°C", Pids.OIL).takeIf { Pids.OIL in supported },
        Side("Escape", exhaustIds.mapNotNull { values[it] }.maxOrNull(), 0, "°C", null).takeIf { exhaustIds.isNotEmpty() },
        railId?.let { Side("Raíl", values[it], 0, "bar", null) },
        Side("Carga", values[Pids.LOAD], 0, "%", null),
        Side("Aire", values[AIR_FLOW], 1, "g/s", null).takeIf { AIR_FLOW in supported },
        Side("Tensión", values[voltageId], 1, "V", voltageId),
        Side("Admisión", values[INTAKE_TEMP], 0, "°C", INTAKE_TEMP).takeIf { INTAKE_TEMP in supported },
    ).take(WIDE_READOUTS)
}

private const val WIDE_READOUTS = 6
private const val NARROW_READOUTS = 4
private const val AIR_FLOW = 0x10
private val RAIL_IDS = listOf(Pids.part(0x6D, 1), 0x23)
private val EXHAUST_IDS = (0..3).map { Pids.part(0x78, it) } + 0x3C

/** Una lectura secundaria del panel apaisado; [rangeId] es la medida cuyo semaforo le da color. */
private class Side(val title: String, val value: Double?, val decimals: Int, val unit: String, val rangeId: Int?)

private const val WIDE_RATIO = 1.9f
private val FULL_CLUSTER_WIDTH = 560.dp
private val READOUTS_HEIGHT = 90.dp
private val MIN_CLUSTER_HEIGHT = 110.dp

@Composable
private fun Readout(side: Side, modifier: Modifier = Modifier.fillMaxWidth()) {
    val value = side.value
    val status = if (value != null && side.rangeId != null) DefaultRanges.byPid[side.rangeId]?.evaluate(value) else null
    val color = when (status) {
        Status.WARN, Status.ALERT -> statusColor(status)
        else -> Bmw.Text
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(side.title)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value?.let { formatValue(it, side.decimals) } ?: "—", color = color, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Text(side.unit, color = Bmw.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

/** Oculta la barra de estado y la de navegacion mientras este en pantalla; deslizar las enseña un momento. */
@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private const val INTAKE_TEMP = 0x0F
private const val MODULE_VOLTAGE = 0x42

@Composable
private fun StatusRow(state: LiveState, onDisconnect: () -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Bmw.MRed))
        Label("REC " + formatDuration((now - state.liveSinceMillis).coerceAtLeast(0)), color = Bmw.Text)
        if (state.simulated) Label("Simulación", color = Bmw.MBlueLight)
        Spacer(Modifier.weight(1f))
        Label("%.1f lecturas/s".format(state.cyclesPerSecond))
        Label(
            if (state.simulated) "Salir" else "Desconectar",
            Modifier.clickable(onClick = onDisconnect).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            color = Bmw.Accent,
        )
    }
    Text(
        state.adapterInfo,
        color = Bmw.TextDim,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SmallGauge(
    title: String,
    value: String?,
    unit: String,
    fraction: Float,
    labels: List<String>,
    modifier: Modifier = Modifier,
    redFrom: Float = 1f,
) {
    ArcGauge(fraction = fraction, labels = labels, redFrom = redFrom, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value ?: "—", color = Bmw.Text, fontSize = 28.sp, fontWeight = FontWeight.Light)
            Text(unit, color = Bmw.TextDim, fontSize = 12.sp)
        }
        Label(title, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Tile(reading: Reading, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(statusColor(reading.status)))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Label(reading.title)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(reading.value, color = Bmw.Text, fontSize = 26.sp, fontWeight = FontWeight.Light)
                Text(reading.unit, color = Bmw.TextDim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Las tarjetas por seccion. Una medida que el coche anuncia pero todavia no ha dado valor sale
 * con una raya, salvo las de los PIDs de varias medidas: ahi solo salen los sensores que existen.
 */
private fun tiles(state: LiveState): List<Pair<PidGroup, List<Reading>>> {
    val byGroup = LinkedHashMap<PidGroup, MutableList<Reading>>()
    val estimated = Pids.fuelRateIsEstimated(state.supported)
    val diesel = state.vehicle.diesel
    Pids.litersPer100Km(state.values, diesel)?.let {
        byGroup.getOrPut(PidGroup.FUEL) { mutableListOf() }
            .add(Reading(if (estimated) "Consumo instantáneo (estimado)" else "Consumo instantáneo", formatValue(it, 1), "L/100", null))
    }
    if (estimated) {
        Pids.fuelRate(state.values, diesel)?.let {
            byGroup.getOrPut(PidGroup.FUEL) { mutableListOf() }
                .add(Reading("Consumo (estimado)", formatValue(it, 1), "L/h", null))
        }
    }
    for (def in Pids.all.distinctBy { it.id }) {
        if (def.id !in state.supported || def.id in ON_GAUGES) continue
        val value = state.values[def.id]
        if (value == null && def.pid != def.id) continue
        val reading = Reading(
            title = def.name,
            value = value?.let { formatValue(it, def.decimals) } ?: "—",
            unit = def.unit,
            status = value?.let { v -> DefaultRanges.byPid[def.id]?.evaluate(v) },
        )
        byGroup.getOrPut(def.group) { mutableListOf() }.add(reading)
    }
    return PidGroup.entries.mapNotNull { group -> byGroup[group]?.let { group to it } }
}
