package com.almus.appcreatorpro.service.artifacts

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 helpers. File checksums are lower-case hex (like `sha256sum`); certificate fingerprints use [SignerInfo.fingerprint]. */
object Checksums {
    private const val BUFFER = 64 * 1024

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    /** Reads [input] to its end (does not close it). */
    @Throws(IOException::class)
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return hex(digest.digest())
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** "3f2a9c01...e7b1" for display; the full value is always available in the details. */
    fun short(sha256: String?): String = if (sha256 == null) "not available" else if (sha256.length <= 20) sha256 else sha256.take(12) + "..." + sha256.takeLast(8)

    private val HEX = "0123456789abcdef".toCharArray()
}
