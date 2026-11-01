/* Network page */
(function () {
  'use strict';
  let trafficChart = null, auto = null, d = null;

  registerPage({
    id: 'network',
    title: '网络管理',
    async mount(root) {
      d = await CM.api.get('/network');
      root.innerHTML = `
      <div class="grid g3" id="n-ifaces"></div>
      <div class="grid g-2-1">
        <div class="card">
          <div class="card-title">实时流量 (近 60 秒)</div>
          <div class="chart-box"><canvas id="n-ch"></canvas></div>
        </div>
        <div class="card">
          <div class="card-title">路由 / DNS</div>
          <div class="muted small">默认网关</div>
          <div class="mono small mb" id="n-gw">${d.defaultRoute ? fmt.esc(d.defaultRoute.gw + ' via ' + d.defaultRoute.dev) : '—'}</div>
          <div class="muted small">DNS</div>
          <pre class="pre" style="max-height:150px">${fmt.esc(d.dns || '—')}</pre>
        </div>
      </div>
      <div class="card">
        <div class="card-title">监听端口 (<span id="n-listen-n">${d.listening.length}</span>)
          <div class="right">
            <label class="row small muted"><input type="checkbox" id="n-auto" checked> 自动刷新</label>
            <span class="mono small" id="n-rate"></span>
            <button class="btn btn-sm" id="n-refresh">${icon('refresh', { size: 13 })} 刷新</button>
          </div>
        </div>
        <div id="n-listen"><span class="muted small">加载中…</span></div>
      </div>
      <div class="card">
        <div class="card-title">已建立连接 (<span id="n-estab-n">${d.established.length}</span>)</div>
        <div id="n-estab"><span class="muted small">加载中…</span></div>
        <div class="muted small mt"><b>连接统计</b><pre class="pre" id="n-sum" style="max-height:130px">${fmt.esc(d.summary || '')}</pre></div>
      </div>
      <div class="card">
        <div class="card-title">防火墙 (UFW) ${d.ufwActive ? ui.badge('已启用', 'ok') : ui.badge('未启用', 'warn')}
          <div class="right">
            <button class="btn btn-sm ${d.ufwActive ? 'btn-danger' : 'btn-ok'}" id="n-ufw-toggle">${d.ufwActive ? '禁用防火墙' : '启用防火墙'}</button>
            <button class="btn btn-sm" id="n-ufw-reload">${icon('restart', { size: 12 })} 重载规则</button>
          </div>
        </div>
        <div class="row mb">
          <input class="inp mono" id="n-ufw-spec" placeholder="端口规格，如 8080/tcp 或 3306" style="width:260px">
          <button class="btn btn-ok" id="n-ufw-allow">+ 允许 (ALLOW)</button>
          <button class="btn btn-danger" id="n-ufw-deny">− 拒绝 (DENY)</button>
        </div>
        <div id="n-ufw-rules"></div>
        <div class="muted small mt">${icon('info', { size: 12 })} 禁用防火墙前请确认安全组仍在外层提供保护；启用前确保 22 端口已放行。防火墙规则表在执行操作后刷新。</div>
      </div>
      <div class="card">
        <div class="card-title">网卡开关</div>
        <div class="row" id="n-iface-btns"></div>
      </div>`;

      /* 以下区块每次刷新重画；交互区（ufw 表单、网卡按钮）保持不动 */
      const renderLive = () => {
        document.getElementById('n-ifaces').innerHTML = d.interfaces.map((i) => `
        <div class="card stat" style="margin-bottom:0">
          <div class="stat-label">${fmt.esc(i.name)} ${i.operstate === 'UP' ? ui.badge('UP', 'ok') : ui.badge(i.operstate || '?', 'gray')}</div>
          <div class="mono" style="font-size:15px;margin:8px 0 4px">${fmt.esc((i.ipv4 || []).join(', ') || '无 IPv4')}</div>
          <div class="stat-sub">${(i.ipv6 || []).map(fmt.esc).join(', ') || '无全局 IPv6'}</div>
          <div class="stat-sub">MAC ${fmt.esc(i.mac || '-')} · MTU ${i.mtu || '-'}</div>
        </div>`).join('');

        document.getElementById('n-listen-n').textContent = d.listening.length;
        document.getElementById('n-listen').innerHTML = ui.table([
          { k: 'netid', label: '协议' },
          { k: 'local', label: '本地地址', cls: 'mono' },
          { k: 'peer', label: '对端', cls: 'mono' },
          { k: 'process', label: '进程', cls: 'mono', render: (v) => fmt.esc(String(v || '').replace(/users:\(\("|"\)|,fd=\d+/g, '').slice(0, 60)) },
        ], d.listening, { style: 'max-height:300px' });

        document.getElementById('n-estab-n').textContent = d.established.length;
        document.getElementById('n-estab').innerHTML = ui.table([
          { k: 'local', label: '本地', cls: 'mono' },
          { k: 'peer', label: '远程', cls: 'mono' },
          { k: 'process', label: '进程', cls: 'mono', render: (v) => fmt.esc(String(v || '').replace(/users:\(\("|"\)|,fd=\d+/g, '').slice(0, 60)) },
        ], d.established, { style: 'max-height:260px' });
        document.getElementById('n-sum').textContent = d.summary || '';
      };

      const load = async () => { d = await CM.api.get('/network'); renderLive(); };
      renderLive();

      /* iface up/down buttons */
      const ib = document.getElementById('n-iface-btns');
      ib.innerHTML = d.interfaces.filter((i) => i.name !== 'lo').map((i) =>
        `<button class="btn btn-sm" data-iface="${fmt.esc(i.name)}">${fmt.esc(i.name)}: ${i.operstate === 'UP' ? '关闭' : '开启'}</button>`).join('');
      ib.querySelectorAll('[data-iface]').forEach((b) => {
        b.onclick = async () => {
          const dev = b.dataset.iface;
          const up = b.textContent.includes('开启');
          const okc = await modal.confirm(up ? '开启网卡' : '关闭网卡', `${up ? '启用' : '禁用'}网卡 <b class="mono">${fmt.esc(dev)}</b>？${up ? '' : ' <span style="color:#f87171">远程连接可能中断！</span>'}`, { danger: !up });
          if (!okc) return;
          runAction('/network/action', { action: up ? 'iface-up' : 'iface-down', params: { dev } }, { reload: () => location.reload() }).catch(() => {});
        };
      });

      /* ufw rules */
      const renderRules = (rules, raw) => {
        const el = document.getElementById('n-ufw-rules');
        if (rules && rules.length) {
          el.innerHTML = ui.table([
            { k: 'num', label: '#', cls: 'num' },
            { k: 'rule', label: '规则', cls: 'mono' },
            { k: 'action', label: '动作', render: (v) => ui.badge(v, v === 'ALLOW' ? 'ok' : 'bad') },
            { k: 'from', label: '来源', cls: 'mono' },
            { k: 'comment', label: '备注' },
            { k: 'num', label: '', render: (v) => `<button class="btn btn-sm btn-danger" data-del="${v}">删除</button>` },
          ], rules);
          el.querySelectorAll('[data-del]').forEach((btn) => {
            btn.onclick = async () => {
              const okc = await modal.confirm('删除防火墙规则', `删除 UFW 规则 #${btn.dataset.del}？`, { danger: true });
              if (!okc) return;
              try { const r = await runAction('/network/action', { action: 'ufw-delete', params: { num: Number(btn.dataset.del) } }); refreshUfw(); modal.output('删除结果', r.output); } catch (_) {}
            };
          });
        } else {
          el.innerHTML = `<pre class="pre" style="max-height:180px">${fmt.esc((raw || ['(防火墙未启用或无规则)']).join('\n'))}</pre>`;
        }
      };
      renderRules(d.ufwRules, d.ufwRaw);

      const refreshUfw = async () => {
        try {
          const s = await CM.api.get('/security');
          renderRules(s.ufw.rules, s.ufw.raw);
        } catch (_) {}
      };
      document.getElementById('n-ufw-allow').onclick = async () => {
        const spec = document.getElementById('n-ufw-spec').value.trim();
        if (!spec) return window.toast('请输入端口规格', 'warn');
        runAction('/network/action', { action: 'ufw-allow', params: { spec } }, { reload: refreshUfw }).catch(() => {});
      };
      document.getElementById('n-ufw-deny').onclick = async () => {
        const spec = document.getElementById('n-ufw-spec').value.trim();
        if (!spec) return window.toast('请输入端口规格', 'warn');
        runAction('/network/action', { action: 'ufw-deny', params: { spec } }, { reload: refreshUfw }).catch(() => {});
      };
      document.getElementById('n-ufw-toggle').onclick = async () => {
        const act = d.ufwActive ? 'ufw-disable' : 'ufw-enable';
        const okc = await modal.confirm(d.ufwActive ? '禁用防火墙' : '启用防火墙',
          d.ufwActive ? '禁用后服务器将无主机层防火墙保护！' : '将启用 UFW（默认拒绝入站）。请确认已允许 22 端口：ufw allow 22/tcp', { danger: true });
        if (!okc) return;
        runAction('/network/action', { action: act }, { reload: () => location.reload() }).catch(() => {});
      };
      document.getElementById('n-ufw-reload').onclick = () =>
        runAction('/network/action', { action: 'ufw-reload' }).catch(() => {});

      document.getElementById('n-refresh').onclick = () => load().catch((e) => window.toast(e.message, 'err'));

      /* live traffic chart */
      trafficChart = ui.chart(document.getElementById('n-ch'), {
        series: [{ label: '下行 KB/s', color: '#22c55e', fill: true }, { label: '上行 KB/s', color: '#38bdf8' }],
        yFormat: (v) => v + 'K', points: 60,
      });
      if (!CM._netTrafficSub) {
        CM._netTrafficSub = true;
        CM.hub.on('metrics', (msg) => {
          if (!trafficChart || !CM.router.current || CM.router.current.id !== 'network') return;
          const ifaces = (msg.data.net && msg.data.net.ifaces) || {};
          let rx = 0, tx = 0;
          for (const [n, v] of Object.entries(ifaces)) if (n !== 'lo') { rx += v.rxBps; tx += v.txBps; }
          trafficChart.push(fmt.ts(msg.data.ts), [rx / 1024, tx / 1024]);
        });
      }

      auto = CM.autoRefresh(() => load().catch(() => {}), { pageId: 'network' });
      document.getElementById('n-auto').onchange = (e) => auto.setEnabled(e.target.checked);
      document.getElementById('n-rate').textContent = CM.refreshLabel(auto.ms());
      auto.start();

      this.show();
    },
    show() {
      if (trafficChart) trafficChart.resize();
      const r = document.getElementById('n-rate');
      if (r && auto) r.textContent = CM.refreshLabel(auto.ms());
      if (auto && auto.isEnabled()) auto.start();
    },
    hide() { if (auto) auto.stop(); },
  });
})();
