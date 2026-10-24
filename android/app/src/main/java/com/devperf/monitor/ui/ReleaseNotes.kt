package com.devperf.monitor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 把 GitHub Release 的 Markdown 更新说明渲染成正常排版（1.0.20）。
 *
 * 之前是整段原始 Markdown 丢进一个 `Text`，于是 `**加粗**`、`- 列表`、`# 标题`、
 * `> 引用`、`` `代码` `` 这些标记符号原样糊在屏幕上，看起来就是「一坨文字 + 一堆
 * 特殊符号」。这里做轻量解析：标题 / 引用 / 列表 / 段落四类块，外加行内的加粗与
 * 等宽代码，够覆盖我们自己写的更新说明（不引第三方 Markdown 库，包体与依赖都省）。
 */
@Composable
fun ReleaseNotes(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Column(modifier = modifier.fillMaxWidth()) {
        parseBlocks(text).forEach { block ->
            when (block) {
                is Block.Heading -> HeadingBlock(block)
                is Block.Quote -> QuoteBlock(block)
                is Block.Bullet -> BulletBlock(block)
                is Block.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: Block.Heading) {
    Text(
        text = inlineMarkdown(block.text),
        style = if (block.level <= 2) {
            MaterialTheme.typography.titleSmall
        } else {
            MaterialTheme.typography.titleSmall
        },
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/** 引用块：缩进 + 弱化小字（更新说明顶部的「注：…」）。 */
@Composable
private fun QuoteBlock(block: Block.Quote) {
    Text(
        text = inlineMarkdown(block.text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
    )
}

@Composable
private fun BulletBlock(block: Block.Bullet) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = inlineMarkdown(block.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// 解析：Markdown -> 块
// ---------------------------------------------------------------------------

private sealed interface Block {
    data class Heading(val text: String, val level: Int) : Block
    data class Quote(val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Paragraph(val text: String) : Block
}

private val ORDERED_BULLET = Regex("^\\d+[.)]\\s+.*")
private val CIRCLED_BULLET = Regex("^[①②③④⑤⑥⑦⑧⑨⑩].*")

private fun parseBlocks(raw: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(Block.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }

    raw.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isEmpty() -> flushParagraph()
            line.startsWith("#") -> {
                flushParagraph()
                val hashes = line.takeWhile { it == '#' }.length
                blocks.add(Block.Heading(line.trimStart('#').trim(), hashes))
            }
            line.startsWith(">") -> {
                flushParagraph()
                blocks.add(Block.Quote(line.removePrefix(">").trim()))
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flushParagraph()
                blocks.add(Block.Bullet(line.substring(2).trim()))
            }
            ORDERED_BULLET.matches(line) || CIRCLED_BULLET.matches(line) -> {
                flushParagraph()
                blocks.add(Block.Bullet(line))
            }
            else -> {
                // 段内换行按空格接上（Markdown 的软换行），避免行末硬断
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * 行内标记：`**加粗**`、`` `等宽` ``。只处理这两种——更新说明里用不到链接和
 * 图片，多了反而会渲染出意外内容（比如把 URL 当图片占位）。
 */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (cursor < text.length) {
        val boldStart = text.indexOf("**", cursor)
        val codeStart = text.indexOf('`', cursor)
        val useBold = boldStart >= 0 && (codeStart < 0 || boldStart < codeStart)
        val marker = if (useBold) "**" else "`"
        val start = if (useBold) boldStart else codeStart
        if (start < 0) {
            append(text.substring(cursor))
            return@buildAnnotatedString
        }
        val end = text.indexOf(marker, start + marker.length)
        if (end <= start + marker.length) {
            // 没有配对的后半个标记：原样输出剩余内容
            append(text.substring(cursor))
            return@buildAnnotatedString
        }
        append(text.substring(cursor, start))
        pushStyle(
            if (useBold) {
                SpanStyle(fontWeight = FontWeight.Bold)
            } else {
                SpanStyle(fontFamily = FontFamily.Monospace)
            },
        )
        append(text.substring(start + marker.length, end))
        pop()
        cursor = end + marker.length
    }
}
