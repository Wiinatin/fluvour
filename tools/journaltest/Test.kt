import app.fluvour.ai.*
fun main() {
    val d = TextDiff.changed(listOf("I","go","to","market"), listOf("I","went","to","the","market"))
    println(d.toList())
    check(d.toList() == listOf(false,true,false,true,false))
    val p = JournalPrompt.parse("```json\n{\"corrected\":\"I went to the market.\",\"fixes\":[{\"wrong\":\"go\",\"right\":\"went\",\"why\":\"past\"}],\"newWords\":[{\"word\":\"market\",\"meaning\":\"ตลาด\",\"example\":\"x\"},{\"word\":\"market\",\"meaning\":\"dup\"}],}\n```")!!
    check(p.corrected.startsWith("I went") && p.fixes.size == 1 && p.words.size == 1)
    check(JournalPrompt.parse("nope") == null)
    println("ALL OK")
}
