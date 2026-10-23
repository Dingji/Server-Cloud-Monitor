package com.servermaintance.panel.pages.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.FileEntry
import com.servermaintance.panel.core.FileStore
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.SearchHit
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.TrashItem
import com.servermaintance.panel.nowEpochMillis
import com.servermaintance.panel.platformExporter
import com.servermaintance.panel.pickBinaryFiles
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PctBar
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.launch

/**
 * 文件管理器 —— 对标宝塔 / 1Panel 的站点文件操作。
 *
 * 浏览、创建、上传下载、在线编辑、重命名、移动、复制、权限与属主、打包解压、搜索、
 * 删除先进回收站（服务器保留 30 天可还原）。路径校验、find/du/tar 调用与缺命令时的
 * 降级都在服务端 `ops-server/files.js`，这里只展示它返回的可读错误。
 */
@Composable
fun OpsFilesPage() {
    val listing by FileStore.listing.collectAsState()
    val preview by FileStore.preview.collectAsState()
    val editing by FileStore.editing.collectAsState()
    val trash by FileStore.trash.collectAsState()
    val hits by FileStore.hits.collectAsState()
    val usage by FileStore.usage.collectAsState()
    val busy by FileStore.busy.collectAsState()
    val unsupported by FileStore.unsupported.collectAsState()

    var pathInput by remember { mutableStateOf(FileStore.dir.value) }
    var query by remember { mutableStateOf("") }
    var editDraft by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showTrash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { FileStore.open(FileStore.dir.value); FileStore.loadTrash() }
    LaunchedEffect(editing, preview) { if (editing != null) editDraft = preview?.text.orEmpty() }

    fun toast(err: String?, okMsg: String) {
        if (err == null) AppState.toast(okMsg, ToastKind.Ok) else AppState.toast(err, ToastKind.Err, ms = 7000)
    }

    fun go(path: String) {
        selected = emptySet()
        pathInput = path
        scope.launch { FileStore.open(path) }
    }

    PageScaffold(
        title = "文件管理",
        desc = "服务器文件系统操作：浏览、编辑、上传下载、权限、打包、回收站（删除保留 30 天）",
        actions = {
            GhostBtn(text = "上级", onClick = {
                selected = emptySet()
                scope.launch { FileStore.up(1); pathInput = FileStore.dir.value }
            }, icon = "arrowLeft")
            Spacer(Modifier.size(6.dp))
            GhostBtn(text = "刷新", onClick = { scope.launch { FileStore.refresh() } }, icon = "refresh")
            Spacer(Modifier.size(6.dp))
            GhostBtn(text = "回收站${if (trash.isEmpty()) "" else " ${trash.size}"}", onClick = { showTrash = !showTrash }, icon = "trash")
            Spacer(Modifier.size(6.dp))
            GhostBtn(text = "上传", onClick = {
                pickBinaryFiles(emptyList(), true) { picked ->
                    if (picked.isEmpty()) return@pickBinaryFiles
                    scope.launch {
                        picked.forEach { f ->
                            val err = FileStore.upload(f.name, f.bytes)
                            if (err != null && err.contains("已存在")) {
                                if (Modal.confirm("同名文件已存在", "${f.name}：覆盖？", okText = "覆盖", danger = true)) {
                                    toast(FileStore.upload(f.name, f.bytes, overwrite = true), "已上传并覆盖 ${f.name}")
                                }
                            } else {
                                toast(err, "已上传 ${f.name}")
                            }
                        }
                    }
                }
            }, icon = "upload")
            Spacer(Modifier.size(6.dp))
            PrimaryBtn(text = "新建目录", onClick = {
                scope.launch {
                    val f = Modal.prompt("在 ${listing?.dir ?: ""} 下新建目录", listOf(FormField.Text("name", "目录名称")), okText = "创建") ?: return@launch
                    toast(FileStore.mkdir(f["name"].orEmpty()), "目录已创建")
                }
            }, icon = "plus")
            GhostBtn(text = "新建文件", onClick = {
                scope.launch {
                    val f = Modal.prompt("在 ${listing?.dir ?: ""} 下新建文件", listOf(FormField.Text("name", "文件名", hint = "如 index.html")), okText = "创建") ?: return@launch
                    val name = f["name"].orEmpty()
                    val err = FileStore.createFile(name)
                    toast(err, "文件已创建")
                }
            }, icon = "fileDown")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        val l = listing
        if (l == null) {
            LoadingState("读取目录…")
            return@PageScaffold
        }

        PanelCard("位置", cardKey = "files-loc", collapsible = false) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                l.breadcrumb.forEachIndexed { i, crumb ->
                    if (i > 0) Text(text = "/", color = Palette.muted, fontSize = 12.sp)
                    GhostBtn(text = crumb.label, onClick = { go(crumb.path) })
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = "跳转路径",
                    hint = "绝对路径，如 /var/www/html",
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                GhostBtn(text = "前往", onClick = { go(pathInput.trim()) }, icon = "external")
                GhostBtn(text = "占用分析", onClick = { scope.launch { FileStore.loadUsage(l.dir) } }, icon = "chart")
            }
            Spacer(Modifier.size(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = "常用：", color = Palette.muted, fontSize = 11.5.sp)
                l.bookmarks.forEach { b ->
                    GhostBtn(text = b.label, onClick = { go(b.path) }, icon = "folder")
                }
            }
            l.df?.let { d ->
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = "文件系统 ${d.filesystem}", color = Palette.muted, fontSize = 11.5.sp)
                    Text(text = "已用 ${d.pct}", color = Palette.text, fontSize = 11.5.sp)
                    Text(text = "剩余 ${Fmt.bytes(d.availKb * 1024)} / 共 ${Fmt.bytes(d.totalKb * 1024)}", color = Palette.muted, fontSize = 11.5.sp)
                }
                Spacer(Modifier.size(4.dp))
                PctBar(pct = d.pct.replace("%", "").toDoubleOrNull() ?: 0.0, height = 5.dp, showLabel = false)
            }
        }

        if (busy) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LevelBadge("操作进行中", Level.Info)
            }
        }

        if (selected.isNotEmpty()) {
            PanelCard("已选 ${selected.size} 项", cardKey = "files-bulk", collapsible = false) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GhostBtn(text = "移动到…", onClick = {
                        scope.launch {
                            val f = Modal.prompt("移动 ${selected.size} 项", listOf(FormField.Text("to", "目标目录（绝对路径）", value = l.dir, mono = true)), okText = "移动") ?: return@launch
                            toast(FileStore.move(selected.toList(), f["to"].orEmpty()), "已移动")
                            selected = emptySet()
                        }
                    }, icon = "arrowRight")
                    GhostBtn(text = "复制到…", onClick = {
                        scope.launch {
                            val f = Modal.prompt("复制 ${selected.size} 项", listOf(FormField.Text("to", "目标目录（绝对路径）", value = l.dir, mono = true)), okText = "复制") ?: return@launch
                            toast(FileStore.copy(selected.toList(), f["to"].orEmpty()), "已复制")
                            selected = emptySet()
                        }
                    }, icon = "copy")
                    GhostBtn(text = "打包 tar.gz", onClick = {
                        scope.launch {
                            val stamp = Fmt.dateTime(nowEpochMillis()).substringBefore(' ').replace("-", "")
                            val f = Modal.prompt("打包 ${selected.size} 项到 ${l.dir}", listOf(
                                FormField.Text("name", "归档文件名", value = "backup-$stamp.tar.gz", mono = true),
                            ), okText = "开始打包") ?: return@launch
                            toast(FileStore.archive(f["name"].orEmpty(), selected.toList()), "打包完成")
                            selected = emptySet()
                        }
                    }, icon = "archive")
                    GhostBtn(text = "改权限", onClick = {
                        scope.launch {
                            val f = Modal.prompt("权限（八进制）", listOf(
                                FormField.Text("mode", "如 755 / 644", value = "644", mono = true),
                                FormField.Select("rec", "范围", options = listOf("仅所选", "递归子项"), value = "仅所选"),
                            ), okText = "应用") ?: return@launch
                            toast(FileStore.chmod(selected.toList(), f["mode"].orEmpty(), f["rec"] == "递归子项"), "权限已更新")
                        }
                    }, icon = "lock")
                    GhostBtn(text = "改属主", onClick = {
                        scope.launch {
                            val f = Modal.prompt("属主", listOf(
                                FormField.Text("owner", "user 或 user:group", value = "www-data:www-data", mono = true),
                                FormField.Select("rec", "范围", options = listOf("仅所选", "递归子项"), value = "仅所选"),
                            ), okText = "应用") ?: return@launch
                            toast(FileStore.chown(selected.toList(), f["owner"].orEmpty(), f["rec"] == "递归子项"), "属主已更新")
                        }
                    }, icon = "user")
                    GhostBtn(text = "移入回收站", onClick = {
                        scope.launch {
                            if (Modal.confirm("删除到回收站", "把 ${selected.size} 项移入回收站（保留 ${FileStore.trashDays.value} 天，可还原）。", okText = "移入回收站", danger = true)) {
                                toast(FileStore.delete(selected.toList()), "已移入回收站")
                                selected = emptySet()
                            }
                        }
                    }, icon = "trash")
                    GhostBtn(text = "取消选择", onClick = { selected = emptySet() }, icon = "close")
                }
            }
        }

        editing?.let { path ->
            PanelCard("在线编辑 · ${path.substringAfterLast('/')}", cardKey = "files-edit", collapsible = false) {
                Text(text = path, color = Palette.muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.size(6.dp))
                Field(value = editDraft, onValueChange = { editDraft = it }, mono = true, singleLine = false, hint = "共 ${editDraft.length} 字符")
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryBtn(text = "保存", onClick = {
                        scope.launch { toast(FileStore.saveEdit(path, editDraft), "已保存") }
                    }, icon = "save")
                    GhostBtn(text = "放弃编辑", onClick = { FileStore.editing.value = null }, icon = "close")
                    GhostBtn(text = "重新读取", onClick = { scope.launch { FileStore.previewFile(path) } }, icon = "refresh")
                }
            }
        }

        preview?.let { p -> if (editing == null) {
            PanelCard("预览 · ${p.path}", cardKey = "files-preview", right = {
                GhostBtn(text = "关闭", onClick = { FileStore.preview.value = null }, icon = "close")
                if (!p.binary && !p.tooLarge) {
                    GhostBtn(text = "编辑此文件", onClick = {
                        scope.launch {
                            val err = FileStore.startEdit(p.path)
                            if (err != null) AppState.toast(err, ToastKind.Warn, ms = 7000)
                        }
                    }, icon = "edit")
                }
            }) {
                when {
                    p.binary -> Text(text = "二进制文件（${Fmt.bytes(p.size)}），不做预览；可下载后用本地编辑器打开。", color = Palette.muted, fontSize = 12.sp)
                    p.tooLarge -> Text(text = "文件 ${Fmt.bytes(p.size)} 超过预览上限 ${Fmt.bytes(p.limit)}，请下载或用 Web 终端查看。", color = Palette.warn, fontSize = 12.sp)
                    else -> {
                        Column(
                            Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState())
                                .background(Palette.codeBlock, RoundedCornerShape(3.dp)).padding(10.dp),
                        ) {
                            Text(
                                text = p.text.take(20000) + if (p.truncated) "…（预览截断，共 ${Fmt.bytes(p.size)}）" else "",
                                color = Palette.text,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(text = "${Fmt.dateTime(p.mtimeMs)} · ${p.lines} 行 · 扩展名 ${p.ext.ifBlank { "无" }}", color = Palette.muted, fontSize = 11.sp)
                    }
                }
            }
        } }

        if (showTrash) {
            PanelCard("回收站（保留 ${FileStore.trashDays.value} 天）", cardKey = "files-trash", right = {
                GhostBtn(text = "清空", onClick = {
                    scope.launch {
                        if (Modal.confirm("清空回收站", "彻底删除 ${trash.size} 项，无法恢复。", okText = "清空", danger = true)) {
                            toast(FileStore.emptyTrash(), "回收站已清空")
                        }
                    }
                }, icon = "warning")
            }) {
                if (trash.isEmpty()) EmptyState("回收站是空的", icon = "trash")
                else DataTable(
                    columns = listOf(
                        TableCol<TrashItem>("名称", weight = 1.6f) { TableText(it.name) },
                        TableCol<TrashItem>("原位置", weight = 2.4f, mono = true) { TableText(it.original, mono = true, color = Palette.muted) },
                        TableCol<TrashItem>("大小", weight = 0.9f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.size), textAlign = TextAlign.End, mono = true) },
                        TableCol<TrashItem>("删除时间", weight = 1.3f, mono = true) { TableText(Fmt.dateTime(it.at), mono = true) },
                        TableCol<TrashItem>("操作", weight = 1.7f) { t ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = "还原", onClick = {
                                    scope.launch { toast(FileStore.restore(listOf(t.id)), "已还原到原位置") }
                                }, icon = "import")
                                GhostBtn(text = "彻底删除", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("彻底删除", "${t.name} 将无法恢复。", okText = "删除", danger = true)) {
                                            toast(FileStore.purgeTrash(listOf(t.id)), "已彻底删除")
                                        }
                                    }
                                }, icon = "trash")
                            }
                        },
                    ),
                    rows = trash,
                    rowKey = { it.id },
                    maxRows = 10,
                )
            }
        }

        if (hits.isNotEmpty()) {
            PanelCard("搜索结果 ·「$query」 ${hits.size} 项", cardKey = "files-hits", right = {
                GhostBtn(text = "清除", onClick = { FileStore.hits.value = emptyList() }, icon = "close")
            }) {
                DataTable(
                    columns = listOf(
                        TableCol<SearchHit>("路径", weight = 3f, mono = true) { TableText(it.path, mono = true) },
                        TableCol<SearchHit>("类型", weight = 0.7f) { TableText(it.type) },
                        TableCol<SearchHit>("大小", weight = 0.9f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.size), textAlign = TextAlign.End, mono = true) },
                        TableCol<SearchHit>("修改", weight = 1.2f, mono = true) { TableText(it.mtimeText, mono = true, color = Palette.muted) },
                        TableCol<SearchHit>("操作", weight = 1.4f) { h ->
                            GhostBtn(text = "打开所在目录", onClick = {
                                go(h.path.substringBeforeLast('/', "/").ifBlank { "/" })
                            }, icon = "folder")
                        },
                    ),
                    rows = hits,
                    rowKey = { it.path },
                    maxRows = 25,
                )
            }
        }

        if (usage.isNotEmpty()) {
            PanelCard("目录占用 · ${FileStore.usageFor.value}", cardKey = "files-usage") {
                val topKb = (usage.maxOfOrNull { it.kb } ?: 1L).coerceAtLeast(1L).toDouble()
                usage.forEach { u ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        TableText(text = u.path.substringAfterLast('/').ifBlank { u.path }, modifier = Modifier.padding(end = 8.dp))
                        PctBar(pct = u.kb / topKb * 100, modifier = Modifier.weight(1f), height = 5.dp, showLabel = false)
                        TableText(text = Fmt.bytes(u.kb * 1024), textAlign = TextAlign.End, mono = true, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        PanelCard(
            title = "${l.dir}（${l.count} 项 · ${if (l.usedShell) "服务端 find" else "本地扫描"}）",
            cardKey = "files-list",
            collapsible = false,
            right = {
                GhostBtn(text = "全选", onClick = { selected = l.entries.map { it.path }.toSet() }, icon = "checkSquare")
                GhostBtn(text = "只选目录", onClick = { selected = l.entries.filter { it.isDir }.map { it.path }.toSet() }, icon = "folder")
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    value = query,
                    onValueChange = { query = it },
                    hint = "在本目录内按名称搜索（深度 4）",
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                PrimaryBtn(text = "搜索", onClick = { scope.launch { FileStore.search(query) } }, enabled = query.isNotBlank(), icon = "search")
            }
            Spacer(Modifier.size(8.dp))
            if (l.entries.isEmpty()) {
                EmptyState("空目录", icon = "folder")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<FileEntry>("选", weight = 0.4f, align = TextAlign.Center) { e ->
                            Toggle(
                                checked = e.path in selected,
                                onCheckedChange = { on -> selected = if (on) selected + e.path else selected - e.path },
                            )
                        },
                        TableCol<FileEntry>("名称", weight = 2.0f) { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PanelIcon(if (e.isDir) "folder" else "fileDown", 13.dp, if (e.isDir) Palette.info else Palette.muted)
                                Spacer(Modifier.size(6.dp))
                                Column {
                                    TableText(e.name)
                                    if (e.isLink && e.linkTarget.isNotBlank()) TableText("→ ${e.linkTarget}", color = Palette.muted, mono = true)
                                }
                            }
                        },
                        TableCol<FileEntry>("类型", weight = 0.7f) { e -> TableText(e.typeLabel, color = if (e.isDir) Palette.info else Palette.muted) },
                        TableCol<FileEntry>("大小", weight = 0.9f, align = TextAlign.End, mono = true) { e ->
                            TableText(if (e.isDir) (if (e.entryCount > 0) "${e.entryCount} 项" else "—") else Fmt.bytes(e.size), textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<FileEntry>("权限", weight = 1.1f, mono = true) { e -> TableText(e.symbolic.ifBlank { e.mode }, mono = true) },
                        TableCol<FileEntry>("属主", weight = 1.0f, mono = true) { e ->
                            TableText(
                                listOf(e.owner, e.group).filter(String::isNotBlank).joinToString(":")
                                    .ifBlank { listOf(e.uid, e.gid).filter(String::isNotBlank).joinToString(":").ifBlank { "—" } },
                                mono = true,
                            )
                        },
                        TableCol<FileEntry>("修改时间", weight = 1.4f, mono = true) { e ->
                            TableText(if (e.mtimeMs > 0) Fmt.dateTime(e.mtimeMs) else "—", mono = true, color = Palette.muted)
                        },
                        TableCol<FileEntry>("操作", weight = 2.8f) { e ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (e.isDir) {
                                    GhostBtn(text = "进入", onClick = { go(e.path) }, icon = "chevronRight")
                                } else {
                                    GhostBtn(text = "预览", onClick = { scope.launch { FileStore.previewFile(e.path) } }, icon = "eye")
                                    GhostBtn(text = "编辑", onClick = {
                                        scope.launch {
                                            val err = FileStore.startEdit(e.path)
                                            if (err != null) AppState.toast(err, ToastKind.Warn, ms = 7000)
                                        }
                                    }, icon = "edit")
                                    GhostBtn(text = "下载", onClick = {
                                        scope.launch {
                                            val (bytes, err) = FileStore.download(e.path)
                                            if (err != null) {
                                                AppState.toast(err, ToastKind.Err, ms = 7000)
                                                return@launch
                                            }
                                            val saved = platformExporter.saveFile(e.name, bytes ?: ByteArray(0))
                                            AppState.toast(if (saved == null) "已取消保存" else "已保存：$saved", ToastKind.Ok)
                                        }
                                    }, icon = "download")
                                    if (e.name.endsWith(".tar.gz") || e.name.endsWith(".tgz") || e.name.endsWith(".zip")) {
                                        GhostBtn(text = "解压", onClick = {
                                            scope.launch { toast(FileStore.extract(e.path, l.dir), "已解压到 ${l.dir}") }
                                        }, icon = "box")
                                    }
                                }
                                GhostBtn(text = "重命名", onClick = {
                                    scope.launch {
                                        val f = Modal.prompt("重命名", listOf(FormField.Text("name", "新名称", value = e.name)), okText = "确定") ?: return@launch
                                        toast(FileStore.rename(e.path, f["name"].orEmpty()), "已重命名")
                                    }
                                }, icon = "edit")
                                GhostBtn(text = "删", onClick = {
                                    scope.launch {
                                        if (Modal.confirm("移入回收站", "「${e.name}」将进入回收站，保留 ${FileStore.trashDays.value} 天。", okText = "移入回收站", danger = true)) {
                                            toast(FileStore.delete(listOf(e.path)), "已移入回收站")
                                        }
                                    }
                                }, icon = "trash")
                            }
                        },
                    ),
                    rows = l.entries,
                    rowKey = { it.path },
                    onRowClick = { e ->
                        if (e.isDir) go(e.path) else scope.launch { FileStore.previewFile(e.path) }
                    },
                    maxRows = 200,
                )
            }
        }

        PanelCard("边界说明", cardKey = "files-notes", collapsible = false) {
            Text(
                text = "允许范围由服务器的 CM_FILES_ROOTS 决定（默认整个文件系统）；面板本来就有 Web 终端（等同 root shell），" +
                    "文件管理器不额外扩权。所有写操作都进面板审计日志（ops.files.*），删除先进回收站、30 天内可还原。",
                color = Palette.muted,
                fontSize = 11.5.sp,
            )
        }
    }
}
