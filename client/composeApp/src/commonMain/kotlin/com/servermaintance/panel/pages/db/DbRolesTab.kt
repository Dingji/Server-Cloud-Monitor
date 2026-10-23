package com.servermaintance.panel.pages.db

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
        st.isSqlite -> PanelCard(title = "用户与权限", cardKey = "rl-sqlite", collapsible = false) {
            Text("SQLite 是单文件嵌入式数据库，没有用户/角色概念，访问控制由操作系统的文件权限决定。", style = dbMuted, fontSize = 12.sp)
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

        PanelCard(title = "创建用户/角色", cardKey = "rl-create") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Field(name, { name = it }, Modifier.width(160.dp), label = "名称", hint = "appuser", mono = true)
                if (st.isMy) Field(host, { host = it }, Modifier.width(140.dp), label = "Host", mono = true)
                Field(pw, { pw = it }, Modifier.width(160.dp), label = "密码", hint = "留空则无密码", password = true)
                CheckRow(superU, { superU = it }, "超级用户")
                DbBtn("创建", tone = DbBtnTone.Primary) {
                    val n = name.trim()
                    if (n.isEmpty()) AppState.toast("请输入名称", ToastKind.Warn)
                    else scope.launch {
                        val okc = Modal.confirm("创建角色", n + if (superU) " （超级用户）" else "")
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

        PanelCard(title = "角色列表 (${roles.size})", cardKey = "rl-list", right = { DbBtn("刷新") { st.bump() } }) {
            DynamicTable(
                columns = buildList {
                    add("name")
                    if (st.isMy) { add("host"); add("has_password") }
                    if (st.isPg) { add("super"); add("can_login"); add("member_of") } else add("super")
                },
                rows = roles,
                maxHeight = 320,
                extra = TableCol("操作", weight = 2.4f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("改密") { changePw(scope, st, n) }
                        DbBtn("授权库", tone = DbBtnTone.Ok) { grantDb(scope, st, n, dbNames) }
                        DbBtn("表级授权") { grantTable(scope, st, n, dbNames) }
                        DbBtn(if (st.isPg) "属性" else "账户") { alterRole(scope, st, n) }
                        DbBtn("删除", tone = DbBtnTone.Danger) { dropRole(scope, st, n) }
                    }
                },
            )
        }
        if (grants.isNotEmpty()) {
            PanelCard(title = "当前用户权限 (SHOW GRANTS)", cardKey = "rl-grants") {
                PreBox(grants.joinToString("\n"), maxHeight = 220)
            }
        }
        if (privs.isNotEmpty()) {
            PanelCard(title = "表级授权概览", cardKey = "rl-privs") {
                DynamicTable(listOf("grantee", "schema", "privilege"), privs, maxHeight = 280)
            }
        }
    }
}

private fun changePw(scope: CoroutineScope, st: DbState, name: String) {
    scope.launch {
        val r = Modal.prompt("修改密码 - $name", listOf(FormField.Password("password", "新密码")), okText = "修改") ?: return@launch
        val pw = r["password"].orEmpty()
        if (pw.isEmpty()) return@launch
        runDb("/db/role", jsonObjectOf("engine" to st.engineId, "action" to "password", "name" to name, "password" to pw))
    }
}

private fun grantDb(scope: CoroutineScope, st: DbState, name: String, dbs: List<String>) {
    scope.launch {
        if (dbs.isEmpty()) return@launch
        val r = Modal.prompt("授予数据库权限", listOf(FormField.Select("database", "数据库", dbs, dbs.first())), okText = "授权") ?: return@launch
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
            "表级授权 - $name",
            listOf(FormField.Select("database", "数据库", dbs, st.database ?: dbs.first())),
            okText = "下一步",
        ) ?: return@launch
        val database = r1["database"].orEmpty()
        val tables = runCatching { AppState.api.get("/db/tables?engine=${st.engineId}&database=${q(database)}") }
            .getOrNull().asObjList().map { it.str("schema") + "." + it.str("name") }
        val r2 = Modal.prompt(
            "选择表与权限",
            listOf(
                FormField.Select("table", "表", tables.ifEmpty { listOf("") }, tables.firstOrNull().orEmpty()),
                FormField.Select("privs", "权限", listOf("SELECT", "SELECT,INSERT", "SELECT,INSERT,UPDATE,DELETE", "ALL"), "SELECT"),
                FormField.Select("op", "操作", listOf("授予", "回收"), "授予"),
            ),
            okText = "执行",
        ) ?: return@launch
        val table = r2["table"].orEmpty()
        if (table.isEmpty()) return@launch
        val privileges = r2["privs"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        runDb(
            "/db/role",
            jsonObjectOf(
                "engine" to st.engineId,
                "action" to if (r2["op"] == "授予") "grant-table" else "revoke-table",
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
                "角色属性 - $name",
                listOf(
                    FormField.Select("login", "LOGIN（可登录）", listOf("不变", "LOGIN", "NOLOGIN"), "不变"),
                    FormField.Select("superuser", "SUPERUSER", listOf("不变", "SUPERUSER", "NOSUPERUSER"), "不变"),
                    FormField.Select("createdb", "CREATEDB", listOf("不变", "CREATEDB", "NOCREATEDB"), "不变"),
                    FormField.Select("createrole", "CREATEROLE", listOf("不变", "CREATEROLE", "NOCREATEROLE"), "不变"),
                    FormField.Text("connlimit", "连接数限制（空=不变，-1=无限）"),
                    FormField.Text("validUntil", "密码有效期 (如 2030-01-01，空=不变)"),
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
                "账户管理 - $name",
                listOf(FormField.Select("op", "操作", listOf("ACCOUNT LOCK(锁定)", "ACCOUNT UNLOCK(解锁)", "PASSWORD EXPIRE(强制改密)", "显示权限清单"), "ACCOUNT LOCK(锁定)")),
                danger = true,
                okText = "执行",
            ) ?: return@launch
            val op = r["op"].orEmpty()
            if (op.startsWith("显示")) {
                val res = runDb("/db/role", jsonObjectOf("engine" to "mysql", "action" to "grants-list", "name" to name), outputTitle = "权限清单 - $name", toast = false)
                if (res != null) Modal.output("权限清单 - $name", res.str("output"))
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
        val okc = Modal.confirm("删除角色", "删除 $name ？该角色拥有的对象可能变为无主。", okText = "删除", danger = true)
        if (!okc) return@launch
        runDb("/db/role", jsonObjectOf("engine" to st.engineId, "action" to "drop", "name" to name), reload = { st.bump() })
    }
}

@Composable
private fun Db2Roles(st: DbState, scope: CoroutineScope) {
    var payload by remember(st.tick) { mutableStateOf<JsonObject?>(null) }
    var user by remember { mutableStateOf("") }
    var scopeSel by remember { mutableStateOf("数据库级") }
    var auth by remember { mutableStateOf("DBADM") }
    var privs by remember { mutableStateOf("SELECT") }
    var table by remember { mutableStateOf("") }
    var op by remember { mutableStateOf("授予") }

    TabScaffold(
        st = st,
        loadKey = "db2rl|${st.tick}",
        load = {
            payload = runCatching { AppState.api.get("/db/roles?engine=db2") }.getOrNull() as? JsonObject
        },
    ) {
        val roles = payload?.let { it["roles"] }.asObjList()
        PanelCard(title = "Db2 授权（GRANT / REVOKE）", cardKey = "db2-grant") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Field(user, { user = it }, Modifier.width(150.dp), label = "用户/组", hint = "APPUSER", mono = true)
                SelectField(scopeSel, listOf("数据库级", "表级"), { scopeSel = it }, Modifier.width(120.dp), label = "作用范围")
                SelectField(auth, listOf("DBADM", "DATAACCESS", "ACCESSCTRL", "SECADM", "CONNECT"), { auth = it }, Modifier.width(140.dp), label = "权限")
                SelectField(privs, listOf("SELECT", "SELECT,INSERT", "SELECT,INSERT,UPDATE,DELETE", "ALL"), { privs = it }, Modifier.width(220.dp), label = "表级权限")
                Field(table, { table = it }, Modifier.width(170.dp), label = "表 (SCHEMA.TABLE)", hint = "APP.TBL", mono = true)
                SelectField(op, listOf("授予", "回收"), { op = it }, Modifier.width(90.dp), label = "操作")
                DbBtn("执行", tone = DbBtnTone.Primary) {
                    val n = user.trim()
                    if (n.isEmpty()) AppState.toast("请输入用户/组名", ToastKind.Warn)
                    else if (scopeSel == "表级" && table.trim().isEmpty()) AppState.toast("表级操作需填写 SCHEMA.TABLE", ToastKind.Warn)
                    else scope.launch {
                        val action = (if (op == "授予") "grant" else "revoke") + if (scopeSel == "表级") "-table" else "-db"
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
            Text("Db2 用 GRANT/REVOKE 管理权限；用户本身需先在实例上创建（db2 CREATE USER 或操作系统账号）。", style = dbMuted, fontSize = 11.sp)
        }
        PanelCard(title = "用户 / 权限 (${roles.size})", cardKey = "db2-users", right = { DbBtn("刷新") { st.bump() } }) {
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
        PanelCard(title = "创建 ACL 用户", cardKey = "acl-create") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Field(name, { name = it }, Modifier.width(150.dp), label = "用户名", hint = "appuser", mono = true)
                Field(pw, { pw = it }, Modifier.width(170.dp), label = "密码", password = true)
                DbBtn("创建", tone = DbBtnTone.Primary) {
                    val n = name.trim()
                    if (n.isEmpty()) AppState.toast("请输入用户名", ToastKind.Warn)
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
            Text("创建的用户拥有 ~* +@all（全部键 + 全部命令），可到 ACL SETUSER 细化授权。", style = dbMuted, fontSize = 11.sp)
        }
        PanelCard(title = "ACL 用户 (${roles.size})", cardKey = "acl-list", right = { DbBtn("刷新") { st.bump() } }) {
            DynamicTable(
                columns = listOf("name", "rule"),
                rows = roles,
                extra = TableCol("操作", weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("改密") { changePw(scope, st, n) }
                        DbBtn("删除", tone = DbBtnTone.Danger) { dropRole(scope, st, n) }
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
        PanelCard(title = "创建用户", cardKey = "mo-user") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                Field(name, { name = it }, Modifier.width(150.dp), label = "用户名", mono = true)
                Field(pw, { pw = it }, Modifier.width(170.dp), label = "密码", password = true)
                Field(dbn, { dbn = it }, Modifier.width(110.dp), label = "库", mono = true)
                Field(rolesStr, { rolesStr = it }, Modifier.width(180.dp), label = "角色", hint = "readWrite, dbOwner", mono = true)
                DbBtn("创建", tone = DbBtnTone.Primary) {
                    val n = name.trim()
                    if (n.isEmpty()) AppState.toast("请输入用户名", ToastKind.Warn)
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
        PanelCard(title = "用户列表 (${roles.size})", cardKey = "mo-users", right = { DbBtn("刷新") { st.bump() } }) {
            DynamicTable(
                columns = listOf("name", "roles"),
                rows = roles,
                extra = TableCol("操作", weight = 1.2f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("改密") {
                            scope.launch {
                                val r = Modal.prompt("修改密码 - $n", listOf(FormField.Password("password", "新密码")), okText = "修改") ?: return@launch
                                runDb("/db/role", jsonObjectOf("engine" to "mongo", "action" to "password", "name" to n, "password" to r["password"], "database" to "admin"), reload = { st.bump() })
                            }
                        }
                        DbBtn("删除", tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm("删除用户", "删除 $n ？", okText = "删除", danger = true)
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

