package com.almus.appcreatorpro.service.artifacts

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.JarFile
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Safety limits so a hostile or huge file can only make the check fail, never exhaust the phone. */
data class AnalyzerLimits(
    /** Total bytes read from inside the archive (compressed data can be much larger than the file: a "zip bomb"). */
    val maxUncompressedBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxEntries: Int = 100_000,
    val maxSmallEntryBytes: Int = 4 * 1024 * 1024,
    val maxReportedProblems: Int = 5,
)

/** What a look inside the file itself shows. Contains no secret: only public data and checksums. */
data class FileAnalysis(
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the whole file; null when it could not be read. */
    val sha256: String?,
    /** True when the archive is complete: every entry reads and matches its recorded CRC and size, and the mandatory files exist. */
    val structureOk: Boolean,
    val structureProblems: List<String>,
    val schemes: List<SignatureScheme>,
    /** Public certificates found in the file (not verified by this class). */
    val signers: List<SignerInfo>,
    /** AAB only: true/false = the JAR signature was checked against the contents; null = nothing to check (not signed). */
    val jarSignatureVerified: Boolean?,
    val signatureProblems: List<String>,
    /** AAB only. */
    val bundleManifest: BundleManifest?,
) {
    val hasSignature: Boolean get() = schemes.isNotEmpty()
}

/**
 * Looks inside an APK or AAB using only the Java ZIP/JAR classes, so it behaves the same on a phone and in a unit test.
 *
 * What it can prove: the file is a complete ZIP (every entry's CRC and size match), the mandatory files exist, the file's
 * SHA-256, which signature schemes it declares, which public certificates it carries, and (AAB) that its JAR signature
 * matches the contents.
 * What it cannot prove: that Android will accept an APK. That is [ApkPackageReader]'s job.
 */
class ArtifactAnalyzer(private val limits: AnalyzerLimits = AnalyzerLimits()) {

    fun analyze(file: File, type: ArtifactType): FileAnalysis {
        val size = file.length()
        val sha = try {
            Checksums.sha256(file)
        } catch (e: IOException) {
            return FileAnalysis(size, null, false, listOf("The file could not be read."), emptyList(), emptyList(), null, emptyList(), null)
        }

        val problems = ArrayList<String>()
        val signatureProblems = ArrayList<String>()
        val signers = LinkedHashMap<String, SignerInfo>()
        val schemes = LinkedHashSet<SignatureScheme>()
        var bundleManifest: BundleManifest? = null
        var jarVerified: Boolean? = null

        try {
            ZipFile(file).use { zip ->
                val names = HashSet<String>()
                val signatureBlocks = ArrayList<String>()
                var total = 0L
                var count = 0
                var manifestBytes: ByteArray? = null
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (++count > limits.maxEntries) {
                        problem(problems, "The archive has too many files to check.")
                        break
                    }
                    val name = entry.name
                    names += name
                    if (entry.isDirectory) continue

                    val wantBytes = isSignatureBlock(name) || name == bundleManifestPath(type)
                    val crc = CRC32()
                    var read = 0L
                    val sink = if (wantBytes) java.io.ByteArrayOutputStream() else null
                    try {
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                crc.update(buffer, 0, n)
                                read += n
                                total += n
                                if (total > limits.maxUncompressedBytes) throw TooBig()
                                if (sink != null) {
                                    if (read > limits.maxSmallEntryBytes) throw TooBig()
                                    sink.write(buffer, 0, n)
                                }
                            }
                        }
                    } catch (e: TooBig) {
                        problem(problems, "The archive expands to more data than can be checked safely.")
                        return@use
                    } catch (e: IOException) {
                        problem(problems, "Damaged data in $name.")
                        continue
                    }
                    if (entry.size >= 0L && entry.size != read) problem(problems, "Size of $name does not match the archive index.")
                    if (entry.crc >= 0L && entry.crc != crc.value) problem(problems, "Checksum of $name does not match (damaged).")
                    if (sink != null) {
                        if (isSignatureBlock(name)) signatureBlocks += name
                        if (name == bundleManifestPath(type)) manifestBytes = sink.toByteArray()
                        if (isSignatureBlock(name)) collectCertificates(sink.toByteArray(), signers, signatureProblems)
                    }
                }

                checkMandatory(type, names, problems)
                if (signatureBlocks.isNotEmpty() || names.any { isSignatureFile(it) }) schemes += SignatureScheme.V1
                if (type == ArtifactType.AAB) {
                    manifestBytes?.let { bundleManifest = BundleManifestReader.parse(it) }
                }
            }
        } catch (e: ZipException) {
            problem(problems, "This is not a complete ZIP archive.")
        } catch (e: IOException) {
            problem(problems, "The archive could not be read.")
        } catch (e: SecurityException) {
            problem(problems, "The archive could not be read.")
        }

        if (type == ArtifactType.APK) {
            val block = ApkSigningBlock.scan(file)
            schemes += block.schemes
            block.certificates.forEach { der -> SignerInfo.fromDer(der)?.let { signers.putIfAbsent(it.sha256, it) } }
            signatureProblems += block.problems
        } else if (SignatureScheme.V1 in schemes) {
            jarVerified = verifyJar(file, signatureProblems)
        }

        return FileAnalysis(
            sizeBytes = size,
            sha256 = sha,
            structureOk = problems.isEmpty(),
            structureProblems = problems,
            schemes = schemes.sortedBy { it.ordinal },
            signers = signers.values.toList(),
            jarSignatureVerified = jarVerified,
            signatureProblems = signatureProblems,
            bundleManifest = bundleManifest,
        )
    }

    private class TooBig : Exception()

    private fun problem(list: MutableList<String>, text: String) {
        if (list.size < limits.maxReportedProblems && text !in list) list += text
    }

    private fun checkMandatory(type: ArtifactType, names: Set<String>, problems: MutableList<String>) {
        when (type) {
            ArtifactType.APK -> if ("AndroidManifest.xml" !in names) problem(problems, "AndroidManifest.xml is missing, so this is not an installable APK.")
            ArtifactType.AAB -> {
                if ("base/manifest/AndroidManifest.xml" !in names) problem(problems, "The bundle has no base manifest.")
                if ("BundleConfig.pb" !in names) problem(problems, "BundleConfig.pb is missing, so this is not a valid bundle.")
            }
        }
    }

    private fun bundleManifestPath(type: ArtifactType): String? = if (type == ArtifactType.AAB) "base/manifest/AndroidManifest.xml" else null

    /** META-INF/NAME.RSA, .DSA or .EC: the PKCS#7 block that carries the signer's certificate. */
    private fun isSignatureBlock(name: String): Boolean {
        val n = name.uppercase()
        return n.startsWith("META-INF/") && n.indexOf('/', 9) < 0 && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
    }

    private fun isSignatureFile(name: String): Boolean {
        val n = name.uppercase()
        return isSignatureBlock(name) || (n.startsWith("META-INF/") && n.indexOf('/', 9) < 0 && n.endsWith(".SF"))
    }

    private fun collectCertificates(pkcs7: ByteArray, into: MutableMap<String, SignerInfo>, problems: MutableList<String>) {
        try {
            val certs = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pkcs7))
            // The signer's own certificate is listed first; the rest is its chain.
            val first = certs.firstOrNull() as? X509Certificate
            if (first == null) {
                problems += "A signature block holds no certificate."
            } else {
                val info = SignerInfo.fromCertificate(first)
                into.putIfAbsent(info.sha256, info)
            }
        } catch (e: Exception) {
            problems += "A signature block could not be read."
        }
    }

    /**
     * Checks an AAB's JAR signature: every file is read through [JarFile], which compares each digest with the signed
     * manifest. true = all files are covered and match. false = a mismatch, or files that are not covered.
     */
    private fun verifyJar(file: File, problems: MutableList<String>): Boolean {
        return try {
            JarFile(file, true).use { jar ->
                var uncovered = 0
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    jar.getInputStream(entry).use { drain(it) }
                    if (isSignatureFile(entry.name) || entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                    if (entry.codeSigners.isNullOrEmpty()) uncovered++
                }
                if (uncovered > 0) {
                    problems += "$uncovered file(s) in the bundle are not covered by its signature."
                    false
                } else {
                    true
                }
            }
        } catch (e: SecurityException) {
            problems += "The signature does not match the contents of the bundle."
            false
        } catch (e: IOException) {
            problems += "The bundle's signature could not be checked."
            false
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(32 * 1024)
        while (input.read(buffer) >= 0) {
            // reading is what makes JarFile verify the digest
        }
    }
}
