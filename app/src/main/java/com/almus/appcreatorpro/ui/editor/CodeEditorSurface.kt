package com.almus.appcreatorpro.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almus.appcreatorpro.editor.BracketMatcher
import com.almus.appcreatorpro.editor.EditorValue
import com.almus.appcreatorpro.editor.Language
import com.almus.appcreatorpro.editor.SyntaxHighlighter
import com.almus.appcreatorpro.editor.Token
import com.almus.appcreatorpro.editor.TokenType
import kotlin.math.roundToInt

private class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    val tag: Color,
    val attribute: Color,
) {
    fun forType(type: TokenType): Color = when (type) {
        TokenType.KEYWORD -> keyword
        TokenType.STRING -> string
        TokenType.COMMENT -> comment
        TokenType.NUMBER -> number
        TokenType.ANNOTATION -> annotation
        TokenType.TAG -> tag
        TokenType.ATTRIBUTE -> attribute
    }
}

private val DarkSyntax = SyntaxColors(
    keyword = Color(0xFFCFBCFF), string = Color(0xFFA5D6A7), comment = Color(0xFF8A8FA3),
    number = Color(0xFFFFCC80), annotation = Color(0xFFF48FB1), tag = Color(0xFF90CAF9), attribute = Color(0xFFFFE082),
)
private val LightSyntax = SyntaxColors(
    keyword = Color(0xFF6A1B9A), string = Color(0xFF2E7D32), comment = Color(0xFF757575),
    number = Color(0xFFE65100), annotation = Color(0xFFAD1457), tag = Color(0xFF1565C0), attribute = Color(0xFF8D6E00),
)

/** Colors the text with [tokens] and highlights a matching bracket pair. Text offsets are unchanged. */
private class CodeVisualTransformation(
    private val tokens: List<Token>,
    private val colors: SyntaxColors,
    private val bracketPair: Pair<Int, Int>?,
    private val bracketBackground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text.text)
        val length = text.length
        for (token in tokens) {
            if (token.end > length) break
            val style = if (token.type == TokenType.COMMENT) {
                SpanStyle(color = colors.forType(token.type), fontStyle = FontStyle.Italic)
            } else {
                SpanStyle(color = colors.forType(token.type))
            }
            builder.addStyle(style, token.start, token.end)
        }
        bracketPair?.let { (a, b) ->
            for (i in intArrayOf(a, b)) {
                if (i in 0 until length) builder.addStyle(SpanStyle(background = bracketBackground), i, i + 1)
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

private fun EditorValue.toTextFieldValue() = TextFieldValue(
    text = text,
    selection = TextRange(selStart.coerceIn(0, text.length), selEnd.coerceIn(0, text.length)),
)

private fun TextFieldValue.toEditorValue() = EditorValue(text, selection.start, selection.end)

/**
 * The editing area: a line-number gutter next to a horizontally scrolling text field, both inside
 * one vertical scroll so they stay aligned. Wrapping is off (long lines scroll sideways).
 *
 * The text field keeps its own [TextFieldValue] (so the keyboard's composing state is not disturbed
 * while typing). The ViewModel is told about every edit via [onUserEdit] and only pushes a value back
 * when it changes the text itself (undo, indent, find, ...), signalled by [revision].
 */
@Composable
fun CodeEditorSurface(
    path: String,
    value: EditorValue,
    revision: Int,
    language: Language,
    onUserEdit: (EditorValue) -> EditorValue,
    onIndentKey: (shift: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val colors = if (scheme.surface.luminance() < 0.5f) DarkSyntax else LightSyntax
    val textStyle = remember(scheme.onSurface) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = scheme.onSurface,
        )
    }

    var field by remember(path) { mutableStateOf(value.toTextFieldValue()) }
    LaunchedEffect(path, revision) { field = value.toTextFieldValue() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val tokens = remember(field.text, language) { SyntaxHighlighter.tokenize(field.text, language) }
    val bracketPair = remember(field.text, field.selection) {
        if (field.selection.collapsed) BracketMatcher.findMatch(field.text, field.selection.end) else null
    }
    val transformation = remember(tokens, bracketPair, colors) {
        CodeVisualTransformation(tokens, colors, bracketPair, scheme.primary.copy(alpha = 0.28f))
    }

    val lineCount = remember(field.text) { field.text.count { it == '\n' } + 1 }
    val gutterText = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val digits = lineCount.toString().length.coerceAtLeast(2)
    val gutterWidth = with(density) { (digits * 9).sp.toDp() + 20.dp }

    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeightPx = constraints.maxHeight
        val fieldViewportWidthPx = with(density) { (maxWidth - gutterWidth).coerceAtLeast(0.dp).toPx() }
        val startPaddingPx = with(density) { 8.dp.toPx() }

        // Keep the caret visible while typing, after find/replace and when switching files.
        LaunchedEffect(field.selection.end, layout) {
            val result = layout ?: return@LaunchedEffect
            val offset = field.selection.end.coerceIn(0, result.layoutInput.text.length)
            val rect = result.getCursorRect(offset)
            if (rect.top < verticalScroll.value) {
                verticalScroll.scrollTo(rect.top.roundToInt().coerceAtLeast(0))
            } else if (rect.bottom > verticalScroll.value + viewportHeightPx) {
                verticalScroll.scrollTo((rect.bottom - viewportHeightPx).roundToInt())
            }
            val x = rect.left + startPaddingPx
            if (x < horizontalScroll.value + 24f) {
                horizontalScroll.scrollTo((x - 24f).roundToInt().coerceAtLeast(0))
            } else if (x > horizontalScroll.value + fieldViewportWidthPx - 24f) {
                horizontalScroll.scrollTo((x - fieldViewportWidthPx + 96f).roundToInt())
            }
        }

        Row(modifier = Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            Text(
                text = gutterText,
                style = textStyle.copy(color = scheme.outline, textAlign = TextAlign.End),
                modifier = Modifier
                    .width(gutterWidth)
                    .heightIn(min = maxHeight)
                    .background(scheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(end = 8.dp),
            )
            Box(modifier = Modifier.weight(1f).horizontalScroll(horizontalScroll)) {
                BasicTextField(
                    value = field,
                    onValueChange = { typed ->
                        val typedValue = typed.toEditorValue()
                        val shown = onUserEdit(typedValue)
                        field = if (shown == typedValue) typed else shown.toTextFieldValue()
                    },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    visualTransformation = transformation,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .padding(start = 8.dp, end = 64.dp)
                        .defaultMinSize(
                            minWidth = (maxWidth - gutterWidth - 72.dp).coerceAtLeast(0.dp),
                            minHeight = maxHeight,
                        )
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                                onIndentKey(event.isShiftPressed)
                                true
                            } else {
                                false
                            }
                        },
                )
            }
        }
    }
}
