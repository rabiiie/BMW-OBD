package com.rabie.bmwobd.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.LiveState
import com.rabie.bmwobd.advice.Severity
import com.rabie.bmwobd.dash.DashGroup
import com.rabie.bmwobd.dash.DashTile
import com.rabie.bmwobd.dash.Dashboard
import com.rabie.bmwobd.obd.Pids
import kotlinx.coroutines.delay

private const val ROTATE_MS = 8_000L
private const val TOUCH_HOLD_MS = 20_000L
private const val CLUSTER_MAX_SHARE = 0.56f
private val STRIP_HEIGHT = 38.dp
private val INFO_HEIGHT = 24.dp
private val FULL_CLUSTER = 560.dp

/** El grupo que sigue a [index] sin repetir el de la otra columna ni el que esta fijo. */
private fun nextGroup(index: Int, other: Int, pinned: Int?): Int {
    val count = DashGroup.entries.size
    var next = index
    repeat(count) {
        next = (next + 1) % count
        if (next != other && next != pinned) return next
    }
    return index
}

/**
 * El panel apaisado: arriba una franja con lo mas importante, en el centro el cuadro, y a los
 * lados dos grupos de datos que van rotando. Tocar una columna pasa al grupo siguiente. Con un
 * aviso, la columna izquierda se queda fija en el grupo al que toca.
 */
@Composable
internal fun DashPanel(state: LiveState, onMenu: () -> Unit, maxWidth: Dp, maxHeight: Dp) {
    val values = state.values
    val vehicle = state.vehicle
    val groups = DashGroup.entries
    val top = state.advice.firstOrNull { it.severity != Severity.INFO } ?: state.advice.firstOrNull()
    val pinned = top?.let(Dashboard::group)
    val pinnedIndex by rememberUpdatedState(pinned?.ordinal)

    var left by rememberSaveable { mutableIntStateOf(0) }
    var right by rememberSaveable { mutableIntStateOf(1) }
    var leftHold by remember { mutableLongStateOf(0L) }
    var rightHold by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ROTATE_MS)
            val now = SystemClock.elapsedRealtime()
            if (now >= leftHold) left = nextGroup(left, right, pinnedIndex)
            if (now >= rightHold) right = nextGroup(right, left, pinnedIndex)
        }
    }
    val leftGroup = pinned ?: groups[left]
    val rightGroup = groups[right].takeIf { it != leftGroup } ?: groups[nextGroup(right, leftGroup.ordinal, pinned?.ordinal)]
    val alertColor = top?.let { severityColor(it.severity) } ?: Bmw.Accent

    val clusterWidth = minOf((maxHeight - STRIP_HEIGHT - INFO_HEIGHT - 20.dp) * CLUSTER_ASPECT, maxWidth * CLUSTER_MAX_SHARE)
    val scale = clusterWidth / FULL_CLUSTER
    val rpm = values[Pids.RPM]
    val speed = values[Pids.SPEED]

    Column(
        Modifier.fillMaxSize().background(Bmw.Background).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Strip(state, onMenu)
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            GroupColumn(
                group = leftGroup,
                tiles = Dashboard.tiles(leftGroup, values),
                pinnedColor = alertColor.takeIf { pinned != null },
                modifier = Modifier.weight(1f),
                onClick = {
                    if (pinned == null) {
                        left = nextGroup(left, right, null)
                        leftHold = SystemClock.elapsedRealtime() + TOUCH_HOLD_MS
                    }
                },
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        values[Dashboard.GEAR]?.let { gear ->
                            Text(if (gear < 1) "N" else formatValue(gear, 0) + "ª", color = Bmw.Text, fontSize = (22 * scale).sp)
                        }
                        CenterReadout(state, (28 * scale).sp)
                    },
                    right = {
                        Text(rpm?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = (54 * scale).sp, fontWeight = FontWeight.Light)
                        Label("rpm")
                    },
                )
                Info(state, Modifier.height(INFO_HEIGHT))
            }
            GroupColumn(
                group = rightGroup,
                tiles = Dashboard.tiles(rightGroup, values),
                pinnedColor = null,
                modifier = Modifier.weight(1f),
                onClick = {
                    right = nextGroup(right, leftGroup.ordinal, pinned?.ordinal)
                    rightHold = SystemClock.elapsedRealtime() + TOUCH_HOLD_MS
                },
            )
        }
    }
}

/** La franja de arriba: lo mas importante que pasa ahora y que hacer, o que todo va normal. */
@Composable
private fun Strip(state: LiveState, onMenu: () -> Unit) {
    val top = state.advice.firstOrNull { it.severity != Severity.INFO } ?: state.advice.firstOrNull()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val ink = if (top == null) Bmw.Text else Bmw.Background
    val soft = if (top == null) Bmw.TextDim else Bmw.Background
    Row(
        Modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(top?.let { severityColor(it.severity) } ?: Bmw.Surface)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Roundel(Modifier.size(24.dp))
        Text(top?.title ?: "Todo normal", color = ink, fontSize = 16.sp, maxLines = 1)
        Text(
            top?.detail ?: "",
            color = soft,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("REC " + formatDuration((now - state.liveSinceMillis).coerceAtLeast(0)), color = soft, fontSize = 12.sp, maxLines = 1)
        Text("Menú", color = if (top == null) Bmw.Accent else Bmw.Background, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onMenu).padding(6.dp))
    }
}

/** Bajo el cuadro: el agua, el deposito y los kilometros que quedan, que son una cuenta. */
@Composable
private fun Info(state: LiveState, modifier: Modifier = Modifier) {
    val values = state.values
    val parts = listOfNotNull(
        values[Pids.COOLANT]?.let { "Agua " + formatValue(it, 0) + " °C" },
        values[Pids.bmw(Pids.BMW_TANK_ADDRESS)]?.let { "Depósito " + formatValue(it, 0) + " L" },
        Dashboard.rangeKm(values, state.averageL100)?.let { "≈ " + formatValue(it, 0) + " km" },
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(parts.joinToString("  ·  "), color = Bmw.TextDim, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun GroupColumn(group: DashGroup, tiles: List<DashTile>, pinnedColor: Color?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.title, color = pinnedColor ?: Bmw.Accent, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (pinnedColor != null) {
                Text("fijo", color = pinnedColor, fontSize = 11.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (other in DashGroup.entries) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (other == group) Bmw.Accent else Bmw.Line))
                    }
                }
            }
        }
        for (tile in tiles) {
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Bmw.Surface)
                    .border(1.dp, pinnedColor ?: Bmw.Surface, shape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Label(tile.title)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(tile.value, color = Bmw.Text, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1)
                    Text(tile.unit, color = Bmw.TextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
    }
}

/** El emblema, dibujado: un aro oscuro y el circulo partido en cuartos azules y blancos. */
@Composable
private fun Roundel(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension / 2
        val inner = radius * 0.6f
        val corner = Offset(center.x - inner, center.y - inner)
        val box = Size(inner * 2, inner * 2)
        drawCircle(Color(0xFF0B0B0B), radius)
        drawCircle(Color(0xFFB4B2A9), radius - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
        drawCircle(Color.White, inner)
        drawArc(Color(0xFF1C69D4), 180f, 90f, useCenter = true, topLeft = corner, size = box)
        drawArc(Color(0xFF1C69D4), 0f, 90f, useCenter = true, topLeft = corner, size = box)
    }
}
