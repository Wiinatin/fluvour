import app.fluvour.reading.*
import app.fluvour.reading.articles.*
fun check(name: String, ok: Boolean) { println((if (ok) "ok   " else "FAIL ") + name); if (!ok) System.exit(1) }
fun main() {
    // HtmlText
    check("decode entities", HtmlText.decode("a &amp; b &lt;c&gt; &#12354; &#x3042; &nbsp;") == "a & b <c> あ あ  ")
    check("stripRuby", HtmlText.text(HtmlText.stripRuby("<ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>を読む")) == "漢字を読む")
    check("text blocks", HtmlText.text("<p>One</p><p>Two<br>three</p><script>x()</script>") == "One\nTwo\nthree")
    val nested = "<div class=\"a article-body b\"><div>inner</div><p>text</p></div><div>after</div>"
    check("element nested", HtmlText.element(nested, "article-body") == "<div>inner</div><p>text</p>")
    check("element missing", HtmlText.element(nested, "nope") == null)
    check("paragraphs", HtmlText.paragraphs("<p>A</p><b>x</b><p> B </p>") == "A\n\nB")
    // Rss
    val xml = """<rss><channel><title>t</title><item><title><![CDATA[速報 &テスト]]></title><link>https://x.test/1</link><description>&lt;b&gt;要約&lt;/b&gt;</description><pubDate>Mon</pubDate></item><item><title>no link</title></item><item><title>Two &amp; more</title><link>https://x.test/2</link></item></channel></rss>"""
    val items = Rss.parse(xml)
    check("rss count", items.size == 2)
    check("rss cdata", items[0].title == "速報 &テスト" && items[0].link == "https://x.test/1")
    check("rss entities", items[1].title == "Two & more")
    // NHK Easy list (assumed shape: date -> list of items with news_id)
    val json = """[{"2026-10-29":[{"news_id":"k1","title":"<ruby>新<rt>あたら</rt></ruby>しいニュース","news_prearranged_time":"2026-10-29 10:00:00"},{"news_id":"k2","title":"古い","news_prearranged_time":"2026-10-29 08:00:00"}],"2026-10-30":[{"news_id":"k3","title":"今日","news_prearranged_time":"2026-10-30 09:00:00"},{"news_id":"k1","title":"重複","news_prearranged_time":"2026-10-29 10:00:00"}]}]"""
    val l = NhkParse.easyList(json)
    check("easy list newest first, deduped", l.map { it.url.substringAfterLast('/') } == listOf("k3.html", "k1.html", "k2.html"))
    check("easy list url", l[0].url == "https://www3.nhk.or.jp/news/easy/k3/k3.html")
    check("easy list title strips ruby", l[1].title == "新しいニュース" || l[1].title == "重複")
    check("easy list bad json", NhkParse.easyList("not json").isEmpty() && NhkParse.easyList("[]").isEmpty())
    check("easy list max", NhkParse.easyList(json, 2).size == 2)
    // NHK Easy article
    val page = "<html><body><div class=\"article-body\"><p><ruby>東京<rt>とうきょう</rt></ruby>で<ruby>雪<rt>ゆき</rt></ruby>が降りました。</p><p>電車が止まって、たくさんの人が困りました。駅では係の人が説明をしていました。</p></div><footer>menu</footer></body></html>"
    val body = NhkParse.easyArticle(page)
    check("easy article", body == "東京で雪が降りました。\n電車が止まって、たくさんの人が困りました。駅では係の人が説明をしていました。")
    check("easy article fallback to p", NhkParse.easyArticle("<p>これは長い文章です。これは長い文章です。これは長い文章です。これは長い文章です。</p>") != null)
    check("easy article too short", NhkParse.easyArticle("<p>短い</p>") == null)
    // NHK news
    val nl = NhkParse.newsList(xml)
    check("news list", nl.size == 2 && nl[0].sourceId == "nhk-news" && nl[0].summary == "要約")
    val np = "<section class=\"content--summary\">首相は今日、新しい政策について会見で説明しました。</section><section class=\"content--detail-body\"><p>詳しい内容は次の通りです。物価の上昇に対応するための取り組みが示されました。</p></section>"
    val nb = NhkParse.newsArticle(np)!!
    check("news article parts", nb.startsWith("首相は") && "\n\n詳しい" in nb)
    // English (assumed shapes)
    val longP = "This is a long paragraph about the news of the day, written in simple words for learners of English."
    val bneHome = """<a href="2410/241027-ai-boom.html">AI</a><a href="https://breakingnewsenglish.com/2410/241027-ai-boom-3.html">x</a><a href="/2410/241026-cats-and-dogs.html">Cats</a><a href="/about.html">About</a><a href="https://other.com/2410/241025-no.html">no</a>"""
    val bl = EnglishParse.bneList(bneHome)
    check("bne list dedupes levels, skips others", bl.map { it.url } == listOf("https://breakingnewsenglish.com/2410/241027-ai-boom.html", "https://breakingnewsenglish.com/2410/241026-cats-and-dogs.html"))
    check("bne title from slug", bl[0].title == "Ai boom" && bl[1].title == "Cats and dogs")
    check("bne article by id", EnglishParse.bneArticle("<div id=\"article\"><p>$longP</p><p>$longP</p></div><div id=\"quiz\">Quiz</div>")!!.startsWith("This is a long"))
    check("bne article fallback to paragraphs", EnglishParse.bneArticle("<p>$longP</p><p>nav</p><p>$longP</p>")!!.contains("\n\n"))
    check("bne article too short", EnglishParse.bneArticle("<p>short</p>") == null)
    val nilHome = """<a href="https://www.newsinlevels.com/products/storm-hits-city-level-1/">a</a><a href="https://www.newsinlevels.com/products/storm-hits-city-level-3/">c</a><a href="https://www.newsinlevels.com/products/storm-hits-city-level-2/">b</a><a href="https://www.newsinlevels.com/products/new-bridge-level-2/">d</a><a href="/contact/">x</a>"""
    val nl2 = EnglishParse.nilList(nilHome)
    check("nil list: stories with levels in order", nl2.size == 4 && nl2[0].summary.endsWith("version 1 of 3") && nl2[2].summary.endsWith("version 3 of 3") && nl2[3].title == "New bridge")
    check("nil list max stories", EnglishParse.nilList(nilHome, 1).size == 3)
    check("nil article", EnglishParse.nilArticle("<div id=\"nContent\"><p>$longP</p><p>$longP</p></div>") != null)
    val voaHome = """<a href="/a/rising-seas-threaten-coasts/7800001.html"><h4>Rising Seas Threaten Coasts</h4></a><a href="/a/rising-seas-threaten-coasts/7800001.html">again</a><a href="/a/short-one/7800002.html">Go</a><a href="/z/3521">Programs</a>"""
    val vl = EnglishParse.voaList(voaHome)
    check("voa list", vl.size == 2 && vl[0].title == "Rising Seas Threaten Coasts" && vl[1].title == "Short one")
    check("voa article", EnglishParse.voaArticle("<div id=\"article-content\"><div class=\"wsw\"><p>$longP</p><p>$longP</p></div></div>") != null)
    check("voa article by class", EnglishParse.voaArticle("<div class=\"wsw other\"><p>$longP</p><p>$longP</p></div>") != null)
    // Indonesian (assumed shapes)
    val idxml = """<rss><channel><item><title>Banjir di Jakarta</title><link>https://www.bbc.com/indonesia/articles/c123?at_medium=RSS</link><description>Ringkasan banjir.</description></item><item><title>Iklan</title><link>https://other.com/x</link></item></channel></rss>"""
    val bi = IndonesianParse.bbcList(idxml)
    check("bbc id list strips query, filters host", bi.size == 1 && bi[0].url == "https://www.bbc.com/indonesia/articles/c123" && bi[0].lang == "id" && bi[0].summary == "Ringkasan banjir.")
    val idP = "Hujan deras yang turun sejak semalam menyebabkan banjir di sejumlah wilayah ibu kota dan sekitarnya."
    val bbcPage = "<main><div data-component=\"text-block\"><p>$idP</p></div><div data-component=\"text-block\"><p>$idP</p></div></main>"
    check("bbc id article text-blocks", IndonesianParse.bbcArticle(bbcPage)!!.contains("\n\n"))
    check("bbc id article fallback", IndonesianParse.bbcArticle("<p>$idP</p><p>$idP</p>") != null)
    val dx = """<rss><channel><item><title>Berita &amp; Kabar</title><link>https://news.detik.com/berita/d-1/slug?single=1</link><description>x</description></item></channel></rss>"""
    val dl = IndonesianParse.detikList(dx)
    check("detik list", dl.size == 1 && dl[0].title == "Berita & Kabar" && dl[0].url == "https://news.detik.com/berita/d-1/slug")
    val dp = "<div class=\"detail__body-text itp_bodycontent\"><p>ADVERTISEMENT</p><p>SCROLL TO CONTINUE WITH CONTENT</p><p>$idP</p><p>Baca juga: lain</p><p>$idP</p></div>"
    val da = IndonesianParse.detikArticle(dp)!!
    check("detik article cleaned", !da.contains("ADVERTISEMENT") && !da.contains("Baca juga") && da.contains("Hujan deras"))
    // Thai (assumed shapes)
    val thP = "ฝนตกหนักในกรุงเทพมหานครตั้งแต่เมื่อคืนทำให้เกิดน้ำท่วมขังในหลายพื้นที่และการจราจรติดขัดทั่วเมือง"
    val pbsHome = """<a href="/news/content/353001">ฝนตกหนักทั่วกรุงเทพฯ น้ำท่วมขังหลายพื้นที่</a><a href="https://www.thaipbs.or.th/news/content/353001?utm=x">ซ้ำ</a><a href="/news/content/353002"><span>ราคาน้ำมันปรับขึ้นอีกลิตรละ 50 สตางค์</span></a><a href="/news/categories/politics">การเมือง</a><a href="/news/content/353003">สั้น</a>"""
    val pl = ThaiParse.pbsList(pbsHome)
    check("pbs list", pl.size == 2 && pl[0].url == "https://www.thaipbs.or.th/news/content/353001" && pl[0].lang == "th" && pl[1].title.startsWith("ราคาน้ำมัน"))
    check("pbs article container", ThaiParse.pbsArticle("<div class=\"news-content\"><p>$thP</p><p>$thP</p></div>") != null)
    check("pbs article fallback", ThaiParse.pbsArticle("<nav><p>เมนู</p></nav><p>$thP</p><p>$thP</p>") != null)
    val txml = """<rss><channel><item><title>ข่าวด่วน</title><link>https://www.bbc.com/thai/articles/c9?at=RSS</link><description>สรุป</description></item></channel></rss>"""
    val tb = ThaiParse.bbcList(txml)
    check("bbc th list", tb.size == 1 && tb[0].lang == "th" && tb[0].url == "https://www.bbc.com/thai/articles/c9")
    check("bbc th article", ThaiParse.bbcArticle("<div data-component=\"text-block\"><p>$thP</p></div><div data-component=\"text-block\"><p>$thP</p></div>") != null)
    println("ALL OK")
}
