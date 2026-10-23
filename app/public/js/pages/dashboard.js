/* Dashboard page - smooth, non-replaying live updates */
(function () {
  'use strict';

  let charts = null, sparks = null, cards = null, histChart = null;
  let histRange = '1h';
  let histGen = 0;            /* loadHistory 请求代次，丢弃过期响应 */
  let histLastTs = 0;        /* 历史图已入库的最新时间戳（毫秒），用于去重 */
  const histLive = [];      /* 已收到但可能尚未并入图表的实时点（处理历史请求晚返回的竞态） */
  const histCache = {};
  let lastDisks = null;
  let flashTimer = null;

  function cardStat(label, ic, key) {
    return `<div class="card stat" data-card="${key}">
      <div class="stat-top">
        <div class="stat-label">${label}</div>
        <div class="stat-icon">${icon(ic, { size: 16 })}</div>
      </div>
      <div class="stat-value" data-v>—</div>
      <div class="stat-sub" data-s></div>
      <div class="bar" data-bar><div style="width:0%"></div></div>
      <div class="stat-spark"><canvas class="spark"></canvas></div>
    </div>`;
  }

  registerPage({
    id: 'dashboard',
    title: '仪表盘',
    desc: '服务器与中继的实时运行状态总览',
    async mount(root) {
      root.innerHTML = `
      <div class="card" id="dash-head" style="padding:14px 16px">
        <div class="row between">
          <div class="row" style="gap:18px">
            <div><span class="muted small">主机</span> <b class="mono" id="dh-host">—</b></div>
            <div class="hide-sm"><span class="muted small">系统</span> <span class="mono small" id="dh-os">—</span></div>
            <div class="hide-sm"><span class="muted small">内核</span> <span class="mono small" id="dh-kernel">—</span></div>
            <div><span class="muted small">CPU</span> <span class="mono small" id="dh-cpu">—</span></div>
            <div class="hide-sm"><span class="muted small">公网IP</span> <span class="mono small" id="dh-ip">—</span></div>
          </div>
          <div class="row"><span class="muted small hide-sm">实时刷新</span><span class="badge info" id="dh-rate">—</span></div>
        </div>
      </div>

      <div class="grid g4 mb">
        ${cardStat('CPU 使用率', 'cpu', 'cpu')}
        ${cardStat('内存使用率', 'memory', 'mem')}
        ${cardStat('根分区', 'disk', 'disk')}
        ${cardStat('网络流量', 'activity', 'net')}
      </div>

      <div class="grid g2 mb">
        <div class="card">
          <div class="card-title">系统健康 <div class="right"><span class="muted small" id="dh-health-time"></span></div></div>
          <div class="row" id="dh-health"><span class="muted small">检查中</span></div>
        </div>
        <div class="card">
          <div class="card-title">OpenCode 中继 <div class="right"><a class="btn btn-sm" href="#/relay">详情</a></div></div>
          <div class="row" id="dh-relay"><span class="muted small">加载中</span></div>
        </div>
      </div>

      <div class="grid g-2-1">
        <div>
          <div class="card">
            <div class="card-title">历史趋势 <span class="muted small" id="dh-hist-note"></span>
              <div class="right range-btns">
                ${['10m:10分钟', '1h:1小时', '6h:6小时', '24h:24小时'].map((r) => {
                  const [v, l] = r.split(':');
                  return `<button class="btn btn-sm ${v === histRange ? 'active' : ''}" data-hr="${v}">${l}</button>`;
                }).join('')}
              </div>
            </div>
            <div class="chart-box lg"><canvas id="ch-hist"></canvas></div>
          </div>
          <div class="card">
            <div class="card-title">CPU 使用率 % <span class="right muted small" id="dash-load"></span></div>
            <div class="chart-box"><canvas id="ch-cpu"></canvas></div>
          </div>
          <div class="grid g2" style="gap:0">
            <div class="card" style="margin-right:7px">
              <div class="card-title">内存 / 交换 (MB)</div>
              <div class="chart-box"><canvas id="ch-mem"></canvas></div>
            </div>
            <div class="card" style="margin-left:7px">
              <div class="card-title">网络吞吐 (KB/s)</div>
              <div class="chart-box"><canvas id="ch-net"></canvas></div>
            </div>
          </div>
        </div>
        <div>
          <div class="card">
            <div class="card-title">磁盘用量</div>
            <div id="dash-disks">${ui.skeleton(3)}</div>
          </div>
          <div class="card">
            <div class="card-title">CPU 占用 Top</div>
            <div id="dash-top">${ui.skeleton(5)}</div>
          </div>
          <div class="card">
            <div class="card-title">系统状态</div>
            <dl class="kv" id="dash-sys"><dt>运行时间</dt><dd>—</dd><dt>进程</dt><dd>—</dd><dt>负载</dt><dd>—</dd></dl>
          </div>
        </div>
      </div>`;

      /* persistent references: values are updated in place, never rebuilt */
      cards = {};
      for (const key of ['cpu', 'mem', 'disk', 'net']) {
        const el = root.querySelector(`[data-card="${key}"]`);
        cards[key] = { val: el.querySelector('[data-v]'), sub: el.querySelector('[data-s]'), bar: el.querySelector('[data-bar]') };
      }

      const sparkEls = root.querySelectorAll('canvas.spark');
      sparks = {
        cpu: ui.spark(sparkEls[0], '#7a9bff', 100),
        mem: ui.spark(sparkEls[1], '#8b5cf6', 100),
        disk: ui.spark(sparkEls[2], '#f59e0b', 100),
        net: ui.spark(sparkEls[3], '#22c55e', 0),
      };

      charts = {
        cpu: ui.chart(document.getElementById('ch-cpu'), {
          series: [{ label: '总 CPU', color: '#4370f0', fill: true }],
          suggestedMax: 100, yFormat: (v) => v + '%', points: 300,
        }),
        mem: ui.chart(document.getElementById('ch-mem'), {
          series: [{ label: '内存', color: '#8b5cf6', fill: true }, { label: '交换', color: '#f59e0b' }],
          yFormat: (v) => fmt.bytes(v * 1048576).replace(' ', ''), points: 300,
        }),
        net: ui.chart(document.getElementById('ch-net'), {
          series: [{ label: '下行', color: '#22c55e', fill: true }, { label: '上行', color: '#38bdf8' }],
          yFormat: (v) => v + 'K', points: 300,
        }),
      };

      histChart = ui.chart(document.getElementById('ch-hist'), {
        series: [
          { label: 'CPU%', color: '#4370f0', fill: true },
          { label: '内存%', color: '#8b5cf6' },
          { label: '磁盘%', color: '#f59e0b' },
        ],
        suggestedMax: 100, yFormat: (v) => v + '%', points: 1500,
      });
      loadHistory(histRange);
      root.querySelectorAll('[data-hr]').forEach((b) => {
        b.onclick = () => {
          histRange = b.dataset.hr;
          histReset();                 /* 清空数据源与去重基准，作废在途请求 */
          root.querySelectorAll('[data-hr]').forEach((x) => x.classList.toggle('active', x === b));
          updateHistNote();
          loadHistory(histRange);
        };
      });

      /* initial snapshot */
      try {
        const ov = await CM.api.get('/overview');
        const info = ov.info;
        document.getElementById('dh-host').textContent = info.hostname;
        document.getElementById('dh-os').textContent = info.osName;
        document.getElementById('dh-kernel').textContent = info.kernel;
        document.getElementById('dh-cpu').textContent = `${info.cpuModel.replace(/Intel\(R\)\s*/, '').replace(/CPU|@/g, '').trim()} × ${info.cores}`;
        document.getElementById('dh-ip').textContent = info.publicIp || '—';
        document.getElementById('dh-rate').textContent = (CM.me && CM.me.refreshMs ? CM.me.refreshMs : 2000) + ' ms';
        if (ov.disks) { lastDisks = ov.disks; renderDisks(ov.disks); }
        renderHealth(ov);
        renderRelayCard();
      } catch (e) { console.error(e); }

      if (!CM._dashSubscribed) {
        CM._dashSubscribed = true;
        CM.hub.on('metrics', (msg) => { if (CM.router.current && CM.router.current.id === 'dashboard') onMetrics(msg); });
      }
    },
    show() {
      /* container is cached between switches: re-measure canvases */
      if (charts) { charts.cpu.resize(); charts.mem.resize(); charts.net.resize(); }
      if (histChart) histChart.resize();
      if (sparks) { sparks.cpu.redraw(); sparks.mem.redraw(); sparks.disk.redraw(); sparks.net.redraw(); }
    },
    hide() {
      /* keep charts/sparks alive for instant revisit; only stop live updates
         (onMetrics is already gated on the current page) */
    },
  });

  /* batched history replay: one redraw per chart instead of N */
  CM.applyMetricsBatch = function (points) {
    if (!charts || !points || !points.length) return;
    const labels = [], cpuV = [], memV = [], swapV = [], netRx = [], netTx = [];
    const sparkCpu = [], sparkMem = [], sparkNet = [];
    for (const m of points) {
      labels.push(fmt.ts(m.ts));
      const cpuPct = m.cpu.totalPct || 0;
      const memUsed = m.memory.total ? (m.memory.total - m.memory.available) / 1048576 : 0;
      let rx = 0, tx = 0;
      for (const [name, v] of Object.entries(m.net.ifaces)) { if (name !== 'lo') { rx += v.rxBps; tx += v.txBps; } }
      cpuV.push(cpuPct);
      memV.push(memUsed);
      swapV.push(m.memory.swapTotal ? m.memory.swapUsed / 1048576 : 0);
      netRx.push(rx / 1024);
      netTx.push(tx / 1024);
      sparkCpu.push(cpuPct);
      sparkMem.push(m.memory.actualUsedPct);
      sparkNet.push((rx + tx) / 1024);
    }
    charts.cpu.pushBatch(labels, [cpuV], true);
    charts.mem.pushBatch(labels, [memV, swapV], true);
    charts.net.pushBatch(labels, [netRx, netTx], true);
    if (sparks) { sparks.cpu.pushBatch(sparkCpu); sparks.mem.pushBatch(sparkMem); sparks.net.pushBatch(sparkNet); }
    updateCards(points[points.length - 1], true);
  };

  /* ============ helpers ============ */
  function setBar(key, pct, kind) {
    if (!cards || !cards[key]) return;
    const inner = cards[key].bar.firstElementChild;
    const next = Math.max(0, Math.min(100, pct || 0));
    /* avoid pointless reflow churn on negligible changes */
    if (Math.abs((parseFloat(inner.dataset.w) || 0) - next) < 0.2) return;
    inner.dataset.w = next;
    inner.style.width = next + '%';
    cards[key].bar.className = 'bar ' + (kind || fmt.pctColor(next));
  }

  function flashValue(el) {
    if (flashTimer) return;
    el.style.animation = 'flashWarn 1.2s ease';
    flashTimer = setTimeout(() => { el.style.animation = ''; flashTimer = null; }, 4000);
  }

  const HIST_MAX = 1500;
  const HIST_STEP_TARGET = 400;                 /* 历史降采样目标点数，给实时点留出滚动余量 */

  const histLabel = (ts) => new Date(ts).toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' });

  /* 图表当前展示的数据源（= 历史 + 已收到的实时点），单一真相，避免两路互相覆盖 */
  const histS = { labels: [], v: [[], [], []] };

  function histTrim() {
    const over = histS.labels.length - HIST_MAX;
    if (over <= 0) return;
    histS.labels.splice(0, over);
    histS.v.forEach((a) => a.splice(0, over));
  }

  function histRender(animate) {
    if (!histChart) return;
    const c = histChart.chart;
    histTrim();
    c.data.labels = histS.labels;
    histS.v.forEach((vals, i) => { c.data.datasets[i].data = vals; });
    c.update(animate ? undefined : 'none');
    updateHistNote();
  }

  function updateHistNote() {
    const note = document.getElementById('dh-hist-note');
    if (!note) return;
    const n = histS.labels.length;
    note.textContent = n ? `${n} 个点 · 实时滚动中` : '无采样点';
  }

  function histReset() {
    histGen++;                 /* 作废在途的历史请求 */
    histLastTs = 0;            /* 重置去重基准 */
    histLive.length = 0;
    histS.labels.length = 0;
    histS.v.forEach((a) => { a.length = 0; });
  }

  async function loadHistory(range) {
    if (!histChart) return;
    const gen = ++histGen;                    /* 防止旧请求回来覆盖新范围 */
    try {
      let d;
      if (histCache[range] && Date.now() - histCache[range].ts < 30000) d = histCache[range].data;
      else { d = await CM.api.get('/history?range=' + range); histCache[range] = { ts: Date.now(), data: d }; }
      if (gen !== histGen || !histChart) return;
      const step = Math.max(1, Math.floor(d.points.length / HIST_STEP_TARGET));
      histS.labels.length = 0;
      histS.v.forEach((a) => { a.length = 0; });
      for (let i = 0; i < d.points.length; i += step) {
        const p = d.points[i];
        histS.labels.push(histLabel(p.t * 1000));
        histS.v[0].push(p.c); histS.v[1].push(p.m); histS.v[2].push(p.d);
      }
      /* 历史基准时间戳：其后到达的实时点才会被追加，避免与历史尾点重复 */
      const pts = d.points;
      const lastT = pts.length ? pts[pts.length - 1].t * 1000 : 0;
      /* 实时点可能先于本请求返回而到达，此时把它们接在历史之后 */
      for (const q of histLive) {
        if (q.ts > lastT) { histS.labels.push(q.label); histS.v[0].push(q.c); histS.v[1].push(q.m); histS.v[2].push(q.d); }
      }
      histLastTs = Math.max(lastT, histLive.length ? histLive[histLive.length - 1].ts : 0);
      histRender(true);                       /* 切换范围时做一次带动画的整体重绘 */
    } catch (e) { console.error(e); }
  }

  /* 实时把新采样点追加进历史趋势图：只往 histS 追加，再由 histRender 同步到图表。
   注意 c.data.labels 与 histS.labels 是同一个数组引用，绝不能两边都 push。 */
  function pushHistory(m) {
    if (!histChart || !m || !m.ts) return;
    const ts = typeof m.ts === 'number' ? m.ts : Date.parse(m.ts);
    if (!ts || ts <= histLastTs) return;               /* 去重：跳过重复/倒序的采样点 */
    histLastTs = ts;
    const mounts = (lastDisks && lastDisks.mounts) || [];
    const rootM = mounts.find((x) => x.mount === '/') || mounts[0];
    const pt = {
      ts,
      label: histLabel(ts),
      c: +(m.cpu.totalPct || 0).toFixed(1),
      m: m.memory.total ? +(((m.memory.total - m.memory.available) / m.memory.total) * 100).toFixed(1) : 0,
      d: rootM ? +(+rootM.pct).toFixed(1) : 0,
    };
    histLive.push(pt);
    if (histLive.length > HIST_MAX) histLive.splice(0, histLive.length - HIST_MAX);

    histS.labels.push(pt.label);
    histS.v[0].push(pt.c); histS.v[1].push(pt.m); histS.v[2].push(pt.d);
    histRender(false);              /* 引用赋值 + update('none')，2s 一次，无动画、无闪烁 */
  }

  function onMetrics(msg) {
    if (!charts || !cards) return;
    const m = msg.data;
    const t = fmt.ts(m.ts);

    /* charts + sparklines (canvas, no DOM rebuild) */
    const cpuPct = m.cpu.totalPct || 0;
    const memUsed = m.memory.total ? (m.memory.total - m.memory.available) / 1048576 : 0;
    charts.cpu.push(t, [cpuPct]);
    charts.mem.push(t, [memUsed, m.memory.swapTotal ? m.memory.swapUsed / 1048576 : 0]);
    let rx = 0, tx = 0;
    for (const [name, v] of Object.entries(m.net.ifaces)) { if (name !== 'lo') { rx += v.rxBps; tx += v.txBps; } }
    charts.net.push(t, [rx / 1024, tx / 1024]);
    if (sparks) {
      sparks.cpu.push(cpuPct);
      sparks.mem.push(m.memory.actualUsedPct);
      sparks.net.push((rx + tx) / 1024);
    }

    if (msg.top) renderTop(msg.top);
    if (msg.disks) { lastDisks = msg.disks; renderDisks(msg.disks); }
    pushHistory(m);                          /* 历史趋势图实时滚动（放在 disks 之后，保证磁盘值是本拍的） */
    updateCards(m, rx, tx);
  }

  /* 出口带宽上限（bps）；未配置时沿用 1 Mbps 基准 */
  function netLimit() { return (CM.me && CM.me.netLimitBps) || 125000; }
  function netPct(m, rx, tx) { return Math.min(100, (rx + tx) / netLimit() * 100); }

  /* stat cards + kv panel: pure in-place updates (no DOM rebuild) */
  function updateCards(m, rxIn, txIn) {
    if (!cards) return;
    const cpuPct = m.cpu.totalPct || 0;
    let rx = rxIn, tx = txIn;
    if (rx === undefined) {
      rx = 0; tx = 0;
      for (const [name, v] of Object.entries(m.net.ifaces)) { if (name !== 'lo') { rx += v.rxBps; tx += v.txBps; } }
    }

    ui.animateNumber(cards.cpu.val, cpuPct, (v) => v.toFixed(1) + '%', 550, 0.15);
    cards.cpu.sub.textContent = `${(m.cpu.cores || []).filter((c) => c > 50).length} 核高负载 · ${m.cpu.coresCount || '?'} 核`;
    setBar('cpu', cpuPct);
    if (cpuPct >= 90) flashValue(cards.cpu.val);

    ui.animateNumber(cards.mem.val, m.memory.usedPct, (v) => v.toFixed(1) + '%', 550, 0.15);
    cards.mem.sub.textContent = `${fmt.bytes(m.memory.total - m.memory.available)} / ${fmt.bytes(m.memory.total)} · swap ${m.memory.swapPct.toFixed(0)}%`;
    setBar('mem', m.memory.usedPct);

    ui.animateNumber(cards.net.val, rx, (v) => fmt.bps(v), 450, 32);
    {
      const lim = netLimit();
      cards.net.sub.textContent = '上行 ' + fmt.bps(tx) +
        (lim > 125000 ? ' · 上限 ' + (lim / 1e6).toFixed(1) + ' Mbps' : ' · 基准 1 Mbps');
    }
    setBar('net', netPct(m, rx, tx), (rx + tx) > netLimit() * 0.8 ? 'warn' : 'ok');

    if (lastDisks) {
      const rootM = lastDisks.mounts.find((x) => x.mount === '/') || lastDisks.mounts[0];
      if (rootM) {
        ui.animateNumber(cards.disk.val, rootM.pct, (v) => v.toFixed(1) + '%', 550, 0.15);
        cards.disk.sub.textContent = `${fmt.bytes(rootM.used)} / ${fmt.bytes(rootM.total)} · 可用 ${fmt.bytes(rootM.avail)}`;
        setBar('disk', rootM.pct);
      }
    }

    const sysEl = document.getElementById('dash-sys');
    if (sysEl) {
      sysEl.innerHTML = `
        <dt>运行时间</dt><dd>${fmt.uptime(m.cpu.uptimeSec)}</dd>
        <dt>进程</dt><dd>${m.cpu.procs ? m.cpu.procs.running + ' / ' + m.cpu.procs.total : '—'}</dd>
        <dt>负载</dt><dd>${(m.cpu.loadAvg || []).join(' / ')}</dd>
        <dt>磁盘 I/O</dt><dd>${fmt.bps(m.diskio.readBps)} 读 · ${fmt.bps(m.diskio.writeBps)} 写</dd>`;
    }
  }

  async function renderRelayCard() {
    const el = document.getElementById('dh-relay');
    if (!el) return;
    try {
      const [s, h, p] = await Promise.all([
        CM.api.get('/relay/summary', 5000),
        CM.api.get('/relay/health', 5000),
        CM.api.get('/relay/providers', 5000),
      ]);
      const providers = (p.providers || []);
      const badProv = providers.filter((x) => x.exhausted);
      el.innerHTML = `
        ${ui.badge('采集器 ' + (h.ok === false ? '异常' : '运行中'), h.ok === false ? 'bad' : 'ok')}
        ${ui.badge('今日 ' + (s.requests_today ?? 0) + ' 请求', 'info')}
        ${ui.badge('成功率 ' + (s.success_rate ?? 0).toFixed(1) + '%', (s.success_rate ?? 100) >= 95 ? 'ok' : 'warn')}
        ${ui.badge('P95 ' + (s.p95_latency ?? 0).toFixed(0) + 'ms', 'gray')}
        ${providers.map((x) => ui.badge((x.label || x.id) + ' ' + (x.exhausted ? '耗尽' : '可用'), x.exhausted ? 'bad' : 'ok')).join('')}
        ${badProv.length ? `<span class="row small" style="gap:5px;color:#f87171">${icon('warning', { size: 13 })}${badProv.length} 个供应商配额耗尽</span>` : ''}`;
    } catch (e) {
      el.innerHTML = `<span class="row small" style="gap:5px;color:#f87171">${icon('warning', { size: 13 })}中继不可达: ${fmt.esc(e.message.slice(0, 40))}</span>`;
    }
  }

  async function renderHealth(ov) {
    const el = document.getElementById('dh-health');
    if (!el) return;
    try {
      const [svc, alerts] = await Promise.all([CM.api.get('/services'), CM.api.get('/alerts')]);
      const failed = svc.list.filter((s) => s.active === 'failed');
      const recentAlerts = (alerts.alerts || []).slice(0, 3);
      el.innerHTML = `
        ${ui.badge('服务 ' + svc.counts.running + ' 运行', 'ok')}
        ${failed.length ? ui.badge(failed.length + ' 个服务失败', 'bad') : ui.badge('无失败服务', 'ok')}
        ${(ov.temps || []).length ? ui.badge('温度 ' + ov.temps.map((t) => t.celsius + '°').join(' / '), 'info') : ''}
        ${alerts.config && alerts.config.enabled ? ui.badge('告警防护开启', 'info') : ui.badge('告警已关闭', 'gray')}
        ${recentAlerts.map((a) => `<span class="row small" style="gap:5px;color:${a.level === 'bad' ? '#f87171' : '#facc15'}">${icon(a.level === 'bad' ? 'warning' : 'info', { size: 13 })}${fmt.esc(a.message.slice(0, 60))}</span>`).join('')}
      `;
      const tEl = document.getElementById('dh-health-time');
      if (tEl) tEl.textContent = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    } catch (_) { el.innerHTML = '<span class="muted small">健康检查不可用</span>'; }
  }

  function renderTop(list) {
    const el = document.getElementById('dash-top');
    if (!el) return;
    el.innerHTML = ui.table([
      { k: 'pid', label: 'PID', cls: 'num' },
      { k: 'cpu', label: 'CPU%', cls: 'num' },
      { k: 'mem', label: 'MEM%', cls: 'num' },
      { k: 'args', label: '进程', render: (v) => `<span title="${fmt.esc(v)}">${fmt.esc(String(v).slice(0, 32))}</span>` },
    ], list.slice(0, 8), { style: 'max-height:280px;border:none;background:none' });
  }

  function renderDisks(disks) {
    const el = document.getElementById('dash-disks');
    if (!el) return;
    /* build once, then update widths in place so the bars never replay */
    const list = disks.mounts.slice(0, 6);
    if (el.dataset.ready !== '1' || el.children.length !== list.length) {
      el.innerHTML = list.map((d) => `
        <div data-mount="${fmt.esc(d.mount)}" style="margin-bottom:10px">
          <div class="row between small"><span class="mono">${fmt.esc(d.mount)}</span><span class="muted mono" data-info></span></div>
          <div class="bar"><div style="width:0%"></div></div>
        </div>`).join('');
      el.dataset.ready = '1';
    }
    list.forEach((d, i) => {
      const row = el.children[i];
      if (!row) return;
      row.querySelector('[data-info]').textContent = `${fmt.bytes(d.used)} / ${fmt.bytes(d.total)} · ${d.pct}%`;
      const bar = row.querySelector('.bar');
      bar.className = 'bar ' + fmt.pctColor(d.pct);
      bar.firstElementChild.style.width = Math.min(100, d.pct) + '%';
    });
  }
})();
