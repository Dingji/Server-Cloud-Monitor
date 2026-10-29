/* Web servers page (nginx / apache / SSL) */
(function () {
  'use strict';

  registerPage({
    id: 'web',
    title: '网站 / Nginx',
    async mount(root) {
      const d = await CM.api.get('/web');
      const isWin = CM.isWindows();
      const n = d.nginx;
      const iis = d.iis || { installed: false, active: false, info: '' };

      root.innerHTML = `
      ${isWin ? `<div class="card mb"><div class="muted small">Windows：优先使用 <b>IIS (W3SVC)</b>；若安装了 Nginx 也可在下方管理。SSL 请用 IIS 证书绑定或 win-acme。</div></div>` : ''}
      <div class="grid g3">
        ${isWin ? `<div class="card stat">
          <div class="stat-label">IIS (W3SVC)</div>
          <div class="stat-value" style="font-size:22px;color:${iis.active ? '#4ade80' : '#f87171'}">${iis.installed ? (iis.active ? '运行中' : '已停止') : '未安装'}</div>
          <div class="stat-sub">${fmt.esc(iis.info || '')}</div>
          <div class="mt row" style="gap:6px">
            ${iis.installed ? `<button class="btn btn-sm btn-ok" id="w-iis-start">启动</button><button class="btn btn-sm btn-danger" id="w-iis-stop">停止</button><button class="btn btn-sm" id="w-iis-reload">重启 IIS</button>` : '<span class="muted small">可选功能：设置 → 应用 → 可选功能 → IIS</span>'}
          </div>
        </div>` : ''}
        <div class="card stat">
          <div class="stat-label">Nginx</div>
          <div class="stat-value" style="font-size:22px;color:${n.active ? '#4ade80' : '#f87171'}">${n.installed ? (n.active ? '运行中' : '已停止') : '未安装'}</div>
          <div class="stat-sub">${fmt.esc(n.version || '')}</div>
          <div class="mt row" style="gap:6px">
            <button class="btn btn-sm" id="w-ngx-test">配置检测</button>
            ${n.installed ? `<button class="btn btn-sm" id="w-ngx-reload">重载</button>` : ''}
            ${n.active ? `<button class="btn btn-sm btn-danger" id="w-ngx-stop">停止</button>` : `<button class="btn btn-sm btn-ok" id="w-ngx-start">启动</button>`}
          </div>
          <div class="stat-sub mt">配置校验: ${n.testOk ? ui.badge('通过', 'ok') : ui.badge('异常', 'bad')}</div>
        </div>
        ${isWin ? '' : `<div class="card stat">
          <div class="stat-label">Apache2</div>
          <div class="stat-value" style="font-size:22px;color:${d.apache.active ? '#4ade80' : '#7f92a8'}">${d.apache.installed ? (d.apache.active ? '运行中' : '已停止') : '未安装'}</div>
          <div class="stat-sub">${fmt.esc(String(d.apache.info || '').split('\n')[0])}</div>
        </div>`}
        <div class="card stat">
          <div class="stat-label">SSL 证书${isWin ? ' / win-acme' : " (Let's Encrypt)"}</div>
          <div class="stat-value" style="font-size:22px">${d.certs.length}</div>
          <div class="stat-sub">${isWin ? (d.certbotInstalled ? 'certbot 可用' : '推荐 IIS 管理器或 win-acme') : (d.certbotInstalled ? 'certbot 已安装' : 'certbot 未安装')}</div>
          ${d.certbotInstalled
            ? `<div class="mt"><button class="btn btn-sm" id="w-renew">续期证书</button></div>`
            : `<div class="mt"><button class="btn btn-sm btn-ok" id="w-certbot-install">+ 安装 certbot</button></div>`}
        </div>
      </div>

      <div class="card">
        <div class="card-title">Nginx 站点 (${n.sites.length})
          <div class="right"><button class="btn btn-sm" id="w-refresh">${icon('refresh', { size: 13 })} 刷新</button></div>
        </div>
        <div id="w-sites"></div>
      </div>

      ${isWin ? `<div class="card mb" id="w-iis-mgmt"><div class="card-title">${icon('server', { size: 13 })} IIS 管理 <span class="muted small" id="w-iis-note">加载中…</span></div><div id="w-iis-body" class="muted small">加载中…</div></div>` : ''}

      ${d.certs.length ? `<div class="card">
        <div class="card-title">证书详情</div>
        ${ui.table([
          { k: 'domain', label: '域名', cls: 'mono' },
          { k: 'expiry', label: '到期时间', cls: 'mono' },
          { k: 'daysLeft', label: '剩余天数', render: (v) => ui.badge(v + ' 天', v < 14 ? 'bad' : v < 30 ? 'warn' : 'ok') },
        ], d.certs)}
      </div>` : ''}

      <div class="card">
        <div class="card-title">说明</div>
        <div class="muted small">${isWin ? 'Windows 面板由 CloudMonitorDotNet 服务直接监听 <code>/panel/</code>，无需 Nginx。若使用 IIS 反代，请保留该站点的 <code>/panel</code> 转发规则。' : '本监控面板自身通过 nginx <code>/panel/</code> 路径反向代理（配置位于 <code>opencode-relay</code> 站点文件中的 location 块），不要停用该站点以免面板与中继服务同时下线。'}</div>
      </div>`;

      const renderSites = () => {
        const el = document.getElementById('w-sites');
        if (!n.sites.length) { el.innerHTML = '<div class="muted small">sites-enabled 为空</div>'; return; }
        const rows = [];
        for (const s of n.sites) {
          const blocks = s.blocks && s.blocks.length ? s.blocks : [{ serverName: '(无法解析)', listen: [], root: null, proxy: null }];
          blocks.forEach((b, i) => rows.push({ site: s, b, i }));
        }
        el.innerHTML = ui.table([
          { k: 'site', label: '站点文件', cls: 'mono', render: (v, r) => r.i === 0 ? fmt.esc(v.name) : '' },
          { k: 'b', label: 'server_name', cls: 'mono', render: (v) => fmt.esc(String(v.serverName).slice(0, 50)) },
          { k: 'b', label: '监听', cls: 'mono', render: (v) => fmt.esc((v.listen || []).join(', ')) },
          { k: 'b', label: '根目录 / 代理', cls: 'mono', render: (v) => fmt.esc(String(v.root || v.proxy || '-').slice(0, 44)) },
          { k: 'b', label: 'SSL', render: (v) => v.ssl ? ui.badge('HTTPS', 'ok') : '<span class="muted">—</span>' },
          { k: 'site', label: '操作', render: (v) => `<button class="btn btn-sm" data-site="${fmt.esc(v.name)}" data-enable="1">重新启用+重载</button>` },
        ], rows);
      };
      renderSites();

      document.getElementById('w-refresh').onclick = () => location.reload();
      const bind = (id, action, params, danger) => {
        const b = document.getElementById(id);
        if (b) b.onclick = async () => {
          if (danger) {
            const okc = await modal.confirm('确认操作', `执行 <b>${action}</b>？`, { danger: true });
            if (!okc) return;
          }
          runAction('/web/action', { action, params }, { reload: () => location.reload(), reloadDelay: 1200 }).catch(() => {});
        };
      };
      document.getElementById('w-ngx-test').innerHTML = icon('check', { size: 12 }) + ' 配置检测';
      document.getElementById('w-ngx-reload') && (document.getElementById('w-ngx-reload').innerHTML = icon('restart', { size: 12 }) + ' 重载');
      document.getElementById('w-ngx-stop') && (document.getElementById('w-ngx-stop').innerHTML = icon('stop', { size: 12 }) + ' 停止');
      document.getElementById('w-ngx-start') && (document.getElementById('w-ngx-start').innerHTML = icon('play', { size: 12 }) + ' 启动');
      bind('w-ngx-test', 'nginx-test', {}, false);
      bind('w-ngx-reload', 'nginx-reload', {}, false);
      bind('w-ngx-stop', 'nginx-stop', {}, true);
      bind('w-ngx-start', 'nginx-start', {}, false);
      if (isWin) {
        bind('w-iis-start', 'iis-start', {}, false);
        bind('w-iis-stop', 'iis-stop', {}, true);
        bind('w-iis-reload', 'iis-reload', {}, false);
      }
      bind('w-renew', 'cert-renew', {}, false);
      const cbInstall = document.getElementById('w-certbot-install');
      if (cbInstall) cbInstall.onclick = async () => {
        const okc = await modal.confirm('安装 certbot', isWin ? '将尝试 <code>winget install EFF.Certbot</code>（或使用 win-acme）。' : '执行 <code>apt-get install -y certbot</code>，约 1-3 分钟。', { okText: '开始安装' });
        if (!okc) return;
        ui.busy(cbInstall, true);
        try { await runAction('/pkg/install', { pkg: 'certbot' }, { showOutput: true, title: 'certbot 安装日志' }); location.reload(); }
        catch (_) { ui.busy(cbInstall, false); }
      };

      document.querySelectorAll('[data-site]').forEach((b) => {
        b.onclick = async () => {
          const okc = await modal.confirm('重新启用站点', `启用 <b class="mono">${fmt.esc(b.dataset.site)}</b> 并重载 nginx？`);
          if (!okc) return;
          runAction('/web/action', { action: 'site-toggle', params: { site: b.dataset.site, enable: true } }, { reload: () => location.reload() }).catch(() => {});
        };
      });

      /* ===== Windows：IIS 站点与应用池管理（原 Windows 工具页并入） ===== */
      if (isWin) this.renderIisMgmt();
    },

    async renderIisMgmt() {
      const esc = fmt.esc;
      let d;
      try { d = await CM.api.get('/windows/iis', 30000); }
      catch (e) {
        const el = document.getElementById('w-iis-body');
        if (el) el.innerHTML = `<span class="muted">${esc(e.message)}</span>`;
        return;
      }
      const bodyEl = document.getElementById('w-iis-body');
      const noteEl = document.getElementById('w-iis-note');
      if (!bodyEl) return;
      if (!d.available) {
        if (noteEl) noteEl.textContent = '';
        bodyEl.innerHTML = `<span class="muted">${esc(d.message || '未检测到 IIS。')}</span>`;
        return;
      }
      if (noteEl) noteEl.textContent = `${(d.sites || []).length} 个站点 · ${(d.pools || []).length} 个应用池`;
      const stBadge = (v) => /started/i.test(String(v)) ? ui.badge('运行', 'ok') : /stopped/i.test(String(v)) ? ui.badge('停止', 'gray') : ui.badge(String(v || '-'), 'warn');
      bodyEl.innerHTML = `
        ${ui.table([
          { k: 'name', label: '站点', cls: 'mono' },
          { k: 'state', label: '状态', render: stBadge },
          { k: 'bindings', label: '绑定', cls: 'mono small', render: (v) => '<span class="cmdline-clip" title="' + esc(v) + '">' + esc(String(v).slice(0, 60)) + '</span>' },
          { k: 'name', label: '操作', render: (v, r) => `<div class="row" style="gap:4px;flex-wrap:nowrap">
            ${/started/i.test(r.state) ? `<button class="btn btn-sm btn-danger" data-iis="site|stop|${esc(v)}">停止</button>`
          : `<button class="btn btn-sm btn-ok" data-iis="site|start|${esc(v)}">启动</button>`}</div>` },
        ], d.sites || [], { style: 'max-height:26vh', hint: false })}
        ${ui.table([
          { k: 'name', label: '应用池', cls: 'mono' },
          { k: 'state', label: '状态', render: stBadge },
          { k: 'runtime', label: '运行时', cls: 'small' },
          { k: 'name', label: '操作', render: (v, r) => `<div class="row" style="gap:4px;flex-wrap:nowrap">
            ${/started/i.test(r.state)
          ? `<button class="btn btn-sm" data-iis="apppool|recycle|${esc(v)}">回收</button><button class="btn btn-sm btn-danger" data-iis="apppool|stop|${esc(v)}">停止</button>`
          : `<button class="btn btn-sm btn-ok" data-iis="apppool|start|${esc(v)}">启动</button>`}</div>` },
        ], d.pools || [], { style: 'max-height:26vh', hint: false })}`;
      document.querySelectorAll('#w-iis-body [data-iis]').forEach((btn) => {
        btn.onclick = async () => {
          const [kind, action, name] = btn.dataset.iis.split('|');
          if (!await modal.confirm('IIS 操作', `对 <b class="mono">${esc(name)}</b> 执行「${esc(action)}」？`, { danger: action === 'stop' })) return;
          ui.busy(btn, true);
          try { await CM.api.post('/windows/iis/action', { kind, action, name }); window.toast('操作完成', 'ok'); this.renderIisMgmt(); }
          catch (e) { window.toast(e.message, 'err', 6000); ui.busy(btn, false); }
        };
      });
    },
  });
})();
