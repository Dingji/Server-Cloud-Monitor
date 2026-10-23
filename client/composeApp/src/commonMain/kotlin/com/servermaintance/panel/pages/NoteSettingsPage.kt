package com.servermaintance.panel.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.core.NoteBoxStore
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.CheckRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * NoteBox 设置 —— 复刻 notebox「settings」视图 + 登录页的服务器连接配置
 * 端点：POST /api/settings/password   GET /api/stats   GET /api/bootstrap
 * ==================================================================== */

@Composable
fun NoteSettingsPage() {
    NoteGate { SettingsInner() }
}

@Composable
private fun SettingsInner() {
    val scope = rememberCoroutineScope()
    val stats by NoteBoxState.stats.collectAsState()
    val cfg = NoteBoxState.config()

    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }

    /* 服务器连接 */
    var url by remember { mutableStateOf(cfg.normalizedUrl().ifEmpty { NoteBoxStore.DEFAULT_URL }) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) { NoteBoxState.refreshStats() }

    PageScaffold(
        title = "NoteBox 设置",
        desc = "账号与服务器连接",
        actions = { NoteUserChip() },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /* 左：改密码 + 连接配置 */
            Column(Modifier.weight(1f)) {
                PanelCard(title = "修改登录密码", collapsible = false) {
                    Field(oldPw, { oldPw = it }, Modifier.fillMaxWidth(), label = "原密码", password = true, singleLine = true)
                    Spacer(Modifier.size(8.dp))
                    Field(newPw, { newPw = it }, Modifier.fillMaxWidth(), label = "新密码（至少 8 位）", password = true, singleLine = true)
                    Spacer(Modifier.size(12.dp))
                    PrimaryBtn(text = "修改密码", onClick = {
                        scope.launch {
                            if (newPw.length < 8) {
                                NoteBoxState.toast("新密码至少 8 位", ToastKind.Warn)
                                return@launch
                            }
                            try {
                                withContext(Dispatchers.IO) { NoteBoxState.api.changePassword(oldPw, newPw) }
                                oldPw = ""; newPw = ""
                                NoteBoxState.toast("密码修改成功", ToastKind.Ok)
                            } catch (e: Exception) {
                                NoteBoxState.toast(e.message ?: "修改失败", ToastKind.Err)
                            }
                        }
                    })
                }

                PanelCard(title = "服务器连接", collapsible = false) {
                    Text(
                        "NoteBox 服务地址（默认端口 13345）。修改后需要重新登录。",
                        color = Palette.muted, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Field(url, { url = it; testOk = null; testMsg = "" }, Modifier.fillMaxWidth(),
                        hint = NoteBoxStore.DEFAULT_URL, mono = true, singleLine = true)
                    Spacer(Modifier.size(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryBtn(
                            text = if (testing) "测试中…" else "测试连接",
                            enabled = !testing,
                            onClick = {
                                scope.launch {
                                    testing = true; testMsg = ""; testOk = null
                                    try {
                                        testMsg = withContext(Dispatchers.IO) {
                                            com.servermaintance.panel.core.NoteBoxApi.probe(url)
                                        }
                                        testOk = true
                                    } catch (e: Exception) {
                                        testOk = false
                                        testMsg = "连接失败：" + (e.message ?: "未知错误")
                                    } finally { testing = false }
                                }
                            },
                        )
                        GhostSaveBtn(url)
                        val ok = testOk
                        if (ok != null) {
                            Text(
                                testMsg,
                                color = if (ok) Palette.ok else Palette.bad,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DangerBtn(text = "退出 NoteBox 登录", onClick = {
                            scope.launch { NoteBoxState.logout() }
                        })
                    }
                }
            }

            /* 右：服务器信息 */
            Column(Modifier.weight(1f)) {
                PanelCard(title = "服务器信息", collapsible = false) {
                    if (stats == null) {
                        Text("加载中…", color = Palette.muted, fontSize = 12.sp)
                    } else {
                        val s: JsonObject = stats!!
                        KVRow("版本", "NoteBox 1.0")
                        KVRow("服务器时间", s.str("server_time").ifEmpty { "-" })
                        KVRow("数据库大小", Fmt.bytes(s.long("db_bytes")))
                        KVRow("笔记数", "${s.long("notes")}")
                        KVRow("待办（未完成 / 已完成）", "${s.long("pending")} / ${s.long("done")}")
                        KVRow("已过期 / 今天到期", "${s.long("overdue")} / ${s.long("today")}")
                        KVRow("今日邮件发送成功", "${s.long("mail_ok_today")}")
                        KVRow(
                            "SMTP 状态",
                            if (NoteBoxState.settings.value?.let { (it["smtp_ready"] as? kotlinx.serialization.json.JsonPrimitive)?.content } == "true") {
                                "已配置"
                            } else "未配置",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KVRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Palette.muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 130.dp))
        Text(v, color = Palette.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium)
    }
}

/** 保存地址按钮：持久化 + 重新登录。 */
@Composable
private fun GhostSaveBtn(url: String) {
    val scope = rememberCoroutineScope()
    com.servermaintance.panel.ui.GhostBtn(text = "保存地址", onClick = {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NoteBoxState.reconfigure(url) }
                NoteBoxState.toast("地址已保存，请重新登录", ToastKind.Ok)
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: "保存失败", ToastKind.Err)
            }
        }
    })
}
