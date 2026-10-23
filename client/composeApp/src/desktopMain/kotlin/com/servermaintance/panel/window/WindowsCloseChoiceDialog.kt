package com.servermaintance.panel.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Palette

@Composable
internal fun WindowsCloseChoiceDialog(
    visible: Boolean,
    traySupported: Boolean,
    onDismiss: () -> Unit,
    onTray: () -> Unit,
    onQuit: () -> Unit,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 420.dp)
                .clip(shape)
                .border(1.dp, Palette.border, shape)
                .background(Palette.panel),
        ) {
            Box(
                Modifier.fillMaxWidth().height(2.dp).background(AccentState.current.primary),
            )
            Column(Modifier.padding(20.dp)) {
                Text(
                    "关闭 Cloud Monitor",
                    color = Palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (traySupported) {
                        "退出程序，或驻留到系统托盘继续在后台接收服务器告警。"
                    } else {
                        "退出程序，或最小化到任务栏继续在后台接收服务器告警。"
                    },
                    color = Palette.muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DialogBtn("取消", ghost = true, onClick = onDismiss)
                    Spacer(Modifier.padding(4.dp))
                    DialogBtn(
                        if (traySupported) "驻留到托盘" else "最小化到任务栏",
                        ghost = false,
                        onClick = onTray,
                    )
                    Spacer(Modifier.padding(4.dp))
                    DialogBtn("退出程序", ghost = false, danger = true, onClick = onQuit)
                }
            }
        }
    }
}

@Composable
private fun DialogBtn(
    label: String,
    ghost: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        danger -> Palette.bad.copy(alpha = 0.9f)
        ghost -> Palette.bg
        else -> AccentState.current.primary
    }
    val fg = if (ghost) Palette.text else Palette.text
    val border = if (ghost) Palette.border else AccentState.current.primary
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
