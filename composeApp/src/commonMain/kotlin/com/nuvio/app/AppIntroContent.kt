package com.nuvio.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.AppBrandWordmark
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

internal const val AppIntroMinDurationMs = 1300L

@Composable
internal fun AppIntroContent(modifier: Modifier = Modifier) {
    val wordmarkAlpha = remember { Animatable(0f) }
    val wordmarkScale = remember { Animatable(0.55f) }
    LaunchedEffect(Unit) {
        launch {
            wordmarkAlpha.animateTo(1f, tween(durationMillis = 350, easing = FastOutSlowInEasing))
        }
        wordmarkScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = 120f,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        AppBrandWordmark(
            large = true,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth(0.86f)
                .aspectRatio(com.nuvio.app.features.settings.TotalEclipseLogoAspect)
                .graphicsLayer {
                    alpha = wordmarkAlpha.value
                    scaleX = wordmarkScale.value
                    scaleY = wordmarkScale.value
                },
        )
    }
}
