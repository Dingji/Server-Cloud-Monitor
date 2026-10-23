package com.servermaintance.panel.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AutoRefresh
import com.servermaintance.panel.ui.Chip
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.DangerBtn
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.SearchField
import com.servermaintance.panel.ui.SectionTitle
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 安全管理 —— 复刻 upstream/public/js/pages/security.js (295 行)
 * （docs/api/page-security.md 的 9 个 UI 分区逐条对应）
 *
 * 卡片清单（自检）：
 *  S1 姿态四联卡：主机防火墙 UFW / Root 密码登录 / SSH 密码认证 / Fail2ban
 *  S2 「SSH 服务配置 (sshd 有效配置)」6 项表单 + 右上「应用并重载 SSH」
 *  S3 「Fail2ban 防爆破」徽章 + 状态 + 解封 IP + 最近封禁
 *  S4 限流统计四联（/relayops/ratelimit 成功才渲染）：429 拦截 / 限流日志 /
 *     限流区域 / 当前封禁 IP
 *  S5 「限流 / CC 防护」：徽章 + 刷新；限流区域表 + 应用位置表 + Fail2ban jail 表
 *     + 配置/访问日志路径 + 按小时 429 表（≤24）+ 修改阈值说明
 *  S6 「内核/系统安全参数 (sysctl)」两列键值表单 + 每行「应用」
 *  S7 「用户管理 (N)」创建表单 + 用户表 + sudoers pre
 *  S8 「SSH 授权密钥 (authorized_keys)」密钥表 + 多行输入 + 「添加公钥」
 *  S9 「最近登录与失败尝试」成功登录 (last) + 失败尝试 (lastb)
 *
 * 动作语义（照抄 security.js + page-security.md「用户动作 → 请求」表）：
 *  · 多数动作成功后 location.reload() → 本页等价为重新拉 /security + /relayops/ratelimit
 *  · sshd 参数两侧命名不同：读取用**小写**（permitrootlogin），提交用**驼峰**（permitRootLogin）
 *  · sshkey-del 的 params.key 传**整段公钥文本**
 *  · fail2ban-install 的 reloadDelay=1500 → 这里 opsRunAction 的 700ms 后再等 800ms
 *
 * 注：面板的「文件编辑器」(GET /files、GET /file、POST /file) 在 **settings.js**，
 * 不在 security.js —— 那属 task-30（面板设置页），本页不含文件编辑。
 * ===================================================================== */

private val ROOT_LOGIN_OPTS = listOf("yes", "prohibit-password", "without-password", "no")
private val YES_NO = listOf("yes", "no")
private val YES_NO_LEAD_NO = listOf("no", "yes")

@Composable
fun SecurityPage() {
    val scope = rememberCoroutineScope()

    var sec by remember { mutableStateOf<JsonObject?>(null) }
    var rl by remember { mutableStateOf<JsonObject?>(null) }
    var rlFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    var portText by remember { mutableStateOf("22") }
    var mauthText by remember { mutableStateOf("6") }
    var rootLogin by remember { mutableStateOf("yes") }
    var pwAuth by remember { mutableStateOf("yes") }
    var pubkeyAuth by remember { mutableStateOf("yes") }
    var emptyPw by remember { mutableStateOf("no") }
    var sshdFilled by remember { mutableStateOf(false) }

    var unbanIp by remember { mutableStateOf("") }
    var newUser by remember { mutableStateOf("") }
    var newUserPw by remember { mutableStateOf("") }
    var newUserSudo by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var keyLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var sysctlEdits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sysctlFilled by remember { mutableStateOf(false) }

    suspend fun loadSecurity() {
        runCatching {
            sec = AppState.api.get("/security") as? JsonObject
            rlFailed = false
            rl = runCatching {
                AppState.api.get("/relayops/ratelimit", cacheMs = 5000) as? JsonObject
            }.getOrElse {
                rlFailed = true
                null
            }
            error = null
        }.onFailure { e ->
            if (e is CancellationException) throw e
            error = e.message
        }
        loading = false
    }

    suspend fun loadKeys() {
        runCatching {
            val r = AppState.api.post("/user", jsonObjectOf("action" to "sshkey-list"))
            val out = r.str("output")
            keyLines = out.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            keyError = null
        }.onFailure { e ->
            if (e is CancellationException) throw e
            keyError = e.message
        }
    }

    /** 复刻 location.reload()：重拉本页两个数据源并重绘。 */
    suspend fun reloadAll() {
        loadSecurity()
        loadKeys()
    }

    suspend fun secAction(action: String, params: JsonObject? = null, title: String? = null, reload: (suspend () -> Unit)? = null) {
        val body = if (params != null) jsonObjectOf("action" to action, "params" to params)
        else jsonObjectOf("action" to action)
        opsRunAction(path = "/security/action", body = body, title = title, reload = reload)
    }

    LaunchedEffect(Unit) {
        loadSecurity()
        loadKeys()
    }

    val d = sec
    val sshd = d?.obj("sshd") ?: JsonObject(emptyMap())
    val ufwOn = d?.obj("ufw")?.bool("active") == true
    val f2b = d?.obj("fail2ban")
    val ufwRulesCount = d?.obj("ufw")?.arr("rules")?.size ?: 0
    val users = d?.arr("users")?.filterIsInstance<JsonObject>() ?: emptyList()
    val sysctlObj = d?.obj("sysctl")

    /* sshd 表单只在首次填充（与 swappiness 同策略，避免轮询覆盖用户输入） */
    LaunchedEffect(sshd) {
        if (!sshdFilled && sshd.isNotEmpty()) {
            portText = sshd.str("port").ifEmpty { "22" }
            mauthText = sshd.str("maxauthtries").ifEmpty { "6" }
            rootLogin = sshd.str("permitrootlogin").ifEmpty { "yes" }
            pwAuth = sshd.str("passwordauthentication").ifEmpty { "yes" }
            pubkeyAuth = sshd.str("pubkeyauthentication").ifEmpty { "yes" }
            emptyPw = sshd.str("permitemptypasswords").ifEmpty { "no" }
            sshdFilled = true
        }
    }

    /* sysctl 表单只在首次填充 */
    LaunchedEffect(sysctlObj) {
        if (!sysctlFilled && sysctlObj != null && sysctlObj.isNotEmpty()) {
            sysctlEdits = sysctlObj.keys.associateWith { sysctlObj.str(it) }
            sysctlFilled = true
        }
    }

    PageScaffold(title = "安全管理") {

        if (d == null && loading) { Text("加载中…", color = Palette.muted, fontSize = 13.sp) }
        if (d == null && !loading && error != null) {
            ErrorState(message = error ?: "加载失败", onRetry = { scope.launch { loadSecurity() } })
        }

        if (d != null) {
            /* S1 姿态四联 */
            GridLayout(columns = 4, spacing = 12.dp) {
                StatTile(
                    label = "主机防火墙 UFW",
                    value = if (ufwOn) "已启用" else "未启用",
                    sub = "$ufwRulesCount 条规则",
                    valueColor = if (ufwOn) Color(0xFF4ADE80) else Color(0xFFFACC15),
                    icon = "firewall",
                )
                StatTile(
                    label = "Root 密码登录",
                    value = sshd.str("permitrootlogin").ifEmpty { "?" },
                    sub = "PermitRootLogin",
                    valueColor = if (sshd.str("permitrootlogin") == "yes") Color(0xFFF87171) else Color(0xFF4ADE80),
                    icon = "key",
                )
                StatTile(
                    label = "SSH 密码认证",
                    value = sshd.str("passwordauthentication").ifEmpty { "?" },
                    sub = "PasswordAuthentication",
                    valueColor = if (sshd.str("passwordauthentication") == "yes") Color(0xFFFACC15) else Color(0xFF4ADE80),
                    icon = "unlock",
                )
                StatTile(
                    label = "Fail2ban",
                    value = if (f2b?.bool("active") == true) "防护中" else "未防护",
                    sub = "SSH 防爆破",
                    valueColor = if (f2b?.bool("active") == true) Color(0xFF4ADE80) else Color(0xFFF87171),
                    icon = "shield",
                )
            }

            /* S2 + S3 */
            GridLayout(columns = 2, spacing = 12.dp) {
                PanelCard(
                    title = "SSH 服务配置 (sshd 有效配置)",
                    cardKey = "sec-sshd",
                    right = {
                        PrimaryBtn(
                            text = "应用并重载 SSH",
                            onClick = {
                                scope.launch {
                                    val params = jsonObjectOf(
                                        "port" to portText,
                                        "maxAuthTries" to mauthText,
                                        "permitRootLogin" to rootLogin,
                                        "passwordAuthentication" to pwAuth,
                                        "pubkeyAuthentication" to pubkeyAuth,
                                        "permitEmptyPasswords" to emptyPw,
                                    )
                                    val body = params.keys.joinToString("\n") { k ->
                                        "$k = ${(params[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""}"
                                    }
                                    val warn = if (pwAuth == "no") {
                                        "\n\n警告：将关闭密码认证，请确认已配置 SSH 密钥，否则可能无法再登录！"
                                    } else ""
                                    val ok = Modal.confirm(
                                        title = "应用 SSH 配置",
                                        text = "将写入并重载 sshd：\n$body$warn",
                                        okText = "确认",
                                        danger = true,
                                    )
                                    if (!ok) return@launch
                                    secAction(
                                        action = "sshd-apply",
                                        params = params,
                                        title = "SSH 配置应用",
                                        reload = { loadSecurity() },
                                    )
                                }
                            },
                        )
                    },
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit) },
                            label = "SSH 端口",
                            mono = true,
                            modifier = Modifier.width(120.dp),
                        )
                        Field(
                            value = mauthText,
                            onValueChange = { mauthText = it.filter(Char::isDigit) },
                            label = "MaxAuthTries",
                            mono = true,
                            modifier = Modifier.width(130.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectField(
                            value = rootLogin,
                            options = ROOT_LOGIN_OPTS,
                            onValueChange = { rootLogin = it },
                            label = "PermitRootLogin",
                            modifier = Modifier.weight(1f),
                        )
                        SelectField(
                            value = pwAuth,
                            options = YES_NO,
                            onValueChange = { pwAuth = it },
                            label = "PasswordAuthentication",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectField(
                            value = pubkeyAuth,
                            options = YES_NO,
                            onValueChange = { pubkeyAuth = it },
                            label = "PubkeyAuthentication",
                            modifier = Modifier.weight(1f),
                        )
                        SelectField(
                            value = emptyPw,
                            options = YES_NO_LEAD_NO,
                            onValueChange = { emptyPw = it },
                            label = "PermitEmptyPasswords",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "修改会写入 /etc/ssh/sshd_config.d/99-cloud-monitor.conf，先执行 sshd -t 校验，失败自动放弃。建议先配好密钥再关闭密码认证。",
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }

                PanelCard(
                    title = "Fail2ban 防爆破",
                    cardKey = "sec-f2b",
                    right = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            val active = f2b?.bool("active") == true
                            if (active) {
                                LevelBadge("运行中", Level.Ok)
                                DangerBtn(
                                    text = "停止",
                                    onClick = {
                                        scope.launch {
                                            val ok = Modal.confirm(
                                                title = "停止 Fail2ban",
                                                text = "停止后将失去 SSH 防爆破保护。",
                                                okText = "确认",
                                                danger = true,
                                            )
                                            if (ok) secAction("fail2ban-stop", reload = { loadSecurity() })
                                        }
                                    },
                                )
                            } else {
                                LevelBadge("未运行", Level.Bad)
                                PrimaryBtn(
                                    text = "安装并启动",
                                    onClick = {
                                        scope.launch {
                                            val ok = Modal.confirm(
                                                title = "安装 Fail2ban",
                                                text = "将执行 apt-get install fail2ban 并启动服务（需要外网连接，可能需要 1-2 分钟）。",
                                                okText = "安装",
                                            )
                                            if (ok) {
                                                secAction(
                                                    action = "fail2ban-install",
                                                    reload = { delay(800); loadSecurity() },
                                                )
                                            }
                                        }
                                    },
                                )
                                GhostBtn(
                                    text = "启动服务",
                                    onClick = { scope.launch { secAction("fail2ban-start", reload = { loadSecurity() }) } },
                                )
                            }
                        }
                    },
                ) {
                    MonoBox(
                        text = f2b?.str("status")?.ifEmpty { "fail2ban 未安装/未运行" } ?: "fail2ban 未安装/未运行",
                        height = 130.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Field(
                            value = unbanIp,
                            onValueChange = { unbanIp = it },
                            hint = "要解封的 IP",
                            mono = true,
                            modifier = Modifier.weight(1f),
                        )
                        GhostBtn(
                            text = "解封 IP",
                            onClick = {
                                scope.launch {
                                    val ip = unbanIp.trim()
                                    if (ip.isEmpty()) {
                                        AppState.toast("请输入 IP", ToastKind.Warn)
                                        return@launch
                                    }
                                    secAction("fail2ban-unban", params = jsonObjectOf("ip" to ip))
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "最近封禁：${f2b?.str("banned")?.ifEmpty { "无数据" } ?: "无数据"}",
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }
            }

            /* S4 + S5 限流区块：仅当 /relayops/ratelimit 成功才渲染 */
            val r = rl
            if (r != null) {
                val zones = r.arr("zones").filterIsInstance<JsonObject>()
                val applied = r.arr("applied").filterIsInstance<JsonObject>()
                val jails = r.arr("jails").filterIsInstance<JsonObject>()
                val byHour = r.arr("byHour").filterIsInstance<JsonObject>()
                val rateZones = zones.filter { it.str("type") != "global" }
                val banned = jails.sumOf { it.long("currentlyBanned") }

                GridLayout(columns = 4, spacing = 12.dp) {
                    StatTile(
                        label = "429 拦截",
                        value = "${r.long("limited429")}",
                        sub = "最近 ${r.str("windowLines")} 条请求中",
                    )
                    StatTile(
                        label = "限流日志",
                        value = "${r.long("errorLimitCount")}",
                        sub = "error.log 累计",
                    )
                    StatTile(
                        label = "限流区域",
                        value = "${rateZones.size}",
                        sub = "limit_req / limit_conn",
                    )
                    StatTile(
                        label = "当前封禁 IP",
                        value = "$banned",
                        sub = "${jails.size} 个 jail",
                        valueColor = if (banned > 0) Color(0xFFEF4444) else null,
                        icon = "firewall",
                    )
                }

                PanelCard(
                    title = "限流 / CC 防护",
                    cardKey = "sec-rl",
                    right = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            if (r.bool("present")) LevelBadge("已启用", Level.Ok)
                            else LevelBadge("未配置", Level.Bad)
                            GhostBtn(text = "刷新", onClick = { scope.launch { reloadAll() } })
                        }
                    },
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            SectionTitle("限流区域 (${rateZones.size})")
                            val zoneRows = rateZones + zones.filter { it.str("type") == "global" }
                            DataTable(
                                columns = listOf(
                                    TableCol<JsonObject>(title = "类型", weight = 1f) {
                                        val t = it.str("type")
                                        val (label, tone) = when (t) {
                                            "req" -> "速率" to Level.Ok
                                            "conn" -> "并发" to Level.Ok
                                            else -> "全局" to Level.Muted
                                        }
                                        LevelBadge(label, tone)
                                    },
                                    TableCol<JsonObject>(title = "名称 / 值", weight = 3f, mono = true) {
                                        val extra = when {
                                            it.str("rate").isNotEmpty() -> " · ${it.str("rate")}"
                                            it.str("size").isNotEmpty() -> " · ${it.str("size").trim()}"
                                            else -> ""
                                        }
                                        Text(it.str("name") + extra, fontSize = 12.sp)
                                    },
                                ),
                                rows = zoneRows,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            SectionTitle("应用位置 (${applied.size})")
                            DataTable(
                                columns = listOf(
                                    TableCol<JsonObject>(title = "上下文", weight = 2f, mono = true) {
                                        Text(
                                            it.str("context").trimEnd(' ', '{').trim(),
                                            fontSize = 11.5.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    TableCol<JsonObject>(title = "区域", weight = 3f, mono = true) {
                                        val extra = if (it.str("kind") == "req") {
                                            " burst=${it.str("burst").ifEmpty { "-" }}" +
                                                (if (it.bool("nodelay")) " nodelay" else "")
                                        } else {
                                            " ×${it.str("value")}"
                                        }
                                        Text(it.str("zone") + extra, fontSize = 11.5.sp)
                                    },
                                ),
                                rows = applied,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    SectionTitle("Fail2ban jail (${jails.size})")
                    if (jails.isEmpty()) {
                        Text("无", color = Palette.muted, fontSize = 12.sp)
                    } else {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(title = "Jail", weight = 2f, mono = true) {
                                    Text(it.str("name"), fontSize = 12.sp)
                                },
                                TableCol<JsonObject>(title = "当前封禁", weight = 1f, align = TextAlign.End, mono = true) {
                                    Text("${it.long("currentlyBanned")}", fontSize = 12.sp)
                                },
                                TableCol<JsonObject>(title = "累计封禁", weight = 1f, align = TextAlign.End, mono = true) {
                                    Text("${it.long("totalBanned")}", fontSize = 12.sp)
                                },
                                TableCol<JsonObject>(title = "被封 IP", weight = 2f, mono = true) {
                                    val ips = it.arr("ips").mapNotNull { p ->
                                        (p as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    }
                                    if (ips.isEmpty()) Text("无", color = Palette.muted, fontSize = 12.sp)
                                    else Text(ips.joinToString(", "), fontSize = 11.5.sp)
                                },
                            ),
                            rows = jails,
                        )
                    }

                    val logs = r.obj("logFiles")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "配置：${logs?.str("conf") ?: "—"} · 访问日志：${logs?.str("access") ?: "—"}",
                        color = Palette.muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )

                    if (byHour.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SectionTitle("最近按小时的 429 拦截")
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(title = "小时", weight = 1f, mono = true) {
                                    Text(it.str("hour"), fontSize = 12.sp)
                                },
                                TableCol<JsonObject>(title = "429 次数", weight = 1f, align = TextAlign.End, mono = true) {
                                    Text("${it.long("count")}", fontSize = 12.sp)
                                },
                            ),
                            rows = byHour,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "修改限流阈值：编辑 00-limits.conf，然后 nginx -t && systemctl reload nginx。",
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }
            } else if (rlFailed) {
                /* rl 失败时整块不渲染（复刻 `${rl ? … : ''}`）——此处仅留占位说明 */
            }

            /* S6 sysctl */
            PanelCard(title = "内核/系统安全参数 (sysctl)", cardKey = "sec-sysctl") {
                val keys = sysctlObj?.keys?.toList() ?: emptyList()
                if (keys.isEmpty()) {
                    Text("暂无参数", color = Palette.muted, fontSize = 12.5.sp)
                } else {
                    keys.forEach { key ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                key,
                                color = Palette.muted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Field(
                                value = sysctlEdits[key] ?: "",
                                onValueChange = { sysctlEdits = sysctlEdits + (key to it) },
                                mono = true,
                                singleLine = true,
                                modifier = Modifier.width(140.dp),
                            )
                            GhostBtn(
                                text = "应用",
                                onClick = {
                                    scope.launch {
                                        val v = sysctlEdits[key] ?: ""
                                        val ok = Modal.confirm(
                                            title = "应用 sysctl",
                                            text = "设置 $key=$v 并持久化？",
                                            okText = "确认",
                                        )
                                        if (ok) {
                                            secAction(
                                                action = "sysctl-set",
                                                params = jsonObjectOf("key" to key, "value" to v),
                                                reload = { loadSecurity() },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            /* S7 + S8 */
            GridLayout(columns = 2, spacing = 12.dp) {
                PanelCard(
                    title = "用户管理 (${users.size})",
                    cardKey = "sec-users",
                    right = { GhostBtn(text = "刷新", onClick = { scope.launch { loadSecurity() } }) },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Field(
                            value = newUser,
                            onValueChange = { newUser = it },
                            hint = "用户名",
                            mono = true,
                            modifier = Modifier.weight(1f),
                        )
                        Field(
                            value = newUserPw,
                            onValueChange = { newUserPw = it },
                            hint = "密码",
                            password = true,
                            modifier = Modifier.weight(1.2f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CheckRow(checked = newUserSudo, onChange = { newUserSudo = it }, label = "sudo 权限")
                        PrimaryBtn(
                            text = "创建用户",
                            onClick = {
                                scope.launch {
                                    val name = newUser.trim()
                                    if (name.isEmpty() || newUserPw.isEmpty()) {
                                        AppState.toast("请填写用户名和密码", ToastKind.Warn)
                                        return@launch
                                    }
                                    val sudoSuffix = if (newUserSudo) " 并授予 sudo 权限" else ""
                                    val ok = Modal.confirm(
                                        title = "创建用户",
                                        text = "创建用户 $name$sudoSuffix？",
                                        okText = "确认",
                                    )
                                    if (!ok) return@launch
                                    opsRunAction(
                                        path = "/user",
                                        body = jsonObjectOf(
                                            "action" to "create",
                                            "params" to jsonObjectOf(
                                                "name" to name,
                                                "password" to newUserPw,
                                                "sudo" to newUserSudo,
                                            ),
                                        ),
                                        reload = { loadSecurity() },
                                    )
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(title = "用户", weight = 1.4f, mono = true) {
                                Text(it.str("name"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = "UID", weight = 0.8f, align = TextAlign.End, mono = true) {
                                Text("${it.long("uid")}", fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = "家目录", weight = 1.6f, mono = true) {
                                Text(it.str("home"), fontSize = 11.5.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = "Shell", weight = 1.4f, mono = true) {
                                Text(it.str("shell"), fontSize = 11.5.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = "操作", weight = 1.8f) { row ->
                                val name = row.str("name")
                                if (name == "root") {
                                    Text("—", color = Palette.muted, fontSize = 12.sp)
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        GhostBtn(
                                            text = "改密",
                                            onClick = {
                                                scope.launch {
                                                    val r = Modal.prompt(
                                                        title = "修改密码 - $name",
                                                        fields = listOf(
                                                            FormField.Password(
                                                                name = "password",
                                                                label = "新密码",
                                                            ),
                                                        ),
                                                        okText = "修改",
                                                    )
                                                    val pw = r?.get("password")
                                                    if (pw.isNullOrEmpty()) return@launch
                                                    opsRunAction(
                                                        path = "/user",
                                                        body = jsonObjectOf(
                                                            "action" to "passwd",
                                                            "params" to jsonObjectOf("name" to name, "password" to pw),
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                        GhostBtn(
                                            text = "sudo",
                                            onClick = {
                                                scope.launch {
                                                    val ok = Modal.confirm(
                                                        title = "授予 sudo",
                                                        text = "将 $name 加入 sudo 组？",
                                                        okText = "确认",
                                                        danger = true,
                                                    )
                                                    if (ok) {
                                                        secAction(
                                                            action = "sudo-grant",
                                                            params = jsonObjectOf("name" to name),
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                        DangerBtn(
                                            text = "删除",
                                            onClick = {
                                                scope.launch {
                                                    val ok = Modal.confirm(
                                                        title = "删除用户",
                                                        text = "将删除用户 $name 及其家目录（userdel -r），不可恢复！",
                                                        okText = "删除",
                                                        danger = true,
                                                    )
                                                    if (ok) {
                                                        opsRunAction(
                                                            path = "/user",
                                                            body = jsonObjectOf(
                                                                "action" to "delete",
                                                                "params" to jsonObjectOf("name" to name),
                                                            ),
                                                            reload = { loadSecurity() },
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                        ),
                        rows = users,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("sudoers：", color = Palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(3.dp))
                    val sudoers = d.arr("sudoers").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    Text(
                        sudoers.joinToString("\n").ifEmpty { "(无)" },
                        color = Palette.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                PanelCard(
                    title = "SSH 授权密钥 (authorized_keys)",
                    cardKey = "sec-keys",
                    right = { GhostBtn(text = "刷新", onClick = { scope.launch { loadKeys() } }) },
                ) {
                    if (keyError != null) {
                        Text(keyError ?: "", color = Palette.bad, fontSize = 12.sp)
                    } else if (keyLines.isEmpty()) {
                        Text("暂无授权密钥（使用密钥登录更安全）", color = Palette.muted, fontSize = 12.5.sp)
                    } else {
                        val parsed = keyLines.map { line ->
                            val p = line.split(Regex("\\s+"))
                            Triple(p.getOrElse(0) { "" }, p.getOrElse(1) { line }, p.getOrElse(2) { "" })
                        }
                        DataTable(
                            columns = listOf(
                                TableCol<Triple<String, String, String>>(title = "类型", weight = 1.2f, mono = true) {
                                    Text(it.first, fontSize = 12.sp)
                                },
                                TableCol<Triple<String, String, String>>(title = "公钥", weight = 3f, mono = true) {
                                    Text(it.second.take(60) + "…", fontSize = 11.5.sp, color = Palette.muted)
                                },
                                TableCol<Triple<String, String, String>>(title = "备注", weight = 1.6f) {
                                    Text(it.third, fontSize = 12.sp, color = Palette.muted)
                                },
                                TableCol<Triple<String, String, String>>(title = "", weight = 1f) { row ->
                                    DangerBtn(
                                        text = "删除",
                                        onClick = {
                                            scope.launch {
                                                val ok = Modal.confirm(
                                                    title = "删除公钥",
                                                    text = "删除后持有该密钥的客户端将无法登录。",
                                                    okText = "删除",
                                                    danger = true,
                                                )
                                                if (!ok) return@launch
                                                opsRunAction(
                                                    path = "/user",
                                                    body = jsonObjectOf(
                                                        "action" to "sshkey-del",
                                                        "params" to jsonObjectOf("key" to row.second),
                                                    ),
                                                    reload = { loadKeys() },
                                                )
                                            }
                                        },
                                    )
                                },
                            ),
                            rows = parsed,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Field(
                        value = newKey,
                        onValueChange = { newKey = it },
                        hint = "粘贴公钥 (ssh-ed25519 AAAA... user@host)",
                        mono = true,
                        singleLine = false,
                    )
                    Spacer(Modifier.height(6.dp))
                    PrimaryBtn(
                        text = "添加公钥",
                        onClick = {
                            scope.launch {
                                val key = newKey.trim()
                                if (key.isEmpty()) {
                                    AppState.toast("请粘贴公钥", ToastKind.Warn)
                                    return@launch
                                }
                                val r = Actions.run(
                                    path = "/user",
                                    body = jsonObjectOf(
                                        "action" to "sshkey-add",
                                        "params" to jsonObjectOf("key" to key),
                                    ),
                                )
                                if (r != null) {
                                    AppState.toast(r.str("output").ifEmpty { "操作成功" }, ToastKind.Ok)
                                    newKey = ""
                                    loadKeys()
                                }
                            }
                        },
                    )
                }
            }

            /* S9 最近登录与失败尝试 */
            PanelCard(title = "最近登录与失败尝试", cardKey = "sec-logins") {
                Text("成功登录 (last)", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(3.dp))
                val logins = d.arr("recentLogins").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                MonoBox(text = logins.joinToString("\n").ifEmpty { "(无)" }, height = 150.dp)
                Spacer(Modifier.height(8.dp))
                Text("失败尝试 (lastb)", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(3.dp))
                val failed = d.arr("recentFailed").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                MonoBox(text = failed.joinToString("\n").ifEmpty { "(无记录)" }, height = 180.dp)
            }
        }
    }
}

/** 面板 `<pre class="pre">` 的等价物：等宽、固定高度、内部可滚动。 */
@Composable
private fun MonoBox(text: String, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(Palette.bg2, RoundedCornerShape(3.dp))
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text,
            color = Palette.text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp,
        )
    }
}
