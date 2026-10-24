package app.fluvour.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import javax.inject.Inject
import javax.inject.Singleton

/** Plays a Tatoeba native recording from its URL. Streams, nothing is saved. `onFail` runs when it cannot play (offline, bad id). */
@Singleton
class ClipPlayer @Inject constructor() {
    private var mp: MediaPlayer? = null

    fun play(tatoebaAudioId: Long, onFail: () -> Unit) {
        stop()
        try {
            Ducker.set("clip", true)
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource("https://tatoeba.org/audio/download/$tatoebaAudioId")
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); onFail(); true }
                prepareAsync()
            }
        } catch (e: Exception) { stop(); onFail() }
    }

    fun stop() { try { mp?.release() } catch (_: Exception) {}; mp = null; Ducker.set("clip", false) }
}
