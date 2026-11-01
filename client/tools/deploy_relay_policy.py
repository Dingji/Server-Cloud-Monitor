#!/usr/bin/env python
"""Deploy the relay per-client usage stats + idle-hour routing to the box.

  * backs up usagetap.py / collector.py / busy_hours.py / nginx site / policy json
  * uploads the new versions + the v2 busy-hours.json (idle block)
  * python syntax checks on the box, nginx -t before reload
  * restarts relay-usagetap + relay-stats, reloads nginx
  * verifies: identity columns in relay.db, health file idle_hours, allowlist

  python tools/deploy_relay_policy.py            # full deploy + verify
  python tools/deploy_relay_policy.py --rollback # restore the newest backups
"""
import json
import os
import posixpath
import sys
import time

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = "root"
KEY = os.path.expanduser(os.environ.get("SM_KEY", "~/.ssh/panel_ed25519"))
RELAY = "/opt/relay"
BACKUP = "/opt/relay/backups-policy-%s"
LOCAL = os.path.join(os.path.dirname(__file__), "..", "upstream", "opt", "relay")
FILES = ["usagetap.py", "collector.py", "busy_hours.py", "busy-hours.json"]
NGINX_SRC = "/etc/nginx/sites-available/opencode-relay"


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=120):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    rc = out.channel.recv_exit_status()
    return rc, out.read().decode("utf-8", "replace"), err.read().decode("utf-8", "replace")


def main():
    import argparse
    import datetime
    ap = argparse.ArgumentParser()
    ap.add_argument("--rollback", action="store_true")
    a = ap.parse_args()

    c = connect()
    sftp = c.open_sftp()
    stamp = time.strftime("%Y%m%d-%H%M%S")

    if a.rollback:
        rc, out, err = run(c, "ls -dt %s/* | head -1" % RELAY + "/backups-policy-*")
        latest = out.strip().split("\n")[-1]
        print("rollback from", latest)
        rc, out, err = run(c,
                           "cp %(b)s/usagetap.py %(r)s/usagetap.py && "
                           "cp %(b)s/collector.py %(r)s/collector.py && "
                           "cp %(b)s/busy_hours.py %(r)s/busy_hours.py && "
                           "cp %(b)s/busy-hours.json /etc/relay/busy-hours.json && "
                           "cp %(b)s/opencode-relay %(n)s && nginx -t && systemctl reload nginx && "
                           "systemctl restart relay-usagetap relay-stats && sleep 1 && "
                           "systemctl is-active relay-usagetap relay-stats nginx" % {
                               "b": latest, "r": RELAY, "n": NGINX_SRC})
        print(out.strip(), err.strip())
        return 0 if out.strip().count("active") == 3 else 1

    bdir = BACKUP % stamp
    rc, out, err = run(c, "mkdir -p %(b)s && cp %(r)s/usagetap.py %(r)s/collector.py %(r)s/busy_hours.py "
                          "%(b)s/ && cp /etc/relay/busy-hours.json %(b)s/ && cp %(n)s %(b)s/opencode-relay && echo BACKUP_OK"
                       % {"b": bdir, "r": RELAY, "n": NGINX_SRC})
    print(out.strip() or err.strip())
    if "BACKUP_OK" not in out:
        print("备份失败，终止")
        return 1

    for name in FILES:
        sftp.put(os.path.join(LOCAL, name), posixpath.join(RELAY, name))
        print("uploaded", name)
    sftp.put(os.path.join(LOCAL, "opencode-relay.sites-available"), NGINX_SRC)
    print("uploaded nginx site")

    rc, out, err = run(c, "python3 -m py_compile %s/usagetap.py %s/collector.py %s/busy_hours.py && "
                          "python3 -c 'import json;json.load(open(\"/etc/relay/busy-hours.json\"))' && echo SYNTAX_OK"
                       % (RELAY, RELAY, RELAY))
    print(out.strip() or err.strip())
    if "SYNTAX_OK" not in out:
        print("语法检查未通过，终止（备份在 %s）" % bdir)
        return 1

    rc, out, err = run(c, "nginx -t 2>&1")
    print("nginx -t:", out.strip() or err.strip())
    if "syntax is ok" not in (out + err):
        print("nginx 配置校验失败，终止（备份在 %s）" % bdir)
        return 1

    rc, out, err = run(c, "systemctl restart relay-usagetap relay-stats && systemctl reload nginx && sleep 2 && "
                          "systemctl is-active relay-usagetap relay-stats nginx")
    print("restart:", out.strip(), err.strip())
    if out.strip().count("active") != 3:
        print("服务未全部恢复，请检查 journalctl -u relay-usagetap -u relay-stats")
        return 1

    # ── verify ──
    rc, out, err = run(c, "sqlite3 /var/lib/relay/relay.db 'PRAGMA table_info(usage_events)' | grep -E 'client_(key|ip)|user_agent'; "
                          "cat /var/lib/relay/public/usagetap.json | python3 -c 'import json,sys;d=json.load(sys.stdin);"
                          "print(\"health ok=\", d[\"ok\"], \"idle=\", d.get(\"idle_hours\"))'")
    print(out.strip(), err.strip())

    rc, out, err = run(c, "python3 %s/busy_hours.py status" % RELAY)
    print(out)
    rc, out, err = run(c, "python3 %s/busy_hours.py check 10:30 && echo --- && python3 %s/busy_hours.py check 20:00" % (RELAY, RELAY))
    print(out)
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
