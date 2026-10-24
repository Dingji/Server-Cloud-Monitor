package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

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

private val AUDIT_RANGES  get() = listOf(
    "1h" to L["最近_1_小时_92e6814bba"],
    "6h" to L["最近_6_小时_5a6f35c4fd"],
    "24h" to L["最近_24_小时_eb9f889d6a"],
    "all" to L["当前日志全部_8615243b08"],
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
            text = text.ifBlank { L["无_3f83251907"] },
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
    var range by remember { mutableStateOf(L["24h_0277959611"]) }
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
        title = L["审计日志_7666cd4323"],
        desc = L["auditd_记录的敏感文件变更_谁_用什么命令_de2731982f"],
    ) {
        when {
            loading && d == null -> PanelCard(title = L["审计日志_7666cd4323"]) {
                LoadingState(L["正在读取审计日志_414fed4710"])
            }

            errText != null -> PanelCard(title = L["查询失败_381ea3ac5e"]) {
                ErrorState(errText!!, onRetry = { scope.launch { query() } })
            }

            d == null -> PanelCard(title = L["审计日志_7666cd4323"]) {
                ErrorState(L["暂无数据_b246458f20"], onRetry = { scope.launch { query() } })
            }

            !available -> PanelCard(title = L["审计不可用_5ba8cd56a3"], right = { PanelIcon("shieldAlert", size = 14.dp) }) {
                ErrorState(d.str("reason").ifBlank { L["auditd_未启用_2ca17006e2"] })
                SectionTitle(L["启用方法_9260bb76c1"])
                CodeBlock(
                    L["apt_get_install_auditd_3127c05c11"] +
                        "# 载入规则 /etc/audit/rules.d/90-relay.rules\n" +
                        L["augenrules_load_5570be381e"]
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
        StatTile(label = L["命中事件_5a9f9753fc"], value = d.int("total").toString(), sub = L["当前筛选_8936ccc663"])
        StatTile(label = L["审计规则_bc6a3d7a3f"], value = d.arr("rules").size.toString(), sub = L["auditctl_l_d215291d63"])
        StatTile(
            label = L["日志体积_89cbbd3cbe"],
            value = d.str("storage").split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { "—" },
            sub = d.str("logFile"),
        )
        StatTile(label = L["审计服务_17e6a7950d"], value = L["运行中_5942497005"], sub = "auditd")
    }

    /* ---- A2 筛选 ---- */
    val keys = d.arr("keys").map { it.prim() }
    val counts = d.obj("counts") ?: JsonObject(emptyMap())
    val countOf: (String) -> Int = { k -> (counts[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
    val keyOptions = buildList {
        add(L["全部_仅显示有规则标记的事件_c50328c4bb"])
        keys.forEach { add("$it (${countOf(it)})") }
    }
    val keyValues = buildList { add(""); addAll(keys) }
    val displayIndex = keyValues.indexOf(keyState).let { if (it < 0) 0 else it }

    PanelCard(title = L["筛选_dcce9a144a"], right = { PanelIcon("filter", size = 14.dp) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectField(
                value = AUDIT_RANGES.firstOrNull { it.first == rangeState }?.second ?: L["最近_24_小时_eb9f889d6a"],
                options = AUDIT_RANGES.map { it.second },
                onValueChange = { label ->
                    val v = AUDIT_RANGES.firstOrNull { it.second == label }?.first ?: L["24h_0277959611"]
                    onQuery(v, keyState, limitState)
                },
                label = L["时间范围_2be9040878"],
                modifier = Modifier.weight(1f),
            )
            SelectField(
                value = keyOptions.getOrElse(displayIndex) { keyOptions.first() },
                options = keyOptions,
                onValueChange = { label ->
                    val idx = keyOptions.indexOf(label)
                    onQuery(rangeState, keyValues.getOrElse(idx) { "" }, limitState)
                },
                label = L["审计规则_bc6a3d7a3f"],
                modifier = Modifier.weight(1.4f),
            )
            SelectField(
                value = limitState,
                options = AUDIT_LIMITS,
                onValueChange = { onQuery(rangeState, keyState, it) },
                label = L["返回条数_5ab4faa584"],
                modifier = Modifier.weight(0.7f),
            )
            PrimaryBtn(text = L["查询_711363c424"], onClick = { onQuery(rangeState, keyState, limitState) })
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = L["规则含义_relay_conf_中继配置_r_fc03773de9"] +
                L["nginx_site_nginx_conf_ng_adcab1b0c3"] +
                L["ssh_authorized_keys_授权密钥_3bc82ec886"],
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    /* ---- A3 事件表 ---- */
    PanelCard(
        title = L["事件_550e328062"],
        right = {
            Text(
                text = trf("0_条展示_命中_1_条_0e114bc461", events.size, d.int("total")),
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = onRefresh)
        },
    ) {
        when {
            loading -> LoadingState(L["查询中_15954fd62b"])
            events.isEmpty() -> EmptyState(L["当前条件下没有事件_f37e5eca2a"])
            else -> DataTable<JsonElement>(
                columns = listOf(
                    TableCol(title = L["时间_89b4aa6364"], weight = 1.4f, mono = true) {
                        Text(
                            text = auditTs(it.num("ts")),
                            color = Palette.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    },
                    TableCol(title = L["规则_ed904c685a"], weight = 0.9f, mono = true) {
                        val k = it.str("key")
                        if (k.isBlank()) Text("—", color = Palette.muted)
                        else LevelBadge(text = k, level = Level.Warn)
                    },
                    TableCol(title = L["进程_4eb4768d27"], weight = 0.8f, mono = true) {
                        val c = it.str("comm")
                        Text(
                            text = c.ifBlank { "—" },
                            color = if (c.isBlank()) Palette.muted else Palette.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    },
                    TableCol(title = L["命令_b114b91547"], weight = 3f, mono = true) {
                        val cmd = it.str("cmd")
                        val shown = cmd.ifBlank { "—" }.let { s -> if (s.length > 160) s.take(160) + L["s_df316e17de"] else s }
                        Text(
                            text = shown,
                            color = if (cmd.isBlank()) Palette.muted else Palette.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    TableCol(title = L["涉及文件_f54d77becb"], weight = 2.4f, mono = true) {
                        val files = it.arr("files").map { f -> f.prim() }
                        Text(
                            text = files.joinToString(L["s_d3bc9a378d"]).ifBlank { "—" },
                            color = if (files.isEmpty()) Palette.muted else Palette.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    TableCol(title = L["结果_0a2c91cec6"], weight = 0.7f) {
                        when (it.str("success")) {
                            "yes" -> LevelBadge(text = L["成功_51991a5d11"], level = Level.Ok)
                            "no" -> LevelBadge(text = L["失败_3e3c8068bb"], level = Level.Bad)
                            else -> Text("—", color = Palette.muted)
                        }
                    },
                ),
                rows = events,
                rowKey = { "${it.num("ts").toLong()}-${it.str("key")}-${it.str("cmd")}" },
                emptyText = L["当前条件下没有事件_f37e5eca2a"],
                modifier = Modifier.heightIn(max = 560.dp),
            )
        }
    }

    /* ---- A4 审计规则 ---- */
    PanelCard(title = L["已加载的审计规则_575f3a8203"], right = { PanelIcon("key", size = 14.dp) }) {
        CodeBlock(text = d.arr("rules").joinToString("\n") { it.prim() }, maxLines = 12)
        Spacer(Modifier.height(6.dp))
        Text(
            text = L["日志按_var_log_audit_audit_705976aed4"],
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
