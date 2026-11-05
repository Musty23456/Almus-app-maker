package com.almus.appcreatorpro.service.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.almus.appcreatorpro.MainActivity
import com.almus.appcreatorpro.R

/**
 * Shows build notifications with the Android notification APIs. Two channels: quiet ones for "started" and "in
 * progress" (updated in place, no sound), and a normal one for the result. A tap opens that build's details.
 * Notifications carry no secrets: project name, build type, status and result only.
 */
class AndroidNotificationSink(context: Context) : NotificationSink {
    private val appContext = context.applicationContext

    init {
        createChannels()
    }

    override fun status(): NotificationAccessStatus {
        if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return NotificationAccessStatus.ENABLED
        val permissionMissing = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        return if (permissionMissing) NotificationAccessStatus.PERMISSION_NEEDED else NotificationAccessStatus.BLOCKED_BY_SYSTEM
    }

    @SuppressLint("MissingPermission") // status() is checked by the caller; a SecurityException is handled there too.
    override fun post(notification: BuildNotification) {
        val kind = notification.kind
        val openBuild = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeepLinks.EXTRA_BUILD_ID, notification.buildId)
        }
        // One PendingIntent per build (own request code), so each notification opens its own build.
        val tap = PendingIntent.getActivity(
            appContext,
            notification.buildId.hashCode(),
            openBuild,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(appContext, if (kind.terminal) CHANNEL_RESULT else CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_build)
            .setContentTitle(notification.title)
            .setContentText(notification.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.bigText))
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (kind.terminal) {
            builder.setAutoCancel(true)
                .setOngoing(false)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        } else {
            builder.setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true)
                .setWhen(notification.startedAtMillis)
                .setShowWhen(true)
                .setUsesChronometer(true)
        }
        NotificationManagerCompat.from(appContext).notify(notification.buildId, NOTIFICATION_ID, builder.build())
    }

    override fun cancel(buildId: String) {
        NotificationManagerCompat.from(appContext).cancel(buildId, NOTIFICATION_ID)
    }

    override fun cancelAll() {
        NotificationManagerCompat.from(appContext).cancelAll()
    }

    private fun createChannels() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Build progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that a cloud build has started and is running. Silent."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Build results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a cloud build succeeded, failed or was cancelled."
            },
        )
    }

    private companion object {
        const val CHANNEL_PROGRESS = "almus_build_progress"
        const val CHANNEL_RESULT = "almus_build_result"

        /** The build id is the notification tag, so this constant is the same for every build. */
        const val NOTIFICATION_ID = 7001
    }
}
