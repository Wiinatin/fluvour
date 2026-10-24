package app.fluvour.srs

import kotlin.test.Test
import kotlin.test.assertEquals

class AnkiExportTest {
    private val header = "#separator:tab\n#html:true\n#notetype:Basic\n#tags column:3\n"

    @Test fun emptyListIsJustTheHeader() = assertEquals(header, AnkiExport.toText(emptyList()))

    @Test fun joinsMeaningExampleAndNotes() {
        val out = AnkiExport.toText(listOf(AnkiExport.Card("犬", "dog", "犬が好きです", "common noun", "ja")))
        assertEquals(header + "犬\tdog<br><br>犬が好きです<br><br>common noun\tja\n", out)
    }

    @Test fun skipsBlankExampleAndNotes() {
        val out = AnkiExport.toText(listOf(AnkiExport.Card("a", "b", " ", null, "th")))
        assertEquals(header + "a\tb\tth\n", out)
    }

    @Test fun escapesHtmlTabsAndNewlines() {
        val out = AnkiExport.toText(listOf(AnkiExport.Card("a<b>", "x\ty\nz & w", null, null, "en us")))
        assertEquals(header + "a&lt;b&gt;\tx y<br>z &amp; w\ten_us\n", out)
    }
}
