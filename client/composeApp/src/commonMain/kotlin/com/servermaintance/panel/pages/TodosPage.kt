package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.str
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.CheckRow
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
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * 待办清单 —— 复刻 notebox static/app.js「todos」视图
 * 端点：GET /api/todos?q=  POST /api/todos  PUT /api/todos/<id>
 *       POST /api/todos/<id>/toggle  DELETE /api/todos/<id>
 *       POST /api/todos/clear-done
 * 交互：快速添加(标题+日期+时间+优先级+提醒) / tab 过滤 / 分组渲染
 *       勾选完成 / 点击编辑(弹窗) / 删除 / 清理已完成 / 搜索
 * ==================================================================== */

private class TodoSubtask(o: JsonObject) {
    val id: Int = o.int("id")
    val title: String = o.str("title")
    val done: Boolean = o.int("done") != 0
    fun toJson(): JsonObject = com.servermaintance.panel.core.jsonObjectOf(
        "id" to id, "title" to title, "done" to done,
    )
}

private class TodoRec(o: JsonObject) {
    val id: Int = o.int("id")
    var title: String = o.str("title")
    var detail: String = o.str("detail")
    var status: String = o.str("status").ifEmpty { "pending" }
    var priority: Int = o.int("priority")
    var dueAt: Long = o.long("due_at").takeIf { it > 0 } ?: 0L
    var dueDate: String = o.str("due_date")
    var remind: Boolean = o.int("remind") != 0
    val createdAt: Long = o.long("created_at")
    /* 进阶字段 */
    var folderId: Int? = o.int("folder_id").takeIf { it > 0 }
    val folderName: String = o.str("folder_name")
    val folderColor: String = o.str("folder_color")
    var tags: String = o.str("tags")
    var repeat: String = o.str("repeat").ifEmpty { "none" }
    var subtasks: List<TodoSubtask> = (o["subtasks"] as? kotlinx.serialization.json.JsonArray)
        .orEmpty().mapNotNull { (it as? JsonObject)?.let(::TodoSubtask) }
    val subtasksDone: Int = o.int("subtasks_done")
    val subtasksTotal: Int = o.int("subtasks_total")
    val nextId: Int = o.int("next_id")

    fun subtasksJson(): List<JsonObject> = subtasks.map { it.toJson() }
}

private const val FILTER_TODAY = "today"
private const val FILTER_INBOX = "inbox"
private const val FILTER_UPCOMING = "upcoming"
private const val FILTER_DONE = "done"
private const val FILTER_ALL = "all"

/** 同 app.js todoMatchesFilter。 */
private fun matchesFilter(t: TodoRec, f: String): Boolean {
    val info = dueInfo(t.toJson())
    return when (f) {
        FILTER_DONE -> t.status == "done"
        FILTER_ALL -> true
        FILTER_INBOX -> t.status != "done"
        FILTER_TODAY -> t.status != "done" && (info.late || info.today)
        FILTER_UPCOMING -> t.status != "done" && info.hasDue && !info.late && !info.today
        else -> true
    }
}

private fun TodoRec.toJson(): JsonObject = com.servermaintance.panel.core.jsonObjectOf(
    "status" to status,
    "due_at" to dueAt,
    "due_date" to dueDate,
)

/** 同 app.js groupOf。 */
private fun groupOf(t: TodoRec, f: String): String {
    val info = dueInfo(t.toJson())
    return when {
        t.status == "done" -> L["已完成_e99b48a29b"]
        info.late -> L["已过期_1354374f76"]
        f == FILTER_TODAY -> L["今天_17e83cc25e"]
        info.today -> L["今天_17e83cc25e"]
        !info.hasDue -> L["未安排_0bf49114ad"]
        f == FILTER_UPCOMING -> info.label
        info.label.startsWith(L["明天_b76ce230d3"]) -> L["明天_b76ce230d3"]
        else -> L["以后_ac5d03ece3"]
    }
}

private val GROUP_ORDER  get() = listOf(L["已过期_1354374f76"], L["今天_17e83cc25e"], L["明天_b76ce230d3"], L["未安排_0bf49114ad"], L["以后_ac5d03ece3"], L["已完成_e99b48a29b"])

private fun repeatText(r: String): String = when (r) {
    "daily" -> L["每天_5e4715ffc0"]
    "weekdays" -> L["工作日_27b4a6aed2"]
    "weekly" -> L["每周_a93b55d8bf"]
    "monthly" -> L["每月_24aedc3608"]
    else -> ""
}

/* LazyColumn 条目动效规格：出现淡入 / 跨组·重排软弹簧位移 / 移除淡出 */
private val nbTodoAppear: FiniteAnimationSpec<Float> = tween(240, easing = FastOutSlowInEasing)
private val nbTodoMove: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.8f, stiffness = 380f)
private val nbTodoDisappear: FiniteAnimationSpec<Float> = tween(150)

/** 列表扁平条目：组头或待办行（key 供 LazyColumn 复用与动画）。 */
private class TodoEntry(val header: String? = null, val todo: TodoRec? = null) {
    val key: String = if (header != null) "#h:$header" else "#t:${todo!!.id}"
}

@Composable
fun TodosPage() {
    NoteGate { TodosInner() }
}

@Composable
private fun TodosInner() {
    val scope = rememberCoroutineScope()
    var todos by remember { mutableStateOf<List<TodoRec>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FILTER_TODAY) }
    var folders by remember { mutableStateOf<List<NoteFolder>>(emptyList()) }
    var listSel by remember { mutableStateOf("") } // ""=全部清单 | none | f:<id>
    var tagFilter by remember { mutableStateOf("") }
    var allTags by remember { mutableStateOf<List<String>>(emptyList()) }

    /* 快速添加 */
    var qaTitle by remember { mutableStateOf("") }
    var qaDate by remember { mutableStateOf("") }
    var qaTime by remember { mutableStateOf("") }
    var qaPriority by remember { mutableStateOf(L["中_0869071c92"]) }
    var qaRemind by remember { mutableStateOf(true) }

    /* 编辑弹窗 */
    var editing by remember { mutableStateOf<TodoRec?>(null) }

    val listParam = when {
        listSel.startsWith("f:") -> listSel.removePrefix("f:")
        listSel == "none" -> "none"
        else -> ""
    }

    suspend fun load() {
        try {
            val list = withContext(Dispatchers.IO) {
                NoteBoxState.api.listTodos(q.trim(), tag = tagFilter, folder = listParam)
            }
            todos = list.filterIsInstance<JsonObject>().map(::TodoRec)
            folders = withContext(Dispatchers.IO) {
                runCatching { NoteBoxState.api.listFolders("todo") }.getOrDefault(emptyList())
            }.toFolders()
            allTags = withContext(Dispatchers.IO) {
                runCatching { NoteBoxState.api.todoTags() }.getOrDefault(emptyList())
            }
            error = null
            NoteBoxState.refreshStats()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (!e.isAuth) error = e.message
        } catch (e: Exception) {
            error = e.message ?: tr("加载失败_f6b7a41e56")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(q, listSel, tagFilter) { delay(260); load() }

    fun toggle(t: TodoRec) {
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { NoteBoxState.api.toggleTodo(t.id) }
                val rec = TodoRec(updated)
                todos = todos.map { if (it.id == rec.id) rec else it }
                if (rec.status == "done") {
                    if (rec.nextId > 0) {
                        NoteBoxState.toast(tr("已完成_下一次重复已自动排期_2f89384a29"), ToastKind.Ok)
                        load()
                    } else {
                        NoteBoxState.toast(tr("已完成_e99b48a29b"), ToastKind.Ok)
                    }
                }
                NoteBoxState.refreshStats()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("操作失败_09e424b5e8"), ToastKind.Err)
            }
        }
    }

    fun notifyByMail(t: TodoRec) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.sendTodoMail(t.id) }
                NoteBoxState.toast(tr("提醒邮件已发送_含详情_子任务与日历邀请_7359e6549a"), ToastKind.Ok)
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("发送失败_e767d34c78"), ToastKind.Err)
            }
        }
    }

    fun createList(parent: Int?) {
        scope.launch {
            val opts = folderParentOptions(folders)
            val initial = opts.firstOrNull { it.first == parent }?.second ?: L["顶层_8dcb7af51d"]
            val r = Modal.prompt(
                L["新建清单_0f3d4d8665"],
                listOf(
                    FormField.Text("name", L["名称_1be7ae4fc2"]),
                    FormField.Select("parent", L["上级清单_9b74e98db5"], opts.map { it.second }, initial),
                    FormField.Select("color", L["颜色_8ef4886033"], folderColorNames, L["默认_c8d09cf955"]),
                ),
                okText = L["创建_fcbd093292"],
            ) ?: return@launch
            val name = r["name"].orEmpty().trim()
            if (name.isEmpty()) {
                NoteBoxState.toast(tr("清单名称不能为空_6250381cc7"), ToastKind.Err)
                return@launch
            }
            val pid = opts.firstOrNull { it.second == r["parent"] }?.first
            val hex = NB_NOTE_COLORS[folderColorNames.indexOf(r["color"] ?: L["默认_c8d09cf955"]).coerceAtLeast(0)]
            try {
                val created = withContext(Dispatchers.IO) {
                    NoteBoxState.api.createFolder(name, "todo", pid, hex)
                }
                listSel = "f:" + created.int("id")
                NoteBoxState.toast(tr("已创建清单_name_0235b26a57"), ToastKind.Ok)
                load()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("创建失败_deb3990191"), ToastKind.Err)
            }
        }
    }

    fun editList(folder: NoteFolder) {
        scope.launch {
            val blocked = folderSubtreeIds(folders, folder.id)
            val opts = folderParentOptions(folders).filter { it.first == null || it.first !in blocked }
            val initial = opts.firstOrNull { it.first == folder.parentId }?.second ?: L["顶层_8dcb7af51d"]
            val r = Modal.prompt(
                L["编辑清单_b94bb808ea"],
                listOf(
                    FormField.Text("name", L["名称_1be7ae4fc2"], folder.name),
                    FormField.Select("parent", L["上级清单_9b74e98db5"], opts.map { it.second }, initial),
                    FormField.Select("color", L["颜色_8ef4886033"], folderColorNames, colorNameOf(folder.color)),
                ),
                okText = L["保存_fadf24dbc5"],
            ) ?: return@launch
            val name = r["name"].orEmpty().trim()
            if (name.isEmpty()) {
                NoteBoxState.toast(tr("清单名称不能为空_6250381cc7"), ToastKind.Err)
                return@launch
            }
            val pid = opts.firstOrNull { it.second == r["parent"] }?.first
            val hex = NB_NOTE_COLORS[folderColorNames.indexOf(r["color"] ?: L["默认_c8d09cf955"]).coerceAtLeast(0)]
            try {
                withContext(Dispatchers.IO) {
                    NoteBoxState.api.updateFolder(folder.id, name, pid, hex, folder.position)
                }
                NoteBoxState.toast(tr("清单已更新_f933c18e79"), ToastKind.Ok)
                load()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("保存失败_40525a7328"), ToastKind.Err)
            }
        }
    }

    fun removeList(folder: NoteFolder) {
        scope.launch {
            val ok = Modal.confirm(
                L["删除清单_9ef1393048"],
                trf("删除_0_其中的待办与子清单会移动到上一级_a7634c43e7", folder.name),
                okText = L["删除_3755f56f2f"], danger = true,
            )
            if (!ok) return@launch
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.deleteFolder(folder.id) }
                if (listSel.startsWith("f:")) {
                    val selId = listSel.removePrefix("f:").toIntOrNull()
                    if (selId != null && selId in folderSubtreeIds(folders, folder.id)) listSel = ""
                }
                NoteBoxState.toast(tr("清单已删除_内容已移到上一级_71c8380991"), ToastKind.Ok)
                load()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("删除失败_72250c5922"), ToastKind.Err)
            }
        }
    }

    fun delete(t: TodoRec) {
        scope.launch {
            val ok = Modal.confirm(L["删除事项_baf69ed21f"], trf("删除事项_0_fd51d58bcb", t.title), okText = L["删除_3755f56f2f"], danger = true)
            if (!ok) return@launch
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.deleteTodo(t.id) }
                todos = todos.filterNot { it.id == t.id }
                NoteBoxState.toast(tr("已删除_fb5fe1e266"))
                NoteBoxState.refreshStats()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("删除失败_72250c5922"), ToastKind.Err)
            }
        }
    }

    PageScaffold(
        title = L["待办清单_febc09d1b1"],
        desc = L["notebox_待办事项_支持截止时间与邮件提醒_f64366f1ad"],
        actions = { NoteUserChip() },
        scrollBody = false, // 列表区独立滚动、占满剩余高度
    ) {
        val stats = com.servermaintance.panel.core.NoteBoxState.stats.value
        NbStatRow(stats)

        val listLabels = listOf(L["全部清单_54e487ccc0"], L["未归类_9af2c7babc"]) + folderFlat(folders).map { folderPathName(folders, it.id) }
        val currentListLabel = when {
            listSel == "none" -> L["未归类_9af2c7babc"]
            listSel.startsWith("f:") -> folderPathName(folders, listSel.removePrefix("f:").toIntOrNull())
            else -> L["全部清单_54e487ccc0"]
        }
        val selectedList = folders.firstOrNull { "f:${it.id}" == listSel }
        fun pickList(label: String) {
            when (label) {
                L["全部清单_54e487ccc0"] -> listSel = ""
                L["未归类_9af2c7babc"] -> listSel = "none"
                L["新建清单_76f9b5e92e"] -> createList(selectedList?.id)
                else -> folderFlat(folders).firstOrNull { folderPathName(folders, it.id) == label }
                    ?.let { listSel = "f:${it.id}" }
            }
        }

        /* 快速添加编排器（同 app.js #quick-add；聚焦流光边框 + 快捷日期胶囊） */
        val qaShape = RoundedCornerShape(nbCardRadius(12.dp))
        var qaFocused by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().padding(bottom = 12.dp)
                .nbEnter(5)
                .clip(qaShape)
                .background(Palette.panel)
                .border(1.dp, Palette.border, qaShape)
                .nbGlowBorder(enabled = qaFocused, shape = qaShape)
                .padding(if (platformHandheld) 10.dp else 12.dp),
        ) {
            /* 第一行：加号徽标 + 标题输入 + 添加按钮 */
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /* 加号徽标：标题就绪时开始呼吸，暗示「可以添加了」 */
                val qaReady = qaTitle.isNotBlank()
                val readyPulse by rememberInfiniteTransition(label = "qaReady").animateFloat(
                    initialValue = 0.96f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "qaReadyV",
                )
                Box(
                    Modifier.size(28.dp)
                        .graphicsLayer {
                            val s = if (qaReady) readyPulse else 1f
                            scaleX = s; scaleY = s
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentState.current.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PanelIcon("plus", 15.dp, AccentState.current.secondary)
                }
                Box(Modifier.weight(1f).onFocusEvent { qaFocused = it.isFocused }) {
                    Field(
                        qaTitle, { qaTitle = it }, Modifier.fillMaxWidth(),
                        hint = L["想到什么_立刻记下来_c54f483edc"], singleLine = true,
                    )
                }
                Box(Modifier.nbPressScale(0.96f)) {
                    PrimaryBtn(text = L["添加_94191ce210"], icon = "plus", onClick = {
                    val title = qaTitle.trim()
                    if (title.isEmpty()) return@PrimaryBtn
                    scope.launch {
                        try {
                            var dueAt: Long? = null
                            var dueDate: String? = null
                            val d = qaDate.trim()
                            val t = qaTime.trim()
                            if (d.isNotEmpty() && t.isNotEmpty()) {
                                dueAt = epochOf(d, t)
                                if (dueAt == null) {
                                    NoteBoxState.toast(tr("日期_时间格式不对_yyyy_mm_dd_与_h_8b0d431063"), ToastKind.Warn)
                                    return@launch
                                }
                            } else if (d.isNotEmpty()) {
                                if (!Regex("^\\d{4}-\\d{2}-\\d{2}\$").containsMatchIn(d)) {
                                    NoteBoxState.toast(tr("日期格式应为_yyyy_mm_dd_565fb221c7"), ToastKind.Warn)
                                    return@launch
                                }
                                dueDate = d
                            } else if (t.isNotEmpty()) {
                                // 只给时间 = 今天该时刻
                                dueAt = epochOf(localDateStr(), t) ?: run {
                                    NoteBoxState.toast(tr("时间格式应为_hh_mm_8820852ef2"), ToastKind.Warn)
                                    return@launch
                                }
                            }
                            val pri = when (qaPriority) { L["高_b096b3f5ac"] -> 3; L["低_b9ee259b7f"] -> 1; else -> 2 }
                            val n = withContext(Dispatchers.IO) {
                                NoteBoxState.api.createTodo(
                                    title, priority = pri, dueAt = dueAt, dueDate = dueDate,
                                    remind = qaRemind, folderId = listParam.toIntOrNull(),
                                )
                            }
                            todos = listOf(TodoRec(n)) + todos
                            qaTitle = ""; qaDate = ""; qaTime = ""
                            NoteBoxState.refreshStats()
                        } catch (e: Exception) {
                            NoteBoxState.toast(e.message ?: tr("添加失败_9d563ebbbd"), ToastKind.Err)
                        }
                    }
                    })
                }
            }
            Spacer(Modifier.size(9.dp))
            /* 第二行：快捷日期胶囊（一键今天/明天/后天）+ 日期/时间/优先级 */
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val todayStr = localDateStr()
                val tomorrowStr = localDateStr(1)
                val dayAfterStr = localDateStr(2)
                NbQuickChip(L["今天_17e83cc25e"], qaDate == todayStr) { qaDate = if (qaDate == todayStr) "" else todayStr }
                NbQuickChip(L["明天_b76ce230d3"], qaDate == tomorrowStr) { qaDate = if (qaDate == tomorrowStr) "" else tomorrowStr }
                NbQuickChip(L["后天_639e8f00b7"], qaDate == dayAfterStr) { qaDate = if (qaDate == dayAfterStr) "" else dayAfterStr }
                Box(Modifier.widthIn(max = 124.dp)) {
                    Field(qaDate, { qaDate = it }, label = null, hint = L["yyyy_mm_dd_d3f8f7b810"], mono = true, singleLine = true)
                }
                Box(Modifier.widthIn(max = 92.dp)) {
                    Field(qaTime, { qaTime = it }, label = null, hint = L["hh_mm_9668f5afb0"], mono = true, singleLine = true)
                }
                Box(Modifier.widthIn(max = 92.dp)) {
                    SelectField(qaPriority, listOf(L["低_b9ee259b7f"], L["中_0869071c92"], L["高_b096b3f5ac"]), { qaPriority = it })
                }
                Box(Modifier.widthIn(max = 140.dp)) {
                    SelectField(currentListLabel, listLabels + L["新建清单_76f9b5e92e"], { pickList(it) }, placeholder = L["清单_01af8c6eed"])
                }
                if (selectedList != null) {
                    TodoMiniIcon("edit", L["编辑清单_b94bb808ea"]) { editList(selectedList) }
                    TodoMiniIcon("trash", L["删除清单_9ef1393048"], danger = true) { removeList(selectedList) }
                }
                if (!platformHandheld) {
                    Spacer(Modifier.weight(1f))
                    CheckRow(qaRemind, { qaRemind = it }, L["到期邮件提醒_4e76e9fec7"])
                }
            }
            if (platformHandheld) {
                /* 手持端第三行：优先级 + 提醒 */
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        SelectField(qaPriority, listOf(L["低_b9ee259b7f"], L["中_0869071c92"], L["高_b096b3f5ac"]), { qaPriority = it })
                    }
                    CheckRow(qaRemind, { qaRemind = it }, L["到期提醒_ce425cc527"])
                }
            }
        }

        /* 搜索 + tabs（滑动胶囊分段控件，带计数徽标；同 app.js #todo-tabs） */
        val filterLabels = listOf(L["今天_17e83cc25e"], L["未完成_b61b08aec3"], L["即将_f48bcfb627"], L["已完成_e99b48a29b"], L["全部_778fc8f994"])
        val filterKeys = listOf(FILTER_TODAY, FILTER_INBOX, FILTER_UPCOMING, FILTER_DONE, FILTER_ALL)
        val filterCounts = filterKeys.map { f -> todos.count { matchesFilter(it, f) } }
        val selectedFilterIdx = filterKeys.indexOf(filter).coerceAtLeast(0)
        if (platformHandheld) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).nbEnter(6)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchField(q, { q = it }, Modifier.weight(1f), placeholder = L["搜索事项_a5b34743c2"])
                    Box(Modifier.widthIn(max = 120.dp)) {
                        SelectField(
                            if (tagFilter.isEmpty()) L["全部标签_ffd92f5524"] else "#$tagFilter",
                            listOf(L["全部标签_ffd92f5524"]) + allTags,
                            { tagFilter = if (it == L["全部标签_ffd92f5524"]) "" else it },
                            placeholder = L["标签_ae0a7afece"],
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    NbSegmented(
                        filterLabels, selectedFilterIdx,
                        onSelect = { filter = filterKeys[it] },
                        compact = true, badges = filterCounts,
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).nbEnter(6),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchField(q, { q = it }, Modifier.weight(1f), placeholder = L["搜索事项_a5b34743c2"])
                Box(Modifier.widthIn(max = 130.dp)) {
                    SelectField(
                        if (tagFilter.isEmpty()) L["全部标签_ffd92f5524"] else "#$tagFilter",
                        listOf(L["全部标签_ffd92f5524"]) + allTags,
                        { tagFilter = if (it == L["全部标签_ffd92f5524"]) "" else it },
                        placeholder = L["标签_ae0a7afece"],
                    )
                }
                NbSegmented(
                    filterLabels, selectedFilterIdx,
                    onSelect = { filter = filterKeys[it] },
                    badges = filterCounts,
                )
            }
        }

        when {
            loading && todos.isEmpty() && error == null -> {
                /* 骨架屏加载：列表形状的微光占位（替代转圈） */
                NbSkeletonList(
                    rows = 7,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    rowHeight = 46.dp,
                )
            }
            error != null && todos.isEmpty() -> ErrorState(message = error!!, onRetry = {
                scope.launch { loading = true; load() }
            })
            else -> {
                val list = todos.filter { matchesFilter(it, filter) }

                /* 完成进度：渐变细条以软弹簧追随 done/total（全部完成时整条变绿） */
                if (todos.isNotEmpty()) {
                    val doneN = todos.count { it.status == "done" }
                    val total = todos.size
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).nbEnter(6)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                L["完成进度_722d50c7b4"],
                                color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "$doneN/$total · ${doneN * 100 / total}%",
                                color = if (doneN == total) Palette.ok else Palette.muted,
                                fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.size(5.dp))
                        NbProgressBar(doneN.toFloat() / total.toFloat())
                    }
                }

                if (list.isEmpty()) {
                    NbEmptyGlow(
                        icon = "sparkle",
                        title = if (filter == FILTER_DONE) L["还没有完成的事项_a290b58977"] else L["这里空空如也_c631010e57"],
                        subtitle = L["在上方输入框快速添加一条待办_55d72d1b70"],
                        Modifier.padding(top = 24.dp),
                    )
                } else {
                    /* 分组扁平化后进入 LazyColumn：animateItem 让跨组移动
                     * （勾选完成/取消）、新增、删除都有平滑的位移与淡入淡出 */
                    val groups = list.groupBy { groupOf(it, filter) }
                    val gkeys = GROUP_ORDER.filter { it in groups.keys } +
                        groups.keys.filterNot { it in GROUP_ORDER }.sorted()
                    val entries = buildList {
                        gkeys.forEach { g ->
                            add(TodoEntry(header = g))
                            groups[g]?.forEach { add(TodoEntry(todo = it)) }
                        }
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().nbFadingEdges(top = 14.dp, bottom = 14.dp),
                    ) {
                        items(entries, key = { it.key }, contentType = { it.todo == null }) { e ->
                            val h = e.header
                            if (h != null) {
                                TodoGroupHeader(
                                    h, groups[h]?.size ?: 0,
                                    Modifier.animateItem(nbTodoAppear, nbTodoMove, nbTodoDisappear),
                                )
                            } else {
                                val t = e.todo
                                if (t != null) {
                                    Box(Modifier.animateItem(nbTodoAppear, nbTodoMove, nbTodoDisappear)) {
                                        TodoRow(t, { toggle(t) }, { editing = t }, { delete(t) })
                                    }
                                }
                            }
                        }
                    }
                }

                /* 页脚：计数 + 清理已完成（同 app.js .todo-foot；置底分隔线） */
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val pending = todos.count { it.status == "pending" }
                    val done = todos.count { it.status == "done" }
                    Text(
                        trf("未完成_0_已完成_1_38023d83b8", nbCountUp(pending), nbCountUp(done)),
                        color = Palette.muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    GhostBtn(text = L["清理已完成_149b3c638f"], icon = "trash", onClick = {
                        scope.launch {
                            val n = todos.count { it.status == "done" }
                            if (n == 0) {
                                NoteBoxState.toast(tr("没有已完成的事项_2cda631543"))
                                return@launch
                            }
                            val ok = Modal.confirm(L["清理已完成_149b3c638f"], trf("删除全部_0_条已完成事项_edea779a77", n), okText = L["清理_907c3945d8"], danger = true)
                            if (!ok) return@launch
                            try {
                                val deleted = withContext(Dispatchers.IO) { NoteBoxState.api.clearDone() }
                                todos = todos.filter { it.status != "done" }
                                NoteBoxState.toast(tr("已清理_deleted_条_4c23a20ddb"))
                                NoteBoxState.refreshStats()
                            } catch (e: Exception) {
                                NoteBoxState.toast(e.message ?: tr("清理失败_6f2390db7a"), ToastKind.Err)
                            }
                        }
                    })
                }
            }
        }
    }

    /* 编辑弹窗（同 app.js #modal 编辑事项） */
    editing?.let { t ->
        TodoEditDialog(
            todo = t,
            folders = folders,
            onClose = { editing = null },
            onNotify = { notifyByMail(t) },
            onSave = { title, detail, pri, remind, done, dueAt, dueDate, tags, subs, repeat, folderId ->
                scope.launch {
                    try {
                        val updated = withContext(Dispatchers.IO) {
                            NoteBoxState.api.updateTodo(
                                t.id, title, detail, pri, dueAt, dueDate, remind,
                                status = if (done) "done" else "pending",
                                tags = tags, subtasks = subs, repeat = repeat, folderId = folderId,
                            )
                        }
                        val rec = TodoRec(updated)
                        todos = todos.map { if (it.id == rec.id) rec else it }
                        editing = null
                        if (rec.nextId > 0) {
                            NoteBoxState.toast(tr("已保存_下一次重复已自动排期_866a056587"), ToastKind.Ok)
                            load()
                        } else {
                            NoteBoxState.toast(tr("已保存_cdfab96f75"))
                        }
                        NoteBoxState.refreshStats()
                    } catch (e: Exception) {
                        NoteBoxState.toast(e.message ?: tr("保存失败_40525a7328"), ToastKind.Err)
                    }
                }
            },
            onDelete = { editing = null; delete(t) },
        )
    }
}

/** `yyyy-MM-dd` + `HH:mm` -> epoch 秒（本地时区）；格式不对返回 null。 */
private fun epochOf(date: String, time: String): Long? {
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(date.trim()) ?: return null
    val hm = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim()) ?: return null
    val (y, mo, d) = m.destructured
    val (h, mi) = hm.destructured
    return try {
        val ldt = LocalDateTime(
            y.toInt(), mo.toInt(), d.toInt(),
            h.toInt().coerceIn(0, 23), mi.toInt().coerceIn(0, 59),
        )
        ldt.toInstant(TimeZone.currentSystemDefault()).epochSeconds
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun TodoMiniIcon(icon: String, hint: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        PanelIcon(icon, 12.dp, if (danger) Palette.bad else Palette.muted)
    }
}

/* ------------------------------------------------------------------ *
 * 分组标题（已过期轻微呼吸脉冲；右侧渐隐分割线）
 * ------------------------------------------------------------------ */

@Composable
private fun TodoGroupHeader(g: String, count: Int, modifier: Modifier = Modifier) {
    val overdue = g == L["已过期_1354374f76"]
    val overduePulse by rememberInfiniteTransition(label = "overdue").animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "overduePulse",
    )
    Row(
        modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$g · $count",
            color = if (overdue) Palette.bad else Palette.muted,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
            modifier = Modifier.alpha(if (overdue) overduePulse else 1f),
        )
        Spacer(Modifier.size(9.dp))
        /* 渐隐分割线（同 .group-head::after linear-gradient） */
        Box(
            Modifier.weight(1f).height(1.dp).background(
                Brush.horizontalGradient(
                    listOf(
                        (if (overdue) Palette.bad else Palette.muted).copy(alpha = 0.4f),
                        Color.Transparent,
                    ),
                ),
            ),
        )
    }
}

/* ------------------------------------------------------------------ *
 * 一行待办（现代卡片：悬停浮起 + 按压微缩 + 勾选弹跳 + 完成脉冲）
 * ------------------------------------------------------------------ */

@Composable
private fun TodoRow(t: TodoRec, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val info = dueInfo(t.toJson())
    val done = t.status == "done"
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val priColor = priTone(t.priority).let {
        when (it) {
            Level.Bad -> Palette.bad
            Level.Warn -> Palette.warn
            Level.Info -> Palette.info
            else -> Palette.border2
        }
    }
    val rowShape = RoundedCornerShape(nbCardRadius())

    /* 悬停浮起 / 按压微缩 / 表面色与描边过渡（逾期行叠加淡淡的红晕） */
    val lateTint = info.late && !done
    val lift by animateFloatAsState(if (hovered && !done) -1.5f else 0f, nbSpringSoft(stiffness = 500f), label = "todoLift")
    val pressScale by animateFloatAsState(if (pressed) 0.99f else 1f, nbSpringPop(stiffness = 600f), label = "todoPress")
    val rowBg by animateColorAsState(
        targetValue = when {
            hovered && lateTint -> Palette.bad.copy(alpha = 0.09f)
            hovered -> Palette.panel2
            lateTint -> Palette.bad.copy(alpha = 0.05f)
            else -> Palette.panel
        },
        animationSpec = tween(220),
        label = "todoBg",
    )
    val rowBorder by animateColorAsState(
        targetValue = when {
            pressed -> AccentState.current.primary.copy(alpha = 0.55f)
            hovered -> Palette.border2
            else -> Palette.border
        },
        animationSpec = tween(180),
        label = "todoBorder",
    )
    val titleColor by animateColorAsState(
        targetValue = if (done) Palette.muted else Palette.text,
        animationSpec = tween(280),
        label = "todoTitle",
    )

    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp)
            .graphicsLayer {
                translationY = lift.dp.toPx()
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(rowShape)
            .background(rowBg)
            .border(1.dp, rowBorder, rowShape)
            .clickable(interactionSource = interaction, indication = null) { onEdit() }
            .padding(start = 0.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /* 优先级左侧色条（高=红 中=黄 低=蓝 无=灰；悬停轻微加宽） */
        val priWidth by animateDpAsState(
            targetValue = if (hovered) 4.dp else 3.dp,
            animationSpec = tween(180),
            label = "priWidth",
        )
        Box(
            Modifier.padding(start = 1.dp).width(priWidth).height(38.dp)
                .clip(RoundedCornerShape(50))
                .background(priColor.copy(alpha = if (t.priority >= 1) 0.9f else 0.25f)),
        )
        Spacer(Modifier.size(10.dp))
        /* 复选框：圆形填充展开 + 对勾弹入 + 完成脉冲圈 */
        NbCheckCircle(done = done, onToggle = onToggle)
        Spacer(Modifier.size(10.dp))
        /* 完成后整块内容平滑退隐（配合删除线） */
        val contentAlpha by animateFloatAsState(if (done) 0.62f else 1f, tween(260), label = "todoContentA")
        Column(Modifier.weight(1f).graphicsLayer { alpha = contentAlpha }) {
            Text(
                t.title,
                color = titleColor,
                fontSize = 13.5.sp, lineHeight = 19.sp,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (info.hasDue) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                if (info.late) Palette.bad.copy(alpha = 0.12f)
                                else if (info.today) Palette.warn.copy(alpha = 0.12f)
                                else Palette.inset,
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 7.dp, vertical = 1.5.dp),
                    ) {
                        /* 逾期：红点呼吸脉冲提醒视线 */
                        if (info.late && !done) {
                            val dotPulse by rememberInfiniteTransition(label = "lateDot").animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(tween(760, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                                label = "lateDotV",
                            )
                            Box(
                                Modifier.size(5.dp)
                                    .graphicsLayer { alpha = dotPulse }
                                    .clip(CircleShape)
                                    .background(Palette.bad),
                            )
                            Spacer(Modifier.size(4.dp))
                        }
                        PanelIcon(
                            "calendar", 11.dp,
                            if (info.late) Palette.bad else if (info.today) Palette.warn else Palette.muted,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            info.label,
                            color = if (info.late) Palette.bad else if (info.today) Palette.warn else Palette.muted,
                            fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = if (info.late) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                LevelBadge(priLabel(t.priority), priTone(t.priority))
                if (t.remind && info.hasDue && !done) {
                    PanelIcon("mail", 11.dp, Palette.muted)
                }
                if (t.detail.isNotEmpty()) {
                    Text(
                        t.detail.take(60),
                        color = Palette.muted, fontSize = 10.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            val tags = t.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (t.folderName.isNotEmpty() || t.subtasksTotal > 0 || t.repeat != "none" || tags.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (t.folderName.isNotEmpty()) {
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(Palette.inset)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PanelIcon("folder", 9.dp, Palette.muted)
                            Spacer(Modifier.size(3.dp))
                            Text(
                                t.folderName, color = Palette.muted, fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 88.dp),
                            )
                        }
                    }
                    if (t.subtasksTotal > 0) {
                        val subsDone = t.subtasks.count { it.done }
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(AccentState.current.primary.copy(alpha = 0.10f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PanelIcon("checkSquare", 9.dp, AccentState.current.secondary)
                            Spacer(Modifier.size(3.dp))
                            Text(
                                "$subsDone/${t.subtasksTotal}",
                                color = AccentState.current.secondary,
                                fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    if (t.repeat != "none") {
                        PanelIcon("refresh", 10.dp, Palette.muted)
                        Text(
                            repeatText(t.repeat),
                            color = Palette.muted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    tags.take(2).forEach { tag ->
                        Text(
                            "#$tag",
                            color = Palette.muted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Palette.inset)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        /* 删除按钮：悬停浮现（alpha + 缩放；手持常显） */
        val revealTarget = if (platformHandheld || hovered) 1f else 0f
        val reveal by animateFloatAsState(revealTarget, tween(170, easing = FastOutSlowInEasing), label = "delReveal")
        Box(
            Modifier.size(28.dp)
                .graphicsLayer {
                    alpha = reveal
                    scaleX = 0.7f + 0.3f * reveal
                    scaleY = 0.7f + 0.3f * reveal
                }
                .then(
                    if (reveal > 0.05f) {
                        Modifier.clip(RoundedCornerShape(5.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onDelete() }
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            PanelIcon("trash", 14.dp, if (hovered) Palette.bad else Palette.muted)
        }
    }
}

/**
 * 圆形勾选框：勾选时主题色从中心展开、对勾弹入、整体 pop 一下，
 * 完成瞬间额外向外扩散一圈脉冲（NbPulseRing）。
 */
@Composable
private fun NbCheckCircle(done: Boolean, onToggle: () -> Unit) {
    val accent = AccentState.current.primary
    var popReady by remember { mutableStateOf(false) }
    var pop by remember { mutableStateOf(false) }
    var doneCount by remember { mutableStateOf(0) }
    LaunchedEffect(done) {
        if (popReady) {
            if (done) doneCount++ // 仅「完成」方向扩散脉冲
            pop = true
            delay(110)
            pop = false
        } else {
            popReady = true // 首次组合不弹，避免入场时整排抖动
        }
    }
    val popScale by animateFloatAsState(
        targetValue = if (pop) 1.16f else 1f,
        animationSpec = nbSpringPop(),
        label = "cbPop",
    )
    val fillScale by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = nbSpringSoft(stiffness = 430f),
        label = "cbFill",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = nbSpringPop(stiffness = 550f),
        label = "cbCheck",
    )
    val ringColor by animateColorAsState(
        targetValue = if (done) accent else Palette.border2,
        animationSpec = tween(220),
        label = "cbRing",
    )
    Box(Modifier.graphicsLayer { scaleX = popScale; scaleY = popScale }) {
        /* 完成脉冲圈（铺在勾选圆外圈） */
        NbPulseRing(trigger = doneCount, color = accent, modifier = Modifier.size(20.dp))
        Box(
            Modifier.size(20.dp)
                .clip(CircleShape)
                .background(Palette.input)
                .border(1.6.dp, ringColor, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            /* 主题色填充：从中心缩放展开 */
            if (fillScale > 0.01f) {
                Box(
                    Modifier.size(20.dp)
                        .graphicsLayer { scaleX = fillScale; scaleY = fillScale }
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            /* 对勾：弹入 */
            if (checkScale > 0.01f) {
                Box(Modifier.graphicsLayer { scaleX = checkScale; scaleY = checkScale }) {
                    PanelIcon("check", 12.dp, Palette.white, stroke = 2.8f)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * 编辑弹窗
 * ------------------------------------------------------------------ */

@Composable
private fun TodoEditDialog(
    todo: TodoRec,
    folders: List<NoteFolder> = emptyList(),
    onClose: () -> Unit,
    onSave: (
        title: String, detail: String, priority: Int, remind: Boolean, done: Boolean,
        dueAt: Long?, dueDate: String?, tags: String, subtasks: List<JsonObject>, repeat: String, folderId: Int?,
    ) -> Unit,
    onNotify: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    var title by remember { mutableStateOf(todo.title) }
    var detail by remember { mutableStateOf(todo.detail) }
    var pri by remember { mutableStateOf(priLabel(todo.priority)) }
    var remind by remember { mutableStateOf(todo.remind) }
    var done by remember { mutableStateOf(todo.status == "done") }
    var date by remember { mutableStateOf(todo.dueDate) }
    var time by remember { mutableStateOf("") }
    var dueAtInit by remember { mutableStateOf(todo.dueAt) }
    var dueDateInit by remember { mutableStateOf(todo.dueDate) }
    var tags by remember { mutableStateOf(todo.tags) }
    var repeat by remember { mutableStateOf(todo.repeat) }
    var folderId by remember { mutableStateOf(todo.folderId) }
    var subtasks by remember { mutableStateOf(todo.subtasks) }
    var newSub by remember { mutableStateOf("") }
    fun addSubtask() {
        val t = newSub.trim()
        if (t.isEmpty()) return
        val nid = (subtasks.maxOfOrNull { it.id } ?: 0) + 1
        subtasks = subtasks + TodoSubtask(
            com.servermaintance.panel.core.jsonObjectOf("id" to nid, "title" to t, "done" to false),
        )
        newSub = ""
    }

    // due_at 预填为 date+time
    LaunchedEffect(todo.id) {
        if (todo.dueAt > 0) {
            val ldt = Instant.fromEpochSeconds(todo.dueAt)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            date = ldt.date.toString()
            time = (if (ldt.hour < 10) "0" else "") + ldt.hour + ":" + (if (ldt.minute < 10) "0" else "") + ldt.minute
        }
    }

    /* 入场：遮罩淡入 + 弹窗上浮缩放（软弹簧） */
    val appear = remember { Animatable(0f) }
    LaunchedEffect(todo.id) { appear.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    val ap = appear.value

    Box(
        Modifier.fillMaxSize().zIndex(70f)
            .graphicsLayer { alpha = ap }
            .background(Palette.scrim.copy(alpha = Palette.scrim.alpha * ap))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.padding(horizontal = if (platformHandheld) 14.dp else 24.dp)
                .widthIn(max = 460.dp).fillMaxWidth()
                .graphicsLayer {
                    scaleX = 0.94f + 0.06f * ap
                    scaleY = 0.94f + 0.06f * ap
                    translationY = (1f - ap) * 18.dp.toPx()
                }
                .clip(shape)
                .background(Palette.panel)
                .border(1.dp, Palette.border2, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            /* 弹窗内容错落入场：标题 → 字段 → 按钮 依次浮现 */
            Row(
                Modifier.fillMaxWidth().nbEnter(0),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                        .background(AccentState.current.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PanelIcon("checkSquare", 13.dp, AccentState.current.secondary)
                }
                Spacer(Modifier.size(8.dp))
                Text(L["编辑事项_6ea6964be4"], color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.size(12.dp))
            Field(title, { title = it }, Modifier.fillMaxWidth().nbEnter(1), label = L["标题_748d7dc7e3"], singleLine = true)
            Spacer(Modifier.size(8.dp))
            Field(detail, { detail = it }, Modifier.fillMaxWidth().nbEnter(1), label = L["备注_e0361480e3"], hint = L["可选_2f3b613ff2"], singleLine = false)
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(tags, { tags = it }, Modifier.weight(1f), label = L["标签_ae0a7afece"], hint = L["逗号分隔_c3f6abcdf5"], singleLine = true)
                Box(Modifier.weight(1f)) {
                    val listLabels = listOf(L["未归类_9af2c7babc"]) + folderFlat(folders).map { folderPathName(folders, it.id) }
                    val current = folderPathName(folders, folderId)
                    SelectField(current, listLabels, { label ->
                        folderId = folderFlat(folders).firstOrNull { folderPathName(folders, it.id) == label }?.id
                    }, label = L["清单_01af8c6eed"])
                }
            }
            Spacer(Modifier.size(8.dp))
            /* 截止日期：快捷胶囊一键填充 + 手动输入 */
            Column(Modifier.fillMaxWidth().nbEnter(2)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 5.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val todayStr = localDateStr()
                    val tomorrowStr = localDateStr(1)
                    val dayAfterStr = localDateStr(2)
                    NbQuickChip(L["今天_17e83cc25e"], date == todayStr) { date = if (date == todayStr) "" else todayStr }
                    NbQuickChip(L["明天_b76ce230d3"], date == tomorrowStr) { date = if (date == tomorrowStr) "" else tomorrowStr }
                    NbQuickChip(L["后天_639e8f00b7"], date == dayAfterStr) { date = if (date == dayAfterStr) "" else dayAfterStr }
                    if (date.isNotEmpty() || time.isNotEmpty()) {
                        NbQuickChip(L["清除_7b15e5e8e7"], false) { date = ""; time = "" }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(date, { date = it }, Modifier.weight(1f), label = L["截止日期_9b3177f0b7"], hint = L["yyyy_mm_dd_d3f8f7b810"], mono = true, singleLine = true)
                    Field(time, { time = it }, Modifier.weight(1f), label = L["截止时间_864048b32f"], hint = L["hh_mm_9668f5afb0"], mono = true, singleLine = true)
                }
            }
            Spacer(Modifier.size(10.dp))
            /* 优先级：滑动胶囊分段选择（替代下拉框，单手可达） */
            Column(Modifier.nbEnter(3)) {
                Text(
                    L["优先级_7cabe771eb"],
                    color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
                val priOptions = listOf(L["无_72077749f7"], L["低_b9ee259b7f"], L["中_0869071c92"], L["高_b096b3f5ac"])
                NbSegmented(
                    options = priOptions,
                    selected = priOptions.indexOf(pri).coerceAtLeast(0),
                    onSelect = { pri = priOptions[it] },
                    compact = true,
                )
            }
            Spacer(Modifier.size(10.dp))
            /* 重复规则 */
            Column(Modifier.nbEnter(3)) {
                Text(
                    L["重复_6e708ba759"],
                    color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
                val repOptions = listOf(L["不重复_d081b5aa6a"], L["每天_5e4715ffc0"], L["工作日_27b4a6aed2"], L["每周_a93b55d8bf"], L["每月_24aedc3608"])
                val repKeys = listOf("none", "daily", "weekdays", "weekly", "monthly")
                NbSegmented(
                    options = repOptions,
                    selected = repKeys.indexOf(repeat).coerceAtLeast(0),
                    onSelect = { repeat = repKeys[it] },
                    compact = true,
                )
            }
            Spacer(Modifier.size(10.dp))
            /* 子任务清单 */
            Column(Modifier.fillMaxWidth().nbEnter(3)) {
                Text(
                    L["子任务_2a8ce33ff0"] + if (subtasks.isNotEmpty()) " · ${subtasks.count { it.done }}/${subtasks.size}" else "",
                    color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
                subtasks.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NbCheckCircle(done = s.done, onToggle = {
                            subtasks = subtasks.map {
                                if (it.id == s.id) TodoSubtask(
                                    com.servermaintance.panel.core.jsonObjectOf(
                                        "id" to it.id, "title" to it.title, "done" to !it.done,
                                    ),
                                ) else it
                            }
                        })
                        Spacer(Modifier.size(8.dp))
                        Text(
                            s.title,
                            color = if (s.done) Palette.muted else Palette.text,
                            fontSize = 12.5.sp,
                            textDecoration = if (s.done) TextDecoration.LineThrough else null,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TodoMiniIcon("close", L["删除子任务_165f2995e1"]) {
                            subtasks = subtasks.filterNot { it.id == s.id }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        Field(
                            newSub, { newSub = it }, Modifier.fillMaxWidth(),
                            hint = L["添加子任务_bcec5e13ad"], singleLine = true,
                        )
                    }
                    TodoMiniIcon("plus", L["添加子任务_1f50119c5b"]) { addSubtask() }
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.nbEnter(4)) {
                CheckRow(remind, { remind = it }, L["到期邮件提醒_4e76e9fec7"])
                CheckRow(done, { done = it }, L["已完成_e99b48a29b"])
            }
            Spacer(Modifier.size(14.dp))
            Row(
                Modifier.fillMaxWidth().nbEnter(5),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onNotify != null && todo.status != "done") {
                    GhostBtn(text = L["发送详情邮件_ad8277f7a5"], onClick = onNotify)
                    Spacer(Modifier.weight(1f))
                }
                GhostBtn(text = L["win.dialog.cancel"], onClick = onClose)
                Spacer(Modifier.size(8.dp))
                DangerBtn(text = L["删除_3755f56f2f"], onClick = onDelete)
                Spacer(Modifier.size(8.dp))
                PrimaryBtn(text = L["保存_fadf24dbc5"], onClick = {
                    val d = date.trim()
                    val tm = time.trim()
                    var dueAt: Long? = null
                    var dueDate: String? = null
                    if (d.isNotEmpty() && tm.isNotEmpty()) {
                        dueAt = epochOf(d, tm) ?: dueAtInit.takeIf { d == dueDateInit }
                        if (dueAt == null) {
                            NoteBoxState.toast(tr("日期_时间格式不对_yyyy_mm_dd_与_h_8b0d431063"), ToastKind.Warn)
                            return@PrimaryBtn
                        }
                    } else if (d.isNotEmpty()) {
                        dueDate = d
                    }
                    val p = when (pri) { L["高_b096b3f5ac"] -> 3; L["中_0869071c92"] -> 2; L["低_b9ee259b7f"] -> 1; else -> 0 }
                    if (title.isBlank()) {
                        NoteBoxState.toast(tr("标题不能为空_f2d8d992fe"), ToastKind.Warn)
                        return@PrimaryBtn
                    }
                    if (repeat != "none" && d.isEmpty()) {
                        NoteBoxState.toast(tr("重复事项需要设置截止日期_60cc0d8dec"), ToastKind.Warn)
                        return@PrimaryBtn
                    }
                    onSave(
                        title.trim(), detail, p, remind, done, dueAt, dueDate,
                        tags.trim(), subtasks.map { it.toJson() }, repeat, folderId,
                    )
                })
            }
        }
    }
}
