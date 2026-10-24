#!/usr/bin/env python
"""Remove the commercial licence/quota/order layer (biz.js) from the panel,
keeping the host inventory (纳管主机) by migrating it into the ops module.

  python tools/remove_biz.py --dry-run   # 只读预检，打印将要做的每件事
  python tools/remove_biz.py             # 执行（备份 → 摘挂载 → 迁数据 → 重启 → 冒烟）

Nothing is deleted: biz.js, its public key and its state directory are moved
under /opt/cloud-monitor/backups/ with a timestamp, and index.js is backed up
first. Roll back by restoring backups/index.js.before-biz-remove-*.
"""
import argparse
import datetime
import json
import os
import posixpath
import re
import sys

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
APP = "/opt/cloud-monitor"
SRV = posixpath.join(APP, "server")
BACKUP_DIR = posixpath.join(APP, "backups")
BIZ_JS = posixpath.join(SRV, "biz.js")
PUBKEY = posixpath.join(SRV, "keys", "license_public.pem")
BIZ_DATA = "/var/lib/cloud-panel/biz"
OPS_DATA = "/var/lib/cloud-panel/ops"
PASSWORD = "**"

BLOCK_RE = re.compile(
    rb"/\* =+ commercial layer[^=]*=+ \*/\ntry \{ require\('\./biz'\)\.mount\(api, \{ wrap, audited \}\); \}\n"
    rb"catch \(e\) \{ logErr\('biz module not mounted:', e\.message\); \}\n\n", re.S)


def shq(s):
    return "'" + str(s).replace("'", "'\\''") + "'"


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, key_filename=KEY, timeout=25,
              look_for_keys=False, allow_agent=False)
    return c


def run(c, cmd, timeout=180):
    _, out, err = c.exec_command(cmd, timeout=timeout)
    rc = out.channel.recv_exit_status()
    return rc, out.read().decode("utf-8", "replace"), err.read().decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    c = connect()
    sftp = c.open_sftp()

    def read(p):
        try:
            return sftp.open(p, "rb").read()
        except IOError:
            return None

    index = read(posixpath.join(SRV, "index.js"))
    block = BLOCK_RE.search(index) if index else None
    has_mount_line = index is not None and b"require('./biz').mount" in index
    biz_exists = read(BIZ_JS) is not None
    key_exists = read(PUBKEY) is not None
    _, ls_data, _ = run(c, "ls -la %s 2>&1 | tail -12" % BIZ_DATA)
    hosts_raw = read(posixpath.join(BIZ_DATA, "hosts.json"))
    try:
        hosts = json.loads(hosts_raw.decode("utf-8")).get("items", []) if hosts_raw else []
    except Exception:
        hosts = []

    print("=== 现状 ===")
    print("index.js 里的 biz 挂载块 : %s（含 mount 行: %s）" % (bool(block), has_mount_line))
    print("server/biz.js            : %s" % biz_exists)
    print("验签公钥                 : %s" % key_exists)
    print("数据目录 %s : %s" % (BIZ_DATA, ls_data.strip().replace("\n", " | ")))
    print("待迁移的纳管主机         : %d 台 -> %s" % (len(hosts), ", ".join(h.get("label", "?") for h in hosts)))

    if a.dry_run:
        print("\n=== dry-run：将要执行 ===")
        print("1. cp server/index.js -> backups/index.js.before-biz-remove-%s" % stamp)
        print("2. 从 index.js 删除 biz 挂载块（%d 字节）" % (len(block.group(0)) if block else 0))
        print("3. mv server/biz.js -> backups/biz.js.removed-%s" % stamp)
        print("4. mv server/keys/license_public.pem -> backups/license_public.pem.removed-%s" % stamp)
        print("5. mv %s -> %s.removed-%s" % (BIZ_DATA, BIZ_DATA, stamp))
        print("6. 把 %d 台纳管主机写入 %s/hosts.json（转成 ops 新字段，无配额）" % (len(hosts), OPS_DATA))
        print("7. node --check server/index.js && systemctl restart cloud-monitor")
        print("8. 冒烟：/api/biz/* 应 404，/api/ops/hosts 应看到迁移后的主机")
        return 0

    if has_mount_line:
        if not block:
            print("挂载块格式与预期不同，停止自动改写（请手工删除 index.js 中的 require('./biz').mount 三行）")
            return 1
        run(c, "cp %s/server/index.js %s/index.js.before-biz-remove-%s" % (APP, BACKUP_DIR, stamp))
        patched = BLOCK_RE.sub(b"", index, count=1)
        with sftp.open(posixpath.join(SRV, "index.js"), "wb") as f:
            f.write(patched)
        print("已摘除 biz 挂载（%d -> %d 字节）" % (len(index), len(patched)))
    else:
        print("index.js 里没有 biz 挂载，跳过")

    if biz_exists:
        run(c, "mv %s %s/biz.js.removed-%s" % (BIZ_JS, BACKUP_DIR, stamp))
        print("server/biz.js -> backups/biz.js.removed-%s" % stamp)
    if key_exists:
        run(c, "mv %s %s/license_public.pem.removed-%s" % (PUBKEY, BACKUP_DIR, stamp))
        print("验签公钥 -> backups/license_public.pem.removed-%s" % stamp)

    if hosts:
        migrated = []
        for h in hosts:
            migrated.append({
                "id": "h" + re.sub(r"\W", "", str(h.get("id", "")))[:8] or ("m" + stamp[:8]),
                "label": h.get("label") or "未命名",
                "addr": h.get("addr") or "",
                "sshPort": 22, "role": "other", "env": "其它", "owner": "", "tags": [],
                "note": h.get("note") or "", "panelUrl": "",
                "addedAt": h.get("addedAt") or 0,
                "updatedAt": int(datetime.datetime.now().timestamp() * 1000),
                "addedBy": h.get("addedBy") or "",
                "lastSeenAt": 0, "lastCheckOk": False, "lastCheckError": "", "banner": "",
            })
        run(c, "mkdir -p %s" % OPS_DATA)
        tmp = posixpath.join(OPS_DATA, "hosts.json.new")
        with sftp.open(tmp, "wb") as f:
            f.write(json.dumps({"items": migrated, "migratedFrom": "biz",
                                "updatedAt": int(datetime.datetime.now().timestamp() * 1000)},
                               indent=2).encode("utf-8"))
        rc, o, e = run(c, "test -e %s/hosts.json && echo SKIP_EXISTS || mv %s %s/hosts.json"
                       % (OPS_DATA, tmp, OPS_DATA))
        print("纳管主机迁移 -> %s/hosts.json %s %s" % (OPS_DATA, o.strip(), e.strip()))

    rc, o, e = run(c, "test -d %s && mv %s %s.removed-%s && echo MOVED || echo NONE"
                   % (BIZ_DATA, BIZ_DATA, BIZ_DATA, stamp))
    print("biz 数据目录: %s %s" % (o.strip(), e.strip()))

    rc, o, e = run(c, "node --check %s/server/index.js && echo SYNTAX_OK" % APP)
    print("语法检查: %s %s" % (o.strip(), e.strip()))
    if rc != 0:
        print("语法不过，还原 index.js")
        run(c, "cp %s/index.js.before-biz-remove-%s %s/server/index.js" % (BACKUP_DIR, stamp, APP))
        return 1

    rc, o, e = run(c, "systemctl restart cloud-monitor && sleep 3 && systemctl is-active cloud-monitor")
    print("重启: %s %s" % (o.strip(), e.strip()))

    script = ("PW=%s; T=$(curl -s -X POST -H 'Content-Type: application/json' -d '{\"password\":\"'\"$PW'\"}' "
              "http://127.0.0.1:9000/api/login | python3 -c 'import sys,json;print(json.load(sys.stdin)[\"token\"])'); "
              % shq(PASSWORD))
    for label, p in [("biz/license 期望404", "/biz/license"), ("biz/usage 期望404", "/biz/usage"),
                     ("ops/hosts 期望200", "/ops/hosts"), ("ops/overview 期望200", "/ops/overview"),
                     ("ops/files/list 期望200", "/ops/files/list?dir=/etc")]:
        script += "printf %s; curl -s -o /tmp/o.json -w '%{http_code}' -H \"Authorization: Bearer $T\" " \
                  "\"http://127.0.0.1:9000/api%s\"; echo \" $(head -c 130 /tmp/o.json)\"; " % (shq(label.ljust(26)), p)
    rc, o, e = run(c, script)
    print("\n=== 冒烟（面板本机 127.0.0.1:9000）===")
    print(o)
    if e.strip():
        print("stderr:", e.strip())
    sftp.close()
    c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
