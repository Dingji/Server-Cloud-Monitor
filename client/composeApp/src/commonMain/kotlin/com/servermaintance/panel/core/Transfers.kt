package com.servermaintance.panel.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一次传输的可见状态。
 *
 * 进度数字来自 Ktor 的 onDownload / onUpload 回调（它们报的是真正写进 socket 的字节），
 * 不是客户端自己按块数累加——后者在压缩与分块下会偏。
 */
class Transfer(val id: Long, val name: String, val kind: Kind, total0: Long) {
    enum class Kind { Download, Upload }

    /** 下载时总长来自响应头，可能要到第一个字节才知道，所以是可变的。 */
    var total by mutableStateOf(total0)
    var sent by mutableStateOf(0L)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var path by mutableStateOf<String?>(null)

    /** 未知总长时返回 0，界面据此显示「大小未知」而不是画一根假进度条。 */
    val known: Boolean get() = total > 0L
    val fraction: Float
        get() = if (total > 0L) (sent.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    val finished: Boolean get() = done || error != null
}

/** 全局传输队列：任何页面发起的上传/下载都汇到这里，所以切页签也不会丢进度。 */
object Transfers {
    private var seq = 0L
    private val _items = mutableStateListOf<Transfer>()

    val items: List<Transfer> get() = _items
    val active: Int get() = _items.count { !it.finished }

    fun start(kind: Transfer.Kind, name: String, total: Long): Transfer {
        val t = Transfer(++seq, name, kind, total)
        _items.add(0, t)
        while (_items.size > 20) _items.removeAt(_items.size - 1)
        return t
    }

    fun progress(t: Transfer, sent: Long) {
        t.sent = sent
    }

    fun succeed(t: Transfer, path: String?) {
        t.done = true
        t.path = path
        if (t.total > 0L) t.sent = t.total
    }

    fun fail(t: Transfer, message: String?) {
        t.error = message?.takeIf { it.isNotBlank() } ?: "failed"
        // 失败的那条不要显示成「传到一半完成」
        if (t.total > 0L && t.sent > t.total) t.sent = t.total
    }

    fun clearFinished() {
        val it = _items.listIterator()
        while (it.hasNext()) if (it.next().finished) it.remove()
    }
}
