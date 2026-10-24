package app.fluvour.ocr

import android.graphics.Bitmap
import android.util.Base64
import app.fluvour.ai.AiProvider
import app.fluvour.ai.AiService
import app.fluvour.data.AuthRepository
import app.fluvour.language.Languages
import app.fluvour.net.Backend
import app.fluvour.net.Http
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class OcrOutcome {
    data class Ok(val page: OcrPage) : OcrOutcome()
    data class Failed(val message: String) : OcrOutcome()
}

/**
 * Reads text from a screenshot (Phase 2B step 2).
 *  1. ML Kit on the device: Latin (en, es, fr, de, vi, id), Chinese, Japanese, Korean. Free, offline, nothing leaves the phone.
 *  2. Gemini vision when ML Kit cannot read the language (Thai) or found no text, and the user allows it: the screenshot is sent to Gemini.
 *     Goes straight to Gemini with your own keys, or through the ai-proxy when you have none.
 * Not included: manga-ocr (an on-device model for Japanese comics). ML Kit Japanese plus the Gemini fallback cover it for now.
 */
@Singleton
class OcrEngine @Inject constructor(private val ai: AiService, private val auth: AuthRepository) {
    private val recognizers = HashMap<String, TextRecognizer>()

    private fun recognizer(lang: String): TextRecognizer? = synchronized(recognizers) {
        val group = when (lang) { "ja", "zh", "ko" -> lang; "th" -> return null; else -> "latin" }
        recognizers.getOrPut(group) {
            TextRecognition.getClient(when (group) {
                "ja" -> JapaneseTextRecognizerOptions.Builder().build()
                "zh" -> ChineseTextRecognizerOptions.Builder().build()
                "ko" -> KoreanTextRecognizerOptions.Builder().build()
                else -> TextRecognizerOptions.DEFAULT_OPTIONS
            })
        }
    }

    suspend fun recognize(bitmap: Bitmap, lang: String, allowAi: Boolean): OcrOutcome {
        val rec = recognizer(lang)
        var mlMessage: String? = null
        if (rec != null) {
            try {
                val blocks = toBlocks(mlkit(rec, bitmap), lang)
                if (blocks.isNotEmpty()) return OcrOutcome.Ok(OcrPage(blocks, "mlkit", bitmap.width, bitmap.height))
            } catch (e: Exception) { mlMessage = "The on-device reader failed (${e.message ?: "unknown error"})." }
        }
        val name = Languages.byCode(lang).name
        if (!allowAi) {
            return OcrOutcome.Failed(
                if (rec == null) "$name cannot be read on the device. Turn on \"Let AI read the screen\" in Screen lookup settings."
                else mlMessage ?: "No text found. Try Select area on a smaller part of the screen."
            )
        }
        return gemini(bitmap, lang, name)
    }

    // ---- ML Kit ----
    private suspend fun mlkit(rec: TextRecognizer, bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        rec.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun box(r: android.graphics.Rect) = OcrBox(r.left, r.top, r.right, r.bottom)

    private fun toBlocks(t: Text, lang: String): List<OcrBlock> = t.textBlocks.mapNotNull { b ->
        val lines = b.lines.mapNotNull { l ->
            val r = l.boundingBox ?: return@mapNotNull null
            OcrLine(l.text.replace('\n', ' ').trim(), box(r), l.elements.mapNotNull { e -> e.boundingBox?.let { OcrPiece(e.text, box(it)) } })
        }
        OcrBlock.of(lines, OcrGeometry.joiner(lang))
    }

    // ---- Gemini vision ----
    private suspend fun gemini(bitmap: Bitmap, lang: String, name: String): OcrOutcome {
        val b64 = jpegBase64(bitmap)
        val prompt = "Read the $name text in this screenshot. Return JSON only, in this shape: " +
            "{\"blocks\":[{\"text\":\"...\",\"box_2d\":[ymin,xmin,ymax,xmax]}]}. " +
            "One block per speech bubble, caption, subtitle or paragraph. box_2d is normalised to 0-1000. " +
            "Copy the text exactly as written: no translation, no romanisation, no added punctuation. Ignore text in other languages and ignore interface buttons."
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 4000).put("responseMimeType", "application/json"))
            .toString()
        val answer: String = if (ai.pool(AiProvider.GEMINI).hasKeys()) {
            val r = ai.geminiRaw(body)
            r.text ?: return OcrOutcome.Failed(r.message ?: "Gemini could not read the screen.")
        } else {
            if (!Backend.configured) return OcrOutcome.Failed("No AI key yet. Add a Gemini key in Settings > AI keys.")
            val token = auth.validToken() ?: return OcrOutcome.Failed("Add a Gemini key in Settings > AI keys, or sign in (Settings > Account).")
            val payload = JSONObject().put("provider", "gemini").put("body", JSONObject(body)).toString()
            val r = try {
                Http.request("POST", "${Backend.url}/functions/v1/ai-proxy", Backend.headers(token), payload, readTimeoutMs = 90_000)
            } catch (e: Exception) { return OcrOutcome.Failed("No connection.") }
            if (r.code == 429) return OcrOutcome.Failed("The shared AI is at its limit. Add your own key in Settings > AI keys.")
            if (!r.ok) return OcrOutcome.Failed("The AI could not read the screen (${r.code}).")
            app.fluvour.reading.LookupRepository.answerText(r.body) ?: return OcrOutcome.Failed("The AI returned no text.")
        }
        val blocks = OcrJson.fromGemini(answer, bitmap.width, bitmap.height)
            ?: return OcrOutcome.Failed("The AI answer could not be read. Try again.")
        return OcrOutcome.Ok(OcrPage(blocks, "gemini", bitmap.width, bitmap.height))
    }

    /** JPEG, longest side at most 1600 px: keeps the upload small without making small print unreadable. */
    private fun jpegBase64(src: Bitmap): String {
        val scale = 1600f / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (bmp !== src) bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
