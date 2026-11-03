package com.almus.appcreatorpro.service.monitor

/** Whether Android lets this app show notifications right now. */
enum class NotificationAccessStatus {
    ENABLED,

    /** Android 13+: the notification permission was never granted. The app may ask for it. */
    PERMISSION_NEEDED,

    /** The user turned notifications off for this app (or denied the permission for good) in Android settings. */
    BLOCKED_BY_SYSTEM,
}

fun interface NotificationAccess {
    fun status(): NotificationAccessStatus
}

/** Shows and removes build notifications. The Android implementation is [AndroidNotificationSink]. */
interface NotificationSink : NotificationAccess {
    /** Shows [notification], or replaces the one already shown for the same build. */
    fun post(notification: BuildNotification)

    fun cancel(buildId: String)

    /** Removes every notification this app has shown (used when the user turns notifications off). */
    fun cancelAll()
}

/** Where a notification tap leads. The extra is validated before it is used (the activity is exported). */
object DeepLinks {
    const val EXTRA_BUILD_ID = "com.almus.appcreatorpro.extra.BUILD_ID"

    private val safeId = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** The build id if [raw] looks like one the app itself created, otherwise null. */
    fun sanitizeBuildId(raw: String?): String? = raw?.takeIf { safeId.matches(it) }
}
