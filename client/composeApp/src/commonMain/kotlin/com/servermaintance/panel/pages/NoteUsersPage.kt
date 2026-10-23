package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SelectField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * 用户管理 —— 复刻 notebox「users」视图（仅管理员）
 * 端点：GET/POST     /api/admin/users
 *       PUT/DELETE   /api/admin/users/{id}
 *       POST         /api/admin/users/{id}/resend
 * ==================================================================== */

/** 新建/编辑弹窗的目标账号；`user == null` 表示新建。 */
private data class UserDialogTarget(val user: JsonObject?)

@Composable
fun NoteUsersPage() {
    NoteGate { UsersInner() }
}

@Composable
private fun UsersInner() {
    val scope = rememberCoroutineScope()
    val me by NoteBoxState.user.collectAsState()

    var users by remember { mutableStateOf<List<JsonElement>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<UserDialogTarget?>(null) }
    var q by remember { mutableStateOf("") }
    var roleFilter by remember { mutableStateOf("全部角色") }
    var statusFilter by remember { mutableStateOf("全部状态") }

    suspend fun load() {
        try {
            val list = withContext(Dispatchers.IO) { NoteBoxState.api.listUsers() }
            users = list
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (!e.isAuth) error = e.message ?: "加载失败"
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        }
    }

    /* 重发验证邮件（pending 账号） */
    fun resend(u: JsonObject) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.resendUserVerification(u.int("id")) }
                NoteBoxState.toast("验证邮件已重新发送", ToastKind.Ok)
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: "发送失败", ToastKind.Err)
            }
        }
    }

    /* 停用 / 启用 */
    fun toggleActive(u: JsonObject) {
        scope.launch {
            val on = u.int("active") == 1 && u.str("status") != "disabled"
            if (on) {
                val ok = Modal.confirm(
                    "停用用户",
                    "停用 ${u.str("email")} ？其会话将立即失效。",
                    okText = "停用", danger = true,
                )
                if (!ok) return@launch
            }
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.updateUser(u.int("id"), active = !on) }
                NoteBoxState.toast(if (on) "已停用 ${u.str("email")}" else "已启用 ${u.str("email")}", ToastKind.Ok)
                load()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: "操作失败", ToastKind.Err)
            }
        }
    }

    /* 删除账号（连同笔记/待办/共享） */
    fun remove(u: JsonObject) {
        scope.launch {
            val ok = Modal.confirm(
                "删除用户",
                "删除用户 ${u.str("email")} ？其全部笔记、待办与共享将一并删除，不可恢复！",
                okText = "删除", danger = true,
            )
            if (!ok) return@launch
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.deleteUser(u.int("id")) }
                NoteBoxState.toast("已删除 ${u.str("email")}", ToastKind.Ok)
                load()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: "删除失败", ToastKind.Err)
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    PageScaffold(
        title = "用户管理",
        desc = "NoteBox 账号（仅管理员）",
        actions = { NoteUserChip() },
        scrollBody = false, // 列表区独立滚动
    ) {
        val rows = users.orEmpty().filterIsInstance<JsonObject>()

        /* 统计磁贴：账号 / 管理员 / 待验证 / 已停用 */
        if (rows.isNotEmpty()) {
            val admins = rows.count { it.str("role") == "admin" }
            val pending = rows.count { it.str("status") == "pending" }
            val disabled = rows.count { it.str("status") == "disabled" || it.int("active") != 1 }
            UserStatRow(rows.size, admins, pending, disabled)
        }

        /* 工具行：搜索 + 角色/状态过滤 + 新建（卡片容器） */
        val barShape = RoundedCornerShape(6.dp)
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp)
                .clip(barShape)
                .background(Palette.panel)
                .border(1.dp, Palette.border, barShape)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(q, { q = it }, Modifier.weight(1f), placeholder = "搜索邮箱…")
            Box(Modifier.widthIn(max = 130.dp)) {
                SelectField(roleFilter, listOf("全部角色", "管理员", "普通用户"), { roleFilter = it }, placeholder = "全部角色")
            }
            Box(Modifier.widthIn(max = 140.dp)) {
                SelectField(statusFilter, listOf("全部状态", "已启用", "待邮箱验证", "已停用"), { statusFilter = it }, placeholder = "全部状态")
            }
            PrimaryBtn(text = "新建用户", icon = "plus", onClick = { dialog = UserDialogTarget(null) })
        }

        when {
            users == null && error == null -> LoadingState()
            error != null && users == null -> ErrorState(message = error!!, onRetry = {
                scope.launch { load() }
            })
            else -> {
                val filtered = rows.filter { u ->
                    val emailOk = q.isEmpty() || u.str("email").contains(q, ignoreCase = true)
                    val roleOk = roleFilter == "全部角色" ||
                        (u.str("role") == "admin") == (roleFilter == "管理员")
                    val status = u.str("status")
                    val st = when {
                        status == "pending" -> "待邮箱验证"
                        status == "disabled" || u.int("active") != 1 -> "已停用"
                        else -> "已启用"
                    }
                    emailOk && roleOk && (statusFilter == "全部状态" || st == statusFilter)
                }
                if (filtered.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(52.dp).clip(RoundedCornerShape(13.dp))
                                    .background(AccentState.current.primary.copy(alpha = 0.10f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                PanelIcon("users", 26.dp, AccentState.current.secondary)
                            }
                            Spacer(Modifier.size(12.dp))
                            Text(
                                if (rows.isEmpty()) "还没有用户" else "没有匹配的账号",
                                color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            )
                            if (rows.isEmpty()) {
                                Spacer(Modifier.size(4.dp))
                                Text("点上方「新建用户」创建第一个账号", color = Palette.muted, fontSize = 11.5.sp)
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        filtered.forEachIndexed { idx, u ->
                            UserRow(
                                u = u, index = idx, me = me,
                                onEdit = { dialog = UserDialogTarget(u) },
                                onResend = { resend(u) },
                                onToggleActive = { toggleActive(u) },
                                onDelete = { remove(u) },
                            )
                        }
                        Spacer(Modifier.size(4.dp))
                    }
                }
            }
        }
    }

    /* 新建 / 编辑弹窗（同 app.js #user-modal） */
    dialog?.let { target ->
        UserEditDialog(
            user = target.user,
            onClose = { dialog = null },
            onSave = { email, password, role, active ->
                val creating = target.user == null
                scope.launch {
                    try {
                        if (creating) {
                            withContext(Dispatchers.IO) {
                                NoteBoxState.api.createUser(email, password, role, active = active)
                            }
                        } else {
                            withContext(Dispatchers.IO) {
                                NoteBoxState.api.updateUser(
                                    target.user.int("id"),
                                    email = email, password = password, role = role, active = active,
                                )
                            }
                        }
                        load()
                        dialog = null
                        NoteBoxState.toast("已保存 $email", ToastKind.Ok)
                    } catch (e: Exception) {
                        NoteBoxState.toast(e.message ?: "保存失败", ToastKind.Err)
                    }
                }
            },
        )
    }
}

/** epoch 秒 -> `MM-DD HH:mm`；0 表示从未发生，显示 `—`。 */
private fun tsShort(ts: Long): String = if (ts <= 0) "—" else fmtShortTime(ts)

/* ------------------------------------------------------------------ *
 * 统计磁贴
 * ------------------------------------------------------------------ */

@Composable
private fun UserStatRow(total: Int, admins: Int, pending: Int, disabled: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UserStatTile("总账号", "$total", "users", Modifier.weight(1f))
        UserStatTile("管理员", "$admins", "shield", Modifier.weight(1f), tone = AccentState.current.secondary)
        UserStatTile("待验证", "$pending", "mail", Modifier.weight(1f), tone = Palette.warn, warn = pending > 0)
        UserStatTile("已停用", "$disabled", "power", Modifier.weight(1f), tone = Palette.muted, warn = disabled > 0 && pending == 0)
    }
}

@Composable
private fun UserStatTile(
    label: String,
    value: String,
    icon: String,
    modifier: Modifier = Modifier,
    tone: Color = AccentState.current.secondary,
    warn: Boolean = false,
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.clip(shape)
            .background(Palette.panel)
            .border(1.dp, if (warn) tone.copy(alpha = 0.35f) else Palette.border, shape)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                .background(tone.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            PanelIcon(icon, 14.dp, tone)
        }
        Spacer(Modifier.size(9.dp))
        Column {
            Text(
                value,
                color = if (warn) tone else Palette.text,
                fontSize = 16.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
                maxLines = 1,
            )
            Text(label, color = Palette.muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/* ------------------------------------------------------------------ *
 * 用户卡片行
 * ------------------------------------------------------------------ */

@Composable
private fun UserRow(
    u: JsonObject,
    index: Int,
    me: String?,
    onEdit: () -> Unit,
    onResend: () -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = u.str("status")
    val isAdmin = u.str("role") == "admin"
    val isMe = u.str("email") == me
    val tone = when {
        status == "pending" -> Palette.warn
        status == "disabled" || u.int("active") != 1 -> Palette.muted
        isAdmin -> AccentState.current.secondary
        else -> Palette.ok
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .nbEnter(index)
            .clip(shape)
            .background(if (hovered) Palette.panel2 else Palette.panel)
            .border(1.dp, if (hovered) Palette.border2 else Palette.border, shape)
            .clickable(interactionSource = interaction, indication = null) { onEdit() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /* 头像：首字母 + 角色/状态色 */
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(50))
                .background(tone.copy(alpha = 0.14f))
                .border(1.4.dp, tone.copy(alpha = 0.45f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                u.str("email").firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = tone, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    u.str("email"),
                    color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isMe) Chip("我", tone = Level.Info)
                if (isAdmin) LevelBadge("管理员", Level.Info) else LevelBadge("普通用户", Level.Muted)
                when {
                    status == "pending" -> LevelBadge("待邮箱验证", Level.Warn)
                    status == "disabled" || u.int("active") != 1 -> LevelBadge("已停用", Level.Muted)
                    else -> LevelBadge("已启用", Level.Ok)
                }
            }
            Spacer(Modifier.size(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetaChip("note", "${u.int("notes")}")
                MetaChip("checkSquare", "${u.int("todos")}")
                MetaChip("calendar", "注册 ${tsShort(u.long("created_at"))}")
                MetaChip("clock", "登录 ${tsShort(u.long("last_login"))}")
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostBtn(text = "编辑", icon = "edit", onClick = onEdit)
            if (status == "pending") {
                GhostBtn(text = "重发验证", icon = "mail", onClick = onResend)
            } else {
                val on = u.int("active") == 1 && status != "disabled"
                GhostBtn(
                    text = if (on) "停用" else "启用",
                    icon = if (on) "power" else "checkCircle",
                    onClick = onToggleActive,
                )
            }
            DangerBtn(text = "删除", icon = "trash", onClick = onDelete)
        }
    }
}

/** 卡片内元信息小胶囊（图标 + 等宽文字）。 */
@Composable
private fun MetaChip(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelIcon(icon, 12.dp, Palette.muted)
        Spacer(Modifier.size(4.dp))
        Text(
            text,
            color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

/* ------------------------------------------------------------------ *
 * 新建 / 编辑账号弹窗
 * ------------------------------------------------------------------ */

@Composable
private fun UserEditDialog(
    user: JsonObject?,
    onClose: () -> Unit,
    onSave: (email: String, password: String, role: String, active: Boolean) -> Unit,
) {
    val creating = user == null
    var email by remember(user) { mutableStateOf(user?.str("email") ?: "") }
    var pass by remember(user) { mutableStateOf("") }
    var role by remember(user) { mutableStateOf(if (user?.str("role") == "admin") "管理员" else "普通用户") }
    var active by remember(user) { mutableStateOf(user == null || user.int("active") == 1) }

    Box(
        Modifier.fillMaxSize().zIndex(70f)
            .background(Palette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.padding(horizontal = if (platformHandheld) 14.dp else 24.dp)
                .widthIn(max = 460.dp).fillMaxWidth()
                .clip(shape)
                .background(Palette.panel)
                .border(1.dp, Palette.border2, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .verticalScroll(rememberScrollState()),
        ) {
            /* 头部：图标 + 标题 + 副标题 */
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)
                    .background(Palette.tableHeader),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(7.dp))
                        .background(AccentState.current.primary.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    PanelIcon(if (creating) "plus" else "user", 16.dp, AccentState.current.secondary)
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (creating) "新建用户" else "编辑用户",
                        color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(1.dp))
                    Text(
                        if (creating) "创建 NoteBox 账号并分配角色" else user?.str("email").orEmpty(),
                        color = Palette.muted, fontSize = 11.sp,
                        fontFamily = if (creating) FontFamily.Default else FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    PanelIcon("close", 14.dp, Palette.muted)
                }
            }

            Column(Modifier.padding(16.dp)) {
                Field(email, { email = it }, Modifier.fillMaxWidth(), label = "邮箱", hint = "you@example.com", mono = true, singleLine = true)
                Spacer(Modifier.size(10.dp))
                Field(
                    pass, { pass = it }, Modifier.fillMaxWidth(),
                    label = "密码", hint = if (creating) "至少 8 位" else "留空则不修改",
                    password = true, singleLine = true,
                )
                Spacer(Modifier.size(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SelectField(role, listOf("普通用户", "管理员"), { role = it }, Modifier.weight(1f), label = "角色")
                    Column(Modifier.weight(1f)) {
                        Text("状态", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                        CheckRow(active, { active = it }, "已启用")
                    }
                }
                Spacer(Modifier.size(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                Spacer(Modifier.size(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GhostBtn(text = "取消", onClick = onClose)
                    Spacer(Modifier.size(8.dp))
                    PrimaryBtn(text = if (creating) "创建" else "保存", icon = "save", onClick = {
                        if (email.trim().isEmpty()) {
                            NoteBoxState.toast("请输入邮箱", ToastKind.Warn)
                            return@PrimaryBtn
                        }
                        if (creating && pass.length < 8) {
                            NoteBoxState.toast("新建用户需要至少 8 位的密码", ToastKind.Warn)
                            return@PrimaryBtn
                        }
                        onSave(email.trim(), pass, if (role == "管理员") "admin" else "user", active)
                    })
                }
            }
        }
    }
}
