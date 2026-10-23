package com.servermaintance.panel.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * 系统服务 —— 复刻 upstream/public/js/pages/services.js（85 行）
 * 端点：GET /services → {ts, list:[{unit,load,active,sub,description,enabled}],
 *                        counts:{running,failed,total}}
 *       POST /service {unit, action}（start/stop/restart/enable/disable）
 * ==================================================================== */

private class Svc(
    val unit: String,
    val load: String,
    val active: String,
    val sub: String,
    val description: String,
    val enabled: String,
)

/** JS `ui.svcBadge(active, sub)`（upstream/public/js/ui.js）。 */
@Composable
private fun svcBadge(active: String, sub: String) {
    val text: String
    val level: Level
    when {
        active == "active" -> { text = sub.ifEmpty { "active" }; level = Level.Ok }
        active == "failed" -> { text = "failed"; level = Level.Bad }
        active == "activating" || active == "deactivating" -> { text = active; level = Level.Warn }
        else -> { text = active.ifEmpty { "-" }; level = Level.Muted }
    }
    LevelBadge(text, level)
}

/** JS `CM.refreshLabel(ms)`（core.js：>=1000 显示秒，否则毫秒）。
 *  注意：不叫 refreshLabel —— pages 包内 ProcessesPage 已有同名 internal 声明，重命名避冲突。 */
private fun svcRefreshLabel(ms: Long): String =
    if (ms >= 1000) {
        val s = ms / 1000.0
        val t = (s * 100).toLong() / 100.0 // toFixed(2) 截断等价
        (if (t % 1.0 == 0.0) t.toLong().toString() else t.toString()) + " 秒"
    } else "$ms 毫秒"

@Composable
fun ServicesPage() {
    var services by remember { mutableStateOf<List<Svc>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var stateLabel by remember { mutableStateOf("全部状态") }
    var autoOn by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val stateFilters = listOf("全部状态", "仅运行", "仅失败", "仅停止")
    val stateValue = mapOf("仅运行" to "active", "仅失败" to "failed", "仅停止" to "inactive")

    suspend fun load() {
        try {
            val d = AppState.api.get("/services", cacheMs = 2000)
            services = d.arr("list").filterIsInstance<JsonObject>().map { o ->
                Svc(
                    unit = o.str("unit"),
                    load = o.str("load"),
                    active = o.str("active"),
                    sub = o.str("sub"),
                    description = o.str("description"),
                    enabled = o.str("enabled"),
                )
            }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e::class.simpleName ?: "请求失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }
    AutoRefresh(key = "services", enabled = autoOn, pauseWhileEditing = false) { load() }

    /** JS `runAction('/service', {unit, action}, {reload: load})`。 */
    suspend fun svcAction(unit: String, act: String) {
        val okc = Modal.confirm(
            title = "$act 服务",
            text = "对 $unit 执行 systemctl $act ？",
            danger = act == "stop",
        )
        if (!okc) return
        try {
            Actions.run(
                path = "/service",
                body = jsonObjectOf("unit" to unit, "action" to act),
                successToast = "操作成功",
            )
        } catch (_: Throwable) {
            /* runAction 失败也会 toast，这里仅确保随后刷新 */
        }
        load()
    }

    val q = query.trim().lowercase()
    val filtered = remember(services, q, stateLabel) {
        val sv = stateValue[stateLabel]
        services
            .filter { s -> q.isEmpty() || (s.unit + " " + s.description).lowercase().contains(q) }
            .filter { s -> sv == null || s.active == sv }
    }
    // JS: list.slice(0, 400)
    val shown = filtered.take(400)

    PageScaffold(title = "系统服务") {
        PanelCard(
            title = "systemd 服务",
            right = {
                GhostBtn(text = "刷新", icon = "refresh", onClick = {
                    scope.launch { loading = true; load() }
                })
            },
        ) {
            /* —— 计数徽章 + 搜索 + 状态过滤 + 自动刷新（JS card-title .right） —— */
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val running = services.count { it.active == "active" }
                val failed = services.count { it.active == "failed" }
                LevelBadge("运行 $running", Level.Ok)
                if (failed > 0) LevelBadge("失败 $failed", Level.Bad)
                LevelBadge("共 ${services.size}", Level.Info)
                Spacer(Modifier.width(4.dp))
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.width(200.dp),
                    placeholder = "搜索服务名…",
                )
                SelectField(
                    value = stateLabel,
                    options = stateFilters,
                    onValueChange = { stateLabel = it },
                )
                CheckRow(checked = autoOn, onChange = { autoOn = it }, label = "自动刷新")
                Text(
                    svcRefreshLabel(AppState.meRefreshMs()),
                    color = Palette.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading && services.isEmpty() && error == null -> LoadingState()
                error != null && services.isEmpty() -> ErrorState(message = error!!, onRetry = {
                    scope.launch { loading = true; load() }
                })
                else -> {
                    /* —— 服务表（JS ui.table，列定义照抄） —— */
                    DataTable(
                        columns = listOf(
                            TableCol<Svc>("单元", weight = 2f, mono = true) { s ->
                                Text(s.unit, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<Svc>("状态", weight = 1.2f) { s -> svcBadge(s.active, s.sub) },
                            TableCol<Svc>("开机启动", weight = 1.1f) { s ->
                                when (s.enabled) {
                                    "enabled" -> LevelBadge("enabled", Level.Ok)
                                    "static" -> LevelBadge("static", Level.Muted)
                                    else -> LevelBadge(s.enabled.ifEmpty { "-" }, Level.Warn)
                                }
                            },
                            TableCol<Svc>("描述", weight = 2.4f) { s ->
                                Text(s.description.take(70), fontSize = 12.sp, color = Palette.muted)
                            },
                            TableCol<Svc>("操作", weight = 2.6f, minWidth = 290.dp) { s ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    GhostBtn(text = "启动", icon = "play", onClick = {
                                        scope.launch { svcAction(s.unit, "start") }
                                    })
                                    GhostBtn(text = "重启", icon = "restart", onClick = {
                                        scope.launch { svcAction(s.unit, "restart") }
                                    })
                                    DangerBtn(text = "停止", icon = "stop", onClick = {
                                        scope.launch { svcAction(s.unit, "stop") }
                                    })
                                    // JS: 已 enabled 的显示「关自启」(disable)，否则「开自启」(enable)
                                    if (s.enabled == "enabled") {
                                        GhostBtn(text = "关自启", icon = "power", onClick = {
                                            scope.launch { svcAction(s.unit, "disable") }
                                        })
                                    } else {
                                        GhostBtn(text = "开自启", icon = "power", onClick = {
                                            scope.launch { svcAction(s.unit, "enable") }
                                        })
                                    }
                                }
                            },
                        ),
                        rows = shown,
                        rowKey = { it.unit },
                        emptyText = "没有匹配的服务",
                    )
                }
            }
        }
    }
}
