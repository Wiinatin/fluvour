package app.fluvour.ai

/** Which words of an improved text are new compared with what the learner said. Pure, so it is checked on a plain JVM. */
object TextDiff {
    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * [original] and [improved] are word lists (the reader's tokens). Returns one flag per improved word: true when that word is not part of
     * the longest common run of words, which means the AI changed or added it. Punctuation (empty keys) is never flagged.
     */
    fun changed(original: List<String>, improved: List<String>): BooleanArray {
        val a = original.map(::key); val b = improved.map(::key)
        val n = a.size; val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            lcs[i][j] = if (a[i] == b[j] && a[i].isNotEmpty()) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val same = BooleanArray(m)
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] && a[i].isNotEmpty() -> { same[j] = true; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
        return BooleanArray(m) { !same[it] && b[it].isNotEmpty() }
    }
}
