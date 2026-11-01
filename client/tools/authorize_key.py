#!/usr/bin/env python
"""Append a public key to the cloud box's root authorized_keys -- additively.

Guarantees, each verified on the host afterwards:
  * the file is backed up first and never overwritten in place
  * existing lines are untouched (size and per-line identity are compared)
  * the appended line parses as a key AND its fingerprint equals what the operator pasted
  * idempotent: running twice does not add a second copy
  * sshd is only syntax-checked; no restart, no reload, no config change

  python tools/authorize_key.py --check              read-only report
  python tools/authorize_key.py "<pubkey line>" --expect <fingerprint>
  python tools/authorize_key.py --rollback <backup path>
"""
import argparse
import os
import re
import shlex
import subprocess
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = "root"
KEY = os.path.expanduser(os.environ.get("SM_KEY", "~/.ssh/oc_relay_ed25519"))
AK = "/root/.ssh/authorized_keys"
FP_RE = re.compile(r"^SHA256:[A-Za-z0-9+/=]{20,}$")

PUB_RE = re.compile(r"^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256|sk-ssh-ed25519@openssh\.com)\s+\S+")


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    return c


def r(c, cmd, t=90):
    _, o, e = c.exec_command(cmd, timeout=t)
    rc = o.channel.recv_exit_status()
    return rc, o.read().decode("utf-8", "replace").rstrip(), e.read().decode("utf-8", "replace").rstrip()


def local_fingerprint(line):
    """Fingerprint computed locally from the pasted line -- an independent check that
    the string is a well-formed key before it ever reaches the host."""
    p = subprocess.run(["ssh-keygen", "-lf", "-"], input=line.encode("utf-8"),
                       capture_output=True)
    m = re.search(r"(SHA256:\S+)", p.stdout.decode("utf-8", "replace"))
    return m.group(1) if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pubkey", nargs="?", help="the full 'type base64 comment' line")
    ap.add_argument("--expect", help="fingerprint the operator was shown, SHA256:...")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--rollback", metavar="BACKUP")
    args = ap.parse_args()

    c = connect()

    if args.rollback:
        bk = args.rollback
        rc, out, err = r(c, "test -f %s && cp -a %s %s && echo RESTORED"
                         % (shlex.quote(bk), shlex.quote(bk), AK))
        print(out or err)
        if "RESTORED" not in out:
            sys.exit("restore failed")
        rc, out, _ = r(c, "awk '{print $3}' %s" % AK)
        print("authorized_keys comments now:", out.replace("\n", " "))
        return 0

    rc, listing, _ = r(c, "wc -l < %s; awk '{print \"  \"$3}' %s" % (AK, AK))
    lines = listing.splitlines()
    before_n = lines[0].strip() if lines else "?"
    print("=== before: %s entries ===" % before_n)
    print("\n".join(lines[1:]))
    rc, opts, _ = r(c, "sshd -T 2>/dev/null | grep -i '^authorizedkeysfile'")
    print("sshd reads keys from:", opts or "(default ~/.ssh/authorized_keys)")
    rc, perm, _ = r(c, "stat -c '%%a %%U:%%G %%n' /root/.ssh %s" % AK)
    print("permissions:\n " + perm.replace("\n", "\n "))

    if args.check:
        return 0

    if not args.pubkey:
        sys.exit("need the public key line (or --check)")
    line = " ".join(args.pubkey.split())          # collapse any wrapped/spaced paste
    if not PUB_RE.match(line):
        sys.exit("not a recognised public key line: %r" % line[:60])
    if "$" in line or "`" in line or ";" in line:
        sys.exit("refusing: the line contains shell metacharacters")

    fp = local_fingerprint(line)
    if not fp:
        sys.exit("this box's ssh-keygen cannot parse the pasted key -- re-copy the .pub contents")
    print("\nlocal parse OK, fingerprint:", fp)
    if args.expect:
        if not FP_RE.match(args.expect):
            sys.exit("--expect should look like SHA256:...")
        if fp != args.expect.strip():
            sys.exit("FINGERPRINT MISMATCH: pasted line gives %s but the operator saw %s"
                     % (fp, args.expect))
        print("matches the fingerprint the operator was shown:", args.expect)
    else:
        print("!! --expect not supplied: cannot confirm this is the key the Pi generated")

    rc, exists, _ = r(c, "grep -qxF %s %s && echo YES || echo NO" % (shlex.quote(line), AK))
    if exists.strip() == "YES":
        print("\nalready authorised -- nothing to do.")
        return 0

    ts = r(c, "date +%Y%m%d-%H%M%S")[1].strip()
    bak = "%s.bak-%s" % (AK, ts)
    q_line = shlex.quote(line)
    script = (
        "set -e; "
        "cp -a %s %s; "
        "tail -c1 %s | od -An -tx1 | grep -q '0a' || printf '\\n' >> %s; "
        "printf '%%s\\n' %s >> %s; "
        "chmod 600 %s; chmod 700 /root/.ssh; "
        "echo APPENDED"
        % (AK, bak, AK, AK, q_line, AK, AK)
    )
    rc, out, err = r(c, script)
    if "APPENDED" not in out:
        sys.exit("append failed (rc=%s): %s %s" % (rc, out, err))
    print("\nappended; backup at", bak)

    # ---- verify ----
    rc, out, _ = r(c, "wc -l < %s" % AK)
    after_n = out.strip()
    rc, dup, _ = r(c, "grep -cxF %s %s" % (q_line, AK))
    rc, fp_host, _ = r(c, "grep -xF %s %s | ssh-keygen -lf - | awk '{print $2}'" % (q_line, AK))
    rc, comments, _ = r(c, "awk '{print \"  \"$3}' %s" % AK)
    rc, same, _ = r(c, "diff <(grep -vxF %s %s | sort) <(sort %s) >/dev/null && echo UNCHANGED || echo CHANGED"
                    % (q_line, AK, bak))
    rc, syntax, _ = r(c, "sshd -t 2>&1 && echo SSHD_CONFIG_OK")
    print("entries:", before_n, "->", after_n, "| copies of the new line:", dup.strip())
    print("fingerprint on the host:", fp_host.strip())
    print("pre-existing lines:", "UNCHANGED" if same.strip() == "UNCHANGED" else "CHANGED(!)")
    print("comments now:\n" + comments)
    print("sshd config still valid:", syntax.strip().splitlines()[-1])
    if fp_host.strip() != fp:
        print("!! host fingerprint differs from local -- inspect", AK)
        sys.exit(1)
    print("\nOK. Test it from the Pi:\n  ssh -i ~/.ssh/id_cloud_ed25519 root@%s" % HOST)
    print("Rollback if needed:  python tools/authorize_key.py --rollback %s" % bak)
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
