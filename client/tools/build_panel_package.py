#!/usr/bin/env python
"""Build the portable panel package by pulling the server-side app from the live box.

Read-only against the source server: it tars a whitelist of files (panel code + web
shell + manifests) plus node_modules, streams them locally into
deploy/cloud-monitor-panel/, and verifies every pulled file against the remote md5.

Secrets and box-specific state are never pulled:
  config.json (password hash + ssh/db passwords), keys/, backups/, logs/, tmp/,
  runtime/, /var/lib/cloud-panel.

  python tools/build_panel_package.py            # pull + assemble
  python tools/build_panel_package.py --no-vendor  # skip node_modules (slow link)
"""
import hashlib
import io
import os
import posixpath
import shutil
import sys
import tarfile

import paramiko

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
PKG = os.path.join(REPO, "deploy", "cloud-monitor-panel")
APP = os.path.join(PKG, "app")
VENDOR = os.path.join(PKG, "vendor")

HOST = os.environ.get("SM_HOST", "8.153.153.176")
PORT = int(os.environ.get("SM_PORT", "22"))
USER = os.environ.get("SM_USER", "root")
PASS = os.environ["SM_PASS"]
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))

REMOTE_ROOT = "/opt/cloud-monitor"
# app payload: code + web shell + manifests. Everything else is box state.
APP_ITEMS = ["server", "public", "package.json", "package-lock.json"]
APP_EXCLUDES = [
    "server/keys",          # empty since the licensing layer was removed
    "server/page_test.js",  # dev-only contract runner, hardcodes /opt/cloud-monitor
]


def connect():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    if os.path.exists(KEY):
        try:
            c.connect(HOST, port=PORT, username=USER, key_filename=KEY, timeout=25,
                      banner_timeout=25, auth_timeout=25, look_for_keys=False, allow_agent=False)
            return c
        except paramiko.SSHException:
            pass
    c.connect(HOST, port=PORT, username=USER, password=PASS, timeout=25,
              banner_timeout=25, auth_timeout=25, look_for_keys=False, allow_agent=False)
    return c


def run(client, cmd, timeout=600):
    _, out, err = client.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace")
    e = err.read().decode("utf-8", "replace")
    return out.channel.recv_exit_status(), o, e


def stream_tar(client, remote_cmd, local_path):
    """Run a tar command remotely and write its stdout to a local file."""
    chan = client.get_transport().open_channel("session")
    chan.exec_command(remote_cmd)
    buf = io.BytesIO()
    err = bytearray()
    while True:
        chunk = chan.recv(1 << 20)
        if not chunk:
            break
        buf.write(chunk)
        while chan.recv_stderr_ready():
            err.extend(chan.recv_stderr(1 << 16))
    code = chan.exit_status_ready() and chan.recv_exit_status()
    chan.close()
    if code:
        raise SystemExit(f"remote tar failed ({remote_cmd}): {bytes(err).decode('utf-8', 'replace')}")
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    with open(local_path, "wb") as f:
        f.write(buf.getvalue())
    return len(buf.getvalue())


def extract(tar_bytes_path, dest, strip_top=False):
    os.makedirs(dest, exist_ok=True)
    with tarfile.open(tar_bytes_path, "r:gz") as t:
        for m in t.getmembers():
            if m.issym() or m.islnk():
                continue  # node_modules has no symlinks that matter on the target either
            if not (m.isfile() or m.isdir()):
                continue
            name = m.name
            if strip_top and "/" in name:
                name = name.split("/", 1)[1]
            elif strip_top:
                continue
            if not name:
                continue
            target = os.path.normpath(os.path.join(dest, name))
            if not target.startswith(os.path.normpath(dest) + os.sep) and target != os.path.normpath(dest):
                raise SystemExit("tar entry escapes destination: " + m.name)
            if m.isdir():
                os.makedirs(target, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with t.extractfile(m) as src, open(target, "wb") as dst:
                    dst.write(src.read())


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


def main():
    skip_vendor = "--no-vendor" in sys.argv
    print(f"== connect {USER}@{HOST}:{PORT} (read-only pull) ==")
    client = connect()

    code, out, _ = run(client, f"node -v; ls {REMOTE_ROOT}")
    print(out.strip())

    # 1) app payload
    excl = " ".join(f"--exclude={e}" for e in APP_EXCLUDES)
    items = " ".join(APP_ITEMS)
    tmp_app = os.path.join(VENDOR, "_app.tar.gz")
    size = stream_tar(client, f"cd {REMOTE_ROOT} && tar -czf - {excl} {items}", tmp_app)
    print(f"app tar: {size/1024:.0f} KB")
    if os.path.isdir(APP):
        shutil.rmtree(APP)
    extract(tmp_app, APP, strip_top=False)
    os.remove(tmp_app)

    # 2) verify md5 for every pulled regular file under server/ and public/
    code, out, _ = run(client, f"cd {REMOTE_ROOT} && find {items} -type f | sort | xargs md5sum 2>/dev/null")
    remote = {}
    for line in out.splitlines():
        parts = line.split(None, 1)
        if len(parts) == 2:
            remote[parts[1].strip().lstrip("./")] = parts[0]
    checked = mismatch = 0
    bad = []
    for root, _, files in os.walk(APP):
        for fn in files:
            lp = os.path.join(root, fn)
            rel = os.path.relpath(lp, APP).replace(os.sep, "/")
            if rel.endswith("node_modules" + os.sep):
                continue
            if any(rel == e or rel.startswith(e + "/") for e in APP_EXCLUDES):
                bad.append("LEAKED EXCLUDED: " + rel)
                continue
            if rel in remote:
                checked += 1
                if md5_of(lp) != remote[rel]:
                    mismatch += 1
                    bad.append("MD5 MISMATCH: " + rel)
    print(f"md5 checked={checked} mismatch={mismatch}")
    if bad:
        raise SystemExit("refusing to build:\n" + "\n".join(bad[:20]))

    # 4) guard: no secret files / secret-bearing config inside the package
    for bad_name in ["config.json", "pty_key", "id_rsa"]:
        for root, _, files in os.walk(APP):
            if bad_name in files:
                raise SystemExit(f"secret leaked into package: {os.path.join(root, bad_name)}")
    secret_hits = []
    for root, _, files in os.walk(APP):
        for fn in files:
            if not fn.endswith((".js", ".json", ".html", ".py")):
                continue
            lp = os.path.join(root, fn)
            try:
                text = open(lp, "r", encoding="utf-8", errors="ignore").read()
            except OSError:
                continue
            if '"passwordHash": "scrypt$' in text or "PRIVATE KEY" in text:
                secret_hits.append(os.path.relpath(lp, PKG))
    if secret_hits:
        raise SystemExit("possible secrets inside package:\n" + "\n".join(secret_hits))

    # 4) node_modules (offline install), kept as a tarball the installer unpacks.
    #    ssh2 ships two OPTIONAL native addons (cpu-features + sshcrypto). They are
    #    machine-specific binaries, so they never travel; verified on the source box
    #    that ssh2 still connects and execs in pure-JS fallback without them.
    if not skip_vendor:
        nm = os.path.join(VENDOR, "node_modules.tar.gz")
        native_excl = ("--exclude=node_modules/cpu-features/build "
                       "--exclude=node_modules/ssh2/lib/protocol/crypto/build")
        size = stream_tar(client, f"cd {REMOTE_ROOT} && tar -czf - {native_excl} node_modules", nm)
        print(f"vendor/node_modules.tar.gz: {size/1024/1024:.1f} MB")
        with tarfile.open(nm, "r:gz") as t:
            names = [m.name for m in t.getmembers() if m.isreg()]
        print(f"  files={len(names)} express={any('express/index.js' in n for n in names)} "
              f"ssh2={any('ssh2/lib' in n for n in names)} ws={any(n.endswith('ws/index.js') for n in names)} "
              f"chartjs={any('chart.umd.js' in n for n in names)} xterm={any('xterm.js' in n for n in names)}")
        natives = [n for n in names if n.endswith(".node")]
        if natives:
            raise SystemExit("vendor 仍含原生模块，剔除失败: " + ", ".join(natives[:5]))
        node_v = run(client, "node -v")[1].strip()
        with open(os.path.join(VENDOR, "node_modules.README.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write(
                "node_modules 来自源机 " + HOST + "（" + node_v + "），已剔除：\n"
                "  node_modules/cpu-features/build\n"
                "  node_modules/ssh2/lib/protocol/crypto/build\n"
                "这两块是 ssh2 的可选原生加速（.node 二进制），与机器/架构绑定，不能跨机搬运。\n"
                "已在源机实测：删除后 ssh2 走纯 JS 实现，仍能完成真实 SSH 连接与命令执行。\n"
                "若目标机想要原生加速，自行执行: cd <安装目录> && npm rebuild ssh2（需要 gcc/make）。\n")

    # 5) manifest
    code, out, _ = run(client, (
        f"cd {REMOTE_ROOT} && md5sum server/*.js server/ops/*.js | sed 's#^# #'"))
    with open(os.path.join(PKG, "MANIFEST.remote-md5.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# md5 of the panel source as pulled from " + HOST + " " +
                run(client, "date -Is")[1].strip() + "\n")
        f.write(out)
    code, out, _ = run(client, "node -v")
    with open(os.path.join(PKG, "MANIFEST.env.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("source_host=" + HOST + "\n")
        f.write("source_node=" + out.strip() + "\n")
        f.write("source_root=" + REMOTE_ROOT + "\n")
    print("manifests written")
    client.close()

    n = sum(len(fs) for _, _, fs in os.walk(APP))
    print(f"== done: {APP} has {n} files ==")


if __name__ == "__main__":
    main()
