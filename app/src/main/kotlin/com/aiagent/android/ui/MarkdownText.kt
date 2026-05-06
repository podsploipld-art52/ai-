package com.aiagent.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight Markdown renderer for the agent's chat replies.
 *
 * Supports the subset of Markdown that an LLM actually emits in chat:
 *  - Fenced code blocks ```lang\n...```. Rendered as a separate **code cell** with a
 *    monospace background and «Скопировать» / «Сохранить» buttons.
 *  - Inline `code`, **bold**, *italic*, ~~strike~~, [text](url).
 *  - Headers (`#`, `##`, `###`) and unordered lists (`-`, `*`).
 *
 * Anything that doesn't match falls through as plain text. We intentionally avoid pulling
 * in a full Markdown library to keep the APK size small — this covers ~95% of what the
 * model actually outputs.
 */
@Composable
fun MarkdownText(
    source: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(source) { parseMarkdownBlocks(source) }
    Column(modifier = modifier.fillMaxWidth()) {
        for ((index, block) in blocks.withIndex()) {
            if (index > 0) Spacer(Modifier.height(4.dp))
            when (block) {
                is MdBlock.Paragraph -> SelectionContainer {
                    Text(
                        text = renderInline(block.text),
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is MdBlock.Header -> SelectionContainer {
                    Text(
                        text = renderInline(block.text),
                        color = color,
                        fontWeight = FontWeight.Bold,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                    )
                }
                is MdBlock.ListItem -> SelectionContainer {
                    Text(
                        text = buildAnnotatedString {
                            append("• ")
                            append(renderInline(block.text))
                        },
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is MdBlock.CodeBlock -> CodeCell(language = block.language, code = block.code)
            }
        }
    }
}

/** Renders one fenced code block as a separate cell with copy & save buttons. */
@Composable
private fun CodeCell(language: String, code: String) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF263238), RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = if (language.isBlank()) "code" else language,
                color = Color(0xFF80CBC4),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                copyToClipboard(ctx, code)
                Toast.makeText(ctx, "Код скопирован", Toast.LENGTH_SHORT).show()
            }) { Text("Копировать", style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = {
                val path = saveCodeToAgentFolder(ctx, language, code)
                Toast.makeText(ctx, "Сохранено: $path", Toast.LENGTH_LONG).show()
            }) { Text("Сохранить", style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = {
                shareCode(ctx, code)
            }) { Text("Поделиться", style = MaterialTheme.typography.labelSmall) }
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = code,
                    color = Color(0xFFECEFF1),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ai-agent-code", text))
}

private fun shareCode(ctx: Context, code: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, code)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    ctx.startActivity(Intent.createChooser(intent, "Поделиться кодом").apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    })
}

/**
 * Save the code block to the AI Agent folder so the user can find it from a file
 * manager. Tries `Documents/AI-Agent/` (visible to the user) and falls back to the
 * app-private external dir.
 */
private fun saveCodeToAgentFolder(ctx: Context, language: String, code: String): String {
    val ext = when (language.lowercase()) {
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "c++", "cpp" -> "cpp"
        "c" -> "c"
        "html" -> "html"
        "css" -> "css"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "xml" -> "xml"
        "sh", "bash" -> "sh"
        "rust", "rs" -> "rs"
        "go" -> "go"
        else -> "txt"
    }
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val name = "code-$ts.$ext"
    val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val candidates = listOfNotNull(
        File(publicDocs, "AI-Agent"),
        ctx.getExternalFilesDir(null)?.let { File(it, "code") },
        File(ctx.filesDir, "code"),
    )
    for (dir in candidates) {
        val ok = runCatching { dir.mkdirs() }.getOrDefault(false) || dir.exists()
        if (!ok) continue
        val target = File(dir, name)
        val written = runCatching { target.writeText(code) }.isSuccess
        if (written) return target.absolutePath
    }
    return "ошибка: не удалось сохранить"
}

// ----------------- Parser -----------------------------------------------------------------

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Header(val level: Int, val text: String) : MdBlock
    data class ListItem(val text: String) : MdBlock
    data class CodeBlock(val language: String, val code: String) : MdBlock
}

private fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = source.lines()
    var i = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotBlank()) out.add(MdBlock.Paragraph(paragraph.toString().trimEnd()))
        paragraph.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val fence = Regex("^```\\s*(\\S*)").find(line)
        if (fence != null) {
            flushParagraph()
            val lang = fence.groupValues[1]
            val sb = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                sb.appendLine(lines[i])
                i++
            }
            // Skip closing fence
            if (i < lines.size) i++
            out.add(MdBlock.CodeBlock(language = lang, code = sb.toString().trimEnd('\n')))
            continue
        }
        val header = Regex("^(#{1,6})\\s+(.*)$").find(line)
        if (header != null) {
            flushParagraph()
            out.add(MdBlock.Header(level = header.groupValues[1].length, text = header.groupValues[2]))
            i++
            continue
        }
        val listItem = Regex("^[\\s]*[-*+]\\s+(.*)$").find(line)
        if (listItem != null) {
            flushParagraph()
            out.add(MdBlock.ListItem(text = listItem.groupValues[1]))
            i++
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line)
        i++
    }
    flushParagraph()
    return out
}

private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val rest = text.substring(i)
        // Inline code `...`
        if (rest.startsWith("`")) {
            val end = rest.indexOf('`', 1)
            if (end > 0) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFE0E0E0))) {
                    append(rest.substring(1, end))
                }
                i += end + 1
                continue
            }
        }
        // Bold **...**
        if (rest.startsWith("**")) {
            val end = rest.indexOf("**", 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(renderInline(rest.substring(2, end)))
                }
                i += end + 2
                continue
            }
        }
        // Italic *...* (single asterisk; not adjacent **).
        if (rest.startsWith("*") && !rest.startsWith("**") && rest.length > 1) {
            val end = findMatchingChar(rest, '*', startIndex = 1)
            if (end > 0) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(renderInline(rest.substring(1, end)))
                }
                i += end + 1
                continue
            }
        }
        // Strike ~~...~~
        if (rest.startsWith("~~")) {
            val end = rest.indexOf("~~", 2)
            if (end > 0) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(renderInline(rest.substring(2, end)))
                }
                i += end + 2
                continue
            }
        }
        // Link [text](url)
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close > 0 && close + 1 < rest.length && rest[close + 1] == '(') {
                val end = rest.indexOf(')', close + 2)
                if (end > 0) {
                    val label = rest.substring(1, close)
                    val url = rest.substring(close + 2, end)
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF1E88E5),
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) { append(label) }
                    append(" (")
                    append(url)
                    append(")")
                    i += end + 1
                    continue
                }
            }
        }
        append(rest[0])
        i++
    }
}

private fun findMatchingChar(s: String, ch: Char, startIndex: Int): Int {
    var idx = startIndex
    while (idx < s.length) {
        if (s[idx] == ch) return idx
        idx++
    }
    return -1
}


