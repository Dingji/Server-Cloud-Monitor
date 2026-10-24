package com.servermaintance.panel.pages
import com.servermaintance.panel.i18n.trf
import com.servermaintance.panel.i18n.L
import com.servermaintance.panel.i18n.tr

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
            error = e.message ?: e::class.simpleName ?: L["请求失败_8fdc4112a4"]
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
        confirmTitle: String = L["确认_b56d9ac6c5"],
        confirmText: String = trf("执行_0_70b4feea4a", action),
        okText: String = L["确认_b56d9ac6c5"],
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
                successToast = L["操作成功_3c5be5f2a1"],
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
            Modal.output(trf("容器日志_0_176db5f47c", id.take(12)), r.str("output"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppState.toast(e.message ?: tr("日志获取失败_47449d85ad"), ToastKind.Err)
        }
    }

    PageScaffold(title = L["nav.docker"]) {
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
                            Text(L["服务器未安装_docker_容器引擎_84468c43e1"], color = Palette.muted, fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
                            PrimaryBtn(
                                text = L["一键安装_docker_docker_io_d4c1d88eaa"],
                                enabled = !installing,
                                onClick = {
                                    scope.launch {
                                        val okc = Modal.confirm(
                                            title = L["安装_docker_f131e1cdff"],
                                            text = L["将执行_apt_get_install_y_d_5218b05b7a"],
                                            okText = L["开始安装_d109e7cc01"],
                                        )
                                        if (!okc) return@launch
                                        installing = true
                                        try {
                                            Actions.run(
                                                path = "/pkg/install",
                                                body = jsonObjectOf("pkg" to "docker.io"),
                                                title = L["docker_安装日志_08ccdea4e9"],
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
                                L["安装约需_1_3_分钟_取决于网络_完成后此页_75a815fda1"],
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
                        label = L["docker_引擎_755076c561"],
                        value = if (active) L["运行中_5942497005"] else L["已停止_75dddf524e"],
                        sub = "v${version.ifEmpty { "?" }}",
                    )
                    StatTile(
                        label = L["容器_d179a3480d"],
                        value = "${containers.size}",
                        sub = trf("0_运行中_1_停止_3f10fff62d", running, containers.size - running),
                    )
                    StatTile(label = L["镜像_e64cd65578"], value = "${images.size}", sub = L["本地镜像数量_019cf81efd"])
                    StatTile(label = L["维护_72527e2f0e"], value = "—")
                }

                /* 引擎与维护操作（JS 四个 bind：daemon-start/stop/enable + prune） */
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        DangerBtn(text = L["停止引擎_f83e27f9a5"], onClick = {
                            scope.launch {
                                dockerAction(
                                    "daemon-stop", confirmFirst = true, danger = true,
                                    confirmTitle = L["确认_b56d9ac6c5"], confirmText = L["执行_daemon_stop_dfe266a216"],
                                )
                            }
                        })
                    } else {
                        GhostBtn(text = L["启动引擎_193b25d8af"], icon = "play", onClick = {
                            scope.launch { dockerAction("daemon-start") }
                        })
                    }
                    GhostBtn(text = L["开机自启_e0f2218651"], icon = "power", onClick = {
                        scope.launch { dockerAction("daemon-enable", reloadDelayMs = 0) }
                    })
                    DangerBtn(text = L["清理未使用资源_aef1da41a9"], icon = "trash", onClick = {
                        scope.launch {
                            dockerAction(
                                "prune", confirmFirst = true, danger = true,
                                confirmTitle = L["确认_b56d9ac6c5"], confirmText = L["执行_prune_6b4f1926c7"],
                            )
                        }
                    })
                }

                PanelCard(
                    title = L["容器列表_6ee925b478"],
                    right = { GhostBtn(text = L["刷新_38108eaa1d"], onClick = { scope.launch { loading = true; load() } }) },
                ) {
                    DataTable(
                        columns = listOf(
                            TableCol<JsonObject>(L["名称_1be7ae4fc2"], weight = 1.4f, mono = true) {
                                Text(it.str("name"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>("ID", weight = 0.9f, mono = true) {
                                Text(it.str("id").take(12), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            },
                            TableCol<JsonObject>(L["镜像_e64cd65578"], weight = 1.6f, mono = true) {
                                Text(it.str("image").take(40), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                            },
                            TableCol<JsonObject>(L["状态_62e951a692"], weight = 1.4f) {
                                val st = it.str("status").take(28)
                                LevelBadge(st, if (it.bool("running")) Level.Ok else Level.Muted)
                            },
                            TableCol<JsonObject>(L["端口_6cbb7335b5"], weight = 1.4f, mono = true) {
                                Text(
                                    it.str("ports").ifEmpty { "-" }.take(34),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Palette.muted,
                                    maxLines = 1,
                                )
                            },
                            TableCol<JsonObject>(L["操作_f3ea6d345e"], weight = 2.4f, minWidth = 250.dp) { c ->
                                val id = c.str("id")
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (c.bool("running")) {
                                        GhostBtn(text = L["重启_385c272e35"], onClick = {
                                            scope.launch {
                                                dockerAction(
                                                    "restart",
                                                    params = jsonObjectOf("target" to id),
                                                    confirmFirst = true,
                                                    confirmTitle = L["重启_容器_450f2d207a"],
                                                    confirmText = trf("对容器_0_执行_docker_restar_c942faf88e", id.take(12)),
                                                )
                                            }
                                        })
                                        DangerBtn(text = L["停止_a17f70a8d3"], onClick = {
                                            scope.launch {
                                                dockerAction(
                                                    "stop",
                                                    params = jsonObjectOf("target" to id),
                                                    confirmFirst = true, danger = true,
                                                    confirmTitle = L["停止_容器_c97111b01f"],
                                                    confirmText = trf("对容器_0_执行_docker_stop_02eec7a6e4", id.take(12)),
                                                )
                                            }
                                        })
                                    } else {
                                        GhostBtn(text = L["启动_ebd26da421"], icon = "play", onClick = {
                                            scope.launch {
                                                dockerAction(
                                                    "start",
                                                    params = jsonObjectOf("target" to id),
                                                    confirmFirst = true,
                                                    confirmTitle = L["启动_容器_76c73301e8"],
                                                    confirmText = trf("对容器_0_执行_docker_start_a6406fd5d5", id.take(12)),
                                                )
                                            }
                                        })
                                    }
                                    GhostBtn(text = L["日志_4de50894b8"], icon = "logs", onClick = {
                                        scope.launch { tailLogs(id) }
                                    })
                                    DangerBtn(text = L["删除_3755f56f2f"], icon = "trash", onClick = {
                                        scope.launch {
                                            val okc = Modal.confirm(
                                                title = L["删除容器_c1cbe14e79"],
                                                text = trf("将强制删除容器_0_docker_rm_f_e5e8832e8e", c.str("name")),
                                                okText = L["删除_3755f56f2f"],
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
                        emptyText = L["暂无容器_96a29bf063"],
                    )
                }

                GridLayout(columns = 2) {
                    PanelCard(title = L["镜像_e64cd65578"]) {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(L["镜像_e64cd65578"], weight = 2.4f, mono = true) {
                                    Text(it.str("repo").take(44), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<JsonObject>(L["大小_fd20702c73"], weight = 0.8f, align = TextAlign.End) {
                                    Text(it.str("size"))
                                },
                                TableCol<JsonObject>(L["创建于_f3b01f3f1a"], weight = 1.2f) {
                                    Text(it.str("created"), color = Palette.muted)
                                },
                            ),
                            rows = images,
                            rowKey = { it.str("repo") + "#" + it.str("created") },
                            emptyText = L["暂无镜像_dd1ca5cd99"],
                            maxRows = 20,
                        )
                    }
                    PanelCard(title = L["实时资源占用_c8bda73ba6"]) {
                        DataTable(
                            columns = listOf(
                                TableCol<JsonObject>(L["容器_d179a3480d"], weight = 1.4f, mono = true) {
                                    Text(it.str("name"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                                },
                                TableCol<JsonObject>("CPU", weight = 0.8f, align = TextAlign.End) {
                                    Text(it.str("cpu"))
                                },
                                TableCol<JsonObject>(L["内存_d014ab7703"], weight = 1.1f, align = TextAlign.End) {
                                    Text(it.str("mem"))
                                },
                                TableCol<JsonObject>(L["mem_8c4bf90fbc"], weight = 0.8f, align = TextAlign.End) {
                                    Text(it.str("memPct"))
                                },
                                TableCol<JsonObject>(L["网络_i_o_a51f84968c"], weight = 1f, align = TextAlign.End) {
                                    Text(it.str("net"))
                                },
                            ),
                            rows = stats,
                            rowKey = { it.str("name") },
                            emptyText = L["无运行中容器_85fb788d24"],
                            maxRows = 20,
                        )
                    }
                }
            }
        }
    }
}
