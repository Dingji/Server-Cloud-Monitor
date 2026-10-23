package com.servermaintance.panel.ui

import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.platformHandheld

/**
 * Android 手持端布局常量与断点（[platformHandheld] 为 false 时全部为桌面默认，不影响 Desktop）。
 */
object HandheldLayout {

    fun pageHorizontalPadding(): Dp = if (platformHandheld) 12.dp else 18.dp

    fun pageTopPadding(): Dp = if (platformHandheld) 10.dp else 16.dp

    fun pageBottomPadding(): Dp = if (platformHandheld) 32.dp else 44.dp

    fun cardInnerHorizontal(): Dp = if (platformHandheld) 10.dp else 15.dp

    fun cardInnerVertical(): Dp = if (platformHandheld) 10.dp else 14.dp

    fun statValueFontSize() = if (platformHandheld) 22 else 26

    /** 外壳：Android 一律抽屉导航；桌面仍按宽度断点。 */
    fun shellCompact(screenWidth: Dp): Boolean =
        platformHandheld || screenWidth < 860.dp

    fun shellRail(screenWidth: Dp): Boolean =
        !platformHandheld && screenWidth >= 860.dp && screenWidth < 1150.dp

    fun drawerWidth(screenWidth: Dp): Dp =
        if (platformHandheld) minOf(288.dp, screenWidth * 0.86f) else 218.dp

    /** 页内双栏/宽表等是否叠成单列（对齐 app.css hide-sm）。 */
    fun contentStacked(screenWidth: Dp): Boolean =
        platformHandheld || screenWidth < 860.dp

    fun modalHorizontalMargin(): Dp = if (platformHandheld) 12.dp else 24.dp

    fun loginCardHorizontalPadding(): Dp = if (platformHandheld) 18.dp else 30.dp
}

/** 页体滚动区：键盘弹出时保留可滚空间（仅 Android）。 */
fun Modifier.handheldImePadding(): Modifier =
    if (platformHandheld) imePadding() else this
