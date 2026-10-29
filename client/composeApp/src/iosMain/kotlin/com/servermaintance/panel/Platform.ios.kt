package com.servermaintance.panel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableData
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.posix.memcpy
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/* ============================ 基础服务 ============================ */

/** UserDefaults（等价 Android SharedPreferences / Desktop Preferences）。 */
private class IosStore : KeyValueStore {
    // 类属性映射成 companion 成员，取值不带括号（multiplatform-settings 原样写法）。
    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun put(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

/**
 * 落盘目录 = 沙盒 `Documents`。
 *
 * Android 用应用私有 Download（免权限、可在文件管理器里翻到），Desktop 用 `~/Downloads`。
 * iOS 的对等物只有沙盒目录，所以壳工程需要 `UIFileSharingEnabled=YES`
 * （见 iosApp/iosApp/Info.plist），导出的备份包才能在「文件」App 里被看到，
 * 否则用户拿到路径也无从下手。
 */
@OptIn(ExperimentalForeignApi::class)
private fun documentsDir(): String? = runCatching {
    val dir: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        NSDocumentDirectory, NSUserDomainMask, null, true, null,
    )
    dir?.path
}.getOrNull()

private class IosExporter : FileExporter {
    override fun defaultDir(): String = documentsDir() ?: ""

    @OptIn(BetaInteropApi::class)
    override suspend fun saveFile(name: String, bytes: ByteArray): String? = runCatching {
        val dir = documentsDir() ?: return@runCatching null
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = "$dir/$safe"
        val url = NSURL.fileURLWithPath(out)
        if (bytes.toNSData().writeToURL(url, atomically = true)) out else null
    }.getOrNull()
}

private class IosClip : Clip {
    // CMP 1.12.2 自己的 PlatformClipboard.ios.kt 就是这一行（`string` 是 NSString? 属性）。
    override fun copy(text: String) {
        runCatching { UIPasteboard.generalPasteboard.string = text }
    }
}

actual val platformStore: KeyValueStore = IosStore()
actual val platformExporter: FileExporter = IosExporter()
actual val platformClipboard: Clip = IosClip()
actual val platformName: String = "iOS"
actual val platformHandheld: Boolean = true

/** 与 CMP 1.12.2 `PlatformUriHandler.ios.kt` 同款三参形式（旧的单参 `openURL:` 已废弃）。 */
@OptIn(BetaInteropApi::class)
actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    runCatching {
        UIApplication.sharedApplication.openURL(
            url = nsUrl,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }
}

/**
 * 走 stdlib 的 `kotlin.time.Clock`（Kotlin 2.2+ 已进 common stdlib，kotlinx-datetime 0.8.0
 * 自己也 re-export 它），不需要 NSDate interop。
 */
actual fun nowEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** 与 Desktop 完全同源：Skia `makeFromEncoded`，因此三端解码结果一致。 */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Exception) {
    null
}

/* ============================ 文件选择 ============================ */

/**
 * 选择器宿主。`root` 由 [MainViewController] 创建根 VC 时登记；
 * `retain` 强引用 delegate —— UIKit 的 `delegate` 属性是 weak，不持有会被回收。
 */
internal object IosPickerHost {
    var root: UIViewController? = null
    var pendingText: ((List<PickedFile>) -> Unit)? = null
    var pendingBinary: ((List<PickedBinary>) -> Unit)? = null
    var retain: Any? = null
}

/** mime 前缀 → UTI；识别不了就放开到全部文件（Android 侧同样传通配 MIME）。 */
private fun utisFor(mimePrefixes: List<String>): List<String> =
    mimePrefixes.mapNotNull { prefix ->
        when {
            prefix.startsWith("image/") -> "public.image"
            prefix.startsWith("video/") -> "public.movie"
            prefix.startsWith("audio/") -> "public.audio"
            else -> null
        }
    }.ifEmpty { listOf("public.data") }

/**
 * 呈现成功返回 true；没有宿主 VC 时返回 false，由调用方立刻回空列表
 * （对齐 Android「launcher 未注册」那条分支）。
 *
 * `documentTypes:inMode:` 自 iOS 14 起废弃但 K/N 不把它翻成 Kotlin `@Deprecated`，
 * 仍是可用 API；这里用它而不是 `forOpeningContentTypes:`，因为后者要引
 * `platform.UniformTypeIdentifiers` 的一堆 UTType 常量名，符号面更大。
 */
@OptIn(BetaInteropApi::class)
private fun presentPicker(delegate: UIDocumentPickerDelegateProtocol, utis: List<String>, multiple: Boolean): Boolean {
    val host = IosPickerHost.root ?: return false
    // 两个 init 都收成 (List<*>, mode)，位置实参会撞上 initWithURLs:inMode: 的歧义，
    // 必须用 documentTypes 具名实参。
    val picker = UIDocumentPickerViewController(
        documentTypes = utis,
        inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
    )
    picker.allowsMultipleSelection = multiple
    // 全屏呈现：pageSheet 的下滑关闭不会触发 documentPickerWasCancelled，
    // 挂起的回调会静默丢失（导入无声失败）。Android 的 SAF 本来就是全屏。
    picker.modalPresentationStyle = platform.UIKit.UIModalPresentationFullScreen
    picker.delegate = delegate
    IosPickerHost.retain = delegate
    host.presentViewController(picker, animated = true, completion = null)
    return true
}

actual fun pickTextFiles(extensions: List<String>, multiple: Boolean, onPicked: (List<PickedFile>) -> Unit) {
    IosPickerHost.pendingText = onPicked
    IosPickerHost.pendingBinary = null
    if (!presentPicker(TextPickerDelegate(), utisFor(emptyList()), multiple)) {
        IosPickerHost.pendingText = null
        IosPickerHost.retain = null
        onPicked(emptyList())
    }
}

actual fun pickBinaryFiles(mimePrefixes: List<String>, multiple: Boolean, onPicked: (List<PickedBinary>) -> Unit) {
    IosPickerHost.pendingBinary = onPicked
    IosPickerHost.pendingText = null
    if (!presentPicker(BinaryPickerDelegate(), utisFor(mimePrefixes), multiple)) {
        IosPickerHost.pendingBinary = null
        IosPickerHost.retain = null
        onPicked(emptyList())
    }
}

@OptIn(BetaInteropApi::class)
private class TextPickerDelegate : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        deliverText(didPickDocumentsAtURLs)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        deliverText(emptyList<Any?>())
    }
}

@OptIn(BetaInteropApi::class)
private class BinaryPickerDelegate : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        deliverBinary(didPickDocumentsAtURLs)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        deliverBinary(emptyList<Any?>())
    }
}

/** 读文件放后台线程、回调切回主线程，与 Android 的 `AndroidPickerBridge.deliver` 一致。 */
private fun deliverText(urls: List<*>) {
    val callback = IosPickerHost.pendingText
    IosPickerHost.pendingText = null
    IosPickerHost.retain = null
    if (callback == null) return
    CoroutineScope(Dispatchers.Default).launch {
        // 与 Android 一样按 UTF-8 解码字节，而不是走 NSString 的编码猜测。
        val picked = urls.mapNotNull { item ->
            val url = item as? NSURL ?: return@mapNotNull null
            val bytes = url.readBytes() ?: return@mapNotNull null
            PickedFile(url.lastPathComponent ?: "import.txt", bytes.decodeToString())
        }
        withContext(Dispatchers.Main.immediate) { callback(picked) }
    }
}

private fun deliverBinary(urls: List<*>) {
    val callback = IosPickerHost.pendingBinary
    IosPickerHost.pendingBinary = null
    IosPickerHost.retain = null
    if (callback == null) return
    CoroutineScope(Dispatchers.Default).launch {
        val picked = urls.mapNotNull { item ->
            val url = item as? NSURL ?: return@mapNotNull null
            val bytes = url.readBytes() ?: return@mapNotNull null
            val name = url.lastPathComponent ?: "file.bin"
            PickedBinary(name, guessMime(name), bytes)
        }
        withContext(Dispatchers.Main.immediate) { callback(picked) }
    }
}

/** `+dataWithContentsOfURL:` 在 K/N 里是 companion 上的扩展函数，需要单独 import。 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private fun NSURL.readBytes(): ByteArray? =
    NSData.dataWithContentsOfURL(this)?.toByteArray()

/** 沙盒内绝对路径 → 字节；[readBundledText] 也用同一套。 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal fun readFileBytes(absPath: String): ByteArray? =
    NSData.dataWithContentsOfURL(NSURL(fileURLWithPath = absPath))?.toByteArray()

/** 与 Ktor `ktor-client-darwin` 里 `NSData.toByteArray()` 同一段实现。 */
@OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isEmpty()) return result
    result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}

/** 与 Ktor `ktor-client-darwin` 里 `ByteArray.toNSData()` 同一段实现。 */
@OptIn(UnsafeNumber::class, ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSMutableData()
    else this.usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
