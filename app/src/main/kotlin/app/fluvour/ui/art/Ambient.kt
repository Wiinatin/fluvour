package app.fluvour.ui.art

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fluvour.ui.theme.Fluvour
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The design's ambient background (fx, flkSvg and their CSS) and the faint per-language mark (lm).
 *   Thai     : slow water ripples and falling drops
 *   Japanese : nine drifting blossoms
 *   English  : two flocks of birds that fly by simple flocking rules, over faint sliding wave lines
 * Everything lives behind the screen content, in the top 420dp, fading out downward.
 * Switched off by Settings > Illustrations (the design's .noart) and by Reduce motion (.rm).
 */

private val rippleEase = CubicBezierEasing(0.2f, 0.6f, 0.3f, 1f)
private val inOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

internal fun family(lang: String): String = when (lang) {
    "th" -> "th"
    "en" -> "en"
    "zh", "ko" -> "ja"   // only used by the old art; LangArt takes over for these
    "es", "fr", "de", "vi", "id" -> "en"
    else -> "ja"
}

// ---------------------------------------------------------------- the flock

private class Bird(
    var x: Double, var y: Double, var vx: Double, var vy: Double, var ph: Double,
    var am: Double, var fl: Int, var ft: Double, val hz: Double, val z: Double,
) { var sx = 1; var an = 0.0 }

private class Flk(val s: Double, val v: Double, val o: Double, var gx: Double, var gy: Double, var gt: Double, val b: List<Bird>)

/** Where the birds may roam, per variant (FBS): eh = Home, eo = every other screen (smaller and higher). */
private class Bounds(val x0: Double, val x1: Double, val y0: Double, val y1: Double, val sc: Double)

/** The flock simulation. One flock is shared by all screens, so it keeps flying as you move between tabs. */
internal object Flock {
    private const val FW = 390.0
    private const val FH = 200.0
    private val eh = Bounds(178.0, 440.0, 42.0, 120.0, 1.0)
    private val eo = Bounds(120.0, 273.0, 16.0, 68.0, 0.72)
    private var fb = eh
    private var flocks: List<Flk>? = null

    private const val BU = "M1.5 -1.2C1 -5 -.5 -8 -3 -10L-5.4 -9L-5 -7.6L-6.4 -6.6L-5.4 -5.2L-6 -3.8L-4 -1z"
    private const val BM = "M1.5 -1C-1 -3 -5 -4 -9.4 -3.4L-9 -2.2L-7.6 -2.4L-7.8 -1.2L-6 -1.6L-6 -.4L-3 -.2z"
    private const val BD = "M1.5 -.6C1.4 3 .4 6 -2.6 8L-4.2 6.8L-3.4 6.2L-4.6 5L-3.4 4.4L-4 3L-3 .8z"
    private const val BB = "M-7.6 1L-3.6 -.2Q-1 -1.7 2.5 -1.5Q5 -2.1 6 -.7L8.8 .3L6.2 .9Q5 2.5 2 2.3Q-1 2.7 -3.4 1.3L-7.6 2.5z"
    private val numRe = Regex("-?\\d*\\.?\\d+")
    private val nu = numRe.findAll(BU).map { it.value.toDouble() }.toList()
    private val nm = numRe.findAll(BM).map { it.value.toDouble() }.toList()
    private val nd = numRe.findAll(BD).map { it.value.toDouble() }.toList()
    private val body: Path by lazy { PathParser().parsePathString(BB).toPath() }

    fun useBounds(home: Boolean) { fb = if (home) eh else eo }

    private fun init(): List<Flk> {
        val r = Rng(23)
        data class Spec(val n: Int, val s: Double, val v: Double, val x: Double, val y: Double, val o: Double)
        return listOf(Spec(7, 0.56, 30.0, 300.0, 70.0, 0.7), Spec(15, 0.86, 44.0, 230.0, 62.0, 1.0)).map { f ->
            val gt = 4 + r.next() * 4
            val birds = ArrayList<Bird>()
            for (i in 0 until f.n) {
                val a = r.next() * 6.283; val d = r.next() * 30
                birds.add(Bird(
                    x = f.x + cos(a) * d, y = f.y + sin(a) * d * 0.55,
                    vx = f.v * 0.85 + (r.next() - 0.5) * 8, vy = (r.next() - 0.5) * 8,
                    ph = r.next() * 6.283, am = if (r.next() < 0.6) 1.0 else 0.0, fl = if (r.next() < 0.6) 1 else 0,
                    ft = r.next() * 2, hz = 3 + r.next() * 1.1, z = 0.88 + r.next() * 0.24,
                ))
            }
            Flk(f.s, f.v, f.o, f.x, f.y, gt, birds)
        }
    }

    fun step(dt: Double) {
        if (dt <= 0.0) return
        val all = flocks ?: init().also { flocks = it }
        for (fl in all) {
            val bs = fl.b; val mx = fl.v; val mn = mx * 0.55; val fm = mx * 1.7
            var kx = 0.0; var ky = 0.0
            for (b in bs) { kx += b.x; ky += b.y }
            kx /= bs.size; ky /= bs.size
            fl.gt -= dt
            if (abs(kx - fl.gx) < 38 && abs(ky - fl.gy) < 38 && fl.gt > 2) fl.gt = 2.0
            if (fl.gt <= 0) {
                fl.gt = 5 + Random.nextDouble() * 6
                fl.gx = if (Random.nextDouble() < 0.1 && fb.x1 > FW) FW + 45 else fb.x0 + 30 + Random.nextDouble() * (FW - fb.x0 - 60)
                fl.gy = fb.y0 + 10 + Random.nextDouble() * (fb.y1 - fb.y0 - 20)
            }
            for ((i, b) in bs.withIndex()) {
                var ax = 0.0; var ay = 0.0; var cx = 0.0; var cy = 0.0; var sx = 0.0; var sy = 0.0; var ss = 0.0; var m = 0
                var fx = 0.0; var fy = 0.0
                val nb = ArrayList<Pair<Double, Bird>>()
                for (j in bs.indices) {
                    if (j == i) continue
                    val o = bs[j]; val dx = o.x - b.x; val dy = o.y - b.y
                    nb.add((dx * dx + dy * dy) to o)
                }
                nb.sortBy { it.first }
                for (e in nb.take(7)) {
                    val d = sqrt(e.first).let { if (it == 0.0) 1.0 else it }
                    val q = e.second
                    if (d < 14) { val w = (14 - d) / 14 / d; sx -= (q.x - b.x) * w; sy -= (q.y - b.y) * w }
                    ax += q.vx; ay += q.vy; cx += q.x; cy += q.y; ss += sin(q.ph - b.ph); m++
                }
                if (m > 0) {
                    ax /= m; ay /= m; cx = cx / m - b.x; cy = cy / m - b.y
                    fx += sx * mx * 3.4 + (ax - b.vx) * 1.5 + cx * 0.5
                    fy += sy * mx * 3.4 + (ay - b.vy) * 1.5 + cy * 0.5
                }
                val gx = fl.gx - b.x; val gy = fl.gy - b.y
                val gd = sqrt(gx * gx + gy * gy).let { if (it == 0.0) 1.0 else it }
                fx += gx / gd * mx * 0.85; fy += gy / gd * mx * 0.85
                var sp = sqrt(b.vx * b.vx + b.vy * b.vy).let { if (it == 0.0) 1.0 else it }
                fx += b.vx / sp * (mx * 0.88 - sp) * 1.6 + (Random.nextDouble() - 0.5) * 8
                fy += b.vy / sp * (mx * 0.88 - sp) * 1.6 + (Random.nextDouble() - 0.5) * 8 - b.vy * 0.9
                if (b.y < fb.y0) fy += (fb.y0 - b.y) * 6
                if (b.y > fb.y1) fy -= (b.y - fb.y1) * 6
                if (b.x < fb.x0) fx += (fb.x0 - b.x) * 5
                if (b.x > fb.x1) fx -= (b.x - fb.x1) * 5
                val f = sqrt(fx * fx + fy * fy)
                if (f > fm) { fx *= fm / f; fy *= fm / f }
                b.vx += fx * dt; b.vy += fy * dt
                sp = sqrt(b.vx * b.vx + b.vy * b.vy).let { if (it == 0.0) 1.0 else it }
                if (sp > mx) { b.vx *= mx / sp; b.vy *= mx / sp; sp = mx } else if (sp < mn) { b.vx *= mn / sp; b.vy *= mn / sp; sp = mn }
                b.x += b.vx * dt; b.y += b.vy * dt
                val cl = -b.vy / mx
                b.ft -= dt
                if (b.ft <= 0) { b.fl = if (b.fl != 0) 0 else 1; b.ft = if (b.fl != 0) 1.2 + Random.nextDouble() * 1.8 else 0.7 + Random.nextDouble() * 1.4 }
                b.am += (if ((b.fl != 0 || cl > 0.28) && !(cl < -0.3)) 1 - b.am else -b.am) * min(1.0, dt * 4)
                b.ph += dt * (6.283 * b.hz * (1 + 0.3 * cl) + (if (m > 0) 1.6 * ss / m else 0.0))
                if (b.sx > 0 && b.vx < -0.2 * sp) b.sx = -1 else if (b.sx < 0 && b.vx > 0.2 * sp) b.sx = 1
                b.an = if (b.sx > 0) atan2(b.vy, b.vx) else atan2(-b.vy, -b.vx)
            }
        }
    }

    /** The wing between its mid pose and fully up (p>0) or fully down (p<0). Same number-by-number blend as bwing(). */
    private fun wing(p: Double): Path {
        val a = if (p >= 0) nu else nd; val t = abs(p); var i = 0
        val s = numRe.replace(BM) { String.format(Locale.US, "%.1f", nm[i] + (a[i] - nm[i]) * t).also { i++ } }
        return PathParser().parsePathString(s).toPath()
    }

    private fun pose(b: Bird) = b.am * (sin(b.ph) + 0.22 * sin(2 * b.ph)) * 0.92 + (1 - b.am) * 0.3

    /** Draws the flocks into a [w] x [h] area (in dp, which the caller has already scaled to). The art is 390x200, fitted at the top and centred. */
    fun draw(d: DrawScope, ink: Color, w: Float, h: Float) {
        val all = flocks ?: return
        val k = min(w / FW.toFloat(), h / FH.toFloat())
        val cv = d.drawContext.canvas
        val bounds = Rect(0f, 0f, w, h)
        cv.saveLayer(bounds, Paint().apply { alpha = 0.32f })          // .amb .flk { opacity: .32 }
        d.withTransform({ translate((w - FW.toFloat() * k) / 2f, 0f); scale(k, k, Offset.Zero) }) {
            for (fl in all) {
                cv.saveLayer(Rect(-200f, -200f, 700f, 400f), Paint().apply { alpha = fl.o.toFloat() })
                for (b in fl.b) {
                    val bob = b.am * sin(b.ph + 1.3) * 0.5
                    val s = fl.s * b.z * fb.sc
                    withTransform({
                        translate(b.x.toFloat(), (b.y + bob * s).toFloat())
                        rotate((b.an * 57.296).toFloat(), Offset.Zero)
                        scale((b.sx * s).toFloat(), s.toFloat(), Offset.Zero)
                    }) {
                        drawPath(wing(pose(b)), ink)
                        drawPath(body, ink)
                    }
                }
                cv.restore()
            }
        }
        cv.restore()
    }
}

// ---------------------------------------------------------------- Thai ripples, Japanese petals, English streaks

/** wob: a ring that is not quite an ellipse (48 points, two slow wobbles), scaled about its centre. */
private fun wobPath(cx: Float, cy: Float, rx: Float, ry: Float, ph: Float, scale: Float): Path {
    val path = Path()
    for (i in 0 until 48) {
        val a = i / 48.0 * PI * 2
        val k = 1 + 0.035 * sin(3 * a + ph) + 0.025 * sin(5 * a + ph * 2.3)
        val x = cx + rx * scale * k * cos(a); val y = cy + ry * scale * k * sin(a)
        if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
    }
    path.close()
    return path
}

private fun DrawScope.ringStroke(g: Path, ink: Color, alpha: Float, hlX: Float, hlY: Float) {
    if (alpha <= 0.001f) return
    translate(hlX, hlY) { drawPath(g, Color(0xFFFFFDF8), alpha, Stroke(1.8f)) }   // the pale highlight, offset down and right
    drawPath(g, ink, alpha, Stroke(1f))
}

private fun DrawScope.thaiRipples(pal: ArtPalette, ms: Float, home: Boolean) {
    val t = ms / 1000f
    val centres = if (!home) listOf(328f to 93f, 391f to 98f) else listOf(322f to 168f, 389f to 173f)
    // big rings: rpl 8s, scale .28 -> 1, opacity .17 -> .07 -> 0
    centres.forEachIndexed { k, c ->
        for (i in 0 until (if (k > 0) 2 else 3)) {
            val delay = i * 2.7f + k * 1.4f
            if (t < delay) continue
            val u = ((t - delay) % 8f) / 8f
            val e = rippleEase.transform(u)
            val sc = lerp(0.28f, 1f, e)
            val op = if (u < 0.55f) lerp(0.17f, 0.07f, rippleEase.transform(u / 0.55f)) else lerp(0.07f, 0f, rippleEase.transform((u - 0.55f) / 0.45f))
            val sd = k * 3 + i
            ringStroke(wobPath(c.first, c.second, 120f, 36f, sd * 1.3f + 0.4f, sc), pal.il1, op, 1.2f, 1.4f)
        }
    }
    // drops: a short line falls, then a small ring opens where it lands
    val r0 = centres[0]
    val drops = listOf(floatArrayOf(-48f, -16f, 0f), floatArrayOf(24f, 12f, 1.7f), floatArrayOf(-8f, 26f, 3.1f),
        floatArrayOf(56f, -20f, 4.4f), floatArrayOf(-64f, 14f, 5.2f), floatArrayOf(8f, -28f, 6.3f))
    drops.forEachIndexed { k, dr ->
        val x = r0.first + dr[0]; val y = r0.second + dr[1]
        val dur = 6f + (k * 0.7f) % 2.5f
        val u = (((t + dr[2]) % dur) + dur) % dur / dur
        // ring (ipl): hidden until 8%, opens to full size by the end
        val sc = if (u < 0.08f) 0.12f else lerp(0.12f, 1f, rippleEase.transform((u - 0.08f) / 0.92f))
        val op = when {
            u < 0.08f -> 0f
            u < 0.10f -> lerp(0f, 0.2f, rippleEase.transform((u - 0.08f) / 0.02f))
            else -> lerp(0.2f, 0f, rippleEase.transform((u - 0.10f) / 0.90f))
        }
        ringStroke(wobPath(x, y, 26f, 8f, k.toFloat(), sc), pal.il1, op, 0.8f, 1f)
        // the falling line (drp): moves 62 in the first 8% of the cycle
        val ty = if (u < 0.08f) 62f * (u / 0.08f) else 62f
        val lo = when {
            u < 0.01f -> 0.3f * (u / 0.01f)
            u < 0.08f -> 0.3f
            u < 0.09f -> 0.3f * (1 - (u - 0.08f) / 0.01f)
            else -> 0f
        }
        if (lo > 0.001f) drawLine(pal.il1, Offset(x, y - 72 + ty), Offset(x, y - 60 + ty), 1f, StrokeCap.Round, alpha = lo)
    }
}

private class PetalSpec(val left: Float, val size: Float, val fall: Float, val fallOff: Float, val swayDur: Float, val swayOff: Float)

private val petals: List<PetalSpec> by lazy {
    val r = Rng(17)
    (0 until 9).map {
        val d = 14 + r.next() * 9; val sz = 14 + r.next() * 10
        val left = Math.round(4 + r.next() * 90).toFloat()
        val off = (Math.round(r.next() * d * 10) / 10.0)
        val sd = Math.round((3 + r.next() * 3) * 10) / 10.0
        val so = Math.round(r.next() * 4 * 10) / 10.0
        PetalSpec(left, Math.round(sz).toFloat(), (Math.round(d * 10) / 10.0).toFloat(), off.toFloat(), sd.toFloat(), so.toFloat())
    }
}

private fun DrawScope.japanPetals(pal: ArtPalette, ms: Float, w: Float) {
    val t = ms / 1000f
    val cv = drawContext.canvas
    for (p in petals) {
        val fall = (((t + p.fallOff) % p.fall) + p.fall) % p.fall / p.fall
        val y = -30f + 460f * fall
        // sway: alternates between two poses, eased both ways
        val cyc = (((t + p.swayOff) % (p.swayDur * 2)) + p.swayDur * 2) % (p.swayDur * 2) / p.swayDur
        val s = inOut.transform(if (cyc <= 1f) cyc else 2f - cyc)
        val cx = w * p.left / 100f + p.size / 2f + lerp(-14f, 14f, s)
        val cy = y + p.size / 2f
        val k = p.size / 12f
        cv.saveLayer(Rect(cx - p.size, cy - p.size, cx + p.size, cy + p.size), Paint().apply { alpha = 0.3f })
        withTransform({ translate(cx, cy); rotate(lerp(-40f, 50f, s), Offset.Zero); scale(k, k, Offset.Zero) }) {
            for (a in listOf(0f, 72f, 144f, 216f, 288f)) rotate(a, Offset.Zero) {
                drawOval(pal.il2, Offset(-2.3f, -2.6f - 3f), Size(4.6f, 6f))
            }
            drawCircle(pal.il3, 1f, Offset.Zero)
        }
        cv.restore()
    }
}

/** The six sliding wave lines under the birds (SR in the design): y, height, wavelength, seconds per loop, opacity, width. */
private val streaks = listOf(
    floatArrayOf(6f, 2.2f, 36f, 9f, 0.16f, 1.1f), floatArrayOf(17f, 2f, 30f, 13f, 0.11f, 1f), floatArrayOf(29f, 1.8f, 40f, 11f, 0.075f, 1f),
    floatArrayOf(43f, 1.6f, 32f, 16f, 0.045f, 0.9f), floatArrayOf(59f, 1.4f, 44f, 14f, 0.025f, 0.9f), floatArrayOf(77f, 1.2f, 36f, 19f, 0.012f, 0.8f),
)

private fun DrawScope.englishStreaks(pal: ArtPalette, ms: Float, w: Float, home: Boolean) {
    val t = ms / 1000f
    val k = if (home) 0.5f else 0.3f
    val top = (if (home) 137.6f else 83.8f) - 6 * k
    val hh = 96 * k
    val mx = if (home) 0.88f else 0.6f
    val sx = w / 390f
    val cv = drawContext.canvas
    cv.saveLayer(Rect(0f, top, w, top + hh), Paint())
    streaks.forEachIndexed { j, a ->
        val per = a[2]; val amp = a[1]
        val path = Path()
        path.moveTo(-per * sx, a[0] * k)
        var x = -per
        while (x < 390 + per) {
            path.relativeQuadraticBezierTo(per / 4 * sx, -amp * k, per / 2 * sx, 0f)
            path.relativeQuadraticBezierTo(per / 4 * sx, amp * k, per / 2 * sx, 0f)
            x += per
        }
        val frac = (t % a[3]) / a[3]
        val shift = (if (j % 2 == 1) -per else per) * frac * sx
        translate(shift, top) { drawPath(path, pal.il1, a[4], Stroke(a[5], cap = StrokeCap.Round)) }
    }
    // mask: radial, widest at the top, centred at 88% (Home) or 60% across
    val cx = w * mx
    withTransform({ translate(cx, top); scale(w * 0.95f, hh, Offset.Zero) }) {
        drawRect(
            Brush.radialGradient(0.10f to Color.Black, 0.85f to Color.Transparent, center = Offset.Zero, radius = 1f),
            Offset(-cx / (w * 0.95f) - 2f, -1f), Size(w / (w * 0.95f) + 4f, 4f), blendMode = BlendMode.DstIn,
        )
    }
    cv.restore()
}

// ---------------------------------------------------------------- the public modifiers

/** A clock in milliseconds that ticks with every frame, and steps the flock when [flock] is set. Only the draw phase reads it. */
@Composable
private fun rememberAmbientClock(flock: Boolean, home: Boolean, running: Boolean = true): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(flock, home, running) {
        if (!running) return@LaunchedEffect
        if (flock) Flock.useBounds(home)
        var last = 0L
        val t0 = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { n ->
                if (flock) Flock.step(if (last == 0L) 0.0 else min(0.05, (n - last) / 1e9))
                last = n
                t.floatValue = ((n - t0) / 1_000_000L).toFloat()
            }
        }
    }
    return t
}

/**
 * The ambient background behind a screen: ripples (Thai), petals (Japanese), birds and wave lines (English).
 * [v] picks the layout for Thai and Japanese ("r" is the Read tab), [e] for English ("eh" Home, "eo" everywhere else).
 * Put it right after the scroll modifier. [bleed] extends it over side padding so it spans the screen.
 */
@Composable
fun Modifier.ambient(v: String = "", e: String = "eo", bleed: Dp = 0.dp): Modifier {
    val reduce = rememberReduceMotion()
    val pal = ArtPalettes.of(Fluvour.lang)
    val fam = family(Fluvour.lang)
    val langCode = Fluvour.lang
    val lx = LangArt.has(langCode)
    val variant = if (fam == "en") e else v
    val clock = rememberAmbientClock(fam == "en" && !lx, variant == "eh", ArtPrefs.enabled && !reduce)
    if (!ArtPrefs.enabled || reduce) return this
    return this.then(Modifier.drawBehind {
        val ms = clock.value
        val d = density
        val bleedPx = bleed.toPx()
        val wDp = (size.width + 2 * bleedPx) / d
        val cv = drawContext.canvas
        val layer = Rect(-bleedPx, 0f, size.width + bleedPx, 420f * d)
        cv.saveLayer(layer, Paint())
        clipRect(layer.left, layer.top, layer.right, layer.bottom) {
            translate(-bleedPx, 0f) {
                withTransform({ scale(d, d, Offset.Zero) }) {
                    when {
                        lx -> paintLangAmbient(langCode, pal, ms, wDp)
                        fam == "th" -> translate(wDp - 390f, 0f) { thaiRipples(pal, ms, variant != "r") }
                        fam == "ja" -> japanPetals(pal, ms, wDp)
                        else -> { Flock.draw(this, pal.il4, wDp, 420f); englishStreaks(pal, ms, wDp, variant == "eh") }
                    }
                }
            }
        }
        // fade out downward: opaque for the top half, clear at 420dp
        drawRect(Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent, startY = 0f, endY = 420f * d),
            Offset(layer.left, 0f), Size(layer.width, 420f * d), blendMode = BlendMode.DstIn)
        cv.restore()
    })
}

/** Where each language-mark variant sits (the .lm rules): top, right inset, size, opacity. */
private class MarkSpec(val top: Float, val right: Float, val size: Float, val opacity: Float)

private fun markSpec(v: String) = when (v) {
    "lmh" -> MarkSpec(28f, -8f, 130f, 0.2f)
    "lmo" -> MarkSpec(13f, 118f, 84f, 0.24f)
    "lmr" -> MarkSpec(-10f, -8f, 120f, 0.14f)
    else -> MarkSpec(56f, -8f, 130f, 0.14f)
}

/** Draws one language mark ("mj", "mt" or "me") into the square at ([x],[y]) of [side] px, at [opacity], faded toward the left from [solidFrom]. */
private fun DrawScope.paintMark(pal: ArtPalette, ms: Float, key: String, x: Float, y: Float, side: Float, opacity: Float, solidFrom: Float) {
    val cv = drawContext.canvas
    cv.saveLayer(Rect(x, y, x + side, y + side), Paint().apply { alpha = opacity })
    translate(x, y) { paintArt(key, pal, ms, Size(side, side), small = false) }
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, solidFrom to Color.Black, 1f to Color.Black, startX = x, endX = x + side),
        Offset(x, y), Size(side, side), blendMode = BlendMode.DstIn,
    )
    cv.restore()
}

private fun markKey(lang: String) = if (LangArt.has(lang)) "lx:$lang" else when (family(lang)) { "th" -> "mt"; "en" -> "me"; else -> "mj" }

/**
 * The faint language mark in a screen's top corner (a branch for Japanese, a lotus for Thai, the skyline for English).
 * [v] is the layout for Thai and Japanese ("" default, "lmr" the Read tab), [e] the layout for English ("lmh" Home, "lmo" the rest).
 */
@Composable
fun Modifier.languageMark(v: String = "", e: String = "lmo", bleed: Dp = 0.dp): Modifier {
    val pal = ArtPalettes.of(Fluvour.lang)
    val fam = family(Fluvour.lang)
    val reduce = rememberReduceMotion()
    val lx = LangArt.has(Fluvour.lang)
    val clock = rememberAmbientClock(false, false, ArtPrefs.enabled && (fam == "en" || lx) && !reduce)
    if (!ArtPrefs.enabled) return this
    val spec = markSpec(if (fam == "en") e else v)
    val key = markKey(Fluvour.lang)
    return this.then(Modifier.drawBehind {
        val d = density
        val bleedPx = bleed.toPx()
        val side = spec.size * d
        val x = size.width + bleedPx - spec.right * d - side
        paintMark(pal, if (lx && reduce) -1f else clock.value, key, x, spec.top * d, side, spec.opacity, 0.7f)
    })
}

/** The larger mark on the "All done" and onboarding screens (.mo.lmo): 112dp, half strength, solid over the right 45%. */
@Composable
fun LanguageMotif(modifier: Modifier = Modifier) {
    if (!ArtPrefs.enabled) return
    val pal = ArtPalettes.of(Fluvour.lang)
    val fam = family(Fluvour.lang)
    val reduce = rememberReduceMotion()
    val lx = LangArt.has(Fluvour.lang)
    val clock = rememberAmbientClock(false, false, (fam == "en" || lx) && !reduce)
    val key = markKey(Fluvour.lang)
    Canvas(modifier.size(112.dp)) {
        paintMark(pal, if (lx && reduce) -1f else clock.value, key, 0f, 0f, size.width, 0.5f, 0.55f)
    }
}
