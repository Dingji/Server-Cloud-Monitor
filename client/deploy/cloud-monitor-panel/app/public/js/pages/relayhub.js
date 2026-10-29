/* RelayHub page - 内网中继监控(frp 从机设备 / 隧道可达性 / 传输速率)
   数据经面板后端 /api/relayhub/* 代理至 RelayHub(127.0.0.1:8100),与面板同一会话认证 */
(function () {
  'use strict';

  /* SIG: 结构签名。设备/探测结果不变时只原位更新数值,不重建 DOM。
     图表容器固定高度:Chart.js responsive+maintainAspectRatio=false 若放进
     没有固定高度的容器,canvas 会把自身高度当容器高度,每次刷新越撑越高
     (图表"一直向下坠"+ resize 循环卡顿)——必须用固定高度包裹层。 */
  const S = { sel: null, built: null, timer: null, nextAt: 0, probing: false, chart: null, sigList: null, sigProbes: null };
  const REFRESH_MS = 5000;

  const STYLE_ONCE = 'rh-page-styles';
  const STYLE = `
  <style id="${STYLE_ONCE}">
  .rh-top{display:flex;gap:10px;flex-wrap:wrap;align-items:stretch;margin-bottom:14px}
  .rh-chip{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);padding:9px 16px;display:flex;flex-direction:column;min-width:112px}
  .rh-chip b{font-size:18px;font-variant-numeric:tabular-nums}
  .rh-chip.ok b{color:var(--ok)} .rh-chip span{font-size:11px;color:var(--muted)}
  .rh-hitem{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--muted);background:var(--panel);border:1px solid var(--border);border-radius:12px;padding:5px 12px}
  .rh-hitem.ok{color:var(--ok);border-color:rgba(34,197,94,.35)}
  .rh-hitem.bad{color:var(--bad);border-color:rgba(239,68,68,.35)}
  .rh-dot{width:7px;height:7px;border-radius:50%;background:currentColor;flex-shrink:0}
  .rh-dot.pulse{animation:rhpulse 2s infinite}@keyframes rhpulse{0%,100%{opacity:1}50%{opacity:.4}}
  .rh-layout{display:grid;grid-template-columns:288px 1fr;gap:14px;align-items:start}
  .rh-list{display:flex;flex-direction:column;gap:8px;max-height:calc(100vh - 230px);overflow-y:auto;padding-right:2px}
  .rh-list::-webkit-scrollbar{width:6px}.rh-list::-webkit-scrollbar-thumb{background:var(--border2);border-radius:6px}
  .rh-dev{display:flex;align-items:center;gap:10px;padding:11px 12px;cursor:pointer;background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);transition:border-color .15s,background .15s}
  .rh-dev:hover{border-color:var(--border2);transform:translateX(2px)}
  .rh-dev.sel{border-color:rgba(var(--accent-rgb),.65);background:linear-gradient(135deg,rgba(var(--accent-rgb),.13),rgba(var(--accent-rgb),.04))}
  .rh-dev.off{opacity:.55}
  .rh-ava{width:36px;height:36px;border-radius:var(--radius);background:linear-gradient(135deg,var(--accent),var(--info));display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700;font-size:13px;text-transform:uppercase;flex-shrink:0}
  .rh-dev.off .rh-ava{background:var(--panel3)}
  .rh-name{font-weight:600;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .rh-sub{font-size:11px;color:var(--muted);display:flex;gap:7px;align-items:center;margin-top:1px}
  .rh-rate{font-size:11.5px;font-family:var(--mono);color:var(--info);text-align:right}
  .rh-rate.o{color:var(--accent2)}
  .rh-detail{min-width:0;display:flex;flex-direction:column;gap:14px}
  .rh-card{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);padding:15px 17px}
  .rh-head{display:flex;align-items:center;gap:14px;position:relative;overflow:hidden}
  .rh-head::after{content:"";position:absolute;right:-50px;top:-60px;width:200px;height:200px;background:radial-gradient(circle,rgba(var(--accent-rgb),.14),transparent 65%);pointer-events:none}
  .rh-ava.big{width:48px;height:48px;font-size:18px;box-shadow:0 5px 16px rgba(var(--accent-rgb),.35)}
  .rh-title{font-size:17px;font-weight:700}
  .rh-pills{display:flex;gap:6px;margin-top:6px;flex-wrap:wrap}
  .rh-pill{font-size:11px;padding:2px 9px;border-radius:10px;border:1px solid var(--border);color:var(--muted)}
  .rh-pill.ok{color:var(--ok);border-color:rgba(34,197,94,.35)}
  .rh-pill.bad{color:var(--bad);border-color:rgba(239,68,68,.35)}
  .rh-pill.warn{color:var(--warn);border-color:rgba(234,179,8,.35)}
  .rh-stats{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}
  .rh-stat{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);padding:11px 14px}
  .rh-stat b{font-size:16px;font-variant-numeric:tabular-nums}
  .rh-stat span{display:block;font-size:11px;color:var(--muted);margin-top:1px}
  .rh-chartwrap{position:relative;height:170px}
  .rh-probe{display:grid;grid-template-columns:minmax(90px,1.1fr) minmax(140px,1.3fr) minmax(120px,1.2fr) 92px 96px;gap:9px;align-items:center;padding:10px 4px;border-bottom:1px solid var(--border);font-size:12.5px}
  .rh-probe:last-child{border-bottom:none}
  .rh-probe.h{font-size:10.5px;color:var(--muted);text-transform:uppercase;letter-spacing:.5px;padding:4px}
  .rh-probe .nm{font-weight:600;display:flex;gap:7px;align-items:center;min-width:0}
  .rh-probe .nm i{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-style:normal}
  .rh-ep{font-family:var(--mono);font-size:11.5px;color:var(--info)}
  .rh-det{color:var(--muted);font-size:11.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .rh-ms{font-family:var(--mono);font-weight:700;font-size:11.5px}
  .rh-ms.ok{color:var(--ok)} .rh-ms.warn{color:var(--warn)} .rh-ms.bad{color:var(--bad)}
  .rh-bars{display:flex;align-items:flex-end;gap:2px;height:20px}
  .rh-bars i{flex:1;min-width:3px;border-radius:1px;background:rgba(34,197,94,.6);transition:height .3s}
  .rh-bars i.f{background:rgba(239,68,68,.65)}
  .rh-table{width:100%;border-collapse:collapse;font-size:12.5px}
  .rh-table th{text-align:left;font-size:10.5px;color:var(--muted);text-transform:uppercase;letter-spacing:.5px;padding:7px 8px;border-bottom:1px solid var(--border)}
  .rh-table td{padding:9px 8px;border-bottom:1px solid rgba(255,255,255,.04)}
  .rh-empty{text-align:center;color:var(--muted);padding:36px 0}
  @media(max-width:1180px){.rh-layout{grid-template-columns:1fr}.rh-list{flex-direction:row;overflow-x:auto;max-height:none}.rh-dev{min-width:210px}.rh-stats{grid-template-columns:repeat(2,1fr)}.rh-probe{grid-template-columns:1.2fr 1.2fr 1fr 80px}.rh-probe>:nth-child(5){display:none}}
  </style>`;

  registerPage({
    id: 'relayhub',
    title: '内网中继监控',
    desc: 'frp 内网穿透:从机设备全景、隧道端到端可达性探测与传输速率',
    async mount(root) {
      if (!document.getElementById(STYLE_ONCE)) document.head.insertAdjacentHTML('beforeend', STYLE);
      root.innerHTML = `
        <div class="rh-top">
          <div class="rh-chip" id="rh-c-dev"><b>–</b><span>从机设备</span></div>
          <div class="rh-chip" id="rh-c-reach"><b>–</b><span>隧道可达</span></div>
          <div class="rh-chip"><b id="rh-c-in">–</b><span>总入站速率</span></div>
          <div class="rh-chip"><b id="rh-c-out">–</b><span>总出站速率</span></div>
          <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap" id="rh-health"></div>
          <span style="flex:1"></span>
          <button class="btn btn-sm btn-primary" id="rh-probe-btn" style="align-self:center">${icon('scan', { size: 13 })} 立即探测</button>
        </div>
        <div class="rh-layout">
          <div class="rh-list" id="rh-list"></div>
          <div class="rh-detail" id="rh-detail"></div>
        </div>`;
      document.getElementById('rh-probe-btn').onclick = probeNow;
      await render(true);
      S.timer = setInterval(tick, 1000);
    },
    hide() { clearInterval(S.timer); },
    show() { S.nextAt = 0; render(); },
  });

  async function tick() {
    if (!CM.token || document.hidden) return;
    if (!CM.router.current || CM.router.current.id !== 'relayhub') return;
    const now = Date.now();
    if (now < S.nextAt) return;
    S.nextAt = now + REFRESH_MS;
    render();
  }

  async function probeNow() {
    const btn = document.getElementById('rh-probe-btn');
    if (!btn || S.probing) return;
    S.probing = true; btn.disabled = true; btn.style.opacity = .6;
    try {
      await CM.api.post('/relayhub/probe-now');
      window.toast && window.toast('端到端探测完成', 'ok');
      S.nextAt = 0; await render();
    } catch (e) { window.toast && window.toast('探测失败: ' + e.message, 'err'); }
    finally { S.probing = false; btn.disabled = false; btn.style.opacity = 1; }
  }

  const fmtB = (n) => window.fmt ? fmt.bytes(n) : (n || 0) + 'B';
  const fmtBps = (n) => (window.fmt && fmt.bps) ? fmt.bps(n) : fmtB(n) + '/s';
  const esc = (s) => window.fmt ? fmt.esc(s) : String(s == null ? '' : s);
  function ago(str, nowTs) {
    if (!str) return '–';
    if (/^\d{2}-\d{2} \d{2}:\d{2}/.test(str)) str = new Date().getFullYear() + '-' + str;
    const t = new Date(String(str).replace(' ', 'T')).getTime();
    if (isNaN(t)) return str;
    const d = Math.max(0, (nowTs || Date.now() / 1000) * 1000 - t) / 1000;
    if (d < 60) return Math.floor(d) + ' 秒前';
    if (d < 3600) return Math.floor(d / 60) + ' 分钟前';
    if (d < 86400) return Math.floor(d / 3600) + ' 小时前';
    return Math.floor(d / 86400) + ' 天前';
  }
  function spark(points, w, h, color) {
    const pts = points.slice(-22).map((v) => v || 0);
    if (!pts.length) return '';
    const max = Math.max(1, ...pts);
    return `<svg width="${w}" height="${h}"><polyline fill="none" stroke="${color}" stroke-width="1.5" points="${pts.map((v, i) => `${(i / Math.max(1, pts.length - 1)) * w},${h - 2 - (v / max) * (h - 4)}`).join(' ')}"/></svg>`;
  }

  let failCount = 0;

  function devSig(devices) {
    return devices.map((x) => `${x.name}|${x.status}|${x.probed}|${x.reachable}`).join(';');
  }

  function buildListHTML(devices) {
    return devices.map((dev) => `
      <div class="rh-dev ${dev.status !== 'online' ? 'off' : ''} ${S.sel === dev.name ? 'sel' : ''}" data-dev="${esc(dev.name)}">
        <div class="rh-ava">${esc(dev.name.slice(0, 2))}</div>
        <div style="flex:1;min-width:0">
          <div class="rh-name">${esc(dev.name)}</div>
          <div class="rh-sub"><span class="rh-dot ${dev.status === 'online' ? 'pulse' : ''}" style="color:${dev.status === 'online' ? 'var(--ok)' : 'var(--muted)'}"></span>${dev.probed ? '可达 ' + dev.reachable + '/' + dev.probed : '未探测'}</div>
        </div>
        <div>
          <div class="rh-rate">↓${fmtBps(dev.rate_in)}</div>
          <div class="rh-rate o">↑${fmtBps(dev.rate_out)}</div>
          <div class="rh-spark">${spark((dev.hist || []).map((h) => (h.in || 0) + (h.out || 0)), 58, 14, 'var(--info)')}</div>
        </div>
      </div>`).join('') || '<div class="rh-empty">暂无设备接入</div>';
  }

  function updateListInPlace(list, devices) {
    const cards = list.querySelectorAll('.rh-dev');
    if (cards.length !== devices.length) return false;
    for (let i = 0; i < devices.length; i++) {
      const el = cards[i], dev = devices[i];
      if (el.dataset.dev !== dev.name) return false;
      el.classList.toggle('off', dev.status !== 'online');
      el.classList.toggle('sel', S.sel === dev.name);
      const rateIn = el.querySelector('.rh-rate'), rateOut = el.querySelector('.rh-rate.o');
      if (rateIn) rateIn.textContent = '↓' + fmtBps(dev.rate_in);
      if (rateOut) rateOut.textContent = '↑' + fmtBps(dev.rate_out);
      const svg = spark((dev.hist || []).map((h) => (h.in || 0) + (h.out || 0)), 58, 14, 'var(--info)');
      const sp = el.querySelector('.rh-spark');
      if (sp && sp.dataset.svg !== svg) { sp.innerHTML = svg; sp.dataset.svg = svg; }
    }
    return true;
  }

  async function render(rebuild) {
    let d;
    try { d = await CM.api.get('/relayhub/summary'); } catch (e) {
      failCount++;
      const box = document.getElementById('rh-detail');
      if (box && failCount >= 3) {
        box.innerHTML = '<div class="rh-card rh-empty" style="color:var(--warn)">' +
          '⚠ 中继服务暂时不可达(RelayHub 面板或 frps 未响应),将在恢复后自动刷新。<br>' +
          '<span class="small">可在 Web 终端执行 systemctl status relayhub-frps relayhub-panel 排查</span></div>';
      }
      return;
    }
    failCount = 0;
    if (!d || !d.devices) return;
    const devices = d.devices, probes = d.probes || {}, health = d.health || {};
    const oldWarn = document.getElementById('rh-frps-warn');
    if (d.frps_ok === false) {
      if (!oldWarn) {
        const c = document.getElementById('rh-c-dev');
        if (c) c.insertAdjacentHTML('afterend', '<div class="rh-card" id="rh-frps-warn" style="padding:8px 14px;color:var(--warn);border-color:rgba(234,179,8,.4)">⚠ frps 控制面不可达:' + esc(d.frps_error || '未知错误') + '</div>');
      }
    } else if (oldWarn) oldWarn.remove();
    const onDev = devices.filter((x) => x.status === 'online');
    const reach = devices.reduce((a, x) => a + (x.reachable || 0), 0);
    const probed = devices.reduce((a, x) => a + (x.probed || 0), 0);

    const set = (id, v) => { const el = document.getElementById(id); if (el && el.textContent !== v) el.textContent = v; };
    const cdev = document.getElementById('rh-c-dev');
    if (cdev) cdev.className = 'rh-chip' + (onDev.length === devices.length ? ' ok' : '');
    if (cdev) cdev.querySelector('b').textContent = onDev.length + ' / ' + devices.length;
    const cre = document.getElementById('rh-c-reach');
    if (cre) { cre.className = 'rh-chip' + (probed && reach === probed ? ' ok' : ''); cre.querySelector('b').textContent = probed ? reach + ' / ' + probed : '–'; }
    set('rh-c-in', fmtBps(devices.reduce((a, x) => a + (x.rate_in || 0), 0)));
    set('rh-c-out', fmtBps(devices.reduce((a, x) => a + (x.rate_out || 0), 0)));
    const hh = document.getElementById('rh-health');
    if (hh) {
      const hhh = [['frps_api', 'frps 控制面'], ['tcp_7000', 'TCP 7000'], ['nginx_80', 'nginx :80']]
        .map(([k, l]) => `<span class="rh-hitem ${health[k] ? 'ok' : 'bad'}"><span class="rh-dot ${health[k] ? 'pulse' : ''}"></span>${l}</span>`).join('');
      if (hh.dataset.html !== hhh) { hh.innerHTML = hhh; hh.dataset.html = hhh; }
    }

    /* 左侧列表:结构不变时原位更新,不再每轮整块重建 */
    const list = document.getElementById('rh-list');
    if (list) {
      const sig = devSig(devices);
      if (sig !== S.sigList) {
        list.innerHTML = buildListHTML(devices);
        S.sigList = sig;
        (list.querySelectorAll('[data-dev]') || []).forEach((el) => (el.onclick = () => {
          S.sel = el.dataset.dev; S.built = null; render(true);
        }));
      } else if (!updateListInPlace(list, devices)) {
        S.sigList = null;
      }
    }

    /* 右侧详情 */
    const box = document.getElementById('rh-detail');
    if (!box) return;
    const sel = devices.find((x) => x.name === S.sel) || devices[0];
    if (!sel) {
      if (S.chart) { S.chart.destroy(); S.chart = null; }
      S.built = null; S.sigProbes = null;
      box.innerHTML = '<div class="rh-card rh-empty">暂无设备 — 在 RelayHub 面板的「接入指南」中添加第一台内网设备</div>';
      return;
    }
    S.sel = sel.name;

    if (S.built !== sel.name) {
      S.built = sel.name;
      S.sigProbes = null;
      if (S.chart) { S.chart.destroy(); S.chart = null; }   // 旧实例连同旧 canvas 一起释放
      box.innerHTML = `
        <div class="rh-card rh-head">
          <div class="rh-ava big">${esc(sel.name.slice(0, 2))}</div>
          <div style="flex:1;min-width:0;position:relative">
            <div class="rh-title">${esc(sel.name)}</div>
            <div class="rh-pills">
              <span class="rh-pill" id="rh-pill"></span>
              <span class="rh-pill">接入 · WebSocket :80</span>
              <span class="rh-pill" id="rh-start"></span>
              <span class="rh-pill" id="rh-lat"></span>
            </div>
          </div>
        </div>
        <div class="rh-stats">
          <div class="rh-stat"><b style="color:var(--info)" id="rh-si">–</b><span>入站速率(设备→公网)</span></div>
          <div class="rh-stat"><b style="color:var(--accent2)" id="rh-so">–</b><span>出站速率(公网→设备)</span></div>
          <div class="rh-stat"><b id="rh-st">–</b><span>今日中继流量</span></div>
          <div class="rh-stat"><b id="rh-sc">–</b><span>当前活动连接</span></div>
        </div>
        <div class="rh-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
            <b>设备实时速率(近 10 分钟)</b>
            <span class="muted small">↑ 出站 · <span style="color:var(--info)">●</span> 入站</span>
          </div>
          <div class="rh-chartwrap"><canvas id="rh-chart"></canvas></div>
        </div>
        <div class="rh-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
            <b>隧道可达性探测</b><span class="muted small">每 10 秒自动端到端探测 · SSH 横幅 / HTTP 状态验证</span>
          </div>
          <div class="rh-probe h"><span>隧道</span><span>公网入口</span><span>服务验证</span><span>延迟</span><span>历史</span></div>
          <div id="rh-probes"></div>
        </div>
        <div class="rh-card">
          <b style="display:block;margin-bottom:6px">代理明细</b>
          <table class="rh-table">
            <thead><tr><th>代理</th><th>类型</th><th>状态</th><th style="text-align:right">今日流量 ↓/↑</th><th style="text-align:right">连接</th><th>最近上线</th></tr></thead>
            <tbody id="rh-proxies"></tbody>
          </table>
        </div>`;
    }

    const pill = document.getElementById('rh-pill');
    if (pill) {
      const on = sel.status === 'online';
      pill.className = 'rh-pill ' + (on ? 'ok' : sel.status === 'error' ? 'warn' : 'bad');
      pill.textContent = on ? '● 在线' : sel.status === 'error' ? '⚠ 异常' : '○ 离线';
    }
    const set2 = (id, v) => { const el = document.getElementById(id); if (el && el.textContent !== v) el.textContent = v; };
    set2('rh-start', sel.last_start ? '上线 ' + sel.last_start : '未连接');
    set2('rh-lat', sel.avg_latency != null ? '平均探测 ' + sel.avg_latency + 'ms' : '');
    set2('rh-si', fmtBps(sel.rate_in));
    set2('rh-so', fmtBps(sel.rate_out));
    set2('rh-st', fmtB(sel.today_in + sel.today_out));
    set2('rh-sc', String(sel.cur_conns));

    /* 图表(Chart.js, 实例复用;容器固定高度,杜绝 responsive 自撑高循环) */
    const hist = sel.hist || [];
    const cv = document.getElementById('rh-chart');
    if (cv && window.Chart) {
      const labels = hist.map((h) => new Date(h.t * 1000).toLocaleTimeString('zh-CN', { hour12: false, minute: '2-digit' }));
      if (S.chart && S.chart.canvas === cv) {
        S.chart.data.labels = labels;
        S.chart.data.datasets[0].data = hist.map((h) => h.in);
        S.chart.data.datasets[1].data = hist.map((h) => h.out);
        S.chart.update('none');
      } else {
        if (S.chart) { S.chart.destroy(); S.chart = null; }
        S.chart = new Chart(cv.getContext('2d'), {
          type: 'line',
          data: {
            labels,
            datasets: [
              { label: '入站', data: hist.map((h) => h.in), borderColor: '#38bdf8', backgroundColor: 'rgba(56,189,248,.12)', fill: true, tension: .35, pointRadius: 0, borderWidth: 2 },
              { label: '出站', data: hist.map((h) => h.out), borderColor: '#7a9bff', backgroundColor: 'rgba(122,155,255,.10)', fill: true, tension: .35, pointRadius: 0, borderWidth: 2 },
            ],
          },
          options: {
            responsive: true, maintainAspectRatio: false, animation: false,
            plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => c.dataset.label + ' ' + fmtBps(c.parsed.y) } } },
            scales: {
              x: { ticks: { color: '#7a8899', font: { size: 10 }, maxTicksLimit: 6 }, grid: { color: 'rgba(255,255,255,.05)' } },
              y: { ticks: { color: '#7a8899', font: { size: 10 }, callback: (v) => fmtB(v) }, grid: { color: 'rgba(255,255,255,.05)' }, beginAtZero: true },
            },
          },
        });
      }
    }

    /* 探测行:数据未变则跳过,变化时高度条自带过渡动画 */
    const devProxies = (d.proxies || []).filter((p) => p.name.split('.')[0] === sel.name);
    const pbox = document.getElementById('rh-probes');
    if (pbox) {
      const psig = devProxies.map((p) => {
        const pr = probes[p.name];
        return [p.name, p.status, pr ? pr.ok : '', pr ? pr.ms : '', pr ? pr.detail : '', pr && pr.hist ? pr.hist.join(',') : ''].join('~');
      }).join(';');
      if (psig !== S.sigProbes) {
        S.sigProbes = psig;
        pbox.innerHTML = devProxies.map((p) => {
          const pr = probes[p.name];
          const conf = p.conf || {};
          const port = conf.remotePort != null ? String(conf.remotePort) : (String(p.remoteAddr || '').includes(':') ? String(p.remoteAddr).split(':').pop() : '');
          const bars = (pr && pr.hist ? pr.hist : []).map((ms) => ms == null
            ? '<i class="f" style="height:100%"></i>'
            : `<i style="height:${Math.max(12, Math.min(100, ms / 40 * 100))}%"></i>`).join('');
          const msCls = !pr ? '' : pr.ok ? (pr.ms != null && pr.ms < 30 ? 'ok' : pr.ms != null && pr.ms < 150 ? 'warn' : 'warn') : 'bad';
          return `<div class="rh-probe">
            <span class="nm"><span class="rh-dot ${p.status === 'online' ? 'pulse' : ''}" style="color:${p.status === 'online' ? 'var(--ok)' : 'var(--muted)'}"></span><i>${esc(p.name.split('.').slice(1).join('.') || p.name)}</i></span>
            <span class="rh-ep">${port ? esc(d.meta.public_addr) + ':' + esc(port) : '–'}</span>
            <span class="rh-det">${pr ? esc(pr.detail || (pr.ok ? '可达' : '不可达')) : '等待探测…'}</span>
            <span class="rh-ms ${msCls}">${pr ? (pr.ok ? (pr.ms != null ? pr.ms + ' ms' : '通') : '不可达') : '–'}</span>
            <span class="rh-bars">${bars || '<i style="height:12%"></i>'}</span>
          </div>`;
        }).join('') || '<div class="rh-empty">该设备暂无代理通道</div>';
      }
    }

    const tbody = document.getElementById('rh-proxies');
    if (tbody) tbody.innerHTML = devProxies.map((p) => `
      <tr>
        <td class="mono"><b>${esc(p.name.split('.').slice(1).join('.') || p.name)}</b></td>
        <td>${esc(p._proto || 'tcp').toUpperCase()}</td>
        <td>${p.status === 'online' ? '<span class="rh-pill ok">在线</span>' : p.err ? '<span class="rh-pill warn">异常</span>' : '<span class="rh-pill">离线</span>'}</td>
        <td class="mono" style="text-align:right;font-size:11.5px">${fmtB(p.todayTrafficIn || 0)} / ${fmtB(p.todayTrafficOut || 0)}</td>
        <td class="mono" style="text-align:right">${p.curConns || 0}</td>
        <td class="muted small">${esc(ago(p.lastStartTime, d.meta.now))}</td>
      </tr>`).join('');
  }
})();
