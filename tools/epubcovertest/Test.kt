import app.fluvour.reading.EpubCover
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small EPUBs in memory and checks which picture EpubCover picks. Run: see README.md. */
var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) fails++ }

val JPG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 1)
val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13, 1, 2)
val WEBP = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
val SVG = "<svg xmlns='http://www.w3.org/2000/svg'></svg>  ".toByteArray()

fun epub(opf: String, files: Map<String, ByteArray>, opfPath: String = "OEBPS/content.opf"): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        put("mimetype", "application/epub+zip".toByteArray())
        put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
        put(opfPath, opf.toByteArray())
        files.forEach { (n, b) -> put(n, b) }
    }
    return out.toByteArray()
}
fun cover(b: ByteArray): ByteArray? = EpubCover.find { ByteArrayInputStream(b) }
fun opf(manifest: String, meta: String = "") = """<package><metadata><dc:title>T</dc:title>$meta</metadata><manifest>$manifest</manifest><spine/></package>"""

fun main() {
    // EPUB 3 cover-image property
    val e3 = epub(opf("""<item id="x" href="img/front.jpg" media-type="image/jpeg" properties="cover-image"/><item id="y" href="img/other.png" media-type="image/png"/>"""),
        mapOf("OEBPS/img/front.jpg" to JPG, "OEBPS/img/other.png" to PNG))
    check("epub3 cover-image property", cover(e3)?.contentEquals(JPG) == true)

    // EPUB 2 meta cover, attribute order reversed, single quotes
    val e2 = epub(opf("""<item id="c1" href="pic.png" media-type="image/png"/><item id="z" href="z.jpg" media-type="image/jpeg"/>""", """<meta content='c1' name='cover'/>"""),
        mapOf("OEBPS/pic.png" to PNG, "OEBPS/z.jpg" to JPG))
    check("epub2 meta cover", cover(e2)?.contentEquals(PNG) == true)

    // Name only
    val nm = epub(opf("""<item id="i1" href="a.jpg" media-type="image/jpeg"/><item id="i2" href="images/Cover.jpg" media-type="image/jpeg"/>"""),
        mapOf("OEBPS/a.jpg" to PNG, "OEBPS/images/Cover.jpg" to JPG))
    check("cover found by file name", cover(nm)?.contentEquals(JPG) == true)

    // No cover at all: do not guess from an ordinary picture
    val none = epub(opf("""<item id="i1" href="a.jpg" media-type="image/jpeg"/>"""), mapOf("OEBPS/a.jpg" to JPG))
    check("no cover gives null", cover(none) == null)

    // WebP is accepted, SVG is not
    val wp = epub(opf("""<item id="cover" href="c.webp" media-type="image/webp" properties="cover-image"/>"""), mapOf("OEBPS/c.webp" to WEBP))
    check("webp accepted", cover(wp)?.contentEquals(WEBP) == true)
    val sv = epub(opf("""<item id="cover" href="c.svg" media-type="image/svg+xml" properties="cover-image"/>"""), mapOf("OEBPS/c.svg" to SVG))
    check("svg cover ignored", cover(sv) == null)

    // First candidate is not a picture, second is: falls through
    val fall = epub(opf("""<item id="a" href="a.jpg" media-type="image/jpeg" properties="cover-image"/><item id="cover" href="cover.png" media-type="image/png"/>"""),
        mapOf("OEBPS/a.jpg" to "not a picture at all, just text".toByteArray(), "OEBPS/cover.png" to PNG))
    check("bad first candidate falls through", cover(fall)?.contentEquals(PNG) == true)

    // Too big
    val big = ByteArray(EpubCover.MAX_BYTES + 100) { 1 }.also { JPG.copyInto(it) }
    val huge = epub(opf("""<item id="cover" href="c.jpg" media-type="image/jpeg" properties="cover-image"/>"""), mapOf("OEBPS/c.jpg" to big))
    check("over 5 MB ignored", cover(huge) == null)

    // Paths: opf at root, href with %20 and ../
    val root = epub(opf("""<item id="cover" href="my%20pics/c.jpg" media-type="image/jpeg" properties="cover-image"/>"""), mapOf("my pics/c.jpg" to JPG), opfPath = "content.opf")
    check("opf at root, escaped path", cover(root)?.contentEquals(JPG) == true)
    val up = epub(opf("""<item id="cover" href="../shared/c.png" media-type="image/png" properties="cover-image"/>"""), mapOf("shared/c.png" to PNG), opfPath = "OEBPS/content.opf")
    check("href with ..", cover(up)?.contentEquals(PNG) == true)
    val ci = epub(opf("""<item id="cover" href="C.JPG" media-type="image/jpeg" properties="cover-image"/>"""), mapOf("OEBPS/c.jpg" to JPG))
    check("case differs in zip", cover(ci)?.contentEquals(JPG) == true)

    // Messy input never throws
    check("not a zip", cover("hello".toByteArray()) == null)
    check("empty", cover(ByteArray(0)) == null)
    check("zip without opf", cover(ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write(1); z.closeEntry() } }.toByteArray()) == null)
    val noManifest = epub("<package/>", emptyMap())
    check("opf without manifest", cover(noManifest) == null)
    var opens = 0
    check("open failing is null", EpubCover.find { opens++; throw java.io.IOException("x") } == null && opens == 1)
    check("truncated zip does not throw", runCatching { cover(e3.copyOf(e3.size / 2)) }.isSuccess)

    println(if (fails == 0) "ALL OK" else "$fails FAILED")
}
