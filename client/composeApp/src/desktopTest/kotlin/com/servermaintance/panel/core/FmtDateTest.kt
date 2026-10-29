package com.servermaintance.panel.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 日期时间格式化的对照测试。
 *
 * 存在的理由：kotlinx-datetime 0.8 把 `Instant` 与 `LocalDateTime.monthNumber/dayOfMonth`
 * 都标了废弃（改用 `kotlin.time.Instant` 与 `month` / `day`），全仓 20 处告警就是这么来的。
 * 换 API 属于「静默错位」高危区（月份从 1 起、`Month.number = ordinal + 1`），
 * 所以拿 `java.time` 这条完全独立的实现当基准，而不是再用手改过的同一套 API 自证。
 */
class FmtDateTest {

    /** 覆盖月初/年末/跨世纪/两位数月日，全部是真实毫秒时间戳。 */
    private val samples = longArrayOf(
        946_684_800_000L, // 2000-01-01T00:00:00Z —— 月与日都是最小值
        1_700_000_000_000L, // 2023-11-14T22:13:20Z
        1_735_689_599_000L, // 2024-12-31T23:59:59Z —— 跨年最后一秒
        1_767_196_800_000L, // 2026-01-01T00:00:00Z —— 换年后第一天
        4_102_444_800_000L, // 2100-01-01T00:00:00Z
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    @Test
    fun dateTimeMatchesJavaTimeInSystemZone() {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        for (ms in samples) {
            assertEquals(
                Instant.ofEpochMilli(ms).atZone(zone).format(f),
                Fmt.dateTime(ms),
                "Fmt.dateTime($ms) 与 java.time 在当前系统时区下不一致",
            )
        }
    }

    @Test
    fun tsMatchesJavaTimeInSystemZone() {
        val f = DateTimeFormatter.ofPattern("HH:mm:ss")
        for (ms in samples) {
            assertEquals(
                Instant.ofEpochMilli(ms).atZone(zone).format(f),
                Fmt.ts(ms, 5_000L),
                "Fmt.ts($ms, 5000) 与 java.time 在当前系统时区下不一致",
            )
        }
    }

    @Test
    fun subSecondSuffixOnlyUnderOneSecondRefresh() {
        // 面板 500ms 一拍时时间戳要带一位小数（百毫秒位），>=1s 时不带
        val ms = 1_700_000_000_100L
        val base = Fmt.ts(ms, 5_000L)
        assertEquals(base, Fmt.ts(ms, 1_000L))
        assertEquals("$base.1", Fmt.ts(ms, 500L))
    }

    @Test
    fun zeroAndNullFallBackToPlaceholders() {
        assertEquals("--", Fmt.dateTime(0L))
        assertEquals("--:--:--", Fmt.ts(null, 500L))
    }
}
