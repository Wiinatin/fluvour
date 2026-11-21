package app.fluvour.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 2 of the speaking pipeline: turns the transcript into an improved version and new words. Text only, so any provider with a key
 * can answer (rotation as for other text calls), and the rewrite can be redone without uploading the recording again.
 * The text of the result is the model's JSON, read with [SpeakingParse.improved].
 */
@Singleton
class SpeechImprover @Inject constructor(private val ai: AiService) {
    suspend fun improve(lang: String, own: String, level: String, transcript: Transcript): AiText =
        ai.text(ImprovePrompt.build(lang, own, level, transcript.text, transcript.substituted), ImprovePrompt.TEMPERATURE, ImprovePrompt.MAX_TOKENS)
}
