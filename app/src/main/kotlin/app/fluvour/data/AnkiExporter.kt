package app.fluvour.data

import android.content.Context
import android.net.Uri
import app.fluvour.srs.AnkiExport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Writes all cards, in every language, to a file the user picked, in Anki's text import format. */
@Singleton
class AnkiExporter @Inject constructor(
    @ApplicationContext private val ctx: Context, private val cardRepo: CardRepository
) {
    /** Returns the number of cards written. */
    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val cards = cardRepo.allForExport()
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file")
        out.use { it.write(AnkiExport.toText(cards).toByteArray(Charsets.UTF_8)) }
        cards.size
    }
}
