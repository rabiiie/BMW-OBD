package com.rabie.bmwobd.ui

import android.os.SystemClock
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import com.rabie.bmwobd.cases.Case
import com.rabie.bmwobd.dash.DashPage
import com.rabie.bmwobd.dash.DashPages
import com.rabie.bmwobd.dash.TripNow
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.rabie.bmwobd.dash.Dashboard
import com.rabie.bmwobd.dash.ShiftHint
import com.rabie.bmwobd.dash.Shutdown
import com.rabie.bmwobd.obd.Pids
import kotlinx.coroutines.delay

private const val CARD_TILT = 32f
private const val CARD_SHRINK = 0.14f
private const val CARD_FADE = 0.55f
private const val CARD_CAMERA = 14f
private const val USER_HOLD_MS = 30_000L
private const val MARK_TEXT_FROM_MIDDLE = 0.15f
private const val GLOW_ALPHA = 0.55f
private const val PER_100_FULL = 20.0
private const val PER_HOUR_FULL = 10.0
private const val TANK_LITERS = 53.0
private const val FUEL_LEVEL = 0x2F
private const val OUTSIDE_TEMP = 0x46
private val WING_WIDTH = 150.dp
private val WING_STROKE = 2.dp
private val WING_GLOW = 8.dp
private val WING_PAD = 10.dp
private val WING_SCALE = 30.dp
private val WING_ARM = 34.dp
private val WING_FAR = 2.dp
private val WING_GAP = 4.dp
private val WING_BAND = 9.dp
private val WING_TICK = 5.dp
private val WING_CONTENT_SHIFT = 34.dp
private val MINI_HEIGHT = 44.dp
private val MINI_WIDTH = 7.dp
private const val MINI_SEGMENTS = 5

// Donde va la barra de segmentos, contado desde el lado del pico.
private const val MINI_AT = 0.66f
private val WING_SKEW = 30.dp
private val WING_POOL = 14.dp
private const val COOLANT_FROM = 50.0
private const val COOLANT_TO = 150.0
private val M_RED_DEEP = Color(0xFF5A0F18)
private val CONSUMPTION_HEIGHT = 18.dp
private val STRIP_HEIGHT = 38.dp
private val INFO_HEIGHT = 24.dp
private val CARD_RADIUS = 18.dp
private val CARD_SHADOW = 18.dp
private val CARD_SIDE = 22.dp

/**
 * El panel apaisado: arriba una franja con el aviso mas grave; a los lados, la velocidad y las
 * revoluciones en dos marcadores estrechos; en medio, una tarjeta grande por tema que se pasa
 * deslizando, con un giro en 3D para que se note el cambio. La tarjeta que se ve decide que se lee
 * mas seguido. Un aviso grave pasa una vez a su tarjeta, salvo que se acabe de deslizar a mano.
 */
@Composable
internal fun DashPanel(
    state: LiveState,
    onMenu: () -> Unit,
    onWatch: (List<Int>) -> Unit,
    onCase: (Case?) -> Unit,
    maxWidth: Dp,
    maxHeight: Dp,
) {
    val values = state.values
    val vehicle = state.vehicle
    val rpm = values[Pids.RPM]
    val speed = values[Pids.SPEED]

    Column(
        Modifier.fillMaxSize().background(Bmw.Background).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Strip(state, onMenu)
        val glow = Bmw.MBlueDark
        Row(
            Modifier.fillMaxWidth().weight(1f).drawBehind {
                // El resplandor azul de detras del centro, como en el cuadro del coche.
                drawRect(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = GLOW_ALPHA), Color.Transparent),
                        center = Offset(size.width / 2, size.height * 0.62f),
                        radius = size.height * 0.95f,
                    ),
                )
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tank = values[Pids.bmw(Pids.BMW_TANK_ADDRESS)]
            SideWing(
                fraction = ((speed ?: 0.0) / SPEED_MAX).toFloat(),
                mirrored = false,
                color = Bmw.MBlueLight,
                deep = Bmw.MBlueDark,
                marks = listOf(0, 60, 120, 180, 240).map { (it / SPEED_MAX).toFloat() to it.toString() },
                mini = (values[FUEL_LEVEL]?.let { it / 100.0 } ?: tank?.let { it / TANK_LITERS })?.toFloat(),
                miniLabels = listOf("1", "½", "0"),
                modifier = Modifier.width(WING_WIDTH).fillMaxHeight(),
            ) {
                Text(speed?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("km/h", color = Bmw.MBlueLight, fontSize = 12.sp)
                Cruise(values)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Consumption(state)
                PageCards(state, onWatch, onCase, Modifier.weight(1f))
            }
            val coolant = values[Pids.COOLANT]
            SideWing(
                fraction = ((rpm ?: 0.0) / vehicle.rpmMax).toFloat(),
                mirrored = true,
                color = Bmw.MRed,
                deep = M_RED_DEEP,
                marks = (0..(vehicle.rpmMax / 1000).toInt()).map { (it * 1000 / vehicle.rpmMax).toFloat() to it.toString() },
                mini = coolant?.let { ((it - COOLANT_FROM) / (COOLANT_TO - COOLANT_FROM)).toFloat() },
                miniLabels = listOf("150", "100", "50"),
                modifier = Modifier.width(WING_WIDTH).fillMaxHeight(),
            ) {
                // La marcha va donde el coche pone la suya; es una cuenta de la app. Sin marcha
                // conocida, las revoluciones ocupan su sitio.
                val gear = state.gear
                if (gear == null) {
                    Text(rpm?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("rpm", color = Bmw.MRed, fontSize = 12.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (gear < 1) "N" else "$gear", color = Bmw.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        // La recomendacion de cambio, tambien calculada: flecha arriba para subir, abajo para bajar.
                        when (state.shift) {
                            ShiftHint.UP -> Text("▲", color = Bmw.MBlueLight, fontSize = 16.sp)
                            ShiftHint.DOWN -> Text("▼", color = Bmw.Amber, fontSize = 16.sp)
                        }
                    }
                    Text(rpm?.let { formatValue(it, 0) + " rpm" } ?: "rpm", color = Bmw.MRed, fontSize = 12.sp)
                }
            }
        }
        Bottom(state, Modifier.height(INFO_HEIGHT))
    }
}

/**
 * Un reloj como los del cuadro digital de un BMW actual. Por fuera, un marco de doble trazo que
 * hace pico hacia el borde de la pantalla, con la escala fuera y una banda entre los dos trazos que
 * se llena de abajo arriba. Por dentro del coche, una diagonal larga. El reloj de la derecha es el
 * de la izquierda en espejo, trazo a trazo. El panel es cristal tallado: bloques inclinados arriba y abajo y una franja oscura en medio,
 * donde va la cifra. Abajo, una barra de segmentos: el deposito o la temperatura del motor. El de la
 * izquierda azul y el de la derecha rojo, como en los M, con resplandor de neon.
 */
@Composable
private fun SideWing(
    fraction: Float,
    mirrored: Boolean,
    color: Color,
    deep: Color,
    marks: List<Pair<Float, String>>,
    mini: Float?,
    miniLabels: List<String>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(350, easing = LinearEasing), label = "marcador")
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val miniStyle = TextStyle(color = Bmw.Text, fontSize = 9.sp)
    val middle = lerp(deep, color, 0.45f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val top = WING_PAD.toPx()
            val bottom = size.height - top
            val mid = size.height / 2
            val half = (bottom - top) / 2
            val scale = WING_SCALE.toPx()
            val arm = WING_ARM.toPx()
            val gap = WING_GAP.toPx()
            val band = WING_BAND.toPx()
            val skew = WING_SKEW.toPx()
            val margin = WING_FAR.toPx()
            val inset = gap * 2 + band
            // El lado del pico se cuenta desde el borde de la pantalla y se da la vuelta en el reloj de la derecha.
            fun x(fromPeak: Float) = if (mirrored) w - fromPeak else fromPeak
            fun peakAt(y: Float) = scale + arm * abs(y - mid) / half
            fun yAt(t: Float) = bottom - t * (bottom - top)
            val innerTop = x(w - margin - skew)
            val innerBottom = x(w - margin)
            fun innerAt(y: Float) = innerTop + (innerBottom - innerTop) * (y - top) / (bottom - top)

            // El panel de cristal.
            val panelTop = top + inset
            val panelBottom = bottom - inset
            val panel = Path().apply {
                moveTo(x(scale + inset), mid)
                lineTo(x(scale + arm + inset), panelTop)
                lineTo(innerAt(panelTop) + if (mirrored) inset else -inset, panelTop)
                lineTo(innerAt(panelBottom) + if (mirrored) inset else -inset, panelBottom)
                lineTo(x(scale + arm + inset), panelBottom)
                close()
            }
            drawPath(panel, Brush.verticalGradient(listOf(deep.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.6f), deep.copy(alpha = 0.55f)), panelTop, panelBottom))
            clipPath(panel) {
                // Bloques inclinados como la diagonal: dos arriba y dos abajo.
                val lean = skew * 0.9f
                val bandHalf = (panelBottom - panelTop) * 0.13f
                fun block(fromX: Float, toX: Float, y0: Float, y1: Float, paint: Color) {
                    val shift = lean * (y1 - y0) / (panelBottom - panelTop)
                    drawPath(
                        Path().apply {
                            moveTo(x(fromX), y0)
                            lineTo(x(toX), y0)
                            lineTo(x(toX + shift), y1)
                            lineTo(x(fromX + shift), y1)
                            close()
                        },
                        paint,
                    )
                }
                block(w * 0.05f, w * 0.42f, panelTop, mid - bandHalf, middle.copy(alpha = 0.55f))
                block(w * 0.45f, w * 0.95f, panelTop, mid - bandHalf, middle.copy(alpha = 0.35f))
                block(w * 0.10f, w * 0.55f, mid + bandHalf, panelBottom, middle.copy(alpha = 0.35f))
                block(w * 0.58f, w * 1.05f, mid + bandHalf, panelBottom, middle.copy(alpha = 0.55f))
                // La franja oscura del medio, donde va la cifra.
                drawRect(Color.Black.copy(alpha = 0.7f), Offset(0f, mid - bandHalf), Size(w, bandHalf * 2))
            }

            // La banda que se llena, entre los dos trazos del marco.
            val track = Path().apply {
                moveTo(x(scale + arm + gap), top + gap)
                lineTo(x(scale + gap), mid)
                lineTo(x(scale + arm + gap), bottom - gap)
                lineTo(x(scale + arm + gap + band), bottom - gap)
                lineTo(x(scale + gap + band), mid)
                lineTo(x(scale + arm + gap + band), top + gap)
                close()
            }
            drawPath(track, deep.copy(alpha = 0.35f))
            if (shown > 0.005f) {
                clipRect(top = yAt(shown), bottom = bottom) { drawPath(track, Brush.verticalGradient(listOf(color, deep), top, bottom)) }
            }

            // El marco de fuera, que se cierra en la diagonal de dentro, y el trazo de dentro.
            val outer = Path().apply {
                moveTo(innerTop, top)
                lineTo(x(scale + arm), top)
                lineTo(x(scale), mid)
                lineTo(x(scale + arm), bottom)
                lineTo(innerBottom, bottom)
            }
            val frameInner = Path().apply {
                moveTo(x(scale + arm + inset - gap), top + inset - gap)
                lineTo(x(scale + inset - gap), mid)
                lineTo(x(scale + arm + inset - gap), bottom - inset + gap)
            }
            val diagonal = Path().apply {
                moveTo(innerTop, top)
                lineTo(innerBottom, bottom)
            }
            // El resplandor de debajo de la linea de abajo.
            drawOval(
                Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent), center = Offset(w / 2, bottom), radius = w * 0.55f),
                topLeft = Offset(0f, bottom - WING_POOL.toPx()),
                size = Size(w, WING_POOL.toPx() * 2),
            )
            for (line in listOf(outer, diagonal)) {
                drawPath(line, color.copy(alpha = 0.22f), style = Stroke(WING_GLOW.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(line, color, style = Stroke(WING_STROKE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawPath(frameInner, color.copy(alpha = 0.7f), style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))

            // La raya que marca por donde va.
            val level = yAt(shown)
            val from = peakAt(level) + gap
            drawLine(Color.White, Offset(x(from), level), Offset(x(from + band + WING_TICK.toPx()), level), 2.dp.toPx(), StrokeCap.Round)

            // La escala, por fuera del marco.
            for ((t, text) in marks) {
                val y = yAt(t)
                val edge = peakAt(y)
                drawLine(color, Offset(x(edge - 2.dp.toPx()), y), Offset(x(edge - 2.dp.toPx() - WING_TICK.toPx()), y), 1.5.dp.toPx())
                val layout = measurer.measure(text, labelStyle)
                val right = edge - WING_TICK.toPx() - 4.dp.toPx()
                val textX = if (mirrored) w - right else right - layout.size.width
                drawText(layout, topLeft = Offset(textX, (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
            }

            // La barra de segmentos de abajo, con sus cifras a la izquierda, como en el cuadro.
            val barBottom = panelBottom - 6.dp.toPx()
            val barTop = barBottom - MINI_HEIGHT.toPx()
            val barLeft = if (mirrored) w - w * MINI_AT - MINI_WIDTH.toPx() else w * MINI_AT
            val segment = (barBottom - barTop) / MINI_SEGMENTS
            val lit = mini?.coerceIn(0f, 1f)?.let { (it * MINI_SEGMENTS).roundToInt() } ?: 0
            for (i in 0 until MINI_SEGMENTS) {
                val y = barBottom - (i + 1) * segment
                val on = i < lit
                val paint = when {
                    !on -> Bmw.Line
                    !mirrored && lit <= 1 -> Bmw.Amber
                    mirrored && i >= MINI_SEGMENTS - 1 -> Bmw.MRed
                    else -> Bmw.Text
                }
                drawRect(paint, Offset(barLeft, y + 1.dp.toPx()), Size(MINI_WIDTH.toPx(), segment - 2.dp.toPx()))
            }
            miniLabels.forEachIndexed { index, text ->
                val layout = measurer.measure(text, miniStyle)
                val y = barTop + (barBottom - barTop) * index / (miniLabels.size - 1) - layout.size.height / 2f
                // Las cifras van del lado del pico, en los dos relojes.
                val textX = if (mirrored) barLeft + MINI_WIDTH.toPx() + 4.dp.toPx() else barLeft - 4.dp.toPx() - layout.size.width
                drawText(layout, topLeft = Offset(textX, y))
            }
        }
        Column(
            Modifier.padding(start = if (mirrored) 0.dp else WING_CONTENT_SHIFT, end = if (mirrored) WING_CONTENT_SHIFT else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * El consumo de ahora en una barra fina, encima de las tarjetas. Es una cuenta de la app; parado
 * va en litros por hora. Sin dato el hueco se queda, para que lo de debajo no salte.
 */
@Composable
private fun Consumption(state: LiveState) {
    val per100 = Pids.litersPer100Km(state.values, state.vehicle.diesel)
    val perHour = Pids.fuelRate(state.values, state.vehicle.diesel)
    val value = per100 ?: perHour
    Row(
        Modifier.fillMaxWidth().height(CONSUMPTION_HEIGHT).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (value == null) return@Row
        val full = if (per100 != null) PER_100_FULL else PER_HOUR_FULL
        Text(Dashboard.CALCULATED + formatValue(value, 1), color = Bmw.Text, fontSize = 13.sp, maxLines = 1)
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Bmw.Line)) {
            Box(Modifier.fillMaxWidth((value / full).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(Bmw.Text))
        }
        Text(if (per100 != null) "L/100" else "L/h", color = Bmw.TextDim, fontSize = 11.sp, maxLines = 1)
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
 * Las tarjetas grandes, una por tema. Al deslizar, la que se va gira hacia dentro, se encoge y se
 * apaga, y la que llega hace lo contrario, como las caras de un cubo: asi se nota el cambio.
 */
@Composable
private fun PageCards(state: LiveState, onWatch: (List<Int>) -> Unit, onCase: (Case?) -> Unit, modifier: Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val trip = TripNow(
        state.tripKm, (now - state.liveSinceMillis).coerceAtLeast(0), state.averageL100, state.vehicle.diesel,
        state.tripLiters, state.tripLitersReal, state.fuelPrice,
    )
    val pages = DashPages.pages(state.values, trip, state.advice, bmw = state.cases)
    var saved by rememberSaveable { mutableIntStateOf(0) }
    val pager = rememberPagerState(initialPage = saved.coerceIn(0, pages.size - 1)) { pages.size }
    val scope = rememberCoroutineScope()
    var touchedAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) touchedAt = SystemClock.elapsedRealtime() }
    }
    // Un aviso grave nuevo lleva una vez a su tarjeta, si no se acaba de elegir otra a mano.
    val urgent = state.advice.firstOrNull { it.severity != Severity.INFO }
    LaunchedEffect(urgent?.id) {
        val group = urgent?.let(Dashboard::group) ?: return@LaunchedEffect
        val target = DashPages.indexOf(pages, group) ?: return@LaunchedEffect
        if (SystemClock.elapsedRealtime() - touchedAt > USER_HOLD_MS && target != pager.currentPage) pager.animateScrollToPage(target)
    }
    // Lo que se ve es lo que se lee mas seguido: su caso y sus medidas.
    val shown = pages.getOrNull(pager.currentPage)
    LaunchedEffect(shown?.group) {
        saved = pager.currentPage
        onWatch(Dashboard.alwaysWatched + shown?.group?.let(Dashboard::watched).orEmpty())
        onCase(shown?.group?.case)
    }
    DisposableEffect(Unit) {
        onDispose {
            onWatch(emptyList())
            onCase(null)
        }
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
        Road(state.values[Pids.SPEED] ?: 0.0, Modifier.matchParentSize())
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = CARD_SIDE),
            pageSpacing = 8.dp,
            modifier = Modifier.matchParentSize(),
        ) { index ->
            val offset = ((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
            PageCard(
                pages[index],
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 6.dp)
                    .graphicsLayer {
                        rotationY = offset * CARD_TILT
                        cameraDistance = CARD_CAMERA * density
                        val scale = 1f - CARD_SHRINK * abs(offset)
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - CARD_FADE * abs(offset)
                    }
                    .clickable { scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % pages.size) } },
            )
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            pages.forEachIndexed { index, page ->
                val current = index == pager.currentPage
                val width by animateDpAsState(if (current) 18.dp else 6.dp, label = "punto")
                Box(
                    Modifier.height(6.dp).width(width).clip(CircleShape)
                        .background(page.alert?.let { severityColor(it) } ?: if (current) Bmw.Accent else Bmw.Line),
                )
            }
        }
    }
}

/**
 * La carretera de fondo, en perspectiva: el asfalto que se estrecha hacia el horizonte, los dos
 * bordes como raíles de luz en azul y rojo M, y las rayas de los carriles, que avanzan hacia quien
 * mira a la velocidad del coche. Parado, la carretera se queda quieta.
 */
@Composable
private fun Road(speed: Double, modifier: Modifier) {
    val currentSpeed by rememberUpdatedState(speed)
    val travel by produceState(0f) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) value = (value + ((now - last) / 1e9f) * (currentSpeed.toFloat() / ROAD_KMH_PER_TURN)) % 1f
                last = now
            }
        }
    }
    val left = Bmw.MBlueLight
    val right = Bmw.MRed
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * ROAD_HORIZON
        val farHalf = w * ROAD_FAR_HALF
        val nearHalf = w * ROAD_NEAR_HALF
        val cx = w / 2
        // Lo lejos sube despacio y lo cerca baja deprisa: la profundidad va al cuadrado.
        fun yAt(depth: Float) = horizon + (h - horizon) * depth * depth
        fun halfAt(y: Float) = farHalf + (nearHalf - farHalf) * (y - horizon) / (h - horizon)

        drawRect(Brush.verticalGradient(listOf(Color.Transparent, left.copy(alpha = 0.08f)), 0f, horizon))
        val asphalt = Path().apply {
            moveTo(cx - farHalf, horizon)
            lineTo(cx + farHalf, horizon)
            lineTo(cx + nearHalf, h)
            lineTo(cx - nearHalf, h)
            close()
        }
        drawPath(asphalt, Brush.verticalGradient(listOf(ROAD_FAR, ROAD_NEAR), horizon, h))

        // Los raíles: los dos bordes, con su resplandor.
        for ((sign, color) in listOf(-1f to left, 1f to right)) {
            val from = Offset(cx + sign * farHalf, horizon)
            val to = Offset(cx + sign * nearHalf, h)
            drawLine(color.copy(alpha = 0.25f), from, to, 10.dp.toPx(), StrokeCap.Round)
            drawLine(color, from, to, 2.5.dp.toPx(), StrokeCap.Round)
        }

        // Las rayas de los dos carriles, que avanzan.
        for (lane in listOf(-1f / 3f, 1f / 3f)) {
            for (i in 0 until ROAD_DASHES) {
                val start = (i.toFloat() / ROAD_DASHES + travel) % 1f
                val end = (start + ROAD_DASH_LENGTH).coerceAtMost(1f)
                val y0 = yAt(start)
                val y1 = yAt(end)
                val width = (1f + 5f * start).dp.toPx()
                drawLine(
                    Color.White.copy(alpha = 0.15f + 0.45f * start),
                    Offset(cx + lane * 2 * halfAt(y0), y0),
                    Offset(cx + lane * 2 * halfAt(y1), y1),
                    width,
                )
            }
        }
    }
}

private const val ROAD_HORIZON = 0.35f
private const val ROAD_FAR_HALF = 0.06f
private const val ROAD_NEAR_HALF = 0.62f
private const val ROAD_DASHES = 7
private const val ROAD_DASH_LENGTH = 0.07f

// A 100 km/h las rayas recorren la carretera entera unas tres veces por segundo.
private const val ROAD_KMH_PER_TURN = 33f
private val ROAD_FAR = Color(0xFF0B0E13)
private val ROAD_NEAR = Color(0xFF1A1F27)

/**
 * Una tarjeta: con sombra y un brillo arriba, como las del cuadro de un Mercedes. Arriba el tema y
 * su estado; en medio los datos en dos columnas, numero grande y debajo que es; abajo, si hay un
 * aviso, lo que hay que hacer.
 */
@Composable
private fun PageCard(page: DashPage, modifier: Modifier) {
    val shape = RoundedCornerShape(CARD_RADIUS)
    val accent = page.alert?.let { severityColor(it) }
    val glow = accent ?: Bmw.MBlueLight
    val edge = if (accent != null) Brush.horizontalGradient(listOf(accent, accent)) else Brush.horizontalGradient(M_COLORS)
    Column(
        modifier
            // El resplandor de neon de fuera: una sombra de color y un halo del borde.
            .shadow(CARD_SHADOW, shape, ambientColor = glow, spotColor = glow)
            .drawBehind {
                drawRoundRect(edge, cornerRadius = CornerRadius(CARD_RADIUS.toPx()), style = Stroke(NEON_WIDTH.toPx()), alpha = 0.35f)
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CARD_TOP, CARD_BOTTOM)))
            .border(1.5.dp, edge, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(page.title.uppercase(), color = accent ?: Bmw.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            page.status?.let {
                Text(it, color = accent ?: Bmw.TextDim, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            for (pair in page.cells.chunked(2)) {
                Row(Modifier.fillMaxWidth()) {
                    for (cell in pair) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(cell.value, color = Bmw.Text, fontSize = 24.sp, fontWeight = FontWeight.Light, maxLines = 1)
                            Text(cell.tag, color = Bmw.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        page.note?.let {
            Text(it, color = accent ?: Bmw.Text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val CARD_TOP = Color(0xFF1E2633)
private val CARD_BOTTOM = Color(0xFF0C0F14)
private val NEON_WIDTH = 6.dp

// Los tres colores de la M: azul claro, azul oscuro y rojo.
private val M_COLORS = listOf(Color(0xFF6EC1F5), Color(0xFF1C4F9C), Color(0xFFE22B2B))

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
        NavigatorButton()
        Text("Menú", color = if (top == null) Bmw.Accent else Bmw.Background, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onMenu).padding(6.dp))
    }
}

/**
 * Abre Waze en la otra mitad de la pantalla. Va en la franja para no tener que salir del panel a
 * buscarlo conduciendo. El emblema esta dibujado, como el de BMW: el muñeco blanco con sus dos
 * ojos, la sonrisa y las ruedas sobre fondo azul claro. Si Waze no esta instalado no hace nada.
 */
@Composable
private fun NavigatorButton() {
    val context = LocalContext.current
    Canvas(
        Modifier
            .size(NAVIGATOR_SIZE)
            .clip(RoundedCornerShape(9.dp))
            .background(NAVIGATOR_BLUE)
            .clickable { openWaze(context) },
    ) {
        val w = size.width
        val h = size.height
        val ink = Color(0xFF1B1B1B)
        val line = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // El cuerpo: un bocadillo redondo con el pico abajo a la izquierda.
        val body = Path().apply {
            moveTo(w * 0.30f, h * 0.70f)
            cubicTo(w * 0.10f, h * 0.70f, w * 0.14f, h * 0.52f, w * 0.20f, h * 0.50f)
            cubicTo(w * 0.16f, h * 0.22f, w * 0.40f, h * 0.14f, w * 0.54f, h * 0.14f)
            cubicTo(w * 0.78f, h * 0.14f, w * 0.88f, h * 0.30f, w * 0.88f, h * 0.44f)
            cubicTo(w * 0.88f, h * 0.60f, w * 0.74f, h * 0.70f, w * 0.58f, h * 0.70f)
            close()
        }
        drawPath(body, Color.White)
        drawPath(body, ink, style = line)
        // Los ojos y la sonrisa.
        drawCircle(ink, w * 0.04f, Offset(w * 0.46f, h * 0.36f))
        drawCircle(ink, w * 0.04f, Offset(w * 0.68f, h * 0.36f))
        drawArc(
            ink, 20f, 140f, useCenter = false,
            topLeft = Offset(w * 0.45f, h * 0.36f), size = Size(w * 0.24f, h * 0.20f), style = Stroke(w * 0.05f, cap = StrokeCap.Round),
        )
        // Las ruedas.
        drawCircle(ink, w * 0.075f, Offset(w * 0.36f, h * 0.80f))
        drawCircle(ink, w * 0.075f, Offset(w * 0.64f, h * 0.80f))
    }
}

private val NAVIGATOR_SIZE = 34.dp
private val NAVIGATOR_BLUE = Color(0xFF33CCFF)

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
        ShutdownLight(values)
        MStripe()
        Text(
            listOfNotNull(clock, values[OUTSIDE_TEMP]?.let { formatValue(it, 0) + " °C" }).joinToString("  ·  "),
            color = Bmw.TextDim,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/**
 * La luz de apagado: roja con "ESPERA" si el escape esta muy caliente o el filtro regenera, ambar
 * mientras se enfria y verde cuando ya se puede apagar. En marcha solo se ve el punto; el rotulo
 * sale con el coche parado, que es cuando hace falta.
 */
@Composable
private fun ShutdownLight(values: Map<Int, Double>) {
    val state = Dashboard.shutdown(values) ?: return
    val color = when (state) {
        Shutdown.WAIT -> Bmw.MRed
        Shutdown.COOLING -> Bmw.Amber
        Shutdown.OK -> SHUTDOWN_OK
    }
    val stopped = (values[Pids.SPEED] ?: 1.0) < 1.0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        if (stopped) Text(state.label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private val SHUTDOWN_OK = Color(0xFF3DBE6B)

private fun clockText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

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
