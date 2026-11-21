package app.fluvour.reading.articles

/** Plain-JVM helpers to get readable text out of a web page. No HTML library: a few careful patterns. Layouts change, so callers try several class names. */
object HtmlText {
    fun decode(s: String): String {
        var r = Regex("&#x([0-9a-fA-F]+);").replace(s) { m -> runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault("") }
        r = Regex("&#(\\d+);").replace(r) { m -> runCatching { String(Character.toChars(m.groupValues[1].toInt(10))) }.getOrDefault("") }
        return r.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }

    /** Drops furigana: the reading inside `<rt>` and the brackets in `<rp>`, so only the written text is left. */
    fun stripRuby(html: String): String =
        html.replace(Regex("(?is)<rt\\b.*?</rt>"), "").replace(Regex("(?is)<rp\\b.*?</rp>"), "")

    /** Page or fragment to plain text, one line per block, blank lines kept to one. */
    fun text(html: String): String {
        var s = html.replace(Regex("(?is)<!--.*?-->"), "")
        s = s.replace(Regex("(?is)<(script|style|noscript|svg)\\b.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|h[1-6]|tr|section|article)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]*>"), "")
        s = decode(s)
        val lines = s.split("\n").map { it.replace(Regex("[ \\t\\u00A0\\u3000]+"), " ").trim() }
        return lines.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** Inner HTML of the first element whose [attr] attribute (class by default, or id) contains [part], or null. */
    fun element(html: String, part: String, attr: String = "class"): String? {
        val open = Regex("<([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*\\s" + attr + "\\s*=\\s*[\"'][^\"']*" + Regex.escape(part) + "[^\"']*[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return null
        if (open.value.endsWith("/>")) return null
        val tag = open.groupValues[1]
        val tagRe = Regex("<(/?)" + Regex.escape(tag) + "\\b[^>]*>", RegexOption.IGNORE_CASE)
        val start = open.range.last + 1
        var depth = 1
        for (m in tagRe.findAll(html, start)) {
            if (m.value.endsWith("/>")) continue
            if (m.groupValues[1] == "/") depth-- else depth++
            if (depth == 0) return html.substring(start, m.range.first)
        }
        return html.substring(start)
    }

    /** Text of all `<p>` blocks, the fallback when no article container is found. */
    fun paragraphs(html: String): String =
        Regex("(?is)<p\\b[^>]*>(.*?)</p>").findAll(html).map { text(it.groupValues[1]) }.filter { it.isNotBlank() }.joinToString("\n\n")
}
