package app.fluvour.reading

/** One touch point on the writing pad, as a fraction of the pad side (0 to 1), so a drawing can be redrawn at any size. */
data class PadPoint(val x: Float, val y: Float)

/**
 * What the finger has drawn on the Heisig writing pad. Pure Kotlin, no Android, never saved.
 * Every change returns a new [PadDrawing], so it can sit in Compose state as is.
 * [done] are finished strokes in drawing order; [live] is the stroke under the finger right now.
 */
data class PadDrawing(val done: List<List<PadPoint>> = emptyList(), val live: List<PadPoint> = emptyList()) {
    val isEmpty: Boolean get() = done.isEmpty() && live.isEmpty()
    val strokeCount: Int get() = done.size + if (live.isEmpty()) 0 else 1

    /** Finger down: closes any open stroke and starts a new one. Too many strokes: ignored. */
    fun begin(p: PadPoint): PadDrawing {
        val closed = end()
        if (closed.done.size >= PadMath.MAX_STROKES) return closed
        return closed.copy(live = listOf(PadMath.clamp(p)))
    }

    /** Finger moved. Points closer than [PadMath.MIN_STEP] to the last one are skipped. No open stroke: ignored. */
    fun add(p: PadPoint): PadDrawing {
        if (live.isEmpty() || live.size >= PadMath.MAX_POINTS) return this
        val q = PadMath.clamp(p)
        return if (PadMath.dist(live.last(), q) < PadMath.MIN_STEP) this else copy(live = live + q)
    }

    /** Finger up. A tap (one point) stays as a dot. */
    fun end(): PadDrawing = if (live.isEmpty()) this else PadDrawing(done + listOf(live), emptyList())

    /** Removes the stroke under the finger, or else the last finished one. */
    fun undo(): PadDrawing = when {
        live.isNotEmpty() -> copy(live = emptyList())
        done.isNotEmpty() -> copy(done = done.dropLast(1))
        else -> this
    }

    fun clear(): PadDrawing = PadDrawing()

    /** Every stroke, finished and live, for drawing. */
    fun all(): List<List<PadPoint>> = if (live.isEmpty()) done else done + listOf(live)
}

object PadMath {
    const val MIN_STEP = 0.004f
    const val MAX_STROKES = 60
    const val MAX_POINTS = 2000

    fun clamp(p: PadPoint) = PadPoint(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))

    fun dist(a: PadPoint, b: PadPoint): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /**
     * Rounds the corners of a finger line (Chaikin corner cutting). The first and last point stay where they are.
     * One or two points come back unchanged (a dot stays a dot).
     */
    fun smooth(points: List<PadPoint>, rounds: Int = 2): List<PadPoint> {
        var cur = points
        repeat(rounds) {
            if (cur.size < 3) return cur
            val out = ArrayList<PadPoint>(cur.size * 2)
            out.add(cur.first())
            for (i in 0 until cur.size - 1) {
                val a = cur[i]; val b = cur[i + 1]
                out.add(PadPoint(0.75f * a.x + 0.25f * b.x, 0.75f * a.y + 0.25f * b.y))
                out.add(PadPoint(0.25f * a.x + 0.75f * b.x, 0.25f * a.y + 0.75f * b.y))
            }
            out.add(cur.last())
            cur = out
        }
        return cur
    }
}
