package app.fluvour.reading

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Shown to the user as is, so keep the wording plain. */
class ImportException(message: String) : Exception(message)

data class ParsedChapter(val title: String, val text: String)
data class ParsedBook(val title: String, val author: String?, val language: String?, val chapters: List<ParsedChapter>)

/**
 * Phase 2A step 8: turning HTML into reader paragraphs. Pure JVM, no Android imports.
 * Paragraphs are separated by "\n" in the saved text, which is what the reader splits on.
 */
object HtmlText {
    private val comments = Regex("(?s)<!--.*?-->")
    // rt and rp hold furigana / pinyin; dropping them keeps the base text clean (<ruby>漢字<rt>かんじ</rt></ruby> gives 漢字)
    private val dropped = Regex("(?is)<(script|style|head|svg|rt|rp)\\b[^>]*>.*?</\\1\\s*>")
    private val lineBreaks = Regex("(?i)<br\\s*/?>")
    private val blocks = Regex("(?i)</?(?:p|div|h[1-6]|li|ul|ol|tr|td|th|table|blockquote|section|article|header|footer|aside|figure|figcaption|pre|dd|dt|dl|hr|nav|body|html)\\b[^>]*>")
    private val anyTag = Regex("<[^>]*>")
    private val spaces = Regex("\\s+")
    private val cjk = "\\u3000-\\u30FF\\u3400-\\u9FFF\\uFF00-\\uFFEF"
    private val cjkBreak = Regex("(?<=[$cjk])[ \\t]*\\r?\\n\\s*(?=[$cjk])")
    private val anyBreak = Regex("\\s*\\r?\\n\\s*")
    private val headingRe = Regex("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]>")
    private val titleRe = Regex("(?is)<title\\b[^>]*>(.*?)</title>")

    fun toParagraphs(html: String): List<String> {
        var s = comments.replace(html, "")
        s = dropped.replace(s, "")
        s = joinSourceLines(s)   // line breaks in the HTML source are not paragraph breaks
        s = lineBreaks.replace(s, "\n")
        s = blocks.replace(s, "\n")
        s = anyTag.replace(s, "")
        s = decodeEntities(s)
        return s.split('\n').map(::tidy).filter { it.isNotEmpty() }
    }

    /** Tags removed, entities decoded, whitespace collapsed: for titles and table-of-contents entries. */
    fun inline(html: String): String = tidy(decodeEntities(anyTag.replace(dropped.replace(comments.replace(html, ""), ""), "")))

    fun firstHeading(html: String): String? = headingRe.find(html)?.groupValues?.get(1)?.let(::inline)?.takeIf { it.isNotEmpty() }
    fun titleTag(html: String): String? = titleRe.find(html)?.groupValues?.get(1)?.let(::inline)?.takeIf { it.isNotEmpty() }

    /** Joins lines of one paragraph: no space where both sides are Japanese / Chinese characters, one space elsewhere. */
    fun joinSourceLines(s: String): String = anyBreak.replace(cjkBreak.replace(s, ""), " ")

    /** One line of text: single spaces and trimmed. */
    fun tidy(s: String): String = spaces.replace(s.replace('\u00A0', ' '), " ").trim()

    private val entityRe = Regex("&(#[xX][0-9A-Fa-f]+|#[0-9]+|[A-Za-z][A-Za-z0-9]*);")
    private val latin1 = ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro para " +
        "middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml " +
        "Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
        "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc " +
        "otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml").split(' ')
    internal val named: Map<String, String> = buildMap {
        latin1.forEachIndexed { i, n -> put(n, (160 + i).toChar().toString()) }
        put("amp", "&"); put("lt", "<"); put("gt", ">"); put("quot", "\""); put("apos", "'")
        put("nbsp", " "); put("ensp", " "); put("emsp", " "); put("thinsp", " "); put("shy", "")
        put("zwj", ""); put("zwnj", ""); put("lrm", ""); put("rlm", "")
        put("hellip", "\u2026"); put("mdash", "\u2014"); put("ndash", "\u2013"); put("bull", "\u2022")
        put("lsquo", "\u2018"); put("rsquo", "\u2019"); put("ldquo", "\u201C"); put("rdquo", "\u201D")
        put("euro", "\u20AC"); put("trade", "\u2122")
    }

    fun decodeEntities(s: String): String = if (!s.contains('&')) s else entityRe.replace(s) { m ->
        val g = m.groupValues[1]
        when {
            g.startsWith("#x") || g.startsWith("#X") -> codePoint(g.substring(2).toIntOrNull(16), m.value)
            g.startsWith("#") -> codePoint(g.substring(1).toIntOrNull(), m.value)
            else -> named[g] ?: m.value
        }
    }

    private fun codePoint(n: Int?, original: String): String =
        if (n != null && n in 1..0x10FFFF && n !in 0xD800..0xDFFF) String(Character.toChars(n)) else original
}

/**
 * Cuts long text into rows the reader and the database can handle. One saved row is one chapter or part: the reader
 * splits and looks up a whole row at once, and a database row over about 2 MB cannot be read back on Android.
 */
object Chunker {
    const val MAX_CHARS = 12_000
    private const val MAX_PARAGRAPH = 2_500

    fun split(title: String, paragraphs: List<String>, max: Int = MAX_CHARS): List<ParsedChapter> {
        val flat = paragraphs.flatMap { splitLong(it) }
        val parts = ArrayList<List<String>>()
        var cur = ArrayList<String>(); var size = 0
        for (p in flat) {
            if (cur.isNotEmpty() && size + p.length > max) { parts += cur; cur = ArrayList(); size = 0 }
            cur += p; size += p.length + 1
        }
        if (cur.isNotEmpty()) parts += cur
        return parts.mapIndexed { i, ps -> ParsedChapter(if (parts.size == 1) title else "$title (${i + 1})", ps.joinToString("\n")) }
    }

    /** A paragraph over the limit (a text pasted with no line breaks) is cut after a sentence end, else at a space, else hard. */
    internal fun splitLong(p: String, limit: Int = MAX_PARAGRAPH): List<String> {
        if (p.length <= limit) return listOf(p)
        val out = ArrayList<String>()
        var rest = p
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            var cut = -1
            for (i in window.length - 1 downTo limit / 2) {
                val c = window[i]
                if (c in "。！？!?…" || (c == '.' && i + 1 < window.length && window[i + 1] == ' ')) { cut = i + 1; break }
            }
            if (cut < 0) { val sp = window.lastIndexOf(' '); cut = if (sp >= limit / 2) sp + 1 else limit }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotEmpty() }
    }
}

/** Plain text and HTML files, and pasted text. */
object TextImport {
    // Lines that look like chapter headings in web novels and e-books, in the app's ten languages.
    private val headingRe = Regex("^(?:" + listOf(
        "第\\s*[0-9０-９一二三四五六七八九十百千零〇两兩]+\\s*[章話话節节回幕巻卷部篇]",
        "(?:chapter|chap\\.|part|book|section)\\s+(?:[0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten)\\b",
        "(?:cap[ií]tulo|chapitre|kapitel|capitolo)\\s+(?:[0-9]+|[ivxlcdm]+)\\b",
        "(?:prologue|epilogue|preface|prologo|prólogo|prolog|epilog|épilogue|プロローグ|エピローグ|序章|終章|终章|楔子|尾声|尾聲)\\s*$",
        "제\\s*[0-9]+\\s*[장화권부]",
        "บทที่\\s*[0-9๐-๙]+",
        "(?:chương|phần)\\s+[0-9]+",
        "bab\\s+(?:[0-9]+|[ivxlcdm]+)\\b"
    ).joinToString("|") + ")", RegexOption.IGNORE_CASE)

    internal fun isHeading(p: String) = p.length <= 60 && headingRe.containsMatchIn(p)

    /** Decodes a file's bytes. UTF-8 (with or without a BOM) first; old Japanese, Chinese, Korean and Thai files fall back to their usual code pages. */
    fun decode(bytes: ByteArray, lang: String): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) { /* not UTF-8, try the old code pages */ }
        val names = when (lang.substringBefore('-').lowercase()) {
            "ja" -> listOf("windows-31j", "Shift_JIS")
            "zh" -> listOf("GB18030", "GBK")
            "ko" -> listOf("x-windows-949", "windows-949", "EUC-KR")
            "th" -> listOf("windows-874", "TIS-620")
            else -> listOf("windows-1252", "ISO-8859-1")
        }
        val cs = names.firstNotNullOfOrNull { n -> try { if (Charset.isSupported(n)) Charset.forName(n) else null } catch (e: Exception) { null } } ?: Charsets.ISO_8859_1
        return String(bytes, cs)
    }

    /** Reader paragraphs from plain text. Hard-wrapped books (blank line between paragraphs, line breaks inside) are re-joined; one-line-per-paragraph text is kept as is. */
    internal fun paragraphs(raw: String): List<String> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
        val blocks = text.split(Regex("\\n(?:[ \\t\\u3000]*\\n)+")).map { it.trim('\n') }.filter { it.isNotBlank() }
        val wrapped = blocks.size >= 2 && blocks.count { it.contains('\n') } * 2 > blocks.size
        val lines = if (wrapped) blocks.map(HtmlText::joinSourceLines) else blocks.flatMap { it.split('\n') }
        return lines.map(HtmlText::tidy).filter { it.isNotEmpty() }
    }

    /** `title` null or blank: the first line is used (shortened). Chapters come from heading lines when there are at least two. */
    fun fromText(raw: String, title: String?): ParsedBook {
        val paras = paragraphs(raw)
        if (paras.isEmpty()) throw ImportException("There is no text to import.")
        val name = title?.trim().takeUnless { it.isNullOrEmpty() } ?: paras.first().let { if (it.length > 48) it.take(48).trimEnd() + "\u2026" else it }
        val heads = paras.indices.filter { isHeading(paras[it]) }
        val chapters = if (heads.size >= 2) byHeadings(paras, heads).ifEmpty { Chunker.split(name, paras) } else Chunker.split(name, paras)
        return ParsedBook(name, null, null, chapters)
    }

    private fun byHeadings(paras: List<String>, heads: List<Int>): List<ParsedChapter> {
        val out = ArrayList<ParsedChapter>()
        var carry: List<String> = paras.subList(0, heads[0])   // text before the first heading goes into the first chapter
        for ((k, start) in heads.withIndex()) {
            val end = if (k + 1 < heads.size) heads[k + 1] else paras.size
            val body = carry + paras.subList(start + 1, end)
            if (body.isEmpty()) continue   // a divider such as "Part 1" with nothing under it
            carry = emptyList()
            out += Chunker.split(paras[start], body)
        }
        return out
    }

    fun fromHtml(raw: String, fallbackTitle: String): ParsedBook {
        val paras = HtmlText.toParagraphs(raw)
        if (paras.isEmpty()) throw ImportException("There is no text in this file.")
        val title = HtmlText.titleTag(raw) ?: HtmlText.firstHeading(raw) ?: fallbackTitle
        return ParsedBook(title, null, null, Chunker.split(title, paras))
    }
}
