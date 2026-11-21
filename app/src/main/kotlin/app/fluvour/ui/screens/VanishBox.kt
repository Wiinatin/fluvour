package app.fluvour.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import app.fluvour.ui.theme.Fluvour
import kotlin.random.Random

private class Dot(val x: Float, val y: Float, val dx: Float, val dy: Float, val size: Float, val shade: Float)

/**
 * Shows [content] and, while [active], turns it to dust: it fades from left to right while small specks drift up and away.
 * [onDone] is called when the animation has finished (about a second); the owner then moves on to the next card.
 */
@Composable
fun VanishBox(active: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Fluvour.colors
    val density = LocalDensity.current.density
    val progress = remember { Animatable(0f) }
    val dots = remember { val r = Random(11); List(220) { Dot(r.nextFloat(), r.nextFloat(), 30f + r.nextFloat() * 110f, -(20f + r.nextFloat() * 90f), 1.5f + r.nextFloat() * 3.5f, r.nextFloat()) } }
    LaunchedEffect(active) {
        if (active) { progress.snapTo(0f); progress.animateTo(1f, tween(1100, easing = LinearEasing)); onDone() }
    }
    Box(modifier) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (active) (1f - progress.value * 1.5f).coerceIn(0f, 1f) else 1f }) { content() }
        if (active) Canvas(Modifier.fillMaxSize()) {
            val p = progress.value
            for (d in dots) {
                // the specks go from left to right: each starts when the sweep reaches it
                val t = ((p - d.x * 0.45f) / 0.55f).coerceIn(0f, 1f)
                if (t <= 0f) continue
                val a = (1f - t) * 0.9f
                drawCircle(lerp(c.mute, c.ink, d.shade).copy(alpha = a), d.size * density, Offset(d.x * size.width + d.dx * density * t, d.y * size.height + d.dy * density * t))
            }
        }
    }
}
