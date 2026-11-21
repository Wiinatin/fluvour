import app.fluvour.reading.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) fails++ }
fun near(a: Float, b: Float) = Math.abs(a - b) < 0.0001f

fun main() {
    check("catalog id", LibraryMath.catalogId("gutenberg", 1342L) == "gutenberg:1342")
    check("overall first chapter half", near(LibraryMath.overall(0, 10, 0.5f), 0.05f))
    check("overall middle", near(LibraryMath.overall(4, 10, 0.5f), 0.45f))
    check("overall last chapter done", near(LibraryMath.overall(9, 10, 1f), 1f))
    check("overall clamps percent", near(LibraryMath.overall(0, 4, 3f), 0.25f))
    check("overall negative percent", near(LibraryMath.overall(1, 4, -1f), 0.25f))
    check("overall zero chapters safe", near(LibraryMath.overall(0, 0, 0.5f), 0.5f))
    check("overall never above 1", LibraryMath.overall(20, 10, 1f) == 1f)

    val rows = listOf(
        BookProgressRow("a", "a1", 0.9f, 100, 5, 0),
        BookProgressRow("a", "a2", 0.2f, 300, 5, 1),
        BookProgressRow("b", "b1", 0.5f, 200, 2, 0),
        BookProgressRow("c", "c1", 0.1f, 400, 3, 0),
        BookProgressRow("c", "c2", 0.3f, 400, 3, 1)
    )
    val l = LibraryMath.continueList(rows)
    check("one entry per book", l.map { it.bookId }.toSet() == setOf("a", "b", "c") && l.size == 3)
    check("newest first", l.map { it.bookId } == listOf("c", "a", "b"))
    check("latest chapter picked", l.first { it.bookId == "a" }.articleId == "a2")
    check("tie goes to later chapter", l.first { it.bookId == "c" }.articleId == "c2")
    check("book overall", near(l.first { it.bookId == "a" }.overall, 0.24f))
    check("empty list", LibraryMath.continueList(emptyList()).isEmpty())
    check("safe file name", LibraryMath.safeFileName("gutenberg:1342") == "gutenberg_1342")
    check("safe file name path chars", LibraryMath.safeFileName("file:../a b") == "file_.._a_b")
    println(if (fails == 0) "ALL OK" else "$fails FAILED")
}
