package app.fluvour.reading

import java.text.NumberFormat
import java.util.Locale

/** What the word lists know about one word: frequency rank (1 = most common) and an exam level code, either may be missing. */
data class LevelInfo(val rank: Int?, val code: String?)

/**
 * Phase 2A step 7: level tags (JLPT, HSK, CEFR) and frequency rank. Pure JVM, no Android imports.
 * Data lives in `assets/levels/<lang>.tsv`, built by `tools/levels/build_levels.py`:
 * `word <TAB> rank (0 = none) <TAB> code (may be empty)`.
 * Codes: ja `N1`-`N5`; zh `H1`-`H7` (HSK 3.0, H7 = 7-9) or `h1`-`h6` (HSK 2.0 only); en `A1`-`C2`. Other languages have rank only.
 */
class LevelTable(private val map: Map<String, LevelInfo>) {
    val size: Int get() = map.size

    /** Tries each candidate in order (dictionary form first, then the surface form); level and rank may come from different candidates. */
    fun find(vararg candidates: String?): LevelInfo? {
        var rank: Int? = null; var code: String? = null
        for (c in candidates) {
            val hit = map[c?.trim()?.lowercase() ?: continue] ?: continue
            if (rank == null) rank = hit.rank
            if (code == null) code = hit.code
        }
        return if (rank == null && code == null) null else LevelInfo(rank, code)
    }
}

object Levels {
    fun parse(lines: Sequence<String>): LevelTable {
        val m = HashMap<String, LevelInfo>(40_000)
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 2 || p[0].isEmpty()) continue
            val rank = p[1].toIntOrNull()?.takeIf { it > 0 }
            val code = p.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            if (rank != null || code != null) m.putIfAbsent(p[0], LevelInfo(rank, code))
        }
        return LevelTable(m)
    }

    /** "JLPT N3", "HSK 4", "HSK 7–9", "HSK 2.0 · 3", "CEFR B1"; null for an unknown code. */
    fun levelLabel(lang: String, code: String?): String? {
        if (code == null) return null
        return when (lang) {
            "ja" -> if (Regex("N[1-5]").matches(code)) "JLPT $code" else null
            "zh" -> when {
                code == "H7" -> "HSK 7–9"
                Regex("H[1-6]").matches(code) -> "HSK ${code[1]}"
                Regex("h[1-6]").matches(code) -> "HSK 2.0 · ${code[1]}"
                else -> null
            }
            "en" -> if (Regex("[ABC][12]").matches(code)) "CEFR $code" else null
            else -> null
        }
    }

    /** "Freq #1,107". The rank comes from subtitle / web text, so it is a guide to how common a word is, not an exam list. */
    fun rankLabel(rank: Int?): String? = rank?.let { "Freq #" + NumberFormat.getIntegerInstance(Locale.US).format(it) }

    /** Chips for the popup: exam level first, then frequency. */
    fun chips(lang: String, info: LevelInfo?): List<String> =
        listOfNotNull(levelLabel(lang, info?.code), rankLabel(info?.rank))
}
