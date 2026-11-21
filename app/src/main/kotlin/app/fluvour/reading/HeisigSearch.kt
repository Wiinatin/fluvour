package app.fluvour.reading

/**
 * Search box filter for the Heisig practice grid. Pure Kotlin (no Android), so it is checked on a plain JVM in tools/practicetest.
 *
 * It works on any list through three small selectors, so it does not need [HeisigEntry] (that class lives in a file that
 * imports Android). For the practice grid call:
 *     HeisigSearch.filter(all, query, { it.ch }, { it.frame }, { it.keyword })
 *
 * A kanji matches when any of these is true:
 *  - its keyword contains the typed text, ignoring case ("ou" finds "four" and "mouth")
 *  - the typed text contains the kanji itself (typing or pasting 日 finds 日, pasting 日本 finds both)
 *  - the typed text is a number and the frame number starts with it ("6" finds 6, 60 to 69, 600 and up; "#6" and "６" work too)
 * An empty or blank query returns the whole list. The order of [all] is kept (Heisig order).
 */
object HeisigSearch {
    fun <T> filter(all: List<T>, query: String, ch: (T) -> String, frame: (T) -> Int, keyword: (T) -> String): List<T> {
        val q = normalise(query)
        if (q.isEmpty()) return all
        val lower = q.lowercase()
        val digits = lower.removePrefix("#").takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        return all.filter { e ->
            val k = ch(e)
            keyword(e).lowercase().contains(lower) ||
                (k.isNotEmpty() && q.contains(k)) ||
                (digits != null && frame(e).toString().startsWith(digits))
        }
    }

    /** Trims, and turns full-width digits and the full-width # (typed on a Japanese keyboard) into plain ones. */
    private fun normalise(s: String): String = buildString {
        for (c in s.trim()) append(
            when (c) {
                in '０'..'９' -> '0' + (c - '０')
                '＃' -> '#'
                else -> c
            }
        )
    }
}
