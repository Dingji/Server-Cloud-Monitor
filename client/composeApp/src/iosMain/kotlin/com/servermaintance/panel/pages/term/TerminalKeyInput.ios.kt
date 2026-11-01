package com.servermaintance.panel.pages.term

import androidx.compose.ui.input.key.KeyEvent

/**
 * iOS 的 KeyEvent 不带 AWT 那种已解析字符（`keyChar`）；可打印字符走透明 TextField
 * 的 IME 通道发送，特殊键走 `keySequence` 映射，因此这里与 Android 一致返回 null。
 */
actual fun KeyEvent.platformTerminalChar(): String? = null
