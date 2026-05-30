package com.example.adaptivellm.ui

import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.example.adaptivellm.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

// --- Inline LaTeX → Unicode conversion ---

private val superscriptMap = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'n' to 'ⁿ', 'i' to 'ⁱ', 'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ',
    'k' to 'ᵏ', 'm' to 'ᵐ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
    'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ',
)

private val subscriptMap = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ',
    'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'n' to 'ₙ', 'p' to 'ₚ',
    'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ',
)

private fun toSuperscript(s: String) = s.map { superscriptMap[it] ?: it }.joinToString("")
private fun toSubscript(s: String) = s.map { subscriptMap[it] ?: it }.joinToString("")

private val latexCommands = listOf(
    "\\implies" to " ⟹ ", "\\Rightarrow" to " ⇒ ", "\\Leftarrow" to " ⇐ ",
    "\\leftrightarrow" to " ↔ ", "\\to" to " → ",
    "\\times" to "×", "\\cdot" to "·", "\\pm" to "±", "\\mp" to "∓",
    "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
    "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
    "\\infty" to "∞", "\\pi" to "π",
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
    "\\epsilon" to "ε", "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ",
    "\\sigma" to "σ", "\\phi" to "φ", "\\omega" to "ω",
    "\\Delta" to "Δ", "\\Sigma" to "Σ", "\\Pi" to "Π", "\\Omega" to "Ω",
    "\\in" to " ∈ ", "\\notin" to " ∉ ",
    "\\subset" to " ⊂ ", "\\subseteq" to " ⊆ ",
    "\\cup" to " ∪ ", "\\cap" to " ∩ ",
    "\\forall" to "∀", "\\exists" to "∃",
    "\\partial" to "∂", "\\nabla" to "∇",
    "\\ldots" to "…", "\\cdots" to "⋯",
    "\\langle" to "⟨", "\\rangle" to "⟩",
    "\\sum" to "Σ", "\\prod" to "Π", "\\int" to "∫",
    "\\left" to "", "\\right" to "",
    "\\," to " ", "\\;" to " ", "\\!" to "", "\\quad" to "  ",
    "\\text" to "",
)

private fun latexExprToUnicode(expr: String): String {
    var r = expr.trim()
    // \frac{a}{b} → a/b
    r = Regex("""\\frac\{([^}]*)\}\{([^}]*)\}""").replace(r) { m ->
        "${m.groupValues[1]}/${m.groupValues[2]}"
    }
    // \sqrt{x} → √(x)
    r = Regex("""\\sqrt\{([^}]*)\}""").replace(r) { "√(${it.groupValues[1]})" }
    // Simple commands
    for ((cmd, repl) in latexCommands) r = r.replace(cmd, repl)
    // Superscripts: ^{abc} or ^x
    r = Regex("""\^\{([^}]+)\}""").replace(r) { toSuperscript(it.groupValues[1]) }
    r = Regex("""\^(\w)""").replace(r) { toSuperscript(it.groupValues[1]) }
    // Subscripts: _{abc} or _x
    r = Regex("""_\{([^}]+)\}""").replace(r) { toSubscript(it.groupValues[1]) }
    r = Regex("""_(\w)""").replace(r) { toSubscript(it.groupValues[1]) }
    // Remove remaining braces
    r = r.replace("{", "").replace("}", "")
    return r
}

/** Convert inline $...$ LaTeX to Unicode, leaving $$...$$ display blocks for JLatexMathPlugin. */
private fun processInlineLatex(text: String): String {
    return Regex("""(?<!\$)\$(?!\$)(.+?)\$(?!\$)""").replace(text) { match ->
        latexExprToUnicode(match.groupValues[1])
    }
}

// Material Design "content_copy" icon (avoids material-icons-extended dependency)
internal val CopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f)
            lineTo(4f, 1f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            lineTo(2f, 17f)
            horizontalLineTo(4f)
            lineTo(4f, 3f)
            horizontalLineTo(16f)
            close()
            moveTo(19f, 5f)
            lineTo(8f, 5f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            lineTo(6f, 21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineTo(19f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            lineTo(21f, 7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            lineTo(8f, 7f)
            horizontalLineTo(19f)
            close()
        }
    }.build()
}

// --- Markdown segment parsing ---

private sealed class MarkdownSegment {
    data class Prose(val text: String) : MarkdownSegment()
    data class Code(val code: String, val language: String) : MarkdownSegment()
}

private val codeBlockRegex = Regex("```(\\w*)\\n([\\s\\S]*?)```")

private fun parseSegments(text: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    var lastEnd = 0

    for (match in codeBlockRegex.findAll(text)) {
        val before = text.substring(lastEnd, match.range.first).trim()
        if (before.isNotEmpty()) segments.add(MarkdownSegment.Prose(before))
        segments.add(
            MarkdownSegment.Code(
                code = match.groupValues[2].trimEnd(),
                language = match.groupValues[1],
            )
        )
        lastEnd = match.range.last + 1
    }

    val remaining = text.substring(lastEnd).trim()
    if (remaining.isNotEmpty()) segments.add(MarkdownSegment.Prose(remaining))

    return segments
}

// --- Composables ---

/** Renders markdown prose via Markwon (selectable text). */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enableLatex: Boolean = true,
) {
    val context = LocalContext.current
    val colorArgb = color.toArgb()

    // Convert inline $...$ to Unicode (always — works during streaming too)
    val processedText = remember(text) { processInlineLatex(text) }

    // Full Markwon with JLatexMathPlugin for display $$...$$ blocks
    val markwonFull = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(40f) { builder ->
                builder.inlinesEnabled(true)
            })
            .build()
    }

    // Lightweight Markwon without LaTeX (fast, no jitter during streaming)
    val markwonLight = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .build()
    }

    val markwon = if (enableLatex) markwonFull else markwonLight

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(colorArgb)
                textSize = 14f
                setPadding(0, 0, 0, 0)
                setTextIsSelectable(true)
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            if ((v as TextView).hasSelection()) {
                                v.parent.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.parent.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    false
                }
            }
        },
        update = { tv ->
            tv.setTextColor(colorArgb)
            markwon.setMarkdown(tv, processedText)
        },
    )
}

/** Fenced code block with a copy button in the top-right corner. */
@Composable
@Suppress("DEPRECATION") // LocalClipboard миграция на suspend API — отдельная задача
private fun CodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.25f)),
    ) {
        // Language label (top-left)
        if (language.isNotEmpty()) {
            Text(
                text = language,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // Copy button (top-right)
        IconButton(
            onClick = { clipboardManager.setText(AnnotatedString(code)) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp),
        ) {
            Icon(
                CopyIcon,
                contentDescription = stringResource(R.string.markdown_copy_code),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }

        // Scrollable code text
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
                .padding(top = 20.dp),
        )
    }
}

/**
 * Renders a full assistant message: splits by fenced code blocks,
 * renders prose via Markwon and code blocks with copy buttons.
 */
@Composable
fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    isGenerating: Boolean = false,
) {
    val segments = remember(text) { parseSegments(text) }

    Column(modifier = modifier) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.Prose -> MarkdownText(
                    text = segment.text,
                    color = color,
                    enableLatex = !isGenerating,
                )
                is MarkdownSegment.Code -> CodeBlock(
                    code = segment.code,
                    language = segment.language,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}
