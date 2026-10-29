#!/usr/bin/env node
/*
 * 页面运行时冒烟测试：用真实后端数据 + 极简 DOM 实际执行 page.mount()，
 * 捕获"模板渲染前就读 DOM"这类时序错误（静态检查发现不了）。
 */
'use strict';
const fs = require('fs');
const path = require('path');

/* ---------- 极简 DOM ---------- */
function makeEl(id) {
  return {
    id, value: '', checked: true, textContent: '', innerHTML: '', dataset: {}, style: {},
    /* <select> 在真实浏览器里有 options；缺了会让 for...of 直接崩 */
    options: [], selectedIndex: 0, length: 0,
    onclick: null, onchange: null, oninput: null,
    addEventListener() {}, focus() {}, scrollTop: 0, offsetWidth: 0,
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    querySelector() { return null; }, querySelectorAll() { return []; }, appendChild() {},
  };
}

function makeDoc(store) {
  return {
    getElementById(id) { return store.get(id) || null; },
    querySelector(sel) { const m = /^#([\w-]+)$/.exec(sel); return m ? (store.get(m[1]) || null) : null; },
    querySelectorAll() { return []; },
    createElement: () => makeEl(), addEventListener() {}, activeElement: null,
    head: { appendChild() {} },
  };
}

function makeRoot(store, log) {
  const r = { _html: '', scrollTop: 0 };
  Object.defineProperty(r, 'innerHTML', {
    get() { return this._html; },
    set(v) {
      this._html = String(v);
      /* 模板渲染后，其中的 id 才变得可见 —— 之前的 getElementById 必须返回 null */
      for (const m of this._html.matchAll(/id="([\w-]+)"/g)) if (!store.has(m[1])) store.set(m[1], makeEl(m[1]));
      log.push({ htmlLen: this._html.length, ids: [...store.keys()].length });
    },
  });
  r.querySelector = (s) => { const m = /^#([\w-]+)$/.exec(s); return m ? (store.get(m[1]) || null) : null; };
  r.querySelectorAll = () => [];
  return r;
}

/* ---------- globals ---------- */
function buildGlobals(store, root, data, pageId) {
  const doc = makeDoc(store);
  const CM = {
    apiBase: '', token: 'test', BUILD: 'test', pages: [], lazyPages: {},
    me: { refreshMs: 2000, info: { hostname: 'test' } },
    router: { current: { id: pageId }, containers: new Map() },
    api: {
      get: async (p) => {
        const key = String(p).split('?')[0];
        if (!(key in data)) throw new Error('no mock data for ' + key);
        return data[key];
      },
      post: async () => ({ ok: true, output: '' }),
    },
    refreshLabel: (ms) => ms + 'ms',
    autoRefresh: (fn, opts) => ({ ms: () => 2000, start() {}, stop() {}, run: fn, isEnabled: () => true, setEnabled() {} }),
    debounce: (fn) => fn,
  };
  const ui = {
    table: (cols, rows) => '<div class="table-wrap"><table class="tbl"><tbody>' +
      (rows || []).map(() => '<tr><td>x</td></tr>').join('') + '</tbody></table></div>',
    badge: (t) => '<span class="badge ok">' + t + '</span>',
    skeleton: () => '<div class="skel"></div>',
    bar: () => '<div class="bar"></div>',
    chart: () => ({ resize() {}, push() {} }),
    busy() {}, svcBadge: () => '',
  };
  const fmt = {
    esc: (s) => String(s == null ? '' : s),
    bytes: (n) => n + 'B', bps: (n) => n + 'B/s', uptime: () => '', ts: () => 0,
  };
  const modal = { confirm: async () => true, prompt: async () => ({}), output() {} };
  const g = {
    CM, ui, fmt, modal, document: doc, console,
    window: { toast() {}, addEventListener() {} },
    location: { hash: '#/' + pageId, reload() {} },
    localStorage: { getItem: () => '', setItem() {}, removeItem() {} },
    registerPage: (p) => { g.__page = p; },
    runAction: async () => ({ ok: true, output: '' }),
    icon: () => '', setTimeout, clearTimeout, setInterval, clearInterval,
    fetch: async () => ({ json: async () => ({}) }),
  };
  g.icon = () => '';
  return g;
}

/* ---------- runner ---------- */
async function testPage(name, file, data) {
  const src = fs.readFileSync(file, 'utf8');
  const store = new Map();
  const renders = [];
  const root = makeRoot(store, renders);
  const g = buildGlobals(store, root, data, name);

  const vm = require('vm');
  const ctx = vm.createContext(g);
  try {
    vm.runInContext(src, ctx, { filename: path.basename(file) });
  } catch (e) {
    return { name, ok: false, stage: 'load', err: e.message };
  }
  const page = g.__page;
  if (!page) return { name, ok: false, stage: 'load', err: 'registerPage 未被调用' };

  try {
    await page.mount(root);
  } catch (e) {
    return { name, ok: false, stage: 'mount', err: e.message, stack: (e.stack || '').split('\n')[1] };
  }
  if (!root._html || root._html.length < 200) {
    return { name, ok: false, stage: 'mount', err: 'mount 后 innerHTML 过短: ' + root._html.length };
  }
  try { if (page.show) page.show(); if (page.hide) page.hide(); } catch (e) {
    return { name, ok: false, stage: 'show/hide', err: e.message };
  }
  return { name, ok: true, html: root._html.length, renders: renders.length, ids: store.size };
}

module.exports = { testPage };

/* ---------- main ---------- */
if (require.main === module) {
  (async () => {
    const APP = process.env.CM_APP_DIR || '/opt/cloud-monitor';
    const rm = require(APP + '/server/relaymetrics');
    const ro = require(APP + '/server/relayops');
    const act = require(APP + '/server/actions');

    const data = {};
    const load = async (key, fn) => { try { data[key] = await fn(); } catch (e) { console.log('  (mock ' + key + ' 失败: ' + e.message + ')'); } };
    await load('/relayops/slo', () => rm.sloStats({ range: '24h' }));
    await load('/relayops/hitrate', () => rm.hitRate({ range: '24h' }));
    await load('/relayops/tokenhit', () => rm.tokenHit({ range: '24h' }));
    await load('/relayops/usage', () => rm.usageCost({ range: '24h' }));
    await load('/relayops/health', () => ro.healthAlerts());
    await load('/relayops/audit', () => ro.auditSearch({ range: 'all', limit: 5 }));
    await load('/backups', () => act.backupList());

    const PAGES = [
      ['slo', APP + '/public/js/pages/slo.js'],
      ['healthalerts', APP + '/public/js/pages/healthalerts.js'],
      ['audit', APP + '/public/js/pages/audit.js'],
      ['backups', APP + '/public/js/pages/backups.js'],
    ];
    let bad = 0;
    for (const [name, file] of PAGES) {
      const r = await testPage(name, file, data);
      if (r.ok) console.log('  PASS  ' + name.padEnd(14) + ' 渲染 ' + r.html + ' 字节, 模板赋值 ' + r.renders + ' 次, 元素 ' + r.ids + ' 个');
      else { bad++; console.log('  FAIL  ' + name.padEnd(14) + ' [' + r.stage + '] ' + r.err + (r.stack ? '  @ ' + r.stack.trim() : '')); }
    }
    console.log(bad ? '\n' + bad + ' 个页面失败' : '\n全部页面 mount 通过');
    process.exit(bad ? 1 : 0);
  })().catch((e) => { console.error('测试框架异常: ' + e.message); process.exit(2); });
}
