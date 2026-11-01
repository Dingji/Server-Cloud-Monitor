# Cloud Monitor panel API contract (master table)

> This file is the **endpoint master table** for the KMP replica. Every field name comes
> from `api-dump/*.json` (real responses), `docs/findings/extra-api/*.json` (supplementary
> dumps) and `upstream/server/*.js` (authoritative source), checked verbatim — nothing here
> is invented.
> Per-page detail lives in `docs/api/page-<id>.md`; the WebSocket protocol in `docs/api/ws.md`;
> the acceptance reconciliation in `docs/api/feature-matrix.md`.
> Authoritative route table: `upstream/server/index.js` (the api routes are at L127–L495).
>
> **About the Chinese inside this document:** the panel's server emits its own messages in
> Chinese, so quoted bodies such as `{"error":"密码错误"}` are **protocol literals reproduced
> verbatim**, not documentation prose. Translating them would make the contract wrong for
> anyone matching on them. Everything outside those quotes is English.
>
> `upstream/` and `api-dump/` are local reference mirrors and are not committed to this
> repository; the committed equivalent of `upstream/server/…` is `app/server/…`.

## 0. Basic conventions

| Item | Value | Source |
|---|---|---|
| API base | `http://<host>/panel/api` (default `http://8.153.153.176/panel/api`; `basePath` comes from config.json, default `/panel`) | `index.js:L17,L496`, `config.json` |
| Auth | After login every request carries `Authorization: Bearer <token>`; `auth.extract()` also accepts the query form `?token=` (used by WebSocket) | `auth.js:L86-L91` |
| Login | `POST /login {password}` → 200 `{token, expiresAt}`. **Wrong password → 401 `{"error":"密码错误"}`; 8 failures within 10 minutes locks the account for 10 minutes → 429 `{"error":"尝试次数过多，请 10 分钟后再试"}`.** `/login` wraps `auth.login` in try/catch and forwards `e.status`/`e.message`; before 2026-10-26 it was an unwrapped synchronous handler, so a thrown error fell through to the global handler at `index.js:L528-L531` and always answered 500 `{"error":"服务器内部错误"}` — which is why the Android client reported "internal server error" during a lockout even with the correct password | Measured (401 after the 2026-10-26 patch) + `auth.js:L36-L55` |
| Logout | `POST /logout` → `{ok:true}` (token deleted server-side) | `index.js:L152-L155` |
| Not logged in | 401 `{"error":"未登录或会话过期"}` | `auth.js:L95` |
| Rate limit | **1800 requests per minute per IP** (`auth.js` `API_MAX_PER_WINDOW = 1800`; the "240" in the `index.js` comment and in the error text is stale wording) → 429 `{"error":"请求过于频繁（单 IP 每分钟上限 240 次），请稍后再试"}` | `auth.js:L16-L18,L77-L84`, `index.js:L139-L144` |
| Successful action | `{"ok":true,"output":"..."}`, sometimes with extra fields (`{state}`, `{name}`, `{validation}`) | `actions.js:L19`, `dbadmin.js:L20` |
| Errors | A non-2xx body is `{"error":"..."}`; when the exception carries `e.output` (command output) the response is `{"error":"...","output":"..."}` and the front end shows `modal.output` | `index.js:L146-L150` |
| Status codes | Bad parameter / failed command 400; not logged in 401; rate limit or login lockout 429 (login failures historically surfaced as 500, see above); missing resource 404; uncaught 500 | handlers |
| Audit | POSTs wrapped in `audited()` write the audit log before executing (`<ISO> ip=… action=<name> {json}`, `util.js:L26-L39`); in the recorded request body `content` becomes `<N bytes>` and `sql` is truncated at 120 characters | `index.js:L214-L221` |
| Download | `/backups/download`, `/db/backup/download`, `/relayops/backup/download` return binary plus `Content-Disposition: attachment`; an invalid file name is 400 `{"error":"非法文件名"}`, a missing file is 404 | `index.js:L308-L319,L380-L394,L439-L452` |
| Upload | `POST /db/upload?name=xxx`: raw body (`express.raw`, 500MB cap) | `index.js:L322-L326` |
| gzip | JSON/text responses over 1KB and under 512KB are gzipped server-side (clients just let the HTTP layer decompress) | `index.js:L36-L63` |
| WebSocket | `/panel/ws?token=…` (metrics plus log follow), `/panel/term?token=…` (terminal); the upgrade path is matched against `/(\/ws\|\/term)$/`, and an invalid token gets a direct 401 plus disconnect | `hub.js:L273-L289`, see `ws.md` |

### Measured environment facts for this host (8.153.153.176), 2026-10-16, verified over SSH

- nginx installed and active/enabled; fail2ban active/enabled; ufw active/enabled.
- **Docker is not installed** → `GET /docker` = `{"ts":…,"installed":false}`.
- **PostgreSQL: psql is installed but the service is inactive/disabled** → every
  SQL-executing endpoint with `engine=postgres` returns 400
  `{"error":"psql: error: connection to server on socket \"/var/run/postgresql/.s.PGSQL.5432\" failed: No such file or directory…","output": same}`.
- **MySQL/MariaDB not installed** → `engine=mysql` returns 400
  `{"error":"/bin/bash: line 1: mysql: command not found","output":…}`.
- sqlite3 is installed (`/db/tables?engine=sqlite&…` with a bad path returns 400
  `{"error":"SQLite 数据库文件必须以 .db/.sqlite/.sqlite3/.db3 结尾"}`).
- redis-server / mongod installed but inactive → `/db/kv/*` still returns 200 with an empty
  shape (`{"keys":[],"cursor":"0","db":"0","pattern":"*"}`,
  `{"type":"","key":"foo","value":[],"ttl":null}`).
- The relay collector (127.0.0.1:8787) is online → `/relay/available` is ok; but
  **`GET /relay/ips` returns 404 `{"error":"unknown endpoint"}`** (the collector does not
  implement it, so clients must tolerate that).
- `GET /pkg/job` with no job in progress returns **200 `null`**;
  `GET /pkg/job?id=<unknown>` returns **404 `{"error":"任务不存在或已被清理"}`**.

## 1. Endpoint master table

Legend: in the **Page** column an id maps to `docs/api/page-<id>.md`; the **Audit** column is
the name `audited()` records (empty = not audited); the **Errors** column lists only what is
specific to that endpoint (the generic `{error}` / `{error,output}` shapes are in §0).

### 1.1 Auth and global

| Method | Path | Parameters | Top-level response | Page | Audit | Errors |
|---|---|---|---|---|---|---|
| GET | `/health` | – | `{ok:bool,name:str,version:str,ts:num}` | shell (connection test) | – | – |
| POST | `/login` | body `{password}` | `{token:str,expiresAt:"ISO"}` | shell (login screen) | – (the server logs login-ok/login-fail separately) | wrong password 401 `{"error":"密码错误"}`; lockout 429 `{"error":"尝试次数过多，请 10 分钟后再试"}` |
| POST | `/logout` | – | `{ok:true}` | shell | logout | 401 |
| GET | `/me` | – | `{ok:true,info:{hostname,osName,osVersion,kernel,arch,cpuModel,cores,memTotal,bootTimeApprox:null,publicIp,panelMode,nodeVersion,ts},env:{distro,family,pm,systemd,init,webserver,tools:{psql,mysql,docker},paths:{data,logs,backups,dbBackups}},refreshMs,netLimitBps,uptimeStr,panelPid}` | global | – | – |
| GET | `/config` | – | `{port,bind,basePath,refreshMs,netLimitBps,sloTarget,sloPeriodDays,tokenTtlHours,ssh:{host,port,user,hasPassword,hasKey},db:{postgres:{user,host,port,hasPassword},mysql:{…},db2:{instance,database,user,host,port,hasPassword},redis:{host,port,db,hasPassword},mongo:{host,port,user,authdb,hasPassword},sqlite:{roots}},underSystemd}` | settings | – | – |
| POST | `/config` | body `{refreshMs,netLimitBps,sloTarget,sloPeriodDays,ssh:{host,port,user,password},db:{…}}` | `{ok:true,output:"配置已保存"}` (with an appended note when `ssh` is present) | settings | config.update | – |
| POST | `/password` | body `{oldPassword,newPassword}` | `{ok:true,output:"密码已更新，其它设备的登录将会失效"}` | settings | password-change (manually audited) | 400 `{"error":"当前密码不正确"}` / `{"error":"新密码至少 8 位"}` |
| POST | `/restart` | – | `{ok:true,output:"面板重启指令已发送"}` | settings | panel.restart | 400 `{"error":"仅在 systemd 服务模式下支持重启"}` |
| GET | `/metrics` | – | `{ts,cpu:{totalPct,cores[],loadAvg[3],uptimeSec,procs:{running,total},times:{user,system,idle,iowait,steal},coresCount},memory:{total,free,available,buffers,cached,sReclaimable,used,usedPct,actualUsedPct,swapTotal,swapUsed,swapPct,dirty,writeback,shmem,huge},net:{ifaces:{<name>:{rxBps,txBps,rxTotal,txTotal}}},diskio:{readBps,writeBps,readOps,writeOps}}` | dashboard | – | – |
| GET | `/overview` | – | `{metrics:<as /metrics>,disks:{ts,mounts[],blocks[],overallPct},info:<as /me.info>,topProcesses[],temps:[]}` | dashboard | – | – |
| GET | `/history` | query `range` = `10m\|1h\|6h\|24h` (default 10m) | `{range,points:[{t (seconds),c,m,s,rx,tx,d}]}` (10m uses tick-rate data, over 15min uses 30s samples) | dashboard | – | – |
| GET | `/alerts` | – | `{alerts:[{key,level,message,ts}] (at most 100),config:{enabled,cpu,mem,disk,cooldownMin}}` | healthalerts, global bell | – | – |
| POST | `/alerts/config` | body `{enabled,cpu,mem,disk,cooldownMin}` | `{ok:true,output:"告警阈值已保存",config:{…}}` | healthalerts | alerts.config | – |
| GET | `/metrics/history` | query `range` = `6h\|24h\|7d\|30d\|all` (default 24h), `compare=1` | `{ok:true,range,hours,total,agg:bool,step,points:[{t,c,m,s,rx,tx,d,peakCpu,peakRx}],prevTotal?,prevPoints?,hasPrev?}` | slo | – | – |
| GET | `/metrics/store` | – | `{ok:true,rows,aggRows,first,last,sizeBytes,file}` | slo | – | – |
| GET | `/metrics/trend` | query `days` (default 7) | `{cpu:{points:[{day,v}],slopePerDay,current,daysToFull,days},mem:{…},disk:{…}}` | slo | – | – |

### 1.2 Server read endpoints outside the monitoring pages

| Method | Path | Parameters | Top-level response | Page | Audit | Errors |
|---|---|---|---|---|---|---|
| GET | `/processes` | query `q` (filters args/user/pid), `sort` = `cpu\|mem\|pid\|user` | `{total,statCounts:{running,sleeping,zombie,stopped,other},list:[{pid,ppid,user,cpu,mem,rss,vsz,stat,etime,args}]}` (truncated to 300) | processes | – | – |
| GET | `/services` | – | `{ts,list:[{unit,load,active,sub,description,enabled}],counts:{running,failed,total}}` | services | – | – |
| GET | `/disks` | – | `{ts,mounts:[{dev,total,used,avail,pct,mount,itotal?,iused?,iavail?,ipct?}],blocks:[{name,size,type,fstype,mountpoint}],overallPct}` | disks, dashboard | – | – |
| GET | `/memory` | – | `{metrics:<as /metrics>,swappiness:num,topRss:[15 process objects],procTotal}` | memory | – | – |
| GET | `/network` | – | `{ts,interfaces:[{name,state,operstate,mtu,mac,ipv4[],ipv6[]}],defaultRoute:{gw,dev,proto}|null,dns:str,listening:[{netid,state,recv,send,local,peer,process}],established:[{local,peer,process}],summary:str}` | network | – | – |
| GET | `/security` | – | `{ufw:{installed,active,rules:[{num,rule,action,from,comment}],raw[]},fail2ban:{active,status,banned},sshd:{port,maxauthtries,permitrootlogin,pubkeyauthentication,passwordauthentication,kbdinteractiveauthentication,permitemptypasswords},users:[{name,uid,home,shell}],sudoers[],recentFailed[],recentLogins[],sysctl:{<17 keys>:str},apparmor:str}` | security | – | – |
| GET | `/databases` | – | `{ts,engines:[{id,name,installed,service,active,version,databases:[…],stats}],bins:{psql,redis-cli,mongosh,redis-server,mongod,sqlite3,…}}` | databases | – | – |
| GET | `/web` | – | `{ts,nginx:{installed,version,active,testOk,testOutput,sites:[{name,path,blocks:[{listen[],serverName,root,proxy,ssl}]}]},apache:{installed,active,info},certs:[{domain,expiry,daysLeft}],certbotInstalled}` | web | – | – |
| GET | `/docker` | – | `{ts,installed:false}` when absent; when installed `{ts,installed:true,active,version,containers:[{id,name,image,status,ports,running}],images:[{repo,size,created}],stats:[{name,cpu,mem,memPct,net}]}` | docker | – | – |
| GET | `/cron` | – | `{ts,rootJobs[str],etcJobs[str],cronFiles[str],timers:[{next,left,unit,activates,last,passed}],dailyHourly[str]}` | cron | – | – |
| GET | `/backups` | – | `{dir,items:[{size,name,mtime}],relay:{dir,items:[{name,size,mtime}],state:{ok,ts,size,encrypted,remote},verify:{ok,ts,backup,step,detail},dbguard:{ok,ts,action,detail},diskguard:{ok,ts,pct,action},encrypted,remote,retention,timer}\|{error,dir,items:[]}}` | backups | – | – |
| GET | `/logs/sources` | – | `{files:[7 entries: syslog/auth/kern/dpkg/nginx-access/nginx-error/cron],special:[2 entries: dmesg/journal]}` | logs | – | – |
| GET | `/file` | query `path` | `{content,size,mode,owner,mtime}` | web/security etc. (config editor) | – | 400: path outside the allowlist, or file over 2MB, or missing |
| GET | `/files` | query `dir` (default `/etc/`) | `{dir,entries:[{mode,name,isDir,isLink,target}]}` | as above | – | 400 `{"error":"目录不在允许范围"}` |

### 1.3 Action endpoints (POST)

| Method | Path | Request body | Success response | Page | Audit | Specific errors |
|---|---|---|---|---|---|---|
| POST | `/process/kill` | `{pid,signal:"TERM\|KILL\|HUP\|INT\|USR1\|STOP"}` | `{ok,output:"已向 PID x 发送 SIGTERM\n…"}` | processes | process.kill | 400 invalid PID |
| POST | `/service` | `{unit,action:"start\|stop\|restart\|enable\|disable\|reload\|mask\|unmask"}` | `{ok,output,state}` | services | service.action | 400 bad unit name / unknown action |
| POST | `/disk/action` | `{action}` (below) | `{ok,output}` | disks | disk.action | 400 unknown disk action |
| POST | `/network/action` | `{action,params}` (below) | `{ok,output}` | network | network.action | 400 bad port/rule format, bad rule number, bad interface name, unknown interface |
| POST | `/security/action` | `{action,params}` (below) | `{ok,output}` | security | security.action | 400 bad IP, unknown sysctl key, sshd validation failed (not applied), cannot lock root, … |
| POST | `/db/action` | `{db:"mysql\|postgres\|redis\|mongo",action:"start\|stop\|restart\|enable\|disable"}` | `{ok,output,state?}` | databases | db.action | 400 unknown database / unknown database action |
| POST | `/db/query` | `{db:"mysql\|postgres\|redis",sql,database?}` | `{ok,output:<command output text>}` | databases | db.query | 400 empty SQL, SQL too long, this engine has no query console, MySQL query failed |
| POST | `/web/action` | `{action,params}` (below) | `{ok,output}` | web | web.action | 400 bad site name, site not found, refusing to disable opencode-relay (production relay), `nginx -t` failed and rolled back |
| POST | `/docker/action` | `{action,params}` (below) | `{ok,output}` | docker | docker.action | 400 bad container id/name, bad image name, unknown Docker action |
| POST | `/cron/action` | `{action:"add"\|"delete",params:{name,schedule,command}}` | `{ok,output}` | cron | cron.action | 400 task name must be `[A-Za-z0-9_-]` 1–40, illegal characters in the schedule, invalid command (≤500 chars, single line), bad task name, task not found |
| POST | `/backups` | `{source:"/etc\|/root\|/var/www\|/opt\|/srv"}` | `{ok,output:"备份完成: <name>\n…",name}` | backups | backup.create | 400 backup source must be one of the allowlist |
| POST | `/backups/delete` | `{name:"cm-*.tar.gz"}` | `{ok,output}` | backups | backup.delete | 400 illegal backup file name |
| POST | `/user` | `{action,params}` (below) | `{ok,output}` | settings | user.action | 400 username must be `[a-z0-9_-]` starting lowercase, user exists, password too short, cannot delete root, refusing to delete a system user (UID<1000) through the panel, bad public key format, key already present, … |
| POST | `/file` | `{path,content,validate?}` | `{ok,output:"已保存",validation:{cmd,ok,output}\|null}` | web/security etc. | file.write | 400 path not editable, content over 2MB, or "saved but validation failed (<cmd>)" |
| POST | `/pkg/install` | `{pkg}` (13-item allowlist) | `{ok,output}` | packages | pkg.install | 400 not in the install allowlist (install it yourself in the web terminal) |
| POST | `/logs/tail` | `{source}` | `{output:<tail text>}` | logs | – | 400 `{"error":"未知日志源"}` |

**Action parameter detail (authoritative in `actions.js`)**

- `disk/action.action` ∈ `pkg-clean`, `pkg-update`, `pkg-autoremove` (distribution
  independent, via the distro package manager), `apt-clean`, `apt-autoremove`,
  `journal-vacuum`, `fstrim`, `drop-caches`, `docker-prune`, `analyze` (`actions.js:L56-L70`).
- `network/action.action` ∈ `ufw-allow`/`ufw-deny` (`params.spec`, e.g. `8080/tcp`),
  `ufw-delete` (`params.num`), `ufw-reload`, `ufw-enable`, `ufw-disable`,
  `iface-up`/`iface-down` (`params.dev`).
- `security/action.action` ∈ `fail2ban-install`, `fail2ban-start`, `fail2ban-stop`,
  `fail2ban-unban` (`params.ip`), `sysctl-set` (`params.key,value`, persisted to
  `/etc/sysctl.d/99-cloud-monitor.conf`), `sshd-apply`
  (`params.port,permitRootLogin,passwordAuthentication,permitEmptyPasswords,pubkeyAuthentication,maxAuthTries`,
  writing `/etc/ssh/sshd_config.d/99-cloud-monitor.conf` and validating with `sshd -t`),
  `user-lock`/`user-unlock` (`params.user`).
- `web/action.action` ∈ `nginx-reload`, `nginx-start`, `nginx-stop`, `nginx-test`,
  `site-toggle` (`params.site,enable`, with automatic rollback when `nginx -t` fails),
  `cert-renew`.
- `docker/action.action` ∈ `start`/`stop`/`restart`/`rm` (`params.target`), `img-rm`
  (`params.ref`), `daemon-start`, `daemon-stop`, `daemon-enable`, `prune`.
- `user/action.action` ∈ `create` (`params.name,password,sudo`), `delete`, `passwd`,
  `sudo-grant`, `sudo-revoke`, `sshkey-list`, `sshkey-add` (`params.key`), `sshkey-del`
  (`params.key` = fingerprint).
- `cron/add.params`: `{name,schedule,command}` → writes `/etc/cron.d/cm-<name>`.

### 1.4 Package management `/pkg/*`

| Method | Path | Parameters | Top-level response | Audit |
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
| GET | `/pkg/search-in-source` | `q,uri,limit` | (search results within a repository) | – |
| GET | `/pkg/test-mirror` | `url` | `{id,name,url,reachable,http_code,latency_ms,release,ok}` | – |
| POST | `/pkg/switch-mirror` | body | `{ok,output}` | pkg.mirror.switch |
| POST | `/pkg/preview` | body | `{ok,output}` | pkg.preview |
| POST | `/pkg/action` | body (`{action,names,force}` etc., see page-packages.md) | `{ok,output}` | pkg.action |
| POST | `/pkg/job` | body `{action,names[],force}` | `{id}` (job started, returns immediately) | pkg.job |
| GET | `/pkg/job` | query `id` (omitted = the most recent job) | the job object while running; `null` when there is none; unknown id → 404 | – |

### 1.5 Database management `/db/*`

> `checkEngine()` accepts only `postgres` / `mysql` (`dbadmin.js:L200-L202`); the additional
> engines (sqlite/db2/redis/mongo) go through dbmulti. On this host the postgres service is
> not running and mysql is not installed → most executing endpoints return 400
> `{error,output}` (see the measured facts in §0).

**GET (`dbG`)**: `/db/status`, `/db/databases`, `/db/tables`, `/db/table/schema`,
`/db/roles`, `/db/backups`, `/db/insights`, `/db/objects`, `/db/statements`, `/db/schemas`,
`/db/table/storage`, `/db/missing-indexes`, `/db/extra-monitor` (all take `engine`, most also
`database`/`table`).

Key response shapes (measured on this host plus source):

- `/db/status?engine=postgres|mysql` → `{engine,version:{},stats:{},processes:[…],variables:[…],capabilities:{…}}` (`dbadmin.js:L205-L236`; 400 on this host).
- `/db/databases` → array `[{name,owner,size,size_bytes,encoding,collation,is_template,created,connections,tables}]` (pg) / `[{name,owner,size,size_bytes,encoding,collation,tables,created,connections}]` (mysql, `dbadmin.js:L239-L263`; 400 on this host).
- `/db/insights`, `/db/objects`, `/db/extra-monitor` → `{engine,database,sections:[{title,columns,rows,error,note}]}` — a **section-tolerant structure**: a failure in one section appears in that section's `error` rather than failing the whole call.
- `/db/statements` → `{available:bool,hint:str,rows:[],columns:[]}` (on this host `available:false` with a hint that the `pg_stat_statements` extension is missing and needs a PostgreSQL restart).
- `/db/backups` → `{dir,items:[{name,size,uploaded,export,engine,database,mode,mtime}]}`.
- `/db/engines` (dbmulti) → `{engines:[{id,name,kind,installed,active,service,packages[],needsRepo?}]}` (4 entries on this host: sqlite/db2/redis/mongo; **postgres/mysql are not included** — those come from `GET /databases`).
- `/db/kv/keys` → `{keys[],cursor,db,pattern}`; `/db/kv/value` → `{type,key,value,ttl}`; `/db/kv/config` → `{ok:true,output:"",rows:[]}` (the empty shape when redis is not running).

**POST**: `/db/table/data` (not audited), `/db/table/insert|update|delete|action`,
`/db/database/create|drop|rename|alter`, `/db/maintenance`, `/db/maintenance/plan`,
`/db/role`, `/db/kill`, `/db/console`, `/db/backup`, `/db/backup/cleanup`,
`/db/backup/delete`, `/db/restore`, `/db/schema/action`, `/db/schema/manage`,
`/db/table/bulk`, `/db/table/copy-db`, `/db/table/backup`, `/db/table/search`,
`/db/explain/tree`, `/db/sequence`, `/db/object`, `/db/session/kill`, `/db/export`,
`/db/count`, `/db/import`, `/db/extension`, `/db/engine/install`, `/db/engine/service`,
`/db/kv/set`, `/db/kv/delete`, `/db/kv/config`, and
`/db/doc/find|insert|update|delete|collection|indexes|index|index/drop|shell`.
Request body fields and per-endpoint responses are in `docs/api/page-databases.md`.

- Generic success shape: `{ok:true,output:"…"}` (dbadmin's `ok()`), sometimes with
  `columns/rows/durationMs`.
- Read-only entry points (such as `/db/explain/tree`) are checked server-side by
  `assertReadOnlySingleStatement()`: 400 when the statement is not a single one, or when it
  contains a write operation that the read-only entry forbids (`dbadmin.js:L120-L133`).
- `/db/backup/download?name=` GET: binary; 400 illegal file name, 404 file not found.
- `/db/upload?name=` POST: raw body; an empty body is 400.

### 1.6 Relay `/relay/*` and relay ops `/relayops/*`

| Method | Path | Parameters | Response | Page | Audit |
|---|---|---|---|---|---|
| GET | `/relay/available` | – | `{ok:bool,data:{ok,rows_total,rows_ingested,last_ingest,uptime_s,ingest_error,ingest_offset,lines_skipped,db_bytes},host,port}` | global (nav probe, cached 15s) | – |
| GET | `/relay/<name>` | passed through to the collector (29 names, see `RELAY_GET` in index.js) | varies per endpoint; samples in `api-dump/relay_*.json`; `/relay/ips` is 404 on this host | relay, slo | – |
| POST | `/relay/action` | `{name,payload}` | per endpoint | relay | relay.action |
| GET | `/relayops/ratelimit` | – | `{present,zones:[{type,name,size}],applied:[{context,kind,zone,value}],configRaw,windowLines,limited429,byHour,dist:[{code,count}],errorLimitCount,fail2banActive,jails:[{name,currentlyBanned,totalBanned,ips}],logFiles:{access,error,conf}}` | slo | – |
| GET | `/relayops/slo` | `range` | `{range,calls,ok,e5,e4,e0,retried,successRate,errorRate,availability,avgRt,maxRt,avgTtfb,latency:{p50,p95,p99},byRoute:[…],errors:[{status,n}],timeline:[{bucket,calls,ok,e5}]}` | slo | – |
| GET | `/relayops/slo-prev` | `range` | `{range,calls,ok,e5,e4,retried,successRate,availability,avgRt,maxRt}` | slo | – |
| GET | `/relayops/slo-budget` | – | `{target,periodDays,allowedErrorRatePct,period:{calls,bad,e4,allowedErrors,consumedPct,remainingPct},windows:[{window,calls,bad,e4,errorRatePct,burnRate,exhaustDays,status}]}` | slo | – |
| GET | `/relayops/hitrate` | `range` | `{range,plans:[{plan,label,calls,hit,miss,denominator,e402,e403,e429,e5,e0,client4xx,…}],buckets[],series:[{plan,label,color,points,calls}],totals:{calls,hit,miss,client4xx,abort,retried,denominator,hitRate,rawSuccessRate},planLabels:{…}}` | slo | – |
| GET | `/relayops/tokenhit` | `range` | `{range,vendor:{events,sessions,inputNew,cacheRead,cacheWrite,output,hitRate,coverageEvents,coveragePct,byModel[],byDay[]},reuse:{sessions,multiTurnSessions,singleTurnSessions,turns,totalBytes,reuseBytes,newBytes,reuseRate,avgSessionReuse,top[],byDay[],byModel[]},gap,live:{…}}` | slo | – |
| GET | `/relayops/usage` | `range` | `{range,events,sessions,totalTokens,totalSecs,byModel:[{model,calls,in_tok,out_tok,cache_r,cache_w,reason_tok,total,secs,sessions,priced,effectiveInput,…}],byDay,rates:{perHour1h,perHour6h,perDay},quota:{rolling,weekly,monthly:{status,percent,resetsAt}},quotaFetchedAt,quotaHistory,forecast:{…},pricing:{currency,present}}` | slo | – |
| GET | `/relayops/correlate` | `hours` (1–720), `window` (5–180) | `{hours,windowMin,summary:{anomalies,withCauses,ratioPct},changeCount,links:[{anomaly,causes,likely}]}` | slo | – |
| GET | `/relayops/audit` | `range,key,limit` | `{available,range,key,limit,keys[],logFile,rules[],storage,events:[{serial,ts,key,comm,exe,uid,pid,success,cmd,cwd,files}],total,counts:{…}}` | audit | – |
| GET | `/relayops/health` | – | `{available,reason,checks:[{name,ok,since,lastSent}],summary:{total,failing,ok},failingNames,log[],backup:{…},verify:{…},dbguard:{…},diskguard:{…},smtp:{to[],host,port,ssl,user,from},timers:[{unit,activates,next,leftUs,last,left}],units[],sources:{state,log,script}}` | healthalerts | – |
| POST | `/relayops/alert/test` | – | `{ok,…}` | healthalerts | relay.alert.test |
| POST | `/relayops/backup/run` | – | `{ok,…}` | healthalerts, backups | relay.backup.run |
| POST | `/relayops/backup/delete` | `{name}` | `{ok,…}` | healthalerts, backups | relay.backup.delete |
| GET | `/relayops/backup/download` | `name` | binary | – | relay.backup.download |

### 1.7 Static and non-API paths

- `GET /panel`, `/panel/`, `/panel/index.html` → 302 to `/panel/public/index.html` (the web
  shell; unrelated to the KMP client).
- `GET /panel/public/**`, `/panel/vendor/*`: static assets (not used by the app; only there to
  understand the web version).
- `GET /api/*` without the basePath → 404 `{"error":"not found"}` (`index.js:L497`).

## 2. Implementation notes for the replica (for whoever writes the code)

1. **Every endpoint sits under the `/panel/api` prefix**; `PanelApi.get/post` already exists
   (`core/Api.kt`) — pass a relative `/xxx` path.
2. **Parse errors uniformly as `{error}` / `{error,output}`**: `ApiException(message, code,
   output, isAuth)` already carries all four, and page layers show `output` in a dialog.
3. **Polling pages** use `CM.me.refreshMs` (from `/me.refreshMs`, default 2000ms, changeable in
   settings; the WS `refresh-interval` message can also change it). On the KMP side that is
   `Hub.refreshMs` (already implemented in `core/Hub.kt`).
4. **Live dashboard data goes over `/ws`** (`hello` replay plus the `metrics` stream);
   page-level REST polling is only for when there is no WS source.
5. **Auditing happens only server-side**; the client does not handle it, but UI wording such as
   "the action was recorded" follows what the page script is told.
6. **401 → clear the token and return to the login screen** (`AppState.expireSession()` exists).
7. Use `PanelApi.download` for download endpoints (do not parse them as JSON).

## 3. Related files

- Per-page contracts: `docs/api/page-<id>.md` (20 files)
- Ops extensions (built by this project, not upstream): `docs/api/ops.md` — prefix
  `/panel/api/ops/*`, mounted by `server/ops/index.js`. These endpoints are absent from the
  upstream route table and answer 404 when the module is not deployed.
- WebSocket: `docs/api/ws.md`
- Feature reconciliation matrix: `docs/api/feature-matrix.md`
- Supplementary samples and scripts: `docs/findings/extra-api/`, `docs/findings/extra_dump.py`,
  `docs/findings/summarize_dumps.py`
