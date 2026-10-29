package com.servermaintance.panel.pages.relay

import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.has
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AdaptiveRow
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SectionTitle
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.Toggle

private val ROUTE_FALLBACK = listOf("go", "zen", "glm", "mimo")

/** 与网页 `tzList` 同一组预设；选不中就走下面的分钟输入。`get()` 是为了跟着语言切换走。 */
private val TZ_PRESETS get() = listOf(
    480 to L["utc_08_00_北京_上海_香港_新加坡_8057041faf"],
    540 to L["utc_09_00_东京_首尔_f4f27cfbce"],
    420 to L["utc_07_00_曼谷_雅加达_384b10402b"],
    0 to L["utc_00_00_伦敦_bf6871eb95"],
    -240 to L["utc_04_00_纽约_03b373a20f"],
    -480 to L["utc_08_00_洛杉矶_20605b4a22"],
)

/**
 * 忙时策略页签 —— 对应 web `relay.js` 的 `tabPolicy()`。
 *
 * 这里只是编辑器：改不改写由请求路径上的 tap 决定。判断逻辑不在客户端复刻第二份，
 * 是为了消除「界面说不改写、线上却在改写」的分歧 —— 连预览都是把 `?at=` 交给 tap 回答。
 *
 * 轮询不覆盖草稿：只在第一次拿到配置时播种，之后填的内容归用户。
 */
@Composable
fun RelayPolicyTab() {
    val feed = rememberRelayFeed(mapOf("policy" to "/relay/busy-hours"), intervalMs = 15_000L)
    val data = feed["policy"]

    // 试算：路径变了 feed 会重建并立刻重拉，判定仍在 tap 一侧
    var previewAt by remember { mutableStateOf("") }
    var previewDraft by remember { mutableStateOf("") }
    val previewPaths: Map<String, String> =
        if (previewAt.isBlank()) emptyMap()
        else mapOf("p" to ("/relay/busy-hours?at=" + previewAt.replace(" ", "%20")))
    val previewFeed = rememberRelayFeed(previewPaths, intervalMs = 3_600_000L,
                                        enabled = previewAt.isNotBlank())
    val viewingPreview = previewAt.isNotBlank() && previewFeed.has("p")
    val view = if (viewingPreview) previewFeed["p"] else data

    var seeded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var weekdays by remember { mutableStateOf(true) }
    var route by remember { mutableStateOf("go") }
    var target by remember { mutableStateOf("") }
    var prefixes by remember { mutableStateOf("deepseek") }
    var offset by remember { mutableStateOf("480") }
    var rows by remember { mutableStateOf(listOf("09:00" to "12:00", "14:00" to "18:00")) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(data) {
        if (seeded || !data.has("config")) return@LaunchedEffect
        val c = data.obj("config") ?: return@LaunchedEffect
        enabled = c.bool("enabled")
        weekdays = c.bool("weekdays_only")
        route = c.str("route").ifBlank { "go" }
        target = c.str("target_model")
        prefixes = c.arr("rewrite_prefixes").joinToString(", ") { jstr(it) }
        offset = c.int("utc_offset_minutes").toString()
        rows = c.arr("windows").map { w ->
            val o = w as? JsonObject
            (o?.str("start").orEmpty()) to (o?.str("end").orEmpty())
        }
        seeded = true
    }

    val cfg = data.obj("config")
    val tap = data.obj("tap")
    val loadErr = feed.err("policy")
    val routes = data.arr("policy_routes").map { jstr(it) }.filter { it.isNotEmpty() }
        .ifEmpty { ROUTE_FALLBACK }
    val targets = data.arr("go_allowlist").map { jstr(it) }.filter { it.isNotEmpty() }
        .let { if (target.isNotBlank() && target !in it) it + target else it }
    val inPath = tap?.bool("in_request_path") != false

    PanelCard(
        title = L["当前状态_045859e792"],
        cardKey = "relay-policy-state",
        collapsible = false,
        right = {
            val at = view.str("at")
            if (at.isNotEmpty()) Chip(text = trf("试算_0_268b1e7c77", at), tone = Level.Muted)
        },
    ) {
        if (loadErr != null && !data.has("config")) {
            Text(
                text = trf("忙时策略读取失败_0_a28a65512c", loadErr),
                color = Palette.bad,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = L["中继统计服务需要包含_api_busy_hou_5a0f990a89"],
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            return@PanelCard
        }

        // 忙/闲的判定由服务端就地给出（它调的是 tap 本人那份函数），这里只画出来。
        // 老版本中继不带 phase，才退回按 window_active_now 显示 —— 仍然不在客户端算窗口。
        val ph = view.obj("phase")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ph != null) {
                val state = ph.str("state")
                Chip(
                    text = ph.str("label").ifEmpty {
                        when (state) {
                            "busy" -> L["忙时_aca614835e"]
                            "unknown" -> L["未知_d9c32a4c3d"]
                            else -> L["闲时_3a52aa95b4"]
                        }
                    },
                    tone = when (state) {
                        "busy" -> Level.Warn          // 正在改写：琥珀，和网页同一语义
                        "unknown" -> Level.Muted      // 判不出来就说判不出来，不假装闲
                        else -> Level.Ok
                    },
                )
            } else {
                val active = view.bool("window_active_now")
                Chip(
                    text = if (active) L["现在处于改写时段_634e62ff83"] else L["现在不改写_38d1eb76b9"],
                    tone = if (active) Level.Ok else Level.Muted,
                )
            }
            val off = view.bool("switch_off")
            Chip(
                text = if (off) L["已被临时停用_e2f1bbc35c"] else L["策略启用中_6ec06b6ca7"],
                tone = if (off) Level.Warn else Level.Ok,
            )
        }
        val phReason = ph?.str("reason").orEmpty()
        if (phReason.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            val yearGap = ph != null && ph.has("year_data_available") && !ph.bool("year_data_available")
            Text(
                text = phReason,
                color = if (yearGap) Palette.warn else Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                text = if (inPath) L["流量经过_tap_1fdad4908e"] else L["tap_已被旁路_da9f9d5110"],
                tone = if (inPath) Level.Ok else Level.Bad,
            )
            if (data.bool("config_file_invalid")) {
                Chip(text = L["策略文件解析失败_沿用上一次成功读取的内容_8a4b6499c9"], tone = Level.Warn)
            }
        }
        if (!inPath) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "nginx 没有把流量转到 tap：改写与白名单都不会执行。",
                color = Palette.bad,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(10.dp))
        val wins = (cfg?.arr("windows") ?: emptyList()).joinToString("、") { w ->
            val o = w as? JsonObject
            (o?.str("start").orEmpty() + "-" + o?.str("end").orEmpty())
        }
        val rewrites = if (tap?.has("rewrites") == true) tap.int("rewrites").toString() else "—"
        val lastLr = tap?.obj("last_rewrite")
        StatRow(
            columns = 4,
            items = listOf(
                StatItem(L["作用线路_c0a1e6127a"], "/" + (cfg?.str("route") ?: "").ifBlank { "—" } + "/"),
                StatItem(
                    L["命中前缀与目标模型_aa4e6f9ee8"],
                    jstr(cfg?.arr("rewrite_prefixes")?.firstOrNull()).ifEmpty { "—" } +
                        " → " + (cfg?.str("target_model") ?: "").ifBlank { "—" },
                ),
                StatItem(L["时间窗口_3c27d8bb87"], wins.ifEmpty { L["无_72077749f7"] }),
                StatItem(
                    L["累计改写次数_4d1fa9a3f7"],
                    rewrites,
                    sub = lastLr?.let {
                        if (it.has("from")) jstr(it.key("from")) + " → " + jstr(it.key("to")) else ""
                    } ?: "",
                ),
            ),
        )

        view.obj("probe")?.let { p ->
            Spacer(Modifier.height(8.dp))
            val to = jstr(p.key("would_rewrite_to"))
            Text(
                text = (if (viewingPreview) L["试算结果_8959d1bc26"] else L["此刻试算_18b23624da"]) +
                    jstr(p.key("model")) + " → " + to.ifEmpty { L["不改写_fef4a22dda"] } +
                    "（" + p.str("reason") + "）",
                color = Palette.text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Spacer(Modifier.height(14.dp))

    /* ---------------- 节假日与调休 ----------------
       日历由服务端用 tap 同一个 is_workday() 算好下发，只给「和周一到周五不一样」的日子；
       浏览器自己去抓节假日 CDN 既到不了，也会和真正改写流量的判定各说各话。 */
    PanelCard(
        title = L["中国国内节假日与调休_fe0729e131"],
        cardKey = "relay-policy-calendar",
        collapsible = false,
        right = {
            val yrs = view.obj("calendar_years")
            if (yrs != null && yrs.has("min")) {
                Chip(text = "chinese_calendar ${yrs.int("min")}-${yrs.int("max")}", tone = Level.Muted)
            }
        },
    ) {
        val cal = view.obj("calendar")
        val note = cal?.str("note").orEmpty()
        when {
            cal == null -> Text(
                text = L["这个中继版本还没有下发节假日日历_b6bacff934"],
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            !cal.bool("available") -> Text(
                text = note.ifEmpty { L["该年没有节假日数据_4de0c82dae"] },
                color = Palette.warn,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                val today = view.str("at").take(10)
                val days = cal.arr("days").mapNotNull { it as? JsonObject }
                Text(
                    text = trf("0_年_休假_1_天_调休上班_2_32cac65f0f", cal.int("year"), cal.int("off_count"), cal.int("makeup_count")),
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                // 接下来的排前面，过去的沉下去：要看的是下一次调休
                val upcoming = days.filter { it.str("date") >= today }.sortedBy { it.str("date") }
                val past = days.filter { it.str("date") < today }.sortedByDescending { it.str("date") }
                (upcoming + past).take(12).forEach { d ->
                    val dim = d.str("date") < today
                    val rest = d.str("type") != "makeup"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = d.str("date"),
                            modifier = Modifier.weight(1.3f),
                            color = if (dim) Palette.muted else Palette.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = d.str("name"),
                            modifier = Modifier.weight(1.7f),
                            color = if (dim) Palette.muted else Palette.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Chip(
                            text = if (rest) L["休假_e063611c02"] else L["调休上班_63b4228f69"],
                            tone = if (rest) Level.Ok else Level.Warn,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (days.size > 12) {
                    Text(
                        text = trf("其余_0_天略_2a5caa33c9", days.size - 12),
                        color = Palette.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    PanelCard(
        title = L["试算某个时刻_9d0f8160ed"],
        cardKey = "relay-policy-preview",
        collapsible = false,
    ) {
        Text(
            text = L["判定交给_tap_不在客户端重算一遍_d38d41afa6"],
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Field(
            value = previewDraft,
            onValueChange = { previewDraft = it },
            label = L["时间_hh_mm_或_yyyy_mm_dd_hh_293d938628"],
            hint = L["例如_10_30_或_2026_10_31_10_afab4a5c56"],
            mono = true,
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostBtn(
                text = L["试算_959ab3bcab"],
                icon = "search",
                enabled = previewDraft.isNotBlank(),
                onClick = { previewAt = previewDraft.trim() },
            )
            if (previewAt.isNotBlank()) {
                GhostBtn(
                    text = L["回到实时_adf8037d7c"],
                    icon = "refresh",
                    onClick = { previewAt = ""; previewDraft = "" },
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    PanelCard(
        title = L["忙时改写策略_bf80e555f9"],
        cardKey = "relay-policy-edit",
        collapsible = false,
        right = {
            val path = data.str("config_path")
            if (path.isNotEmpty()) Chip(text = path, tone = Level.Muted)
        },
    ) {
        Toggle(checked = enabled, onCheckedChange = { enabled = it }, label = L["启用策略_fc94dc5947"])
        Spacer(Modifier.height(6.dp))
        Toggle(
            checked = weekdays,
            onCheckedChange = { weekdays = it },
            label = L["仅工作日_周一至周五_8c93a741af"],
        )
        Spacer(Modifier.height(12.dp))

        AdaptiveRow(spacing = 12.dp) {
            Column(Modifier.span()) {
                SectionTitle(L["作用线路_c0a1e6127a"])
                SelectField(
                    value = route,
                    options = routes,
                    onValueChange = { route = it },
                    label = L["只有这条线路的请求会被改写_2c072857c1"],
                )
            }
            Column(Modifier.span()) {
                SectionTitle(L["目标模型_改写成_0700669f93"])
                SelectField(
                    value = target,
                    options = targets.ifEmpty { listOf("") },
                    onValueChange = { target = it },
                    label = L["必须在这个套餐的白名单里_439a4398a1"],
                    placeholder = L["请选择目标模型_0a5235e11c"],
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Field(
            value = prefixes,
            onValueChange = { prefixes = it },
            label = L["命中前缀_逗号分隔_模型以这些开头才被改写_fd07488522"],
            hint = L["例如_deepseek_会命中_deepseek_9789c4d6f4"],
            mono = true,
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        val customTz = L["自定义_分钟偏移_fe8a8a7910"]
        SelectField(
            value = TZ_PRESETS.firstOrNull { it.first.toString() == offset.trim() }?.second ?: customTz,
            options = TZ_PRESETS.map { it.second } + customTz,
            onValueChange = { pick ->
                TZ_PRESETS.firstOrNull { it.second == pick }?.let { offset = it.first.toString() }
            },
            label = L["时区_fb2a23dc16"],
        )
        Spacer(Modifier.height(8.dp))
        Field(
            value = offset,
            onValueChange = { offset = it },
            label = L["时区偏移_分钟_北京为_480_51cd253d67"],
            mono = true,
            singleLine = true,
        )

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(L["时间窗口_本地时区_止时刻不含_b02d9b60fd"])
            GhostBtn(
                text = L["添加窗口_7ed9133af2"],
                icon = "plus",
                onClick = { rows = rows + ("" to "") },
            )
        }
        if (rows.isEmpty()) {
            Text(
                text = L["没有窗口等于策略永不改写_要停用请关闭上面的启用_70d998d82e"],
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        rows.forEachIndexed { i, pair ->
            AdaptiveRow(spacing = 8.dp, verticalAlignment = Alignment.Bottom) {
                Column(Modifier.span()) {
                    Field(
                        value = pair.first,
                        onValueChange = { v -> rows = rows.mapIndexed { j, x -> if (j == i) v to x.second else x } },
                        label = L["开始_hh_mm_99f4740556"],
                        mono = true,
                        singleLine = true,
                    )
                }
                Column(Modifier.span()) {
                    Field(
                        value = pair.second,
                        onValueChange = { v -> rows = rows.mapIndexed { j, x -> if (j == i) x.first to v else x } },
                        label = L["结束_hh_mm_669b729c94"],
                        mono = true,
                        singleLine = true,
                    )
                }
                // 删除按钮不该和输入框平分宽度：桌面定宽，手持端叠成整行
                Column(Modifier.spanFixed(112.dp)) {
                    GhostBtn(
                        text = L["删除_3755f56f2f"],
                        icon = "trash",
                        onClick = { rows = rows.filterIndexed { j, _ -> j != i } },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(12.dp))
        AdaptiveRow(spacing = 8.dp) {
            PrimaryBtn(
                text = L["保存策略_d824c49034"],
                icon = "check",
                enabled = !busy,
                modifier = Modifier.span(),
                onClick = {
                    val list = prefixes.split(',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                    if (list.isEmpty()) {
                        AppState.toast(L["至少要有一个命中前缀_否则永远不会改写_b74f30e401"], ToastKind.Err)
                        return@PrimaryBtn
                    }
                    if (target.isBlank()) {
                        AppState.toast(L["请选择目标模型_0a5235e11c"], ToastKind.Err)
                        return@PrimaryBtn
                    }
                    val winTxt = rows.joinToString("、") { it.first + "-" + it.second }
                        .ifEmpty { L["无窗口_a1466b777a"] }
                    busy = true
                    scope.launch {
                        try {
                            relayAction(
                                name = "busy-hours/save",
                                payload = policyPayload(
                                    enabled, weekdays, route, target, prefixes, offset, rows,
                                ),
                                confirmFirst = true,
                                confirmTitle = L["保存忙时策略_65e434c7c3"],
                                confirmMsg = trf("线路_0_命中_1_的模型_a43759b64d", route, list.joinToString(",")) +
                                    trf("将改写成_0_窗口_1_f6a296de47", target, winTxt) +
                                    (if (weekdays) L["仅工作日_4832af2441"] else L["每天_5e4715ffc0"]) +
                                    (if (enabled) L["启用_7fb1923cee"] else L["关闭_fe7601b911"]) +
                                    L["这会改变生产环境的路由行为_158a372ca9"],
                                reload = {
                                    seeded = false
                                    feed.reload()
                                },
                            )
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            val offNow = data.bool("switch_off")
            GhostBtn(
                text = if (offNow) L["恢复改写_a557ac8ee8"] else L["临时停用改写_9b3eaf6ce7"],
                icon = "warning",
                modifier = Modifier.span(),
                onClick = {
                    scope.launch {
                        relayAction(
                            name = "busy-hours/switch",
                            payload = jsonObjectOf("off" to !offNow),
                            reload = { feed.reload() },
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = L["保存会写入策略文件_tap_按文件变更自动重载_a879c44f32"],
            color = Palette.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 草稿 -> 策略文档。服务端会再校验一遍，这里不做第二套判断。 */
private fun policyPayload(
    enabled: Boolean,
    weekdays: Boolean,
    route: String,
    target: String,
    prefixes: String,
    offset: String,
    rows: List<Pair<String, String>>,
): JsonObject = jsonObjectOf(
    "enabled" to enabled,
    "weekdays_only" to weekdays,
    "route" to route,
    "target_model" to target.trim(),
    "rewrite_prefixes" to buildJsonArray {
        prefixes.split(',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .forEach { add(JsonPrimitive(it)) }
    },
    "utc_offset_minutes" to (offset.trim().toIntOrNull() ?: 480),
    "windows" to buildJsonArray {
        rows.forEach { (s, e) -> add(jsonObjectOf("start" to s.trim(), "end" to e.trim())) }
    },
)
