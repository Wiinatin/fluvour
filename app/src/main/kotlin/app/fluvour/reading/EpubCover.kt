package app.fluvour.reading

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Read library step 8: finds the cover picture inside an EPUB. Pure JVM, no Android imports.
 * Order of tries: the item marked `cover-image` (EPUB 3), the `<meta name="cover">` item (EPUB 2), then an image whose id or
 * file name says "cover". Only real pictures count (JPEG, PNG, GIF, WebP), at most 5 MB. Returns null when there is none,
 * so the app draws its generated cover. Never throws: a damaged file simply has no cover.
 * `open` is called twice (one pass for the book description, one for the picture), so pass a function that opens the file anew.
 */
object EpubCover {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val attrRe = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val itemRe = Regex("(?is)<item\\b[^>]*>")
    private val metaRe = Regex("(?is)<meta\\b[^>]*>")

    private class Item(val id: String, val path: String, val mediaType: String, val properties: List<String>)

    fun find(open: () -> InputStream): ByteArray? = try { findOrNull(open) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private fun findOrNull(open: () -> InputStream): ByteArray? {
        val texts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(open()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val n = e.name
                if (!e.isDirectory && (n.equals("META-INF/container.xml", true) || n.endsWith(".opf", true))) texts[n] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        val container = texts.entries.firstOrNull { it.key.equals("META-INF/container.xml", true) }?.value?.let { String(it, Charsets.UTF_8) }
        val opfPath = container?.let { Regex("(?i)full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(it)?.groupValues?.get(1) }
            ?: texts.keys.firstOrNull { it.endsWith(".opf", true) } ?: return null
        val opf = (texts[opfPath] ?: texts.entries.firstOrNull { it.key.equals(opfPath, true) }?.value)?.let { String(it, Charsets.UTF_8) } ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")

        val items = itemRe.findAll(opf).mapNotNull { m ->
            val a = attrs(m.value)
            val id = a["id"]; val href = a["href"]
            if (id == null || href == null) null else Item(id, EpubReader.resolve(opfDir, href), a["media-type"].orEmpty().lowercase(), a["properties"].orEmpty().split(' '))
        }.toList()
        fun isImage(i: Item) = i.mediaType.startsWith("image/") || i.path.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp")
        val images = items.filter(::isImage)

        val coverMetaId = metaRe.findAll(opf).map { attrs(it.value) }.firstOrNull { it["name"]?.lowercase() == "cover" }?.get("content")
        val candidates = LinkedHashSet<String>()
        images.filter { "cover-image" in it.properties }.forEach { candidates += it.path }
        if (coverMetaId != null) images.filter { it.id == coverMetaId }.forEach { candidates += it.path }
        images.filter { it.id.contains("cover", true) || it.path.substringAfterLast('/').contains("cover", true) }.forEach { candidates += it.path }
        if (candidates.isEmpty()) return null

        val found = HashMap<String, ByteArray>()
        val wanted = candidates.associateBy { it.lowercase() }
        ZipInputStream(open()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val key = wanted[e.name.lowercase()]
                if (!e.isDirectory && key != null) readLimited(zip)?.let { found[key] = it }
                e = zip.nextEntry
            }
        }
        return candidates.firstNotNullOfOrNull { p -> found[p]?.takeIf(::isPicture) }
    }

    /** Reads the current entry, or null when it is bigger than the limit. */
    private fun readLimited(zip: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = zip.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    /** JPEG, PNG, GIF or WebP by the first bytes. A cover that is an SVG or something else is not used. */
    internal fun isPicture(b: ByteArray): Boolean = b.size > 12 && (
        (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) ||
        (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) ||
        (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()) ||
        (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()))

    private fun attrs(tag: String): Map<String, String> = attrRe.findAll(tag).associate { m ->
        m.groupValues[1].lowercase() to HtmlText.decodeEntities(m.groupValues[3].ifEmpty { m.groupValues[4] })
    }
}
