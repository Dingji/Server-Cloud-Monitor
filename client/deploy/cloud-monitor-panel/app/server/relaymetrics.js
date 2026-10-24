'use strict';
/*
 * Relay metrics: SLO, usage and quota analytics on top of relay.db + quota.json.
 */
const fs = require('fs');
const path = require('path');
const { getAgent } = require('./agent');
const { parseInt10 } = require('./util');

const DB = '/var/lib/relay/relay.db';
const QUOTA = '/var/lib/relay/public/quota.json';
const QUOTA_HIST = '/var/lib/relay/quota-history.jsonl';
const PRICING = '/etc/relay/pricing.json';

const RANGE_SEC = { '1h': 3600, '6h': 6 * 3600, '24h': 86400, '7d': 604800, all: 0 };
const RANGE_SQL = {
  '1h': "-3600 seconds", '6h': "-21600 seconds", '24h': "-86400 seconds",
  '7d': "-604800 seconds", all: "-100000 days",
};
/* +8h = CST, same basis as daily_report */
const TZH = "+8 hours";

function bad(msg) { const e = new Error(msg); e.status = 400; throw e; }

function jsonOr(t, fb) { try { return JSON.parse(String(t || '').trim()) || fb; } catch (_) { return fb; } }

function readJson(p, fb) { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (_) { return fb; } }
/* price lookup: longest * pattern matches */
function priceFor(model, pricing) {
  const table = (pricing && pricing.per_million_tokens) || {};
  let best = null, bestLen = -1;
  for (const [pat, val] of Object.entries(table)) {
    const re = new RegExp('^' + pat.split('*').map((s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$');
    if (re.test(model || '') && pat.length > bestLen) { best = val; bestLen = pat.length; }
  }
  return best || (pricing && pricing.default) || { input: 0, output: 0 };
}

/* ---------- sqlite3 -json 解析（O(n) 单遍） ----------
 * 旧行为：逐行 `buf += line` 再 `buf.trim().endsWith(']')` —— 每行都要复制并扫描
 * 整个缓冲区，行数多时是 O(n²)。实测 tokenHit(7d) 6500+ 行 JSON 要 5 秒，
 * 而 24h 只有 1164 行只要 190ms（拖慢的是整个面板进程的事件循环）。
 * 现在按括号配对单遍切出每条语句的 JSON 数组，再各自 JSON.parse 一次。 */
function parseSqlBlocks(text) {
  const s = String(text == null ? '' : text);
  const slices = [];
  let depth = 0, start = -1, inStr = false, esc = false;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (inStr) {
      if (esc) esc = false;
      else if (c === 92) esc = true;      /* backslash */
      else if (c === 34) inStr = false;   /* double quote */
      continue;
    }
    if (c === 34) { inStr = true; continue; }
    if (c === 91) { if (depth === 0) start = i; depth++; }
    else if (c === 93) { depth--; if (depth === 0 && start >= 0) { slices.push(s.slice(start, i + 1)); start = -1; } }
  }
  const res = [];
  for (let i = 0; i < slices.length; i++) {
    try { const a = JSON.parse(slices[i]); res.push(Array.isArray(a) ? a : []); }
    catch (_) { res.push([]); }
  }
  return res;
}

/* ---------- 极短 TTL 结果缓存 ----------
 * 2 秒轮询下，多个页签/多个浏览器窗口的重叠请求共享同一次计算。 */
const _memo = new Map();
function cached(ttlMs, key, fn) {
  const now = Date.now();
  const hit = _memo.get(key);
  if (hit && now - hit.t < ttlMs) return hit.p;
  const p = Promise.resolve().then(fn);
  _memo.set(key, { t: now, p });
  p.catch(() => { const cur = _memo.get(key); if (cur && cur.p === p) _memo.delete(key); });
  if (_memo.size > 32) { for (const [k, v] of _memo) if (now - v.t > ttlMs) _memo.delete(k); }
  return p;
}

/* ---------- 补齐 ts 索引 ----------
 * relay.db 的表由 usagetap 创建，usage_events / session_turns 都只有 day/model
 * 索引，按 ts 过滤是全表扫描（EXPLAIN: SCAN usage_events）。面板启动时补一次。 */
let _idxTried = false;
function ensureIndexes() {
  if (_idxTried) return Promise.resolve();
  _idxTried = true;
  return getAgent()
    .run(`sqlite3 -cmd ".timeout 5000" ${DB} "CREATE INDEX IF NOT EXISTS ix_ue_ts ON usage_events(ts); CREATE INDEX IF NOT EXISTS ix_st_ts ON session_turns(ts);"`, { timeout: 30000 })
    .catch(() => { _idxTried = false; });
}

/* =============== SLO =============== */
async function sloStats({ range } = {}) {
  range = String(range || '24h');
  if (!(range in RANGE_SEC)) bad('unknown range: ' + range);
  const agent = getAgent();
  const r = await agent.run(`sqlite3 -json ${DB} "
    SELECT
      COUNT(*) AS calls,
      SUM(CASE WHEN status BETWEEN 200 AND 299 THEN 1 ELSE 0 END) AS ok,
      SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) AS e5,
      SUM(CASE WHEN status >= 400 AND status < 500 THEN 1 ELSE 0 END) AS e4,
      SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS e0,
      SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) AS retried,
      AVG(rt) AS avg_rt, MAX(rt) AS max_rt, AVG(ttfb) AS avg_ttfb
    FROM requests WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}');
    SELECT route, COUNT(*) AS calls,
      SUM(CASE WHEN status BETWEEN 200 AND 299 THEN 1 ELSE 0 END) AS ok,
      SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) AS e5,
      SUM(CASE WHEN status >= 400 AND status < 500 THEN 1 ELSE 0 END) AS e4,
      SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) AS retried,
      AVG(rt) AS avg_rt, MAX(rt) AS max_rt
    FROM requests WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}')
    GROUP BY route ORDER BY calls DESC;
    SELECT rt FROM requests
      WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}') AND rt IS NOT NULL AND rt > 0
      ORDER BY rt;
    SELECT status, COUNT(*) AS n FROM requests
      WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}') AND status >= 400
      GROUP BY status ORDER BY n DESC LIMIT 15;
    SELECT strftime('%m-%d %H', datetime(ts,'unixepoch','${TZH}')) AS bucket, COUNT(*) AS calls,
      SUM(CASE WHEN status BETWEEN 200 AND 299 THEN 1 ELSE 0 END) AS ok,
      SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) AS e5
    FROM requests WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}')
    GROUP BY bucket ORDER BY bucket;
  " 2>/dev/null || echo '[]'`, { timeout: 25000 });

  /* sqlite3 -json prints one array per statement; parse line by line */
  const rows = parseSqlBlocks(r.stdout);
  const overall = (rows[0] && rows[0][0]) || {};
  const byRoute = rows[1] || [];
  const rts = (rows[2] || []).map((x) => x.rt).filter((v) => typeof v === 'number');
  const errors = rows[3] || [];
  const timeline = rows[4] || [];

  const pct = (q) => {
    if (!rts.length) return null;
    const i = Math.min(rts.length - 1, Math.max(0, Math.floor(rts.length * q)));
    return +rts[i].toFixed(3);
  };
  const calls = overall.calls || 0;
  const ok = overall.ok || 0;
  return {
    range, calls, ok,
    e5: overall.e5 || 0, e4: overall.e4 || 0, e0: overall.e0 || 0,
    retried: overall.retried || 0,
    successRate: calls ? +(ok / calls * 100).toFixed(3) : 100,
    errorRate: calls ? +((calls - ok) / calls * 100).toFixed(3) : 0,
    /* availability: 5xx / no-response only, client 4xx excluded */
    availability: calls ? +((calls - (overall.e5 || 0) - (overall.e0 || 0)) / calls * 100).toFixed(3) : 100,
    avgRt: overall.avg_rt ? +overall.avg_rt.toFixed(3) : null,
    maxRt: overall.max_rt ? +overall.max_rt.toFixed(3) : null,
    avgTtfb: overall.avg_ttfb ? +overall.avg_ttfb.toFixed(3) : null,
    latency: { p50: pct(0.5), p95: pct(0.95), p99: pct(0.99) },
    byRoute: byRoute.map((x) => ({
      route: x.route, calls: x.calls, ok: x.ok, e5: x.e5 || 0, e4: x.e4 || 0,
      retried: x.retried || 0,
      successRate: x.calls ? +(x.ok / x.calls * 100).toFixed(2) : 100,
      availability: x.calls ? +((x.calls - (x.e5 || 0)) / x.calls * 100).toFixed(2) : 100,
      avgRt: x.avg_rt ? +x.avg_rt.toFixed(3) : null,
      maxRt: x.max_rt ? +x.max_rt.toFixed(3) : null,
    })),
    errors, timeline,
  };
}

/* =============== usage & quota =============== */
async function usageCost({ range } = {}) {
  range = String(range || '24h');
  if (!(range in RANGE_SEC)) bad('unknown range: ' + range);
  const agent = getAgent();
  const r = await agent.run(`sqlite3 -json ${DB} "
    SELECT model, COUNT(*) AS calls,
      SUM(COALESCE(input_tokens,0)) AS in_tok,
      SUM(COALESCE(output_tokens,0)) AS out_tok,
      SUM(COALESCE(cache_read,0)) AS cache_r,
      SUM(COALESCE(cache_write,0)) AS cache_w,
      SUM(COALESCE(reasoning,0)) AS reason_tok,
      SUM(COALESCE(total_tokens,0)) AS total,
      SUM(COALESCE(duration,0)) AS secs,
      COUNT(DISTINCT sess) AS sessions
    FROM usage_events WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}')
      AND COALESCE(total_tokens,0) > 0
    GROUP BY model ORDER BY total DESC LIMIT 40;
    SELECT day, COUNT(*) AS calls,
      SUM(COALESCE(input_tokens,0)) AS in_tok,
      SUM(COALESCE(output_tokens,0)) AS out_tok,
      SUM(COALESCE(total_tokens,0)) AS total
    FROM usage_events WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}')
      AND COALESCE(total_tokens,0) > 0
    GROUP BY day ORDER BY day DESC LIMIT 30;
    SELECT COUNT(*) AS events, COUNT(DISTINCT sess) AS sessions,
      SUM(COALESCE(total_tokens,0)) AS total, SUM(COALESCE(duration,0)) AS secs
    FROM usage_events WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}');
  " 2>/dev/null || echo '[]'`, { timeout: 25000 });

  const rows = parseSqlBlocks(r.stdout);
  const pricing = readJson(PRICING, null);
  const byModel = (rows[0] || []).map((x) => {
    const p = priceFor(x.model, pricing);
    const inp = (x.in_tok || 0) - (x.cache_r || 0);
    const inpBase = Math.max(0, inp);
    const cost = (inpBase * (p.input || 0) + (x.out_tok || 0) * (p.output || 0)
      + (x.cache_r || 0) * (p.cache_read || 0)
      + (x.cache_w || 0) * (p.cache_write || 0)
      + (x.reason_tok || 0) * (p.reasoning || 0)) / 1e6;
    return Object.assign({}, x, {
      priced: !!(pricing && p),
      effectiveInput: inpBase,
      cost: +cost.toFixed(4),
      price: p,
    });
  });
  const byDay = rows[1] || [];
  const total = (rows[2] && rows[2][0]) || {};

  /* token burn rate over 1h / 6h / 24h */
  const rateR = await agent.run(`sqlite3 -json ${DB} "
    SELECT
      SUM(CASE WHEN ts >= strftime('%s','now','-3600 seconds') THEN COALESCE(total_tokens,0) ELSE 0 END) AS t1h,
      SUM(CASE WHEN ts >= strftime('%s','now','-21600 seconds') THEN COALESCE(total_tokens,0) ELSE 0 END) AS t6h,
      SUM(COALESCE(total_tokens,0)) AS t24h
    FROM usage_events WHERE ts >= strftime('%s','now','-86400 seconds');
  " 2>/dev/null || echo '[]'`, { timeout: 15000 });
  let rates = {};
  try { rates = (JSON.parse(rateR.stdout.trim()) || [{}])[0] || {}; } catch (_) {}

  const quota = readJson(QUOTA, null);
  const hist = readQuotaHistory();

  return {
    range,
    events: total.events || 0,
    sessions: total.sessions || 0,
    totalTokens: total.total || 0,
    totalSecs: +(total.secs || 0).toFixed(1),
    byModel, byDay,
    rates: {
      perHour1h: Math.round((rates.t1h || 0) / 1),
      perHour6h: Math.round((rates.t6h || 0) / 6),
      perDay: rates.t24h || 0,
    },
    quota: quota ? (quota.usage || {}) : null,
    quotaFetchedAt: quota ? quota.fetched_at : null,
    quotaHistory: hist,
    forecast: quotaForecast(hist, quota ? (quota.usage || {}) : null),
    pricing: pricing ? { currency: pricing.currency || 'USD', present: true } : { present: false },
  };
}

function readQuotaHistory() {
  try {
    if (!fs.existsSync(QUOTA_HIST)) return [];
    return fs.readFileSync(QUOTA_HIST, 'utf8').split('\n').filter(Boolean)
      .map((l) => jsonOr(l, null)).filter(Boolean).slice(-30);
  } catch (_) { return []; }
}

/* =============== quota trend & forecast =============== */
function quotaForecast(history, usage) {
  const out = {};
  for (const w of ['rolling', 'weekly', 'monthly']) {
    const cur = usage && usage[w];
    if (!cur) { out[w] = null; continue; }
    const pts = history.map((h) => h[w] && h[w].percent).filter((v) => typeof v === 'number');
    let deltaPerDay = null;
    if (pts.length >= 2) {
      const first = pts[0], last = pts[pts.length - 1];
      const spanDays = Math.max(0.5, (history[history.length - 1].ts - history[0].ts) / 86400);
      deltaPerDay = +((last - first) / spanDays).toFixed(2);
    }
    let resetIn = null, risk = null;
    if (cur.resetsAt) {
      const ms = new Date(cur.resetsAt).getTime() - Date.now();
      resetIn = +(ms / 3600000).toFixed(1);
      if (deltaPerDay != null && ms > 0) {
        const daysLeft = ms / 86400000;
        const projected = cur.percent + deltaPerDay * daysLeft;
        risk = projected >= 100 ? '耗尽' : (projected >= 85 ? '紧张' : '正常');
        out[w] = { percent: cur.percent, status: cur.status, resetIn, deltaPerDay, projected: +projected.toFixed(1), risk };
        continue;
      }
    }
    out[w] = { percent: cur.percent, status: cur.status, resetIn, deltaPerDay, projected: null, risk };
  }
  return out;
}

/* separate endpoint so sloStats stays cheap */
async function sloPrev({ range } = {}) {
  range = String(range || '24h');
  if (!(range in RANGE_SEC)) bad('unknown range: ' + range);
  const agent = getAgent();
  const PREV = { '1h': '-7200 seconds', '6h': '-43200 seconds', '24h': '-172800 seconds', '7d': '-1209600 seconds', all: '-200000 days' };
  const r = await agent.run(`sqlite3 -json ${DB} "
    SELECT COUNT(*) AS calls,
      SUM(CASE WHEN status BETWEEN 200 AND 299 THEN 1 ELSE 0 END) AS ok,
      SUM(CASE WHEN status >= 500 THEN 1 ELSE 0 END) AS e5,
      SUM(CASE WHEN status >= 400 AND status < 500 THEN 1 ELSE 0 END) AS e4,
      SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) AS retried,
      AVG(rt) AS avg_rt, MAX(rt) AS max_rt
    FROM requests WHERE ts >= strftime('%s','now','${PREV[range]}')
      AND ts < strftime('%s','now','${RANGE_SQL[range]}');
  " 2>/dev/null || echo '[]'`, { timeout: 20000 });
  let rows = [];
  try { rows = JSON.parse(r.stdout.trim()) || []; } catch (_) {}
  const o = rows[0] || {};
  const calls = o.calls || 0, ok = o.ok || 0;
  return {
    range, calls, ok, e5: o.e5 || 0, e4: o.e4 || 0, retried: o.retried || 0,
    successRate: calls ? +(ok / calls * 100).toFixed(3) : 100,
    availability: calls ? +((calls - (o.e5 || 0)) / calls * 100).toFixed(3) : 100,
    avgRt: o.avg_rt ? +o.avg_rt.toFixed(3) : null,
    maxRt: o.max_rt ? +o.max_rt.toFixed(3) : null,
  };
}

/* 错误预算：只有 5xx / 无响应消耗预算，客户端 4xx 不计入（与可用性口径一致） */
const BUDGET_WINDOWS = [
  ['1h', '-3600 seconds'], ['6h', '-21600 seconds'],
  ['24h', '-86400 seconds'], ['7d', '-604800 seconds'],
];

async function sloBudget() {
  const cfg = require('./config').get();
  const target = Number(cfg.sloTarget) || 0.999;
  const periodDays = Number(cfg.sloPeriodDays) || 30;
  const allowed = Math.max(1e-9, 1 - target);
  const agent = getAgent();

  const parts = BUDGET_WINDOWS.map(([label, off]) => `
    SELECT '${label}' AS w, COUNT(*) AS calls,
      SUM(CASE WHEN status >= 500 OR status = 0 THEN 1 ELSE 0 END) AS bad,
      SUM(CASE WHEN status >= 400 AND status < 500 THEN 1 ELSE 0 END) AS e4
    FROM requests WHERE ts >= strftime('%s','now','${off}');
  `);
  parts.push(`
    SELECT 'period' AS w, COUNT(*) AS calls,
      SUM(CASE WHEN status >= 500 OR status = 0 THEN 1 ELSE 0 END) AS bad,
      SUM(CASE WHEN status >= 400 AND status < 500 THEN 1 ELSE 0 END) AS e4
    FROM requests WHERE ts >= strftime('%s','now','-${periodDays} days');
  `);

  const r = await agent.run(`sqlite3 -json ${DB} "${parts.join('')}" 2>/dev/null || echo '[]'`, { timeout: 25000 });
  const rows = parseSqlBlocks(r.stdout).flat();
  const pick = (w) => rows.find((x) => x.w === w) || {};

  const windows = BUDGET_WINDOWS.map(([label]) => {
    const x = pick(label);
    const calls = x.calls || 0, bad = x.bad || 0;
    const errRate = calls ? bad / calls : 0;
    const burn = errRate / allowed;
    const exhaustDays = burn > 0.001 ? periodDays / burn : null;
    return {
      window: label, calls, bad, e4: x.e4 || 0,
      errorRatePct: +(errRate * 100).toFixed(4),
      burnRate: +burn.toFixed(2),
      exhaustDays: exhaustDays != null && exhaustDays < 3650 ? +exhaustDays.toFixed(1) : null,
      status: burn >= 14.4 ? 'critical' : burn >= 6 ? 'warn' : burn > 1 ? 'slipping' : 'ok',
    };
  });

  const p = pick('period');
  const pcalls = p.calls || 0, pbad = p.bad || 0;
  const allowedErrors = pcalls * allowed;
  const consumed = allowedErrors > 0 ? pbad / allowedErrors : 0;
  return {
    target, periodDays,
    allowedErrorRatePct: +(allowed * 100).toFixed(4),
    period: {
      calls: pcalls, bad: pbad, e4: p.e4 || 0,
      allowedErrors: Math.floor(allowedErrors),
      consumedPct: +(Math.min(999, consumed * 100)).toFixed(2),
      remainingPct: +(Math.max(0, 100 - consumed * 100)).toFixed(2),
    },
    windows,
  };
}

/* 异常分桶：按分钟统计 5xx/无响应，供"变更–事件关联"使用 */
async function errorBuckets({ hours = 24 } = {}) {
  const h = Math.max(1, Math.min(24 * 30, Number(hours) || 24));
  const agent = getAgent();
  const r = await agent.run(`sqlite3 -json ${DB} "
    SELECT strftime('%Y-%m-%d %H:%M', datetime(ts,'unixepoch','+8 hours')) AS bucket,
           COUNT(*) AS calls,
           SUM(CASE WHEN status >= 500 OR status = 0 THEN 1 ELSE 0 END) AS bad,
           SUM(CASE WHEN status >= 400 AND status < 500 THEN 1 ELSE 0 END) AS e4,
           AVG(rt) AS avg_rt
    FROM requests WHERE ts >= strftime('%s','now','-${h} hours')
    GROUP BY bucket HAVING bad > 0 ORDER BY bucket DESC LIMIT 200;
  " 2>/dev/null || echo '[]'`, { timeout: 25000 });
  let rows = [];
  try { rows = JSON.parse(r.stdout.trim()) || []; } catch (_) {}
  return rows.map((x) => ({
    bucket: x.bucket, calls: x.calls || 0, bad: x.bad || 0,
    e4: x.e4 || 0, avgRt: x.avg_rt ? +x.avg_rt.toFixed(2) : null,
    /* epoch 秒（CST 分桶转回 UTC） */
    ts: Math.floor(new Date(x.bucket.replace(' ', 'T') + ':00+08:00').getTime() / 1000),
  }));
}

/* 把异常时段与之前的变更事件配对（默认回看 30 分钟） */
function correlate(anomalies, changes, windowMin = 30) {
  const win = Math.max(5, Number(windowMin) || 30) * 60;
  const links = [];
  for (const a of anomalies || []) {
    const causes = (changes || [])
      .filter((c) => c.ts <= a.ts && a.ts - c.ts <= win)
      .sort((x, y) => y.ts - x.ts)
      .slice(0, 5);
    links.push({ anomaly: a, causes, likely: causes.length > 0 });
  }
  return links;
}

/* 汇总：异常总数、有关联的比例 */
function correlateSummary(links) {
  const hit = (links || []).filter((l) => l.likely).length;
  return {
    anomalies: (links || []).length, withCauses: hit,
    ratioPct: links && links.length ? +(hit / links.length * 100).toFixed(1) : 0,
  };
}

/* =============== 套餐（route）真实命中率 ===============
 * relay.db.requests 里 route 就是套餐/线路：go=OpenCode Go、zen=OpenCode Zen、
 * glm=GLM Coding Plan、mimo=MiMo Token Plan（nginx 按 location 划分）。
 *
 * 口径（避免"上游拒绝"被 4xx 掩盖，也避免客户端自身错误拉低分数）：
 *   命中        = 2xx
 *   未命中      = 套餐/上游原因：402 额度耗尽、403 权限、429 限流、5xx、无响应(0)
 *   不计入分母  = 客户端自身 4xx（400/401/404…）与 499 客户端中断
 *   真实命中率  = 命中 ÷ (命中 + 未命中)
 * 上游状态优先看 upstream_status（nginx $upstream_status），取不到再退回 status。
 */
const HIT_PLANS = { go: 'OpenCode Go', zen: 'OpenCode Zen', glm: 'GLM Coding Plan', mimo: 'MiMo Token Plan' };
const PLAN_COLORS = ['#4370f0', '#22c55e', '#f59e0b', '#8b5cf6', '#ef4444', '#06b6d4', '#a3e635', '#f472b6'];

/* 未命中四类的判定表达式（status 与 upstream_status 双通道，upstream_status 可能是 "502,502" 形式） */
const MISS_SQL = {
  quota: `SUM(CASE WHEN status = 402 OR COALESCE(upstream_status,'') LIKE '%402%' THEN 1 ELSE 0 END) AS e402`,
  forbid: `SUM(CASE WHEN status = 403 OR COALESCE(upstream_status,'') LIKE '%403%' THEN 1 ELSE 0 END) AS e403`,
  ratelimit: `SUM(CASE WHEN status = 429 OR COALESCE(upstream_status,'') LIKE '%429%' THEN 1 ELSE 0 END) AS e429`,
  server: `SUM(CASE WHEN status >= 500 AND status < 600 OR COALESCE(upstream_status,'') GLOB '*5[0-9][0-9]*' THEN 1 ELSE 0 END) AS e5`,
  noresp: `SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS e0`,
};
const MISS_WHERE = `(status = 402 OR COALESCE(upstream_status,'') LIKE '%402%'
      OR status = 403 OR COALESCE(upstream_status,'') LIKE '%403%'
      OR status = 429 OR COALESCE(upstream_status,'') LIKE '%429%'
      OR status >= 500 OR COALESCE(upstream_status,'') GLOB '*5[0-9][0-9]*'
      OR status = 0)`;

async function hitRate({ range } = {}) {
  range = String(range || '24h');
  if (!(range in RANGE_SEC)) bad('unknown range: ' + range);
  /* 1 秒 TTL：与 tokenhit 同样的轮询节奏，重叠请求共享一次计算 */
  return cached(1000, 'hitrate:' + range, () => computeHitRate(range));
}

async function computeHitRate(range) {
  /* 注意：SQL 会包在 bash 双引号里执行，`${TZH}` 必须用模板字符串先由 JS 插值成
     '+8 hours'，否则 bash 会把 ${TZH} 当环境变量展开成空串，datetime 变 NULL */
  const bucketExpr = (range === '7d' || range === 'all')
    ? `strftime('%m-%d', datetime(ts,'unixepoch','${TZH}'))`
    : `strftime('%m-%d %H', datetime(ts,'unixepoch','${TZH}'))`;
  const agent = getAgent();
  const r = await agent.run(`sqlite3 -json ${DB} "
    SELECT COALESCE(NULLIF(route,''),'unknown') AS route,
      COUNT(*) AS calls,
      SUM(CASE WHEN status BETWEEN 200 AND 299 THEN 1 ELSE 0 END) AS hit,
      ${MISS_SQL.quota}, ${MISS_SQL.forbid}, ${MISS_SQL.ratelimit}, ${MISS_SQL.server}, ${MISS_SQL.noresp},
      SUM(CASE WHEN status = 499 THEN 1 ELSE 0 END) AS abort,
      SUM(CASE WHEN status >= 400 AND status < 500 AND status NOT IN (402,403,429,499)
                AND NOT (${MISS_WHERE}) THEN 1 ELSE 0 END) AS c4,
      SUM(CASE WHEN attempts > 1 THEN 1 ELSE 0 END) AS retried,
      ROUND(AVG(rt),3) AS avg_rt, ROUND(AVG(ttfb),3) AS avg_ttfb,
      COUNT(DISTINCT NULLIF(model,'')) AS models,
      SUM(CASE WHEN model IS NULL OR model = '' OR model = 'unknown' THEN 1 ELSE 0 END) AS no_model
    FROM requests WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}')
    GROUP BY 1 ORDER BY calls DESC;
    SELECT ${bucketExpr} AS bucket, COALESCE(NULLIF(route,''),'unknown') AS route,
      COUNT(*) AS calls,
      SUM(CASE WHEN status BETWEEN 200 AND 299 THEN 1 ELSE 0 END) AS hit,
      SUM(CASE WHEN ${MISS_WHERE} THEN 1 ELSE 0 END) AS miss
    FROM requests WHERE ts >= strftime('%s','now','${RANGE_SQL[range]}')
    GROUP BY bucket, route ORDER BY bucket;
  " 2>/dev/null || echo '[]'`, { timeout: 30000 });

  const rows = parseSqlBlocks(r.stdout);

  const plans = (rows[0] || []).map((x) => {
    const miss = (x.e402 || 0) + (x.e403 || 0) + (x.e429 || 0) + (x.e5 || 0) + (x.e0 || 0);
    const denom = (x.hit || 0) + miss;
    const calls = x.calls || 0;
    return {
      plan: x.route, label: HIT_PLANS[x.route] || x.route,
      calls, hit: x.hit || 0, miss, denominator: denom,
      e402: x.e402 || 0, e403: x.e403 || 0, e429: x.e429 || 0, e5: x.e5 || 0, e0: x.e0 || 0,
      client4xx: x.c4 || 0, abort: x.abort || 0, retried: x.retried || 0,
      hitRate: denom ? +((x.hit || 0) / denom * 100).toFixed(2) : null,
      rawSuccessRate: calls ? +((x.hit || 0) / calls * 100).toFixed(2) : null,
      avgRt: x.avg_rt == null ? null : +x.avg_rt,
      avgTtfb: x.avg_ttfb == null ? null : +x.avg_ttfb,
      models: x.models || 0,
      modelCoverage: calls ? +((calls - (x.no_model || 0)) / calls * 100).toFixed(1) : 100,
    };
  });

  /* 时间序列：每个套餐一条命中率曲线（分母为 0 的小时留空，图表用 spanGaps 连接） */
  const tl = (rows[1] || []);
  const buckets = [...new Set(tl.map((t) => t.bucket))].sort().slice(-240);
  const series = plans.map((p, i) => {
    const byBucket = new Map(tl.filter((t) => t.route === p.plan).map((t) => [t.bucket, t]));
    return {
      plan: p.plan, label: p.label, color: PLAN_COLORS[i % PLAN_COLORS.length],
      points: buckets.map((b) => {
        const t = byBucket.get(b);
        if (!t) return null;
        const den = (t.hit || 0) + (t.miss || 0);
        return den ? +((t.hit || 0) / den * 100).toFixed(1) : null;
      }),
      calls: buckets.map((b) => (byBucket.get(b) || {}).calls || 0),
    };
  });

  const tot = plans.reduce((a, p) => ({
    calls: a.calls + p.calls, hit: a.hit + p.hit, miss: a.miss + p.miss,
    client4xx: a.client4xx + p.client4xx, abort: a.abort + p.abort, retried: a.retried + p.retried,
  }), { calls: 0, hit: 0, miss: 0, client4xx: 0, abort: 0, retried: 0 });
  const totDen = tot.hit + tot.miss;
  return {
    range,
    plans, buckets, series,
    totals: Object.assign({}, tot, {
      denominator: totDen,
      hitRate: totDen ? +(tot.hit / totDen * 100).toFixed(2) : null,
      rawSuccessRate: tot.calls ? +(tot.hit / tot.calls * 100).toFixed(2) : null,
    }),
    planLabels: HIT_PLANS,
  };
}

/* =============== Token 命中率（会话维度） ===============
 * 两个口径并排输出，避免"0%"被误读：
 *   A. 真实缓存命中率 —— 上游在响应里上报的缓存读取量（Anthropic 的
 *      cache_read_input_tokens / OpenAI 的 prompt_tokens_details.cached_tokens），
 *      口径与 collector 一致：cache_read ÷ (未命中输入 + cache_read)。
 *      当前多数上游不上报该字段，所以同时给 coverage（上报覆盖率）。
 *   B. 会话内上下文复用率 —— 不依赖厂商：编码代理每轮都会重发整个上下文，
 *      第 N 轮请求体里至少有 min(上一轮字节, 本轮字节) 是"前面已发过的内容"，
 *      这部分是缓存本该命中的上限。用 session_turns 的逐轮 sent_bytes 估算。
 */
async function tokenHit({ range, limit } = {}) {
  range = String(range || '24h');
  if (!(range in RANGE_SEC)) bad('unknown range: ' + range);
  if (range === 'all') ensureIndexes().catch(() => {});
  /* 1 秒 TTL：2 秒轮询下的重叠请求共享一次计算，慢查询不会堆积 */
  return cached(1000, 'tokenhit:' + range, () => computeTokenHit(range));
}

/* 全部聚合在 SQL 里完成（LAG 窗口 + GROUP BY），只回传 80 行以内的聚合结果。
   旧行为是把逐轮明细整个查出来在 JS 里聚合：7d/全部 有 6500+ 行，
   一次响应 1.3MB、解析 O(n²)，既拖慢面板进程（5 秒）又卡死浏览器。 */
async function computeTokenHit(range) {
  const agent = getAgent();
  const win = `strftime('%s','now','${RANGE_SQL[range]}')`;
  const REUSE = `
    WITH t AS (
      SELECT sess, COALESCE(route,'') AS route,
             COALESCE(NULLIF(model,''),'unknown') AS model, day, ts,
             COALESCE(sent_bytes,0) AS b,
             LAG(COALESCE(sent_bytes,0)) OVER (PARTITION BY sess ORDER BY ts) AS prev
      FROM session_turns WHERE ts >= ${win} AND COALESCE(sent_bytes,0) > 0
    )`;
  const REUSE_SUM = `SUM(CASE WHEN prev IS NULL THEN 0 ELSE MIN(b, prev) END)`;
  const r = await agent.run(`sqlite3 -json ${DB} "
    SELECT COUNT(*) AS events, COUNT(DISTINCT sess) AS sessions,
      SUM(COALESCE(input_tokens,0)) AS inp, SUM(COALESCE(cache_read,0)) AS cr,
      SUM(COALESCE(cache_write,0)) AS cw, SUM(COALESCE(output_tokens,0)) AS outp,
      SUM(CASE WHEN COALESCE(cache_read,0) > 0 OR COALESCE(cache_write,0) > 0 THEN 1 ELSE 0 END) AS with_cache
    FROM usage_events WHERE ts >= ${win} AND COALESCE(total_tokens,0) > 0;
    SELECT COALESCE(NULLIF(model,''),'unknown') AS model, COUNT(*) AS events,
      SUM(COALESCE(input_tokens,0)) AS inp, SUM(COALESCE(cache_read,0)) AS cr,
      SUM(COALESCE(cache_write,0)) AS cw
    FROM usage_events WHERE ts >= ${win} AND COALESCE(total_tokens,0) > 0
    GROUP BY 1 ORDER BY inp DESC LIMIT 30;
    SELECT day, COUNT(*) AS events, SUM(COALESCE(input_tokens,0)) AS inp, SUM(COALESCE(cache_read,0)) AS cr
    FROM usage_events WHERE ts >= ${win} AND COALESCE(total_tokens,0) > 0
    GROUP BY day ORDER BY day;
    ${REUSE}
    SELECT sess, MAX(route) AS route, COUNT(*) AS turns, SUM(b) AS total,
      ${REUSE_SUM} AS reuse, MAX(b) AS max_turn, MAX(day) AS last_day,
      GROUP_CONCAT(DISTINCT model) AS models
    FROM t GROUP BY sess ORDER BY total DESC LIMIT 80;
    SELECT COUNT(*) AS events, COUNT(DISTINCT sess) AS sessions,
      SUM(COALESCE(input_tokens,0)) AS inp, SUM(COALESCE(cache_read,0)) AS cr
    FROM usage_events
    WHERE ts >= strftime('%s','now','-300 seconds') AND COALESCE(total_tokens,0) > 0;
    ${REUSE}
    SELECT day, COUNT(*) AS turns, SUM(b) AS total, ${REUSE_SUM} AS reuse
    FROM t GROUP BY day ORDER BY day;
    ${REUSE}
    SELECT model, COUNT(*) AS turns, COUNT(DISTINCT sess) AS sessions,
      SUM(b) AS total, ${REUSE_SUM} AS reuse
    FROM t GROUP BY model ORDER BY total DESC LIMIT 30;
    ${REUSE}
    SELECT COUNT(*) AS sessions, SUM(CASE WHEN turns >= 2 THEN 1 ELSE 0 END) AS multi,
      SUM(turns) AS turns, SUM(total) AS total, SUM(reuse) AS reuse,
      AVG(CASE WHEN turns >= 2 AND total > 0 THEN 1.0 * reuse / total * 100 END) AS avg_multi
    FROM (SELECT sess, COUNT(*) AS turns, SUM(b) AS total, ${REUSE_SUM} AS reuse
          FROM t GROUP BY sess);
    SELECT COUNT(*) AS turns, COUNT(DISTINCT sess) AS sessions,
      SUM(COALESCE(sent_bytes,0)) AS bytes
    FROM session_turns
    WHERE ts >= strftime('%s','now','-300 seconds') AND COALESCE(sent_bytes,0) > 0;
  " 2>/dev/null || echo '[]'`, { timeout: 30000 });

  const rows = parseSqlBlocks(r.stdout);
  const tot = (rows[0] && rows[0][0]) || {};
  const byModel = rows[1] || [];
  const byDay = rows[2] || [];
  const topRaw = rows[3] || [];
  const liveUsage = (rows[4] && rows[4][0]) || {};
  const reuseByDay = rows[5] || [];
  const reuseByModel = rows[6] || [];
  const summary = (rows[7] && rows[7][0]) || {};
  const liveTurns = (rows[8] && rows[8][0]) || {};

  const rateOf = (hit, base) => (base > 0 ? +(hit / base * 100).toFixed(2) : null);

  /* ---- A. 厂商上报的真实缓存命中率 ---- */
  const inp = tot.inp || 0, cr = tot.cr || 0;
  const vendor = {
    events: tot.events || 0, sessions: tot.sessions || 0,
    inputNew: inp, cacheRead: cr, cacheWrite: tot.cw || 0, output: tot.outp || 0,
    hitRate: rateOf(cr, inp + cr),
    /* 上报覆盖率：有多少事件真的带了缓存字段——用来区分"没命中"和"没上报" */
    coverageEvents: tot.with_cache || 0,
    coveragePct: rateOf(tot.with_cache || 0, tot.events || 0),
    byModel: byModel.map((m) => ({
      model: m.model, events: m.events || 0, inputNew: m.inp || 0,
      cacheRead: m.cr || 0, cacheWrite: m.cw || 0,
      hitRate: rateOf(m.cr || 0, (m.inp || 0) + (m.cr || 0)),
    })),
    byDay: byDay.map((d) => ({
      day: d.day, events: d.events || 0, inputNew: d.inp || 0, cacheRead: d.cr || 0,
      hitRate: rateOf(d.cr || 0, (d.inp || 0) + (d.cr || 0)),
    })),
  };

  /* ---- B. 会话内上下文复用率（SQL 聚合，不依赖厂商） ---- */
  const top = topRaw.map((s) => {
    const total = Number(s.total) || 0, reuse = Number(s.reuse) || 0;
    const models = String(s.models || '').split(',').filter(Boolean);
    return {
      sess: s.sess, route: s.route || '', turns: Number(s.turns) || 0,
      totalBytes: total, reuseBytes: reuse, newBytes: Math.max(0, total - reuse),
      reuseRate: rateOf(reuse, total),
      avgPerTurn: Math.round(total / Math.max(1, Number(s.turns) || 1)),
      maxTurn: Number(s.max_turn) || 0,
      models: models.slice(0, 4).join(',') + (models.length > 4 ? '…' : ''),
      lastDay: s.last_day || '',
    };
  });
  const sumTotal = Number(summary.total) || 0;
  const sumReuse = Number(summary.reuse) || 0;
  const sessCount = Number(summary.sessions) || 0;
  const multiCount = Number(summary.multi) || 0;
  const reuse = {
    sessions: sessCount,
    multiTurnSessions: multiCount,
    singleTurnSessions: Math.max(0, sessCount - multiCount),
    turns: Number(summary.turns) || 0,
    totalBytes: sumTotal, reuseBytes: sumReuse, newBytes: Math.max(0, sumTotal - sumReuse),
    reuseRate: rateOf(sumReuse, sumTotal),
    /* 多轮会话的平均复用率（单轮没有轮间复用，混进来会把平均拉到 0） */
    avgSessionReuse: summary.avg_multi == null ? null : +Number(summary.avg_multi).toFixed(2),
    top,
    byDay: reuseByDay.map((d) => ({
      day: d.day, turns: Number(d.turns) || 0, totalBytes: Number(d.total) || 0,
      reuseBytes: Number(d.reuse) || 0, reuseRate: rateOf(d.reuse, d.total),
    })),
    byModel: reuseByModel.map((m) => ({
      model: m.model, turns: Number(m.turns) || 0, sessions: Number(m.sessions) || 0,
      totalBytes: Number(m.total) || 0, reuseBytes: Number(m.reuse) || 0,
      reuseRate: rateOf(m.reuse, m.total),
    })),
  };

  return {
    range,
    vendor,
    reuse,
    /* 两个口径的差距：理论可复用比例 - 真实命中比例（百分点） */
    gap: (vendor.hitRate != null && reuse.reuseRate != null)
      ? +((reuse.reuseRate - vendor.hitRate).toFixed(2)) : null,
    /* 近 5 分钟活动：给实时刷新一个看得见在跳的信号 */
    live: {
      minutes: 5,
      turns: Number(liveTurns.turns) || 0,
      sessions: Number(liveTurns.sessions) || 0,
      bytes: Number(liveTurns.bytes) || 0,
      events: liveUsage.events || 0,
      tokens: liveUsage.inp || 0,
      cacheRead: liveUsage.cr || 0,
    },
  };
}

/* 启动即补齐 ts 索引（一次性，失败下次调用再试） */
setImmediate(() => { ensureIndexes().catch(() => {}); });

module.exports = { sloStats, sloPrev, sloBudget, usageCost, quotaForecast, errorBuckets, correlate, correlateSummary, hitRate, tokenHit, RANGE_SEC, readJson, PRICING, parseSqlBlocks };
