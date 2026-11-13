package com.almus.appcreatorpro.ai

/** A tappable idea shown above the input. Tapping it only fills the text box: nothing is sent until the user presses Send. */
data class AssistantSuggestion(val label: String, val prompt: String, val intent: CodingIntent)

/**
 * Phase 24: context-aware suggestions. What is offered depends on the project profile, the conversation so far and the files
 * attached. Every suggested prompt is written so that [IntentDetector] reads it back as the intent it was made for, in the
 * language it is written in (a test checks this for every suggestion).
 */
object SuggestionEngine {
    private fun pick(language: ChatLanguage, en: String, ha: String) = if (language == ChatLanguage.HAUSA) ha else en

    private fun make(language: ChatLanguage, intent: CodingIntent, en: String, ha: String): AssistantSuggestion {
        val text = pick(language, en, ha)
        return AssistantSuggestion(text, text, intent)
    }

    fun suggest(
        language: ChatLanguage,
        profile: ProjectProfile?,
        turns: List<ChatTurn>,
        attachedPaths: List<String>,
        limit: Int = 4,
    ): List<AssistantSuggestion> {
        val out = ArrayList<AssistantSuggestion>()
        val lastAnswer = turns.lastOrNull { it.role == AiRole.MODEL && it.state == TurnState.DONE && it.text.isNotBlank() }
        val file = attachedPaths.singleOrNull()?.substringAfterLast('/')

        when {
            // 1. files are attached: things to do with them
            attachedPaths.isNotEmpty() -> {
                val what = file ?: pick(language, "these files", "waɗannan fayilolin")
                out += make(language, CodingIntent.EXPLAIN, "Explain $what", "Bayyana min $what")
                out += make(language, CodingIntent.FIND_BUGS, "Find bugs in $what", "Nemo kuskure (bugs) a $what")
                out += make(language, CodingIntent.OPTIMIZE, "Optimize $what", "Inganta $what")
                out += make(language, CodingIntent.REFACTOR, "Refactor $what", "Sake tsara $what")
            }
            // 2. the assistant just answered: next steps that fit the kind of answer
            lastAnswer != null -> out += followUps(language, lastAnswer)
            // 3. a fresh conversation: starters that fit this project
            else -> out += starters(language, profile)
        }
        return out.distinctBy { it.prompt }.take(limit)
    }

    private fun followUps(language: ChatLanguage, last: ChatTurn): List<AssistantSuggestion> {
        val hasCode = last.text.contains("```")
        val explain = make(language, CodingIntent.EXPLAIN, "Explain this code", "Bayyana min wannan code")
        val bugs = make(language, CodingIntent.FIND_BUGS, "Find bugs in this code", "Nemo kuskure (bugs) a wannan code")
        val optimize = make(language, CodingIntent.OPTIMIZE, "Optimize this code", "Inganta wannan code")
        val refactor = make(language, CodingIntent.REFACTOR, "Refactor this code", "Sake tsara wannan code")
        val tests = make(language, CodingIntent.GENERATE, "Write unit tests for this", "Rubuta unit tests don wannan")
        return when (last.intent) {
            CodingIntent.FIND_BUGS -> listOf(make(language, CodingIntent.FIND_BUGS, "Fix the bugs you found", "Gyara kuskuren da ka samo"), explain, tests)
            CodingIntent.OPTIMIZE -> listOf(make(language, CodingIntent.EXPLAIN, "Explain what you optimized", "Bayyana min abin da ka inganta"), bugs, tests)
            CodingIntent.EXPLAIN -> listOf(bugs, optimize, refactor)
            CodingIntent.REFACTOR -> listOf(bugs, tests, explain)
            else -> if (hasCode) listOf(explain, bugs, optimize, tests) else listOf(explain, bugs)
        }
    }

    private fun starters(language: ChatLanguage, profile: ProjectProfile?): List<AssistantSuggestion> {
        val out = ArrayList<AssistantSuggestion>()
        val compose = profile?.usesCompose == true
        val xml = profile?.uiToolkit == UiToolkit.XML_VIEWS
        val web = profile?.isWeb == true
        out += when {
            compose -> make(language, CodingIntent.UI_COMPONENT, "Create a modern login screen", "Ƙirƙiri login screen na zamani mai kyau")
            xml -> make(language, CodingIntent.UI_COMPONENT, "Create a login screen layout", "Ƙirƙiri shafin shiga (login screen) mai kyau")
            web -> make(language, CodingIntent.UI_COMPONENT, "Create a responsive login page", "Ƙirƙiri shafin shiga (login page) mai kyau")
            else -> make(language, CodingIntent.UI_COMPONENT, "Create a modern login screen", "Ƙirƙiri login screen na zamani mai kyau")
        }
        if (compose) out += make(language, CodingIntent.UI_COMPONENT, "Create a reusable button composable", "Ƙirƙiri maballi (button composable) da za a iya sake amfani da shi")
        val hasHttp = profile != null && (profile.hasLibrary("Retrofit") || profile.hasLibrary("OkHttp") || profile.hasLibrary("Ktor client"))
        out += if (hasHttp) make(language, CodingIntent.API_INTEGRATION, "Add a new API endpoint call", "Haɗa sabon API endpoint")
        else make(language, CodingIntent.API_INTEGRATION, "Integrate a REST API to load data", "Haɗa API na REST don ɗora bayanai")
        out += make(language, CodingIntent.FUNCTION, "Write a function that validates an email address", "Rubuta function da zai tabbatar da adireshin email")
        out += make(language, CodingIntent.CLASS, "Create a User data class and a repository", "Rubuta class na User da repository")
        return out
    }

    private val hausaToEnglish = mapOf(
        "shafi" to "screen page", "shafin" to "screen page", "shiga" to "login", "rajista" to "register signup", "maballi" to "button", "maballin" to "button",
        "fom" to "form", "allo" to "screen", "allon" to "screen", "fuska" to "screen", "aji" to "class", "ajin" to "class", "launi" to "color theme",
    )

    /**
     * Files that probably matter for [text] (by name), to be offered as tappable chips. Nothing is attached until the user taps.
     * Hausa words are mapped to the English words that file names use ("shafin shiga" finds LoginScreen.kt).
     * Returns nothing when files are already attached.
     */
    fun suggestFiles(text: String, paths: List<String>, alreadyAttached: List<String>, limit: Int = 3): List<String> {
        if (alreadyAttached.isNotEmpty()) return emptyList()
        val extra = LanguageDetector.tokens(text).mapNotNull { hausaToEnglish[it] }.joinToString(" ")
        return ContextSuggester.suggest("$text $extra", paths, limit)
    }
}
