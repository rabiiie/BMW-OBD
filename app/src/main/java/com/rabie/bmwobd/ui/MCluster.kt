package com.rabie.bmwobd.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

const val CLUSTER_ASPECT = 1.55f

// Medio hexagono abierto hacia el centro, de abajo arriba, en fracciones del ancho y del alto.
private val BRACKET = listOf(0.36f to 0.95f, 0.14f to 0.95f, 0.03f to 0.50f, 0.14f to 0.05f, 0.36f to 0.05f)

/**
 * Cuadro al estilo de los BMW actuales: dos instrumentos angulares enfrentados, velocidad a la
 * izquierda y revoluciones a la derecha, que se llenan de abajo arriba con los colores M. Las
 * cifras van dentro de cada instrumento y en el hueco central. [reveal] dibuja solo el principio
 * del trazo, para la animacion de arranque. El tamaño lo pone quien lo usa, con la proporcion
 * [CLUSTER_ASPECT].
 */
@Composable
fun MCluster(
    speedFraction: Float,
    rpmFraction: Float,
    speedLabels: List<String>,
    rpmLabels: List<String>,
    rpmRedFrom: Float,
    modifier: Modifier = Modifier,
    reveal: Float = 1f,
    animate: Boolean = true,
    left: @Composable () -> Unit = {},
    center: @Composable () -> Unit = {},
    right: @Composable () -> Unit = {},
) {
    val spec = if (animate) tween<Float>(350, easing = LinearEasing) else snap<Float>()
    val speed by animateFloatAsState(speedFraction.coerceIn(0f, 1f), spec, label = "velocidad")
    val rpm by animateFloatAsState(rpmFraction.coerceIn(0f, 1f), spec, label = "rpm")
    val measurer = rememberTextMeasurer()

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val speedBrush = Brush.verticalGradient(listOf(Bmw.MBlueLight, Bmw.MBlueDark))
            val rpmBrush = Brush.verticalGradient(listOf(Bmw.MRed, Bmw.Amber, Bmw.MBlueLight))
            instrument(mirrored = false, value = speed, redFrom = 1f, labels = speedLabels, brush = speedBrush, reveal = reveal, measurer = measurer)
            instrument(mirrored = true, value = rpm, redFrom = rpmRedFrom, labels = rpmLabels, brush = rpmBrush, reveal = reveal, measurer = measurer)

            // Dos trazos finos cierran la figura por arriba y por abajo entre los dos instrumentos.
            val gap = Bmw.Line.copy(alpha = reveal)
            for (y in listOf(0.05f, 0.95f)) {
                drawLine(gap, Offset(size.width * 0.40f, size.height * y), Offset(size.width * 0.60f, size.height * y), strokeWidth = size.height * 0.008f)
            }
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(0.11f))
            Slot(Modifier.weight(0.28f), left)
            Slot(Modifier.weight(0.22f), center)
            Slot(Modifier.weight(0.28f), right)
            Spacer(Modifier.weight(0.11f))
        }
    }
}

@Composable
private fun Slot(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

private fun DrawScope.instrument(
    mirrored: Boolean,
    value: Float,
    redFrom: Float,
    labels: List<String>,
    brush: Brush,
    reveal: Float,
    measurer: TextMeasurer,
) {
    val stroke = size.height * 0.034f
    val path = Path()
    BRACKET.forEachIndexed { index, (fx, fy) ->
        val x = size.width * (if (mirrored) 1f - fx else fx)
        val y = size.height * fy
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length

    fun piece(from: Float, to: Float): Path {
        val segment = Path()
        measure.getSegment(length * from, length * to, segment, true)
        return segment
    }

    val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (reveal > 0f) drawPath(piece(0f, reveal), Bmw.Line, style = style)
    if (reveal > redFrom) drawPath(piece(redFrom, reveal), Bmw.MRed.copy(alpha = 0.55f), style = Stroke(stroke, join = StrokeJoin.Round))
    val shown = minOf(value, reveal)
    if (shown > 0f) {
        val filled = piece(0f, shown)
        drawPath(filled, brush, alpha = 0.22f, style = Stroke(stroke * 2.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(filled, brush, style = style)
    }

    // Las marcas y sus numeros van por dentro, hacia el centro del instrumento.
    val inside = Offset(size.width * (if (mirrored) 0.72f else 0.28f), size.height * 0.5f)
    val labelStyle = TextStyle(color = Bmw.TextDim, fontSize = (size.height * 0.058f).toSp())
    val steps = (labels.size - 1).coerceAtLeast(1)
    for (i in labels.indices) {
        val f = i.toFloat() / steps
        if (f > reveal) break
        val at = measure.getPosition(length * f)
        val tangent = measure.getTangent(length * f)
        var normal = Offset(-tangent.y, tangent.x)
        if (normal.x * (inside.x - at.x) + normal.y * (inside.y - at.y) < 0f) normal = -normal
        val color: Color = if (f >= redFrom) Bmw.MRed else Bmw.Text
        drawLine(color, at + normal * (stroke * 0.9f), at + normal * (stroke * 1.9f), strokeWidth = stroke * 0.22f)
        val layout = measurer.measure(labels[i], labelStyle)
        val center = at + normal * (stroke * 3.4f)
        drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
    }
}
