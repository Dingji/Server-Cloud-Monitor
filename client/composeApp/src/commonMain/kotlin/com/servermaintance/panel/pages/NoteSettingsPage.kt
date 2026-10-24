package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
        title = L["notebox_设置_efb5a47b98"],
        desc = L["账号与服务器连接_91b559cbe1"],
        actions = { NoteUserChip() },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /* 左：改密码 + 连接配置 */
            Column(Modifier.weight(1f)) {
                PanelCard(title = L["修改登录密码_321d9d86f6"], collapsible = false) {
                    Field(oldPw, { oldPw = it }, Modifier.fillMaxWidth(), label = L["原密码_2bd80322a5"], password = true, singleLine = true)
                    Spacer(Modifier.size(8.dp))
                    Field(newPw, { newPw = it }, Modifier.fillMaxWidth(), label = L["新密码_至少_8_位_0d551cb025"], password = true, singleLine = true)
                    Spacer(Modifier.size(12.dp))
                    PrimaryBtn(text = L["修改密码_0ae20ac3af"], onClick = {
                        scope.launch {
                            if (newPw.length < 8) {
                                NoteBoxState.toast(tr("新密码至少_8_位_695025596e"), ToastKind.Warn)
                                return@launch
                            }
                            try {
                                withContext(Dispatchers.IO) { NoteBoxState.api.changePassword(oldPw, newPw) }
                                oldPw = ""; newPw = ""
                                NoteBoxState.toast(tr("密码修改成功_f0e65f05ad"), ToastKind.Ok)
                            } catch (e: Exception) {
                                NoteBoxState.toast(e.message ?: tr("修改失败_9abb465039"), ToastKind.Err)
                            }
                        }
                    })
                }

                PanelCard(title = L["服务器连接_20ba55ffe2"], collapsible = false) {
                    Text(
                        L["notebox_服务地址_默认端口_13345_e0880dfda6"],
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
                            text = if (testing) L["测试中_49562bf14c"] else L["login.test_connection"],
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
                                        testMsg = L["连接失败_eb93205cb2"] + (e.message ?: L["未知错误_5f76edc5de"])
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
                        DangerBtn(text = L["退出_notebox_登录_7577d0fc9f"], onClick = {
                            scope.launch { NoteBoxState.logout() }
                        })
                    }
                }
            }

            /* 右：服务器信息 */
            Column(Modifier.weight(1f)) {
                PanelCard(title = L["服务器信息_d2e0457697"], collapsible = false) {
                    if (stats == null) {
                        Text(L["common.loading"], color = Palette.muted, fontSize = 12.sp)
                    } else {
                        val s: JsonObject = stats!!
                        KVRow(L["版本_989d1affa0"], L["notebox_1_0_2b16f9792f"])
                        KVRow(L["服务器时间_e76ff2af0c"], s.str("server_time").ifEmpty { "-" })
                        KVRow(L["数据库大小_ed9a685d54"], Fmt.bytes(s.long("db_bytes")))
                        KVRow(L["笔记数_c6bb826e1e"], "${s.long("notes")}")
                        KVRow(L["待办_未完成_已完成_7422fade10"], "${s.long("pending")} / ${s.long("done")}")
                        KVRow(L["已过期_今天到期_86491fdf75"], "${s.long("overdue")} / ${s.long("today")}")
                        KVRow(L["今日邮件发送成功_060f80e076"], "${s.long("mail_ok_today")}")
                        KVRow(
                            L["smtp_状态_8658366a9a"],
                            if (NoteBoxState.settings.value?.let { (it["smtp_ready"] as? kotlinx.serialization.json.JsonPrimitive)?.content } == "true") {
                                L["已配置_dc7cd22f34"]
                            } else L["未配置_63595e95b7"],
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
    com.servermaintance.panel.ui.GhostBtn(text = L["保存地址_b4507bac53"], onClick = {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NoteBoxState.reconfigure(url) }
                NoteBoxState.toast(tr("地址已保存_请重新登录_f054bd16b1"), ToastKind.Ok)
            } catch (e: Exception) {
                NoteBoxState.toast(e.message ?: tr("保存失败_40525a7328"), ToastKind.Err)
            }
        }
    })
}
