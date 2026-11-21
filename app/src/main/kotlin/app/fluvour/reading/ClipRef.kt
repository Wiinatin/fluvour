package app.fluvour.reading

/**
 * The few seconds of a video a clip card plays: the video's link and the mined subtitle line's start and end (milliseconds from the
 * start of the video). Nothing of the video is stored, the card only remembers where to find it. Pure JVM, no Android.
 */
data class ClipRef(val url: String, val startMs: Long, val endMs: Long, val words: List<ClipWord> = emptyList()) {
    /**
     * When the word is spoken in this clip, in video milliseconds (from, to), or null when the clip has no word timing or the word is not in
     * it. Each timed piece lasts until the next one starts (the last until the line ends); a word that is only part of a piece gets its
     * share of that piece by characters.
     */
    fun wordSpan(word: String): Pair<Long, Long>? {
        val target = word.filterNot { it.isWhitespace() }
        if (words.isEmpty() || target.isEmpty()) return null
        val flat = StringBuilder(); val piece = ArrayList<Int>(); val inPiece = ArrayList<Int>(); val sizes = IntArray(words.size)
        words.forEachIndexed { i, w -> for (ch in w.text) if (!ch.isWhitespace()) { flat.append(ch); piece += i; inPiece += sizes[i]++ } }
        val at = flat.toString().indexOf(target, ignoreCase = true)
        if (at < 0) return null
        val last = at + target.length - 1
        fun pieceEnd(i: Int) = words.getOrNull(i + 1)?.ms ?: endMs
        fun share(i: Int, chars: Int): Long { val d = (pieceEnd(i) - words[i].ms).coerceAtLeast(0L); return words[i].ms + d * chars / sizes[i] }
        val from = share(piece[at], inPiece[at])
        val to = share(piece[last], inPiece[last] + 1)
        return from to maxOf(to, from + 1)
    }

    /**
     * Where each timed piece of the clip's line sits in [sentence] (the card's example), for the karaoke fill: the character range of the piece
     * and when it starts. Spaces do not count. Null when the clip has no word timing, or when the pieces do not spell the sentence
     * (the example was edited, or cleaned differently from the caption), because lighting the wrong letters is worse than lighting nothing.
     */
    fun litSpans(sentence: String?): List<LitSpan>? = WordTiming.pieceSpans(sentence, words.map { it.ms to it.text })

    /**
     * The estimate for a clip that does not know when each word starts (manual captions, older clips, or pieces that do not spell the sentence):
     * the line's time (start to end) is split over the sentence's words by their length (by beats for Japanese, see WordTiming.estimate). [tokens] are the sentence split into
     * words by WordSplitter (it also handles Japanese, Chinese and Thai, which have no spaces). Null when there are fewer than two words.
     */
    fun estimatedSpans(sentence: String?, tokens: List<Token>): List<LitSpan>? {
        if (sentence.isNullOrBlank() || endMs <= startMs) return null
        val ws = tokens.filter { it.isWord && it.start >= 0 && it.end > it.start && it.end <= sentence.length }
        if (ws.size < 2) return null
        val starts = WordTiming.estimate(sentence, ws, startMs, endMs) ?: return null   // same weighing as the Watch screen (beats for Japanese)
        return ws.mapIndexed { i, t -> LitSpan(t.start, t.end, starts[i]) }
    }

    /** The video's cover picture (YouTube's own thumbnail), known from the link alone, so it can fill the frame while the video loads. Null for other links. */
    val cover: String? get() = videoId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    companion object {
        private val ids = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"), Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})")
        )
        /** The 11-character YouTube video id in a link, or null. */
        fun videoId(url: String): String? = ids.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

        /** Longest clip, so a very long subtitle line does not play for half a minute. */
        const val MAX_MS = 8000L

        private val wordItem = Regex("\"t\"\\s*:\\s*(\\d+)\\s*,\\s*\"w\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val jsonEscape = Regex("\\\\(u[0-9a-fA-F]{4}|.)")
        private fun unescape(s: String) = jsonEscape.replace(s) { m ->
            val c = m.groupValues[1]
            when { c.length == 5 && c[0] == 'u' -> c.substring(1).toInt(16).toChar().toString(); c == "n" -> "\n"; c == "t" -> "\t"; c == "r" -> "\r"; c == "b" -> "\b"; c == "f" -> "\u000c"; else -> c }
        }

        /** The timed pieces of the subtitle line saved in the sentence's `position` text, or empty (older clips, manual subtitles). */
        fun words(position: String?): List<ClipWord> {
            val p = position ?: return emptyList()
            val from = p.indexOf("\"words\"")
            if (from < 0) return emptyList()
            return wordItem.findAll(p, from).mapNotNull { m -> m.groupValues[1].toLongOrNull()?.let { ClipWord(it, unescape(m.groupValues[2])) } }.toList()
        }

        private val start = Regex("\"startMs\"\\s*:\\s*(\\d+)")
        private val end = Regex("\"endMs\"\\s*:\\s*(\\d+)")

        /** Start and end from the sentence's `position` text (see SentenceRepository.videoPosition), or null when it is not a video position. */
        fun times(position: String?): Pair<Long, Long>? {
            val p = position ?: return null
            val s = start.find(p)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val e = end.find(p)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            return if (e > s) s to e else null
        }
    }
}

/** One timed piece of the clip's subtitle line: when it starts (ms from the start of the video) and its text. */
data class ClipWord(val ms: Long, val text: String)

/** A timed piece of the sentence for the karaoke fill: characters [from, to) of the sentence, spoken from [ms] (video milliseconds). */
data class LitSpan(val from: Int, val to: Int, val ms: Long)
