package app.fluvour.srs

import java.io.File
import kotlin.math.abs

/** Replays vectors generated from the official ts-fsrs 5.4.2 and compares every field after every review. */
object GoldenVectors {
    private fun config(name: String): SchedulerConfig = when (name) {
        "default" -> SchedulerConfig()
        "longterm" -> SchedulerConfig(enableShortTerm = false)
        "nosteps" -> SchedulerConfig(learningStepsMinutes = emptyList(), relearningStepsMinutes = emptyList())
        "onestep" -> SchedulerConfig(learningStepsMinutes = listOf(15))
        "threestep" -> SchedulerConfig(learningStepsMinutes = listOf(1, 10, 60))
        "ret85" -> SchedulerConfig(desiredRetention = 0.85)
        else -> error("unknown config $name")
    }

    /** Returns (reviews checked, mismatch messages). */
    fun run(file: File): Pair<Int, List<String>> {
        var scheduler = Scheduler(); var state = CardState(); var cfgName = ""
        var scenario = 0; var checked = 0
        val problems = ArrayList<String>()
        for (line in file.readLines()) {
            if (line.isBlank()) continue
            val p = line.split("|")
            if (p[0] == "S") { cfgName = p[1]; scheduler = Scheduler(config(cfgName)); state = CardState(); scenario++; continue }
            val rating = Rating.values()[p[1].toInt() - 1]
            val at = p[2].toLong()
            state = scheduler.review(state, rating, at)
            checked++
            val bad = ArrayList<String>()
            if (state.phase.name != p[3]) bad.add("phase ${state.phase} vs ${p[3]}")
            if (abs(state.stability - p[4].toDouble()) > 1e-6) bad.add("stability ${state.stability} vs ${p[4]}")
            if (abs(state.difficulty - p[5].toDouble()) > 1e-6) bad.add("difficulty ${state.difficulty} vs ${p[5]}")
            if (state.step != p[6].toInt()) bad.add("step ${state.step} vs ${p[6]}")
            if (state.scheduledDays != p[7].toInt()) bad.add("scheduledDays ${state.scheduledDays} vs ${p[7]}")
            if (state.dueMillis != p[8].toLong()) bad.add("due ${state.dueMillis} vs ${p[8]}")
            if (state.reps != p[9].toInt()) bad.add("reps ${state.reps} vs ${p[9]}")
            if (state.lapses != p[10].toInt()) bad.add("lapses ${state.lapses} vs ${p[10]}")
            if (bad.isNotEmpty()) {
                problems.add("[$cfgName #$scenario] rating=${rating} at=$at: " + bad.joinToString("; "))
                // resync to the reference so one early drift does not hide later, independent differences
                state = state.copy(phase = Phase.valueOf(p[3]), stability = p[4].toDouble(), difficulty = p[5].toDouble(),
                    step = p[6].toInt(), scheduledDays = p[7].toInt(), dueMillis = p[8].toLong(), reps = p[9].toInt(), lapses = p[10].toInt())
            }
        }
        return Pair(checked, problems)
    }
}

fun main(args: Array<String>) {
    val (n, problems) = GoldenVectors.run(File(args[0]))
    println("checked $n reviews, ${problems.size} mismatches")
    problems.take(15).forEach { println("  $it") }
    if (problems.isNotEmpty()) System.exit(1)
}
