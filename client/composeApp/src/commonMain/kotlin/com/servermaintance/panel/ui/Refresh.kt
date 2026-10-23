package com.servermaintance.panel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.servermaintance.panel.core.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 复刻 `CM.autoRefresh`（core.js）：
 *  - 按 [intervalMs] 循环（最低 500ms，跟随面板 /me.refreshMs）
 *  - 上一拍还没跑完（busy）则**跳过**这一拍，不叠加请求
 *  - [key] 变化重启；页面离开（组合销毁）协程取消、自动停止
 *  - [pauseWhileEditing] 为 true：输入框聚焦（InputFocus）或 Modal 打开期间暂停
 *    （复刻 web 的 guarded()）
 *  - [block] 抛出的异常吞掉（页面自行处理），协程取消正常传播
 */
@Composable
fun AutoRefresh(
    key: Any?,
    enabled: Boolean = true,
    intervalMs: () -> Long = { AppState.meRefreshMs() },
    pauseWhileEditing: Boolean = true,
    block: suspend () -> Unit,
) {
    val currentBlock by rememberUpdatedState(block)
    val currentInterval by rememberUpdatedState(intervalMs)
    val currentPause by rememberUpdatedState(pauseWhileEditing)

    LaunchedEffect(key, enabled) {
        if (!enabled) return@LaunchedEffect
        var busy = false
        while (isActive) {
            val ms = currentInterval().coerceAtLeast(500L)
            if (!isActive) break
            if (currentPause && (InputFocus.count > 0 || Modal.isOpen)) {
                delay(ms)
                continue
            }
            if (busy) {
                delay(ms)
                continue
            }
            busy = true
            try {
                currentBlock()
            } catch (_: CancellationException) {
                // 单次请求被取消（切页、重组）不应终止整个轮询循环
            } catch (_: Exception) {
                // web: `try { await fn(); } catch (_) {}` —— 轮询失败静默
            } finally {
                busy = false
            }
            delay(ms)
        }
    }
}
