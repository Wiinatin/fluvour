import app.fluvour.path.*

private var failed = 0
private fun ok(name: String, cond: Boolean) { if (cond) println("ok $name") else { println("FAIL $name"); failed++ } }

private val unit = CurriculumUnit("u1", 1, "Whose is it?", "N5", 20, "I can say who something belongs to.", emptyList(), listOf("の"),
    listOf("これ", "それ", "私", "本", "かばん", "先生", "誰", "高い").map { CurriculumWord(it, it, "x") })

private const val GOOD = """```json
{"chunks":[
{"words":[{"t":"これ","g":"this"},{"t":"は","g":"topic"},{"t":"私","g":"I"},{"t":"の","g":"possession"},{"t":"本","g":"book"},{"t":"です","g":"is"},{"t":"。","g":""}],"en":"This is my book.","pattern":"noun + の + noun"},
{"words":[{"t":"それ","g":"that"},{"t":"は","g":"topic"},{"t":"誰","g":"who"},{"t":"の","g":"of"},{"t":"かばん","g":"bag"},{"t":"ですか","g":"is it"}],"en":"Whose bag is that?","pattern":"誰の + noun"},
{"words":[{"t":"先生","g":"teacher"},{"t":"の","g":"of"},{"t":"本","g":"book"}],"en":"The teacher's book.","pattern":"の joins nouns"},
{"words":[{"t":"この","g":"this"},{"t":"本","g":"book"},{"t":"は","g":"topic"},{"t":"高","g":"expensive"},{"t":"い","g":""},{"t":"です","g":"is"}],"en":"This book is expensive.","pattern":"い-adjective + です"}],
"drills":[
{"type":"build","prompt":"This is my book.","chips":["これ","は","私","の","本","です","が"],"answer":["これ","は","私","の","本","です"],"why":"の joins nouns.","target":"本"},
{"type":"build","prompt":"Whose bag?","chips":["誰","の","かばん","先生"],"answer":["誰","の","かばん"],"why":"誰の.","target":"誰"},
{"type":"build","prompt":"Teacher's book","chips":["先生","の","本"],"answer":["先生","の","本"],"why":"の.","target":"先生"},
{"type":"choose","prompt":"Whose bag is that?","options":["それは誰のかばんですか","それは私のかばんですか","これは誰のかばんですか"],"answer":0,"why":"誰 is whose.","target":"誰"},
{"type":"choose","prompt":"that (near you)","options":["これ","それ","あれ"],"answer":1,"why":"それ.","target":"それ"},
{"type":"choose","prompt":"book","options":["本","私","車"],"answer":0,"why":"本.","target":"本"},
{"type":"typed","prompt":"What does this mean?","jp":"誰の本","accepted":["whose book","whose book is it"],"why":"誰の本.","target":"誰"},
{"type":"typed","prompt":"What does this mean?","jp":"私の本","accepted":["my book"],"why":"私の本.","target":"nonsense"}]}
```"""

fun main() {
    ok("json: object", MiniJson.parse("""{"a":[1,2.5,"x\n\u3042",true,null],"b":{}}""") == mapOf("a" to listOf(1.0, 2.5, "x\nあ", true, null), "b" to emptyMap<String, Any?>()))
    ok("json: bad input is null", MiniJson.parse("{\"a\":") == null && MiniJson.parse("nope") == null)
    ok("json: extract from fence", MiniJson.extract("Here:\n```json\n{\"a\":1}\n```") == "{\"a\":1}")

    val r = LessonLogic.parse(unit, GOOD)
    ok("good lesson passes", r.lesson != null)
    val l = r.lesson!!
    ok("4 chunks, 8 drills", l.chunks.size == 4 && l.drills.size == 8)
    ok("chunk sentence joins words", l.chunks[0].jp == "これは私の本です。")
    ok("unknown target becomes null", (l.drills[7] as TypedDrill).target == null && l.drills[0].target == "本")
    ok("coverage counts stems (高い via 高)", r.coverage == 1.0)

    ok("not json fails", LessonLogic.parse(unit, "Sorry, I cannot.").lesson == null)
    ok("too few chunks fails", LessonLogic.parse(unit, GOOD.replace(Regex("""\{"words":\[\{"t":"先生".*?"pattern":"の joins nouns"\},"""), "").replace(Regex("""\{"words":\[\{"t":"それ".*?"pattern":"誰の \+ noun"\},"""), "")).lesson == null)

    val badBuild = GOOD.replace("\"answer\":[\"誰\",\"の\",\"かばん\"]", "\"answer\":[\"誰\",\"の\",\"車\"]")
    val rb = LessonLogic.parse(unit, badBuild)
    ok("build drill whose answer is not in the chips is dropped", rb.lesson != null && rb.dropped == 1 && rb.lesson!!.drills.size == 7)

    val badChoose = GOOD.replace("\"answer\":1,\"why\":\"それ.\"", "\"answer\":5,\"why\":\"それ.\"")
    ok("choose drill with bad index is dropped", LessonLogic.parse(unit, badChoose).dropped == 1)

    val few = GOOD.replace("\"本\",\"私\",\"車\"", "\"本\",\"本\",\"車\"")
    ok("choose drill with repeated option is dropped", LessonLogic.parse(unit, few).dropped == 1)

    val tiny = CurriculumUnit("u2", 2, "T", "N5", 20, "c", emptyList(), listOf("g"), listOf("犬", "猫", "鳥", "魚", "馬").map { CurriculumWord(it, it, "x") })
    val rc = LessonLogic.parse(tiny, GOOD)
    ok("missing target words fail the coverage check", rc.lesson == null && rc.problem!!.contains("target words"))

    val t = TypedDrill("p", "誰の本", listOf("whose book", "whose book is it"), "w", null)
    ok("typed: exact", LessonLogic.typedOk(t, "Whose book"))
    ok("typed: case, punctuation", LessonLogic.typedOk(t, "  WHOSE book?? "))
    ok("typed: articles ignored", LessonLogic.typedOk(TypedDrill("p", null, listOf("my book"), "w", null), "the my book") )
    ok("typed: extra words allowed", LessonLogic.typedOk(t, "it means whose book"))
    ok("typed: wrong", !LessonLogic.typedOk(t, "my book"))
    ok("typed: empty", !LessonLogic.typedOk(t, "   "))
    ok("typed: too many extra words", !LessonLogic.typedOk(t, "I really do not know but maybe it is whose book"))

    val b = l.drills[0] as BuildDrill
    ok("build: right order", LessonLogic.buildOk(b, b.answer))
    ok("build: wrong order", !LessonLogic.buildOk(b, b.answer.reversed()))

    val p = LessonLogic.prompt(unit)
    ok("prompt names the unit words and goal", p.contains("かばん") && p.contains("I can say who something belongs to."))

    ok("json: trailing commas are accepted", MiniJson.parse("{\"a\":[1,2,],\"b\":\"x,}\",}") == mapOf("a" to listOf(1.0, 2.0), "b" to "x,}"))
    ok("json: cut off reply is detected", MiniJson.looksCutOff("{\"chunks\":[{\"words\":[") && !MiniJson.looksCutOff("{\"a\":\"}\"}") && !MiniJson.looksCutOff("sorry"))

    // ---- fill-the-blank and listening drills ----
    run {
        val fill = LessonLogic.drill(MiniJson.parse("""{"type":"fill","prompt":"これは＿です。(This is a book)","options":["本","犬","水"],"answer":0,"why":"w","target":"本"}"""), setOf("本"))
        ok("fill drill parses as a choose drill with a blank", fill is ChooseDrill && fill.audio == null && '＿' in fill.prompt && fill.target == "本")
        ok("fill drill without a blank is dropped", LessonLogic.drill(MiniJson.parse("""{"type":"fill","prompt":"これはです。","options":["本","犬","水"],"answer":0,"why":"w"}"""), emptySet()) == null)
        val listen = LessonLogic.drill(MiniJson.parse("""{"type":"listen","prompt":"What does it mean?","jp":"これは本です。","options":["This is a book","This is a dog","This is water"],"answer":0,"why":"w","target":"本"}"""), setOf("本"))
        ok("listen drill keeps its sentence as audio", listen is ChooseDrill && listen.audio == "これは本です。" && listen.options.size == 3)
        ok("listen drill without Japanese is dropped", LessonLogic.drill(MiniJson.parse("""{"type":"listen","prompt":"p","jp":"This is a book","options":["a","b","c"],"answer":0,"why":"w"}"""), emptySet()) == null)
        ok("listen drill with a bad index is dropped", LessonLogic.drill(MiniJson.parse("""{"type":"listen","prompt":"p","jp":"これ","options":["a","b","c"],"answer":3,"why":"w"}"""), emptySet()) == null)
        val pr = LessonLogic.prompt(unit)
        ok("prompt asks for the new drill types", pr.contains("\"type\":\"fill\"") && pr.contains("\"type\":\"listen\"") && pr.contains("1 of type fill"))
    }

    println(if (failed == 0) "ALL OK" else "FAILED $failed")
    if (failed > 0) System.exit(1)
}
