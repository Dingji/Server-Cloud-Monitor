# 运维增强 API（`/panel/api/ops/…`）

面板内的 `server/ops/` 模块（仓库 `ops-server/`），补上商业运维软件有、原版面板没有的九类能力：
**可用性拨测（uptime/SLA）**、**自定义告警规则引擎**、**通知渠道推送**、**纳管主机台账**、
**完整文件管理器**、**安全基线巡检**、**磁盘治理**、**服务看护自愈**、**远程主机指标采集**。
认证沿用面板 Bearer token；未部署时端点 404，客户端整组降级为空态。

```
/opt/cloud-monitor/server/ops/
├── index.js      挂载入口（index.js 里一行 try/catch require）
├── store.js      原子 JSON 存储 + JSONL 轨迹 + 留存裁剪
├── exec.js       命令执行适配层（复用面板 agent 的 local/ssh 模式，可注入桩）
├── uptime.js     拨测探针（http/ssl/tcp/ping/dns）+ 调度 + SLA 聚合
├── alerting.js   规则引擎（阈值/持续/冷却/恢复）+ 静默与维护窗口
├── notify.js     渠道推送 + 失败退避重试 + 发送记录 + 凭据脱敏
├── hosts.js      纳管主机台账 + SSH 端口可达性/banner 探测
├── files.js      文件管理器（浏览/编辑/上传下载/权限/打包/搜索/回收站）
├── secops.js     安全基线巡检（23 项只读检查 + 评分 + 白名单修复）
├── disk.js       磁盘治理（容量/大文件/目录占用/日志 + 清理器 + 定时任务）
├── watchdog.js   服务看护自愈（systemd/进程/端口探活 + 有界重启）
├── remote.js     远程主机指标采集（固定只读命令束 over ssh2 + 历史）
send_mail.py      邮件通道用的 SMTP 发送器（服务器无 MTA，用 python3 smtplib）
```

挂载同时把 `hub.js` 的导出补了 `fireAlert`，因此自定义规则产生的告警会走面板原有的
告警缓冲区与 WebSocket —— 客户端铃铛、`GET /alerts`、历史列表都不用改。

## 拨测

| 类型 | 需要字段 | 判定 |
|---|---|---|
| `http` | url（必须带协议）、expectStatus、keyword | 状态码命中区间 **且** 响应含关键字 |
| `ssl` | host、port(默认 443) | 证书未过期，`certDays` 回传；低于 `certWarnDays` 计入「即将到期」 |
| `tcp` | host、port | 在 timeoutMs 内连上 |
| `ping` | host | `ping -c 4` 有回包，附带丢包率与平均 RTT |
| `dns` | host | `resolve4`（失败回落 `resolve6`）有记录 |

连续失败达到 `failThreshold` 才判不可用（防抖），产生 `down` 事件；恢复产生 `up` 事件。
两者都写入 `uptime_events.jsonl`、进面板铃铛、并按任务的 `channelIds` 推送。
结果按日聚合进 `uptime_rollup.json`（最多 120 天），SLA 可用率 = 1 − 失败次数/探测次数。

| 端点 | 说明 |
|---|---|
| `GET /ops/uptime/tasks` | 任务 + 实时状态（up/down/flapping/pending）+ 汇总 |
| `GET /ops/uptime/history?id=&limit=&events=` | 探测明细与事件 |
| `GET /ops/uptime/sla?days=7\|30` | 每任务可用率、平均耗时、失败次数、按日序列、最差项 |
| `POST /ops/uptime/task/save` | 新建/编辑（校验失败 400；保存后重置该任务探测状态） |
| `POST /ops/uptime/task/run` `{id}` | 立即探测一次 |
| `POST /ops/uptime/task/toggle` / `delete` | 停用/启用、删除 |
| `POST /ops/uptime/run` | 手动全量扫描 |

## 告警规则引擎

14 个可编排指标，字段名与面板采集器一致（见 `api-dump/metrics.json`、`disks.json`）：

`cpuPct`(cpu.totalPct) · `memPct`(memory.actualUsedPct) · `memAvailMb` · `swapPct` ·
`load1`(cpu.loadAvg[0]) · `diskMaxPct` · `diskAvailGb` · `diskReadBps` · `diskWriteBps` ·
`netRxKbps` · `netTxKbps` · `procsRunning` · `probeDown` · `notifyFailed`

> `diskMaxPct` / `diskAvailGb` 只统计 **≥1GiB 的真实卷**，忽略 `/boot`、`/boot/efi` 之类
> 常年"满"的小分区，否则会误报。

规则字段：`metric + op(>=,>,<=,<) + threshold + forSec(持续时长) + level(warn|bad|info)
+ cooldownSec + channelIds + notifyRecover`。引擎每 15 秒评估一轮（`CM_OPS_RULE_MS`）。

静默/维护窗口：`scope` = `*`（全部）/ `rule`（指定规则）/ `metric`（指定指标），
`kind` = `once`（startsAt–endsAt）或 `daily`（days + from–to，支持跨零点）。
被静默的规则状态为 `silenced`，不发送通知。

| 端点 | 说明 |
|---|---|
| `GET /ops/alerts/rules` | 规则 + 指标目录 + 引擎最近采样值 |
| `GET /ops/alerts/silences` / `events?limit=` | 窗口（带 active 标记）/ 事件流 |
| `POST /ops/alerts/rule/save\|delete\|toggle\|test` | `test` 用当前真实值试跑一次 |
| `POST /ops/alerts/silence/save\|delete` | |

## 通知渠道

| 类型 | cfg 字段 | 说明 |
|---|---|---|
| `webhook` | url, secret?, secretHeader? | JSON 载荷 + `X-Cloud-Monitor-Signature` HMAC-SHA256 |
| `wecom` | webhook, msgtype? | 企业微信 markdown 消息 |
| `dingtalk` | webhook, secret?, atMobiles?, atAll? | 加签（timestamp + HMAC-SHA256） |
| `feishu` | webhook, secret? | 交互卡片，按级别换色；加签 timestamp+sign |
| `telegram` | botToken, chatId, apiBase? | 可填代理 apiBase |
| `email` | host, port, security, user, password, from, to, python? | 走 `send_mail.py`（SSL/STARTTLS） |

安全处理：**接口回显一律脱敏**（密码/secret/token 显示 `••••••`，webhook URL 的
`key=`/`access_token=` 也遮蔽），并附 `secretHints`；把占位符回传不会覆盖已存凭据。
发送失败按 30s / 2min / 5min 退避重试最多 3 次，全部记入发送记录。

| 端点 | 说明 |
|---|---|
| `GET /ops/alerts/channels` | 类型目录 + 渠道（脱敏） |
| `GET /ops/notify/log?limit=` | 发送记录（渠道、级别、耗时、错误、来源） |
| `POST /ops/alerts/channel/save\|delete\|test` | `test` 立即发一条测试消息并返回 Markdown 预览 |
| `POST /ops/notify/send` | 手动广播（channelIds 省略=全部启用渠道） |

`channelIds` 语义：省略（不传）= 全部启用渠道；**空数组 = 只进面板铃铛、不外发**
（规则/拨测任务默认如此，避免新建就轰炸所有群）。

## 纳管主机（台账，无配额无计费）

| 端点 | 说明 |
|---|---|
| `GET /ops/hosts` | 清单 + 用途/环境字典 + 统计（可达 / 不通 / 未探测 / 无地址、按用途与环境聚合） |
| `POST /ops/host/save` | 登记或修改（label 必填；role/env 非法值回落默认；tags ≤12 个） |
| `POST /ops/host/delete` `{id}` | 移除台账 |
| `POST /ops/host/check` `{id?,timeoutMs?}` | 带 id 单台探测；不带 id 批量探测 |

可达性探测 = 打开 `addr:sshPort` 读第一行 banner（不发送任何凭据），因此顺带能看到对端
`SSH-2.0-OpenSSH_9.6` 这类版本串，便于排查升级。主机字段：
`label addr sshPort role env owner tags[] note panelUrl addedAt lastSeenAt lastCheckOk lastCheckError banner`。

## 文件管理器

读：`GET /ops/files/list?dir=`（条目 + 面包屑 + 书签 + df）、`stat?path=`、`tree?dir=&depth=`、
`preview?path=`、`search?dir=&q=&depth=`、`usage?dir=`、`trash`、`download?path=`（流式）。

写（全部 `audited()`）：`POST /ops/files/mkdir` `{dir,name}`、`touch` `{dir,name}` 或 `{paths[]}`、
`write` `{path,content}`、`rename` `{path,name}`、`move` `{paths[],to}`、`copy` `{paths[],to,name?}`、
`delete` `{paths[],purge?}`（默认进回收站）、`restore` `{ids[]}`、`trash/delete` `{ids[]}`、
`trash/empty`、`chmod` `{paths[],mode,recursive}`、`chown` `{paths[],owner,recursive}`、
`archive` `{paths[],name,to,kind}`、`extract` `{path,to}`、
`upload?dir=&name=&overwrite=1`（raw body，`express.raw`，≤64MB）。

路径与权限语义：

- 每个路径都过 `abs()`：拒绝控制字符、折叠 `..`、结果必须落在允许根内（默认整个文件系统，
  可用 `CM_FILES_ROOTS` 收窄）；名称字段禁止分隔符，`/` 与被回收站内部路径禁止被操作。
- 目录列表优先用 GNU `find -printf`（拿到 `drwxr-xr-x`、`root:root` 这类真实权限与属主），
  没有 find 时回落纯 fs（数字 uid/gid + 自算符号权限），所以 Windows 开发机也能跑。
- 回收站在 `<dataDir>/ops/files_trash/`，元数据 `index.json` 记录原路径，保留 30 天。
  `/tmp`（tmpfs）与数据目录不同盘时 `rename` 会 EXDEV，`hardMove()` 自动改为复制+删除。
- 面板本来就有 Web 终端（等同 root shell），文件管理器**不额外扩权**；能力边界与之一致。

## 安全基线巡检

**扫描是只读的**：23 项检查只跑 `sshd -T`、`ufw status`、`ss -ltn`、`apt list --upgradable`、
`find`、`stat`、`timedatectl` 一类查询命令，不改任何东西。缺二进制时该项标记 `skipped`（不计入扣分），
所以在 Windows 开发机 / 精简容器上也能返回报告。

| 分类 | 检查项 |
|---|---|
| 访问控制 | root 直登 · 密码登录 · MaxAuthTries · SSH 端口 · fail2ban |
| 网络暴露 | 防火墙状态 · 对外监听端口数 · 数据库/中间件端口对外暴露 · TLS 证书剩余天数 |
| 系统更新 | 待安装安全更新 · 自动安全更新 · 需要重启 |
| 账号与权限 | UID 0 账号 · 空密码账号 · authorized_keys 权限 · /etc 世界可写 · 异常 SUID · /tmp noexec |
| 日志与时间 | 时钟同步 · journal 占用 · logrotate |
| 运行健康 | 僵尸进程 · 近 3 天异常登录来源 |

按权重折算 0–100 分与 A–E 等级（`bad` 权重高、`warn` 折半），每次扫描落一条到 `secops_history.jsonl` 供趋势图使用。

修复走 **白名单**，非自由命令，且每条都声明风险与前置条件：

| fix id | 动作 | 安全处理 |
|---|---|---|
| `sshd.harden` | 禁密码登录 + root 仅密钥 + MaxAuthTries 4 | 写 `/etc/ssh/sshd_config.d/99-cloud-monitor.conf` drop-in（不覆盖原文件），`sshd -t` 校验通过才 reload，失败自动回滚 |
| `fail2ban.enable` | 建 jail 并启用服务 | 已有 `jail.d/defaults-debian.conf` 或 `jail.local` 就不再写 |
| `updates.enableAuto` | 开启 unattended-upgrades | 只改开关 |
| `firewall.enableSsh` | 启用 ufw | **先放行当前 SSH 端口与 80/443** 再 `--force enable`，避免把自己关在门外 |
| `perms.fixSshFiles` | 权限过宽的 `authorized_keys` 收紧为 600 | `find … -perm /077`，只改权限不改内容属主；结尾复查剩余数为 0 才算成功 |

| 端点 | 说明 |
|---|---|
| `GET /ops/sec/report` | 最近一次扫描结果（分数、等级、按分类分组） |
| `GET /ops/sec/checks` | 检查项与修复项目录（含 `fixable` 标记） |
| `GET /ops/sec/trend?days=30` | 历史得分 + 修复日志 |
| `POST /ops/sec/run` | 立即扫描一次（只读） |
| `POST /ops/sec/fix` `{fix}` | 执行白名单修复，记入 `secops_fixes.jsonl`；不在白名单 404，服务器缺对应二进制 400 |

## 磁盘治理

| 端点 | 说明 |
|---|---|
| `GET /ops/disk/overview` | ≥1GiB 真实卷 + inode 使用 + journal/apt/`/var/log`/回收站可回收量 |
| `GET /ops/disk/bigfiles?dir=&minMb=&limit=` | 大文件排行（默认 ≥100MB，`minMb` 夹在 10–100000） |
| `GET /ops/disk/topdirs?dir=&depth=` | 目录占用下钻（du） |
| `GET /ops/disk/logs?dir=` | 日志明细 + 已轮转份数 + 可回收估算 |
| `GET /ops/disk/history?mount=&days=` | 容量采样序列（每 30 分钟一次，保留 180 天） |
| `GET /ops/disk/events?limit=` | 清理执行记录 |
| `GET /ops/disk/schedule` | 清理器目录（含默认参数）+ 定时配置 |
| `POST /ops/disk/schedule/save` | 设定哪些清理器在几点跑（默认关闭，需显式启用） |
| `POST /ops/disk/clean` `{targets[],params}` | 手动执行清理器（非白名单目标 400） |
| `POST /ops/disk/run` | 立即按已启用配置跑一轮 |
| `POST /ops/disk/truncate` `{path}` | 清空单个日志（只允许 `CM_DISK_LOG_ROOTS` 内的目录，越界 403） |

5 个清理器：`journal`（vacuum-size/time，回显"清理前 → 现在"）、`apt`（clean + autoclean + autoremove --purge）、
`rotlogs`（30 天前的 `.gz/.1/.old/.xz`）、`tmp`（长期未访问文件，绕开 `systemd-private-*`）、
`docker`（悬空镜像与构建缓存，**不动卷**）。每次清理用 `df -Pk /` 的前后空闲差实测回收了多少，写进事件流，
避免"命令成功但没释放空间"的错觉。

`truncate` 的保护根默认 `/var/log,/opt,/srv,/www,/data,/home`（`CM_DISK_LOG_ROOTS` 可覆盖），
所以自检里对 `/etc/passwd` 清空会得到 403。

## 服务看护自愈

看护项三种探活方式，探测本身只读：`systemd`（`systemctl is-active`）、`process`（`pgrep -fc` 匹配数）、
`port`（`host:port` 能否连上）。重启动作受 **有界重试**约束：`windowMin` 分钟内最多 `maxRestarts` 次，
超过就转 `giveup` 并只告警不再拉起（防止 crash-loop 把机器拖死），`cooldownSec` 控制这条升级告警的最小间隔；
恢复时补一条 `ok` 级事件。非 systemd 的服务用面板自带的服务发现拼 `svcCmd`；两者都没有时记 `no-start-cmd`，
不乱执行命令。`channelIds` 默认空数组 = 只进面板铃铛不外发（与其它 ops 模块一致）。
状态变化通过 `alerting.raiseEvent` 进铃铛并按渠道推送，历史在 `watchdog_events.jsonl`。

| 端点 | 说明 |
|---|---|
| `GET /ops/watchdog` | 看护项 + 实时状态（ok/down/pending，giveup 单独标记）+ 统计（`failing`=已重启过仍 down、24h 重启次数）+ 事件 |
| `POST /ops/watchdog/save` | 新建/编辑（kind 与对应字段校验：systemd 要 unit、process 要 match、port 要 1–65535） |
| `POST /ops/watchdog/delete` / `toggle` | 移除 / 启停 |
| `POST /ops/watchdog/run` `{id, restart?}` | 立即探活；`restart=true` 才允许拉起，默认只观测 |
| `POST /ops/watchdog/reset` `{id?}` | 清空重启计数窗口（不带 id 清全部），解除 `giveup` |

## 远程主机指标采集

面板原本只看自己这台机器；本模块从纳管主机台账里取地址，用 `ssh2`（面板已有依赖）拉一组
**固定只读命令束**：`/proc/loadavg`、`free -b`、`df -Pk`、Top10 CPU 进程、对外监听端口、`uptime`、
发行版、`nproc`、常见服务 `is-active`。命令串不接受运维现场传入的 shell，避免把远程执行面扩大。

| 端点 | 说明 |
|---|---|
| `GET /ops/remote` | 主机 + 采集状态（待采集数、最近一次指标、runner 是否可用） |
| `GET /ops/remote/history?id=&hours=` | 单主机 load/内存/磁盘峰值时序 |
| `POST /ops/remote/cred` `{id,user?,port?,authMode?,keyPath?,password?}` | 存凭据（0600；回显只给 `hasPassword`，占位符回传不覆盖已存密码；未知主机 404） |
| `POST /ops/remote/cred/delete` `{id}` | 删除凭据 |
| `POST /ops/remote/collect` `{id?}` | 立即采集；不带 id 采集全部到期主机。未配凭据返回可读错误而不是崩 |

调度间隔 `CM_OPS_REMOTE_SEC`（默认 300 秒）只采集"已配凭据且到期"的主机；采集成功顺带更新台账的
`lastSeenAt/lastCheckOk`。历史写 `remote_history.jsonl`（4MB 折半）。

## 数据与调参

状态在 `/var/lib/cloud-panel/ops/`（0600，原子写）：`uptime_tasks.json` `uptime_state.json`
`uptime_rollup.json` `uptime_history.jsonl` `uptime_events.jsonl` `alert_rules.json`
`alert_state.json` `alert_silences.json` `alert_events.jsonl` `notify_channels.json`
`notify_log.jsonl` `hosts.json` `files_trash/`（+ `index.json`）`secops_scan.json` `secops_history.jsonl`
`secops_fixes.jsonl` `disk_schedule.json` `disk_events.jsonl` `disk_history.jsonl` `disk_last_sample.json`
`watchdog.json` `watchdog_state.json` `watchdog_events.jsonl` `remote_creds.json`
`remote_state.json` `remote_history.jsonl`。JSONL 超 4–8MB 自动折半，rollup 只留 120 天。

环境变量：`CM_OPS_DIR`、`CM_OPS_DISABLE_SCHEDULERS=1`（测试）、`CM_OPS_PROBE_MS`（拨测，默认 10s）、
`CM_OPS_RULE_MS`（规则，默认 15s）、`CM_OPS_WD_MS`（看护，默认 30s）、`CM_OPS_REMOTE_SEC`（远程采集，默认 300s）、
`CM_DISK_LOG_ROOTS`（允许清空日志的根目录）、`CM_FILES_ROOTS`（文件管理器允许根）。

## 验证

```bash
node tools/ops_test.js                # 181 项契约测试（假 fetch + 桩 collectors/hub + 注入 exec，不联网）
python tools/deploy_ops.py --dry-run  # 只读预检
python tools/deploy_ops.py            # 备份 → 上传 → 挂载 → 语法校验 → 重启
python tools/ops_smoke.py             # 公网链路总览 + 建/跑/删一个自检拨测
python tools/ops_smoke.py live        # 规则引擎在线验证（必然触发 → 面板铃铛命中）
python tools/ops_smoke.py files       # 文件管理器 24 步往返（只在 /tmp/cm-ops-selftest 内）
python tools/ops_smoke.py newops      # 巡检/磁盘/看护/远程只读自检（不执行修复与清理）
python tools/ops_smoke.py removed     # 确认许可证层已摘除、主机迁到 ops
python tools/deploy_ops.py --rollback
```

`newops` 自检刻意保持只读：`POST /ops/sec/run` 只扫描、`/ops/watchdog/run` 传 `restart=false`、
`/ops/disk/truncate` 故意指向 `/etc/passwd` 以确认 403 保护生效，因此可以在生产上重复跑。
