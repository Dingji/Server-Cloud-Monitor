package com.servermaintance.panel.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.servermaintance.panel.core.key
import com.servermaintance.panel.core.long
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
import com.servermaintance.panel.ui.PanelIcon
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

    PageScaffold(title = "备份中心", desc = "服务器文件打包归档与下载，含 Relay 每日状态备份") {
        when {
            loading && d == null -> PanelCard(title = "备份中心") { LoadingState("正在读取备份列表…") }
            errText != null -> PanelCard(title = "读取失败") {
                ErrorState(errText!!, onRetry = { scope.launch { load() } })
            }
            d == null -> PanelCard(title = "备份中心") {
                ErrorState("暂无数据", onRetry = { scope.launch { load() } })
            }

            else -> {
                /* ---- B1 统计 + 快速备份 ---- */
                GridLayout(columns = 3, spacing = 12.dp) {
                    com.servermaintance.panel.ui.StatTile(
                        label = "备份文件",
                        value = items.size.toString(),
                        sub = Fmt.bytes(totalSize) + " 总占用",
                    )
                    com.servermaintance.panel.ui.StatTile(
                        label = "存放位置",
                        value = d.str("dir"),
                        sub = "面板同机目录",
                    )
                    com.servermaintance.panel.ui.StatTile(
                        label = "快速备份",
                        value = "选择目录 ›",
                        sub = "打包为 tar.gz",
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
                            text = if (busy) "备份中…" else src,
                            enabled = !busy && busySrc == null,
                            onClick = {
                                scope.launch {
                                    val ok = Modal.confirm(
                                        title = "创建备份",
                                        text = "将 $src 打包为 tar.gz（大目录可能耗时较长）。",
                                        okText = "开始备份",
                                    )
                                    if (!ok) return@launch
                                    busySrc = src
                                    try {
                                        val r = Actions.run(
                                            path = "/backups",
                                            body = jsonObjectOf("source" to src),
                                            title = "创建备份",
                                        )
                                        val nm = (r as? JsonObject)?.str("name").orEmpty()
                                        AppState.toast("备份完成: " + nm.ifBlank { src }, ToastKind.Ok)
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
                    title = "备份列表",
                    right = { GhostBtn(text = "刷新", onClick = { scope.launch { load() } }) },
                ) {
                    if (items.isEmpty()) {
                        EmptyState("暂无备份，点击上方目录按钮创建")
                    } else {
                        DataTable<BackupItem>(
                            columns = listOf(
                                TableCol(title = "文件名", weight = 3f, mono = true) {
                                    Text(
                                        text = it.name,
                                        color = Palette.text,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                TableCol(title = "大小", weight = 1f, align = androidx.compose.ui.text.style.TextAlign.End) {
                                    Text(
                                        text = Fmt.bytes(it.size),
                                        color = Palette.text,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                TableCol(title = "时间", weight = 1.6f, mono = true) {
                                    Text(
                                        text = it.mtime,
                                        color = Palette.muted,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                },
                                TableCol(title = "操作", weight = 1.6f) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        GhostBtn(
                                            text = "下载",
                                            onClick = {
                                                scope.launch { fetchFile("/backups/download?name=${urlEncode(it.name)}", it.name) }
                                            },
                                        )
                                        com.servermaintance.panel.ui.DangerBtn(
                                            text = "删除",
                                            onClick = {
                                                scope.launch {
                                                    val ok = Modal.confirm(
                                                        title = "删除备份",
                                                        text = "删除 ${it.name}？不可恢复。",
                                                        okText = "删除",
                                                        danger = true,
                                                    )
                                                    if (!ok) return@launch
                                                    Actions.run(
                                                        path = "/backups/delete",
                                                        body = jsonObjectOf("name" to it.name),
                                                        successToast = "备份已删除",
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
                            emptyText = "暂无备份",
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "下载备份：下载按钮走当前登录会话鉴权。建议定期将重要备份另行转存到对象存储/本地。",
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
                                title = "立即备份",
                                text = "执行一次 relay 状态热备份（SQLite 在线备份，不中断中继服务）。",
                                okText = "开始备份",
                            )
                            if (!ok) return@launch
                            relayBusy = true
                            try {
                                Actions.run(path = "/relayops/backup/run", body = jsonObjectOf(), title = "Relay 备份结果")
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
                                title = "删除备份",
                                text = "删除 ${item.name}？不可恢复。",
                                okText = "删除",
                                danger = true,
                            )
                            if (!ok) return@launch
                            Actions.run(
                                path = "/relayops/backup/delete",
                                body = jsonObjectOf("name" to item.name),
                                successToast = "中继备份已删除",
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
        if (saved != null) AppState.toast("已保存到 $saved", ToastKind.Ok, 6000)
        else AppState.toast("保存失败或被取消", ToastKind.Err, 6000)
    } catch (e: ApiException) {
        AppState.toast(e.message.orEmpty(), ToastKind.Err, 6000)
        e.output?.let { Modal.output("下载失败", it, isError = true) }
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
        title = "Relay 每日状态备份",
        right = {
            PrimaryBtn(
                text = if (busy) "备份中…" else "立即备份",
                enabled = !busy,
                onClick = onRun,
            )
            GhostBtn(text = "刷新", onClick = onRefresh)
        },
    ) {
        val err = relay?.str("error").orEmpty()
        if (relay == null || relay["dir"] == null || err.isNotBlank()) {
            Text(
                text = "relay 备份不可用" + if (err.isNotBlank()) "：$err" else "",
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
                label = "备份份数",
                value = rItems.size.toString(),
                sub = "保留 ${relay.int("retention")} 份",
            )
            com.servermaintance.panel.ui.StatTile(
                label = "占用",
                value = Fmt.bytes(rTotal),
                sub = relay.str("dir"),
            )
            com.servermaintance.panel.ui.StatTile(
                label = "上次结果",
                value = if (st == null) "—" else if (st.str("ok").toBoolean()) "成功" else "失败",
                sub = st?.str("ts")?.replace('T', ' ')?.take(19).orEmpty().ifBlank { "无记录" },
            )
            com.servermaintance.panel.ui.StatTile(
                label = "远端推送",
                value = if (remote.isNotBlank()) "已配置" else "仅本地",
                sub = remote.ifBlank { "在 /etc/relay/backup.env 配置" },
            )
        }
        Spacer(Modifier.height(10.dp))

        if (st != null) {
            val okBadge = st.str("ok").toBoolean()
            LevelBadge(
                text = if (okBadge) "上次成功" else "上次失败",
                level = if (okBadge) Level.Ok else Level.Bad,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (rItems.isEmpty()) {
            EmptyState("暂无中继备份")
        } else {
            DataTable<BackupItem>(
                columns = listOf(
                    TableCol(title = "文件名", weight = 3f, mono = true) {
                        Text(it.name, color = Palette.text, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    },
                    TableCol(title = "大小", weight = 1f, align = androidx.compose.ui.text.style.TextAlign.End) {
                        Text(Fmt.bytes(it.size), color = Palette.text, style = MaterialTheme.typography.bodySmall)
                    },
                    TableCol(title = "时间", weight = 1.6f, mono = true) {
                        Text(it.mtime, color = Palette.muted, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    },
                    TableCol(title = "操作", weight = 1.6f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GhostBtn(text = "下载", onClick = { onDownload(it) })
                            com.servermaintance.panel.ui.DangerBtn(text = "删除", onClick = { onDelete(it) })
                        }
                    },
                ),
                rows = rItems,
                rowKey = { it.name },
                emptyText = "暂无中继备份",
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "由 /opt/relay/backup_relay.sh 生成，relay-backup.timer 每日 03:30 执行。",
            color = Palette.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
