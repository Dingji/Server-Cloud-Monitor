package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.trfn
import com.servermaintance.panel.i18n.L

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.AccentState
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ *
 * 页面外壳：引擎切换条 / 页签条 / 安装引导 / 错误卡 / 页签加载容器
 * ------------------------------------------------------------------ */

enum class DbBtnTone { Plain, Primary, Ok, Danger }

/* 共享文本样式（表格单元格/说明行，db 包内复用） */
val dbMono = TextStyle(color = Palette.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
val dbMonoSmall = TextStyle(color = Palette.muted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
val dbBody = TextStyle(color = Palette.text, fontSize = 12.5.sp)
val dbMuted = TextStyle(color = Palette.muted, fontSize = 11.5.sp)

/** 面板 `.btn.btn-sm` 等价小按钮（复刻 app.css 的四种按钮配色）。[onClick] 必须是末参以支持尾随 lambda。 */
@Composable
fun DbBtn(
    text: String,
    modifier: Modifier = Modifier,
    tone: DbBtnTone = DbBtnTone.Plain,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(3.dp)
    val bg: Color
    val border: Color
    val fg: Color
    when (tone) {
        DbBtnTone.Plain -> {
            bg = Palette.panel3; border = Palette.border2; fg = Palette.text
        }
        DbBtnTone.Primary -> {
            bg = Color(0xFF2A53C9); border = Color(0xFF3562E0); fg = Color.White
        }
        DbBtnTone.Ok -> {
            bg = Palette.ok.copy(alpha = 0.09f); border = Palette.ok.copy(alpha = 0.45f); fg = Color(0xFF4ADE80)
        }
        DbBtnTone.Danger -> {
            bg = Palette.bad.copy(alpha = 0.09f); border = Palette.bad.copy(alpha = 0.45f); fg = Color(0xFFF87171)
        }
    }
    Row(
        modifier
            .background(if (enabled) bg else bg.copy(alpha = 0.5f), shape)
            .border(1.dp, if (enabled) border else border.copy(alpha = 0.4f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (enabled) fg else fg.copy(alpha = 0.5f),
            fontSize = 11.5.sp,
            fontWeight = if (tone == DbBtnTone.Primary) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 引擎切换胶囊（按钮内可放徽章，复刻 `.btn.active` + badge 结构）。 */
@Composable
fun DbPill(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Row(
        Modifier
            .background(
                if (selected) AccentState.current.primary.copy(alpha = 0.10f) else Palette.panel3,
                shape,
            )
            .border(1.dp, if (selected) AccentState.current.primary else Palette.border2, shape)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/* -------------------- 引擎切换条 -------------------- */

@Composable
fun EngineBar(st: DbState, scope: CoroutineScope) {
    var showInstall by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.panel, RoundedCornerShape(3.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(3.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                st.engines.forEach { e ->
                    EnginePill(st, e, scope)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(L["引擎_9413bdc38c"], color = Palette.muted, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            DbBtn(L["安装引擎_e5276cf0a5"], tone = DbBtnTone.Plain) { showInstall = !showInstall }
            Spacer(Modifier.width(6.dp))
            DbBtn(
                text = if (refreshing) L["刷新中_ea0ab1492c"] else L["刷新引擎信息_022b2c17cd"],
                enabled = !refreshing,
                onClick = {
                    scope.launch {
                        refreshing = true
                        reloadAfterService(st)
                        refreshing = false
                    }
                },
            )
        }
        if (showInstall) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            Spacer(Modifier.height(10.dp))
            Text(L["数据库引擎安装_1b953ccc06"], color = Palette.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            InstallGrid(st, scope)
        }
    }
}

@Composable
private fun EnginePill(st: DbState, e: DbEngineItem, scope: CoroutineScope) {
    val selected = e.id == st.engineId
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        DbPill(selected = selected, onClick = { st.switchEngine(e.id); st.bump() }) {
            Text(
                e.name,
                color = if (selected) AccentState.current.secondary else Palette.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            when (e.kind) {
                "kv" -> LevelBadge("KV", Level.Info)
                "doc" -> LevelBadge("DOC", Level.Info)
            }
            LevelBadge(if (e.active) L["运行中_5942497005"] else L["未运行_b2ba9634d9"], if (e.active) Level.Ok else Level.Bad)
        }
        val unit = DbMeta.serviceUnit(e.id, e.service)
        if (unit != null) {
            if (e.active) {
                DbBtn(L["停止_a17f70a8d3"], tone = DbBtnTone.Plain) { engineService(scope, st, e.id, "stop") }
            } else {
                DbBtn(L["启动_ebd26da421"], tone = DbBtnTone.Ok) { engineService(scope, st, e.id, "start") }
            }
        }
    }
}

/** 启停引擎服务（复刻 L180-L196：确认 → /db/engine/service → 清缓存重取）。 */
fun engineService(scope: CoroutineScope, st: DbState, engineId: String, action: String) {
    scope.launch {
        val label = DbMeta.label(engineId)
        val okc = if (action == "stop") {
            Modal.confirm(trf("停止_0_e0be2c8b16", label), trf("将停止_0_服务_不会删除数据_3644eaaa47", label), okText = L["停止_a17f70a8d3"], danger = true)
        } else {
            Modal.confirm(trf("启动_0_5c677d444c", label), trf("将启动_0_服务_1f105846d4", label), okText = L["启动_ebd26da421"])
        }
        if (!okc) return@launch
        runDb(
            "/db/engine/service",
            jsonObjectOf("engine" to engineId, "action" to action),
            outputTitle = L["服务操作_143b5c61b3"],
        )
        reloadAfterService(st)
    }
}

/** 安装引擎（postgres/mysql 走 apt 白名单 /pkg/install，其余 /db/engine/install）。 */
fun installEngine(scope: CoroutineScope, st: DbState, engineId: String, onBusy: (String?) -> Unit) {
    scope.launch {
        val label = DbMeta.label(engineId)
        val okc = Modal.confirm(
            trfn("安装_label_0399b4e4de", "label" to label),
            trfn("在服务器上安装_label_可能需要数分钟_d_7bd49b35e9", "label" to label),
            okText = L["开始安装_d109e7cc01"],
        )
        if (!okc) return@launch
        onBusy(engineId)
        val pkg = when (engineId) {
            "postgres" -> "postgresql"
            "mysql" -> "mysql-server"
            else -> null
        }
        val r = if (pkg != null) {
            Actions.run("/pkg/install", jsonObjectOf("pkg" to pkg), title = trfn("label_安装日志_13ce1b1029", "label" to label))
        } else {
            Actions.run("/db/engine/install", jsonObjectOf("engine" to engineId), title = trfn("label_安装日志_13ce1b1029", "label" to label))
        }
        if (r != null) {
            AppState.toast(trfn("label_安装完成_正在刷新_7df73f0205", "label" to label), ToastKind.Ok, 6000)
            reloadAfterService(st)
        }
        onBusy(null)
    }
}

/* -------------------- 一键安装（无任何引擎 / 追加安装） -------------------- */

/** 复刻 renderInstallAll 的引擎卡片网格。 */
@Composable
fun InstallGrid(st: DbState, scope: CoroutineScope) {
    var busy by remember { mutableStateOf<String?>(null) }
    val installedIds = st.engines.map { it.id }.toSet()

    Column {
        DbMeta.INSTALL_TARGETS.chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { t ->
                    val hint = st.engineHints.firstOrNull { it.str("id") == t.engine }
                    val isInstalled = installedIds.contains(t.engine) || hint?.bool("installed") == true
                    Column(
                        Modifier.weight(1f)
                            .background(Palette.panel, RoundedCornerShape(3.dp))
                            .border(1.dp, Palette.border, RoundedCornerShape(3.dp))
                            .padding(12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.label, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            LevelBadge(if (isInstalled) L["已安装_eb88ff57c9"] else L["未安装_6f7dc945ae"], if (isInstalled) Level.Ok else Level.Muted)
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(t.hint, color = Palette.muted, fontSize = 11.sp)
                        Spacer(Modifier.size(8.dp))
                        DbBtn(
                            text = if (busy == t.engine) L["安装中_b2c6913616"] else trf("安装_0_710b3a3d30", t.label),
                            tone = if (isInstalled) DbBtnTone.Plain else DbBtnTone.Primary,
                            enabled = !isInstalled && busy == null,
                        ) { installEngine(scope, st, t.engine) { busy = it } }
                        if (busy == t.engine) Spacer(Modifier.size(6.dp))
                    }
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.size(10.dp))
        }
    }
}

@Composable
fun InstallAllSection(st: DbState, scope: CoroutineScope) {
    Column {
        PanelCardLocal(L["数据库引擎总览_8dc625b5ae"], L["未检测到任何可用的数据库引擎_可在此一键安装常见_38b395d313"])
        InstallGrid(st, scope)
    }
}

/** 轻量卡片（标题 + 说明，不折叠）。 */
@Composable
private fun PanelCardLocal(title: String, desc: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.panel, RoundedCornerShape(3.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(3.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(11.dp).background(AccentState.current.primary, RoundedCornerShape(1.dp)))
            Spacer(Modifier.width(9.dp))
            Text(title, color = Palette.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp)
        }
        Spacer(Modifier.size(8.dp))
        Text(desc, color = Palette.muted, fontSize = 12.sp)
    }
}

/* -------------------- 页签条 -------------------- */

@Composable
fun TabBar(st: DbState) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        st.tabs().forEach { (id, label) ->
            DbBtn(
                text = label,
                tone = if (st.tab == id) DbBtnTone.Primary else DbBtnTone.Plain,
            ) { st.tab = id }
        }
    }
}

/* -------------------- 错误卡（复刻 renderTab catch） -------------------- */

@Composable
fun EngineErrorCard(st: DbState, failure: DbFailure, scope: CoroutineScope) {
    val engine = st.engine ?: return
    val unit = DbMeta.serviceUnit(engine.id, engine.service)

    com.servermaintance.panel.ui.PanelCard(
        title = trf("0_无法加载_7b32254619", engine.name),
        cardKey = "db-error",
        collapsible = false,
        right = {
            LevelBadge(
                text = when {
                    failure.notInstalled -> L["未安装_6f7dc945ae"]
                    failure.notRunning -> L["未运行_b2ba9634d9"]
                    else -> L["错误_b859c7be75"]
                },
                level = if (failure.notInstalled) Level.Muted else Level.Bad,
            )
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Palette.inputElevated, RoundedCornerShape(3.dp))
                .border(1.dp, Palette.border, RoundedCornerShape(3.dp))
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            Text(failure.message, color = Palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (failure.notRunning && unit != null) {
                DbBtn(trf("启动_0_服务_16fc9bfc5e", engine.name), tone = DbBtnTone.Ok) {
                    engineService(scope, st, engine.id, "start")
                }
            }
            if (failure.notInstalled) {
                DbBtn(trf("安装_0_340ee35e2a", engine.name), tone = DbBtnTone.Primary) {
                    installEngine(scope, st, engine.id) { }
                }
            }
            DbBtn(L["重新加载_5982c44c18"]) { scope.launch { reloadAfterService(st) } }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = unit?.let { L["服务单元_it_安装_启动都是显式操作_默_902797720d"] }
                ?: L["该引擎无_systemd_服务_文件型数据库_13948e1a08"],
            color = Palette.muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/* -------------------- 页签加载容器（复刻 renderTab try/catch） -------------------- */

/**
 * 页签通用外壳：[loadKey] 变化（引擎/页签/库表/动作刷新）→ 重新 [load]；
 * 失败 → 与面板一致的「<引擎> 无法加载」错误卡。
 *
 * 调用方必须把 lambda 捕获的全部状态放进 [loadKey]（引擎 id、database、table、tick…）。
 */
@Composable
fun TabScaffold(
    st: DbState,
    loadKey: Any?,
    load: suspend () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var loading by remember(loadKey) { mutableStateOf(true) }
    var failure by remember(loadKey) { mutableStateOf<DbFailure?>(null) }

    androidx.compose.runtime.LaunchedEffect(loadKey) {
        loading = true
        failure = null
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = dbFailureOf(e)
        }
        loading = false
    }

    androidx.compose.foundation.layout.Column {
        when {
            loading -> LoadingState()
            failure != null -> EngineErrorCard(st, failure!!, rememberCoroutineScope())
            else -> content()   // content 是 ColumnScope 扩展，需在 Column 内调用
        }
    }
}

/* -------------------- 未实现页签占位 -------------------- */

@Composable
fun PendingTab(label: String) {
    com.servermaintance.panel.ui.PanelCard(title = label, cardKey = "pending", collapsible = false) {
        EmptyState(trfn("label_实现中_70505f7ef8", "label" to label))
    }
}
