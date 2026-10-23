package com.servermaintance.panel.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * 网站 / Nginx —— 复刻 upstream/public/js/pages/web.js（118 行）
 * 端点：GET  /web → {nginx:{installed,version,active,testOk,testOutput,
 *                          sites:[{name,path,blocks:[{listen[],serverName,
 *                             root,proxy,ssl}]}]},
 *                    apache:{installed,active,info},
 *                    certs:[{domain,expiry,daysLeft}], certbotInstalled}
 *       POST /web/action {action, params}
 *              action: nginx-test/nginx-reload/nginx-stop/nginx-start/
 *                      site-toggle{site,enable}/cert-renew
 *       POST /pkg/install {pkg:"certbot"}（certbot 未安装时）
 * ==================================================================== */

/** 次要说明文本（JS `<div class="muted small">`）。 */
@Composable
private fun Muted(text: String) {
    Text(text, color = Palette.muted, fontSize = 12.sp)
}

/** 站点 × server 块展开后的表格行（JS renderSites 的 rows.push({site,b,i})）。 */
private class SiteRow(
    val site: String,
    val serverName: String,
    val listen: String,
    val rootProxy: String,
    val ssl: Boolean,
    val first: Boolean,
)

@Composable
fun WebPage() {
    var data by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            data = AppState.api.get("/web", cacheMs = 2000)
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

    /** JS `bind()`：runAction('/web/action', {action, params}, {reload, reloadDelay:1200})。 */
    suspend fun webAction(action: String, params: JsonObject = jsonObjectOf(), danger: Boolean = false) {
        if (danger) {
            val okc = Modal.confirm(title = "确认操作", text = "执行 $action ？", danger = true)
            if (!okc) return
        }
        try {
            Actions.run(
                path = "/web/action",
                body = jsonObjectOf("action" to action, "params" to params),
                successToast = "操作成功",
                reload = { delay(1200); load() },
            )
        } catch (_: Throwable) {
            delay(1200)
            load()
        }
    }

    PageScaffold(title = "网站 / Nginx") {
        when {
            loading && data == null && error == null -> LoadingState()
            error != null && data == null -> ErrorState(message = error!!, onRetry = {
                scope.launch { loading = true; load() }
            })
            else -> {
                val d = data ?: JsonObject(emptyMap())
                val n = d.obj("nginx") ?: JsonObject(emptyMap())
                val apache = d.obj("apache") ?: JsonObject(emptyMap())
                val certs = d.arr("certs").filterIsInstance<JsonObject>()
                val certbotInstalled = d.bool("certbotInstalled")

                val nInstalled = n.bool("installed")
                val nActive = n.bool("active")
                val nTestOk = n.bool("testOk")

                /* ---------------- 三张状态卡（JS grid g3） ---------------- */
                GridLayout(columns = 3) {
                    /* —— Nginx —— */
                    PanelCard(title = "Nginx", collapsible = false) {
                        Text(
                            text = when {
                                !nInstalled -> "未安装"
                                nActive -> "运行中"
                                else -> "已停止"
                            },
                            color = when {
                                !nInstalled -> Palette.muted
                                nActive -> Palette.ok
                                else -> Palette.bad
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (n.str("version").isNotEmpty()) {
                            Muted(n.str("version"))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GhostBtn(text = "配置检测", icon = "check", onClick = {
                                scope.launch { webAction("nginx-test") }
                            })
                            if (nInstalled) {
                                GhostBtn(text = "重载", icon = "restart", onClick = {
                                    scope.launch { webAction("nginx-reload") }
                                })
                            }
                            if (nActive) {
                                DangerBtn(text = "停止", icon = "stop", onClick = {
                                    scope.launch { webAction("nginx-stop", danger = true) }
                                })
                            } else {
                                GhostBtn(text = "启动", icon = "play", onClick = {
                                    scope.launch { webAction("nginx-start") }
                                })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Muted("配置校验:")
                            if (nTestOk) LevelBadge("通过", Level.Ok) else LevelBadge("异常", Level.Bad)
                        }
                    }

                    /* —— Apache2 —— */
                    PanelCard(title = "Apache2", collapsible = false) {
                        val aInstalled = apache.bool("installed")
                        val aActive = apache.bool("active")
                        Text(
                            text = when {
                                !aInstalled -> "未安装"
                                aActive -> "运行中"
                                else -> "已停止"
                            },
                            color = when {
                                aActive -> Palette.ok
                                else -> Color(0xFF7F92A8)
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val info = apache.str("info").split('\n').firstOrNull() ?: ""
                        if (info.isNotEmpty()) Muted(info)
                    }

                    /* —— SSL 证书 —— */
                    PanelCard(title = "SSL 证书 (Let's Encrypt)", collapsible = false) {
                        Text(
                            "${certs.size}",
                            color = Palette.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Muted(if (certbotInstalled) "certbot 已安装" else "certbot 未安装")
                        Spacer(Modifier.height(8.dp))
                        if (certbotInstalled) {
                            GhostBtn(text = "续期证书", onClick = {
                                scope.launch { webAction("cert-renew") }
                            })
                        } else {
                            GhostBtn(text = "+ 安装 certbot", enabled = !installing, onClick = {
                                scope.launch {
                                    val okc = Modal.confirm(
                                        title = "安装 certbot",
                                        text = "执行 apt-get install -y certbot，约 1-3 分钟。",
                                        okText = "开始安装",
                                    )
                                    if (!okc) return@launch
                                    installing = true
                                    try {
                                        Actions.run(
                                            path = "/pkg/install",
                                            body = jsonObjectOf("pkg" to "certbot"),
                                            title = "certbot 安装日志",
                                        )
                                        load()
                                    } finally {
                                        installing = false
                                    }
                                }
                            })
                        }
                    }
                }

                /* ---------------- 站点表（JS renderSites） ---------------- */
                val sites = n.arr("sites").filterIsInstance<JsonObject>()
                val siteRows = remember(sites) {
                    val rows = mutableListOf<SiteRow>()
                    for (s in sites) {
                        val name = s.str("name")
                        var blocks = s.arr("blocks").filterIsInstance<JsonObject>()
                        if (blocks.isEmpty()) {
                            blocks = listOf(
                                JsonObject(
                                    mapOf(
                                        "serverName" to kotlinx.serialization.json.JsonPrimitive("(无法解析)"),
                                        "listen" to kotlinx.serialization.json.JsonArray(emptyList()),
                                        "root" to kotlinx.serialization.json.JsonNull,
                                        "proxy" to kotlinx.serialization.json.JsonNull,
                                        "ssl" to kotlinx.serialization.json.JsonPrimitive(false),
                                    ),
                                ),
                            )
                        }
                        blocks.forEachIndexed { i, b ->
                            val listenArr = runCatching {
                                (b["listen"] as? kotlinx.serialization.json.JsonArray)
                                    ?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
                                    ?: emptyList()
                            }.getOrDefault(emptyList())
                            rows += SiteRow(
                                site = name,
                                serverName = b.str("serverName").take(50),
                                listen = listenArr.joinToString(", "),
                                rootProxy = (b.str("root").ifEmpty { b.str("proxy") }).ifEmpty { "-" }.take(44),
                                ssl = b.bool("ssl"),
                                first = i == 0,
                            )
                        }
                    }
                    rows
                }

                PanelCard(
                    title = "Nginx 站点 (${sites.size})",
                    right = { GhostBtn(text = "刷新", icon = "refresh", onClick = { scope.launch { loading = true; load() } }) },
                ) {
                    if (sites.isEmpty()) {
                        Muted("sites-enabled 为空")
                    } else {
                        DataTable(
                            columns = listOf(
                                TableCol<SiteRow>("站点文件", weight = 1.2f, mono = true) { r ->
                                    if (r.first) {
                                        Text(r.site, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                    }
                                },
                                TableCol<SiteRow>("server_name", weight = 1.6f, mono = true) { r ->
                                    Text(r.serverName, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<SiteRow>("监听", weight = 1.2f, mono = true) { r ->
                                    Text(r.listen, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<SiteRow>("根目录 / 代理", weight = 1.8f, mono = true) { r ->
                                    Text(r.rootProxy, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<SiteRow>("SSL", weight = 0.7f) { r ->
                                    if (r.ssl) LevelBadge("HTTPS", Level.Ok) else Text("—", color = Palette.muted)
                                },
                                TableCol<SiteRow>("操作", weight = 1.4f, minWidth = 130.dp) { r ->
                                    GhostBtn(text = "重新启用+重载", onClick = {
                                        scope.launch {
                                            val okc = Modal.confirm(
                                                title = "重新启用站点",
                                                text = "启用 ${r.site} 并重载 nginx？",
                                            )
                                            if (!okc) return@launch
                                            webAction(
                                                "site-toggle",
                                                params = jsonObjectOf("site" to r.site, "enable" to true),
                                            )
                                        }
                                    })
                                },
                            ),
                            rows = siteRows,
                            rowKey = { it.site + "#" + it.serverName + "#" + it.listen },
                            emptyText = "sites-enabled 为空",
                        )
                    }
                }

                /* ---------------- 证书详情（JS certs.length 才渲染） ---------------- */
                if (certs.isNotEmpty()) {
                    PanelCard(title = "证书详情") {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>("域名", weight = 1.4f, mono = true) {
                                    Text(it.str("domain"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<JsonObject>("到期时间", weight = 1.6f, mono = true) {
                                    Text(it.str("expiry"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<JsonObject>("剩余天数", weight = 0.8f) {
                                    val days = it.int("daysLeft")
                                    LevelBadge(
                                        "$days 天",
                                        when {
                                            days < 14 -> Level.Bad
                                            days < 30 -> Level.Warn
                                            else -> Level.Ok
                                        },
                                    )
                                },
                            ),
                            rows = certs,
                            rowKey = { it.str("domain") },
                        )
                    }
                }

                /* ---------------- 说明（JS 原文） ---------------- */
                PanelCard(title = "说明") {
                    Muted(
                        "本监控面板自身通过 nginx /panel/ 路径反向代理（配置位于 opencode-relay 站点文件中的 " +
                            "location 块），不要停用该站点以免面板与中继服务同时下线。",
                    )
                }
            }
        }
    }
}
