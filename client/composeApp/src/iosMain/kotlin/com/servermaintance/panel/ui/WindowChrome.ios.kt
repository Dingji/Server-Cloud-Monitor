package com.servermaintance.panel.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable

/** iOS 无桌面标题栏，与 Android 一致：悬停态恒为 false（触屏无 hover）。 */
@Composable
internal actual fun captionButtonHovered(interaction: MutableInteractionSource): Boolean = false
