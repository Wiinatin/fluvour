package app.fluvour.path

/** One typed answer to grade. [n] is its number in the batch; [question] is what the learner saw; [expected] are the accepted answers. */
data class GradeItem(val n: Int, val question: String, val expected: List<String>, val answer: String)

/** The grader's verdict on one typed answer, with a one-line reason. */
data class Verdict(val ok: Boolean, val why: String)

/**
 * Typed-answer grading by the AI (pure Kotlin; tools/gradetest runs it on a plain JVM).
 * One request grades one answer (drills) or all typed answers of a test. The model only judges meaning; the app keeps the score.
 */
object GradeLogic {
    fun prompt(items: List<GradeItem>): String {
        val list = items.joinToString("\n") {
            "${it.n}. Question: ${it.question.replace('\n', ' ')} | Accepted: ${it.expected.joinToString(" / ")} | Learner wrote: ${it.answer.replace('\n', ' ')}"
        }
        return """
You grade short answers from an English-speaking learner of Japanese. For each line, decide if the learner's answer has the same meaning as an accepted answer.
Rules: accept synonyms, different but correct English wording, missing articles, small typos and punctuation. Reject a wrong meaning, a missing key word, or an empty answer. Do not be harsher than a kind teacher.
Reply with JSON only, no other text, in this shape:
{"results":[{"n":1,"ok":true,"why":"one short sentence"}]}
Give exactly one result per line, with the same n.

$list
""".trim()
    }

    /** Verdicts by n. Lines the reply does not cover, or malformed ones, are left out so the caller can fall back to the local check. */
    fun parse(items: List<GradeItem>, reply: String): Map<Int, Verdict> {
        val json = MiniJson.extract(reply)?.let { MiniJson.parse(it) } as? Map<*, *> ?: return emptyMap()
        val rows = json["results"] as? List<*> ?: return emptyMap()
        val wanted = items.map { it.n }.toSet()
        val out = LinkedHashMap<Int, Verdict>()
        for (r in rows) {
            val m = r as? Map<*, *> ?: continue
            val n = (m["n"] as? Number)?.toInt() ?: (m["n"] as? String)?.trim()?.toIntOrNull() ?: continue
            val ok = m["ok"] as? Boolean ?: continue
            if (n !in wanted || n in out) continue
            out[n] = Verdict(ok, (m["why"] as? String).orEmpty().trim())
        }
        return out
    }

    /** Verdict for every item: the AI's where it gave one, else the local check. */
    fun merge(items: List<GradeItem>, ai: Map<Int, Verdict>, local: (GradeItem) -> Boolean): Map<Int, Verdict> =
        items.associate { it.n to (ai[it.n] ?: Verdict(local(it), "")) }
}
