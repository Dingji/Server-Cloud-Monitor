package com.servermaintance.panel.pages.term

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.KeyEvent
import java.awt.event.KeyEvent as AwtKeyEvent

actual fun KeyEvent.platformTerminalChar(): String? {
    val awt = awtEventOrNull ?: return null
    val ch = awt.keyChar
    if (ch == AwtKeyEvent.CHAR_UNDEFINED || ch.isISOControl()) return null
    return ch.toString()
}
