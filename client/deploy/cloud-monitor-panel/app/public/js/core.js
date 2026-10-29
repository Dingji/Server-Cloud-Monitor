/* Cloud Monitor - core: api, auth, ws, modal, toast, router skeleton */
(function () {
  'use strict';

  const CM = window.CM = {
    token: localStorage.getItem('cm_token') || '',
    pages: [],
    me: null,
    ready: false,
  };

  CM.BUILD = '20261025-27';

  CM.base = location.pathname.replace(/\/public\/.*$/, '') || '';
  CM.apiBase = CM.base + '/api';

  CM.isWindows = function () {
    if (CM.me && CM.me.env && CM.me.env.family === 'windows') return true;
    const pm = CM.me && CM.me.info && String(CM.me.info.panelMode || '');
    return /windows/i.test(pm);
  };

  /* ---------- formatting ---------- */
  const fmt = window.fmt = {
    bytes(n) {
      if (n == null || isNaN(n)) return '-';
      const u = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
      let i = 0, v = Math.abs(n);
      while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
      const num = i === 0 ? v.toFixed(0) : v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2);
      return (n < 0 ? '-' : '') + num + ' ' + u[i];
    },
    bps(n) { return fmt.bytes(n) + '/s'; },
    pctColor(p) { return p >= 90 ? 'bad' : p >= 75 ? 'warn' : 'ok'; },
    uptime(sec) {
      sec = Math.floor(sec || 0);
      const d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60;
      if (d > 0) return d + '天 ' + h + '时';
      if (h > 0) return h + '时 ' + m + '分';
      if (m > 0) return m + '分';
      return s + '秒';
    },
    esc(s) {
      return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    },
    ts(t) {
      if (!t) return '--:--:--';
      const d = new Date(t);
      const s = d.toLocaleTimeString('zh-CN', { hour12: false });
      /* 亚秒级刷新时同一秒会有多个点，补一位小数避免 category 轴标签重复 */
      return (CM && CM.me && CM.me.refreshMs < 1000) ? s + '.' + Math.floor(d.getMilliseconds() / 100) : s;
    },
  };

  /* ---------- toast ---------- */
  window.toast = function (msg, type, ms) {
    const root = document.getElementById('toast-root');
    const el = document.createElement('div');
    el.className = 'toast ' + (type || 'info');
    el.innerHTML = fmt.esc(msg).replace(/\n/g, '<br>') + `<div class="toast-progress" style="animation-duration:${ms || 3800}ms"></div>`;
    root.appendChild(el);
    /* stack limit */
    while (root.children.length > 5) root.firstChild.remove();
    setTimeout(() => {
      el.classList.add('out');
      setTimeout(() => el.remove(), 500);
    }, ms || 3800);
  };

  /* ---------- api (GET cache + inflight dedupe) ---------- */
  const apiCache = new Map();    // key -> { exp, data }
  const apiInflight = new Map(); // key -> promise
  const api = CM.api = {
    async request(method, path, body) {
      const opts = {
        method,
        headers: { 'Authorization': 'Bearer ' + CM.token, 'Content-Type': 'application/json' },
      };
      if (body !== undefined) opts.body = JSON.stringify(body);
      let res;
      try { res = await fetch(CM.apiBase + path, opts); }
      catch (e) { throw new Error('网络错误: ' + e.message); }
      if (res.status === 401) {
        CM.token = '';
        try { localStorage.removeItem('cm_token'); } catch (_) {}
        CM.showLogin('会话已过期，请重新登录');
        throw new Error('未登录');
      }
      let data = {};
      try { data = await res.json(); } catch (_) {}
      if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
      return data;
    },
    /* GET with optional cache ttl (ms); concurrent identical requests share one promise */
    get(p, cacheMs) {
      if (!cacheMs) return api.request('GET', p);
      const hit = apiCache.get(p);
      if (hit && hit.exp > Date.now()) return Promise.resolve(hit.data);
      if (apiInflight.has(p)) return apiInflight.get(p);
      const pr = api.request('GET', p).then((d) => {
        apiCache.set(p, { exp: Date.now() + cacheMs, data: d });
        apiInflight.delete(p);
        return d;
      }).catch((e) => { apiInflight.delete(p); throw e; });
      apiInflight.set(p, pr);
      return pr;
    },
    invalidate(prefix) {
      for (const k of [...apiCache.keys()]) if (!prefix || k.startsWith(prefix)) apiCache.delete(k);
    },
    post(p, body) { return api.request('POST', p, body || {}); },
  };

  CM.debounce = function (fn, ms) {
    let t = null, lastArgs = null, lastThis = null;
    return function (...args) {
      lastArgs = args; lastThis = this;
      clearTimeout(t);
      t = setTimeout(() => fn.apply(lastThis, lastArgs), ms || 160);
    };
  };

  /* ---------- login ---------- */
  CM.showLogin = function (msg) {
    document.getElementById('app').classList.add('hidden');
    const ov = document.getElementById('login-overlay');
    ov.classList.remove('hidden');
    document.getElementById('login-error').textContent = msg || '';
    setTimeout(() => document.getElementById('login-password').focus(), 50);
  };
  CM.hideLogin = function () {
    document.getElementById('login-overlay').classList.add('hidden');
    document.getElementById('app').classList.remove('hidden');
  };

  async function doLogin() {
    const pw = document.getElementById('login-password').value;
    const errEl = document.getElementById('login-error');
    errEl.textContent = '';
    /* 未配置面板密码时允许空密码登录 */
    try {
      const r = await api.request('POST', '/login', { password: pw });
      CM.token = r.token;
      localStorage.setItem('cm_token', r.token);
      CM.hideLogin();
      window.dispatchEvent(new CustomEvent('cm-login'));
    } catch (e) {
      errEl.textContent = e.message;
    }
  }
  window.addEventListener('DOMContentLoaded', () => {
    document.getElementById('login-btn').addEventListener('click', doLogin);
    document.getElementById('login-password').addEventListener('keydown', (e) => { if (e.key === 'Enter') doLogin(); });
    document.getElementById('logout-btn').addEventListener('click', async () => {
      try { await api.post('/logout'); } catch (_) {}
      CM.token = ''; localStorage.removeItem('cm_token');
      location.reload();
    });
  });

  /* ---------- modal ---------- */
  const modalRoot = () => document.getElementById('modal-root');
  function closeModal(mask, cb) {
    const m = mask.querySelector('.modal');
    if (m) m.classList.add('closing');
    mask.classList.add('closing');
    setTimeout(() => { mask.remove(); if (cb) cb(); }, 190);
  }
  window.modal = {
    confirm(title, html, opts) {
      opts = opts || {};
      return new Promise((resolve) => {
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.innerHTML = `<div class="modal" style="max-width:520px">
          <div class="modal-head">${fmt.esc(title)}</div>
          <div class="modal-body">${html}</div>
          <div class="modal-foot">
            <button class="btn" data-a="cancel">取消</button>
            <button class="btn ${opts.danger ? 'btn-danger' : 'btn-primary'}" data-a="ok">${fmt.esc(opts.okText || '确认')}</button>
          </div></div>`;
        modalRoot().appendChild(mask);
        const close = (v) => closeModal(mask, () => resolve(v));
        mask.querySelector('[data-a=cancel]').onclick = () => close(false);
        mask.querySelector('[data-a=ok]').onclick = () => close(true);
        mask.addEventListener('click', (e) => { if (e.target === mask) close(false); });
      });
    },
    output(title, text, opts) {
      opts = opts || {};
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = `<div class="modal" style="width:760px">
        <div class="modal-head">${fmt.esc(title)} ${opts.error ? '<span class="badge bad">失败</span>' : '<span class="badge ok">成功</span>'}</div>
        <div class="modal-body"><pre class="pre tall" style="max-height:56vh">${fmt.esc(text)}</pre></div>
        <div class="modal-foot">
          <button class="btn" data-a="copy">复制</button>
          <button class="btn btn-primary" data-a="close">关闭</button>
        </div></div>`;
      modalRoot().appendChild(mask);
      mask.querySelector('[data-a=close]').onclick = () => closeModal(mask);
      mask.querySelector('[data-a=copy]').onclick = async () => {
        try { await navigator.clipboard.writeText(text); window.toast('已复制到剪贴板', 'ok'); }
        catch (_) { window.toast('复制失败', 'err'); }
      };
      mask.addEventListener('click', (e) => { if (e.target === mask) closeModal(mask); });
    },
    prompt(title, fields, opts) {
      opts = opts || {};
      return new Promise((resolve) => {
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        const body = fields.map((f) => {
          if (f.type === 'select') {
            return `<label class="fld mb">${fmt.esc(f.label)}<select class="inp" name="${f.name}">${f.options.map((o) => `<option value="${fmt.esc(o)}" ${o === f.value ? 'selected' : ''}>${fmt.esc(o)}</option>`).join('')}</select></label>`;
          }
          return `<label class="fld mb">${fmt.esc(f.label)}<input class="inp ${f.mono ? 'mono' : ''}" name="${f.name}" value="${fmt.esc(f.value || '')}" placeholder="${fmt.esc(f.placeholder || '')}"></label>`;
        }).join('');
        mask.innerHTML = `<div class="modal" style="max-width:480px">
          <div class="modal-head">${fmt.esc(title)}</div>
          <div class="modal-body">${body}</div>
          <div class="modal-foot">
            <button class="btn" data-a="cancel">取消</button>
            <button class="btn ${opts.danger ? 'btn-danger' : 'btn-primary'}" data-a="ok">${fmt.esc(opts.okText || '确认')}</button>
          </div></div>`;
        modalRoot().appendChild(mask);
        const close = (v) => closeModal(mask, () => resolve(v));
        mask.querySelector('[data-a=cancel]').onclick = () => close(null);
        mask.querySelector('[data-a=ok]').onclick = () => {
          const out = {};
          mask.querySelectorAll('[name]').forEach((el) => { out[el.name] = el.value; });
          close(out);
        };
      });
    },
  };

  /* ---------- auto refresh ----------
     间隔跟随面板「数据刷新间隔」(CM.me.refreshMs)，页面可见时才发请求，
     上一拍未返回则跳过；页面 hide() 时应调用 stop()。 */
  CM.REFRESH_MIN_MS = 500;

  CM.refreshLabel = function (ms) {
    return ms >= 1000 ? String(+(ms / 1000).toFixed(2)) + ' 秒' : ms + ' 毫秒';
  };

  CM.autoRefresh = function (fn, opts) {
    opts = opts || {};
    let timer = null;
    let on = opts.enabled !== false;
    let busy = false;
    const ms = () => Math.max(CM.REFRESH_MIN_MS,
      opts.ms ? opts.ms() : ((CM.me && CM.me.refreshMs) || 2000));
    const stop = () => { if (timer) { clearInterval(timer); timer = null; } };
    let applied = 0;
    /* 弹窗打开或焦点在输入控件时跳过；重画前后保持滚动位置 */
    const guarded = () => {
      if (document.getElementById('modal-root').childElementCount > 0) return true;
      const a = document.activeElement;
      if (a && ['INPUT', 'TEXTAREA', 'SELECT'].includes(a.tagName) && a.type !== 'checkbox') return true;
      return false;
    };
    const snapshotScroll = () => {
      const saved = [];
      document.querySelectorAll('#view .table-wrap, #view .page').forEach((el) => {
        if (el && el.scrollTop) saved.push([el, el.scrollTop]);
      });
      return () => saved.forEach(([el, top]) => { if (el) el.scrollTop = top; });
    };
    const tick = async () => {
      if (!on || busy) return;
      if (opts.pageId && (!CM.router.current || CM.router.current.id !== opts.pageId)) return;
      if (guarded()) return;
      busy = true;
      const restore = snapshotScroll();
      try { await fn(); } catch (_) { /* ignore */ }
      restore();
      busy = false;
      if (on && ms() !== applied) start();
    };
    const start = () => { stop(); if (on) { applied = ms(); timer = setInterval(tick, applied); } };
    return {
      ms, start, stop, run: tick,
      isEnabled: () => on,
      setEnabled(v) { on = !!v; if (on) start(); else stop(); },
    };
  };

  /* ---------- command palette (Ctrl/Cmd + K) ---------- */
  CM.commands = [];
  CM.registerCommand = function (c) { if (c && c.label) CM.commands.push(c); };

  let palette = null, palIdx = 0, palList = [];

  function closePalette() {
    if (!palette) return;
    palette.remove();
    palette = null;
    palList = [];
  }

  function paletteRender(q) {
    const list = document.getElementById('cmdk-list');
    if (!list) return;
    const query = String(q || '').trim().toLowerCase();
    palList = CM.commands.filter((c) => !query ||
      c.label.toLowerCase().includes(query) ||
      String(c.hint || '').toLowerCase().includes(query));
    palIdx = Math.max(0, Math.min(palIdx, palList.length - 1));
    list.innerHTML = palList.length
      ? palList.map((c, i) => `<div class="cmdk-item${i === palIdx ? ' sel' : ''}" data-i="${i}">
          ${icon(c.icon || 'chevronRight', { size: 13 })}<span>${fmt.esc(c.label)}</span>
          ${c.hint ? `<span class="k">${fmt.esc(c.hint)}</span>` : ''}</div>`).join('')
      : '<div class="cmdk-empty">没有匹配的页面或命令</div>';
    list.querySelectorAll('[data-i]').forEach((el) => {
      el.onclick = () => { palIdx = Number(el.dataset.i); paletteRun(); };
    });
  }

  function paletteRun() {
    const c = palList[palIdx];
    closePalette();
    if (c && typeof c.run === 'function') {
      try { c.run(); } catch (e) { window.toast(e.message, 'err'); }
    }
  }

  CM.openPalette = function () {
    if (palette) return closePalette();
    const mask = document.createElement('div');
    mask.className = 'cmdk-mask';
    mask.innerHTML = `<div class="cmdk">
      <input class="cmdk-input" id="cmdk-input" placeholder="搜索页面或命令…（Esc 关闭）" autocomplete="off">
      <div class="cmdk-list" id="cmdk-list"></div>
      <div class="cmdk-foot"><span>↑↓ 选择</span><span>Enter 执行</span><span>Esc 关闭</span></div>
    </div>`;
    document.body.appendChild(mask);
    palette = mask;
    mask.onclick = (e) => { if (e.target === mask) closePalette(); };
    const input = document.getElementById('cmdk-input');
    palIdx = 0;
    paletteRender('');
    setTimeout(() => { try { input.focus(); } catch (_) {} }, 10);
    input.oninput = () => { palIdx = 0; paletteRender(input.value); };
    input.onkeydown = (e) => {
      if (e.key === 'ArrowDown') { e.preventDefault(); palIdx = Math.min(palIdx + 1, palList.length - 1); paletteRender(input.value); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); palIdx = Math.max(0, palIdx - 1); paletteRender(input.value); }
      else if (e.key === 'Enter') { e.preventDefault(); paletteRun(); }
      else if (e.key === 'Escape') { e.preventDefault(); closePalette(); }
    };
  };

  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && String(e.key).toLowerCase() === 'k') {
      e.preventDefault();
      CM.openPalette();
    }
  });

  /* ---------- collapsible cards (click a card title) ---------- */
  const COLLAPSE_KEY = 'cm_collapsed_cards_v1';
  function collapseStore() {
    try { return JSON.parse(localStorage.getItem(COLLAPSE_KEY) || '{}'); } catch (_) { return {}; }
  }
  function collapseSave(o) {
    try { localStorage.setItem(COLLAPSE_KEY, JSON.stringify(o)); } catch (_) {}
  }
  function cardKey(page, card) {
    const t = card.querySelector(':scope > .card-title');
    const label = t ? String(t.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 28) : '';
    return page + '|' + label;
  }
  /* 页面挂载后恢复折叠状态 */
  CM.applyCollapsed = function (pageId, container) {
    if (!container) return;
    const st = collapseStore();
    container.querySelectorAll('.card').forEach((card) => {
      if (st[cardKey(pageId, card)]) card.classList.add('collapsed');
    });
  };
  document.addEventListener('click', (e) => {
    const t = e.target;
    if (!t || !t.closest) return;
    if (t.closest('.right') || t.closest('button') || t.closest('input') || t.closest('select') || t.closest('a')) return;
    const title = t.closest('.card-title');
    if (!title) return;
    const card = title.closest('.card');
    if (!card) return;
    const page = (CM.router && CM.router.current && CM.router.current.id) || 'x';
    const key = cardKey(page, card);
    const nowCollapsed = !card.classList.contains('collapsed');
    card.classList.toggle('collapsed', nowCollapsed);
    const st = collapseStore();
    if (nowCollapsed) st[key] = 1; else delete st[key];
    collapseSave(st);
  });

  /* ---------- run action helper (POST + toast + reload) ---------- */
  window.runAction = async function (path, body, opts) {
    opts = opts || {};
    try {
      const r = await api.post(path, body);
      if (opts.toast !== false) window.toast(r.output || '操作成功', 'ok');
      if (r.output && opts.showOutput) modal.output(opts.title || '执行结果', r.output);
      if (opts.reload) setTimeout(opts.reload, opts.reloadDelay || 700);
      return r;
    } catch (e) {
      window.toast(e.message, 'err', 6000);
      if (e.output) modal.output(opts.title || '执行失败', e.output, { error: true });
      throw e;
    }
  };

  /* ---------- package job progress (软件包管理模块共用) ----------
     后端 POST /pkg/job 立即返回任务 id，前端轮询 GET /pkg/job?id= 取
     百分比 / 阶段 / 实时日志，渲染进度条弹窗；完成时 resolve，失败时 reject。
     弹窗可「后台运行」：隐藏后继续轮询，右下角有悬浮进度胶囊。 */
  const JOB_STEP_LABEL = {
    queued: '排队中', resolve: '解析依赖', plan: '制定方案', download: '下载软件包',
    unpack: '解包', configure: '安装配置', remove: '移除软件包', update: '更新索引',
    finish: '收尾', done: '完成', failed: '失败',
  };
  const sleepMs = (ms) => new Promise((r) => setTimeout(r, ms));

  CM.pkg = {
    /* 启动任务并跟踪进度，返回完成时的任务视图 */
    async run(opts) {
      opts = opts || {};
      const start = await api.request('POST', '/pkg/job', {
        action: opts.action, names: opts.names || [], force: !!opts.force,
      });
      if (!start || !start.id) throw new Error('任务启动失败：服务端未返回任务 id');
      return this.watch(start.id, opts);
    },

    /* 页面加载时若存在进行中的任务（例如刚刷新过页面），接上它的进度窗 */
    async resume(opts) {
      let cur = null;
      try { cur = await api.request('GET', '/pkg/job'); } catch (_) { return null; }
      if (!cur || !cur.active || !cur.id) return null;
      const o = Object.assign({}, opts || {});
      if (typeof o.title === 'function') { try { o.title = o.title(cur); } catch (_) { o.title = 'apt 任务'; } }
      return this.watch(cur.id, o);
    },

    async watch(id, opts) {
      opts = opts || {};
      const title = opts.title || '执行任务';
      const rootEl = document.getElementById('modal-root');
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = `<div class="modal job-modal" style="width:780px">
        <div class="modal-head"><span>${fmt.esc(title)}</span><span class="badge info" data-r="state">执行中</span></div>
        <div class="modal-body">
          <div class="job-row">
            <div class="job-pct" data-r="pct">0%</div>
            <div class="job-bar"><div data-r="fill" style="width:0%"></div></div>
          </div>
          <div class="job-steps" data-r="steps"></div>
          <div class="job-result" data-r="result" style="display:none"></div>
          <div class="job-meta">
            <b data-r="phase">排队中</b>
            <span data-r="detail" class="muted"></span>
            <span style="flex:1"></span>
            <span data-r="elapsed" class="muted"></span>
          </div>
          <pre class="pre job-log" data-r="log">（等待任务输出…）</pre>
        </div>
        <div class="modal-foot">
          <span class="muted small" data-r="logpath" style="margin-right:auto;word-break:break-all"></span>
          <button class="btn" data-a="bg">后台运行</button>
          <button class="btn btn-primary" data-a="done" disabled>完成</button>
        </div>
      </div>`;
      rootEl.appendChild(mask);

      const pill = document.createElement('div');
      pill.className = 'job-pill';
      pill.innerHTML = `<span class="job-pill-fill" style="width:0%"></span><span class="job-pill-text" data-r="ptext">${fmt.esc(title)} 0%</span>`;
      pill.style.display = 'none';   /* 默认隐藏，仅在「后台运行」时浮出 */
      document.body.appendChild(pill);

      const q = (k) => mask.querySelector(`[data-r="${k}"]`);
      let hidden = false, fails = 0, finished = false;

      const showMask = () => { hidden = false; mask.style.display = ''; if (!mask.isConnected) rootEl.appendChild(mask); pill.style.display = 'none'; };
      const hideMask = () => { if (hidden) return; hidden = true; mask.style.display = 'none'; pill.style.display = ''; };
      const finishUi = (v) => {
        finished = true;
        const ok = v.state === 'done';
        const stateEl = q('state');
        stateEl.textContent = ok ? '成功' : '失败';
        stateEl.className = 'badge ' + (ok ? 'ok' : 'bad');
        const pct = ok ? 100 : Math.max(0, Math.min(100, v.percent || 0));
        q('pct').textContent = ok ? '✓' : '✗';
        q('fill').style.width = pct + '%';
        q('fill').style.background = ok ? 'linear-gradient(90deg,#22c55e,#4ade80)' : 'linear-gradient(90deg,#ef4444,#f87171)';
        q('phase').textContent = ok ? '已完成' : '执行失败';
        const banner = q('result');
        banner.style.display = '';
        const secs = v.seconds != null ? `用时 ${v.seconds}s` : '';
        const exit = v.exit_code != null ? ` · 退出码 ${v.exit_code}` : '';
        if (ok) {
          banner.className = 'job-result ok';
          banner.innerHTML = `${icon('check', { size: 15 })} <b>操作成功</b><span class="muted">${fmt.esc(secs + exit)}</span>
            <span class="muted small">日志见下方，关闭后可到「可升级」页签复查结果。</span>`;
          const btn = q('bg'); if (btn) btn.style.display = 'none';
          const done = mask.querySelector('[data-a=done]');
          if (done) { done.disabled = false; done.textContent = '关闭'; }
        } else {
          banner.className = 'job-result bad';
          const firstErr = String(v.error || v.output || '任务失败').split('\n').filter(Boolean).slice(-4).join('\n');
          banner.innerHTML = `${icon('warning', { size: 15 })} <b>操作失败</b><span class="muted">${fmt.esc(secs + exit)}</span>
            <pre class="pre" style="max-height:120px;margin-top:8px">${fmt.esc(firstErr || '（无错误输出）')}</pre>`;
          const btn = q('bg'); if (btn) btn.style.display = 'none';
          const done = mask.querySelector('[data-a=done]');
          if (done) { done.disabled = false; done.textContent = '关闭'; }
        }
      };
      pill.onclick = showMask;
      mask.querySelector('[data-a=bg]').onclick = hideMask;
      const doneBtn = mask.querySelector('[data-a=done]');
      const closeAll = () => { mask.remove(); teardown(); };
      doneBtn.onclick = closeAll;
      mask.addEventListener('click', (e) => { if (e.target === mask) { if (finished) closeAll(); else hideMask(); } });

      const renderSteps = (v) => {
        const host = q('steps');
        const steps = (v.steps || []).filter((s) => s !== 'failed' && s !== 'done');
        if (!steps.length) { host.innerHTML = ''; return; }
        const cur = v.state === 'done' ? steps.length : steps.indexOf(v.phase);
        host.innerHTML = steps.map((s, i) => {
          const cls = i < cur ? 'done' : (i === cur ? 'on' : '');
          return `<span class="job-step ${cls}">${cls === 'done' ? '✓ ' : ''}${fmt.esc(JOB_STEP_LABEL[s] || s)}</span>`;
        }).join('<span class="job-step-sep">›</span>');
      };

      const paint = (v) => {
        const pct = Math.max(0, Math.min(100, v.percent || 0));
        q('pct').textContent = pct + '%';
        q('fill').style.width = pct + '%';
        q('phase').textContent = v.phase_label || '执行中';
        q('detail').textContent = v.detail || '';
        q('state').textContent = v.state === 'queued' ? '排队中' : '执行中';
        const el = Math.max(0, Math.round((Date.now() - (v.startedAt || Date.now())) / 1000));
        q('elapsed').textContent = '已用 ' + fmt.uptime(el) + (v.chunks > 1 ? ` · 批次 ${v.chunk}/${v.chunks}` : '');
        renderSteps(v);
        const log = q('log');
        const stick = log.scrollHeight - log.scrollTop - log.clientHeight < 28;
        const text = (v.lines || []).join('\n');
        log.textContent = text || '（等待任务输出…）';
        if (stick) log.scrollTop = log.scrollHeight;
        if (v.log) q('logpath').textContent = '完整日志：' + v.log;
        pill.querySelector('[data-r=ptext]').textContent = `${title} ${pct}%`;
        pill.querySelector('.job-pill-fill').style.width = pct + '%';
      };

      const teardown = () => { pill.remove(); };
      try {
        for (;;) {
          let v;
          try {
            v = await api.request('GET', '/pkg/job?id=' + encodeURIComponent(id));
            fails = 0;
          } catch (e) {
            /* 网络抖动容忍几次，彻底失败才放弃（任务仍在服务端跑） */
            fails++;
            if (fails >= 8) throw e;
            await sleepMs(1200);
            continue;
          }
          if (!v) {
            /* 服务端重启或任务已被清理：给出可操作的提示，而不是 null 报错 */
            const err = new Error('任务状态已丢失（服务端可能重启过），请重新发起该操作');
            throw err;
          }
          paint(v);
          if (v.state === 'done' || v.state === 'failed') {
            /* 结果横幅留在窗口里，直到用户点「关闭」——升级到底成没成功一目了然 */
            finishUi(v);
            if (v.state === 'done') return v;
            const err = new Error(v.error || '任务执行失败');
            err.job = v;
            err.handledInModal = true;
            throw err;
          }
          await sleepMs(650);
        }
      } catch (e) {
        if (!finished) { mask.remove(); teardown(); }
        throw e;
      }
    },
  };


  /* ---------- websocket hub ---------- */
  class Hub {
    constructor(path) { this.path = path; this.handlers = {}; this.sock = null; this.backoff = 1000; this.closedByUser = false; }
    on(type, fn) { (this.handlers[type] = this.handlers[type] || []).push(fn); return this; }
    emit(type, data) { (this.handlers[type] || []).forEach((fn) => { try { fn(data); } catch (e) { console.error(e); } }); }
    connect() {
      if (!CM.token) return;
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const url = `${proto}//${location.host}${CM.base}${this.path}?token=${encodeURIComponent(CM.token)}`;
      let ws;
      try { ws = new WebSocket(url); } catch (_) { return this.scheduleReconnect(); }
      this.sock = ws;
      ws.onopen = () => { this.backoff = 1000; this.emit('__open'); };
      ws.onmessage = (ev) => {
        let msg; try { msg = JSON.parse(ev.data); } catch (_) { return; }
        this.emit(msg.type, msg);
      };
      ws.onclose = () => { this.emit('__close'); this.scheduleReconnect(); };
      ws.onerror = () => { try { ws.close(); } catch (_) {} };
    }
    scheduleReconnect() {
      if (this.closedByUser) return;
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(this.backoff * 1.6, 15000);
    }
    send(obj) { if (this.sock && this.sock.readyState === 1) { this.sock.send(JSON.stringify(obj)); return true; } return false; }
  }
  CM.Hub = Hub;

  /* ---------- page registry / router ----------
     Pages keep their DOM between switches (cached container per page), so
     revisiting a page is instant: only show()/hide() lifecycle runs.
     Page modules themselves are lazy-loaded on first visit. */
  window.registerPage = function (page) { CM.pages.push(page); };

  const pageLoads = new Map(); // id -> Promise
  CM.loadPageModule = function (id) {
    const file = CM.lazyPages && CM.lazyPages[id];
    if (!file) return Promise.resolve();
    if (CM.pages.find((p) => p.id === id)) return Promise.resolve();
    if (pageLoads.has(id)) return pageLoads.get(id);
    const pr = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = CM.base + '/public/' + file + (CM.BUILD ? '?v=' + CM.BUILD : '');
      s.onload = () => resolve();
      s.onerror = () => reject(new Error('模块加载失败: ' + file));
      document.head.appendChild(s);
    });
    pageLoads.set(id, pr);
    return pr;
  };

  CM.router = {
    current: null,
    containers: new Map(), // id -> { el, mounted }

    async navigate(id) {
      if (!id) id = 'dashboard';
      if (CM.hiddenPages && CM.hiddenPages.has(id)) id = 'dashboard';
      if (!CM.pages.find((p) => p.id === id) && !(CM.lazyPages && CM.lazyPages[id])) id = 'dashboard';
      if (location.hash !== '#/' + id) { location.hash = '#/' + id; return; } // triggers hashchange
      this.activate(id);
    },

    async activate(id) {
      if (CM.hiddenPages && CM.hiddenPages.has(id)) id = 'dashboard';
      if (!CM.pages.find((p) => p.id === id)) {
        try { await CM.loadPageModule(id); } catch (e) { console.error(e); return; }
      }
      const page = CM.pages.find((p) => p.id === id);
      if (!page || (this.current && this.current.id === id)) return;

      /* hide previous page (keep its DOM cached) */
      const view = document.getElementById('view');
      if (this.current) {
        const prev = this.containers.get(this.current.id);
        if (prev) prev.el.style.display = 'none';
        if (this.current.hide) { try { this.current.hide(); } catch (_) {} }
      }
      this.current = page;

      const titleEl = document.getElementById('page-title');
      if (titleEl && titleEl.textContent !== page.title) {
        titleEl.textContent = page.title;
        titleEl.classList.remove('swap'); void titleEl.offsetWidth; titleEl.classList.add('swap');
      }
      document.querySelectorAll('.nav-item').forEach((el) => el.classList.toggle('active', el.dataset.page === id));

      let c = this.containers.get(id);
      if (!c) {
        const el = document.createElement('div');
        el.className = 'page';
        /* page header (title + one-line description) */
        const head = document.createElement('div');
        head.className = 'page-head';
        head.innerHTML = `<div><div class="ph-title">${fmt.esc(page.title)}</div>${page.desc ? `<div class="ph-desc">${page.desc}</div>` : ''}</div><div class="ph-right"></div>`;
        el.appendChild(head);
        const body = document.createElement('div');
        body.className = 'page-body';
        el.appendChild(body);
        view.appendChild(el);
        c = { el, body, mounted: false };
        this.containers.set(id, c);
      }
      c.el.style.display = '';

      if (!c.mounted) {
        c.mounted = true;
        try { await page.mount(c.body); }
        catch (e) { console.error(e); c.body.innerHTML = `<div class="card"><div class="card-title">页面加载失败</div><pre class="pre">${fmt.esc(e.message)}</pre></div>`; }
        /* 先恢复折叠状态，再做入场动画，避免闪动 */
        try { CM.applyCollapsed(id, c.body); } catch (_) {}
        /* one-time staggered entrance (class removed right after, so later
           data refreshes never replay the animation) */
        const targets = [...c.body.querySelectorAll(':scope > .card, :scope > .grid > *')];
        targets.forEach((el, i) => {
          el.classList.add('enter');
          el.style.animationDelay = Math.min(i * 55, 420) + 'ms';
        });
        setTimeout(() => targets.forEach((el) => {
          el.classList.remove('enter');
          el.style.animationDelay = '';
        }), 1200);
      } else if (page.show) {
        try { page.show(); } catch (_) {}
      }
      view.scrollTop = 0;
      c.el.scrollTop = 0;
    },
  };
  window.addEventListener('hashchange', () => {
    const id = (location.hash || '').replace(/^#\//, '');
    if (CM.ready && CM.router.current && CM.router.current.id === id) return;
    if (CM.ready) CM.router.activate(id);
  });
})();
