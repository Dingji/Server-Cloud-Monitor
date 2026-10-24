'use strict';
/*
 * Availability probing (uptime monitoring). Commercial equivalents: PRTG /
 * Datadog / 1Panel monitors. Types: HTTP(S) with expected status + keyword,
 * TLS certificate days-left, TCP port, ICMP ping, DNS resolution. Each result
 * is recorded, rolled up per day for SLA, and state transitions are raised as
 * alerting events (so they reach the bell and the notification channels).
 */
const net = require('net');
const tls = require('tls');
const os = require('os');
const dns = require('dns').promises;
const { spawn } = require('child_process');
const S = require('./store');

const TASKS = 'uptime_tasks.json';
const STATE = 'uptime_state.json';
const ROLLUP = 'uptime_rollup.json';
const HISTORY = 'uptime_history.jsonl';
const EVENTS = 'uptime_events.jsonl';

const TYPES = {
  http: 'HTTP / HTTPS 网页',
  ssl: 'TLS 证书剩余天数',
  tcp: 'TCP 端口连通',
  ping: 'ICMP Ping',
  dns: 'DNS 解析',
};

/**
 * Probes keep the fetch that existed when the module loaded, so a test harness
 * (or a future wrapper) stubbing global fetch for notification channels cannot
 * silently fake the probe results.
 */
const httpFetch = typeof globalThis.fetch === 'function' ? globalThis.fetch.bind(globalThis) : null;

const list = () => S.readJson(TASKS, { items: [] }).items;
const states = () => S.readJson(STATE, { tasks: {} });

function normalizeTask(b, existing) {
  const type = TYPES[b.type] ? b.type : (existing && existing.type) || 'http';
  const st = existing || {};
  const pick = (k, d) => (b[k] !== undefined ? b[k] : (st[k] !== undefined ? st[k] : d));
  const t = {
    id: st.id || S.genId('u'),
    name: String(pick('name', type)).trim().slice(0, 60),
    type,
    url: String(pick('url', '')).trim().slice(0, 300),
    host: String(pick('host', '')).trim().slice(0, 160),
    port: Math.round(S.clampNum(pick('port', 0), 0, 65535, type === 'ssl' ? 443 : 0)),
    method: ['GET', 'HEAD', 'POST'].includes(String(pick('method', 'GET')).toUpperCase()) ? String(pick('method', 'GET')).toUpperCase() : 'GET',
    expectStatus: String(pick('expectStatus', '200-299,301,302')).slice(0, 60),
    keyword: String(pick('keyword', '')).slice(0, 120),
    timeoutMs: Math.round(S.clampNum(pick('timeoutMs', 8000), 500, 60000, 8000)),
    intervalSec: Math.round(S.clampNum(pick('intervalSec', 60), 10, 86400, 60)),
    failThreshold: Math.round(S.clampNum(pick('failThreshold', 2), 1, 10, 2)),
    certWarnDays: Math.round(S.clampNum(pick('certWarnDays', 14), 1, 365, 14)),
    channelIds: Array.isArray(pick('channelIds', [])) ? pick('channelIds', []).map(String).slice(0, 20) : [],
    enabled: pick('enabled', true) !== false,
    group: String(pick('group', '默认')).trim().slice(0, 30) || '默认',
    note: String(pick('note', '')).slice(0, 200),
    createdAt: st.createdAt || S.now(),
    updatedAt: S.now(),
  };
  if (t.type === 'http' && !/^https?:\/\//i.test(t.url)) { const e = new Error('HTTP 拨测需要以 http:// 或 https:// 开头的地址'); e.status = 400; throw e; }
  if ((t.type === 'tcp' || t.type === 'ssl') && !t.host) { const e = new Error('TCP / 证书拨测需要填写主机地址'); e.status = 400; throw e; }
  if ((t.type === 'tcp') && !(t.port > 0)) { const e = new Error('TCP 拨测需要填写端口'); e.status = 400; throw e; }
  if (t.type === 'dns' && !t.host) { const e = new Error('DNS 拨测需要填写域名'); e.status = 400; throw e; }
  if (t.type === 'ping' && !t.host) { const e = new Error('Ping 拨测需要填写主机地址'); e.status = 400; throw e; }
  return t;
}

/* ---------------- probes ---------------- */

function statusAllowed(spec, code) {
  const s = String(spec || '').trim();
  if (!s) return code >= 200 && code < 400;
  return s.split(/[,;、\s]+/).filter(Boolean).some((part) => {
    const rng = /^(\d{3})\s*-\s*(\d{3})$/.exec(part);
    if (rng) return code >= Number(rng[1]) && code <= Number(rng[2]);
    return Number(part) === code;
  });
}

function tcpProbe(host, port, timeoutMs) {
  return new Promise((resolve) => {
    const started = process.hrtime.bigint();
    const sock = net.connect({ host, port });
    let done = false;
    const finish = (ok, error) => {
      if (done) return;
      done = true;
      sock.destroy();
      resolve({ ok, ms: Number(process.hrtime.bigint() - started) / 1e6, error });
    };
    sock.setTimeout(timeoutMs);
    sock.once('connect', () => finish(true, ''));
    sock.once('timeout', () => finish(false, '连接超时'));
    sock.once('error', (e) => finish(false, e.code || e.message));
  });
}

function tlsProbe(host, port, timeoutMs) {
  return new Promise((resolve) => {
    const started = process.hrtime.bigint();
    let done = false;
    let sock;
    const finish = (r) => { if (!done) { done = true; try { if (sock) sock.destroy(); } catch (_) {} resolve(Object.assign({ ms: Number(process.hrtime.bigint() - started) / 1e6 }, r)); } };
    try {
      sock = tls.connect({ host, port, servername: host, rejectUnauthorized: false, timeout: timeoutMs }, () => {
        try {
          const cert = sock.getPeerCertificate(true);
          if (!cert || !cert.valid_to) return finish({ ok: false, error: '未取得证书' });
          const validTo = new Date(cert.valid_to).getTime();
          const days = Math.floor((validTo - Date.now()) / 86400000);
          finish({ ok: days > 0, certDays: days, code: null, error: days > 0 ? '' : `证书已过期 ${-days} 天`, subject: (cert.subject && cert.subject.CN) || cert.fingerprint256 || '' });
        } catch (e) { finish({ ok: false, error: '证书解析失败：' + e.message }); }
      });
    } catch (e) { return finish({ ok: false, ms: 0, error: e.message }); }
    sock.setTimeout(timeoutMs);
    sock.once('timeout', () => finish({ ok: false, error: '握手超时' }));
    sock.once('error', (e) => finish({ ok: false, error: e.code || e.message }));
  });
}

function icmpProbe(host, timeoutMs) {
  const isWin = process.platform === 'win32';
  const args = isWin ? ['-n', '4', host] : ['-c', '4', '-W', String(Math.max(1, Math.round(timeoutMs / 1000))), host];
  return new Promise((resolve) => {
    const started = process.hrtime.bigint();
    let child;
    try { child = spawn(isWin ? 'ping' : '/bin/ping', args, { timeout: timeoutMs + 4000 }); }
    catch (e) { resolve({ ok: false, ms: 0, error: '无法执行 ping：' + e.message }); return; }
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('error', (e) => resolve({ ok: false, ms: 0, error: e.code === 'ENOENT' ? '服务器没有 ping 命令' : e.message }));
    child.on('close', (code) => {
      const rtts = String(out).match(isWin ? /time[=<](\d+(?:\.\d+)?)ms/gi : /time=(\d+(?:\.\d+)?)\s*ms/g) || [];
      const vals = rtts.map((s) => Number(String(s).split(/[=<]/)[1].replace(/[^\d.]/g, ''))).filter((n) => Number.isFinite(n));
      const loss = /(\d+)%/.exec(/loss|丢失|packet loss/i.test(out) ? out : '');
      resolve({
        ok: code === 0 && vals.length > 0,
        ms: vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : Number(process.hrtime.bigint() - started) / 1e6,
        packets: vals.length,
        lossPct: loss ? Number(loss[1]) : (vals.length ? 0 : 100),
        error: vals.length ? '' : ('ping 无响应（退出码 ' + code + '）'),
      });
    });
  });
}

async function dnsProbe(host, timeoutMs) {
  const started = process.hrtime.bigint();
  try {
    const recs = await Promise.race([
      dns.resolve4(host).catch(() => dns.resolve6(host)),
      new Promise((_, rej) => { const t = setTimeout(() => rej(new Error('解析超时')), timeoutMs); if (t.unref) t.unref(); }),
    ]);
    const list = Array.isArray(recs) ? recs : [];
    return { ok: list.length > 0, ms: Number(process.hrtime.bigint() - started) / 1e6, code: null, addresses: list.slice(0, 8), error: list.length ? '' : '无解析记录' };
  } catch (e) {
    return { ok: false, ms: Number(process.hrtime.bigint() - started) / 1e6, error: e.code || e.message };
  }
}

async function httpProbe(task) {
  if (!httpFetch) return { ok: false, ms: 0, error: '运行环境缺少 fetch（需要 Node 18+）' };
  const started = process.hrtime.bigint();
  try {
    const res = await httpFetch(task.url, {
      method: task.method === 'HEAD' ? 'HEAD' : task.method,
      redirect: 'follow',
      headers: { 'user-agent': 'CloudMonitor-Probe/1.0', 'accept': '*/*' },
      signal: AbortSignal.timeout(task.timeoutMs),
    });
    let body = '';
    if (task.keyword && res.text) { try { body = (await res.text()).slice(0, 200000); } catch (_) {} }
    const ms = Number(process.hrtime.bigint() - started) / 1e6;
    const codeOk = statusAllowed(task.expectStatus, res.status);
    const keyOk = !task.keyword || body.includes(task.keyword);
    return {
      ok: codeOk && keyOk, ms, code: res.status,
      error: !codeOk ? `状态码 ${res.status}（期望 ${task.expectStatus || '2xx/3xx'}）` : !keyOk ? `响应中未找到关键字「${task.keyword}」` : '',
    };
  } catch (e) {
    return { ok: false, ms: Number(process.hrtime.bigint() - started) / 1e6, error: (e.name === 'TimeoutError' ? '请求超时' : e.cause && e.cause.code) || e.message };
  }
}

async function probe(task) {
  switch (task.type) {
    case 'http': return httpProbe(task);
    case 'ssl': return tlsProbe(task.host, task.port || 443, task.timeoutMs);
    case 'tcp': return tcpProbe(task.host, task.port, task.timeoutMs);
    case 'ping': return icmpProbe(task.host, task.timeoutMs);
    case 'dns': return dnsProbe(task.host, task.timeoutMs);
    default: return { ok: false, error: '未知拨测类型：' + task.type };
  }
}

/* ---------------- record + rollup ---------------- */

function rollupAdd(taskId, ok, ms, ts) {
  const r = S.readJson(ROLLUP, { days: {} });
  const day = S.dayKey(ts);
  const slot = r.days[day] || {};
  const t = slot[taskId] || { n: 0, down: 0, msSum: 0 };
  t.n += 1;
  if (!ok) t.down += 1;
  t.msSum += ms || 0;
  slot[taskId] = t;
  r.days[day] = slot;
  const keys = Object.keys(r.days).sort();
  while (keys.length > 120) delete r.days[keys.shift()];
  S.writeJson(ROLLUP, r);
}

function describe(task, res) {
  const bits = [];
  if (res.code != null) bits.push('状态码 ' + res.code);
  if (res.certDays != null) bits.push('证书剩余 ' + res.certDays + ' 天');
  if (res.lossPct != null) bits.push('丢包 ' + res.lossPct + '%');
  if (Array.isArray(res.addresses) && res.addresses.length) bits.push(res.addresses.slice(0, 3).join(','));
  bits.push('耗时 ' + Math.round(res.ms) + ' ms');
  return bits.join('，');
}

async function record(task, res, opts) {
  const ts = S.now();
  S.appendLine(HISTORY, { ts, id: task.id, ok: !!res.ok, ms: Math.round(res.ms || 0), code: res.code == null ? null : res.code, error: res.error || '', certDays: res.certDays == null ? null : res.certDays });
  S.trimLineFile(HISTORY, 8 * 1024 * 1024);
  rollupAdd(task.id, !!res.ok, res.ms || 0, ts);

  const st = states();
  st.tasks = st.tasks || {};
  const p = st.tasks[task.id] || { lastOk: true, consecFails: 0, consecOk: 0, downSince: 0, prevKnown: false };
  const wasDown = p.consecFails >= (task.failThreshold || 2) && !p.lastOk;
  if (res.ok) {
    p.consecOk += 1; p.consecFails = 0;
  } else {
    p.consecFails += 1; p.consecOk = 0;
  }
  p.lastOk = !!res.ok;
  p.lastTs = ts;
  p.lastMs = Math.round(res.ms || 0);
  p.lastCode = res.code == null ? null : res.code;
  p.lastError = res.error || '';
  if (p.lastCertDays == null && res.certDays != null) p.lastCertDays = res.certDays;
  if (res.certDays != null) p.lastCertDays = res.certDays;
  if (!res.ok && !p.downSince) p.downSince = ts;
  if (res.ok) p.downSince = 0;

  const downNow = !p.lastOk && p.consecFails >= (task.failThreshold || 2);
  const transitions = [];
  if (downNow && !wasDown && p.prevKnown) transitions.push('down');
  if (!downNow && wasDown && p.prevKnown) transitions.push('up');
  if (!p.prevKnown && downNow) transitions.push('down');
  p.prevKnown = true;
  st.tasks[task.id] = p;
  S.writeJson(STATE, st);

  if (transitions.length && !(opts && opts.silent)) {
    const target = task.url || `${task.host}:${task.port || 443}`;
    const alerting = S.soft('./alerting');
    S.appendLine(EVENTS, { ts, id: task.id, name: task.name, state: transitions[0], error: res.error || '', ms: p.lastMs });
    S.trimLineFile(EVENTS, 4 * 1024 * 1024);
    if (alerting && typeof alerting.raiseEvent === 'function') {
      const down = transitions[0] === 'down';
      await alerting.raiseEvent({
        key: 'probe:' + task.id + (down ? '' : ':recovered'),
        level: down ? 'bad' : 'ok',
        source: 'uptime',
        cooldownSec: down ? 300 : 0,
        channelIds: task.channelIds,
        title: `${task.name} ${down ? '不可用' : '已恢复'}`,
        text: `${TYPES[task.type] || task.type} ${target} ${down ? '失败：' + (res.error || '无响应') : '恢复正常'}（连续失败 ${p.consecFails} 次，阈值 ${task.failThreshold}）`,
        fields: [{ k: '类型', v: TYPES[task.type] || task.type }, { k: '目标', v: target }, { k: '结果', v: describe(task, res) }, { k: '不可用时长', v: down && p.downSince ? Math.round((ts - p.downSince) / 1000) + ' 秒' : '—' }],
      });
    }
  }
  return { task, state: p, transition: transitions[0] || null };
}

async function runTask(task, opts) {
  const res = await probe(task);
  return record(task, res, opts);
}

/* ---------------- scheduler ---------------- */

let timer = null;
let sweeping = false;
const inflight = new Set();

function dueTasks() {
  const st = states().tasks || {};
  return list().filter((t) => {
    if (t.enabled === false) return false;
    if (inflight.has(t.id)) return false;
    const last = st[t.id] && st[t.id].lastTs ? st[t.id].lastTs : 0;
    return S.now() - last >= Math.max(10, t.intervalSec) * 1000;
  });
}

async function sweep(opts) {
  if (sweeping && !(opts && opts.force)) return { ok: true, skipped: true };
  sweeping = true;
  try {
    const tasks = opts && opts.force ? list().filter((t) => t.enabled !== false) : dueTasks();
    const ran = [];
    for (const t of tasks) {
      inflight.add(t.id);
      try { ran.push(await runTask(t, opts)); }
      catch (e) { S.util.logErr && S.util.logErr('probe failed:', t.id, e.message); }
      inflight.delete(t.id);
    }
    return { ok: true, ran: ran.length };
  } finally { sweeping = false; }
}

function start(intervalMs) {
  stop();
  const ms = Math.max(3000, intervalMs || 10000);
  timer = setInterval(() => { sweep().catch(() => {}); }, ms);
  if (timer.unref) timer.unref();
  setTimeout(() => sweep().catch(() => {}), 3000).unref?.();
}
function stop() { if (timer) { clearInterval(timer); timer = null; } }

/* ---------------- queries ---------------- */

function withState() {
  const st = states().tasks || {};
  return list().map((t) => {
    const s = st[t.id] || {};
    const down = !s.lastOk && (s.consecFails || 0) >= (t.failThreshold || 2);
    return Object.assign({}, t, {
      state: down ? 'down' : s.lastOk === false ? 'flapping' : (s.lastTs ? 'up' : 'pending'),
      lastOk: s.lastOk === undefined ? null : !!s.lastOk,
      lastTs: s.lastTs || 0, lastMs: s.lastMs || 0, lastCode: s.lastCode == null ? null : s.lastCode,
      lastError: s.lastError || '', consecFails: s.consecFails || 0, consecOk: s.consecOk || 0,
      downSince: s.downSince || 0, certDays: s.lastCertDays == null ? null : s.lastCertDays,
    });
  });
}

function history(id, limit) {
  return S.readLines(HISTORY, Math.max(1, Math.min(2000, Number(limit) || 300)) * 3)
    .filter((r) => !id || r.id === id)
    .slice(-(Number(limit) || 300));
}

function sla(days) {
  const n = Math.max(1, Math.min(120, Number(days) || 30));
  const r = S.readJson(ROLLUP, { days: {} });
  const from = S.dayKey(S.now() - (n - 1) * 86400000);
  const tasks = list();
  const perTask = tasks.map((t) => {
    let checks = 0, down = 0, msSum = 0, daysSeen = 0;
    const daily = [];
    for (let i = 0; i < n; i++) {
      const key = S.dayKey(S.now() - (n - 1 - i) * 86400000);
      const slot = (r.days[key] || {})[t.id];
      if (slot && slot.n) {
        checks += slot.n; down += slot.down; msSum += slot.msSum; daysSeen += 1;
        daily.push({ date: key, checks: slot.n, down: slot.down, availability: Number((100 * (1 - slot.down / slot.n)).toFixed(2)), avgMs: Math.round(slot.msSum / slot.n) });
      } else daily.push({ date: key, checks: 0, down: 0, availability: null, avgMs: null });
    }
    return {
      id: t.id, name: t.name, type: t.type, url: t.url || `${t.host}${t.port ? ':' + t.port : ''}`, group: t.group,
      checks, downChecks: down, availability: checks ? Number((100 * (1 - down / checks)).toFixed(3)) : null,
      avgMs: checks ? Math.round(msSum / checks) : null, daysSeen,
      downtimeSec: Math.round((states().tasks[t.id] || {}).downSince ? (S.now() - (states().tasks[t.id] || {}).downSince) / 1000 : 0),
      daily,
    };
  });
  const scored = perTask.filter((x) => x.availability != null);
  return {
    ok: true, days: n, from, generatedAt: S.now(), host: os.hostname(),
    overall: scored.length ? Number((scored.reduce((a, b) => a + b.availability, 0) / scored.length).toFixed(3)) : null,
    worst: scored.slice().sort((a, b) => (a.availability - b.availability)).slice(0, 3),
    tasks: perTask,
  };
}

function summary() {
  const w = withState();
  const down = w.filter((t) => t.state === 'down');
  return {
    total: w.length, enabled: w.filter((t) => t.enabled !== false).length,
    up: w.filter((t) => t.state === 'up').length, down: down.length, flapping: w.filter((t) => t.state === 'flapping').length,
    pending: w.filter((t) => t.state === 'pending').length,
    certExpiring: w.filter((t) => t.certDays != null && t.certDays < (t.certWarnDays || 14)).length,
    downNames: down.map((t) => t.name),
    sla30: (() => { const s = sla(30); return s.overall; })(),
  };
}

/* ---------------- routes ---------------- */

function register(api, wrap, audited) {
  api.get('/ops/uptime/tasks', wrap(async (req, res) => res.json({
    ok: true, types: Object.entries(TYPES).map(([k, v]) => ({ key: k, label: v })),
    items: withState(), summary: summary(), engine: { running: !!timer, sweeping },
  })));
  api.get('/ops/uptime/history', wrap(async (req, res) => res.json({
    ok: true, id: req.query.id || null, items: history(req.query.id, Number(req.query.limit) || 300), events: S.readLines(EVENTS, Number(req.query.events) || 100),
  })));
  api.get('/ops/uptime/sla', wrap(async (req, res) => res.json(sla(req.query.days))));
  api.post('/ops/uptime/task/save', audited('ops.uptime.save', async (req) => {
    const b = req.body || {};
    const items = list();
    const existing = b.id ? items.find((t) => t.id === b.id) : null;
    if (b.id && !existing) { const e = new Error('拨测任务不存在：' + b.id); e.status = 404; throw e; }
    const t = normalizeTask(b, existing);
    if (existing) items.splice(items.indexOf(existing), 1, t); else items.push(t);
    S.writeJson(TASKS, { items });
    /* 配置变更后重置探测状态：不要让旧失败次数影响新配置 */
    const st = states();
    if (st.tasks && st.tasks[t.id]) { delete st.tasks[t.id]; S.writeJson(STATE, st); }
    return { ok: true, task: t };
  }));
  api.post('/ops/uptime/task/delete', audited('ops.uptime.delete', async (req) => {
    const items = list();
    const idx = items.findIndex((t) => t.id === (req.body || {}).id);
    if (idx < 0) { const e = new Error('拨测任务不存在：' + (req.body || {}).id); e.status = 404; throw e; }
    const [gone] = items.splice(idx, 1);
    S.writeJson(TASKS, { items });
    const st = states(); if (st.tasks) delete st.tasks[gone.id];
    S.writeJson(STATE, st);
    return { ok: true, removed: gone.id };
  }));
  api.post('/ops/uptime/task/toggle', audited('ops.uptime.toggle', async (req) => {
    const b = req.body || {};
    const items = list();
    const t = items.find((x) => x.id === b.id);
    if (!t) { const e = new Error('拨测任务不存在：' + b.id); e.status = 404; throw e; }
    t.enabled = b.enabled === undefined ? !t.enabled : !!b.enabled;
    t.updatedAt = S.now();
    S.writeJson(TASKS, { items });
    return { ok: true, task: t };
  }));
  api.post('/ops/uptime/task/run', audited('ops.uptime.run', async (req) => {
    const b = req.body || {};
    const t = list().find((x) => x.id === b.id);
    if (!t) { const e = new Error('拨测任务不存在：' + b.id); e.status = 404; throw e; }
    const r = await runTask(t, { silent: b.silent === true });
    return { ok: true, result: r, task: withState().find((x) => x.id === t.id) };
  }));
  api.post('/ops/uptime/run', audited('ops.uptime.sweep', async (req) => sweep({ force: true, silent: !!(req.body || {}).silent })));
}

module.exports = {
  TYPES, list, register, start, stop, sweep, runTask, probe, sla, summary, withState, history,
  normalizeTask, statusAllowed, record, TASKS, STATE, ROLLUP, HISTORY, EVENTS,
};
