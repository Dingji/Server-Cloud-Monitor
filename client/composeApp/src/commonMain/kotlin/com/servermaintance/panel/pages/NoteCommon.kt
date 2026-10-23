package com.servermaintance.panel.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.NbSessionState
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.core.NoteBoxStore
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.long
import com.servermaintance.panel.nowEpochMillis
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.HandheldLayout
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.handheldImePadding
import com.servermaintance.panel.platformHandheld
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 当前时区的「今天」日期。 */
internal fun todayDate(): kotlinx.datetime.LocalDate =
    Instant.fromEpochMilliseconds(nowEpochMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date

/** epoch 秒 -> 当前时区日期字符串 `yyyy-MM-dd`。 */
internal fun localDateOfEpochSeconds(epochSec: Long): String =
    Instant.fromEpochSeconds(epochSec).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

/* ==================================================================== *
 * NoteBox 共享组件 —— 登录门控 / 时间与优先级 / Markdown 轻量预览
 * ==================================================================== */

/** 待办优先级文案（同 app.js PRI_LABEL）。 */
internal fun priLabel(p: Int): String = when (p) {
    3 -> "高"; 2 -> "中"; 1 -> "低"; else -> "无"
}

internal fun priTone(p: Int): com.servermaintance.panel.ui.Level = when (p) {
    3 -> com.servermaintance.panel.ui.Level.Bad
    2 -> com.servermaintance.panel.ui.Level.Warn
    1 -> com.servermaintance.panel.ui.Level.Info
    else -> com.servermaintance.panel.ui.Level.Muted
}

/** 本地日期 `yyyy-MM-dd`。 */
internal fun localDateStr(days: Int = 0): String {
    val today: LocalDate = Instant.fromEpochMilliseconds(nowEpochMillis())
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    if (days == 0) return today.toString()
    return LocalDate.fromEpochDays(today.toEpochDays() + days).toString()
}

/** 简单 markdown 剥离（列表摘要用，同 app.js stripMd）。 */
internal fun stripMd(s: String): String = s
    .replace(Regex("```[\\s\\S]*?```"), " 〔代码〕 ")
    .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " 〔图片〕 ")
    .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    .replace(Regex("[#>*_`~|-]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** 秒级时间戳 -> `MM-DD HH:mm`（笔记列表/待办行内）。 */
internal fun fmtShortTime(tsSec: Long): String {
    if (tsSec <= 0) return "--"
    val ldt = Instant.fromEpochSeconds(tsSec).toLocalDateTime(TimeZone.currentSystemDefault())
    val two = { v: Int -> if (v < 10) "0$v" else "$v" }
    return "${two(ldt.monthNumber)}-${two(ldt.dayOfMonth)} ${two(ldt.hour)}:${two(ldt.minute)}"
}

/** 秒级时间戳 -> 相对时间（刚刚 / N 分钟前 / N 小时前 / N 天前 / 超 7 天回退 MM-DD HH:mm）。 */
internal fun fmtRelTime(tsSec: Long): String {
    if (tsSec <= 0) return "--"
    val diff = nowEpochMillis() / 1000 - tsSec
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86400 -> "${diff / 3600} 小时前"
        diff < 7 * 86400 -> "${diff / 86400} 天前"
        else -> fmtShortTime(tsSec)
    }
}

/** 秒级时间戳 -> 完整日期时间（邮件日志等）。 */
internal fun fmtDateTimeSec(tsSec: Long): String {
    if (tsSec <= 0) return "--"
    val ldt = Instant.fromEpochSeconds(tsSec).toLocalDateTime(TimeZone.currentSystemDefault())
    val two = { v: Int -> if (v < 10) "0$v" else "$v" }
    return "${ldt.year}-${two(ldt.monthNumber)}-${two(ldt.dayOfMonth)} ${two(ldt.hour)}:${two(ldt.minute)}:${two(ldt.second)}"
}

/**
 * 待办截止信息（同 app.js dueInfo）：
 * [late] 逾期、[today] 今天到期、[label] 展示文案、[hasDue] 是否设了截止。
 */
internal data class DueInfo(val late: Boolean, val today: Boolean, val label: String, val hasDue: Boolean)

internal fun dueInfo(t: JsonObject): DueInfo {
    val now = nowEpochMillis() / 1000
    val today = localDateStr()
    val tomorrow = localDateStr(1)
    val dueAt = (t["due_at"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
    val dueDate = t["due_date"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
    val pending = t["status"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "pending"
    if (dueAt != null && dueAt > 0) {
        val ldt = Instant.fromEpochSeconds(dueAt).toLocalDateTime(TimeZone.currentSystemDefault())
        val ds = ldt.date.toString()
        val hm = (if (ldt.hour < 10) "0" else "") + ldt.hour + ":" + (if (ldt.minute < 10) "0" else "") + ldt.minute
        val label = when (ds) {
            today -> "今天 $hm"
            tomorrow -> "明天 $hm"
            else -> "${ldt.monthNumber}-${(if (ldt.dayOfMonth < 10) "0" else "")}${ldt.dayOfMonth} $hm"
        }
        return DueInfo(late = pending && dueAt < now, today = ds == today, label = label, hasDue = true)
    }
    if (dueDate.isNotEmpty()) {
        val label = when (dueDate) {
            today -> "今天"; tomorrow -> "明天"; else -> dueDate
        }
        return DueInfo(late = pending && dueDate < today, today = dueDate == today, label = label, hasDue = true)
    }
    return DueInfo(late = false, today = false, label = "", hasDue = false)
}

/* ==================================================================== *
 * 共享权限模型（多用户版）—— 与服务端 auth.py note_permission 对齐
 * ==================================================================== */

/** 权限文案（同服务端 PERM_TEXT）。 */
internal fun permText(perm: String?): String = when (perm) {
    "read" -> "只读"; "edit" -> "可编辑"; "admin" -> "管理"; else -> "拥有者"
}

/**
 * 笔记访问能力（同服务端 app.js noteAccess）：
 * [perm] null = 自己的笔记（或管理员代管）；否则 read/edit/admin。
 * [canEdit] 只读不可编辑；[canManage] 只有 owner/admin 能置顶、删除、管理共享。
 */
internal data class NoteAccess(val perm: String?, val canEdit: Boolean, val canManage: Boolean)

internal fun noteAccess(myPermission: String?): NoteAccess = when (myPermission) {
    null -> NoteAccess(null, canEdit = true, canManage = true)
    "read" -> NoteAccess("read", canEdit = false, canManage = false)
    "edit" -> NoteAccess("edit", canEdit = true, canManage = false)
    else -> NoteAccess(myPermission, canEdit = true, canManage = true) // admin
}

/* ==================================================================== *
 * 动画基建 —— 让界面更生动（卡片入场、错误抖动、元素弹入）
 * ==================================================================== */

/**
 * 卡片/页面元素入场：淡入 + 上移浮现 + 轻缩放回弹。用法：
 * `Modifier.nbEnter(index)`，index 越大延迟越久（stagger，40ms 步进）。
 * 注：animateXxxAsState 首帧即取目标值，故用 entered 标志下一帧才置位触发动画。
 */
internal fun Modifier.nbEnter(index: Int = 0, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    return composed {
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val delay = (index * 40).coerceAtMost(400)
        val alpha by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(320, delayMillis = delay, easing = FastOutSlowInEasing),
            label = "nbEnterA",
        )
        val y by animateDpAsState(
            targetValue = if (entered) 0.dp else 14.dp,
            animationSpec = tween(400, delayMillis = delay, easing = FastOutSlowInEasing),
            label = "nbEnterY",
        )
        val scale by animateFloatAsState(
            targetValue = if (entered) 1f else 0.97f,
            animationSpec = nbSpringSoft(stiffness = 320f),
            label = "nbEnterS",
        )
        this.graphicsLayer {
            translationY = y.toPx()
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        }
    }
}

/**
 * 错误抖动（登录/注册失败时卡片左右晃一下，同服务端 .shake）。
 * 用法：`val shake = rememberShakeState()` → 失败时 `scope.launch { shake.fire() }`
 * → `Modifier.shake(shake)`。
 */
internal class ShakeState internal constructor(private val anim: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) {
    val value: Float get() = anim.value

    /** 播放一次抖动（幂等：正在抖时重新触发会从头开始）。 */
    suspend fun fire() {
        anim.snapTo(0f)
        anim.animateTo(1f, animationSpec = tween(420, easing = FastOutSlowInEasing))
    }
}

@Composable
internal fun rememberShakeState(): ShakeState = ShakeState(remember { Animatable(0f) })

/** 把 [ShakeState] 的抖动应用为水平位移（sine 衰减波形）。 */
internal fun Modifier.shake(state: ShakeState): Modifier = this.graphicsLayer {
    val p = state.value
    // 4 个半波、幅度随 p 衰减到 0
    val wave = (kotlin.math.sin(p * kotlin.math.PI * 4) * (1f - p)).toFloat()
    translationX = wave * 14f
}

/* ==================================================================== *
 * NoteGate —— 未登录时渲染 NoteBox 登录卡（复刻 notebox index.html 登录屏）
 * ==================================================================== */

private enum class NbAuthPane { Login, Register, Done }

/**
 * NoteBox 页面门控：会话 Ready 直接渲染 [content]；
 * 否则显示登录卡（用户名 + 密码 + 可折叠服务器连接配置）。
 */
@Composable
fun NoteGate(content: @Composable () -> Unit) {
    val session by NoteBoxState.session.collectAsState()
    when (session) {
        NbSessionState.Ready -> content()
        NbSessionState.Boot -> {
            LaunchedEffect(Unit) { NoteBoxState.bootstrap() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("连接 NoteBox…", color = Palette.muted, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            }
        }
        NbSessionState.Login -> NoteLoginCard()
    }
}

/** 登录卡（居中，样式同面板登录卡；含 登录 ⇄ 注册 ⇄ 注册完成 三态）。 */
@Composable
private fun NoteLoginCard() {
    var pane by remember { mutableStateOf(NbAuthPane.Login) }
    var user by remember { mutableStateOf(NoteBoxState.config().user) }
    var pass by remember { mutableStateOf(NoteBoxState.config().password) }
    var url by remember { mutableStateOf(NoteBoxState.config().normalizedUrl().ifEmpty { NoteBoxStore.DEFAULT_URL }) }
    var rememberPw by remember { mutableStateOf(NoteBoxState.config().rememberPassword) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connOpen by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    /* 注册态 */
    var regEmail by remember { mutableStateOf("") }
    var regPass by remember { mutableStateOf("") }
    var regPass2 by remember { mutableStateOf("") }
    var doneMsg by remember { mutableStateOf("") }
    val shake = rememberShakeState()
    val scope = rememberCoroutineScope()

    fun fireError(msg: String) {
        error = msg
        scope.launch { shake.fire() }
    }

    val doLogin: () -> Unit = {
        if (user.isBlank() || pass.isEmpty()) {
            fireError("请输入邮箱和密码")
        } else if (!busy) {
            error = ""
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        NoteBoxState.updateCredentials(user.trim(), pass, rememberPw)
                        if (url.trim().trimEnd('/').ifEmpty { NoteBoxStore.DEFAULT_URL } !=
                            NoteBoxState.config().normalizedUrl()
                        ) {
                            NoteBoxState.reconfigure(url, user.trim())
                            NoteBoxState.updateCredentials(user.trim(), pass, rememberPw)
                        }
                    }
                    val err = NoteBoxState.login(user.trim(), pass)
                    if (err != null) fireError(err) else NoteBoxState.toast("NoteBox 登录成功", ToastKind.Ok)
                } catch (e: Exception) {
                    fireError(e.message ?: e::class.simpleName ?: "登录失败")
                } finally {
                    busy = false
                }
            }
        }
    }

    val doRegister: () -> Unit = {
        when {
            !regEmail.contains("@") -> fireError("请输入正确的邮箱地址")
            regPass.length < 8 -> fireError("密码至少需要 8 位")
            regPass != regPass2 -> fireError("两次输入的密码不一致")
            else -> {
                error = ""
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) {
                            if (url.trim().trimEnd('/').ifEmpty { NoteBoxStore.DEFAULT_URL } !=
                                NoteBoxState.config().normalizedUrl()
                            ) {
                                NoteBoxState.reconfigure(url, regEmail.trim())
                            }
                        }
                        val err = NoteBoxState.register(regEmail.trim(), regPass)
                        if (err != null) {
                            fireError(err)
                        } else {
                            doneMsg = "验证邮件已发送，请查收（24 小时内有效）。若未收到请联系管理员激活。"
                            regPass = ""; regPass2 = ""
                            pane = NbAuthPane.Done
                            NoteBoxState.toast("注册成功，请查收验证邮件", ToastKind.Ok)
                        }
                    } catch (e: Exception) {
                        fireError(e.message ?: e::class.simpleName ?: "注册失败")
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Palette.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = if (platformHandheld) 400.dp else 360.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .handheldImePadding()
                .padding(12.dp),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .nbEnter(0)
                    .shake(shake)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Palette.panel, RoundedCornerShape(3.dp))
                    .border(1.dp, Palette.border2, RoundedCornerShape(3.dp)),
            ) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(AccentState.current.primary))
                Column(
                    Modifier.padding(horizontal = HandheldLayout.loginCardHorizontalPadding())
                        .padding(top = 26.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.nbFloat(amplitude = 3.dp), contentAlignment = Alignment.Center) {
                        PanelIcon("notebox", 34.dp, AccentState.current.secondary, stroke = 1.6f)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "NOTEBOX",
                        color = Palette.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("云端笔记 · 待办清单", color = Palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.size(16.dp))

                    when (pane) {
                        NbAuthPane.Login -> {
                            NbLoginField(user, { user = it; if (error.isNotEmpty()) error = "" }, "邮箱 / 账号", enabled = !busy, onEnter = doLogin)
                            Spacer(Modifier.size(8.dp))
                            NbLoginField(pass, { pass = it; if (error.isNotEmpty()) error = "" }, "密码",
                                password = true, enabled = !busy, onEnter = doLogin)

                            Text(
                                error,
                                color = Palette.bad, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp).padding(top = 8.dp, bottom = 4.dp),
                            )

                            Box(
                                Modifier.fillMaxWidth().height(38.dp).nbPressScale(0.985f)
                                    .background(if (!busy) AccentState.current.primary else Palette.panel3, RoundedCornerShape(3.dp))
                                    .clickable(enabled = !busy) { doLogin() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (busy) "登录中…" else "登 录",
                                    color = if (!busy) Palette.white else Palette.muted,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                                )
                            }

                            /* 记住密码 */
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp)
                                    .clickable { rememberPw = !rememberPw },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val cbBg by androidx.compose.animation.animateColorAsState(
                                    targetValue = if (rememberPw) AccentState.current.primary else Color.Transparent,
                                    animationSpec = tween(180),
                                    label = "cbBg",
                                )
                                val cbBorder by androidx.compose.animation.animateColorAsState(
                                    targetValue = if (rememberPw) AccentState.current.primary else Palette.border2,
                                    animationSpec = tween(180),
                                    label = "cbBorder",
                                )
                                val cbCheck by animateFloatAsState(
                                    targetValue = if (rememberPw) 1f else 0f,
                                    animationSpec = nbSpringPop(stiffness = 600f),
                                    label = "cbCheck",
                                )
                                Box(
                                    Modifier.size(15.dp)
                                        .background(cbBg, RoundedCornerShape(2.dp))
                                        .border(1.4.dp, cbBorder, RoundedCornerShape(2.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(Modifier.graphicsLayer { scaleX = cbCheck; scaleY = cbCheck }) {
                                        PanelIcon("check", 11.dp, Palette.white, 2.4f)
                                    }
                                }
                                Spacer(Modifier.size(8.dp))
                                Text("记住密码", color = Palette.muted, fontSize = 12.sp)
                            }

                            /* 未验证邮箱 → 提示可重发验证邮件 */
                            if (error.contains("验证")) {
                                GhostBtn("重新发送验证邮件", onClick = {
                                    scope.launch {
                                        val msg = NoteBoxState.resendVerification(user.trim())
                                        NoteBoxState.toast(msg, ToastKind.Info, 5000)
                                    }
                                }, Modifier.padding(top = 8.dp))
                            }

                            /* 切到注册 */
                            Row(
                                Modifier.fillMaxWidth().padding(top = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text("还没有账号？", color = Palette.muted, fontSize = 12.sp)
                                Text(
                                    "邮箱注册",
                                    color = AccentState.current.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { error = ""; pane = NbAuthPane.Register },
                                )
                            }
                        }

                        NbAuthPane.Register -> {
                            NbLoginField(regEmail, { regEmail = it; if (error.isNotEmpty()) error = "" }, "邮箱", enabled = !busy, onEnter = doRegister)
                            Spacer(Modifier.size(8.dp))
                            NbLoginField(regPass, { regPass = it; if (error.isNotEmpty()) error = "" }, "密码（至少 8 位）",
                                password = true, enabled = !busy, onEnter = doRegister)
                            Spacer(Modifier.size(8.dp))
                            NbLoginField(regPass2, { regPass2 = it; if (error.isNotEmpty()) error = "" }, "确认密码",
                                password = true, enabled = !busy, onEnter = doRegister)

                            Text(
                                error,
                                color = Palette.bad, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp).padding(top = 8.dp, bottom = 4.dp),
                            )

                            Box(
                                Modifier.fillMaxWidth().height(38.dp).nbPressScale(0.985f)
                                    .background(if (!busy) AccentState.current.primary else Palette.panel3, RoundedCornerShape(3.dp))
                                    .clickable(enabled = !busy) { doRegister() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (busy) "注册中…" else "注 册",
                                    color = if (!busy) Palette.white else Palette.muted,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                                )
                            }

                            Row(
                                Modifier.fillMaxWidth().padding(top = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Text("已有账号？", color = Palette.muted, fontSize = 12.sp)
                                Text(
                                    "去登录",
                                    color = AccentState.current.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { error = ""; pane = NbAuthPane.Login },
                                )
                            }
                        }

                        NbAuthPane.Done -> {
                            PanelIcon("checkCircle", 46.dp, Palette.ok, stroke = 1.6f)
                            Spacer(Modifier.size(12.dp))
                            Text("注册成功", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                doneMsg,
                                color = Palette.muted, fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.size(16.dp))
                            GhostBtn("重新发送验证邮件", onClick = {
                                scope.launch {
                                    val msg = NoteBoxState.resendVerification(regEmail.ifEmpty { user }.trim())
                                    NoteBoxState.toast(msg, ToastKind.Info, 5000)
                                }
                            })
                            Spacer(Modifier.size(10.dp))
                            Box(
                                Modifier.fillMaxWidth().height(38.dp)
                                    .background(AccentState.current.primary, RoundedCornerShape(3.dp))
                                    .clickable { error = ""; pane = NbAuthPane.Login },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "返回登录",
                                    color = Palette.white, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                                )
                            }
                        }
                    }
                }

                /* 服务器连接配置（可折叠） */
                Column(Modifier.fillMaxWidth().background(Palette.bg2).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { connOpen = !connOpen }, verticalAlignment = Alignment.CenterVertically) {
                        PanelIcon("link", 13.dp, Palette.muted)
                        Spacer(Modifier.size(7.dp))
                        Text(
                            "服务器连接配置",
                            color = Palette.muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp, modifier = Modifier.weight(1f),
                        )
                        PanelIcon(if (connOpen) "chevronUp" else "chevronDown", 14.dp, Palette.muted)
                    }
                    if (connOpen) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = true,
                            enter = androidx.compose.animation.expandVertically(
                                animationSpec = nbSpringSoft(stiffness = 300f),
                            ) + androidx.compose.animation.fadeIn(tween(220)),
                        ) {
                            Column {
                            Spacer(Modifier.size(10.dp))
                        NbLoginField(url, { url = it; testOk = null; testMsg = "" }, NoteBoxStore.DEFAULT_URL, enabled = !busy, onEnter = {
                            scope.launch {
                                testing = true; testMsg = ""; testOk = null
                                try {
                                    testMsg = withContext(Dispatchers.IO) {
                                        com.servermaintance.panel.core.NoteBoxApi.probe(url)
                                    }
                                    testOk = true
                                } catch (e: Exception) {
                                    testOk = false; testMsg = "连接失败：" + (e.message ?: "未知错误")
                                } finally { testing = false }
                            }
                        })
                        Spacer(Modifier.size(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.background(Color.Transparent, RoundedCornerShape(3.dp))
                                    .border(1.dp, Palette.border2, RoundedCornerShape(3.dp))
                                    .clickable(enabled = !testing) {
                                        scope.launch {
                                            testing = true; testMsg = ""; testOk = null
                                            try {
                                                testMsg = withContext(Dispatchers.IO) { com.servermaintance.panel.core.NoteBoxApi.probe(url) }
                                                testOk = true
                                            } catch (e: Exception) {
                                                testOk = false; testMsg = "连接失败：" + (e.message ?: "未知错误")
                                            } finally { testing = false }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(if (testing) "测试中…" else "测试连接", color = Palette.text, fontSize = 11.5.sp)
                            }
                            Spacer(Modifier.size(10.dp))
                            val ok = testOk
                            Text(
                                testMsg.ifEmpty { "默认 ${NoteBoxStore.DEFAULT_URL}" },
                                color = when (ok) { true -> Palette.ok; false -> Palette.bad; null -> Palette.muted },
                                fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
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

/** 登录卡内的等宽输入框。 */
@Composable
private fun NbLoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
    onEnter: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) AccentState.current.primary else Palette.border2
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            color = Palette.text, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentState.current.primary),
        visualTransformation = if (password) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password
            else androidx.compose.ui.text.input.KeyboardType.Text,
            imeAction = androidx.compose.ui.text.input.ImeAction.Go,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onEnter?.invoke() }),
        modifier = modifier.fillMaxWidth().onFocusEvent { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Palette.input)
                    .border(1.dp, border, RoundedCornerShape(3.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = Palette.muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                inner()
            }
        },
    )
}

/* ==================================================================== *
 * Markdown 渲染已升级为完整实现（对齐网页端 md.js）：
 *   NoteMarkdown.kt   —— 渲染组件（表格/任务/嵌套列表/代码高亮/媒体）
 *   NoteMarkdownParser.kt —— 语法高亮
 *   NoteMarkdownBlocks.kt —— 块级解析 + 行内样式
 * 旧的 MarkdownLite 轻量实现已删除，同名入口见 NoteMarkdown.kt。
 * ==================================================================== */

/* ==================================================================== *
 * 小部件
 * ==================================================================== */

/** 页头右侧的 NoteBox 用户徽标 + 退出（复刻侧栏 who + logout）。 */
@Composable
fun NoteUserChip() {
    val user by NoteBoxState.user.collectAsState()
    val scope = rememberCoroutineScope()
    val email = user
    val initial = email.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .nbPressScale(scale = 0.96f)
            .clip(RoundedCornerShape(50))
            .background(Palette.inset)
            .border(1.dp, Palette.border, RoundedCornerShape(50))
            .padding(start = 4.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(50))
                .background(AccentState.current.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial,
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(7.dp))
        Text(
            email,
            color = Palette.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.size(6.dp))
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(50))
                .clickable { scope.launch { NoteBoxState.logout() } },
            contentAlignment = Alignment.Center,
        ) {
            PanelIcon("logout", 13.dp, Palette.muted)
        }
    }
}

/** NoteBox 统计磁贴（bootstrap/stats 的 notes/pending/done/overdue/today）。 */
@Composable
fun NbStatRow(stats: JsonObject?) {
    if (stats == null) return
    val s = stats
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NbMiniStat("笔记", s.long("notes").toInt(), "note", Modifier.weight(1f), index = 0)
        NbMiniStat("未完成", s.long("pending").toInt(), "checkSquare", Modifier.weight(1f), index = 1)
        NbMiniStat("已完成", s.long("done").toInt(), "checkCircle", Modifier.weight(1f), index = 2)
        NbMiniStat("已过期", s.long("overdue").toInt(), "warn", Modifier.weight(1f), index = 3, bad = s.long("overdue") > 0)
        NbMiniStat("今天到期", s.long("today").toInt(), "calendar", Modifier.weight(1f), index = 4, warn = s.long("today") > 0)
    }
}

@Composable
private fun NbMiniStat(
    label: String,
    value: Int,
    icon: String,
    modifier: Modifier = Modifier,
    index: Int = 0,
    bad: Boolean = false,
    warn: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    /* 悬停轻微上浮 + 图标呼吸放大 */
    val lift by animateFloatAsState(if (hovered) -2f else 0f, nbSpringSoft(stiffness = 500f), label = "statLift")
    val iconScale by animateFloatAsState(if (hovered) 1.12f else 1f, nbSpringPop(), label = "statIcon")
    val valueColor = when {
        bad -> Palette.bad
        warn -> Palette.warn
        else -> Palette.text
    }
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            bad -> Palette.bad.copy(alpha = 0.35f)
            hovered -> AccentState.current.primary.copy(alpha = 0.55f)
            else -> Palette.border
        },
        animationSpec = tween(220),
        label = "statBorder",
    )
    val shown = nbCountUp(value)
    val shape = RoundedCornerShape(nbCardRadius())
    Row(
        modifier.nbEnter(index)
            .graphicsLayer { translationY = lift.dp.toPx() }
            .clip(shape)
            .background(Palette.panel)
            .border(1.dp, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null) {}
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                .clip(RoundedCornerShape(7.dp))
                .background(
                    when {
                        bad -> Palette.bad.copy(alpha = 0.14f)
                        warn -> Palette.warn.copy(alpha = 0.14f)
                        else -> AccentState.current.primary.copy(alpha = 0.12f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            PanelIcon(
                icon, 14.dp,
                when {
                    bad -> Palette.bad
                    warn -> Palette.warn
                    else -> AccentState.current.secondary
                },
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                "$shown",
                color = valueColor,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                maxLines = 1,
            )
            Text(label, color = Palette.muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** 通用「卡片内小表单」按钮排。 */
@Composable
fun NbButtonRow(vararg buttons: Triple<String, Boolean, () -> Unit>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { (text, enabled, onClick) ->
            PrimaryBtn(text = text, onClick = onClick, enabled = enabled)
        }
    }
}
