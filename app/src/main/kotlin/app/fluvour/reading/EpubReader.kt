package app.fluvour.reading

import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Phase 2A step 8: reads an EPUB into chapters of plain text. Pure JVM, no Android imports, no extra libraries.
 * An EPUB is a zip: META-INF/container.xml points to the .opf file, whose spine lists the chapter files in reading
 * order; chapter titles come from the book's contents page (EPUB 3 nav or EPUB 2 ncx), else from the first heading.
 * Only text entries are read, so images and fonts never use memory.
 */
object EpubReader {
    private const val MAX_TEXT_BYTES = 80L * 1024 * 1024
    private val textExt = setOf("xhtml", "html", "htm", "xml", "opf", "ncx")
    private val attrRe = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val itemRe = Regex("(?is)<item\\b[^>]*>")
    private val itemrefRe = Regex("(?is)<itemref\\b[^>]*>")
    private val spineRe = Regex("(?is)<spine\\b[^>]*>")
    private val navAnchor = Regex("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
    private val navBlock = Regex("(?is)<nav\\b[^>]*(?:epub:type|role)\\s*=\\s*[\"'](?:doc-)?toc[\"'][^>]*>(.*?)</nav>")
    private val ncxPoint = Regex("(?is)<navPoint\\b.*?<navLabel>\\s*<text>(.*?)</text>.*?</navLabel>\\s*<content\\b[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']")

    private class Item(val path: String, val mediaType: String, val properties: List<String>)
    private class Draft(val title: String, val paras: MutableList<String>)

    fun read(input: InputStream, fallbackTitle: String = "Book"): ParsedBook {
        val files = readTextEntries(input)
        val byLower = files.keys.associateBy { it.lowercase() }
        fun text(path: String): String? = (files[path] ?: byLower[path.lowercase()]?.let { files[it] })?.let(::decodeUtf)

        val container = text("META-INF/container.xml") ?: throw ImportException("This does not look like an EPUB file.")
        val opfPath = Regex("(?i)full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1)
            ?: files.keys.firstOrNull { it.endsWith(".opf", true) } ?: throw ImportException("This does not look like an EPUB file.")
        val opf = text(opfPath) ?: throw ImportException("This EPUB is damaged: its book description is missing.")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = LinkedHashMap<String, Item>()
        for (m in itemRe.findAll(opf)) {
            val a = attrs(m.value)
            val id = a["id"]; val href = a["href"]
            if (id != null && href != null) manifest[id] = Item(resolve(opfDir, href), a["media-type"].orEmpty().lowercase(), a["properties"].orEmpty().split(' '))
        }
        fun isHtml(i: Item) = i.mediaType.contains("html") || i.path.substringAfterLast('.', "").lowercase() in setOf("xhtml", "html", "htm")
        fun isNav(i: Item) = "nav" in i.properties

        val tocId = spineRe.find(opf)?.let { attrs(it.value)["toc"] }
        var spine = itemrefRe.findAll(opf).map { attrs(it.value) }.filter { it["linear"]?.lowercase() != "no" }
            .mapNotNull { it["idref"] }.mapNotNull { manifest[it] }.filter { isHtml(it) && !isNav(it) }.toList()
        if (spine.isEmpty()) spine = manifest.values.filter { isHtml(it) && !isNav(it) }

        val titles = LinkedHashMap<String, String>()
        manifest.values.firstOrNull(::isNav)?.let { n -> text(n.path)?.let { parseNav(it, n.path.substringBeforeLast('/', ""), titles) } }
        if (titles.isEmpty()) {
            val ncx = manifest[tocId ?: ""] ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ncx?.let { n -> text(n.path)?.let { parseNcx(it, n.path.substringBeforeLast('/', ""), titles) } }
        }
        val hasToc = titles.isNotEmpty()

        // A spine file the contents page does not list continues the chapter before it.
        val drafts = ArrayList<Draft>()
        for (item in spine) {
            val html = text(item.path) ?: continue
            val paras = HtmlText.toParagraphs(html)
            val toc = titles[item.path]
            if (!hasToc || toc != null || drafts.isEmpty()) drafts += Draft(toc ?: HtmlText.firstHeading(html).orEmpty(), paras.toMutableList())
            else drafts.last().paras += paras
        }
        val chapters = ArrayList<ParsedChapter>()
        var n = 0
        for (d in drafts) {
            val ps = if (d.paras.isNotEmpty() && d.title.isNotEmpty() && d.paras.first() == d.title) d.paras.drop(1) else d.paras
            if (ps.sumOf { it.length } < 20) continue   // covers, dividers, image-only pages
            n += 1
            chapters += Chunker.split(d.title.ifEmpty { if (hasToc) "Front matter" else "Chapter $n" }, ps)
        }
        if (chapters.isEmpty()) throw ImportException("No readable text was found. The book may be copy-protected.")

        val meta = Regex("(?is)<(?:\\w+:)?metadata\\b.*?</(?:\\w+:)?metadata>").find(opf)?.value ?: opf
        fun tag(name: String) = Regex("(?is)<(?:\\w+:)?$name\\b[^>]*>(.*?)</(?:\\w+:)?$name>").find(meta)?.groupValues?.get(1)
            ?.let(HtmlText::inline)?.takeIf { it.isNotEmpty() }
        return ParsedBook(tag("title") ?: fallbackTitle, tag("creator"), tag("language"), chapters)
    }

    /** Maps an EPUB language tag (en, en-US, eng, zh-Hans, jpn...) to one of the app's ten language codes, or null. */
    fun languageCode(tag: String?): String? {
        val t = tag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        val two = when (t) {
            "eng" -> "en"; "jpn" -> "ja"; "zho", "chi" -> "zh"; "kor" -> "ko"; "spa" -> "es"
            "fra", "fre" -> "fr"; "deu", "ger" -> "de"; "vie" -> "vi"; "ind" -> "id"; "tha" -> "th"
            else -> t
        }
        return two.takeIf { it in setOf("ja", "th", "en", "zh", "ko", "es", "fr", "de", "vi", "id") }
    }

    private fun readTextEntries(input: InputStream): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.substringAfterLast('.', "").lowercase() in textExt) {
                        val bytes = zip.readBytes()
                        total += bytes.size
                        if (total > MAX_TEXT_BYTES) throw ImportException("This book is too large to import.")
                        files[e.name] = bytes
                    }
                    e = zip.nextEntry
                }
            }
        } catch (e: ZipException) { throw ImportException("This file is damaged or is not an EPUB.") }
        return files
    }

    private fun decodeUtf(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else -> String(b, Charsets.UTF_8)
    }

    private fun attrs(tag: String): Map<String, String> = attrRe.findAll(tag).associate { m ->
        m.groupValues[1].lowercase() to HtmlText.decodeEntities(m.groupValues[3].ifEmpty { m.groupValues[4] })
    }

    private fun parseNav(html: String, dir: String, out: MutableMap<String, String>) {
        val scope = navBlock.find(html)?.groupValues?.get(1) ?: html
        for (m in navAnchor.findAll(scope)) {
            val title = HtmlText.inline(m.groupValues[2])
            if (title.isNotEmpty()) out.putIfAbsent(resolve(dir, m.groupValues[1]), title)
        }
    }

    private fun parseNcx(xml: String, dir: String, out: MutableMap<String, String>) {
        for (m in ncxPoint.findAll(xml)) {
            val title = HtmlText.inline(m.groupValues[1])
            if (title.isNotEmpty()) out.putIfAbsent(resolve(dir, m.groupValues[2]), title)
        }
    }

    /** A path inside the zip: relative to `baseDir`, fragment and query dropped, %-escapes decoded, "." and ".." folded. */
    internal fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = try { URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") } catch (e: IllegalArgumentException) { clean }
        val full = if (decoded.startsWith("/")) decoded.drop(1) else if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        val parts = ArrayList<String>()
        for (seg in full.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += seg
        }
        return parts.joinToString("/")
    }
}
