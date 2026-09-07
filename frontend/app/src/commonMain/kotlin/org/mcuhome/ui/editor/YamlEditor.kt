// SPDX-FileCopyrightText: 2026 The MCUHome Contributors
// SPDX-License-Identifier: Apache-2.0

package org.mcuhome.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import org.mcuhome.ui.component.ThinHorizontalScrollbar
import org.mcuhome.ui.component.ThinVerticalScrollbar
import org.mcuhome.ui.theme.MCUHomeColors
import org.mcuhome.ui.theme.MCUHomeTheme
import kotlin.math.roundToInt

/** The width the gutter takes; enough for four digits plus the marker. */
private val GutterWidth = 52.dp

/** How far the text is inset from the left edge and the top of its column. */
private val EditorContentStart = 8.dp
private val EditorContentTop = 4.dp

/** What the Tab key inserts. YAML has no tabs; two spaces are one level. */
private const val INDENT = "  "

/**
 * How far outside its underline a diagnostic still reacts to the
 * pointer, in pixels — a line of squiggle is too thin to hit exactly.
 */
private const val HOVER_TOLERANCE = 6f

/** How far above the top edge a line jumped to is placed. */
private val JUMP_MARGIN = 60.dp

/** Roughly how tall the message box is; enough to decide which side of the line it goes on. */
private val TooltipHeight = 34.dp

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
 * aligned with the text through scrolling.
 *
 * Long lines are not wrapped. A configuration file's indentation carries
 * meaning, and a wrapped line puts a continuation where an indent level
 * would be; the text is laid out at its natural width instead and the
 * column scrolls sideways.
 *
 * [jumpToLine] moves the caret and the viewport to a line of the
 * document — what the diagnostics lists do when one of their entries is
 * clicked. [onJumpHandled] is called once the move has happened, so the
 * caller can forget the request.
 *
 * [onFocusChanged] reports whether the text has the focus. On a phone
 * that is the same question as "is the keyboard up", which is what turns
 * the page into its editing mode; nothing else in the interface can
 * answer it, because the browser does not say.
 */
@Composable
fun YamlEditor(
    state: TextFieldState,
    diagnostics: List<EditorDiagnostic>,
    modifier: Modifier = Modifier,
    jumpToLine: Int? = null,
    onJumpHandled: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val colors = MCUHomeTheme.colors
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    // The text sits inset from the edge of its column. Everything drawn
    // next to it — the gutter numbers, the current-line band, the
    // underlines — is placed in the text field's own coordinates and has
    // to be shifted by the same inset to line up with the glyphs.
    val contentOffset = with(density) { Offset(EditorContentStart.toPx(), EditorContentTop.toPx()) }

    val textStyle = TextStyle(
        fontFamily = MCUHomeTheme.typography.mono,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        color = colors.ink,
    )
    val gutterStyle = textStyle.copy(color = colors.editorGutter)

    val documentText = state.text.toString()
    val lineStarts = remember(documentText) { lineStartOffsets(documentText) }
    val cursorLine = remember(documentText, state.selection) { lineIndexOf(lineStarts, state.selection.start) }

    // The callback is read through the latest value rather than captured:
    // the modifier below outlives the composition it was built in.
    val reportFocus by rememberUpdatedState(onFocusChanged)

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var pinnedDiagnostic by remember { mutableStateOf<EditorDiagnostic?>(null) }

    // Keyed on the color scheme only: a new OutputTransformation instance
    // restarts the text-input session, which drops keystrokes on wasmJs.
    val highlighting = remember(colors.darkScheme) {
        YamlOutputTransformation(colors.spanStyles(), EDITOR_OVERSCROLL_LINES)
    }

    // The width the longest line needs, so nothing has to wrap. Only that
    // one line is measured, and only when the document changes.
    val longestLine = remember(documentText) { documentText.lineSequence().maxByOrNull { it.length }.orEmpty() }
    val textWidth = with(density) {
        textMeasurer.measure(longestLine, textStyle).size.width.toDp() + EditorContentStart * 2
    }

    Row(modifier.background(colors.surface)) {
        Canvas(
            Modifier
                .width(GutterWidth)
                .fillMaxHeight()
                .background(colors.backgroundAlt)
                // The numbers are drawn at the document's coordinates and
                // shifted by the scroll offset, so a scrolled document puts
                // them above the top edge. Without this they are painted
                // over the header and the top bar.
                .clipToBounds()
                .pointerInput(diagnostics, layout) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press) {
                                val position = event.changes.first().position
                                val line = layout?.let { result ->
                                    lineAtY(result, position.y - contentOffset.y + verticalScroll.value, lineStarts)
                                }
                                pinnedDiagnostic = diagnostics
                                    .firstOrNull { it.line - 1 == line }
                                    .takeIf { it != pinnedDiagnostic }
                            }
                        }
                    }
                },
        ) {
            val result = layout ?: return@Canvas
            translate(top = contentOffset.y - verticalScroll.value) {
                lineStarts.forEachIndexed { index, offset ->
                    val visualLine = result.getLineForOffset(offset)
                    val measured = textMeasurer.measure((index + 1).toString(), gutterStyle)
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

        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val contentWidth = maxOf(textWidth, maxWidth)
            val viewportHeight = constraints.maxHeight
            Box(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScroll)
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
                    },
            ) {
                Box(Modifier.width(contentWidth).fillMaxHeight()) {
                    Canvas(Modifier.fillMaxSize()) {
                        val result = layout ?: return@Canvas
                        translate(left = contentOffset.x, top = contentOffset.y - verticalScroll.value) {
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
                                underlineRect(result, documentText, diagnostic)?.let { rect ->
                                    drawWavyUnderline(rect, colors.severityColor(diagnostic))
                                }
                            }
                        }
                    }

                    BasicTextField(
                        state = state,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = EditorContentStart, top = EditorContentTop, end = EditorContentStart)
                            .onFocusChanged { focus -> reportFocus(focus.isFocused) }
                            .onPreviewKeyEvent { event -> insertIndentOnTab(state, event.key, event.type) },
                        textStyle = textStyle,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        cursorBrush = SolidColor(colors.accent),
                        outputTransformation = highlighting,
                        scrollState = verticalScroll,
                        onTextLayout = { getResult -> layout = getResult() },
                    )

                    val hovered = layout?.let { result ->
                        pointer?.let { position ->
                            diagnostics.firstOrNull { diagnostic ->
                                underlineRect(result, documentText, diagnostic)
                                    ?.translate(contentOffset.x, contentOffset.y - verticalScroll.value)
                                    ?.inflate(HOVER_TOLERANCE)
                                    ?.contains(position + Offset(horizontalScroll.value.toFloat(), 0f)) == true
                            }
                        }
                    }
                    val shown = hovered ?: pinnedDiagnostic
                    if (shown != null) {
                        DiagnosticTooltip(
                            diagnostic = shown,
                            anchor = layout?.let { result ->
                                underlineRect(result, documentText, shown)
                                    ?.translate(contentOffset.x, contentOffset.y - verticalScroll.value)
                            },
                            viewportHeight = viewportHeight,
                        )
                    }
                }
            }

            ThinVerticalScrollbar(verticalScroll, Modifier.align(Alignment.TopEnd).fillMaxHeight())
            ThinHorizontalScrollbar(horizontalScroll, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
    }

    val jumpMargin = with(density) { JUMP_MARGIN.toPx() }
    LaunchedEffect(jumpToLine, layout) {
        val line = jumpToLine ?: return@LaunchedEffect
        val result = layout ?: return@LaunchedEffect
        val offset = lineStarts.getOrNull(line - 1) ?: return@LaunchedEffect
        state.edit { selection = TextRange(offset) }
        val top = result.getLineTop(result.getLineForOffset(offset))
        verticalScroll.animateScrollTo((top - jumpMargin).coerceAtLeast(0f).roundToInt())
        onJumpHandled()
    }
}

/** Tab is an indent, not a way out of the field: YAML is indented with spaces. */
private fun insertIndentOnTab(
    state: TextFieldState,
    key: Key,
    type: KeyEventType,
): Boolean {
    if (type != KeyEventType.KeyDown || key != Key.Tab) return false
    state.edit {
        val at = selection.min
        replace(selection.min, selection.max, INDENT)
        selection = TextRange(at + INDENT.length)
    }
    return true
}

/**
 * The message of the diagnostic under the pointer.
 *
 * It is a popup rather than a box inside the editor: a diagnostic on the
 * last visible line would otherwise have its message cut off by the edge
 * of the column. For the same reason it moves above the line it belongs
 * to when there is no room below it.
 */
@Composable
private fun DiagnosticTooltip(
    diagnostic: EditorDiagnostic,
    anchor: Rect?,
    viewportHeight: Int,
) {
    val colors = MCUHomeTheme.colors
    val density = LocalDensity.current
    val height = with(density) { TooltipHeight.toPx() }
    val bottom = anchor?.bottom ?: 0f
    val above = bottom + height > viewportHeight
    val offset = IntOffset(
        x = (anchor?.left ?: 0f).roundToInt(),
        y = (if (above) (anchor?.top ?: 0f) - height else bottom + 4f).roundToInt(),
    )
    Popup(alignment = Alignment.TopStart, offset = offset) {
        Box(
            Modifier
                .background(colors.surface, RoundedCornerShape(6.dp))
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = diagnostic.message,
                color = colors.ink,
                fontFamily = MCUHomeTheme.typography.body,
                fontSize = 13.sp,
            )
        }
    }
}

/** Applies the syntax colors to the text the field shows, leaving the document untouched. */
private data class YamlOutputTransformation(
    private val styles: Map<YamlToken, SpanStyle>,
    private val trailingBlankLines: Int,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        highlightYaml(asCharSequence()).forEach { span ->
            styles[span.token]?.let { addStyle(it, span.start, span.end) }
        }
        append(overscrollText(trailingBlankLines))
    }
}

private fun MCUHomeColors.spanStyles(): Map<YamlToken, SpanStyle> = mapOf(
    YamlToken.Key to SpanStyle(color = editorKey),
    YamlToken.Value to SpanStyle(color = editorValue),
    YamlToken.Tag to SpanStyle(color = editorTag),
    YamlToken.Literal to SpanStyle(color = editorLiteral),
    YamlToken.Comment to SpanStyle(color = editorComment),
)

private fun MCUHomeColors.severityColor(diagnostic: EditorDiagnostic): Color = when (diagnostic.severity) {
    DiagnosticSeverity.Error -> error
    DiagnosticSeverity.Warning -> warning
    DiagnosticSeverity.Info -> info
}

private fun lineAtY(
    result: TextLayoutResult,
    y: Float,
    lineStarts: List<Int>,
): Int? {
    val visualLine = result.getLineForVerticalPosition(y)
    return lineIndexOf(lineStarts, result.getLineStart(visualLine))
}

/** The stretch of a line a diagnostic underlines: its content, without the indent. */
private fun underlineRect(
    result: TextLayoutResult,
    text: String,
    diagnostic: EditorDiagnostic,
): Rect? {
    val range = contentRangeOfLine(text, diagnostic.line) ?: return null
    val visualLine = result.getLineForOffset(range.first)
    return Rect(
        left = result.getHorizontalPosition(range.first, usePrimaryDirection = true),
        top = result.getLineTop(visualLine),
        right = result.getHorizontalPosition(range.last + 1, usePrimaryDirection = true),
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
