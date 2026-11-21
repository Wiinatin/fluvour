package app.fluvour.path

/** One word of a lesson sentence, tappable in the lesson. Punctuation tokens have an empty gloss. */
data class LessonWord(val text: String, val gloss: String)

/** One teaching sentence: its words, the English line and a one-line pattern note. */
data class LessonChunk(val words: List<LessonWord>, val en: String, val pattern: String) {
    val jp: String get() = words.joinToString("") { it.text }
}

/** A drill. [target] is the unit word it practises (null if the model gave none that matches); [why] is the short reason shown after a miss. */
sealed interface Drill { val why: String; val target: String? }
data class BuildDrill(val prompt: String, val chips: List<String>, val answer: List<String>, override val why: String, override val target: String?) : Drill
/** [audio] set = a listening drill: the Japanese sentence is played, its text stays hidden until answered, the options are meanings. A prompt with ＿ is a fill-the-blank drill. */
data class ChooseDrill(val prompt: String, val options: List<String>, val answer: Int, override val why: String, override val target: String?, val audio: String? = null) : Drill
data class TypedDrill(val prompt: String, val jp: String?, val accepted: List<String>, override val why: String, override val target: String?) : Drill

data class Lesson(val unitId: String, val chunks: List<LessonChunk>, val drills: List<Drill>)

/** [lesson] is set when the reply passed the local checks; otherwise [problem] says why (shown to nobody but the log). */
data class LessonResult(val lesson: Lesson?, val problem: String?, val dropped: Int = 0, val coverage: Double = 0.0)

/**
 * Lesson prompt, parsing and local checks (pure Kotlin; tools/lessontest runs it on a plain JVM).
 * The model writes the lesson once per unit; the app checks that the unit's target words appear and that every drill is well formed.
 * It cannot check that the Japanese is natural: that is what the flag button in a later step is for.
 */
object LessonLogic {
    const val MIN_CHUNKS = 3; const val MAX_CHUNKS = 5; const val MIN_DRILLS = 6; const val MIN_COVERAGE = 0.8

    /**
     * [known] (null = no vocabulary rule) are the words the learner already knows besides the target words (earlier units and cards they have
     * held for 14 days or more); [kanji] are the Heisig kanji they have matured. Nothing else may be used as a new word.
     */
    fun prompt(unit: CurriculumUnit, known: List<String>? = null, kanji: String = ""): String {
        val words = unit.words.joinToString("\n") { "- ${it.word} (${it.reading}) = ${it.gloss}" }
        val grammar = unit.grammar.joinToString("\n") { "- $it" }
        val vocab = if (known == null) "" else {
            val others = if (known.isEmpty()) "The learner knows no other words yet." else "Other words the learner already knows: ${known.joinToString("、")}."
            val kanjiText = if (kanji.isEmpty()) "" else " Words made only of these kanji are also allowed: $kanji."
            "\nVOCABULARY RULE (strict): $others Besides the target words and those words you may use only particles, です and ます forms, the grammar points above, and names of people (for example 田中 or しずか).$kanjiText Do not use any other word, even a common one. If a sentence needs a word that is not allowed, write a simpler sentence.\n"
        }
        return """
You write one short lesson of a Japanese course for an English speaker. Level ${unit.level}. Write natural, everyday Japanese in polite form. Use only vocabulary and grammar at or below this level, plus the target words. Do not copy any textbook.

Unit: ${unit.title}
Goal: ${unit.canDo}
Grammar points:
$grammar
Target words (written form, reading, meaning):
$words
$vocab
Write:
1. 4 chunks. A chunk is one sentence or a 2-line mini dialogue joined in one sentence line, split into words. Together the chunks use every target word at least once (a verb or adjective may be in a polite or inflected form) and show each grammar point.
2. 8 drills: 3 of type build, 1 of type choose, 1 of type fill, 1 of type listen, 2 of type typed. Each drill tests one target word or grammar point, with sentences that are NOT identical to the chunks.

Reply with JSON only, no other text, in exactly this shape:
{"chunks":[{"words":[{"t":"これ","g":"this"},{"t":"は","g":"topic marker"}],"en":"English meaning","pattern":"one short pattern note"}],
"drills":[
{"type":"build","prompt":"English sentence to build","chips":["word tokens in shuffled order, plus 1 or 2 wrong extra tokens"],"answer":["the correct tokens in order"],"why":"one short reason","target":"a target word"},
{"type":"choose","prompt":"question","options":["option A","option B","option C"],"answer":0,"why":"one short reason","target":"a target word"},
{"type":"fill","prompt":"Japanese sentence with exactly one ＿ in place of the missing word, then its English meaning in brackets","options":["word A","word B","word C"],"answer":0,"why":"one short reason","target":"a target word"},
{"type":"listen","prompt":"What does it mean?","jp":"the Japanese sentence that is read aloud","options":["English meaning A","English meaning B","English meaning C"],"answer":0,"why":"one short reason","target":"a target word"},
{"type":"typed","prompt":"What does this mean?","jp":"Japanese text to translate","accepted":["answer 1","answer 2","answer 3"],"why":"one short reason","target":"a target word"}
]}
Rules: "answer" of choose, fill and listen is the index (0 to 2) of the correct option, and the correct option must not always be first. Fill options are Japanese words and only one of them fits the blank; listen options are English meanings and the listen sentence uses only allowed words. Typed "accepted" lists 2 to 4 natural English answers. Words in chunks include punctuation as their own token with an empty "g". Keep every "why" and "pattern" under 15 words.
""".trim()
    }

    fun parse(unit: CurriculumUnit, reply: String): LessonResult {
        val json = MiniJson.extract(reply)?.let { MiniJson.parse(it) } as? Map<*, *> ?: return LessonResult(null, if (MiniJson.looksCutOff(reply)) "The reply was cut off." else "The reply was not JSON.")
        val chunks = (json["chunks"] as? List<*>).orEmpty().mapNotNull { chunk(it) }
        if (chunks.size < MIN_CHUNKS) return LessonResult(null, "Too few usable chunks (${chunks.size}).")
        val rawDrills = (json["drills"] as? List<*>).orEmpty()
        val targets = unit.words.map { it.word }.toSet()
        val drills = rawDrills.mapNotNull { drill(it, targets) }
        if (drills.size < MIN_DRILLS) return LessonResult(null, "Too few usable drills (${drills.size}).", rawDrills.size - drills.size)
        val cov = coverage(unit, chunks.take(MAX_CHUNKS), drills)
        if (cov < MIN_COVERAGE) return LessonResult(null, "Only ${(cov * 100).toInt()}% of the target words appear.", rawDrills.size - drills.size, cov)
        return LessonResult(Lesson(unit.id, chunks.take(MAX_CHUNKS), drills), null, rawDrills.size - drills.size, cov)
    }

    private fun str(x: Any?) = (x as? String)?.trim().orEmpty()

    private fun chunk(o: Any?): LessonChunk? {
        val m = o as? Map<*, *> ?: return null
        val words = (m["words"] as? List<*>).orEmpty().mapNotNull { w ->
            val wm = w as? Map<*, *> ?: return@mapNotNull null
            val t = str(wm["t"]); if (t.isEmpty()) null else LessonWord(t, str(wm["g"]))
        }
        val glossed = words.count { it.gloss.isNotEmpty() }
        val en = str(m["en"]); val pattern = str(m["pattern"])
        if (glossed < 2 || en.isEmpty() || pattern.isEmpty()) return null
        return LessonChunk(words, en, pattern)
    }

    fun drill(o: Any?, targets: Set<String>): Drill? {
        val m = o as? Map<*, *> ?: return null
        val prompt = str(m["prompt"]); val why = str(m["why"])
        if (prompt.isEmpty() || why.isEmpty()) return null
        val target = str(m["target"]).takeIf { it in targets }
        val strings = { k: String -> (m[k] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } } }
        return when (str(m["type"])) {
            "build" -> {
                val chips = strings("chips"); val answer = strings("answer")
                if (answer.size < 2 || chips.size < answer.size || chips.toSet().size != chips.size) return null
                val pool = chips.toMutableList()
                for (a in answer) if (!pool.remove(a)) return null
                BuildDrill(prompt, chips, answer, why, target)
            }
            "choose" -> {
                val options = strings("options"); val a = (m["answer"] as? Double)?.toInt() ?: return null
                if (options.size != 3 || options.toSet().size != 3 || a !in 0..2) return null
                ChooseDrill(prompt, options, a, why, target)
            }
            "fill" -> {
                val options = strings("options"); val a = (m["answer"] as? Double)?.toInt() ?: return null
                if (options.size != 3 || options.toSet().size != 3 || a !in 0..2 || '＿' !in prompt) return null
                ChooseDrill(prompt, options, a, why, target)
            }
            "listen" -> {
                val options = strings("options"); val a = (m["answer"] as? Double)?.toInt() ?: return null
                val jp = str(m["jp"])
                if (options.size != 3 || options.toSet().size != 3 || a !in 0..2 || jp.none { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' }) return null
                ChooseDrill(prompt, options, a, why, target, audio = jp)
            }
            "typed" -> {
                val accepted = strings("accepted"); if (accepted.isEmpty()) return null
                val jp = str(m["jp"]).ifEmpty { null }
                TypedDrill(prompt, jp, accepted, why, target)
            }
            else -> null
        }
    }

    /** Share of the unit's target words that show up in the chunks or drills. A word also counts by its stem (the word minus a final kana), so inflected verbs and adjectives are found. */
    fun coverage(unit: CurriculumUnit, chunks: List<LessonChunk>, drills: List<Drill>): Double {
        val text = buildString {
            chunks.forEach { append(it.jp).append('\n') }
            drills.forEach { d ->
                when (d) {
                    is BuildDrill -> append(d.answer.joinToString("")).append('\n')
                    is ChooseDrill -> { d.audio?.let { append(it).append('\n') }; d.options.forEach { append(it).append('\n') } }
                    is TypedDrill -> d.jp?.let { append(it).append('\n') }
                }
            }
        }
        if (unit.words.isEmpty()) return 1.0
        return unit.words.count { appears(text, it.word) }.toDouble() / unit.words.size
    }

    fun appears(text: String, word: String): Boolean {
        if (text.contains(word)) return true
        val last = word.last()
        val kana = last in '\u3041'..'\u3096'
        return kana && word.length >= 2 && text.contains(word.dropLast(1))
    }

    // ---- typed answers (step 9 replaces this with Gemini grading) ----
    private val articles = setOf("a", "an", "the")
    private fun norm(s: String): List<String> =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() && it !in articles }

    /** Equal to an accepted answer, or contains one as whole words with at most 3 extra words. */
    fun typedOk(drill: TypedDrill, answer: String): Boolean {
        val a = norm(answer); if (a.isEmpty()) return false
        return drill.accepted.any { acc ->
            val n = norm(acc)
            n.isNotEmpty() && (a == n || (a.size <= n.size + 3 && a.windowed(n.size).any { it == n }))
        }
    }

    fun buildOk(drill: BuildDrill, tray: List<String>) = tray == drill.answer
}
