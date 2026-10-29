package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.has
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AdaptiveRow
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.TableText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* ==================================================================== *
 * PHP 站点 —— 对应 panel-web/php.js 与 /php 前缀端点
 *
 * 每个站点 = 独立系统用户 + 独立 PHP-FPM 池 + 独立 unix 套接字 + 一份
 * nginx server 块；代码按 releases/<时间戳> 原子发布，current/htdocs 软链
 * 切换，可回滚到任意历史版本。
 * ==================================================================== */

private fun fmtBytes(n: Long): String {
    if (n <= 0) return "0"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return (if (v >= 100) v.toLong().toString() else String.format("%.1f", v)) + units[i]
}

private fun ageOf(iso: String): String {
    if (iso.isBlank()) return "-"
    val t = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return "-"
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 90 -> L["刚刚_9e636642d6"]
        s < 5400 -> trf("0_分钟前_607909447d", s / 60)
        s < 172800 -> trf("0_小时前_87a6439b2e", s / 3600)
        else -> trf("0_天前_9094e27946", s / 86400)
    }
}

private fun strings(el: JsonElement?): List<String> =
    (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

private fun panelHost(): String {
    val u = runCatching { AppState.config().normalizedUrl() }.getOrDefault("")
    return u.removePrefix("https://").removePrefix("http://").substringBefore('/')
}

private fun accessUrl(s: JsonObject): String {
    val doms = strings(s.key("domains"))
    if (doms.isNotEmpty()) return (if (s.bool("ssl")) "https://" else "http://") + doms.first()
    val p = strings(s.key("ports")).firstOrNull()
    return "http://" + panelHost() + (if (p != null && p != "80") ":$p" else "")
}

/**
 * 起一个带进度展示的任务，返回任务 id（面板没给就为 null，此时已提示过）。
 * 顶层函数：页面与站点详情卡都要传它给对话框。
 */
suspend fun launchJob(path: String, body: JsonObject, title: String): String? = try {
    val r = AppState.api.post(path, body)
    val id = r.obj("job").str("id")
    if (id.isEmpty()) {
        AppState.toast(r.str("output").ifEmpty { title + L["完成_e982a7473c"] }, ToastKind.Ok)
        null
    } else {
        AppState.toast(trf("0_已启动_8a15c04aca", title), ToastKind.Info)
        id
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    AppState.toast(e.message ?: L["启动失败_65525f0f44"], ToastKind.Err, ms = 8000)
    null
}

/** 轮询 /php/job 直到 active=false，然后回调 [onDone]；轮询失败下一拍重试。 */
@Composable
private fun JobProgress(jobId: String, onDone: () -> Unit) {
    var pct by remember { mutableStateOf(0) }
    var phase by remember { mutableStateOf("…") }
    var detail by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("running") }
    var error by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(jobId) {
        var alive = true
        while (alive) {
            try {
                val j = AppState.api.get("/php/job?id=" + jobId, cacheMs = 0)
                pct = j.int("pct")
                phase = j.str("phase")
                detail = j.str("detail")
                state = j.str("state").ifEmpty { "running" }
                error = j.str("error")
                lines = strings(j.key("log")).takeLast(60)
                if (!j.bool("active")) alive = false
            } catch (_: Throwable) {
            }
            if (alive) delay(1500)
        }
        if (state == "failed") AppState.toast(L["任务失败_f898533eaa"] + error.ifEmpty { L["未知原因_b98c9d9d26"] }, ToastKind.Err, ms = 8000)
        else AppState.toast(L["任务完成_765b7fbfbc"], ToastKind.Ok)
        onDone()
    }

    PanelCard(
        title = if (state == "failed") L["任务失败_b0082f8b8f"] else L["任务执行中_ead00dc1bd"],
        cardKey = "php-job",
        collapsible = false,
    ) {
        AdaptiveRow(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$pct%",
                modifier = Modifier.spanFixed(52.dp),
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = if (state == "failed") Palette.bad else Palette.info,
            )
            Text(
                text = (if (state == "failed") L["失败_f6abc47551"] else "") + phase + (if (detail.isNotEmpty()) " · $detail" else ""),
                modifier = Modifier.span(1f),
                color = Palette.muted,
                fontSize = 12.sp,
            )
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(error, color = Palette.bad, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                lines.joinToString("\n").ifEmpty { L["暂无输出_b22713158d"] },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Palette.text,
            )
        }
    }
}

@Composable
fun PhpSitesPage() {
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JsonElement?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var jobId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            data = AppState.api.get("/php/overview", cacheMs = 0)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e::class.simpleName ?: L["请求失败_8fdc4112a4"]
        } finally {
            loading = false
        }
    }

    suspend fun loadDetail(slug: String) {
        detailLoading = true
        try {
            detail = AppState.api.get("/php/site?slug=" + slug, cacheMs = 0)
        } catch (e: Throwable) {
            AppState.toast(e.message ?: L["读取失败_d9f607a200"], ToastKind.Err, ms = 7000)
        } finally {
            detailLoading = false
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(selected) { selected?.let { loadDetail(it) } }

    PageScaffold(
        title = L["php_站点_f353096c70"],
        desc = "部署与管理 PHP 网站：nginx 虚拟主机 + PHP-FPM 池 + 原子发布与回滚 + 数据库 + HTTPS",
        actions = {
            PrimaryBtn(text = L["新建站点_97cf62968a"], icon = "plus", onClick = {
                scope.launch {
                    val slug = createSite((data as? JsonObject) ?: JsonObject(emptyMap()))
                    if (slug != null) {
                        selected = slug
                        load()
                    }
                }
            })
            Spacer(Modifier.size(8.dp))
            GhostBtn(text = L["任务记录_c9109f7b70"], icon = "list", onClick = { scope.launch { showJobs() } })
            Spacer(Modifier.size(8.dp))
            GhostBtn(text = L["刷新_38108eaa1d"], icon = "refresh", onClick = { scope.launch { load() } })
        },
    ) {
        when {
            loading && data == null && error == null -> LoadingState()
            error != null && data == null -> ErrorState(message = error!!, onRetry = {
                scope.launch { loading = true; load() }
            })
            else -> {
                val d = data ?: JsonObject(emptyMap())
                val versions = d.arr("versions").filterIsInstance<JsonObject>()
                val res = d.obj("resources") ?: JsonObject(emptyMap())
                val sites = d.arr("sites").filterIsInstance<JsonObject>()
                val missing = strings(d.key("missing"))
                val counts = d.obj("counts")

                GridLayout(columns = 4) {
                    StatTile(
                        label = L["php_运行时_95cde05815"],
                        value = if (versions.isEmpty()) L["未安装_6f7dc945ae"] else versions.size.toString() + L["个版本_3ff6c7c2a8"],
                        sub = versions.joinToString(" | ") { v -> v.str("version") + " " + v.str("serviceState") },
                        icon = "code",
                        valueColor = if (versions.isEmpty()) Palette.bad else Palette.ok,
                    )
                    StatTile(
                        label = L["托管站点_c451d16488"],
                        value = sites.size.toString(),
                        sub = trf("启用_0_https_1_带库_e14747b827", sites.count { it.bool("enabled") && it.bool("linked") }, sites.count { it.bool("ssl") }, sites.count { it.key("db") !is kotlinx.serialization.json.JsonNull }),
                        icon = "browser",
                    )
                    StatTile(
                        label = L["磁盘余量_4c54ad7e5e"],
                        value = fmtBytes(res.obj("disk").long("free")),
                        sub = L["站点根_b1dff81ce6"] + d.str("base"),
                        icon = "disk",
                    )
                    StatTile(
                        label = L["可用内存_027febb39b"],
                        value = res.obj("mem").int("availMb").toString() + " MB",
                        sub = res.str("load").ifEmpty { "-" } + L["单池建议_b62707cd9f"] + res.int("poolCeiling") + L["子进程_7bd39f24b3"],
                        icon = "memory",
                    )
                }

                if (missing.isNotEmpty()) {
                    PanelCard(title = L["运行环境缺件_7df39720bf"], cardKey = "php-missing", collapsible = false) {
                        AdaptiveRow(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                L["缺失_533ac0e6eb"] + missing.joinToString(", ") + "。PHP 站点功能需要 php-fpm；Composer 用于 Git/框架部署；certbot 用于 HTTPS；MariaDB 用于建站顺便建库。",
                                modifier = Modifier.span(1f),
                                color = Palette.warn,
                                fontSize = 12.sp,
                            )
                            PrimaryBtn(
                                text = L["一键安装_5d183d0c0c"],
                                modifier = Modifier.spanFixed(110.dp),
                                onClick = {
                                    scope.launch {
                                        for (m in missing) {
                                            val action = when (m) {
                                                "php-fpm" -> "stack"
                                                "mariadb-server" -> "mariadb"
                                                else -> m
                                            }
                                            launchJob("/php/env/install", jsonObjectOf("action" to action), trf("安装_0_710b3a3d30", m))
                                            load()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                jobId?.let { id ->
                    JobProgress(jobId = id) {
                        jobId = null
                        scope.launch { load() }
                    }
                }

                PanelCard(
                    title = trf("站点列表_0_80ff32f6cd", sites.size),
                    cardKey = "php-sites",
                    right = { Text("配置在 /etc/nginx/sites-enabled/panel-php-*.conf", color = Palette.muted, fontSize = 11.sp) },
                ) {
                    if (sites.isEmpty()) {
                        EmptyState(L["还没有_php_站点_点右上_新建站点_建完就_1afa1c7510"], icon = "browser")
                    } else {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(L["站点_619bc67325"], weight = 1.6f) { r ->
                                    TableText(
                                        r.str("name").ifEmpty { r.str("slug") } + "  ·  " + r.str("slug") +
                                            "  ·  " + (strings(r.key("domains")).joinToString(", ").ifEmpty { L["仅端口_f9ab925ab9"] }),
                                    )
                                },
                                TableCol<JsonObject>(L["运行_0c3acd446f"], weight = 1.0f) { r ->
                                    TableText(
                                        "PHP " + r.str("php").ifEmpty { "-" } + " · " + r.str("preset") +
                                            L["端口_56ebc5c05e"] + strings(r.key("ports")).joinToString("/"),
                                    )
                                },
                                TableCol<JsonObject>(L["状态_62e951a692"], weight = 1.1f) { r ->
                                    Column {
                                        if (r.bool("enabled") && r.bool("linked")) {
                                            LevelBadge(L["运行中_5942497005"], Level.Ok)
                                        } else {
                                            LevelBadge(L["已停用_6c7dcbb73a"], Level.Bad)
                                        }
                                        if (r.bool("ssl")) {
                                            Spacer(Modifier.height(4.dp))
                                            LevelBadge("HTTPS", Level.Ok)
                                        }
                                    }
                                },
                                TableCol<JsonObject>(L["代码_33246dda76"], weight = 1.0f) { r ->
                                    TableText(
                                        fmtBytes(r.long("docSize")) + " · " + r.int("fileCount") + L["文件_b1a78312cc"] + r.int("releases") + L["版本_d0e9f788da"],
                                    )
                                },
                                TableCol<JsonObject>(L["操作_f3ea6d345e"], weight = 1.9f) { r ->
                                    val slug = r.str("slug")
                                    val running = r.bool("enabled") && r.bool("linked")
                                    AdaptiveRow {
                                        GhostBtn(text = if (selected == slug) L["收起_5d5815647c"] else L["管理_4989b5cf94"], modifier = Modifier.spanFixed(56.dp), onClick = {
                                            selected = if (selected == slug) null else slug
                                        })
                                        GhostBtn(text = L["部署_3220a19f43"], modifier = Modifier.spanFixed(52.dp), onClick = {
                                            scope.launch {
                                                deployDialog(slug, ::launchJob) { scope.launch { load() } }
                                            }
                                        })
                                        if (running) {
                                            DangerBtn(text = L["停用_d989e55188"], modifier = Modifier.spanFixed(52.dp), onClick = {
                                                scope.launch {
                                                    val okc = Modal.confirm(
                                                        title = L["停用站点_a10b67b937"],
                                                        text = "停用 $slug 会移除它的 nginx 站点链接并重载 nginx，访问立即失效。面板自身与 relay 站点不受影响。",
                                                        danger = true,
                                                    )
                                                    if (okc) {
                                                        Actions.run("/php/site/toggle", jsonObjectOf("slug" to slug, "enabled" to false), reload = { load() })
                                                    }
                                                }
                                            })
                                        } else {
                                            GhostBtn(text = L["启用_d4e9ca3dd4"], modifier = Modifier.spanFixed(52.dp), onClick = {
                                                scope.launch {
                                                    Actions.run("/php/site/toggle", jsonObjectOf("slug" to slug, "enabled" to true), reload = { load() })
                                                }
                                            })
                                        }
                                        DangerBtn(text = L["删除_3755f56f2f"], modifier = Modifier.spanFixed(52.dp), onClick = {
                                            scope.launch {
                                                if (deleteSite(slug)) {
                                                    if (selected == slug) selected = null
                                                    load()
                                                }
                                            }
                                        })
                                    }
                                },
                            ),
                            rows = sites,
                            rowKey = { it.str("slug") },
                            emptyText = L["还没有站点_a5aad0b20b"],
                        )
                    }
                }

                selected?.let { slug ->
                    val det = detail
                    when {
                        detailLoading -> LoadingState(L["读取站点详情_812b7ffd9a"])
                        det == null -> EmptyState(L["读取失败_请重试_db09fe0125"], icon = "warning")
                        else -> SiteDetail(
                            det = (det as? JsonObject) ?: JsonObject(emptyMap()),
                            versions = versions,
                            onReload = { scope.launch { load() } },
                            launchJob = ::launchJob,
                        )
                    }
                }

                PanelCard(title = L["说明_26670dda42"], cardKey = "php-notes") {
                    Text(
                        L["每个站点是独立系统用户_独立_fpm_池_c0479c3805"] +
                            "· 代码布局 /srv/php/<站点>/releases/<时间戳>，nginx 根指向 current/htdocs 软链；切换是原子的，旧版本保留 8 个。\n" +
                            L["可写目录_wp_content_upload_e3afe10d73"] +
                            L["纯_ip_访问需选_1024_以上的端口_云_44cff3ead7"],
                        color = Palette.muted,
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}

/**
 * 站点管理区。JS 版这里的动作很多，KMP 版按同一份契约收进一张卡，避免手机上
 * 滚不到底；表格仍然全部走 DataTable（自带手持端横向滚动）。
 */
@Composable
private fun SiteDetail(
    det: JsonObject,
    versions: List<JsonObject>,
    onReload: () -> Unit,
    launchJob: suspend (String, JsonObject, String) -> String?,
) {
    val scope = rememberCoroutineScope()
    val site = det.obj("site") ?: JsonObject(emptyMap())
    val rec = det.obj("record") ?: JsonObject(emptyMap())
    val rel = det.obj("releases") ?: JsonObject(emptyMap())
    val ssl = det.obj("ssl") ?: JsonObject(emptyMap())
    val db = det.obj("db") ?: JsonObject(emptyMap())
    val slug = site.str("slug")
    val releases = rel.arr("releases").filterIsInstance<JsonObject>()

    PanelCard(
        title = L["站点管理_f7bb30b0a9"] + (site.str("name").ifEmpty { slug }),
        cardKey = "php-detail",
        right = { GhostBtn(text = L["收起_5d5815647c"], onClick = { onReload() }) },
    ) {
        AdaptiveRow(verticalAlignment = Alignment.Top) {
            Column(Modifier.span(1f)) {
                Text(L["访问入口_51e22f43f3"], color = Palette.muted, fontSize = 11.sp)
                strings(site.key("urls")).forEach { u ->
                    Text(u, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.text)
                }
                Spacer(Modifier.height(8.dp))
                Text(L["健康探测_cfdcce10bd"], color = Palette.muted, fontSize = 11.sp)
                ProbeRow(slug)
            }
        }
        Spacer(Modifier.height(6.dp))
        AdaptiveRow(verticalAlignment = Alignment.CenterVertically) {
            GhostBtn(text = L["部署新版本_c58710b606"], icon = "upload", modifier = Modifier.spanFixed(104.dp), onClick = {
                scope.launch {
                    deployDialog(slug, ::launchJob, onReload)
                }
            })
            GhostBtn(text = L["清_opcache_06d12f284b"], modifier = Modifier.spanFixed(96.dp), onClick = {
                scope.launch {
                    Actions.run("/php/opcache/reset", jsonObjectOf("slug" to slug), title = L["重载_php_进程池_a0678761d2"])
                }
            })
            GhostBtn(text = L["编辑配置_bb8107891e"], modifier = Modifier.spanFixed(84.dp), onClick = {
                scope.launch { editSite(slug, rec, versions, onReload) }
            })
        }
    }

    PanelCard(title = trf("发布历史与回滚_0_22550c55c7", releases.size), cardKey = "php-releases") {
        if (releases.isEmpty()) {
            EmptyState(L["还没有发布记录_bbf31b5a9d"], icon = "archive")
        } else {
            DataTable(
                columns = listOf(
                    TableCol<JsonObject>(L["版本_989d1affa0"], weight = 1.4f) { r ->
                        TableText(r.str("id") + if (r.bool("active")) L["当前_aa309a9c7e"] else "")
                    },
                    TableCol<JsonObject>(L["方式_af90809619"], weight = 0.9f) { TableText(it.str("kind")) },
                    TableCol<JsonObject>(L["时间_89b4aa6364"], weight = 1.0f) { TableText(ageOf(it.str("at"))) },
                    TableCol<JsonObject>(L["备注_e0361480e3"], weight = 1.4f) { TableText(it.str("note").ifEmpty { it.str("subject") }) },
                    TableCol<JsonObject>("Git", weight = 0.9f) { r -> TableText(r.str("sha").ifEmpty { "—" }) },
                ),
                rows = releases,
                rowKey = { it.str("id") },
                emptyText = L["还没有发布记录_ac000d2905"],
            )
            Spacer(Modifier.height(8.dp))
            Text(
                L["shared_里的_4ac0c5984e"] + strings(rel.key("shared")).joinToString("、").ifEmpty { L["无_6238bf9ad5"] } + L["不随版本切换丢失_6698d54985"],
                color = Palette.muted,
                fontSize = 11.sp,
            )
        }
    }

    GridLayout(columns = 2) {
        PanelCard(title = "HTTPS", cardKey = "php-ssl", collapsible = false) {
            val cert = ssl.obj("cert")
            if (ssl.bool("ssl") && cert != null) {
                Text(
                    L["已启用_到期_58a409e05c"] + cert.str("expiry") + L["剩_1a9f94c7e4"] + cert.int("daysLeft") + L["天_666bed0251"],
                    fontSize = 12.sp,
                    color = Palette.text,
                )
            } else if (ssl.bool("ssl")) {
                Text(L["已启用_但暂时读不到证书信息_3a7c7d58b4"], fontSize = 12.sp, color = Palette.warn)
            } else {
                Text(L["未启用_ec5b73dce5"], fontSize = 12.sp, color = Palette.muted)
            }
            val reason = ssl.str("reason")
            if (reason.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(reason, color = Palette.muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            AdaptiveRow {
                GhostBtn(
                    text = if (ssl.bool("ssl")) L["重新签发_4dd898efea"] else L["申请证书_1929a9e97b"],
                    modifier = Modifier.spanFixed(96.dp),
                    onClick = { scope.launch { sslDialog(slug, strings(site.key("domains")), ssl.bool("ssl"), ::launchJob, onReload) } },
                )
                if (ssl.bool("ssl")) {
                    DangerBtn(text = L["关闭_https_e19ad13b9c"], modifier = Modifier.spanFixed(96.dp), onClick = {
                        scope.launch { Actions.run("/php/ssl/disable", jsonObjectOf("slug" to slug), reload = onReload) }
                    })
                }
            }
        }

        PanelCard(title = L["数据库_f4dbbc63a5"], cardKey = "php-db", collapsible = false) {
            if (db.bool("bound")) {
                Text(
                    L["库名_457a04a8af"] + db.obj("db").str("name") + L["用户_2cf84ba59e"] + db.obj("db").str("user") +
                        " · " + db.int("tables") + L["表_52c6483e26"] + fmtBytes(db.long("bytes")),
                    fontSize = 12.sp,
                    color = Palette.text,
                )
            } else {
                Text(L["尚未绑定数据库_f98083af30"], fontSize = 12.sp, color = Palette.muted)
            }
            Spacer(Modifier.height(8.dp))
            AdaptiveRow {
                if (db.bool("bound")) {
                    GhostBtn(text = L["重置密码_7e422146dd"], modifier = Modifier.spanFixed(88.dp), onClick = {
                        scope.launch {
                            val okc = Modal.confirm(title = L["重置数据库密码_73d85565db"], text = L["旧密码立即失效_需要同步改应用配置_632d5777aa"])
                            if (!okc) return@launch
                            try {
                                val r = AppState.api.post("/php/db/password", jsonObjectOf("slug" to slug))
                                Modal.output(L["新数据库密码_只显示这一次_db3da15884"], r.str("password"))
                            } catch (e: Throwable) {
                                AppState.toast(e.message ?: L["重置失败_dd9a02eacf"], ToastKind.Err)
                            }
                        }
                    })
                    GhostBtn(text = L["导出备份_d11b66842a"], modifier = Modifier.spanFixed(88.dp), onClick = {
                        scope.launch { launchJob("/php/db/dump", jsonObjectOf("slug" to slug), L["备份数据库_8ac426ef16"]) }
                    })
                } else {
                    PrimaryBtn(text = L["建数据库_c0af8826a1"], modifier = Modifier.spanFixed(96.dp), onClick = {
                        scope.launch { createDb(slug, onReload) }
                    })
                }
            }
        }
    }

    PanelCard(title = L["git_与自动部署_ef5869644f"], cardKey = "php-git") {
        val git = rec.obj("git")
        Text(
            if (git == null || git.str("url").isEmpty()) L["未配置仓库_89e645f953"] else git.str("url") + " · " + git.str("branch"),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Palette.text,
        )
        Spacer(Modifier.height(8.dp))
        AdaptiveRow {
            GhostBtn(text = L["配置仓库_20d38cf2f0"], modifier = Modifier.spanFixed(88.dp), onClick = {
                scope.launch { gitDialog(slug, git, onReload) }
            })
            GhostBtn(text = L["部署日志_fb3b0a1eca"], modifier = Modifier.spanFixed(88.dp), onClick = {
                scope.launch { logDialog(slug, "deploy") }
            })
        }
    }

    PanelCard(title = L["流量与日志_fb53417271"], cardKey = "php-logs") {
        TrafficSummary(slug)
        Spacer(Modifier.height(8.dp))
        AdaptiveRow {
            GhostBtn(text = L["访问日志_b5cd27723a"], modifier = Modifier.spanFixed(80.dp), onClick = { scope.launch { logDialog(slug, "access") } })
            GhostBtn(text = L["错误日志_6a1d4eccbe"], modifier = Modifier.spanFixed(80.dp), onClick = { scope.launch { logDialog(slug, "error") } })
            GhostBtn(text = L["慢日志_f15f5a3d9b"], modifier = Modifier.spanFixed(72.dp), onClick = { scope.launch { logDialog(slug, "slow") } })
        }
    }
}

@Composable
private fun ProbeRow(slug: String) {
    var text by remember { mutableStateOf(L["未探测_f0c3554820"]) }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GhostBtn(text = L["探测_b5ef40a22d"], onClick = {
            scope.launch {
                text = L["探测中_d02f3646b4"]
                try {
                    val r = AppState.api.get("/php/preview?slug=" + slug, cacheMs = 0)
                    text = "HTTP " + r.int("code") + " · " + r.str("detail") + " → " + r.str("url")
                } catch (e: Throwable) {
                    text = L["探测失败_beca07dc6d"] + (e.message ?: "")
                }
            }
        })
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Palette.muted)
    }
}

@Composable
private fun TrafficSummary(slug: String) {
    var st by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(slug) {
        try {
            st = AppState.api.get("/php/stats?slug=" + slug, cacheMs = 4000) as? JsonObject
        } catch (_: Throwable) {
        }
    }
    val s = st
    if (s == null) {
        Text(L["统计读取中_30de676f1b"], color = Palette.muted, fontSize = 11.sp)
        return
    }
    if (!s.bool("exists")) {
        Text(L["还没有访问日志_6b2f3c3e15"], color = Palette.muted, fontSize = 11.sp)
        return
    }
    val cls = s.obj("byClass")
    Text(
        L["请求_86a42d1ce9"] + s.int("requests") + " · 2xx " + cls.int("ok") + " · 4xx " + cls.int("clientError") +
            " · 5xx " + cls.int("serverError") + L["流量_64d38e0917"] + fmtBytes(s.long("bytes")) + L["错误率_adaf95e588"] + s.int("errorRate") + "%",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = Palette.muted,
    )
    val top = s.arr("topUrls").filterIsInstance<JsonObject>().take(5)
        .joinToString("\n") { "  " + it.str("path") + " (" + it.int("count") + ")" }
    if (top.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
val topText = trf("热门路径_0_8a2b42c8c8", top)
        Text(
            topText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Palette.muted,
        )
    }
}

/* ------------------------------------------------------------------ *
 * 对话框
 * ------------------------------------------------------------------ */

/** 返回新建成功的 slug，取消/失败为 null。 */
private suspend fun createSite(overview: JsonObject): String? {
    val presets = overview.arr("presets").filterIsInstance<JsonObject>()
        .map { it.str("id") to it.str("label") }
        .ifEmpty { listOf("generic" to L["通用_php_站点_b1bbf5eb15"]) }
    val vers = overview.arr("versions").filterIsInstance<JsonObject>().map { it.str("version") }
    if (vers.isEmpty()) {
        AppState.toast("还没有可用的 PHP-FPM 版本，请先安装运行栈", ToastKind.Err, ms = 8000)
        return null
    }
    val f = Modal.prompt(
        L["新建_php_站点_db45918cac"],
        listOf(
            FormField.Text("slug", L["站点标识_小写字母_数字_连字符_5ba4cf3567"], mono = true, hint = "my-shop"),
            FormField.Text("name", L["显示名称_可选_默认同标识_bea923e805"]),
            FormField.Select("preset", L["框架预设_7b85a407b9"], options = presets.map { it.second }, value = presets.first().second),
            FormField.Select("php", L["php_版本_3811d63835"], options = vers.map { "PHP $it" }, value = "PHP " + vers.last()),
            FormField.Text("domains", L["域名_多个用空格或逗号分隔_可空_441ac3c73e"], mono = true, hint = "a.com www.a.com"),
            FormField.Text("ports", L["监听端口_7e95f4e583"], value = "80", mono = true, hint = L["80_443_或_1024_单端口_50b43a2621"]),
            FormField.Text("docRoot", L["代码内公开目录_留空_根_fe4696170f"], mono = true, hint = L["laravel_thinkphp_填_publi_4a052f7620"]),
        ),
        okText = L["创建_fcbd093292"],
    ) ?: return null
    if (f["slug"].isNullOrBlank()) {
        AppState.toast(L["站点标识不能为空_60ea68edb9"], ToastKind.Err)
        return null
    }
    return try {
        val r = AppState.api.post(
            "/php/site/create",
            jsonObjectOf(
                "slug" to f["slug"], "name" to f["name"],
                "preset" to (presets.firstOrNull { it.second == f["preset"] }?.first ?: "generic"),
                "php" to f["php"]?.removePrefix("PHP "),
                "domains" to (f["domains"] ?: ""),
                "ports" to (f["ports"] ?: "80").split(',', ' ').filter { it.isNotBlank() },
                "docRoot" to f["docRoot"],
            ),
        )
        AppState.toast(r.str("output").ifEmpty { L["站点已创建_5992e4f5d1"] }, ToastKind.Ok, ms = 7000)
        if (f["ports"].isNullOrBlank().not() && (f["ports"] ?: "").any { it !in "80443" }) {
            AppState.toast(L["自定义端口已在本机防火墙放行_云上还需在安全组放_b6bc8a42a7"], ToastKind.Warn, ms = 9000)
        }
        f["slug"]
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppState.toast(e.message ?: L["创建失败_deb3990191"], ToastKind.Err, ms = 9000)
        null
    }
}

private suspend fun deleteSite(slug: String): Boolean {
    val okc = Modal.confirm(
        title = L["删除站点_1bc45c7436"],
        text = "将移除 $slug 的 nginx 站点、PHP-FPM 池、系统用户及其配套数据库。站点目录（代码/日志/数据库备份）会移动到保留位置，不是直接删除。",
        danger = true,
        okText = L["确认删除_3c06abe116"],
    )
    if (!okc) return false
    Actions.run("/php/site/delete", jsonObjectOf("slug" to slug), title = L["删除站点_1bc45c7436"])
    return true
}

private suspend fun createDb(slug: String, onReload: () -> Unit) {
    val okc = Modal.confirm(
        title = L["创建数据库_1bf70a1209"],
        text = trf("为_0_建一个同名_mariadb_库与专用_d3c680c7c1", slug),
    )
    if (!okc) return
    try {
        val r = AppState.api.post("/php/db/create", jsonObjectOf("slug" to slug))
        val db = r.obj("db")
        Modal.output(
            L["数据库已创建_请立刻保存密码_c06c734744"],
            "host: localhost\nname: " + db.str("name") + "\nuser: " + db.str("user") +
                "\npass: " + db.str("passwordShown"),
        )
        onReload()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppState.toast(e.message ?: L["建库失败_f1a6536bb3"], ToastKind.Err, ms = 8000)
    }
}

private suspend fun sslDialog(
    slug: String,
    domains: List<String>,
    enabled: Boolean,
    launchJob: suspend (String, JsonObject, String) -> String?,
    onReload: () -> Unit,
) {
    if (domains.isEmpty()) {
        AppState.toast(L["该站点没有域名_let_s_encrypt_不为_e56b22633b"], ToastKind.Err, ms = 9000)
        return
    }
    val f = Modal.prompt(
        if (enabled) L["重新签发证书_572e11113b"] else L["申请_https_证书_b93f3bf9c1"],
        listOf(
            FormField.Text("domains", L["要签入的域名_默认全部_ac9d646966"], value = domains.joinToString(" "), mono = true),
            FormField.Text("email", L["联系邮箱_可选_用于到期提醒_674381ba6b"], mono = true),
        ),
        okText = L["申请证书_1929a9e97b"],
    ) ?: return
    val id = launchJob(
        "/php/ssl/issue",
        jsonObjectOf("slug" to slug, "domains" to (f["domains"] ?: domains.joinToString(" ")), "email" to f["email"]),
        trf("0_申请证书_46e1e399d3", slug),
    )
    if (id != null) onReload()
}

private suspend fun gitDialog(slug: String, git: JsonObject?, onReload: () -> Unit) {
    val f = Modal.prompt(
        trf("git_仓库_0_b08c093f64", slug),
        listOf(
            FormField.Text("url", L["仓库地址_6b470c4670"], value = git?.str("url") ?: "", mono = true, hint = "https://github.com/me/site.git 或 git@…"),
            FormField.Text("branch", L["分支_2f6f9d51c5"], value = git?.str("branch")?.ifEmpty { "main" } ?: "main", mono = true),
            FormField.Text("subdir", L["仓库内子目录_可选_6e511f0bf7"], value = git?.str("subdir") ?: "", mono = true),
            FormField.Text("depth", L["浅克隆深度_728c4a56d9"], value = (git?.int("depth") ?: 1).toString(), mono = true),
            FormField.Text("token", L["https_token_留空_不修改_b968c5a83c"], mono = true),
            FormField.Text("privateKey", L["deploy_私钥_pem_留空_不修改_435e99baca"], mono = true),
        ),
        okText = L["保存_fadf24dbc5"],
    ) ?: return
    val body = mutableMapOf<String, Any?>(
        "slug" to slug,
        "git" to jsonObjectOf(
            "url" to f["url"], "branch" to f["branch"], "subdir" to f["subdir"],
            "depth" to (f["depth"]?.toIntOrNull() ?: 1),
        ),
    )
    val gitBody = body["git"] as JsonObject
    val withSecrets = buildList {
        gitBody.forEach { add(it.key to it.value) }
        if (!f["token"].isNullOrBlank()) add("token" to f["token"])
        if (!f["privateKey"].isNullOrBlank()) add("privateKey" to f["privateKey"])
    }
    try {
        AppState.api.post("/php/site/git", jsonObjectOf("slug" to slug, "git" to jsonObjectOf(*withSecrets.toTypedArray())))
        AppState.toast(L["git_配置已保存_8a9977f1c6"], ToastKind.Ok)
        onReload()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppState.toast(e.message ?: L["保存失败_40525a7328"], ToastKind.Err)
    }
    body.clear()
}

private suspend fun logDialog(slug: String, type: String) {
    val f = Modal.prompt(
        trf("查看日志_0_1_5cf0267061", slug, type),
        listOf(
            FormField.Text("lines", L["行数_579f280b5d"], value = "200", mono = true),
            FormField.Text("grep", L["关键字过滤_可选_d49f97def5"]),
        ),
        okText = L["查看_f7acefd2d4"],
    ) ?: return
    try {
        val r = AppState.api.get(
            "/php/logs?slug=" + slug + "&type=" + type + "&lines=" + (f["lines"] ?: "200") +
                "&grep=" + java.net.URLEncoder.encode(f["grep"] ?: "", "UTF-8"),
            cacheMs = 0,
        )
        Modal.output(
            trf("0_日志_1_9bf84454ec", type, slug) + (if (r.bool("exists")) "（" + fmtBytes(r.long("bytes")) + "）" else L["文件尚不存在_7d88a98ca8"]),
            strings(r.key("lines")).joinToString("\n").ifEmpty { L["空_c6db273746"] },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppState.toast(e.message ?: L["读取失败_d9f607a200"], ToastKind.Err)
    }
}

private suspend fun editSite(slug: String, rec: JsonObject, versions: List<JsonObject>, onReload: () -> Unit) {
    val pool = rec.obj("pool")
    val ov = rec.obj("phpOverrides")
    val f = Modal.prompt(
        trf("编辑站点_0_a78b38e7d6", slug),
        listOf(
            FormField.Text("name", L["显示名称_75ae6a8a7d"], value = rec.str("name")),
            FormField.Select("preset", L["框架预设_7b85a407b9"], options = listOf("generic", "wordpress", "laravel", "thinkphp", "codeigniter", "symfony", "drupal", "hyperf", "static", "custom"), value = rec.str("preset").ifEmpty { "generic" }),
            FormField.Select("php", L["php_版本_3811d63835"], options = versions.map { "PHP " + it.str("version") }, value = "PHP " + rec.str("php")),
            FormField.Text("domains", L["域名_空格_逗号分隔_30b7de82a0"], value = strings(rec.key("domains")).joinToString(" "), mono = true),
            FormField.Text("ports", L["监听端口_7e95f4e583"], value = strings(rec.key("ports")).joinToString(","), mono = true),
            FormField.Text("docRoot", L["公开目录_20c996471e"], value = rec.str("docRoot"), mono = true),
            FormField.Select("pm", L["进程管理_ddaa0fd352"], options = listOf("ondemand", "dynamic", "static"), value = pool.str("pm").ifEmpty { "ondemand" }),
            FormField.Text("maxChildren", L["子进程上限_0ab4b01e82"], value = pool.int("pm.max_children").let { if (it == 0) "12" else it.toString() }, mono = true),
            FormField.Text("memoryLimit", "memory_limit", value = ov.str("memory_limit").ifEmpty { "256M" }, mono = true),
            FormField.Text("uploadMax", "upload_max_filesize", value = ov.str("upload_max_filesize").ifEmpty { "64M" }, mono = true),
            FormField.Text("maxExec", L["max_execution_time_秒_6664f51ac9"], value = ov.str("max_execution_time").ifEmpty { "60" }, mono = true),
            FormField.Text("disableFunctions", L["disable_functions_留空_面板默_5931136103"], value = rec.str("disableFunctions"), mono = true),
            FormField.Text("uploadDirs", L["可写目录_逗号分隔_禁止其中执行_php_060775f1a2"], value = strings(rec.key("uploadDirs")).joinToString(","), mono = true),
        ),
        okText = L["保存并重载_c558bd828e"],
    ) ?: return
    try {
        Actions.run(
            "/php/site/update",
            jsonObjectOf(
                "slug" to slug, "name" to f["name"], "preset" to f["preset"],
                "php" to f["php"]?.removePrefix("PHP "),
                "domains" to (f["domains"] ?: ""), "ports" to f["ports"], "docRoot" to f["docRoot"],
                "pool" to jsonObjectOf("pm" to f["pm"], "pm.max_children" to (f["maxChildren"]?.toIntOrNull() ?: 12)),
                "phpOverrides" to jsonObjectOf(
                    "memory_limit" to f["memoryLimit"], "upload_max_filesize" to f["uploadMax"],
                    "max_execution_time" to f["maxExec"],
                ),
                "disableFunctions" to f["disableFunctions"],
                "uploadDirs" to (f["uploadDirs"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            ),
            title = L["保存站点配置_f2086928e2"],
        )
        onReload()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppState.toast(e.message ?: L["保存失败_40525a7328"], ToastKind.Err, ms = 8000)
    }
}

private suspend fun showJobs() {
    try {
        val r = AppState.api.get("/php/jobs", cacheMs = 0)
        val jobs = r.arr("jobs").filterIsInstance<JsonObject>()
        if (jobs.isEmpty()) {
            Modal.output(L["任务记录_c9109f7b70"], L["还没有_php_相关任务_17a0e21846"])
            return
        }
        Modal.output(
            L["任务记录_最近_a1ee32e8e1"] + jobs.size + "）",
            jobs.joinToString("\n") { j ->
                "[" + j.str("state") + "] " + j.str("title") + "  " +
                    j.str("startedAt").take(19).replace('T', ' ') + " " +
                    j.str("error").ifEmpty { j.str("detail") }
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppState.toast(e.message ?: L["读取失败_d9f607a200"], ToastKind.Err)
    }
}

/** 部署对话框。 */
private suspend fun deployDialog(
    slug: String,
    launchJob: suspend (String, JsonObject, String) -> String?,
    onReload: () -> Unit,
) {
    // 预设目录每次现取：一键安装应用列表以服务器为准
    val presets = try {
        AppState.api.get("/php/presets", cacheMs = 60000).arr("presets").filterIsInstance<JsonObject>()
            .filter { it.has("install") }
    } catch (_: Throwable) {
        emptyList()
    }
    val f = Modal.prompt(
        trf("部署到_0_dff0a57ff3", slug),
        listOf(
            FormField.Select(
                "channel", L["部署方式_3f2d194fff"],
                options = listOf(L["压缩包路径部署_1cf33144b9"], L["git_拉取_composer_e34cc04b3d"], L["仅重载配置_61382705e9"], L["一键安装框架_d5d5d6520b"]),
                value = L["压缩包路径部署_1cf33144b9"],
            ),
            FormField.Text("path", L["服务器上的压缩包绝对路径_cf324d7b39"], value = "/srv/php/uploads/site.tar.gz", mono = true, hint = "zip / tar.gz / tar.xz / 7z"),
            FormField.Text("gitUrl", L["仓库地址_git_通道_6a55908a21"], mono = true),
            FormField.Text("gitBranch", L["分支_2f6f9d51c5"], value = "main", mono = true),
            FormField.Text("note", L["版本备注_4e1c6cc81e"]),
            FormField.Select("app", L["一键安装应用_1bca70606b"], options = presets.map { it.str("label") }, value = presets.firstOrNull()?.str("label") ?: "WordPress"),
        ),
        okText = L["开始部署_dfeb5a7dff"],
    ) ?: return
    val channel = when (f["channel"]) {
        L["git_拉取_composer_e34cc04b3d"] -> "git"
        L["仅重载配置_61382705e9"] -> "reload"
        L["一键安装框架_d5d5d6520b"] -> "scaffold"
        else -> "archive"
    }
    val body = mutableMapOf<String, Any?>(
        "slug" to slug, "channel" to channel, "note" to f["note"], "autoRollback" to true,
    )
    when (channel) {
        "archive" -> {
            body["source"] = "path"
            body["path"] = f["path"]
        }
        "git" -> body["git"] = jsonObjectOf("url" to f["gitUrl"], "branch" to f["gitBranch"], "remember" to true)
        "scaffold" -> body["app"] = presets.firstOrNull { it.str("label") == f["app"] }?.str("id") ?: "wordpress"
    }
    val id = launchJob("/php/deploy", jsonObjectOf(*body.map { it.key to it.value }.toTypedArray()), trf("0_部署_b2a0ca3c97", slug))
    if (id != null) onReload()
}
