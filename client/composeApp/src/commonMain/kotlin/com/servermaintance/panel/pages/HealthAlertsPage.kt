package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L

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
    if (t == null || pts.isEmpty()) return TrendRow(label, "—", "—", L["暂无数据_b246458f20"], 0)
    val n = pts.size
    val now = numStr(t.num("current")) + "%"
    if (!t.containsKey("slopePerDay") || t["slopePerDay"] == null) {
        return TrendRow(label, now, L["需_2_天_24e3e9f50c"], "—", n)
    }
    val s = t.num("slopePerDay")
    val eta = when {
        s > 0.01 -> {
            val d = if (t.containsKey("daysToFull") && t["daysToFull"] != null) t.num("daysToFull") else null
            if (d != null && d > 0) trf("0_天后达_100_4e4bc3f192", kotlin.math.round(d).toLong()) else L["持续增长_ab21b96aa8"]
        }
        s < -0.01 -> L["下降中_4eeadab450"]
        else -> L["稳定_4024bd5e23"]
    }
    return TrendRow(
        k = label,
        now = now,
        slope = (if (s >= 0) L["s_a979ef10cc"] else "") + numStr(s) + L["天_26abf68dec"],
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
        title = L["健康告警_a3932fd7ea"],
        desc = L["relay_alert_py_检查_smtp_告_012aaa3373"],
        actions = { GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { load() } }) },
    ) {
        val d = health

        if (loading && d == null) Text(L["common.loading"], color = Palette.muted, fontSize = 13.sp)
        if (error != null && d == null) Text(error ?: L["加载失败_f6b7a41e56"], color = Palette.bad, fontSize = 13.sp)

        /* ---- H0：尚未运行健康检查 ---- */
        if (d != null && d.bool("available") == false) {
            PanelCard(title = L["尚未运行健康检查_ba6dfd78c0"], cardKey = "ha-none", collapsible = false) {
                if (d.str("reason").isNotEmpty()) {
                    Text(d.str("reason"), color = Palette.muted, fontSize = 13.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(4.dp))
                }
                val script = d.obj("sources")?.str("script") ?: "/opt/relay/relay_alert.py"
                Text(
                    L["启用方式_script_由_relay_ale_ef278b0211"],
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
                StatTile(label = L["检查项_871b61f946"], value = "${s?.int("total") ?: 0}", sub = "relay_alert.py")
                StatTile(
                    label = L["通过_dcc4233255"],
                    value = "${s?.int("ok") ?: 0}",
                    sub = L["健康_28ff5ffe95"],
                    valueColor = Color(0xFF10B981),
                )
                StatTile(
                    label = L["失败_3e3c8068bb"],
                    value = trfn("failing_67343baf45", "failing" to failing),
                    sub = if (failing > 0) L["需处理_ed5909bac1"] else L["无_72077749f7"],
                    valueColor = if (failing > 0) Color(0xFFEF4444) else null,
                )
                StatTile(
                    label = L["当前状态_045859e792"],
                    value = if (failing > 0) trfn("failing_项失败_c0f4c9b479", "failing" to failing) else L["全部正常_d02882ad7e"],
                    sub = L["状态变化才发信_3e6b8f558f"],
                    valueColor = if (failing > 0) Color(0xFFEF4444) else Color(0xFF10B981),
                )
            }

            /* ---- H2 失败的检查 ---- */
            if (failing > 0) {
                PanelCard(title = L["失败的检查_587001c2d4"], cardKey = "ha-failing", collapsible = false) {
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
            PanelCard(title = trf("检查项明细_0_0bd2e11781", s?.int("total") ?: 0), cardKey = "ha-checks") {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["检查项_871b61f946"], weight = 2.4f, mono = true) {
                            Text(it.str("name"), fontSize = 12.5.sp)
                        },
                        TableCol<JsonObject>(title = L["状态_62e951a692"], weight = 0.8f, align = TextAlign.Center) {
                            if (it.bool("ok")) LevelBadge("OK", Level.Ok) else LevelBadge("FAIL", Level.Bad)
                        },
                        TableCol<JsonObject>(title = L["当前状态起始_ec8a451989"], weight = 1.6f, mono = true) {
                            val since = it.str("since")
                            Text(
                                if (since.isEmpty()) "—" else MonFmt.isoShort(since),
                                fontSize = 12.sp,
                                color = Palette.muted,
                            )
                        },
                        TableCol<JsonObject>(title = L["最近告警_fbce4cc6ee"], weight = 1.4f, mono = true) {
                            val ls = it.long("lastSent")
                            Text(
                                if (ls <= 0) L["从未发信_d605d91365"] else Fmt.dateTime(ls * 1000),
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
                    title = L["告警通道_复用_relay_每日报告的_smtp_c02c260a95"],
                    cardKey = "ha-smtp",
                    right = {
                        PrimaryBtn(
                            text = L["发送测试邮件_1eace6fb59"],
                            onClick = {
                                scope.launch {
                                    Actions.run(
                                        path = "/relayops/alert/test",
                                        body = jsonObjectOf(),
                                        title = L["测试邮件发送结果_9b487677ed"],
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
                        L["收件人_529414cfe5"] to (to.joinToString(L["s_d3bc9a378d"]).ifEmpty { "—" }),
                        L["nav.section.server"] to (smtp?.str("host") ?: "—") + ":" + (smtp?.int("port") ?: 0) + (if (ssl) L["ssl_ce356e34cb"] else ""),
                        L["发件账号_c4c54b3c06"] to (smtp?.str("user")?.ifEmpty { "—" } ?: "—"),
                        L["发件人名_c1dac198b4"] to (smtp?.str("from")?.ifEmpty { "—" } ?: "—"),
                        L["检查脚本_459d03768b"] to (sources?.str("script") ?: "—"),
                        L["状态文件_ba5e6e9f66"] to (sources?.str("state") ?: "—"),
                    )
                    KeyValueTable(rows)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L["仅在检查状态发生变化时发信_同一故障每_6_小时_81be500ffa"],
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }

                PanelCard(title = L["最近一次状态备份_e5c455f5bf"], cardKey = "ha-backup") {
                    if (bk == null) {
                        Text(L["暂无备份状态记录_f254ce0b6d"], color = Palette.muted, fontSize = 12.5.sp)
                    } else {
                        val ok = bk.bool("ok")
                        KeyValueTable(
                            listOf(
                                L["结果_0a2c91cec6"] to if (ok) L["成功_51991a5d11"] else L["失败_3e3c8068bb"],
                                L["时间_89b4aa6364"] to MonFmt.isoShort(bk.str("ts")),
                                L["大小_fd20702c73"] to Fmt.bytes(bk.num("size")),
                                L["文件内条目_b4ce600040"] to "${bk.long("files")}",
                                L["远端推送_69e4125b5a"] to (bk.str("remote").ifEmpty { "—" }),
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        if (ok) LevelBadge(L["成功_51991a5d11"], Level.Ok) else LevelBadge(L["失败_3e3c8068bb"], Level.Bad)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            L["保留_30_份_relay_backup_tim_b625d85371"],
                            color = Palette.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            /* ---- H6 容量趋势（近 7 天）---- */
            val trendRows = listOf(
                trendRow(L["磁盘占用_73dccc2205"], trend?.obj("disk")),
                trendRow(L["内存占用_871ca4fff4"], trend?.obj("mem")),
                trendRow(L["cpu_占用_89f8a08e80"], trend?.obj("cpu")),
            )
            PanelCard(title = L["容量趋势_近_7_天_d77b3aebd5"], cardKey = "ha-trend") {
                DataTable(
                    columns = listOf(
                        TableCol<TrendRow>(title = L["指标_1dfc810a7b"], weight = 1.4f) { Text(it.k, fontSize = 12.5.sp) },
                        TableCol<TrendRow>(title = L["当前_25e74dceac"], weight = 1f, align = TextAlign.End, mono = true) {
                            Text(it.now, fontSize = 12.5.sp)
                        },
                        TableCol<TrendRow>(title = L["日均变化_9dc6a161af"], weight = 1.3f, align = TextAlign.End, mono = true) {
                            Text(it.slope, fontSize = 12.5.sp)
                        },
                        TableCol<TrendRow>(title = L["预计触顶_b61c003290"], weight = 1.6f) { Text(it.eta, fontSize = 12.5.sp) },
                        TableCol<TrendRow>(title = L["样本_610da878ac"], weight = 0.9f, align = TextAlign.End, mono = true) {
                            Text(
                                if (it.days > 0) trf("0_天_5834f54606", it.days) else "—",
                                fontSize = 12.5.sp,
                                color = Palette.muted,
                            )
                        },
                    ),
                    rows = trendRows,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    L["来自持久化指标_30_秒采样_保留_30_天_的_ff4f75947f"],
                    color = Palette.muted,
                    fontSize = 12.sp,
                )
            }

            /* ---- H7 相关定时器 ---- */
            val timers = d.arr("timers").filterIsInstance<JsonObject>()
            PanelCard(title = L["相关定时器_92ab9c4135"], cardKey = "ha-timers") {
                if (timers.isEmpty()) {
                    Text(L["未找到相关_timer_e957cd4c26"], color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = L["定时器_70cbf60aeb"], weight = 1.6f, mono = true) { Text(it.str("unit"), fontSize = 12.sp) },
                            TableCol<JsonObject>(title = L["执行单元_2466ded40e"], weight = 1.6f, mono = true) { Text(it.str("activates"), fontSize = 12.sp) },
                            TableCol<JsonObject>(title = L["下次执行_373074f4ee"], weight = 1.5f, mono = true) { Text(it.str("next"), fontSize = 12.sp) },
                            TableCol<JsonObject>(title = L["倒计时_80176c18f3"], weight = 1f, mono = true) {
                                Text(it.str("left"), fontSize = 12.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = L["上次执行_434c6bf57e"], weight = 1.5f, mono = true) { Text(it.str("last"), fontSize = 12.sp) },
                        ),
                        rows = timers,
                    )
                }
            }

            /* ---- H8 告警日志 ---- */
            val logLines = d.arr("log").mapNotNull { (it as? JsonPrimitive)?.content }
            PanelCard(
                title = trf("告警日志_0_a892400684", sources?.str("logs") ?: ""),
                cardKey = "ha-log",
                right = { GhostBtn(text = L["清屏_e8db7ce0cc"], onClick = { logCleared = true }) },
            ) {
                val text = when {
                    logCleared -> L["已清屏_刷新可重新加载_01eb7fac6f"]
                    logLines.isEmpty() -> L["空_9e1138c983"]
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
        PanelCard(title = L["告警阈值_仪表盘右上角铃铛_c7224212dd"], cardKey = "ha-threshold") {
            Toggle(checked = cfgEnabled, onCheckedChange = { cfgEnabled = it }, label = L["启用告警_d6b9351330"])
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = cfgCpu,
                    onValueChange = { cfgCpu = it.filter(Char::isDigit) },
                    label = L["cpu_45ab473ccb"],
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = cfgMem,
                    onValueChange = { cfgMem = it.filter(Char::isDigit) },
                    label = L["内存_ba57ca4da5"],
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = cfgDisk,
                    onValueChange = { cfgDisk = it.filter(Char::isDigit) },
                    label = L["磁盘_874593fffd"],
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    value = cfgCd,
                    onValueChange = { cfgCd = it.filter(Char::isDigit) },
                    label = L["冷却_分钟_c84b0ae919"],
                    mono = true,
                    modifier = Modifier.weight(1.2f),
                )
            }
            Spacer(Modifier.height(10.dp))
            PrimaryBtn(
                text = L["保存阈值_1a6c3945df"],
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
                            successToast = L["告警阈值已保存_f6ed6094c3"],
                        )
                        AppState.loadAlerts()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                L["超过阈值时页面右上角铃铛会弹出告警_同一指标在冷_3d09697f8d"],
                color = Palette.muted,
                fontSize = 12.sp,
            )
        }

        /* ---- H10 告警历史 ---- */
        PanelCard(
            title = L["告警历史_c507274d75"],
            cardKey = "ha-history",
            right = { GhostBtn(text = L["标记已读_504ecf732d"], onClick = { AppState.markAlertsRead() }) },
        ) {
            if (alerts.isEmpty()) {
                Text(L["暂无告警记录_805d7ef9cc"], color = Palette.muted, fontSize = 12.5.sp)
            } else {
                DataTable(
                    columns = listOf(
                        TableCol<JsonObject>(title = L["时间_89b4aa6364"], weight = 1.4f, mono = true) {
                            Text(Fmt.dateTime(it.long("ts")), fontSize = 12.sp, color = Palette.muted)
                        },
                        TableCol<JsonObject>(title = L["级别_2548499200"], weight = 0.7f, align = TextAlign.Center) {
                            if (it.str("level") == "bad") LevelBadge("BAD", Level.Bad)
                            else LevelBadge("WARN", Level.Warn)
                        },
                        TableCol<JsonObject>(title = L["指标_1dfc810a7b"], weight = 0.9f, mono = true) {
                            Text(it.str("key"), fontSize = 12.sp, color = Palette.muted)
                        },
                        TableCol<JsonObject>(title = L["内容_163aec9194"], weight = 4f) {
                            Text(it.str("message"), fontSize = 12.5.sp)
                        },
                    ),
                    rows = alerts,
                    maxRows = 100,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    L["阈值告警由_server_hub_js_eval_87eb59fa4a"],
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
            TableCol<Pair<String, String>>(title = L["项_64728a7727"], weight = 1f) {
                Text(it.first, fontSize = 12.5.sp, color = Palette.muted)
            },
            TableCol<Pair<String, String>>(title = L["值_321b955840"], weight = 2.4f, mono = true) {
                Text(it.second, fontSize = 12.5.sp, maxLines = 2)
            },
        ),
        rows = rows,
    )
}
