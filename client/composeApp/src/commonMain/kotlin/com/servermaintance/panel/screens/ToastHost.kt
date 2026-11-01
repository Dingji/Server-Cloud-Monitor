package com.servermaintance.panel.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.ToastMessage
import com.servermaintance.panel.ui.Palette

/**
 * Toast 宿主（复刻 `#toast-root .toast`：右下角堆叠、最多 5 条、自动消失、
 * 左侧 3px 类型色条 + 底部 2px 计时进度条）。
 * 放在根层最后渲染，确保浮在页面之上（ModalHost 之后由 Root 排序）。
 */
@Composable
fun ToastHost() {
    val toasts by AppState.toasts.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(end = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Bottom,
    ) {
        toasts.forEach { t ->
            key(t.id) { ToastCard(t) }
        }
    }
}

@Composable
private fun ToastCard(t: ToastMessage) {
    val edge = when (t.kind) {
        ToastKind.Info -> Palette.info
        ToastKind.Ok -> Palette.ok
        ToastKind.Warn -> Palette.warn
        ToastKind.Err -> Palette.bad
    }
    val progress = remember(t.id) { Animatable(1f) }
    LaunchedEffect(t.id) {
        progress.animateTo(0f, tween(t.ms.toInt().coerceAtLeast(1)))
    }

    Box(
        Modifier.padding(bottom = 7.dp)
            .widthIn(max = 430.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.panel2)
            .border(1.dp, Palette.border2, RoundedCornerShape(3.dp)),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(edge))
            Text(
                text = t.text,
                color = Palette.text,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(start = 10.dp, end = 13.dp, top = 9.dp, bottom = 11.dp),
            )
        }
        /* 底部计时进度条（.toast-progress） */
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
                .background(Palette.border),
        )
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.value).height(2.dp)
                .background(Palette.muted.copy(alpha = 0.45f)),
        )
    }
}
