package com.rabie.bmwobd.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.cos
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP = 270f
private const val MINOR_PER_MAJOR = 4

/**
 * Reloj de arco de 270 grados. [fraction] es la posicion de la aguja de 0 a 1 y [labels] los
 * numeros de las divisiones grandes, de principio a fin. [reveal] dibuja solo el principio del
 * arco, para la animacion de arranque. El centro queda libre para [content].
 */
@Composable
fun ArcGauge(
    fraction: Float,
    labels: List<String>,
    modifier: Modifier = Modifier,
    redFrom: Float = 1f,
    reveal: Float = 1f,
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (animate) tween<Float>(350, easing = LinearEasing) else snap<Float>(),
        label = "aguja",
    )
    val measurer = rememberTextMeasurer()

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.028f
            val radius = size.minDimension / 2f - stroke
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            val value = minOf(shown, reveal)

            fun point(r: Float, f: Float): Offset {
                val rad = Math.toRadians((START_ANGLE + SWEEP * f).toDouble())
                return Offset(center.x + r * cos(rad).toFloat(), center.y + r * sin(rad).toFloat())
            }

            drawArc(
                color = Bmw.Line,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP * reveal,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (reveal > redFrom) {
                drawArc(
                    color = Bmw.MRed.copy(alpha = 0.6f),
                    startAngle = START_ANGLE + SWEEP * redFrom,
                    sweepAngle = SWEEP * (reveal - redFrom),
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(stroke),
                )
            }
            if (value > 0f) {
                val color = if (value >= redFrom) Bmw.MRed else Bmw.Accent
                drawArc(
                    color = color.copy(alpha = 0.18f),
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP * value,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(stroke * 2.6f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP * value,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }

            val majors = (labels.size - 1).coerceAtLeast(1)
            val ticks = majors * MINOR_PER_MAJOR
            val tickOuter = radius - stroke * 1.4f
            val labelStyle = TextStyle(color = Bmw.TextDim, fontSize = (size.minDimension * 0.052f).toSp())
            for (i in 0..ticks) {
                val f = i.toFloat() / ticks
                if (f > reveal) break
                val major = i % MINOR_PER_MAJOR == 0
                val tickInner = tickOuter - stroke * (if (major) 2.4f else 1.1f)
                val color = when {
                    f >= redFrom -> Bmw.MRed
                    major -> Bmw.Text
                    else -> Bmw.TextDim
                }
                drawLine(color, point(tickInner, f), point(tickOuter, f), strokeWidth = stroke * (if (major) 0.32f else 0.18f))
                if (major) {
                    val layout = measurer.measure(labels[i / MINOR_PER_MAJOR], labelStyle)
                    val at = point(tickInner - stroke * 2.6f, f)
                    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
                }
            }

            if (reveal <= 0f) return@Canvas

            // La aguja es un trazo corto junto al borde, para no tapar la cifra del centro.
            drawLine(
                color = Bmw.Accent.copy(alpha = 0.25f),
                start = point(radius * 0.60f, value),
                end = point(tickOuter, value),
                strokeWidth = stroke * 1.5f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Bmw.Accent,
                start = point(radius * 0.60f, value),
                end = point(tickOuter, value),
                strokeWidth = stroke * 0.55f,
                cap = StrokeCap.Round,
            )
        }
        content()
    }
}
