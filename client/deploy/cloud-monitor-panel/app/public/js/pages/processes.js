/* Processes page */
(function () {
  'use strict';
  let auto = null;
  let loadRef = () => {};

  registerPage({
    id: 'processes',
    title: '进程管理',
    async mount(root) {
      root.innerHTML = `
      <div class="card">
        <div class="card-title">进程列表
          <div class="right">
            <span id="p-counts" class="row" style="gap:6px"></span>
            <input class="inp" id="p-q" placeholder="搜索 PID/用户/命令…" style="width:200px">
            <select class="inp" id="p-sort"><option value="cpu">按 CPU</option><option value="mem">按 内存</option><option value="pid">按 PID</option><option value="user">按 用户</option></select>
            <label class="row small muted"><input type="checkbox" id="p-auto" checked> 自动刷新</label>
            <span class="mono small" id="p-rate"></span>
            <button class="btn btn-sm" id="p-refresh">${icon('refresh', { size: 13 })} 刷新</button>
          </div>
        </div>
        <div id="p-table"><span class="muted small">加载中…</span></div>
      </div>`;

      const load = async () => {
        try {
          const q = encodeURIComponent(document.getElementById('p-q').value.trim());
          const sort = document.getElementById('p-sort').value;
          const d = await CM.api.get(`/processes?q=${q}&sort=${sort}`);
          document.getElementById('p-counts').innerHTML =
            ui.badge('运行 ' + d.statCounts.running, 'ok') + ' ' +
            ui.badge('休眠 ' + d.statCounts.sleeping, 'gray') + ' ' +
            (d.statCounts.zombie ? ui.badge('僵尸 ' + d.statCounts.zombie, 'bad') : '') + ' ' +
            ui.badge('共 ' + d.total, 'info');
          document.getElementById('p-table').innerHTML = ui.table([
            { k: 'pid', label: 'PID', cls: 'num' },
            { k: 'user', label: '用户' },
            { k: 'cpu', label: 'CPU%', cls: 'num', render: (v) => `<span style="color:${v > 80 ? '#f87171' : v > 30 ? '#facc15' : ''}">${v.toFixed(1)}</span>` },
            { k: 'mem', label: 'MEM%', cls: 'num' },
            { k: 'rss', label: 'RSS', cls: 'num', render: (v) => fmt.bytes(v) },
            { k: 'stat', label: '状态' },
            { k: 'etime', label: '运行时长', cls: 'mono' },
            { k: 'args', label: '命令', cls: 'wrap', render: (v, r) => `<span class="mono small" title="${fmt.esc(v)}">${fmt.esc(String(v).slice(0, 90))}</span>` },
            { k: 'pid', label: '', render: (v) => v <= 1 ? '' : `<button class="btn btn-sm btn-danger" data-kill="${v}">${icon('stop', { size: 12 })} 终止</button>` },
          ], d.list);
          document.getElementById('p-table').querySelectorAll('[data-kill]').forEach((b) => {
            b.onclick = async () => {
              const pid = b.dataset.kill;
              const row = d.list.find((x) => String(x.pid) === pid);
              const okc = await modal.confirm('终止进程', `向 <b class="mono">PID ${pid}</b> (${fmt.esc((row ? row.args : '').slice(0, 60))}) 发送 SIGTERM？`);
              if (!okc) return;
              try { await runAction('/process/kill', { pid: Number(pid), signal: 'TERM' }, { reload: load }); }
              catch (_) {}
            };
          });
        } catch (e) { document.getElementById('p-table').innerHTML = `<div class="muted">${fmt.esc(e.message)}</div>`; }
      };

      document.getElementById('p-refresh').onclick = load;
      loadRef = load;
      document.getElementById('p-q').addEventListener('input', CM.debounce(() => load(), 250));
      document.getElementById('p-sort').onchange = load;

      await load();
      auto = CM.autoRefresh(() => loadRef(), { pageId: 'processes' });
      document.getElementById('p-auto').onchange = (e) => auto.setEnabled(e.target.checked);
      document.getElementById('p-rate').textContent = CM.refreshLabel(auto.ms());
      auto.start();
    },
    show() {
      const r = document.getElementById('p-rate');
      if (r && auto) r.textContent = CM.refreshLabel(auto.ms());
      if (auto && auto.isEnabled()) auto.start();
    },
    hide() { if (auto) auto.stop(); },
  });
})();
