import app.fluvour.path.*
import kotlin.random.Random

private var failed = 0
private fun ok(name: String, cond: Boolean) { if (cond) println("ok $name") else { println("FAIL $name"); failed++ } }

private val GLOSSES = listOf("hello", "thank you", "sorry", "goodbye", "please", "yes", "no", "water", "book", "dog", "cat", "tree")
private val WORDS = listOf("こんにちは", "ありがとう", "ごめんなさい", "さようなら", "おねがい", "はい", "いいえ", "みず", "ほん", "いぬ", "ねこ", "き")

fun unit12() = CurriculumUnit("g1-01", 1, "Greetings", "A1", 20, "I can greet.", emptyList(), listOf("set phrases"),
    WORDS.mapIndexed { i, w -> CurriculumWord(w, w, GLOSSES[i]) })

fun lesson4(unit: CurriculumUnit): Lesson {
    fun chunk(i: Int, vararg toks: String) = LessonChunk(toks.mapIndexed { k, t -> LessonWord(t, "g$i$k") } + LessonWord("。", ""), "English sentence $i", "pattern $i")
    return Lesson(unit.id, listOf(chunk(0, "こんにちは", "たなか", "さん"), chunk(1, "ありがとう", "ございます"), chunk(2, "ごめんなさい", "おそく", "なって"), chunk(3, "さようなら", "また", "あした")), emptyList())
}

/** A learner who answers recognition right with [recog] chance and production right with [prod] chance. */
fun run(recog: Double, prod: Double, seed: Int): Triple<MasterySession, Int, List<String>> {
    val unit = unit12(); val (items, pool) = MasteryBuilder.build(unit, lesson4(unit), Random(seed))
    val s = MasterySession(items, pool, Random(seed)); val rnd = Random(seed + 99)
    var turns = 0; val order = ArrayList<String>()
    while (true) {
        val d = s.next() ?: break
        order.add(s.currentItem!!.id)
        val production = d is BuildDrill || d is TypedDrill
        s.record(rnd.nextDouble() < (if (production) prod else recog)); turns++
    }
    return Triple(s, turns, order)
}

fun main() {
    val unit = unit12(); val lesson = lesson4(unit)
    val (items, pool) = MasteryBuilder.build(unit, lesson, Random(1))
    ok("16 items: 12 words and 4 sentences", items.size == 16 && items.count { it.kind == ItemKind.WORD } == 12 && items.count { it.kind == ItemKind.SENTENCE } == 4)
    ok("every item has recognition, production and a proof drill", items.all { i -> pool[i.id].orEmpty().let { p -> p.any { !it.production } && p.any { it.production && !it.proof } && p.any { it.proof } } })
    ok("a word has choose, listen, fill, typed and spell drills", pool["w:ありがとう"].orEmpty().let { p -> p.count { !it.production } >= 3 && p.any { it.drill is TypedDrill } && p.any { it.drill is BuildDrill } })
    ok("a sentence has listen, choose, build and typed drills", pool["s:0"].orEmpty().let { p -> p.any { (it.drill as? ChooseDrill)?.audio != null } && p.any { it.drill is ChooseDrill && (it.drill as ChooseDrill).audio == null } && p.any { it.drill is BuildDrill } && p.any { it.drill is TypedDrill } })
    ok("fill drill hides the word with a blank", pool["w:ありがとう"].orEmpty().any { (it.drill as? ChooseDrill)?.prompt?.contains('＿') == true })
    ok("the build drill of a sentence has distinct chips and the answer in order", (pool["s:1"].orEmpty().first { it.drill is BuildDrill }.drill as BuildDrill).let { b -> b.chips.toSet().size == b.chips.size && b.answer == listOf("ありがとう", "ございます") && b.chips.containsAll(b.answer) })
    ok("choose options are distinct and the answer index is right", pool.values.flatten().mapNotNull { it.drill as? ChooseDrill }.all { it.options.toSet().size == 3 && it.answer in 0..2 })

    val (perfect, turns, order) = run(1.0, 1.0, 3)
    ok("a perfect learner finishes with every item proven", perfect.allDone && !perfect.capped && perfect.mastered == 16 && perfect.progress > 0.999f)
    ok("a perfect learner needs 3 drills per item (recognise, produce, proof)", turns == 48)
    ok("the same item never comes twice in a row", order.zipWithNext().none { (a, b) -> a == b })
    ok("an item is asked at least 3 times (recognise, produce, proof)", items.all { i -> order.count { it == i.id } >= 3 })

    val (guess, _, _) = run(0.33, 0.0, 4)
    ok("pure guessing never graduates", !guess.allDone && guess.capped)
    val (recogOnly, _, _) = run(1.0, 0.0, 5)
    ok("recognition alone is not enough (production is required)", !recogOnly.allDone)
    val (mostly, mt, _) = run(0.9, 0.8, 6)
    ok("a decent learner still finishes, with more drills than a perfect one", mostly.allDone && mt > turns)
    ok("misses are remembered for the end message", mostly.hardest().size <= 3 && mostly.correctCount < mostly.answered)

    // proof only after learning: the first drill of every item is recognition and a proof never comes before 3 answers
    run {
        val u = unit12(); val (it2, p2) = MasteryBuilder.build(u, lesson4(u), Random(8)); val s = MasterySession(it2, p2, Random(8))
        val seen = HashMap<String, Int>(); var early = false; var firstOk = true
        while (true) {
            val d = s.next() ?: break
            val id = s.currentItem!!.id; val n = seen[id] ?: 0
            if (s.inProof && n < 2) early = true
            if (n == 0 && (d is BuildDrill || d is TypedDrill)) firstOk = false
            seen[id] = n + 1; s.record(true)
        }
        ok("the first question on an item is recognition", firstOk)
        ok("a proof question never comes before the item was practised", !early)
        // progress can be saved and restored
        val s2 = MasterySession(it2, p2, Random(8)); s2.restore(s.snapshot())
        ok("saved progress restores (a finished session stays finished)", s2.allDone && s2.next() == null)
        val s3 = MasterySession(it2, p2, Random(8)); val d0 = s3.next(); s3.record(true); val snap = s3.snapshot()
        val s4 = MasterySession(it2, p2, Random(8)); s4.restore(snap)
        ok("a half-done session resumes with its points", d0 != null && s4.progress == s3.progress && s4.progress > 0f)
    }
    run {
        val u = unit12(); val (it2, p2) = MasteryBuilder.build(u, lesson4(u), Random(9)); val s = MasterySession(it2, p2, Random(9))
        var wrongProofKeepsItOpen = false
        val d1 = s.next(); s.record(false)
        ok("a miss lowers nothing below zero and the session goes on", d1 != null && s.progress >= 0f && s.next() != null)
    }
    ok("an empty session ends at once", MasterySession(emptyList(), emptyMap()).let { it.next() == null && it.allDone })
    // session length for a unit of 12 words and 4 sentences (information, not a check)
    for ((r, p) in listOf(1.0 to 1.0, 0.9 to 0.8, 0.8 to 0.6)) {
        val ts = (1..20).map { seed -> val x = run(r, p, seed); Triple(x.second, x.first.allDone, x.first.capped) }
        println("info recognition=$r production=$p: avg ${ts.map { it.first }.average().toInt()} drills (min ${ts.minOf { it.first }}, max ${ts.maxOf { it.first }}), finished ${ts.count { it.second }}/20")
    }
    println(if (failed == 0) "ALL OK" else "FAILED $failed")
}
