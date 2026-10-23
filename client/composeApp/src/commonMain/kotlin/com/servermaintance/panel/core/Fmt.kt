package com.servermaintance.panel.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.servermaintance.panel.nowEpochMillis

/**
 * Kotlin 等价物 of the panel's `window.fmt` (`upstream/public/js/core.js`).
 *
 * 供 ui-core 与所有 pages 角色共用：**照抄 core.js 的算法与文案**，别再各写一份。
 *
 * ```kotlin
 * Fmt.bytes(1234567L)   // "1.18 MB"
 * Fmt.bps(1024)         // "1.00 KB/s"
 * Fmt.pctColor(95.0)    // "bad"
 * Fmt.uptime(90061)     // "1天 1时"
 * Fmt.ts(ts)            // "14:03:22"（refreshMs < 1000 时补十分位）
 * ```
 */
object Fmt {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** `fmt.bytes` —— 1024 进制，保留规则同 JS（0 位 / ≥100 位 / ≥10 一位 / 其余两位）。 */
    fun bytes(n: Number?): String {
        if (n == null) return "-"
        val raw = n.toDouble()
        if (raw.isNaN()) return "-"
        var i = 0
        var v = kotlin.math.abs(raw)
        while (v >= 1024 && i < UNITS.size - 1) {
            v /= 1024
            i++
        }
        val num = when {
            i == 0 -> fixed(v, 0)
            v >= 100 -> fixed(v, 0)
            v >= 10 -> fixed(v, 1)
            else -> fixed(v, 2)
        }
        return (if (raw < 0) "-" else "") + num + " " + UNITS[i]
    }

    /** `fmt.bps` —— 字节/秒。 */
    fun bps(n: Number?): String = bytes(n) + "/s"

    /** `fmt.pctColor` —— ≥90 bad、≥75 warn、其余 ok（对应 `Palette.pct` 阈值）。 */
    fun pctColor(p: Number?): String {
        val v = p?.toDouble() ?: 0.0
        return when {
            v >= 90 -> "bad"
            v >= 75 -> "warn"
            else -> "ok"
        }
    }

    /** `fmt.uptime` —— 天/时/分/秒的面板简写。 */
    fun uptime(sec: Number?): String {
        val total = kotlin.math.floor((sec?.toDouble() ?: 0.0).coerceAtLeast(0.0)).toLong()
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            d > 0 -> "${d}天 ${h}时"
            h > 0 -> "${h}时 ${m}分"
            m > 0 -> "${m}分"
            else -> "${s}秒"
        }
    }

    /**
     * `fmt.esc` —— 面板用它做 HTML 转义；Compose 里无害，保留以对齐调用点。
     */
    fun esc(s: Any?): String {
        val str = s?.toString() ?: ""
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * `fmt.ts` —— `HH:mm:ss`（面板 `hour12: false` 的本地时间）。
     * 亚秒级刷新（`CM.me.refreshMs < 1000`）时补一位十分位，避免同秒标签重复。
     */
    fun ts(t: Number?): String = ts(t, refreshMs())

    fun ts(t: Number?, refreshMs: Long): String {
        val ms = t?.toLong() ?: 0L
        if (ms <= 0L) return "--:--:--"
        val ldt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
        val base = two(ldt.hour) + ":" + two(ldt.minute) + ":" + two(ldt.second)
        return if (refreshMs < 1000) base + "." + ((ms % 1000) / 100) else base
    }

    /** `new Date(t).toLocaleString('zh-CN', {hour12:false})` 的等价物（告警列表用）。 */
    fun dateTime(t: Number?): String {
        val ms = t?.toLong() ?: 0L
        if (ms <= 0L) return "--"
        val ldt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${ldt.year}-${two(ldt.monthNumber)}-${two(ldt.dayOfMonth)} " +
            two(ldt.hour) + ":" + two(ldt.minute) + ":" + two(ldt.second)
    }

    /** 顶栏本地时钟（每秒刷新）。 */
    fun clock(): String {
        val ldt = Instant.fromEpochMilliseconds(nowEpochMillis())
            .toLocalDateTime(TimeZone.currentSystemDefault())
        return two(ldt.hour) + ":" + two(ldt.minute) + ":" + two(ldt.second)
    }

    /** 相对时间：刚刚 / N 秒前 / N 分钟前 / N 小时前 / N 天前（超出 7 天回落 dateTime）。 */
    fun relativeTime(t: Number?): String {
        val ms = t?.toLong() ?: 0L
        if (ms <= 0L) return "--"
        val diff = nowEpochMillis() - ms
        return when {
            diff < 10_000 -> "刚刚"
            diff < 60_000 -> "${diff / 1000} 秒前"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            diff < 7L * 86_400_000 -> "${diff / 86_400_000} 天前"
            else -> dateTime(t)
        }
    }

    /** 百分比展示：`99.1%`（≤0.1 精度，去掉多余 0）。 */
    fun pct(p: Number?, suffix: String = "%"): String {
        val v = p?.toDouble() ?: 0.0
        if (v.isNaN()) return "-"
        val s = fixed(v, 1)
        return (if (s.endsWith(".0")) s.dropLast(2) else s) + suffix
    }

    /** 千分位整数（进程数、连接数等）。 */
    fun count(n: Number?): String {
        val v = n?.toLong() ?: 0L
        val neg = v < 0
        val digits = kotlin.math.abs(v).toString()
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return (if (neg) "-" else "") + sb
    }

    /** 当前 `/me.refreshMs`，缺省 2000，最小 500（同 `CM.REFRESH_MIN_MS`）。 */
    fun refreshMs(): Long = AppState.meRefreshMs()

    /* -------------------- 小工具 -------------------- */

    private fun fixed(v: Double, digits: Int): String {
        // commonMain 没有 String.format，手写定点数（避免引入 java.* ）。
        if (v.isNaN() || v.isInfinite()) return "-"
        val neg = v < 0
        var a = kotlin.math.abs(v)
        val factor = POW10(digits)
        a = kotlin.math.round(a * factor) / factor
        val intPart = kotlin.math.floor(a).toLong()
        if (digits == 0) return (if (neg) "-" else "") + intPart
        val fracRaw = kotlin.math.round((a - intPart) * factor).toLong()
        var frac = fracRaw.toString()
        while (frac.length < digits) frac = "0" + frac
        // 进位可能让小数部分溢出（如 0.999 -> 1.00）
        if (fracRaw >= factor) {
            val carry = intPart + 1
            return (if (neg) "-" else "") + carry + "." + "0".repeat(digits)
        }
        return (if (neg) "-" else "") + intPart + "." + frac
    }

    private fun POW10(d: Int): Long = when (d) {
        0 -> 1L; 1 -> 10L; 2 -> 100L; 3 -> 1000L; 4 -> 10000L; else -> 100000L
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()
}
