import java.io.File
var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok  " else "FAIL ") + name); if (!ok) fails++ }
fun main(args: Array<String>) {
    val b = "${args[0]}/app/src/main/kotlin/app/fluvour"
    val vm = File("$b/ui/ReviewViewModel.kt").readText()
    val scr = File("$b/ui/screens/ReviewScreen.kt").readText()
    val repo = File("$b/data/Repositories.kt").readText()
    check("Front has SARAWAK", "enum class Front { WORD, MEANING, CLIP, SARAWAK }" in vm)
    check("StudyMode has SARAWAK", "SARAWAK(FacetKind.SARAWAK)" in vm)
    check("Sarawak facets are queued", "private val sarawakStudyReady = true" in repo)
    check("Sarawak front speaks the Sarawak word", "spokenWord(it.card, it.kind)" in vm && "kind == FacetKind.SARAWAK" in vm)
    check("mode row shows Standard/Sarawak only for Malay", "Fluvour.lang == \"ms\"" in scr && "\"Standard\"" in scr && "\"Sarawak\"" in scr)
    check("FrontBar names the Sarawak front", "Front.SARAWAK -> \"Front: Sarawak\"" in scr)
    check("Sarawak front is drawn", "SarawakFront(card, speak)" in scr && "private fun SarawakFront" in scr)
    check("back shows both forms", "Play standard word" in scr && "Play Sarawak word" in scr)
    check("Language icon imported", "icons.outlined.Language" in scr)
    check("old fronts untouched", "front == Front.MEANING -> MeaningFront(card)" in scr && "else -> FrontFace(card, speak)" in scr)
    println(if (fails == 0) "ALL OK" else "$fails FAILED")
}
