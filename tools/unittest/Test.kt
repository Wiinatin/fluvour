import app.fluvour.path.*
import kotlin.random.Random

var failed = 0
fun ok(name: String, cond: Boolean) { println((if (cond) "ok " else "FAIL ") + name); if (!cond) failed++ }

fun words(chars: String) = chars.map { CurriculumWord(it.toString(), "r$it", "g$it") }
fun unitOf(id: String, order: Int, chars: String) = CurriculumUnit(id, order, "Title $id", "A1", 20, "I can do $id.", emptyList(), listOf("grammar $id"), words(chars))

fun mem(phase: String = "REVIEW", stab: Double = 10.0, lapses: Int = 0, dueAt: Long? = null) = WordMemory("x", phase, stab, lapses, dueAt, null)
fun cand(unit: String, ch: Char, m: WordMemory?, recent: Boolean = false, pre: Boolean = false) =
    OlderCandidate(unit, "Title $unit", CurriculumWord(ch.toString(), "r", "g"), m, recent, pre)

/** One valid question JSON for a slot, with the assigned word inside the Japanese. */
fun qJson(s: TestSlot, jpWord: String = s.word.word, n: Int = s.n): String = when (s.type) {
    "build" -> """{"n":$n,"type":"build","prompt":"This is a ${s.word.gloss} $n","chips":["これは","${jpWord}","です$n","が"],"answer":["これは","${jpWord}","です$n"],"why":"w","target":"${s.word.word}"}"""
    "typed" -> """{"n":$n,"type":"typed","prompt":"What does this mean?","jp":"これは${jpWord}です $n","accepted":["this is ${s.word.gloss}","it is ${s.word.gloss}"],"why":"w","target":"${s.word.word}"}"""
    else -> """{"n":$n,"type":"choose","prompt":"Which one is ${s.word.gloss} $n?","options":["opt1","${jpWord}","opt2"],"answer":1,"why":"w","target":"${s.word.word}"}"""
}
fun reply(items: List<String>) = "Here you go:\n```json\n{\"questions\":[" + items.joinToString(",") + "]}\n```"

fun main() {
    val now = 1_800_000_000_000L; val day = 86_400_000L
    val unit = unitOf("u4", 4, "本車犬猫水山川空海花")

    // ---- scoring of older words ----
    val weak = olderScoreOf(cand("u1", 'a', mem("REVIEW", 1.0, 3, now - 10 * day)))
    val strong = olderScoreOf(cand("u1", 'b', mem("REVIEW", 60.0, 0, now + 30 * day)))
    ok("lapsed, overdue, low stability scores above a stable word", weak > strong)
    ok("a word with no card scores 0", TestLogic.olderScore(cand("u1", 'c', null), now) == 0.0)
    ok("recently graduated adds to the score", olderScoreOf(cand("u1", 'd', mem(), recent = true)) > olderScoreOf(cand("u1", 'd', mem())))
    ok("prerequisite unit weighs more", olderScoreOf(cand("u1", 'e', mem(), pre = true)) > olderScoreOf(cand("u1", 'e', mem())))
    ok("a card not yet in review (new or learning) scores above a settled one", olderScoreOf(cand("u1", 'f', mem("LEARNING", 0.5))) > olderScoreOf(cand("u1", 'f', mem("REVIEW", 20.0))))

    // ---- picking older words ----
    val cands = listOf("u1" to "あいうえおかきくけこ", "u2" to "さしすせそたちつてと", "u3" to "なにぬねのはひふへほ").flatMap { (u, cs) -> cs.map { cand(u, it, mem("REVIEW", 5.0, 1)) } } +
        listOf(cand("u1", 'ん', null), cand("u2", 'ゃ', null))
    val picked = TestLogic.pickOlder(cands, 10, now)
    ok("picks the number asked for", picked.size == 10)
    ok("never picks a word without a card", picked.none { it.memory == null })
    ok("spreads over units (3 per unit while others have words)", picked.groupBy { it.unitId }.values.all { it.size <= 4 } && picked.map { it.unitId }.toSet().size == 3)
    val onlyOne = TestLogic.pickOlder(cands.filter { it.unitId == "u1" }, 5, now)
    ok("fills from one unit when it is the only one", onlyOne.size == 5)
    ok("fewer cards than asked gives fewer", TestLogic.pickOlder(cands.take(2), 10, now).size == 2)
    ok("no candidates gives none", TestLogic.pickOlder(emptyList(), 10, now).isEmpty())

    // ---- question places ----
    val slots = TestLogic.buildSlots(unit, picked.take(10), Random(7))
    val primary = slots.filter { it.n <= 30 }; val spares = slots.filter { it.n > 30 }
    ok("30 places plus 3 spare", primary.size == 30 && spares.size == 3 && slots.map { it.n }.toSet().size == 33)
    ok("9 older words in the 30, 21 from the unit", primary.count { it.older } == 9 && primary.count { !it.older } == 21)
    ok("one older and two unit places spare", spares.count { it.older } == 1 && spares.count { !it.older } == 2)
    ok("types are about a third each", listOf("build", "typed", "choose").all { t -> primary.count { it.type == t } == 10 })
    ok("no two questions on the same word side by side", (1 until primary.size).all { primary[it].word.word != primary[it - 1].word.word })
    ok("older places carry their own unit", primary.filter { it.older }.all { it.unitId != "u4" } && primary.filter { !it.older }.all { it.unitId == "u4" })
    ok("every unit word is tested at least once", unit.words.all { w -> primary.any { !it.older && it.word.word == w.word } })
    val noOlder = TestLogic.buildSlots(unit, emptyList(), Random(3))
    ok("first unit: all 30 from the unit", noOlder.filter { it.n <= 30 }.all { !it.older } && noOlder.size == 33)
    val few = TestLogic.buildSlots(unit, picked.take(5), Random(3))
    ok("5 older words available: 5 older places and no older spare", few.filter { it.n <= 30 }.count { it.older } == 5 && few.filter { it.n > 30 }.none { it.older })
    val twelve = TestLogic.buildSlots(unit, picked.take(10), Random(3))
    ok("10 older words: 9 placed, 1 spare", twelve.filter { it.n <= 30 }.count { it.older } == 9)

    // ---- prompt ----
    val p = TestLogic.prompt(unit, slots, listOf("これは本です", "車があります"))
    ok("prompt lists every place with its word", slots.all { p.contains("${it.n}. type ${it.type}, assigned word ${it.word.word}") })
    ok("prompt names older units and lines to avoid", p.contains("earlier unit \"Title u1\"") && p.contains("これは本です") && p.contains("Write exactly 33 questions"))
    ok("prompt asks for JSON with the unit goal", p.contains("I can do u4.") && p.contains("\"questions\""))

    // ---- reading a reply ----
    val good = reply(slots.map { qJson(it) })
    val parsed = TestLogic.parse(slots, good, emptySet())
    ok("a full good reply gives all 33", parsed.valid.size == 33 && parsed.dropped == 0)
    ok("every question's target is its assigned word", parsed.valid.values.all { it.drill.target == it.slot.word.word })
    val missingWord = reply(slots.map { qJson(it, jpWord = if (it.n == 5) "X" else it.word.word) })
    ok("a question without its assigned word is dropped", TestLogic.parse(slots, missingWord, emptySet()).valid.containsKey(5).not() && TestLogic.parse(slots, missingWord, emptySet()).valid.size == 32)
    ok("a reply that is not JSON gives nothing", TestLogic.parse(slots, "sorry", emptySet()).valid.isEmpty())
    ok("unknown question numbers are dropped", TestLogic.parse(slots, reply(listOf(qJson(slots[0], n = 99))), emptySet()).valid.isEmpty())
    val dup = reply(listOf(qJson(slots[0]), qJson(slots[0])))
    ok("the same number twice keeps one", TestLogic.parse(slots, dup, emptySet()).valid.size == 1)
    val k0 = TestLogic.key(parsed.valid[1]!!.drill)
    ok("a sentence seen in an earlier test is dropped", !TestLogic.parse(slots, good, setOf(k0)).valid.containsKey(1) && TestLogic.parse(slots, good, setOf(k0)).valid.size == 32)
    val badBuild = """{"n":1,"type":"build","prompt":"p","chips":["a","b"],"answer":["a","z"],"why":"w"}"""
    ok("a malformed drill is dropped", TestLogic.parse(slots, reply(listOf(badBuild)), emptySet()).valid.isEmpty())
    val noWhy = qJson(slots[0]).replace("\"why\":\"w\"", "\"why\":\"\"")
    ok("a question without a reason is dropped", TestLogic.parse(slots, reply(listOf(noWhy)), emptySet()).valid.isEmpty())
    val noTarget = qJson(slots[1]).replace(",\"target\":\"${slots[1].word.word}\"", "")
    ok("a missing target field is filled from the place", TestLogic.parse(slots, reply(listOf(noTarget)), emptySet()).valid[2]?.drill?.target == slots[1].word.word)

    // ---- assembling 30 ----
    val all = TestLogic.assemble("u4", slots, parsed.valid)
    ok("all 33 valid builds a test of 30", all.test?.questions?.size == 30 && all.missing.isEmpty())
    ok("the 30 keep their place numbers in order", all.test!!.questions.map { it.slot.n } == (1..30).toList())
    val holeCur = parsed.valid.filterKeys { it != primary.first { s -> !s.older }.n }
    val afterHole = TestLogic.assemble("u4", slots, holeCur)
    ok("a failed unit place is covered by a spare", afterHole.test != null && afterHole.test!!.questions.size == 30)
    val holeOld = parsed.valid.filterKeys { it != primary.first { s -> s.older }.n }
    val afterOld = TestLogic.assemble("u4", slots, holeOld)
    ok("a failed older place is covered by the older spare", afterOld.test != null && afterOld.test!!.questions.count { it.slot.older } == 9)
    val holes3 = parsed.valid.filterKeys { k -> k !in primary.filter { !it.older }.take(3).map { it.n } }
    val three = TestLogic.assemble("u4", slots, holes3)
    ok("three unit places failed but only two spares: one is missing", three.test == null && three.missing.size == 1 && !three.missing[0].older)
    ok("the top-up prompt only asks for the missing place", TestLogic.prompt(unit, three.missing, emptyList()).contains("Write exactly 1 questions") && TestLogic.prompt(unit, three.missing, emptyList()).contains("${three.missing[0].n}. type"))
    val topUp = TestLogic.parse(slots, reply(listOf(qJson(three.missing[0]))), emptySet())
    ok("a top-up completes the test", TestLogic.assemble("u4", slots, holes3 + topUp.valid).test != null)

    // ---- answers and score ----
    val b = BuildDrill("p", listOf("a", "b", "c"), listOf("a", "b"), "w", null)
    ok("build: right order", TestLogic.correct(b, TestAnswer(tray = listOf("a", "b"))) && !TestLogic.correct(b, TestAnswer(tray = listOf("b", "a"))))
    val c = ChooseDrill("p", listOf("x", "y", "z"), 2, "w", null)
    ok("choose: right option only", TestLogic.correct(c, TestAnswer(choice = 2)) && !TestLogic.correct(c, TestAnswer(choice = 0)) && !TestLogic.correct(c, TestAnswer()))
    val t = TypedDrill("p", "jp", listOf("my book"), "w", null)
    ok("typed: accepted answer", TestLogic.correct(t, TestAnswer(text = "My book.")) && !TestLogic.correct(t, TestAnswer(text = "dog")))
    val test = all.test!!
    val allRight = TestLogic.score(test, List(30) { true }, emptySet())
    ok("30 right passes", allRight.score == 30 && allRight.passed && allRight.missed.isEmpty() && allRight.currentRight == 21 && allRight.olderRight == 9)
    val rights25 = List(30) { it < 25 }
    ok("25 right passes, 24 does not", TestLogic.score(test, rights25, emptySet()).passed && !TestLogic.score(test, List(30) { it < 24 }, emptySet()).passed)
    val mixed = TestLogic.score(test, List(30) { it % 5 != 0 }, emptySet())
    ok("misses are split between unit and older questions", mixed.missed.size == 6 && mixed.currentTotal == 21 && mixed.olderTotal == 9 && mixed.currentRight + mixed.olderRight == mixed.score)
    val flaggedOne = TestLogic.score(test, List(30) { it >= 6 }, setOf(0, 1, 2))
    ok("flagged questions never count as misses", flaggedOne.missed.size == 3 && flaggedOne.score == 27 && flaggedOne.flagged.size == 3)
    ok("too few verdicts count as misses", TestLogic.score(test, emptyList(), emptySet()).score == 0)

    // ---- unit check size, covered units and the known-word check (Unit 1 rework) ----
    run {
        val g1 = unitOf("g1-01", 1, "あいうえおかきくけこさし"); val g2 = unitOf("g1-02", 2, "たちつてとなにぬねの"); val g3 = unitOf("g1-03", 3, "はひふへほまみむめも")
        val seq = listOf(g1, g2, g3)
        ok("unit 1 has no check", !TestRules.hasTest(g1) && TestRules.plan(seq, g1) == null)
        ok("unit 1 is covered by unit 2", TestRules.coveredBy(seq, g1)?.id == "g1-02" && TestRules.coveredBy(seq, g2) == null)
        val p2 = TestRules.plan(seq, g2)!!
        ok("unit 2's check covers units 1 and 2", p2.units.map { it.id } == listOf("g1-01", "g1-02") && TestRules.orders(p2.units) == "1 and 2")
        ok("22 words give 30 questions, 25 to pass, set-phrase mode", p2.total == 30 && p2.pass == 25 && p2.phrase)
        val p3 = TestRules.plan(seq, g3)!!
        ok("unit 3 covers only itself, is not set-phrase, 14 questions, 12 to pass", p3.units.size == 1 && !p3.phrase && p3.total == 14 && p3.pass == 12)
        ok("pass mark is 83 percent rounded up", listOf(10, 15, 22, 30).map { TestRules.passFor(it) } == listOf(9, 13, 19, 25))
        ok("size follows the words but stays between 10 and 30", TestRules.total(5) == 10 && TestRules.total(11) == 15 && TestRules.total(40) == 30)

        val s2 = TestLogic.buildSlots(p2, emptyList(), Random(5))
        ok("unit 2's check: 30 places plus 3 spare, all from units 1 and 2", s2.size == 33 && s2.filter { it.n <= 30 }.all { !it.older && (it.unitId == "g1-01" || it.unitId == "g1-02") })
        ok("every word of both units is tested", (g1.words + g2.words).all { w -> s2.filter { it.n <= 30 }.any { it.word.word == w.word } })
        val s3 = TestLogic.buildSlots(TestPlan(listOf(g3), 14, 12, false), picked.take(10), Random(9))
        ok("14 questions: 4 older (30 percent), 10 from the unit, 3 spare", s3.size == 17 && s3.filter { it.n <= 14 }.count { it.older } == 4)

        val full = TestLogic.parse(s2, reply(s2.map { qJson(it) }), emptySet(), null, true)
        val built = TestLogic.assemble("g1-02", s2, full.valid, p2)
        val t2 = built.test!!
        ok("assembled check carries pass mark, covered units and mode", t2.questions.size == 30 && t2.pass == 25 && t2.covered == listOf("g1-01", "g1-02") && t2.phrase)
        ok("score uses the check's own pass mark", TestLogic.score(t2, List(30) { it < 25 }, emptySet()).let { it.passed && it.pass == 25 } && !TestLogic.score(t2, List(30) { it < 24 }, emptySet()).passed)
        val full3 = TestLogic.parse(s3, reply(s3.map { qJson(it) }), emptySet(), null, false)
        val t3 = TestLogic.assemble("g1-03", s3, full3.valid, TestPlan(listOf(g3), 14, 12, false)).test!!
        ok("a 14-question check passes at 12, not at 11", t3.questions.size == 14 && TestLogic.score(t3, List(14) { it < 12 }, emptySet()).passed && !TestLogic.score(t3, List(14) { it < 11 }, emptySet()).passed)

        val anyBuild = full.valid.values.first { it.drill is BuildDrill }.drill
        ok("by-type keys carry the question format", TestLogic.key(anyBuild, true).startsWith("build:") && TestLogic.key(anyBuild) == TestLogic.key(anyBuild, true).removePrefix("build:"))
        val noKanji: (String) -> List<String> = { t -> if (t.contains("禁")) listOf("禁") else emptyList() }
        val withBad = TestLogic.parse(s2, reply(s2.map { qJson(it, jpWord = if (it.n == 7) it.word.word + "禁" else it.word.word) }), emptySet(), noKanji, true)
        ok("a question with a word outside the pool is dropped and the word reported", withBad.valid.size == 32 && !withBad.valid.containsKey(7) && "禁" in withBad.outside)

        ok("the same word is never asked in the same format twice (20 seeds)", (1..20).all { seed ->
            TestLogic.buildSlots(p2, emptyList(), Random(seed)).filter { it.n <= 30 }.groupBy { it.word.word }.values.all { g -> g.map { it.type }.distinct().size == g.size }
        })
        ok("types stay about a third each after the repair", TestLogic.buildSlots(p2, emptyList(), Random(4)).filter { it.n <= 30 }.groupBy { it.type }.values.all { it.size == 10 })
        val oneShort = full.valid.filterKeys { it <= 30 && it != 12 }
        ok("one question short is not a test without the last resort", TestLogic.assemble("g1-02", s2, oneShort, p2).test == null)
        val shortTest = TestLogic.assemble("g1-02", s2, oneShort, p2, allowShort = true).test
        ok("with the last resort a check one short is made with a matching pass mark", shortTest != null && shortTest.questions.size == 29 && shortTest.pass == TestRules.passFor(29))
        ok("a check far too short is still refused", TestLogic.assemble("g1-02", s2, full.valid.filterKeys { it <= 5 }, p2, allowShort = true).test == null)
        ok("drops are explained", withBad.reasons["words not learned yet"] == 1)

        val pp = TestLogic.prompt(p2, s2, emptyList(), listOf("おはよう", "ありがとう"), listOf("禁"), "山川")
        ok("prompt carries the vocabulary rule, kanji, rejected words and the set-phrase style",
            pp.contains("VOCABULARY RULE") && pp.contains("おはよう、ありがとう") && pp.contains("山川") && pp.contains("禁") && pp.contains("set phrases only") && pp.contains("Unit 1:") && !pp.contains("Every sentence must be new"))
        ok("lesson prompt carries the vocabulary rule", LessonLogic.prompt(unit, listOf("水"), "山").contains("水") && LessonLogic.prompt(unit, emptyList(), "").contains("knows no other words yet"))

        val pool = KnownPool(listOf("ありがとう", "水", "田中さん"), listOf("みず"), setOf('山'))
        fun tok(surface: String, pos: String = "名詞", p2: String = "一般", p3: String = "*", at: Int = 0) = JaToken(surface, surface, pos, p2, p3, at)
        ok("a known word passes", VocabCheck.outside("水です", pool, listOf(tok("水"), tok("です", "助動詞", at = 1))).isEmpty())
        ok("an unknown word is reported", VocabCheck.outside("犬です", pool, listOf(tok("犬"), tok("です", "助動詞", at = 1))) == listOf("犬"))
        ok("a person's name passes", VocabCheck.outside("田中です", pool, listOf(tok("田中", "名詞", "固有名詞", "人名"), tok("です", "助動詞", at = 2))).isEmpty())
        ok("a phrase of the pool passes even when the splitter cuts it into pieces", VocabCheck.outside("ありがとう", pool, listOf(tok("ありが"), tok("とう", at = 3))).isEmpty())
        ok("a matured kanji passes, an unmatured one does not", VocabCheck.outside("山", pool, listOf(tok("山"))).isEmpty() && VocabCheck.outside("川", pool, listOf(tok("川"))) == listOf("川"))
        ok("the kana reading of a known word passes", VocabCheck.outside("みず", pool, listOf(tok("みず"))).isEmpty())
        ok("punctuation and English always pass", VocabCheck.outside("Hello。", pool, listOf(tok("Hello"), tok("。", "記号", at = 5))).isEmpty())
    }

    println(if (failed == 0) "ALL OK" else "FAILED $failed")
    if (failed > 0) System.exit(1)
}

fun olderScoreOf(c: OlderCandidate) = TestLogic.olderScore(c, 1_800_000_000_000L)
