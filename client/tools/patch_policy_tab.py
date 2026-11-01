#!/usr/bin/env python3
"""One-shot patcher: add the idle-policy editor to RelayPolicyTab.kt (run from repo root)."""
import io

p = r"composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/relay/RelayPolicyTab.kt"
s = io.open(p, encoding="utf-8").read()

# 1) 闲时草稿状态
old = """    var rows by remember { mutableStateOf(listOf("09:00" to "12:00", "14:00" to "18:00")) }
    var busy by remember { mutableStateOf(false) }"""
assert old in s, "state vars"
s = s.replace(old, """    var rows by remember { mutableStateOf(listOf("09:00" to "12:00", "14:00" to "18:00")) }
    var idleEnabled by remember { mutableStateOf(true) }
    var idleTarget by remember { mutableStateOf("") }
    var idleWeekdays by remember { mutableStateOf(false) }
    var idleRows by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var busy by remember { mutableStateOf(false) }""")

# 2) 播种闲时草稿
old2 = """        rows = c.arr("windows").map { w ->
            val o = w as? JsonObject
            (o?.str("start").orEmpty()) to (o?.str("end").orEmpty())
        }
        seeded = true"""
assert old2 in s, "seed"
s = s.replace(old2, """        rows = c.arr("windows").map { w ->
            val o = w as? JsonObject
            (o?.str("start").orEmpty()) to (o?.str("end").orEmpty())
        }
        val idleDoc = c.obj("idle")
        if (idleDoc != null) {
            idleEnabled = idleDoc.bool("enabled")
            idleTarget = idleDoc.str("target_model")
            idleWeekdays = idleDoc.bool("weekdays_only")
            idleRows = idleDoc.arr("windows").map { w ->
                val o = w as? JsonObject
                (o?.str("start").orEmpty()) to (o?.str("end").orEmpty())
            }
        }
        seeded = true""")

# 3) targets 也要含闲时目标
old3 = """    val targets = data.arr("go_allowlist").map { jstr(it) }.filter { it.isNotEmpty() }
        .let { if (target.isNotBlank() && target !in it) it + target else it }"""
assert old3 in s, "targets"
s = s.replace(old3, """    val targets = data.arr("go_allowlist").map { jstr(it) }.filter { it.isNotEmpty() }
        .let { if (target.isNotBlank() && target !in it) it + target else it }
    val idleTargets = targets
        .let { if (idleTarget.isNotBlank() && idleTarget !in it) it + idleTarget else it }""")

# 4) policyPayload 增加 idle 形参
old4 = """private fun policyPayload(
    enabled: Boolean,
    weekdays: Boolean,
    route: String,
    target: String,
    prefixes: String,
    offset: String,
    rows: List<Pair<String, String>>,
): JsonObject = jsonObjectOf("""
assert old4 in s, "payload sig"
s = s.replace(old4, """private fun policyPayload(
    enabled: Boolean,
    weekdays: Boolean,
    route: String,
    target: String,
    prefixes: String,
    offset: String,
    rows: List<Pair<String, String>>,
    idle: Triple<Boolean, String, Pair<Boolean, List<Pair<String, String>>>>? = null,
): JsonObject = jsonObjectOf(""")

old5 = """    "windows" to buildJsonArray {
        rows.forEach { (s, e) -> add(jsonObjectOf("start" to s.trim(), "end" to e.trim())) }
    },
)"""
assert old5 in s, "payload body"
s = s.replace(old5, """    "windows" to buildJsonArray {
        rows.forEach { (s, e) -> add(jsonObjectOf("start" to s.trim(), "end" to e.trim())) }
    },
    "idle" to (idle?.let { (on, tg, wd) ->
        jsonObjectOf(
            "enabled" to on,
            "target_model" to tg.trim(),
            "weekdays_only" to wd.first,
            "windows" to buildJsonArray {
                wd.second.forEach { (s, e) -> add(jsonObjectOf("start" to s.trim(), "end" to e.trim())) }
            },
        )
    } ?: jsonObjectOf()),
)""")

# 5) 忙时保存带上闲时草稿
old6 = """                                payload = policyPayload(
                                    enabled, weekdays, route, target, prefixes, offset, rows,
                                ),"""
assert old6 in s, "busy save"
s = s.replace(old6, """                                payload = policyPayload(
                                    enabled, weekdays, route, target, prefixes, offset, rows,
                                    Triple(idleEnabled, idleTarget, idleWeekdays to idleRows),
                                ),""")

# 6) 插入闲时编辑卡
IDLE_CARD = """    /* ---------------- 闲时策略（v2） ----------------
       与忙时同一份配置文件的 idle 子块：不在忙时窗口内时，该线路【全部】请求改写到
       idle.target_model；窗口留空 = 忙时窗口的补集（晚间/午休/周末/节假日）。 */
    PanelCard(
        title = L["闲时改写策略_8f5c14fe70"],
        cardKey = "relay-policy-idle",
        collapsible = false,
        right = {
            val ih = data.obj("idle_hours")
            val n = ih?.int("rewrites")
            if (n != null) Chip(text = trf("0_次_2f9578aead", n) + " " + L["闲时累计改写_1cf408d364"], tone = Level.Muted)
        },
    ) {
        Toggle(checked = idleEnabled, onCheckedChange = { idleEnabled = it }, label = L["闲时启用_2e0c254cb0"])
        Spacer(Modifier.height(10.dp))
        AdaptiveRow(spacing = 12.dp) {
            Column(Modifier.span()) {
                SectionTitle(L["闲时目标模型_470ab174ec"])
                SelectField(
                    value = idleTarget,
                    options = idleTargets.ifEmpty { listOf("") },
                    onValueChange = { idleTarget = it },
                    label = L["必须在这个套餐的白名单里_439a4398a1"],
                    placeholder = L["请选择目标模型_0a5235e11c"],
                )
            }
            Column(Modifier.span()) {
                SectionTitle(L["仅工作日_4832af2441"])
                SelectField(
                    value = if (idleWeekdays) L["仅工作日_4832af2441"] else L["每天_5e4715ffc0"],
                    options = listOf(L["每天_5e4715ffc0"], L["仅工作日_4832af2441"]),
                    onValueChange = { pick -> idleWeekdays = pick == L["仅工作日_4832af2441"] },
                    label = L["只在显式配置了闲时窗口时生效_a1b2c3d4e6"],
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(L["闲时窗口_留空_忙时窗口的补集_0cd9d1e6a6"])
            GhostBtn(
                text = L["添加窗口_7ed9133af2"],
                icon = "plus",
                onClick = { idleRows = idleRows + ("" to "") },
            )
        }
        Text(
            text = L["留空_忙时窗口的补集_晚间_午休_周末与节假日整天都算闲时_f3ec0f2e81"],
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        idleRows.forEachIndexed { i, pair ->
            AdaptiveRow(spacing = 8.dp, verticalAlignment = Alignment.Bottom) {
                Column(Modifier.span()) {
                    Field(
                        value = pair.first,
                        onValueChange = { v -> idleRows = idleRows.mapIndexed { j, x -> if (j == i) v to x.second else x } },
                        label = L["开始_hh_mm_99f4740556"],
                        mono = true,
                        singleLine = true,
                    )
                }
                Column(Modifier.span()) {
                    Field(
                        value = pair.second,
                        onValueChange = { v -> idleRows = idleRows.mapIndexed { j, x -> if (j == i) x.first to v else x } },
                        label = L["结束_hh_mm_669b729c94"],
                        mono = true,
                        singleLine = true,
                    )
                }
                Column(Modifier.spanFixed(112.dp)) {
                    GhostBtn(
                        text = L["删除_3755f56f2f"],
                        icon = "trash",
                        onClick = { idleRows = idleRows.filterIndexed { j, _ -> j != i } },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        val idleWinTxt = idleRows.joinToString("、") { it.first + "-" + it.second }
            .ifEmpty { L["忙时窗口的补集_9a1b2c3d4e"] }
        PrimaryBtn(
            text = L["保存闲时策略_618c16fa65"],
            icon = "check",
            enabled = !busy,
            onClick = {
                if (idleTarget.isBlank()) {
                    AppState.toast(L["请选择目标模型_0a5235e11c"], ToastKind.Err)
                    return@PrimaryBtn
                }
                busy = true
                scope.launch {
                    try {
                        relayAction(
                            name = "busy-hours/save",
                            payload = policyPayload(
                                enabled, weekdays, route, target, prefixes, offset, rows,
                                Triple(idleEnabled, idleTarget, idleWeekdays to idleRows),
                            ),
                            confirmFirst = true,
                            confirmTitle = L["保存闲时策略_618c16fa65"],
                            confirmMsg = trf("闲时全部模型改写到_0_窗口_1_b616f3dc1f", idleTarget, idleWinTxt) +
                                (if (idleEnabled) L["启用_7fb1923cee"] else L["关闭_fe7601b911"]) +
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = L["留空_忙时窗口的补集_晚间_午休_周末与节假日整天都算闲时_f3ec0f2e81"],
            color = Palette.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}"""

old7 = """        Text(
            text = L["保存会写入策略文件_tap_按文件变更自动重载_a879c44f32"],
            color = Palette.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}"""
assert old7 in s, "editor tail"
s = s.replace(old7, """        Text(
            text = L["保存会写入策略文件_tap_按文件变更自动重载_a879c44f32"],
            color = Palette.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }

""" + IDLE_CARD)

io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("RelayPolicyTab.kt extended OK")
