package com.almus.appcreatorpro.domain

import com.almus.appcreatorpro.domain.validation.PackageNameSuggester
import com.almus.appcreatorpro.domain.validation.PackageNameValidator
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNameValidatorTest {

    @Test
    fun blank_name_is_rejected() {
        val result = ProjectNameValidator.validate("   ")
        assertFalse(result.isValid)
        assertEquals("Project name is required.", result.errorMessage)
    }

    @Test
    fun too_short_name_is_rejected() {
        assertFalse(ProjectNameValidator.validate("ab").isValid)
    }

    @Test
    fun too_long_name_is_rejected() {
        assertFalse(ProjectNameValidator.validate("a".repeat(ProjectNameValidator.MAX_LENGTH + 1)).isValid)
    }

    @Test
    fun name_at_max_length_is_accepted() {
        assertTrue(ProjectNameValidator.validate("a".repeat(ProjectNameValidator.MAX_LENGTH)).isValid)
    }

    @Test
    fun name_must_start_with_letter_or_digit() {
        assertFalse(ProjectNameValidator.validate("-My App").isValid)
        assertFalse(ProjectNameValidator.validate(" _hidden").isValid)
    }

    @Test
    fun illegal_characters_are_rejected() {
        assertFalse(ProjectNameValidator.validate("My/App").isValid)
        assertFalse(ProjectNameValidator.validate("My App!").isValid)
    }

    @Test
    fun typical_names_are_accepted() {
        assertTrue(ProjectNameValidator.validate("My App").isValid)
        assertTrue(ProjectNameValidator.validate("  Padded Name  ").isValid)
        assertTrue(ProjectNameValidator.validate("App_1.2-beta").isValid)
    }

    @Test
    fun duplicate_name_is_rejected_ignoring_case_and_whitespace() {
        val result = ProjectNameValidator.validate("weather buddy", listOf(" Weather Buddy "))
        assertFalse(result.isValid)
        assertEquals("A project with this name already exists.", result.errorMessage)
    }

    @Test
    fun valid_result_has_no_error_message() {
        assertEquals(null, ProjectNameValidator.validate("Good Name").errorMessage)
    }
}

class PackageNameValidatorTest {

    @Test
    fun typical_package_names_are_accepted() {
        assertTrue(PackageNameValidator.validate("com.example.app").isValid)
        assertTrue(PackageNameValidator.validate("com.my_app.v2").isValid)
        assertTrue(PackageNameValidator.validate(" com.example.app ").isValid)
    }

    @Test
    fun blank_package_is_rejected() {
        assertFalse(PackageNameValidator.validate("").isValid)
    }

    @Test
    fun single_segment_is_rejected() {
        assertFalse(PackageNameValidator.validate("app").isValid)
    }

    @Test
    fun empty_segment_is_rejected() {
        assertFalse(PackageNameValidator.validate("com..app").isValid)
        assertFalse(PackageNameValidator.validate("com.example.").isValid)
    }

    @Test
    fun uppercase_segment_is_rejected() {
        assertFalse(PackageNameValidator.validate("com.Example.app").isValid)
    }

    @Test
    fun segment_starting_with_digit_is_rejected() {
        assertFalse(PackageNameValidator.validate("com.1app").isValid)
    }

    @Test
    fun reserved_word_is_rejected() {
        assertFalse(PackageNameValidator.validate("com.example.class").isValid)
        assertFalse(PackageNameValidator.validate("com.example.new").isValid)
    }

    @Test
    fun overly_long_package_is_rejected() {
        val longName = "com." + "a".repeat(130)
        assertFalse(PackageNameValidator.validate(longName).isValid)
    }
}

class PackageNameSuggesterTest {

    @Test
    fun suggests_lowercase_alphanumeric_slug() {
        assertEquals("com.example.weatherbuddy", PackageNameSuggester.suggest("Weather Buddy"))
    }

    @Test
    fun prefixes_slug_that_starts_with_digit() {
        assertEquals("com.example.app123go", PackageNameSuggester.suggest("123 Go"))
    }

    @Test
    fun returns_empty_when_nothing_usable() {
        assertEquals("", PackageNameSuggester.suggest("!!!"))
        assertEquals("", PackageNameSuggester.suggest(""))
    }

    @Test
    fun suggestion_for_normal_name_passes_validation() {
        assertTrue(PackageNameValidator.validate(PackageNameSuggester.suggest("Notes Lite")).isValid)
    }
}
