package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
                logError = e.message ?: L["加载失败_f6b7a41e56"]
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
        title = L["邮件通知_22a119343a"],
        desc = L["notebox_smtp_与提醒规则_cd81b4ede4"],
        actions = {
            LevelBadge(
                if (smtpReady) L["smtp_已配置_70f40873a7"] else L["smtp_未配置_先填账号授权码_c97b712442"],
                if (smtpReady) Level.Ok else Level.Warn,
            )
            Spacer(Modifier.size(10.dp))
            NoteUserChip()
        },
    ) {
        PanelCard(title = L["smtp_账号_163_邮箱_4bbcd0b1d0"], collapsible = false) {
            Text(
                L["163_邮箱_设置_pop3_smtp_2cd036d08b"],
                color = com.servermaintance.panel.ui.Palette.muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(host, { host = it }, Modifier.weight(1.5f), label = L["smtp_主机_9cb2a0f9f3"], hint = "smtp.163.com", singleLine = true)
                Field(port, { port = it }, Modifier.weight(1f), label = L["端口_6cbb7335b5"], hint = "465", mono = true, singleLine = true)
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Toggle(ssl, { ssl = it }, label = L["使用_ssl_465_端口勾选_316a136e99"])
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(user, { user = it }, Modifier.weight(1f), label = L["邮箱账号_fa8cbad280"], hint = L["you_163_com_ca1e042c87"], singleLine = true)
                Field(pass, { pass = it }, Modifier.weight(1f), label = L["授权码_886b52b1db"], hint = L["smtp_授权码_273299a5a3"], password = true, singleLine = true)
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(from, { from = it }, Modifier.weight(1f), label = L["发件人地址_e83d394caa"], hint = L["留空则用邮箱账号_878dd6ff26"], singleLine = true)
                Field(to, { to = it }, Modifier.weight(1f), label = L["收件人_529414cfe5"], hint = L["留空发给自己_多个用逗号分隔_cb0371264d"], singleLine = true)
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryBtn(text = L["保存_smtp_设置_31e1de2129"], onClick = {
                    scope.launch {
                        try {
                            val s = withContext(Dispatchers.IO) { NoteBoxState.api.putSettings(smtpPayload()) }
                            NoteBoxState.applySettings(s)
                            NoteBoxState.toast(tr("smtp_设置已保存_e5e2238754"), ToastKind.Ok)
                        } catch (e: Exception) {
                            NoteBoxState.toast(e.message ?: tr("保存失败_40525a7328"), ToastKind.Err)
                        }
                    }
                })
                PrimaryBtn(
                    text = if (testing) L["发送中_c1b894480d"] else L["发送测试邮件_1eace6fb59"],
                    enabled = !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            try {
                                withContext(Dispatchers.IO) {
                                    NoteBoxState.api.putSettings(smtpPayload())
                                    NoteBoxState.api.testEmail()
                                }
                                NoteBoxState.toast(tr("测试邮件已发送_请查收_bb593d799c"), ToastKind.Ok)
                                loadLog()
                            } catch (e: Exception) {
                                NoteBoxState.toast(e.message ?: tr("发送失败_e767d34c78"), ToastKind.Err, 6000)
                                loadLog()
                            } finally {
                                testing = false
                            }
                        }
                    },
                )
            }
        }

        PanelCard(title = L["通知规则_5229b0abf4"], collapsible = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Toggle(notify, { notify = it }, label = L["启用邮件通知_25c29ea9b4"])
                Toggle(digest, { digest = it }, label = L["启用每日摘要_0a9df7843b"])
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(before, { before = it }, Modifier.widthIn(max = 160.dp), label = L["提前提醒_分钟_0c4a512c91"], mono = true, singleLine = true)
                Field(digestTime, { digestTime = it }, Modifier.widthIn(max = 160.dp), label = L["摘要发送时间_0eccc15130"], hint = L["08_30_c46cf95d73"], mono = true, singleLine = true)
                com.servermaintance.panel.ui.SelectField(
                    tz, listOf("Asia/Shanghai", "Asia/Hong_Kong", "Asia/Tokyo", "UTC"), { tz = it },
                    Modifier.widthIn(max = 220.dp), label = L["时区_fb2a23dc16"],
                )
            }
            Spacer(Modifier.size(8.dp))
            Field(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = L["站点地址_邮件跳转链接_062727b23d"], hint = "http://8.153.153.176:13345", singleLine = true)
            Spacer(Modifier.size(10.dp))
            PrimaryBtn(text = L["保存通知规则_81afc138cf"], onClick = {
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
                        NoteBoxState.toast(tr("通知规则已保存_47bd518d21"), ToastKind.Ok)
                    } catch (e: Exception) {
                        NoteBoxState.toast(e.message ?: tr("保存失败_40525a7328"), ToastKind.Err)
                    }
                }
            })
            Spacer(Modifier.size(8.dp))
            Text(
                L["到期提醒_截止前_n_分钟发送_每日摘要_在设定_0805f20d0d"] +
                    L["每个事项的提醒开关在事项编辑弹窗里_e97512b037"],
                color = com.servermaintance.panel.ui.Palette.muted, fontSize = 11.5.sp, lineHeight = 17.sp,
            )
        }

        PanelCard(title = L["发送记录_90ed819b22"], collapsible = false) {
            when {
                log == null && logError == null -> LoadingState()
                logError != null -> ErrorState(message = logError!!, onRetry = {
                    scope.launch { loadLog() }
                })
                else -> {
                    val rows = log.orEmpty().filterIsInstance<JsonObject>()
                    DataTable(
                        columns = listOf(
                            TableCol(L["时间_89b4aa6364"], 1.6f) { l -> Text(fmtDateTimeSec(l.long("sent_at")), mono = true) },
                            TableCol(L["类型_e4e46c7235"], 0.8f) { l -> Text(l.str("kind")) },
                            TableCol(L["主题_e848ddd482"], 2.4f) { l -> Text(l.str("subject")) },
                            TableCol(L["状态_62e951a692"], 1.2f) { l ->
                                val st = l.str("status")
                                when (st) {
                                    "ok" -> LevelBadge(L["成功_51991a5d11"], Level.Ok)
                                    "skipped" -> LevelBadge(L["跳过_31a98593f1"], Level.Muted)
                                    else -> LevelBadge(l.str("error").ifEmpty { L["失败_3e3c8068bb"] }, Level.Bad)
                                }
                            },
                        ),
                        rows = rows,
                        rowKey = { it.int("id") },
                        emptyText = L["还没有发送记录_8ec160d4de"],
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
