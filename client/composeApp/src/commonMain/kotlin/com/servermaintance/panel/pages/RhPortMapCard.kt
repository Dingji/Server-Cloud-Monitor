package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.has
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.platformClipboard
import com.servermaintance.panel.ui.AdaptiveRow
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.Dim
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 端口映射编辑器 —— 复刻 relayhub.js 的 MAP 段（239-487 行）
 *
 * 必须与面板一致的语义：
 *  - 保存只写进**面板的期望态**（mappings.json），隧道不会立刻变化；还要在设备上执行
 *    「生成下发命令」给出的那条命令才真正生效（面板反复强调这点）。
 *  - 编辑期间 500ms 轮询**不得重建这张卡**，否则用户输入被清。这里草稿状态只跟
 *    `device` 绑，summary 轮询不参与；映射只在切设备 / 保存后重取（同面板 mapFetch）。
 *  - 代理名为空或形如 `port-<n>`（自动名）时，跟随本地端口重算，避免改完端口
 *    残留旧名被后端以「代理名重复」整次拒绝。
 *  - 「生成下发命令」前必须先保存。
 * ===================================================================== */

/** 一行可编辑映射。`id` 只为 Compose 的行稳定性，不进请求体。 */
internal data class RhMapRow(
    val id: Int,
    val name: String = "",
    val localPort: String = "",
    val remotePort: String = "",
    val type: String = "tcp",
    val note: String = "",
    val localIP: String = "127.0.0.1",
)

/** 与面板 `mapSig` 同一字段集：只有这几项变化才算「有未保存的修改」。 */
internal fun rhMapSig(rows: List<RhMapRow>): String =
    rows.joinToString(";") { "${it.name}|${it.localPort}|${it.remotePort}|${it.type}|${it.note}" }

/** 面板 `/^port-\d+$/`：这种名字是系统起的，必须跟随本地端口重算。 */
internal fun rhMapAutonamed(name: String): Boolean {
    if (!name.startsWith("port-")) return false
    val tail = name.removePrefix("port-")
    return tail.isNotEmpty() && tail.all { it.isDigit() }
}

/** JSON 里可能是数字也可能是 null；null/缺失给空串（面板 `m.localPort` 原样进 input）。 */
internal fun rhPortOf(el: JsonObject, key: String): String =
    if (el.has(key)) el.int(key).toString() else ""

internal fun rhDigitsOnly(s: String): String = s.filter { it.isDigit() }

@Composable
internal fun RhPortMapCard(device: String) {
    val scope = rememberCoroutineScope()
    var rows by remember(device) { mutableStateOf<List<RhMapRow>>(emptyList()) }
    var baseSig by remember(device) { mutableStateOf("") }
    var live by remember(device) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var rangesText by remember(device) { mutableStateOf("") }
    var maxRows by remember(device) { mutableStateOf(16) }
    var loaded by remember(device) { mutableStateOf(false) }
    var busy by remember(device) { mutableStateOf(false) }
    var msg by remember(device) { mutableStateOf("") }
    var kind by remember(device) { mutableStateOf("") }
    var cmd by remember(device) { mutableStateOf("") }
    var warn by remember(device) { mutableStateOf("") }
    var nextId by remember(device) { mutableIntStateOf(0) }

    val dirty = loaded && rhMapSig(rows) != baseSig

    suspend fun fetch(keepMsg: Boolean) {
        try {
            val d = AppState.api.get("/relayhub/mappings") as? JsonObject ?: return
            rangesText = d.str("ranges_text")
            if (d.has("max_per_device")) maxRows = d.int("max_per_device")
            live = (d.obj("live")?.arr(device) ?: emptyList()).filterIsInstance<JsonObject>()
            val parsed = (d.obj("mappings")?.arr(device) ?: emptyList()).filterIsInstance<JsonObject>().map { m ->
                RhMapRow(
                    id = nextId++,
                    name = m.str("name"),
                    localPort = rhPortOf(m, "localPort"),
                    remotePort = rhPortOf(m, "remotePort"),
                    type = m.str("type").ifEmpty { "tcp" }.lowercase(),
                    note = m.str("note"),
                    localIP = m.str("localIP").ifEmpty { "127.0.0.1" },
                )
            }
            rows = parsed
            baseSig = rhMapSig(parsed)
            loaded = true
            if (!keepMsg) { msg = ""; kind = "" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = trf("relayhub.map.fetch_fail", e.message ?: "")
            kind = "err"
        }
    }

    LaunchedEffect(device) { fetch(keepMsg = false) }

    fun setRow(id: Int, transform: (RhMapRow) -> RhMapRow) {
        rows = rows.map { if (it.id == id) transform(it) else it }
    }

    PanelCard(title = L["relayhub.map.title"], cardKey = "rh-map", collapsible = false) {
        Text(L["relayhub.map.hint"], color = Palette.muted, fontSize = 11.5.sp, maxLines = 4)
        Text(
            if (rangesText.isEmpty()) L["relayhub.map.reading_ranges"]
            else trf("relayhub.map.allowed", rangesText, maxRows),
            color = Palette.muted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(9.dp))

        DataTable(
            columns = listOf(
                TableCol<RhMapRow>(title = L["relayhub.map.th_local"], weight = 1.0f, minWidth = 104.dp) { r ->
                    Field(
                        value = r.localPort,
                        onValueChange = { v -> setRow(r.id) { it.copy(localPort = rhDigitsOnly(v)) } },
                        hint = L["relayhub.map.ph_local"],
                        mono = true,
                        enabled = !busy,
                    )
                },
                TableCol<RhMapRow>(title = L["relayhub.map.th_remote"], weight = 1.0f, minWidth = 110.dp) { r ->
                    Field(
                        value = r.remotePort,
                        onValueChange = { v -> setRow(r.id) { it.copy(remotePort = rhDigitsOnly(v)) } },
                        hint = L["relayhub.map.ph_remote"],
                        mono = true,
                        enabled = !busy,
                    )
                },
                TableCol<RhMapRow>(title = L["relayhub.map.th_proto"], weight = 0.7f, minWidth = 96.dp) { r ->
                    SelectField(
                        value = if (r.type == "udp") "UDP" else "TCP",
                        options = listOf("TCP", "UDP"),
                        onValueChange = { v -> setRow(r.id) { it.copy(type = if (v == "UDP") "udp" else "tcp") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                TableCol<RhMapRow>(title = L["relayhub.map.th_note"], weight = 1.3f, minWidth = 120.dp) { r ->
                    Field(
                        value = r.note,
                        onValueChange = { v -> setRow(r.id) { it.copy(note = v) } },
                        hint = L["relayhub.map.ph_note"],
                        enabled = !busy,
                    )
                },
                TableCol<RhMapRow>(
                    title = L["relayhub.map.th_name"],
                    weight = 1.4f,
                    minWidth = 168.dp,
                    align = TextAlign.End,
                ) { r ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TableText(
                            r.name.ifEmpty { L["relayhub.map.pending_name"] },
                            color = if (r.name.isEmpty()) Palette.muted else Palette.text,
                            mono = true,
                            maxLines = 1,
                        )
                        DangerBtn(
                            text = tr("删除_3755f56f2f"),
                            onClick = {
                                rows = rows.filterNot { it.id == r.id }
                                cmd = ""            // 面板：配置已变，旧命令作废
                            },
                            enabled = !busy,
                        )
                    }
                },
            ),
            rows = rows,
            rowKey = { it.id },
            emptyText = L["relayhub.map.empty"],
        )

        if (live.isNotEmpty()) {
            val text = live.joinToString(" · ") { p ->
                val local = if (p.has("localPort")) p.int("localPort").toString() else "?"
                "${p.str("name").ifEmpty { "?" }} $local→${p.int("remotePort")} " +
                    (if (p.str("status") == "online") L["在线_0373ff9231"] else L["离线_211357d22f"])
            }
            Text(
                L["relayhub.map.live_prefix"] + " $text",
                color = Palette.muted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        AdaptiveRow(spacing = 8.dp) {
            GhostBtn(
                text = L["relayhub.map.add"],
                onClick = { rows = rows + RhMapRow(id = nextId) },
                enabled = !busy && rows.size < maxRows,
            )
            Text(
                when {
                    busy -> L["relayhub.map.busy"]
                    dirty -> L["relayhub.map.dirty"]
                    loaded -> L["relayhub.map.synced"]
                    else -> L["relayhub.map.reading"]
                },
                color = if (dirty) Palette.warn else Palette.muted,
                fontSize = 11.5.sp,
                // 桌面：吃掉中间的空隙把两个动作按钮顶到右边（等价原来的 Spacer(weight)）；
                // 手持端：整行显示状态，按钮各占一行，不再横向溢出被裁掉
                modifier = Modifier.span(1f),
            )
            GhostBtn(
                text = L["relayhub.map.gen"],
                enabled = !busy,
                onClick = {
                    scope.launch {
                        // 面板 mapGen：有未保存修改时拒绝生成
                        if (dirty) {
                            msg = L["relayhub.map.unsaved_first"]; kind = "warn"; return@launch
                        }
                        if (!loaded) return@launch
                        busy = true
                        try {
                            val d = AppState.api.get("/relayhub/frpc-config?name=" + device) as? JsonObject
                            cmd = d?.str("apply")?.trim().orEmpty()
                            warn = d?.str("warning").orEmpty()
                            if (cmd.isEmpty()) {
                                msg = L["relayhub.map.no_mappings_gen"]; kind = "warn"
                            } else {
                                msg = ""; kind = ""
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            msg = trf("relayhub.map.gen_fail", e.message ?: ""); kind = "err"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            GhostBtn(
                text = L["relayhub.map.save"],
                enabled = !busy && dirty,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val payload = rhMapPayload(rows)
                            val body = jsonObjectOf(
                                "device" to device,
                                "mappings" to JsonArray(payload),
                            )
                            val d = AppState.api.post("/relayhub/mappings", body) as? JsonObject
                            warn = d?.str("warning").orEmpty()
                            cmd = ""                 // 面板：配置已变，旧命令作废
                            val count = d?.int("count") ?: 0
                            fetch(keepMsg = true)
                            msg = if (count > 0) trf("relayhub.map.saved_count", count)
                            else L["relayhub.map.saved_cleared"]
                            kind = if (count > 0) "ok" else "warn"
                            if (count > 0) AppState.toast(tr("relayhub.map.saved_toast"), ToastKind.Ok)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 后端已把逐条校验明细拼进 error 文本，这里原样显示
                            msg = e.message ?: e.toString(); kind = "err"
                            AppState.toast(
                                trf("relayhub.map.save_failed", e.message ?: ""),
                                ToastKind.Err,
                                ms = 6000,
                            )
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }

        if (rows.size >= maxRows) {
            Text(
                L["relayhub.map.max_reached"],
                color = Palette.warn,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (msg.isNotEmpty()) {
            RhCardMsg(msg, when (kind) { "err" -> Palette.bad; "warn" -> Palette.warn; else -> Palette.ok })
        }
        if (warn.isNotEmpty()) RhCardMsg(warn, Palette.warn)

        if (cmd.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val shape = RoundedCornerShape(Dim.radius.dp)
            Column(
                Modifier.fillMaxWidth()
                    .clip(shape)
                    .background(Palette.panel2)
                    .border(1.dp, Palette.border2, shape)
                    .padding(11.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        trf("relayhub.map.cmd_title", device),
                        color = Palette.text,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    GhostBtn(
                        text = L["relayhub.map.copy"],
                        onClick = {
                            platformClipboard.copy(cmd)
                            AppState.toast(tr("relayhub.map.copied"), ToastKind.Ok)
                        },
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(cmd, color = Palette.info, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(7.dp))
                Text(L["relayhub.map.cmd_note"], color = Palette.muted, fontSize = 11.sp, maxLines = 4)
            }
        }
    }
}

/** 面板 `mapPayload()`：丢掉两个端口都没填的行；自动名跟随本地端口；类型只认 udp。 */
internal fun rhMapPayload(rows: List<RhMapRow>): List<JsonObject> = rows
    .filter { it.localPort.isNotBlank() || it.remotePort.isNotBlank() }
    .map { r ->
        val lp = rhDigitsOnly(r.localPort).toLongOrNull()
        val given = r.name.trim()
        jsonObjectOf(
            "name" to (if (given.isEmpty() || rhMapAutonamed(given)) "port-${lp ?: 0}" else given),
            "type" to if (r.type == "udp") "udp" else "tcp",
            "localIP" to r.localIP.ifEmpty { "127.0.0.1" },
            "localPort" to (lp ?: 0L),
            "remotePort" to (rhDigitsOnly(r.remotePort).toLongOrNull() ?: 0L),
            "note" to r.note,
        )
    }

/** 卡片内的结果/校验条（面板 `.rh-mapmsg` / `.rh-poolbox` 同款样式）。 */
@Composable
internal fun RhCardMsg(text: String, color: Color) {
    val shape = RoundedCornerShape(Dim.radius.dp)
    Column(
        Modifier.fillMaxWidth()
            .padding(top = 8.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Text(text, color = color, fontSize = 11.5.sp)
    }
}
