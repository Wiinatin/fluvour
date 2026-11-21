import app.fluvour.audio.PcmSplit

var failed = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) failed++ }

const val RATE = 24000
/** Fake speech: tone bursts with a small dip inside (like a comma), then silence of [gapMs]. Each part is (speechMs, gapAfterMs). */
fun make(parts: List<Pair<Int, Int>>, lead: Int = 300, noise: Int = 0): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun put(ms: Int, amp: Int) { for (i in 0 until RATE * ms / 1000) { val v = (amp * Math.sin(i * 0.15) + (if (noise > 0) (i % 7 - 3) * noise else 0)).toInt(); out.write(v and 0xff); out.write((v shr 8) and 0xff) } }
    put(lead, 0)
    for ((speech, gap) in parts) {
        put(speech / 2, 8000); put(180, 0); put(speech - speech / 2, 8000); put(gap, 0)
    }
    return out.toByteArray()
}

fun main() {
    val five = make(List(5) { 1500 to 2000 })
    val r = PcmSplit.split(five, RATE, 5)
    check("5 sentences, 2 s gaps: 5 pieces", r?.size == 5)
    check("pieces are whole sentences (longer than 1.4 s)", r?.all { it.size / 2 > RATE * 14 / 10 } == true)
    check("a short dip inside a sentence is not a cut", PcmSplit.split(five, RATE, 10) == null)
    check("wrong count gives null", PcmSplit.split(five, RATE, 4) == null && PcmSplit.split(five, RATE, 6) == null)
    check("short 0.8 s gaps still work", PcmSplit.split(make(List(8) { 1200 to 800 }), RATE, 8)?.size == 8)
    check("uneven gaps (0.7 to 2 s)", PcmSplit.split(make(listOf(1000 to 700, 2000 to 2000, 900 to 1000, 1500 to 1500)), RATE, 4)?.size == 4)
    check("low background noise is ignored", PcmSplit.split(make(List(6) { 1400 to 1800 }, noise = 6), RATE, 6)?.size == 6)
    check("one sentence", PcmSplit.split(make(listOf(1500 to 500)), RATE, 1)?.size == 1)
    check("silence only gives null", PcmSplit.split(ByteArray(RATE * 2 * 3), RATE, 2) == null)
    check("empty input gives null", PcmSplit.split(ByteArray(0), RATE, 3) == null)
    println(if (failed == 0) "ALL OK" else "$failed FAILED")
    if (failed != 0) kotlin.system.exitProcess(1)
}
