# Server Cloud Monitor

This repository ships two related pieces of the **Cloud Monitor** stack:

1. **Panel server** (repository root) — Node/Express application that runs on Linux hosts under `/opt/cloud-monitor`, exposes the HTTP API and browser UI at `/panel/`, and includes nine ops extensions (uptime checks, alert rules, notification channels, managed hosts, file browser, security baseline scans, disk cleanup, service watchdog, remote metrics).
2. **Native client** (`client/`) — Kotlin Multiplatform + Compose Multiplatform app for **Android** and **desktop** (Windows, Linux, macOS). It talks to the same panel API as the web UI; no WebView shell.

Default reference deployment: Ubuntu, panel behind nginx at `http://<host>/panel`, process bound to `127.0.0.1:9000`.

Repository: <https://github.com/Dingji/Server-Cloud-Monitor>

---

## Repository layout

```
Server-Cloud-Monitor/
├── install.sh, uninstall.sh, deploy-remote.sh   # server install (run from repo root)
├── app/, vendor/                                 # panel code + offline node_modules
├── MANIFEST.*                                    # last sync fingerprint vs reference host
├── client/                                       # KMP client (Gradle project root)
│   ├── composeApp/                               # application sources (commonMain + android + desktop)
│   ├── gradle/, gradlew, gradlew.bat
│   ├── settings.gradle.kts, gradle.properties
│   └── build.ps1                                 # Windows helper (JDK / Android SDK paths)
└── README.md                                     # this file
```

| Path | Role |
|------|------|
| `app/server/` | Express app, collectors, `ops/` modules |
| `app/public/` | Static web UI (optional; client does not use it) |
| `client/composeApp/src/commonMain/` | Shared UI, API client, navigation |
| `client/composeApp/src/androidMain/` | Android entry, platform hooks |
| `client/composeApp/src/desktopMain/` | Desktop entry, window chrome, tray |

---

## Panel server

### What you get

- **Included:** application code, static UI, vendored `node_modules`, install/uninstall scripts, systemd and nginx helpers.
- **Excluded** (per-host secrets or state — never copied from a live box):

| Item | Reason |
|------|--------|
| `config.json` | Login scrypt hash, SSH passwords, DB credentials |
| `keys/pty_key` | Terminal host key; `install.sh` runs `ssh-keygen` on the target |
| `backups/`, `logs/`, `tmp/` | Host-specific data |
| `runtime/` | Optional toolchain copy on some hosts; not required to run |
| `/var/lib/cloud-panel/` | Metrics DB, backups, ops state (tasks, rules, channels, hosts, trash) |

A new install has empty ops configuration unless you restore `/var/lib/cloud-panel/ops` from another host (see [Migrating ops state](#migrating-ops-state)).

`ssh2` optional native `.node` addons are stripped from the vendor tarball; SSH works via the JS implementation. Run `npm rebuild ssh2` on the target if you want native builds.

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
| `--dry-run` | — | Print plan only |

nginx `auto` mode writes `/etc/nginx/snippets/cloud-monitor-location.conf` and prints an `include` line; it does not edit existing vhosts. On an empty nginx host, `--nginx-standalone 8091` is usually simpler.

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

### Refreshing the server bundle in git

From a machine with SSH read access to the reference host, in the separate **ServerMaintance** dev tree:

```bash
python tools/build_panel_package.py
# sync deploy/cloud-monitor-panel/ into this repo root (app/, vendor/, manifests), then commit
```

See `MANIFEST.env.txt` and `MANIFEST.remote-md5.txt`.

---

## Native client (`client/`)

### Purpose

The client reimplements the panel’s feature set with **Compose Multiplatform** widgets: same REST and WebSocket contracts as the browser UI (`/panel/api/...`, `/panel/ws`, `/panel/term`). Server URL is configured at runtime; you do not rebuild the app to point at another host.

**Application ID:** `com.servermaintance.panel`  
**Version:** `1.0.0` (`AppInfo.VERSION` may show a patch suffix in the UI)

### Feature coverage (navigation)

| Section | Pages |
|---------|--------|
| Monitoring | Dashboard, health alerts, SLO & usage, uptime checks, alert rules, notification channels, OpenCode relay (shown only when the collector is detected) |
| Server | Processes, disks, memory, network, security, services, packages, file manager, managed hosts, security baseline, disk governance, service watchdog, remote host metrics |
| Applications | Databases, Docker, web/nginx, cron, backups, logs, audit |
| Notes (NoteBox) | Notes, todos, import/export, mail notifications, NoteBox settings, user admin |
| Tools | Web terminal |
| System | Panel settings |

NoteBox uses a **separate** HTTP service (default port **13345**), not the panel under `/panel`. This repository does **not** include the NoteBox server; deploy it on the host if you use the Notes section. NoteBox login is email/password with session cookies, independent of the panel access password.

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

NoteBox keys use the `notebox_` prefix in the same stores.

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
┌─────────────────┐     HTTPS or SSH tunnel      ┌──────────────────────────┐
│  KMP client     │ ───────────────────────────► │  nginx :443 / :80        │
│  (Android/      │      /panel/api, /panel/ws   │  proxy → 127.0.0.1:9000  │
│   desktop)      │                              │  cloud-monitor.service   │
└─────────────────┘                              └──────────────────────────┘
         │ optional :13345
         ▼
   NoteBox server (not in this repo)
```

Typical order: install the panel on the server (`install.sh`) → configure nginx/TLS → install or build the client → point the client at `https://your-host/panel` → change the default password.

---

## Verification notes

Isolated panel package tests (separate prefix/port, production untouched) are run from the ServerMaintance tree: `python tools/test_panel_package.py`. Last recorded run: 61 application files md5-matched the reference host; install smoke covered login, ops endpoints, static assets, and WebSocket handshake.

Items not fully automated on every run: first-time NodeSource install on a blank VM, nginx `--nginx-standalone` reload on a host that already owns `:80`, and first systemd enable on a machine with no prior unit.

---

## License

See `LICENSE` in the repository root.
