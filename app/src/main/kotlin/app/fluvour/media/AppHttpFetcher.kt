package app.fluvour.media

import app.fluvour.extractor.api.HttpFetcher
import app.fluvour.extractor.api.HttpRequest
import app.fluvour.extractor.api.HttpResponse
import java.net.HttpURLConnection
import java.net.URL

/** The web access the extractor asks for. Plain HttpURLConnection, no extra libraries. Replaces the old NewPipeDownloader. */
object AppHttpFetcher : HttpFetcher {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        conn.requestMethod = request.method
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", UA)
        request.headers.forEach { (k, v) ->
            if (!k.equals("Accept-Encoding", true)) conn.setRequestProperty(k, v.joinToString(", "))   // let Android decode gzip itself
        }
        request.body?.let { data -> conn.doOutput = true; conn.outputStream.use { it.write(data) } }
        val code = conn.responseCode
        val message = conn.responseMessage ?: ""
        val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val headers = HashMap<String, List<String>>()
        conn.headerFields.forEach { (k, v) -> if (k != null && v != null) headers[k] = v }
        val finalUrl = conn.url.toString()
        conn.disconnect()
        return HttpResponse(code, message, headers, body, finalUrl)   // a 429 is turned into the captcha error inside the extractor
    }
}
