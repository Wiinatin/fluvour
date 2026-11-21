import app.fluvour.reading.*
import app.fluvour.srs.Phase
import java.io.File

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) fails++ }

fun main(args: Array<String>) {
    val all = File(args[0]).useLines { SarawakPackData.parse(it) }
    // pack format
    check("pack has 721 cards", all.size == 721)
    check("day order sorted and unique", all.map { it.dayOrder } == all.map { it.dayOrder }.sorted() && all.map { it.dayOrder }.toSet().size == all.size)
    check("keys unique", all.map { it.key }.toSet().size == all.size)
    check("day 1 starts Kamek, Kitak, Pegi", all.take(3).map { it.sarawak } == listOf("Kamek", "Kitak", "Pegi"))
    check("every card has fields", all.all { SarawakPackData.cardFields(it) != null })
    check("header and short rows skipped", SarawakPackData.parse(sequenceOf("standard_malay\tsarawak\tenglish\texample\tsource\ttier\tday_order", "a\tb\tc", "x\tY\tz\t\ts\t1\t")).isEmpty())

    // card fields
    val kamek = SarawakPackData.cardFields(all[0])!!
    check("Saya; Aku -> word Saya, variant Kamek, notes Also: Aku", kamek.word == "Saya" && kamek.variantWord == "Kamek" && kamek.notes == "Also: Aku" && kamek.meaning == "I; Me")
    check("example goes to the Sarawak example", kamek.variantExample == "Kamek dari Kuching." && kamek.example == null)
    check("standard word differs so Sarawak facet is made", app.fluvour.srs.needsSarawakFacet(kamek.word, kamek.variantWord))
    val noStd = all.first { it.standard.isBlank() }
    val f2 = SarawakPackData.cardFields(noStd)!!
    check("no standard Malay: card is the Sarawak word, no variant", f2.word == noStd.sarawak && f2.variantWord == null)
    check("no standard Malay: no Sarawak facet", !app.fluvour.srs.needsSarawakFacet(f2.word, f2.variantWord))
    check("no english falls back to standard word", SarawakPackData.cardFields(SarawakEntry("k", "Pergi", "Pegi", "", "", null, 1))!!.meaning == "Pergi")
    check("nothing to put on a card gives null", SarawakPackData.cardFields(SarawakEntry("k", "", "Pegi", "", "", null, 1)) == null)

    // example meaning (8th column)
    check("kamek example has its meaning", all[0].exampleMeaning.isNotBlank() && kamek.variantExampleMeaning == all[0].exampleMeaning)
    check("27 examples all have a meaning", all.filter { it.example.isNotBlank() }.let { it.size == 27 && it.all { e -> e.exampleMeaning.isNotBlank() } })
    check("old 7-column row still parses, meaning empty", SarawakPackData.parse(sequenceOf("Pergi\tPegi\tGo\tKitak pegi?\ts\t1\t1")).single().exampleMeaning == "")
    check("no example means no meaning on the card", SarawakPackData.cardFields(SarawakEntry("k", "Pergi", "Pegi", "Go", "", null, 1, "stray"))!!.variantExampleMeaning == null)
    check("no standard Malay card keeps the meaning with its plain example", f2.example == noStd.example.ifBlank { null } && f2.variantExampleMeaning == noStd.exampleMeaning.ifBlank { null })
    val dengan = all.first { it.sarawak == "Dengan" }
    check("Dengan (no standard Malay, has an example): example and meaning both on the plain card", SarawakPackData.cardFields(dengan)!!.let { it.example == dengan.example && it.variantExampleMeaning == dengan.exampleMeaning && it.variantExampleMeaning != null })

    // mastery ring and tick
    check("new card: 0%, no tick", SarawakMasteryRules.of(Phase.NEW, 0.0) == SarawakMastery(0, false))
    check("learning card never ticks, capped at 99%", SarawakMasteryRules.of(Phase.LEARNING, 40.0) == SarawakMastery(99, false))
    check("review, 10.5 days: 50%", SarawakMasteryRules.of(Phase.REVIEW, 10.5) == SarawakMastery(50, false))
    check("review, 20.9 days: 99% and no tick yet", SarawakMasteryRules.of(Phase.REVIEW, 20.9) == SarawakMastery(99, false))
    check("review, 21 days: tick (same as Gold)", SarawakMasteryRules.of(Phase.REVIEW, 21.0) == SarawakMastery(100, true))
    check("relearning after a lapse loses the tick", !SarawakMasteryRules.of(Phase.RELEARNING, 30.0).mastered)

    // AI suggestion for a new standard card (prompt and reading of the answer)
    val pr = SarawakSuggestPrompt.build("duduk", "to sit")
    check("prompt names the word, its meaning and asks for SAME when unsure", pr.contains("\"duduk\"") && pr.contains("to sit") && pr.contains("SAME"))
    val ok = SarawakSuggestPrompt.parse("Sarawak: dudok\nExample: Dudok lok kitak.\nMeaning: Please sit down first.", "duduk")!!
    check("three-line answer read", ok.variantWord == "dudok" && ok.variantExample == "Dudok lok kitak." && ok.exampleMeaning == "Please sit down first.")
    check("markdown, numbering and quotes are cleaned", SarawakSuggestPrompt.parse("1. **Sarawak:** \"dudok\"\n2. **Example:** Dudok lok.\n3. **Meaning:** Sit first.", "duduk")!!.let { it.variantWord == "dudok" && it.variantExample == "Dudok lok." && it.exampleMeaning == "Sit first." })
    check("one-line answer read", SarawakSuggestPrompt.parse("Sarawak: pegi | Example: Kitak nak pegi sine? | Meaning: Where are you going?", "pergi")!!.variantWord == "pegi")
    check("SAME gives no suggestion", SarawakSuggestPrompt.parse("Sarawak: SAME", "makan") == null)
    check("same word back gives no suggestion", SarawakSuggestPrompt.parse("Sarawak: Makan\nExample: Kitak makan?\nMeaning: Do you eat?", "makan") == null)
    check("word only: no example, no meaning", SarawakSuggestPrompt.parse("Sarawak: dudok", "duduk")!!.let { it.variantExample == null && it.exampleMeaning == null })
    check("meaning without an example is dropped", SarawakSuggestPrompt.parse("Sarawak: dudok\nMeaning: Sit.", "duduk")!!.exampleMeaning == null)
    check("junk or empty answer gives no suggestion", SarawakSuggestPrompt.parse("I am sorry, I cannot help.", "duduk") == null && SarawakSuggestPrompt.parse(null, "duduk") == null && SarawakSuggestPrompt.parse("", "duduk") == null)
    check("suggested word makes a Sarawak facet", app.fluvour.srs.needsSarawakFacet("duduk", ok.variantWord))

    // unlock rule
    val d1 = SarawakPackData.nextToUnlock(all, emptySet(), 10, 0)
    check("day 1 unlocks 10 in order", d1.size == 10 && d1 == all.take(10))
    val un = d1.map { it.key }.toSet()
    check("same day, already 10 today: nothing more", SarawakPackData.nextToUnlock(all, un, 10, 10).isEmpty())
    check("setting raised to 15 same day: 5 more", SarawakPackData.nextToUnlock(all, un, 15, 10) == all.subList(10, 15))
    check("next day continues after day 1", SarawakPackData.nextToUnlock(all, un, 10, 0) == all.subList(10, 20))
    check("skipped days do not pile up", SarawakPackData.nextToUnlock(all, un, 10, 0).size == 10)
    check("0 per day pauses", SarawakPackData.nextToUnlock(all, emptySet(), 0, 0).isEmpty())
    check("over today's limit never negative", SarawakPackData.nextToUnlock(all, emptySet(), 5, 9).isEmpty())
    check("learn more ignores the daily limit", SarawakPackData.more(all, un, 7) == all.subList(10, 17))
    check("end of deck: fewer than asked", SarawakPackData.nextToUnlock(all, all.dropLast(3).map { it.key }.toSet(), 10, 0).size == 3)
    check("73 days at 10 a day", generateSequence(emptySet<String>()) { u -> u + SarawakPackData.nextToUnlock(all, u, 10, 0).map { it.key } }.takeWhile { it.size < all.size }.count() == 73)

    // duplicate skip (same loop as SarawakRepository.unlock, with a fake deck)
    val deck = mutableSetOf("saya", "pergi")
    val prog = LinkedHashMap<String, SarawakProgress>()
    var added = 0; var skipped = 0
    for (e in SarawakPackData.nextToUnlock(all, emptySet(), 10, 0)) {
        val f = SarawakPackData.cardFields(e)
        if (f == null || f.word.lowercase() in deck) { prog[e.key] = SarawakProgress(1L, SarawakStatus.SKIPPED, ""); skipped++ }
        else { deck.add(f.word.lowercase()); prog[e.key] = SarawakProgress(1L, SarawakStatus.ADDED, "id${e.key}"); added++ }
    }
    check("deck already had Saya (Kamek) and Pergi (Pegi): 2 skipped, 8 added", skipped == 2 && added == 8 && prog.size == 10)
    check("skipped words still count as unlocked (progress moves on)", SarawakPackData.nextToUnlock(all, prog.keys, 10, 10).isEmpty() && SarawakPackData.nextToUnlock(all, prog.keys, 10, 0).first() == all[10])

    // progress file
    val back = SarawakPackData.decode(SarawakPackData.encode(prog).lineSequence())
    check("progress file round trip", back == prog)
    check("bad progress lines ignored", SarawakPackData.decode(sequenceOf("", "a\t1\tNOPE\tx", "b\t2\tADDED\tc1")).keys == setOf("b"))

    println(if (fails == 0) "ALL OK" else "FAILURES: $fails")
}
