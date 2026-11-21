package app.fluvour.audio

/**
 * Works out how to load a voice-pack folder from the names of the files in it. Pure (no Android, no sherpa), so it is
 * checked on a plain JVM in tools/cliptest. [LocalVoice] turns the result into a sherpa-onnx config.
 *
 * Two layouts are known:
 * - Kokoro: `voices.bin` next to a `model*.onnx`, plus `tokens.txt`, an `espeak-ng-data` folder, lexicon and rule files.
 * - VITS (Piper and MMS): one `*.onnx`, `tokens.txt`, and either an `espeak-ng-data` folder (Piper) or a `lexicon.txt` (others).
 */
data class ModelLayout(
    val kind: Kind,
    val model: String,
    val tokens: String,
    val voices: String = "",
    val dataDir: String = "",
    val lexicons: List<String> = emptyList(),
    val ruleFsts: List<String> = emptyList(),
    val dictDir: String = "",
) {
    enum class Kind { KOKORO, VITS }

    companion object {
        /** [names] are the entries directly inside the folder (files and folders, names only). Null when the folder is not a usable voice pack. */
        fun detect(names: List<String>): ModelLayout? {
            if ("tokens.txt" !in names) return null
            val onnx = names.filter { it.endsWith(".onnx") }.sorted()
            if (onnx.isEmpty()) return null
            val data = "espeak-ng-data".takeIf { it in names }.orEmpty()
            if ("voices.bin" in names) {
                // gb-en and us-en lexicons both describe English; using both makes the English words ambiguous, so the US one wins.
                val lex = names.filter { it.startsWith("lexicon") && it.endsWith(".txt") && it != "lexicon-gb-en.txt" }.sorted()
                return ModelLayout(
                    Kind.KOKORO, onnx.first(), "tokens.txt", voices = "voices.bin", dataDir = data, lexicons = lex,
                    ruleFsts = names.filter { it.endsWith(".fst") }.sorted(), dictDir = "dict".takeIf { it in names }.orEmpty(),
                )
            }
            return ModelLayout(Kind.VITS, onnx.first(), "tokens.txt", dataDir = data, lexicons = listOf("lexicon.txt").filter { it in names })
        }
    }
}
