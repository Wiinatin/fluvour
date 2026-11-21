import app.fluvour.reading.*
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) System.exit(1) }
fun main() {
    // beats
    check("morae きょう=2", WordTiming.morae("キョウ") == 2)
    check("morae がっこう=4 (small tsu counts)", WordTiming.morae("ガッコウ") == 4)
    check("morae ラーメン=4", WordTiming.morae("ラーメン") == 4)
    check("morae ファイル=3", WordTiming.morae("ファイル") == 3)

    // real word times still win, and punctuation no longer breaks the match
    val t1 = "今日は、天気がいい。"
    val pieces = listOf(0L to "今日", 400L to "は", 600L to "天気", 1000L to "が", 1200L to "いい")
    check("matches although text has 、。", WordTiming.pieceSpans(t1, pieces)?.size == 5)
    check("source real", WordTiming.source(t1, pieces) == "real word times")
    check("mismatch still rejected", WordTiming.pieceSpans("全然違う文です", pieces) == null)
    val en = listOf(0L to "Hello", 500L to "there,", 900L to "my", 1200L to "friend.")
    check("english with punctuation in pieces", WordTiming.pieceSpans("Hello there, my friend.", en)?.size == 4)
    check("english pieces without punctuation vs text with", WordTiming.pieceSpans("Hello there, my friend.", listOf(0L to "Hello", 500L to "there", 900L to "my", 1200L to "friend"))?.size == 4)

    // estimate, Japanese: 今日(キョウ=2) は(1*.7) 、pause 天気(テンキ=3) が(.7) いい(2)。
    val toks = listOf(Token(0, 2, true, null, "キョウ"), Token(2, 3, true, null, "ハ"), Token(3, 4, false), Token(4, 6, true, null, "テンキ"), Token(6, 7, true, null, "ガ"), Token(7, 9, true, null, "イイ"), Token(9, 10, false))
    val ws = toks.filter { it.isWord }
    val st = WordTiming.starts(t1, emptyList(), toks, 0L, 4000L)!!
    println(st)
    check("starts count", st.size == 5 && st[0] == 0L)
    check("increasing", st.indices.all { i -> i == 0 || st[i] > st[i - 1] })
    // 2 beats for 今日, so word 2 starts at 2 beats; the 、 pause adds 2 beats before 天気
    val total = 2.0 + 0.7 + 2.0 + 3.0 + 0.7 + 2.0
    val dur = minOf(4000.0, total * 1000 / WordTiming.SLOWEST_BEATS_PER_SEC)
    check("pause pushes 天気 later than a no-pause split", Math.abs(st[2] - (dur * (2.0 + 0.7 + 2.0) / total)) < 2)
    // long lingering line: 20 s on screen for a 9-beat line ends at the speed cap, not 20 s
    val lazy = WordTiming.starts(t1, emptyList(), toks, 0L, 20000L)!!
    check("lingering line capped", lazy.last() < 5000)
    // fast line that is shorter than the cap keeps its own length
    val fast = WordTiming.starts(t1, emptyList(), toks, 0L, 1000L)!!
    check("short line uses full time", fast.last() < 1000 && fast.last() > 500)

    // English is unchanged: split by characters
    val et = "Hello there my friend"
    val etoks = listOf(Token(0, 5, true), Token(6, 11, true), Token(12, 14, true), Token(15, 21, true))
    val es = WordTiming.starts(et, emptyList(), etoks, 0L, 1900L)!!
    check("english by characters", es == listOf(0L, 527L, 1055L, 1266L))
    // Chinese-like: reading is pinyin, not kana -> old behaviour (reading length)
    val ct = "你好吗"
    val ctoks = listOf(Token(0, 2, true, null, "nihao"), Token(2, 3, true, null, "ma"))
    check("pinyin not treated as Japanese", WordTiming.starts(ct, emptyList(), ctoks, 0L, 7000L) == listOf(0L, 5000L))
    // no reading, kana + digits + latin
    val nt = "2024年にiPhoneを買った"
    val ntoks = listOf(Token(0, 4, true), Token(4, 5, true, null, "ネン"), Token(5, 6, true, null, "ニ"), Token(6, 12, true), Token(12, 13, true, null, "ヲ"), Token(13, 14, true, null, "カッ"), Token(14, 16, true, null, "タ"))
    val ns = WordTiming.starts(nt, emptyList(), ntoks, 0L, 5000L)!!
    check("no-reading words get beats (digits 4*2.5=10 before 年)", ns.size == 7 && ns[1] > ns[0] + 1500)
    check("no words / no duration", WordTiming.starts(t1, emptyList(), emptyList(), 0, 1000) == null && WordTiming.starts(t1, emptyList(), toks, 1000, 1000) == null)
}
