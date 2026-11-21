import app.fluvour.ai.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    // transcript: clean, fenced, chatter around, trailing comma, objects as marks, mark not in text, silence, garbage
    val clean = """{"transcript":"ผมไป market เมื่อวาน","substituted":["market"]}"""
    check("transcript clean", SpeakingParse.transcript(clean) == Transcript("ผมไป market เมื่อวาน", listOf("market")))
    check("transcript fenced", SpeakingParse.transcript("```json\n$clean\n```")?.substituted == listOf("market"))
    check("transcript chatter", SpeakingParse.transcript("Here you go:\n$clean\nHope that helps!")?.text == "ผมไป market เมื่อวาน")
    check("transcript trailing comma", SpeakingParse.transcript("""{"transcript":"a b","substituted":["b",],}""")?.substituted == listOf("b"))
    check("transcript marks as objects", SpeakingParse.transcript("""{"transcript":"a b","substituted":[{"word":"b"}]}""")?.substituted == listOf("b"))
    check("transcript mark missing from text dropped", SpeakingParse.transcript("""{"transcript":"a b","substituted":["zzz","b","b"]}""")?.substituted == listOf("b"))
    check("transcript silence", SpeakingParse.transcript("""{"transcript":"","substituted":[]}""") == Transcript("", emptyList()))
    check("transcript text key", SpeakingParse.transcript("""{"text":"hi"}""")?.text == "hi")
    check("transcript plain text is null", SpeakingParse.transcript("I heard: hello there") == null)
    check("transcript empty reply null", SpeakingParse.transcript("") == null)
    check("transcript escapes", SpeakingParse.transcript("""{"transcript":"he said \"hi\"\nthen left","substituted":[]}""")?.text == "he said \"hi\"\nthen left")

    // improved
    val imp = """{"improved":"ผมไปตลาดเมื่อวาน","newWords":[{"word":"ตลาด","meaning":"market","example":"ผมไปตลาด"},{"word":"ตลาด","meaning":"dup"},{"word":"","meaning":"x"},{"word":"วาน","meaning":""},{"word":"เมื่อวาน","meaning":"yesterday"}]}"""
    val i = SpeakingParse.improved("```\n$imp\n```")
    check("improved text", i?.improved == "ผมไปตลาดเมื่อวาน")
    check("improved words deduped and blanks dropped", i?.newWords == listOf(NewWord("ตลาด", "market", "ผมไปตลาด"), NewWord("เมื่อวาน", "yesterday", null)))
    check("improved no words", SpeakingParse.improved("""{"improved":"ok","newWords":[]}""") == Improved("ok", emptyList()))
    check("improved missing list", SpeakingParse.improved("""{"improved":"ok"}""")?.newWords == emptyList<NewWord>())
    check("improved snake case key", SpeakingParse.improved("""{"improved":"ok","new_words":[{"word":"a","meaning":"b"}]}""")?.newWords?.size == 1)
    check("improved blank text null", SpeakingParse.improved("""{"improved":"  ","newWords":[]}""") == null)
    check("improved garbage null", SpeakingParse.improved("sorry, I cannot") == null)
    check("improved capped", SpeakingParse.improved("""{"improved":"x","newWords":[${(1..40).joinToString(",") { """{"word":"w$it","meaning":"m"}""" }}]}""")?.newWords?.size == 20)

    // segments
    check("segments none", SpeakingParse.segments("abc", emptyList()) == listOf(Segment("abc", false)))
    check("segments empty text", SpeakingParse.segments("", listOf("a")).isEmpty())
    check("segments mark", SpeakingParse.segments("go to market now", listOf("market")) == listOf(Segment("go to ", false), Segment("market", true), Segment(" now", false)))
    check("segments all occurrences", SpeakingParse.segments("a b a", listOf("a")).count { it.marked } == 2)
    check("segments overlap merged", SpeakingParse.segments("abcd", listOf("abc", "bcd")) == listOf(Segment("abcd", true)))
    check("segments rebuild text", SpeakingParse.segments("x ab y ab z", listOf("ab", "y")).joinToString("") { it.text } == "x ab y ab z")

    // stored JSON round trip, with characters that need escaping
    val words = listOf(NewWord("a\"b", "m\\n\nl", "ex\tample", "id-1"), NewWord("ตลาด", "market"), NewWord("c", "d", null, "id-2"))
    check("newWords round trip", SpeakingParse.newWordsFrom(SpeakingParse.newWordsJson(words)) == words)
    val subs = listOf("market", "say \"hi\"", "\u0001ctl")
    check("substituted round trip", SpeakingParse.substitutedFrom(SpeakingParse.substitutedJson(subs)) == subs)
    check("stored null and junk", SpeakingParse.newWordsFrom(null).isEmpty() && SpeakingParse.substitutedFrom("not json").isEmpty())
    check("empty lists", SpeakingParse.substitutedJson(emptyList()) == "[]" && SpeakingParse.newWordsJson(emptyList()) == "[]")

    // topic titles and cues
    check("titles", SpeakingParse.titles("```json\n[\"อาหาร\", \"บ้าน\", \"อาหาร\", \" \", {\"title\":\"งาน\"}]\n```") == listOf("อาหาร", "บ้าน", "งาน"))
    check("titles garbage", SpeakingParse.titles("none").isEmpty())
    check("titles trailing comma", SpeakingParse.titles("[\"a\",\"b\",]") == listOf("a", "b"))
    check("cues", SpeakingParse.cues("""[{"cue":"ชอบกินอะไร","meaning":"What do you like to eat?"},{"cue":"ที่ไหน"},{"meaning":"x"},"พูดเรื่อง"]""") ==
        listOf("ชอบกินอะไร" to "What do you like to eat?", "ที่ไหน" to "", "พูดเรื่อง" to ""))
    check("cues garbage", SpeakingParse.cues("sorry").isEmpty())
    check("topic prompts", TopicPrompt.suggest("Thai", "B1").contains("Thai") && TopicPrompt.cues("My home", "Thai", "English", "").let { it.contains("My home") && !it.contains("level") })

    // prompts
    val tp = TranscribePrompt.build("Thai", "English")
    check("transcribe prompt keeps mistakes", tp.contains("Do not correct") && tp.contains("Thai") && tp.contains("\"substituted\""))
    check("transcribe prompt allows only practised, Thai, English", tp.contains("Thai and English") && tp.contains("never write Vietnamese") && !tp.contains("usually"))
    check("transcribe prompt english practice allows Thai swaps", TranscribePrompt.build("English", "Thai").contains("English and Thai") && TranscribePrompt.allowed("English") == listOf("English", "Thai"))
    check("transcribe prompt japanese allowed", TranscribePrompt.allowed("Japanese") == listOf("Japanese", "Thai", "English"))
    check("transcribe prompt bans romanizing and stray digits", tp.contains("Never romanize") && tp.contains("Write a digit only when"))
    check("transcribe prompt hints", TranscribePrompt.build("Thai", "English", "My home", listOf("บ้าน", "ห้อง")).let { it.contains("My home") && it.contains("บ้าน, ห้อง") && it.contains("Never write a word that was not spoken") })
    check("transcribe prompt no hints no topic sentence", !tp.contains("The topic is"))
    check("transcribe prompt strict retry", TranscribePrompt.build("Thai", "English", strict = true).startsWith("A previous attempt was wrong") && !tp.startsWith("A previous"))
    // transcript check
    check("check clean thai", TranscriptCheck.problems("ผมไป market เมื่อวาน", listOf("market"), "Thai").isEmpty())
    check("check vietnamese letters", TranscriptCheck.problems("tôi không biết ơn", emptyList(), "Thai").isNotEmpty())
    check("check romanized thai", TranscriptCheck.problems("mak mak", emptyList(), "Thai").isNotEmpty())
    check("check unflagged latin ru", TranscriptCheck.problems("5 ru", emptyList(), "Thai").isNotEmpty())
    check("check flagged multi word swap", TranscriptCheck.problems("ผมชอบ ice cream", listOf("ice cream"), "Thai").isEmpty())
    check("check english practice thai swap ok", TranscriptCheck.problems("I like กิน rice", listOf("กิน"), "English").isEmpty())
    check("check english practice vietnamese letters", TranscriptCheck.problems("I like phở và bún ơi", emptyList(), "English").isNotEmpty())
    check("check english practice romanized no stray rule", TranscriptCheck.problems("I went to the market", emptyList(), "English").isEmpty())
    check("check japanese clean", TranscriptCheck.problems("昨日 market に行きました", listOf("market"), "Japanese").isEmpty())
    check("check japanese thai swap ok", TranscriptCheck.problems("私はกินました", listOf("กิน"), "Japanese").isEmpty())
    check("check japanese vietnamese", TranscriptCheck.problems("私は không", emptyList(), "Japanese").isNotEmpty())
    check("check vietnamese practice allows its letters", TranscriptCheck.problems("tôi không biết", emptyList(), "Vietnamese").isEmpty())
    check("check cyrillic rejected", TranscriptCheck.problems("привет", emptyList(), "Thai").isNotEmpty())
    check("check silence", TranscriptCheck.problems("", emptyList(), "Thai").isEmpty())
    val ip = ImprovePrompt.build("Thai", "English", "B1", "ผมไป market", listOf("market"))
    check("improve prompt has parts", ip.contains("ผมไป market") && ip.contains("\"market\"") && ip.contains("B1") && ip.contains("English"))
    check("improve prompt no level and no marks", ImprovePrompt.build("Thai", "English", "", "x", emptyList()).let { it.contains("none") && !it.contains("level is") })

    // measures (step 8)
    check("distinct words english", SpeakingMeasures.distinctWords("I went to the market, and the market was big.", "en") == 8)
    check("distinct words ignores case and punctuation", SpeakingMeasures.distinctWords("Hello, hello! HELLO.", "en") == 1)
    check("distinct words blank", SpeakingMeasures.distinctWords("  ", "en") == 0)
    check("distinct words thai splits more than one", SpeakingMeasures.distinctWords("ผมไปตลาดเมื่อวาน", "th") > 1)
    val m1 = SpeakingMeasures.of(30, "I go market yesterday", """["market"]""", """[{"word":"a","meaning":"b"},{"word":"c","meaning":"d","cardId":"x"}]""", "en")
    check("measures of", m1 == Measures(30, 4, 1, 2))
    check("measures of null json", SpeakingMeasures.of(10, "hi", null, null, "en") == Measures(10, 1, 0, 0))
    val m2 = SpeakingMeasures.of(55, "I went to the market yesterday and bought fruit", "[]", """[{"word":"a","meaning":"b"}]""", "en")
    check("measures change", SpeakingMeasures.change(m1, m2) == Measures(25, m2.distinctWords - 4, -1, -1))
    check("signed", SpeakingMeasures.signed(3) == "+3" && SpeakingMeasures.signed(-2) == "-2" && SpeakingMeasures.signed(0) == "0")

    println(if (fails == 0) "ALL OK" else "$fails FAILED")
    if (fails > 0) System.exit(1)
}
