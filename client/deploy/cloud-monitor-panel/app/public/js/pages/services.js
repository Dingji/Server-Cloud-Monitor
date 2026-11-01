/* Services page */
(function () {
  'use strict';
  let auto = null, cache = null;
  let loadRef = () => {};

  registerPage({
    id: 'services',
    title: '系统服务',
    async mount(root) {
      root.innerHTML = `
      <div class="card">
        <div class="card-title">systemd 服务
          <div class="right">
            <span id="sv-counts" class="row" style="gap:6px"></span>
            <input class="inp" id="sv-q" placeholder="搜索服务名…" style="width:200px">
            <select class="inp" id="sv-state"><option value="">全部状态</option><option value="active">仅运行</option><option value="failed">仅失败</option><option value="inactive">仅停止</option></select>
            <label class="row small muted"><input type="checkbox" id="sv-auto" checked> 自动刷新</label>
            <span class="mono small" id="sv-rate"></span>
            <button class="btn btn-sm" id="sv-refresh">${icon('refresh', { size: 13 })} 刷新</button>
          </div>
        </div>
        <div id="sv-table"><span class="muted small">加载中…</span></div>
      </div>`;

      const load = async () => {
        try {
          cache = await CM.api.get('/services');
          render();
        } catch (e) { document.getElementById('sv-table').innerHTML = `<div class="muted">${fmt.esc(e.message)}</div>`; }
      };

      const render = () => {
        if (!cache) return;
        const q = document.getElementById('sv-q').value.trim().toLowerCase();
        const state = document.getElementById('sv-state').value;
        let list = cache.list;
        if (q) list = list.filter((s) => (s.unit + ' ' + s.description).toLowerCase().includes(q));
        if (state) list = list.filter((s) => s.active === state);
        document.getElementById('sv-counts').innerHTML =
          ui.badge('运行 ' + cache.counts.running, 'ok') + ' ' +
          (cache.counts.failed ? ui.badge('失败 ' + cache.counts.failed, 'bad') : '') + ' ' +
          ui.badge('共 ' + cache.counts.total, 'info');
        document.getElementById('sv-table').innerHTML = ui.table([
          { k: 'unit', label: '单元', cls: 'mono', render: (v) => `<span title="${fmt.esc(v)}">${fmt.esc(v)}</span>` },
          { k: 'active', label: '状态', render: (v, r) => ui.svcBadge(v, r.sub) },
          { k: 'enabled', label: '开机启动', render: (v) => v === 'enabled' ? ui.badge('enabled', 'ok') : v === 'static' ? ui.badge('static', 'gray') : ui.badge(v || '-', 'warn') },
          { k: 'description', label: '描述', cls: 'wrap', render: (v) => fmt.esc(String(v).slice(0, 70)) },
          { k: 'unit', label: '操作', render: (v) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm" data-svc="start" data-u="${fmt.esc(v)}">${icon('play', { size: 11 })} 启动</button>
              <button class="btn btn-sm" data-svc="restart" data-u="${fmt.esc(v)}">${icon('restart', { size: 11 })} 重启</button>
              <button class="btn btn-sm btn-danger" data-svc="stop" data-u="${fmt.esc(v)}">${icon('stop', { size: 11 })} 停止</button>
              <button class="btn btn-sm" data-svc="${v && cache.list.find((x) => x.unit === v && x.enabled === 'enabled') ? 'disable' : 'enable'}" data-u="${fmt.esc(v)}">${icon('power', { size: 11 })} ${v && cache.list.find((x) => x.unit === v && x.enabled === 'enabled') ? '关自启' : '开自启'}</button>
            </span>` },
        ], list.slice(0, 400));
        document.getElementById('sv-table').querySelectorAll('[data-svc]').forEach((b) => {
          b.onclick = async () => {
            const unit = b.dataset.u, act = b.dataset.svc;
            const okc = await modal.confirm(act + ' 服务', `对 <b class="mono">${fmt.esc(unit)}</b> 执行 <b>systemctl ${act}</b>？`, { danger: act === 'stop' });
            if (!okc) return;
            try { await runAction('/service', { unit, action: act }, { reload: load }); }
            catch (_) { load(); }
          };
        });
      };

      document.getElementById('sv-refresh').onclick = load;
      loadRef = load;
      document.getElementById('sv-q').oninput = CM.debounce(render, 160);
      document.getElementById('sv-state').onchange = render;

      await load();
      auto = CM.autoRefresh(() => loadRef(), { pageId: 'services' });
      document.getElementById('sv-auto').onchange = (e) => auto.setEnabled(e.target.checked);
      document.getElementById('sv-rate').textContent = CM.refreshLabel(auto.ms());
      auto.start();
    },
    show() {
      const r = document.getElementById('sv-rate');
      if (r && auto) r.textContent = CM.refreshLabel(auto.ms());
      if (auto && auto.isEnabled()) auto.start();
    },
    hide() { if (auto) auto.stop(); },
  });
})();
