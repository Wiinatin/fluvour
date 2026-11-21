package app.fluvour.ai

import app.fluvour.reading.MiniJson
import app.fluvour.reading.WpmeData
import app.fluvour.reading.WpmeItem
import app.fluvour.reading.WpmeLink
import app.fluvour.reading.WpmeLinks

/** One word to link: the deck item and the passage of the user's own book about it (heading and text, cut to length). */
data class WpmeLinkTask(val item: WpmeItem, val passage: String)

/** One entry of the AI's reply, before checking. */
data class WpmeRawLink(val parts: List<String>, val gloss: String)

/** What the checker decided about one word. */
sealed class WpmeVerdict {
    /** A link whose parts all check out. */
    class Linked(val link: WpmeLink) : WpmeVerdict()
    /** The AI said the passage does not show how the word is built. Accepted: the word stays plain and is not asked again. */
    object Plain : WpmeVerdict()
    /** The reply is wrong or missing. [reason] is short plain text, also used when asking again. */
    class Rejected(val reason: String) : WpmeVerdict()
}

/**
 * Pure JVM, so it is tested on a plain JVM. Asks the AI to split deck words into parts from the book's own passage only, reads the
 * reply, and checks every link against the word itself, so a link is never kept just because the AI said it. The checker only
 * proves the parts fit the word and the lists; it cannot prove the meaning is right, so the user spot-checks.
 */
object WpmeLinksPrompt {
    /** Words per AI call. Small, so the passages fit the small free limits of the fallback providers. */
    const val BATCH = 10
    const val PASSAGE_MAX = 500
    private const val MAX_PARTS = 6
    private const val MAX_TAIL = 2

    /** Common English prefixes and suffixes (letters only) that the deck has no card for. A link may use them; they never block a word. */
    val STANDARD_PREFIXES = listOf(
        "a", "an", "ab", "abs", "ad", "ac", "af", "ag", "al", "ap", "ar", "as", "at", "ante", "anti", "auto", "be", "bi", "circum",
        "co", "col", "com", "con", "cor", "contra", "counter", "de", "di", "dif", "dis", "e", "ef", "em", "en", "ex", "extra",
        "for", "fore", "hyper", "hypo", "il", "im", "in", "inter", "intra", "ir", "mal", "mis", "mono", "non", "ob", "oc", "of",
        "op", "out", "over", "per", "post", "pre", "pro", "re", "retro", "se", "semi", "sub", "suc", "suf", "sup", "super", "sur",
        "sus", "trans", "tri", "ultra", "un", "under", "up",
    )
    val STANDARD_SUFFIXES = listOf(
        "able", "ible", "age", "al", "ial", "ical", "ic", "ics", "ish", "ism", "ist", "ite", "ity", "ty", "ive", "ative", "itive",
        "ize", "ise", "ify", "fy", "less", "ly", "ment", "ness", "ous", "ious", "eous", "ose", "or", "er", "ar", "ary", "ory", "ant",
        "ent", "ance", "ence", "ancy", "ency", "tion", "sion", "ion", "ation", "ition", "ure", "ate", "ful", "hood", "ship", "dom",
        "ee", "ess", "en", "y", "ry", "cy", "ling", "ward", "wise", "s", "es", "ed", "ing", "d", "e",
        "ology", "logy", "ologist", "ography", "graphy", "cracy", "crat", "ia",
    )
    private val STANDARD: Set<String> = (STANDARD_PREFIXES + STANDARD_SUFFIXES).toSet()

    // ---------------------------------------------------------------- prompt

    /** Words the checker can work with: letters only (a leading "to " and hyphens dropped). Null for phrases such as "a, b" or "all of one mind". */
    fun wordLetters(form: String): String? {
        var w = WpmeData.norm(form).removePrefix("to ")
        if (w.any { it == ' ' || it == ',' || it == '/' || it == ';' }) return null
        w = w.filter { it in 'a'..'z' }
        return w.takeIf { it.length >= 3 }
    }

    fun isLinkable(item: WpmeItem): Boolean = item.kind == WpmeData.WORD && wordLetters(item.form) != null

    /** The passage as sent: one line, cut to [PASSAGE_MAX]. */
    fun trimPassage(heading: String, text: String): String {
        val one = (heading.trim().let { if (it.isEmpty()) "" else "$it: " } + text).replace(Regex("\\s+"), " ").trim()
        return if (one.length <= PASSAGE_MAX) one else one.substring(0, PASSAGE_MAX).trimEnd() + "..."
    }

    /** [fixes]: word number to the reason an earlier answer was rejected, for the second try. */
    fun build(tasks: List<WpmeLinkTask>, elements: List<WpmeItem>, fixes: Map<Int, String> = emptyMap()): String {
        val forms = elements.filter { it.kind == WpmeData.ELEMENT }.joinToString(", ") { it.form }
        val std = (STANDARD_PREFIXES.map { "$it-" } + STANDARD_SUFFIXES.map { "-$it" }).joinToString(" ")
        val words = tasks.mapIndexed { i, t -> "${i + 1}. ${t.item.form}\nPassage: ${t.passage}" }.joinToString("\n\n")
        val fix = if (fixes.isEmpty()) "" else "\n\nYour earlier answer for these words was rejected. Answer them again and obey the rules:\n" +
            fixes.entries.joinToString("\n") { "${it.key}. ${it.value}" }
        return """You split English words into their parts, using only the passage that is given for each word.
Allowed parts: roots, prefixes and suffixes from this list: $forms
Also allowed: these common prefixes and suffixes: $std

Words:

$words$fix

Reply with a JSON array only, nothing else, one entry per word: [{"n": 1, "parts": ["mis-", "anthrop", "-e"], "gloss": "hate + mankind"}]
Rules:
- Use only the passage. If it does not show how the word is built, give "parts": [].
- Parts are in word order and spelled as they appear in the word. A root may lose its ending (anthropos becomes anthrop). Write prefixes with a hyphen after (mis-), suffixes with a hyphen before (-ology), roots with no hyphen.
- Every part must be letters of the word, and the parts must make up the whole word (a joining vowel such as o between two parts may be left out).
- Every part must come from the allowed lists.
- "gloss": the meaning of each part, joined with " + ", at most 8 words. Plain words, no explanations."""
    }

    // ---------------------------------------------------------------- reading the reply

    /** Reads the reply. Word number to entry. Never throws; text that is not a JSON array gives an empty map. */
    fun parse(text: String): Map<Int, WpmeRawLink> {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('['); val end = cleaned.lastIndexOf(']')
        if (start < 0) return emptyMap()
        val whole = if (end > start) try { MiniJson.parse(cleaned.substring(start, end + 1)) as? List<*> } catch (e: Exception) { null } else null
        // A reply cut off mid-way (token limit) or with stray text: keep every complete {...} entry. Missing words are asked again by the caller.
        val list = whole ?: Regex("\\{[^{}]*\\}").findAll(cleaned.substring(start)).mapNotNull { m ->
            try { MiniJson.parse(m.value) } catch (e: Exception) { null }
        }.toList().ifEmpty { return emptyMap() }
        val out = LinkedHashMap<Int, WpmeRawLink>()
        for (item in list) {
            val m = item as? Map<*, *> ?: continue
            val n = (m["n"] as? Number)?.toInt() ?: (m["n"] as? String)?.trim()?.toIntOrNull() ?: continue
            val parts = (m["parts"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() } } ?: continue
            val gloss = WpmeLinks.cleanGloss((m["gloss"] as? String).orEmpty())
            if (n !in out) out[n] = WpmeRawLink(parts, gloss)
        }
        return out
    }

    // ---------------------------------------------------------------- the checker

    private fun variants(core: String): List<String> {
        val v = LinkedHashSet<String>()
        v.add(core)
        if (core.length > 3 && core.endsWith("e")) v.add(core.dropLast(1))
        if (core.length > 2 && core.endsWith("y")) v.add(core.dropLast(1) + "i")
        return v.toList()
    }

    /** True when [core] is a stem of a deck element (or a one-or-two-letter variant of one). */
    fun isElementCore(core: String, stems: List<String>): Boolean =
        core.length >= 3 && stems.any { it == core || (it.startsWith(core) && it.length - core.length <= 2) || (core.startsWith(it) && core.length - it.length <= 2) }

    /** The smallest total of left-out letters for placing the parts in order inside the word, or null when they cannot be placed. */
    private fun place(word: String, parts: List<List<String>>): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        fun go(i: Int, pos: Int, gaps: Int, left: String) {
            if (i == parts.size) {
                val tail = word.length - pos
                if (tail <= MAX_TAIL) {
                    val total = gaps + tail
                    if (best == null || total < best!!.first) best = total to (left + word.substring(pos))
                }
                return
            }
            for (v in parts[i]) {
                var from = pos
                while (true) {
                    val at = word.indexOf(v, from)
                    if (at < 0) break
                    val gap = at - pos
                    val ok = if (i == 0) gap == 0 else gap == 0 || (gap == 1 && word[pos] in "aeiouy")
                    if (ok) go(i + 1, at + v.length, gaps + gap, left + word.substring(pos, at))
                    from = at + 1
                    if (gap > 1) break
                }
            }
        }
        go(0, 0, 0, "")
        return best
    }

    /**
     * Checks one reply entry against the word. [raw] null means the reply had no entry for the word. A link is kept only when:
     * every part is in the word, in order, with nothing left out except a joining vowel between parts and up to two letters at the
     * end; every part is a stem of a deck element or a common prefix or suffix; and at least one part is a deck element (the
     * unlock rule needs one). Otherwise [WpmeVerdict.Rejected] says why.
     */
    fun check(task: WpmeLinkTask, raw: WpmeRawLink?, elements: List<WpmeItem>): WpmeVerdict {
        val word = wordLetters(task.item.form) ?: return WpmeVerdict.Plain
        if (raw == null) return WpmeVerdict.Rejected("no answer for this word")
        if (raw.parts.isEmpty()) return WpmeVerdict.Plain
        if (raw.parts.size > MAX_PARTS) return WpmeVerdict.Rejected("too many parts (at most $MAX_PARTS)")
        val cores = raw.parts.map { WpmeLinks.core(it) }
        if (cores.any { it.isEmpty() }) return WpmeVerdict.Rejected("a part has no letters")
        val stems = elements.filter { it.kind == WpmeData.ELEMENT }.flatMap { WpmeData.stems(it.form) }.distinct()
        var anyElement = false
        for ((i, c) in cores.withIndex()) {
            val isEl = isElementCore(c, stems)
            if (isEl) anyElement = true
            if (!isEl && c !in STANDARD) return WpmeVerdict.Rejected("part \"${raw.parts[i]}\" is not in the allowed lists")
        }
        val placed = place(word, cores.map { variants(it) })
        if (placed == null) {
            val missing = cores.indexOfFirst { c -> variants(c).none { word.contains(it) } }
            return WpmeVerdict.Rejected(if (missing >= 0) "part \"${raw.parts[missing]}\" is not in the word" else "the parts do not fit the word in this order, with nothing left out")
        }
        if (!anyElement) return WpmeVerdict.Rejected("no part comes from the list of roots and affixes")
        val parts = raw.parts.map { p -> p.lowercase().filter { it in 'a'..'z' || it == '-' } }
        return WpmeVerdict.Linked(WpmeLink(task.item.key, parts, raw.gloss))
    }

    /** Checks a whole reply: word number (1-based, as in the prompt) to verdict. */
    fun checkAll(tasks: List<WpmeLinkTask>, reply: Map<Int, WpmeRawLink>, elements: List<WpmeItem>): Map<Int, WpmeVerdict> =
        tasks.indices.associate { i -> (i + 1) to check(tasks[i], reply[i + 1], elements) }
}
