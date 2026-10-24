# Cloud Monitor 面板 · 可移植部署包

把 `8.153.153.176` 上那套面板（**含本项目加的 9 个运维模块**：拨测、告警规则、通知渠道、
纳管主机、文件管理器、安全基线巡检、磁盘治理、服务看护、远程采集）整体搬到另一台
Ubuntu/Debian 服务器上直接跑起来。

包根目录（本机）：`deploy/cloud-monitor-panel/`

```
deploy/cloud-monitor-panel/
├── install.sh                 # 目标机执行：预检 → 装 Node22 → 落代码 → systemd → nginx → 冒烟
├── uninstall.sh               # 停服务/删单元/删本脚本生成的 nginx（默认保留代码与数据）
├── deploy-remote.sh           # 从你的电脑推送到目标机并远程执行 install.sh
├── README.md                  # 本文件
├── MANIFEST.remote-md5.txt    # 源机代码逐文件 md5（拉取时已核对，61 个文件 0 差异）
├── MANIFEST.env.txt           # 源机与 Node 版本
├── app/                       # 面板本体 = 部署到 /opt/cloud-monitor 的内容
│   ├── server/                #   index.js + 20 个模块 + ops/ 13 个文件（含 send_mail.py）
│   ├── public/                #   Web 壳（浏览器打开 /panel/ 看的那套页面）
│   ├── package.json
│   └── package-lock.json
└── vendor/
    ├── node_modules.tar.gz    # 离线依赖 3.3MB（1226 个文件，纯 JS）
    └── node_modules.README.txt
```

## 一、包里有什么、有意不有什么

**有**：面板代码、Web 静态壳、依赖（离线）、安装/卸载脚本、systemd 与 nginx 生成逻辑。

**没有**（这些都是源机器的状态或秘密，不该跟着包跑）：

| 不打包的东西 | 原因 |
|---|---|
| `config.json` | 里面有登录口令的 scrypt 哈希、SSH 口令、PostgreSQL/MySQL/Redis/Mongo 口令 |
| `keys/pty_key` | 源机的终端宿主私钥；目标机由 `install.sh` 用 `ssh-keygen` 现场生成 |
| `backups/`、`logs/`、`tmp/` | 源机的备份与审计日志 |
| `runtime/`（205MB） | 源机上的一份工具链副本，面板运行不依赖它 |
| `/var/lib/cloud-panel/` | 数据目录：指标库、备份、以及 ops 的拨测任务/告警规则/通知渠道/主机台账/回收站 |

所以新机器装完是**干净面板**：拨测任务、告警规则、通知渠道、纳管主机都要重新配。
需要把源机的这些配置带过去，手动复制数据目录即可（见「六、迁移配置」）。

**依赖里没有原生模块**：`ssh2` 自带两个可选的 `.node` 加速件（`cpu-features`、`sshcrypto`），
与机器/架构绑定，已在打包时剔除。源机上实测：删掉后 `ssh2` 走纯 JS 实现，真实 SSH 连接与
命令执行都正常（远程采集不受影响）。想要原生加速就在目标机 `cd /opt/cloud-monitor && npm rebuild ssh2`
（需要 gcc/make），非必需。

## 二、前置条件

| 项 | 要求 |
|---|---|
| 系统 | Ubuntu 20.04+/Debian 11+（面板动作类功能走 `apt`/`systemctl`/`ufw`，CentOS 系只能看指标） |
| 权限 | root（写 systemd、nginx、`/opt`） |
| Node | **>= 22**（`metricsstore` 用内置 `node:sqlite`）。脚本会在 Ubuntu/Debian 上经 NodeSource 自动装 |
| 端口 | 默认 `127.0.0.1:9000`，只回环；要外网请走 nginx 或 `--direct` |
| 磁盘 | 约 80MB 代码 + 依赖；数据目录按留存策略增长 |
| 内存 | systemd 侧 `MemoryHigh=256M / MemoryMax=400M`，与源机一致 |
| 外网 | 只有「自动装 Node」和「`npm ci` 回落」需要；依赖已离线随包 |

## 三、部署方法

### 方式 A：从你的电脑一键推送（推荐）

```bash
cd deploy/cloud-monitor-panel

# 密钥登录（推荐；非默认路径的私钥用 SM_KEY 指定）
./deploy-remote.sh root@新服务器IP
SM_KEY=~/.ssh/my_key ./deploy-remote.sh root@新服务器IP

# 只有口令登录时（本机需装 sshpass；多数服务器禁口令登录，优先用密钥）
SM_PASS='新机器口令' ./deploy-remote.sh root@新服务器IP

# 非 root + sudo（sudoers 需 NOPASSWD）
./deploy-remote.sh deploy@新服务器IP --sudo

# 先看会做什么，不落地
./deploy-remote.sh root@新服务器IP --dry-run
```

它会打一个 tar 包推到 `/tmp/cloud-monitor-panel-<时间戳>/`，然后远端跑 `install.sh`。
所有转给 `install.sh` 的参数都经 `printf %q` 转义后才拼进远端命令串，不会被 shell 二次解释。

### 方式 B：手动拷过去

```bash
# 1) 在本机打个单文件包
tar -czf cloud-monitor-panel.tar.gz -C deploy cloud-monitor-panel

# 2) 传到目标机并解包
scp cloud-monitor-panel.tar.gz root@新服务器IP:/tmp/
ssh root@新服务器IP
tar -xzf /tmp/cloud-monitor-panel.tar.gz -C /tmp && cd /tmp/cloud-monitor-panel

# 3) 安装
bash install.sh
```

### 方式 C：同一台机器再开一个实例（本项目自测用的就是这条）

```bash
bash install.sh --prefix /opt/cm-second --data-dir /var/lib/cm-second \
                --port 9100 --service cm-second --no-nginx --no-systemd
```

## 四、`install.sh` 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--prefix DIR` | `/opt/cloud-monitor` | 代码目录 |
| `--data-dir DIR` | `/var/lib/cloud-panel` | 数据目录（`CM_DATA_DIR`），0700 |
| `--port N` | `9000` | 监听端口（`CM_PORT`） |
| `--bind ADDR` | `127.0.0.1` | 监听地址（`CM_BIND`）；`--direct` 会改成 `0.0.0.0` |
| `--base-path /x` | `/panel` | URL 前缀，需与客户端一致 |
| `--service NAME` | `cloud-monitor` | systemd 服务名（同机多实例时务必改） |
| `--password '...'` | 随机生成 | 只用于首次启动；哈希落进 `config.json` 后临时 env 文件即被删除 |
| `--node auto\|skip` | `auto` | `skip` = 不动 Node（自己已装好） |
| `--no-nginx` | — | 完全不碰 nginx |
| `--nginx-standalone PORT` | — | 生成独立 server 块（默认 80），避开现有 `:80 default_server` 冲突 |
| `--direct` | — | 绑 `0.0.0.0` 且不装 nginx，公网直连 |
| `--no-systemd` / `--no-start` / `--no-smoke` | — | 不写单元 / 不启动 / 不自检 |
| `--dry-run` | — | 只打印计划 |

nginx 默认模式（`auto`）的行为是**安全的**：只写一个片段
`/etc/nginx/snippets/cloud-monitor-location.conf`，**不改你的站点文件**，打印一行 `include` 让你自己加。
如果目标机是干净的新 nginx，直接用 `--nginx-standalone 8091` 最省事。

## 五、装完之后

脚本末尾会打印入口地址和（首次生成的）登录口令。登录方式两种：

- 浏览器：`http://<入口地址>/panel/`
- 本项目 KMP 客户端：登录页把服务器地址改成同一入口（`scheme://host`，端口不是 80 时带上），
  **不需要重新编译客户端**——地址是运行时配置的。

安全须知（面板 = 服务器上的 root 能力，务必看完）：

1. 默认只绑 `127.0.0.1`。远程访问优先用 nginx + HTTPS + 限源，或临时 `ssh -L 8080:127.0.0.1:9000 主机`。
2. `--direct` 会把面板暴露到公网，请用云安全组把端口限制到办公 IP。
3. 首次登录后**立刻在「面板设置」改口令**（口令文件 `/etc/cloud-monitor.env` 已被脚本删除，
   `config.json` 里只剩 scrypt 哈希，权限 0600）。
4. 登录连错 8 次锁 10 分钟；API 限流 1800 次/分钟/IP。
5. ops 模块（文件管理器、终端）能改文件、能执行服务操作，能力边界与面板自带 Web 终端一致。

运维命令：

```bash
systemctl status cloud-monitor
journalctl -u cloud-monitor -f
curl -s -X POST http://127.0.0.1:9000/panel/api/login -H 'Content-Type: application/json' \
     -d '{"password":"你的口令"}' | head -c 120        # 拿 token
```

## 六、迁移配置（可选）

想把源机的拨测任务/告警规则/通知渠道/纳管主机/巡检历史带到新机：

```bash
# 源机
tar -czf /tmp/ops-state.tar.gz -C /var/lib/cloud-panel ops
# 新机（install.sh 跑完之后）
mkdir -p /var/lib/cloud-panel && tar -xzf /tmp/ops-state.tar.gz -C /var/lib/cloud-panel
systemctl restart cloud-monitor
```

注意：`ops/notify_channels.json` 里含渠道 secret（脱敏只在接口回显层），
`ops/remote_creds.json` 里含 SSH 口令/私钥路径。跨机搬运请把它们当作口令对待，
传完即删中间 tar 包；更稳妥的做法是新机重新录入。

## 七、升级与回滚

- **升级**：重跑 `install.sh`。旧 `server/` 会先备份到 `/opt/cloud-monitor/.backup-<时间戳>/`，
  `config.json`、`keys/`、`node_modules`、数据目录都不动。
- **回滚**：
  ```bash
  rm -rf /opt/cloud-monitor/server
  cp -a /opt/cloud-monitor/.backup-<时间戳>/server /opt/cloud-monitor/
  systemctl restart cloud-monitor
  ```
- **卸载**：`./uninstall.sh`（保留代码与数据）· `./uninstall.sh --purge-app --purge-data`

## 八、排查

| 现象 | 原因与处理 |
|---|---|
| 启动即退出，日志 `EADDRINUSE` | 端口被占：`ss -ltnp \| grep :9000`，或换 `--port` |
| `无法监听 ... EACCES` | 绑 <1024 端口且无权限；换端口或给单元加 `AmbientCapabilities=CAP_NET_BIND_SERVICE` |
| 日志出现 `node:sqlite 不可用` | Node < 22；装 22 后重启服务（其余功能不受影响，只是历史指标不入库） |
| `ops modules not mounted: ...` | ops 文件没解全或语法错；`node --check /opt/cloud-monitor/server/ops/index.js` |
| 页面能开但顶栏全空/整组报错 | 客户端地址或 `basePath` 不一致（`config.json` 的 `basePath` 要等于 URL 前缀） |
| WebSocket 连不上、终端黑屏 | nginx 少了 `$connection_upgrade` 的 map，或代理没转发 `Upgrade` 头 |
| 429 请求过于频繁 | 单 IP 每分钟 1800 次上限，属正常保护 |
| 登录 500 | 面板登录处理器对失败也返回 500（上游行为）；先 `journalctl -u cloud-monitor` 看是否锁定 |
| `apt`/服务/包管理页报错 | 目标机不是 Debian 系，或没装 `apt`；这类功能是发行版相关的 |

## 九、这个包是怎么来的

`python tools/build_panel_package.py`（仓库根目录）会：连源机 → 只读 tar `server/ public/
package*.json`（排除 `server/keys`、`page_test.js`）与 `node_modules`（剔除两个原生加速件）→
本地组装本目录 → **逐文件比对 md5** → 扫描包内是否混入 `config.json`/私钥/口令哈希，命中即失败。

源机信息见 `MANIFEST.env.txt`，代码指纹见 `MANIFEST.remote-md5.txt`。

## 十、这个包已经验证到什么程度

自测命令（在源机上用**独立前缀/端口/数据目录**跑，不碰生产服务，跑完自动清理）：

```bash
python tools/test_panel_package.py
```

最近一次结果 —— 33 项全部符合预期（rc=0）：

| 环节 | 结果 |
|---|---|
| 包完整性 | 61 个源文件与源机 **md5 逐文件一致**；包内扫描确认没有 `config.json`/私钥/口令哈希 |
| install.sh | rc=0；代码 + ops 13 文件 + 静态壳 + `node_modules` + 现场生成的 `keys/pty_key` + 数据目录全部到位；依赖里确认 **0 个 `.node`** |
| Node 探测 | `node:sqlite 可用（历史指标入库正常）`（Node 22.20） |
| 启动 | 新实例日志 `Cloud Monitor listening on http://127.0.0.1:9101/panel`，进程 cwd 就是测试前缀，无 `ops modules not mounted` |
| 端点 | 登录拿 token；`/me`、`/ops/overview`（九模块聚合）、`/ops/sec/checks`、`/ops/disk/overview`、`/ops/watchdog`、`/ops/remote`、`/ops/files/list`、`/ops/uptime/tasks`、`/ops/alerts/rules`、`/ops/hosts` 全 200 |
| 静态与实时 | `public/index.html` 200；`/panel/vendor/{chart.js,xterm.js,xterm.css,addon-fit.js}` 由解包出来的 `node_modules` 正常供出；`/panel/ws` 握手 **101**（错误口令 401） |
| 隔离性 | 前后对比 `systemctl is-active cloud-monitor` 与生产 `config.json` 的 md5 **无变化**；清理后端口释放、测试目录删净 |
| deploy-remote.sh | 用本地 `ssh` 桩验证三段远端命令拼装；带 `;`、`&&`、空格的口令参数被 `printf %q` 封成单个 argv，注入不生效 |

**未覆盖**：目标机的 NodeSource 自动装 Node（源机已满足条件，走的是跳过分支）、nginx `--nginx-standalone`
真实 reload（源机的 :80 已有 default_server，自测刻意不碰）、systemd 单元在**全新机**上的首启。
这三条在别的机器上第一次部署时要留意，日志与 `nginx -t` 会给答案。
