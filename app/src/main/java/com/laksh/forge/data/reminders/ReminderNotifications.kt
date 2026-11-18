package com.laksh.forge.data.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.laksh.forge.R

/** The reminder notification channel and everything needed to post into it safely. */
object ReminderNotifications {

    const val CHANNEL_ID = "forge_reminders"

    /** Idempotent: creating an existing channel only updates its name and description. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName("Reminders")
            .setDescription("Weigh-in, food logging and training-day reminders you switched on in Forge.")
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Android 13+ asks for POST_NOTIFICATIONS at runtime; older versions grant it at install. */
    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun permissionGranted(context: Context): Boolean =
        !needsRuntimePermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Permission granted AND not switched off for the app (or the channel) in system settings. */
    fun canPost(context: Context): Boolean {
        if (!permissionGranted(context)) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    /** The system screen where the person can turn Forge's notifications back on. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Permission is checked by canPost() right before notify(); the catch covers a revoke
    // racing between the check and the call.
    @SuppressLint("MissingPermission")
    fun post(context: Context, type: ReminderType, title: String, text: String, deepLink: String) {
        ensureChannel(context)
        if (!canPost(context)) return
        val pending = PendingIntent.getActivity(
            context,
            type.notificationId,
            DeepLinks.intent(context, deepLink),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // The launcher foreground is a transparent "F" glyph, which renders correctly as a
            // monochrome status-bar icon.
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(type.notificationId, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post; nothing useful to do.
        }
    }

    fun cancel(context: Context, type: ReminderType) {
        NotificationManagerCompat.from(context).cancel(type.notificationId)
    }
}
