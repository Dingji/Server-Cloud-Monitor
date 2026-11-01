package com.servermaintance.panel.core
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.trf

import com.servermaintance.panel.platformStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.IO
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/* ==================================================================== *
 * NoteBox —— 服务器上的「笔记 + 待办」独立服务（Flask + SQLite）。
 *
 * 与监控面板不同，NoteBox 用 **session cookie** 认证：
 *   POST /api/login {username, password} -> Set-Cookie: session=…
 *   之后所有请求带 `Cookie: session=…`；任意 401 = 会话过期。
 *
 * 服务默认跑在 `http://<面板主机>:13345`（公网可达，ufw 已放行）。
 * API 契约以服务器上 notebox 的 Python 源码为准。
 * ==================================================================== */

/** NoteBox 连接配置（与面板配置独立存储、独立会话）。 */
data class NoteBoxConfig(
    val url: String = "",
    val user: String = "",
    /** `session=…` cookie 原样保存；空 = 未登录。 */
    val session: String = "",
    val rememberPassword: Boolean = false,
    val password: String = "",
) {
    fun normalizedUrl(): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u
        if (u.endsWith("/index.html")) u = u.substringBeforeLast("/index.html")
        return u.trimEnd('/')
    }

    val isLoggedIn: Boolean get() = session.isNotBlank()
    fun forgetSession() = copy(session = "")
}

object NoteBoxStore {

    private const val K_URL = "notebox_url"
    private const val K_USER = "notebox_user"
    private const val K_SESSION = "notebox_session"
    private const val K_REMEMBER_PW = "notebox_remember_pw"
    private const val K_PASSWORD = "notebox_password"

    /** 与面板同主机、独立端口（nginx 未反代，NoteBox 直接监听 13345）。 */
    const val DEFAULT_URL = "http://127.0.0.1:9000/panel/notebox"

    fun load(): NoteBoxConfig = NoteBoxConfig(
        url = platformStore.get(K_URL) ?: DEFAULT_URL,
        user = platformStore.get(K_USER) ?: "",
        session = platformStore.get(K_SESSION) ?: "",
        rememberPassword = platformStore.get(K_REMEMBER_PW) == "1",
        password = platformStore.get(K_PASSWORD) ?: "",
    )

    fun save(cfg: NoteBoxConfig) {
        platformStore.put(K_URL, cfg.normalizedUrl())
        platformStore.put(K_USER, cfg.user)
        platformStore.put(K_SESSION, cfg.session)
        platformStore.put(K_REMEMBER_PW, if (cfg.rememberPassword) "1" else "0")
        if (cfg.rememberPassword) platformStore.put(K_PASSWORD, cfg.password)
        else platformStore.remove(K_PASSWORD)
    }
}

/**
 * NoteBox REST 客户端。与 [PanelApi] 平行的独立实例（不同 origin、不同认证）。
 * 错误契约同面板：失败 `{error}`，401 抛 `isAuth=true`。
 */
class NoteBoxApi(private val config: () -> NoteBoxConfig) {

    val http = HttpClient(createEngine()) {
        engine { configureHttpEngine(this) }
        install(ContentNegotiation) { json(Json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    private fun urlOf(path: String): String {
        val base = config().normalizedUrl()
        if (base.isEmpty()) throw ApiException(L["notebox_地址未配置_3d37258011"], 0)
        return base + path
    }

    private fun HttpRequestBuilder.auth() {
        val c = config().session
        if (c.isNotBlank()) header(HttpHeaders.Cookie, c)
    }

    private suspend fun parseError(path: String, code: Int, body: String): ApiException {
        val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val msg = parsed?.let { (it["error"] as? JsonPrimitive)?.safeContent() }
        return ApiException(
            message = if (msg.isNullOrBlank()) com.servermaintance.panel.i18n.trf("common.http_code", code) else msg,
            code = code,
            isAuth = code == 401,
        )
    }

    private suspend fun HttpResponse.textOrEmpty(): String = try {
        bodyAsText()
    } catch (_: Exception) {
        ""
    }

    private suspend fun readJson(path: String, r: HttpResponse): JsonElement {
        val text = r.textOrEmpty()
        if (r.status.value >= 400) throw parseError(path, r.status.value, text)
        return runCatching { Json.parseToJsonElement(text) }.getOrElse {
            throw ApiException(trf("响应解析失败_0_2b68ffa3e9", text.take(300)), r.status.value)
        }
    }

    private suspend fun send(
        method: String,
        path: String,
        body: JsonElement? = null,
    ): JsonElement {
        val r: HttpResponse = try {
            when (method) {
                "GET" -> http.get(urlOf(path)) { auth() }
                "DELETE" -> http.delete(urlOf(path)) { auth() }
                else -> http.post(urlOf(path)) {
                    auth()
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    if (body != null) setBody(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        return readJson(path, r)
    }

    private suspend fun sendPut(path: String, body: JsonElement): JsonElement {
        val r: HttpResponse = try {
            http.put(urlOf(path)) {
                auth()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        return readJson(path, r)
    }

    /* -------------------- auth -------------------- */

    /**
     * 登录并从 `Set-Cookie` 提取 session。成功返回更新后的配置
     * （含新 session 与可选保存的凭据），调用方负责 [NoteBoxStore.save]。
     *
     * 多用户版：标识符是**邮箱**（服务端 `email` 或 `username` 字段均可）。
     */
    suspend fun login(email: String, password: String): NoteBoxConfig {
        val r: HttpResponse = try {
            http.post(urlOf("/api/login")) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(jsonObjectOf("email" to email, "password" to password))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("无法连接_notebox_0_cc669cd82a", e.message ?: e::class.simpleName ?: L["网络不可达_7e7812414f"]), 0)
        }
        val text = r.textOrEmpty()
        if (r.status.value >= 400) throw parseError("/api/login", r.status.value, text)

        val cookie = r.headers.getAll(HttpHeaders.SetCookie)
            .orEmpty()
            .firstOrNull { it.startsWith("session=") }
            ?.substringBefore(";")
            ?.takeIf { it.length > "session=".length }
            ?: throw ApiException(L["服务器未返回会话_cookie_9dc7f14670"], r.status.value)

        val cur = config()
        return cur.copy(
            user = email.trim(),
            session = cookie,
            password = if (cur.rememberPassword) password else cur.password,
        )
    }

    /** 邮箱注册（服务端发验证邮件；`mailSent=false` 表示 SMTP 未就绪）。 */
    suspend fun register(email: String, password: String): JsonObject =
        send("POST", "/api/register", jsonObjectOf("email" to email, "password" to password)).jsonObject

    /** 重新发送验证邮件（服务端不限存在性，统一提示）。 */
    suspend fun resendVerification(email: String): String =
        send("POST", "/api/resend-verification", jsonObjectOf("email" to email)).str("message")

    suspend fun logout() {
        runCatching { send("POST", "/api/logout") }
    }

    /* -------------------- bootstrap / stats -------------------- */

    suspend fun bootstrap(): JsonObject = send("GET", "/api/bootstrap").jsonObject

    suspend fun stats(): JsonObject = send("GET", "/api/stats").jsonObject

    /* -------------------- users (admin) -------------------- */

    suspend fun listUsers(): List<JsonElement> =
        send("GET", "/api/admin/users").let { (it as? JsonArray)?.items() ?: emptyList() }

    suspend fun createUser(email: String, password: String, role: String, active: Boolean = true): JsonObject =
        send("POST", "/api/admin/users", jsonObjectOf(
            "email" to email, "password" to password, "role" to role, "active" to active,
        )).jsonObject

    suspend fun updateUser(
        id: Int,
        email: String? = null,
        password: String? = null,
        role: String? = null,
        active: Boolean? = null,
    ): JsonObject = sendPut("/api/admin/users/$id", jsonObjectOf(
        "email" to email,
        "password" to password?.takeIf { it.isNotEmpty() },
        "role" to role,
        "active" to active,
    )).jsonObject

    suspend fun deleteUser(id: Int) {
        send("DELETE", "/api/admin/users/$id")
    }

    suspend fun resendUserVerification(id: Int) {
        send("POST", "/api/admin/users/$id/resend")
    }

    /* -------------------- note shares -------------------- */

    /** 笔记共享列表（任何能看到笔记的人都可读；管理需 owner/admin 权限）。 */
    suspend fun listShares(nid: Int): List<JsonElement> =
        send("GET", "/api/notes/$nid/shares").let { (it as? JsonArray)?.items() ?: emptyList() }

    /** 共享给某邮箱（已存在则更新权限）；返回更新后的完整列表。 */
    suspend fun addShare(nid: Int, email: String, permission: String): List<JsonElement> =
        send("POST", "/api/notes/$nid/shares", jsonObjectOf(
            "email" to email, "permission" to permission,
        )).obj("shares").shareList()

    suspend fun updateShare(nid: Int, sid: Int, permission: String): List<JsonElement> =
        sendPut("/api/notes/$nid/shares/$sid", jsonObjectOf("permission" to permission))
            .obj("shares").shareList()

    suspend fun deleteShare(nid: Int, sid: Int): List<JsonElement> =
        send("DELETE", "/api/notes/$nid/shares/$sid").obj("shares").shareList()

    /** 开启公开链接；返回 `{share_code, share_url}`（幂等，已有码则复用）。 */
    suspend fun shareNote(nid: Int): JsonObject = send("POST", "/api/notes/$nid/share").jsonObject

    suspend fun unshareNote(nid: Int) {
        send("POST", "/api/notes/$nid/unshare")
    }

    /** 公开分享页的完整 URL（`/s/<code>`）。 */
    fun shareUrl(code: String): String = config().normalizedUrl() + "/s/" + code

    /* -------------------- convert / uploads -------------------- */

    /** 粘贴的富文本（HTML）→ Markdown。 */
    suspend fun convertHtml(html: String): String =
        send("POST", "/api/convert/html", jsonObjectOf("html" to html)).str("markdown")

    /** 上传图片/视频/音频（multipart，≤200MB）；返回 `{url,name,size,kind}`。 */
    suspend fun uploadAsset(bytes: ByteArray, filename: String, mime: String): JsonObject {
        val form: List<io.ktor.http.content.PartData> = io.ktor.client.request.forms.formData {
            append(
                "file",
                bytes,
                io.ktor.http.headers {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"${filename.replace("\\", "")}\"")
                },
            )
        }
        val r: HttpResponse = try {
            http.post(urlOf("/api/assets")) {
                auth()
                setBody(form)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        return readJson("/api/assets", r).jsonObject
    }

    /** 把 `/uploads/...` 相对 URL 拼成绝对地址（图片渲染用）。 */
    fun absoluteUrl(path: String): String {
        if (path.startsWith("http")) return path
        return config().normalizedUrl() + (if (path.startsWith("/")) path else "/$path")
    }

    /* -------------------- folders (multi-level) -------------------- */

    /** `kind` = `note`（归类）或 `todo`（清单）。 */
    suspend fun listFolders(kind: String): List<JsonElement> =
        send("GET", "/api/folders?kind=" + kind.encodeUrl())
            .let { (it as? JsonArray)?.items() ?: emptyList() }

    suspend fun createFolder(
        name: String,
        kind: String,
        parentId: Int? = null,
        color: String = "",
    ): JsonObject = send("POST", "/api/folders", jsonObjectOf(
        "name" to name, "kind" to kind, "parent_id" to parentId, "color" to color,
    )).jsonObject

    suspend fun updateFolder(
        id: Int,
        name: String,
        parentId: Int?,
        color: String,
        position: Int = 0,
    ): JsonObject = sendPut("/api/folders/$id", buildJsonObject {
        put("name", JsonPrimitive(name))
        put("parent_id", parentId?.let(::JsonPrimitive) ?: JsonNull)
        put("color", JsonPrimitive(color))
        put("position", JsonPrimitive(position))
    }).jsonObject

    suspend fun deleteFolder(id: Int): JsonObject =
        send("DELETE", "/api/folders/$id").jsonObject

    /* -------------------- notes -------------------- */

    suspend fun listNotes(
        q: String = "",
        tag: String = "",
        folder: String = "",
        trash: Boolean = false,
        scope: String = "",
        sort: String = "",
    ): List<JsonElement> {
        val url = "/api/notes?q=" + q.encodeUrl() +
            "&tag=" + tag.encodeUrl() +
            "&folder=" + folder.encodeUrl() +
            "&sort=" + sort.encodeUrl() +
            (if (trash) "&trash=1" else "") +
            (if (scope.isNotEmpty()) "&scope=" + scope.encodeUrl() else "")
        return send("GET", url).let { (it as? JsonArray)?.items() ?: emptyList() }
    }

    suspend fun tags(): List<String> =
        send("GET", "/api/tags").let { el ->
            (el as? JsonArray)?.items().orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.safeContent()?.takeIf(String::isNotBlank) }
        }

    suspend fun createNote(
        title: String,
        content: String = "",
        tags: String = "",
        pinned: Boolean = false,
        folderId: Int? = null,
        color: String = "",
    ): JsonObject = send("POST", "/api/notes", jsonObjectOf(
        "title" to title, "content" to content, "tags" to tags, "pinned" to pinned,
        "folder_id" to folderId, "color" to color,
    )).jsonObject

    suspend fun updateNote(id: Int, title: String, content: String, tags: String, pinned: Boolean): JsonObject =
        sendPut("/api/notes/$id", jsonObjectOf(
            "title" to title, "content" to content, "tags" to tags, "pinned" to pinned,
        )).jsonObject

    /** 移动归类（`null` = 未归类）；服务端只允许所有者操作。 */
    suspend fun moveNote(id: Int, folderId: Int?): JsonObject =
        sendPut("/api/notes/$id", buildJsonObject {
            put("folder_id", folderId?.let(::JsonPrimitive) ?: JsonNull)
        }).jsonObject

    suspend fun setNoteColor(id: Int, color: String): JsonObject =
        sendPut("/api/notes/$id", jsonObjectOf("color" to color)).jsonObject

    suspend fun trashNote(id: Int) {
        send("POST", "/api/notes/$id/trash")
    }

    suspend fun restoreNote(id: Int): JsonObject =
        send("POST", "/api/notes/$id/restore").jsonObject

    /** 复制到自己的账号（共享笔记也可复制）。 */
    suspend fun duplicateNote(id: Int): JsonObject =
        send("POST", "/api/notes/$id/duplicate").jsonObject

    suspend fun bulkMoveNotes(ids: List<Int>, folderId: Int?): Int =
        send("POST", "/api/notes/bulk-move", buildJsonObject {
            put("ids", JsonArray(ids.map(::JsonPrimitive)))
            put("folder_id", folderId?.let(::JsonPrimitive) ?: JsonNull)
        }).int("moved")

    suspend fun deleteNote(id: Int) {
        send("DELETE", "/api/notes/$id")
    }

    /* -------------------- todos -------------------- */

    suspend fun todoTags(): List<String> =
        send("GET", "/api/todos/tags").let { el ->
            (el as? JsonArray)?.items().orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.safeContent()?.takeIf(String::isNotBlank) }
        }

    suspend fun listTodos(
        q: String = "",
        status: String = "",
        tag: String = "",
        folder: String = "",
    ): List<JsonElement> {
        val url = "/api/todos?q=" + q.encodeUrl() + "&status=" + status.encodeUrl() +
            "&tag=" + tag.encodeUrl() + "&folder=" + folder.encodeUrl()
        return send("GET", url).let { (it as? JsonArray)?.items() ?: emptyList() }
    }

    suspend fun createTodo(
        title: String,
        detail: String = "",
        priority: Int = 1,
        dueAt: Long? = null,
        dueDate: String? = null,
        remind: Boolean = true,
        tags: String = "",
        subtasks: List<JsonObject> = emptyList(),
        repeat: String = "none",
        folderId: Int? = null,
    ): JsonObject = send(
        "POST", "/api/todos",
        todoPayload(title, detail, priority, dueAt, dueDate, remind, tags, subtasks, repeat, folderId),
    ).jsonObject

    suspend fun updateTodo(
        id: Int,
        title: String,
        detail: String,
        priority: Int,
        dueAt: Long?,
        dueDate: String?,
        remind: Boolean,
        status: String,
        tags: String = "",
        subtasks: List<JsonObject> = emptyList(),
        repeat: String = "none",
        folderId: Int? = null,
    ): JsonObject = sendPut(
        "/api/todos/$id",
        JsonObject(
            todoPayload(title, detail, priority, dueAt, dueDate, remind, tags, subtasks, repeat, folderId) +
                mapOf("status" to JsonPrimitive(status)),
        ),
    ).jsonObject

    private fun todoPayload(
        title: String,
        detail: String,
        priority: Int,
        dueAt: Long?,
        dueDate: String?,
        remind: Boolean,
        tags: String,
        subtasks: List<JsonObject>,
        repeat: String,
        folderId: Int?,
    ): JsonObject = buildJsonObject {
        put("title", JsonPrimitive(title))
        put("detail", JsonPrimitive(detail))
        put("priority", JsonPrimitive(priority.coerceIn(0, 3)))
        put("due_at", dueAt?.let(::JsonPrimitive) ?: JsonNull)
        put("due_date", dueDate?.let(::JsonPrimitive) ?: JsonNull)
        put("remind", JsonPrimitive(if (remind) 1 else 0))
        put("tags", JsonPrimitive(tags))
        put("subtasks", JsonArray(subtasks))
        put("repeat", JsonPrimitive(repeat))
        put("folder_id", folderId?.let(::JsonPrimitive) ?: JsonNull)
    }

    suspend fun toggleTodo(id: Int): JsonObject = send("POST", "/api/todos/$id/toggle").jsonObject

    /** 立即发送包含完整详情的待办邮件（附日历邀请）。 */
    suspend fun sendTodoMail(id: Int) {
        send("POST", "/api/todos/$id/notify")
    }

    suspend fun deleteTodo(id: Int) {
        send("DELETE", "/api/todos/$id")
    }

    /** 清理全部已完成；返回删除条数。 */
    suspend fun clearDone(): Int =
        send("POST", "/api/todos/clear-done").int("deleted")

    /* -------------------- settings / mail -------------------- */

    suspend fun getSettings(): JsonObject = send("GET", "/api/settings").jsonObject

    suspend fun putSettings(values: Map<String, Any?>): JsonObject =
        sendPut("/api/settings", jsonObjectOf(*values.map { it.key to it.value }.toTypedArray())).jsonObject

    suspend fun changePassword(old: String, new: String) {
        send("POST", "/api/settings/password", jsonObjectOf("old" to old, "new" to new))
    }

    suspend fun testEmail() {
        send("POST", "/api/settings/test-email")
    }

    suspend fun emailLog(): List<JsonElement> =
        send("GET", "/api/emails").let { (it as? JsonArray)?.items() ?: emptyList() }

    /* -------------------- import / export -------------------- */

    suspend fun importNotes(files: List<Pair<String, String>>, split: Boolean): Int =
        send("POST", "/api/import/notes", jsonObjectOf(
            "split" to split,
            "files" to JsonArray(files.map { (name, content) ->
                jsonObjectOf("name" to name, "content" to content)
            }),
        )).int("imported")

    suspend fun importTodos(name: String, content: String, format: String = "auto"): Int =
        send("POST", "/api/import/todos", jsonObjectOf(
            "name" to name, "content" to content, "format" to format,
        )).int("imported")

    suspend fun importBackup(content: String, mode: String): JsonObject =
        send("POST", "/api/import/backup", jsonObjectOf("content" to content, "mode" to mode)).jsonObject

    /** 导出下载（/api/export、/api/export/markdown、/api/export/csv）。 */
    suspend fun download(path: String): ByteArray {
        val r: HttpResponse = try {
            http.get(urlOf(path)) { auth() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        if (r.status.value >= 400) throw parseError(path, r.status.value, r.textOrEmpty())
        if (!r.status.isSuccess()) throw ApiException("HTTP ${r.status.value}", r.status.value)
        return r.bodyAsBytes()
    }

    fun close() {
        try { http.close() } catch (_: Exception) {}
    }

    companion object {
        /** `GET /api/bootstrap` 探活（登录页「测试连接」）。 */
        suspend fun probe(url: String): String {
            val normalized = url.trim().trimEnd('/').ifEmpty { NoteBoxStore.DEFAULT_URL }
            val full = if (normalized.startsWith("http")) normalized else "http://$normalized"
            val http = HttpClient(createEngine()) {
                engine { configureHttpEngine(this) }
                install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 15_000 }
            }
            try {
                val r = http.get("$full/api/bootstrap")
                when {
                    r.status.value == 401 -> return L["notebox_服务可达_未登录_e023d68cad"]
                    r.status.isSuccess() -> return L["notebox_服务可达_6a7506d6ac"]
                    else -> throw ApiException(trf("服务不可达_http_0_6aaaef3706", r.status.value), r.status.value)
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException(trf("无法连接_notebox_0_cc669cd82a", e.message ?: e::class.simpleName ?: L["网络不可达_7e7812414f"]), 0)
            } finally {
                try { http.close() } catch (_: Exception) {}
            }
        }
    }
}

/* -------------------- 小工具 -------------------- */

private fun String.encodeUrl(): String = this.encodeURLParameter()

private fun JsonArray.items(): List<JsonElement> = this.toList()

private fun JsonObject?.shareList(): List<JsonElement> =
    (this?.get("shares") as? JsonArray)?.toList() ?: emptyList()

/* ==================================================================== *
 * NoteBox 会话状态（与面板会话平行的单例）
 * ==================================================================== */

enum class NbSessionState { Boot, Login, Ready }

/**
 * NoteBox 全局会话：登录 / 登出 / 过期，配置持久化。
 * 页面通过 `NoteBoxState.session` 门控（未登录时渲染 NoteGate 登录卡）。
 */
object NoteBoxState {

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private var cfg: NoteBoxConfig = NoteBoxStore.load()
    private var _api: NoteBoxApi = NoteBoxApi { cfg }

    val api: NoteBoxApi get() = _api

    fun config(): NoteBoxConfig = cfg

    private val _session = kotlinx.coroutines.flow.MutableStateFlow(
        if (cfg.isLoggedIn) NbSessionState.Boot else NbSessionState.Login,
    )
    val session: kotlinx.coroutines.flow.StateFlow<NbSessionState> = _session

    private val _user = kotlinx.coroutines.flow.MutableStateFlow(cfg.user)
    val user: kotlinx.coroutines.flow.StateFlow<String> = _user

    /** 当前角色：`admin` / `user`（多用户版新增；决定用户管理/邮件页是否可见）。 */
    private val _role = kotlinx.coroutines.flow.MutableStateFlow("user")
    val role: kotlinx.coroutines.flow.StateFlow<String> = _role

    val isAdmin: Boolean get() = _role.value == "admin"

    private val _stats = kotlinx.coroutines.flow.MutableStateFlow<JsonObject?>(null)
    val stats: kotlinx.coroutines.flow.StateFlow<JsonObject?> = _stats

    private val _settings = kotlinx.coroutines.flow.MutableStateFlow<JsonObject?>(null)
    val settings: kotlinx.coroutines.flow.StateFlow<JsonObject?> = _settings

    /** 启动时验证已保存的 session 是否仍有效。 */
    suspend fun bootstrap() {
        if (!cfg.isLoggedIn) {
            _session.value = NbSessionState.Login
            return
        }
        try {
            val b = _api.bootstrap()
            _user.value = b.str("user").ifEmpty { cfg.user }
            _role.value = b.str("role").ifEmpty { "user" }
            _stats.value = b.obj("stats")
            _settings.value = b.obj("settings")
            _session.value = NbSessionState.Ready
        } catch (e: ApiException) {
            if (e.isAuth) {
                cfg = cfg.forgetSession(); NoteBoxStore.save(cfg)
                _session.value = NbSessionState.Login
            } else {
                // 网络问题：保留 cookie，登录页给提示
                _session.value = NbSessionState.Login
            }
        }
    }

    /** 登录；返回 null 成功，否则为错误消息。 */
    suspend fun login(username: String, password: String): String? = try {
        val next = _api.login(username, password)
        cfg = next
        NoteBoxStore.save(cfg)
        val b = _api.bootstrap()
        _user.value = b.str("user").ifEmpty { username }
        _role.value = b.str("role").ifEmpty { "user" }
        _stats.value = b.obj("stats")
        _settings.value = b.obj("settings")
        _session.value = NbSessionState.Ready
        null
    } catch (e: ApiException) {
        e.message ?: L["登录失败_aeb6c8a818"]
    } catch (e: Exception) {
        e.message ?: e::class.simpleName ?: L["登录失败_aeb6c8a818"]
    }

    /** 邮箱注册；返回 null 成功，否则为错误消息。 */
    suspend fun register(email: String, password: String): String? = try {
        _api.register(email, password)
        null
    } catch (e: ApiException) {
        e.message ?: L["注册失败_a1d7fb5dfc"]
    } catch (e: Exception) {
        e.message ?: e::class.simpleName ?: L["注册失败_a1d7fb5dfc"]
    }

    suspend fun resendVerification(email: String): String = try {
        _api.resendVerification(email)
    } catch (e: Exception) {
        e.message ?: L["发送失败_e767d34c78"]
    }

    suspend fun logout() {
        runCatching { _api.logout() }
        cfg = cfg.forgetSession()
        NoteBoxStore.save(cfg)
        _stats.value = null
        _settings.value = null
        _role.value = "user"
        _session.value = NbSessionState.Login
    }

    /** 任意请求 401 时调用：清 session 回登录页。 */
    fun expireSession() {
        cfg = cfg.forgetSession()
        NoteBoxStore.save(cfg)
        _stats.value = null
        _settings.value = null
        _role.value = "user"
        _session.value = NbSessionState.Login
    }

    /** 改服务器地址（丢弃旧 session，需要重新登录）。 */
    suspend fun reconfigure(url: String, user: String? = null): String? {
        val old = cfg
        cfg = cfg.copy(
            url = url.trim().ifEmpty { NoteBoxStore.DEFAULT_URL },
            user = user ?: cfg.user,
        ).forgetSession()
        NoteBoxStore.save(cfg)
        _user.value = cfg.user
        _stats.value = null
        _settings.value = null
        _role.value = "user"
        _session.value = NbSessionState.Login
        if (old.session.isNotEmpty()) runCatching { _api.logout() }
        return null
    }

    /** 保存（可选记住）密码，供登录页预填。 */
    fun updateCredentials(user: String, password: String, remember: Boolean) {
        cfg = cfg.copy(user = user, password = password, rememberPassword = remember)
        NoteBoxStore.save(cfg)
    }

    suspend fun refreshStats() {
        runCatching { _stats.value = _api.stats() }
    }

    suspend fun refreshSettings() {
        runCatching { _settings.value = _api.getSettings() }
    }

    /** PUT /api/settings 返回的新设置直接落到本地状态（免一次 GET）。 */
    fun applySettings(s: JsonObject) {
        _settings.value = s
    }

    fun toast(text: String, kind: ToastKind = ToastKind.Info, ms: Long = 3800) =
        AppState.toast(text, kind, ms)

    /** 包一层：401 自动掉会话（页面无需重复处理）。 */
    suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.isAuth) expireSession()
        throw e
    }
}
