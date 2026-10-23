package com.servermaintance.panel.pages

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
            error = e.message ?: e::class.simpleName ?: "请求失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    PageScaffold(title = "定时任务") {
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
                    StatTile(label = "面板管理的任务", value = "${cmJobs.size}", sub = "/etc/cron.d/cm-*")
                    StatTile(label = "Root Crontab", value = "${rootJobs.size}", sub = "crontab -l 条目")
                    StatTile(label = "Systemd 定时器", value = "${timers.size}", sub = "systemctl list-timers")
                }

                /* ---------------- 新建 + 说明（JS grid g2） ---------------- */
                GridLayout(columns = 2) {
                    PanelCard(
                        title = "新建定时任务（root 权限执行）",
                        right = { PrimaryBtn(text = "创建", onClick = {
                            scope.launch {
                                val n = name.trim(); val sc = schedule.trim(); val cmd = command.trim()
                                if (n.isEmpty() || sc.isEmpty() || cmd.isEmpty()) {
                                    AppState.toast("请填写完整", ToastKind.Warn)
                                    return@launch
                                }
                                val okc = Modal.confirm(
                                    title = "创建定时任务",
                                    text = "$sc root $cmd\n任务名: cm-$n",
                                    okText = "创建",
                                )
                                if (!okc) return@launch
                                try {
                                    Actions.run(
                                        path = "/cron/action",
                                        body = jsonObjectOf(
                                            "action" to "add",
                                            "params" to jsonObjectOf("name" to n, "schedule" to sc, "command" to cmd),
                                        ),
                                        successToast = "操作成功",
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
                                label = "任务名",
                                hint = "backup-www",
                                mono = true,
                            )
                            Field(
                                value = schedule,
                                onValueChange = { schedule = it },
                                label = "Cron 表达式",
                                hint = "0 3 * * *",
                                mono = true,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Field(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "执行命令",
                            hint = "/usr/local/bin/backup.sh",
                            mono = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        Muted(
                            "表达式格式：分 时 日 月 周，例：0 3 * * * 每天3点 · */5 * * * * 每5分钟 · " +
                                "0 */6 * * * 每6小时。任务写入 /etc/cron.d/cm-<名>，以 root 执行。",
                        )
                    }

                    PanelCard(title = "说明") {
                        Muted("· 此处创建的任务带 cm- 前缀，可在下方表格中直接删除")
                        Muted("· 手动编辑的 root crontab 请通过 Web 终端操作（crontab -e）")
                        Muted("· Systemd 定时器由 systemd 管理，仅展示；")
                        Muted(
                            "· /etc/cron.daily、cron.hourly 脚本：" +
                                (dailyHourly.take(8).joinToString(", ").ifEmpty { "(空)" }),
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
                PanelCard(title = "面板管理的定时任务 (${cmRows.size})") {
                    DataTable(
                        columns = listOf(
                            TableCol<CmJob>("任务文件", weight = 1f, mono = true) {
                                Text(it.file, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<CmJob>("内容", weight = 2.6f, mono = true) {
                                Text(it.line, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            },
                            TableCol<CmJob>("", weight = 0.7f, minWidth = 76.dp) { job ->
                                DangerBtn(text = "删除", icon = "trash", onClick = {
                                    scope.launch {
                                        val okc = Modal.confirm(
                                            title = "删除定时任务",
                                            text = "删除 ${job.file}？",
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
                                                successToast = "操作成功",
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
                        emptyText = "暂无面板创建的任务",
                    )
                }

                /* ---------------- crontab 原文（JS grid g2 pre） ---------------- */
                GridLayout(columns = 2) {
                    PanelCard(title = "Root Crontab") {
                        PreBox(rootJobs.joinToString("\n").ifEmpty { "(空)" }, maxHeight = 240.dp)
                    }
                    PanelCard(title = "/etc/cron.d 其它任务") {
                        PreBox(otherJobs.joinToString("\n").take(4000).ifEmpty { "(空)" }, maxHeight = 240.dp)
                    }
                }

                /* ---------------- Systemd 定时器表 ---------------- */
                PanelCard(title = "Systemd 定时器 (${timers.size})") {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>("下次执行", weight = 1.2f, mono = true) {
                                Text(it.str("next"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>("倒计时", weight = 0.9f, mono = true) {
                                Text(it.str("left"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            },
                            TableCol<JsonObject>("定时器", weight = 1.6f, mono = true) {
                                Text(it.str("unit"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>("执行单元", weight = 1.6f, mono = true) {
                                Text(it.str("activates"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>("上次执行", weight = 1.2f, mono = true) {
                                Text(it.str("last"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>("已过去", weight = 0.9f, mono = true) {
                                Text(it.str("passed"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            },
                        ),
                        rows = timers,
                        rowKey = { it.str("unit") + "#" + it.str("next") },
                        emptyText = "无 systemd 定时器",
                    )
                }
            }
        }
    }
}
