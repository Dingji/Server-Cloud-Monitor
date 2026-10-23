package com.servermaintance.panel.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.pages.*
import com.servermaintance.panel.pages.ops.*
import com.servermaintance.panel.ui.Palette

/**
 * 页面注册表 —— **所有 pages 角色对接的稳定接口**。
 *
 * 顺序、分组标签、文案完全照抄 `upstream/public/js/app.js` 的 NAV：
 * 监控 / 服务器 / 应用 / 工具 / 系统。
 */
enum class PageId {
    Dashboard, HealthAlerts, SLO, OpsUptime, OpsRules, OpsChannels, Relay,
    Processes, Disks, Memory, Network, Security, Services, Packages,
    Databases, Docker, Web, Cron, Backups, Logs, Audit,
    OpsFiles, OpsHosts, OpsSec, OpsDisk, OpsWatch, OpsRemote,
    Notes, Todos, NoteTransfer, NoteMail, NoteSettings, NoteUsers,
    Terminal, Settings,
}

/**
 * 一条导航项。[section] 为分组标签（同一分组的条目在 NAV 中连续出现）。
 * [optional] 为 true 的条目只有在探测成功后才会显示（目前仅 relay）。
 */
data class NavEntry(
    val id: PageId,
    val label: String,
    val icon: String,
    val section: String,
    val optional: Boolean = false,
    /** 仅 NoteBox 管理员可见（用户管理/邮件通知）；非 admin 时隐藏。 */
    val adminOnly: Boolean = false,
)

/** 面板路由用的小写 id，与 app.js 的 `n.id` / `CM.lazyPages` 的 key 一致。 */
val PageId.route: String
    get() = when (this) {
        PageId.Dashboard -> "dashboard"
        PageId.HealthAlerts -> "healthalerts"
        PageId.SLO -> "slo"
        PageId.OpsUptime -> "opsuptime"
        PageId.OpsRules -> "opsrules"
        PageId.OpsChannels -> "opschannels"
        PageId.Relay -> "relay"
        PageId.Processes -> "processes"
        PageId.Disks -> "disks"
        PageId.Memory -> "memory"
        PageId.Network -> "network"
        PageId.Security -> "security"
        PageId.Services -> "services"
        PageId.Packages -> "packages"
        PageId.Databases -> "databases"
        PageId.Docker -> "docker"
        PageId.Web -> "web"
        PageId.Cron -> "cron"
        PageId.Backups -> "backups"
        PageId.Logs -> "logs"
        PageId.Audit -> "audit"
        PageId.OpsFiles -> "opsfiles"
        PageId.OpsHosts -> "opshosts"
        PageId.OpsSec -> "opssec"
        PageId.OpsDisk -> "opsdisk"
        PageId.OpsWatch -> "opswatch"
        PageId.OpsRemote -> "opsremote"
        PageId.Notes -> "notes"
        PageId.Todos -> "todos"
        PageId.NoteTransfer -> "notetransfer"
        PageId.NoteMail -> "notemail"
        PageId.NoteSettings -> "notesettings"
        PageId.NoteUsers -> "noteusers"
        PageId.Terminal -> "terminal"
        PageId.Settings -> "settings"
    }

/** 面板导航（含可选的 relay 项，紧随 dashboard 之后，同 app.js 注释位置）。 */
val NAV: List<NavEntry> = listOf(
    NavEntry(PageId.Dashboard, "仪表盘", "dashboard", "监控"),
    NavEntry(PageId.HealthAlerts, "健康告警", "shieldAlert", "监控"),
    NavEntry(PageId.SLO, "SLO 与用量", "gauge", "监控"),
    NavEntry(PageId.OpsUptime, "可用性拨测", "activity", "监控"),
    NavEntry(PageId.OpsRules, "告警规则", "bell", "监控"),
    NavEntry(PageId.OpsChannels, "通知渠道", "mail", "监控"),
    NavEntry(PageId.Relay, "OpenCode 中继", "satellite", "监控", optional = true),
    NavEntry(PageId.Processes, "进程管理", "process", "服务器"),
    NavEntry(PageId.Disks, "磁盘管理", "disk", "服务器"),
    NavEntry(PageId.Memory, "内存管理", "memory", "服务器"),
    NavEntry(PageId.Network, "网络管理", "network", "服务器"),
    NavEntry(PageId.Security, "安全管理", "shield", "服务器"),
    NavEntry(PageId.Services, "系统服务", "gear", "服务器"),
    NavEntry(PageId.Packages, "软件包管理", "package", "服务器"),
    NavEntry(PageId.Databases, "数据库", "database", "应用"),
    NavEntry(PageId.Docker, "Docker 容器", "docker", "应用"),
    NavEntry(PageId.Web, "网站 / Nginx", "browser", "应用"),
    NavEntry(PageId.Cron, "定时任务", "clock", "应用"),
    NavEntry(PageId.Backups, "备份中心", "archive", "应用"),
    NavEntry(PageId.Logs, "日志中心", "logs", "应用"),
    NavEntry(PageId.Audit, "审计日志", "list", "应用"),
    NavEntry(PageId.OpsFiles, "文件管理", "folder", "服务器"),
    NavEntry(PageId.OpsHosts, "纳管主机", "server", "服务器"),
    NavEntry(PageId.OpsSec, "安全基线巡检", "shieldAlert", "服务器"),
    NavEntry(PageId.OpsDisk, "磁盘治理", "disk", "服务器"),
    NavEntry(PageId.OpsWatch, "服务看护", "shield", "服务器"),
    NavEntry(PageId.OpsRemote, "远程主机指标", "cloud", "服务器"),
    NavEntry(PageId.Notes, "笔记", "note", "笔记"),
    NavEntry(PageId.Todos, "待办清单", "checkSquare", "笔记"),
    NavEntry(PageId.NoteTransfer, "导入 / 导出", "import", "笔记"),
    NavEntry(PageId.NoteMail, "邮件通知", "mail", "笔记", adminOnly = true),
    NavEntry(PageId.NoteSettings, "NoteBox 设置", "sliders", "笔记"),
    NavEntry(PageId.NoteUsers, "用户管理", "user", "笔记", adminOnly = true),
    NavEntry(PageId.Terminal, "Web 终端", "terminal", "工具"),
    NavEntry(PageId.Settings, "面板设置", "sliders", "系统"),
)

/** 分组出现顺序（照抄 app.js 的 sep 顺序；「笔记」为 NoteBox 新增分组）。 */
val NAV_SECTIONS: List<String> = listOf("监控", "服务器", "应用", "笔记", "工具", "系统")

/** 按 id 取注册项（含 label/icon/section）。 */
fun entryOf(id: PageId): NavEntry = NAV.first { it.id == id }

/** 路由字符串 -> PageId，未知 id 返回 null（调用方回落到 Dashboard）。 */
fun pageIdOf(route: String?): PageId? =
    NAV.firstOrNull { it.id.route == (route ?: "").trim().lowercase() }?.id

/**
 * 按可选模块探测结果 + NoteBox 角色过滤后的可见导航，顺序与面板一致：
 * relay 探测（`GET /relay/available` → `ok`）成功才插在 dashboard 之后；
 * [isAdmin] 为 false 时隐藏 NoteBox 管理员专属项（用户管理/邮件通知）。
 */
fun visibleNav(relayAvailable: Boolean, isAdmin: Boolean = true): List<NavEntry> =
    NAV.filter { (!it.optional || relayAvailable) && (!it.adminOnly || isAdmin) }

/** 按分组整理（只含可见项），侧栏按此顺序渲染分组标题与条目。 */
fun groupedNav(relayAvailable: Boolean, isAdmin: Boolean = true): List<Pair<String, List<NavEntry>>> =
    NAV_SECTIONS.mapNotNull { sec ->
        visibleNav(relayAvailable, isAdmin).filter { it.section == sec }
            .takeIf { it.isNotEmpty() }?.let { sec to it }
    }

/**
 * 每个页面的渲染入口 —— pages 角色各自实现 `pages/XxxPage()`，
 * 这里只做 `when` 分发（外壳不提供页头，页面自带 PageScaffold）。
 */
@Composable
fun PageContent(id: PageId) {
    when (id) {
        PageId.Dashboard -> DashboardPage()
        PageId.HealthAlerts -> HealthAlertsPage()
        PageId.SLO -> SloPage()
        PageId.OpsUptime -> OpsUptimePage()
        PageId.OpsRules -> OpsRulesPage()
        PageId.OpsChannels -> OpsChannelsPage()
        PageId.Relay -> RelayPage()
        PageId.Processes -> ProcessesPage()
        PageId.Disks -> DisksPage()
        PageId.Memory -> MemoryPage()
        PageId.Network -> NetworkPage()
        PageId.Security -> SecurityPage()
        PageId.Services -> ServicesPage()
        PageId.Packages -> PackagesPage()
        PageId.Databases -> DatabasesPage()
        PageId.Docker -> DockerPage()
        PageId.Web -> WebPage()
        PageId.Cron -> CronPage()
        PageId.Backups -> BackupsPage()
        PageId.Logs -> LogsPage()
        PageId.Audit -> AuditPage()
        PageId.Notes -> NotesPage()
        PageId.Todos -> TodosPage()
        PageId.NoteTransfer -> NoteTransferPage()
        PageId.NoteMail -> NoteMailPage()
        PageId.NoteSettings -> NoteSettingsPage()
        PageId.NoteUsers -> NoteUsersPage()
        PageId.OpsFiles -> OpsFilesPage()
        PageId.OpsHosts -> OpsHostsPage()
        PageId.OpsSec -> OpsSecPage()
        PageId.OpsDisk -> OpsDiskPage()
        PageId.OpsWatch -> OpsWatchPage()
        PageId.OpsRemote -> OpsRemotePage()
        PageId.Terminal -> TerminalPage()
        PageId.Settings -> SettingsPage()
    }
}

/** 页面尚未实现时的占位（pages 角色会用真实页面替换整个函数体）。 */
@Composable
fun PagePlaceholder(title: String) {
    Box(
        Modifier.fillMaxSize().background(Palette.bg).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "「$title」页面实现中…",
            color = Palette.muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}
