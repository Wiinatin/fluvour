package app.fluvour.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val ACCENT = 0xFFD9743C.toInt()

/** The floating button. Draws a round badge; shows a spinning arc while a page is being read. */
class BubbleView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 24 * d; typeface = Typeface.DEFAULT_BOLD }
    private var spin = 0f
    var busy = false
        set(v) { field = v; invalidate() }

    val sizePx: Int get() = (54 * d).toInt()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        c.drawCircle(r, r, r - 2 * d, fill)
        if (busy) {
            spin = (spin + 9f) % 360f
            val inset = 7 * d
            c.drawArc(RectF(inset, inset, width - inset, height - inset), spin, 100f, false, ring)
            postInvalidateOnAnimation()
        } else {
            c.drawText("A", r, r - (glyph.ascent() + glyph.descent()) / 2, glyph)
        }
    }
}

/**
 * The full-screen layer shown after a capture. It dims the screen and outlines every block of text that was read; tap one to look it up.
 * "Select area" switches to drag mode, to read only part of the screen (long strips, busy backgrounds). "Close" removes the layer.
 */
class LensView(
    context: Context,
    var page: OcrPage?,
    private val onPick: (block: OcrBlock, index: Int, offset: Int) -> Unit,
    private val onRegion: (left: Int, top: Int, right: Int, bottom: Int) -> Unit,
    private val onClose: () -> Unit
) : View(context) {
    private val d = resources.displayMetrics.density
    private val scrim = Paint().apply { color = 0x66000000 }
    private val blockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40D9743C }
    private val blockLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2B2B.toInt(); textAlign = Paint.Align.CENTER; textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 15 * d; setShadowLayer(4 * d, 0f, 0f, Color.BLACK) }
    private val dragLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * d; pathEffect = android.graphics.DashPathEffect(floatArrayOf(14 * d, 8 * d), 0f) }

    /** Message at the top, for example "No text found." */
    var status: String? = null
        set(v) { field = v; invalidate() }
    private var regionMode = false
    private var dragStart: Pair<Float, Float>? = null
    private var dragNow: Pair<Float, Float>? = null
    private val topPad = 64 * d
    private val closeBox = RectF()
    private val modeBox = RectF()

    /** Opens straight in "Select area" mode (long-press on the button). */
    fun enterRegionMode() { regionMode = true; status = null; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        if (!regionMode) page?.blocks?.forEach {
            val r = RectF(it.box.left.toFloat(), it.box.top.toFloat(), it.box.right.toFloat(), it.box.bottom.toFloat()).apply { inset(-4 * d, -4 * d) }
            c.drawRoundRect(r, 8 * d, 8 * d, blockFill); c.drawRoundRect(r, 8 * d, 8 * d, blockLine)
        }
        val h = 40 * d
        closeBox.set(width - 16 * d - 92 * d, topPad, width - 16 * d, topPad + h)
        modeBox.set(closeBox.left - 8 * d - 150 * d, topPad, closeBox.left - 8 * d, topPad + h)
        for ((box, label) in listOf(closeBox to "Close", modeBox to if (regionMode) "Whole screen" else "Select area")) {
            c.drawRoundRect(box, h / 2, h / 2, pillFill)
            c.drawText(label, box.centerX(), box.centerY() - (pillText.ascent() + pillText.descent()) / 2, pillText)
        }
        val msg = status ?: if (regionMode) "Drag a box around the text" else if (page?.blocks.isNullOrEmpty()) null else "Tap the text to look up"
        if (msg != null) c.drawText(msg, width / 2f, topPad + h + 36 * d, hint)
        val a = dragStart; val b = dragNow
        if (regionMode && a != null && b != null) {
            val r = RectF(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second))
            c.drawRect(r, Paint().apply { color = 0x33FFFFFF }); c.drawRect(r, dragLine)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragStart = e.x to e.y; dragNow = e.x to e.y; return true }
            MotionEvent.ACTION_MOVE -> { dragNow = e.x to e.y; if (regionMode) invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                val a = dragStart; val moved = a != null && (abs(e.x - a.first) > 12 * d || abs(e.y - a.second) > 12 * d)
                dragStart = null; dragNow = null
                if (!moved && closeBox.contains(e.x, e.y)) { onClose(); return true }
                if (!moved && modeBox.contains(e.x, e.y)) { regionMode = !regionMode; status = null; invalidate(); return true }
                if (regionMode) {
                    if (a != null && moved) {
                        val l = min(a.first, e.x).toInt(); val t = min(a.second, e.y).toInt(); val r = max(a.first, e.x).toInt(); val b = max(a.second, e.y).toInt()
                        if (r - l > 40 && b - t > 40) onRegion(l, t, r, b)
                    }
                    invalidate(); return true
                }
                val p = page ?: return true
                val x = e.x.toInt(); val y = e.y.toInt()
                OcrGeometry.blockAt(p, x, y)?.let { onPick(it, p.blocks.indexOf(it), OcrGeometry.offsetAt(it, x, y)) }
                    ?: run { status = "No text there. Tap a highlighted box."; }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { dragStart = null; dragNow = null; invalidate(); return true }
        }
        return true
    }
}
