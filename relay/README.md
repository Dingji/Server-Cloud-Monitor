# OpenCode relay server

Every load-bearing file of the relay service that lives under `/opt/relay` and
`/etc/relay`. Before this directory existed, those files were only on the running
host. What is checked in here is **the copy that actually works** — verified
file-by-file against the live host by md5, not a rewrite or a simplification.

The panel's *OpenCode relay* page, the busy-hours tab behind `relay.js`, and the
`phase` / `calendar` fields the Compose client reads are all produced by
`server/collector.py`.

## Paths and where they deploy

| Repository path | Installed to | Notes |
| --- | --- | --- |
| `server/usagetap.py` | `/opt/relay/usagetap.py` | `relay-usagetap.service`, :8790. **On the request path**: rewriting and the allowlist happen here |
| `server/collector.py` | `/opt/relay/collector.py` | `relay-stats.service`, :8787. Stats API plus busy-hours policy read/write |
| `server/keystore.py` | `/opt/relay/keystore.py` | Hard dependency of the collector (multiple keys, quota-aware key choice) |
| `server/clientkeys.py` | `/opt/relay/clientkeys.py` | The distributed client keys: store, per-window quotas, the gate the tap calls, and the generated credential list |
| `server/busy_hours.py` | `/opt/relay/busy_hours.py` | Busy-hours policy editor and the on/off marker used by the panel and timers |
| `server/relay_guard.py` | `/opt/relay/relay_guard.py` | `relay-guard.service`, integrity and streaming-canary self check |
| `server/selfheal.py` | `/opt/relay/selfheal.py` | `relay-selfheal.service` |
| `server/modelcatalog.py` `modelcheck.py` `daily_report.py` `relay_alert.py` | `/opt/relay/` | Each driven by its own timer |
| `server/{db,disk}_guard.sh` `disk_hygiene.sh` `backup_relay.sh` `backup_verify.sh` | `/opt/relay/` | Backup and watermark guards |
| `nginx/opencode-relay` | `/etc/nginx/sites-available/opencode-relay` | The site nginx actually runs (**not** `/opt/relay/opencode-relay`, which is a stale 222-line copy) |
| `nginx/{opencode,glm,mimo}-upstream.conf` | `/opt/relay/` | Upstream definitions |
| `systemd/relay-*.service` `relay-*.timer` | `/etc/systemd/system/` | 13 services plus 11 timers |
| `config/busy-hours.json` | `/etc/relay/busy-hours.json` | Busy-hours policy; the tap reloads it by mtime, so editing it needs no restart |
| `config/go-allowlist.json` | `/etc/relay/go-allowlist.json` | Models the Go plan may use; missing means fail-closed 403 |

## What is deliberately not here

**`/etc/relay/upstream-auth.conf` is never committed.** nginx `include`s it in three
places and its content is `proxy_set_header ... "Bearer <real token>"` — the upstream
credentials themselves. A new host must create it, or nginx will not start. The same
applies to `/etc/nginx/.htpasswd-relay` (basic auth for `/stats/`).

Also excluded: `install_tap*.sh`, `install_timer_and_verify.sh`, `retention_tune.py`,
`patch_*.py`. Those are one-off migration and verification scripts whose curl lines
embed real tokens, and they only make sense for the single change they were written for.

Generated runtime state is not committed either: `/etc/relay/relay-auth.conf` (the
client-key list rendered from `client-keys.json`), `/etc/relay/client-keys.json`, and
`/etc/relay/client-labels.json`.

## Golden files and the guard

When `relay_guard.py` detects tampering it restores `usagetap.py`,
`opencode-relay.nginx` and `go-allowlist.json` from `/opt/relay/golden/`, and
`/opt/relay/update_golden.sh` refreshes that baseline. **After changing `usagetap.py`
or the nginx site you must refresh the golden copy**, otherwise the edit is reverted
once the 180-second `recently_edited` grace window expires. `collector.py` is not on
the guard's list.

## Who decides the busy window

There is exactly one answer to "is the window active right now": `busy_window_active()`
in `usagetap.py`. The "weekdays only" rule calls `chinese_calendar.is_workday()`, so it
knows official holidays and make-up workdays. That library covers 2004–2026; years
outside the range fall back to plain Monday–Friday and will treat that year's public
holidays as workdays.

`busy_state()` in `collector.py` publishes the same verdict together with `calendar`
(the days that disagree with Monday–Friday) and `phase` (busy or idle, and why), so the
web page and the client only render it — **neither decides the window again on its own**.
Pausing the policy temporarily writes the marker file `/var/lib/relay/busy-hours.off`
instead of editing the configuration.

## Client origins and device labels

Field 19 of `relay_json` in `nginx/opencode-relay` is `$http_x_forwarded_for`, and the
collector folds it together with `$remote_addr` into a `client_src` side table. The hot
`requests` table is left alone: its INSERT statement hard-codes 22 columns. Putting the
new field last is deliberate — a client that stuffs `|` into XFF can only append junk at
the end, never shift the first 18 fields.

- `GET /api/client-origins` returns, per origin: `ip` / `class` / `xff` / `ua` / `hits` /
  first and last seen / `label`.
- Labels are written through the action channel: `POST /api/clients/label` with body
  `{"ip":…,"label":…}` (empty clears it). Labels live in `/etc/relay/client-labels.json`
  — a file rather than a database column — so pruning traffic history does not take the
  device names with it.
- `class` is decided by the collector (it is the only component that sees the real peer);
  clients only localise the display.

**Why there is no MAC column**: this host's only interface is an Aliyun VPC address, every
neighbour in `ip neigh` resolves to the vRouter's synthetic MAC `ee:ff:ff:ff:ff:ff`, and
layer-2 frames do not cross a router anyway — anything stored would be fake. Same reason
there is no TTL or OS fingerprint: the client socket is terminated by nginx, nginx does not
expose TTL, and the peer the tap sees is always `127.0.0.1`. The only reliable way to tell
different devices behind one NAT apart would be hashing each client's API key, which means
touching the tap that sits on the request path.

## One step still missing

`install.sh` currently covers the panel, `--with-php` and `--with-relayhub`. There is
**no `--with-relay` yet**: a fresh host that clones this repository gets every file in
the table above, but still has to place them by hand and run
`systemctl enable --now` on each unit.
