# Cloud Monitor 面板 API 契约总表（README）

> 本文件是 KMP 复刻项目的**端点总表**。所有字段名均来自 `api-dump/*.json`（真实响应）、`docs/findings/extra-api/*.json`（补 dump）与 `upstream/server/*.js`（权威源码），逐字核对，未编造。
> 每页细节见 `docs/api/page-<id>.md`；WebSocket 协议见 `docs/api/ws.md`；验收对账见 `docs/api/feature-matrix.md`。
> 权威路由表：`upstream/server/index.js`（api 路由在 L127–L495）。

## 0. 基本约定

| 项 | 值 | 依据 |
|---|---|---|
| API Base | `http://<host>/panel/api`（默认 `http://8.153.153.176/panel/api`；`basePath` 来自 config.json，默认 `/panel`） | `upstream/server/index.js:L17,L496`、`upstream/config.json` |
| 认证 | 登录后所有请求带 `Authorization: Bearer <token>`；`auth.extract()` 也接受 query `?token=`（用于 WS） | `upstream/server/auth.js:L86-L91` |
| 登录 | `POST /login {password}` → 200 `{token, expiresAt}`；**密码错误实测返回 HTTP 500 `{"error":"服务器内部错误"}`**（登录处理器未被 `wrap()` 包裹，同步抛错落到全局错误处理器，`index.js:L131-L135` 与 `L501-L504`）；10 分钟内失败 8 次锁 10 分钟，同样表现为 500 | 实测 + `upstream/server/auth.js:L36-L55` |
| 登出 | `POST /logout` → `{ok:true}`（token 服务端删除） | `index.js:L152-L155` |
| 未登录 | 401 `{"error":"未登录或会话过期"}` | `auth.js:L95` |
| 限流 | 单 IP **每分钟 1800 次**（`auth.js` `API_MAX_PER_WINDOW = 1800`，index.js 注释与错误文案写“240 次”是旧文案）→ 429 `{"error":"请求过于频繁（单 IP 每分钟上限 240 次），请稍后再试"}` | `auth.js:L16-L18,L77-L84`、`index.js:L139-L144` |
| 成功动作 | `{"ok":true,"output":"..."}`，部分附带额外字段（如 `{state}`、`{name}`、`{validation}`） | `actions.js:L19`、`dbadmin.js:L20` |
| 错误 | 非 2xx 体为 `{"error":"..."}`；若异常带 `e.output`（命令输出），响应为 `{"error":"...","output":"..."}`，前端弹 `modal.output` | `index.js:L146-L150` |
| 状态码 | 参数/命令失败 400；未登录 401；限流/登录锁 429（但登录相关实际是 500，见上）；资源不存在 404；未捕获 500 | 各 handler |
| 审计 | `audited()` 包裹的 POST 先写审计日志再执行（`<ISO> ip=… action=<名> {json}`，`util.js:L26-L39`）；请求体中 `content` 记为 `<N bytes>`、`sql` 截断 120 字符 | `index.js:L214-L221` |
| 下载 | `/backups/download`、`/db/backup/download`、`/relayops/backup/download` 返回二进制 + `Content-Disposition: attachment`；非法文件名 400 `{"error":"非法文件名"}`，缺失 404 | `index.js:L308-L319,L380-L394,L439-L452` |
| 上传 | `POST /db/upload?name=xxx`：raw body（`express.raw`，上限 500MB） | `index.js:L322-L326` |
| gzip | JSON/文本响应 >1KB 且 <512KB 时服务端 gzip（客户端自动解压即可） | `index.js:L36-L63` |
| WebSocket | `/panel/ws?token=…`（指标+日志跟随）、`/panel/term?token=…`（终端）；升级路径校验 `/(\/ws\|\/term)$/`，token 无效直接回 401 并断开 | `hub.js:L273-L289`、见 `ws.md` |

### 本台服务器（8.153.153.176）实测环境事实（2026-10-16，SSH 核实）

- nginx：已安装、active/enabled；fail2ban：active/enabled；ufw：active/enabled。
- **docker 未安装** → `GET /docker` = `{"ts":…,"installed":false}`。
- **PostgreSQL：psql 已安装但服务 inactive/disabled** → 所有 `engine=postgres` 的 SQL 执行类端点返回 400 `{"error":"psql: error: connection to server on socket \"/var/run/postgresql/.s.PGSQL.5432\" failed: No such file or directory…","output":同上}`。
- **MySQL/MariaDB 未安装** → `engine=mysql` 返回 400 `{"error":"/bin/bash: line 1: mysql: command not found","output":…}`。
- SQLite3 已安装（`/db/tables?engine=sqlite&…` 参数错时 400 `{"error":"SQLite 数据库文件必须以 .db/.sqlite/.sqlite3/.db3 结尾"}`）。
- redis-server / mongod 已安装但 inactive → `/db/kv/*` 仍返回 200 空结构（`{"keys":[],"cursor":"0","db":"0","pattern":"*"}`、`{"type":"","key":"foo","value":[],"ttl":null}`）。
- relay 采集器（127.0.0.1:8787）在线 → `/relay/available` ok；但 **`GET /relay/ips` 返回 404 `{"error":"unknown endpoint"}`**（采集器未实现该端点，必须容错）。
- `GET /pkg/job`（无进行中任务）返回 **200 `null`**；`GET /pkg/job?id=不存在` 返回 **404 `{"error":"任务不存在或已被清理"}`**。

## 1. 端点总表

图例：**页面**列的 id 对应 `docs/api/page-<id>.md`；**审计**列为 `audited()` 的审计名（空=不审计）；**错误**只列该端点特有形态（通用 `{error}`/`{error,output}` 见上）。

### 1.1 认证与全局

| 方法 | 路径 | 参数 | 响应顶层结构 | 页面 | 审计 | 错误 |
|---|---|---|---|---|---|---|
| GET | `/health` | – | `{ok:bool,name:str,version:str,ts:num}` | shell（连接测试） | – | – |
| POST | `/login` | body `{password}` | `{token:str,expiresAt:"ISO"}` | shell（登录页） | –（服务端另记 login-ok/login-fail） | 错误密码实测 500 `{"error":"服务器内部错误"}` |
| POST | `/logout` | – | `{ok:true}` | shell | logout | 401 |
| GET | `/me` | – | `{ok:true,info:{hostname,osName,osVersion,kernel,arch,cpuModel,cores,memTotal,bootTimeApprox:null,publicIp,panelMode,nodeVersion,ts},env:{distro,family,pm,systemd,init,webserver,tools:{psql,mysql,docker},paths:{data,logs,backups,dbBackups}},refreshMs,netLimitBps,uptimeStr,panelPid}` | 全局 | – | – |
| GET | `/config` | – | `{port,bind,basePath,refreshMs,netLimitBps,sloTarget,sloPeriodDays,tokenTtlHours,ssh:{host,port,user,hasPassword,hasKey},db:{postgres:{user,host,port,hasPassword},mysql:{…},db2:{instance,database,user,host,port,hasPassword},redis:{host,port,db,hasPassword},mongo:{host,port,user,authdb,hasPassword},sqlite:{roots}},underSystemd}` | settings | – | – |
| POST | `/config` | body `{refreshMs,netLimitBps,sloTarget,sloPeriodDays,ssh:{host,port,user,password},db:{…}}` | `{ok:true,output:"配置已保存"（含 ssh 时追加“（SSH 连接设置将在新终端会话中生效）”）}` | settings | config.update | – |
| POST | `/password` | body `{oldPassword,newPassword}` | `{ok:true,output:"密码已更新，其它设备的登录将会失效"}` | settings | password-change（手动 audit） | 400 `{"error":"当前密码不正确"}` / `{"error":"新密码至少 8 位"}` |
| POST | `/restart` | – | `{ok:true,output:"面板重启指令已发送"}` | settings | panel.restart | 400 `{"error":"仅在 systemd 服务模式下支持重启"}` |
| GET | `/metrics` | – | `{ts,cpu:{totalPct,cores[],loadAvg[3],uptimeSec,procs:{running,total},times:{user,system,idle,iowait,steal},coresCount},memory:{total,free,available,buffers,cached,sReclaimable,used,usedPct,actualUsedPct,swapTotal,swapUsed,swapPct,dirty,writeback,shmem,huge},net:{ifaces:{<名>:{rxBps,txBps,rxTotal,txTotal}}},diskio:{readBps,writeBps,readOps,writeOps}}` | dashboard | – | – |
| GET | `/overview` | – | `{metrics:<同/metrics>,disks:{ts,mounts[],blocks[],overallPct},info:<同/me.info>,topProcesses[],temps:[]}` | dashboard | – | – |
| GET | `/history` | query `range`=`10m\|1h\|6h\|24h`（默认 10m） | `{range,points:[{t(秒),c,m,s,rx,tx,d}]}`（10m 用 tick 率数据，>15min 用 30s 采样） | dashboard | – | – |
| GET | `/alerts` | – | `{alerts:[{key,level,message,ts}]（最多100条）,config:{enabled,cpu,mem,disk,cooldownMin}}` | healthalerts、全局铃铛 | – | – |
| POST | `/alerts/config` | body `{enabled,cpu,mem,disk,cooldownMin}` | `{ok:true,output:"告警阈值已保存",config:{…}}` | healthalerts | alerts.config | – |
| GET | `/metrics/history` | query `range`=`6h\|24h\|7d\|30d\|all`（默认 24h）、`compare=1` | `{ok:true,range,hours,total,agg:bool,step,points:[{t,c,m,s,rx,tx,d,peakCpu,peakRx}],prevTotal?,prevPoints?,hasPrev?}` | slo | – | – |
| GET | `/metrics/store` | – | `{ok:true,rows,aggRows,first,last,sizeBytes,file}` | slo | – | – |
| GET | `/metrics/trend` | query `days`（默认 7） | `{cpu:{points:[{day,v}],slopePerDay,current,daysToFull,days},mem:{…},disk:{…}}` | slo | – | – |

### 1.2 监控页之外的服务器读取端点

| 方法 | 路径 | 参数 | 响应顶层结构 | 页面 | 审计 | 错误 |
|---|---|---|---|---|---|---|
| GET | `/processes` | query `q`（过滤 args/user/pid）、`sort`=`cpu\|mem\|pid\|user` | `{total,statCounts:{running,sleeping,zombie,stopped,other},list:[{pid,ppid,user,cpu,mem,rss,vsz,stat,etime,args}]}`（截断 300 条） | processes | – | – |
| GET | `/services` | – | `{ts,list:[{unit,load,active,sub,description,enabled}],counts:{running,failed,total}}` | services | – | – |
| GET | `/disks` | – | `{ts,mounts:[{dev,total,used,avail,pct,mount,itotal?,iused?,iavail?,ipct?}],blocks:[{name,size,type,fstype,mountpoint}],overallPct}` | disks、dashboard | – | – |
| GET | `/memory` | – | `{metrics:<同/metrics>,swappiness:num,topRss:[进程对象×15],procTotal}` | memory | – | – |
| GET | `/network` | – | `{ts,interfaces:[{name,state,operstate,mtu,mac,ipv4[],ipv6[]}],defaultRoute:{gw,dev,proto}|null,dns:str,listening:[{netid,state,recv,send,local,peer,process}],established:[{local,peer,process}],summary:str}` | network | – | – |
| GET | `/security` | – | `{ufw:{installed,active,rules:[{num,rule,action,from,comment}],raw[]},fail2ban:{active,status,banned},sshd:{port,maxauthtries,permitrootlogin,pubkeyauthentication,passwordauthentication,kbdinteractiveauthentication,permitemptypasswords},users:[{name,uid,home,shell}],sudoers[],recentFailed[],recentLogins[],sysctl:{<17 个键>:str},apparmor:str}` | security | – | – |
| GET | `/databases` | – | `{ts,engines:[{id,name,installed,service,active,version,databases:[…],stats}],bins:{psql,redis-cli,mongosh,redis-server,mongod,sqlite3,…}}` | databases | – | – |
| GET | `/web` | – | `{ts,nginx:{installed,version,active,testOk,testOutput,sites:[{name,path,blocks:[{listen[],serverName,root,proxy,ssl}]}]},apache:{installed,active,info},certs:[{domain,expiry,daysLeft}],certbotInstalled}` | web | – | – |
| GET | `/docker` | – | `{ts,installed:false}`（未安装）；安装时 `{ts,installed:true,active,version,containers:[{id,name,image,status,ports,running}],images:[{repo,size,created}],stats:[{name,cpu,mem,memPct,net}]}` | docker | – | – |
| GET | `/cron` | – | `{ts,rootJobs[str],etcJobs[str],cronFiles[str],timers:[{next,left,unit,activates,last,passed}],dailyHourly[str]}` | cron | – | – |
| GET | `/backups` | – | `{dir,items:[{size,name,mtime}],relay:{dir,items:[{name,size,mtime}],state:{ok,ts,size,encrypted,remote},verify:{ok,ts,backup,step,detail},dbguard:{ok,ts,action,detail},diskguard:{ok,ts,pct,action},encrypted,remote,retention,timer}\|{error,dir,items:[]}}` | backups | – | – |
| GET | `/logs/sources` | – | `{files:[{id,label,file}×7：syslog/auth/kern/dpkg/nginx-access/nginx-error/cron],special:[{id,label}×2：dmesg/journal]}` | logs | – | – |
| GET | `/file` | query `path` | `{content,size,mode,owner,mtime}` | web/security 等（配置编辑器） | – | 400：路径越界 `仅允许编辑: /etc/, /var/www/, /opt/, /srv/, /root/`、`文件超过 2MB，不支持在线编辑`、`文件不存在: …` |
| GET | `/files` | query `dir`（默认 `/etc/`） | `{dir,entries:[{mode,name,isDir,isLink,target}]}` | 同上 | – | 400 `{"error":"目录不在允许范围"}` |

### 1.3 动作端点（POST）

| 方法 | 路径 | 请求体 | 成功响应 | 页面 | 审计 | 特有错误 |
|---|---|---|---|---|---|---|
| POST | `/process/kill` | `{pid,signal:"TERM\|KILL\|HUP\|INT\|USR1\|STOP"}` | `{ok,output:"已向 PID x 发送 SIGTERM\n…"}` | processes | process.kill | 400 `无效 PID` |
| POST | `/service` | `{unit,action:"start\|stop\|restart\|enable\|disable\|reload\|mask\|unmask"}` | `{ok,output,state}` | services | service.action | 400 `无效的服务单元名` / `无效操作: …` |
| POST | `/disk/action` | `{action}`（见下） | `{ok,output}` | disks | disk.action | 400 `未知磁盘操作: …` |
| POST | `/network/action` | `{action,params}`（见下） | `{ok,output}` | network | network.action | 400 `无效的端口/规则格式 …`、`无效规则编号`、`无效网卡名`、`网卡不存在: …` |
| POST | `/security/action` | `{action,params}`（见下） | `{ok,output}` | security | security.action | 400 `无效 IP`、`sysctl 键不存在: …`、`sshd 配置校验失败，未应用:\n…`、`不能锁定 root` 等 |
| POST | `/db/action` | `{db:"mysql\|postgres\|redis\|mongo",action:"start\|stop\|restart\|enable\|disable"}` | `{ok,output,state?}` | databases | db.action | 400 `未知数据库: …` / `未知数据库操作: …` |
| POST | `/db/query` | `{db:"mysql\|postgres\|redis",sql,database?}` | `{ok,output:<命令输出文本>}` | databases | db.query | 400 `SQL 为空`、`SQL 过长`、`该数据库不支持查询控制台: …`、`MySQL 查询失败:\n…` |
| POST | `/web/action` | `{action,params}`（见下） | `{ok,output}` | web | web.action | 400 `无效站点名`、`站点不存在: …`、`拒绝停用 opencode-relay（生产中继服务）`、`nginx -t 校验失败，已回滚:\n…` |
| POST | `/docker/action` | `{action,params}`（见下） | `{ok,output}` | docker | docker.action | 400 `无效容器 ID/名称`、`无效镜像名`、`未知 Docker 操作: …` |
| POST | `/cron/action` | `{action:"add"\|"delete",params:{name,schedule,command}}` | `{ok,output}` | cron | cron.action | 400 `任务名仅允许字母数字_-(1-40位)`、`定时表达式含非法字符`、`命令无效（≤500字符，单行）`、`无效任务名`、`任务不存在: …` |
| POST | `/backups` | `{source:"/etc\|/root\|/var/www\|/opt\|/srv"}` | `{ok,output:"备份完成: <name>\n…",name}` | backups | backup.create | 400 `备份源必须是: …` |
| POST | `/backups/delete` | `{name:"cm-*.tar.gz"}` | `{ok,output}` | backups | backup.delete | 400 `非法备份文件名` |
| POST | `/user` | `{action,params}`（见下） | `{ok,output}` | settings | user.action | 400 `用户名仅允许小写字母开头的 [a-z0-9_-]`、`用户已存在: …`、`密码至少 6 位`、`不能删除 root`、`拒绝通过面板删除系统用户 (UID<1000)`、`公钥格式无效`、`该公钥已存在` 等 |
| POST | `/file` | `{path,content,validate?}` | `{ok,output:"已保存"（校验通过时 "，校验通过: <cmd>"）,validation:{cmd,ok,output}\|null}` | web/security 等 | file.write | 400 `仅允许编辑: …`、`内容超过 2MB`、`文件已保存，但配置校验失败（<cmd>）:\n…` |
| POST | `/pkg/install` | `{pkg}`（白名单 13 项） | `{ok,output}` | packages | pkg.install | 400 `不在允许安装列表: …（可在网页终端自行安装）` |
| POST | `/logs/tail` | `{source}` | `{output:<tail 文本>}` | logs | – | 400 `{"error":"未知日志源"}` |

**动作参数明细（以 `actions.js` 为准）**

- `disk/action.action` ∈ `pkg-clean`、`pkg-update`、`pkg-autoremove`（发行版无关，走 distro PM）、`apt-clean`、`apt-autoremove`、`journal-vacuum`、`fstrim`、`drop-caches`、`docker-prune`、`analyze`（`actions.js:L56-L70`）。
- `network/action.action` ∈ `ufw-allow`/`ufw-deny`（`params.spec`，如 `8080/tcp`）、`ufw-delete`（`params.num`）、`ufw-reload`、`ufw-enable`、`ufw-disable`、`iface-up`/`iface-down`（`params.dev`）。
- `security/action.action` ∈ `fail2ban-install`、`fail2ban-start`、`fail2ban-stop`、`fail2ban-unban`（`params.ip`）、`sysctl-set`（`params.key,value`，持久化到 `/etc/sysctl.d/99-cloud-monitor.conf`）、`sshd-apply`（`params.port,permitRootLogin,passwordAuthentication,permitEmptyPasswords,pubkeyAuthentication,maxAuthTries`，写 `/etc/ssh/sshd_config.d/99-cloud-monitor.conf` 并 `sshd -t` 校验）、`user-lock`/`user-unlock`（`params.user`）。
- `web/action.action` ∈ `nginx-reload`、`nginx-start`、`nginx-stop`、`nginx-test`、`site-toggle`（`params.site,enable`，`nginx -t` 校验失败自动回滚）、`cert-renew`。
- `docker/action.action` ∈ `start`/`stop`/`restart`/`rm`（`params.target`）、`img-rm`（`params.ref`）、`daemon-start`、`daemon-stop`、`daemon-enable`、`prune`。
- `user/action.action` ∈ `create`（`params.name,password,sudo`）、`delete`、`passwd`、`sudo-grant`、`sudo-revoke`、`sshkey-list`、`sshkey-add`（`params.key`）、`sshkey-del`（`params.key`=指纹）。
- `cron/add.params`：`{name,schedule,command}` → 写 `/etc/cron.d/cm-<name>`。

### 1.4 软件包管理 `/pkg/*`

| 方法 | 路径 | 参数 | 响应顶层结构 | 审计 |
|---|---|---|---|---|
| GET | `/pkg/summary` | – | `{installed_total,installed_active,removed_config,upgradable,held,hold_list[str],index_updated,apt_cache_size,disk:{line,avail,total,used_pct},lock:{busy,label}}` | – |
| GET | `/pkg/search` | `q`,`limit` | `{query,total,truncated,results:[{name,description,installed,version,size_kb,section,exact}]}` | – |
| GET | `/pkg/detail` | `name` | `{name,summary,description,version,section,priority,homepage,origin,maintainer,bugs_url,size_kb,size,depends[],recommends[],installed_version,candidate,pinned,installed,installed_size_kb,upgradable,…}` | – |
| GET | `/pkg/installed` | `q,section,sort,dir,limit,offset` | `{total,offset,limit,sections[str],rows:[{name,version,section,size_kb,status,installed,size}]}` | – |
| GET | `/pkg/upgradable` | – | `{total,rows:[…]}` | – |
| GET | `/pkg/autoremove` | – | `{output,raw_lines}` | – |
| GET | `/pkg/recommended` | – | `{total,installed_total,category_count,categories:[{id,label,icon,total,installed_count,rows[]}]}` | – |
| GET | `/pkg/sources` | – | `{codename,current:{uri,file,suite,name,mirror_id,security_uri,security_mirror},files:[{path,name,stanzas}],mirrors:[{id,name,url,region,desc}]}` | – |
| GET | `/pkg/source-backups` | – | `{dir,total,items[str]}` | – |
| GET | `/pkg/search-in-source` | `q,uri,limit` | （源内搜索结果） | – |
| GET | `/pkg/test-mirror` | `url` | `{id,name,url,reachable,http_code,latency_ms,release,ok}` | – |
| POST | `/pkg/switch-mirror` | body | `{ok,output}` | pkg.mirror.switch |
| POST | `/pkg/preview` | body | `{ok,output}` | pkg.preview |
| POST | `/pkg/action` | body（`{action,names,force}` 等，见 page-packages.md） | `{ok,output}` | pkg.action |
| POST | `/pkg/job` | body `{action,names[],force}` | `{id}`（启动任务，立即返回） | pkg.job |
| GET | `/pkg/job` | query `id`（缺省=最近一个任务） | 任务对象（进行中）；无任务时 `null`；id 不存在 404 `{"error":"任务不存在或已被清理"}` | – |

### 1.5 数据库管理 `/db/*`

> `checkEngine()` 只接受 `postgres` / `mysql`（`dbadmin.js:L200-L202`）；额外引擎（sqlite/db2/redis/mongo）走 dbmulti。本服务器 postgres 服务未运行、mysql 未安装 → 执行类端点大多 400 `{error,output}`（见 §0 实测）。

**GET（`dbG`）**：`/db/status`、`/db/databases`、`/db/tables`、`/db/table/schema`、`/db/roles`、`/db/backups`、`/db/insights`、`/db/objects`、`/db/statements`、`/db/schemas`、`/db/table/storage`、`/db/missing-indexes`、`/db/extra-monitor`（query 均为 `engine`，多数还要 `database`/`table`）。

关键响应形状（本服务器实测 + 源码）：

- `/db/status?engine=postgres|mysql` → `{engine,version:{},stats:{},processes:[…],variables:[…],capabilities:{…}}`（`dbadmin.js:L205-L236`；本机 400）。
- `/db/databases` → 数组 `[{name,owner,size,size_bytes,encoding,collation,is_template,created,connections,tables}]`（pg）/ `[{name,owner,size,size_bytes,encoding,collation,tables,created,connections}]`（mysql，`dbadmin.js:L239-L263`；本机 400）。
- `/db/insights`、`/db/objects`、`/db/extra-monitor` → `{engine,database,sections:[{title,columns,rows,error,note}]}`（**分节容错结构**：单节失败只在该节 `error` 里，不整体报错）。
- `/db/statements` → `{available:bool,hint:str,rows:[],columns:[]}`（本机 `{available:false,hint:"未安装 pg_stat_statements 扩展。安装后需要重启 PostgreSQL 才能生效。"}`）。
- `/db/backups` → `{dir,items:[{name,size,uploaded,export,engine,database,mode,mtime}]}`。
- `/db/engines`（dbmulti）→ `{engines:[{id,name,kind,installed,active,service,packages[],needsRepo?}]}`（本机 4 项：sqlite/db2/redis/mongo；**不含 postgres/mysql**，那两个由 `GET /databases` 提供）。
- `/db/kv/keys` → `{keys[],cursor,db,pattern}`；`/db/kv/value` → `{type,key,value,ttl}`；`/db/kv/config` → `{ok:true,output:"",rows:[]}`（redis 未启动时的空形态）。

**POST**：`/db/table/data`（不审计）、`/db/table/insert|update|delete|action`、`/db/database/create|drop|rename|alter`、`/db/maintenance`、`/db/maintenance/plan`、`/db/role`、`/db/kill`、`/db/console`、`/db/backup`、`/db/backup/cleanup`、`/db/backup/delete`、`/db/restore`、`/db/schema/action`、`/db/schema/manage`、`/db/table/bulk`、`/db/table/copy-db`、`/db/table/backup`、`/db/table/search`、`/db/explain/tree`、`/db/sequence`、`/db/object`、`/db/session/kill`、`/db/export`、`/db/count`、`/db/import`、`/db/extension`、`/db/engine/install`、`/db/engine/service`、`/db/kv/set`、`/db/kv/delete`、`/db/kv/config`、`/db/doc/find|insert|update|delete|collection|indexes|index|index/drop|shell`。请求体字段与响应逐端点见 `docs/api/page-databases.md`。

- 通用成功形态：`{ok:true,output:"…"}`（dbadmin `ok()`），部分带 `columns/rows/durationMs`。
- 只读入口（如 `/db/explain/tree`）服务端有 `assertReadOnlySingleStatement()` 校验：400 `{"error":"… 只允许单条语句 …"}` / `{"error":"… 中包含不允许的写操作: XX（此入口仅允许只读查询）"}`（`dbadmin.js:L120-L133`）。
- `/db/backup/download?name=` GET：二进制；400 `非法文件名`、404 `文件不存在`。
- `/db/upload?name=` POST：raw body；空 body 400 `未收到文件内容`。

### 1.6 中继 `/relay/*` 与运维 `/relayops/*`

| 方法 | 路径 | 参数 | 响应 | 页面 | 审计 |
|---|---|---|---|---|---|
| GET | `/relay/available` | – | `{ok:bool,data:{ok,rows_total,rows_ingested,last_ingest,uptime_s,ingest_error,ingest_offset,lines_skipped,db_bytes},host,port}` | 全局（导航探测，缓存 15s） | – |
| GET | `/relay/<name>` | 透传给采集器（29 个 name，见 index.js `RELAY_GET`） | 各端点不同，样本见 `api-dump/relay_*.json`；`/relay/ips` 本机 404 `{"error":"unknown endpoint"}` | relay、slo | – |
| POST | `/relay/action` | `{name,payload}` | relay.action | relay | relay.action |
| GET | `/relayops/ratelimit` | – | `{present,zones:[{type,name,size}],applied:[{context,kind,zone,value}],configRaw,windowLines,limited429,byHour,dist:[{code,count}],errorLimitCount,fail2banActive,jails:[{name,currentlyBanned,totalBanned,ips}],logFiles:{access,error,conf}}` | slo | – |
| GET | `/relayops/slo` | `range` | `{range,calls,ok,e5,e4,e0,retried,successRate,errorRate,availability,avgRt,maxRt,avgTtfb,latency:{p50,p95,p99},byRoute:[…],errors:[{status,n}],timeline:[{bucket,calls,ok,e5}]}` | slo | – |
| GET | `/relayops/slo-prev` | `range` | `{range,calls,ok,e5,e4,retried,successRate,availability,avgRt,maxRt}` | slo | – |
| GET | `/relayops/slo-budget` | – | `{target,periodDays,allowedErrorRatePct,period:{calls,bad,e4,allowedErrors,consumedPct,remainingPct},windows:[{window,calls,bad,e4,errorRatePct,burnRate,exhaustDays,status}]}` | slo | – |
| GET | `/relayops/hitrate` | `range` | `{range,plans:[{plan,label,calls,hit,miss,denominator,e402,e403,e429,e5,e0,client4xx,…}],buckets[],series:[{plan,label,color,points,calls}],totals:{calls,hit,miss,client4xx,abort,retried,denominator,hitRate,rawSuccessRate},planLabels:{…}}` | slo | – |
| GET | `/relayops/tokenhit` | `range` | `{range,vendor:{events,sessions,inputNew,cacheRead,cacheWrite,output,hitRate,coverageEvents,coveragePct,byModel[],byDay[]},reuse:{sessions,multiTurnSessions,singleTurnSessions,turns,totalBytes,reuseBytes,newBytes,reuseRate,avgSessionReuse,top[],byDay[],byModel[]},gap,live:{…}}` | slo | – |
| GET | `/relayops/usage` | `range` | `{range,events,sessions,totalTokens,totalSecs,byModel:[{model,calls,in_tok,out_tok,cache_r,cache_w,reason_tok,total,secs,sessions,priced,effectiveInput,…}],byDay,rates:{perHour1h,perHour6h,perDay},quota:{rolling,weekly,monthly:{status,percent,resetsAt}},quotaFetchedAt,quotaHistory,forecast:{…},pricing:{currency,present}}` | slo | – |
| GET | `/relayops/correlate` | `hours`(1–720),`window`(5–180) | `{hours,windowMin,summary:{anomalies,withCauses,ratioPct},changeCount,links:[{anomaly,causes,likely}]}` | slo | – |
| GET | `/relayops/audit` | `range,key,limit` | `{available,range,key,limit,keys[],logFile,rules[],storage,events:[{serial,ts,key,comm,exe,uid,pid,success,cmd,cwd,files}],total,counts:{…}}` | audit | – |
| GET | `/relayops/health` | – | `{available,reason,checks:[{name,ok,since,lastSent}],summary:{total,failing,ok},failingNames,log[],backup:{…},verify:{…},dbguard:{…},diskguard:{…},smtp:{to[],host,port,ssl,user,from},timers:[{unit,activates,next,leftUs,last,left}],units[],sources:{state,log,script}}` | healthalerts | – |
| POST | `/relayops/alert/test` | – | `{ok,…}` | healthalerts | relay.alert.test |
| POST | `/relayops/backup/run` | – | `{ok,…}` | healthalerts/backups | relay.backup.run |
| POST | `/relayops/backup/delete` | `{name}` | `{ok,…}` | healthalerts/backups | relay.backup.delete |
| GET | `/relayops/backup/download` | `name` | 二进制 | – | relay.backup.download |

### 1.7 静态与非 API 路径

- `GET /panel`、`/panel/`、`/panel/index.html` → 302 到 `/panel/public/index.html`（Web 壳，与 KMP 无关）。
- `GET /panel/public/**`、`/panel/vendor/*`：静态资源（与 App 无关，仅用于理解网页版）。
- `GET /api/*`（不带 basePath）→ 404 `{"error":"not found"}`（`index.js:L497`）。

## 2. 复刻实现要点（给写码角色）

1. **所有端点都在 `/panel/api` 前缀下**；`PanelApi.get/post` 已实现（`core/Api.kt`），路径传 `/xxx` 相对即可。
2. **错误统一按 `{error}` / `{error,output}` 解析**：`ApiException(message, code, output, isAuth)` 已具备，页面层弹窗展示 `output`。
3. **轮询页**用 `CM.me.refreshMs`（来自 `/me.refreshMs`，默认 2000ms，可被设置页修改；WS `refresh-interval` 也可改）。KMP 端对应 `Hub.refreshMs`（`core/Hub.kt` 已实现）。
4. **仪表盘实时数据走 `/ws`**（`hello` 回放 + `metrics` 流），页面级 REST 轮询只在没有 WS 数据源时用。
5. **审计只在服务端发生**，客户端无需处理；但 UI 文案中出现的“操作已记录”类提示以页面脚本为准。
6. **401 → 清 token 回登录页**（`AppState.expireSession()` 已实现）。
7. 下载端点用 `PanelApi.download`（不要按 JSON 解析）。

## 3. 相关文件

- 每页契约：`docs/api/page-<id>.md`（20 份）
- 运维增强（本项目自研，非上游）：`docs/api/ops.md` —— 前缀 `/panel/api/ops/*`，
  由 `server/ops/index.js` 挂载；上游路由表里没有这些端点，未部署时一律 404。
- WebSocket：`docs/api/ws.md`
- 功能对账矩阵：`docs/api/feature-matrix.md`
- 补充样本与脚本：`docs/findings/extra-api/`、`docs/findings/extra_dump.py`、`docs/findings/summarize_dumps.py`
