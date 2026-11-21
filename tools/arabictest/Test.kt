import app.fluvour.reading.Coverage
import app.fluvour.reading.Token
import app.fluvour.srs.*

var failed = false
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) failed = true }

fun main() {
    val sent = "كَتَبَ الْوَلَدُ الدَّرْسَ"
    val r = findForms("ar", sent, "الولد")
    check("findForms ar: plain word inside vowelled sentence", r.size == 1 && sent.substring(r[0].first, r[0].last + 1) == "الْوَلَدُ")
    val r2 = findForms("ar", sent, "الْوَلَدُ")
    check("findForms ar: vowelled word finds the same span", r2 == r)
    check("findForms ar: trailing marks stay inside the range", sent.substring(findForms("ar", sent, "كتب")[0].let { it.first..it.last }.let { it.first }, findForms("ar", sent, "كتب")[0].last + 1) == "كَتَبَ")
    check("findForms ar: no match", findForms("ar", sent, "قلم").isEmpty())
    check("findForms ar: blank word", findForms("ar", sent, " ").isEmpty())
    val en = "The cat saw the Cat"
    check("findForms en: ignores case, two hits", findForms("en", en, "cat").size == 2)
    check("findForms en: ranges point at original text", findForms("en", en, "cat").map { en.substring(it.first, it.last + 1) } == listOf("cat", "Cat"))

    val text = "كَتَبَ الْوَلَدُ"
    val tokens = listOf(Token(0, 4, true), Token(4, 5, false), Token(5, text.length, true))
    val cov = Coverage.compute(listOf(text to tokens), setOf("كتب"), "ar")
    check("coverage ar: plain known word matches vowelled text", cov.known == 1 && cov.total == 2)
    val cov2 = Coverage.compute(listOf("Cat dog" to listOf(Token(0, 3, true), Token(3, 4, false), Token(4, 7, true))), setOf("cat"), "en")
    check("coverage en unchanged", cov2.known == 1 && cov2.total == 2)
    println(if (failed) "FAILED" else "ALL OK")
    if (failed) kotlin.system.exitProcess(1)
}
