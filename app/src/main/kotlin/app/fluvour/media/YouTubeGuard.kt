package app.fluvour.media

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Thrown without touching the network while YouTube has recently refused this IP. */
class YouTubeCooldownException(val minutesLeft: Long) : Exception(
    "YouTube is limiting this connection right now. Fluvour is pausing YouTube requests for about $minutesLeft min so the block can clear. " +
        "Switching between Wi-Fi and mobile data can also help."
)

/**
 * One place that decides whether, and when, Fluvour may talk to YouTube. Three jobs:
 *  1. Pacing: every request start is spaced out with random jitter, so a batch never looks like a burst.
 *  2. Circuit breaker: after a bot-check or rate-limit answer, no request is sent until a cooldown ends (3 min, doubling up to 30 min; a network change or the Settings button ends it at once; a request you start yourself is always tried).
 *     The cooldown is saved, so restarting the app does not start hammering again.
 *  3. Cache: the same video or search is not asked for twice within a short time.
 */
object YouTubeGuard {
    private const val PREFS = "yt_guard"
    private const val K_UNTIL = "until"
    private const val K_LEVEL = "level"
    private const val K_DAY = "count_day"
    private const val K_COUNT = "count"
    private const val MIN_GAP_MS = 1_500L
    private const val MAX_GAP_MS = 4_000L
    private const val BASE_COOLDOWN_MS = 3L * 60_000
    private const val MAX_COOLDOWN_MS = 30L * 60_000
    private const val CACHE_MS = 20L * 60_000
    private const val CACHE_MAX = 60

    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var until = 0L
    @Volatile private var level = 0
    private val _requestsToday = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Guarded YouTube requests actually sent today (cache hits and cooldown refusals are not counted). Resets at local midnight. */
    val requestsToday: kotlinx.coroutines.flow.StateFlow<Int> = _requestsToday
    private val _lastReason = kotlinx.coroutines.flow.MutableStateFlow("")
    /** Short text of the last block answer (shown in Settings so it is clear what YouTube said). */
    val lastReason: kotlinx.coroutines.flow.StateFlow<String> = _lastReason

    /** Put in a coroutine's context for requests the person started (opening a video): they are tried even during a cooldown. Background jobs never use it. */
    class UserRequest : kotlin.coroutines.AbstractCoroutineContextElement(UserRequest) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<UserRequest>
    }

    /** Ends the cooldown now (Settings button, or the network changed). */
    fun reset() {
        until = 0L; level = 0
        prefs?.edit()?.putLong(K_UNTIL, 0L)?.putInt(K_LEVEL, 0)?.apply()
    }

    private var netCallbackOn = false
    private fun watchNetwork(context: Context) {
        if (netCallbackOn) return
        netCallbackOn = true
        runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            var last: android.net.Network? = null
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    // a different network (Wi-Fi <-> mobile data) is a different address for YouTube: ask again
                    if (last != null && last != network) reset()
                    last = network
                }
            })
        }
    }

    private val paceLock = Mutex()
    private var lastStart = 0L

    private class Entry(val at: Long, val value: Any?)
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > CACHE_MAX
    }

    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        until = p.getLong(K_UNTIL, 0L)
        level = p.getInt(K_LEVEL, 0)
        _requestsToday.value = if (p.getString(K_DAY, null) == today()) p.getInt(K_COUNT, 0) else 0
        watchNetwork(context)
    }

    private fun today(): String = java.time.LocalDate.now().toString()

    /** One more request is about to be sent: add it to today's count (a new day starts again from 1). */
    private fun countRequest() = synchronized(this) {
        val day = today()
        val p = prefs
        val n = if (p == null || p.getString(K_DAY, null) == day) _requestsToday.value + 1 else 1
        _requestsToday.value = n
        p?.edit()?.putString(K_DAY, day)?.putInt(K_COUNT, n)?.apply()
    }

    /** True for YouTube's "sign in to confirm you're not a bot" and for rate limits (HTTP 429 / captcha). */
    fun isBlock(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 6) {
            val text = (e.javaClass.simpleName + " " + (e.message ?: "")).lowercase()
            if ("signinconfirmnotbot" in text || "not a bot" in text || "login_required" in text ||
                "recaptcha" in text || "captcha" in text || "http 429" in text || "too many requests" in text) return true
            e = e.cause
        }
        return false
    }

    /** Minutes of cooldown left, 0 when requests are allowed. */
    fun cooldownMinutes(): Long {
        val left = until - System.currentTimeMillis()
        return if (left <= 0) 0 else (left + 59_999) / 60_000
    }

    fun coolingDown(): Boolean = cooldownMinutes() > 0

    fun onBlocked(cause: Throwable? = null) {
        if (cause != null) _lastReason.value = ((cause.message ?: cause.javaClass.simpleName).lineSequence().firstOrNull() ?: "").take(120)
        level = (level + 1).coerceAtMost(5)
        val len = (BASE_COOLDOWN_MS shl (level - 1)).coerceAtMost(MAX_COOLDOWN_MS)
        until = System.currentTimeMillis() + len
        prefs?.edit()?.putLong(K_UNTIL, until)?.putInt(K_LEVEL, level)?.apply()
        synchronized(cache) { cache.clear() }
    }

    /** True when a saved answer for [key] is still fresh, without touching the network. */
    fun isCached(key: String, ttlMs: Long): Boolean = synchronized(cache) { cache[key] }?.let { System.currentTimeMillis() - it.at < ttlMs } == true

    /** Drops one saved answer, so the next [run] with this key asks YouTube again (used when a saved stream address stopped working). */
    fun forget(key: String) { synchronized(cache) { cache.remove(key) } }

    /** A request worked: the next block (if any) starts from the shortest cooldown again. */
    fun onSuccess() {
        if (level == 0 && until == 0L) return
        level = 0; until = 0L
        prefs?.edit()?.putInt(K_LEVEL, 0)?.putLong(K_UNTIL, 0L)?.apply()
    }

    /** Waits so request starts are at least a random 1.5 to 4 s apart, even when several coroutines ask at once. */
    private suspend fun pace() = paceLock.withLock {
        val gap = MIN_GAP_MS + Random.nextLong(MAX_GAP_MS - MIN_GAP_MS)
        val wait = lastStart + gap - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastStart = System.currentTimeMillis()
    }

    /**
     * Runs one YouTube request under the rules above. [key] turns on caching (null = never cached); [ttlMs] is how long a saved answer is reused (the caller must pass the same value when it reads and when it saves).
     * A cooldown answers instantly with [YouTubeCooldownException]; a block answer starts the cooldown and is never retried here.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(key: String?, block: () -> Result<T>, ttlMs: Long = CACHE_MS): Result<T> {
        if (key != null) synchronized(cache) { cache[key] }?.let { if (System.currentTimeMillis() - it.at < ttlMs) return Result.success(it.value as T) }
        val left = cooldownMinutes()
        if (left > 0 && kotlin.coroutines.coroutineContext[UserRequest] == null) return Result.failure(YouTubeCooldownException(left))
        pace()
        countRequest()
        val r = block()
        if (r.isSuccess) {
            onSuccess()
            if (key != null) synchronized(cache) { cache[key] = Entry(System.currentTimeMillis(), r.getOrThrow()) }
        } else if (isBlock(r.exceptionOrNull())) onBlocked(r.exceptionOrNull())
        return r
    }
}
