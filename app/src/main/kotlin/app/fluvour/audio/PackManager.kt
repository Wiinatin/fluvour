package app.fluvour.audio

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.fluvour.BuildConfig
import app.fluvour.net.Http
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PackState {
    data object NotInstalled : PackState
    data class Downloading(val percent: Int) : PackState
    /** Checking and unpacking the finished download. */
    data object Installing : PackState
    /** [updateAvailable]: the release has a newer file for this pack than the one installed. */
    data class Ready(val updateAvailable: Boolean = false) : PackState
    /** The last try stopped. Tap Download again: it carries on from where it was. [detail] is for the log, not for the screen. */
    data class Failed(val detail: String) : PackState
}

/**
 * Downloads, checks, installs and deletes voice packs (see audio/VoicePacks.kt for the list they come from).
 *
 * - The list is `packs.json` on the `voicepacks` release ([BuildConfig.VOICEPACKS_INDEX_URL]). The last good copy is kept on the
 *   phone, so Settings can show packs offline.
 * - Nothing downloads by itself. [download] is called from Settings only.
 * - A download writes to `filesDir/voicepacks/_dl/<id>-<hash>.zip.part` and carries on from there after a stop (HTTP Range). The finished
 *   file is checked against the sha256 in `packs.json`, unpacked beside the target, and swapped in with a rename, so a half-installed pack never
 *   exists under its real name.
 * - Installed packs live in `filesDir/voicepacks/<id>/` (not cacheDir, so Android does not clear them).
 * - A download runs while the app process lives. If Android ends the process, tap Download again and it resumes.
 */
@Singleton
class PackManager @Inject constructor(@ApplicationContext private val ctx: Context, private val local: LocalVoice) {
    private val root = File(ctx.filesDir, "voicepacks").apply { mkdirs() }
    private val dl = File(root, "_dl")
    private val indexCache = File(root, "packs.json")
    private val prefs = ctx.getSharedPreferences("voicepacks", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val transient = HashMap<String, PackState>()
    private val installed = HashMap<String, String>()       // pack id -> sha256 of the installed file

    private val _catalog = MutableStateFlow<VoiceCatalog?>(null)
    /** The pack list: the last good copy until [refresh] gets a newer one. Null when there never was one. */
    val catalog: StateFlow<VoiceCatalog?> = _catalog.asStateFlow()

    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
    /** State per pack id, for every pack in the list and every installed pack. */
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    private val _installedIds = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of packs that are installed and ready to speak. Cheap to read from [TtsManager]. */
    val installedIds: StateFlow<Set<String>> = _installedIds.asStateFlow()

    init {
        val dirs = root.list()?.toSet().orEmpty()
        for ((k, v) in prefs.all) {
            val id = k.removePrefix(KEY).takeIf { k.startsWith(KEY) } ?: continue
            if (id in dirs && ModelLayout.detect(File(root, id).list()?.toList().orEmpty()) != null) installed[id] = v as? String ?: "" else prefs.edit().remove(k).apply()
        }
        root.listFiles { f -> f.isDirectory && f.name.startsWith("_tmp-") }?.forEach { it.deleteRecursively() }
        runCatching { if (indexCache.exists()) _catalog.value = VoicePacks.parse(indexCache.readText(), BuildConfig.VOICEPACKS_INDEX_URL) }
        publish()
    }

    /** False when this build has no release address (no git remote), so there is nothing to download. */
    fun isConfigured(): Boolean = BuildConfig.VOICEPACKS_INDEX_URL.isNotBlank()

    /** The folder of an installed pack, or null. */
    fun dirOf(id: String): File? = if (id in installed) File(root, id) else null

    /** True on mobile data or any metered connection, for the "uses mobile data" line before a download. */
    fun onMeteredNetwork(): Boolean = runCatching { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered }.getOrDefault(false)

    /** Fetches `packs.json` again. True when the list is now up to date; false keeps whatever list there was. */
    suspend fun refresh(): Boolean {
        if (!isConfigured()) return false
        return try {
            val r = Http.request("GET", BuildConfig.VOICEPACKS_INDEX_URL, mapOf("Cache-Control" to "no-cache"))
            check(r.ok) { "packs.json: HTTP ${r.code}" }
            val cat = VoicePacks.parse(r.body, BuildConfig.VOICEPACKS_INDEX_URL)
            indexCache.writeText(r.body)
            _catalog.value = cat
            publish()
            true
        } catch (t: Throwable) { if (t is CancellationException) throw t; Log.w(TAG, "pack list not refreshed", t); false }
    }

    /** Starts (or carries on) the download of [id]. Does nothing when it is already running or the pack is not in the list. */
    fun download(id: String) {
        val pack = _catalog.value?.byId(id) ?: return
        synchronized(this) {
            if (jobs[id]?.isActive == true) return
            jobs[id] = scope.launch { run(pack) }
        }
    }

    /** Stops a download. The part already fetched is kept, so Download carries on from it. */
    fun cancel(id: String) { synchronized(this) { jobs.remove(id)?.cancel() } }

    /** Removes the pack from the phone, with any half-finished download. */
    fun delete(id: String) {
        cancel(id)
        scope.launch {
            local.release()
            File(root, id).deleteRecursively()
            dl.listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
            prefs.edit().remove(KEY + id).apply()
            synchronized(this@PackManager) { installed.remove(id); transient.remove(id) }
            publish()
        }
    }

    private suspend fun run(pack: VoicePack) {
        val id = pack.id
        try {
            set(id, PackState.Downloading(0))
            dl.mkdirs()
            dl.listFiles { f -> f.name.startsWith("$id-") && !f.name.startsWith("$id-${pack.sha256.take(12)}.") }?.forEach { it.delete() }   // parts of an older file
            val part = File(dl, "$id-${pack.sha256.take(12)}.zip.part")
            val need = pack.size - PackFiles.resumeOffset(part, pack.size)
            if (root.usableSpace < need + pack.size * 3 / 2) throw IOException("not enough space")
            fetch(pack, part)
            set(id, PackState.Installing)
            if (!PackFiles.sha256(part).equals(pack.sha256, ignoreCase = true)) { part.delete(); throw IOException("checksum mismatch") }
            install(pack, part)
            settle(id)
        } catch (t: Throwable) {
            if (t is CancellationException) { settle(id); throw t }
            Log.w(TAG, "pack $id stopped", t)
            if (synchronized(this) { id in installed }) settle(id) else set(id, PackState.Failed(t.message ?: t.javaClass.simpleName))
        }
    }

    private suspend fun fetch(pack: VoicePack, part: File) {
        var offset = PackFiles.resumeOffset(part, pack.size)
        if (offset < pack.size) {
            val conn = URL(pack.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Fluvour")
            conn.setRequestProperty("Accept-Encoding", "identity")      // the byte counts must match the file
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            try {
                val code = conn.responseCode
                val append = when {
                    code == 206 && PackFiles.contentRangeStart(conn.getHeaderField("Content-Range")) == offset -> true
                    code == 200 -> false                                  // the server ignored the range: start again
                    else -> throw IOException("download: HTTP $code")
                }
                if (!append) offset = 0
                var done = offset
                var shown = -1
                java.io.FileOutputStream(part, append).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > pack.size) throw IOException("download is larger than the list says")
                            out.write(buf, 0, n)
                            val p = PackFiles.percent(done, pack.size)
                            if (p != shown) { shown = p; set(pack.id, PackState.Downloading(p)) }
                        }
                    }
                }
            } finally { conn.disconnect() }
        }
        if (part.length() != pack.size) throw IOException("download incomplete (${part.length()} of ${pack.size})")
    }

    private fun install(pack: VoicePack, zip: File) {
        val id = pack.id
        val tmp = File(root, "_tmp-$id").also { it.deleteRecursively() }
        try {
            PackFiles.unzip(zip, tmp, maxBytes = pack.size * 8)
            check(ModelLayout.detect(tmp.list()?.toList().orEmpty()) != null) { "the pack has no usable model files" }
            local.release()                                               // the old copy may be the model that is loaded
            val dir = File(root, id)
            dir.deleteRecursively()
            check(tmp.renameTo(dir)) { "could not move the pack into place" }
        } finally { tmp.deleteRecursively() }
        prefs.edit().putString(KEY + id, pack.sha256).apply()
        synchronized(this) { installed[id] = pack.sha256 }
        zip.delete()
    }

    private fun set(id: String, s: PackState) { synchronized(this) { transient[id] = s }; publish() }

    /** Drops the in-progress state, so the pack shows as what it is on disk: ready or not installed. */
    private fun settle(id: String) { synchronized(this) { transient.remove(id) }; publish() }

    private fun publish() {
        val (map, ids) = synchronized(this) {
            val cat = _catalog.value
            val out = LinkedHashMap<String, PackState>()
            for (id in (cat?.packs?.map { it.id }.orEmpty() + installed.keys).distinct()) {
                out[id] = transient[id] ?: installed[id]?.let { sha -> PackState.Ready(updateAvailable = cat?.byId(id)?.let { !it.sha256.equals(sha, true) } ?: false) } ?: PackState.NotInstalled
            }
            out to installed.keys.toSet()
        }
        _states.value = map
        _installedIds.value = ids
    }

    private companion object { const val TAG = "PackManager"; const val KEY = "installed_" }
}
