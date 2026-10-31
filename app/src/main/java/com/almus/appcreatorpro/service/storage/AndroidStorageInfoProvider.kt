package com.almus.appcreatorpro.service.storage

import android.content.Context
import android.os.StatFs
import com.almus.appcreatorpro.domain.model.StorageInfo
import com.almus.appcreatorpro.domain.repository.StorageInfoProvider
import java.io.File

/** Reads real storage numbers for the app's private data directory. */
class AndroidStorageInfoProvider(private val context: Context) : StorageInfoProvider {

    override fun getStorageInfo(): StorageInfo {
        val dir = context.filesDir
        return try {
            val stat = StatFs(dir.path)
            StorageInfo(
                totalBytes = stat.totalBytes,
                freeBytes = stat.availableBytes,
                appDataBytes = directorySize(dir),
            )
        } catch (e: IllegalArgumentException) {
            StorageInfo(totalBytes = 0L, freeBytes = 0L, appDataBytes = 0L)
        }
    }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
