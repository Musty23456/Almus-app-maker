package com.almus.appcreatorpro.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun src(vararg l: String) = l.joinToString("\n")

private fun toks(text: String, lang: Language) =
    SyntaxHighlighter.tokenize(text, lang).map { it.type to text.substring(it.start, it.end) }

private fun names(text: String, lang: Language) = OutlineEngine.outline(text, lang).map { it.name }

class Phase11LanguageTest {
    @Test
    fun new_file_extensions_are_recognised() {
        assertEquals(Language.PYTHON, Language.fromFileName("tool.py"))
        assertEquals(Language.TYPESCRIPT, Language.fromFileName("src/app.ts"))
        assertEquals(Language.TYPESCRIPT, Language.fromFileName("View.TSX"))
        assertEquals(Language.DART, Language.fromFileName("lib/main.dart"))
        assertEquals(Language.YAML, Language.fromFileName("pubspec.yaml"))
        assertEquals(Language.YAML, Language.fromFileName(".github/workflows/ci.yml"))
        assertEquals(Language.SQL, Language.fromFileName("schema.sql"))
        assertEquals(Language.JAVASCRIPT, Language.fromFileName("App.jsx"))
    }

    @Test
    fun existing_extensions_are_unchanged() {
        assertEquals(Language.KOTLIN, Language.fromFileName("A.kt"))
        assertEquals(Language.KOTLIN, Language.fromFileName("build.gradle.kts"))
        assertEquals(Language.JAVA, Language.fromFileName("A.java"))
        assertEquals(Language.JAVASCRIPT, Language.fromFileName("a.js"))
        assertEquals(Language.XML, Language.fromFileName("a.xml"))
        assertEquals(Language.MARKDOWN, Language.fromFileName("README.md"))
        assertEquals(Language.PLAIN, Language.fromFileName("Makefile"))
    }

    @Test
    fun python_comments_keywords_and_strings() {
        val code = "def f():  # note\n    return 'a'"
        assertEquals(
            listOf(TokenType.KEYWORD to "def", TokenType.COMMENT to "# note", TokenType.KEYWORD to "return", TokenType.STRING to "'a'"),
            toks(code, Language.PYTHON),
        )
    }

    @Test
    fun python_triple_single_quotes_span_lines() {
        assertEquals(listOf(TokenType.STRING to "'''a\nb'''"), toks("x = '''a\nb'''", Language.PYTHON))
    }

    @Test
    fun sql_keywords_match_in_any_case_and_dash_dash_is_a_comment() {
        assertEquals(
            listOf(TokenType.KEYWORD to "SELECT", TokenType.KEYWORD to "From", TokenType.COMMENT to "-- c"),
            toks("SELECT name From t -- c", Language.SQL),
        )
    }

    @Test
    fun yaml_comment_and_values() {
        assertEquals(
            listOf(TokenType.KEYWORD to "true", TokenType.COMMENT to "# c", TokenType.STRING to "'v'"),
            toks("key: true # c\nother: 'v'", Language.YAML),
        )
    }

    @Test
    fun typescript_types_and_decorators() {
        assertEquals(
            listOf(TokenType.KEYWORD to "interface", TokenType.KEYWORD to "number"),
            toks("interface A { x: number }", Language.TYPESCRIPT),
        )
        assertEquals(listOf(TokenType.ANNOTATION to "@Component"), toks("@Component", Language.TYPESCRIPT))
    }

    @Test
    fun dart_keywords_and_triple_quotes() {
        assertEquals(
            listOf(TokenType.KEYWORD to "class", TokenType.KEYWORD to "extends", TokenType.KEYWORD to "String", TokenType.STRING to "'''x'''"),
            toks("class A extends B { String s = '''x'''; }", Language.DART),
        )
    }

    @Test
    fun existing_kotlin_highlighting_still_works() {
        assertEquals(
            listOf(TokenType.KEYWORD to "fun", TokenType.COMMENT to "// hi"),
            toks("fun x() // hi", Language.KOTLIN),
        )
    }
}

class Phase11OutlineTest {
    @Test
    fun kotlin_symbols_skip_comments_and_raw_strings() {
        val code = src(
            "package a",
            "class Foo(val x: Int) {",
            "    fun bar(): Int = 1",
            "    // class Fake",
            "    val s = \"\"\"",
            "class InString",
            "\"\"\"",
            "}",
            "object Single",
            "interface Api",
            "fun String.shout() = this",
            "enum class Color { RED }",
        )
        val symbols = OutlineEngine.outline(code, Language.KOTLIN)
        assertEquals(
            listOf("Foo" to SymbolKind.CLASS, "bar" to SymbolKind.FUNCTION, "Single" to SymbolKind.OBJECT, "Api" to SymbolKind.INTERFACE, "shout" to SymbolKind.FUNCTION, "Color" to SymbolKind.CLASS),
            symbols.map { it.name to it.kind },
        )
        assertEquals(1, symbols[0].line)
        assertEquals(6, symbols[0].column)
        assertEquals(4, symbols[1].indent)
        assertEquals(8, symbols[2].line)
        assertEquals(10, symbols[4].line)
    }

    @Test
    fun java_types_methods_and_constructors() {
        val code = src(
            "public class Main {",
            "    private static int count(String s) { return 1; }",
            "    public Main() {}",
            "    void pkgPrivate() {}",
            "    // public void commented() {}",
            "}",
            "interface Shape {}",
        )
        assertEquals(
            listOf("Main" to SymbolKind.CLASS, "count" to SymbolKind.FUNCTION, "Main" to SymbolKind.FUNCTION, "Shape" to SymbolKind.INTERFACE),
            OutlineEngine.outline(code, Language.JAVA).map { it.name to it.kind },
        )
    }

    @Test
    fun python_classes_functions_and_docstrings() {
        val code = src(
            "class A:",
            "    def run(self):",
            "        pass",
            "async def go():",
            "    \"\"\"",
            "    def fake():",
            "    \"\"\"",
        )
        val symbols = OutlineEngine.outline(code, Language.PYTHON)
        assertEquals(listOf("A", "run", "go"), symbols.map { it.name })
        assertEquals(listOf(SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.FUNCTION), symbols.map { it.kind })
        assertEquals(4, symbols[1].indent)
        assertEquals(3, symbols[2].line)
    }

    @Test
    fun javascript_functions_arrows_classes_and_methods_but_not_control_flow() {
        val code = src(
            "function alpha() {}",
            "export const beta = async (x) => x;",
            "class Gamma {",
            "  render() {",
            "    if (a) {",
            "    }",
            "  }",
            "}",
        )
        assertEquals(listOf("alpha", "beta", "Gamma", "render"), names(code, Language.JAVASCRIPT))
    }

    @Test
    fun typescript_interfaces_types_and_enums() {
        val code = src("export interface User {}", "type Id = string;", "enum E { A }")
        assertEquals(
            listOf("User" to SymbolKind.INTERFACE, "Id" to SymbolKind.TYPE, "E" to SymbolKind.ENUM),
            OutlineEngine.outline(code, Language.TYPESCRIPT).map { it.name to it.kind },
        )
    }

    @Test
    fun dart_classes_and_methods_but_not_else_if() {
        val code = src("class Counter {", "  int value = 0;", "  void increment() {", "  else if (x) {", "  }", "}")
        assertEquals(listOf("Counter", "increment"), names(code, Language.DART))
    }

    @Test
    fun markdown_headings_ignore_fenced_code() {
        val code = src("# Title", "text", "## Section A", "```", "# not a heading", "```", "## Section B")
        val s = OutlineEngine.outline(code, Language.MARKDOWN)
        assertEquals(listOf("Title", "Section A", "Section B"), s.map { it.name })
        assertEquals(listOf(1, 2, 2), s.map { it.level })
    }

    @Test
    fun yaml_top_level_and_second_level_keys_only() {
        val code = src("name: app", "version: 1", "deps:", "  - a: 1", "  sub: x", "      deep: y", "# comment: z")
        assertEquals(listOf("name", "version", "deps", "a", "sub"), names(code, Language.YAML))
    }

    @Test
    fun json_lists_only_the_keys_of_the_root_object() {
        val s = OutlineEngine.outline("{\"a\": 1, \"b\": {\"c\": 2}, \"d\": [ {\"e\": 3} ]}", Language.JSON)
        assertEquals(listOf("a", "b", "d"), s.map { it.name })
        assertEquals(0, s[0].line)
        assertEquals(2, s[0].column)
    }

    @Test
    fun sql_create_statements_in_any_case() {
        val code = src("CREATE TABLE IF NOT EXISTS users (", " id INT", ");", "create view v_active as select 1;")
        assertEquals(
            listOf("users" to SymbolKind.TABLE, "v_active" to SymbolKind.TYPE),
            OutlineEngine.outline(code, Language.SQL).map { it.name to it.kind },
        )
    }

    @Test
    fun css_selectors_and_at_rules() {
        val code = src(".a {", " color: red;", "}", "@media (max-width: 600px) {", "  .b { }", "}")
        assertEquals(listOf(".a", "@media (max-width: 600px)"), names(code, Language.CSS))
    }

    @Test
    fun html_headings_and_ids_and_android_ids() {
        val html = src("<h1>Title <b>x</b></h1>", "<div id=\"main\"></div>")
        assertEquals(listOf("Title x", "div#main"), names(html, Language.HTML))
        assertEquals(listOf("Button#btn_ok"), names("<Button android:id=\"@+id/btn_ok\" />", Language.XML))
    }

    @Test
    fun plain_text_empty_text_and_huge_text_give_no_symbols() {
        assertTrue(OutlineEngine.outline("class A", Language.PLAIN).isEmpty())
        assertTrue(OutlineEngine.outline("", Language.KOTLIN).isEmpty())
        assertTrue(OutlineEngine.outline("x".repeat(OutlineEngine.MAX_CHARS + 1), Language.KOTLIN).isEmpty())
    }

    @Test
    fun symbol_count_is_capped() {
        val code = (1..OutlineEngine.MAX_SYMBOLS + 50).joinToString("\n") { "fun f$it() {}" }
        assertEquals(OutlineEngine.MAX_SYMBOLS, OutlineEngine.outline(code, Language.KOTLIN).size)
    }

    @Test
    fun definition_finder_puts_the_current_file_first() {
        val files = mapOf("A.kt" to "class Foo\nfun bar() {}", "B.kt" to "fun bar() {}\nclass Baz")
        assertEquals(listOf("B.kt", "A.kt"), DefinitionFinder.find("bar", "B.kt", files).map { it.path })
        assertEquals(listOf("A.kt", "B.kt"), DefinitionFinder.find("bar", "A.kt", files).map { it.path })
        assertTrue(DefinitionFinder.find("missing", "A.kt", files).isEmpty())
        assertTrue(DefinitionFinder.find("", "A.kt", files).isEmpty())
    }

    @Test
    fun definition_finder_works_across_languages_and_reports_the_position() {
        val files = mapOf("x.js" to "// top\nfunction helper() {}", "y.py" to "def helper():\n    pass")
        val hits = DefinitionFinder.find("helper", null, files)
        assertEquals(listOf("x.js", "y.py"), hits.map { it.path })
        assertEquals(1, hits[0].line)
        assertEquals(9, hits[0].column)
    }

    @Test
    fun symbol_names_for_completion_skip_headings_and_keys() {
        val files = mapOf("A.kt" to "class Foo\nfun bar() {}", "notes.md" to "# Heading", "c.yml" to "key: 1")
        assertEquals(setOf("Foo", "bar"), DefinitionFinder.symbolNames(files))
    }
}
