package com.servermaintance.panel.pages.relay

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

private val WIN_LABEL = linkedMapOf(
    "rolling" to "滚动窗口",
    "weekly" to "本周",
    "monthly" to "本月",
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
        EmptyState(text = "无供应商数据", icon = "key")
        return
    }

    // 「当前密钥」信息行（GET /relay/key：{key_masked, configured, updated_at}）
    if (feed.has("key")) {
        val k = feed["key"]
        Muted(
            "当前密钥 ${k.str("key_masked").ifEmpty { "-" }} · " +
                (if (k.bool("configured")) "已配置" else "未配置") +
                " · 更新于 ${rTmTail(k.str("updated_at")).ifEmpty { "-" }}",
            Modifier.padding(bottom = 10.dp),
        )
    } else {
        val keyErr = feed.err("key")
        if (keyErr != null) Muted("当前密钥：暂不可用", Modifier.padding(bottom = 10.dp))
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
        cardKey = "rl-prov-$pid",
        right = {
            LevelBadge(
                text = if (exhausted) "配额耗尽" else "可用",
                level = if (exhausted) Level.Bad else Level.Ok,
            )
            Spacer(Modifier.width(6.dp))
            Chip(text = "密钥 ${rNum(p.num("key_count"))} · 可用 ${rNum(p.num("usable"))}")
            Spacer(Modifier.width(6.dp))
            Chip(text = "窗口 ${p.arr("windows").joinToString("/") { jstr(it) }}")
        },
    ) {
        val err = feed.err("keys")
        when {
            err != null && !feed.has("keys") -> Muted(err)
            !feed.has("keys") -> Muted("加载中…")
            else -> {
                val kd = feed["keys"]
                val keys = kd.arr("keys").filterIsInstance<JsonObject>()
                val wins = kd.arr("windows").map { jstr(it) }
                    .ifEmpty { listOf("rolling", "weekly", "monthly") }

                if (keys.isEmpty()) {
                    Muted("暂无密钥")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        keys.forEach { k -> KeyRow(k = k, pid = pid, wins = wins, reload = reload) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryBtn(
                        text = "添加密钥",
                        icon = "plus",
                        onClick = { scope.launch { addKey(pid, reload) } },
                    )
                    GhostBtn(
                        text = "刷新全部配额",
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
                    Muted("最近切换记录", Modifier.padding(bottom = 6.dp))
                    relayTable(
                        rows = log,
                        cols = listOf(
                            RCol("at", "时间", fmt = { rTm(it) }),
                            RCol("to_masked", "切换到", mono = true),
                            RCol(
                                "ok", "结果",
                                fmt = { if (jbool(it)) "成功" else "失败" },
                                tone = { if (jbool(it)) Level.Ok else Level.Bad },
                            ),
                            RCol("detail", "详情", width = 1.6f),
                        ),
                        emptyText = "暂无切换记录",
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
            if (active) LevelBadge("当前使用", Level.Info)
            if (enabled) LevelBadge("启用", Level.Ok) else LevelBadge("禁用", Level.Muted)
            if (exhausted.isNotEmpty()) LevelBadge("${exhausted.joinToString("/")} 耗尽", Level.Warn)
            if (recoverAt.isNotEmpty()) Chip(text = "恢复于 ${rTmRecover(recoverAt)}")
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
            GhostBtn("切换使用", onClick = {
                scope.launch { relayAction("keys/activate", keyPayload(k, pid), reload = reload) }
            })
            GhostBtn("刷新配额", onClick = {
                scope.launch { relayAction("keys/refresh", keyPayload(k, pid), reload = reload) }
            })
            GhostBtn("测试", onClick = {
                scope.launch { relayAction("keys/test", keyPayload(k, pid), reload = reload) }
            })
            GhostBtn(if (enabled) "禁用" else "启用", onClick = {
                scope.launch {
                    relayAction("keys/enable", keyPayload(k, pid, enabled = !enabled), reload = reload)
                }
            })
            DangerBtn("删除", onClick = {
                scope.launch {
                    relayAction(
                        "keys/remove",
                        keyPayload(k, pid),
                        confirmFirst = true,
                        danger = true,
                        confirmTitle = "删除密钥",
                        confirmMsg = "删除后无法恢复，确认删除该密钥？",
                        confirmText = "删除",
                        reload = reload,
                    )
                }
            })
        }

        Muted("添加于 ${rTmShort(k.str("added_at"))} · 配额查询于 ${rTmShort(k.str("usage_at").ifEmpty { "-" })}")
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
        title = "添加密钥 - $provId",
        fields = listOf(
            FormField.Text("key", "密钥", hint = "oc_sk_... / glm-...", mono = true),
            FormField.Text("label", "备注", hint = "可选"),
        ),
        okText = "添加",
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
    AppState.toast(msg ?: "已添加", ToastKind.Ok)
    reload()
}
