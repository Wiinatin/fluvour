import app.fluvour.audio.ModelLayout
import java.io.File
var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) fails++ }
fun main() {
    val amy = File("vits-piper-en_US-amy-low").list()!!.toList()
    val a = ModelLayout.detect(amy)
    check("piper: VITS kind", a?.kind == ModelLayout.Kind.VITS)
    check("piper: model file", a?.model == "en_US-amy-low.onnx")
    check("piper: espeak data", a?.dataDir == "espeak-ng-data")
    check("piper: no lexicon", a?.lexicons?.isEmpty() == true)
    val kok = File("kokoro-int8-multi-lang-v1_0").list()!!.toList()
    val k = ModelLayout.detect(kok)
    check("kokoro: kind", k?.kind == ModelLayout.Kind.KOKORO)
    check("kokoro: model file", k?.model == "model.int8.onnx")
    check("kokoro: voices", k?.voices == "voices.bin")
    check("kokoro: lexicons us-en + zh, not gb-en", k?.lexicons == listOf("lexicon-us-en.txt", "lexicon-zh.txt"))
    check("kokoro: three rule fsts", k?.ruleFsts == listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst"))
    check("kokoro: dict and espeak data", k?.dictDir == "dict" && k.dataDir == "espeak-ng-data")
    val mms = ModelLayout.detect(listOf("model.onnx", "tokens.txt", "lexicon.txt"))
    check("mms-style: VITS with lexicon, no espeak", mms?.kind == ModelLayout.Kind.VITS && mms.lexicons == listOf("lexicon.txt") && mms.dataDir == "")
    check("no tokens: not a pack", ModelLayout.detect(listOf("model.onnx")) == null)
    check("no model: not a pack", ModelLayout.detect(listOf("tokens.txt", "say.txt")) == null)
    check("empty folder: not a pack", ModelLayout.detect(emptyList()) == null)
    check("test folder with say.txt and sid.txt still detected", ModelLayout.detect(amy + listOf("say.txt", "sid.txt")) != null)
    if (fails > 0) { println("$fails failed"); System.exit(1) }
}
