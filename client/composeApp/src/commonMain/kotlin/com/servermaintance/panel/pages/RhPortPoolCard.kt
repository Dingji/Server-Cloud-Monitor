package com.servermaintance.panel.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.has
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.ui.AdaptiveRow
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* =====================================================================
 * 端口池配置卡 —— 复刻 relayhub.js 的 POOL 段（490-882 行）
 *
 * 服务端 config.json 的 port_pool 是唯一可信源；这卡只做：
 *   改草稿 → ① 校验预览（只读不落盘，返回 diff）→ ② 应用到服务端
 *   （备份 → 写 config → 就地重载 → 改 frps.toml → 同步 ufw → 重启 frps）→ 公网自检。
 *
 * 必须对齐的行为：
 *  - 「应用」只有在预览通过且草稿没再改动时才可用（面板 poolActionState）。
 *  - preview/apply/probe 走 postRetainingBody：400/409 的 errors/conflicts 必须看得见。
 *  - 应用结果带 rollback_pool 时给「↺ 回滚到应用前」，复用同一个 apply 接口。
 *  - 徽章在卡片折叠时也要有值 → 进页面就加载状态（面板 mount 里同样做法）。
 * ===================================================================== */

internal data class RhRangeRow(val id: Int, val label: String, val start: String, val end: String)

internal data class RhPoolDraft(
    val ranges: List<RhRangeRow>,
    val exclude: String,
    val policy: String,
    val maxPerClient: String,
    val syncUfw: Boolean,
    /** 面板把 `protected` 原样带回提交，服务端据此拒绝覆盖保护端口。 */
    val protectedPorts: JsonArray,
)

private val RH_POLICIES = listOf(
    "sequential" to "relayhub.pool.pol_sequential",
    "random" to "relayhub.pool.pol_random",
    "least_used" to "relayhub.pool.pol_least_used",
)

internal fun rhDigits(s: String): String = s.filter { it.isDigit() }

/**
 * 面板 `poolSig` 里的 `+value` 语义：`"016"` 与 `"16"` 相等（不该算改动），
 * 非数字 → NaN → JSON 里等价 `null`。只用于「是否算改动」的签名，不用于取值。
 */
private fun rhNum(s: String): String = rhDigits(s).toLongOrNull()?.toString() ?: "null"

/** JsonElement（含数组里的原始值）→ 可读文本；字符串去掉 JSON 引号。 */
internal fun rhText(el: JsonElement?): String = when (el) {
    null -> ""
    is JsonPrimitive -> el.content
    else -> el.toString()
}

internal fun rhNums(arr: List<JsonElement>): List<Long> =
    arr.mapNotNull { rhText(it).toLongOrNull() }

/** 面板 `poolSig`：只有这些字段参与「有改动」判定。 */
internal fun rhPoolSig(d: RhPoolDraft): String =
    d.ranges.joinToString(";") { "${it.label}|${rhNum(it.start)}|${rhNum(it.end)}" } +
        "#" + rhNums(d.exclude.split(',', '，', '、', ';', '；', ' ').map { JsonPrimitive(it) }).joinToString(",") +
        "#" + d.policy + "#" + rhNum(d.maxPerClient) + "#" + d.syncUfw

internal fun rhPolicyLabel(key: String): String =
    RH_POLICIES.firstOrNull { it.first == key }?.let { L[it.second] } ?: key

internal fun rhPolicyOfLabel(label: String): String? =
    RH_POLICIES.firstOrNull { L[it.second] == label }?.first

/** 面板 `poolDraftErrs()`：不联网就能给出的本地校验。 */
internal fun rhPoolDraftErrs(d: RhPoolDraft): List<String> {
    val errs = ArrayList<String>()
    if (d.ranges.isEmpty()) errs += L["relayhub.pool.err_no_ranges"]
    d.ranges.forEachIndexed { i, r ->
        val name = r.label.ifEmpty { trf("relayhub.pool.range_n", i + 1) }
        val a = r.start.toLongOrNull()
        val b = r.end.toLongOrNull()
        if (a == null || b == null) {
            errs += trf("relayhub.pool.err_not_number", name)
        } else if (a > b) {
            errs += trf("relayhub.pool.err_reversed", name, r.start, r.end)
        }
    }
    return errs
}

/** 面板 `poolPayload()`：标签截 32、策略白名单、每客户端钳 1..256、保护端口原样带回。 */
internal fun rhPoolPayload(d: RhPoolDraft): JsonObject = jsonObjectOf(
    "ranges" to JsonArray(
        d.ranges.map {
            jsonObjectOf(
                "label" to it.label.take(32),
                "start" to (rhDigits(it.start).toLongOrNull() ?: 0L),
                "end" to (rhDigits(it.end).toLongOrNull() ?: 0L),
            )
        },
    ),
    "exclude" to JsonArray(
        d.exclude.split(',', '，', '、', ';', '；', ' ')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0 }
            .map { JsonPrimitive(it) },
    ),
    "alloc_policy" to (rhPolicyOfLabel(d.policy) ?: if (RH_POLICIES.any { it.first == d.policy }) d.policy else "sequential"),
    "max_per_client" to (rhDigits(d.maxPerClient).toIntOrNull() ?: 16).coerceIn(1, 256),
    "sync_ufw" to d.syncUfw,
    "protected" to d.protectedPorts,
)

/** 面板 `poolFrpsMatch()`：frps.toml 的 allowPorts 是否与生效段逐条一致。 */
internal fun rhFrpsMatch(st: JsonObject): Boolean {
    val text = st.str("frps_allowports")
    val eff = st.arr("effective")
    if (text.isEmpty() || eff.isEmpty()) return false
    val count = text.split("{ start = ").size - 1
    if (count != eff.size) return false
    return eff.all { pair ->
        val ab = (pair as? JsonArray)?.let { rhNums(it.toList()) } ?: return@all false
        ab.size == 2 && text.contains("{ start = ${ab[0]}, end = ${ab[1]} }")
    }
}

@Composable
internal fun RhPortPoolCard() {
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<JsonObject?>(null) }
    var draft by remember { mutableStateOf<RhPoolDraft?>(null) }
    var baseSig by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    var previewStale by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<JsonObject?>(null) }
    var applyr by remember { mutableStateOf<JsonObject?>(null) }
    var msg by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    var nextId by remember { mutableIntStateOf(0) }

    suspend fun load() {
        try {
            val r = AppState.api.get("/relayhub/port-pool") as? JsonObject ?: return
            val pool = r.obj("pool") ?: return
            st = r
            val ranges = pool.arr("ranges").filterIsInstance<JsonObject>().map {
                RhRangeRow(
                    id = nextId++,
                    label = it.str("label"),
                    start = if (it.has("start")) it.int("start").toString() else "",
                    end = if (it.has("end")) it.int("end").toString() else "",
                )
            }
            val nd = RhPoolDraft(
                ranges = ranges,
                exclude = rhNums(pool.arr("exclude")).joinToString(", "),
                policy = pool.str("alloc_policy").ifEmpty { "sequential" },
                maxPerClient = pool.int("max_per_client").toString(),
                syncUfw = pool.bool("sync_ufw"),
                protectedPorts = JsonArray(pool.arr("protected")),
            )
            draft = nd
            baseSig = rhPoolSig(nd)
            loaded = true
            if (kind == "err") { msg = ""; kind = "" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = trf("relayhub.pool.load_fail", e.message ?: "")
            kind = "err"
        }
    }

    /** 面板 `poolApply()`：预览确认后或回滚时都走它；失败也要看到 errors/conflicts。 */
    suspend fun applyPool(payload: JsonObject) {
        if (busy) return
        busy = true
        msg = L["relayhub.pool.applying"]; kind = "warn"
        try {
            val r = AppState.api.postRetainingBody("/relayhub/port-pool/apply", jsonObjectOf("pool" to payload))
            applyr = r.body
            if (r.ok && r.body.bool("ok")) {
                msg = ""; kind = ""
                preview = null; previewStale = false
                AppState.toast(tr("relayhub.pool.applied_toast"), ToastKind.Ok)
                load()
            } else {
                val errs = r.body.arr("errors").map { rhText(it) }.filter { it.isNotEmpty() }
                msg = errs.joinToString("; ")
                    .ifEmpty { r.body.str("error").ifEmpty { "HTTP ${r.status}" } }
                kind = "err"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = trf("relayhub.pool.apply_fail", e.message ?: ""); kind = "err"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (!loaded) {
            busy = true; load(); busy = false
        }
    }

    val d = draft
    val dirty = d != null && rhPoolSig(d) != baseSig
    val serverBusy = st?.bool("busy") == true
    val dis = busy || serverBusy
    val stale = previewStale || dirty
    val inSync = st != null && rhFrpsMatch(st!!)
    val summary = st?.obj("pool")

    PanelCard(
        title = L["relayhub.pool.title"],
        cardKey = "rh-pool",
        right = {
            Chip(
                text = when {
                    !loaded && kind == "err" -> L["relayhub.pool.read_fail"]
                    !loaded -> L["relayhub.pool.reading"]
                    inSync -> L["relayhub.pool.in_sync"]
                    else -> L["relayhub.pool.frps_mismatch"]
                },
                tone = if (loaded && inSync) Level.Ok else Level.Warn,
            )
            if (loaded && summary != null) {
                Text(
                    st?.str("ranges_text").orEmpty() +
                        " · " + trf("relayhub.pool.cur_ports", st?.int("port_count") ?: 0) +
                        " · " + rhPolicyLabel(summary.str("alloc_policy")) +
                        " · " + trf("relayhub.pool.cur_per_client", summary.int("max_per_client")) +
                        if (serverBusy) " · " + L["relayhub.pool.applying_short"] else "",
                    color = Palette.muted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            probe?.let { pr ->
                val covered = pr.bool("covered")
                val s = pr.obj("summary")
                Text(
                    if (covered) {
                        trf(
                            "relayhub.pool.probe_ok_state",
                            (s?.int("open") ?: 0) + (s?.int("refused") ?: 0),
                            pr.arr("results").size,
                        )
                    } else {
                        trf("relayhub.pool.probe_bad_state", s?.int("blocked") ?: 0)
                    },
                    color = if (covered) Palette.ok else Palette.bad,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    ) {
        val cur = d
        if (!loaded || cur == null) {
            Text(L["relayhub.pool.loading"], color = Palette.muted, fontSize = 12.sp)
            return@PanelCard
        }

        fun edit(updater: (RhPoolDraft) -> RhPoolDraft) {
            draft = updater(cur)
            previewStale = true
        }

        AdaptiveRow(spacing = 14.dp, verticalAlignment = Alignment.Top) {
            Column(Modifier.span(1f)) {
                Text(
                    trf("relayhub.pool.ranges_label", rhNums(st?.arr("protected").orEmpty()).joinToString(", ").ifEmpty { "–" }),
                    color = Palette.text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                DataTable(
                    columns = listOf(
                        TableCol<RhRangeRow>(title = L["relayhub.pool.th_label"], weight = 1.2f, minWidth = 100.dp) { r ->
                            Field(
                                value = r.label,
                                onValueChange = { v ->
                                    edit { d0 -> d0.copy(ranges = d0.ranges.map { if (it.id == r.id) it.copy(label = v.take(32)) else it }) }
                                },
                                hint = L["relayhub.pool.ph_label"],
                                enabled = !dis,
                            )
                        },
                        TableCol<RhRangeRow>(title = L["relayhub.pool.th_start"], weight = 0.8f, minWidth = 84.dp) { r ->
                            Field(
                                value = r.start,
                                onValueChange = { v ->
                                    edit { d0 -> d0.copy(ranges = d0.ranges.map { if (it.id == r.id) it.copy(start = v) else it }) }
                                },
                                hint = L["relayhub.pool.ph_start"],
                                mono = true,
                                enabled = !dis,
                            )
                        },
                        TableCol<RhRangeRow>(title = L["relayhub.pool.th_end"], weight = 0.8f, minWidth = 84.dp) { r ->
                            Field(
                                value = r.end,
                                onValueChange = { v ->
                                    edit { d0 -> d0.copy(ranges = d0.ranges.map { if (it.id == r.id) it.copy(end = v) else it }) }
                                },
                                hint = L["relayhub.pool.ph_end"],
                                mono = true,
                                enabled = !dis,
                            )
                        },
                        TableCol<RhRangeRow>(title = "", weight = 0.6f, minWidth = 76.dp, align = TextAlign.End) { r ->
                            DangerBtn(
                                text = tr("删除_3755f56f2f"),
                                onClick = {
                                    edit { d0 -> d0.copy(ranges = d0.ranges.filterNot { it.id == r.id }) }
                                },
                                enabled = !dis,
                            )
                        },
                    ),
                    rows = cur.ranges,
                    rowKey = { it.id },
                    emptyText = L["relayhub.pool.no_ranges"],
                )
                Spacer(Modifier.height(8.dp))
                AdaptiveRow(spacing = 8.dp) {
                    GhostBtn(
                        text = L["relayhub.pool.add_range"],
                        enabled = !dis,
                        onClick = {
                            // 面板：新段起点接上一段末尾 +1，跨度 +100
                            val lastEnd = rhDigits(cur.ranges.lastOrNull()?.end.orEmpty()).toLongOrNull()
                            edit { d0 ->
                                d0.copy(
                                    ranges = d0.ranges + RhRangeRow(
                                        id = nextId++,
                                        label = trf("relayhub.pool.new_range", d0.ranges.size + 1),
                                        start = lastEnd?.plus(1)?.toString().orEmpty(),
                                        end = lastEnd?.plus(100)?.toString().orEmpty(),
                                    ),
                                )
                            }
                        },
                    )
                    val used = rhNums(st?.arr("used").orEmpty())
                    Text(
                        trf(
                            "relayhub.pool.used",
                            if (used.isEmpty()) L["relayhub.pool.none"] else used.joinToString(", "),
                        ),
                        color = Palette.muted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        modifier = Modifier.span(1f),
                    )
                }
            }

            Column(Modifier.span(1f)) {
                Field(
                    value = cur.exclude,
                    onValueChange = { v -> edit { it.copy(exclude = v) } },
                    label = L["relayhub.pool.exclude_label"],
                    hint = L["relayhub.pool.ph_exclude"],
                    mono = true,
                    enabled = !dis,
                )
                Spacer(Modifier.height(8.dp))
                SelectField(
                    value = rhPolicyLabel(cur.policy),
                    options = RH_POLICIES.map { L[it.second] },
                    onValueChange = { label ->
                        rhPolicyOfLabel(label)?.let { key -> edit { it.copy(policy = key) } }
                    },
                    label = L["relayhub.pool.policy_label"],
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Field(
                    value = cur.maxPerClient,
                    onValueChange = { v -> edit { it.copy(maxPerClient = rhDigits(v)) } },
                    label = L["relayhub.pool.mpc_label"],
                    mono = true,
                    enabled = !dis,
                )
                Spacer(Modifier.height(10.dp))
                Toggle(
                    checked = cur.syncUfw,
                    onCheckedChange = { on -> edit { it.copy(syncUfw = on) } },
                    label = L["relayhub.pool.ufw_label"],
                )
                Spacer(Modifier.height(10.dp))
                Text(L["relayhub.pool.flow"], color = Palette.muted, fontSize = 11.sp, maxLines = 8)
            }
        }

        Spacer(Modifier.height(12.dp))

        AdaptiveRow(spacing = 8.dp) {
            PrimaryBtn(
                text = L["relayhub.pool.preview_btn"],
                enabled = !dis,
                onClick = {
                    scope.launch {
                        val dd = draft ?: return@launch
                        val bad = rhPoolDraftErrs(dd)
                        if (bad.isNotEmpty()) {
                            // 本地错误不发请求，直接按面板的样子呈现为「校验未通过」
                            preview = jsonObjectOf(
                                "ok" to false,
                                "errors" to JsonArray(bad.map { JsonPrimitive(it) }),
                            )
                            previewStale = false
                            return@launch
                        }
                        busy = true
                        try {
                            val r = AppState.api.postRetainingBody(
                                "/relayhub/port-pool/preview",
                                jsonObjectOf("pool" to rhPoolPayload(dd)),
                            )
                            preview = r.body
                            previewStale = false
                            msg = ""; kind = ""
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            msg = trf("relayhub.pool.preview_fail", e.message ?: ""); kind = "err"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            GhostBtn(
                text = L["relayhub.pool.apply_btn"],
                enabled = !dis && preview?.bool("ok") == true && !stale,
                onClick = {
                    scope.launch {
                        val dd = draft ?: return@launch
                        val pv = preview ?: return@launch
                        if (!pv.bool("ok") || stale) return@launch
                        val diff = pv.obj("diff")
                        val tail = if (summary?.bool("sync_ufw") != false) "\n" + L["relayhub.pool.apply_confirm_ufw"] else ""
                        val ok = Modal.confirm(
                            title = L["relayhub.pool.apply_title"],
                            text = trf(
                                "relayhub.pool.apply_confirm",
                                diff?.obj("after")?.str("text").orEmpty(),
                                diff?.obj("after")?.int("count") ?: 0,
                                diff?.obj("before")?.str("frps").orEmpty(),
                                diff?.obj("after")?.str("frps").orEmpty(),
                            ) + tail + "\n" + L["relayhub.pool.apply_confirm_tail"],
                            okText = L["relayhub.pool.apply_btn"],
                            danger = true,
                        )
                        if (!ok) return@launch
                        applyPool(rhPoolPayload(dd))
                    }
                },
            )
            GhostBtn(
                text = L["relayhub.pool.probe_btn"],
                enabled = !dis,
                onClick = {
                    scope.launch {
                        busy = true
                        msg = L["relayhub.pool.probing"]; kind = "warn"
                        try {
                            val r = AppState.api.postRetainingBody("/relayhub/port-pool/probe", jsonObjectOf())
                            if (r.ok && r.body.bool("ok")) {
                                probe = r.body; msg = ""; kind = ""
                            } else {
                                msg = trf(
                                    "relayhub.pool.probe_fail",
                                    r.body.str("error").ifEmpty { "HTTP ${r.status}" },
                                )
                                kind = "err"
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            msg = trf("relayhub.pool.probe_fail", e.message ?: ""); kind = "err"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            GhostBtn(
                text = L["relayhub.pool.reload_btn"],
                enabled = !dis,
                onClick = {
                    scope.launch {
                        if (dirty && !Modal.confirm(
                                title = L["relayhub.pool.reload_title"],
                                text = L["relayhub.pool.reload_confirm"],
                                okText = L["relayhub.pool.reload_btn"],
                            )
                        ) return@launch
                        preview = null; previewStale = false; draft = null; loaded = false
                        load()
                    }
                },
            )
            if (stale) {
                Text(L["relayhub.pool.stale_hint"], color = Palette.warn, fontSize = 11.sp, maxLines = 2)
            }
        }

        if (msg.isNotEmpty()) {
            RhCardMsg(msg, when (kind) { "err" -> Palette.bad; "warn" -> Palette.warn; else -> Palette.ok })
        }

        preview?.let { pv ->
            val pvOk = pv.bool("ok")
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    when {
                        !pvOk -> L["relayhub.pool.preview_bad"]
                        previewStale -> L["relayhub.pool.preview_stale"]
                        else -> L["relayhub.pool.preview_ok"]
                    },
                    color = if (pvOk && !previewStale) Palette.ok else if (pvOk) Palette.warn else Palette.bad,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                pv.arr("errors").forEach {
                    Text("· ${rhText(it)}", color = Palette.text, fontSize = 11.5.sp)
                }
                if (pvOk && !previewStale) {
                    val diff = pv.obj("diff")
                    val before = diff?.obj("before")
                    val after = diff?.obj("after")
                    RhPoolStep(
                        L["relayhub.pool.effective_scope"],
                        "${before?.str("text")} → ${after?.str("text")}" +
                            " (${before?.int("count") ?: 0} → ${after?.int("count") ?: 0})",
                    )
                    val mpcTxt = after?.takeIf { it.has("max_per_client") }
                        ?.let { " · maxPortsPerClient = ${it.int("max_per_client")}" } ?: ""
                    RhPoolStep(
                        "frps allowPorts",
                        "${before?.str("frps")} → ${after?.str("frps")}" + mpcTxt,
                    )
                    val rm = diff?.arr("ufw_remove").orEmpty().map { rhText(it) }.filter { it.isNotEmpty() }
                    val ad = diff?.arr("ufw_add").orEmpty().map { rhText(it) }.filter { it.isNotEmpty() }
                    RhPoolStep(
                        "ufw",
                        trf(
                            "relayhub.pool.ufw_diff",
                            if (rm.isEmpty()) L["relayhub.pool.none_paren"] else rm.joinToString(", "),
                            if (ad.isEmpty()) L["relayhub.pool.none_paren"] else ad.joinToString(", "),
                        ),
                    )
                    val risk = diff?.arr("tunnels_at_risk").orEmpty().filterIsInstance<JsonObject>().map {
                        "${it.str("name")}(${it.int("remotePort")})"
                    }
                    if (risk.isNotEmpty()) {
                        RhPoolStep(L["relayhub.pool.at_risk"], risk.joinToString(", "), tint = Palette.warn)
                    }
                }
            }
        }

        probe?.let { pr ->
            val covered = pr.bool("covered")
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    if (covered) L["relayhub.pool.probe_covered"]
                    else trf("relayhub.pool.probe_not_covered", pr.str("note")),
                    color = if (covered) Palette.ok else Palette.bad,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    trf(
                        "relayhub.pool.probe_note",
                        pr.str("host"),
                        pr.arr("results").size,
                        pr.int("took_ms"),
                    ),
                    color = Palette.muted,
                    fontSize = 11.sp,
                    maxLines = 5,
                )
                pr.arr("results").filterIsInstance<JsonObject>().forEach { x ->
                    val state = x.str("state")
                    RhPoolStep(
                        x.int("port").toString(),
                        (when (state) {
                            "open" -> L["relayhub.pool.st_open"]
                            "refused" -> L["relayhub.pool.st_refused"]
                            else -> L["relayhub.pool.st_blocked"]
                        }) + " " + x.str("note") +
                            if (x.has("ms")) " · ${x.int("ms")}ms" else "",
                        tint = when (state) {
                            "open" -> Palette.ok
                            "refused" -> Palette.info
                            else -> Palette.bad
                        },
                    )
                }
            }
        }

        applyr?.let { ap ->
            val ok = ap.bool("ok")
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(
                    if (ok) L["relayhub.pool.apply_ok"] else L["relayhub.pool.apply_partial"],
                    color = if (ok) Palette.ok else Palette.bad,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                ap.arr("errors").forEach {
                    Text("· ${rhText(it)}", color = Palette.bad, fontSize = 11.5.sp)
                }
                ap.arr("steps").filterIsInstance<JsonObject>().forEach { s ->
                    RhPoolStep(
                        s.str("step"),
                        (if (s.bool("ok")) "✓ " else "✗ ") + s.str("detail"),
                        tint = if (s.bool("ok")) Palette.ok else Palette.bad,
                    )
                }
                ap.arr("warnings").forEach {
                    Text("⚠ ${rhText(it)}", color = Palette.warn, fontSize = 11.5.sp)
                }
                val rb = ap.obj("rollback_pool")
                val backups = ap.arr("backups").map { rhText(it) }.filter { it.isNotEmpty() }
                if (rb != null || backups.isNotEmpty()) {
                    AdaptiveRow(spacing = 8.dp, modifier = Modifier.padding(top = 8.dp)) {
                        if (rb != null) {
                            GhostBtn(
                                text = L["relayhub.pool.rollback_btn"],
                                enabled = !dis,
                                onClick = {
                                    scope.launch {
                                        if (!Modal.confirm(
                                                title = L["relayhub.pool.rollback_title"],
                                                text = L["relayhub.pool.rollback_confirm"],
                                                okText = L["relayhub.pool.rollback_btn"],
                                                danger = true,
                                            )
                                        ) return@launch
                                        applyPool(rb)
                                    }
                                },
                            )
                        }
                        if (backups.isNotEmpty()) {
                            Text(
                                trf("relayhub.pool.backups_label", backups.joinToString(", ")),
                                color = Palette.muted,
                                fontSize = 11.sp,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一行「标签 — 值」的差异/步骤条目（面板 `.rh-poolstep`）。 */
@Composable
private fun RhPoolStep(label: String, value: String, tint: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            color = tint ?: Palette.text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            modifier = Modifier.width(116.dp),
        )
        Text(
            value,
            color = tint ?: Palette.muted,
            fontSize = 11.5.sp,
            modifier = Modifier.weight(1f),
        )
    }
}
