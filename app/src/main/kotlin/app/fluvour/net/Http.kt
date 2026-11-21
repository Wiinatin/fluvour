package app.fluvour.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val code: Int, val body: String) { val ok get() = code in 200..299 }

/** Minimal HTTP helper (no extra dependencies). All calls run on the IO dispatcher. */
object Http {
    suspend fun request(
        method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null,
        readTimeoutMs: Int = 30_000
    ): HttpResult = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally { conn.disconnect() }
    }

    /** Downloads a file as bytes (follows redirects). Throws IOException on a non-2xx reply or when the file is larger than `maxBytes`. */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 60 * 1024 * 1024, readTimeoutMs: Int = 60_000): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = readTimeoutMs
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                val out = java.io.ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw java.io.IOException("File too large")
                    }
                }
                out.toByteArray()
            } finally { conn.disconnect() }
        }
}
