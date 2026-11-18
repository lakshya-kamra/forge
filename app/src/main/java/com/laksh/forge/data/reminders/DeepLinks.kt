package com.laksh.forge.data.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.laksh.forge.MainActivity

/**
 * URIs that notifications and home-screen widgets open. They are plain explicit intents to
 * MainActivity carrying a `forge://` data URI; the NavHost matches them to routes through
 * navDeepLink entries (see docs/contracts/requests/tools-widgets-data.md), so no
 * intent-filter is needed and no other app can trigger them.
 */
object DeepLinks {
    const val SCHEME = "forge"

    const val TODAY = "forge://today"
    const val LOG = "forge://log"
    const val TRAIN = "forge://train"
    const val TOOLS = "forge://tools"
    const val SETTINGS_DATA = "forge://settings/data"

    /** Pattern for navDeepLink; [session] builds a concrete link. */
    const val SESSION_PATTERN = "forge://session/{sessionId}"
    fun session(id: Long): String = "forge://session/$id"

    /**
     * CLEAR_TASK rebuilds the back stack from the start destination, so Back from a deep-linked
     * screen lands on Today instead of leaving the app.
     */
    fun intent(context: Context, uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri), context.applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
