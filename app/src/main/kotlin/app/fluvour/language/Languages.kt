package app.fluvour.language

/** Any target language is supported by data, not code. Add a row to add a language. */
data class Language(
    val code: String,          // BCP-47
    val name: String,          // English name
    val native: String,        // name in its own language
    val noSpaces: Boolean = false,   // needs a tokenizer in Phase 2
    val tokenizer: String? = null    // in use: kuromoji (ja) | jieba (zh) | icu (th)
)

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
        Language("id", "Indonesian", "Bahasa Indonesia")
    )
    val default = all.first { it.code == "ja" }
    fun byCode(code: String) = all.firstOrNull { it.code == code } ?: default
    /** UI languages shipped first: English and Thai (per the roadmap). */
    val uiLanguages = all.filter { it.code == "en" || it.code == "th" }
}
