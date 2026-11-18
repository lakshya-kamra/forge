package com.laksh.forge.data.wearables.whoop

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ai.SecretStore
import com.laksh.forge.data.wearables.WearableStatusStore
import com.laksh.forge.data.wearables.WearableSync
import com.laksh.forge.data.wearables.WearableSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * WHOOP OAuth 2 (authorization code) for a personal developer app.
 *
 * The user's own client id/secret and the token pair live only in SecretStore (Keystore-backed,
 * excluded from backup). The research notes the secret ideally stays off the phone; here it was
 * typed in by its owner for their own single-user app, never shipped in the APK.
 */
object WhoopAuth {
    private const val KEY_CLIENT_ID = "whoop.client_id"
    private const val KEY_CLIENT_SECRET = "whoop.client_secret"
    private const val KEY_TOKENS = "whoop.tokens"

    /** A browser sign-in left open longer than this is treated as abandoned. */
    private const val STATE_TTL_MS = 15 * 60_000L

    /** Refresh a minute early so a request never leaves with a token that dies in flight. */
    private const val EXPIRY_MARGIN_MS = 60_000L

    sealed interface AuthStatus {
        data object Idle : AuthStatus
        data object WaitingForBrowser : AuthStatus
        data object Exchanging : AuthStatus
        data class Connected(val name: String?) : AuthStatus
        data class Failed(val message: String) : AuthStatus
    }

    data class Credentials(val clientId: String, val clientSecret: String)

    internal data class Tokens(val access: String, val refresh: String?, val expiresAt: Long) {
        fun isFresh(now: Long = System.currentTimeMillis()) = expiresAt - EXPIRY_MARGIN_MS > now
    }

    private class AuthRejected(message: String) : Exception(message)

    private val _status = MutableStateFlow<AuthStatus>(AuthStatus.Idle)
    val status: StateFlow<AuthStatus> = _status.asStateFlow()

    // Token exchange must outlive the redirect activity, which finishes immediately.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ credentials

    suspend fun saveCredentials(clientId: String, clientSecret: String) {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        if (id.isEmpty() || secret.isEmpty()) {
            throw WhoopException("Paste both the client ID and the client secret.")
        }
        SecretStore.put(KEY_CLIENT_ID, id)
        SecretStore.put(KEY_CLIENT_SECRET, secret)
    }

    suspend fun credentials(): Credentials? {
        val id = SecretStore.get(KEY_CLIENT_ID)?.takeIf { it.isNotBlank() } ?: return null
        val secret = SecretStore.get(KEY_CLIENT_SECRET)?.takeIf { it.isNotBlank() } ?: return null
        return Credentials(id, secret)
    }

    /** The client id with its middle masked, so the user can tell which app is saved. */
    suspend fun clientIdHint(): String? = credentials()?.clientId?.let { id ->
        if (id.length <= 8) "••••" else id.take(4) + "••••" + id.takeLast(4)
    }

    suspend fun forgetCredentials(context: Context) {
        disconnect(context)
        SecretStore.remove(KEY_CLIENT_ID)
        SecretStore.remove(KEY_CLIENT_SECRET)
    }

    suspend fun isConnected(): Boolean = loadTokens() != null

    // ------------------------------------------------------------------ authorization

    /**
     * Builds the authorize URL and remembers its state. The state is persisted, not held in
     * memory, because Android may kill Forge while the user is signing in in the browser.
     */
    suspend fun prepareAuthorization(context: Context): Uri {
        val creds = credentials() ?: throw WhoopException("Add your WHOOP client ID and secret first.")
        val state = newState()
        WearableStatusStore.get(context).setPendingWhoopState(state, System.currentTimeMillis())
        _status.value = AuthStatus.WaitingForBrowser
        return Uri.parse(WhoopConfig.AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", creds.clientId)
            .appendQueryParameter("redirect_uri", WhoopConfig.REDIRECT_URI)
            .appendQueryParameter("scope", WhoopConfig.SCOPES.joinToString(" "))
            .appendQueryParameter("state", state)
            .build()
    }

    /** Opens [uri] in a Custom Tab (never a WebView, per the research). Pass an Activity context. */
    fun launch(context: Context, uri: Uri) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            _status.value = AuthStatus.Failed("No browser is installed to open WHOOP's sign-in page.")
        }
    }

    /** Called by WhoopCallbackActivity with forge://whoop/callback?code=...&state=... */
    fun onRedirect(context: Context, uri: Uri) {
        val app = context.applicationContext
        scope.launch {
            val store = WearableStatusStore.get(app)
            val pending = store.pendingWhoopState()
            store.setPendingWhoopState(null, 0L)

            val error = uri.getQueryParameter("error")
            if (error != null) {
                val detail = uri.getQueryParameter("error_description")
                _status.value = AuthStatus.Failed(
                    detail?.takeIf { it.isNotBlank() } ?: "WHOOP sign-in didn't complete ($error)."
                )
                return@launch
            }
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            if (code.isNullOrBlank()) {
                _status.value = AuthStatus.Failed("WHOOP didn't return an authorization code.")
                return@launch
            }
            if (pending == null || state != pending.first ||
                System.currentTimeMillis() - pending.second > STATE_TTL_MS
            ) {
                _status.value = AuthStatus.Failed(
                    "That sign-in didn't match the one Forge started, or it expired. Tap Connect again."
                )
                return@launch
            }

            _status.value = AuthStatus.Exchanging
            try {
                val creds = credentials()
                    ?: throw WhoopException("WHOOP client credentials are missing. Add them again.")
                val tokens = requestTokens(
                    FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("client_id", creds.clientId)
                        .add("client_secret", creds.clientSecret)
                        .add("redirect_uri", WhoopConfig.REDIRECT_URI)
                        .build()
                )
                saveTokens(tokens)
                ForgeApp.instance.repository.settingsStore.setWhoopConnected(true)
                val name = runCatching { WhoopClient.profileName() }.getOrNull()
                store.setWhoopUserName(name)
                _status.value = AuthStatus.Connected(name)
                WearableSyncWorker.schedule(app)
                runCatching { WearableSync.syncAll() }
            } catch (e: AuthRejected) {
                _status.value = AuthStatus.Failed(e.message ?: "WHOOP rejected the sign-in.")
            } catch (e: WhoopException) {
                _status.value = AuthStatus.Failed(e.message ?: "Couldn't connect WHOOP.")
            } catch (e: Exception) {
                _status.value = AuthStatus.Failed("Couldn't connect WHOOP: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun clearStatus() {
        _status.value = AuthStatus.Idle
    }

    /** Revokes access at WHOOP (best effort) and forgets the tokens. Synced history is kept. */
    suspend fun disconnect(context: Context) {
        val tokens = loadTokens()
        if (tokens != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("${WhoopConfig.API_BASE}/v2/user/access")
                        .header("Authorization", "Bearer ${tokens.access}")
                        .delete()
                        .build()
                    http.newCall(request).execute().close()
                }
            }
        }
        clearTokens()
        WearableStatusStore.get(context).setWhoopUserName(null)
        _status.value = AuthStatus.Idle
    }

    // ------------------------------------------------------------------ tokens

    /**
     * A usable access token. Pass the token a request was just rejected with as [rejected] to
     * force a refresh. Both tokens rotate on every refresh and only one concurrent refresh
     * succeeds, so refreshes are serialized and re-check the store once inside the lock: another
     * caller may already have rotated the pair while this one waited.
     */
    internal suspend fun accessToken(rejected: String? = null): String {
        val current = loadTokens() ?: throw notConnected()
        if (rejected == null && current.isFresh()) return current.access
        return refreshMutex.withLock {
            val latest = loadTokens() ?: throw notConnected()
            if (latest.access != rejected && latest.isFresh()) latest.access
            else refresh(latest).access
        }
    }

    private suspend fun refresh(t: Tokens): Tokens {
        val refreshToken = t.refresh ?: run {
            clearTokens()
            throw WhoopException("WHOOP didn't issue a refresh token (the 'offline' scope was missing). Connect again.")
        }
        val creds = credentials()
            ?: throw WhoopException("WHOOP client credentials are missing. Add them again and reconnect.")
        val next = try {
            requestTokens(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", creds.clientId)
                    .add("client_secret", creds.clientSecret)
                    .add("scope", "offline")
                    .build()
            )
        } catch (e: AuthRejected) {
            clearTokens()
            throw WhoopException("WHOOP sign-in has expired. Connect again.", e)
        }
        // Stored as one value so the rotated pair can never be half-written.
        saveTokens(next)
        return next
    }

    private suspend fun requestTokens(form: FormBody): Tokens = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(WhoopConfig.TOKEN_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val (code, body) = try {
            http.newCall(request).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
        } catch (e: IOException) {
            throw WhoopException("Couldn't reach WHOOP: ${e.message ?: "network error"}", e)
        }
        if (code == 400 || code == 401) {
            throw AuthRejected(errorDescription(body) ?: "WHOOP rejected the credentials (HTTP $code). Check the client ID, secret and redirect URI.")
        }
        if (code !in 200..299) throw WhoopException("WHOOP token request failed (HTTP $code).")

        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw WhoopException("WHOOP sent an unreadable token response.")
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw WhoopException("WHOOP's token response had no access token.")
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        Tokens(access, refresh, System.currentTimeMillis() + expiresIn * 1000L)
    }

    private fun errorDescription(body: String): String? = runCatching {
        val obj: JsonObject = json.parseToJsonElement(body).jsonObject
        obj["error_description"]?.jsonPrimitive?.contentOrNull
            ?: obj["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun loadTokens(): Tokens? {
        val raw = SecretStore.get(KEY_TOKENS) ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            Tokens(
                access = obj["access"]!!.jsonPrimitive.content,
                refresh = obj["refresh"]?.jsonPrimitive?.contentOrNull,
                expiresAt = obj["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0L
            )
        }.getOrNull()
    }

    private suspend fun saveTokens(t: Tokens) {
        val raw = buildJsonObject {
            put("access", t.access)
            put("refresh", t.refresh)
            put("expiresAt", t.expiresAt)
        }.toString()
        SecretStore.put(KEY_TOKENS, raw)
    }

    private suspend fun clearTokens() {
        SecretStore.remove(KEY_TOKENS)
        runCatching { ForgeApp.instance.repository.settingsStore.setWhoopConnected(false) }
    }

    private fun notConnected() = WhoopException("WHOOP isn't connected.")

    private fun newState(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return buildString(WhoopConfig.STATE_LENGTH) {
            repeat(WhoopConfig.STATE_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}
