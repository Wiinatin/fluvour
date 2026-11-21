package app.fluvour.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.fluvour.reading.ClipRef
import app.fluvour.ui.CLIP_LEAD_MS
import app.fluvour.ui.CLIP_TAIL_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Clip cards save the few seconds they play on disk (the SD card when there is one), so a clip that was prepared ahead plays with no
 * YouTube request at all. One shared [SimpleCache] (least recently used goes first, [MAX_BYTES] cap). Bytes are stored under a key made of
 * the video link and the stream type (itag), not the stream address, which changes with every lookup.
 * A clip is "ready" only after [fetch] has buffered its range AND played it back from disk alone (no network) without a problem; ready
 * clips are listed in a small preferences file. The size cap is kept by [trim], which removes a whole video (all its clips and their
 * ready marks) at a time, least recently used first, so a ready clip is never left half-removed.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ClipCache {
    const val MAX_BYTES = 500L * 1024 * 1024
    private const val PREFS = "clip_ready"          // "<video url>|<startMs>" -> itag
    private const val LEDGER = "clip_videos"        // resource key -> last time used (ms)
    private const val SETTINGS = "clip_ahead"
    private const val FETCH_TIMEOUT_MS = 60_000L
    private const val VERIFY_TIMEOUT_MS = 15_000L
    @Volatile private var cache: SimpleCache? = null

    /** The SD card's app folder when one is mounted, else the phone's external app folder, else internal storage (same rule as the saved voices). */
    private fun root(ctx: Context): File {
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        val sd = dirs.firstOrNull { runCatching { Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.getOrDefault(false) }
        return sd ?: dirs.firstOrNull() ?: ctx.filesDir
    }

    @Synchronized private fun get(ctx: Context): SimpleCache = cache ?: SimpleCache(
        File(root(ctx), "clips").also { it.mkdirs() }, NoOpCacheEvictor(), StandaloneDatabaseProvider(ctx.applicationContext)
    ).also { cache = it }

    /** Data source for the clip players: reads from the saved bytes first, the network only for what is missing. */
    fun factory(ctx: Context): DataSource.Factory = CacheDataSource.Factory().setCache(get(ctx))
        .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun mediaSourceFactory(ctx: Context) = DefaultMediaSourceFactory(ctx).setDataSourceFactory(factory(ctx))

    fun itagOf(streamUrl: String): String = runCatching { Uri.parse(streamUrl).getQueryParameter("itag") }.getOrNull() ?: "0"
    private fun key(videoUrl: String, itag: String) = "clip:" + videoUrl.trim() + "|" + itag

    /** A clip's stream from a fresh lookup: everything played through it is saved under the video's key. */
    fun item(streamUrl: String, videoUrl: String): MediaItem =
        MediaItem.Builder().setUri(streamUrl).setCustomCacheKey(key(videoUrl, itagOf(streamUrl))).build()

    /** A clip prepared ahead: its bytes are already saved; the video link is only the address used if something is missing (then the player errors and the fresh-lookup retry runs). */
    fun readyItem(ref: ClipRef, itag: String): MediaItem =
        MediaItem.Builder().setUri(ref.url.trim()).setCustomCacheKey(key(ref.url, itag)).build()

    private fun readyKey(ref: ClipRef) = ref.url.trim() + "|" + ref.startMs

    /** The stream type this clip was saved with, or null when it is not ready (or its bytes were evicted). */
    fun readyItag(ctx: Context, ref: ClipRef): String? {
        val itag = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(readyKey(ref), null) ?: return null
        val k = key(ref.url, itag)
        if (get(ctx).getCachedSpans(k).isEmpty()) { forgetReady(ctx, ref); return null }
        touch(ctx, k)
        return itag
    }

    private fun touch(ctx: Context, resourceKey: String) { ctx.getSharedPreferences(LEDGER, Context.MODE_PRIVATE).edit().putLong(resourceKey, System.currentTimeMillis()).apply() }

    /** The on/off switch in Settings (on by default). */
    fun aheadOn(ctx: Context): Boolean = ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean("on", true)
    fun setAheadOn(ctx: Context, on: Boolean) { ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply() }

    /** Removes one video's saved bytes and every ready mark that belongs to them. */
    private fun removeVideo(ctx: Context, resourceKey: String) {
        get(ctx).removeResource(resourceKey)
        ctx.getSharedPreferences(LEDGER, Context.MODE_PRIVATE).edit().remove(resourceKey).apply()
        val url = resourceKey.removePrefix("clip:").substringBeforeLast('|')
        val itag = resourceKey.substringAfterLast('|')
        val rp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ed = rp.edit()
        rp.all.forEach { (k, v) -> if (k.startsWith("$url|") && v == itag) ed.remove(k) }
        ed.apply()
    }

    /** Keeps the saved clips under [MAX_BYTES]: whole videos go, least recently used first; the video named in [keep] stays. Call off the main thread. */
    fun trim(ctx: Context, keep: String? = null) {
        val c = get(ctx)
        val ledger = ctx.getSharedPreferences(LEDGER, Context.MODE_PRIVATE)
        while (c.cacheSpace > MAX_BYTES) {
            val victim = c.keys.filter { it != keep }.minByOrNull { ledger.getLong(it, 0L) } ?: break
            removeVideo(ctx, victim)
        }
    }

    /** Deletes every saved clip (Settings, Clear). Call off the main thread. */
    fun clear(ctx: Context) {
        val c = get(ctx)
        c.keys.toList().forEach { c.removeResource(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        ctx.getSharedPreferences(LEDGER, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun forgetReady(ctx: Context, ref: ClipRef) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(readyKey(ref)).apply() }

    /** Megabytes saved now. */
    fun usedMb(ctx: Context): Long = get(ctx).cacheSpace / (1024 * 1024)

    private enum class Run { OK, TIMEOUT, ERROR }

    /** Plays [item] from [from] with no screen and no sound until [last] is buffered. [extra] is how much more than the clip the buffer holds. */
    private suspend fun runPlayer(ctx: Context, factory: DefaultMediaSourceFactory, item: MediaItem, from: Long, last: Long, extra: Int, timeoutMs: Long, stopWhenIdle: Boolean): Run =
        withContext(Dispatchers.Main) {
            val span = (last - from).toInt().coerceAtLeast(2_000) + extra
            val load = DefaultLoadControl.Builder().setBufferDurationsMs(span, span, 1_000, 1_000).build()
            val p = ExoPlayer.Builder(ctx).setMediaSourceFactory(factory).setLoadControl(load).build()
            var failed = false
            try {
                p.volume = 0f
                p.playWhenReady = false
                p.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed = true } })
                p.setMediaItem(item, from)
                p.prepare()
                withTimeoutOrNull(timeoutMs) {
                    var result = Run.ERROR
                    while (true) {
                        val s = p.playbackState
                        val enough = s == Player.STATE_ENDED || (s == Player.STATE_READY && (p.bufferedPosition >= last || (stopWhenIdle && !p.isLoading)))
                        if (enough) { result = Run.OK; break }
                        if (failed) { result = if (p.bufferedPosition >= last) Run.OK else Run.ERROR; break }   // an error after the range was buffered does not matter
                        delay(250)
                    }
                    result
                } ?: Run.TIMEOUT
            } finally { p.release() }
        }

    /**
     * Saves one clip's range from [streamUrl], then proves it: the clip is played back from disk with no network. Only then is it marked
     * ready. The download player has a buffer sized to the clip, so little more than the clip is fetched. True when the clip is ready.
     */
    suspend fun fetch(ctx: Context, ref: ClipRef, streamUrl: String): Boolean {
        val itag = itagOf(streamUrl)
        val from = (ref.startMs - CLIP_LEAD_MS).coerceAtLeast(0L)
        val last = minOf(ref.endMs, ref.startMs + ClipRef.MAX_MS) + CLIP_TAIL_MS
        val saved = runPlayer(ctx, mediaSourceFactory(ctx), item(streamUrl, ref.url), from, last, 1_500, FETCH_TIMEOUT_MS, stopWhenIdle = true)
        var ok = false
        if (saved != Run.ERROR) {
            // the proof: no upstream at all, so anything missing is an error; its buffer is smaller than the download's, so it never asks for more than was saved
            val offline = DefaultMediaSourceFactory(ctx).setDataSourceFactory(CacheDataSource.Factory().setCache(get(ctx)).setUpstreamDataSourceFactory(null))
            ok = runPlayer(ctx, offline, readyItem(ref, itag), from, last, 300, VERIFY_TIMEOUT_MS, stopWhenIdle = false) == Run.OK
        }
        if (ok) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(readyKey(ref), itag).apply()
        withContext(Dispatchers.IO) { touch(ctx, key(ref.url, itag)); trim(ctx, keep = key(ref.url, itag)) }
        return ok
    }
}
