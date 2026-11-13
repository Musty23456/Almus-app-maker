package com.almus.appcreatorpro.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchValidatorTest {
    private val kt = "app/src/main/java/com/example/A.kt"

    @Test fun `a clean small change passes`() {
        val v = PatchValidator.validate(kt, "package a\nfun f() {\n    x()\n}\n", "package a\nfun f() {\n    y()\n}\n")
        assertEquals(ValidationStatus.PASSED, v.status)
        assertTrue(v.canApply); assertTrue(v.problems.isEmpty())
    }

    @Test fun `a bracket problem introduced by the patch blocks it`() {
        val v = PatchValidator.validate(kt, "package a\nfun f() {\n    x()\n}\n", "package a\nfun f() {\n    y(\n}\n")
        assertEquals(ValidationStatus.BLOCKED, v.status)
        assertFalse(v.canApply)
        assertTrue(v.errors.isNotEmpty())
    }

    @Test fun `a problem that was already there and is still there is only a warning`() {
        val before = "package a\nfun f() {\n    x()\n"
        val after = "package a\nfun f() {\n    y()\n"
        val v = PatchValidator.validate(kt, before, after)
        assertEquals(ValidationStatus.WARNINGS, v.status)
        assertTrue(v.canApply)
        assertTrue(v.warnings.any { it.message.contains("Already there") })
    }

    @Test fun `a patch that fixes the broken brackets says so`() {
        val v = PatchValidator.validate(kt, "package a\nfun f() {\n    x()\n", "package a\nfun f() {\n    x()\n}\n")
        assertEquals(ValidationStatus.PASSED, v.status)
        assertTrue(v.notes.any { it.contains("removes") })
    }

    @Test fun `brackets inside strings and comments are ignored`() {
        val v = PatchValidator.validate(kt, "package a\nval s = \"(\"\n", "package a\nval s = \"((\" // {\n")
        assertEquals(ValidationStatus.PASSED, v.status)
    }

    @Test fun `malformed xml introduced by the patch blocks it`() {
        val v = PatchValidator.validate("app/src/main/res/values/strings.xml", "<resources>\n<string name=\"a\">A</string>\n</resources>\n", "<resources>\n<string name=\"a\">A</string>\n")
        assertEquals(ValidationStatus.BLOCKED, v.status)
    }

    @Test fun `invalid json blocks and valid json passes`() {
        assertEquals(ValidationStatus.BLOCKED, PatchValidator.validate("a.json", "{\"a\":1}", "{\"a\":").status)
        assertEquals(ValidationStatus.PASSED, PatchValidator.validate("a.json", "{\"a\":1}", "{\"a\":2}").status)
    }

    @Test fun `changing the package line is blocked`() {
        val v = PatchValidator.validate(kt, "package a\nfun f() {}\n", "package b\nfun f() {}\n")
        assertEquals(ValidationStatus.BLOCKED, v.status)
        assertTrue(v.errors.any { it.message.contains("package") })
    }

    @Test fun `an added placeholder is blocked`() {
        val v = PatchValidator.validate(kt, "package a\nfun f() {\n    x()\n}\n", "package a\nfun f() {\n    ...\n}\n")
        assertEquals(ValidationStatus.BLOCKED, v.status)
    }

    @Test fun `whitespace only changes are flagged`() {
        val v = PatchValidator.validate(kt, "package a\nfun f() {\n    x()\n}\n", "package a\nfun f() {\n        x()\n}\n")
        assertEquals(ValidationStatus.WARNINGS, v.status)
        assertTrue(v.warnings.any { it.message.contains("whitespace") })
    }

    @Test fun `a rewrite of the whole file is not a minimal repair`() {
        val before = (1..200).joinToString("\n") { "val a$it = $it" } + "\n"
        val after = (1..200).joinToString("\n") { "val b$it = $it" } + "\n"
        val v = PatchValidator.validate(kt, before, after)
        assertEquals(ValidationStatus.BLOCKED, v.status)
        assertTrue(v.errors.any { it.message.contains("rewrite") })
    }

    @Test fun `a larger but moderate change is a warning`() {
        val before = (1..100).joinToString("\n") { "val a$it = $it" } + "\n"
        val lines = (1..100).map { if (it <= 25) "val z$it = $it" else "val a$it = $it" }
        val v = PatchValidator.validate(kt, before, lines.joinToString("\n") + "\n")
        assertEquals(ValidationStatus.WARNINGS, v.status)
        assertTrue(v.canApply)
    }

    @Test fun `an empty result is blocked`() {
        assertEquals(ValidationStatus.BLOCKED, PatchValidator.validate(kt, "package a\n", "  \n").status)
    }

    @Test fun `file types without a checker are reported as not checked`() {
        val v = PatchValidator.validate("gradle.properties", "a=1\n", "a=2\n")
        assertEquals(ValidationStatus.NOT_CHECKED, v.status)
        assertTrue(v.canApply)
    }
}
