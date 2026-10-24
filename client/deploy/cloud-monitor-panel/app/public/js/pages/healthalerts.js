/* Health alerts page - relay_alert.py checks, SMTP channel, alert log */
(function () {
  'use strict';

  registerPage({
    id: 'healthalerts',
    title: '健康告警',
    async mount(root) {
      root.innerHTML = `<div class="card">${ui.skeleton(6)}</div>`;
      let d, tr = null;
      try {
        const [a, b] = await Promise.all([
          CM.api.get('/relayops/health'),
          CM.api.get('/metrics/trend?days=7').catch(() => null),
        ]);
        d = a; tr = b;
      } catch (e) { root.innerHTML = `<div class="card"><div class="muted">${fmt.esc(e.message)}</div></div>`; return; }

      if (!d.available) {
        root.innerHTML = `<div class="card">
          <div class="card-title">${icon('shieldAlert', { size: 14 })} 尚未运行健康检查</div>
          <div class="muted small" style="line-height:2">${fmt.esc(d.reason || '')}<br>
          启用方式：<code>${fmt.esc(d.sources ? d.sources.script : '/opt/relay/relay_alert.py')}</code>，由 <code>relay-alert.timer</code> 每 5 分钟执行一次。</div>
        </div>`;
        return;
      }

      const s = d.summary;
      const stateBadge = s.failing ? ui.badge(s.failing + ' 项失败', 'bad') : ui.badge('全部正常', 'ok');
      const bk = d.backup;
      const bkOk = !!(bk && bk.ok);

      root.innerHTML = `
      <div class="grid g4 mb">
        <div class="card stat"><div class="stat-label">检查项</div><div class="stat-value">${s.total}</div><div class="stat-sub">relay_alert.py</div></div>
        <div class="card stat"><div class="stat-label">通过</div><div class="stat-value" style="color:var(--ok,#10b981)">${s.ok}</div><div class="stat-sub">健康</div></div>
        <div class="card stat"><div class="stat-label">失败</div><div class="stat-value" style="color:${s.failing ? 'var(--danger,#ef4444)' : 'inherit'}">${s.failing}</div><div class="stat-sub">${s.failing ? '需处理' : '无'}</div></div>
        <div class="card stat"><div class="stat-label">当前状态</div><div class="stat-value" style="font-size:16px;padding-top:8px">${stateBadge}</div><div class="stat-sub">状态变化才发信</div></div>
      </div>

      ${s.failing ? `<div class="card mb" style="border-color:var(--danger,#ef4444)">
        <div class="card-title">${icon('warning', { size: 14 })} 失败的检查</div>
        <div class="row" style="gap:6px;flex-wrap:wrap">${d.failingNames.map((n) => `<span class="badge bad">${fmt.esc(n)}</span>`).join('')}</div>
      </div>` : ''}

      <div class="card mb">
        <div class="card-title">${icon('check', { size: 14 })} 检查项明细 (${s.total})
          <div class="right"><button class="btn btn-sm" id="ha-reload">刷新</button></div>
        </div>
        ${ui.table([
          { k: 'name', label: '检查项', cls: 'mono' },
          { k: 'ok', label: '状态', render: (v) => (v ? ui.badge('OK', 'ok') : ui.badge('FAIL', 'bad')) },
          { k: 'since', label: '当前状态起始', cls: 'mono', render: (v) => fmt.esc(String(v || '').replace('T', ' ').slice(0, 19) || '—') },
          { k: 'lastSent', label: '最近告警', cls: 'mono', render: (v) => (v ? new Date(v * 1000).toLocaleString('zh-CN', { hour12: false }) : '从未发信') },
        ], d.checks, { style: 'max-height:420px' })}
      </div>

      <div class="grid g2 mb">
        <div class="card">
          <div class="card-title">${icon('info', { size: 14 })} 告警通道（复用 relay 每日报告的 SMTP）
            <div class="right"><button class="btn btn-sm btn-primary" id="ha-test">发送测试邮件</button></div>
          </div>
          ${ui.table([
            { k: 'label', label: '项' },
            { k: 'val', label: '值', cls: 'mono' },
          ], [
            { label: '收件人', val: (d.smtp.to || []).join(', ') || '—' },
            { label: '服务器', val: d.smtp.host + ':' + d.smtp.port + (d.smtp.ssl ? ' (SSL)' : '') },
            { label: '发件账号', val: d.smtp.user || '—' },
            { label: '发件人名', val: d.smtp.from || '—' },
            { label: '检查脚本', val: d.sources.script },
            { label: '状态文件', val: d.sources.state },
          ])}
          <div class="muted small mt">仅在检查状态发生变化时发信，同一故障每 6 小时提醒一次。</div>
        </div>

        <div class="card">
          <div class="card-title">${icon('archive', { size: 14 })} 最近一次状态备份</div>
          ${bk ? ui.table([
            { k: 'label', label: '项' },
            { k: 'val', label: '值', cls: 'mono', render: (v, row) => (row.label === '结果'
              ? (String(v) === '成功' ? ui.badge('成功', 'ok') : ui.badge('失败', 'bad'))
              : fmt.esc(v)) },
          ], [
            { label: '结果', val: bkOk ? '成功' : '失败' },
            { label: '时间', val: (bk.ts || '').replace('T', ' ').slice(0, 19) },
            { label: '大小', val: fmt.bytes(bk.size || 0) },
            { label: '文件内条目', val: String(bk.files || 0) },
            { label: '远端推送', val: bk.remote || '—' },
          ]) : '<div class="muted small">暂无备份状态记录</div>'}
          <div class="muted small mt">保留 30 份，<code>relay-backup.timer</code> 每日 03:30 执行。</div>
        </div>
      </div>

      <div class="card mb">
        <div class="card-title">${icon('gauge', { size: 14 })} 容量趋势（近 7 天）</div>
        ${(() => {
          const row = (label, t) => {
            if (!t || !t.points || !t.points.length) return { k: label, now: '—', slope: '—', eta: '暂无数据', days: 0 };
            const n = t.points.length;
            if (t.slopePerDay == null) return { k: label, now: t.current + '%', slope: '需 ≥2 天', eta: '—', days: n };
            const s = t.slopePerDay;
            let eta = '稳定';
            if (s > 0.01) eta = (t.daysToFull != null && t.daysToFull > 0) ? Math.round(t.daysToFull) + ' 天后达 100%' : '持续增长';
            else if (s < -0.01) eta = '下降中';
            return { k: label, now: t.current + '%', slope: (s >= 0 ? '+' : '') + s + '%/天', eta, days: n };
          };
          return ui.table([
            { k: 'k', label: '指标' },
            { k: 'now', label: '当前', cls: 'num' },
            { k: 'slope', label: '日均变化', cls: 'num' },
            { k: 'eta', label: '预计触顶' },
            { k: 'days', label: '样本', cls: 'num', render: (v) => (v ? v + ' 天' : '—') },
          ], [row('磁盘占用', tr && tr.disk), row('内存占用', tr && tr.mem), row('CPU 占用', tr && tr.cpu)]);
        })()}
        <div class="muted small mt">来自持久化指标（30 秒采样，保留 30 天）的逐日线性外推；样本不足 2 天时只显示当前值。</div>
      </div>

      <div class="card mb">
        <div class="card-title">${icon('clock', { size: 14 })} 相关定时器</div>
        ${d.timers.length ? ui.table([
          { k: 'unit', label: '定时器', cls: 'mono' },
          { k: 'activates', label: '执行单元', cls: 'mono' },
          { k: 'next', label: '下次执行', cls: 'mono' },
          { k: 'left', label: '倒计时', cls: 'mono' },
          { k: 'last', label: '上次执行', cls: 'mono' },
        ], d.timers) : '<div class="muted small">未找到相关 timer</div>'}
      </div>

      <div class="card">
        <div class="card-title">${icon('logs', { size: 14 })} 告警日志 <span class="muted small">(${fmt.esc(d.sources.log)})</span>
          <div class="right"><button class="btn btn-sm" id="ha-logclear">清屏</button></div>
        </div>
        <pre class="pre" id="ha-log" style="max-height:320px">${d.log.length ? fmt.esc(d.log.join('\n')) : '(空)'}</pre>
      </div>`;

      document.getElementById('ha-reload').onclick = () => this.mount(root);
      document.getElementById('ha-logclear').onclick = () => {
        const el = document.getElementById('ha-log');
        if (el) el.textContent = '(已清屏，刷新可重新加载)';
      };
      document.getElementById('ha-test').onclick = async (ev) => {
        const b = ev.currentTarget;
        ui.busy(b, true);
        try {
          await runAction('/relayops/alert/test', {}, { showOutput: true, title: '测试邮件发送结果' });
        } catch (_) { /* toast 已提示 */ }
        finally { ui.busy(b, false); }
      };
    },
  });
})();
