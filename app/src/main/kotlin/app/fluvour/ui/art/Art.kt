package app.fluvour.ui.art

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fluvour.ui.theme.Fluvour
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The design's printed illustrations, ported from the HTML (the ART.* functions, zst/zht/zh helpers, the SVG grain filter,
 * the .tw twinkle and the zpt header patterns). Everything is drawn from code, so one drawing serves every language:
 * colours come from the five illustration inks (il1-il5) plus the tint, exactly as the CSS classes i1-i5, k1-k5 and tn did.
 */

/** Runtime switches for the art. Held in memory for now; TODO(settings): persist both with the other settings. */
object ArtPrefs {
    /** Settings > Appearance > Illustrations. Off hides the header patterns, language marks, finish motif and ambient background (the design's .noart). Covers and grain stay. */
    var enabled by mutableStateOf(true)
    /** Settings > Accessibility > Reduce motion. Also respected when the system animation scale is 0. */
    var reduceMotion by mutableStateOf(false)
}

/** The five inks of a language palette plus the wash behind covers (data-lang rows in the design CSS). */
@Immutable
class ArtPalette(val tint: Color, val il1: Color, val il2: Color, val il3: Color, val il4: Color, val il5: Color) {
    fun il(n: Int): Color = when (n) { 1 -> il1; 2 -> il2; 3 -> il3; 4 -> il4; else -> il5 }
    /** .dk in the CSS: il4 mixed 70% with black. */
    val dk: Color get() = Color(il4.red * 0.7f, il4.green * 0.7f, il4.blue * 0.7f)
}

object ArtPalettes {
    private fun c(hex: Long) = Color(0xFF000000 or hex)
    private val ja = ArtPalette(c(0xEBD3B8), c(0xB5532F), c(0xC9663F), c(0xD4915E), c(0x1F3448), c(0xF3E8D6))
    private val th = ArtPalette(c(0xE3E8D3), c(0x4F6B3A), c(0xA9B88F), c(0xD9A93B), c(0x6B4A33), c(0xF1EBD8))
    private val en = ArtPalette(c(0xD5E0E8), c(0x3E5C7A), c(0xA9BFCF), c(0xC98B4B), c(0x22384C), c(0xF1EBDD))
    private val zh = ArtPalette(c(0xBFD6CC), c(0xB3302B), c(0x7FAE9A), c(0xD8A23A), c(0x26302F), c(0xF7EEDB))
    private val ko = ArtPalette(c(0xD2E7E2), c(0x2F7F7A), c(0xA5CFC4), c(0xE2A15B), c(0x25363A), c(0xF3EEE2))
    private val es = ArtPalette(c(0xF0D9AC), c(0xC8841A), c(0xE2B35A), c(0xB8432F), c(0x2E3F5C), c(0xF8EFD9))
    private val fr = ArtPalette(c(0xDBD5EC), c(0x6D5CA6), c(0xB7ABD8), c(0xE0B25C), c(0x2A2C4A), c(0xF4F0E6))
    private val de = ArtPalette(c(0xCEDDD2), c(0x2F5F4B), c(0x9CBBA6), c(0xC9843F), c(0x2B3430), c(0xF1EEE0))
    private val vi = ArtPalette(c(0xF4D3DC), c(0xC25577), c(0xE8A7B8), c(0xE3B84E), c(0x2E4A44), c(0xFBF1E6))
    private val id = ArtPalette(c(0xE8D0BA), c(0x8C5434), c(0xD1A074), c(0x3F7F86), c(0x2A2F45), c(0xF6ECDC))
    /** Illustrations are printed art, so they keep their light inks in dark mode too. Languages without a row borrow the nearest family. */
    fun of(lang: String): ArtPalette = when (lang) {
        "th" -> th
        "en" -> en
        "zh" -> zh
        "ko" -> ko
        "es" -> es
        "fr" -> fr
        "de" -> de
        "vi" -> vi
        "id" -> id
        else -> ja
    }
}

// ---- seeded random + curve helpers (zrng, zrg, zh) ----

/** mulberry32, the same generator as zrng, so stipple lands where the design puts it. */
internal class Rng(seed: Int) {
    private var s = seed
    fun next(): Double {
        s += 0x6D2B79F5
        var t = (s xor (s ushr 15)) * (1 or s)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

private fun rg(x: Double, w: Double, a: Double, b: Double, p: Double, k: Double): Double = a + b * sin(x / w * PI * k + p)

/** A hill silhouette: a gentle sine crest sampled at 24 steps, closed along the bottom. */
private fun hill(w: Float, h: Float, a: Float, b: Float, p: Float, k: Float = 1.2f): Path {
    val path = Path()
    path.moveTo(0f, h)
    path.lineTo(0f, rg(0.0, w.toDouble(), a.toDouble(), b.toDouble(), p.toDouble(), k.toDouble()).toFloat())
    for (i in 1..24) {
        val x = w * i / 24f
        path.lineTo(x, rg(x.toDouble(), w.toDouble(), a.toDouble(), b.toDouble(), p.toDouble(), k.toDouble()).toFloat())
    }
    path.lineTo(w, h)
    path.close()
    return path
}

private val svgPaths = HashMap<String, Path>()
/** Parses an SVG path string once and keeps it. Paths are only read after this, never changed, so sharing is safe. */
private fun svg(d: String): Path = svgPaths.getOrPut(d) { PathParser().parsePathString(d).toPath() }

private val stipples = HashMap<String, Pair<List<Offset>, List<Offset>>>()

/** Stipple dots, dense at the crest and thinning below. The same dots come back every time for the same arguments. */
private fun stippleDots(seed: Int, w: Float, h: Float, a: Float, b: Float, p: Float, k: Float, n: Int, fall: Float): Pair<List<Offset>, List<Offset>> =
    stipples.getOrPut("$seed|$w|$h|$a|$b|$p|$k|$n|$fall") {
        val r = Rng(seed)
        val one = ArrayList<Offset>(); val two = ArrayList<Offset>()
        var c = 0; var g = 0
        val wd = w.toDouble(); val hd = h.toDouble()
        while (c < n && g++ < n * 80) {
            val x = r.next() * wd; val y = r.next() * hd
            val crest = rg(x, wd, a.toDouble(), b.toDouble(), p.toDouble(), k.toDouble())
            if (y < crest) continue
            if (r.next() > exp(-(y - crest) / fall)) continue
            val o = Offset((x * 10).roundToInt() / 10f, (y * 10).roundToInt() / 10f)
            if (r.next() < 0.3) two.add(o) else one.add(o)
            c++
        }
        one to two
    }

// ---- the painter: the z* functions of the design, drawing straight onto a canvas ----

private class Painter(val d: DrawScope, val p: ArtPalette, val clockMs: Float) {
    /** class=tn / i1..i5 / dk fills. */
    fun fill(path: Path, color: Color, alpha: Float = 1f) = d.drawPath(path, color, alpha)
    fun rect(w: Float, h: Float, color: Color, alpha: Float = 1f, y: Float = 0f) =
        d.drawRect(color, Offset(0f, y), Size(w, h), alpha)
    fun circle(cx: Float, cy: Float, r: Float, color: Color, alpha: Float = 1f) = d.drawCircle(color, r, Offset(cx, cy), alpha)
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, alpha: Float = 1f, blend: BlendMode = BlendMode.SrcOver) =
        d.drawOval(color, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), alpha, blendMode = blend)
    fun stroke(path: Path, color: Color, width: Float, alpha: Float = 1f, round: Boolean = true) =
        d.drawPath(path, color, alpha, Stroke(width, cap = if (round) StrokeCap.Round else StrokeCap.Butt))
    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, alpha: Float = 1f) =
        d.drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r), alpha = alpha)
    fun rotatedOval(cx: Float, cy: Float, rx: Float, ry: Float, deg: Float, px: Float, py: Float, color: Color, alpha: Float = 1f, blend: BlendMode = BlendMode.SrcOver) =
        d.rotate(deg, Offset(px, py)) { drawOval(color, Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), alpha, blendMode = blend) }

    /** zsun: a soft halo behind the sun disc. */
    fun sun(x: Float, y: Float, r: Float, color: Color = p.il1) {
        circle(x + 0.8f, y + 0.7f, r * 1.6f, p.il1, 0.16f)
        circle(x, y, r, color)
    }

    /** zst: seeded stipple under a crest. */
    fun stipple(seed: Int, w: Float, h: Float, a: Float, b: Float, ph: Float, k: Float, n: Int, fall: Float, ink: Color, op: Float, w1: Float, w2: Float) {
        val (one, two) = stippleDots(seed, w, h, a, b, ph, k, n, fall)
        d.drawPoints(one, PointMode.Points, ink, w1, StrokeCap.Round, alpha = op)
        d.drawPoints(two, PointMode.Points, ink, w2, StrokeCap.Round, alpha = op)
    }

    /** zht: hatching that follows a crest and opens out like terraces, clipped to the hill. */
    fun hatch(w: Float, h: Float, a: Float, b: Float, ph: Float, k: Float, gap: Float, ink: Color, sw: Float, op: Float, n: Int) {
        val st = w / 24f
        d.clipPath(hill(w, h, a, b, ph, k)) {
            for (j in 0 until n) {
                val dy = j * gap * (1 + j * 0.045f)
                val path = Path()
                var x = -st
                var first = true
                while (x <= w + st) {
                    val y = rg(x.toDouble(), w.toDouble(), a.toDouble(), b.toDouble(), ph.toDouble(), k.toDouble()).toFloat() + dy + gap * 0.6f
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    x += st
                }
                drawPath(path, ink, op, Stroke(sw, cap = StrokeCap.Round))
            }
        }
    }

    /** zsprout: a two-leaf seedling. [c] picks the ink number (stem k<c>, leaves i<c>). */
    fun sprout(x: Float, y: Float, s: Float, c: Int) {
        d.withTransform({ translate(x, y); scale(s, s, Offset.Zero) }) {
            drawPath(svg("M0 0V9"), p.il(c), 1f, Stroke(1.3f, cap = StrokeCap.Round))
            drawPath(svg("M0 0C-1 -4 -7 -4 -10 -10C-4 -11 0 -8 0 0zM0 0C1 -4 7 -4 10 -10C4 -11 0 -8 0 0z"), p.il(c))
        }
    }

    /** zbloom: eight overlapping petals that multiply into each other, with a centre. */
    fun bloom(x: Float, y: Float, rot: Float, ox: Float, ry: Float, rx: Float, cr: Float) {
        for (i in 0 until 8) {
            d.rotate(i * 45f + rot, Offset(x, y)) {
                drawOval(if (i % 2 == 1) p.il2 else p.il1, Offset(x - rx, y - ox - ry), Size(rx * 2, ry * 2), 0.8f, blendMode = BlendMode.Multiply)
            }
        }
        circle(x, y, cr, p.il3)
    }

    /** zcloud: four puffs on a flat base, drawn as one shape so the overlaps stay even. */
    fun cloud(x: Float, y: Float, s: Float) {
        val path = Path()
        listOf(Triple(0f, 0f, 15f), Triple(18f, -8f, 19f), Triple(40f, -2f, 16f), Triple(58f, 4f, 12f)).forEach { (dx, dy, r) ->
            val cx = x + dx * s; val cy = y + dy * s
            path.addOval(Rect(cx - r * s, cy - r * s, cx + r * s, cy + r * s))
        }
        path.addRoundRect(RoundRect(x - 14 * s, y + 2 * s, x - 14 * s + 86 * s, y + 2 * s + 13 * s, CornerRadius(6 * s)))
        fill(path, p.il5, 0.92f)
    }

    /** Twinkle: the design's .tw keyframes (opacity 1 to .2 and back over 3s, each star 0.5s later than the one before). */
    fun twinkle(i: Int): Float = twinkleAfter(i * 0.5f)

    /** The same cycle with the delay given in seconds (the language mark's lamp reflections use their own delays). */
    fun twinkleAfter(delaySec: Float): Float {
        val period = 3000f
        val t = (((clockMs - delaySec * 1000f) % period) + period) % period
        val e = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
        return if (t < period / 2) 1f - 0.8f * e.transform(t / (period / 2)) else 0.2f + 0.8f * e.transform((t - period / 2) / (period / 2))
    }
}

// ---- the scenes ----

/** viewBox of each scene. Covers are 80x100, banners 400x176, motifs 100x100 (ZS in the design). */
private fun viewBoxOf(key: String): Pair<Float, Float> = if (isLangKey(key)) langView(key).let { it[2] to it[3] } else when (key) {
    "b1", "b2", "b3" -> 400f to 176f
    "m1", "m2", "m3" -> 100f to 100f
    "mj", "mt", "me" -> 140f to 140f
    else -> 80f to 100f
}

/** Names the design uses for the same scene (AR.dunes, AR.sea). */
private fun canon(key: String) = when (key) { "dunes" -> "terraces"; "sea" -> "coast"; else -> key }

private fun Painter.scene(raw: String) {
    when (canon(raw)) {
        "terraces" -> {
            rect(80f, 100f, p.tint); sun(58f, 30f, 12f)
            fill(hill(80f, 100f, 62f, 6f, 0.6f), p.il3)
            stipple(7, 80f, 100f, 62f, 6f, 0.6f, 1.2f, 170, 12f, p.il4, 0.6f, 0.9f, 1.5f)
            fill(hill(80f, 100f, 73f, 6f, 2.2f), p.il2)
            stipple(11, 80f, 100f, 73f, 6f, 2.2f, 1.2f, 40, 8f, p.il4, 0.45f, 0.9f, 1.5f)
            hatch(80f, 100f, 73f, 6f, 2.2f, 1.2f, 1.2f, p.il4, 0.5f, 0.4f, 18)
            fill(hill(80f, 100f, 88f, 5f, 4f), p.il4)
        }
        "sunhills" -> {
            rect(80f, 100f, p.tint); sun(50f, 54f, 17f)
            fill(hill(80f, 100f, 66f, 5f, 0.4f, 1f), p.il3)
            fill(hill(80f, 100f, 84f, 5f, 2.6f, 1.1f), p.il4)
        }
        "leaf" -> {
            val leaf = svg("M34 94C16 68 18 34 52 10C68 40 62 74 34 94z")
            rect(80f, 100f, p.tint)
            d.withTransform({ translate(1.4f, 1f) }) { drawPath(leaf, p.il4, 0.12f) }
            fill(leaf, p.il1)
            stroke(svg("M34 94C40 66 46 40 52 10"), p.il5, 0.6f)
            stroke(svg("M39 76C44 74 48 70 51 66M37 64C42 62 46 58 49 53M40 52C44 50 47 46 50 41"), p.il5, 0.45f, 0.55f)
            stroke(svg("M38 80C34 77 31 72 30 67M39 66C35 62 33 57 33 52"), p.il5, 0.45f, 0.55f)
            circle(62f, 80f, 5f, p.il3)
        }
        "moon" -> {
            rect(80f, 100f, p.il4)
            circle(50f, 30f, 20f, p.il5, 0.14f)
            // the crescent: a disc with a second disc cut out of it (the SVG mask)
            val hole = Path().apply { addOval(Rect(57f - 11f, 27f - 11f, 57f + 11f, 27f + 11f)) }
            d.clipPath(hole, ClipOp.Difference) { drawCircle(p.il5, 12f, Offset(50f, 30f)) }
            circle(14f, 18f, 1f, p.il5, 0.7f); circle(68f, 48f, 0.9f, p.il5, 0.7f); circle(24f, 44f, 1.2f, p.il5, 0.7f)
            fill(hill(80f, 100f, 68f, 5f, 1.2f), p.il2, 0.28f)
            stipple(3, 80f, 100f, 68f, 5f, 1.2f, 1.2f, 60, 8f, p.il5, 0.35f, 0.9f, 1.5f)
            fill(hill(80f, 100f, 86f, 4f, 3f), p.dk)
        }
        "coast" -> {
            rect(80f, 100f, p.tint); sun(41f, 36f, 10f, p.il5)
            fill(svg("M0 62H80V100H0z"), p.il2)
            stroke(svg("M33 71h9M45 78h13M35 86h7M46 93h10M38 98h5"), p.il5, 0.6f, 0.7f)
            fill(svg("M0 100V38C12 37 21 44 25 58C28 70 30 86 32 100z"), p.il4)
            fill(svg("M80 100V48C68 47 58 52 54 66C52 76 51 88 50 100z"), p.il1)
        }
        "sprouts" -> {
            rect(80f, 100f, p.tint)
            fill(svg("M0 54H80V100H0z"), p.il2)
            val rows = floatArrayOf(62f, 78f, 94f)
            for (r in 0 until 3) for (c in 0 until 4) {
                val x = (if (r % 2 == 1) 4 else 20) + c * 26
                if (x > 84) continue
                if (r == 1 && x == 30) continue
                sprout(x.toFloat(), rows[r], 0.8f, 4)
            }
            sprout(32f, 80f, 1.9f, 3)
        }
        "bloom" -> {
            rect(80f, 100f, p.tint)
            stroke(svg("M46 58C46 72 44 86 44 100"), p.il4, 1.4f)
            fill(svg("M45 82C36 78 31 72 31 65C40 65 44 71 45 82z"), p.il4)
            bloom(46f, 42f, 0f, 8f, 8.5f, 4.6f, 4.6f)
        }
        "mist" -> {
            rect(80f, 100f, p.tint)
            fill(hill(80f, 100f, 38f, 5f, 0.3f, 1f), p.il5)
            stipple(21, 80f, 100f, 38f, 5f, 0.3f, 1f, 140, 10f, p.il4, 0.4f, 1.1f, 1.8f)
            fill(hill(80f, 100f, 57f, 6f, 2f, 1.3f), p.il5)
            stipple(22, 80f, 100f, 57f, 6f, 2f, 1.3f, 200, 11f, p.il4, 0.65f, 1.1f, 1.8f)
            fill(hill(80f, 100f, 76f, 6f, 4.2f, 1.1f), p.il5)
            stipple(23, 80f, 100f, 76f, 6f, 4.2f, 1.1f, 260, 12f, p.il4, 0.95f, 1.1f, 1.8f)
        }
        "b1" -> {   // Thai banner
            rect(400f, 176f, p.tint); sun(322f, 62f, 30f)
            cloud(24f, 46f, 1.5f); cloud(118f, 30f, 1.9f); cloud(190f, 58f, 1.15f)
            fill(hill(400f, 176f, 106f, 9f, 0.5f, 1.5f), p.il3)
            stipple(31, 400f, 176f, 106f, 9f, 0.5f, 1.5f, 1100, 32f, p.il4, 0.55f, 1.2f, 1.9f)
            fill(hill(400f, 176f, 122f, 8f, 2.1f, 1.3f), p.il2)
            hatch(400f, 176f, 122f, 8f, 2.1f, 1.3f, 3.4f, p.il4, 0.7f, 0.38f, 16)
            fill(hill(400f, 176f, 152f, 7f, 4f, 1.1f), p.il4)
        }
        "b2" -> {   // Japanese banner
            rect(400f, 176f, p.tint); sun(300f, 88f, 32f)
            fill(hill(400f, 176f, 86f, 15f, 0.3f, 4.2f), p.il3)
            stipple(32, 400f, 176f, 86f, 15f, 0.3f, 4.2f, 700, 22f, p.il4, 0.5f, 1.2f, 1.9f)
            ellipse(150f, 104f, 170f, 7f, p.tint, 0.8f)
            fill(hill(400f, 176f, 100f, 12f, 1.7f, 3f), p.il2)
            ellipse(320f, 112f, 110f, 6f, p.tint, 0.75f)
            fill(hill(400f, 176f, 114f, 7f, 3.6f, 2f), p.il4)
            rect(400f, 54f, p.tint, y = 122f)
            rect(400f, 54f, p.il2, 0.32f, y = 122f)
            stroke(svg("M280 134h46M288 142h30M294 150h18"), p.il1, 1.6f, 0.6f)
            stroke(svg("M40 138h36M110 150h52M190 140h30M60 164h44M170 160h60M250 168h40M330 160h28"), p.il4, 1.2f, 0.3f)
        }
        "b3" -> {   // English banner, with the twinkling stars
            rect(400f, 176f, p.tint)
            val stars = listOf(floatArrayOf(40f, 30f, 1.6f), floatArrayOf(96f, 58f, 1.2f), floatArrayOf(150f, 22f, 1.8f),
                floatArrayOf(222f, 44f, 1.2f), floatArrayOf(352f, 20f, 1.4f), floatArrayOf(24f, 92f, 1.2f))
            stars.forEachIndexed { i, a -> circle(a[0], a[1], a[2] * 0.8f, p.il1, 0.5f * twinkle(i)) }
            sun(272f, 86f, 28f, p.il5)
            rect(400f, 68f, p.il2, y = 108f)
            stroke(svg("M170 120h34M232 128h58M190 140h26M260 148h44M150 160h50M320 120h30M330 164h34"), p.il5, 1.4f, 0.75f)
            fill(svg("M0 176V84C34 80 86 86 118 112C134 126 138 150 140 176z"), p.il4)
            fill(svg("M400 176V80C364 78 332 90 320 114C310 134 308 156 310 176z"), p.il1)
        }
        "m1" -> {
            roundBase()
            fill(hill(100f, 100f, 82f, 3f, 0.8f, 1f), p.il2)
            stroke(svg("M50 52C50 64 48 74 48 86"), p.il4, 1.5f)
            bloom(50f, 40f, 0f, 10f, 9f, 5f, 5f)
            sprout(22f, 84f, 0.8f, 4); sprout(76f, 86f, 0.8f, 4); sprout(88f, 82f, 0.6f, 4)
        }
        "m2" -> {
            roundBase(); sun(50f, 48f, 24f)
            fill(hill(100f, 100f, 74f, 4f, 1.4f, 1f), p.il3)
            fill(hill(100f, 100f, 88f, 4f, 3.2f, 1.1f), p.il4)
        }
        "m3" -> {
            roundBase()
            ellipse(50f, 80f, 24f, 6f, p.il4)
            sprout(50f, 74f, 2.4f, 1)
            circle(70f, 70f, 2f, p.il3)
        }
        "mj" -> {   // Japanese language mark: a blossoming branch
            val k = p.il4
            stroke(svg("M148 -2C124 14 100 22 78 40"), k, 3.6f, round = true)
            stroke(svg("M78 40C62 52 44 56 18 54"), k, 2.2f)
            stroke(svg("M112 17C108 30 106 40 100 52"), k, 1.5f); stroke(svg("M96 27C90 22 84 20 76 22"), k, 1.5f)
            stroke(svg("M66 47C62 58 60 66 52 74"), k, 1.5f); stroke(svg("M124 13C128 24 126 32 122 40"), k, 1.5f)
            stroke(svg("M120 20l-2 5M112 25l-2 5M104 30l-3 4M96 36l-3 4M88 41l-3 4M80 46l-3 3M70 50l-2 4M58 54l-1 4M44 56l0 4"), k, 0.5f, 0.7f)
            val r = Rng(9); val dots = ArrayList<Offset>()
            val cs = listOf(100f to 54f, 76f to 22f, 52f to 76f, 20f to 56f, 122f to 42f)
            for (i in 0 until 110) {
                val c = cs[i % 5]
                val x = c.first + (r.next() - 0.5) * 46; val y = c.second + (r.next() - 0.5) * 40
                if (x > 2 && x < 140 && y > 4 && y < 100) dots.add(Offset((x * 10).roundToInt() / 10f, (y * 10).roundToInt() / 10f))
            }
            d.drawPoints(dots, PointMode.Points, p.il1, 1.1f, StrokeCap.Round, alpha = 0.6f)
            blossom(100f, 54f, 12f, 10f); blossom(76f, 22f, 10f, 40f); blossom(52f, 76f, 11f, 25f); blossom(20f, 56f, 9f, 5f); blossom(122f, 42f, 9f, 50f)
            circle(112f, 17f, 2.2f, p.il1); circle(66f, 47f, 2f, p.il1); circle(40f, 55f, 1.8f, p.il1)
            rotatedOval(64f, 104f, 3.4f, 2f, 30f, 64f, 104f, p.il2, 0.8f)
            rotatedOval(90f, 116f, 3.4f, 2f, -20f, 90f, 116f, p.il2, 0.8f)
            rotatedOval(40f, 122f, 3f, 1.8f, 50f, 40f, 122f, p.il2, 0.8f)
        }
        "mt" -> {   // Thai language mark: a lotus on a pond
            stroke(svg("M4 126h54M70 130h40M92 124h36M20 134h30M96 136h34"), p.il4, 0.8f, 0.5f)
            ellipse(58f, 120f, 40f, 11f, p.il1, 0.9f)
            stroke(svg("M58 120L20 116M58 120L24 124M58 120L36 111M58 120L40 129M58 120L60 110M58 120L78 111M58 120L94 117M58 120L90 124"), p.il5, 0.6f, 0.8f)
            ellipse(132f, 126f, 14f, 5f, p.il1, 0.85f)
            stroke(svg("M112 134C108 114 110 94 110 80"), p.il4, 2.4f)
            stroke(svg("M128 122C128 112 130 106 134 100"), p.il4, 1.4f)
            ellipse(134f, 96f, 3.6f, 6f, p.il3)
            val petal = "M0 0C-11 -8 -12 -32 0 -46C12 -32 11 -8 0 0z"
            val back = listOf(-62f, 62f, -34f, 34f)
            for (i in 0 until 4) petalAt(petal, back[i], if (i < 2) 0.92f else 0.98f, p.il2, veins = false)
            for (i in -1..1) petalAt(petal, i * 15f, 1f, if (i != 0) p.il3 else p.il2, veins = true)
            circle(110f, 79f, 2.4f, p.il3)
        }
        "me" -> {   // English language mark: London skyline reflected in the river
            val stripes = listOf(119.4f to 1.7f, 122.6f to 1.5f, 126f to 1.3f, 129.6f to 1.1f, 133.6f to 0.9f, 138f to 0.7f)
            london(Rng(5), false)
            val clip = Path().apply { stripes.forEach { addRect(Rect(0f, it.first, 140f, it.first + it.second)) } }
            d.clipPath(clip) {
                // the reflection: the silhouette flipped about y=118, at half strength
                val cv = d.drawContext.canvas
                cv.saveLayer(Rect(0f, 0f, 140f, 140f), Paint().apply { alpha = 0.5f })
                withTransform({ translate(0f, 236f); scale(1f, -1f, Offset.Zero) }) { london(Rng(5), true) }
                cv.restore()
            }
            listOf(floatArrayOf(84f, 122f, 9f, 0f), floatArrayOf(96f, 125.6f, 6f, 1.1f), floatArrayOf(86f, 129f, 12f, 2.3f),
                floatArrayOf(100f, 133f, 5f, 0.6f), floatArrayOf(80f, 136.4f, 8f, 1.7f)).forEach { a ->
                roundRect(a[0], a[1], a[2], 0.9f, 0.45f, p.il3, twinkleAfter(a[3]))
            }
            fill(svg("M36 127.4h13l-2.6 3.2h-7.8z"), p.il4)
            stroke(svg("M42.6 127.4V112"), p.il4, 0.6f, round = false)
            fill(svg("M43.4 112.8L50 126.4h-6.6z"), p.il3, 0.95f)
            fill(svg("M41.8 116.4L36.6 126.4h5.2z"), p.il2)
            val wave = Path().apply {
                moveTo(-8f, 118f)
                var x = -8f
                while (x < 150f) { relativeQuadraticBezierTo(2f, -1.6f, 4f, 0f); relativeQuadraticBezierTo(2f, 1.6f, 4f, 0f); x += 8f }
            }
            stroke(wave, p.il1, 1.1f, 0.9f)
        }
        else -> scene("sunhills")   // unknown keys fall back to sunhills, like the design
    }
}

private fun Painter.roundBase() =
    d.drawRoundRect(p.tint, Offset.Zero, Size(100f, 100f), CornerRadius(16f))

// ---- helpers for the language marks (zbl5, zarch, zwg, zlond) ----

private fun Painter.box(x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float = 1f) =
    d.drawRect(color, Offset(x, y), Size(w, h), alpha)

/** zbl5: a five-petal blossom with fine veins and a centre. */
private fun Painter.blossom(x: Float, y: Float, r: Float, rot: Float) {
    val rx = ((r * 0.5f) * 10).roundToInt() / 10f; val ry = ((r * 0.62f) * 10).roundToInt() / 10f
    for (i in 0 until 5) rotatedOval(x, y - r * 0.55f, rx, ry, i * 72f + rot, x, y, p.il2, 0.85f, BlendMode.Multiply)
    val veins = Path()
    for (i in 0 until 5) {
        val a = Math.toRadians((i * 72 + rot + 36).toDouble())
        veins.moveTo(x, y); veins.relativeLineTo((sin(a) * r * 0.5).toFloat(), (-cos(a) * r * 0.5).toFloat())
    }
    stroke(veins, p.il1, 0.6f, 0.8f)
    circle(x, y, r * 0.2f, p.il3)
}

/** One lotus petal at the stem head (110, 80), turned by [deg] and scaled by [sc]. */
private fun Painter.petalAt(d0: String, deg: Float, sc: Float, fillColor: Color, veins: Boolean) {
    d.withTransform({ translate(110f, 80f); rotate(deg, Offset.Zero); scale(sc, sc, Offset.Zero) }) {
        drawPath(svg(d0), fillColor)
        drawPath(svg(d0), p.il4, 1f, Stroke(0.7f))
        if (veins) drawPath(svg("M0 -6V-34M-3 -12L-3 -26M3 -12L3 -26"), p.il4, 0.7f, Stroke(0.5f, cap = StrokeCap.Round))
    }
}

/** zarch: a small arched window. */
private fun Painter.arch(x: Float, y: Float, w: Float, h: Float, color: Color, o: Float = 1f) {
    val r = w / 2f
    val path = Path().apply {
        moveTo(x, y + h); lineTo(x, y + r)
        arcTo(Rect(x, y, x + w, y + w), 180f, 180f, false)
        lineTo(x + w, y + h); close()
    }
    fill(path, color, o)
}

/** zwg: a grid of lit and unlit windows. The random number is drawn for every cell, even in the silhouette, so both passes line up. */
private fun Painter.windows(r: Rng, x0: Double, x1: Double, y0: Double, y1: Double, sx: Double, sy: Double, sil: Boolean) {
    var y = y0
    while (y <= y1) {
        var x = x0
        while (x <= x1) {
            val q = r.next()
            if (!sil) arch(x.toFloat(), y.toFloat(), 1.8f, 3.6f, if (q < 0.17) p.il3 else p.il5, if (q < 0.17) 0.95f else 0.6f)
            x += sx
        }
        y += sy
    }
}

/** zlond: the London skyline in three layers (terrace and spire, domed cathedral, Westminster and the clock tower). [sil] draws a plain silhouette. */
private fun Painter.london(r: Rng, sil: Boolean) {
    val bl = 118f
    if (!sil) { circle(90f, 58f, 25f, p.il3, 0.3f); circle(90f, 58f, 15f, p.il3, 0.22f) }
    val terrace = listOf(floatArrayOf(2f, 12f, 30f), floatArrayOf(14f, 12f, 37f), floatArrayOf(26f, 12f, 33f), floatArrayOf(38f, 12f, 41f))
    // far layer: Georgian terrace and a church spire
    terrace.forEach { h ->
        val hx = h[0]; val w = h[1]; val t = bl - h[2]
        box(hx, t, w, h[2], p.il2)
        val roof = Path().apply { moveTo(hx - 0.8f, t); lineTo(hx + w / 2, t - 6); lineTo(hx + w + 0.8f, t); close() }
        fill(roof, p.il2)
        box(hx + w * 0.68f, t - 7, 2f, 5f, p.il2)
    }
    box(52f, 64f, 5f, 54f, p.il2)
    fill(svg("M51.2 64L54.5 38L57.8 64z"), p.il2)
    if (!sil) {
        terrace.forEach { h -> val t = bl - h[2]; windows(r, h[0] + 2.2, h[0] + 6.4, (t + 5).toDouble(), (bl - 12).toDouble(), 5.0, 8.0, false) }
        stroke(svg("M54.5 38V32M52.6 34.4h3.8"), p.il5, 0.5f, 0.6f, round = false)
    }
    // mid layer: the domed cathedral
    box(58f, 92f, 36f, 26f, p.il1); box(58f, 82f, 6f, 10f, p.il1); box(88f, 82f, 6f, 10f, p.il1)
    fill(svg("M57 82L61 76L65 82zM87 82L91 76L95 82z"), p.il1)
    box(67f, 82f, 18f, 10f, p.il1)
    fill(svg("M67.4 82C67.4 66 84.6 66 84.6 82z"), p.il1)
    box(75f, 62f, 2f, 7f, p.il1); circle(76f, 60.6f, 1.5f, p.il1)
    fill(svg("M68 92L76 85L84 92z"), p.il1)
    stroke(svg("M76 59V53M73.8 55.4h4.4"), p.il1, 0.9f)
    if (!sil) {
        val cols = Path(); var x = 62f
        while (x <= 90f) { cols.moveTo(x, 96f); cols.lineTo(x, 117f); x += 3f }
        stroke(cols, p.il5, 0.55f, 0.55f, round = false)
        stroke(svg("M58 94h36M69 87h14"), p.il5, 0.5f, 0.5f, round = false)
        arch(60.2f, 84f, 1.6f, 3.6f, p.il5, 0.6f); arch(89.2f, 84f, 1.6f, 3.6f, p.il5, 0.6f); arch(75.1f, 76.5f, 1.8f, 3.6f, p.il3, 0.9f)
    }
    // near layer: the Palace of Westminster and the clock tower
    box(92f, 92f, 13f, 26f, p.il4); box(116f, 84f, 24f, 34f, p.il4); box(130f, 75f, 6f, 10f, p.il4)
    fill(svg("M129 75L133 66L137 75z"), p.il4)
    var x = 93f
    while (x < 103f) { fill(svg("M$x 92l1.1 -3.8l1.1 3.8z"), p.il4); x += 3.4f }
    x = 117f
    while (x < 139f) { fill(svg("M$x 84l1.2 -4l1.2 4z"), p.il4); x += 4.2f }
    box(104.3f, 50f, 11.4f, 68f, p.il4); box(102.6f, 34f, 14.8f, 18f, p.il4); box(104f, 24f, 12f, 10f, p.il4)
    fill(svg("M104 24Q108.6 22 110 9Q111.4 22 116 24z"), p.il4)
    fill(svg("M102.6 34L104 28.4L105.4 34zM114.6 34L116 28.4L117.4 34z"), p.il4)
    roundRect(108.9f, 3.6f, 2.2f, 2.2f, 1.1f, p.il3)
    stroke(svg("M110 9V5.8"), p.il4, 0.9f)
    if (!sil) {
        circle(110f, 43f, 5.4f, p.il5)
        stroke(svg("M110 43V39.4M110 43l2.6 1.4"), p.il4, 0.8f)
        stroke(svg("M110 38.2v.9M110 47v.9M105.2 43h.9M113.9 43h.9"), p.il4, 0.5f)
        box(105.4f, 26.4f, 1.4f, 6f, p.il5, 0.7f); box(109.3f, 26.4f, 1.4f, 6f, p.il5, 0.7f); box(113.2f, 26.4f, 1.4f, 6f, p.il5, 0.7f)
        stroke(svg("M104.3 70h11.4M104.3 92h11.4M107.6 52V118M112.4 52V118"), p.il5, 0.5f, 0.5f, round = false)
        var i = 58
        while (i < 112) {
            arch(105.9f, i.toFloat(), 1.8f, 3.6f, if (i == 76) p.il3 else p.il5, if (i == 76) 0.95f else 0.62f)
            arch(112.3f, i.toFloat(), 1.8f, 3.6f, p.il5, 0.62f)
            i += 9
        }
        windows(r, 94.2, 101.0, 96.0, 112.0, 3.4, 6.0, false)
        windows(r, 118.5, 137.0, 90.0, 112.0, 4.2, 6.0, false)
        arch(131.1f, 77.5f, 1.8f, 3.6f, p.il5, 0.7f)
    }
}


// ---- grain (the SVG feTurbulence filters) ----

private object Grain {
    /** Speckle for the covers: grain-filter alpha = 1.5 * noise - .84 (the small size, -.93, is sparser). */
    val cover: Shader by lazy { tile(128, 3, 1.5f, -0.84f, 56, 38, 26) }
    val coverSmall: Shader by lazy { tile(128, 3, 1.5f, -0.93f, 56, 38, 26) }
    /** Whole-screen overlay: alpha = 1.6 * noise - .3, drawn at 4.5% opacity. */
    val screen: Shader by lazy { tile(180, 11, 1.6f, -0.3f, 64, 43, 26) }

    /** The filters' noise cells are smaller than a pixel, so on screen they read as per-pixel noise. A seeded Gaussian stands in for it. */
    private fun tile(size: Int, seed: Long, gain: Float, offset: Float, r: Int, g: Int, b: Int): Shader {
        val rnd = java.util.Random(seed)
        val px = IntArray(size * size)
        for (i in px.indices) {
            val n = (0.5f + 0.17f * rnd.nextGaussian().toFloat()).coerceIn(0f, 1f)
            val a = (gain * n + offset).coerceIn(0f, 1f)
            px[i] = ((a * 255f).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        return ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
    }
}

/** Whole-app paper grain (the design's .app::after at 4.5% opacity). Place it last in a Box over the app; it never takes touches. */
@Composable
fun GrainOverlay() {
    val brush = remember { ShaderBrush(Grain.screen) }
    Canvas(Modifier.fillMaxSize()) { drawRect(brush, alpha = 0.045f) }
}

// ---- public composables ----

/** True when motion should be skipped: the app's Reduce motion switch, or the system animation scale set to 0. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    val system = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    return ArtPrefs.reduceMotion || system
}

/**
 * One illustration. [key] is a design scene name: covers (terraces, sunhills, leaf, moon, coast, sprouts, bloom, mist),
 * banners (b1 Thai, b2 Japanese, b3 English) or motifs (m1, m2, m3). [small] uses the sparser grain of the thumbnails.
 * The picture is cropped to fill its box (xMidYMid slice) and takes its inks from the language being learned.
 */
@Composable
fun Art(key: String, modifier: Modifier = Modifier, small: Boolean = false, corner: Dp = 0.dp) {
    val pal = ArtPalettes.of(Fluvour.lang)
    val shape = RoundedCornerShape(corner)
    val name = canon(key)
    val still = rememberReduceMotion()
    val clock: State<Float> = if (isLangKey(name) && still) remember { mutableStateOf(-1f) }   // still frame
    else if ((name == "b3" || name == "me" || isLangKey(name)) && !still) {
        val t = rememberInfiniteTransition(label = "twinkle")
        t.animateFloat(0f, 3000f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "tw")
    } else remember { mutableStateOf(0f) }

    Canvas(modifier.clip(shape)) { paintArt(name, pal, clock.value, size, small) }
}

/** Draws scene [key] into a box of [box] size at the current origin: cropped to fill (xMidYMid slice), then the grain over it. */
internal fun DrawScope.paintArt(key: String, pal: ArtPalette, clockMs: Float, box: Size, small: Boolean) {
    if (isLangKey(key)) {   // zh, ko, es, fr, de, vi, id: data-driven scenes from LangArt.kt
        paintLangScene(key, pal, clockMs, box)
        drawRect(ShaderBrush(if (small) Grain.coverSmall else Grain.cover), Offset.Zero, box)
        return
    }
    val (vw, vh) = viewBoxOf(key)
    val s = max(box.width / vw, box.height / vh)             // preserveAspectRatio="xMidYMid slice"
    val dx = (box.width - vw * s) / 2f; val dy = (box.height - vh * s) / 2f
    withTransform({ translate(dx, dy); scale(s, s, Offset.Zero) }) { Painter(this, pal, clockMs).scene(key) }
    drawRect(ShaderBrush(if (small) Grain.coverSmall else Grain.cover), Offset.Zero, box)   // the grain filter, in screen pixels
}

/** Banner for the Watch tab: one scene per language (stage() in the design). */
fun stageKey(lang: String): String = when {
    LangArt.has(lang) -> "lxs:$lang"      // mark banner with the ambient layer over it
    lang == "th" -> "b1"; lang == "en" -> "b3"; else -> "b2"
}

/** Home's "next up" cover: for languages with their own art it is the mark, cropped (lxc:). */
fun coverKeyFor(lang: String, route: String): String = if (LangArt.has(lang)) "lxc:$lang" else coverKey(route)

/** Cover for each "next up" and Today item (AK in the design). */
fun coverKey(route: String): String = when (route) { "cards" -> "terraces"; "read" -> "leaf"; "speak" -> "coast"; "watch" -> "moon"; else -> "sunhills" }

/** Thumbnails for the Output tab (CALLS and TA in the design). */
val callArt = listOf("terraces", "leaf", "moon", "coast")
val topicArt = listOf("sunhills", "sprouts", "mist", "bloom")

/**
 * Faint pattern behind a screen title (zpt in the design). 1 = stipple mist (Home), 2 = terrace lines (Read).
 * It is drawn behind the content, 240dp tall, at 12% opacity and fading out downward. [bleed] extends it past side padding.
 */
@Composable
fun Modifier.artPattern(n: Int, bleed: Dp = 0.dp): Modifier {
    if (!ArtPrefs.enabled) return this
    val ink = ArtPalettes.of(Fluvour.lang).il1
    val ids = if (n == 1) 1 else 2
    return this.then(
        Modifier.drawWithCache {
            val unit = density                                   // the pattern tile is 120 css px
            val tile = patternTile(ids)
            onDrawBehind {
                val bleedPx = bleed.toPx()
                val w = size.width + bleedPx * 2
                val h = 240f * unit
                // A layer sized to the pattern (not to this node), so the fade can be cut out of it without clipping to the node.
                val canvas = drawContext.canvas
                canvas.saveLayer(Rect(-bleedPx, 0f, size.width + bleedPx, h), Paint())
                translate(-bleedPx, 0f) {
                    withTransform({ scale(unit, unit, Offset.Zero) }) {
                        var ty = 0f
                        while (ty * unit < h) {
                            var tx = 0f
                            while (tx * unit < w) {
                                translate(tx, ty) {
                                    if (ids == 1) drawPoints(tile.dots, PointMode.Points, ink, 1.4f, StrokeCap.Round, alpha = 0.12f)
                                    else tile.lines.forEach { drawPath(it, ink, 0.12f, Stroke(0.6f)) }
                                }
                                tx += 120f
                            }
                            ty += 120f
                        }
                    }
                    drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), 0f, h), Offset.Zero, Size(w, h), blendMode = BlendMode.DstIn)
                }
                canvas.restore()
            }
        }
    )
}

private class PatternTile(val dots: List<Offset>, val lines: List<Path>)
private val patternTiles = HashMap<Int, PatternTile>()

private fun patternTile(n: Int): PatternTile = patternTiles.getOrPut(n) {
    if (n == 1) {
        val r = Rng(5)
        val dots = ArrayList<Offset>()
        for (i in 0 until 420) {
            val x = 2 + r.next() * 116; val y = 2 + r.next() * 116
            if (r.next() < (cos(y / 120 * PI * 2) + 1) / 2 * 0.9 + 0.04) dots.add(Offset(x.toFloat(), y.toFloat()))
        }
        PatternTile(dots, emptyList())
    } else {
        val ys = listOf(4, 12, 20, 28, 36, 46, 56, 66, 76, 86, 99, 112)
        val lines = ys.mapIndexed { j, y0 ->
            val path = Path()
            var a = 0
            while (a <= 120) {
                val y = (y0 + 2.4 * sin(a / 120.0 * PI * 2 + j)).toFloat()
                if (a == 0) path.moveTo(a.toFloat(), y) else path.lineTo(a.toFloat(), y)
                a += 6
            }
            path
        }
        PatternTile(emptyList(), lines)
    }
}
