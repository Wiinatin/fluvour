package app.fluvour.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.reading.PadDrawing
import app.fluvour.reading.PadMath
import app.fluvour.reading.PadPoint
import app.fluvour.ui.uiWeight
import app.fluvour.ui.theme.Fluvour

/** Stroke width as a share of the pad side: about 7dp on a 280dp pad. The preview uses the same share, so it looks the same, only smaller. */
private const val STROKE_SHARE = 0.025f

/**
 * A square pad to write a kanji on with a finger, plus a row under it: Undo, Grid, Clear.
 * No card logic here. The caller keeps [drawing] and [showGrid] in its own state, so they can survive
 * from the front of a card to the back. Nothing is saved.
 *
 * The pad takes the first finger only (a second finger or a palm touching later is ignored) and consumes
 * its touches, so a click or scroll on a parent does not fire while drawing.
 *
 * [ghost] (optional) is drawn behind the strokes and over the grid, like in [PadPreview]: the practice page puts a dimmed copy of
 * the kanji there as a trace guide. Callers that do not pass it are unchanged.
 */
@Composable
fun WritingPad(
    drawing: PadDrawing,
    onDrawingChange: (PadDrawing) -> Unit,
    showGrid: Boolean,
    onShowGridChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    padSize: Dp = 280.dp,
    ghost: (@Composable BoxScope.() -> Unit)? = null
) {
    val latest by rememberUpdatedState(drawing)
    val emit by rememberUpdatedState(onDrawingChange)
    Column(modifier.width(padSize), horizontalAlignment = Alignment.CenterHorizontally) {
        PadSurface(Modifier.size(padSize), drawing, showGrid, ghost = ghost) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val id = down.id
                    fun pt(o: Offset) = PadPoint(o.x / size.width, o.y / size.height)
                    var cur = latest.begin(pt(down.position))
                    emit(cur)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        cur = cur.add(pt(change.position))
                        emit(cur)
                    }
                    emit(cur.end())
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            PadButton("Undo", { emit(latest.undo()) }, enabled = !drawing.isEmpty)
            PadButton(if (showGrid) "Grid on" else "Grid off", { onShowGridChange(!showGrid) }, hot = showGrid)
            PadButton("Clear", { emit(latest.clear()) }, enabled = !drawing.isEmpty)
        }
    }
}

/**
 * Read-only copy of a drawing, for the back of the card. [ghost] is drawn behind the strokes and over the grid:
 * the caller puts the correct kanji there (for example in the accent colour with some transparency) when the
 * "show answer over my drawing" switch is on.
 */
@Composable
fun PadPreview(
    drawing: PadDrawing,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    showGrid: Boolean = true,
    ghost: (@Composable BoxScope.() -> Unit)? = null
) {
    PadSurface(modifier.size(size), drawing, showGrid, ghost = ghost)
}

/** The dark rounded square: grid, optional ghost, strokes. [input] adds the touch handling (the preview has none). */
@Composable
private fun PadSurface(
    modifier: Modifier,
    drawing: PadDrawing,
    showGrid: Boolean,
    ghost: (@Composable BoxScope.() -> Unit)? = null,
    input: (() -> Modifier)? = null
) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(16.dp)
    val smoothDone = remember(drawing.done) { drawing.done.map { PadMath.smooth(it) } }
    val live = if (drawing.live.isEmpty()) emptyList() else listOf(PadMath.smooth(drawing.live))
    Box(
        modifier.clip(shape).background(c.page, shape).border(1.dp, c.line, shape)
            .then(input?.invoke() ?: Modifier)
    ) {
        if (showGrid) Canvas(Modifier.fillMaxSize()) { drawPadGrid(c.line) }
        if (ghost != null) ghost()
        Canvas(Modifier.fillMaxSize()) {
            val w = size.minDimension * STROKE_SHARE
            drawPadStrokes(smoothDone, c.ink, w)
            drawPadStrokes(live, c.ink, w)
        }
    }
}

/** Dashed cross, same look as in [StrokeOrderView]: `line` colour, thin, dashed. */
private fun DrawScope.drawPadGrid(color: Color) {
    val inset = 8.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    val w = 1.dp.toPx()
    drawLine(color, Offset(size.width / 2, inset), Offset(size.width / 2, size.height - inset), w, pathEffect = dash)
    drawLine(color, Offset(inset, size.height / 2), Offset(size.width - inset, size.height / 2), w, pathEffect = dash)
}

private fun DrawScope.drawPadStrokes(strokes: List<List<PadPoint>>, color: Color, width: Float) {
    val w = size.width
    val h = size.height
    for (s in strokes) {
        if (s.isEmpty()) continue
        if (s.size == 1) {
            drawCircle(color, width / 2f, Offset(s[0].x * w, s[0].y * h))
            continue
        }
        val path = Path().apply {
            moveTo(s[0].x * w, s[0].y * h)
            for (i in 1 until s.size) lineTo(s[i].x * w, s[i].y * h)
        }
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Small outlined button for the row under the pad. At least 44dp high. [hot] = accent outline and text (grid on). */
@Composable
private fun PadButton(text: String, onClick: () -> Unit, enabled: Boolean = true, hot: Boolean = false) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.heightIn(min = 44.dp).widthIn(min = 68.dp).alpha(if (enabled) 1f else 0.4f)
            .clip(shape).border(1.dp, if (hot) c.accent else c.line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, fontWeight = uiWeight(), color = if (hot) c.accent else c.ink)
    }
}
