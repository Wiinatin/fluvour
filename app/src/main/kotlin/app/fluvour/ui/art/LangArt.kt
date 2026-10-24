package app.fluvour.ui.art

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import app.fluvour.ui.art.lang.*
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/*
 * Runtime for the data-driven language art of zh, ko, es, fr, de, vi and id (marks and ambient layers).
 * The scene data in the files in ui/art/lang is GENERATED from the HTML designs by tools/svg2scene (see docs/LANG_ART.md):
 * every SVG shape becomes a LangNode, every CSS @keyframes animation becomes a LangAnim, and each node carries a "chain"
 * of transform steps (static matrix, animation, clip) from the outermost group down. This file only draws that data.
 */

/** Chain step encoding: lm(i) = static matrix i, la(i) = animation i, lk(i) = clip i. */
internal fun lm(i: Int) = i
internal fun la(i: Int) = 1000 + i
internal fun lk(i: Int) = 2000 + i

/** One CSS animation. [tf] stops are [t, tx, ty, rotDeg, sx, sy]; [op] stops are [t, opacity]; t runs 0..1. [ease]: 0 linear, 1 ease-in-out, 2 ease-out. */
internal class LangAnim(val dur: Float, val delay: Float, val ease: Int, val ox: Float, val oy: Float, val tf: FloatArray?, val op: FloatArray?)

/** Gradient in objectBoundingBox units. Linear: a = x1,y1,x2,y2. Radial: a = cx,cy,r. [cols] are 0xAARRGGBB. */
internal class LangGrad(val radial: Boolean, val a: FloatArray, val offs: FloatArray, val cols: LongArray)

/**
 * One drawable. [f]/[s] (fill / stroke): 0 none, 1..5 palette ink il1..il5, 10+i gradient i, 30+i literal colour i.
 * [fa]/[sa] are the fill and stroke alphas, [cap] 0 butt / 1 round / 2 square, [ev] even-odd fill.
 */
internal class LangNode(val d: String, val c: Int, val f: Int, val fa: Float, val s: Int, val sa: Float, val sw: Float, val cap: Int, val ev: Boolean) {
    var path: Path? = null
    var bounds: Rect = Rect.Zero
}

internal class LangScene(
    val w: Float, val h: Float,
    val mats: FloatArray, val anims: Array<LangAnim>, val clips: Array<FloatArray>, val chains: Array<IntArray>,
    val grads: Array<LangGrad>, val colors: LongArray, val nodes: Array<LangNode>,
) {
    val clipPaths: Array<Path?> = arrayOfNulls(clips.size)
}

private val easeInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val easeOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Language codes that have their own art. */
internal object LangArt {
    val codes = setOf("zh", "ko", "es", "fr", "de", "vi", "id")
    fun has(lang: String) = lang in codes
    fun mark(lang: String): LangScene? = when (lang) {
        "zh" -> ZhArt.mark; "ko" -> KoArt.mark; "es" -> EsArt.mark; "fr" -> FrArt.mark
        "de" -> DeArt.mark; "vi" -> ViArt.mark; "id" -> IdArt.mark; else -> null
    }
    fun ambient(lang: String): LangScene? = when (lang) {
        "zh" -> ZhArt.ambient; "ko" -> KoArt.ambient; "es" -> EsArt.ambient; "fr" -> FrArt.ambient
        "de" -> DeArt.ambient; "vi" -> ViArt.ambient; "id" -> IdArt.ambient; else -> null
    }
}

// ------------------------------------------------------------------ animation maths

private fun sample(a: FloatArray, stride: Int, p: Float, ease: Int, out: FloatArray) {
    val n = a.size / stride
    var i = 0
    while (i < n - 2 && p >= a[(i + 1) * stride]) i++
    val t0 = a[i * stride]; val t1 = a[(i + 1) * stride]
    var u = if (t1 > t0) ((p - t0) / (t1 - t0)).coerceIn(0f, 1f) else 1f
    u = when (ease) { 0 -> u; 1 -> easeInOut.transform(u); else -> easeOut.transform(u) }
    for (k in 1 until stride) out[k - 1] = a[i * stride + k] + (a[(i + 1) * stride + k] - a[i * stride + k]) * u
}

private val tfOut = FloatArray(5)
private val opOut = FloatArray(1)

private fun mul(m: FloatArray, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
    val ma = m[0]; val mb = m[1]; val mc = m[2]; val md = m[3]; val me = m[4]; val mf = m[5]
    m[0] = ma * a + mc * b; m[1] = mb * a + md * b
    m[2] = ma * c + mc * d; m[3] = mb * c + md * d
    m[4] = ma * e + mc * f + me; m[5] = mb * e + md * f + mf
}

/** Multiplies the animation's current transform into [m] and returns its opacity. [t] < 0 means "still": no animation at all. */
private fun applyAnim(an: LangAnim, t: Float, m: FloatArray): Float {
    if (t < 0f) return 1f
    val x = (t - an.delay) / an.dur
    val p = x - floor(x)
    var op = 1f
    val tf = an.tf
    if (tf != null) {
        sample(tf, 6, p, an.ease, tfOut)
        val r = Math.toRadians(tfOut[2].toDouble())
        val co = cos(r).toFloat(); val si = sin(r).toFloat()
        val a = co * tfOut[3]; val b = si * tfOut[3]; val c = -si * tfOut[4]; val d = co * tfOut[4]
        val e = an.ox + tfOut[0] - (a * an.ox + c * an.oy)
        val f = an.oy + tfOut[1] - (b * an.ox + d * an.oy)
        mul(m, a, b, c, d, e, f)
    }
    val o = an.op
    if (o != null) { sample(o, 2, p, an.ease, opOut); op = opOut[0] }
    return op
}

// ------------------------------------------------------------------ drawing

private val scratch = FloatArray(6)
private val composeMatrix = Matrix()

private fun LangNode.ensurePath(): Path {
    path?.let { return it }
    val p = PathParser().parsePathString(d).toPath()
    if (ev) p.fillType = PathFillType.EvenOdd
    path = p; bounds = p.getBounds()
    return p
}

private fun LangScene.clipPath(i: Int): Path = clipPaths[i] ?: Path().also { p ->
    val r = clips[i]
    for (k in r.indices step 4) p.addRect(Rect(r[k], r[k + 1], r[k] + r[k + 2], r[k + 1] + r[k + 3]))
    clipPaths[i] = p
}

private fun LangScene.brush(g: LangGrad, b: Rect): Brush {
    val stops = Array(g.offs.size) { g.offs[it] to Color(g.cols[it]) }
    return if (g.radial) {
        Brush.radialGradient(*stops, center = Offset(b.left + g.a[0] * b.width, b.top + g.a[1] * b.height), radius = (g.a[2] * b.width).coerceAtLeast(0.01f))
    } else {
        Brush.linearGradient(*stops, start = Offset(b.left + g.a[0] * b.width, b.top + g.a[1] * b.height), end = Offset(b.left + g.a[2] * b.width, b.top + g.a[3] * b.height))
    }
}

private fun setMatrix(m: FloatArray) {
    val v = composeMatrix.values
    composeMatrix.reset()
    v[0] = m[0]; v[1] = m[1]; v[4] = m[2]; v[5] = m[3]; v[12] = m[4]; v[13] = m[5]
}

/**
 * Draws [s] in its own coordinate space at the current origin (marks are 140x140, ambient is 296x300).
 * [t] is seconds on the animation clock; pass a negative value for a still frame (Reduce motion). [alpha] multiplies everything.
 */
internal fun DrawScope.drawLangScene(s: LangScene, pal: ArtPalette, t: Float, alpha: Float = 1f) {
    val cv = drawContext.canvas
    val m = scratch
    for (n in s.nodes) {
        val chain = s.chains[n.c]
        m[0] = 1f; m[1] = 0f; m[2] = 0f; m[3] = 1f; m[4] = 0f; m[5] = 0f
        var op = alpha
        var saved = false
        for (st in chain) {
            when {
                st < 1000 -> { val k = st * 6; mul(m, s.mats[k], s.mats[k + 1], s.mats[k + 2], s.mats[k + 3], s.mats[k + 4], s.mats[k + 5]) }
                st < 2000 -> op *= applyAnim(s.anims[st - 1000], t, m)
                else -> {
                    cv.save(); saved = true
                    setMatrix(m); cv.concat(composeMatrix)
                    m[0] = 1f; m[1] = 0f; m[2] = 0f; m[3] = 1f; m[4] = 0f; m[5] = 0f
                    cv.clipPath(s.clipPath(st - 2000))
                }
            }
        }
        if (op <= 0.001f) { if (saved) cv.restore(); continue }
        if (!saved) cv.save()
        setMatrix(m); cv.concat(composeMatrix)
        val path = n.ensurePath()
        if (n.f != 0) paintWith(s, n.f, pal, path, n, n.fa * op, null)
        if (n.s != 0) paintWith(s, n.s, pal, path, n, n.sa * op, Stroke(n.sw, cap = when (n.cap) { 1 -> StrokeCap.Round; 2 -> StrokeCap.Square; else -> StrokeCap.Butt }))
        cv.restore()
    }
}

private fun DrawScope.paintWith(s: LangScene, code: Int, pal: ArtPalette, path: Path, n: LangNode, a: Float, stroke: Stroke?) {
    when {
        code <= 5 -> if (stroke == null) drawPath(path, pal.il(code), a) else drawPath(path, pal.il(code), a, stroke)
        code < 30 -> {
            val b = s.brush(s.grads[code - 10], n.bounds)
            if (stroke == null) drawPath(path, b, a) else drawPath(path, b, a, stroke)
        }
        else -> { val c = Color(s.colors[code - 30]); if (stroke == null) drawPath(path, c, a) else drawPath(path, c, a, stroke) }
    }
}

// ------------------------------------------------------------------ keys used by Art.kt

/**
 * Scene keys: "lx:es" the 140x140 mark, "lxc:es" the cover crop (viewBox 30 14 84 108), "lxs:es" the Watch banner
 * (viewBox 0 22 140 71, with the ambient layer over it at 85%).
 */
internal fun isLangKey(key: String) = key.startsWith("lx:") || key.startsWith("lxc:") || key.startsWith("lxs:")

/** viewBox x, y, w, h of a lang key. */
internal fun langView(key: String): FloatArray = when {
    key.startsWith("lxc:") -> floatArrayOf(30f, 14f, 84f, 108f)
    key.startsWith("lxs:") -> floatArrayOf(0f, 22f, 140f, 71f)
    else -> floatArrayOf(0f, 0f, 140f, 140f)
}

/** Draws a lang key cropped to fill [box] (xMidYMid slice). [clockMs] < 0 = still. Does not draw the grain (Art.kt does). */
internal fun DrawScope.paintLangScene(key: String, pal: ArtPalette, clockMs: Float, box: Size) {
    val lang = key.substringAfter(':')
    val mark = LangArt.mark(lang) ?: return
    val v = langView(key)
    val sc = max(box.width / v[2], box.height / v[3])
    val dx = (box.width - v[2] * sc) / 2f; val dy = (box.height - v[3] * sc) / 2f
    val t = if (clockMs < 0f) -1f else clockMs / 1000f
    cv(box) {
        withTransform({ translate(dx - v[0] * sc, dy - v[1] * sc); scale(sc, sc, Offset.Zero) }) { drawLangScene(mark, pal, t) }
        if (key.startsWith("lxs:")) {
            LangArt.ambient(lang)?.let { amb ->
                // viewBox 0 0 296 300, xMidYMin slice, 85% opacity
                val s2 = max(box.width / 296f, box.height / 300f)
                withTransform({ translate((box.width - 296f * s2) / 2f, 0f); scale(s2, s2, Offset.Zero) }) { drawLangScene(amb, pal, t, 0.85f) }
            }
        }
    }
}

private inline fun DrawScope.cv(box: Size, block: () -> Unit) {
    val c = drawContext.canvas
    c.save(); c.clipRect(0f, 0f, box.width, box.height); block(); c.restore()
}

/** The ambient layer behind a screen, drawn at the current origin in dp space with width [wDp]; the caller fades it out. */
internal fun DrawScope.paintLangAmbient(lang: String, pal: ArtPalette, clockMs: Float, wDp: Float) {
    val amb = LangArt.ambient(lang) ?: return
    val k = wDp / 296f
    withTransform({ scale(k, k, Offset.Zero) }) { drawLangScene(amb, pal, clockMs / 1000f) }
}
