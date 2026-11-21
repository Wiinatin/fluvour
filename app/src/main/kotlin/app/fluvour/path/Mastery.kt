package app.fluvour.path

import kotlin.random.Random

/**
 * Practice by mastery (pure Kotlin; tools/masterytest runs it on a plain JVM). One session covers every word of the unit AND every
 * sentence of its lesson. Each item earns points: [MasteryRules.RECOGNITION] for a right choose/listen/fill answer, [MasteryRules.PRODUCTION]
 * for a right build/typed answer, minus [MasteryRules.MISS_PENALTY] for a miss. An item is learned at [MasteryRules.MASTERED_POINTS] points with at
 * least [MasteryRules.MIN_PRODUCTION] right production answer, and then must pass one last typed question (no options) after a gap: at the very
 * least a recognition answer, a production answer and the proof, in that order.
 * The session keeps choosing the weakest items until every item has passed that proof, so there is no fixed number of drills.
 */
object MasteryRules {
    const val MASTERED_POINTS = 3
    const val MIN_PRODUCTION = 1
    const val RECOGNITION = 1
    const val PRODUCTION = 2
    const val MISS_PENALTY = 1
    /** Other drills between two questions on the same item, and before its proof question (relaxed when few items are left). */
    const val GAP = 3
    const val PROOF_GAP = 4
    /** The session stops (not learned) after this many drills per item, so a hopeless item cannot trap the learner. */
    const val MAX_TURNS_PER_ITEM = 9
}

enum class ItemKind { WORD, SENTENCE }

/** One thing to learn: a unit word or a lesson sentence. */
class MasteryItem(val id: String, val kind: ItemKind, val label: String)

/** A drill that belongs to an item. [production] = the learner produces the answer (build, typed) instead of recognising it; [proof] = usable as the last question. */
class PooledDrill(val drill: Drill, val production: Boolean, val proof: Boolean = false)

class MasterySession(val items: List<MasteryItem>, private val pool: Map<String, List<PooledDrill>>, private val rnd: Random = Random.Default) {
    private class S { var points = 0; var prod = 0; var proof = false; var lastTurn = -100; var lastMiss = false; var misses = 0 }
    private val st: Map<String, S> = items.associate { it.id to S() }
    private val used = HashMap<PooledDrill, Int>()
    private var current: Pair<MasteryItem, PooledDrill>? = null
    private var lastItem: String? = null
    private var lastDrill: PooledDrill? = null
    private var turn = 0
    private val maxTurns = items.size * MasteryRules.MAX_TURNS_PER_ITEM + 20

    var answered = 0; private set
    var correctCount = 0; private set
    /** True when the session stopped because of [MasteryRules.MAX_TURNS_PER_ITEM]: some items are not learned. */
    var capped = false; private set

    val total: Int get() = items.size
    /** Items that passed their proof question. */
    val mastered: Int get() = items.count { st.getValue(it.id).proof }
    val allDone: Boolean get() = mastered == items.size
    /** The question now on screen is a proof (last) question. */
    val inProof: Boolean get() = current?.second?.proof == true
    val currentItem: MasteryItem? get() = current?.first
    /** 0 to 1, moves with every point: 80% for the points, 20% for the proof. */
    val progress: Float get() = if (items.isEmpty()) 1f else items.sumOf { i ->
        val s = st.getValue(i.id); (minOf(s.points, MasteryRules.MASTERED_POINTS) / MasteryRules.MASTERED_POINTS.toDouble()) * 0.8 + if (s.proof) 0.2 else 0.0
    }.toFloat() / items.size

    private fun learned(s: S) = s.points >= MasteryRules.MASTERED_POINTS && s.prod >= MasteryRules.MIN_PRODUCTION

    /** The next drill, or null when every item passed its proof (see [allDone]) or the session was capped. */
    fun next(): Drill? {
        if (allDone || items.isEmpty()) { current = null; return null }
        if (turn >= maxTurns) { capped = true; current = null; return null }
        val open = items.filter { !st.getValue(it.id).proof }
        val spaced = open.filter { turn - st.getValue(it.id).lastTurn >= (if (learned(st.getValue(it.id))) MasteryRules.PROOF_GAP else MasteryRules.GAP) }
        val from = spaced.ifEmpty { open.filter { it.id != lastItem }.ifEmpty { open } }
        val low = from.minOf { st.getValue(it.id).points }
        val item = from.filter { st.getValue(it.id).points == low }.random(rnd)
        val pd = choose(item) ?: run { // an item with no drill at all cannot be learned: count it as done so the session can end
            st.getValue(item.id).apply { points = MasteryRules.MASTERED_POINTS; prod = MasteryRules.MIN_PRODUCTION; proof = true }
            return next()
        }
        used[pd] = (used[pd] ?: 0) + 1
        current = item to pd; lastItem = item.id; lastDrill = pd
        return pd.drill
    }

    private fun choose(item: MasteryItem): PooledDrill? {
        val all = pool[item.id].orEmpty(); if (all.isEmpty()) return null
        val s = st.getValue(item.id)
        val recognition = all.filter { !it.production }
        val production = all.filter { it.production && !it.proof }
        val proofs = all.filter { it.proof }
        val wanted: List<PooledDrill> = when {
            learned(s) -> proofs.ifEmpty { production }
            s.points < 1 || (s.lastMiss && s.points < 2) -> recognition
            s.prod < MasteryRules.MIN_PRODUCTION -> production.ifEmpty { recognition }
            else -> (if (rnd.nextBoolean()) production else recognition).ifEmpty { production + recognition }
        }.ifEmpty { all }
        val least = wanted.minOf { used[it] ?: 0 }
        val fresh = wanted.filter { (used[it] ?: 0) == least && it !== lastDrill }.ifEmpty { wanted.filter { (used[it] ?: 0) == least } }
        return fresh.random(rnd)
    }

    /** The answer to the drill last returned by [next]. */
    fun record(correct: Boolean) {
        val (item, pd) = current ?: return
        val s = st.getValue(item.id)
        answered++; if (correct) correctCount++
        s.lastTurn = turn; s.lastMiss = !correct
        if (pd.proof) {
            if (correct) s.proof = true else { s.points = maxOf(0, s.points - MasteryRules.MISS_PENALTY); s.misses++ }
        } else if (correct) {
            s.points += if (pd.production) MasteryRules.PRODUCTION else MasteryRules.RECOGNITION
            if (pd.production) s.prod++
        } else { s.points = maxOf(0, s.points - MasteryRules.MISS_PENALTY); s.misses++ }
        turn++
        current = null
    }

    /** The progress of every item as numbers (points, production answers, proof 0/1, misses), to save when the learner leaves and restore later. */
    fun snapshot(): Map<String, List<Int>> = items.associate { it.id to st.getValue(it.id).let { s -> listOf(s.points, s.prod, if (s.proof) 1 else 0, s.misses) } }

    /** Puts saved progress back; items that no longer exist are ignored. A restored item that was proven keeps its proof only when it is still learned. */
    fun restore(saved: Map<String, List<Int>>) {
        for (i in items) { val v = saved[i.id] ?: continue; if (v.size < 4) continue; st.getValue(i.id).apply { points = v[0]; prod = v[1]; proof = v[2] == 1; misses = v[3] } }
    }

    /** Labels of the items that were missed at least once (for the end message). */
    fun hardest(n: Int = 3): List<String> = items.sortedByDescending { st.getValue(it.id).misses }.filter { st.getValue(it.id).misses > 0 }.take(n).map { it.label }
}

/** Builds the items and their drills from a unit and its lesson, locally (no AI request): the lesson's own drills are added where their target word matches. */
object MasteryBuilder {
    private fun jp(t: String) = t.any { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' }

    fun build(unit: CurriculumUnit, lesson: Lesson, rnd: Random): Pair<List<MasteryItem>, Map<String, List<PooledDrill>>> {
        val items = ArrayList<MasteryItem>(); val pool = HashMap<String, MutableList<PooledDrill>>()
        fun add(id: String, d: Drill, production: Boolean, proof: Boolean = false) { pool.getOrPut(id) { ArrayList() }.add(PooledDrill(d, production, proof)) }
        fun options(right: String, others: List<String>): Pair<List<String>, Int>? {
            val extra = others.distinct().filter { it != right }.shuffled(rnd).take(2)
            if (extra.size < 2) return null
            val all = (extra + right).shuffled(rnd)
            return all to all.indexOf(right)
        }
        val chunks = lesson.chunks
        val glosses = unit.words.map { it.gloss.ifBlank { it.word } }

        // ---- sentences ----
        chunks.forEachIndexed { ci, c ->
            val id = "s:$ci"; items.add(MasteryItem(id, ItemKind.SENTENCE, c.jp))
            val why = c.pattern.ifBlank { c.en }
            options(c.en, chunks.map { it.en })?.let { (o, a) -> add(id, ChooseDrill("What does it mean?", o, a, why, null, audio = c.jp), false) }
            options(c.jp, chunks.map { it.jp })?.let { (o, a) -> add(id, ChooseDrill("Which sentence means: \"${c.en}\"?", o, a, why, null), false) }
            val tokens = c.words.map { it.text }.filter { jp(it) }
            if (tokens.size in 2..9 && tokens.distinct().size == tokens.size) {
                val spare = chunks.flatMap { o -> o.words.map { it.text } }.filter { jp(it) && it !in tokens }.distinct().shuffled(rnd).take(1)
                add(id, BuildDrill(c.en, (tokens + spare).shuffled(rnd), tokens, why, null), true)
            }
            val typed = TypedDrill("What does this mean?", c.jp, listOf(c.en), why, null)
            add(id, typed, true); add(id, typed, true, proof = true)
        }

        // ---- words ----
        for (w in unit.words) {
            val id = "w:${w.word}"; items.add(MasteryItem(id, ItemKind.WORD, w.word))
            val g = w.gloss.ifBlank { w.word }
            val why = "${w.word} (${w.reading}) = $g"
            options(g, glosses)?.let { (o, a) ->
                add(id, ChooseDrill("What does ${w.word} mean?", o, a, why, w.word), false)
                add(id, ChooseDrill("What does it mean?", o, a, why, w.word, audio = w.word), false)
            }
            val host = chunks.firstOrNull { c -> c.words.any { it.text == w.word } && c.jp.split(w.word).size == 2 }
            if (host != null) options(w.word, unit.words.map { it.word }.filter { !w.word.contains(it) && !it.contains(w.word) })?.let { (o, a) ->
                add(id, ChooseDrill(host.jp.replaceFirst(w.word, "＿") + " (" + host.en + ")", o, a, why, w.word), false)
            }
            val accepted = (listOf(g) + g.split("/", ";", ",").map { it.trim() }).filter { it.isNotEmpty() }.distinct()
            val typed = TypedDrill("What does this mean?", w.word, accepted, why, w.word)
            add(id, typed, true); add(id, typed, true, proof = true)
            val chars = w.word.map { it.toString() }
            if (chars.size in 2..8 && chars.distinct().size == chars.size && chars.all { jp(it) }) add(id, BuildDrill("Build: $g", chars.shuffled(rnd), chars, why, w.word), true)
        }
        // the AI's own drills add variety to the words they are about
        for (d in lesson.drills) {
            val t = d.target ?: continue; val id = "w:$t"
            if (items.none { it.id == id }) continue
            add(id, d, d !is ChooseDrill)
        }
        return items to pool
    }
}
