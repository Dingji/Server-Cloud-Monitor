package com.servermaintance.panel.core
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Android / Desktop 均使用 CIO（见各平台 actual）。 */
expect fun createEngine(): HttpClientEngineFactory<*>

/** 平台级 HttpClient 引擎微调（当前均为空实现）。 */
expect fun configureHttpEngine(engineConfig: io.ktor.client.engine.HttpClientEngineConfig)

/**
 * REST client for the panel API.
 *
 * Mirrors `public/js/core.js`:
 *  - `Authorization: Bearer <token>`
 *  - GET caching + in-flight de-duplication
 *  - `{error, output}` failures become [ApiException]
 *  - 401 becomes an `isAuth` exception so AppState can drop the session
 */
class PanelApi(private val config: () -> ServerConfig) {

    val http = HttpClient(createEngine()) {
        engine { configureHttpEngine(this) }
        install(ContentNegotiation) { json(Json) }
        install(io.ktor.client.plugins.websocket.WebSockets) {
            pingIntervalMillis = 30_000
            maxFrameSize = 16L * 1024 * 1024
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryOnServerErrors()
            // Ktor 3.x signature: (base: Double, maxDelayMs: Long, ...)
            exponentialDelay(base = 300.0, maxDelayMs = 3000L)
        }
    }

    /* -------------------- cache + in-flight -------------------- */
    private class Slot(val origin: TimeMark, val ttl: Duration, val value: JsonElement) {
        fun expired(): Boolean = origin.elapsedNow() >= ttl
    }

    private val cache = LinkedHashMap<String, Slot>()
    private val inflight = LinkedHashMap<String, Deferred<JsonElement>>()
    private val lock = Mutex()
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun invalidate(prefix: String? = null) {
        lock.withLock {
            cache.keys.filter { prefix == null || it.startsWith(prefix) }.toList()
                .forEach { cache.remove(it) }
        }
    }

    private fun HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer " + config().token)
    }

    private fun urlOf(path: String): String {
        val base = config().apiBase()
        if (base.isEmpty()) throw ApiException(L["服务器地址未配置_18f3debc5d"], 0)
        return base + path
    }

    private suspend fun parseError(path: String, code: Int, body: String): ApiException {
        val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val msg = parsed?.let { (it["error"] as? kotlinx.serialization.json.JsonPrimitive)?.safeContent() }
        val out = parsed?.let { (it["output"] as? kotlinx.serialization.json.JsonPrimitive)?.safeContent() }
        return ApiException(
            message = if (msg.isNullOrBlank()) com.servermaintance.panel.i18n.trf("common.http_code", code) else msg,
            code = code,
            output = out?.takeIf { it.isNotBlank() },
            isAuth = code == 401,
        )
    }

    private suspend fun fetch(path: String): JsonElement {
        val r: HttpResponse = try {
            http.get(urlOf(path)) { auth() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        val text = try {
            r.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        if (r.status.value >= 400) throw parseError(path, r.status.value, text)
        return runCatching { Json.parseToJsonElement(text) }.getOrElse {
            throw ApiException(trf("响应解析失败_0_2b68ffa3e9", text.take(300)), r.status.value)
        }
    }

    /**
     * GET with optional TTL cache. Concurrent identical requests share a single
     * in-flight call — the original panel relies on this for `/me`, `/relay/available`.
     */
    suspend fun get(path: String, cacheMs: Long = 0): JsonElement {
        if (cacheMs <= 0) return fetch(path)

        val ttl = cacheMs.milliseconds
        var shared: Deferred<JsonElement>? = null

        lock.withLock {
            val hit = cache[path]
            if (hit != null && !hit.expired()) return hit.value

            val existing = inflight[path]
            if (existing != null) {
                shared = existing
            } else {
                // Holder so the coroutine can find its own slot on completion.
                val holder = arrayOfNulls<Deferred<JsonElement>>(1)
                val d = ownScope.async {
                    try {
                        val v = fetch(path)
                        lock.withLock {
                            if (cache.size > 256) cache.remove(cache.keys.firstOrNull())
                            cache[path] = Slot(TimeSource.Monotonic.markNow(), ttl, v)
                        }
                        v
                    } finally {
                        lock.withLock {
                            val mine = holder[0]
                            if (mine != null && inflight[path] === mine) inflight.remove(path)
                        }
                    }
                }
                holder[0] = d
                inflight[path] = d
                shared = d
            }
        }

        // Await outside the lock so concurrent callers are never blocked by I/O.
        return shared!!.await()
    }

    suspend fun post(path: String, body: JsonElement = JsonObject(emptyMap())): JsonElement {
        val r: HttpResponse = try {
            http.post(urlOf(path)) {
                auth()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        val text = try {
            r.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        if (r.status.value >= 400) throw parseError(path, r.status.value, text)
        invalidate()
        return runCatching { Json.parseToJsonElement(text) }.getOrElse {
            throw ApiException(trf("响应解析失败_0_2b68ffa3e9", text.take(300)), r.status.value)
        }
    }

    /** Raw-body POST used by `/db/upload` (up to 500 MB SQL dumps). */
    suspend fun postRaw(path: String, bytes: ByteArray): JsonElement {
        val r: HttpResponse = try {
            http.post(urlOf(path)) {
                auth()
                header(HttpHeaders.ContentType, ContentType.Any)
                setBody(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        val text = try {
            r.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        if (r.status.value >= 400) throw parseError(path, r.status.value, text)
        return runCatching { Json.parseToJsonElement(text) }
            .getOrElse { throw ApiException(trf("响应解析失败_0_2b68ffa3e9", text.take(300)), r.status.value) }
    }

    /** Binary download (backup archives, SQL dumps, relay backups). */
    suspend fun download(path: String): ByteArray {
        val r: HttpResponse = try {
            http.get(urlOf(path)) { auth() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        if (r.status == HttpStatusCode.Unauthorized) throw ApiException(L["未登录或会话过期_ae00c4ba08"], 401, isAuth = true)
        if (!r.status.isSuccess()) {
            val txt = runCatching { r.bodyAsText() }.getOrDefault("")
            throw parseError(path, r.status.value, txt)
        }
        return r.bodyAsBytes()
    }

    /** Reachability probe used when the user edits the server address. */
    suspend fun health(): JsonObject {
        val base = config().normalizedUrl()
        if (base.isEmpty()) throw ApiException(L["服务器地址未配置_18f3debc5d"], 0)
        val r: HttpResponse = try {
            http.get("$base/api/health")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("无法连接服务器_0_2154d8942a", e.message ?: e::class.simpleName ?: L["网络不可达_7e7812414f"]), 0)
        }
        val text = runCatching { r.bodyAsText() }.getOrDefault("")
        if (r.status.value >= 400) throw ApiException(trf("服务不可达_http_0_6aaaef3706", r.status.value), r.status.value)
        return runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw ApiException(trf("服务响应异常_0_2671b6c611", text.take(200)), r.status.value) }
    }

    suspend fun login(password: String): LoginResult {
        val r: HttpResponse = try {
            http.post(urlOf("/login")) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(jsonObjectOf("password" to password))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        val text = try {
            r.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val el = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (r.status.value >= 400 || el == null) throw parseError("/login", r.status.value, text)
        val o = el.jsonObject
        val token = (o["token"] as? kotlinx.serialization.json.JsonPrimitive)?.safeContent().orEmpty()
        if (token.isEmpty()) throw ApiException(L["服务器未返回令牌_871c697da3"], r.status.value)
        return LoginResult(
            token = token,
            expiresAt = (o["expiresAt"] as? kotlinx.serialization.json.JsonPrimitive)?.safeContent().orEmpty(),
        )
    }

    /**
     * 失败回执也保留完整 JSON 的 POST。
     *
     * 端口池的 apply/preview 在 400/409 时才把 `errors`/`conflicts`/`rollback_pool`
     * 放在响应体里（面板 relayhub.js 为此特意绕过 `CM.api` 直接用 fetch），走 [post]
     * 会被压成一句 "HTTP 409"，用户看不到到底哪里冲突。401 仍然抛出，让
     * AppState 照常把会话踢回登录页。
     */
    suspend fun postRetainingBody(path: String, body: JsonElement = JsonObject(emptyMap())): ApiReply {
        val r: HttpResponse = try {
            http.post(urlOf(path)) {
                auth()
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(trf("网络错误_0_d319ef957f", e.message ?: e::class.simpleName ?: L["连接失败_2c056f182f"]), 0)
        }
        val status = r.status.value
        val text = try {
            r.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        if (status == 401) throw parseError(path, status, text)
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        return ApiReply(status, parsed ?: JsonObject(emptyMap()))
    }

    /** [postRetainingBody] 的回执：状态码 + 已解析的响应体（解析失败时为空对象）。 */
    class ApiReply(val status: Int, val body: JsonObject) {
        val ok: Boolean get() = status in 200..299
    }

    suspend fun logout() { runCatching { post("/logout") } }

    fun close() {
        try { http.close() } catch (_: Exception) {}
        ownScope.cancel()
    }

    data class LoginResult(val token: String, val expiresAt: String)
}
