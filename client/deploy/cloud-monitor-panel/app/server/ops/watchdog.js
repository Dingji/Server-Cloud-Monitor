'use strict';
/*
 * Service watchdog (服务看护自愈) — the "进程守护" of 宝塔 / the health actions of
 * 1Panel: poll a systemd unit, a process pattern or a TCP port; when it is down,
 * restart it (bounded), log every attempt, and escalate through the alerting
 * module after repeated failures.
 */
const net = require('net');
const S = require('./store');
const ex = require('./exec');
const alerting = require('./alerting');

const FILE = 'watchdog.json';
const STATE = 'watchdog_state.json';
const EVENTS = 'watchdog_events.jsonl';
const svc = S.soft('../svc');

const KINDS = { systemd: 'systemd 服务', process: '进程（pgrep 匹配）', port: 'TCP 端口探活' };
const DEFAULTS = { intervalSec: 30, timeoutMs: 8000, maxRestarts: 3, windowMin: 30, cooldownSec: 120 };

const list = () => S.readJson(FILE, { items: [] }).items;
const save = (items) => S.writeJson(FILE, { items, updatedAt: S.now() });
const states = () => S.readJson(STATE, { items: {} });

function normalize(b, existing) {
  const pick = (k, d) => (b[k] !== undefined && b[k] !== null && b[k] !== '' ? b[k] : (existing ? existing[k] : d));
  const kind = KINDS[pick('kind', 'systemd')] ? pick('kind', 'systemd') : 'systemd';
  const name = String(pick('name', '')).trim().slice(0, 60);
  if (!name) { const e = new Error('看护项名称不能为空'); e.status = 400; throw e; }
  const w = { ...DEFAULTS, ...((existing && existing.opts) || {}) };
  const e = {
    id: (existing && existing.id) || S.genId('w'),
    name,
    kind,
    unit: String(pick('unit', '')).trim().slice(0, 80),
    match: String(pick('match', '')).trim().slice(0, 120),
    host: String(pick('host', '127.0.0.1')).trim().slice(0, 80) || '127.0.0.1',
    port: Math.round(S.clampNum(pick('port', 0), 0, 65535, existing ? existing.port : 0)),
    startCmd: String(pick('startCmd', '')).trim().slice(0, 500),
    enabled: pick('enabled', existing ? existing.enabled !== false : true) !== false,
    restart: pick('restart', existing ? existing.restart !== false : true) !== false,
    opts: {
      intervalSec: Math.round(S.clampNum(pick('intervalSec', w.intervalSec), 5, 3600, w.intervalSec)),
      timeoutMs: Math.round(S.clampNum(pick('timeoutMs', w.timeoutMs), 1000, 60000, w.timeoutMs)),
      maxRestarts: Math.round(S.clampNum(pick('maxRestarts', w.maxRestarts), 1, 20, w.maxRestarts)),
      windowMin: Math.round(S.clampNum(pick('windowMin', w.windowMin), 1, 1440, w.windowMin)),
      cooldownSec: Math.round(S.clampNum(pick('cooldownSec', w.cooldownSec), 30, 86400, w.cooldownSec)),
    },
    channelIds: Array.isArray(pick('channelIds', [])) ? pick('channelIds', []).map(String).slice(0, 10) : (existing && existing.channelIds) || [],
    note: String(pick('note', '')).slice(0, 200),
    createdAt: (existing && existing.createdAt) || S.now(),
    updatedAt: S.now(),
  };
  if (e.kind === 'systemd' && !e.unit) { const err = new Error('systemd 看护需要单元名，如 nginx.service'); err.status = 400; throw err; }
  if (e.kind === 'systemd' && !/^[\w:@.\\-]+$/.test(e.unit)) { const err = new Error('单元名字符不合法'); err.status = 400; throw err; }
  if (e.kind === 'process' && !e.match) { const err = new Error('进程看护需要匹配关键字（pgrep -f）'); err.status = 400; throw err; }
  if (e.kind === 'port' && !(e.port > 0)) { const err = new Error('端口看护需要端口号'); err.status = 400; throw err; }
  return e;
}

function portOpen(host, port, timeoutMs) {
  return new Promise((resolve) => {
    const sock = net.connect({ host, port });
    let settled = false;
    const done = (ok, error) => { if (!settled) { settled = true; try { sock.destroy(); } catch (_) {} resolve({ ok, error: error || '' }); } };
    sock.setTimeout(Math.max(500, timeoutMs));
    sock.once('connect', () => done(true));
    sock.once('timeout', () => done(false, '连接超时'));
    sock.once('error', (e) => done(false, e.code || e.message));
  });
}

async function probe(e) {
  if (e.kind === 'port') return portOpen(e.host, e.port, e.opts.timeoutMs);
  if (e.kind === 'systemd') {
    const r = await ex.run(`systemctl is-active ${S.shq(e.unit)} 2>&1 | head -1`, { timeout: e.opts.timeoutMs });
    const out = (r.stdout || '').trim();
    return { ok: out === 'active', error: out ? '' : '读不到状态', status: out || 'unknown' };
  }
  const r = await ex.run(`pgrep -fc ${S.shq(e.match)} 2>/dev/null || echo 0`, { timeout: e.opts.timeoutMs });
  const n = Number((r.stdout || '0').trim().split('\n').pop()) || 0;
  return { ok: n > 0, pids: n, error: n ? '' : `没有匹配「${e.match}」的进程` };
}

async function restartCmd(e) {
  if (e.kind === 'systemd') {
    let cmd = `systemctl restart ${S.shq(e.unit)} 2>&1 | tail -5`;
    if (svc && typeof svc.svcCmd === 'function') {
      try { const m = await svc.detect(); if (!m.systemd && m.serviceBin) cmd = `${svc.svcCmd('restart', e.unit)} 2>&1 | tail -5`; } catch (_) {}
    }
    return cmd;
  }
  if (e.kind === 'process') {
    if (!e.startCmd) return '';
    return `${e.startCmd} 2>&1 | tail -8`;
  }
  return e.startCmd ? `${e.startCmd} 2>&1 | tail -8` : '';
}

function recentRestarts(e, st) {
  const since = S.now() - e.opts.windowMin * 60000;
  return ((st[e.id] || {}).restarts || []).filter((t) => t > since);
}

function log(e, event, detail) {
  S.appendLine(EVENTS, { ts: S.now(), id: e.id, name: e.name, kind: e.kind, event, detail: String(detail || '').slice(0, 400) });
  S.trimLineFile(EVENTS, 4 * 1024 * 1024);
}

async function checkOne(e, st) {
  const started = S.now();
  const p = await probe(e);
  const s = st[e.id] || { down: false, restarts: [], lastAlert: 0, fails: 0 };
  let action = null;

  if (p.ok) {
    if (s.down) {
      await alerting.raiseEvent({
        key: 'wd:' + e.id + ':recovered', level: 'ok', source: 'watchdog', cooldownSec: 0, channelIds: e.channelIds,
        title: `${e.name} 已恢复`, text: `${KINDS[e.kind]} 探活恢复正常（中断约 ${Math.round((S.now() - (s.downSince || S.now())) / 1000)} 秒）`,
      });
      log(e, 'recovered', p.status || `pids=${p.pids}`);
    }
    s.down = false; s.downSince = 0; s.fails = 0; s.lastOk = S.now(); s.lastMs = S.now() - started; s.lastStatus = p.status || (p.pids != null ? `${p.pids} 个进程` : 'ok');
    st[e.id] = s;
    return { id: e.id, ok: true, action: null, state: s };
  }

  s.lastBad = S.now();
  s.lastError = p.error || p.status || '不可用';
  if (!s.down) { s.down = true; s.downSince = S.now(); log(e, 'down', s.lastError); }

  if (!e.restart) {
    s.fails++;
    st[e.id] = s;
    return { id: e.id, ok: false, action: 'skip-restart', state: s };
  }

  s.restarts = recentRestarts(e, st);
  if (s.restarts.length >= e.opts.maxRestarts) {
    if (S.now() - (s.lastAlert || 0) >= e.opts.cooldownSec * 1000) {
      s.lastAlert = S.now();
      await alerting.raiseEvent({
        key: 'wd:' + e.id + ':givingup', level: 'bad', source: 'watchdog', cooldownSec: 0, channelIds: e.channelIds,
        title: `${e.name} 反复不可用，已达重启上限`,
        text: `${e.opts.windowMin} 分钟内已尝试重启 ${s.restarts.length} 次仍失败：${s.lastError}。需要人工介入。`,
      });
      log(e, 'giveup', `${s.restarts.length} 次重启仍失败`);
    }
    st[e.id] = s;
    return { id: e.id, ok: false, action: 'giveup', state: s };
  }

  const cmd = await restartCmd(e);
  if (!cmd) {
    if (S.now() - (s.lastAlert || 0) >= e.opts.cooldownSec * 1000) {
      s.lastAlert = S.now();
      await alerting.raiseEvent({
        key: 'wd:' + e.id + ':nostart', level: 'bad', source: 'watchdog', cooldownSec: 0, channelIds: e.channelIds,
        title: `${e.name} 不可用且未配置启动命令`, text: `探测失败：${s.lastError}。请在看护项里补上启动命令，或改为只告警不自愈。`,
      });
      log(e, 'no-start-cmd', s.lastError);
    }
    st[e.id] = s;
    return { id: e.id, ok: false, action: 'no-start-cmd', state: s };
  }

  const r = await ex.run(cmd, { timeout: Math.max(30000, e.opts.timeoutMs * 4) });
  s.restarts.push(S.now());
  s.lastRestart = S.now();
  s.restartOutput = (r.stdout + ' ' + r.stderr).trim().slice(0, 300);
  log(e, 'restart', s.restartOutput);
  const after = await probe(e);
  s.down = !after.ok;
  if (after.ok) s.downSince = 0;
  if (S.now() - (s.lastAlert || 0) >= e.opts.cooldownSec * 1000) {
    s.lastAlert = S.now();
    await alerting.raiseEvent({
      key: 'wd:' + e.id + ':down', level: after.ok ? 'warn' : 'bad', source: 'watchdog', cooldownSec: 0, channelIds: e.channelIds,
      title: `${e.name} 不可用${after.ok ? '，已自动拉起' : '，自动拉起后仍失败'}`,
      text: `${KINDS[e.kind]} 探测失败：${s.lastError}；执行重启命令后${after.ok ? '已恢复' : '仍不可用'}。第 ${s.restarts.length} 次重启（${e.opts.windowMin} 分钟内上限 ${e.opts.maxRestarts} 次）。`,
      fields: [{ k: '重启输出', v: s.restartOutput || '（无）' }],
    });
  }
  st[e.id] = s;
  return { id: e.id, ok: after.ok, action: 'restarted', output: s.restartOutput, state: s };
}

let timer = null;
let running = false;

async function tick() {
  if (running) return { ok: true, skipped: true };
  running = true;
  const st = states();
  const results = [];
  try {
    const now = S.now();
    for (const e of list()) {
      if (!e.enabled) continue;
      const s = st[e.id] || {};
      if (s.lastCheck && now - s.lastCheck < Math.max(5, e.opts.intervalSec) * 1000) continue;
      s.lastCheck = now;
      st[e.id] = s;
      try { results.push(await checkOne(e, st)); }
      catch (err) { results.push({ id: e.id, ok: false, action: 'error', error: err.message }); log(e, 'error', err.message); }
    }
    S.writeJson(STATE, st);
  } finally { running = false; }
  return { ok: true, checked: results.length, results };
}

function start(intervalMs) {
  stop();
  const ms = Math.max(5000, intervalMs || 15000);
  timer = setInterval(() => { tick().catch(() => {}); }, ms);
  if (timer.unref) timer.unref();
}
function stop() { if (timer) { clearInterval(timer); timer = null; } }

function view() {
  const st = states();
  return list().map((e) => {
    const s = st[e.id] || {};
    return Object.assign({}, e, {
      status: !e.enabled ? 'disabled' : s.down ? 'down' : (s.lastOk ? 'ok' : 'pending'),
      lastCheck: s.lastCheck || 0, lastOk: s.lastOk || 0, lastBad: s.lastBad || 0,
      lastError: s.lastError || '', lastStatus: s.lastStatus || '',
      restarts: recentRestarts(e, st).length, lastRestart: s.lastRestart || 0, restartOutput: s.restartOutput || '',
    });
  });
}

function stats() {
  const v = view();
  const st = states();
  let restarts24 = 0;
  for (const e of v) {
    const s = st[e.id] || {};
    restarts24 += ((s.restarts || []).filter((t) => t > S.now() - 86400000)).length;
  }
  return {
    total: v.length, enabled: v.filter((x) => x.enabled).length, down: v.filter((x) => x.status === 'down').length,
    failing: v.filter((x) => x.status === 'down' && x.restarts > 0).length, restarts24,
  };
}

function events(limit) { return S.readLines(EVENTS, limit || 100); }

function register(api, wrap, audited) {
  api.get('/ops/watchdog', wrap(async (req, res) => res.json({
    ok: true, items: view(), kinds: Object.entries(KINDS).map(([k, v]) => ({ key: k, label: v })),
    stats: stats(), events: events(Number(req.query.events) || 40), engine: { running: !!timer, ticking: running }, defaults: DEFAULTS,
  })));
  api.post('/ops/watchdog/save', audited('ops.watchdog.save', async (req) => {
    const b = req.body || {};
    const items = list();
    const existing = b.id ? items.find((x) => x.id === b.id) : null;
    if (b.id && !existing) { const e = new Error('看护项不存在：' + b.id); e.status = 404; throw e; }
    const e = normalize(b, existing);
    if (existing) items.splice(items.indexOf(existing), 1, e); else items.push(e);
    save(items);
    return { ok: true, entry: view().find((x) => x.id === e.id) };
  }));
  api.post('/ops/watchdog/delete', audited('ops.watchdog.delete', async (req) => {
    const items = list();
    const idx = items.findIndex((x) => x.id === (req.body || {}).id);
    if (idx < 0) { const e = new Error('看护项不存在：' + (req.body || {}).id); e.status = 404; throw e; }
    const [gone] = items.splice(idx, 1);
    save(items);
    const st = states(); delete st[gone.id]; S.writeJson(STATE, st);
    return { ok: true, removed: gone.id };
  }));
  api.post('/ops/watchdog/toggle', audited('ops.watchdog.toggle', async (req) => {
    const b = req.body || {};
    const items = list();
    const e = items.find((x) => x.id === b.id);
    if (!e) { const err = new Error('看护项不存在：' + b.id); err.status = 404; throw err; }
    e.enabled = b.enabled === undefined ? !e.enabled : !!b.enabled;
    e.updatedAt = S.now();
    save(items);
    return { ok: true, entry: e };
  }));
  api.post('/ops/watchdog/run', audited('ops.watchdog.run', async (req) => {
    const b = req.body || {};
    if (!b.id) return tick();
    const e = list().find((x) => x.id === b.id);
    if (!e) { const err = new Error('看护项不存在：' + b.id); err.status = 404; throw err; }
    const st = states();
    const r = await checkOne(e, st);
    S.writeJson(STATE, st);
    return { ok: true, result: r, entry: view().find((x) => x.id === e.id) };
  }));
  api.post('/ops/watchdog/reset', audited('ops.watchdog.reset', async (req) => {
    const b = req.body || {};
    const st = states();
    if (b.id) { st[b.id] = { restarts: [] }; } else { for (const k of Object.keys(st)) st[k] = { restarts: [] }; }
    S.writeJson(STATE, st);
    return { ok: true, cleared: b.id || 'all' };
  }));
}

module.exports = { register, tick, start, stop, view, stats, events, list, normalize, probe, checkOne, restartCmd, KINDS, DEFAULTS, FILE, STATE, EVENTS };
