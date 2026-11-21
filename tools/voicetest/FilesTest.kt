import app.fluvour.audio.PackFiles
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) fails++ }
fun zipOf(f: File, vararg e: Pair<String, String>) = ZipOutputStream(f.outputStream()).use { z -> for ((n, t) in e) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
fun main() {
    val t = File.createTempFile("packs", "").also { it.delete(); it.mkdirs() }
    val good = File(t, "good.zip"); zipOf(good, "tokens.txt" to "a", "model.onnx" to "xx", "espeak-ng-data/en_dict" to "yyy")
    val out = File(t, "out")
    check("unzip writes three files", PackFiles.unzip(good, out, 1000) == 3 && File(out, "espeak-ng-data/en_dict").readText() == "yyy")
    val slip = File(t, "slip.zip"); zipOf(slip, "ok.txt" to "a", "../evil.txt" to "b")
    var threw = false
    try { PackFiles.unzip(slip, File(t, "o2"), 1000) } catch (e: java.io.IOException) { threw = true }
    check("zip with ../ entry is refused", threw && !File(t, "evil.txt").exists())
    val abs = File(t, "abs.zip"); zipOf(abs, "/tmp/evil-abs.txt" to "b")
    threw = false
    try { PackFiles.unzip(abs, File(t, "o3"), 1000) } catch (e: java.io.IOException) { threw = true }
    check("absolute path entry stays inside or is refused", threw || !File("/tmp/evil-abs.txt").exists())
    val big = File(t, "big.zip"); zipOf(big, "a.bin" to "x".repeat(500), "b.bin" to "y".repeat(500))
    threw = false
    try { PackFiles.unzip(big, File(t, "o4"), 600) } catch (e: java.io.IOException) { threw = true }
    check("unpacked size cap", threw)
    check("sha256 of 'abc'", File(t, "abc").also { it.writeText("abc") }.let { PackFiles.sha256(it) } == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    val part = File(t, "p.part")
    check("no part: 0", PackFiles.resumeOffset(part, 100) == 0L)
    part.writeBytes(ByteArray(40)); check("part kept", PackFiles.resumeOffset(part, 100) == 40L)
    part.writeBytes(ByteArray(100)); check("full part kept", PackFiles.resumeOffset(part, 100) == 100L)
    part.writeBytes(ByteArray(101)); check("too long part dropped", PackFiles.resumeOffset(part, 100) == 0L && !part.exists())
    check("content-range start", PackFiles.contentRangeStart("bytes 20000000-67376968/67376969") == 20000000L)
    check("content-range odd", PackFiles.contentRangeStart(null) == null && PackFiles.contentRangeStart("items 1-2/3") == null && PackFiles.contentRangeStart("bytes */100") == null)
    check("percent", PackFiles.percent(0, 100) == 0 && PackFiles.percent(42, 100) == 42 && PackFiles.percent(100, 100) == 100 && PackFiles.percent(5, 0) == 0)
    if (fails > 0) { println("$fails failed"); System.exit(1) }
}
