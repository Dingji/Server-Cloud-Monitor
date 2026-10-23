package com.servermaintance.panel

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // SAF 文件选择器：OpenDocument（单选）与 OpenMultipleDocuments（多选）。
        // 两者的 input 均为 Array<String>（MIME 类型列表）。
        val singleLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            AndroidPickerBridge.onResult(listOfNotNull(uri))
        }
        AndroidPickerBridge.registerSingle(singleLauncher)

        val multiLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            AndroidPickerBridge.onResult(uris.orEmpty())
        }
        AndroidPickerBridge.registerMulti(multiLauncher)

        setContent {
            // 保留 edge-to-edge 沉浸色，同时为状态栏/导航栏留出可点击区域（避免遮住系统菜单）
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                App()
            }
        }
    }
}
