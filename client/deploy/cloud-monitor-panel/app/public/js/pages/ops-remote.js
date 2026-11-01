/* 运维增强 · 远程主机指标：对纳管主机跑固定只读命令包（loadavg/free/df/ss/uptime），
   SSH 凭据只存不回显（password 只给 hasPassword 布尔），定时采集 + 历史。 */
(function () {
  'use strict';

  registerPage({
    id: 'ops-remote',
    title: '远程主机指标',
    desc: '纳管主机的固定只读采集（load/内存/磁盘/对外端口/关键单元）· 凭据脱敏 · 定时调度与历史',
    async mount(root) {
      root.innerHTML = '<div class="card">' + ui.skeleton(5) + '</div>';
      let data = {};
      let historyHours = 24;

      async function load() {
        const out = {};
        await Promise.all(['/ops/remote', `/ops/remote/history?hours=${historyHours}`, '/ops/hosts'].map(async (p) => {
          const key = p.split('?')[0].split('/').pop();
          try { out[key] = await CM.api.get(p, 0); } catch (e) { out[key] = { __error: e.message }; }
        }));
        data = out;
        render();
      }

      function render() {
        const r = data.remote || {}, hs = data.hosts || {};
        if (r.__error) { root.innerHTML = `<div class="card"><div class="muted">读取失败：${fmt.esc(r.__error)}（需部署 ops 模块）</div></div>`; return; }
        const hosts = hs.items || [];
        const nameOf = (id) => ((hosts.find((h) => h.id === id) || {}).label) || id;
        const items = (r.items || []).map((x) => Object.assign({}, x, {
          _label: x.label || nameOf(x.id),
          _load: x.load1 != null ? Math.round(x.load1 * 100) / 100 : null,
          _online: x.online ? ui.badge('在线', 'ok') : x.lastOk === false ? `<span title="${fmt.esc(x.lastError || '')}">${ui.badge('失败', 'bad')}</span>` : '<span class="muted">未采集</span>',
          _at: x.lastAt ? new Date(x.lastAt).toLocaleString('zh-CN', { hour12: false }) : '—',
          _cred: x.hasCred ? ui.badge(x.authMode === 'password' ? '密码' : '私钥', '') : '<span class="muted">未配置</span>',
          _ports: (x.ports || []).slice(0, 6).join(', '),
        }));
        const hist = ((data.history || {}).items || []).map((x) => Object.assign({}, x, {
          _label: nameOf(x.id),
          _t: new Date(x.ts).toLocaleString('zh-CN', { hour12: false }),
        }));

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">纳管主机</div><div class="stat-value">${hosts.length}</div><div class="stat-sub">已配凭据 ${(r.items || []).filter((x) => x.hasCred).length}</div></div>
          <div class="card stat"><div class="stat-label">在线</div><div class="stat-value">${(r.items || []).filter((x) => x.online).length}</div><div class="stat-sub">上次采集成功 ${(r.items || []).filter((x) => x.lastOk).length}</div></div>
          <div class="card stat"><div class="stat-label">采集调度</div><div class="stat-value" style="font-size:18px">${r.scheduled ? '每 ' + r.intervalSec + 's' : '未启用'}</div><div class="stat-sub">到期待采 ${r.due || 0} · 执行器 ${r.runner === 'ssh2' ? 'ssh2' : (r.runner || '—')}</div></div>
          <div class="card stat"><div class="stat-label">采集范围</div><div class="stat-sub" style="margin-top:10px">固定只读命令包：loadavg/free/df/ps top/ss 对外端口/uptime/os-release/nproc/关键单元 —— 不接受自定义脚本</div></div>
        </div>

        <div class="card mb">
          <div class="card-title">主机指标 (${items.length})
            <div class="right" style="display:flex;gap:6px">
              <button class="btn btn-sm" id="rm-refresh">${icon('refresh', { size: 12 })} 刷新</button>
            </div>
          </div>
          ${ui.table([
            { k: '_label', label: '主机' },
            { k: '_online', label: '状态', render: (v) => v },
            { k: '_load', label: 'load1', cls: 'num', render: (v) => v != null ? v : '—' },
            { k: 'memPct', label: '内存', render: (v) => v != null ? `${ui.bar(Math.round(v))} <span class="small mono">${Math.round(v)}%</span>` : '—' },
            { k: 'diskMaxPct', label: '最大磁盘', render: (v) => v != null ? `${ui.bar(v, v >= 90 ? 'bad' : v >= 75 ? 'warn' : 'ok')} <span class="small mono">${v}%</span>` : '—' },
            { k: 'cores', label: '核', cls: 'num' },
            { k: 'os', label: '系统', cls: 'small', render: (v) => fmt.esc(String(v || '').slice(0, 34)) },
            { k: 'uptimeLine', label: '运行时长', cls: 'small', render: (v) => fmt.esc(String(v || '').replace(/^.*up\s+/i, 'up ').slice(0, 30)) },
            { k: '_ports', label: '对外端口', cls: 'mono small' },
            { k: '_cred', label: '凭据', render: (v) => v },
            { k: '_at', label: '采集时间', cls: 'mono small' },
            { k: '', label: '操作', render: (v, x) => `
              <div style="display:flex;gap:4px;flex-wrap:wrap">
                <button class="btn btn-sm" data-act="collect" data-id="${fmt.esc(x.id)}">采集</button>
                <button class="btn btn-sm" data-act="cred" data-id="${fmt.esc(x.id)}">凭据</button>
                ${x.hasCred ? `<button class="btn btn-sm btn-danger" data-act="delcred" data-id="${fmt.esc(x.id)}">删凭据</button>` : ''}
              </div>` },
          ], items, { style: 'max-height:440px' })}
          <div class="small muted" style="padding:8px 16px">凭据只存不回显（接口只返回 hasPassword / hasKey 布尔与脱敏用户名）。采集走 SSH 执行固定只读命令包。</div>
        </div>

        <div class="card">
          <div class="card-title">采集历史（近 ${historyHours} 小时）
            <div class="right" style="display:flex;gap:6px">
              ${[6, 24, 72].map((h) => `<button class="btn btn-sm ${historyHours === h ? 'btn-primary' : ''}" data-h="${h}">${h}h</button>`).join('')}
            </div>
          </div>
          ${ui.table([
            { k: '_t', label: '时间', cls: 'mono' },
            { k: '_label', label: '主机' },
            { k: 'load1', label: 'load1', cls: 'num', render: (v) => v != null ? Math.round(v * 100) / 100 : '—' },
            { k: 'memPct', label: '内存%', cls: 'num' },
            { k: 'diskMaxPct', label: '磁盘%', cls: 'num' },
            { k: 'ms', label: '耗时', cls: 'num', render: (v) => v != null ? v + ' ms' : '—' },
          ], hist.slice(0, 200), { style: 'max-height:320px' })}
        </div>`;
        bind();
      }

      function bind() {
        root.querySelectorAll('[data-act]').forEach((b) => {
          b.onclick = async () => {
            const id = b.dataset.id;
            const item = (data.remote.items || []).find((x) => x.id === id);
            try {
              if (b.dataset.act === 'collect') {
                toast('SSH 采集中…', 'info');
                await CM.api.post('/ops/remote/collect', { id });
                toast('采集完成', 'ok'); await load();
              } else if (b.dataset.act === 'cred') {
                const f = await modal.prompt('配置 SSH 凭据（只存不回显）', [
                  { name: 'user', label: 'SSH 用户', value: (item && item.credUser) || 'root' },
                  { name: 'port', label: 'SSH 端口', value: (item && item.credPort) || 22 },
                  { name: 'authMode', label: '认证方式', type: 'select', options: ['key', 'password'], value: (item && item.authMode) || 'key' },
                  { name: 'keyPath', label: '私钥路径（authMode=key，服务器上的文件路径）', value: '' },
                  { name: 'password', label: '密码（authMode=password）' },
                ], { okText: '保存', width: '560px' });
                if (!f) return;
                await CM.api.post('/ops/remote/cred', {
                  id, user: f.user, port: Number(f.port) || 22, authMode: f.authMode,
                  keyPath: f.authMode === 'key' ? f.keyPath : undefined,
                  password: f.authMode === 'password' ? f.password : undefined,
                });
                toast('凭据已保存', 'ok'); await load();
              } else if (b.dataset.act === 'delcred') {
                if (await modal.confirm('删除凭据', '删除后该主机停止采集（台账保留）。', { danger: true, okText: '删除' })) {
                  await CM.api.post('/ops/remote/cred/delete', { id }); await load();
                }
              }
            } catch (e) { toast(e.message, 'err'); }
          };
        });
        root.querySelectorAll('[data-h]').forEach((b) => {
          b.onclick = () => { historyHours = Number(b.dataset.h); load(); };
        });
        const rf = document.getElementById('rm-refresh');
        if (rf) rf.onclick = () => load();
      }

      await load();
    },
  });
})();
