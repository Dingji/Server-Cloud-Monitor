/* Backups page */
(function () {
  'use strict';
  const SOURCES = (CM.me && CM.me.env && CM.me.env.family === 'windows')
    ? ['C:\\ProgramData\\CloudPanel', 'C:\\CloudMonitorDotNet', 'C:\\inetpub', 'C:\\ProgramData']
    : ['/etc', '/root', '/var/www', '/srv'];

  registerPage({
    id: 'backups',
    title: '备份中心',
    async mount(root) {
      root.innerHTML = `<div class="card">${ui.skeleton(5)}</div>`;
      let d;
      try { d = await CM.api.get('/backups'); }
      catch (e) { root.innerHTML = `<div class="card"><div class="muted">${fmt.esc(e.message)}</div></div>`; return; }
      const totalSize = d.items.reduce((a, b) => a + b.size, 0);

      root.innerHTML = `
      <div class="grid g3 mb">
        <div class="card stat"><div class="stat-label">备份文件</div><div class="stat-value">${d.items.length}</div><div class="stat-sub">${fmt.bytes(totalSize)} 总占用</div></div>
        <div class="card stat"><div class="stat-label">存放位置</div><div class="stat-value" style="font-size:15px;font-family:var(--mono)">${fmt.esc(d.dir)}</div><div class="stat-sub">面板同机目录</div></div>
        <div class="card stat"><div class="stat-label">快速备份</div><div class="stat-value" style="font-size:16px">选择目录 ${icon('chevronRight', { size: 14 })}</div>
          <div class="row mt">${SOURCES.map((s) => `<button class="btn btn-sm btn-primary" data-bak="${s}">${s}</button>`).join('')}</div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">备份列表
          <div class="right"><button class="btn btn-sm" id="bk-refresh">刷新</button></div>
        </div>
        <div id="bk-list"></div>
        <div class="muted small mt">下载备份：<b>下载按钮</b>走当前登录会话鉴权。建议定期将重要备份另行转存到对象存储/本地。</div>
      </div>

      <div class="card" id="bk-relay">
        <div class="card-title">${icon('archive', { size: 14 })} Relay 每日状态备份
          <div class="right">
            <button class="btn btn-sm btn-primary" id="bk-relay-run">${icon('play', { size: 12 })} 立即备份</button>
            <button class="btn btn-sm" id="bk-relay-refresh">刷新</button>
          </div>
        </div>
        <div id="bk-relay-body">${ui.skeleton(4)}</div>
      </div>`;

      document.getElementById('bk-list').innerHTML = d.items.length ? ui.table([
        { k: 'name', label: '文件名', cls: 'mono wrap' },
        { k: 'size', label: '大小', cls: 'num', render: (v) => fmt.bytes(v) },
        { k: 'mtime', label: '时间', cls: 'mono' },
        { k: 'name', label: '操作', render: (v) => `<span class="row" style="gap:4px">
          <a class="btn btn-sm" href="${CM.apiBase}/backups/download?name=${encodeURIComponent(v)}&token=${encodeURIComponent(CM.token)}">下载</a>
          <button class="btn btn-sm btn-danger" data-del="${fmt.esc(v)}">删除</button></span>` },
      ], d.items) : '<div class="muted small">暂无备份，点击右上方目录按钮创建。</div>';

      document.querySelectorAll('[data-bak]').forEach((b) => {
        b.onclick = async () => {
          const src = b.dataset.bak;
          const okc = await modal.confirm('创建备份', `将 <b class="mono">${src}</b> 打包为 tar.gz（大目录可能耗时较长）。`, { okText: '开始备份' });
          if (!okc) return;
          ui.busy(b, true);
          try {
            const r = await runAction('/backups', { source: src }, { toast: false });
            window.toast('备份完成: ' + (r.name || src), 'ok');
            location.reload();
          } catch (_) { ui.busy(b, false); }
        };
      });
      document.querySelectorAll('[data-del]').forEach((b) => {
        b.onclick = async () => {
          const okc = await modal.confirm('删除备份', `删除 <b class="mono">${fmt.esc(b.dataset.del)}</b>？不可恢复。`, { danger: true, okText: '删除' });
          if (!okc) return;
          runAction('/backups/delete', { name: b.dataset.del }, { reload: () => location.reload() }).catch(() => {});
        };
      });
      document.getElementById('bk-refresh').onclick = () => location.reload();
      document.getElementById('bk-relay-refresh').onclick = () => location.reload();

      /* ---- relay daily state backup (/var/backups/relay) ---- */
      const rr = d.relay;
      const body = document.getElementById('bk-relay-body');
      const renderRelay = () => {
        if (!rr || rr.error || !rr.dir) {
          body.innerHTML = `<div class="muted small">relay 备份不可用${rr && rr.error ? '：' + fmt.esc(rr.error) : ''}</div>`;
          return;
        }
        const st = rr.state;
        const total = rr.items.reduce((a, b) => a + b.size, 0);
        body.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">备份份数</div><div class="stat-value">${rr.items.length}</div><div class="stat-sub">保留 ${rr.retention} 份</div></div>
          <div class="card stat"><div class="stat-label">占用</div><div class="stat-value">${fmt.bytes(total)}</div><div class="stat-sub" style="font-family:var(--mono)">${fmt.esc(rr.dir)}</div></div>
          <div class="card stat"><div class="stat-label">上次结果</div><div class="stat-value" style="font-size:16px;padding-top:8px">${st ? (st.ok ? ui.badge('成功', 'ok') : ui.badge('失败', 'bad')) : '<span class="muted">—</span>'}</div><div class="stat-sub">${st ? fmt.esc(String(st.ts || '').replace('T', ' ').slice(0, 19)) : '无记录'}</div></div>
          <div class="card stat"><div class="stat-label">远端推送</div><div class="stat-value" style="font-size:15px;padding-top:8px;word-break:break-all">${rr.remote ? ui.badge('已配置', 'ok') : ui.badge('仅本地', 'gray')}</div><div class="stat-sub">${rr.remote ? fmt.esc(rr.remote) : (CM.isWindows() ? 'Windows 面板仅本地备份' : '在 /etc/relay/backup.env 配置')}</div></div>
        </div>
        ${ui.table([
          { k: 'name', label: '文件名', cls: 'mono wrap' },
          { k: 'size', label: '大小', cls: 'num', render: (v) => fmt.bytes(v) },
          { k: 'mtime', label: '时间', cls: 'mono' },
          { k: 'name', label: '操作', render: (v) => `<span class="row" style="gap:4px">
            <a class="btn btn-sm" href="${CM.apiBase}/relayops/backup/download?name=${encodeURIComponent(v)}&token=${encodeURIComponent(CM.token)}">下载</a>
            <button class="btn btn-sm btn-danger" data-rdel="${fmt.esc(v)}">删除</button></span>` },
        ], rr.items)}
        <div class="muted small mt">${CM.isWindows() ? 'Relay 备份需 Linux relay 节点；本机 Windows 面板不运行 relay 定时备份。' : '由 <code>/opt/relay/backup_relay.sh</code> 生成，<code>relay-backup.timer</code> 每日 03:30 执行。'}</div>`;

        body.querySelectorAll('[data-rdel]').forEach((b) => {
          b.onclick = async () => {
            const okc = await modal.confirm('删除备份', `删除 <b class="mono">${fmt.esc(b.dataset.rdel)}</b>？不可恢复。`, { danger: true, okText: '删除' });
            if (!okc) return;
            runAction('/relayops/backup/delete', { name: b.dataset.rdel }, { reload: () => location.reload() }).catch(() => {});
          };
        });
      };
      renderRelay();

      document.getElementById('bk-relay-run').onclick = async (ev) => {
        const b = ev.currentTarget;
        const okc = await modal.confirm('立即备份', '执行一次 relay 状态热备份（SQLite 在线备份，不中断中继服务）。', { okText: '开始备份' });
        if (!okc) return;
        ui.busy(b, true);
        try {
          await runAction('/relayops/backup/run', {}, { showOutput: true, title: 'Relay 备份结果' });
          location.reload();
        } catch (_) { ui.busy(b, false); }
      };
    },
  });
})();
