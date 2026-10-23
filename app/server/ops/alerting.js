'use strict';
/*
 * Alert rule engine. The panel shipped with 4 hard-coded thresholds
 * (cpu/mem/disk/swap) and no way to notify a human. This adds: arbitrary
 * metric + operator + duration (forSec) rules, per-rule cooldown, silence and
 * maintenance windows, recovery notifications, and dispatch to the channels in
 * notify.js. Firing still goes through hub.fireAlert so the existing bell and
 * WebSocket keep working.
 */
const S = require('./store');
const notify = require('./notify');

const collectors = S.soft('../collectors');
const hub = S.soft('../hub');

const RULES = 'alert_rules.json';
const SILENCES = 'alert_silences.json';
const STATE = 'alert_state.json';
const EVENTS = 'alert_events.jsonl';

const OPS = { '>=': '≥', '>': '>', '<=': '≤', '<': '<' };
const LEVELS = { warn: '警告', bad: '严重', info: '提示' };

/** Metrics evaluated by rules. Field names follow api-dump/ exactly. */
const METRICS = {
  cpuPct: { label: 'CPU 使用率', unit: '%', src: 'cpu.totalPct', get: (m) => m.cpu && num(m.cpu.totalPct) },
  memPct: { label: '内存使用率', unit: '%', src: 'memory.actualUsedPct', get: (m) => m.memory && num(m.memory.actualUsedPct) },
  memAvailMb: { label: '可用内存', unit: 'MB', src: 'memory.available', get: (m) => m.memory && div(m.memory.available, 1048576) },
  swapPct: { label: 'Swap 使用率', unit: '%', src: 'memory.swapPct', get: (m) => m.memory && num(m.memory.swapPct), needs: (m) => m.memory && m.memory.swapTotal > 0 },
  load1: { label: '1 分钟负载', unit: '', src: 'cpu.loadAvg[0]', get: (m) => m.cpu && Array.isArray(m.cpu.loadAvg) && m.cpu.loadAvg.length ? num(m.cpu.loadAvg[0]) : null },
  diskMaxPct: { label: '磁盘使用率（最高分区）', unit: '%', src: 'disks.mounts[].pct', disk: true, get: (m, d) => maxPct(d) },
  diskAvailGb: { label: '磁盘剩余（最低分区）', unit: 'GB', src: 'disks.mounts[].avail', disk: true, get: (m, d) => minAvailGb(d) },
  diskReadBps: { label: '磁盘读吞吐', unit: 'MB/s', src: 'diskio.readBps', get: (m) => m.diskio && div(m.diskio.readBps, 1048576) },
  diskWriteBps: { label: '磁盘写吞吐', unit: 'MB/s', src: 'diskio.writeBps', get: (m) => m.diskio && div(m.diskio.writeBps, 1048576) },
  netRxKbps: { label: '网络入向', unit: 'KB/s', src: 'net.ifaces[].rxBps', get: (m) => m.net && netSum(m.net.ifaces, 'rxBps', 1024) },
  netTxKbps: { label: '网络出向', unit: 'KB/s', src: 'net.ifaces[].txBps', get: (m) => m.net && netSum(m.net.ifaces, 'txBps', 1024) },
  procsRunning: { label: '运行中进程数', unit: '', src: 'cpu.procs.running', get: (m) => m.cpu && m.cpu.procs && num(m.cpu.procs.running) },
  probeDown: { label: '拨测失败任务数', unit: '个', src: 'ops/uptime', external: true, get: () => upSummary().down },
  notifyFailed: { label: '近 1 小时通知失败次数', unit: '次', src: 'ops/notify', external: true, get: () => notify.recent(400).filter((r) => !r.ok && r.ts > S.now() - 3600000).length },
};

function num(v) { return typeof v === 'number' && Number.isFinite(v) ? v : (v === undefined || v === null ? null : Number(v)); }
function div(v, d) { const n = num(v); return n == null ? null : n / d; }
/**
 * /boot, /boot/efi and container overlays are tiny and routinely "full"; judging
 * them as capacity problems produces false pages, so rules only see real volumes.
 */
const REAL_FS_BYTES = 1073741824;
function realMounts(d) {
  if (!d || !Array.isArray(d.mounts)) return [];
  return d.mounts.filter((x) => num(x.total) != null && num(x.total) >= REAL_FS_BYTES);
}
function maxPct(d) {
  const m = realMounts(d);
  const pcts = m.map((x) => num(x.pct)).filter((x) => x != null);
  return pcts.length ? Math.max.apply(null, pcts) : null;
}
function minAvailGb(d) {
  const m = realMounts(d);
  const a = m.map((x) => div(x.avail, REAL_FS_BYTES)).filter((x) => x != null);
  return a.length ? Math.min.apply(null, a) : null;
}
function netSum(ifaces, key, scale) {
  if (!ifaces) return null;
  let sum = 0;
  for (const name of Object.keys(ifaces)) {
    if (name === 'lo') continue;
    sum += num(ifaces[name][key]) || 0;
  }
  return sum / scale;
}

const listRules = () => S.readJson(RULES, { items: [] }).items;
const listSilences = () => S.readJson(SILENCES, { items: [] }).items;
const stateOf = () => S.readJson(STATE, { rules: {} });

async function sample() {
  if (!collectors || typeof collectors.getMetrics !== 'function') return null;
  const m = await collectors.getMetrics();
  let d = null;
  if (typeof collectors.getDisks === 'function') { try { d = await collectors.getDisks(); } catch (_) {} }
  const out = {};
  for (const key of Object.keys(METRICS)) {
    const spec = METRICS[key];
    let v = null;
    try { if (!spec.needs || spec.needs(m, d)) v = spec.get(m, d); } catch (_) { v = null; }
    out[key] = v == null || !Number.isFinite(Number(v)) ? null : Number(v);
  }
  return { values: out, ts: num(m.ts) || S.now() };
}

function upSummary() {
  try { return require('./uptime').summary(); } catch (_) { return { down: 0, total: 0 }; }
}

/* ---------------- silence / maintenance windows ---------------- */

function inWindow(w, now) {
  if (w.kind === 'daily') {
    const days = Array.isArray(w.days) && w.days.length ? w.days : [0, 1, 2, 3, 4, 5, 6];
    const dt = new Date(now);
    if (!days.includes(dt.getDay())) return false;
    const mins = dt.getHours() * 60 + dt.getMinutes();
    const a = hm(w.from, 0), b = hm(w.to, 1439);
    return a <= b ? (mins >= a && mins <= b) : (mins >= a || mins <= b);
  }
  return now >= (Number(w.startsAt) || 0) && now <= (Number(w.endsAt) || 0);
}
const hm = (s, dflt) => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || ''));
  return m ? Number(m[1]) * 60 + Number(m[2]) : dflt;
};

function silenceFor(rule, now) {
  return listSilences().find((w) => {
    if (w.enabled === false) return false;
    if (!inWindow(w, now || S.now())) return false;
    if (w.scope === '*') return true;
    if (w.scope === 'rule' && w.ruleId === rule.id) return true;
    if (w.scope === 'metric' && w.metric === rule.metric) return true;
    return false;
  }) || null;
}

/* ---------------- the raise primitive (used by uptime too) ---------------- */

/**
 * Deduplicated event: push into the panel's alert buffer + WebSocket (so the bell
 * still works), then fan out to channels. `key` stable across repeats is what
 * makes the cooldown per-rule instead of per-message.
 */
async function raiseEvent(o) {
  const key = String(o.key || 'event');
  const cooldown = Math.max(0, Number(o.cooldownSec != null ? o.cooldownSec : 600));
  const st = stateOf();
  st.events = st.events || {};
  const last = st.events[key] || 0;
  if (cooldown > 0 && S.now() - last < cooldown * 1000) return { ok: true, suppressed: 'cooldown' };
  st.events[key] = S.now();
  const evs = Object.keys(st.events);
  if (evs.length > 400) { for (const k of evs.slice(0, evs.length - 400)) delete st.events[k]; }
  S.writeJson(STATE, st);

  const msg = notify.buildMessage({
    title: o.title, text: o.text, level: LEVELS[o.level] ? o.level : 'warn',
    host: require('os').hostname(), link: o.link, fields: o.fields,
  });
  S.appendLine(EVENTS, { ts: msg.ts, key, level: msg.level, title: msg.title, source: o.source || 'rule' });
  S.trimLineFile(EVENTS, 4 * 1024 * 1024);

  if (hub && typeof hub.fireAlert === 'function') {
    try { hub.fireAlert(key.slice(0, 48), msg.level, `${msg.title} ${msg.text}`.trim()); } catch (_) {}
  }
  const dispatch = await notify.dispatch(msg, Array.isArray(o.channelIds) ? o.channelIds : null, {
    source: o.source || 'rule', ruleId: o.ruleId || null,
  });
  return { ok: true, dispatched: dispatch.sent, results: dispatch.targets, message: msg };
}

/* ---------------- engine ---------------- */

let timer = null;
let running = false;
const lastValues = { ts: 0, values: {} };

function ruleText(r, value, state) {
  const spec = METRICS[r.metric] || { label: r.metric, unit: '' };
  const cmp = OPS[r.op] || r.op;
  if (state === 'recovered') {
    return `${spec.label}已恢复：当前 ${fmtVal(value)}${spec.unit}（阈值 ${cmp} ${r.threshold}${spec.unit}）`;
  }
  const dur = r.forSec > 0 ? `，持续 ${Math.round(r.forSec / 60)} 分钟` : '';
  return `${spec.label} ${fmtVal(value)}${spec.unit}，阈值 ${cmp} ${r.threshold}${spec.unit}${dur}`;
}
const fmtVal = (v) => (Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(2));

async function evaluate() {
  if (running) return;
  running = true;
  try {
    const rules = listRules().filter((r) => r.enabled !== false);
    if (!rules.length) return;
    const s = await sample();
    if (!s) return;
    lastValues.ts = s.ts; lastValues.values = s.values;
    const st = stateOf();
    st.rules = st.rules || {};
    for (const r of rules) {
      const spec = METRICS[r.metric];
      if (!spec) continue;
      const v = s.values[r.metric];
      if (v == null) continue;
      const breach = r.op === '<' ? v < r.threshold : r.op === '<=' ? v <= r.threshold : r.op === '>' ? v > r.threshold : v >= r.threshold;
      const rs = st.rules[r.id] || { state: 'ok', breachSince: 0, firedAt: 0 };
      const forSec = Math.max(0, Number(r.forSec) || 0);
      if (breach) {
        if (!rs.breachSince) rs.breachSince = S.now();
        const held = S.now() - rs.breachSince >= forSec * 1000;
        const cooldownMs = Math.max(60, Number(r.cooldownSec) || 900) * 1000;
        if (held) {
          const sil = silenceFor(r, S.now());
          if (sil) {
            rs.state = 'silenced'; rs.silenceName = sil.name;
          } else if (!rs.firedAt || S.now() - rs.firedAt >= cooldownMs) {
            const res = await raiseEvent({
              key: 'rule:' + r.id, level: r.level || 'warn', source: 'rule', ruleId: r.id,
              title: (r.name || spec.label) + ' 触发告警', text: ruleText(r, v, 'breach'),
              fields: [{ k: '指标', v: `${spec.label}（${spec.src}）` }, { k: '当前值', v: `${fmtVal(v)}${spec.unit}` }, { k: '条件', v: `${OPS[r.op] || r.op} ${r.threshold}${spec.unit}` }, { k: '持续', v: `${Math.round((S.now() - rs.breachSince) / 60000)} 分钟` }],
              cooldownSec: 0,
            });
            rs.firedAt = S.now(); rs.state = 'fired'; rs.lastResult = res.suppressed ? 'suppressed' : 'sent';
            delete rs.silenceName;
          }
        } else {
          rs.state = 'breaching';
        }
      } else {
        if (rs.breachSince && (S.now() - rs.breachSince) >= forSec * 1000 && rs.state === 'fired') {
          if (r.notifyRecover !== false) {
            await raiseEvent({
              key: 'rule:' + r.id + ':recovered', level: 'ok', source: 'rule', ruleId: r.id,
              title: (r.name || spec.label) + ' 已恢复', text: ruleText(r, v, 'recovered'), cooldownSec: 0,
            });
          }
        }
        rs.breachSince = 0; rs.firedAt = 0; rs.state = 'ok';
      }
      rs.value = v; rs.updatedAt = S.now();
      st.rules[r.id] = rs;
    }
    S.writeJson(STATE, st);
  } finally { running = false; }
}

function start(intervalMs) {
  stop();
  const ms = Math.max(5000, intervalMs || 15000);
  timer = setInterval(() => { evaluate().catch((e) => S.util.logErr && S.util.logErr('alerting eval:', e.message)); }, ms);
  if (timer.unref) timer.unref();
  evaluate().catch(() => {});
}
function stop() { if (timer) { clearInterval(timer); timer = null; } }

/* ---------------- CRUD ---------------- */

function normalizeRule(b, existing) {
  const metric = METRICS[b.metric] ? b.metric : (existing && existing.metric) || 'cpuPct';
  const op = OPS[b.op] ? b.op : (existing && existing.op) || '>=';
  const level = LEVELS[b.level] ? b.level : (existing && existing.level) || 'warn';
  return {
    id: (existing && existing.id) || S.genId('r'),
    name: String(b.name != null ? b.name : (existing && existing.name) || '').trim().slice(0, 60) || METRICS[metric].label,
    metric, op, level,
    threshold: S.clampNum(b.threshold, -1e9, 1e9, existing ? Number(existing.threshold) || 0 : 90),
    forSec: Math.round(S.clampNum(b.forSec, 0, 86400, existing ? Number(existing.forSec) || 0 : 60)),
    cooldownSec: Math.round(S.clampNum(b.cooldownSec, 60, 86400, existing ? Number(existing.cooldownSec) || 900 : 900)),
    channelIds: Array.isArray(b.channelIds) ? b.channelIds.map(String).slice(0, 20) : (existing && existing.channelIds) || [],
    notifyRecover: b.notifyRecover === undefined ? (existing ? existing.notifyRecover !== false : true) : !!b.notifyRecover,
    enabled: b.enabled === undefined ? (existing ? existing.enabled !== false : true) : !!b.enabled,
    note: String(b.note || '').slice(0, 200),
    createdAt: (existing && existing.createdAt) || S.now(),
    updatedAt: S.now(),
  };
}

function saveRule(body) {
  const items = listRules();
  const existing = body.id ? items.find((r) => r.id === body.id) : null;
  if (body.id && !existing) { const e = new Error('规则不存在：' + body.id); e.status = 404; throw e; }
  if (!METRICS[body.metric] && !existing) { const e = new Error('未知指标：' + body.metric); e.status = 400; throw e; }
  const r = normalizeRule(body, existing);
  if (existing) items.splice(items.indexOf(existing), 1, r); else items.push(r);
  S.writeJson(RULES, { items });
  return { ok: true, rule: r };
}

function deleteRule(body) {
  const items = listRules();
  const idx = items.findIndex((r) => r.id === body.id);
  if (idx < 0) { const e = new Error('规则不存在：' + body.id); e.status = 404; throw e; }
  const [gone] = items.splice(idx, 1);
  const st = stateOf(); if (st.rules) delete st.rules[gone.id];
  S.writeJson(RULES, { items }); S.writeJson(STATE, st);
  return { ok: true, removed: gone.id };
}

function saveSilence(body) {
  const items = listSilences();
  const existing = body.id ? items.find((w) => w.id === body.id) : null;
  const w = {
    id: (existing && existing.id) || S.genId('s'),
    name: String(body.name || '维护窗口').slice(0, 60),
    kind: body.kind === 'daily' ? 'daily' : 'once',
    scope: ['*', 'rule', 'metric'].includes(body.scope) ? body.scope : (existing && existing.scope) || '*',
    ruleId: body.ruleId ? String(body.ruleId) : (existing && existing.ruleId) || null,
    metric: body.metric && METRICS[body.metric] ? body.metric : (existing && existing.metric) || null,
    startsAt: Number(body.startsAt) || S.now(),
    endsAt: Number(body.endsAt) || S.now() + 3600000,
    from: /^\d{1,2}:\d{2}$/.test(String(body.from || '')) ? body.from : (existing && existing.from) || '01:00',
    to: /^\d{1,2}:\d{2}$/.test(String(body.to || '')) ? body.to : (existing && existing.to) || '05:00',
    days: Array.isArray(body.days) ? body.days.map(Number).filter((d) => d >= 0 && d <= 6) : (existing && existing.days) || [0, 1, 2, 3, 4, 5, 6],
    reason: String(body.reason || '').slice(0, 200),
    enabled: body.enabled === undefined ? true : !!body.enabled,
  };
  if (w.kind === 'once' && w.endsAt <= w.startsAt) { const e = new Error('结束时间必须晚于开始时间'); e.status = 400; throw e; }
  if (existing) items.splice(items.indexOf(existing), 1, w); else items.push(w);
  S.writeJson(SILENCES, { items });
  return { ok: true, silence: w };
}

function deleteSilence(body) {
  const items = listSilences();
  const idx = items.findIndex((w) => w.id === body.id);
  if (idx < 0) { const e = new Error('静默窗口不存在：' + body.id); e.status = 404; throw e; }
  const [gone] = items.splice(idx, 1);
  S.writeJson(SILENCES, { items });
  return { ok: true, removed: gone.id };
}

function summary() {
  const rules = listRules();
  const st = stateOf();
  const active = Object.entries(st.rules || {}).filter(([, v]) => v.state === 'fired' || v.state === 'breaching' || v.state === 'silenced');
  return {
    total: rules.length, enabled: rules.filter((r) => r.enabled !== false).length,
    firing: active.filter(([, v]) => v.state === 'fired').length,
    breaching: active.filter(([, v]) => v.state === 'breaching').length,
    silenced: active.filter(([, v]) => v.state === 'silenced').length,
    silences: listSilences().filter((w) => w.enabled !== false && inWindow(w, S.now())).length,
  };
}

function register(api, wrap, audited) {
  api.get('/ops/alerts/rules', wrap(async (req, res) => {
    const st = stateOf();
    res.json({
      ok: true,
      metrics: Object.entries(METRICS).map(([k, v]) => ({ key: k, label: v.label, unit: v.unit, source: v.src, external: !!v.external })),
      ops: Object.entries(OPS).map(([k, v]) => ({ key: k, label: v })),
      levels: Object.entries(LEVELS).map(([k, v]) => ({ key: k, label: v })),
      items: listRules().map((r) => Object.assign({}, r, { state: (st.rules[r.id] || {}).state || 'ok', currentValue: (st.rules[r.id] || {}).value == null ? null : (st.rules[r.id] || {}).value })),
      engine: { running: !!timer, lastEval: lastValues.ts, values: lastValues.values },
    });
  }));
  api.get('/ops/alerts/silences', wrap(async (req, res) => {
    const now = S.now();
    res.json({ ok: true, items: listSilences().map((w) => Object.assign({}, w, { active: w.enabled !== false && inWindow(w, now) })) });
  }));
  api.get('/ops/alerts/events', wrap(async (req, res) => res.json({ ok: true, items: S.readLines(EVENTS, Number(req.query.limit) || 200) })));
  api.post('/ops/alerts/rule/save', audited('ops.rule.save', async (req) => saveRule(req.body || {})));
  api.post('/ops/alerts/rule/delete', audited('ops.rule.delete', async (req) => deleteRule(req.body || {})));
  api.post('/ops/alerts/rule/toggle', audited('ops.rule.toggle', async (req) => {
    const b = req.body || {};
    const items = listRules();
    const r = items.find((x) => x.id === b.id);
    if (!r) { const e = new Error('规则不存在：' + b.id); e.status = 404; throw e; }
    r.enabled = b.enabled === undefined ? r.enabled === false : !!b.enabled;
    r.updatedAt = S.now();
    S.writeJson(RULES, { items });
    return { ok: true, rule: r };
  }));
  api.post('/ops/alerts/rule/test', audited('ops.rule.test', async (req) => {
    const b = req.body || {};
    const r = normalizeRule(b, b.id ? listRules().find((x) => x.id === b.id) : null);
    const s = await sample();
    const v = s ? s.values[r.metric] : null;
    const res = await raiseEvent({
      key: 'rule-test:' + r.id + ':' + S.now(), level: r.level, source: 'test', ruleId: r.id, cooldownSec: 0,
      title: (r.name || METRICS[r.metric].label) + '（测试）',
      text: `这是规则试跑。当前 ${METRICS[r.metric].label} = ${v == null ? '无数据' : fmtVal(v) + METRICS[r.metric].unit}，条件 ${OPS[r.op]} ${r.threshold}${METRICS[r.metric].unit}`,
    });
    return { ok: true, currentValue: v, result: res };
  }));
  api.post('/ops/alerts/silence/save', audited('ops.silence.save', async (req) => saveSilence(req.body || {})));
  api.post('/ops/alerts/silence/delete', audited('ops.silence.delete', async (req) => deleteSilence(req.body || {})));
}

module.exports = {
  METRICS, LEVELS, OPS, register, start, stop, evaluate, sample, raiseEvent, silenceFor, inWindow,
  listRules, saveRule, deleteRule, listSilences, saveSilence, deleteSilence, summary, stateOf, EVENTS,
};
