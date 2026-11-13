package com.almus.appcreatorpro.ai

/**
 * Phase 24: the project knowledge Smart mode uses, as two small functions so the chat engine stays free of Android classes.
 * [profile] is the cached [ProjectProfile]; [knownPaths] are the files that exist right now (follow-up files are checked against them).
 */
class ChatAssistant(
    val profile: suspend () -> ProjectProfile?,
    val knownPaths: suspend () -> Set<String>,
) {
    companion object {
        fun forProject(provider: ProjectProfileProvider, files: AiProjectFiles): ChatAssistant = ChatAssistant(
            profile = { provider.profile() },
            knownPaths = { files.list().filter { ContextPolicy.isTextPath(it) && !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }.toSet() },
        )
    }
}
