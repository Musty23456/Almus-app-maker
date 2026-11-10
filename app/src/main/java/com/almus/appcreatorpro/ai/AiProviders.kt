package com.almus.appcreatorpro.ai

/**
 * Phase 13: the seam that keeps the AI provider replaceable. Everything above it (chat, editing, the agent) talks to an
 * [AiBackend] and never to a vendor class. Today exactly one provider exists: Gemini, reached through the ALMUS backend.
 * Adding another (a different model service, an on-device model) means writing one [AiBackend] and registering it here.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val backend: AiBackend
}

class SimpleAiProvider(override val id: String, override val displayName: String, override val backend: AiBackend) : AiProvider

class AiProviderRegistry(providers: List<AiProvider>, private val defaultId: String) {
    private val byId: Map<String, AiProvider>

    init {
        require(providers.isNotEmpty()) { "At least one AI provider is needed." }
        require(providers.map { it.id }.toSet().size == providers.size) { "Provider ids must be unique." }
        require(providers.any { it.id == defaultId }) { "The default provider is not registered." }
        byId = providers.associateBy { it.id }
    }

    fun all(): List<AiProvider> = byId.values.toList()
    fun get(id: String?): AiProvider? = id?.let { byId[it] }

    /** The provider named [id], or the default one when [id] is null or unknown. */
    fun resolve(id: String?): AiProvider = get(id) ?: byId.getValue(defaultId)
    fun default(): AiProvider = byId.getValue(defaultId)

    companion object {
        const val GEMINI_ID = "gemini"

        fun single(backend: AiBackend, id: String = GEMINI_ID, displayName: String = "Gemini (through the ALMUS backend)"): AiProviderRegistry =
            AiProviderRegistry(listOf(SimpleAiProvider(id, displayName, backend)), id)
    }
}
