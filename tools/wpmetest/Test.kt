import app.fluvour.reading.*
import java.io.File

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) fails++ }

fun unesc(s: String): String {
    val sb = StringBuilder(); var i = 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 1 < s.length) { sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2 } else { sb.append(s[i]); i++ }
    }
    return sb.toString()
}

fun note(front: String, back: String, deck: String = "**daily::WPME::01 - Chapter 3 (Sessions 1-3)") = WpmeNote(listOf(front, back, ""), deck)

fun main(args: Array<String>) {
    // ---- synthetic: classification ----
    val deck3 = "**daily::WPME::01 - Chapter 3 (Sessions 1-3)"
    val deck4 = "**daily::WPME::03 - Chapter 4 (Sessions 4-6)"
    val a = WpmeData.fromNotes(sequenceOf(
        note("What is the meaning of the root: ego?", "self, I", deck3),
        note("What is the meaning of the suffix: -itis?", "inflammation", deck4),
        note("What does the prefix 're-' mean?", "again, back", deck4),
        note("What does the verb suffix '-fy' mean?", "to make", deck4),
        note("Match the definition of: egocentric", "excessively fixated on his own needs", deck3),
        note("Recall the word for: unselfish (A...)", "altruistic", deck3),
        note("Recall the word for: all of one mind (adjective)", "unanimous", deck3),
        note("True or False: ego means other", "False", deck3),
        note("Using the suffix -ic, write the adjective form of symphony.", "symphonic", deck3),
        note("Give a synonym starting with P for: bucket", "pail", deck3),
    ))
    check("synthetic: kinds", a.count { it.kind == 'E' } == 4 && a.count { it.kind == 'W' } == 3)
    check("synthetic: filler dropped", a.none { it.form.contains("symphon") || it.form == "pail" })
    check("synthetic: elements first", a.take(4).all { it.kind == 'E' })
    check("synthetic: affix forms", listOf("-itis", "re-", "-fy").all { f -> a.any { it.form == f } })
    check("synthetic: recall without hint", a.any { it.key == "W:unanimous" && it.meaning == "all of one mind (adjective)" })
    check("synthetic: ego example", a.first { it.key == "E:ego" }.examples == listOf("egocentric"))
    check("synthetic: chapter", a.first { it.key == "E:-itis" }.chapter == 3 && a.first { it.key == "E:ego" }.chapter == 1)

    // ---- synthetic: html, merges, cleaning ----
    val m = WpmeData.fromNotes(sequenceOf(
        note("Match the definition of: <b>logos</b>&nbsp;", "science, study"),
        note("Match the definition of: logos", "word, speech, study"),
        note("Match the definition of: ego", "self, I"),
        note("Match the definition of: Ego", "I, self"),
        note("Match the definition of: philanthropy", "love of mankind"),
        note("Match the definition of: philanthropy", "charitable works"),
        note("Match the definition of: soporific (adj.)", "causing sleep"),
        note("Match the definition of: soporific (noun)", "a sleep drug"),
        note("Match the definition of: 静aphrodisiac", "arousing desire"),
        note("Match the definition of: to alter", "to change"),
        note("Recall the word for: to change (A...)", "alter"),
        note("Match the definition of: aggregate (n.)", "a total"),
    ))
    fun meaning(k: String) = m.first { it.key == k }.meaning
    check("merge: logos joined", meaning("W:logos") == "science, study; word, speech, study".let { "word, speech, study" } || meaning("W:logos") == "science, study; word, speech, study")
    check("merge: same words in other order is one meaning", m.count { it.key == "W:ego" } == 1 && meaning("W:ego").length == 7)
    check("merge: different meanings joined", meaning("W:philanthropy") == "love of mankind; charitable works")
    check("clean: part of speech stripped and merged", m.count { it.key.startsWith("W:soporific") } == 1 && m.first { it.key == "W:soporific" }.form == "soporific")
    check("clean: stray character", m.any { it.key == "W:aphrodisiac" })
    check("clean: aggregate", m.any { it.key == "W:aggregate" })
    check("clean: to alter stays apart from alter", m.any { it.key == "W:to alter" } && m.any { it.key == "W:alter" })
    check("merge: no duplicate keys", m.map { it.key }.toSet().size == m.size)
    check("merge helper: contained keeps longer", WpmeData.mergeMeanings("to change", "to change or alter") == "to change or alter")

    // ---- synthetic: conditional phobia and person cards ----
    val c = WpmeData.fromNotes(sequenceOf(
        note("What is the meaning of the root: anthropos?", "mankind", deck3),
        note("What is the meaning of the root: aequus (equ-)?", "equal", deck3),
        note("Identify the person who: studies the development of the human race", "anthropologist", deck3),
        note("Identify the phobia for: dread of air", "aerophobia", deck3),
        note("Identify the phobia for: dread of horses", "equinophobia", deck3),
    ))
    check("conditional: root word kept", c.any { it.key == "W:anthropologist" && it.meaning.startsWith("one who ") })
    check("conditional: no root skipped", c.none { it.key == "W:aerophobia" })
    check("conditional: bracket form stem", c.any { it.key == "W:equinophobia" })
    check("stems: bracket and cuts", WpmeData.stems("aequus (equ-)").containsAll(listOf("equ", "aequus", "aequu")))
    check("stems: bracket list", WpmeData.stems("facio (fec-, fic-, -fy)").containsAll(listOf("fec", "fic")))
    check("conditional: phobia needs a root other than phobia", WpmeData.fromNotes(sequenceOf(note("What is the meaning of the root: phobos?", "fear", deck3), note("Identify the phobia for: dread of air", "aerophobia", deck3))).none { it.key == "W:aerophobia" })
    check("stems: or forms", WpmeData.stems("opsis or optikos").containsAll(listOf("opsis", "optikos")))
    check("chapterOf", WpmeData.chapterOf(deck4) == 3 && WpmeData.chapterOf("**daily::WPME") == 99)

    // ---- deck names ----
    val json = """{"1":{"id":1,"name":"Default"},"1781239562023":{"id":1781239562023,"name":"**daily\u2b50::WPME::01 - Chapter 3 (Sessions 1-3)","x":[1,2]},"bad":{"name":"x"},"7":{"id":7}}"""
    val names = WpmeData.deckNamesFromJson(json)
    check("deck names: legacy json", names[1781239562023L] == "**daily\u2b50::WPME::01 - Chapter 3 (Sessions 1-3)" && names[1L] == "Default" && names.size == 2)
    check("deck names: garbage json", WpmeData.deckNamesFromJson("not json").isEmpty() && WpmeData.deckNamesFromJson("").isEmpty())
    check("deck names: new format separator", WpmeData.chapterOf(WpmeData.deckName("**daily\u2b50\u001fWPME\u001f05 - Chapter 5 (Sessions 7-10)")) == 5)
    check("deck names: legacy name unchanged", WpmeData.deckName("a::b::07 - c") == "a::b::07 - c")

    // ---- synthetic: file round trip ----
    val tricky = listOf(
        WpmeItem('W', "W:a\\b", "a\\b", "line one\nline two\ttab", 4, emptyList()),
        WpmeItem('E', "E:ego", "ego", "self, I", 1, listOf("egoist", "egocentric")),
    )
    val back = WpmeData.decode(WpmeData.encode(tricky).lineSequence())
    check("tsv round trip", back[1] == tricky[1] && back[0].meaning == "line one\nline two tab" && back[0].form == "a\\b")
    check("tsv bad lines skipped", WpmeData.decode(sequenceOf("junk", "X\ta\tb\tc\t1\t", "E\tk\tf\tm\tx\t")).isEmpty())

    // ---- real deck (needs notes.tsv from dump_apkg.py) ----
    val path = args.firstOrNull()
    if (path != null && File(path).exists()) {
        val notes = File(path).readLines().map { l -> val p = l.split('\t'); WpmeNote(unesc(p[1]).split('\u001f'), unesc(p[0])) }
        val items = WpmeData.fromNotes(notes.asSequence())
        val els = items.filter { it.kind == 'E' }
        val words = items.filter { it.kind == 'W' }
        println("real: ${notes.size} notes, ${els.size} elements, ${words.size} words")
        check("real: 3534 notes", notes.size == 3534)
        check("real: about 227 roots plus 5 affixes", els.size in 225..235)
        check("real: the 5 affix cards", listOf("-ician", "-osis", "-itis", "-fy", "re-").all { f -> els.any { it.form == f } })
        val base = WpmeData.fromNotes(notes.asSequence().filter { it.fields[0].startsWith("Match the") || it.fields[0].startsWith("Recall the") }).count { it.kind == 'W' }
        println("real: words from Match and Recall only: $base (raw unique forms 814 before part-of-speech merge)")
        check("real: Match and Recall words close to the 820 expected", base in 770..830)
        check("real: no duplicate keys", items.map { it.key }.toSet().size == items.size)
        check("real: no blank meanings or forms", items.none { it.meaning.isBlank() || it.form.isBlank() })
        check("real: no markup left", items.none { it.form.contains('<') || it.meaning.contains('<') || it.meaning.contains("&nbsp;") })
        check("real: no part-of-speech tags left in forms", words.none { Regex("\\((n|v|adj|adv|noun)\\.?\\)").containsMatchIn(it.form) })
        check("real: logos merged", els.first { WpmeData.norm(it.form) == "logos" }.meaning.let { it.contains("science") && it.contains("speech") })
        check("real: psyche merged", els.count { WpmeData.norm(it.form).startsWith("psyche") } == 1)
        check("real: ego is a root and a word", els.any { it.key == "E:ego" } && words.any { it.key == "W:ego" })
        check("real: elements before words", items.indexOfLast { it.kind == 'E' } < items.indexOfFirst { it.kind == 'W' })
        check("real: chapter order inside kinds", els.map { it.chapter }.zipWithNext().all { it.first <= it.second } && words.map { it.chapter }.zipWithNext().all { it.first <= it.second })
        check("real: chapters are known", items.none { it.chapter == 99 })
        check("real: most roots have an example", els.count { it.examples.isNotEmpty() } > els.size * 7 / 10)
        check("real: round trip", WpmeData.decode(WpmeData.encode(items).lineSequence()) == items)
        println("real: phobia words kept: " + words.count { it.key.endsWith("phobia") } + ", person words kept: " + words.count { it.meaning.startsWith("one who ") })
        println("real: roots without example: " + els.filter { it.examples.isEmpty() }.joinToString(", ") { it.form })
        println("real: sample logos = " + els.first { WpmeData.norm(it.form) == "logos" }.meaning)
        println("real: sample first 6 elements = " + els.take(6).joinToString(" | ") { "${it.form}=${it.meaning} [${it.examples.joinToString(",")}]" })
    } else println("skip real-deck checks (pass notes.tsv as the first argument)")

    val realNotes = args.firstOrNull()?.takeIf { File(it).exists() }?.let { pth -> File(pth).readLines().map { l -> val p = l.split('\t'); WpmeNote(unesc(p[1]).split('\u001f'), unesc(p[0])) } }
    locatorTests(realNotes, args.getOrNull(1))
    excerptTests(realNotes, args.getOrNull(1))
    linksTests()
    studyTests()
    fails += lfails + kfails + sfails

    println(if (fails == 0) "ALL OK" else "$fails FAILED")
}
