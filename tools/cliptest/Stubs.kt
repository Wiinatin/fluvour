package app.fluvour.reading
data class KanjiWord(val word: String, val level: String?, val rank: Int?)
data class Token(val start: Int, val end: Int, val isWord: Boolean, val lemma: String? = null, val reading: String? = null)
object WordSplitter {
    fun split(text: String, langTag: String): List<Token> {
        val out = ArrayList<Token>(); var i = 0
        while (i < text.length) {
            if (text[i].isLetterOrDigit()) { var j = i; while (j < text.length && text[j].isLetterOrDigit()) j++; out += Token(i, j, true); i = j } else i++
        }
        return out
    }
}
