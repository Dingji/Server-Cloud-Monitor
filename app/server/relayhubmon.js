'use strict';
/*
 * RelayHub (内网中继) 运维洞察。
 *
 * relayhub 的 /api/summary 与 /api/port-pool 已经把设备、隧道、探测、端口池都算好了，
 * 但面板只是把它们原样画出来：端口池还剩多少、哪条隧道在反复掉线、frps 的 allowPorts
 * 和面板期望是否漂移 —— 这些都要人自己心算。这里把它们折成一份结构化结论，
 * 顺带喂给 ops 告警（隧道掉线、端口池见底），走面板已有的通知渠道。
 *
 * 只读：这个模块从不 POST 给 relayhub，不改配置。
 */
const fs = require('fs');
const http = require('http');
const { logErr } = require('./util');

const CONF = '/opt/relayhub/panel/config.json';
const HOST = '127.0.0.1';
const PORT = 8100;
const TTL_MS = 15000;          // 与面板页的刷新节奏一致
const STALE_MS = 120000;       // 超过这个年龄就当作取不到，宁可不报也不误报

let cached = { at: 0, data: null, error: null, fetching: null };

function internalKey() {
  try { return JSON.parse(fs.readFileSync(CONF, 'utf8')).internal_key || ''; } catch (_) { return ''; }
}

function get(path) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: HOST, port: PORT, path, method: 'GET',
      headers: { 'X-RelayHub-Key': internalKey(), Accept: 'application/json' },
      timeout: 5000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        if (res.statusCode >= 400) { reject(new Error('HTTP ' + res.statusCode)); return; }
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); }
        catch (e) { reject(e); }
      });
    });
    req.on('timeout', () => req.destroy(new Error('relayhub 响应超时')));
    req.on('error', reject);
    req.end();
  });
}

/* ---------- 端口池：容量、碎片、最热端口 ---------- */

/**
 * "20000-20099,22000-22010" -> [[20000,20099],[22000,22010]]
 * frps 那边存的是 TOML 原行（allowPorts = [{ start = 12838, end = 12838 }, …]），
 * 两种写法都要认：只认逗号串会把 frps 的实际放行读成空，漂移判断就会长期误报。
 */
function parseRanges(text) {
  const s = String(text || '');
  const out = [];
  const toml = /\{\s*start\s*=\s*(\d+)(?:\s*,\s*end\s*=\s*(\d+))?\s*\}/g;
  let m;
  while ((m = toml.exec(s))) {
    const a = Number(m[1]), b = m[2] === undefined ? a : Number(m[2]);
    if (Number.isFinite(a) && Number.isFinite(b) && b >= a) out.push([a, b]);
  }
  if (out.length) return out;
  return s.split(/[,;\s]+/).map((t) => {
    const mm = /^(\d+)\s*-\s*(\d+)$/.exec(t.trim()) || /^(\d+)$/.exec(t.trim());
    if (!mm) return null;
    const a = Number(mm[1]), b = Number(mm[2] === undefined ? mm[1] : mm[2]);
    return Number.isFinite(a) && Number.isFinite(b) && b >= a ? [a, b] : null;
  }).filter(Boolean);
}

/* 逐端口扫描要有预算：allowPorts 写成 1-65535 时不能把面板拖住。
   超预算就停止并如实标 exact=false，让页面显示"近似"而不是给个错数字。 */
const SCAN_BUDGET = 60000;

function poolInsight(pp) {
  const pool = (pp && pp.pool) || {};
  const count = Number(pp.port_count) || 0;
  const usedSet = new Set((Array.isArray(pp.used) ? pp.used : []).map(Number).filter(Number.isFinite));
  const free = Math.max(0, count - usedSet.size);
  const pct = count ? Math.round((usedSet.size / count) * 1000) / 10 : 0;
  /* 碎片度：空闲端口被切成几段。段数多而最长段小 = 池被切碎，
     新隧道可能"总量还有但放不下"，这比百分比更值得提前告警。 */
  const eff = [];
  (pp.effective || []).forEach((r) => {
    const a = Number(r && r[0] !== undefined ? r[0] : (r && r.start));
    const b = Number(r && r[1] !== undefined ? r[1] : (r && r.end));
    if (Number.isFinite(a) && Number.isFinite(b) && b >= a) eff.push([a, b]);
  });
  let runs = 0, longest = 0, cur = 0, scanned = 0, exact = true;
  for (const [a, b] of eff) {
    for (let p = a; p <= b; p++) {
      if (++scanned > SCAN_BUDGET) { exact = false; break; }
      if (usedSet.has(p)) { if (cur > 0) { runs++; longest = Math.max(longest, cur); } cur = 0; }
      else cur++;
    }
    if (!exact) break;
  }
  if (cur > 0) { runs++; longest = Math.max(longest, cur); }
  return {
    count, used: usedSet.size, free, pct,
    fragments: exact ? runs : null, longestFreeRun: exact ? longest : null, exact,
    rangesText: pp.ranges_text || '',
    hottest: (pp.usage_top || []).slice(0, 8),
    protected: (pp.protected || []).length,
    level: !count ? 'unknown' : pct >= 95 ? 'bad' : pct >= 85 ? 'warn' : 'ok',
  };
}

/* ---------- 配置漂移：面板期望 vs frps 实际 ---------- */

/** want 里的每个区间都要被 allow 里某个区间完整覆盖。 */
function uncovered(allowText, wantText) {
  const allow = parseRanges(allowText);
  const out = [];
  parseRanges(wantText).forEach(([a, b]) => {
    if (!allow.some(([x, y]) => x <= a && y >= b)) out.push(a + '-' + b);
  });
  return out;
}

function driftInsight(pp) {
  const pool = (pp && pp.pool) || {};
  const allow = String(pp.frps_allowports || '');
  const want = pp.ranges_text || (pp.effective || []).map((r) => `${r[0]}-${r[1]}`).join(',');
  const missing = allow ? uncovered(allow, want) : parseRanges(want).map(([a, b]) => a + '-' + b);
  return {
    frpsAllowPorts: allow,
    expected: want,
    /* frps 的 allowPorts 少了任何一段，那段里的隧道会被 frps 直接拒绝 —— 面板改了也没用 */
    matches: missing.length === 0 && !!allow,
    missing,
    /* ufw_rules 是逐条规则数组，不是数字 */
    ufwRules: Array.isArray(pp.ufw_rules) ? pp.ufw_rules.length : (Number(pp.ufw_rules) || 0),
    maxPerClient: Number(pool.max_per_client) || 0,
    frpsMaxPerClient: Number(pp.max_ports_per_client_frps) || 0,
    /* frps 侧的单机上限低于面板策略上限时，超出的隧道同样会被拒 */
    cappedByFrps: !!(pp.max_ports_per_client_frps && pool.max_per_client
      && Number(pp.max_ports_per_client_frps) < Number(pool.max_per_client)),
  };
}

/* ---------- 隧道：错误、抖动、流量 ---------- */

const HOUR = 3600000;

/**
 * relayhub 的 lastStartTime / lastCloseTime 是不带年份、不带时区的本地时间串
 * （"10-30 22:11:13"，页面里的 ago() 也是这么补年份的），meta.now 是秒。
 * 解析不出来就返回 null，不参与抖动判断。
 */
function parseTs(raw) {
  let s = String(raw || '').trim();
  if (!s) return null;
  if (/^\d{2}-\d{2} \d{2}:\d{2}/.test(s)) s = new Date().getFullYear() + '-' + s;
  const iso = (s.indexOf('T') >= 0 || s.indexOf('Z') >= 0) ? s : s.replace(' ', 'T');
  const t = new Date(iso).getTime();
  return Number.isFinite(t) ? t : null;
}
/** frps 的隧道名是 "设备.隧道"，设备名要从这里取（summary 里没有 device 字段）。 */
const deviceOf = (name) => {
  const s = String(name || '');
  return s.indexOf('.') >= 0 ? s.split('.')[0] : '(默认)';
};

function tunnelInsight(d) {
  const devices = d.devices || [], proxies = d.proxies || [];
  const errors = [];
  const flapping = [];
  /* devices[].proxies 是计数不是数组，隧道级的 err 只在 proxies 里 */
  proxies.forEach((p) => {
    if (p.err) errors.push({ device: deviceOf(p.name), name: p.name, err: String(p.err).slice(0, 160) });
  });
  const now = Date.now();
  proxies.forEach((p) => {
    const close = parseTs(p.lastCloseTime);
    if (String(p.status) !== 'online' && close != null && now - close < 6 * HOUR && now >= close) {
      flapping.push({ device: deviceOf(p.name), name: p.name, closedAgo: Math.max(0, Math.round((now - close) / 60000)) });
    }
  });
  const online = devices.filter((x) => x.status === 'online');
  return {
    total: proxies.length,
    online: proxies.filter((p) => String(p.status) === 'online').length,
    errors: errors.slice(0, 40),
    errorCount: errors.length,
    flapping: flapping.sort((a, b) => a.closedAgo - b.closedAgo).slice(0, 20),
    curConns: devices.reduce((a, x) => a + (Number(x.cur_conns) || 0), 0),
    todayIn: devices.reduce((a, x) => a + (Number(x.today_in) || 0), 0),
    todayOut: devices.reduce((a, x) => a + (Number(x.today_out) || 0), 0),
    rateIn: devices.reduce((a, x) => a + (Number(x.rate_in) || 0), 0),
    rateOut: devices.reduce((a, x) => a + (Number(x.rate_out) || 0), 0),
    deviceOffline: devices.filter((x) => x.status !== 'online').map((x) => x.name),
    /* 在线设备但一条隧道都没起来：frpc 活着、映射全空，是最容易被忽略的故障形态 */
    emptyTunnels: online.filter((x) => !Number(x.online_proxies)).map((x) => x.name),
  };
}

/* ---------- 公网探测延迟 ---------- */

function probeInsight(d) {
  const probes = d.probes || {};
  const rows = Object.keys(probes).map((k) => Object.assign({ name: k }, probes[k] || {}));
  const ok = rows.filter((r) => r.ok);
  const bad = rows.filter((r) => !r.ok);
  const sorted = ok.slice().sort((a, b) => (b.ms || 0) - (a.ms || 0));
  return {
    covered: rows.length, total: (d.devices || []).length,
    slowest: sorted.slice(0, 5).map((r) => ({ name: r.name, ms: Math.round(r.ms || 0) })),
    avgMs: ok.length ? Math.round(ok.reduce((a, r) => a + (r.ms || 0), 0) / ok.length) : null,
    failed: bad.slice(0, 20).map((r) => ({ name: r.name, detail: String(r.detail || r.state || '').slice(0, 120) })),
    failedCount: bad.length,
  };
}

function build() {
  return Promise.all([get('/api/summary'), get('/api/port-pool').catch(() => null)])
    .then(([d, pp]) => {
      const insight = {
        ok: true,
        at: new Date().toISOString(),
        meta: d.meta || {},
        health: d.health || {},
        frpsOk: d.frps_ok !== false,
        frpsError: d.frps_error || '',
        devices: (d.devices || []).length,
        tunnels: tunnelInsight(d),
        probes: probeInsight(d),
        pool: pp ? poolInsight(pp) : null,
        drift: pp ? driftInsight(pp) : null,
        history: d.history || [],
      };
      return insight;
    });
}

/** 带 TTL 的快照；并发调用共享同一个 in-flight 请求。 */
function snapshot() {
  const now = Date.now();
  if (cached.data && now - cached.at < TTL_MS) return Promise.resolve(cached.data);
  if (cached.fetching) return cached.fetching;
  cached.fetching = build()
    .then((data) => { cached = { at: Date.now(), data, error: null, fetching: null }; return data; })
    .catch((e) => {
      logErr('relayhub insight failed:', e.message);
      // 失败时保留旧数据但记下时间，页面与告警都能判断"这是陈旧的"
      cached = { at: cached.at, data: cached.data, error: e.message, fetching: null };
      if (cached.data) return cached.data;
      return { ok: false, error: e.message, at: new Date().toISOString() };
    });
  return cached.fetching;
}

function ageMs() { return cached.data ? Date.now() - cached.at : null; }
function isStale() { const a = ageMs(); return a == null || a > STALE_MS; }

/* ---------- 告警指标（面板已有规则引擎与通知渠道） ---------- */

function metricTunnelDown() {
  if (!cached.data || isStale()) return null;
  const t = cached.data.tunnels || {};
  const devs = cached.data.devices || 0;
  const offline = (t.deviceOffline || []).length + (cached.data.frpsOk === false ? devs : 0);
  return offline;
}
function metricPortFree() {
  if (!cached.data || isStale() || !cached.data.pool) return null;
  return cached.data.pool.free;
}
function metricPortPct() {
  if (!cached.data || isStale() || !cached.data.pool) return null;
  return cached.data.pool.pct;
}
function metricTunnelErrors() {
  if (!cached.data || isStale()) return null;
  return (cached.data.tunnels || {}).errorCount || 0;
}

let poller = null;
/** 告警规则要一直有数，不能等人打开页面才有快照。装了 relayhub 才轮询；没装就静默。 */
function startPolling(ms) {
  if (poller) return;
  const tick = () => { snapshot().catch(() => {}); };
  tick();
  poller = setInterval(tick, ms || 30000);
  if (poller.unref) poller.unref();
}
function stopPolling() { if (poller) { clearInterval(poller); poller = null; } }

module.exports = { snapshot, startPolling, stopPolling, ageMs, isStale, metricTunnelDown, metricPortFree, metricPortPct, metricTunnelErrors };
