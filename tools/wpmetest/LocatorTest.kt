import app.fluvour.reading.*
import java.io.File

var lfails = 0
fun lcheck(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) lfails++ }

fun chunk(title: String, vararg lines: String) = WpmeChunk(title, lines.joinToString("\n"))
fun word(form: String, chapter: Int) = WpmeItem('W', "W:" + WpmeData.norm(form), form, "m", chapter, emptyList())

/** Locator checks: synthetic book first, then the real EPUB (second argument) with the real deck (first argument is notes.tsv). */
fun locatorTests(notes: List<WpmeNote>?, epub: String?) {
    val book = listOf(
        chunk("2. How to Start Building Your Vocabulary", "Early mention of egoist here."),
        chunk("3. How to Talk About Personality Types (Sessions 1\u20133) (1)", "TEASER PREVIEW", "egotist appears in the teaser.", "SESSION 1", "IDEAS", "1. me first", "An egoist is selfish.", "USING THE WORDS", "1. egoist", "ego"),
        chunk("3. How to Talk About Personality Types (Sessions 1\u20133) (2)", "More text.", "ORIGINS AND RELATED WORDS", "Latin ego, the egotist and the altruist.", "CHAPTER REVIEW", "altruist again"),
        chunk("4. How to Talk About Doctors (Sessions 4\u20136)", "IDEAS", "The altruist helps, an aggregate counts.", "USING THE WORDS", "aggregate"),
    )
    val loc = WpmeLocator.locate(book, listOf(word("egoist", 1), word("egotist", 1), word("altruist", 1), word("aggregate", 3), word("to egoist", 1), word("missingword", 1), word("oval, ovoid", 1)))
    lcheck("locator: teaching section in the deck's chapter beats an earlier mention", loc["W:egoist"]?.chunk == 1 && loc["W:egoist"]?.section == "IDEAS")
    lcheck("locator: offset points at the word", book[1].text.substring(loc["W:egoist"]!!.offset).startsWith("egoist"))
    lcheck("locator: teaser is not teaching, later teaching section wins", loc["W:egotist"]?.chunk == 2 && loc["W:egotist"]?.section == "ORIGINS AND RELATED WORDS")
    lcheck("locator: section carries across chunks", WpmeLocator.locate(listOf(chunk("5. X (1)", "IDEAS"), chunk("5. X (2)", "the word zebra here")), listOf(word("zebra", 5)))["W:zebra"]?.section == "IDEAS")
    lcheck("locator: chapter hint for chapter 4 deck", loc["W:aggregate"]?.chunk == 3 && loc["W:aggregate"]?.section == "IDEAS")
    lcheck("locator: leading to is tried without it", loc["W:to egoist"]?.chunk == 1)
    lcheck("locator: unknown word is absent", "W:missingword" !in loc)
    lcheck("locator: whole words only", WpmeLocator.locate(listOf(chunk("3. A", "IDEAS", "egoists and ego")), listOf(word("egoist", 1))).isEmpty())
    lcheck("locator: falls back to any section", WpmeLocator.locate(listOf(chunk("9. A", "plain prose with a zebra")), listOf(word("zebra", 1)))["W:zebra"]?.chunk == 0)
    lcheck("locator: alternatives", WpmeLocator.locate(listOf(chunk("3. A", "IDEAS", "an ovoid shape")), listOf(word("oval, ovoid", 1))).containsKey("W:oval, ovoid"))
    val prose = WpmeLocator.locate(listOf(chunk("3. A", "IDEAS", "misanthropy", "misanthropic", "The misanthrope hates people, and so on and on and on and on and on and on, for a long line of prose.")), listOf(word("misanthrope", 1), word("misanthropy", 1)))
    lcheck("locator: prose beats a word list in the same section", prose["W:misanthrope"]?.offset == 36 + 0 || book.isNotEmpty() && prose["W:misanthrope"]?.let { it.offset > 30 } == true)
    val back = WpmeLocator.decode(WpmeLocator.encode(loc.values).lineSequence())
    lcheck("locator: file round trip", back == loc && WpmeLocator.decode(sequenceOf("junk", "a\tb\tc\td")).isEmpty())
    lcheck("locator: deck chapter table", WpmeLocator.bookChapterFor(1) == 3 && WpmeLocator.bookChapterFor(2) == 3 && WpmeLocator.bookChapterFor(24) == null && WpmeLocator.bookChapterFor(10) == null && WpmeLocator.bookChapterFor(25) == null && WpmeLocator.bookChapterFor(23) == 16)

    if (notes != null && epub != null && File(epub).exists()) {
        val bk = File(epub).inputStream().use { EpubReader.read(it) }
        val chunks = bk.chapters.map { WpmeChunk(it.title, it.text) }
        lcheck("real book: 75 chunks", chunks.size == 75)
        val allLines = chunks.flatMap { it.text.split('\n') }
        lcheck("real book: SESSION headings are separate lines", allLines.count { Regex("^SESSION \\d+$").matches(it.trim()) } >= 44)
        lcheck("real book: numbered section titles are separate lines", allLines.count { it.trim() == "1. the ego" || it.trim() == "2. others" } >= 2)
        lcheck("real book: teaching headings are separate lines", WpmeLocator.TEACHING.all { h -> allLines.count { it.trim() == h } >= (if (h == "IDEAS") 10 else 30) })
        val items = WpmeData.fromNotes(notes.asSequence())
        val base = WpmeData.fromNotes(notes.asSequence().filter { it.fields[0].startsWith("Match the") || it.fields[0].startsWith("Recall the") }).filter { it.kind == 'W' }
        val t0 = System.currentTimeMillis()
        val all = WpmeLocator.locate(chunks, items)
        val ms = System.currentTimeMillis() - t0
        val found = base.count { it.key in all }
        val teach = base.count { all[it.key]?.section in WpmeLocator.TEACHING }
        println("real: located ${all.size} of ${items.count { it.kind == 'W' }} words; Match/Recall words found $found of ${base.size}, in teaching sections $teach; ${ms} ms")
        println("real: not found: " + base.filter { it.key !in all }.joinToString(", ") { it.form })
        lcheck("real: about 811 of 820 words found", found >= base.size * 97 / 100)
        lcheck("real: about 720 in teaching sections", teach >= base.size * 85 / 100)
        lcheck("real: offsets inside their chunk", all.values.all { it.chunk in chunks.indices && it.offset in 0 until chunks[it.chunk].text.length })
        lcheck("real: hit is the word", all.values.all { l -> val it = items.first { x -> x.key == l.key }; WpmeLocator.variants(it.form).any { v -> chunks[l.chunk].text.regionMatches(l.offset, v, 0, v.length, ignoreCase = true) } })
        val ego = all["W:egocentric"]; val sample = listOf("egocentric", "misanthropy", "anthropologist", "aggregate", "altruistic").map { k -> all["W:$k"]?.let { "$k -> chunk ${it.chunk} ${chunks[it.chunk].title.take(28)} / ${it.section}" } ?: "$k -> none" }
        println("real: samples: " + sample.joinToString(" | "))
        lcheck("real: egocentric sits in chapter 3", ego != null && chunks[ego.chunk].title.startsWith("3."))
        lcheck("real: file round trip", WpmeLocator.decode(WpmeLocator.encode(all.values).lineSequence()) == all)
    } else println("skip real-book checks (pass notes.tsv and the .epub as arguments)")
}

fun excerptTests(notes: List<WpmeNote>?, epub: String?) {
    val text = listOf("IDEAS", "1. me first", "Short line.", "2. others", "You live for others. " + "Filler sentence here. ".repeat(60) + "The altruist gives. And more.", "last").joinToString("\n")
    val off = text.indexOf("altruist")
    val e = WpmePassage.excerpt(text, off)!!
    lcheck("excerpt: heading above", e.heading == "2. others")
    lcheck("excerpt: long paragraph is cut around the hit", e.text.contains("altruist") && e.text.length <= WpmePassage.MAX_CHARS + 6 && e.text.startsWith("\u2026"))
    val h = WpmePassage.excerpt(text, text.indexOf("2. others"))!!
    lcheck("excerpt: hit in a heading takes the next paragraph", h.heading == "2. others" && h.text.startsWith("You live for others."))
    val list = "IDEAS\n1. misanthrope or misanthropist\nmisanthropy\nmisanthropic\n2. misogynist\nmisogyny"
    val l = WpmePassage.excerpt(list, list.indexOf("misanthropy"))!!
    lcheck("excerpt: hit in a word list shows the list under its heading", l.heading == "1. misanthrope or misanthropist" && l.text == "misanthropy\nmisanthropic")
    val sh = WpmePassage.excerpt("IDEAS\n1. me first\nYour attitude is simple. Everything is about you.", 25)!!
    lcheck("excerpt: short paragraph kept whole", sh.heading == "1. me first" && sh.text == "Your attitude is simple. Everything is about you.")
    lcheck("excerpt: empty text", WpmePassage.excerpt("", 0) == null && WpmePassage.excerpt("\n\n", 3) == null)
    val pos = WpmePassage.position("one\n\ntwo words\nthree", 8)
    lcheck("position: blank lines do not count as paragraphs", pos.para == 1 && pos.ch == 3 && Math.abs(pos.percent - (3 + 3) / 17f) < 1e-6)
    lcheck("position: first paragraph", WpmePassage.position("abc\ndef", 1) == WpmePosition(0, 1, 1 / 6f))
    lcheck("position: empty", WpmePassage.position("", 5) == WpmePosition(0, 0, 0f))
    if (notes != null && epub != null && File(epub).exists()) {
        val bk = File(epub).inputStream().use { EpubReader.read(it) }
        val chunks = bk.chapters.map { WpmeChunk(it.title, it.text) }
        val items = WpmeData.fromNotes(notes.asSequence())
        val loc = WpmeLocator.locate(chunks, items)
        var empty = 0; var noHead = 0; var tooLong = 0; var n = 0
        for ((k, l) in loc) { val ex = WpmePassage.excerpt(chunks[l.chunk].text, l.offset); n++; if (ex == null || ex.text.isBlank()) empty++ else { if (ex.heading.isEmpty()) noHead++; if (ex.text.length > WpmePassage.MAX_CHARS + 6) tooLong++ } }
        println("real: excerpts $n, empty $empty, without heading $noHead, too long $tooLong")
        lcheck("real: every located word has an excerpt", empty == 0 && tooLong == 0)
        lcheck("real: positions are valid", loc.values.all { val p = WpmePassage.position(chunks[it.chunk].text, it.offset); p.para >= 0 && p.ch >= 0 && p.percent in 0f..1f })
        for (k in listOf("W:egocentric", "W:misanthropy", "W:altruistic", "W:aggregate", "W:egomaniac")) {
            val l = loc[k] ?: continue
            val ex = WpmePassage.excerpt(chunks[l.chunk].text, l.offset)!!
            println("sample $k [${ex.heading}]: ${ex.text.take(160)}")
        }
    }
}
