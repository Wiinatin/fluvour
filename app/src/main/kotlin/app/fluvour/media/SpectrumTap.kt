package app.fluvour.media

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/** Reads the player's live output (Android Visualizer, needs the RECORD_AUDIO permission) and turns it into [BANDS] smooth loudness bars, low pitch on the left. */
class SpectrumTap {
    companion object {
        const val BANDS = 36
        private const val TAG = "FluvourSpectrum"
        private const val LOW_BIN = 1       // about 43 Hz at 1024 samples and 44.1 kHz
        private const val HIGH_BIN = 190    // about 8 kHz: where speech and most music live
    }

    private var viz: Visualizer? = null
    private var fft = ByteArray(0)
    private val level = FloatArray(BANDS)
    private val edges = IntArray(BANDS + 1) { i ->
        (LOW_BIN * (HIGH_BIN.toDouble() / LOW_BIN).pow(i.toDouble() / BANDS)).toInt()
    }.also { for (i in 1..BANDS) if (it[i] <= it[i - 1]) it[i] = it[i - 1] + 1 }

    val active get() = viz != null

    /** Starts listening to an audio session. Returns false when it cannot (no permission, no such session, device refuses). */
    fun attach(session: Int): Boolean {
        release()
        if (session == 0 || session == -1) return false
        return try {
            val v = Visualizer(session)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.enabled = true
            fft = ByteArray(v.captureSize); viz = v
            true
        } catch (e: Exception) { Log.w(TAG, "attach failed", e); viz = null; false }
    }

    fun release() { try { viz?.enabled = false; viz?.release() } catch (_: Exception) {}; viz = null; level.fill(0f) }

    /** One frame: fresh bars (0 to 1). Rise fast, fall slowly, so it moves like an equalizer instead of flickering. When [playing] is false the bars settle down. */
    fun frame(playing: Boolean): FloatArray {
        val v = viz
        val got = playing && v != null && try { v.getFft(fft) == Visualizer.SUCCESS } catch (_: Exception) { false }
        for (b in 0 until BANDS) {
            var target = 0f
            if (got) {
                var peak = 0f
                for (k in edges[b] until edges[b + 1].coerceAtMost(fft.size / 2)) {
                    val m = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
                    if (m > peak) peak = m
                }
                val tilt = 1f + 0.9f * b / BANDS                        // treble is naturally quieter, lift it a little
                target = (ln(1f + peak * tilt) / ln(1f + 70f)).coerceIn(0f, 1f)
            }
            level[b] = if (target > level[b]) level[b] + (target - level[b]) * 0.65f else level[b] * 0.86f
        }
        return level.copyOf()
    }
}
