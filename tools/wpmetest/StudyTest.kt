import app.fluvour.reading.*
import app.fluvour.srs.CardState
import app.fluvour.srs.Phase
import app.fluvour.srs.Rating
import app.fluvour.srs.Scheduler

var sfails = 0
fun scheck(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) sfails++ }

private fun e(form: String, ch: Int = 1) = WpmeItem('E', "E:" + WpmeData.norm(form), form, "m", ch, emptyList())
private fun w(form: String, ch: Int = 1) = WpmeItem('W', "W:" + WpmeData.norm(form), form, "m", ch, emptyList())
private fun lk(word: String, vararg parts: String) = WpmeLink("W:$word", parts.toList(), "")
private fun prog(phase: Phase, due: Long? = null, at: Long = 1L) = WpmeProgress(CardState(phase = phase, stability = 1.0, difficulty = 5.0, dueMillis = due), at)

/** Word Power study (step 7): the unlock rule, the session order and the progress file. Pure, with the real FSRS scheduler. */
fun studyTests() {
    val items = listOf(e("anthropos"), e("misein (mis-, miso-)"), e("ego"), e("logos"), e("philos"), w("misanthrope"), w("egoist"), w("philanthropy"), w("anthropology"), w("zebra"))
    val links = mapOf(
        "W:misanthrope" to lk("misanthrope", "mis-", "anthrop", "-e"),
        "W:egoist" to WpmeLink("W:egoist", emptyList(), ""),            // plain: tried, no clean breakdown
        "W:philanthropy" to lk("philanthropy", "phil", "anthrop", "-y"),
        "W:anthropology" to lk("anthropology", "anthrop", "-ology"),
    )
    val unlock = WpmeUnlock(items)
    fun keys(l: List<WpmeItem>) = l.map { it.key }

    // ---- unlock rule
    scheck("study: a part matches the element it is a stem of", unlock.elementsFor("anthrop").map { it.key } == listOf("E:anthropos") && unlock.elementsFor("mis-").map { it.key } == listOf("E:misein (mis-, miso-)"))
    scheck("study: a common affix matches no element and never blocks", unlock.elementsFor("-ology").isEmpty() && unlock.elementsFor("-e").isEmpty() && unlock.elementsFor("re-").isEmpty())
    scheck("study: no link or a plain link has no rule", unlock.ready(null, emptySet()) && unlock.ready(links["W:egoist"], emptySet()))
    scheck("study: a linked word waits for its element parts", !unlock.ready(links["W:misanthrope"], emptySet()) && !unlock.ready(links["W:misanthrope"], setOf("E:anthropos")))
    scheck("study: a linked word is ready when every element part is known", unlock.ready(links["W:misanthrope"], setOf("E:anthropos", "E:misein (mis-, miso-)")))
    scheck("study: affix-only parts do not need anything", unlock.ready(lk("x", "re-", "-ing"), emptySet()))
    val two = WpmeUnlock(listOf(e("logos"), e("logon (log-)")))
    scheck("study: a part matching two elements needs only one of them", two.elementsFor("log").size == 2 && two.ready(lk("y", "log"), setOf("E:logon (log-)")) && !two.ready(lk("y", "log"), emptySet()))

    // ---- what is offered
    val none = emptyMap<String, WpmeProgress>()
    val f0 = WpmeStudyPlan.fresh(items, links, unlock, none)
    scheck("study: at the start elements come first, then only words with no rule", keys(f0).take(5) == listOf("E:anthropos", "E:misein (mis-, miso-)", "E:ego", "E:logos", "E:philos") && keys(f0).drop(5).toSet() == setOf("W:egoist", "W:zebra"))
    scheck("study: linked words are held back and counted as waiting", f0.none { it.key == "W:misanthrope" } && WpmeStudyPlan.waiting(items, links, unlock, none) == 3)
    val p1 = mapOf("E:anthropos" to prog(Phase.REVIEW), "E:misein (mis-, miso-)" to prog(Phase.LEARNING))
    scheck("study: a word in the learning phase does not unlock its words", WpmeStudyPlan.fresh(items, links, unlock, p1).none { it.key == "W:misanthrope" })
    scheck("study: a word with all parts known unlocks (anthropology needs only anthropos)", WpmeStudyPlan.fresh(items, links, unlock, p1).any { it.key == "W:anthropology" })
    val p2 = p1 + ("E:misein (mis-, miso-)" to prog(Phase.REVIEW))
    scheck("study: misanthrope unlocks once mis- and anthrop are in review", WpmeStudyPlan.fresh(items, links, unlock, p2).any { it.key == "W:misanthrope" })
    scheck("study: philanthropy stays held back while philos is unknown, then unlocks", WpmeStudyPlan.fresh(items, links, unlock, p2).none { it.key == "W:philanthropy" } && WpmeStudyPlan.fresh(items, links, unlock, p2 + ("E:philos" to prog(Phase.REVIEW))).any { it.key == "W:philanthropy" })
    scheck("study: started items are not offered again", WpmeStudyPlan.fresh(items, links, unlock, p1).none { it.key in p1 })

    // ---- session
    val now = 1_000_000L
    val pd = mapOf("E:ego" to prog(Phase.REVIEW, due = now - 10), "E:logos" to prog(Phase.REVIEW, due = now - 500), "E:philos" to prog(Phase.REVIEW, due = now + 99_999))
    val isDue = { s: CardState -> s.dueMillis != null && s.dueMillis!! <= now }
    val s1 = WpmeStudyPlan.session(items, links, unlock, pd, 2, isDue)
    scheck("study: due items first, soonest first, then the new items up to the limit", keys(s1).take(2) == listOf("E:logos", "E:ego") && s1.size == 4 && s1[2].key == "E:anthropos" && s1[3].key == "E:misein (mis-, miso-)")
    scheck("study: a limit of 0 gives only the due items", keys(WpmeStudyPlan.session(items, links, unlock, pd, 0, isDue)) == listOf("E:logos", "E:ego") && WpmeStudyPlan.session(items, links, unlock, pd, -3, isDue).size == 2)
    scheck("study: an item that left the deck is ignored", WpmeStudyPlan.due(items, mapOf("E:gone" to prog(Phase.REVIEW, due = 1L)), isDue).isEmpty())

    // ---- rating with the real scheduler
    val sch = Scheduler()
    var st = CardState()
    for (i in 0 until 4) st = sch.review(st, Rating.GOOD, now + i * 86_400_000L * 3, 0L)
    scheck("study: good ratings graduate an item to review, which counts as known", st.phase == Phase.REVIEW && WpmeStudyPlan.known(mapOf("E:x" to WpmeProgress(st, now), "E:y" to prog(Phase.LEARNING))) == setOf("E:x"))

    // ---- progress file
    val m = linkedMapOf("E:ego" to WpmeProgress(st, 5L), "W:zebra" to prog(Phase.LEARNING, due = 7L, at = 9L))
    val back = WpmeProgressData.decode(WpmeProgressData.encode(m).lineSequence())
    scheck("study: progress file round trip", back == m)
    scheck("study: progress decode skips bad lines", WpmeProgressData.decode(sequenceOf("", "junk", "E:a\tNOPE\t1\t1\t0\t\t\t0\t0\t0\t1")).isEmpty())
}
