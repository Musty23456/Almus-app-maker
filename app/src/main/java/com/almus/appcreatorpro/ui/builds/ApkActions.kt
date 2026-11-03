package com.almus.appcreatorpro.ui.builds

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.almus.appcreatorpro.service.artifacts.StoredApk

/**
 * Android-only helpers for a downloaded APK. The APK is shared through a FileProvider (a temporary read-only grant
 * for the receiving app); nothing outside filesDir/apks/ can be shared this way (see res/xml/file_paths.xml).
 */
object ApkActions {
    const val APK_MIME = "application/vnd.android.package-archive"

    /** Android App Bundles have no registered type; this makes every app accept the file as plain data. */
    const val GENERIC_MIME = "application/octet-stream"

    /** Opens the system share sheet for [apk]. Returns an error message, or null when the sheet was opened. */
    fun share(context: Context, apk: StoredApk): String? {
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (apk.isBundle) GENERIC_MIME else APK_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share ${apk.fileName}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can receive the file."
        } catch (e: IllegalArgumentException) {
            "This file cannot be shared."
        } catch (e: SecurityException) {
            "Sharing was not allowed for this file."
        }
    }
}
