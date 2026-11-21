package app.fluvour.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sound made by a local voice: float samples (-1 to 1), mono, at the rate the model uses (24000 Kokoro, 22050 or 16000 Piper and MMS). */
class Pcm(val samples: FloatArray, val sampleRate: Int) {
    val seconds: Float get() = if (sampleRate > 0) samples.size / sampleRate.toFloat() else 0f

    /** 16-bit mono WAV. The rate comes from the model, it is not always 24000. */
    fun toWav(): ByteArray {
        val n = samples.size
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }
}
