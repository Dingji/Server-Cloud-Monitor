/* SLO & usage page - success rate, latency percentiles, quota, token usage */
(function () {
  'use strict';
  let auto = null;
  const RANGES = [['1h', '最近 1 小时'], ['6h', '最近 6 小时'], ['24h', '最近 24 小时'], ['7d', '最近 7 天'], ['all', '全部']];

  const fmtTok = (n) => {
    if (n == null || isNaN(n)) return '—';
    n = Number(n);
    if (n >= 1e9) return (n / 1e9).toFixed(2) + 'B';
    if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k';
    return String(n);
  };
  const fmtSec = (s) => (s == null || isNaN(s)) ? '—' : (s >= 60 ? (s / 60).toFixed(1) + ' 分' : Number(s).toFixed(2) + ' 秒');
  const rateBadge = (r) => r >= 99.9 ? ui.badge('优秀', 'ok') : r >= 99 ? ui.badge('良好', 'ok')
    : r >= 95 ? ui.badge('关注', 'warn') : ui.badge('劣化', 'bad');
  const riskBadge = (v) => v === '正常' ? ui.badge('正常', 'ok') : v === '紧张' ? ui.badge('紧张', 'warn')
    : v === '耗尽' ? ui.badge('预计耗尽', 'bad') : '<span class="muted">—</span>';

  registerPage({
    id: 'slo',
    title: 'SLO 与用量',
    desc: '成功率、延迟分位、错误分布、配额消耗与 token 用量归集',

    async mount(root) {
      root.innerHTML = `
      <div class="card"><div class="card-title">加载中…</div>${ui.skeleton(4)}</div>`;

      let slo = null, usg = null, prev = null, budget = null, corr = null;
      /* 用模块级状态保存范围，避免首次 load() 在模板渲染前读 DOM */
      let curRange = '24h';
      const range = () => curRange;
      const burnLabel = (s) => ({ ok: '正常', slipping: '偏快', warn: '告警', critical: '紧急' }[s] || s || '—');
      const burnKind = (s) => ({ ok: 'ok', slipping: 'info', warn: 'warn', critical: 'bad' }[s] || 'gray');

      /* 环比：goodDown=true 表示"下降是好事"（如 5xx、延迟） */
      const delta = (cur, prv, fmt, goodDown) => {
        if (prv == null || cur == null || !prv) return { cur: fmt(cur), prv: '—', d: '—', b: '' };
        const diff = cur - prv;
        const pct = prv ? (diff / prv * 100) : 0;
        const up = diff >= 0;
        const good = goodDown ? !up : up;
        return {
          cur: fmt(cur), prv: fmt(prv),
          d: (up ? '↑' : '↓') + ' ' + Math.abs(pct).toFixed(1) + '%',
          b: ui.badge((up ? '↑' : '↓') + ' ' + Math.abs(pct).toFixed(1) + '%', good ? 'ok' : 'warn'),
        };
      };

      const render = () => {
        const q = (usg && usg.quota) || {};
        const fc = (usg && usg.forecast) || {};
        const priced = !!(usg && usg.pricing && usg.pricing.present);
        const totalCost = (usg && usg.byModel || []).reduce((a, b) => a + (b.cost || 0), 0);

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">成功率</div>
            <div class="stat-value" style="color:${slo.successRate >= 99 ? 'var(--ok,#10b981)' : slo.successRate >= 95 ? '#facc15' : '#f87171'}">${slo.successRate}%</div>
            <div class="stat-sub">服务可用性 ${slo.availability}% · ${fmtTok(slo.ok)}/${fmtTok(slo.calls)} 请求</div></div>
          <div class="card stat"><div class="stat-label">p95 延迟</div>
            <div class="stat-value">${fmtSec(slo.latency.p95)}</div>
            <div class="stat-sub">p50 ${fmtSec(slo.latency.p50)} · p99 ${fmtSec(slo.latency.p99)}</div></div>
          <div class="card stat"><div class="stat-label">5xx 错误</div>
            <div class="stat-value" style="color:${slo.e5 ? '#f87171' : 'inherit'}">${fmtTok(slo.e5)}</div>
            <div class="stat-sub">4xx ${fmtTok(slo.e4)} · 无响应 ${fmtTok(slo.e0)}</div></div>
          <div class="card stat"><div class="stat-label">上游重试</div>
            <div class="stat-value">${fmtTok(slo.retried)}</div>
            <div class="stat-sub">平均 ${fmtSec(slo.avgRt)} · TTFB ${fmtSec(slo.avgTtfb)}</div></div>
        </div>

        ${prev && prev.calls ? `
        <div class="card mb">
          <div class="card-title">${icon('refresh', { size: 14 })} 环比上一周期</div>
          ${ui.table([
            { k: 'k', label: '指标' },
            { k: 'cur', label: '当前' },
            { k: 'prv', label: '上一周期' },
            { k: 'b', label: '变化', render: (v) => v || '—' },
          ], [
            Object.assign({ k: '请求数' }, delta(slo.calls, prev.calls, (v) => fmtTok(v), false)),
            Object.assign({ k: '成功率' }, delta(slo.successRate, prev.successRate, (v) => v + '%', false)),
            Object.assign({ k: '服务可用性' }, delta(slo.availability, prev.availability, (v) => v + '%', false)),
            Object.assign({ k: '5xx 错误' }, delta(slo.e5, prev.e5, (v) => fmtTok(v), true)),
            Object.assign({ k: '4xx 错误' }, delta(slo.e4, prev.e4, (v) => fmtTok(v), true)),
            Object.assign({ k: '上游重试' }, delta(slo.retried, prev.retried, (v) => fmtTok(v), true)),
            Object.assign({ k: '平均耗时' }, delta(slo.avgRt, prev.avgRt, (v) => fmtSec(v), true)),
          ])}
          <div class="muted small mt">上一周期 = 紧邻的同等长度窗口，用于判断趋势在改善还是恶化。</div>
        </div>` : ''}

        ${budget ? `
        <div class="card mb">
          <div class="card-title">${icon('shield', { size: 14 })} SLO 错误预算
            <div class="right">
              <label class="row small muted">目标
                <input class="inp mono" id="slo-target" style="width:88px" value="${(budget.target * 100).toFixed(3)}">
              </label>
              <span class="small muted">%</span>
              <label class="row small muted">周期
                <input class="inp mono" id="slo-period" type="number" min="1" max="365" style="width:66px" value="${budget.periodDays}">
              </label>
              <span class="small muted">天</span>
              <button class="btn btn-sm btn-primary" id="slo-target-apply">应用</button>
            </div>
          </div>
          <div class="grid g3 mb">
            <div class="card stat"><div class="stat-label">预算剩余</div>
              <div class="stat-value" style="color:${budget.period.remainingPct > 50 ? 'var(--ok,#10b981)' : budget.period.remainingPct > 20 ? '#facc15' : '#f87171'}">${budget.period.remainingPct}%</div>
              <div data-b>${ui.bar(Math.min(100, budget.period.consumedPct))}</div>
              <div class="stat-sub">周期 ${budget.periodDays} 天 · 已消耗 ${budget.period.consumedPct}%</div></div>
            <div class="card stat"><div class="stat-label">允许错误率</div>
              <div class="stat-value" style="font-size:19px;padding-top:5px">${budget.allowedErrorRatePct}%</div>
              <div class="stat-sub">目标 ${(budget.target * 100).toFixed(3)}%</div></div>
            <div class="card stat"><div class="stat-label">周期内消耗</div>
              <div class="stat-value">${fmtTok(budget.period.bad)}</div>
              <div class="stat-sub">允许 ${fmtTok(budget.period.allowedErrors)} · 4xx ${fmtTok(budget.period.e4)}（不计入）</div></div>
          </div>
          ${ui.table([
            { k: 'window', label: '窗口', cls: 'mono' },
            { k: 'calls', label: '请求', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'bad', label: '5xx/无响应', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'errorRatePct', label: '错误率', cls: 'num', render: (v) => v + '%' },
            { k: 'burnRate', label: '燃烧率', cls: 'num', render: (v) => v + '×' },
            { k: 'exhaustDays', label: '按此速率耗尽', cls: 'num', render: (v) => (v == null ? '—' : v + ' 天') },
            { k: 'status', label: '状态', render: (v) => ui.badge(burnLabel(v), burnKind(v)) },
          ], budget.windows)}
          <div class="muted small mt">燃烧率 1× ＝ 正好按预算节奏消耗；≥6× 表示约 5 天烧完整个周期预算；≥14.4× 表示约 2 天烧完（SRE 常用告警阈值）。4xx 属客户端问题，不计入预算。</div>
        </div>` : ''}

        ${corr && corr.links && corr.links.length ? `
        <div class="card mb">
          <div class="card-title">${icon('link', { size: 14 })} 异常与变更关联
            <div class="right">${ui.badge(corr.summary.withCauses + ' / ' + corr.summary.anomalies + ' 个异常找到疑似变更', corr.summary.withCauses ? 'warn' : 'ok')}</div>
          </div>
          ${ui.table([
            { k: 'time', label: '异常时段', cls: 'mono' },
            { k: 'bad', label: '5xx', cls: 'num' },
            { k: 'calls', label: '请求', cls: 'num' },
            { k: 'cause', label: '疑似变更（异常前 ' + corr.windowMin + ' 分钟内）', cls: 'wrap' },
          ], corr.links.map((l) => ({
            time: l.anomaly.bucket,
            bad: l.anomaly.bad,
            calls: l.anomaly.calls,
            cause: l.causes.length
              ? l.causes.map((c) => {
                const t = new Date(c.ts * 1000).toLocaleTimeString('zh-CN', { hour12: false });
                const what = (c.files && c.files[0]) ? c.files[0] : (c.cmd || c.comm || c.key);
                const mins = Math.max(0, Math.round((l.anomaly.ts - c.ts) / 60));
                return `<span class="mono">${t}</span> [${fmt.esc(c.key)}] ${fmt.esc(String(what).slice(0, 58))} <span class="muted">(${mins} 分钟前)</span>`;
              }).join('<br>')
              : '<span class="muted">该时段前无变更记录</span>',
          })), { style: 'max-height:340px' })}
          <div class="muted small mt">变更取自 auditd 文件完整性事件（配置 / 密钥 / nginx / sshd 等），共 ${corr.changeCount} 条。这是<b>相关性提示</b>而非因果结论，用于缩小排查范围。</div>
        </div>` : ''}

        <div class="card mb">
          <div class="card-title">${icon('clock', { size: 14 })} 配额窗口
            <div class="right">
              <label class="row small muted"><input type="checkbox" id="slo-auto" checked> 自动刷新</label>
              <span class="mono small" id="slo-rate"></span>
              <select class="inp" id="slo-range" style="width:140px">${RANGES.map((r) => `<option value="${r[0]}"${r[0] === curRange ? ' selected' : ''}>${r[1]}</option>`).join('')}</select>
              <button class="btn btn-sm" id="slo-refresh">${icon('refresh', { size: 13 })} 刷新</button>
            </div>
          </div>
          ${q && Object.keys(q).length ? ui.table([
            { k: 'k', label: '窗口' },
            { k: 'percent', label: '已用', render: (v) => `${ui.bar(v == null ? 0 : v)}<span class="small mono">${v == null ? '—' : v + '%'}</span>` },
            { k: 'status', label: '状态', render: (v) => ui.badge(v || '—', v === 'ok' ? 'ok' : v === 'exhausted' ? 'bad' : 'warn') },
            { k: 'resetIn', label: '重置倒计时', cls: 'mono', render: (v) => v == null ? '—' : (v < 0 ? '已到期' : v.toFixed(1) + ' 小时') },
            { k: 'deltaPerDay', label: '日增量', cls: 'num', render: (v) => v == null ? '<span class="muted">需 2 天历史</span>' : (v >= 0 ? '+' : '') + v + '%/天' },
            { k: 'projected', label: '重置前预测', cls: 'num', render: (v) => v == null ? '—' : v + '%' },
            { k: 'risk', label: '风险', render: (v) => riskBadge(v) },
          ], ['rolling', 'weekly', 'monthly'].filter((k) => q[k]).map((k) => Object.assign({ k }, fc[k] || { percent: q[k].percent, status: q[k].status })))
          : '<div class="muted small">暂无配额数据</div>'}
          <div class="muted small mt">日增量与预测来自每日配额快照（<code>quota-history.jsonl</code>），积累 2 天后开始给出预测。
            当前 token 速率：近 1 小时 <b>${fmtTok(usg.rates.perHour1h)}</b>／小时 · 近 24 小时 <b>${fmtTok(usg.rates.perDay)}</b></div>
        </div>

        <div class="card mb">
          <div class="card-title">${icon('gauge', { size: 14 })} 路由可靠性</div>
          ${ui.table([
            { k: 'route', label: '路由', cls: 'mono' },
            { k: 'calls', label: '请求数', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'successRate', label: '成功率', cls: 'num' },
            { k: 'availability', label: '服务可用性', cls: 'num', render: (v) => `${v}%` },
            { k: 'e5', label: '5xx', cls: 'num' },
            { k: 'e4', label: '4xx', cls: 'num' },
            { k: 'avgRt', label: '平均耗时', cls: 'num', render: (v) => fmtSec(v) },
            { k: 'maxRt', label: '最慢', cls: 'num', render: (v) => fmtSec(v) },
            { k: 'retried', label: '重试', cls: 'num' },
          ], slo.byRoute)}
          <div class="muted small mt">成功率 = 2xx 占比；服务可用性 = 非 5xx 占比。两者差距大说明失败主要来自客户端参数或配额，而非上游故障。</div>
        </div>

        <div class="grid g2 mb">
          <div class="card">
            <div class="card-title">${icon('warning', { size: 14 })} 错误分布</div>
            ${slo.errors.length ? ui.table([
              { k: 'status', label: '状态码', cls: 'mono' },
              { k: 'n', label: '次数', cls: 'num' },
              { k: 'n', label: '', render: (v) => ui.bar(slo.calls ? Math.round(v / slo.calls * 1000) / 10 : 0) },
            ], slo.errors) : '<div class="muted small">该时间范围内无 4xx/5xx</div>'}
          </div>
          <div class="card">
            <div class="card-title">${icon('chart', { size: 14 })} 分时成功率</div>
            ${slo.timeline.length ? ui.table([
              { k: 'bucket', label: '时段', cls: 'mono' },
              { k: 'calls', label: '请求', cls: 'num' },
              { k: 'r', label: '成功率', cls: 'num', render: (v) => `${v}%` },
              { k: 'e5', label: '5xx', cls: 'num' },
            ], slo.timeline.slice(-24).map((t) => Object.assign({}, t, { r: t.calls ? +(t.ok / t.calls * 100).toFixed(1) : 100 })), { style: 'max-height:320px' })
            : '<div class="muted small">无数据</div>'}
          </div>
        </div>

        <div class="card mb">
          <div class="card-title">${icon('package', { size: 14 })} Token 用量归集
            <div class="right">${priced ? ui.badge('含成本估算', 'warn') : ui.badge('未配置单价', 'gray')}</div>
          </div>
          <div class="grid g4 mb">
            <div class="card stat"><div class="stat-label">总 Token</div><div class="stat-value">${fmtTok(usg.totalTokens)}</div><div class="stat-sub">${fmtTok(usg.events)} 次调用</div></div>
            <div class="card stat"><div class="stat-label">活跃会话</div><div class="stat-value">${fmtTok(usg.sessions)}</div><div class="stat-sub">去重 sess</div></div>
            <div class="card stat"><div class="stat-label">总耗时</div><div class="stat-value">${(usg.totalSecs / 3600).toFixed(1)}<span class="small"> 小时</span></div><div class="stat-sub">上游处理时间</div></div>
            <div class="card stat"><div class="stat-label">估算成本</div><div class="stat-value">${priced ? (usg.pricing.currency === 'USD' ? '$' : '') + totalCost.toFixed(2) : '—'}</div><div class="stat-sub">${priced ? '按 /etc/relay/pricing.json' : '配置单价后显示'}</div></div>
          </div>
          ${ui.table([
            { k: 'model', label: '模型', cls: 'mono' },
            { k: 'calls', label: '调用', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'sessions', label: '会话', cls: 'num' },
            { k: 'in_tok', label: '输入', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'out_tok', label: '输出', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'cache_r', label: '缓存读', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'total', label: '合计', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'secs', label: '耗时', cls: 'num', render: (v) => fmtSec(v) },
            { k: 'cost', label: '估算成本', cls: 'num', render: (v, r) => (r.priced ? (usg.pricing.currency === 'USD' ? '$' : '') + v : '<span class="muted">未定价</span>') },
          ], usg.byModel, { style: 'max-height:360px' })}
          ${priced ? '' : '<div class="muted small mt">单价表：<code>/etc/relay/pricing.json</code>，支持 <code>mimo-*</code> 通配；含 <code>-free</code> 的模型按 0 计。</div>'}
        </div>

        <div class="card">
          <div class="card-title">${icon('logs', { size: 14 })} 按天用量</div>
          ${ui.table([
            { k: 'day', label: '日期', cls: 'mono' },
            { k: 'calls', label: '调用', cls: 'num' },
            { k: 'in_tok', label: '输入', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'out_tok', label: '输出', cls: 'num', render: (v) => fmtTok(v) },
            { k: 'total', label: '合计', cls: 'num', render: (v) => fmtTok(v) },
          ], usg.byDay)}
        </div>`;

        /* 事件绑定（每次重画后重新绑定） */
        document.getElementById('slo-refresh').onclick = () => load().catch((err) => window.toast(err.message, 'err'));
        document.getElementById('slo-range').onchange = (e) => {
          curRange = e.target.value;
          load().catch((err) => window.toast(err.message, 'err'));
        };
        document.getElementById('slo-auto').onchange = (e) => { if (auto) auto.setEnabled(e.target.checked); };
        const tApply = document.getElementById('slo-target-apply');
        if (tApply) tApply.onclick = async () => {
          const t = parseFloat(document.getElementById('slo-target').value);
          const dy = parseInt(document.getElementById('slo-period').value, 10);
          if (!(t > 0 && t <= 100)) return window.toast('SLO 目标应为 0~100 之间的百分数', 'warn');
          if (!(dy >= 1 && dy <= 365)) return window.toast('预算周期应为 1~365 天', 'warn');
          try {
            await CM.api.post('/config', { sloTarget: +(t / 100).toFixed(6), sloPeriodDays: dy });
            window.toast('SLO 目标已更新为 ' + t + '% ／ ' + dy + ' 天', 'ok');
            load().catch(() => {});
          } catch (err) { window.toast(err.message, 'err'); }
        };
        document.getElementById('slo-rate').textContent = CM.refreshLabel(auto ? auto.ms() : 0);
      };

      const load = async () => {
        const r = range() || '24h';
        const [a, b, c, bud, cor] = await Promise.all([
          CM.api.get('/relayops/slo?range=' + r),
          CM.api.get('/relayops/usage?range=' + r),
          CM.api.get('/relayops/slo-prev?range=' + r).catch(() => null),
          CM.api.get('/relayops/slo-budget').catch(() => null),
          CM.api.get('/relayops/correlate?hours=24').catch(() => null),
        ]);
        slo = a; usg = b; prev = c; budget = bud; corr = cor;
        render();
      };

      try { await load(); }
      catch (e) { root.innerHTML = `<div class="card"><div class="muted">${fmt.esc(e.message)}</div></div>`; return; }

      /* 分析型页面查询较重，下限设为 5 秒 */
      auto = CM.autoRefresh(() => load().catch(() => {}), {
        pageId: 'slo',
        ms: () => Math.max(5000, Math.min(60000, (CM.me && CM.me.refreshMs) || 30000)),
      });
      document.getElementById('slo-auto').onchange = (e) => auto.setEnabled(e.target.checked);
      document.getElementById('slo-rate').textContent = CM.refreshLabel(auto.ms());
      auto.start();
    },
    show() { if (auto && auto.isEnabled()) auto.start(); },
    hide() { if (auto) auto.stop(); },
  });
})();
