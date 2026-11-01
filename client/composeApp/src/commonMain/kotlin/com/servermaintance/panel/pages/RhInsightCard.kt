package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.has
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PctBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 运维洞察 —— GET /relayhub/insight（面板侧 relayhubmon.js 折好的结论）。
 *
 * 这张卡不自己算口径：端口池容量与碎片、frps 配置漂移、隧道错误与抖动、
 * 公网探测延迟都由服务端从 relayhub 的 /api/summary + /api/port-pool 折成
 * 结构化结论，web 与 Compose 两端只是把它画出来（口径必须一致）。
 *
 * 服务端快照有 15s TTL，另有 30s 后台轮询（告警规则要一直有数），
 * 所以这里 10s 拉一次就够新，不参与本页 500ms 的实时循环。
 * ===================================================================== */

/** 洞察卡的拉取节奏：服务端快照 TTL 15s + 后台 30s 轮询，10s 已经够新。 */
private const val RH_INSIGHT_MS = 10_000L

@Composable
internal fun RhInsightCard() {
    val scope = rememberCoroutineScope()
    var x by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            val r = AppState.api.get("/relayhub/insight") as? JsonObject
            x = r
            err = if (r != null && !r.bool("ok")) r.str("error") else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message ?: e::class.simpleName
        }
        loaded = true
        busy = false
    }

    AutoRefresh(key = "rh-insight", intervalMs = { RH_INSIGHT_MS }) { load() }

    val age = if (x != null) x!!.int("cacheAgeMs") else -1
    val stale = x?.bool("stale") == true
    val sec = (age.coerceAtLeast(0) / 1000).toString()
    val ageText = if (x == null) {
        if (loaded) L["中继洞察不可用_76967ba19f"] else L["读取中_b21b631cd5"]
    } else {
        val past = L["秒前_9132a8b22c"]
        trf("快照_0_1_38111bbc1f", sec, past) + if (stale) L["已过期_500ca81c2d"] else ""
    }

    PanelCard(
        title = L["运维洞察_0d605390b1"],
        cardKey = "rh-insight",
        right = {
            Chip(
                text = ageText,
                tone = when {
                    x == null || stale || err != null -> Level.Warn
                    else -> Level.Muted
                },
            )
            Spacer(Modifier.width(8.dp))
            GhostBtn(
                text = L["刷新_38108eaa1d"],
                icon = "refresh",
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch { load() }
                },
            )
        },
    ) {
        val d = x
        when {
            d == null -> Text(
                err ?: L["中继服务未响应_b597560763"],
                color = Palette.muted, fontSize = 12.sp,
            )
            !d.bool("ok") -> Text(
                d.str("error").ifEmpty { L["中继服务未响应_b597560763"] },
                color = Palette.muted, fontSize = 12.sp,
            )
            else -> {
                /* 缺哪块就少画哪块：port-pool 取不到时 pool/drift 为 null，
                   隧道与探测仍然有意义，不能整卡一起废掉。 */
                GridLayout(columns = 2) {
                    RhPoolCell(d.obj("pool"))
                    RhDriftCell(d.obj("drift"), d.obj("pool"))
                    RhTunnelCell(d.obj("tunnels"))
                    RhProbeCell(d.obj("probes"))
                }
            }
        }
    }
}

@Composable
private fun RhCellHead(title: String, pill: String, level: Level) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Palette.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        LevelBadge(text = pill, level = level)
    }
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun RhLine(
    text: String,
    mono: Boolean = false,
    color: Color = Palette.text,
    maxLines: Int = 2,
) {
    Text(
        text,
        color = color,
        fontSize = 11.5.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RhWarn(text: String) {
    Text(text, color = Palette.bad, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** 端口池：用了多少、还剩多少、空闲被切成几段、哪几个端口最热。 */
@Composable
private fun RhPoolCell(p: JsonObject?) {
    if (p == null) return
    val pct = p.num("pct")
    val level = when (p.str("level")) {
        "bad" -> Level.Bad
        "warn" -> Level.Warn
        else -> Level.Ok
    }
    val free = p.int("free").toString()
    val total = p.int("count").toString()
    val used = p.int("used").toString()
    val pill = when {
        pct >= 95 -> L["见底_4ea988cea4"]
        pct >= 85 -> L["偏紧_6b13e3c506"]
        else -> L["余量充足_4fba79c0f1"]
    }
    val frag = p.int("fragments").toString()
    val run = p.int("longestFreeRun").toString()
    val hot = p.arr("hottest").filterIsInstance<JsonObject>().take(6)
    val pctText = Fmt.pct(pct)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RhCellHead(L["端口池_a16ed2a7ad"], pill, level)
        PctBar(pct = pct, showLabel = false)
        RhLine(trf("0_1_空闲_已用_2_3_f68d168702", free, total, used, pctText), mono = true)
        if (p.has("exact") && !p.bool("exact")) {
            RhLine(L["范围过大未统计碎片度_e651b8c259"], color = Palette.muted)
        } else {
            RhLine(trf("空闲连续段_0_处_最长可分配_1_09597cc122", frag, run), color = Palette.muted)
        }
        if (hot.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                hot.forEach { h ->
                    val port = h.int("port").toString()
                    val cnt = h.int("count").toString()
                    Chip(text = ":$port ×$cnt", tone = Level.Muted)
                }
            }
        }
        RhLine(p.str("rangesText"), color = Palette.muted)
    }
}

/** 配置漂移：面板期望的端口段是否真的被 frps 的 allowPorts 覆盖。 */
@Composable
private fun RhDriftCell(dr: JsonObject?, pool: JsonObject?) {
    if (dr == null) return
    val matches = dr.bool("matches")
    val exp = dr.str("expected").ifEmpty { "-" }
    val allow = dr.str("frpsAllowPorts").ifEmpty { L["未设置_55a04b58cd"] }
    val missing = dr.arr("missing").map { rhText(it) }.filter { it.isNotEmpty() }
    val ufw = dr.int("ufwRules").toString()
    val prot = (pool?.int("protected") ?: 0).toString()
    val frpsCap = dr.int("frpsMaxPerClient").toString()
    val policyCap = dr.int("maxPerClient").toString()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RhCellHead(
            L["配置漂移_87e1a7f644"],
            if (matches) L["frps_与面板一致_e437f1b1e9"] else L["frps_未覆盖_73ea2eb0e7"],
            if (matches) Level.Ok else Level.Bad,
        )
        RhLine(trf("期望_0_5021b13ba5", exp))
        RhLine("frps $allow", mono = true)
        if (missing.isNotEmpty()) {
            val j = missing.joinToString(", ")
            RhWarn(trf("缺段_0_这些端口里的隧道会被_frp_fc8fb25b88", j))
        }
        if (dr.has("cappedByFrps") && dr.bool("cappedByFrps")) {
            RhWarn(trf("frps_单机上限_0_面板策略_1_b3ce606d62", frpsCap, policyCap))
        }
        RhLine(trf("ufw_托管规则_0_条_受保护端口_8c582b79b5", ufw, prot), color = Palette.muted)
    }
}

/** 隧道：在线/总数、报错明细、最近掉线、离线设备、在线但映射为空。 */
@Composable
private fun RhTunnelCell(tn: JsonObject?) {
    if (tn == null) return
    val errs = tn.arr("errors").filterIsInstance<JsonObject>().take(4)
    val flap = tn.arr("flapping").filterIsInstance<JsonObject>()
    val off = tn.arr("deviceOffline").map { rhText(it) }.filter { it.isNotEmpty() }
    val empty = tn.arr("emptyTunnels").map { rhText(it) }.filter { it.isNotEmpty() }
    val errCount = tn.int("errorCount")
    val online = tn.int("online").toString()
    val total = tn.int("total").toString()
    val conns = tn.int("curConns").toString()
    val down = Fmt.bytes(tn.num("todayIn"))
    val up = Fmt.bytes(tn.num("todayOut"))
    val pill = when {
        errCount > 0 -> trf("0_条报错_e93727172f", errCount)
        flap.isNotEmpty() -> trf("0_条抖动_2222b0d0ed", flap.size)
        else -> L["无异常_0020abec9d"]
    }
    val lvl = when {
        errCount > 0 -> Level.Bad
        flap.isNotEmpty() -> Level.Warn
        else -> Level.Ok
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RhCellHead(L["隧道健康_1b58079e23"], pill, lvl)
        RhLine(trf("0_1_在线_活动连接_2_5505e8ddbf", online, total, conns, down, up), mono = true)
        errs.forEach { e ->
            val nm = e.str("name")
            val line = if (nm.isNotEmpty()) "${e.str("device")} · $nm ${e.str("err")}" else "${e.str("device")} ${e.str("err")}"
            RhLine(line, color = Palette.bad, maxLines = 1)
        }
        if (flap.isNotEmpty()) {
            val j = flap.take(4).map {
                val ago = it.int("closedAgo").toString()
                trf("0_1_2_分钟前_e9972adaae", it.str("device"), it.str("name"), ago)
            }.joinToString("，")
            RhLine(trf("最近掉线_0_42cb059e7e", j), color = Palette.muted)
        }
        if (off.isNotEmpty()) RhWarn(trf("离线设备_0_896f42ca22", off.joinToString(", ")))
        if (empty.isNotEmpty()) RhWarn(trf("在线但无隧道_0_frpc_活着_映射是空的_51870d25d7", empty.joinToString(", ")))
    }
}

/** 公网探测：端到端可达覆盖、平均延迟、最慢几条、失败明细。 */
@Composable
private fun RhProbeCell(pr: JsonObject?) {
    if (pr == null) return
    val failed = pr.arr("failed").filterIsInstance<JsonObject>().take(4)
    val slow = pr.arr("slowest").filterIsInstance<JsonObject>().take(5)
    val failedCount = pr.int("failedCount")
    val cov = pr.int("covered").toString()
    val tot = pr.int("total").toString()
    val avg = if (pr.has("avgMs")) pr.int("avgMs").toString() else "-"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RhCellHead(
            L["公网探测_6cc512434f"],
            if (failedCount > 0) trf("0_条不可达_a234a3fb8f", failedCount) else L["全部可达_c988faee86"],
            if (failedCount > 0) Level.Warn else Level.Ok,
        )
        RhLine(trf("覆盖_0_1_台_平均_2_933b010e86", cov, tot, avg), mono = true)
        if (slow.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                slow.forEach { s ->
                    val ms = s.int("ms").toString()
                    Chip(text = "${s.str("name")} $ms ms", tone = Level.Muted)
                }
            }
        }
        failed.forEach { f ->
            val detail = f.str("detail").ifEmpty { L["不可达_358801edaa"] }
            RhLine("${f.str("name")} $detail", color = Palette.bad, maxLines = 1)
        }
        RhLine(
            L["探测只能按超时推断_被拦_云安全组是否放行需以_cd839a2266"],
            color = Palette.muted, maxLines = 3,
        )
    }
}
