package com.servermaintance.panel.pages.term

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual defaults modeled after common Linux desktop terminals (GNOME Terminal / VTE, Tango palette).
 */
object TerminalTheme {
    /** Near-black canvas, similar to default xterm on many distros. */
    val background = Color(0xFF0C0C0C)

    /** Default foreground when the PTY does not set SGR. */
    val foreground = Color(0xFFBBBBBB)

    /** Block cursor uses the same hue as default text (classic hardware terminal). */
    val cursor = foreground

    val frameBorder = Color(0xFF3A3A3A)
    val frameOuter = Color(0xFF1A1A1A)

    val contentPaddingH = 8.dp
    val contentPaddingV = 6.dp

    val fontSize = 14.sp
    val cornerRadius = 6.dp
}

@Composable
fun TerminalSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(TerminalTheme.cornerRadius))
            .background(TerminalTheme.frameOuter)
            .border(1.dp, TerminalTheme.frameBorder, RoundedCornerShape(TerminalTheme.cornerRadius)),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(TerminalTheme.cornerRadius))
                .background(TerminalTheme.background),
            content = content,
        )
    }
}
