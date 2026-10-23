/* Relay page - OpenCode 中继（整合原 /stats/ 面板，统计维度 1:1 对齐）
   所有页签：构建一次 + 原地平滑更新（数字续接补间、表格按签名跳过重建、图表复用实例） */
(function () {
  'use strict';

  const S = { tab: 'overview', timer: null, upd: {} };
  const WIN_LABEL = { rolling: '滚动窗口', weekly: '本周', monthly: '本月' };
  const TABS = [
    ['overview', '概览'], ['traffic', '流量分析'], ['quality', '质量分析'],
    ['hitrate', '套餐命中率'], ['tokenhit', 'Token 命中率'],
    ['clients', '客户端与会话'], ['tokens', 'Token'], ['keys', '密钥池'],
  ];
  const REFRESH_MS = 10000;
  /* 命中率类页签走实时刷新：主定时器按 1 秒滴答，各页签按自己的节奏拉取 */
  const REFRESH_MS_TAB = { hitrate: 2000, tokenhit: 2000 };

  registerPage({
    id: 'relay',
    title: 'OpenCode 中继',
    desc: 'LLM API 中继的请求量、Token 用量、密钥池配额、模型可用性与质量分析',
    async mount(root) {
      root.innerHTML = '<div class="range-btns" id="rl-tabs" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px"></div><div id="rl-body"></div>';
      const tb = document.getElementById('rl-tabs');
      tb.innerHTML = TABS.map(([k, l]) => `<button class="btn btn-sm ${S.tab === k ? 'active btn-primary' : ''}" data-tab="${k}">${l}</button>`).join('') +
        '<span style="flex:1"></span><span class="muted small" id="rl-live">每 ' + (REFRESH_MS / 1000) + ' 秒自动刷新</span>' +
        '<a class="btn btn-sm" href="/stats/" target="_blank" rel="noopener">' + icon('external', { size: 13 }) + ' 旧版面板</a>';
      tb.querySelectorAll('[data-tab]').forEach((b) => (b.onclick = () => {
        S.tab = b.dataset.tab;
        S.nextAt = 0;                /* 切页签立刻拉一次，不等节奏 */
        S.upd = {}; /* 布局不同：丢弃缓存更新器，全量重建 */
        tb.querySelectorAll('[data-tab]').forEach((x) => { x.classList.toggle('active', x === b); x.classList.toggle('btn-primary', x === b); });
        render(true);
      }));
      await render(true);
      S.timer = setInterval(() => {
        if (!CM.token) return;               /* 会话过期：停止刷新 */
        if (document.hidden) return;         /* 标签页在后台：不空转请求 */
        if (!CM.router.current || CM.router.current.id !== 'relay') return;
        const now = Date.now();
        if (S.nextAt && now < S.nextAt) return;   /* 按当前页签的节奏放行 */
        S.nextAt = now + (REFRESH_MS_TAB[S.tab] || REFRESH_MS);
        render();
      }, 1000);
    },
    hide() { clearInterval(S.timer); },
    show() { render(); },
  });

  const FETCH = {
    overview: ['summary', 'health', 'selfheal', 'quota', 'providers', 'hourly', 'daily'],
    traffic: ['summary', 'hourly', 'daily', 'models', 'models-daily', 'status', 'routes', 'endpoints', 'methods'],
    quality: ['summary', 'latency', 'ttfb', 'streaming', 'sizes', 'failover', 'errors', 'model-status', 'weekday'],
    hitrate: [],
    tokenhit: [],
    clients: ['sessions', 'session-memory', 'useragents', 'recent', 'summary'],
    tokens: [],
    keys: ['providers'],
  };

  async function load(names) {
    const out = {};
    await Promise.all(names.map(async (n) => {
      try { out[n] = await CM.api.get('/relay/' + n, 2000); } catch (e) { out[n] = { __error: e.message }; }
    }));
    return out;
  }

  /* switching=true -> 全量重建；否则若该页签已构建 -> 原地更新（传入新数据） */
  async function render(switching) {
    const box = document.getElementById('rl-body');
    if (!box) return;
    /* 页头刷新节奏随页签变化（命中率类是 2 秒实时） */
    const liveEl = document.getElementById('rl-live');
    if (liveEl) {
      const ms = REFRESH_MS_TAB[S.tab] || REFRESH_MS;
      const txt = ms < REFRESH_MS ? `实时 · 每 ${ms / 1000} 秒刷新` : `每 ${ms / 1000} 秒自动刷新`;
      if (liveEl.textContent !== txt) liveEl.textContent = txt;
    }
    const upd = S.upd[S.tab];
    if (!switching && upd) {
      if (!CM.token) return;                 /* 未登录/会话过期：不刷新 */
      const d = await load(FETCH[S.tab] || []);
      try { await upd(d); } catch (e) { console.error(e); }
      return;
    }
    box.innerHTML = '<div class="card">' + ui.skeleton(6) + '</div>';
    S.upd[S.tab] = null;
    const d = await load(FETCH[S.tab] || []);
    try {
      if (S.tab === 'overview') S.upd[S.tab] = tabOverview(box, d);
      else if (S.tab === 'traffic') S.upd[S.tab] = tabTraffic(box, d);
      else if (S.tab === 'quality') S.upd[S.tab] = tabQuality(box, d);
      else if (S.tab === 'hitrate') S.upd[S.tab] = tabHitrate(box, d);
      else if (S.tab === 'tokenhit') S.upd[S.tab] = tabTokenHit(box, d);
      else if (S.tab === 'clients') S.upd[S.tab] = tabClients(box, d);
      else if (S.tab === 'tokens') S.upd[S.tab] = tabTokens(box, d);
      else if (S.tab === 'keys') S.upd[S.tab] = await tabKeys(box, d);
      /* 首次填充：用刚拉到的数据执行一次原地更新 */
      if (S.upd[S.tab]) await S.upd[S.tab](d);
    } catch (e) {
      box.innerHTML = '<div class="card"><div class="card-title">渲染失败</div><pre class="pre">' + fmt.esc(e.message) + '</pre></div>';
    }
  }

  /* ============ generic helpers ============ */
  const num = (v) => Number(v || 0).toLocaleString('zh-CN');
  const tm = (v) => fmt.esc(String(v || '').replace('T', ' ').slice(5, 19));
  const tmFull = (v) => fmt.esc(String(v || '').replace('T', ' ').slice(0, 19));
  const pct1 = (v) => (Number(v) || 0).toFixed(1) + '%';

  function stat(label, value, sub, key) {
    const k = key ? ' data-live="' + key + '"' : '';
    return '<div class="card stat"' + k + '><div class="stat-label">' + label + '</div>' +
      '<div class="stat-value" style="font-size:22px" data-v>' + value + '</div>' +
      '<div class="stat-sub" data-s>' + (sub || '') + '</div></div>';
  }
  function liveStats(keys) {
    return '<div class="grid g' + (keys.length === 3 ? '3' : '4') + ' mb">' + keys.map((x) => stat(x[1], '—', '', x[0])).join('') + '</div>';
  }
  function live(root, key) {
    const c = root.querySelector('[data-live="' + key + '"]');
    return c ? { v: c.querySelector('[data-v]'), s: c.querySelector('[data-s]') } : { v: null, s: null };
  }
  function setNum(el, to, fmtFn) {
    if (!el) return;
    ui.animateNumber(el, to, fmtFn || ((x) => num(Math.round(x))), 500, 0.5);
  }
  function setText(el, html) { if (el && el.innerHTML !== html) el.innerHTML = html; }

  /* cheap stable signature so unchanged tables are never rebuilt */
  function sigOf(x) {
    try {
      const s = JSON.stringify(x);
      let h = 5381;
      for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
      return h + ':' + s.length;
    } catch (_) { return 'x' + Math.random(); }
  }
  function slot(box, id, html, sig) {
    const el = box.querySelector('#' + id);
    if (!el) return;
    if (sig !== undefined) {
      if (el.dataset.sig === sig) return;
      el.dataset.sig = sig;
    }
    el.innerHTML = html;
  }

  /* columns: [key, label, cls, renderFn] */
  function T(cols, rows, style) {
    const c = cols.map((x) => ({ k: x[0], label: x[1], cls: x[2] || '', render: x[3] || ((v) => (v == null || v === '' ? '<span class="muted">-</span>' : fmt.esc(typeof v === 'number' ? num(v) : String(v)))) }));
    return ui.table(c, rows, { style: style || 'max-height:34vh' });
  }
  function section(title, inner, right) {
    const r = right ? '<div class="right">' + right + '</div>' : '';
    return '<div class="card"><div class="card-title">' + title + r + '</div>' + inner + '</div>';
  }
  function histTable(hist) {
    return T([['bucket', '区间', 'mono'], ['count', '数量', 'num']], (hist || []).map((h) => ({ bucket: h.bucket, count: h.count })), 'max-height:260px');
  }

  function barChart(id, labels, series) {
    const cv = document.getElementById(id);
    if (!cv || !window.Chart) return;
    if (!cv._chart) {
      const stacked = !!(series[0] && series[0].stack);
      cv._chart = new Chart(cv.getContext('2d'), {
        type: 'bar',
        data: { labels, datasets: series.map((s) => ({ label: s.label, data: s.data, backgroundColor: s.color, borderColor: s.border || s.color, borderWidth: 1, stack: s.stack ? 'a' : undefined })) },
        options: {
          responsive: true, maintainAspectRatio: false, animation: false,
          plugins: { legend: { labels: { color: '#7f92a8', boxWidth: 10, font: { size: 10.5 } } } },
          scales: {
            x: { stacked, ticks: { color: '#5d7089', font: { size: 9 }, maxRotation: 0, autoSkip: true, maxTicksLimit: 24 }, grid: { color: 'rgba(34,48,65,.5)' } },
            y: { stacked, beginAtZero: true, ticks: { color: '#5d7089', font: { size: 9 } }, grid: { color: 'rgba(34,48,65,.5)' } },
          },
        },
      });
      return;
    }
    /* in-place update (no destroy, no flash) */
    cv._chart.data.labels = labels;
    series.forEach((s, i) => { const ds = cv._chart.data.datasets[i]; if (ds) ds.data = s.data; });
    cv._chart.update('none');
  }

  /* 折线图（命中率趋势）：y 轴固定 0-100%，空档用 spanGaps 连起来 */
  function lineChart(id, labels, series) {
    const cv = document.getElementById(id);
    if (!cv || !window.Chart) return;
    const build = () => series.map((s) => ({
      label: s.label, data: s.data, borderColor: s.color,
      backgroundColor: s.color, borderWidth: 1.8, pointRadius: 0,
      tension: 0.25, spanGaps: true, fill: false,
    }));
    if (!cv._chart) {
      cv._chart = new Chart(cv.getContext('2d'), {
        type: 'line',
        data: { labels, datasets: build() },
        options: {
          responsive: true, maintainAspectRatio: false, animation: false,
          plugins: { legend: { labels: { color: '#7f92a8', boxWidth: 10, font: { size: 10.5 } } } },
          scales: {
            x: { ticks: { color: '#5d7089', font: { size: 9 }, maxTicksLimit: 24, maxRotation: 0 }, grid: { color: 'rgba(34,48,65,.5)' } },
            y: {
              min: 0, max: 100,
              ticks: { color: '#5d7089', font: { size: 9 }, callback: (v) => v + '%' },
              grid: { color: 'rgba(34,48,65,.5)' },
            },
          },
        },
      });
      return;
    }
    /* 套餐数量可能变化（有流量的线路增减），直接重建 datasets 更稳 */
    cv._chart.data.labels = labels;
    cv._chart.data.datasets = build();
    cv._chart.update('none');
  }

  /* ============ 1. overview ============ */
  function tabOverview(box, d) {
    box.innerHTML =
      liveStats([['total', '总请求'], ['success', '成功率'], ['p95', 'P95 延迟'], ['failrate', '失败率']]) +
      liveStats([['traffic', '总流量(出)'], ['retry', '重试 / 故障切换'], ['peak', '峰值时段'], ['collector', '采集器']]) +
      section('今日 24 小时分布', '<div class="chart-box lg"><canvas id="rl-hourly"></canvas></div>') +
      section('近 7 日请求与错误', '<div class="chart-box"><canvas id="rl-daily"></canvas></div>') +
      section('配额与自愈', '<div class="grid g2"><div id="rl-quota"></div><div id="rl-selfheal"></div></div>') +
      section('供应商状态', '<div id="rl-provs"></div>');

    const R = {};
    ['total', 'success', 'p95', 'failrate', 'traffic', 'retry', 'peak', 'collector'].forEach((k) => { R[k] = live(box, k); });
    const quotaBox = box.querySelector('#rl-quota');

    /* 固定配额行，让进度条靠 CSS transition 平滑推进 */
    quotaBox.innerHTML =
      '<div class="muted small mb">当前密钥 <span class="mono" data-q="key">-</span> · 刷新于 <span class="mono" data-q="at">-</span></div>' +
      '<div class="rl-wins" style="grid-template-columns:1fr">' +
      ['rolling', 'weekly', 'monthly'].map((w) =>
        '<div class="rl-win" data-win="' + w + '"><span>' + WIN_LABEL[w] + '</span>' +
        '<div class="bar ok"><div style="width:0%"></div></div><span class="mono">–</span></div>').join('') +
      '</div>' +
      '<button class="btn btn-sm mt" id="rl-q-refresh">' + icon('refresh', { size: 12 }) + ' 刷新配额</button>';

    box.querySelector('#rl-q-refresh').onclick = (e) => act('quota/refresh', {}, e.currentTarget);

    return (nd) => {
      nd = nd || {};
      const s = nd.summary || {}, h = nd.health || {}, sh = nd.selfheal || {}, q = nd.quota || {};
      const provs = ((nd.providers || {}).providers || []);
      const hourly = (nd.hourly && nd.hourly.hourly) || [];
      const daily = ((nd.daily || {}).daily || []).slice(-7);

      setNum(R.total.v, Number((s.totals && s.totals.requests) || 0));
      setText(R.total.s, '今日 <b>' + num(s.requests_today) + '</b> · 会话 ' + num(s.sessions) + ' · 客户端 ' + num(s.clients));
      setNum(R.success.v, Number(s.success_rate || 0), (x) => x.toFixed(1) + '%');
      setText(R.success.s, '成功 ' + num(s.success) + ' · 错误 ' + num(s.errors) + ' · 超时 ' + num(s.timeouts));
      setNum(R.p95.v, Number(s.p95_latency || 0), (x) => x.toFixed(0) + ' ms');
      setText(R.p95.s, '均值 ' + (s.avg_latency || 0) + ' · P99 ' + (s.p99_latency || 0) + ' · 流式 ' + (s.streaming_rate || 0) + '%');
      setNum(R.failrate.v, Number(s.error_rate || 0), (x) => x.toFixed(1) + '%');
      setText(R.failrate.s, '4xx ' + num(s.errors_4xx) + ' · 5xx ' + num(s.errors_5xx) + ' · 未授权 ' + num(s.unauthorized));
      setText(R.traffic.v, fmt.bytes(s.bytes_out));
      setText(R.traffic.s, '入站 ' + fmt.bytes(s.bytes_in) + ' · 均值/请求 ' + fmt.bytes(s.avg_bytes_total));
      setNum(R.retry.v, Number(s.retried || 0));
      setText(R.retry.s, '切换率 ' + (s.failover_rate || 0) + '% · 尝试/请求 ' + (s.attempts_per_request || 1));
      setText(R.peak.v, s.peak_hour || '-');
      setText(R.peak.s, '该时段 ' + num(s.peak_hour_requests) + ' 请求');
      const okc = h.ok !== false;
      setText(R.collector.v, okc ? '正常' : '异常');
      if (R.collector.v) R.collector.v.style.color = okc ? '' : '#f87171';
      setText(R.collector.s, '入库 ' + num(h.rows_ingested) + '/' + num(h.rows_total) + ' · 运行 ' + fmt.uptime(h.uptime_s || 0));

      barChart('rl-hourly', hourly.map((x) => x.hour), [
        { label: '请求', data: hourly.map((x) => x.requests), color: 'rgba(59,130,246,.55)', stack: true },
        { label: '错误', data: hourly.map((x) => x.errors), color: 'rgba(239,68,68,.6)', stack: true },
      ]);
      barChart('rl-daily', daily.map((r) => r.day.slice(5)), [
        { label: '请求', data: daily.map((r) => r.requests), color: 'rgba(59,130,246,.55)' },
        { label: '错误', data: daily.map((r) => r.errors), color: 'rgba(239,68,68,.6)' },
      ]);

      setText(quotaBox.querySelector('[data-q="key"]'), fmt.esc(q.key_masked || '-'));
      setText(quotaBox.querySelector('[data-q="at"]'), fmt.esc(String(q.fetched_at || '-').replace('T', ' ')));
      ['rolling', 'weekly', 'monthly'].forEach((w) => {
        const row = quotaBox.querySelector('[data-win="' + w + '"]');
        const u = (q.usage || {})[w];
        if (!row) return;
        if (!u) { row.style.opacity = '.35'; return; }
        row.style.opacity = '';
        const pct = u.percent || 0;
        const bar = row.querySelector('.bar');
        bar.className = 'bar ' + (pct >= 90 ? 'bad' : pct >= 70 ? 'warn' : 'ok');
        bar.firstElementChild.style.width = pct + '%';   /* CSS transition */
        row.querySelector('.mono').textContent = pct + '%';
      });

      slot(box, 'rl-selfheal',
        '<div class="muted small mb">自愈状态 ' + (sh.status === 'ok' ? ui.badge('全部通过', 'ok') : ui.badge(sh.status || '-', sh.status === 'ok' ? 'ok' : 'warn')) +
        ' <span class="mono small">' + fmt.esc(String(sh.checked_at || '').replace('T', ' ')) + ' · ' + (sh.duration_ms || 0) + 'ms</span></div>' +
        T([['name', '检查项', 'mono'], ['ok', '结果', '', (v) => (v ? ui.badge('OK', 'ok') : ui.badge('FAIL', 'bad'))], ['detail', '详情', 'wrap small'], ['ms', '耗时', 'num', (v) => v + 'ms']], (sh.checks || []).slice(0, 10), 'max-height:220px') +
        '<button class="btn btn-sm mt" id="rl-sh-run">' + icon('restart', { size: 12 }) + ' 执行自愈</button>',
        sigOf(sh.checks));
      const sb = box.querySelector('#rl-sh-run');
      if (sb && !sb.dataset.wired) { sb.dataset.wired = '1'; sb.onclick = () => act('selfheal/run', {}, sb, true); }

      slot(box, 'rl-provs', provs.map((p) =>
        '<div class="rl-prov ' + (p.exhausted ? 'rl-exhausted' : '') + '"><div class="rl-prov-head">' +
        '<span class="rl-prov-name">' + fmt.esc(p.label || p.id) + '</span>' +
        (p.exhausted ? '<span class="badge bad">配额耗尽</span>' : '<span class="badge ok">可用</span>') +
        '<span class="chip">密钥 ' + p.key_count + ' · 可用 ' + p.usable + '</span>' +
        '<span class="chip">窗口 ' + (p.windows || []).join('/') + '</span></div></div>').join(''), sigOf(provs));
    };
  }

  /* ============ 2. traffic ============ */
  function tabTraffic(box, d) {
    box.innerHTML =
      liveStats([['today_req', '今日请求'], ['today_out', '今日出站'], ['today_err', '今日错误'], ['today_sr', '今日成功率']]) +
      section('模型请求趋势', '<div class="chart-box lg"><canvas id="rl-md"></canvas></div>') +
      section('模型用量 Top 15', '<div id="rl-traffic-models"></div>') +
      '<div class="grid g3">' +
      section('状态码分布', '<div id="rl-traffic-status"></div>') +
      section('线路分布', '<div id="rl-traffic-routes"></div>') +
      section('HTTP 方法', '<div id="rl-traffic-methods"></div>') +
      '</div>' +
      section('接口端点', '<div id="rl-traffic-endpoints"></div>') +
      section('每日明细（区间环比）', '<div id="rl-traffic-daily"></div>');

    const R = {};
    ['today_req', 'today_out', 'today_err', 'today_sr'].forEach((k) => { R[k] = live(box, k); });
    const PAL = ['#4370f0', '#8b5cf6', '#22c55e', '#f59e0b', '#ef4444', '#06b6d4', '#ec4899', '#84cc16'];

    return (nd) => {
      nd = nd || {};
      const daily = ((nd.daily || {}).daily || []);
      const today = daily[daily.length - 1] || {}, yest = daily[daily.length - 2] || {};
      const delta = (a, b) => (!b ? '-' : (((a || 0) - (b || 0)) / Math.max(1, b) * 100).toFixed(1) + '%');

      setNum(R.today_req.v, Number(today.requests || 0));
      setText(R.today_req.s, '环比昨日 ' + delta(today.requests, yest.requests));
      setText(R.today_out.v, fmt.bytes(today.bytes_out));
      setText(R.today_out.s, '环比 ' + delta(today.bytes_out, yest.bytes_out));
      setNum(R.today_err.v, Number(today.errors || 0));
      setText(R.today_err.s, '4xx ' + num(today.errors_4xx) + ' · 5xx ' + num(today.errors_5xx));
      setNum(R.today_sr.v, Number(today.success_rate || 0), (x) => x.toFixed(1) + '%');
      setText(R.today_sr.s, '均延迟 ' + (today.avg_latency || 0) + ' · TTFB ' + (today.avg_ttfb || 0));

      const models = ((nd.models || {}).models || []).slice(0, 15).map((m) => Object.assign({}, m, {
        _sr: '<span style="color:' + (m.success_rate >= 99 ? '#4ade80' : m.success_rate >= 80 ? '#facc15' : '#f87171') + '">' + pct1(m.success_rate) + '</span>',
        _last: tm(m.last_seen),
      }));
      slot(box, 'rl-traffic-models', T([
        ['model', '模型', 'mono'], ['route', '线路', '', (v) => ui.badge(v || '-', 'info')], ['requests', '请求', 'num'],
        ['bytes_out', '输出', 'num', (v) => fmt.bytes(v)], ['avg_latency', '均延迟', 'num'], ['p95_latency', 'P95', 'num'],
        ['_sr', '成功率', 'num'], ['sessions', '会话', 'num'], ['_last', '最近'],
      ], models), sigOf(models));

      slot(box, 'rl-traffic-status', T([['status', '状态码', 'mono'], ['c', '数量', 'num'], ['attempts', '尝试', 'num']], (nd.status || {}).status || []), sigOf((nd.status || {}).status));
      slot(box, 'rl-traffic-routes', T([['route', '线路', 'mono'], ['reqs', '请求', 'num'], ['ok', '成功', 'num'], ['bout', '出站', 'num', (v) => fmt.bytes(v)], ['avg_rt', '均延迟', 'num'], ['avg_ttfb', 'TTFB', 'num'], ['retried', '重试', 'num']], (nd.routes || {}).routes || []), sigOf((nd.routes || {}).routes));
      slot(box, 'rl-traffic-methods', T([['method', '方法', 'mono'], ['requests', '请求', 'num'], ['bytes_out', '出站', 'num', (v) => fmt.bytes(v)], ['clients', '客户端', 'num'], ['errors', '错误', 'num']], (nd.methods || {}).methods || []), sigOf((nd.methods || {}).methods));
      slot(box, 'rl-traffic-endpoints', T([['uri', '路径', 'mono'], ['requests', '请求', 'num'], ['bytes_out', '出站', 'num', (v) => fmt.bytes(v)], ['avg_latency', '均延迟', 'num'], ['avg_ttfb', 'TTFB', 'num']], (nd.endpoints || {}).endpoints || []), sigOf((nd.endpoints || {}).endpoints));
      slot(box, 'rl-traffic-daily', T([
        ['day', '日期', 'mono'], ['requests', '请求', 'num'], ['bytes_out', '出站', 'num', (v) => fmt.bytes(v)],
        ['bytes_in', '入站', 'num', (v) => fmt.bytes(v)], ['errors', '错误', 'num'], ['errors_4xx', '4xx', 'num'],
        ['errors_5xx', '5xx', 'num'], ['sessions', '会话', 'num'], ['clients', '客户端', 'num'], ['models', '模型数', 'num'],
        ['success_rate', '成功率', 'num', (v) => pct1(v)], ['avg_latency', '均延迟', 'num'], ['avg_ttfb', 'TTFB', 'num'],
      ], daily.slice(-30), 'max-height:32vh'), sigOf(daily));

      const md = nd['models-daily'] || {};
      const mrows = (md.models || []).slice(0, 8);
      barChart('rl-md', (md.days || []).map((x) => x.slice(5)),
        mrows.map((m, i) => ({ label: m, data: ((md.series || {})[m] || {}).requests || [], color: PAL[i % 8] + 'aa', border: PAL[i % 8] })));
    };
  }

  /* ============ 3. quality ============ */
  function tabQuality(box, d) {
    box.innerHTML =
      liveStats([['e2e', '端到端延迟 P50/P95/P99'], ['ttfb', 'TTFB P50/P95'], ['stream', '流式占比'], ['retry2', '重试 / 故障切换']]) +
      '<div class="grid g2">' +
      section('端到端延迟分布', '<div id="rl-q-lat"></div>') +
      section('首字节 TTFB 分布', '<div id="rl-q-ttfb"></div>') +
      '</div>' +
      '<div class="grid g2">' +
      section('TTFB 按线路', '<div id="rl-q-route"></div>') +
      section('流式会话时长分布', '<div id="rl-q-dur"></div>') +
      '</div>' +
      section('流式请求（每日）', '<div class="chart-box sm"><canvas id="rl-stream"></canvas></div>') +
      section('流式按模型', '<div id="rl-q-bymodel"></div>') +
      '<div class="grid g2">' +
      section('报文大小分布（请求）', '<div id="rl-q-size-in"></div>', '<span id="rl-q-size-in-note" class="muted small"></span>') +
      section('报文大小分布（响应）', '<div id="rl-q-size-out"></div>', '<span id="rl-q-size-out-note" class="muted small"></span>') +
      '</div>' +
      '<div class="grid g2">' +
      section('重试与故障切换', '<div id="rl-q-failover"></div>') +
      section('上游状态明细', '<div id="rl-q-upstream"></div>') +
      '</div>' +
      section('每小时 × 星期 请求热力图', '<div id="rl-q-heat"></div>') +
      section('模型可用性巡检', '<div id="rl-q-ms"></div>') +
      section('最近错误', '<div id="rl-q-errors"></div>');

    const R = {};
    ['e2e', 'ttfb', 'stream', 'retry2'].forEach((k) => { R[k] = live(box, k); });

    return (nd) => {
      nd = nd || {};
      const s = nd.summary || {}, ttfb = nd.ttfb || {}, st = nd.streaming || {}, sz = nd.sizes || {}, fo = nd.failover || {};
      const ms = nd['model-status'] || {};
      const cells = (nd.weekday || {}).cells || [];

      setText(R.e2e.v, [s.p50_latency, s.p95_latency, s.p99_latency].map((v) => Number(v || 0).toFixed(0)).join('/') + ' ms');
      setText(R.e2e.s, '最大 ' + (s.max_latency || 0) + ' ms · 均值 ' + (s.avg_latency || 0));
      setNum(R.ttfb.v, ttfb.overall ? Number(ttfb.overall.p50 || 0) : 0, (x) => x.toFixed(0) + ' ms');
      setText(R.ttfb.s, '端到端均值 ' + (ttfb.overall ? ttfb.overall.avg_total : '-') + ' ms · 样本 ' + num(tfbSamples(ttfb)));
      setNum(R.stream.v, Number(s.streaming_rate || 0), (x) => x.toFixed(1) + '%');
      setText(R.stream.s, '流式 ' + num(s.streaming) + ' · 非流式 ' + num((s.requests || 0) - (s.streaming || 0)));
      setNum(R.retry2.v, Number(s.retried || 0));
      setText(R.retry2.s, '切换率 ' + (s.failover_rate || 0) + '%');

      slot(box, 'rl-q-lat', histTable((nd.latency || {}).histogram), sigOf((nd.latency || {}).histogram));
      slot(box, 'rl-q-ttfb', histTable(ttfb.histogram), sigOf(ttfb.histogram));
      slot(box, 'rl-q-route', T([['route', '线路', 'mono'], ['p50', 'P50', 'num'], ['p95', 'P95', 'num'], ['avg', '均值', 'num'], ['count', '样本', 'num']], ttfb.by_route || []), sigOf(ttfb.by_route));
      slot(box, 'rl-q-dur', histTable(st.duration), sigOf(st.duration));
      slot(box, 'rl-q-bymodel', T([['model', '模型', 'mono'], ['streaming', '流式', 'num'], ['total', '总计', 'num']], (st.by_model || []).slice(0, 15)), sigOf(st.by_model));
      slot(box, 'rl-q-size-in', histTable(sz.request), sigOf(sz.request));
      slot(box, 'rl-q-size-out', histTable(sz.response), sigOf(sz.response));

      const heat = (() => {
        if (!cells.length) return '<div class="muted small">无数据</div>';
        const maxCell = Math.max(1, ...cells.map((c) => c[2] || 0));
        const days = ['日', '一', '二', '三', '四', '五', '六'];
        const grid = {};
        cells.forEach((c) => { grid[c[1] + '-' + c[0]] = c[2]; });
        let html = '<div style="overflow:auto"><table class="tbl"><thead><tr><th>时</th>' + days.map((x) => '<th class="num">' + x + '</th>').join('') + '</tr></thead><tbody>';
        for (let hh = 0; hh < 24; hh++) {
          html += '<tr><td class="mono">' + String(hh).padStart(2, '0') + '</td>';
          for (let dd = 0; dd < 7; dd++) {
            const v = grid[dd + '-' + hh] || 0;
            const a = v ? (0.15 + 0.85 * Math.min(1, v / maxCell)).toFixed(2) : 0;
            html += '<td class="num" style="background:rgba(var(--accent-rgb),' + a + ');font-size:11px">' + (v || '') + '</td>';
          }
          html += '</tr>';
        }
        return html + '</tbody></table></div>';
      })();
      slot(box, 'rl-q-heat', heat, sigOf(cells));

      const provMs = Object.entries((ms.providers || {}));
      let msHtml = '';
      provMs.forEach(([pid, pv]) => {
        const models = Object.entries(pv.models || {});
        const okc = models.filter(([, m]) => m.status === 'ok').length;
        msHtml += '<div class="muted small mt mb">' + fmt.esc(pid) + ' · ' + okc + '/' + models.length + ' 可用 · 巡检于 ' + fmt.esc(String(pv.checked_at || ms.generated_at || '').replace('T', ' ')) + '</div>';
        msHtml += T([['name', '模型', 'mono'], ['st', '状态 / 路线'], ['at', '检查时间', 'small']],
          models.map(([k, m]) => ({ name: k, st: m.status === 'ok' ? ui.badge('OK ' + (m.via || ''), 'ok') : ui.badge((m.status || '?') + ' ' + (m.code || ''), 'bad'), at: String(m.checked_at || '').replace('T', ' ').slice(5, 19) })), 'max-height:280px');
      });
      msHtml += '<button class="btn btn-sm mt" id="rl-mc">' + icon('scan', { size: 12 }) + ' 立即巡检</button>';
      slot(box, 'rl-q-ms', msHtml, sigOf(ms.providers));
      const mc = box.querySelector('#rl-mc');
      if (mc && !mc.dataset.wired) { mc.dataset.wired = '1'; mc.onclick = () => act('modelcheck', {}, mc, true); }

      slot(box, 'rl-q-failover', T([['day', '日期', 'mono'], ['requests', '请求', 'num'], ['retried', '重试', 'num'], ['first_try', '首次成功', 'num'], ['attempts', '总尝试', 'num'], ['failed', '失败', 'num']], fo.daily || []), sigOf(fo.daily));
      slot(box, 'rl-q-upstream',
        '<div class="muted small mb">上游状态码</div>' + T([['us', '状态', 'mono'], ['c', '数量', 'num']], fo.upstream_status || []) +
        '<div class="muted small mt mb">上游地址</div>' + T([['addr', '地址', 'mono'], ['requests', '请求', 'num']], fo.upstreams || []),
        sigOf([fo.upstream_status, fo.upstreams]));

      const inNote = box.querySelector('#rl-q-size-in-note');
      const outNote = box.querySelector('#rl-q-size-out-note');
      if (inNote) inNote.textContent = '均值 ' + fmt.bytes(sz.avg_request) + ' · 最大 ' + fmt.bytes(sz.max_request);
      if (outNote) outNote.textContent = '均值 ' + fmt.bytes(sz.avg_response) + ' · 最大 ' + fmt.bytes(sz.max_response);

      const sd = st.daily || [];
      barChart('rl-stream', sd.map((x) => x.day.slice(5)), [
        { label: '流式', data: sd.map((x) => x.streaming), color: 'rgba(34,197,94,.55)', stack: true },
        { label: '非流式', data: sd.map((x) => x.other), color: 'rgba(122,136,153,.5)', stack: true },
      ]);

      slot(box, 'rl-q-errors', T([
        ['time', '时间', 'small'], ['model', '模型', 'mono'], ['route', '线路', 'mono'], ['uri', '路径', 'mono small'],
        ['status', '状态', 'num'], ['bytes_out', '出站', 'num', (v) => fmt.bytes(v)], ['latency', '延迟', 'num'],
        ['attempts', '尝试', 'num'], ['upstream_status', '上游', 'num'],
      ], ((nd.errors || {}).errors || []).slice(0, 40).map((x) => Object.assign({}, x, { time: String(x.time || '').replace('T', ' ').slice(5, 19) })), 'max-height:34vh'), sigOf((nd.errors || {}).errors));
    };
  }
  function tfbSamples(t) { return (t.overall && t.overall.samples) || 0; }

  /* ============ 4. clients & sessions ============ */
  function tabClients(box, d) {
    box.innerHTML =
      liveStats([['sess', '会话数(今日)'], ['clients', '客户端数'], ['sessmem', '会话内存视图'], ['uas', '客户端软件']]) +
      '<div class="grid g2">' +
      section('会话分布（按请求数）', '<div id="rl-c-dist"></div>') +
      section('每日会话', '<div id="rl-c-daily"></div>') +
      '</div>' +
      section('Top 会话', '<div id="rl-c-top"></div>') +
      section('会话上下文明细（内存视图）', '<div id="rl-c-mem"></div>') +
      section('客户端软件构成', '<div id="rl-c-ua"></div>') +
      section('实时活动（最近请求）', '<div id="rl-c-recent"></div>');

    const R = {};
    ['sess', 'clients', 'sessmem', 'uas'].forEach((k) => { R[k] = live(box, k); });

    return (nd) => {
      nd = nd || {};
      const s = nd.sessions || {}, sm = nd['session-memory'] || {}, ua = nd.useragents || {}, rec = nd.recent || {}, sum = nd.summary || {};
      const todayS = ((s.daily || [])[(s.daily || []).length - 1]) || {};
      const uas = (ua.user_agents || []).map((u) => Object.assign({}, u, {
        _sr: pct1(u.success_rate), _samples: (u.samples || []).join(' | ').slice(0, 60), _last: tm(u.last_seen),
      }));
      const sessTop = (s.top || []).map((x) => Object.assign({}, x, { _first: tm(x.first), _last: tm(x.last) }));
      const smRows = (sm.sessions || []).map((x) => Object.assign({}, x, { _chr: Math.round(x.avg_ctx || 0), _cache: pct1(x.cache_hit_rate) }));
      const recent = ((rec.recent || []).slice(0, 60)).map((r) => Object.assign({}, r, {
        _time: String(r.time || '').replace('T', ' ').slice(11, 19), _out: fmt.bytes(r.bytes_out), _in: fmt.bytes(r.bytes_in),
        _stream: r.streaming ? ui.badge('S', 'ok') : '', _sess: fmt.esc(String(r.session || '').slice(0, 20)),
      }));

      setNum(R.sess.v, Number(todayS.sessions || 0));
      setText(R.sess.s, '今日请求 ' + num(todayS.requests));
      setNum(R.clients.v, Number(sum.clients || 0));
      setText(R.clients.s, '今日 ' + num(sum.clients_today) + ' · 新增 ' + num(sum.new_clients));
      setNum(R.sessmem.v, Number((sm.sessions || []).length));
      setText(R.sessmem.s, '累计轮次 ' + num((sm.sessions || []).reduce((a, b) => a + (b.turns || 0), 0)));
      setNum(R.uas.v, Number((ua.user_agents || []).length));
      setText(R.uas.s, '主要 ' + fmt.esc(((ua.user_agents || [])[0] || {}).family || '-'));

      slot(box, 'rl-c-dist', T([['bucket', '请求数区间', 'mono'], ['count', '会话数', 'num']], s.distribution || []), sigOf(s.distribution));
      slot(box, 'rl-c-daily', T([['day', '日期', 'mono'], ['sessions', '会话', 'num'], ['requests', '请求', 'num']], s.daily || []), sigOf(s.daily));
      slot(box, 'rl-c-top', T([['session', '会话', 'mono small'], ['requests', '请求', 'num'], ['models', '模型', 'num'], ['clients', '客户端', 'num'], ['_first', '首次'], ['_last', '最近']], sessTop), sigOf(s.top));
      slot(box, 'rl-c-mem', T([
        ['sess', '会话', 'mono small'], ['turns', '轮次', 'num'], ['total', '累计Token', 'num'], ['input', '输入', 'num'], ['output', '输出', 'num'],
        ['cache_read', '缓存读', 'num'], ['max_ctx', '最大上下文', 'num'], ['_chr', '均上下文', 'num'], ['latest_context', '最近上下文', 'num'],
        ['_cache', '缓存命中', 'num'], ['archived_turns', '已归档', 'num'], ['max_msgs', '最大消息', 'num'],
      ], smRows, 'max-height:32vh'), sigOf(sm.sessions));
      slot(box, 'rl-c-ua', T([
        ['family', 'UA 族', 'mono'], ['requests', '请求', 'num'], ['bytes_out', '出站', 'num', (v) => fmt.bytes(v)], ['bytes_in', '入站', 'num', (v) => fmt.bytes(v)],
        ['sessions', '会话', 'num'], ['clients', '客户端', 'num'], ['_sr', '成功率', 'num'], ['_samples', '样本', 'mono small', (v) => fmt.esc(v)], ['_last', '最近'],
      ], uas), sigOf(ua.user_agents));
      slot(box, 'rl-c-recent', T([
        ['_time', '时间'], ['ip', '来源IP', 'mono'], ['model', '模型', 'mono'], ['route', '线路', 'mono'], ['method', '方法', 'mono'],
        ['uri', '路径', 'mono small'], ['status', '状态', 'num'], ['_out', '出站', 'num'], ['_in', '入站', 'num'],
        ['latency', '延迟', 'num'], ['ttfb', 'TTFB', 'num'], ['_stream', '流式'], ['_sess', '会话', 'mono small'],
      ], recent, 'max-height:40vh'), sigOf(rec.recent));
    };
  }

  /* ============ 5. tokens (日 / 周 / 月 · 按模型分列) ============ */
  const WIN = [['d', '今日', 1], ['w', '本周', 7], ['m', '本月', 30]];
  function tabTokens(box, d) {
    box.innerHTML =
      '<div class="range-btns row mb" style="gap:8px">' +
      '<span class="muted small">统计窗口</span>' +
      WIN.map(([k, l, dd]) => `<button class="btn btn-sm" data-win="${k}" data-days="${dd}">${l} <span class="muted">(近${dd}天)</span></button>`).join('') +
      '<span class="muted small" style="margin-left:auto">Token 统计按滚动窗口聚合 · 数据自 <span class="mono" id="tk-since">-</span></span>' +
      '</div>' +
      liveStats([['tk_total', 'Token 总量'], ['tk_io', '输入 / 输出'], ['tk_cache', '缓存读 / 写'], ['tk_avg', '单请求 Token']]) +
      '<div class="grid g2">' +
      section('每日 Token 消耗', '<div class="chart-box lg"><canvas id="tk-daily"></canvas></div>') +
      section('Token 构成 <span class="muted small" id="tk-win-label"></span>', '<div id="tk-composition"></div>') +
      '</div>' +
      section('按模型 Token 统计（日 / 周 / 月 并排）', '<div id="tk-models"></div>') +
      section('按模型 Token 分布图 <span class="muted small" id="tk-modelchart-label"></span>', '<div class="chart-box lg"><canvas id="tk-modelchart"></canvas></div>') +
      section('每日 Token 明细', '<div id="tk-dailytable"></div>');

    const R = {};
    ['tk_total', 'tk_io', 'tk_cache', 'tk_avg'].forEach((k) => { R[k] = live(box, k); });
    let win = 'w';
    let W = null;

    const paint = () => {
      if (!W) return;
      const t = W[win] || {};
      const label = (WIN.find((x) => x[0] === win) || [])[1] || '';
      setText(box.querySelector('#tk-since'), t.since || '-');
      setText(box.querySelector('#tk-win-label'), label);
      setText(box.querySelector('#tk-modelchart-label'), label);

      setNum(R.tk_total.v, Number(t.total || 0));
      setText(R.tk_total.s, '采样 ' + num(t.measured) + '/' + num(t.requests) + ' · 覆盖 ' + (t.coverage || 0) + '%');
      setText(R.tk_io.v, num(t.input) + ' / ' + num(t.output));
      setText(R.tk_io.s, '推理 ' + num(t.reasoning) + ' · 输出占比 ' + pct1(t.output_share));
      setText(R.tk_cache.v, num(t.cache_read) + ' / ' + num(t.cache_write));
      setText(R.tk_cache.s, '缓存命中率 ' + pct1(t.cache_hit_rate));
      setNum(R.tk_avg.v, Number(t.avg_per_request || 0));
      setText(R.tk_avg.s, '最大 ' + num(t.max_request) + ' · 请求 ' + num(t.requests));

      const parts = [['输入', t.input, '#4370f0'], ['输出', t.output, '#8b5cf6'], ['缓存读', t.cache_read, '#22c55e'], ['缓存写', t.cache_write, '#f59e0b'], ['推理', t.reasoning, '#ef4444']].filter(([, v]) => v > 0);
      const tot = parts.reduce((a, [, v]) => a + v, 0) || 1;
      slot(box, 'tk-composition', parts.map(([l, v, c]) =>
        '<div style="margin-bottom:10px"><div class="row between small"><span>' + l + '</span><span class="mono">' + num(v) + ' · ' + (v / tot * 100).toFixed(1) + '%</span></div>' +
        '<div class="bar"><div style="width:' + (v / tot * 100).toFixed(2) + '%;background:' + c + '"></div></div></div>').join('') ||
        '<div class="muted small">该窗口无 Token 数据</div>', sigOf([t.input, t.output, t.cache_read, t.cache_write, t.reasoning]));

      const byModel = { d: {}, w: {}, m: {} };
      Object.keys(W).forEach((k) => { ((W[k] || {}).models || []).forEach((m) => { byModel[k][m.model] = m; }); });
      const allModels = [...new Set([...Object.keys(byModel.d), ...Object.keys(byModel.w), ...Object.keys(byModel.m)])];
      const rows = allModels.map((mo) => {
        const a = byModel.d[mo] || {}, b = byModel.w[mo] || {}, c = byModel.m[mo] || {};
        return {
          model: mo,
          d_total: num(a.total || 0), w_total: num(b.total || 0), m_total: num(c.total || 0),
          d_n: num(a.n || 0), w_n: num(b.n || 0), m_n: num(c.n || 0),
          m_input: num(c.input || b.input || a.input || 0), m_output: num(c.output || b.output || a.output || 0),
          m_cache: num(c.cache_read || b.cache_read || a.cache_read || 0),
          m_avg: num(Math.round(c.avg_total || b.avg_total || a.avg_total || 0)),
          _share: ((c.total || b.total || a.total || 0) / ((W[win] || {}).total || 1) * 100).toFixed(1) + '%',
        };
      }).sort((x, y) => (Number(String(y.m_total).replace(/,/g, '')) || 0) - (Number(String(x.m_total).replace(/,/g, '')) || 0));
      slot(box, 'tk-models', rows.length ? T([
        ['model', '模型', 'mono'], ['d_total', '今日 Token', 'num'], ['w_total', '本周 Token', 'num'], ['m_total', '本月 Token', 'num'],
        ['d_n', '今日请求', 'num'], ['w_n', '本周请求', 'num'], ['m_n', '本月请求', 'num'],
        ['m_input', '输入(月)', 'num'], ['m_output', '输出(月)', 'num'], ['m_cache', '缓存(月)', 'num'],
        ['m_avg', '均值/请求', 'num'], ['_share', '当前窗口占比', 'num'],
      ], rows, { style: 'max-height:44vh' }) : '<div class="muted small">无模型 Token 数据</div>', sigOf(rows));

      const winModels = ((W[win] || {}).models || []).slice(0, 12);
      barChart('tk-modelchart', winModels.map((m) => m.model), [
        { label: '输入', data: winModels.map((m) => m.input), color: 'rgba(59,130,246,.55)', stack: true },
        { label: '输出', data: winModels.map((m) => m.output), color: 'rgba(139,92,246,.55)', stack: true },
        { label: '缓存读', data: winModels.map((m) => m.cache_read), color: 'rgba(34,197,94,.55)', stack: true },
      ]);
      const daily = ((W[win] || {}).daily || []);
      barChart('tk-daily', daily.map((x) => x.day.slice(5)), [
        { label: '输入', data: daily.map((x) => x.input), color: 'rgba(59,130,246,.55)', stack: true },
        { label: '输出', data: daily.map((x) => x.output), color: 'rgba(139,92,246,.55)', stack: true },
        { label: '缓存读', data: daily.map((x) => x.cache_read), color: 'rgba(34,197,94,.55)', stack: true },
      ]);
      slot(box, 'tk-dailytable', T([
        ['day', '日期', 'mono'], ['n', '请求', 'num'], ['measured', '已测量', 'num'], ['input', '输入', 'num'],
        ['output', '输出', 'num'], ['cache_read', '缓存读', 'num'], ['reasoning', '推理', 'num'], ['total', '合计', 'num'],
      ], daily.slice(-30), 'max-height:32vh'), sigOf(daily));
    };

    box.querySelectorAll('[data-win]').forEach((b) => (b.onclick = () => {
      win = b.dataset.win;
      box.querySelectorAll('[data-win]').forEach((x) => { x.classList.toggle('active', x === b); x.classList.toggle('btn-primary', x === b); });
      paint();
    }));
    box.querySelector('[data-win="w"]').classList.add('active', 'btn-primary');

    /* 更新器：自行拉取三个窗口 + 模型路线 */
    return async () => {
      const [t1, t7, t30, mdl] = await Promise.all([
        CM.api.get('/relay/tokens?days=1', 2000).catch(() => ({})),
        CM.api.get('/relay/tokens?days=7', 2000).catch(() => ({})),
        CM.api.get('/relay/tokens?days=30', 2000).catch(() => ({})),
        CM.api.get('/relay/models?days=30', 2000).catch(() => ({})),
      ]);
      W = { d: t1, w: t7, m: t30 };
      paint();
    };
  }

  /* ============ 6. keys ============ */
  async function tabKeys(box, d) {
    const provs0 = (((d || {}).providers || {}).providers || []);
    box.innerHTML = '<div id="rl-key-list"></div>';
    const listBox = box.querySelector('#rl-key-list');

    const paint = async () => {
      let provs = provs0;
      try { const p = await CM.api.get('/relay/providers', 2000); provs = (p.providers || provs); } catch (_) {}
      listBox.innerHTML = provs.length ? provs.map((p) =>
        '<div class="card rl-prov ' + (p.exhausted ? 'rl-exhausted' : '') + '" data-prov="' + fmt.esc(p.id) + '">' +
        '<div class="card-title">' + fmt.esc(p.label || p.id) +
        '<div class="right">' + (p.exhausted ? '<span class="badge bad">配额耗尽</span>' : '<span class="badge ok">可用</span>') +
        '<span class="chip">密钥 ' + p.key_count + ' · 可用 ' + p.usable + '</span>' +
        '<span class="chip">窗口 ' + (p.windows || []).join('/') + '</span></div></div>' +
        '<div id="rl-keys-' + fmt.esc(p.id) + '"><div class="skel" style="height:40px"></div></div></div>').join('')
        : '<div class="card"><div class="muted small">无供应商数据</div></div>';

      await Promise.all(provs.map(async (p) => {
        const host = document.getElementById('rl-keys-' + p.id);
        if (!host) return;
        let kd;
        try { kd = await CM.api.get('/relay/keys?provider=' + encodeURIComponent(p.id), 2000); }
        catch (e) { host.innerHTML = '<div class="muted small">' + fmt.esc(e.message) + '</div>'; return; }
        const keys = kd.keys || [];
        const wins = kd.windows || ['rolling', 'weekly', 'monthly'];
        const sig = sigOf([keys, kd.switch_log]);
        if (host.dataset.sig === sig) return;
        host.dataset.sig = sig;
        const rows = keys.map((k) => {
          const whtml = wins.map((w) => {
            const u = (k.usage || {})[w] || {}, pct = u.percent || 0, bar = pct >= 90 ? 'bad' : pct >= 70 ? 'warn' : 'ok';
            return '<div class="rl-win"><span>' + (WIN_LABEL[w] || w) + '</span><div class="bar ' + bar + '"><div style="width:' + pct + '%"></div></div><span class="mono">' + pct + '%</span></div>';
          }).join('');
          return '<div class="rl-key ' + (k.active ? 'rl-active' : '') + '">' +
            '<div class="rl-key-head"><span class="mono">' + fmt.esc(k.masked || k.id) + '</span>' +
            (k.active ? '<span class="badge info">当前使用</span>' : '') +
            (k.enabled ? '<span class="badge ok">启用</span>' : '<span class="badge gray">禁用</span>') +
            ((k.exhausted_windows || []).length ? '<span class="badge warn">' + k.exhausted_windows.join('/') + ' 耗尽</span>' : '') +
            (k.recover_at ? '<span class="chip">恢复于 ' + fmt.esc(String(k.recover_at).replace('T', ' ').slice(5, 16)) + '</span>' : '') + '</div>' +
            '<div class="rl-wins" style="grid-template-columns:repeat(' + wins.length + ',1fr)">' + whtml + '</div>' +
            '<div class="row" style="gap:6px">' +
            '<button class="btn btn-sm" data-act="keys/activate" data-id="' + fmt.esc(k.id) + '" data-prov="' + fmt.esc(p.id) + '">切换使用</button>' +
            '<button class="btn btn-sm" data-act="keys/refresh" data-id="' + fmt.esc(k.id) + '" data-prov="' + fmt.esc(p.id) + '">刷新配额</button>' +
            '<button class="btn btn-sm" data-act="keys/test" data-id="' + fmt.esc(k.id) + '" data-prov="' + fmt.esc(p.id) + '">测试</button>' +
            '<button class="btn btn-sm" data-act="keys/enable" data-id="' + fmt.esc(k.id) + '" data-prov="' + fmt.esc(p.id) + '" data-en="' + (!k.enabled) + '">' + (k.enabled ? '禁用' : '启用') + '</button>' +
            '<button class="btn btn-sm btn-danger" data-act="keys/remove" data-id="' + fmt.esc(k.id) + '" data-prov="' + fmt.esc(p.id) + '">删除</button></div>' +
            '<div class="muted small mt">添加于 ' + fmt.esc(String(k.added_at || '').replace('T', ' ').slice(0, 16)) + ' · 配额查询于 ' + fmt.esc(String(k.usage_at || '-').replace('T', ' ').slice(0, 16)) + '</div></div>';
        }).join('');
        const log = (kd.switch_log || []).slice(-5).reverse().map((x) => Object.assign({}, x, { _at: tm(x.at), _ok: x.ok ? ui.badge('成功', 'ok') : ui.badge('失败', 'bad') }));
        host.innerHTML = (rows || '<div class="muted small">暂无密钥</div>') +
          '<div class="row mt" style="gap:6px">' +
          '<button class="btn btn-sm btn-ok" data-addkey="' + fmt.esc(p.id) + '">' + icon('plus', { size: 12 }) + ' 添加密钥</button>' +
          '<button class="btn btn-sm" data-refreshkey="' + fmt.esc(p.id) + '">' + icon('refresh', { size: 12 }) + ' 刷新全部配额</button></div>' +
          (log.length ? '<div class="muted small mt mb">最近切换记录</div>' + T([['_at', '时间'], ['to_masked', '切换到', 'mono'], ['_ok', '结果'], ['detail', '详情', 'small']], log) : '');
        host.querySelectorAll('[data-addkey]').forEach((b) => (b.onclick = () => addKey(p.id)));
        host.querySelectorAll('[data-refreshkey]').forEach((b) => (b.onclick = () => act('keys/refresh', { provider: p.id }, b)));
        host.querySelectorAll('[data-act]').forEach((b) => (b.onclick = () => keyAct(b.dataset.act, { id: b.dataset.id, provider: b.dataset.prov, enabled: b.dataset.en === 'true' }, b)));
      }));
    };

    return paint;
  }

  /* ============ 7. 套餐真实命中率 ============ */
  const HR_RANGES = [['1h', '近 1 小时'], ['6h', '近 6 小时'], ['24h', '近 24 小时'], ['7d', '近 7 天'], ['all', '全部']];
  const HR_RATE_CLS = (v) => (v == null ? 'gray' : v >= 99 ? 'ok' : v >= 95 ? 'warn' : 'bad');

  function tabHitrate(box, d) {
    box.innerHTML =
      '<div class="range-btns row mb" style="gap:8px">' +
      '<span class="muted small">统计窗口</span>' +
      HR_RANGES.map(([k, l]) => '<button class="btn btn-sm" data-hr="' + k + '">' + l + '</button>').join('') +
      '<span style="flex:1"></span><span class="muted small" id="hr-updated">连接中…</span>' +
      '<span class="muted small hide-sm">真实命中率 = 2xx ÷（2xx + 套餐/上游拒绝）</span>' +
      '</div>' +
      liveStats([['hr_rate', '真实命中率'], ['hr_calls', '有效请求'], ['hr_miss', '未命中(套餐原因)'], ['hr_client', '客户端错误(不计入)']]) +
      '<div class="grid g2">' +
      section('各套餐命中率 <span class="muted small" id="hr-label"></span>', '<div id="hr-cards"></div>') +
      section('命中率趋势 <span class="muted small" id="hr-gran"></span>', '<div class="chart-box lg"><canvas id="hr-trend"></canvas></div>') +
      '</div>' +
      section('套餐明细 <span class="muted small" id="hr-sub"></span>', '<div id="hr-table"></div>') +
      section('口径说明', '<div class="muted small" style="line-height:1.95">' +
        '· <b>命中</b>：上游返回 2xx，真正拿到了模型响应。<br>' +
        '· <b>未命中（套餐/上游原因）</b>：<span class="mono">402</span> 额度耗尽、<span class="mono">403</span> 权限或密钥失效、<span class="mono">429</span> 限流、<span class="mono">5xx</span> 上游故障、<span class="mono">0</span> 无响应 —— 这些才算"套餐没干活"。<br>' +
        '· <b>不计入分母</b>：<span class="mono">400/401/404</span> 等客户端自身错误与 <span class="mono">499</span> 客户端中断，单独列出，避免把用户侧问题算到套餐头上。<br>' +
        '· <b>真实命中率</b> = 命中 ÷（命中 + 未命中）；表中"总成功率" = 命中 ÷ 全部请求，口径更保守，两者并列便于对照。<br>' +
        '· 数据来自 nginx 访问日志（含 <span class="mono">$upstream_status</span>）写入的 <span class="mono">relay.db</span>，套餐按 route 区分：go=OpenCode Go、zen=OpenCode Zen、glm=GLM Coding Plan、mimo=MiMo Token Plan。');

    const R = {};
    ['hr_rate', 'hr_calls', 'hr_miss', 'hr_client'].forEach((k) => { R[k] = live(box, k); });
    let range = '24h';
    let H = null;
    let inflight = false;   /* 上一次请求未返回时跳过本拍 */
    let chartSig = null;    /* 数据没变不重建图表 */

    const missChips = (p) => {
      const chips = [['402 额度', p.e402], ['403 权限', p.e403], ['429 限流', p.e429], ['5xx', p.e5], ['无响应', p.e0]]
        .filter(([, v]) => v > 0)
        .map(([l, v]) => '<span class="chip">' + l + ' ' + num(v) + '</span>').join(' ');
      return chips || '<span class="muted">无未命中</span>';
    };

    const paint = () => {
      if (!H) return;
      const t = H.totals || {};
      const cur = HR_RANGES.find((x) => x[0] === range) || HR_RANGES[2];
      const gran = (range === '7d' || range === 'all') ? '按天' : '按小时';
      setText(box.querySelector('#hr-label'), cur[1]);
      setText(box.querySelector('#hr-gran'), cur[1] + ' · ' + gran);
      setText(box.querySelector('#hr-sub'), cur[1] + ' · 共 ' + num(t.calls || 0) + ' 次请求 · 有效 ' + num(t.denominator || 0) + ' 次');

      setNum(R.hr_rate.v, Number(t.hitRate || 0), (x) => x.toFixed(2) + '%');
      setText(R.hr_rate.s, '总成功率 ' + (t.rawSuccessRate == null ? '-' : pct1(t.rawSuccessRate)) + ' · 重试 ' + num(t.retried));
      setNum(R.hr_calls.v, Number(t.denominator || 0));
      setText(R.hr_calls.s, '命中 ' + num(t.hit) + ' · 未命中 ' + num(t.miss));
      setNum(R.hr_miss.v, Number(t.miss || 0));
      setText(R.hr_miss.s, '有效请求中未拿到响应的次数');
      setNum(R.hr_client.v, Number((t.client4xx || 0) + (t.abort || 0)));
      setText(R.hr_client.s, '4xx ' + num(t.client4xx) + ' · 499 中断 ' + num(t.abort));

      slot(box, 'hr-cards', (H.plans || []).length
        ? '<div class="grid g2" style="gap:9px">' + H.plans.map((p) => {
          const rate = p.hitRate == null ? '-' : p.hitRate.toFixed(2) + '%';
          const color = p.hitRate == null ? 'var(--muted)'
            : p.hitRate >= 99 ? 'var(--ok)' : p.hitRate >= 95 ? 'var(--warn)' : '#f87171';
          return `<div class="card" style="padding:11px 12px">
              <div class="row between"><span class="mono">${fmt.esc(p.label)}</span>
                <span class="mono" style="font-size:16.5px;color:${color}">${rate}</span></div>
              <div class="bar" style="margin-top:8px"><div style="width:${Math.min(100, p.hitRate || 0)}%;background:${color}"></div></div>
              <div class="muted small" style="margin-top:9px;line-height:1.85">
                请求 ${num(p.calls)} · 命中 ${num(p.hit)} · 未命中 ${num(p.miss)} · 客户端4xx ${num(p.client4xx)}<br>
                ${missChips(p)}
              </div></div>`;
        }).join('') + '</div>'
        : '<div class="muted small">该窗口内没有请求记录</div>', sigOf(H.plans));

      const cs = sigOf([H.buckets, H.series, range]);
      if (cs !== chartSig) {
        chartSig = cs;
        lineChart('hr-trend', H.buckets || [], (H.series || []).map((s) => ({ label: s.label, data: s.points, color: s.color })));
      }

      slot(box, 'hr-table', (H.plans || []).length ? T([
        ['label', '套餐 / 线路', 'mono'],
        ['calls', '请求', 'num'],
        ['hit', '命中', 'num'],
        ['miss', '未命中', 'num'],
        ['e402', '402 额度', 'num'],
        ['e403', '403 权限', 'num'],
        ['e429', '429 限流', 'num'],
        ['e5', '5xx', 'num'],
        ['e0', '无响应', 'num'],
        ['client4xx', '客户端4xx', 'num'],
        ['abort', '499 中断', 'num'],
        ['retried', '重试', 'num'],
        ['rawSuccessRate', '总成功率', 'num', (v) => (v == null ? '<span class="muted">-</span>' : pct1(v))],
        ['hitRate', '真实命中率', 'num', (v) => ui.badge(v == null ? '-' : v.toFixed(2) + '%', HR_RATE_CLS(v))],
        ['avgRt', '均延迟', 'num', (v) => (v == null ? '<span class="muted">-</span>' : Number(v).toFixed(2) + 's')],
        ['avgTtfb', 'TTFB', 'num', (v) => (v == null ? '<span class="muted">-</span>' : Number(v).toFixed(2) + 's')],
        ['_cov', '模型解析率', 'num'],
      ], (H.plans || []).map((p) => Object.assign({}, p, { _cov: pct1(p.modelCoverage) })), 'max-height:44vh')
        : '<div class="muted small">无数据</div>', sigOf(H.plans));
    };

    const pull = async () => {
      if (inflight) return;
      inflight = true;
      try {
        /* 无缓存轮询，保证 2 秒实时 */
        H = await CM.api.get('/relayops/hitrate?range=' + encodeURIComponent(range));
      } catch (e) {
        H = null;
        setText(box.querySelector('#hr-updated'), '<span style="color:var(--bad)">●</span> 更新失败: ' + fmt.esc(e.message));
        setText(box.querySelector('#hr-sub'), '加载失败: ' + e.message);
        return;
      } finally {
        inflight = false;
      }
      setText(box.querySelector('#hr-updated'),
        '<span style="color:var(--ok)">●</span> 更新于 ' +
        new Date().toLocaleTimeString('zh-CN', { hour12: false }) + ' · 每 2 秒');
      paint();
    };

    box.querySelectorAll('[data-hr]').forEach((b) => (b.onclick = () => {
      range = b.dataset.hr;
      box.querySelectorAll('[data-hr]').forEach((x) => { x.classList.toggle('active', x === b); x.classList.toggle('btn-primary', x === b); });
      pull();
    }));
    box.querySelector('[data-hr="24h"]').classList.add('active', 'btn-primary');

    return pull;   /* 交给 render() 周期性调用（每 10 秒原地刷新） */
  }

  /* ============ 8. Token 命中率（会话维度，双口径） ============ */
  function tabTokenHit(box, d) {
    box.innerHTML =
      '<div class="range-btns row mb" style="gap:8px">' +
      '<span class="muted small">统计窗口</span>' +
      HR_RANGES.map(([k, l]) => '<button class="btn btn-sm" data-tkh="' + k + '">' + l + '</button>').join('') +
      '<span style="flex:1"></span>' +
      '<span class="muted small" id="tkh-updated">连接中…</span>' +
      '<span class="muted small hide-sm">复用率(估算) vs 真实缓存命中率(厂商)</span>' +
      '</div>' +
      liveStats([['tkh_reuse', '上下文复用率(估算)'], ['tkh_vendor', '真实缓存命中率'], ['tkh_bytes', '可复用上下文'], ['tkh_gap', '两者差距']]) +
      '<div class="grid g2">' +
      section('会话上下文构成 <span class="muted small" id="tkh-mixlabel"></span>', '<div class="chart-box lg"><canvas id="tkh-mix"></canvas></div>') +
      section('真实缓存命中率趋势 <span class="muted small" id="tkh-vlabel"></span>', '<div class="chart-box lg"><canvas id="tkh-vendor"></canvas></div>') +
      '</div>' +
      section('会话排行（按上下文字节） <span class="muted small" id="tkh-toplabel"></span>', '<div id="tkh-top"></div>') +
      '<div class="grid g2">' +
      section('按模型：复用率 vs 真实命中率', '<div id="tkh-models"></div>') +
      section('口径说明', '<div class="muted small" style="line-height:1.95">' +
        '· <b>上下文复用率（估算）</b>：编码代理每轮都会把整个会话上下文重发一遍。第 N 轮请求体里至少有 <span class="mono">min(上一轮字节, 本轮字节)</span> 是前面轮次已经发过的内容 —— 这就是缓存<b>本该</b>命中的上限。数据来自 <span class="mono">session_turns.sent_bytes</span>，不依赖厂商。<br>' +
        '· <b>真实缓存命中率（厂商上报）</b>：上游响应 usage 里的 <span class="mono">cache_read</span>（Anthropic 的 cache_read_input_tokens / OpenAI 的 prompt_tokens_details.cached_tokens）÷（未命中输入 + cache_read），与采集端口径一致。<br>' +
        '· <b>上报覆盖率</b>：多少比例的事件真的带了缓存字段。覆盖率为 0 时，"真实命中率 0%" 表示<b>厂商没上报</b>，不能直接断定没有命中。<br>' +
        '· 两个数字差距越大，说明"本可缓存的上下文正按全价被重复计费"。') +
      '</div>';

    const R = {};
    ['tkh_reuse', 'tkh_vendor', 'tkh_bytes', 'tkh_gap'].forEach((k) => { R[k] = live(box, k); });
    let range = '24h';
    let H = null;
    let inflight = false;   /* 上一次请求未返回时跳过本拍，避免堆积 */
    let chartSig = null;    /* 数据没变就不重建图表（省掉 Chart.js 的重排） */
    const rateBadge = (v) => ui.badge(v == null ? '-' : Number(v).toFixed(2) + '%', v == null ? 'gray' : v >= 60 ? 'ok' : v >= 25 ? 'warn' : 'bad');

    const paint = () => {
      if (!H) return;
      const ru = H.reuse || {}, ve = H.vendor || {};
      const cur = HR_RANGES.find((x) => x[0] === range) || HR_RANGES[2];
      const gran = '按天';
      setText(box.querySelector('#tkh-mixlabel'), cur[1] + ' · ' + gran + ' · 可复用 vs 新增');
      setText(box.querySelector('#tkh-vlabel'), cur[1] + ' · ' + gran);
      setText(box.querySelector('#tkh-toplabel'),
        cur[1] + ' · ' + num(ru.sessions || 0) + ' 个会话 / ' + num(ru.turns || 0) + ' 轮 · 合计 ' + fmt.bytes(ru.totalBytes || 0));
      /* 实时指示：绿点 + 本次刷新时间 + 近 5 分钟活动（每次刷新会跳动） */
      const lv = H.live || {};
      setText(box.querySelector('#tkh-updated'),
        '<span style="color:var(--ok)">●</span> 更新于 ' +
        new Date().toLocaleTimeString('zh-CN', { hour12: false }) +
        ' · 近5分钟 <b class="mono">' + num(lv.turns || 0) + '</b> 轮 / ' +
        '<b class="mono">' + fmt.bytes(lv.bytes || 0) + '</b> / <b class="mono">' + num(lv.events || 0) + '</b> 事件');

      setNum(R.tkh_reuse.v, Number(ru.reuseRate || 0), (x) => x.toFixed(2) + '%');
      setText(R.tkh_reuse.s, '多轮会话平均 ' + (ru.avgSessionReuse == null ? '-' : pct1(ru.avgSessionReuse)) +
        ' · 多轮 ' + num(ru.multiTurnSessions) + ' / 单轮 ' + num(ru.singleTurnSessions));
      setNum(R.tkh_vendor.v, Number(ve.hitRate || 0), (x) => x.toFixed(2) + '%');
      setText(R.tkh_vendor.s, '命中 ' + num(ve.cacheRead) + ' / 未命中 ' + num(ve.inputNew) + ' tok · 覆盖率 ' + (ve.coveragePct == null ? '-' : pct1(ve.coveragePct)));
      setNum(R.tkh_bytes.v, Number(ru.reuseBytes || 0), (x) => fmt.bytes(x));
      setText(R.tkh_bytes.s, '总 ' + fmt.bytes(ru.totalBytes || 0) + ' · 新增 ' + fmt.bytes(ru.newBytes || 0));
      if (H.gap == null) setText(R.tkh_gap.v, '-');
      else setNum(R.tkh_gap.v, Number(H.gap), (x) => x.toFixed(2) + ' pp');
      setText(R.tkh_gap.s, '复用率 − 真实命中率（差距越大越亏）');

      const days = ru.byDay || [];
      const vd = ve.byDay || [];
      const cs = sigOf([days, vd, range]);
      if (cs !== chartSig) {
        chartSig = cs;
        barChart('tkh-mix', days.map((x) => x.day), [
          { label: '可复用(缓存上限)', data: days.map((x) => x.reuseBytes), color: 'rgba(34,197,94,.55)', stack: true },
          { label: '新增', data: days.map((x) => x.totalBytes - x.reuseBytes), color: 'rgba(122,136,153,.4)', stack: true },
        ]);
        lineChart('tkh-vendor', vd.map((x) => x.day), [
          { label: '真实缓存命中率', data: vd.map((x) => x.hitRate), color: '#8b5cf6' },
        ]);
      }

      slot(box, 'tkh-top', (ru.top || []).length ? T([
        ['sess', '会话', 'mono small'],
        ['route', '线路', 'mono'],
        ['turns', '轮数', 'num'],
        ['totalBytes', '上下文总量', 'num', (v) => fmt.bytes(v)],
        ['reuseBytes', '可复用', 'num', (v) => fmt.bytes(v)],
        ['newBytes', '新增', 'num', (v) => fmt.bytes(v)],
        ['reuseRate', '复用率', 'num', (v) => rateBadge(v)],
        ['avgPerTurn', '均/轮', 'num', (v) => fmt.bytes(v)],
        ['maxTurn', '峰值轮', 'num', (v) => fmt.bytes(v)],
        ['models', '模型', 'small wrap'],
        ['lastDay', '最近', 'mono small'],
      ], ru.top, 'max-height:46vh') : '<div class="muted small">该窗口内没有会话数据</div>', sigOf(ru.top));

      const vByModel = new Map((ve.byModel || []).map((m) => [m.model, m]));
      const mrows = (ru.byModel || []).map((m) => {
        const v = vByModel.get(m.model) || {};
        return Object.assign({}, m, { vendorHit: v.hitRate == null ? null : v.hitRate, cacheRead: v.cacheRead || 0 });
      });
      slot(box, 'tkh-models', mrows.length ? T([
        ['model', '模型', 'mono'],
        ['sessions', '会话', 'num'],
        ['turns', '轮数', 'num'],
        ['totalBytes', '上下文总量', 'num', (v) => fmt.bytes(v)],
        ['reuseBytes', '可复用', 'num', (v) => fmt.bytes(v)],
        ['reuseRate', '复用率', 'num', (v) => rateBadge(v)],
        ['vendorHit', '真实命中率', 'num', (v) => (v == null ? '<span class="muted">未上报</span>' : pct1(v))],
        ['cacheRead', '缓存读 tok', 'num'],
      ], mrows, 'max-height:40vh') : '<div class="muted small">无数据</div>', sigOf(mrows));
    };

    const pull = async () => {
      if (inflight) return;              /* 上一次还没返回：跳过本拍，防止请求越堆越多 */
      inflight = true;
      try {
        /* 不带缓存：每次轮询都拿最新数据（带缓存会把 2 秒实时退化成 5 秒） */
        H = await CM.api.get('/relayops/tokenhit?range=' + encodeURIComponent(range));
      } catch (e) {
        H = null;
        setText(box.querySelector('#tkh-updated'), '<span style="color:var(--bad)">●</span> 更新失败: ' + fmt.esc(e.message));
        setText(box.querySelector('#tkh-toplabel'), '加载失败: ' + e.message);
        return;
      } finally {
        inflight = false;
      }
      paint();
    };

    box.querySelectorAll('[data-tkh]').forEach((b) => (b.onclick = () => {
      range = b.dataset.tkh;
      box.querySelectorAll('[data-tkh]').forEach((x) => { x.classList.toggle('active', x === b); x.classList.toggle('btn-primary', x === b); });
      pull();
    }));
    box.querySelector('[data-tkh="24h"]').classList.add('active', 'btn-primary');

    return pull;
  }

  /* ============ actions ============ */
  async function addKey(provId) {
    const r = await modal.prompt('添加密钥 - ' + provId, [
      { name: 'key', label: '密钥', placeholder: 'oc_sk_... / glm-...', mono: true },
      { name: 'label', label: '备注', placeholder: '可选' },
    ], { okText: '添加' });
    if (!r || !r.key) return;
    try { const out = await CM.api.post('/relay/action', { name: 'keys/add', payload: { key: r.key, label: r.label, provider: provId } }); window.toast(out.detail || out.msg || '已添加', 'ok'); refreshNow(); }
    catch (e) { window.toast(e.message, 'err'); }
  }

  async function keyAct(name, payload, btn) {
    if (name === 'keys/remove') {
      const okc = await modal.confirm('删除密钥', '删除后无法恢复，确认删除该密钥？', { danger: true, okText: '删除' });
      if (!okc) return;
    }
    if (btn) ui.busy(btn, true);
    try { const out = await CM.api.post('/relay/action', { name, payload }); window.toast(out.detail || out.msg || '操作完成', 'ok'); refreshNow(); }
    catch (e) { window.toast(e.message, 'err'); }
    if (btn) ui.busy(btn, false);
  }

  async function act(name, payload, btn, confirmFirst) {
    if (confirmFirst) {
      const okc = await modal.confirm('确认执行', '执行 <b>' + fmt.esc(name) + '</b>？', { okText: '执行' });
      if (!okc) return;
    }
    if (btn) ui.busy(btn, true);
    try { const out = await CM.api.post('/relay/action', { name, payload }); window.toast(out.detail || out.msg || out.status || '操作完成', 'ok'); refreshNow(); }
    catch (e) { window.toast(e.message, 'err'); }
    if (btn) ui.busy(btn, false);
  }

  /* 操作后立即原地刷新（会重新拉数据并原地更新，不重建 DOM） */
  function refreshNow() {
    render();
  }
})();
