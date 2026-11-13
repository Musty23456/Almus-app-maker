package com.almus.appcreatorpro.artifacts

import com.almus.appcreatorpro.service.artifacts.ApkSigningBlock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Phase 33 test material. The certificates and the signed bundles below are REAL: they were made once with keytool and
 * jarsigner from throw-away test keys that exist nowhere else (the private keys were not kept). They hold no secret.
 *  - REL_CERT: "CN=Test Release, O=Almus Tests, C=NG"
 *  - DBG_CERT: "CN=Android Debug, O=Android, C=US" (looks like the standard Android debug certificate)
 */

val REL_CERT_B64 = (
    "MIIDGTCCAgGgAwIBAgIIRDSwx/RrOEIwDQYJKoZIhvcNAQEMBQAwOjELMAkGA1UEBhMCTkcxFDASBgNVBAoTC0FsbXVzIFRlc3Rz" +
    "MRUwEwYDVQQDEwxUZXN0IFJlbGVhc2UwIBcNMjYxMTEzMDQ0OTAyWhgPMjA1MTA3MDUwNDQ5MDJaMDoxCzAJBgNVBAYTAk5HMRQw" +
    "EgYDVQQKEwtBbG11cyBUZXN0czEVMBMGA1UEAxMMVGVzdCBSZWxlYXNlMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA" +
    "wsEhGkx/D35+tzpk9o+9xznFHzMM/9u4LH1BEGMkWigNqOlvO4cMGUt8tvhDrgnWuG7HEG6AXAhBDcHfxiNxKTXm+1+Fzj7P/6Uj" +
    "6XJD+06lfP+5oJ8GT/jBwGaz+IhLqX0Qx0PqB4QcNEQHTJEzQ18E6m643kpylV4dxjwhDrA4Fe9thDzRW7HNokT0TAQAx2gZuMJ5" +
    "H8099GejZDQv5+jCCE/JGqOuM02fCw3BCcrIqcsYjnuxZg8tVINsKs1w0Jp4sxrP8t26NhlvKVPHX9taH9q9WCMGxOnwrBZTiIWb" +
    "AwFynNL2awepx+EYkb2vI2O2a0OVfvZ3WsOPWAU74QIDAQABoyEwHzAdBgNVHQ4EFgQUbTFE04T/Wf6xo7XM7egwXBjJFHUwDQYJ" +
    "KoZIhvcNAQEMBQADggEBAIUCy77t6tsybURrt8aPeuUVWFssTGoALCuXkpudVtrrCHRjtvtzJ1s+5J9Dhn1/dMdjA0PV2zM2BG5y" +
    "Zki7z6V6q4pcB3QHsRF70pKdrFRuqN0LLolAkJ8YNP0Qs6zu6KbAiKRk8QP6+qDmLmzRId4CNO2Jf0/kQ4j3zOXbR9RQYtH2cItj" +
    "afAYInet+H8EuFiJmgSdX0V0tY8D7n2FxFYh3kZpYsCgbuQBxoghimdgkwJtKnqZP89mo9zXHGn5FK48k/B2VPTt2SQnS35h/Cky" +
    "NL5wcoPyWRIcx1AVmqeUgoo0hWC0D7vpLaIvGBcNwuIKhW85ja4zmkngR7RhAYM="
)

val DBG_CERT_B64 = (
    "MIIDEzCCAfugAwIBAgIIbErFfRx0yeswDQYJKoZIhvcNAQEMBQAwNzELMAkGA1UEBhMCVVMxEDAOBgNVBAoTB0FuZHJvaWQxFjAU" +
    "BgNVBAMTDUFuZHJvaWQgRGVidWcwIBcNMjYxMTEzMDQ0OTAxWhgPMjA1MTA3MDUwNDQ5MDFaMDcxCzAJBgNVBAYTAlVTMRAwDgYD" +
    "VQQKEwdBbmRyb2lkMRYwFAYDVQQDEw1BbmRyb2lkIERlYnVnMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1iZcYGOZ" +
    "rxXD04vLF0qK1xgH//9C8OT9CvH/XV+K4T/YDZ18Z3QucGRSYmwBT0+jVxkawG6d0dsfHmZWdP+8/0JySSWuSaIdpzKwWeXSAl/Z" +
    "y2hT114D03FeZZkGzlWZo1icz8hiL/immFFe8v0koHP0edu85txSi90inOka2Zli68u5OLkWbS8pHqeoLtXHn/x2AKh0xwx8vlPB" +
    "sCH53Tuxi4w9LiLABm3rfOgK3Gr2eXk7Tw569wuKS4xL04p8oHKKA3tLM9C4nD1K994ODVe9MHKDiH/TTgEwYG5/825LkktCjpdO" +
    "TMR4nYQrByvQHwqPHdXCxdrsPPENNnenmwIDAQABoyEwHzAdBgNVHQ4EFgQUOjrGZgBVeFRknQovOz0UeKlKzSQwDQYJKoZIhvcN" +
    "AQEMBQADggEBAHUw6GeIoDYgcst+MoFmgmNLJA0UqgISYxB4mR1kOInZH74jNuc40Q11nimvVOcuUq3UGiHjCjliwRXy2wes9aER" +
    "Dk65UTQQ1ZlKEXyNvhtct317dmN8i1jC04TZqY2gnQB2gRMcbLVPtOZ3yXSKikjfF5wlcXPu0Km9/82dkw1nTjUEEyZ8n0G6H++9" +
    "+ap8w6J/qvKGCV2TuWEGM3ZjtqsFFOTkJ7BZbuu9IY77S0sEcENe5UyCfuaElxEFP1YZ7PXCIUlF28QGOwOccxb6lPy16cwVgpeJ" +
    "wTUImpdZTPBmLkXHbOSCOh3NUnNvZxEMTmAE0TCRTou52rpvAIdSbX0="
)

val SIGNED_RELEASE_AAB_B64 = (
    "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"
)

val SIGNED_DEBUG_AAB_B64 = (
    "UEsDBBQACAgIAC0ubV0AAAAAAAAAAAAAAAAUAAAATUVUQS1JTkYvTUFOSUZFU1QuTUZtzstugkAAheE9Ce/Ask3DVUVL0gW3Qi/I" +
    "RZ1UdsgMCDKADBNHnr5J46KpXZ7N958ga6sCkVEEaCBV1xqCKik8Zw8oGxEUrashaKqkSKomqcLD7kDbkT7yHM+tM4wM4ZARJEPE" +
    "5LzJCEFEgojx3MY3RW2hi05VIjIawtGvjn7ppWixWg+nJMPb6bPtmnyuT09aHGHbreeK+a4nOHj5Y+PbP9ls4dBVMLhtieHmPmRt" +
    "ttY5J2kI3TfmXOVL4p4SwGDqznS7ps81XMsgHyjY3YUGRDo65IhI/eEe9lrTLBK39l9Nk0ZY2Tc9XLJVvQ+SnMFVv7Gn+ENdRh5S" +
    "fsEWbWGD7K4tqvJf1rXHhH6lGThHHS6KyKFhuGv8BYvDCSyDS//uOfqMAFDGP+w3UEsHCJNjQ30qAQAArgEAAFBLAwQUAAgICAAu" +
    "Lm1dAAAAAAAAAAAAAAAAFAAAAE1FVEEtSU5GL0FORFJPSURELlNGdc/bjppQGIbhcxPvgcM2hM1i0CpJDwAHUEdlZDPAGcgPsxj2" +
    "a6HI1Tdt2qRx2ht43u9zcF7HdOiB86EnuKkVBvHifKb3EFNIOe2uMBLiRR5JPGK+eMlQ0+HrfOZYKictltwG50Aod4hrnAGhCvOM" +
    "CpBf1ZVr1sVQnMxpiJ4nKtNT1jmBpTaBvNsWdml4wuX7fxnuEOOaUyntcTJQIAoTxt3L6TrarXt0DVsQwL53qY6D/PWDDqwxnzGJ" +
    "WyVBFS3F9+SnPJ8d4woUJokJCCmMwqWMCQHCpzA+dhUmaCuRlZwK6ctIs92+8yZH3B2DbLU1g5t6tlCfk6BVg1X+aFe/NwtqnfYN" +
    "Tv984Meq/ByS6+3a2BddyTpCq8WjlZ3tgpV1H90O2bc3LJdGeQyekvLuPYZ6IM3QX4DwbfIZnqJruEYh2fXU/HjJs/F89d+806Jd" +
    "+YC7/f66ZidYSLe7Kf4Fa0OdlqA3dYbzf7ISNfXIlrQQ3VD5Hi43qNWmLTsC4Dza7C4p8Y2nMrHGiPxifwBQSwcIps4mto4BAABQ" +
    "AgAAUEsDBBQACAgIAC4ubV0AAAAAAAAAAAAAAAAVAAAATUVUQS1JTkYvQU5EUk9JREQuUlNBM2hijWHj1GrzaPvOy8jOtKCJ1deg" +
    "idWTiZHRkN+Al40zoc2DMZWZhYmRlcGAG6GQcUETs7hBE7OwQRPj7wXMTIxMTBw5XkdrZUpOvgZpg6pj5GFlMDA35DbgZGMOZWET" +
    "ZgoNNhQw4ANxuITZHfNSivIzUwzFDERAIszCvFARBZfUpNJ0AwVxXiMzQ0NDYwMTE0sDwygJfiMDU0MDcwNTqAD5RjcxKiE7k5GV" +
    "gbmJkZ/BoImRi6mJkZHhmlpMQvLM9aKHL3efFvfqui7B/v+/04cnf7k+/o+N73pof4N3bk16iV5BSlBSDqO//+JwSakDeXMv3paX" +
    "Swsr+b/nv1ORp+o6z0Wyy402RD69xBR/83RG8PU45suFcakz2c6FzlwcMef8iST9H8tmBMZ9+quyoPhL5e09z+4Edd9VmvNS6ubM" +
    "pNend1rsFMvV15RbvkLv6vH5f8oYVpQc56nZF3xwg+LPu9Ybu3ts9ZQOsOW+rnnBdSfrW2WltT9f1XfuLu8e78tdNQuKupirvY0v" +
    "7Jhj6/X9Hh9v+F6DouaO+st+jAYJefWf87wneTv1TffzOVIxt0WbXfuCPFe/7NVDR2+9sfnIa1a+fDYTMyMD42JFA3kDWTbmUFk+" +
    "FjEWESurY2kMoRUhKXO59K1tRSpWep1VQYtrZlDYlRq8SO9YYKZQdLrOqDGtKdlbhVdkFZNQskDFTNkUi86b8vuUzZ5bXOQtnae5" +
    "PuS5XtDaK1KKj7kskw6KfrrNvubrQkE+v52BJgJXZ3oJ1vTuk47ZXltdllzTHXHocsvNlb0L5jKUNQrL5Gz13/Ks/GRJV5fHffE5" +
    "qoXF7y6s3Pv/7NzJvOl+pizCajXzHXfJv9/7c1XN4UX1qz61ccZO3pnIZlyWvG01q8iTJ+obIvNe71Xs++3tzVLgHPfUp6nuWct0" +
    "QVb7MMk3Xw8perrePsJmzTynWOzXlD9bX54RbZreedCUY9b0SJ8PaXqux3OeNFnJng0qzk8X5PFLYLloMNGve+etXfkM7UG5tYZN" +
    "TJwGTUysTIyMBs5kJ1HU/ISUDRc0zjCQgIc6J7MhcrY0kEHIsBryI2cgI/MoA12ErImhgoEcmtELkWKTm5XBQB+hnMVQiUWBVyZg" +
    "nsjftyb/Wcyk7hvlJ7se39IWdVWmXXZXZGX75idfDVD1szQxMkhuZVi98+vt5eEm53u5j6wqFL4/w+udzp4LXMvKZvhN7ipcyqfd" +
    "0crCIzrlit1XSX3hmX+mW+7sOt044/TaUPl9ja7nnfOMtxqdnTrBf4Ox37q75Z+mftwg6nF9ht5b9bs/jstYnHkUnfT1oXV9XsLb" +
    "N3n9sj+WHLzcd1pm8Xm9KfxfzD9FXXq07lDqlLAV4Z2s1cW//n6WX9535uEeDXOT5ghv7z75itj9+9c84TPcFcrS7iCks+PZdofn" +
    "8zQVxGVbN0UrbJo9jzEi+8nSx1VTON2W9Hg21R1aK/rTc9b/uU0+DUGBRuJbDm/zUe4uW2HlPv3d20+GX2Zyb7n6tbz4W0jmumTl" +
    "CbZM7zrWlzoBAFBLBwjoE6RkhAQAAGAFAABQSwMEFAAICAgALS5tXQAAAAAAAAAAAAAAAA8AAABCdW5kbGVDb25maWcucGLjYgAA" +
    "UEsHCHX6NrsEAAAAAgAAAFBLAwQUAAgICAAtLm1dAAAAAAAAAAAAAAAAIQAAAGJhc2UvbWFuaWZlc3QvQW5kcm9pZE1hbmlmZXN0" +
    "LnhtbJ3QMUsDMRjGcSInLTkK5ca4ZTxoQmsLKqKg0NGl4P5e8noXLrmES078BgoOgp9Y1A6ucX3g/xse+lrQHZ3BoEdvdFV3KYUr" +
    "KaPq0EEUx10o7ySEXo4Y5XFjcweDecKY+Fk1C6B6aJEtlXcCX8AFiyJ2PvBrmoFW5TOO0fjh3mtk5ILv/1U/gENWrsVGbFcam6mt" +
    "Pwn9IGw+RYyrqHt+m+UunBkOun/81dnJZsvvsoBlgrHF9Nc439XvhL4RVkII1ihIxg/8Mos9tdCgZcXh++ibrJT+/NJCY5EVaZzw" +
    "C1BLBwgnsALN3gAAAAoCAABQSwMEFAAICAgALS5tXQAAAAAAAAAAAAAAABQAAABiYXNlL2RleC9jbGFzc2VzLmRleEtJrdBNqixJ" +
    "LdZNGWWNEBYAUEsHCGLUDS0RAAAA9AEAAFBLAwQUAAgICAAtLm1dAAAAAAAAAAAAAAAAEQAAAGJhc2UvcmVzb3VyY2VzLnBiK0ot" +
    "LqINAgBQSwcIgJwnTQgAAABaAAAAUEsBAhQAFAAICAgALS5tXZNjQ30qAQAArgEAABQAAAAAAAAAAAAAAAAAAAAAAE1FVEEtSU5G" +
    "L01BTklGRVNULk1GUEsBAhQAFAAICAgALi5tXabOJraOAQAAUAIAABQAAAAAAAAAAAAAAAAAbAEAAE1FVEEtSU5GL0FORFJPSURE" +
    "LlNGUEsBAhQAFAAICAgALi5tXegTpGSEBAAAYAUAABUAAAAAAAAAAAAAAAAAPAMAAE1FVEEtSU5GL0FORFJPSURELlJTQVBLAQIU" +
    "AxQACAgIAC0ubV11+ja7BAAAAAIAAAAPAAAAAAAAAAAAAACAAQMIAABCdW5kbGVDb25maWcucGJQSwECFAMUAAgICAAtLm1dJ7AC" +
    "zd4AAAAKAgAAIQAAAAAAAAAAAAAAgAFECAAAYmFzZS9tYW5pZmVzdC9BbmRyb2lkTWFuaWZlc3QueG1sUEsBAhQDFAAICAgALS5t" +
    "XWLUDS0RAAAA9AEAABQAAAAAAAAAAAAAAIABcQkAAGJhc2UvZGV4L2NsYXNzZXMuZGV4UEsBAhQDFAAICAgALS5tXYCcJ00IAAAA" +
    "WgAAABEAAAAAAAAAAAAAAIABxAkAAGJhc2UvcmVzb3VyY2VzLnBiUEsFBgAAAAAHAAcA1AEAAAsKAAAAAA=="
)

val UNSIGNED_AAB_B64 = (
    "UEsDBBQAAAAIAC0ubV11+ja7BAAAAAIAAAAPAAAAQnVuZGxlQ29uZmlnLnBi42IAAFBLAwQUAAAACAAtLm1d5j9qhcoAAADCAQAA" +
    "IQAAAGJhc2UvbWFuaWZlc3QvQW5kcm9pZE1hbmlmZXN0LnhtbJ3Mu2oCQRSAYdYoyoggW47dlAvOGC+FIhiSJqSwEeyPMyfusDsX" +
    "9oziWwh5obxailik3bQ//B/7fmIr1gdvmmBNXpQpxY1SpEt0QPLRpQ5OQaxUg6QejQ8cePuJlMQk70fQFZyRj3VwEm/gYo2SyhDF" +
    "lrVA8+EVG7LBvwWDPFuLl3/de3DIe89yIWfFV8buGR9cCGlKphK7VuLIWX8w1fHX5Z35Ury2AsYJmjOmv8ZiVXywdz6EGGurIdng" +
    "xboV2qvhhDXvHsoQfwBQSwMEFAAAAAgALS5tXWLUDS0RAAAA9AEAABQAAABiYXNlL2RleC9jbGFzc2VzLmRleEtJrdBNqixJLdZN" +
    "GWWNEBYAUEsDBBQAAAAIAC0ubV2AnCdNCAAAAFoAAAARAAAAYmFzZS9yZXNvdXJjZXMucGIrSi0uog0CAFBLAQIUAxQAAAAIAC0u" +
    "bV11+ja7BAAAAAIAAAAPAAAAAAAAAAAAAACAAQAAAABCdW5kbGVDb25maWcucGJQSwECFAMUAAAACAAtLm1d5j9qhcoAAADCAQAA" +
    "IQAAAAAAAAAAAAAAgAExAAAAYmFzZS9tYW5pZmVzdC9BbmRyb2lkTWFuaWZlc3QueG1sUEsBAhQDFAAAAAgALS5tXWLUDS0RAAAA" +
    "9AEAABQAAAAAAAAAAAAAAIABOgEAAGJhc2UvZGV4L2NsYXNzZXMuZGV4UEsBAhQDFAAAAAgALS5tXYCcJ00IAAAAWgAAABEAAAAA" +
    "AAAAAAAAAIABfQEAAGJhc2UvcmVzb3VyY2VzLnBiUEsFBgAAAAAEAAQADQEAALQBAAAAAA=="
)

fun b64(text: String): ByteArray = Base64.getDecoder().decode(text)

/** Fingerprint of the REL_CERT test certificate, as keytool prints it. */
const val REL_CERT_SHA256 = "AA:34:02:13:70:48:11:7D:B5:13:2D:23:43:DE:6C:1D:3F:A2:D6:ED:EA:5E:B8:6C:AD:A4:A1:80:50:7E:A7:06"

fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

/** An APK-shaped zip. */
fun plainApk(payload: String = "classes"): ByteArray =
    buildZip("AndroidManifest.xml" to "<manifest/>".toByteArray(), "classes.dex" to payload.toByteArray())

private fun le32(v: Long): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
private fun le64(v: Long): ByteArray = le32(v and 0xFFFFFFFFL) + le32(v shr 32)
private fun prefixed(data: ByteArray): ByteArray = le32(data.size.toLong()) + data

/** The value of a v2/v3 pair that carries one signer with one certificate (digests, signatures and key left empty). */
private fun signerSequence(cert: ByteArray): ByteArray {
    val certs = prefixed(prefixed(cert))
    val signedData = prefixed(prefixed(ByteArray(0)) + certs + prefixed(ByteArray(0)))
    val signer = prefixed(signedData + prefixed(ByteArray(0)) + prefixed(ByteArray(0)))
    return prefixed(signer)
}

/**
 * Inserts a genuine-looking "APK Signing Block" (with the given scheme ids) before the central directory of [zip].
 * Only the layout is real: the digests and signatures inside are empty, which is enough to test READING the block.
 */
fun withSigningBlock(zip: ByteArray, cert: ByteArray, ids: List<Long> = listOf(ApkSigningBlock.ID_V2)): ByteArray {
    var eocd = -1
    for (i in zip.size - 22 downTo 0) {
        if (zip[i] == 0x50.toByte() && zip[i + 1] == 0x4B.toByte() && zip[i + 2] == 0x05.toByte() && zip[i + 3] == 0x06.toByte()) {
            eocd = i
            break
        }
    }
    require(eocd >= 0) { "no EOCD" }
    val cdOffset = (0 until 4).fold(0L) { acc, k -> acc or ((zip[eocd + 16 + k].toLong() and 0xFF) shl (8 * k)) }.toInt()

    var pairs = ByteArray(0)
    for (id in ids) {
        val value = signerSequence(cert)
        pairs += le64(4L + value.size) + le32(id) + value
    }
    val size = pairs.size.toLong() + 24L
    val block = le64(size) + pairs + le64(size) + "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    val out = zip.copyOf()
    val newCd = (cdOffset + block.size).toLong()
    val patched = out.copyOfRange(0, cdOffset) + block + out.copyOfRange(cdOffset, out.size)
    val eocdNew = eocd + block.size
    le32(newCd).copyInto(patched, eocdNew + 16)
    return patched
}

/** Rebuilds a zip, letting [change] alter or drop entries; signature files are copied untouched. */
fun rewriteZip(zip: ByteArray, change: (name: String, data: ByteArray) -> ByteArray?, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val data = zin.readBytes()
                val replaced = change(e.name, data) ?: continue
                z.putNextEntry(ZipEntry(e.name))
                z.write(replaced)
                z.closeEntry()
            }
        }
        for ((n, d) in extra) {
            z.putNextEntry(ZipEntry(n))
            z.write(d)
            z.closeEntry()
        }
    }
    return out.toByteArray()
}
