# Server Cloud Monitor — panel server bundle

Portable install of the **Cloud Monitor** web panel (Node/Express, deployed as `/opt/cloud-monitor` on the reference host). The bundle includes nine ops extensions: uptime checks, alert rules, notification channels, managed hosts, file browser, security baseline scans, disk cleanup, service watchdog, and remote metrics collection.

Clone this repository and run `install.sh` on Ubuntu or Debian. A separate Kotlin Multiplatform client can point at the same HTTP API; see [ServerMaintance](https://github.com/Dingji/ServerMaintance) if published.

Repository: <https://github.com/Dingji/Server-Cloud-Monitor>

The repository root is the deployment package:

```
./
├── install.sh                 # on target: preflight → Node 22 → files → systemd → nginx → smoke test
├── uninstall.sh               # stop unit, remove generated nginx bits (keeps app + data by default)
├── deploy-remote.sh           # push bundle from your workstation and run install.sh over SSH
├── README.md
├── MANIFEST.remote-md5.txt    # per-file md5 vs reference host (61 files)
├── MANIFEST.env.txt           # reference host / Node version metadata
├── app/                       # installed to /opt/cloud-monitor
│   ├── server/                # index.js, core modules, ops/ (13 files incl. send_mail.py)
│   ├── public/                # static UI served under /panel/
│   ├── package.json
│   └── package-lock.json
└── vendor/
    ├── node_modules.tar.gz    # offline deps (~3.3 MB, 1226 files, JS only)
    └── node_modules.README.txt
```

## Contents and exclusions

**Included:** application code, static UI, vendored `node_modules`, install/uninstall scripts, systemd and nginx helpers.

**Excluded** (machine-specific state or secrets):

| Item | Why |
|------|-----|
| `config.json` | Login scrypt hash, SSH passwords, DB credentials |
| `keys/pty_key` | Terminal host key from the source box; `install.sh` generates a new key |
| `backups/`, `logs/`, `tmp/` | Source host data |
| `runtime/` (~205 MB) | Toolchain copy on the source host; not required at runtime |
| `/var/lib/cloud-panel/` | Metrics DB, backups, ops state (tasks, rules, channels, hosts, trash) |

A fresh install starts with an empty ops configuration. Copy `/var/lib/cloud-panel/ops` from another host if you need to migrate state (see [Migrating ops state](#migrating-ops-state)).

**Native addons:** `ssh2` ships optional `.node` binaries (`cpu-features`, `sshcrypto`) tied to CPU/arch. They are stripped from the vendor tarball. SSH still works via the pure JS path on the reference host. Run `cd /opt/cloud-monitor && npm rebuild ssh2` on the target if you want native builds (requires gcc/make).

## Requirements

| | |
|---|---|
| OS | Ubuntu 20.04+ or Debian 11+ (`apt` / `systemctl` / `ufw` actions; RHEL-like systems get read-only metrics) |
| User | root (systemd, nginx, `/opt`) |
| Node | **≥ 22** (`metricsstore` uses `node:sqlite`). Ubuntu/Debian installs via NodeSource when `--node auto` |
| Listen | Default `127.0.0.1:9000`; expose via nginx or `--direct` |
| Disk | ~80 MB for app + vendor; data dir grows with retention |
| Memory | systemd `MemoryHigh=256M`, `MemoryMax=400M` (same as reference) |
| Network | Needed only for Node install or `npm ci` fallback; deps ship in `vendor/` |

## Installation

### A. Remote push from your machine

```bash
cd Server-Cloud-Monitor

./deploy-remote.sh root@TARGET_IP
SM_KEY=~/.ssh/my_key ./deploy-remote.sh root@TARGET_IP

# password login (requires sshpass on your machine; keys are preferred)
SM_PASS='password' ./deploy-remote.sh root@TARGET_IP

# non-root with passwordless sudo
./deploy-remote.sh deploy@TARGET_IP --sudo

./deploy-remote.sh root@TARGET_IP --dry-run
```

The script uploads a tarball to `/tmp/cloud-monitor-panel-<timestamp>/` and runs `install.sh` there. Arguments passed through to `install.sh` are quoted with `printf %q` before remote execution.

### B. Manual copy

```bash
tar -czf cloud-monitor-panel.tar.gz --exclude=.git Server-Cloud-Monitor
scp cloud-monitor-panel.tar.gz root@TARGET_IP:/tmp/
ssh root@TARGET_IP
tar -xzf /tmp/cloud-monitor-panel.tar.gz -C /tmp && cd /tmp/Server-Cloud-Monitor
bash install.sh
```

### C. Second instance on the same host

```bash
bash install.sh --prefix /opt/cm-second --data-dir /var/lib/cm-second \
                --port 9100 --service cm-second --no-nginx --no-systemd
```

## `install.sh` options

| Flag | Default | Meaning |
|------|---------|---------|
| `--prefix DIR` | `/opt/cloud-monitor` | Application directory |
| `--data-dir DIR` | `/var/lib/cloud-panel` | Data directory (`CM_DATA_DIR`), mode 0700 |
| `--port N` | `9000` | Listen port (`CM_PORT`) |
| `--bind ADDR` | `127.0.0.1` | Bind address (`CM_BIND`); `--direct` sets `0.0.0.0` |
| `--base-path /x` | `/panel` | URL prefix; must match clients |
| `--service NAME` | `cloud-monitor` | systemd unit name (change for multiple instances) |
| `--password '...'` | random | First boot only; hash written to `config.json`, temp env file removed |
| `--node auto\|skip` | `auto` | `skip` leaves Node unchanged |
| `--no-nginx` | — | Do not touch nginx |
| `--nginx-standalone PORT` | — | Dedicated server block (e.g. 8091) when :80 is taken |
| `--direct` | — | Bind `0.0.0.0`, skip nginx |
| `--no-systemd` / `--no-start` / `--no-smoke` | — | Skip unit / start / smoke test |
| `--dry-run` | — | Print plan only |

With nginx mode `auto`, the script writes `/etc/nginx/snippets/cloud-monitor-location.conf` only and prints an `include` line for you to add. It does not edit existing site configs. On a blank nginx host, `--nginx-standalone 8091` is usually simpler.

## After install

The script prints the URL and the initial login password.

- Browser: `http://<host>/panel/` (or your chosen port/prefix)
- KMP client: set the server URL at login (`scheme://host`, include port if not 80). No rebuild required.

The panel can perform root-level actions on the host. Read this before exposing it:

1. Default bind is loopback. Prefer nginx + TLS + IP allow lists, or `ssh -L 8080:127.0.0.1:9000 user@host`.
2. `--direct` listens on all interfaces; restrict the port in your cloud firewall.
3. Change the password under panel settings after first login. `/etc/cloud-monitor.env` is removed; only the scrypt hash remains in `config.json` (0600).
4. Eight failed logins lock the account for ten minutes. API rate limit: 1800 requests/minute per IP.
5. Ops file manager and web terminal can modify files and services like the built-in terminal.

```bash
systemctl status cloud-monitor
journalctl -u cloud-monitor -f
curl -s -X POST http://127.0.0.1:9000/panel/api/login -H 'Content-Type: application/json' \
     -d '{"password":"YOUR_PASSWORD"}' | head -c 120
```

## Migrating ops state

To move uptime tasks, alert rules, channels, hosts, or scan history:

```bash
# source host
tar -czf /tmp/ops-state.tar.gz -C /var/lib/cloud-panel ops

# target host (after install.sh)
mkdir -p /var/lib/cloud-panel && tar -xzf /tmp/ops-state.tar.gz -C /var/lib/cloud-panel
systemctl restart cloud-monitor
```

`ops/notify_channels.json` holds channel secrets. `ops/remote_creds.json` holds SSH passwords or key paths. Treat archives like credentials; delete them after restore, or re-enter secrets on the new host.

## Upgrade and rollback

**Upgrade:** run `install.sh` again. The previous `server/` tree is copied to `/opt/cloud-monitor/.backup-<timestamp>/`. `config.json`, `keys/`, `node_modules`, and the data directory are left in place.

**Rollback:**

```bash
rm -rf /opt/cloud-monitor/server
cp -a /opt/cloud-monitor/.backup-<timestamp>/server /opt/cloud-monitor/
systemctl restart cloud-monitor
```

**Uninstall:** `./uninstall.sh` (keeps app and data) · `./uninstall.sh --purge-app --purge-data`

## Troubleshooting

| Symptom | What to check |
|---------|----------------|
| Exit on start, `EADDRINUSE` | Port in use: `ss -ltnp \| grep :9000`, or use `--port` |
| `EACCES` on bind | Privileged port without capability; change port or add `AmbientCapabilities=CAP_NET_BIND_SERVICE` |
| `node:sqlite` unavailable | Node &lt; 22; upgrade and restart (other features work; metrics DB disabled) |
| `ops modules not mounted: ...` | Incomplete ops tree or syntax error: `node --check /opt/cloud-monitor/server/ops/index.js` |
| UI loads but API errors | `basePath` in `config.json` must match the URL prefix |
| WebSocket / blank terminal | nginx missing `$connection_upgrade` map or `Upgrade` headers |
| HTTP 429 | Rate limit (1800/min/IP) |
| Login HTTP 500 | Check `journalctl -u cloud-monitor` for lockout or server error |
| `apt` / service pages fail | Host is not Debian-family or `apt` is missing |

## Building and updating this bundle

Maintainers pull read-only from the reference host in the ServerMaintance repo:

```bash
python tools/build_panel_package.py
# sync deploy/cloud-monitor-panel/ into this repository, then commit and push
```

Pull scope: `server/`, `public/`, `package*.json` (excluding `server/keys`, `page_test.js`), and vendored `node_modules` without arch-specific `.node` files. No `config.json`, `keys/`, backups, or logs.

See `MANIFEST.env.txt` and `MANIFEST.remote-md5.txt` (61 files, md5 match on last sync).

## Verification (reference host)

Isolated install test (separate prefix/port/data dir, production service untouched):

```bash
python tools/test_panel_package.py
```

Last run: 33 checks passed (rc=0). Highlights:

| Area | Result |
|------|--------|
| Integrity | 61 files md5 match; no `config.json`/keys/password material in package |
| `install.sh` | Full tree, ops (13 files), static UI, `node_modules`, generated `keys/pty_key`, data dir; 0 `.node` in vendor |
| Node | `node:sqlite` OK on Node 22.20 |
| Runtime | Log line `Cloud Monitor listening on http://127.0.0.1:9101/panel`; ops mounted |
| API | Login, `/me`, `/ops/*` endpoints returned 200 |
| Static / WS | `index.html` and vendor assets 200; `/panel/ws` 101 (401 on bad password) |
| Isolation | Production `cloud-monitor` unit and `config.json` unchanged after test |
| `deploy-remote.sh` | Remote argv quoting verified (no shell injection on special characters) |

Not exercised on that run: first-time NodeSource install on a bare host, real nginx reload with `--nginx-standalone` on a host where :80 is already `default_server`, and first systemd enable on a machine with no prior unit. Watch install logs and `nginx -t` on first deploy.
