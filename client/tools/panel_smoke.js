'use strict';
/* 面板 API 冒烟：口令从 stdin 读（不进 argv、不留盘），只打印结论不打凭据。
   用法: node /var/tmp/panel_smoke.js < /path/to/pw.txt   （或管道） */
const http = require('http');

const BASE = 'http://127.0.0.1:9000/panel/api';

async function main() {
  const pw = (await new Promise((res) => {
    let s = '';
    process.stdin.on('data', (c) => { s += c; });
    process.stdin.on('end', () => res(s));
  })).replace(/\r?\n/g, '');

  const login = await fetch(BASE + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password: pw }),
  });
  const lj = await login.json().catch(() => ({}));
  const token = lj.token || '';
  console.log('login:', login.status, token ? 'token ok' : JSON.stringify(lj).slice(0, 120));
  if (!token) return;
  const H = { Authorization: 'Bearer ' + token };

  const j = async (p, opt) => {
    const r = await fetch(BASE + p, Object.assign({ headers: H }, opt || {}));
    const txt = await r.text();
    let d = {};
    try { d = JSON.parse(txt); } catch (_) { d = { raw: txt.slice(0, 120) }; }
    return { status: r.status, d, ct: r.headers.get('content-type') };
  };

  const ins = await j('/relayhub/insight');
  console.log('insight:', ins.status,
    'ok=' + ins.d.ok,
    'pool=' + JSON.stringify(ins.d.pool && { count: ins.d.pool.count, used: ins.d.pool.used, free: ins.d.pool.free, pct: ins.d.pool.pct, level: ins.d.pool.level, exact: ins.d.pool.exact }),
    'drift=' + JSON.stringify(ins.d.drift && { matches: ins.d.drift.matches, ufw: ins.d.drift.ufwRules, missing: ins.d.drift.missing }),
    'tunnels=' + JSON.stringify(ins.d.tunnels && { online: ins.d.tunnels.online, total: ins.d.tunnels.total, err: ins.d.tunnels.errorCount, flap: ins.d.tunnels.flapping.length, offline: ins.d.tunnels.deviceOffline }),
    'ageMs=' + ins.d.cacheAgeMs, 'stale=' + ins.d.stale);

  const sum = await j('/relayhub/summary');
  const meta = (sum.d && sum.d.meta) || {};
  console.log('summary:', sum.status,
    'frps_token present=' + ('frps_token' in meta),
    'masked=' + JSON.stringify(meta.frps_token_masked || null),
    'server_bind_password present=' + ('server_bind_password' in meta),
    'devices=' + (sum.d.devices || []).length);

  const probe = await j('/notebox/probe');
  console.log('notebox probe:', probe.status, JSON.stringify(probe.d));

  /* 代理转发是否真的把 notebox 的 401 原样带回来（未登录 notebox 时应该是 401） */
  const nbBoot = await j('/notebox/bootstrap');
  console.log('notebox /bootstrap:', nbBoot.status, JSON.stringify(nbBoot.d).slice(0, 160));
  const nbDown = await j('/notebox/nope');
  console.log('notebox 未知路径:', nbDown.status, JSON.stringify(nbDown.d).slice(0, 120));

  /* 新页签用到的路由：路径映射对不对（未登录 notebox 时应当是 401，不是 404） */
  for (const p of ['/notebox/settings', '/notebox/emails', '/notebox/admin/users', '/notebox/stats']) {
    const r = await j(p);
    console.log('route ' + p + ':', r.status, JSON.stringify(r.d).slice(0, 80));
  }
  /* /uploads/<名> 必须打到 notebox 的 /uploads（不带 /api 前缀）：
     文件不存在时它回 {"error":"not found"}，被加前缀的话会是 Flask 的 404 页 */
  const up = await j('/notebox/uploads/__probe__.png');
  console.log('uploads 映射:', up.status, JSON.stringify(up.d).slice(0, 120), 'ct=' + JSON.stringify(up.ct));
  const sh = await j('/notebox/share/nosuchcode');
  console.log('share 只读接口:', sh.status, JSON.stringify(sh.d).slice(0, 120));

  /* 静态件：markdown 渲染器要能从面板 origin 取到 */
  const md = await new Promise((res) => {
    http.get('http://127.0.0.1:9000/panel/public/js/md.js', (r) => {
      const c = [];
      r.on('data', (x) => c.push(x));
      r.on('end', () => res({ status: r.statusCode, bytes: Buffer.concat(c).length, ct: r.headers['content-type'] }));
    }).on('error', (e) => res({ status: 'ERR ' + e.message }));
  });
  console.log('md.js:', JSON.stringify(md));

  /* 告警指标是否进了规则引擎的表 */
  const met = await j('/ops/alerts/rules');
  const keys = ((met.d && met.d.metrics) || []).map((m) => m.key);
  const want = ['relayTunnelDown', 'relayTunnelErr', 'relayPortFree', 'relayPortPct'];
  console.log('ops alerts/rules:', met.status, '指标总数=' + keys.length,
    want.map((k) => k + '=' + (keys.indexOf(k) >= 0)).join(' '));
  const eng = (met.d && met.d.engine) || {};
  console.log('engine values:', want.map((k) => k + '=' + JSON.stringify(eng.values && eng.values[k])).join(' '));
}

main().catch((e) => console.log('ERROR', e.message));
