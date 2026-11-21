import app.fluvour.lyrics.LyricsParsers
import app.fluvour.lyrics.LyricsText
import app.fluvour.media.SubLine
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

var fails = 0
fun check(name: String, ok: Boolean) { if (!ok) { fails++; println("FAIL $name") } else println("ok   $name") }

fun krcEncode(text: String): String {
    val key = intArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69)
    val d = Deflater(); d.setInput(text.toByteArray()); d.finish()
    val bos = ByteArrayOutputStream(); val b = ByteArray(4096)
    while (!d.finished()) bos.write(b, 0, d.deflate(b))
    val z = bos.toByteArray()
    val out = ByteArray(z.size + 4) { 'k'.code.toByte() }
    for (i in z.indices) out[i + 4] = (z[i].toInt() xor key[i % 16]).toByte()
    return Base64.getEncoder().encodeToString(out)
}

fun main() {
    val lrc = "[00:12.50] <00:12.50>Hello <00:13.10>darling <00:13.90>tonight\n[00:20.00]Plain line here\n[00:05.00]Early\n[ar:x]\n"
    val l = LyricsParsers.parseLrc(lrc)
    check("lrc sorted", l.map { it.startMs } == listOf(5000L, 12500L, 20000L))
    check("lrc words", l[1].words.size == 3 && l[1].words[1].startMs == 13100L && l[1].text == "Hello darling tonight")
    check("lrc plain no words", l[2].words.isEmpty())
    check("wordSynced false mixed", !LyricsParsers.wordSynced(l))

    val krc = "[offset:0]\n[ar:Foo]\n[0,2000]<0,500,0>He<500,500,0>llo <1000,1000,0>world\n[2500,1500]<0,700,0>Second<700,800,0> line\n[5000,500]作词：Someone\n"
    val k = LyricsParsers.parseKrc(krc)
    check("krc 2 lines, credit dropped", k.size == 2)
    check("krc word times", k[0].words.map { it.startMs } == listOf(0L, 500L, 1000L) && k[0].text == "Hello world")
    check("krc roundtrip", LyricsParsers.krcDecode(krcEncode(krc)) == krc)
    check("krc bad input null", LyricsParsers.krcDecode("!!!") == null)

    val g = LyricsText.guess("Taylor Swift - Love Story (Official Music Video) [4K]", "TaylorSwiftVEVO")
    check("guess artist", g.artist == "Taylor Swift" && g.title == "Love Story")
    check("guess alt", g.alt?.artist == "Love Story")
    val j = LyricsText.guess("YOASOBI「夜に駆ける」 Official Music Video", "Ayase")
    check("guess jp", j.title == "夜に駆ける" && j.artist == "YOASOBI")
    check("accepts same", LyricsText.accepts("Love Story", "Taylor Swift", 235, g, 236))
    check("rejects live", !LyricsText.accepts("Love Story (Live)", "Taylor Swift", 235, g, 236))
    check("rejects other song", !LyricsText.accepts("Shake It Off", "Taylor Swift", 235, g, 236))
    check("rejects far duration", !LyricsText.accepts("Love Story", "Taylor Swift", 400, g, 236))
    check("sim", LyricsText.sim("Love Story", "love story!") == 1.0)
    check("script ja", LyricsText.scriptFits("ja", listOf(SubLine(0, 1, "夜に駆けるさあ行こう"))))
    check("script en rejects thai", !LyricsText.scriptFits("en", listOf(SubLine(0, 1, "สวัสดีทุกคนครับวันนี้"))))

    val ly = (0 until 20).map { SubLine(it * 5000L, it * 5000L + 4000, "this is lyric line number ${'a' + it} with words") }
    val ref = (0 until 20).map { SubLine(it * 5000L + 2000, it * 5000L + 6000, "this is lyric line number ${'a' + it} with") }
    val a = LyricsText.agreement(ly, ref)
    check("agreement offset 2000", a.pairs >= 15 && a.offsetMs == 2000L && a.spreadMs == 0L)
    val bad = (0 until 20).map { SubLine(it * 5000L, it * 5000L + 3000, "completely different text ${it * 7919} xyz") }
    check("agreement unrelated", LyricsText.agreement(ly, bad).pairs < 4)
    println(if (fails == 0) "ALL OK" else "$fails FAILED")
    if (fails > 0) System.exit(1)
}
