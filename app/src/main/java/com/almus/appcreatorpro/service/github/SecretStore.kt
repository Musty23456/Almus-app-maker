package com.almus.appcreatorpro.service.github

/** Holds secrets (the GitHub access token). Implementations must encrypt at rest. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Not persistent and not encrypted: for tests only. */
class InMemorySecretStore : SecretStore {
    private val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}
