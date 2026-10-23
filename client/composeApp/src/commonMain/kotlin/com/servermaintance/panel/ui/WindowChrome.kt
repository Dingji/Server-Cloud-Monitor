package com.servermaintance.panel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Desktop window controls. Null on Android, so shared screens stay free of caption buttons.
 * [dragModifier] moves the window; put it on the bar, not on the buttons.
 */
class WindowChrome(
    val maximized: Boolean,
    val dragModifier: Modifier,
    val onMinimize: () -> Unit,
    val onToggleMaximize: () -> Unit,
    val onClose: () -> Unit,
)

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/** Desktop caption hover; always false on Android (no hover / no collectIsHoveredAsState). */
@Composable
internal expect fun captionButtonHovered(interaction: MutableInteractionSource): Boolean

private val CaptionBtnWidth = 46.dp
private val CloseHover = Color(0xFFC42B1C)

/** Login / splash caption. The main shell embeds the same buttons in its own top bar. */
@Composable
fun WithWindowCaption(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val chrome = LocalWindowChrome.current
    if (chrome == null) {
        content()
        return
    }
    Column(Modifier.fillMaxSize()) {
        CaptionStrip(chrome, title, subtitle)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun CaptionStrip(chrome: WindowChrome, title: String, subtitle: String?) {
    Column(Modifier.fillMaxWidth().background(Palette.rail)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().then(chrome.dragModifier).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(18.dp)
                        .background(Palette.inset, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    PanelIcon("server", 12.dp, AccentState.current.secondary)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    color = Palette.text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    maxLines = 1,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = subtitle,
                        color = Palette.muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            WindowCaptionButtons(chrome)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
    }
}

@Composable
fun WindowCaptionButtons(chrome: WindowChrome) {
    Row(Modifier.fillMaxHeight()) {
        CaptionButton(onClick = chrome.onMinimize) { CaptionGlyph.Minimize(it) }
        CaptionButton(onClick = chrome.onToggleMaximize) {
            if (chrome.maximized) CaptionGlyph.Restore(it) else CaptionGlyph.Maximize(it)
        }
        CaptionButton(onClick = chrome.onClose, destructive = true) { CaptionGlyph.Close(it) }
    }
}

@Composable
private fun CaptionButton(
    onClick: () -> Unit,
    destructive: Boolean = false,
    glyph: @Composable (Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered = captionButtonHovered(interaction)
    val bg = when {
        hovered && destructive -> CloseHover
        hovered -> Palette.text.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val fg = if (hovered && destructive) Color.White else Palette.text
    Box(
        Modifier
            .width(CaptionBtnWidth)
            .fillMaxHeight()
            .hoverable(interaction)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        glyph(fg)
    }
}

private object CaptionGlyph {
    @Composable
    fun Minimize(color: Color) {
        Canvas(Modifier.size(10.dp)) {
            val y = size.height / 2f
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.15f)
        }
    }

    @Composable
    fun Maximize(color: Color) {
        Canvas(Modifier.size(10.dp)) {
            drawRect(
                color,
                topLeft = Offset(0.6f, 0.6f),
                size = Size(size.width - 1.2f, size.height - 1.2f),
                style = Stroke(1.15f),
            )
        }
    }

    @Composable
    fun Restore(color: Color) {
        Canvas(Modifier.size(10.dp)) {
            val s = size.minDimension
            val inset = s * 0.22f
            drawRect(
                color,
                topLeft = Offset(inset, 0.5f),
                size = Size(s - inset - 0.5f, s - inset - 0.5f),
                style = Stroke(1.15f),
            )
            drawRect(
                color,
                topLeft = Offset(0.5f, inset),
                size = Size(s - inset - 0.5f, s - inset - 0.5f),
                style = Stroke(1.15f),
            )
        }
    }

    @Composable
    fun Close(color: Color) {
        Canvas(Modifier.size(10.dp)) {
            drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 1.15f)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 1.15f)
        }
    }
}
