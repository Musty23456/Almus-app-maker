package com.almus.appcreatorpro.ai

/**
 * Phase 24: what the user wants from the coding assistant, understood from plain English or Hausa.
 * [task] is the backend task that already exists (the backend is NOT changed in Phase 24); the intent-specific rules
 * travel in the request text (see [CodingPrompts]). [needsExistingCode] tells the UI to ask for a file when none is attached.
 */
enum class CodingIntent(
    val label: String,
    val hausaLabel: String,
    val task: AiTaskKind,
    val needsExistingCode: Boolean,
    val writesCode: Boolean,
) {
    QUESTION("Question", "Tambaya", AiTaskKind.CHAT, false, false),
    EXPLAIN("Code explanation", "Bayanin code", AiTaskKind.EXPLAIN, true, false),
    GENERATE("Code generation", "Rubuta code", AiTaskKind.GENERATE_CODE, false, true),
    FUNCTION("Function generation", "Rubuta function", AiTaskKind.GENERATE_CODE, false, true),
    CLASS("Class generation", "Rubuta class", AiTaskKind.GENERATE_CODE, false, true),
    UI_COMPONENT("UI component", "Ƙirƙirar UI", AiTaskKind.GENERATE_CODE, false, true),
    API_INTEGRATION("API integration", "Haɗa API", AiTaskKind.GENERATE_CODE, false, true),
    OPTIMIZE("Optimization", "Inganta code", AiTaskKind.EDIT_CODE, true, true),
    FIND_BUGS("Bug finding", "Nemo bugs", AiTaskKind.CHAT, true, true),
    REFACTOR("Refactoring", "Sake tsara code", AiTaskKind.EDIT_CODE, true, true),
    EDIT("Code change", "Canza code", AiTaskKind.EDIT_CODE, true, true),
    ;

    fun labelFor(language: ChatLanguage): String = if (language == ChatLanguage.HAUSA) hausaLabel else label
}

enum class IntentConfidence { HIGH, MEDIUM, LOW }

/** [signals] are the words that decided it, so the UI (and a test) can show why. */
data class IntentGuess(val intent: CodingIntent, val confidence: IntentConfidence, val language: ChatLanguage, val signals: List<String>)

object IntentRouting {
    /**
     * The backend task for an intent. One attached file: "edit_code" (the backend answers with the complete new file).
     * Several files: "generate_code" (one labelled code block per file). Edits with no file attached go to plain chat, where
     * the prompt tells the model to ask which file to attach instead of guessing.
     */
    fun taskFor(intent: CodingIntent, attachedCount: Int): AiTaskKind = when (intent) {
        CodingIntent.OPTIMIZE, CodingIntent.REFACTOR, CodingIntent.EDIT -> when {
            attachedCount == 1 -> AiTaskKind.EDIT_CODE
            attachedCount > 1 -> AiTaskKind.GENERATE_CODE
            else -> AiTaskKind.CHAT
        }
        else -> intent.task
    }
}

/**
 * Reads a request in English or Hausa (or a mix: programming words are English in both) and decides what the user wants.
 * Rules, in this order, so a specific wish beats a general one:
 *  1. An ACTION on code (explain, find bugs, optimize, refactor), when a clear word for it is present.
 *  2. A creation verb followed by the thing to create: the FIRST thing named decides ("a ViewModel for the login screen" is a class).
 *     A creation verb followed by "this/my/the current..." names something that exists, so it is a change (3), not a creation.
 *  3. A change verb (change, update, remove, fix, "make it ...", Hausa "canza", "cire", "gyara").
 *  4. Creation with no recognised thing: plain code generation. An API word on its own is API integration.
 *  5. Otherwise a question. A short message that is none of these continues the previous request ([previous]).
 */
object IntentDetector {
    private class Rule(val intent: CodingIntent, val weight: Int, val signal: String, pattern: String) {
        val regex = Regex(pattern)
    }

    // ---- 1. actions on code (patterns run on LanguageDetector.normalize text) ----------------------------------------------
    private val actionRules: List<Rule> = listOf(
        // explain
        Rule(CodingIntent.EXPLAIN, 3, "explain", """\bexplain\w*\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "walk through", """\bwalk me through\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "what does it do", """\bwhat (does|is) (this|that|it|the)\b.*\b(do|does|mean|means)\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "how does it work", """\bhow (does|do) (this|that|it|the)\b.*\bwork\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "describe the code", """\b(describe|summari[sz]e|understand) (this|that|the|my) (code|function|class|method|file|screen)\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "bayyana", """\bbayyana\w*\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "fassara", """\bfassara\w*\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "me yake nufi", """\b(me|mene|menene) (yake|ke|take) nufi\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "yaya yake aiki", """\byaya\b.*\b(yake|take|ke) aiki\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "menene wannan", """\bmene ?ne (wannan|wancan)\b"""),
        Rule(CodingIntent.EXPLAIN, 3, "gaya min", """\bgaya (min|mini|mana)\b.*\b(code|function|class|aikin)\b"""),
        // find bugs
        Rule(CodingIntent.FIND_BUGS, 3, "bug", """\bbugs?\b|\bbuggy\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "debug", """\bdebug\w*\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "find the problem", """\bfind (the |any |all |some )?(errors?|problems?|issues?|mistakes?|flaws?)\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "what is wrong", """\bwhat.?s wrong\b|\bwhat is wrong\b|\bwrong with\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "not working", """\b(not|isn.?t|doesn.?t|does not|won.?t|stopped) (work|working|run|running|load|loading|show|showing)\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "crash", """\bcrash\w*\b|\bforce close\b|\bapp (closes|stops)\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "exception", """\bexception\b|\bnull ?pointer\b|\bnpe\b|\bstack ?trace\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "why does it fail", """\bwhy (does|is|do|am|did)\b.*\b(fail|crash|error|null|not|wrong|empty|blank)\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "fix the error", """\bfix (the |this |my |that )?(error|errors|bug|crash|issue|problem)\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "review", """\bcode review\b|\breview (this|my|the|that)\b|\bcheck (this|my|the) code\b|\blook for (errors|problems|bugs)\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "kuskure", """\bkuskur\w*\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "matsala", """\bmatsal\w*\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "ba ya aiki", """\bba (ya|ta|su|ka|ki|a) aiki\b|\bbaya aiki\b|\bbata aiki\b|\bbai aiki\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "rushewa", """\brushe\w*\b|\brufe kansa\b|\bya (rufe|fadi)\b"""),
        Rule(CodingIntent.FIND_BUGS, 3, "duba code", """\bduba\b.*\b(code|lambar|function)\b"""),
        // optimize
        Rule(CodingIntent.OPTIMIZE, 3, "optimize", """\boptimi[sz]\w*\b"""),
        Rule(CodingIntent.OPTIMIZE, 3, "performance", """\bperformance\b|\bperformant\b"""),
        Rule(CodingIntent.OPTIMIZE, 3, "faster", """\bfaster\b|\bspeed ?up\b|\bspeed (it|this) up\b|\bslow\b|\blag(gy|ging)?\b|\bjank\w*\b"""),
        Rule(CodingIntent.OPTIMIZE, 3, "efficient", """\befficien\w+\b|\breduce (memory|battery|size|allocations)\b|\bmemory leak\b"""),
        Rule(CodingIntent.OPTIMIZE, 3, "inganta", """\binganta\w*\b"""),
        Rule(CodingIntent.OPTIMIZE, 3, "sauri", """\bgaggauta\b|\bsaurin\b|\bmai sauri\b|\bkara sauri\b|\bsauri\b"""),
        // refactor
        Rule(CodingIntent.REFACTOR, 3, "refactor", """\brefactor\w*\b"""),
        Rule(CodingIntent.REFACTOR, 3, "clean up", """\bclean ?up\b|\bclean(er)? code\b|\bclean (this|it|the)\b"""),
        Rule(CodingIntent.REFACTOR, 3, "restructure", """\brestructur\w+\b|\breorgani[sz]\w+\b|\bsimplif\w+\b"""),
        Rule(CodingIntent.REFACTOR, 3, "extract", """\bextract (a |the |this )?(function|method|class|component|composable|interface|logic)\b"""),
        Rule(CodingIntent.REFACTOR, 3, "split", """\bsplit (this|the|it|into)\b|\bseparate (the )?concerns\b|\bdecouple\b"""),
        Rule(CodingIntent.REFACTOR, 3, "more readable", """\b(more )?(readable|maintainable|modular|testable|reusable)\b"""),
        Rule(CodingIntent.REFACTOR, 3, "migrate", """\bmigrat\w+\b|\bconvert (this |the |it |my )?(java )?(code )?(from \w+ )?to (kotlin|compose|mvvm|coroutines|java|viewbinding)\b"""),
        Rule(CodingIntent.REFACTOR, 3, "sake tsara", """\bsake (tsara|rubuta)\b|\btsaftace\b|\btsafta\b|\bsaukak\w+\b|\braba\b"""),
    )

    private val actionPriority = listOf(CodingIntent.EXPLAIN, CodingIntent.FIND_BUGS, CodingIntent.OPTIMIZE, CodingIntent.REFACTOR)

    // ---- 2. creation: verb, then the first thing named ------------------------------------------------------------------------
    private val creationVerbs = setOf(
        "create", "generate", "write", "implement", "develop", "build", "design", "scaffold", "produce", "draft",
        "rubuta", "kirkiri", "kirkira", "samar", "gina",
    )

    /** "make", "add" and "hada" only create when a thing follows ("make a ...", "add a new ..."); "make it blue" is a change. */
    private val weakCreationVerbs = setOf("make", "add", "hada", "kara", "yi", "sa")
    private val articles = setOf("a", "an", "new", "some", "another", "sabon", "sabuwar", "wani", "wata", "mini", "min", "me")

    /** Words that point at something that already exists: the request changes it instead of creating it. */
    private val existingMarkers = setOf("this", "that", "my", "current", "existing", "above", "following", "these", "those", "wannan", "wancan", "nawa", "wanda")
    private val stopAtWords = setOf("and", "then", "but", "kuma", "sannan")

    private const val MAX_GAP = 7

    private val nouns: Map<String, CodingIntent> = buildMap {
        for (w in listOf("function", "method", "helper", "utility", "util", "fun", "fanshon", "funshon", "extension")) put(w, CodingIntent.FUNCTION)
        for (w in listOf(
            "class", "interface", "enum", "viewmodel", "repository", "usecase", "entity", "dao", "adapter", "model", "manager", "handler",
            "datasource", "aji", "ajin", "sealed", "singleton", "mapper", "factory",
        )) put(w, CodingIntent.CLASS)
        for (w in listOf(
            "screen", "button", "dialog", "card", "login", "signin", "signup", "register", "registration", "form", "navbar", "toolbar",
            "topbar", "bottombar", "layout", "composable", "component", "widget", "page", "dashboard", "textfield", "chip", "tab",
            "splash", "onboarding", "profile", "menu", "drawer", "snackbar", "toast", "dropdown", "spinner", "checkbox", "switch",
            "slider", "recyclerview", "ui", "fragment", "activity", "shafi", "shafin", "maballi", "maballin", "fom", "allo", "allon",
            "fuska", "fuskar", "shiga", "rajista", "list", "listview", "carousel", "banner", "avatar", "appbar",
        )) put(w, CodingIntent.UI_COMPONENT)
        for (w in listOf("api", "retrofit", "okhttp", "ktor", "volley", "endpoint", "graphql", "websocket", "webhook", "firebase", "oauth", "rest", "httpurlconnection")) put(w, CodingIntent.API_INTEGRATION)
        for (w in listOf("test", "unit", "preview")) put(w, CodingIntent.GENERATE)
    }

    /** Two-word names, joined before the lookup: "view model", "sign in", "text field", "use case", "bottom bar", "app bar", "rest api". */
    private val joinedPairs = mapOf(
        "view model" to "viewmodel", "sign in" to "signin", "sign up" to "signup", "text field" to "textfield", "use case" to "usecase",
        "bottom bar" to "bottombar", "bottom nav" to "bottombar", "top bar" to "topbar", "app bar" to "appbar", "recycler view" to "recyclerview",
        "list view" to "listview", "data source" to "datasource", "log in" to "login", "http url" to "httpurlconnection",
    )

    private val apiOnlyNouns = setOf("api", "retrofit", "okhttp", "ktor", "volley", "endpoint", "graphql", "websocket", "webhook", "oauth", "httpurlconnection")

    // ---- 3. changes ---------------------------------------------------------------------------------------------------------
    private val editRule = Regex(
        """\b(change|modify|update|replace|remove|delete|rename|adjust|tweak|swap|rewrite|fix|edit|add|insert|include|canza|sauya|cire|gyara|sanya|maye|kara)\b|\bmake (it|this|that|the|them)\b|\bset\b.*\bto\b|\bsa shi\b|\bmaye gurbin\b""",
    )

    private val jointRegex = joinedPairs.keys.map { Regex("\\b" + it.replace(" ", "\\s+") + "\\b") to joinedPairs.getValue(it) }

    private fun joinPairs(text: String): String {
        var t = text
        for ((re, to) in jointRegex) t = re.replace(t, to)
        return t
    }

    /** Lookup with a plain plural: "screens" finds "screen", "buttons" finds "button". */
    private fun nounOf(token: String): CodingIntent? =
        nouns[token] ?: if (token.endsWith("es") && token.length > 4) nouns[token.dropLast(2)] ?: nouns[token.dropLast(1)]
        else if (token.endsWith("s") && token.length > 3) nouns[token.dropLast(1)] else null

    fun detect(text: String, hasAttachedFiles: Boolean = false, previous: CodingIntent? = null): IntentGuess {
        val language = LanguageDetector.detect(text)
        val norm = joinPairs(LanguageDetector.normalize(text))
        val words = LanguageDetector.tokens(norm)
        if (words.isEmpty()) return IntentGuess(CodingIntent.QUESTION, IntentConfidence.LOW, language, emptyList())

        // 1. actions on code
        val score = HashMap<CodingIntent, Int>()
        val signals = HashMap<CodingIntent, MutableList<String>>()
        for (r in actionRules) {
            if (r.regex.containsMatchIn(norm)) {
                score[r.intent] = (score[r.intent] ?: 0) + r.weight
                signals.getOrPut(r.intent) { ArrayList() }.add(r.signal)
            }
        }
        val action = actionPriority.filter { (score[it] ?: 0) >= 3 }.maxByOrNull { score.getValue(it) }
        // An action word wins, unless a creation verb comes FIRST and names a thing ("Create a function to optimize images" is a function).
        if (action != null) {
            val created = firstCreation(words)
            val creationFirst = created != null && !created.existing && created.intent != null && created.verbIndex < actionWordIndex(words, action)
            if (!creationFirst) return IntentGuess(action, IntentConfidence.HIGH, language, signals.getValue(action))
        }

        // 2. creation
        val c = firstCreation(words)
        if (c != null && !c.existing && c.intent != null) {
            return IntentGuess(c.intent, IntentConfidence.HIGH, language, listOf(c.verb, c.noun.orEmpty()))
        }

        // 3. change
        if (editRule.containsMatchIn(norm)) {
            val hit = editRule.find(norm)?.value.orEmpty().trim()
            return IntentGuess(CodingIntent.EDIT, if (hasAttachedFiles) IntentConfidence.HIGH else IntentConfidence.MEDIUM, language, listOf(hit))
        }

        // 4. creation with no recognised thing, or an API word on its own
        if (c != null && !c.existing) return IntentGuess(CodingIntent.GENERATE, IntentConfidence.MEDIUM, language, listOf(c.verb))
        val apiWord = words.firstOrNull { it in apiOnlyNouns }
        if (apiWord != null) return IntentGuess(CodingIntent.API_INTEGRATION, IntentConfidence.MEDIUM, language, listOf(apiWord))
        val uiWords = words.filter { nounOf(it) == CodingIntent.UI_COMPONENT }
        if (uiWords.size >= 2) return IntentGuess(CodingIntent.UI_COMPONENT, IntentConfidence.LOW, language, uiWords.take(3))

        // 5. a short message that fits nothing continues the previous request ("and in dark mode too")
        if (previous != null && previous != CodingIntent.QUESTION && words.size <= 12 && !text.trim().endsWith("?")) {
            return IntentGuess(previous, IntentConfidence.LOW, language, listOf("follow-up"))
        }
        return IntentGuess(CodingIntent.QUESTION, IntentConfidence.LOW, language, emptyList())
    }

    private class Creation(val verb: String, val verbIndex: Int, val intent: CodingIntent?, val noun: String?, val existing: Boolean)

    /** The first creation verb with what follows it, or null. [Creation.existing] is true when "this/my/the current..." comes before any thing named. */
    private fun firstCreation(words: List<String>): Creation? {
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val strong = w in creationVerbs
            val weak = !strong && w in weakCreationVerbs && i + 1 < words.size && words[i + 1] in articles
            val ino = !strong && !weak && w == "ina" && i + 1 < words.size && words[i + 1].let { it == "so" || it == "son" || it == "bukatar" }
            val want = !strong && !weak && !ino && (w == "need" || w == "want") && i > 0 && words[i - 1] == "i"
            if (strong || weak || ino || want) {
                var j = i + 1
                var existing = false
                var steps = 0
                while (j < words.size && steps < MAX_GAP) {
                    val t = words[j]
                    if (t in stopAtWords) break
                    val n = nounOf(t)
                    if (n != null) return Creation(w, i, n, t, existing = false)
                    if (t in existingMarkers) existing = true
                    j++; steps++
                }
                // a strong verb with nothing named is still a generation request ("write the code for me")
                return if (strong || ino || want) Creation(w, i, null, null, existing) else if (existing) Creation(w, i, null, null, true) else null
            }
            i++
        }
        return null
    }

    private fun actionWordIndex(words: List<String>, action: CodingIntent): Int {
        val needles = when (action) {
            CodingIntent.EXPLAIN -> listOf("explain", "bayyana", "fassara", "describe", "understand", "walk", "what", "how", "menene", "mene", "yaya", "gaya")
            CodingIntent.FIND_BUGS -> listOf("bug", "bugs", "debug", "error", "errors", "crash", "wrong", "fix", "review", "kuskure", "matsala", "check", "why")
            CodingIntent.OPTIMIZE -> listOf("optimize", "optimise", "faster", "performance", "inganta", "speed", "efficient")
            else -> listOf("refactor", "clean", "restructure", "simplify", "extract", "split", "migrate", "convert", "sake", "tsaftace")
        }
        val idx = words.indexOfFirst { w -> needles.any { w.startsWith(it) } }
        return if (idx < 0) Int.MAX_VALUE else idx
    }
}
