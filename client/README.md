# Cloud Monitor — server monitoring panel, KMP client

A **Kotlin Multiplatform + Compose Multiplatform** replica of the **Cloud Monitor**
panel running on a public Ubuntu server (`/opt/cloud-monitor`, Node/Express, roughly
19.6k lines). Everything is rendered with **native controls** — no WebView wrapper. The
client talks to the same panel API, and the server address is configurable.

- Target platforms: **Android + Desktop (JVM)**
- Default server: `http://8.153.153.176/panel`
- Current status: see [`docs/status/STATUS.md`](docs/status/STATUS.md)

---

## What this is

The original panel is a web console running on the server itself
(`127.0.0.1:9000`, reverse-proxied by nginx under `/panel/`). This project implements
**every feature of all 20 pages** as a native client, plus 9 additional ops pages:

| Group | Pages (20 + 6 NoteBox + 9 ops) |
|---|---|
| Monitoring | Dashboard · Health alerts · SLO & usage · **Uptime probes · Alert rules · Notification channels** · OpenCode relay (optional; shown only when the collector answers the probe) |
| Server | Process management · Disk management · Memory management · Network management · Security management · System services · Package management · **File management · Managed hosts · Security baseline · Disk governance · Service watchdog · Remote host metrics** |
| Apps | Databases · Docker containers · Websites / Nginx · Scheduled tasks · Backup center · Log center · Audit log |
| **Notes** | **Notes · Todo list · Import/Export · Email notifications · NoteBox settings · User management** (a separate server feature: its own port `13345` and its own multi-user login) |
| Tools | Web terminal |
| System | Panel settings |

The per-page feature inventory with implementation locations is in
[`docs/guide/features.md`](docs/guide/features.md); the baseline feature matrix is in
[`docs/api/feature-matrix.md`](docs/api/feature-matrix.md).

---

## 2026-10-30 full feature audit and gap closure (server + web + KMP client)

All three tiers were reconciled feature by feature (mechanically: `tests/parity.py` with
228 checks plus `tests/endpoint_inventory.py`); every gap found was closed and deployed to
production. Three mirror directories were added so the server and web front-end code is
versioned here too: `panel-server/` (the panel's Node server), `panel-web/` (the browser
front end: shell + pages), `relay-server/` (the OpenCode relay collector/rewriter).
**Deployment is sourced from these three directories**; `upstream/` remains a read-only
reference mirror of the production host and is not committed.

### Web front end (`panel-web/`)

- **File manager** (new page): browse, preview by type (editable text, images, audio/video,
  PDF, archive contents with per-entry download), upload/download, trash, permissions and
  ownership, pack and extract
- **8 new ops pages under one group**: uptime probes · alert rules · notification channels ·
  managed hosts · security baseline · disk governance · service watchdog · remote hosts —
  same endpoints and same fields as the KMP client
- **A new "busy / idle hours" tab on the OpenCode relay page**: phase banner (idle rewrite in
  effect / busy), two-way editing (busy maps deepseek→mimo; idle maps every model→
  deepseek-v4.1-flash, an empty window means the complement of the busy window), trial
  evaluation at any instant, temporary pause and resume; saving goes through the panel audit
  log and backs up the policy file automatically
- **Client usage**: the relay attributes token usage per client by
  source IP + user agent + credential hash (the clients tab)
- Mobile: every page follows the panel's responsive breakpoints (1150/860/480px) — single
  column on narrow screens, tables scroll horizontally

### Server (`ops-server/`, `relay-server/`, `panel-server/`)

- `ops/files.js` + `archive_tool.py`: preview by type (kind/mime), inline `/raw` stream,
  archive listing / entry preview / streaming entry download (zip-slip immune), tar
  extraction repair, python fallback for zip packing
- `relay-server/usagetap.py`: idle-hours rewrite policy (complement semantics), client
  identity columns (an `usage_events` migration), the `X-Relay-Rewritten-Policy` header
- `relay-server/collector.py`: the `/api/clients-usage` aggregation, busy-hours policy API
  upgraded to v2 (validates and preserves the `idle` sub-block), idle/busy phase decision
- `panel-server/relay.js`: fixed a Content-Length bug where relay.action dropped its payload
  (it affected every parameterised relay admin operation); `index.js`: `RELAY_GET` extended
  with clients / clients-usage and friends

### KMP client (`composeApp/`)

- Type-aware preview in the file manager page (inline image decode / archive browser /
  information-card fallback)
- A client usage table added to the relay's "clients and sessions" tab; an idle-hours editor
  added to the "busy / idle" tab
- Fixed HTTP 404 being misread as "module not deployed" (`ApiException.fromServer`)
- All 18 locales completed; `compileKotlinDesktop` passes; parity 228 checks, 0 missing

### Verification

- `node tools/ops_test.js`: 130 PASS (local contract tests)
- `python tools/relay_policy_test.py`: 20 PASS (busy/idle policy unit tests)
- `python tools/deploy_files_preview.py --smoke-only`: 18/18 (production smoke)
- Page-by-page browser sweep of 30 pages, interaction tests on every write path of the new
  pages, and a spot check at 390px width

---

## Building and running

Full steps, the toolchain version table and troubleshooting are in
**[`docs/guide/build.md`](docs/guide/build.md)**. Common commands:

```powershell
# quick compile check (run this first after editing)
.\build.ps1 :composeApp:compileKotlinDesktop

# Android debug package
.\build.ps1 :composeApp:assembleDebug

# desktop installer for the current OS
.\build.ps1 :composeApp:packageDistributionForCurrentOS

# run the desktop app directly (no installer)
.\build.ps1 :composeApp:run

# list every task
.\build.ps1 tasks --all

# iOS: type-check at klib level on this machine (Windows) — no linking, no packaging,
# but it catches expect/actual mismatches and wrong UIKit calls early
# (see docs/guide/build.md §3.1)
pwsh tests\ios-gate.ps1
```

**Artifact paths** (produced on a successful build):

| Artifact | Path |
|---|---|
| Android APK | `composeApp\build\outputs\apk\debug\composeApp-debug.apk` |
| Windows installer | `composeApp\build\compose\binaries\main\msi\CloudMonitor-1.0.0.msi` |
| Linux installer | `composeApp\build\compose\binaries\main\deb\cloudmonitor_1.0.0-1_amd64.deb` |
| macOS installer | `composeApp\build\compose\binaries\main\dmg\CloudMonitor-1.0.0.dmg` |
| iOS framework / app | **macOS only**: open `iosApp\iosApp.xcodeproj` in Xcode and run (`import ComposeApp`) |

> `build.ps1` sets `JAVA_HOME` (the JBR 25 bundled with Android Studio) and `ANDROID_HOME`
> for you; when `.toolchain/` is absent it falls back to the `gradlew.bat` in the repository.

---

## Configuring the server address

The panel address **must include the application context path**, in the form
`http://<host>[:port]/panel`: REST appends `/api`, WebSocket appends `/ws` and `/term`.

**Two places to change it:**

1. **The login screen** — the "server connection" area: enter the address → "test
   connection" (calls `GET /panel/api/health`) → on success enter the access password.
   Changing the address discards the old token, so you must log in again.
2. **Panel settings** — change and save it while logged in (settings → server connection).

**When it is wrong:**

| Symptom | Cause / fix |
|---|---|
| "Cannot reach the server: network error / connection refused" | Wrong address, port not open, or the `/panel` suffix is missing. Add the context path: `http://8.153.153.176/panel`, not `http://8.153.153.176` |
| "Service unreachable (HTTP 404/502)" | Wrong path (it should be `/panel`), or nginx is not running on the server |
| "Session expired, please log in again" | The token is no longer valid — just log in again. Editing the address lands here too |
| Stuck showing "reconnecting…" | Login works but the WebSocket is blocked; check whether HTTPS/a proxy rewrote `ws://` |

Configuration is persisted locally: `SharedPreferences` (`cm_panel`) on Android,
`java.util.prefs` on desktop, under the keys `server_url` / `server_token` /
`remember_password` / `password`.

---

## Login

- Uses the **panel access password** (`POST /panel/api/login`) — **not** the SSH password.
- Tick "remember password" to log in automatically next time.
- A successful login returns a 64-character token with a default lifetime of **168 hours**;
  any request answering 401 clears the token and returns to the login screen.

> On this server **SSH has password authentication disabled (publickey only)**, which has
> nothing to do with the panel password. A working key on this machine:
> `ssh -i "$env:USERPROFILE\.ssh\oc_relay_ed25519" root@8.153.153.176`

---

## Notes / todos (NoteBox)

The server also runs a separate **NoteBox** service (notes plus todos, Flask + SQLite,
`/opt/notebox`); the client's six pages under the "Notes" sidebar group talk to it.
**It is a second service with a second login, independent of the panel:**

| | Panel (20 pages) | NoteBox (Notes group) |
|---|---|---|
| Address | `http://<host>/panel` | `http://<host>:13345` (default `http://8.153.153.176:13345`) |
| Auth | Bearer token (`POST /panel/api/login`) | **session cookie** (`POST /api/login {email,password}`) |
| Account | Access password only | **email + password** (multi-user; `admin` is the administrator, with email registration and verification/activation) |
| Entry point | Login screen | Any page in the "Notes" sidebar group — when logged out it shows a NoteBox login card (login ⇄ register  registration-done, three states) |

- Opening any page in the "Notes" group for the first time asks for a NoteBox login; ticking
  "remember password" logs you in automatically afterwards.
- **Multi-user**: notes can be shared with other registered users at three permission levels
  (read-only / can edit / admin), and a public link (`/s/<code>`) can be enabled. Todos are
  isolated per user. Administrators (`role=admin`) additionally see the "User management" and
  "Email notifications" pages; those are hidden from the sidebar for everyone else.
- Address, email, password, session and role are all persisted locally (Android
  `SharedPreferences`, desktop `java.util.prefs`, keys prefixed `notebox_`). Changing the
  address requires logging in again.
- The note editor has a toolbar (headings/bold/lists/tables/code blocks/images/video…) and
  media goes through the upload endpoint and is inserted as Markdown.
- Features and endpoints are listed in the **W section** of
  [`docs/guide/features.md`](docs/guide/features.md).

## Ops extensions (probes · alert rules · channels · file manager · baseline · disk · watchdog · remote)

Capabilities comparable to commercial ops products (1Panel / BaoTa / PRTG / Datadog),
implemented as `server/ops/` modules on the panel:

- **Uptime probes**: HTTP(S) status code plus response keyword checks, TLS certificate days
  remaining, TCP port, ICMP, DNS. A target counts as unavailable only after consecutive
  failures (debouncing); availability (SLA) is aggregated per day and kept for up to 120 days.
- **Alert rule engine**: 14 composable metrics + threshold + duration + severity + per-rule
  cooldown + recovery notification, replacing the four hard-coded thresholds the panel used
  to have. A firing rule both lands in the panel bell (pushed over WebSocket) and is sent to
  notification channels.
- **Notification channels**: webhook (HMAC signed), WeCom, DingTalk (signed), Feishu (signed
  cards), Telegram (optional proxy address), SMTP mail; failures retry three times with
  30s/2min/5min backoff and every attempt is logged.
- **Silence and maintenance windows**: scoped by rule or by metric, one-off time ranges or
  daily recurring windows (crossing midnight supported), so backups and releases stop
  flooding the feed.
- **Credential hygiene**: channel passwords, secrets and bot webhook keys are masked in every
  API response, and posting the placeholder back does not overwrite a stored credential.
- **File manager**: tree and breadcrumbs, common directories, online editing (up to 2MB),
  upload/download, create/rename/move/copy, chmod/chown, tar.gz and zip pack/extract, search
  by name, du ranking; deletion goes to a **trash with 30-day restore** first, and across
  filesystems (`/tmp` is tmpfs) it automatically switches to copy-then-delete.
- **Security baseline**: 23 read-only checks (SSH policy, fail2ban, ufw, database ports
  exposed, UID 0, empty passwords, authorized_keys permissions, world-writable /etc, unusual
  SUID, /tmp noexec, certificate expiry, clock sync, pending updates…), scored 0–100 with an
  A–E grade by weight. Remediation is a **whitelisted server action**: an SSH fix writes a
  drop-in, validates with `sshd -t` and rolls back if it fails; a ufw fix allows the current
  SSH port before enabling the firewall.
- **Disk governance**: capacity and inodes (ignoring small volumes such as `/boot/efi`),
  journal/APT/`/var/log`/trash usage, big files and directory ranking, rotated-log detection
  with a reclaimable estimate, five cleanup jobs (each measuring the free-space delta to
  report the **space actually reclaimed**), an optional daily maintenance schedule, and log
  truncation restricted to plain files inside whitelisted directories.
- **Service watchdog with self-healing**: three liveness styles (systemd unit / process
  keyword / TCP port); when unavailable it restarts the service up to a bounded number of
  times per window, and past that limit it stops the restart loop and escalates to a manual
  alert. Leaving `startCmd` empty means alert-only, no self-healing.
- **Remote host metrics**: a fixed set of read-only commands against managed hosts (load,
  memory, disks, top processes, listening ports, system info, key units). SSH credentials
  accept a private key path on the server or a password (masked in responses; an empty
  submission does not overwrite).

| Item | Location |
|---|---|
| Server modules | `ops-server/` → deployed as `/opt/cloud-monitor/server/ops/` |
| Endpoints and metric table | [`docs/api/ops.md`](docs/api/ops.md) |
| Client state layer | `…/core/Ops.kt`, `Files.kt`, `Hosts.kt`, `SecOps.kt`, `DiskOps.kt`, `GuardOps.kt` (reuses the panel Bearer token) |
| Client pages | nine pages under `…/pages/ops/`: uptime probes / alert rules / notification channels / file management / managed hosts / security baseline / disk governance / service watchdog / remote metrics |
| File manager | `ops-server/files.js`: tree, upload/download, online editing, permissions and ownership, pack/extract, search, trash with 30-day restore |
| Managed hosts | `ops-server/hosts.js`: purpose/environment/owner/tags inventory + SSH port reachability and banner probe (no quotas, no billing) |
| Baseline and governance | `ops-server/secops.js` (23 read-only checks + 5 whitelisted fixes), `ops-server/disk.js` (capacity / big files / logs / cleaners / schedules) |
| Watchdog and remote | `ops-server/watchdog.js` (three probes + bounded self-healing), `ops-server/remote.js` (fixed read-only command bundle over ssh2), `ops-server/exec.js` (shared executor with an injectable stub) |
| Contract tests | `node tools/ops_test.js` (181 checks: fake fetch + injected executor, covering parsing, guards, remediation rollback, cleanup reporting) |
| Deploy and self-check | `python tools/deploy_ops.py` / `python tools/ops_smoke.py [live|files|newops|removed]` |
| Whole-machine portability | `deploy/cloud-monitor-panel/` (offline installer for the panel plus ops, including `install.sh`); rebuild with `python tools/build_panel_package.py`, self-test with `python tools/test_panel_package.py` |

```powershell
python tools/ops_smoke.py live   # create a rule that must fire, proving evaluation + the panel bell
```

---

## Deploying the whole panel to another server

`deploy/cloud-monitor-panel/` is a **self-contained offline installer**: panel server code +
web shell + the 9 ops modules + `node_modules` (machine-specific native addons stripped, so
ssh2 runs on its pure-JS path) + `install.sh` / `uninstall.sh` / `deploy-remote.sh` + the
systemd and nginx generation logic. Three commands on the target:

```powershell
# push from this machine (key-based login)
cd deploy/cloud-monitor-panel
./deploy-remote.sh root@<new-server-ip>
```

Deliberately **not in the package**: `config.json` (password hashes, SSH and database
passwords), `keys/pty_key`, backups and audit logs, `runtime/`, and the data directory
`/var/lib/cloud-panel`. A new machine therefore starts as a clean panel that generates its
own password hash on first start. Deployment steps, the parameter table, config migration,
upgrade/rollback and troubleshooting are in
[`deploy/cloud-monitor-panel/README.md`](deploy/cloud-monitor-panel/README.md).

## Repository structure

```
ServerMaintance/
├── build.ps1                 # build wrapper (sets JAVA_HOME/ANDROID_HOME)
├── gradlew.bat               # Gradle wrapper (fallback when .toolchain is absent)
├── composeApp/src/
│   ├── commonMain/kotlin/com/servermaintance/panel/
│   │   ├── Platform.kt       # expect: KeyValueStore / FileExporter / Clip / openUrl / pickTextFiles / pickBinaryFiles
│   │   ├── App.kt            # root composable, dispatches on session state
│   │   ├── core/             # config, REST, WS, global state, Fmt, Actions, NoteBox (notes/todos)
│   │   ├── ui/               # shared controls (Icons/Widgets/Charts/Dialogs/Refresh)
│   │   ├── nav/              # registry and routing for 26 pages (including the 6 Notes pages)
│   │   ├── screens/          # shell: login, Root, Toast/Modal hosts
│   │   └── pages/            # pages (with relay/, term/, db/ sub-modules and the NoteXxx note pages)
│   ├── androidMain/          # Android actuals (Platform.android.kt, MainActivity)
│   ├── desktopMain/          # Desktop actuals (Platform.desktop.kt, Main.kt)
│   └── iosMain/              # iOS actuals (Platform.ios.kt, Api.ios.kt=Darwin, MainViewController.kt)
├── iosApp/                   # iOS shell (Xcode + Swift; can only be packaged on macOS)
│   └── iosApp.xcodeproj      # the "Compile Kotlin Framework" phase calls embedAndSignAppleFrameworkForXcode
├── panel-server/             # panel Node server (mirrors production)
├── panel-web/                # panel browser front end: shell + pages (mirrors production)
├── relay-server/             # OpenCode relay collector / rewriter (mirrors /opt/relay)
├── ops-server/               # ops module sources (nine modules) → deployed as /opt/cloud-monitor/server/ops/
├── deploy/cloud-monitor-panel/  # offline installer (app + vendor + install/uninstall/deploy-remote)
├── upstream/                 # read-only reference mirror of the production host (not committed)
├── api-dump/                 # read-only real API response samples (the authoritative field reference; not committed)
├── docs/                     # BRIEF / UI-CONTRACT / api / guide / status / testing
└── tools/                    # remote and research helpers (build_panel_package.py, test_panel_package.py)
```

---

## Hard constraints

1. **No WebView wrapper** — charts are **hand-drawn on Canvas** in `ui/Charts.kt` and in
   page-specific visualisations, and the terminal is a custom ANSI renderer
   (`pages/term/AnsiScreen.kt` + `pages/term/TerminalView.kt`).
2. **The server address is configurable**, default `http://8.153.153.176/panel`.
3. **Every feature must be present — not one missing.** The reconciliation baseline is
   `docs/api/feature-matrix.md`; the reconciliation records are in `docs/testing/`.

---

## Related documents

| Document | Contents |
|---|---|
| [`docs/guide/build.md`](docs/guide/build.md) | Build steps for all three platforms (iOS on macOS only), toolchain versions, troubleshooting |
| [`docs/guide/architecture.md`](docs/guide/architecture.md) | Layered architecture, write boundaries, native constraints |
| [`docs/guide/features.md`](docs/guide/features.md) | Feature → Kotlin implementation map |
| [`docs/BRIEF.md`](docs/BRIEF.md) | Project goals, server access, collaboration rules |
| [`docs/UI-CONTRACT.md`](docs/UI-CONTRACT.md) | Shared control API contract |
| [`docs/api/README.md`](docs/api/README.md) | Panel API endpoint reference |
| [`docs/api/ops.md`](docs/api/ops.md) | Ops extensions: probes, alert engine, channels, host inventory, file manager, baseline, disk, watchdog, remote collection |
| [`docs/status/STATUS.md`](docs/status/STATUS.md) | Current progress and risks |
