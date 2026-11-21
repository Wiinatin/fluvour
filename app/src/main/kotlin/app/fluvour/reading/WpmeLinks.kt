package app.fluvour.reading

/**
 * The link from one deck word to its parts, as built from the user's own book (see `ai/WpmeLinksPrompt.kt`).
 * [parts] are normalised: lower case, letters and hyphens only (`mis-`, `anthrop`, `-e`). An empty [parts] means the word was
 * tried and has no clean breakdown, so it stays a plain word card and is not asked again.
 */
data class WpmeLink(val key: String, val parts: List<String>, val gloss: String)

/** Pure (no Android): the small file the links are kept in, `wpme-links.tsv`: key, parts joined by `|`, gloss. No book text is ever stored here. */
object WpmeLinks {
    const val FILE = "wpme-links.tsv"

    /** The letters of a part without its hyphens: `-ology` gives `ology`, `mis-` gives `mis`. */
    fun core(part: String): String = part.lowercase().filter { it in 'a'..'z' }

    /** Short plain text on one line: tabs and line breaks become spaces. */
    fun cleanGloss(s: String, max: Int = 100): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.substring(0, max).trimEnd()
    }

    fun encode(links: Collection<WpmeLink>): String =
        links.joinToString("") { it.key + "\t" + it.parts.joinToString("|") + "\t" + cleanGloss(it.gloss) + "\n" }

    /** Bad lines are skipped. A later line for the same key wins. */
    fun decode(lines: Sequence<String>): Map<String, WpmeLink> {
        val out = LinkedHashMap<String, WpmeLink>()
        for (l in lines) {
            if (l.isBlank()) continue
            val p = l.split('\t')
            if (p.size < 2 || p[0].isBlank()) continue
            val parts = p[1].split('|').map { it.trim() }.filter { it.isNotEmpty() }
            out[p[0]] = WpmeLink(p[0], parts, p.getOrElse(2) { "" })
        }
        return out
    }
}
