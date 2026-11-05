package com.almus.appcreatorpro.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almus.appcreatorpro.editor.Language
import com.almus.appcreatorpro.editor.SyntaxHighlighter
import com.almus.appcreatorpro.editor.TokenType

private class CodeColors(val keyword: Color, val string: Color, val comment: Color, val number: Color, val annotation: Color, val tag: Color, val attribute: Color) {
    fun of(t: TokenType): Color = when (t) {
        TokenType.KEYWORD -> keyword
        TokenType.STRING -> string
        TokenType.COMMENT -> comment
        TokenType.NUMBER -> number
        TokenType.ANNOTATION -> annotation
        TokenType.TAG -> tag
        TokenType.ATTRIBUTE -> attribute
    }
}

private val DarkCode = CodeColors(Color(0xFFCFBCFF), Color(0xFFA5D6A7), Color(0xFF8A8FA3), Color(0xFFFFCC80), Color(0xFFF48FB1), Color(0xFF90CAF9), Color(0xFFFFE082))
private val LightCode = CodeColors(Color(0xFF6A1B9A), Color(0xFF2E7D32), Color(0xFF757575), Color(0xFFE65100), Color(0xFFAD1457), Color(0xFF1565C0), Color(0xFF8D6E00))

/** Very long blocks are shown without colours: highlighting runs again for every streamed fragment. */
private const val MAX_HIGHLIGHT_CHARS = 20_000

private fun highlight(code: String, language: Language, colors: CodeColors): AnnotatedString {
    if (code.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(code)
    val b = AnnotatedString.Builder(code)
    for (t in SyntaxHighlighter.tokenize(code, language)) {
        if (t.start < 0 || t.end > code.length || t.start >= t.end) continue
        b.addStyle(SpanStyle(color = colors.of(t.type)), t.start, t.end)
    }
    return b.toAnnotatedString()
}

/** Code with syntax colours and a Copy button. The same highlighter as the editor is used. */
@Composable
fun CodeBlockView(code: String, language: Language, onCopy: (String) -> Unit, modifier: Modifier = Modifier, title: String? = null) {
    val colors = if (isSystemInDarkTheme()) DarkCode else LightCode
    val text = remember(code, language, colors) { highlight(code, language, colors) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title ?: language.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onCopy(code) }) { Text("Copy") }
        }
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}
