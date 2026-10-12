package com.rabie.bmwobd.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
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
import androidx.compose.runtime.DisposableEffect
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
import com.rabie.bmwobd.dash.ShiftHint
import com.rabie.bmwobd.obd.Pids
import kotlinx.coroutines.delay

private const val ROTATE_MS = 8_000L
private const val TOUCH_HOLD_MS = 20_000L
private const val CENTER_SHARE = 0.27f
private const val ARC_HALF_ANGLE = 48f
private const val TANK_LITERS = 53.0
private const val FUEL_LEVEL = 0x2F
private const val OUTSIDE_TEMP = 0x46
private val CENTER_MIN = 180.dp
private val CENTER_MAX = 260.dp
private val ARC_WIDTH = 118.dp
private val ARC_STROKE = 5.dp
private val ARC_PAD = 14.dp
private val ARC_INSET = 4.dp
private val ARC_LABEL_GAP = 6.dp
private val ARC_CONTENT_SHIFT = 26.dp
private val SWIPE_DISTANCE = 28.dp
private val STRIP_HEIGHT = 38.dp
private val INFO_HEIGHT = 24.dp

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
 * El panel apaisado: arriba una franja con lo mas importante; en medio, la velocidad y las
 * revoluciones en dos arcos finos con unas tarjetas entre ellos; a los lados, dos grupos de datos
 * que van rotando. Tocar una columna pasa al grupo siguiente. Con un aviso, la columna izquierda
 * se queda fija en el grupo al que toca.
 */
@Composable
internal fun DashPanel(state: LiveState, onMenu: () -> Unit, onWatch: (List<Int>) -> Unit, maxWidth: Dp, maxHeight: Dp) {
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

    // Lo que esta en pantalla se lee mas seguido; al salir del panel, deja de pedirse asi. La
    // marcha y el control de crucero van siempre.
    LaunchedEffect(leftGroup, rightGroup) {
        onWatch(Dashboard.alwaysWatched + Dashboard.watched(leftGroup) + Dashboard.watched(rightGroup))
    }
    DisposableEffect(Unit) { onDispose { onWatch(emptyList()) } }

    val rpm = values[Pids.RPM]
    val speed = values[Pids.SPEED]
    val centerWidth = (maxWidth * CENTER_SHARE).coerceIn(CENTER_MIN, CENTER_MAX)

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
            SideArc(
                fraction = ((speed ?: 0.0) / SPEED_MAX).toFloat(),
                mirrored = false,
                redFrom = 1f,
                labels = listOf("0", "120", "240"),
                modifier = Modifier.width(ARC_WIDTH).fillMaxHeight(),
            ) {
                Text(speed?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = 44.sp, fontWeight = FontWeight.Light, maxLines = 1)
                Label("km/h")
                Cruise(values)
            }
            Column(
                Modifier.width(centerWidth).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Sin marcha conocida el hueco se queda, para que la tarjeta no salte de sitio.
                    val gear = state.gear
                    Text(
                        when {
                            gear == null -> " "
                            gear < 1 -> "N"
                            else -> Dashboard.CALCULATED + "${gear}ª"
                        },
                        color = Bmw.Text,
                        fontSize = 22.sp,
                    )
                    // La recomendacion de cambio, tambien calculada: flecha arriba para subir, abajo para bajar.
                    when (state.shift) {
                        ShiftHint.UP -> Text("▲", color = Bmw.Accent, fontSize = 22.sp)
                        ShiftHint.DOWN -> Text("▼", color = Bmw.Amber, fontSize = 22.sp)
                    }
                }
                CenterCards(state)
            }
            SideArc(
                fraction = ((rpm ?: 0.0) / vehicle.rpmMax).toFloat(),
                mirrored = true,
                redFrom = vehicle.rpmRedFrom,
                labels = listOf("0", formatValue(vehicle.rpmMax / 2000, 0), formatValue(vehicle.rpmMax / 1000, 0)),
                modifier = Modifier.width(ARC_WIDTH).fillMaxHeight(),
            ) {
                Text(rpm?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = 34.sp, fontWeight = FontWeight.Light, maxLines = 1)
                Label("rpm")
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
        Bottom(state, Modifier.height(INFO_HEIGHT))
    }
}

/**
 * Un reloj reducido a un arco fino en un lado, con su cifra dentro. El de la izquierda se abre
 * hacia la derecha y el de la derecha al reves; los dos se llenan de abajo arriba.
 */
@Composable
private fun SideArc(
    fraction: Float,
    mirrored: Boolean,
    redFrom: Float,
    labels: List<String>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(350, easing = LinearEasing), label = "arco")
    val measurer = rememberTextMeasurer()
    val track = Bmw.Line
    val fill = Bmw.Accent
    val red = Bmw.MRed
    val labelStyle = TextStyle(color = Bmw.TextDim, fontSize = 11.sp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = ARC_STROKE.toPx()
            val pad = ARC_PAD.toPx()
            val half = ARC_HALF_ANGLE
            val radius = (size.height - 2 * pad) / (2 * sin(Math.toRadians(half.toDouble())).toFloat())
            val edge = stroke / 2 + ARC_INSET.toPx()
            val cx = if (mirrored) size.width - edge - radius else edge + radius
            val box = Size(radius * 2, radius * 2)
            val corner = Offset(cx - radius, size.height / 2 - radius)
            // Los angulos van en el sentido de las agujas desde las tres: abajo es 90.
            val start = if (mirrored) half else 180f - half
            val turn = if (mirrored) -1f else 1f
            val whole = 2 * half
            val line = Stroke(stroke, cap = StrokeCap.Round)
            drawArc(track, start, turn * whole, useCenter = false, topLeft = corner, size = box, style = line)
            if (redFrom < 1f) {
                drawArc(red, start + turn * whole * redFrom, turn * whole * (1 - redFrom), useCenter = false, topLeft = corner, size = box, style = line)
            }
            if (shown > 0.005f) drawArc(fill, start, turn * whole * shown, useCenter = false, topLeft = corner, size = box, style = line)

            // Tres rotulos: abajo, en medio y arriba, por dentro del arco.
            labels.forEachIndexed { index, text ->
                val angle = Math.toRadians((start + turn * whole * index / (labels.size - 1)).toDouble())
                val inner = radius - stroke - ARC_LABEL_GAP.toPx()
                val layout = measurer.measure(text, labelStyle)
                val x = cx + inner * cos(angle).toFloat() - if (mirrored) layout.size.width.toFloat() else 0f
                val y = size.height / 2 + inner * sin(angle).toFloat() - layout.size.height / 2f
                drawText(layout, topLeft = Offset(x, y.coerceIn(0f, size.height - layout.size.height)))
            }
        }
        Column(
            Modifier.padding(start = if (mirrored) 0.dp else ARC_CONTENT_SHIFT, end = if (mirrored) ARC_CONTENT_SHIFT else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** El control de crucero junto a la velocidad: un reloj pequeño y la velocidad fijada. Apagado no sale. */
@Composable
private fun Cruise(values: Map<Int, Double>) {
    val state = values[Dashboard.CRUISE_STATE]?.toInt() ?: return
    if (state != Dashboard.CRUISE_DRIVING && state != Dashboard.CRUISE_WAITING) return
    val color = if (state == Dashboard.CRUISE_DRIVING) Bmw.MBlueLight else Bmw.TextDim
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(14.dp)) {
            val line = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(color, 150f, 240f, useCenter = false, style = line)
            drawLine(color, center, Offset(center.x + size.width * 0.28f, center.y - size.height * 0.28f), line.width, StrokeCap.Round)
        }
        values[Dashboard.CRUISE_SPEED]?.takeIf { it > 0 }?.let { Text(formatValue(it, 0), color = color, fontSize = 14.sp) }
    }
}

/**
 * Las tarjetas del centro: trayecto, temperaturas y motor. Se pasan deslizando a un lado o a
 * otro, o tocando; no rotan solas. Cuatro numeros con su unidad debajo.
 */
@Composable
private fun CenterCards(state: LiveState) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val cards = Dashboard.cards(
        state.values, state.tripKm, (now - state.liveSinceMillis).coerceAtLeast(0), state.averageL100, state.vehicle.diesel,
    )
    var page by rememberSaveable { mutableIntStateOf(0) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { SWIPE_DISTANCE.toPx() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Bmw.Surface)
                .pointerInput(cards.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            if (dragged <= -threshold) page = (page + 1) % cards.size
                            if (dragged >= threshold) page = (page + cards.size - 1) % cards.size
                        },
                    ) { _, amount -> dragged += amount }
                }
                .clickable { page = (page + 1) % cards.size }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (pair in cards[page % cards.size].chunked(2)) {
                Row(Modifier.fillMaxWidth()) {
                    for (cell in pair) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(cell.value, color = Bmw.Text, fontSize = 24.sp, fontWeight = FontWeight.Light, maxLines = 1)
                            Text(cell.tag, color = Bmw.TextDim, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (index in cards.indices) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (index == page % cards.size) Bmw.Accent else Bmw.Line))
            }
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

/** La linea de abajo: el deposito con lo que queda, que es una cuenta, la hora y la temperatura de fuera. */
@Composable
private fun Bottom(state: LiveState, modifier: Modifier = Modifier) {
    val values = state.values
    val tank = values[Pids.bmw(Pids.BMW_TANK_ADDRESS)]
    val clock by produceState(clockText()) {
        while (true) {
            value = clockText()
            delay(10_000)
        }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tank != null) {
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Bmw.Line)) {
                // La barra sale del nivel en tanto por ciento que da el coche; sin el, de los litros
                // sobre la capacidad del deposito del Serie 1, que es aproximada.
                val level = values[FUEL_LEVEL]?.let { it / 100.0 } ?: (tank / TANK_LITERS)
                Box(Modifier.fillMaxWidth(level.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(Bmw.Text))
            }
            Text(formatValue(tank, 0) + " L", color = Bmw.Text, fontSize = 13.sp, maxLines = 1)
            Dashboard.rangeKm(values, state.averageL100)?.let {
                Text(Dashboard.CALCULATED + formatValue(it, 0) + " km", color = Bmw.TextDim, fontSize = 13.sp, maxLines = 1)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        MStripe()
        Text(
            listOfNotNull(clock, values[OUTSIDE_TEMP]?.let { formatValue(it, 0) + " °C" }).joinToString("  ·  "),
            color = Bmw.TextDim,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

private fun clockText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

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
