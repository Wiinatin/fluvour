package app.fluvour.media

import android.content.Context
import android.util.Log
import app.fluvour.BuildConfig
import app.fluvour.extractor.api.ExtractorApi
import app.fluvour.extractor.api.ExtractorBackend
import app.fluvour.extractor.api.HttpRequest
import app.fluvour.extractor.impl.NewPipeBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateResult {
    data class Updated(val version: String) : UpdateResult
    data object UpToDate : UpdateResult
    data object Skipped : UpdateResult          // checked recently, or auto-update is not configured
    data class Failed(val reason: String) : UpdateResult
}

/**
 * Owns the YouTube extractor. It runs the newest NewPipeExtractor build it has: a downloaded plugin if there is one
 * that works, otherwise the version built into the app.
 *
 * Updates: the release workflow builds a plugin APK for every NewPipeExtractor release and publishes a small
 * latest.json. This class checks that file (at app start, and whenever a video fails), downloads the APK, checks its
 * SHA-256, test-loads it against a real video, and only then switches over. A bad or broken download never replaces
 * a working extractor. Personal-use app: Play Store forbids downloading code, sideloaded builds do not care.
 */
@Singleton
class ExtractorManager @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "extractor").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("extractor", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateLock = Mutex()
    @Volatile private var current: ExtractorBackend? = null

    /** The extractor to use right now. Loads on first call, so call it off the main thread. */
    fun backend(): ExtractorBackend = current ?: synchronized(this) { current ?: loadActive().also { current = it } }

    /** Version of the extractor in use, e.g. "v0.26.5". */
    fun version(): String = backend().extractorVersion

    /** Fire and forget; used at app start. */
    fun checkInBackground() { scope.launch { checkForUpdate() } }

    /** [force] skips the 6 hour wait (still at least 5 minutes between checks). Returns Updated if a newer extractor is now active. */
    suspend fun checkForUpdate(force: Boolean = false): UpdateResult = updateLock.withLock {
        withContext(Dispatchers.IO) { runCatching { doCheck(force) }.getOrElse { Log.w(TAG, "update check failed", it); UpdateResult.Failed(it.message ?: it.javaClass.simpleName) } }
    }

    // ---- update ----

    private fun doCheck(force: Boolean): UpdateResult {
        if (BuildConfig.EXTRACTOR_MANIFEST_URL.isBlank()) return UpdateResult.Skipped
        val now = System.currentTimeMillis()
        val gap = if (force) 5 * MINUTE else 6 * HOUR
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < gap) return UpdateResult.Skipped
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val m = fetchManifest()
        if (m.api != ExtractorApi.VERSION) return UpdateResult.UpToDate            // built for another app generation: not for us
        if (compareVersions(m.version, backend().extractorVersion) <= 0) return UpdateResult.UpToDate
        if (prefs.getString(KEY_REJECTED, null) == m.version && now - prefs.getLong(KEY_REJECTED_AT, 0) < DAY) return UpdateResult.Skipped

        val file = download(m)
        try {
            val candidate = open(file)
            // Prove it works before trusting it: read a real video with it.
            val media = candidate.extract(SMOKE_URL)
            check(media.videoStreams.isNotEmpty()) { "test video returned no streams" }
            current = candidate
            prefs.edit().putString(KEY_FILE, file.name).putString(KEY_VERSION, m.version).remove(KEY_REJECTED).apply()
            Log.i(TAG, "extractor updated to ${m.version}")
            return UpdateResult.Updated(m.version)
        } catch (t: Throwable) {
            Log.w(TAG, "extractor ${m.version} rejected", t)
            prefs.edit().putString(KEY_REJECTED, m.version).putLong(KEY_REJECTED_AT, now).apply()
            file.setWritable(true); file.delete()
            return UpdateResult.Failed("${m.version} did not pass the test: ${t.message}")
        }
    }

    private class Manifest(val api: Int, val version: String, val url: String, val sha256: String)

    private fun fetchManifest(): Manifest {
        val r = AppHttpFetcher.execute(HttpRequest("GET", BuildConfig.EXTRACTOR_MANIFEST_URL, mapOf("Cache-Control" to listOf("no-cache")), null))
        check(r.code == 200) { "manifest: HTTP ${r.code}" }
        val j = JSONObject(r.body)
        return Manifest(j.getInt("api"), j.getString("version"), j.getString("url"), j.getString("sha256"))
    }

    private fun download(m: Manifest): File {
        val tmp = File(dir, "download.tmp")
        val target = File(dir, "plugin-${m.version}.apk")
        val conn = URL(m.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "download: HTTP ${conn.responseCode}" }
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        check(total <= MAX_BYTES) { "download too large" }
                        md.update(buf, 0, n); out.write(buf, 0, n)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            check(sha.equals(m.sha256, ignoreCase = true)) { "checksum mismatch" }
            if (target.exists()) { target.setWritable(true); target.delete() }
            check(tmp.renameTo(target)) { "could not store the download" }
            target.setReadOnly()                                                      // Android 14+ refuses to load writable code files
            return target
        } finally { conn.disconnect(); tmp.delete() }
    }

    // ---- loading ----

    private fun loadActive(): ExtractorBackend {
        val name = prefs.getString(KEY_FILE, null)
        dir.listFiles { f -> f.name.startsWith("plugin-") && f.name != name }?.forEach { it.setWritable(true); it.delete() }   // old versions
        if (name != null) {
            val file = File(dir, name)
            try { if (file.exists()) return open(file) } catch (t: Throwable) { Log.w(TAG, "downloaded extractor unusable, using built-in", t) }
            file.setWritable(true); file.delete()
            prefs.edit().remove(KEY_FILE).remove(KEY_VERSION).apply()
        }
        return NewPipeBackend().also { it.init(AppHttpFetcher) }
    }

    private fun open(file: File): ExtractorBackend {
        file.setReadOnly()
        val loader = PluginClassLoader(file.absolutePath, ExtractorManager::class.java.classLoader!!)
        val backend = loader.loadClass(ENTRY_CLASS).getDeclaredConstructor().newInstance() as ExtractorBackend
        check(backend.apiVersion == ExtractorApi.VERSION) { "plugin is for API ${backend.apiVersion}, app speaks ${ExtractorApi.VERSION}" }
        backend.init(AppHttpFetcher)
        return backend
    }

    /**
     * Loads the plugin's own copy of NewPipeExtractor first, even though the app has one built in, so the downloaded
     * version wins. Only the shared contract (app.fluvour.extractor.api) and the Android/Java platform come from the app.
     */
    private class PluginClassLoader(path: String, parent: ClassLoader) : DexClassLoader(path, null, null, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(this) {
                val loaded = findLoadedClass(name)
                if (loaded != null) return loaded
                if (SHARED.any { name.startsWith(it) }) return super.loadClass(name, resolve)
                return try { findClass(name) } catch (_: ClassNotFoundException) { super.loadClass(name, resolve) }
            }
        }
    }

    private companion object {
        const val TAG = "ExtractorManager"
        const val ENTRY_CLASS = "app.fluvour.extractor.impl.NewPipeBackend"
        const val SMOKE_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"   // "Me at the zoo": the first YouTube video, not going anywhere
        const val KEY_LAST_CHECK = "last_check"; const val KEY_FILE = "active_file"; const val KEY_VERSION = "active_version"
        const val KEY_REJECTED = "rejected_version"; const val KEY_REJECTED_AT = "rejected_at"
        const val MINUTE = 60_000L; const val HOUR = 60 * MINUTE; const val DAY = 24 * HOUR
        const val MAX_BYTES = 80L * 1024 * 1024
        val SHARED = listOf("java.", "javax.", "android.", "dalvik.", "libcore.", "sun.", "com.android.",
            "org.json.", "org.xml.", "org.w3c.", "org.xmlpull.", "app.fluvour.extractor.api.")

        /** "v0.26.5" vs "v0.27.0": compares the numbers part by part. */
        fun compareVersions(a: String, b: String): Int {
            val x = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
            val y = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
