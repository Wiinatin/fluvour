package app.fluvour.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Decodes an audio stream to loudness bars for the waveform scrubber. Heavy: call it off the main thread. */
object WaveformExtractor {
    private const val TAG = "FluvourWave"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

    /** `buckets` loudness values from 0 to 1 spread over the whole audio, or null if it is too long, cannot be read or `cancelled()` turns true. */
    fun extract(url: String, buckets: Int = 240, maxMinutes: Int = 40, cancelled: () -> Boolean = { false }): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var file: java.io.File? = null
        val started = System.currentTimeMillis()
        try {
            // Download first (ranged chunks, so YouTube does not throttle), then decode the local file.
            file = download(url, started, cancelled) ?: return null
            Log.d(TAG, "downloaded ${file.length()} bytes in ${System.currentTimeMillis() - started} ms")
            java.io.FileInputStream(file).use { ex.setDataSource(it.fd) }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: run { Log.w(TAG, "no audio track, tracks=${ex.trackCount}"); return null }
            val fmt = ex.getTrackFormat(track)
            Log.d(TAG, "start, format=$fmt")
            if (!fmt.containsKey(MediaFormat.KEY_DURATION)) { Log.w(TAG, "no duration in format"); return null }
            val durationUs = fmt.getLong(MediaFormat.KEY_DURATION)
            if (durationUs <= 0 || durationUs > maxMinutes * 60_000_000L) { Log.w(TAG, "duration out of range: $durationUs us"); return null }
            ex.selectTrack(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()

            val peaks = FloatArray(buckets)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            while (true) {
                if (cancelled() || System.currentTimeMillis() - started > 180_000) { Log.w(TAG, "stopped: cancelled=${cancelled()} elapsedMs=${System.currentTimeMillis() - started}"); return null }
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val pcm = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        var m = 0
                        while (pcm.hasRemaining()) { val v = abs(pcm.get().toInt()); if (v > m) m = v }
                        val b = (info.presentationTimeUs.toDouble() / durationUs * buckets).toInt().coerceIn(0, buckets - 1)
                        if (m / 32768f > peaks[b]) peaks[b] = m / 32768f
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val top = peaks.max()
            if (top <= 0f) { Log.w(TAG, "all silent"); return null }
            Log.d(TAG, "done in ${System.currentTimeMillis() - started} ms")
            return FloatArray(buckets) { sqrt(peaks[it] / top) }   // square root so quiet parts stay visible
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
            try { file?.delete() } catch (_: Exception) {}
        }
    }

    private fun download(url: String, started: Long, cancelled: () -> Boolean): java.io.File? {
        val out = java.io.File.createTempFile("wave", ".bin")
        val chunk = 1_000_000L
        val maxBytes = 80_000_000L
        var off = 0L
        try {
            out.outputStream().use { os ->
                while (off < maxBytes) {
                    if (cancelled() || System.currentTimeMillis() - started > 120_000) { Log.w(TAG, "download stopped at $off"); out.delete(); return null }
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    try {
                        c.connectTimeout = 15_000; c.readTimeout = 20_000
                        c.setRequestProperty("User-Agent", UA)
                        c.setRequestProperty("Range", "bytes=$off-${off + chunk - 1}")
                        val code = c.responseCode
                        if (code != 200 && code != 206) { Log.w(TAG, "download HTTP $code at $off"); out.delete(); return null }
                        val n = c.inputStream.use { it.copyTo(os) }
                        if (code == 200) return out                      // server ignored Range and sent everything
                        val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        off += n
                        if (n == 0L || (total != null && off >= total)) return out
                    } finally { c.disconnect() }
                }
            }
            Log.w(TAG, "audio larger than $maxBytes bytes"); out.delete(); return null
        } catch (e: Exception) { Log.e(TAG, "download failed at $off", e); out.delete(); return null }
    }
}
