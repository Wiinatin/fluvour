package app.fluvour.ai

import java.io.IOException

enum class AiError {
    NO_KEYS,          // no API key saved for this provider
    ALL_KEYS_LIMITED, // every key hit its quota: retry later or add another key
    KEYS_REJECTED,    // every key was refused (invalid or no permission)
    OFFLINE,          // the phone could not reach the service: other providers would fail too
    BUSY,             // the model is busy or gone on every try: retry later, or another provider may work
    OTHER             // anything else: every key would fail the same way
}

sealed interface GeminiResult {
    /** [json] is the whole response body; [model] is the model that answered (after any fallback). */
    data class Success(val json: String, val model: String) : GeminiResult
    data class Failure(val error: AiError, val message: String) : GeminiResult
}

data class PostResult(val status: Int, val body: String)

enum class KeyErrorKind { QUOTA, AUTH }

/** The rules of Momori's GeminiProvider and Torbito's classify_error, widened a little for the other providers. */
object GeminiErrors {
    /** The model (not the key) cannot answer right now: busy, down or gone. The next model may still work. */
    fun modelUnavailable(status: Int, body: String): Boolean {
        val text = body.uppercase()
        return status >= 500 || status == 404 || listOf("UNAVAILABLE", "OVERLOADED", "HIGH DEMAND", "NOT_FOUND", "MODEL_NOT_FOUND").any { it in text }
    }

    /**
     * Null means "not a key problem": every key would fail the same way. The HTTP status decides first (a busy or missing
     * model is never a quota, whatever words its answer contains); the words in the answer only decide for other statuses.
     * 402 (credits used up) counts as a quota.
     */
    fun classify(status: Int, body: String): KeyErrorKind? {
        val text = body.uppercase()
        if (status == 429 || status == 402) return KeyErrorKind.QUOTA
        if ("DAILY FREE ALLOCATION" in text) return KeyErrorKind.QUOTA // Cloudflare Workers AI: the 10,000 free neurons of the day are used up
        if (status == 401 || status == 403) return KeyErrorKind.AUTH
        if (status >= 500 || status == 404) return null
        if ("RESOURCE_EXHAUSTED" in text || "INSUFFICIENT_QUOTA" in text || "RATE_LIMIT" in text) return KeyErrorKind.QUOTA
        if (listOf("PERMISSION_DENIED", "UNAUTHENTICATED", "API_KEY_INVALID", "API KEY NOT VALID", "INVALID_API_KEY", "INVALID API KEY").any { it in text }) return KeyErrorKind.AUTH
        return null
    }
}

/**
 * One provider's calls through its rotating key pool (the rules of Momori's GeminiProvider). A quota error tries the next key (the key is
 * stamped only when it failed on every model, see [generate]); an invalid key is skipped without a stamp; anything else fails at once. A busy or missing model moves on
 * to the next model; a model that failed on every key rests for [REST_MS] and is tried last.
 * Pure JVM: the HTTP call is passed in ([post] sets the right auth header for its provider).
 */
class RotatingClient(
    private val pool: GeminiKeyPool,
    private val label: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** One POST. Throws IOException for network failures. */
    private val post: suspend (url: String, apiKey: String, body: String) -> PostResult
) {
    companion object { const val REST_MS = 90_000L }

    private val restingUntil = HashMap<String, Long>()

    private fun order(chain: List<String>): List<String> {
        val now = clock()
        val (rested, resting) = chain.partition { (synchronized(restingUntil) { restingUntil[it] } ?: 0L) <= now }
        return rested + resting
    }

    private fun rest(model: String) = synchronized(restingUntil) { restingUntil[model] = clock() + REST_MS }

    /**
     * [request] gives the URL and body for one model.
     *
     * A key is stamped as "hit a limit" only with proof: it got a quota error on EVERY model this call could use, so a
     * quota that belongs to one model (for example Gemini's strongest model being used up) never marks a key that still
     * works on the next model. Stamps are written once at the end, so a key that answered is never stamped.
     */
    suspend fun generate(models: List<String>, request: (model: String) -> Pair<String, String>): GeminiResult {
        val keys = pool.orderedKeys()
        if (keys.isEmpty()) return GeminiResult.Failure(AiError.NO_KEYS, "No $label key yet. Add one in Settings > AI keys.")
        val chain = order(models.distinct())
        var sawQuota = false
        var sawBusy = false
        var lastReply: String? = null
        val replies = mutableListOf<String>() // "…ab12 → 429": what each key answered, so a failure says exactly what happened
        val badKeys = mutableSetOf<String>() // refused as invalid: not tried again on the next model
        val quotaOn = HashMap<String, MutableSet<String>>() // key id -> models where that key got a quota error

        suspend fun stampProven() {
            for ((id, ms) in quotaOn) if (ms.size >= chain.size) pool.markLimited(id)
        }

        for (m in chain) {
            val (url, body) = request(m)
            var tried = 0
            var quotaHits = 0
            for (entry in keys) {
                if (entry.id in badKeys) continue
                tried++
                val result = try { post(url, entry.key, body) } catch (e: IOException) {
                    stampProven()
                    return GeminiResult.Failure(AiError.OFFLINE, "Could not reach $label: ${e.message ?: "no connection"}")
                }
                if (result.status in 200..299) {
                    stampProven()
                    return GeminiResult.Success(result.body, m)
                }
                replies += "…${entry.key.takeLast(4)} → ${result.status}"
                lastReply = "(${result.status}) ${result.body.replace(Regex("\\s+"), " ").take(140)}"
                when (GeminiErrors.classify(result.status, result.body)) {
                    KeyErrorKind.QUOTA -> {
                        quotaOn.getOrPut(entry.id) { mutableSetOf() } += m
                        sawQuota = true; quotaHits++
                    }
                    KeyErrorKind.AUTH -> badKeys += entry.id
                    null -> {
                        if (!GeminiErrors.modelUnavailable(result.status, result.body)) {
                            stampProven()
                            return GeminiResult.Failure(AiError.OTHER, "$label request failed (${result.status}): ${result.body.take(160)}. Replies: ${replies.joinToString(", ")}")
                        }
                        sawBusy = true; rest(m); break // every key would see the same: go straight to the next model
                    }
                }
            }
            if (tried > 0 && quotaHits == tried) rest(m)
        }
        stampProven()
        val detail = " Replies: ${replies.joinToString(", ")}. Last: $lastReply"
        return when {
            sawBusy -> GeminiResult.Failure(AiError.BUSY, "$label is busy right now.$detail")
            sawQuota -> GeminiResult.Failure(AiError.ALL_KEYS_LIMITED, "Every saved $label key has hit its limit.$detail")
            else -> GeminiResult.Failure(AiError.KEYS_REJECTED, "$label rejected the API key. Check it in Settings > AI keys.$detail")
        }
    }
}
