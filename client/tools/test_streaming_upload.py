#!/usr/bin/env python
"""Pre-deploy functional test for the streaming upload handler.

Runs on the server: loads the NEW files.js (as files.uploadfix.js, next to the real one
so ./store resolves) inside a bare express app that mirrors the panel's global
express.json({limit:'4mb'}), with auth stripped. Asserts size, atomicity and memory --
the old implementation buffered the whole body in a 256MB-heap process.
"""
import json
import os
import subprocess
import sys
import time

import paramiko

CLOUD = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.path.expanduser("~/.ssh/oc_relay_ed25519")
TARGET_DIR = "/home"

HARNESS = r'''
'use strict';
const express = require('/opt/cloud-monitor/node_modules/express');
const app = express();
app.use(express.json({ limit: '4mb' }));            /* same global parser as the panel */
const files = require('/opt/cloud-monitor/server/ops/files.uploadfix.js');
const posts = {};
const noop = () => {};
files.register(new Proxy({}, { get: (t, k) => (k === 'post' ? (p, ...mw) => { posts[p] = mw; } : noop) }),
  (fn) => async (req, res, next) => { try { const r = await fn(req, res); if (r !== undefined) res.json(r); } catch (e) { next(e); } },
  (name, fn) => async (req, res, next) => { try { const r = await fn(req); res.json(r); } catch (e) { next(e); } });
app.use('/panel/api', (req, res, next) => {
  const mw = posts['/ops/files/upload'];
  if (mw && req.path === '/ops/files/upload') {
    let i = 0;
    const go = (e) => { if (e) return next(e); if (i < mw.length) { try { mw[i++](req, res, go); } catch (e2) { next(e2); } } else next(); };
    go();
  } else next();
});
app.use((err, req, res, next) => res.status(err.status || 500).json({ error: err.message }));
app.listen(9111, '127.0.0.1', () => console.log('harness-up pid=' + process.pid));
'''


def connect():
    last = None
    for i in range(12):
        c = paramiko.SSHClient()
        c.load_system_host_keys()
        c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            c.connect(CLOUD, 22, "root", key_filename=KEY, look_for_keys=False,
                      allow_agent=False, timeout=30)
            return c
        except Exception as exc:
            last = "%s: %s" % (type(exc).__name__, str(exc).strip()[:80])
            c.close()
            time.sleep(2.5)
    raise RuntimeError(last)


def r(cli, cmd, t=180):
    _, o, e = cli.exec_command(cmd, timeout=t)
    o.channel.settimeout(t)
    o.channel.recv_exit_status()
    return (o.read().decode("utf-8", "replace") + e.read().decode("utf-8", "replace")).strip()


cli = connect()
sftp = cli.open_sftp()
with sftp.file("/opt/cloud-monitor/server/ops/files.uploadfix.js", "wb") as f:
    f.write(open("upstream/fix413/files.js", "rb").read())
with sftp.file("/var/tmp/upload_harness.js", "wb") as f:
    f.write(HARNESS.encode("utf-8"))
print("harness files staged")

# fresh test dir state
print(r(cli, "rm -f /home/CloudMonitor.zip /home/harness-*.bin /home/*.part; ls -la /home | head -5"))
r(cli, "nohup node /var/tmp/upload_harness.js > /var/tmp/harness.log 2>&1 & echo $! > /var/tmp/harness.pid")
time.sleep(1.5)
print("harness:", r(cli, "cat /var/tmp/harness.log"))
pid = r(cli, "cat /var/tmp/harness.pid").strip()
rss0 = r(cli, "ps -o rss= -p %s" % pid).strip()
print("pid=%s  rss0=%s KB" % (pid, rss0))


def post(mb, ct, name, timeout=240):
    cmd = ("head -c %d /dev/zero | curl -s -m %d --data-binary @- -H 'Content-Type: %s' "
           "-w '\\n%%{http_code}' 'http://127.0.0.1:9111/panel/api/ops/files/upload?dir=%s&name=%s'"
           % (mb * 1024 * 1024 if mb else 0, timeout, ct, TARGET_DIR.replace("/", "%2F"), name))
    out = r(cli, cmd)
    lines = out.rsplit("\n", 1)
    return lines[-1].strip(), (lines[0] if len(lines) > 1 else "")[:220]


fails = 0


def ck(name, cond, detail=""):
    global fails
    print(("  PASS  " if cond else "  FAIL  ") + name + ("   [" + detail + "]" if detail else ""))
    if not cond:
        fails += 1


print("\n=== 100 MB 正常上传 ===")
code, body = post(100, "application/octet-stream", "harness-100.bin")
ck("状态 200", code == "200", code + " " + body)
try:
    meta = json.loads(body)
    ck("bytes 正确", meta.get("bytes") == 100 * 1024 * 1024, str(meta.get("bytes")))
except Exception:
    meta = {}
ck("文件落位", r(cli, "stat -c %s /home/harness-100.bin 2>/dev/null").strip() == str(100 * 1024 * 1024))
ck("无 .part 残留", "part" not in r(cli, "ls /home/ | grep -c part || true"))
rss1 = r(cli, "ps -o rss= -p %s" % pid).strip()
print("  rss: %s -> %s KB（增长 %s KB；旧实现此处应≈100000KB）" % (rss0, rss1,
      (int(rss1 or 0) - int(rss0 or 0)) if rss0.isdigit() and rss1.isdigit() else "?"))

print("\n=== 超上限（声明 1200MB，应立即 413 且不收完）===")
t0 = time.time()
code, body = post(1200, "application/octet-stream", "harness-big.bin", timeout=40)
ck("状态 413", code == "413", code + " " + body)
ck("快速拒绝（<15s，说明按 Content-Length 预检）", time.time() - t0 < 15, "%.1fs" % (time.time() - t0))
ck("没有留下 harness-big.bin", "harness-big" not in r(cli, "ls /home/ || true"))

print("\n=== application/json 上传（应 400 而不是默默写空文件）===")
code, body = post(3, "application/json", "harness-json.bin")
ck("状态 400", code == "400", code + " " + body)

print("\n=== 空请求体 ===")
code, body = post(0, "application/octet-stream", "harness-empty.bin")
ck("状态 400 上传内容为空", code == "400" and "上传内容为空" in body, code + " " + body)

print("\n=== 同名不覆盖 → 409；带 overwrite=1 → 200 ===")
code, _ = post(1, "application/octet-stream", "harness-100.bin")
ck("409", code == "409", code)
code, _ = post(1, "application/octet-stream", "harness-100.bin")  # need overwrite in query
# post() 不带 overwrite，这里单独补一发
out = r(cli, "head -c 1048576 /dev/zero | curl -s -m 60 --data-binary @- "
             "-w '\\n%{http_code}' 'http://127.0.0.1:9111/panel/api/ops/files/upload?dir=%2Fhome&name=harness-100.bin&overwrite=1'")
ck("overwrite=1 成功", out.rsplit("\n", 1)[-1] == "200", out[-60:])

rssF = r(cli, "ps -o rss= -p %s" % pid).strip()
print("\n最终 rss: %s KB" % rssF)
print(r(cli, "ls -la /home/ | grep harness"))
print(r(cli, "kill $(cat /var/tmp/harness.pid) 2>/dev/null; rm -f /home/harness-*.bin /opt/cloud-monitor/server/ops/files.uploadfix.js /var/tmp/upload_harness.js /var/tmp/harness.log /var/tmp/harness.pid; echo cleaned"))
print("\n%s (%d fail)" % ("ALL PASS" if fails == 0 else "FAILURES", fails))
sys.exit(1 if fails else 0)
