# Server Cloud Monitor

This repository ships the **Cloud Monitor** stack end to end:

1. **Panel server** (repository root) — Node/Express under `/opt/cloud-monitor`, API and browser UI at `/panel/`, plus ten ops extensions (uptime checks, alert rules, notification channels, managed hosts, file browser, security baseline scans, disk cleanup, service watchdog, remote metrics, **PHP site hosting**) and the **device hardware-metrics agent** collector.
2. **RelayHub** (`relayhub/`) — optional intranet-tunnel service: `frps` plus a stdlib-only Python panel on `127.0.0.1:8100`, exposed at `/relayhub/`. The main panel proxies it and its nav item self-hides when it is absent.
3. **NoteBox** (`notebox/`) — Flask + SQLite notes and todos on port **13345** by default: Python service, browser UI (`notebox/app/notebox/static/`), and API used by the native client’s Notes section.
4. **Native client** (`client/`) — Kotlin Multiplatform + Compose Multiplatform for **Android**, **desktop** and **iOS**. Implements the panel UI and NoteBox UI against the HTTP APIs above (no WebView for the panel shell).

Typical deployment: Ubuntu; panel on `127.0.0.1:9000` behind nginx at `/panel`; RelayHub on `127.0.0.1:8100` behind nginx at `/relayhub/` with `frps` on `7000`; NoteBox on `0.0.0.0:13345` (restrict in firewall); clients configured at runtime.

Repository: <https://github.com/Dingji/Server-Cloud-Monitor>

---

## Repository layout

```
Server-Cloud-Monitor/
├── install.sh, uninstall.sh, deploy-remote.sh   # panel install (repo root)
├── app/, vendor/                                 # panel code + offline node_modules
│   └── agent.py                                  # device agent served to intranet hosts
├── MANIFEST.*                                    # panel sync fingerprint
├── tools/repo_verify.js                          # bundle self-check (syntax/requires/nav)
├── relayhub/
│   ├── deploy.sh                                 # frps + panel + systemd + port pool
│   ├── relayhub-nginx.py                         # managed nginx blocks (check|apply|remove)
│   └── panel/                                    # app.py (stdlib), static UI, device install.sh
├── notebox/
│   ├── install.sh, uninstall.sh                  # NoteBox install
│   ├── app/                                      # Flask app → /opt/notebox/app
│   │   ├── run.py, requirements.txt
│   │   └── notebox/                              # routes, db, static web client
│   └── client/README.md                          # where web vs KMP clients live
├── client/                                       # KMP app (panel + NoteBox screens)
│   ├── composeApp/
│   ├── gradle/, gradlew, gradlew.bat
│   └── build.ps1
└── README.md
```

| Path | Role |
|------|------|
| `app/server/` | Panel Express app, collectors, `ops/`, `ops/php/`, `devmetrics.js` |
| `app/public/` | Panel browser UI, one lazy-loaded file per nav page |
| `app/agent.py` | Push-based device agent (`POST /agent/push`), served pre-auth for curl install |
| `relayhub/panel/app.py` | frps supervision + tunnel mappings API on `127.0.0.1:8100` |
| `notebox/app/` | NoteBox WSGI app and static web UI |
| `notebox/app/notebox/db.py` | SQLite schema, settings, `NB_DATA_DIR` |
| `client/composeApp/.../core/NoteBox.kt` | NoteBox HTTP client (session cookie) |
| `client/composeApp/.../pages/Note*.kt` | Native NoteBox screens |

---

## Panel server

### What you get

- **Included:** application code, static UI, vendored `node_modules`, install/uninstall scripts, systemd and nginx helpers, the device agent (`app/agent.py`), and the optional RelayHub service source under `relayhub/`.
- **Excluded** (per-host secrets or state — never copied from a live box):

| Item | Reason |
|------|--------|
| `config.json` | Login scrypt hash, SSH passwords, DB credentials |
| `devices.json` | Device-agent registry and per-device tokens; created on first `/devices/register` |
| `keys/pty_key` | Terminal host key; `install.sh` runs `ssh-keygen` on the target |
| `backups/`, `logs/`, `tmp/` | Host-specific data |
| `runtime/` | Optional toolchain copy on some hosts; not required to run |
| `/var/lib/cloud-panel/` | Metrics DB, backups, ops state (tasks, rules, channels, hosts, trash) |
| `/var/lib/relay/devmetrics.db` | Tiered device-metric time series (500 ms → 5 min), created by `devmetrics.js` |
| `relayhub/panel/config.json` | frps token, dashboard password, session secret, `internal_key` |
| `relayhub/panel/mappings.json`, `port_usage.json` | Tunnel state on the relay box |
| `relayhub/frps`, `relayhub/downloads/*.tar.gz` | ~20 MB binaries; fetched per-architecture at deploy time |

A new install has empty ops configuration unless you restore `/var/lib/cloud-panel/ops` from another host (see [Migrating ops state](#migrating-ops-state)).

`ssh2` optional native `.node` addons are stripped from the vendor tarball; SSH works via the JS implementation. Run `npm rebuild ssh2` on the target if you want native builds.

### Panel feature inventory

Everything below is counted from the routes that exist in this bundle, not from a roadmap.

**Browser UI** (`app/public/`, one lazily-loaded file per sidebar entry) — twenty permanent pages:
仪表盘 · 健康告警 · SLO 与用量 · 进程管理 · 磁盘管理 · 内存管理 · 网络管理 · 安全管理 · 系统服务 · 软件包管理 · 数据库 · Docker 容器 · 网站 / Nginx · **PHP 站点** · 定时任务 · 备份中心 · 日志中心 · 审计日志 · Web 终端 · 面板设置.

Two entries are added only when their probe answers, so a host that lacks the backing service never shows a dead page:

| Page | Probe | Requires |
|------|-------|----------|
| OpenCode 中继 | `GET /api/relay/available` | the OpenCode relay on `127.0.0.1:8787` — a separate project, not bundled here |
| 内网中继监控 | `GET /api/relayhub/available` | [RelayHub](#relayhub-relayhub): tunnels **and** the device hardware charts |

**Ops extensions** (`app/server/ops/`) expose REST under `/api/ops/…` and **have no browser page** — `app/public/` reaches `/ops/*` only from `php.js`. The native client is what renders them, each as its own sidebar entry. The whole layer mounts through one guarded line in `server/index.js`:

```js
try { require('./ops').mount(api, { wrap, audited }); } catch (e) { /* panel still boots */ }
```

so a missing dependency degrades one module instead of aborting the boot. That is also why `install.sh` probes each module after startup rather than trusting a green service.

| Module | Routes | Covers |
|--------|-------:|--------|
| `uptime.js` | 8 | http / tcp / ping / dns probes, task scheduling, 24 h SLA rollup |
| `alerting.js` | 9 | threshold rules, event log, silences, rule dry-run |
| `notify.js` | 6 | channels: dingtalk / feishu / wecom / telegram / mail / webhook; delivery log, connectivity test |
| `hosts.js` | 4 | managed-host inventory, reachability check |
| `files.js` | 23 | tree, list, search, preview, stat, usage, upload/download, write, touch, mkdir, copy, move, rename, chmod/chown, archive/extract, delete → trash → restore/empty |
| `secops.js` | 5 | security baseline checks, report, trend, remediation |
| `disk.js` | 11 | big files, top directories, log events/history, truncate, scheduled cleanup |
| `remote.js` | 5 | SSH collection from other hosts, credential vault, history |
| `watchdog.js` | 6 | service supervision with auto-restart and budget reset |

**Added in this sync**, with where each one is actually rendered:

| Feature | Backend | Browser page | Native page |
|---------|---------|--------------|-------------|
| PHP site hosting | `ops/php/` — 49 routes grouped under `overview`, `site`/`sites`, `deploy`/`rollback`/`releases`, `env`/`ext`/`ini`/`fpm`/`opcache`/`phpinfo`, `db`, `ssl`, `logs`/`stats`, `jobs`, `upload`, `webhook` | `js/pages/php.js` | `pages/PhpSitesPage.kt` |
| Device hardware metrics | `devmetrics.js` — 10 routes + pre-auth `POST /agent/push` and `GET /agent/agent.py` | charts inside `js/pages/relayhub.js` | `pages/RelayHubPage.kt` |
| RelayHub tunnels | 10 proxied `/relayhub/*` routes (`summary`, `mappings`, `port-pool`, `frpc-config`, `probe-now`) | `js/pages/relayhub.js` | `RelayHubPage.kt`, `RhPortPoolCard.kt`, `RhPortMapCard.kt` |

Native-client-only surfaces: the nine ops modules above, `WinToolsPage.kt` (Windows hosts) and the NoteBox section.

### Server requirements

| | |
|---|---|
| OS | Ubuntu 20.04+ or Debian 11+ (`apt` / `systemctl` / `ufw`; RHEL-like hosts get read-only metrics) |
| User | root |
| Node | **≥ 22** (`node:sqlite` for metrics store) |
| Listen | Default `127.0.0.1:9000`; expose via nginx or `--direct` |
| Disk | ~80 MB app + vendor; data dir grows with retention |
| Memory | systemd `MemoryHigh=256M`, `MemoryMax=400M` |

### Install the panel

**A. Remote push from your workstation**

```bash
# from repository root (where install.sh lives)
./deploy-remote.sh root@TARGET_IP
SM_KEY=~/.ssh/my_key ./deploy-remote.sh root@TARGET_IP
SM_PASS='password' ./deploy-remote.sh root@TARGET_IP   # needs sshpass; prefer keys
./deploy-remote.sh deploy@TARGET_IP --sudo             # passwordless sudo required
./deploy-remote.sh root@TARGET_IP --dry-run
```

Uploads to `/tmp/cloud-monitor-panel-<timestamp>/` and runs `install.sh`. Extra install flags are passed through with safe quoting.

**B. Manual archive**

```bash
tar -czf cloud-monitor-panel.tar.gz --exclude=.git --exclude=client .
scp cloud-monitor-panel.tar.gz root@TARGET_IP:/tmp/
ssh root@TARGET_IP
mkdir -p /tmp/cm-panel && tar -xzf /tmp/cloud-monitor-panel.tar.gz -C /tmp/cm-panel
cd /tmp/cm-panel && bash install.sh
```

**C. Second instance on one host**

```bash
bash install.sh --prefix /opt/cm-second --data-dir /var/lib/cm-second \
                --port 9100 --service cm-second --no-nginx --no-systemd
```

### `install.sh` options

| Flag | Default | Meaning |
|------|---------|---------|
| `--prefix DIR` | `/opt/cloud-monitor` | Application directory |
| `--data-dir DIR` | `/var/lib/cloud-panel` | Data directory (`CM_DATA_DIR`), mode 0700 |
| `--port N` | `9000` | Listen port (`CM_PORT`) |
| `--bind ADDR` | `127.0.0.1` | Bind address; `--direct` → `0.0.0.0` |
| `--base-path /x` | `/panel` | URL prefix; must match clients |
| `--service NAME` | `cloud-monitor` | systemd unit name |
| `--password '...'` | random | First boot only; hash in `config.json`, temp env file removed |
| `--node auto\|skip` | `auto` | `skip` leaves Node unchanged |
| `--no-nginx` | — | Do not touch nginx |
| `--nginx-standalone PORT` | — | Dedicated server block when :80 is taken |
| `--direct` | — | Bind all interfaces, skip nginx |
| `--no-systemd` / `--no-start` / `--no-smoke` | — | Skip unit / start / smoke test |
| `--with-php` | — | Also install the **PHP site** runtime stack (php-fpm, MariaDB, certbot, Composer) |
| `--php-base DIR` | `/srv/php` | Site root the PHP module manages (`CM_PHP_BASE`) |
| `--with-relayhub` | — | Also deploy **RelayHub** (`frps` + relay panel) and wire its nginx routes |
| `--relayhub-dir DIR` | `/opt/relayhub` | RelayHub install directory |
| `--relayhub-port N` | `8100` | RelayHub panel backend port (proxied by the main panel) |
| `--relayhub-pool A-B` | `6000-6999` | Public port pool handed to `frps.allowPorts` and ufw |
| `--no-relayhub-ufw` | — | Deploy RelayHub without touching the firewall |
| `--dry-run` | — | Print plan only |

nginx `auto` mode writes `/etc/nginx/snippets/cloud-monitor-location.conf` and prints an `include` line; it does not edit existing vhosts. On an empty nginx host, `--nginx-standalone 8091` is usually simpler.

### Feature stacks

Two panel features need host-level software the panel itself does not ship. Both are opt-in flags, so a plain `install.sh` never changes more of the machine than asked.

**PHP sites** (`app/server/ops/php/`, nav *PHP 站点*) — per-site php-fpm pool + nginx server block + atomic `releases/current` deploy + optional MariaDB database + certbot HTTPS. `--with-php` detects the distribution's `php*-fpm` version, installs the required extensions as one transaction and the optional ones individually (a missing extension must not abort the stack), then writes conservative MariaDB (`64M` buffer pool, `performance_schema=OFF`) and PHP ini drop-ins.

- The module only *orchestrates*: without php-fpm/MariaDB it still mounts, and endpoints that shell out fail with the command output in the UI.
- `cloud-monitor.service` runs with `PrivateTmp=true`, so archives uploaded from the host's `/tmp` are invisible to the panel — use `/srv/php/uploads`, which the module creates.
- Pool files understand `;` comments only, and `php-fpm -t` takes `-y <file>`; delete a site's pool and reload before `userdel`, or the user leaks.

**Device hardware metrics** (`app/server/devmetrics.js`, rendered on the *内网中继监控* page) — intranet devices (Raspberry Pi, NAS) sit behind NAT, so the panel never dials them: `agent.py` pushes samples to `POST /agent/push`, which is mounted *before* session auth and guarded by a per-device token. The panel keeps a tiered SQLite series (500 ms/6 h → 5 s/48 h → 30 s/30 d → 5 min/90 d) under `/var/lib/relay/devmetrics.db`, broadcasts live over the existing `/ws` hub, and raises thresholds through the same alert bell as ops rules.

- `GET /agent/agent.py` serves the agent source pre-auth so `curl` on a fresh device works; the same file is mirrored to `public/cm-agent.py`.
- The install one-liner is generated by `GET /api/devices/install`.

### RelayHub (`relayhub/`)

Optional intranet-tunnel service the panel's *内网中继监控* page proxies to. Two units:

| Unit | Runs | Listens |
|------|------|---------|
| `relayhub-frps.service` | `/opt/relayhub/frps -c /opt/relayhub/frps.toml` | `0.0.0.0:7000` (WebSocket via nginx), dashboard `127.0.0.1:7500` |
| `relayhub-panel.service` | `python3 app.py` in `/opt/relayhub/panel` | `127.0.0.1:8100` |

`app.py` is standard-library only — no pip install, no virtualenv. It bootstraps its own `config.json` (admin password, salted hash, session secret) on first run.

Deploy it as part of the panel install:

```bash
bash install.sh --nginx-standalone 8091 --with-relayhub
```

or by hand on a box that already has `/opt/relayhub`:

```bash
cp -a relayhub/. /opt/relayhub/
RELAYHUB_SKIP_NGINX=1 bash /opt/relayhub/deploy.sh --port-range 6000-6999
```

`deploy.sh` picks or downloads the matching `frp_<ver>_linux_<arch>.tar.gz`, writes `frps.toml` with a fresh token and `allowPorts` derived from the port pool, installs both units, and (unless told to skip) injects managed `/relayhub/` blocks into an existing nginx vhost. `relayhub-nginx.py check|apply|remove` re-applies or removes those blocks later.

How it connects to the panel:

- The panel reads `/opt/relayhub/panel/config.json` → `internal_key` and sends it as `X-RelayHub-Key` on every `/relayhub/*` proxy call, so the relay API stays loopback-only.
- `install.sh --with-relayhub` generates `internal_key` if absent and writes `/etc/nginx/snippets/relayhub-location.conf` (panel at `/relayhub/`, plus the `/~!frp` WebSocket location that carries `frpc` logins over port 80).
- Without RelayHub, `GET /api/relayhub/available` returns `{"ok":false}` and the nav entry is not added — no dead page.

Devices join with the client installer the panel serves:

```bash
curl -fsSL http://<server>/relayhub/install.sh | bash -s -- --name pi-office --token <token from the RelayHub panel>
```

Security-group note: `7000` (or the WebSocket path) and every port in the public pool must be opened in the cloud console, not only in ufw.

Removing it again is manual (`uninstall.sh` does not own this service):

```bash
# if deploy.sh injected the managed blocks into an existing vhost:
python3 /opt/relayhub/relayhub-nginx.py remove
# if install.sh wrote the snippet instead, just drop the include and the file
systemctl disable --now relayhub-panel relayhub-frps
rm -f /etc/systemd/system/relayhub-{panel,frps}.service /etc/nginx/snippets/relayhub-location.conf
systemctl daemon-reload && systemctl reload nginx && rm -rf /opt/relayhub /var/log/relayhub
```

### After the panel is running

The installer prints the URL and initial password.

- Browser: `http://<host>/panel/` (adjust port/prefix as configured).
- Native client: set the same base URL at login (see [Client configuration](#client-configuration)).

Security:

1. Default bind is loopback. Use nginx + TLS + IP restrictions, or `ssh -L 8080:127.0.0.1:9000 user@host`.
2. `--direct` exposes the panel; restrict the port in your firewall.
3. Change the password in panel settings after first login. `/etc/cloud-monitor.env` is removed; only scrypt hash remains in `config.json` (0600).
4. Eight failed logins → 10 minute lockout. API limit: 1800 requests/minute per IP.
5. Ops file manager and web terminal can modify the host like the built-in terminal.

```bash
systemctl status cloud-monitor
journalctl -u cloud-monitor -f
curl -s -X POST http://127.0.0.1:9000/panel/api/login \
  -H 'Content-Type: application/json' -d '{"password":"YOUR_PASSWORD"}' | head -c 120
```

### Migrating ops state

```bash
# source
tar -czf /tmp/ops-state.tar.gz -C /var/lib/cloud-panel ops
# target (after install.sh)
mkdir -p /var/lib/cloud-panel && tar -xzf /tmp/ops-state.tar.gz -C /var/lib/cloud-panel
systemctl restart cloud-monitor
```

Treat archives containing `ops/notify_channels.json` or `ops/remote_creds.json` as credential material.

### Panel upgrade, rollback, uninstall

- **Upgrade:** run `install.sh` again; previous `server/` copied to `.backup-<timestamp>/`.
- **Rollback:** restore that `server/` tree and `systemctl restart cloud-monitor`.
- **Uninstall:** `./uninstall.sh` or `./uninstall.sh --purge-app --purge-data`.

### Panel troubleshooting

| Symptom | Check |
|---------|--------|
| `EADDRINUSE` | `ss -ltnp \| grep :9000` or change `--port` |
| `EACCES` on bind | Use a higher port or `CAP_NET_BIND_SERVICE` |
| `node:sqlite` unavailable | Node &lt; 22 |
| `ops modules not mounted` | `node --check /opt/cloud-monitor/server/ops/index.js` |
| API errors in UI | `basePath` in `config.json` must match URL prefix |
| WebSocket / terminal | nginx `Upgrade` / `$connection_upgrade` |
| HTTP 429 | Rate limit |
| `apt` pages fail | Not Debian-family or `apt` missing |
| PHP site endpoints error | `systemctl status php*-fpm mariadb`; panel-side orchestration only — see [Feature stacks](#feature-stacks) |
| Uploaded archive "not found" | `PrivateTmp=true`; stage it under `/srv/php/uploads`, not host `/tmp` |
| Device tiles stay empty | No agent has pushed yet; `curl http://127.0.0.1:9000/panel/api/agent/agent.py` should return the script |
| Device stops updating | `devices.json` token mismatch; rotate with `POST /api/devices/rotate` and re-run the installer one-liner |
| 内网中继 nav item missing | `GET /api/relayhub/available` returns `{"ok":false}`; start `relayhub-panel` or drop `--with-relayhub` |
| `/relayhub/*` returns 401 | `internal_key` in `/opt/relayhub/panel/config.json` differs from what the panel loaded; restart `cloud-monitor` |

### Refreshing the server bundle in git

The bundle is a mirror of a running host, so the refresh is scripted rather than hand-copied. From the **ServerMaintance** dev tree with SSH read access to the reference host (`SM_HOST`, `SM_KEY` override the defaults; nothing is written back to the host):

```bash
python tools/server_pull.py                    # panel server/ + public/ + agent.py -> upstream/live-<STAMP>/
STAMP=live-$(date +%Y%m%d) python tools/relayhub_pull.py   # relayhub code, no binaries or secrets
python tools/panel_diff.py                     # only-live / only-repo / changed, LF-insensitive
python tools/sync_to_repo.py --plan            # what would be written into the repo
python tools/sync_to_repo.py                   # byte-exact copy into app/, relayhub/, client/
node tools/repo_verify.js .                    # gate: syntax, requires, nav ids, ops mounts
```

`sync_to_repo.py` copies with `shutil` so the panel's UTF-8 Chinese strings are never re-encoded, and `repo_verify.js` fails on a nav id without a page file or a `require()` that no vendor package satisfies. Line endings are pinned by `.gitattributes`; `MANIFEST.remote-md5.txt` records the fingerprint of the host the bundle came from.

---

## NoteBox (`notebox/`)

NoteBox is a **separate service** from the panel: multi-user notes, folders, todos, sharing, public links, SMTP reminders, and digests. It does not use the panel login token.

| | Panel | NoteBox |
|---|--------|---------|
| URL | `http://<host>/panel` | `http://<host>:13345` (default) |
| Auth | `POST /panel/api/login` → bearer token | `POST /api/login` → `session` cookie |
| Account | Single access password | Email + password per user; admin role |
| Data | `/var/lib/cloud-panel` | `/opt/notebox/data/notebox.db` (default) |

### What is in the tree

- **`notebox/app/`** — Python package (Flask, Waitress, scheduler). Static files under `notebox/static/` are the **browser client** (`index.html`, `app.js`, markdown helpers).
- **`notebox/client/`** — Index of clients: web static files live inside `app/`; native UI is in [`client/`](../client/) (Compose).
- **Not packaged:** live `data/`, `venv/`, SQLite files, uploaded media under `data/uploads/` (created on the host).

### Install NoteBox

On the target host (root):

```bash
cd notebox
chmod +x install.sh uninstall.sh
./install.sh
```

Options: `--prefix /opt/notebox`, `--port 13345`, `--host 0.0.0.0`, `--service notebox`, `--user notebox`, `--no-start`, `--dry-run`.

The script creates user `notebox`, copies `app/` to `/opt/notebox/app`, creates `/opt/notebox/data`, builds `/opt/notebox/venv`, installs `requirements.txt`, and enables systemd unit `notebox.service`.

```bash
systemctl status notebox
journalctl -u notebox -f
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:13345/
```

Open firewall if clients reach the host directly, e.g. `ufw allow 13345/tcp`.

> **Read this before opening 13345.** `notebox/install.sh` binds `0.0.0.0:13345` and the
> service has no reverse proxy in front of it, so an open firewall port means the
> *entire* note store is reachable from the internet with only one thing protecting it:
> the admin password.
>
> On a brand-new database, `_seed_users_and_owners()` creates the first admin from
> `NB_PASS`. If `NB_PASS` is unset, a random password is generated and **printed to the
> journal once** — read it with `journalctl -u notebox | grep 'initial admin password'`
> and change it immediately. There is no default value in this repository, and there must
> never be one again: a credential that ships in a public repo is not a secret, it is an
> instruction manual.
>
> Worth checking on any host you inherited:
>
> ```bash
> ss -ltnp | grep 13345                  # is it on 0.0.0.0, or loopback only?
> ufw status numbered | grep 13345       # is it allowed from Anywhere?
> grep -rn 'environ.get("[A-Z_]*PASS' /opt/notebox/app   # any hardcoded default left?
> ```
>
> Prefer loopback + nginx: run NoteBox with `--host 127.0.0.1`, delete the `13345/tcp`
> rule, and publish it as a `location /notes/ { proxy_pass http://127.0.0.1:13345; }`
> block behind TLS.

### First-time setup

1. Browse to `http://<host>:13345/` and register (email verification flow depends on SMTP settings).
2. In **admin** settings, set **base URL** to the public URL (used in email and share links). Fresh installs ship with an empty default in code.
3. In the KMP app, open any **Notes** sidebar page and sign in with the same NoteBox URL (`http://<host>:13345`) and credentials.

SMTP-related keys live in SQLite settings (`smtp_host`, `smtp_user`, …). Configure via admin API or the native **NoteBox settings** / **Mail** pages when logged in as admin.

### NoteBox API (summary)

| Area | Examples |
|------|-----------|
| Auth | `POST /api/login`, `POST /api/logout`, `POST /api/register`, `GET /api/me` |
| Notes | CRUD under `/api/notes`, folders `/api/folders`, uploads `/api/upload` |
| Todos | `/api/todos` |
| Sharing | `/api/shares`, public `/s/<code>` |
| Admin | `/api/admin/settings`, users, mail test |

Full behavior is defined in `notebox/app/notebox/routes_*.py`. The KMP client mirrors these in `client/composeApp/.../core/NoteBox.kt`.

### Upgrade and uninstall

Re-run `./install.sh` to refresh code; `data/` is preserved.

```bash
./uninstall.sh                  # stop unit, keep /opt/notebox
./uninstall.sh --purge-data     # remove SQLite and uploads tree
```

### NoteBox development

```bash
cd notebox/app
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export NB_DATA_DIR=./data NB_PORT=13345
python run.py
```

Optional tests: `cd notebox/app && pip install pytest && python -m pytest tests`

---

## Native client (`client/`)

### Purpose

The client reimplements the panel’s feature set with **Compose Multiplatform** widgets: same REST and WebSocket contracts as the browser UI (`/panel/api/...`, `/panel/ws`, `/panel/term`). Server URL is configured at runtime; you do not rebuild the app to point at another host.

**Application ID:** `com.servermaintance.panel`  
**Version:** `1.0.0` (`AppInfo.VERSION` may show a patch suffix in the UI)

### Feature coverage (navigation)

| Section | Pages |
|---------|--------|
| Monitoring | Dashboard, health alerts, SLO & usage, uptime checks, alert rules, notification channels, OpenCode relay and RelayHub / intranet device metrics (each shown only when its probe succeeds) |
| Server | Processes, disks, memory, network, security, services, packages, file manager, managed hosts, security baseline, disk governance, service watchdog, remote host metrics |
| Applications | Databases, Docker, web/nginx, PHP sites, cron, backups, logs, audit |
| Notes (NoteBox) | Notes, todos, import/export, mail notifications, NoteBox settings, user admin |
| Tools | Web terminal, Windows host tools (shown on Windows hosts) |
| System | Panel settings |

NoteBox screens call the service in [`notebox/`](#notebox-notebox) (default `http://<host>:13345`). Configure URL and email/password on first use in the Notes section; session is stored separately from the panel token (`notebox_*` keys). See [NoteBox clients](notebox/client/README.md).

### Toolchain

| Component | Version |
|-----------|---------|
| JDK | 17+ for compile; **JDK 21+ with `jpackage`** for desktop installers |
| Android SDK | compile/target SDK **37**, min SDK **26** |
| Gradle | **9.8.1** (wrapper in `client/`) |
| Kotlin | **2.4.21** |
| Android Gradle Plugin | **9.4.1** |
| Compose Multiplatform | **1.12.2** |
| Ktor client | **3.6.0** |

Versions are pinned in `client/gradle/libs.versions.toml`.

### Build on Windows

Install **Android Studio** (or a standalone JDK) and the Android SDK. From `client/`:

```powershell
cd client

# fast compile check (desktop + common)
.\build.ps1 :composeApp:compileKotlinDesktop

# Android debug APK
.\build.ps1 :composeApp:assembleDebug

# desktop installer for current OS
.\build.ps1 :composeApp:packageDistributionForCurrentOS

# run desktop without packaging
.\build.ps1 :composeApp:run

.\build.ps1 tasks --all
```

`build.ps1` sets `JAVA_HOME` (Android Studio JBR or JDK 21) and `ANDROID_HOME` when missing, then invokes the Gradle wrapper.

Desktop MSI/DMG/DEB packaging needs a full JDK with `jpackage` (not the Android Studio JBR alone). Set `DESKTOP_JDK` to that JDK before `packageDistributionForCurrentOS`.

Without `build.ps1`:

```powershell
cd client
.\gradlew.bat :composeApp:compileKotlinDesktop
```

### Build on Linux / macOS

```bash
cd client
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:packageDistributionForCurrentOS
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:run
```

Install a JDK 17+ and Android SDK for Android builds. Accept SDK licenses (`sdkmanager --licenses`) before the first Android task.

### Build outputs

| Artifact | Path (under `client/`) |
|----------|-------------------------|
| Android debug APK | `composeApp/build/outputs/apk/debug/composeApp-debug.apk` |
| Windows MSI | `composeApp/build/compose/binaries/main/msi/CloudMonitor-1.0.0.msi` |
| Linux DEB | `composeApp/build/compose/binaries/main/deb/cloudmonitor_1.0.0-1_amd64.deb` |
| macOS DMG | `composeApp/build/compose/binaries/main/dmg/CloudMonitor-1.0.0.dmg` |

Desktop package name: **CloudMonitor**. Windows installer registers a fixed `upgradeUuid` so upgrades replace the same product entry.

### Client configuration

**Panel base URL must include the path prefix**, e.g. `http://203.0.113.10/panel` or `https://monitor.example.com/panel`. The client appends `/api` for REST and uses `/ws` and `/term` under the same prefix.

Where to set it:

1. **Login screen** — Server connection field → “test connection” (`GET …/api/health`) → panel access password → login.
2. **Settings** (after login) — Server connection section.

Persistence:

| Platform | Store | Keys (panel) |
|----------|-------|----------------|
| Android | `SharedPreferences` `cm_panel` | `server_url`, `server_token`, `remember_password`, `password` |
| Desktop | `java.util.prefs` | same keys |

NoteBox URL, email, password, and session cookie use the `notebox_` prefix in the same stores.

Common mistakes:

| Symptom | Likely cause |
|---------|----------------|
| Network error / connection refused | Wrong host, closed port, or missing `/panel` suffix |
| HTTP 404 / 502 on health check | Wrong path or nginx not proxying to the panel |
| Session expired after URL change | Token cleared when base URL changes; log in again |
| Stuck on “reconnecting” | WebSocket blocked (HTTPS page needs `wss://`, or proxy strips `Upgrade`) |

### Login

- Use the **panel access password** (`POST /panel/api/login`). This is not the Linux or SSH password.
- “Remember password” stores the password locally for auto-login.
- Token is 64 hex characters; default lifetime **168 hours**. HTTP 401 clears the token and returns to the login screen.

### Client source map

| Area | Location |
|------|----------|
| App entry, theme | `composeApp/src/commonMain/.../App.kt`, `ui/Theme.kt` |
| API + WebSocket | `core/Api.kt`, platform engines in `androidMain` / `desktopMain` |
| Page registry | `nav/Nav.kt`, `nav/Navigator.kt` |
| Screens | `pages/*.kt`, `pages/ops/*.kt`, `pages/db/*.kt`, `pages/relay/*.kt` |
| Login / shell | `screens/LoginScreen.kt`, `screens/Root.kt` |
| Desktop window | `desktopMain/.../Main.kt`, `ui/WindowChrome.desktop.kt` |
| Android activity | `androidMain/.../MainActivity.kt` |

### Client troubleshooting

| Issue | What to do |
|-------|------------|
| Gradle “JAVA_HOME” invalid on Windows | Run via `build.ps1` or set `JAVA_HOME` to a JDK `bin/java.exe` |
| `jpackage` not found | Set `DESKTOP_JDK` to a full JDK 21+ for desktop packages |
| Android SDK missing | Set `ANDROID_HOME`, install platform 37 and build-tools 36.x |
| AGP / KMP plugin errors | Use the wrapper versions in `gradle/wrapper/gradle-wrapper.properties`; do not mix random AGP 8.x |
| Desktop runs, Android fails | Run `:composeApp:assembleDebug` and read the first compile error in `commonMain` |
| All pages 401 | Token expired or clock skew; re-login |
| Relay section missing | Expected when the host has no relay collector; optional nav entry |

First build downloads dependencies from Maven Central and Google Maven; allow network access. Enable Gradle caching in `gradle.properties` for faster rebuilds.

---

## End-to-end deployment sketch

```
┌─────────────────┐   /panel/api, /panel/ws     ┌─────────────────────────┐
│  KMP client     │ ──────────────────────────► │ nginx → cloud-monitor   │
│  + browser      │                             │ (127.0.0.1:9000)        │
└────────┬────────┘                             └───────┬─────────────────┘
         │  :13345 /api/ + session cookie               │ /relayhub/* + X-RelayHub-Key
         ▼                                             ▼
┌─────────────────┐                       ┌───────────────────────────────┐
│ notebox.service │                       │ relayhub-panel (127.0.0.1:8100)│
│  web UI at /    │                       │  └ relayhub-frps (0.0.0.0:7000)│
└─────────────────┘                       └───────────────▲───────────────┘
                                                          │ frpc (websocket)
   intranet devices ── POST /panel/api/agent/push ────────┘   (behind NAT: push, never polled)
```

Typical order: `install.sh` (panel, add `--with-php` / `--with-relayhub` as needed) at repo root → `notebox/install.sh` → nginx/TLS and firewall for `/panel`, `/relayhub/`, port 13345 and the frp port pool → build or install KMP client → set panel URL and NoteBox URL → change default panel password and create NoteBox users.

---

## Verification notes

The panel bundle is checked without a Linux host, because "does it come up from a fresh checkout" is a repo property, not a host property:

```bash
node tools/repo_verify.js .                     # 70 .js files parse; every require() resolves
                                                # against vendor/ or a sibling file; 22 nav ids
                                                # ↔ 22 lazy page files; 10 ops modules mounted
tar -xzf vendor/node_modules.tar.gz -C builddir && cp -a app/. builddir
cd builddir && CM_PORT=9111 node server/index.js
```

Last recorded run (2026-10-29, Node 24 on a non-Linux workstation, so host collectors return nulls by design):

| Endpoint | Result |
|----------|--------|
| `POST /panel/api/login` | token issued |
| `GET /panel/api/ops/overview` | `{"ok":true}` — all ten ops extensions mounted |
| `GET /panel/api/devices` | `{"ok":true,"devices":[],"alerts":{…}}` — `devmetrics.js` mounts, SQLite tier store created |
| `GET /panel/api/php/sites` | `[]` — PHP extension mounts (execution endpoints need php-fpm on the host) |
| `GET /panel/api/relayhub/available` | `{"ok":false}` — degrades, nav item stays hidden |
| `GET /panel/api/agent/agent.py` | 25 133 bytes, served before auth |
| `GET /panel/public/js/pages/{php,relayhub}.js` | 64 828 / 97 427 bytes |
| `node server/page_test.js` (with `CM_APP_DIR`) | runtime render of slo / healthalerts / audit |

`MANIFEST.remote-md5.txt` is the md5 fingerprint of the reference host the bundle was copied from; diff a target against it with `python tools/panel_diff.py`.

Not covered by any of the above, and still needing a real Ubuntu host: first-time NodeSource install on a blank VM, nginx `--nginx-standalone` reload on a host that already owns `:80`, `--with-php` (needs apt), `--with-relayhub` (needs frp download + ufw), and PHP site creation end to end.

---

## License

See `LICENSE` in the repository root.
