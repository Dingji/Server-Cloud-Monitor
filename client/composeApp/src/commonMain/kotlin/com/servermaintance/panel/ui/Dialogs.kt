package com.servermaintance.panel.ui
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.platformClipboard
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.HandheldLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/* ------------------------------------------------------------------ *
 * 表单字段（modal.prompt 的字段描述）
 * ------------------------------------------------------------------ */

sealed class FormField {
    abstract val name: String
    abstract val label: String

    data class Text(
        override val name: String,
        override val label: String,
        val value: String = "",
        val hint: String = "",
        val mono: Boolean = false,
    ) : FormField()

    data class Password(
        override val name: String,
        override val label: String,
        val value: String = "",
    ) : FormField()

    data class Select(
        override val name: String,
        override val label: String,
        val options: List<String>,
        val value: String = "",
    ) : FormField()
}

/* ------------------------------------------------------------------ *
 * Modal —— 复刻 window.modal（core.js）
 * ------------------------------------------------------------------ */

/**
 * 全局对话框。三个 suspend 方法在 [ModalHost] 渲染弹窗并等待用户操作后返回；
 * 同时弹多个时排队逐个展示。失败类输出弹窗带「失败」徽章、可复制。
 */
object Modal {

    internal sealed class Request {
        abstract val title: String
        abstract val okText: String
        abstract val danger: Boolean

        data class Confirm(
            override val title: String,
            val text: String,
            override val okText: String,
            override val danger: Boolean,
        ) : Request()

        data class Prompt(
            override val title: String,
            val fields: List<FormField>,
            override val okText: String,
            override val danger: Boolean,
        ) : Request()

        data class Output(
            override val title: String,
            val text: String,
            val isError: Boolean,
            override val okText: String,
        ) : Request() {
            override val danger: Boolean get() = isError
        }
    }

    internal val display = mutableStateOf<Request?>(null)

    private var active: Pair<Request, CompletableDeferred<Any?>>? = null
    private val queue = ArrayDeque<Pair<Request, CompletableDeferred<Any?>>>()
    private val mutex = Mutex()

    /** 是否有弹窗打开（AutoRefresh 的 guarded 判断）。 */
    val isOpen: Boolean get() = display.value != null

    /** 确认框：确定 true，取消/点遮罩 false。 */
    suspend fun confirm(title: String, text: String, okText: String = L["确认_b56d9ac6c5"], danger: Boolean = false): Boolean =
        show(Request.Confirm(title, text, okText, danger), false) as Boolean

    /** 表单弹窗：取消返回 null，确认返回 name -> value 映射。 */
    suspend fun prompt(
        title: String,
        fields: List<FormField>,
        okText: String = L["确认_b56d9ac6c5"],
        danger: Boolean = false,
    ): Map<String, String>? {
        @Suppress("UNCHECKED_CAST")
        return show(Request.Prompt(title, fields, okText, danger), null) as Map<String, String>?
    }

    /** 输出弹窗：展示命令输出，关闭后返回。 */
    suspend fun output(title: String, text: String, isError: Boolean = false, okText: String = L["关闭_6c14bd7f6f"]) {
        show(Request.Output(title, text, isError, okText), Unit)
    }

    private suspend fun show(req: Request, fallback: Any?): Any? {
        val deferred = CompletableDeferred<Any?>()
        mutex.withLock {
            if (active == null) {
                active = req to deferred
                display.value = req
            } else {
                queue.addLast(req to deferred)
            }
        }
        return deferred.await() ?: fallback
    }

    /** ModalHost 在用户操作后调用：完成当前请求并展示队列中的下一个。 */
    internal suspend fun resolve(result: Any?) {
        var done: Pair<Request, CompletableDeferred<Any?>>? = null
        mutex.withLock {
            val cur = active
            if (cur != null) {
                active = null
                display.value = null
                done = cur
                val next = queue.removeFirstOrNull()
                if (next != null) {
                    active = next
                    display.value = next.first
                }
            }
        }
        done?.second?.complete(result)
    }

    internal fun cancelResult(req: Request): Any? = when (req) {
        is Request.Confirm -> false
        is Request.Prompt -> null
        is Request.Output -> Unit
    }

    internal fun okResult(req: Request, values: Map<String, String>): Any? = when (req) {
        is Request.Confirm -> true
        is Request.Prompt -> values
        is Request.Output -> Unit
    }
}

/* ------------------------------------------------------------------ *
 * 宿主（由 shell 渲染在根层）
 * ------------------------------------------------------------------ */

/**
 * 全局弹窗宿主：渲染 [Modal] 的当前请求（复刻 .modal-mask / .modal：
 * 遮罩点击取消、顶部 2px accent、mono 标题、右对齐按钮组）。
 * 必须放在根容器最上层。
 */
@Composable
fun ModalHost() {
    val req = Modal.display.value ?: return
    ModalBody(req)
}

@Composable
private fun ModalBody(req: Modal.Request) {
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(Dim.radius.dp)

    // prompt 字段值（记住弹窗身份，关闭即丢）
    val values = remember(req) {
        val m = mutableStateMapOf<String, String>()
        if (req is Modal.Request.Prompt) {
            req.fields.forEach { f ->
                m[f.name] = when (f) {
                    is FormField.Text -> f.value
                    is FormField.Password -> f.value
                    is FormField.Select ->
                        if (f.value.isNotEmpty() && f.value in f.options) f.value
                        else f.options.firstOrNull() ?: ""
                }
            }
        }
        m
    }

    val (cfgMin, cfgMax) = when (req) {
        is Modal.Request.Output ->
            if (platformHandheld) 0.dp to 720.dp else 760.dp to 760.dp
        is Modal.Request.Prompt ->
            if (platformHandheld) 0.dp to 480.dp else 420.dp to 480.dp
        is Modal.Request.Confirm ->
            if (platformHandheld) 0.dp to 520.dp else 420.dp to 520.dp
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Palette.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { scope.launch { Modal.resolve(Modal.cancelResult(req)) } },
        contentAlignment = Alignment.Center,
    ) {
        val avail = maxWidth - HandheldLayout.modalHorizontalMargin() * 2
        val dismissClick = remember { MutableInteractionSource() }

        Column(
            Modifier.widthIn(min = minOf(cfgMin, avail), max = minOf(cfgMax, avail))
                .clip(shape)
                .background(Palette.panel)
                .border(1.dp, Palette.border2, shape)
                // 吞掉弹窗内部点击，避免穿透遮罩
                .clickable(interactionSource = dismissClick, indication = null) { /* consume */ },
        ) {
            // 顶部 accent 线（.modal border-top: 2px solid var(--accent)）
            Box(Modifier.fillMaxWidth().height(2.dp).background(AccentState.current.primary))

            // 头
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    req.title,
                    color = Palette.text,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (req is Modal.Request.Output) {
                    Spacer(Modifier.width(8.dp))
                    if (req.isError) {
                        LevelBadge(L["失败_3e3c8068bb"], Level.Bad)
                    } else {
                        LevelBadge(L["成功_51991a5d11"], Level.Ok)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))

            // 体
            Column(
                Modifier.fillMaxWidth()
                    .then(
                        if (req !is Modal.Request.Output) {
                            Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                        } else Modifier,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                when (req) {
                    is Modal.Request.Confirm -> {
                        Text(req.text, color = Palette.text, fontSize = 13.sp, lineHeight = 21.sp)
                    }
                    is Modal.Request.Prompt -> {
                        req.fields.forEachIndexed { i, f ->
                            if (i > 0) Spacer(Modifier.height(10.dp))
                            when (f) {
                                is FormField.Text -> Field(
                                    value = values[f.name] ?: "",
                                    onValueChange = { values[f.name] = it },
                                    label = f.label,
                                    hint = f.hint,
                                    mono = f.mono,
                                )
                                is FormField.Password -> Field(
                                    value = values[f.name] ?: "",
                                    onValueChange = { values[f.name] = it },
                                    label = f.label,
                                    password = true,
                                )
                                is FormField.Select -> SelectField(
                                    value = values[f.name] ?: "",
                                    options = f.options,
                                    onValueChange = { values[f.name] = it },
                                    label = f.label,
                                )
                            }
                        }
                    }
                    is Modal.Request.Output -> {
                        PreBlock(req.text, maxHeight = 420.dp)
                    }
                }
            }

            // 底
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (req) {
                    is Modal.Request.Output -> {
                        GhostBtn(L["复制_4edd1d0087"], onClick = {
                            platformClipboard.copy(req.text)
                            AppState.toast(tr("已复制到剪贴板_c2bb6d50ae"), ToastKind.Ok)
                        }, icon = "copy")
                        PrimaryBtn(req.okText, onClick = { scope.launch { Modal.resolve(Unit) } })
                    }
                    else -> {
                        GhostBtn(L["win.dialog.cancel"], onClick = { scope.launch { Modal.resolve(Modal.cancelResult(req)) } })
                        val onOk: () -> Unit = {
                            scope.launch { Modal.resolve(Modal.okResult(req, values.toMap())) }
                        }
                        if (req.danger) {
                            DangerBtn(req.okText, onClick = onOk)
                        } else {
                            PrimaryBtn(req.okText, onClick = onOk)
                        }
                    }
                }
            }
        }
    }
}
