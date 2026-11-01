#!/usr/bin/env python
"""Make the Pi's normal `ssh root@8.153.153.176` work by telling ssh which key to use.

Why this is the fix: ssh only auto-tries id_rsa/id_ecdsa/id_ed25519, and the Pi's keys are
named id_cloud_ed25519 / id_server_ed25519, so a plain invocation offers nothing.

Safety: first read fail2ban's sshd jail (the Pi's egress IP has preauth failures in the
cloud log). Then append -- never overwrite -- a marked block to ~/.ssh/config, backing the
file up first. Rollback is the printed mv of the .bak file.
"""
import logging
import os
import time

import paramiko

logging.getLogger("paramiko").setLevel(logging.CRITICAL)

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
PI_PORT = int(os.environ.get("PI_PORT", "12838"))
PI_USER = os.environ.get("PI_USER", "dingji")
KEY = os.path.expanduser(os.environ.get("PUBKEY_FILE", "~/.ssh/oc_relay_ed25519"))
MARK = "# qoder: cloud server key"
DRYRUN = os.environ.get("PI_DRYRUN", "") == "1"

BLOCK = (
    "\n%s\n"
    "Host %s cloud-server\n"
    "    HostName %s\n"
    "    User root\n"
    "    IdentityFile ~/.ssh/id_cloud_ed25519\n"
    "    IdentitiesOnly yes\n"
    "    ServerAliveInterval 30\n"
) % (MARK, CLOUD, CLOUD)


def connect(host, port, user, tries=20):
    last = None
    for i in range(1, tries + 1):
        c = paramiko.SSHClient()
        c.load_system_host_keys()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            c.connect(hostname=host, port=port, username=user, key_filename=KEY,
                      look_for_keys=False, allow_agent=False, timeout=30)
            return c
        except Exception as exc:
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:90])
            c.close()
            time.sleep(2.5)
    raise RuntimeError("never connected to %s:%d (%s)" % (host, port, last))


def r(cli, cmd, t=60):
    _, o, e = cli.exec_command(cmd, timeout=t)
    rc = o.channel.recv_exit_status()
    return rc, (o.read().decode("utf-8", "replace").strip() or e.read().decode("utf-8", "replace").strip() or "(empty)")


print("=== 1. cloud: is the Pi's egress IP banned right now? ===")
cloud = connect(CLOUD, 22, "root")
print(r(cloud, "fail2ban-client status sshd 2>&1 | tr -d '\\n' | sed 's/||/ | /g'")[1][:700])
print(r(cloud, "journalctl --no-pager -n 2000 2>/dev/null | grep -iE '218\\.90\\.2\\.208' | grep -iE 'ban|unban' | tail -6")[1] or "   (no ban/unban lines for 218.90.2.208 in the last 2000 log lines)")
cloud.close()

print("\n=== 2. pi: current ~/.ssh/config ===")
pi = connect(CLOUD, PI_PORT, PI_USER)
rc, out = r(pi, "cat ~/.ssh/config 2>/dev/null || echo '(no config file)'")
print(out[:900])
rc, has = r(pi, "grep -qF '%s' ~/.ssh/config 2>/dev/null && echo ALREADY || echo MISSING" % CLOUD)
print("   block already present for %s: %s" % (CLOUD, has))

if DRYRUN:
    print("\nDRY RUN -- would append this block (existing file untouched, backup first):")
    print(BLOCK)
    pi.close()
    raise SystemExit(0)

if has == "ALREADY":
    print("   nothing to write")
else:
    print("\n=== 3. back up, then append (never overwrite) ===")
    print(r(pi, "ts=$(date +%s); [ -f ~/.ssh/config ] && cp -p ~/.ssh/config ~/.ssh/config.bak-$ts && echo \"BACKUP ~/.ssh/config.bak-$ts\" || echo 'BACKUP n/a (no prior config)'")[1])
    sftp = pi.open_sftp()
    home = r(pi, "echo -n $HOME")[1]
    remote_cfg = "%s/.ssh/config" % home.rstrip("/")
    with sftp.file(remote_cfg, "a") as f:
        f.write(BLOCK)
    sftp.close()
    print("   wrote %s" % remote_cfg)
    print(r(pi, "chmod 600 ~/.ssh/config; ls -l ~/.ssh/config; tail -8 ~/.ssh/config")[1])

print("\n=== 4. verify: plain ssh WITHOUT -i, from inside the Pi ===")
rc, out = r(pi, "ssh -o BatchMode=yes -o ConnectTimeout=10 root@%s 'echo PI_TO_CLOUD_OK; hostname; id -un' 2>&1 | tail -4" % CLOUD)
print("   rc=%d\n%s" % (rc, out))
rc2, out2 = r(pi, "ssh -o BatchMode=yes -o ConnectTimeout=10 cloud-server 'echo ALIAS_OK; hostname' 2>&1 | tail -3")
print("   alias form: rc=%d %s" % (rc2, out2))
rc3, out3 = r(pi, "ssh -G root@%s | grep -iE '^(identityfile|identitiesonly|user|hostname) ' | tr '\\n' ' '" % CLOUD)
print("   ssh -G says:", out3[:300])
pi.close()
if "PI_TO_CLOUD_OK" not in out:
    print("\n!! plain ssh still failed -- restore: mv ~/.ssh/config.bak-<ts> ~/.ssh/config")
    raise SystemExit(1)
print("\nOK. Rollback if ever needed: cp -p ~/.ssh/config.bak-<ts> ~/.ssh/config")
