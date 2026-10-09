package com.rabie.bmwobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.rabie.bmwobd.health.HealthCard
import com.rabie.bmwobd.health.HealthLevel
import com.rabie.bmwobd.obd.Status

private fun levelColor(level: HealthLevel): Color = when (level) {
    HealthLevel.OK -> statusColor(Status.OK)
    HealthLevel.WATCH -> statusColor(Status.WARN)
    HealthLevel.BAD -> statusColor(Status.ALERT)
    HealthLevel.UNKNOWN -> statusColor(null)
}

/** Lo que cuenta un trayecto en dos o tres frases. */
@Composable
fun TripSummary(lines: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (line in lines) Text(line, color = Bmw.Text, fontSize = 15.sp)
    }
}

/** Como esta un sistema del coche: color, una frase, los datos que la sostienen y que mirar. */
@Composable
fun HealthCardView(card: HealthCard, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(Bmw.Surface),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(levelColor(card.level)))
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Label(card.system.title, color = levelColor(card.level).takeIf { card.level != HealthLevel.UNKNOWN } ?: Bmw.TextDim)
            Text(card.headline, color = Bmw.Text, fontSize = 16.sp)
            for (fact in card.facts) Text(fact, color = Bmw.TextDim, fontSize = 13.sp)
            card.advice?.let { Text("Qué mirar: $it", color = Bmw.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
        }
    }
}
