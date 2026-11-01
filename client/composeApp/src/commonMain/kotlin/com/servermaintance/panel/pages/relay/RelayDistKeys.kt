package com.servermaintance.panel.pages.relay
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.trf

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.has
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.platformClipboard
import com.servermaintance.panel.ui.AdaptiveRow
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PrimaryBtn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* =========================================================================
 * 分发 Key（relay.js `tabDistKeys`）—— 发给客户端用的内部 key
 *
 * FETCH: GET /relay/client-keys
 * 动作: client-keys/create | update | enable | delete | apply | reveal
 *
 * 与网页面板同一套口径：客户端只持有这里的 key，官方 key 由中继在请求途中替换；
 * 时/周/月上限按 key 计 token，数据就是 tap 自己写的用量表，所以"用了多少"和
 * "拦不拦"是同一个来源。列表只回掩码，明文只在生成那一次返回，错过就用
 * 「复制完整 key」再取一次（那次读取会进面板审计）。
 * ========================================================================= */

private val DK_PATHS = mapOf("client-keys" to "/relay/client-keys")
private val DK_WINS = listOf("hour", "week", "month")

/** 生成/编辑弹窗里的默认上限：只是起点，不是策略。 */
private val DK_DEFAULTS = mapOf("hour" to "20000000", "week" to "200000000", "month" to "600000000")

private fun dkWinLabel(w: String): String = when (w) {
    "hour" -> L["近_1_小时_9b122efc5b"]
    "week" -> L["本周_e16e8248b1"]
    else -> L["本月_389f0007de"]
}

private fun dkWinHint(w: String): String = when (w) {
    "hour" -> L["滚动窗口_f4ad4453d0"]
    "week" -> L["周一_00_00_重置_89224791ae"]
    else -> L["1_号_00_00_重置_196f175fce"]
}

/** 面板输入是文本框，这里按网页版 dkParse 的规矩解析：去千分位、非数字或负数当 0。 */
private fun dkParse(s: String?): Long {
    val v = s.orEmpty().replace(",", "").trim().toLongOrNull() ?: 0L
    return if (v < 0) 0L else v
}

private fun dkQuotaJson(pairs: Map<String, Long>) =
    jsonObjectOf("hour" to pairs["hour"], "week" to pairs["week"], "month" to pairs["month"])

@Composable
fun RelayDistKeysTab() {
    val feed = rememberRelayFeed(DK_PATHS, intervalMs = 10_000L)
    val scope = rememberCoroutineScope()
    val reload: suspend () -> Unit = { feed.reload() }

    if (feed.allFailed) { FeedError(feed); return }

    val data = feed["client-keys"]
    val keys = data.arr("keys").filterIsInstance<JsonObject>()
    val totals = data.obj("totals")

    if (keys.isEmpty() && feed.err("client-keys") != null) {
        Muted(feed.err("client-keys") ?: L["加载失败_f6b7a41e56"])
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StatRow(
            listOf(
                StatItem(L["启用中_595aabc68f"], "${keys.count { it.bool("enabled") }} / ${keys.size}", L["客户端持有的内部_key_c8043eaa1f"]),
            ) + DK_WINS.map { w ->
                val win = totals?.obj(w)
                val limit = win?.num("limit") ?: 0.0
                val wl = dkWinLabel(w)
                val cap = rNum(limit)
                StatItem(
                    trf("0_已用_c15a4c7e8f", wl),
                    rNum(win?.num("used") ?: 0.0),
                    if (limit > 0) trf("上限合计_0_d742f7d573", cap) else L["均未设上限_f7e69e026d"],
                )
            },
        )

        RelaySection(
            title = L["分发_key_4091c997cc"],
            right = {
                GhostBtn(L["重新下发名单_39b14c64ed"], icon = "refresh", onClick = {
                    scope.launch { dkAct("client-keys/apply", jsonObjectOf(), reload) }
                })
                Spacer(Modifier.width(6.dp))
                PrimaryBtn(L["新建_key_400f18c17f"], icon = "plus", onClick = {
                    scope.launch { dkCreate(reload) }
                })
            },
        ) {
            Muted(
                L["客户端把_opencode_relay_key_0acb717ce6"] +
                    L["额度口径_时_滚动_1_小时_周_本自然周_周一_5de43300e0"],
                Modifier.padding(bottom = 6.dp),
            )
            val store = data.str("store").ifEmpty { "-" }
            val synced = rTmTail(data.str("updated_at")).ifEmpty { "-" }
            Muted(
                trf("名单文件_0_仓库更新_1_ef672c7b9e", store, synced),
                Modifier.padding(bottom = 10.dp),
            )
            if (keys.isEmpty()) {
                EmptyState(L["还没有分发_key_点右上_新建_key_生成第_77d0c5b4d8"], icon = "key")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEach { k -> DistKeyRow(k, reload) }
                }
            }
        }
    }
}

/** 一枚分发 key：状态行 + 三档额度条 + 操作按钮 + 创建时间。 */
@Composable
private fun DistKeyRow(k: JsonObject, reload: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = k.str("id")
    val internal = k.bool("internal")
    val enabled = k.bool("enabled")
    val label = k.str("label")
    val note = k.str("note")
    val over = DK_WINS.filter { k.obj("windows")?.obj(it)?.bool("over") ?: false }

    Column(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        /* ---- 状态行：窄屏时整行横向滚动，不挤压徽章 ---- */
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Mono(k.str("masked").ifEmpty { id }, color = Palette.text)
            if (label.isNotEmpty()) {
                Text(label, color = Palette.text, style = MaterialTheme.typography.bodySmall)
            }
            if (enabled) LevelBadge(L["启用_d4e9ca3dd4"], Level.Ok) else LevelBadge(L["已停用_6c7dcbb73a"], Level.Muted)
            if (internal) LevelBadge(L["主令牌_不受额度限制_abe31062f1"], Level.Info)
            if (over.isNotEmpty()) {
                LevelBadge(over.joinToString("/") { dkWinLabel(it) } + L["超额_c705a17379"], Level.Bad)
            }
            Chip(id)
        }
        if (note.isNotEmpty()) Muted(note)

        /* ---- 三档额度（手持端自动叠成三行） ---- */
        AdaptiveRow(spacing = 10.dp) {
            DK_WINS.forEach { w -> DkWinCell(k, w, Modifier.span(1f)) }
        }

        /* ---- 操作：主令牌只是兜底入口，不给改 ---- */
        if (!internal) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GhostBtn(L["复制完整_key_42a69c90f0"], icon = "copy", onClick = {
                    scope.launch { dkCopy(id) }
                })
                GhostBtn(L["改额度_标签_0eafa5e321"], onClick = {
                    scope.launch { dkEdit(k, reload) }
                })
                GhostBtn(if (enabled) L["停用_d989e55188"] else L["启用_d4e9ca3dd4"], onClick = {
                    scope.launch {
                        dkAct("client-keys/enable", jsonObjectOf("id" to id, "enabled" to !enabled), reload)
                    }
                })
                DangerBtn(L["删除_3755f56f2f"], onClick = {
                    scope.launch {
                        dkAct(
                            "client-keys/delete",
                            jsonObjectOf("id" to id),
                            reload,
                            confirmTitle = L["删除_key_a04bdb8bfc"],
                            confirmMsg = L["删除后这枚_key_立刻不可用_且无法恢复_历史_9909d5cca4"],
                            confirmText = L["删除_3755f56f2f"],
                        )
                    }
                })
            }
        }
        val created = rTmFull(k.str("created_at")).ifEmpty { "-" }
        Muted(trf("创建于_0_be5695d8de", created))
    }
}

/** 一档额度：上限为 0 时不画条，只写"不限"。 */
@Composable
private fun DkWinCell(k: JsonObject, w: String, modifier: Modifier = Modifier) {
    val win = k.obj("windows")?.obj(w)
    val limit = win?.num("limit") ?: 0.0
    val used = win?.num("used") ?: 0.0
    val over = win?.bool("over") ?: false
    Column(modifier) {
        Text(dkWinLabel(w), color = Palette.muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(3.dp))
        if (limit <= 0) {
            ColorBar(pct = 0.0, color = Palette.border, thresholds = false)
            Spacer(Modifier.height(2.dp))
            Mono(L["不限_09c4fc252c"], color = Palette.muted)
        } else {
            ColorBar(pct = (used / limit * 100.0).coerceIn(0.0, 100.0))
            Spacer(Modifier.height(2.dp))
            Mono(
                "${rNum(used)} / ${rNum(limit)}" + if (over) L["已超_dd70e43d13"] else "",
                color = if (over) Palette.bad else Palette.muted,
            )
        }
    }
}

/* ------------------------------------------------------------------ *
 *  动作
 * ------------------------------------------------------------------ */

/** 取回明文并放进剪贴板（网页版同一个动作，读取会进面板审计）。 */
private suspend fun dkCopy(id: String) {
    val out = Actions.run(
        path = "/relay/action",
        body = jsonObjectOf("name" to "client-keys/reveal", "payload" to jsonObjectOf("id" to id)),
        successToast = null,
    ) as? JsonObject ?: return
    val secret = out.str("key")
    if (secret.isEmpty()) {
        AppState.toast(L["响应里没有_key_字段_681bf7ccf4"], ToastKind.Err)
        return
    }
    runCatching { platformClipboard.copy(secret) }
        .onSuccess { AppState.toast(L["完整_key_已复制到剪贴板_f176e80f0a"], ToastKind.Ok) }
        .onFailure { AppState.toast(L["剪贴板不可用_请改用_新建_key_重新生成一枚_c131d85c1f"], ToastKind.Err) }
}

private fun dkFields(current: JsonObject? = null): List<FormField> {
    val q = current?.obj("quota")
    return listOf(
        FormField.Text("label", L["用途_使用人_be12fc261a"],
            value = current?.str("label").orEmpty(), hint = L["例_张三的笔记本_34dfb03e78"]),
    ) + DK_WINS.map { w ->
        val wl = dkWinLabel(w)
        val hint = dkWinHint(w)
        FormField.Text(
            w,
            trf("0_上限_token_0_不限_1_d6d70b79f7", wl, hint),
            value = ((q?.num(w) ?: 0.0).toLong().takeIf { it > 0 } ?: DK_DEFAULTS[w]!!.toLong()).toString(),
            mono = true,
        )
    } + listOf(
        FormField.Text("note", L["备注_e0361480e3"], value = current?.str("note").orEmpty(), hint = L["可选_53e32830a5"]),
    )
}

private suspend fun dkCreate(reload: suspend () -> Unit) {
    val r = Modal.prompt(L["新建分发_key_给谁用_ae95947862"], dkFields(), okText = L["生成_4aa2306395"]) ?: return
    if (r["label"].isNullOrBlank()) {
        AppState.toast(L["请填写用途标签_e4b1af7c48"], ToastKind.Err)
        return
    }
    val out = Actions.run(
        path = "/relay/action",
        body = jsonObjectOf(
            "name" to "client-keys/create",
            "payload" to jsonObjectOf(
                "label" to r["label"].orEmpty(),
                "note" to r["note"].orEmpty(),
                "quota" to dkQuotaJson(DK_WINS.associateWith { dkParse(r[it]) }),
            ),
        ),
        successToast = null,
    ) as? JsonObject ?: return

    val secret = out.str("key")
    if (secret.isNotEmpty()) Modal.output(L["这枚_key_只显示这一次_请立刻复制保存_befb8d209d"], secret)
    dkReport(out, L["已生成_79c74f41ca"])
    reload()
}

private suspend fun dkEdit(k: JsonObject, reload: suspend () -> Unit) {
    val id = k.str("id")
    val who = k.str("label").ifEmpty { id }
    val r = Modal.prompt(
        trf("调整额度_0_d4b75b12f5", who),
        dkFields(k),
        okText = L["保存_fadf24dbc5"],
    ) ?: return
    val out = Actions.run(
        path = "/relay/action",
        body = jsonObjectOf(
            "name" to "client-keys/update",
            "payload" to jsonObjectOf(
                "id" to id,
                "label" to r["label"].orEmpty(),
                "note" to r["note"].orEmpty(),
                "quota" to dkQuotaJson(DK_WINS.associateWith { dkParse(r[it]) }),
            ),
        ),
        successToast = null,
    ) as? JsonObject ?: return
    dkReport(out, L["已保存_cdfab96f75"])
    reload()
}

/**
 * 启停 / 删除 / 重新下发名单都走这里（网页版 `dkAct`）。通用的 `relayAction` 一律按
 * "成功"提示，但这三个动作都可能遇到"仓库写好了、nginx 名单没下发"——那时 key 还进不了
 * 中继，报成成功会把人带偏，所以复用 [dkReport] 按 `applied` 决定提示等级。
 */
private suspend fun dkAct(
    name: String,
    payload: JsonObject,
    reload: suspend () -> Unit,
    confirmTitle: String? = null,
    confirmMsg: String? = null,
    confirmText: String? = null,
) {
    if (confirmTitle != null) {
        val ok = Modal.confirm(
            title = confirmTitle,
            text = confirmMsg.orEmpty(),
            okText = confirmText.orEmpty(),
            danger = true,
        )
        if (!ok) return
    }
    val out = Actions.run(
        path = "/relay/action",
        body = jsonObjectOf("name" to name, "payload" to payload),
        successToast = null,
    ) as? JsonObject ?: return
    dkReport(out, L["操作完成_66c6340147"])
    reload()
}

/**
 * 服务端把"仓库已写入"和"nginx 名单已下发"分开报：`applied:false` 时 key 其实还
 * 进不了中继（边缘名单没它），所以这里不能只 toast 一个"成功"。
 */
private fun dkReport(out: JsonObject, fallback: String) {
    val detail = out.str("detail").ifEmpty { fallback }
    if (out.has("applied") && !out.bool("applied")) {
        AppState.toast(out.str("warning").ifEmpty { detail }, ToastKind.Warn)
    } else {
        AppState.toast(detail, ToastKind.Ok)
    }
}
