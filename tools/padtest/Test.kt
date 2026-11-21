import app.fluvour.reading.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) fails++ }
fun near(a: Float, b: Float) = Math.abs(a - b) < 0.0001f
fun pt(x: Float, y: Float) = PadPoint(x, y)

fun main() {
    val empty = PadDrawing()
    check("starts empty", empty.isEmpty && empty.strokeCount == 0 && empty.all().isEmpty())

    // one stroke
    var d = empty.begin(pt(0.1f, 0.1f)).add(pt(0.2f, 0.2f)).add(pt(0.3f, 0.3f))
    check("live stroke counts", d.strokeCount == 1 && d.live.size == 3 && d.done.isEmpty() && !d.isEmpty)
    d = d.end()
    check("end moves live to done", d.done.size == 1 && d.live.isEmpty() && d.strokeCount == 1)
    check("end twice is harmless", d.end() == d)

    // two strokes keep order
    d = d.begin(pt(0.5f, 0.5f)).add(pt(0.6f, 0.5f)).end()
    check("two strokes in order", d.done.size == 2 && d.done[0].first() == pt(0.1f, 0.1f) && d.done[1].first() == pt(0.5f, 0.5f))

    // begin closes an open stroke
    val open = empty.begin(pt(0.1f, 0.1f)).add(pt(0.4f, 0.4f)).begin(pt(0.8f, 0.8f))
    check("begin closes previous stroke", open.done.size == 1 && open.live.size == 1 && open.strokeCount == 2)

    // tap = dot
    val dot = empty.begin(pt(0.5f, 0.5f)).end()
    check("tap stays as a dot", dot.done.size == 1 && dot.done[0].size == 1)

    // tiny moves skipped
    val tiny = empty.begin(pt(0.5f, 0.5f)).add(pt(0.5005f, 0.5f)).add(pt(0.5f, 0.5008f))
    check("tiny moves skipped", tiny.live.size == 1)
    val far = empty.begin(pt(0.5f, 0.5f)).add(pt(0.51f, 0.5f))
    check("normal step kept", far.live.size == 2)

    // add without begin
    check("add without begin ignored", empty.add(pt(0.3f, 0.3f)) == empty)

    // clamp
    val out = empty.begin(pt(-0.5f, 1.7f)).add(pt(2f, -3f))
    check("points clamped into 0..1", out.live.all { it.x in 0f..1f && it.y in 0f..1f } && out.live[0] == pt(0f, 1f) && out.live[1] == pt(1f, 0f))

    // undo
    var u = empty.begin(pt(0.1f, 0.1f)).add(pt(0.2f, 0.2f)).end().begin(pt(0.5f, 0.5f)).add(pt(0.6f, 0.6f)).end()
    u = u.undo()
    check("undo removes last stroke", u.done.size == 1 && u.done[0].first() == pt(0.1f, 0.1f))
    u = u.undo()
    check("undo to empty", u.isEmpty)
    check("undo on empty is harmless", u.undo() == u)
    val u2 = empty.begin(pt(0.1f, 0.1f)).add(pt(0.2f, 0.2f)).end().begin(pt(0.5f, 0.5f)).add(pt(0.6f, 0.6f)).undo()
    check("undo drops the stroke under the finger first", u2.live.isEmpty() && u2.done.size == 1)

    // clear
    check("clear empties everything", d.begin(pt(0.9f, 0.9f)).clear().isEmpty)

    // all() includes live
    val mix = empty.begin(pt(0.1f, 0.1f)).add(pt(0.3f, 0.3f)).end().begin(pt(0.6f, 0.6f)).add(pt(0.7f, 0.7f))
    check("all includes finished and live", mix.all().size == 2 && mix.all()[1].first() == pt(0.6f, 0.6f))

    // immutability
    val before = empty.begin(pt(0.1f, 0.1f)).end()
    val after = before.begin(pt(0.5f, 0.5f)).end()
    check("old drawing not changed by later edits", before.done.size == 1 && after.done.size == 2)

    // caps
    var many = empty
    for (i in 0 until PadMath.MAX_STROKES + 10) many = many.begin(pt(0.1f, 0.1f)).end()
    check("stroke count capped", many.done.size == PadMath.MAX_STROKES)
    var longS = empty.begin(pt(0f, 0f))
    for (i in 1..PadMath.MAX_POINTS + 50) longS = longS.add(pt((i % 250) / 250f, if (i % 2 == 0) 0.2f else 0.8f))
    check("points per stroke capped", longS.live.size == PadMath.MAX_POINTS)

    // smoothing
    val line = listOf(pt(0.1f, 0.1f), pt(0.3f, 0.3f), pt(0.6f, 0.6f), pt(0.9f, 0.9f))
    val s = PadMath.smooth(line)
    check("smooth keeps first point", s.first() == line.first())
    check("smooth keeps last point", s.last() == line.last())
    check("smooth adds points", s.size > line.size)
    check("smooth keeps a straight line straight", s.all { near(it.x, it.y) })
    check("smooth stays inside 0..1", s.all { it.x in 0f..1f && it.y in 0f..1f })
    check("smooth of one point unchanged", PadMath.smooth(listOf(pt(0.4f, 0.4f))) == listOf(pt(0.4f, 0.4f)))
    check("smooth of two points unchanged", PadMath.smooth(listOf(pt(0.1f, 0.1f), pt(0.9f, 0.2f))).size == 2)
    check("smooth of empty list", PadMath.smooth(emptyList()).isEmpty())
    val corner = listOf(pt(0f, 0f), pt(0.5f, 0f), pt(0.5f, 0.5f))
    val sc = PadMath.smooth(corner, 1)
    check("smooth cuts a corner", sc.none { near(it.x, 0.5f) && near(it.y, 0f) })
    check("smooth rounds=0 unchanged", PadMath.smooth(line, 0) == line)

    println(if (fails == 0) "ALL OK" else "$fails FAILED")
}
