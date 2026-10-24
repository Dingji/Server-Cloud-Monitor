package com.servermaintance.panel
import com.servermaintance.panel.i18n.L

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun context(): Context? = AppContextHolder.app as? Context

private class SharedPrefsStore : KeyValueStore {
    private fun prefs() = context()?.getSharedPreferences("cm_panel", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs()?.getString(key, null)
    override fun put(key: String, value: String) {
        prefs()?.edit()?.putString(key, value)?.commit()
    }
    override fun remove(key: String) { prefs()?.edit()?.remove(key)?.commit() }
}

private class AndroidExporter : FileExporter {
    override fun defaultDir(): String =
        (context()?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context()?.filesDir
            ?: File("/sdcard/Download")).absolutePath

    /**
     * API 29+ moved shared Downloads behind MediaStore (needs WRITE permission),
     * so we prefer the app-specific directory which is always writable and is a
     * real, reachable path ("Android/data/<pkg>/files/Download").
     */
    override suspend fun saveFile(name: String, bytes: ByteArray): String? = runCatching {
        val dir = (context()?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context()?.filesDir ?: return@runCatching null)
        if (!dir.exists()) dir.mkdirs()
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(dir, safe)
        out.writeBytes(bytes)
        out.absolutePath
    }.getOrNull()
}

private class AndroidClip : Clip {
    override fun copy(text: String) {
        val ctx = context() ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(L["cloud_monitor_5e4d4fbbe5"], text))
    }
}

actual val platformStore: KeyValueStore = SharedPrefsStore()
actual val platformExporter: FileExporter = AndroidExporter()
actual val platformClipboard: Clip = AndroidClip()
actual val platformName: String = "Android"
actual val platformHandheld: Boolean = true

actual fun openUrl(url: String) {
    val ctx = context() ?: return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

actual fun nowEpochMillis(): Long = System.currentTimeMillis()

/** 图片解码（BitmapFactory；超大图先降采样，避免 OOM）。 */
actual fun decodeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= 2048) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?.asImageBitmap()
} catch (_: Exception) {
    null
}

/**
 * SAF 文件选择桥：MainActivity.onCreate 注册 launcher，这里只负责发起与消费。
 * 结果回调已在主线程；读文件内容放后台线程。
 */
object AndroidPickerBridge {
    internal var single: ActivityResultLauncher<Array<String>>? = null
    internal var multi: ActivityResultLauncher<Array<String>>? = null
    internal var pending: ((List<PickedFile>) -> Unit)? = null
    internal var pendingBinary: ((List<PickedBinary>) -> Unit)? = null

    // MainActivity 注册（单选）。SAF 合约 input 恒为 Array<String>（MIME 列表）。
    fun registerSingle(launcher: ActivityResultLauncher<Array<String>>) { single = launcher }

    // MainActivity 注册（多选）。
    fun registerMulti(launcher: ActivityResultLauncher<Array<String>>) { multi = launcher }

    /** SAF 结果统一入口：按当前挂起的回调类型分发（文本 / 二进制）。 */
    internal fun onResult(uris: List<Uri>) {
        val textCb = pending.also { pending = null }
        val binCb = pendingBinary.also { pendingBinary = null }
        when {
            textCb != null -> deliver(uris, textCb)
            binCb != null -> deliverBinary(uris, binCb)
        }
    }

    internal fun deliver(uris: List<Uri>, callback: ((List<PickedFile>) -> Unit)?) {
        if (callback == null) return
        if (uris.isEmpty()) { callback(emptyList()); return }
        val ctx = context()
        if (ctx == null) { callback(emptyList()); return }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val picked = uris.mapNotNull { uri ->
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?: uri.lastPathSegment
                        ?: "import.txt"
                    val text = ctx.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes().decodeToString() }
                        ?: return@mapNotNull null
                    PickedFile(name, text)
                }.getOrNull()
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { callback(picked) }
        }
    }

    internal fun deliverBinary(uris: List<Uri>, callback: ((List<PickedBinary>) -> Unit)?) {
        if (callback == null) return
        if (uris.isEmpty()) { callback(emptyList()); return }
        val ctx = context()
        if (ctx == null) { callback(emptyList()); return }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val picked = uris.mapNotNull { uri ->
                runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?: uri.lastPathSegment
                        ?: "file.bin"
                    val bytes = ctx.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: return@mapNotNull null
                    PickedBinary(name, guessMime(name), bytes)
                }.getOrNull()
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { callback(picked) }
        }
    }
}

actual fun pickTextFiles(extensions: List<String>, multiple: Boolean, onPicked: (List<PickedFile>) -> Unit) {
    AndroidPickerBridge.pending = onPicked
    AndroidPickerBridge.pendingBinary = null
    val launched = if (multiple) {
        AndroidPickerBridge.multi?.also { it.launch(arrayOf("*/*")) } != null
    } else {
        AndroidPickerBridge.single?.also { it.launch(arrayOf("*/*")) } != null
    }
    if (!launched) {
        AndroidPickerBridge.pending = null
        onPicked(emptyList())
    }
}

actual fun pickBinaryFiles(mimePrefixes: List<String>, multiple: Boolean, onPicked: (List<PickedBinary>) -> Unit) {
    AndroidPickerBridge.pendingBinary = onPicked
    AndroidPickerBridge.pending = null
    // image/*、video/*、audio/* 前缀直接当 MIME 传给 SAF；混合前缀回退 */*
    val mimes = mimePrefixes.filter { it.endsWith("/*") }.ifEmpty { listOf("*/*") }
    val launched = if (multiple) {
        AndroidPickerBridge.multi?.also { it.launch(mimes.toTypedArray()) } != null
    } else {
        AndroidPickerBridge.single?.also { it.launch(mimes.toTypedArray()) } != null
    }
    if (!launched) {
        AndroidPickerBridge.pendingBinary = null
        onPicked(emptyList())
    }
}
