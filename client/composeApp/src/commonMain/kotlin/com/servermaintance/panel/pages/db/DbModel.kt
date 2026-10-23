package com.servermaintance.panel.pages.db

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.safeContent
import com.servermaintance.panel.core.str
import com.servermaintance.panel.platformExporter
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* ------------------------------------------------------------------ *
 * 数据库页共享模型 —— 复刻 databases.js 的常量 / 状态 / 发现逻辑。
 * 端点与字段以 docs/api/page-databases.md + upstream/server/dbadmin.js 为准。
 * ------------------------------------------------------------------ */

data class InstallTarget(val engine: String, val label: String, val hint: String)

object DbMeta {
    val ENGINE_LABEL = mapOf(
        "postgres" to "PostgreSQL",
        "mysql" to "MySQL / MariaDB",
        "sqlite" to "SQLite",
        "db2" to "IBM Db2",
        "redis" to "Redis",
        "mongo" to "MongoDB",
    )
    val ENGINE_KIND = mapOf(
        "postgres" to "sql", "mysql" to "sql", "sqlite" to "sql", "db2" to "sql",
        "redis" to "kv", "mongo" to "doc",
    )
    val ENGINE_ORDER = listOf("postgres", "mysql", "sqlite", "db2", "redis", "mongo")

    /** systemd 单元兜底（服务端给不出时用）。 */
    val SVC = mapOf(
        "postgres" to "postgresql", "mysql" to "mysql", "redis" to "redis-server", "mongo" to "mongod",
    )

    val INSTALL_TARGETS = listOf(
        InstallTarget("postgres", "PostgreSQL", "apt install postgresql"),
        InstallTarget("mysql", "MySQL / MariaDB", "apt install mysql-server"),
        InstallTarget("sqlite", "SQLite", "apt install sqlite3"),
        InstallTarget("redis", "Redis", "apt install redis-server"),
        InstallTarget("mongo", "MongoDB", "添加 MongoDB 官方源并安装 mongodb-org"),
        InstallTarget("db2", "IBM Db2", "添加 IBM 官方源并安装 ibm-db2（体积较大，需接受许可）"),
    )

    val SQL_TABS = listOf(
        "overview" to "总览与监控", "databases" to "数据库管理", "tables" to "表与数据",
        "console" to "SQL 控制台", "insights" to "性能洞察", "objects" to "对象浏览器",
        "roles" to "用户与权限", "backups" to "备份与恢复", "advanced" to "维护与高级",
    )
    val KV_TABS = listOf(
        "overview" to "总览", "keys" to "键空间", "console" to "命令控制台",
        "roles" to "用户与权限 (ACL)", "backups" to "备份与恢复", "advanced" to "配置与维护",
    )
    val DOC_TABS = listOf(
        "overview" to "总览", "databases" to "数据库", "tables" to "集合与文档",
        "console" to "Shell 控制台", "roles" to "用户与权限", "backups" to "备份与恢复",
        "advanced" to "索引与维护",
    )

    fun label(id: String?): String = ENGINE_LABEL[id ?: ""] ?: id ?: ""
    fun kind(id: String?): String = ENGINE_KIND[id ?: ""] ?: "sql"
    fun serviceUnit(engineId: String, hint: String?): String? = hint ?: SVC[engineId]
}

data class DbEngineItem(
    val id: String,
    val name: String,
    val kind: String,
    val active: Boolean,
    val service: String? = null,
    val version: String? = null,
    val packages: List<String> = emptyList(),
    val needsRepo: String? = null,
)

private fun JsonElement.strVal(): String = (this as? JsonPrimitive)?.safeContent() ?: ""

/** 复刻 buildEngineList()（databases.js L56-L67）：采集器 + /db/engines 合并、过滤未安装、按 ENGINE_ORDER 排序。 */
fun buildEngineList(collector: List<JsonElement>, extra: List<JsonElement>): List<DbEngineItem> {
    val byId = LinkedHashMap<String, DbEngineItem>()
    collector.forEach { e ->
        val id = e.str("id")
        if (id.isEmpty()) return@forEach
        if (e.key("installed") != null && !e.bool("installed")) return@forEach   // installed === false 跳过
        byId[id] = DbEngineItem(
            id = id,
            name = DbMeta.label(id),
            kind = DbMeta.kind(id),
            active = e.bool("active"),
            service = e.str("service").ifEmpty { null },
            version = e.str("version").ifEmpty { null },
        )
    }
    extra.forEach { e ->
        val id = e.str("id")
        if (id.isEmpty() || !e.bool("installed")) return@forEach
        val prev = byId[id]
        byId[id] = DbEngineItem(
            id = id,
            name = DbMeta.label(id),
            kind = e.str("kind").ifEmpty { DbMeta.kind(id) },
            active = e.bool("active"),
            service = e.str("service").ifEmpty { null } ?: prev?.service,
            version = prev?.version,
            packages = e.arr("packages").map { it.strVal() },
            needsRepo = e.str("needsRepo").ifEmpty { null },
        )
    }
    return DbMeta.ENGINE_ORDER.mapNotNull { byId[it] }
}

/** 页面状态（复刻 databases.js 的 `S`）。 */
class DbState {
    var engines by mutableStateOf<List<DbEngineItem>>(emptyList())
    var engineHints by mutableStateOf<List<JsonElement>>(emptyList())
    var host by mutableStateOf<JsonObject?>(null)
    var enginesLoading by mutableStateOf(true)

    var engineId by mutableStateOf<String?>(null)
    var tab by mutableStateOf("overview")
    var database by mutableStateOf<String?>(null)
    var table by mutableStateOf<String?>(null)

    var page by mutableStateOf(0)
    var pageSize by mutableStateOf(50)
    var orderBy by mutableStateOf("")
    var orderDir by mutableStateOf("asc")
    var where by mutableStateOf("")

    var tableView by mutableStateOf("data")   // data | structure | ddl

    var kvKey by mutableStateOf<String?>(null)
    var kvPattern by mutableStateOf("*")
    var kvDb by mutableStateOf("0")
    var docDb by mutableStateOf<String?>(null)
    var docColl by mutableStateOf<String?>(null)

    /** 动作完成后 bump → 所有页签的 LaunchedEffect 重新取数（复刻 renderTab()）。 */
    var tick by mutableStateOf(0)

    val engine: DbEngineItem? get() = engines.firstOrNull { it.id == engineId }
    val kind: String get() = DbMeta.kind(engineId)
    val isPg: Boolean get() = engineId == "postgres"
    val isMy: Boolean get() = engineId == "mysql"
    val isSqlite: Boolean get() = engineId == "sqlite"
    val isDb2: Boolean get() = engineId == "db2"
    val isSql: Boolean get() = kind == "sql"

    fun tabs(): List<Pair<String, String>> = when (kind) {
        "kv" -> DbMeta.KV_TABS
        "doc" -> DbMeta.DOC_TABS
        else -> DbMeta.SQL_TABS
    }

    fun switchEngine(id: String) {
        engineId = id
        database = null
        table = null
        page = 0
        orderBy = ""
        where = ""
        tab = "overview"
    }

    fun bump() { tick++ }
}

/** 复刻 refreshEngines()：读 /databases 与 /db/engines（各 10s 缓存）合并引擎列表。 */
suspend fun refreshEngines(st: DbState) {
    val d = runCatching { AppState.api.get("/databases", 10_000) }.getOrNull()
    val ex = runCatching { AppState.api.get("/db/engines", 10_000) }.getOrNull()
    st.host = d as? JsonObject
    val hints = ex?.arr("engines") ?: emptyList()
    st.engineHints = hints
    st.engines = buildEngineList(d?.arr("engines") ?: emptyList(), hints)
    if (st.engines.none { it.id == st.engineId }) st.engineId = st.engines.firstOrNull()?.id
}

/** 引擎启停/安装/错误卡「重新加载」后的统一刷新：清缓存 → 重取引擎 → 页签重载。 */
suspend fun reloadAfterService(st: DbState) {
    runCatching { AppState.api.invalidate("/databases") }
    runCatching { AppState.api.invalidate("/db/") }
    refreshEngines(st)
    st.bump()
}

/**
 * 复刻数据库页的 runAction 组合：
 * - [outputTitle] 非空且响应带 output → `Modal.output`（维护/DDL 类，JS 里 toast:false）
 * - [toast] 默认 true → 成功后 toast `r.output`（JS 默认 toast 行为）
 * - [reload] 成功后延迟 700ms 执行（Actions 内部处理）
 * 失败处理（toast 6s + output 弹窗）由 `Actions.run` 统一完成。
 */
suspend fun runDb(
    path: String,
    body: JsonObject,
    outputTitle: String? = null,
    toast: Boolean = outputTitle == null,
    reload: suspend () -> Unit = {},
): JsonElement? {
    val r = Actions.run(path, body, successToast = null, title = outputTitle, reload = reload)
    if (r != null && toast) {
        val out = r.str("output")
        AppState.toast(if (out.isEmpty()) "操作成功" else out, ToastKind.Ok, 6000)
    }
    return r
}

/** 页签加载失败（复刻 renderTab 的 catch → 错误卡分支）。 */
data class DbFailure(val message: String, val notInstalled: Boolean, val notRunning: Boolean)

private val RE_NOT_INSTALLED = Regex("未安装|未找到")
private val RE_NOT_RUNNING = Regex(
    "未运行|ECONNREFUSED|连接失败|无法连接|No such file|does not exist.*running|not found",
    RegexOption.IGNORE_CASE,
)

/**
 * 把异常转成错误卡数据。**本机常态**：postgres 未启动 / mysql 未安装 → 400 `{error,output}`，
 * `ApiException.message` 即面板 `e.message`（服务端 error 字段）。
 */
fun dbFailureOf(e: Throwable): DbFailure {
    val msg = when (e) {
        is ApiException -> e.message ?: "未知错误"
        else -> e.message ?: e::class.simpleName ?: "未知错误"
    }
    return DbFailure(
        message = msg,
        notInstalled = RE_NOT_INSTALLED.containsMatchIn(msg),
        notRunning = RE_NOT_RUNNING.containsMatchIn(msg),
    )
}

/** query 参数编码（复刻 encodeURIComponent，保持 /db/tables?database= 一致）。 */
fun q(v: String): String = v.encodeURLQueryComponent()

fun jsonStrings(items: Iterable<String>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })

fun JsonElement?.asObjList(): List<JsonObject> = when (this) {
    is JsonArray -> this.filterIsInstance<JsonObject>()
    else -> emptyList()
}

/** 表格单元格文本；JSON null 显示为 NULL。 */
fun JsonElement?.cell(key: String): String {
    val el = (this as? JsonObject)?.get(key) ?: return ""
    return el.asText()
}

fun JsonElement.asText(): String = when (this) {
    is JsonNull -> "NULL"
    is JsonPrimitive -> this.safeContent()
    else -> this.toString()
}

fun JsonElement?.flag(key: String): Boolean {
    if (bool(key)) return true
    return when (str(key).lowercase()) {
        "t", "true", "1", "y", "yes" -> true
        else -> false
    }
}

/** 下载备份/导出文件到本机（复刻 `<a download>`）。 */
suspend fun downloadNamed(name: String) {
    if (name.isEmpty()) return
    val bytes = AppState.api.download("/db/backup/download?name=${q(name)}")
    val path = platformExporter.saveFile(name, bytes)
    if (path != null) AppState.toast("已保存到 $path", ToastKind.Ok)
}
