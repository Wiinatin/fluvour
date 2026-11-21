package app.fluvour.reading
data class Token(val start: Int, val end: Int, val isWord: Boolean, val lemma: String? = null, val reading: String? = null)
