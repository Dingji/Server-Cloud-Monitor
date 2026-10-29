/* RelayHub page - 内网中继监控(frp 从机设备 / 隧道可达性 / 传输速率)
   数据经面板后端 /api/relayhub/* 代理至 RelayHub(127.0.0.1:8100),与面板同一会话认证 */
(function () {
  'use strict';

  /* SIG: 结构签名。设备/探测结果不变时只原位更新数值,不重建 DOM。
     图表容器固定高度:Chart.js responsive+maintainAspectRatio=false 若放进
     没有固定高度的容器,canvas 会把自身高度当容器高度,每次刷新越撑越高
     (图表"一直向下坠"+ resize 循环卡顿)——必须用固定高度包裹层。 */
  const S = { sel: null, built: null, timer: null, nextAt: 0, probing: false, chart: null, sigList: null, sigProbes: null };
  const REFRESH_MS = 500;
  /* 图表 x 轴标签:后端已按 0.5s 采样,近 10 分钟 = 1210 点。若每帧逐点调用
     toLocaleTimeString,2Hz 下每秒要格式化 2420 次,浏览器端会明显吃 CPU。
     时间戳只有秒级粒度且相邻两帧高度重叠(约 99%),缓存后每帧几乎零格式化。 */
  const LABEL_CACHE = new Map();
  function tl(t) {
    let v = LABEL_CACHE.get(t);
    if (v === undefined) {
      v = new Date(t * 1000).toLocaleTimeString('zh-CN', { hour12: false, minute: '2-digit' });
      if (LABEL_CACHE.size > 4096) LABEL_CACHE.clear();
      LABEL_CACHE.set(t, v);
    }
    return v;
  }

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

  .rh-mapfoot{display:flex;gap:8px;align-items:center;margin-top:11px;flex-wrap:wrap}
  .rh-mapfoot .muted{margin-right:2px}
  .rh-map-in{width:100%;min-width:0;padding:5px 8px;font-size:12.5px;font-family:var(--mono)}
  .rh-mapmsg{margin-top:9px;padding:8px 11px;border-radius:8px;font-size:12px;line-height:1.6;
             border:1px solid var(--border);background:var(--panel2);color:var(--muted);word-break:break-word}
  .rh-mapmsg.ok{color:var(--ok);border-color:rgba(34,197,94,.35);background:rgba(34,197,94,.07)}
  .rh-mapmsg.err{color:var(--bad);border-color:rgba(239,68,68,.4);background:rgba(239,68,68,.07)}
  .rh-mapmsg.warn{color:var(--warn);border-color:rgba(234,179,8,.4);background:rgba(234,179,8,.07)}
  .rh-mapcmd{margin-top:10px;padding:10px 11px;border:1px dashed rgba(var(--accent-rgb),.5);border-radius:10px;background:rgba(var(--accent-rgb),.05)}
  .rh-mapcmd pre{margin:0;padding:9px 10px;background:#0b111a;border:1px solid var(--border);border-radius:8px;
                 font-size:11.5px;line-height:1.55;white-space:pre-wrap;word-break:break-all;max-height:200px;overflow:auto;color:#8fe3c0}
  .rh-maplive{margin-top:7px;font-size:11.5px;color:var(--muted)}
  .rh-empty{text-align:center;color:var(--muted);padding:36px 0}
  .rh-poolhead{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
  .rh-poolgrid{display:grid;grid-template-columns:1.5fr 1fr;gap:16px;align-items:start}
  .rh-poolgrid label{display:block;font-size:11px;color:var(--muted);margin:10px 0 4px}
  .rh-poolgrid .inp{width:100%;padding:6px 9px;font-size:12.5px;font-family:var(--mono)}
  .rh-pool-acts{display:flex;gap:8px;margin-top:12px;flex-wrap:wrap;align-items:center}
  .rh-poolbox{margin-top:11px;padding:10px 12px;border:1px solid var(--border);border-radius:9px;background:var(--panel2);font-size:12.5px;line-height:1.65;word-break:break-word}
  .rh-poolbox.ok{border-color:rgba(34,197,94,.4);background:rgba(34,197,94,.06)}
  .rh-poolbox.err{border-color:rgba(239,68,68,.45);background:rgba(239,68,68,.07);color:var(--bad)}
  .rh-poolbox.warn{border-color:rgba(234,179,8,.45);background:rgba(234,179,8,.07);color:var(--warn)}
  .rh-poolbox h5{margin:0 0 6px;font-size:12.5px}
  .rh-poolstep{display:flex;gap:9px;align-items:baseline;padding:3px 0;flex-wrap:wrap}
  .rh-poolstep b{font-size:12px;min-width:84px}
  .rh-poolarrow{font-family:var(--mono);font-size:11.5px;color:var(--info);word-break:break-all}

  /* ---- 内网设备硬件性能(经 Agent 上报) ---- */
  .hw-badge{display:inline-flex;align-items:center;gap:6px;font-size:11px;padding:2px 9px;border-radius:10px;border:1px solid var(--border);color:var(--muted)}
  .hw-badge.on{color:var(--ok);border-color:rgba(34,197,94,.35);background:rgba(34,197,94,.07)}
  .hw-badge.off{color:var(--bad);border-color:rgba(239,68,68,.38);background:rgba(239,68,68,.07)}
  .hw-gauges{display:grid;grid-template-columns:repeat(6,1fr);gap:10px}
  .hw-g{background:var(--panel2);border:1px solid var(--border);border-radius:var(--radius);padding:11px 13px;min-width:0}
  .hw-g .lab{font-size:11px;color:var(--muted);display:flex;align-items:center;gap:5px;white-space:nowrap}
  .hw-g .val{font-size:20px;font-weight:700;font-variant-numeric:tabular-nums;margin-top:3px;line-height:1.15}
  .hw-g .sub{font-size:10.5px;color:var(--muted);margin-top:2px;min-height:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .hw-g .bar{height:4px;border-radius:3px;background:var(--panel3);margin-top:7px;overflow:hidden}
  .hw-g .bar i{display:block;height:100%;border-radius:3px;background:var(--info);transition:width .35s,background .35s}
  .hw-core{display:flex;gap:4px;align-items:flex-end;height:52px;margin-top:12px}
  .hw-core .col{flex:1;min-width:0;display:flex;flex-direction:column;justify-content:flex-end;height:100%;gap:3px}
  .hw-core .col .b{border-radius:2px;background:linear-gradient(180deg,var(--accent),rgba(var(--accent-rgb),.5));min-height:2px;transition:height .35s}
  .hw-core .col .n{text-align:center;font-size:9.5px;color:var(--muted);font-family:var(--mono)}
  .hw-two{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:14px;align-items:start}
  .hw-kv{display:grid;grid-template-columns:auto 1fr;gap:5px 14px;font-size:12.5px}
  .hw-kv .k{color:var(--muted);white-space:nowrap}
  .hw-kv .v{font-family:var(--mono);word-break:break-all}
  .hw-pills{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px}
  .hw-pill{font-size:11.5px;padding:3px 9px;border-radius:9px;border:1px solid var(--border);color:var(--muted);font-family:var(--mono)}
  .hw-pill.bad{color:var(--bad);border-color:rgba(239,68,68,.42);background:rgba(239,68,68,.07)}
  .hw-pill.warn{color:var(--warn);border-color:rgba(234,179,8,.42);background:rgba(234,179,8,.07)}
  .hw-pill.ok{color:var(--ok);border-color:rgba(34,197,94,.35);background:rgba(34,197,94,.06)}
  .hw-ranges{display:flex;gap:5px;flex-wrap:wrap;align-items:center}
  .hw-ranges button{font-size:11px;padding:3px 10px;border-radius:8px;border:1px solid var(--border);background:var(--panel2);color:var(--muted);cursor:pointer;font-family:inherit}
  .hw-ranges button:hover{border-color:var(--border2);color:var(--text)}
  .hw-ranges button.on{border-color:rgba(var(--accent-rgb),.6);color:var(--accent);background:rgba(var(--accent-rgb),.1)}
  .hw-sec{margin-top:15px}
  .hw-sec>.t{display:flex;justify-content:space-between;align-items:center;gap:9px;margin-bottom:7px;flex-wrap:wrap}
  .hw-sec>.t b{font-size:12.5px}
  .hw-jrow{font-family:var(--mono);font-size:11.5px;color:var(--bad);padding:4px 0;border-bottom:1px solid rgba(255,255,255,.04);word-break:break-all;line-height:1.5}
  .hw-jrow:last-child{border-bottom:none}
  .hw-empty{color:var(--muted);font-size:12px;padding:7px 0}
  @media(max-width:1180px){.hw-gauges{grid-template-columns:repeat(3,1fr)}.hw-two{grid-template-columns:1fr}}
  @media(max-width:640px){.hw-gauges{grid-template-columns:repeat(2,1fr)}}
  @media(max-width:1180px){.rh-poolgrid{grid-template-columns:1fr}}
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
          <div class="rh-chip" id="rh-c-hw"><b>–</b><span>硬件上报</span></div>
          <div class="rh-chip"><b id="rh-c-in">–</b><span>总入站速率</span></div>
          <div class="rh-chip"><b id="rh-c-out">–</b><span>总出站速率</span></div>
          <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap" id="rh-health"></div>
          <span style="flex:1"></span>
          <button class="btn btn-sm btn-primary" id="rh-probe-btn" style="align-self:center">${icon('scan', { size: 13 })} 立即探测</button>
        </div>
        <div class="rh-layout">
          <div class="rh-list" id="rh-list"></div>
          <div class="rh-detail" id="rh-detail"></div>
        </div>
        <div class="rh-card" id="rh-pool-card" style="margin-top:14px">
          <div class="rh-poolhead" id="rh-pool-head" style="cursor:pointer" title="点击展开/收起">
            <b>端口池配置</b>
            <span class="rh-pill" id="rh-pool-badge">读取中…</span>
            <span class="muted small" id="rh-pool-cur">–</span>
            <span style="flex:1"></span>
            <span class="muted small" id="rh-pool-probestate" style="display:none"></span>
            <button class="btn btn-sm" type="button" id="rh-pool-toggle">展开 ▼</button>
          </div>
          <div id="rh-pool-body" style="display:none;margin-top:12px"></div>
        </div>
        <div class="rh-card" id="rh-hw-card" style="margin-top:14px">
          <div class="rh-poolhead" id="rh-hw-head" style="cursor:pointer" title="点击展开/收起">
            <b>硬件性能设备（Agent 上报）</b>
            <span class="rh-pill" id="rh-hw-badge">读取中…</span>
            <span class="muted small" id="rh-hw-sum">–</span>
            <span style="flex:1"></span>
            <button class="btn btn-sm" type="button" id="rh-hw-toggle">展开 ▼</button>
          </div>
          <div id="rh-hw-body" style="display:none;margin-top:12px"></div>
        </div>`;
      document.getElementById('rh-probe-btn').onclick = probeNow;
      poolWire();
      poolLoad().catch(() => {});
      hwWire();
      hwBindHub();          /* 订阅设备硬件推送(页面 mount 只执行一次,不会重复注册) */
      hwSync().catch(() => {});
      await render(true);
      S.timer = setInterval(tick, 500);
    },
    hide() { clearInterval(S.timer); },
    show() { S.nextAt = 0; render(); },
  });

  let busy = false;
  async function tick() {
    if (!CM.token || document.hidden) return;
    if (!CM.router.current || CM.router.current.id !== 'relayhub') return;
    if (busy) return;   /* 上一拍未完成则跳过,500ms 高频下绝不堆积请求 */
    const now = Date.now();
    if (now < S.nextAt) return;
    S.nextAt = now + REFRESH_MS;
    busy = true;
    try { await render(); } finally { busy = false; }
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

  /* ===== 端口映射编辑器:本地端口 -> 中继公网端口 =====
     期望态存于面板(mappings.json),编辑期间 MAP.dirty 置位,500ms 刷新
     只更新数值区,不会重建本卡片,因此输入不会被轮询清掉。 */
  const MAP = { dev: null, loaded: false, rows: [], live: [], base: '', ranges: '', max: 16,
                dirty: false, busy: false, msg: '', kind: '', cmd: '', warn: '' };
  const mapSig = (rows) => JSON.stringify(rows.map((r) => [r.name, r.localPort, r.remotePort, r.type, r.note]));
  const AUTONAME = /^port-\d+$/;
  const asRow = (m) => ({
    name: m.name || '', localPort: m.localPort, remotePort: m.remotePort,
    type: (m.type || 'tcp').toLowerCase(), note: m.note || '', localIP: m.localIP || '127.0.0.1',
  });

  async function mapFetch(device, keepMsg) {
    try {
      const d = await CM.api.get('/relayhub/mappings');
      if (MAP.dev !== device) return false;          // 期间已切到别的设备
      MAP.ranges = d.ranges_text || '';
      MAP.max = d.max_per_device || 16;
      MAP.live = (d.live || {})[device] || [];
      const rows = ((d.mappings || {})[device] || []).map(asRow);
      MAP.rows = rows;
      MAP.base = mapSig(rows);
      MAP.loaded = true;
      if (!keepMsg) { MAP.msg = ''; MAP.kind = ''; }
      mapRender();
      return true;
    } catch (e) {
      if (MAP.dev !== device) return false;
      MAP.msg = '读取映射失败: ' + e.message;
      MAP.kind = 'err';
      mapRender();
      return false;
    }
  }

  function mapLoad(device) {
    if (MAP.dev !== device) {
      MAP.dev = device; MAP.loaded = false; MAP.rows = []; MAP.live = [];
      MAP.base = ''; MAP.dirty = false; MAP.cmd = ''; MAP.warn = '';
      MAP.msg = ''; MAP.kind = '';
      mapRender();
      mapFetch(device);
      return;
    }
    mapRender();
  }

  function mapBusyUI(on) {
    ['rh-map-add', 'rh-map-gen', 'rh-map-save'].forEach((id) => {
      const el = document.getElementById(id);
      if (!el) return;
      el.disabled = !!on || (id === 'rh-map-save' ? (!MAP.dirty || MAP.busy) : MAP.busy);
    });
  }

  function mapState() {
    const st = document.getElementById('rh-map-state');
    if (st) st.textContent = MAP.busy ? '处理中…'
      : MAP.dirty ? '● 有未保存的修改'
      : MAP.loaded ? '已与面板同步' : '…';
    const sv = document.getElementById('rh-map-save');
    if (sv) sv.disabled = MAP.busy || !MAP.dirty;
  }

  function mapRender() {
    const body = document.getElementById('rh-map-body');
    if (!body) return;
    const rng = document.getElementById('rh-map-ranges');
    if (rng) rng.textContent = MAP.ranges
      ? ('允许公网端口 ' + MAP.ranges + ' · 单设备最多 ' + MAP.max + ' 条')
      : '读取允许范围…';

    const trs = MAP.rows.map((r, i) => `
      <tr>
        <td><input class="inp rh-map-in" data-i="${i}" data-f="localPort" inputmode="numeric" value="${esc(r.localPort == null ? '' : r.localPort)}" placeholder="如 8080"></td>
        <td><input class="inp rh-map-in" data-i="${i}" data-f="remotePort" inputmode="numeric" value="${esc(r.remotePort == null ? '' : r.remotePort)}" placeholder="如 36001"></td>
        <td><select class="inp rh-map-in" data-i="${i}" data-f="type"><option value="tcp"${r.type === 'udp' ? '' : ' selected'}>TCP</option><option value="udp"${r.type === 'udp' ? ' selected' : ''}>UDP</option></select></td>
        <td><input class="inp rh-map-in" data-i="${i}" data-f="note" value="${esc(r.note || '')}" placeholder="备注(可选)"></td>
        <td style="text-align:right;white-space:nowrap">
          <span class="muted small" style="margin-right:7px">${r.name ? esc(r.name) : '<i>待生成</i>'}</span>
          <button class="btn btn-sm btn-danger" data-del="${i}">删除</button>
        </td>
      </tr>`).join('');

    const live = (MAP.live || []).map((p) =>
      `${esc(p.name || '?')} ${p.localPort == null ? '?' : esc(p.localPort)}→${esc(p.remotePort)}${p.status === 'online' ? ' 在线' : ' 离线'}`).join(' · ');

    body.innerHTML = `
      <table class="rh-table">
        <thead><tr><th style="width:19%">本地端口</th><th style="width:21%">公网端口</th><th style="width:12%">协议</th><th>备注</th><th style="text-align:right">代理名 / 操作</th></tr></thead>
        <tbody>${trs || '<tr><td colspan="5" class="muted small" style="padding:15px 8px">尚未配置任何映射 — 点「+ 添加映射」为该设备新开一条隧道</td></tr>'}</tbody>
      </table>
      ${live ? `<div class="rh-maplive">现网已注册:${live}</div>` : ''}
      <div class="rh-mapfoot">
        <button class="btn btn-sm" id="rh-map-add"${MAP.rows.length >= MAP.max ? ' disabled title="已达单设备代理数上限"' : ''}>+ 添加映射</button>
        <span class="muted small" id="rh-map-state">…</span>
        <span style="flex:1"></span>
        <button class="btn btn-sm" id="rh-map-gen">生成下发命令</button>
        <button class="btn btn-sm btn-primary" id="rh-map-save">保存到面板</button>
      </div>
      ${MAP.msg ? `<div class="rh-mapmsg ${MAP.kind}">${esc(MAP.msg)}</div>` : ''}
      ${MAP.warn ? `<div class="rh-mapmsg warn">${esc(MAP.warn)}</div>` : ''}
      ${MAP.cmd ? `
      <div class="rh-mapcmd">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:7px">
          <b style="font-size:12px">在设备 <span class="mono">${esc(MAP.dev)}</span> 上以 root 执行一次</b>
          <button class="btn btn-sm" id="rh-map-copy">复制命令</button>
        </div>
        <pre id="rh-map-cmdtext">${esc(MAP.cmd)}</pre>
        <div class="muted small" style="margin-top:7px">写入 /etc/frp/frpc.toml 并重启 relay-client;执行前面板上的修改不影响该设备。</div>
      </div>` : ''}`;

    wireMap();
    mapState();
  }

  function wireMap() {
    const body = document.getElementById('rh-map-body');
    if (!body) return;
    body.querySelectorAll('.rh-map-in').forEach((el) => {
      const i = +el.dataset.i, f = el.dataset.f;
      el.addEventListener(el.tagName === 'SELECT' ? 'change' : 'input', () => {
        const r = MAP.rows[i];
        if (!r) return;
        if (f === 'localPort' || f === 'remotePort') {
          const v = String(el.value).replace(/\D/g, '');
          r[f] = v;
          if (el.value !== v) el.value = v;      // 实时剔除非数字,不打断输入
        } else {
          r[f] = el.value;
        }
        const d = mapSig(MAP.rows);
        if ((d !== MAP.base) !== MAP.dirty) { MAP.dirty = d !== MAP.base; mapState(); }
      });
    });
    body.querySelectorAll('[data-del]').forEach((btn) => {
      btn.onclick = () => {
        const i = +btn.dataset.del;
        MAP.rows.splice(i, 1);
        MAP.dirty = mapSig(MAP.rows) !== MAP.base;
        MAP.cmd = '';
        mapRender();
      };
    });
    const add = document.getElementById('rh-map-add');
    if (add) add.onclick = () => {
      if (MAP.busy || MAP.rows.length >= MAP.max) return;
      MAP.rows.push({ name: '', localPort: '', remotePort: '', type: 'tcp', note: '', localIP: '127.0.0.1' });
      MAP.dirty = mapSig(MAP.rows) !== MAP.base;
      mapRender();
      const inputs = document.querySelectorAll('#rh-map-body .rh-map-in[data-f="localPort"]');
      const last = inputs[inputs.length - 1];
      if (last) last.focus();
    };
    const save = document.getElementById('rh-map-save');
    if (save) save.onclick = mapSave;
    const gen = document.getElementById('rh-map-gen');
    if (gen) gen.onclick = mapGen;
    const copy = document.getElementById('rh-map-copy');
    if (copy) copy.onclick = mapCopy;
  }

  function mapPayload() {
    return MAP.rows
      .filter((r) => String(r.localPort).trim() !== '' || String(r.remotePort).trim() !== '')
      .map((r) => {
        const lp = parseInt(String(r.localPort).replace(/\D/g, ''), 10);
        let name = String(r.name || '').trim();
        /* 系统自动生成的名字始终跟随本地端口重算,避免"改过端口后残留旧名字"
           与后续新增行撞名(后端会以"代理名重复"拒绝整个保存)。 */
        if (!name || AUTONAME.test(name)) name = 'port-' + lp;
        return {
          name, type: r.type === 'udp' ? 'udp' : 'tcp',
          localIP: r.localIP || '127.0.0.1',
          localPort: lp,
          remotePort: parseInt(String(r.remotePort).replace(/\D/g, ''), 10),
          note: r.note || '',
        };
      });
  }

  async function mapSave() {
    if (MAP.busy || !MAP.dirty) return;
    MAP.busy = true; mapBusyUI(true); mapState();
    try {
      const d = await CM.api.post('/relayhub/mappings', { device: MAP.dev, mappings: mapPayload() });
      MAP.warn = d.warning || '';
      MAP.cmd = '';                                  // 配置已变,旧命令作废
      await mapFetch(MAP.dev, true);
      MAP.msg = d.count
        ? ('已保存 ' + d.count + ' 条到面板 — 尚未在设备生效,请点「生成下发命令」并在设备上执行')
        : '已清空该设备的映射(需执行生成的命令才会停止现有隧道)';
      MAP.kind = d.count ? 'ok' : 'warn';
      mapRender();
      window.toast && window.toast('端口映射已保存', 'ok');
    } catch (e) {
      MAP.msg = e.message || String(e);              // 后端已把逐条校验明细拼进 error
      MAP.kind = 'err';
      mapRender();
      window.toast && window.toast('保存失败: ' + (e.message || e), 'err');
    } finally {
      MAP.busy = false; mapBusyUI(false); mapState();
    }
  }

  async function mapGen() {
    if (MAP.busy) return;
    if (MAP.dirty) {
      MAP.msg = '有未保存的修改 — 先「保存到面板」,再生成下发命令';
      MAP.kind = 'warn'; mapRender();
      return;
    }
    if (!MAP.loaded) return;
    MAP.busy = true; mapBusyUI(true); mapState();
    try {
      const d = await CM.api.get('/relayhub/frpc-config?name=' + encodeURIComponent(MAP.dev));
      MAP.cmd = String(d.apply || '').trim();
      MAP.warn = d.warning || '';
      MAP.msg = MAP.cmd ? '' : '该设备没有映射配置,不生成下发命令(以免误清空现有隧道)';
      MAP.kind = MAP.cmd ? '' : 'warn';
    } catch (e) {
      MAP.msg = '生成失败: ' + e.message;
      MAP.kind = 'err';
    } finally {
      MAP.busy = false; mapBusyUI(false); mapRender();
    }
  }

  async function mapCopy() {
    const txt = MAP.cmd || '';
    if (!txt) return;
    try {
      await navigator.clipboard.writeText(txt);
      window.toast && window.toast('命令已复制到剪贴板', 'ok');
      return;
    } catch (_) { /* 无剪贴板权限(http 非安全上下文),降级为选中文本 */ }
    const pre = document.getElementById('rh-map-cmdtext');
    if (pre && window.getSelection && document.createRange) {
      const rg = document.createRange();
      rg.selectNodeContents(pre);
      const sel = window.getSelection();
      sel.removeAllRanges(); sel.addRange(rg);
      let ok = false;
      try { ok = document.execCommand('copy'); } catch (_) {}
      window.toast && window.toast(ok ? '命令已复制' : '已选中,请按 Ctrl+C 复制', ok ? 'ok' : 'info');
    } else {
      window.toast && window.toast('请手动选中命令复制', 'info');
    }
  }


  /* ===== 端口池配置(全局卡): config.json port_pool → frps allowPorts / ufw / 分配策略
     单一可信源在服务端;这里只做编辑 → 校验预览(diff)→ 一键应用 → 公网自检。 */
  const POOL = {
    open: false, loaded: false, busy: false, st: null, draft: null, baseSig: '',
    preview: null, previewStale: false, probe: null, applyr: null, msg: '', kind: '',
  };
  const POOL_POLICIES = { sequential: '顺序分配', random: '随机分配', least_used: '最少使用' };

  function poolSig(p) {
    if (!p) return '';
    return JSON.stringify([(p.ranges || []).map((r) => [r.label, +r.start, +r.end]),
      (p.exclude || []).map(Number), p.alloc_policy, +p.max_per_client, !!p.sync_ufw]);
  }
  function poolDirty() { return !!POOL.draft && poolSig(POOL.draft) !== POOL.baseSig; }

  /* 绕过 CM.api:失败时也要拿到完整 payload(errors/steps/rollback_pool) */
  async function poolRaw(path, body) {
    const opts = {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'Authorization': 'Bearer ' + CM.token, 'Content-Type': 'application/json' },
    };
    if (body !== undefined) opts.body = JSON.stringify(body);
    let res;
    try { res = await fetch(CM.apiBase + path, opts); }
    catch (e) { return { status: 0, data: { ok: false, error: '网络错误: ' + e.message } }; }
    if (res.status === 401) {
      CM.token = '';
      try { localStorage.removeItem('cm_token'); } catch (_) {}
      CM.showLogin('会话已过期，请重新登录');
      return { status: 401, data: { ok: false, error: '未登录' } };
    }
    let data = {};
    try { data = await res.json(); } catch (_) {}
    return { status: res.status, data };
  }

  function poolFrpsMatch(st) {
    const t = st.frps_allowports || '';
    const eff = st.effective || [];
    if (!t || !eff.length) return false;
    const n = (t.match(/\{ start = /g) || []).length;
    return n === eff.length && eff.every((p) => t.indexOf('{ start = ' + p[0] + ', end = ' + p[1] + ' }') >= 0);
  }

  async function poolLoad() {
    const r = await poolRaw('/relayhub/port-pool');
    if (r.status !== 200 || !r.data || !r.data.pool) {
      POOL.msg = '读取端口池失败: '
        + ((r.data && (r.data.error || (r.data.errors || []).join('; '))) || ('HTTP ' + r.status));
      POOL.kind = 'err';
      poolRender();
      return;
    }
    POOL.st = r.data;
    POOL.draft = JSON.parse(JSON.stringify(r.data.pool));
    POOL.baseSig = poolSig(POOL.draft);
    POOL.loaded = true;
    if (POOL.msg.indexOf('读取') === 0) { POOL.msg = ''; POOL.kind = ''; }
    poolRender();
  }

  function poolWire() {
    const head = document.getElementById('rh-pool-head');
    const tg = document.getElementById('rh-pool-toggle');
    const applyOpen = () => {
      const body = document.getElementById('rh-pool-body');
      if (body) body.style.display = POOL.open ? 'block' : 'none';
      if (tg) tg.textContent = POOL.open ? '收起 ▲' : '展开 ▼';
      if (POOL.open) { if (!POOL.loaded) poolLoad().catch(() => {}); else poolRender(); }
    };
    if (head) head.onclick = () => { POOL.open = !POOL.open; applyOpen(); };
    applyOpen();
  }

  function poolPayload() {
    const d = POOL.draft || {};
    const p = {
      ranges: (d.ranges || []).map((r) => ({
        label: String(r.label || '').slice(0, 32),
        start: +r.start, end: +r.end,
      })),
      exclude: (d.exclude || []).map(Number).filter((x) => x > 0),
      alloc_policy: POOL_POLICIES[d.alloc_policy] ? d.alloc_policy : 'sequential',
      max_per_client: Math.max(1, Math.min(256, +d.max_per_client || 16)),
      sync_ufw: !!d.sync_ufw,
    };
    if (POOL.st && POOL.st.pool && POOL.st.pool.protected) p.protected = POOL.st.pool.protected;
    return p;
  }

  function poolDraftErrs() {
    const d = POOL.draft || {};
    const errs = [];
    if (!(d.ranges || []).length) errs.push('至少需要一个端口段');
    (d.ranges || []).forEach((r, i) => {
      const nm = r.label || ('段' + (i + 1));
      if (!/^\d+$/.test(String(r.start)) || !/^\d+$/.test(String(r.end))) {
        errs.push('端口段「' + nm + '」的起止端口必须是数字');
      } else if (+r.start > +r.end) {
        errs.push('端口段「' + nm + '」起止颠倒(' + r.start + ' > ' + r.end + ')');
      }
    });
    return errs;
  }

  async function poolPreview() {
    if (!POOL.draft || POOL.busy) return;
    const bad = poolDraftErrs();
    if (bad.length) {
      POOL.preview = { ok: false, errors: bad };
      POOL.previewStale = false;
      poolRender();
      return;
    }
    POOL.busy = true;
    poolRender();
    try {
      const r = await poolRaw('/relayhub/port-pool/preview', { pool: poolPayload() });
      POOL.preview = r.data || { ok: false, errors: ['HTTP ' + r.status] };
      POOL.previewStale = false;
      POOL.msg = ''; POOL.kind = '';
    } finally {
      POOL.busy = false;
      poolRender();
    }
  }

  async function poolApply(payload) {
    if (POOL.busy) return;
    POOL.busy = true;
    POOL.msg = '应用中…(备份 → 改写 frps.toml → 同步 ufw → 重启 frps, 约 5-30 秒)';
    POOL.kind = 'warn';
    poolRender();
    try {
      const r = await poolRaw('/relayhub/port-pool/apply', { pool: payload });
      POOL.applyr = r.data || {};
      if (POOL.applyr.ok) {
        POOL.msg = ''; POOL.kind = '';
        POOL.preview = null; POOL.previewStale = false;
        window.toast && window.toast('端口池已应用', 'ok');
        await poolLoad();
      } else {
        POOL.msg = ((POOL.applyr.errors || []).join('; ')
          || POOL.applyr.error || ('HTTP ' + r.status));
        POOL.kind = 'err';
      }
    } finally {
      POOL.busy = false;
      poolRender();
    }
  }

  async function poolApplyConfirm() {
    if (POOL.busy || !POOL.preview || !POOL.preview.ok || POOL.previewStale || poolDirty()) return;
    const d = POOL.preview.diff || {};
    const msg = '将生效:' + (d.after ? d.after.text : '') + '(' + (d.after ? d.after.count : '?') + ' 个端口)\n'
      + 'frps allowPorts:\n  ' + (d.before ? d.before.frps : '') + '\n  → ' + (d.after ? d.after.frps : '')
      + ((POOL.st && POOL.st.pool && POOL.st.pool.sync_ufw !== false) ? '\n并同步 ufw 规则(带注释标记回收旧规则)' : '')
      + '\n然后重启 frps(在线隧道自动重连;面板不重启)。继续?';
    if (!confirm(msg)) return;
    await poolApply(poolPayload());
  }

  async function poolProbe() {
    if (POOL.busy) return;
    POOL.busy = true;
    POOL.msg = '公网自检中…(从服务器反打自己的公网 IP, 最多约 10 秒)';
    POOL.kind = 'warn';
    poolRender();
    try {
      const r = await poolRaw('/relayhub/port-pool/probe', {});
      if (r.data && r.data.ok) {
        POOL.probe = r.data;
        POOL.msg = ''; POOL.kind = '';
      } else {
        POOL.msg = '公网自检失败: ' + ((r.data && r.data.error) || ('HTTP ' + r.status));
        POOL.kind = 'err';
      }
    } finally {
      POOL.busy = false;
      poolRender();
    }
  }

  function poolPreviewHTML() {
    const pv = POOL.preview;
    if (!pv) return '';
    const d = pv.diff || {};
    const stale = POOL.previewStale;
    return `
      <div class="rh-poolbox ${pv.ok ? 'ok' : 'err'}">
        <h5>${pv.ok ? (stale ? '⚠ 预览已过期(配置有改动, 请重新校验)' : '✓ 校验通过 — 确认 diff 后点「② 应用到服务端」') : '✗ 校验未通过'}</h5>
        ${(pv.errors || []).map((e) => '<div>· ' + esc(e) + '</div>').join('')}
        ${pv.ok && !stale ? `
        <div class="rh-poolstep"><b>生效范围</b><span>${esc(d.before.text)} → <b>${esc(d.after.text)}</b>(${d.before.count} → ${d.after.count} 个端口)</span></div>
        <div class="rh-poolstep"><b>frps allowPorts</b><span class="rh-poolarrow">${esc(d.before.frps)}<br>→ ${esc(d.after.frps)}${d.after.max_per_client ? ' · maxPortsPerClient = ' + d.after.max_per_client : ''}</span></div>
        <div class="rh-poolstep"><b>ufw</b><span>移除 ${d.ufw_remove.length ? esc(d.ufw_remove.join(', ')) : '(无)'} · 新增 ${d.ufw_add.length ? esc(d.ufw_add.join(', ')) : '(无)'}</span></div>
        ${(d.tunnels_at_risk || []).length ? `<div class="rh-poolstep"><b style="color:var(--warn)">将失效隧道</b><span style="color:var(--warn)">${esc(d.tunnels_at_risk.map((x) => x.name + '(' + x.port + ')').join(', '))}</span></div>` : ''}
        ` : ''}
      </div>`;
  }

  function poolProbeHTML() {
    const pr = POOL.probe;
    if (!pr) return '';
    const rows = (pr.results || []).map((x) => `
      <div class="rh-poolstep"><b style="min-width:64px;font-family:var(--mono)">${x.port}</b><span style="min-width:104px;color:${x.state === 'blocked' ? 'var(--bad)' : x.state === 'open' ? 'var(--ok)' : 'var(--info)'}">${x.state === 'open' ? '通 · 隧道在线' : x.state === 'refused' ? '放行 ✓ 无监听' : '超时 ✗ 被拦截'}</span><span class="muted small">${esc(x.note)}${x.ms != null ? ' · ' + x.ms + 'ms' : ''}</span></div>`).join('');
    return `
      <div class="rh-poolbox ${pr.covered ? 'ok' : 'err'}">
        <h5>${pr.covered ? '✓ 云安全组已覆盖所测端口' : '✗ ' + esc(pr.note)}</h5>
        <div class="muted small">目标 ${esc(pr.host)} · 采样 ${(pr.results || []).length} 个端口 · ${pr.took_ms}ms。从服务器反打自己的公网 IP 会穿越云安全组:超时 = 被安全组或 ufw 拦截,需到云控制台放行该段。</div>
        ${rows}
      </div>`;
  }

  function poolApplyHTML() {
    const ap = POOL.applyr;
    if (!ap) return '';
    const steps = (ap.steps || []).map((s) => `
      <div class="rh-poolstep"><b>${esc(s.step)}</b><span style="color:${s.ok ? 'var(--ok)' : 'var(--bad)'}">${s.ok ? '✓' : '✗'}</span><span class="muted small">${esc(s.detail || '')}</span></div>`).join('');
    const errs = (ap.errors || []).map((e) => '<div>· ' + esc(e) + '</div>').join('');
    const warn = (ap.warnings || []).map((w) => '<div style="color:var(--warn)">⚠ ' + esc(w) + '</div>').join('');
    return `
      <div class="rh-poolbox ${ap.ok ? 'ok' : 'err'}">
        <h5>${ap.ok ? '✓ 已应用到服务端' : '✗ 应用未完全成功'}</h5>
        ${errs}${steps}${warn}
        <div class="rh-pool-acts">
          ${ap.rollback_pool ? '<button class="btn btn-sm" id="rh-pool-rollback">↺ 回滚到应用前</button>' : ''}
          ${(ap.backups || []).length ? '<span class="muted small">备份: ' + esc(ap.backups.join(', ')) + '</span>' : ''}
        </div>
      </div>`;
  }

  function poolRender() {
    const badge = document.getElementById('rh-pool-badge');
    const cur = document.getElementById('rh-pool-cur');
    if (!badge || !cur) return;
    const st = POOL.st;
    if (!POOL.loaded) {
      badge.className = 'rh-pill' + (POOL.kind === 'err' ? ' bad' : '');
      badge.textContent = POOL.kind === 'err' ? '读取失败' : '读取中…';
      cur.textContent = '';
    } else {
      const same = poolFrpsMatch(st);
      badge.className = 'rh-pill ' + (same ? 'ok' : 'warn');
      badge.textContent = same ? '● 已生效' : '⚠ frps 与配置不一致';
      cur.textContent = (st.ranges_text || '') + ' · ' + st.port_count + ' 端口 · '
        + (POOL_POLICIES[st.pool.alloc_policy] || st.pool.alloc_policy) + ' · 每客户端 '
        + st.pool.max_per_client + ' 条' + (st.busy ? ' · 应用中…' : '');
    }
    const ps = document.getElementById('rh-pool-probestate');
    if (ps) {
      ps.style.display = POOL.probe ? '' : 'none';
      if (POOL.probe) {
        ps.style.color = POOL.probe.covered ? 'var(--ok)' : 'var(--bad)';
        ps.textContent = POOL.probe.covered
          ? ('公网自检 ✓ ' + (POOL.probe.summary.open + POOL.probe.summary.refused) + '/' + POOL.probe.results.length + ' 放行')
          : ('公网自检 ✗ ' + POOL.probe.summary.blocked + ' 端口超时');
      }
    }
    if (!POOL.open) return;
    const body = document.getElementById('rh-pool-body');
    if (!body) return;
    if (!POOL.loaded || !POOL.draft) {
      body.innerHTML = '<div class="muted small">加载中…</div>';
      return;
    }
    const d = POOL.draft;
    const dis = POOL.busy || (st && st.busy) ? ' disabled' : '';
    const rows = (d.ranges || []).map((r, i) => `
      <tr>
        <td><input class="inp rh-pool-in" data-i="${i}" data-f="label" value="${esc(r.label || '')}" placeholder="如 默认段"${dis}></td>
        <td><input class="inp rh-pool-in" data-i="${i}" data-f="start" inputmode="numeric" value="${esc(r.start == null ? '' : r.start)}" placeholder="起"${dis}></td>
        <td><input class="inp rh-pool-in" data-i="${i}" data-f="end" inputmode="numeric" value="${esc(r.end == null ? '' : r.end)}" placeholder="止"${dis}></td>
        <td style="text-align:right"><button class="btn btn-sm btn-danger rh-pool-del" data-i="${i}"${dis}>删除</button></td>
      </tr>`).join('');
    body.innerHTML = `
      <div class="rh-poolgrid">
        <div>
          <b class="small" style="display:block;margin-bottom:6px">端口段(可命名;池不得覆盖保护端口:${esc((st.protected || []).join(', '))})</b>
          <table class="rh-table">
            <thead><tr><th style="width:34%">段名</th><th>起始端口</th><th>结束端口</th><th></th></tr></thead>
            <tbody>${rows || '<tr><td colspan="4" class="muted small" style="padding:12px 8px">还没有端口段</td></tr>'}</tbody>
          </table>
          <div class="rh-pool-acts">
            <button class="btn btn-sm" id="rh-pool-add"${dis}>+ 添加端口段</button>
            <span class="muted small">占用中:${esc((st.used || []).join(', ') || '无')}</span>
          </div>
        </div>
        <div>
          <label>池内排除端口(逗号分隔;不分配、不放行)</label>
          <input class="inp rh-pool-in" id="rh-pool-excl" value="${esc((d.exclude || []).join(', '))}" placeholder="如 36000, 36005"${dis}>
          <label>分配策略</label>
          <select class="inp rh-pool-in" id="rh-pool-policy"${dis}>${Object.keys(POOL_POLICIES).map((k) => '<option value="' + k + '"' + (d.alloc_policy === k ? ' selected' : '') + '>' + POOL_POLICIES[k] + '</option>').join('')}</select>
          <label>每客户端(单设备)代理数上限 — 同步 frps maxPortsPerClient</label>
          <input class="inp rh-pool-in" id="rh-pool-mpc" inputmode="numeric" value="${esc(d.max_per_client)}"${dis}>
          <label style="display:flex;gap:7px;align-items:center;margin-top:12px;cursor:pointer">
            <input type="checkbox" id="rh-pool-ufw"${d.sync_ufw ? ' checked' : ''}${dis}>
            应用时同步 ufw(规则带注释标记,旧池规则自动回收)</label>
          <div class="muted small" style="margin-top:10px">流程:改配置 → ① 校验预览(只读,不落盘)→ ② 应用到服务端(备份 config/frps → 就地重载 → 改写 frps.toml → 同步 ufw → 重启 frps;面板不重启,隧道自动重连)。</div>
        </div>
      </div>
      <div class="rh-pool-acts">
        <button class="btn btn-sm btn-primary" id="rh-pool-prev"${dis}>① 校验预览</button>
        <button class="btn btn-sm" id="rh-pool-applybtn"${dis}>② 应用到服务端</button>
        <button class="btn btn-sm" id="rh-pool-probe"${dis}>公网自检安全组</button>
        <button class="btn btn-sm" id="rh-pool-reload"${dis}>刷新状态</button>
        <span class="muted small" id="rh-pool-dirty" style="display:none;color:var(--warn)">● 配置有改动,请重新「① 校验预览」</span>
      </div>
      ${POOL.msg ? `<div class="rh-poolbox ${POOL.kind || 'warn'}">${esc(POOL.msg)}</div>` : ''}
      ${poolPreviewHTML()}
      ${POOL.probe ? poolProbeHTML() : ''}
      ${POOL.applyr ? poolApplyHTML() : ''}`;
    wirePool(body);
  }

  function poolActionState() {
    const busy = POOL.busy || (POOL.st && POOL.st.busy);
    const stale = POOL.previewStale || poolDirty();
    const ap = document.getElementById('rh-pool-applybtn');
    if (ap) ap.disabled = !!busy || !POOL.preview || !POOL.preview.ok || stale;
    const hint = document.getElementById('rh-pool-dirty');
    if (hint) hint.style.display = stale ? '' : 'none';
    ['rh-pool-prev', 'rh-pool-probe', 'rh-pool-reload', 'rh-pool-add'].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.disabled = !!busy;
    });
    document.querySelectorAll('.rh-pool-del').forEach((b) => { b.disabled = !!busy; });
  }

  function poolEdited() {
    POOL.previewStale = true;
    poolActionState();
  }

  function wirePool(body) {
    body.querySelectorAll('.rh-pool-in[data-f]').forEach((el) => {
      const i = +el.dataset.i, f = el.dataset.f;
      el.addEventListener(el.tagName === 'SELECT' ? 'change' : 'input', () => {
        const r = (POOL.draft.ranges || [])[i];
        if (!r) return;
        r[f] = el.value;
        poolEdited();
      });
    });
    const ex = body.querySelector('#rh-pool-excl');
    if (ex) ex.addEventListener('input', () => {
      POOL.draft.exclude = ex.value.split(/[,，、;；\s]+/).map((x) => +x).filter((x) => x > 0);
      poolEdited();
    });
    const pol = body.querySelector('#rh-pool-policy');
    if (pol) pol.addEventListener('change', () => { POOL.draft.alloc_policy = pol.value; poolEdited(); });
    const mpc = body.querySelector('#rh-pool-mpc');
    if (mpc) mpc.addEventListener('input', () => { POOL.draft.max_per_client = mpc.value; poolEdited(); });
    const uf = body.querySelector('#rh-pool-ufw');
    if (uf) uf.addEventListener('change', () => { POOL.draft.sync_ufw = uf.checked; poolEdited(); });
    const add = body.querySelector('#rh-pool-add');
    if (add) add.onclick = () => {
      const rs = POOL.draft.ranges || (POOL.draft.ranges = []);
      const last = rs[rs.length - 1];
      rs.push({
        label: '新段' + (rs.length + 1),
        start: last && /^\d+$/.test(String(last.end)) ? +last.end + 1 : '',
        end: last && /^\d+$/.test(String(last.end)) ? +last.end + 100 : '',
      });
      POOL.previewStale = true;
      poolRender();
    };
    body.querySelectorAll('.rh-pool-del').forEach((b) => {
      b.onclick = () => { POOL.draft.ranges.splice(+b.dataset.i, 1); POOL.previewStale = true; poolRender(); };
    });
    const pv = body.querySelector('#rh-pool-prev');
    if (pv) pv.onclick = poolPreview;
    const ap = body.querySelector('#rh-pool-applybtn');
    if (ap) ap.onclick = poolApplyConfirm;
    const pb = body.querySelector('#rh-pool-probe');
    if (pb) pb.onclick = poolProbe;
    const rl = body.querySelector('#rh-pool-reload');
    if (rl) rl.onclick = () => {
      if (poolDirty() && !confirm('放弃未保存的修改并刷新状态?')) return;
      POOL.preview = null; POOL.previewStale = false;
      POOL.draft = null; POOL.loaded = false;
      poolLoad().catch(() => {});
    };
    const rb = body.querySelector('#rh-pool-rollback');
    if (rb) rb.onclick = () => {
      const src = POOL.applyr && POOL.applyr.rollback_pool;
      if (!src) return;
      if (!confirm('回滚到上一次应用前的端口池配置?同样会备份、改写 frps.toml 并重启 frps。')) return;
      poolApply(src);
    };
    poolActionState();
  }

  /* ===== 内网设备硬件性能(Agent 上报 → 面板聚合) =====
     数据流:设备 agent 每 500ms POST /api/agent/push → 服务端落 SQLite + WS 广播
     这里靠 WS 增量更新,只在结构签名变化时重建 DOM,与本页其余部分同一套约定。 */
  const HW = {
    devs: [], live: new Map(), alerts: null,
    hist: null, histDev: null, histRange: '10m', histAt: 0, hchart: null, hchartDev: null,
    open: false, builtFor: null, msg: '', kind: '', busy: false,
    cmd: '', cmdDev: '', token: '',
    reg: { id: '', name: '', note: '' },
    lastSync: 0,
  };
  const HW_RANGES = { '10m': '10 分钟', '1h': '1 小时', '6h': '6 小时', '24h': '24 小时', '7d': '7 天', '30d': '30 天' };
  const hwLive = (id) => HW.live.get(id) || null;

  function hwBindHub() {
    if (!CM.hub || CM._hwBound) return;
    CM._hwBound = true;
    const put = (dev, patch) => {
      if (!dev) return;
      const rec = HW.live.get(dev) || {};
      Object.assign(rec, patch, { at: Date.now(), online: true });
      HW.live.set(dev, rec);
      if (S.sel === dev) hwPaintLive(dev);
      hwPaintChip();
    };
    CM.hub.on('devmetrics', (m) => { if (m && m.data) put(m.data.dev, { light: m.data.light }); });
    CM.hub.on('devheavy', (m) => { if (m && m.data) put(m.data.dev, { heavy: m.data.heavy }); });
    CM.hub.on('devstatus', (m) => {
      if (!m || !m.data) return;
      const rec = HW.live.get(m.data.dev) || {};
      rec.online = !!m.data.online;
      rec.lastPush = m.data.lastPush;
      HW.live.set(m.data.dev, rec);
      if (S.sel === m.data.dev) hwPaintLive(m.data.dev);
      hwPaintChip();
    });
  }

  /* 服务端全量:设备注册表 + 阈值 + 每台设备最新样本(WS 断连时兜底) */
  async function hwSync(force) {
    const now = Date.now();
    if (!force && now - HW.lastSync < 15000) return;
    HW.lastSync = now;
    let d;
    try { d = await CM.api.get('/devices'); } catch (e) { return; }
    if (!d || !Array.isArray(d.devices)) return;
    HW.devs = d.devices;
    HW.alerts = d.alerts || HW.alerts;
    for (const dev of d.devices) {
      const rec = HW.live.get(dev.id) || {};
      if (dev.light) rec.light = dev.light;
      if (dev.heavy) rec.heavy = dev.heavy;
      rec.online = !!dev.online;
      rec.lastPush = dev.lastPush;
      rec.meta = dev;
      HW.live.set(dev.id, rec);
    }
    hwPaintChip();
    hwRenderMgmt();
    if (S.sel) hwPaintLive(S.sel);
  }

  function hwPaintChip() {
    const el = document.getElementById('rh-c-hw');
    if (!el) return;
    const total = HW.devs.length;
    const on = HW.devs.filter((d) => (hwLive(d.id) || {}).online).length;
    el.className = 'rh-chip' + (total && on === total ? ' ok' : '');
    el.querySelector('b').textContent = total ? (on + ' / ' + total) : '–';
    el.title = total ? '' : '尚未接入硬件性能设备';
  }

  function agoShort(ts) {
    if (!ts) return '无上报';
    const d = Math.max(0, Date.now() - ts) / 1000;
    if (d < 3) return '刚刚';
    if (d < 60) return Math.floor(d) + ' 秒前';
    if (d < 3600) return Math.floor(d / 60) + ' 分钟前';
    return Math.floor(d / 3600) + ' 小时前';
  }

  /* ---------- 实时硬件面板(设备详情内) ---------- */
  const GAUGES = [
    { id: 'cpu', label: 'CPU 使用率', ic: 'cpu' },
    { id: 'mem', label: '内存使用率', ic: 'memory' },
    { id: 'swap', label: 'Swap 使用率', ic: 'memory' },
    { id: 'disk', label: '磁盘占用', ic: 'disk' },
    { id: 'temp', label: 'SoC 温度', ic: 'activity' },
    { id: 'load', label: '负载 (1m)', ic: 'gauge' },
  ];

  function hwEmptyHTML(dev) {
    const known = HW.devs.some((d) => d.id === dev);
    return `
      <div class="hw-empty" style="padding:4px 0 2px">
        <b style="display:block;margin-bottom:6px;font-size:12.5px;color:var(--text)">硬件性能 · ${esc(dev)}</b>
        ${known
          ? '<span class="muted">该设备已注册但尚未收到上报,请检查设备上 cm-agent 服务:<br><span class="mono">systemctl status cm-agent</span></span>'
          : `<span class="muted">该设备暂未接入硬件性能采集。在下方「硬件性能设备」卡片中点击「接入命令」,在设备上执行一条命令即可完成安装(agent 主动外发,无需公网端口)。</span>`}
      </div>`;
  }

  function hwBuildLive(dev) {
    const box = document.getElementById('rh-hwlive');
    if (!box) return;
    HW.builtFor = dev;
    if (!hwLive(dev) || !(hwLive(dev).light || hwLive(dev).heavy)) {
      HW.liveDom = false;
      box.innerHTML = hwEmptyHTML(dev);
      box.removeAttribute('data-dom');
      return;
    }
    HW.liveDom = true;
    box.innerHTML = `
      <div class="hw-sec" style="margin-top:0">
        <div class="t">
          <b>硬件性能 · ${esc(dev)}</b>
          <span class="hw-badge" id="hw-badge">–</span>
          <span style="flex:1"></span>
          <span class="muted small" id="hw-meta">–</span>
        </div>
      </div>
      <div class="hw-gauges" id="hw-gauges">
        ${GAUGES.map((g) => `
          <div class="hw-g">
            <div class="lab">${icon(g.ic, { size: 13 })}${g.label}</div>
            <div class="val" id="hwv-${g.id}">–</div>
            <div class="sub" id="hws-${g.id}"></div>
            <div class="bar"><i id="hwb-${g.id}" style="width:0%"></i></div>
          </div>`).join('')}
      </div>
      <div class="hw-sec"><div class="t"><b>每核 CPU</b><span class="muted small" id="hw-freq"></span></div>
        <div class="hw-core" id="hw-cores"></div></div>
      <div class="hw-two">
        <div><div class="hw-sec" style="margin-top:0"><div class="t"><b>网络接口</b><span class="muted small" id="hw-netsum"></span></div>
          <table class="rh-table"><thead><tr><th>接口</th><th style="text-align:right">下行</th><th style="text-align:right">上行</th><th style="text-align:right">累计 ↓/↑</th><th style="text-align:right">错包</th></tr></thead>
          <tbody id="hw-net"></tbody></table></div>
          <div class="hw-sec"><div class="t"><b>磁盘挂载</b><span class="muted small" id="hw-io"></span></div>
          <table class="rh-table"><thead><tr><th>挂载点</th><th>类型</th><th style="text-align:right">容量</th><th style="text-align:right">已用</th><th style="text-align:right">可用</th></tr></thead>
          <tbody id="hw-disk"></tbody></table></div>
        </div>
        <div>
          <div class="hw-sec" style="margin-top:0"><div class="t"><b>系统信息</b></div><div class="hw-kv" id="hw-sys"></div></div>
          <div class="hw-sec"><div class="t"><b>健康状态</b></div><div id="hw-health"></div></div>
        </div>
      </div>
      <div class="hw-two">
        <div><div class="hw-sec" style="margin-top:0"><div class="t"><b>进程 Top(CPU)</b><span class="muted small" id="hw-pcount"></span></div>
          <table class="rh-table"><thead><tr><th style="width:46px">PID</th><th>进程</th><th style="text-align:right">CPU</th><th style="text-align:right">内存</th></tr></thead>
          <tbody id="hw-pcpu"></tbody></table></div></div>
        <div><div class="hw-sec" style="margin-top:0"><div class="t"><b>进程 Top(内存)</b></div>
          <table class="rh-table"><thead><tr><th style="width:46px">PID</th><th>进程</th><th style="text-align:right">RSS</th><th style="text-align:right">占比</th></tr></thead>
          <tbody id="hw-pmem"></tbody></table></div></div>
      </div>`;
    hwPaintLive(dev);
  }

  const hwSet = (id, v) => { const e = document.getElementById(id); if (e && e.textContent !== v) e.textContent = v; };
  const hwBar = (id, pct) => {
    const e = document.getElementById(id);
    if (!e) return;
    const p = Math.max(0, Math.min(100, pct || 0));
    if (e.style.width !== p.toFixed(1) + '%') e.style.width = p.toFixed(1) + '%';
    const col = p >= 90 ? 'var(--bad)' : p >= 75 ? 'var(--warn)' : 'var(--info)';
    if (e.style.background !== col) e.style.background = col;
  };

  const hwSigNet = (rows) => (rows || []).map((r) => r.name).join(',');
  const hwSigDisk = (m) => (m || []).map((x) => x.mount).join(',');
  const hwSigProc = (p) => (p || []).map((x) => x.pid + ':' + x.cmd).join('|');
  const hwSigSys = (s) => (s ? [s.host, s.os, s.kernel, s.arch, s.model, s.cores].join('|') : '');

  function hwPaintLive(dev) {
    const rec = hwLive(dev);
    const has = !!(rec && (rec.light || rec.heavy));
    /* 空态 → 数据首次到达时必须重建 DOM(否则只有占位文本) */
    if (HW.builtFor !== dev || (has && HW.liveDom === false)) { hwBuildLive(dev); return; }
    if (!has) return;
    const l = rec.light;
    const hv = rec.heavy || {};
    const sys = hv.sys || null;
    const health = hv.health || {};
    const meta = rec.meta || HW.devs.find((x) => x.id === dev) || {};

    const badge = document.getElementById('hw-badge');
    if (badge) {
      const on = rec.online !== false;
      badge.className = 'hw-badge ' + (on ? 'on' : 'off');
      const txt = on ? '● 上报中' : '○ 已失联';
      if (badge.textContent !== txt) badge.textContent = txt;
    }
    hwSet('hw-meta', `Agent v${meta.agentVer || '–'} · ` +
      `节拍 ${meta.intervalMs ? (meta.intervalMs + 'ms') : '–'} · 最后上报 ${agoShort(rec.lastPush)}`);

    if (l) {
      const cpu = l.cpu == null ? null : l.cpu;
      const mem = l.mem || {};
      const d = l.disk || {};
      const ld = (l.load || [])[0];
      hwSet('hwv-cpu', cpu == null ? '–' : cpu.toFixed(1) + '%');
      hwBar('hwb-cpu', cpu);
      hwSet('hws-cpu', (l.cores && l.cores.length ? l.cores.length + ' 核' : '') +
        (l.freq ? ' · ' + (l.freq / 1000).toFixed(2) + ' GHz' : ''));
      hwSet('hwv-mem', mem.usedPct == null ? '–' : mem.usedPct.toFixed(1) + '%');
      hwBar('hwb-mem', mem.usedPct);
      hwSet('hws-mem', mem.total ? (fmtB(mem.used) + ' / ' + fmtB(mem.total)) : '');
      hwSet('hwv-swap', mem.swapPct == null ? '–' : mem.swapPct.toFixed(1) + '%');
      hwBar('hwb-swap', mem.swapPct);
      hwSet('hws-swap', mem.swapTotal ? (fmtB(mem.swapUsed) + ' / ' + fmtB(mem.swapTotal)) : '未启用');
      hwSet('hwv-disk', d.pct == null ? '–' : d.pct.toFixed(1) + '%');
      hwBar('hwb-disk', d.pct);
      hwSet('hws-disk', d.top ? (d.top.mount + ' · 剩 ' + fmtB(d.top.avail)) : '');
      const t = l.temp;
      hwSet('hwv-temp', t == null ? '–' : t + '℃');
      hwBar('hwb-temp', t == null ? 0 : (t - 30) * 1.4);
      hwSet('hws-temp', t == null ? '无传感器' : (t >= 80 ? '过热' : t >= 65 ? '偏高' : '正常'));
      hwSet('hwv-load', ld == null ? '–' : ld.toFixed(2));
      hwBar('hwb-load', ld == null ? 0 : ld / Math.max(1, (l.cores || []).length || 1) * 100);
      hwSet('hws-load', (l.load || []).join(' / ') + (l.running != null ? ' · 运行 ' + l.running : ''));

      /* 每核条形:核数不变则原位改高度 */
      const cores = l.cores || [];
      const coreBox = document.getElementById('hw-cores');
      if (coreBox && coreBox.childElementCount !== cores.length) {
        coreBox.innerHTML = cores.map((v, i) =>
          `<div class="col"><div class="b" id="hwc-${i}" style="height:2%"></div><div class="n">C${i}</div></div>`).join('');
      }
      cores.forEach((v, i) => {
        const e = document.getElementById('hwc-' + i);
        if (e) { const h = Math.max(2, Math.min(100, v)).toFixed(0) + '%'; if (e.style.height !== h) e.style.height = h; }
      });
      hwSet('hw-freq', l.freq ? '当前频率 ' + (l.freq / 1000).toFixed(2) + ' GHz' + (l.procs ? ' · 进程数 ' + l.procs : '') : (l.procs ? '进程数 ' + l.procs : ''));

      /* 网络表(接口集合变化才重建) */
      const rows = ((l.net || {}).ifaces || []).slice().sort((a, b) => (b.rxBps + b.txBps) - (a.rxBps + a.txBps));
      const netT = document.getElementById('hw-net');
      if (netT) {
        const sig = hwSigNet(rows);
        if (netT.dataset.sig !== sig) {
          netT.dataset.sig = sig;
          netT.innerHTML = rows.map((r) => `
            <tr data-if="${esc(r.name)}">
              <td class="mono"><b>${esc(r.name)}</b></td>
              <td class="mono" style="text-align:right;color:var(--info)">–</td>
              <td class="mono" style="text-align:right;color:var(--accent2)">–</td>
              <td class="mono" style="text-align:right">–</td>
              <td class="mono" style="text-align:right">–</td>
            </tr>`).join('') || '<tr><td colspan="5" class="muted small">无接口数据</td></tr>';
        }
        for (const r of rows) {
          const tr = netT.querySelector('[data-if="' + CSS.escape(r.name) + '"]');
          if (!tr) continue;
          const c = tr.children;
          const v1 = fmtBps(r.rxBps), v2 = fmtBps(r.txBps);
          if (c[1].textContent !== v1) c[1].textContent = v1;
          if (c[2].textContent !== v2) c[2].textContent = v2;
          const tot = fmtB(r.rx) + ' / ' + fmtB(r.tx);
          if (c[3].textContent !== tot) c[3].textContent = tot;
          const err = (r.rxErr || 0) + (r.txErr || 0);
          c[4].textContent = err ? String(err) : '0';
          c[4].style.color = err ? 'var(--warn)' : '';
        }
      }
      hwSet('hw-netsum', `↓${fmtBps(l.net ? l.net.rx : 0)} · ↑${fmtBps(l.net ? l.net.tx : 0)}`);

      /* 磁盘挂载表 */
      const mounts = d.mounts || [];
      const dT = document.getElementById('hw-disk');
      if (dT && dT.dataset.sig !== hwSigDisk(mounts)) {
        dT.dataset.sig = hwSigDisk(mounts);
        dT.innerHTML = mounts.map((m) => `
          <tr data-mp="${esc(m.mount)}">
            <td class="mono"><b>${esc(m.mount)}</b>${m.inoPct != null && m.inoPct > 90 ? ' <span class="rh-pill warn">inode ' + m.inoPct + '%</span>' : ''}</td>
            <td>${esc(m.fs)}</td>
            <td class="mono" style="text-align:right">${fmtB(m.total)}</td>
            <td class="mono" style="text-align:right"><b id="hd-${esc(m.mount)}">–</b></td>
            <td class="mono" style="text-align:right">${fmtB(m.avail)}</td>
          </tr>`).join('') || '<tr><td colspan="5" class="muted small">无挂载数据</td></tr>';
      }
      for (const m of mounts) {
        const cell = dT && dT.querySelector('[data-mp="' + CSS.escape(m.mount) + '"] b');
        if (cell) {
          const v = m.pct + '% (' + fmtB(m.used) + ')';
          if (cell.textContent !== v) {
            cell.textContent = v;
            cell.style.color = m.pct >= 90 ? 'var(--bad)' : m.pct >= 75 ? 'var(--warn)' : '';
          }
        }
      }
      const io = d.io || {};
      hwSet('hw-io', `读 ${fmtBps(io.read || 0)} · 写 ${fmtBps(io.write || 0)}`);
    }

    /* 系统信息 + 健康状态(重数据,低频变化) */
    const sysBox = document.getElementById('hw-sys');
    if (sysBox && sys) {
      const sig = hwSigSys(sys);
      if (sysBox.dataset.sig !== sig) {
        sysBox.dataset.sig = sig;
        const rows = [
          ['主机名', sys.host], ['系统', sys.os], ['内核', sys.kernel],
          ['架构', sys.arch], ['硬件', sys.model || '未知'], ['核心数', sys.cores],
          ['内存', sys.memTotal ? fmtB(sys.memTotal) : '–'],
          ['运行时长', l && l.uptime ? fmt.uptime(l.uptime) : '–'],
          ['Agent', sys.agent ? 'v' + sys.agent : '–'],
        ].filter((r) => r[1] != null && r[1] !== '');
        sysBox.innerHTML = rows.map((r) => `<span class="k">${esc(r[0])}</span><span class="v">${esc(String(r[1]))}</span>`).join('');
      } else if (l && l.uptime) {
        const k = sysBox.querySelectorAll('.k');
        for (const el of k) if (el.textContent === '运行时长') {
          const v = el.nextElementSibling;
          const nv = fmt.uptime(l.uptime);
          if (v && v.textContent !== nv) v.textContent = nv;
        }
      }
    }

    const hBox = document.getElementById('hw-health');
    if (hBox) {
      const pills = [];
      const wifi = health.wifi;
      if (wifi) {
        const ok = wifi.signal >= -70;
        pills.push(`<span class="hw-pill ${ok ? 'ok' : 'warn'}">Wi-Fi ${wifi.iface} ${wifi.signal} dBm (${wifi.qualityPct}%)</span>`);
      }
      const th = health.throttle;
      if (th) pills.push(th.flags && th.flags.length
        ? `<span class="hw-pill ${th.flags.some((f) => f.indexOf('当前') === 0) ? 'bad' : 'warn'}" title="${esc(th.raw)}">${esc(th.flags.join('、'))}</span>`
        : `<span class="hw-pill ok" title="${esc(th.raw)}">供电与温度正常</span>`);
      const fu = health.failedUnits || [];
      if (fu.length) pills.push(`<span class="hw-pill bad">${fu.length} 个失败服务</span>`);
      const ro = health.roMounts || [];
      if (ro.length) pills.push(`<span class="hw-pill bad">只读挂载 ${esc(ro.join(', '))}</span>`);
      if (!fu.length) pills.push('<span class="hw-pill ok">systemd 无失败单元</span>');
      const journal = health.journal || [];
      const html = `<div class="hw-pills">${pills.join('')}</div>
        <div style="margin-top:8px">${journal.length
          ? journal.map((j) => `<div class="hw-jrow">${esc(j)}</div>`).join('')
          : '<div class="hw-empty">近 10 分钟 journal 无 error 级日志</div>'}</div>`;
      const sig = (fu.join(',') + '|' + ro.join(',') + '|' + journal.join('~') + '|' + (wifi ? wifi.signal : ''));
      if (hBox.dataset.sig !== sig) { hBox.dataset.sig = sig; hBox.innerHTML = html; }
    }

    /* 进程 Top 两表 */
    const pc = document.getElementById('hw-pcpu');
    const procs = hv.procs || [];
    if (pc && pc.dataset.sig !== hwSigProc(procs)) {
      pc.dataset.sig = hwSigProc(procs);
      pc.innerHTML = procs.map((p) => `
        <tr><td class="mono muted">${p.pid}</td>
          <td title="${esc(p.cmd)}" style="max-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(p.cmd)}</td>
          <td class="mono" style="text-align:right;color:${p.cpu >= 80 ? 'var(--bad)' : p.cpu >= 40 ? 'var(--warn)' : ''}">${p.cpu}%</td>
          <td class="mono" style="text-align:right">${p.memPct}%</td></tr>`).join('')
        || '<tr><td colspan="4" class="muted small">等待上报…</td></tr>';
    }
    const pm = document.getElementById('hw-pmem');
    const topMem = hv.topMem || [];
    if (pm && pm.dataset.sig !== hwSigProc(topMem)) {
      pm.dataset.sig = hwSigProc(topMem);
      pm.innerHTML = topMem.map((p) => `
        <tr><td class="mono muted">${p.pid}</td>
          <td title="${esc(p.cmd)}" style="max-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(p.cmd)}</td>
          <td class="mono" style="text-align:right">${fmtB(p.rss)}</td>
          <td class="mono" style="text-align:right">${p.memPct}%</td></tr>`).join('')
        || '<tr><td colspan="4" class="muted small">等待上报…</td></tr>';
    }
    hwSet('hw-pcount', hv.procCount ? '共 ' + hv.procCount + ' 个进程' : '');
  }

  /* ---------- 历史趋势 + CSV ---------- */
  async function hwLoadHist(dev, range) {
    if (!dev) return;
    HW.histDev = dev; HW.histRange = range || HW.histRange;
    HW.histAt = Date.now();
    let d;
    try { d = await CM.api.get('/devices/history?dev=' + encodeURIComponent(dev) + '&range=' + HW.histRange, 4000); }
    catch (e) { hwRenderHist(dev, { ok: false, reason: e.message }); return; }
    if (S.sel !== dev) return;                 /* 期间切换了设备 */
    HW.hist = d;
    hwRenderHist(dev, d);
  }

  function hwRenderHist(dev, d) {
    const box = document.getElementById('rh-hwhist');
    if (!box) return;
    const ranges = Object.keys(HW_RANGES).map((r) =>
      `<button data-r="${r}" class="${r === HW.histRange ? 'on' : ''}">${HW_RANGES[r]}</button>`).join('');
    if (box.dataset.dev !== dev) {
      box.dataset.dev = dev;
      box.innerHTML = `
        <div class="hw-sec" style="margin-top:0">
          <div class="t">
            <b>历史趋势 · ${esc(dev)}</b>
            <span class="hw-ranges" id="hw-ranges">${ranges}</span>
            <span class="muted small" id="hw-histmeta"></span>
            <button class="btn btn-sm" id="hw-csv">${icon('export', { size: 13 })} 导出 CSV</button>
          </div>
          <div class="rh-chartwrap" style="height:200px"><canvas id="hw-chart"></canvas></div>
          <div class="muted small" id="hw-histnote" style="margin-top:7px"></div>
        </div>`;
      box.querySelectorAll('#hw-ranges button').forEach((b) => (b.onclick = () => {
        box.querySelectorAll('#hw-ranges button').forEach((x) => x.classList.remove('on'));
        b.classList.add('on');
        HW.histRange = b.dataset.r;
        hwLoadHist(dev, HW.histRange);
      }));
      const csv = document.getElementById('hw-csv');
      if (csv) csv.onclick = () => {
        location.href = CM.apiBase + '/devices/csv?dev=' + encodeURIComponent(dev) +
          '&range=' + HW.histRange + '&token=' + encodeURIComponent(CM.token);
      };
      if (HW.hchart) { try { HW.hchart.destroy(); } catch (_) {} HW.hchart = null; }
      HW.hchartDev = null;
    }
    const note = document.getElementById('hw-histnote');
    const meta = document.getElementById('hw-histmeta');
    if (!d || !d.ok) {
      if (meta) meta.textContent = '';
      if (note) note.textContent = d && d.reason ? ('历史数据不可用: ' + d.reason) : '暂无历史数据(agent 上报后自动积累)';
      return;
    }
    if (meta) meta.textContent = `${d.total} 个采样点 · 粒度 ${d.stepMs}ms${d.agg ? ' · 已聚合' : ''}`;
    const pts = d.points || [];
    if (note) note.textContent = pts.length
      ? `平均 CPU ${d.avgCpu != null ? d.avgCpu + '%' : '–'} · 峰值 CPU ${d.maxCpu != null ? d.maxCpu + '%' : '–'} · 最高温度 ${d.maxTemp != null ? d.maxTemp + '℃' : '–'}`
      : '该时间范围内暂无采样';
    const cv = document.getElementById('hw-chart');
    if (!cv || !window.Chart) return;
    const labels = pts.map((p) => tl(p.t));
    const mk = (key, label, color, axis) => ({
      label, data: pts.map((p) => p[key]),
      borderColor: color, backgroundColor: color.replace(')', ',.10)').replace('rgb', 'rgba'),
      fill: false, tension: .3, pointRadius: 0, borderWidth: 1.8, yAxisID: axis || 'y',
    });
    const datasets = [
      Object.assign(mk('c', 'CPU %', 'rgb(56,189,248)'), { fill: true }),
      Object.assign(mk('m', '内存 %', 'rgb(122,155,255)'), {}),
      Object.assign(mk('d', '磁盘 %', 'rgb(52,211,153)'), {}),
      Object.assign(mk('tp', '温度 ℃', 'rgb(251,191,36)'), { yAxisID: 'y1' }),
    ];
    if (HW.hchart && HW.hchartDev === dev && HW.hchart.canvas === cv) {
      HW.hchart.data.labels = labels;
      datasets.forEach((ns, i) => { if (HW.hchart.data.datasets[i]) HW.hchart.data.datasets[i].data = ns.data; });
      HW.hchart.update('none');
    } else {
      if (HW.hchart) { try { HW.hchart.destroy(); } catch (_) {} }
      HW.hchartDev = dev;
      HW.hchart = new Chart(cv.getContext('2d'), {
        type: 'line',
        data: { labels, datasets },
        options: {
          responsive: true, maintainAspectRatio: false, animation: false, interaction: { mode: 'index', intersect: false },
          plugins: {
            legend: { labels: { color: '#9aa7b8', boxWidth: 11, font: { size: 10.5 } } },
            tooltip: { callbacks: { label: (c) => c.dataset.label + ' ' + (c.parsed.y == null ? '–' : (+c.parsed.y).toFixed(1)) } },
          },
          scales: {
            x: { ticks: { color: '#7a8899', font: { size: 10 }, maxTicksLimit: 7 }, grid: { color: 'rgba(255,255,255,.05)' } },
            y: { position: 'left', min: 0, max: 100, ticks: { color: '#7a8899', font: { size: 10 }, callback: (v) => v + '%' }, grid: { color: 'rgba(255,255,255,.05)' } },
            y1: { position: 'right', min: 0, suggestedMax: 90, ticks: { color: '#b08900', font: { size: 10 }, callback: (v) => v + '℃' }, grid: { drawOnChartArea: false } },
          },
        },
      });
    }
  }

  /* ---------- 设备接入 / 阈值管理(页面底部卡片) ---------- */
  function hwWire() {
    const head = document.getElementById('rh-hw-head');
    const tg = document.getElementById('rh-hw-toggle');
    const body = document.getElementById('rh-hw-body');
    const apply = () => {
      if (body) body.style.display = HW.open ? 'block' : 'none';
      if (tg) tg.textContent = HW.open ? '收起 ▲' : '展开 ▼';
      if (HW.open) hwRenderMgmt();
    };
    if (head) head.onclick = () => { HW.open = !HW.open; apply(); };
    apply();
  }

  function hwRenderMgmt() {
    const badge = document.getElementById('rh-hw-badge');
    const sum = document.getElementById('rh-hw-sum');
    const body = document.getElementById('rh-hw-body');
    if (badge) {
      badge.className = 'rh-pill ' + (HW.devs.length ? 'ok' : '');
      badge.textContent = HW.devs.length ? (HW.devs.filter((d) => (hwLive(d.id) || {}).online).length + ' 台上报中') : '未接入';
    }
    if (sum) sum.textContent = HW.devs.length
      ? '采样 500ms · 阈值 ' + (HW.alerts ? `CPU ${HW.alerts.cpu}% / 内存 ${HW.alerts.mem}% / 磁盘 ${HW.alerts.disk}% / 温度 ${HW.alerts.temp}℃ / 失联 ${HW.alerts.offlineSec}s` : '读取中')
      : '在设备上执行一条命令即可接入(主动外发,无需公网端口)';
    if (!HW.open || !body) return;
    /* 用户正在表单里输入时跳过本轮重建(15s 一次的同步会把光标和输入内容清掉) */
    if (body.contains(document.activeElement)) return;

    const a = HW.alerts || {};
    const devRows = HW.devs.map((d) => {
      const rec = hwLive(d.id) || {};
      const on = rec.online !== false;
      const l = rec.light;
      return `<tr>
        <td class="mono"><b>${esc(d.id)}</b><div class="muted small" style="font-family:var(--mono)">${esc(d.name || '')}</div></td>
        <td><span class="hw-pill ${on ? 'ok' : 'bad'}">${on ? '● 上报中' : '○ 失联'}</span></td>
        <td class="mono" style="text-align:right">${l && l.cpu != null ? l.cpu.toFixed(0) + '%' : '–'}</td>
        <td class="mono" style="text-align:right">${l && l.mem ? l.mem.usedPct.toFixed(0) + '%' : '–'}</td>
        <td class="mono" style="text-align:right">${l && l.temp != null ? l.temp + '℃' : '–'}</td>
        <td class="muted small">${agoShort(rec.lastPush)}</td>
        <td style="text-align:right;white-space:nowrap">
          <button class="btn btn-sm" data-cmd="${esc(d.id)}">接入命令</button>
          <button class="btn btn-sm" data-hist="${esc(d.id)}">历史</button>
          <button class="btn btn-sm btn-danger" data-del="${esc(d.id)}">删除</button>
        </td></tr>`;
    }).join('');

    body.innerHTML = `
      ${HW.msg ? `<div class="rh-mapmsg ${HW.kind}">${esc(HW.msg)}</div>` : ''}
      <table class="rh-table">
        <thead><tr><th>设备 ID</th><th>状态</th><th style="text-align:right">CPU</th><th style="text-align:right">内存</th><th style="text-align:right">温度</th><th>最后上报</th><th style="text-align:right">操作</th></tr></thead>
        <tbody>${devRows || '<tr><td colspan="7" class="muted small" style="padding:15px 8px">还没有设备接入硬件性能采集</td></tr>'}</tbody>
      </table>
      ${HW.cmd ? `
      <div class="rh-mapcmd">
        <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:7px">
          <b style="font-size:12px">在设备 <span class="mono">${esc(HW.cmdDev)}</span> 上以 root 执行一次(安装并启动 cm-agent)</b>
          <button class="btn btn-sm" id="hw-copy">复制命令</button>
        </div>
        <pre id="hw-cmdtext">${esc(HW.cmd)}</pre>
        <div class="muted small" style="margin-top:7px">命令含该设备专属 token,请勿外泄。执行后面板约 1 秒内开始收到数据。</div>
      </div>` : ''}
      <div class="rh-mapfoot" style="margin-top:13px">
        <b style="font-size:12.5px">接入新设备</b>
        <input class="inp rh-map-in" id="hw-newid" style="width:150px" placeholder="设备ID(小写)" value="${esc(HW.reg.id)}">
        <input class="inp rh-map-in" id="hw-newname" style="width:170px" placeholder="显示名称(可选)" value="${esc(HW.reg.name)}">
        <button class="btn btn-sm btn-primary" id="hw-add"${HW.busy ? ' disabled' : ''}>注册并生成命令</button>
        <span style="flex:1"></span>
      </div>
      <div class="rh-mapfoot" style="margin-top:15px;border-top:1px solid var(--border);padding-top:13px">
        <b style="font-size:12.5px">全局告警阈值</b>
        <label class="muted small" style="margin:0">CPU</label><input class="inp rh-map-in" id="hw-t-cpu" style="width:66px" value="${esc(a.cpu)}">
        <label class="muted small" style="margin:0">内存</label><input class="inp rh-map-in" id="hw-t-mem" style="width:66px" value="${esc(a.mem)}">
        <label class="muted small" style="margin:0">磁盘</label><input class="inp rh-map-in" id="hw-t-disk" style="width:66px" value="${esc(a.disk)}">
        <label class="muted small" style="margin:0">温度℃</label><input class="inp rh-map-in" id="hw-t-temp" style="width:66px" value="${esc(a.temp)}">
        <label class="muted small" style="margin:0">失联秒</label><input class="inp rh-map-in" id="hw-t-off" style="width:74px" value="${esc(a.offlineSec)}">
        <label class="muted small" style="margin:0;display:flex;gap:5px;align-items:center"><input type="checkbox" id="hw-t-en"${a.enabled === false ? '' : ' checked'}>启用</label>
        <button class="btn btn-sm btn-primary" id="hw-t-save"${HW.busy ? ' disabled' : ''}>保存阈值</button>
      </div>`;

    body.querySelectorAll('[data-cmd]').forEach((b) => (b.onclick = () => hwInstall(b.dataset.cmd)));
    body.querySelectorAll('[data-hist]').forEach((b) => (b.onclick = () => {
      S.sel = b.dataset.hist; S.built = null; HW.histDev = null;
      render(true);
      const box = document.getElementById('rh-detail');
      if (box) box.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }));
    body.querySelectorAll('[data-del]').forEach((b) => (b.onclick = () => hwRemove(b.dataset.del)));
    const add = document.getElementById('hw-add');
    if (add) add.onclick = hwAdd;
    const sv = document.getElementById('hw-t-save');
    if (sv) sv.onclick = hwSaveAlerts;
    const cp = document.getElementById('hw-copy');
    if (cp) cp.onclick = () => {
      navigator.clipboard.writeText(HW.cmd).then(
        () => window.toast && window.toast('命令已复制', 'ok'),
        () => window.toast && window.toast('复制失败,请手动选中', 'warn'));
    };
    ['id', 'name'].forEach((k) => {
      const el = document.getElementById(k === 'id' ? 'hw-newid' : 'hw-newname');
      if (el) el.oninput = () => { HW.reg[k] = el.value; };
    });
  }

  async function hwAdd() {
    const id = String(HW.reg.id || '').trim().toLowerCase();
    if (!/^[a-z0-9][a-z0-9_-]{1,31}$/.test(id)) {
      HW.msg = '设备 ID 需 2-32 位小写字母/数字/-/_'; HW.kind = 'err'; hwRenderMgmt(); return;
    }
    HW.busy = true; hwRenderMgmt();
    try {
      await CM.api.post('/devices/register', { id, name: HW.reg.name || id });
      HW.reg = { id: '', name: '', note: '' };
      HW.msg = '已注册,正在生成接入命令…'; HW.kind = 'ok';
      await hwInstall(id);
      await hwSync(true);
    } catch (e) {
      HW.msg = '注册失败: ' + e.message; HW.kind = 'err';
    } finally { HW.busy = false; hwRenderMgmt(); }
  }

  async function hwInstall(id) {
    try {
      const d = await CM.api.get('/devices/install?id=' + encodeURIComponent(id));
      HW.cmd = d.cmd || ''; HW.cmdDev = id; HW.token = d.token || '';
      HW.msg = ''; HW.kind = '';
      HW.open = true; hwRenderMgmt();
      const body = document.getElementById('rh-hw-body');
      if (body) { body.style.display = 'block'; const t = document.getElementById('rh-hw-toggle'); if (t) t.textContent = '收起 ▲'; }
    } catch (e) { HW.msg = '生成失败: ' + e.message; HW.kind = 'err'; hwRenderMgmt(); }
  }

  async function hwRemove(id) {
    if (!confirm(`删除设备 ${id} ?\n其历史采样数据将一并清除,设备上的 agent 会因 token 失效而停止上报。`)) return;
    HW.busy = true; hwRenderMgmt();
    try {
      await CM.api.post('/devices/remove', { id });
      HW.live.delete(id);
      HW.msg = '设备 ' + id + ' 已删除'; HW.kind = 'ok';
      if (HW.cmdDev === id) { HW.cmd = ''; HW.cmdDev = ''; }
      await hwSync(true);
      if (S.sel === id) { S.built = null; HW.builtFor = null; HW.histDev = null; }
    } catch (e) { HW.msg = '删除失败: ' + e.message; HW.kind = 'err'; }
    finally { HW.busy = false; hwRenderMgmt(); }
  }

  async function hwSaveAlerts() {
    const num = (id, min, max) => {
      const el = document.getElementById(id);
      const v = el ? parseInt(String(el.value).replace(/\D/g, ''), 10) : NaN;
      return Number.isFinite(v) ? Math.max(min, Math.min(max, v)) : null;
    };
    const body = {
      cpu: num('hw-t-cpu', 1, 100), mem: num('hw-t-mem', 1, 100),
      disk: num('hw-t-disk', 1, 100), temp: num('hw-t-temp', 30, 110),
      offlineSec: num('hw-t-off', 15, 3600),
    };
    const en = document.getElementById('hw-t-en');
    body.enabled = en ? en.checked : true;
    for (const k of ['cpu', 'mem', 'disk', 'temp', 'offlineSec']) if (body[k] == null) { HW.msg = '阈值必须是数字'; HW.kind = 'err'; hwRenderMgmt(); return; }
    HW.busy = true; hwRenderMgmt();
    try {
      const r = await CM.api.post('/devices/alerts', { alerts: body });
      HW.alerts = r.alerts;
      HW.msg = '阈值已保存,超限时将推送到告警铃铛与已配置的通知渠道'; HW.kind = 'ok';
      await hwSync(true);
    } catch (e) { HW.msg = '保存失败: ' + e.message; HW.kind = 'err'; }
    finally { HW.busy = false; hwRenderMgmt(); }
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
      HW.builtFor = null;          /* 详情 DOM 被整体重建,硬件卡片必须重画 */
      HW.histDev = null;           /* 历史卡片同理(其 canvas 也随之销毁) */
      if (HW.hchart) { try { HW.hchart.destroy(); } catch (_) {} HW.hchart = null; HW.hchartDev = null; }
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
        <div class="rh-card" id="rh-hwlive"></div>
        <div class="rh-card" id="rh-hwhist"></div>
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
        </div>
        <div class="rh-card">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:6px">
            <b>端口映射(单台设备可中继多个端口)</b>
            <span class="muted small" id="rh-map-ranges">读取允许范围…</span>
          </div>
          <div class="muted small" style="margin-bottom:9px">
            左列是设备上要暴露的服务端口,右列是中继服务器对公网开放的端口。保存只写入面板;需在设备执行「生成下发命令」给出的那行命令才真正生效。
          </div>
          <div id="rh-map-body"><div class="muted small">加载中…</div></div>
        </div>`;

      mapLoad(sel.name);
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
      const labels = hist.map((h) => tl(h.t));
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

    /* ---- 内网设备硬件性能(Agent 上报) ----
       结构在 S.built 变化时重建,此后每拍只原位改数值;
       历史曲线按 range 惰性拉取,60s 刷新一次即可(实时值走 WS)。 */
    try {
      hwPaintLive(sel.name);                       /* builtFor 变化时会自动重建 */
      if (HW.histDev !== sel.name) hwLoadHist(sel.name, HW.histRange);
      else if (Date.now() - HW.histAt > 60000) hwLoadHist(sel.name, HW.histRange);
      hwSync().catch(() => {});
    } catch (e) { console.error('hw render:', e); }
  }
})();
