# tests/ — the test and verification harness (the *testing* role)

This directory is where the machine-checkable evidence comes from for the question
"is the replica complete, and is it correct". Every script is **Python 3 standard
library plus paramiko** (reusing `tools/sh.py`): nothing depends on a Kotlin build,
so any of them can run at any time.

## Scripts

| Script | What it does | Output | Needs |
|---|---|---|---|
| `api_contract.py` | Sends real requests against **all 96 GET endpoints** registered in `app/server/index.js`, checking status code, top-level shape and the types of key fields (baseline = the `api-dump/*.json` snapshots, which are generated locally and are not committed here), plus negative auth contracts: no token, forged token, wrong password, unknown route, invalidated session | `report-api.md`, `report-api.json` | Network (panel reachable) |
| `endpoint_inventory.py` | Parses every route in `index.js` (`api.get/post`, the `dbG`/`dbR`/`pkgG` factories, `RELAY_GET`) plus the WS endpoints in `hub.js`, compares them with what `api_contract.py` covers and **reports the uncovered ones** | `endpoints.md` | No network |
| `collect_baseline.py` | Collects a server health baseline through `tools/sh.py` (read-only SSH commands) plus HTTP probes | `baseline.md` | Network + SSH key |

## Running them

```powershell
python tests\endpoint_inventory.py     # seconds, pure static analysis
python tests\api_contract.py           # ~1 minute, rate-limited and serial
python tests\collect_baseline.py       # ~1-2 minutes, 16 read-only SSH commands
python tests\api_contract.py --only relay   # only the cases whose path contains "relay"
```

## Hard safety rules (follow them)

1. **Rate limiting**: the panel API allows **1800 requests per minute per IP**
   (`app/server/auth.js`, `API_MAX_PER_WINDOW=1800`; the 240 in an `index.js` comment is
   stale). Every script runs serially with sleeps and backs off 65 seconds on a 429.
2. **No side effects**: GET only, plus the three harmless POSTs `/login`, `/logout` and
   `/logs/tail`. Nothing under `POST /…/action`, no `/db/*` writes, no `/pkg/*`
   mutations — those are never called from automated tests.
3. **Do not touch `tools/.token`**: the contract script logs in for its own token and
   logs out afterwards, so it cannot disturb the other roles.
4. **Local proxy trap**: a Windows registry proxy (e.g. `127.0.0.1:7890`) makes urllib
   return an **empty 502** for plain HTTP. Every script forces a direct connection with
   `ProxyHandler({})`. The same applies when you poke the server by hand with
   `curl`/`Invoke-WebRequest`: a 502 may be your own proxy, not the server.
5. **Failed logins accumulate**: 8 wrong passwords lock the account for 10 minutes
   (`auth.js`, `MAX_FAILS`). The negative tests send a wrong password **once**.

## What each verdict means

| Verdict | Meaning |
|---|---|
| PASS | Status code, top-level shape and every baseline field and type match |
| PASS* | Endpoint reachable and returning valid JSON, with no `api-dump` baseline (routes that need arguments or exercise an error path — this checks "the route exists + the error contract") |
| WARN | Endpoint works but differs from the `api-dump` baseline by a missing field or a type drift (usually a change in server data; confirm by hand) |
| FAIL | Unexpected status code, non-JSON body, or a key-field assertion failed |

## How this proves "not one missing"

- `endpoints.md` proves every backend route is inventoried: all GETs covered, POSTs
  listed explicitly (they have side effects, so they are not called automatically).
- `report-api.md` proves each endpoint's real response shape matches `api-dump` — or
  names the drift.
- `parity.md` (produced once `docs/api/feature-matrix.md` is complete) maps 20 pages ×
  every feature to its Kotlin implementation. That is the final acceptance evidence.

## Compile gate

```powershell
.\build.ps1 :composeApp:compileKotlinDesktop
```
On failure: report the complete errors by file and line, hand them to the owning role
(do not edit someone else's source), and record the result in `tests/build-gate.md`.
