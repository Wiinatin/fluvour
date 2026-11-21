package app.fluvour.reading.articles

import app.fluvour.net.Http
import app.fluvour.net.HttpResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/** All article sites go through here: an honest User-Agent and at least 1.5 seconds between two requests to the same site. Requests run one at a time. */
object PoliteHttp {
    const val UA = "Fluvour/0.0.1 (personal language-learning app)"
    private const val GAP_MS = 1_500L
    private val lock = Mutex()
    private val last = HashMap<String, Long>()

    suspend fun get(url: String, accept: String = "*/*"): HttpResult {
        val host = runCatching { URI(url).host }.getOrNull() ?: ""
        lock.withLock {
            val wait = (last[host] ?: 0L) + GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            last[host] = System.currentTimeMillis()
        }
        return Http.request("GET", url, mapOf("User-Agent" to UA, "Accept" to accept), readTimeoutMs = 30_000)
    }
}
