package com.almus.appcreatorpro.service.artifacts

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Which APK signature schemes (v2/v3/v3.1) a file declares and the certificates it carries. */
data class SigningBlockScan(
    val schemes: Set<SignatureScheme>,
    /** DER certificates, first certificate of every signer, in file order. */
    val certificates: List<ByteArray>,
    val problems: List<String>,
) {
    companion object {
        val EMPTY = SigningBlockScan(emptySet(), emptyList(), emptyList())
    }
}

/**
 * Finds and reads the "APK Signing Block" (v2, v3, v3.1) that sits right before the ZIP central directory.
 *
 * IMPORTANT: this only READS what the block declares (which scheme, which certificate). It does not check the
 * signature against the file; that is Android's job (see [ApkPackageReader]), and the manager never calls an APK
 * "verified" because of this scan.
 *
 * Every length is bounds-checked, so a hostile file can only make the scan report a problem, never crash it.
 */
object ApkSigningBlock {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val EOCD_MIN = 22
    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    private const val MAX_BLOCK = 32L * 1024 * 1024

    const val ID_V2 = 0x7109871AL
    const val ID_V3 = 0xF05368C0L
    const val ID_V31 = 0x1B93AD61L

    fun scan(file: File): SigningBlockScan = try {
        RandomAccessFile(file, "r").use { scan(it) }
    } catch (e: IOException) {
        SigningBlockScan(emptySet(), emptyList(), listOf("The signing block could not be read."))
    } catch (e: BadBlock) {
        SigningBlockScan(emptySet(), emptyList(), listOf("The signing block is malformed."))
    }

    private fun scan(raf: RandomAccessFile): SigningBlockScan {
        val length = raf.length()
        val cdOffset = findCentralDirectoryOffset(raf, length) ?: return SigningBlockScan.EMPTY
        if (cdOffset < 32L || cdOffset > length) return SigningBlockScan.EMPTY

        val footer = ByteArray(24)
        raf.seek(cdOffset - 24)
        raf.readFully(footer)
        if (!footer.copyOfRange(8, 24).contentEquals(MAGIC)) return SigningBlockScan.EMPTY

        val blockSize = Cursor(footer, 0, 8).u64()
        // blockSize counts everything after the first 8-byte size field: the pairs, the repeated size and the magic.
        if (blockSize < 24L || blockSize > MAX_BLOCK || blockSize + 8L > cdOffset) throw BadBlock()
        val start = cdOffset - blockSize - 8L
        val pairsLength = (blockSize - 24L).toInt()

        val head = ByteArray(8)
        raf.seek(start)
        raf.readFully(head)
        if (Cursor(head, 0, 8).u64() != blockSize) throw BadBlock()

        val pairs = ByteArray(pairsLength)
        raf.readFully(pairs)

        val schemes = LinkedHashSet<SignatureScheme>()
        val certs = ArrayList<ByteArray>()
        val problems = ArrayList<String>()
        val cur = Cursor(pairs, 0, pairs.size)
        while (cur.remaining() > 0) {
            val pairLen = cur.u64()
            if (pairLen < 4L || pairLen > cur.remaining()) throw BadBlock()
            val id = cur.u32()
            val valueLen = (pairLen - 4L).toInt()
            val value = cur.slice(valueLen)
            val scheme = when (id) {
                ID_V2 -> SignatureScheme.V2
                ID_V3 -> SignatureScheme.V3
                ID_V31 -> SignatureScheme.V3_1
                else -> null
            }
            if (scheme != null) {
                schemes += scheme
                try {
                    certs += readSignerCertificates(value)
                } catch (e: BadBlock) {
                    problems += "The ${scheme.label} signature data is malformed."
                }
            }
        }
        return SigningBlockScan(schemes, certs, problems)
    }

    /** value = u32 length-prefixed sequence of signers; signer = length-prefixed signed data, signatures, public key. */
    private fun readSignerCertificates(value: Cursor): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val signers = value.lengthPrefixed()
        while (signers.remaining() > 0) {
            val signer = signers.lengthPrefixed()
            val signedData = signer.lengthPrefixed()
            signedData.lengthPrefixed() // digests
            val certificates = signedData.lengthPrefixed()
            if (certificates.remaining() > 0) {
                val first = certificates.lengthPrefixed()
                out += first.rest()
            }
        }
        return out
    }

    /** Offset of the central directory from the end-of-central-directory record, or null when this is not a plain ZIP. */
    private fun findCentralDirectoryOffset(raf: RandomAccessFile, length: Long): Long? {
        if (length < EOCD_MIN) return null
        val tailLength = minOf(length, 65_535L + EOCD_MIN).toInt()
        val tail = ByteArray(tailLength)
        raf.seek(length - tailLength)
        raf.readFully(tail)
        var i = tailLength - EOCD_MIN
        while (i >= 0) {
            val c = Cursor(tail, i, tailLength)
            if (c.u32() == EOCD_SIGNATURE) {
                val comment = Cursor(tail, i + 20, tailLength).u16()
                if (i + EOCD_MIN + comment == tailLength) {
                    val offset = Cursor(tail, i + 16, tailLength).u32()
                    return if (offset == 0xFFFFFFFFL) null else offset // ZIP64 is not used by APKs
                }
            }
            i--
        }
        return null
    }

    internal class BadBlock : Exception("bad block")

    /** Little-endian reader over a byte array that refuses to read past [end]. */
    internal class Cursor(private val data: ByteArray, private var pos: Int, private val end: Int) {
        fun remaining(): Int = end - pos

        fun u16(): Int {
            need(2)
            val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }

        fun u32(): Long {
            need(4)
            var v = 0L
            for (k in 3 downTo 0) v = (v shl 8) or (data[pos + k].toLong() and 0xFF)
            pos += 4
            return v
        }

        fun u64(): Long {
            need(8)
            var v = 0L
            for (k in 7 downTo 0) v = (v shl 8) or (data[pos + k].toLong() and 0xFF)
            pos += 8
            if (v < 0L) throw BadBlock()
            return v
        }

        fun slice(length: Int): Cursor {
            need(length)
            val c = Cursor(data, pos, pos + length)
            pos += length
            return c
        }

        fun lengthPrefixed(): Cursor {
            val len = u32()
            if (len > remaining().toLong()) throw BadBlock()
            return slice(len.toInt())
        }

        fun rest(): ByteArray {
            val out = data.copyOfRange(pos, end)
            pos = end
            return out
        }

        private fun need(n: Int) {
            if (n < 0 || n > end - pos) throw BadBlock()
        }
    }
}
