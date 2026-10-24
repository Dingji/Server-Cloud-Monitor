#!/usr/bin/env python
"""Drive the ops endpoints through the public panel URL and print a readable report.

  python tools/ops_smoke.py            # 现状总览 + 建/删一个自检拨测
  python tools/ops_smoke.py verbose    # 附带原始 JSON
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("SM_PANEL_URL", "http://8.153.153.176/panel").rstrip("/")
API = BASE + "/api"
PASSWORD = os.environ.get("SM_PANEL_PW", "Ladridibiciclette!")
TOKEN = None
VERBOSE = "verbose" in sys.argv


def http(method, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw or "{}")
        except Exception:
            return e.code, {"error": raw[:400]}
    except Exception as e:
        return 0, {"error": repr(e)}


def line(label, st, j, keys=None):
    if keys:
        picked = {k: j.get(k) for k in keys if isinstance(j, dict)}
        print("%-26s HTTP %s  %s" % (label, st, json.dumps(picked, ensure_ascii=False)[:600]))
    else:
        print("%-26s HTTP %s  %s" % (label, st, json.dumps(j, ensure_ascii=False)[:600]))
    if VERBOSE:
        print(" " * 30 + json.dumps(j, ensure_ascii=False)[:2000])


def verify_engine():
    """Prove the rule engine really evaluates on the server and that firing alerts
    reach the panel's own alert buffer (bell + WebSocket), not only the notify log."""
    global TOKEN
    st, j = http("POST", "/login", {"password": PASSWORD})
    if st != 200:
        print("登录失败", st, j)
        return 1
    TOKEN = j["token"]

    st, rule = http("POST", "/ops/alerts/rule/save", {
        "name": "自检-必然触发", "metric": "cpuPct", "op": ">=", "threshold": 0,
        "forSec": 0, "level": "warn", "cooldownSec": 60, "channelIds": [],
    })
    rid = (rule.get("rule") or {}).get("id")
    print("建规则 %s -> HTTP %s" % (rid, st))
    print("等待 20 秒让引擎跑一轮（默认 15s 周期）…")
    __import__("time").sleep(20)

    st, rules = http("GET", "/ops/alerts/rules")
    mine = next((x for x in rules.get("items", []) if x.get("id") == rid), {})
    print("规则状态=%s 当前采样值=%s" % (mine.get("state"), mine.get("currentValue")))
    eng = rules.get("engine") or {}
    print("引擎 lastEval=%s" % eng.get("lastEval"))
    print("引擎采样=%s" % json.dumps(eng.get("values") or {}, ensure_ascii=False)[:400])

    st, alerts = http("GET", "/alerts")
    items = alerts if isinstance(alerts, list) else (alerts.get("alerts") or alerts.get("items") or [])
    hit = [a for a in items if isinstance(a, dict) and rid and a.get("key") == "rule:" + rid]
    print("面板告警缓冲命中本规则: %s  示例=%s" % (bool(hit), json.dumps(hit[:1], ensure_ascii=False)[:300]))

    st, ev = http("GET", "/ops/alerts/events?limit=5")
    print("运维事件流: %s" % json.dumps(ev.get("items", [])[:3], ensure_ascii=False)[:400])

    st, nl = http("GET", "/ops/notify/log?limit=5")
    print("通知日志（channelIds 为空应不外发）: %s" % json.dumps(nl.get("items", [])[:2], ensure_ascii=False)[:300])

    http("POST", "/ops/alerts/rule/delete", {"id": rid})
    st, after = http("GET", "/ops/alerts/rules")
    print("已删除规则，剩余 %d 条" % len(after.get("items", [])))
    return 0 if mine.get("state") == "fired" else 1


def check_removed():
    """确认许可证层已从面板摘掉、纳管主机已迁到 ops、文件管理器可用。"""
    global TOKEN
    st, j = http("POST", "/login", {"password": PASSWORD})
    if st != 200:
        print("登录失败", st, j)
        return 1
    TOKEN = j["token"]
    bad = 0
    for p in ["/biz/license", "/biz/usage", "/biz/plan", "/biz/orders"]:
        st, r = http("GET", p)
        print("%-16s HTTP %s %s" % (p, st, json.dumps(r, ensure_ascii=False)[:90]))
        if st != 404:
            bad += 1
    st, h = http("GET", "/ops/hosts")
    print("/ops/hosts     HTTP %s 主机 %d 台: %s" % (st, len(h.get("items", [])),
          json.dumps([{k: v for k, v in x.items() if k in ("label", "addr", "role", "env", "note")} for x in h.get("items", [])], ensure_ascii=False)[:300]))
    print("               stats=%s" % json.dumps(h.get("stats"), ensure_ascii=False))
    if st != 200 or not h.get("items"):
        bad += 1
    st, fl = http("GET", "/ops/files/list?dir=/etc")
    names = [e["name"] for e in fl.get("entries", [])][:8]
    print("/ops/files/list HTTP %s 目录=%s 条目=%s 用了find=%s df=%s" % (
        st, fl.get("dir"), len(fl.get("entries", [])), fl.get("usedShell"), json.dumps(fl.get("df"), ensure_ascii=False)))
    print("               前几个: %s" % ", ".join(names))
    if st != 200 or not fl.get("entries"):
        bad += 1
    st, tr = http("GET", "/ops/files/trash")
    print("/ops/files/trash HTTP %s 条目=%s 保留=%s 天" % (st, tr.get("count"), tr.get("days")))
    print("\n结论：%s" % ("许可证已摘除、主机已迁移、文件管理器可用" if bad == 0 else "有 %d 项不符合预期" % bad))
    return 0 if bad == 0 else 1


def check_files():
    """Round-trip the file manager in a scratch dir: mkdir/write/preview/rename/
    move/copy/search/tree/usage/chmod/archive+extract/trash/restore/purge."""
    global TOKEN
    st, j = http("POST", "/login", {"password": PASSWORD})
    if st != 200:
        print("登录失败", st, j)
        return 1
    TOKEN = j["token"]
    base = "/tmp/cm-ops-selftest"
    bad = 0

    def step(label, method, path, body=None, expect=(200,), key=None):
        nonlocal bad
        st, r = http(method, path, body)
        flag = "OK " if st in expect else "BAD"
        detail = ""
        if key and isinstance(r, dict):
            v = r
            for part in key.split("."):
                v = v.get(part) if isinstance(v, dict) else (v[0].get(part) if isinstance(v, list) and v else None)
            detail = json.dumps(v, ensure_ascii=False)[:200] if v is not None else "（空）"
        else:
            detail = json.dumps(r, ensure_ascii=False)[:200]
        print("%s %-22s HTTP %s  %s" % (flag, label, st, detail))
        if st not in expect:
            bad += 1
        return r

    print("=== 文件管理器往返（只在 %s 内操作）===" % base)
    step("清理旧目录", "POST", "/ops/files/delete", {"paths": [base], "purge": True}, expect=(200, 404))
    step("新建目录", "POST", "/ops/files/mkdir", {"dir": "/tmp", "name": "cm-ops-selftest"})
    step("子目录", "POST", "/ops/files/mkdir", {"dir": base, "name": "sub"})
    step("写文件", "POST", "/ops/files/write", {"path": base + "/app.conf", "content": "listen = 9000\nowner = ops\n"})
    step("写第二份", "POST", "/ops/files/write", {"path": base + "/sub/notes.md", "content": "# 自检\n第二行\n"})
    r = step("预览", "GET", "/ops/files/preview?path=%s/app.conf" % base, key="text")
    if "listen" not in json.dumps(r, ensure_ascii=False):
        bad += 1
    r = step("浏览目录", "GET", "/ops/files/list?dir=%s" % base, key="entries")
    names = [e["name"] for e in r.get("entries", [])]
    print("    条目=%s usedShell=%s df=%s" % (names, r.get("usedShell"), json.dumps(r.get("df"), ensure_ascii=False)))
    if "app.conf" not in names or "sub" not in names:
        print("BAD 目录条目不完整"); bad += 1
    step("二进制拒绝编辑", "GET", "/ops/files/preview?path=/usr/bin/env", key="binary")
    step("重命名", "POST", "/ops/files/rename", {"path": base + "/app.conf", "name": "app.conf.bak"})
    step("移动", "POST", "/ops/files/move", {"paths": [base + "/app.conf.bak"], "to": base + "/sub"})
    step("复制", "POST", "/ops/files/copy", {"paths": [base + "/sub/notes.md"], "to": base, "name": "notes-copy.md"})
    step("chmod", "POST", "/ops/files/chmod", {"paths": [base + "/notes-copy.md"], "mode": "640"})
    step("非法权限串被拒", "POST", "/ops/files/chmod", {"paths": [base], "mode": "999"}, expect=(400,))
    step("越界路径被拒", "POST", "/ops/files/mkdir", {"dir": base, "name": "a/b"}, expect=(400,))
    step("根目录保护", "POST", "/ops/files/delete", {"paths": ["/"]}, expect=(403,))
    r = step("搜索", "GET", "/ops/files/search?dir=%s&q=notes&depth=3" % base, key="items")
    print("    命中=%d" % len(r.get("items", [])))
    if not r.get("items"):
        print("BAD 搜索无结果"); bad += 1
    step("目录树", "GET", "/ops/files/tree?dir=%s&depth=2" % base, key="nodes")
    step("占用分析", "GET", "/ops/files/usage?dir=%s" % base, key="items")
    def raw_get(path):
        req = urllib.request.Request(API + path, method="GET")
        req.add_header("Authorization", "Bearer " + TOKEN)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except Exception as e:
            return 0, repr(e).encode("utf-8")

    st, blob = raw_get("/ops/files/download?path=%s/notes-copy.md" % base)
    okd = st == 200 and len(blob) == 19 and "自检".encode("utf-8") in blob
    print("%s %-22s HTTP %s  %d 字节 内容=%r" % ("OK " if okd else "BAD", "下载（按字节）", st, len(blob), blob[:60]))
    if not okd:
        bad += 1
    r = step("打包 tar.gz", "POST", "/ops/files/archive", {"paths": [base + "/sub"], "name": "pack.tar.gz", "to": base})
    if r.get("ok"):
        step("解压", "POST", "/ops/files/extract", {"path": base + "/pack.tar.gz", "to": base + "/sub"})
    step("删除进回收站", "POST", "/ops/files/delete", {"paths": [base + "/notes-copy.md"]})
    r = step("回收站列表", "GET", "/ops/files/trash", key="items")
    tid = None
    for it in r.get("items", []):
        if it.get("name") == "notes-copy.md":
            tid = it["id"]
    if not tid:
        print("BAD 回收站里找不到刚删除的文件"); bad += 1
    else:
        step("还原", "POST", "/ops/files/restore", {"ids": [tid]})
        step("彻底删除", "POST", "/ops/files/delete", {"paths": [base + "/notes-copy.md"], "purge": True})
    step("上传（raw）", "POST", "/ops/files/upload?dir=%s&name=up.txt" % base, None, expect=(200, 400, 415))
    step("收尾清理", "POST", "/ops/files/delete", {"paths": [base], "purge": True})
    st, after = http("GET", "/ops/files/list?dir=%s" % base)
    print("%s 清理后应 404/400，实际 %s" % ("OK " if st in (400, 404) else "BAD", st))
    if st not in (400, 404):
        bad += 1
    print("\n结论：%s" % ("文件管理器 24 步全部符合预期" if bad == 0 else "有 %d 步不符合预期" % bad))
    return 0 if bad == 0 else 1


def check_newops():
    """Live check of 巡检 / 磁盘治理 / 看护 / 远程采集 — read-only: no fixes, no cleaners."""
    global TOKEN
    st, j = http("POST", "/login", {"password": PASSWORD})
    if st != 200:
        print("登录失败", st, j)
        return 1
    TOKEN = j["token"]
    bad = 0

    print("=== 安全基线巡检（只读扫描，不执行修复）===")
    st, rep = http("POST", "/ops/sec/run", {})
    checks = rep.get("checks", [])
    print("POST /ops/sec/run  HTTP %s  检查项=%d 得分=%s 等级=%s bad=%s warn=%s" % (
        st, len(checks), rep.get("score"), rep.get("grade"),
        (rep.get("counts") or {}).get("bad"), (rep.get("counts") or {}).get("warn")))
    for c in checks:
        if c.get("level") in ("bad", "warn"):
            print("   %-14s %s  %s%s" % (LEVEL_TAG.get(c["level"], c["level"]), c["label"], c["detail"][:90],
                                          "   [可修复:%s]" % c["fix"] if c.get("fix") else ""))
    if st != 200 or len(checks) < 20:
        print("BAD 巡检项过少"); bad += 1
    st, cats = http("GET", "/ops/sec/checks")
    print("GET /ops/sec/checks HTTP %s  分类=%d 修复项=%d runner=%s" % (st, len(cats.get("categories", [])), len(cats.get("fixes", [])), cats.get("runner")))
    st, tr = http("GET", "/ops/sec/trend")
    print("GET /ops/sec/trend  HTTP %s  历史=%d 修复日志=%d" % (st, len(tr.get("items", [])), len(tr.get("fixLog", []))))

    print("\n=== 磁盘治理（只读）===")
    st, ov = http("GET", "/ops/disk/overview")
    for m in ov.get("mounts", []):
        print("   %-12s 总 %s 已用 %s%% inode %s%%" % (m["mount"], fmt_kb(m["totalKb"]), m["usedPct"], m.get("inodeUsedPct")))
    print("   journal=%s apt=%s /var/log=%s 回收站=%s KB" % (
        ov.get("journalKb"), ov.get("aptCacheKb"), ov.get("varLogKb"), ov.get("trashKb")))
    if st != 200 or not ov.get("mounts"):
        print("BAD 容量总览为空"); bad += 1
    st, bf = http("GET", "/ops/disk/bigfiles?minMb=200&limit=8&path=/")
    print("GET bigfiles  HTTP %s  %d 项，最大：" % (st, len(bf.get("items", []))))
    for x in bf.get("items", [])[:4]:
        print("   %10s  %s" % (fmt_bytes(x["size"]), x["path"]))
    st, td = http("GET", "/ops/disk/topdirs?path=/&depth=1&limit=6")
    print("GET topdirs   HTTP %s  %s" % (st, " ".join("%s=%s" % (x["path"], fmt_kb(x["kb"])) for x in td.get("items", [])[:5])))
    st, lg = http("GET", "/ops/disk/logs?path=/var/log")
    print("GET logs      HTTP %s  最大=%s 轮转=%d 可回收≈%s KB" % (
        st, (lg.get("items") or [{}])[0].get("path"), lg.get("rotatedCount", 0), lg.get("reclaimableKb")))
    st, rej = http("POST", "/ops/disk/truncate", {"path": "/etc/passwd"})
    print("越权清空被拒  HTTP %s %s" % (st, json.dumps(rej, ensure_ascii=False)[:80]))
    if st != 403:
        print("BAD 清空守卫失效"); bad += 1
    st, sc = http("GET", "/ops/disk/schedule")
    print("GET schedule  HTTP %s  清理器=%d 已启用=%s" % (st, len(sc.get("cleaners", [])), (sc.get("config") or {}).get("enabled")))

    print("\n=== 服务看护（restart=false，只观测）===")
    st, w = http("POST", "/ops/watchdog/save", {"name": "自检-auditd", "kind": "systemd", "unit": "auditd.service", "restart": False, "intervalSec": 30})
    wid = (w.get("entry") or {}).get("id")
    st, run1 = http("POST", "/ops/watchdog/run", {"id": wid})
    r1 = (run1.get("result") or {})
    print("systemd 探活  状态=%s ok=%s 详情=%s" % ((run1.get("entry") or {}).get("status"), r1.get("ok"), (r1.get("state") or {}).get("lastStatus")))
    st, w2 = http("POST", "/ops/watchdog/save", {"name": "自检-死端口", "kind": "port", "host": "127.0.0.1", "port": 59999, "restart": False, "intervalSec": 30})
    pid = (w2.get("entry") or {}).get("id")
    st, run2 = http("POST", "/ops/watchdog/run", {"id": pid})
    r2 = (run2.get("result") or {})
    print("端口探活      状态=%s ok=%s 动作=%s" % ((run2.get("entry") or {}).get("status"), r2.get("ok"), r2.get("action")))
    if r2.get("action") not in (None, "skip-restart"):
        print("BAD 关闭自愈却执行了动作"); bad += 1
    st, wl = http("GET", "/ops/watchdog")
    print("GET watchdog  HTTP %s 统计=%s" % (st, json.dumps(wl.get("stats"), ensure_ascii=False)))
    for i in (wid, pid):
        http("POST", "/ops/watchdog/delete", {"id": i})
    st, wl2 = http("GET", "/ops/watchdog")
    print("清理后剩余    %d 项" % len(wl2.get("items", [])))

    print("\n=== 远程采集 ===")
    st, rm = http("GET", "/ops/remote")
    print("GET /ops/remote HTTP %s 主机=%d runner=%s 待采集=%s 定时器=%s" % (
        st, len(rm.get("items", [])), rm.get("runner"), rm.get("due"), rm.get("scheduled")))
    for x in rm.get("items", []):
        print("   %-10s %-14s 凭据=%s 最近采集=%s" % (x["label"], x["addr"], "已配" if x.get("cred") else "未配",
              fmt_ts((x.get("last") or {}).get("at", 0))))
    st, nocred = http("POST", "/ops/remote/collect", {"id": rm["items"][0]["id"] if rm.get("items") else "x"})
    print("未配凭据时采集 HTTP %s %s" % (st, json.dumps(nocred, ensure_ascii=False)[:160]))
    st, bad_cred = http("POST", "/ops/remote/cred", {"id": "nonexistent", "user": "root"})
    print("未知主机存凭据 HTTP %s（应 404）" % st)
    if st != 404:
        bad += 1
    st, hist = http("GET", "/ops/remote/history?hours=6")
    print("采集历史      HTTP %s 条=%d" % (st, len(hist.get("items", []))))

    st, o = http("GET", "/ops/overview")
    print("\n总览: 拨测=%s 规则=%s 渠道=%s 主机=%s 看护=%s 巡检=%s" % (
        json.dumps(o.get("uptime"), ensure_ascii=False)[:60],
        json.dumps(o.get("rules"), ensure_ascii=False)[:60],
        json.dumps(o.get("channels"), ensure_ascii=False)[:50],
        json.dumps(o.get("hosts"), ensure_ascii=False)[:60],
        json.dumps(o.get("watchdog"), ensure_ascii=False)[:60],
        json.dumps(o.get("secops"), ensure_ascii=False)[:60]))
    print("\n结论：%s" % ("四个新模块在生产面板全部按预期工作（只读）" if bad == 0 else "%d 项不符合预期" % bad))
    return 0 if bad == 0 else 1


LEVEL_TAG = {"bad": "🔴风险", "warn": "🟠建议", "ok": "🟢合规", "info": "⚪未覆盖"}


def fmt_kb(kb):
    if kb in (None, 0):
        return "—"
    return fmt_bytes(int(kb) * 1024)


def fmt_bytes(n):
    n = float(n or 0)
    for u in ["B", "KB", "MB", "GB", "TB"]:
        if n < 1024 or u == "TB":
            return ("%.1f%s" % (n, u)) if n >= 1 else ("%.0fB" % n)
        n /= 1024.0
    return "%d" % n


def fmt_ts(ms):
    import datetime
    return datetime.datetime.utcfromtimestamp(ms / 1000.0).strftime("%m-%d %H:%M") if ms else "—"


def main():
    if "removed" in sys.argv:
        return check_removed()
    if "files" in sys.argv:
        return check_files()
    if "newops" in sys.argv:
        return check_newops()
    if "live" in sys.argv:
        return verify_engine()
    global TOKEN
    st, j = http("POST", "/login", {"password": PASSWORD})
    if st != 200:
        print("登录失败", st, j)
        return 1
    TOKEN = j["token"]

    line("GET /ops/overview", *http("GET", "/ops/overview"), keys=["ok", "uptime", "rules", "channels"])
    st, o = http("GET", "/ops/overview")
    if isinstance(o.get("dataDir"), str):
        print("%-26s %s" % ("  数据目录", o["dataDir"]))

    line("GET /ops/alerts/channels", *http("GET", "/ops/alerts/channels"), keys=["ok"])
    line("GET /ops/alerts/rules", *http("GET", "/ops/alerts/rules"), keys=["ok", "engine"])
    st, r = http("GET", "/ops/alerts/rules")
    print("%-26s %s" % ("  可选指标数", len(r.get("metrics", []))))
    print("%-26s %s" % ("  引擎上次评估", r.get("engine", {}).get("lastEval")))
    vals = (r.get("engine") or {}).get("values") or {}
    print("%-26s %s" % ("  当前采样", json.dumps({k: vals[k] for k in list(vals)[:8]}, ensure_ascii=False)))

    name = "自检-面板健康接口"
    st, created = http("POST", "/ops/uptime/task/save", {
        "name": name, "type": "http", "url": BASE + "/api/health",
        "expectStatus": "200", "keyword": "cloud-monitor", "intervalSec": 60, "failThreshold": 2,
    })
    line("POST /ops/uptime/task/save", st, created, keys=["ok"])
    tid = (created.get("task") or {}).get("id")
    if tid:
        st, runres = http("POST", "/ops/uptime/task/run", {"id": tid})
        t = runres.get("task") or {}
        print("%-26s HTTP %s  状态=%s  lastOk=%s  码=%s  耗时=%sms  错误=%s" % (
            "POST /ops/uptime/task/run", st, t.get("state"), t.get("lastOk"),
            t.get("lastCode"), t.get("lastMs"), t.get("lastError")))

        st, sla = http("GET", "/ops/uptime/sla?days=7")
        hit = [x for x in sla.get("tasks", []) if x.get("id") == tid]
        print("%-26s HTTP %s  %s" % ("GET /ops/uptime/sla", st, json.dumps(hit, ensure_ascii=False)[:500]))

        st, hist = http("GET", "/ops/uptime/history?id=%s&limit=5" % tid)
        print("%-26s HTTP %s  条目=%d" % ("GET /ops/uptime/history", st, len(hist.get("items", []))))

        # a rule that cannot fire on this box, purely to prove rule CRUD + evaluation wiring
        st, rule = http("POST", "/ops/alerts/rule/save", {
            "name": "自检-CPU 100%", "metric": "cpuPct", "op": ">=", "threshold": 100,
            "forSec": 60, "level": "warn", "cooldownSec": 600, "channelIds": [],
        })
        rid = (rule.get("rule") or {}).get("id")
        print("%-26s HTTP %s  rule=%s" % ("POST /ops/alerts/rule/save", st, rid))
        st, rules = http("GET", "/ops/alerts/rules")
        mine = [x for x in rules.get("items", []) if x.get("id") == rid]
        print("%-26s %s" % ("  规则状态", json.dumps(mine, ensure_ascii=False)[:400]))
        if rid:
            http("POST", "/ops/alerts/rule/delete", {"id": rid})
        st, sil = http("POST", "/ops/alerts/silence/save", {
            "name": "自检-维护窗口", "kind": "once", "scope": "*",
            "startsAt": int(__import__("time").time() * 1000), "endsAt": int(__import__("time").time() * 1000) + 60000,
            "reason": "部署自检", "enabled": False,
        })
        sid = (sil.get("silence") or {}).get("id")
        print("%-26s HTTP %s  silence=%s" % ("POST /ops/alerts/silence/save", st, sid))
        if sid:
            http("POST", "/ops/alerts/silence/delete", {"id": sid})

        st, delres = http("POST", "/ops/uptime/task/delete", {"id": tid})
        print("%-26s HTTP %s  %s" % ("POST /ops/uptime/task/delete", st, json.dumps(delres, ensure_ascii=False)[:200]))

    st, fin = http("GET", "/ops/uptime/tasks")
    print("%-26s %s" % ("  收尾任务数", len(fin.get("items", []))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
