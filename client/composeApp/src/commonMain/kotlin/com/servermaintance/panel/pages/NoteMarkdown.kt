package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.platformClipboard
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ==================================================================== *
 * NoteMarkdown —— 对齐网页端 md.js 的完整渲染器（原 MarkdownLite 的替代）。
 * 支持：表格 / 任务清单 / 嵌套列表 / 删除线 / ==高亮== / 代码块（语言
 * 徽标 + 自动检测 + 语法高亮 + 复制按钮）/ 引用 / 分割线 / 图片真实加载 /
 * 视频·音频·YouTube·B站 链接卡。
 * ==================================================================== */

@Composable
fun NoteMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
) {
    val nodes = remember(text) { parseMdNodes(text) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        RenderNodes(nodes, fontSize)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun RenderNodes(nodes: List<MdNode>, fontSize: Int) {
    var i = 0
    while (i < nodes.size) {
        when (val node = nodes[i]) {
            is MdNode.Heading -> MdHeading(node, fontSize)
            is MdNode.Code -> MdCodeBlock(node, fontSize)
            is MdNode.Quote -> MdQuote(node, fontSize)
            is MdNode.Hr -> MdHr()
            is MdNode.Table -> MdTable(node, fontSize)
            is MdNode.Media -> MdMedia(node, fontSize)
            is MdNode.Para -> MdPara(node, fontSize)
            is MdNode.ListItem -> {
                // 收集连续列表项
                val group = ArrayList<MdNode.ListItem>()
                while (i < nodes.size && nodes[i] is MdNode.ListItem) {
                    group += nodes[i] as MdNode.ListItem
                    i++
                }
                MdList(group, fontSize)
                continue
            }
        }
        i++
    }
}

/* -------------------- 标题（h1 带底部分割线，同 .markdown h1） -------------------- */

@Composable
private fun MdHeading(node: MdNode.Heading, fontSize: Int) {
    val size = when (node.level) {
        1 -> (fontSize * 1.6f)
        2 -> (fontSize * 1.35f)
        3 -> (fontSize * 1.15f)
        else -> (fontSize * 1.02f)
    }
    val base = size.toInt()
    val content: @Composable () -> Unit = {
        Text(
            renderInline(node.text, base, bold = true),
            color = Palette.text,
            fontSize = size.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = (size * 1.35f).sp,
        )
    }
    if (node.level == 1) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
            content()
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
        }
    } else {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) { content() }
    }
}

/* -------------------- 代码块（语言徽标 + 复制 + 高亮，同 .codeblock） -------------------- */

@Composable
private fun MdCodeBlock(node: MdNode.Code, fontSize: Int) {
    val shape = RoundedCornerShape(8.dp)
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val label = node.explicitLang.ifEmpty {
        if (node.lang != "text") node.lang else "code"
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .clip(shape)
            .border(1.dp, Color(0xFF111827), shape),
    ) {
        // 头部：语言徽标 + 复制按钮（同 .code-head）
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF0B1120))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val badgeShape = RoundedCornerShape(50)
            Box(
                Modifier.background(AccentState.current.primary.copy(alpha = 0.16f), badgeShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    label.uppercase(),
                    color = Color(0xFF818CF8),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(7.dp))
                    .background(if (copied) Color(0xFF14532D) else Color(0xFF1E293B))
                    .border(1.dp, if (copied) Color(0xFF166534) else Color(0xFF334155), RoundedCornerShape(7.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        platformClipboard.copy(node.code)
                        copied = true
                        scope.launch { delay(1600); copied = false }
                    }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    if (copied) L["已复制_e381a5763d"] else L["复制_4edd1d0087"],
                    color = if (copied) Color(0xFF4ADE80) else Color(0xFFCBD5E1),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        // 代码体（横向可滚，等宽）
        Box(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .background(Color(0xFF0F172A))
                .padding(horizontal = 15.dp, vertical = 13.dp),
        ) {
            Text(
                highlightCode(node.code, node.lang),
                color = MdTok.base,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 21.sp,
            )
        }
    }
}

/* -------------------- 引用（accent 左条 + soft 底，同 .markdown blockquote） -------------------- */

@Composable
private fun MdQuote(node: MdNode.Quote, fontSize: Int) {
    val accent = AccentState.current.primary
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(0.dp, 8.dp, 8.dp, 0.dp))
            .background(accent.copy(alpha = 0.08f)),
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 18.dp).background(accent))
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 3.dp, bottom = 3.dp, end = 8.dp)) {
            node.paras.forEach { p ->
                Text(
                    renderInline(p, fontSize),
                    color = Palette.muted,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.6f).sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/* -------------------- 分割线 -------------------- */

@Composable
private fun MdHr() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 9.dp)
            .height(1.dp).background(Palette.border2),
    )
}

/* -------------------- 表格（同 .markdown table） -------------------- */

@Composable
private fun MdTable(node: MdNode.Table, fontSize: Int) {
    val cols = maxOf(node.head.size, node.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).horizontalScroll(rememberScrollState())) {
        Column(
            Modifier.border(1.dp, Palette.border, shape).clip(shape)
                .background(Palette.tableSurface),
        ) {
            // 表头
            Row(Modifier.background(Palette.tableHeader)) {
                repeat(cols) { c ->
                    Box(Modifier.widthIn(min = 64.dp).padding(horizontal = 11.dp, vertical = 7.dp)) {
                        Text(
                            renderInline(node.head.getOrElse(c) { "" }, fontSize - 1),
                            color = Palette.muted,
                            fontSize = (fontSize - 1).sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border2))
            // 表体
            node.rows.forEachIndexed { r, row ->
                Row {
                    repeat(cols) { c ->
                        Box(Modifier.widthIn(min = 64.dp).padding(horizontal = 11.dp, vertical = 6.dp)) {
                            Text(
                                renderInline(row.getOrElse(c) { "" }, fontSize - 1),
                                color = Palette.text,
                                fontSize = (fontSize - 1).sp,
                                lineHeight = (fontSize + 5).sp,
                                maxLines = 8,
                            )
                        }
                    }
                }
                if (r != node.rows.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.tableDivider))
                }
            }
        }
    }
}

/* -------------------- 列表（嵌套 + 任务清单） -------------------- */

@Composable
private fun MdList(items: List<MdNode.ListItem>, fontSize: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        items.forEach { item ->
            val indent = 16.dp * item.depth
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).padding(start = indent),
                verticalAlignment = Alignment.Top,
            ) {
                when {
                    item.checked != null -> {
                        val boxShape = RoundedCornerShape(3.dp)
                        Box(
                            Modifier.padding(top = 3.dp).size(14.dp).clip(boxShape)
                                .background(if (item.checked) AccentState.current.primary else Color.Transparent)
                                .border(1.2.dp, if (item.checked) AccentState.current.primary else Palette.border2, boxShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.checked) {
                                Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(7.dp))
                    }
                    item.ordered -> {
                        Text(
                            "${item.index}. ",
                            color = AccentState.current.secondary,
                            fontSize = fontSize.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    else -> {
                        Text(
                            L["s_f79cc0e87e"],
                            color = AccentState.current.secondary,
                            fontSize = fontSize.sp,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
                Text(
                    renderInline(item.text, fontSize),
                    color = if (item.checked == true) Palette.muted else Palette.text,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.6f).sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/* -------------------- 段落（多行以换行合并，同 <br>） -------------------- */

@Composable
private fun MdPara(node: MdNode.Para, fontSize: Int) {
    val text = node.lines.joinToString("\n")
    if (text.isBlank()) {
        Spacer(Modifier.height(6.dp))
        return
    }
    Text(
        renderInlineMultiline(text, fontSize),
        color = Palette.text,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.75f).sp,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

/** 段落多行：先按行渲染行内样式再以 \n 拼接（等价 <br>）。 */
private fun renderInlineMultiline(text: String, fontSize: Int): androidx.compose.ui.text.AnnotatedString {
    val parts = text.split('\n')
    return androidx.compose.ui.text.buildAnnotatedString {
        parts.forEachIndexed { idx, p ->
            append(renderInline(p, fontSize))
            if (idx != parts.lastIndex) append("\n")
        }
    }
}

/* -------------------- 媒体（图片真实加载 / 视频音频卡 / YT·B站卡） -------------------- */

@Composable
private fun MdMedia(node: MdNode.Media, fontSize: Int) {
    when (node.kind) {
        "image" -> NoteImage(node.url, node.alt, Modifier.fillMaxWidth().padding(vertical = 4.dp))
        "video" -> NoteMediaLink(node.url, "video", Modifier.fillMaxWidth().padding(vertical = 4.dp))
        "audio" -> NoteMediaLink(node.url, "audio", Modifier.fillMaxWidth().padding(vertical = 4.dp))
        "youtube" -> {
            val id = mdYoutubeId(node.url)
            NoteVideoEmbed(
                "https://www.youtube-nocookie.com/embed/$id",
                L["youtube_视频_70a0be3df0"],
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        "bilibili" -> {
            val bvid = mdBiliId(node.url)
            NoteVideoEmbed(
                "https://player.bilibili.com/player.html?bvid=$bvid&autoplay=0&high_quality=1",
                L["bilibili_视频_7fcd50c628"],
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
    }
}

/* -------------------- 兼容入口：MarkdownLite 指向新渲染器 -------------------- */

/** 旧名保留（调用点无需改动）；实现已升级为完整 [NoteMarkdown]。 */
@Composable
fun MarkdownLite(text: String, modifier: Modifier = Modifier, fontSize: Int = 13) =
    NoteMarkdown(text, modifier, fontSize)
