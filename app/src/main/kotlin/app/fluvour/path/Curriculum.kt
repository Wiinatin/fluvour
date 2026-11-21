package app.fluvour.path

/** One target word of a unit: written form, reading, short English gloss. */
data class CurriculumWord(val word: String, val reading: String, val gloss: String)

/** One capped learning unit (about 8 to 12 words, 1 or 2 grammar points). No book text, no anchors. */
data class CurriculumUnit(
    val id: String,
    val order: Int,
    val title: String,
    val level: String,
    val minutes: Int,
    val canDo: String,
    val prerequisites: List<String>,
    val grammar: List<String>,
    val words: List<CurriculumWord>,
)

/** One path (for example Genki I) for one study language, units in learning order. */
data class CurriculumPath(
    val id: String,
    val title: String,
    val languageCode: String,
    val level: String,
    val units: List<CurriculumUnit>,
)

/**
 * Reads assets/curriculum/<lang>-<path>.tsv. Pure Kotlin (no Android) so tools/curriculumtest can run it on a plain JVM.
 *
 * Lines starting with # and blank lines are ignored. The line "@path<TAB>id<TAB>title<TAB>languageCode<TAB>level" names the path.
 * A unit line has 9 tab-separated columns: id, order, title, level, minutes, canDo, prerequisite ids (comma),
 * grammar points (|), words (| between words, word;reading;gloss inside one). Bad unit lines are skipped.
 */
object CurriculumParser {
    fun parse(text: String): CurriculumPath? {
        var meta: List<String>? = null
        val units = ArrayList<CurriculumUnit>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) continue
            if (line.startsWith("@path\t")) {
                val p = line.split('\t')
                if (p.size >= 5) meta = p
                continue
            }
            parseUnit(line)?.let { units.add(it) }
        }
        val m = meta ?: return null
        return CurriculumPath(m[1], m[2], m[3], m[4], units.sortedBy { it.order })
    }

    fun parseUnit(line: String): CurriculumUnit? {
        val p = line.split('\t')
        if (p.size < 9) return null
        val order = p[1].toIntOrNull() ?: return null
        val minutes = p[4].toIntOrNull() ?: return null
        if (p[0].isBlank() || p[2].isBlank()) return null
        val words = p[8].split('|').mapNotNull { w ->
            val f = w.split(';')
            if (f.size < 2 || f[0].isBlank() || f[1].isBlank()) null
            else CurriculumWord(f[0], f[1], f.getOrElse(2) { "" })
        }
        if (words.isEmpty()) return null
        return CurriculumUnit(
            id = p[0], order = order, title = p[2], level = p[3], minutes = minutes, canDo = p[5],
            prerequisites = p[6].split(',').filter { it.isNotBlank() },
            grammar = p[7].split('|').filter { it.isNotBlank() },
            words = words,
        )
    }
}
