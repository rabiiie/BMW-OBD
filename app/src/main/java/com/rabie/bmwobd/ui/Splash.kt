package com.rabie.bmwobd.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPLASH_SPEED_LABELS = listOf("0", "40", "80", "120", "160", "200", "240")
private val SPLASH_RPM_LABELS = listOf("0", "1", "2", "3", "4", "5", "6")
private const val SPLASH_RED_FROM = 5f / 6f

/**
 * Arranque: los dos instrumentos se dibujan, se llenan hasta el fondo y vuelven a cero, como el
 * chequeo del cuadro al dar el contacto. Debajo aparece el nombre del ultimo coche.
 */
@Composable
fun SplashScreen(title: String, onFinished: () -> Unit) {
    val outline = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    val caption = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        outline.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        launch { caption.animateTo(1f, tween(600)) }
        sweep.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        sweep.animateTo(0f, tween(800, easing = FastOutSlowInEasing))
        delay(300)
        onFinished()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Bmw.Background).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        MCluster(
            speedFraction = sweep.value,
            rpmFraction = sweep.value,
            speedLabels = SPLASH_SPEED_LABELS,
            rpmLabels = SPLASH_RPM_LABELS,
            rpmRedFrom = SPLASH_RED_FROM,
            reveal = outline.value,
            animate = false,
            modifier = Modifier.weight(1f, fill = false).aspectRatio(CLUSTER_ASPECT),
        )
        Text(
            title,
            modifier = Modifier.alpha(caption.value),
            color = Bmw.Text,
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 2.sp,
            maxLines = 1,
        )
        MStripe(Modifier.alpha(caption.value))
    }
}
