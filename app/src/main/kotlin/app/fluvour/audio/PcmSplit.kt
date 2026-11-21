package app.fluvour.audio

/**
 * Cuts one long spoken recording (16-bit little-endian mono PCM) into one piece per sentence, at the silent gaps (pure Kotlin;
 * tools/splittest runs it on a plain JVM). The batch voice asks Gemini to leave a long pause between sentences. If the number of
 * pieces found is not exactly the number of sentences, nothing is returned: a wrong cut would put a sentence on the wrong button.
 */
object PcmSplit {
    private const val WINDOW_MS = 20
    private const val MIN_VOICED_MS = 250          // a piece shorter than this is a click or breath, not a sentence
    private const val PAD_BEFORE_MS = 120
    private const val PAD_AFTER_MS = 250
    /** Gap lengths to try, longest first: the longest gap that gives exactly the wanted count is the safest cut. */
    val GAPS_MS = listOf(1600, 1300, 1000, 800, 650, 500, 400, 300)

    /** One piece per sentence (PCM bytes), or null when no gap length gives exactly [expected] pieces. */
    fun split(pcm: ByteArray, rate: Int, expected: Int): List<ByteArray>? {
        if (expected <= 0 || pcm.size < 2) return null
        val win = rate * WINDOW_MS / 1000
        val n = pcm.size / 2 / win
        if (n < expected) return null
        val rms = DoubleArray(n) { w -> energy(pcm, w * win, win) }
        val sorted = rms.sorted()
        val loud = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        if (loud < 50.0) return null                                    // no real speech in it
        val threshold = maxOf(40.0, loud * 0.06)
        val voiced = BooleanArray(n) { rms[it] > threshold }
        for (gap in GAPS_MS) {
            val runs = voicedRuns(voiced, gap / WINDOW_MS)
            if (runs.size != expected) continue
            if (runs.any { (it.last - it.first + 1) * WINDOW_MS < MIN_VOICED_MS }) continue
            return runs.mapIndexed { i, r ->
                val from = maxOf(if (i == 0) 0 else runs[i - 1].last + 1, r.first - PAD_BEFORE_MS / WINDOW_MS)
                val to = minOf(if (i == runs.lastIndex) n - 1 else runs[i + 1].first - 1, r.last + PAD_AFTER_MS / WINDOW_MS)
                pcm.copyOfRange(from * win * 2, (to + 1) * win * 2)
            }
        }
        return null
    }

    /** Runs of voiced windows; two runs are one when the silence between them is shorter than [gapWindows]. */
    private fun voicedRuns(voiced: BooleanArray, gapWindows: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1; var last = -1
        for (i in voiced.indices) {
            if (!voiced[i]) continue
            if (start == -1) { start = i; last = i }
            else if (i - last - 1 >= gapWindows) { out.add(start..last); start = i; last = i }
            else last = i
        }
        if (start != -1) out.add(start..last)
        return out
    }

    private fun energy(pcm: ByteArray, from: Int, count: Int): Double {
        var sum = 0.0
        for (k in 0 until count) {
            val i = (from + k) * 2
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            sum += v.toDouble() * v
        }
        return Math.sqrt(sum / count)
    }
}
