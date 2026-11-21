package app.fluvour.path

/**
 * One word of a Japanese text as the splitter reports it. Plain data, so the check below runs on a plain JVM without Kuromoji.
 * [pos], [pos2] and [pos3] are the first three IPADIC part-of-speech levels (for example 名詞 / 固有名詞 / 人名).
 */
data class JaToken(val surface: String, val base: String, val pos: String, val pos2: String, val pos3: String, val start: Int)

/**
 * What a lesson or a unit test may use. [listed] are the words shown to the AI, in priority order: the words of the unit(s) being taught
 * or tested, then cards the learner has held for [TestRules.MATURE_WORD_DAYS] days or more, then the words of earlier units.
 * [readings] (kana forms) also count as known. [kanji] are Heisig kanji at [TestRules.MATURE_KANJI_DAYS] days of interval or more.
 */
class KnownPool(val listed: List<String>, readings: Collection<String> = emptyList(), val kanji: Set<Char> = emptySet()) {
    val all: Set<String> = (listed + readings).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    /** Words of two or more characters, longest first: a phrase of the pool counts as known wherever it appears in a text. */
    val phrases: List<String> = all.filter { it.length >= 2 }.sortedByDescending { it.length }
}

/**
 * The known-word check (pure). A text passes when every Japanese word in it is known: in the pool, a particle or ます/です form,
 * a person's name, or written only with kanji the learner has matured. [tokens] come from the splitter (Android: [app.fluvour.reading.WordSplitter]).
 */
object VocabCheck {
    /** Light verbs the grammar of every level uses. */
    private val ALWAYS = setOf("する", "ある", "いる", "なる")
    private val FUNCTION_POS = setOf("助詞", "助動詞", "記号")

    fun isJapanese(c: Char) = c in '\u3041'..'\u30FF' || c in '\u3400'..'\u9FFF' || c == '\u3005'
    fun isKanji(c: Char) = c in '\u3400'..'\u9FFF'

    /** The words of [text] that are not allowed (empty means the text passes). Text without Japanese characters always passes. */
    fun outside(text: String, pool: KnownPool, tokens: List<JaToken>): List<String> {
        val covered = BooleanArray(text.length)
        for (p in pool.phrases) {
            var i = text.indexOf(p)
            while (i >= 0) { for (k in i until i + p.length) covered[k] = true; i = text.indexOf(p, i + 1) }
        }
        val bad = LinkedHashSet<String>()
        for (t in tokens) {
            if (t.surface.none { isJapanese(it) }) continue
            val end = minOf(text.length, t.start + t.surface.length)
            if (t.start in text.indices && end > t.start && (t.start until end).all { covered[it] }) continue
            if (!allowed(t, pool)) bad.add(t.surface)
        }
        return bad.toList()
    }

    private fun allowed(t: JaToken, pool: KnownPool): Boolean {
        if (t.pos in FUNCTION_POS) return true
        if (t.pos == "接頭詞" && (t.surface == "お" || t.surface == "ご")) return true
        if (t.pos == "名詞") {
            if (t.pos2 == "非自立") return true
            if ((t.pos2 == "固有名詞" || t.pos2 == "接尾") && t.pos3 == "人名") return true   // names of people, and さん, くん after them
        }
        if ((t.pos == "動詞" || t.pos == "形容詞") && (t.pos2 == "非自立" || t.pos2 == "接尾")) return true
        if (t.base in ALWAYS) return true
        if (t.surface in pool.all || t.base in pool.all) return true
        val kanji = t.surface.filter { isKanji(it) }
        return kanji.isNotEmpty() && kanji.all { it in pool.kanji }
    }
}

/**
 * One unit check. [units] are the units it covers (the unit itself, plus the units right before it that have no check of their own),
 * [total] questions and [pass] to pass. [phrase] is true when every covered unit only teaches set phrases: the test then reuses the lesson's phrases.
 */
data class TestPlan(val units: List<CurriculumUnit>, val total: Int, val pass: Int, val phrase: Boolean) {
    /** Each tested word with the unit it belongs to; a word that two covered units share is listed once. */
    val pairs: List<Pair<CurriculumWord, CurriculumUnit>> = units.flatMap { u -> u.words.map { it to u } }.distinctBy { it.first.word }
    val words: List<CurriculumWord> get() = pairs.map { it.first }

    companion object {
        /** A full-size check of one unit (30 questions, 25 to pass). */
        fun full(unit: CurriculumUnit) = TestPlan(listOf(unit), TestRules.MAX_TOTAL, TestRules.passFor(TestRules.MAX_TOTAL), false)
    }
}

/** Which units have a unit check, what a check covers and how long it is. All numbers are in one place. */
object TestRules {
    /** Genki I Unit 1 (greetings) has no check: it graduates when its lesson and drills are finished, and the check after Unit 2 covers both. */
    val NO_TEST: Set<String> = setOf("g1-01")
    /** Units that teach set phrases only (no sentence grammar): their check reuses the lesson's phrases in other question types. */
    val PHRASE_UNITS: Set<String> = setOf("g1-01", "g1-02")

    /** A word counts as learned for tests after this many days of interval (WORD facet), a Heisig kanji after the second number. */
    const val MATURE_WORD_DAYS = 14
    const val MATURE_KANJI_DAYS = 20
    const val MIN_TOTAL = 10
    const val MAX_TOTAL = 30
    /** Questions per tested word (before the cap). */
    const val PER_WORD = 1.4
    /** Share of a test that is scaffolding from earlier units, when such words exist. */
    const val OLDER_SHARE = 0.3

    fun hasTest(unit: CurriculumUnit) = unit.id !in NO_TEST

    /** The unit itself, preceded by the units right before it that have no check. */
    fun covered(seq: List<CurriculumUnit>, unit: CurriculumUnit): List<CurriculumUnit> {
        val i = seq.indexOfFirst { it.id == unit.id }
        if (i < 0) return listOf(unit)
        var from = i
        while (from > 0 && !hasTest(seq[from - 1])) from--
        return seq.subList(from, i + 1)
    }

    /** For a unit without a check: the first later unit that has one (it covers this unit). */
    fun coveredBy(seq: List<CurriculumUnit>, unit: CurriculumUnit): CurriculumUnit? {
        if (hasTest(unit)) return null
        val i = seq.indexOfFirst { it.id == unit.id }
        if (i < 0) return null
        return seq.drop(i + 1).firstOrNull { hasTest(it) }
    }

    fun total(words: Int): Int = Math.round(words * PER_WORD).toInt().coerceIn(MIN_TOTAL, MAX_TOTAL)

    /** About 83 percent, rounded up: 25 of 30, 13 of 15. */
    fun passFor(total: Int): Int = (total * 25 + 29) / 30

    fun olderTarget(total: Int): Int = Math.round(total * OLDER_SHARE).toInt()

    /** The check of [unit], or null when the unit has none. */
    fun plan(seq: List<CurriculumUnit>, unit: CurriculumUnit): TestPlan? {
        if (!hasTest(unit)) return null
        val units = covered(seq, unit)
        val words = units.flatMap { u -> u.words.map { it.word } }.distinct().size
        val n = total(words)
        return TestPlan(units, n, passFor(n), units.all { it.id in PHRASE_UNITS })
    }

    /** "1", "1 and 2", "1, 2 and 3" */
    fun orders(units: List<CurriculumUnit>): String {
        val o = units.map { it.order.toString() }
        return if (o.size <= 1) o.joinToString("") else o.dropLast(1).joinToString(", ") + " and " + o.last()
    }
}
