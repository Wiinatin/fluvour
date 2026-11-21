import app.fluvour.reading.*
import app.fluvour.reading.articles.*
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) System.exit(1) }
fun main() {
    val tsv = (1..30).map { "w$it\t$it\tA1" } + listOf("w31\t31\tB1", "w32\t32\tB1", "w33\t33\tC2")
    val table = Levels.parse(tsv.asSequence())
    val easy = (1..30).joinToString(" ") { "w$it" }
    check("easy text is A1", ArticleLevel.measure(easy, "en", table).code == "A1")
    val hard = (1..30).joinToString(" ") { "w$it" } + " w31 w32 w33 w33 w33 w33 w33 w33 w33 w33"
    check("hard text is C2", ArticleLevel.measure(hard, "en", table).code == "C2")
    check("token total", ArticleLevel.measure(easy, "en", table).tokenTotal == 30)
    check("too short gives no level", ArticleLevel.measure("w1 w2 w3", "en", table).code == null)
    check("no table gives no level", ArticleLevel.measure(easy, "en", null).code == null)
    check("empty text", ArticleLevel.measure("", "en", table).code == null)
    check("en label", ArticleLevel.label("en", "B2") == "B2")
    check("ja label", ArticleLevel.label("ja", "N3") == "N3")
    check("th label has tilde", ArticleLevel.label("th", "A2") == "~A2")
    check("id label has tilde", ArticleLevel.label("id", "C1") == "~C1")
    check("unknown code", ArticleLevel.label("en", "N3") == null && ArticleLevel.label("en", null) == null)
    println("ALL OK")
}
