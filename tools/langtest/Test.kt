import app.fluvour.language.*
import java.io.File

var failed = false
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) failed = true }

fun main(args: Array<String>) {
    val ar = Languages.byCode("ar"); val ms = Languages.byCode("ms")
    check("ar row exists (not the default)", ar.code == "ar")
    check("ms row exists (not the default)", ms.code == "ms")
    check("ar is right to left", ar.rtl)
    check("ms is left to right", !ms.rtl && !Languages.byCode("ja").rtl)
    check("ar has no OCR, others keep it", !ar.ocr && ms.ocr && Languages.all.filter { it.code != "ar" }.all { it.ocr })
    check("ms phone voice is ms-MY", ms.deviceTag == "ms-MY" && Languages.deviceTag("ms") == "ms-MY")
    check("ms-MY passes through", Languages.deviceTag("ms-MY") == "ms-MY")
    check("ms is never Indonesian", !ms.deviceTag.startsWith("id") && "not Indonesian" in ms.aiName)
    check("ms prompt name says Malaysia", "Malay (Malaysia)" in ms.aiName)
    check("ar prompt says Quranic + vowelled", "Quranic" in ar.aiName && "vowelled" in ar.aiName)
    check("other languages unchanged", Languages.deviceTag("ja") == "ja" && Languages.deviceTag("id") == "id" && Languages.byCode("id").aiName == "Indonesian")
    check("old rows still there in order", Languages.all.take(10).map { it.code } == listOf("ja", "th", "zh", "ko", "en", "es", "fr", "de", "vi", "id"))
    check("default still Japanese", Languages.default.code == "ja")
    check("codes unique", Languages.all.map { it.code }.toSet().size == Languages.all.size)
    val root = args.getOrNull(0)
    if (root != null) {
        val base = "$root/app/src/main/kotlin/app/fluvour"
        check("recognition maps ms-MY and ar", File("$base/media/SpeechListener.kt").readText().let { "\"ms\" -> \"ms-MY\"" in it && "\"ar\" -> \"ar-SA\"" in it })
        check("tts uses deviceTag + aiName", File("$base/audio/TtsManager.kt").readText().let { "Languages.deviceTag(langTag)" in it && ".aiName" in it })
        check("ocr refuses languages without ocr", "!Languages.byCode(lang).ocr" in File("$base/ocr/OcrEngine.kt").readText())
    }
    println(if (failed) "FAILED" else "ALL OK")
}
