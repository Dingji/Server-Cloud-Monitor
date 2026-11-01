/* 运维增强 · 服务看护：systemd/pgrep/TCP 三种探活、不可用自动拉起（窗口限次）、事件流水。 */
(function () {
  'use strict';

  /* 服务端 KINDS 的 key：systemd / process / port（看护目标随 kind 取 unit/match/host:port） */
  const KINDS = [['systemd', 'systemd 服务'], ['process', '进程关键字'], ['port', 'TCP 端口探活']];
  const kindLabel = (k) => (KINDS.find((x) => x[0] === k) || [k, k])[1];
  const statusBadge = (s) => ui.badge(s === 'up' ? '存活' : s === 'down' ? '不可用' : s === 'failing' ? '连续失败' : '未知',
    s === 'up' ? 'ok' : s === 'down' ? 'bad' : s === 'failing' ? 'warn' : '');

  registerPage({
    id: 'ops-watch',
    title: '服务看护',
    desc: '探活（systemd/pgrep/TCP）· 不可用自动拉起（窗口内限次，防无限重启）· 恢复与升级告警',
    async mount(root) {
      root.innerHTML = '<div class="card">' + ui.skeleton(5) + '</div>';
      let data = {};

      async function load() {
        try { data = { watchdog: await CM.api.get('/ops/watchdog?events=40', 0) }; }
        catch (e) { data = { watchdog: { __error: e.message } }; }
        render();
      }

      function render() {
        const w = data.watchdog || {}, ev = { items: w.events || [] };
        if (w.__error) { root.innerHTML = `<div class="card"><div class="muted">读取失败：${fmt.esc(w.__error)}（需部署 ops 模块）</div></div>`; return; }
        const items = (w.items || []).map((x) => Object.assign({}, x, {
          _kind: kindLabel(x.kind),
          _target: x.kind === 'systemd' ? (x.unit || '') : x.kind === 'pgrep' ? (x.match || '') : `${x.host || ''}:${x.port || ''}`,
          _last: x.lastCheck ? new Date(x.lastCheck).toLocaleString('zh-CN', { hour12: false }) : '—',
          _guard: x.restart ? `拉起（≤${x.maxRestarts} 次 / ${x.windowMin} 分）` : '仅告警不自愈',
        }));
        const s = w.stats || {};

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">看护项</div><div class="stat-value">${s.total || 0}</div><div class="stat-sub">启用 ${s.enabled || 0}</div></div>
          <div class="card stat"><div class="stat-label">不可用</div><div class="stat-value" style="color:${(s.down || 0) > 0 ? '#cf3b3b' : 'inherit'}">${s.down || 0}</div><div class="stat-sub">连续失败 ${s.failing || 0}</div></div>
          <div class="card stat"><div class="stat-label">24h 拉起</div><div class="stat-value">${s.restarts24 || 0}</div><div class="stat-sub">达上限自动停止并升级告警</div></div>
          <div class="card stat"><div class="stat-label">不自愈路径</div><div class="stat-value" style="font-size:15px">skip-restart /<br>no-start-cmd</div><div class="stat-sub">restart=false 或无启动命令</div></div>
        </div>

        <div class="card mb">
          <div class="card-title">看护项 (${items.length})
            <div class="right" style="display:flex;gap:6px">
              <button class="btn btn-sm btn-primary" id="wd-add">${icon('plus', { size: 12 })} 新建看护</button>
              <button class="btn btn-sm" id="wd-refresh">${icon('refresh', { size: 12 })} 刷新</button>
            </div>
          </div>
          ${ui.table([
            { k: 'name', label: '名称' },
            { k: '_kind', label: '探活' },
            { k: '_target', label: '目标', cls: 'mono small' },
            { k: 'status', label: '状态', render: (v, x) => statusBadge(v) + (x.lastError ? ` <span class="small muted">${fmt.esc(x.lastError.slice(0, 40))}</span>` : '') },
            { k: '_guard', label: '自愈策略', cls: 'small' },
            { k: 'restarts', label: '已拉起', cls: 'num' },
            { k: '_last', label: '最近检查', cls: 'mono small' },
            { k: '', label: '操作', render: (v, x) => `
              <div style="display:flex;gap:4px;flex-wrap:wrap">
                <button class="btn btn-sm" data-act="run" data-id="${fmt.esc(x.id)}">立即检查</button>
                <button class="btn btn-sm" data-act="reset" data-id="${fmt.esc(x.id)}">重置计数</button>
                <button class="btn btn-sm" data-act="toggle" data-id="${fmt.esc(x.id)}">${x.enabled ? '停用' : '启用'}</button>
                <button class="btn btn-sm" data-act="edit" data-id="${fmt.esc(x.id)}">编辑</button>
                <button class="btn btn-sm btn-danger" data-act="del" data-id="${fmt.esc(x.id)}">删除</button>
              </div>` },
          ], items, { style: 'max-height:420px' })}
        </div>

        <div class="card">
          <div class="card-title">看护事件（最近 ${(ev.items || []).length} 条）</div>
          ${ui.table([
            { k: 'ts', label: '时间', cls: 'mono', render: (v) => new Date(v).toLocaleString('zh-CN', { hour12: false }) },
            { k: 'name', label: '看护项' },
            { k: 'event', label: '事件', render: (v) => ui.badge(v, v === 'recovered' ? 'ok' : v === 'down' ? 'bad' : 'warn') },
            { k: 'detail', label: '详情', cls: 'small', render: (v) => fmt.esc(String(v || '').slice(0, 100)) },
          ], ev.items || [], { style: 'max-height:300px' })}
        </div>`;
        bind();
      }

      function watchForm(x) {
        const r = x || {};
        return modal.prompt(x ? '编辑看护项' : '新建看护项', [
          { name: 'name', label: '名称', value: r.name || '' },
          { name: 'kind', label: '探活方式', type: 'select', options: KINDS.map((k) => k[0]), value: r.kind || 'systemd' },
          { name: 'unit', label: 'systemd 单元名（kind=systemd）', value: r.unit || '' },
          { name: 'match', label: '进程关键字（kind=pgrep）', value: r.match || '' },
          { name: 'host', label: 'TCP 主机（kind=tcp）', value: r.host || '127.0.0.1' },
          { name: 'port', label: 'TCP 端口（kind=tcp）', value: r.port || '' },
          { name: 'startCmd', label: '拉起命令（如 systemctl start xxx；空=不自愈只告警）', value: r.startCmd || '' },
          { name: 'restart', label: '不可用时自动拉起', type: 'select', options: ['是', '否'], value: r.restart === false ? '否' : '是' },
          { name: 'intervalSec', label: '检查间隔（秒）', value: r.intervalSec || 30 },
          { name: 'maxRestarts', label: '窗口内最多拉起次数（防无限重启）', value: r.maxRestarts || 3 },
          { name: 'windowMin', label: '限次窗口（分钟）', value: r.windowMin || 30 },
          { name: 'cooldownSec', label: '通知冷却（秒）', value: r.cooldownSec != null ? r.cooldownSec : 600 },
          { name: 'channelIds', label: '通知渠道 id（逗号分隔，空=仅面板）', value: (r.channelIds || []).join(',') },
          { name: 'note', label: '备注', value: r.note || '' },
        ], { okText: '保存', width: '560px' });
      }

      function bind() {
        root.querySelectorAll('[data-act]').forEach((b) => {
          b.onclick = async () => {
            const id = b.dataset.id;
            const item = (data.watchdog.items || []).find((x) => x.id === id);
            try {
              if (b.dataset.act === 'run') {
                toast('检查中…', 'info');
                const r = await CM.api.post('/ops/watchdog/run', { id });
                const it = (r && r.entry) || {};
                toast(`${it.name || ''}：${it.status || '?'}`, it.status === 'up' ? 'ok' : 'warn');
                await load();
              } else if (b.dataset.act === 'reset') {
                await CM.api.post('/ops/watchdog/reset', { id }); toast('计数已重置', 'ok'); await load();
              } else if (b.dataset.act === 'toggle') {
                await CM.api.post('/ops/watchdog/toggle', { id }); await load();
              } else if (b.dataset.act === 'del') {
                if (await modal.confirm('删除看护项', `删除 <b class="mono">${fmt.esc(item && item.name || id)}</b>？不再探活与自愈。`, { danger: true, okText: '删除' })) {
                  await CM.api.post('/ops/watchdog/delete', { id }); await load();
                }
              } else if (b.dataset.act === 'edit') {
                const f = await watchForm(item);
                if (!f) return;
                await CM.api.post('/ops/watchdog/save', body(f, id));
                toast('已保存', 'ok'); await load();
              }
            } catch (e) { toast(e.message, 'err'); }
          };
        });
        const add = document.getElementById('wd-add');
        if (add) add.onclick = async () => {
          const f = await watchForm(null);
          if (!f) return;
          try { await CM.api.post('/ops/watchdog/save', body(f, null)); toast('已创建', 'ok'); await load(); }
          catch (e) { toast(e.message, 'err'); }
        };
        const rf = document.getElementById('wd-refresh');
        if (rf) rf.onclick = () => load();
      }

      function body(f, id) {
        return {
          id: id || undefined, name: f.name, kind: f.kind, unit: f.unit, match: f.match,
          host: f.host, port: Number(f.port) || 0, startCmd: f.startCmd,
          restart: f.restart === '是', intervalSec: Number(f.intervalSec) || 30,
          maxRestarts: Number(f.maxRestarts) || 3, windowMin: Number(f.windowMin) || 30,
          cooldownSec: Number(f.cooldownSec) || 600,
          channelIds: String(f.channelIds || '').split(/[\s,]+/).filter(Boolean),
          note: f.note, enabled: true,
        };
      }

      await load();
    },
  });
})();
