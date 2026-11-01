#!/usr/bin/env python
"""Read-only: what does the relayhub frps port 12838 actually forward to, and
what auth methods does the SSH server behind it advertise?

Nothing is written; the auth probe uses method "none", which no server accepts --
it exists precisely to make the server report its allowed methods.
"""
import json
import os

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
PORT = os.environ.get("PROBE_PORT", "12838")
USER = os.environ.get("PROBE_USER", "dingji")

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username="root", key_filename=KEY, timeout=25,
          look_for_keys=False, allow_agent=False)


def r(cmd, t=60):
    _, o, e = c.exec_command(cmd, timeout=t)
    o.channel.recv_exit_status()
    return o.read().decode("utf-8", "replace").rstrip(), e.read().decode("utf-8", "replace").rstrip()


dash = r("sed -n 's/.*#DASHPW=\\(.*\\)/\\1/p' /opt/relayhub/frps.toml")[0].strip()
print("=== frps tcp proxies ===")
out, err = r("curl -s -m 6 -u 'relayhub:%s' http://127.0.0.1:7500/api/proxy/tcp" % dash)
try:
    for p in json.loads(out).get("proxies", []):
        print("   %-22s remotePort=%-7s type=%-5s conns=%s" %
              (p.get("name"), p.get("remotePort"), p.get("type"), len(p.get("todayConns") or [])))
except Exception as exc:
    print("   dashboard unreadable:", exc, out[:120])

print("=== frps clients (which devices are online) ===")
out, _ = r("curl -s -m 6 -u 'relayhub:%s' http://127.0.0.1:7500/api/clients" % dash)
try:
    for cl in json.loads(out).get("clients", []):
        print("   user=%-12s version=%s" % (cl.get("user"), cl.get("version")))
except Exception as exc:
    print("   none:", out[:120])

print("=== relayhub mappings.json ===")
print(r("cat /opt/relayhub/panel/mappings.json")[0] or "   (empty)")

print("=== panel device registry ===")
print(r("head -c 1200 /opt/cloud-monitor/devices.json")[0] or "   (none)")

print("\n=== SSH offered auth methods on 127.0.0.1:%s (from the cloud box) ===" % PORT)
print("    method 'none' is never accepted; the reply lists what IS accepted")
out, err = r("ssh -o BatchMode=yes -o StrictHostKeyChecking=no -o PreferredAuthentications=none "
             "-p %s %s@127.0.0.1 2>&1 | tail -4" % (PORT, USER))
print(out)
print("--- sshd banner ---")
print(r("timeout 6 bash -c 'exec 3<>/dev/tcp/127.0.0.1/%s; head -1 <&3'" % PORT)[0] or "   (no banner)")
c.close()
