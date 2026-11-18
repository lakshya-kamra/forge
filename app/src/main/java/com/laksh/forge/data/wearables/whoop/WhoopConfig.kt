package com.laksh.forge.data.wearables.whoop

/**
 * WHOOP Developer API facts, all from docs/research/wearables.json ("whoopApi").
 * Every developer app must be registered by its owner, so Forge ships no client id: the user
 * creates their own Sandbox app and pastes its credentials in.
 */
object WhoopConfig {
    const val AUTHORIZE_URL = "https://api.prod.whoop.com/oauth/oauth2/auth"
    const val TOKEN_URL = "https://api.prod.whoop.com/oauth/oauth2/token"
    const val API_BASE = "https://api.prod.whoop.com/developer"
    const val DASHBOARD_URL = "https://developer-dashboard.whoop.com"

    /** Must be registered on the WHOOP app exactly as written; handled by WhoopCallbackActivity. */
    const val REDIRECT_URI = "forge://whoop/callback"
    const val REDIRECT_SCHEME = "forge"
    const val REDIRECT_HOST = "whoop"
    const val REDIRECT_PATH = "/callback"

    /** `offline` is what makes WHOOP issue a refresh token. */
    val SCOPES: List<String> = listOf(
        "read:recovery",
        "read:cycles",
        "read:workout",
        "read:sleep",
        "read:profile",
        "read:body_measurement",
        "offline"
    )

    /** WHOOP requires a self-generated state to be exactly 8 characters. */
    const val STATE_LENGTH = 8

    /** Collections return at most 25 records per page. */
    const val PAGE_LIMIT = 25

    /** Documented limit is 100 requests/minute per client; stay under it with headroom. */
    const val REQUESTS_PER_MINUTE_BUDGET = 90

    /** Sandbox tier: up to 10 WHOOP members can connect without approval. */
    const val SANDBOX_MEMBERS = 10

    const val MAX_APPS_PER_ACCOUNT = 5
}

/** Every WHOOP failure carries a message that is safe to show as-is. */
class WhoopException(message: String, cause: Throwable? = null) : Exception(message, cause)
