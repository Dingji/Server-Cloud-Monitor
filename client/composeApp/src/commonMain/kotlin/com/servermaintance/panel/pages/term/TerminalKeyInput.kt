package com.servermaintance.panel.pages.term

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key

/** 方向键、退格等 VT 序列（在 TextField 的 onPreviewKeyEvent 里优先消费）。 */
internal fun terminalSpecialBytesForKeyEvent(event: KeyEvent): String? {
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
    return keySequence(event.key)
}

/** 桌面物理键盘在 Key 映射失败时的兜底（见 desktop actual）。 */
internal expect fun KeyEvent.platformTerminalChar(): String?
