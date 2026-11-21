import app.fluvour.srs.*

var failed = false
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) failed = true }

fun main() {
    check("facet id is stable", facetId("c1", FacetKind.WORD) == facetId("c1", FacetKind.WORD))
    check("facet id differs by kind", facetId("c1", FacetKind.WORD) != facetId("c1", FacetKind.MEANING))
    check("facet id differs by card", facetId("c1", FacetKind.WORD) != facetId("c2", FacetKind.WORD))

    fun st(p: Phase, d: Int) = CardState(phase = p, scheduledDays = d)
    check("unlock: 2 passed and 3 days", FacetRules.shouldUnlockMeaning(st(Phase.REVIEW, 3), 2))
    check("no unlock: 1 passed", !FacetRules.shouldUnlockMeaning(st(Phase.REVIEW, 5), 1))
    check("no unlock: 2 days", !FacetRules.shouldUnlockMeaning(st(Phase.REVIEW, 2), 5))
    check("no unlock: still learning", !FacetRules.shouldUnlockMeaning(st(Phase.LEARNING, 9), 9))

    val a = QueueItem("fa", "A", FacetKind.WORD, Phase.REVIEW, 300)
    val b = QueueItem("fb", "B", FacetKind.CLIP, Phase.REVIEW, 100)
    val c = QueueItem("fc", "A", FacetKind.MEANING, Phase.REVIEW, 200)
    val n = QueueItem("fn", "N", FacetKind.WORD, Phase.NEW, null)
    val ordered = FacetQueue.mixedOrder(listOf(n, a, b, c))
    check("mixed: due order, new last", ordered.map { it.facetId } == listOf("fb", "fc", "fa", "fn"))
    val one = FacetQueue.buried(ordered, emptySet())
    check("burying: one facet per card", one.map { it.facetId } == listOf("fb", "fc", "fn"))
    check("burying: answered card leaves", FacetQueue.buried(ordered, setOf("A")).map { it.facetId } == listOf("fb", "fn"))

    println(if (failed) "FAILED" else "ALL OK")
}
