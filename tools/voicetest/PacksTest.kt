import app.fluvour.audio.VoicePacks
var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) fails++ }
val SHA_A = "a".repeat(64)
val SHA_B = "B".repeat(64)
val INDEX = "https://github.com/me/fluvour/releases/download/voicepacks/packs.json"
fun main() {
    val json = """{"version":1,"packs":[
      {"id":"kokoro-multi","name":"Kokoro","engine":"Kokoro","version":"1","url":"kokoro-multi.zip","size":137000000,"sha256":"$SHA_A",
       "languages":{"en":3,"es":28,"fr":30,"ja":37,"zh":45}},
      {"id":"piper-de","name":"Piper German","engine":"piper","url":"https://example.com/de.zip","size":63000000,"sha256":"$SHA_B","languages":{"de":0}},
      {"id":"piper-ja","engine":"piper","url":"piper-ja.zip","size":60000000,"sha256":"$SHA_A","languages":{"ja":0}},
      {"id":"bad-hash","engine":"piper","url":"x.zip","size":1,"sha256":"nothex","languages":{"vi":0}},
      {"id":"bad-size","engine":"piper","url":"x.zip","size":0,"sha256":"$SHA_A","languages":{"vi":0}},
      {"id":"bad-url","engine":"piper","url":"../x.zip","size":5,"sha256":"$SHA_A","languages":{"vi":0}},
      {"id":"no-langs","engine":"piper","url":"x.zip","size":5,"sha256":"$SHA_A","languages":{}},
      {"id":"kokoro-multi","engine":"kokoro","url":"dup.zip","size":5,"sha256":"$SHA_A","languages":{"en":1}},
      "junk"
    ]}"""
    val c = VoicePacks.parse(json, INDEX)
    check("three good packs, six skipped", c.packs.map { it.id } == listOf("kokoro-multi", "piper-de", "piper-ja") && c.skipped == 6)
    val k = c.byId("kokoro-multi")!!
    check("file-name url resolved next to packs.json", k.url == "https://github.com/me/fluvour/releases/download/voicepacks/kokoro-multi.zip")
    check("full url kept", c.byId("piper-de")!!.url == "https://example.com/de.zip")
    check("engine lower-cased, hash lower-cased", k.engine == "kokoro" && c.byId("piper-de")!!.sha256 == "b".repeat(64))
    check("size and version", k.size == 137000000L && k.version == "1")
    check("missing version falls back to hash prefix", c.byId("piper-ja")!!.version == "a".repeat(12))
    check("missing name falls back to id", c.byId("piper-ja")!!.name == "piper-ja")
    check("one Kokoro pack covers five languages", k.languages.keys.toList() == listOf("en", "es", "fr", "ja", "zh"))
    check("speaker per language", c.speaker(k, "ja") == 37 && c.speaker(k, "es") == 28 && c.speaker(k, "de") == null)
    check("region tags normalised", c.speaker(k, "ja-JP") == 37 && c.speaker(k, "EN_us") == 3)
    check("packsFor ja lists both, file order", c.packsFor("ja").map { it.id } == listOf("kokoro-multi", "piper-ja"))
    check("choose: nothing installed", c.choose("ja", emptySet()) == null)
    check("choose: only the later pack installed", c.choose("ja", setOf("piper-ja"))?.id == "piper-ja")
    check("choose: file order wins when both installed", c.choose("ja", setOf("piper-ja", "kokoro-multi"))?.id == "kokoro-multi")
    check("choose: language with no pack", c.choose("th", setOf("kokoro-multi")) == null)
    check("languages list, no repeats", c.languages() == listOf("en", "es", "fr", "ja", "zh", "de"))
    check("sample sentence per language", VoicePacks.sample("th").isNotEmpty() && VoicePacks.sample("ja-JP") == VoicePacks.SAMPLES["ja"] && VoicePacks.sample("xx") == VoicePacks.SAMPLES["en"])
    check("all ten app languages have a sample", listOf("ja","th","zh","ko","en","es","fr","de","vi","id").all { it in VoicePacks.SAMPLES })
    check("index url from extractor url", VoicePacks.indexUrl("https://github.com/me/fluvour/releases/download/extractor-latest/latest.json") == INDEX)
    check("index url empty for other shapes", VoicePacks.indexUrl("") == "" && VoicePacks.indexUrl("https://example.com/latest.json") == "")
    check("relative url with no index base is skipped", VoicePacks.parse("""{"packs":[{"id":"a","engine":"x","url":"a.zip","size":1,"sha256":"$SHA_A","languages":{"en":0}}]}""", "").packs.isEmpty())
    var threw = false
    try { VoicePacks.parse("[1,2]", INDEX) } catch (e: IllegalArgumentException) { threw = true }
    check("not an object throws", threw)
    threw = false
    try { VoicePacks.parse("""{"nope":1}""", INDEX) } catch (e: IllegalArgumentException) { threw = true }
    check("no packs list throws", threw)
    threw = false
    try { VoicePacks.parse("<html>", INDEX) } catch (e: Exception) { threw = true }
    check("garbage throws", threw)
    check("empty list is fine", VoicePacks.parse("""{"packs":[]}""", INDEX).packs.isEmpty())
    if (fails > 0) { println("$fails failed"); System.exit(1) }
}
