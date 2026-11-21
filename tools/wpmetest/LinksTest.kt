import app.fluvour.ai.*
import app.fluvour.reading.*

var kfails = 0
fun kcheck(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) kfails++ }

private fun el(form: String, ch: Int = 1) = WpmeItem('E', "E:" + WpmeData.norm(form), form, "m", ch, emptyList())
private fun wd(form: String, ch: Int = 1) = WpmeItem('W', "W:" + WpmeData.norm(form), form, "m", ch, emptyList())
private fun task(form: String) = WpmeLinkTask(wd(form), "2. others: the passage about $form.")
private fun raw(gloss: String = "g", vararg parts: String) = WpmeRawLink(parts.toList(), gloss)

/** Word links (step 6): prompt, reply reading, the checker and the cache file. All synthetic, the real deck and book are not needed. */
fun linksTests() {
    val elements = listOf(el("anthropos"), el("misein (mis-, miso-)"), el("logos"), el("ego"), el("-itis"), el("re-"), el("mercy"), el("grace"), el("phobos (phob-)"))
    fun verdict(word: String, r: WpmeRawLink?) = WpmeLinksPrompt.check(task(word), r, elements)
    fun linked(v: WpmeVerdict) = v is WpmeVerdict.Linked
    fun reason(v: WpmeVerdict) = (v as? WpmeVerdict.Rejected)?.reason.orEmpty()

    // ---- good replies
    val good = verdict("misanthrope", raw("hate + mankind", "mis-", "anthrop", "-e"))
    kcheck("links: a good link is kept", linked(good) && (good as WpmeVerdict.Linked).link.parts == listOf("mis-", "anthrop", "-e") && good.link.gloss == "hate + mankind" && good.link.key == "W:misanthrope")
    kcheck("links: stem change of a root (anthropos to anthrop)", linked(verdict("anthropology", raw("g", "anthrop", "-ology"))))
    kcheck("links: a joining vowel between two parts may be left out", linked(verdict("anthropophobia", raw("g", "anthrop", "phob", "-ia"))))
    kcheck("links: y to i spelling change (mercy)", linked(verdict("merciless", raw("g", "mercy", "-less"))))
    kcheck("links: dropped e (grace)", linked(verdict("gracious", raw("g", "grace", "-ious"))))
    kcheck("links: uppercase and stray spaces are cleaned", linked(verdict("misanthrope", raw("g", " Mis- ", "ANTHROP", "-E"))))
    kcheck("links: assimilated prefix is allowed (in- as il-)", linked(verdict("illogical", raw("g", "il-", "log", "-ic", "-al"))))

    // ---- plain and missing
    kcheck("links: empty parts means plain, accepted", verdict("misanthrope", raw("g")) === WpmeVerdict.Plain)
    kcheck("links: no entry is rejected", reason(verdict("misanthrope", null)).contains("no answer"))
    kcheck("links: a phrase is plain, never sent", verdict("all of one mind", raw("g", "mis-")) === WpmeVerdict.Plain && !WpmeLinksPrompt.isLinkable(wd("oval, ovoid")))

    // ---- wrong replies
    kcheck("links: a part not in the word is rejected", reason(verdict("misanthrope", raw("g", "mis-", "anthrop", "-ic"))).contains("not in the word"))
    kcheck("links: a part outside the lists is rejected", reason(verdict("misanthrope", raw("g", "mis-", "anthrop", "zzz-"))).contains("allowed lists"))
    kcheck("links: a missing part is rejected (un + able leaves comfort out)", reason(verdict("uncomfortable", raw("g", "un-", "-able"))).isNotEmpty() && !linked(verdict("uncomfortable", raw("g", "un-", "-able"))))
    kcheck("links: parts in the wrong order are rejected", !linked(verdict("misanthrope", raw("g", "anthrop", "mis-", "-e"))))
    kcheck("links: leaving out the start of the word is rejected (egoistic without ego)", !linked(verdict("egoistic", raw("g", "-ist", "-ic"))))
    kcheck("links: only standard affixes, no element, is rejected", reason(verdict("rewrite", raw("g", "re-", "-e"))).isNotEmpty() && !linked(verdict("rewrite", raw("g", "re-", "-e"))))
    kcheck("links: too many parts are rejected", !linked(verdict("misanthrope", raw("g", "m", "i", "s", "a", "n", "t", "h"))))
    kcheck("links: a part without letters is rejected", reason(verdict("misanthrope", raw("g", "mis-", "---", "anthrop"))).contains("no letters"))
    kcheck("links: the word is not hidden inside one part and the tail is limited", !linked(verdict("misanthropically", raw("g", "mis-", "anthrop"))))

    // ---- word letters
    kcheck("links: wordLetters drops 'to ' and hyphens", WpmeLinksPrompt.wordLetters("to alter") == "alter" && WpmeLinksPrompt.wordLetters("self-centered") == "selfcentered" && WpmeLinksPrompt.wordLetters("a, b") == null)

    // ---- reply reading
    val txt = "Here you go:\n```json\n[{\"n\": 1, \"parts\": [\"mis-\", \"Anthrop\"], \"gloss\": \"hate +\\n mankind\"}, {\"n\": \"2\", \"parts\": []}, {\"n\": 3}, {\"parts\": [\"x\"]}, {\"n\": 1, \"parts\": [\"dup\"]}]\n```"
    val p = WpmeLinksPrompt.parse(txt)
    kcheck("links: parse reads fenced JSON with prose around it", p.size == 2 && p[1]?.parts == listOf("mis-", "anthrop") && p[1]?.gloss == "hate + mankind")
    kcheck("links: parse accepts a string number and keeps the first of a duplicate", p[2]?.parts == emptyList<String>() && p[1]?.parts?.contains("dup") == false)
    kcheck("links: parse never throws on garbage", WpmeLinksPrompt.parse("I cannot do that").isEmpty() && WpmeLinksPrompt.parse("[{broken").isEmpty() && WpmeLinksPrompt.parse("").isEmpty())

    // ---- whole reply
    val tasks = listOf(task("misanthrope"), task("anthropology"), task("egoistic"))
    val all = WpmeLinksPrompt.checkAll(tasks, mapOf(1 to raw("g", "mis-", "anthrop", "-e"), 3 to raw("g")), elements)
    kcheck("links: checkAll answers every word", all.keys == setOf(1, 2, 3) && linked(all.getValue(1)) && all.getValue(2) is WpmeVerdict.Rejected && all.getValue(3) === WpmeVerdict.Plain)

    // ---- prompt
    val prompt = WpmeLinksPrompt.build(tasks, elements, mapOf(2 to "part \"x\" is not in the word"))
    kcheck("links: prompt has numbered words, passages, the element list and the standard list", prompt.contains("1. misanthrope") && prompt.contains("3. egoistic") && prompt.contains("Passage: 2. others") && prompt.contains("anthropos, misein (mis-, miso-)") && prompt.contains("mis-") && prompt.contains("-ology"))
    kcheck("links: prompt carries the reason for a second try", prompt.contains("2. part \"x\" is not in the word") && !WpmeLinksPrompt.build(tasks, elements).contains("rejected"))
    kcheck("links: trimPassage joins the heading and cuts long text", WpmeLinksPrompt.trimPassage("2. others", "line one\nline two") == "2. others: line one line two" && WpmeLinksPrompt.trimPassage("", "x".repeat(900)).length <= WpmeLinksPrompt.PASSAGE_MAX + 3)

    // ---- file
    val links = listOf(WpmeLink("W:misanthrope", listOf("mis-", "anthrop", "-e"), "hate\t+ mankind"), WpmeLink("W:oddword", emptyList(), ""))
    val back = WpmeLinks.decode(WpmeLinks.encode(links).lineSequence())
    kcheck("links: file round trip keeps parts, plain words and a clean gloss", back.size == 2 && back["W:misanthrope"]?.parts == links[0].parts && back["W:misanthrope"]?.gloss == "hate + mankind" && back["W:oddword"]?.parts?.isEmpty() == true)
    kcheck("links: decode skips bad lines and the last line for a key wins", WpmeLinks.decode(sequenceOf("", "junk", "W:a\tx-|y\tone", "W:a\tz\ttwo")).let { it.size == 1 && it["W:a"]?.parts == listOf("z") })
    kcheck("links: core keeps letters only", WpmeLinks.core("-Ology-") == "ology")
    kcheck("links: the file holds no book text (only key, parts, short gloss)", WpmeLinks.encode(links).lines().filter { it.isNotEmpty() }.all { it.split('\t').size == 3 })
}
