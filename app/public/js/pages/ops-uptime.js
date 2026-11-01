/* 运维增强 · 可用性拨测：任务 CRUD / 立即拨测 / SLA / 探活历史 / 不可用事件。
   服务端 ops/uptime.js；字段与 KMP 客户端 OpsUptimePage 同源。 */
(function () {
  'use strict';

  const STATE_LABEL = { up: '正常', down: '故障', flapping: '抖动', pending: '待探测' };
  const stateBadge = (s) => ui.badge(STATE_LABEL[s] || s, s === 'up' ? 'ok' : s === 'down' ? 'bad' : s === 'flapping' ? 'warn' : '');
  const TYPES = [['http', 'HTTP'], ['https', 'HTTPS'], ['tcp', 'TCP 端口'], ['icmp', 'ICMP Ping'], ['dns', 'DNS 解析']];
  const typeLabel = (t) => (TYPES.find((x) => x[0] === t) || [t, t])[1];

  registerPage({
    id: 'ops-uptime',
    title: '可用性拨测',
    desc: 'HTTP/TCP/ICMP/DNS 拨测 · 连续失败防抖 · SLA 按日聚合 · 到期告警',
    async mount(root) {
      root.innerHTML = '<div class="card">' + ui.skeleton(6) + '</div>';
      let slaDays = 7;
      let data = {};

      async function load() {
        const out = {};
        await Promise.all([
          '/ops/uptime/tasks', '/ops/uptime/sla?days=' + slaDays, '/ops/alerts/events?limit=20',
        ].map(async (p) => {
          try { out[p.split('?')[0].split('/').pop()] = await CM.api.get(p, 0); }
          catch (e) { out[p.split('?')[0].split('/').pop()] = { __error: e.message }; }
        }));
        data = out;
        render();
      }

      function render() {
        const t = data.tasks || {}, sla = data.sla || {}, ev = data.events || {};
        if (t.__error) { root.innerHTML = `<div class="card"><div class="muted">读取失败：${fmt.esc(t.__error)}（需部署 ops 模块）</div></div>`; return; }
        const s = t.summary || {};
        const items = (t.items || []).map((x) => Object.assign({}, x, {
          _target: x.type === 'http' || x.type === 'https' ? x.url : (x.type === 'dns' ? x.host : `${x.host || '-'}:${x.port || '-'}`),
          _last: x.lastTs ? new Date(x.lastTs).toLocaleString('zh-CN', { hour12: false }) : '—',
          _err: (x.lastError || '').slice(0, 60),
        }));

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">任务</div><div class="stat-value">${s.total || 0}</div><div class="stat-sub">启用 ${s.enabled || 0}</div></div>
          <div class="card stat"><div class="stat-label">正常 / 故障</div><div class="stat-value">${s.up || 0} / <span style="color:${(s.down || 0) > 0 ? '#cf3b3b' : 'inherit'}">${s.down || 0}</span></div><div class="stat-sub">抖动 ${s.flapping || 0} · 待探测 ${s.pending || 0}</div></div>
          <div class="card stat"><div class="stat-label">30 天 SLA</div><div class="stat-value">${s.sla30 != null ? s.sla30 + '%' : '—'}</div><div class="stat-sub">按全部任务聚合</div></div>
          <div class="card stat"><div class="stat-label">证书临期</div><div class="stat-value">${s.certExpiring || 0}</div><div class="stat-sub">低于任务告警线</div></div>
        </div>
        ${(s.downNames || []).length ? `<div class="card mb" style="border-left:3px solid #cf3b3b"><div class="small">当前故障：${s.downNames.map(fmt.esc).join('、')}</div></div>` : ''}

        <div class="card mb">
          <div class="card-title">拨测任务 (${items.length})
            <div class="right" style="display:flex;gap:6px">
              <button class="btn btn-sm btn-primary" id="up-add">${icon('plus', { size: 12 })} 新建任务</button>
              <button class="btn btn-sm" id="up-refresh">${icon('refresh', { size: 12 })} 刷新</button>
            </div>
          </div>
          ${ui.table([
            { k: 'name', label: '名称', cls: 'mono' },
            { k: 'type', label: '类型', render: (v) => `<span class="muted">${typeLabel(v)}</span>` },
            { k: '_target', label: '目标', cls: 'mono small' },
            { k: 'state', label: '状态', render: (v) => stateBadge(v) },
            { k: 'lastMs', label: '耗时', cls: 'mono', render: (v, r) => r.lastOk === true ? v + ' ms' : (r.lastOk === false ? `<span style="color:#cf3b3b">${fmt.esc(r._err)}</span>` : '—') },
            { k: 'intervalSec', label: '间隔', cls: 'mono', render: (v) => v + 's' },
            { k: 'certDays', label: '证书', cls: 'mono', render: (v) => v != null ? v + ' 天' : '—' },
            { k: 'channelIds', label: '通知', render: (v) => (v || []).length ? v.length + ' 渠道' : '<span class="muted">—</span>' },
            { k: '_last', label: '最近探测', cls: 'mono small' },
            { k: '', label: '操作', render: (v, r) => `
              <div style="display:flex;gap:4px;flex-wrap:wrap">
                <button class="btn btn-sm" data-act="run" data-id="${fmt.esc(r.id)}">拨测</button>
                <button class="btn btn-sm" data-act="toggle" data-id="${fmt.esc(r.id)}">${r.enabled ? '停用' : '启用'}</button>
                <button class="btn btn-sm" data-act="edit" data-id="${fmt.esc(r.id)}">编辑</button>
                <button class="btn btn-sm btn-danger" data-act="del" data-id="${fmt.esc(r.id)}">删除</button>
              </div>` },
          ], items, { style: 'max-height:440px' })}
        </div>

        <div class="grid g2 mb">
          <div class="card">
            <div class="card-title">SLA 按日聚合（近 ${sla.days || slaDays} 天，整体 ${(sla.overall != null ? sla.overall : '—')}%）
              <div class="right" style="display:flex;gap:6px;align-items:center">
                ${[7, 14, 30].map((d) => `<button class="btn btn-sm ${slaDays === d ? 'btn-primary' : ''}" data-sla="${d}">${d}天</button>`).join('')}
              </div>
            </div>
            ${ui.table([
              { k: 'name', label: '任务', cls: 'mono' },
              { k: 'checks', label: '探测数', cls: 'num' },
              { k: 'downChecks', label: '失败', cls: 'num' },
              { k: 'availability', label: '可用率', cls: 'num', render: (v) => v != null ? ui.bar(v) + ` <span class="small mono">${v}%</span>` : '—' },
              { k: 'avgMs', label: '均耗时', cls: 'num', render: (v) => v != null ? v + ' ms' : '—' },
              { k: 'downtimeSec', label: '累计停机', cls: 'num', render: (v) => fmt.uptime(v) },
            ], sla.tasks || [], { style: 'max-height:340px' })}
          </div>
          <div class="card">
            <div class="card-title">不可用 / 恢复事件</div>
            ${ui.table([
              { k: 'ts', label: '时间', cls: 'mono', render: (v) => new Date(v * 1000).toLocaleString('zh-CN', { hour12: false }) },
              { k: 'name', label: '任务', cls: 'mono' },
              { k: 'state', label: '事件', render: (v) => stateBadge(v) },
              { k: 'error', label: '原因', cls: 'small', render: (v) => fmt.esc((v || '').slice(0, 80)) },
              { k: 'ms', label: '耗时', cls: 'num' },
            ], ev.items || [], { style: 'max-height:340px' })}
          </div>
        </div>`;
        bind();
      }

      function taskForm(existing) {
        const r = existing || {};
        return modal.prompt(existing ? '编辑拨测任务' : '新建拨测任务', [
          { name: 'name', label: '任务名称', value: r.name || '' },
          { name: 'type', label: '类型', type: 'select', options: TYPES.map((x) => x[0]), value: r.type || 'https' },
          { name: 'url', label: 'URL（http/https 必填，如 https://example.com/health）', value: r.url || '' },
          { name: 'host', label: '主机（tcp/icmp/dns 必填；dns 填要解析的域名）', value: r.host || '' },
          { name: 'port', label: '端口（tcp 必填）', value: r.port || '' },
          { name: 'expectStatus', label: '期望状态码区间（如 200-299，可空）', value: r.expectStatus || '' },
          { name: 'keyword', label: '响应关键字（可空）', value: r.keyword || '' },
          { name: 'intervalSec', label: '探测间隔（秒）', value: r.intervalSec || 60 },
          { name: 'failThreshold', label: '连续失败几次判故障', value: r.failThreshold || 3 },
          { name: 'certWarnDays', label: '证书剩余天数告警线（http/https）', value: r.certWarnDays || 14 },
          { name: 'channelIds', label: '通知渠道 id（逗号分隔，可空=仅面板铃铛）', value: (r.channelIds || []).join(',') },
          { name: 'group', label: '分组（可空）', value: r.group || '' },
          { name: 'note', label: '备注', value: r.note || '' },
        ], { okText: '保存', width: '560px' });
      }

      function bind() {
        root.querySelectorAll('[data-act]').forEach((b) => {
          b.onclick = async () => {
            const id = b.dataset.id;
            const item = (data.tasks.items || []).find((x) => x.id === id);
            try {
              if (b.dataset.act === 'run') {
                toast('拨测中…', 'info');
                const r = await CM.api.post('/ops/uptime/task/run', { id });
                const t = (r && r.task) || {};
                toast(`${t.name || ''}：${t.state || '?'}${t.lastError ? ' · ' + t.lastError : ''}`, t.state === 'up' ? 'ok' : 'warn');
                await load();
              } else if (b.dataset.act === 'toggle') {
                await CM.api.post('/ops/uptime/task/toggle', { id }); await load();
              } else if (b.dataset.act === 'del') {
                if (await modal.confirm('删除任务', `删除拨测任务 <b class="mono">${fmt.esc(item && item.name || id)}</b>？（历史与 SLA 保留）`, { danger: true, okText: '删除' })) {
                  await CM.api.post('/ops/uptime/task/delete', { id }); await load();
                }
              } else if (b.dataset.act === 'edit') {
                const f = await taskForm(item);
                if (!f) return;
                await CM.api.post('/ops/uptime/task/save', buildBody(f, id));
                toast('已保存', 'ok'); await load();
              }
            } catch (e) { toast(e.message, 'err'); }
          };
        });
        root.querySelectorAll('[data-sla]').forEach((b) => {
          b.onclick = () => { slaDays = Number(b.dataset.sla); load(); };
        });
        const add = document.getElementById('up-add');
        if (add) add.onclick = async () => {
          const f = await taskForm(null);
          if (!f) return;
          try { await CM.api.post('/ops/uptime/task/save', buildBody(f, null)); toast('已创建', 'ok'); await load(); }
          catch (e) { toast(e.message, 'err'); }
        };
        const rf = document.getElementById('up-refresh');
        if (rf) rf.onclick = () => load();
      }

      function buildBody(f, id) {
        const numv = (v, d) => (String(v).trim() === '' ? d : Number(v));
        return {
          id: id || undefined,
          name: f.name, type: f.type, url: f.url, host: f.host, port: numv(f.port, 0),
          method: 'GET', expectStatus: f.expectStatus, keyword: f.keyword,
          timeoutMs: 5000, intervalSec: numv(f.intervalSec, 60), failThreshold: numv(f.failThreshold, 3),
          certWarnDays: numv(f.certWarnDays, 14),
          channelIds: String(f.channelIds || '').split(/[\s,]+/).filter(Boolean),
          group: f.group, note: f.note, enabled: true,
        };
      }

      await load();
    },
  });
})();
