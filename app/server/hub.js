'use strict';
/*
 * WebSocket hub:
 *   /ws   - metrics stream (history replay on connect), log follow channels
 *   /term - web terminal sessions
 */
const url = require('url');
const cp = require('child_process');
const { WebSocketServer } = require('ws');
const collectors = require('./collectors');
const auth = require('./auth');
const config = require('./config');
const { resolveSource, listSources } = require('./logsources');
const shells = require('./shell');
const { log, logErr } = require('./util');

const HISTORY_MAX = 150;
const MAX_FOLLOWS = 4;

let wss = null;
let metricsTimer = null;
const history = [];           // recent metric points (ws replay)
const metricsClients = new Set();
const follows = new Map();    // sourceId -> { child, subs:Set, buf:string }

/* ============ long-term history (downsampled) ============ */
const metricsStore = require('./metricsstore');
const histRecent = [];                 // tick-rate, last 10 min
const histLong = [];                   // 30s samples, up to 24h
let lastLongSample = 0;
let lastCompact = null;
let rootDiskPct = null;

function compactSample(m) {
  let rx = 0, tx = 0;
  for (const [n, v] of Object.entries(m.net.ifaces)) if (n !== 'lo') { rx += v.rxBps; tx += v.txBps; }
  return {
    t: Math.floor(m.ts / 1000),
    c: +(m.cpu.totalPct || 0).toFixed(1),
    m: +m.memory.actualUsedPct.toFixed(1),
    s: +m.memory.swapPct.toFixed(1),
    rx: Math.round(rx), tx: Math.round(tx),
    d: rootDiskPct == null ? 0 : rootDiskPct,
  };
}

function getHistory(range) {
  const secs = { '10m': 600, '1h': 3600, '6h': 21600, '24h': 86400 }[range] || 600;
  const cutoff = Math.floor(Date.now() / 1000) - secs;
  let points;
  if (secs <= 900) points = histRecent.filter((p) => p.t >= cutoff);
  else points = histLong.filter((p) => p.t >= cutoff);
  if (lastCompact && (!points.length || points[points.length - 1].t < lastCompact.t)) points = points.concat([lastCompact]);
  return { range, points };
}

/* ============ alerts ============ */
const alertsBuf = [];
const alertCooldown = new Map(); // key -> last fired ts
let lastAlertEval = 0;

function fireAlert(key, level, message) {
  const cfg = config.get().alerts || {};
  const cd = (cfg.cooldownMin || 10) * 60000;
  const last = alertCooldown.get(key) || 0;
  if (Date.now() - last < cd) return;
  alertCooldown.set(key, Date.now());
  const alert = { key, level, message, ts: Date.now() };
  alertsBuf.unshift(alert);
  if (alertsBuf.length > 200) alertsBuf.pop();
  broadcast({ type: 'alert', alert });
  log('ALERT:', level, message);
}

function evaluateAlerts(m) {
  const cfg = config.get().alerts || {};
  if (cfg.enabled === false) return;
  if (Date.now() - lastAlertEval < 15000) return;
  lastAlertEval = Date.now();
  const host = '本机';
  if (cfg.cpu && m.cpu.totalPct >= cfg.cpu) fireAlert('cpu', 'warn', `${host} CPU 使用率达到 ${m.cpu.totalPct.toFixed(0)}%（阈值 ${cfg.cpu}%）`);
  if (cfg.mem && m.memory.actualUsedPct >= cfg.mem) fireAlert('mem', 'warn', `${host} 内存使用率达到 ${m.memory.actualUsedPct.toFixed(0)}%（阈值 ${cfg.mem}%）`);
  if (cfg.disk && rootDiskPct != null && rootDiskPct >= cfg.disk) fireAlert('disk', 'bad', `${host} 磁盘使用率达到 ${rootDiskPct}%（阈值 ${cfg.disk}%）`);
  if (m.memory.swapTotal && m.memory.swapPct >= 80) fireAlert('swap', 'bad', `${host} Swap 使用率达到 ${m.memory.swapPct.toFixed(0)}%，内存严重不足`);
}

function getAlerts() { return alertsBuf.slice(0, 100); }

/* ============ metrics scheduler ============ */
let tickCount = 0;
let running = false;
let currentInterval = 0;

async function tick() {
  if (running) return; // skip if previous tick is slow
  running = true;
  try {
    const m = await collectors.getMetrics();
    tickCount++;
    let top = null;
    if (tickCount % 3 === 0) {
      try { top = (await collectors.getProcesses({ limit: 8 })).list; } catch (_) {}
    }
    let disks = null;
    if (tickCount % 5 === 0) {
      try { disks = await collectors.getDisks(); } catch (_) {}
    }
    const point = m;
    history.push(point);
    if (history.length > HISTORY_MAX) history.shift();

    /* long-term history + alerts */
    try {
      const compact = compactSample(m);
      lastCompact = compact;
      histRecent.push(compact);
      while (histRecent.length > 600) histRecent.shift();
      if (Date.now() - lastLongSample >= 30000) {
        lastLongSample = Date.now();
        histLong.push(compact);
        while (histLong.length > 3000) histLong.shift();
      }
      /* 落盘到 SQLite（内部 30 秒节流），面板重启后历史仍在 */
      try { metricsStore.record(compact); } catch (_) {}
      evaluateAlerts(m);
    } catch (e) { logErr('history/alert error:', e.message); }

    broadcast({ type: 'metrics', data: point, top, disks });
    if (disks && disks.mounts) {
      const root = disks.mounts.find((x) => x.mount === '/') || disks.mounts[0];
      if (root) rootDiskPct = root.pct;
    }
  } catch (e) {
    logErr('metrics tick error:', e.message);
  } finally {
    running = false;
  }
}

function startScheduler() {
  const ms = config.get().refreshMs || 2000;
  if (metricsTimer) clearInterval(metricsTimer);
  currentInterval = ms;
  metricsTimer = setInterval(tick, ms);
  tick();
}

/* ============ broadcast ============ */
function broadcast(obj) {
  const s = JSON.stringify(obj);
  for (const ws of metricsClients) {
    if (ws.readyState === 1) {
      try { ws.send(s); } catch (_) {}
    }
  }
}

function send(ws, obj) {
  if (ws.readyState === 1) { try { ws.send(JSON.stringify(obj)); } catch (_) {} }
}

/* ============ log follow ============ */
function subscribeLogs(ws, sourceId) {
  const src = resolveSource(sourceId);
  if (!src) return send(ws, { type: 'log-error', source: sourceId, message: '未知日志源' });
  let f = follows.get(sourceId);
  if (!f) {
    if (follows.size >= MAX_FOLLOWS) return send(ws, { type: 'log-error', source: sourceId, message: `并发日志流已达上限 (${MAX_FOLLOWS})` });
    let child;
    try { child = cp.exec(src.follow, { shell: '/bin/bash', timeout: 0, maxBuffer: 8 * 1024 * 1024 }); }
    catch (e) { return send(ws, { type: 'log-error', source: sourceId, message: e.message }); }
    f = { child, subs: new Set(), buf: '' };
    follows.set(sourceId, f);
    child.stdout.on('data', (d) => {
      f.buf += d.toString();
      const lines = f.buf.split('\n');
      f.buf = lines.pop();
      if (f.buf.length > 64 * 1024) f.buf = f.buf.slice(-64 * 1024);
      const payload = JSON.stringify({ type: 'log', source: sourceId, lines });
      for (const s of f.subs) if (s.readyState === 1) { try { s.send(payload); } catch (_) {} }
    });
    child.stderr.on('data', () => {});
    child.on('exit', () => {
      for (const s of f.subs) send(s, { type: 'log-end', source: sourceId });
      cleanupFollow(sourceId);
    });
    log(`log follow started: ${sourceId}`);
  }
  f.subs.add(ws);
  ws._logSubs = ws._logSubs || new Set();
  ws._logSubs.add(sourceId);
  send(ws, { type: 'log-start', source: sourceId });
}

function unsubscribeLogs(ws, sourceId) {
  const f = follows.get(sourceId);
  if (f) f.subs.delete(ws);
  if (ws._logSubs) ws._logSubs.delete(sourceId);
  if (f && f.subs.size === 0) cleanupFollow(sourceId);
}

function cleanupFollow(sourceId) {
  const f = follows.get(sourceId);
  if (!f) return;
  try { f.child.kill('SIGKILL'); } catch (_) {}
  follows.delete(sourceId);
  log(`log follow stopped: ${sourceId}`);
}

/* ============ terminal ============ */
function handleTermSocket(ws, query) {
  let session = null;
  let clientId = null;

  const onSessionData = (d) => send(ws, { type: 'data', d });
  const onSessionStatus = (st) => send(ws, { type: 'status', state: st.state, detail: st.detail });
  const detach = () => {
    if (session) {
      session.removeListener('data', onSessionData);
      session.removeListener('status', onSessionStatus);
      session.detach();
      session = null;
    }
  };

  ws.on('message', (raw) => {
    let msg; try { msg = JSON.parse(raw.toString()); } catch (_) { return; }
    if (msg.t === 'attach') {
      clientId = String(msg.id || '');
      const s = shells.getSession(clientId);
      if (!s) return send(ws, { type: 'status', state: 'closed', detail: '无效的会话 ID' });
      session = s;
      session.on('data', onSessionData);
      session.on('status', onSessionStatus);
      const tail = session.attach();
      if (tail) send(ws, { type: 'data', d: tail });
      send(ws, { type: 'status', state: session.alive ? 'connected' : 'reconnecting', detail: '已附加到会话' });
      return;
    }
    if (msg.t === 'resize' && session) return session.resize(msg.cols, msg.rows);
    if (msg.t === 'data' && session) return session.write(msg.d || '');
  });

  const close = () => { detach(); };
  ws.on('close', close);
  ws.on('error', close);
}

/* ============ metrics socket ============ */
function handleMetricsSocket(ws) {
  metricsClients.add(ws);
  send(ws, { type: 'hello', refreshMs: currentInterval, history });
  ws.on('message', (raw) => {
    let msg; try { msg = JSON.parse(raw.toString()); } catch (_) { return; }
    if (msg.type === 'ping') return send(ws, { type: 'pong', ts: Date.now() });
    if (msg.type === 'subscribe-logs') return subscribeLogs(ws, msg.source);
    if (msg.type === 'unsubscribe-logs') return unsubscribeLogs(ws, msg.source);
    if (msg.type === 'refresh-interval') {
      const ms = Math.max(500, Math.min(60000, parseInt(msg.ms, 10) || 0));
      if (ms && ms !== currentInterval) { config.patch({ refreshMs: ms }); startScheduler(); }
      return send(ws, { type: 'hello', refreshMs: currentInterval, history: [] });
    }
  });
  const cleanup = () => {
    metricsClients.delete(ws);
    if (ws._logSubs) for (const s of [...ws._logSubs]) unsubscribeLogs(ws, s);
  };
  ws.on('close', cleanup);
  ws.on('error', cleanup);
}

/* ============ upgrade handling ============ */
function init(httpServer) {
  wss = new WebSocketServer({ noServer: true });
  httpServer.on('upgrade', (req, socket, head) => {
    const parsed = url.parse(req.url, true);
    const p = parsed.pathname || '';
    if (!/(\/ws|\/term)$/.test(p)) { socket.destroy(); return; }
    const rec = auth.verify(parsed.query && parsed.query.token);
    if (!rec) {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      if (/\/term$/.test(p)) handleTermSocket(ws, parsed.query);
      else handleMetricsSocket(ws);
    });
  });

  /* heartbeat */
  setInterval(() => {
    for (const ws of wss.clients) {
      if (ws._alive === false) { try { ws.terminate(); } catch (_) {} continue; }
      ws._alive = false;
      try { ws.ping(); } catch (_) {}
    }
    for (const ws of wss.clients) ws.on('pong', () => { ws._alive = true; });
  }, 30000).unref();

  startScheduler();
  log('websocket hub initialized');
}

function applyRefresh() { startScheduler(); }

function shutdown() {
  if (metricsTimer) clearInterval(metricsTimer);
  for (const s of follows.values()) { try { s.child.kill('SIGKILL'); } catch (_) {} }
  follows.clear();
  shells.killAll();
  try { if (wss) wss.close(); } catch (_) {}
}

module.exports = { init, shutdown, listSources, applyRefresh, getHistory, getAlerts, fireAlert };
