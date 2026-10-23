'use strict';
/*
 * 指标时序存储：把 hub 的 compactSample 落到 SQLite，面板重启不再丢历史。
 *   近 48h 保留 30 秒原始采样，更早的数据每小时聚合，最长保留 90 天。
 * 数据库：/var/lib/relay/metrics.db（node:sqlite，Node 22 内置，无外部依赖）
 */
const fs = require('fs');
const path = require('path');

/* node:sqlite 目前标 experimental，加载时会打印一次 Warning；这里静默掉以免刷日志 */
{
  const orig = process.emitWarning;
  process.emitWarning = function (w, ...rest) {
    if (String(w).toLowerCase().includes('sqlite')) return;
    return orig.call(process, w, ...rest);
  };
  let DatabaseSync = null;
  try { ({ DatabaseSync } = require('node:sqlite')); } catch (e) {
    console.error('[metricsstore] node:sqlite 不可用:', e.message);
  }
  global.__DatabaseSync = DatabaseSync;
  process.emitWarning = orig;
}

const DB = '/var/lib/relay/metrics.db';
const TZ = 8 * 3600 * 1000;   /* CST */
const RAW_MS = 48 * 3600 * 1000;
const AGG_DAYS = 90;
const RECORD_MIN_MS = 30000;  /* 落盘节流：30 秒一行 */
const ROLLUP_MS = 3600 * 1000;

let db = null;
let disabled = false;
let lastRecord = 0;
let lastRollup = 0;

const cst = (ms) => new Date(ms + TZ).toISOString();

function open() {
  if (disabled) return null;
  if (db) return db;
  if (!global.__DatabaseSync) { disabled = true; return null; }
  try {
    fs.mkdirSync(path.dirname(DB), { recursive: true, mode: 0o700 });
    db = new global.__DatabaseSync(DB);
    db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = NORMAL;
      CREATE TABLE IF NOT EXISTS samples (
        ts   INTEGER PRIMARY KEY,   /* epoch ms */
        day  TEXT NOT NULL,         /* YYYY-MM-DD CST */
        hr   TEXT NOT NULL,         /* YYYY-MM-DD HH CST */
        cpu  REAL, mem REAL, swap REAL,
        rx   INTEGER, tx INTEGER,
        disk REAL,
        agg  INTEGER NOT NULL DEFAULT 0   /* 1 = 小时聚合点 */
      );
      CREATE INDEX IF NOT EXISTS i_day ON samples(day);
      CREATE INDEX IF NOT EXISTS i_hr  ON samples(hr);
    `);
    return db;
  } catch (e) {
    disabled = true;
    console.error('[metricsstore] 打开失败，已停用:', e.message);
    return null;
  }
}

/* hub 每拍调用；内部 30 秒节流 */
function record(p) {
  if (!p || disabled) return;
  const now = Date.now();
  if (now - lastRecord < RECORD_MIN_MS) return;
  const d = open();
  if (!d) return;
  lastRecord = now;
  const iso = cst(now);
  try {
    d.prepare('INSERT OR REPLACE INTO samples (ts, day, hr, cpu, mem, swap, rx, tx, disk, agg) VALUES (?,?,?,?,?,?,?,?,?,0)')
      .run(p.t * 1000, iso.slice(0, 10), iso.slice(0, 13), p.c, p.m, p.s, p.rx, p.tx, p.d);
  } catch (e) {
    disabled = true;
    console.error('[metricsstore] 写入失败，已停用:', e.message);
  }
  if (now - lastRollup > ROLLUP_MS) { lastRollup = now; rollup(); }
}

/* 把 48h 前的原始采样压成小时级，并清理过期数据 */
function rollup() {
  const d = open();
  if (!d) return;
  const cut = Date.now() - RAW_MS;
  try {
    d.exec(`BEGIN`);
    d.prepare(`INSERT OR REPLACE INTO samples (ts, day, hr, cpu, mem, swap, rx, tx, disk, agg)
               SELECT MIN(ts), day, hr, AVG(cpu), AVG(mem), AVG(swap), AVG(rx), AVG(tx), AVG(disk), 1
                 FROM samples WHERE ts < ? AND agg = 0 GROUP BY hr`).run(cut);
    d.prepare('DELETE FROM samples WHERE ts < ? AND agg = 0').run(cut);
    d.prepare('DELETE FROM samples WHERE day < ?').run(cst(Date.now() - AGG_DAYS * 86400000).slice(0, 10));
    d.exec('COMMIT');
    d.exec('PRAGMA wal_checkpoint(TRUNCATE)');
  } catch (e) {
    try { d.exec('ROLLBACK'); } catch (_) {}
    console.error('[metricsstore] rollup:', e.message);
  }
}

/* 按目标点数降采样，保证前端曲线密度稳定 */
function downsample(rows, target) {
  const total = rows.length;
  if (!total) return [];
  if (total <= target) return rows.map(toPoint);
  const step = Math.ceil(total / target);
  const out = [];
  for (let i = 0; i < total; i += step) {
    const grp = rows.slice(i, i + step);
    let cpu = 0, mem = 0, swap = 0, rx = 0, tx = 0, disk = 0, peakCpu = 0, peakRx = 0;
    for (const g of grp) {
      cpu += g.cpu || 0; mem += g.mem || 0; swap += g.swap || 0;
      rx += g.rx || 0; tx += g.tx || 0; disk += g.disk || 0;
      if ((g.cpu || 0) > peakCpu) peakCpu = g.cpu;
      if ((g.rx || 0) > peakRx) peakRx = g.rx;
    }
    const n = grp.length;
    out.push({
      t: Math.round(grp[0].ts / 1000),
      c: +(cpu / n).toFixed(1), m: +(mem / n).toFixed(1), s: +(swap / n).toFixed(1),
      rx: Math.round(rx / n), tx: Math.round(tx / n), d: +(disk / n).toFixed(1),
      peakCpu: +peakCpu.toFixed(1), peakRx: Math.round(peakRx),
    });
  }
  return out;
}

const toPoint = (r) => ({
  t: Math.round(r.ts / 1000), c: r.cpu, m: r.mem, s: r.swap,
  rx: r.rx, tx: r.tx, d: r.disk,
});

function query(range, compare) {
  const d = open();
  if (!d) return { ok: false, points: [], agg: false, reason: disabled ? 'store disabled' : 'no store' };
  const hours = { '6h': 6, '24h': 24, '7d': 168, '30d': 720, all: AGG_DAYS * 24 }[range] || 24;
  const now = Date.now();
  const cutoff = now - hours * 3600000;
  const target = 420;
  let rows;
  try {
    rows = d.prepare('SELECT ts, cpu, mem, swap, rx, tx, disk FROM samples WHERE ts >= ? ORDER BY ts').all(cutoff);
  } catch (e) {
    return { ok: false, points: [], agg: false, reason: e.message };
  }
  const total = rows.length;
  const points = downsample(rows, target);
  const res = {
    ok: true, range, hours, total, agg: total > target,
    step: total > target ? Math.ceil(total / target) : 1,
    points,
  };

  /* 上一周期：同样长度、同样采样密度，便于逐点对齐 */
  if (compare) {
    try {
      const prev = d.prepare('SELECT ts, cpu, mem, swap, rx, tx, disk FROM samples WHERE ts >= ? AND ts < ? ORDER BY ts')
        .all(cutoff - hours * 3600000, cutoff);
      const dp = downsample(prev, target);
      res.prevTotal = prev.length;
      /* 重采样到与当前点数一致，前端可直接按索引叠在同一 X 轴上 */
      res.prevPoints = resampleTo(dp, points.length);
      res.hasPrev = res.prevPoints.length > 0;
    } catch (e) {
      res.prevPoints = []; res.hasPrev = false; res.prevError = e.message;
    }
  }
  return res;
}

/* 把一组点线性重采样到 n 个（不足补 null，超出抽稀） */
function resampleTo(points, n) {
  if (!points || !points.length || !n) return [];
  if (points.length === n) return points;
  const out = [];
  for (let i = 0; i < n; i++) {
    const src = points.length / n * i;
    const a = points[Math.min(points.length - 1, Math.floor(src))];
    out.push({ t: a.t, c: a.c, m: a.m, s: a.s, rx: a.rx, tx: a.tx, d: a.d });
  }
  return out;
}

/* 趋势预测：对给定字段做最小二乘，返回每日增量与触顶天数 */
function trend(days, field) {
  const d = open();
  if (!d || !days || !field) return null;
  const FIELDS = { cpu: 'cpu', mem: 'mem', disk: 'disk' };
  if (!FIELDS[field]) return null;
  const since = Date.now() - days * 86400000;
  let rows;
  try {
    rows = d.prepare(`SELECT day, AVG(${FIELDS[field]}) v FROM samples WHERE ts >= ? GROUP BY day ORDER BY day`).all(since);
  } catch (e) { return null; }
  if (rows.length < 2) {
    /* 样本不足时不给斜率，但当前值仍要返回，否则前端显示 undefined */
    const only = rows.length ? +(rows[0].v || 0).toFixed(2) : null;
    return {
      points: rows.map((r) => ({ day: r.day, v: +(r.v || 0).toFixed(2) })),
      slopePerDay: null, current: only, daysToFull: null, days: rows.length,
    };
  }

  /* 只用每天同小时段的均值做对比容易受作息影响，这里直接用逐日均值线性回归 */
  let n = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
  rows.forEach((r, i) => {
    const x = i, y = r.v || 0;
    n++; sx += x; sy += y; sxx += x * x; sxy += x * y;
  });
  const denom = n * sxx - sx * sx;
  const slope = denom ? (n * sxy - sx * sy) / denom : 0;
  const last = rows[rows.length - 1].v || 0;
  const daysTo100 = slope > 0.001 ? (100 - last) / slope : null;
  return {
    points: rows.map((r) => ({ day: r.day, v: +(r.v || 0).toFixed(2) })),
    slopePerDay: +slope.toFixed(3),
    current: +last.toFixed(2),
    daysToFull: daysTo100 == null ? null : +daysTo100.toFixed(1),
    days: rows.length,
  };
}

function stats() {
  const d = open();
  if (!d) return { ok: false, disabled: true };
  try {
    const r = d.prepare('SELECT COUNT(*) n, MIN(ts) a, MAX(ts) b, SUM(agg) aggn FROM samples').get();
    const size = fs.existsSync(DB) ? fs.statSync(DB).size : 0;
    return { ok: true, rows: r.n || 0, aggRows: r.aggn || 0, first: r.a || null, last: r.b || null, sizeBytes: size, file: DB };
  } catch (e) { return { ok: false, error: e.message }; }
}

module.exports = { record, query, trend, stats, rollup, DB };
