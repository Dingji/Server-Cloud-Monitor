package com.servermaintance.panel.nav

import com.servermaintance.panel.platformStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 跨启动的工作区状态：上次访问页面、收藏页、最近访问。
 * 仅存路由字符串，未知/已下线路由在读取时丢弃。
 */
object Workspace {

    private const val K_LAST = "ui_last_page"
    private const val K_PINNED = "ui_pinned_pages"
    private const val K_RECENT = "ui_recent_pages"
    private const val MAX_RECENT = 6

    private val _pinned = MutableStateFlow<List<PageId>>(emptyList())
    val pinned: StateFlow<List<PageId>> = _pinned

    private val _recent = MutableStateFlow<List<PageId>>(emptyList())
    val recent: StateFlow<List<PageId>> = _recent

    private var loaded = false

    private fun parse(raw: String?): List<PageId> =
        raw.orEmpty().split(',').mapNotNull { pageIdOf(it) }.distinct()

    private fun encode(ids: List<PageId>): String = ids.joinToString(",") { it.route }

    fun load() {
        if (loaded) return
        loaded = true
        _pinned.value = parse(platformStore.get(K_PINNED))
        _recent.value = parse(platformStore.get(K_RECENT))
    }

    fun lastPage(): PageId? {
        load()
        return pageIdOf(platformStore.get(K_LAST))
    }

    fun visit(id: PageId) {
        load()
        platformStore.put(K_LAST, id.route)
        _recent.value = (listOf(id) + _recent.value.filterNot { it == id }).take(MAX_RECENT)
        platformStore.put(K_RECENT, encode(_recent.value))
    }

    fun isPinned(id: PageId): Boolean {
        load()
        return id in _pinned.value
    }

    fun togglePin(id: PageId) {
        load()
        _pinned.value = if (id in _pinned.value) _pinned.value - id else _pinned.value + id
        platformStore.put(K_PINNED, encode(_pinned.value))
    }

    fun clearRecent() {
        load()
        _recent.value = emptyList()
        platformStore.remove(K_RECENT)
    }
}
