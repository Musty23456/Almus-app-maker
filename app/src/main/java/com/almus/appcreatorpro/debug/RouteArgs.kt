package com.almus.appcreatorpro.debug

/**
 * Percent-encoding for navigation route arguments (file paths contain '/', spaces and sometimes '?' or '&').
 * Navigation decodes the value again before the screen sees it. Spaces become %20 (never '+').
 */
object RouteArgs {
    private const val HEX = "0123456789ABCDEF"

    fun encode(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF)
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }
        return sb.toString()
    }

    fun decode(value: String): String {
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length && HEX.indexOf(value[i + 1].uppercaseChar()) >= 0 && HEX.indexOf(value[i + 2].uppercaseChar()) >= 0) {
                out.write(HEX.indexOf(value[i + 1].uppercaseChar()) * 16 + HEX.indexOf(value[i + 2].uppercaseChar()))
                i += 3
            } else {
                val cp = value.codePointAt(i)
                val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i += Character.charCount(cp)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
