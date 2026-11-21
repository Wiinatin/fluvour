package app.fluvour.reading

/** One chunk of the imported book, as the reader stores it (title as `EpubReader` gives it, text with one paragraph per line). */
class WpmeChunk(val title: String, val text: String)

/** Where the book teaches one deck item: the chunk (index in the order `EpubReader` returned them), the character offset inside that chunk's text, and the section heading it sits under ("" when none). Positions only, no book text. */
data class WpmeLocation(val key: String, val chunk: Int, val offset: Int, val section: String)

/**
 * Pure (no Android): finds where Norman Lewis's book teaches each deck word. Search order for a word: the teaching sections
 * (IDEAS, ORIGINS AND RELATED WORDS, REVIEW OF ETYMOLOGY) of the book chapter the deck's sub-deck belongs to, then any other
 * section of that chapter, then the teaching sections anywhere, then anywhere. The earliest hit in a tier wins. Within the teaching sections a hit in running prose beats a hit in a heading or word list.
 */
object WpmeLocator {
    const val FILE = "wpme-locator.tsv"
    val TEACHING = setOf("IDEAS", "ORIGINS AND RELATED WORDS")
    private val HEADINGS = TEACHING + setOf("REVIEW OF ETYMOLOGY", "USING THE WORDS", "CHAPTER REVIEW", "TEASER QUESTIONS FOR THE AMATEUR ETYMOLOGIST", "TEASER PREVIEW")
    private val SESSION = Regex("^SESSION \\d+$")
    private val CHAPTER_NO = Regex("^(\\d+)\\.\\s")
    private val CHUNK_SUFFIX = Regex("\\s*\\(\\d+\\)$")

    /**
     * Deck sub-deck number NN ("NN - Chapter 5 ...") to the book chapter that teaches it. The brief intermissions sit inside the
     * chapter they follow in the book. Null for the comprehensive tests (10, 17, 24) and the appendix: they teach no words, so they give no chapter hint.
     */
    fun bookChapterFor(deckNo: Int): Int? = when (deckNo) {
        0 -> 1
        1, 2 -> 3
        3, 4 -> 4
        5, 6 -> 5
        7, 8 -> 6
        9 -> 7
        11, 12 -> 9
        13, 14 -> 10
        15 -> 11
        16 -> 12
        18, 19 -> 14
        20, 21 -> 15
        22, 23 -> 16
        else -> null
    }

    private class Index(val chunk: WpmeChunk, val lc: String, val bookChapter: Int?, val marks: IntArray, val labels: Array<String>) {
        fun sectionAt(offset: Int): String {
            var lo = 0; var hi = marks.size - 1; var best = 0
            while (lo <= hi) { val mid = (lo + hi) ushr 1; if (marks[mid] <= offset) { best = mid; lo = mid + 1 } else hi = mid - 1 }
            return labels[best]
        }
    }

    private fun buildIndex(chunks: List<WpmeChunk>): List<Index> {
        var carry = ""
        var prevBase = ""
        return chunks.map { c ->
            val base = CHUNK_SUFFIX.replace(c.title, "")
            if (base != prevBase) carry = ""
            prevBase = base
            val marks = ArrayList<Int>(); val labels = ArrayList<String>()
            marks.add(0); labels.add(carry)
            var pos = 0
            for (line in c.text.split('\n')) {
                val t = line.trim()
                val label = when { t in HEADINGS -> t; SESSION.matches(t) -> "SESSION"; else -> null }
                if (label != null) { marks.add(pos); labels.add(label); carry = label }
                pos += line.length + 1
            }
            val lc = c.text.lowercase().let { if (it.length == c.text.length) it else c.text }
            Index(c, lc, CHAPTER_NO.find(base)?.groupValues?.get(1)?.toIntOrNull(), marks.toIntArray(), labels.toTypedArray())
        }
    }

    /** What to look for in the book for one deck word: the word, the word without a leading "to ", then the parts of "a, b" and "a / b" and "a or b". */
    fun variants(form: String): List<String> {
        val f = WpmeData.norm(form)
        val out = LinkedHashSet<String>()
        out.add(f)
        if (f.startsWith("to ")) out.add(f.removePrefix("to "))
        for (p in f.split(Regex("\\s*,\\s*|\\s+/\\s+|\\s+or\\s+"))) {
            val q = p.trim().removePrefix("to ")
            if (q.length >= 2) out.add(q)
        }
        return out.filter { it.length >= 2 }
    }

    /** What to look for for a root, prefix or suffix: each form in the card ("aequus (equ-)", "opsis or optikos"), hyphens dropped, 3 letters or more. */
    fun elementVariants(form: String): List<String> {
        val out = LinkedHashSet<String>()
        for (p in WpmeData.norm(form).split(Regex("[,()/]|\\s+or\\s+"))) {
            val q = p.trim().replace("-", "").replace(Regex("\\s*\\(as in [^)]*\\)"), "").trim()
            if (q.length >= 3 && q.all { it.isLetter() }) out.add(q)
        }
        return out.toList()
    }

    private fun isWordChar(c: Char) = c.isLetter()

    private const val PROSE = 80

    private fun paragraphLength(ix: Index, o: Int): Int {
        val t = ix.chunk.text
        val from = t.lastIndexOf('\n', o - 1) + 1
        val to = t.indexOf('\n', o).let { if (it < 0) t.length else it }
        return to - from
    }

    /** Offset of the first whole-word hit of w in the chunk that `accept` allows, or -1. */
    /** True when the line holds a fill-in blank (`_____`): an exercise, never an explanation. */
    private fun isExercise(ix: Index, o: Int): Boolean {
        val t = ix.chunk.text
        val from = t.lastIndexOf('\n', o - 1) + 1
        val to = t.indexOf('\n', o).let { if (it < 0) t.length else it }
        // A short line with a blank is an exercise item; a long paragraph that ends with a question and a blank is still the explanation.
        return to - from < 120 && t.indexOf("___", from).let { it in 0 until to }
    }

    /** Like [firstHit] but the word only has to start with [w] (a stem), outside exercises. */
    private fun firstStartHit(ix: Index, w: String, accept: (Int) -> Boolean): Int {
        var i = ix.lc.indexOf(w)
        while (i >= 0) {
            val before = if (i == 0) ' ' else ix.lc[i - 1]
            if (!isWordChar(before) && !isExercise(ix, i) && ix.bookChapter !in TEST_CHAPTERS && accept(i)) return i
            i = ix.lc.indexOf(w, i + 1)
        }
        return -1
    }

    /** Book chapters that are tests and self-checks (the comprehensive tests and "How to Check Your Standing"): they test words, they never teach them. */
    private val TEST_CHAPTERS = setOf(8, 13, 17, 18)

    private val EXERCISE = setOf("SESSION", "REVIEW OF ETYMOLOGY", "USING THE WORDS", "CHAPTER REVIEW", "TEASER QUESTIONS FOR THE AMATEUR ETYMOLOGIST", "TEASER PREVIEW")

    private fun firstHit(ix: Index, w: String, accept: (Int) -> Boolean): Int {
        var i = ix.lc.indexOf(w)
        while (i >= 0) {
            val before = if (i == 0) ' ' else ix.lc[i - 1]
            val after = if (i + w.length >= ix.lc.length) ' ' else ix.lc[i + w.length]
            if (!isWordChar(before) && !isWordChar(after) && !isExercise(ix, i) && ix.bookChapter !in TEST_CHAPTERS && accept(i)) return i
            i = ix.lc.indexOf(w, i + 1)
        }
        return -1
    }

    /** One location per word, root, prefix or suffix item that the book mentions. Elements are only taken from teaching sections (a root such as "par" is a common word elsewhere). */
    fun locate(chunks: List<WpmeChunk>, items: List<WpmeItem>): Map<String, WpmeLocation> {
        val idx = buildIndex(chunks)
        val out = HashMap<String, WpmeLocation>()
        val ordered = items.filter { it.kind == WpmeData.WORD } + items.filter { it.kind != WpmeData.WORD }
        for (item in ordered) {
            val isEl = item.kind != WpmeData.WORD
            val hint = bookChapterFor(item.chapter)
            for (v in (if (isEl) elementVariants(item.form) else variants(item.form))) {
                // Tiers: 1 teaching prose in the chapter, 2 teaching line in the chapter, 3 any section of the chapter,
                // 4 teaching prose anywhere, 5 teaching line anywhere, 6 anywhere. Prose is a paragraph of PROSE chars or more;
                // shorter lines are headings and word lists, which name a word without explaining it.
                val found = arrayOfNulls<WpmeLocation>(7)
                for ((ci, ix) in idx.withIndex()) {
                    val inHint = hint != null && ix.bookChapter == hint
                    val teaching = { o: Int -> ix.sectionAt(o) in TEACHING }
                    val prose = { o: Int -> teaching(o) && paragraphLength(ix, o) >= PROSE }
                    fun hit(tier: Int, o: Int) { if (o >= 0 && found[tier] == null) found[tier] = WpmeLocation(item.key, ci, o, ix.sectionAt(o)) }
                    if (inHint) {
                        hit(1, firstHit(ix, v, prose)); if (found[1] != null) break
                        if (found[2] == null) hit(2, firstHit(ix, v, teaching))
                        if (found[3] == null && !isEl) hit(3, firstHit(ix, v) { ix.sectionAt(it) !in EXERCISE })
                    }
                    if (found[4] == null) hit(4, firstHit(ix, v, prose))
                    if (found[5] == null) hit(5, firstHit(ix, v, teaching))
                    if (found[6] == null && !isEl) hit(6, firstHit(ix, v) { ix.sectionAt(it) !in EXERCISE })
                }
                // A root is often explained through its words ("egocentric" for centrum): the first example word that is located in
                // this item's own chapter beats a mention of the root in some other chapter.
                val viaExample = if (isEl && found[1] == null && found[2] == null) item.examples.firstNotNullOfOrNull { ex ->
                    out["W:" + WpmeData.norm(ex)]?.takeIf { l -> hint != null && idx[l.chunk].bookChapter == hint }?.let { l -> WpmeLocation(item.key, l.chunk, l.offset, l.section) }
                } else null
                val best = found[1] ?: found[2] ?: viaExample ?: (3..6).firstNotNullOfOrNull { found[it] }
                if (best != null) { out[item.key] = best; break }
            }
            // A word the book only teaches through a related form (pungency: "pungent"; vivacity: "vivacious"): the first teaching
            // passage in its chapter, then anywhere, that has a word starting with the stem of the word.
            if (!isEl && item.key !in out) {
                val base = WpmeData.norm(item.form).removePrefix("to ").filter { it.isLetter() }
                stemLoop@ for (cut in 1..4) {
                    val stem = base.dropLast(cut)
                    if (stem.length < 5) break
                    for (pass in 0..1) for ((ci, ix) in idx.withIndex()) {
                        if (pass == 0 && !(hint != null && ix.bookChapter == hint)) continue
                        val o = firstStartHit(ix, stem) { ix.sectionAt(it) in TEACHING }
                        if (o >= 0) { out[item.key] = WpmeLocation(item.key, ci, o, ix.sectionAt(o)); break@stemLoop }
                    }
                }
            }
        }
        return out
    }

    fun encode(list: Collection<WpmeLocation>): String =
        list.sortedBy { it.key }.joinToString("\n") { listOf(it.key.replace('\t', ' '), it.chunk.toString(), it.offset.toString(), it.section).joinToString("\t") }

    fun decode(lines: Sequence<String>): Map<String, WpmeLocation> {
        val m = HashMap<String, WpmeLocation>(1_200)
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 4) continue
            val c = p[1].toIntOrNull() ?: continue
            val o = p[2].toIntOrNull() ?: continue
            m[p[0]] = WpmeLocation(p[0], c, o, p[3])
        }
        return m
    }
}
