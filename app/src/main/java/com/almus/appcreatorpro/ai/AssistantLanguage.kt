package com.almus.appcreatorpro.ai

/**
 * Phase 24: the language a user writes in. The assistant answers in the same language, but programming terms, code,
 * identifiers and file paths always stay in English. Plain Kotlin (no Android classes), so it is unit-tested on a normal JVM.
 */
enum class ChatLanguage(val label: String) {
    ENGLISH("English"),
    HAUSA("Hausa"),
}

object LanguageDetector {
    /**
     * Lower-case text with the Hausa hooked letters written as their plain forms (so "ƙirƙiri" and "kirkiri" are the same
     * word) and typographic apostrophes made plain. Everything else is kept, so offsets are not meaningful afterwards.
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when (ch) {
                'ɓ' -> sb.append('b')
                'ɗ' -> sb.append('d')
                'ƙ' -> sb.append('k')
                'ƴ' -> sb.append('y')
                '’', 'ʼ', '‘', '`' -> sb.append('\'')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Words that are Hausa and not English (after [normalize]). Short words that are also English words are left out. */
    private val hausaWords = setOf(
        "kuma", "wannan", "wancan", "domin", "yadda", "shafin", "shafi", "ina", "inda", "zan", "zaka", "zaki", "kana", "kake", "yana", "suna",
        "muna", "mini", "min", "maka", "maki", "mana", "don", "wane", "wace", "abin", "wani", "wata", "cikin", "mai", "masu", "kyau", "sosai",
        "daga", "zuwa", "kafin", "bayan", "idan", "amma", "saboda", "yanzu", "nan", "can", "duk", "dukkan", "kowane", "babu", "akwai", "yi",
        "yin", "ayyuka", "aikace", "manhajar", "manhaja", "lambar", "lambobi", "ka", "ki", "ku", "ya", "ta", "sa", "shi", "ita", "su", "ni",
        "kai", "ke", "ce", "ne", "na", "da", "ba", "bata", "bai", "kar", "karka", "don", "taimaka", "taimako", "nuna", "bani", "ban",
        "rubuta", "kirkiri", "kirkira", "gyara", "bayyana", "fassara", "inganta", "canza", "kara", "cire", "nemo", "duba", "sake", "tsara",
        "haɗa", "hada", "aiki", "aikin", "kuskure", "matsala", "wanda", "wadda", "wadanda", "mene", "menene", "yaya", "yaushe", "me", "meyasa",
        "shiga", "fita", "rajista", "maballin", "maballi", "fom", "allon", "fuska", "launi", "hoto", "jerin", "jeri", "saurin", "sauri",
        "sauki", "taimakon", "hanya", "tambaya", "amsa", "gaskiya", "kyauta", "bukata", "so", "tare", "bisa", "kamar", "irin", "sabon", "sabuwar", "tsohon",
    )

    /** Words whose Hausa meaning is common enough that one of them is a clue but not proof (they are also English: "so", "me", "ka"...). */
    private val weak = setOf("so", "me", "na", "da", "ba", "ka", "ki", "ku", "ya", "ta", "sa", "shi", "ita", "su", "ni", "kai", "ke", "ce", "ne", "nan", "can", "duk", "don", "min", "bai", "ban", "mai", "tare", "bisa", "kamar", "irin", "yi")

    private val stems = listOf(
        "kuskur", "matsal", "gyara", "kirkir", "bayyan", "fassar", "inganta", "rubuta", "canza", "saukak", "tsafta", "gaggaut",
        "aikac", "manhaj", "lamba", "shafin", "maballi", "kwamfut", "waya", "bayanai", "bayani", "sabunta",
    )

    private val splitter = Regex("[^\\p{L}']+")

    fun tokens(text: String): List<String> = normalize(text).split(splitter).map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** Hausa when the text holds at least two clear Hausa words (or one in a very short text); otherwise English. */
    fun detect(text: String): ChatLanguage {
        val words = tokens(text)
        if (words.isEmpty()) return ChatLanguage.ENGLISH
        var strong = 0
        var weakHits = 0
        for (w in words) {
            val isWord = w in hausaWords || stems.any { w.length >= it.length && w.startsWith(it) }
            if (!isWord) continue
            if (w in weak) weakHits++ else strong++
        }
        return when {
            strong >= 2 -> ChatLanguage.HAUSA
            strong == 1 && (weakHits >= 1 || words.size <= 3) -> ChatLanguage.HAUSA
            strong == 0 && weakHits >= 3 && weakHits * 2 >= words.size -> ChatLanguage.HAUSA
            else -> ChatLanguage.ENGLISH
        }
    }
}

/** Programming terms that stay in English even in a Hausa answer, so the user learns the words every tutorial and error message uses. */
object TechnicalTerms {
    val keepInEnglish: List<String> = listOf(
        "function", "class", "interface", "object", "variable", "parameter", "return", "import", "package", "constructor", "property",
        "ViewModel", "Repository", "Composable", "Activity", "Fragment", "Service", "layout", "view", "button", "screen", "dialog",
        "state", "StateFlow", "coroutine", "API", "endpoint", "request", "response", "JSON", "dependency", "Gradle", "build", "manifest",
        "permission", "database", "Room", "Retrofit", "null", "exception", "error", "bug", "crash", "log", "test", "refactor", "optimize",
        "preview", "commit", "file", "code",
    )

    /** One line for the model prompt. */
    fun promptLine(): String = keepInEnglish.joinToString(", ")

    /** Terms of [keepInEnglish] that appear (as whole words, ignoring case) in [text]. Used to check a Hausa answer kept them. */
    fun foundIn(text: String): List<String> {
        val lower = text.lowercase()
        return keepInEnglish.filter { Regex("(?<![\\p{L}\\d])" + Regex.escape(it.lowercase()) + "(?![\\p{L}\\d])").containsMatchIn(lower) }
    }
}
