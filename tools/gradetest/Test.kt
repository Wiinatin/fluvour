import app.fluvour.path.*

var failed = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) failed++ }

fun main() {
    val items = listOf(
        GradeItem(1, "私は学生です", listOf("I am a student"), "i'm a student"),
        GradeItem(2, "猫", listOf("cat"), "dog"),
        GradeItem(3, "水", listOf("water"), ""),
    )
    val p = GradeLogic.prompt(items)
    check("prompt lists every item", p.contains("1. Question") && p.contains("2. Question") && p.contains("3. Question"))
    check("prompt carries answers", p.contains("Learner wrote: dog") && p.contains("Accepted: I am a student"))
    check("prompt asks for JSON only", p.contains("JSON only"))

    val good = """{"results":[{"n":1,"ok":true,"why":"Same meaning."},{"n":2,"ok":false,"why":"Dog is not cat."},{"n":3,"ok":false,"why":"Empty."}]}"""
    val v = GradeLogic.parse(items, good)
    check("parse reads 3 verdicts", v.size == 3)
    check("parse keeps ok and why", v[1]?.ok == true && v[2]?.ok == false && v[2]?.why == "Dog is not cat.")
    check("parse takes fenced reply", GradeLogic.parse(items, "```json\n$good\n```").size == 3)
    check("parse takes trailing comma", GradeLogic.parse(items, """{"results":[{"n":1,"ok":true,"why":"x",},]}""").size == 1)
    check("parse takes n as string", GradeLogic.parse(items, """{"results":[{"n":"2","ok":true,"why":""}]}""")[2]?.ok == true)
    check("parse ignores unknown n", GradeLogic.parse(items, """{"results":[{"n":9,"ok":true,"why":""}]}""").isEmpty())
    check("parse ignores duplicate n", GradeLogic.parse(items, """{"results":[{"n":1,"ok":true,"why":"a"},{"n":1,"ok":false,"why":"b"}]}""")[1]?.why == "a")
    check("parse ignores missing ok", GradeLogic.parse(items, """{"results":[{"n":1,"why":"a"}]}""").isEmpty())
    check("parse of junk is empty", GradeLogic.parse(items, "sorry").isEmpty() && GradeLogic.parse(items, "{\"results\":5}").isEmpty())
    check("parse of cut-off reply is empty", GradeLogic.parse(items, """{"results":[{"n":1,"ok":tr""").isEmpty())

    val partial = GradeLogic.parse(items, """{"results":[{"n":1,"ok":false,"why":"No."}]}""")
    val m = GradeLogic.merge(items, partial) { it.n == 2 }
    check("merge prefers the AI", m[1]?.ok == false && m[1]?.why == "No.")
    check("merge falls back to local", m[2]?.ok == true && m[3]?.ok == false)
    check("merge with no AI is all local", GradeLogic.merge(items, emptyMap()) { true }.values.all { it.ok })

    println(if (failed == 0) "ALL OK" else "$failed FAILED")
    if (failed != 0) kotlin.system.exitProcess(1)
}
