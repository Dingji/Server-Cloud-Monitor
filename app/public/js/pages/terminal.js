/* Web terminal page (xterm.js + WebSocket) */
(function () {
  'use strict';
  let term = null, fitAddon = null, ws = null, clientId = null, disposed = false;
  let termReconnectTimer = null;

  function getClientId() {
    let id = localStorage.getItem('cm_term_client');
    if (!id) {
      id = (crypto.randomUUID ? crypto.randomUUID() : 'x' + Date.now() + Math.random().toString(16).slice(2));
      localStorage.setItem('cm_term_client', id);
    }
    return id;
  }

  function connect() {
    if (disposed) return;
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${location.host}${CM.base}/term?token=${encodeURIComponent(CM.token)}`;
    ws = new WebSocket(url);
    ws.onopen = () => {
      setStatus('ok', '已连接');
      ws.send(JSON.stringify({ t: 'attach', id: clientId }));
      try { fitAddon.fit(); ws.send(JSON.stringify({ t: 'resize', cols: term.cols, rows: term.rows })); } catch (_) {}
    };
    ws.onmessage = (ev) => {
      let msg; try { msg = JSON.parse(ev.data); } catch (_) { return; }
      if (msg.type === 'data') term.write(msg.d.replace(/\n/g, '\r\n'));
      else if (msg.type === 'status') {
        setStatus(msg.state === 'connected' ? 'ok' : msg.state === 'reconnecting' ? 'warn' : 'bad', msg.detail || msg.state);
        if (msg.state === 'closed') term.write('\r\n\x1b[33m[会话已关闭]\x1b[0m\r\n');
      }
    };
    ws.onclose = () => {
      if (disposed) return;
      setStatus('bad', '连接断开，3 秒后重连…');
      clearTimeout(termReconnectTimer);
      termReconnectTimer = setTimeout(() => { if (!disposed) connect(); }, 3000);
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

  registerPage({
    id: 'terminal',
    title: 'Web 终端',
    desc: 'root 权限的交互式 Shell（经 SSH PTY）',
    async mount(root) {
      root.innerHTML = `
      <div class="term-wrap">
        <div class="term-bar">
          <div class="conn-dot" id="term-dot"></div>
          <span id="term-status">连接中</span>
          <span class="term-host mono small" id="term-host">root@—</span>
          <button class="btn btn-sm" id="term-new">${icon('plus', { size: 13 })} 新会话</button>
          <button class="btn btn-sm" id="term-clear">${icon('trash', { size: 13 })} 清屏</button>
          <span class="muted small">Ctrl+Shift+C/V 复制粘贴 · 会话在刷新页面后保留 2 分钟</span>
        </div>
        <div id="term-container"></div>
      </div>`;

      clientId = getClientId();
      document.getElementById('term-new').onclick = () => {
        localStorage.removeItem('cm_term_client');
        clientId = getClientId();
        if (term) term.reset();
        try { if (ws) ws.close(); } catch (_) {}
        window.toast('已创建新终端会话', 'ok');
      };
      document.getElementById('term-clear').onclick = () => { if (term) term.clear(); };
      ensureTerm();
    },
    show() { disposed = false; ensureTerm(); },
    hide() { disposeTerm(); },
  });

  /* (re)create the xterm instance inside the cached container */
  function ensureTerm() {
    if (term) { try { fitAddon.fit(); term.focus(); } catch (_) {} connect(); return; }
    term = new Terminal({
      fontSize: 13.5,
      fontFamily: 'Consolas, "SFMono-Regular", Menlo, monospace',
      theme: { background: '#070b10', foreground: '#c9d7e6', cursor: '#4ade80', selectionBackground: 'rgba(59,130,246,.35)' },
      cursorBlink: true,
      scrollback: 5000,
      allowProposedApi: true,
    });
    fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.open(document.getElementById('term-container'));
    try { fitAddon.fit(); } catch (_) {}

    term.onData((d) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'data', d })); });
    term.onResize(({ cols, rows }) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify({ t: 'resize', cols, rows })); });

    window.removeEventListener('resize', term._onWinResize || (() => {}));
    window.addEventListener('resize', term._onWinResize = () => { try { fitAddon.fit(); } catch (_) {} });

    if (CM.me && CM.me.info) document.getElementById('term-host').textContent = `${CM.me.info.hostname} (${CM.me.info.publicIp || ''})`;
    connect();
  }

  function disposeTerm() {
    disposed = true;
    clearTimeout(termReconnectTimer);
    if (term && term._onWinResize) window.removeEventListener('resize', term._onWinResize);
    try { if (ws) ws.close(); } catch (_) {}
    if (term) { term.dispose(); term = null; }
  }
})();
