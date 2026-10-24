package com.servermaintance.panel.core
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.trf

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 文件管理器（`/panel/api/ops/files`）。
 *
 * 面板原来只有「浏览目录 + 读改单个文件」，这里补齐：树、创建、重命名、移动、复制、
 * 上传/下载、权限与属主、打包/解压、搜索、目录占用，以及删除先进回收站（30 天）再彻底删。
 * 路径校验与 find/du/tar 的降级都在服务端，客户端只管展示返回的可读错误。
 */

data class FileCrumb(val label: String, val path: String)

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isFile: Boolean,
    val isLink: Boolean,
    val type: String,
    val typeLabel: String,
    val size: Long,
    val mode: String,
    val symbolic: String,
    val owner: String,
    val group: String,
    val uid: String,
    val gid: String,
    val mtimeMs: Long,
    val linkTarget: String = "",
    val entryCount: Int = 0,
)

data class DiskFree(val totalKb: Long, val usedKb: Long, val availKb: Long, val pct: String, val filesystem: String)

data class FileBookmark(val label: String, val path: String)

data class FileListing(
    val dir: String,
    val parent: String,
    val breadcrumb: List<FileCrumb>,
    val entries: List<FileEntry>,
    val count: Int,
    val usedShell: Boolean,
    val df: DiskFree?,
    val bookmarks: List<FileBookmark>,
    val os: String,
    val self: FileEntry?,
)

data class FilePreview(
    val path: String,
    val size: Long,
    val mtimeMs: Long,
    val binary: Boolean,
    val tooLarge: Boolean,
    val truncated: Boolean,
    val text: String,
    val ext: String,
    val lines: Int,
    val limit: Long,
)

data class TrashItem(val id: String, val original: String, val name: String, val size: Long, val isDir: Boolean, val at: Long)

data class SearchHit(val path: String, val type: String, val size: Long, val mtimeText: String)

data class UsageItem(val path: String, val kb: Long)

object FileStore {

    private val api get() = AppState.api
    const val ROOT_DEFAULT = "/etc"

    val dir = MutableStateFlow(ROOT_DEFAULT)
    val listing = MutableStateFlow<FileListing?>(null)
    val preview = MutableStateFlow<FilePreview?>(null)
    val editing = MutableStateFlow<String?>(null)
    val trash = MutableStateFlow<List<TrashItem>>(emptyList())
    val trashDays = MutableStateFlow(30)
    val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val usage = MutableStateFlow<List<UsageItem>>(emptyList())
    val usageFor = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val unsupported = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)

    private fun JsonObject.entry(): FileEntry = FileEntry(
        name = str("name"), path = str("path"),
        isDir = bool("isDir"), isFile = bool("isFile"), isLink = bool("isLink"),
        type = str("type"), typeLabel = str("typeLabel"),
        size = long("size"), mode = str("mode"), symbolic = str("symbolic"),
        owner = str("owner"), group = str("group"), uid = str("uid"), gid = str("gid"),
        mtimeMs = long("mtimeMs"),
        linkTarget = str("linkTarget"), entryCount = int("entryCount"),
    )

    private fun noteFailure(path: String, e: Exception) {
        if (e is ApiException && e.code == 404) {
            unsupported.value = true
            lastError.value = null
            return
        }
        lastError.value = trf("0_1_4d3c418a83", path, e.message ?: L["请求失败_8fdc4112a4"])
    }

    /** 返回 null=成功，非空=可直接 toast 的失败文案。 */
    private suspend fun call(path: String, body: JsonObject, after: (suspend () -> Unit)? = null): String? = try {
        busy.value = true
        api.post(path, body)
        lastError.value = null
        after?.invoke()
        null
    } catch (e: ApiException) {
        if (e.code == 404) unsupported.value = true else lastError.value = e.message
        e.message ?: L["操作失败_09e424b5e8"]
    } catch (e: Exception) {
        lastError.value = e.message
        e.message ?: L["操作失败_09e424b5e8"]
    } finally {
        busy.value = false
    }

    suspend fun open(target: String = dir.value) {
        dir.value = target
        preview.value = null
        editing.value = null
        try {
            val o = api.get("/ops/files/list?dir=${urlEncode(target)}", 0)
            unsupported.value = false
            listing.value = FileListing(
                dir = o.str("dir"), parent = o.str("parent"),
                breadcrumb = o.arr("breadcrumb").mapNotNull { it as? JsonObject }
                    .map { FileCrumb(it.str("label"), it.str("path")) },
                entries = o.arr("entries").mapNotNull { it as? JsonObject }.map { it.entry() },
                count = o.int("count"), usedShell = o.bool("usedShell"),
                df = o.obj("df")?.let { DiskFree(it.long("totalKb"), it.long("usedKb"), it.long("availKb"), it.str("pct"), it.str("filesystem")) },
                bookmarks = o.arr("bookmarks").mapNotNull { it as? JsonObject }.map { FileBookmark(it.str("label"), it.str("path")) },
                os = o.str("os"),
                self = o.obj("self")?.entry(),
            )
            lastError.value = null
        } catch (e: Exception) {
            noteFailure("ops/files/list", e)
        }
    }

    suspend fun refresh() = open(dir.value)

    suspend fun up(level: Int) {
        val l = listing.value ?: return
        if (l.parent.isNotBlank()) open(l.parent)
    }

    suspend fun loadTrash() {
        try {
            val o = api.get("/ops/files/trash", 0)
            trash.value = o.arr("items").mapNotNull { it as? JsonObject }.map {
                TrashItem(it.str("id"), it.str("original"), it.str("name"), it.long("size"), it.bool("isDir"), it.long("at"))
            }
            trashDays.value = o.int("days")
        } catch (e: Exception) { noteFailure("ops/files/trash", e) }
    }

    suspend fun previewFile(path: String) {
        try {
            preview.value = api.get("/ops/files/preview?path=${urlEncode(path)}", 0).let {
                FilePreview(
                    it.str("path"), it.long("size"), it.long("mtimeMs"), it.bool("binary"), it.bool("tooLarge"),
                    it.bool("truncated"), it.str("text"), it.str("ext"), it.int("lines"), it.long("limit"),
                )
            }
        } catch (e: Exception) {
            preview.value = null
            noteFailure("ops/files/preview", e)
        }
    }

    suspend fun startEdit(path: String): String? {
        previewFile(path)
        val p = preview.value ?: return lastError.value
        return when {
            p.binary -> L["这是二进制文件_不能在线编辑_请下载后用编辑器打_d2f2d93e6e"]
            p.tooLarge -> trf("文件_0_超过可编辑上限_1_请用终端或_bf27cf6d14", Fmt.bytes(p.size), Fmt.bytes(p.limit))
            p.truncated -> trf("文件被截断预览_0_在线编辑会丢失内容_建_1d7dde5641", Fmt.bytes(p.size))
            else -> {
                editing.value = path
                null
            }
        }
    }

    suspend fun saveEdit(path: String, content: String): String? =
        call("/ops/files/write", jsonObjectOf("path" to path, "content" to content)) {
            editing.value = null
            previewFile(path)
            refresh()
        }

    suspend fun mkdir(name: String): String? = call("/ops/files/mkdir", jsonObjectOf("dir" to dir.value, "name" to name)) { refresh() }

    suspend fun createFile(name: String): String? = call("/ops/files/touch", jsonObjectOf("dir" to dir.value, "name" to name)) { refresh() }

    suspend fun rename(path: String, name: String): String? =
        call("/ops/files/rename", jsonObjectOf("path" to path, "name" to name)) { refresh() }

    suspend fun move(paths: List<String>, to: String): String? =
        call("/ops/files/move", jsonObjectOf("paths" to paths.toJsonArray(), "to" to to)) { refresh(); loadTrash() }

    suspend fun copy(paths: List<String>, to: String): String? =
        call("/ops/files/copy", jsonObjectOf("paths" to paths.toJsonArray(), "to" to to)) { refresh() }

    suspend fun delete(paths: List<String>, purge: Boolean = false): String? =
        call("/ops/files/delete", jsonObjectOf("paths" to paths.toJsonArray(), "purge" to purge)) { refresh(); loadTrash() }

    suspend fun restore(ids: List<String>): String? =
        call("/ops/files/restore", jsonObjectOf("ids" to ids.toJsonArray())) { loadTrash(); refresh() }

    suspend fun purgeTrash(ids: List<String>): String? =
        call("/ops/files/trash/delete", jsonObjectOf("ids" to ids.toJsonArray())) { loadTrash() }

    suspend fun emptyTrash(): String? = call("/ops/files/trash/empty", jsonObjectOf()) { loadTrash() }

    suspend fun chmod(paths: List<String>, mode: String, recursive: Boolean): String? =
        call("/ops/files/chmod", jsonObjectOf("paths" to paths.toJsonArray(), "mode" to mode, "recursive" to recursive)) { refresh() }

    suspend fun chown(paths: List<String>, owner: String, recursive: Boolean): String? =
        call("/ops/files/chown", jsonObjectOf("paths" to paths.toJsonArray(), "owner" to owner, "recursive" to recursive)) { refresh() }

    suspend fun upload(name: String, bytes: ByteArray, overwrite: Boolean = false): String? = try {
        busy.value = true
        api.postRaw("/ops/files/upload?dir=${urlEncode(dir.value)}&name=${urlEncode(name)}" +
            (if (overwrite) "&overwrite=1" else ""), bytes)
        lastError.value = null
        refresh()
        null
    } catch (e: ApiException) {
        if (e.code == 404) unsupported.value = true else lastError.value = e.message
        e.message ?: L["上传失败_a6f805694b"]
    } catch (e: Exception) {
        e.message ?: L["上传失败_a6f805694b"]
    } finally { busy.value = false }

    suspend fun download(path: String): Pair<ByteArray?, String?> = try {
        api.download("/ops/files/download?path=${urlEncode(path)}") to null
    } catch (e: ApiException) {
        null to (e.message ?: L["下载失败_e0dab22b1a"])
    } catch (e: Exception) {
        null to (e.message ?: L["下载失败_e0dab22b1a"])
    }

    suspend fun search(q: String, depth: Int = 4) {
        if (q.isBlank()) return
        try {
            hits.value = api.get(
                "/ops/files/search?dir=${urlEncode(dir.value)}&q=${urlEncode(q)}&depth=$depth", 0,
            ).arr("items").mapNotNull { it as? JsonObject }.map {
                SearchHit(it.str("path"), it.str("type"), it.long("size"), it.str("mtimeText"))
            }
        } catch (e: Exception) { noteFailure("ops/files/search", e) }
    }

    suspend fun loadUsage(path: String = dir.value) {
        try {
            val o = api.get("/ops/files/usage?dir=${urlEncode(path)}", 30_000)
            usage.value = o.arr("items").mapNotNull { it as? JsonObject }.map { UsageItem(it.str("path"), it.long("kb")) }
            usageFor.value = o.str("dir")
        } catch (e: Exception) { noteFailure("ops/files/usage", e) }
    }

    suspend fun archive(name: String, paths: List<String>, zip: Boolean = false): String? =
        call("/ops/files/archive", jsonObjectOf("name" to name, "paths" to paths.toJsonArray(), "to" to dir.value, "kind" to (if (zip) "zip" else "tgz"))) { refresh() }

    suspend fun extract(path: String, to: String): String? =
        call("/ops/files/extract", jsonObjectOf("path" to path, "to" to to)) { refresh() }

    fun isTextish(e: FileEntry): Boolean = !e.isDir && (
        e.typeLabel != com.servermaintance.panel.i18n.apiTag("符号链接_bcf783908a") && (
            e.name.endsWith(".conf") || e.name.endsWith(".cfg") || e.name.endsWith(".sh") ||
                e.name.endsWith(".py") || e.name.endsWith(".js") || e.name.endsWith(".ts") ||
                e.name.endsWith(".kt") || e.name.endsWith(".json") || e.name.endsWith(".yaml") ||
                e.name.endsWith(".yml") || e.name.endsWith(".md") || e.name.endsWith(".txt") ||
                e.name.endsWith(".log") || e.name.endsWith(".env") || e.name.endsWith(".service") ||
                e.name.endsWith(".xml") || e.name.endsWith(".ini") || e.name.endsWith(".properties") ||
                !e.name.contains('.') || e.ext().length > 3
            )
        )

    private fun FileEntry.ext(): String = name.substringAfterLast('.', "")
}

private fun List<String>.toJsonArray(): JsonElement =
    kotlinx.serialization.json.JsonArray(this.map { JsonPrimitive(it) })

/** commonMain 没有 URLEncoder；`/` 保留原样，其余非安全字符按 UTF-8 百分号编码。 */
internal fun urlEncode(s: String): String = buildString {
    for (ch in s) {
        if (ch.isLetterOrDigit() || ch in "-._~/") append(ch)
        else for (b in ch.toString().encodeToByteArray())
            append("%" + (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}
