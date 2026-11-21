package app.fluvour.ai

import app.fluvour.reading.ClipLevels
import app.fluvour.reading.MiniJson

/** One Gemini suggestion: a search phrase and which of the learner's words it is meant to catch. */
data class ClipQuery(val query: String, val words: List<String>)

/** The prompt for clip search phrases and the reading of the answer. Pure JVM, so it is tested on a plain JVM. */
object ClipPrompt {
    /** Candidate pool for N wanted clips, and how many search phrases that takes (3 hits kept per phrase). */
    fun poolSize(n: Int) = (2 * n).coerceIn(12, 40)
    fun queryCount(n: Int) = (poolSize(n) + 2) / 3

    fun build(langName: String, levelCode: String, lang: String, learned: List<String>, count: Int): String {
        val scale = ClipLevels.scale(lang)
        val i = scale.codes.indexOf(levelCode).coerceAtLeast(0)
        val label = scale.labels[i]
        val frac = if (scale.top == 0) 0.5 else i.toDouble() / scale.top
        val style = when {
            frac <= 0.2 -> "slow, clear everyday speech: simple vlogs, daily routines, easy conversations"
            frac >= 0.8 -> "demanding listening: long interviews, documentaries, debates, podcasts, lectures"
            else -> "normal-speed talk on everyday and general-interest topics: vlogs, explainers, short interviews, travel and food"
        }
        val approx = if (scale.byRank) " (an approximate level: how common the words they know are)" else ""
        val words = if (learned.isEmpty()) "(none yet)" else learned.joinToString(", ")
        return """You choose YouTube search phrases for a language learner.
Language: $langName
Their level: $label$approx
Words they are learning, newest first: $words

Return exactly $count items as a JSON array, nothing else: [{"query": "...", "words": ["..."]}]
- "query": a natural spoken-style phrase in $langName, 3 to 8 words, about a topic where someone would use some of their words. Not a bare word, and not a request for a lesson, definition or "how to say" video.
- "words": 0 to 3 of their words (copied exactly from the list) that this phrase is meant to catch.
- Match their level: $style.
- Different topics for different items. No URLs, no video titles, no explanations."""
    }

    /** Reads the answer. Words not in [learned] are dropped, so the row never shows an invented word. Never throws. */
    fun parse(text: String, learned: List<String>, max: Int): List<ClipQuery> {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('['); val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val list = try { MiniJson.parse(cleaned.substring(start, end + 1)) as? List<*> } catch (e: Exception) { null } ?: return emptyList()
        val byLower = learned.associateBy { it.lowercase() }
        val seen = HashSet<String>()
        val out = ArrayList<ClipQuery>()
        for (item in list) {
            val m = item as? Map<*, *> ?: continue
            val q = (m["query"] as? String)?.trim().orEmpty()
            if (q.isEmpty() || !seen.add(q.lowercase())) continue
            val w = (m["words"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.lowercase()?.let(byLower::get) }.distinct().take(3)
            out += ClipQuery(q, w)
            if (out.size >= max) break
        }
        return out
    }
}
