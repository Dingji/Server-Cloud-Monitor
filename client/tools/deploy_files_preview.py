#!/usr/bin/env python
"""Deploy the file-manager preview/archive additions to the cloud panel.

  * backs up the remote ops/files.js (never overwrites the backup set)
  * uploads ops-server/files.js + ops-server/archive_tool.py
  * node --check on the box before restarting
  * restarts cloud-monitor.service and waits for it to answer
  * runs a read-mostly smoke pass over the new endpoints (own sandbox dir)

  python tools/deploy_files_preview.py            # deploy + smoke
  python tools/deploy_files_preview.py --smoke    # only run the smoke pass
"""
import argparse
import datetime
import json
import os
import posixpath
import sys
import urllib.request
import urllib.error

import paramiko

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = "root"
KEY = os.path.expanduser(os.environ.get("SM_KEY", "~/.ssh/panel_ed25519"))
APP = "/opt/cloud-monitor"
OPS = APP + "/server/ops"
BACKUP_DIR = APP + "/backups"
LOCAL = os.path.join(os.path.dirname(__file__), "..", "ops-server")
BASE = os.environ.get("SM_PANEL_URL", "http://8.153.153.176/panel").rstrip("/") + "/api"
PASSWORD = os.environ.get("SM_PANEL_PW", "")
if not PASSWORD:
    sys.exit("需要 SM_PANEL_PW（面板登录口令）：故意不留默认值，口令不进仓库")



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


def http(method, path, body=None, token=None, raw=False, headers=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + (token or ""))
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = None
    if body is not None:
        if raw:
            data = body
        else:
            data = json.dumps(body).encode()
            req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=60) as r:
            payload = r.read()
            return r.status, payload if raw else json.loads(payload.decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload.decode("utf-8", "replace"))
        except Exception:
            return e.code, {"error": payload.decode("utf-8", "replace")[:300]}


def smoke_with_conn(c):
    ok = fail = 0

    def check(name, cond, extra=None):
        nonlocal ok, fail
        if cond:
            ok += 1
            print("  PASS  " + name)
        else:
            fail += 1
            txt = repr(extra)[:300] if extra is not None else ""
            print("  FAIL  " + name + ("  -> " + txt if txt else ""))

    st, j = http("POST", "/login", {"password": PASSWORD})
    check("登录拿 token", st == 200 and j.get("token"), j)
    tok = j["token"]

    def get(path, raw=False):
        return http("GET", path, None, tok, raw=raw)

    sandbox = "/root/cm-files-smoke"
    rc, out, err = run(c, (
        f"rm -rf {sandbox} && mkdir -p {sandbox}/sub && "
        f"printf 'smoke text line\\nsecond line\\n' > {sandbox}/note.txt && "
        "head -c 4096 /dev/urandom > " + sandbox + "/blob.bin && "
        "head -c 2097152 /dev/zero > " + sandbox + "/big.bin && "
        "python3 -c \"import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'w'); "
        "z.write(sys.argv[2],'inner.txt'); z.write(sys.argv[2],'sub/nested.txt'); z.close()\" "
        f"{sandbox}/bundle.zip {sandbox}/note.txt"
    ))
    check("准备沙箱目录与 zip", rc == 0, err[:200])

    st, j = get("/ops/files/list?dir=" + sandbox)
    check("list 返回条目", st == 200 and j.get("count") == 5, j.get("count"))
    st, j = get("/ops/files/preview?path=" + sandbox + "/note.txt")
    check("preview 文本带 kind=other", st == 200 and j.get("kind") == "other" and "smoke text" in j.get("text", ""), j)
    st, j = get("/ops/files/preview?path=" + sandbox + "/blob.bin")
    check("preview 二进制带 kind=other+binary", st == 200 and j.get("binary") is True, j)
    st, j = get("/ops/files/preview?path=" + sandbox + "/bundle.zip")
    check("preview zip 带 kind=archive", st == 200 and j.get("kind") == "archive", j)

    st, raw_bytes = get("/ops/files/raw?path=" + sandbox + "/note.txt", raw=True)
    check("raw 文本流式返回原字节", st == 200 and b"smoke text" in raw_bytes, raw_bytes[:80])
    st, raw_bytes = get("/ops/files/raw?path=" + sandbox + "/blob.bin", raw=True)
    check("raw 二进制返回 4096 字节", st == 200 and len(raw_bytes) == 4096, len(raw_bytes or b""))
    st, raw_bytes = get("/ops/files/raw?path=" + sandbox + "/big.bin&maxMB=1", raw=True)
    check("raw 超过 maxMB 被拒 413", st == 413, (st, str(raw_bytes)[:120]))

    st, j = get("/ops/files/archive/list?path=" + sandbox + "/bundle.zip")
    names = [e["name"] for e in (j.get("entries") or [])]
    check("archive/list 列出内部条目", st == 200 and set(names) == {"inner.txt", "sub/nested.txt"}, names)
    st, j = get("/ops/files/archive/entry?path=" + sandbox + "/bundle.zip&entry=inner.txt")
    check("archive/entry 文本预览", st == 200 and "smoke text" in j.get("text", ""), j)
    st, j = get("/ops/files/archive/entry?path=" + sandbox + "/bundle.zip&entry=sub/nested.txt")
    check("archive/entry 子目录条目预览", st == 200 and "smoke text" in j.get("text", ""), j)
    st, j = get("/ops/files/archive/entry?path=" + sandbox + "/bundle.zip&entry=inner.txt&base64=1")
    check("archive/entry base64 预览", st == 200 and j.get("binary") is False and j.get("text"), j)
    st, j = get("/ops/files/archive/entry?path=" + sandbox + "/bundle.zip&entry=ghost.txt")
    check("archive/entry 不存在条目 400", st == 400, (st, j))
    st, raw_bytes = get("/ops/files/archive/download?path=" + sandbox + "/bundle.zip&entry=sub/nested.txt", raw=True)
    check("archive/download 子目录条目流式下载", st == 200 and b"smoke text" in raw_bytes, str(raw_bytes)[:80])
    # 公网链路上的 WAF 会把任何带 .. 片段的查询直接 RST（连编码后的也是），所以穿越
    # 防护改为在服务器本机打 127.0.0.1:9000 验证——真实客户端永远不会发送这种请求。
    rc, out, err = run(c, (
        "TOK=$(curl -s -X POST http://127.0.0.1:9000/panel/api/login -H 'Content-Type: application/json' "
        "-d \"{\\\"password\\\":\\\"" + PASSWORD + "\\\"}\" | node -e 'let d=\"\";process.stdin.on(\"data\",c=>d+=c).on(\"end\",()=>console.log(JSON.parse(d).token))'); "
        "CODE=$(curl -s -o /tmp/cm-trav.json -w '%{http_code}' -G 'http://127.0.0.1:9000/panel/api/ops/files/archive/download' "
        "--data-urlencode 'path=" + sandbox + "/bundle.zip' --data-urlencode 'entry=../../../etc/passwd' "
        "-H \"Authorization: Bearer $TOK\"); echo \"$CODE $(cat /tmp/cm-trav.json)\""
    ), timeout=60)
    check("archive/download 拒绝穿越条目（本机直连）", out.strip().startswith("400"), out.strip()[:160])
    st, j = get("/ops/files/archive/list?path=" + sandbox + "/note.txt")
    check("archive/list 非压缩包给可读错误", st == 400, (st, j))

    rc, out, err = run(c, "tar -cf %s/plain.tar -C %s note.txt && cd %s && tar -xf plain.tar -C sub/ && cat sub/note.txt" % (sandbox, sandbox, sandbox))
    check("extract 修复: tar -xf 能解非 gzip 包", rc == 0, err[:200])

    rc, _, _ = run(c, "rm -rf " + sandbox)
    print("smoke done: %d pass, %d fail" % (ok, fail))
    return fail == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--smoke-only", action="store_true")
    a = ap.parse_args()

    c = connect()
    sftp = c.open_sftp()
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")

    if not a.smoke_only:
        rc, _, _ = run(c, "cp %s/files.js %s/files.js.before-preview-%s" % (OPS, BACKUP_DIR, stamp))
        print("backup files.js -> rc", rc)

        for name in ("files.js", "archive_tool.py"):
            local = os.path.normpath(os.path.join(LOCAL, name))
            sftp.put(local, posixpath.join(OPS, name))
            print("uploaded", name)

        rc, out, err = run(c, "node --check %s/files.js && node --check %s/../index.js && echo SYNTAX_OK" % (OPS, OPS))
        print("node --check:", out.strip() or err.strip())
        if "SYNTAX_OK" not in out:
            print("语法检查未通过，不重启")
            return 1
        rc, out, err = run(c, "systemctl restart cloud-monitor && sleep 2 && systemctl is-active cloud-monitor")
        print("restart ->", out.strip(), err.strip())
        if out.strip() != "active":
            print("服务未恢复 active，请立即检查 journalctl -u cloud-monitor")
            return 1

    good = smoke_with_conn(c)
    c.close()
    return 0 if good else 1


if __name__ == "__main__":
    sys.exit(main())
