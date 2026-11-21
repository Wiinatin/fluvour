package app.fluvour.reading

import app.fluvour.ai.AiService
import javax.inject.Inject
import javax.inject.Singleton

/** Asks the AI (your own keys, Settings > AI keys) for the Sarawak side of a new Standard Malay card. Null when there is no key, no answer, or nothing to add. */
@Singleton
class SarawakSuggester @Inject constructor(private val ai: AiService) {
    suspend fun suggest(word: String, meaning: String): SarawakSuggestion? {
        if (word.isBlank()) return null
        val r = ai.text(SarawakSuggestPrompt.build(word, meaning), SarawakSuggestPrompt.TEMPERATURE, SarawakSuggestPrompt.MAX_TOKENS)
        return SarawakSuggestPrompt.parse(r.text, word)
    }
}
