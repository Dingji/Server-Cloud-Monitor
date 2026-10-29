package com.servermaintance.panel
import com.servermaintance.panel.i18n.L

import java.io.File
import java.io.FileOutputStream
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.prefs.Preferences
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.servermaintance.panel.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class PrefsStore : KeyValueStore {
    private val prefs = Preferences.userNodeForPackage(PrefsStore::class.java)
    override fun get(key: String): String? = prefs.get(key, null)
    override fun put(key: String, value: String) {
        prefs.put(key, value)
        // preferences sync asynchronously; flush so workspace state survives
        // an abrupt process exit (crash / task manager / OS shutdown)
        runCatching { prefs.flush() }
    }
    override fun remove(key: String) {
        prefs.remove(key)
        runCatching { prefs.flush() }
    }
}

private class DesktopExporter : FileExporter {
    private fun homeDownloads(): File {
        val out = File(System.getProperty("user.home") ?: ".", "Downloads")
        return if (out.isDirectory) out else File(System.getProperty("user.home") ?: ".", "CloudMonitor")
    }

    override fun defaultDir(): String = homeDownloads().absolutePath

    override suspend fun saveFile(name: String, bytes: ByteArray): String? = runCatching {
        val dir = homeDownloads()
        if (!dir.exists()) dir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(dir, safe)
        out.writeBytes(bytes)
        out.absolutePath
    }.getOrNull()

    /* 系统「另存为」→ 可分块写的句柄。AWT 弹不出来时退到默认下载目录，
       而不是让整次下载失败：用户要的是拿到文件。 */
    override suspend fun beginSave(name: String): SaveHandle? = withContext(Dispatchers.IO) {
        runCatching {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val picked = runCatching {
                val dlg = FileDialog(null as Frame?, L["另存为_a86b5bd5da"], FileDialog.SAVE)
                dlg.file = safe
                dlg.directory = homeDownloads().absolutePath
                dlg.isVisible = true
                val dir = dlg.directory ?: return@runCatching null
                File(dir, (dlg.file ?: safe))
            }.getOrNull()
            val target = picked ?: File(homeDownloads(), safe)
            if (!target.parentFile!!.exists()) target.parentFile!!.mkdirs()
            val out = FileOutputStream(target)
            object : SaveHandle {
                override val displayPath: String get() = target.absolutePath
                override fun write(chunk: ByteArray) { out.write(chunk) }
                override fun close() { runCatching { out.flush() }; runCatching { out.close() } }
                override fun discard() {
                    runCatching { out.close() }
                    runCatching { target.delete() }
                }
            }
        }.getOrNull()
    }
}

private class DesktopClip : Clip {
    override fun copy(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }
}

actual val platformStore: KeyValueStore = PrefsStore()
actual val platformExporter: FileExporter = DesktopExporter()
actual val platformClipboard: Clip = DesktopClip()
actual val platformName: String = "Desktop"
actual val platformHandheld: Boolean = false

actual fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI(url))
    }
}

actual fun nowEpochMillis(): Long = System.currentTimeMillis()

/** 图片解码（Skia makeFromEncoded，支持 png/jpeg/gif/webp/bmp/ico）。 */
actual fun decodeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = try {
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Exception) {
    null
}

/** Desktop：Swing JFileChooser（EDT 打开，读文件走后台线程）。 */
actual fun pickTextFiles(extensions: List<String>, multiple: Boolean, onPicked: (List<PickedFile>) -> Unit) {
    javax.swing.SwingUtilities.invokeLater {
        val fc = javax.swing.JFileChooser()
        fc.fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        fc.isMultiSelectionEnabled = multiple
        if (extensions.isNotEmpty()) {
            val filter = javax.swing.filechooser.FileNameExtensionFilter(
                trf("文本文件_0_69e2147621", extensions.joinToString(" ") { "*.$it" }),
                *extensions.toTypedArray(),
            )
            fc.addChoosableFileFilter(filter)
            fc.fileFilter = filter // 默认选中刚添加的过滤器
        }
        val result = fc.showOpenDialog(null)
        if (result != javax.swing.JFileChooser.APPROVE_OPTION) {
            onPicked(emptyList())
            return@invokeLater
        }
        val files = (if (multiple) fc.selectedFiles.toList() else listOfNotNull(fc.selectedFile))
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val picked = files.mapNotNull { f ->
                runCatching { PickedFile(f.name, f.readText(Charsets.UTF_8)) }.getOrNull()
            }
            javax.swing.SwingUtilities.invokeLater { onPicked(picked) }
        }
    }
}

/** Desktop：媒体文件选择（同 JFileChooser，读 bytes）。 */
actual fun pickBinaryFiles(mimePrefixes: List<String>, multiple: Boolean, onPicked: (List<PickedBinary>) -> Unit) {
    javax.swing.SwingUtilities.invokeLater {
        val fc = javax.swing.JFileChooser()
        fc.fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        fc.isMultiSelectionEnabled = multiple
        val result = fc.showOpenDialog(null)
        if (result != javax.swing.JFileChooser.APPROVE_OPTION) {
            onPicked(emptyList())
            return@invokeLater
        }
        val files = (if (multiple) fc.selectedFiles.toList() else listOfNotNull(fc.selectedFile))
            .filter { f -> mimePrefixes.isEmpty() || mimePrefixes.any { guessMime(f.name).startsWith(it) } }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val picked = files.mapNotNull { f ->
                runCatching { PickedBinary(f.name, guessMime(f.name), f.readBytes()) }.getOrNull()
            }
            javax.swing.SwingUtilities.invokeLater { onPicked(picked) }
        }
    }
}
