package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
    var roleFilter by remember { mutableStateOf(L["全部角色_9adaf88c6d"]) }
    var statusFilter by remember { mutableStateOf(L["全部状态_1a4c26d92d"]) }

    suspend fun load() {
        try {
            val list = withContext(Dispatchers.IO) { NoteBoxState.api.listUsers() }
            users = list
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (!e.isAuth) error = e.message ?: L["加载失败_f6b7a41e56"]
        } catch (e: Exception) {
            error = e.message ?: L["加载失败_f6b7a41e56"]
        }
    }

    /* 重发验证邮件（pending 账号） */
    fun resend(u: JsonObject) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.resendUserVerification(u.int("id")) }
                NoteBoxState.toast(tr("验证邮件已重新发送_2dcdc605ce"), ToastKind.Ok)
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("发送失败_e767d34c78"), ToastKind.Err)
            }
        }
    }

    /* 停用 / 启用 */
    fun toggleActive(u: JsonObject) {
        scope.launch {
            val on = u.int("active") == 1 && u.str("status") != "disabled"
            if (on) {
                val ok = Modal.confirm(
                    L["停用用户_3f2f67ac37"],
                    trf("停用_0_其会话将立即失效_bd25e844b8", u.str("email")),
                    okText = L["停用_d989e55188"], danger = true,
                )
                if (!ok) return@launch
            }
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.updateUser(u.int("id"), active = !on) }
                NoteBoxState.toast(if (on) trf("已停用_0_c934ebc3bd", u.str("email")) else trf("已启用_0_29dab49ed0", u.str("email")), ToastKind.Ok)
                load()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("操作失败_09e424b5e8"), ToastKind.Err)
            }
        }
    }

    /* 删除账号（连同笔记/待办/共享） */
    fun remove(u: JsonObject) {
        scope.launch {
            val ok = Modal.confirm(
                L["删除用户_bcfca951a1"],
                trf("删除用户_0_其全部笔记_待办与共享将一并_2bdfec376d", u.str("email")),
                okText = L["删除_3755f56f2f"], danger = true,
            )
            if (!ok) return@launch
            try {
                withContext(Dispatchers.IO) { NoteBoxState.api.deleteUser(u.int("id")) }
                NoteBoxState.toast(trf("已删除_0_a0892ec5c3", u.str("email")), ToastKind.Ok)
                load()
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("删除失败_72250c5922"), ToastKind.Err)
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    PageScaffold(
        title = L["用户管理_baf84751a2"],
        desc = L["notebox_账号_仅管理员_81b8d4ca26"],
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
            SearchField(q, { q = it }, Modifier.weight(1f), placeholder = L["搜索邮箱_8dad475e2f"])
            Box(Modifier.widthIn(max = 130.dp)) {
                SelectField(roleFilter, listOf(L["全部角色_9adaf88c6d"], L["管理员_ef84e765e0"], L["普通用户_bd8d896aad"]), { roleFilter = it }, placeholder = L["全部角色_9adaf88c6d"])
            }
            Box(Modifier.widthIn(max = 140.dp)) {
                SelectField(statusFilter, listOf(L["全部状态_1a4c26d92d"], L["已启用_25d2843150"], L["待邮箱验证_bada0c2e46"], L["已停用_6c7dcbb73a"]), { statusFilter = it }, placeholder = L["全部状态_1a4c26d92d"])
            }
            PrimaryBtn(text = L["新建用户_30416407e5"], icon = "plus", onClick = { dialog = UserDialogTarget(null) })
        }

        when {
            users == null && error == null -> LoadingState()
            error != null && users == null -> ErrorState(message = error!!, onRetry = {
                scope.launch { load() }
            })
            else -> {
                val filtered = rows.filter { u ->
                    val emailOk = q.isEmpty() || u.str("email").contains(q, ignoreCase = true)
                    val roleOk = roleFilter == L["全部角色_9adaf88c6d"] ||
                        (u.str("role") == "admin") == (roleFilter == L["管理员_ef84e765e0"])
                    val status = u.str("status")
                    val st = when {
                        status == "pending" -> L["待邮箱验证_bada0c2e46"]
                        status == "disabled" || u.int("active") != 1 -> L["已停用_6c7dcbb73a"]
                        else -> L["已启用_25d2843150"]
                    }
                    emailOk && roleOk && (statusFilter == L["全部状态_1a4c26d92d"] || st == statusFilter)
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
                                if (rows.isEmpty()) L["还没有用户_7e3b44aaac"] else L["没有匹配的账号_4612b2a961"],
                                color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            )
                            if (rows.isEmpty()) {
                                Spacer(Modifier.size(4.dp))
                                Text(L["点上方_新建用户_创建第一个账号_a49eedd915"], color = Palette.muted, fontSize = 11.5.sp)
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
                        NoteBoxState.toast(tr("已保存_email_4726a5328b"), ToastKind.Ok)
                    } catch (e: Exception) {
                        NoteBoxState.toast(e.message ?: tr("保存失败_40525a7328"), ToastKind.Err)
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
        UserStatTile(L["总账号_02ceb243fe"], "$total", "users", Modifier.weight(1f))
        UserStatTile(L["管理员_ef84e765e0"], "$admins", "shield", Modifier.weight(1f), tone = AccentState.current.secondary)
        UserStatTile(L["待验证_0b48e4bdd8"], "$pending", "mail", Modifier.weight(1f), tone = Palette.warn, warn = pending > 0)
        UserStatTile(L["已停用_6c7dcbb73a"], "$disabled", "power", Modifier.weight(1f), tone = Palette.muted, warn = disabled > 0 && pending == 0)
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
                if (isMe) Chip(L["我_34f201bc50"], tone = Level.Info)
                if (isAdmin) LevelBadge(L["管理员_ef84e765e0"], Level.Info) else LevelBadge(L["普通用户_bd8d896aad"], Level.Muted)
                when {
                    status == "pending" -> LevelBadge(L["待邮箱验证_bada0c2e46"], Level.Warn)
                    status == "disabled" || u.int("active") != 1 -> LevelBadge(L["已停用_6c7dcbb73a"], Level.Muted)
                    else -> LevelBadge(L["已启用_25d2843150"], Level.Ok)
                }
            }
            Spacer(Modifier.size(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetaChip("note", "${u.int("notes")}")
                MetaChip("checkSquare", "${u.int("todos")}")
                MetaChip("calendar", trf("注册_0_5ad4a3af5f", tsShort(u.long("created_at"))))
                MetaChip("clock", trf("登录_0_0e5eb0faa5", tsShort(u.long("last_login"))))
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostBtn(text = L["编辑_a7f814c0a4"], icon = "edit", onClick = onEdit)
            if (status == "pending") {
                GhostBtn(text = L["重发验证_94c7c8acad"], icon = "mail", onClick = onResend)
            } else {
                val on = u.int("active") == 1 && status != "disabled"
                GhostBtn(
                    text = if (on) L["停用_d989e55188"] else L["启用_d4e9ca3dd4"],
                    icon = if (on) "power" else "checkCircle",
                    onClick = onToggleActive,
                )
            }
            DangerBtn(text = L["删除_3755f56f2f"], icon = "trash", onClick = onDelete)
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
    var role by remember(user) { mutableStateOf(if (user?.str("role") == "admin") L["管理员_ef84e765e0"] else L["普通用户_bd8d896aad"]) }
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
                        if (creating) L["新建用户_30416407e5"] else L["编辑用户_2b8f00100b"],
                        color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(1.dp))
                    Text(
                        if (creating) L["创建_notebox_账号并分配角色_2911541ceb"] else user?.str("email").orEmpty(),
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
                Field(email, { email = it }, Modifier.fillMaxWidth(), label = L["邮箱_9ed627bcf6"], hint = L["you_example_com_50e2b46ef8"], mono = true, singleLine = true)
                Spacer(Modifier.size(10.dp))
                Field(
                    pass, { pass = it }, Modifier.fillMaxWidth(),
                    label = L["密码_c839a8ff17"], hint = if (creating) L["至少_8_位_bfd5e79db7"] else L["留空则不修改_05999682bf"],
                    password = true, singleLine = true,
                )
                Spacer(Modifier.size(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SelectField(role, listOf(L["普通用户_bd8d896aad"], L["管理员_ef84e765e0"]), { role = it }, Modifier.weight(1f), label = L["角色_6b26695e4d"])
                    Column(Modifier.weight(1f)) {
                        Text(L["状态_62e951a692"], color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                        CheckRow(active, { active = it }, L["已启用_25d2843150"])
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
                    GhostBtn(text = L["win.dialog.cancel"], onClick = onClose)
                    Spacer(Modifier.size(8.dp))
                    PrimaryBtn(text = if (creating) L["创建_fcbd093292"] else L["保存_fadf24dbc5"], icon = "save", onClick = {
                        if (email.trim().isEmpty()) {
                            NoteBoxState.toast(tr("请输入邮箱_f2dc24deb8"), ToastKind.Warn)
                            return@PrimaryBtn
                        }
                        if (creating && pass.length < 8) {
                            NoteBoxState.toast(tr("新建用户需要至少_8_位的密码_59c185c76b"), ToastKind.Warn)
                            return@PrimaryBtn
                        }
                        onSave(email.trim(), pass, if (role == L["管理员_ef84e765e0"]) "admin" else "user", active)
                    })
                }
            }
        }
    }
}
