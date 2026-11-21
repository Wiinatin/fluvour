import app.fluvour.reading.*
import app.fluvour.ai.*
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) System.exit(1) }
fun main() {
    val tsv = listOf("the\t1\tA1","cat\t2\tA1","sat\t3\tA2","mat\t4\tB1","ephemeral\t5\tC2","walk\t6\tA1")
    val table = Levels.parse(tsv.asSequence())
    val c = ClipMeasurer.count(listOf("The cat sat", "the mat walk 123", "ephemeral zzz"), "en")
    check("tokens skip digits", c.tokenTotal == 8 && c.counts["the"] == 2)
    check("levelCode", ClipMeasurer.levelCode(c, "en", table) == "C2")   // zzz unlisted so 90% needs C2: 8 of 9 listed = 89%
    val s = ClipMeasurer.score(c, "en", table, "B1", setOf("zzz"), 120_000)!!
    println(s)
    check("levelKnows B1 = 6/8", s.levelKnowsPct == 75)
    check("youKnow with learned word = 7/8", s.youKnowPct == 88)
    check("new words per min", Math.abs(s.newWordsPerMin - 0.5) < 1e-9)
    check("approx en", s.approx && !s.bandApprox)
    println(ClipMeasurer.tagLine(s, "B1"))
    check("unknown declared", ClipMeasurer.score(c, "en", table, "N5", emptySet(), 1000) == null)
    // Thai uses rank bands
    val th = Levels.parse(sequenceOf("กิน\t100\t","ข้าว\t700\t","อร่อย\t2000\t"))
    val tc = ClipCounts(4, mapOf("กิน" to 2, "ข้าว" to 1, "โอ" to 1))
    val ts = ClipMeasurer.score(tc, "th", th, "A2", emptySet(), 60_000)!!
    check("thai bands A2 knows 3/4", ts.levelKnowsPct == 75 && ts.bandApprox)
    println(ClipMeasurer.tagLine(ts, "A2"))
    // ranking
    val sc = ClipLevels.scale("en")
    data class V(val n: String, val k: Double, val npm: Double)
    fun mk(v: V) = ClipScore(0, "A1", v.k, v.k, 0, 0, v.npm, true, false)
    val vs = listOf(V("a", .99, 1.0), V("b", .90, 1.0), V("c", .86, 1.0), V("d", .70, 1.0))
    check("rank B1 band order", ClipRanking.rank(vs, 2, sc, ::mk).map { it.n } == listOf("b", "c", "a", "d"))
    val tv = listOf(V("x", .99, 2.0), V("y", .90, 9.0), V("z", .80, 50.0))
    check("rank C2 by new words/min", ClipRanking.rank(tv, 5, sc, ::mk).map { it.n } == listOf("y", "x", "z"))
    // dedupe
    val rolled = listOf("so today we are going", "so today we are going to cook", "to cook some rice tonight", "to cook some rice tonight")
    println(ClipMeasurer.dedupeRolling(rolled))
    check("dedupe rolling", ClipMeasurer.dedupeRolling(rolled).joinToString(" ") == "so today we are going to cook some rice tonight")
    check("dedupe keeps normal", ClipMeasurer.dedupeRolling(listOf("hello there", "how are you")) == listOf("hello there", "how are you"))
    // prompt + parse
    val p = ClipPrompt.build("English", "C2", "en", listOf("ephemeral", "walk"), 4)
    check("prompt has words", "ephemeral, walk" in p && "documentaries" in p)
    check("pool", ClipPrompt.poolSize(6) == 12 && ClipPrompt.poolSize(30) == 40 && ClipPrompt.queryCount(6) == 4)
    val ans = "```json\n[{\"query\":\"interview about memory\",\"words\":[\"Ephemeral\",\"invented\"]},{\"query\":\"interview about memory\",\"words\":[]},{\"query\":\"debate on cities\",\"words\":[\"walk\"]}]\n```"
    val q = ClipPrompt.parse(ans, listOf("ephemeral", "walk"), 5)
    check("parse", q.size == 2 && q[0].words == listOf("ephemeral") && q[1].words == listOf("walk"))
    check("parse garbage", ClipPrompt.parse("sorry", emptyList(), 3).isEmpty())
    // ClipPlan
    check("videoKey forms match", ClipPlan.videoKey("https://youtu.be/abcDEF12345") == "abcDEF12345" && ClipPlan.videoKey("https://www.youtube.com/watch?v=abcDEF12345&t=5") == "abcDEF12345" && ClipPlan.videoKey("https://youtube.com/shorts/abcDEF12345") == "abcDEF12345")
    data class H(val u: String, val d: Long)
    val hs = listOf(H("https://www.youtube.com/watch?v=aaaaaa1", 10_000), H("https://www.youtube.com/watch?v=bbbbbb2", 60_000), H("https://www.youtube.com/watch?v=bbbbbb2", 60_000),
        H("https://www.youtube.com/watch?v=cccccc3", 900_000), H("https://www.youtube.com/watch?v=dddddd4", 120_000), H("https://www.youtube.com/watch?v=eeeeee5", 200_000))
    val cut = ClipPlan.cheapCut(hs, { it.u }, { it.d }, setOf("dddddd4"), 10)
    check("cheapCut length, dupes, history", cut.map { ClipPlan.videoKey(it.u) } == listOf("bbbbbb2", "eeeeee5"))
    check("cheapCut limit", ClipPlan.cheapCut(hs, { it.u }, { it.d }, emptySet(), 1).size == 1)
    check("interleave", ClipPlan.interleave(listOf(listOf(1, 2, 3), listOf(4), listOf(5, 6))) == listOf(1, 4, 5, 2, 6, 3))
    val k = ClipPlan.cacheKey("en", "C2", 6)
    check("cache fresh", ClipPlan.cacheValid(1000, 1000 + ClipPlan.CACHE_MS, k, k))
    check("cache old", !ClipPlan.cacheValid(1000, 1001 + ClipPlan.CACHE_MS, k, k))
    check("cache other level", !ClipPlan.cacheValid(1000, 2000, k, ClipPlan.cacheKey("en", "C1", 6)))
    check("cache clock went back", !ClipPlan.cacheValid(5000, 1000, k, k))
    check("rowWords", ClipPlan.rowWords(listOf(listOf("a", "b"), emptyList(), listOf("b", "c"))) == listOf("a", "b", "c"))
    val en = ClipLevels.scale("en")
    check("libraryLevels mid", ClipPlan.libraryLevels(en, 2) == listOf("A2", "B1", "B2"))
    check("libraryLevels bottom", ClipPlan.libraryLevels(en, 0) == listOf("A1", "A2"))
    check("libraryLevels top two", ClipPlan.libraryLevels(en, 4) == listOf("B2", "C1", "C2") && ClipPlan.libraryLevels(en, 5) == listOf("C1", "C2"))
    check("libraryLevels bad index", ClipPlan.libraryLevels(en, -1).isEmpty() && ClipPlan.libraryLevels(en, 9).isEmpty())
    check("indexPlan delay in range", IndexPlan.delayMs(0.0) == IndexPlan.DELAY_MIN_MS && IndexPlan.delayMs(1.0) == IndexPlan.DELAY_MAX_MS && IndexPlan.delayMs(5.0) == IndexPlan.DELAY_MAX_MS)
    check("indexPlan caps", IndexPlan.remainingToday(40) == 60 && IndexPlan.remainingToday(500) == 0 && IndexPlan.roomInLibrary(IndexPlan.LIBRARY_CAP) == 0)
    val good = ClipScore(2, "B1", 0.9, 0.9, 90, 90, 1.0, false, false)
    check("atLevel band", ClipRanking.atLevel(good, 2, en) && !ClipRanking.atLevel(good.copy(youKnow = 0.97), 2, en) && !ClipRanking.atLevel(good.copy(youKnow = 0.80), 2, en))
    check("atLevel top two", ClipRanking.atLevel(good.copy(youKnow = 0.97), 5, en) && !ClipRanking.atLevel(good.copy(youKnow = 0.80), 5, en))
    check("isGone content not available", ClipPlan.isGone(AgeRestrictedContentException("x")) && ClipPlan.isGone(RuntimeException("wrapped", ContentNotAvailableException("y"))))
    check("isGone no stream", ClipPlan.isGone(Exception("No playable stream found for this video")))
    check("isGone network is not gone", !ClipPlan.isGone(java.io.IOException("timeout")) && !ClipPlan.isGone(Exception("YouTube asked for a captcha")))
    check("indexPlan prune below cap", IndexPlan.PRUNE_TO < IndexPlan.LIBRARY_CAP)
}

open class ContentNotAvailableException(m: String) : Exception(m)
class AgeRestrictedContentException(m: String) : ContentNotAvailableException(m)
