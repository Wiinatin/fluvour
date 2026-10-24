package app.fluvour.quiz

import app.fluvour.data.LookupCacheDao
import app.fluvour.data.LookupCacheEntity
import app.fluvour.language.Languages
import app.fluvour.reading.LookupRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extra example sentences for a word (Phase 3 steps 5 and 6). Real sentences from Tatoeba first, a few AI-written ones when Tatoeba has none.
 * Each is fetched once and kept on the phone (the same cache as the reader's lookups), so quizzes cost nothing after the first time.
 */
@Singleton
class ExampleRepository @Inject constructor(private val cache: LookupCacheDao, private val ai: TextAi) {

    suspend fun examples(userId: String, lang: String, uiLang: String, word: String, meaning: String): List<QSentence> {
        val t = tatoeba(userId, lang, uiLang, word)
        if (t.isNotEmpty()) return t
        return aiExamples(userId, lang, uiLang, word, meaning)
    }

    /** Examples already saved on the phone for this word (no network): lets a word that has a sentence still get new ones over time. */
    suspend fun cached(lang: String, uiLang: String, word: String): List<QSentence> =
        listOf(LookupRepository.hash("ex_tatoeba2", lang, uiLang, word, ""), LookupRepository.hash("ex_ai", lang, uiLang, word, ""))
            .flatMap { k -> cache.get(k)?.let { decode(it.text) }.orEmpty() }

    private suspend fun tatoeba(userId: String, lang: String, uiLang: String, word: String): List<QSentence> {
        val key = LookupRepository.hash("ex_tatoeba2", lang, uiLang, word, "")
        cache.get(key)?.let { return decode(it.text) }
        val found = TatoebaClient.search(lang, uiLang, word).getOrNull() ?: return emptyList()   // a failure is not cached: try again next time
        val list = found.map { QSentence(it.text, it.translation, it.audioId) }
        cache.put(LookupCacheEntity(key, userId, "ex_tatoeba", lang, word, encode(list), System.currentTimeMillis()))   // "none found" is cached too
        return list
    }

    private suspend fun aiExamples(userId: String, lang: String, uiLang: String, word: String, meaning: String): List<QSentence> {
        val key = LookupRepository.hash("ex_ai", lang, uiLang, word, "")
        cache.get(key)?.let { return decode(it.text) }
        val target = Languages.byCode(lang).name
        val prompt = "Write 3 short, natural $target sentences that use the word \"$word\" (meaning: ${QuizEngine.shortMeaning(meaning)}). " +
            "At most 14 words each, plain everyday vocabulary. One sentence per line. No numbering, no translation, no romanisation, no quotes."
        val text = ai.ask(prompt, 300).text ?: return emptyList()
        val list = text.lines().map { it.trim().trim('"', '「', '」').replace(Regex("^(\\d+[.)]|[-•*])\\s*"), "") }
            .filter { it.length in 3..140 && it.contains(word, ignoreCase = true) }.distinct().take(3).map { QSentence(it) }
        if (list.isNotEmpty()) cache.put(LookupCacheEntity(key, userId, "ex_ai", lang, word, encode(list), System.currentTimeMillis()))
        return list
    }

    /** One fresh sentence, different from `old`, for "Try a new angle". Not cached: every press gives a new one. */
    suspend fun freshExample(lang: String, word: String, meaning: String, old: String?): Result<String> {
        val target = Languages.byCode(lang).name
        val prompt = "Write ONE new short $target sentence (at most 14 words) that uses the word \"$word\" (meaning: ${QuizEngine.shortMeaning(meaning)})." +
            (old?.let { " It must be different from: \"$it\"." } ?: "") + " Answer with the sentence only: no translation, no romanisation, no quotes."
        val p = ai.ask(prompt, 120)
        val line = p.text?.lineSequence()?.map { it.trim().trim('"', '「', '」') }?.firstOrNull { it.isNotEmpty() }
        return if (line != null) Result.success(line) else Result.failure(Exception(p.message ?: "The AI returned no sentence."))
    }

    private fun encode(list: List<QSentence>) = if (list.isEmpty()) NONE else list.joinToString("\n") { it.text.replace('\t', ' ').replace('\n', ' ') + "\t" + (it.translation?.replace('\t', ' ')?.replace('\n', ' ') ?: "") + "\t" + (it.audioId ?: "") }
    private fun decode(text: String): List<QSentence> =
        if (text == NONE) emptyList() else text.lines().filter { it.isNotBlank() }.map { l -> l.split('\t').let { QSentence(it[0], it.getOrNull(1)?.takeIf { t -> t.isNotBlank() }, it.getOrNull(2)?.toLongOrNull())  } }

    private companion object { const val NONE = "-" }
}
