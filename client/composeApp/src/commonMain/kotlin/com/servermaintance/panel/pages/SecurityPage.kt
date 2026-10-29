package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.long
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AdaptiveRow
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
import com.servermaintance.panel.ui.SectionTitle
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import com.servermaintance.panel.ui.slotHorizontalScroll
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

    PageScaffold(title = L["安全管理_27a60a5c86"]) {

        if (d == null && loading) { Text(L["加载中_300ee3dee4"], color = Palette.muted, fontSize = 13.sp) }
        if (d == null && !loading && error != null) {
            ErrorState(message = error ?: L["加载失败_f6b7a41e56"], onRetry = { scope.launch { loadSecurity() } })
        }

        if (d != null) {
            /* S1 姿态四联 */
            GridLayout(columns = 4, spacing = 12.dp) {
                StatTile(
                    label = L["主机防火墙_ufw_28b14f25fa"],
                    value = if (ufwOn) L["已启用_25d2843150"] else L["未启用_8bb38ef00c"],
                    sub = trfn("ufwrulescount_条规则_4d66159719", "ufwRulesCount" to ufwRulesCount),
                    valueColor = if (ufwOn) Color(0xFF4ADE80) else Color(0xFFFACC15),
                    icon = "firewall",
                )
                StatTile(
                    label = L["root_密码登录_df671edd13"],
                    value = sshd.str("permitrootlogin").ifEmpty { "?" },
                    sub = "PermitRootLogin",
                    valueColor = if (sshd.str("permitrootlogin") == "yes") Color(0xFFF87171) else Color(0xFF4ADE80),
                    icon = "key",
                )
                StatTile(
                    label = L["ssh_密码认证_f22e3cd826"],
                    value = sshd.str("passwordauthentication").ifEmpty { "?" },
                    sub = "PasswordAuthentication",
                    valueColor = if (sshd.str("passwordauthentication") == "yes") Color(0xFFFACC15) else Color(0xFF4ADE80),
                    icon = "unlock",
                )
                StatTile(
                    label = "Fail2ban",
                    value = if (f2b?.bool("active") == true) L["防护中_a8d7b5d826"] else L["未防护_ba54f04f59"],
                    sub = L["ssh_防爆破_3e3c79eef0"],
                    valueColor = if (f2b?.bool("active") == true) Color(0xFF4ADE80) else Color(0xFFF87171),
                    icon = "shield",
                )
            }

            /* S2 + S3 */
            GridLayout(columns = 2, spacing = 12.dp) {
                PanelCard(
                    title = L["ssh_服务配置_sshd_有效配置_b5a2f5733e"],
                    cardKey = "sec-sshd",
                    right = {
                        PrimaryBtn(
                            text = L["应用并重载_ssh_68c70d62a3"],
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
                                        L["警告_将关闭密码认证_请确认已配置_ssh_38bc29023e"]
                                    } else ""
                                    val ok = Modal.confirm(
                                        title = L["应用_ssh_配置_6630ee6205"],
                                        text = L["将写入并重载_sshd_body_warn_b8307d441d"],
                                        okText = L["确认_b56d9ac6c5"],
                                        danger = true,
                                    )
                                    if (!ok) return@launch
                                    secAction(
                                        action = "sshd-apply",
                                        params = params,
                                        title = L["ssh_配置应用_c35c2ef2bf"],
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
                            label = L["ssh_端口_db451b3851"],
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
                        L["修改会写入_etc_ssh_sshd_conf_4cb39a7a47"],
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }

                PanelCard(
                    title = L["fail2ban_防爆破_e5d811ec15"],
                    cardKey = "sec-f2b",
                    right = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.slotHorizontalScroll(),
                        ) {
                            val active = f2b?.bool("active") == true
                            if (active) {
                                LevelBadge(L["运行中_5942497005"], Level.Ok)
                                DangerBtn(
                                    text = L["停止_a17f70a8d3"],
                                    onClick = {
                                        scope.launch {
                                            val ok = Modal.confirm(
                                                title = L["停止_fail2ban_c4775a3abd"],
                                                text = L["停止后将失去_ssh_防爆破保护_cf3e594655"],
                                                okText = L["确认_b56d9ac6c5"],
                                                danger = true,
                                            )
                                            if (ok) secAction("fail2ban-stop", reload = { loadSecurity() })
                                        }
                                    },
                                )
                            } else {
                                LevelBadge(L["未运行_b2ba9634d9"], Level.Bad)
                                PrimaryBtn(
                                    text = L["安装并启动_d20e3e1fda"],
                                    onClick = {
                                        scope.launch {
                                            val ok = Modal.confirm(
                                                title = L["安装_fail2ban_38aa818b73"],
                                                text = L["将执行_apt_get_install_fail_e1244066c3"],
                                                okText = L["安装_087db63ab1"],
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
                                    text = L["启动服务_f5f2a0dd6f"],
                                    onClick = { scope.launch { secAction("fail2ban-start", reload = { loadSecurity() }) } },
                                )
                            }
                        }
                    },
                ) {
                    MonoBox(
                        text = f2b?.str("status")?.ifEmpty { L["fail2ban_未安装_未运行_006151f653"] } ?: L["fail2ban_未安装_未运行_006151f653"],
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
                            hint = L["要解封的_ip_32d685e891"],
                            mono = true,
                            modifier = Modifier.weight(1f),
                        )
                        GhostBtn(
                            text = L["解封_ip_af385638f1"],
                            onClick = {
                                scope.launch {
                                    val ip = unbanIp.trim()
                                    if (ip.isEmpty()) {
                                        AppState.toast(tr("请输入_ip_338f1d98f9"), ToastKind.Warn)
                                        return@launch
                                    }
                                    secAction("fail2ban-unban", params = jsonObjectOf("ip" to ip))
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        trf("最近封禁_0_073f233150", f2b?.str("banned")?.ifEmpty { L["无数据_6ffa7e6d35"] } ?: L["无数据_6ffa7e6d35"]),
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
                        label = L["429_拦截_563bec273d"],
                        value = "${r.long("limited429")}",
                        sub = trf("最近_0_条请求中_22b35b84eb", r.str("windowLines")),
                    )
                    StatTile(
                        label = L["限流日志_cadf589c16"],
                        value = "${r.long("errorLimitCount")}",
                        sub = L["error_log_累计_40739fb64e"],
                    )
                    StatTile(
                        label = L["限流区域_f6cdd5f94a"],
                        value = "${rateZones.size}",
                        sub = L["limit_req_limit_conn_03b8dfa986"],
                    )
                    StatTile(
                        label = L["当前封禁_ip_c5ccad72be"],
                        value = trfn("banned_df7f6e7b0c", "banned" to banned),
                        sub = trf("0_个_jail_b6895cfd78", jails.size),
                        valueColor = if (banned > 0) Color(0xFFEF4444) else null,
                        icon = "firewall",
                    )
                }

                PanelCard(
                    title = L["限流_cc_防护_1cacc191c4"],
                    cardKey = "sec-rl",
                    right = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.slotHorizontalScroll(),
                        ) {
                            if (r.bool("present")) LevelBadge(L["已启用_25d2843150"], Level.Ok)
                            else LevelBadge(L["未配置_63595e95b7"], Level.Bad)
                            GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { reloadAll() } })
                        }
                    },
                ) {
                    AdaptiveRow(
                        spacing = 14.dp,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.span(1f)) {
                            SectionTitle(trf("限流区域_0_2073b98a9e", rateZones.size))
                            val zoneRows = rateZones + zones.filter { it.str("type") == "global" }
                            DataTable(
                                columns = listOf(
                                    TableCol<JsonObject>(title = L["类型_e4e46c7235"], weight = 1f) {
                                        val t = it.str("type")
                                        val (label, tone) = when (t) {
                                            "req" -> L["速率_8ec7c4ba74"] to Level.Ok
                                            "conn" -> L["并发_09d869d15d"] to Level.Ok
                                            else -> L["全局_a5644f4bbf"] to Level.Muted
                                        }
                                        LevelBadge(label, tone)
                                    },
                                    TableCol<JsonObject>(title = L["名称_值_576e23fba3"], weight = 3f, mono = true) {
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
                        Column(Modifier.span(1f)) {
                            SectionTitle(trf("应用位置_0_48979a348d", applied.size))
                            DataTable(
                                columns = listOf(
                                    TableCol<JsonObject>(title = L["上下文_d9aa9fe0d6"], weight = 2f, mono = true) {
                                        Text(
                                            it.str("context").trimEnd(' ', '{').trim(),
                                            fontSize = 11.5.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    TableCol<JsonObject>(title = L["区域_17fc93c9cd"], weight = 3f, mono = true) {
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
                        Text(L["无_72077749f7"], color = Palette.muted, fontSize = 12.sp)
                    } else {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(title = "Jail", weight = 2f, mono = true) {
                                    Text(it.str("name"), fontSize = 12.sp)
                                },
                                TableCol<JsonObject>(title = L["当前封禁_4f4e3ab4f7"], weight = 1f, align = TextAlign.End, mono = true) {
                                    Text("${it.long("currentlyBanned")}", fontSize = 12.sp)
                                },
                                TableCol<JsonObject>(title = L["累计封禁_27b6c8449a"], weight = 1f, align = TextAlign.End, mono = true) {
                                    Text("${it.long("totalBanned")}", fontSize = 12.sp)
                                },
                                TableCol<JsonObject>(title = L["被封_ip_906f300fbd"], weight = 2f, mono = true) {
                                    val ips = it.arr("ips").mapNotNull { p ->
                                        (p as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    }
                                    if (ips.isEmpty()) Text(L["无_72077749f7"], color = Palette.muted, fontSize = 12.sp)
                                    else Text(ips.joinToString(L["s_d3bc9a378d"]), fontSize = 11.5.sp)
                                },
                            ),
                            rows = jails,
                        )
                    }

                    val logs = r.obj("logFiles")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        trf("配置_0_访问日志_1_67aaf6d9b8", logs?.str("conf") ?: "—", logs?.str("access") ?: "—"),
                        color = Palette.muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )

                    if (byHour.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SectionTitle(L["最近按小时的_429_拦截_cd3fc0518c"])
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(title = L["小时_99f6904ff3"], weight = 1f, mono = true) {
                                    Text(it.str("hour"), fontSize = 12.sp)
                                },
                                TableCol<JsonObject>(title = L["429_次数_9333e8c60f"], weight = 1f, align = TextAlign.End, mono = true) {
                                    Text("${it.long("count")}", fontSize = 12.sp)
                                },
                            ),
                            rows = byHour,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        L["修改限流阈值_编辑_00_limits_conf_1152af13c3"],
                        color = Palette.muted,
                        fontSize = 12.sp,
                    )
                }
            } else if (rlFailed) {
                /* rl 失败时整块不渲染（复刻 `${rl ? … : ''}`）——此处仅留占位说明 */
            }

            /* S6 sysctl */
            PanelCard(title = L["内核_系统安全参数_sysctl_1d74700ad7"], cardKey = "sec-sysctl") {
                val keys = sysctlObj?.keys?.toList() ?: emptyList()
                if (keys.isEmpty()) {
                    Text(L["暂无参数_8ff5e03996"], color = Palette.muted, fontSize = 12.5.sp)
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
                                text = L["nav.section.app"],
                                onClick = {
                                    scope.launch {
                                        val v = sysctlEdits[key] ?: ""
                                        val ok = Modal.confirm(
                                            title = L["应用_sysctl_e2dac5275c"],
                                            text = L["设置_key_v_并持久化_090be0bfab"],
                                            okText = L["确认_b56d9ac6c5"],
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
                    title = trf("用户管理_0_9d8d092e16", users.size),
                    cardKey = "sec-users",
                    right = { GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { loadSecurity() } }) },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Field(
                            value = newUser,
                            onValueChange = { newUser = it },
                            hint = L["用户名_a1aaf352cb"],
                            mono = true,
                            modifier = Modifier.weight(1f),
                        )
                        Field(
                            value = newUserPw,
                            onValueChange = { newUserPw = it },
                            hint = L["密码_c839a8ff17"],
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
                        CheckRow(checked = newUserSudo, onChange = { newUserSudo = it }, label = L["sudo_权限_e03e1e61f9"])
                        PrimaryBtn(
                            text = L["创建用户_315315d695"],
                            onClick = {
                                scope.launch {
                                    val name = newUser.trim()
                                    if (name.isEmpty() || newUserPw.isEmpty()) {
                                        AppState.toast(tr("请填写用户名和密码_85362f2da5"), ToastKind.Warn)
                                        return@launch
                                    }
                                    val sudoSuffix = if (newUserSudo) L["并授予_sudo_权限_8b30bef02a"] else ""
                                    val ok = Modal.confirm(
                                        title = L["创建用户_315315d695"],
                                        text = trfn("创建用户_name_sudosuffix_423f11e860", "name" to name, "sudoSuffix" to sudoSuffix),
                                        okText = L["确认_b56d9ac6c5"],
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
                            TableCol<JsonObject>(title = L["用户_9ba763ea34"], weight = 1.4f, mono = true) {
                                Text(it.str("name"), fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = "UID", weight = 0.8f, align = TextAlign.End, mono = true) {
                                Text("${it.long("uid")}", fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(title = L["家目录_f273ea575f"], weight = 1.6f, mono = true) {
                                Text(it.str("home"), fontSize = 11.5.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = "Shell", weight = 1.4f, mono = true) {
                                Text(it.str("shell"), fontSize = 11.5.sp, color = Palette.muted)
                            },
                            TableCol<JsonObject>(title = L["操作_f3ea6d345e"], weight = 1.8f) { row ->
                                val name = row.str("name")
                                if (name == "root") {
                                    Text("—", color = Palette.muted, fontSize = 12.sp)
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        GhostBtn(
                                            text = L["改密_bb974d3064"],
                                            onClick = {
                                                scope.launch {
                                                    val r = Modal.prompt(
                                                        title = trfn("修改密码_name_d9c700cba6", "name" to name),
                                                        fields = listOf(
                                                            FormField.Password(
                                                                name = "password",
                                                                label = L["新密码_d22c9c0085"],
                                                            ),
                                                        ),
                                                        okText = L["修改_c9c77517fe"],
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
                                                        title = L["授予_sudo_b17c8ef2b8"],
                                                        text = trfn("将_name_加入_sudo_组_e6b5bf2823", "name" to name),
                                                        okText = L["确认_b56d9ac6c5"],
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
                                            text = L["删除_3755f56f2f"],
                                            onClick = {
                                                scope.launch {
                                                    val ok = Modal.confirm(
                                                        title = L["删除用户_bcfca951a1"],
                                                        text = trfn("将删除用户_name_及其家目录_userde_38f9f46e9a", "name" to name),
                                                        okText = L["删除_3755f56f2f"],
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
                    Text(L["sudoers_21c467b0a2"], color = Palette.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(3.dp))
                    val sudoers = d.arr("sudoers").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    Text(
                        sudoers.joinToString("\n").ifEmpty { L["无_3f83251907"] },
                        color = Palette.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                PanelCard(
                    title = L["ssh_授权密钥_authorized_key_4fee36881a"],
                    cardKey = "sec-keys",
                    right = { GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { loadKeys() } }) },
                ) {
                    if (keyError != null) {
                        Text(keyError ?: "", color = Palette.bad, fontSize = 12.sp)
                    } else if (keyLines.isEmpty()) {
                        Text(L["暂无授权密钥_使用密钥登录更安全_07e7ba27d0"], color = Palette.muted, fontSize = 12.5.sp)
                    } else {
                        val parsed = keyLines.map { line ->
                            val p = line.split(Regex("\\s+"))
                            Triple(p.getOrElse(0) { "" }, p.getOrElse(1) { line }, p.getOrElse(2) { "" })
                        }
                        DataTable(
                            columns = listOf(
                                TableCol<Triple<String, String, String>>(title = L["类型_e4e46c7235"], weight = 1.2f, mono = true) {
                                    Text(it.first, fontSize = 12.sp)
                                },
                                TableCol<Triple<String, String, String>>(title = L["公钥_b6965e119c"], weight = 3f, mono = true) {
                                    Text(it.second.take(60) + L["s_df316e17de"], fontSize = 11.5.sp, color = Palette.muted)
                                },
                                TableCol<Triple<String, String, String>>(title = L["备注_e0361480e3"], weight = 1.6f) {
                                    Text(it.third, fontSize = 12.sp, color = Palette.muted)
                                },
                                TableCol<Triple<String, String, String>>(title = "", weight = 1f) { row ->
                                    DangerBtn(
                                        text = L["删除_3755f56f2f"],
                                        onClick = {
                                            scope.launch {
                                                val ok = Modal.confirm(
                                                    title = L["删除公钥_b83efa4dbe"],
                                                    text = L["删除后持有该密钥的客户端将无法登录_1d472813e0"],
                                                    okText = L["删除_3755f56f2f"],
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
                        hint = L["粘贴公钥_ssh_ed25519_aaaa_2134dc2324"],
                        mono = true,
                        singleLine = false,
                    )
                    Spacer(Modifier.height(6.dp))
                    PrimaryBtn(
                        text = L["添加公钥_3121954aea"],
                        onClick = {
                            scope.launch {
                                val key = newKey.trim()
                                if (key.isEmpty()) {
                                    AppState.toast(tr("请粘贴公钥_f95fd35ee8"), ToastKind.Warn)
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
                                    AppState.toast(r.str("output").ifEmpty { tr("操作成功_3c5be5f2a1") }, ToastKind.Ok)
                                    newKey = ""
                                    loadKeys()
                                }
                            }
                        },
                    )
                }
            }

            /* S9 最近登录与失败尝试 */
            PanelCard(title = L["最近登录与失败尝试_d575d4606c"], cardKey = "sec-logins") {
                Text(L["成功登录_last_d5df9592bd"], color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(3.dp))
                val logins = d.arr("recentLogins").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                MonoBox(text = logins.joinToString("\n").ifEmpty { L["无_3f83251907"] }, height = 150.dp)
                Spacer(Modifier.height(8.dp))
                Text(L["失败尝试_lastb_56a9bc43bc"], color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.height(3.dp))
                val failed = d.arr("recentFailed").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                MonoBox(text = failed.joinToString("\n").ifEmpty { L["无记录_3c275e011f"] }, height = 180.dp)
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
