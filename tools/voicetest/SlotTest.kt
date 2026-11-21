import app.fluvour.audio.Pcm
import app.fluvour.audio.VoiceEngine
import app.fluvour.audio.VoiceSlot
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) fails++ }
class Fake(val dir: File, val speakers: Int, val sentences: Int = 3) : VoiceEngine {
    var freed = false; var lastSid = -1; var made = 0
    override val numSpeakers get() = speakers
    override fun generate(text: String, sid: Int, speed: Float, shouldStop: () -> Boolean): Pcm? {
        lastSid = sid
        repeat(sentences) { if (shouldStop()) return null; made++ }
        return Pcm(FloatArray(100) { 0.5f }, 22050)
    }
    override fun free() { freed = true }
}
fun main() {
    var t = 0L; val made = ArrayList<Fake>()
    val slot = VoiceSlot(60_000, { t }) { d -> Fake(d, 54).also { made += it } }
    val a = File("/p/a"); val b = File("/p/b")
    check("nothing loaded at first", slot.loadedPath == null)
    check("first call loads and speaks", slot.synthesize(a, "hi", 3)?.sampleRate == 22050 && made.size == 1 && slot.loadedPath == a.absolutePath)
    slot.synthesize(a, "again", 3)
    check("same folder reuses the model", made.size == 1)
    slot.synthesize(b, "other", 0)
    check("other folder swaps: old freed, one loaded", made.size == 2 && made[0].freed && !made[1].freed && slot.loadedPath == b.absolutePath)
    slot.synthesize(b, "x", 99); check("speaker past the end falls back to 0", made[1].lastSid == 0)
    slot.synthesize(b, "x", -4); check("negative speaker falls back to 0", made[1].lastSid == 0)
    slot.synthesize(b, "x", 45); check("speaker in range kept", made[1].lastSid == 45)
    val mono = VoiceSlot(1000, { t }) { d -> Fake(d, 0).also { made += it } }
    mono.synthesize(a, "x", 7); check("model with no speaker count uses 0", made.last().lastSid == 0)
    // cancel
    var calls = 0; val before = made[1].made
    val r = slot.synthesize(b, "long text", 0, 1f) { calls++ >= 1 }
    check("stop between sentences gives null, work stopped early", r == null && made[1].made == before + 1)
    check("model stays loaded after a stop", slot.loadedPath == b.absolutePath && !made[1].freed)
    // idle
    t = 0; slot.synthesize(b, "x"); t = 59_999
    check("not idle yet: kept", !slot.releaseIfIdle() && slot.loadedPath != null)
    t = 60_000
    check("idle for a minute: freed", slot.releaseIfIdle() && made[1].freed && slot.loadedPath == null)
    check("idle check with nothing loaded does nothing", !slot.releaseIfIdle())
    slot.synthesize(b, "x"); check("loads again after idle free", made.size == 4 && !made.last().freed)
    t += 30_000; slot.synthesize(b, "x"); t += 40_000
    check("use resets the idle clock", !slot.releaseIfIdle())
    slot.release(); check("release frees at once", slot.loadedPath == null && made.last().freed)
    slot.release(); check("release twice is fine", true)
    var threw = false
    try { slot.synthesize(a, "   ") } catch (e: IllegalArgumentException) { threw = true }
    check("blank text refused", threw)
    val bad = VoiceSlot(1000, { t }) { _ -> error("corrupt model") }
    threw = false
    try { bad.synthesize(a, "x") } catch (e: IllegalStateException) { threw = true }
    check("load failure throws, nothing kept", threw && bad.loadedPath == null)
    // threads: two callers never run inside the engine at the same time
    var inside = 0; var overlap = false
    val busy = VoiceSlot(1000, { t }) { d -> object : VoiceEngine {
        override val numSpeakers = 1
        override fun generate(text: String, sid: Int, speed: Float, shouldStop: () -> Boolean): Pcm? { if (++inside > 1) overlap = true; Thread.sleep(20); inside--; return Pcm(FloatArray(1), 1) }
        override fun free() {}
    } }
    val ts = List(4) { Thread { repeat(5) { busy.synthesize(a, "x") } } }; ts.forEach { it.start() }; ts.forEach { it.join() }
    check("calls are serialised", !overlap)
    // wav
    val w = Pcm(floatArrayOf(0f, 1f, -1f, 2f), 16000).toWav(); val bb = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
    check("wav header and rate", String(w, 0, 4) == "RIFF" && String(w, 8, 4) == "WAVE" && bb.getInt(24) == 16000 && bb.getInt(28) == 32000 && w.size == 44 + 8)
    check("wav samples clipped to 16 bit", bb.getShort(44).toInt() == 0 && bb.getShort(46).toInt() == 32767 && bb.getShort(48).toInt() == -32767 && bb.getShort(50).toInt() == 32767)
    check("pcm seconds", Pcm(FloatArray(8000), 16000).seconds == 0.5f)
    if (fails > 0) { println("$fails failed"); System.exit(1) }
}
