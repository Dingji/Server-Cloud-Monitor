'use strict';
/*
 * 内网设备硬件监控 (device metrics)
 * ==================================
 * 内网设备(树莓派等)上的 Agent 主动把硬件性能推送到面板,面板负责:
 *   1. 设备 token 认证接收入口  (POST /agent/push, 在会话认证之前挂载)
 *   2. 分层时序存储 SQLite      (500ms 原始 6h → 5s 48h → 30s 30d → 5min 90d)
 *   3. 实时 WebSocket 广播      (前端零轮询)
 *   4. 阈值告警 + 掉线/恢复检测 (hub.fireAlert 进铃铛, ops/notify 发钉钉/飞书/邮件)
 *
 * 设备数据由 Agent 侧采集(/proc、sysfs),面板只做汇聚与展示,不反向连设备 ——
 * 内网设备在 NAT 后面,面板无法主动入站,这是唯一可靠的生产拓扑。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { log, logErr } = require('./util');
const hub = require('./hub');

/* 外部通知渠道(ops 层可能被禁用,防御性加载) */
let notify = null;
try { notify = require('./ops/notify'); } catch (_) {}

/* ---------------- 存储配置 ---------------- */
const REG_PATH = path.resolve(__dirname, '..', 'devices.json');
const AGENT_SRC = path.resolve(__dirname, '..', 'agent.py');
const PUBLIC_DIR = path.resolve(__dirname, '..', 'public');
/* agent 源码同步到 public:新设备用 curl 免登录拉取(源码本身无密钥) */
const AGENT_PUBLIC = path.join(PUBLIC_DIR, 'cm-agent.py');
const DB_PATH = '/var/lib/relay/devmetrics.db';

/* 分层采样:step = 聚合桶宽(ms), keepMs = 该层保留时长 */
const TIERS = [
  { i: 0, step: 500, keepMs: 6 * 3600000 },        /* 原始 500ms */
  { i: 1, step: 5000, keepMs: 48 * 3600000 },      /* 5s */
  { i: 2, step: 30000, keepMs: 30 * 86400000 },    /* 30s */
  { i: 3, step: 300000, keepMs: 90 * 86400000 },   /* 5min */
];
const RANGES = { '10m': 10 / 60, '1h': 1, '6h': 6, '24h': 24, '7d': 168, '30d': 720 };
const TARGET_POINTS = 420;

const DEFAULT_ALERTS = {
  enabled: true,
  cpu: 90,        /* CPU % */
  mem: 90,        /* 内存 % */
  disk: 95,       /* 任一挂载点 % */
  temp: 75,       /* SoC 温度 ℃ */
  offlineSec: 90, /* 超过该秒数未上报判定掉线 */
};

/* ---------------- 设备注册表 ---------------- */
let reg = null;

function regLoad() {
  if (reg) return reg;
  try {
    reg = JSON.parse(fs.readFileSync(REG_PATH, 'utf8'));
  } catch (_) {
    reg = { devices: [], alerts: Object.assign({}, DEFAULT_ALERTS) };
  }
  reg.devices = Array.isArray(reg.devices) ? reg.devices : [];
  reg.alerts = Object.assign({}, DEFAULT_ALERTS, reg.alerts || {});
  return reg;
}

function regSave() {
  try {
    fs.writeFileSync(REG_PATH, JSON.stringify(reg, null, 2), { mode: 0o600 });
  } catch (e) { logErr('devices.json 写入失败:', e.message); }
}

const ID_RE = /^[a-z0-9][a-z0-9_-]{1,31}$/;

function tokenEq(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length || !a.length) return false;
  return crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
}

function findByToken(tok) {
  if (!tok) return null;
  return regLoad().devices.find((d) => tokenEq(d.token, tok)) || null;
}

function getDevice(id) {
  return regLoad().devices.find((d) => d.id === id) || null;
}

function registerDevice(body) {
  const r = regLoad();
  const id = String((body || {}).id || '').trim().toLowerCase();
  if (!ID_RE.test(id)) {
    const e = new Error('设备 ID 需为 2-32 位小写字母/数字/-/_,且以字母或数字开头');
    e.status = 400; throw e;
  }
  if (r.devices.some((d) => d.id === id)) {
    const e = new Error('设备 ID 已存在');
    e.status = 409; throw e;
  }
  const dev = {
    id,
    name: String((body || {}).name || id).slice(0, 64),
    note: String((body || {}).note || '').slice(0, 200),
    token: crypto.randomBytes(24).toString('hex'),
    createdAt: Date.now(),
    enabled: true,
    alerts: null,           /* null = 沿用全局阈值 */
  };
  r.devices.push(dev);
  regSave();
  log('device registered:', id);
  return publicDevice(dev);
}

function removeDevice(id) {
  const r = regLoad();
  const i = r.devices.findIndex((d) => d.id === id);
  if (i < 0) { const e = new Error('设备不存在'); e.status = 404; throw e; }
  const dev = r.devices[i];
  r.devices.splice(i, 1);
  regSave();
  latest.delete(id);
  purgeDevice(id);
  log('device removed:', id);
  return { ok: true, id: dev.id };
}

/* 列表输出绝不回传 token 明文(安装命令按需单独取) */
function publicDevice(d) {
  const st = latest.get(d.id);
  const alerts = Object.assign({}, DEFAULT_ALERTS, regLoad().alerts, d.alerts || {});
  return {
    id: d.id, name: d.name, note: d.note, enabled: d.enabled !== false,
    createdAt: d.createdAt, alerts,
    lastPush: st ? st.lastPush : null,
    online: st ? st.online : false,
    agentVer: st && st.meta ? st.meta.ver : null,
    intervalMs: st && st.meta ? st.meta.interval : null,
    sampleCount: st ? st.count : 0,
  };
}

/* ---------------- 时序库 (node:sqlite) ---------------- */
let db = null;
let dbDisabled = false;

{
  /* 加载期静默 experimental 警告(与 metricsstore 保持一致) */
  const orig = process.emitWarning;
  process.emitWarning = function (w, ...rest) {
    if (String(w).toLowerCase().includes('sqlite')) return;
    return orig.call(process, w, ...rest);
  };
  let DatabaseSync = null;
  try { ({ DatabaseSync } = require('node:sqlite')); } catch (e) { logErr('devmetrics: node:sqlite 不可用:', e.message); }
  global.__DatabaseSync = global.__DatabaseSync || DatabaseSync;
  process.emitWarning = orig;
}

function dbOpen() {
  if (dbDisabled) return null;
  if (db) return db;
  if (!global.__DatabaseSync) { dbDisabled = true; return null; }
  try {
    fs.mkdirSync(path.dirname(DB_PATH), { recursive: true, mode: 0o700 });
    db = new global.__DatabaseSync(DB_PATH);
    db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = NORMAL;
      CREATE TABLE IF NOT EXISTS dev_samples (
        ts   INTEGER NOT NULL,
        dev  TEXT    NOT NULL,
        tier INTEGER NOT NULL,
        cpu  REAL, mem REAL, swap REAL, temp REAL,
        rx   INTEGER, tx INTEGER,
        disk REAL, ior INTEGER, iow INTEGER, load REAL,
        PRIMARY KEY (dev, tier, ts)
      );
      CREATE INDEX IF NOT EXISTS i_dts ON dev_samples(dev, ts);
    `);
    return db;
  } catch (e) {
    dbDisabled = true;
    logErr('devmetrics: 打开失败,已停用:', e.message);
    return null;
  }
}

function recordSample(dev, s) {
  const d = dbOpen();
  if (!d) return;
  const mounts = (s.disk && s.disk.mounts) || [];
  let disk = 0;
  for (const m of mounts) if ((m.pct || 0) > disk) disk = m.pct;
  let rx = 0, tx = 0;
  const ifaces = (s.net && s.net.ifaces) || {};
  for (const n of Object.keys(ifaces)) {
    if (n === 'lo') continue;
    rx += ifaces[n].rxBps || 0;
    tx += ifaces[n].txBps || 0;
  }
  try {
    d.prepare(`INSERT OR REPLACE INTO dev_samples
               (ts, dev, tier, cpu, mem, swap, temp, rx, tx, disk, ior, iow, load)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`)
      .run(s.ts, dev, 0,
        s.cpu ? s.cpu.total : null,
        s.mem ? s.mem.usedPct : null,
        s.mem ? s.mem.swapPct : null,
        typeof s.temp === 'number' ? s.temp : null,
        Math.round(rx), Math.round(tx), disk,
        s.disk && s.disk.io ? Math.round(s.disk.io.read || 0) : 0,
        s.disk && s.disk.io ? Math.round(s.disk.io.write || 0) : 0,
        s.cpu && s.cpu.load && s.cpu.load.length ? s.cpu.load[0] : null);
  } catch (e) {
    dbDisabled = true;
    logErr('devmetrics: 写入失败,已停用:', e.message);
  }
}

/* 把 N 层按 step 聚合到 N+1 层,并清理过期数据(每 5 分钟跑一次) */
function rollup() {
  const d = dbOpen();
  if (!d) return;
  const now = Date.now();
  try {
    d.exec('BEGIN');
    for (let i = 0; i < TIERS.length - 1; i++) {
      const a = TIERS[i], b = TIERS[i + 1];
      d.prepare(`INSERT OR REPLACE INTO dev_samples
                 (ts, dev, tier, cpu, mem, swap, temp, rx, tx, disk, ior, iow, load)
                 SELECT (ts / ?) * ?, dev, ?,
                        AVG(cpu), AVG(mem), AVG(swap), AVG(temp),
                        AVG(rx), AVG(tx), AVG(disk), AVG(ior), AVG(iow), AVG(load)
                   FROM dev_samples WHERE tier = ? AND ts < ? GROUP BY dev, ts / ?`)
        .run(a.step, a.step, b.i, b.i, now - a.step, a.step);
      d.prepare('DELETE FROM dev_samples WHERE tier = ? AND ts < ?').run(a.i, now - a.keepMs);
    }
    const last = TIERS[TIERS.length - 1];
    d.prepare('DELETE FROM dev_samples WHERE tier = ? AND ts < ?').run(last.i, now - last.keepMs);
    d.exec('COMMIT');
    d.exec('PRAGMA wal_checkpoint(PASSIVE)');
  } catch (e) {
    try { d.exec('ROLLBACK'); } catch (_) {}
    logErr('devmetrics rollup:', e.message);
  }
}

function purgeDevice(dev) {
  const d = dbOpen();
  if (!d) return;
  try { d.prepare('DELETE FROM dev_samples WHERE dev = ?').run(dev); } catch (e) { logErr('purge:', e.message); }
}

function tierForRange(range) {
  const h = RANGES[range] != null ? RANGES[range] : 24;
  if (h <= 6) return 0;     /* 500ms 原始 */
  if (h <= 48) return 1;    /* 5s */
  if (h <= 360) return 2;   /* 30s:7d 走这层 */
  return 3;                 /* 5min:30d 走这层(8.6 万行 vs 86 万行) */
}

/* 按目标点数降采样,保证前端曲线密度稳定 */
function downsample(rows, target) {
  const total = rows.length;
  if (!total) return [];
  if (total <= target) return rows.map(toPoint);
  const step = Math.ceil(total / target);
  const out = [];
  for (let i = 0; i < total; i += step) {
    const grp = rows.slice(i, i + step);
    const n = grp.length;
    let cpu = 0, mem = 0, swap = 0, rx = 0, tx = 0, disk = 0, temp = 0, ior = 0, iow = 0, load = 0;
    let tempN = 0, peakCpu = 0;
    for (const g of grp) {
      cpu += g.cpu || 0; mem += g.mem || 0; swap += g.swap || 0;
      rx += g.rx || 0; tx += g.tx || 0; disk += g.disk || 0;
      ior += g.ior || 0; iow += g.iow || 0; load += g.load || 0;
      if (g.temp != null) { temp += g.temp; tempN++; }
      if ((g.cpu || 0) > peakCpu) peakCpu = g.cpu;
    }
    out.push({
      t: Math.round(grp[0].ts / 1000),
      c: +(cpu / n).toFixed(1), m: +(mem / n).toFixed(1), s: +(swap / n).toFixed(1),
      tp: tempN ? +(temp / tempN).toFixed(1) : null,
      rx: Math.round(rx / n), tx: Math.round(tx / n), d: +(disk / n).toFixed(1),
      ir: Math.round(ior / n), iw: Math.round(iow / n), l: +(load / n).toFixed(2),
      pc: +peakCpu.toFixed(1),
    });
  }
  return out;
}

const toPoint = (r) => ({
  t: Math.round(r.ts / 1000),
  c: r.cpu, m: r.mem, s: r.swap, tp: r.temp,
  rx: r.rx, tx: r.tx, d: r.disk, ir: r.ior, iw: r.iow, l: r.load,
});

function queryHistory(dev, range) {
  const d = dbOpen();
  if (!d) return { ok: false, points: [], range, reason: dbDisabled ? 'store disabled' : 'no store' };
  const hours = RANGES[range] != null ? RANGES[range] : 24;
  const tier = tierForRange(range);
  const cutoff = Date.now() - hours * 3600000;
  let rows;
  try {
    rows = d.prepare(`SELECT ts, cpu, mem, swap, temp, rx, tx, disk, ior, iow, load
                        FROM dev_samples WHERE dev = ? AND tier = ? AND ts >= ? ORDER BY ts`)
      .all(dev, tier, cutoff);
  } catch (e) {
    return { ok: false, points: [], range, reason: e.message };
  }
  const points = downsample(rows, TARGET_POINTS);
  const res = {
    ok: true, range, tier, stepMs: TIERS[tier].step,
    total: rows.length, points,
  };
  if (rows.length) {
    let cpu = 0, n = 0, mx = 0, mt = 0;
    for (const r of rows) {
      if (r.cpu != null) { cpu += r.cpu; n++; if (r.cpu > mx) mx = r.cpu; }
      if (r.temp != null && r.temp > mt) mt = r.temp;
    }
    res.avgCpu = n ? +(cpu / n).toFixed(1) : null;
    res.maxCpu = n ? +mx.toFixed(1) : null;
    res.maxTemp = rows.some((r) => r.temp != null) ? mt : null;
  }
  return res;
}

function exportCsv(dev, range) {
  const d = dbOpen();
  if (!d) return { ok: false, reason: 'store disabled' };
  const hours = RANGES[range] != null ? RANGES[range] : 24;
  const tier = tierForRange(range);
  const cutoff = Date.now() - hours * 3600000;
  const rows = d.prepare(`SELECT ts, cpu, mem, swap, temp, rx, tx, disk, ior, iow, load
                            FROM dev_samples WHERE dev = ? AND tier = ? AND ts >= ? ORDER BY ts`)
    .all(dev, tier, cutoff);
  const head = 'timestamp,iso8601,cpu_pct,mem_pct,swap_pct,temp_c,rx_bps,tx_bps,disk_pct,disk_read_bps,disk_write_bps,load1\n';
  const body = rows.map((r) => [
    r.ts, new Date(r.ts).toISOString(), r.cpu, r.mem, r.swap, r.temp,
    r.rx, r.tx, r.disk, r.ior, r.iow, r.load,
  ].map((v) => (v == null ? '' : v)).join(',')).join('\n');
  return { ok: true, csv: head + body + '\n', rows: rows.length };
}

/* ---------------- 实时状态 ---------------- */
/* dev -> { light, heavy, lastPush, online, meta, count, notifiedOffline } */
const latest = new Map();
let bootAt = Date.now();
let rollupAt = 0;
let broadcast = () => {};

function bcast(type, payload) {
  try { broadcast({ type, data: payload }); } catch (_) {}
}

/* 把原始样本压成实时广播用的轻量结构 */
function lightOf(s) {
  const mounts = (s.disk && s.disk.mounts) || [];
  const ifaces = (s.net && s.net.ifaces) || {};
  let rx = 0, tx = 0, rxTot = 0, txTot = 0;
  const netRows = [];
  for (const n of Object.keys(ifaces)) {
    const v = ifaces[n];
    if (n !== 'lo') { rx += v.rxBps || 0; tx += v.txBps || 0; rxTot += v.rx || 0; txTot += v.tx || 0; }
    netRows.push({ name: n, rxBps: v.rxBps || 0, txBps: v.txBps || 0, rx: v.rx || 0, tx: v.tx || 0,
      rxErr: v.rxErr || 0, txErr: v.txErr || 0 });
  }
  let diskPct = 0, disk = null;
  for (const m of mounts) if ((m.pct || 0) >= diskPct) { diskPct = m.pct; disk = m; }
  return {
    ts: s.ts, uptime: s.uptime || 0,
    cpu: s.cpu ? s.cpu.total : null,
    cores: s.cpu ? s.cpu.cores : [],
    load: s.cpu ? s.cpu.load : [],
    freq: s.cpu ? s.cpu.freq : null,
    procs: s.cpu ? s.cpu.procs : null,
    running: s.cpu ? s.cpu.running : null,
    mem: s.mem || null,
    temp: typeof s.temp === 'number' ? s.temp : null,
    net: { rx, tx, rxTot, txTot, ifaces: netRows },
    disk: { pct: diskPct, top: disk, mounts, io: (s.disk && s.disk.io) || { read: 0, write: 0 } },
  };
}

function ingest(device, samples, meta) {
  const dev = getDevice(device.id) || device;
  if (dev.enabled === false) return { ok: false, error: '设备已停用' };
  if (!Array.isArray(samples) || !samples.length) return { ok: false, error: 'samples 为空' };

  const now = Date.now();
  let st = latest.get(dev.id);
  if (!st) {
    st = { light: null, heavy: null, lastPush: now, online: true, meta, count: 0, wasOffline: false };
    latest.set(dev.id, st);
  }
  if (meta) st.meta = Object.assign({}, st.meta || {}, meta);
  const cameBack = st.wasOffline;
  st.wasOffline = false;
  st.lastPush = now;
  st.online = true;
  st.count += samples.length;

  let lastLight = null;
  let heavy = null;
  for (const s of samples) {
    if (!s || typeof s !== 'object' || !s.ts) continue;
    recordSample(dev.id, s);
    lastLight = lightOf(s);
    /* heavy(进程/健康度/系统信息)只在刚刷新时广播,避免每拍重复 10 次 */
    if (s.heavy && (s.heavyAge == null || s.heavyAge < 1500)) heavy = s.heavy;
  }
  if (!lastLight) return { ok: true, accepted: 0 };

  st.light = lastLight;
  if (heavy) st.heavy = heavy;
  bcast('devmetrics', { dev: dev.id, light: lastLight, at: now });
  if (heavy) bcast('devheavy', { dev: dev.id, heavy, at: now });

  if (Date.now() - rollupAt > 300000) { rollupAt = Date.now(); setImmediate(rollup); }

  evaluate(dev, st, cameBack);
  return { ok: true, accepted: samples.length };
}

/* ---------------- 告警评估 ---------------- */
const notifyCool = new Map();   /* key -> last sent ts (外部渠道独立冷却) */
const COOLDOWN_MS = 10 * 60000;

function effAlerts(dev) {
  return Object.assign({}, DEFAULT_ALERTS, regLoad().alerts, dev.alerts || {});
}

function fire(dev, st, key, level, message, fields) {
  /* 铃铛 + WS:复用 hub.fireAlert,自带 cooldown 配置 */
  try { hub.fireAlert('dev:' + dev.id + ':' + key, level, message); } catch (_) {}
  if (!notify) return;
  const nk = dev.id + ':' + key;
  const last = notifyCool.get(nk) || 0;
  if (Date.now() - last < COOLDOWN_MS) return;
  notifyCool.set(nk, Date.now());
  try {
    notify.dispatch(notify.buildMessage({
      title: '内网设备 · ' + (dev.name || dev.id),
      text: message,
      level,
      host: dev.id,
      link: '/panel/#/relayhub',
      fields: fields || [],
    }), null, { source: 'device', ruleId: 'dev-' + dev.id + '-' + key })
      .then((r) => log('device alert dispatched:', nk, r && r.sent))
      .catch((e) => logErr('device alert notify:', e.message));
  } catch (e) { logErr('device alert notify:', e.message); }
}

function evaluate(dev, st, cameBack) {
  const a = effAlerts(dev);
  if (a.enabled === false) return;
  const l = st.light;
  if (!l) return;
  const name = dev.name || dev.id;

  if (l.cpu != null && a.cpu > 0 && l.cpu >= a.cpu) {
    fire(dev, st, 'cpu', 'warn', `${name} CPU 使用率 ${l.cpu.toFixed(0)}%（阈值 ${a.cpu}%）`,
      [{ k: 'CPU', v: l.cpu + '%' }, { k: '负载', v: (l.load || []).join(' / ') }]);
  }
  if (l.mem && a.mem > 0 && l.mem.usedPct >= a.mem) {
    fire(dev, st, 'mem', 'warn', `${name} 内存使用率 ${l.mem.usedPct.toFixed(0)}%（阈值 ${a.mem}%）`,
      [{ k: '内存', v: fmtBytes(l.mem.used) + ' / ' + fmtBytes(l.mem.total) }]);
  }
  if (a.disk > 0 && l.disk && l.disk.pct >= a.disk) {
    fire(dev, st, 'disk', 'bad', `${name} 磁盘 ${l.disk.top ? l.disk.top.mount : ''} 使用率 ${l.disk.pct}%（阈值 ${a.disk}%）`,
      [{ k: '挂载点', v: l.disk.top ? l.disk.top.mount : '-' }, { k: '剩余', v: l.disk.top ? fmtBytes(l.disk.top.avail) : '-' }]);
  }
  if (a.temp > 0 && l.temp != null && l.temp >= a.temp) {
    const lvl = l.temp >= a.temp + 10 ? 'bad' : 'warn';
    fire(dev, st, 'temp', lvl, `${name} SoC 温度 ${l.temp}℃（阈值 ${a.temp}℃）`);
  }
  if (cameBack) {
    fire(dev, st, 'online', 'ok', `${name} 已恢复上报`);
    st.notifiedOffline = false;
  }
}

function fmtBytes(n) {
  if (!n && n !== 0) return '-';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0; let v = +n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return (i ? v.toFixed(1) : Math.round(v)) + u[i];
}

/* ---------------- 掉线看门狗 ---------------- */
let watchdog = null;
function startWatchdog() {
  if (watchdog) return;
  watchdog = setInterval(() => {
    const r = regLoad();
    const now = Date.now();
    for (const d of r.devices) {
      if (d.enabled === false) continue;
      const st = latest.get(d.id);
      const a = effAlerts(d);
      if (!st) {
        /* 启动后从未上报过:给 3 倍 offlineSec 宽限,避免面板重启瞬间误报 */
        if (now - bootAt > a.offlineSec * 3000) {
          latest.set(d.id, { light: null, heavy: null, lastPush: 0, online: false, meta: null, count: 0, wasOffline: true });
        }
        continue;
      }
      const idle = now - Math.max(st.lastPush, bootAt);
      if (st.online && idle > a.offlineSec * 1000) {
        st.online = false;
        st.wasOffline = true;
        if (!st.notifiedOffline) {
          st.notifiedOffline = true;
          const mins = Math.round(idle / 60000);
          fire(d, st, 'offline', 'bad',
            `${d.name || d.id} 已失联 ${mins >= 1 ? mins + ' 分钟' : Math.round(idle / 1000) + ' 秒'}（${a.offlineSec}s 内无上报）`,
            [{ k: '最后上报', v: st.lastPush ? new Date(st.lastPush).toLocaleString('zh-CN', { hour12: false }) : '无' }]);
        }
        bcast('devstatus', { dev: d.id, online: false, lastPush: st.lastPush });
      }
    }
  }, 10000);
  watchdog.unref && watchdog.unref();
}

/* ---------------- API ---------------- */

/* 设备上报入口:在会话认证之前挂载,靠 X-Device-Token 认证 */
async function pushHandler(req, res) {
  const tok = req.headers['x-device-token'];
  const dev = findByToken(tok);
  if (!dev) {
    const ip = req.headers['x-real-ip'] || req.socket.remoteAddress || '?';
    log('devmetrics: 无效 token 来自', ip);
    return res.status(401).json({ error: 'invalid device token' });
  }
  if (dev.enabled === false) return res.status(403).json({ error: 'device disabled' });
  const body = req.body || {};
  const meta = {
    ver: String(req.headers['x-agent-ver'] || '').slice(0, 24) || null,
    interval: Number(body.interval) || null,
    ip: req.headers['x-real-ip'] || req.socket.remoteAddress || '?',
  };
  try {
    const r = ingest(dev, body.samples, meta);
    res.status(r.ok ? 200 : 400).json(r);
  } catch (e) {
    logErr('devmetrics push:', e.message);
    res.status(500).json({ error: e.message });
  }
}

/* 会话认证之后的只读/管理接口 */
function mount(api, wrap, audited) {
  api.get('/devices', wrap(async (req, res) => {
    const r = regLoad();
    const devices = r.devices.map(publicDevice);
    for (const d of devices) {
      const st = latest.get(d.id);
      d.light = st ? st.light : null;
      d.heavy = st ? st.heavy : null;
    }
    res.json({ ok: true, devices, alerts: r.alerts });
  }));

  api.get('/devices/history', wrap(async (req, res) => {
    const dev = String(req.query.dev || '');
    if (!getDevice(dev)) return res.status(404).json({ error: '设备不存在' });
    res.json(Object.assign({ dev }, queryHistory(dev, String(req.query.range || '10m'))));
  }));

  api.get('/devices/export', (req, res) => res.redirect('/devices/csv'));

  api.post('/devices/register', audited('device.register', (req) => registerDevice(req.body || {})));
  api.post('/devices/remove', audited('device.remove', (req) => removeDevice(String((req.body || {}).id || ''))));
  api.post('/devices/rotate', audited('device.rotate', (req) => {
    const d = getDevice(String((req.body || {}).id || ''));
    if (!d) { const e = new Error('设备不存在'); e.status = 404; throw e; }
    d.token = crypto.randomBytes(24).toString('hex');
    regSave();
    return { ok: true, token: d.token };
  }));
  api.post('/devices/toggle', audited('device.toggle', (req) => {
    const d = getDevice(String((req.body || {}).id || ''));
    if (!d) { const e = new Error('设备不存在'); e.status = 404; throw e; }
    d.enabled = (req.body || {}).enabled !== false;
    regSave();
    return { ok: true, enabled: d.enabled };
  }));
  api.post('/devices/alerts', audited('device.alerts', (req) => {
    const b = req.body || {};
    const r = regLoad();
    if (b.id) {
      const d = getDevice(String(b.id));
      if (!d) { const e = new Error('设备不存在'); e.status = 404; throw e; }
      d.alerts = normalizeAlerts(b.alerts, d.alerts);
      regSave();
      return { ok: true, scope: 'device', alerts: publicDevice(d).alerts };
    }
    r.alerts = normalizeAlerts(b.alerts, r.alerts);
    regSave();
    return { ok: true, scope: 'global', alerts: r.alerts };
  }));

  /* 安装命令:返回含 token 的一次性接入脚本命令 */
  api.get('/devices/install', audited('device.install', (req) => {
    const d = getDevice(String(req.query.id || ''));
    if (!d) { const e = new Error('设备不存在'); e.status = 404; throw e; }
    const host = (req.headers['x-forwarded-host'] || req.headers.host || '').split(',')[0];
    const proto = req.headers['x-forwarded-proto'] || 'http';
    const base = host ? `${proto}://${host}/panel` : '';
    const src = `${base}/public/cm-agent.py`;
    const ep = `${base}/api/agent/push`;
    return {
      ok: true, id: d.id, token: d.token, base,
      endpoint: ep,
      cmd: base ? `curl -fsSL ${src} -o /tmp/cm-agent.py && sudo python3 /tmp/cm-agent.py --install --endpoint ${ep} --token ${d.token} --device ${d.id}` : '',
    };
  }));

  /* agent 源码同步到 public/(免登录可取,源码不含任何密钥) */
  try {
    const src = fs.readFileSync(AGENT_SRC);
    if (!fs.existsSync(AGENT_PUBLIC) || !src.equals(fs.readFileSync(AGENT_PUBLIC))) {
      fs.writeFileSync(AGENT_PUBLIC, src, { mode: 0o644 });
      log('devmetrics: agent.py 已同步到 public/cm-agent.py');
    }
  } catch (e) { logErr('devmetrics: agent 同步失败:', e.message); }

  /* CSV 导出(独立处理,不走 JSON 封装) */
  api.get('/devices/csv', wrap(async (req, res) => {
    const dev = String(req.query.dev || '');
    if (!getDevice(dev)) return res.status(404).json({ error: '设备不存在' });
    const range = String(req.query.range || '24h');
    const out = exportCsv(dev, range);
    if (!out.ok) return res.status(500).json({ error: out.reason || 'export failed' });
    const fn = `${dev}-${range}-${new Date().toISOString().slice(0, 10)}.csv`;
    res.setHeader('Content-Type', 'text/csv; charset=utf-8');
    res.setHeader('Content-Disposition', `attachment; filename="${fn}"`);
    res.setHeader('X-Rows', String(out.rows));
    res.end('\ufeff' + out.csv);   /* BOM,Excel 直接打开不乱码 */
    return undefined;
  }));

  startWatchdog();
}

function normalizeAlerts(b, existing) {
  b = b || {};
  const cur = Object.assign({}, DEFAULT_ALERTS, existing || {});
  const out = { enabled: b.enabled === undefined ? cur.enabled : !!b.enabled };
  for (const k of ['cpu', 'mem', 'disk', 'temp', 'offlineSec']) {
    const v = Number(b[k]);
    if (Number.isFinite(v)) {
      out[k] = k === 'offlineSec' ? Math.max(15, Math.min(3600, v)) : Math.max(0, Math.min(100, v));
    } else {
      out[k] = cur[k];
    }
  }
  return out;
}

function setBroadcast(fn) { broadcast = typeof fn === 'function' ? fn : broadcast; }

function stats() {
  const d = dbOpen();
  const r = { ok: !!d, devices: regLoad().devices.length, online: 0 };
  let on = 0;
  for (const s of latest.values()) if (s.online) on++;
  r.online = on;
  if (d) {
    try {
      const row = d.prepare('SELECT COUNT(*) n, MIN(ts) a, MAX(ts) b FROM dev_samples').get();
      r.rows = row.n || 0;
      r.first = row.a || null;
      r.last = row.b || null;
      r.sizeBytes = fs.existsSync(DB_PATH) ? fs.statSync(DB_PATH).size : 0;
    } catch (e) { r.error = e.message; }
  }
  return r;
}

module.exports = {
  pushHandler, mount, setBroadcast, stats,
  queryHistory, ingest, rollup,
  /* 供测试/运维直接调用 */
  listDevices: () => regLoad().devices.map(publicDevice),
};
