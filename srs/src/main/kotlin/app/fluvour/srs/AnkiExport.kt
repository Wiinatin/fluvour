package app.fluvour.srs

/**
 * Builds a text file that Anki's File > Import reads (Anki 2.1.54 or newer reads the header lines).
 * Pure Kotlin so the same format can be reused on the Torbito web. Fields: Front = word, Back = meaning,
 * example and notes, Tags = language code. Review history and scheduling are not part of the file.
 */
object AnkiExport {
    data class Card(val word: String, val meaning: String, val example: String?, val notes: String?, val tag: String)

    fun toText(cards: List<Card>): String {
        val sb = StringBuilder("#separator:tab\n#html:true\n#notetype:Basic\n#tags column:3\n")
        for (c in cards) {
            val back = listOfNotNull(c.meaning, c.example?.takeIf { it.isNotBlank() }, c.notes?.takeIf { it.isNotBlank() })
                .joinToString("<br><br>") { field(it) }
            sb.append(field(c.word)).append('\t').append(back).append('\t').append(c.tag.trim().replace(' ', '_')).append('\n')
        }
        return sb.toString()
    }

    private fun field(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\t", " ").replace("\r\n", "\n").replace("\n", "<br>")
}
