package app.fluvour.ai

import app.fluvour.reading.MiniJson

/** What was said, as Gemini heard it: [text] keeps the speaker's mistakes, [substituted] are the words that were not in the target language. */
data class Transcript(val text: String, val substituted: List<String>)

/**
 * One word the speaker did not know, in the target language, with its meaning in the speaker's own language.
 * [cardId] is the flashcard made for it, or null when no card was made (not yet, skipped because the word already had one, or removed).
 */
data class NewWord(val word: String, val meaning: String, val example: String? = null, val cardId: String? = null)

/** The natural version of what was said, and the new words that came out of the substitutions. */
data class Improved(val improved: String, val newWords: List<NewWord>)

/** A piece of the transcript, for drawing the flagged words in a different style. */
data class Segment(val text: String, val marked: Boolean)

/** The words for the improve request (step 2 of the speaking pipeline). Pure, so it is checked on a plain JVM. */
object ImprovePrompt {
    const val TEMPERATURE = 0.3
    /** A 3 minute talk plus ten words with examples does not fit the default 700. */
    const val MAX_TOKENS = 2500
    const val MAX_WORDS = 12

    /** [lang] is the language practised, [own] the speaker's own language, [level] a code such as "B1" (may be empty). */
    fun build(lang: String, own: String, level: String, transcript: String, substituted: List<String>): String {
        val subs = if (substituted.isEmpty()) "none" else substituted.joinToString(", ") { "\"$it\"" }
        val lv = if (level.isBlank()) "" else " The speaker's level is $level; keep the wording at or just above that level."
        return "A learner of $lang spoke this out loud (transcribed exactly, with mistakes). Words they said in another language because they did not know the $lang word: $subs.\n\n" +
            "Transcript:\n$transcript\n\n" +
            "Write the improved version: what a natural speaker of $lang would say to express the same thing. Keep the speaker's meaning and order of ideas. Do not add new ideas. Fix grammar, word choice and the substituted words.$lv " +
            "Then list the new words: the $lang word for each substituted word (as used in the improved version, in its dictionary form), with its meaning in $own and, if it helps, one short $lang example sentence. " +
            "At most $MAX_WORDS words. Skip words that are not really new vocabulary. " +
            "Return JSON only, in this shape: {\"improved\":\"...\",\"newWords\":[{\"word\":\"...\",\"meaning\":\"...\",\"example\":\"...\"}]}. If there is nothing to improve, return the transcript as \"improved\" and an empty list."
    }
}

/** The words for the topic suggestions and the cue words shown on the record screen. Pure. */
object TopicPrompt {
    const val TEMPERATURE = 0.8
    const val MAX_TOKENS = 600
    fun suggest(lang: String, level: String): String =
        "Suggest 6 everyday topics that a learner of $lang could talk about out loud for one or two minutes." +
        (if (level.isBlank()) "" else " The learner's level is $level.") +
        " Write each title in $lang, at most 6 words, no numbering. Return JSON only: an array of 6 strings."
    fun cues(title: String, lang: String, own: String, level: String): String =
        "A learner of $lang will speak out loud about the topic \"$title\"." +
        (if (level.isBlank()) "" else " Their level is $level.") +
        " Give 3 short cue phrases in $lang (questions or key words) that help them say something about it, each with its meaning in $own. " +
        "Return JSON only, in this shape: [{\"cue\":\"...\",\"meaning\":\"...\"}]."
}

/** Reads the two Gemini/AI replies of the speaking pipeline and the JSON the app stores. Never throws. */
/**
 * A check on a transcript before it is shown, because the model sometimes writes Vietnamese or Latin-letter Thai.
 * Pure, so it is checked on a plain JVM. [problems] gives a reason per problem found; an empty list means nothing looks wrong.
 */
object TranscriptCheck {
    /** Scripts whose letters never belong in a transcript of this practised language (besides Thai and basic Latin for English). */
    private fun scripts(lang: String): Set<Character.UnicodeScript> = when (lang) {
        "Japanese" -> setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN)
        "Chinese" -> setOf(Character.UnicodeScript.HAN)
        "Korean" -> setOf(Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN)
        else -> emptySet()
    }

    /** Languages written in Latin letters with marks that English does not use (Spanish, French, German, Vietnamese, Indonesian). */
    private fun latinWithMarks(lang: String) = lang in setOf("Spanish", "French", "German", "Vietnamese", "Indonesian")

    private fun isBasicLatin(c: Int) = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code

    fun problems(text: String, substituted: List<String>, lang: String): List<String> {
        val out = ArrayList<String>()
        val own = scripts(lang)
        val marks = latinWithMarks(lang)
        var foreign = false
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i); i += Character.charCount(c)
            if (!Character.isLetter(c)) continue
            val sc = Character.UnicodeScript.of(c)
            val fine = when (sc) {
                Character.UnicodeScript.THAI -> true
                Character.UnicodeScript.LATIN -> isBasicLatin(c) || marks
                else -> sc in own
            }
            if (!fine) { foreign = true; break }
        }
        if (foreign) out += "letters from a language that is not allowed"
        // Thai (or another non-Latin language) written in Latin letters: a Latin word that was not flagged as a swapped-in word.
        if (lang == "Thai" || own.isNotEmpty()) {
            val flagged = substituted.flatMap { it.split(Regex("\\s+")) }.map { it.lowercase() }.toSet()
            val stray = Regex("[A-Za-z]+").findAll(text).map { it.value.lowercase() }.filter { it !in flagged }.toList()
            if (stray.isNotEmpty()) out += "Latin words not flagged: " + stray.distinct().take(5).joinToString(", ")
        }
        return out
    }
}

object SpeakingParse {
    private const val MAX_NEW = 20

    /** Reply of the transcribe call: `{"transcript":"...","substituted":["..."]}`. Silence gives an empty transcript, not null. Null only when the reply cannot be read. */
    fun transcript(reply: String): Transcript? {
        val m = obj(reply) ?: return null
        val text = ((m["transcript"] ?: m["text"]) as? String)?.trim() ?: return null
        val marks = (m["substituted"] as? List<*>).orEmpty().mapNotNull { strOrWord(it) }
        // A mark that is not in the transcript could not be drawn, so it is dropped.
        return Transcript(text, marks.filter { text.contains(it) }.distinct())
    }

    /** Reply of the improve call: `{"improved":"...","newWords":[{"word","meaning","example"}]}`. Null when there is no improved text. */
    fun improved(reply: String): Improved? {
        val m = obj(reply) ?: return null
        val text = (m["improved"] as? String)?.trim()?.ifBlank { null } ?: return null
        val seen = HashSet<String>()
        val words = ArrayList<NewWord>()
        for (item in (m["newWords"] as? List<*> ?: m["new_words"] as? List<*>).orEmpty()) {
            val w = item as? Map<*, *> ?: continue
            val word = (w["word"] as? String)?.trim().orEmpty()
            val meaning = (w["meaning"] as? String)?.trim().orEmpty()
            if (word.isEmpty() || meaning.isEmpty() || !seen.add(word.lowercase())) continue
            words += NewWord(word, meaning, (w["example"] as? String)?.trim()?.ifBlank { null })
            if (words.size >= MAX_NEW) break
        }
        return Improved(text, words)
    }

    /** The transcript cut into pieces, the flagged words marked, for display. Every occurrence of a flagged word is marked. */
    fun segments(text: String, marked: List<String>): List<Segment> {
        val ranges = ArrayList<IntRange>()
        for (w in marked) {
            if (w.isEmpty()) continue
            var i = text.indexOf(w)
            while (i >= 0) { ranges += i until i + w.length; i = text.indexOf(w, i + w.length) }
        }
        if (ranges.isEmpty()) return if (text.isEmpty()) emptyList() else listOf(Segment(text, false))
        ranges.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in ranges) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.size - 1] = last.first..maxOf(last.last, r.last) else merged += r
        }
        val out = ArrayList<Segment>()
        var pos = 0
        for (r in merged) {
            if (r.first > pos) out += Segment(text.substring(pos, r.first), false)
            out += Segment(text.substring(r.first, r.last + 1), true)
            pos = r.last + 1
        }
        if (pos < text.length) out += Segment(text.substring(pos), false)
        return out
    }

    /** Topic titles from the suggestion reply: a JSON array of strings (or of objects with a "title"). Blank and repeated titles are dropped. */
    fun titles(reply: String): List<String> {
        val list = arr(reply) ?: return emptyList()
        return list.mapNotNull { x -> ((x as? String) ?: (x as? Map<*, *>)?.get("title") as? String)?.trim()?.ifBlank { null } }
            .distinctBy { it.lowercase() }.take(8)
    }

    /** Cue phrases from the cue reply: (cue in the target language, meaning in the speaker's language or ""). At most 4. */
    fun cues(reply: String): List<Pair<String, String>> {
        val list = arr(reply) ?: return emptyList()
        return list.mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull (x as? String)?.trim()?.ifBlank { null }?.let { it to "" }
            val cue = (m["cue"] as? String)?.trim()?.ifBlank { null } ?: return@mapNotNull null
            cue to ((m["meaning"] as? String)?.trim().orEmpty())
        }.take(4)
    }

    // ---- stored JSON (columns `substituted` and `newWords` of speaking_versions) ----
    fun substitutedJson(words: List<String>): String = words.joinToString(",", "[", "]") { quote(it) }

    fun newWordsJson(words: List<NewWord>): String = words.joinToString(",", "[", "]") { w ->
        "{\"word\":${quote(w.word)},\"meaning\":${quote(w.meaning)}" + (w.example?.let { ",\"example\":${quote(it)}" } ?: "") + (w.cardId?.let { ",\"cardId\":${quote(it)}" } ?: "") + "}"
    }

    fun substitutedFrom(json: String?): List<String> {
        if (json == null) return emptyList()
        return try { (MiniJson.parse(json) as? List<*>).orEmpty().mapNotNull { strOrWord(it) } } catch (e: Exception) { emptyList() }
    }

    fun newWordsFrom(json: String?): List<NewWord> {
        if (json == null) return emptyList()
        return try {
            (MiniJson.parse(json) as? List<*>).orEmpty().mapNotNull { x ->
                val m = x as? Map<*, *> ?: return@mapNotNull null
                val w = (m["word"] as? String)?.ifBlank { null } ?: return@mapNotNull null
                val me = (m["meaning"] as? String)?.ifBlank { null } ?: return@mapNotNull null
                NewWord(w, me, (m["example"] as? String)?.ifBlank { null }, (m["cardId"] as? String)?.ifBlank { null })
            }
        } catch (e: Exception) { emptyList() }
    }

    // ---- helpers ----
    private fun strOrWord(x: Any?): String? =
        ((x as? String) ?: (x as? Map<*, *>)?.let { (it["word"] ?: it["text"]) as? String })?.trim()?.ifBlank { null }

    /** The first `{` to the last `}` of the reply (code fences and chatter around it are ignored); one retry without trailing commas. */
    private fun obj(reply: String): Map<*, *>? {
        val a = reply.indexOf('{'); val b = reply.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val body = reply.substring(a, b + 1)
        return parse(body) ?: parse(body.replace(Regex(",\\s*([}\\]])"), "$1"))
    }
    private fun arr(reply: String): List<*>? {
        val a = reply.indexOf('['); val b = reply.lastIndexOf(']')
        if (a < 0 || b <= a) return null
        val body = reply.substring(a, b + 1)
        return try { MiniJson.parse(body) as? List<*> } catch (e: Exception) {
            try { MiniJson.parse(body.replace(Regex(",\\s*([}\\]])"), "$1")) as? List<*> } catch (e2: Exception) { null }
        }
    }
    private fun parse(s: String): Map<*, *>? = try { MiniJson.parse(s) as? Map<*, *> } catch (e: Exception) { null }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
