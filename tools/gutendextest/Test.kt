import app.fluvour.reading.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "ok " else "FAIL ") + name); if (!ok) fails++ }

fun main() {
    val good = """{"count":2,"next":"https://gutendex.com/books/?page=2","previous":null,"results":[
      {"id":1342,"title":"Pride and Prejudice","authors":[{"name":"Austen, Jane","birth_year":1775,"death_year":1817}],
       "summaries":["Elizabeth Bennet meets Mr. Darcy."],"subjects":["Courtship -- Fiction","England -- Fiction"],
       "bookshelves":["Category: Novels","Best Books Ever Listings"],"languages":["en"],"copyright":false,"media_type":"Text",
       "formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/1342.epub3.images","text/html":"https://www.gutenberg.org/ebooks/1342.html.images",
       "image/jpeg":"https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg"},"download_count":900000},
      {"id":84,"title":"Frankenstein;\r\nOr, The Modern Prometheus","authors":[{"name":"Shelley, Mary Wollstonecraft"}],"languages":["en"],"copyright":null,
       "formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/84.epub.noimages","application/epub+zip; charset=utf-8":"https://www.gutenberg.org/ebooks/84.epub3.images"}}]}"""
    val p = GutendexParse.page(good)
    check("page parsed", p != null && p.books.size == 2)
    check("hasNext", p?.hasNext == true)
    val a = p!!.books[0]
    check("id", a.id == 1342L)
    check("author flipped", a.author == "Jane Austen")
    check("language", a.language == "en")
    check("epub with images", a.epubUrl.endsWith("1342.epub3.images"))
    check("cover url", a.coverUrl?.endsWith("pg1342.cover.medium.jpg") == true)
    check("tags", a.tags == listOf("Novels", "Best Books Ever Listings", "Courtship"))
    check("about", a.about == "Elizabeth Bennet meets Mr. Darcy.")
    check("downloads", a.downloads == 900000L)
    val b = p.books[1]
    check("title whitespace tidied", b.title == "Frankenstein; Or, The Modern Prometheus")
    check("noimages epub avoided", b.epubUrl.endsWith("84.epub3.images"))
    check("missing cover null", b.coverUrl == null)
    check("missing tags empty", b.tags.isEmpty())
    check("missing about null", b.about == null)

    // messy replies
    check("not json null", GutendexParse.page("<html>502 Bad Gateway</html>") == null)
    check("empty string null", GutendexParse.page("") == null)
    check("truncated null", GutendexParse.page("""{"results":[{"id":1,"title":"A""") == null)
    check("no results key null", GutendexParse.page("""{"detail":"Not found."}""") == null)
    check("results not a list null", GutendexParse.page("""{"results":"x"}""") == null)
    check("empty results", GutendexParse.page("""{"count":0,"next":null,"results":[]}""") == CatalogPage(emptyList(), false))
    check("no next", GutendexParse.page("""{"next":null,"results":[]}""")?.hasNext == false)
    val noEpub = """{"results":[{"id":5,"title":"T","formats":{"text/plain":"https://x/y.txt"}}]}"""
    check("book without epub dropped", GutendexParse.page(noEpub)?.books?.isEmpty() == true)
    val copy = """{"results":[{"id":6,"title":"T","copyright":true,"formats":{"application/epub+zip":"https://x/6.epub"}}]}"""
    check("copyrighted dropped", GutendexParse.page(copy)?.books?.isEmpty() == true)
    val rows = """{"results":[42,null,"x",{"title":"no id","formats":{}},{"id":7,"title":"Ok","formats":{"application/epub+zip":"https://x/7.epub"}}]}"""
    check("bad rows skipped, good kept", GutendexParse.page(rows)?.books?.map { it.id } == listOf(7L))
    val dup = """{"results":[{"id":7,"title":"Ok","formats":{"application/epub+zip":"https://x/7.epub"}},{"id":7,"title":"Ok","formats":{"application/epub+zip":"https://x/7.epub"}}]}"""
    check("duplicates removed", GutendexParse.page(dup)?.books?.size == 1)
    val minimal = """{"results":[{"id":8,"title":"Solo","formats":{"application/epub+zip":"https://x/8.epub"}}]}"""
    val m = GutendexParse.page(minimal)?.books?.first()
    check("minimal book author Unknown", m?.author == "Unknown")
    check("minimal book language empty", m?.language == "")
    check("blank title dropped", GutendexParse.page("""{"results":[{"id":9,"title":"  ","formats":{"application/epub+zip":"https://x/9.epub"}}]}""")?.books?.isEmpty() == true)
    check("ftp url ignored", GutendexParse.page("""{"results":[{"id":10,"title":"T","formats":{"application/epub+zip":"ftp://x/10.epub"}}]}""")?.books?.isEmpty() == true)
    check("two authors", GutendexParse.page("""{"results":[{"id":11,"title":"T","authors":[{"name":"Doe, John"},{"name":"Plato"}],"formats":{"application/epub+zip":"https://x/11.epub"}}]}""")?.books?.first()?.author == "John Doe, Plato")
    check("name no comma", GutendexParse.flipName("Homer") == "Homer")
    check("name trailing comma", GutendexParse.flipName("Homer,") == "Homer")
    check("long tag dropped", GutendexParse.page("""{"results":[{"id":12,"title":"T","bookshelves":["Category: ${"x".repeat(40)}"],"formats":{"application/epub+zip":"https://x/12.epub"}}]}""")?.books?.first()?.tags?.isEmpty() == true)
    check("unicode title", GutendexParse.page("""{"results":[{"id":13,"title":"\u5409\u7965\u5bfa","formats":{"application/epub+zip":"https://x/13.epub"}}]}""")?.books?.first()?.title == "吉祥寺")

    // urls
    check("url popular", GutendexParse.url("en", null) == "https://gutendex.com/books?languages=en")
    check("url search encoded", GutendexParse.url("EN", "war & peace") == "https://gutendex.com/books?languages=en&search=war+%26+peace")
    check("url page 2", GutendexParse.url("fr", " ", 2) == "https://gutendex.com/books?languages=fr&page=2")

    println(if (fails == 0) "ALL OK" else "$fails FAILED")
}
