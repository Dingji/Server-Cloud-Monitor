package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.int
import com.servermaintance.panel.platformExporter
import com.servermaintance.panel.pickTextFiles
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ==================================================================== *
 * 导入 / 导出 —— 复刻 notebox「transfer」视图
 * 端点：POST /api/import/notes {split, files:[{name,content}]}
 *       POST /api/import/todos {name, content, format:"auto"}
 *       POST /api/import/backup {content, mode: merge|replace}
 *       GET  /api/export | /api/export/markdown | /api/export/csv
 * ==================================================================== */

@Composable
fun NoteTransferPage() {
    NoteGate { TransferInner() }
}

@Composable
private fun TransferInner() {
    val scope = rememberCoroutineScope()
    var split by remember { mutableStateOf(true) }
    var notesStatus by remember { mutableStateOf("") }
    var todosStatus by remember { mutableStateOf("") }
    var backupStatus by remember { mutableStateOf("") }
    var backupMode by remember { mutableStateOf("merge") }
    var exporting by remember { mutableStateOf<String?>(null) }

    PageScaffold(
        title = "导入 / 导出",
        desc = "NoteBox 数据迁移",
        actions = { NoteUserChip() },
    ) {
        GridLayout(columns = 2, spacing = 12.dp) {

        TransferCard(
            icon = "note",
            title = "导入笔记",
            desc = "支持 Markdown / 纯文本 / HTML，HTML 自动转成可编辑的 Markdown。含多个一级标题时自动拆分成多条笔记。",
            formats = listOf(".md", ".markdown", ".txt", ".html", ".htm"),
        ) {
            CheckRow(split, { split = it }, "按一级标题拆分")
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryBtn(text = "选择文件导入", icon = "import", onClick = {
                    pickTextFiles(listOf("md", "markdown", "txt", "text", "html", "htm"), multiple = true) { files ->
                        if (files.isEmpty()) return@pickTextFiles
                        scope.launch {
                            notesStatus = "导入中…"
                            try {
                                val n = withContext(Dispatchers.IO) {
                                    NoteBoxState.api.importNotes(files.map { it.name to it.content }, split)
                                }
                                notesStatus = "成功导入 $n 条笔记"
                                NoteBoxState.toast("已导入 $n 条笔记", ToastKind.Ok)
                                NoteBoxState.refreshStats()
                            } catch (e: Exception) {
                                notesStatus = "导入失败：" + (e.message ?: "")
                                NoteBoxState.toast(notesStatus, ToastKind.Err)
                            }
                        }
                    }
                })
                StatusText(notesStatus)
            }
        }

        TransferCard(
            icon = "checkSquare",
            title = "导入待办",
            desc = "支持 CSV（有/无表头均可，列名支持 标题/截止/优先级/备注/状态）、纯文本清单（- [x] 语法）、JSON 数组。",
            formats = listOf(".csv", ".txt", ".json", ".md"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryBtn(text = "选择文件导入", icon = "import", onClick = {
                    pickTextFiles(listOf("csv", "txt", "json", "md"), multiple = false) { files ->
                        val f = files.firstOrNull() ?: return@pickTextFiles
                        scope.launch {
                            todosStatus = "导入中…"
                            try {
                                val n = withContext(Dispatchers.IO) {
                                    NoteBoxState.api.importTodos(f.name, f.content, "auto")
                                }
                                todosStatus = "成功导入 $n 条待办"
                                NoteBoxState.toast("已导入 $n 条待办", ToastKind.Ok)
                                NoteBoxState.refreshStats()
                            } catch (e: Exception) {
                                todosStatus = "导入失败：" + (e.message ?: "")
                                NoteBoxState.toast(todosStatus, ToastKind.Err)
                            }
                        }
                    }
                })
                StatusText(todosStatus)
            }
        }

        TransferCard(
            icon = "archive",
            title = "恢复完整备份",
            desc = "导入 NoteBox 导出的 .json 备份文件，包含全部笔记和待办。",
            formats = listOf(".json"),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RadioRow(backupMode == "merge", "合并（保留现有数据）") { backupMode = "merge" }
                RadioRow(backupMode == "replace", "覆盖（清空现有笔记和待办）") { backupMode = "replace" }
            }
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryBtn(text = "选择备份恢复", icon = "import", onClick = {
                    pickTextFiles(listOf("json"), multiple = false) { files ->
                        val f = files.firstOrNull() ?: return@pickTextFiles
                        scope.launch {
                            if (backupMode == "replace") {
                                val ok = Modal.confirm(
                                    "覆盖恢复",
                                    "覆盖模式会清空现有全部笔记和待办，确定？",
                                    okText = "覆盖导入", danger = true,
                                )
                                if (!ok) return@launch
                            }
                            backupStatus = "恢复中…"
                            try {
                                val r = withContext(Dispatchers.IO) {
                                    NoteBoxState.api.importBackup(f.content, backupMode)
                                }
                                backupStatus = "恢复完成：笔记 ${r.int("notes")} 条，待办 ${r.int("todos")} 条"
                                NoteBoxState.toast("备份恢复成功", ToastKind.Ok)
                                NoteBoxState.refreshStats()
                            } catch (e: Exception) {
                                backupStatus = "恢复失败：" + (e.message ?: "")
                                NoteBoxState.toast(backupStatus, ToastKind.Err)
                            }
                        }
                    }
                })
                StatusText(backupStatus)
            }
        }

        TransferCard(
            icon = "export",
            title = "导出",
            desc = "随时把数据下载到本地备份。",
            formats = null,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Triple("完整备份 JSON", "/api/export", "notebox-backup.json"),
                    Triple("笔记 Markdown", "/api/export/markdown", "notebox-notes.md"),
                    Triple("待办 CSV", "/api/export/csv", "notebox-todos.csv"),
                ).forEach { (label, path, name) ->
                    GhostBtn(
                        text = if (exporting == path) "下载中…" else label,
                        icon = "fileDown",
                        enabled = exporting == null,
                        onClick = {
                            scope.launch {
                                exporting = path
                                try {
                                    val bytes = withContext(Dispatchers.IO) { NoteBoxState.api.download(path) }
                                    val saved = platformExporter.saveFile(name, bytes)
                                    if (saved != null) NoteBoxState.toast("已保存：$saved", ToastKind.Ok)
                                    else NoteBoxState.toast("保存被取消", ToastKind.Warn)
                                } catch (e: Exception) {
                                    NoteBoxState.toast("导出失败：" + (e.message ?: ""), ToastKind.Err)
                                } finally {
                                    exporting = null
                                }
                            }
                        },
                    )
                }
            }
        }
        }
    }
}

/**
 * 导入/导出专用卡片：图标徽标 + 标题 + 说明 + 格式徽标 + 操作区。
 * 比 PanelCard 多了图标头与格式标签，视觉上更清晰。
 */
@Composable
private fun TransferCard(
    icon: String,
    title: String,
    desc: String,
    formats: List<String>?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Palette.panel)
                .border(1.dp, Palette.border, shape),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp))
                            .background(AccentState.current.primary.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PanelIcon(icon, 16.dp, AccentState.current.secondary)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        title,
                        color = Palette.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(desc, color = Palette.muted, fontSize = 12.sp, lineHeight = 18.sp)
                if (formats != null) {
                    Spacer(Modifier.size(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        formats.forEach { f ->
                            Box(
                                Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(Palette.inset)
                                    .border(1.dp, Palette.border, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.5.dp),
                            ) {
                                Text(
                                    f,
                                    color = Palette.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    if (text.isEmpty()) return
    val color = when {
        text.startsWith("导入中") || text.startsWith("恢复中") -> Palette.muted
        text.contains("失败") -> Palette.bad
        else -> Palette.ok
    }
    Text(text, color = color, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(3.dp)).clickable { onClick() }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp)
                .border(1.4.dp, if (selected) AccentState.current.primary else Palette.border2, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(AccentState.current.primary))
        }
        Spacer(Modifier.size(6.dp))
        Text(label, color = Palette.text, fontSize = 12.sp)
    }
}
