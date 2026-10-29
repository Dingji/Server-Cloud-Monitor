/* RelayHub · 内网中继中心 — 前端逻辑 */
"use strict";

const $ = (s, el) => (el || document).querySelector(s);
const $$ = (s, el) => Array.from((el || document).querySelectorAll(s));

/* ---------------- 工具 ---------------- */
function fmtBytes(n) {
  if (n == null || isNaN(n)) return "–";
  const u = ["B", "KB", "MB", "GB", "TB", "PB"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return (i === 0 ? n.toFixed(0) : n.toFixed(n >= 100 ? 0 : n >= 10 ? 1 : 2)) + " " + u[i];
}
function fmtBw(n) { return fmtBytes(n) + "/s"; }
function fmtNum(n) { return n == null ? "–" : n.toLocaleString("zh-CN"); }
function fmtUptime(sec) {
  if (!sec && sec !== 0) return "–";
  const d = Math.floor(sec / 86400), h = Math.floor(sec % 86400 / 3600), m = Math.floor(sec % 3600 / 60);
  return d > 0 ? `${d}天${h}小时` : h > 0 ? `${h}小时${m}分` : `${m}分钟`;
}
function timeAgo(str, nowTs) {
  if (!str) return "–";
  if (/^\d{2}-\d{2} \d{2}:\d{2}/.test(str)) str = new Date().getFullYear() + "-" + str;
  const t = new Date(str.replace(" ", "T")).getTime();
  if (isNaN(t)) return str;
  const diff = Math.max(0, (nowTs || Date.now() / 1000) * 1000 - t) / 1000;
  if (diff < 60) return Math.floor(diff) + " 秒前";
  if (diff < 3600) return Math.floor(diff / 60) + " 分钟前";
  if (diff < 86400) return Math.floor(diff / 3600) + " 小时前";
  return Math.floor(diff / 86400) + " 天前";
}
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
function toast(msg, ok = true) {
  const el = document.createElement("div");
  el.className = "toast " + (ok ? "ok" : "err");
  el.innerHTML = `<span class="dot"></span><span>${esc(msg)}</span>`;
  $("#toast-wrap").appendChild(el);
  setTimeout(() => { el.style.transition = "opacity .3s"; el.style.opacity = "0"; setTimeout(() => el.remove(), 320); }, 2600);
}
async function copyText(text) {
  try { await navigator.clipboard.writeText(text); toast("已复制到剪贴板"); }
  catch (e) {
    const ta = document.createElement("textarea");
    ta.value = text; document.body.appendChild(ta); ta.select();
    document.execCommand("copy"); ta.remove(); toast("已复制到剪贴板");
  }
}

/* ---------------- API ---------------- */
async function api(path, opts) {
  const r = await fetch(path, Object.assign({ credentials: "same-origin" }, opts));
  if (r.status === 401 && path !== "api/login") { showLogin(); throw new Error("unauthorized"); }
  if (r.status === 304) return { __notModified: true };   // ETag 命中,数据未变
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.error || r.statusText), { status: r.status });
  return j;
}

/* ---------------- 全局状态 ---------------- */
const S = { authed: false, data: null, page: "overview", timer: null, charts: {}, relaySel: null, relayBuilt: null };
/* 结构签名:内容不变就不重建 DOM,避免每次轮询重放入场动画(卡顿与"下坠"的根源) */
const SIG = { relayList: null, deviceGrid: null, probeRows: null };
const PAGE_TITLES = { overview: "概览", relay: "中继管理", devices: "设备管理", proxies: "代理列表", guide: "接入指南" };
const PAGE_CHARTS = { overview: ["traffic", "conns"], relay: ["rad"] };

function showLogin() {
  S.authed = false;
  stopPolling();
  $("#app").classList.add("hidden");
  $("#login-view").classList.remove("hidden");
  setTimeout(() => $("#login-password").focus(), 50);
}
function showApp() {
  S.authed = true;
  $("#login-view").classList.add("hidden");
  $("#app").classList.remove("hidden");
  startPolling();
}

/* ---------------- 登录 ---------------- */
$("#login-form").addEventListener("submit", async e => {
  e.preventDefault();
  const btn = $("#login-btn"), errEl = $("#login-err");
  btn.disabled = true; btn.textContent = "验证中…"; errEl.textContent = "";
  try {
    await api("api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password: $("#login-password").value })
    });
    $("#login-password").value = "";
    showApp(); refresh(true);
    toast("欢迎回来");
  } catch (err) {
    errEl.textContent = err.message;
    const card = $(".login-card");
    card.classList.remove("shake"); void card.offsetWidth; card.classList.add("shake");
  } finally {
    btn.disabled = false; btn.textContent = "进入面板";
  }
});
$("#logout-btn").addEventListener("click", async () => {
  try { await api("api/logout", { method: "POST" }); } catch (e) {}
  showLogin();
});

/* ---------------- 路由 ---------------- */
function resizePageCharts(page) {
  requestAnimationFrame(() => {
    (PAGE_CHARTS[page] || []).forEach(k => {
      const c = S.charts[k];
      if (c && !c.isDisposed()) c.resize();
    });
  });
}
function nav(page) {
  if (!PAGE_TITLES[page]) page = "overview";
  S.page = page;
  location.hash = "#/" + page;
  $$(".page").forEach(p => p.classList.add("hidden"));
  $("#page-" + page).classList.remove("hidden");
  $$(".nav-item").forEach(a => a.classList.toggle("active", a.dataset.page === page));
  $("#page-title").textContent = PAGE_TITLES[page];
  render();
  resizePageCharts(page);   // 页面刚显示,ECharts 需按新尺寸重排
}
window.addEventListener("hashchange", () => {
  const p = location.hash.replace(/^#\//, "") || "overview";
  if (p !== S.page) nav(p);
});

/* ---------------- 轮询 ---------------- */
function startPolling() { stopPolling(); refresh(true); S.timer = setInterval(() => { if (!document.hidden) refresh(false); }, 500); tickClock(); }
function stopPolling() { if (S.timer) clearInterval(S.timer); S.timer = null; }
function tickClock() {
  const el = $("#clock");
  if (el) el.textContent = new Date().toLocaleTimeString("zh-CN", { hour12: false });
}
setInterval(tickClock, 1000);

let consecutiveErr = 0;
let refreshing = false;          // 500ms 高频轮询下的在途请求护栏
async function refresh(first) {
  if (!S.authed || refreshing) return;
  refreshing = true;
  try {
    let d;
    try { d = await api("api/summary"); consecutiveErr = 0; }
    catch (e) {
      if (e.message === "unauthorized") return;
      if (++consecutiveErr === 2) setConn("bad", "数据获取失败");
      return;
    }
    if (d.__notModified) return;   // ETag 304:数据无变化,跳过重绘
    S.data = d;
    setConn(d.frps_ok ? "ok" : "bad", d.frps_ok ? "中继正常" : "frps 不可达");
    render();
  } finally {
    refreshing = false;
  }
}

function setConn(state, text) {
  const pill = $("#conn-pill");
  pill.classList.toggle("ok", state === "ok");
  pill.classList.toggle("bad", state === "bad");
  pill.querySelector(".dot").className = "dot " + (state === "ok" ? "dot-ok" : "dot-bad");
  $("#conn-text").textContent = text;
}

/* ---------------- 渲染 ---------------- */
function render() {
  const d = S.data;
  if (!d) return;
  const { meta } = d;
  $("#endpoint-text").textContent = meta.public_addr + ":" + meta.frps_bind_port;
  $("#nav-device-count").textContent = d.devices.filter(x => x.status === "online").length + "/" + d.devices.length;
  $("#nav-proxy-count").textContent = d.proxies.filter(p => p.status === "online").length + "/" + d.proxies.length;
  // 只渲染当前页,隐藏页的图表/列表不再每 3 秒空转重绘
  if (S.page === "overview") renderOverview(d);
  else if (S.page === "relay") renderRelayAdmin(d);
  else if (S.page === "devices") renderDevices(d);
  else if (S.page === "proxies") renderProxies(d);
  else if (S.page === "guide") renderGuide(d);
}

function renderOverview(d) {
  const sys = (d.server || {}).sys || {};
  const frps = (d.server || {}).frps || {};
  const hist = d.history || [];
  const last = hist[hist.length - 1] || {};
  const online = d.devices.filter(x => x.status === "online").length;

  countTo($("#stat-devices"), online);
  $("#stat-devices-sub").textContent = `共 ${d.devices.length} 台`;
  countTo($("#stat-proxies"), d.proxies.filter(p => p.status === "online").length);
  $("#stat-proxies-sub").textContent = `共 ${d.proxies.length} 条`;
  $("#stat-bw").textContent = fmtBw(last.out || 0);
  $("#stat-bw-sub").textContent = `↑${fmtBw(last.in || 0)} · ↓${fmtBw(last.out || 0)}`;
  $("#stat-total").textContent = fmtBytes((frps.totalTrafficIn || 0) + (frps.totalTrafficOut || 0));
  $("#stat-total").title = `入站 ${fmtBytes(frps.totalTrafficIn)} / 出站 ${fmtBytes(frps.totalTrafficOut)}`;
  $("#frps-version").textContent = "frp " + (frps.version || meta.frp_version || "–");

  ring($("#ring-cpu"), sys.cpu, "%");
  ring($("#ring-mem"), sys.mem_percent, "%");
  $("#mem-total").textContent = sys.mem_total_mb ? `/${sys.mem_total_mb}MB` : "";
  ring($("#ring-disk"), sys.disk_percent, "%");
  $("#kv-load").textContent = sys.load1 != null ? sys.load1.toFixed(2) : "–";
  $("#kv-uptime").textContent = fmtUptime(sys.uptime_s);
  $("#kv-net").textContent = `↓${fmtBw(sys.net_rates ? sys.net_rates[0] : 0)} ↑${fmtBw(sys.net_rates ? sys.net_rates[1] : 0)}`;

  drawTrafficChart(hist);
  drawConnsChart(hist);
  renderRecentProxies(d);
}

function ring(el, pct, suffix) {
  if (!el) return;
  const v = pct == null ? 0 : Math.min(100, Math.max(0, pct));
  el.style.setProperty("--pct", v);
  const color = v < 60 ? "var(--ok)" : v < 85 ? "var(--warn)" : "var(--danger)";
  el.style.setProperty("--rc", color);
  el.querySelector("span").textContent = pct == null ? "–" : Math.round(pct) + suffix;
}

/* 数字变化直接写文本;仅首次出现时做一次计数动画,之后不再逐帧 rAF */
function countTo(el, target) {
  if (!el) return;
  const seen = el.dataset.v !== undefined;
  el.dataset.v = target;
  if (seen || target === 0) { el.textContent = target; return; }
  const t0 = performance.now(), dur = 500;
  (function step(t) {
    const k = Math.min(1, (t - t0) / dur);
    el.textContent = Math.round(target * (1 - Math.pow(1 - k, 3)));
    if (k < 1) requestAnimationFrame(step);
  })(t0);
}

const CHART_DARK = {
  textColor: "#8b94ad", lineColor: "rgba(255,255,255,0.08)",
  tipBg: "rgba(14,20,38,0.94)", tipBorder: "rgba(255,255,255,0.14)"
};

function timeLabels(hist) {
  return hist.map(p => new Date(p.t * 1000).toLocaleTimeString("zh-CN", { hour12: false, minute: "2-digit", second: "2-digit" }));
}

function drawTrafficChart(hist) {
  let c = S.charts.traffic;
  const points = hist.slice(-1200);
  if (!c) {
    c = echarts.init($("#chart-traffic"));
    S.charts.traffic = c;
    c.setOption({
      animation: false,          // 关闭动画:轮询增量更新不再整幅重放
      grid: { left: 8, right: 14, top: 14, bottom: 4, containLabel: true },
      tooltip: {
        trigger: "axis", backgroundColor: CHART_DARK.tipBg, borderColor: CHART_DARK.tipBorder,
        textStyle: { color: "#e8ecf6", fontSize: 12 },
        valueFormatter: v => fmtBw(v)
      },
      legend: { show: false },
      xAxis: {
        type: "category", data: timeLabels(points),
        axisLine: { lineStyle: { color: CHART_DARK.lineColor } }, axisLabel: { color: CHART_DARK.textColor, fontSize: 10.5 }
      },
      yAxis: {
        type: "value", axisLabel: { color: CHART_DARK.textColor, fontSize: 10.5, formatter: v => fmtBytes(v) },
        splitLine: { lineStyle: { color: CHART_DARK.lineColor } }
      },
      series: [
        {
          name: "入站", type: "line", smooth: 0.35, symbol: "none", data: points.map(p => p.in || 0),
          lineStyle: { width: 2, color: "#22d3ee" },
          areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: "rgba(34,211,238,0.30)" }, { offset: 1, color: "rgba(34,211,238,0.01)" }]) }
        },
        {
          name: "出站", type: "line", smooth: 0.35, symbol: "none", data: points.map(p => p.out || 0),
          lineStyle: { width: 2, color: "#6366f1" },
          areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: "rgba(99,102,241,0.30)" }, { offset: 1, color: "rgba(99,102,241,0.01)" }]) }
        }
      ]
    });
    return;
  }
  // 增量更新:只替换 x 轴与数据数组
  c.setOption({
    xAxis: { data: timeLabels(points) },
    series: [{ data: points.map(p => p.in || 0) }, { data: points.map(p => p.out || 0) }]
  }, { lazyUpdate: true });
}

function drawConnsChart(hist) {
  let c = S.charts.conns;
  const points = hist.slice(-1200);
  if (!c) {
    c = echarts.init($("#chart-conns"));
    S.charts.conns = c;
    c.setOption({
      animation: false,
      grid: { left: 8, right: 14, top: 14, bottom: 4, containLabel: true },
      tooltip: { trigger: "axis", backgroundColor: CHART_DARK.tipBg, borderColor: CHART_DARK.tipBorder, textStyle: { color: "#e8ecf6", fontSize: 12 } },
      xAxis: { type: "category", data: timeLabels(points), axisLine: { lineStyle: { color: CHART_DARK.lineColor } }, axisLabel: { color: CHART_DARK.textColor, fontSize: 10.5 } },
      yAxis: { type: "value", minInterval: 1, axisLabel: { color: CHART_DARK.textColor, fontSize: 10.5 }, splitLine: { lineStyle: { color: CHART_DARK.lineColor } } },
      series: [{
        name: "连接数", type: "bar", data: points.map(p => p.conns || 0), barWidth: "55%",
        itemStyle: { borderRadius: [3, 3, 0, 0], color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: "#34d399" }, { offset: 1, color: "rgba(52,211,153,0.25)" }]) }
      }]
    });
    return;
  }
  c.setOption({
    xAxis: { data: timeLabels(points) },
    series: [{ data: points.map(p => p.conns || 0) }]
  }, { lazyUpdate: true });
}

function renderRecentProxies(d) {
  const box = $("#recent-proxies");
  const list = [...d.proxies].sort((a, b) => (b.curConns || 0) - (a.curConns || 0) || (b.todayTrafficOut || 0) - (a.todayTrafficOut || 0)).slice(0, 7);
  if (!list.length) { box.innerHTML = emptyHTML("暂无代理"); return; }
  box.innerHTML = list.map(p => {
    const st = p.status === "online" ? "dot-ok" : p.err ? "dot-bad" : "";
    const port = remotePort(p);
    return `<div class="mini-row">
      <span class="dot ${st}"></span>
      <div class="grow"><div class="name">${esc(p.name)}</div>
        <div class="sub">${esc(p._proto || "tcp").toUpperCase()}${port ? " : " + port : ""}</div></div>
      <span class="sub">${fmtBytes((p.todayTrafficIn || 0) + (p.todayTrafficOut || 0))} 今日</span>
    </div>`;
  }).join("");
}

function remotePort(p) {
  const rp = p.conf && p.conf.remotePort;
  if (rp != null) return String(rp);
  const ra = String(p.remoteAddr || "");
  return ra.includes(":") ? ra.split(":").pop() : "";
}

function emptyHTML(text) {
  return `<div class="empty">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
    <div>${esc(text)}</div></div>`;
}

/* ---------------- 中继管理页 ---------------- */
function sparkline(points, w, h, color) {
  const pts = points.slice(-240).map(v => (v == null ? 0 : v));
  if (!pts.length) return "";
  const max = Math.max(1, ...pts);
  return `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
    <polyline fill="none" stroke="${color}" stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round"
      points="${pts.map((v, i) => `${(i / Math.max(1, pts.length - 1)) * w},${h - 2 - (v / max) * (h - 4)}`).join(" ")}"/>
  </svg>`;
}

function latClass(ms) { return ms == null ? "bad" : ms < 30 ? "ok" : ms < 150 ? "warn" : "bad"; }

/* 设备列表结构签名:名称/状态/探测结果不变时仅原位更新速率与迷你曲线 */
function relayListSig(devices) {
  return devices.map(d => `${d.name}|${d.status}|${d.probed}|${d.reachable}`).join(";");
}

function buildDevListHTML(devices, withAnim) {
  return devices.map((dev, i) => {
    const anim = withAnim
      ? ` style="animation:rise .35s ease backwards;animation-delay:${(i * 0.04).toFixed(2)}s"`
      : ' style="animation:none"';
    return `
    <div class="ra-dev ${dev.status !== "online" ? "off" : ""} ${S.relaySel === dev.name ? "sel" : ""}" data-dev="${esc(dev.name)}"${anim}>
      <div class="ra-dev-ava">${esc(dev.name.slice(0, 2))}</div>
      <div class="ra-dev-mid">
        <div class="ra-dev-name">${esc(dev.name)}</div>
        <div class="ra-dev-sub">
          <span class="dot ${dev.status === "online" ? "dot-ok" : dev.status === "error" ? "dot-bad" : ""}"></span>
          <span>${dev.probed ? `可达 ${dev.reachable}/${dev.probed}` : "未探测"}</span>
        </div>
      </div>
      <div class="ra-dev-right">
        <div class="ra-dev-rate">↓${fmtBw(dev.rate_in)}</div>
        <div class="ra-dev-rate out">↑${fmtBw(dev.rate_out)}</div>
        <div class="ra-dev-spark">${sparkline((dev.hist || []).map(h => h.in + h.out), 62, 16, "#22d3ee")}</div>
      </div>
    </div>`;
  }).join("") || emptyHTML("暂无设备");
}

function updateDevListInPlace(list, devices) {
  const cards = list.querySelectorAll(".ra-dev");
  if (cards.length !== devices.length) return false;
  for (let i = 0; i < devices.length; i++) {
    const el = cards[i], dev = devices[i];
    if (el.dataset.dev !== dev.name) return false;
    el.classList.toggle("off", dev.status !== "online");
    el.classList.toggle("sel", S.relaySel === dev.name);
    el.querySelector(".dot").className = "dot " + (dev.status === "online" ? "dot-ok" : dev.status === "error" ? "dot-bad" : "");
    el.querySelector(".ra-dev-rate").textContent = "↓" + fmtBw(dev.rate_in);
    el.querySelector(".ra-dev-rate.out").textContent = "↑" + fmtBw(dev.rate_out);
    const svg = sparkline((dev.hist || []).map(h => h.in + h.out), 62, 16, "#22d3ee");
    const sp = el.querySelector(".ra-dev-spark");
    if (sp.dataset.svg !== svg) { sp.innerHTML = svg; sp.dataset.svg = svg; }
  }
  return true;
}

function renderRelayAdmin(d) {
  const devices = d.devices || [];
  const probes = d.probes || {};
  const health = d.health || {};
  const onDev = devices.filter(x => x.status === "online");
  const reach = devices.reduce((a, x) => a + (x.reachable || 0), 0);
  const probed = devices.reduce((a, x) => a + (x.probed || 0), 0);
  const totIn = devices.reduce((a, x) => a + (x.rate_in || 0), 0);
  const totOut = devices.reduce((a, x) => a + (x.rate_out || 0), 0);

  $("#ra-chip-devices").querySelector(".ra-chip-num").innerHTML = `<span class="ok">${onDev.length}</span> / ${devices.length}`;
  $("#ra-chip-reach").querySelector(".ra-chip-num").innerHTML = probed ? `<span class="${reach === probed ? "ok" : ""}">${reach}</span> / ${probed}` : "–";
  $("#ra-chip-in").textContent = fmtBw(totIn);
  $("#ra-chip-out").textContent = fmtBw(totOut);
  const healthHTML = [
    ["frps_api", "frps 控制面"], ["tcp_7000", "TCP 7000"], ["nginx_80", "nginx :80"]
  ].map(([k, label]) => `<span class="ra-hitem ${health[k] ? "ok" : "bad"}"><span class="dot ${health[k] ? "dot-ok" : "dot-bad"}"></span>${label}</span>`).join("");
  if ($("#ra-health").dataset.html !== healthHTML) { $("#ra-health").innerHTML = healthHTML; $("#ra-health").dataset.html = healthHTML; }

  /* 左侧设备列表:结构变化才重建,否则原位更新 */
  const list = $("#ra-devlist");
  const sig = relayListSig(devices);
  if (sig !== SIG.relayList) {
    const firstBuild = !list.querySelector(".ra-dev");
    list.innerHTML = buildDevListHTML(devices, firstBuild);
    if (firstBuild) setTimeout(() => $$("#ra-devlist .ra-dev").forEach(el => { el.style.animation = "none"; }), 900);
    $$("#ra-devlist .ra-dev").forEach(el => el.addEventListener("click", () => {
      S.relaySel = el.dataset.dev;
      S.relayBuilt = null;
      renderRelayAdmin(S.data);
    }));
    SIG.relayList = sig;
  } else if (!updateDevListInPlace(list, devices)) {
    SIG.relayList = null;   // 结构对不上,下轮重建
  }

  /* 右侧详情 */
  const sel = devices.find(x => x.name === S.relaySel) || devices[0];
  if (!sel) { $("#ra-detail").innerHTML = emptyHTML("暂无设备,前往「接入指南」添加"); S.relayBuilt = null; if (S.charts.rad) { S.charts.rad.dispose(); S.charts.rad = null; } return; }
  S.relaySel = sel.name;
  if (S.relayBuilt !== sel.name) {
    S.relayBuilt = sel.name;
    if (S.charts.rad) { S.charts.rad.dispose(); S.charts.rad = null; }
    $("#ra-detail").innerHTML = `
      <div class="rad-head">
        <div class="rad-ava">${esc(sel.name.slice(0, 2))}</div>
        <div style="flex:1;min-width:0">
          <div class="rad-title">${esc(sel.name)}</div>
          <div class="rad-sub">
            <span class="pill" id="rad-pill-status"></span>
            <span class="tag">接入 · WebSocket :80</span>
            <span class="tag" id="rad-tag-start"></span>
            ${sel.avg_latency != null ? `<span class="tag">平均探测延迟 <b style="color:var(--ok)">${sel.avg_latency}ms</b></span>` : ""}
          </div>
        </div>
      </div>
      <div class="rad-statgrid">
        <div class="rad-stat"><span class="v" style="color:var(--accent2)" id="rad-rate-in">–</span><span class="k">入站速率(设备→公网)</span></div>
        <div class="rad-stat"><span class="v" style="color:#a5b4fc" id="rad-rate-out">–</span><span class="k">出站速率(公网→设备)</span></div>
        <div class="rad-stat"><span class="v" id="rad-today">–</span><span class="k">今日中继流量</span></div>
        <div class="rad-stat"><span class="v" id="rad-conns">–</span><span class="k">当前活动连接</span></div>
      </div>
      <div class="card rad-chart-card">
        <div class="card-head"><h3>设备实时速率(近 10 分钟)</h3>
          <div class="legend"><span><i class="lg-dot lg-in"></i>入站</span><span><i class="lg-dot lg-out"></i>出站</span></div>
        </div>
        <div id="rad-chart" class="rad-chart"></div>
      </div>
      <div class="card">
        <div class="card-head"><h3>隧道可达性探测</h3><span class="tag">每 0.5 秒自动探测 · 端到端穿透验证</span></div>
        <div class="probe-row head"><span>隧道</span><span>公网入口</span><span>服务验证</span><span>延迟</span><span>历史</span></div>
        <div id="rad-probe-rows"></div>
      </div>
      <div class="card">
        <div class="card-head"><h3>代理明细</h3></div>
        <div class="table-wrap"><table class="table">
          <thead><tr><th>代理</th><th>类型</th><th>状态</th><th class="ta-r">今日流量 ↓/↑</th><th class="ta-r">连接</th><th>最近上线</th></tr></thead>
          <tbody id="rad-proxy-tbody"></tbody>
        </table></div>
      </div>`;
    const c = echarts.init($("#rad-chart"));
    c.setOption({
      animation: false,
      grid: { left: 8, right: 14, top: 12, bottom: 4, containLabel: true },
      tooltip: { trigger: "axis", backgroundColor: CHART_DARK.tipBg, borderColor: CHART_DARK.tipBorder, textStyle: { color: "#e8ecf6", fontSize: 12 }, valueFormatter: v => fmtBw(v) },
      xAxis: { type: "category", axisLine: { lineStyle: { color: CHART_DARK.lineColor } }, axisLabel: { color: CHART_DARK.textColor, fontSize: 10.5 } },
      yAxis: { type: "value", axisLabel: { color: CHART_DARK.textColor, fontSize: 10.5, formatter: v => fmtBytes(v) }, splitLine: { lineStyle: { color: CHART_DARK.lineColor } } },
      series: [
        { name: "入站", type: "line", smooth: 0.4, symbol: "none", lineStyle: { width: 2, color: "#22d3ee" }, areaStyle: { color: { type: "linear", x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: "rgba(34,211,238,0.28)" }, { offset: 1, color: "rgba(34,211,238,0.01)" }] } } },
        { name: "出站", type: "line", smooth: 0.4, symbol: "none", lineStyle: { width: 2, color: "#6366f1" }, areaStyle: { color: { type: "linear", x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: "rgba(99,102,241,0.28)" }, { offset: 1, color: "rgba(99,102,241,0.01)" }] } } }
      ]
    });
    S.charts.rad = c;
  }

  /* 增量更新动态值 */
  const pill = $("#rad-pill-status");
  if (pill) {
    pill.className = "pill " + (sel.status === "online" ? "pill-ok" : sel.status === "error" ? "pill-err" : "pill-off");
    pill.textContent = sel.status === "online" ? "在线" : sel.status === "error" ? "异常" : "离线";
  }
  const tagStart = $("#rad-tag-start");
  if (tagStart) tagStart.textContent = sel.last_start ? "上线 " + sel.last_start : sel.err ? "未连接" : "离线";
  const set = (id, v) => { const el = $(id); if (el) el.textContent = v; };
  set("#rad-rate-in", fmtBw(sel.rate_in));
  set("#rad-rate-out", fmtBw(sel.rate_out));
  set("#rad-today", fmtBytes(sel.today_in + sel.today_out));
  set("#rad-conns", fmtNum(sel.cur_conns));

  const hist = sel.hist || [];
  if (S.charts.rad) {
    S.charts.rad.setOption({
      xAxis: { data: hist.map(h => new Date(h.t * 1000).toLocaleTimeString("zh-CN", { hour12: false, minute: "2-digit" })) },
      series: [{ data: hist.map(h => h.in) }, { data: hist.map(h => h.out) }]
    }, { lazyUpdate: true });
  }

  /* 探测行:数据未变则跳过 */
  const devProxies = d.proxies.filter(p => p.name.split(".")[0] === sel.name);
  const probeSig = devProxies.map(p => {
    const pr = probes[p.name];
    return [p.name, p.status, pr ? pr.ok : "", pr ? pr.ms : "", pr ? pr.detail : "", pr && pr.hist ? pr.hist.join(",") : ""].join("~");
  }).join(";");
  if (probeSig !== SIG.probeRows) {
    SIG.probeRows = probeSig;
    $("#rad-probe-rows").innerHTML = devProxies.map(p => {
      const pr = probes[p.name];
      const port = remotePort(p);
      const sparkMax = 40;
      const bars = (pr && pr.hist ? pr.hist : []).map(ms =>
        ms == null ? `<i class="fail" style="height:100%"></i>` : `<i style="height:${Math.max(12, Math.min(100, ms / sparkMax * 100))}%"></i>`).join("");
      return `<div class="probe-row">
        <span class="probe-name"><span class="dot ${p.status === "online" ? "dot-ok" : pr && pr.ok === false ? "dot-bad" : ""}"></span><span class="nm">${esc(p.name.split(".").slice(1).join(".") || p.name)}</span></span>
        <span class="probe-endpoint">${port ? esc(d.meta.public_addr) + ":" + esc(port) : "–"}</span>
        <span class="probe-detail">${pr ? esc(pr.detail || (pr.ok ? "可达" : "不可达")) : "等待探测…"}</span>
        <span class="lat-pill ${pr ? latClass(pr.ms) : ""}">${pr ? (pr.ok ? (pr.ms != null ? pr.ms + " ms" : "通") : "不可达") : "–"}</span>
        <span class="probe-spark" title="最近探测延迟">${bars || "<i style='height:12%'></i>"}</span>
      </div>`;
    }).join("") || `<div class="empty">该设备暂无代理通道</div>`;
  }

  $("#rad-proxy-tbody").innerHTML = devProxies.map(p => `
    <tr>
      <td class="mono"><b>${esc(p.name.split(".").slice(1).join(".") || p.name)}</b></td>
      <td><span class="proto-badge">${esc(p._proto || "tcp")}</span></td>
      <td>${p.status === "online" ? '<span class="pill pill-ok">在线</span>' : p.err ? `<span class="pill pill-err" title="${esc(p.err)}">异常</span>` : '<span class="pill pill-off">离线</span>'}</td>
      <td class="ta-r mono" style="font-size:12px">${fmtBytes(p.todayTrafficIn || 0)} / ${fmtBytes(p.todayTrafficOut || 0)}</td>
      <td class="ta-r mono">${fmtNum(p.curConns || 0)}</td>
      <td class="muted" style="font-size:12px">${esc(timeAgo(p.lastStartTime, d.meta.now))}</td>
    </tr>`).join("");
}

document.addEventListener("click", async e => {
  if (!e.target.closest("#ra-probe-btn")) return;
  const btn = $("#ra-probe-btn");
  btn.disabled = true; btn.style.opacity = 0.6;
  try { await api("api/probe-now", { method: "POST" }); toast("探测完成"); refresh(true); }
  catch (err) { toast("探测失败: " + err.message, false); }
  finally { btn.disabled = false; btn.style.opacity = 1; }
});

/* ---------------- 设备页 ---------------- */
function deviceGridSig(d) {
  return d.devices.map(dev =>
    [dev.name, dev.status, dev.online_proxies, dev.err,
     ...d.proxies.filter(p => p.name.split(".")[0] === dev.name).map(p => (p.name) + ":" + (remotePort(p) || ""))].join("~")
  ).join(";");
}

function renderDevices(d) {
  const grid = $("#device-grid");
  if (!d.devices.length) {
    if (SIG.deviceGrid !== "__empty__") { grid.innerHTML = emptyHTML("还没有设备接入,前往「接入指南」添加第一台设备"); SIG.deviceGrid = "__empty__"; }
    return;
  }
  const sig = deviceGridSig(d);
  if (sig !== SIG.deviceGrid) {
    const firstBuild = !grid.querySelector(".device-card");
    grid.innerHTML = d.devices.map((dev, i) => {
      const online = dev.status === "online";
      const cls = online ? "" : dev.status === "error" ? "error" : "offline";
      const anim = firstBuild
        ? ` style="animation:rise .4s ease backwards;animation-delay:${(i * 0.05).toFixed(2)}s"`
        : ' style="animation:none"';
      const chips = d.proxies.filter(p => p.name.split(".")[0] === dev.name).map(p => {
        const port = remotePort(p);
        return `<span class="chip" data-copy="${esc(sshCmd(port))}" title="点击复制连接命令">${esc(p.name.split(".").slice(1).join(".") || p.name)}<span class="cp">:${esc(port)}</span></span>`;
      }).join("");
      return `<div class="device-card ${cls}" data-dev="${esc(dev.name)}"${anim}>
        <div class="device-top">
          <div class="device-ava">${esc(dev.name.slice(0, 2))}</div>
          <div style="min-width:0;flex:1">
            <div class="device-name">${esc(dev.name)}</div>
            <div class="device-meta">${dev.proxies} 条代理 · ${dev.last_start ? "上线 " + esc(dev.last_start) : dev.err ? "未连接" : "离线"}</div>
          </div>
          <span class="pill ${online ? "pill-ok" : dev.status === "error" ? "pill-err" : "pill-off"}">${online ? "在线" : dev.status === "error" ? "异常" : "离线"}</span>
        </div>
        <div class="device-stats">
          <div class="device-stat"><div class="v dv-in">${fmtBytes(dev.today_in)}</div><div class="k">今日入站</div></div>
          <div class="device-stat"><div class="v dv-out">${fmtBytes(dev.today_out)}</div><div class="k">今日出站</div></div>
          <div class="device-stat"><div class="v dv-conns">${fmtNum(dev.cur_conns)}</div><div class="k">当前连接</div></div>
        </div>
        <div class="proxy-chips">${chips || '<span class="muted" style="font-size:12px">无代理</span>'}</div>
        <div class="device-foot">
          <span class="df-note">${dev.err ? "⚠ " + esc(dev.err) : dev.last_close ? "最后活跃 " + timeAgo(dev.last_close, d.meta.now) : ""}</span>
          <span class="df-online">${dev.online_proxies}/${dev.proxies} 通道在线</span>
        </div>
      </div>`;
    }).join("");
    if (firstBuild) setTimeout(() => $$("#device-grid .device-card").forEach(el => { el.style.animation = "none"; }), 1000);
    $$("#device-grid .chip[data-copy]").forEach(ch => ch.addEventListener("click", () => copyText(ch.dataset.copy)));
    SIG.deviceGrid = sig;
    return;
  }
  /* 结构未变:仅更新动态数值 */
  const cards = grid.querySelectorAll(".device-card");
  d.devices.forEach((dev, i) => {
    const el = cards[i];
    if (!el || el.dataset.dev !== dev.name) return;
    el.querySelector(".dv-in").textContent = fmtBytes(dev.today_in);
    el.querySelector(".dv-out").textContent = fmtBytes(dev.today_out);
    el.querySelector(".dv-conns").textContent = fmtNum(dev.cur_conns);
    el.querySelector(".df-online").textContent = `${dev.online_proxies}/${dev.proxies} 通道在线`;
  });
}

function sshCmd(port) {
  const ip = S.data ? S.data.meta.public_addr : "";
  return `ssh -p ${port} 用户@${ip}`;
}

/* ---------------- 代理页 ---------------- */
function renderProxies(d) {
  const tb = $("#proxy-tbody");
  if (!d.proxies.length) { tb.innerHTML = `<tr><td colspan="9">${emptyHTML("暂无代理通道")}</td></tr>`; return; }
  const rows = [...d.proxies].sort((a, b) => (a.status === "online" ? 0 : 1) - (b.status === "online" ? 0 : 1) || a.name.localeCompare(b.name));
  tb.innerHTML = rows.map(p => {
    const dev = p.name.includes(".") ? p.name.split(".")[0] : "(默认)";
    const svc = p.name.includes(".") ? p.name.split(".").slice(1).join(".") : p.name;
    const port = remotePort(p);
    const pill = p.status === "online" ? '<span class="pill pill-ok">在线</span>'
      : p.err ? `<span class="pill pill-err" title="${esc(p.err)}">异常</span>`
      : '<span class="pill pill-off">离线</span>';
    const recent = p.status === "online" ? timeAgo(p.lastStartTime, d.meta.now) : timeAgo(p.lastCloseTime, d.meta.now);
    return `<tr>
      <td class="mono" title="${esc(p.name)}"><b>${esc(svc)}</b></td>
      <td>${esc(dev)}</td>
      <td><span class="proto-badge">${esc(p._proto || "tcp")}</span></td>
      <td class="port-code">${port ? esc(d.meta.public_addr) + ":" + esc(port) : "–"}</td>
      <td>${pill}</td>
      <td class="ta-r mono" style="font-size:12px">${fmtBytes(p.todayTrafficIn || 0)} / ${fmtBytes(p.todayTrafficOut || 0)}</td>
      <td class="ta-r mono">${fmtNum(p.curConns || 0)}</td>
      <td class="muted" style="font-size:12px">${esc(recent)}</td>
      <td>${port ? `<button class="copy-btn" style="position:static" data-copy="${esc(sshCmd(port))}">复制SSH</button>` : ""}</td>
    </tr>`;
  }).join("");
  $$("#proxy-tbody [data-copy]").forEach(b => b.addEventListener("click", () => copyText(b.dataset.copy)));
}

/* ---------------- 指南页 ---------------- */
function renderGuide(d) {
  const m = d.meta;
  const base = m.panel_base || `http://${m.public_addr}:8100`;
  const token = m.frps_token || "在服务端 config.json 查看";
  const samplePort = m.port_range ? m.port_range[0] + 1 : 6001;
  const rangesText = m.ranges_text || (m.port_range ? `${m.port_range[0]}-${m.port_range[1]}` : "端口池范围");
  const prEl = document.getElementById("guide-port-range");
  if (prEl) prEl.textContent = rangesText;
  const installCmd = `curl -fsSL ${base}/install.sh | bash -s -- --name 设备名 --token ${token}`;
  if ($("#cmd-install").dataset.rendered !== "1") {
    $("#cmd-install").textContent = installCmd;
    $("#cmd-ssh").textContent =
      `ssh -p <分配的端口> 用户@${m.public_addr}   # 例如 ssh -p ${samplePort} pi@${m.public_addr}`;
    $("#guide-bind-port").textContent = m.frps_bind_port;
    $("#manual-config").textContent =
`# /etc/frp/frpc.toml
serverAddr = "${m.public_addr}"
serverPort = ${m.frps_bind_port}
user = "设备名"                # 面板中显示的设备名
auth.token = "${token}"

[[proxies]]
name = "ssh"
type = "tcp"
localIP = "127.0.0.1"
localPort = 22
remotePort = ${samplePort}    # 公网访问端口(${rangesText})

# 映射更多服务: 追加上述 [[proxies]] 段即可
# 启动: frpc -c /etc/frp/frpc.toml  (脚本安装则已注册 systemd 服务)`;
    $("#cmd-install").dataset.rendered = "1";
  }
}

/* 复制按钮事件委托 */
document.addEventListener("click", e => {
  const btn = e.target.closest(".copy-btn[data-copy-target]");
  if (!btn) return;
  const el = document.getElementById(btn.dataset.copyTarget);
  if (el) copyText(el.textContent);
});

/* ---------------- 启动 ---------------- */
(async function init() {
  try {
    await api("api/summary");
    showApp();
  } catch (e) {
    showLogin();
  }
  nav(location.hash.replace(/^#\//, "") || "overview");
  window.addEventListener("resize", () => resizePageCharts(S.page));
})();
