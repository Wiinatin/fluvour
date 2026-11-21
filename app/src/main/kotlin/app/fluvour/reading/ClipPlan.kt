package app.fluvour.reading

/**
 * The plain rules of the clip pipeline (roadmap 4.2): sizes, the cheap cut before subtitles are read, the 24 h cache check.
 * Pure JVM, no Android imports, so it is tested on a plain JVM (tools/cliptest). The constants are guesses, tune them here.
 */
object ClipPlan {
    /** How long a clip choice is reused before Gemini is asked again. */
    const val CACHE_MS = 24L * 60 * 60 * 1000
    /** With fewer learner cards than this Gemini has too little to work with, and the Watch row stays hidden. */
    const val MIN_CARDS = 5
    /** Learned words handed to Gemini (still-learning words first, then known ones). */
    const val LEARNED_WORDS = 30
    /** NewPipe searches at once, and hits kept per search phrase. */
    const val SEARCH_PARALLEL = 3
    const val HITS_PER_QUERY = 3
    /** Subtitle reads at once, and videos per batch (the list grows one batch at a time). */
    const val READ_PARALLEL = 3
    const val BATCH = 12

    /**
     * True when opening a video failed because the video itself is gone or cannot be played (private, removed, age or region locked, no stream),
     * not because the network or the extractor failed. Only then is a clip marked unavailable. Looks at the class names so it works across the plugin boundary.
     */
    fun isGone(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 5) {
            var c: Class<*>? = t.javaClass
            while (c != null) { if (c.simpleName == "ContentNotAvailableException") return true; c = c.superclass }
            if (t.message?.startsWith("No playable stream") == true) return true
            t = t.cause
        }
        return false
    }

    /** Library rows decoded per run: a random sample of the rows that pass the level prefilter, so a big library stays cheap to rank. */
    const val LIBRARY_POOL = 400
    /** A clip shown in the list is left out of the library pool for this long, so the reload button brings different clips. */
    const val SHOWN_COOLDOWN_MS = 3L * 24 * 60 * 60 * 1000

    /**
     * Video levels worth loading from the library for a learner at [declaredIndex]: one below to one above, and at the top two levels
     * everything from one below up (percentages saturate there, see [ClipRanking]). Cuts thousands of rows to a few hundred before any word list is read.
     */
    fun libraryLevels(scale: ClipLevels.Scale, declaredIndex: Int): List<String> {
        if (declaredIndex !in scale.codes.indices) return emptyList()
        val lo = (declaredIndex - 1).coerceAtLeast(0)
        val hi = if (scale.isTopTwo(declaredIndex)) scale.top else (declaredIndex + 1).coerceAtMost(scale.top)
        return scale.codes.subList(lo, hi + 1)
    }

    /** The part of a YouTube link that names the video, so `youtu.be/ID`, `watch?v=ID` and `www.youtube.com/watch?v=ID&t=5` match each other. */
    fun videoKey(url: String): String {
        val u = url.trim()
        Regex("[?&]v=([\\w-]{6,})").find(u)?.let { return it.groupValues[1] }
        Regex("(?:youtu\\.be/|/shorts/|/embed/|/live/)([\\w-]{6,})").find(u)?.let { return it.groupValues[1] }
        return u
    }

    /**
     * The cheap cut: keeps hits of 30 s to 5 min that are not in [skipKeys] (watch history, by [videoKey]), without repeats, at most [limit].
     */
    fun <T> cheapCut(hits: List<T>, url: (T) -> String, durationMs: (T) -> Long, skipKeys: Set<String>, limit: Int): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>()
        for (h in hits) {
            val d = durationMs(h)
            if (d < ClipRanking.MIN_MS || d > ClipRanking.MAX_MS) continue
            val k = videoKey(url(h))
            if (k in skipKeys || !seen.add(k)) continue
            out += h
            if (out.size >= limit) break
        }
        return out
    }

    /** First hit of every list, then the second of every list, and so on, so a small pool is not all from the first search phrases. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        var i = 0
        while (true) {
            var any = false
            for (l in lists) if (i < l.size) { out += l[i]; any = true }
            if (!any) break
            i++
        }
        return out
    }

    /** What a saved clip choice was made for. A different level, clip count or language makes it stale. */
    fun cacheKey(lang: String, levelCode: String, count: Int) = "$lang|$levelCode|$count"
    fun cacheValid(savedAt: Long, now: Long, savedKey: String, key: String) = savedKey == key && now - savedAt in 0..CACHE_MS

    /** The learner words shown in the Watch row: every word Gemini aimed a phrase at, once each, in order. */
    fun rowWords(wordLists: List<List<String>>): List<String> = wordLists.flatten().distinct()
}
