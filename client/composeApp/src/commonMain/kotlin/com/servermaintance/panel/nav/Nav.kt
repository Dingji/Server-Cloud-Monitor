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
import com.servermaintance.panel.i18n.L
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
    /** i18n key in `i18n/<locale>.json` */
    val labelKey: String,
    val icon: String,
    val sectionKey: String,
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
    NavEntry(PageId.Dashboard, "nav.dashboard", "dashboard", "nav.section.monitor"),
    NavEntry(PageId.HealthAlerts, "nav.health_alerts", "shieldAlert", "nav.section.monitor"),
    NavEntry(PageId.SLO, "nav.slo", "gauge", "nav.section.monitor"),
    NavEntry(PageId.OpsUptime, "nav.ops_uptime", "activity", "nav.section.monitor"),
    NavEntry(PageId.OpsRules, "nav.ops_rules", "bell", "nav.section.monitor"),
    NavEntry(PageId.OpsChannels, "nav.ops_channels", "mail", "nav.section.monitor"),
    NavEntry(PageId.Relay, "nav.relay", "satellite", "nav.section.monitor", optional = true),
    NavEntry(PageId.Processes, "nav.processes", "process", "nav.section.server"),
    NavEntry(PageId.Disks, "nav.disks", "disk", "nav.section.server"),
    NavEntry(PageId.Memory, "nav.memory", "memory", "nav.section.server"),
    NavEntry(PageId.Network, "nav.network", "network", "nav.section.server"),
    NavEntry(PageId.Security, "nav.security", "shield", "nav.section.server"),
    NavEntry(PageId.Services, "nav.services", "gear", "nav.section.server"),
    NavEntry(PageId.Packages, "nav.packages", "package", "nav.section.server"),
    NavEntry(PageId.Databases, "nav.databases", "database", "nav.section.app"),
    NavEntry(PageId.Docker, "nav.docker", "docker", "nav.section.app"),
    NavEntry(PageId.Web, "nav.web", "browser", "nav.section.app"),
    NavEntry(PageId.Cron, "nav.cron", "clock", "nav.section.app"),
    NavEntry(PageId.Backups, "nav.backups", "archive", "nav.section.app"),
    NavEntry(PageId.Logs, "nav.logs", "logs", "nav.section.app"),
    NavEntry(PageId.Audit, "nav.audit", "list", "nav.section.app"),
    NavEntry(PageId.OpsFiles, "nav.ops_files", "folder", "nav.section.server"),
    NavEntry(PageId.OpsHosts, "nav.ops_hosts", "server", "nav.section.server"),
    NavEntry(PageId.OpsSec, "nav.ops_sec", "shieldAlert", "nav.section.server"),
    NavEntry(PageId.OpsDisk, "nav.ops_disk", "disk", "nav.section.server"),
    NavEntry(PageId.OpsWatch, "nav.ops_watch", "shield", "nav.section.server"),
    NavEntry(PageId.OpsRemote, "nav.ops_remote", "cloud", "nav.section.server"),
    NavEntry(PageId.Notes, "nav.notes", "note", "nav.section.notes"),
    NavEntry(PageId.Todos, "nav.todos", "checkSquare", "nav.section.notes"),
    NavEntry(PageId.NoteTransfer, "nav.note_transfer", "import", "nav.section.notes"),
    NavEntry(PageId.NoteMail, "nav.note_mail", "mail", "nav.section.notes", adminOnly = true),
    NavEntry(PageId.NoteSettings, "nav.note_settings", "sliders", "nav.section.notes"),
    NavEntry(PageId.NoteUsers, "nav.note_users", "user", "nav.section.notes", adminOnly = true),
    NavEntry(PageId.Terminal, "nav.terminal", "terminal", "nav.section.tools"),
    NavEntry(PageId.Settings, "nav.settings", "sliders", "nav.section.system"),
)

/** 分组出现顺序（照抄 app.js 的 sep 顺序；「笔记」为 NoteBox 新增分组）。 */
val NAV_SECTIONS: List<String> = listOf(
    "nav.section.monitor",
    "nav.section.server",
    "nav.section.app",
    "nav.section.notes",
    "nav.section.tools",
    "nav.section.system",
)

fun NavEntry.labelText(): String = L[labelKey]

fun NavEntry.sectionText(): String = L[sectionKey]

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
        visibleNav(relayAvailable, isAdmin).filter { it.sectionKey == sec }
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
            text = L.fmt("nav.page_placeholder", title),
            color = Palette.muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}
