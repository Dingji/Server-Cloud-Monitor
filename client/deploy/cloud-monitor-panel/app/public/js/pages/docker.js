/* Docker page */
(function () {
  'use strict';

  registerPage({
    id: 'docker',
    title: 'Docker 容器',
    async mount(root) {
      root.innerHTML = `<div class="card">${ui.skeleton(6)}</div><div class="card">${ui.skeleton(5)}</div>`;
      let d;
      try { d = await CM.api.get('/docker'); }
      catch (e) { root.innerHTML = `<div class="card"><div class="muted">${fmt.esc(e.message)}</div></div>`; return; }

      if (!d.installed) {
        root.innerHTML = `<div class="card" style="text-align:center;padding:64px">
          <div style="color:var(--muted);display:flex;justify-content:center;margin-bottom:14px">${icon('docker', { size: 42, stroke: 1.2 })}</div>
          <div class="muted mb">服务器未安装 Docker 容器引擎。</div>
            <button class="btn btn-primary" id="dk-install">一键安装 Docker (docker.io)</button>
          <div class="muted small mt">安装约需 1-3 分钟（取决于网络），完成后此页面自动可用。</div>
        </div>`;
        document.getElementById('dk-install').onclick = async () => {
          const okc = await modal.confirm('安装 Docker', '将执行 <code>apt-get install -y docker.io</code>，约 1-3 分钟。', { okText: '开始安装' });
          if (!okc) return;
          const btn = document.getElementById('dk-install');
          ui.busy(btn, true);
          try { const r = await runAction('/pkg/install', { pkg: 'docker.io' }, { showOutput: true, title: 'Docker 安装日志' }); location.reload(); }
          catch (_) { ui.busy(btn, false); }
        };
        return;
      }

      const statMap = {};
      (d.stats || []).forEach((s) => (statMap[s.name] = s));

      root.innerHTML = `
      <div class="grid g4 mb">
        <div class="card stat"><div class="stat-label">Docker 引擎</div><div class="stat-value" style="font-size:22px;color:${d.active ? '#4ade80' : '#f87171'}">${d.active ? '运行中' : '已停止'}</div><div class="stat-sub">v${fmt.esc(d.version || '?')}</div>
          <div class="row mt" style="gap:6px">
            ${d.active ? `<button class="btn btn-sm btn-danger" id="dk-daemon-stop">停止引擎</button>` : `<button class="btn btn-sm btn-ok" id="dk-daemon-start">启动引擎</button>`}
            <button class="btn btn-sm" id="dk-daemon-enable">开机自启</button>
          </div></div>
        <div class="card stat"><div class="stat-label">容器</div><div class="stat-value">${d.containers.length}</div><div class="stat-sub">${d.containers.filter((c) => c.running).length} 运行中 · ${d.containers.filter((c) => !c.running).length} 停止</div></div>
        <div class="card stat"><div class="stat-label">镜像</div><div class="stat-value">${d.images.length}</div><div class="stat-sub">本地镜像数量</div></div>
        <div class="card stat"><div class="stat-label">维护</div><div class="stat-value" style="font-size:18px">—</div>
          <div class="row mt"><button class="btn btn-sm btn-danger" id="dk-prune">${icon('trash', { size: 12 })} 清理未使用资源</button></div></div>
      </div>

      <div class="card">
        <div class="card-title">容器列表 <div class="right"><button class="btn btn-sm" id="dk-refresh">刷新</button></div></div>
        <div id="dk-containers"></div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">镜像</div>
          <div id="dk-images"></div>
        </div>
        <div class="card">
          <div class="card-title">实时资源占用</div>
          <div id="dk-stats"></div>
        </div>
      </div>`;

      const renderContainers = () => {
        document.getElementById('dk-containers').innerHTML = ui.table([
          { k: 'name', label: '名称', cls: 'mono' },
          { k: 'id', label: 'ID', cls: 'mono', render: (v) => fmt.esc(String(v).slice(0, 12)) },
          { k: 'image', label: '镜像', cls: 'mono', render: (v) => fmt.esc(String(v).slice(0, 40)) },
          { k: 'status', label: '状态', render: (v, r) => r.running ? ui.badge(v.slice(0, 28), 'ok') : ui.badge(v.slice(0, 28), 'gray') },
          { k: 'ports', label: '端口', cls: 'mono small', render: (v) => fmt.esc(String(v || '-').slice(0, 34)) },
          { k: 'id', label: '操作', render: (v, r) => `<span class="row" style="gap:4px">
            ${r.running
              ? `<button class="btn btn-sm" data-dk="restart" data-t="${fmt.esc(v)}">重启</button><button class="btn btn-sm btn-danger" data-dk="stop" data-t="${fmt.esc(v)}">停止</button>`
              : `<button class="btn btn-sm btn-ok" data-dk="start" data-t="${fmt.esc(v)}">启动</button>`}
            <button class="btn btn-sm" data-dklog="${fmt.esc(v)}">${icon('logs', { size: 11 })} 日志</button>
            <button class="btn btn-sm btn-danger" data-dkrm="${fmt.esc(v)}" data-name="${fmt.esc(r.name)}">${icon('trash', { size: 11 })} 删除</button>
          </span>` },
        ], d.containers);
        document.querySelectorAll('[data-dk]').forEach((b) => {
          b.onclick = async () => {
            const act = b.dataset.dk, t = b.dataset.t;
            const okc = await modal.confirm(act + ' 容器', `对容器 <b class="mono">${fmt.esc(t.slice(0, 12))}</b> 执行 <b>docker ${act}</b>？`, { danger: act === 'stop' });
            if (!okc) return;
            runAction('/docker/action', { action: act, params: { target: t } }, { reload: () => location.reload(), reloadDelay: 1200 }).catch(() => {});
          };
        });
        document.querySelectorAll('[data-dklog]').forEach((b) => {
          b.onclick = async () => {
            try {
              const r = await CM.api.post('/logs/tail', { source: 'docker:' + b.dataset.dklog });
              modal.output('容器日志 ' + b.dataset.dklog.slice(0, 12), r.output);
            } catch (e) { window.toast(e.message, 'err'); }
          };
        });
        document.querySelectorAll('[data-dkrm]').forEach((b) => {
          b.onclick = async () => {
            const okc = await modal.confirm('删除容器', `将强制删除容器 <b>${fmt.esc(b.dataset.name)}</b>（docker rm -f），容器数据卷不受影响。`, { danger: true, okText: '删除' });
            if (!okc) return;
            runAction('/docker/action', { action: 'rm', params: { target: b.dataset.dkrm } }, { reload: () => location.reload() }).catch(() => {});
          };
        });
      };
      renderContainers();

      document.getElementById('dk-images').innerHTML = ui.table([
        { k: 'repo', label: '镜像', cls: 'mono', render: (v) => fmt.esc(String(v).slice(0, 44)) },
        { k: 'size', label: '大小', cls: 'num' },
        { k: 'created', label: '创建于' },
      ], d.images, { style: 'max-height:260px' });

      document.getElementById('dk-stats').innerHTML = d.stats.length
        ? ui.table([
          { k: 'name', label: '容器', cls: 'mono' },
          { k: 'cpu', label: 'CPU', cls: 'num' },
          { k: 'mem', label: '内存', cls: 'num' },
          { k: 'memPct', label: 'MEM%', cls: 'num' },
          { k: 'net', label: '网络 I/O', cls: 'num' },
        ], d.stats, { style: 'max-height:260px' })
        : '<div class="muted small">无运行中容器</div>';

      const bind = (id, action, danger) => {
        const b = document.getElementById(id);
        if (b) b.onclick = async () => {
          if (danger) { const okc = await modal.confirm('确认', `执行 <b>${action}</b>？`, { danger: true }); if (!okc) return; }
          runAction('/docker/action', { action }, { reload: () => location.reload(), reloadDelay: 1200 }).catch(() => {});
        };
      };
      bind('dk-daemon-start', 'daemon-start');
      bind('dk-daemon-stop', 'daemon-stop', true);
      bind('dk-daemon-enable', 'daemon-enable');
      bind('dk-prune', 'prune', true);
      document.getElementById('dk-refresh').onclick = () => location.reload();
    },
  });
})();
