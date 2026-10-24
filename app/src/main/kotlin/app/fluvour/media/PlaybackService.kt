package app.fluvour.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Lets the app's one player (owned by PlayerViewModel) be handed to the service without a refactor. Null while no player exists. */
object PlaybackHolder { @Volatile var player: Player? = null }

/**
 * Shows the system media notification (title, play/pause) and keeps playback alive in the background and with the screen off.
 * Started by PlayerViewModel the first time the player starts playing. Media3 promotes it to a foreground service while playing.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = PlaybackHolder.player ?: run { stopSelf(); return }
        val open = PendingIntent.getActivity(this, 0, Intent(this, app.fluvour.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, p).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: keep playing if it is playing, otherwise stop the service. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
