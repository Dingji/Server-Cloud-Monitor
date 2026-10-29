/* Processes page */
(function () {
  'use strict';
  let auto = null;
  let loadRef = () => {};

  function fmtBps(n) {
    if (n == null || !n) return '<span class="muted">0</span>';
    return fmt.bps(n);
  }

  function winColumns() {
    return [
      { k: 'name', label: '名称', cls: 'mono', render: (v) => fmt.esc(v || '') },
      { k: 'status', label: '状态', render: (v) => v === 'Not responding' ? ui.badge('未响应', 'bad') : ui.badge('正在运行', 'ok') },
      {
        k: 'cpu', label: 'CPU', cls: 'num',
        render: (v) => `<span style="color:${v > 80 ? '#f87171' : v > 30 ? '#facc15' : ''}">${Number(v || 0).toFixed(1)}%</span>`,
      },
      {
        k: 'rss', label: '内存', cls: 'num',
        render: (v, r) => `<span title="${fmt.bytes(v)}">${fmt.bytes(v)}</span> <span class="muted small">(${Number(r.mem || 0).toFixed(1)}%)</span>`,
      },
      { k: 'diskBps', label: '磁盘', cls: 'num', render: (v) => fmtBps(v) },
      { k: 'networkBps', label: '网络', cls: 'num', render: (v) => (v ? fmtBps(v) : '<span class="muted">—</span>') },
      { k: 'pid', label: 'PID', cls: 'num' },
      { k: 'user', label: '用户', cls: 'mono', render: (v) => v ? fmt.esc(v) : '<span class="muted">—</span>' },
      { k: 'priority', label: '优先级', cls: 'mono small', render: (v) => fmt.esc(v || '') },
      { k: 'threads', label: '线程', cls: 'num' },
      { k: 'etime', label: '运行时间', cls: 'mono' },
      { k: 'commandLine', label: '命令行', cls: 'proc-cmdline', render: (v, r) => {
        const t = String(v || r.args || r.name || '').replace(/\s+/g, ' ').trim();
        const short = t.length > 96 ? t.slice(0, 93) + '…' : t;
        return `<span class="mono small cmdline-clip" title="${fmt.esc(t)}">${fmt.esc(short)}</span>`;
      } },
      { k: 'pid', label: '', render: (v) => v <= 4 ? '' : `<button class="btn btn-sm btn-danger" data-kill="${v}">${icon('stop', { size: 12 })} 结束任务</button>` },
    ];
  }

  function linuxColumns() {
    return [
      { k: 'pid', label: 'PID', cls: 'num' },
      { k: 'user', label: '用户' },
      { k: 'cpu', label: 'CPU%', cls: 'num', render: (v) => `<span style="color:${v > 80 ? '#f87171' : v > 30 ? '#facc15' : ''}">${v.toFixed(1)}</span>` },
      { k: 'mem', label: 'MEM%', cls: 'num' },
      { k: 'rss', label: 'RSS', cls: 'num', render: (v) => fmt.bytes(v) },
      { k: 'stat', label: '状态' },
      { k: 'etime', label: '运行时长', cls: 'mono' },
      { k: 'args', label: '命令', cls: 'proc-cmdline', render: (v) => {
        const t = String(v || '').replace(/\s+/g, ' ').trim();
        const short = t.length > 96 ? t.slice(0, 93) + '…' : t;
        return `<span class="mono small cmdline-clip" title="${fmt.esc(t)}">${fmt.esc(short)}</span>`;
      } },
      { k: 'pid', label: '', render: (v) => v <= 1 ? '' : `<button class="btn btn-sm btn-danger" data-kill="${v}">${icon('stop', { size: 12 })} 终止</button>` },
    ];
  }

  registerPage({
    id: 'processes',
    title: '进程管理',
    async mount(root) {
      const isWin = CM.isWindows();
      root.innerHTML = `
      <div class="card">
        <div class="card-title">${isWin ? '任务管理器 · 进程' : '进程列表'}
          <div class="right">
            <span id="p-counts" class="row" style="gap:6px"></span>
            <span id="p-summary" class="mono small muted"></span>
            <input class="inp" id="p-q" placeholder="搜索 名称/PID/命令行…" style="width:220px">
            <select class="inp" id="p-sort">${isWin
              ? `<option value="cpu">按 CPU</option><option value="mem">按 内存</option><option value="disk">按 磁盘</option><option value="name">按 名称</option><option value="pid">按 PID</option>`
              : `<option value="cpu">按 CPU</option><option value="mem">按 内存</option><option value="pid">按 PID</option><option value="user">按 用户</option>`}</select>
            <label class="row small muted"><input type="checkbox" id="p-auto" checked> 自动刷新</label>
            <span class="mono small" id="p-rate"></span>
            <button class="btn btn-sm" id="p-refresh">${icon('refresh', { size: 13 })} 刷新</button>
          </div>
        </div>
        <div id="p-table"><span class="muted small">加载中…</span></div>
        ${isWin ? '<div class="muted small mt">列与 Windows 任务管理器「进程」页一致；CPU/磁盘需两次采样后趋于准确。网络/GPU 需系统级计数器，当前显示为 —。</div>' : ''}
      </div>`;

      const load = async () => {
        const table = document.getElementById('p-table');
        try {
          const q = encodeURIComponent(document.getElementById('p-q').value.trim());
          const sort = document.getElementById('p-sort').value;
          CM.api.invalidate('/processes');
          const d = await CM.api.get(`/processes?q=${q}&sort=${sort}&limit=400`);
          const win = isWin || d.windows;
          const sc = d.statCounts || {};
          document.getElementById('p-counts').innerHTML =
            ui.badge('运行 ' + (sc.running || 0), 'ok') + ' ' +
            (sc.notResponding ? ui.badge('未响应 ' + sc.notResponding, 'bad') + ' ' : '') +
            (sc.sleeping ? ui.badge('休眠 ' + sc.sleeping, 'gray') + ' ' : '') +
            (sc.zombie ? ui.badge('僵尸 ' + sc.zombie, 'bad') + ' ' : '') +
            ui.badge('共 ' + (d.total || d.list.length), 'info');
          const sumEl = document.getElementById('p-summary');
          if (sumEl && d.summary) {
            sumEl.textContent = `列表 CPU 合计 ${d.summary.cpuPct || 0}% · 内存 ${d.summary.memPct || 0}%`;
          }
          const cols = win ? winColumns() : linuxColumns();
          document.getElementById('p-table').innerHTML = ui.table(cols, d.list || [], { style: 'max-height:calc(100vh - 260px)' });
          document.getElementById('p-table').querySelectorAll('[data-kill]').forEach((b) => {
            b.onclick = async () => {
              const pid = b.dataset.kill;
              const row = (d.list || []).find((x) => String(x.pid) === pid);
              const label = row ? (row.name || row.args || '') : '';
              const okc = await modal.confirm(
                win ? '结束任务' : '终止进程',
                win
                  ? `结束 <b class="mono">PID ${pid}</b> · ${fmt.esc(String(label).slice(0, 80))}？<br><span class="muted small">等同 taskkill</span>`
                  : `向 <b class="mono">PID ${pid}</b> (${fmt.esc(String(label).slice(0, 60))}) 发送 SIGTERM？`,
                { danger: true, okText: win ? '结束任务' : '终止' },
              );
              if (!okc) return;
              try {
                await runAction('/process/kill', { pid: Number(pid), signal: win ? 'KILL' : 'TERM' }, { reload: load });
              } catch (_) {}
            };
          });
        } catch (e) {
          if (table) table.innerHTML = `<div class="muted">${fmt.esc(e.message || '加载失败')}</div>`;
          window.toast(e.message || '进程列表加载失败', 'err');
        }
      };

      document.getElementById('p-refresh').onclick = load;
      loadRef = load;
      document.getElementById('p-q').addEventListener('input', CM.debounce(() => load(), 250));
      document.getElementById('p-sort').onchange = load;

      await load();
      auto = CM.autoRefresh(() => loadRef(), { pageId: 'processes', ms: isWin ? () => 2000 : undefined });
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
