package com.laksh.forge.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP plumbing shared by every provider: one OkHttp client, cancellable calls, retries for
 * the failures that are worth retrying (429, 5xx, dropped connections), friendly error text,
 * and a small SSE reader.
 *
 * Plain REST over OkHttp rather than vendor SDKs: those target server-side JVMs and pull in
 * dependencies that are a poor fit for an APK, while the wire formats here are small.
 */
object AiHttp {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Reasoning models can think for minutes on a long program PDF before the first byte.
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val jsonMedia = "application/json".toMediaType()

    private const val MAX_RETRIES = 2
    private const val MAX_RETRY_AFTER_SECONDS = 20L

    /**
     * Run [build]'s request and return the body of a 2xx response. 429/5xx and network drops
     * are retried twice with backoff (honouring a short Retry-After); everything else throws
     * an [AiException] whose message names [providerName].
     */
    suspend fun execute(providerName: String, build: () -> Request): String {
        var attempt = 0
        while (true) {
            val failure: AiException = try {
                client.newCall(build()).await().use { r ->
                    val raw = r.body?.string().orEmpty()
                    if (r.isSuccessful) return raw
                    errorFor(providerName, r.code, raw, r.header("retry-after"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                throw e
            } catch (e: IOException) {
                networkError(providerName, e)
            }
            if (!failure.retryable || attempt >= MAX_RETRIES) throw failure
            delay(backoffMillis(attempt, failure))
            attempt++
        }
    }

    /**
     * Open a streaming response. Retries like [execute] until headers arrive; once the body is
     * flowing nothing is retried (the user would see text twice). The call is cancelled when
     * the collecting coroutine is, so leaving the coach screen stops billing promptly where the
     * provider supports cancellation. The caller must close the returned response.
     */
    suspend fun openStream(providerName: String, build: () -> Request): Response {
        var attempt = 0
        while (true) {
            val call = client.newCall(build())
            val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            val failure: AiException = try {
                val r = call.await()
                if (r.isSuccessful) return r
                val raw = r.use { it.body?.string().orEmpty() }
                handle.dispose()
                errorFor(providerName, r.code, raw, r.header("retry-after"))
            } catch (e: CancellationException) {
                handle.dispose()
                throw e
            } catch (e: IOException) {
                handle.dispose()
                networkError(providerName, e)
            }
            if (!failure.retryable || attempt >= MAX_RETRIES) throw failure
            delay(backoffMillis(attempt, failure))
            attempt++
        }
    }

    /** GET [url] and report whether the key was accepted: true on 2xx, false on 401/403. */
    suspend fun probe(providerName: String, build: () -> Request): Boolean {
        val response = try {
            client.newCall(build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw networkError(providerName, e)
        }
        return response.use { r ->
            when {
                r.isSuccessful -> true
                r.code == 401 || r.code == 403 -> false
                else -> {
                    val raw = r.body?.string()
                    // Google answers an invalid key with 400 API_KEY_INVALID rather than 401.
                    val detail = errorDetail(raw).orEmpty() + " " + raw.orEmpty().take(400)
                    if (r.code == 400 && (detail.contains("API_KEY", ignoreCase = true) ||
                            detail.contains("api key", ignoreCase = true))
                    ) {
                        false
                    } else {
                        throw errorFor(providerName, r.code, raw, r.header("retry-after"))
                    }
                }
            }
        }
    }

    private fun backoffMillis(attempt: Int, failure: AiException): Long {
        val hinted = (failure as? RetryHint)?.retryAfterSeconds
        if (hinted != null) return hinted * 1000
        return if (attempt == 0) 1_500L else 4_000L
    }

    /** An [AiException] that carries the provider's Retry-After hint, when short enough to wait out. */
    class RetryHint(message: String, httpCode: Int, val retryAfterSeconds: Long?) :
        AiException(message, retryable = true, httpCode = httpCode)

    fun errorFor(providerName: String, code: Int, raw: String?, retryAfter: String? = null): AiException {
        val detail = errorDetail(raw)
        val suffix = detail?.let { ": $it" } ?: ""
        return when (code) {
            400, 422 -> AiException("$providerName rejected the request ($code)$suffix", httpCode = code)
            401 -> AiException("$providerName rejected the API key (401). Check it in Settings → AI.", httpCode = code)
            402 -> AiException("$providerName says the account needs credit or billing (402)$suffix", httpCode = code)
            403 -> AiException("$providerName refused access (403)$suffix", httpCode = code)
            404 -> AiException(
                "$providerName couldn't find that model or endpoint (404)$suffix. Check Settings → AI → Advanced.",
                httpCode = code
            )
            413 -> AiException("The request was too large for $providerName (413). Try fewer or smaller images.", httpCode = code)
            429 -> RetryHint(
                "$providerName rate limit reached (429)$suffix. Wait a moment and retry.",
                code,
                retryAfter?.trim()?.toLongOrNull()?.takeIf { it in 1..MAX_RETRY_AFTER_SECONDS }
            )
            in 500..599 -> RetryHint("$providerName had a service error ($code). Try again shortly.", code, null)
            else -> AiException("$providerName request failed ($code)$suffix", httpCode = code)
        }
    }

    fun networkError(providerName: String, e: IOException): AiException = when (e) {
        is UnknownHostException -> AiException("Can't reach $providerName. Check your connection.", retryable = true)
        is InterruptedIOException -> AiException("$providerName took too long to answer. Try again.", retryable = true)
        else -> AiException("Network error talking to $providerName: ${e.message ?: "connection failed"}", retryable = true)
    }

    /**
     * Pull a human message out of the error shapes in use: OpenAI/Anthropic `{"error":{"message"}}`,
     * Gemini's `[{"error":{...}}]`, Mistral-style `{"message"}` / `{"detail"}`, or a bare string error.
     */
    fun errorDetail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { AiJson.json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val obj = when (root) {
            is JsonObject -> root
            is JsonArray -> root.firstOrNull() as? JsonObject
            else -> null
        } ?: return null
        val message = messageIn(obj["error"]) ?: messageIn(obj["message"]) ?: messageIn(obj["detail"])
        return message?.trim()?.replace(Regex("\\s+"), " ")?.take(220)
    }

    private fun messageIn(el: JsonElement?): String? = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonObject -> (el["message"] as? JsonPrimitive)?.contentOrNull
        is JsonArray -> (el.firstOrNull() as? JsonObject)?.let { (it["msg"] as? JsonPrimitive)?.contentOrNull }
        else -> null
    }

    /**
     * Read server-sent events, calling [onEvent] with each event's name (if any) and its
     * data. Comment lines (":" keep-alives from OpenRouter and DeepSeek) are skipped.
     * Return false from [onEvent] to stop early.
     */
    suspend fun readSse(source: BufferedSource, onEvent: suspend (event: String?, data: String) -> Boolean) {
        var event: String? = null
        val data = StringBuilder()
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        if (!onEvent(event, data.toString())) return
                        data.setLength(0)
                    }
                    event = null
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter("data:").removePrefix(" "))
                }
            }
        }
        if (data.isNotEmpty()) onEvent(event, data.toString())
    }

    /** base + path with exactly one slash between them (Gemini's base ends in "/", others don't). */
    fun joinUrl(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * Validate a user-entered OpenAI-compatible base URL. Accepts a pasted full endpoint
     * (".../chat/completions") and trims it back to the base. HTTPS only: Android blocks
     * cleartext by default and keys must never travel unencrypted.
     *
     * @return the normalised base URL, or null when it isn't a usable HTTPS URL.
     */
    fun normalizeBaseUrl(raw: String): String? {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        for (suffix in listOf("/chat/completions", "/completions", "/models")) {
            if (s.endsWith(suffix)) s = s.removeSuffix(suffix)
        }
        val url = s.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        return url.toString().trimEnd('/')
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }
}
