package app.fluvour.quiz

import app.fluvour.reading.Cloze
import app.fluvour.reading.Token
import kotlin.random.Random

/** The four question types (Phase 3 step 2). */
enum class QKind { CONTEXT, FILL, LISTEN, TRANSLATE }

/** What the engine needs to know about a card. */
data class QCard(val id: String, val word: String, val meaning: String, val lapses: Int, val known: Boolean, val example: String?)

/** A sentence that can carry a question. `translation` comes from Tatoeba when there is one. */
data class QSentence(val text: String, val translation: String? = null, val audioId: Long? = null)

/**
 * One question. `prompt` depends on the kind: CONTEXT = the sentence with the word marked `<like this>`; FILL = the sentence with `___`;
 * LISTEN = empty (play `speak`); TRANSLATE = the meaning. `options[correct]` is the right answer.
 */
data class Question(
    val kind: QKind, val cardId: String, val word: String, val meaning: String, val prompt: String,
    val options: List<String>, val correct: Int, val hint: String = "", val explanation: String = "", val speak: String = "", val audioId: Long? = null
)

/**
 * Builds quizzes from the learner's own cards (Phase 3). Pure JVM, no Android imports.
 *  - Which words: missed-a-lot cards (leeches) first, then words not yet known, then the rest.
 *  - Which sentence (i+1 filtering): the one where the learner knows every word except the quizzed one, or as near to that as possible.
 *  - Rotation: among equally good sentences one is picked at random, so the same word shows up in different sentences over time.
 *    A leech never gets the sentence that is on its card when another exists: a new angle.
 *  - A kind that needs a sentence falls back to TRANSLATE or LISTEN when the word has none.
 */
object QuizEngine {
    const val LEECH_LAPSES = 6

    /** A short, clean meaning for answer buttons: first non-empty line, list markers dropped, cut at 70 characters. */
    fun shortMeaning(meaning: String): String {
        val line = meaning.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val clean = line.replace(Regex("^(\\d+[.)]|[-•*])\\s*"), "").trim()
        return if (clean.length <= 70) clean else clean.take(69).trimEnd() + "…"
    }

    /** How many words in `sentence` are neither the target word nor already known. 0 = a perfect i+1 sentence. */
    fun unknownOthers(sentence: String, word: String, known: Set<String>, tokens: List<Token>): Int =
        tokens.count { t ->
            if (!t.isWord || t.end > sentence.length) return@count false
            val surface = sentence.substring(t.start, t.end)
            val isTarget = surface.equals(word, true) || t.lemma.equals(word, true)
            !isTarget && surface.lowercase() !in known && (t.lemma?.lowercase() ?: "") !in known
        }

    fun build(
        cards: List<QCard>, sentencesFor: (QCard) -> List<QSentence>, split: (String) -> List<Token>,
        kinds: Set<QKind>, count: Int, known: Set<String>, rnd: Random = Random.Default
    ): List<Question> {
        val usable = cards.filter { it.word.isNotBlank() && shortMeaning(it.meaning).isNotBlank() }
        if (usable.size < 4 || kinds.isEmpty()) return emptyList()
        val knownLower = known.map { it.lowercase() }.toSet()
        val order = usable.sortedByDescending { (if (it.lapses >= LEECH_LAPSES) 100 else 0) + it.lapses * 2 + (if (it.known) 0 else 1) + rnd.nextDouble() }
        val kindList = kinds.toList()
        val out = ArrayList<Question>()
        for ((n, card) in order.take(count).withIndex()) {
            val others = usable.filter { it.id != card.id }
            // Start from the kind that is "next" in rotation, then try the others; the first that works wins.
            val tried = (kindList.indices).map { kindList[(n + it) % kindList.size] } + listOf(QKind.TRANSLATE, QKind.LISTEN)
            val q = tried.firstNotNullOfOrNull { make(it, card, others, sentencesFor, split, knownLower, rnd) } ?: continue
            out += q
        }
        return out
    }

    private fun make(
        kind: QKind, card: QCard, others: List<QCard>, sentencesFor: (QCard) -> List<QSentence>,
        split: (String) -> List<Token>, known: Set<String>, rnd: Random
    ): Question? = when (kind) {
        QKind.CONTEXT, QKind.FILL -> withSentence(kind, card, others, sentencesFor, split, known, rnd)
        QKind.LISTEN -> {
            val opts = wordOptions(card, others, rnd)
            opts?.let { (list, correct) ->
                Question(QKind.LISTEN, card.id, card.word, card.meaning, "", list, correct, explanation = "You heard ${card.word}: ${shortMeaning(card.meaning)}.", speak = card.word)
            }
        }
        QKind.TRANSLATE -> {
            val opts = wordOptions(card, others, rnd)
            opts?.let { (list, correct) ->
                Question(QKind.TRANSLATE, card.id, card.word, card.meaning, shortMeaning(card.meaning), list, correct, explanation = "${card.word} means ${shortMeaning(card.meaning)}.", speak = card.word)
            }
        }
    }

    /** Four word buttons: the card's word and three other words, shuffled. Null when there are not enough different words. */
    private fun wordOptions(card: QCard, others: List<QCard>, rnd: Random): Pair<List<String>, Int>? {
        val distractors = others.map { it.word }.filter { !it.equals(card.word, true) }.distinctBy { it.lowercase() }.shuffled(rnd).take(3)
        if (distractors.size < 3) return null
        val all = (distractors + card.word).shuffled(rnd)
        return all to all.indexOf(card.word)
    }

    private fun withSentence(
        kind: QKind, card: QCard, others: List<QCard>, sentencesFor: (QCard) -> List<QSentence>,
        split: (String) -> List<Token>, known: Set<String>, rnd: Random
    ): Question? {
        var pool = sentencesFor(card).filter { it.text.isNotBlank() }.distinctBy { it.text.trim() }
        // A leech gets a different sentence from the one on its card, if there is one.
        if (card.lapses >= LEECH_LAPSES && card.example != null && pool.size > 1) pool = pool.filter { it.text.trim() != card.example.trim() }
        val usable = pool.mapNotNull { s ->
            val tokens = split(s.text)
            Cloze.build(s.text, card.word, tokens)?.let { Triple(s, it, unknownOthers(s.text, card.word, known, tokens)) }
        }
        if (usable.isEmpty()) return null
        val best = usable.minOf { it.third }
        val (sentence, cz, _) = usable.filter { it.third == best }.random(rnd)
        val translation = sentence.translation?.takeIf { it.isNotBlank() }
        return when (kind) {
            QKind.CONTEXT -> {
                val correct = shortMeaning(card.meaning)
                val distractors = others.map { shortMeaning(it.meaning) }
                    .filter { it.isNotBlank() && !it.equals(correct, true) }.distinctBy { it.lowercase() }.shuffled(rnd).take(3)
                if (distractors.size < 3) return null
                val all = (distractors + correct).shuffled(rnd)
                val clean = { s: String -> s.replace("<", "").replace(">", "") }
                Question(QKind.CONTEXT, card.id, card.word, card.meaning, clean(cz.before) + "<" + clean(cz.answer) + ">" + clean(cz.after), all, all.indexOf(correct),
                    explanation = "${card.word} means $correct here." + (translation?.let { " \"$it\"" } ?: ""), speak = sentence.text, audioId = sentence.audioId)
            }
            else -> {
                val (opts, correct) = wordOptions(card, others, rnd) ?: return null
                Question(QKind.FILL, card.id, card.word, card.meaning, cz.before + "___" + cz.after, opts, correct,
                    hint = shortMeaning(card.meaning), explanation = "${card.word}: ${shortMeaning(card.meaning)}." + (translation?.let { " \"$it\"" } ?: ""), speak = sentence.text, audioId = sentence.audioId)
            }
        }
    }
}
