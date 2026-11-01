#!/usr/bin/env python
"""Install the busy-hour deepseek->mimo policy into the live OpenCode relay.

  python tools/deploy_busy_hours.py --dry-run    # read-only plan + preflight
  python tools/deploy_busy_hours.py              # backup -> patch -> restart -> canary -> golden
  python tools/deploy_busy_hours.py --verify     # no writes: canary + status only
  python tools/deploy_busy_hours.py --rollback   # restore the newest backup and re-promote golden

What it changes on the host:
  /opt/relay/usagetap.py        +BUSY_HOURS_MARK hunks (the in-path tap on 127.0.0.1:8790)
  /etc/relay/busy-hours.json    new policy file (created only if absent)
  /opt/relay/busy_hours.py      new ops CLI
  /opt/relay/golden/usagetap.py re-promoted, otherwise relay_guard reverts the tap

Nothing else is touched: no nginx config, no systemd unit, no credentials, no firewall.
The token used by the canary is read from /etc/relay/relay.env on the host and is never
printed or transferred.
"""
import argparse
import hashlib
import os
import posixpath
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATCHED_TAP = os.path.join(REPO, "upstream", "relay-20261029", "usagetap.py")
CLI_SRC = os.path.join(REPO, "tools", "relay_busy_hours", "busy_hours.py")
CONF_SRC = os.path.join(REPO, "tools", "relay_busy_hours", "busy-hours.json")

TAP = "/opt/relay/usagetap.py"
CLI = "/opt/relay/busy_hours.py"
CONF = "/etc/relay/busy-hours.json"
BKDIR = "/var/backups/relay"
GOLDEN = "/opt/relay/golden/usagetap.py"
# The exact live tap bytes this patch was authored against. If the host has moved on,
# stop: uploading a whole file built from a different base would silently drop someone
# else's change.
BASE_MD5 = "7a5fe6a9c6c87f5d1f39426148a70819"

PROMPT = "Reply with exactly one word: ok"


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=180):
    _, o, e = c.exec_command(cmd, timeout=timeout)
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace"), e.read().decode("utf-8", "replace")


def md5(path):
    with open(path, "rb") as f:
        return hashlib.md5(f.read()).hexdigest()


def say(step, msg):
    print("==> %-22s %s" % (step, msg))


def emit(text):
    """Write host output that may hold non-ASCII without tripping the Windows console."""
    sys.stdout.buffer.write((text or "").encode("utf-8", "replace"))
    sys.stdout.buffer.flush()


def do_canary(c, script):
    """Run the canary on the host; the relay token is read there and never returned."""
    chan = c.get_transport().open_channel("session", None, None, None)
    chan.exec_command("export PROMPT='%s'; bash -s" % PROMPT)
    chan.sendall(script.encode("utf-8"))
    chan.shutdown_write()
    out = b"".join(iter(lambda: chan.recv(8192), b"")).decode("utf-8", "replace")
    err = b"".join(iter(lambda: chan.recv_stderr(8192), b"")).decode("utf-8", "replace")
    rc = chan.recv_exit_status()
    emit(out)
    if err.strip():
        emit("   canary stderr: " + err.strip()[:300])
    if rc:
        say("canary exit", str(rc))
    return rc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--verify", action="store_true", help="read-only canary + status")
    ap.add_argument("--rollback", action="store_true")
    ap.add_argument("--allow-drift", action="store_true",
                    help="proceed even if the live tap differs from BASE_MD5")
    ap.add_argument("--force-config", action="store_true",
                    help="overwrite /etc/relay/busy-hours.json if it already exists")
    args = ap.parse_args()

    for p in (PATCHED_TAP, CLI_SRC, CONF_SRC):
        if not os.path.exists(p):
            sys.exit("missing local source: %s" % p)

    c = connect()

    # ---------------- preflight (always read-only) ----------------
    rc, out, _ = run(c, "md5sum %s 2>/dev/null | cut -d' ' -f1" % TAP)
    live_md5 = out.strip()
    if not live_md5:
        sys.exit("%s not found -- refusing to guess where the relay lives" % TAP)
    already = run(c, "grep -q BUSY_HOURS_MARK %s && echo yes || echo no" % TAP)[1].strip() == "yes"
    say("live tap", "%s md5=%s %s" % (TAP, live_md5[:12], "(already patched)" if already else ""))
    rc, st, _ = run(c, "systemctl is-active relay-usagetap; python3 -V; node -v 2>/dev/null; date '+%F %T %Z %a'")
    say("host state", st.strip().replace("\n", " | "))

    if args.rollback:
        rc, out, _ = run(c, "ls -1t %s/usagetap.py.before-busyhours-* 2>/dev/null | head -1" % BKDIR)
        bk = out.strip()
        if not bk:
            sys.exit("no busy-hours backup found in %s" % BKDIR)
        say("rollback", "restoring %s" % bk)
        if args.dry_run:
            print("   [dry] cp %s %s && restart && update_golden.sh" % (bk, TAP))
            return 0
        rc, out, err = run(c, "cp -a %s %s && python3 -c \"import ast;ast.parse(open('%s',encoding='utf-8').read())\" "
                              "&& systemctl restart relay-usagetap && sleep 3 && systemctl is-active relay-usagetap "
                              "&& /opt/relay/update_golden.sh" % (bk, TAP, TAP))
        print(out, err)
        say("rollback done", "tap md5 now %s" % run(c, "md5sum %s | cut -d' ' -f1" % TAP)[1].strip()[:12])
        return 0

    if not args.verify:
        if live_md5 != BASE_MD5 and not already and not args.allow_drift:
            sys.exit("ABORT: live tap md5 %s != authored base %s and it is not already patched.\n"
                     "The host changed since this patch was written. Re-pull and re-apply, or pass\n"
                     "--allow-drift only after you have read the diff." % (live_md5, BASE_MD5))
        if not already and md5(PATCHED_TAP) == live_md5:
            sys.exit("ABORT: patched file equals live file -- patch is missing")

    # ---------------- canary definition ----------------
    # A few real requests, each <= 8 output tokens, run on the host so the token never
    # leaves it. Header presence is the observable: it is only set when a rewrite happens.
    canary = r'''
set -u
hdr_of() { tr -d '\r' | awk 'tolower($1)=="x-relay-rewritten-from:"{sub(/^[^:]*:[ \t]*/,"");print}' | head -1; }
to_of()  { tr -d '\r' | awk 'tolower($1)=="x-relay-rewritten-to:"{sub(/^[^:]*:[ \t]*/,"");print}' | head -1; }
code_of() { sed -n 's|^HTTP/[0-9.]* \([0-9]*\).*|\1|p' | tail -1; }

tok=$(sed -n 's/^[[:space:]]*set[[:space:]]*\$relay_token[[:space:]]*"\([^"]*\)".*/\1/p' /etc/relay/relay.env | head -1)
[ -n "$tok" ] || { echo "CANNOT READ relay_token from /etc/relay/relay.env"; exit 3; }

req() {  # $1=model -> one line describing what the tap decided
  local body hdr code from to err
  body=$(printf '{"model":"%s","messages":[{"role":"user","content":"%s"}],"max_tokens":8,"stream":false}' "$1" "$PROMPT")
  hdr=$(curl -sS -m 120 -D - -o /tmp/.canary_body.$$ \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
        -d "$body" "http://127.0.0.1${2:-/v1}/chat/completions" 2>/dev/null)
  code=$(printf '%s' "$hdr" | code_of)
  from=$(printf '%s' "$hdr" | hdr_of); to=$(printf '%s' "$hdr" | to_of)
  err=$(head -c 150 /tmp/.canary_body.$$ 2>/dev/null | tr -d '\n'); rm -f /tmp/.canary_body.$$
  printf '  %-26s http=%-4s rewritten_from=%-22s to=%-18s\n' \
         "$1" "${code:-?}" "${from:-(no rewrite)}" "${to:--}"
  [ "${code:-}" = "403" ] && printf '      403: %s\n' "$err"
  return 0
}

echo "--- canary: go route, live decision at $(date '+%H:%M %a') ---"
req deepseek-v4.1-flash
req mimo-v2.6-flash
req longcat-2.5-preview-free
echo "--- allowlist must still reject a non-Go model (403 proves the guard survived) ---"
req gpt-6-luna
echo "--- other routes must never be rewritten by this policy ---"
req glm-4.5-flash /glm/v1
req deepseek-v4.1-flash /zen/v1
'''

    if args.verify:
        do_canary(c, canary)
        rc, st, _ = run(c, "python3 %s status" % CLI)
        emit(st)
        return 0

    if args.dry_run:
        print("""
Plan (no writes yet):
  1. backup   %s -> %s/usagetap.py.before-busyhours-<ts>
  2. upload   %s (+%d bytes vs live) to %s.new, ast.parse gate, then mv into place
  3. install  %s  (only if absent unless --force-config)
  4. install  %s  (ops CLI, mode 0755)
  5. restart  relay-usagetap.service, wait for /var/lib/relay/public/usagetap.json
  6. canary   4 go requests + 1 glm request through nginx on 127.0.0.1
  7. promote  /opt/relay/update_golden.sh   <-- without this relay_guard reverts the tap

Effect: on weekdays 09:00-12:00 and 14:00-18:00 Beijing time, a Go-route request whose
model starts with "deepseek" is served by mimo-v2.6-flash, and the response carries
X-Relay-Rewritten-From. Everything else is byte-for-byte unchanged.
""" % (TAP, BKDIR, PATCHED_TAP, os.path.getsize(PATCHED_TAP) - 35858, TAP, CONF, CLI))
        return 0

    # ---------------- 1. backup ----------------
    ts = run(c, "date -u +%Y%m%d-%H%M%S")[1].strip()
    bk = "%s/usagetap.py.before-busyhours-%s" % (BKDIR, ts)
    run(c, "mkdir -p %s" % BKDIR)
    rc, out, err = run(c, "cp -a %s %s && chmod 600 %s && ls -l %s" % (TAP, bk, bk, bk))
    if rc:
        sys.exit("backup failed: %s" % err)
    say("backup", bk)

    # ---------------- 2. upload patched tap ----------------
    sftp = c.open_sftp()
    tmp_remote = TAP + ".new"
    sftp.put(PATCHED_TAP, tmp_remote)
    rc, out, err = run(c, "python3 -c \"import ast;ast.parse(open('%s',encoding='utf-8').read())\" && echo AST_OK" % tmp_remote)
    if "AST_OK" not in out:
        run(c, "rm -f %s" % tmp_remote)
        sys.exit("patched file does not parse on the host: %s" % err[-400:])
    say("syntax gate", "ast.parse OK on host python")
    if already:
        say("upload", "tap already carries BUSY_HOURS_MARK -- leaving the code alone")
        run(c, "rm -f %s" % tmp_remote)
        # a previous run may have landed it with the temp file's default mode
        rc, out, err = run(c, "chmod 600 %s && chown root:root %s && stat -c '%%a' %s" % (TAP, TAP, TAP))
        say("perms", "mode %s" % out.strip())
    else:
        rc, out, err = run(c, "mv %s %s && chmod 600 %s && chown root:root %s "
                              "&& stat -c '%%a %%U:%%G' %s && grep -c BUSY_HOURS_MARK %s"
                           % (tmp_remote, TAP, TAP, TAP, TAP, TAP))
        if rc:
            sys.exit("install failed: %s" % err)
        say("upload", "installed (%s, BUSY_HOURS_MARK x%s)" % (out.strip().replace("\n", " | "), out.splitlines()[-1]))

    # ---------------- 3/4. config + CLI ----------------
    rc, exists, _ = run(c, "test -f %s && echo yes || echo no" % CONF)
    if exists.strip() == "yes" and not args.force_config:
        say(CONF, "already present -- keeping the operator's copy")
    else:
        sftp.put(CONF_SRC, CONF)
        run(c, "chown root:root %s && chmod 600 %s" % (CONF, CONF))
        say(CONF, "installed")
    sftp.put(CLI_SRC, CLI)
    run(c, "chown root:root %s && chmod 755 %s && python3 -c \"import ast;ast.parse(open('%s',encoding='utf-8').read())\" && echo CLI_OK" % (CLI, CLI, CLI))
    say(CLI, "installed")
    sftp.close()

    # ---------------- 5. restart ----------------
    rc, out, err = run(c, "systemctl restart relay-usagetap && sleep 4 "
                          "&& systemctl is-active relay-usagetap "
                          "&& cat /var/lib/relay/public/usagetap.json")
    say("restart", out.strip()[:400] or err.strip()[:200])
    if "active" not in out:
        say("ATTENTION", "tap is not active -- check: journalctl -u relay-usagetap -n 50")

    # ---------------- 6. canary ----------------
    say("canary", "")
    do_canary(c, canary)

    rc, st, _ = run(c, "python3 %s status" % CLI)
    say("status", "")
    emit(st)

    # ---------------- 7. promote golden ----------------
    rc, out, err = run(c, "/opt/relay/update_golden.sh")
    say("golden", out.strip() or err.strip())
    rc, a, _ = run(c, "md5sum %s %s | awk '{print $1}' | sort -u | wc -l" % (TAP, GOLDEN))
    say("golden == live", a.strip() == "1" and "YES (guard cannot revert the policy)" or "NO -- investigate")

    print("\nRollback if needed:\n  python tools/deploy_busy_hours.py --rollback   (restores %s)" % bk)
    return 0


if __name__ == "__main__":
    sys.exit(main())
