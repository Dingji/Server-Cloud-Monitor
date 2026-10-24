package com.servermaintance.panel.pages.ops
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.SecCheck
import com.servermaintance.panel.core.SecState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LineChart
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PctRing
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.Sparkline
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import kotlinx.coroutines.launch

/**
 * 安全基线巡检 —— 对标宝塔「安全体检」/1Panel 节点检测：
 * 一组只读检查给出评分与等级，风险项带证据与修复建议；修复走服务器白名单动作。
 */
@Composable
fun OpsSecPage() {
    val report by SecState.report.collectAsState()
    val catalog by SecState.catalog.collectAsState()
    val fixes by SecState.fixes.collectAsState()
    val trend by SecState.trend.collectAsState()
    val fixLog by SecState.fixLog.collectAsState()
    val scanning by SecState.scanning.collectAsState()
    val unsupported by SecState.unsupported.collectAsState()
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(L["全部_778fc8f994"]) }

    LaunchedEffect(Unit) {
        if (report == null) SecState.load() else SecState.loadTrend()
        if (catalog.isEmpty()) SecState.loadCatalog()
    }
    AutoRefresh(key = "opssec", intervalMs = { 5 * 60_000 }) { SecState.load() }

    val r = report

    PageScaffold(
        title = L["安全基线巡检_cb83870cad"],
        desc = L["ssh_暴露面_防火墙_账号与权限_更新_日志与_ea3db6747a"],
        actions = {
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { SecState.load(); SecState.loadTrend() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = if (scanning) L["扫描中_7c81ab45e3"] else L["立即巡检_53b8079f5e"], onClick = {
                scope.launch { SecState.scan() }
            }, enabled = !scanning, icon = "scan")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        if (r == null) {
            LoadingState(if (scanning) L["正在巡检_09dfe0b521"] else L["读取最近一次巡检_7cabc948d4"])
            return@PageScaffold
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PctRing(
                pct = r.score.toDouble(), size = 96.dp,
                label = "${r.score}",
                sub = trf("等级_0_97edc6708f", r.grade),
            )
            Spacer(Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = L.fmt(
                        "检查_r_checks_size_项_2e6bee1606",
                        r.checks.size,
                        r.counts["warn"] ?: 0,
                        r.counts["info"] ?: 0,
                    ),
                    color = Palette.text, fontSize = 13.sp,
                )
                Text(
                    text = L.fmt(
                        "评分按风险权重扣减_严重项扣满权重_建议项扣_b2552dd8e4",
                        if (r.at > 0) Fmt.dateTime(r.at) else "—",
                    ),
                    color = Palette.muted, fontSize = 11.5.sp,
                )
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(L.fmt("合规_r_counts_93f6bd5eb9", r.counts["ok"] ?: 0), tone = Level.Ok)
                    Chip(
                        L.fmt(
                            "可修复_r_checks_count_e4b6314c85",
                            r.checks.count { !it.fix.isNullOrBlank() || it.fix.isNotBlank() },
                        ),
                        tone = Level.Info,
                    )
                }
            }
        }

        GridLayout(columns = 3, spacing = 12.dp) {
            r.byCat.forEach { c ->
                StatTile(
                    label = c.cat, value = trf("0_项_2f9578aead", c.total),
                    sub = if (c.bad > 0) trf("风险_0_建议_1_d3770c73e2", c.bad, c.warn) else if (c.warn > 0) L["建议_c_warn_427c849e3e"] else L["全部合规_59b806d265"],
                    icon = "shieldAlert",
                    valueColor = when {
                        c.bad > 0 -> Palette.bad
                        c.warn > 0 -> Palette.warn
                        else -> Palette.ok
                    },
                    pct = c.score,
                )
            }
        }

        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(L["全部_778fc8f994"], L["有问题_ec02351ea3"]) + r.byCat.map { it.cat }).forEach { f ->
                if (f == filter) Chip(f, tone = Level.Info)
                else GhostBtn(text = f, onClick = { filter = f })
            }
        }

        val shown = r.checks.filter {
            when (filter) {
                L["全部_778fc8f994"] -> true
                L["有问题_ec02351ea3"] -> it.level == "warn" || it.level == "bad"
                else -> it.cat == filter
            }
        }

        PanelCard(title = L.fmt("检查项_shown_size_70be8a7c92", shown.size), cardKey = "sec-checks", collapsible = false) {
            DataTable(
                columns = listOf(
                    TableCol<SecCheck>(L["检查项_871b61f946"], weight = 1.6f) { c ->
                        Column {
                            Text(text = c.label, color = Palette.text, fontSize = 12.5.sp)
                            Text(text = c.cat, color = Palette.muted, fontSize = 11.sp)
                        }
                    },
                    TableCol<SecCheck>(L["结论_32b3f16d10"], weight = 0.8f) { c ->
                        Chip(
                            text = SecState.levelText(c.level),
                            tone = when (c.level) {
                                "bad" -> Level.Bad
                                "warn" -> Level.Warn
                                "ok" -> Level.Ok
                                else -> Level.Muted
                            },
                        )
                    },
                    TableCol<SecCheck>(L["证据与建议_5c970a5da1"], weight = 3.0f) { c ->
                        Column {
                            TableText(c.detail.ifBlank { "—" }, color = if (c.level == "bad") Palette.bad else if (c.level == "warn") Palette.warn else Palette.muted, maxLines = 3)
                            if (c.evidence.isNotBlank()) TableText(trf("命令输出_0_1eb07b8f1f", c.evidence.take(160)), color = Palette.muted, maxLines = 2)
                        }
                    },
                    TableCol<SecCheck>(L["操作_f3ea6d345e"], weight = 1.7f) { c ->
                        val fixId = c.fix.takeIf { it.isNotBlank() }
                        if (fixId != null) {
                            GhostBtn(text = L["一键处理_37d1c79d89"], onClick = {
                                scope.launch {
                                    val meta = fixes.firstOrNull { f -> f.id == fixId }
                                    val msg = buildString {
                                        append(SecState.fixLabel(fixId))
                                        append("\n\n")
                                        append(meta?.risk ?: L["该动作由服务器白名单实现_执行前会自动做安全校验_455c5e768f"])
                                        if (c.level == "bad") {
                                            append(L["建议先用_web_终端确认现有登录方式可用_12826a1b60"])
                                        }
                                    }
                                    if (!Modal.confirm(L["执行修复_18ad6adf19"], msg, okText = L["确认执行_ae1109f3ef"], danger = true)) return@launch
                                    val (err, output) = SecState.applyFix(fixId)
                                    Modal.output(if (err == null) L["执行结果_1b213f405b"] else L["未成功_dc36695631"], output ?: err ?: "")
                                }
                            }, icon = "shieldAlert")
                        } else {
                            Text(text = if (c.skipped) L["环境不具备_17f075425e"] else "—", color = Palette.muted, fontSize = 11.5.sp)
                        }
                    },
                ),
                rows = shown,
                rowKey = { it.id },
                emptyText = L["没有匹配的检查项_1ec567bb89"],
            )
        }

        if (trend.isNotEmpty()) {
            PanelCard(title = L.fmt("评分趋势_最近_trend_size_次_f542a3ab0d", trend.size), cardKey = "sec-trend") {
                Sparkline(points = trend.map { it.score.toFloat() }, color = Palette.ok)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(text = trf("最低_0_最高_1_当前_2_70336dd1a0", trend.minOf { it.score }, trend.maxOf { it.score }, trend.last().score),
                        color = Palette.muted, fontSize = 11.5.sp)
                }
            }
        }

        PanelCard(title = L["修复执行记录_2669e0f726"], cardKey = "sec-fixlog") {
            if (fixLog.isEmpty()) EmptyState(L["尚未执行过任何修复动作_fe7c3d4771"], icon = "shieldAlert")
            else DataTable(
                columns = listOf(
                    TableCol<com.servermaintance.panel.core.SecFixRec>(L["时间_89b4aa6364"], weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.at), mono = true) },
                    TableCol<com.servermaintance.panel.core.SecFixRec>(L["动作_d9d9827827"], weight = 2.0f) { TableText(it.label.ifBlank { it.fix }) },
                    TableCol<com.servermaintance.panel.core.SecFixRec>(L["结果_0a2c91cec6"], weight = 0.8f) {
                        if (it.ok) Chip(L["成功_51991a5d11"], tone = Level.Ok) else Chip(L["未成功_dc36695631"], tone = Level.Bad)
                    },
                    TableCol<com.servermaintance.panel.core.SecFixRec>(L["输出_ded698ae1e"], weight = 3.0f) { TableText(it.output.replace("\n", " ").take(220), color = Palette.muted, maxLines = 2) },
                ),
                rows = fixLog,
                rowKey = { "${it.at}-${it.fix}" },
                maxRows = 8,
            )
        }
    }
}
