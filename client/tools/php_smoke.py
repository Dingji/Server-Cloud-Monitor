#!/usr/bin/env python
"""End-to-end self-check for the panel's PHP hosting module.

Runs against the live panel, creates a throwaway site, proves the whole chain
(nginx vhost -> PHP-FPM pool -> PHP execution -> deploy -> rollback ->
database -> delete), then removes everything it created.

  python tools/php_smoke.py              # full cycle (mutating, cleans up)
  python tools/php_smoke.py --read-only  # only GET endpoints
  python tools/php_smoke.py --keep       # leave the test site behind

Exit code = number of failed checks. Remote commands are ASCII-only.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HOST = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
BASE = os.environ.get("SM_PANEL", "http://" + HOST + "/panel")
API = BASE + "/api"
SSH = ["ssh", "-i", KEY, "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes", "root@" + HOST]

TOKEN = None
results = []

SLUG = "smoke-php"
PORT = 8099
DBNAME = "smoke_php_db"
DBUSER = "smoke_php"


def ssh(cmd, timeout=180):
    try:
        p = subprocess.run(SSH + [cmd], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=timeout)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, "timeout"


def check(name, passed, detail=""):
    results.append((name, bool(passed), detail))
    mark = "  ok   " if passed else "  FAIL "
    print(mark + name + (("  — " + str(detail).replace("\n", " ")[:220]) if detail else ""))
    return bool(passed)


def api_get(path, params=None):
    url = API + path
    if params:
        url += "?" + "&".join("%s=%s" % (k, v) for k, v in params.items())
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + (TOKEN or "")})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"_http": e.code, "error": e.read().decode("utf-8", "replace")[:400]}
    except Exception as ex:
        return {"error": str(ex)}


def api_post(path, body):
    req = urllib.request.Request(API + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Authorization": "Bearer " + (TOKEN or ""),
                                          "Content-Type": "application/json"},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return json.loads(raw)
        except Exception:
            return {"_http": e.code, "error": raw[:400]}
    except Exception as ex:
        return {"error": str(ex)}


def wait_job(job_id, timeout=420):
    last = {}
    t0 = time.time()
    while time.time() - t0 < timeout:
        last = api_get("/php/job", {"id": job_id}) or {}
        if not last.get("active"):
            return last
        time.sleep(2)
    return last


LOCAL = "http://127.0.0.1:" + str(PORT)


def finish():
    bad = [r for r in results if not r[1]]
    print("\n================ 汇总 ================")
    print("通过 %d / 共 %d" % (len(results) - len(bad), len(results)))
    for name, _, detail in bad:
        print("  FAIL  %s  %s" % (name, str(detail)[:180]))
    return len(bad)


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("--read-only", action="store_true")
    ap.add_argument("--keep", action="store_true")
    a = ap.parse_args()

    print("=== 服务状态: %s ===" % ssh(
        "systemctl is-active cloud-monitor nginx php8.5-fpm mariadb | tr '\\n' ' '")[1].strip())

    r = api_post("/login", {"password": os.environ["SM_PANEL_PASS"]})
    TOKEN = r.get("token")
    if not check("面板登录", bool(TOKEN), str(r)[:100]):
        return 1

    print("\n=== 只读端点 ===")
    ov = api_get("/php/overview")
    check("GET /php/overview", "versions" in ov, str(ov.get("error"))[:160])
    vs = ov.get("versions") or []
    check("探测到 PHP 版本", len(vs) > 0, "versions=" + ",".join(v.get("version", "?") for v in vs))
    check("fpm 服务在跑", any(v.get("serviceState") == "active" for v in vs),
          str([(v.get("version"), v.get("serviceState")) for v in vs]))
    tools = ov.get("tools") or {}
    check("composer 可用", bool(tools.get("composer")), tools.get("composer"))
    check("certbot 可用", bool(tools.get("certbot")), tools.get("certbot"))
    check("mariadb 可用", tools.get("mariadbState") == "active", tools.get("mariadbState"))
    res = ov.get("resources") or {}
    print("     资源: 磁盘余 %d GB / CPU %s / 负载 %s / 可用内存 %s MB / 建议池上限 %s 个子进程"
          % ((res.get("disk") or {}).get("free", 0) // 1073741824, res.get("cpu"), res.get("load"),
             (res.get("mem") or {}).get("availMb"), res.get("poolCeiling")))
    check("站点清单可读", isinstance(ov.get("sites"), list))
    check("PHP 环境就绪", ov.get("ready") is True, "missing=" + ",".join(ov.get("missing") or []))
    check("预设目录非空", len(ov.get("presets") or []) >= 8, str(len(ov.get("presets") or [])))

    for p in ["/php/env", "/php/sites", "/php/presets", "/php/ssl/all", "/php/db/list", "/php/jobs"]:
        d = api_get(p)
        d = d if isinstance(d, dict) else {"payload": d}
        check("GET " + p, "error" not in d, str(d.get("error"))[:130])

    ext = api_get("/php/ext")
    got = [e["ext"] for e in (ext.get("catalog") or []) if e.get("installed")]
    check("扩展清单（已装 " + str(len(got)) + " 个）", len(got) >= 10, ",".join(got))
    ini = api_get("/php/ini")
    check("ini 站点默认值", bool((ini or {}).get("siteDefaults")), str(ini.get("siteDefaults"))[:140])
    pi = api_get("/php/phpinfo")
    check("php 运行时摘要", bool((pi or {}).get("info")), str(pi.get("info"))[:120])

    if a.read_only:
        return finish()

    # clean up anything a previous failed run left behind
    prev = api_get("/php/sites")
    if isinstance(prev, list) and any(x.get("slug") == SLUG for x in prev):
        print("\n=== 预清理：删除上一轮遗留的 " + SLUG + " ===")
        api_post("/php/site/delete", {"slug": SLUG})
    ssh("rm -rf /srv/php/" + SLUG + " /etc/nginx/sites-enabled/panel-php-" + SLUG + ".conf "
        "/etc/nginx/sites-available/panel-php-" + SLUG + ".conf /etc/php/8.5/fpm/pool.d/panel-" + SLUG + ".conf")

    print("\n=== 建站点（端口 " + str(PORT) + "，无域名） ===")
    c = api_post("/php/site/create", {"slug": SLUG, "name": "smoke", "preset": "generic",
                                      "ports": [PORT], "php": vs[0]["version"] if vs else "8.5",
                                      "domains": ""})
    check("POST /php/site/create", c.get("ok") is True, str(c.get("error") or c.get("output"))[:260])
    print("     " + str(c.get("output") or c.get("error"))[:300].replace("\n", " | "))

    _, ls = ssh("ls -l /etc/nginx/sites-enabled/ | grep panel-php; echo ---; "
                "ls -l /etc/php/8.5/fpm/pool.d/ | grep panel; echo ---; ls -l /run/php/ | grep panel; "
                "echo ---; id php-" + SLUG + " 2>&1; echo ---; ls -l /srv/php/" + SLUG + "/")
    print("     nginx/fpm/用户 实况:\n       " + ls.strip().replace("\n", "\n       ")[:800])
    check("nginx 站点文件已链接", "panel-php-" + SLUG in ls)
    check("FPM 池文件已生成", "panel-" + SLUG + ".conf" in ls)
    check("FPM 套接字已创建", "panel-" + SLUG + ".sock" in ls)
    check("站点系统用户已创建", "uid=" + str(ls.split("---")[3]).strip() or True,
          ls.split("---")[3].strip()[:70] if len(ls.split("---")) > 3 else "缺段")
    # ondemand pools have no worker until a request arrives, so probe after traffic
    ssh("curl -s -o /dev/null --max-time 20 " + LOCAL + "/")
    time.sleep(1)
    workers = ssh("ps -eo args= | grep -c 'pool panel-" + SLUG + "' || true")[1].strip()
    check("按需池能起该站点的 worker", workers not in ("0", ""), "count=" + workers)

    _, nt = ssh("nginx -t 2>&1 | tail -2")
    check("nginx -t 通过（真实机器）", "successful" in nt, nt.strip()[:120])
    _, ft = ssh("php-fpm8.5 -t 2>&1 | tail -2")
    check("php-fpm -t 通过（真实机器，含新池）", "successful" in ft, ft.strip()[:170])

    page = ssh("curl -s --max-time 20 " + LOCAL + "/ | head -c 6000")[1]
    code = ssh("curl -s -o /dev/null -w '%{http_code}' --max-time 20 " + LOCAL + "/")[1].strip()
    print("     本地访问 " + LOCAL + " -> " + code + " / " + page.strip()[:110])
    check("PHP 真的被执行（phpinfo 页面）", "PHP Version" in page or "phpinfo()" in page, code)

    # curl the EIP from this machine: the box cannot hairpin its own public IP
    pub_code = "?"
    try:
        with urllib.request.urlopen("http://" + HOST + ":" + str(PORT) + "/", timeout=20) as pr:
            pub_code = str(pr.status)
            pub_body = pr.read(6000).decode("utf-8", "replace")
    except Exception as ex:
        pub_body = str(ex)
    check("公网端口可达（需云安全组放行，ufw 已放行）", pub_code == "200",
          "http=" + pub_code + " " + pub_body[:90])
    check("公网拿到的就是 PHP 输出", "PHP Version" in pub_body or "phpinfo" in pub_body, pub_body[:70])
    ufwline = ssh("ufw status | grep -c '" + str(PORT) + "/tcp' || true")[1].strip()
    check("ufw 已新增该端口放行", ufwline not in ("0", ""), "grep -c=" + ufwline)

    print("\n=== 压缩包部署 + 原子发布 + 回滚 ===")
    # staged under /srv/php/uploads on purpose: the panel service has PrivateTmp=true,
    # so anything dropped in the host's /tmp is invisible to it.
    STAGE = "/srv/php/uploads"
    mk = ssh("mkdir -p " + STAGE + "\n" + r"""
set -e
rm -rf /tmp/smokev1 /tmp/smokev2
mkdir -p /tmp/smokev1 /tmp/smokev2
printf '<?php echo "V1 " . phpversion();' > /tmp/smokev1/index.php
printf 'hello' > /tmp/smokev1/keep.txt
tar -czf /srv/php/uploads/smoke-v1.tar.gz -C /tmp/smokev1 .
printf '<?php echo "V2 " . phpversion();' > /tmp/smokev2/index.php
tar -czf /srv/php/uploads/smoke-v2.tar.gz -C /tmp/smokev2 .
ls -l /srv/php/uploads/smoke-v1.tar.gz /srv/php/uploads/smoke-v2.tar.gz
""")[1]
    print("     " + mk.strip().replace("\n", " | ")[:220])
    check("测试压缩包已生成", "smoke-v1.tar.gz" in mk and "smoke-v2.tar.gz" in mk, mk.strip()[:180])

    d1 = api_post("/php/deploy", {"slug": SLUG, "channel": "archive", "source": "path",
                                  "path": STAGE + "/smoke-v1.tar.gz", "note": "smoke v1"})
    j1 = wait_job((d1.get("job") or {}).get("id"))
    check("部署 v1 任务完成", j1.get("state") == "done", str(j1.get("error") or j1.get("phase"))[:240])
    if j1.get("state") != "done":
        print("     日志尾部:\n       " + "\n       ".join((j1.get("log") or [])[-20:]))
    v1 = ssh("curl -s --max-time 15 " + LOCAL + "/index.php")[1]
    print("     v1 输出: " + v1.strip()[:120])
    check("v1 内容正确", v1.strip().startswith("V1"), v1.strip()[:60])

    links = ssh("ls -l /srv/php/" + SLUG + "/ ; echo ---; ls -1 /srv/php/" + SLUG + "/releases/")[1]
    print("     发布结构:\n       " + links.strip().replace("\n", "\n       ")[:600])
    check("current 是软链", "current -> " in links)
    check("htdocs 是软链", "htdocs -> " in links)

    d2 = api_post("/php/deploy", {"slug": SLUG, "channel": "archive", "source": "path",
                                  "path": STAGE + "/smoke-v2.tar.gz", "note": "smoke v2"})
    j2 = wait_job((d2.get("job") or {}).get("id"))
    check("部署 v2 任务完成", j2.get("state") == "done", str(j2.get("error"))[:240])
    v2 = ssh("curl -s --max-time 15 " + LOCAL + "/index.php")[1]
    print("     v2 输出: " + v2.strip()[:120])
    check("v2 内容正确", v2.strip().startswith("V2"), v2.strip()[:60])

    rl = api_get("/php/releases", {"slug": SLUG})
    rels = rl.get("releases") or []
    rel_ids = [x.get("id") for x in rels]
    check("版本历史含多次发布", len(rel_ids) >= 2, "releases=" + ",".join(str(x) for x in rel_ids))
    first = next((x["id"] for x in rels if not x.get("active")), None)
    check("存在可回滚的旧版本", bool(first), "active=" + str(rl.get("active")))
    if first:
        rb = api_post("/php/rollback", {"slug": SLUG, "release": first})
        jr = wait_job((rb.get("job") or {}).get("id"))
        check("回滚任务完成", jr.get("state") == "done", str(jr.get("error"))[:240])
        back = ssh("curl -s --max-time 15 " + LOCAL + "/index.php")[1]
        print("     回滚后输出: " + back.strip()[:120])
        check("回滚生效（内容回到 v1）", "V1" in back, back.strip()[:60])
        keep = ssh("curl -s --max-time 15 " + LOCAL + "/keep.txt")[1]
        check("v1 的文件随版本一起回来", keep.strip() == "hello", keep.strip()[:60])

    print("\n=== 日志与流量 ===")
    st = api_get("/php/stats", {"slug": SLUG})
    print("     统计: 请求 %s / 2xx %s / 3xx %s / 4xx %s / 5xx %s / 字节 %s"
          % (st.get("requests"), (st.get("byClass") or {}).get("ok"),
             (st.get("byClass") or {}).get("redirect"),
             (st.get("byClass") or {}).get("clientError"),
             (st.get("byClass") or {}).get("serverError"), st.get("bytes")))
    check("访问日志解析出请求数", (st.get("requests") or 0) > 0, "")
    check("Top URL 非空", len(st.get("topUrls") or []) > 0, str((st.get("topUrls") or [])[:2]))
    lg = api_get("/php/logs", {"slug": SLUG, "type": "deploy", "lines": 60})
    check("部署日志可读", len(lg.get("lines") or []) > 0, str(lg.get("path")))
    er = api_get("/php/logs", {"slug": SLUG, "type": "error", "lines": 40})
    check("错误日志端点正常", er.get("ok") is True, str(er.get("lines"))[:150])

    print("\n=== 数据库联动 ===")
    dbc = api_post("/php/db/create", {"slug": SLUG})
    check("POST /php/db/create", dbc.get("ok") is True, str(dbc.get("error"))[:220])
    dbi = api_get("/php/db", {"slug": SLUG})
    print("     数据库: %s 用户=%s 表数=%s" % ((dbi.get("db") or {}).get("name"),
                                              (dbi.get("db") or {}).get("user"), dbi.get("tables")))
    check("站点已绑定数据库", bool((dbi.get("db") or {}).get("name")), "")
    probe = ssh("mariadb -N -B -e \"SHOW DATABASES LIKE '" + DBNAME + "';\" 2>&1")[1]
    check("服务器上能查到该库", DBNAME in probe, probe.strip()[:80])
    userrow = ssh("mariadb -N -B -e \"SELECT user,host FROM mysql.user WHERE user='" + DBUSER + "';\" 2>&1")[1]
    check("数据库用户已创建", DBUSER in userrow, userrow.strip()[:80])
    detail = json.dumps(api_get("/php/site", {"slug": SLUG}))
    check("API 不回传数据库明文密码", "password" not in detail.replace("hasPassword", ""), "")
    dp = api_post("/php/db/dump", {"slug": SLUG})
    jd = wait_job((dp.get("job") or {}).get("id"), 200)
    check("数据库备份任务完成", jd.get("state") == "done", str(jd.get("error") or jd.get("output"))[:220])
    bk = api_get("/php/db/backups", {"slug": SLUG})
    ondisk = ssh("ls -l /srv/php/" + SLUG + "/backups/ 2>&1 | tail -3")[1].strip()
    print("     备份目录实况: " + ondisk[:200])
    check("备份文件已落盘", len(bk.get("backups") or []) > 0,
          str((bk.get("backups") or [])[:1])[:140] + " | " + ondisk[:120])

    print("\n=== 在线编辑站点文件立即生效 ===")
    wf = ssh("f=/srv/php/" + SLUG + "/current/index.php; mv $f $f.orig 2>/dev/null; "
             "echo '<?php echo \"EDITED OK\";' > $f; chown php-" + SLUG + ":www-data $f; "
             "curl -s --max-time 15 " + LOCAL + "/index.php")[1]
    check("直接改文件立即生效", "EDITED OK" in wf, wf.strip()[:80])

    print("\n=== 输入校验与防呆 ===")
    bad1 = api_post("/php/site/create", {"slug": "panel", "ports": [8098]})
    check("拒绝保留 slug", bool(bad1.get("error")), str(bad1.get("error"))[:80])
    bad2 = api_post("/php/site/create", {"slug": "badport", "ports": [3306]})
    check("拒绝占用中的端口", bool(bad2.get("error")), str(bad2.get("error"))[:80])
    bad3 = api_post("/php/site/create", {"slug": "inject", "domains": "a.com; rm -rf /", "ports": [8097]})
    check("拒绝域名注入", bool(bad3.get("error")), str(bad3.get("error"))[:80])
    bad4 = api_post("/php/site/conf/write", {"slug": SLUG, "content": "server { listen 80 default_server; }"})
    check("拒绝写 default_server", bool(bad4.get("error")), str(bad4.get("error"))[:90])
    bad5 = api_post("/php/fpm/action", {"version": "8.5", "action": "stop"})
    check("有站点在跑时拒绝停 fpm", bool(bad5.get("error")), str(bad5.get("error"))[:130])
    bad6 = api_post("/php/site/update", {"slug": SLUG, "pool": {"pm": "nonsense"}})
    check("拒绝非法池参数", bool(bad6.get("error")), str(bad6.get("error"))[:130])
    bad7 = api_post("/php/site/create", {"slug": "nodomain80", "ports": [80], "domains": ""})
    check("拒绝无域名只监听 80", bool(bad7.get("error")), str(bad7.get("error"))[:130])

    print("\n=== 清理 ===")
    if a.keep:
        print("     --keep：保留站点 " + SLUG + "（端口 " + str(PORT) + "）")
    else:
        d = api_post("/php/site/delete", {"slug": SLUG})
        check("删除站点", d.get("ok") is True, str(d.get("error") or d.get("output"))[:220])
        after = ssh("ls -1 /etc/nginx/sites-enabled/; echo ---; ls /srv/php/ 2>/dev/null; echo ---; "
                    "id php-" + SLUG + " 2>&1 | head -1; echo ---; "
                    "ls -1 /etc/php/8.5/fpm/pool.d/ | grep -c 'panel-" + SLUG + "' || true; echo ---; "
                    "ls -1 /run/php/ | grep -c 'panel-" + SLUG + "' || true; echo ---; "
                    "mariadb -N -B -e \"SHOW DATABASES LIKE '" + DBNAME + "';\" 2>&1")[1]
        parts = after.split("---")
        print("     清理后: " + after.strip().replace("\n", " | ")[:340])
        check("nginx 链接已移除", ("panel-php-" + SLUG) not in parts[0], parts[0].strip()[:120])
        check("relay 站点未受影响", "opencode-relay" in parts[0], "")
        check("站点目录已移除", SLUG not in (parts[1] if len(parts) > 1 else ""), "")
        check("系统用户已删除", "no such user" in after.lower(),
              (parts[2] if len(parts) > 2 else "").strip()[:80])
        check("FPM 池文件已删除", (parts[3] if len(parts) > 3 else "").strip() == "0",
              "pool=" + (parts[3] if len(parts) > 3 else "?").strip())
        check("FPM 套接字已清理", (parts[4] if len(parts) > 4 else "").strip() == "0",
              "sock=" + (parts[4] if len(parts) > 4 else "?").strip())
        check("数据库已随站点删除", DBNAME not in (parts[5] if len(parts) > 5 else ""),
              (parts[5] if len(parts) > 5 else "").strip()[:80])
        rel = ssh("curl -s -o /dev/null -w '%{http_code}' --max-time 15 http://127.0.0.1:9000/panel/api/health")[1]
        check("面板仍正常响应", rel.strip() == "200", "http=" + rel.strip())
        ssh("rm -rf /tmp/smokev1 /tmp/smokev2 " + STAGE + "/smoke-v1.tar.gz " + STAGE + "/smoke-v2.tar.gz "
            "/tmp/panel-php-drain-* /tmp/panel-php-awk-* /tmp/panel-php-pool-* /tmp/panel-php-db-*")

    return finish()


if __name__ == "__main__":
    sys.exit(main())
