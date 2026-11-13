package com.almus.appcreatorpro.ai

/**
 * Phase 24: follow-up conversations. "Make it blue" or "now add a test" refers to the code of the previous exchange.
 * The history already goes to the model; this adds the part that was missing: the FILES. A follow-up without attachments
 * gets the files of the previous exchange attached again (their CURRENT text, since they may have been edited or changed
 * by an applied suggestion), and the user is shown which files those are. Plain Kotlin, unit-tested on a normal JVM.
 */
object FollowUpResolver {
    /** Words that point back at the previous exchange (English and Hausa, after [LanguageDetector.normalize]). */
    private val backReferences = setOf(
        "it", "this", "that", "them", "those", "these", "again", "also", "too", "same", "instead", "previous", "above", "earlier", "last", "now", "then",
        "wannan", "wancan", "shi", "ita", "su", "haka", "shima", "maimakon", "baya", "sama", "yanzu", "sannan",
    )

    private val creationIntents = setOf(CodingIntent.GENERATE, CodingIntent.FUNCTION, CodingIntent.CLASS, CodingIntent.UI_COMPONENT, CodingIntent.API_INTEGRATION)
    const val SHORT_MESSAGE_WORDS = 8

    data class CarryOver(val paths: List<String>, val inherited: List<String>) {
        val isFollowUp: Boolean get() = inherited.isNotEmpty()
    }

    /** The intent of the last answered request in [turns], so a short reply like "and in dark mode too" can continue it. */
    fun lastIntent(turns: List<ChatTurn>): CodingIntent? = turns.lastOrNull { it.role == AiRole.MODEL && it.intent != null }?.intent

    /** True when there is an earlier answer and the message points back at it (or is a short change request). */
    fun isFollowUp(text: String, turns: List<ChatTurn>, intent: CodingIntent): Boolean {
        if (turns.none { it.role == AiRole.MODEL && it.state == TurnState.DONE && it.text.isNotBlank() }) return false
        val words = LanguageDetector.tokens(text)
        if (words.isEmpty()) return false
        if (words.any { it in backReferences }) return true
        // A short message about existing code ("change the title to red") continues; a short NEW creation ("create a settings screen") does not.
        return words.size <= SHORT_MESSAGE_WORDS && intent !in creationIntents && intent != CodingIntent.QUESTION
    }

    /** Project-relative paths that the answer labelled with "File: path" before a code block, in order, without duplicates. */
    fun labelledPaths(answer: String): List<String> =
        ChatMarkdown.split(answer).filterIsInstance<ChatMarkdown.Block.Code>().mapNotNull { it.path }.distinct()

    /**
     * [turns] are the turns BEFORE the new message. [explicit] are the files the user attached to the new message: when there
     * are any, nothing is carried over. [known] are the project's current files; a path that no longer exists is dropped.
     */
    fun carryOver(text: String, turns: List<ChatTurn>, explicit: List<String>, known: Set<String>, intent: CodingIntent, limit: Int = ContextPolicy.MAX_FILES): CarryOver {
        if (explicit.isNotEmpty() || !isFollowUp(text, turns, intent)) return CarryOver(explicit, emptyList())
        val candidates = LinkedHashSet<String>()
        val lastUser = turns.lastOrNull { it.role == AiRole.USER && it.contextPaths.isNotEmpty() }
        lastUser?.contextPaths?.let { candidates.addAll(it) }
        val lastAnswer = turns.lastOrNull { it.role == AiRole.MODEL && it.state == TurnState.DONE && it.text.isNotBlank() }
        lastAnswer?.let { candidates.addAll(labelledPaths(it.text)) }
        val usable = candidates.filter { it in known }.take(limit)
        return CarryOver(usable, usable)
    }
}
