# Point another machine at this AI relay — two steps

Companion file: [`opencode.json`](opencode.json). It was **filtered out** of a local
configuration that is known to work: only the relay's two routes are kept, and the
models that are **guaranteed to fail** today are pre-`disabled` (the Zen route is not
provisioned; it answers `402` per model).

> The full troubleshooting manual is
> [docs/guide/opencode-relay-client.md](../../docs/guide/opencode-relay-client.md) —
> written to be executed by an AI agent. This page is only the minimal install.

---

## 1. Put the config file in place

The path is the same on Windows and on Linux/macOS:

```
~/.config/opencode/opencode.json
```

**If that file already exists on the target machine with someone else's settings, do not
overwrite it wholesale** — merge only the `providers.relay-go` and `providers.relay-zen`
blocks, and keep `"disabled_providers": ["opencode-go"]` and
`"model": "relay-go/mimo-v2.6-flash"`.

## 2. Set the distributed key (one per machine, never shared)

The issuer generates keys in the panel under **OpenCode relay → Distributed keys**; the
plaintext is shown exactly once, at creation. **The key does not go into the config file,
into the repository, or anywhere beyond your shell history.**

Windows (PowerShell; current user only, no administrator required):

```powershell
[Environment]::SetEnvironmentVariable('OPENCODE_RELAY_KEY','rk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx','User')
```

Linux / macOS (write it to your shell profile; a bare `export` in the current shell is
not enough):

```bash
printf '\nexport OPENCODE_RELAY_KEY=%q\n' 'rk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' >> ~/.bashrc
```

Environment variables only reach **newly started processes** — and closing the opencode
window is not the same as quitting. On Windows `opencode.exe` background processes survive
and keep sending the old key (measured 2026-10-31: window closed, two daemons still alive,
every request after the key change still billed to the old key). After setting the key,
confirm no process is left:

```powershell
tasklist | findstr /i opencode    # must be empty; if not: taskkill /F /IM opencode.exe
```

On Linux / macOS: `pkill -f opencode`.

## 3. Verify (all four must pass)

```bash
opencode --version                                   # expect v2.1.4 (the relay is validated against this version)
opencode models | grep -E '^relay-(go|zen)/'          # expect only the two relay-go lines to be selectable
curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST http://8.153.153.176/v1/chat/completions \
  -H "Authorization: Bearer $OPENCODE_RELAY_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"mimo-v2.6-flash","messages":[{"role":"user","content":"say ok"}],"max_tokens":8}'
```

Finally have the user send one message in opencode with `relay-go/mimo-v2.6-flash`
selected — and after the answer appears, **still do step 4**.

**4. Confirm which key the traffic is billed to (the most important check; none of the
first three can catch this).** The issuer looks at the panel's **Distributed keys** page
and confirms this key's usage is climbing. The reason: the old global token
(`ck_master`) is unlimited and still valid — so even when opencode is actually sending
the old key, the curl probe, the model list and a normal answer are all green. Only the
usage numbers expose whose account is being spent (measured: all three green, while
27 million tokens in one week were recorded under master). If the numbers do not move,
look for leftovers in this order: background processes (step 2 above) → opencode's
credential store, the `credential` table in `~/.local/share/opencode/opencode.db`
(integration `opencode-go` — a machine that ever ran `opencode auth login` has an old key
sitting there as a fallback).

Status codes: `200` fine; `401` this key does not exist or has been revoked; `403` the
model is not on the Go plan's allowlist; `429` this key hit its hour/week/month ceiling
(`error.message` in the body names the window and when it resets — **this is not a fault,
do not go changing client configuration**); `503` every upstream plan key is exhausted,
ask the issuer to add keys.

> Note: `ck_master` (the internal fallback token) can never show usage in the panel —
> a key with no limit is not even queried for `used`. That is by design, not a display
> bug. If you see traffic but no numbers, first check whether the client is still on master.

---

## Two things you will notice after installing (both are normal)

1. **Models switch automatically between busy and idle hours.** On weekdays, Beijing time
   09:00–12:00 and 14:00–18:00, deepseek requests are rewritten to mimo; outside those
   windows this route's requests go to deepseek-v4.1-flash. The client UI still shows the
   model the user picked — the rewrite happens server-side. To confirm, look at the
   response headers `X-Relay-Rewritten-From / -To / -Policy`.
2. **Quotas count tokens per key**: hour = rolling 60 minutes, week = this calendar week
   (from Monday 00:00), month = this calendar month (from the 1st, 00:00). Input served
   from cache is not counted. The issuer sees each key's consumption live in the panel.

## When you want the Zen / GLM / MiMo routes

The six `relay-zen` models are already in the config, all `disabled` (the upstream answers
`402` per model because the account is not provisioned). Once the plan is enabled, just
remove `"disabled": true` — `canonical: "opencode"` must stay. The GLM and MiMo routes
(`/glm/v1`, `/mimo/v1`) currently have no upstream quota left; add providers for them per
§4 of the manual when you need them.
