package com.almus.appcreatorpro.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 24: language detection and intent detection, English and Hausa. */
class Phase24IntentTest {
    private fun intent(text: String, attached: Boolean = false, previous: CodingIntent? = null) = IntentDetector.detect(text, attached, previous).intent

    @Test fun `english requests are understood`() {
        val cases = listOf(
            "Create a modern login screen." to CodingIntent.UI_COMPONENT,
            "Create a reusable button composable" to CodingIntent.UI_COMPONENT,
            "Add a new button to the toolbar" to CodingIntent.UI_COMPONENT,
            "I need a settings page with a dark mode switch" to CodingIntent.UI_COMPONENT,
            "Explain this function" to CodingIntent.EXPLAIN,
            "What does this code do?" to CodingIntent.EXPLAIN,
            "Walk me through the ViewModel" to CodingIntent.EXPLAIN,
            "Write a function that sorts users by name" to CodingIntent.FUNCTION,
            "Generate a helper to format dates" to CodingIntent.FUNCTION,
            "Create a function to optimize images" to CodingIntent.FUNCTION,
            "Create a User class with name and email" to CodingIntent.CLASS,
            "Create a ViewModel for the login screen" to CodingIntent.CLASS,
            "Write a repository for notes" to CodingIntent.CLASS,
            "Integrate Retrofit to fetch posts from the API" to CodingIntent.API_INTEGRATION,
            "Create a Retrofit service to load users" to CodingIntent.API_INTEGRATION,
            "How do I call a REST endpoint?" to CodingIntent.API_INTEGRATION,
            "Optimize this loop, it is slow" to CodingIntent.OPTIMIZE,
            "Make the list scroll faster" to CodingIntent.OPTIMIZE,
            "Why does my app crash when I open the screen?" to CodingIntent.FIND_BUGS,
            "Find bugs in this file" to CodingIntent.FIND_BUGS,
            "Fix the error in MainActivity" to CodingIntent.FIND_BUGS,
            "It is not working, what is wrong?" to CodingIntent.FIND_BUGS,
            "Refactor this activity to use MVVM" to CodingIntent.REFACTOR,
            "Simplify this code" to CodingIntent.REFACTOR,
            "Convert this Java code to Kotlin" to CodingIntent.REFACTOR,
            "Change the title color to red" to CodingIntent.EDIT,
            "Make it blue" to CodingIntent.EDIT,
            "Add error handling to this function" to CodingIntent.EDIT,
            "Add a null check here" to CodingIntent.EDIT,
            "Make the button red" to CodingIntent.EDIT,
            "Rename this variable to userName" to CodingIntent.EDIT,
            "Generate unit tests for the repository" to CodingIntent.GENERATE,
            "Write the code for me" to CodingIntent.GENERATE,
            "What is a ViewModel?" to CodingIntent.QUESTION,
            "Which library is best for images" to CodingIntent.QUESTION,
        )
        val wrong = cases.mapNotNull { (text, want) -> intent(text).let { got -> if (got == want) null else "\"$text\": wanted $want, got $got" } }
        assertEquals("Misread requests:\n" + wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    @Test fun `hausa requests are understood`() {
        val cases = listOf(
            "Ƙirƙiri sabon shafin shiga (login screen) mai kyau" to CodingIntent.UI_COMPONENT,
            "Ina so ka kirkiri shafin rajista" to CodingIntent.UI_COMPONENT,
            "Bayyana min wannan code" to CodingIntent.EXPLAIN,
            "Menene wannan code yake yi?" to CodingIntent.EXPLAIN,
            "Nemo kuskure a wannan code" to CodingIntent.FIND_BUGS,
            "Gyara kuskuren a wannan function" to CodingIntent.FIND_BUGS,
            "App ɗin ba ya aiki, me ya faru?" to CodingIntent.FIND_BUGS,
            "Inganta wannan code" to CodingIntent.OPTIMIZE,
            "Sake tsara wannan code" to CodingIntent.REFACTOR,
            "Tsaftace wannan class" to CodingIntent.REFACTOR,
            "Rubuta function da zai raba sunaye" to CodingIntent.FUNCTION,
            "Rubuta class na User" to CodingIntent.CLASS,
            "Canza launin maballin zuwa ja" to CodingIntent.EDIT,
            "Cire wannan maballin" to CodingIntent.EDIT,
            "Hada API da Retrofit" to CodingIntent.API_INTEGRATION,
            "Yi min login screen" to CodingIntent.UI_COMPONENT,
        )
        val wrong = cases.mapNotNull { (text, want) -> intent(text).let { got -> if (got == want) null else "\"$text\": wanted $want, got $got" } }
        assertEquals("Misread requests:\n" + wrong.joinToString("\n"), emptyList<String>(), wrong)
    }

    @Test fun `hooked letters do not matter`() {
        assertEquals(intent("Ƙirƙiri sabon shafin shiga"), intent("Kirkiri sabon shafin shiga"))
        assertEquals("kirkiri shafin dd", LanguageDetector.normalize("Ƙirƙiri shafin ɗɗ"))
        assertEquals("b d k y", LanguageDetector.normalize("ɓ ɗ ƙ ƴ"))
        assertEquals("ba'a", LanguageDetector.normalize("Ba\u2019a"))
    }

    @Test fun `language detection`() {
        assertEquals(ChatLanguage.ENGLISH, LanguageDetector.detect("Create a modern login screen"))
        assertEquals(ChatLanguage.ENGLISH, LanguageDetector.detect("Fix the bug so it works"))
        assertEquals(ChatLanguage.ENGLISH, LanguageDetector.detect("show me the code"))
        assertEquals(ChatLanguage.ENGLISH, LanguageDetector.detect("can you make the toolbar blue"))
        assertEquals(ChatLanguage.ENGLISH, LanguageDetector.detect(""))
        assertEquals(ChatLanguage.HAUSA, LanguageDetector.detect("Ƙirƙiri login screen mai kyau"))
        assertEquals(ChatLanguage.HAUSA, LanguageDetector.detect("Bayyana min wannan code"))
        assertEquals(ChatLanguage.HAUSA, LanguageDetector.detect("Gyara kuskuren"))
        assertEquals(ChatLanguage.HAUSA, LanguageDetector.detect("Ina so in ƙara maballi a shafin farko"))
        assertEquals(ChatLanguage.HAUSA, LanguageDetector.detect("Ban gane ba, ka sake bayyana"))
    }

    @Test fun `the detected language is part of the guess`() {
        assertEquals(ChatLanguage.HAUSA, IntentDetector.detect("Bayyana min wannan code").language)
        assertEquals(ChatLanguage.ENGLISH, IntentDetector.detect("Explain this code").language)
    }

    @Test fun `a creation verb in front of this or my means a change not a creation`() {
        assertEquals(CodingIntent.EDIT, intent("Add logging to my function"))
        assertEquals(CodingIntent.UI_COMPONENT, intent("Create the login screen"))
    }

    @Test fun `an action word wins over a thing named later but not over a creation that comes first`() {
        assertEquals(CodingIntent.OPTIMIZE, intent("Optimize the login screen"))
        assertEquals(CodingIntent.FIND_BUGS, intent("Fix the login screen crash"))
        assertEquals(CodingIntent.EXPLAIN, intent("Explain how the Retrofit call works"))
        assertEquals(CodingIntent.UI_COMPONENT, intent("Create a login screen that handles crashes"))
    }

    @Test fun `a short message that matches nothing continues the previous request`() {
        assertEquals(CodingIntent.UI_COMPONENT, intent("and in dark mode too", previous = CodingIntent.UI_COMPONENT))
        assertEquals(CodingIntent.QUESTION, intent("and in dark mode too", previous = null))
        assertEquals(CodingIntent.QUESTION, intent("and in dark mode too?", previous = CodingIntent.UI_COMPONENT))
        assertEquals(CodingIntent.QUESTION, intent("thanks", previous = CodingIntent.QUESTION))
    }

    @Test fun `empty text is a low confidence question`() {
        val g = IntentDetector.detect("   ")
        assertEquals(CodingIntent.QUESTION, g.intent); assertEquals(IntentConfidence.LOW, g.confidence)
    }

    @Test fun `routing picks the existing backend task`() {
        assertEquals(AiTaskKind.EDIT_CODE, IntentRouting.taskFor(CodingIntent.OPTIMIZE, 1))
        assertEquals(AiTaskKind.GENERATE_CODE, IntentRouting.taskFor(CodingIntent.REFACTOR, 3))
        assertEquals(AiTaskKind.CHAT, IntentRouting.taskFor(CodingIntent.EDIT, 0))
        assertEquals(AiTaskKind.CHAT, IntentRouting.taskFor(CodingIntent.FIND_BUGS, 2))
        assertEquals(AiTaskKind.EXPLAIN, IntentRouting.taskFor(CodingIntent.EXPLAIN, 0))
        assertEquals(AiTaskKind.GENERATE_CODE, IntentRouting.taskFor(CodingIntent.UI_COMPONENT, 0))
        assertTrue(CodingIntent.entries.all { !it.task.expectsJson }) // chat answers are prose, never JSON
    }

    @Test fun `labels exist in both languages`() {
        for (i in CodingIntent.entries) {
            assertTrue(i.labelFor(ChatLanguage.ENGLISH).isNotBlank()); assertTrue(i.labelFor(ChatLanguage.HAUSA).isNotBlank())
        }
        assertFalse(CodingIntent.QUESTION.writesCode); assertTrue(CodingIntent.OPTIMIZE.needsExistingCode)
    }

    @Test fun `technical terms are found as whole words`() {
        assertEquals(listOf("function", "ViewModel"), TechnicalTerms.foundIn("Wannan function yana amfani da ViewModel"))
        assertTrue(TechnicalTerms.foundIn("a functional approach").none { it == "function" })
        assertTrue(TechnicalTerms.promptLine().contains("ViewModel"))
    }
}
