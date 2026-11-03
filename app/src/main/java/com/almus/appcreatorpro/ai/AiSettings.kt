package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.data.storage.KeyValueStore
import com.almus.appcreatorpro.service.github.SecretStore

/** Address and access code of the user's ALMUS backend. [toString] hides the code. */
class AiBackendConfig(val baseUrl: String, val accessCode: String) {
    override fun toString(): String = "AiBackendConfig($baseUrl)"
}

sealed interface UrlCheck {
    data class Ok(val url: String) : UrlCheck
    data class Bad(val reason: String) : UrlCheck
}

object BackendUrlValidator {
    /** Plain http is only accepted for a backend running on the developer's own machine or the Android emulator. */
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun validate(raw: String): UrlCheck {
        val text = raw.trim()
        if (text.isEmpty()) return UrlCheck.Bad("Enter the address of your ALMUS backend.")
        if (text.any { it.isWhitespace() }) return UrlCheck.Bad("The address must not contain spaces.")
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return UrlCheck.Bad("The address must start with https://")
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && scheme != "http") return UrlCheck.Bad("The address must start with https://")
        val rest = text.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.contains('@')) return UrlCheck.Bad("Do not put a user name or password in the address.")
        if (rest.contains('?') || rest.contains('#')) return UrlCheck.Bad("The address must not contain ? or #.")
        val host = authority.substringBeforeLast(':', authority).lowercase()
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return UrlCheck.Bad("The address has no valid host name.")
        val port = if (authority.contains(':')) authority.substringAfterLast(':') else null
        if (port != null && (port.isEmpty() || !port.all { it.isDigit() } || port.toInt() !in 1..65535)) return UrlCheck.Bad("The port number is not valid.")
        if (scheme == "http" && host !in loopbackHosts) return UrlCheck.Bad("Use https:// - plain http is only allowed for localhost or the emulator (10.0.2.2).")
        return UrlCheck.Ok(text.trimEnd('/'))
    }
}

/**
 * Where the backend settings live. The access code goes ONLY into the [SecretStore] (encrypted with an Android
 * Keystore key); the address and the chosen model are not secret. There is no Gemini API key anywhere in the app.
 */
class AiSettingsStore(private val meta: KeyValueStore, private val secrets: SecretStore) {

    fun baseUrl(): String? = meta.getString(KEY_URL)

    fun hasAccessCode(): Boolean = !secrets.get(KEY_CODE).isNullOrBlank()

    fun config(): AiBackendConfig? {
        val url = baseUrl() ?: return null
        val code = secrets.get(KEY_CODE)?.takeIf { it.isNotBlank() } ?: return null
        return AiBackendConfig(url, code)
    }

    sealed interface SaveResult {
        data object Saved : SaveResult
        data class Invalid(val reason: String) : SaveResult
    }

    /** [accessCode] null or blank = keep the stored one. */
    fun save(rawUrl: String, accessCode: String?): SaveResult {
        val url = when (val c = BackendUrlValidator.validate(rawUrl)) {
            is UrlCheck.Ok -> c.url
            is UrlCheck.Bad -> return SaveResult.Invalid(c.reason)
        }
        val code = accessCode?.trim()?.takeIf { it.isNotEmpty() }
        if (code == null && !hasAccessCode()) return SaveResult.Invalid("Enter your access code.")
        if (code != null && code.length < MIN_CODE_LENGTH) return SaveResult.Invalid("The access code is too short.")
        meta.putString(KEY_URL, url)
        if (code != null) secrets.put(KEY_CODE, code)
        return SaveResult.Saved
    }

    fun clear() {
        meta.remove(KEY_URL)
        meta.remove(KEY_MODEL)
        secrets.remove(KEY_CODE)
    }

    fun selectedModel(): String? = meta.getString(KEY_MODEL)

    fun selectModel(id: String?) {
        if (id.isNullOrBlank()) meta.remove(KEY_MODEL) else meta.putString(KEY_MODEL, id)
    }

    companion object {
        const val MIN_CODE_LENGTH = 8
        private const val KEY_URL = "ai_backend_url"
        private const val KEY_MODEL = "ai_model"
        private const val KEY_CODE = "ai_access_code"
    }
}
