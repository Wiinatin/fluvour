package app.fluvour.path

import android.content.Context
import app.fluvour.data.CardRepository
import app.fluvour.data.PathUnitDao
import app.fluvour.data.PathUnitEntity
import app.fluvour.data.SettingsRepository
import app.fluvour.quiz.TextAi
import app.fluvour.reading.HeisigStudyRepository
import app.fluvour.reading.WordSplitter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Learning path step 6: what the Path sub-tab reads. Curriculum comes from [CurriculumRepository], graduation from path_units,
 * and the link between a unit and the user's cards is by word ([UnitItems]). Nothing here blocks a unit because of review backlog (decision 15).
 */
@Singleton
class PathRepository @Inject constructor(
    private val curriculum: CurriculumRepository, private val units: PathUnitDao, private val cards: CardRepository,
    private val ai: TextAi, private val settings: SettingsRepository, private val tts: app.fluvour.audio.TtsManager,
    private val heisig: HeisigStudyRepository, @ApplicationContext private val ctx: Context
) {
    private val prefs = ctx.getSharedPreferences("path", Context.MODE_PRIVATE)
    private companion object { const val KNOWN_IN_PROMPT = 250 }

    /** Work that must finish even after the drill screen is closed (typed verdicts still on their way). */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private fun lessonFile(unitId: String) = File(File(ctx.filesDir, "lessons").also { it.mkdirs() }, "$unitId.json")

    /** A one-line message for the Path screen (for example the drill result). Shown once, then cleared by the screen. */
    val notice = MutableStateFlow<String?>(null)

    /** Units whose lesson has been finished (local only). */
    fun lessonDone(): Set<String> = prefs.getStringSet("lesson_done", emptySet()).orEmpty().toSet()
    fun markLessonDone(unitId: String) { prefs.edit().putStringSet("lesson_done", lessonDone() + unitId).apply() }

    /** Units whose practice session ended with every word and sentence learned ([MasterySession]); the unit check opens only after this. */
    fun masteredUnits(): Set<String> = prefs.getStringSet("mastered", emptySet()).orEmpty().toSet()
    fun markMastered(unitId: String) { prefs.edit().putStringSet("mastered", masteredUnits() + unitId).apply() }

    /** Practice progress of a unit that was left half way (points of each item), so the session can go on later. */
    fun saveMastery(unitId: String, snap: Map<String, List<Int>>) {
        val o = org.json.JSONObject(); snap.forEach { (k, v) -> o.put(k, org.json.JSONArray(v)) }
        prefs.edit().putString("mastery_$unitId", o.toString()).apply()
    }
    fun loadMastery(unitId: String): Map<String, List<Int>>? = runCatching {
        val o = org.json.JSONObject(prefs.getString("mastery_$unitId", null) ?: return null)
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> List(a.length()) { a.getInt(it) } } }
    }.getOrNull()
    fun clearMastery(unitId: String) { prefs.edit().remove("mastery_$unitId").apply() }

    /**
     * The unit's lesson: the saved one, or a new one written by the AI (one request, a second try if the first fails the local checks)
     * and saved. The saved copy is the model's JSON, checked again on every load. [fresh] throws the saved copy away first.
     */
    suspend fun lesson(lang: String, unit: CurriculumUnit, fresh: Boolean = false): LessonLoad = withContext(Dispatchers.IO) {
        val f = lessonFile(unit.id)
        if (fresh) f.delete()
        if (f.exists()) LessonLogic.parse(unit, f.readText()).lesson?.let { kickVoice(); return@withContext LessonLoad(it, null) }
        var message = "The lesson could not be written."
        val seq = curriculum.paths(lang).flatMap { it.units }
        val at = seq.indexOfFirst { it.id == unit.id }
        val pool = knownPool(lang, listOf(unit), if (at > 0) seq.take(at) else emptyList())
        val own = unit.words.map { it.word }.toSet()
        val known = pool.listed.filter { it !in own }.take(KNOWN_IN_PROMPT)
        val kanji = pool.kanji.joinToString("")
        repeat(2) {
            val part = ai.ask(LessonLogic.prompt(unit, known, kanji), maxTokens = 12000, temperature = 0.7, json = true)
            val reply = part.text
            if (reply == null) { message = part.message ?: message; return@withContext LessonLoad(null, message) }   // no key, offline, limit: a second try would fail the same way
            val r = LessonLogic.parse(unit, reply)
            if (r.lesson != null) {
                f.writeText(MiniJson.extract(reply)!!)
                kickVoice()
                return@withContext LessonLoad(r.lesson, null)
            }
            message = "The AI's lesson did not pass the checks (${r.problem}). Try again."
        }
        LessonLoad(null, message)
    }

    /** Asks the background voice job to run soon, so the lesson's sentences are voiced before they are tapped (only when natural voice ahead is on). */
    private fun kickVoice() { runCatching { if (app.fluvour.audio.VoiceBudget(ctx).enabled) app.fluvour.audio.VoiceAheadScheduler.kick(ctx) } }

    /** The Japanese sentences of the most recently written lessons (at most 3 lessons), for the voice job. */
    suspend fun lessonSentences(lang: String): List<String> = withContext(Dispatchers.IO) {
        val files = File(ctx.filesDir, "lessons").listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }.take(3)
        files.flatMap { f ->
            val unit = curriculum.unit(lang, f.nameWithoutExtension) ?: return@flatMap emptyList()
            runCatching { LessonLogic.parse(unit, f.readText()).lesson?.let { l -> l.chunks.map { it.jp } + l.drills.mapNotNull { (it as? ChooseDrill)?.audio } } }.getOrNull().orEmpty()   // the sentences of the chunks and of the listening drills
        }
    }

    /** Learning path: after the lesson, every unit word gets a card (decision 8). Words that already have a card are left alone. Returns how many cards were made. */
    suspend fun ensureCards(lang: String, unit: CurriculumUnit, lesson: Lesson?): Int {
        val have = cards.cardsForWords(lang, UnitItems.words(unit)).map { it.second }.toSet()
        val userId = settings.ensureUserId()
        var made = 0
        for (w in unit.words) {
            if (w.word in have) continue
            val example = lesson?.chunks?.firstOrNull { it.jp.contains(w.word) }?.let { it.jp + " (" + it.en + ")" }
            val notes = if (w.reading != w.word) "Reading: ${w.reading}" else null
            cards.save(userId, lang, null, w.word, w.gloss.ifBlank { w.word }, example, notes)
            made++
        }
        return made
    }

    /** A drill miss: its short reason is added to the notes of the unit word's card, once. */
    suspend fun noteMiss(lang: String, word: String, why: String, label: String = "Drill") {
        val id = cards.cardsForWords(lang, listOf(word)).firstOrNull()?.first ?: return
        val card = cards.get(id) ?: return
        val line = "$label: $why"
        if (card.notes?.contains(line) == true) return
        cards.setAngle(id, null, listOfNotNull(card.notes?.takeIf { it.isNotBlank() }, line).joinToString("\n"))
    }

    suspend fun paths(lang: String): List<CurriculumPath> = curriculum.paths(lang)

    /** False for a language with no curriculum file: the Path sub-tab is hidden. */
    suspend fun unit(lang: String, unitId: String): CurriculumUnit? = curriculum.unit(lang, unitId)

    suspend fun hasPath(lang: String): Boolean = curriculum.hasCurriculum(lang)

    fun observeGraduated(lang: String): Flow<Set<String>> = units.observeGraduated(lang).map { it.toSet() }

    /** Linked cards and due facets for one unit. */
    suspend fun nudge(lang: String, unit: CurriculumUnit): UnitNudge {
        val words = UnitItems.words(unit)
        val linked = UnitItems.cardIds(unit, cards.cardsForWords(lang, words)).size
        return UnitNudge(linked, cards.dueForWords(lang, words))
    }

    /** Set by the test result ("Back to the path"): the Path tab shows the unit list instead of the unit page. */
    val listRequest = MutableStateFlow(false)

    /**
     * The words and kanji a lesson or test of [covered] may use: their own words, the words of every unit in [earlier], cards held for
     * [TestRules.MATURE_WORD_DAYS] days or more, and (Japanese) Heisig kanji held for [TestRules.MATURE_KANJI_DAYS] days or more.
     * Whether a card is "at the same level" is not tracked; the prompt keeps the course level.
     */
    suspend fun knownPool(lang: String, covered: List<CurriculumUnit>, earlier: List<CurriculumUnit>): KnownPool {
        val own = covered.flatMap { it.words }
        val before = earlier.asReversed().flatMap { it.words }   // the most recent units first
        val mature = runCatching { cards.matureWords(lang, TestRules.MATURE_WORD_DAYS) }.getOrDefault(emptyList())
        val kanji = if (lang.startsWith("ja")) runCatching { heisig.maturedKanji(TestRules.MATURE_KANJI_DAYS) }.getOrDefault(emptySet()) else emptySet()
        return KnownPool((own.map { it.word } + mature + before.map { it.word }).distinct(), (own + before).map { it.reading }, kanji)
    }

    /**
     * A unit without a unit check graduates when its drills are finished. Returns what the Path screen says next, or null when the unit
     * has a check (it graduates by the test) or was already graduated.
     */
    suspend fun graduateByLesson(lang: String, unit: CurriculumUnit): LessonGrad? = withContext(Dispatchers.IO) {
        if (TestRules.hasTest(unit)) return@withContext null
        if (units.get(unit.id)?.graduatedAt != null) return@withContext null
        recordTest(lang, unit.id, 0, 0)
        val seq = curriculum.paths(lang).flatMap { it.units }
        LessonGrad(seq.getOrNull(seq.indexOfFirst { it.id == unit.id } + 1)?.order)
    }

    private fun testDir() = File(ctx.filesDir, "tests").also { it.mkdirs() }
    private fun seenFile(unitId: String) = File(testDir(), "$unitId.seen")
    private fun readSeen(unitId: String): List<String> = runCatching { seenFile(unitId).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** The questions of the last build that came up short: kept so "Try again" only asks for the missing ones. In memory only. */
    private class Leftover(val unitId: String, val plan: TestPlan, val slots: List<TestSlot>, val valid: Map<Int, TestQuestion>, val rejected: List<String>)
    @Volatile private var leftover: Leftover? = null

    /** How far the test being written has got (questions that passed the checks out of the total) and what it is doing. Read by the loading screen. */
    val buildProgress = MutableStateFlow(BuildProgress())

    private fun lessonLines(u: CurriculumUnit): List<String> =
        runCatching { LessonLogic.parse(u, lessonFile(u.id).readText()).lesson?.chunks?.map { it.jp } }.getOrNull().orEmpty()

    /**
     * Learning path step 8: a fresh unit check for [unit]. Its size follows the unit ([TestRules.plan]): a unit with no check of its own
     * (Unit 1) is covered by the next unit's check, which tests both. The app picks which words are tested (the unit's own, plus about 30
     * percent from earlier units scored from FSRS state) and the AI writes the questions in one request (a second and third, small, only when
     * some fail the checks). Every question must contain its assigned word, use only words the learner knows ([KnownPool], [VocabCheck]) and
     * must not repeat a sentence of an earlier test of this unit. When a build comes up short, the questions that passed are kept in memory:
     * with [resume] (the "Try again" button) only the missing ones are requested; without it a whole new test is written.
     */
    suspend fun buildTest(lang: String, unit: CurriculumUnit, resume: Boolean = false): TestLoad = withContext(Dispatchers.IO) {
        val seq = curriculum.paths(lang).flatMap { it.units }
        val plan = TestRules.plan(seq, unit)
            ?: return@withContext TestLoad(null, "This unit has no unit check. It graduates when you finish its lesson and drills.")
        fun report(done: Int, step: String) { buildProgress.value = BuildProgress(done.coerceIn(0, plan.total), plan.total, step) }
        report(0, "Choosing the words")
        val firstAt = seq.indexOfFirst { it.id == plan.units.first().id }
        val earlier = if (firstAt > 0) seq.take(firstAt) else emptyList()
        val now = System.currentTimeMillis()
        val own = plan.words.map { it.word }.toSet()
        val memory = cards.wordMemory(lang, earlier.flatMap { UnitItems.words(it) }.filter { it !in own }.distinct()).associateBy { it.word }
        val used = HashSet<String>(); val candidates = ArrayList<OlderCandidate>()
        for (u in earlier) {
            val graduatedAt = units.get(u.id)?.graduatedAt
            val recent = graduatedAt != null && now - graduatedAt < 7 * 86_400_000L
            for (w in u.words) {
                if (w.word in own || !used.add(w.word)) continue
                val m = memory[w.word]?.let { WordMemory(it.word, it.phase, it.stability, it.lapses, it.dueAt, it.lastReviewAt) }
                candidates.add(OlderCandidate(u.id, u.title, w, m, recent, plan.units.any { c -> u.id in c.prerequisites }))
            }
        }
        val slots = TestLogic.buildSlots(plan, TestLogic.pickOlder(candidates, TestRules.olderTarget(plan.total) + 1, now), Random(System.nanoTime()))

        val pool = knownPool(lang, plan.units, earlier)
        val known = pool.listed.take(KNOWN_IN_PROMPT)
        val kanji = pool.kanji.joinToString("")
        val vocab: (String) -> List<String> = { text -> VocabCheck.outside(text, pool, WordSplitter.japaneseTokens(text)) }

        // a set-phrase check reuses the lesson's phrases, so only the last test's questions are kept out
        val seen = readSeen(unit.id).let { if (plan.phrase) it.takeLast(30) else it }
        val avoid = if (plan.phrase) emptyList() else plan.units.flatMap { lessonLines(it) } + seen.takeLast(12)
        val seenSet = seen.toSet()

        val kept = leftover?.takeIf { resume && it.unitId == unit.id && it.plan.total == plan.total && it.plan.units.map { u -> u.id } == plan.units.map { u -> u.id } }
        val usedSlots = kept?.slots ?: slots
        var valid: Map<Int, TestQuestion> = kept?.valid ?: emptyMap()
        val rejected = LinkedHashSet<String>(); kept?.let { rejected.addAll(it.rejected) }
        val reasons = LinkedHashMap<String, Int>()
        if (kept == null) {
            leftover = null
            report(0, "Writing the questions")
            val first = ai.ask(TestLogic.prompt(plan, slots, avoid, known, emptyList(), kanji), maxTokens = 14000, temperature = 0.8, json = true)
            val reply = first.text ?: return@withContext TestLoad(null, first.message ?: "The test could not be written.")
            val parsed = TestLogic.parse(slots, reply, seenSet, vocab, plan.phrase)
            valid = parsed.valid
            rejected.addAll(parsed.outside)
            parsed.reasons.forEach { (k, v) -> reasons[k] = (reasons[k] ?: 0) + v }
        }
        var built = TestLogic.assemble(unit.id, usedSlots, valid, plan)
        report(if (built.test != null) plan.total else plan.total - built.missing.size, if (built.test != null) "Done" else "Checking the words")
        var round = 0
        // a resumed build may be missing any number of questions; a fresh one only gets top-ups when most of it passed
        val limit = if (kept != null) usedSlots.size else maxOf(6, plan.total / 2)
        while (built.test == null && round < 2 && built.missing.size <= limit) {
            round++
            report(plan.total - built.missing.size, "Replacing ${built.missing.size} rejected questions")
            val more = ai.ask(TestLogic.prompt(plan, built.missing, avoid, known, rejected.toList().take(40), kanji), maxTokens = 6000, temperature = 0.8, json = true).text ?: break
            val p = TestLogic.parse(usedSlots, more, seenSet + valid.values.map { TestLogic.key(it.drill, plan.phrase) }, vocab, plan.phrase)
            valid = valid + p.valid
            rejected.addAll(p.outside)
            reasons.clear(); reasons.putAll(p.reasons)   // the latest request's reasons are the ones that still matter
            built = TestLogic.assemble(unit.id, usedSlots, valid, plan)
            report(if (built.test != null) plan.total else plan.total - built.missing.size, "Checking the words")
        }
        // a few questions short after the top-ups: use the questions that passed (the pass mark follows their number)
        if (built.test == null && built.missing.size <= maxOf(1, plan.total / 10)) built = TestLogic.assemble(unit.id, usedSlots, valid, plan, allowShort = true)
        leftover = if (built.test == null && valid.isNotEmpty()) Leftover(unit.id, plan, usedSlots, valid, rejected.toList()) else null
        val test = built.test
        if (test != null) { voiceAhead(lang, test); TestLoad(test, null) }
        else {
            val why = if (reasons.isEmpty()) "" else ": " + reasons.entries.joinToString(", ") { "${it.value} ${it.key}" }
            val words = if (rejected.isEmpty()) "" else " Words it kept using: ${rejected.take(3).joinToString("、")}."
            TestLoad(null, "The AI's test did not pass the checks (${built.missing.size} questions short$why).$words Try again.")
        }
    }

    /** The test's spoken sentences (typed questions with a Japanese line) are made in the background: one batch request; if that cannot be cut, the first 5 one by one. */
    private fun voiceAhead(lang: String, test: UnitTest) {
        val texts = test.questions.mapNotNull { (it.drill as? TypedDrill)?.jp?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()
        if (texts.isEmpty()) return
        scope.launch {
            runCatching {
                if (tts.makeBatch(texts, lang) == app.fluvour.audio.TtsManager.Batch.FELL_BACK) tts.prefetch(texts.take(5), lang)
            }
        }
    }

    /** Items for the typed drills among [questions], numbered by their place in the test. */
    private fun typedItems(test: UnitTest, answers: List<TestAnswer>): List<GradeItem> =
        test.questions.mapIndexedNotNull { i, q ->
            val d = q.drill as? TypedDrill ?: return@mapIndexedNotNull null
            GradeItem(i + 1, d.jp?.takeIf { it.isNotBlank() } ?: d.prompt, d.accepted, answers.getOrElse(i) { TestAnswer() }.text)
        }

    /**
     * Grades every answer of a finished test. Build and choose are exact; ALL typed answers go to the AI in ONE request.
     * If the AI cannot be reached, the local check decides (the test must always finish) and nothing is queued.
     */
    suspend fun gradeTest(test: UnitTest, answers: List<TestAnswer>): List<Boolean> = withContext(Dispatchers.IO) {
        val items = typedItems(test, answers)
        val local = { it: GradeItem -> LessonLogic.typedOk(test.questions[it.n - 1].drill as TypedDrill, it.answer) }
        val verdicts = if (items.isEmpty()) emptyMap() else GradeLogic.merge(items, askGrader(items) ?: emptyMap(), local)
        test.questions.mapIndexed { i, q ->
            if (q.drill is TypedDrill) verdicts[i + 1]?.ok ?: false else TestLogic.correct(q.drill, answers.getOrElse(i) { TestAnswer() })
        }
    }

    /** One request for [items]; null when the AI gave nothing usable (offline, no key, limit, bad reply). Temperature 0. */
    private suspend fun askGrader(items: List<GradeItem>): Map<Int, Verdict>? {
        val r = ai.ask(GradeLogic.prompt(items), maxTokens = 4000 + 300 * items.size, temperature = 0.0, json = true).text ?: return null
        return GradeLogic.parse(items, r).takeIf { it.isNotEmpty() }
    }

    /**
     * A typed drill answer. Returns the AI's verdict, or the local check when the AI is out of reach; in that case the answer is
     * queued and re-graded by [flushGrading] later (a miss the AI would have accepted is then taken back).
     */
    suspend fun gradeDrill(lang: String, drill: TypedDrill, answer: String): Verdict = withContext(Dispatchers.IO) {
        val item = GradeItem(1, drill.jp?.takeIf { it.isNotBlank() } ?: drill.prompt, drill.accepted, answer)
        askGrader(listOf(item))?.get(1)?.let { return@withContext it }
        val ok = LessonLogic.typedOk(drill, answer)
        if (drill.target != null) runCatching { queueGrading(lang, drill, answer, ok) }
        Verdict(ok, "")
    }

    private fun queueFile() = File(File(ctx.filesDir, "grading").also { it.mkdirs() }, "queue.tsv")
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    @Synchronized private fun queueGrading(lang: String, d: TypedDrill, answer: String, localOk: Boolean) {
        val line = listOf(lang, clean(d.target.orEmpty()), clean(d.why), clean(d.jp?.takeIf { it.isNotBlank() } ?: d.prompt),
            d.accepted.joinToString(" / ") { clean(it) }, clean(answer), if (localOk) "1" else "0").joinToString("\t")
        queueFile().appendText(line + "\n")
    }

    /** Re-grades answers queued while offline. A local miss the AI accepts loses its "Drill:" note. Stops quietly if the AI is still out of reach. */
    suspend fun flushGrading(): Int = withContext(Dispatchers.IO) {
        val f = queueFile(); if (!f.exists()) return@withContext 0
        val rows = f.readLines().map { it.split('\t') }.filter { it.size == 7 }
        if (rows.isEmpty()) { f.delete(); return@withContext 0 }
        val keep = ArrayList<List<String>>(); var done = 0
        for (batch in rows.chunked(10)) {
            val items = batch.mapIndexed { i, r -> GradeItem(i + 1, r[3], r[4].split(" / "), r[5]) }
            val got = askGrader(items)
            if (got == null) { keep.addAll(batch); continue }
            batch.forEachIndexed { i, r ->
                val v = got[i + 1]
                if (v == null) { keep.add(r); return@forEachIndexed }
                done++
                if (v.ok && r[6] == "0") runCatching { forgiveMiss(r[0], r[1], r[2], "Drill") }
            }
        }
        if (keep.isEmpty()) f.delete() else f.writeText(keep.joinToString("") { it.joinToString("\t") + "\n" })
        done
    }

    /** Removes a miss note that the AI's later verdict showed to be wrong. */
    private suspend fun forgiveMiss(lang: String, word: String, why: String, label: String) {
        val id = cards.cardsForWords(lang, listOf(word)).firstOrNull()?.first ?: return
        val card = cards.get(id) ?: return
        val line = "$label: $why"
        val notes = card.notes ?: return
        if (!notes.contains(line)) return
        cards.setAngle(id, null, notes.lines().filter { it != line }.joinToString("\n"))
    }

    /**
     * After the result: stores the score (graduates the unit at 25 or more), sends every missed word to review (reason saved with its card,
     * review due today), remembers the sentences so a retake is fresh, and logs flagged questions.
     */
    suspend fun finishTest(lang: String, unit: CurriculumUnit, test: UnitTest, result: TestResult): TestFinish = withContext(Dispatchers.IO) {
        runCatching { recordTest(lang, unit.id, result.score, result.pass) }
        // units without a check of their own (Unit 1) are covered by this one
        if (result.passed) for (id in test.covered) if (id != unit.id) runCatching { recordTest(lang, id, 0, 0) }
        val sent = LinkedHashSet<String>()
        for (q in result.missed) {
            val word = q.slot.word.word
            if (word in sent) continue
            runCatching {
                val id = cards.cardsForWords(lang, listOf(word)).firstOrNull()?.first
                if (id != null) { noteMiss(lang, word, q.drill.why, "Test"); cards.pullDue(id); sent.add(word) }
            }
        }
        runCatching { seenFile(unit.id).writeText((readSeen(unit.id) + test.questions.map { TestLogic.key(it.drill, test.phrase) }).takeLast(90).joinToString("\n")) }
        if (result.flagged.isNotEmpty()) runCatching {
            File(testDir(), "flags.txt").appendText(result.flagged.joinToString("") { "${unit.id}\t${it.slot.word.word}\t${TestLogic.key(it.drill)}\n" })
        }
        val seq = curriculum.paths(lang).flatMap { it.units }
        val next = if (result.passed) seq.getOrNull(seq.indexOfFirst { it.id == unit.id } + 1) else null
        if (result.passed && next != null) notice.value = "Passed. Unit ${next.order} is now open."
        TestFinish(sent.size, next?.order, next?.title)
    }

    /** For the unit check (step 8): keeps the best score, and marks the unit graduated at [passMark] or more (the check's own pass mark). */
    suspend fun recordTest(lang: String, unitId: String, score: Int, passMark: Int = 25) {
        val now = System.currentTimeMillis()
        val old = units.get(unitId)
        val passed = score >= passMark
        units.upsert(PathUnitEntity(
            unitId, lang, graduatedAt = old?.graduatedAt ?: if (passed) now else null,
            bestScore = maxOf(old?.bestScore ?: 0, score), updatedAt = now
        ))
    }
}

/** [lesson] or, when it could not be had, [message] for the screen. */
data class LessonLoad(val lesson: Lesson?, val message: String?)

/** [test] or, when it could not be had, [message] for the screen. */
data class TestLoad(val test: UnitTest?, val message: String?)

/** The loading screen's bar: [done] of [total] questions have passed the checks; [step] says what is happening. [total] 0 means not started. */
data class BuildProgress(val done: Int = 0, val total: Int = 0, val step: String = "")

/** What the Path screen says after the drills of a unit without a check: the order of the unit that opened next (null if none). */
data class LessonGrad(val nextOrder: Int?)

/** What the result screen adds to the score: how many words went to review and which unit opened next (null if none). */
data class TestFinish(val sentToReview: Int, val nextOrder: Int?, val nextTitle: String?)
