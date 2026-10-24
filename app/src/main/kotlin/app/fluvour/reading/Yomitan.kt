package app.fluvour.reading

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Phase 2A step 9: Yomitan / Yomichan dictionary import. Pure JVM, no Android imports. */

/** A word entry of an imported dictionary. `glossary` is plain text, one meaning per line. */
data class TermRow(val term: String, val reading: String, val tags: String, val score: Int, val glossary: String, val sequence: Int)

/** Extra data about a word: `kind` is freq, pitch or ipa. `value` is what is shown ("1234", "0,3", "tabeɾɯ"); `num` is a sortable number for freq. */
data class MetaRow(val term: String, val kind: String, val reading: String, val value: String, val num: Double?)

data class DictIndex(val title: String, val revision: String, val author: String?, val attribution: String?, val format: Int)

data class YomitanImport(val index: DictIndex, val terms: Int, val meta: Int)

/** Receives rows in batches while the zip is read, so a large dictionary never sits in memory whole. */
interface YomitanSink {
    suspend fun terms(rows: List<TermRow>)
    suspend fun meta(rows: List<MetaRow>)
}

class YomitanException(message: String) : Exception(message)

object YomitanReader {
    private const val BATCH = 3000

    /**
     * Reads a Yomitan dictionary zip: `index.json`, `term_bank_N.json`, `term_meta_bank_N.json`. Tag banks, kanji banks and
     * media are skipped. Formats 1 to 3 are understood. Entries may sit in a folder inside the zip.
     */
    suspend fun read(input: InputStream, sink: YomitanSink, onProgress: (String) -> Unit = {}): YomitanImport {
        var index: DictIndex? = null
        var terms = 0; var meta = 0
        var format = 3
        val zip = ZipInputStream(input)
        while (true) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory) continue
            val name = e.name.substringAfterLast('/').lowercase()
            val isTerm = Regex("term_bank_\\d+\\.json").matches(name)
            val isMeta = Regex("term_meta_bank_\\d+\\.json").matches(name)
            if (name != "index.json" && !isTerm && !isMeta) continue
            val text = String(zip.readBytes(), Charsets.UTF_8)
            val json = try { MiniJson.parse(text) } catch (ex: Exception) { throw YomitanException("$name is not valid JSON.") }
            when {
                name == "index.json" -> { index = parseIndex(json); format = index.format }
                isTerm -> {
                    onProgress(name)
                    val rows = parseTerms(json, format)
                    for (chunk in rows.chunked(BATCH)) sink.terms(chunk)
                    terms += rows.size
                }
                else -> {
                    onProgress(name)
                    val rows = parseMeta(json)
                    for (chunk in rows.chunked(BATCH)) sink.meta(chunk)
                    meta += rows.size
                }
            }
        }
        val idx = index ?: throw YomitanException("This is not a Yomitan dictionary (no index.json). Choose the .zip file as it was downloaded.")
        if (terms == 0 && meta == 0) throw YomitanException("\"${idx.title}\" has no word entries. Kanji-only dictionaries are not supported.")
        return YomitanImport(idx, terms, meta)
    }

    // The term bank files are read before index.json only when the zip stores them first; format 1 is rare, so a missing
    // index at that point is treated as format 3 (the common case).

    internal fun parseIndex(json: Any?): DictIndex {
        val m = json as? Map<*, *> ?: throw YomitanException("index.json is not valid.")
        val title = (m["title"] as? String)?.trim().orEmpty()
        if (title.isEmpty()) throw YomitanException("index.json has no title.")
        val format = ((m["format"] ?: m["version"]) as? Double)?.toInt() ?: 3
        return DictIndex(title, (m["revision"] as? String).orEmpty(), (m["author"] as? String)?.ifBlank { null },
            (m["attribution"] as? String)?.ifBlank { null }, format)
    }

    internal fun parseTerms(json: Any?, format: Int): List<TermRow> {
        val list = json as? List<*> ?: throw YomitanException("A term bank is not a list.")
        val out = ArrayList<TermRow>(list.size)
        for (r in list) {
            val row = r as? List<*> ?: continue
            val term = (row.getOrNull(0) as? String)?.trim().orEmpty()
            if (term.isEmpty() || row.size < 6) continue
            val glossaryNodes: List<Any?> = if (format <= 1) row.drop(5) else (row[5] as? List<*> ?: listOf(row[5]))
            val glossary = glossaryText(glossaryNodes)
            if (glossary.isEmpty()) continue
            val tags = listOf(row.getOrNull(2), row.getOrNull(7)).mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }.joinToString(" ")
            out.add(TermRow(term, (row[1] as? String)?.trim().orEmpty(), tags, (row[4] as? Double)?.toInt() ?: 0, glossary,
                (row.getOrNull(6) as? Double)?.toInt() ?: 0))
        }
        return out
    }

    internal fun parseMeta(json: Any?): List<MetaRow> {
        val list = json as? List<*> ?: throw YomitanException("A term meta bank is not a list.")
        val out = ArrayList<MetaRow>(list.size)
        for (r in list) {
            val row = r as? List<*> ?: continue
            val term = (row.getOrNull(0) as? String)?.trim().orEmpty()
            val kind = row.getOrNull(1) as? String ?: continue
            if (term.isEmpty()) continue
            when (kind) {
                "freq" -> freq(row.getOrNull(2))?.let { (reading, display, num) -> out.add(MetaRow(term, "freq", reading, display, num)) }
                "pitch" -> {
                    val d = row.getOrNull(2) as? Map<*, *> ?: continue
                    val pos = (d["pitches"] as? List<*>)?.mapNotNull { p ->
                        when (val v = (p as? Map<*, *>)?.get("position")) { is Double -> v.toInt().toString(); is String -> v; else -> null }
                    }?.distinct().orEmpty()
                    if (pos.isNotEmpty()) out.add(MetaRow(term, "pitch", (d["reading"] as? String).orEmpty(), pos.joinToString(","), null))
                }
                "ipa" -> {
                    val d = row.getOrNull(2) as? Map<*, *> ?: continue
                    val t = (d["transcriptions"] as? List<*>)?.mapNotNull { ((it as? Map<*, *>)?.get("ipa") as? String) }?.distinct().orEmpty()
                    if (t.isNotEmpty()) out.add(MetaRow(term, "ipa", (d["reading"] as? String).orEmpty(), t.joinToString(", "), null))
                }
            }
        }
        return out
    }

    /** Frequency data comes as a number, a string, `{value, displayValue}`, or `{reading, frequency: <any of those>}`. Returns (reading, shown text, number). */
    private fun freq(d: Any?): Triple<String, String, Double?>? {
        var reading = ""
        var v: Any? = d
        if (d is Map<*, *> && d.containsKey("frequency")) { reading = (d["reading"] as? String).orEmpty(); v = d["frequency"] }
        return when (v) {
            is Double -> Triple(reading, v.toLong().toString(), v)
            is String -> Triple(reading, v, v.filter { it.isDigit() }.toDoubleOrNull())
            is Map<*, *> -> {
                val n = v["value"] as? Double
                val shown = (v["displayValue"] as? String)?.ifBlank { null } ?: n?.toLong()?.toString() ?: return null
                Triple(reading, shown, n ?: shown.filter { it.isDigit() }.toDoubleOrNull())
            }
            else -> null
        }
    }

    private val BLOCK = setOf("div", "p", "ul", "ol", "li", "tr", "table", "br", "h1", "h2", "h3", "h4", "details", "summary")
    private val SKIP = setOf("rt", "rp", "img", "style", "script")

    /** One meaning per line. Handles plain strings, `{type: text}`, `{type: image}` (skipped) and structured content. */
    internal fun glossaryText(nodes: List<Any?>): String {
        val lines = ArrayList<String>()
        for (n in nodes) {
            val sb = StringBuilder(); flatten(n, sb)
            sb.toString().split('\n').map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.forEach { lines.add(it) }
        }
        return lines.joinToString("\n")
    }

    private fun flatten(n: Any?, out: StringBuilder) {
        when (n) {
            is String -> out.append(n)
            is List<*> -> n.forEach { flatten(it, out) }
            is Map<*, *> -> {
                when (n["type"]) {
                    "text" -> { out.append(n["text"] as? String ?: ""); return }
                    "image" -> return
                    "structured-content" -> { flatten(n["content"], out); return }
                }
                val tag = n["tag"] as? String ?: run { flatten(n["content"], out); return }
                if (tag in SKIP) return
                val block = tag in BLOCK
                if (block) out.append('\n')
                if (tag == "li") out.append("• ")
                flatten(n["content"], out)
                if (block) out.append('\n')
            }
        }
    }

    /** Chips for the popup: frequency per dictionary (lowest number wins), pitch positions with their reading, IPA. */
    fun chips(rows: List<Pair<String, MetaRow>>): List<String> {
        val out = ArrayList<String>()
        rows.filter { it.second.kind == "freq" }.groupBy { it.first }.forEach { (dict, rs) ->
            val best = rs.minByOrNull { it.second.num ?: Double.MAX_VALUE }!!.second
            out.add("$dict ${best.value}")
        }
        rows.filter { it.second.kind == "pitch" }.map { it.second }.distinctBy { it.reading to it.value }.take(3).forEach {
            out.add("Pitch " + (if (it.reading.isNotEmpty()) "${it.reading} " else "") + it.value.split(',').joinToString(" ") { p -> "[$p]" })
        }
        rows.filter { it.second.kind == "ipa" }.map { it.second.value }.distinct().take(2).forEach { out.add("/$it/") }
        return out
    }
}

/** A small JSON reader (no Android classes, so it can be tested on a plain JVM). Objects become maps, arrays lists, numbers Double. */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text.removePrefix("\uFEFF"))
        p.ws(); val v = p.value(); p.ws()
        if (p.i != p.s.length) throw IllegalArgumentException("Unexpected text at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("Unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw IllegalArgumentException("Bad literal at $i"); i += w.length; return v }
        fun num(): Double {
            val st = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            return s.substring(st, i).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number at $st")
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("Unterminated string")
                val ch = s[i++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>()
            ws(); if (s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw IllegalArgumentException("Bad array at $i") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>()
            ws(); if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); if (s[i] != '"') throw IllegalArgumentException("Bad key at $i")
                val k = str(); ws()
                if (s[i++] != ':') throw IllegalArgumentException("Missing colon at $i")
                ws(); out[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw IllegalArgumentException("Bad object at $i") }
            }
        }
    }
}
