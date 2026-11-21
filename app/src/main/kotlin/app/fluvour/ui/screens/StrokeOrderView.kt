package app.fluvour.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.fluvour.reading.KanjiStrokes
import app.fluvour.ui.theme.Fluvour

/** Draws a kanji stroke by stroke with numbers. Tap it to play again. */
@Composable
fun StrokeOrderView(strokes: KanjiStrokes, modifier: Modifier = Modifier, boxSize: androidx.compose.ui.unit.Dp = 170.dp) {
    val c = Fluvour.colors
    val paths = remember(strokes) { strokes.d.map { runCatching { PathParser().parsePathString(it).toPath() }.getOrNull() } }
    val measures = remember(paths) { paths.map { p -> p?.let { PathMeasure().apply { setPath(it, false) } } } }
    val n = paths.size
    val progress = remember(strokes) { Animatable(0f) }
    var run by remember(strokes) { mutableIntStateOf(0) }
    LaunchedEffect(strokes, run) {
        progress.snapTo(0f)
        progress.animateTo(n.toFloat(), tween((n * 650).coerceIn(700, 9000), easing = LinearEasing))
    }
    val measurer = rememberTextMeasurer()
    Box(
        modifier.size(boxSize).clip(RoundedCornerShape(12.dp)).background(c.card).clickable { run++ }
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val k = size.minDimension / 109f
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            val gridW = 1f
            drawLine(c.line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), gridW, pathEffect = dash)
            drawLine(c.line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), gridW, pathEffect = dash)
            val p = progress.value
            scale(k, pivot = Offset.Zero) {
                for (i in 0 until n) {
                    val path = paths[i] ?: continue
                    val style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    if (p >= i + 1) {
                        drawPath(path, c.ink, style = style)
                    } else if (p > i) {
                        val m = measures[i] ?: continue
                        val seg = Path()
                        m.getSegment(0f, m.length * (p - i), seg, true)
                        drawPath(seg, c.accent, style = style)
                    }
                }
            }
            val fs = with(this) { (8f * k).toSp() }
            for (i in 0 until minOf(n, strokes.nums.size)) {
                if (p <= i) continue
                val (x, y) = strokes.nums[i]
                val col = if (p < i + 1) c.accent else c.mute
                drawText(measurer, (i + 1).toString(), Offset(x * k, (y - 8f) * k), TextStyle(color = col, fontSize = fs))
            }
        }
    }
}
