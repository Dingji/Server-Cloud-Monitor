package com.servermaintance.panel

import androidx.compose.runtime.Composable

/** 客户端本地偏好（非面板 /config）；各平台 actual 自行决定是否展示内容。 */
@Composable
expect fun ClientSettingsSection()
