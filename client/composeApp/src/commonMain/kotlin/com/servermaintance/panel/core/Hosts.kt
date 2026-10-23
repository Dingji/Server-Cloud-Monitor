package com.servermaintance.panel.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 纳管主机清单（`/panel/api/ops/hosts`）。纯运维台账：哪 machines 归这套面板管、
 * 谁负责、什么环境、备注。可达性用「打开 SSH 端口读 banner」判断，不发送任何凭据。
 */

data class ManagedHost(
    val id: String,
    val label: String,
    val addr: String,
    val sshPort: Int,
    val role: String,
    val env: String,
    val owner: String,
    val tags: List<String>,
    val note: String,
    val panelUrl: String,
    val addedAt: Long,
    val updatedAt: Long,
    val addedBy: String,
    val lastSeenAt: Long,
    val lastCheckOk: Boolean,
    val lastCheckError: String,
    val banner: String,
)

data class HostStats(
    val total: Int,
    val reachable: Int,
    val unreachable: Int,
    val unchecked: Int,
    val withoutAddr: Int,
    val byRole: Map<String, Int>,
    val byEnv: Map<String, Int>,
)

private fun JsonObjectCount(o: JsonObject?): Map<String, Int> =
    o?.mapValues { (_, v) -> ((v as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) } ?: emptyMap()

private fun JsonObject.hostItem(): ManagedHost = ManagedHost(
    id = str("id"), label = str("label"), addr = str("addr"), sshPort = int("sshPort"),
    role = str("role"), env = str("env"), owner = str("owner"),
    tags = arr("tags").mapNotNull { (it as? JsonPrimitive)?.content },
    note = str("note"), panelUrl = str("panelUrl"),
    addedAt = long("addedAt"), updatedAt = long("updatedAt"), addedBy = str("addedBy"),
    lastSeenAt = long("lastSeenAt"), lastCheckOk = bool("lastCheckOk"),
    lastCheckError = str("lastCheckError"), banner = str("banner"),
)

object HostState {

    private val api get() = AppState.api

    val items = MutableStateFlow<List<ManagedHost>>(emptyList())
    val roles = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val envs = MutableStateFlow<List<String>>(emptyList())
    val stats = MutableStateFlow<HostStats?>(null)
    val unsupported = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    fun roleLabel(key: String): String = roles.value.firstOrNull { it.first == key }?.second ?: key

    suspend fun load() {
        try {
            val o = api.get("/ops/hosts", 5_000)
            unsupported.value = false
            items.value = o.arr("items").mapNotNull { it as? JsonObject }.map { it.hostItem() }
            roles.value = o.arr("roles").mapNotNull { it as? JsonObject }.map { it.str("key") to it.str("label") }
            envs.value = o.arr("envs").mapNotNull { (it as? JsonPrimitive)?.content }
            val s = o.obj("stats")
            stats.value = HostStats(
                s.int("total"), s.int("reachable"), s.int("unreachable"), s.int("unchecked"), s.int("withoutAddr"),
                s.obj("byRole").let(::JsonObjectCount), s.obj("byEnv").let(::JsonObjectCount),
            )
            lastError.value = null
        } catch (e: Exception) {
            if (e is ApiException && e.code == 404) unsupported.value = true else lastError.value = e.message
        }
    }

    private suspend fun mutate(path: String, body: JsonObject, reload: suspend () -> Unit): String? = try {
        api.post(path, body)
        lastError.value = null
        reload()
        null
    } catch (e: ApiException) {
        if (e.code == 404) unsupported.value = true else lastError.value = e.message
        e.message ?: "操作失败"
    } catch (e: Exception) {
        lastError.value = e.message
        e.message ?: "操作失败"
    }

    suspend fun save(body: JsonObject): String? = mutate("/ops/host/save", body) { load() }

    suspend fun delete(id: String): String? = mutate("/ops/host/delete", jsonObjectOf("id" to id)) { load() }

    /** 不带 id = 批量探测所有主机。 */
    suspend fun check(id: String?): String? = try {
        api.post("/ops/host/check", if (id.isNullOrBlank()) jsonObjectOf() else jsonObjectOf("id" to id))
        load()
        null
    } catch (e: ApiException) {
        if (e.code == 404) unsupported.value = true
        e.message ?: "探测失败"
    } catch (e: Exception) {
        e.message ?: "探测失败"
    }
}
