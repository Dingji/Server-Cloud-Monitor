package com.servermaintance.panel.pages.db
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.CheckRow
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun DbRolesTab(st: DbState, scope: CoroutineScope) {
    when {
        st.isSqlite -> PanelCard(title = L["用户与权限_5c0dbf51f4"], cardKey = "rl-sqlite", collapsible = false) {
            Text(L["sqlite_是单文件嵌入式数据库_没有用户_角_0d7e8e26a3"], style = dbMuted, fontSize = 12.sp)
        }
        st.isDb2 -> Db2Roles(st, scope)
        st.kind == "kv" -> RedisAcl(st, scope)
        st.kind == "doc" -> MongoRoles(st, scope)
        else -> SqlRoles(st, scope)
    }
}

@Composable
private fun SqlRoles(st: DbState, scope: CoroutineScope) {
    var payload by remember(st.engineId, st.tick) { mutableStateOf<JsonObject?>(null) }
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("localhost") }
    var pw by remember { mutableStateOf("") }
    var superU by remember { mutableStateOf(false) }

    TabScaffold(
        st = st,
        loadKey = "rl|${st.engineId}|${st.tick}",
        load = {
            payload = AppState.api.get("/db/roles?engine=${st.engineId}") as? JsonObject
            dbs = runCatching { AppState.api.get("/db/databases?engine=${st.engineId}", 10_000) }.getOrNull().asObjList()
        },
    ) {
        val roles = payload?.let { it["roles"] }.asObjList()
        val grants = (payload?.get("grants") as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() } ?: emptyList()
        val privs = payload?.let { it["privileges"] }.asObjList()
        val dbNames = dbs.map { it.str("name") }.filter { it.isNotEmpty() }

        PanelCard(title = L["创建用户_角色_87bba5f559"], cardKey = "rl-create") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Field(name, { name = it }, Modifier.width(160.dp), label = L["名称_1be7ae4fc2"], hint = "appuser", mono = true)
                if (st.isMy) Field(host, { host = it }, Modifier.width(140.dp), label = L["host_3960ec4ca5"], mono = true)
                Field(pw, { pw = it }, Modifier.width(160.dp), label = L["密码_c839a8ff17"], hint = L["留空则无密码_25bc684b53"], password = true)
                CheckRow(superU, { superU = it }, L["超级用户_b429b65128"])
                DbBtn(L["创建_fcbd093292"], tone = DbBtnTone.Primary) {
                    val n = name.trim()
                    if (n.isEmpty()) AppState.toast(tr("请输入名称_c2afb255a5"), ToastKind.Warn)
                    else scope.launch {
                        val okc = Modal.confirm(L["创建角色_24cf247879"], trf("创建_0_b962ace170", n) + if (superU) L["超级用户_cf4469a6db"] else "")
                        if (!okc) return@launch
                        runDb(
                            "/db/role",
                            jsonObjectOf(
                                "engine" to st.engineId, "action" to "create", "name" to n,
                                "password" to pw, "host" to host,
                                "privileges" to jsonStrings(if (superU) listOf("superuser") else emptyList()),
                            ),
                            reload = { st.bump() },
                        )
                    }
                }
            }
        }

        PanelCard(title = trf("角色列表_0_6ea3fcc772", roles.size), cardKey = "rl-list", right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } }) {
            DynamicTable(
                columns = buildList {
                    add("name")
                    if (st.isMy) { add("host"); add("has_password") }
                    if (st.isPg) { add("super"); add("can_login"); add("member_of") } else add("super")
                },
                rows = roles,
                maxHeight = 320,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 2.4f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["改密_bb974d3064"]) { changePw(scope, st, n) }
                        DbBtn(L["授权库_4d0119c773"], tone = DbBtnTone.Ok) { grantDb(scope, st, n, dbNames) }
                        DbBtn(L["表级授权_dce69aab71"]) { grantTable(scope, st, n, dbNames) }
                        DbBtn(if (st.isPg) L["属性_7d3c35c29b"] else L["账户_c3d92b20c8"]) { alterRole(scope, st, n) }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) { dropRole(scope, st, n) }
                    }
                },
            )
        }
        if (grants.isNotEmpty()) {
            PanelCard(title = L["当前用户权限_show_grants_e8e7d7d1e2"], cardKey = "rl-grants") {
                PreBox(grants.joinToString("\n"), maxHeight = 220)
            }
        }
        if (privs.isNotEmpty()) {
            PanelCard(title = L["表级授权概览_ae18b3f8df"], cardKey = "rl-privs") {
                DynamicTable(listOf("grantee", "schema", "privilege"), privs, maxHeight = 280)
            }
        }
    }
}

private fun changePw(scope: CoroutineScope, st: DbState, name: String) {
    scope.launch {
        val r = Modal.prompt(trf("修改密码_0_1a306d5990", name), listOf(FormField.Password("password", L["新密码_d22c9c0085"])), okText = L["修改_c9c77517fe"]) ?: return@launch
        val pw = r["password"].orEmpty()
        if (pw.isEmpty()) return@launch
        runDb("/db/role", jsonObjectOf("engine" to st.engineId, "action" to "password", "name" to name, "password" to pw))
    }
}

private fun grantDb(scope: CoroutineScope, st: DbState, name: String, dbs: List<String>) {
    scope.launch {
        if (dbs.isEmpty()) return@launch
        val r = Modal.prompt(L["授予数据库权限_fa513839dd"], listOf(FormField.Select("database", L["nav.databases"], dbs, dbs.first())), okText = L["授权_3a6e607f0c"]) ?: return@launch
        runDb(
            "/db/role",
            jsonObjectOf("engine" to st.engineId, "action" to "grant-db", "name" to name, "database" to r["database"]),
            reload = { st.bump() },
        )
    }
}

private fun grantTable(scope: CoroutineScope, st: DbState, name: String, dbs: List<String>) {
    scope.launch {
        if (dbs.isEmpty()) return@launch
        val r1 = Modal.prompt(
            trf("表级授权_0_0060f41bc1", name),
            listOf(FormField.Select("database", L["nav.databases"], dbs, st.database ?: dbs.first())),
            okText = L["下一步_ea0ef2ae72"],
        ) ?: return@launch
        val database = r1["database"].orEmpty()
        val tables = runCatching { AppState.api.get("/db/tables?engine=${st.engineId}&database=${q(database)}") }
            .getOrNull().asObjList().map { it.str("schema") + "." + it.str("name") }
        val r2 = Modal.prompt(
            L["选择表与权限_3c404e93dc"],
            listOf(
                FormField.Select("table", L["表_6a954390e3"], tables.ifEmpty { listOf("") }, tables.firstOrNull().orEmpty()),
                FormField.Select("privs", L["权限_560165a6d7"], listOf("SELECT", L["select_insert_067c776f68"], L["select_insert_update_del_2405844409"], "ALL"), "SELECT"),
                FormField.Select("op", L["操作_f3ea6d345e"], listOf(L["授予_afc60b6b80"], L["回收_6eda6254dd"])),
            ),
            okText = L["执行_28febba225"],
        ) ?: return@launch
        val table = r2["table"].orEmpty()
        if (table.isEmpty()) return@launch
        val privileges = r2["privs"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        runDb(
            "/db/role",
            jsonObjectOf(
                "engine" to st.engineId,
                "action" to if (r2["op"] == L["授予_afc60b6b80"]) "grant-table" else "revoke-table",
                "name" to name,
                "database" to database,
                "params" to jsonObjectOf("table" to table, "privileges" to jsonStrings(privileges)),
            ),
        )
    }
}

private fun alterRole(scope: CoroutineScope, st: DbState, name: String) {
    scope.launch {
        if (st.isPg) {
            val r = Modal.prompt(
                trf("角色属性_0_2879e4b5a3", name),
                listOf(
                    FormField.Select("login", L["login_可登录_0e617ada9b"], listOf(L["不变_46b24ed86c"], "LOGIN", "NOLOGIN")),
                    FormField.Select("superuser", "SUPERUSER", listOf(L["不变_46b24ed86c"], "SUPERUSER", "NOSUPERUSER")),
                    FormField.Select("createdb", "CREATEDB", listOf(L["不变_46b24ed86c"], "CREATEDB", "NOCREATEDB")),
                    FormField.Select("createrole", "CREATEROLE", listOf(L["不变_46b24ed86c"], "CREATEROLE", "NOCREATEROLE")),
                    FormField.Text("connlimit", L["连接数限制_空_不变_1_无限_93c52a151b"]),
                    FormField.Text("validUntil", L["密码有效期_如_2030_01_01_空_不变_4b3bee9f12"]),
                ),
            ) ?: return@launch
            val privileges = buildList {
                if (r["login"] == "LOGIN") add("login") else if (r["login"] == "NOLOGIN") add("nologin")
                if (r["superuser"] == "SUPERUSER") add("superuser") else if (r["superuser"] == "NOSUPERUSER") add("nosuperuser")
                if (r["createdb"] == "CREATEDB") add("createdb") else if (r["createdb"] == "NOCREATEDB") add("nocreatedb")
                if (r["createrole"] == "CREATEROLE") add("createrole") else if (r["createrole"] == "NOCREATEROLE") add("nocreaterole")
            }
            runDb(
                "/db/role",
                jsonObjectOf(
                    "engine" to "postgres", "action" to "alter", "name" to name,
                    "privileges" to jsonStrings(privileges),
                    "params" to jsonObjectOf("connlimit" to r["connlimit"], "validUntil" to r["validUntil"]),
                ),
                reload = { st.bump() },
            )
        } else {
            val r = Modal.prompt(
                trf("账户管理_0_3d8c9ea2f9", name),
                listOf(
                    FormField.Select(
                        "op",
                        L["操作_f3ea6d345e"],
                        listOf(
                            L["account_lock_锁定_bbeaf242af"],
                            L["account_unlock_解锁_b001d58ec0"],
                            L["password_expire_强制改密_03ed6aea19"],
                            L["显示权限清单_edc6d375d3"],
                        ),
                        L["account_lock_锁定_bbeaf242af"],
                    ),
                ),
                danger = true,
                okText = L["执行_28febba225"],
            ) ?: return@launch
            val op = r["op"].orEmpty()
            if (op == L["显示权限清单_edc6d375d3"]) {
                val res = runDb("/db/role", jsonObjectOf("engine" to "mysql", "action" to "grants-list", "name" to name), outputTitle = trf("权限清单_0_14c573d55b", name), toast = false)
                if (res != null) Modal.output(trf("权限清单_0_14c573d55b", name), res.str("output"))
                return@launch
            }
            val act = when {
                op.startsWith("ACCOUNT LOCK") -> "lock"
                op.startsWith("ACCOUNT UNLOCK") -> "unlock"
                else -> "expire"
            }
            runDb("/db/role", jsonObjectOf("engine" to "mysql", "action" to act, "name" to name))
        }
    }
}

private fun dropRole(scope: CoroutineScope, st: DbState, name: String) {
    scope.launch {
        val okc = Modal.confirm(L["删除角色_89dab40d48"], trf("删除_0_该角色拥有的对象可能变为无主_81db941aef", name), okText = L["删除_3755f56f2f"], danger = true)
        if (!okc) return@launch
        runDb("/db/role", jsonObjectOf("engine" to st.engineId, "action" to "drop", "name" to name), reload = { st.bump() })
    }
}

@Composable
private fun Db2Roles(st: DbState, scope: CoroutineScope) {
    var payload by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    var user by remember { mutableStateOf("") }
    var scopeSel by remember { mutableStateOf(L["数据库级_6e3f81b153"]) }
    var auth by remember { mutableStateOf("DBADM") }
    var privs by remember { mutableStateOf("SELECT") }
    var table by remember { mutableStateOf("") }
    var op by remember { mutableStateOf(L["授予_afc60b6b80"]) }

    TabScaffold(
        st = st,
        loadKey = "db2rl|${st.tick}",
        load = {
            payload = runCatching { AppState.api.get("/db/roles?engine=db2") }.getOrNull() as? JsonObject
        },
    ) {
        val roles = payload?.let { it["roles"] }.asObjList()
        PanelCard(title = L["db2_授权_grant_revoke_eb50e06ee0"], cardKey = "db2-grant") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Field(user, { user = it }, Modifier.width(150.dp), label = L["用户_组_1e6a976270"], hint = "APPUSER", mono = true)
                SelectField(scopeSel, listOf(L["数据库级_6e3f81b153"], L["表级_d0102a7431"]), { scopeSel = it }, Modifier.width(120.dp), label = L["作用范围_95a5e61424"])
                SelectField(auth, listOf("DBADM", "DATAACCESS", "ACCESSCTRL", "SECADM", "CONNECT"), { auth = it }, Modifier.width(140.dp), label = L["权限_560165a6d7"])
                SelectField(privs, listOf("SELECT", L["select_insert_067c776f68"], L["select_insert_update_del_2405844409"], "ALL"), { privs = it }, Modifier.width(220.dp), label = L["表级权限_d2b9ff43fe"])
                Field(table, { table = it }, Modifier.width(170.dp), label = L["表_schema_table_a84ffba1ae"], hint = L["app_tbl_45e7d8e021"], mono = true)
                SelectField(op, listOf(L["授予_afc60b6b80"], L["回收_6eda6254dd"]), { op = it }, Modifier.width(90.dp), label = L["操作_f3ea6d345e"])
                DbBtn(L["执行_28febba225"], tone = DbBtnTone.Primary) {
                    val n = user.trim()
                    if (n.isEmpty()) AppState.toast(tr("请输入用户_组名_2b012efd38"), ToastKind.Warn)
                    else if (scopeSel == tr("表级_d0102a7431") && table.trim().isEmpty()) AppState.toast(tr("表级操作需填写_schema_table_a22a79af68"), ToastKind.Warn)
                    else scope.launch {
                        val action = (if (op == L["授予_afc60b6b80"]) "grant" else "revoke") + if (scopeSel == L["表级_d0102a7431"]) L["table_ee2eebe47d"] else L["db_01cac9a55e"]
                        runDb(
                            "/db/role",
                            jsonObjectOf(
                                "engine" to "db2", "action" to action, "name" to n, "privilege" to auth,
                                "params" to jsonObjectOf(
                                    "table" to table.trim(),
                                    "privileges" to jsonStrings(privs.split(",").map { it.trim() }.filter { it.isNotEmpty() }),
                                ),
                            ),
                            reload = { st.bump() },
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(L["db2_用_grant_revoke_管理权限_209a33078b"], style = dbMuted, fontSize = 11.sp)
        }
        PanelCard(title = trf("用户_权限_0_467c74d975", roles.size), cardKey = "db2-users", right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } }) {
            DynamicTable(listOf("name", "dbadm", "data_access", "access_ctrl"), roles, maxHeight = 280)
            val un = payload?.str("unsupported").orEmpty()
            if (un.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Text(un, style = dbMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RedisAcl(st: DbState, scope: CoroutineScope) {
    var payload by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    TabScaffold(
        st = st,
        loadKey = "acl|${st.tick}",
        load = { payload = runCatching { AppState.api.get("/db/roles?engine=redis") }.getOrNull() as? JsonObject },
    ) {
        val roles = payload?.let { it["roles"] }.asObjList()
        PanelCard(title = L["创建_acl_用户_cd96ef0588"], cardKey = "acl-create") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Field(name, { name = it }, Modifier.width(150.dp), label = L["用户名_a1aaf352cb"], hint = "appuser", mono = true)
                Field(pw, { pw = it }, Modifier.width(170.dp), label = L["密码_c839a8ff17"], password = true)
                DbBtn(L["创建_fcbd093292"], tone = DbBtnTone.Primary) {
                    val n = name.trim()
                    if (n.isEmpty()) AppState.toast(tr("请输入用户名_ad5f05f1cf"), ToastKind.Warn)
                    else scope.launch {
                        runDb(
                            "/db/role",
                            jsonObjectOf("engine" to "redis", "action" to "create", "name" to n, "password" to pw),
                            reload = { st.bump() },
                        )
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
            Text(L["创建的用户拥有_all_全部键_全_2da1a3462f"], style = dbMuted, fontSize = 11.sp)
        }
        PanelCard(title = trf("acl_用户_0_b7e2c3ae45", roles.size), cardKey = "acl-list", right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } }) {
            DynamicTable(
                columns = listOf("name", "rule"),
                rows = roles,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["改密_bb974d3064"]) { changePw(scope, st, n) }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) { dropRole(scope, st, n) }
                    }
                },
            )
        }
    }
}

@Composable
private fun MongoRoles(st: DbState, scope: CoroutineScope) {
    var payload by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var dbn by remember { mutableStateOf("admin") }
    var rolesStr by remember { mutableStateOf("readWrite") }
    TabScaffold(
        st = st,
        loadKey = "morl|${st.tick}",
        load = { payload = runCatching { AppState.api.get("/db/roles?engine=mongo") }.getOrNull() as? JsonObject },
    ) {
        val roles = payload?.let { it["roles"] }.asObjList()
        PanelCard(title = L["创建用户_315315d695"], cardKey = "mo-user") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Field(name, { name = it }, Modifier.width(150.dp), label = L["用户名_a1aaf352cb"], mono = true)
                Field(pw, { pw = it }, Modifier.width(170.dp), label = L["密码_c839a8ff17"], password = true)
                Field(dbn, { dbn = it }, Modifier.width(110.dp), label = L["库_87eedee459"], mono = true)
                Field(rolesStr, { rolesStr = it }, Modifier.width(180.dp), label = L["角色_6b26695e4d"], hint = L["readwrite_dbowner_c79887aba1"], mono = true)
                DbBtn(L["创建_fcbd093292"], tone = DbBtnTone.Primary) {
                    val n = name.trim()
                    if (n.isEmpty()) AppState.toast(tr("请输入用户名_ad5f05f1cf"), ToastKind.Warn)
                    else scope.launch {
                        runDb(
                            "/db/role",
                            jsonObjectOf("engine" to "mongo", "action" to "create", "name" to n, "password" to pw, "database" to dbn.trim(), "roles" to rolesStr),
                            reload = { st.bump() },
                        )
                    }
                }
            }
        }
        PanelCard(title = trf("用户列表_0_06a9e65b3d", roles.size), cardKey = "mo-users", right = { DbBtn(L["刷新_38108eaa1d"]) { st.bump() } }) {
            DynamicTable(
                columns = listOf("name", "roles"),
                rows = roles,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["改密_bb974d3064"]) {
                            scope.launch {
                                val r = Modal.prompt(trf("修改密码_0_1a306d5990", n), listOf(FormField.Password("password", L["新密码_d22c9c0085"])), okText = L["修改_c9c77517fe"]) ?: return@launch
                                runDb("/db/role", jsonObjectOf("engine" to "mongo", "action" to "password", "name" to n, "password" to r["password"], "database" to "admin"), reload = { st.bump() })
                            }
                        }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm(L["删除用户_bcfca951a1"], trf("删除_0_a1ee4b1af0", n), okText = L["删除_3755f56f2f"], danger = true)
                                if (!okc) return@launch
                                runDb("/db/role", jsonObjectOf("engine" to "mongo", "action" to "drop", "name" to n, "database" to "admin"), reload = { st.bump() })
                            }
                        }
                    }
                },
            )
        }
    }
}

