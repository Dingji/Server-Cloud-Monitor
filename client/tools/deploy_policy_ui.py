#!/usr/bin/env python
"""Deploy the busy-hours policy UI stack (relay collector + panel proxy + web page).

  python tools/deploy_policy_ui.py --dry-run    # read-only: drift check + plan
  python tools/deploy_policy_ui.py              # backup -> upload -> syntax gate -> restart -> smoke
  python tools/deploy_policy_ui.py --rollback   # restore the newest backup set

Safety gates before anything is written:
  * every live file must match the base hash this work was edited from
  * the diff of each file must be insertions only (no silent deletion of someone else's line)
Nothing here touches nginx, systemd unit definitions, credentials, or the firewall.
"""
import argparse
import difflib
import hashlib
import os
import posixpath
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BK_ROOT = "/var/backups/relay-policy-ui"

# (local edited file, remote path, base md5 of the bytes this was edited from)
FILES = [
    ("upstream/relay-20261029/collector.py",
     "/opt/relay/collector.py", None),
    ("upstream/live-20261029/server/index.js",
     "/opt/cloud-monitor/server/index.js", "b63f2983cdde"),
    ("upstream/live-20261029/server/relay.js",
     "/opt/cloud-monitor/server/relay.js", "e929b23fd158"),
    ("upstream/live-20261029/public/js/pages/relay.js",
     "/opt/cloud-monitor/public/js/pages/relay.js", "731246cfb52e"),
]
SERVICES = ["relay-stats", "cloud-monitor"]


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


def md5(b):
    return hashlib.md5(b).hexdigest()


def say(step, msg):
    print("==> %-24s %s" % (step, msg))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    ap.add_argument("--allow-drift", action="store_true")
    args = ap.parse_args()
    c = connect()
    sftp = c.open_sftp()

    def read_remote(p):
        try:
            with sftp.open(p, "rb") as f:
                return f.read()
        except IOError:
            return None

    if args.rollback:
        rc, out, _ = run(c, "ls -1dt %s/*/ 2>/dev/null | head -1" % BK_ROOT)
        d = out.strip().rstrip("/")
        if not d:
            sys.exit("no backup set under %s" % BK_ROOT)
        say("rollback from", d)
        py = (
            "import json, os, shutil, sys\n"
            "d = %r\n"
            "man = json.load(open(os.path.join(d, 'manifest.json'), encoding='utf-8'))\n"
            "for name, target in man.items():\n"
            "    src = os.path.join(d, name)\n"
            "    if not os.path.exists(src) or not os.path.isdir(os.path.dirname(target)):\n"
            "        print('SKIP', target); continue\n"
            "    shutil.copyfile(src, target); print('restored', target, os.path.getsize(target), 'bytes')\n" % d
        )
        rc, out, err = run(c, "python3 - <<'PYEOF'\n" + py + "PYEOF\n")
        print(out, err)
        rc, out, _ = run(c, "python3 -c \"import ast;ast.parse(open('/opt/relay/collector.py',encoding='utf-8').read())\" "
                            "&& node --check /opt/cloud-monitor/server/index.js && node --check /opt/cloud-monitor/public/js/pages/relay.js "
                            "&& echo SYNTAX_OK")
        say("post-restore syntax", out.strip() or "check failed -- not restarting")
        if "SYNTAX_OK" not in out:
            sys.exit("restored files do not parse; refusing to restart. Inspect %s" % d)
        rc, out, _ = run(c, "systemctl restart relay-stats cloud-monitor; sleep 4; "
                            "systemctl is-active relay-stats cloud-monitor")
        say("services", out.strip().replace("\n", " | "))
        return 0

    # ---------------- preflight: drift + insertions-only ----------------
    problems = []
    pending = []
    for local_rel, remote, base in FILES:
        local_path = os.path.join(REPO, *local_rel.split("/"))
        new_bytes = open(local_path, "rb").read()
        live = read_remote(remote)
        if live is None:
            problems.append("MISSING on host: %s" % remote)
            continue
        live_md5 = md5(live)
        if base and not live_md5.startswith(base):
            problems.append("DRIFT %s live=%s expected=%s*" % (remote, live_md5[:12], base))
        # additive edit, and any changed line must be a recognisable modification of
        # a live line -- never the silent dropping of someone else's work
        a = live.decode("utf-8", "replace").splitlines()
        b = new_bytes.decode("utf-8", "replace").splitlines()
        removed = [l[2:] for l in difflib.ndiff(a, b) if l.startswith("- ")]
        added = [l[2:] for l in difflib.ndiff(a, b) if l.startswith("+ ")]
        lost, modified = [], []
        for line in removed:
            best = difflib.get_close_matches(line, added, n=1, cutoff=0.6)
            (modified if best else lost).append((line, best[0] if best else ""))
        if lost:
            problems.append("DELETIONS in %s (%d lines have no counterpart):\n    %s"
                            % (remote, len(lost), "\n    ".join(x[:140] for x in lost[:8])))
        pending.append((local_path, remote, live_md5, new_bytes, len(added), len(a), len(b)))
        note = ("%d..%d lines, +%d, ~%d modified" % (len(a), len(b), len(added), len(modified)))
        if modified:
            note += " [" + " | ".join(m[1][:60] for m in modified[:3]) + "]"
        say("preflight", "%s live=%s %s, 0 lost" % (posixpath.basename(remote), live_md5[:12], note))

    if problems:
        print("\nREFUSING TO WRITE:")
        for p in problems:
            print("  x " + p)
        sys.exit("re-pull and re-apply, or pass --allow-drift after reading the diff")

    if args.dry_run:
        print("\n[dry-run] would back up and replace:")
        for _, remote, _, _, _, _, _ in pending:
            print("   ", remote)
        print("[dry-run] then restart: " + ", ".join(SERVICES))
        return 0

    # ---------------- backup ----------------
    ts = run(c, "date -u +%Y%m%d-%H%M%S")[1].strip()
    bdir = "%s/%s" % (BK_ROOT, ts)
    run(c, "mkdir -p %s" % bdir)
    manifest = {}
    for i, (_, remote, live_md5, _, _, _, _) in enumerate(pending):
        bn = "%d-%s" % (i, posixpath.basename(remote))
        with sftp.open(posixpath.join(bdir, bn), "wb") as f:
            f.write(read_remote(remote))
        manifest[bn] = remote
        say("backup", "%s  (md5 %s)" % (posixpath.join(bdir, bn), live_md5[:12]))
    import json as _json
    with sftp.open(posixpath.join(bdir, "manifest.json"), "w") as f:
        f.write(_json.dumps(manifest, indent=2))
    say("rollback cmd", "python tools/deploy_policy_ui.py --rollback")

    # ---------------- upload with a host-side syntax gate ----------------
    # The gate copy must keep the real file extension: `node --check foo.js.tmp`
    # exits 1 with no message at all, which looks like a syntax error in the code.
    gate_dir = "/var/tmp/policy-ui-gate"
    run(c, "mkdir -p %s" % gate_dir)
    for i, (local_path, remote, _, new_bytes, _, _, _) in enumerate(pending):
        ext = ".py" if remote.endswith(".py") else ".js"
        probe = "%s/%d-check%s" % (gate_dir, i, ext)
        sftp.put(local_path, probe)
        if ext == ".py":
            gate = "python3 -c \"import ast;ast.parse(open('%s',encoding='utf-8').read())\" && echo AST_OK" % probe
        else:
            gate = "node --check %s && echo NODE_OK" % probe
        rc, out, err = run(c, gate)
        if "OK" not in out:
            run(c, "rm -f %s" % probe)
            sys.exit("SYNTAX GATE FAILED for %s (rc=%s)\nSTDOUT: %s\nSTDERR: %s"
                     % (remote, rc, out.strip()[-600:], err.strip()[-900:]))
        mode = run(c, "stat -c %%a %s" % remote)[1].strip() or "644"
        sftp.put(local_path, remote)
        rc, out, err = run(c, "chmod %s %s && chown root:root %s && rm -f %s" % (mode, remote, remote, probe))
        if rc:
            sys.exit("permission fix failed for %s: %s" % (remote, err))
        now = md5(read_remote(remote))
        if now != md5(new_bytes):
            sys.exit("uploaded %s does not match local bytes (%s vs %s)" % (remote, now[:12], md5(new_bytes)[:12]))
        say("installed", "%s (mode %s, md5 %s)" % (remote, mode, now[:12]))
    run(c, "rm -rf %s" % gate_dir)

    # ---------------- restart ----------------
    rc, out, err = run(c, "systemctl restart relay-stats && sleep 3 && systemctl is-active relay-stats")
    say("relay-stats", out.strip() or err.strip())
    if "active" not in out:
        say("ATTENTION", "relay-stats is not active -- journalctl -u relay-stats -n 40")

    rc, out, err = run(c, "systemctl restart cloud-monitor && sleep 4 && systemctl is-active cloud-monitor")
    say("cloud-monitor", out.strip() or err.strip())

    # ---------------- smoke ----------------
    print("\n==> smoke (collector direct)")
    rc, out, _ = run(c, "curl -s -m 15 http://127.0.0.1:8787/api/busy-hours")
    print("    GET /api/busy-hours ->", out[:260].replace("\n", " "))
    rc, out, _ = run(c, "curl -s -m 15 -X POST http://127.0.0.1:8787/api/busy-hours/save "
                        "-H 'Content-Type: application/json' -H 'X-Relay-Action: 1' "
                        "-d '{\"enabled\":true,\"weekdays_only\":true,\"route\":\"go\","
                        "\"target_model\":\"gpt-6-luna\",\"rewrite_prefixes\":[\"deepseek\"],"
                        "\"windows\":[{\"start\":\"09:00\",\"end\":\"12:00\"}],\"utc_offset_minutes\":480}'")
    print("    off-allowlist target  ->", out[:260].replace("\n", " "))
    rc, out, _ = run(c, "curl -s -m 15 -X POST http://127.0.0.1:8787/api/busy-hours/save "
                        "-H 'Content-Type: application/json' -H 'X-Relay-Action: 1' "
                        "-d '{\"enabled\":true,\"weekdays_only\":true,\"route\":\"go\","
                        "\"target_model\":\"gpt-6-luna\",\"rewrite_prefixes\":[\"deepseek\"],"
                        "\"windows\":[{\"start\":\"9am\",\"end\":\"12:00\"}],\"utc_offset_minutes\":480}'")
    print("    bad window format     ->", out[:260].replace("\n", " "))
    # a CSRF-less POST must be refused
    rc, out, _ = run(c, "curl -s -m 15 -o /dev/null -w '%{http_code}' -X POST "
                        "http://127.0.0.1:8787/api/busy-hours/switch -d '{}'")
    print("    POST without X-Relay-Action ->", out.strip(), "(expect 403)")

    print("\n==> smoke (through the panel proxy)")
    rc, out, _ = run(c, "curl -s -m 15 -o /dev/null -w '%{http_code}' "
                        "http://127.0.0.1:9000/panel/api/relay/busy-hours")
    print("    GET /panel/api/relay/busy-hours (no token) ->", out.strip(), "(expect 401, not 404)")
    rc, out, _ = run(c, "curl -s -m 15 -o /dev/null -w '%{http_code}' "
                        "http://127.0.0.1:9000/panel/api/relay/nosuchendpoint")
    print("    unknown relay name                        ->", out.strip(), "(expect 404)")
    rc, out, _ = run(c, "grep -c 'tabPolicy\\|忙时策略' /opt/cloud-monitor/public/js/pages/relay.js")
    print("    web page carries the new tab ->", out.strip(), "matches")
    rc, out, _ = run(c, "grep -c busy-hours /opt/cloud-monitor/server/index.js /opt/cloud-monitor/server/relay.js")
    print("    panel proxy ->", out.strip().replace("\n", " | "))

    rc, out, _ = run(c, "journalctl -u relay-stats --since '-2 min' --no-pager -p err | tail -5")
    print("\nrelay-stats err journal:", out.strip() or "(none)")
    rc, out, _ = run(c, "journalctl -u cloud-monitor --since '-2 min' --no-pager -p err | tail -5")
    print("cloud-monitor err journal:", out.strip() or "(none)")
    sftp.close()
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
