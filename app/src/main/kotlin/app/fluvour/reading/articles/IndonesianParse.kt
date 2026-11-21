package app.fluvour.reading.articles

/**
 * Plain-JVM parsing for the Indonesian adapters. The BBC Indonesia feed address was checked earlier; the Detik feed address comes from a
 * feed directory and the page layouts are NOT verified (chat cannot reach the sites). Readers fall back to long paragraphs.
 */
object IndonesianParse {
    const val BBC_ID = "bbc-id"
    const val BBC_NAME = "BBC News Indonesia"
    const val DETIK_ID = "detik"
    const val DETIK_NAME = "detikNews"

    private fun items(xml: String, host: String, sourceId: String, sourceName: String, max: Int): List<ArticleItem> =
        Rss.parse(xml, max * 2).filter { it.link.contains(host) }
            .map { ArticleItem(it.link.substringBefore('?').substringBefore('#'), HtmlText.text(it.title), "id", sourceId, sourceName, HtmlText.text(it.description)) }
            .distinctBy { it.url }.take(max)

    fun bbcList(xml: String, max: Int = 25) = items(xml, "bbc.", BBC_ID, BBC_NAME, max)

    /** BBC pages mark each paragraph block with data-component="text-block"; falls back to long paragraphs. */
    fun bbcArticle(html: String): String? {
        val blocks = Regex("(?is)data-component\\s*=\\s*[\"']text-block[\"'][^>]*>(.*?)</div>").findAll(html)
            .map { HtmlText.text(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        val t = blocks.joinToString("\n\n")
        return if (t.length >= 150) t else EnglishParse.article(html, emptyList())
    }

    fun detikList(xml: String, max: Int = 25) = items(xml, "detik.com", DETIK_ID, DETIK_NAME, max)

    /** Detik article body, without the advertising and "Baca juga" lines. */
    fun detikArticle(html: String): String? {
        val t = EnglishParse.article(html, listOf("class" to "detail__body-text", "class" to "itp_bodycontent")) ?: return null
        val clean = t.split("\n").filterNot {
            val l = it.trim()
            l.equals("ADVERTISEMENT", true) || l.startsWith("SCROLL TO CONTINUE", true) || l.startsWith("Baca juga", true) || l.startsWith("Simak juga", true)
        }.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        return clean.takeIf { it.length >= 150 }
    }
}
