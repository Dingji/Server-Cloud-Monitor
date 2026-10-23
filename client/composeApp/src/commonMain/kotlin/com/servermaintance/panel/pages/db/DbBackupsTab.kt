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
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.Field
import com.servermaintance.panel.ui.FormField
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.SelectField
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun DbBackupsTab(st: DbState, scope: CoroutineScope) {
    var bks by remember(st.engineId, st.tick) { mutableStateOf<JsonObject?>(null) }
    var dbs by remember(st.engineId, st.tick) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var dbSel by remember(st.engineId, st.database) { mutableStateOf(st.database ?: "ALL") }
    var mode by remember { mutableStateOf("full") }
    var format by remember { mutableStateOf("plain") }
    var exclude by remember { mutableStateOf("") }
    var sqlPaste by remember { mutableStateOf("") }
    var sqlName by remember { mutableStateOf("upload.sql") }

    TabScaffold(
        st = st,
        loadKey = "bk|${st.engineId}|${st.tick}",
        load = {
            coroutineScope {
                val a = async { AppState.api.get("/db/backups?engine=${st.engineId}") }
                val b = async { runCatching { AppState.api.get("/db/databases?engine=${st.engineId}", 10_000) }.getOrNull() }
                bks = a.await() as? JsonObject
                dbs = b.await().asObjList().filter { !it.flag("is_template") }
            }
        },
    ) {
        val items = bks?.let { it["items"] }.asObjList()
        val dir = bks?.str("dir").orEmpty()
        val kind = st.kind
        val dbOptions = listOf("ALL") + dbs.map { it.str("name") }
        val allLabel = if (kind == "kv") "当前库 (db0-db15) 全量 RDB" else "全部数据库（服务器级全量）"

        PanelCard(
            title = "新建备份 / 导出",
            cardKey = "bk-new",
            right = {
                DbBtn("开始备份", tone = DbBtnTone.Primary) {
                    scope.launch {
                        val database = dbSel
                        val modeLabel = mapOf("full" to "完整", "schema" to "仅结构", "data" to "仅数据")[mode] ?: mode
                        val fmtLabel = when {
                            kind == "kv" -> "RDB 快照"
                            kind == "doc" -> "gzip 归档"
                            format == "custom" -> "PG 自定义格式"
                            else -> "SQL + gzip"
                        }
                        val okc = Modal.confirm("创建备份", "备份 $database（$modeLabel / $fmtLabel）？大库可能需要数分钟。", okText = "开始备份")
                        if (!okc) return@launch
                        val r = runDb(
                            "/db/backup",
                            jsonObjectOf(
                                "engine" to st.engineId, "database" to database,
                                "format" to format, "mode" to mode, "exclude" to exclude.trim().ifEmpty { null },
                            ),
                            toast = false,
                        )
                        if (r != null) {
                            AppState.toast(r.str("output"), ToastKind.Ok, 6000)
                            st.bump()
                        }
                    }
                }
            },
        ) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                SelectField(
                    value = if (dbSel == "ALL") allLabel else dbSel,
                    options = listOf(allLabel) + dbs.map { "${it.str("name")} (${it.str("size")})" },
                    onValueChange = { v ->
                        dbSel = if (v == allLabel) "ALL" else dbs.firstOrNull { v.startsWith(it.str("name")) }?.str("name") ?: "ALL"
                    },
                    modifier = Modifier.width(260.dp),
                    label = "数据库",
                )
                if (kind == "sql") {
                    SelectField(
                        value = when (mode) {
                            "schema" -> "仅结构（不含数据）"
                            "data" -> "仅数据（不含建表）"
                            else -> "完整（结构 + 数据）"
                        },
                        options = listOf("完整（结构 + 数据）", "仅结构（不含数据）", "仅数据（不含建表）"),
                        onValueChange = {
                            mode = when {
                                it.startsWith("仅结构") -> "schema"
                                it.startsWith("仅数据") -> "data"
                                else -> "full"
                            }
                        },
                        modifier = Modifier.width(200.dp),
                        label = "内容",
                    )
                    val fmtOpts = buildList {
                        add("SQL 文本 + gzip（通用，可编辑）")
                        if (st.isPg) add("PostgreSQL 自定义格式 -Fc（更快恢复）")
                    }
                    SelectField(
                        value = if (format == "custom") fmtOpts.getOrElse(1) { fmtOpts.first() } else fmtOpts.first(),
                        options = fmtOpts,
                        onValueChange = { format = if (it.contains("自定义")) "custom" else "plain" },
                        modifier = Modifier.width(260.dp),
                        label = "格式",
                    )
                    Field(exclude, { exclude = it }, Modifier.width(220.dp), label = "排除表（逗号分隔，可选）", hint = "logs, tmp_table", mono = true)
                }
                DbBtn("每日自动备份") { addDailyCron(scope, st) }
            }
            Spacer(Modifier.size(8.dp))
            val extra = when {
                kind == "kv" -> "· Redis 会执行 BGSAVE 并复制 RDB 文件；恢复会覆盖数据目录，需重启 Redis 生效。"
                kind == "doc" -> "· MongoDB 使用 mongodump 生成 gzip 归档（需 mongodump）。"
                st.isSqlite -> "· SQLite 生成 SQL 文本 + gzip，可直接恢复到新文件。"
                else -> ""
            }
            Text("备份保存在服务器 ${dir.ifEmpty { "(未知目录)" }}，可直接下载。$extra", style = dbMuted, fontSize = 11.sp)
        }

        PanelCard(title = "导入 SQL 文件（用于恢复）", cardKey = "bk-up") {
            val hint = when (kind) {
                "doc" -> "可粘贴 mongodump 归档内容或上传后在列表中恢复；此处粘贴文本并指定文件名。"
                "kv" -> "可上传 .rdb 文件名 + 内容。"
                else -> "支持 .sql / .sql.gz / .dump（PG 自定义格式）。粘贴文本后上传到服务器备份目录。"
            }
            Text(hint, style = dbMuted, fontSize = 12.sp)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Field(sqlName, { sqlName = it }, Modifier.width(220.dp), label = "文件名", hint = "backup.sql", mono = true)
                Field(sqlPaste, { sqlPaste = it }, Modifier.weight(1f), label = "文件内容（粘贴）", hint = "-- SQL", mono = true, singleLine = false)
                DbBtn("上传", tone = DbBtnTone.Primary) {
                    scope.launch {
                        if (sqlPaste.isEmpty()) {
                            AppState.toast("请粘贴文件内容", ToastKind.Warn)
                            return@launch
                        }
                        try {
                            val r = AppState.api.postRaw("/db/upload?name=${q(sqlName.ifEmpty { "upload.sql" })}", sqlPaste.encodeToByteArray())
                            AppState.toast("上传成功: ${r.str("output")}", ToastKind.Ok)
                            st.bump()
                        } catch (e: Exception) {
                            AppState.toast("上传失败: ${e.message}", ToastKind.Err)
                        }
                    }
                }
            }
        }

        PanelCard(
            title = "备份文件 (${items.size})",
            cardKey = "bk-list",
            right = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DbBtn("清理旧备份") {
                        scope.launch {
                            val r = Modal.prompt("清理旧备份", listOf(FormField.Text("keep", "保留最近 N 个", value = "10")), okText = "清理", danger = true)
                                ?: return@launch
                            runDb("/db/backup/cleanup", jsonObjectOf("keep" to (r["keep"]?.toIntOrNull() ?: 10)), reload = { st.bump() })
                        }
                    }
                    DbBtn("刷新") { st.bump() }
                }
            },
        ) {
            DynamicTable(
                columns = listOf("name", "engine", "mode", "size", "mtime"),
                rows = items,
                maxHeight = 320,
                extra = TableCol("操作", weight = 1.8f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn("下载") { scope.launch { downloadNamed(n) } }
                        if (!row.bool("export")) {
                            DbBtn("恢复", tone = DbBtnTone.Ok) { restoreFile(scope, st, n, dbs) }
                        }
                        DbBtn("删除", tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm("删除备份文件", "删除 $n ？", okText = "删除", danger = true)
                                if (!okc) return@launch
                                runDb("/db/backup/delete", jsonObjectOf("name" to n), reload = { st.bump() })
                            }
                        }
                    }
                },
            )
        }
    }
}

private fun restoreFile(scope: CoroutineScope, st: DbState, file: String, dbs: List<JsonObject>) {
    scope.launch {
        when (st.kind) {
            "kv" -> {
                val okc = Modal.confirm(
                    "恢复 Redis",
                    "用 $file 覆盖 Redis 当前数据？会覆盖数据目录中的 dump.rdb，重启 Redis 后生效，现有数据将丢失。",
                    okText = "开始恢复",
                    danger = true,
                )
                if (!okc) return@launch
                runDb("/db/restore", jsonObjectOf("engine" to st.engineId, "file" to file), outputTitle = "恢复结果", toast = false)
            }
            "doc" -> {
                val r = Modal.prompt(
                    "恢复 MongoDB 归档",
                    listOf(FormField.Text("database", "限定数据库（留空=恢复全部）", value = st.database ?: "", hint = "mydb")),
                    okText = "下一步",
                ) ?: return@launch
                runDb(
                    "/db/restore",
                    jsonObjectOf("engine" to st.engineId, "file" to file, "database" to r["database"].orEmpty()),
                    outputTitle = "恢复结果",
                    toast = false,
                )
            }
            else -> {
                val def = dbs.firstOrNull()?.str("name").orEmpty()
                val target = Modal.prompt(
                    "恢复备份到目标数据库",
                    listOf(FormField.Text("database", "目标数据库（可输入新名称，配合“自动创建”即可）", value = def, hint = "mydb")),
                    okText = "下一步",
                ) ?: return@launch
                val database = target["database"]?.trim().orEmpty()
                if (database.isEmpty()) return@launch
                val mode = Modal.prompt(
                    "恢复方式",
                    listOf(
                        FormField.Select("mode", "导入方式", listOf("合并导入（保留现有对象）", "清空后恢复（--clean，可能丢数据）"), "合并导入（保留现有对象）"),
                        FormField.Select("target", "目标库不存在时", listOf("自动创建", "报错退出"), "自动创建"),
                    ),
                    danger = true,
                    okText = "开始恢复",
                ) ?: return@launch
                runDb(
                    "/db/restore",
                    jsonObjectOf(
                        "engine" to st.engineId, "database" to database, "file" to file,
                        "dropFirst" to mode["mode"].orEmpty().startsWith("清空"),
                        "createIfMissing" to (mode["target"] == "自动创建"),
                    ),
                    outputTitle = "恢复结果",
                    toast = false,
                )
            }
        }
    }
}

private fun addDailyCron(scope: CoroutineScope, st: DbState) {
    scope.launch {
        val dir = AppState.me.value?.obj("env")?.obj("paths")?.str("dbBackups").orEmpty()
        if (dir.isEmpty()) {
            AppState.toast("未能获取服务器数据目录，请刷新页面后重试", ToastKind.Warn)
            return@launch
        }
        val cmd = when {
            st.kind == "kv" -> "D=\$(redis-cli CONFIG GET dir | tail -1); F=\$(redis-cli CONFIG GET dbfilename | tail -1); redis-cli BGSAVE; sleep 5; cp \"\$D/\$F\" $dir/db-redis-ALL-\$(date +\\%Y-\\%m-\\%d).rdb"
            st.kind == "doc" -> "mongodump --archive=$dir/db-mongo-ALL-\$(date +\\%Y-\\%m-\\%d).dump --gzip"
            st.isSqlite -> "find /opt /srv /var/www -maxdepth 4 -name '*.db' -exec sh -c 'gzip -c \"\$1\" > $dir/db-sqlite-\$(basename \"\$1\")-\$(date +\\%Y-\\%m-\\%d).sql.gz' _ {} \\; 2>/dev/null || true"
            st.isDb2 -> "su - db2inst1 -c 'db2 backup database SAMPLE online to $dir'"
            st.isPg -> "su - postgres -c 'pg_dumpall' | gzip -1 > $dir/db-postgres-ALL-\$(date +\\%Y-\\%m-\\%d).sql.gz"
            else -> "mysqldump --single-transaction --routines --triggers --all-databases | gzip -1 > $dir/db-mysql-ALL-\$(date +\\%Y-\\%m-\\%d).sql.gz"
        }
        val okc = Modal.confirm("创建每日自动备份", "将添加 root 定时任务，每天 03:00 执行：\n$cmd", okText = "创建任务")
        if (!okc) return@launch
        runDb(
            "/cron/action",
            jsonObjectOf(
                "action" to "add",
                "params" to jsonObjectOf("name" to "dbbackup-${st.engineId}", "schedule" to "0 3 * * *", "command" to cmd),
            ),
        )
    }
}
