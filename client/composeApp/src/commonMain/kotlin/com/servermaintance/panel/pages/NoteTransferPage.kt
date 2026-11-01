package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
        title = L["导入_导出_995e254175"],
        desc = L["notebox_数据迁移_362725f18e"],
        actions = { NoteUserChip() },
    ) {
        GridLayout(columns = 2, spacing = 12.dp) {

        TransferCard(
            icon = "note",
            title = L["导入笔记_ef0dc85571"],
            desc = L["支持_markdown_纯文本_html_305bf19136"],
            formats = listOf(L["md_a2c73572c9"], L["markdown_710c683239"], L["txt_39cb42ed04"], L["html_4af551145f"], L["htm_93358b0bb6"]),
        ) {
            CheckRow(split, { split = it }, L["按一级标题拆分_cffaaceb8e"])
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryBtn(text = L["选择文件导入_e25b7bbd7f"], icon = "import", onClick = {
                    pickTextFiles(listOf("md", "markdown", "txt", "text", "html", "htm"), multiple = true) { files ->
                        if (files.isEmpty()) return@pickTextFiles
                        scope.launch {
                            notesStatus = L["导入中_de56a0bf71"]
                            try {
                                val n = withContext(Dispatchers.IO) {
                                    NoteBoxState.api.importNotes(files.map { it.name to it.content }, split)
                                }
                                notesStatus = trf("成功导入_0_条笔记_f1edf5b407", n)
                                NoteBoxState.toast(tr("已导入_n_条笔记_09e6db7128"), ToastKind.Ok)
                                NoteBoxState.refreshStats()
                            } catch (e: Exception) {
                                notesStatus = L["导入失败_aeec838ca4"] + (e.message ?: "")
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
            title = L["导入待办_1a771a17b3"],
            desc = L["支持_csv_有_无表头均可_列名支持_标题_截_564b0aa879"],
            formats = listOf(L["csv_896e10d3ef"], L["txt_39cb42ed04"], L["json_2568884586"], L["md_a2c73572c9"]),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryBtn(text = L["选择文件导入_e25b7bbd7f"], icon = "import", onClick = {
                    pickTextFiles(listOf("csv", "txt", "json", "md"), multiple = false) { files ->
                        val f = files.firstOrNull() ?: return@pickTextFiles
                        scope.launch {
                            todosStatus = L["导入中_de56a0bf71"]
                            try {
                                val n = withContext(Dispatchers.IO) {
                                    NoteBoxState.api.importTodos(f.name, f.content, "auto")
                                }
                                todosStatus = trf("成功导入_0_条待办_0f7f47eb57", n)
                                NoteBoxState.toast(tr("已导入_n_条待办_c3352cbaa8"), ToastKind.Ok)
                                NoteBoxState.refreshStats()
                            } catch (e: Exception) {
                                todosStatus = L["导入失败_aeec838ca4"] + (e.message ?: "")
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
            title = L["恢复完整备份_640eea92dc"],
            desc = L["导入_notebox_导出的_json_备份文_9e7560aff1"],
            formats = listOf(L["json_2568884586"]),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RadioRow(backupMode == "merge", L["合并_保留现有数据_01a4d7c1d8"]) { backupMode = "merge" }
                RadioRow(backupMode == "replace", L["覆盖_清空现有笔记和待办_dc20dbc52a"]) { backupMode = "replace" }
            }
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryBtn(text = L["选择备份恢复_da323f9941"], icon = "import", onClick = {
                    pickTextFiles(listOf("json"), multiple = false) { files ->
                        val f = files.firstOrNull() ?: return@pickTextFiles
                        scope.launch {
                            if (backupMode == "replace") {
                                val ok = Modal.confirm(
                                    L["覆盖恢复_83c574b3b0"],
                                    L["覆盖模式会清空现有全部笔记和待办_确定_70547ed81c"],
                                    okText = L["覆盖导入_62808bb73a"], danger = true,
                                )
                                if (!ok) return@launch
                            }
                            backupStatus = L["恢复中_176238056c"]
                            try {
                                val r = withContext(Dispatchers.IO) {
                                    NoteBoxState.api.importBackup(f.content, backupMode)
                                }
                                backupStatus = trf("恢复完成_笔记_0_条_待办_1_条_7aa19232cf", r.int("notes"), r.int("todos"))
                                NoteBoxState.toast(tr("备份恢复成功_01abfe5b6c"), ToastKind.Ok)
                                NoteBoxState.refreshStats()
                            } catch (e: Exception) {
                                backupStatus = L["恢复失败_08c8ed5980"] + (e.message ?: "")
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
            title = L["导出_188896795f"],
            desc = L["随时把数据下载到本地备份_8e95a3fbc8"],
            formats = null,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Triple(L["完整备份_json_cbe231e819"], "/api/export", "notebox-backup.json"),
                    Triple(L["笔记_markdown_f0f9aad2ff"], "/api/export/markdown", "notebox-notes.md"),
                    Triple(L["待办_csv_3308e95082"], "/api/export/csv", "notebox-todos.csv"),
                ).forEach { (label, path, name) ->
                    GhostBtn(
                        text = if (exporting == path) L["下载中_65188d08a2"] else label,
                        icon = "fileDown",
                        enabled = exporting == null,
                        onClick = {
                            scope.launch {
                                exporting = path
                                try {
                                    val bytes = withContext(Dispatchers.IO) { NoteBoxState.api.download(path) }
                                    val saved = platformExporter.saveFile(name, bytes)
                                    if (saved != null) NoteBoxState.toast(tr("已保存_saved_36d870566a"), ToastKind.Ok)
                                    else NoteBoxState.toast(tr("保存被取消_82f61f25b1"), ToastKind.Warn)
                                } catch (e: Exception) {
                                    NoteBoxState.toast(tr("导出失败_38ab583870") + (e.message ?: ""), ToastKind.Err)
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
        text == L["导入中_de56a0bf71"] || text == L["恢复中_176238056c"] -> Palette.muted
        com.servermaintance.panel.i18n.textHasI18nKey(text, "失败_3e3c8068bb") -> Palette.bad
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
