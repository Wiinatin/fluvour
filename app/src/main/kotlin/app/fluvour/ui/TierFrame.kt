package app.fluvour.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluvour.reading.Tier
import app.fluvour.ui.theme.Fluvour
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kanji maturity look (Heisig practice, step 6b). The design is "Approved tier frame design" in the handoff and the mockup kanji_tiers_v3.html.
 * Geometry is a 100 x 100 unit square scaled to the tile; line widths stay in dp. All decoration sits INSIDE the tile. Not compiled or run yet.
 */
object TierPalette {
    class Metal(val hi: Color, val base: Color, val lo: Color)

    fun metal(t: Tier): Metal? = when (t) {
        Tier.PLAIN -> null
        Tier.COPPER -> Metal(Color(0xFFF2C2A0), Color(0xFFC98A62), Color(0xFF6E4128))
        Tier.SILVER -> Metal(Color(0xFFF7F8FC), Color(0xFFB4B9C6), Color(0xFF5F6472))
        Tier.GOLD -> Metal(Color(0xFFFFF3C8), Color(0xFFE0BD6E), Color(0xFF7D5E1C))
        Tier.JADE -> Metal(Color(0xFFC4FFF0), Color(0xFF4FD1B0), Color(0xFF1B6E5B))
        Tier.RADIANT -> Metal(Color(0xFFFFE8D6), Color(0xFFF4A67F), Color(0xFFA8512F))
    }

    /** How much of the tier base is mixed into the tile fill. */
    fun fillMix(t: Tier): Float = when (t) { Tier.COPPER -> .14f; Tier.SILVER -> .18f; Tier.GOLD -> .26f; Tier.JADE -> .28f; Tier.RADIANT -> .34f; else -> 0f }

    /** Plain tint for kanji in text (reading and watch pages, Answer box): the metal base colour, or null for PLAIN. */
    fun tint(t: Tier): Color? = metal(t)?.base
}

fun tierName(t: Tier): String = t.name.lowercase().replaceFirstChar { it.uppercase() }

/** One shared clock for a whole grid, in seconds. Null when the phone has animations off: everything is then drawn still. [active] false (scrolling, or no Gold+ tile) freezes it. */
@Composable
fun rememberTierClock(active: Boolean): (() -> Double)? {
    val ctx = LocalContext.current
    val animOk = remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(true) }
    if (!animOk) return null
    val nanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (active) {
            val start = androidx.compose.runtime.withFrameNanos { it } - nanos.longValue
            while (true) androidx.compose.runtime.withFrameNanos { nanos.longValue = it - start }
        }
    }
    return remember { { nanos.longValue / 1e9 } }
}

private class TierSeed(seed: Int) {
    private val r = java.util.Random(seed.toLong() * 2654435761L)
    val phase = r.nextFloat() * 10f           // 0 to 10 s
    val speed = .85f + r.nextFloat() * .35f   // 0.85 to 1.2
    val angle = 105f + r.nextInt(60)          // 105 to 164 degrees
}

private fun ease(p: Float) = p * p * (3f - 2f * p)
private fun breathe(p: Float) = ((1f - cos(2.0 * PI * p)) / 2.0).toFloat()

/**
 * Draws the tier frame behind (and a little over) the tile's content. PLAIN draws nothing. [tile] is the plain tile colour.
 * [clock] null = fully still. Plain, Copper and Silver are always still; Gold, Jade and Radiant move (see the handoff).
 */
fun Modifier.tierFrame(tier: Tier, seed: Int, tile: Color, corner: Dp = 10.dp, clock: (() -> Double)? = null): Modifier {
    val m = TierPalette.metal(tier) ?: return this
    return drawWithCache {
        val sd = TierSeed(seed)
        val w = size.width; val h = size.height
        val sx = w / 100f; val sy = h / 100f
        val hair = 1.dp.toPx()
        val cr = corner.toPx()
        val hi = tier >= Tier.GOLD
        val silverUp = tier >= Tier.SILVER
        fun sv(d: String): Path = PathParser().parsePathString(d).toPath().also { it.transform(Matrix().apply { scale(sx, sy) }) }
        val metal = Brush.linearGradient(0f to m.hi, .3f to m.base, .55f to m.lo, .78f to m.base, 1f to m.hi, start = Offset.Zero, end = Offset(w, h))
        val a = Math.toRadians(sd.angle.toDouble())
        val dirB = Offset(sin(a).toFloat(), -cos(a).toFloat())
        val half = (abs(w * sin(a)) + abs(h * cos(a))).toFloat() / 2f
        val border = Brush.linearGradient(0f to m.hi, .3f to m.base, .55f to m.lo, .78f to m.base, 1f to m.hi,
            start = Offset(w / 2, h / 2) - dirB * half, end = Offset(w / 2, h / 2) + dirB * half)
        val fill = Brush.radialGradient(0f to lerp(tile, m.base, TierPalette.fillMix(tier)), 1f to tile, center = Offset(w * .5f, h * .4f), radius = w * .64f)
        val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(cr))) }

        val plateD = "M14 6H86L94 14V86L86 94H14L6 86V14Z"
        val plate = sv(plateD)
        val shadow = sv(plateD).also { it.translate(Offset(.6f * sx, .9f * sy)) }
        val gloss = Brush.verticalGradient(0f to Color.White.copy(alpha = .12f), 1f to Color.Transparent, startY = 6f * sy, endY = 60f * sy)
        val band = if (hi) sv("M6 80H94V86L86 94H14L6 86Z") else null

        // corner brackets (mirrored), Silver and up
        val brackets = Path().apply {
            for ((fx, fy) in listOf(false to false, true to false, false to true, true to true)) {
                fun px(x: Float) = (if (fx) 100f - x else x) * sx
                fun py(y: Float) = (if (fy) 100f - y else y) * sy
                moveTo(px(9.5f), py(24f)); lineTo(px(9.5f), py(15.5f)); lineTo(px(15.5f), py(9.5f)); lineTo(px(24f), py(9.5f))
            }
        }
        val divider = when (tier) { Tier.SILVER, Tier.GOLD -> sv("M6 80H94"); Tier.JADE, Tier.RADIANT -> sv("M13 80H43M57 80H87"); else -> null }
        val diamond = if (tier == Tier.JADE || tier == Tier.RADIANT) sv("M50 76.4L53.6 80L50 83.6L46.4 80Z") else null
        val ticks = if (hi) sv("M6 50H11M89 50H94") else null
        val cap = if (tier == Tier.GOLD || tier == Tier.RADIANT) sv("M35 6H65L59.5 11H40.5Z") else null
        val gem = if (tier == Tier.JADE || tier == Tier.RADIANT) sv("M50 1.5L54.5 6L50 10.5L45.5 6Z") else null
        val cracks = if (tier == Tier.GOLD) listOf(sv("M14 6L17 13L14.5 18L19 24L17 28M19 24L25 25"), sv("M94 70L88 72L90 77L84 79"), sv("M30 94L32 88L29 84L33 80")) else emptyList()
        fun starPath(cx: Float, cy: Float, r: Float): Path = Path().apply {
            val pts = listOf(0f to -r, r * .3f to -r * .3f, r to 0f, r * .3f to r * .3f, 0f to r, -r * .3f to r * .3f, -r to 0f, -r * .3f to -r * .3f)
            pts.forEachIndexed { i, (dx, dy) -> if (i == 0) moveTo((cx + dx) * sx, (cy + dy) * sy) else lineTo((cx + dx) * sx, (cy + dy) * sy) }
            close()
        }
        val stars = if (hi) listOf(10f to 10f, 90f to 10f, 10f to 90f, 90f to 90f).map { (x, y) -> Triple(starPath(x, y, 6f), Offset(x * sx, y * sy), 0) } else emptyList()

        // hatch / pattern layers
        val maskBrush = Brush.radialGradient(0f to Color.Black, .26f to Color.Black, .95f to Color.Transparent, center = Offset(w * .5f, h * .45f), radius = w * .74f)
        fun bitmap(wPx: Int, hPx: Int, draw: DrawScope.() -> Unit): ImageBitmap {
            val bmp = ImageBitmap(max(1, wPx), max(1, hPx)); val cv = Canvas(bmp)
            CanvasDrawScope().draw(this, layoutDirection, cv, Size(bmp.width.toFloat(), bmp.height.toFloat()), draw)
            return bmp
        }
        fun DrawScope.hatch(step: Float, slash: Boolean, color: Color) {
            val d = step * 1.4142f; var k = -h
            while (k < w) {
                if (slash) drawLine(color, Offset(k, h), Offset(k + h, 0f), hair) else drawLine(color, Offset(k, 0f), Offset(k + h, h), hair)
                k += d
            }
        }
        val cellW = w * .2f; val cellH = h * .1f
        fun DrawScope.waves(width: Float) {
            val ux = cellW / 16f; val uy = cellH / 8f
            val stroke = Stroke(max(.5f, .6f * ux))
            val col = m.base.copy(alpha = .6f)
            var j = -1
            while (j * cellH < h + cellH) {
                var i = -1
                while (i * cellW < width + cellW) {
                    val ox = i * cellW; val oy = j * cellH
                    for ((cx, cy) in listOf(8f to 8f, 0f to 4f, 16f to 4f)) for (r in listOf(8f, 5.3f, 2.7f)) {
                        drawArc(col, 180f, 180f, false, Offset(ox + (cx - r) * ux, oy + (cy - r) * uy), Size(2 * r * ux, 2 * r * uy), style = stroke)
                    }
                    i++
                }
                j++
            }
        }
        fun DrawScope.rays() {
            val c = Offset(w * .5f, h * .94f); val far = w * 3f
            val col = m.base.copy(alpha = .3f)
            var deg = -6f
            while (deg < 360f - 6f) {
                val p = Path()
                p.moveTo(c.x, c.y)
                for (dd in listOf(deg, deg + 3f)) { val r = Math.toRadians(dd.toDouble()); p.lineTo(c.x + far * sin(r).toFloat(), c.y - far * cos(r).toFloat()) }
                p.close(); drawPath(p, col)
                deg += 11f
            }
        }
        val patBmp: ImageBitmap? = when (tier) {
            Tier.SILVER -> bitmap(w.toInt(), h.toInt()) { clipPath(plate) { hatch(4.dp.toPx(), false, m.base.copy(alpha = .2f)) }; drawRect(maskBrush, blendMode = BlendMode.DstOut) }
            Tier.GOLD -> bitmap(w.toInt(), h.toInt()) {
                clipPath(plate) { hatch(5.dp.toPx(), false, m.base.copy(alpha = .22f)); hatch(5.dp.toPx(), true, m.base.copy(alpha = .15f)) }
                drawRect(maskBrush, blendMode = BlendMode.DstOut)
            }
            Tier.RADIANT -> bitmap(w.toInt(), h.toInt()) {
                clipPath(plate) { rays() }
                // keep the rays near the bottom only: fade to nothing by about 85% of the height
                drawRect(Brush.verticalGradient(0f to Color.Black, .15f to Color.Black, .55f to Color.Black.copy(alpha = .45f), 1f to Color.Transparent, startY = 0f, endY = h), blendMode = BlendMode.DstOut)
            }
            else -> null
        }
        val waveBmp = if (tier == Tier.JADE) bitmap((w + cellW).toInt() + 1, h.toInt()) { waves(w + cellW) } else null
        val maskBmp = if (tier == Tier.JADE) bitmap(w.toInt(), h.toInt()) { drawRect(maskBrush) } else null

        val sheenCol = Color(0xFFFFF4DC).copy(alpha = .17f)
        val dir105 = Offset(sin(Math.toRadians(105.0)).toFloat(), -cos(Math.toRadians(105.0)).toFloat())
        val travelPath = if (tier == Tier.RADIANT) sv(plateD) else null
        val travelLen = travelPath?.let { PathMeasure().apply { setPath(it, false) }.length } ?: 0f

        fun DrawScope.glow(color: Color, alpha: Float, extent: Float) {
            val n = 8
            for (i in 1..n) {
                val grow = extent * i / n
                val f = 1f - i.toFloat() / (n + 1)
                drawRoundRect(color, Offset(-grow, -grow), Size(w + 2 * grow, h + 2 * grow), CornerRadius(cr + grow), style = Stroke(extent / n + .5f), alpha = alpha * f * f)
            }
        }
        fun DrawScope.segment(measure: PathMeasure, from: Float, len: Float, col: Color, width: Float) {
            val dst = Path(); val a0 = from * travelLen; val b0 = (from + len) * travelLen
            if (b0 <= travelLen) { measure.getSegment(a0, b0, dst, true) } else { measure.getSegment(a0, travelLen, dst, true); measure.getSegment(0f, b0 - travelLen, dst, true) }
            drawPath(dst, col, style = Stroke(width, cap = StrokeCap.Round))
        }
        val measure = PathMeasure().also { if (travelPath != null) it.setPath(travelPath, false) }

        onDrawWithContent {
            val t = clock?.invoke()
            fun cyc(base: Float): Float { val p = base * sd.speed; return (((t ?: 0.0) + sd.phase) % p / p).toFloat() }
            // glow outside the tile: Jade breathes, Radiant is a still warm halo
            if (tier == Tier.JADE && t != null) glow(m.base, .3f * breathe(cyc(5f)), 16.dp.toPx())
            if (tier == Tier.RADIANT) glow(Color(0xFFF4A67F), .24f, 18.dp.toPx())
            drawRoundRect(fill, cornerRadius = CornerRadius(cr))
            drawRoundRect(border, Offset(hair / 2, hair / 2), Size(w - hair, h - hair), CornerRadius(cr), style = Stroke(hair))
            band?.let { drawPath(it, metal, alpha = .28f) }
            drawPath(plate, gloss)
            drawPath(shadow, Color.Black.copy(alpha = .6f), style = Stroke(1.5.dp.toPx()))
            drawPath(plate, metal, alpha = if (hi) 1f else .85f, style = Stroke(hair))
            // pattern layers inside the plate
            if (tier == Tier.JADE && waveBmp != null && maskBmp != null) {
                val frac = if (t != null) ((t + sd.phase) % (14.0 * sd.speed) / (14.0 * sd.speed)).toFloat() else 0f
                clipPath(plate) {
                    drawIntoCanvas { cv ->
                        cv.saveLayer(Rect(0f, 0f, w, h), Paint())
                        drawImage(waveBmp, Offset(-cellW + frac * cellW, 0f))
                        drawImage(maskBmp, blendMode = BlendMode.DstOut)
                        cv.restore()
                    }
                }
            } else patBmp?.let { drawImage(it) }
            if (silverUp) drawPath(brackets, metal, alpha = .6f, style = Stroke(hair))
            divider?.let { drawPath(it, metal, alpha = .75f, style = Stroke(hair)) }
            diamond?.let { drawPath(it, metal) }
            ticks?.let {
                drawPath(it, metal, alpha = .75f, style = Stroke(hair))
                drawCircle(metal, 1.3f * sx, Offset(14f * sx, 50f * sy)); drawCircle(metal, 1.3f * sx, Offset(86f * sx, 50f * sy))
            }
            cap?.let { drawPath(it, metal) }
            gem?.let { drawPath(it, metal) }
            cracks.forEach { drawPath(it, metal, alpha = .9f, style = Stroke(.8.dp.toPx())) }
            stars.forEachIndexed { i, (p, c, _) ->
                var sc = 1f; var al = 1f
                if (tier == Tier.RADIANT && t != null) {
                    val pp = (((t - i * .8) % 3.2 + 3.2) % 3.2 / 3.2).toFloat()
                    val amt = breathe(pp); sc = 1f - .3f * amt; al = 1f - .55f * amt
                }
                withTransform({ scale(sc, sc, c) }) { drawPath(p, metal, alpha = al) }
            }
            drawContent()
            // moving light: Gold, Jade and Radiant only, and only when the clock runs
            if (t != null && hi) {
                val p = cyc(if (tier == Tier.RADIANT) 7f else 10f)
                if (p >= .55f) {
                    val q = (p - .55f) / .45f
                    val cx = (-1.15f + (2.15f + 1.15f) * ease(q)) * w
                    val c = Offset(cx, h / 2); val d = dir105 * (.2f * w)
                    clipPath(shape) { drawRect(Brush.linearGradient(0f to Color.Transparent, .5f to sheenCol, 1f to Color.Transparent, start = c - d, end = c + d)) }
                }
                if (tier == Tier.RADIANT && travelPath != null) {
                    val pos = cyc(5f)
                    segment(measure, pos, .34f, Color(0xFFF4A67F).copy(alpha = .45f), 1.2f * sx)
                    segment(measure, pos, .16f, Color(0xFFF4A67F).copy(alpha = .35f), 3.5f * sx)   // soft glow under the bright part
                    segment(measure, pos, .16f, Color(0xFFFFE6D2), 1.6f * sx)
                }
            }
        }
    }
}

/**
 * One grid tile: kanji big, keyword small. PLAIN is the normal card look; Copper and up get [tierFrame]. Kanji sits in the area from 10% to 76%
 * of the height, the keyword 5.4% from the bottom. The glow may draw a little past the tile (Jade and Radiant only).
 */
@Composable
fun TierTile(ch: String, label: String, tier: Tier, onClick: () -> Unit, modifier: Modifier = Modifier, clock: (() -> Double)? = null, height: Dp = 84.dp, due: Boolean = false) {
    val c = Fluvour.colors
    val shape = RoundedCornerShape(10.dp)
    val metal = TierPalette.metal(tier)
    val kanjiColor = if (tier == Tier.COPPER && metal != null) lerp(c.ink, metal.base, .55f) else c.ink
    val labelColor = if (tier >= Tier.GOLD && metal != null) lerp(c.ink, metal.base, .7f) else c.mute
    val frame = if (tier == Tier.PLAIN) Modifier.background(c.card, shape).border(1.dp, c.line, shape) else Modifier.tierFrame(tier, ch.hashCode(), c.card, 10.dp, clock)
    Box(modifier.fillMaxWidth().height(height).then(frame).clip(shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().padding(top = height * .1f).height(height * .66f), Alignment.Center) {
            Text(ch, fontFamily = targetFont(), fontSize = 30.sp, color = kanjiColor, textAlign = TextAlign.Center, maxLines = 1,
                style = TextStyle(shadow = if (tier == Tier.PLAIN) null else Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 0f)))
        }
        if (due) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(7.dp).background(c.accent, androidx.compose.foundation.shape.CircleShape))
        // the label sits in the band under the divider (80% to 94% of the height), so the divider never strikes through it
        Box(Modifier.fillMaxWidth().padding(top = height * .80f, start = 4.dp, end = 4.dp).height(height * .14f), Alignment.Center) {
            Text(label, fontSize = if (tier == Tier.PLAIN) 10.sp else 9.sp, lineHeight = 10.sp, color = labelColor, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
