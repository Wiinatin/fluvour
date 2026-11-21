import app.fluvour.path.*

private var failed = 0
private fun ok(name: String, cond: Boolean) {
    if (cond) println("ok $name") else { println("FAIL $name"); failed++ }
}

private fun unit(n: Int, vararg words: String) = CurriculumUnit(
    "u$n", n, "Unit $n", "N5", 20, "I can do $n.", if (n > 1) listOf("u${n - 1}") else emptyList(), listOf("g"),
    words.map { CurriculumWord(it, it, "x") })

fun main() {
    val p = CurriculumPath("p", "Path", "ja", "N5", (1..5).map { unit(it, "w$it", "v$it") })

    var rows = PathState.rows(p, emptySet())
    ok("fresh path: first unit current", rows[0].status == UnitStatus.CURRENT)
    ok("fresh path: rest locked", rows.drop(1).all { it.status == UnitStatus.LOCKED })

    rows = PathState.rows(p, setOf("u1", "u2"))
    ok("two graduated, third current", rows.map { it.status } == listOf(UnitStatus.GRADUATED, UnitStatus.GRADUATED, UnitStatus.CURRENT, UnitStatus.LOCKED, UnitStatus.LOCKED))
    ok("graduated count", PathState.graduatedCount(p, setOf("u1", "u2")) == 2)

    rows = PathState.rows(p, setOf("u2"))
    ok("a gap keeps the first ungraduated unit current", rows[0].status == UnitStatus.CURRENT && rows[1].status == UnitStatus.GRADUATED && rows[2].status == UnitStatus.LOCKED)

    rows = PathState.rows(p, (1..5).map { "u$it" }.toSet())
    ok("all graduated: no current, no locked", rows.all { it.status == UnitStatus.GRADUATED })

    rows = PathState.rows(p, setOf("u1", "u2"))
    ok("first locked line names the unit", PathState.lockedLine(rows, 3) == "Opens after Unit 3 is graduated")
    ok("later locked lines say locked", PathState.lockedLine(rows, 4) == "Locked")

    val p2 = CurriculumPath("q", "Path 2", "ja", "N4", listOf(unit(1, "a", "b")).map { it.copy(id = "q1") })
    ok("active path: first with work left", PathState.active(listOf(p, p2), setOf("u1")) == p)
    ok("active path: moves on when one is done", PathState.active(listOf(p, p2), (1..5).map { "u$it" }.toSet()) == p2)
    ok("active path: last one when all done", PathState.active(listOf(p, p2), (1..5).map { "u$it" }.toSet() + "q1") == p2)
    ok("active path: none without paths", PathState.active(emptyList(), emptySet()) == null)

    ok("badge: due wins", UnitNudge(linked = 5, due = 6).badge == "6 due")
    ok("badge: new when no word has a card", UnitNudge(linked = 0, due = 0).badge == "New")
    ok("badge: nothing when cards exist and none are due", UnitNudge(linked = 4, due = 0).badge == null)

    val u = unit(1, "w1", "w1", "v1")
    ok("unit words are distinct", UnitItems.words(u) == listOf("w1", "v1"))
    val cards = listOf("c1" to "w1", "c2" to "zzz", "c3" to "v1", "c4" to "w1")
    ok("cards link by word", UnitItems.cardIds(u, cards) == listOf("c1", "c3", "c4"))
    ok("no cards, no links", UnitItems.cardIds(u, emptyList()).isEmpty())

    println(if (failed == 0) "ALL OK" else "FAILED $failed")
    if (failed > 0) System.exit(1)
}
