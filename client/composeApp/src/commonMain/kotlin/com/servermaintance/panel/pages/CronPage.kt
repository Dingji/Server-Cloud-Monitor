package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * 定时任务 —— 复刻 upstream/public/js/pages/cron.js（106 行）
 * 端点：GET  /cron → {ts, rootJobs:[…], etcJobs:[…], cronFiles:[…],
 *                    timers:[{next,left,unit,activates,last,passed}],
 *                    dailyHourly:[…]}
 *       POST /cron/action {action, params}
 *              action: add{name,schedule,command} / delete{name}
 * ==================================================================== */

/** JS renderCm 的正则：`/^\/etc\/cron\.d\/(cm-[A-Za-z0-9_-]+):\s*(.+)$/`。 */
private val CM_JOB_RE = Regex("^/etc/cron\\.d/(cm-[A-Za-z0-9_-]+):\\s*(.+)$")

private class CmJob(val file: String, val line: String)

/** 等宽预块（JS `<pre class="pre">`）。 */
@Composable
private fun PreBox(text: String, maxHeight: Dp = 240.dp) {
    Box(
        Modifier.fillMaxWidth().heightIn(max = maxHeight)
            .background(Palette.tableSurface, RectangleShape)
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text,
            color = Palette.text,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
        )
    }
}

/** 次要说明文本（JS `<div class="muted small">`）。 */
@Composable
private fun Muted(text: String) {
    Text(text, color = Palette.muted, fontSize = 12.sp, lineHeight = 20.sp)
}

@Composable
fun CronPage() {
    var data by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            data = AppState.api.get("/cron", cacheMs = 5000)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e::class.simpleName ?: L["请求失败_8fdc4112a4"]
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    PageScaffold(title = L["定时任务_c97ffbda2e"]) {
        when {
            loading && data == null && error == null -> LoadingState()
            error != null && data == null -> ErrorState(message = error!!, onRetry = {
                scope.launch { loading = true; load() }
            })
            else -> {
                val d = data ?: JsonObject(emptyMap())
                val rootJobs = d.arr("rootJobs").map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
                val etcJobs = d.arr("etcJobs").map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
                val timers = d.arr("timers").filterIsInstance<JsonObject>()
                val dailyHourly = d.arr("dailyHourly").map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }

                val cmJobs = etcJobs.filter { it.contains("cm-") }
                val otherJobs = etcJobs.filterNot { it.contains("cm-") }

                /* ---------------- 三磁贴（JS grid g3 mb） ---------------- */
                GridLayout(columns = 3) {
                    StatTile(label = L["面板管理的任务_8c1ae9316e"], value = "${cmJobs.size}", sub = "/etc/cron.d/cm-*")
                    StatTile(label = L["root_crontab_165ab8a774"], value = "${rootJobs.size}", sub = L["crontab_l_条目_6be8b3d177"])
                    StatTile(label = L["systemd_定时器_f8d95995f8"], value = "${timers.size}", sub = L["systemctl_list_timers_1d28f42125"])
                }

                /* ---------------- 新建 + 说明（JS grid g2） ---------------- */
                GridLayout(columns = 2) {
                    PanelCard(
                        title = L["新建定时任务_root_权限执行_d637db8d7b"],
                        right = { PrimaryBtn(text = L["创建_fcbd093292"], onClick = {
                            scope.launch {
                                val n = name.trim(); val sc = schedule.trim(); val cmd = command.trim()
                                if (n.isEmpty() || sc.isEmpty() || cmd.isEmpty()) {
                                    AppState.toast(tr("请填写完整_0e68428a12"), ToastKind.Warn)
                                    return@launch
                                }
                                val okc = Modal.confirm(
                                    title = L["创建定时任务_cc992174ac"],
                                    text = L["sc_root_cmd_任务名_cm_n_cfc3e33d90"],
                                    okText = L["创建_fcbd093292"],
                                )
                                if (!okc) return@launch
                                try {
                                    Actions.run(
                                        path = "/cron/action",
                                        body = jsonObjectOf(
                                            "action" to "add",
                                            "params" to jsonObjectOf("name" to n, "schedule" to sc, "command" to cmd),
                                        ),
                                        successToast = L["操作成功_3c5be5f2a1"],
                                    )
                                } finally {
                                    load()
                                }
                            }
                        }) },
                    ) {
                        GridLayout(columns = 2) {
                            Field(
                                value = name,
                                onValueChange = { name = it },
                                label = L["任务名_c8d6eeaa4c"],
                                hint = "backup-www",
                                mono = true,
                            )
                            Field(
                                value = schedule,
                                onValueChange = { schedule = it },
                                label = L["cron_表达式_6859f82ab2"],
                                hint = L["0_3_e98da8159a"],
                                mono = true,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Field(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = L["执行命令_bf162782f5"],
                            hint = "/usr/local/bin/backup.sh",
                            mono = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        Muted(
                            L["表达式格式_分_时_日_月_周_例_0_3_191b93658a"] +
                                L["0_6_每6小时_任务写入_e_369a8da904"],
                        )
                    }

                    PanelCard(title = L["说明_26670dda42"]) {
                        Muted(L["此处创建的任务带_cm_前缀_可在下方表格_a7728c7ff7"])
                        Muted(L["手动编辑的_root_crontab_请通过_72bfbdde79"])
                        Muted(L["systemd_定时器由_systemd_管_6244b23917"])
                        Muted(
                            L["etc_cron_daily_cron_h_0b91b3ac69"] +
                                (dailyHourly.take(8).joinToString(L["s_d3bc9a378d"]).ifEmpty { L["空_9e1138c983"] }),
                        )
                    }
                }

                /* ---------------- 面板管理的任务表（JS renderCm） ---------------- */
                val cmRows = remember(cmJobs) {
                    cmJobs.map { l ->
                        val m = CM_JOB_RE.find(l)
                        if (m != null) CmJob(m.groupValues[1], m.groupValues[2])
                        else CmJob(l.removePrefix("/etc/cron.d/"), l)
                    }
                }
                PanelCard(title = trf("面板管理的定时任务_0_7c6321b375", cmRows.size)) {
                    DataTable(
                        columns = listOf(
                            TableCol<CmJob>(L["任务文件_8f4e93dc3d"], weight = 1f, mono = true) {
                                Text(it.file, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<CmJob>(L["内容_163aec9194"], weight = 2.6f, mono = true) {
                                Text(it.line, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            },
                            TableCol<CmJob>("", weight = 0.7f, minWidth = 76.dp) { job ->
                                DangerBtn(text = L["删除_3755f56f2f"], icon = "trash", onClick = {
                                    scope.launch {
                                        val okc = Modal.confirm(
                                            title = L["删除定时任务_cbbfd7d59d"],
                                            text = trf("删除_0_1fbd66c221", job.file),
                                            danger = true,
                                        )
                                        if (!okc) return@launch
                                        val delName = job.file.removePrefix("cm-")
                                        try {
                                            Actions.run(
                                                path = "/cron/action",
                                                body = jsonObjectOf(
                                                    "action" to "delete",
                                                    "params" to jsonObjectOf("name" to delName),
                                                ),
                                                successToast = L["操作成功_3c5be5f2a1"],
                                            )
                                        } finally {
                                            load()
                                        }
                                    }
                                })
                            },
                        ),
                        rows = cmRows,
                        rowKey = { it.file },
                        emptyText = L["暂无面板创建的任务_e6b14e96f2"],
                    )
                }

                /* ---------------- crontab 原文（JS grid g2 pre） ---------------- */
                GridLayout(columns = 2) {
                    PanelCard(title = L["root_crontab_165ab8a774"]) {
                        PreBox(rootJobs.joinToString("\n").ifEmpty { L["空_9e1138c983"] }, maxHeight = 240.dp)
                    }
                    PanelCard(title = L["etc_cron_d_其它任务_1075d72b5f"]) {
                        PreBox(otherJobs.joinToString("\n").take(4000).ifEmpty { L["空_9e1138c983"] }, maxHeight = 240.dp)
                    }
                }

                /* ---------------- Systemd 定时器表 ---------------- */
                PanelCard(title = trf("systemd_定时器_0_dc0dc6e637", timers.size)) {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(L["下次执行_373074f4ee"], weight = 1.2f, mono = true) {
                                Text(it.str("next"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>(L["倒计时_80176c18f3"], weight = 0.9f, mono = true) {
                                Text(it.str("left"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(L["定时器_70cbf60aeb"], weight = 1.6f, mono = true) {
                                Text(it.str("unit"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>(L["执行单元_2466ded40e"], weight = 1.6f, mono = true) {
                                Text(it.str("activates"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>(L["上次执行_434c6bf57e"], weight = 1.2f, mono = true) {
                                Text(it.str("last"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>(L["已过去_745c1695da"], weight = 0.9f, mono = true) {
                                Text(it.str("passed"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            },
                        ),
                        rows = timers,
                        rowKey = { it.str("unit") + "#" + it.str("next") },
                        emptyText = L["无_systemd_定时器_2cb3ed5b5f"],
                    )
                }
            }
        }
    }
}
