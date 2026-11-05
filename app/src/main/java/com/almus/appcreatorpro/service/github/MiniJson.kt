package com.almus.appcreatorpro.service.github

/**
 * Minimal JSON reader/writer in plain Kotlin. Used instead of org.json because Android's org.json is only a
 * stub in JVM unit tests, which would make every GitHub parsing test meaningless.
 *
 * Parsed values: Map<String, Any?> (objects), List<Any?> (arrays), String, Long (whole numbers), Double,
 * Boolean and null.
 */
object MiniJson {
    class JsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWs()
        val value = parser.readValue(depth = 0)
        parser.skipWs()
        if (!parser.atEnd()) throw JsonException("Unexpected data after JSON value at ${parser.pos}")
        return value
    }

    /** Parses and requires a top-level object. */
    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("Expected a JSON object")

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value.toString())
            is Int, is Long -> sb.append(value.toString())
            is Double, is Float -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, v)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("JSON nested too deeply")
            if (atEnd()) throw JsonException("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Invalid literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("Expected a string key at $pos")
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                map[key] = readValue(depth + 1)
                skipWs()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw JsonException("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                skipWs()
                list.add(readValue(depth + 1))
                skipWs()
                when (next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw JsonException("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw JsonException("Bad unicode escape"))
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw JsonException("Bad number '$text'")
        }

        private fun peek(): Char = if (atEnd()) throw JsonException("Unexpected end of JSON") else s[pos]
        private fun next(): Char = peek().also { pos++ }
        private fun expect(c: Char) {
            if (next() != c) throw JsonException("Expected '$c' at ${pos - 1}")
        }
    }

    private const val MAX_DEPTH = 64
}

// ---- Typed accessors used by the GitHub client ----

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asArr(): List<Any?>? = this as? List<Any?>

fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key].asObj()
fun Map<String, Any?>.arr(key: String): List<Any?>? = this[key].asArr()
