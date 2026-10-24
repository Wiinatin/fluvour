package app.fluvour.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.fluvour.net.Backend
import app.fluvour.net.Http
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authStore by preferencesDataStore("fluvour_auth")

data class Session(val userId: String, val email: String)

/** Email and password sign-in against Supabase Auth. The app still works fully offline and signed out. */
@Singleton
class AuthRepository @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val kAccess = stringPreferencesKey("access_token")
    private val kRefresh = stringPreferencesKey("refresh_token")
    private val kExpires = longPreferencesKey("expires_at")
    private val kUser = stringPreferencesKey("user_id")
    private val kEmail = stringPreferencesKey("email")
    private val lock = Mutex()

    val session: Flow<Session?> = ctx.authStore.data.map { p ->
        val id = p[kUser]; val email = p[kEmail]
        if (id != null && p[kRefresh] != null) Session(id, email ?: "") else null
    }

    /** Returns null on success, otherwise a short message for the user. */
    suspend fun signIn(email: String, password: String): String? {
        if (!Backend.configured) return "The backend address is not set in gradle.properties."
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val r = try {
            Http.request("POST", "${Backend.url}/auth/v1/token?grant_type=password", Backend.headers(), body)
        } catch (e: Exception) { return "No connection. Try again when you are online." }
        if (!r.ok) return errorText(r.body, "Sign-in failed")
        return try { store(JSONObject(r.body)); null } catch (e: Exception) { "Unexpected answer from the server." }
    }

    suspend fun signOut() { ctx.authStore.edit { it.clear() } }

    /** A usable access token, refreshed when it is about to expire. Null when signed out or offline. */
    suspend fun validToken(): String? = lock.withLock {
        val p = ctx.authStore.data.first()
        val access = p[kAccess]; val refresh = p[kRefresh] ?: return@withLock null
        if (access != null && (p[kExpires] ?: 0L) > System.currentTimeMillis() + 60_000L) return@withLock access
        val r = try {
            Http.request("POST", "${Backend.url}/auth/v1/token?grant_type=refresh_token", Backend.headers(),
                JSONObject().put("refresh_token", refresh).toString())
        } catch (e: Exception) { return@withLock null }   // offline: keep the session, try later
        if (r.ok) return@withLock try { store(JSONObject(r.body)) } catch (e: Exception) { null }
        if (r.code == 400 || r.code == 401) signOut()      // refresh token rejected: the session is over
        null
    }

    private suspend fun store(j: JSONObject): String {
        val access = j.getString("access_token")
        val expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L
        val user = j.getJSONObject("user")
        ctx.authStore.edit {
            it[kAccess] = access; it[kRefresh] = j.getString("refresh_token"); it[kExpires] = expiresAt
            it[kUser] = user.getString("id"); it[kEmail] = user.optString("email", "")
        }
        return access
    }

    private fun errorText(body: String, fallback: String): String = try {
        val j = JSONObject(body)
        j.optString("error_description").ifBlank { j.optString("msg") }.ifBlank { fallback }
    } catch (e: Exception) { fallback }
}
