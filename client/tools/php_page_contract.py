#!/usr/bin/env python
"""Contract check: every field path the panel page dereferences must exist (or be
safely optional) in the live API payload.

The in-app browser had no visible surface, so the page could not be rendered.
This is the next-best oracle: drive a real site through the module and compare
the JSON shapes against the paths listed in panel-web/php.js.
"""
import json
import os
import subprocess
import urllib.error
import urllib.request

HOST = os.environ.get("SM_HOST", "8.153.153.176")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
API = "http://" + HOST + "/panel/api"
SSH = ["ssh", "-i", KEY, "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes", "root@" + HOST]
SLUG = "contract-php"
PORT = 8097

fails = []
TOKEN = None


def ssh(c):
    return subprocess.run(SSH + [c], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=180).stdout


def req(path, body=None):
    r = urllib.request.Request(API + path,
                               data=json.dumps(body).encode() if body is not None else None,
                               headers={"Authorization": "Bearer " + (TOKEN or ""),
                                        "Content-Type": "application/json"},
                               method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(r, timeout=120) as f:
            return json.loads(f.read().decode())
    except urllib.error.HTTPError as e:
        return {"__error": e.code, "error": e.read().decode()[:300]}


def has(obj, path):
    cur = obj
    for part in path.split("."):
        if isinstance(cur, list):
            if not cur:
                return True, "[] empty"
            cur = cur[0]
        if not isinstance(cur, dict) or part not in cur:
            return False, "missing at " + part
        cur = cur[part]
    return True, ""


def main():
    global TOKEN, fails
    TOKEN = req("/login", {"password": os.environ["SM_PANEL_PASS"]}).get("token")
    assert TOKEN, "login failed"

    req("/php/site/delete", {"slug": SLUG})
    ssh("rm -rf /srv/php/" + SLUG + " /etc/nginx/sites-enabled/panel-php-" + SLUG + ".conf "
        "/etc/nginx/sites-available/panel-php-" + SLUG + ".conf /etc/php/8.5/fpm/pool.d/panel-" + SLUG + ".conf")
    c = req("/php/site/create", {"slug": SLUG, "name": "contract", "preset": "wordpress",
                                 "ports": [PORT], "domains": "contract.example.test"})
    print("create:", str(c.get("output") or c.get("error"))[:160])
    req("/php/db/create", {"slug": SLUG})
    g = req("/php/site/git", {"slug": SLUG, "git": {"url": "https://github.com/example/site.git",
                                                    "branch": "trunk", "autoDeploy": True}})
    print("git set:", str(g.get("output") or g.get("error"))[:120])
    h = req("/php/site/hooks", {"slug": SLUG, "hooks": {"post_deploy": ["echo ready"]}})
    print("hooks set:", str(h.get("output") or h.get("error"))[:120])

    ov = req("/php/overview")
    sites = req("/php/sites")
    d = req("/php/site?slug=" + SLUG)
    ini = req("/php/ini")
    ext = req("/php/ext")
    rel = req("/php/releases?slug=" + SLUG)
    st = req("/php/stats?slug=" + SLUG)
    logs = req("/php/logs?slug=" + SLUG + "&type=access&lines=50")
    jobs = req("/php/jobs")
    ssl = req("/php/ssl?slug=" + SLUG)
    sslall = req("/php/ssl/all")
    if "certificates" not in sslall:
        fails.append("/php/ssl/all must return the global certificate list")
    conf = req("/php/site/conf?slug=" + SLUG)
    db = req("/php/db?slug=" + SLUG)

    print("\n=== overview 契约（页面顶部卡片 + 环境面板） ===")
    for p in ["versions", "versions.version", "versions.full", "versions.serviceState", "versions.pools",
              "tools.composer", "tools.git", "tools.certbot", "tools.mariadbState", "tools.redisState",
              "resources.disk.free", "resources.disk.used", "resources.disk.total",
              "resources.mem.availMb", "resources.mem.usedMb", "resources.mem.totalMb",
              "resources.cpu", "resources.load", "resources.poolCeiling",
              "sites", "counts.sites", "counts.enabled", "counts.ssl", "counts.withDb", "counts.withGit",
              "presets", "presets.id", "presets.label", "presets.install",
              "iniKeys", "iniKeys.key", "iniKeys.label", "iniKeys.unit",
              "extCatalog", "base", "staging", "missing", "ready", "defaultVersion"]:
        ok, why = has(ov, p)
        if not ok:
            fails.append("overview." + p + " " + why)
    print("  %s" % ("全部命中" if not fails else "\n".join(fails)))

    print("\n=== sites[] 行渲染契约 ===")
    bad = []
    for p in ["slug", "name", "domains", "ports", "php", "preset", "ssl", "enabled", "linked",
              "docSize", "fileCount", "releases", "current", "htdocs",
              "updatedAt", "db.name", "git.url"]:
        ok, why = has(sites, p)
        if not ok:
            bad.append("sites[]." + p + " " + why)
    print("  %s" % ("全部命中" if not bad else "\n".join(bad)))
    fails += bad

    print("\n=== /php/site 详情契约（管理面板） ===")
    bad = []
    for p in ["site.slug", "site.name", "site.urls", "site.php", "site.sockExists", "site.poolExists",
              "site.linked", "site.current", "site.docSize", "site.fileCount", "site.releases",
              "site.domains", "site.ssl", "site.enabled", "site.db", "site.git",
              "record.preset", "record.phpOverrides", "record.pool", "record.uploadDirs",
              "record.hardening.denyUploadExec", "record.openBasedir", "record.disableFunctions",
              "record.extraNginx", "record.notes", "record.docRoot", "record.git.url", "record.git.branch",
              "record.git.subdir", "record.git.depth", "record.git.autoDeploy", "record.hooks",
              "db.bound", "db.db.name", "db.db.user", "db.db.host", "db.tables", "db.bytes", "db.connected",
              "ssl.ssl", "ssl.certbotInstalled", "ssl.reason",
              "releases.releases", "releases.releases.id", "releases.releases.active",
              "releases.releases.kind", "releases.releases.at", "releases.docRoot", "releases.shared",
              "releases.history", "webhook.path", "files.root"]:
        ok, why = has(d, p)
        if not ok:
            bad.append("/php/site." + p + " " + why)
    print("  %s" % ("全部命中" if not bad else "\n".join(bad)))
    fails += bad

    print("\n=== 其余端点契约 ===")
    checks = [
        ("/php/ini", ini, ["version", "iniFile", "scanFile", "effectiveCli", "siteDefaults", "systemOnlyKeys", "overrides"]),
        ("/php/ext", ext, ["version", "loaded", "catalog", "catalog.ext", "catalog.label", "catalog.installed", "catalog.package"]),
        ("/php/releases", rel, ["slug", "active", "releases", "docRoot", "shared"]),
        ("/php/stats", st, ["requests", "byClass.ok", "byClass.redirect", "byClass.clientError",
                            "byClass.serverError", "bytes", "topUrls", "topIps", "errorRate", "recentErrors"]),
        ("/php/logs", logs, ["type", "path", "exists", "bytes", "lines"]),
        ("/php/jobs", jobs, ["jobs"]),
        ("/php/ssl?slug", ssl, ["ssl", "certbotInstalled", "canIssue", "reason"]),
        ("/php/site/conf", conf, ["file", "content", "pool.file", "pool.content"]),
        ("/php/db", db, ["bound", "db.name", "tables", "bytes"]),
    ]
    for name, payload, paths in checks:
        bad = []
        for p in paths:
            ok, why = has(payload, p)
            if not ok:
                bad.append(name + "." + p + " " + why)
        print("  %-16s %s" % (name, "ok" if not bad else "\n".join(bad)))
        fails += bad

    print("\n=== 页面里用到的图标名（必须在面板 icons.js 里存在） ===")
    import re
    src = open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            "panel-web", "php.js"), encoding="utf-8").read()
    used = sorted(set(re.findall(r"icon\('([a-zA-Z0-9]+)'", src)))
    live = set(re.findall(r"^\s{4}([a-zA-Z0-9]+):", ssh("cat /opt/cloud-monitor/public/js/icons.js"), re.M))
    miss = [u for u in used if u not in live]
    print("  用到 %s\n  缺失 %s" % (",".join(used), miss or "无"))
    if miss:
        fails.append("icons missing: " + ",".join(miss))
    nav_icon = re.search(r"id: 'php', label: '[^']*', icon: '([a-zA-Z0-9]+)'", ssh("cat /opt/cloud-monitor/public/js/app.js"))
    print("  导航图标 %s -> %s" % (nav_icon.group(1) if nav_icon else "?",
                                   "存在" if nav_icon and nav_icon.group(1) in live else "缺失"))
    if not nav_icon or nav_icon.group(1) not in live:
        fails.append("nav icon invalid")

    req("/php/site/delete", {"slug": SLUG})
    ssh("rm -rf /srv/php/" + SLUG)

    print("\n================ 契约结果 ================")
    if fails:
        for f in fails:
            print("  MISMATCH  " + f)
    else:
        print("  页面引用的每一个字段路径都在真实响应里存在")
    return len(fails)


if __name__ == "__main__":
    raise SystemExit(main())
