package com.servermaintance.panel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import com.servermaintance.panel.platformHandheld
import kotlinx.coroutines.flow.StateFlow

/**
 * 订阅 [StateFlow]：桌面端仍用 [collectAsState]；Android 用 [produceState] 收集，
 * 避免后台线程更新 StateFlow 时界面停在首帧的问题。
 */
@Composable
fun <T> collectPlatformState(flow: StateFlow<T>): State<T> {
    if (!platformHandheld) return flow.collectAsState()
    return produceState(initialValue = flow.value, flow) {
        flow.collect { value = it }
    }
}
