#!/usr/bin/env python3
"""Cloud Monitor 集成补丁:注入 RelayHub 代理路由(API)、导航入口与懒加载页(前端)。幂等。"""
import json
import pathlib
import secrets
import shutil
import time

TS = int(time.time())


def backup(p):
    if p.exists():
        shutil.copy2(p, str(p) + ".relayhub-bak." + str(TS))


# ---------- 1. 服务端: 代理路由 ----------
IDX = pathlib.Path("/opt/cloud-monitor/server/index.js")
text = IDX.read_text()
if "relayhub" not in text:
    backup(IDX)
    anchor = "api.post('/web/action'"
    assert anchor in text, "web.action anchor missing"
    block = """/* ============ optional RelayHub (内网中继监控) integration ============ */
const RELAYHUB_CONF = (() => {
  try { return JSON.parse(require('fs').readFileSync('/opt/relayhub/panel/config.json', 'utf8')); } catch (_) { return {}; }
})();
const RELAYHUB_KEY = RELAYHUB_CONF.internal_key || '';
async function relayhubFetch(pathname, init) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 5000);
  try {
    const r = await fetch('http://127.0.0.1:8100' + pathname, Object.assign({}, init, {
      headers: { 'X-RelayHub-Key': RELAYHUB_KEY, 'Content-Type': 'application/json' },
      signal: ctrl.signal,
    }));
    const data = await r.json().catch(() => ({}));
    if (!r.ok) { const e = new Error(data.error || ('HTTP ' + r.status)); e.status = r.status; throw e; }
    return data;
  } finally { clearTimeout(timer); }
}
api.get('/relayhub/available', wrap(async (req, res) => {
  try { res.json(await relayhubFetch('/api/health')); } catch (e) { res.json({ ok: false }); }
}));
api.get('/relayhub/summary', wrap(async (req, res) => res.json(await relayhubFetch('/api/summary'))));
api.post('/relayhub/probe-now', audited('relayhub.probe', async () => relayhubFetch('/api/probe-now', { method: 'POST' })));

"""
    text = text.replace(anchor, block + anchor, 1)
    IDX.write_text(text)
    print("server index.js: relayhub routes injected")
else:
    print("server index.js: already patched")

# ---------- 2. 前端 app.js: 导航 + 懒加载 + 版本 ----------
APP = pathlib.Path("/opt/cloud-monitor/public/js/app.js")
atext = APP.read_text()
changed = False
if "'relayhub'" not in atext:
    backup(APP)
    old_nav = """  const OPTIONAL_NAV = [
    { id: 'relay', after: 'dashboard', label: 'OpenCode 中继', icon: 'satellite', probe: '/relay/available' },
  ];"""
    new_nav = """  const OPTIONAL_NAV = [
    { id: 'relay', after: 'dashboard', label: 'OpenCode 中继', icon: 'satellite', probe: '/relay/available' },
    { id: 'relayhub', after: 'relay', label: '内网中继监控', icon: 'wlan', probe: '/relayhub/available' },
  ];"""
    assert old_nav in atext, "OPTIONAL_NAV anchor missing"
    atext = atext.replace(old_nav, new_nav, 1)
    old_lazy = "    relay: 'js/pages/relay.js',"
    assert old_lazy in atext, "lazyPages anchor missing"
    atext = atext.replace(old_lazy, old_lazy + "\n    relayhub: 'js/pages/relayhub.js',", 1)
    import re
    atext, n = re.subn(r"CM\.BUILD = '[^']*';", "CM.BUILD = '" + time.strftime("%Y%m%d-%H") + "';", atext, count=1)
    assert n == 1, "BUILD not found"
    APP.write_text(atext)
    print("app.js: nav + lazyPages + BUILD patched")
    changed = True
else:
    print("app.js: already patched")

# ---------- 3. index.html 版本号 ----------
HTML = pathlib.Path("/opt/cloud-monitor/public/index.html")
htext = HTML.read_text()
newv = "?v=" + time.strftime("%Y%m%d-%H")
if newv not in htext:
    backup(HTML)
    import re
    htext = re.sub(r"\?v=\d{8}-\d+", newv, htext)
    HTML.write_text(htext)
    print("index.html: version bumped to", newv)
else:
    print("index.html: version current")

# ---------- 4. RelayHub 内部密钥 ----------
RH = pathlib.Path("/opt/relayhub/panel/config.json")
cfg = json.loads(RH.read_text())
if not cfg.get("internal_key"):
    cfg["internal_key"] = secrets.token_hex(24)
    RH.write_text(json.dumps(cfg, indent=2, ensure_ascii=False))
    print("relayhub config.json: internal_key generated")
else:
    print("relayhub config.json: internal_key exists")

print("PATCH DONE")
