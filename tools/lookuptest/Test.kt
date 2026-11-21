import app.fluvour.reading.CardText
import app.fluvour.reading.LookupFields
import app.fluvour.srs.*
import java.io.File

var failed = false
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) failed = true }

fun main(args: Array<String>) {
    // Arabic plain form
    val vowelled = "كَتَبَ"; val plain = "كتب"
    check("plainArabic strips harakat", plainArabic(vowelled) == plain)
    check("plainArabic strips shadda, sukun, dagger alef, tatweel", plainArabic("ٱللَّهِ") == "ٱلله" && plainArabic("الرَّحْمَٰنِ") == "الرحمن" && plainArabic("كـتب") == "كتب")
    check("plainArabic keeps different alef forms apart", plainArabic("أَب") != plainArabic("اب"))
    check("plainArabic leaves Latin alone", plainArabic("tidak") == "tidak")
    check("sameWord ar: vowelled = plain", sameWord("ar", vowelled, plain) && sameWord("ar", " كَتَبَ ", "كتب"))
    check("sameWord ar: different words differ", !sameWord("ar", "كَتَبَ", "كَلَبَ"))
    check("sameWord ms: case and spaces only", sameWord("ms", " Tidak ", "tidak") && !sameWord("ms", "tidak", "tak"))
    check("sameWord ja unchanged", sameWord("ja", "食べる", "食べる") && !sameWord("ja", "食べる", "飲む"))

    // Malay answer
    val ms = "1. Standard: tidak | Sarawak: indak\n2. not\n3. Sarawak example: Aku indak tauk.\n4. Negation word."
    val f = LookupFields.parse(ms)!!
    check("ms standard", f.word == "tidak")
    check("ms sarawak", f.variantWord == "indak")
    check("ms sarawak example", f.variantExample == "Aku indak tauk.")
    check("ms: no root/lemma", f.root == null && f.lemma == null)
    check("ms: both forms differ so a Sarawak facet is made", needsSarawakFacet(f.word!!, f.variantWord))
    val same = LookupFields.parse("1. Standard: makan | Sarawak: same\n2. to eat\n3. Usage note.")!!
    check("ms same-in-Sarawak gives no variant", same.variantWord == null && same.word == "makan")
    check("ms same-in-Sarawak gives no facet", !needsSarawakFacet(same.word!!, same.variantWord))
    check("ms identical spelling gives no facet", !needsSarawakFacet("makan", "makan") && !needsSarawakFacet("Makan", "makan"))
    check("ms markdown bold and quotes tolerated", LookupFields.parse("**1. Standard:** \"tidak\" | **Sarawak:** \u201Cindak\u201D")?.variantWord == "indak")
    check("ms CardText still finds the meaning on line 2", CardText.fromExplanation(ms).meaning == "not")
    check("ms notes keep the whole explanation", CardText.fromExplanation(ms).notes == ms)

    // Arabic answer
    val ar = "1. Vowelled: كَتَبَ | Root: ك-ت-ب | Lemma: كَتَبَ\n2. he wrote\n3. Past tense, third person masculine."
    val a = LookupFields.parse(ar)!!
    check("ar vowelled word", a.word == "كَتَبَ")
    check("ar root", a.root == "ك-ت-ب")
    check("ar lemma", a.lemma == "كَتَبَ")
    check("ar has no Sarawak form", a.variantWord == null && a.variantExample == null)
    check("ar CardText meaning", CardText.fromExplanation(ar).meaning == "he wrote")
    check("vowelled card matches plain tapped word", sameWord("ar", a.word!!, "كتب"))

    // Other languages / plain answers unchanged
    check("old 3-line answer gives null", LookupFields.parse("1. 食べる (たべる)\n2. to eat\n3. Ichidan verb.") == null)
    check("empty or null gives null", LookupFields.parse("") == null && LookupFields.parse(null) == null)
    check("a plain sentence with a colon gives null", LookupFields.parse("Note: this is fine") == null)

    // Prompts
    val mp = LookupFields.malayPrompt("Malay (Malaysia), not Indonesian", "English", "tak", "Aku tak tahu.")
    check("ms prompt: Malaysia not Indonesian + both forms", "not Indonesian" in mp && "Standard:" in mp && "Sarawak:" in mp && "Sarawak example:" in mp)
    val ap = LookupFields.arabicPrompt("Quranic (Classical) Arabic, fully vowelled", "English", "كتب", "كتب")
    check("ar prompt: vowelled, root, lemma", "Vowelled:" in ap && "Root:" in ap && "Lemma:" in ap && "vowel marks" in ap)

    val root = args.getOrNull(0)
    if (root != null) {
        val base = "$root/app/src/main/kotlin/app/fluvour"
        val lookup = File("$base/reading/LookupRepository.kt").readText()
        check("lookup prompt uses aiName", "Languages.byCode(lang).aiName" in lookup && "malayPrompt" in lookup && "arabicPrompt" in lookup)
        check("lookup keeps the old prompt for other languages", "Dictionary form of the word (if different)" in lookup)
        check("dictionary tries the plain Arabic form", "plainArabic(word)" in File("$base/reading/DictionaryClient.kt").readText())
        val repo = File("$base/data/Repositories.kt").readText()
        check("save stores the 4 new fields", "variantWord = variantWord, variantExample = variantExample, root = root, lemma = lemma" in repo)
        check("new card makes the Sarawak facet only when needed", "if (needsSarawakFacet(card.word, card.variantWord)) list.add(FacetSeed.sarawak(card, now))" in repo)
        check("edits keep the Sarawak facet in step", "else syncSarawakFacet(card)" in repo)
        check("Sarawak facets are queued since step 5", "private val sarawakStudyReady = true" in repo && "!sarawakStudyReady" in repo)
        check("speaking duplicate check ignores vowel marks", "findWordPlain(lang, word) != null" in repo)
        check("sync pull makes the Sarawak facet", "FacetSeed.sarawak(r, 0L)" in File("$base/sync/SyncRepository.kt").readText())
        val editor = File("$base/ui/screens/CardEditorScreen.kt").readText()
        check("editor duplicate uses sameWord", "app.fluvour.srs.sameWord(lang, it.word, word)" in editor)
        check("editor has Sarawak and Arabic fields", "\"Sarawak word\"" in editor && "\"Root\"" in editor && "\"Lemma\"" in editor)
        check("OCR add-card passes the fields", "LookupFields.parse(m.explanation?.text)" in File("$base/ocr/OcrLookupViewModel.kt").readText())
        check("draft carries the fields", "LookupFields.parse(meaning)" in File("$base/ui/AppViewModel.kt").readText())
        check("review front cannot crash on SARAWAK", "runCatching { Front.valueOf(it.kind.name) }" in File("$base/ui/ReviewViewModel.kt").readText())
    }
    println(if (failed) "FAILED" else "ALL OK")
}
