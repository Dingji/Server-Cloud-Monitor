package com.servermaintance.panel.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局页面导航 —— Kotlin 侧等价物 of the web panel's `location.hash = '#/relay'`
 * (`router.navigate(id)` in `core.js`). The panel uses hash links for cross-page
 * jumps (dashboard 的「详情」→ relay 页、命令面板跳页…), which `PageContent`
 * alone cannot express because `Root.kt` keeps `current` as local state.
 *
 * Usage:
 * ```kotlin
 * Navigator.go(PageId.Relay)      // any page
 * // Root.kt:
 * val current by Navigator.current.collectAsState()
 * PageContent(current)
 * ```
 *
 * 由 lead 维护；`Root.kt` 的接线由被授权改 Root 的角色（书记官 pages-relay
 * 的 CommandPaletteHost 挂载）一并落。
 */
object Navigator {

    private val _current = MutableStateFlow(PageId.Dashboard)
    val current: StateFlow<PageId> = _current

    private val _refresh = MutableStateFlow(0)
    val refresh: StateFlow<Int> = _refresh

    private val _bell = MutableStateFlow(0)
    val bellPulse: StateFlow<Int> = _bell

    /** 跳转；重复跳同一页面是幂等 no-op（避免重置滚动/状态）。 */
    fun go(id: PageId) {
        if (_current.value != id) _current.value = id
    }

    /** 命令面板「刷新当前页面」——等价 web 的 location.reload()，由 Root 用 key() 重挂载。 */
    fun reloadCurrent() { _refresh.value = _refresh.value + 1 }

    /** 命令面板「切换告警中心」。 */
    fun toggleBell() { _bell.value = _bell.value + 1 }

    /** 由 Root 持久化到 state store（可选）；当前实现为内存态，与面板 hash 跳转语义一致。 */
    fun restore(id: PageId) { if (_current.value != id) _current.value = id }
}
