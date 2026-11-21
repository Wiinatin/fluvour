package app.fluvour.reading.articles

/** Thai news from Thai PBS (public broadcaster). Reads the news archive page; no feed was found. Skipped quietly if the page changes or blocks the app. */
object ThaiPbsSource : PageSource(
    ThaiParse.PBS_ID, ThaiParse.PBS_NAME, setOf("th"),
    ThaiParse.PBS_BASE + "/news/archive", "Thai PBS (personal use)"
) {
    override fun parseList(html: String) = ThaiParse.pbsList(html)
    override fun parseArticle(html: String) = ThaiParse.pbsArticle(html)
}
