package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.str
import com.servermaintance.panel.platformClipboard
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SelectField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * 笔记 —— 复刻 notebox static/app.js「notes」视图（多用户 + 共享版）
 * 端点：GET /api/notes?q=&tag=   GET /api/tags
 *       POST /api/notes  PUT /api/notes/<id>  DELETE /api/notes/<id>
 *       共享：GET/POST /api/notes/<id>/shares  PUT/DELETE .../shares/<sid>
 *             POST /api/notes/<id>/share  POST /api/notes/<id>/unshare
 *       上传：POST /api/assets   富文本转换：POST /api/convert/html
 * 交互：搜索防抖 / 标签过滤 / 新建 / 置顶 / 删除 / 自动保存防抖 / 预览
 *       工具栏（H1/H2/加粗/斜体/删除线/行内码/列表/任务/引用/分割线/表格/
 *               代码块/链接/图片/视频）+ 共享面板（公开链接 + 按用户授权）
 *       权限感知（只读共享不可编辑；非 owner/admin 隐藏置顶/删除/共享）
 * ==================================================================== */

private class NoteRec(o: JsonObject) {
    val id: Int = o.int("id")
    var title: String = o.str("title")
    var content: String = o.str("content")
    var tags: String = o.str("tags")
    var pinned: Boolean = o.int("pinned") != 0
    val updatedAt: Long = o.long("updated_at")
    /* 多用户 */
    var myPermission: String? = o.str("my_permission").takeIf { it.isNotEmpty() }
    val isOwner: Boolean = o.int("is_owner") != 0
    val ownerEmail: String = o.str("owner_email")
    var shareCode: String? = o.str("share_code").takeIf { it.isNotEmpty() }
    /* 归类 / 外观 / 回收站 */
    var folderId: Int? = o.int("folder_id").takeIf { it > 0 }
    var folderName: String = o.str("folder_name")
    var folderColor: String = o.str("folder_color")
    var color: String = o.str("color")
    val deletedAt: Long = o.long("deleted_at")
}

@Composable
fun NotesPage() {
    NoteGate { NotesInner() }
}

@Composable
private fun NotesInner() {
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<NoteRec>>(emptyList()) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf<List<NoteFolder>>(emptyList()) }
    var folderSel by remember { mutableStateOf("all") } // all | unfiled | shared | trash | f:<id>
    var sort by remember { mutableStateOf("") } // ""=更新/标题/创建
    var currentId by remember { mutableStateOf<Int?>(null) }
    var preview by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf("") } // "" | saving | saved | err

    /* 编辑缓冲（标题 / 内容[含选区] / 标签 / 置顶） */
    var eTitle by remember { mutableStateOf("") }
    var eContent by remember { mutableStateOf(TextFieldValue("")) }
    var eTags by remember { mutableStateOf("") }
    var ePinned by remember { mutableStateOf(false) }
    var loadedId by remember { mutableStateOf<Int?>(null) }

    /* 共享面板 */
    var sharePanelOpen by remember { mutableStateOf(false) }
    var shares by remember { mutableStateOf<List<JsonObject>>(emptyList()) }

    val current = currentId?.let { id -> notes.firstOrNull { it.id == id } }
    val access = noteAccess(current?.myPermission)

    val inTrash = folderSel == "trash"
    val folderParam = when {
        folderSel.startsWith("f:") -> folderSel.removePrefix("f:")
        folderSel == "unfiled" -> "none"
        else -> ""
    }
    val scopeParam = if (folderSel == "shared") "shared" else ""

    suspend fun load() {
        try {
            val list = withContext(Dispatchers.IO) {
                NoteBoxState.api.listNotes(q.trim(), tag, folderParam, inTrash, scopeParam, sort)
            }
            notes = list.filterIsInstance<JsonObject>().map(::NoteRec)
            folders = withContext(Dispatchers.IO) {
                runCatching { NoteBoxState.api.listFolders("note") }.getOrDefault(emptyList())
            }.toFolders()
            error = null
            val cur = currentId
            if (cur != null && notes.none { it.id == cur }) {
                currentId = null
                loadedId = null
                sharePanelOpen = false
                shares = emptyList()
            }
            withContext(Dispatchers.IO) {
                runCatching { NoteBoxState.api.tags() }.getOrDefault(emptyList())
            }.let { tags = it }
            NoteBoxState.refreshStats()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiException)?.isAuth != true) {
                error = e.message ?: L["加载失败_f6b7a41e56"]
            }
        } finally {
            loading = false
        }
    }

    suspend fun loadShares(nid: Int) {
        try {
            val r = withContext(Dispatchers.IO) { NoteBoxState.api.listShares(nid) }
            if (currentId == nid) shares = r.filterIsInstance<JsonObject>()
        } catch (_: Exception) { /* 只读用户或网络抖动：静默 */ }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(q, tag, folderSel, sort) { delay(260); load() }

    fun selectNote(id: Int) {
        currentId = id
        preview = false
        sharePanelOpen = false
        shares = emptyList()
        val n = notes.firstOrNull { it.id == id } ?: return
        eTitle = n.title
        eContent = TextFieldValue(n.content)
        eTags = n.tags
        ePinned = n.pinned
        loadedId = id
        // owner/admin 打开时拉共享列表（同 app.js renderNoteEditor）
        if (noteAccess(n.myPermission).canManage) {
            scope.launch { loadShares(id) }
        }
    }

    /* 自动保存（防抖 900ms；只读共享不保存） */
    LaunchedEffect(eContent.text, eTitle, eTags) {
        val id = loadedId ?: return@LaunchedEffect
        val n = notes.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (!noteAccess(n.myPermission).canEdit) return@LaunchedEffect
        if (eTitle == n.title && eContent.text == n.content && eTags == n.tags) return@LaunchedEffect
        delay(900)
        saveState = "saving"
        try {
            val updated = withContext(Dispatchers.IO) {
                NoteBoxState.api.updateNote(id, eTitle, eContent.text, eTags, ePinned)
            }
            val rec = NoteRec(updated)
            notes = notes.map { if (it.id == id) rec else it }
            saveState = "saved"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saveState = "err"
            NoteBoxState.toast(tr("保存失败_b12163000c") + (e.message ?: ""), ToastKind.Err)
        }
    }

    /* 工具栏：包裹选区（加粗/斜体/删除线/行内码/链接）。 */
    fun wrapSel(pre: String, post: String, ph: String) {
        val tfv = eContent
        val sel = tfv.selection
        val text = tfv.text
        val picked = if (sel.collapsed) ph else text.substring(sel.start, sel.end)
        val newText = text.substring(0, sel.start) + pre + picked + post + text.substring(sel.end)
        val ns = sel.start + pre.length
        eContent = TextFieldValue(newText, TextRange(ns, ns + picked.length))
    }

    /* 工具栏：整行前缀开关（标题/列表/任务/引用）。 */
    fun togglePrefix(prefix: String) {
        val tfv = eContent
        val text = tfv.text
        val cursor = tfv.selection.start
        val lineStart = if (cursor == 0) 0 else text.lastIndexOf('\n', cursor - 1) + 1
        val lineEnd = text.indexOf('\n', cursor).let { if (it == -1) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        if (line.startsWith(prefix)) {
            val nl = line.removePrefix(prefix)
            val newText = text.substring(0, lineStart) + nl + text.substring(lineEnd)
            val nc = (cursor - prefix.length).coerceAtLeast(lineStart)
            eContent = TextFieldValue(newText, TextRange(nc))
        } else {
            val newText = text.substring(0, lineStart) + prefix + line + text.substring(lineEnd)
            eContent = TextFieldValue(newText, TextRange(cursor + prefix.length))
        }
    }

    fun insertAt(text: String, cursorOffset: Int? = null) {
        val tfv = eContent
        val sel = tfv.selection
        val newText = tfv.text.substring(0, sel.start) + text + tfv.text.substring(sel.end)
        val pos = if (cursorOffset != null) sel.start + cursorOffset else sel.start + text.length
        eContent = TextFieldValue(newText, TextRange(pos))
    }

    /* 上传媒体（图片/视频）→ 插入 Markdown。 */
    fun uploadFiles(files: List<com.servermaintance.panel.PickedBinary>) {
        val usable = files.filter { it.bytes.isNotEmpty() }
        if (usable.isEmpty()) return
        scope.launch {
            NoteBoxState.toast(tr("上传中_usable_size_个文件_79da89118e"))
            var ok = 0
            for (f in usable) {
                try {
                    val r = withContext(Dispatchers.IO) {
                        NoteBoxState.api.uploadAsset(f.bytes, f.name, f.mime)
                    }
                    val url = r.str("url")
                    val kind = r.str("kind")
                    val label = f.name.replace(Regex("[\\[\\]() ]"), "")
                    if (kind == "image") insertAt("\n![$label]($url)\n")
                    else insertAt("\n[$label]($url)\n")
                    ok++
                } catch (e: Exception) {
                    NoteBoxState.toast(tr("上传失败_e3f9c342c6") + (e.message ?: ""), ToastKind.Err)
                    break
                }
            }
            if (ok > 0) NoteBoxState.toast(tr("已插入_ok_个文件_5032d55e92"), ToastKind.Ok)
        }
    }

    PageScaffold(
        title = L["笔记_697dbde096"],
        desc = L["notebox_云端笔记_多级归类_回收站_df77794405"],
        scrollBody = false, // 列表/编辑器各自在有界区域内滚动
        actions = { NoteUserChip() },
    ) {
        /* 归类相关动作（共享给工具栏 / 归类树 / 编辑器） */
        fun folderSubtree(fid: Int): Set<Int> = folderSubtreeIds(folders, fid)
        fun refreshAfterChange() {
            scope.launch { load() }
        }
        fun createFolder(parent: Int?) {
            scope.launch {
                val blocked = emptySet<Int>()
                val opts = folderParentOptions(folders)
                    .filter { it.first == null || it.first !in blocked }
                val initial = opts.firstOrNull { it.first == parent }?.second ?: L["顶层_8dcb7af51d"]
                val r = Modal.prompt(
                    L["新建归类_e4ffdeb063"],
                    listOf(
                        FormField.Text("name", L["名称_1be7ae4fc2"], "", L["例如_工作_项目资料_190eafea68"]),
                        FormField.Select("parent", L["上级归类_974c15f8d9"], opts.map { it.second }, initial),
                        FormField.Select("color", L["颜色_8ef4886033"], folderColorNames, L["默认_c8d09cf955"]),
                    ),
                    okText = L["创建_fcbd093292"],
                ) ?: return@launch
                val name = r["name"].orEmpty().trim()
                if (name.isEmpty()) {
                    NoteBoxState.toast(tr("归类名称不能为空_8aeba11184"), ToastKind.Err)
                    return@launch
                }
                val pid = opts.firstOrNull { it.second == r["parent"] }?.first
                val hex = NB_NOTE_COLORS[folderColorNames.indexOf(r["color"] ?: L["默认_c8d09cf955"]).coerceAtLeast(0)]
                try {
                    withContext(Dispatchers.IO) { NoteBoxState.api.createFolder(name, "note", pid, hex) }
                    NoteBoxState.toast(tr("已创建归类_name_4dcdef1d04"), ToastKind.Ok)
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("创建失败_deb3990191"), ToastKind.Err)
                }
            }
        }
        fun editFolder(folder: NoteFolder) {
            scope.launch {
                val blocked = folderSubtree(folder.id)
                val opts = folderParentOptions(folders)
                    .filter { it.first == null || it.first !in blocked }
                val initial = opts.firstOrNull { it.first == folder.parentId }?.second ?: L["顶层_8dcb7af51d"]
                val r = Modal.prompt(
                    L["编辑归类_c2e1877f39"],
                    listOf(
                        FormField.Text("name", L["名称_1be7ae4fc2"], folder.name),
                        FormField.Select("parent", L["上级归类_974c15f8d9"], opts.map { it.second }, initial),
                        FormField.Select("color", L["颜色_8ef4886033"], folderColorNames, colorNameOf(folder.color)),
                    ),
                    okText = L["保存_fadf24dbc5"],
                ) ?: return@launch
                val name = r["name"].orEmpty().trim()
                if (name.isEmpty()) {
                    NoteBoxState.toast(tr("归类名称不能为空_8aeba11184"), ToastKind.Err)
                    return@launch
                }
                val pid = opts.firstOrNull { it.second == r["parent"] }?.first
                val hex = NB_NOTE_COLORS[folderColorNames.indexOf(r["color"] ?: L["默认_c8d09cf955"]).coerceAtLeast(0)]
                try {
                    withContext(Dispatchers.IO) {
                        NoteBoxState.api.updateFolder(folder.id, name, pid, hex, folder.position)
                    }
                    NoteBoxState.toast(tr("归类已更新_219432c27a"), ToastKind.Ok)
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("保存失败_40525a7328"), ToastKind.Err)
                }
            }
        }
        fun removeFolder(folder: NoteFolder) {
            scope.launch {
                val ok = Modal.confirm(
                    L["删除归类_f282b52e6b"],
                    trf("删除_0_其中的笔记与子归类会移动到上一级_327496c4d8", folder.name),
                    okText = L["删除_3755f56f2f"], danger = true,
                )
                if (!ok) return@launch
                try {
                    withContext(Dispatchers.IO) { NoteBoxState.api.deleteFolder(folder.id) }
                    if (folderSel.startsWith("f:")) {
                        val selId = folderSel.removePrefix("f:").toIntOrNull()
                        if (selId != null && selId in folderSubtree(folder.id)) {
                            folderSel = folder.parentId?.let { "f:$it" } ?: "all"
                        }
                    }
                    NoteBoxState.toast(tr("归类已删除_内容已移到上一级_933faa2f08"), ToastKind.Ok)
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("删除失败_72250c5922"), ToastKind.Err)
                }
            }
        }
        fun replaceNote(json: JsonObject) {
            val rec = NoteRec(json)
            notes = notes.map { if (it.id == rec.id) rec else it }
        }
        fun moveNoteToFolder(note: NoteRec) {
            scope.launch {
                val all = folderFlat(folders)
                val labels = listOf(L["未归类_9af2c7babc"]) + all.map { folderPathName(folders, it.id) }
                val current = folderPathName(folders, note.folderId)
                val r = Modal.prompt(
                    L["移动到归类_cbd544b32e"],
                    listOf(FormField.Select("folder", L["选择归类_a34a4beaca"], labels, current)),
                    okText = L["移动_591f3aa55f"],
                ) ?: return@launch
                val label = r["folder"] ?: L["未归类_9af2c7babc"]
                val target = if (label == L["未归类_9af2c7babc"]) null else
                    all.firstOrNull { folderPathName(folders, it.id) == label }?.id
                try {
                    val updated = withContext(Dispatchers.IO) { NoteBoxState.api.moveNote(note.id, target) }
                    replaceNote(updated)
                    NoteBoxState.toast(tr("已移动到_label_6617d663be"), ToastKind.Ok)
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("移动失败_5ff5a6803e"), ToastKind.Err)
                }
            }
        }
        fun setNoteColor(note: NoteRec, hex: String) {
            scope.launch {
                try {
                    val updated = withContext(Dispatchers.IO) { NoteBoxState.api.setNoteColor(note.id, hex) }
                    replaceNote(updated)
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("颜色修改失败_184c2f8e4b"), ToastKind.Err)
                }
            }
        }
        fun trashNote(note: NoteRec) {
            scope.launch {
                val ok = Modal.confirm(
                    L["移入回收站_298cbe31d3"],
                    trf("将_0_移入回收站_可随时恢复_b035da26b3", note.title.ifEmpty { L["未命名笔记_044c507dcb"] }),
                    okText = L["移入回收站_298cbe31d3"],
                )
                if (!ok) return@launch
                try {
                    withContext(Dispatchers.IO) { NoteBoxState.api.trashNote(note.id) }
                    notes = notes.filterNot { it.id == note.id }
                    if (currentId == note.id) { currentId = null; loadedId = null }
                    NoteBoxState.toast(tr("已移入回收站_54e2ac947c"), ToastKind.Ok)
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("操作失败_09e424b5e8"), ToastKind.Err)
                }
            }
        }
        fun restoreNote(note: NoteRec) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { NoteBoxState.api.restoreNote(note.id) }
                    NoteBoxState.toast(trf("已恢复_0_120218aebb", note.title.ifEmpty { L["未命名笔记_044c507dcb"] }), ToastKind.Ok)
                    if (currentId == note.id) { currentId = null; loadedId = null }
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("恢复失败_76842a0348"), ToastKind.Err)
                }
            }
        }
        fun purgeNote(note: NoteRec) {
            scope.launch {
                val ok = Modal.confirm(
                    L["永久删除_a7fadbe126"],
                    trf("永久删除_0_此操作不可恢复_c22836ea42", note.title.ifEmpty { L["未命名笔记_044c507dcb"] }),
                    okText = L["永久删除_a7fadbe126"],
                    danger = true,
                )
                if (!ok) return@launch
                try {
                    withContext(Dispatchers.IO) { NoteBoxState.api.deleteNote(note.id) }
                    notes = notes.filterNot { it.id == note.id }
                    if (currentId == note.id) { currentId = null; loadedId = null }
                    NoteBoxState.toast(tr("已永久删除_b2e5fd714f"), ToastKind.Ok)
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("删除失败_72250c5922"), ToastKind.Err)
                }
            }
        }
        fun duplicateNote(note: NoteRec) {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) { NoteBoxState.api.duplicateNote(note.id) }
                    val rec = NoteRec(json)
                    if (folderSel == "trash" || folderSel == "shared") folderSel = "all"
                    notes = listOf(rec) + notes
                    selectNote(rec.id)
                    NoteBoxState.toast(tr("已复制笔记_578fe5f530"), ToastKind.Ok)
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(e.message ?: tr("复制失败_f2c6b5167b"), ToastKind.Err)
                }
            }
        }

        /* 工具行：搜索 + 归类 + 标签 + 排序 + 新建（底板容器，视觉归组） */
        val barShape = RoundedCornerShape(nbCardRadius(10.dp))
        val folderLabels = listOf(L["全部笔记_be5bc30cf8"], L["未归类_9af2c7babc"], L["共享给我_6863118293"], L["回收站_64ea8751fb"]) +
            folderFlat(folders).map { folderPathName(folders, it.id) }
        val currentFolderLabel = when {
            folderSel == "all" -> L["全部笔记_be5bc30cf8"]
            folderSel == "unfiled" -> L["未归类_9af2c7babc"]
            folderSel == "shared" -> L["共享给我_6863118293"]
            folderSel == "trash" -> L["回收站_64ea8751fb"]
            else -> folderSel.removePrefix("f:").toIntOrNull()
                ?.let { folderPathName(folders, it) } ?: L["全部笔记_be5bc30cf8"]
        }
        val createNewNote: () -> Unit = {
            scope.launch {
                try {
                    if (folderSel == "trash" || folderSel == "shared") folderSel = "all"
                    val n = withContext(Dispatchers.IO) {
                        NoteBoxState.api.createNote(
                            L["未命名笔记_044c507dcb"],
                            folderId = folderParam.toIntOrNull(),
                        )
                    }
                    val rec = NoteRec(n)
                    q = ""; tag = ""
                    notes = listOf(rec) + notes
                    selectNote(rec.id)
                    NoteBoxState.refreshStats()
                    load()
                } catch (e: Exception) {
                    NoteBoxState.toast(tr("新建失败_ae047a3e45") + (e.message ?: ""), ToastKind.Err)
                }
            }
        }
        val folderSelect: @Composable () -> Unit = {
            Box(Modifier.widthIn(max = if (platformHandheld) 160.dp else 150.dp)) {
                SelectField(currentFolderLabel, folderLabels + L["新建归类_64cf5b76fb"], {
                    when (it) {
                        L["全部笔记_be5bc30cf8"] -> folderSel = "all"
                        L["未归类_9af2c7babc"] -> folderSel = "unfiled"
                        L["共享给我_6863118293"] -> folderSel = "shared"
                        L["回收站_64ea8751fb"] -> folderSel = "trash"
                        L["新建归类_64cf5b76fb"] -> createFolder(folderSel.removePrefix("f:").toIntOrNull())
                        else -> folderFlat(folders)
                            .firstOrNull { f -> folderPathName(folders, f.id) == it }
                            ?.let { f -> folderSel = "f:${f.id}" }
                    }
                }, placeholder = L["归类_6a9b5537c6"])
            }
        }
        val tagSelect: @Composable () -> Unit = {
            Box(Modifier.widthIn(max = 130.dp)) {
                SelectField(tag, listOf(L["全部标签_ffd92f5524"]) + tags, {
                    tag = if (it == L["全部标签_ffd92f5524"]) "" else it
                }, placeholder = L["全部标签_ffd92f5524"])
            }
        }
        val barModifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .nbEnter(0)
            .clip(barShape)
            .background(Palette.panel)
            .border(1.dp, Palette.border, barShape)

        if (platformHandheld) {
            Column(
                barModifier.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchField(q, { q = it }, Modifier.weight(1f), placeholder = L["搜索笔记_8699c6b13f"])
                    Box(Modifier.nbPressScale(0.96f)) {
                        PrimaryBtn(text = L["新建_0cda8d1c71"], icon = "plus", onClick = createNewNote)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    folderSelect()
                    tagSelect()
                }
            }
        } else {
            Row(
                barModifier.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchField(q, { q = it }, Modifier.weight(1f), placeholder = L["搜索笔记_8699c6b13f"])
                folderSelect()
                tagSelect()
                Box(Modifier.widthIn(max = 110.dp)) {
                    val sortLabels = listOf(L["最近更新_80920cd667"], L["标题_748d7dc7e3"], L["创建时间_84e3802f60"])
                    SelectField(
                        if (sort == "title") sortLabels[1] else if (sort == "created") sortLabels[2] else sortLabels[0],
                        sortLabels,
                        {
                            sort = when (it) {
                                L["标题_748d7dc7e3"] -> "title"
                                L["创建时间_84e3802f60"] -> "created"
                                else -> ""
                            }
                        },
                        placeholder = L["排序_dc35af8d69"],
                    )
                }
                Box(Modifier.nbPressScale(0.96f)) {
                    PrimaryBtn(text = L["新建笔记_14fd2fe5ca"], icon = "plus", onClick = createNewNote)
                }
            }
        }

        when {
            loading && notes.isEmpty() && error == null -> {
                /* 骨架屏加载：列表形状的微光占位（桌面保留右栏空态） */
                if (platformHandheld) {
                    NbSkeletonList(rows = 8, modifier = Modifier.fillMaxWidth().padding(top = 2.dp), rowHeight = 54.dp)
                } else {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.width(280.dp).fillMaxHeight().padding(6.dp)) {
                            NbSkeletonList(rows = 9, modifier = Modifier.fillMaxWidth(), rowHeight = 54.dp)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp), contentAlignment = Alignment.Center) {
                            NbEmptyGlow(icon = "note", title = L["正在加载笔记_d95b85e311"])
                        }
                    }
                }
            }
            error != null && notes.isEmpty() -> ErrorState(message = error!!, onRetry = {
                scope.launch { loading = true; load() }
            })
            else -> {
                if (platformHandheld) {
                    /* 手持端：列表 ⇄ 编辑器（方向性滑动 + 淡入淡出）。
                     * targetState 用 currentId（Int?）而非 NoteRec——自动保存会生成新
                     * NoteRec 实例，若以记录对象为状态，每次保存都会误触发切换动画。 */
                    AnimatedContent(
                        targetState = currentId,
                        transitionSpec = {
                            if (initialState == null) {
                                /* 进入编辑器：编辑器从右滑入，列表向左淡出 */
                                (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(240))) togetherWith
                                    (slideOutHorizontally(tween(220)) { -it / 6 } + fadeOut(tween(170)))
                            } else {
                                /* 返回列表：列表从左滑回，编辑器向右淡出 */
                                (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(240))) togetherWith
                                    (slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(170)))
                            }
                        },
                        label = "noteNav",
                    ) { cid ->
                        val cur = cid?.let { id -> notes.firstOrNull { it.id == id } }
                        if (cur == null) {
                            NoteList(
                                notes, currentId, onSelect = { selectNote(it) },
                                viewKey = folderSel,
                                emptyTitle = when (folderSel) {
                                    "trash" -> L["回收站是空的_e872522628"]
                                    "shared" -> L["还没有共享给你的笔记_fc493b2671"]
                                    else -> L["这里还没有笔记_159b548769"]
                                },
                                emptySubtitle = when (folderSel) {
                                    "trash" -> L["删除的笔记会先放到这里_可随时恢复_1e1ce2f496"]
                                    "shared" -> L["其他用户共享给你的笔记会出现在这里_864edb3def"]
                                    else -> L["点右上角_新建笔记_开始_96865806de"]
                                },
                            )
                        } else {
                            NoteEditor(
                                note = cur,
                                access = if (inTrash) NoteAccess(cur.myPermission, canEdit = false, canManage = false)
                                else noteAccess(cur.myPermission),
                                title = eTitle, onTitle = { eTitle = it },
                                content = eContent, onContent = { eContent = it },
                                tags = eTags, onTags = { eTags = it },
                                pinned = ePinned,
                                onPin = {
                                    scope.launch {
                                        try {
                                            val updated = withContext(Dispatchers.IO) {
                                                NoteBoxState.api.updateNote(cur.id, eTitle, eContent.text, eTags, !ePinned)
                                            }
                                            val rec = NoteRec(updated)
                                            notes = notes.map { if (it.id == rec.id) rec else it }
                                            ePinned = rec.pinned
                                            NoteBoxState.toast(if (rec.pinned) tr("已置顶_d7d97038a3") else tr("已取消置顶_64ca5ea597"))
                                        } catch (e: Exception) {
                                            NoteBoxState.toast(e.message ?: tr("操作失败_09e424b5e8"), ToastKind.Err)
                                        }
                                    }
                                },
                                preview = preview, onPreviewToggle = { preview = !preview },
                                saveState = saveState,
                                onDelete = { if (inTrash) purgeNote(cur) else trashNote(cur) },
                                onBack = { currentId = null; loadedId = null },
                                onToolbar = { cmd -> handleToolbar(cmd, ::wrapSel, ::togglePrefix, ::insertAt) },
                                onPickMedia = { mime, multiple -> pickMedia(mime, multiple, ::uploadFiles) },
                                sharePanelOpen = sharePanelOpen,
                                onToggleShare = { sharePanelOpen = !sharePanelOpen },
                                shares = shares,
                                onShareEvents = { ev ->
                                    scope.launch { handleShare(ev, cur, ::loadShares, { shares = it }) }
                                },
                                inTrash = inTrash,
                                folderText = folderPathName(folders, cur.folderId),
                                folderColor = cur.folderColor,
                                noteColor = cur.color,
                                onMove = if (cur.isOwner) ({ moveNoteToFolder(cur) }) else null,
                                onColor = if (cur.isOwner) ({ hex -> setNoteColor(cur, hex) }) else null,
                                onDuplicate = { duplicateNote(cur) },
                                onRestore = if (inTrash) ({ restoreNote(cur) }) else null,
                            )
                        }
                    }
                } else {
                    /* 桌面：归类树 + 列表 + 编辑器；窄窗口自动收起归类树（顶部下拉仍可用） */
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val showRail = maxWidth >= 980.dp
                        Row(Modifier.fillMaxSize()) {
                            if (showRail) {
                                Box(Modifier.width(200.dp).fillMaxHeight().padding(end = 10.dp)) {
                                    NoteFolderRail(
                                        folders = folders,
                                        selected = folderSel,
                                        onSelect = { folderSel = it },
                                        onCreate = { createFolder(it) },
                                        onEdit = { editFolder(it) },
                                        onDelete = { removeFolder(it) },
                                    )
                                }
                            }
                            Box(Modifier.width(300.dp).fillMaxHeight()) {
                                NoteList(
                                    notes, currentId, onSelect = { selectNote(it) },
                                    viewKey = folderSel,
                                    emptyTitle = when (folderSel) {
                                        "trash" -> L["回收站是空的_e872522628"]
                                        "shared" -> L["还没有共享给你的笔记_fc493b2671"]
                                        else -> L["这里还没有笔记_159b548769"]
                                    },
                                    emptySubtitle = when (folderSel) {
                                        "trash" -> L["删除的笔记会先放到这里_可随时恢复_1e1ce2f496"]
                                        "shared" -> L["其他用户共享给你的笔记会出现在这里_864edb3def"]
                                        else -> L["点左上角_新建笔记_创建_90394355e9"]
                                    },
                                )
                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.border))
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (current == null) {
                                    Box(Modifier.align(Alignment.Center)) {
                                        NbEmptyGlow(
                                            icon = "note",
                                            title = L["选择一条笔记开始编辑_ee98f376fb"],
                                            subtitle = L["或点左上角_新建笔记_创建_4bb6db19d3"],
                                        )
                                    }
                                } else {
                                    NoteEditor(
                                        note = current,
                                        access = if (inTrash) NoteAccess(current.myPermission, canEdit = false, canManage = false)
                                        else access,
                                        title = eTitle, onTitle = { eTitle = it },
                                        content = eContent, onContent = { eContent = it },
                                        tags = eTags, onTags = { eTags = it },
                                        pinned = ePinned,
                                        onPin = {
                                            scope.launch {
                                                try {
                                                    val updated = withContext(Dispatchers.IO) {
                                                        NoteBoxState.api.updateNote(current.id, eTitle, eContent.text, eTags, !ePinned)
                                                    }
                                                    val rec = NoteRec(updated)
                                                    notes = notes.map { if (it.id == rec.id) rec else it }
                                                    ePinned = rec.pinned
                                                    NoteBoxState.toast(if (rec.pinned) tr("已置顶_d7d97038a3") else tr("已取消置顶_64ca5ea597"))
                                                } catch (e: Exception) {
                                                    NoteBoxState.toast(e.message ?: tr("操作失败_09e424b5e8"), ToastKind.Err)
                                                }
                                            }
                                        },
                                        preview = preview, onPreviewToggle = { preview = !preview },
                                        saveState = saveState,
                                        onDelete = { if (inTrash) purgeNote(current) else trashNote(current) },
                                        onBack = null,
                                        onToolbar = { cmd -> handleToolbar(cmd, ::wrapSel, ::togglePrefix, ::insertAt) },
                                        onPickMedia = { mime, multiple -> pickMedia(mime, multiple, ::uploadFiles) },
                                        sharePanelOpen = sharePanelOpen,
                                        onToggleShare = { sharePanelOpen = !sharePanelOpen },
                                        shares = shares,
                                        onShareEvents = { ev ->
                                            scope.launch { handleShare(ev, current, ::loadShares, { shares = it }) }
                                        },
                                        inTrash = inTrash,
                                        folderText = folderPathName(folders, current.folderId),
                                        folderColor = current.folderColor,
                                        noteColor = current.color,
                                        onMove = if (current.isOwner) ({ moveNoteToFolder(current) }) else null,
                                        onColor = if (current.isOwner) ({ hex -> setNoteColor(current, hex) }) else null,
                                        onDuplicate = { duplicateNote(current) },
                                        onRestore = if (inTrash) ({ restoreNote(current) }) else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * 工具栏 / 上传 / 共享 逻辑
 * ------------------------------------------------------------------ */

private fun handleToolbar(
    cmd: String,
    wrapSel: (String, String, String) -> Unit,
    togglePrefix: (String) -> Unit,
    insertAt: (String, Int?) -> Unit,
) {
    when (cmd) {
        "h1" -> togglePrefix("# ")
        "h2" -> togglePrefix("## ")
        "h3" -> togglePrefix("### ")
        "ul" -> togglePrefix("- ")
        "ol" -> togglePrefix("1. ")
        "task" -> togglePrefix("- [ ] ")
        "quote" -> togglePrefix("> ")
        "bold" -> wrapSel(L["s_bc2f74c22f"], L["s_bc2f74c22f"], L["加粗_f670b2adc0"])
        "italic" -> wrapSel(L["s_df58248c41"], L["s_df58248c41"], L["斜体_af5a2c8bff"])
        "strike" -> wrapSel(L["s_41677611e1"], L["s_41677611e1"], L["删除线_c85de9e58a"])
        "inline-code" -> wrapSel(L["s_7e15bb5c01"], L["s_7e15bb5c01"], L["代码_33246dda76"])
        "hr" -> insertAt("\n\n---\n\n", null)
        "table" -> insertAt(L["列a_列b_276b732289"], null)
        "codeblock" -> insertAt(L["s_553f6f447e"], L["s_5d72819b21"].length + 1)
        "link" -> wrapSel(L["s_1e5c2f367f"], "](https://)", L["链接文字_154a364e41"])
    }
}

private fun pickMedia(mimePrefixes: List<String>, multiple: Boolean, onFiles: (List<com.servermaintance.panel.PickedBinary>) -> Unit) {
    com.servermaintance.panel.pickBinaryFiles(mimePrefixes, multiple, onFiles)
}

/** 共享面板动作（避免在 composable 里直接写 suspend）。 */
private sealed class ShareEvent {
    data object CopyLink : ShareEvent()
    data object EnablePublic : ShareEvent()
    data object DisablePublic : ShareEvent()
    data class AddUser(val email: String, val perm: String) : ShareEvent()
    data class SetPerm(val sid: Int, val perm: String) : ShareEvent()
    data class RemoveUser(val sid: Int) : ShareEvent()
}

private suspend fun handleShare(
    ev: ShareEvent,
    note: NoteRec,
    loadShares: suspend (Int) -> Unit,
    setShares: (List<JsonObject>) -> Unit,
) {
    try {
        when (ev) {
            ShareEvent.CopyLink -> {
                val code = note.shareCode ?: return
                val url = NoteBoxState.api.shareUrl(code)
                platformClipboard.copy(url)
                NoteBoxState.toast(tr("链接已复制_url_bbec15f531"), ToastKind.Ok)
            }
            ShareEvent.EnablePublic -> {
                val r = withContext(Dispatchers.IO) { NoteBoxState.api.shareNote(note.id) }
                val code = r.str("share_code")
                note.shareCode = code
                val url = NoteBoxState.api.shareUrl(code)
                platformClipboard.copy(url)
                NoteBoxState.toast(tr("公开链接已开启_已复制_url_519f2b78bb"), ToastKind.Ok)
            }
            ShareEvent.DisablePublic -> {
                withContext(Dispatchers.IO) { NoteBoxState.api.unshareNote(note.id) }
                note.shareCode = null
                NoteBoxState.toast(tr("已停止公开分享_9a9cf049a9"))
            }
            is ShareEvent.AddUser -> {
                val r = withContext(Dispatchers.IO) {
                    NoteBoxState.api.addShare(note.id, ev.email.trim(), ev.perm)
                }
                setShares(r.filterIsInstance<JsonObject>())
                NoteBoxState.toast(tr("已共享给_ev_email_permt_77651d6ed4"), ToastKind.Ok)
            }
            is ShareEvent.SetPerm -> {
                val r = withContext(Dispatchers.IO) {
                    NoteBoxState.api.updateShare(note.id, ev.sid, ev.perm)
                }
                setShares(r.filterIsInstance<JsonObject>())
                NoteBoxState.toast(tr("权限已改为_permtext_ev_perm_bde45d2842"), ToastKind.Ok)
            }
            is ShareEvent.RemoveUser -> {
                val ok = Modal.confirm(L["取消共享_2b2e551005"], L["取消对该用户的共享_67e86f5c00"], okText = L["移除_2f752c005e"], danger = true)
                if (!ok) return
                val r = withContext(Dispatchers.IO) {
                    NoteBoxState.api.deleteShare(note.id, ev.sid)
                }
                setShares(r.filterIsInstance<JsonObject>())
                NoteBoxState.toast(tr("已移除共享_830fcc049b"))
            }
        }
    } catch (e: Exception) {
        NoteBoxState.toast(e.message ?: tr("操作失败_09e424b5e8"), ToastKind.Err)
    }
}

/* ------------------------------------------------------------------ *
 * 列表（现代卡片：悬停浮起 + 滑动选中指示条 + 图钉回弹落位）
 * ------------------------------------------------------------------ */

@Composable
private fun NoteList(
    notes: List<NoteRec>,
    currentId: Int?,
    onSelect: (Int) -> Unit,
    viewKey: String = "",
    emptyTitle: String = L["还没有笔记_6682d75366"],
    emptySubtitle: String = L["点右上角_新建笔记_开始_96865806de"],
) {
    val shape = RoundedCornerShape(nbCardRadius(12.dp))
    /* 每张卡片在滚动内容坐标系中的几何，用于选中指示条的滑动定位 */
    val cardBounds = remember { mutableStateMapOf<Int, Rect>() }

    Box(Modifier.fillMaxSize().clip(shape).background(Palette.tableSurface).border(1.dp, Palette.border, shape)) {
        Column(Modifier.fillMaxSize()) {
            /* 列表头：强调短条 + 笔记计数胶囊（计数滚动跳字） */
            Row(
                Modifier.fillMaxWidth().background(Palette.tableHeader)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 3.dp, height = 11.dp)
                        .background(AccentState.current.primary, RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    L["nav.notes"],
                    color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    trf("0_条_1932375aa5", nbCountUp(notes.size)),
                    color = Palette.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Palette.inset)
                        .border(1.dp, Palette.border2, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 1.5.dp),
                )
            }
            if (notes.isEmpty()) {
                NbEmptyGlow(
                    icon = if (viewKey == "trash") "trash" else "note",
                    title = emptyTitle,
                    subtitle = emptySubtitle,
                    Modifier.fillMaxWidth(),
                )
            } else {
                key(viewKey) {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            Modifier.fillMaxSize().nbFadingEdges(top = 10.dp, bottom = 10.dp)
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            itemsIndexed(notes, key = { _, n -> n.id }) { idx, n ->
                                NoteCard(
                                    n = n,
                                    index = idx,
                                    selected = n.id == currentId,
                                    onSelect = { onSelect(n.id) },
                                    onBounds = { cardBounds[n.id] = it },
                                    /* 入场错落交给 nbEnter；animateItem 负责置顶/
                                     * 重排时的平滑位移与删除淡出 */
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = null,
                                        placementSpec = spring(dampingRatio = 0.8f, stiffness = 320f),
                                        fadeOutSpec = tween(150),
                                    ),
                                )
                            }
                        }
                        /* 滑动选中指示条：软弹簧滑到选中卡片 */
                        NoteSelectionBar(cardBounds, currentId)
                    }
                }
            }
        }
    }
}

/** 选中卡片左缘的强调条，位置/高度随选择以软弹簧滑动过渡。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.NoteSelectionBar(
    bounds: Map<Int, Rect>,
    selectedId: Int?,
) {
    val density = LocalDensity.current
    val b = selectedId?.let { bounds[it] }
    val targetY: Dp? = b?.let {
        with(density) { it.top.toDp() } + 7.dp
    }
    val targetH: Dp? = b?.let {
        with(density) { it.height.toDp() } - 14.dp
    }
    val y by animateDpAsState(targetY ?: 0.dp, nbSpringSoft(stiffness = 300f), label = "selBarY")
    val h by animateDpAsState(targetH ?: 0.dp, nbSpringSoft(stiffness = 300f), label = "selBarH")
    val a by animateFloatAsState(if (b != null) 1f else 0f, tween(200), label = "selBarA")
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .offset(x = 3.dp, y = y)
            .width(3.dp).height(h)
            .alpha(a)
            .background(AccentState.current.primary, RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun NoteCard(
    n: NoteRec,
    index: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val acc = noteAccess(n.myPermission)
    val shape = RoundedCornerShape(9.dp)

    /* 选中/悬停的表面色、描边过渡 + 浮起/按压微动效（置顶卡淡染主题色） */
    val cardBg by animateColorAsState(
        targetValue = when {
            selected -> AccentState.current.primary.copy(alpha = 0.10f)
            hovered -> Palette.panel2
            n.pinned -> AccentState.current.primary.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        animationSpec = tween(190),
        label = "noteCardBg",
    )
    val cardBorder by animateColorAsState(
        targetValue = when {
            selected -> AccentState.current.primary.copy(alpha = 0.35f)
            hovered -> Palette.border2
            n.pinned -> AccentState.current.primary.copy(alpha = 0.18f)
            else -> Color.Transparent
        },
        animationSpec = tween(190),
        label = "noteCardBorder",
    )
    val lift by animateFloatAsState(if (hovered && !selected) -1f else 0f, nbSpringSoft(stiffness = 500f), label = "noteLift")
    val press by animateFloatAsState(if (pressed) 0.985f else 1f, nbSpringPop(stiffness = 600f), label = "notePress")
    val titleColor by animateColorAsState(
        targetValue = if (selected) AccentState.current.secondary else Palette.text,
        animationSpec = tween(190),
        label = "noteTitle",
    )

    /* 图钉：出现时带回弹落位 */
    val pinTilt = remember { Animatable(if (n.pinned) -30f else 0f) }
    LaunchedEffect(n.pinned) {
        if (n.pinned) pinTilt.animateTo(0f, nbSpringPop(stiffness = 280f))
    }

    Box(
        modifier.fillMaxWidth()
            .onGloballyPositioned { onBounds(it.boundsInParent()) }
            .nbEnter(index)
            .graphicsLayer {
                translationY = lift.dp.toPx()
                scaleX = press
                scaleY = press
            }
            .clip(shape)
            .background(cardBg)
            .border(1.dp, cardBorder, shape)
            .clickable(interactionSource = interaction, indication = null) { onSelect() },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = noteColorOf(n.color)
                if (dot != Color.Transparent) {
                    Box(
                        Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dot),
                    )
                    Spacer(Modifier.size(6.dp))
                }
                if (n.pinned && n.isOwner) {
                    Box(Modifier.graphicsLayer { rotationZ = pinTilt.value }) {
                        PanelIcon("pin", 13.dp, AccentState.current.secondary)
                    }
                    Spacer(Modifier.size(5.dp))
                }
                Text(
                    n.title.ifEmpty { L["未命名笔记_044c507dcb"] },
                    color = titleColor,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!n.isOwner) {
                    Spacer(Modifier.size(6.dp))
                    LevelBadge(permText(acc.perm), permTone(acc.perm))
                }
            }
            Spacer(Modifier.size(3.dp))
            Text(
                stripMd(n.content).take(140).ifEmpty { L["空笔记_185f0fabc6"] },
                color = Palette.muted, fontSize = 11.5.sp, lineHeight = 16.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (n.folderName.isNotEmpty()) {
                    Row(
                        Modifier.widthIn(max = 110.dp)
                            .clip(RoundedCornerShape(50))
                            .background(noteColorOf(n.folderColor).takeIf { it != Color.Transparent }
                                ?.copy(alpha = 0.14f) ?: Palette.inset)
                            .padding(horizontal = 6.dp, vertical = 1.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PanelIcon(
                            "folder", 9.dp,
                            noteColorOf(n.folderColor).takeIf { it != Color.Transparent } ?: Palette.muted,
                        )
                        Spacer(Modifier.size(3.dp))
                        Text(
                            n.folderName,
                            color = Palette.muted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.size(5.dp))
                }
                if (!n.isOwner && n.ownerEmail.isNotEmpty()) {
                    Text(
                        trf("来自_0_9f6c45367f", n.ownerEmail),
                        color = Palette.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                }
                n.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3).forEach { t ->
                    Box(
                        Modifier.padding(end = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(AccentState.current.primary.copy(alpha = if (selected) 0.22f else 0.12f))
                            .border(
                                0.5.dp,
                                AccentState.current.primary.copy(alpha = if (selected) 0.4f else 0.18f),
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 7.dp, vertical = 1.5.dp),
                    ) {
                        Text(
                            t,
                            color = if (selected) AccentState.current.secondary else Palette.muted,
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    fmtRelTime(n.updatedAt),
                    color = Palette.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        /* 选中光晕：从左向右的主题色薄雾（淡入淡出跟随选中） */
        val washAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "noteWash")
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = washAlpha }
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            AccentState.current.primary.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

private fun permTone(perm: String?): Level = when (perm) {
    "read" -> Level.Warn
    "edit" -> Level.Info
    "admin" -> Level.Ok
    else -> Level.Muted
}

/* ------------------------------------------------------------------ *
 * 编辑器
 * ------------------------------------------------------------------ */

@Composable
private fun NoteEditor(
    note: NoteRec,
    access: NoteAccess,
    title: String, onTitle: (String) -> Unit,
    content: TextFieldValue, onContent: (TextFieldValue) -> Unit,
    tags: String, onTags: (String) -> Unit,
    pinned: Boolean, onPin: () -> Unit,
    preview: Boolean, onPreviewToggle: () -> Unit,
    saveState: String,
    onDelete: () -> Unit,
    onBack: (() -> Unit)?,
    onToolbar: (String) -> Unit,
    onPickMedia: (List<String>, Boolean) -> Unit,
    sharePanelOpen: Boolean,
    onToggleShare: () -> Unit,
    shares: List<JsonObject>,
    onShareEvents: (ShareEvent) -> Unit,
    inTrash: Boolean = false,
    folderText: String = "",
    folderColor: String = "",
    noteColor: String = "",
    onMove: (() -> Unit)? = null,
    onColor: ((String) -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp)) {
        /* 顶行：返回 + 动作组（标题移到下一行做成文档式大标题） */
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp)
                .then(
                    if (platformHandheld) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onBack != null) {
                IconActionBtn("arrowLeft", onClick = onBack)
            }

            /* 来自共享 / 回收站 徽标 */
            if (inTrash) {
                LevelBadge(L["回收站_64ea8751fb"], Level.Muted)
            } else if (access.perm != null) {
                LevelBadge(trf("来自_0_1_1f0e9aa76c", note.ownerEmail.ifEmpty { "?" }, permText(access.perm)), permTone(access.perm))
            }

            Spacer(Modifier.weight(1f))

            SaveIndicator(saveState, readonly = !access.canEdit)

            /* 字数统计（桌面显示；手持端优先保证动作可见） */
            if (content.text.isNotEmpty() && !platformHandheld) {
                Spacer(Modifier.size(7.dp))
                Text(
                    trf("0_字_fc4148893b", content.text.length),
                    color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }

            /* 编辑 / 预览 分段切换 */
            PreviewToggle(preview = preview, onToggle = onPreviewToggle)

            if (inTrash) {
                if (onRestore != null) IconActionBtn("refresh", onClick = onRestore)
                IconActionBtn("trash", danger = true, onClick = onDelete)
            } else {
                if (access.canManage) {
                    IconActionBtn("share", active = sharePanelOpen, onClick = onToggleShare)
                    IconActionBtn("pin", active = pinned, onClick = onPin)
                }
                if (onMove != null) IconActionBtn("folder", onClick = onMove)
                if (onDuplicate != null) IconActionBtn("copy", onClick = onDuplicate)
                if (access.canManage) IconActionBtn("trash", danger = true, onClick = onDelete)
            }
        }

        /* 文档式大标题 */
        TitleInput(title, { if (access.canEdit) onTitle(it) }, enabled = access.canEdit)

        /* 共享面板（仅 owner/admin 展开：高度展开 + 淡入） */
        AnimatedVisibility(
            visible = sharePanelOpen && access.canManage,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = nbSpringSoft(stiffness = 260f),
            ) + fadeIn(tween(260)),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = tween(200, easing = FastOutSlowInEasing),
            ) + fadeOut(tween(150)),
        ) {
            SharePanel(
                note = note, shares = shares,
                onEvent = onShareEvents,
            )
        }

        /* 归类 + 颜色：现代文档元数据行 */
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .then(
                    if (platformHandheld) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                L["归类_6a9b5537c6"], color = Palette.muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(44.dp),
            )
            val fColor = noteColorOf(folderColor).takeIf { it != Color.Transparent }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(fColor?.copy(alpha = 0.13f) ?: Palette.inset)
                    .border(1.dp, fColor?.copy(alpha = 0.35f) ?: Palette.border, RoundedCornerShape(50))
                    .then(
                        if (onMove != null && !inTrash) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onMove() } else Modifier,
                    )
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelIcon("folder", 11.dp, fColor ?: Palette.muted)
                Spacer(Modifier.size(5.dp))
                Text(
                    folderText.ifEmpty { L["未归类_9af2c7babc"] },
                    color = Palette.text, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
                if (onMove != null && !inTrash) {
                    Spacer(Modifier.size(4.dp))
                    PanelIcon("chevronDown", 10.dp, Palette.muted)
                }
            }
            Spacer(Modifier.weight(1f))
            if (onColor != null && !inTrash) {
                Text(
                    L["颜色_8ef4886033"], color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    NB_NOTE_COLORS.forEach { hex ->
                        val selectedColor = hex == noteColor
                        val c = noteColorOf(hex)
                        Box(
                            Modifier.size(15.dp).clip(RoundedCornerShape(50))
                                .background(if (hex.isEmpty()) Palette.inset else c)
                                .border(
                                    if (selectedColor) 2.dp else 1.dp,
                                    if (selectedColor) AccentState.current.primary
                                    else if (hex.isEmpty()) Palette.border2 else c.copy(alpha = 0.55f),
                                    RoundedCornerShape(50),
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { if (noteColor != hex) onColor(hex) },
                        )
                    }
                }
            }
        }

        /* 标签行（# 前缀 + 细边框胶囊） */
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(L["标签_ae0a7afece"], color = Palette.muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(44.dp))
            Field(tags, { if (access.canEdit) onTags(it) }, Modifier.weight(1f), hint = L["逗号分隔标签_如_运维_周报_8871ad8a3f"], singleLine = true, enabled = access.canEdit)
        }

        /* 工具栏（只读时不显示，同 app.js） */
        if (!preview && access.canEdit) {
            EditorToolbar(onToolbar = onToolbar, onPickMedia = onPickMedia)
            Spacer(Modifier.size(6.dp))
        }

        /* 正文 / 预览（淡入 + 轻微上移切换） */
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = preview,
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 22 }) togetherWith
                        fadeOut(tween(140))
                },
                label = "notePreview",
            ) { pv ->
                if (pv) {
                    /* 预览：纸张卡片质感（柔和投影 + 圆角面板） */
                    val pvShape = RoundedCornerShape(nbCardRadius(10.dp))
                    Box(
                        Modifier.fillMaxSize()
                            .shadow(elevation = 5.dp, shape = pvShape, clip = false)
                            .clip(pvShape)
                            .background(Palette.panel)
                            .border(1.dp, Palette.border, pvShape)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        if (content.text.isBlank()) {
                            Text(L["空笔记_185f0fabc6"], color = Palette.muted, fontSize = 12.5.sp)
                        } else {
                            NoteMarkdown(content.text, Modifier.fillMaxSize(), fontSize = 14)
                        }
                    }
                } else {
                    NoteContentField(
                        value = content, onValueChange = { if (access.canEdit) onContent(it) },
                        modifier = Modifier.fillMaxSize(),
                        hint = L["开始写_支持_markdown_标题_加_333c0c0d41"],
                        enabled = access.canEdit,
                    )
                }
            }
        }
    }
}

/** 文档式大标题输入（无边框、大字号，同 web .title-input 升级版）。 */
@Composable
private fun TitleInput(value: String, onValueChange: (String) -> Unit, enabled: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    Column {
        BasicTextField(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = if (enabled) Palette.text else Palette.muted,
                fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
            ),
            cursorBrush = SolidColor(AccentState.current.primary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    if (value.isEmpty()) {
                        Text(L["未命名笔记_044c507dcb"], color = Palette.muted.copy(alpha = 0.6f), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusEvent { focused = it.isFocused },
        )
        /* 底部强调线：聚焦时主题色从左向右生长 */
        val lineFrac by animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = tween(320, easing = FastOutSlowInEasing),
            label = "titleLineFrac",
        )
        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(1.dp).background(Palette.border)) {
            Box(
                Modifier.fillMaxWidth(lineFrac).height(1.dp)
                    .background(AccentState.current.primary),
            )
        }
    }
}

/** 编辑 / 预览 分段切换（滑动胶囊指示器）。 */
@Composable
private fun PreviewToggle(preview: Boolean, onToggle: () -> Unit) {
    NbSegmented(
        options = listOf(L["编辑_a7f814c0a4"], L["预览_de61aa8e1c"]),
        selected = if (preview) 1 else 0,
        onSelect = { onToggle() },
        compact = true,
    )
}

@Composable
private fun IconActionBtn(
    icon: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val tint = when {
        danger && hovered -> Palette.bad
        active -> AccentState.current.secondary
        hovered -> Palette.text
        else -> Palette.muted
    }
    val bg = when {
        active -> AccentState.current.primary.copy(alpha = 0.16f)
        danger && hovered -> Palette.bad.copy(alpha = 0.12f)
        hovered -> Palette.inset
        else -> Color.Transparent
    }
    /* 悬停轻微放大、按下回缩，弹簧过渡 */
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.9f
            hovered -> 1.08f
            else -> 1f
        },
        animationSpec = nbSpringPop(stiffness = 500f),
        label = "iconActScale",
    )
    Box(
        Modifier.size(30.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(6.dp)).background(bg)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        PanelIcon(icon, 15.dp, tint)
    }
}

/** 保存状态徽标（保存中圆点呼吸脉冲；保存完成回弹一下；颜色平滑过渡）。 */
@Composable
private fun SaveIndicator(saveState: String, readonly: Boolean) {
    /* 保存中：无限呼吸脉冲 */
    val pulse = rememberInfiniteTransition(label = "savePulse")
    val pulseV by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(780, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "savePulseV",
    )
    /* 保存完成：圆点 pop 回弹 */
    val pop = remember { Animatable(1f) }
    LaunchedEffect(saveState) {
        if (saveState == "saved") {
            pop.snapTo(1.6f)
            pop.animateTo(1f, nbSpringPop(stiffness = 380f))
        }
    }
    val (text, baseColor) = when {
        readonly -> L["只读_ffc1d065fb"] to Palette.muted
        saveState == "saving" -> L["保存中_6644f06197"] to Palette.warn
        saveState == "saved" -> L["已保存_cdfab96f75"] to Palette.ok
        saveState == "err" -> L["保存失败_40525a7328"] to Palette.bad
        else -> L["已保存_cdfab96f75"] to Palette.muted
    }
    val color by animateColorAsState(baseColor, tween(260), label = "saveColor")
    val dotScale = (if (saveState == "saving") 0.65f + 0.35f * pulseV else 1f) * pop.value
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(6.dp)
                .graphicsLayer { scaleX = dotScale; scaleY = dotScale }
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Spacer(Modifier.size(5.dp))
        Text(text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ *
 * 编辑器工具栏
 * ------------------------------------------------------------------ */

private data class ToolbarItem(val label: String, val cmd: String, val sep: Boolean = false, val icon: String? = null)

private val TOOLBAR_ITEMS  get() = listOf(
    ToolbarItem("H1", "h1"), ToolbarItem("H2", "h2"), ToolbarItem("H3", "h3"),
    ToolbarItem(L["s_3eb416223e"], "", sep = true),
    ToolbarItem("B", "bold"), ToolbarItem("I", "italic"), ToolbarItem("S", "strike"), ToolbarItem("</>", "inline-code"),
    ToolbarItem(L["s_3eb416223e"], "", sep = true),
    ToolbarItem(L["列表_7d9b515037"], "ul"), ToolbarItem(L["1_列表_522d9cc510"], "ol"), ToolbarItem(L["任务_3172b317f9"], "task", icon = "checkSquare"),
    ToolbarItem(L["引用_4cfa595c2a"], "quote"), ToolbarItem(L["分割线_985282b3aa"], "hr", icon = "minus"), ToolbarItem(L["表格_150074c2e8"], "table", icon = "table"),
    ToolbarItem(L["s_3eb416223e"], "", sep = true),
    ToolbarItem(L["代码_33246dda76"], "codeblock", icon = "code2"), ToolbarItem(L["链接_7150220549"], "link", icon = "link"),
    ToolbarItem(L["图片_be8da62ea1"], "image", icon = "image"), ToolbarItem(L["视频_fa4e33b698"], "video", icon = "video"),
)

@Composable
private fun EditorToolbar(onToolbar: (String) -> Unit, onPickMedia: (List<String>, Boolean) -> Unit) {
    val items = remember { TOOLBAR_ITEMS }
    val shape = RoundedCornerShape(nbCardRadius(8.dp))
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(shape)
            .background(Palette.panel)
            .border(1.dp, Palette.border, shape)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items.forEachIndexed { ti, item ->
            if (item.sep) {
                Box(
                    Modifier.padding(horizontal = 3.dp)
                        .width(1.dp).height(16.dp).background(Palette.border2),
                )
            } else {
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                /* 悬停轻微放大 + 按入场序号淡入 */
                val itemScale by animateFloatAsState(
                    targetValue = if (hovered) 1.06f else 1f,
                    animationSpec = nbSpringPop(stiffness = 500f),
                    label = "tbScale",
                )
                Row(
                    Modifier.nbEnter((ti / 2).coerceAtMost(10))
                        .graphicsLayer { scaleX = itemScale; scaleY = itemScale }
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (hovered) Palette.panel3 else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = null) {
                            when (item.cmd) {
                                "image" -> onPickMedia(listOf("image/"), true)
                                "video" -> onPickMedia(listOf("video/", "audio/"), false)
                                else -> onToolbar(item.cmd)
                            }
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.icon != null) {
                        PanelIcon(item.icon, 13.dp, if (hovered) AccentState.current.secondary else Palette.muted)
                        if (item.label.isNotEmpty()) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                item.label,
                                color = if (hovered) Palette.text else Palette.muted,
                                fontSize = 11.5.sp,
                            )
                        }
                    } else {
                        Text(
                            item.label,
                            color = if (hovered) AccentState.current.secondary else Palette.text,
                            fontSize = 11.5.sp,
                            fontFamily = if (item.label.length <= 3) FontFamily.Monospace else FontFamily.Default,
                            fontWeight = if (item.cmd == "bold") FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (item.cmd == "italic") androidx.compose.ui.text.font.FontStyle.Italic else null,
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * 内容输入框（带选区，供工具栏操作）
 * ------------------------------------------------------------------ */

@Composable
private fun NoteContentField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    hint: String,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) AccentState.current.primary else Palette.border2
    val borderColor by animateColorAsState(targetValue = border, label = "editorBorder")
    val fieldShape = RoundedCornerShape(nbCardRadius(8.dp))
    Box(
        modifier
            .clip(fieldShape)
            .background(Palette.panel)
            .border(1.dp, borderColor, fieldShape)
            .nbGlowBorder(enabled = focused, shape = fieldShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.text.isEmpty() && hint.isNotEmpty()) {
            Text(hint, color = Palette.muted.copy(alpha = 0.65f), fontSize = 13.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = TextStyle(
                color = Palette.text, fontSize = 13.5.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 22.sp,
            ),
            cursorBrush = SolidColor(AccentState.current.primary),
            modifier = Modifier.fillMaxSize().onFocusEvent { focused = it.isFocused },
        )
    }
}

/* ------------------------------------------------------------------ *
 * 共享面板
 * ------------------------------------------------------------------ */

private val PERM_OPTIONS  get() = listOf("read" to L["只读_ffc1d065fb"], "edit" to L["可编辑_bff55f2cd9"], "admin" to L["管理_4989b5cf94"])

@Composable
private fun SharePanel(
    note: NoteRec,
    shares: List<JsonObject>,
    onEvent: (ShareEvent) -> Unit,
) {
    var shareEmail by remember { mutableStateOf("") }
    var sharePermLabel by remember { mutableStateOf(L["只读_ffc1d065fb"]) }
    val shape = RoundedCornerShape(nbCardRadius(10.dp))

    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)
            .clip(shape)
            .background(Palette.inset)
            .border(1.dp, Palette.border, shape)
            .padding(12.dp),
    ) {
        /* 公开链接 */
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelIcon("link", 13.dp, AccentState.current.secondary)
            Spacer(Modifier.size(6.dp))
            Text(L["公开链接_093bb537a9"], color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            L["任何拿到链接的人_无需登录_可查看_3f77643748"],
            color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        if (note.shareCode != null) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.input)
                    .border(1.dp, Palette.border2, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelIcon("link", 14.dp, AccentState.current.secondary)
                Spacer(Modifier.size(8.dp))
                Text(
                    NoteBoxState.api.shareUrl(note.shareCode!!),
                    color = AccentState.current.secondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                GhostBtn(text = L["复制_4edd1d0087"], onClick = { onEvent(ShareEvent.CopyLink) })
                Spacer(Modifier.size(6.dp))
                DangerBtn(text = L["停止分享_55c70caa51"], onClick = { onEvent(ShareEvent.DisablePublic) })
            }
        } else {
            PrimaryBtn(text = L["开启公开链接_5594f75324"], icon = "link", onClick = { onEvent(ShareEvent.EnablePublic) })
        }

        Spacer(Modifier.size(10.dp))
        /* 共享给用户 */
        Text(L["共享给用户_f0425c626f"], color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            L["只读_仅查看_可编辑_可修改_管理_可修_7350c52b3d"],
            color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Field(shareEmail, { shareEmail = it }, Modifier.weight(1f), hint = L["对方邮箱_需已注册并验证_a4de8e4a8f"], singleLine = true)
            Box(Modifier.widthIn(max = 110.dp)) {
                SelectField(sharePermLabel, PERM_OPTIONS.map { it.second }, {
                    sharePermLabel = it
                }, placeholder = L["权限_560165a6d7"])
            }
            PrimaryBtn(text = L["共享_9a236964e8"], onClick = {
                if (shareEmail.isBlank()) {
                    NoteBoxState.toast(tr("请填写对方邮箱_41d8e0f582"), ToastKind.Warn)
                } else {
                    onEvent(ShareEvent.AddUser(shareEmail, permCodeOf(sharePermLabel)))
                    shareEmail = ""
                }
            })
        }

        Spacer(Modifier.size(6.dp))
        if (shares.isEmpty()) {
            Text(L["还没有共享给其他用户_dde3059062"], color = Palette.muted, fontSize = 11.5.sp)
        } else {
            shares.forEachIndexed { si, s ->
                val sid = s.int("id")
                val email = s.str("email")
                val perm = s.str("permission")
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).nbEnter(si),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PanelIcon("user", 13.dp, Palette.muted)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        email, color = Palette.text, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    val label = permText(perm)
                    Box(Modifier.widthIn(max = 110.dp)) {
                        SelectField(label, PERM_OPTIONS.map { it.second }, { picked ->
                            val code = permCodeOf(picked)
                            if (code != perm) onEvent(ShareEvent.SetPerm(sid, code))
                        }, placeholder = label)
                    }
                    Spacer(Modifier.size(6.dp))
                    DangerBtn(text = L["移除_2f752c005e"], onClick = { onEvent(ShareEvent.RemoveUser(sid)) })
                }
            }
        }
    }
}

private fun permCodeOf(labelOrCode: String): String = when (labelOrCode) {
    L["只读_ffc1d065fb"] -> "read"; L["可编辑_bff55f2cd9"] -> "edit"; L["管理_4989b5cf94"] -> "admin"
    else -> labelOrCode
}
