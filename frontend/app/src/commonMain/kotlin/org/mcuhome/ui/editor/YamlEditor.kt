// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.mcuhome.ui.theme.MCUHomeColors
import org.mcuhome.ui.theme.MCUHomeTheme

/**
 * A YAML editor: a line-number gutter, syntax highlighting, and the
 * diagnostics of the last validation drawn onto the lines they belong to.
 *
 * The text itself is a `BasicTextField` over a [TextFieldState] — editing,
 * selection, undo and the platform's text input come from Compose, not
 * from an embedded browser widget. Highlighting is added by an
 * `OutputTransformation` that styles the presented text without touching
 * the state behind it; the gutter, the current-line band and the wavy
 * underlines are drawn from the text field's own layout, so they stay
 * aligned with the text through scrolling and wrapping.
 */
@Composable
fun YamlEditor(
    state: TextFieldState,
    diagnostics: List<EditorDiagnostic>,
    modifier: Modifier = Modifier,
) {
    val colors = MCUHomeTheme.colors
    val textMeasurer = rememberTextMeasurer()
    val scrollState = rememberScrollState()

    // The text sits inset from the edge of its column. Everything drawn
    // next to it — the gutter numbers, the current-line band, the
    // underlines — is placed in the text field's own coordinates and has
    // to be shifted by the same inset to line up with the glyphs.
    val contentOffset = with(LocalDensity.current) {
        Offset(EditorContentStart.toPx(), EditorContentTop.toPx())
    }

    val textStyle = TextStyle(
        fontFamily = MCUHomeTheme.typography.mono,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = colors.ink,
    )
    val gutterStyle = textStyle.copy(color = colors.editorGutter)

    val documentText = state.text.toString()
    val lineStarts = remember(documentText) { lineStartOffsets(documentText) }
    val cursorLine = remember(documentText, state.selection) {
        lineIndexOf(lineStarts, state.selection.start)
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var pinnedDiagnostic by remember { mutableStateOf<EditorDiagnostic?>(null) }

    // Keyed on the color scheme only: a new OutputTransformation instance
    // restarts the text-input session, which drops keystrokes on wasmJs.
    val highlighting = remember(colors.darkScheme) {
        YamlOutputTransformation(colors.spanStyles())
    }

    Row(modifier.background(colors.surface)) {
        Canvas(
            Modifier
                .width(GutterWidth)
                .fillMaxHeight()
                .background(colors.backgroundAlt)
                .pointerInput(diagnostics, layout) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                val position = event.changes.first().position
                                val line = layout?.let { result ->
                                    lineAtY(
                                        result,
                                        position.y - contentOffset.y + scrollState.value,
                                        lineStarts,
                                    )
                                }
                                pinnedDiagnostic = diagnostics
                                    .firstOrNull { it.line - 1 == line }
                                    .takeIf { it != pinnedDiagnostic }
                            }
                        }
                    }
                }
        ) {
            val result = layout ?: return@Canvas
            translate(top = contentOffset.y - scrollState.value) {
                lineStarts.forEachIndexed { index, offset ->
                    val visualLine = result.getLineForOffset(offset)
                    val number = (index + 1).toString()
                    val measured = textMeasurer.measure(number, gutterStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            x = size.width - 8.dp.toPx() - measured.size.width,
                            y = result.getLineTop(visualLine),
                        ),
                    )
                    diagnostics.firstOrNull { it.line - 1 == index }?.let { diagnostic ->
                        drawCircle(
                            color = colors.severityColor(diagnostic),
                            radius = 3.dp.toPx(),
                            center = Offset(
                                x = 7.dp.toPx(),
                                y = (result.getLineTop(visualLine) + result.getLineBottom(visualLine)) / 2f,
                            ),
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            pointer = when (event.type) {
                                PointerEventType.Exit -> null
                                else -> event.changes.first().position
                            }
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val result = layout ?: return@Canvas
                translate(left = contentOffset.x, top = contentOffset.y - scrollState.value) {
                    cursorLine?.let { index ->
                        lineStarts.getOrNull(index)?.let { offset ->
                            val visualLine = result.getLineForOffset(offset)
                            drawRect(
                                color = colors.editorCurrentLine,
                                topLeft = Offset(-contentOffset.x, result.getLineTop(visualLine)),
                                size = Size(
                                    width = size.width,
                                    height = result.getLineBottom(visualLine) - result.getLineTop(visualLine),
                                ),
                            )
                        }
                    }
                    diagnostics.forEach { diagnostic ->
                        underlineRect(result, lineStarts, documentText, diagnostic)?.let { rect ->
                            drawWavyUnderline(rect, colors.severityColor(diagnostic))
                        }
                    }
                }
            }

            BasicTextField(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = EditorContentStart, top = EditorContentTop, end = EditorContentStart),
                textStyle = textStyle,
                lineLimits = TextFieldLineLimits.MultiLine(),
                cursorBrush = SolidColor(colors.accent),
                outputTransformation = highlighting,
                scrollState = scrollState,
                onTextLayout = { getResult -> layout = getResult() },
            )

            val hovered = layout?.let { result ->
                pointer?.let { position ->
                    diagnostics.firstOrNull { diagnostic ->
                        underlineRect(result, lineStarts, documentText, diagnostic)
                            ?.translate(contentOffset.x, contentOffset.y - scrollState.value)
                            ?.inflate(6f)
                            ?.contains(position) == true
                    }
                }
            }
            val shown = hovered ?: pinnedDiagnostic
            if (shown != null) {
                DiagnosticTooltip(
                    diagnostic = shown,
                    anchor = layout?.let { result ->
                        underlineRect(result, lineStarts, documentText, shown)
                            ?.translate(contentOffset.x, contentOffset.y - scrollState.value)
                    },
                )
            }
        }
    }
}

/** The width the gutter takes; enough for four digits plus the marker. */
private val GutterWidth = 52.dp

/** How far the text is inset from the left edge and the top of its column. */
private val EditorContentStart = 8.dp
private val EditorContentTop = 4.dp

@Composable
private fun DiagnosticTooltip(diagnostic: EditorDiagnostic, anchor: Rect?) {
    val colors = MCUHomeTheme.colors
    Box(
        Modifier
            .offset {
                IntOffset(
                    x = (anchor?.left ?: 0f).roundToInt(),
                    y = ((anchor?.bottom ?: 0f) + 4f).roundToInt(),
                )
            }
            .background(colors.surface, RoundedCornerShape(6.dp))
            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = diagnostic.message,
            color = colors.ink,
            fontFamily = MCUHomeTheme.typography.body,
            fontSize = 13.sp,
        )
    }
}

/** Applies the syntax colors to the text the field shows, leaving the document untouched. */
private data class YamlOutputTransformation(
    private val styles: Map<YamlToken, SpanStyle>,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        highlightYaml(asCharSequence()).forEach { span ->
            styles[span.token]?.let { addStyle(it, span.start, span.end) }
        }
    }
}

private fun MCUHomeColors.spanStyles(): Map<YamlToken, SpanStyle> = mapOf(
    YamlToken.Key to SpanStyle(color = editorKey),
    YamlToken.Value to SpanStyle(color = editorValue),
    YamlToken.Tag to SpanStyle(color = editorTag),
    YamlToken.Literal to SpanStyle(color = editorLiteral),
    YamlToken.Comment to SpanStyle(color = editorComment),
)

private fun MCUHomeColors.severityColor(diagnostic: EditorDiagnostic): Color =
    when (diagnostic.severity) {
        DiagnosticSeverity.Error -> error
        DiagnosticSeverity.Warning -> warning
        DiagnosticSeverity.Info -> info
    }

private fun lineStartOffsets(text: String): List<Int> {
    val starts = mutableListOf(0)
    text.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
    return starts
}

private fun lineIndexOf(lineStarts: List<Int>, offset: Int): Int? {
    if (lineStarts.isEmpty()) return null
    val index = lineStarts.indexOfLast { it <= offset }
    return index.takeIf { it >= 0 }
}

private fun lineAtY(result: TextLayoutResult, y: Float, lineStarts: List<Int>): Int? {
    val visualLine = result.getLineForVerticalPosition(y)
    val offset = result.getLineStart(visualLine)
    return lineIndexOf(lineStarts, offset)
}

/** The stretch of a line a diagnostic underlines: its content, without the indent. */
private fun underlineRect(
    result: TextLayoutResult,
    lineStarts: List<Int>,
    text: String,
    diagnostic: EditorDiagnostic,
): Rect? {
    val lineStart = lineStarts.getOrNull(diagnostic.line - 1) ?: return null
    val lineEnd = lineStarts.getOrNull(diagnostic.line)?.minus(1) ?: text.length
    if (lineEnd <= lineStart) return null
    var start = lineStart
    while (start < lineEnd && text[start] == ' ') start++
    if (start >= lineEnd) return null

    val visualLine = result.getLineForOffset(start)
    return Rect(
        left = result.getHorizontalPosition(start, usePrimaryDirection = true),
        top = result.getLineTop(visualLine),
        right = result.getHorizontalPosition(lineEnd, usePrimaryDirection = true),
        bottom = result.getLineBottom(visualLine),
    )
}

private fun DrawScope.drawWavyUnderline(rect: Rect, color: Color) {
    val amplitude = 1.5.dp.toPx()
    val period = 5.dp.toPx()
    val baseline = rect.bottom - amplitude
    val path = Path().apply {
        moveTo(rect.left, baseline)
        var x = rect.left
        var up = true
        while (x < rect.right) {
            val next = minOf(x + period / 2f, rect.right)
            quadraticTo(
                (x + next) / 2f,
                if (up) baseline - amplitude * 2f else baseline + amplitude * 2f,
                next,
                baseline,
            )
            up = !up
            x = next
        }
    }
    drawPath(path, color, style = Stroke(width = 1.dp.toPx()))
}
