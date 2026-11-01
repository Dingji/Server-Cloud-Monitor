package com.servermaintance.panel

/**
 * Platform services the shared code needs. Each has an android/desktop actual.
 */

/** Tiny key/value store (SharedPreferences on Android, properties file on desktop). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** 一个正在写入的下载目的地：分块写、最后关一次。 */
interface SaveHandle {
    /** 给人看的路径（SAF 下是文档名，桌面/ iOS 下是绝对路径）。 */
    val displayPath: String
    fun write(chunk: ByteArray)
    fun close()

    /** 下载失败时删掉半成品——留一个截断文件比不下载更糟。 */
    fun discard()
}

/** Where user-visible downloads land, e.g. backup archives. */
interface FileExporter {
    /**
     * Save [bytes] under a friendly [name]. Returns a human-readable path on
     * success, or null when the user cancelled / the OS refused.
     */
    suspend fun saveFile(name: String, bytes: ByteArray): String?

    /**
     * 打开系统「保存到…」并返回可分块写入的句柄；用户取消返回 null。
     * 与 saveFile 的差别就是不要把整个文件先读进内存——86MB 的包在手机上那么挂的。
     */
    suspend fun beginSave(name: String): SaveHandle?

    /** Absolute path of the default download folder, for display. */
    fun defaultDir(): String
}

/** Plain clipboard access. */
interface Clip {
    fun copy(text: String)
}

/** 用户选中的一个文本文件（导入笔记/待办/备份用）。 */
data class PickedFile(val name: String, val content: String)

/** 用户选中的一个二进制文件（上传图片/视频/音频用）。 */
data class PickedBinary(val name: String, val mime: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is PickedBinary && other.name == name
    override fun hashCode(): Int = name.hashCode()
}

/**
 * 打开系统文件选择器选取文本文件（.md/.txt/.csv/.json）。
 * 用户取消时回调空列表；读取在后台线程完成，回调切回主线程。
 */
expect fun pickTextFiles(extensions: List<String>, multiple: Boolean, onPicked: (List<PickedFile>) -> Unit)

/**
 * 打开系统文件选择器选取二进制媒体文件（图片/视频/音频）。
 * mime 为 MIME 前缀过滤（如 "image/"、"video/"、"audio/"）；取消回调空列表。
 */
expect fun pickBinaryFiles(mimePrefixes: List<String>, multiple: Boolean, onPicked: (List<PickedBinary>) -> Unit)

/** 按文件名猜 MIME（上传时告知服务器）。 */
fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
        "webp" -> "image/webp"; "svg" -> "image/svg+xml"; "avif" -> "image/avif"
        "bmp" -> "image/bmp"; "ico" -> "image/x-icon"; "apng" -> "image/apng"
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "ogv" -> "video/ogg"
        "mov" -> "video/quicktime"; "m4v" -> "video/x-m4v"; "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        else -> "application/octet-stream"
    }
}

expect val platformStore: KeyValueStore
expect val platformExporter: FileExporter
expect val platformClipboard: Clip
expect val platformName: String

/** True on phone/tablet targets; drives compact layout (single-column grids, stacked headers). */
expect val platformHandheld: Boolean

/** Opens a URL with the OS handler (used by "repository" links etc). */
expect fun openUrl(url: String)

/**
 * Current wall-clock time in epoch milliseconds.
 *
 * commonMain has no `java.lang.System`, and `kotlinx.datetime.Clock.System`
 * does not resolve against kotlinx-datetime 0.8.0, so time reads go through
 * this expect/actual instead.
 */
expect fun nowEpochMillis(): Long

/** Hub StateFlow 写入：Android 必须在主线程，否则 Compose 可能收不到后续推送。 */
internal expect fun publishHubUpdate(block: () -> Unit)

/**
 * 把图片字节解码为 [androidx.compose.ui.graphics.ImageBitmap]（png/jpeg/gif/webp…）。
 * 解码失败（损坏/不支持的格式，如 SVG）返回 null，调用方渲染占位卡片。
 * Android 用 BitmapFactory；Desktop 用 Skia makeFromEncoded。
 */
expect fun decodeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap?

/** Android needs an Application reference before [platformStore] is usable. */
object AppContextHolder {
    internal var app: Any? = null
    fun init(app: Any?) { if (this.app == null) this.app = app }
}
