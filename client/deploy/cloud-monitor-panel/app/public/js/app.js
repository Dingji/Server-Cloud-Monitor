/* Cloud Monitor - app bootstrap: nav, router, hub, topbar, alerts, responsive */
(function () {
  'use strict';

  const NAV = [
    { sep: '监控' },
    { id: 'dashboard', label: '仪表盘', icon: 'dashboard' },
    { id: 'healthalerts', label: '健康告警', icon: 'shieldAlert' },
    { id: 'slo', label: 'SLO 与用量', icon: 'gauge' },
    /* 'relay' 项在运行时探测到 relay 采集器后才插入（见 detectOptionalNav） */
    { sep: '服务器' },
    { id: 'processes', label: '进程管理', icon: 'process' },
    { id: 'disks', label: '磁盘管理', icon: 'disk' },
    { id: 'memory', label: '内存管理', icon: 'memory' },
    { id: 'network', label: '网络管理', icon: 'network' },
    { id: 'security', label: '安全管理', icon: 'shield' },
    { id: 'services', label: '系统服务', icon: 'gear' },
    { id: 'packages', label: '软件包管理', icon: 'package' },
    { sep: '应用' },
    { id: 'databases', label: '数据库', icon: 'database' },
    { id: 'docker', label: 'Docker 容器', icon: 'docker' },
    { id: 'web', label: '网站 / Nginx', icon: 'browser' },
    { id: 'cron', label: '定时任务', icon: 'clock' },
    { id: 'backups', label: '备份中心', icon: 'archive' },
    { id: 'logs', label: '日志中心', icon: 'logs' },
    { id: 'audit', label: '审计日志', icon: 'list' },
    { sep: '工具' },
    { id: 'terminal', label: 'Web 终端', icon: 'terminal' },
    { sep: '系统' },
    { id: 'settings', label: '面板设置', icon: 'sliders' },
  ];

  /* ---------- theme accent ---------- */
  const ACCENTS = {
    blue: ['#4370f0', '#7a9bff', '67, 112, 240'],
    green: ['#10b981', '#34d399', '16, 185, 129'],
    purple: ['#8b5cf6', '#a78bfa', '139, 92, 246'],
    orange: ['#f59e0b', '#fbbf24', '245, 158, 11'],
    red: ['#ef4444', '#f87171', '239, 68, 68'],
    cyan: ['#06b6d4', '#22d3ee', '6, 182, 212'],
  };
  CM.applyAccent = function (name) {
    const a = ACCENTS[name] || ACCENTS.blue;
    const r = document.documentElement.style;
    r.setProperty('--accent', a[0]);
    r.setProperty('--accent2', a[1]);
    r.setProperty('--accent-rgb', a[2]);
    localStorage.setItem('cm_accent', name);
  };
  CM.applyAccent(localStorage.getItem('cm_accent') || 'blue');
  CM.ACCENT_NAMES = Object.keys(ACCENTS);

  /* ---------- optional module detection ----------
   * Some pages only make sense on a specific setup (e.g. the relay dashboard
   * needs a relay collector on 127.0.0.1:8787). Probe them and inject the nav
   * entry only when available, so a stock Linux install shows a clean menu. */
  const OPTIONAL_NAV = [
    { id: 'relay', after: 'dashboard', label: 'OpenCode 中继', icon: 'satellite', probe: '/relay/available' },
  ];
  async function detectOptionalNav() {
    for (const o of OPTIONAL_NAV) {
      try {
        const r = await CM.api.get(o.probe, 15000);
        if (r && r.ok) {
          const at = NAV.findIndex((n) => n.id === o.after);
          NAV.splice(at + 1, 0, { id: o.id, label: o.label, icon: o.icon });
        }
      } catch (_) { /* absent -> keep the entry hidden */ }
    }
    buildNav();
  }

  /* ---------- lazy page modules ---------- */
  /* bump on each deploy so browsers pick up new page code immediately */
  CM.BUILD = '20261016-06';
  CM.lazyPages = {
    dashboard: 'js/pages/dashboard.js',
    healthalerts: 'js/pages/healthalerts.js',
    slo: 'js/pages/slo.js',
    relay: 'js/pages/relay.js',
    processes: 'js/pages/processes.js',
    disks: 'js/pages/disks.js',
    memory: 'js/pages/memory.js',
    network: 'js/pages/network.js',
    security: 'js/pages/security.js',
    services: 'js/pages/services.js',
    packages: 'js/pages/packages.js',
    databases: 'js/pages/databases.js',
    docker: 'js/pages/docker.js',
    web: 'js/pages/web.js',
    cron: 'js/pages/cron.js',
    backups: 'js/pages/backups.js',
    logs: 'js/pages/logs.js',
    audit: 'js/pages/audit.js',
    terminal: 'js/pages/terminal.js',
    settings: 'js/pages/settings.js',
  };

  function buildNav() {
    const nav = document.getElementById('nav');
    nav.innerHTML = NAV.map((n) => n.sep
      ? `<div class="nav-sep">${n.sep}</div>`
      : `<div class="nav-item" data-page="${n.id}" title="${n.label}">${icon(n.icon, { size: 16 })}<span>${n.label}</span></div>`
    ).join('');
    nav.querySelectorAll('.nav-item').forEach((el) => {
      /* prefetch module on hover so the first click is instant */
      el.addEventListener('mouseenter', () => CM.loadPageModule(el.dataset.page).catch(() => {}), { once: true });
      el.onclick = () => { CM.router.navigate(el.dataset.page); closeDrawer(); };
    });
    registerPaletteCommands();
  }

  /* ---------- command palette entries ---------- */
  let paletteRegistered = false;
  function registerPaletteCommands() {
    if (paletteRegistered) return;
    paletteRegistered = true;
    NAV.filter((n) => !n.sep).forEach((n) => {
      CM.registerCommand({ label: n.label, hint: '打开页面', icon: n.icon, run: () => CM.router.navigate(n.id) });
    });
    CM.registerCommand({ label: '刷新当前页面', hint: '重新加载', icon: 'refresh', run: () => location.reload() });
    CM.registerCommand({
      label: '折叠 / 展开当前页所有卡片', hint: '布局', icon: 'layers',
      run: () => {
        const body = document.querySelector('#view .page:not([style*="display: none"]) .page-body') ||
          (CM.router.containers.get(CM.router.current && CM.router.current.id) || {}).body;
        if (!body) return;
        const cards = [...body.querySelectorAll('.card')];
        const anyOpen = cards.some((c) => !c.classList.contains('collapsed'));
        cards.forEach((c) => c.classList.toggle('collapsed', anyOpen));
        const page = CM.router.current ? CM.router.current.id : 'x';
        const st = {};
        if (anyOpen) cards.forEach((c) => { st[page + '|' + (String((c.querySelector(':scope > .card-title') || {}).textContent || '').replace(/\s+/g, ' ').trim().slice(0, 28))] = 1; });
        try { localStorage.setItem('cm_collapsed_cards_v1', JSON.stringify(st)); } catch (_) {}
        window.toast(anyOpen ? '已折叠本页 ' + cards.length + ' 个卡片' : '已展开本页 ' + cards.length + ' 个卡片', 'ok');
      },
    });
    CM.registerCommand({
      label: '切换告警中心', hint: '最近告警', icon: 'bell',
      run: () => { const b = document.getElementById('alert-bell'); if (b) b.click(); },
    });
  }

  /* static chrome icons */
  function paintChrome() {
    const bi = document.getElementById('brand-icon');
    if (bi && !bi.innerHTML.trim()) bi.innerHTML = icon('server', { size: 18 });
    const lg = document.getElementById('logout-btn');
    if (lg && !lg.innerHTML.trim()) lg.innerHTML = icon('logout', { size: 15 });
    const hb = document.getElementById('hamburger');
    if (hb && !hb.innerHTML.trim()) hb.innerHTML = icon('menu', { size: 18 });
    const bell = document.getElementById('bell-icon');
    if (bell && !bell.innerHTML.trim()) bell.innerHTML = icon('bell', { size: 16 });
  }
  window.addEventListener('DOMContentLoaded', paintChrome);

  /* ---------- mobile drawer ---------- */
  function closeDrawer() {
    document.getElementById('sidebar').classList.remove('open');
    document.getElementById('sidebar-backdrop').classList.remove('show');
  }
  window.addEventListener('DOMContentLoaded', () => {
    document.getElementById('hamburger').onclick = () => {
      document.getElementById('sidebar').classList.toggle('open');
      document.getElementById('sidebar-backdrop').classList.toggle('show');
    };
    document.getElementById('sidebar-backdrop').onclick = closeDrawer;
  });

  /* ---------- alert center ---------- */
  const alertState = { items: [], unread: 0 };

  function renderBell(animate) {
    const cnt = document.getElementById('alert-count');
    const bell = document.getElementById('alert-bell');
    if (alertState.unread > 0) {
      cnt.classList.remove('hidden'); cnt.textContent = alertState.unread > 99 ? '99+' : alertState.unread;
      bell.classList.add('alerting');
      if (animate) { cnt.style.animation = 'none'; void cnt.offsetWidth; cnt.style.animation = ''; }
    } else {
      cnt.classList.add('hidden');
      bell.classList.remove('alerting');
    }
    const menu = document.getElementById('alert-menu');
    menu.innerHTML = alertState.items.length
      ? `<div class="row between small muted" style="padding:2px 6px 8px"><b>告警中心</b><a href="#" id="bell-clear">全部标记已读</a></div>` +
        alertState.items.slice(0, 40).map((a) => `
        <div class="bell-item ${a.level === 'bad' ? 'bad' : ''}">
          <div class="row" style="gap:7px;align-items:flex-start">${icon(a.level === 'bad' ? 'warning' : 'info', { size: 14 })}<span>${fmt.esc(a.message)}</span></div>
          <div class="t">${new Date(a.ts).toLocaleString('zh-CN', { hour12: false })}</div>
        </div>`).join('')
      : '<div class="muted small" style="padding:14px;text-align:center">暂无告警记录</div>';
    const clear = document.getElementById('bell-clear');
    if (clear) clear.onclick = (e) => { e.preventDefault(); alertState.unread = 0; renderBell(); };
  }

  function initBell() {
    document.getElementById('alert-bell').onclick = (e) => {
      e.stopPropagation();
      document.getElementById('alert-menu').classList.toggle('hidden');
      alertState.unread = 0; renderBell();
    };
    document.addEventListener('click', (e) => {
      const menu = document.getElementById('alert-menu');
      const bell = document.getElementById('alert-bell');
      if (!menu || !bell) return;
      if (!menu.contains(e.target) && !bell.contains(e.target)) menu.classList.add('hidden');
    });
  }

  async function loadAlerts() {
    try {
      const d = await CM.api.get('/alerts');
      alertState.items = d.alerts || [];
      renderBell();
    } catch (_) {}
  }

  function pushAlert(a) {
    alertState.items.unshift(a);
    if (alertState.items.length > 200) alertState.items.pop();
    alertState.unread++;
    renderBell(true);
    window.toast(a.message, a.level === 'bad' ? 'err' : 'warn', 8000);
  }

  /* ---------- boot ---------- */
  async function boot() {
    try {
      CM.me = await CM.api.get('/me');
    } catch (_) { return; }

    /* index.html 结构异常时直接提示，避免后续代码抛一串 null */
    const missing = ['app', 'nav', 'view', 'page-title', 'brand-host', 'conn-dot', 'conn-text', 'alert-menu', 'alert-bell']
      .filter((id) => !document.getElementById(id));
    if (missing.length) {
      console.error('index.html 结构异常，缺少元素: ' + missing.join(', '));
      return;
    }

    document.getElementById('brand-host').textContent = CM.me.info.hostname;
    CM.hideLogin();
    buildNav();
    detectOptionalNav();   /* 异步补齐可选模块（探测失败则保持隐藏，不阻塞启动） */
    initBell();
    loadAlerts();

    CM.hub = new CM.Hub('/ws');
    CM.hub.on('__open', () => {
      document.getElementById('conn-dot').className = 'conn-dot ok';
      document.getElementById('conn-text').textContent = '实时连接';
    });
    CM.hub.on('__close', () => {
      document.getElementById('conn-dot').className = 'conn-dot bad';
      document.getElementById('conn-text').textContent = '重连中…';
    });
    CM.hub.on('hello', (msg) => {
      if (msg.history && msg.history.length && CM.router.current && CM.router.current.id === 'dashboard' && CM.applyMetricsBatch) {
        /* replay history in ONE batched update instead of N redraws */
        CM.applyMetricsBatch(msg.history);
      }
    });
    CM.hub.on('alert', (msg) => pushAlert(msg.alert));
    CM.hub.on('metrics', (msg) => {
      const m = msg.data;
      const up = document.getElementById('top-uptime');
      if (up) up.textContent = '运行 ' + fmt.uptime(m.cpu.uptimeSec);
      const ld = document.getElementById('top-load');
      if (ld) ld.textContent = '负载 ' + (m.cpu.loadAvg || []).join(' / ');
    });
    CM.hub.connect();

    setInterval(() => {
      const el = document.getElementById('top-clock');
      if (el) el.textContent = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    }, 1000);

    CM.ready = true;
    const id = (location.hash || '').replace(/^#\//, '') || 'dashboard';
    await CM.loadPageModule(id).catch((e) => console.error(e));
    CM.router.activate(id);
  }

  window.addEventListener('DOMContentLoaded', () => {
    if (CM.token) boot();
    else CM.showLogin();
  });
  window.addEventListener('cm-login', () => { if (!CM.ready) boot(); });
})();
