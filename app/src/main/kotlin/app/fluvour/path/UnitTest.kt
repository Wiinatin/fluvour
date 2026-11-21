package app.fluvour.path

import kotlin.random.Random

/** Memory of one word, read from its WORD facet (what the older-item selector scores). */
data class WordMemory(val word: String, val phase: String, val stability: Double, val lapses: Int, val dueAt: Long?, val lastReviewAt: Long?)

/** A word of an earlier unit that could appear in a test. [memory] is null when the word has no card yet. */
data class OlderCandidate(val unitId: String, val unitTitle: String, val word: CurriculumWord, val memory: WordMemory?, val recentlyGraduated: Boolean, val prerequisite: Boolean)

/** One question place in a test: which word it must contain, from which unit, and the asked type (build, choose or typed). */
data class TestSlot(val n: Int, val word: CurriculumWord, val unitId: String, val unitTitle: String, val older: Boolean, val type: String)

data class TestQuestion(val slot: TestSlot, val drill: Drill)

/** [pass] is the pass mark, [covered] the ids of the units the check covers (all graduate on a pass), [phrase] marks a set-phrase check. */
data class UnitTest(val unitId: String, val questions: List<TestQuestion>, val pass: Int = TestLogic.PASS, val covered: List<String> = emptyList(), val phrase: Boolean = false)

/** What the learner entered for one question. */
data class TestAnswer(val tray: List<String> = emptyList(), val choice: Int? = null, val text: String = "")

/** [test] is set when every question is valid; otherwise [missing] are the slots still to fill. */
data class TestBuild(val test: UnitTest?, val missing: List<TestSlot>, val problem: String?)

/** [outside] are the words that got questions thrown away because the learner has not learned them. */
data class ParsedTest(val valid: Map<Int, TestQuestion>, val dropped: Int, val outside: Set<String> = emptySet(), val reasons: Map<String, Int> = emptyMap())

data class TestResult(
    val score: Int, val total: Int, val passed: Boolean,
    val currentRight: Int, val currentTotal: Int, val olderRight: Int, val olderTotal: Int,
    val missed: List<TestQuestion>, val flagged: List<TestQuestion>, val pass: Int = TestLogic.PASS
)

/**
 * Graduation test (learning path step 8), pure Kotlin so tools/unittest can run it on a plain JVM.
 * The APP chooses which words are tested (the unit's own words, plus about 30 percent from earlier units scored from FSRS state) and writes the
 * question places; the AI only writes the questions. The app then checks that every question contains its assigned word and that it
 * uses only words the learner knows (see [VocabCheck]). The size follows the unit: see [TestPlan] and [TestRules].
 * [TOTAL] and [PASS] are the full size of 30 questions with 25 to pass.
 */
object TestLogic {
    const val TOTAL = 30; const val PASS = 25; const val SPARE = 3
    const val OLDER_TARGET = 9; const val MAX_PER_UNIT = 3; const val MAX_FLAGS = 3
    private const val DAY = 86_400_000L

    /** Higher means more worth testing: lapses, low stability, overdue, not yet reviewed, recently graduated, in the unit's prerequisites. */
    fun olderScore(c: OlderCandidate, now: Long): Double {
        val m = c.memory ?: return 0.0
        var s = 1.0 + 2.0 * minOf(m.lapses, 4)
        s += if (m.phase != "REVIEW") 2.0 else 3.0 / (1.0 + m.stability)
        val due = m.dueAt
        if (due != null && due < now) s += minOf((now - due) / DAY.toDouble(), 14.0) / 7.0
        if (c.recentlyGraduated) s += 1.0
        if (c.prerequisite) s *= 1.5
        return s
    }

    /** The [count] best older words, spread over earlier units (at most [MAX_PER_UNIT] per unit while other units still have words). Words without a card are never picked. */
    fun pickOlder(candidates: List<OlderCandidate>, count: Int, now: Long): List<OlderCandidate> {
        val ranked = candidates.filter { it.memory != null }.sortedByDescending { olderScore(it, now) }
        val out = ArrayList<OlderCandidate>(); val perUnit = HashMap<String, Int>()
        for (c in ranked) { if (out.size >= count) break; val n = perUnit[c.unitId] ?: 0; if (n < MAX_PER_UNIT) { out.add(c); perUnit[c.unitId] = n + 1 } }
        for (c in ranked) { if (out.size >= count) break; if (c !in out) out.add(c) }
        return out
    }

    fun buildSlots(unit: CurriculumUnit, older: List<OlderCandidate>, rnd: Random): List<TestSlot> = buildSlots(TestPlan.full(unit), older, rnd)

    /**
     * The question places of [plan] ([TestPlan.total] of them) plus [SPARE] spare ones (numbered after the last), used only when a model question fails the checks.
     * [older] are picked older words (best first). About a third of the questions each are build, choose and typed (produced answers
     * are never fewer than recognition). Two questions on the same word are not placed next to each other.
     */
    fun buildSlots(plan: TestPlan, older: List<OlderCandidate>, rnd: Random): List<TestSlot> {
        val total = plan.total
        val olderPrimary = older.take(minOf(TestRules.olderTarget(total), older.size))
        val olderSpare = older.drop(olderPrimary.size).take(1)
        val currentWords = plan.pairs.shuffled(rnd)
        fun cur(i: Int) = currentWords[i % currentWords.size]
        val primaryCurrent = (0 until total - olderPrimary.size).map { cur(it) }
        val spareCurrent = (0 until SPARE - olderSpare.size).map { cur(primaryCurrent.size + it) }
        data class Raw(val word: CurriculumWord, val unitId: String, val title: String, val older: Boolean)
        val primary = (primaryCurrent.map { Raw(it.first, it.second.id, it.second.title, false) } + olderPrimary.map { Raw(it.word, it.unitId, it.unitTitle, true) }).shuffled(rnd).toMutableList()
        for (i in 1 until primary.size) if (primary[i].word.word == primary[i - 1].word.word) {
            val j = (i + 1 until primary.size).firstOrNull { primary[it].word.word != primary[i - 1].word.word && (it + 1 >= primary.size || primary[it + 1].word.word != primary[i].word.word) }
            if (j != null) { val t = primary[i]; primary[i] = primary[j]; primary[j] = t }
        }
        val third = total / 3
        val types = (List(third) { "build" } + List(third) { "typed" } + List(total - 2 * third) { "choose" }).shuffled(rnd)
        val slots = primary.mapIndexed { i, r -> TestSlot(i + 1, r.word, r.unitId, r.title, r.older, types[i]) }.toMutableList()
        // the same word must not be asked in the same format twice: a set-phrase unit has one natural question per phrase and format, so the
        // second copy would be thrown out as a repeat every time
        fun clash(n: Int, type: String) = slots.indices.any { it != n && slots[it].word.word == slots[n].word.word && slots[it].type == type }
        for (j in slots.indices) if (clash(j, slots[j].type)) {
            val k = slots.indices.firstOrNull { it != j && slots[it].type != slots[j].type && !clash(j, slots[it].type) && !clash(it, slots[j].type) }
            if (k != null) { val tj = slots[j].type; slots[j] = slots[j].copy(type = slots[k].type); slots[k] = slots[k].copy(type = tj) }
        }
        val spareRaw = olderSpare.map { Raw(it.word, it.unitId, it.unitTitle, true) } + spareCurrent.map { Raw(it.first, it.second.id, it.second.title, false) }
        val spareTypes = listOf("choose", "typed", "build")
        spareRaw.forEachIndexed { i, r -> slots.add(TestSlot(total + 1 + i, r.word, r.unitId, r.title, r.older, spareTypes[i % 3])) }
        return slots
    }

    fun prompt(unit: CurriculumUnit, slots: List<TestSlot>, avoid: List<String>): String = prompt(TestPlan.full(unit), slots, avoid, null, emptyList(), "")

    /**
     * The prompt for [slots] (all of them, or only the missing ones for a top-up). [avoid] are Japanese lines the questions must not reuse.
     * [known] (null = no vocabulary rule) are the words the learner knows, [kanji] the kanji they may write, [rejected] words an earlier reply
     * used that were not allowed.
     */
    fun prompt(plan: TestPlan, slots: List<TestSlot>, avoid: List<String>, known: List<String>?, rejected: List<String>, kanji: String): String {
        val units = plan.units
        val grammar = units.flatMap { it.grammar }.distinct().joinToString("\n") { "- $it" }
        val title = units.joinToString(" + ") { it.title }
        val goal = if (units.size == 1) "Goal: ${units[0].canDo}" else "Goals:\n" + units.joinToString("\n") { "- Unit ${it.order}: ${it.canDo}" }
        val list = slots.joinToString("\n") { s ->
            val from = if (s.older) "earlier unit \"${s.unitTitle}\", use only very simple grammar" else "this unit"
            "${s.n}. type ${s.type}, assigned word ${s.word.word} (${s.word.reading}) = ${s.word.gloss}, from $from"
        }
        val avoidText = if (avoid.isEmpty()) "" else "\nDo not reuse any of these Japanese lines or sentences that were already used:\n" + avoid.joinToString("\n") { "- $it" } + "\n"
        val vocab = if (known == null) "" else {
            val words = if (known.isEmpty()) "(none yet)" else known.joinToString("、")
            val kanjiText = if (kanji.isEmpty()) "" else " Words made only of these kanji are also allowed: $kanji."
            val rejectedText = if (rejected.isEmpty()) "" else " These words were already rejected, do not use them: ${rejected.joinToString("、")}."
            "\nVOCABULARY RULE (strict): the learner knows only these words: $words.\nBesides them you may use particles, です and ます forms, the grammar points above, names of people (for example 田中 or しずか), and each question's assigned word.$kanjiText Any other word, even a common one, makes the question invalid and it is thrown away. If a situation needs a word that is not allowed, choose another situation.$rejectedText\n"
        }
        val style = if (plan.phrase)
            "This unit teaches set phrases only. Reuse the unit's own phrases in different question formats: for example choose the right phrase for a situation, choose the reply to a phrase, build a phrase from its parts, or type what a phrase means. Do not write longer sentences or use grammar that has not been taught. The same phrase may appear in more than one question, but not twice in the same format. Do not give the answer away in the question."
        else
            "Every sentence must be new. Questions of this unit should also use its grammar points. Do not give the answer away in the question."
        return """
You write the questions of one unit test of a Japanese course for an English speaker. Level ${units.first().level}. Write natural, everyday Japanese in polite form. Use only vocabulary and grammar at or below this level, plus each question's assigned word. Do not copy any textbook.

Unit: $title
$goal
Grammar points of this unit:
$grammar
$vocab$avoidText
Write exactly ${slots.size} questions, one for each line below, keeping its number and its type. The Japanese of the question (the sentence to build, the options, or the text to translate) MUST contain that line's assigned word; a verb or adjective may be in a polite or inflected form. $style
$list

Reply with JSON only, no other text, in exactly this shape:
{"questions":[
{"n":1,"type":"build","prompt":"English sentence to build","chips":["word tokens in shuffled order, plus 1 or 2 wrong extra tokens"],"answer":["the correct tokens in order"],"why":"one short reason","target":"the assigned word"},
{"n":2,"type":"choose","prompt":"question","options":["option A","option B","option C"],"answer":0,"why":"one short reason","target":"the assigned word"},
{"n":3,"type":"typed","prompt":"What does this mean?","jp":"Japanese text to translate","accepted":["answer 1","answer 2","answer 3"],"why":"one short reason","target":"the assigned word"}
]}
Rules: "answer" of choose is the index (0 to 2) of the correct option, and the correct option must not always be first. Typed "accepted" lists 2 to 4 natural English answers. Keep every "why" under 15 words.
""".trim()
    }

    /** The text of a question that is in Japanese (what must contain the assigned word). */
    fun japanese(d: Drill): String = when (d) {
        is BuildDrill -> d.answer.joinToString("") + "\n" + d.chips.joinToString("")
        is ChooseDrill -> d.prompt + "\n" + d.options.joinToString("\n")
        is TypedDrill -> d.jp.orEmpty()
    }

    /** The pieces of a question that are Japanese, each checked on its own against the known words (chips one by one, since they are not a sentence). */
    fun vocabTexts(d: Drill): List<String> = when (d) {
        is BuildDrill -> listOf(d.answer.joinToString("")) + d.chips
        is ChooseDrill -> listOf(d.prompt) + d.options
        is TypedDrill -> listOfNotNull(d.jp)
    }.filter { t -> t.any { VocabCheck.isJapanese(it) } }

    /**
     * A short key for "seen before" checks: the sentence a question is about. With [byType] (set-phrase tests, where one phrase is asked in
     * several formats) the question format is part of the key.
     */
    fun key(d: Drill, byType: Boolean = false): String {
        val k = when (d) {
            is BuildDrill -> d.answer.joinToString("")
            is ChooseDrill -> d.prompt.trim() + "|" + d.options.getOrElse(d.answer) { "" }
            is TypedDrill -> d.jp?.trim().takeUnless { it.isNullOrEmpty() } ?: d.prompt.trim()
        }.replace(Regex("\\s+"), " ")
        if (!byType) return k
        return (when (d) { is BuildDrill -> "build"; is ChooseDrill -> "choose"; is TypedDrill -> "typed" }) + ":" + k
    }

    /**
     * Reads a reply: keeps the questions that are well formed, contain their assigned word, use only known words ([vocab] returns the
     * words of a text that are not allowed; null skips the check), and were not seen before or twice in this reply.
     */
    fun parse(slots: List<TestSlot>, reply: String, seen: Set<String>, vocab: ((String) -> List<String>)? = null, phrase: Boolean = false): ParsedTest {
        val json = MiniJson.extract(reply)?.let { MiniJson.parse(it) } as? Map<*, *> ?: return ParsedTest(emptyMap(), 0)
        val raw = (json["questions"] as? List<*>).orEmpty()
        val valid = LinkedHashMap<Int, TestQuestion>(); val keys = HashSet<String>(); val outside = LinkedHashSet<String>(); var dropped = 0
        val reasons = LinkedHashMap<String, Int>()
        fun drop(why: String) { dropped++; reasons[why] = (reasons[why] ?: 0) + 1 }
        for (o in raw) {
            val m = o as? Map<*, *>; val n = (m?.get("n") as? Double)?.toInt() ?: -1
            val slot = slots.firstOrNull { it.n == n }
            if (m == null || slot == null || valid.containsKey(n)) { drop("not one of the asked places"); continue }
            val d = LessonLogic.drill(m, setOf(slot.word.word))
            if (d == null) { drop("badly formed"); continue }
            if (!LessonLogic.appears(japanese(d), slot.word.word)) { drop("assigned word missing"); continue }
            if (vocab != null) {
                val bad = vocabTexts(d).flatMap { vocab(it) }
                if (bad.isNotEmpty()) { drop("words not learned yet"); outside.addAll(bad); continue }
            }
            val k = key(d, phrase)
            if (k in seen) { drop("same as an earlier test"); continue }
            if (!keys.add(k)) { drop("repeated in this test"); continue }
            valid[slot.n] = TestQuestion(slot, withTarget(d, slot.word.word))
        }
        return ParsedTest(valid, dropped, outside, reasons)
    }

    private fun withTarget(d: Drill, w: String): Drill = when (d) {
        is BuildDrill -> d.copy(target = w); is ChooseDrill -> d.copy(target = w); is TypedDrill -> d.copy(target = w)
    }

    /**
     * Every place filled? A failed place is taken over by an unused spare of the same kind (unit or older). [slots] is the full list from [buildSlots]
     * (the last [SPARE] are spares). With [plan] the test carries its pass mark and the units it covers.
     */
    fun assemble(unitId: String, slots: List<TestSlot>, valid: Map<Int, TestQuestion>, plan: TestPlan? = null, allowShort: Boolean = false): TestBuild {
        val total = slots.size - SPARE
        val spares = slots.filter { it.n > total }.mapNotNull { valid[it.n] }.toMutableList()
        val out = ArrayList<TestQuestion>(); val missing = ArrayList<TestSlot>()
        for (s in slots.filter { it.n <= total }) {
            val q = valid[s.n]
            if (q != null) { out.add(q); continue }
            val sp = spares.firstOrNull { it.slot.older == s.older }
            if (sp != null) { spares.remove(sp); out.add(TestQuestion(s, sp.drill)) } else missing.add(s)
        }
        // last resort ([allowShort]): a check a few questions short beats no check; the pass mark follows the real number of questions
        val short = missing.isNotEmpty() && allowShort && out.size >= TestRules.MIN_TOTAL
        val pass = if (short) TestRules.passFor(out.size) else plan?.pass ?: TestRules.passFor(total)
        val test = UnitTest(unitId, out, pass, plan?.units?.map { it.id }.orEmpty(), plan?.phrase ?: false)
        return if (missing.isEmpty() || short) TestBuild(test, emptyList(), null)
        else TestBuild(null, missing, "${missing.size} questions did not pass the checks.")
    }

    fun correct(d: Drill, a: TestAnswer): Boolean = when (d) {
        is BuildDrill -> LessonLogic.buildOk(d, a.tray)
        is ChooseDrill -> a.choice == d.answer
        is TypedDrill -> LessonLogic.typedOk(d, a.text)   // local fallback only; PathRepository.gradeTest grades typed answers with the AI
    }

    /** A flagged question never counts as a miss (a bad question must not fail the unit), up to [MAX_FLAGS] per test. [right] is one verdict per question. */
    fun score(test: UnitTest, right: List<Boolean>, flagged: Set<Int>): TestResult {
        val qs = test.questions
        val ok = qs.indices.map { right.getOrElse(it) { false } || it in flagged }
        val cur = qs.indices.filter { !qs[it].slot.older }; val old = qs.indices.filter { qs[it].slot.older }
        val score = ok.count { it }
        return TestResult(
            score, qs.size, score >= test.pass, cur.count { ok[it] }, cur.size, old.count { ok[it] }, old.size,
            qs.indices.filter { !ok[it] }.map { qs[it] }, qs.indices.filter { it in flagged }.map { qs[it] }, test.pass
        )
    }
}
