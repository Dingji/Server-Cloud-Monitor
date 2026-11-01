/* 运维增强 · 安全基线巡检：23 项检查、0-100 评分、分级修复动作（带确认与风险提示）、趋势。 */
(function () {
  'use strict';

  const levelBadge = (v) => ui.badge(v === 'bad' ? '不符' : v === 'warn' ? '建议' : v === 'ok' ? '通过' : '未覆盖',
    v === 'bad' ? 'bad' : v === 'warn' ? 'warn' : v === 'ok' ? 'ok' : '');

  registerPage({
    id: 'ops-sec',
    title: '安全巡检',
    desc: 'SSH 策略 · fail2ban · ufw · 对外端口 · 空密码 · SUID · 证书到期等 23 项基线检查与一键修复',
    async mount(root) {
      root.innerHTML = '<div class="card">' + ui.skeleton(6) + '</div>';
      let data = {};
      let busy = false;

      async function load() {
        const out = {};
        await Promise.all(['/ops/sec/report', '/ops/sec/trend?days=30'].map(async (p) => {
          const key = p.split('?')[0].split('/').pop();
          try { out[key] = await CM.api.get(p, 90000); } catch (e) { out[key] = { __error: e.message }; }
        }));
        data = out;
        render();
      }

      function render() {
        const rep = data.report || {}, tr = data.trend || {};
        if (rep.__error) { root.innerHTML = `<div class="card"><div class="muted">读取失败：${fmt.esc(rep.__error)}（需部署 ops 模块）</div></div>`; return; }
        const checks = (rep.checks || []).map((x) => Object.assign({}, x, { _lv: levelBadge(x.level) }));
        const bads = checks.filter((x) => x.level === 'bad');
        const warns = checks.filter((x) => x.level === 'warn');
        const fixes = rep.fixes || [];
        const fixables = bads.filter((x) => x.fix && fixes.some((f) => f.id === x.fix));

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">基线得分</div>
            <div class="stat-value" style="color:${(rep.score || 0) >= 90 ? '#2fa84f' : (rep.score || 0) >= 75 ? '#c79209' : '#cf3b3b'}">${rep.score != null ? rep.score : '—'}<span style="font-size:14px"> / 100</span></div>
            <div class="stat-sub">等级 ${rep.grade || '—'} · ${rep.at ? new Date(rep.at).toLocaleString('zh-CN', { hour12: false }) : '未扫描'}</div>
          </div>
          <div class="card stat"><div class="stat-label">不符项</div><div class="stat-value" style="color:${bads.length ? '#cf3b3b' : 'inherit'}">${bads.length}</div><div class="stat-sub">可一键修复 ${fixables.length}</div></div>
          <div class="card stat"><div class="stat-label">建议项</div><div class="stat-value">${warns.length}</div><div class="stat-sub">通过 ${checks.filter((x) => x.level === 'ok').length}</div></div>
          <div class="card stat"><div class="stat-label">未覆盖</div><div class="stat-value">${checks.filter((x) => x.skipped || x.level === 'info').length}</div><div class="stat-sub">缺工具或读不到，不误报</div></div>
        </div>

        <div class="card mb">
          <div class="card-title">基线检查 (${checks.length})
            <div class="right" style="display:flex;gap:6px">
              <button class="btn btn-sm" id="sec-refresh">${icon('refresh', { size: 12 })} 刷新</button>
              <button class="btn btn-sm btn-primary" id="sec-run">${icon('scan', { size: 12 })} 立即扫描</button>
            </div>
          </div>
          ${ui.table([
            { k: 'cat', label: '类别', cls: 'mono small' },
            { k: 'label', label: '检查项' },
            { k: '_lv', label: '结果', render: (v) => v },
            { k: 'detail', label: '详情', cls: 'small', render: (v) => fmt.esc(String(v).slice(0, 90)) },
            { k: 'evidence', label: '证据', cls: 'mono small', render: (v) => fmt.esc(String(v || '').slice(0, 60)) },
            { k: '', label: '操作', render: (v, x) => x.fix && fixes.some((f) => f.id === x.fix)
              ? `<button class="btn btn-sm btn-danger" data-fix="${fmt.esc(x.fix)}" data-label="${fmt.esc(x.label)}">修复</button>` : '' },
          ], checks, { style: 'max-height:480px' })}
          <div class="small muted" style="padding:8px 16px">修复动作带白名单与风险提示（如 SSH 收紧前先放行当前端口），执行前都会二次确认；SSH 修复先写 drop-in 并 sshd -t 校验，不通过自动回滚。</div>
        </div>

        <div class="grid g2">
          <div class="card">
            <div class="card-title">评分趋势（近 30 次记录）</div>
            ${ui.table([
              { k: 'at', label: '时间', cls: 'mono', render: (v) => new Date(v).toLocaleString('zh-CN', { hour12: false }) },
              { k: 'score', label: '得分', cls: 'num', render: (v) => `<b>${v}</b>` },
              { k: 'grade', label: '等级', render: (v) => ui.badge(v, v === 'A' ? 'ok' : v === 'C' ? 'warn' : v === 'D' || v === 'E' ? 'bad' : '') },
              { k: 'bad', label: '不符', cls: 'num' },
              { k: 'warn', label: '建议', cls: 'num' },
            ], (tr.items || []).slice(-30).reverse(), { style: 'max-height:320px' })}
          </div>
          <div class="card">
            <div class="card-title">按类别聚合</div>
            ${ui.table([
              { k: 'cat', label: '类别', cls: 'mono' },
              { k: 'total', label: '项数', cls: 'num' },
              { k: 'bad', label: '不符', cls: 'num' },
              { k: 'warn', label: '建议', cls: 'num' },
              { k: 'score', label: '类别分', cls: 'num', render: (v) => ui.bar(v) + ` <span class="small mono">${Math.round(v)}</span>` },
            ], rep.byCat || [])}
            ${fixes.length ? `<div class="small muted" style="padding:8px 16px">可用修复动作：${fixes.map((f) => fmt.esc(f.label || f.id)).join(' · ')}</div>` : ''}
          </div>
        </div>`;
        bind();
      }

      function bind() {
        root.querySelectorAll('[data-fix]').forEach((b) => {
          b.onclick = async () => {
            const fix = b.dataset.fix, label = b.dataset.label;
            if (!await modal.confirm('执行修复：' + label,
              `将在本机执行白名单修复动作 <b class="mono">${fmt.esc(fix)}</b>。危险操作执行前请确认已了解影响（如 SSH 收紧会先放行当前端口与 80/443）。`,
              { danger: true, okText: '我明白风险，执行' })) return;
            try {
              const r = await CM.api.post('/ops/sec/fix', { fix });
              modal.output('修复结果 · ' + label, JSON.stringify(r, null, 2), !r || r.ok === false);
              await load();
            } catch (e) { toast('修复失败：' + e.message, 'err'); }
          };
        });
        const run = document.getElementById('sec-run');
        if (run) run.onclick = async () => {
          if (busy) return;
          busy = true;
          toast('扫描中（跑 23 项检查，约需十几秒）…', 'info');
          try { await CM.api.post('/ops/sec/run', {}); toast('扫描完成', 'ok'); await load(); }
          catch (e) { toast(e.message, 'err'); }
          finally { busy = false; }
        };
        const rf = document.getElementById('sec-refresh');
        if (rf) rf.onclick = () => load();
      }

      await load();
    },
  });
})();
