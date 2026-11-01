# Cloud Monitor panel · portable deployment package

Moves the whole panel that runs on `8.153.153.176` — **including the 9 ops modules added by
this project** (uptime probes, alert rules, notification channels, managed hosts, file
manager, security baseline, disk governance, service watchdog, remote collection) — onto
another Ubuntu/Debian server and runs it as-is.

Package root (on this machine): `deploy/cloud-monitor-panel/`

```
deploy/cloud-monitor-panel/
├── install.sh                 # run on the target: preflight → Node 22 → code → systemd → nginx → smoke test
├── uninstall.sh               # stop service / remove units / delete the nginx this script generated (keeps code and data by default)
├── deploy-remote.sh           # push from your machine to the target and run install.sh remotely
├── README.md                  # this file
├── MANIFEST.remote-md5.txt    # per-file md5 of the source machine's code (verified on pull: 61 files, 0 differences)
├── MANIFEST.env.txt           # source machine and Node versions
├── app/                       # the panel itself = what gets deployed to /opt/cloud-monitor
│   ├── server/                #   index.js + 20 modules + ops/ 13 files (including send_mail.py)
│   ├── public/                #   the web shell (what you see when opening /panel/ in a browser)
│   ├── package.json
│   └── package-lock.json
└── vendor/
    ├── node_modules.tar.gz    # offline dependencies, 3.3MB (1226 files, pure JS)
    └── node_modules.README.txt
```

## 1. What is in the package, and what is deliberately left out

**In**: panel code, web static shell, dependencies (offline), install/uninstall scripts,
systemd and nginx generation logic.

**Not in** — all of these are source-machine state or secrets and must not travel with a
package:

| Excluded | Why |
|---|---|
| `config.json` | Contains the login password's scrypt hash, SSH passwords, and PostgreSQL/MySQL/Redis/Mongo passwords |
| `keys/pty_key` | The source machine's terminal host private key; `install.sh` generates one on the target with `ssh-keygen` |
| `backups/`, `logs/`, `tmp/` | Source-machine backups and audit logs |
| `runtime/` (205MB) | A toolchain copy on the source machine; the panel does not need it to run |
| `/var/lib/cloud-panel/` | Data directory: metrics DB, backups, and the ops state (probe tasks, alert rules, channels, host inventory, trash) |

So a new machine ends up as a **clean panel**: probe tasks, alert rules, channels and managed
hosts all have to be configured again. To bring that configuration across, copy the data
directory by hand (see §6 Migrating configuration).

**No native modules in the dependencies**: `ssh2` ships two optional `.node` acceleration
addons (`cpu-features`, `sshcrypto`) that are machine/architecture-bound, and they are
stripped when packaging. Measured on the source machine: without them `ssh2` uses its pure
JS path and real SSH connections and command execution still work (remote collection is
unaffected). Want the native speed? Run `cd /opt/cloud-monitor && npm rebuild ssh2` on the
target (needs gcc/make). Optional.

## 2. Prerequisites

| Item | Requirement |
|---|---|
| OS | Ubuntu 20.04+ / Debian 11+ (the panel's action features use `apt`/`systemctl`/`ufw`; on CentOS-family you only get metrics) |
| Privilege | root (writes systemd, nginx, `/opt`) |
| Node | **>= 22** (`metricsstore` uses the built-in `node:sqlite`). The script installs it via NodeSource on Ubuntu/Debian when needed |
| Port | Default `127.0.0.1:9000`, loopback only; for external access use nginx or `--direct` |
| Disk | Roughly 80MB of code plus dependencies; the data directory grows per retention policy |
| Memory | `MemoryHigh=256M / MemoryMax=400M` on the systemd side, same as the source machine |
| Internet | Only needed for "install Node automatically" and the `npm ci` fallback; dependencies ship offline |

## 3. Deployment

### Option W: Windows Server / Windows 10+ (this machine or a Windows box in the rack)

In an **elevated PowerShell** (paths inside the repository):

```powershell
cd deploy\cloud-monitor-panel
.\install.ps1                          # defaults to C:\CloudMonitor, 127.0.0.1:9000, registers a Windows service
.\install.ps1 -Direct                  # binds 0.0.0.0; you must restrict the firewall yourself
.\install.ps1 -AllowNonAdmin -NoService   # no administrator rights: place code only, register no service
.\install-as-service.cmd                  # double-click to trigger UAC and register the CloudMonitor service as administrator
.\register-logon-task.ps1 -Prefix D:\CloudMonitor   # optional: start automatically at logon (needs Task Scheduler rights)
```

Without administrator rights you can use a Start Folder shortcut (`install.ps1` generates
`start-cloud-monitor.cmd`, which you can copy to
`%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup`).

Afterwards open `http://127.0.0.1:9000/panel/` in a browser; in the KMP client just point the
server address at the same entry.

The Windows build shares the same web shell and API routes as the Linux one; local collection
goes through `WinLocalAgent` + PowerShell (`agent mode: local (windows)`). The software
package page uses **winget/choco**; the system services page uses **sc.exe**; the firewall
uses **netsh advfirewall**. Some Linux-only capabilities (ufw/fail2ban/systemd journal, the
OpenCode relay collector) degrade or are unavailable on Windows; the remaining
monitoring/ops pages match the Ubuntu panel.

### Option A: one push from your machine (recommended)

```bash
cd deploy/cloud-monitor-panel

# key-based login (recommended; use SM_KEY for a non-default private key path)
./deploy-remote.sh root@<new-server-ip>
SM_KEY=~/.ssh/my_key ./deploy-remote.sh root@<new-server-ip>

# password login only (needs sshpass locally; most servers disable password auth, prefer a key)
SM_PASS='<new machine password>' ./deploy-remote.sh root@<new-server-ip>

# non-root + sudo (sudoers must be NOPASSWD)
./deploy-remote.sh deploy@<new-server-ip> --sudo

# show what it would do, change nothing
./deploy-remote.sh root@<new-server-ip> --dry-run
```

It builds a tar, pushes it to `/tmp/cloud-monitor-panel-<timestamp>/`, then runs `install.sh`
remotely. Every argument forwarded to `install.sh` is escaped with `printf %q` before being
joined into the remote command string, so the shell cannot reinterpret it a second time.

### Option B: copy it over by hand

```bash
# 1) build a single-file archive locally
tar -czf cloud-monitor-panel.tar.gz -C deploy cloud-monitor-panel

# 2) transfer and unpack on the target
scp cloud-monitor-panel.tar.gz root@<new-server-ip>:/tmp/
ssh root@<new-server-ip>
tar -xzf /tmp/cloud-monitor-panel.tar.gz -C /tmp && cd /tmp/cloud-monitor-panel

# 3) install
bash install.sh
```

### Option C: a second instance on the same machine (this is what this project's own testing uses)

```bash
bash install.sh --prefix /opt/cm-second --data-dir /var/lib/cm-second \
                --port 9100 --service cm-second --no-nginx --no-systemd
```

## 4. `install.sh` parameters

| Flag | Default | Meaning |
|---|---|---|
| `--prefix DIR` | `/opt/cloud-monitor` | Code directory |
| `--data-dir DIR` | `/var/lib/cloud-panel` | Data directory (`CM_DATA_DIR`), mode 0700 |
| `--port N` | `9000` | Listen port (`CM_PORT`) |
| `--bind ADDR` | `127.0.0.1` | Listen address (`CM_BIND`); `--direct` changes it to `0.0.0.0` |
| `--base-path /x` | `/panel` | URL prefix; must match the client |
| `--service NAME` | `cloud-monitor` | systemd unit name (must change it for multiple instances on one host) |
| `--password '...'` | randomly generated | Used only for the first start; once the hash is in `config.json` the temporary env file is deleted |
| `--node auto\|skip` | `auto` | `skip` = leave Node alone (you installed it yourself) |
| `--no-nginx` | — | Do not touch nginx at all |
| `--nginx-standalone PORT` | — | Generate a standalone server block (default 80), avoiding a clash with an existing `:80 default_server` |
| `--direct` | — | Bind `0.0.0.0` and install no nginx: exposed directly |
| `--no-systemd` / `--no-start` / `--no-smoke` | — | No unit / do not start / no self-check |
| `--dry-run` | — | Print the plan only |

The default nginx mode (`auto`) is **safe by design**: it writes one snippet,
`/etc/nginx/snippets/cloud-monitor-location.conf`, **does not modify your site file**, and
prints the `include` line for you to add yourself. On a clean nginx, `--nginx-standalone 8091`
is the least work.

## 5. After installation

The end of the script prints the entry URL and (on first run) the login password. Two ways to
log in:

- Browser: `http://<entry>/panel/`
- This project's KMP client: point the server address on the login screen at the same entry
  (`scheme://host`, include the port when it is not 80). **No client rebuild needed** — the
  address is runtime configuration.

Security notes (the panel is root capability on the server — read all of them):

1. It binds `127.0.0.1` by default. For remote access prefer nginx + HTTPS + source
  restriction, or temporarily `ssh -L 8080:127.0.0.1:9000 <host>`.
2. `--direct` exposes the panel publicly; use your cloud security group to limit the port to
  office IPs.
3. **Change the password immediately in "Panel settings" after the first login** (the password
  file `/etc/cloud-monitor.env` is deleted by the script, and `config.json` keeps only the
  scrypt hash, mode 0600).
4. 8 consecutive wrong logins lock the account for 10 minutes; the API rate limit is
  1800 requests/minute/IP.
5. The ops modules (file manager, terminal) can modify files and perform service operations —
  their capability boundary is the same as the panel's own web terminal.

Operations:

```bash
systemctl status cloud-monitor
journalctl -u cloud-monitor -f
curl -s -X POST http://127.0.0.1:9000/panel/api/login -H 'Content-Type: application/json' \
     -d '{"password":"your password"}' | head -c 120        # get a token
```

## 6. Migrating configuration (optional)

To bring probe tasks / alert rules / channels / managed hosts / baseline history from the
source machine to the new one:

```bash
# source machine
tar -czf /tmp/ops-state.tar.gz -C /var/lib/cloud-panel ops
# new machine (after install.sh has run)
mkdir -p /var/lib/cloud-panel && tar -xzf /tmp/ops-state.tar.gz -C /var/lib/cloud-panel
systemctl restart cloud-monitor
```

Note: `ops/notify_channels.json` contains channel secrets (masking happens only at the API
response layer), and `ops/remote_creds.json` contains SSH passwords and private key paths.
Treat them as credentials when moving them between machines and delete the intermediate tar
afterwards. The safer option is to re-enter them on the new machine.

## 7. Upgrade and rollback

- **Upgrade**: run `install.sh` again. The old `server/` is first backed up to
  `/opt/cloud-monitor/.backup-<timestamp>/`; `config.json`, `keys/`, `node_modules` and the
  data directory are untouched.
- **Rollback**:
  ```bash
  rm -rf /opt/cloud-monitor/server
  cp -a /opt/cloud-monitor/.backup-<timestamp>/server /opt/cloud-monitor/
  systemctl restart cloud-monitor
  ```
- **Uninstall**: `./uninstall.sh` (keeps code and data) · `./uninstall.sh --purge-app --purge-data`

## 8. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Exits immediately, log shows `EADDRINUSE` | Port taken: `ss -ltnp \| grep :9000`, or choose another `--port` |
| `cannot listen ... EACCES` | Binding a port below 1024 without privilege; change the port or add `AmbientCapabilities=CAP_NET_BIND_SERVICE` to the unit |
| Log shows `node:sqlite unavailable` | Node < 22; install 22 and restart the service (other features still work, only historical metrics are not stored) |
| `ops modules not mounted: ...` | ops files were not fully unpacked or have a syntax error; run `node --check /opt/cloud-monitor/server/ops/index.js` |
| Pages open but the top bar is empty / a whole group errors | Client address or `basePath` mismatch (the `basePath` in `config.json` must equal the URL prefix) |
| WebSocket will not connect, terminal is black | nginx is missing the `$connection_upgrade` map, or the proxy does not forward the `Upgrade` header |
| 429 too many requests | The 1800/minute/IP cap — normal protection |
| Login returns 500 | The panel's login handler answers 500 for failures too (upstream behaviour); check `journalctl -u cloud-monitor` first to see whether the account is locked |
| apt / services / package pages error | The target is not Debian-family, or `apt` is not installed; those features are distribution-specific |

## 9. How this package was produced

`python tools/build_panel_package.py` (repository root) will: connect to the source machine →
tar `server/`, `public/`, `package*.json` read-only (excluding `server/keys` and
`page_test.js`) plus `node_modules` (with the two native addons stripped) → assemble this
directory locally → **compare md5 file by file** → scan the package for a smuggled-in
`config.json` / private key / password hash, and fail if any is found.

Source machine details are in `MANIFEST.env.txt`; the code fingerprint is in
`MANIFEST.remote-md5.txt`.

## 10. How far this package has been verified

Self-test command (run on the source machine with an **independent prefix/port/data
directory**, so the production service is untouched and everything is cleaned up afterwards):

```bash
python tools/test_panel_package.py
```

Latest run — all 33 checks behaved as expected (rc=0):

| Stage | Result |
|---|---|
| Package integrity | 61 source files **md5-identical to the source machine, file by file**; a package scan confirms no `config.json` / private key / password hash inside |
| install.sh | rc=0; code + 13 ops files + static shell + `node_modules` + a freshly generated `keys/pty_key` + data directory all present; **0 `.node` files** in the dependencies |
| Node probe | `node:sqlite available (historical metrics stored normally)` (Node 22.20) |
| Startup | The new instance logs `Cloud Monitor listening on http://127.0.0.1:9101/panel`; the process cwd is the test prefix; no `ops modules not mounted` |
| Endpoints | Login yields a token; `/me`, `/ops/overview` (nine-module aggregate), `/ops/sec/checks`, `/ops/disk/overview`, `/ops/watchdog`, `/ops/remote`, `/ops/files/list`, `/ops/uptime/tasks`, `/ops/alerts/rules`, `/ops/hosts` all 200 |
| Static and live | `public/index.html` 200; `/panel/vendor/{chart.js,xterm.js,xterm.css,addon-fit.js}` served from the unpacked `node_modules`; the `/panel/ws` handshake returns **101** (a wrong password returns 401) |
| Isolation | `systemctl is-active cloud-monitor` and the production `config.json` md5 are **unchanged** before and after; after cleanup the port is released and the test directory removed |
| deploy-remote.sh | Verified with a local `ssh` stub that the three remote command strings assemble correctly; password arguments containing `;`, `&&` and spaces are sealed into a single argv by `printf %q`, so injection does not take effect |

**Not covered**: NodeSource's automatic Node install on the target (the source machine already
satisfied it, so the skip branch ran), a real nginx `--nginx-standalone` reload (the source
machine's `:80` already has a default_server and the self-test deliberately does not touch
it), and the first start of the systemd unit on a **brand-new** machine. Watch those three on
a first deployment elsewhere — the log and `nginx -t` will tell you.
