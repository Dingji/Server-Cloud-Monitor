package com.servermaintance.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS 入口，等价于 Android 的 `MainActivity.setContent { … }`。
 *
 * Xcode 壳（`iosApp/`）里 Swift 侧调用 `MainViewControllerKt.MainViewController()`。
 */
fun MainViewController(): UIViewController {
    val controller = ComposeUIViewController { IosRoot() }
    // 文件选择器要有可呈现的宿主 VC，这里登记根控制器（见 Platform.ios.kt 的 IosPickerHost）。
    IosPickerHost.root = controller
    return controller
}

@Composable
private fun IosRoot() {
    // 与 MainActivity 同一处理：内容铺满，但按安全区（刘海 / 底部横条）留出可点击边距。
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        App()
    }
}
