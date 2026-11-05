package com.almus.appcreatorpro.ai

/**
 * Keeps secrets away from the model. Used on every file before it is shown to the user as "will be sent", and on every
 * generated file before it is written into a project. The backend runs a second scan (defence in depth).
 */
object SecretScanner {
    data class Redaction(val text: String, val count: Int)

    private val replacements: List<Pair<Regex, String>> = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----") to "[REDACTED PRIVATE KEY]",
        Regex("AIza[0-9A-Za-z_\\-]{35}") to "[REDACTED API KEY]",
        Regex("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b") to "[REDACTED GITHUB TOKEN]",
        Regex("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b") to "[REDACTED GITHUB TOKEN]",
        Regex("\\bsk-[A-Za-z0-9_\\-]{30,}\\b") to "[REDACTED API KEY]",
        Regex("\\bAKIA[0-9A-Z]{16}\\b") to "[REDACTED AWS KEY]",
        Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b") to "[REDACTED SLACK TOKEN]",
    )

    // name = "value" assignments of secret-looking names. A value that is plainly a reference (BuildConfig.X,
    // System.getenv(...), $variable, a placeholder) is not a secret.
    private val assignment = Regex(
        "(\\b(?:storePassword|keyPassword|password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\b\\s*[=:]\\s*)([\"']?)([^\\s\"',;]{4,})([\"']?)",
        RegexOption.IGNORE_CASE,
    )

    private fun isReference(value: String): Boolean =
        value.startsWith("$") || value.startsWith("[REDACTED") || value.contains("getenv") || value.contains("BuildConfig") ||
            value.contains("(") || value.equals("null", true) || value.equals("true", true) || value.equals("false", true) ||
            value.startsWith("<") || value.startsWith("{{") || value.all { it == '*' || it == 'x' || it == 'X' }

    fun redact(text: String): Redaction {
        var out = text
        var count = 0
        for ((re, rep) in replacements) {
            out = re.replace(out) { count++; rep }
        }
        out = assignment.replace(out) { m ->
            val value = m.groupValues[3]
            if (isReference(value)) m.value else { count++; "${m.groupValues[1]}${m.groupValues[2]}[REDACTED]${m.groupValues[4]}" }
        }
        return Redaction(out, count)
    }

    fun containsSecret(text: String): Boolean = redact(text).count > 0

    /** Files that hold credentials by their nature: never read for the AI, never written by the AI. */
    private val credentialName = Regex(
        "(?i)(?:^|/)(?:local\\.properties|keystore\\.properties|secrets?\\.properties|google-services\\.json|\\.env(?:\\..*)?|id_rsa|id_ed25519|\\.npmrc|\\.netrc)$|\\.(?:jks|keystore|p12|pfx|pem|key|p8|der|crt)$",
    )

    private val secretishName = Regex("(?i)secret|credential|password|passwd|token|keystore|private")
    private val codeExtensions = setOf("kt", "java", "kts")

    fun isCredentialFile(path: String): Boolean = credentialName.containsMatchIn(path)

    /**
     * True when [path] must not be sent. Credential files are always blocked. Other files whose NAME sounds secret
     * (a "secrets.xml", a "tokens.json") are blocked too, except source code, whose content is scanned instead.
     */
    fun isSensitivePath(path: String): Boolean {
        if (isCredentialFile(path)) return true
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext !in codeExtensions && secretishName.containsMatchIn(name)
    }
}
