/* Web servers page (nginx / apache / SSL) */
(function () {
  'use strict';

  registerPage({
    id: 'web',
    title: '网站 / Nginx',
    async mount(root) {
      const d = await CM.api.get('/web');
      const n = d.nginx;

      root.innerHTML = `
      <div class="grid g3">
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
        <div class="card stat">
          <div class="stat-label">Apache2</div>
          <div class="stat-value" style="font-size:22px;color:${d.apache.active ? '#4ade80' : '#7f92a8'}">${d.apache.installed ? (d.apache.active ? '运行中' : '已停止') : '未安装'}</div>
          <div class="stat-sub">${fmt.esc(String(d.apache.info || '').split('\n')[0])}</div>
        </div>
        <div class="card stat">
          <div class="stat-label">SSL 证书 (Let's Encrypt)</div>
          <div class="stat-value" style="font-size:22px">${d.certs.length}</div>
          <div class="stat-sub">${d.certbotInstalled ? 'certbot 已安装' : 'certbot 未安装'}</div>
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
        <div class="muted small">本监控面板自身通过 nginx <code>/panel/</code> 路径反向代理（配置位于 <code>opencode-relay</code> 站点文件中的 location 块），不要停用该站点以免面板与中继服务同时下线。</div>
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
      bind('w-renew', 'cert-renew', {}, false);
      const cbInstall = document.getElementById('w-certbot-install');
      if (cbInstall) cbInstall.onclick = async () => {
        const okc = await modal.confirm('安装 certbot', '执行 <code>apt-get install -y certbot</code>，约 1-3 分钟。', { okText: '开始安装' });
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
    },
  });
})();
