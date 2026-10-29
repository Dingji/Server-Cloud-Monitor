package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.servermaintance.panel.core.Transfer
import com.servermaintance.panel.core.Transfers
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
        title = L["文件管理_3f2244c98f"],
        desc = L["服务器文件系统操作_浏览_编辑_上传下载_权限_66f94fd459"],
        actions = {
            GhostBtn(text = L["上级_205300782e"], onClick = {
                selected = emptySet()
                scope.launch { FileStore.up(1); pathInput = FileStore.dir.value }
            }, icon = "arrowLeft")
            Spacer(Modifier.size(6.dp))
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { FileStore.refresh() } }, icon = "refresh")
            Spacer(Modifier.size(6.dp))
            GhostBtn(text = trf("回收站_0_c62b3efd3d", if (trash.isEmpty()) "" else " (${trash.size})"), onClick = { showTrash = !showTrash }, icon = "trash")
            Spacer(Modifier.size(6.dp))
            GhostBtn(text = L["上传_a977650db1"], onClick = {
                pickBinaryFiles(emptyList(), true) { picked ->
                    if (picked.isEmpty()) return@pickBinaryFiles
                    scope.launch {
                        picked.forEach { f ->
                            val err = FileStore.upload(f.name, f.bytes)
                            if (err != null && com.servermaintance.panel.i18n.textHasI18nKey(err, "已存在_a867d42ddf")) {
                                if (Modal.confirm(L["同名文件已存在_19c091cec7"], trf("0_覆盖_ba416bc36f", f.name), okText = L["覆盖_a26056298a"], danger = true)) {
                                    toast(FileStore.upload(f.name, f.bytes, overwrite = true), trf("已上传并覆盖_0_56609744b2", f.name))
                                }
                            } else {
                                toast(err, trf("已上传_0_62e7aadbe5", f.name))
                            }
                        }
                    }
                }
            }, icon = "upload")
            Spacer(Modifier.size(6.dp))
            PrimaryBtn(text = L["新建目录_80e05af6a0"], onClick = {
                scope.launch {
                    val f = Modal.prompt(trf("在_0_下新建目录_fcaf22904d", listing?.dir ?: ""), listOf(FormField.Text("name", L["目录名称_b4c0418914"])), okText = L["创建_fcbd093292"]) ?: return@launch
                    toast(FileStore.mkdir(f["name"].orEmpty()), L["目录已创建_bec7e97f72"])
                }
            }, icon = "plus")
            GhostBtn(text = L["新建文件_d4526eb925"], onClick = {
                scope.launch {
                    val f = Modal.prompt(trf("在_0_下新建文件_cfb4d5dd9f", listing?.dir ?: ""), listOf(FormField.Text("name", L["文件名_1275f6feb7"], hint = L["如_index_html_e76a6ff3ae"])), okText = L["创建_fcbd093292"]) ?: return@launch
                    val name = f["name"].orEmpty()
                    val err = FileStore.createFile(name)
                    toast(err, L["文件已创建_3e2240f609"])
                }
            }, icon = "fileDown")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        /* 传输队列放在目录读取状态之前：正在下载大文件时列表可能正在刷新，
           进度条不能被 loading 分支挡掉 */
        TransfersCard()

        val l = listing
        if (l == null) {
            LoadingState(L["读取目录_5bfbf3e6d6"])
            return@PageScaffold
        }

        PanelCard(title = L["位置_88c34452cc"], cardKey = "files-loc", collapsible = false) {
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
                    label = L["跳转路径_f881d3d381"],
                    hint = L["绝对路径_如_var_www_html_a7724c2089"],
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                GhostBtn(text = L["前往_23926d6146"], onClick = { go(pathInput.trim()) }, icon = "external")
                GhostBtn(text = L["占用分析_003851e93c"], onClick = { scope.launch { FileStore.loadUsage(l.dir) } }, icon = "chart")
            }
            Spacer(Modifier.size(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = L["常用_ee2804c36b"], color = Palette.muted, fontSize = 11.5.sp)
                l.bookmarks.forEach { b ->
                    GhostBtn(text = b.label, onClick = { go(b.path) }, icon = "folder")
                }
            }
            l.df?.let { d ->
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = trf("文件系统_0_0938484ae2", d.filesystem), color = Palette.muted, fontSize = 11.5.sp)
                    Text(text = trf("已用_0_da8610464a", d.pct), color = Palette.text, fontSize = 11.5.sp)
                    Text(text = trf("剩余_0_共_1_a04477cc45", Fmt.bytes(d.availKb * 1024), Fmt.bytes(d.totalKb * 1024)), color = Palette.muted, fontSize = 11.5.sp)
                }
                Spacer(Modifier.size(4.dp))
                PctBar(pct = d.pct.replace("%", "").toDoubleOrNull() ?: 0.0, height = 5.dp, showLabel = false)
            }
        }

        if (busy) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(L["操作进行中_a31331bbf2"], Level.Info)
            }
        }

        if (selected.isNotEmpty()) {
            PanelCard(title = trf("已选_0_项_1a922f0ed8", selected.size), cardKey = "files-bulk", collapsible = false) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GhostBtn(text = L["移动到_60f0d6d5b6"], onClick = {
                        scope.launch {
                            val f = Modal.prompt(trf("移动_0_项_22ff772325", selected.size), listOf(FormField.Text("to", L["目标目录_绝对路径_029159ba90"], value = l.dir, mono = true)), okText = L["移动_591f3aa55f"]) ?: return@launch
                            toast(FileStore.move(selected.toList(), f["to"].orEmpty()), L["已移动_3bf6e46e2d"])
                            selected = emptySet()
                        }
                    }, icon = "arrowRight")
                    GhostBtn(text = L["复制到_33771a2df3"], onClick = {
                        scope.launch {
                            val f = Modal.prompt(trf("复制_0_项_24fd395315", selected.size), listOf(FormField.Text("to", L["目标目录_绝对路径_029159ba90"], value = l.dir, mono = true)), okText = L["复制_4edd1d0087"]) ?: return@launch
                            toast(FileStore.copy(selected.toList(), f["to"].orEmpty()), L["已复制_e381a5763d"])
                            selected = emptySet()
                        }
                    }, icon = "copy")
                    GhostBtn(text = L["打包_tar_gz_ff1dd64457"], onClick = {
                        scope.launch {
                            val stamp = Fmt.dateTime(nowEpochMillis()).substringBefore(' ').replace("-", "")
                            val f = Modal.prompt(trf("打包_0_项到_1_29836a27f5", selected.size, l.dir), listOf(
                                FormField.Text("name", L["归档文件名_2a8d06976b"], value = L["backup_stamp_tar_gz_54d7148040"], mono = true),
                            ), okText = L["开始打包_ec3abb06f3"]) ?: return@launch
                            toast(FileStore.archive(f["name"].orEmpty(), selected.toList()), L["打包完成_be69db469a"])
                            selected = emptySet()
                        }
                    }, icon = "archive")
                    GhostBtn(text = L["改权限_6d524368de"], onClick = {
                        scope.launch {
                            val f = Modal.prompt(L["权限_八进制_2b99f80c15"], listOf(
                                FormField.Text("mode", L["如_755_644_13c07b9c99"], value = "644", mono = true),
                                FormField.Select("rec", L["范围_785b52eb97"], options = listOf(L["仅所选_d662746763"], L["递归子项_80fb31d52a"]), value = L["仅所选_d662746763"]),
                            ), okText = L["应用_4562024dde"]) ?: return@launch
                            toast(FileStore.chmod(selected.toList(), f["mode"].orEmpty(), f["rec"] == L["递归子项_80fb31d52a"]), L["权限已更新_c799b75cb4"])
                        }
                    }, icon = "lock")
                    GhostBtn(text = L["改属主_962ff54eef"], onClick = {
                        scope.launch {
                            val f = Modal.prompt(L["属主_444fd7bd13"], listOf(
                                FormField.Text("owner", L["user_或_user_group_dab37bbd5c"], value = L["www_data_www_data_e9574ab5c8"], mono = true),
                                FormField.Select("rec", L["范围_785b52eb97"], options = listOf(L["仅所选_d662746763"], L["递归子项_80fb31d52a"]), value = L["仅所选_d662746763"]),
                            ), okText = L["应用_4562024dde"]) ?: return@launch
                            toast(FileStore.chown(selected.toList(), f["owner"].orEmpty(), f["rec"] == L["递归子项_80fb31d52a"]), L["属主已更新_dce00bf89c"])
                        }
                    }, icon = "user")
                    GhostBtn(text = L["移入回收站_298cbe31d3"], onClick = {
                        scope.launch {
                            if (Modal.confirm(L["删除到回收站_f418fcf1e6"], trf("把_0_项移入回收站_保留_1_天_可还_34b70d7414", selected.size, FileStore.trashDays.value), okText = L["移入回收站_298cbe31d3"], danger = true)) {
                                toast(FileStore.delete(selected.toList()), L["已移入回收站_54e2ac947c"])
                                selected = emptySet()
                            }
                        }
                    }, icon = "trash")
                    GhostBtn(text = L["取消选择_f02e943954"], onClick = { selected = emptySet() }, icon = "close")
                }
            }
        }

        editing?.let { path ->
            PanelCard(title = trf("在线编辑_0_60ab9fcf96", path.substringAfterLast('/')), cardKey = "files-edit", collapsible = false) {
                Text(text = path, color = Palette.muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.size(6.dp))
                Field(value = editDraft, onValueChange = { editDraft = it }, mono = true, singleLine = false, hint = trf("共_0_字符_f3f4105419", editDraft.length))
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryBtn(text = L["保存_fadf24dbc5"], onClick = {
                        scope.launch { toast(FileStore.saveEdit(path, editDraft), L["已保存_cdfab96f75"]) }
                    }, icon = "save")
                    GhostBtn(text = L["放弃编辑_815725bb66"], onClick = { FileStore.editing.value = null }, icon = "close")
                    GhostBtn(text = L["重新读取_7784972fc8"], onClick = { scope.launch { FileStore.previewFile(path) } }, icon = "refresh")
                }
            }
        }

        preview?.let { p -> if (editing == null) {
            PanelCard(title = trf("预览_0_322425d855", p.path), cardKey = "files-preview", right = {
                GhostBtn(text = L["关闭_6c14bd7f6f"], onClick = { FileStore.preview.value = null }, icon = "close")
                if (!p.binary && !p.tooLarge) {
                    GhostBtn(text = L["编辑此文件_17388d0bda"], onClick = {
                        scope.launch {
                            val err = FileStore.startEdit(p.path)
                            if (err != null) AppState.toast(err, ToastKind.Warn, ms = 7000)
                        }
                    }, icon = "edit")
                }
            }) {
                when {
                    p.binary -> Text(text = trf("二进制文件_0_不做预览_可下载后用本地编_f52fe7aaa7", Fmt.bytes(p.size)), color = Palette.muted, fontSize = 12.sp)
                    p.tooLarge -> Text(text = trf("文件_0_超过预览上限_1_请下载或用_62cd65cbf5", Fmt.bytes(p.size), Fmt.bytes(p.limit)), color = Palette.warn, fontSize = 12.sp)
                    else -> {
                        Column(
                            Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState())
                                .background(Palette.codeBlock, RoundedCornerShape(3.dp)).padding(10.dp),
                        ) {
                            Text(
                                text = p.text.take(20000) + if (p.truncated) trf("预览截断_共_0_5ac60ccca7", Fmt.bytes(p.size)) else "",
                                color = Palette.text,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(text = trf("0_1_行_扩展名_2_fca39b9bd8", Fmt.dateTime(p.mtimeMs), p.lines, p.ext.ifBlank { L["无_72077749f7"] }), color = Palette.muted, fontSize = 11.sp)
                    }
                }
            }
        } }

        if (showTrash) {
            PanelCard(title = trf("回收站_保留_0_天_38b488832e", FileStore.trashDays.value), cardKey = "files-trash", right = {
                GhostBtn(text = L["清空_84fcd70d42"], onClick = {
                    scope.launch {
                        if (Modal.confirm(L["清空回收站_4833ca01fc"], trf("彻底删除_0_项_无法恢复_9f56fcff22", trash.size), okText = L["清空_84fcd70d42"], danger = true)) {
                            toast(FileStore.emptyTrash(), L["回收站已清空_226ab2d716"])
                        }
                    }
                }, icon = "warning")
            }) {
                if (trash.isEmpty()) EmptyState(L["回收站是空的_e872522628"], icon = "trash")
                else DataTable(
                    columns = listOf(
                        TableCol<TrashItem>(L["名称_1be7ae4fc2"], weight = 1.6f) { TableText(it.name) },
                        TableCol<TrashItem>(L["原位置_00e56f7a13"], weight = 2.4f, mono = true) { TableText(it.original, mono = true, color = Palette.muted) },
                        TableCol<TrashItem>(L["大小_fd20702c73"], weight = 0.9f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.size), textAlign = TextAlign.End, mono = true) },
                        TableCol<TrashItem>(L["删除时间_8d55c8d894"], weight = 1.3f, mono = true) { TableText(Fmt.dateTime(it.at), mono = true) },
                        TableCol<TrashItem>(L["操作_f3ea6d345e"], weight = 1.7f) { t ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GhostBtn(text = L["还原_457d449ce1"], onClick = {
                                    scope.launch { toast(FileStore.restore(listOf(t.id)), L["已还原到原位置_88f9ffd058"]) }
                                }, icon = "import")
                                GhostBtn(text = L["彻底删除_8c0b37b684"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["彻底删除_8c0b37b684"], trf("0_将无法恢复_cf526ad9a4", t.name), okText = L["删除_3755f56f2f"], danger = true)) {
                                            toast(FileStore.purgeTrash(listOf(t.id)), L["已彻底删除_21ae7be063"])
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
            PanelCard(title = trf("搜索结果_0_1_项_bc8aaa1631", query, hits.size), cardKey = "files-hits", right = {
                GhostBtn(text = L["清除_7b15e5e8e7"], onClick = { FileStore.hits.value = emptyList() }, icon = "close")
            }) {
                DataTable(
                    columns = listOf(
                        TableCol<SearchHit>(L["路径_9132e087f1"], weight = 3f, mono = true) { TableText(it.path, mono = true) },
                        TableCol<SearchHit>(L["类型_e4e46c7235"], weight = 0.7f) { TableText(it.type) },
                        TableCol<SearchHit>(L["大小_fd20702c73"], weight = 0.9f, align = TextAlign.End, mono = true) { TableText(Fmt.bytes(it.size), textAlign = TextAlign.End, mono = true) },
                        TableCol<SearchHit>(L["修改_c9c77517fe"], weight = 1.2f, mono = true) { TableText(it.mtimeText, mono = true, color = Palette.muted) },
                        TableCol<SearchHit>(L["操作_f3ea6d345e"], weight = 1.4f) { h ->
                            GhostBtn(text = L["打开所在目录_b4bf69d6b9"], onClick = {
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
            PanelCard(title = trf("目录占用_0_12ed08ebe7", FileStore.usageFor.value), cardKey = "files-usage") {
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
            title = trf("0_1_项_2_72069f61cf", l.dir, l.count, if (l.usedShell) L["服务端_find_9eb5103833"] else L["本地扫描_02b5698fe8"]),
            cardKey = "files-list",
            collapsible = false,
            right = {
                GhostBtn(text = L["全选_3e44b2a933"], onClick = { selected = l.entries.map { it.path }.toSet() }, icon = "checkSquare")
                GhostBtn(text = L["只选目录_bc8b48ee0e"], onClick = { selected = l.entries.filter { it.isDir }.map { it.path }.toSet() }, icon = "folder")
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    value = query,
                    onValueChange = { query = it },
                    hint = L["在本目录内按名称搜索_深度_4_0256e64630"],
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                PrimaryBtn(text = L["搜索_f04090805c"], onClick = { scope.launch { FileStore.search(query) } }, enabled = query.isNotBlank(), icon = "search")
            }
            Spacer(Modifier.size(8.dp))
            if (l.entries.isEmpty()) {
                EmptyState(L["空目录_e878e3c87b"], icon = "folder")
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<FileEntry>(L["选_471c7b464c"], weight = 0.4f, align = TextAlign.Center) { e ->
                            Toggle(
                                checked = e.path in selected,
                                onCheckedChange = { on -> selected = if (on) selected + e.path else selected - e.path },
                            )
                        },
                        TableCol<FileEntry>(L["名称_1be7ae4fc2"], weight = 2.0f) { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PanelIcon(if (e.isDir) "folder" else "fileDown", 13.dp, if (e.isDir) Palette.info else Palette.muted)
                                Spacer(Modifier.size(6.dp))
                                Column {
                                    TableText(e.name)
                                    if (e.isLink && e.linkTarget.isNotBlank()) TableText("→ ${e.linkTarget}", color = Palette.muted, mono = true)
                                }
                            }
                        },
                        TableCol<FileEntry>(L["类型_e4e46c7235"], weight = 0.7f) { e -> TableText(e.typeLabel, color = if (e.isDir) Palette.info else Palette.muted) },
                        TableCol<FileEntry>(L["大小_fd20702c73"], weight = 0.9f, align = TextAlign.End, mono = true) { e ->
                            TableText(if (e.isDir) (if (e.entryCount > 0) trf("0_项_2f9578aead", e.entryCount) else "—") else Fmt.bytes(e.size), textAlign = TextAlign.End, mono = true)
                        },
                        TableCol<FileEntry>(L["权限_560165a6d7"], weight = 1.1f, mono = true) { e -> TableText(e.symbolic.ifBlank { e.mode }, mono = true) },
                        TableCol<FileEntry>(L["属主_444fd7bd13"], weight = 1.0f, mono = true) { e ->
                            TableText(
                                listOf(e.owner, e.group).filter(String::isNotBlank).joinToString(":")
                                    .ifBlank { listOf(e.uid, e.gid).filter(String::isNotBlank).joinToString(":").ifBlank { "—" } },
                                mono = true,
                            )
                        },
                        TableCol<FileEntry>(L["修改时间_af71bc5cc4"], weight = 1.4f, mono = true) { e ->
                            TableText(if (e.mtimeMs > 0) Fmt.dateTime(e.mtimeMs) else "—", mono = true, color = Palette.muted)
                        },
                        TableCol<FileEntry>(L["操作_f3ea6d345e"], weight = 2.8f) { e ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (e.isDir) {
                                    GhostBtn(text = L["进入_0e25578e8f"], onClick = { go(e.path) }, icon = "chevronRight")
                                } else {
                                    GhostBtn(text = L["预览_de61aa8e1c"], onClick = { scope.launch { FileStore.previewFile(e.path) } }, icon = "eye")
                                    GhostBtn(text = L["编辑_a7f814c0a4"], onClick = {
                                        scope.launch {
                                            val err = FileStore.startEdit(e.path)
                                            if (err != null) AppState.toast(err, ToastKind.Warn, ms = 7000)
                                        }
                                    }, icon = "edit")
                                    GhostBtn(text = L["下载_2b9d013177"], onClick = {
                                        scope.launch {
                                            val out = FileStore.downloadTo(e.path, e.name)
                                            when {
                                                out.cancelled -> AppState.toast(tr("已取消保存_1f0e8fda6a"), ToastKind.Ok)
                                                out.error != null -> AppState.toast(out.error!!, ToastKind.Err, ms = 7000)
                                                else -> AppState.toast(
                                                    trf("已保存到_0_1_06aa73d49a", out.path ?: "", Fmt.bytes(out.bytes)),
                                                    ToastKind.Ok, ms = 6000,
                                                )
                                            }
                                        }
                                    }, icon = "download")
                                    if (e.name.endsWith(".tar.gz") || e.name.endsWith(".tgz") || e.name.endsWith(".zip")) {
                                        GhostBtn(text = L["解压_809c04fbd9"], onClick = {
                                            scope.launch { toast(FileStore.extract(e.path, l.dir), trf("已解压到_0_51e4ef2361", l.dir)) }
                                        }, icon = "box")
                                    }
                                }
                                GhostBtn(text = L["重命名_1cd80fd7a8"], onClick = {
                                    scope.launch {
                                        val f = Modal.prompt(L["重命名_1cd80fd7a8"], listOf(FormField.Text("name", L["新名称_b8659855b0"], value = e.name)), okText = L["确定_f526c89937"]) ?: return@launch
                                        toast(FileStore.rename(e.path, f["name"].orEmpty()), L["已重命名_dc9fe43fb7"])
                                    }
                                }, icon = "edit")
                                GhostBtn(text = L["删_38cadf487e"], onClick = {
                                    scope.launch {
                                        if (Modal.confirm(L["移入回收站_298cbe31d3"], trf("0_将进入回收站_保留_1_天_0a8a244509", e.name, FileStore.trashDays.value), okText = L["移入回收站_298cbe31d3"], danger = true)) {
                                            toast(FileStore.delete(listOf(e.path)), L["已移入回收站_54e2ac947c"])
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

        PanelCard(title = L["边界说明_ccc6bb88d9"], cardKey = "files-notes", collapsible = false) {
            Text(
                text = L["允许范围由服务器的_cm_files_roots_1e4f0369fa"] +
                    L["文件管理器不额外扩权_所有写操作都进面板审计日志_9856778584"],
                color = Palette.muted,
                fontSize = 11.5.sp,
            )
        }
    }
}

/**
 * 传输队列。进度来自 Ktor 的 onDownload/onUpload（报的是真正进出 socket 的字节），
 * 不是客户端按块自己累加——后者在压缩与分块下会偏。
 * 未知总长时不画假进度条，只报已传字节。
 */
@Composable
private fun TransfersCard() {
    val items = Transfers.items
    if (items.isEmpty()) return
    PanelCard(
        title = L["传输_14b6b3a266"],
        cardKey = "files-transfers",
        collapsible = false,
        right = {
            if (items.any { it.finished }) {
                GhostBtn(text = L["清除已完成_de4ca16286"], icon = "close", onClick = { Transfers.clearFinished() })
            }
        },
    ) {
        items.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = t.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.text,
                )
                Text(
                    text = Fmt.bytes(t.sent) +
                        (if (t.total > 0L) " / " + Fmt.bytes(t.total) else L["大小未知_4cdd9c30fc"]) +
                        " · " + (if (t.kind == Transfer.Kind.Download) L["下载_2b9d013177"] else L["上传_a977650db1"]),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.muted,
                )
            }
            Spacer(Modifier.height(4.dp))
            when {
                t.error != null -> Text(
                    text = t.error!!, color = Palette.bad, style = MaterialTheme.typography.labelSmall,
                )
                t.done -> Text(
                    text = trf("已完成_0_b30b29b63b", t.path ?: ""),
                    color = Palette.ok,
                    style = MaterialTheme.typography.labelSmall,
                )
                else -> LinearProgressIndicator(
                    progress = { t.fraction },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
