package app.fluvour.language

/** Any target language is supported by data, not code. Add a row to add a language. */
data class Language(
    val code: String,          // BCP-47
    val name: String,          // English name
    val native: String,        // name in its own language
    val noSpaces: Boolean = false,   // needs a tokenizer in Phase 2
    val tokenizer: String? = null,   // in use: kuromoji (ja) | jieba (zh) | icu (th)
    val rtl: Boolean = false,        // written right to left (Arabic)
    val ttsTag: String? = null,      // exact voice/recognition locale when the bare code is not enough (Malay must be ms-MY, never Indonesian)
    val promptName: String? = null,  // how Gemini is told the language, when the plain name is ambiguous
    val ocr: Boolean = true          // false = screen reading is not offered (Arabic)
) {
    /** Locale for the phone's voice and for speech recognition. */
    val deviceTag: String get() = ttsTag ?: code
    /** Name to put in an AI prompt. */
    val aiName: String get() = promptName ?: name
}

object Languages {
    val all = listOf(
        Language("ja", "Japanese", "日本語", true, "kuromoji"),
        Language("th", "Thai", "ไทย", true, "icu"),
        Language("zh", "Chinese", "中文", true, "jieba"),
        Language("ko", "Korean", "한국어"),
        Language("en", "English", "English"),
        Language("es", "Spanish", "Español"),
        Language("fr", "French", "Français"),
        Language("de", "German", "Deutsch"),
        Language("vi", "Vietnamese", "Tiếng Việt"),
        Language("id", "Indonesian", "Bahasa Indonesia"),
        // Quranic Arabic: vowelled text, right to left, no screen reading, no Path.
        Language("ar", "Arabic", "العربية", rtl = true, ttsTag = "ar-SA", promptName = "Quranic (Classical) Arabic, fully vowelled", ocr = false),
        // One Malay language holds Standard and Sarawak together. Always Malay (Malaysia), never Indonesian.
        Language("ms", "Malay", "Bahasa Melayu", ttsTag = "ms-MY", promptName = "Malay (Malaysia), not Indonesian")
    )
    val default = all.first { it.code == "ja" }
    fun byCode(code: String) = all.firstOrNull { it.code == code } ?: default
    /** UI languages shipped first: English and Thai (per the roadmap). */
    /** Phone-voice / recognition locale for a tag such as "ms" or "ms-MY". Unknown codes pass through unchanged. */
    fun deviceTag(tag: String): String {
        val code = tag.substringBefore('-').lowercase()
        return all.firstOrNull { it.code == code }?.ttsTag?.takeIf { tag.equals(code, true) } ?: tag
    }
    val uiLanguages = all.filter { it.code == "en" || it.code == "th" }
}
