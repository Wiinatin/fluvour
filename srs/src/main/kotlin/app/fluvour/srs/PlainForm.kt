package app.fluvour.srs

/**
 * Arabic written with vowel marks (Uthmani style) and without them is the same word. Matching and duplicate checks compare the
 * plain forms; cards always DISPLAY the vowelled form. Pure JVM, no Android.
 *
 * Removed: harakat and shadda/sukun (U+064B-U+065F), dagger alef (U+0670), Quranic annotation signs (U+06D6-U+06ED), tatweel (U+0640).
 * Alef variants are NOT merged, so two different words never collide.
 */
fun plainArabic(s: String): String {
    val out = StringBuilder(s.length)
    for (ch in s) {
        val c = ch.code
        val mark = c in 0x064B..0x065F || c == 0x0670 || c in 0x06D6..0x06ED || c == 0x0640
        if (!mark) out.append(ch)
    }
    return out.toString().trim()
}

/** The form used to decide that two cards are the same word: Arabic ignores vowel marks, every language ignores case and outer spaces. */
fun plainForm(lang: String, s: String): String = (if (lang == "ar") plainArabic(s) else s.trim()).lowercase()

fun sameWord(lang: String, a: String, b: String): Boolean = plainForm(lang, a) == plainForm(lang, b)

/**
 * Where `word` occurs in `text`, as [start, end) ranges in `text` itself. Case is ignored; for Arabic the vowel marks are ignored on both
 * sides, so a plain word lights up inside a fully vowelled sentence and the other way round. Marks that trail a match are included
 * in the range, so a letter is never highlighted without its marks. Empty list when `word` is blank.
 */
fun findForms(lang: String, text: String, word: String): List<IntRange> {
    val w = plainForm(lang, word)
    if (w.isEmpty()) return emptyList()
    val plainChars = StringBuilder(); val origin = ArrayList<Int>()   // plain char i came from text[origin[i]]
    for (i in text.indices) if (lang != "ar" || !isArabicMark(text[i])) { plainChars.append(text[i].lowercaseChar()); origin += i }
    val out = ArrayList<IntRange>(); var from = 0
    while (from <= plainChars.length - w.length) {
        val j = plainChars.indexOf(w, from)
        if (j < 0) break
        var end = origin[j + w.length - 1] + 1
        if (lang == "ar") while (end < text.length && isArabicMark(text[end])) end++
        out += origin[j] until end
        from = j + w.length
    }
    return out
}

private fun isArabicMark(ch: Char): Boolean { val c = ch.code; return c in 0x064B..0x065F || c == 0x0670 || c in 0x06D6..0x06ED || c == 0x0640 }
