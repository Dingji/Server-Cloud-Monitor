# page-disks — 磁盘管理（disks）

## 概览

- `registerPage({ id:'disks', title:'磁盘管理' })`（`upstream/public/js/pages/disks.js:L16-L18`）。
- **REST 轮询** `CM.autoRefresh(load, {pageId:'disks'})`（L109）+ **WS 实时 I/O**（`metrics` 消息的 `diskio` 只在本页时刷新，L100-L107）。
- 服务端 `/disks` 有 5 秒缓存（`collectors.js:L165-L168`），`GET /disks` 走 `getDisks(true)` 强制刷新（`index.js:L193`）。

## 接口调用

### GET /disks（轮询/刷新）
- 触发：mount（L99）、`刷新`（L79）、自动刷新（L109）、任何维护动作完成后（L93）。
- 响应（样本 `api-dump/disks.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `ts` | number(ms) | 采集时间 |
| `mounts[]` | array | 挂载点列表，元素：`dev:string`、`total:number(B)`、`used:number(B)`、`avail:number(B)`、`pct:number`、`mount:string`、`itotal/iused/iavail:number`、`ipct:number`（inode，df -i 合并，`collectors.js:L182-L195`） |
| `blocks[]` | array | lsblk 扁平化：`name:string`（带缩进空格表示层级）、`size`、`type`（`part`/`disk`/`lvm`…）、`fstype`、`mountpoint`（多个用 `, ` 连接） |
| `overallPct` | number | 根分区使用率（`mounts` 中 `/` 或第一个） |

### WS /ws — `metrics`（仅 I/O 面板）
- `msg.data.diskio` → `读取速率/写入速率/读取 IOPS/写入 IOPS`（`disks.js:L102-L106`；全局只注册一次 `CM._diskIoSub`，当前页必须是 disks）。

### POST /disk/action（维护动作）
- 请求体 `{action}`，本页暴露 7 个（`disks.js:L6-L14`）：`apt-clean`、`apt-autoremove`、`journal-vacuum`、`fstrim`、`drop-caches`、`docker-prune`、`analyze`。
  （后端还支持未暴露的：`pkg-clean`、`pkg-update`、`pkg-autoremove`、`apt-clean`、`apt-autoremove`、`journal-vacuum`、`fstrim`、`drop-caches`、`docker-prune`、`analyze`，见 `actions.js:L56-L70`。）
- 成功：`{ok:true, output:"<命令输出>"}`（可能为空输出则 `'(无输出)'`，`actions.js:L83`）。
- 错误：400 `{"error":"未知磁盘操作: <action>"}`；命令失败 400 `{error:"命令失败 (exit N):\n…", output:"…"}`；未检测到包管理器 → `{"error":"未检测到受支持的包管理器（apt/dnf/yum/zypper/apk/pacman）"}`（仅 dynamic 动作）。
- 审计名 `disk.action`（`index.js:L225`）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `自动刷新`（L24）/ `刷新`（L26） | `GET /disks` | – |
| `清理 apt 缓存`（L7） | `POST /disk/action {action:"apt-clean"}` | 确认框：标题 `清理 apt 缓存？`，正文 `删除 /var/cache/apt/archives 下的已下载安装包`，okText `执行` |
| `自动卸载无用包`（L8，danger） | `{action:"apt-autoremove"}` | okText `我明白风险，执行` |
| `裁剪系统日志`（L9） | `{action:"journal-vacuum"}` | – |
| `执行 TRIM`（L10） | `{action:"fstrim"}` | – |
| `释放内存缓存`（L11，danger） | `{action:"drop-caches"}` | – |
| `Docker 清理`（L12，danger） | `{action:"docker-prune"}` | 本机 docker 未安装 → 命令失败 400 |
| `磁盘占用分析`（L13） | `{action:"analyze"}` | 成功后 `modal.output('磁盘占用分析', r.output)`（**不弹 toast**，L91）；其它动作成功 → toast `操作完成` + `modal.output(标签, r.output)`（L92） |
| 动作失败 | toast `e.message`(6s) + 有 `output` 时 `modal.output('<标签> 失败', e.output, {error:true})`（L94） | – |

统一确认框：标题 `<label>？`、正文 = 动作 `desc`、danger 动作用 `okText:'我明白风险，执行'` 否则 `'执行'`（L86）；按钮 busy 态（L88、L95）。

## UI 分区

1. **统计四联卡**（`grid g4`，L21/L51-L55）：`根分区使用率`（值 + 进度条 + `used / total`）、`可用空间`（`avail`，副 `/ 分区`）、`inode 使用率`（根分区 `ipct%`，副 `小文件数量占比`）、`分区数量`（`mounts.length`，副 `物理挂载点`）。
2. **分区与挂载点卡**（L22-L29、表 L57-L65）：列 `设备`(mono)、`挂载点`(mono)、`总容量`、`已用`、`可用`（均 fmt.bytes）、`使用率`（进度条 + `pct%`）、`inode`（>90 红色，缺失 `-`）。
3. **块设备 (lsblk) 卡**（L31-L34、表 L69-L76）：列 `设备`、`大小`、`类型`、`文件系统`、`挂载点`；空 → `无块设备信息`。
4. **实时磁盘 I/O 卡**（L35-L38）：键值对 `读取速率`、`写入速率`（fmt.bps）、`读取 IOPS`、`写入 IOPS`（1 位小数），由 WS `diskio` 驱动。
5. **磁盘维护操作卡**（L40-L44）：7 个按钮（danger 三个为红），按钮 title = 动作 desc；底部说明 `危险操作会弹出确认框。所有操作都会写入审计日志。`

## 实现提示与边界

- 输出弹窗为主：所有维护动作都弹 `modal.output`（含「复制」「关闭」，成功/失败徽章），KMP 需要等价的输出查看器（等宽、可复制）。
- `analyze` 是只读动作但同样审计（`disk.action`）。
- I/O 面板只认当前页（pageId gate），离开页面停更（`hide()` 还会清空 `els`，L119）。
- `mounts` 里根分区优先取 `mount === '/'`；`overallPct` 服务端已给，但本页自己算 rootD。
- 确认框与输出框都会阻塞自动刷新（core.js `guarded()`），KMP 端建议同样处理。
