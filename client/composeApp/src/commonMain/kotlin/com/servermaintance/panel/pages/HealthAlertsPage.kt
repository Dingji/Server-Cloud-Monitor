package com.servermaintance.panel.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* =====================================================================
 * 健康告警 —— 复刻 upstream/public/js/pages/healthalerts.js (152 行)
 *
 * 卡片清单（自检）：
 *  H0  未运行健康检查时的提示卡（reason + 启用方式脚本 + timer 说明）
 *  H1  磁贴 ×4：检查项 / 通过 / 失败 / 当前状态
 *  H2  失败的检查（failing > 0 时出现，列出 failingNames）
 *  H3  检查项明细 (N) + 刷新按钮（名称/状态/当前状态起始/最近告警）
 *  H4  告警通道（收件人·服务器·发件账号·发件人名·检查脚本·状态文件）
 *      + 发送测试邮件 → POST /relayops/alert/test（弹出 output）
 *  H5  最近一次状态备份（结果/时间/大小/文件内条目/远端推送）
 *  H6  容量趋势（近 7 天）—— /metrics/trend 的 磁盘/内存/CPU 逐日外推
 *  H7  相关定时器（定时器/执行单元/下次执行/倒计时/上次执行）
 *  H8  告警日志（/var/log/relay-alert.log）+ 清屏
 *  H9  告警阈值配置（按 brief 补充；面板同款表单在「面板设置」页，
 *      字段照抄 settings.js 的 al-enabled/al-cpu/al-mem/al-disk/al-cd）
 *  H10 告警历史（按 brief 补充；/alerts + WS 实时推送，面板用右上角铃铛呈现）
 * ===================================================================== */

private data class TrendRow(
    val k: String,
    val now: String,
    val slope: String,
    val eta: String,
    val days: Int,
)

/** 双精度按 JS Number.toString 的口径输出（整数不带 .0）。 */
private fun numStr(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/** healthalerts.js 内联的 trend 行构造。 */
private fun trendRow(label: String, t: JsonObject?): TrendRow {
    val pts = t?.arr("points") ?: emptyList()
    if (t == null || pts.isEmpty()) return TrendRow(label, "—", "—", "暂无数据", 0)
    val n = pts.size
    val now = numStr(t.num("current")) + "%"
    if (!t.containsKey("slopePerDay") || t["slopePerDay"] == null) {
        return TrendRow(label, now, "需 ≥2 天", "—", n)
    }
    val s = t.num("slopePerDay")
    val eta = when {
        s > 0.01 -> {
            val d = if (t.containsKey("daysToFull") && t["daysToFull"] != null) t.num("daysToFull") else null
            if (d != null && d > 0) "${kotlin.math.round(d).toLong()} 天后达 100%" else "持续增长"
        }
        s < -0.01 -> "下降中"
        else -> "稳定"
    }
    return TrendRow(
        k = label,
        now = now,
        slope = (if (s >= 0) "+" else "") + numStr(s) + "%/天",
        eta = eta,
        days = n,
    )
}

@Composable
fun HealthAlertsPage() {
    val scope = rememberCoroutineScope()
    val alerts by AppState.alerts.collectAsState()
    val alertCfg by AppState.alertConfig.collectAsState()

    var health by remember { mutableStateOf<JsonObject?>(null) }
    var trend by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var logCleared by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching {
            val a = AppState.api.get("/relayops/health")
            health = a as? JsonObject
            trend = runCatching { AppState.api.get("/metrics/trend?days=7") as? JsonObject }.getOrNull()
            error = null
        }.onFailure { e -> error = e.message }
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    /* 面板本身靠「刷新」按钮；App 里补一轮 30 秒轮询保持新鲜 */
    AutoRefresh(key = "healthalerts", intervalMs = { 30_000 }) { load() }

    /* ---------- 阈值表单（POST /alerts/config，字段同 settings.js） ---------- */
    var cfgEnabled by remember { mutableStateOf(true) }
    var cfgCpu by remember { mutableStateOf("90") }
    var cfgMem by remember { mutableStateOf("90") }
    var cfgDisk by remember { mutableStateOf("95") }
    var cfgCd by remember { mutableStateOf("10") }
    LaunchedEffect(alertCfg) {
        val c = alertCfg ?: return@LaunchedEffect
        cfgEnabled = c.bool("enabled")
        cfgCpu = c.int("cpu").toString()
        cfgMem = c.int("mem").toString()
        cfgDisk = c.int("disk").toString()
        cfgCd = c.int("cooldownMin").toString()
    }

    PageScaffold(
        title = "健康告警",
        desc = "relay_alert.py 检查、SMTP 告警通道、阈值与告警记录",
        actions = { GhostBtn(text = "刷新", onClick = { scope.launch { load() } }) },
    ) {
        val d = health

        if (loading && d == null) Text("加载中…", color = Palette.muted, fontSize = 13.sp)
        if (error != null && d == null) Text(error ?: "加载失败", color = Palette.bad, fontSize = 13.sp)

        /* ---- H0：尚未运行健康检查 ---- */
        if (d != null && d.bool("available") == false) {
            PanelCard(title = "尚未运行健康检查", cardKey = "ha-none", collapsible = false) {
                if (d.str("reason").isNotEmpty()) {
                    Text(d.str("reason"), color = Palette.muted, fontSize = 13.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(4.dp))
                }
                val script = d.obj("sources")?.str("script") ?: "/opt/relay/relay_alert.py"
                Text(
                    "启用方式：$script，由 relay-alert.timer 每 5 分钟执行一次。",
                    color = Palette.muted,
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                )
            }
        }

        if (d != null && d.bool("available") != false) {
            val s = d.obj("summary")
            val bk = d.obj("backup")
            val smtp = d.obj("smtp")
            val sources = d.obj("sources")
            val failing = s?.int("failing") ?: 0

            /* ---- H1 汇总磁贴 ---- */
            GridLayout(columns = 4, spacing = 12.dp) {
                StatTile(label = "检查项", value = "${s?.int("total") ?: 0}", sub = "relay_alert.py")
                StatTile(
                    label = "通过",
                    value = "${s?.int("ok") ?: 0}",
                    sub = "健康",
                    valueColor = Color(0xFF10B981),
                )
                StatTile(
                    label = "失败",
                    value = "$failing",
                    sub = if (failing > 0) "需处理" else "无",
                    valueColor = if (failing > 0) Color(0xFFEF4444) else null,
                )
                StatTile(
                    label = "当前状态",
                    value = if (failing > 0) "$failing 项失败" else "全部正常",
                    sub = "状态变化才发信",
                    valueColor = if (failing > 0) Color(0xFFEF4444) else Color(0xFF10B981),
                )
            }

            /* ---- H2 失败的检查 ---- */
            if (failing > 0) {
                PanelCard(title = "失败的检查", cardKey = "ha-failing", collapsible = false) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        d.arr("failingNames").forEach { n ->
                            val name = (n as? JsonPrimitive)?.content ?: ""
                            if (name.isNotEmpty()) LevelBadge(text = name, level = Level.Bad)
                        }
                    }
                }
            }

            /* ---- H3 检查项明细 ---- */
            PanelCard(title = "检查项明细 (${s?.int("total") ?: 0})", cardKey = "ha-checks") {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "检查项", weight = 2.4f, mono = true) {
                            Text(it.str("name"), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = "状态", weight = 0.8f, align = TextAlign.Center) {
                            if (it.bool("ok")) LevelBadge("OK", Level.Ok) else LevelBadge("FAIL", Level.Bad)
                        },
                        TableCol<JsonObject>(title = "当前状态起始", weight = 1.6f, mono = true) {
                            val since = it.str("since")
                            Text(
                                if (since.isEmpty()) "—" else MonFmt.isoShort(since),
                                fontSize = 12.sp,
                                color = Palette.muted,
                            )
                        },
                        TableCol<JsonObject>(title = "最近告警", weight = 1.4f, mono = true) {
                            val ls = it.long("lastSent")
                            Text(
                                if (ls <= 0) "从未发信" else Fmt.dateTime(ls * 1000),
                                fontSize = 12.sp,
                                color = if (ls > 0) Palette.text else Palette.muted,
                            )
                        },
                    ),
                    rows = d.arr("checks").filterIsInstance<JsonObject>(),
                )
            }

            /* ---- H4 告警通道 + H5 最近一次状态备份 ---- */
            GridLayout(columns = 2, spacing = 12.dp) {
                PanelCard(
                    title = "告警通道（复用 relay 每日报告的 SMTP）",
                    cardKey = "ha-smtp",
                    right = {
                        PrimaryBtn(
                            text = "发送测试邮件",
                            onClick = {
                                scope.launch {
                                    Actions.run(
                                        path = "/relayops/alert/test",
                                        body = jsonObjectOf(),
                                        title = "测试邮件发送结果",
                                    )
                                }
                            },
                        )
                    },
                ) {
                    val to = smtp?.arr("to")
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?.filter { it.isNotBlank() } ?: emptyList()
                    val ssl = smtp?.bool("ssl") == true
                    val rows = listOf(
                        "收件人" to (to.joinToString(", ").ifEmpty { "—" }),
                        "服务器" to (smtp?.str("host") ?: "—") + ":" + (smtp?.int("port") ?: 0) + (if (ssl) " (SSL)" else ""),
                        "发件账号" to (smtp?.str("user")?.ifEmpty { "—" } ?: "—"),
                        "发件人名" to (smtp?.str("from")?.ifEmpty { "—" } ?: "—"),
                        "检查脚本" to (sources?.str("script") ?: "—"),
                        "状态文件" to (sources?.str("state") ?: "—"),
                    )
                    KeyValueTable(rows)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "仅在检查状态发生变化时发信，同一故障每 6 小时提醒一次。",
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }

                PanelCard(title = "最近一次状态备份", cardKey = "ha-backup") {
                    if (bk == null) {
                        Text("暂无备份状态记录", color = Palette.muted, fontSize = 12.5.sp)
                    } else {
                        val ok = bk.bool("ok")
                        KeyValueTable(
                            listOf(
                                "结果" to if (ok) "成功" else "失败",
                                "时间" to MonFmt.isoShort(bk.str("ts")),
                                "大小" to Fmt.bytes(bk.num("size")),
                                "文件内条目" to "${bk.long("files")}",
                                "远端推送" to (bk.str("remote").ifEmpty { "—" }),
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        if (ok) LevelBadge("成功", Level.Ok) else LevelBadge("失败", Level.Bad)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "保留 30 份，relay-backup.timer 每日 03:30 执行。",
                            color = Palette.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            /* ---- H6 容量趋势（近 7 天）---- */
            val trendRows = listOf(
                trendRow("磁盘占用", trend?.obj("disk")),
                trendRow("内存占用", trend?.obj("mem")),
                trendRow("CPU 占用", trend?.obj("cpu")),
            )
            PanelCard(title = "容量趋势（近 7 天）", cardKey = "ha-trend") {
                DataTable(
                    columns = listOf(
                        TableCol<TrendRow>(title = "指标", weight = 1.4f) { Text(it.k, fontSize = 12.5.sp) },
                        TableCol<TrendRow>(title = "当前", weight = 1f, align = TextAlign.End, mono = true) {
                            Text(it.now, fontSize = 12.5.sp)
                        },
                        TableCol<TrendRow>(title = "日均变化", weight = 1.3f, align = TextAlign.End, mono = true) {
                            Text(it.slope, fontSize = 12.5.sp)
                        },
                        TableCol<TrendRow>(title = "预计触顶", weight = 1.6f) { Text(it.eta, fontSize = 12.5.sp) },
                        TableCol<TrendRow>(title = "样本", weight = 0.9f, align = TextAlign.End, mono = true) {
                            Text(
                                if (it.days > 0) "${it.days} 天" else "—",
                                fontSize = 12.5.sp,
                                color = Palette.muted,
                            )
                        },
                    ),
                    rows = trendRows,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "来自持久化指标（30 秒采样，保留 30 天）的逐日线性外推；样本不足 2 天时只显示当前值。",
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }

            /* ---- H7 相关定时器 ---- */
            val timers = d.arr("timers").filterIsInstance<JsonObject>()
            PanelCard(title = "相关定时器", cardKey = "ha-timers") {
                if (timers.isEmpty()) {
                    Text("未找到相关 timer", color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = "定时器", weight = 1.6f, mono = true) { Text(it.str("unit"), fontSize = 12.sp) },
                            TableCol<JsonObject>(title = "执行单元", weight = 1.6f, mono = true) { Text(it.str("activates"), fontSize = 12.sp) },
                            TableCol<JsonObject>(title = "下次执行", weight = 1.5f, mono = true) { Text(it.str("next"), fontSize = 12.sp) },
                            TableCol<JsonObject>(title = "倒计时", weight = 1f, mono = true) {
                                Text(it.str("left"), fontSize = 12.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = "上次执行", weight = 1.5f, mono = true) { Text(it.str("last"), fontSize = 12.sp) },
                        ),
                        rows = timers,
                    )
                }
            }

            /* ---- H8 告警日志 ---- */
            val logLines = d.arr("log").mapNotNull { (it as? JsonPrimitive)?.content }
            PanelCard(
                title = "告警日志 (${sources?.str("log") ?: ""})",
                cardKey = "ha-log",
                right = { GhostBtn(text = "清屏", onClick = { logCleared = true }) },
            ) {
                val text = when {
                    logCleared -> "(已清屏，刷新可重新加载)"
                    logLines.isEmpty() -> "(空)"
                    else -> logLines.joinToString("\n")
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .background(Palette.bg2, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text,
                        color = Palette.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        /* ---- H9 告警阈值配置 ---- */
        PanelCard(title = "告警阈值（仪表盘右上角铃铛）", cardKey = "ha-threshold") {
            Toggle(checked = cfgEnabled, onCheckedChange = { cfgEnabled = it }, label = "启用告警")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = cfgCpu,
                    onValueChange = { cfgCpu = it.filter(Char::isDigit) },
                    label = "CPU ≥ %",
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = cfgMem,
                    onValueChange = { cfgMem = it.filter(Char::isDigit) },
                    label = "内存 ≥ %",
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = cfgDisk,
                    onValueChange = { cfgDisk = it.filter(Char::isDigit) },
                    label = "磁盘 ≥ %",
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = cfgCd,
                    onValueChange = { cfgCd = it.filter(Char::isDigit) },
                    label = "冷却(分钟)",
                    mono = true,
                    modifier = Modifier.weight(1.2f),
                )
            }
            Spacer(Modifier.height(10.dp))
            PrimaryBtn(
                text = "保存阈值",
                onClick = {
                    scope.launch {
                        Actions.run(
                            path = "/alerts/config",
                            body = jsonObjectOf(
                                "enabled" to cfgEnabled,
                                "cpu" to (cfgCpu.toIntOrNull() ?: 90),
                                "mem" to (cfgMem.toIntOrNull() ?: 90),
                                "disk" to (cfgDisk.toIntOrNull() ?: 95),
                                "cooldownMin" to (cfgCd.toIntOrNull() ?: 10),
                            ),
                            successToast = "告警阈值已保存",
                        )
                        AppState.loadAlerts()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "超过阈值时页面右上角铃铛会弹出告警；同一指标在冷却时间内不重复提醒。",
                color = Palette.muted,
                fontSize = 12.sp,
            )
        }

        /* ---- H10 告警历史 ---- */
        PanelCard(
            title = "告警历史",
            cardKey = "ha-history",
            right = { GhostBtn(text = "标记已读", onClick = { AppState.markAlertsRead() }) },
        ) {
            if (alerts.isEmpty()) {
                Text("暂无告警记录", color = Palette.muted, fontSize = 12.5.sp)
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = "时间", weight = 1.4f, mono = true) {
                            Text(Fmt.dateTime(it.long("ts")), fontSize = 12.sp, color = Palette.muted)
                        },
                        TableCol<JsonObject>(title = "级别", weight = 0.7f, align = TextAlign.Center) {
                            if (it.str("level") == "bad") LevelBadge("BAD", Level.Bad)
                            else LevelBadge("WARN", Level.Warn)
                        },
                        TableCol<JsonObject>(title = "指标", weight = 0.9f, mono = true) {
                            Text(it.str("key"), fontSize = 12.sp, color = Palette.muted)
                        },
                        TableCol<JsonObject>(title = "内容", weight = 4f) {
                            Text(it.str("message"), fontSize = 12.5.sp)
                        },
                    ),
                    rows = alerts,
                    maxRows = 100,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "阈值告警由 server/hub.js evaluateAlerts() 每 15 秒评估一次，最多保留 200 条。",
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 复刻面板 `ui.table([{k:'label',...},{k:'val',...}])` 的两列表。 */
@Composable
private fun KeyValueTable(rows: List<Pair<String, String>>) {
    DataTable(
        columns = listOf(
            TableCol<Pair<String, String>>(title = "项", weight = 1f) {
                Text(it.first, fontSize = 12.5.sp, color = Palette.muted)
            },
            TableCol<Pair<String, String>>(title = "值", weight = 2.4f, mono = true) {
                Text(it.second, fontSize = 12.5.sp, maxLines = 2)
            },
        ),
        rows = rows,
    )
}
