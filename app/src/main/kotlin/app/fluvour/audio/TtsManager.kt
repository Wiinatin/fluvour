package app.fluvour.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import app.fluvour.ai.AiError
import app.fluvour.ai.AiService
import app.fluvour.ai.GeminiTts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import app.fluvour.language.Languages
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks words and sentences. With "Natural voice" on (the default) and a Gemini key saved, Gemini's text-to-speech reads the text; each
 * sound is kept on the phone, so the same word or sentence is made once and then plays at once, offline, without using the key again.
 * When that is not possible (no key, no connection, limit reached) the language's downloaded voice pack speaks, if there is one, and
 * otherwise the phone's own voice engine, so a tap is never silent. "Prefer offline voice" puts a ready pack before Gemini.
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val ai: AiService,
    private val packs: PackManager,
    private val local: LocalVoice,
) {
    enum class Result { OK, NO_VOICE, NOT_READY }
    /** The outcome of making a sound ahead of time: already saved, made now, every key refused, or failed (no connection, odd answer). */
    enum class Ahead { CACHED, MADE, LIMITED, FAILED }

    /** The daily allowance for background voice requests (see [VoiceBudget]). */
    val budget = VoiceBudget(ctx)
    @Volatile private var lastError: AiError? = null

    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: (() -> Unit)? = null
    @Volatile private var current: String? = null
    private var counter = 0

    // ---- natural voice (Gemini) ----
    private val prefs = ctx.getSharedPreferences("tts", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var aiJob: Job? = null
    private var player: MediaPlayer? = null
    /** After a failure the phone's voice is used straight away until this time, so every tap does not wait for a request that will fail. */
    @Volatile private var pausedUntil = 0L

    private val _aiVoice = MutableStateFlow(prefs.getBoolean("ai_voice", true))
    /** Natural voice on or off (Settings > AI keys). */
    val aiVoice: StateFlow<Boolean> = _aiVoice.asStateFlow()
    private val _voice = MutableStateFlow(prefs.getString("voice", null)?.takeIf { it in GeminiTts.VOICES } ?: GeminiTts.VOICES.first())
    /** The Gemini voice used for new sounds. */
    val voice: StateFlow<String> = _voice.asStateFlow()

    private val _preferOffline = MutableStateFlow(prefs.getBoolean("prefer_offline", false))
    /** "Prefer offline voice": a ready voice pack speaks before Gemini (a sound Gemini already saved still plays). Off by default. */
    val preferOffline: StateFlow<Boolean> = _preferOffline.asStateFlow()
    private val _sarawakAccent = MutableStateFlow(prefs.getBoolean("sarawak_accent", false))
    /** Test setting (Settings > Voice, Malay only): Gemini is asked to speak Malay with a Sarawak accent. Not promised to be accurate. Off by default. */
    val sarawakAccent: StateFlow<Boolean> = _sarawakAccent.asStateFlow()
    fun setSarawakAccent(on: Boolean) { _sarawakAccent.value = on; prefs.edit().putBoolean("sarawak_accent", on).apply(); pausedUntil = 0L }

    fun setPreferOffline(on: Boolean) { _preferOffline.value = on; prefs.edit().putBoolean("prefer_offline", on).apply() }

    fun setAiVoice(on: Boolean) {
        _aiVoice.value = on; prefs.edit().putBoolean("ai_voice", on).apply()
        pausedUntil = 0L
        if (!on) stop()
    }
    /**
     * Settings > Voice: chooses [voice] and plays [sample] with it through Gemini only. The sample of each voice is asked for once and kept for good
     * (`tts/voices/<voice>-en.wav`, never trimmed), so every later tap plays it with no request.
     * A tap here is a deliberate test, so it ignores the rest after an earlier failure and never falls back to the phone's voice.
     * [onDone] gets null when the sound plays, or a short reason when Gemini could not make it (main thread).
     */
    fun previewVoice(voice: String, sample: String, onDone: (String?) -> Unit) {
        if (voice !in GeminiTts.VOICES) { onDone("Unknown voice."); return }
        _voice.value = voice; prefs.edit().putString("voice", voice).apply()
        stop()
        val id = "fluvour-${++counter}"; current = id
        aiJob = scope.launch {
            val out = withContext(Dispatchers.IO) { File(File(dir(), "voices").also { it.mkdirs() }, "$voice-en.wav") }
            var file: File? = out.takeIf { it.length() > 44 }
            var reason: String? = null
            if (file == null) {
                budget.add()
                val r = withContext(Dispatchers.IO) { ai.speech("Say clearly and naturally: $sample", voice) }
                val b64 = r.pcm
                if (b64 == null) reason = r.message ?: "Gemini could not make this voice."
                else file = withContext(Dispatchers.IO) {
                    runCatching {
                        val tmp = File(out.path + ".part")
                        tmp.writeBytes(wav(Base64.decode(b64, Base64.DEFAULT), GeminiTts.SAMPLE_RATE)); tmp.renameTo(out)
                        out.takeIf { it.length() > 44 }
                    }.getOrNull()
                } ?: run { reason = "Could not save the sound."; null }
            }
            if (id != current) return@launch   // another voice was tapped meanwhile
            onDone(if (file == null) reason ?: "Gemini could not make this voice." else null)
            file?.let { play(it, id) }
        }
    }

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            // The video fades down while this speaks. Only the latest utterance may end the duck (QUEUE_FLUSH stops the old one after the new one starts).
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                private fun finished(id: String?) { if (id == current) Ducker.set("tts", false) }
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
            if (ready) { queued?.invoke() }
            queued = null
        }
    }

    /** langTag is a BCP-47 code such as "ja" or "th". NOT_READY means the engine is still starting; the text plays once it is. */
    fun speak(text: String, langTag: String, keep: Boolean = false): Result {
        if (text.isBlank()) return Result.OK
        // keep = the sound is kept for good (the improved version in Output): a kept copy plays first, with no network and whatever the voice setting is now
        if (keep) keptFile(text)?.let { f -> stop(); val kid = "fluvour-${++counter}"; current = kid; play(f, kid); return Result.OK }
        val pack = packFor(langTag)
        val geminiReady = _aiVoice.value && System.currentTimeMillis() >= pausedUntil
        if (!geminiReady && pack == null) {
            // Gemini is resting (limit, offline) but this sound is already saved: play it instead of the phone's voice
            val saved = if (_aiVoice.value) cached(text) else null
            if (saved == null) return speakDevice(text, langTag)
            stop(); val sid = "fluvour-${++counter}"; current = sid; play(saved, sid)
            return Result.OK
        }
        stop()
        val id = "fluvour-${++counter}"; current = id
        aiJob = scope.launch {
            val file = soundFor(text, pack, geminiReady, langTag)
            if (keep && file != null) withContext(Dispatchers.IO) { keepCopy(text, file) }
            if (id != current) return@launch   // another word was asked for meanwhile
            if (file == null) speakDevice(text, langTag) else play(file, id)
        }
        return Result.OK
    }

    /** Settings > Voice packs > Test: speaks the text with this pack only. [onDone] gets false when the pack could not speak (main thread). */
    fun speakWithPack(text: String, langTag: String, packId: String, onDone: (Boolean) -> Unit = {}) {
        val cat = packs.catalog.value
        val pack = cat?.byId(packId)
        val dir = packs.dirOf(packId)
        val sid = if (cat != null && pack != null) cat.speaker(pack, langTag) else null
        if (dir == null || sid == null) { onDone(false); return }
        stop()
        val id = "fluvour-${++counter}"; current = id
        aiJob = scope.launch {
            val file = localSound(text, LocalChoice(packId, dir, sid))
            if (id != current) return@launch
            onDone(file != null)
            if (file != null) play(file, id)
        }
    }

    /** Tier order: a saved Gemini sound (when preferring offline), else Gemini (unless preferring offline), else the pack. Null means the phone's voice. */
    private suspend fun soundFor(text: String, pack: LocalChoice?, geminiReady: Boolean, langTag: String?): File? {
        val offlineFirst = pack != null && _preferOffline.value
        if (offlineFirst && _aiVoice.value) cached(text)?.let { return it }
        if (geminiReady && !offlineFirst) obtain(text, langTag)?.let { return it }
        return if (pack != null) localSound(text, pack) else null
    }

    /**
     * Makes the sounds for these texts ahead of time (nearest first, one at a time, a short rest between requests), so the speaker button
     * of the next cards plays at once. A new call replaces the waiting list; a request already under way finishes. Stops at the first
     * failure (limit, no connection) and leaves the rest to the phone's voice.
     */
    fun prefetch(texts: List<String>, langTag: String? = null) {
        if (!_aiVoice.value) return
        if (langTag != null && _preferOffline.value && packFor(langTag) != null) return   // the pack speaks this language, so no Gemini requests
        synchronized(pending) { pending.clear(); pending += texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct() }
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            while (_aiVoice.value && System.currentTimeMillis() >= pausedUntil) {
                val next = synchronized(pending) { pending.removeFirstOrNull() } ?: break
                if (cached(next) != null) continue
                if (obtain(next, langTag) == null) break
                delay(1000)
            }
            synchronized(pending) { pending.clear() }
        }
    }
    private val pending = ArrayList<String>()
    private var worker: Job? = null
    /** Sounds being made right now, by file name: a tap on a word that is already being prefetched waits for it instead of asking twice. */
    private val inFlight = HashMap<String, Deferred<File?>>()

    private suspend fun obtain(text: String, langTag: String? = null): File? {
        cached(text)?.let { return it }
        val key = fileFor(text).name
        val job = synchronized(inFlight) {
            inFlight.getOrPut(key) { scope.async(Dispatchers.IO) { try { fetch(text, langTag) } finally { synchronized(inFlight) { inFlight.remove(key) } } } }
        }
        return job.await()
    }

    // ---- transport for the sound that is playing (Output screens): pause, resume, seek, speed ----
    @Volatile private var speed = 1f
    /** True while a sound file is loaded in the player (playing or paused). False for the phone's own voice, which cannot be paused here. */
    val hasSound: Boolean get() = player != null
    fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (_: Exception) { false }
    fun positionMs(): Int = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    fun durationMs(): Int = try { player?.duration?.takeIf { it > 0 } ?: 0 } catch (_: Exception) { 0 }
    fun pause() { try { player?.takeIf { it.isPlaying }?.pause() } catch (_: Exception) {}; Ducker.set("tts", false) }
    fun resume() { try { player?.let { applySpeed(it); it.start(); Ducker.set("tts", true) } } catch (_: Exception) {} }
    fun seekTo(ms: Int) { try { player?.seekTo(ms) } catch (_: Exception) {} }
    fun setSpeed(f: Float) { speed = f; try { player?.takeIf { it.isPlaying }?.let { applySpeed(it) } } catch (_: Exception) {} }
    private fun applySpeed(p: MediaPlayer) { try { p.playbackParams = p.playbackParams.setSpeed(speed) } catch (_: Exception) {} }
    /** Plays a saved file (your own recording). */
    fun playFile(file: File) { if (!file.exists()) return; stop(); val id = "fluvour-${++counter}"; current = id; play(file, id) }

    fun stop() {
        aiJob?.cancel(); aiJob = null
        releasePlayer()
        tts?.stop(); current = null; Ducker.set("tts", false)
    }

    private fun speakDevice(text: String, langTag: String): Result {
        ensure()
        val engine = tts ?: return Result.NOT_READY
        if (!ready) { queued = { speakDevice(text, langTag) }; return Result.NOT_READY }
        val avail = engine.setLanguage(Locale.forLanguageTag(Languages.deviceTag(langTag)))
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) return Result.NO_VOICE
        val id = "fluvour-${++counter}"; current = id
        Ducker.set("tts", true)
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) Ducker.set("tts", false)
        return Result.OK
    }

    // ---- the sound files ----
    // The saved sounds live in app storage on the SD card when the phone has one mounted (no permission needed; they go when the app is
    // uninstalled), else in the phone's own external app folder, else internal storage. Never the cache folder, which Android may empty.
    private fun root(): File {
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        val sd = dirs.firstOrNull { runCatching { Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.getOrDefault(false) }
        return sd ?: dirs.firstOrNull() ?: ctx.filesDir
    }
    @Volatile private var oldCacheCleared = false
    private fun dir(): File {
        if (!oldCacheCleared) { oldCacheCleared = true; runCatching { File(ctx.cacheDir, "tts").deleteRecursively() } }   // the sounds saved here before
        return File(root(), "tts").also { it.mkdirs() }
    }
    private fun fileFor(text: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest("${_voice.value}${if (_sarawakAccent.value) "\nsarawak" else ""}\n$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(), "$h.wav")
    }
    private fun cached(text: String): File? = fileFor(text).takeIf { it.length() > 44 }

    // ---- kept sounds: never trimmed, and not tied to the voice (changing the voice does not orphan them) ----
    private fun keepDir(): File = File(dir(), "keep").also { it.mkdirs() }
    private fun keptFile(text: String): File? {
        val h = MessageDigest.getInstance("SHA-1").digest("keep\n$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(keepDir(), "$h.wav").takeIf { it.length() > 44 }
    }
    private fun isKept(f: File) = f.parentFile?.name == "keep"
    private fun keepCopy(text: String, src: File) {
        if (keptFile(text) != null) return
        val h = MessageDigest.getInstance("SHA-1").digest("keep\n$text".toByteArray()).joinToString("") { "%02x".format(it) }
        val out = File(keepDir(), "$h.wav")
        runCatching { val tmp = File(out.path + ".part"); src.copyTo(tmp, overwrite = true); tmp.renameTo(out) }
    }
    /** True when the sound is kept for good, copying it from the normal saved sounds if that is where it is now. */
    fun keepIfCached(text: String): Boolean {
        if (keptFile(text) != null) return true
        val c = cached(text) ?: return false
        keepCopy(text, c)
        return keptFile(text) != null
    }

    /** Asks Gemini for the sound and keeps it. Null when it cannot (the caller then uses the phone's voice) and pauses new requests for a while. */
    /**
     * What Gemini is told about the language of a sound. With kanji alone it cannot tell Japanese from Chinese, so the language is always
     * named (and for Japanese the reading is spelled out). Empty when the language is not known.
     */
    private fun languagePhrase(langTag: String?): String {
        val code = langTag?.substringBefore('-').orEmpty()
        if (code.isEmpty()) return ""
        val name = Languages.byCode(code).aiName
        if (code == "ms" && _sarawakAccent.value) return "in $name, with a Sarawak Malay accent (as spoken in Kuching), not Indonesian"
        return if (code == "ja") "in $name, reading every kanji with its Japanese reading and never a Chinese one" else "in $name"
    }

    private suspend fun fetch(text: String, langTag: String? = null): File? {
        budget.add()
        val lp = languagePhrase(langTag)
        val r = ai.speech("Say clearly and naturally" + (if (lp.isEmpty()) "" else ", $lp") + ": $text", _voice.value)
        val b64 = r.pcm
        if (b64 == null) {
            lastError = r.error
            pausedUntil = System.currentTimeMillis() + when (r.error) {
                AiError.ALL_KEYS_LIMITED -> 5 * 60_000L
                AiError.OFFLINE -> 30_000L
                else -> 60_000L
            }
            return null
        }
        return runCatching {
            val pcm = Base64.decode(b64, Base64.DEFAULT)
            val out = fileFor(text)
            val tmp = File(out.path + ".part")
            tmp.writeBytes(wav(pcm, GeminiTts.SAMPLE_RATE)); tmp.renameTo(out)
            trim()
            out
        }.getOrNull()
    }

    // ---- voices made ahead (background, see VoiceAheadWorker) ----
    fun isCached(text: String): Boolean = cached(text) != null || keptFile(text) != null
    /** False when the language's offline pack speaks instead of Gemini, so nothing is asked of Gemini for it. */
    fun wantsGemini(langTag: String): Boolean = _aiVoice.value && !(_preferOffline.value && packFor(langTag) != null)
    suspend fun makeAhead(text: String, keep: Boolean = false, langTag: String? = null): Ahead {
        if (cached(text) != null) return Ahead.CACHED
        lastError = null
        val f = obtain(text, langTag)
        if (f != null && keep) withContext(Dispatchers.IO) { keepCopy(text, f) }
        return if (f != null) Ahead.MADE else if (lastError == AiError.ALL_KEYS_LIMITED) Ahead.LIMITED else Ahead.FAILED
    }
    enum class Batch { MADE, NOTHING, FELL_BACK, LIMITED, FAILED }

    /**
     * ONE Gemini request voices several sentences: they are read in one long recording with a long silence between them, and
     * [PcmSplit] cuts it at the silences into one saved sound per sentence (the same files the speaker button uses). Only when the
     * number of pieces is exactly the number of sentences; otherwise nothing is saved from it ([Batch.FELL_BACK]) and the sentences
     * are voiced one by one as before. Up to 15 sentences; fewer than 2 not yet voiced is [Batch.NOTHING].
     */
    suspend fun makeBatch(texts: List<String>, langTag: String): Batch = withContext(Dispatchers.IO) {
        if (!wantsGemini(langTag) || !budget.enabled || System.currentTimeMillis() < pausedUntil || budget.room() == 0) return@withContext Batch.NOTHING
        val todo = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().filter { !isCached(it) }.take(15)
        if (todo.size < 2) return@withContext Batch.NOTHING
        budget.add()
        val lp = languagePhrase(langTag)
        val prompt = "Read these ${todo.size} lines aloud" + (if (lp.isEmpty()) "" else ", $lp") + ", one after another, in this order. Speak each line clearly and naturally, then stay completely silent for 2 seconds before the next line. " +
            "Do not read any numbers, do not add any words, do not repeat a line.\n\n" + todo.joinToString("\n")
        val r = ai.speech(prompt, _voice.value)
        val b64 = r.pcm
        if (b64 == null) {
            lastError = r.error
            pausedUntil = System.currentTimeMillis() + when (r.error) { AiError.ALL_KEYS_LIMITED -> 5 * 60_000L; AiError.OFFLINE -> 30_000L; else -> 60_000L }
            noteBatch("failed (${r.error})")
            return@withContext if (r.error == AiError.ALL_KEYS_LIMITED) Batch.LIMITED else Batch.FAILED
        }
        val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        val pieces = pcm?.let { PcmSplit.split(it, GeminiTts.SAMPLE_RATE, todo.size) }
        if (pieces == null) { noteBatch("fell back, the pauses did not give ${todo.size} pieces"); return@withContext Batch.FELL_BACK }
        val saved = runCatching {
            todo.forEachIndexed { i, text ->
                val out = fileFor(text); val tmp = File(out.path + ".part")
                tmp.writeBytes(wav(pieces[i], GeminiTts.SAMPLE_RATE)); tmp.renameTo(out)
            }
            trim()
        }.isSuccess
        if (!saved) { noteBatch("fell back, the sounds could not be saved"); return@withContext Batch.FELL_BACK }
        noteBatch("matched, ${todo.size} sentences from one request")
        Batch.MADE
    }

    private fun noteBatch(s: String) { ctx.getSharedPreferences("voice_batch", Context.MODE_PRIVATE).edit().putString("last", s).apply() }
    /** One line for Settings: what the last batch voice did, or empty. */
    fun batchNote(): String = ctx.getSharedPreferences("voice_batch", Context.MODE_PRIVATE).getString("last", null)?.let { "\nLast batch voice: $it" }.orEmpty()

    /** Settings > AI keys > Make voices ahead. */
    fun setAhead(on: Boolean) {
        budget.enabled = on
        if (on) { VoiceAheadScheduler.schedule(ctx); VoiceAheadScheduler.kick(ctx) } else VoiceAheadScheduler.cancel(ctx)
    }
    /** Asks for a background run soon (cards opened, an improved version made). */
    fun kickAhead() { if (budget.enabled && _aiVoice.value) VoiceAheadScheduler.kick(ctx) }

    // ---- voice packs (offline) ----
    private class LocalChoice(val id: String, val dir: File, val sid: Int)

    /** The ready pack for this language, or null. */
    private fun packFor(langTag: String): LocalChoice? {
        val cat = packs.catalog.value ?: return null
        val pack = cat.choose(langTag, packs.installedIds.value) ?: return null
        val dir = packs.dirOf(pack.id) ?: return null
        return LocalChoice(pack.id, dir, cat.speaker(pack, langTag) ?: 0)
    }

    /** Pack sounds are kept like Gemini sounds, under a key that holds the pack id and speaker, so the two never collide. */
    private fun localFileFor(text: String, c: LocalChoice): File {
        val h = MessageDigest.getInstance("SHA-1").digest("pack:${c.id}:${c.sid}\n$text".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(), "$h.wav")
    }

    /** The sound made by the pack, from the saved copy or by synthesizing it. Null when the pack cannot load or speak (the caller uses the phone's voice). */
    private suspend fun localSound(text: String, c: LocalChoice): File? {
        val out = localFileFor(text, c)
        if (out.length() > 44) return out
        return try {
            val pcm = local.synthesizeAsync(c.dir, text, c.sid)
            withContext(Dispatchers.IO) {
                val tmp = File(out.path + ".part")
                tmp.writeBytes(pcm.toWav()); tmp.renameTo(out)
                trim()
                out.takeIf { it.length() > 44 }
            }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }
    }

    /** Keeps the saved sounds under a limit, the oldest going first: about 1.5 GB on the SD card, about 200 MB when they have to sit on the phone. */
    private fun trim() {
        val files = dir().listFiles()?.filter { it.extension == "wav" } ?: return
        val onSd = runCatching { Environment.isExternalStorageRemovable(root()) }.getOrDefault(false)
        val limit = if (onSd) 1536L * 1024 * 1024 else 200L * 1024 * 1024
        var total = files.sumOf { it.length() }
        if (total <= limit + limit / 5) return
        for (f in files.sortedBy { it.lastModified() }) { if (total <= limit) break; total -= f.length(); f.delete() }
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }

    private fun play(file: File, id: String) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }   // a sound just played is the last to be removed when space runs out
        releasePlayer()
        Ducker.set("tts", true)
        val p = MediaPlayer()
        player = p
        fun end() { if (player === p) releasePlayer(); if (id == current) Ducker.set("tts", false) }
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            p.setDataSource(file.path)
            p.setOnPreparedListener { applySpeed(it); it.start() }
            p.setOnCompletionListener { end() }
            p.setOnErrorListener { _, _, _ -> if (!isKept(file)) file.delete(); end(); true }   // an unreadable file is made again next time
            p.prepareAsync()
        } catch (e: Exception) { if (!isKept(file)) file.delete(); end() }
    }

    private fun releasePlayer() { player?.let { runCatching { it.stop() }; runCatching { it.release() } }; player = null }
}
