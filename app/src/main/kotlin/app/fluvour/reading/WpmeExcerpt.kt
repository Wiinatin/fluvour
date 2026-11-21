package app.fluvour.reading

/** The "See more" text: the section heading above the passage (for example "2. others") and the passage itself. */
data class WpmeExcerpt(val heading: String, val text: String)

/** Where a character offset of a chunk sits for the reader: paragraph number, character inside it, and how far through the text it is (0..1). */
data class WpmePosition(val para: Int, val ch: Int, val percent: Float)

/**
 * Pure (no Android): cuts the passage for "See more" out of the user's own book text at tap time, and works out the reader
 * position for "Read in the book". Paragraphs are counted exactly as the reader counts them (split on newlines, blank ones dropped).
 * Nothing here is ever stored.
 */
object WpmePassage {
    const val MAX_CHARS = 700
    private const val SHORT = 80
    private val HEADING = Regex("^\\d+\\.\\s+\\S.{0,78}$")
    private val SENTENCE_END = Regex("(?<=[.!?][\"\u201D\u2019)]?)\\s+")

    private class Para(val text: String, val start: Int)

    private fun paragraphs(text: String): List<Para> {
        val out = ArrayList<Para>()
        var pos = 0
        for (line in text.split('\n')) {
            if (line.isNotBlank()) out.add(Para(line, pos))
            pos += line.length + 1
        }
        return out
    }

    private fun paraIndexAt(ps: List<Para>, offset: Int): Int {
        var best = 0
        for ((i, p) in ps.withIndex()) { if (p.start <= offset) best = i else break }
        return best
    }

    /** Reader position of `offset`: the reader counts non-blank paragraphs and adds up their lengths for the percentage. */
    fun position(text: String, offset: Int): WpmePosition {
        val ps = paragraphs(text)
        if (ps.isEmpty()) return WpmePosition(0, 0, 0f)
        val i = paraIndexAt(ps, offset)
        val ch = (offset - ps[i].start).coerceIn(0, ps[i].text.length)
        val total = ps.sumOf { it.text.length }.coerceAtLeast(1)
        val before = ps.take(i).sumOf { it.text.length }
        return WpmePosition(i, ch, (before + ch) / total.toFloat())
    }

    private fun isHeading(p: String) = HEADING.matches(p.trim()) && !p.trim().endsWith(".")

    private const val MAX_LIST_LINES = 8

    private fun headingAbove(ps: List<Para>, i: Int): Int { var j = i - 1; while (j >= 0) { if (isHeading(ps[j].text)) return j; j-- }; return -1 }

    /** Consecutive short lines from `start` (a word list under a heading), up to the next heading, a long paragraph or MAX_LIST_LINES. */
    private fun block(ps: List<Para>, start: Int): String {
        val out = ArrayList<String>()
        var k = start
        while (k < ps.size && out.size < MAX_LIST_LINES && !isHeading(ps[k].text) && (out.isEmpty() || ps[k].text.length < SHORT)) { out.add(ps[k].text.trim()); k++ }
        return out.joinToString("\n")
    }

    /**
     * What "See more" shows for a hit. In running prose: the paragraph with the numbered heading above it ("2. others"), cut at
     * sentence ends around the hit when longer than MAX_CHARS. In a heading such as "3. egomaniac": that heading and what follows it.
     * In a short line of a word list: the heading above and the list under it.
     */
    fun excerpt(text: String, offset: Int, max: Int = MAX_CHARS): WpmeExcerpt? {
        val ps = paragraphs(text)
        if (ps.isEmpty()) return null
        val i = paraIndexAt(ps, offset)
        val p = ps[i]
        if (isHeading(p.text)) {
            val next = ps.getOrNull(i + 1) ?: return WpmeExcerpt("", p.text.trim())
            val body = if (next.text.length >= SHORT) cut(next.text, 0, max) else block(ps, i + 1)
            if (body.isNotBlank()) return WpmeExcerpt(p.text.trim(), body)
            // A numbered line followed by more numbered lines (a list such as "2. maternity ... 3. maternal ..."): show that line and its neighbours.
            val items = ArrayList<String>(); var k = i
            while (k < ps.size && items.size < 4 && isHeading(ps[k].text)) { items.add(ps[k].text.trim()); k++ }
            return WpmeExcerpt("", items.joinToString("\n"))
        }
        val h = headingAbove(ps, i)
        val heading = if (h >= 0) ps[h].text.trim() else ""
        if (p.text.length < SHORT) return WpmeExcerpt(heading, block(ps, if (h >= 0) h + 1 else i))
        return WpmeExcerpt(heading, cut(p.text, (offset - p.start).coerceIn(0, p.text.length), max))
    }

    private fun cut(p: String, hit: Int, max: Int): String {
        if (p.length <= max) return p
        val sentences = ArrayList<Pair<Int, String>>()
        var pos = 0
        for (s in p.split(SENTENCE_END)) { val at = p.indexOf(s, pos); sentences.add(at to s); pos = at + s.length }
        var k = sentences.indexOfLast { it.first <= hit }.coerceAtLeast(0)
        var from = k; var to = k
        var len = sentences[k].second.length
        // Grow forwards first (the explanation follows the word), then backwards, while it fits.
        while (true) {
            val next = sentences.getOrNull(to + 1)
            if (next != null && len + 1 + next.second.length <= max) { to++; len += 1 + next.second.length; continue }
            val prev = sentences.getOrNull(from - 1)
            if (prev != null && len + 1 + prev.second.length <= max) { from--; len += 1 + prev.second.length; continue }
            break
        }
        val body = sentences.subList(from, to + 1).joinToString(" ") { it.second }
        val capped = if (body.length > max) body.take(max).trimEnd() + "\u2026" else body
        return (if (from > 0) "\u2026 " else "") + capped + (if (to < sentences.size - 1 && !capped.endsWith("\u2026")) " \u2026" else "")
    }
}
