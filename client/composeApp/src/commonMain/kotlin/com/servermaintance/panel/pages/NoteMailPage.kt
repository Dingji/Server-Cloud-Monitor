package com.servermaintance.panel.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * 邮件通知 —— 复刻 notebox「mail」视图
 * 端点：GET/PUT /api/settings   POST /api/settings/test-email   GET /api/emails
 * ==================================================================== */

@Composable
fun NoteMailPage() {
    NoteGate { MailInner() }
}

@Composable
private fun MailInner() {
    val scope = rememberCoroutineScope()
    val settings by NoteBoxState.settings.collectAsState()

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("465") }
    var ssl by remember { mutableStateOf(true) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var notify by remember { mutableStateOf(true) }
    var digest by remember { mutableStateOf(true) }
    var before by remember { mutableStateOf("30") }
    var digestTime by remember { mutableStateOf("08:30") }
    var tz by remember { mutableStateOf("Asia/Shanghai") }
    var baseUrl by remember { mutableStateOf("") }
    var filled by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    var log by remember { mutableStateOf<List<JsonElement>?>(null) }
    var logError by remember { mutableStateOf<String?>(null) }

    suspend fun loadLog() {
        try {
            log = withContext(Dispatchers.IO) { NoteBoxState.api.emailLog() }
            logError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? com.servermaintance.panel.core.ApiException)?.isAuth != true) {
                logError = e.message ?: "加载失败"
            }
        }
    }

    LaunchedEffect(Unit) {
        NoteBoxState.refreshSettings()
        loadLog()
    }

    // settings 到达后填充表单（只填一次，避免覆盖用户编辑）
    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (filled) return@LaunchedEffect
        host = s.str("smtp_host"); port = s.str("smtp_port").ifEmpty { "465" }
        ssl = s.str("smtp_ssl") == "1"
        user = s.str("smtp_user"); pass = s.str("smtp_pass")
        from = s.str("mail_from"); to = s.str("mail_to")
        notify = s.str("notify_enabled") == "1"
        digest = s.str("digest_enabled") == "1"
        before = s.str("remind_before_min").ifEmpty { "30" }
        digestTime = s.str("digest_time").ifEmpty { "08:30" }
        tz = s.str("digest_tz").ifEmpty { "Asia/Shanghai" }
        baseUrl = s.str("base_url")
        filled = true
    }

    val smtpReady = settings?.bool("smtp_ready") == true

    fun smtpPayload() = mapOf(
        "smtp_host" to host.trim(), "smtp_port" to port.trim(), "smtp_ssl" to ssl,
        "smtp_user" to user.trim(), "smtp_pass" to pass,
        "mail_from" to from.trim(), "mail_to" to to.trim(),
    )

    PageScaffold(
        title = "邮件通知",
        desc = "NoteBox SMTP 与提醒规则",
        actions = {
            LevelBadge(
                if (smtpReady) "SMTP 已配置" else "SMTP 未配置（先填账号授权码）",
                if (smtpReady) Level.Ok else Level.Warn,
            )
            Spacer(Modifier.size(10.dp))
            NoteUserChip()
        },
    ) {
        PanelCard(title = "SMTP 账号（163 邮箱）", collapsible = false) {
            Text(
                "163 邮箱：设置 → POP3/SMTP → 开启并生成「授权码」，填入下方（不是登录密码）。",
                color = com.servermaintance.panel.ui.Palette.muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(host, { host = it }, Modifier.weight(1.5f), label = "SMTP 主机", hint = "smtp.163.com", singleLine = true)
                Field(port, { port = it }, Modifier.weight(1f), label = "端口", hint = "465", mono = true, singleLine = true)
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Toggle(ssl, { ssl = it }, label = "使用 SSL（465 端口勾选）")
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(user, { user = it }, Modifier.weight(1f), label = "邮箱账号", hint = "you@163.com", singleLine = true)
                Field(pass, { pass = it }, Modifier.weight(1f), label = "授权码", hint = "SMTP 授权码", password = true, singleLine = true)
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(from, { from = it }, Modifier.weight(1f), label = "发件人地址", hint = "留空则用邮箱账号", singleLine = true)
                Field(to, { to = it }, Modifier.weight(1f), label = "收件人", hint = "留空发给自己；多个用逗号分隔", singleLine = true)
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryBtn(text = "保存 SMTP 设置", onClick = {
                    scope.launch {
                        try {
                            val s = withContext(Dispatchers.IO) { NoteBoxState.api.putSettings(smtpPayload()) }
                            NoteBoxState.applySettings(s)
                            NoteBoxState.toast("SMTP 设置已保存", ToastKind.Ok)
                        } catch (e: Exception) {
                            NoteBoxState.toast(e.message ?: "保存失败", ToastKind.Err)
                        }
                    }
                })
                PrimaryBtn(
                    text = if (testing) "发送中…" else "发送测试邮件",
                    enabled = !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            try {
                                withContext(Dispatchers.IO) {
                                    NoteBoxState.api.putSettings(smtpPayload())
                                    NoteBoxState.api.testEmail()
                                }
                                NoteBoxState.toast("测试邮件已发送，请查收", ToastKind.Ok)
                                loadLog()
                            } catch (e: Exception) {
                                NoteBoxState.toast(e.message ?: "发送失败", ToastKind.Err, 6000)
                                loadLog()
                            } finally {
                                testing = false
                            }
                        }
                    },
                )
            }
        }

        PanelCard(title = "通知规则", collapsible = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Toggle(notify, { notify = it }, label = "启用邮件通知")
                Toggle(digest, { digest = it }, label = "启用每日摘要")
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(before, { before = it }, Modifier.widthIn(max = 160.dp), label = "提前提醒（分钟）", mono = true, singleLine = true)
                Field(digestTime, { digestTime = it }, Modifier.widthIn(max = 160.dp), label = "摘要发送时间", hint = "08:30", mono = true, singleLine = true)
                com.servermaintance.panel.ui.SelectField(
                    tz, listOf("Asia/Shanghai", "Asia/Hong_Kong", "Asia/Tokyo", "UTC"), { tz = it },
                    Modifier.widthIn(max = 220.dp), label = "时区",
                )
            }
            Spacer(Modifier.size(8.dp))
            Field(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = "站点地址（邮件跳转链接）", hint = "http://8.153.153.176:13345", singleLine = true)
            Spacer(Modifier.size(10.dp))
            PrimaryBtn(text = "保存通知规则", onClick = {
                scope.launch {
                    try {
                        val s = withContext(Dispatchers.IO) {
                            NoteBoxState.api.putSettings(mapOf(
                                "notify_enabled" to notify, "digest_enabled" to digest,
                                "remind_before_min" to before, "digest_time" to digestTime, "digest_tz" to tz,
                                "base_url" to baseUrl.trim(),
                            ))
                        }
                        NoteBoxState.applySettings(s)
                        NoteBoxState.toast("通知规则已保存", ToastKind.Ok)
                    } catch (e: Exception) {
                        NoteBoxState.toast(e.message ?: "保存失败", ToastKind.Err)
                    }
                }
            })
            Spacer(Modifier.size(8.dp))
            Text(
                "到期提醒：截止前 N 分钟发送；每日摘要：在设定时间汇总「已过期 + 今天到期」的事项。" +
                    "每个事项的提醒开关在事项编辑弹窗里。",
                color = com.servermaintance.panel.ui.Palette.muted, fontSize = 11.5.sp, lineHeight = 17.sp,
            )
        }

        PanelCard(title = "发送记录", collapsible = false) {
            when {
                log == null && logError == null -> LoadingState()
                logError != null -> ErrorState(message = logError!!, onRetry = {
                    scope.launch { loadLog() }
                })
                else -> {
                    val rows = log.orEmpty().filterIsInstance<JsonObject>()
                    DataTable(
                        columns = listOf(
                            TableCol("时间", 1.6f) { l -> Text(fmtDateTimeSec(l.long("sent_at")), mono = true) },
                            TableCol("类型", 0.8f) { l -> Text(l.str("kind")) },
                            TableCol("主题", 2.4f) { l -> Text(l.str("subject")) },
                            TableCol("状态", 1.2f) { l ->
                                val st = l.str("status")
                                when (st) {
                                    "ok" -> LevelBadge("成功", Level.Ok)
                                    "skipped" -> LevelBadge("跳过", Level.Muted)
                                    else -> LevelBadge(l.str("error").ifEmpty { "失败" }, Level.Bad)
                                }
                            },
                        ),
                        rows = rows,
                        rowKey = { it.int("id") },
                        emptyText = "还没有发送记录",
                    )
                }
            }
        }
    }
}

private val monoStyle = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
)

/** 表格单元文字（mono=true 用等宽）。 */
@Composable
private fun Text(text: String, mono: Boolean = false) {
    androidx.compose.material3.Text(
        text = text,
        style = if (mono) monoStyle else androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
        color = com.servermaintance.panel.ui.Palette.text,
        maxLines = 1,
    )
}
