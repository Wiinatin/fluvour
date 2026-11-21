package app.fluvour.audio

import app.fluvour.reading.MiniJson

/**
 * The list of voice packs, read from `packs.json` on the `voicepacks` release. Pure (no Android), so it is checked on a
 * plain JVM in tools/voicetest. Nothing here is hard-coded about a model: sizes, hashes, urls and speaker numbers all
 * come from the file, which the "Voice packs" workflow writes.
 *
 * File shape:
 * ```
 * { "version": 1, "packs": [ {
 *     "id": "kokoro-multi", "name": "Kokoro", "engine": "kokoro", "version": "1",
 *     "url": "kokoro-multi.zip", "size": 137000000, "sha256": "…",
 *     "languages": { "en": 3, "es": 28, "ja": 37 } } ] }
 * ```
 * `languages` maps a language code to the speaker number for it (0 for a single-voice model). `url` may be a file name
 * (resolved next to `packs.json`) or a full address.
 */
data class VoicePack(
    val id: String,
    val name: String,
    val engine: String,
    /** Changes when the pack file changes, so an installed older copy can be told apart. */
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
    /** Language code to speaker number. Order is the order in the file. */
    val languages: Map<String, Int>,
)

class VoiceCatalog(val packs: List<VoicePack>, val skipped: Int = 0) {
    fun byId(id: String): VoicePack? = packs.firstOrNull { it.id == id }

    /** Every pack that can speak [lang], in file order. */
    fun packsFor(lang: String): List<VoicePack> = normalize(lang).let { l -> packs.filter { l in it.languages } }

    /**
     * The pack to speak [lang] with: the first one in the file that is in [installed]. Null when none is installed.
     * The file order is the preference order, so moving a language to another model is a change to `packs.json`.
     */
    fun choose(lang: String, installed: Set<String>): VoicePack? = packsFor(lang).firstOrNull { it.id in installed }

    /** The speaker number [pack] uses for [lang], or null when it does not cover it. */
    fun speaker(pack: VoicePack, lang: String): Int? = pack.languages[normalize(lang)]

    /** Language codes that have at least one pack, for the Settings list. */
    fun languages(): List<String> = packs.flatMap { it.languages.keys }.distinct()
}

object VoicePacks {
    const val TAG = "voicepacks"
    const val INDEX = "packs.json"

    /** Sample sentence for the Test button, per language. Short, plain, and easy to judge by ear. */
    val SAMPLES: Map<String, String> = mapOf(
        "ja" to "今朝はとても早く起きました。",
        "th" to "สวัสดีครับ วันนี้อากาศดีมาก",
        "zh" to "今天天气很好。",
        "ko" to "오늘은 날씨가 정말 좋아요.",
        "en" to "The weather is lovely this morning.",
        "es" to "Hoy hace muy buen tiempo.",
        "fr" to "Il fait très beau ce matin.",
        "de" to "Heute ist das Wetter sehr schön.",
        "vi" to "Hôm nay thời tiết rất đẹp.",
        "id" to "Cuaca hari ini sangat bagus.",
    )

    fun sample(lang: String): String = SAMPLES[normalize(lang)] ?: SAMPLES.getValue("en")

    /**
     * The address of `packs.json` for a given extractor manifest address, which is
     * `https://github.com/OWNER/REPO/releases/download/extractor-latest/latest.json`. Both live on the same repo, so the
     * pack index is the same address with the release tag and file name swapped. Empty when [extractorManifestUrl] is
     * not in that shape (for example a custom override): packs are then off.
     */
    fun indexUrl(extractorManifestUrl: String): String {
        val tail = "/releases/download/extractor-latest/latest.json"
        if (!extractorManifestUrl.endsWith(tail)) return ""
        return extractorManifestUrl.removeSuffix(tail) + "/releases/download/$TAG/$INDEX"
    }

    /**
     * Reads `packs.json`. [indexUrl] is where the file came from, used to resolve file-name urls. A pack with a missing or
     * odd field is skipped (counted in `skipped`) so one bad entry never hides the others. Throws
     * [IllegalArgumentException] when the text is not a pack list at all.
     */
    fun parse(text: String, indexUrl: String): VoiceCatalog {
        val root = MiniJson.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("packs.json: not an object")
        val list = root["packs"] as? List<*> ?: throw IllegalArgumentException("packs.json: no packs list")
        val base = indexUrl.substringBeforeLast('/', "")
        val seen = HashSet<String>()
        val out = ArrayList<VoicePack>()
        var skipped = 0
        for (item in list) {
            val p = pack(item as? Map<*, *>, base)
            if (p == null || !seen.add(p.id)) skipped++ else out += p
        }
        return VoiceCatalog(out, skipped)
    }

    private fun pack(m: Map<*, *>?, base: String): VoicePack? {
        if (m == null) return null
        val id = (m["id"] as? String)?.trim().orEmpty()
        if (!ID.matches(id)) return null
        val engine = (m["engine"] as? String)?.trim()?.lowercase().orEmpty()
        if (engine.isEmpty()) return null
        val file = (m["url"] as? String)?.trim().orEmpty()
        if (file.isEmpty()) return null
        val url = if (file.startsWith("https://") || file.startsWith("http://")) file
        else if (base.isEmpty() || file.contains('/')) return null else "$base/$file"
        val size = (m["size"] as? Double)?.toLong() ?: return null
        if (size <= 0) return null
        val sha = (m["sha256"] as? String)?.trim()?.lowercase().orEmpty()
        if (!SHA.matches(sha)) return null
        val langs = LinkedHashMap<String, Int>()
        for ((k, v) in (m["languages"] as? Map<*, *>) ?: return null) {
            val code = normalize(k as? String ?: return null)
            val sid = (v as? Double)?.toInt() ?: return null
            if (code.isEmpty() || sid < 0) return null
            langs[code] = sid
        }
        if (langs.isEmpty()) return null
        val name = (m["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: id
        val version = (m["version"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: sha.take(12)
        return VoicePack(id, name, engine, version, url, size, sha, langs)
    }

    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val SHA = Regex("[0-9a-f]{64}")
}

/** "ja-JP", "ja_JP" and "JA" all become "ja". */
internal fun normalize(lang: String): String = lang.trim().lowercase().substringBefore('-').substringBefore('_')
