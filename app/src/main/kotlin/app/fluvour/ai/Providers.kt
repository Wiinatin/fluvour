package app.fluvour.ai

import app.fluvour.reading.MiniJson

/**
 * The AI providers Fluvour can call with your own keys. Each has its own key pool (Settings > AI keys).
 * Text tries the providers that have keys in [TEXT_ORDER]; pictures use [IMAGE_ORDER] (Hugging Face only). Pure JVM.
 * Model names change often: every provider's model can be overridden in Settings, the default is only a starting point.
 */
enum class AiProvider(
    val id: String, val label: String, val text: Boolean, val image: Boolean,
    val defaultModel: String, val chatUrl: String?, val keysAt: String, val keyHint: String
) {
    GEMINI("gemini", "Gemini", true, false, GeminiModels.DEFAULT, null, "aistudio.google.com/apikey", "Starts with AIza"),
    GROQ("groq", "Groq", true, false, "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1/chat/completions", "console.groq.com/keys", "Starts with gsk_"),
    CEREBRAS("cerebras", "Cerebras", true, false, "llama-3.3-70b", "https://api.cerebras.ai/v1/chat/completions", "cloud.cerebras.ai", "Starts with csk-"),
    MISTRAL("mistral", "Mistral", true, false, "mistral-small-latest", "https://api.mistral.ai/v1/chat/completions", "console.mistral.ai/api-keys", ""),
    OPENROUTER("openrouter", "OpenRouter", true, false, "meta-llama/llama-3.3-70b-instruct:free", "https://openrouter.ai/api/v1/chat/completions", "openrouter.ai/keys", "Starts with sk-or-"),
    HUGGINGFACE("huggingface", "Hugging Face", true, true, "Qwen/Qwen2.5-72B-Instruct", "https://router.huggingface.co/v1/chat/completions", "huggingface.co/settings/tokens", "Starts with hf_; needs the Inference Providers permission"),
    // Pictures only. A "key" here is two parts joined as ACCOUNT_ID:TOKEN (the Settings screen has a field for each).
    CLOUDFLARE("cloudflare", "Cloudflare", false, true, "@cf/black-forest-labs/flux-1-schnell", null, "dash.cloudflare.com > Workers AI", "Two parts: your Account ID and an API token with the Workers AI permission");

    companion object {
        /** Gemini first, then the others; a provider with no key is skipped. */
        val TEXT_ORDER = listOf(GEMINI, GROQ, CEREBRAS, MISTRAL, OPENROUTER, HUGGINGFACE)
        /** Pictures: Cloudflare Workers AI first (free daily allowance), then Hugging Face. Never Gemini: its free API does not generate images. */
        val IMAGE_ORDER = listOf(CLOUDFLARE, HUGGINGFACE)

        const val HF_IMAGE_MODEL = "black-forest-labs/FLUX.1-schnell"
        // Hugging Face routes this to Together AI, which serves FLUX.1-schnell and answers with base64.
        const val HF_IMAGE_URL = "https://router.huggingface.co/together/v1/images/generations"

        /** Cloudflare Workers AI: one URL per account. The model id starts with @cf/ and stays part of the path. */
        fun cloudflareUrl(accountId: String, model: String) = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        /** A Cloudflare "key" is stored as ACCOUNT_ID:TOKEN. Null when it is not in that shape. */
        fun splitCloudflareKey(key: String): Pair<String, String>? {
            val i = key.indexOf(':')
            if (i <= 0 || i == key.length - 1) return null
            return key.substring(0, i).trim() to key.substring(i + 1).trim()
        }

        fun geminiUrl(model: String) = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
    }
}

/** Reads what the providers answer. Pure (uses the app's own small JSON reader), so it is tested on a plain JVM. */
object AiParse {
    private fun map(x: Any?) = x as? Map<*, *>
    private fun list(x: Any?) = x as? List<*>

    /** Gemini: the text of the first candidate. */
    fun geminiText(json: String): String? = try {
        val parts = list(map(map(list(map(MiniJson.parse(json))?.get("candidates"))?.firstOrNull())?.get("content"))?.get("parts"))
        parts?.joinToString("") { map(it)?.get("text") as? String ?: "" }?.trim()?.ifBlank { null }
    } catch (e: Exception) { null }

    /** OpenAI-style chat: choices[0].message.content. */
    fun chatText(json: String): String? = try {
        val content = map(map(list(map(MiniJson.parse(json))?.get("choices"))?.firstOrNull())?.get("message"))?.get("content")
        when (content) {
            is String -> content
            is List<*> -> content.joinToString("") { map(it)?.get("text") as? String ?: "" }
            else -> null
        }?.trim()?.ifBlank { null }
    } catch (e: Exception) { null }

    /** Cloudflare Workers AI image answer: result.image (base64). */
    fun cloudflareImage(json: String): String? = try {
        (map(map(MiniJson.parse(json))?.get("result"))?.get("image") as? String)?.ifBlank { null }
    } catch (e: Exception) { null }

    /** Together / OpenAI-style image answer: data[0].b64_json. */
    fun b64Image(json: String): String? = try {
        (map(list(map(MiniJson.parse(json))?.get("data"))?.firstOrNull())?.get("b64_json") as? String)?.ifBlank { null }
    } catch (e: Exception) { null }
}
