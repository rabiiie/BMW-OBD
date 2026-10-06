package com.rabie.bmwobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.obd.Status

/** Paleta del cuadro de instrumentos del E87: fondo negro, iluminacion ambar y la franja M. */
object Bmw {
    val Background = Color(0xFF08090B)
    val Surface = Color(0xFF14161A)
    val SurfaceHigh = Color(0xFF1E2126)
    val Line = Color(0xFF2B2F36)
    val Amber = Color(0xFFFF6A1F)
    val Text = Color(0xFFECE9E4)
    val TextDim = Color(0xFF8C9199)
    val MBlueLight = Color(0xFF6EC1F5)
    val MBlueDark = Color(0xFF1C4F9C)
    val MRed = Color(0xFFE22B2B)
}

private val colors = darkColorScheme(
    primary = Bmw.Amber,
    onPrimary = Color.Black,
    secondary = Bmw.MBlueLight,
    background = Bmw.Background,
    onBackground = Bmw.Text,
    surface = Bmw.Surface,
    onSurface = Bmw.Text,
    surfaceVariant = Bmw.SurfaceHigh,
    onSurfaceVariant = Bmw.TextDim,
    outline = Bmw.Line,
    error = Bmw.MRed,
)

@Composable
fun BmwObdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

fun statusColor(status: Status?): Color = when (status) {
    Status.OK -> Color(0xFF3FB56B)
    Status.WARN -> Color(0xFFF0B429)
    Status.ALERT -> Bmw.MRed
    Status.NEUTRAL -> Bmw.MBlueLight
    null -> Bmw.Line
}

/** La franja de tres colores de BMW M, como detalle de marca. */
@Composable
fun MStripe(modifier: Modifier = Modifier) {
    Row(modifier.width(54.dp).height(3.dp)) {
        for (color in listOf(Bmw.MBlueLight, Bmw.MBlueDark, Bmw.MRed)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(color))
        }
    }
}

/** Rotulo pequeño en mayusculas, como los del cuadro. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Bmw.TextDim) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.5.sp,
    )
}

fun formatValue(value: Double, decimals: Int): String = "%.${decimals}f".format(value)

fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
