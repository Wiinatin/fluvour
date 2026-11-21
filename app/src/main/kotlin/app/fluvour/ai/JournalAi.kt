package app.fluvour.ai

import app.fluvour.reading.MiniJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class JournalFix(val wrong: String, val right: String, val why: String)

/** One journal entry. [corrected] is null until the AI has checked it. [words] are the words worth learning (a card exists when cardId is set). */
data class JournalEntry(
    val id: String, val lang: String, val createdAt: Long, val updatedAt: Long, val text: String,
    val corrected: String? = null, val fixes: List<JournalFix> = emptyList(), val words: List<NewWord> = emptyList()
)

/** The words for the journal check and the reading of its answer. Pure parts are checked on a plain JVM. */
object JournalPrompt {
    const val TEMPERATURE = 0.3
    const val MAX_TOKENS = 3000

    fun build(lang: String, own: String, level: String, text: String): String {
        val lv = if (level.isBlank()) "" else " The learner's level is $level; keep the wording at or just above that level."
        return "A learner of $lang wrote this journal entry. It may contain mistakes.\n\nEntry:\n$text\n\n" +
            "Write the corrected version: minimal changes, the learner's meaning and style kept, only real mistakes and unnatural wording fixed.$lv " +
            "List each fix as the exact wrong phrase from the entry, the corrected phrase, and a one-line reason in $own (at most 12 fixes). " +
            "Then list up to 6 words worth learning from the corrected version, in dictionary form, with a short meaning in $own and one short $lang example. " +
            "Return JSON only, in this shape: {\"corrected\":\"...\",\"fixes\":[{\"wrong\":\"...\",\"right\":\"...\",\"why\":\"...\"}],\"newWords\":[{\"word\":\"...\",\"meaning\":\"...\",\"example\":\"...\"}]}. " +
            "If the entry has no mistakes, return it unchanged as \"corrected\" and an empty fixes list."
    }

    class Parsed(val corrected: String, val fixes: List<JournalFix>, val words: List<NewWord>)

    fun parse(reply: String): Parsed? {
        val a = reply.indexOf('{'); val b = reply.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val body = reply.substring(a, b + 1)
        val m = try { MiniJson.parse(body) as? Map<*, *> } catch (e: Exception) {
            try { MiniJson.parse(body.replace(Regex(",\\s*([}\\]])"), "$1")) as? Map<*, *> } catch (e2: Exception) { null }
        } ?: return null
        val corrected = (m["corrected"] as? String)?.trim()?.ifBlank { null } ?: return null
        val fixes = (m["fixes"] as? List<*>).orEmpty().mapNotNull { x ->
            val f = x as? Map<*, *> ?: return@mapNotNull null
            val w = (f["wrong"] as? String)?.trim().orEmpty(); val r = (f["right"] as? String)?.trim().orEmpty()
            if (w.isEmpty() || r.isEmpty() || w == r) null else JournalFix(w, r, (f["why"] as? String)?.trim().orEmpty())
        }.take(12)
        val seen = HashSet<String>()
        val words = (m["newWords"] as? List<*>).orEmpty().mapNotNull { x ->
            val w = x as? Map<*, *> ?: return@mapNotNull null
            val word = (w["word"] as? String)?.trim().orEmpty(); val me = (w["meaning"] as? String)?.trim().orEmpty()
            if (word.isEmpty() || me.isEmpty() || !seen.add(word.lowercase())) null else NewWord(word, me, (w["example"] as? String)?.trim()?.ifBlank { null })
        }.take(8)
        return Parsed(corrected, fixes, words)
    }
}

/** Journal entries kept in one small file on the phone (no database change). Newest first. */
class JournalStore(private val file: File) {
    @Synchronized fun all(): List<JournalEntry> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { read(arr.getJSONObject(it)) }.sortedByDescending { it.updatedAt }
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun put(e: JournalEntry) {
        val list = all().filter { it.id != e.id } + e
        write(list)
    }

    @Synchronized fun delete(id: String) { write(all().filter { it.id != id }) }

    private fun write(list: List<JournalEntry>) {
        try {
            val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString()); tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    private fun toJson(e: JournalEntry) = JSONObject().put("id", e.id).put("lang", e.lang).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt)
        .put("text", e.text).put("corrected", e.corrected ?: JSONObject.NULL)
        .put("fixes", JSONArray().also { a -> e.fixes.forEach { a.put(JSONObject().put("wrong", it.wrong).put("right", it.right).put("why", it.why)) } })
        .put("words", JSONArray().also { a -> e.words.forEach { a.put(JSONObject().put("word", it.word).put("meaning", it.meaning).put("example", it.example ?: JSONObject.NULL).put("cardId", it.cardId ?: JSONObject.NULL)) } })

    private fun read(o: JSONObject): JournalEntry {
        val fx = o.optJSONArray("fixes"); val ws = o.optJSONArray("words")
        return JournalEntry(
            o.getString("id"), o.optString("lang"), o.optLong("createdAt"), o.optLong("updatedAt"), o.optString("text"),
            if (o.isNull("corrected")) null else o.optString("corrected"),
            (0 until (fx?.length() ?: 0)).map { val f = fx!!.getJSONObject(it); JournalFix(f.optString("wrong"), f.optString("right"), f.optString("why")) },
            (0 until (ws?.length() ?: 0)).map { val w = ws!!.getJSONObject(it)
                NewWord(w.optString("word"), w.optString("meaning"), if (w.isNull("example")) null else w.optString("example"), if (w.isNull("cardId")) null else w.optString("cardId")) }
        )
    }
}
