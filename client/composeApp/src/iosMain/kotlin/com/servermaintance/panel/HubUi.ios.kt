package com.servermaintance.panel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hub StateFlow 写入必须在主线程（Android 侧同逻辑，见 HubUi.android.kt），
 * 否则 Compose 可能收不到后续推送。
 *
 * `Main.immediate`：已在主线程时同步执行，从后台线程调用则投递到主队列——
 * 与 Android 的 `Looper.myLooper()` 判断 + `Handler.post` 一一对应。
 * commonMain 已经在用这个 dispatcher（AppState.watchHub），不是新引入的假设。
 */
private val hubMainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

internal actual fun publishHubUpdate(block: () -> Unit) {
    hubMainScope.launch { block() }
}
