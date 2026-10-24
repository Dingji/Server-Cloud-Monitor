package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.L

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.servermaintance.panel.pages.db.DbAdvancedTab
import com.servermaintance.panel.pages.db.DbBackupsTab
import com.servermaintance.panel.pages.db.DbConsoleTab
import com.servermaintance.panel.pages.db.DbDatabasesTab
import com.servermaintance.panel.pages.db.DbInsightsTab
import com.servermaintance.panel.pages.db.DbObjectsTab
import com.servermaintance.panel.pages.db.DbOverviewTab
import com.servermaintance.panel.pages.db.DbRolesTab
import com.servermaintance.panel.pages.db.DbState
import com.servermaintance.panel.pages.db.DbTablesTab
import com.servermaintance.panel.pages.db.DocCollectionsTab
import com.servermaintance.panel.pages.db.DocDatabasesTab
import com.servermaintance.panel.pages.db.DocOverviewTab
import com.servermaintance.panel.pages.db.EngineBar
import com.servermaintance.panel.pages.db.InstallAllSection
import com.servermaintance.panel.pages.db.KvKeysTab
import com.servermaintance.panel.pages.db.KvOverviewTab
import com.servermaintance.panel.pages.db.TabBar
import com.servermaintance.panel.pages.db.refreshEngines
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.PageScaffold
import kotlinx.coroutines.CoroutineScope

/**
 * 数据库 —— 6 引擎 × 9 页签的 DBA 控制台（复刻 `upstream/public/js/pages/databases.js`）。
 */
@Composable
fun DatabasesPage() {
    val st = remember { DbState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            refreshEngines(st)
        } finally {
            st.enginesLoading = false
        }
    }

    PageScaffold(
        title = L["数据库_f4dbbc63a5"],
        desc = L["postgresql_mysql_sql_42e513fca9"],
    ) {
        when {
            st.enginesLoading -> LoadingState()
            st.engines.isEmpty() -> InstallAllSection(st, scope)
            else -> {
                EngineBar(st, scope)
                TabBar(st)
                DbTabBody(st, scope)
            }
        }
    }
}

@Composable
private fun DbTabBody(st: DbState, scope: CoroutineScope) {
    when (st.tab) {
        "overview" -> when (st.kind) {
            "kv" -> KvOverviewTab(st, scope)
            "doc" -> DocOverviewTab(st, scope)
            else -> DbOverviewTab(st, scope)
        }
        "databases" -> if (st.kind == "doc") DocDatabasesTab(st, scope) else DbDatabasesTab(st, scope)
        "tables" -> if (st.kind == "doc") DocCollectionsTab(st, scope) else DbTablesTab(st, scope)
        "keys" -> KvKeysTab(st, scope)
        "console" -> DbConsoleTab(st, scope)
        "insights" -> DbInsightsTab(st, scope)
        "objects" -> DbObjectsTab(st, scope)
        "roles" -> DbRolesTab(st, scope)
        "backups" -> DbBackupsTab(st, scope)
        "advanced" -> DbAdvancedTab(st, scope)
        else -> DbOverviewTab(st, scope)
    }
}
