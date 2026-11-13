package com.almus.appcreatorpro.service.artifacts

import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.str
import java.io.File
import java.io.IOException

/** The checksum recorded for one saved file. [source] is "download" or "first check". */
data class ArtifactRecord(
    val sha256: String,
    val sizeBytes: Long,
    val recordedAtMillis: Long,
    val source: String,
)

/**
 * Remembers the SHA-256 of every saved APK/AAB, so a later check can tell whether the file changed. The records live
 * OUTSIDE the folder that can be shared (see res/xml/file_paths.xml), one small JSON file per artifact, and hold only
 * a checksum, a size, a time and a word: no key, no password, no secret.
 *
 * Build ids and file names are validated like [ApkStore] does, so nothing can reach outside [dir].
 */
class ArtifactRegistry(private val dir: File) {

    fun get(buildId: String, fileName: String): ArtifactRecord? {
        val file = recordFile(buildId, fileName) ?: return null
        if (!file.isFile) return null
        return try {
            val o = MiniJson.parseObject(file.readText())
            ArtifactRecord(
                sha256 = o.str("sha256") ?: return null,
                sizeBytes = o.long("sizeBytes") ?: return null,
                recordedAtMillis = o.long("recordedAtMillis") ?: 0L,
                source = o.str("source") ?: "first check",
            )
        } catch (e: MiniJson.JsonException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    /** Stores (or replaces) the record. Returns false when it could not be written; callers carry on without it. */
    fun put(buildId: String, fileName: String, record: ArtifactRecord): Boolean {
        val file = recordFile(buildId, fileName) ?: return false
        return try {
            val parent = file.parentFile ?: return false
            if (!parent.isDirectory && !parent.mkdirs()) return false
            val temp = File(parent, file.name + ".part")
            temp.writeText(
                MiniJson.stringify(
                    linkedMapOf(
                        "sha256" to record.sha256,
                        "sizeBytes" to record.sizeBytes,
                        "recordedAtMillis" to record.recordedAtMillis,
                        "source" to record.source,
                    ),
                ),
            )
            if (file.exists() && !file.delete()) {
                temp.delete()
                return false
            }
            temp.renameTo(file)
        } catch (e: IOException) {
            false
        }
    }

    fun remove(buildId: String, fileName: String) {
        recordFile(buildId, fileName)?.delete()
    }

    fun removeBuild(buildId: String) {
        if (!ApkStore.validBuildId(buildId)) return
        File(dir, buildId).deleteRecursively()
    }

    private fun recordFile(buildId: String, fileName: String): File? {
        if (!ApkStore.validBuildId(buildId) || !ApkStore.validFileName(fileName)) return null
        return File(File(dir, buildId), "$fileName.json")
    }
}
