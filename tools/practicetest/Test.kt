import app.fluvour.reading.HeisigSearch
import app.fluvour.reading.Tier
import app.fluvour.reading.tierOf
import app.fluvour.srs.Phase

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) fails++ }

data class E(val ch: String, val frame: Int, val keyword: String)

val deck = listOf(
    E("一", 1, "one"), E("二", 2, "two"), E("三", 3, "three"), E("四", 4, "four"), E("六", 6, "six"),
    E("口", 11, "mouth"), E("日", 12, "day"), E("田", 13, "rice field"), E("目", 14, "eye"),
    E("十", 60, "ten"), E("古", 61, "old"), E("𠮟", 600, "scold")
)

fun f(q: String) = HeisigSearch.filter(deck, q, { it.ch }, { it.frame }, { it.keyword })
fun frames(q: String) = f(q).map { it.frame }

fun main() {
    // empty query
    check("empty returns all", f("") == deck)
    check("blank returns all", f("   ") == deck)
    check("empty keeps order", f("").map { it.frame } == deck.map { it.frame })

    // keyword
    check("keyword contains, 'ou' finds four and mouth only", frames("ou") == listOf(4, 11))
    check("keyword case-insensitive", frames("SIX") == listOf(6))
    check("keyword mixed case", frames("MoUtH") == listOf(11))
    check("keyword with space", frames("rice field") == listOf(13))
    check("keyword part of a two-word keyword", frames("field") == listOf(13))
    check("keyword surrounding spaces trimmed", frames("  eye ") == listOf(14))
    check("keyword that matches nothing", f("zzz").isEmpty())

    // kanji itself
    check("single kanji", frames("日") == listOf(12))
    check("pasted word finds each kanji", frames("日本") == listOf(12))
    check("pasted word with two deck kanji", frames("目田") == listOf(13, 14))
    check("kanji outside the deck gives no match", f("本").isEmpty())
    check("kanji beyond the basic plane", frames("𠮟") == listOf(600))

    // frame number
    check("exact frame", frames("11") == listOf(11))
    check("frame prefix 1 finds 1, 11, 12, 13, 14", frames("1") == listOf(1, 11, 12, 13, 14))
    check("frame prefix 6 finds 6, 60, 61, 600", frames("6") == listOf(6, 60, 61, 600))
    check("frame prefix 60", frames("60") == listOf(60, 600))
    check("frame is not matched in the middle ('0' finds 60 and 600 only by prefix: none)", f("0").isEmpty())
    check("hash prefix", frames("#6") == listOf(6, 60, 61, 600))
    check("full-width digits", frames("６") == listOf(6, 60, 61, 600))
    check("full-width hash and digits", frames("＃１１") == listOf(11))
    check("frame above the deck", f("9999").isEmpty())
    check("a bare hash matches nothing", f("#").isEmpty())

    // combined and order
    check("results keep deck order", frames("o") == listOf(1, 2, 4, 11, 61, 600).filter { it in frames("o") } && frames("o") == frames("o").sorted())
    check("keyword and number can both match without duplicates", frames("1").distinct() == frames("1"))
    check("the input list is not changed", deck.size == 12 && deck.first().ch == "一")

    // a number inside a keyword still works as text
    val withDigit = listOf(E("丁", 5, "street 5"), E("七", 7, "seven"))
    check("keyword containing a digit", HeisigSearch.filter(withDigit, "street 5", { it.ch }, { it.frame }, { it.keyword }).map { it.frame } == listOf(5))

    // empty list
    check("empty deck gives empty result", HeisigSearch.filter(emptyList<E>(), "x", { it.ch }, { it.frame }, { it.keyword }).isEmpty())

    // maturity tiers
    check("never studied is plain", tierOf(false, Phase.NEW, 0.0) == Tier.PLAIN)
    check("NEW phase is plain even if started flag is set", tierOf(true, Phase.NEW, 0.0) == Tier.PLAIN)
    check("learning is copper whatever the stability", tierOf(true, Phase.LEARNING, 400.0) == Tier.COPPER)
    check("relearning is copper", tierOf(true, Phase.RELEARNING, 100.0) == Tier.COPPER)
    check("review under 7 days is copper", tierOf(true, Phase.REVIEW, 6.99) == Tier.COPPER)
    check("7 days is silver", tierOf(true, Phase.REVIEW, 7.0) == Tier.SILVER)
    check("20.9 days is silver", tierOf(true, Phase.REVIEW, 20.9) == Tier.SILVER)
    check("21 days is gold", tierOf(true, Phase.REVIEW, 21.0) == Tier.GOLD)
    check("60 days is jade", tierOf(true, Phase.REVIEW, 60.0) == Tier.JADE)
    check("179 days is jade", tierOf(true, Phase.REVIEW, 179.0) == Tier.JADE)
    check("180 days is radiant", tierOf(true, Phase.REVIEW, 180.0) == Tier.RADIANT)
    check("tiers are ordered", Tier.PLAIN < Tier.COPPER && Tier.JADE < Tier.RADIANT)

    println(if (fails == 0) "ALL OK" else "$fails FAILED")
    if (fails != 0) kotlin.system.exitProcess(1)
}
