package com.servermaintance.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.HostState
import com.servermaintance.panel.core.NoteBoxState
import com.servermaintance.panel.core.OpsState
import com.servermaintance.panel.core.SecState
import com.servermaintance.panel.core.WatchState
import com.servermaintance.panel.core.SessionState
import com.servermaintance.panel.screens.LoginScreen
import com.servermaintance.panel.screens.Root
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.ColorTheme
import com.servermaintance.panel.ui.ThemeState
import com.servermaintance.panel.i18n.LocaleState
import com.servermaintance.panel.i18n.ProvideLocale
import com.servermaintance.panel.i18n.StringRepository
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.ui.WithWindowCaption

/**
 * 根 Composable。
 *
 * 三分逻辑（**不能拿 `storeReady` 当登录态**，它只表示配置已加载）：
 *  - [SessionState.Boot]  → 启动闪屏（配置加载 / 校验令牌中）
 *  - [SessionState.Login] → 登录页（含会话过期横幅）
 *  - [SessionState.Ready] → 应用外壳 `Root()`
 */
@Composable
fun App() {
    val session by AppState.session.collectAsState()
    val colorTheme = ThemeState.mode

    LaunchedEffect(Unit) {
        AccentState.load()
        ThemeState.load()
        LocaleState.load()
        StringRepository.preload()
        AppState.bootstrap()
        // NoteBox（笔记/待办）会话恢复：有保存的 cookie 才会真正发请求
        NoteBoxState.bootstrap()
    }

    val m3Scheme = when (colorTheme) {
        ColorTheme.Dark ->
        darkColorScheme(
            primary = Color(0xFF4370F0),
            onPrimary = Color.White,
            secondary = Color(0xFF7A9BFF),
            background = Palette.bg,
            surface = Palette.panel,
            onBackground = Palette.text,
            onSurface = Palette.text,
            error = Palette.bad,
        )
        ColorTheme.Light ->
        lightColorScheme(
            primary = Color(0xFF4370F0),
            onPrimary = Color.White,
            secondary = Color(0xFF5B7FE6),
            background = Palette.bg,
            surface = Palette.panel,
            onBackground = Palette.text,
            onSurface = Palette.text,
            error = Palette.bad,
        )
    }

    MaterialTheme(colorScheme = m3Scheme) {
        ProvideLocale {
        Surface(color = Palette.bg) {
            when (session) {
                SessionState.Boot -> WithWindowCaption(AppInfo.NAME.uppercase(), "${AppInfo.VERSION_LABEL} · ${L["app.description"]}") { BootSplash() }
                SessionState.Login -> WithWindowCaption(AppInfo.NAME.uppercase(), "${AppInfo.VERSION_LABEL} · ${L["app.description"]}") { LoginScreen() }
                SessionState.Ready -> {
                    // 运维增强（拨测/规则/渠道/主机台账）都走面板 Bearer token
                    LaunchedEffect(Unit) {
                        OpsState.loadAll()
                        HostState.load()
                        SecState.load()
                        WatchState.load()
                    }
                    Root()
                }
            }
        }
        }
    }
}

/** 启动闪屏（配置加载 / 令牌校验阶段）。 */
@Composable
private fun BootSplash() {
    Box(Modifier.fillMaxSize().background(Palette.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = L["cloud_monitor_5768a16d7b"],
                color = Palette.text,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Text(
                text = L["common.loading"],
                color = Palette.muted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
