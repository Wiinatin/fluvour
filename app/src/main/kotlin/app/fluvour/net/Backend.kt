package app.fluvour.net

import app.fluvour.BuildConfig

object Backend {
    val url: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String get() = BuildConfig.SUPABASE_ANON_KEY
    val configured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()

    fun headers(accessToken: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "apikey" to anonKey,
            "Authorization" to "Bearer ${accessToken ?: anonKey}",
            "Content-Type" to "application/json"
        ) + extra
}
