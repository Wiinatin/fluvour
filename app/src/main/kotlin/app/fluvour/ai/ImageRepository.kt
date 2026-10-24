package app.fluvour.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.fluvour.ui.art.ArtPalettes
import app.fluvour.data.AuthRepository
import app.fluvour.data.CardEntity
import app.fluvour.data.CardRepository
import app.fluvour.net.Backend
import app.fluvour.net.Http
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** null message means success. */
data class ImageResult(val message: String?)

/**
 * One AI picture per word, generated through the ai-proxy Edge Function (the key never lives in the app),
 * shrunk to a small JPEG and cached on the phone. Needs a signed-in account; without one cards stay text-only.
 */
@Singleton
class ImageRepository @Inject constructor(
    @ApplicationContext private val ctx: Context, private val auth: AuthRepository, private val cardRepo: CardRepository,
    private val ai: AiService
) {
    private val gate = Mutex()   // one request at a time keeps us inside the free-tier rate limits

    suspend fun generate(cardId: String): ImageResult = gate.withLock {
        val card = cardRepo.get(cardId) ?: return@withLock ImageResult("Card not found.")
        val prompt = promptFor(card)
        val data: String
        if (ai.hasImageKeys()) {
            // Your own Cloudflare / Hugging Face keys (Settings > AI keys): direct, rotated, no sign-in and no app-side limit.
            val (img, err) = ai.image(prompt)
            data = img ?: return@withLock ImageResult(err ?: "Image failed.")
        } else {
            if (!Backend.configured) return@withLock ImageResult("No picture key yet. Add a Cloudflare key in Settings > AI keys (free).")
            val token = auth.validToken() ?: return@withLock ImageResult("Add a Cloudflare key in Settings > AI keys (free), or sign in (Settings > Account).")
            val body = JSONObject().put("provider", "image").put("body", JSONObject().put("prompt", prompt)).toString()
            val r = try {
                Http.request("POST", "${Backend.url}/functions/v1/ai-proxy", Backend.headers(token), body, readTimeoutMs = 90_000)
            } catch (e: Exception) { return@withLock ImageResult("No connection.") }
            when {
                r.code == 429 -> return@withLock ImageResult("The shared AI is at its limit. Add your own key in Settings > AI keys.")
                !r.ok -> return@withLock ImageResult("Image failed (${r.code}).")
            }
            data = try { JSONObject(r.body).getString("data") } catch (e: Exception) { return@withLock ImageResult("The picture could not be read.") }
        }
        val path = try { save(card.id, data) } catch (e: Exception) { return@withLock ImageResult("The picture could not be read.") }
        cardRepo.setImage(card.id, path, prompt)
        ImageResult(null)
    }

    /** The editorial picture style (ImageStyle), painted in the inks of the card's language so it matches that language's covers and marks. */
    private fun promptFor(card: CardEntity): String {
        val p = ArtPalettes.of(card.languageCode)
        fun hex(c: Color) = "%06X".format(c.toArgb() and 0xFFFFFF)
        return ImageStyle.prompt(card.meaning, card.word, ImageInks(hex(p.il1), hex(p.il2), hex(p.il3), hex(p.il4), hex(p.il5)))
    }

    /** Decodes, shrinks to at most 512 px on the long side and stores as JPEG. Returns the file path. */
    private suspend fun save(cardId: String, base64: String): String = withContext(Dispatchers.IO) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("bad image")
        val scale = 512f / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "$cardId.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        file.absolutePath
    }
}
