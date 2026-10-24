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
        val allLabel = if (kind == "kv") L["当前库_db0_db15_全量_rdb_8e1d4e62b1"] else L["全部数据库_服务器级全量_488b444221"]

        PanelCard(
            title = L["新建备份_导出_e3160cab07"],
            cardKey = "bk-new",
            right = {
                DbBtn(L["开始备份_bf05cce62d"], tone = DbBtnTone.Primary) {
                    scope.launch {
                        val database = dbSel
                        val modeLabel = mapOf("full" to L["完整_6e6e581133"], "schema" to L["仅结构_15c49958e4"], "data" to L["仅数据_bb3ec07c5a"])[mode] ?: mode
                        val fmtLabel = when {
                            kind == "kv" -> L["rdb_快照_62f218ed40"]
                            kind == "doc" -> L["gzip_归档_39fcde3a7b"]
                            format == "custom" -> L["pg_自定义格式_0cc41d0ea4"]
                            else -> L["sql_gzip_d1899c1985"]
                        }
                        val okc = Modal.confirm(L["创建备份_f4587bf8b2"], trf("备份_0_1_2_大库可能需要_3278894ce9", database, modeLabel, fmtLabel), okText = L["开始备份_bf05cce62d"])
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
                    label = L["数据库_f4dbbc63a5"],
                )
                if (kind == "sql") {
                    SelectField(
                        value = when (mode) {
                            "schema" -> L["仅结构_不含数据_86341bfbf0"]
                            "data" -> L["仅数据_不含建表_deb7198275"]
                            else -> L["完整_结构_数据_6aebce144a"]
                        },
                        options = listOf(L["完整_结构_数据_6aebce144a"], L["仅结构_不含数据_86341bfbf0"], L["仅数据_不含建表_deb7198275"]),
                        onValueChange = {
                            mode = when {
                                it.startsWith(L["仅结构_15c49958e4"]) -> "schema"
                                it.startsWith(L["仅数据_bb3ec07c5a"]) -> "data"
                                else -> "full"
                            }
                        },
                        modifier = Modifier.width(200.dp),
                        label = L["内容_163aec9194"],
                    )
                    val fmtOpts = buildList {
                        add(L["sql_文本_gzip_通用_可编辑_48f716760f"])
                        if (st.isPg) add(L["postgresql_自定义格式_fc_更快恢_080c860e81"])
                    }
                    SelectField(
                        value = if (format == "custom") fmtOpts.getOrElse(1) { fmtOpts.first() } else fmtOpts.first(),
                        options = fmtOpts,
                        onValueChange = {
                            format = if (st.isPg && it == fmtOpts.getOrNull(1)) "custom" else "plain"
                        },
                        modifier = Modifier.width(260.dp),
                        label = L["格式_9d2601c843"],
                    )
                    Field(exclude, { exclude = it }, Modifier.width(220.dp), label = L["排除表_逗号分隔_可选_c3fbff6bed"], hint = L["logs_tmp_table_69c2e14626"], mono = true)
                }
                DbBtn(L["每日自动备份_7ccce9e358"]) { addDailyCron(scope, st) }
            }
            Spacer(Modifier.size(8.dp))
            val extra = when {
                kind == "kv" -> L["redis_会执行_bgsave_并复制_r_86e2019f6b"]
                kind == "doc" -> L["mongodb_使用_mongodump_生_c5b1c88a48"]
                st.isSqlite -> L["sqlite_生成_sql_文本_gzi_f960464be7"]
                else -> ""
            }
            Text(
                trf("备份保存在服务器_0_可直接下载_1_2bb556350e", dir.ifEmpty { L["未知目录_9fdfa31d5a"] }, extra),
                style = dbMuted,
                fontSize = 11.sp,
            )
        }

        PanelCard(title = L["导入_sql_文件_用于恢复_3313acce03"], cardKey = "bk-up") {
            val hint = when (kind) {
                "doc" -> L["可粘贴_mongodump_归档内容或上传后在列_40fb6a2727"]
                "kv" -> L["可上传_rdb_文件名_内容_0e9334cab9"]
                else -> L["支持_sql_sql_gz_dum_e07870399d"]
            }
            Text(hint, style = dbMuted, fontSize = 12.sp)
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Field(sqlName, { sqlName = it }, Modifier.width(220.dp), label = L["文件名_1275f6feb7"], hint = "backup.sql", mono = true)
                Field(sqlPaste, { sqlPaste = it }, Modifier.weight(1f), label = L["文件内容_粘贴_98b4f4a653"], hint = L["sql_2408bda0eb"], mono = true, singleLine = false)
                DbBtn(L["上传_a977650db1"], tone = DbBtnTone.Primary) {
                    scope.launch {
                        if (sqlPaste.isEmpty()) {
                            AppState.toast(tr("请粘贴文件内容_9c360ce221"), ToastKind.Warn)
                            return@launch
                        }
                        try {
                            val r = AppState.api.postRaw("/db/upload?name=${q(sqlName.ifEmpty { "upload.sql" })}", sqlPaste.encodeToByteArray())
                            AppState.toast(trf("上传成功_0_9885cca2ec", r.str("output")), ToastKind.Ok)
                            st.bump()
                        } catch (e: Exception) {
                            AppState.toast(tr("上传失败_e_message_54a69c1dab"), ToastKind.Err)
                        }
                    }
                }
            }
        }

        PanelCard(
            title = trf("备份文件_0_fa83a4f5de", items.size),
            cardKey = "bk-list",
            right = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DbBtn(L["清理旧备份_17d95953c3"]) {
                        scope.launch {
                            val r = Modal.prompt(L["清理旧备份_17d95953c3"], listOf(FormField.Text("keep", L["保留最近_n_个_855e3ce9c7"], value = "10")), okText = L["清理_907c3945d8"], danger = true)
                                ?: return@launch
                            runDb("/db/backup/cleanup", jsonObjectOf("keep" to (r["keep"]?.toIntOrNull() ?: 10)), reload = { st.bump() })
                        }
                    }
                    DbBtn(L["刷新_38108eaa1d"]) { st.bump() }
                }
            },
        ) {
            DynamicTable(
                columns = listOf("name", "engine", "mode", "size", "mtime"),
                rows = items,
                maxHeight = 320,
                extra = TableCol(L["操作_f3ea6d345e"], weight = 1.8f) { row ->
                    val n = row.str("name")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DbBtn(L["下载_2b9d013177"]) { scope.launch { downloadNamed(n) } }
                        if (!row.bool("export")) {
                            DbBtn(L["恢复_79748ca1c6"], tone = DbBtnTone.Ok) { restoreFile(scope, st, n, dbs) }
                        }
                        DbBtn(L["删除_3755f56f2f"], tone = DbBtnTone.Danger) {
                            scope.launch {
                                val okc = Modal.confirm(L["删除备份文件_df6543f316"], trf("删除_0_a1ee4b1af0", n), okText = L["删除_3755f56f2f"], danger = true)
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
                    L["恢复_redis_7dd20f103c"],
                    L["用_file_覆盖_redis_当前数据_会覆_3f0f894cfc"],
                    okText = L["开始恢复_86cc2051a7"],
                    danger = true,
                )
                if (!okc) return@launch
                runDb("/db/restore", jsonObjectOf("engine" to st.engineId, "file" to file), outputTitle = L["恢复结果_25404d8096"], toast = false)
            }
            "doc" -> {
                val r = Modal.prompt(
                    L["恢复_mongodb_归档_ad961869c7"],
                    listOf(FormField.Text("database", L["限定数据库_留空_恢复全部_8f54e161bd"], value = st.database ?: "", hint = "mydb")),
                    okText = L["下一步_ea0ef2ae72"],
                ) ?: return@launch
                runDb(
                    "/db/restore",
                    jsonObjectOf("engine" to st.engineId, "file" to file, "database" to r["database"].orEmpty()),
                    outputTitle = L["恢复结果_25404d8096"],
                    toast = false,
                )
            }
            else -> {
                val def = dbs.firstOrNull()?.str("name").orEmpty()
                val target = Modal.prompt(
                    L["恢复备份到目标数据库_00fbc874c2"],
                    listOf(FormField.Text("database", L["目标数据库_可输入新名称_配合_自动创建_即可_ee8b39a62f"], value = def, hint = "mydb")),
                    okText = L["下一步_ea0ef2ae72"],
                ) ?: return@launch
                val database = target["database"]?.trim().orEmpty()
                if (database.isEmpty()) return@launch
                val mode = Modal.prompt(
                    L["恢复方式_4a0ce48265"],
                    listOf(
                        FormField.Select(
                            "mode",
                            L["导入方式_0eac17d654"],
                            listOf(L["合并导入_保留现有对象_5693e46162"], L["清空后恢复_clean_可能丢数据_641f717946"]),
                            L["合并导入_保留现有对象_5693e46162"],
                        ),
                        FormField.Select(
                            "target",
                            L["目标库不存在时_3bc50befc9"],
                            listOf(L["自动创建_349e19da26"], L["报错退出_e9bcdb3450"]),
                            L["自动创建_349e19da26"],
                        ),
                    ),
                    danger = true,
                    okText = L["开始恢复_86cc2051a7"],
                ) ?: return@launch
                runDb(
                    "/db/restore",
                    jsonObjectOf(
                        "engine" to st.engineId, "database" to database, "file" to file,
                        "dropFirst" to mode["mode"].orEmpty().startsWith(L["清空_84fcd70d42"]),
                        "createIfMissing" to (mode["target"] == L["自动创建_349e19da26"]),
                    ),
                    outputTitle = L["恢复结果_25404d8096"],
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
            AppState.toast(tr("未能获取服务器数据目录_请刷新页面后重试_6a4049502d"), ToastKind.Warn)
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
        val okc = Modal.confirm(
            L["创建每日自动备份_7c94d434a4"],
            trf("将添加_root_定时任务_每天_03_00_执_e6ee798c58", cmd),
            okText = L["创建任务_cf42fd678f"],
        )
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
