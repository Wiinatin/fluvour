import app.fluvour.path.CurriculumParser
import java.io.File

private var failed = 0
private fun ok(name: String, cond: Boolean) {
    if (cond) println("ok $name") else { println("FAIL $name"); failed++ }
}

fun main(args: Array<String>) {
    val file = File(args.getOrNull(0) ?: "app/src/main/assets/curriculum/ja-genki1.tsv")
    val path = CurriculumParser.parse(file.readText())
    ok("file parses", path != null)
    if (path == null) { println("ALL FAILED"); System.exit(1) }
    val units = path!!.units

    ok("path meta", path.id == "genki1" && path.title == "Genki I" && path.languageCode == "ja" && path.level == "N5")
    ok("at least 40 units", units.size >= 40)
    ok("orders run 1..N", units.map { it.order } == (1..units.size).toList())
    ok("ids unique", units.map { it.id }.toSet().size == units.size)
    ok("ids are g1-NN", units.all { Regex("g1-\\d\\d").matches(it.id) })

    val before = HashSet<String>()
    var prereqOk = true
    for (u in units) { if (u.prerequisites.any { it !in before }) prereqOk = false; before.add(u.id) }
    ok("prerequisites point at earlier units", prereqOk)
    ok("every unit after the first has a prerequisite or is a root", units.count { it.prerequisites.isEmpty() } <= 3)

    ok("6 to 12 words per unit", units.all { it.words.size in 6..12 })
    ok("1 or 2 grammar points per unit", units.all { it.grammar.size in 1..2 })
    ok("every word has reading and gloss", units.all { u -> u.words.all { it.word.isNotBlank() && it.reading.isNotBlank() && it.gloss.isNotBlank() } })
    ok("title, can-do and minutes set", units.all { it.title.isNotBlank() && it.canDo.isNotBlank() && it.minutes in 10..40 })
    val keys = units.flatMap { u -> u.words.map { it.word to it.reading } }
    ok("no word listed in two units", keys.toSet().size == keys.size)
    ok("no em dashes in UI text", units.none { u -> (listOf(u.title, u.canDo) + u.grammar + u.words.map { it.gloss }).any { it.contains('—') } })

    ok("bad lines are skipped", CurriculumParser.parse("@path\tx\tX\tja\tN5\nnot a unit\ng\tz\tT\tN5\t5\tc\t\tg\tw;r;g\n")!!.units.isEmpty())
    ok("no @path line gives null", CurriculumParser.parse("g1-01\t1\tT\tN5\t5\tc\t\tg\tw;r;g\n") == null)
    val one = CurriculumParser.parse("@path\tx\tX\tja\tN5\ng-1\t1\tT\tN5\t5\tcan\t\tg1|g2\ta;b;c|d;e;f\n")!!.units.single()
    ok("one unit round trip", one.words.size == 2 && one.words[1].gloss == "f" && one.grammar == listOf("g1", "g2") && one.prerequisites.isEmpty())

    println("units=${units.size} words=${keys.size}")
    if (failed == 0) println("ALL OK") else { println("$failed FAILED"); System.exit(1) }
}
