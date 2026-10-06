package com.rabie.bmwobd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ChartSeries(val name: String, val unit: String, val decimals: Int, val values: DoubleArray)

/** Minimo, maximo y media de una serie, sin contar los huecos. */
private class Scale(val min: Double, val max: Double, val avg: Double) {
    private val span = (max - min).takeIf { it > 0 } ?: 1.0

    fun y(value: Double, height: Float): Float = height - ((value - min) / span).toFloat() * height

    fun at(fraction: Float): Double = min + (max - min) * fraction

    companion object {
        fun of(values: DoubleArray): Scale? {
            val valid = values.filter { !it.isNaN() }
            return if (valid.isEmpty()) null else Scale(valid.min(), valid.max(), valid.average())
        }
    }
}

private val TIME_STEPS_MS = listOf(30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L)
private const val MAX_TIME_LINES = 6
private const val VALUE_LINES = 3

/**
 * Una medida a lo largo del trayecto, con rejilla de valores y de tiempo. Tocando o arrastrando
 * sobre la grafica se ve el valor en ese instante. [second] se dibuja encima con su propia escala,
 * para ver como se mueven dos medidas a la vez.
 */
@Composable
fun TripChart(tMs: LongArray, main: ChartSeries, second: ChartSeries?) {
    val mainScale = remember(main) { Scale.of(main.values) }
    if (mainScale == null || tMs.isEmpty()) {
        Text("Sin datos de esta medida.", color = Bmw.TextDim)
        return
    }
    val secondScale = remember(second) { second?.let { Scale.of(it.values) } }
    val total = tMs.last().coerceAtLeast(1L)
    var cursor by remember(tMs) { mutableStateOf<Float?>(null) }
    val measurer = rememberTextMeasurer()
    val secondColor = Bmw.MBlueLight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Legend(main, mainScale, Bmw.Accent)
        if (second != null && secondScale != null) Legend(second, secondScale, secondColor)

        val at = cursor
        if (at == null) {
            Text("Toca o arrastra sobre la gráfica para ver el valor en un punto.", color = Bmw.TextDim, fontSize = 12.sp)
        } else {
            val index = nearestIndex(tMs, (at * total).toLong())
            val parts = listOfNotNull(
                formatDuration(tMs[index]),
                reading(main, index),
                second?.let { reading(it, index) },
            )
            Text(parts.joinToString("  ·  "), color = Bmw.Text, fontSize = 14.sp)
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(tMs) { detectTapGestures { cursor = (it.x / size.width).coerceIn(0f, 1f) } }
                .pointerInput(tMs) {
                    detectHorizontalDragGestures { change, _ -> cursor = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
        ) {
            val labelStyle = TextStyle(color = Bmw.TextDim, fontSize = 10.sp)
            drawGrid(total, mainScale, main.decimals, secondScale, second?.decimals ?: 0, measurer, labelStyle)

            val line = seriesPath(tMs, main.values, total, mainScale)
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(Bmw.Accent.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(line, Bmw.Accent, style = Stroke(width = 2.dp.toPx()))
            if (second != null && secondScale != null) {
                drawPath(seriesPath(tMs, second.values, total, secondScale), secondColor, style = Stroke(width = 1.5.dp.toPx()))
            }

            if (at != null) {
                val index = nearestIndex(tMs, (at * total).toLong())
                val x = tMs[index].toFloat() / total * size.width
                drawLine(Bmw.Text.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height))
                main.values[index].takeIf { !it.isNaN() }?.let {
                    drawCircle(Bmw.Accent, 4.dp.toPx(), Offset(x, mainScale.y(it, size.height)))
                }
                if (second != null && secondScale != null) {
                    second.values[index].takeIf { !it.isNaN() }?.let {
                        drawCircle(secondColor, 4.dp.toPx(), Offset(x, secondScale.y(it, size.height)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(series: ChartSeries, scale: Scale, color: Color) {
    fun n(value: Double) = formatValue(value, series.decimals)
    Text(
        "${series.name}: media ${n(scale.avg)} · mín ${n(scale.min)} · máx ${n(scale.max)} ${series.unit}",
        color = color,
        fontSize = 13.sp,
    )
}

private fun reading(series: ChartSeries, index: Int): String {
    val value = series.values[index]
    val text = if (value.isNaN()) "—" else formatValue(value, series.decimals)
    return "${series.name} $text ${series.unit}".trim()
}

/** Lineas horizontales con el valor de cada una (el de la segunda serie, a la derecha) y verticales con el minuto. */
private fun DrawScope.drawGrid(
    totalMs: Long,
    main: Scale,
    mainDecimals: Int,
    second: Scale?,
    secondDecimals: Int,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    for (i in 0..VALUE_LINES) {
        val fraction = i.toFloat() / VALUE_LINES
        val y = size.height * (1f - fraction)
        drawLine(Bmw.Line, Offset(0f, y), Offset(size.width, y))
        val labelY = (y - 14.dp.toPx()).coerceAtLeast(0f)
        drawText(measurer.measure(formatValue(main.at(fraction), mainDecimals), style), topLeft = Offset(2.dp.toPx(), labelY))
        if (second != null) {
            val layout = measurer.measure(formatValue(second.at(fraction), secondDecimals), style.copy(color = Bmw.MBlueLight))
            drawText(layout, topLeft = Offset(size.width - layout.size.width - 2.dp.toPx(), labelY))
        }
    }
    val step = TIME_STEPS_MS.firstOrNull { totalMs / it <= MAX_TIME_LINES } ?: TIME_STEPS_MS.last()
    var t = step
    while (t < totalMs) {
        val x = t.toFloat() / totalMs * size.width
        drawLine(Bmw.Line.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height))
        val layout = measurer.measure(formatDuration(t), style)
        drawText(layout, topLeft = Offset(x + 2.dp.toPx(), size.height - layout.size.height))
        t += step
    }
}

/**
 * El trazo de una serie. Si hay mas muestras que pixeles, cada columna de pixeles se dibuja de su
 * minimo a su maximo, para que un pico corto no desaparezca al encoger.
 */
private fun DrawScope.seriesPath(tMs: LongArray, values: DoubleArray, totalMs: Long, scale: Scale): Path {
    val path = Path()
    val columns = size.width.toInt().coerceAtLeast(1)
    var started = false

    fun add(x: Float, value: Double) {
        val y = scale.y(value, size.height)
        if (started) path.lineTo(x, y) else path.moveTo(x, y)
        started = true
    }

    if (tMs.size <= columns * 2) {
        for (i in tMs.indices) if (!values[i].isNaN()) add(tMs[i].toFloat() / totalMs * size.width, values[i])
        return path
    }
    var i = 0
    for (column in 0 until columns) {
        val limit = (column + 1).toDouble() / columns * totalMs
        var low = Double.NaN
        var high = Double.NaN
        while (i < tMs.size && tMs[i] <= limit) {
            val v = values[i]
            if (!v.isNaN()) {
                if (low.isNaN() || v < low) low = v
                if (high.isNaN() || v > high) high = v
            }
            i++
        }
        if (!low.isNaN()) {
            add(column.toFloat(), low)
            if (high != low) add(column.toFloat(), high)
        }
    }
    return path
}

/** La muestra mas cercana a un instante, por busqueda binaria. */
private fun nearestIndex(tMs: LongArray, target: Long): Int {
    var low = 0
    var high = tMs.size - 1
    while (low < high) {
        val mid = (low + high) / 2
        if (tMs[mid] < target) low = mid + 1 else high = mid
    }
    return if (low > 0 && target - tMs[low - 1] < tMs[low] - target) low - 1 else low
}
