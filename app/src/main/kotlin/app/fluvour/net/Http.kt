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
}
