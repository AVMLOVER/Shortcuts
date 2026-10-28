package com.germanium.shortcuts

import android.graphics.Paint
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.germanium.shortcuts.core.*
import kotlin.math.max
import kotlin.math.min

internal val VariableOrange = Color(0xFFF3A33C)
internal data class InspectedVariable(val field: String, val token: ReferenceToken)
internal class VariableBinding(val id: String, val read: () -> TextFieldValue, val write: (TextFieldValue) -> Unit, val numeric: Boolean = false)
internal class VariableSession {
    var active: VariableBinding? = null
    var inspected by mutableStateOf<InspectedVariable?>(null)
    var pendingFocus by mutableStateOf<String?>(null)
    fun insert(text: String) {
        val field = active ?: return
        val value = field.read()
        val edited = if (field.numeric && text.startsWith("{{")) TemplateEditing.replace(value.text, 0, value.text.length, text) else TemplateEditing.replace(value.text, value.selection.start, value.selection.end, text)
        field.write(TextFieldValue(edited.text, TextRange(edited.start, edited.end)))
        inspected = null; pendingFocus = field.id
    }
    fun removeInspected() { insert("") }
}

private data class DisplayToken(val token: ReferenceToken, val start: Int, val end: Int)
private class VariableTransformation(private val names: Map<String, String>, private val foreground: Color, private val maxWidth: Float, private val fontPx: Float) : VisualTransformation {
    var displayed: List<DisplayToken> = emptyList()
        private set
    override fun filter(text: AnnotatedString): TransformedText {
        val result = AnnotatedString.Builder()
        val spans = ArrayList<DisplayToken>()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fontPx }
        var cursor = 0
        for (token in TemplateEditing.tokens(text.text)) {
            result.append(text.text.substring(cursor, token.start))
            var label = names[token.name] ?: token.name.takeUnless { it.startsWith('@') } ?: "…"
            val limit = max(24f, maxWidth - fontPx * 3)
            if (paint.measureText(label) > limit) {
                val original = label
                while (label.isNotEmpty() && paint.measureText("$label…") > limit) label = label.dropLast(if (label.last().isLowSurrogate()) 2 else 1)
                if (label != original) label += "…"
            }
            val start = result.length
            result.append("\u2066\u00A0")
            result.pushStyle(SpanStyle(color = VariableOrange, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
            result.append("x")
            result.pop()
            result.pushStyle(SpanStyle(color = foreground, fontWeight = FontWeight.Medium))
            result.append("\u00A0" + label.codePoints().toArray().map { String(Character.toChars(it)) }.joinToString("\u2060").replace(' ', '\u00A0') + "\u00A0")
            result.pop(); result.append("\u2069")
            spans += DisplayToken(token, start, result.length)
            cursor = token.end
        }
        result.append(text.text.substring(cursor)); displayed = spans
        val transformed = result.toAnnotatedString()
        val mapping = object : OffsetMapping {
            private fun map(offset: Int, original: Boolean): Int {
                var low = 0; var high = spans.lastIndex; var previous = -1
                while (low <= high) { val middle = (low + high) ushr 1; val start = if (original) spans[middle].token.start else spans[middle].start
                    if (start <= offset) { previous = middle; low = middle + 1 } else high = middle - 1 }
                if (previous < 0) return offset
                val item = spans[previous]
                val start = if (original) item.token.start else item.start; val end = if (original) item.token.end else item.end
                val toStart = if (original) item.start else item.token.start; val toEnd = if (original) item.end else item.token.end
                return if (offset >= end) toEnd + offset - end else if (offset - start < end - offset) toStart else toEnd
            }
            override fun originalToTransformed(offset: Int) = map(offset, true).coerceIn(0, transformed.length)
            override fun transformedToOriginal(offset: Int) = map(offset, false).coerceIn(0, text.length)
        }
        return TransformedText(transformed, mapping)
    }
}

/** Compose text input with real cursor/IME support; references are rendered and edited as atomic pills. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun VariableTextField(id: String, value: String, changed: (String) -> Unit, label: String,
    session: VariableSession, names: Map<String, String>, modifier: Modifier = Modifier, enabled: Boolean = true,
    multiline: Boolean = false, focused: () -> Unit = {}, minLines: Int = if (multiline) 2 else 1,
    keyboardType: KeyboardType = KeyboardType.Text) {
    var local by rememberSaveable(id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val onChange by rememberUpdatedState(changed)
    val onFocus by rememberUpdatedState(focused)
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fontPx = with(density) { 16.sp.toPx() }
    val colors = MaterialTheme.colorScheme
    val transformation = remember(names, width, colors.onPrimaryContainer, fontPx) { VariableTransformation(names, colors.onPrimaryContainer, if (width == 0) 600f else width.toFloat(), fontPx) }
    fun apply(next: TextFieldValue) {
        if (next.text.length > 262144) return
        val edit = TemplateEditing.reconcile(local.text, next.text, next.selection.start, next.selection.end)
        val corrected = if (edit.text == next.text && edit.start == next.selection.start && edit.end == next.selection.end) next
            else TextFieldValue(edit.text, TextRange(edit.start, edit.end))
        if (keyboardType == KeyboardType.Decimal && corrected.text.isNotEmpty() && !corrected.text.matches(Regex("[-+]?(?:[0-9]*(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]*)?")) && TemplateEditing.tokens(corrected.text).none { it.start == 0 && it.end == corrected.text.length }) return
        val different = local.text != corrected.text
        local = corrected
        if (different) { session.inspected = null; onChange(corrected.text) }
    }
    val binding = remember(id, keyboardType) { VariableBinding(id, { local }, { next -> local = next; onChange(next.text) }, keyboardType == KeyboardType.Decimal) }
    LaunchedEffect(value) { if (value != local.text) {
        val previous = session.active?.takeIf { it.id == id }?.read()
        val selection = if (session.pendingFocus == id && previous?.text == value) previous.selection else TextRange(local.selection.end.coerceAtMost(value.length))
        local = TextFieldValue(value, selection)
    } }
    LaunchedEffect(session.pendingFocus) { if (session.pendingFocus == id && enabled) { session.active = binding; requester.requestFocus(); keyboard?.show(); session.pendingFocus = null } }
    BasicTextField(local, ::apply, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(requester).onFocusChanged {
        if (it.isFocused) { if (session.inspected?.field != id) session.inspected = null; session.active = binding; onFocus() }
    }, enabled = enabled, minLines = minLines, singleLine = !multiline,
        interactionSource = interaction, visualTransformation = transformation,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, lineHeight = 28.sp),
        cursorBrush = SolidColor(colors.primary), onTextLayout = { layout = it }, decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(value = local.text, innerTextField = {
                Box(Modifier.testTag("tokens_$id").onSizeChanged { width = it.width }.drawBehind {
                    val measured = layout ?: return@drawBehind
                    for (span in transformation.displayed) {
                        if (span.end > measured.layoutInput.text.length) continue
                        val firstLine = measured.getLineForOffset(span.start); val lastLine = measured.getLineForOffset((span.end - 1).coerceAtLeast(span.start))
                        for (line in firstLine..lastLine) {
                            val first = max(span.start, measured.getLineStart(line)); val last = min(span.end, measured.getLineEnd(line))
                            if (last <= first) continue
                            var left = Float.MAX_VALUE; var right = -Float.MAX_VALUE
                            for (index in first until last) { val rect = measured.getBoundingBox(index); left = min(left, rect.left); right = max(right, rect.right) }
                            val top = measured.getLineTop(line) + 1.dp.toPx(); val bottom = measured.getLineBottom(line) - 1.dp.toPx()
                            drawRoundRect(colors.primaryContainer.copy(alpha = 0.7f), Offset(left, top), Size((right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)), CornerRadius(9.dp.toPx()))
                        }
                    }
                }.pointerInput(transformation, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val measured = layout ?: return@awaitEachGesture
                        val offset = measured.getOffsetForPosition(down.position)
                        val token = transformation.displayed.firstOrNull { offset >= it.start && offset < it.end }?.token ?: return@awaitEachGesture
                        down.consume()
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop * 2) {
                            up.consume(); requester.requestFocus(); session.active = binding
                            local = local.copy(selection = TextRange(token.start, token.end), composition = null)
                            onFocus(); session.inspected = InspectedVariable(id, token); keyboard?.hide()
                        }
                    }
                }) { inner() }
            }, enabled = enabled, singleLine = !multiline, visualTransformation = transformation,
                interactionSource = interaction, label = { Text(label) },
                container = { OutlinedTextFieldDefaults.Container(enabled, false, interaction, shape = RoundedCornerShape(16.dp)) })
        })
}
