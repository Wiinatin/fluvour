package app.fluvour.reading

/** One Anki note of the user's own Word Power deck, with the name of the sub-deck its card sits in (for the chapter order). */
data class WpmeNote(val fields: List<String>, val deck: String)

/**
 * One study item. kind is 'E' (root, prefix or suffix) or 'W' (word). key is unique across both kinds ("E:ego", "W:ego").
 * chapter is the number NN from the sub-deck name "NN - ...", 99 when the name has none. examples is only used by elements.
 */
data class WpmeItem(val kind: Char, val key: String, val form: String, val meaning: String, val chapter: Int, val examples: List<String>)

/**
 * Pure parts (no Android) of the Word Power deck: sorting the deck's card types, dropping the filler, merging duplicates and
 * the small file the result is kept in. Nothing of the deck ships with the app.
 */
object WpmeData {
    const val FILE = "wpme.tsv"
    const val ELEMENT = 'E'
    const val WORD = 'W'

    private const val EXAMPLES_PER_ELEMENT = 3
    private const val MIN_STEM = 3

    fun htmlToText(html: String): String {
        var t = html.replace(Regex("(?i)<br\\s*/?>|</div>|</p>|<div[^>]*>|<p[^>]*>"), "\n").replace(Regex("<[^>]*>"), "")
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        return t.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** "**daily::WPME::05 - Chapter 5 (Sessions 7-10)" gives 5. 99 when there is no number. */
    fun chapterOf(deck: String): Int = Regex("::(\\d+)\\s*-").find(deck)?.groupValues?.get(1)?.toIntOrNull() ?: 99

    /** New Anki exports separate sub-deck levels with U+001F; the legacy JSON uses "::". Both end up as "::". */
    fun deckName(raw: String): String = raw.replace('\u001f', ':').let { if (it.contains("::")) it else it.replace(":", "::") }

    /** Legacy `col.decks` JSON: {"<id>": {"name": "...", ...}, ...} to id and name. Bad entries are skipped, bad JSON gives an empty map. */
    fun deckNamesFromJson(json: String): Map<Long, String> {
        val root = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return emptyMap()
        val out = HashMap<Long, String>()
        for ((k, v) in root) {
            val id = (k as? String)?.toLongOrNull() ?: continue
            val name = (v as? Map<*, *>)?.get("name") as? String ?: continue
            out[id] = deckName(name)
        }
        return out
    }

    private class Parsed(val kind: Char, val form: String, val meaning: String, val needsRoot: Boolean, val skipStem: String = "")

    private val ROOT = Regex("^What is the meaning of the root:\\s*(.+?)\\s*\\??$")
    private val AFFIX = Regex("^What is the meaning of the (?:prefix|suffix):\\s*(.+?)\\s*\\??$")
    private val AFFIX_QUOTED = Regex("^What does the (?:\\w+ )?(?:prefix|suffix) ['\u2018\u2019\"](.+?)['\u2018\u2019\"] mean\\??$")
    private val MATCH = Regex("^Match the definition of:\\s*(.+)$")
    private val RECALL = Regex("^Recall the word for:\\s*(.+?)(?:\\s*\\([A-Za-z]\\.{2,3}\\))?$")
    private val PHOBIA = Regex("^Identify the phobia for:\\s*(.+)$")
    private val PERSON = Regex("^Identify the person who:\\s*(.+)$")
    private val POS_SUFFIX = Regex("\\s*\\((?:n|v|adj|adv|noun|verb|adjective|adverb|pl|prep)\\.?(?:,[^)]*)?\\)\\s*$", RegexOption.IGNORE_CASE)

    /** Word as the deck writes it, without a trailing part of speech such as "(adj.)" and without stray non-Latin letters. */
    fun cleanWord(raw: String): String {
        var w = raw.trim()
        w = buildString { for (c in w) if (!Character.isLetter(c) || Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN) append(c) }
        w = w.replace(POS_SUFFIX, "")
        return w.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', ';')
    }

    private fun classify(front: String, back: String): Parsed? {
        val f = front.replace(Regex("\\s+"), " ").trim()
        val b = back.replace(Regex("\\s+"), " ").trim()
        if (f.isEmpty() || b.isEmpty()) return null
        ROOT.find(f)?.let { return Parsed(ELEMENT, it.groupValues[1], b, false) }
        AFFIX.find(f)?.let { return Parsed(ELEMENT, it.groupValues[1], b, false) }
        AFFIX_QUOTED.find(f)?.let { return Parsed(ELEMENT, it.groupValues[1], b, false) }
        MATCH.find(f)?.let { return Parsed(WORD, cleanWord(it.groupValues[1]), b, false) }
        RECALL.find(f)?.let { return Parsed(WORD, cleanWord(b), it.groupValues[1].trim(), false) }
        PHOBIA.find(f)?.let { return Parsed(WORD, cleanWord(b), it.groupValues[1].trim(), true, "phob") }
        PERSON.find(f)?.let { return Parsed(WORD, cleanWord(b), "one who " + it.groupValues[1].trim(), true) }
        return null
    }

    fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun tokens(s: String) = norm(s).replace(Regex("[^\\p{L}\\p{N} ]"), "").split(' ').filter { it.isNotEmpty() }.toSet()

    /** Two meanings for one entry: the same text (ignoring case, punctuation and word order) stays one, one inside the other keeps the longer, otherwise they are joined with a semicolon. */
    fun mergeMeanings(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta == tb) return if (b.length > a.length) b else a
        if (tb.containsAll(ta)) return b
        if (ta.containsAll(tb)) return a
        return "$a; $b"
    }

    /** Search stems of an element form: "aequus (equ-)" gives equ, aequu, aequ. Long enough to be meaningful, short enough to match inside English words. */
    fun stems(form: String): List<String> {
        val out = LinkedHashSet<String>()
        val bracket = Regex("\\(([^)]*)\\)").findAll(form).flatMap { it.groupValues[1].split(',') }.toList()
        val main = form.replace(Regex("\\([^)]*\\)"), " ")
        val forms = (bracket + main.split(Regex("\\s+or\\s+|/|,"))).map { norm(it).trim('-', ' ', '?') }
        for (f in forms) {
            if (f.length < MIN_STEM || f.contains(' ')) continue
            out.add(f)
            for (cut in 1..2) if (f.length - cut >= 4) out.add(f.dropLast(cut))
        }
        return out.toList()
    }

    private fun isAffix(form: String) = form.startsWith("-") || form.endsWith("-")

    private class Acc(val kind: Char, val form: String, var meaning: String, var chapter: Int, val order: Int)

    /**
     * Notes of the deck, in the order the database gives them. Roots, prefixes and suffixes become element items, "Match the
     * definition of" and "Recall the word for" become word items, and phobia and "Identify the person who" cards become word items
     * only when the word contains a root of the element list. Every other card type is dropped. Duplicates merge by key.
     * Result: elements first, then words, each by chapter and then by first appearance.
     */
    fun fromNotes(notes: Sequence<WpmeNote>): List<WpmeItem> {
        val acc = LinkedHashMap<String, Acc>()
        val conditional = ArrayList<Pair<Parsed, Int>>()
        var order = 0
        fun add(p: Parsed, chapter: Int, ord: Int) {
            val key = p.kind + ":" + norm(p.form)
            val a = acc[key]
            if (a == null) acc[key] = Acc(p.kind, p.form, p.meaning, chapter, ord)
            else {
                a.meaning = mergeMeanings(a.meaning, p.meaning)
                if (chapter < a.chapter) a.chapter = chapter
            }
        }
        for (n in notes) {
            if (n.fields.size < 2) continue
            val p = classify(htmlToText(n.fields[0]), htmlToText(n.fields[1])) ?: continue
            if (p.form.isBlank()) continue
            val ch = chapterOf(n.deck)
            if (p.needsRoot) conditional.add(p to ch) else add(p, ch, order)
            order++
        }
        val rootStems = acc.values.filter { it.kind == ELEMENT && !isAffix(it.form) }.flatMap { stems(it.form) }.distinct()
        for ((p, ch) in conditional) {
            val w = norm(p.form)
            if (rootStems.any { w.contains(it) && !(p.skipStem.isNotEmpty() && it.startsWith(p.skipStem)) }) add(p, ch, order++)
        }
        val items = acc.map { (key, a) -> Triple(key, a, emptyList<String>()) }
        val words = items.filter { it.second.kind == WORD }.sortedWith(compareBy({ it.second.chapter }, { it.second.order }))
        val elements = items.filter { it.second.kind == ELEMENT }.sortedWith(compareBy({ it.second.chapter }, { it.second.order }))
        val out = ArrayList<WpmeItem>(items.size)
        for ((key, a, _) in elements) {
            val st = stems(a.form)
            // Example words: same chapter first, then the rest in path order.
            val ex = words.asSequence().filter { w -> val t = norm(w.second.form); st.any { t.contains(it) } }
                .sortedBy { if (it.second.chapter == a.chapter) 0 else 1 }.take(EXAMPLES_PER_ELEMENT).map { it.second.form }.toList()
            out.add(WpmeItem(ELEMENT, key, a.form, a.meaning, a.chapter, ex))
        }
        for ((key, a, _) in words) out.add(WpmeItem(WORD, key, a.form, a.meaning, a.chapter, emptyList()))
        return out
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '\t' -> sb.append(' ')
            '\r' -> {}
            '\n' -> sb.append("\\n")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun unesc(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(if (s[i + 1] == 'n') '\n' else s[i + 1])
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** One line per item: kind, key, form, meaning, chapter, examples (joined with a vertical bar). */
    fun encode(list: List<WpmeItem>): String =
        list.joinToString("\n") { listOf(it.kind.toString(), esc(it.key), esc(it.form), esc(it.meaning), it.chapter.toString(), esc(it.examples.joinToString("|"))).joinToString("\t") }

    fun decode(lines: Sequence<String>): List<WpmeItem> {
        val out = ArrayList<WpmeItem>(1_200)
        for (line in lines) {
            val p = line.split('\t')
            if (p.size < 6) continue
            val kind = p[0].firstOrNull()?.takeIf { it == ELEMENT || it == WORD } ?: continue
            val chapter = p[4].toIntOrNull() ?: continue
            val ex = unesc(p[5]).split('|').filter { it.isNotEmpty() }
            out.add(WpmeItem(kind, unesc(p[1]), unesc(p[2]), unesc(p[3]), chapter, ex))
        }
        return out
    }
}
