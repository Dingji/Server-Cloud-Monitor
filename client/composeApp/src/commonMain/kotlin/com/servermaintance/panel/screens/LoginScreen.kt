package com.servermaintance.panel.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ConfigStore
import com.servermaintance.panel.core.ServerConfig
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.platformHandheld
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.HandheldLayout
import com.servermaintance.panel.ui.handheldImePadding
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelIcon
import kotlinx.coroutines.launch

/**
 * 登录浮层 —— 复刻 `index.html` 的 `#login-overlay .login-card`：
 * logo + CLOUD MONITOR + 副标题 + 访问密码 + 错误提示 + 登录按钮 + 审计提示。
 *
 * 额外增加**「服务器连接配置」区**（原生 App 必须能改服务器地址）：
 * 地址输入（默认 `ConfigStore.DEFAULT_URL`）、「测试连接」（`AppState.testConnection()`）、「记住密码」、折叠式高级区。
 */
@Composable
fun LoginScreen() {
    val cfg = AppState.config()
    val banner by AppState.banner.collectAsState()

    var password by remember { mutableStateOf(AppState.savedPassword()) }
    var url by remember { mutableStateOf(cfg.normalizedUrl().ifEmpty { ConfigStore.DEFAULT_URL }) }
    var rememberPw by remember { mutableStateOf(cfg.rememberPassword) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    /* 服务器连接配置区 */
    var connOpen by remember { mutableStateOf(true) }
    var advancedOpen by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var testMsg by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun normalize(raw: String): String =
        raw.trim().ifEmpty { ConfigStore.DEFAULT_URL }

    val doLogin: () -> Unit = {
        if (password.isEmpty()) {
            error = "请输入密码"
        } else if (!busy) {
            error = ""
            scope.launch {
                busy = true
                try {
                    val target = normalize(url)
                    val cur = AppState.config()
                    val changed = ServerConfig(target).normalizedUrl() != cur.normalizedUrl() ||
                        rememberPw != cur.rememberPassword
                    // 地址或记住密码变了：先持久化并重建客户端，再登录（复刻改地址后登录的场景）
                    if (changed) AppState.reconfigure(target, password = password, rememberPassword = rememberPw)
                    AppState.login(password)
                } catch (e: ApiException) {
                    error = e.message ?: "登录失败"
                } catch (e: Exception) {
                    error = e.message ?: e::class.simpleName ?: "登录失败"
                } finally {
                    busy = false
                }
            }
        }
    }

    val doTest: () -> Unit = {
        if (!testing) {
            scope.launch {
                testing = true
                testOk = null
                testMsg = ""
                try {
                    val target = normalize(url)
                    if (ServerConfig(target).normalizedUrl() != AppState.config().normalizedUrl()) {
                        AppState.reconfigure(target)
                    }
                    val msg = AppState.testConnection()
                    testOk = true
                    testMsg = msg
                    AppState.toast(msg, ToastKind.Ok)
                } catch (e: Exception) {
                    testOk = false
                    testMsg = "连接失败：" + (e.message ?: "未知错误")
                    AppState.toast(testMsg, ToastKind.Err, 6000)
                } finally {
                    testing = false
                }
            }
        }
    }

    /* ---- 网格背景（.login-overlay 的 42px 细网格） ---- */
    val grid = Color.White.copy(alpha = 0.022f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .drawBehind {
                val step = 42.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = if (platformHandheld) 420.dp else 360.dp)
                .fillMaxWidth()
                .heightIn(max = if (platformHandheld) 900.dp else 760.dp)
                .verticalScroll(rememberScrollState())
                .handheldImePadding()
                .padding(if (platformHandheld) 10.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.panel, RoundedCornerShape(3.dp))
                    .border(1.dp, Palette.border2, RoundedCornerShape(3.dp)),
            ) {
                /* 顶部 2px 强调条（border-top: 2px solid var(--accent)） */
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(AccentState.current.primary),
                )

                Column(
                    Modifier
                        .padding(horizontal = HandheldLayout.loginCardHorizontalPadding())
                        .padding(
                            top = if (platformHandheld) 24.dp else 34.dp,
                            bottom = if (platformHandheld) 20.dp else 26.dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    /* logo */
                    PanelIcon(
                        name = "server",
                        size = 34.dp,
                        color = AccentState.current.secondary,
                        stroke = 1.6f,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "CLOUD MONITOR",
                        color = Palette.text,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "服务器实时监控与配置控制台",
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.size(16.dp))

                    /* 会话过期 / 网络提示 */
                    val b = banner
                    if (!b.isNullOrEmpty()) {
                        Text(
                            text = b,
                            color = Palette.warn,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Palette.panel2, RoundedCornerShape(3.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                    }

                    /* 访问密码 */
                    LoginField(
                        value = password,
                        onValueChange = { password = it; if (error.isNotEmpty()) error = "" },
                        placeholder = "访问密码",
                        password = true,
                        enabled = !busy,
                        onEnter = doLogin,
                    )

                    /* 错误提示（min-height 20px，同 .login-error） */
                    Text(
                        text = error,
                        color = Palette.bad,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 20.dp)
                            .padding(top = 8.dp, bottom = 4.dp),
                    )

                    /* 登录按钮 */
                    BlockButton(
                        text = if (busy) "登录中…" else "登 录",
                        enabled = !busy,
                        onClick = doLogin,
                    )

                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = "所有操作均被审计记录",
                        color = Palette.muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                /* ---------------- 服务器连接配置 ---------------- */
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.bg2)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { connOpen = !connOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PanelIcon("link", 13.dp, Palette.muted)
                        Spacer(Modifier.size(7.dp))
                        Text(
                            text = "服务器连接配置",
                            color = Palette.muted,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                            modifier = Modifier.weight(1f),
                        )
                        PanelIcon(
                            name = if (connOpen) "chevronUp" else "chevronDown",
                            size = 14.dp,
                            color = Palette.muted,
                        )
                    }

                    if (connOpen) {
                        Spacer(Modifier.size(10.dp))
                        LoginField(
                            value = url,
                            onValueChange = { url = it; testOk = null; testMsg = "" },
                            placeholder = ConfigStore.DEFAULT_URL,
                            onEnter = doTest,
                        )
                        Spacer(Modifier.size(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallButton(
                                text = if (testing) "测试中…" else "测试连接",
                                enabled = !testing,
                                onClick = doTest,
                            )
                            Spacer(Modifier.size(10.dp))
                            val ok = testOk
                            if (ok != null) {
                                Text(
                                    text = testMsg,
                                    color = if (ok) Palette.ok else Palette.bad,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Text(
                                    text = "默认 ${ConfigStore.DEFAULT_URL}",
                                    color = Palette.muted,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Spacer(Modifier.size(6.dp))

                        /* 记住密码 */
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    rememberPw = !rememberPw
                                    if (!rememberPw) {
                                        val target = url.trim().ifEmpty { ConfigStore.DEFAULT_URL }
                                        scope.launch {
                                            AppState.reconfigure(target, rememberPassword = false)
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(15.dp)
                                    .background(
                                        if (rememberPw) AccentState.current.primary else Color.Transparent,
                                        RoundedCornerShape(2.dp),
                                    )
                                    .border(
                                        1.4.dp,
                                        if (rememberPw) AccentState.current.primary else Palette.border2,
                                        RoundedCornerShape(2.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (rememberPw) PanelIcon("check", 11.dp, Palette.white, 2.4f)
                            }
                            Spacer(Modifier.size(8.dp))
                            Text("记住密码", color = Palette.muted, fontSize = 12.sp)
                        }

                        /* 折叠式高级区 */
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { advancedOpen = !advancedOpen }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PanelIcon("sliders", 12.dp, Palette.muted)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = "高级",
                                color = Palette.muted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            PanelIcon(
                                name = if (advancedOpen) "chevronUp" else "chevronDown",
                                size = 13.dp,
                                color = Palette.muted,
                            )
                        }

                        if (advancedOpen) {
                            val cur = AppState.config()
                            InfoRow("规范化地址", cur.normalizedUrl().ifEmpty { "—" })
                            InfoRow("API 地址", cur.apiBase().ifEmpty { "—" })
                            InfoRow(
                                "访问令牌",
                                if (cur.token.isNotEmpty()) cur.token.take(10) + "…" else "未登录",
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmallButton(text = "恢复默认地址") { url = ConfigStore.DEFAULT_URL }
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    text = "改地址后点「测试连接」即持久化生效",
                                    color = Palette.muted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */

/** 登录卡里的等宽输入框（复刻 `.login-card input`：#0b1017 底、聚焦强调色边框）。 */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
    onEnter: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) AccentState.current.primary else Palette.border2

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = Palette.text,
            fontSize = 13.5.sp,
            fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = SolidColor(AccentState.current.primary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onEnter?.invoke() }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.input, RoundedCornerShape(3.dp))
                    .border(1.dp, border, RoundedCornerShape(3.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Palette.muted,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                inner()
            }
        },
    )
}

/** 全宽主按钮（复刻 `.btn.btn-primary.btn-block`）。 */
@Composable
private fun BlockButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(
                if (enabled) AccentState.current.primary else Palette.panel3,
                RoundedCornerShape(3.dp),
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Palette.white else Palette.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}

/** 小号描边按钮（测试连接 / 恢复默认地址）。 */
@Composable
private fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color.Transparent, RoundedCornerShape(3.dp))
            .border(1.dp, Palette.border2, RoundedCornerShape(3.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = if (enabled) Palette.text else Palette.muted,
            fontSize = 11.5.sp,
        )
    }
}

/** 高级区的信息行（键 = 值，等宽）。 */
@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = k,
            color = Palette.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = v,
            color = Palette.text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}
