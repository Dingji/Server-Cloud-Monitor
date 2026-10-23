package com.servermaintance.panel.pages.ops

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
    var filter by remember { mutableStateOf("全部") }

    LaunchedEffect(Unit) {
        if (report == null) SecState.load() else SecState.loadTrend()
        if (catalog.isEmpty()) SecState.loadCatalog()
    }
    AutoRefresh(key = "opssec", intervalMs = { 5 * 60_000 }) { SecState.load() }

    val r = report

    PageScaffold(
        title = "安全基线巡检",
        desc = "SSH 暴露面、防火墙、账号与权限、更新、日志与时间同步等只读体检；修复动作经服务器安全校验后执行",
        actions = {
            GhostBtn(text = "刷新", onClick = { scope.launch { SecState.load(); SecState.loadTrend() } }, icon = "refresh")
            Spacer(Modifier.size(8.dp))
            PrimaryBtn(text = if (scanning) "扫描中…" else "立即巡检", onClick = {
                scope.launch { SecState.scan() }
            }, enabled = !scanning, icon = "scan")
        },
    ) {
        if (unsupported) {
            OpsUnsupported()
            return@PageScaffold
        }
        if (r == null) {
            LoadingState(if (scanning) "正在巡检…" else "读取最近一次巡检…")
            return@PageScaffold
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PctRing(
                pct = r.score.toDouble(), size = 96.dp,
                label = "${r.score}",
                sub = "等级 ${r.grade}",
            )
            Spacer(Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "检查 ${r.checks.size} 项 · 风险 ${r.counts["bad"] ?: 0} · 建议 ${r.counts["warn"] ?: 0} · 未覆盖 ${r.counts["info"] ?: 0}",
                    color = Palette.text, fontSize = 13.sp,
                )
                Text(
                    text = "最近巡检 ${if (r.at > 0) Fmt.dateTime(r.at) else "—"}；评分按风险权重扣减（严重项扣满权重，建议项扣一半）",
                    color = Palette.muted, fontSize = 11.5.sp,
                )
                Spacer(Modifier.size(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("合规 ${r.counts["ok"] ?: 0}", tone = Level.Ok)
                    Chip("可修复 ${r.checks.count { !it.fix.isNullOrBlank() || it.fix.isNotBlank() }}", tone = Level.Info)
                }
            }
        }

        GridLayout(columns = 3, spacing = 12.dp) {
            r.byCat.forEach { c ->
                StatTile(
                    label = c.cat, value = "${c.total} 项",
                    sub = if (c.bad > 0) "风险 $c.bad · 建议 $c.warn" else if (c.warn > 0) "建议 $c.warn" else "全部合规",
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
            (listOf("全部", "有问题") + r.byCat.map { it.cat }).forEach { f ->
                if (f == filter) Chip(f, tone = Level.Info)
                else GhostBtn(text = f, onClick = { filter = f })
            }
        }

        val shown = r.checks.filter {
            when (filter) {
                "全部" -> true
                "有问题" -> it.level == "warn" || it.level == "bad"
                else -> it.cat == filter
            }
        }

        PanelCard("检查项（${shown.size}）", cardKey = "sec-checks", collapsible = false) {
            DataTable(
                columns = listOf(
                    TableCol<SecCheck>("检查项", weight = 1.6f) { c ->
                        Column {
                            Text(text = c.label, color = Palette.text, fontSize = 12.5.sp)
                            Text(text = c.cat, color = Palette.muted, fontSize = 11.sp)
                        }
                    },
                    TableCol<SecCheck>("结论", weight = 0.8f) { c ->
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
                    TableCol<SecCheck>("证据与建议", weight = 3.0f) { c ->
                        Column {
                            TableText(c.detail.ifBlank { "—" }, color = if (c.level == "bad") Palette.bad else if (c.level == "warn") Palette.warn else Palette.muted, maxLines = 3)
                            if (c.evidence.isNotBlank()) TableText("命令输出：${c.evidence.take(160)}", color = Palette.muted, maxLines = 2)
                        }
                    },
                    TableCol<SecCheck>("操作", weight = 1.7f) { c ->
                        val fixId = c.fix.takeIf { it.isNotBlank() }
                        if (fixId != null) {
                            GhostBtn(text = "一键处理", onClick = {
                                scope.launch {
                                    val meta = fixes.firstOrNull { f -> f.id == fixId }
                                    val msg = buildString {
                                        append(SecState.fixLabel(fixId))
                                        append("\n\n")
                                        append(meta?.risk ?: "该动作由服务器白名单实现，执行前会自动做安全校验。")
                                        if (c.level == "bad") {
                                            append("\n\n建议先用 Web 终端确认现有登录方式可用。")
                                        }
                                    }
                                    if (!Modal.confirm("执行修复", msg, okText = "确认执行", danger = true)) return@launch
                                    val (err, output) = SecState.applyFix(fixId)
                                    Modal.output(if (err == null) "执行结果" else "未成功", output ?: err ?: "")
                                }
                            }, icon = "shieldAlert")
                        } else {
                            Text(text = if (c.skipped) "环境不具备" else "—", color = Palette.muted, fontSize = 11.5.sp)
                        }
                    },
                ),
                rows = shown,
                rowKey = { it.id },
                emptyText = "没有匹配的检查项",
            )
        }

        if (trend.isNotEmpty()) {
            PanelCard("评分趋势（最近 ${trend.size} 次）", cardKey = "sec-trend") {
                Sparkline(points = trend.map { it.score.toFloat() }, color = Palette.ok)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(text = "最低 ${trend.minOf { it.score }} · 最高 ${trend.maxOf { it.score }} · 当前 ${trend.last().score}",
                        color = Palette.muted, fontSize = 11.5.sp)
                }
            }
        }

        PanelCard("修复执行记录", cardKey = "sec-fixlog") {
            if (fixLog.isEmpty()) EmptyState("尚未执行过任何修复动作", icon = "shieldAlert")
            else DataTable(
                columns = listOf(
                    TableCol<com.servermaintance.panel.core.SecFixRec>("时间", weight = 1.4f, mono = true) { TableText(Fmt.dateTime(it.at), mono = true) },
                    TableCol<com.servermaintance.panel.core.SecFixRec>("动作", weight = 2.0f) { TableText(it.label.ifBlank { it.fix }) },
                    TableCol<com.servermaintance.panel.core.SecFixRec>("结果", weight = 0.8f) {
                        if (it.ok) Chip("成功", tone = Level.Ok) else Chip("未成功", tone = Level.Bad)
                    },
                    TableCol<com.servermaintance.panel.core.SecFixRec>("输出", weight = 3.0f) { TableText(it.output.replace("\n", " ").take(220), color = Palette.muted, maxLines = 2) },
                ),
                rows = fixLog,
                rowKey = { "${it.at}-${it.fix}" },
                maxRows = 8,
            )
        }
    }
}
