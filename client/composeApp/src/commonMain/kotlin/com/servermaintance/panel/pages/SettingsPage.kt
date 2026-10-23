package com.servermaintance.panel.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.AppInfo
import com.servermaintance.panel.ClientSettingsSection
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.Accents
import com.servermaintance.panel.ui.ColorTheme
import com.servermaintance.panel.ui.ThemeState
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PanelIcon
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SectionTitle
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.Toggle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 面板设置 —— 复刻 upstream/public/js/pages/settings.js (324 行)
 *
 * 卡片清单（自检，供测试对账）：
 *  S0  服务器连接地址（用户核心需求：可配置；测试连接 + 保存重连）
 *  S1  告警阈值：启用开关 / CPU / 内存 / 磁盘 / 冷却分钟 → POST /alerts/config
 *  S2  界面主题色：6 个强调色圆点 → AccentState.apply（即时生效并持久化）
 *  S3  面板参数：数据刷新间隔(500~10000ms)+应用（并推 WS refresh-interval）/
 *      出口带宽上限 Mbps+应用 / 监听地址 / 访问路径 / 运行模式 / 重启面板(danger)
 *  S4  修改面板密码：当前+新密码(≥8) → POST /password
 *  S5  SSH 连接（终端 PTY）：host/port/user/password → POST /config {ssh}
 *  S6  数据库凭据：PostgreSQL / MySQL / IBM Db2 / Redis / MongoDB / SQLite 搜索目录
 *      → 一次 POST /config {db}（空密码不覆盖服务端已存值）
 *  S7  配置文件编辑器：目录浏览(GET /files) / 打开文件(GET /file) /
 *      保存(POST /file) + 配置校验徽章（nginx|sshd|fstab 自动校验）
 *  S8  关于：版本 / Node / 刷新节奏 / 审计说明
 * ===================================================================== */

private val REFRESH_CHOICES = listOf(500, 1000, 2000, 3000, 5000, 10000)

private fun refreshLabel(ms: Int): String =
    if (ms >= 1000) "${(ms / 1000.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }} 秒" else "$ms 毫秒"

private data class FileEntry(
    val name: String,
    val mode: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val target: String,
)

@Composable
fun SettingsPage() {
    var cfg by remember { mutableStateOf<JsonObject?>(null) }
    var alertCfg by remember { mutableStateOf<JsonObject?>(null) }
    var errText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        errText = null
        try {
            cfg = AppState.api.get("/config", cacheMs = 2000) as? JsonObject
            alertCfg = (AppState.api.get("/alerts") as? JsonObject)?.obj("config")
        } catch (e: ApiException) {
            errText = e.message + (e.output?.let { "\n$it" } ?: "")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    PageScaffold(title = "面板设置", desc = "连接地址、告警阈值、面板参数、凭据与配置文件编辑") {
        when {
            loading && cfg == null -> PanelCard(title = "面板设置") { LoadingState("正在读取配置…") }
            errText != null -> PanelCard(title = "读取失败") {
                ErrorState(errText!!, onRetry = { scope.launch { load() } })
            }
            cfg == null -> PanelCard(title = "面板设置") { ErrorState("暂无数据", onRetry = { scope.launch { load() } }) }

            else -> {
                ServerConnectionCard()
                Spacer(Modifier.height(14.dp))
                AlertThresholdsCard(alertCfg, onSaved = { scope.launch { load() } })
                Spacer(Modifier.height(14.dp))
                ThemeCard()
                Spacer(Modifier.height(14.dp))
                ClientSettingsSection()
                Spacer(Modifier.height(14.dp))
                PanelParamsCard(cfg!!)
                Spacer(Modifier.height(14.dp))
                PasswordCard()
                Spacer(Modifier.height(14.dp))
                SshCard(cfg!!)
                Spacer(Modifier.height(14.dp))
                DbCredsCard(cfg!!)
                Spacer(Modifier.height(14.dp))
                FileEditorCard()
                Spacer(Modifier.height(14.dp))
                AboutCard(cfg!!)
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * S0 服务器连接地址（用户核心需求）
 * ------------------------------------------------------------------ */
@Composable
private fun ServerConnectionCard() {
    var url by remember { mutableStateOf(AppState.config().normalizedUrl()) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    PanelCard(
        title = "服务器连接地址",
        right = { PanelIcon("link", size = 14.dp) },
    ) {
        Text(
            text = "App 连接的 Cloud Monitor 面板地址。默认 http://8.153.153.176/panel，可改为任意已部署面板的地址。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(
                value = url,
                onValueChange = { url = it; result = null },
                hint = "http://主机[:端口][/路径]",
                mono = true,
                modifier = Modifier.weight(1f),
            )
            GhostBtn(
                text = if (testing) "测试中…" else "测试连接",
                enabled = !testing && !saving,
                onClick = {
                    scope.launch {
                        testing = true
                        result = null
                        try {
                            // 先暂存地址再探测，避免改动未保存时测错目标
                            val prev = AppState.config()
                            if (prev.normalizedUrl() != url.trim().trimEnd('/')) {
                                AppState.reconfigure(url)
                                result = AppState.testConnection()
                                AppState.reconfigure(prev.url)
                            } else {
                                result = AppState.testConnection()
                            }
                        } catch (e: Exception) {
                            result = e.message
                        } finally {
                            testing = false
                        }
                    }
                },
            )
            PrimaryBtn(
                text = if (saving) "保存中…" else "保存并重连",
                enabled = !testing && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        try {
                            AppState.reconfigure(url)
                            AppState.toast("服务器地址已保存", ToastKind.Ok)
                            result = "已切换到 ${AppState.config().normalizedUrl()}"
                        } catch (e: Exception) {
                            result = e.message
                            AppState.toast(e.message ?: "切换失败", ToastKind.Err)
                        } finally {
                            saving = false
                        }
                    }
                },
            )
        }
        result?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                color = if (it.contains("无法") || it.contains("失败")) Palette.bad else Palette.ok,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "当前令牌会在切换到不同服务器时自动失效，需重新登录。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/* ------------------------------------------------------------------ *
 * S1 告警阈值
 * ------------------------------------------------------------------ */
@Composable
private fun AlertThresholdsCard(al: JsonObject?, onSaved: () -> Unit) {
    var enabled by remember(al) { mutableStateOf(if (al == null) true else al.bool("enabled")) }
    var cpu by remember(al) { mutableStateOf(((al ?: jsonObjectOf())["cpu"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "90")) }
    var mem by remember(al) { mutableStateOf(((al ?: jsonObjectOf())["mem"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "90")) }
    var disk by remember(al) { mutableStateOf(((al ?: jsonObjectOf())["disk"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "95")) }
    var cd by remember(al) { mutableStateOf(((al ?: jsonObjectOf())["cooldownMin"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "10")) }
    val scope = rememberCoroutineScope()

    PanelCard(title = "告警阈值（仪表盘右上角铃铛）") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Toggle(checked = enabled, onCheckedChange = { enabled = it }, label = "启用告警")
            Field(value = cpu, onValueChange = { cpu = it }, label = "CPU ≥ %", modifier = Modifier.weight(1f))
            Field(value = mem, onValueChange = { mem = it }, label = "内存 ≥ %", modifier = Modifier.weight(1f))
            Field(value = disk, onValueChange = { disk = it }, label = "磁盘 ≥ %", modifier = Modifier.weight(1f))
            Field(value = cd, onValueChange = { cd = it }, label = "冷却(分钟)", modifier = Modifier.weight(1.2f))
            PrimaryBtn(
                text = "保存阈值",
                onClick = {
                    scope.launch {
                        Actions.run(
                            path = "/alerts/config",
                            body = jsonObjectOf(
                                "enabled" to enabled,
                                "cpu" to (cpu.toIntOrNull() ?: 90),
                                "mem" to (mem.toIntOrNull() ?: 90),
                                "disk" to (disk.toIntOrNull() ?: 95),
                                "cooldownMin" to (cd.toIntOrNull() ?: 10),
                            ),
                            successToast = "告警阈值已保存",
                        )
                        onSaved()
                    }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "超过阈值时页面右上角铃铛会弹出告警；同一指标在冷却时间内不重复提醒。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/* ------------------------------------------------------------------ *
 * S2 外观：浅色 / 深色 + 强调色
 * ------------------------------------------------------------------ */
@Composable
private fun ThemeCard() {
    val current = AccentState.current
    var accentLocal by remember { mutableStateOf(current.id) }
    var themeLocal by remember { mutableStateOf(ThemeState.mode) }

    PanelCard(title = "外观") {
        Text("显示模式", color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeModeChip(
                label = "深色",
                icon = "moon",
                selected = themeLocal == ColorTheme.Dark,
                onClick = {
                    themeLocal = ColorTheme.Dark
                    ThemeState.apply(ColorTheme.Dark)
                    AppState.toast("已切换为深色模式", ToastKind.Ok)
                },
            )
            ThemeModeChip(
                label = "浅色",
                icon = "sun",
                selected = themeLocal == ColorTheme.Light,
                onClick = {
                    themeLocal = ColorTheme.Light
                    ThemeState.apply(ColorTheme.Light)
                    AppState.toast("已切换为浅色模式", ToastKind.Ok)
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("强调色", color = Palette.muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Accents.all.forEach { a ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(a.primary, CircleShape)
                        .border(
                            width = 2.dp,
                            color = if (a.id == accentLocal) Palette.text else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable {
                            accentLocal = a.id
                            AccentState.apply(a.id)
                            AppState.toast("主题色已更新", ToastKind.Ok)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = a.id.take(1).uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "显示模式与强调色会立即生效并保存在本机。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ThemeModeChip(
    label: String,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = AccentState.current.primary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) accent.copy(alpha = 0.12f) else Palette.panel2)
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.55f) else Palette.border,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PanelIcon(icon, 16.dp, if (selected) accent else Palette.muted)
        Text(
            label,
            color = if (selected) Palette.text else Palette.muted,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/* ------------------------------------------------------------------ *
 * S3 面板参数
 * ------------------------------------------------------------------ */
@Composable
private fun PanelParamsCard(c: JsonObject) {
    var refreshMs by remember(c) { mutableStateOf(c.int("refreshMs").toString()) }
    var netMbps by remember(c) {
        val bps = c.num("netLimitBps")
        mutableStateOf(if (bps > 0) ((bps / 1e6).let { d -> if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString() }) else "0")
    }
    val scope = rememberCoroutineScope()

    PanelCard(title = "面板参数") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectField(
                value = refreshLabel(refreshMs.toIntOrNull() ?: 2000),
                options = REFRESH_CHOICES.map { refreshLabel(it) },
                onValueChange = { label ->
                    val v = REFRESH_CHOICES.firstOrNull { refreshLabel(it) == label } ?: 2000
                    refreshMs = v.toString()
                    scope.launch {
                        val ok = Actions.run(
                            path = "/config",
                            body = jsonObjectOf("refreshMs" to v),
                            successToast = "刷新间隔已更新为 ${refreshLabel(v)}",
                        )
                        if (ok != null) AppState.hub.setRefreshInterval(v)
                    }
                },
                label = "数据刷新间隔",
                modifier = Modifier.weight(1.4f),
            )
            Field(
                value = netMbps,
                onValueChange = { netMbps = it },
                label = "出口带宽上限（Mbps，0 = 不限）",
                modifier = Modifier.weight(1.4f),
            )
            PrimaryBtn(
                text = "应用带宽",
                onClick = {
                    scope.launch {
                        val mbps = netMbps.toDoubleOrNull() ?: 0.0
                        val bps = (mbps * 1e6).toLong()
                        Actions.run(
                            path = "/config",
                            body = jsonObjectOf("netLimitBps" to bps),
                            successToast = if (mbps > 0) "出口带宽上限已设为 $mbps Mbps" else "已取消带宽上限",
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "用于仪表盘网络吞吐进度条，按真实上限显示占用比例；未设置时沿用 1 Mbps 基准。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        KeyValueRow("监听地址", "${c.str("bind")}:${c.int("port")}")
        KeyValueRow("访问路径", "${c.str("basePath")}/")
        KeyValueRow("运行模式", if (c.bool("underSystemd")) "systemd 服务" else "手动进程")

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DangerBtn(
                text = "重启面板服务",
                onClick = {
                    scope.launch {
                        val ok = Modal.confirm(
                            title = "重启面板",
                            text = "面板服务将重启（约 2-5 秒不可用），继续？",
                            danger = true,
                        )
                        if (!ok) return@launch
                        Actions.run(path = "/restart", body = jsonObjectOf(), successToast = "重启中…")
                    }
                },
            )
            Text(
                text = "修改端口/路径需编辑 config.json 后重启",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun KeyValueRow(k: String, v: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = k,
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = v,
            color = Palette.text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/* ------------------------------------------------------------------ *
 * S4 修改面板密码
 * ------------------------------------------------------------------ */
@Composable
private fun PasswordCard() {
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    PanelCard(title = "修改面板密码") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(value = oldPw, onValueChange = { oldPw = it }, password = true, label = "当前密码", modifier = Modifier.weight(1f))
            Field(value = newPw, onValueChange = { newPw = it }, password = true, label = "新密码（至少 8 位）", modifier = Modifier.weight(1f))
            PrimaryBtn(
                text = "更新密码",
                onClick = {
                    scope.launch {
                        if (oldPw.isBlank() || newPw.length < 8) {
                            AppState.toast("新密码至少 8 位", ToastKind.Warn)
                            return@launch
                        }
                        val r = Actions.run(
                            path = "/password",
                            body = jsonObjectOf("oldPassword" to oldPw, "newPassword" to newPw),
                            successToast = null,
                        )
                        if (r != null) {
                            AppState.toast((r as? JsonObject)?.str("output")?.ifBlank { "密码已更新" } ?: "密码已更新", ToastKind.Ok)
                            oldPw = ""; newPw = ""
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "密码更新后其它设备的登录将会失效。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/* ------------------------------------------------------------------ *
 * S5 SSH 连接
 * ------------------------------------------------------------------ */
@Composable
private fun SshCard(c: JsonObject) {
    val ssh = c.obj("ssh") ?: JsonObject(emptyMap())
    var host by remember(c) { mutableStateOf(ssh.str("host")) }
    var port by remember(c) { mutableStateOf(ssh.str("port").ifBlank { "22" }) }
    var user by remember(c) { mutableStateOf(ssh.str("user")) }
    var pw by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    PanelCard(title = "SSH 连接（用于 Web 终端的 PTY）") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(value = host, onValueChange = { host = it }, label = "主机", mono = true, modifier = Modifier.weight(1.2f))
            Field(value = port, onValueChange = { port = it }, label = "端口", mono = true, modifier = Modifier.weight(0.6f))
            Field(value = user, onValueChange = { user = it }, label = "用户", mono = true, modifier = Modifier.weight(0.8f))
            Field(
                value = pw,
                onValueChange = { pw = it },
                password = true,
                label = "密码（留空保持不变）",
                modifier = Modifier.weight(1.4f),
            )
            PrimaryBtn(
                text = "保存",
                onClick = {
                    scope.launch {
                        Actions.run(
                            path = "/config",
                            body = jsonObjectOf(
                                "ssh" to jsonObjectOf(
                                    "host" to host.trim(),
                                    "port" to (port.toIntOrNull() ?: 22),
                                    "user" to user.trim(),
                                    "password" to pw,
                                )
                            ),
                            successToast = "SSH 设置已保存",
                        )
                        pw = ""
                    }
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (ssh.bool("hasKey"))
                "终端优先使用密钥认证（keys/pty_key），密码为备用。修改后新终端会话生效。"
            else "当前未配置私钥，将使用密码认证。修改后新终端会话生效。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/* ------------------------------------------------------------------ *
 * S6 数据库凭据
 * ------------------------------------------------------------------ */
@Composable
private fun DbCredsCard(c: JsonObject) {
    val db = c.obj("db") ?: JsonObject(emptyMap())
    fun sec(name: String): JsonObject = db.obj(name) ?: JsonObject(emptyMap())

    val pg = sec("postgres"); val my = sec("mysql"); val db2 = sec("db2")
    val rd = sec("redis"); val mo = sec("mongo"); val sq = sec("sqlite")

    var pgUser by remember(c) { mutableStateOf(pg.str("user")) }
    var pgHost by remember(c) { mutableStateOf(pg.str("host")) }
    var pgPort by remember(c) { mutableStateOf(pg.str("port")) }
    var pgPw by remember { mutableStateOf("") }

    var myUser by remember(c) { mutableStateOf(my.str("user")) }
    var myHost by remember(c) { mutableStateOf(my.str("host")) }
    var myPort by remember(c) { mutableStateOf(my.str("port")) }
    var myPw by remember { mutableStateOf("") }

    var db2Inst by remember(c) { mutableStateOf(db2.str("instance")) }
    var dbName by remember(c) { mutableStateOf(db2.str("database")) }
    var db2Port by remember(c) { mutableStateOf(db2.str("port")) }

    var rdHost by remember(c) { mutableStateOf(rd.str("host")) }
    var rdPort by remember(c) { mutableStateOf(rd.str("port")) }
    var rdDb by remember(c) { mutableStateOf(rd.str("db")) }
    var rdPw by remember { mutableStateOf("") }

    var moHost by remember(c) { mutableStateOf(mo.str("host")) }
    var moPort by remember(c) { mutableStateOf(mo.str("port")) }
    var moUser by remember(c) { mutableStateOf(mo.str("user")) }
    var moAuth by remember(c) { mutableStateOf(mo.str("authdb")) }
    var moPw by remember { mutableStateOf("") }

    var sqRoots by remember(c) { mutableStateOf(sq.str("roots")) }
    val scope = rememberCoroutineScope()

    PanelCard(title = "数据库连接凭据", right = { PanelIcon("database", size = 14.dp) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PostgreSQL（默认使用 peer 认证 su - postgres）", color = Palette.text, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(pgUser, { pgUser = it }, label = "用户", mono = true, modifier = Modifier.weight(1f))
                Field(pgHost, { pgHost = it }, label = "TCP 主机", hint = "留空=本地 socket", mono = true, modifier = Modifier.weight(1.4f))
                Field(pgPort, { pgPort = it }, label = "端口", hint = "5432", mono = true, modifier = Modifier.weight(0.7f))
                Field(pgPw, { pgPw = it }, password = true,
                    label = if (pg.bool("hasPassword")) "密码（留空不变）" else "密码（无）",
                    modifier = Modifier.weight(1.2f))
            }

            Text("MySQL / MariaDB（默认 root + socket）", color = Palette.text, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(myUser, { myUser = it }, label = "用户", mono = true, modifier = Modifier.weight(1f))
                Field(myHost, { myHost = it }, label = "TCP 主机", hint = "留空=本地 socket", mono = true, modifier = Modifier.weight(1.4f))
                Field(myPort, { myPort = it }, label = "端口", hint = "3306", mono = true, modifier = Modifier.weight(0.7f))
                Field(myPw, { myPw = it }, password = true,
                    label = if (my.bool("hasPassword")) "密码（留空不变）" else "密码（无）",
                    modifier = Modifier.weight(1.2f))
            }

            Text("IBM Db2", color = Palette.text, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(db2Inst, { db2Inst = it }, label = "实例用户", mono = true, modifier = Modifier.weight(1.2f))
                Field(dbName, { dbName = it }, label = "数据库", hint = "SAMPLE", mono = true, modifier = Modifier.weight(1.2f))
                Field(db2Port, { db2Port = it }, label = "端口", hint = "50000", mono = true, modifier = Modifier.weight(0.7f))
            }

            Text("Redis", color = Palette.text, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(rdHost, { rdHost = it }, label = "主机", mono = true, modifier = Modifier.weight(1.4f))
                Field(rdPort, { rdPort = it }, label = "端口", mono = true, modifier = Modifier.weight(0.7f))
                Field(rdDb, { rdDb = it }, label = "默认库", mono = true, modifier = Modifier.weight(0.7f))
                Field(rdPw, { rdPw = it }, password = true,
                    label = if (rd.bool("hasPassword")) "密码（留空不变）" else "密码（无）",
                    modifier = Modifier.weight(1.2f))
            }

            Text("MongoDB", color = Palette.text, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(moHost, { moHost = it }, label = "主机", mono = true, modifier = Modifier.weight(1.4f))
                Field(moPort, { moPort = it }, label = "端口", mono = true, modifier = Modifier.weight(0.7f))
                Field(moUser, { moUser = it }, label = "用户", hint = "留空=不认证", mono = true, modifier = Modifier.weight(1.1f))
                Field(moAuth, { moAuth = it }, label = "认证库", mono = true, modifier = Modifier.weight(1f))
                Field(moPw, { moPw = it }, password = true,
                    label = if (mo.bool("hasPassword")) "密码（留空不变）" else "密码（无）",
                    modifier = Modifier.weight(1.2f))
            }

            Text("SQLite 搜索目录", color = Palette.text, style = MaterialTheme.typography.labelMedium)
            Field(
                value = sqRoots,
                onValueChange = { sqRoots = it },
                label = "允许搜索的根目录（冒号分隔）",
                mono = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "面板只会在此目录范围内列出/打开 .db、.sqlite 文件（防止误开系统文件），新增目录后立即生效。",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )

            PrimaryBtn(
                text = "保存数据库连接",
                onClick = {
                    scope.launch {
                        // 空密码不传，避免覆盖服务端已保存的值（同 settings.js 的 undefined 处理）
                        fun cred(host: String, port: String, user: String, pw: String): JsonObject {
                            val m = mutableMapOf<String, Any?>()
                            if (host.isNotBlank()) m["host"] = host.trim()
                            if (port.isNotBlank()) m["port"] = port.trim()
                            if (user.isNotBlank()) m["user"] = user.trim()
                            if (pw.isNotBlank()) m["password"] = pw
                            return jsonObjectOf(*m.map { it.key to it.value }.toTypedArray())
                        }
                        val body = jsonObjectOf(
                            "db" to jsonObjectOf(
                                "postgres" to cred(pgHost, pgPort, pgUser, pgPw),
                                "mysql" to cred(myHost, myPort, myUser, myPw),
                                "db2" to jsonObjectOf(
                                    "instance" to db2Inst.trim(),
                                    "database" to dbName.trim(),
                                    "port" to db2Port.trim(),
                                ),
                                "redis" to cred(rdHost, rdPort, "", rdPw).let {
                                    jsonObjectOf(
                                        "host" to rdHost.trim(), "port" to rdPort.trim(),
                                        "db" to rdDb.trim(), "password" to rdPw,
                                    )
                                },
                                "mongo" to jsonObjectOf(
                                    "host" to moHost.trim(), "port" to moPort.trim(),
                                    "user" to moUser.trim(), "authdb" to moAuth.trim(), "password" to moPw,
                                ),
                                "sqlite" to jsonObjectOf("roots" to sqRoots.trim()),
                            )
                        )
                        val ok = Actions.run(path = "/config", body = body, successToast = "数据库连接已保存")
                        if (ok != null) {
                            pgPw = ""; myPw = ""; rdPw = ""; moPw = ""
                        }
                    }
                },
            )
        }
    }
}

/* ------------------------------------------------------------------ *
 * S7 配置文件编辑器
 * ------------------------------------------------------------------ */
@Composable
private fun FileEditorCard() {
    var dir by remember { mutableStateOf("/etc/") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var curPath by remember { mutableStateOf<String?>(null) }
    var curInfo by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun browse(target: String) {
        loading = true
        errText = null
        try {
            val d = AppState.api.get("/files?dir=${urlEncode(target)}") as? JsonObject
            dir = d?.str("dir") ?: target
            entries = d?.arr("entries").orEmpty().map { e ->
                FileEntry(
                    name = e.str("name"),
                    mode = e.str("mode"),
                    isDir = e.bool("isDir"),
                    isLink = e.bool("isLink"),
                    target = e.str("target"),
                )
            }.filterNot { it.name == "." || it.name == ".." }
        } catch (e: ApiException) {
            errText = e.message
        } finally {
            loading = false
        }
    }

    suspend fun openFile(path: String) {
        try {
            val f = AppState.api.get("/file?path=${urlEncode(path)}") as? JsonObject
            curPath = path
            curInfo = "${Fmt.bytes(f?.num("size"))} · mode ${f?.str("mode")} · ${f?.str("owner")}"
            content = f?.str("content") ?: ""
            validation = null
        } catch (e: ApiException) {
            AppState.toast(e.message.orEmpty(), ToastKind.Err)
        }
    }

    LaunchedEffect(Unit) { browse("/etc/") }

    PanelCard(
        title = "配置文件编辑器",
        right = {
            Text("（/etc、/var/www、/opt、/srv、/root）", color = Palette.muted,
                style = MaterialTheme.typography.bodySmall)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(value = dir, onValueChange = { dir = it }, mono = true, modifier = Modifier.weight(2f))
            GhostBtn(text = "浏览", onClick = { scope.launch { browse(dir) } })
            Text(
                text = "nginx/sshd/fstab 配置保存后会自动校验",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        errText?.let { Spacer(Modifier.height(6.dp)); ErrorState(it, onRetry = { scope.launch { browse(dir) } }) }

        Spacer(Modifier.height(8.dp))
        when {
            loading -> LoadingState("列目录中…")
            entries.isEmpty() -> EmptyState("（空目录）")
            else -> DataTable<FileEntry>(
                columns = listOf(
                    TableCol(title = "权限", weight = 1f, mono = true) {
                        Text(it.mode, color = Palette.muted, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    },
                    TableCol(title = "名称", weight = 5f, mono = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = it.name,
                                color = if (it.isDir) Palette.info else Palette.text,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (it.isLink && it.target.isNotBlank()) {
                                Text(
                                    text = " → ${it.target}",
                                    color = Palette.muted,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                ),
                rows = entries,
                rowKey = { it.name },
                onRowClick = { e ->
                    scope.launch {
                        val full = if (dir.endsWith("/")) dir + e.name else "$dir/${e.name}"
                        if (e.isDir && !e.isLink) browse(full) else openFile(full)
                    }
                },
                modifier = Modifier.heightIn(max = 240.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = curPath ?: "未选择文件",
                color = if (curPath == null) Palette.muted else Palette.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (curInfo.isNotBlank()) Text(curInfo, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            PrimaryBtn(
                text = "保存文件",
                enabled = curPath != null,
                onClick = {
                    scope.launch {
                        val p = curPath ?: return@launch
                        val risky = Regex("nginx|sshd|fstab").containsMatchIn(p)
                        val ok = Modal.confirm(
                            title = "保存文件",
                            text = "写入 $p？" + if (risky) "（会自动执行配置校验）" else "",
                            okText = "保存",
                            danger = true,
                        )
                        if (!ok) return@launch
                        try {
                            val r = Actions.run(
                                path = "/file",
                                body = jsonObjectOf("path" to p, "content" to content),
                                successToast = "文件已保存",
                            )
                            validation = (r as? JsonObject)?.obj("validation")?.let {
                                if (it.bool("ok")) "配置校验通过" else "校验失败"
                            }
                        } catch (e: ApiException) {
                            validation = null
                            Modal.output("保存失败", (e.output ?: e.message).orEmpty(), isError = true)
                        }
                    }
                },
            )
        }

        if (curPath != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 420.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = Palette.text,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.border2,
                    unfocusedBorderColor = Palette.border,
                    focusedContainerColor = Palette.bg2,
                    unfocusedContainerColor = Palette.bg2,
                    cursorColor = AccentState.current.primary,
                ),
            )
            validation?.let {
                Spacer(Modifier.height(6.dp))
                LevelBadge(text = it, level = if (it.contains("通过")) Level.Ok else Level.Bad)
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * S8 关于
 * ------------------------------------------------------------------ */
@Composable
private fun AboutCard(c: JsonObject) {
    val me = AppState.me.value
    val nodeVer = (me?.obj("info")?.str("nodeVersion")).orEmpty()
    PanelCard(title = "关于") {
        Text(
            text = "${AppInfo.NAME} ${AppInfo.VERSION_LABEL} · ${AppInfo.COPYRIGHT}" +
                (if (nodeVer.isNotBlank()) " · Node $nodeVer" else "") +
                " · 数据每 ${refreshLabel(c.int("refreshMs"))} 从 /proc 实时采集" +
                " · 所有管理操作均写入审计日志 (logs/audit.log)",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
