package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.servermaintance.panel.core.ApiException
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.Fmt
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.int
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.num
import com.servermaintance.panel.core.obj
import com.servermaintance.panel.core.str
import com.servermaintance.panel.platformExporter
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.EmptyState
import com.servermaintance.panel.ui.ErrorState
import com.servermaintance.panel.ui.GhostBtn
import com.servermaintance.panel.ui.GridLayout
import com.servermaintance.panel.ui.Level
import com.servermaintance.panel.ui.LevelBadge
import com.servermaintance.panel.ui.LoadingState
import com.servermaintance.panel.ui.Modal
import com.servermaintance.panel.ui.PageScaffold
import com.servermaintance.panel.ui.Palette
import com.servermaintance.panel.ui.PanelCard
import com.servermaintance.panel.ui.PrimaryBtn
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/* =====================================================================
 * 备份中心 —— 复刻 upstream/public/js/pages/backups.js (124 行)
 *
 * 卡片清单（自检，供测试对账）：
 *  B1 磁贴 ×3：备份文件(items.length + 总占用) / 存放位置(dir, mono) /
 *              快速备份（4 个源按钮 /etc /root /var/www /srv → 建档）
 *  B2 备份列表：文件名(mono wrap)/大小(时间)/时间/操作(下载|删除) + 「刷新」
 *              + 下载鉴权说明
 *  B3 Relay 每日状态备份：
 *     · 不可用态：relay 缺失或带 error → 「relay 备份不可用：<error>」
 *     · 磁贴 ×4：备份份数(+retention) / 占用(+dir) / 上次结果(成功|失败 + ts) /
 *                远端推送(已配置|仅本地 + remote)
 *     · 表格：文件名/大小/时间/操作(下载|删除)
 *     · 「立即备份」「刷新」+ backup_relay.sh / relay-backup.timer(03:30) 说明
 *
 * 动作端点：
 *   POST /backups {source}                    建档（确认 okText=开始备份）
 *   POST /backups/delete {name}               删档（danger）
 *   GET  /backups/download?name=              下载 → platformExporter.saveFile
 *   POST /relayops/backup/run {}              立即备份（成功弹 output）
 *   POST /relayops/backup/delete {name}       删中继备份（danger）
 *   GET  /relayops/backup/download?name=      下载中继备份
 * ===================================================================== */

private val BACKUP_SOURCES = listOf("/etc", "/root", "/var/www", "/srv")

private data class BackupItem(val name: String, val size: Long, val mtime: String)

private fun parseItems(arr: List<JsonElement>): List<BackupItem> = arr.map {
    BackupItem(
        name = it.str("name"),
        size = it.num("size").toLong(),
        mtime = it.str("mtime"),
    )
}

@Composable
fun BackupsPage() {
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var errText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busySrc by remember { mutableStateOf<String?>(null) }
    var relayBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        errText = null
        try {
            data = AppState.api.get("/backups") as? JsonObject
        } catch (e: ApiException) {
            errText = e.message + (e.output?.let { "\n$it" } ?: "")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    val d = data
    val items = parseItems(d?.arr("items") ?: emptyList())
    val totalSize = items.sumOf { it.size }
    val relayObj = d?.obj("relay")

    PageScaffold(title = L["nav.backups"], desc = L["服务器文件打包归档与下载_含_relay_每日状_268e49a95e"]) {
        when {
            loading && d == null -> PanelCard(title = L["nav.backups"]) { LoadingState(L["正在读取备份列表_bbfed01c1d"]) }
            errText != null -> PanelCard(title = L["读取失败_d9f607a200"]) {
                ErrorState(errText!!, onRetry = { scope.launch { load() } })
            }
            d == null -> PanelCard(title = L["备份中心_b031a1495d"]) {
                ErrorState(L["暂无数据_b246458f20"], onRetry = { scope.launch { load() } })
            }

            else -> {
                /* ---- B1 统计 + 快速备份 ---- */
                GridLayout(columns = 3, spacing = 12.dp) {
                    com.servermaintance.panel.ui.StatTile(
                        label = L["备份文件_f7fa5f4803"],
                        value = items.size.toString(),
                        sub = Fmt.bytes(totalSize) + L["总占用_3da7163a84"],
                    )
                    com.servermaintance.panel.ui.StatTile(
                        label = L["存放位置_182f3cab1b"],
                        value = d.str("dir"),
                        sub = L["面板同机目录_3c70d2a627"],
                    )
                    com.servermaintance.panel.ui.StatTile(
                        label = L["快速备份_1cbe05845d"],
                        value = L["选择目录_0035ee50a9"],
                        sub = L["打包为_tar_gz_3ea0166662"],
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BACKUP_SOURCES.forEach { src ->
                        val busy = busySrc == src
                        PrimaryBtn(
                            text = if (busy) L["备份中_335065c5e3"] else src,
                            enabled = !busy && busySrc == null,
                            onClick = {
                                scope.launch {
                                    val ok = Modal.confirm(
                                        title = L["创建备份_f4587bf8b2"],
                                        text = L["将_src_打包为_tar_gz_大目录可能耗_0e4d5baded"],
                                        okText = L["开始备份_bf05cce62d"],
                                    )
                                    if (!ok) return@launch
                                    busySrc = src
                                    try {
                                        val r = Actions.run(
                                            path = "/backups",
                                            body = jsonObjectOf("source" to src),
                                            title = L["创建备份_f4587bf8b2"],
                                        )
                                        val nm = (r as? JsonObject)?.str("name").orEmpty()
                                        AppState.toast(tr("备份完成_5afaa8c8a9") + nm.ifBlank { src }, ToastKind.Ok)
                                        load()
                                    } finally {
                                        busySrc = null
                                    }
                                }
                            },
                        )
                    }
                }

                /* ---- B2 备份列表 ---- */
                Spacer(Modifier.height(14.dp))
                PanelCard(
                    title = L["备份列表_2a4c87bca5"],
                    right = { GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { load() } }) },
                ) {
                    if (items.isEmpty()) {
                        EmptyState(L["暂无备份_点击上方目录按钮创建_68fce26f69"])
                    } else {
                        DataTable<BackupItem>(
                            columns = listOf(
                                TableCol(title = L["文件名_1275f6feb7"], weight = 3f, mono = true) {
                                    Text(
                                        text = it.name,
                                        color = Palette.text,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                TableCol(title = L["大小_fd20702c73"], weight = 1f, align = androidx.compose.ui.text.style.TextAlign.End) {
                                    Text(
                                        text = Fmt.bytes(it.size),
                                        color = Palette.text,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                TableCol(title = L["时间_89b4aa6364"], weight = 1.6f, mono = true) {
                                    Text(
                                        text = it.mtime,
                                        color = Palette.muted,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                },
                                TableCol(title = L["操作_f3ea6d345e"], weight = 1.6f) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        GhostBtn(
                                            text = L["下载_2b9d013177"],
                                            onClick = {
                                                scope.launch { fetchFile("/backups/download?name=${urlEncode(it.name)}", it.name) }
                                            },
                                        )
                                        com.servermaintance.panel.ui.DangerBtn(
                                            text = L["删除_3755f56f2f"],
                                            onClick = {
                                                scope.launch {
                                                    val ok = Modal.confirm(
                                                        title = L["删除备份_749438d2de"],
                                                        text = trf("删除_0_不可恢复_122855ffb8", it.name),
                                                        okText = L["删除_3755f56f2f"],
                                                        danger = true,
                                                    )
                                                    if (!ok) return@launch
                                                    Actions.run(
                                                        path = "/backups/delete",
                                                        body = jsonObjectOf("name" to it.name),
                                                        successToast = L["备份已删除_463724802f"],
                                                    )
                                                    load()
                                                }
                                            },
                                        )
                                    }
                                },
                            ),
                            rows = items,
                            rowKey = { it.name },
                            emptyText = L["暂无备份_d5392bb044"],
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = L["下载备份_下载按钮走当前登录会话鉴权_建议定期将_4fe80a03be"],
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                /* ---- B3 Relay 每日状态备份 ---- */
                Spacer(Modifier.height(14.dp))
                RelayBackupCard(
                    relay = relayObj,
                    busy = relayBusy,
                    onRefresh = { scope.launch { load() } },
                    onRun = {
                        scope.launch {
                            val ok = Modal.confirm(
                                title = L["立即备份_10119439c7"],
                                text = L["执行一次_relay_状态热备份_sqlite_8bc4bec089"],
                                okText = L["开始备份_bf05cce62d"],
                            )
                            if (!ok) return@launch
                            relayBusy = true
                            try {
                                Actions.run(path = "/relayops/backup/run", body = jsonObjectOf(), title = L["relay_备份结果_0cf7f02f06"])
                                load()
                            } finally {
                                relayBusy = false
                            }
                        }
                    },
                    onDownload = { item ->
                        scope.launch {
                            fetchFile("/relayops/backup/download?name=${urlEncode(item.name)}", item.name)
                        }
                    },
                    onDelete = { item ->
                        scope.launch {
                            val ok = Modal.confirm(
                                title = L["删除备份_749438d2de"],
                                text = trf("删除_0_不可恢复_122855ffb8", item.name),
                                okText = L["删除_3755f56f2f"],
                                danger = true,
                            )
                            if (!ok) return@launch
                            Actions.run(
                                path = "/relayops/backup/delete",
                                body = jsonObjectOf("name" to item.name),
                                successToast = L["中继备份已删除_2f3ade3c12"],
                            )
                            load()
                        }
                    },
                )
            }
        }
    }
}

/** 二进制下载 → 保存到本机，返回保存路径（或 null）。 */
internal suspend fun fetchFile(path: String, fileName: String) {
    try {
        val bytes = AppState.api.download(path)
        val saved = platformExporter.saveFile(fileName, bytes)
        if (saved != null) AppState.toast(tr("已保存到_saved_49bd95c1fb"), ToastKind.Ok, 6000)
        else AppState.toast(tr("保存失败或被取消_7e2bbcfe18"), ToastKind.Err, 6000)
    } catch (e: ApiException) {
        AppState.toast(e.message.orEmpty(), ToastKind.Err, 6000)
        e.output?.let { Modal.output(L["下载失败_e0dab22b1a"], it, isError = true) }
    }
}

@Composable
private fun RelayBackupCard(
    relay: JsonObject?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRun: () -> Unit,
    onDownload: (BackupItem) -> Unit,
    onDelete: (BackupItem) -> Unit,
) {
    PanelCard(
        title = L["relay_每日状态备份_32dee8ba2b"],
        right = {
            PrimaryBtn(
                text = if (busy) L["备份中_335065c5e3"] else L["立即备份_10119439c7"],
                enabled = !busy,
                onClick = onRun,
            )
            GhostBtn(text = L["刷新_38108eaa1d"], onClick = onRefresh)
        },
    ) {
        val err = relay?.str("error").orEmpty()
        if (relay == null || relay["dir"] == null || err.isNotBlank()) {
            Text(
                text = L["relay_备份不可用_0e40f7d8f3"] + if (err.isNotBlank()) "：$err" else "",
                color = Palette.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            return@PanelCard
        }

        val rItems = parseItems(relay.arr("items"))
        val rTotal = rItems.sumOf { it.size }
        val st = relay.obj("state")
        val remote = relay.str("remote")

        GridLayout(columns = 4, spacing = 12.dp) {
            com.servermaintance.panel.ui.StatTile(
                label = L["备份份数_e27f7251ad"],
                value = rItems.size.toString(),
                sub = trf("保留_0_份_6f62f9cd31", relay.int("retention")),
            )
            com.servermaintance.panel.ui.StatTile(
                label = L["占用_7b81023b68"],
                value = Fmt.bytes(rTotal),
                sub = relay.str("dir"),
            )
            com.servermaintance.panel.ui.StatTile(
                label = L["上次结果_8f8eeff3b1"],
                value = if (st == null) "—" else if (st.str("ok").toBoolean()) L["成功_51991a5d11"] else L["失败_3e3c8068bb"],
                sub = st?.str("ts")?.replace('T', ' ')?.take(19).orEmpty().ifBlank { L["无记录_afaa05cdc4"] },
            )
            com.servermaintance.panel.ui.StatTile(
                label = L["远端推送_69e4125b5a"],
                value = if (remote.isNotBlank()) L["已配置_dc7cd22f34"] else L["仅本地_5c28a4b5d1"],
                sub = remote.ifBlank { L["在_etc_relay_backup_env_f2bf93a0b1"] },
            )
        }
        Spacer(Modifier.height(10.dp))

        if (st != null) {
            val okBadge = st.str("ok").toBoolean()
            LevelBadge(
                text = if (okBadge) L["上次成功_99cb56c5f4"] else L["上次失败_5ab6caa33f"],
                level = if (okBadge) Level.Ok else Level.Bad,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (rItems.isEmpty()) {
            EmptyState(L["暂无中继备份_dacac650ad"])
        } else {
            DataTable<BackupItem>(
                columns = listOf(
                    TableCol(title = L["文件名_1275f6feb7"], weight = 3f, mono = true) {
                        Text(it.name, color = Palette.text, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    },
                    TableCol(title = L["大小_fd20702c73"], weight = 1f, align = androidx.compose.ui.text.style.TextAlign.End) {
                        Text(Fmt.bytes(it.size), color = Palette.text, style = MaterialTheme.typography.bodySmall)
                    },
                    TableCol(title = L["时间_89b4aa6364"], weight = 1.6f, mono = true) {
                        Text(it.mtime, color = Palette.muted, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    },
                    TableCol(title = L["操作_f3ea6d345e"], weight = 1.6f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GhostBtn(text = L["下载_2b9d013177"], onClick = { onDownload(it) })
                            com.servermaintance.panel.ui.DangerBtn(text = L["删除_3755f56f2f"], onClick = { onDelete(it) })
                        }
                    },
                ),
                rows = rItems,
                rowKey = { it.name },
                emptyText = L["暂无中继备份_dacac650ad"],
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = L["由_opt_relay_backup_rela_bc08303542"],
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
