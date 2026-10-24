# page-docker — Docker 容器（docker）

## 概览

- `registerPage({ id:'docker', title:'Docker 容器' })`（`upstream/public/js/pages/docker.js:L5-L7`）。
- **一次性加载**：mount 拉 `GET /docker`（L11）；动作成功后 `location.reload()`；`刷新` 也是整页 reload。无自动轮询。
- **本机实测：docker 未安装** → `GET /docker` = `{"ts":…,"installed":false}`（`api-dump/docker.json`），页面显示「一键安装 Docker」引导（见下）。

## 接口调用

### GET /docker（mount）
- 两种形态：
  - 未安装：`{ts:number, installed:false}`（`collectors.js:L626`）。
  - 已安装：`{ts, installed:true, active:bool, version:string|null, containers:[], images:[], stats:[]}`；active=false 时后面四个数组为空（`collectors.js:L628-L629`）。
- 已安装时数组元素（`collectors.js:L637-L648`）：

| 数组 | 元素字段 |
|---|---|
| `containers[]` | `id:string`（截 12 显示）、`name`、`image`、`status:string`（如 `Up 3 hours`）、`ports`、`running:bool`（`/Up/i` 判定） |
| `images[]` | `repo:string`（`repo:tag`）、`size`、`created` |
| `stats[]` | `name`、`cpu:string`（如 `0.05%`）、`mem:string`（如 `12.3MiB / 1.9GiB`）、`memPct:string`、`net:string` |

### POST /pkg/install（一键安装）
- 请求体 `{pkg:"docker.io"}`；成功 `{ok, output}`；审计名 `pkg.install`（`index.js:L355`）。

### POST /docker/action
| action | 请求体 | 行为（`actions.js:L345-L369`） | 错误 |
|---|---|---|---|
| `start` / `stop` / `restart` | `{action, params:{target:<id或名>}}` | `docker <action> <target>`（60s 超时） | 400 `{"error":"无效容器 ID/名称"}` |
| `rm` | `{action, params:{target}}` | `docker rm -f <target>` | 同上 |
| `img-rm` | `{action, params:{ref}}` | `docker rmi <ref>` | 400 `{"error":"无效镜像名"}` |
| `daemon-start` / `daemon-stop` / `daemon-enable` | `{action, params:{}}` | systemctl start/stop/enable docker | – |
| `prune` | `{action, params:{}}` | `docker system prune -af \| tail -20`（300s 超时） | – |

审计名 `docker.action`（`index.js:L353`）。

### POST /logs/tail（容器日志）
- 请求体 `{source:"docker:<cid>"}`（`docker.js:L90`）；响应 `{output}`（`docker logs --tail 300`）；cid 须匹配 `^[a-f0-9]{6,64}$`，否则 400 `{"error":"未知日志源"}`（`logsources.js:L30-L34`）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `一键安装 Docker (docker.io)`（未安装分支，L18/L21-L28） | 确认框 `安装 Docker`（okText `开始安装`）→ `POST /pkg/install {pkg:'docker.io'}`，`showOutput:true, title:'Docker 安装日志'`，成功 `location.reload()` | busy 态 |
| `停止引擎` / `启动引擎`（L39/L128-L129） | 停止需确认 `确认` / `执行 <b>daemon-stop</b>？`（danger）→ `POST /docker/action {action:'daemon-stop'}` + reload（1200ms） | – |
| `开机自启`（L40/L130） | `{action:'daemon-enable'}` + reload | – |
| `清理未使用资源`（L45/L131，danger） | 确认 `执行 <b>prune</b>？` → `{action:'prune'}` + reload | – |
| `刷新`（L49/L132） | `location.reload()` | – |
| 容器行 `重启` / `停止` / `启动`（L72-L74/L79-L86） | 确认框 `<act> 容器` / `对容器 <id前12> 执行 <b>docker <act></b>？`（stop 为 danger）→ `{action:act, params:{target:id}}` + reload（1200ms） | 按 `running` 切换显示 |
| 容器行 `日志`（L75/L87-L94） | `POST /logs/tail {source:'docker:'+id}` → `modal.output('容器日志 <id前12>', r.output)` | 失败 toast |
| 容器行 `删除`（L76/L95-L101） | 确认框 `删除容器` / `将强制删除容器 <name>（docker rm -f），容器数据卷不受影响。`（danger, okText `删除`）→ `{action:'rm', params:{target:id}}` + reload | – |

> 注：页面未暴露 `img-rm`（后端支持）；`stats`/`images` 表无行内动作。

## UI 分区

1. **未安装分支**（L14-L30）：居中卡片：docker 图标（42px）、`服务器未安装 Docker 容器引擎。`、主按钮 `一键安装 Docker (docker.io)`、说明 `安装约需 1-3 分钟（取决于网络），完成后此页面自动可用。`
2. **统计四联卡**（已安装，L36-L46）：`Docker 引擎`（`运行中`绿/`已停止`红，副 `v<version>`，按钮 停止引擎/启动引擎 + 开机自启）、`容器`（总数，副 `N 运行中 · N 停止`）、`镜像`（总数，副 `本地镜像数量`）、`维护`（值 `—`，按钮 `清理未使用资源` 红）。
3. **容器列表卡**（L48-L51、表 L65-L78）：列 `名称`、`ID`（截 12）、`镜像`（截 40）、`状态`（running→绿徽章 / 停止→灰徽章，`status` 截 28 字）、`端口`（截 34 字，空 `-`）、`操作`（重启/停止 或 启动 + `日志` + `删除` 红）。
4. **镜像卡**（L54-L57、表 L105-L109）：列 `镜像`（截 44 字）、`大小`、`创建于`；最大高 260px。
5. **实时资源占用卡**（L58-L61、表 L111-L119）：列 `容器`、`CPU`、`内存`、`MEM%`、`网络 I/O`；空 → `无运行中容器`；最大高 260px。

## 实现提示与边界

- `stats` 来自 `docker stats --no-stream`（一次性抓取），**不是**流式；页面也不轮询 —— KMP 端同样按“进入页面抓一次”实现。
- 容器 ID 在按钮 data 属性里用完整值，显示截 12 位（L67、L82）。
- `running` 判定在服务端（`/Up/i`），前端只信该布尔（L69、L72）。
- 未安装分支要能被 `POST /pkg/install` 长任务（可能 1-3 分钟）阻塞 —— KMP 建议用通知/进度展示，完成后刷新页面。
- 本机当前处于未安装分支：验收时「一键安装」是唯一可见动作（不要真的在生产服务器上点它）。
