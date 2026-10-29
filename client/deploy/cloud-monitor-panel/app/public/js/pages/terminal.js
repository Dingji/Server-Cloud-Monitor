/* Web terminal page (xterm.js + WebSocket) */
(function () {
  'use strict';
  let term = null, fitAddon = null, ws = null, clientId = null, disposed = false;
  let termReconnectTimer = null;

  const SHELL_KEY = 'cm_term_shell';

  function getShellPref() {
    return localStorage.getItem(SHELL_KEY) || 'auto';
  }

  function setShellPref(v) {
    localStorage.setItem(SHELL_KEY, v);
  }

  function shellLabel(v) {
    if (v === 'cmd') return 'CMD';
    if (v === 'powershell') return 'PowerShell 5.1';
    if (v === 'pwsh') return 'PowerShell 7';
    return '自动';
  }

  function getClientId() {
    let id = localStorage.getItem('cm_term_client');
    if (!id) {
      id = (crypto.randomUUID ? crypto.randomUUID() : 'x' + Date.now() + Math.random().toString(16).slice(2));
      localStorage.setItem('cm_term_client', id);
    }
    return id;
  }

  function sendAttach() {
    if (!ws || ws.readyState !== 1) return;
    ws.send(JSON.stringify({ t: 'attach', id: clientId, shell: getShellPref() }));
    try {
      fitAddon.fit();
      ws.send(JSON.stringify({ t: 'resize', id: clientId, cols: term.cols, rows: term.rows }));
    } catch (_) {}
  }

  function connect() {
    if (disposed) return;
    if (!CM.token) {
      setStatus('bad', '请先登录后再使用终端');
      return;
    }
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${location.host}${CM.base}/term?token=${encodeURIComponent(CM.token)}`;
    if (ws && (ws.readyState === 0 || ws.readyState === 1)) return;
    ws = new WebSocket(url);
    ws.onopen = () => {
      setStatus('ok', '已连接');
      sendAttach();
    };
    ws.onmessage = (ev) => {
      let msg; try { msg = JSON.parse(ev.data); } catch (_) { return; }
      if (msg.type === 'data') term.write(msg.d.replace(/\n/g, '\r\n'));
      else if (msg.type === 'status') {
        const detail = msg.detail || msg.state || '';
        setStatus(msg.state === 'connected' ? 'ok' : msg.state === 'reconnecting' ? 'warn' : 'bad', detail);
        if (msg.state === 'closed') term.write('\r\n\x1b[33m[会话已关闭]\x1b[0m\r\n');
        if (msg.state === 'connected') try { term.focus(); } catch (_) {}
        if (detail === 'invalid session id') term.write('\r\n\x1b[33m[会话无效，请点击「新会话」]\x1b[0m\r\n');
      }
    };
    ws.onclose = (ev) => {
      if (disposed) return;
      if (!CM.token) {
        setStatus('bad', '未登录');
        return;
      }
      if (ev.code === 1008 || ev.code === 4401) {
        setStatus('bad', '认证失败，请重新登录');
        return;
      }
      setStatus('bad', '连接断开，3 秒后重连…');
      clearTimeout(termReconnectTimer);
      termReconnectTimer = setTimeout(() => { if (!disposed && CM.token) connect(); }, 3000);
    };
    ws.onerror = () => { try { ws.close(); } catch (_) {} };
  }

  function setStatus(kind, text) {
    const dot = document.getElementById('term-dot');
    const txt = document.getElementById('term-status');
    if (!dot) return;
    dot.className = 'conn-dot ' + (kind === 'ok' ? 'ok' : kind === 'warn' ? '' : 'bad');
    txt.textContent = text;
  }

  function updateHostLabel() {
    const el = document.getElementById('term-host');
    if (!el) return;
    const shell = shellLabel(getShellPref());
    if (CM.me && CM.me.info) el.textContent = `${CM.me.info.hostname} · ${shell}`;
    else el.textContent = shell;
  }

  function startNewSession() {
    localStorage.removeItem('cm_term_client');
    clientId = getClientId();
    if (term) {
      try { term.clear(); } catch (_) {}
    }
    updateHostLabel();
    try { if (ws) ws.close(); } catch (_) {}
    ws = null;
    window.toast('已创建新会话: ' + shellLabel(getShellPref()), 'ok');
    connect();
  }

  registerPage({
    id: 'terminal',
    title: 'Web 终端',
    desc: '本机 PowerShell / CMD 交互终端',
    async mount(root) {
      const isWin = CM.isWindows();
      const shell = getShellPref();
      root.innerHTML = `
      <div class="term-wrap">
        <div class="term-bar">
          <div class="conn-dot" id="term-dot"></div>
          <span id="term-status">连接中</span>
          <span class="term-host mono small" id="term-host">${isWin ? shellLabel(shell) : 'root@—'}</span>
          <label class="term-shell-pick small muted">
            Shell
            <select id="term-shell" class="input input-sm">
              <option value="auto">自动 (优先 PS7)</option>
              <option value="cmd">CMD</option>
              <option value="powershell">PowerShell 5.1</option>
              <option value="pwsh">PowerShell 7</option>
            </select>
          </label>
          <button class="btn btn-sm" id="term-new">${icon('plus', { size: 13 })} 新会话</button>
          <button class="btn btn-sm" id="term-clear">${icon('trash', { size: 13 })} 清屏</button>
          <span class="muted small">点击终端区域后输入 · Enter 执行 · Ctrl+Shift+C/V 复制粘贴</span>
        </div>
        <div id="term-container"></div>
      </div>`;

      clientId = getClientId();
      const sel = document.getElementById('term-shell');
      if (sel) {
        sel.value = shell;
        sel.onchange = () => {
          setShellPref(sel.value);
          updateHostLabel();
          window.toast('已选择 ' + shellLabel(sel.value) + '，请点击「新会话」启动', 'ok', 5000);
        };
      }
      document.getElementById('term-new').onclick = () => startNewSession();
      document.getElementById('term-clear').onclick = () => { if (term) term.clear(); };
      ensureTerm();
    },
    show() { disposed = false; ensureTerm(); },
    hide() { disposeTerm(); },
  });

  function ensureTerm() {
    if (term) {
      try { fitAddon.fit(); term.focus(); } catch (_) {}
      connect();
      return;
    }
    term = new Terminal({
      fontSize: 13.5,
      fontFamily: 'Consolas, "SFMono-Regular", Menlo, monospace',
      theme: { background: '#070b10', foreground: '#c9d7e6', cursor: '#4ade80', selectionBackground: 'rgba(59,130,246,.35)' },
      cursorBlink: true,
      scrollback: 5000,
      allowProposedApi: true,
      convertEol: true,
    });
    fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    const container = document.getElementById('term-container');
    term.open(container);
    try { fitAddon.fit(); } catch (_) {}
    container.tabIndex = 0;
    container.addEventListener('mousedown', (e) => {
      e.preventDefault();
      try { term.focus(); } catch (_) {}
    });
    requestAnimationFrame(() => { try { term.focus(); } catch (_) {} });

    term.onData((d) => {
      if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'data', id: clientId, d }));
    });
    term.onResize(({ cols, rows }) => {
      if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'resize', id: clientId, cols, rows }));
    });

    window.removeEventListener('resize', term._onWinResize || (() => {}));
    window.addEventListener('resize', term._onWinResize = () => { try { fitAddon.fit(); } catch (_) {} });

    updateHostLabel();
    connect();
  }

  window.addEventListener('cm-login', () => {
    if (disposed || !term) return;
    try { if (ws) ws.close(); } catch (_) {}
    ws = null;
    connect();
  });

  function disposeTerm() {
    disposed = true;
    clearTimeout(termReconnectTimer);
    if (term && term._onWinResize) window.removeEventListener('resize', term._onWinResize);
    try { if (ws) ws.close(); } catch (_) {}
    if (term) { term.dispose(); term = null; }
  }
})();
