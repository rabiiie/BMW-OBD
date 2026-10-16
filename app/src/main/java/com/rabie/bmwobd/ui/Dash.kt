package com.rabie.bmwobd.ui

import android.os.SystemClock
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
private val WING_WIDTH = 112.dp
private val WING_STROKE = 2.5.dp
private val WING_PAD = 12.dp
private val WING_INSET = 4.dp
private val WING_ARM = 40.dp
private val WING_CAP = 12.dp
private val WING_GAP = 4.dp
private val WING_BAND = 12.dp
private val WING_TICK = 5.dp
private val WING_CONTENT_SHIFT = 30.dp
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
            SideWing(
                fraction = ((speed ?: 0.0) / SPEED_MAX).toFloat(),
                mirrored = false,
                redFrom = 1f,
                marks = listOf(0, 60, 120, 180, 240).map { (it / SPEED_MAX).toFloat() to it.toString() },
                modifier = Modifier.width(WING_WIDTH).fillMaxHeight(),
            ) {
                Text(speed?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = 38.sp, fontWeight = FontWeight.Light, maxLines = 1)
                Label("km/h")
                Cruise(values)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Sin marcha conocida el hueco se queda, para que la tarjeta no salte de sitio.
                    val gear = state.gear
                    Text(
                        when {
                            gear == null -> " "
                            gear < 1 -> "N"
                            else -> Dashboard.CALCULATED + "${gear}ª"
                        },
                        color = Bmw.Text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Light,
                    )
                    // La recomendacion de cambio, tambien calculada: flecha arriba para subir, abajo para bajar.
                    when (state.shift) {
                        ShiftHint.UP -> Text("▲", color = Bmw.Accent, fontSize = 18.sp)
                        ShiftHint.DOWN -> Text("▼", color = Bmw.Amber, fontSize = 18.sp)
                    }
                    Box(Modifier.weight(1f)) { Consumption(state) }
                }
                PageCards(state, onWatch, onCase, Modifier.weight(1f))
            }
            SideWing(
                fraction = ((rpm ?: 0.0) / vehicle.rpmMax).toFloat(),
                mirrored = true,
                redFrom = vehicle.rpmRedFrom,
                marks = (0..(vehicle.rpmMax / 1000).toInt()).map { (it * 1000 / vehicle.rpmMax).toFloat() to it.toString() },
                modifier = Modifier.width(WING_WIDTH).fillMaxHeight(),
            ) {
                Text(rpm?.let { formatValue(it, 0) } ?: "—", color = Bmw.Text, fontSize = 30.sp, fontWeight = FontWeight.Light, maxLines = 1)
                Label("rpm")
            }
        }
        Bottom(state, Modifier.height(INFO_HEIGHT))
    }
}

/**
 * Un reloj en angulo, como los del cuadro de un BMW actual: un trazo que hace pico hacia fuera y,
 * por dentro, una banda que se llena de abajo arriba. El de la izquierda tiene el pico a la
 * izquierda y el de la derecha al reves. La cifra va en el hueco que deja.
 */
@Composable
private fun SideWing(
    fraction: Float,
    mirrored: Boolean,
    redFrom: Float,
    marks: List<Pair<Float, String>>,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(350, easing = LinearEasing), label = "marcador")
    val measurer = rememberTextMeasurer()
    val outline = Bmw.Text
    val dim = Bmw.TextDim
    val track = Bmw.MBlueDark
    val low = Bmw.Accent
    val high = Bmw.MBlueLight
    val red = Bmw.MRed
    val labelStyle = TextStyle(color = dim, fontSize = 11.sp)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val top = WING_PAD.toPx()
            val bottom = size.height - top
            val mid = size.height / 2
            val half = (bottom - top) / 2
            val edge = WING_INSET.toPx()
            val arm = WING_ARM.toPx()
            val gap = WING_GAP.toPx()
            val band = WING_BAND.toPx()
            // Las medidas se cuentan desde el lado del pico; en el de la derecha se dan la vuelta.
            fun x(fromPeak: Float) = if (mirrored) size.width - fromPeak else fromPeak
            fun peakAt(y: Float) = edge + arm * abs(y - mid) / half
            fun yAt(t: Float) = bottom - t * (bottom - top)

            val trace = Path().apply {
                moveTo(x(edge + arm + WING_CAP.toPx()), top)
                lineTo(x(edge + arm), top)
                lineTo(x(edge), mid)
                lineTo(x(edge + arm), bottom)
                lineTo(x(edge + arm + WING_CAP.toPx()), bottom)
            }
            val inside = Path().apply {
                moveTo(x(edge + arm + gap), top)
                lineTo(x(edge + gap), mid)
                lineTo(x(edge + arm + gap), bottom)
                lineTo(x(edge + arm + gap + band), bottom)
                lineTo(x(edge + gap + band), mid)
                lineTo(x(edge + arm + gap + band), top)
                close()
            }
            drawPath(inside, Brush.verticalGradient(listOf(track.copy(alpha = 0.55f), track.copy(alpha = 0.2f)), top, bottom))
            if (redFrom < 1f) clipRect(top = top, bottom = yAt(redFrom)) { drawPath(inside, red.copy(alpha = 0.45f)) }
            if (shown > 0.005f) {
                clipRect(top = yAt(shown), bottom = bottom) { drawPath(inside, Brush.verticalGradient(listOf(high, low), top, bottom)) }
                if (shown > redFrom) clipRect(top = yAt(shown), bottom = yAt(redFrom)) { drawPath(inside, red) }
            }
            drawPath(trace, outline, style = Stroke(WING_STROKE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // La raya que marca por donde va, un poco mas larga que la banda.
            val level = yAt(shown)
            val from = peakAt(level) + gap
            drawLine(outline, Offset(x(from), level), Offset(x(from + band + WING_TICK.toPx()), level), 2.dp.toPx(), StrokeCap.Round)

            // Las marcas de la escala. La cifra de las del medio no se pinta: ahi va el numero grande.
            for ((t, text) in marks) {
                val y = yAt(t)
                val start = peakAt(y) + gap + band + 2.dp.toPx()
                drawLine(dim, Offset(x(start), y), Offset(x(start + WING_TICK.toPx()), y), 1.dp.toPx())
                if (abs(t - 0.5f) < MARK_TEXT_FROM_MIDDLE) continue
                val layout = measurer.measure(text, labelStyle)
                val left = start + WING_TICK.toPx() + 3.dp.toPx()
                val textX = if (mirrored) size.width - left - layout.size.width else left
                drawText(layout, topLeft = Offset(textX, (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)))
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
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = CARD_SIDE),
            pageSpacing = 8.dp,
            modifier = Modifier.fillMaxWidth().weight(1f),
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
 * Una tarjeta: con sombra y un brillo arriba, como las del cuadro de un Mercedes. Arriba el tema y
 * su estado; en medio los datos en dos columnas, numero grande y debajo que es; abajo, si hay un
 * aviso, lo que hay que hacer.
 */
@Composable
private fun PageCard(page: DashPage, modifier: Modifier) {
    val shape = RoundedCornerShape(CARD_RADIUS)
    val accent = page.alert?.let { severityColor(it) }
    Column(
        modifier
            .shadow(CARD_SHADOW, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(CARD_TOP, CARD_BOTTOM)))
            .border(1.dp, Brush.verticalGradient(listOf((accent ?: Color.White).copy(alpha = 0.35f), Color.Transparent)), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(page.title.uppercase(), color = accent ?: Bmw.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            page.status?.let {
                Text(it, color = accent ?: Bmw.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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

private val CARD_TOP = Color(0xFF26303D)
private val CARD_BOTTOM = Color(0xFF12171E)

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
