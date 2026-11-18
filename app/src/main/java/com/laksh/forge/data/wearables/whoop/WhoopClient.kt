package com.laksh.forge.data.wearables.whoop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.time.Instant

/**
 * Read-only WHOOP Developer API calls. One shared object so every caller draws from the same
 * client-side rate budget (WHOOP allows 100 requests/minute and 10,000/day per client).
 */
internal object WhoopClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val paceMutex = Mutex()
    private val recentRequests = ArrayDeque<Long>()

    /** Hard stop per collection: 40 pages x 25 = 1,000 records, far beyond any sync window. */
    private const val MAX_PAGES = 40

    suspend fun profileName(): String? {
        val obj = get(url("/v2/user/profile/basic"))
        return obj["first_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * Every record of a collection whose start falls in [start, end). Pages with `nextToken`
     * until WHOOP stops returning `next_token`.
     */
    suspend fun collection(path: String, start: Instant, end: Instant): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var next: String? = null
        var pages = 0
        do {
            val builder = url(path).newBuilder()
                .addQueryParameter("limit", WhoopConfig.PAGE_LIMIT.toString())
                .addQueryParameter("start", start.toString())
                .addQueryParameter("end", end.toString())
            next?.let { builder.addQueryParameter("nextToken", it) }
            val page = get(builder.build())
            (page["records"] as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.let { out += it }
            }
            next = page["next_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            pages++
        } while (next != null && pages < MAX_PAGES)
        return out
    }

    private fun url(path: String): HttpUrl = (WhoopConfig.API_BASE + path).toHttpUrl()

    private suspend fun get(url: HttpUrl): JsonObject {
        var token = WhoopAuth.accessToken()
        var refreshed = false
        var attempts = 0
        while (true) {
            pace()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            val result = try {
                withContext(Dispatchers.IO) {
                    WhoopAuth.http.newCall(request).execute().use { r ->
                        Triple(r.code, r.body?.string().orEmpty(), r.header("X-RateLimit-Reset"))
                    }
                }
            } catch (e: IOException) {
                throw WhoopException("Couldn't reach WHOOP: ${e.message ?: "network error"}", e)
            }
            val (code, body, reset) = result
            when {
                code in 200..299 -> {
                    if (body.isBlank()) return JsonObject(emptyMap())
                    return runCatching { json.parseToJsonElement(body).jsonObject }
                        .getOrElse { throw WhoopException("WHOOP sent an unreadable response.") }
                }
                code == 401 && !refreshed -> {
                    // A 401 means the access token expired early or was rotated elsewhere.
                    token = WhoopAuth.accessToken(rejected = token)
                    refreshed = true
                }
                code == 401 -> throw WhoopException("WHOOP no longer accepts Forge's sign-in. Connect again.")
                code == 403 -> throw WhoopException("WHOOP refused access to this data. Reconnect and allow every requested permission.")
                code == 429 && attempts < 3 -> {
                    attempts++
                    delay(rateLimitWaitMs(reset))
                }
                code == 429 -> throw WhoopException("WHOOP's rate limit was hit. Try again in a few minutes.")
                code >= 500 && attempts < 2 -> {
                    attempts++
                    delay(2_000L * attempts)
                }
                else -> throw WhoopException("WHOOP API error (HTTP $code).")
            }
        }
    }

    /**
     * WHOOP sends X-RateLimit-Reset; the research doesn't pin its unit, so treat it as seconds
     * but clamp it - a misread header must not stall a background sync for an hour.
     */
    private fun rateLimitWaitMs(reset: String?): Long {
        val seconds = reset?.trim()?.toLongOrNull()?.takeIf { it in 0..120 } ?: 60L
        return (seconds + 1) * 1_000L
    }

    /** Sliding one-minute window kept under the documented per-minute limit. */
    private suspend fun pace() {
        paceMutex.withLock {
            while (true) {
                val now = System.currentTimeMillis()
                while (recentRequests.isNotEmpty() && now - recentRequests.first() >= 60_000L) {
                    recentRequests.removeFirst()
                }
                if (recentRequests.size < WhoopConfig.REQUESTS_PER_MINUTE_BUDGET) {
                    recentRequests.addLast(now)
                    return
                }
                delay(60_000L - (now - recentRequests.first()) + 50L)
            }
        }
    }
}
