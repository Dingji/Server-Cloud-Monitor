/* 运维增强 · 纳管主机台账：用途/环境/负责人/标签/面板地址 + SSH 端口可达性探测（不发送凭据）。 */
(function () {
  'use strict';

  registerPage({
    id: 'ops-hosts',
    title: '纳管主机',
    desc: '主机台账（用途/环境/负责人/标签）· SSH banner 可达性探测 · 批量探测统计',
    async mount(root) {
      root.innerHTML = '<div class="card">' + ui.skeleton(5) + '</div>';
      let data = {};

      async function load() {
        try { data = await CM.api.get('/ops/hosts', 0); }
        catch (e) { data = { __error: e.message }; }
        render();
      }

      function render() {
        if (data.__error) { root.innerHTML = `<div class="card"><div class="muted">读取失败：${fmt.esc(data.__error)}（需部署 ops 模块）</div></div>`; return; }
        const items = (data.items || []).map((x) => Object.assign({}, x, {
          _addr: x.addr ? `${x.addr}:${x.sshPort || 22}` : '<span class="muted">未填地址</span>',
          _tags: (x.tags || []).map((t) => `<span class="badge">${fmt.esc(t)}</span>`).join(' '),
          _check: x.lastCheckOk === true ? ui.badge('可达', 'ok') : x.lastCheckOk === false ? `<span title="${fmt.esc(x.lastCheckError || '')}">${ui.badge('不通', 'bad')}</span>` : '<span class="muted">未探测</span>',
          _seen: x.lastSeenAt ? new Date(x.lastSeenAt).toLocaleString('zh-CN', { hour12: false }) : '—',
          _panel: x.panelUrl ? `<a href="${fmt.esc(x.panelUrl)}" target="_blank" rel="noopener" class="mono small">${fmt.esc(x.panelUrl.replace(/^https?:\/\//, ''))}</a>` : '<span class="muted">—</span>',
        }));
        const stats = data.stats || {};
        const byRole = stats.byRole || {}, byEnv = stats.byEnv || {};

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">主机</div><div class="stat-value">${stats.total || 0}</div><div class="stat-sub">可达 ${stats.reachable || 0} · 不通 ${stats.unreachable || 0}</div></div>
          <div class="card stat"><div class="stat-label">未探测 / 无地址</div><div class="stat-value">${stats.unchecked || 0} / ${stats.withoutAddr || 0}</div><div class="stat-sub">探测只读 banner，不发凭据</div></div>
          <div class="card stat"><div class="stat-label">按用途</div><div class="stat-sub" style="margin-top:10px">${Object.entries(byRole).map(([k, v]) => `${fmt.esc(k || '未分类')} ${v}`).join(' · ') || '—'}</div></div>
          <div class="card stat"><div class="stat-label">按环境</div><div class="stat-sub" style="margin-top:10px">${Object.entries(byEnv).map(([k, v]) => `${fmt.esc(k || '未分类')} ${v}`).join(' · ') || '—'}</div></div>
        </div>

        <div class="card">
          <div class="card-title">主机台账 (${items.length})
            <div class="right" style="display:flex;gap:6px">
              <button class="btn btn-sm" id="ht-check">${icon('scan', { size: 12 })} 全部探测</button>
              <button class="btn btn-sm btn-primary" id="ht-add">${icon('plus', { size: 12 })} 纳管主机</button>
              <button class="btn btn-sm" id="ht-refresh">${icon('refresh', { size: 12 })} 刷新</button>
            </div>
          </div>
          ${ui.table([
            { k: 'label', label: '名称' },
            { k: '_addr', label: 'SSH 地址', cls: 'mono', render: (v) => v },
            { k: 'role', label: '用途' },
            { k: 'env', label: '环境', render: (v) => v ? ui.badge(v, v === 'prod' ? 'bad' : v === 'staging' ? 'warn' : '') : '<span class="muted">—</span>' },
            { k: 'owner', label: '负责人' },
            { k: '_tags', label: '标签', render: (v) => v || '<span class="muted">—</span>' },
            { k: '_panel', label: '面板地址' },
            { k: '_check', label: '可达性' },
            { k: '_seen', label: '最近探测', cls: 'mono small' },
            { k: '', label: '操作', render: (v, x) => `
              <div style="display:flex;gap:4px;flex-wrap:wrap">
                <button class="btn btn-sm" data-act="check" data-id="${fmt.esc(x.id)}">探测</button>
                <button class="btn btn-sm" data-act="edit" data-id="${fmt.esc(x.id)}">编辑</button>
                <button class="btn btn-sm btn-danger" data-act="del" data-id="${fmt.esc(x.id)}">移除</button>
              </div>` },
          ], items, { style: 'max-height:520px' })}
          <div class="small muted" style="padding:8px 16px">可达性探测 = 打开 SSH 端口读一行 banner（可顺带看到对端 OpenSSH 版本），不发送任何凭据。</div>
        </div>`;
        bind();
      }

      function hostForm(x) {
        return modal.prompt(x ? '编辑主机' : '纳管主机', [
          { name: 'label', label: '名称', value: (x && x.label) || '' },
          { name: 'addr', label: '地址（IP 或域名，可空=仅台账）', value: (x && x.addr) || '' },
          { name: 'sshPort', label: 'SSH 端口', value: (x && x.sshPort) || 22 },
          { name: 'role', label: '用途（如 web/db/gateway）', value: (x && x.role) || '' },
          { name: 'env', label: '环境', type: 'select', options: ['prod', 'staging', 'dev', 'test'], value: (x && x.env) || 'prod' },
          { name: 'owner', label: '负责人', value: (x && x.owner) || '' },
          { name: 'tags', label: '标签（逗号分隔）', value: ((x && x.tags) || []).join(',') },
          { name: 'panelUrl', label: '面板地址（可空）', value: (x && x.panelUrl) || '' },
          { name: 'note', label: '备注', value: (x && x.note) || '' },
        ], { okText: '保存', width: '560px' });
      }

      function bind() {
        root.querySelectorAll('[data-act]').forEach((b) => {
          b.onclick = async () => {
            const id = b.dataset.id;
            const item = (data.items || []).find((x) => x.id === id);
            try {
              if (b.dataset.act === 'check') {
                toast('探测中…', 'info');
                const r = await CM.api.post('/ops/host/check', { id });
                const c = (r && r.check) || {};
                toast(c.ok ? `可达：${c.banner || ''}` : '不通：' + (c.error || ''), c.ok ? 'ok' : 'err');
                await load();
              } else if (b.dataset.act === 'del') {
                if (await modal.confirm('移除主机', `把 <b class="mono">${fmt.esc(item && item.label || id)}</b> 移出台账？`, { danger: true, okText: '移除' })) {
                  await CM.api.post('/ops/host/delete', { id }); await load();
                }
              } else if (b.dataset.act === 'edit') {
                const f = await hostForm(item);
                if (!f) return;
                await CM.api.post('/ops/host/save', hostBody(f, id));
                toast('已保存', 'ok'); await load();
              }
            } catch (e) { toast(e.message, 'err'); }
          };
        });
        const add = document.getElementById('ht-add');
        if (add) add.onclick = async () => {
          const f = await hostForm(null);
          if (!f) return;
          try { await CM.api.post('/ops/host/save', hostBody(f, null)); toast('已纳管', 'ok'); await load(); }
          catch (e) { toast(e.message, 'err'); }
        };
        const chk = document.getElementById('ht-check');
        if (chk) chk.onclick = async () => {
          if (!await modal.confirm('批量探测', '对台账内所有已填地址的主机依次打开 SSH 端口读 banner（可能需要数十秒）。', { okText: '开始' })) return;
          toast('批量探测中…', 'info');
          try { await CM.api.post('/ops/host/check', {}); toast('批量探测完成', 'ok'); await load(); }
          catch (e) { toast(e.message, 'err'); }
        };
        const rf = document.getElementById('ht-refresh');
        if (rf) rf.onclick = () => load();
      }

      function hostBody(f, id) {
        return {
          id: id || undefined, label: f.label, addr: f.addr, sshPort: Number(f.sshPort) || 22,
          role: f.role, env: f.env, owner: f.owner,
          tags: String(f.tags || '').split(/[\s,]+/).filter(Boolean),
          panelUrl: f.panelUrl, note: f.note,
        };
      }

      await load();
    },
  });
})();
