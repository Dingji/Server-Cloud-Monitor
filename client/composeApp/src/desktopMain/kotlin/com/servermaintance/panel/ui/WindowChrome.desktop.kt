package com.servermaintance.panel.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

@Composable
internal actual fun captionButtonHovered(interaction: MutableInteractionSource): Boolean {
    val hovered by interaction.collectIsHoveredAsState()
    return hovered
}
