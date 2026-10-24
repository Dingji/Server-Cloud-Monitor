package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L

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
            error = e.message ?: e::class.simpleName ?: L["请求失败_8fdc4112a4"]
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    /** JS `bind()`：runAction('/web/action', {action, params}, {reload, reloadDelay:1200})。 */
    suspend fun webAction(action: String, params: JsonObject = jsonObjectOf(), danger: Boolean = false) {
        if (danger) {
            val okc = Modal.confirm(title = L["确认操作_3161d348cc"], text = trfn("执行_action_b272ecea51", "action" to action), danger = true)
            if (!okc) return
        }
        try {
            Actions.run(
                path = "/web/action",
                body = jsonObjectOf("action" to action, "params" to params),
                successToast = L["操作成功_3c5be5f2a1"],
                reload = { delay(1200); load() },
            )
        } catch (_: Throwable) {
            delay(1200)
            load()
        }
    }

    PageScaffold(title = L["网站_nginx_62b11c295b"]) {
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
                                !nInstalled -> L["未安装_6f7dc945ae"]
                                nActive -> L["运行中_5942497005"]
                                else -> L["已停止_75dddf524e"]
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
                            GhostBtn(text = L["配置检测_7635dbf3bd"], icon = "check", onClick = {
                                scope.launch { webAction("nginx-test") }
                            })
                            if (nInstalled) {
                                GhostBtn(text = L["重载_e7b13a8bf8"], icon = "restart", onClick = {
                                    scope.launch { webAction("nginx-reload") }
                                })
                            }
                            if (nActive) {
                                DangerBtn(text = L["停止_a17f70a8d3"], icon = "stop", onClick = {
                                    scope.launch { webAction("nginx-stop", danger = true) }
                                })
                            } else {
                                GhostBtn(text = L["启动_ebd26da421"], icon = "play", onClick = {
                                    scope.launch { webAction("nginx-start") }
                                })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Muted(L["配置校验_9e6b524956"])
                            if (nTestOk) LevelBadge(L["通过_dcc4233255"], Level.Ok) else LevelBadge(L["异常_5caf279339"], Level.Bad)
                        }
                    }

                    /* —— Apache2 —— */
                    PanelCard(title = "Apache2", collapsible = false) {
                        val aInstalled = apache.bool("installed")
                        val aActive = apache.bool("active")
                        Text(
                            text = when {
                                !aInstalled -> L["未安装_6f7dc945ae"]
                                aActive -> L["运行中_5942497005"]
                                else -> L["已停止_75dddf524e"]
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
                    PanelCard(title = L["ssl_证书_let_s_encrypt_332fd65bdb"], collapsible = false) {
                        Text(
                            "${certs.size}",
                            color = Palette.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Muted(if (certbotInstalled) L["certbot_已安装_7690d6f09a"] else L["certbot_未安装_b2dd3df63b"])
                        Spacer(Modifier.height(8.dp))
                        if (certbotInstalled) {
                            GhostBtn(text = L["续期证书_bc4865026e"], onClick = {
                                scope.launch { webAction("cert-renew") }
                            })
                        } else {
                            GhostBtn(text = L["安装_certbot_c830445551"], enabled = !installing, onClick = {
                                scope.launch {
                                    val okc = Modal.confirm(
                                        title = L["安装_certbot_b472f9adf8"],
                                        text = L["执行_apt_get_install_y_ce_6568cc1ca1"],
                                        okText = L["开始安装_d109e7cc01"],
                                    )
                                    if (!okc) return@launch
                                    installing = true
                                    try {
                                        Actions.run(
                                            path = "/pkg/install",
                                            body = jsonObjectOf("pkg" to "certbot"),
                                            title = L["certbot_安装日志_1bf087319a"],
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
                                        "serverName" to kotlinx.serialization.json.JsonPrimitive(L["无法解析_9262bec0c8"]),
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
                    title = trf("nginx_站点_0_6bcfddef71", sites.size),
                    right = { GhostBtn(text = L["刷新_38108eaa1d"], icon = "refresh", onClick = { scope.launch { loading = true; load() } }) },
                ) {
                    if (sites.isEmpty()) {
                        Muted(L["sites_enabled_为空_4516b41170"])
                    } else {
                        DataTable(
                            columns = listOf(
                                TableCol<SiteRow>(L["站点文件_6bb8ebce82"], weight = 1.2f, mono = true) { r ->
                                    if (r.first) {
                                        Text(r.site, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                    }
                                },
                                TableCol<SiteRow>("server_name", weight = 1.6f, mono = true) { r ->
                                    Text(r.serverName, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<SiteRow>(L["监听_1c30dd2a97"], weight = 1.2f, mono = true) { r ->
                                    Text(r.listen, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<SiteRow>(L["根目录_代理_6dc180f179"], weight = 1.8f, mono = true) { r ->
                                    Text(r.rootProxy, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<SiteRow>("SSL", weight = 0.7f) { r ->
                                    if (r.ssl) LevelBadge("HTTPS", Level.Ok) else Text("—", color = Palette.muted)
                                },
                                TableCol<SiteRow>(L["操作_f3ea6d345e"], weight = 1.4f, minWidth = 130.dp) { r ->
                                    GhostBtn(text = L["重新启用_重载_7b7fccf0ec"], onClick = {
                                        scope.launch {
                                            val okc = Modal.confirm(
                                                title = L["重新启用站点_54c7d8b461"],
                                                text = trf("启用_0_并重载_nginx_cec5f2e46f", r.site),
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
                            emptyText = L["sites_enabled_为空_4516b41170"],
                        )
                    }
                }

                /* ---------------- 证书详情（JS certs.length 才渲染） ---------------- */
                if (certs.isNotEmpty()) {
                    PanelCard(title = L["证书详情_afc5516b7b"]) {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(L["域名_282d68464d"], weight = 1.4f, mono = true) {
                                    Text(it.str("domain"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<JsonObject>(L["到期时间_3144a998f0"], weight = 1.6f, mono = true) {
                                    Text(it.str("expiry"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<JsonObject>(L["剩余天数_fb0ca9d4ed"], weight = 0.8f) {
                                    val days = it.int("daysLeft")
                                    LevelBadge(
                                        trfn("days_天_a6409fc14f", "days" to days),
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
                PanelCard(title = L["说明_26670dda42"]) {
                    Muted(
                        L["本监控面板自身通过_nginx_panel_e67414791d"] +
                            L["location_块_不要停用该站点以免面板与_4ae164b62b"],
                    )
                }
            }
        }
    }
}
