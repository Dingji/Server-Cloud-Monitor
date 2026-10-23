package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.nowEpochMillis
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.EmptyState
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
import com.servermaintance.panel.ui.PctBar
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/* ==================================================================== *
 * 软件包管理 —— 复刻 upstream/public/js/pages/packages.js（770 行）
 * + core.js CM.pkg 任务进度体系（L405-L551）
 * 后端权威：upstream/server/pkgmgr.js；字段样本：api-dump/pkg_*.json、
 *           docs/findings/extra-api/pkg_*.json、docs/api/page-packages.md
 * ==================================================================== */

private val ACTION_TITLE = mapOf(
    "install" to "安装", "remove" to "卸载", "purge" to "彻底卸载",
    "upgrade" to "升级", "full-upgrade" to "完整升级", "autoremove" to "清理孤立依赖",
    "hold" to "锁定版本", "unhold" to "解除锁定", "update" to "更新软件源索引", "clean" to "清理缓存",
)
private val RISKY = setOf("remove", "purge", "autoremove", "full-upgrade")

/** core.js JOB_STEP_LABEL（进度阶段条中文标签）。 */
private val JOB_STEP_LABEL = mapOf(
    "queued" to "排队中", "resolve" to "解析依赖", "plan" to "制定方案", "download" to "下载软件包",
    "unpack" to "解包", "configure" to "安装配置", "remove" to "移除软件包", "update" to "更新索引",
    "finish" to "收尾", "done" to "完成", "failed" to "失败",
)

/** 预览汇总行：`N upgraded, N newly installed, N to remove`。 */
private val PLAN_RE = Regex("(\\d+) upgraded, (\\d+) newly installed, (\\d+) to remove")

/** JS `kb(n) = n == null ? '-' : fmt.bytes(n * 1024)`（size_kb 可能是 JSON null）。 */
private fun kbRaw(el: JsonElement?): String = when {
    el == null || el is JsonNull -> "-"
    else -> Fmt.bytes((((el as? JsonPrimitive)?.doubleOrNull) ?: 0.0) * 1024)
}

/** encodeURIComponent 等价（js 的保留字符集）。 */
private fun urlEnc(s: String): String = buildString {
    for (ch in s) {
        val c = ch.code
        if (ch.isLetterOrDigit() || ch in "-_.!~*'()") append(ch)
        else {
            append('%')
            append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/** 次要说明文本（JS `<div class="muted small">`）。 */
@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Palette.muted, fontSize = 12.sp, lineHeight = 19.sp)
}

/** 等宽预块（JS `<pre class="pre">`）。 */
@Composable
private fun PreBox(text: String, maxHeight: androidx.compose.ui.unit.Dp = 260.dp) {
    Box(
        Modifier.fillMaxWidth().heightIn(max = maxHeight)
            .background(Palette.tableSurface, RectangleShape)
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(text, color = Palette.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    }
}

/** kv 行（JS `<dl class="kv">`）。 */
@Composable
private fun KvRow(label: String, value: String, mono: Boolean = false, tint: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.width(84.dp))
        Text(
            value,
            color = tint ?: Palette.text,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

private class SrcOpt(val uri: String, val suites: List<String>, val file: String)

private fun srcDisplay(uri: String): String =
    uri.replace(Regex("^https?://"), "")

@Composable
fun PackagesPage() {
    /* ---------------- 页面级状态（对应 JS 的 S / sum / recData…） ---------------- */
    var sum by remember { mutableStateOf<JsonObject?>(null) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("search") }
    var pkgBusy by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    val sel = remember { mutableStateMapOf<String, Boolean>() } // 包名 -> 是否已安装
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()

    /* 任务进度（CM.pkg.watch 的 KMP 等价物） */
    var jobOn by remember { mutableStateOf(false) }
    var jobHidden by remember { mutableStateOf(false) }
    var jobTitle by remember { mutableStateOf("") }
    var jobView by remember { mutableStateOf<JsonObject?>(null) }

    /* 搜索页签 */
    var srcList by remember { mutableStateOf<List<SrcOpt>>(emptyList()) }
    var srcReady by remember { mutableStateOf(false) }
    var srcUri by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var searchRes by remember { mutableStateOf<JsonObject?>(null) }
    var searchErr by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }

    /* 已安装页签 */
    var iq by remember { mutableStateOf("") }
    var iqDeb by remember { mutableStateOf("") }
    var iSection by remember { mutableStateOf("") }
    var iSort by remember { mutableStateOf("name") }
    var iDir by remember { mutableStateOf("asc") }
    var iPage by remember { mutableStateOf(0) }
    var instRes by remember { mutableStateOf<JsonObject?>(null) }
    var instErr by remember { mutableStateOf<String?>(null) }
    var instLoading by remember { mutableStateOf(false) }

    /* 可升级页签 */
    var upRes by remember { mutableStateOf<JsonObject?>(null) }
    var upErr by remember { mutableStateOf<String?>(null) }

    /* 推荐页签 */
    var recData by remember { mutableStateOf<JsonObject?>(null) }
    var recErr by remember { mutableStateOf<String?>(null) }
    var recCat by remember { mutableStateOf("all") }
    var recQ by remember { mutableStateOf("") }

    /* 软件源页签 */
    var srcData by remember { mutableStateOf<JsonObject?>(null) }
    var srcErr by remember { mutableStateOf<String?>(null) }
    var bkData by remember { mutableStateOf<JsonObject?>(null) }
    val lat = remember { mutableStateMapOf<String, String>() }
    var testingAll by remember { mutableStateOf(false) }

    /* ---------------- 取数 ---------------- */
    suspend fun loadSummary() {
        try {
            sum = AppState.api.get("/pkg/summary", 30000) as? JsonObject
            loadErr = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (sum == null) loadErr = e.message ?: "请求失败"
        }
    }

    suspend fun doSearchNow() {
        if (q.isBlank()) {
            searchRes = null; searchErr = null
            return
        }
        searching = true
        try {
            searchRes = (if (srcUri.isEmpty()) {
                AppState.api.get("/pkg/search?q=${urlEnc(q.trim())}&limit=100", 60000)
            } else {
                AppState.api.get("/pkg/search-in-source?q=${urlEnc(q.trim())}&uri=${urlEnc(srcUri)}&limit=100", 120000)
            }) as? JsonObject
            searchErr = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            searchErr = e.message ?: "搜索失败"
        } finally {
            searching = false
        }
    }

    /* ---------------- CM.pkg.watch：650ms 轮询 + 8 次容错 + null 判丢失 ---------------- */
    suspend fun watchJob(id: String, t: String): JsonObject {
        jobTitle = t
        jobHidden = false
        jobView = null
        jobOn = true
        var fails = 0
        try {
            while (true) {
                val v: JsonObject? = try {
                    val r = AppState.api.get("/pkg/job?id=$id", cacheMs = 0)
                    when {
                        r is JsonNull -> null
                        r is JsonObject -> r
                        else -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fails++
                    if (fails >= 8) throw e
                    delay(1200)
                    continue
                }
                fails = 0
                if (v == null) {
                    throw IllegalStateException("任务状态已丢失（服务端可能重启过），请重新发起该操作")
                }
                jobView = v
                when (v.str("state")) {
                    "done" -> return v
                    "failed" -> throw IllegalStateException(v.str("error").ifEmpty { "任务执行失败" })
                }
                delay(650)
            }
        } finally {
            jobOn = false
        }
    }

    /* ---------------- 统一变更入口（JS doAct） ---------------- */
    suspend fun doAct(action: String, names: List<String>, force: Boolean = false) {
        if (pkgBusy) {
            AppState.toast("已有 apt 任务在执行，请等待完成", ToastKind.Warn)
            return
        }
        pkgBusy = true
        val title = ACTION_TITLE[action] ?: action
        val t = if (names.size > 1) "$title · ${names.size} 个包" else title
        var retry = false
        try {
            try {
                val start = AppState.api.post(
                    "/pkg/job",
                    jsonObjectOf(
                        "action" to action,
                        "names" to kotlinx.serialization.json.JsonArray(names.map { JsonPrimitive(it) }),
                        "force" to force,
                    ),
                )
                val id = start.str("id")
                if (id.isEmpty()) throw IllegalStateException("任务启动失败：服务端未返回任务 id")
                val r = watchJob(id, t)
                val secsEl = r.obj("result")?.key("seconds") ?: r.key("seconds")
                val secs = (secsEl as? JsonPrimitive)?.doubleOrNull
                AppState.toast(
                    title + " 完成" + (if (secs != null) "（${secs.toInt()}s）" else ""),
                    ToastKind.Ok, 4000,
                )
                Modal.output(title + " 结果", r.str("output"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = e.message ?: e::class.simpleName ?: "未知错误"
                /* 受保护包首次被拦 → 显式强制重试（JS L86-L91、L105） */
                if (msg.contains("受保护") && !force) {
                    val okc = Modal.confirm(
                        title = "该操作已被安全策略拦截",
                        text = msg.substringBefore('\n') +
                            "\n\n服务端已阻止该操作。继续执行可能导致系统无法启动、SSH 断开或面板失效。",
                        okText = "我已知晓风险，强制执行",
                        danger = true,
                    )
                    if (okc) retry = true
                } else {
                    AppState.toast(msg.substringBefore('\n'), ToastKind.Err, 8000)
                    Modal.output(title + " 失败", msg, isError = true)
                }
            }
        } finally {
            pkgBusy = false
            sel.clear()
            detail = null
            recData = null
            srcReady = false
            upRes = null
            runCatching { loadSummary() }
            reloadTick++
        }
        if (retry) doAct(action, names, force = true)
    }

    /* ---------------- 预览 → 确认 → 执行（JS previewAndAct / removeFlow） ---------------- */
    suspend fun previewAndAct(action: String, names: List<String>, warn: String = "") {
        val pv = try {
            AppState.api.post(
                "/pkg/preview",
                jsonObjectOf(
                    "action" to action,
                    "names" to kotlinx.serialization.json.JsonArray(names.map { JsonPrimitive(it) }),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppState.toast((e.message ?: "预览失败").substringBefore('\n'), ToastKind.Err, 7000)
            return
        }
        val lines = pv.str("output").trim()
        val sb = StringBuilder()
        if (names.isNotEmpty()) sb.appendLine(names.joinToString(", "))
        PLAN_RE.find(lines)?.let { m ->
            sb.appendLine("${m.groupValues[1]} 升级 · ${m.groupValues[2]} 新装 · ${m.groupValues[3]} 移除")
        }
        if (warn.isNotEmpty()) sb.appendLine(warn)
        sb.append(lines.ifEmpty { "(无变更)" })
        val okc = Modal.confirm(
            title = (ACTION_TITLE[action] ?: action) + " · 变更预览",
            text = sb.toString(),
            okText = "执行",
            danger = action in RISKY,
        )
        if (!okc) return
        doAct(action, names)
    }

    suspend fun removeFlow(names: List<String>, rdeps: List<String> = emptyList()) {
        val warn = if (rdeps.isNotEmpty()) {
            "以下已安装包依赖它，卸载可能连带移除或导致异常：\n" + rdeps.take(14).joinToString(", ")
        } else ""
        previewAndAct("remove", names, warn)
    }

    /* 卸载前先取反向依赖（JS data-rm / data-rm2） */
    suspend fun removePkg(name: String) {
        val rd = try {
            AppState.api.get("/pkg/detail?name=${urlEnc(name)}", 40000).arr("reverse_deps")
                .mapNotNull { (it as? JsonPrimitive)?.content }
        } catch (_: Throwable) {
            emptyList()
        }
        removeFlow(listOf(name), rd)
    }

    suspend fun showDetail(name: String) {
        try {
            detail = AppState.api.get("/pkg/detail?name=${urlEnc(name)}", 40000) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppState.toast(e.message ?: "加载详情失败", ToastKind.Err)
        }
    }

    /* ---------------- 挂载：summary + CM.pkg.resume ---------------- */
    LaunchedEffect(Unit) {
        loadSummary()
        /* CM.pkg.resume：接上页面刷新前仍在跑的任务 */
        try {
            val cur = AppState.api.get("/pkg/job", cacheMs = 0)
            val o = cur as? JsonObject
            if (o != null && o.bool("active") && o.str("id").isNotEmpty()) {
                val at = o.str("action")
                try {
                    watchJob(o.str("id"), (ACTION_TITLE[at] ?: at) + " · 进行中")
                } finally {
                    recData = null; srcReady = false; upRes = null
                    runCatching { loadSummary() }
                    reloadTick++
                }
            }
        } catch (_: Throwable) {
            /* 无任务（null）/ 404 / 网络失败都静默 */
        }
    }

    /* ---------------- 页签数据加载 ---------------- */
    /* 搜索：源下拉数据（带缓存，动作后失效） */
    LaunchedEffect(tab, reloadTick, srcReady) {
        if (tab != "search" || srcReady) return@LaunchedEffect
        try {
            val d = AppState.api.get("/pkg/sources", 30000)
            val map = LinkedHashMap<String, SrcOpt>()
            for (f in d.arr("files")) {
                for (s in f.arr("stanzas")) {
                    if (!s.bool("enabled")) continue
                    val uris = s.arr("uris").mapNotNull { (it as? JsonPrimitive)?.content }.filter { it.isNotEmpty() }
                    if (uris.isEmpty()) continue
                    val uri = uris[0]
                    if (!map.containsKey(uri)) {
                        map[uri] = SrcOpt(uri, s.arr("suites").mapNotNull { (it as? JsonPrimitive)?.content }, f.str("path"))
                    }
                }
            }
            srcList = map.values.toList()
        } catch (_: Throwable) {
            srcList = emptyList()
        }
        srcReady = true
    }

    /* 搜索：520ms 防抖 */
    LaunchedEffect(q, srcUri, tab, reloadTick) {
        if (tab != "search") return@LaunchedEffect
        delay(520)
        doSearchNow()
    }

    /* 已安装：420ms 防抖过滤 */
    LaunchedEffect(iq) {
        delay(420)
        if (iqDeb != iq) {
            iqDeb = iq
            iPage = 0
        }
    }
    LaunchedEffect(tab, iqDeb, iSection, iSort, iDir, iPage, reloadTick) {
        if (tab != "installed") return@LaunchedEffect
        instLoading = true
        try {
            instRes = AppState.api.get(
                "/pkg/installed?q=${urlEnc(iqDeb)}&section=${urlEnc(iSection)}&sort=$iSort&dir=$iDir&limit=50&offset=${iPage * 50}",
                60000,
            ) as? JsonObject
            instErr = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            instErr = e.message ?: "加载失败"
        } finally {
            instLoading = false
        }
    }

    /* 可升级 */
    LaunchedEffect(tab, reloadTick) {
        if (tab != "upgradable") return@LaunchedEffect
        try {
            upRes = AppState.api.get("/pkg/upgradable", 60000) as? JsonObject
            upErr = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            upErr = e.message ?: "加载失败"
        }
    }

    /* 推荐（缓存，动作后由 doAct 置空） */
    LaunchedEffect(tab, reloadTick, recData) {
        if (tab != "recommended" || recData != null) return@LaunchedEffect
        try {
            recData = AppState.api.get("/pkg/recommended", 40000) as? JsonObject
            recErr = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recErr = e.message ?: "加载失败"
        }
    }

    /* 软件源 */
    LaunchedEffect(tab, reloadTick) {
        if (tab != "sources") return@LaunchedEffect
        try {
            srcData = AppState.api.get("/pkg/sources", 30000) as? JsonObject
            srcErr = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            srcErr = e.message ?: "加载失败"
        }
        bkData = (try {
            AppState.api.get("/pkg/source-backups", 20000)
        } catch (_: Throwable) {
            JsonObject(mapOf("total" to JsonPrimitive(0)))
        }) as? JsonObject
    }

    /* 切页签清空详情（JS render() L248） */
    LaunchedEffect(tab) { detail = null }

    /* ==================================================================== *
     * 页签内容（局部 Composable，直接捕获页面状态）
     * ==================================================================== */

    @Composable
    fun Mono12(text: String, color: Color = Palette.text, maxLines: Int = 1) {
        Text(
            text,
            color = color,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = maxLines,
        )
    }

    /* 批量操作条（JS renderSelBar） */
    @Composable
    fun SelBar(mode: String, pageRows: List<JsonObject> = emptyList()) {
        val n = sel.size
        Column {
            if (pageRows.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Toggle(
                        checked = pageRows.all { sel.containsKey(it.str("name")) },
                        onCheckedChange = { v ->
                            pageRows.forEach {
                                val nm = it.str("name")
                                if (v) sel[nm] = it.bool("installed") else sel.remove(nm)
                            }
                        },
                        label = "全选本页",
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            if (n == 0) {
                Muted("勾选后可批量操作：执行前照常预览变更，过程中显示总进度条与实时日志（单次最多 500 个包，自动分批）")
                return
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("已选 ", color = Palette.muted, fontSize = 12.sp)
                Text("$n", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(" 个包", color = Palette.muted, fontSize = 12.sp)
                val ins = sel.values.count { !it }
                val rem = n - ins
                val act: suspend (String, List<String>) -> Unit = { a, names ->
                    if (names.isEmpty()) AppState.toast("没有可执行该操作的包", ToastKind.Warn)
                    else if (names.size > 500) AppState.toast("一次最多 500 个包，请分批选择", ToastKind.Warn)
                    else previewAndAct(a, names)
                }
                if (mode == "installed") {
                    DangerBtn(text = "批量卸载 ($n)", onClick = {
                        scope.launch { act("remove", sel.filterValues { it }.keys.toList()) }
                    })
                } else {
                    if (ins > 0) {
                        PrimaryBtn(text = "批量安装 ($ins)", onClick = {
                            scope.launch { act("install", sel.filterValues { !it }.keys.toList()) }
                        })
                    }
                    if (rem > 0) {
                        DangerBtn(text = "批量卸载 ($rem)", onClick = {
                            scope.launch { act("remove", sel.filterValues { it }.keys.toList()) }
                        })
                    }
                }
                GhostBtn(text = "取消选择", onClick = { sel.clear() })
            }
        }
    }

    /* ---------------- 详情面板（JS showDetail L196-L240） ---------------- */
    @Composable
    fun DetailCard() {
        val d = detail ?: return
        var expanded by remember(d) { mutableStateOf(false) }
        val prot = when (d.str("protected")) {
            "critical" -> "系统关键组件"
            "protected" -> "面板依赖"
            else -> ""
        }
        PanelCard(
            title = d.str("name"),
            collapsible = false,
            right = {
                if (d.bool("installed")) {
                    GhostBtn(text = "锁定版本", onClick = { scope.launch { doAct("hold", listOf(d.str("name"))) } })
                    GhostBtn(text = "解除锁定", onClick = { scope.launch { doAct("unhold", listOf(d.str("name"))) } })
                    DangerBtn(text = "卸载", onClick = {
                        scope.launch { removeFlow(listOf(d.str("name")), d.arr("reverse_deps").mapNotNull { (it as? JsonPrimitive)?.content }) }
                    })
                } else {
                    PrimaryBtn(text = "安装", onClick = { scope.launch { previewAndAct("install", listOf(d.str("name"))) } })
                }
                GhostBtn(text = "关闭", onClick = { detail = null })
            },
        ) {
            if (prot.isNotEmpty()) {
                LevelBadge(prot, if (prot == "系统关键组件") Level.Bad else Level.Warn)
                Spacer(Modifier.height(8.dp))
            }
            GridLayout(columns = 2, spacing = 14.dp) {
                Column {
                    KvRow("版本", d.str("version").ifEmpty { "-" }, mono = true)
                    KvRow(
                        "候选",
                        d.str("candidate").ifEmpty { "-" } + if (d.bool("pinned")) "（已固定）" else "",
                        mono = true,
                        tint = if (d.bool("pinned")) Palette.info else null,
                    )
                    KvRow(
                        "已装版本",
                        when {
                            !d.bool("installed") -> "未安装"
                            d.bool("upgradable") -> d.str("installed_version") + "（有新版本）"
                            else -> d.str("installed_version")
                        },
                        mono = true,
                        tint = when {
                            !d.bool("installed") -> Palette.muted
                            d.bool("upgradable") -> Palette.warn
                            else -> null
                        },
                    )
                    KvRow("分组", d.str("section").ifEmpty { "-" })
                    KvRow(
                        "安装大小",
                        d.str("size") + if (d.int("file_count") > 0) " · ${d.int("file_count")} 个文件" else "",
                    )
                    KvRow("主页", d.str("homepage").ifEmpty { "-" })
                }
                Column {
                    Muted("摘要")
                    Spacer(Modifier.height(3.dp))
                    Text(d.str("summary").ifEmpty { "-" }, color = Palette.text, fontSize = 12.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    val depN = d.arr("depends").size
                    val recN = d.arr("recommends").size
                    val revN = d.arr("reverse_deps").size
                    Muted("依赖 $depN · 推荐依赖 $recN · 反向依赖 $revN")
                    if (revN > 0) {
                        val revs = d.arr("reverse_deps").mapNotNull { (it as? JsonPrimitive)?.content }
                        Muted("被依赖：" + revs.take(10).joinToString(", ") + if (revN > 10) " …" else "")
                    }
                    Spacer(Modifier.height(6.dp))
                    GhostBtn(text = if (expanded) "收起完整说明" else "完整说明与依赖列表", onClick = { expanded = !expanded })
                    if (expanded) {
                        Spacer(Modifier.height(6.dp))
                        PreBox(
                            d.str("description") +
                                "\n\n--- 依赖 ---\n" + (d.arr("depends").mapNotNull { (it as? JsonPrimitive)?.content }
                                .joinToString("\n").ifEmpty { "-" }) +
                                "\n\n--- 推荐 ---\n" + (d.arr("recommends").mapNotNull { (it as? JsonPrimitive)?.content }
                                .take(30).joinToString("\n").ifEmpty { "-" }),
                            maxHeight = 280.dp,
                        )
                    }
                }
            }
        }
    }

    /* ---------------- 搜索安装页签 ---------------- */
    @Composable
    fun SearchTab() {
        PanelCard(title = "搜索软件包") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    value = q,
                    onValueChange = { q = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "搜索软件包：名称或描述，如 nginx / htop / python3",
                )
                val options = listOf("全部源（全局搜索）") + srcList.map { srcDisplay(it.uri) }
                SelectField(
                    value = if (srcUri.isEmpty()) "全部源（全局搜索）" else srcList.firstOrNull { it.uri == srcUri }?.let { srcDisplay(it.uri) } ?: "全部源（全局搜索）",
                    options = options,
                    onValueChange = { v ->
                        srcUri = if (v == "全部源（全局搜索）") "" else srcList.firstOrNull { srcDisplay(it.uri) == v }?.uri ?: ""
                    },
                    modifier = Modifier.width(280.dp),
                )
                PrimaryBtn(text = "搜索", icon = "search", onClick = { scope.launch { doSearchNow() } })
            }
            Spacer(Modifier.height(8.dp))
            if (srcUri.isEmpty()) {
                Muted("搜索范围：全部已启用源（共 ${srcList.size} 个源），按名称与描述匹配。到「软件源」页可切换国内镜像。")
            } else {
                val one = srcList.firstOrNull { it.uri == srcUri }
                Muted(
                    "仅搜索源 $srcUri" +
                        (one?.suites?.takeIf { it.isNotEmpty() }?.let { "（${it.joinToString(" ")}）" } ?: "") +
                        " 的软件包。若结果为空，请到「软件源」页更新该源索引。",
                )
            }
        }

        when {
            q.isBlank() -> PanelCard(title = "开始搜索", collapsible = false) {
                Muted("输入关键词查找软件包，或指定某个源后仅在该源内搜索。")
            }
            searching && searchRes == null -> LoadingState()
            searchErr != null && searchRes == null -> ErrorState(message = searchErr!!, onRetry = {
                scope.launch { doSearchNow() }
            })
            searchRes == null -> EmptyState("暂无搜索结果")
            else -> {
                val d = searchRes ?: return
                val total = d.int("total")
                if (total == 0) {
                    PanelCard(title = "搜索结果 (0)", collapsible = false) {
                        Muted("${if (srcUri.isNotEmpty()) "该源中" else ""}未找到与 “$q” 匹配的软件包。")
                    }
                    return
                }
                val scanned = d.int("scanned_files")
                val title = buildString {
                    append("搜索结果 ($total)")
                    if (srcUri.isNotEmpty()) append(" · 指定源")
                    if (scanned > 0) append(" · 已扫描 $scanned 个索引文件")
                    if (d.bool("truncated")) append(" · 已截断")
                }
                val rows = d.arr("results").filterIsInstance<JsonObject>()
                PanelCard(title = title) {
                    SelBar(mode = "mixed", pageRows = rows)
                    Spacer(Modifier.height(8.dp))
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(" ", weight = 0.35f, minWidth = 44.dp) { r ->
                                Toggle(
                                    checked = sel.containsKey(r.str("name")),
                                    onCheckedChange = { v ->
                                        val nm = r.str("name")
                                        if (v) sel[nm] = r.bool("installed") else sel.remove(nm)
                                    },
                                )
                            },
                            TableCol<JsonObject>("包名", weight = 1.5f, mono = true) { Mono12(it.str("name")) },
                            TableCol<JsonObject>("版本", weight = 1.2f, mono = true) {
                                Mono12(it.str("version").ifEmpty { "-" }, color = Palette.muted)
                            },
                            TableCol<JsonObject>("描述", weight = 2.4f) {
                                Text(it.str("description").take(140), fontSize = 12.sp, color = Palette.muted)
                            },
                            if (srcUri.isEmpty()) {
                                TableCol<JsonObject>("已占", weight = 0.8f) {
                                    Text(kbRaw(it.key("size_kb")), fontSize = 12.sp)
                                }
                            } else {
                                TableCol<JsonObject>("分组", weight = 0.9f) {
                                    Text(it.str("section").ifEmpty { "-" }, fontSize = 12.sp, color = Palette.muted)
                                }
                            },
                            TableCol<JsonObject>("状态", weight = 0.8f) {
                                if (it.bool("installed")) LevelBadge("已安装", Level.Ok)
                                else LevelBadge("未安装", Level.Muted)
                            },
                            TableCol<JsonObject>("操作", weight = 1.6f, minWidth = 160.dp) { r ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    GhostBtn(text = "详情", onClick = { scope.launch { showDetail(r.str("name")) } })
                                    if (r.bool("installed")) {
                                        DangerBtn(text = "卸载", onClick = { scope.launch { removePkg(r.str("name")) } })
                                    } else {
                                        PrimaryBtn(text = "安装", onClick = {
                                            scope.launch { previewAndAct("install", listOf(r.str("name"))) }
                                        })
                                    }
                                }
                            },
                        ),
                        rows = rows,
                        rowKey = { it.str("name") },
                        emptyText = "未找到匹配的软件包",
                    )
                }
            }
        }
    }

    /* ---------------- 已安装页签 ---------------- */
    @Composable
    fun InstalledTab() {
        val sortLabels = listOf("按名称" to "name", "按大小" to "size_kb", "按分组" to "section", "按版本" to "version")
        val dirLabels = listOf("升序" to "asc", "降序" to "desc")
        val sections = instRes?.arr("sections").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        PanelCard(title = "过滤与排序", collapsible = false) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    value = iq,
                    onValueChange = { iq = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "过滤已安装包（名称或分组）",
                )
                SelectField(
                    value = iSection,
                    options = listOf("全部分组") + sections,
                    onValueChange = { iSection = if (it == "全部分组") "" else it; iPage = 0 },
                    modifier = Modifier.width(170.dp),
                )
                SelectField(
                    value = sortLabels.firstOrNull { it.second == iSort }?.first ?: "按名称",
                    options = sortLabels.map { it.first },
                    onValueChange = { v -> iSort = sortLabels.firstOrNull { it.first == v }?.second ?: "name"; iPage = 0 },
                    modifier = Modifier.width(120.dp),
                )
                SelectField(
                    value = dirLabels.firstOrNull { it.second == iDir }?.first ?: "升序",
                    options = dirLabels.map { it.first },
                    onValueChange = { v -> iDir = dirLabels.firstOrNull { it.first == v }?.second ?: "asc" },
                    modifier = Modifier.width(100.dp),
                )
            }
        }
        when {
            instLoading && instRes == null -> LoadingState()
            instErr != null && instRes == null -> ErrorState(message = instErr!!, onRetry = { reloadTick++ })
            instRes == null -> EmptyState("暂无数据")
            else -> {
                val d = instRes ?: return
                val total = d.int("total")
                val limit = d.int("limit").coerceAtLeast(1)
                val offset = d.int("offset")
                val pages = kotlin.math.max(1, kotlin.math.ceil(total / limit.toDouble()).toInt())
                val rows = d.arr("rows").filterIsInstance<JsonObject>()
                PanelCard(
                    title = "已安装 ($total)",
                    right = {
                        Muted(
                            "第 ${if (total > 0) offset + 1 else 0}-${kotlin.math.min(offset + limit, total)} 条",
                        )
                    },
                ) {
                    SelBar(mode = "installed", pageRows = rows)
                    Spacer(Modifier.height(8.dp))
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(" ", weight = 0.35f, minWidth = 44.dp) { r ->
                                Toggle(
                                    checked = sel.containsKey(r.str("name")),
                                    onCheckedChange = { v ->
                                        val nm = r.str("name")
                                        if (v) sel[nm] = true else sel.remove(nm)
                                    },
                                )
                            },
                            TableCol<JsonObject>("包名", weight = 1.6f, mono = true) { Mono12(it.str("name")) },
                            TableCol<JsonObject>("版本", weight = 1.4f, mono = true) {
                                Mono12(it.str("version"), color = Palette.muted)
                            },
                            TableCol<JsonObject>("分组", weight = 0.9f) {
                                Text(it.str("section"), fontSize = 12.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>("大小", weight = 0.7f, align = androidx.compose.ui.text.style.TextAlign.End) {
                                Text(it.str("size"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>("状态", weight = 0.7f) {
                                val st = it.str("status")
                                if (st.startsWith("ii")) LevelBadge("已装", Level.Ok)
                                else LevelBadge(st.trim().ifEmpty { "?" }, Level.Muted)
                            },
                            TableCol<JsonObject>("操作", weight = 1.4f, minWidth = 150.dp) { r ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    GhostBtn(text = "详情", onClick = { scope.launch { showDetail(r.str("name")) } })
                                    DangerBtn(text = "卸载", onClick = { scope.launch { removePkg(r.str("name")) } })
                                }
                            },
                        ),
                        rows = rows,
                        rowKey = { it.str("name") },
                        emptyText = "没有匹配的已安装包",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostBtn(text = "上一页", enabled = iPage > 0, onClick = { iPage-- })
                        Text(
                            " ${iPage + 1} / $pages ",
                            color = Palette.muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        GhostBtn(text = "下一页", enabled = iPage + 1 < pages, onClick = { iPage++ })
                    }
                }
            }
        }
    }

    /* ---------------- 可升级页签 ---------------- */
    @Composable
    fun UpgradableTab() {
        when {
            upRes == null && upErr == null -> LoadingState()
            upErr != null && upRes == null -> ErrorState(message = upErr!!, onRetry = { reloadTick++ })
            upRes == null -> EmptyState("暂无数据")
            else -> {
                val d = upRes ?: return
                val total = d.int("total")
                val rows = d.arr("rows").filterIsInstance<JsonObject>()
                PanelCard(
                    title = "可升级 ($total)",
                    right = {
                        PrimaryBtn(text = "全部安全升级", icon = "upload", onClick = {
                            scope.launch { previewAndAct("upgrade", emptyList()) }
                        })
                        GhostBtn(text = "完整升级（含移除）", onClick = {
                            scope.launch {
                                previewAndAct(
                                    "full-upgrade", emptyList(),
                                    "完整升级可能移除与新版本冲突的软件包，请仔细核对预览内容。",
                                )
                            }
                        })
                    },
                ) {
                    if (total == 0) {
                        Muted("所有软件包均为最新版本。")
                    } else {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>("包名", weight = 1.4f, mono = true) { Mono12(it.str("name")) },
                                TableCol<JsonObject>("当前版本", weight = 1.2f, mono = true) {
                                    Mono12(it.str("current"), color = Palette.muted)
                                },
                                TableCol<JsonObject>("目标版本", weight = 1.2f, mono = true) {
                                    Mono12(it.str("candidate"), color = Palette.ok)
                                },
                                TableCol<JsonObject>("来源", weight = 0.8f) {
                                    Text(it.str("suite"), fontSize = 12.sp, color = Palette.muted)
                                },
                                TableCol<JsonObject>("操作", weight = 0.8f, minWidth = 76.dp) {
                                    PrimaryBtn(text = "升级", onClick = {
                                        scope.launch { previewAndAct("upgrade", listOf(it.str("name"))) }
                                    })
                                },
                            ),
                            rows = rows,
                            rowKey = { it.str("name") },
                        )
                    }
                }
                PanelCard(title = "说明", collapsible = true) {
                    Muted("「安全升级」只做向上兼容的升级，不卸载其它包；「完整升级」允许移除冲突包，风险更高，执行前会展示变更预览。")
                }
            }
        }
    }

    /* ---------------- 常用推荐页签 ---------------- */
    @Composable
    fun RecTile(r: JsonObject) {
        Column(
            Modifier.fillMaxWidth()
                .background(Palette.panel2, RoundedCornerShape(3.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(3.dp))
                .padding(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Toggle(
                        checked = sel.containsKey(r.str("name")),
                        onCheckedChange = { v ->
                            val nm = r.str("name")
                            if (v) sel[nm] = r.bool("installed") else sel.remove(nm)
                        },
                    )
                    Spacer(Modifier.size(6.dp))
                    Mono12(r.str("name"))
                }
                when {
                    r.bool("held") -> LevelBadge("已锁定", Level.Warn)
                    r.bool("installed") -> LevelBadge("已安装", Level.Ok)
                    else -> LevelBadge("未安装", Level.Muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            Muted(r.str("desc"))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (r.bool("installed")) {
                    GhostBtn(text = "详情", onClick = { scope.launch { showDetail(r.str("name")) } })
                    DangerBtn(text = "卸载", onClick = { scope.launch { removePkg(r.str("name")) } })
                } else {
                    PrimaryBtn(text = "安装", icon = "download", onClick = {
                        scope.launch { previewAndAct("install", listOf(r.str("name"))) }
                    })
                    GhostBtn(text = "详情", onClick = { scope.launch { showDetail(r.str("name")) } })
                }
            }
        }
    }

    @Composable
    fun RecommendedTab() {
        when {
            recData == null && recErr == null -> LoadingState()
            recErr != null && recData == null -> ErrorState(message = recErr!!, onRetry = { recData = null; reloadTick++ })
            recData == null -> EmptyState("暂无数据")
            else -> {
                val d = recData ?: return
                val cats = d.arr("categories").filterIsInstance<JsonObject>()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Muted("共 ${d.int("total")} 个常用包 · 已装 ${d.int("installed_total")} · ${d.int("category_count")} 个分类")
                    SearchField(
                        value = recQ,
                        onValueChange = { recQ = it },
                        modifier = Modifier.width(190.dp),
                        placeholder = "在推荐中过滤",
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (recCat == "all") PrimaryBtn(text = "全部 (${d.int("total")})", onClick = { recCat = "all" })
                    else GhostBtn(text = "全部 (${d.int("total")})", onClick = { recCat = "all" })
                    cats.forEach { c ->
                        val label = "${c.str("label")} ${c.int("installed_count")}/${c.int("total")}"
                        if (recCat == c.str("id")) PrimaryBtn(text = label, onClick = { recCat = c.str("id") })
                        else GhostBtn(text = label, onClick = { recCat = c.str("id") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                SelBar(mode = "mixed")
                Spacer(Modifier.height(8.dp))

                val kw = recQ.trim().lowercase()
                val shown = if (recCat == "all") cats else cats.filter { it.str("id") == recCat }
                val groups = shown.mapNotNull { c ->
                    val rows = c.arr("rows").filterIsInstance<JsonObject>().filter {
                        kw.isEmpty() || it.str("name").lowercase().contains(kw) || it.str("desc").lowercase().contains(kw)
                    }
                    if (rows.isEmpty()) null else c to rows
                }
                if (groups.isEmpty()) {
                    PanelCard(title = "推荐包", collapsible = false) { Muted("没有匹配的包") }
                }
                groups.forEach { (c, rows) ->
                    PanelCard(
                        title = c.str("label"),
                        right = { Muted("${rows.size} 个 · 已装 ${rows.count { it.bool("installed") }}") },
                    ) {
                        GridLayout(columns = 3, spacing = 8.dp) {
                            rows.forEach { r -> RecTile(r) }
                        }
                    }
                }
            }
        }
    }

    /* ---------------- 维护操作页签 ---------------- */
    @Composable
    fun MaintainTab() {
        val s = sum ?: return
        val holdList = s.arr("hold_list").mapNotNull { (it as? JsonPrimitive)?.content }
        val holds = holdList.joinToString("、")
        val logDir = (AppState.me.value?.obj("env")?.obj("paths")?.str("logs")).orEmpty()
            .ifEmpty { "服务端日志目录" }
        GridLayout(columns = 2) {
            PanelCard(title = "软件源索引") {
                Muted("从镜像站拉取最新软件包列表，安装新包前建议先刷新。")
                Spacer(Modifier.height(4.dp))
                Muted("当前索引更新于 ${s.str("index_updated").ifEmpty { "未知" }}")
                Spacer(Modifier.height(8.dp))
                PrimaryBtn(text = "更新索引", onClick = { scope.launch { doAct("update", emptyList()) } })
            }
            PanelCard(title = "缓存清理") {
                Muted("删除已下载的 .deb 安装包，释放 ${s.str("apt_cache_size").ifEmpty { "?" }}。不影响已安装的软件。")
                Spacer(Modifier.height(8.dp))
                GhostBtn(text = "清理 apt 缓存", onClick = { scope.launch { doAct("clean", emptyList()) } })
            }
            PanelCard(title = "孤立依赖清理") {
                Muted("移除不再被需要的自动安装依赖（apt autoremove），执行前会展示预览。")
                Spacer(Modifier.height(8.dp))
                DangerBtn(text = "查看可清理项", onClick = {
                    scope.launch {
                        try {
                            val d = AppState.api.get("/pkg/autoremove", 150000)
                            val out = d.str("output")
                            Modal.output("可清理的孤立依赖（apt-get -s autoremove）", out)
                            if (Regex("\\d+ to remove|REMOVED").containsMatchIn(out)) {
                                val okc = Modal.confirm(
                                    title = "执行清理",
                                    text = "确认执行 apt autoremove --purge ？",
                                    okText = "执行",
                                    danger = true,
                                )
                                if (okc) doAct("autoremove", emptyList())
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            AppState.toast(e.message ?: "查询失败", ToastKind.Err)
                        }
                    }
                })
            }
            PanelCard(title = "版本锁定") {
                Muted(
                    "锁定的包不会被自动升级。当前 ${s.int("held")} 个" +
                        (if (holds.isNotEmpty()) "：$holds" else "") + "。",
                )
                Spacer(Modifier.height(8.dp))
                if (holdList.isNotEmpty()) {
                    GhostBtn(text = "全部解除锁定", onClick = {
                        scope.launch {
                            val okc = Modal.confirm(
                                title = "解除锁定",
                                text = "解除 $holds 的版本锁定？这些包将恢复自动升级。",
                                okText = "解除",
                            )
                            if (okc) doAct("unhold", holdList)
                        }
                    })
                } else {
                    Muted("当前没有锁定的包")
                }
            }
        }
        PanelCard(title = "操作日志") {
            Muted("每次变更的完整 apt 输出保存在服务器 $logDir/pkg-*.log。")
        }
    }

    /* ---------------- 软件源页签 ---------------- */
    @Composable
    fun MirrorTile(m: JsonObject) {
        val cur = srcData?.obj("current") ?: JsonObject(emptyMap())
        val isCur = cur.str("mirror_id") == m.str("id")
        Column(
            Modifier.fillMaxWidth()
                .background(Palette.panel2, RoundedCornerShape(3.dp))
                .border(
                    1.dp,
                    if (isCur) AccentState.current.primary else Palette.border,
                    RoundedCornerShape(3.dp),
                )
                .padding(11.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(m.str("name"), color = Palette.text, fontSize = 12.5.sp)
                if (isCur) LevelBadge("当前", Level.Ok)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                m.str("url"),
                color = Palette.muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(5.dp))
            Muted("${m.str("region")} · ${m.str("desc")}")
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GhostBtn(
                    text = lat[m.str("id")] ?: "测速",
                    onClick = {
                        scope.launch {
                            try {
                                val r = AppState.api.get("/pkg/test-mirror?url=${urlEnc(m.str("url"))}", 25000)
                                lat[m.str("id")] = if (r.bool("reachable")) "${r.int("latency_ms")} ms" else "不可达"
                            } catch (_: Throwable) {
                                lat[m.str("id")] = "失败"
                            }
                        }
                    },
                )
                if (!isCur) {
                    PrimaryBtn(text = "切换到此源", onClick = {
                        scope.launch {
                            val okc = Modal.confirm(
                                title = "切换软件源",
                                text = "从 ${cur.str("uri").ifEmpty { "当前" }} 切换到：\n\n" +
                                    "${m.str("name")}  ${m.str("url")}\n" +
                                    "${m.str("region")} · ${m.str("desc")}\n\n" +
                                    "将自动备份当前源文件并执行 apt-get update；若刷新失败会自动回滚。此过程约需 30-120 秒。",
                                okText = "切换并刷新索引",
                            )
                            if (!okc) return@launch
                            try {
                                val r = AppState.api.post("/pkg/switch-mirror", jsonObjectOf("mirror" to m.str("id")))
                                AppState.toast("已切换到 ${m.str("name")}", ToastKind.Ok, 5000)
                                Modal.output(
                                    "切换软件源",
                                    r.str("output") + (r.str("backup").let { if (it.isEmpty()) "" else "\n\n原配置备份：$it" }),
                                )
                                srcReady = false
                                reloadTick++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                AppState.toast((e.message ?: "切换失败").substringBefore('\n'), ToastKind.Err, 8000)
                                Modal.output("切换失败（已回滚）", e.message ?: "", isError = true)
                            }
                        }
                    })
                }
            }
        }
    }

    @Composable
    fun SourcesTab() {
        when {
            srcData == null && srcErr == null -> LoadingState()
            srcErr != null -> PanelCard(title = "当前系统不支持软件源切换", collapsible = false) {
                Muted(srcErr!!)
                Spacer(Modifier.height(6.dp))
                Muted("镜像切换是 Debian/Ubuntu 专有功能（apt sources.list）。其它发行版请使用系统自带的软件源工具。")
            }
            else -> {
                val d = srcData ?: return
                val cur = d.obj("current") ?: JsonObject(emptyMap())
                val bk = bkData ?: JsonObject(emptyMap())

                PanelCard(
                    title = "当前镜像",
                    right = { GhostBtn(text = "刷新状态", icon = "refresh", onClick = { reloadTick++ }) },
                ) {
                    GridLayout(columns = 2, spacing = 14.dp) {
                        Column {
                            KvRow("主仓库", "${cur.str("name").ifEmpty { "未识别" }} ${cur.str("uri").ifEmpty { "-" }}")
                            KvRow("发行版", d.str("codename").ifEmpty { "-" }, mono = true)
                            KvRow(
                                "安全更新",
                                cur.str("security_mirror").ifEmpty { "同主仓库" } +
                                    (cur.str("security_uri").takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""),
                            )
                            KvRow("源文件", cur.str("file").ifEmpty { "-" }, mono = true)
                        }
                        Column {
                            Muted("· 切换镜像会先备份源文件，改写后立即执行 apt-get update；若刷新失败会自动回滚到原镜像。")
                            Muted("· 切换成功后会清理旧镜像残留索引，避免搜索结果串源。")
                            Muted("· 阿里云 ECS 建议使用「阿里云(内网源)」，走内网不消耗公网流量。")
                        }
                    }
                }

                val mirrors = d.arr("mirrors").filterIsInstance<JsonObject>()
                PanelCard(
                    title = "可选镜像",
                    right = {
                        GhostBtn(text = "测速全部", icon = "activity", enabled = !testingAll, onClick = {
                            scope.launch {
                                testingAll = true
                                try {
                                    for (m in mirrors) {
                                        try {
                                            val r = AppState.api.get("/pkg/test-mirror?url=${urlEnc(m.str("url"))}", 25000)
                                            lat[m.str("id")] = if (r.bool("reachable")) "${r.int("latency_ms")} ms" else "不可达"
                                        } catch (_: Throwable) {
                                            lat[m.str("id")] = "失败"
                                        }
                                    }
                                } finally {
                                    testingAll = false
                                }
                            }
                        })
                    },
                ) {
                    GridLayout(columns = 3, spacing = 9.dp) {
                        mirrors.forEach { m -> MirrorTile(m) }
                    }
                }

                GridLayout(columns = 2) {
                    val files = d.arr("files").filterIsInstance<JsonObject>()
                    PanelCard(title = "已启用的源文件 (${files.size})") {
                        if (files.isEmpty()) {
                            Muted("未发现源文件")
                        } else {
                            files.forEach { f ->
                                Text(f.str("name"), color = Palette.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.height(4.dp))
                                f.arr("stanzas").forEach { s ->
                                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                        Box(
                                            Modifier.width(2.dp).height(30.dp)
                                                .background(if (s.bool("enabled")) Palette.ok else Palette.muted),
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            val types = s.str("types")
                                            val uris = s.arr("uris").mapNotNull { (it as? JsonPrimitive)?.content }
                                            Text(
                                                "$types · ${uris.joinToString(" ")}",
                                                color = Palette.text,
                                                fontSize = 11.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                            val suites = s.arr("suites").mapNotNull { (it as? JsonPrimitive)?.content }
                                            val comps = s.arr("components").mapNotNull { (it as? JsonPrimitive)?.content }
                                            val signed = s.str("signed_by").split('/').lastOrNull().orEmpty()
                                            Muted(
                                                "${suites.joinToString(" ")} [${comps.joinToString(" ")}]" +
                                                    (if (signed.isNotEmpty()) " · 签名 $signed" else ""),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    PanelCard(title = "源文件备份 (${bk.int("total")})") {
                        Muted("每次切换镜像前自动备份，路径 ${bk.str("dir").ifEmpty { "-" }}")
                        Spacer(Modifier.height(6.dp))
                        val items = bk.arr("items").mapNotNull { (it as? JsonPrimitive)?.content }
                        if (items.isNotEmpty()) PreBox(items.joinToString("\n"), maxHeight = 220.dp)
                        else Muted("暂无备份")
                        Spacer(Modifier.height(8.dp))
                        Muted("如需回滚，可通过 Web 终端把备份文件复制回 /etc/apt/sources.list.d/ 后执行「更新索引」。")
                    }
                }
            }
        }
    }

    /* ---------------- 任务进度弹窗 + 后台胶囊（CM.pkg.watch） ---------------- */
    @Composable
    fun JobSteps(v: JsonObject) {
        val steps = v.arr("steps").mapNotNull { (it as? JsonPrimitive)?.content }
            .filter { it != "failed" && it != "done" }
        if (steps.isEmpty()) return
        val cur = if (v.str("state") == "done") steps.size else steps.indexOf(v.str("phase"))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { i, s ->
                val label = (JOB_STEP_LABEL[s] ?: s).let { if (i < cur) "✓ $it" else it }
                val color = when {
                    i < cur -> Palette.ok
                    i == cur -> AccentState.current.primary
                    else -> Palette.muted
                }
                Text(label, color = color, fontSize = 11.5.sp, fontWeight = if (i == cur) FontWeight.SemiBold else null)
                if (i != steps.lastIndex) Text(" ›", color = Palette.border2, fontSize = 11.sp)
            }
        }
    }

    @Composable
    fun JobOverlay() {
        val v = jobView ?: return
        val pct = v.int("percent").coerceIn(0, 100)
        val state = v.str("state")
        Box(
            Modifier.fillMaxSize().background(Palette.scrim).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { jobHidden = true },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth(0.88f).widthIn(max = 780.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.panel)
                    .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                    .padding(18.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* 卡片内点击不关闭（同 JS mask 仅点遮罩关闭） */ },
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(jobTitle, color = Palette.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    LevelBadge(if (state == "queued") "排队中" else "执行中", Level.Info)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$pct%",
                        color = AccentState.current.primary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.size(10.dp))
                    PctBar(pct.toDouble(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                JobSteps(v)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(v.str("phase_label").ifEmpty { "执行中" }, color = Palette.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    if (v.str("detail").isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text(v.str("detail"), color = Palette.muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    val elapsed = ((nowEpochMillis() - v.long("startedAt")) / 1000).coerceAtLeast(0)
                    val chunks = v.int("chunks")
                    Muted(
                        "已用 " + Fmt.uptime(elapsed) +
                            (if (chunks > 1) " · 批次 ${v.int("chunk")}/$chunks" else ""),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(220.dp)
                        .background(Palette.tableSurface, RectangleShape)
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val lines = v.arr("lines").mapNotNull { (it as? JsonPrimitive)?.content }
                    Text(
                        lines.joinToString("\n").ifEmpty { "（等待任务输出…）" },
                        color = Palette.text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("完整日志：" + v.str("log"), modifier = Modifier.weight(1f))
                    GhostBtn(text = "后台运行", onClick = { jobHidden = true })
                    Spacer(Modifier.size(8.dp))
                    PrimaryBtn(text = "完成", enabled = false, onClick = {})
                }
            }
        }
    }

    @Composable
    fun JobPill() {
        val v = jobView ?: return
        val pct = v.int("percent").coerceIn(0, 100)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.padding(16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.panel2)
                    .border(1.dp, Palette.border2, RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { jobHidden = false }
                    .width(230.dp).height(28.dp),
            ) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    drawRect(color = Palette.tableDivider)
                    val w = (pct / 100f) * size.width
                    if (w > 0f) drawRect(color = AccentState.current.primary.copy(alpha = 0.35f), size = Size(w, size.height))
                }
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "$jobTitle $pct%",
                        color = Palette.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    /* ==================================================================== *
     * 页面主体
     * ==================================================================== */
    Box(Modifier.fillMaxSize()) {
        PageScaffold(title = "软件包管理") {
            when {
                sum == null && loadErr == null -> LoadingState()
                sum == null && loadErr != null -> ErrorState(message = loadErr!!, onRetry = {
                    scope.launch { loadSummary() }
                })
                else -> {
                    val s = sum ?: return@PageScaffold

                    /* —— 统计四联卡（JS renderStats L44-L52） —— */
                    GridLayout(columns = 4) {
                        StatTile(
                            label = "已安装",
                            value = "${s.int("installed_active")}",
                            sub = "共 ${s.int("installed_total")} 条记录",
                        )
                        StatTile(
                            label = "可升级",
                            value = "${s.int("upgradable")}",
                            sub = if (s.int("upgradable") > 0) "建议尽快更新" else "已是最新",
                        )
                        StatTile(label = "版本锁定", value = "${s.int("held")}", sub = "apt-mark hold")
                        StatTile(
                            label = "根分区可用",
                            value = s.obj("disk")?.str("avail").orEmpty().ifEmpty { "-" },
                            sub = "缓存 ${s.str("apt_cache_size").ifEmpty { "?" }}",
                        )
                    }

                    /* —— 页签条（JS L28-L36） —— */
                    PanelCard(title = "页签", collapsible = false) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val tabs = listOf(
                                    "search" to "搜索安装", "installed" to "已安装", "upgradable" to "可升级",
                                    "recommended" to "常用推荐", "maintain" to "维护操作", "sources" to "软件源",
                                )
                                tabs.forEach { (id, label) ->
                                    if (tab == id) PrimaryBtn(text = label, onClick = { tab = id; sel.clear() })
                                    else GhostBtn(text = label, onClick = { tab = id; sel.clear() })
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val lock = s.obj("lock")
                                val idx = s.str("index_updated").substringBefore('.')
                                Muted(
                                    if (lock?.bool("busy") == true) "apt 任务进行中：${lock.str("label")}"
                                    else "索引更新于 ${idx.ifEmpty { "未知" }}",
                                )
                                Spacer(Modifier.size(8.dp))
                                GhostBtn(text = "刷新", icon = "refresh", onClick = {
                                    scope.launch {
                                        srcReady = false
                                        recData = null
                                        upRes = null
                                        loadSummary()
                                        reloadTick++
                                    }
                                })
                            }
                        }
                    }

                    if (detail != null) DetailCard()

                    Spacer(Modifier.height(4.dp))
                    when (tab) {
                        "search" -> SearchTab()
                        "installed" -> InstalledTab()
                        "upgradable" -> UpgradableTab()
                        "recommended" -> RecommendedTab()
                        "maintain" -> MaintainTab()
                        "sources" -> SourcesTab()
                    }
                }
            }
        }

        if (jobOn && jobHidden) JobPill()
        if (jobOn && !jobHidden) JobOverlay()
    }
}
