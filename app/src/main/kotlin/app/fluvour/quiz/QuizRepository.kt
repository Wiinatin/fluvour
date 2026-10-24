package app.fluvour.quiz

import app.fluvour.data.CardDao
import app.fluvour.data.SentenceDao
import app.fluvour.reading.WordSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** A ready quiz, or the reason there is none. */
data class QuizPrep(val questions: List<Question>, val message: String? = null)

/**
 * Puts a quiz together from the learner's own cards (Phase 3). The sets on the Quiz tab:
 * mix (all kinds, 8 questions), ctx (in context), fil (fill in the blank), lis (listening), trn (which word means this), 5 questions each.
 * Sentences come from the card, the sentences mined from reading and the screen, and Tatoeba / AI examples (fetched once, cached).
 */
@Singleton
class QuizRepository @Inject constructor(private val cards: CardDao, private val sentences: SentenceDao, private val examples: ExampleRepository) {

    private fun kindsFor(setId: String): Pair<Set<QKind>, Int> = when (setId) {
        "ctx" -> setOf(QKind.CONTEXT) to 5
        "fil" -> setOf(QKind.FILL) to 5
        "lis" -> setOf(QKind.LISTEN) to 5
        "trn" -> setOf(QKind.TRANSLATE) to 5
        else -> setOf(QKind.CONTEXT, QKind.FILL, QKind.LISTEN, QKind.TRANSLATE) to 8
    }

    suspend fun prepare(userId: String, lang: String, uiLang: String, setId: String): QuizPrep {
        val all = cards.observe(lang).first().filter { QuizEngine.shortMeaning(it.meaning).isNotBlank() }
        if (all.size < 4) return QuizPrep(emptyList(), "A quiz needs at least 4 cards with a meaning. You have ${all.size}. Add a few words first.")
        val (kinds, count) = kindsFor(setId)
        val mined = sentences.allForLang(lang)
        val minedById = mined.associateBy { it.id }
        val known = all.filter { it.phase == "REVIEW" }.map { it.word }.toSet()

        // Sentences each card can use now: its own example, its linked sentence, mined sentences that contain the word.
        val pool = HashMap<String, MutableList<QSentence>>()
        for (c in all) {
            val list = ArrayList<QSentence>()
            c.example?.takeIf { it.isNotBlank() }?.let { list += QSentence(it) }
            c.sentenceId?.let { minedById[it]?.let { s -> list += QSentence(s.text, s.translation) } }
            mined.filter { it.text.contains(c.word, ignoreCase = true) }.forEach { list += QSentence(it.text, it.translation) }
            list += examples.cached(lang, uiLang, c.word)
            pool[c.id] = list
        }

        // Words with no sentence get Tatoeba / AI examples, a few at a time, within a time limit. Everything fetched is cached.
        if (kinds.any { it == QKind.CONTEXT || it == QKind.FILL }) {
            val lacking = all.filter { pool[it.id].isNullOrEmpty() }.shuffled().take(6)
            if (lacking.isNotEmpty()) withTimeoutOrNull(25_000) {
                val gate = Semaphore(2)
                coroutineScope {
                    lacking.map { c -> async { gate.withPermit { pool[c.id]?.addAll(examples.examples(userId, lang, uiLang, c.word, c.meaning)) } } }.forEach { it.await() }
                }
            }
        }
        val qCards = all.map { QCard(it.id, it.word, it.meaning, it.lapses, it.phase == "REVIEW", it.example) }
        val questions = withContext(Dispatchers.Default) {
            QuizEngine.build(qCards, { pool[it.id].orEmpty() }, { WordSplitter.split(it, lang) }, kinds, count, known)
        }
        if (questions.isEmpty()) return QuizPrep(emptyList(), "Could not build questions from your cards. Words with a short, clear meaning work best.")
        return QuizPrep(questions)
    }
}
