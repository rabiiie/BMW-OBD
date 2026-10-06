package com.rabie.bmwobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rabie.bmwobd.advice.Advice
import com.rabie.bmwobd.advice.Severity

fun severityColor(severity: Severity): Color = when (severity) {
    Severity.ALERT -> Bmw.MRed
    Severity.WARN -> Color(0xFFF0B429)
    Severity.INFO -> Bmw.MBlueLight
}

/** Un indicio: titulo, explicacion y, en el repaso de un trayecto, cuanto duro. */
@Composable
fun AdviceCard(advice: Advice, modifier: Modifier = Modifier, note: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(severityColor(advice.severity)))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(advice.title, color = Bmw.Text, fontSize = 16.sp)
            Text(advice.detail, color = Bmw.TextDim, fontSize = 13.sp)
            note?.let { Label(it, Modifier.padding(top = 4.dp)) }
        }
    }
}
