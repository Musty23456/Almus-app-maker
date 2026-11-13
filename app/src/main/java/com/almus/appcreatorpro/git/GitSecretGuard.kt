package com.almus.appcreatorpro.git

/**
 * Stops signing keys, passwords and tokens from being committed by accident. Findings never contain the secret itself,
 * only the file and a short reason, so they are safe to show and to log.
 */
object GitSecretGuard {
    data class Finding(val path: String, val reason: String)

    const val INTERNAL_SUFFIX = ".almus-tmp"

    private val secretFileNames = setOf(
        "local.properties", "keystore.properties", "signing.properties", "secrets.properties", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
    )
    private val secretExtensions = listOf(".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")

    private val textPatterns: List<Pair<Regex, String>> = listOf(
        Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}""") to "looks like a GitHub token",
        Regex("""\bgithub_pat_[A-Za-z0-9_]{30,}""") to "looks like a GitHub token",
        Regex("""-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----""") to "contains a private key",
        Regex("""\bAKIA[0-9A-Z]{16}\b""") to "looks like an AWS access key",
        Regex("""\bAIza[0-9A-Za-z_\-]{35}\b""") to "looks like a Google API key",
        Regex("""\bxox[baprs]-[0-9A-Za-z\-]{10,}""") to "looks like a Slack token",
        Regex("""(?i)\b(?:storePassword|keyPassword)\s*[=:]?\s*["'][^"'$\s]{4,}["']""") to "contains a signing password",
    )

    /** Reason this PATH is risky to commit, or null. */
    fun checkPath(path: String): String? {
        val name = path.substringAfterLast('/')
        val lower = name.lowercase()
        return when {
            lower in secretFileNames -> "\"$name\" usually holds local secrets or machine settings"
            lower == ".env" || lower.startsWith(".env.") -> "\"$name\" usually holds secrets"
            secretExtensions.any { lower.endsWith(it) } -> "\"$name\" looks like a key or certificate file"
            else -> null
        }
    }

    /** One finding per reason, so a big file cannot flood the list. */
    fun scanText(path: String, text: String): List<Finding> =
        textPatterns.filter { (re, _) -> re.containsMatchIn(text) }.map { (_, why) -> Finding(path, why) }.distinct()

    fun isInternalPath(path: String): Boolean = path.endsWith(INTERNAL_SUFFIX)
}
