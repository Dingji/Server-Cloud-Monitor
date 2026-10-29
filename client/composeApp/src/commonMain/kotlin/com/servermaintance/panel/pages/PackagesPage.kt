package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
import com.servermaintance.panel.ui.AdaptiveRow
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

private val ACTION_TITLE  get() = mapOf(
    "install" to L["安装_087db63ab1"], "remove" to L["卸载_200cf1550d"], "purge" to L["彻底卸载_33072f5e11"],
    "upgrade" to L["升级_2e8e70958c"], "full-upgrade" to L["完整升级_05b1a3ac5b"], "autoremove" to L["清理孤立依赖_1a60a375a7"],
    "hold" to L["锁定版本_fd3722179b"], "unhold" to L["解除锁定_eafaffdf18"], "update" to L["更新软件源索引_ab8e1a3dc1"], "clean" to L["清理缓存_a2cf5ddefb"],
)
private val RISKY = setOf("remove", "purge", "autoremove", "full-upgrade")

/** core.js JOB_STEP_LABEL（进度阶段条中文标签）。 */
private val JOB_STEP_LABEL  get() = mapOf(
    "queued" to L["排队中_4dcbbcfa61"], "resolve" to L["解析依赖_834fe27e03"], "plan" to L["制定方案_fabaee1920"], "download" to L["下载软件包_8568baf78c"],
    "unpack" to L["解包_8684a68cf4"], "configure" to L["安装配置_02517d8d8f"], "remove" to L["移除软件包_cba9cf8ecf"], "update" to L["更新索引_b2ee89ac6d"],
    "finish" to L["收尾_7c151dbd71"], "done" to L["完成_33246f6a5e"], "failed" to L["失败_3e3c8068bb"],
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
            if (sum == null) loadErr = e.message ?: L["请求失败_8fdc4112a4"]
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
            searchErr = e.message ?: L["搜索失败_b9786d51c3"]
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
                    throw IllegalStateException(L["任务状态已丢失_服务端可能重启过_请重新发起该_3a4e99bf96"])
                }
                jobView = v
                when (v.str("state")) {
                    "done" -> return v
                    "failed" -> throw IllegalStateException(v.str("error").ifEmpty { L["任务执行失败_97312db4c3"] })
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
            AppState.toast(tr("已有_apt_任务在执行_请等待完成_52d32c3717"), ToastKind.Warn)
            return
        }
        pkgBusy = true
        val title = ACTION_TITLE[action] ?: action
        val t = if (names.size > 1) trf("0_1_个包_108817bcfa", title, names.size) else title
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
                if (id.isEmpty()) throw IllegalStateException(L["任务启动失败_服务端未返回任务_id_500799f8a2"])
                val r = watchJob(id, t)
                val secsEl = r.obj("result")?.key("seconds") ?: r.key("seconds")
                val secs = (secsEl as? JsonPrimitive)?.doubleOrNull
                AppState.toast(
                    title + tr("完成_e982a7473c") + (if (secs != null) "（${secs.toInt()}s）" else ""),
                    ToastKind.Ok, 4000,
                )
                Modal.output(title + L["结果_268f3e1aef"], r.str("output"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = e.message ?: e::class.simpleName ?: L["未知错误_5f76edc5de"]
                /* 受保护包首次被拦 → 显式强制重试（JS L86-L91、L105） */
                if (com.servermaintance.panel.i18n.textHasI18nKey(msg, "受保护_9505edfd06") && !force) {
                    val okc = Modal.confirm(
                        title = L["该操作已被安全策略拦截_b8ca2b42da"],
                        text = msg.substringBefore('\n') +
                            L["服务端已阻止该操作_继续执行可能导致系统无法_265cce6378"],
                        okText = L["我已知晓风险_强制执行_cd4e2f773d"],
                        danger = true,
                    )
                    if (okc) retry = true
                } else {
                    AppState.toast(msg.substringBefore('\n'), ToastKind.Err, 8000)
                    Modal.output(title + L["失败_01d770b029"], msg, isError = true)
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
            AppState.toast((e.message ?: tr("预览失败_d285886c5b")).substringBefore('\n'), ToastKind.Err, 7000)
            return
        }
        val lines = pv.str("output").trim()
        val sb = StringBuilder()
        if (names.isNotEmpty()) sb.appendLine(names.joinToString(L["s_d3bc9a378d"]))
        PLAN_RE.find(lines)?.let { m ->
            sb.appendLine(trf("0_升级_1_新装_2_移除_f730a322df", m.groupValues[1], m.groupValues[2], m.groupValues[3]))
        }
        if (warn.isNotEmpty()) sb.appendLine(warn)
        sb.append(lines.ifEmpty { L["无变更_f98b030e4c"] })
        val okc = Modal.confirm(
            title = (ACTION_TITLE[action] ?: action) + L["变更预览_241ab41868"],
            text = sb.toString(),
            okText = L["执行_28febba225"],
            danger = action in RISKY,
        )
        if (!okc) return
        doAct(action, names)
    }

    suspend fun removeFlow(names: List<String>, rdeps: List<String> = emptyList()) {
        val warn = if (rdeps.isNotEmpty()) {
            L["以下已安装包依赖它_卸载可能连带移除或导致异常_f26237adcd"] + rdeps.take(14).joinToString(", ")
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
            AppState.toast(e.message ?: tr("加载详情失败_02c28cd84c"), ToastKind.Err)
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
                    watchJob(o.str("id"), (ACTION_TITLE[at] ?: at) + L["进行中_a9e306e247"])
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
            instErr = e.message ?: L["加载失败_f6b7a41e56"]
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
            upErr = e.message ?: L["加载失败_f6b7a41e56"]
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
            recErr = e.message ?: L["加载失败_f6b7a41e56"]
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
            srcErr = e.message ?: L["加载失败_f6b7a41e56"]
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
                        label = L["全选本页_b82f2ebbd9"],
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            if (n == 0) {
                Muted(L["勾选后可批量操作_执行前照常预览变更_过程中显示_8b4692abd7"])
                return
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L["已选_17f3287f61"], color = Palette.muted, fontSize = 12.sp)
                Text("$n", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(L["个包_7043df702a"], color = Palette.muted, fontSize = 12.sp)
                val ins = sel.values.count { !it }
                val rem = n - ins
                val act: suspend (String, List<String>) -> Unit = { a, names ->
                    if (names.isEmpty()) AppState.toast(tr("没有可执行该操作的包_34484f1862"), ToastKind.Warn)
                    else if (names.size > 500) AppState.toast(tr("一次最多_500_个包_请分批选择_6bbae64fda"), ToastKind.Warn)
                    else previewAndAct(a, names)
                }
                if (mode == "installed") {
                    DangerBtn(text = L["批量卸载_n_65d9c20918"], onClick = {
                        scope.launch { act("remove", sel.filterValues { it }.keys.toList()) }
                    })
                } else {
                    if (ins > 0) {
                        PrimaryBtn(text = L["批量安装_ins_d21155177e"], onClick = {
                            scope.launch { act("install", sel.filterValues { !it }.keys.toList()) }
                        })
                    }
                    if (rem > 0) {
                        DangerBtn(text = L["批量卸载_rem_3ccf4b0014"], onClick = {
                            scope.launch { act("remove", sel.filterValues { it }.keys.toList()) }
                        })
                    }
                }
                GhostBtn(text = L["取消选择_f02e943954"], onClick = { sel.clear() })
            }
        }
    }

    /* ---------------- 详情面板（JS showDetail L196-L240） ---------------- */
    @Composable
    fun DetailCard() {
        val d = detail ?: return
        var expanded by remember(d) { mutableStateOf(false) }
        val prot = when (d.str("protected")) {
            "critical" -> L["系统关键组件_434e1cae8b"]
            "protected" -> L["面板依赖_10de20077c"]
            else -> ""
        }
        PanelCard(
            title = d.str("name"),
            collapsible = false,
            right = {
                if (d.bool("installed")) {
                    GhostBtn(text = L["锁定版本_fd3722179b"], onClick = { scope.launch { doAct("hold", listOf(d.str("name"))) } })
                    GhostBtn(text = L["解除锁定_eafaffdf18"], onClick = { scope.launch { doAct("unhold", listOf(d.str("name"))) } })
                    DangerBtn(text = L["卸载_200cf1550d"], onClick = {
                        scope.launch { removeFlow(listOf(d.str("name")), d.arr("reverse_deps").mapNotNull { (it as? JsonPrimitive)?.content }) }
                    })
                } else {
                    PrimaryBtn(text = L["安装_087db63ab1"], onClick = { scope.launch { previewAndAct("install", listOf(d.str("name"))) } })
                }
                GhostBtn(text = L["关闭_6c14bd7f6f"], onClick = { detail = null })
            },
        ) {
            if (prot.isNotEmpty()) {
                LevelBadge(prot, if (prot == L["系统关键组件_434e1cae8b"]) Level.Bad else Level.Warn)
                Spacer(Modifier.height(8.dp))
            }
            GridLayout(columns = 2, spacing = 14.dp) {
                Column {
                    KvRow(L["版本_989d1affa0"], d.str("version").ifEmpty { "-" }, mono = true)
                    KvRow(
                        L["候选_0461e4db19"],
                        d.str("candidate").ifEmpty { "-" } + if (d.bool("pinned")) L["已固定_46f9ebb3cb"] else "",
                        mono = true,
                        tint = if (d.bool("pinned")) Palette.info else null,
                    )
                    KvRow(
                        L["已装版本_9bdb334d0c"],
                        when {
                            !d.bool("installed") -> L["未安装_6f7dc945ae"]
                            d.bool("upgradable") -> d.str("installed_version") + L["有新版本_f329c95a7b"]
                            else -> d.str("installed_version")
                        },
                        mono = true,
                        tint = when {
                            !d.bool("installed") -> Palette.muted
                            d.bool("upgradable") -> Palette.warn
                            else -> null
                        },
                    )
                    KvRow(L["分组_97d8a6c05b"], d.str("section").ifEmpty { "-" })
                    KvRow(
                        L["安装大小_85c8cf1c58"],
                        d.str("size") + if (d.int("file_count") > 0) trf("0_个文件_81d82139e8", d.int("file_count")) else "",
                    )
                    KvRow(L["主页_25c408b30e"], d.str("homepage").ifEmpty { "-" })
                }
                Column {
                    Muted(L["摘要_46d4c1b4e4"])
                    Spacer(Modifier.height(3.dp))
                    Text(d.str("summary").ifEmpty { "-" }, color = Palette.text, fontSize = 12.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    val depN = d.arr("depends").size
                    val recN = d.arr("recommends").size
                    val revN = d.arr("reverse_deps").size
                    Muted(trf("依赖_0_推荐依赖_1_反向依赖_2507ad6a45", depN, recN, revN))
                    if (revN > 0) {
                        val revs = d.arr("reverse_deps").mapNotNull { (it as? JsonPrimitive)?.content }
                        Muted(L["被依赖_45c402e800"] + revs.take(10).joinToString(L["s_d3bc9a378d"]) + if (revN > 10) L["s_978ed47b35"] else "")
                    }
                    Spacer(Modifier.height(6.dp))
                    GhostBtn(text = if (expanded) L["收起完整说明_fedbce873b"] else L["完整说明与依赖列表_0cf5ceed2a"], onClick = { expanded = !expanded })
                    if (expanded) {
                        Spacer(Modifier.height(6.dp))
                        PreBox(
                            d.str("description") +
                                L["依赖_2ebf56c207"] + (d.arr("depends").mapNotNull { (it as? JsonPrimitive)?.content }
                                .joinToString("\n").ifEmpty { "-" }) +
                                L["推荐_e8fcb9bb63"] + (d.arr("recommends").mapNotNull { (it as? JsonPrimitive)?.content }
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
        PanelCard(title = L["搜索软件包_a162c7cf8d"]) {
            AdaptiveRow(spacing = 8.dp) {
                SearchField(
                    value = q,
                    onValueChange = { q = it },
                    modifier = Modifier.span(1f),
                    placeholder = L["搜索软件包_名称或描述_如_nginx_ht_320fde8b40"],
                )
                val options = listOf(L["全部源_全局搜索_252f184452"]) + srcList.map { srcDisplay(it.uri) }
                SelectField(
                    value = if (srcUri.isEmpty()) L["全部源_全局搜索_252f184452"] else srcList.firstOrNull { it.uri == srcUri }?.let { srcDisplay(it.uri) } ?: L["全部源_全局搜索_252f184452"],
                    options = options,
                    onValueChange = { v ->
                        srcUri = if (v == L["全部源_全局搜索_252f184452"]) "" else srcList.firstOrNull { srcDisplay(it.uri) == v }?.uri ?: ""
                    },
                    modifier = Modifier.spanFixed(280.dp),
                )
                PrimaryBtn(text = L["搜索_f04090805c"], icon = "search", onClick = { scope.launch { doSearchNow() } })
            }
            Spacer(Modifier.height(8.dp))
            if (srcUri.isEmpty()) {
                Muted(trf("搜索范围_全部已启用源_共_0_个源_按名_b786d5dc17", srcList.size))
            } else {
                val one = srcList.firstOrNull { it.uri == srcUri }
                Muted(
                    trf("仅搜索源_0_843fdd9250", srcUri) +
                        (one?.suites?.takeIf { it.isNotEmpty() }?.let { "（${it.joinToString(" ")}）" } ?: "") +
                        L["的软件包_若结果为空_请到_软件源_页更新该源_8fb011f496"],
                )
            }
        }

        when {
            q.isBlank() -> PanelCard(title = L["开始搜索_8fdc065eda"], collapsible = false) {
                Muted(L["输入关键词查找软件包_或指定某个源后仅在该源内搜_f4777e948a"])
            }
            searching && searchRes == null -> LoadingState()
            searchErr != null && searchRes == null -> ErrorState(message = searchErr!!, onRetry = {
                scope.launch { doSearchNow() }
            })
            searchRes == null -> EmptyState(L["暂无搜索结果_8f8274c754"])
            else -> {
                val d = searchRes ?: return
                val total = d.int("total")
                if (total == 0) {
                    PanelCard(title = L["搜索结果_0_99e3cf5dae"], collapsible = false) {
                        Muted(trf("0_未找到与_1_匹配的软件包_191b61f1fa", if (srcUri.isNotEmpty()) L["该源中_2d239ff06c"] else "", q))
                    }
                    return
                }
                val scanned = d.int("scanned_files")
                val title = buildString {
                    append(L["搜索结果_total_71fe2c69d1"])
                    if (srcUri.isNotEmpty()) append(L["指定源_c1701e5ea0"])
                    if (scanned > 0) append(trf("已扫描_0_个索引文件_9909c34fb8", scanned))
                    if (d.bool("truncated")) append(L["已截断_61c54972d5"])
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
                            TableCol<JsonObject>(L["包名_03c2d07a24"], weight = 1.5f, mono = true) { Mono12(it.str("name")) },
                            TableCol<JsonObject>(L["版本_989d1affa0"], weight = 1.2f, mono = true) {
                                Mono12(it.str("version").ifEmpty { "-" }, color = Palette.muted)
                            },
                            TableCol<JsonObject>(L["描述_412f54dc38"], weight = 2.4f) {
                                Text(it.str("description").take(140), fontSize = 12.sp, color = Palette.muted)
                            },
                            if (srcUri.isEmpty()) {
                                TableCol<JsonObject>(L["已占_028e892f8e"], weight = 0.8f) {
                                    Text(kbRaw(it.key("size_kb")), fontSize = 12.sp)
                                }
                            } else {
                                TableCol<JsonObject>(L["分组_97d8a6c05b"], weight = 0.9f) {
                                    Text(it.str("section").ifEmpty { "-" }, fontSize = 12.sp, color = Palette.muted)
                                }
                            },
                            TableCol<JsonObject>(L["状态_62e951a692"], weight = 0.8f) {
                                if (it.bool("installed")) LevelBadge(L["已安装_eb88ff57c9"], Level.Ok)
                                else LevelBadge(L["未安装_6f7dc945ae"], Level.Muted)
                            },
                            TableCol<JsonObject>(L["操作_f3ea6d345e"], weight = 1.6f, minWidth = 160.dp) { r ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    GhostBtn(text = L["详情_4f55ee1e68"], onClick = { scope.launch { showDetail(r.str("name")) } })
                                    if (r.bool("installed")) {
                                        DangerBtn(text = L["卸载_200cf1550d"], onClick = { scope.launch { removePkg(r.str("name")) } })
                                    } else {
                                        PrimaryBtn(text = L["安装_087db63ab1"], onClick = {
                                            scope.launch { previewAndAct("install", listOf(r.str("name"))) }
                                        })
                                    }
                                }
                            },
                        ),
                        rows = rows,
                        rowKey = { it.str("name") },
                        emptyText = L["未找到匹配的软件包_f031dea558"],
                    )
                }
            }
        }
    }

    /* ---------------- 已安装页签 ---------------- */
    @Composable
    fun InstalledTab() {
        val sortLabels = listOf(L["按名称_0ea96767da"] to "name", L["按大小_ee9a3bcc8e"] to "size_kb", L["按分组_008e3a2b0b"] to "section", L["按版本_f04ae00cab"] to "version")
        val dirLabels = listOf(L["升序_c0276ec9a7"] to "asc", L["降序_a4c38f3ce2"] to "desc")
        val sections = instRes?.arr("sections").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        PanelCard(title = L["过滤与排序_77a35ebb60"], collapsible = false) {
            AdaptiveRow(spacing = 8.dp) {
                SearchField(
                    value = iq,
                    onValueChange = { iq = it },
                    modifier = Modifier.span(1f),
                    placeholder = L["过滤已安装包_名称或分组_7ecdc6b62e"],
                )
                // 三个下拉：桌面并排（170/120/100dp 一行放得下），手持端叠成三行整宽
                AdaptiveRow(spacing = 8.dp) {
                    SelectField(
                        value = iSection,
                        options = listOf(L["全部分组_eb8d23fedf"]) + sections,
                        onValueChange = { iSection = if (it == L["全部分组_eb8d23fedf"]) "" else it; iPage = 0 },
                        modifier = Modifier.spanFixed(170.dp),
                    )
                    SelectField(
                        value = sortLabels.firstOrNull { it.second == iSort }?.first ?: L["按名称_0ea96767da"],
                        options = sortLabels.map { it.first },
                        onValueChange = { v -> iSort = sortLabels.firstOrNull { it.first == v }?.second ?: "name"; iPage = 0 },
                        modifier = Modifier.spanFixed(120.dp),
                    )
                    SelectField(
                        value = dirLabels.firstOrNull { it.second == iDir }?.first ?: L["升序_c0276ec9a7"],
                        options = dirLabels.map { it.first },
                        onValueChange = { v -> iDir = dirLabels.firstOrNull { it.first == v }?.second ?: "asc" },
                        modifier = Modifier.spanFixed(100.dp),
                    )
                }
            }
        }
        when {
            instLoading && instRes == null -> LoadingState()
            instErr != null && instRes == null -> ErrorState(message = instErr!!, onRetry = { reloadTick++ })
            instRes == null -> EmptyState(L["暂无数据_b246458f20"])
            else -> {
                val d = instRes ?: return
                val total = d.int("total")
                val limit = d.int("limit").coerceAtLeast(1)
                val offset = d.int("offset")
                val pages = kotlin.math.max(1, kotlin.math.ceil(total / limit.toDouble()).toInt())
                val rows = d.arr("rows").filterIsInstance<JsonObject>()
                PanelCard(
                    title = L["已安装_total_438379cb60"],
                    right = {
                        Muted(
                            trf("第_0_1_条_d43fae8803", if (total > 0) offset + 1 else 0, kotlin.math.min(offset + limit, total)),
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
                            TableCol<JsonObject>(L["包名_03c2d07a24"], weight = 1.6f, mono = true) { Mono12(it.str("name")) },
                            TableCol<JsonObject>(L["版本_989d1affa0"], weight = 1.4f, mono = true) {
                                Mono12(it.str("version"), color = Palette.muted)
                            },
                            TableCol<JsonObject>(L["分组_97d8a6c05b"], weight = 0.9f) {
                                Text(it.str("section"), fontSize = 12.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(L["大小_fd20702c73"], weight = 0.7f, align = androidx.compose.ui.text.style.TextAlign.End) {
                                Text(it.str("size"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(L["状态_62e951a692"], weight = 0.7f) {
                                val st = it.str("status")
                                if (st.startsWith("ii")) LevelBadge(L["已装_3647e1394b"], Level.Ok)
                                else LevelBadge(st.trim().ifEmpty { "?" }, Level.Muted)
                            },
                            TableCol<JsonObject>(L["操作_f3ea6d345e"], weight = 1.4f, minWidth = 150.dp) { r ->
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    GhostBtn(text = L["详情_4f55ee1e68"], onClick = { scope.launch { showDetail(r.str("name")) } })
                                    DangerBtn(text = L["卸载_200cf1550d"], onClick = { scope.launch { removePkg(r.str("name")) } })
                                }
                            },
                        ),
                        rows = rows,
                        rowKey = { it.str("name") },
                        emptyText = L["没有匹配的已安装包_3aa1636e48"],
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostBtn(text = L["上一页_b41561d807"], enabled = iPage > 0, onClick = { iPage-- })
                        Text(
                            " ${iPage + 1} / $pages ",
                            color = Palette.muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        GhostBtn(text = L["下一页_67a246a344"], enabled = iPage + 1 < pages, onClick = { iPage++ })
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
            upRes == null -> EmptyState(L["暂无数据_b246458f20"])
            else -> {
                val d = upRes ?: return
                val total = d.int("total")
                val rows = d.arr("rows").filterIsInstance<JsonObject>()
                PanelCard(
                    title = L["可升级_total_12792da8ef"],
                    right = {
                        PrimaryBtn(text = L["全部安全升级_31ec522e55"], icon = "upload", onClick = {
                            scope.launch { previewAndAct("upgrade", emptyList()) }
                        })
                        GhostBtn(text = L["完整升级_含移除_3bed74b0f0"], onClick = {
                            scope.launch {
                                previewAndAct(
                                    "full-upgrade", emptyList(),
                                    L["完整升级可能移除与新版本冲突的软件包_请仔细核对_50ce8ea44d"],
                                )
                            }
                        })
                    },
                ) {
                    if (total == 0) {
                        Muted(L["所有软件包均为最新版本_ee413e3b4c"])
                    } else {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(L["包名_03c2d07a24"], weight = 1.4f, mono = true) { Mono12(it.str("name")) },
                                TableCol<JsonObject>(L["当前版本_46e66f6321"], weight = 1.2f, mono = true) {
                                    Mono12(it.str("current"), color = Palette.muted)
                                },
                                TableCol<JsonObject>(L["目标版本_fda86a2031"], weight = 1.2f, mono = true) {
                                    Mono12(it.str("candidate"), color = Palette.ok)
                                },
                                TableCol<JsonObject>(L["来源_c63f79e636"], weight = 0.8f) {
                                    Text(it.str("suite"), fontSize = 12.sp, color = Palette.muted)
                                },
                                TableCol<JsonObject>(L["操作_f3ea6d345e"], weight = 0.8f, minWidth = 76.dp) {
                                    PrimaryBtn(text = L["升级_2e8e70958c"], onClick = {
                                        scope.launch { previewAndAct("upgrade", listOf(it.str("name"))) }
                                    })
                                },
                            ),
                            rows = rows,
                            rowKey = { it.str("name") },
                        )
                    }
                }
                PanelCard(title = L["说明_26670dda42"], collapsible = true) {
                    Muted(L["安全升级_只做向上兼容的升级_不卸载其它包_841c2b3607"])
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
                    r.bool("held") -> LevelBadge(L["已锁定_3cc7a5af4c"], Level.Warn)
                    r.bool("installed") -> LevelBadge(L["已安装_eb88ff57c9"], Level.Ok)
                    else -> LevelBadge(L["未安装_6f7dc945ae"], Level.Muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            Muted(r.str("desc"))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (r.bool("installed")) {
                    GhostBtn(text = L["详情_4f55ee1e68"], onClick = { scope.launch { showDetail(r.str("name")) } })
                    DangerBtn(text = L["卸载_200cf1550d"], onClick = { scope.launch { removePkg(r.str("name")) } })
                } else {
                    PrimaryBtn(text = L["安装_087db63ab1"], icon = "download", onClick = {
                        scope.launch { previewAndAct("install", listOf(r.str("name"))) }
                    })
                    GhostBtn(text = L["详情_4f55ee1e68"], onClick = { scope.launch { showDetail(r.str("name")) } })
                }
            }
        }
    }

    @Composable
    fun RecommendedTab() {
        when {
            recData == null && recErr == null -> LoadingState()
            recErr != null && recData == null -> ErrorState(message = recErr!!, onRetry = { recData = null; reloadTick++ })
            recData == null -> EmptyState(L["暂无数据_b246458f20"])
            else -> {
                val d = recData ?: return
                val cats = d.arr("categories").filterIsInstance<JsonObject>()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Muted(trf("共_0_个常用包_已装_1_2_e967a843a6", d.int("total"), d.int("installed_total"), d.int("category_count")))
                    SearchField(
                        value = recQ,
                        onValueChange = { recQ = it },
                        modifier = Modifier.width(190.dp),
                        placeholder = L["在推荐中过滤_655cfb330e"],
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (recCat == "all") PrimaryBtn(text = trf("全部_0_910e2f37bc", d.int("total")), onClick = { recCat = "all" })
                    else GhostBtn(text = trf("全部_0_910e2f37bc", d.int("total")), onClick = { recCat = "all" })
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
                    PanelCard(title = L["推荐包_d834496b57"], collapsible = false) { Muted(L["没有匹配的包_f28a3dc6a5"]) }
                }
                groups.forEach { (c, rows) ->
                    PanelCard(
                        title = c.str("label"),
                        right = { Muted(trf("0_个_已装_1_ab8c2e6db4", rows.size, rows.count { it.bool("installed") })) },
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
            .ifEmpty { L["服务端日志目录_037c3d3cda"] }
        GridLayout(columns = 2) {
            PanelCard(title = L["软件源索引_376afe36ab"]) {
                Muted(L["从镜像站拉取最新软件包列表_安装新包前建议先刷新_18f22cecb1"])
                Spacer(Modifier.height(4.dp))
                Muted(trf("当前索引更新于_0_1c82f67640", s.str("index_updated").ifEmpty { L["未知_d9c32a4c3d"] }))
                Spacer(Modifier.height(8.dp))
                PrimaryBtn(text = L["更新索引_b2ee89ac6d"], onClick = { scope.launch { doAct("update", emptyList()) } })
            }
            PanelCard(title = L["缓存清理_7dc4dae50d"]) {
                Muted(trf("删除已下载的_deb_安装包_释放_0_不_dc8e1f42c6", s.str("apt_cache_size").ifEmpty { "?" }))
                Spacer(Modifier.height(8.dp))
                GhostBtn(text = L["清理_apt_缓存_55c020f574"], onClick = { scope.launch { doAct("clean", emptyList()) } })
            }
            PanelCard(title = L["孤立依赖清理_3088424930"]) {
                Muted(L["移除不再被需要的自动安装依赖_apt_autor_267620f018"])
                Spacer(Modifier.height(8.dp))
                DangerBtn(text = L["查看可清理项_cc9ca0dc55"], onClick = {
                    scope.launch {
                        try {
                            val d = AppState.api.get("/pkg/autoremove", 150000)
                            val out = d.str("output")
                            Modal.output(L["可清理的孤立依赖_apt_get_s_auto_25a43a9abb"], out)
                            if (Regex("\\d+ to remove|REMOVED").containsMatchIn(out)) {
                                val okc = Modal.confirm(
                                    title = L["执行清理_bebeb0649b"],
                                    text = L["确认执行_apt_autoremove_pu_54a12414d7"],
                                    okText = L["执行_28febba225"],
                                    danger = true,
                                )
                                if (okc) doAct("autoremove", emptyList())
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            AppState.toast(e.message ?: tr("查询失败_381ea3ac5e"), ToastKind.Err)
                        }
                    }
                })
            }
            PanelCard(title = L["版本锁定_f00c209863"]) {
                Muted(
                    trf("锁定的包不会被自动升级_当前_0_个_e9feae0738", s.int("held")) +
                        (if (holds.isNotEmpty()) "：$holds" else "") + L["s_f92669081d"],
                )
                Spacer(Modifier.height(8.dp))
                if (holdList.isNotEmpty()) {
                    GhostBtn(text = L["全部解除锁定_fc0465ac55"], onClick = {
                        scope.launch {
                            val okc = Modal.confirm(
                                title = L["解除锁定_eafaffdf18"],
                                text = L["解除_holds_的版本锁定_这些包将恢复自动_1fb2f54761"],
                                okText = L["解除_ec7ae06b09"],
                            )
                            if (okc) doAct("unhold", holdList)
                        }
                    })
                } else {
                    Muted(L["当前没有锁定的包_e812929549"])
                }
            }
        }
        PanelCard(title = L["操作日志_f4bc877cd2"]) {
            Muted(L["每次变更的完整_apt_输出保存在服务器_lo_737e75d3ca"])
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
                if (isCur) LevelBadge(L["当前_25e74dceac"], Level.Ok)
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
                    text = lat[m.str("id")] ?: L["测速_304d8c0081"],
                    onClick = {
                        scope.launch {
                            try {
                                val r = AppState.api.get("/pkg/test-mirror?url=${urlEnc(m.str("url"))}", 25000)
                                lat[m.str("id")] = if (r.bool("reachable")) "${r.int("latency_ms")} ms" else L["不可达_358801edaa"]
                            } catch (_: Throwable) {
                                lat[m.str("id")] = tr("失败_3e3c8068bb")
                            }
                        }
                    },
                )
                if (!isCur) {
                    PrimaryBtn(text = L["切换到此源_6a51392f3d"], onClick = {
                        scope.launch {
                            val okc = Modal.confirm(
                                title = L["切换软件源_1d685b5c20"],
                                text = trf("从_0_切换到_94f8b2509a", cur.str("uri").ifEmpty { L["当前_25e74dceac"] }) +
                                    "${m.str("name")}  ${m.str("url")}\n" +
                                    "${m.str("region")} · ${m.str("desc")}\n\n" +
                                    L["将自动备份当前源文件并执行_apt_get_up_0276b8cab1"],
                                okText = L["切换并刷新索引_90b6357c92"],
                            )
                            if (!okc) return@launch
                            try {
                                val r = AppState.api.post("/pkg/switch-mirror", jsonObjectOf("mirror" to m.str("id")))
                                AppState.toast(trf("已切换到_0_f0c31c31dc", m.str("name")), ToastKind.Ok, 5000)
                                Modal.output(
                                    L["切换软件源_1d685b5c20"],
                                    r.str("output") + (r.str("backup").let { if (it.isEmpty()) "" else trf("原配置备份_0_45dc9a773d", it) }),
                                )
                                srcReady = false
                                reloadTick++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                AppState.toast((e.message ?: tr("切换失败_9e2fdc4fee")).substringBefore('\n'), ToastKind.Err, 8000)
                                Modal.output(L["切换失败_已回滚_c0cfb2c6b5"], e.message ?: "", isError = true)
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
            srcErr != null -> PanelCard(title = L["当前系统不支持软件源切换_718bf56e6b"], collapsible = false) {
                Muted(srcErr!!)
                Spacer(Modifier.height(6.dp))
                Muted(L["镜像切换是_debian_ubuntu_专有功能_73bbd10e7d"])
            }
            else -> {
                val d = srcData ?: return
                val cur = d.obj("current") ?: JsonObject(emptyMap())
                val bk = bkData ?: JsonObject(emptyMap())

                PanelCard(
                    title = L["当前镜像_ed71a792a7"],
                    right = { GhostBtn(text = L["刷新状态_7cc7f07a2c"], icon = "refresh", onClick = { reloadTick++ }) },
                ) {
                    GridLayout(columns = 2, spacing = 14.dp) {
                        Column {
                            KvRow(L["主仓库_6ab193cdf9"], trf("0_1_b245f58149", cur.str("name").ifEmpty { L["未识别_face982a4a"] }, cur.str("uri").ifEmpty { "-" }))
                            KvRow(L["发行版_6ef5fc415c"], d.str("codename").ifEmpty { "-" }, mono = true)
                            KvRow(
                                L["安全更新_d02224fdc8"],
                                cur.str("security_mirror").ifEmpty { L["同主仓库_01a7130455"] } +
                                    (cur.str("security_uri").takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""),
                            )
                            KvRow(L["源文件_be43b936dd"], cur.str("file").ifEmpty { "-" }, mono = true)
                        }
                        Column {
                            Muted(L["切换镜像会先备份源文件_改写后立即执行_ap_b31cb7b626"])
                            Muted(L["切换成功后会清理旧镜像残留索引_避免搜索结果_3a16b0a93b"])
                            Muted(L["阿里云_ecs_建议使用_阿里云_内网源_beb7548e7b"])
                        }
                    }
                }

                val mirrors = d.arr("mirrors").filterIsInstance<JsonObject>()
                PanelCard(
                    title = L["可选镜像_67237d63bf"],
                    right = {
                        GhostBtn(text = L["测速全部_42afec91d1"], icon = "activity", enabled = !testingAll, onClick = {
                            scope.launch {
                                testingAll = true
                                try {
                                    for (m in mirrors) {
                                        try {
                                            val r = AppState.api.get("/pkg/test-mirror?url=${urlEnc(m.str("url"))}", 25000)
                                            lat[m.str("id")] = if (r.bool("reachable")) "${r.int("latency_ms")} ms" else L["不可达_358801edaa"]
                                        } catch (_: Throwable) {
                                            lat[m.str("id")] = tr("失败_3e3c8068bb")
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
                    PanelCard(title = trf("已启用的源文件_0_7a8ed113e1", files.size)) {
                        if (files.isEmpty()) {
                            Muted(L["未发现源文件_371b4f4aa9"])
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
                                                    (if (signed.isNotEmpty()) trf("签名_0_e57efd3ddc", signed) else ""),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    PanelCard(title = trf("源文件备份_0_4a529aae31", bk.int("total"))) {
                        Muted(trf("每次切换镜像前自动备份_路径_0_4331441441", bk.str("dir").ifEmpty { "-" }))
                        Spacer(Modifier.height(6.dp))
                        val items = bk.arr("items").mapNotNull { (it as? JsonPrimitive)?.content }
                        if (items.isNotEmpty()) PreBox(items.joinToString("\n"), maxHeight = 220.dp)
                        else Muted(L["暂无备份_d5392bb044"])
                        Spacer(Modifier.height(8.dp))
                        Muted(L["如需回滚_可通过_web_终端把备份文件复制回_91d4cbe18f"])
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
                val label = (JOB_STEP_LABEL[s] ?: s).let { if (i < cur) L["it_2b79a5d69f"] else it }
                val color = when {
                    i < cur -> Palette.ok
                    i == cur -> AccentState.current.primary
                    else -> Palette.muted
                }
                Text(label, color = color, fontSize = 11.5.sp, fontWeight = if (i == cur) FontWeight.SemiBold else null)
                if (i != steps.lastIndex) Text(L["s_0c1dcbdef8"], color = Palette.border2, fontSize = 11.sp)
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
                    LevelBadge(if (state == "queued") L["排队中_4dcbbcfa61"] else L["执行中_1f425b6bf0"], Level.Info)
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
                    Text(v.str("phase_label").ifEmpty { L["执行中_1f425b6bf0"] }, color = Palette.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    if (v.str("detail").isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text(v.str("detail"), color = Palette.muted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    val elapsed = ((nowEpochMillis() - v.long("startedAt")) / 1000).coerceAtLeast(0)
                    val chunks = v.int("chunks")
                    Muted(
                        L["已用_11ab4c52e3"] + Fmt.uptime(elapsed) +
                            (if (chunks > 1) trf("批次_0_1_a517254bd8", v.int("chunk"), chunks) else ""),
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
                        lines.joinToString("\n").ifEmpty { L["等待任务输出_9d3133ea50"] },
                        color = Palette.text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted(L["完整日志_4a1b1f9673"] + v.str("log"), modifier = Modifier.weight(1f))
                    GhostBtn(text = L["后台运行_d8478a3802"], onClick = { jobHidden = true })
                    Spacer(Modifier.size(8.dp))
                    PrimaryBtn(text = L["完成_33246f6a5e"], enabled = false, onClick = {})
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
        PageScaffold(title = L["软件包管理_dc0eed4bee"]) {
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
                            label = L["已安装_eb88ff57c9"],
                            value = "${s.int("installed_active")}",
                            sub = trf("共_0_条记录_41eef6780b", s.int("installed_total")),
                        )
                        StatTile(
                            label = L["可升级_546591fd74"],
                            value = "${s.int("upgradable")}",
                            sub = if (s.int("upgradable") > 0) L["建议尽快更新_4434051095"] else L["已是最新_5985ee9a3c"],
                        )
                        StatTile(label = L["版本锁定_f00c209863"], value = "${s.int("held")}", sub = L["apt_mark_hold_2a881a84a6"])
                        StatTile(
                            label = L["根分区可用_0fff0a9d78"],
                            value = s.obj("disk")?.str("avail").orEmpty().ifEmpty { "-" },
                            sub = trf("缓存_0_dfe2b5a86d", s.str("apt_cache_size").ifEmpty { "?" }),
                        )
                    }

                    /* —— 页签条（JS L28-L36） —— */
                    PanelCard(title = L["页签_46c7c1c3b1"], collapsible = false) {
                        AdaptiveRow(spacing = 8.dp) {
                            Row(
                                Modifier.span(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val tabs = listOf(
                                    "search" to L["搜索安装_7bede136f7"], "installed" to L["已安装_eb88ff57c9"], "upgradable" to L["可升级_546591fd74"],
                                    "recommended" to L["常用推荐_f9420928eb"], "maintain" to L["维护操作_7a7b416bde"], "sources" to L["软件源_d6d000ca92"],
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
                                    if (lock?.bool("busy") == true) trf("apt_任务进行中_0_6028d2fbd3", lock.str("label"))
                                    else trf("索引更新于_0_3df853c52d", idx.ifEmpty { L["未知_d9c32a4c3d"] }),
                                )
                                Spacer(Modifier.size(8.dp))
                                GhostBtn(text = L["刷新_38108eaa1d"], icon = "refresh", onClick = {
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
