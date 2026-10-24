# 远程健康基线（tests/baseline.md）

> 自动生成：`python tests/collect_baseline.py`（testing 角色，全部只读命令）。
> 用途：面板/主机任何异常时的**回归比对基准**；也用于确认复刻 App 所依赖的运行时事实（监听端口、限流、引擎可用性）没有变化。

- 采集时间：2026-10-16 14:32:13 +0800
- 目标：`http://8.153.153.176/panel`（SSH root@8.153.153.176，key: `~/.ssh/oc_relay_ed25519`）
- 面板监听：`127.0.0.1:9000`（`/panel` base），前置 Nginx 暴露 80 端口

## HTTP 探测

| 探测点 | URL | 经由 | 状态 | 响应摘要 |
|---|---|---|---|---|
| 公网 /panel/api/health | `http://8.153.153.176/panel/api/health` | 本机直连 | 200 | `{"ok":true,"name":"cloud-monitor","version":"1.0.0","ts":1792132332352}` |
| 公网 /panel/public/index.html | `http://8.153.153.176/panel/public/index.html` | 本机直连 | 200 | `<!DOCTYPE html> <html lang="zh-CN"> <head> <meta charset="UTF-8"> <meta name="viewport" content="wid` |
| 服务器本机 127.0.0.1:9000/panel/api/health | `http://127.0.0.1:9000/panel/api/health` | SSH curl（远程机本机端口） | 200 | `{"ok":true,"name":"cloud-monitor","version":"1.0.0","ts":1792132333332}` |

注意：本机（Windows）探测必须**绕过系统代理**（如 127.0.0.1:7890），否则代理对裸 HTTP 返回空 502 —— 那是本机代理问题，不是服务器故障。

### 机器可读健康摘要（服务器本机端口）

```json
{
 "ok": true,
 "name": "cloud-monitor",
 "version": "1.0.0",
 "ts": 1792132333332
}
```

## 远程命令基线

### 时间与内核

```
$ date -Is; uptime; uname -a
2026-10-16T14:31:58+08:00
 14:31:58 up 16:09,  1 user,  load average: 0.22, 0.40, 0.31
Linux iZuf66cq9462zor8d12o65Z 7.0.0-31-generic #31-Ubuntu SMP PREEMPT_DYNAMIC Sat Aug  1 04:26:38 UTC 2026 x86_64 GNU/Linux
```

rc=0，耗时 1.9s

### 发行版

```
$ grep -E '^(NAME|VERSION)=' /etc/os-release
NAME="Ubuntu"
VERSION="26.04.1 LTS (Resolute Raccoon)"
```

rc=0，耗时 0.6s

### CPU / 内存

```
$ nproc; free -m | head -3
2
               total        used        free      shared  buff/cache   available
Mem:            1735         807         215         106         989         928
Swap:           8191         268        7923
```

rc=0，耗时 2.0s

### 磁盘用量

```
$ df -hT -x tmpfs -x devtmpfs -x squashfs | head -15
Filesystem     Type      Size  Used Avail Use% Mounted on
/dev/vda3      ext4       40G   13G   25G  34% /
efivarfs       efivarfs  256K   33K  219K  14% /sys/firmware/efi/efivars
/dev/vda2      vfat      197M  6.3M  191M   4% /boot/efi
```

rc=0，耗时 1.9s

### 面板服务状态

```
$ systemctl status cloud-monitor --no-pager -l 2>&1 | head -25
● cloud-monitor.service - Cloud Monitor web panel (monitoring & configuration)
     Loaded: loaded (/etc/systemd/system/cloud-monitor.service; enabled; preset: enabled)
     Active: active (running) since Fri 2026-10-16 12:51:43 CST; 1h 40min ago
 Invocation: a57bce872de0416c905563de560e46b2
   Main PID: 154684 (node)
      Tasks: 11 (limit: 100)
     Memory: 153.5M (high: 256M, max: 400M, available: 102.4M, peak: 258.1M, swap: 268.5M, swap peak: 305.5M)
        CPU: 3min 52.040s
     CGroup: /system.slice/cloud-monitor.service
             └─154684 /usr/local/bin/node --max-old-space-size=256 --max-semi-space-size=8 server/index.js

Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175188]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175188]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175209]: (to postgres) root on none
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175209]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175209]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175230]: (to postgres) root on none
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175230]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175230]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:27:26 iZuf66cq9462zor8d12o65Z node[154684]: [2026-10-16T06:27:26.554Z] api error: GET /panel/api/pkg/search-in-source?q=nginx - 请指定软件源
Oct 16 14:27:27 iZuf66cq9462zor8d12o65Z node[154684]: [2026-10-16T06:27:27.009Z] api error: GET /panel/api/pkg/test-mirror - 请指定镜像
```

rc=0，耗时 0.5s

### 面板进程

```
$ ps -ef | grep -E 'node .*cloud-monitor|node .*index.js' | grep -v grep
root      154684       1  1 12:51 ?        00:01:13 /usr/local/bin/node --max-old-space-size=256 --max-semi-space-size=8 server/index.js
```

rc=0，耗时 0.4s

### 监听端口

```
$ ss -ltnp | head -20
State  Recv-Q Send-Q Local Address:Port Peer Address:PortProcess                                                  
LISTEN 0      4096      127.0.0.54:53        0.0.0.0:*    users:(("systemd-resolve",pid=483,fd=19))               
LISTEN 0      4096   127.0.0.53%lo:53        0.0.0.0:*    users:(("systemd-resolve",pid=483,fd=17))               
LISTEN 0      5          127.0.0.1:8787      0.0.0.0:*    users:(("python3",pid=1277,fd=6))                       
LISTEN 0      4096         0.0.0.0:22        0.0.0.0:*    users:(("sshd",pid=81815,fd=3),("systemd",pid=1,fd=71)) 
LISTEN 0      5          127.0.0.1:8790      0.0.0.0:*    users:(("python3",pid=1151,fd=3))                       
LISTEN 0      511          0.0.0.0:80        0.0.0.0:*    users:(("nginx",pid=56561,fd=7),("nginx",pid=1268,fd=7))
LISTEN 0      511        127.0.0.1:9000      0.0.0.0:*    users:(("node",pid=154684,fd=27))                       
LISTEN 0      4096            [::]:22           [::]:*    users:(("sshd",pid=81815,fd=4),("systemd",pid=1,fd=74)) 
LISTEN 0      511             [::]:80           [::]:*    users:(("nginx",pid=56561,fd=8),("nginx",pid=1268,fd=8))
```

rc=0，耗时 0.5s

### Node 版本

```
$ /usr/local/bin/node -v 2>/dev/null || node -v
v22.20.0
```

rc=0，耗时 0.4s

### 面板版本

```
$ cat /opt/cloud-monitor/package.json 2>/dev/null | head -20
{
  "name": "cloud-monitor",
  "version": "1.0.0",
  "private": true,
  "description": "Real-time cloud server monitoring & configuration panel (runs on the server itself)",
  "main": "server/index.js",
  "scripts": {
    "start": "node server/index.js",
    "deploy": "node tools/deploy.js"
  },
  "dependencies": {
    "@xterm/addon-fit": "^0.10.0",
    "@xterm/xterm": "^5.5.0",
    "chart.js": "^4.4.7",
    "express": "^4.21.2",
    "ssh2": "^1.16.0",
    "ws": "^8.18.0"
  }
}
```

rc=0，耗时 2.0s

### 面板最近日志

```
$ journalctl -u cloud-monitor --no-pager -n 25 2>&1 | tail -25
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175081]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175081]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175102]: (to postgres) root on none
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175102]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175102]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175123]: (to postgres) root on none
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175123]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175123]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175146]: (to postgres) root on none
Oct 16 14:26:58 iZuf66cq9462zor8d12o65Z su[175146]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175146]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175167]: (to postgres) root on none
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175167]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175167]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175188]: (to postgres) root on none
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175188]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175188]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175209]: (to postgres) root on none
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175209]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175209]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175230]: (to postgres) root on none
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175230]: pam_unix(su-l:session): session opened for user postgres(uid=105) by (uid=0)
Oct 16 14:26:59 iZuf66cq9462zor8d12o65Z su[175230]: pam_unix(su-l:session): session closed for user postgres
Oct 16 14:27:26 iZuf66cq9462zor8d12o65Z node[154684]: [2026-10-16T06:27:26.554Z] api error: GET /panel/api/pkg/search-in-source?q=nginx - 请指定软件源
Oct 16 14:27:27 iZuf66cq9462zor8d12o65Z node[154684]: [2026-10-16T06:27:27.009Z] api error: GET /panel/api/pkg/test-mirror - 请指定镜像
```

rc=0，耗时 0.6s

### 面板数据目录

```
$ du -sh /var/lib/cloud-panel/* 2>/dev/null | head -15
52K	/var/lib/cloud-panel/backups
164K	/var/lib/cloud-panel/logs
8.0K	/var/lib/cloud-panel/tmp
```

rc=0，耗时 0.5s

### 中继采集器

```
$ systemctl list-units --all --no-pager | grep -E 'relay' | head -25
relay-alert.service                                                                                                    loaded    inactive dead      OpenCode relay health check + email alert
  relay-backup.service                                                                                                   loaded    inactive dead      OpenCode relay daily state backup (sqlite + keys + configs)
  relay-daily-report.service                                                                                             loaded    inactive dead      OpenCode relay daily report email
  relay-dbguard.service                                                                                                  loaded    inactive dead      Relay database integrity guard (self-heal, fall back to newest backup)
  relay-disk-hygiene.service                                                                                             loaded    inactive dead      OpenCode relay disk hygiene (logs, journal, database, backups)
  relay-diskguard.service                                                                                                loaded    inactive dead      Disk watermark guard (auto cleanup at 90%, escalate at 95%)
  relay-modelcatalog.service                                                                                             loaded    inactive dead      OpenCode relay model catalog (single source of truth for client configs)
  relay-modelcheck.service                                                                                               loaded    inactive dead      Daily OpenCode model availability probe
  relay-selfheal.service                                                                                                 loaded    inactive dead      OpenCode relay self-healing watchdog (checks + auto-repair)
  relay-stats.service                                                                                                    loaded    active   running   OpenCode relay stats collector and dashboard API
  relay-usagetap.service                                                                                                 loaded    active   running   OpenCode relay token-usage tap (inline usage recorder)
  relay-verify.service                                                                                                   loaded    inactive dead      Relay backup restore drill (verify the newest backup really restores)
  relay-alert.timer                                                                                                      loaded    active   waiting   Run relay health check every 5 minutes
  relay-backup.timer                                                                                                     loaded    active   waiting   Run relay backup once a day
  relay-daily-report.timer                                                                                               loaded    active   waiting   Send the OpenCode relay daily report at 00:05
  relay-dbguard.timer                                                                                                    loaded    active   waiting   Check relay.db integrity every 5 minutes
  relay-disk-hygiene.timer                                                                                               loaded    active   waiting   Run relay disk hygiene weekly
  relay-diskguard.timer                                                                                                  loaded    active   waiting   Check disk watermark every 5 minutes
  relay-modelcatalog.timer                                                                                               loaded    active   waiting   Refresh the relay model catalog every 6 hours
  relay-modelcheck.timer                                                                                                 loaded    active   waiting   Run the OpenCode model probe every day
  relay-selfheal.ti
```

rc=0，耗时 0.7s

### 中继采集器日志

```
$ journalctl -u relay-collector --no-pager -n 8 2>&1 | tail -8; systemctl status relay-selfheal.timer relay-alert.timer --no-pager 2>&1 | head -12
-- No entries --
● relay-selfheal.timer - Run the OpenCode relay self-healing watchdog every minute
     Loaded: loaded (/etc/systemd/system/relay-selfheal.timer; enabled; preset: enabled)
     Active: active (waiting) since Thu 2026-10-15 22:22:57 CST; 16h ago
 Invocation: 1efa7a43a613468680030b9a53a68653
    Trigger: Fri 2026-10-16 14:32:36 CST; 27s left
   Triggers: ● relay-selfheal.service

Oct 15 22:22:57 iZuf66cq9462zor8d12o65Z systemd[1]: Started relay-selfheal.timer - Run the OpenCode relay self-healing watchdog every minute.

● relay-alert.timer - Run relay health check every 5 minutes
     Loaded: loaded (/etc/systemd/system/relay-alert.timer; enabled; preset: enabled)
     Active: active (waiting) since Thu 2026-10-15 22:22:57 CST; 16h ago
```

rc=0，耗时 0.6s

### Nginx 面板配置

```
$ grep -rn -E 'proxy_pass|location|listen ' /etc/nginx/ 2>/dev/null | grep -E '9000|panel|listen' | head -20
/etc/nginx/nginx.conf.mem-bak:75:#		listen     localhost:110;
/etc/nginx/nginx.conf.mem-bak:81:#		listen     localhost:143;
/etc/nginx/sites-available/opencode-relay.before-bare-20261014134950:60:    listen 80 default_server so_keepalive=30:10:3;
/etc/nginx/sites-available/opencode-relay.before-bare-20261014134950:61:    listen [::]:80 default_server so_keepalive=30:10:3;
/etc/nginx/sites-available/opencode-relay.before-bare-20261014134950:85:    location = /panel { return 301 /panel/; }
/etc/nginx/sites-available/opencode-relay.before-bare-20261014134950:86:    location /panel/ {
/etc/nginx/sites-available/opencode-relay.before-bare-20261014134950:87:        proxy_pass http://127.0.0.1:9000;
/etc/nginx/sites-available/opencode-relay.pre-limit-20261014210425:60:    listen 80 default_server so_keepalive=30:10:3;
/etc/nginx/sites-available/opencode-relay.pre-limit-20261014210425:61:    listen [::]:80 default_server so_keepalive=30:10:3;
/etc/nginx/sites-available/opencode-relay.pre-limit-20261014210425:85:    location = /panel { return 301 /panel/; }
/etc/nginx/sites-available/opencode-relay.pre-limit-20261014210425:86:    location /panel/ {
/etc/nginx/sites-available/opencode-relay.pre-limit-20261014210425:87:        proxy_pass http://127.0.0.1:9000;
/etc/nginx/sites-available/default:22:	listen 80 default_server;
/etc/nginx/sites-available/default:23:	listen [::]:80 default_server;
/etc/nginx/sites-available/default:27:	# listen 443 ssl default_server;
/etc/nginx/sites-available/default:28:	# listen [::]:443 ssl default_server;
/etc/nginx/sites-available/default:80:#	listen 80;
/etc/nginx/sites-available/default:81:#	listen [::]:80;
/etc/nginx/sites-available/opencode-relay.bak.cm.20261013175556:60:    listen 80 default_server so_keepalive=30:10:3;
/etc/nginx/sites-available/opencode-relay.bak.cm.20261013175556:61:    listen [::]:80 default_server so_keepalive=30:10:3;
```

rc=0，耗时 0.5s

### 数据库引擎进程

```
$ ps -ef | grep -E 'postgres|mysqld|redis-server|mongod' | grep -v grep | head -10
(无输出)
```

rc=0，耗时 1.6s

### 系统服务失败项

```
$ systemctl --failed --no-pager --plain 2>/dev/null | head -10
UNIT              LOAD   ACTIVE SUB    DESCRIPTION
logrotate.service loaded failed failed Rotate log files

Legend: LOAD   → Reflects whether the unit definition was properly loaded.
        ACTIVE → The high-level unit activation state, i.e. generalization of SUB.
        SUB    → The low-level unit activation state, values depend on unit type.

1 loaded units listed.
```

rc=0，耗时 1.3s

## 回归比对要点

1. `cloud-monitor.service` 必须 `active (running)`，且 `ps` 里有 `node ... server/index.js`；
2. 面板进程必须监听 `127.0.0.1:9000`，Nginx 暴露 `/panel`；
3. `/panel/api/health` 返回 `{ok:true,name:'cloud-monitor',version:'1.0.0',ts}`；
4. API 限流 1800 次/分钟/IP（`server/auth.js`），登录失败 8 次锁 10 分钟 —— 自动化测试必须限速；
5. 引擎可用性（postgres/redis/mongo/docker）会随服务器状态变化，App 与测试都要按「引擎不可用 → 展示 `{error,output}`」处理，不得当异常崩溃。

