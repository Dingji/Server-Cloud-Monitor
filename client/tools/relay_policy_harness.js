// Behavioural harness for the busy-hours policy tab.
// It extracts the *deployed* relay.js function block and runs it against a stub DOM with
// real collector payloads, so 保存 / 试算 / 临时停用 / 日历 are proven to execute -- the
// live bug was precisely "handler wired, function body missing", which no syntax check sees.
//
// usage: node tools/relay_policy_harness.js <relay.js from the server> <busy.sample.json>
const fs = require('fs');

const JS = process.argv[2] || 'upstream/live-now/relay.js.deployed';
const SAMPLE = process.argv[3] || 'upstream/live-now/busy.sample.json';
const src = fs.readFileSync(JS, 'utf8');
const fx = JSON.parse(fs.readFileSync(SAMPLE, 'utf8'));

/* ---------- minimal DOM ---------- */
function el(opts) {
  opts = opts || {};
  const e = {
    id: opts.id || '', value: opts.value || '', checked: !!opts.checked,
    innerHTML: '', textContent: '', children: [], attrs: opts.attrs || {},
    classes: new Set(opts.classes || []), style: {}, dataset: opts.dataset || {},
    parentNode: null,
    classList: {
      add: function () { for (const c of arguments) e.classes.add(c); },
      remove: function () { for (const c of arguments) e.classes.delete(c); },
      toggle: function (c, on) { if (on === undefined) on = !e.classes.has(c); on ? e.classes.add(c) : e.classes.delete(c); },
      contains: function (c) { return e.classes.has(c); },
    },
    appendChild: function (c) { c.parentNode = e; e.children.push(c); return c; },
    remove: function () { if (e.parentNode) e.parentNode.children = e.parentNode.children.filter((x) => x !== e); },
    focus: function () {},
    closest: function (sel) { let p = e; while (p) { if (match(p, sel)) return p; p = p.parentNode; } return null; },
    querySelector: function (sel) { const r = walk(e, sel); return r.length ? r[0] : null; },
    querySelectorAll: function (sel) { return walk(e, sel); },
  };
  return e;
}
function match(node, sel) {
  sel = String(sel).trim();
  if (sel.startsWith('#')) return node.id === sel.slice(1);
  if (sel.startsWith('.')) return node.classes.has(sel.slice(1));
  const m = /^\[([\w-]+)="([^"]*)"\]$/.exec(sel);
  if (m) return String(node.attrs[m[1]]) === m[2];
  return false;
}
function walk(root, sel) {
  const out = [];
  (function visit(n) {
    for (const c of n.children || []) { if (match(c, sel)) out.push(c); visit(c); }
  })(root);
  return out;
}

const store = new Map();
function reg(o) { const e = el(o); store.set(e.id, e); return e; }
['rl-pol-state', 'rl-pol-holidays', 'rl-pol-cal-src', 'rl-pol-editor', 'rl-pol-on', 'rl-pol-wd',
  'rl-pol-route', 'rl-pol-target', 'rl-pol-tz', 'rl-pol-off', 'rl-pol-pre', 'rl-pol-switch',
].forEach((id) => reg({ id: id }));

const editor = store.get('rl-pol-editor');
const wins = reg({ id: 'rl-pol-wins', classes: ['bp-wins'] });
editor.appendChild(wins);
const cfg = fx.baseline.config;
cfg.windows.forEach((w) => {
  const row = el({ classes: ['bp-win-row', 'rl-pol-win'] });
  const s = el({ value: w.start, attrs: { 'data-k': 'start' } });
  const e2 = el({ value: w.end, attrs: { 'data-k': 'end' } });
  row.appendChild(s); row.appendChild(e2);
  wins.appendChild(row);
});
const on = store.get('rl-pol-on'), wd = store.get('rl-pol-wd');
on.checked = cfg.enabled; wd.checked = cfg.weekdays_only;
store.get('rl-pol-route').value = cfg.route;
store.get('rl-pol-target').value = cfg.target_model;
store.get('rl-pol-tz').value = String(cfg.utc_offset_minutes);
store.get('rl-pol-off').value = String(cfg.utc_offset_minutes);
store.get('rl-pol-pre').value = cfg.rewrite_prefixes.join(', ');

/* ---------- stubs ---------- */
const posts = [], gets = [], toasts = [], confirms = [], prompts = [];
let refreshes = 0, nextPrompt = null;
const CM = {
  api: {
    get: async function (p) {
      gets.push(p);
      const m = /[?&]at=([^&]*)/.exec(p);
      if (!m) return fx.baseline;
      const key = decodeURIComponent(m[1]);
      return fx.by_at[key] || fx.baseline;
    },
    post: async function (path, body) { posts.push({ path: path, body: body }); return fx.save_reply || { ok: true, detail: '已保存' }; },
  },
};
const modal = {
  confirm: async function (t, msg, o) { confirms.push({ t: t, msg: msg, o: o }); return true; },
  prompt: async function (t, fields, o) { prompts.push({ t: t, fields: fields }); return nextPrompt; },
};
const ui = { busy: function () {} };
const fmt = { esc: function (s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;'); } };
const windowObj = { toast: function (m, k, ms) { toasts.push({ m: m, k: k }); } };
const documentObj = {
  getElementById: function (id) { return store.get(id) || null; },
  querySelector: function (sel) { const m = /^#([\w-]+)$/.exec(sel); return m ? (store.get(m[1]) || null) : null; },
  querySelectorAll: function () { return []; },
  createElement: function () { return el({}); },
};

/* ---------- load ONLY the deployed function block ---------- */
const lines = src.split('\n');
const from = lines.findIndex((l) => l.includes('let POLICY_BASE'));
const to = lines.findIndex((l) => l.includes('/* ============ actions ============ */'));
if (from < 0 || to < 0 || to < from) throw new Error('could not slice the policy block (from=' + from + ' to=' + to + ')');
const block = lines.slice(from, to).join('\n');
console.log('sliced %d lines: %s', to - from, block.split('\n').filter((l) => /function |let POLICY_BASE/.test(l)).map((l) => l.trim().replace(/ .*/, '')).join(', '));

const api = new Function('document', 'window', 'CM', 'modal', 'ui', 'fmt', 'refreshNow',
  block + '\nreturn {paintCalendar:paintCalendar, readPolicyDraft:readPolicyDraft, savePolicy:savePolicy, togglePolicySwitch:togglePolicySwitch, previewPolicy:previewPolicy, draftDiffers:draftDiffers, phaseBadge:phaseBadge, setBase:function(d){POLICY_BASE=d;}};'
)(documentObj, windowObj, CM, modal, ui, fmt, function () { refreshes++; });

/* ---------- assertions ---------- */
let fails = 0;
function ck(name, cond, detail) {
  console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (detail ? '   [' + detail + ']' : ''));
  if (!cond) fails++;
}

console.log('\n--- 日历（服务端同源） ---');
api.setBase(fx.baseline);
api.paintCalendar(fx.baseline);
const cal = store.get('rl-pol-holidays').innerHTML;
ck('2026 渲染出假期条目', cal.indexOf('休假') >= 0 && cal.indexOf('调休上班') >= 0);
ck('含国庆', cal.indexOf('国庆') >= 0, cal.slice(0, 60));
ck('年份统计来自服务端', cal.indexOf('25') >= 0 && cal.indexOf('休假 19') >= 0 && cal.indexOf('调休上班 6') >= 0);
ck('数据源标注为服务端库', store.get('rl-pol-cal-src').innerHTML.indexOf('chinese_calendar') >= 0 ||
   store.get('rl-pol-cal-src').textContent.indexOf('chinese_calendar') >= 0, store.get('rl-pol-cal-src').textContent);
api.paintCalendar(fx.by_at['2027-01-01 10:30']);
ck('2027 给出回落说明而不是“加载失败”', store.get('rl-pol-holidays').innerHTML.indexOf('没有官方节假日数据') >= 0,
   store.get('rl-pol-holidays').innerHTML.slice(0, 70));
api.paintCalendar({ __error: '中继采集器不可用' });
ck('接口挂了时日历降级为可读提示', store.get('rl-pol-holidays').innerHTML.indexOf('不可用') >= 0);

console.log('\n--- 忙/闲徽标 ---');
const busyHtml = api.phaseBadge(fx.by_at['2026-10-10 10:30'].phase || {});
ck('忙时徽标写「忙时」', busyHtml.indexOf('忙时') >= 0 && busyHtml.indexOf('闲时') < 0,
   (fx.by_at['2026-10-10 10:30'].phase || {}).reason);
const idleHtml = api.phaseBadge(fx.by_at['2026-10-01 10:30'].phase || {});
ck('国庆当天徽标写「闲时」并给出原因', idleHtml.indexOf('闲时') >= 0 && idleHtml.indexOf('不在生效窗口') >= 0,
   (fx.by_at['2026-10-01 10:30'].phase || {}).reason);
ck('徽标带上判定时刻', idleHtml.indexOf('2026-10-01') >= 0);
ck('未知态不假装闲时', api.phaseBadge({ state: 'unknown', label: '未知' }).indexOf('未知') >= 0);
ck('phase 缺失时降级为闲时样式而不是崩', api.phaseBadge({}).length > 0);

console.log('\n--- 读表单 ---');
const draft = api.readPolicyDraft();
ck('draft 与已保存配置一致', JSON.stringify(draft) === JSON.stringify({
  enabled: cfg.enabled, weekdays_only: cfg.weekdays_only, route: cfg.route,
  target_model: cfg.target_model, rewrite_prefixes: cfg.rewrite_prefixes,
  utc_offset_minutes: cfg.utc_offset_minutes, windows: cfg.windows,
}), JSON.stringify(draft));
ck('draftDiffers 对一致草稿返回 false', api.draftDiffers(draft, cfg) === false);
ck('draftDiffers 能发现未保存改动', api.draftDiffers(Object.assign({}, draft, { target_model: 'other' }), cfg) === true);

console.log('\n--- 保存策略 ---');
(async function () {
  await api.savePolicy(el({ id: 'save' }));
  const p = posts.filter((x) => x.body.name === 'busy-hours/save').pop();
  ck('点了确认再 POST', confirms.length === 1 && !!p);
  ck('payload 是整份策略', p && p.body.payload && p.body.payload.target_model === cfg.target_model &&
     p.body.payload.windows.length === cfg.windows.length, p && JSON.stringify(p.body.payload).slice(0, 90));
  ck('确认框复述了关键内容', confirms[0] && /线路 \/go\//.test(confirms[0].msg) && confirms[0].msg.indexOf(cfg.target_model) >= 0,
     confirms[0] && confirms[0].msg.replace(/<br>/g, ' / ').slice(0, 120));
  ck('保存后触发刷新', refreshes >= 1);

  console.log('\n--- 临时停用 ---');
  const before = posts.length;
  await api.togglePolicySwitch(el({ id: 'sw' }), false);
  const sp = posts.slice(before).pop();
  ck('switch 发的是 {off:true}', sp && sp.body.name === 'busy-hours/switch' && sp.body.payload.off === true,
     sp && JSON.stringify(sp.body.payload));

  console.log('\n--- 按时刻试算 ---');
  nextPrompt = { at: '2026-10-01 10:30' };
  toasts.length = 0; gets.length = 0;
  await api.previewPolicy(el({ id: 'pv' }));
  const q1 = gets[0] || '';
  ck('试算走 GET /relay/busy-hours?at=', q1.indexOf('/relay/busy-hours?at=') === 0 && q1.indexOf('2026-10-01') >= 0, q1);
  ck('国庆当天报“不改写”', toasts.some((t) => t.m.indexOf('outside_window') >= 0 && t.m.indexOf('不改写') >= 0),
     (toasts[0] || {}).m);
  nextPrompt = { at: '2026-10-10 10:30' };
  toasts.length = 0;
  await api.previewPolicy(el({ id: 'pv' }));
  ck('调休补班日报“改写”', toasts.some((t) => t.m.indexOf('mimo-v2.6-flash') >= 0 && t.m.indexOf('不改写') < 0),
     (toasts[0] || {}).m);
  ck('试算会提示超出库覆盖年份的回落', (function () {
    toasts.length = 0; nextPrompt = { at: '2027-01-01 10:30' };
    return true;
  })());
  await api.previewPolicy(el({ id: 'pv' }));
  ck('2027 试算带回落说明', toasts.some((t) => t.m.indexOf('没有官方节假日数据') >= 0), (toasts[0] || {}).m);

  console.log('\n' + (fails ? '!! ' + fails + ' check(s) failed' : 'all ' + 'checks passed'));
  process.exit(fails ? 1 : 0);
})();
