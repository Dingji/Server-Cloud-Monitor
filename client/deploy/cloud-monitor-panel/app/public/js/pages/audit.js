/* Audit trail page - auditd file-integrity events (relay config / keys / nginx / ssh) */
(function () {
  'use strict';

  const RANGES = [['1h', '最近 1 小时'], ['6h', '最近 6 小时'], ['24h', '最近 24 小时'], ['all', '当前日志全部']];
  const LIMITS = [100, 300, 500, 1000];

  registerPage({
    id: 'audit',
    title: '审计日志',
    desc: 'auditd 记录的敏感文件变更：谁、用什么命令、改了哪个文件',

    async mount(root) {
      root.innerHTML = `<div class="card">${ui.skeleton(6)}</div>`;

      let first;
      try { first = await CM.api.get('/relayops/audit?range=24h&limit=300'); }
      catch (e) { root.innerHTML = `<div class="card"><div class="muted">${fmt.esc(e.message)}</div></div>`; return; }

      if (!first.available) {
        root.innerHTML = `<div class="card">
          <div class="card-title">${icon('shieldAlert', { size: 14 })} 审计不可用</div>
          <div class="muted small" style="line-height:2">${fmt.esc(first.reason || '')}<br>
          启用：<code>apt-get install auditd</code> 并加载 <code>/etc/audit/rules.d/90-relay.rules</code>，随后 <code>augenrules --load</code>。</div>
        </div>`;
        return;
      }

      const st = { range: '24h', key: '', limit: 300, data: first };

      root.innerHTML = `
      <div class="grid g4 mb">
        <div class="card stat"><div class="stat-label">命中事件</div><div class="stat-value" id="au-total">${first.total}</div><div class="stat-sub">当前筛选</div></div>
        <div class="card stat"><div class="stat-label">审计规则</div><div class="stat-value">${first.rules.length}</div><div class="stat-sub">auditctl -l</div></div>
        <div class="card stat"><div class="stat-label">日志体积</div><div class="stat-value" style="font-size:17px;padding-top:6px">${fmt.esc((first.storage || '—').split(/\s+/)[0])}</div><div class="stat-sub">${fmt.esc(first.logFile)}</div></div>
        <div class="card stat"><div class="stat-label">审计服务</div><div class="stat-value" style="font-size:17px;padding-top:6px">${ui.badge('运行中', 'ok')}</div><div class="stat-sub">auditd</div></div>
      </div>

      <div class="card mb">
        <div class="card-title">${icon('filter', { size: 14 })} 筛选
          <div class="right"><button class="btn btn-sm btn-primary" id="au-query">查询</button></div>
        </div>
        <div class="grid g3" style="gap:10px">
          <label class="fld">时间范围
            <select class="inp" id="au-range">${RANGES.map((r) => `<option value="${r[0]}"${r[0] === st.range ? ' selected' : ''}>${r[1]}</option>`).join('')}</select>
          </label>
          <label class="fld">审计规则
            <select class="inp mono" id="au-key">
              <option value="">全部（仅显示有规则标记的事件）</option>
              ${first.keys.map((k) => `<option value="${k}" data-c="${first.counts[k] || 0}">${k} (${first.counts[k] || 0})</option>`).join('')}
            </select>
          </label>
          <label class="fld">返回条数
            <select class="inp" id="au-limit">${LIMITS.map((l) => `<option value="${l}"${l === st.limit ? ' selected' : ''}>${l}</option>`).join('')}</select>
          </label>
        </div>
        <div class="muted small mt">规则含义：<code>relay_conf</code> 中继配置 · <code>relay_db</code> 统计数据库 · <code>relay_keys</code> API 密钥 ·
        <code>nginx_site</code>/<code>nginx_conf</code> Nginx 配置 · <code>sshd_config</code> SSH 配置 ·
        <code>ssh_authorized_keys</code> 授权密钥 · <code>relay_scripts</code> 中继脚本 · <code>ufw_conf</code> 防火墙 · <code>cron_conf</code> 定时任务。</div>
      </div>

      <div class="card mb">
        <div class="card-title">${icon('list', { size: 14 })} 事件 <span class="muted small" id="au-count"></span>
          <div class="right"><button class="btn btn-sm" id="au-reload">刷新</button></div>
        </div>
        <div id="au-events">${ui.skeleton(5)}</div>
      </div>

      <div class="card">
        <div class="card-title">${icon('key', { size: 14 })} 已加载的审计规则</div>
        <pre class="pre" style="max-height:240px">${fmt.esc(first.rules.join('\n') || '(无)')}</pre>
        <div class="muted small mt">日志按 <code>/var/log/audit/audit.log</code> 轮转，更早的记录在同目录的 <code>audit.log.*</code> 压缩包中。</div>
      </div>`;

      const renderEvents = () => {
        const d = st.data;
        document.getElementById('au-total').textContent = d.total;
        document.getElementById('au-count').textContent = `(${d.events.length} 条展示 / 命中 ${d.total} 条)`;
        /* refresh the per-key counts shown in the dropdown */
        const sel = document.getElementById('au-key');
        for (const o of sel.options) {
          if (!o.value) continue;
          const c = d.counts[o.value] || 0;
          o.textContent = `${o.value} (${c})`;
        }
        document.getElementById('au-events').innerHTML = ui.table([
          { k: 'ts', label: '时间', cls: 'mono', render: (v) => new Date(v * 1000).toLocaleString('zh-CN', { hour12: false }) },
          { k: 'key', label: '规则', cls: 'mono', render: (v) => ui.badge(v, 'warn') },
          { k: 'comm', label: '进程', cls: 'mono', render: (v, r) => (v ? fmt.esc(v) : '<span class="muted">—</span>') },
          { k: 'cmd', label: '命令', cls: 'mono wrap', render: (v) => (v ? fmt.esc(v.length > 160 ? v.slice(0, 160) + '…' : v) : '<span class="muted">—</span>') },
          { k: 'files', label: '涉及文件', cls: 'mono wrap', render: (v) => (v && v.length ? fmt.esc(v.join(', ')) : '<span class="muted">—</span>') },
          { k: 'success', label: '结果', render: (v) => (v === 'yes' ? ui.badge('成功', 'ok') : (v ? ui.badge('失败', 'bad') : '<span class="muted">—</span>')) },
        ], d.events, { style: 'max-height:56vh' });
      };

      const query = async () => {
        st.range = document.getElementById('au-range').value;
        st.key = document.getElementById('au-key').value;
        st.limit = parseInt(document.getElementById('au-limit').value, 10) || 300;
        const box = document.getElementById('au-events');
        box.innerHTML = ui.skeleton(5);
        try {
          st.data = await CM.api.get(`/relayops/audit?range=${encodeURIComponent(st.range)}&key=${encodeURIComponent(st.key)}&limit=${st.limit}`);
          renderEvents();
        } catch (e) {
          box.innerHTML = `<div class="muted">${fmt.esc(e.message)}</div>`;
        }
      };

      document.getElementById('au-query').onclick = query;
      document.getElementById('au-reload').onclick = query;
      document.getElementById('au-key').onchange = query;
      document.getElementById('au-range').onchange = query;
      renderEvents();
    },
  });
})();
