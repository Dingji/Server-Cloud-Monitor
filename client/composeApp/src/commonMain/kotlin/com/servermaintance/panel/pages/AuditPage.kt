package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SectionTitle
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* =====================================================================
 * 审计日志 —— 复刻 upstream/public/js/pages/audit.js (117 行)
 *
 * 卡片清单（自检，供测试对账）：
 *  A0 不可用态：available=false → 展示 reason + 启用方法（apt install auditd / augenrules --load）
 *  A1 磁贴 ×4：命中事件(total) / 审计规则(rules.length) / 日志体积(storage 首段 + logFile)
 *              / 审计服务(运行中, sub=auditd)
 *  A2 筛选：时间范围(1h/6h/24h/all) × 审计规则(全部 + keys(计数)) × 返回条数(100/300/500/1000)
 *          +「查询」+ 规则含义说明；任一筛选变化即查询（对应 audit.js onchange=query）
 *  A3 事件表：时间(ts×1000)/规则(badge warn)/进程/命令(截 160)/涉及文件/结果(badge ok|bad)
 *          + 计数「N 条展示 / 命中 M 条」+「刷新」
 *  A4 已加载的审计规则：rules 逐行等宽 + 轮转说明
 * ===================================================================== */

private val AUDIT_RANGES = listOf(
    "1h" to "最近 1 小时",
    "6h" to "最近 6 小时",
    "24h" to "最近 24 小时",
    "all" to "当前日志全部",
)

private val AUDIT_LIMITS = listOf("100", "300", "500", "1000")

/** commonMain 没有 URLEncoder，手写 percent-encoding（UTF-8 + rfc3986 子集）。 */
internal fun urlEncode(s: String): String {
    if (s.isEmpty()) return ""
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            sb.append(ch)
        } else {
            sb.append('%')
            val hex = c.toString(16).uppercase().padStart(2, '0')
            sb.append(hex)
        }
    }
    return sb.toString()
}

/** 面板 `.pre` 的等价物：等宽 + 深色底。 */
@Composable
internal fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 200,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.bg2)
            .padding(10.dp),
    ) {
        Text(
            text = text.ifBlank { "(无)" },
            color = Palette.text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxLines,
        )
    }
}

private fun JsonElement?.prim(): String = (this as? JsonPrimitive)?.content ?: ""

private fun JsonElement?.boolOr(default: Boolean = false): Boolean {
    val p = this as? JsonPrimitive ?: return default
    return p.content.toBooleanStrictOrNull() ?: default
}

private fun auditTs(sec: Double): String =
    if (sec <= 0) "—" else Fmt.dateTime((sec * 1000).toLong())

@Composable
fun AuditPage() {
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var errText by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf("24h") }
    var keySel by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("300") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun query() {
        loading = true
        errText = null
        try {
            val d = AppState.api.get(
                "/relayops/audit?range=${urlEncode(range)}&key=${urlEncode(keySel)}&limit=${urlEncode(limit)}"
            )
            data = d as? JsonObject
        } catch (e: ApiException) {
            errText = e.message + (e.output?.let { "\n$it" } ?: "")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { query() }

    val d = data
    val events: List<JsonElement> = d?.arr("events") ?: emptyList()
    val available: Boolean = d?.key("available").boolOr(default = true)

    PageScaffold(
        title = "审计日志",
        desc = "auditd 记录的敏感文件变更：谁、用什么命令、改了哪个文件",
    ) {
        when {
            loading && d == null -> PanelCard(title = "审计日志") {
                LoadingState("正在读取审计日志…")
            }

            errText != null -> PanelCard(title = "查询失败") {
                ErrorState(errText!!, onRetry = { scope.launch { query() } })
            }

            d == null -> PanelCard(title = "审计日志") {
                ErrorState("暂无数据", onRetry = { scope.launch { query() } })
            }

            !available -> PanelCard(title = "审计不可用", right = { PanelIcon("shieldAlert", size = 14.dp) }) {
                ErrorState(d.str("reason").ifBlank { "auditd 未启用" })
                SectionTitle("启用方法")
                CodeBlock(
                    "apt-get install auditd\n" +
                        "# 载入规则 /etc/audit/rules.d/90-relay.rules\n" +
                        "augenrules --load"
                )
            }

            else -> AuditBody(d, loading, range, keySel, limit, events,
                onQuery = { r, k, l ->
                    range = r; keySel = k; limit = l
                    scope.launch { query() }
                },
                onRefresh = { scope.launch { query() } }
            )
        }
    }
}

@Composable
private fun AuditBody(
    d: JsonObject,
    loading: Boolean,
    range: String,
    keySel: String,
    limit: String,
    events: List<JsonElement>,
    onQuery: (String, String, String) -> Unit,
    onRefresh: () -> Unit,
) {
    var rangeState by remember(range) { mutableStateOf(range) }
    var keyState by remember(keySel) { mutableStateOf(keySel) }
    var limitState by remember(limit) { mutableStateOf(limit) }

    /* ---- A1 统计磁贴 ---- */
    GridLayout(columns = 4, spacing = 12.dp) {
        StatTile(label = "命中事件", value = d.int("total").toString(), sub = "当前筛选")
        StatTile(label = "审计规则", value = d.arr("rules").size.toString(), sub = "auditctl -l")
        StatTile(
            label = "日志体积",
            value = d.str("storage").split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { "—" },
            sub = d.str("logFile"),
        )
        StatTile(label = "审计服务", value = "运行中", sub = "auditd")
    }

    /* ---- A2 筛选 ---- */
    val keys = d.arr("keys").map { it.prim() }
    val counts = d.obj("counts") ?: JsonObject(emptyMap())
    val countOf: (String) -> Int = { k -> (counts[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
    val keyOptions = buildList {
        add("全部（仅显示有规则标记的事件）")
        keys.forEach { add("$it (${countOf(it)})") }
    }
    val keyValues = buildList { add(""); addAll(keys) }
    val displayIndex = keyValues.indexOf(keyState).let { if (it < 0) 0 else it }

    PanelCard(title = "筛选", right = { PanelIcon("filter", size = 14.dp) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectField(
                value = AUDIT_RANGES.firstOrNull { it.first == rangeState }?.second ?: "最近 24 小时",
                options = AUDIT_RANGES.map { it.second },
                onValueChange = { label ->
                    val v = AUDIT_RANGES.firstOrNull { it.second == label }?.first ?: "24h"
                    onQuery(v, keyState, limitState)
                },
                label = "时间范围",
                modifier = Modifier.weight(1f),
            )
            SelectField(
                value = keyOptions.getOrElse(displayIndex) { keyOptions.first() },
                options = keyOptions,
                onValueChange = { label ->
                    val idx = keyOptions.indexOf(label)
                    onQuery(rangeState, keyValues.getOrElse(idx) { "" }, limitState)
                },
                label = "审计规则",
                modifier = Modifier.weight(1.4f),
            )
            SelectField(
                value = limitState,
                options = AUDIT_LIMITS,
                onValueChange = { onQuery(rangeState, keyState, it) },
                label = "返回条数",
                modifier = Modifier.weight(0.7f),
            )
            PrimaryBtn(text = "查询", onClick = { onQuery(rangeState, keyState, limitState) })
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "规则含义：relay_conf 中继配置 · relay_db 统计数据库 · relay_keys API 密钥 · " +
                "nginx_site/nginx_conf Nginx 配置 · sshd_config SSH 配置 · " +
                "ssh_authorized_keys 授权密钥 · relay_scripts 中继脚本 · ufw_conf 防火墙 · cron_conf 定时任务。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    /* ---- A3 事件表 ---- */
    PanelCard(
        title = "事件",
        right = {
            Text(
                text = "(${events.size} 条展示 / 命中 ${d.int("total")} 条)",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            GhostBtn(text = "刷新", onClick = onRefresh)
        },
    ) {
        when {
            loading -> LoadingState("查询中…")
            events.isEmpty() -> EmptyState("当前条件下没有事件")
            else -> DataTable<JsonElement>(
                columns = listOf(
                    TableCol(title = "时间", weight = 1.4f, mono = true) {
                        Text(
                            text = auditTs(it.num("ts")),
                            color = Palette.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    },
                    TableCol(title = "规则", weight = 0.9f, mono = true) {
                        val k = it.str("key")
                        if (k.isBlank()) Text("—", color = Palette.muted)
                        else LevelBadge(text = k, level = Level.Warn)
                    },
                    TableCol(title = "进程", weight = 0.8f, mono = true) {
                        val c = it.str("comm")
                        Text(
                            text = c.ifBlank { "—" },
                            color = if (c.isBlank()) Palette.muted else Palette.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    },
                    TableCol(title = "命令", weight = 3f, mono = true) {
                        val cmd = it.str("cmd")
                        val shown = cmd.ifBlank { "—" }.let { s -> if (s.length > 160) s.take(160) + "…" else s }
                        Text(
                            text = shown,
                            color = if (cmd.isBlank()) Palette.muted else Palette.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    TableCol(title = "涉及文件", weight = 2.4f, mono = true) {
                        val files = it.arr("files").map { f -> f.prim() }
                        Text(
                            text = files.joinToString(", ").ifBlank { "—" },
                            color = if (files.isEmpty()) Palette.muted else Palette.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    TableCol(title = "结果", weight = 0.7f) {
                        when (it.str("success")) {
                            "yes" -> LevelBadge(text = "成功", level = Level.Ok)
                            "no" -> LevelBadge(text = "失败", level = Level.Bad)
                            else -> Text("—", color = Palette.muted)
                        }
                    },
                ),
                rows = events,
                rowKey = { "${it.num("ts").toLong()}-${it.str("key")}-${it.str("cmd")}" },
                emptyText = "当前条件下没有事件",
                modifier = Modifier.heightIn(max = 560.dp),
            )
        }
    }

    /* ---- A4 审计规则 ---- */
    PanelCard(title = "已加载的审计规则", right = { PanelIcon("key", size = 14.dp) }) {
        CodeBlock(text = d.arr("rules").joinToString("\n") { it.prim() }, maxLines = 12)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "日志按 /var/log/audit/audit.log 轮转，更早的记录在同目录的 audit.log.* 压缩包中。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
