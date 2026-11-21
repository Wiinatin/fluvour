package app.fluvour.ai

/** One Gemini model the app can use. [short] is what the little label on an entry says. */
data class GeminiModel(val id: String, val name: String, val short: String, val note: String)

/** Gemini text-to-speech (preview). The answer is raw 16-bit mono sound at [SAMPLE_RATE] Hz. Model names change often: the older one is the fallback. */
object GeminiTts {
    const val MODEL = "gemini-3.1-flash-tts-preview"
    const val FALLBACK = "gemini-2.5-flash-preview-tts"
    const val SAMPLE_RATE = 24000
    /** The 30 prebuilt Gemini voices. The first six stay in their old order, so saved choices and the default (Kore) do not change. */
    val VOICES = listOf(
        "Kore", "Leda", "Aoede", "Puck", "Charon", "Orus",
        "Zephyr", "Fenrir", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome",
        "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
        "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    )
}

object GeminiModels {
    /** Strongest first. */
    val ALL = listOf(
        GeminiModel("gemini-3.6-flash", "Gemini 3.6 Flash", "3.6 Flash", "The strongest. Gets busy most often."),
        GeminiModel("gemini-3.5-flash", "Gemini 3.5 Flash", "3.5 Flash", "Strong and steadier."),
        GeminiModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "3.5 Lite", "The lightest and fastest. Least careful.")
    )

    const val DEFAULT = "gemini-3.6-flash"

    fun find(id: String?): GeminiModel? = ALL.firstOrNull { it.id == id }

    /** The short label for a stored model id; unknown ids (older or future models) show as they are. */
    fun shortName(id: String): String = find(id)?.short ?: id.removePrefix("gemini-")

    /**
     * The models to try, in order. The chosen one first; with [fallback] on, the weaker ones next (best of
     * those first), then the stronger ones. Choosing 3.6 gives 3.6, 3.5, Lite; choosing Lite gives Lite, 3.5, 3.6.
     */
    fun chain(preferred: String?, fallback: Boolean): List<String> {
        val i = ALL.indexOfFirst { it.id == preferred }.let { if (it < 0) 0 else it }
        val first = ALL[i].id
        if (!fallback) return listOf(first)
        return listOf(first) + ALL.drop(i + 1).map { it.id } + ALL.take(i).reversed().map { it.id }
    }
}
