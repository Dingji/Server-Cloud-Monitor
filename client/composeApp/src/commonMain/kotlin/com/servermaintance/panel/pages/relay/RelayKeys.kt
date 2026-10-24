package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import kotlinx.serialization.json.JsonObject
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PctBar
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PrimaryBtn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/* =========================================================================
 * 密钥池（relay.js `tabKeys`）
 * FETCH: providers → 逐供应商 GET /relay/keys?provider=<id>
 * 动作: keys/add | keys/activate | keys/refresh | keys/test | keys/enable | keys/remove
 * ========================================================================= */

private val KEYS_PATHS = mapOf(
    "providers" to "/relay/providers",
    // lead 裁定保留 /relay/key：面板 API 的附带能力，作为「当前密钥」信息行
    "key" to "/relay/key",
)

private val WIN_LABEL  get() = linkedMapOf(
    "rolling" to L["滚动窗口_f4ad4453d0"],
    "weekly" to L["本周_e16e8248b1"],
    "monthly" to L["本月_389f0007de"],
)

/** 保守的查询串编码（provider id 多为安全字符，但不假设）。 */
private fun enc(s: String): String {
    val hex = "0123456789ABCDEF"
    return buildString {
        for (c in s) {
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~') {
                append(c)
            } else {
                for (b in c.toString().encodeToByteArray()) {
                    val v = b.toInt() and 0xFF
                    append('%').append(hex[v shr 4]).append(hex[v and 0xF])
                }
            }
        }
    }
}

@Composable
fun RelayKeysTab() {
    val feed = rememberRelayFeed(KEYS_PATHS, intervalMs = 10_000L)

    if (feed.allFailed) { FeedError(feed); return }

    val provs = feed["providers"].arr("providers").filterIsInstance<JsonObject>()
    if (provs.isEmpty()) {
        EmptyState(text = L["无供应商数据_079694fa08"], icon = "key")
        return
    }

    // 「当前密钥」信息行（GET /relay/key：{key_masked, configured, updated_at}）
    if (feed.has("key")) {
        val k = feed["key"]
        Muted(
            trf("当前密钥_0_a6dc721288", k.str("key_masked").ifEmpty { "-" }) +
                (if (k.bool("configured")) L["已配置_dc7cd22f34"] else L["未配置_63595e95b7"]) +
                trf("更新于_0_7340e34cda", rTmTail(k.str("updated_at")).ifEmpty { "-" }),
            Modifier.padding(bottom = 10.dp),
        )
    } else {
        val keyErr = feed.err("key")
        if (keyErr != null) Muted(L["当前密钥_暂不可用_698a7394eb"], Modifier.padding(bottom = 10.dp))
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        provs.forEach { p -> ProviderKeysCard(p) }
    }
}

/** 单个供应商卡片：标题行 + 密钥列表 + 操作按钮 + 切换记录。 */
@Composable
private fun ProviderKeysCard(p: JsonObject) {
    val pid = p.str("id")
    val label = p.str("label").ifEmpty { pid }
    val exhausted = p.bool("exhausted")

    // provider 变化时 map 内容变化 → rememberRelayFeed 会重建 holder 并重拉
    val paths = mapOf("keys" to "/relay/keys?provider=${enc(pid)}")
    val feed = rememberRelayFeed(paths, intervalMs = 10_000L)
    val scope = rememberCoroutineScope()
    val reload: suspend () -> Unit = { feed.reload() }

    PanelCard(
        title = label,
        cardKey = L["rl_prov_pid_e3748ee319"],
        right = {
            LevelBadge(
                text = if (exhausted) L["配额耗尽_9f8e15153c"] else L["可用_e91365cf9e"],
                level = if (exhausted) Level.Bad else Level.Ok,
            )
            Spacer(Modifier.width(6.dp))
            Chip(text = trf("密钥_0_可用_1_426c7d2a6e", rNum(p.num("key_count")), rNum(p.num("usable"))))
            Spacer(Modifier.width(6.dp))
            Chip(text = trf("窗口_0_66b23aab3b", p.arr("windows").joinToString("/") { jstr(it) }))
        },
    ) {
        val err = feed.err("keys")
        when {
            err != null && !feed.has("keys") -> Muted(err)
            !feed.has("keys") -> Muted(L["common.loading"])
            else -> {
                val kd = feed["keys"]
                val keys = kd.arr("keys").filterIsInstance<JsonObject>()
                val wins = kd.arr("windows").map { jstr(it) }
                    .ifEmpty { listOf("rolling", "weekly", "monthly") }

                if (keys.isEmpty()) {
                    Muted(L["暂无密钥_0e15befa13"])
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        keys.forEach { k -> KeyRow(k = k, pid = pid, wins = wins, reload = reload) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryBtn(
                        text = L["添加密钥_0f17b63bac"],
                        icon = "plus",
                        onClick = { scope.launch { addKey(pid, reload) } },
                    )
                    GhostBtn(
                        text = L["刷新全部配额_076a4d3565"],
                        icon = "refresh",
                        onClick = {
                            scope.launch {
                                relayAction("keys/refresh", jsonObjectOf("provider" to pid), reload = reload)
                            }
                        },
                    )
                }

                val log = kd.arr("switch_log").filterIsInstance<JsonObject>().takeLast(5).reversed()
                if (log.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Muted(L["最近切换记录_4e33862ed5"], Modifier.padding(bottom = 6.dp))
                    relayTable(
                        rows = log,
                        cols = listOf(
                            RCol("at", L["时间_89b4aa6364"], fmt = { rTm(it) }),
                            RCol("to_masked", L["切换到_dcfa6ff76a"], mono = true),
                            RCol(
                                "ok", L["结果_0a2c91cec6"],
                                fmt = { if (jbool(it)) L["成功_51991a5d11"] else L["失败_3e3c8068bb"] },
                                tone = { if (jbool(it)) Level.Ok else Level.Bad },
                            ),
                            RCol("detail", L["详情_4f55ee1e68"], width = 1.6f),
                        ),
                        emptyText = L["暂无切换记录_cb6e9b8316"],
                    )
                }
            }
        }
    }
}

/** 单把密钥：状态行 + 三档配额条 + 5 个操作按钮 + 添加/查询时间。 */
@Composable
private fun KeyRow(
    k: JsonObject,
    pid: String,
    wins: List<String>,
    reload: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val masked = k.str("masked").ifEmpty { k.str("id") }
    val enabled = k.bool("enabled")
    val active = k.bool("active")
    val exhausted = k.arr("exhausted_windows").map { jstr(it) }
    // relay.js 读 k.recover_at，但真实 /relay/keys 响应里字段名是 recovers_at —— 两个都读
    val recoverAt = k.str("recover_at").ifEmpty { k.str("recovers_at") }
    val usage = k.key("usage") as? JsonObject

    Column(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        /* ---- 头部状态行 ---- */
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Mono(masked, color = Palette.text, modifier = Modifier.weight(1f, fill = false))
            if (active) LevelBadge(L["当前使用_297318c276"], Level.Info)
            if (enabled) LevelBadge(L["启用_d4e9ca3dd4"], Level.Ok) else LevelBadge(L["禁用_be70be5a2e"], Level.Muted)
            if (exhausted.isNotEmpty()) LevelBadge(trf("0_耗尽_47f859e844", exhausted.joinToString("/")), Level.Warn)
            if (recoverAt.isNotEmpty()) Chip(text = trf("恢复于_0_57aae0c315", rTmRecover(recoverAt)))
        }

        /* ---- 三档配额（契约控件 PctBar；阈值差 70 vs 75 已知并接受） ---- */
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            wins.forEach { w ->
                val pct = usage?.obj(w)?.num("percent")
                Column(Modifier.weight(1f)) {
                    Text(
                        WIN_LABEL[w] ?: w,
                        color = Palette.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(3.dp))
                    PctBar(pct = pct ?: 0.0, showLabel = false)
                    Spacer(Modifier.height(2.dp))
                    Mono("${rPlain(pct ?: 0.0)}%", color = Palette.muted)
                }
            }
        }

        /* ---- 5 个操作按钮（对应 relay.js data-act） ---- */
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            GhostBtn(L["切换使用_440dcd4a60"], onClick = {
                scope.launch { relayAction("keys/activate", keyPayload(k, pid), reload = reload) }
            })
            GhostBtn(L["刷新配额_851859899a"], onClick = {
                scope.launch { relayAction("keys/refresh", keyPayload(k, pid), reload = reload) }
            })
            GhostBtn(L["测试_0b5d7ed54b"], onClick = {
                scope.launch { relayAction("keys/test", keyPayload(k, pid), reload = reload) }
            })
            GhostBtn(if (enabled) L["禁用_be70be5a2e"] else L["启用_d4e9ca3dd4"], onClick = {
                scope.launch {
                    relayAction("keys/enable", keyPayload(k, pid, enabled = !enabled), reload = reload)
                }
            })
            DangerBtn(L["删除_3755f56f2f"], onClick = {
                scope.launch {
                    relayAction(
                        "keys/remove",
                        keyPayload(k, pid),
                        confirmFirst = true,
                        danger = true,
                        confirmTitle = L["删除密钥_8017614a47"],
                        confirmMsg = L["删除后无法恢复_确认删除该密钥_68e3c91b1d"],
                        confirmText = L["删除_3755f56f2f"],
                        reload = reload,
                    )
                }
            })
        }

        Muted(trf("添加于_0_配额查询于_1_1c991e5cd8", rTmShort(k.str("added_at")), rTmShort(k.str("usage_at").ifEmpty { "-" })))
    }
}

private fun keyPayload(k: JsonObject, pid: String, enabled: Boolean? = null): JsonObject {
    val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
        "id" to kotlinx.serialization.json.JsonPrimitive(k.str("id")),
        "provider" to kotlinx.serialization.json.JsonPrimitive(pid),
    )
    if (enabled != null) map["enabled"] = JsonPrimitive(enabled)
    return JsonObject(map)
}

/** JS `addKey(provId)`：Modal.prompt 两个字段 → POST /relay/action keys/add */
private suspend fun addKey(provId: String, reload: suspend () -> Unit) {
    val r = Modal.prompt(
        title = L["添加密钥_provid_0f9d502f28"],
        fields = listOf(
            FormField.Text("key", L["密钥_0d1965e139"], hint = L["oc_sk_glm_bf58d4a93b"], mono = true),
            FormField.Text("label", L["备注_e0361480e3"], hint = L["可选_53e32830a5"]),
        ),
        okText = L["添加_94191ce210"],
    ) ?: return
    val keyVal = r["key"].orEmpty()
    if (keyVal.isEmpty()) return

    val out = Actions.run(
        path = "/relay/action",
        body = jsonObjectOf(
            "name" to "keys/add",
            "payload" to jsonObjectOf(
                "key" to keyVal,
                "label" to (r["label"].orEmpty()),
                "provider" to provId,
            ),
        ),
        successToast = null,
    ) ?: return

    val msg = (out as? JsonObject)?.let { o ->
        listOf("detail", "msg").firstNotNullOfOrNull { f ->
            (o.key(f) as? JsonPrimitive)?.takeIf { s -> s.content.isNotBlank() }?.content
        }
    }
    AppState.toast(msg ?: tr("已添加_57828673c0"), ToastKind.Ok)
    reload()
}
