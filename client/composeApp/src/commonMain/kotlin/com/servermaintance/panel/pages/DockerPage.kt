package com.servermaintance.panel.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.servermaintance.panel.core.Actions
import com.servermaintance.panel.core.AppState
import com.servermaintance.panel.core.ToastKind
import com.servermaintance.panel.core.arr
import com.servermaintance.panel.core.bool
import com.servermaintance.panel.core.jsonObjectOf
import com.servermaintance.panel.core.str
import com.servermaintance.panel.ui.DataTable
import com.servermaintance.panel.ui.DangerBtn
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
import com.servermaintance.panel.ui.StatTile
import com.servermaintance.panel.ui.TableCol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/* ==================================================================== *
 * Docker 容器 —— 复刻 upstream/public/js/pages/docker.js（135 行）
 * 端点：GET  /docker → {ts, installed, active, version,
 *                      containers:[{id,name,image,status,ports,running}],
 *                      images:[{repo,size,created}],
 *                      stats:[{name,cpu,mem,memPct,net}]}
 *       POST /docker/action {action, params:{target|ref}}
 *              action: start/stop/restart/rm/img-rm/
 *                      daemon-start/daemon-stop/daemon-enable/prune
 *       POST /logs/tail {source:"docker:<id>"} → {output}
 *       POST /pkg/install {pkg:"docker.io"}（未安装时一键安装）
 * 注意：真实服务器 api-dump/docker.json 只有 {ts, installed:false} ——
 *       未安装分支必须优雅降级，不得访问 containers 等缺失字段。
 * ==================================================================== */

@Composable
fun DockerPage() {
    var data by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            data = AppState.api.get("/docker", cacheMs = 2000)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: e::class.simpleName ?: "请求失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    /** JS `runAction('/docker/action', {action, params}, {reload, reloadDelay})`。 */
    suspend fun dockerAction(
        action: String,
        params: JsonObject = jsonObjectOf(),
        confirmFirst: Boolean = false,
        danger: Boolean = false,
        confirmTitle: String = "确认",
        confirmText: String = "执行 $action ？",
        okText: String = "确认",
        reloadDelayMs: Long = 1200L,
    ) {
        if (confirmFirst) {
            val okc = Modal.confirm(title = confirmTitle, text = confirmText, okText = okText, danger = danger)
            if (!okc) return
        }
        try {
            Actions.run(
                path = "/docker/action",
                body = jsonObjectOf("action" to action, "params" to params),
                successToast = "操作成功",
                reload = { delay(reloadDelayMs); load() },
            )
        } catch (_: Throwable) {
            /* 失败时 Actions.run 已 toast；仍按 JS 习惯稍后刷新 */
            delay(reloadDelayMs)
            load()
        }
    }

    /** 容器日志：POST /logs/tail → Modal.output（JS `modal.output('容器日志 …', r.output)`）。 */
    suspend fun tailLogs(id: String) {
        try {
            val r = AppState.api.post("/logs/tail", jsonObjectOf("source" to "docker:$id"))
            Modal.output("容器日志 ${id.take(12)}", r.str("output"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppState.toast(e.message ?: "日志获取失败", ToastKind.Err)
        }
    }

    PageScaffold(title = "Docker 容器") {
        when {
            loading && data == null && error == null -> LoadingState()
            error != null && data == null -> ErrorState(message = error!!, onRetry = {
                scope.launch { loading = true; load() }
            })
            else -> {
                val d = data ?: JsonObject(emptyMap())
                val installed = d.bool("installed")

                /* ---------------- 未安装：一键安装（JS L14-30） ---------------- */
                if (!installed) {
                    PanelCard(title = "Docker", collapsible = false) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PanelIcon("docker", size = 42.dp, color = Palette.muted, stroke = 1.2f)
                            Spacer(Modifier.height(14.dp))
                            Text("服务器未安装 Docker 容器引擎。", color = Palette.muted, fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
                            PrimaryBtn(
                                text = "一键安装 Docker (docker.io)",
                                enabled = !installing,
                                onClick = {
                                    scope.launch {
                                        val okc = Modal.confirm(
                                            title = "安装 Docker",
                                            text = "将执行 apt-get install -y docker.io，约 1-3 分钟。",
                                            okText = "开始安装",
                                        )
                                        if (!okc) return@launch
                                        installing = true
                                        try {
                                            Actions.run(
                                                path = "/pkg/install",
                                                body = jsonObjectOf("pkg" to "docker.io"),
                                                title = "Docker 安装日志",
                                            )
                                            load()
                                        } finally {
                                            installing = false
                                        }
                                    }
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "安装约需 1-3 分钟（取决于网络），完成后此页面自动可用。",
                                color = Palette.muted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    return@PageScaffold
                }

                /* ---------------- 已安装：四磁贴 + 容器表 + 镜像/占用 ---------------- */
                val active = d.bool("active")
                val version = d.str("version")
                val containers = d.arr("containers").filterIsInstance<JsonObject>()
                val images = d.arr("images").filterIsInstance<JsonObject>()
                val stats = d.arr("stats").filterIsInstance<JsonObject>()
                val running = containers.count { it.bool("running") }

                GridLayout(columns = 4) {
                    StatTile(
                        label = "Docker 引擎",
                        value = if (active) "运行中" else "已停止",
                        sub = "v${version.ifEmpty { "?" }}",
                    )
                    StatTile(
                        label = "容器",
                        value = "${containers.size}",
                        sub = "$running 运行中 · ${containers.size - running} 停止",
                    )
                    StatTile(label = "镜像", value = "${images.size}", sub = "本地镜像数量")
                    StatTile(label = "维护", value = "—")
                }

                /* 引擎与维护操作（JS 四个 bind：daemon-start/stop/enable + prune） */
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        DangerBtn(text = "停止引擎", onClick = {
                            scope.launch {
                                dockerAction(
                                    "daemon-stop", confirmFirst = true, danger = true,
                                    confirmTitle = "确认", confirmText = "执行 daemon-stop ？",
                                )
                            }
                        })
                    } else {
                        GhostBtn(text = "启动引擎", icon = "play", onClick = {
                            scope.launch { dockerAction("daemon-start") }
                        })
                    }
                    GhostBtn(text = "开机自启", icon = "power", onClick = {
                        scope.launch { dockerAction("daemon-enable", reloadDelayMs = 0) }
                    })
                    DangerBtn(text = "清理未使用资源", icon = "trash", onClick = {
                        scope.launch {
                            dockerAction(
                                "prune", confirmFirst = true, danger = true,
                                confirmTitle = "确认", confirmText = "执行 prune ？",
                            )
                        }
                    })
                }

                PanelCard(
                    title = "容器列表",
                    right = { GhostBtn(text = "刷新", onClick = { scope.launch { loading = true; load() } }) },
                ) {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>("名称", weight = 1.4f, mono = true) {
                                Text(it.str("name"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>("ID", weight = 0.9f, mono = true) {
                                Text(it.str("id").take(12), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            },
                            TableCol<JsonObject>("镜像", weight = 1.6f, mono = true) {
                                Text(it.str("image").take(40), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>("状态", weight = 1.4f) {
                                val st = it.str("status").take(28)
                                LevelBadge(st, if (it.bool("running")) Level.Ok else Level.Muted)
                            },
                            TableCol<JsonObject>("端口", weight = 1.4f, mono = true) {
                                Text(
                                    it.str("ports").ifEmpty { "-" }.take(34),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Palette.muted,
                                    maxLines = 1,
                                )
                            },
                            TableCol<JsonObject>("操作", weight = 2.4f, minWidth = 250.dp) { c ->
                                val id = c.str("id")
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (c.bool("running")) {
                                        GhostBtn(text = "重启", onClick = {
                                            scope.launch {
                                                dockerAction(
                                                    "restart",
                                                    params = jsonObjectOf("target" to id),
                                                    confirmFirst = true,
                                                    confirmTitle = "重启 容器",
                                                    confirmText = "对容器 ${id.take(12)} 执行 docker restart ？",
                                                )
                                            }
                                        })
                                        DangerBtn(text = "停止", onClick = {
                                            scope.launch {
                                                dockerAction(
                                                    "stop",
                                                    params = jsonObjectOf("target" to id),
                                                    confirmFirst = true, danger = true,
                                                    confirmTitle = "停止 容器",
                                                    confirmText = "对容器 ${id.take(12)} 执行 docker stop ？",
                                                )
                                            }
                                        })
                                    } else {
                                        GhostBtn(text = "启动", icon = "play", onClick = {
                                            scope.launch {
                                                dockerAction(
                                                    "start",
                                                    params = jsonObjectOf("target" to id),
                                                    confirmFirst = true,
                                                    confirmTitle = "启动 容器",
                                                    confirmText = "对容器 ${id.take(12)} 执行 docker start ？",
                                                )
                                            }
                                        })
                                    }
                                    GhostBtn(text = "日志", icon = "logs", onClick = {
                                        scope.launch { tailLogs(id) }
                                    })
                                    DangerBtn(text = "删除", icon = "trash", onClick = {
                                        scope.launch {
                                            val okc = Modal.confirm(
                                                title = "删除容器",
                                                text = "将强制删除容器 ${c.str("name")}（docker rm -f），容器数据卷不受影响。",
                                                okText = "删除",
                                                danger = true,
                                            )
                                            if (!okc) return@launch
                                            dockerAction("rm", params = jsonObjectOf("target" to id))
                                        }
                                    })
                                }
                            },
                        ),
                        rows = containers,
                        rowKey = { it.str("id") },
                        emptyText = "暂无容器",
                    )
                }

                GridLayout(columns = 2) {
                    PanelCard(title = "镜像") {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>("镜像", weight = 2.4f, mono = true) {
                                    Text(it.str("repo").take(44), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<JsonObject>("大小", weight = 0.8f, align = TextAlign.End) {
                                    Text(it.str("size"))
                                },
                                TableCol<JsonObject>("创建于", weight = 1.2f) {
                                    Text(it.str("created"), color = Palette.muted)
                                },
                            ),
                            rows = images,
                            rowKey = { it.str("repo") + "#" + it.str("created") },
                            emptyText = "暂无镜像",
                            maxRows = 20,
                        )
                    }
                    PanelCard(title = "实时资源占用") {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>("容器", weight = 1.4f, mono = true) {
                                    Text(it.str("name"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<JsonObject>("CPU", weight = 0.8f, align = TextAlign.End) {
                                    Text(it.str("cpu"))
                                },
                                TableCol<JsonObject>("内存", weight = 1.1f, align = TextAlign.End) {
                                    Text(it.str("mem"))
                                },
                                TableCol<JsonObject>("MEM%", weight = 0.8f, align = TextAlign.End) {
                                    Text(it.str("memPct"))
                                },
                                TableCol<JsonObject>("网络 I/O", weight = 1f, align = TextAlign.End) {
                                    Text(it.str("net"))
                                },
                            ),
                            rows = stats,
                            rowKey = { it.str("name") },
                            emptyText = "无运行中容器",
                            maxRows = 20,
                        )
                    }
                }
            }
        }
    }
}
