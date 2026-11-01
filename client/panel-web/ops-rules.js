/* 运维增强 · 告警规则：14 指标 × 比较符 × 持续时长 × 级别 × 冷却 × 恢复通知；
   静默窗口（全部/按规则/按指标，单次或每日时段）；告警事件流水。 */
(function () {
  'use strict';

  const STATE_LABEL = { ok: '正常', breaching: '持续越限', fired: '已触发', silenced: '静默中' };
  const stateBadge = (s) => ui.badge(STATE_LABEL[s] || s, s === 'ok' ? 'ok' : s === 'fired' ? 'bad' : s === 'silenced' ? '' : 'warn');
  const LEVELS = ['info', 'warn', 'critical'];
  const OPS = ['>', '>=', '<', '<='];

  registerPage({
    id: 'ops-rules',
    title: '告警规则',
    desc: '自定义阈值规则 · 持续时长防抖 · 冷却 · 恢复通知 · 静默与维护窗口',
    async mount(root) {
      root.innerHTML = '<div class="card">' + ui.skeleton(6) + '</div>';
      let data = {};

      async function load() {
        const out = {};
        await Promise.all(['/ops/alerts/rules', '/ops/alerts/silences', '/ops/alerts/events?limit=40'].map(async (p) => {
          const key = p.split('?')[0].split('/').pop();
          try { out[key] = await CM.api.get(p, 0); } catch (e) { out[key] = { __error: e.message }; }
        }));
        data = out;
        render();
      }

      function render() {
        const r = data.rules || {}, si = data.silences || {}, ev = data.events || {};
        if (r.__error) { root.innerHTML = `<div class="card"><div class="muted">读取失败：${fmt.esc(r.__error)}（需部署 ops 模块）</div></div>`; return; }
        const items = (r.items || []).map((x) => Object.assign({}, x, {
          _metric: metricLabel(x.metric),
          _cur: x.currentValue != null ? fmtNum(x.currentValue) : '—',
          _thr: `${x.op} ${fmtNum(x.threshold)}`,
          _for: x.forSec ? x.forSec + 's' : '立即',
          _cool: x.cooldownSec ? Math.round(x.cooldownSec / 60) + ' 分' : '—',
          _ch: (x.channelIds || []).length ? x.channelIds.length + ' 渠道' : '仅面板',
        }));
        const metrics = r.metrics || [];
        const metricOpts = metrics.map((m) => m.key);
        const ruleOpts = (r.items || []).map((x) => x.id);

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">规则</div><div class="stat-value">${items.length}</div><div class="stat-sub">指标 ${metrics.length} 个可监控</div></div>
          <div class="card stat"><div class="stat-label">触发中</div><div class="stat-value" style="color:${items.filter((x) => x.state === 'fired').length ? '#cf3b3b' : 'inherit'}">${items.filter((x) => x.state === 'fired').length}</div><div class="stat-sub">越限 ${items.filter((x) => x.state === 'breaching').length}</div></div>
          <div class="card stat"><div class="stat-label">静默中</div><div class="stat-value">${items.filter((x) => x.state === 'silenced').length}</div><div class="stat-sub">维护窗口 ${(si.items || []).length}</div></div>
          <div class="card stat"><div class="stat-label">引擎指标当前值</div><div class="stat-sub" style="max-height:52px;overflow:auto">${metrics.slice(0, 8).map((m) => {
            const v = (r.engine && r.engine.values || {})[m.key];
            return `${fmt.esc(m.label)} ${v == null ? '—' : fmtNum(v)}`;
          }).join(' · ') || '—'}</div></div>
        </div>

        <div class="card mb">
          <div class="card-title">告警规则 (${items.length})
            <div class="right" style="display:flex;gap:6px">
              <button class="btn btn-sm btn-primary" id="ru-add">${icon('plus', { size: 12 })} 新建规则</button>
              <button class="btn btn-sm" id="ru-refresh">${icon('refresh', { size: 12 })} 刷新</button>
            </div>
          </div>
          ${ui.table([
            { k: 'name', label: '名称' },
            { k: '_metric', label: '指标', cls: 'mono' },
            { k: '_thr', label: '阈值', cls: 'mono' },
            { k: '_for', label: '持续', cls: 'mono' },
            { k: '_cool', label: '冷却', cls: 'mono' },
            { k: 'level', label: '级别', render: (v) => ui.badge(v, v === 'critical' ? 'bad' : v === 'warn' ? 'warn' : '') },
            { k: 'state', label: '状态', render: (v, x) => stateBadge(v) + (x.currentValue != null ? ` <span class="small muted mono">${fmt.esc(x._cur)}</span>` : '') },
            { k: '_ch', label: '通知' },
            { k: 'notifyRecover', label: '恢复通知', render: (v) => v ? '<span class="muted">是</span>' : '<span class="muted">否</span>' },
            { k: '', label: '操作', render: (v, x) => `
              <div style="display:flex;gap:4px;flex-wrap:wrap">
                <button class="btn btn-sm" data-act="test" data-id="${fmt.esc(x.id)}">试跑</button>
                <button class="btn btn-sm" data-act="toggle" data-id="${fmt.esc(x.id)}">${x.enabled ? '停用' : '启用'}</button>
                <button class="btn btn-sm" data-act="edit" data-id="${fmt.esc(x.id)}">编辑</button>
                <button class="btn btn-sm btn-danger" data-act="del" data-id="${fmt.esc(x.id)}">删除</button>
              </div>` },
          ], items, { style: 'max-height:420px' })}
        </div>

        <div class="grid g2 mb">
          <div class="card">
            <div class="card-title">静默 / 维护窗口 (${(si.items || []).length})
              <div class="right"><button class="btn btn-sm" id="si-add">${icon('plus', { size: 12 })} 新建窗口</button></div>
            </div>
            ${ui.table([
              { k: 'name', label: '名称' },
              { k: 'kind', label: '类型', render: (v) => ui.badge(v === 'daily' ? '每日时段' : '单次', '') },
              { k: 'scope', label: '作用域', render: (v, x) => fmt.esc(v === 'rule' ? '规则 ' + (x.ruleId || '') : v === 'metric' ? '指标 ' + (x.metric || '') : '全部') },
              { k: 'when', label: '时段', cls: 'mono small', render: (v, x) => fmt.esc(x.kind === 'daily' ? `${x.from}-${x.to} (${(x.days || []).join(',')})` : `${new Date(x.startsAt).toLocaleString('zh-CN', { hour12: false })} ~ ${new Date(x.endsAt).toLocaleString('zh-CN', { hour12: false })}`) },
              { k: 'active', label: '生效', render: (v) => v ? ui.badge('生效中', 'warn') : '<span class="muted">未生效</span>' },
              { k: '', label: '操作', render: (v, x) => `<button class="btn btn-sm btn-danger" data-si-del="${fmt.esc(x.id)}">删除</button>` },
            ], si.items || []) }
          </div>
          <div class="card">
            <div class="card-title">告警 / 恢复事件（最近 ${((ev.items || [])).length} 条）</div>
            ${ui.table([
              { k: 'ts', label: '时间', cls: 'mono', render: (v) => new Date(v * 1000).toLocaleString('zh-CN', { hour12: false }) },
              { k: 'level', label: '级别', render: (v) => ui.badge(v, v === 'critical' ? 'bad' : v === 'warn' ? 'warn' : '') },
              { k: 'title', label: '内容', cls: 'small', render: (v) => fmt.esc(v) },
              { k: 'source', label: '来源', cls: 'mono small', render: (v) => fmt.esc((v || '').slice(0, 30)) },
            ], ev.items || [], { style: 'max-height:320px' })}
          </div>
        </div>`;
        bind(metricOpts, ruleOpts, metrics.map((m) => m.key));
      }

      const fmtNum = (v) => Math.round(v * 100) / 100;
      const metricLabel = (key) => ((data.rules.metrics || []).find((m) => m.key === key) || {}).label || key;

      function ruleForm(x, metricOpts) {
        return modal.prompt(x ? '编辑规则' : '新建规则', [
          { name: 'name', label: '规则名称', value: (x && x.name) || '' },
          { name: 'metric', label: '指标', type: 'select', options: metricOpts, value: (x && x.metric) || metricOpts[0] },
          { name: 'op', label: '比较', type: 'select', options: OPS, value: (x && x.op) || '>=' },
          { name: 'threshold', label: '阈值', value: x && x.threshold != null ? x.threshold : '' },
          { name: 'forSec', label: '持续秒数（0=立即）', value: (x && x.forSec) || 0 },
          { name: 'level', label: '级别', type: 'select', options: LEVELS, value: (x && x.level) || 'warn' },
          { name: 'cooldownSec', label: '冷却秒数（重复通知间隔）', value: (x && x.cooldownSec != null) ? x.cooldownSec : 900 },
          { name: 'channelIds', label: '通知渠道 id（逗号分隔，空=仅面板铃铛）', value: ((x && x.channelIds) || []).join(',') },
          { name: 'notifyRecover', label: '恢复时也通知', type: 'select', options: ['是', '否'], value: x && x.notifyRecover ? '是' : '是' },
          { name: 'note', label: '备注', value: (x && x.note) || '' },
        ], { okText: '保存', width: '560px' });
      }

      function silenceForm(ruleOpts, metricOpts) {
        return modal.prompt('新建静默窗口', [
          { name: 'name', label: '名称（如：夜间备份窗口）', value: '' },
          { name: 'kind', label: '类型', type: 'select', options: ['once', 'daily'], value: 'once' },
          { name: 'scope', label: '作用域', type: 'select', options: ['all', 'rule', 'metric'], value: 'all' },
          { name: 'ruleId', label: '作用规则 id（scope=rule 时填）', value: ruleOpts[0] || '' },
          { name: 'metric', label: '作用指标 key（scope=metric 时填）', value: metricOpts[0] || '' },
          { name: 'startsAt', label: '开始（once：YYYY-MM-DD HH:MM）', value: new Date(Date.now()).toLocaleString('sv-SE').slice(0, 16) },
          { name: 'endsAt', label: '结束（once）', value: new Date(Date.now() + 3600000).toLocaleString('sv-SE').slice(0, 16) },
          { name: 'from', label: '每日起（daily：HH:MM）', value: '23:00' },
          { name: 'to', label: '每日止（daily，可跨零点）', value: '06:00' },
          { name: 'days', label: '星期（daily：逗号分隔 0-6，0=周日）', value: '1,2,3,4,5' },
          { name: 'reason', label: '原因', value: '' },
        ], { okText: '创建', width: '560px' });
      }

      function bind(metricOpts, ruleOpts) {
        const byId = (id) => (data.rules.items || []).find((x) => x.id === id);
        root.querySelectorAll('[data-act]').forEach((b) => {
          b.onclick = async () => {
            const id = b.dataset.id;
            const item = byId(id);
            try {
              if (b.dataset.act === 'test') {
                toast('试跑中（用当前真实指标值）…', 'info');
                const r = await CM.api.post('/ops/alerts/rule/test', { id });
                const cv = r && r.currentValue;
                toast(`试跑完成：当前值 ${cv == null ? '无数据' : fmtNum(cv)}（已按规则走通知链路，见通知记录）`, 'ok');
                await load();
              } else if (b.dataset.act === 'toggle') {
                await CM.api.post('/ops/alerts/rule/toggle', { id }); await load();
              } else if (b.dataset.act === 'del') {
                if (await modal.confirm('删除规则', `删除 <b class="mono">${fmt.esc(item && item.name || id)}</b>？`, { danger: true, okText: '删除' })) {
                  await CM.api.post('/ops/alerts/rule/delete', { id }); await load();
                }
              } else if (b.dataset.act === 'edit') {
                const f = await ruleForm(item, metricOpts);
                if (!f) return;
                await CM.api.post('/ops/alerts/rule/save', ruleBody(f, id));
                toast('已保存', 'ok'); await load();
              }
            } catch (e) { toast(e.message, 'err'); }
          };
        });
        root.querySelectorAll('[data-si-del]').forEach((b) => {
          b.onclick = async () => {
            if (await modal.confirm('删除静默窗口', '删除后该窗口立即失效。', { danger: true, okText: '删除' })) {
              try { await CM.api.post('/ops/alerts/silence/delete', { id: b.dataset.siDel }); await load(); }
              catch (e) { toast(e.message, 'err'); }
            }
          };
        });
        const add = document.getElementById('ru-add');
        if (add) add.onclick = async () => {
          const f = await ruleForm(null, metricOpts);
          if (!f) return;
          try { await CM.api.post('/ops/alerts/rule/save', ruleBody(f, null)); toast('已创建', 'ok'); await load(); }
          catch (e) { toast(e.message, 'err'); }
        };
        const siAdd = document.getElementById('si-add');
        if (siAdd) siAdd.onclick = async () => {
          const f = await silenceForm(ruleOpts, metricOpts);
          if (!f) return;
          try {
            await CM.api.post('/ops/alerts/silence/save', silenceBody(f));
            toast('已创建', 'ok'); await load();
          } catch (e) { toast(e.message, 'err'); }
        };
        const rf = document.getElementById('ru-refresh');
        if (rf) rf.onclick = () => load();
      }

      function ruleBody(f, id) {
        return {
          id: id || undefined, name: f.name, metric: f.metric, op: f.op,
          threshold: Number(f.threshold) || 0, forSec: Number(f.forSec) || 0,
          level: f.level, cooldownSec: Number(f.cooldownSec) || 0,
          channelIds: String(f.channelIds || '').split(/[\s,]+/).filter(Boolean),
          notifyRecover: f.notifyRecover === '是', enabled: true, note: f.note,
        };
      }

      function silenceBody(f) {
        const day = (s, fallback) => {
          const t = new Date(String(s).replace(' ', 'T'));
          return isNaN(t) ? fallback : t.getTime();
        };
        return {
          name: f.name, kind: f.kind, scope: f.scope,
          ruleId: f.scope === 'rule' ? f.ruleId : undefined,
          metric: f.scope === 'metric' ? f.metric : undefined,
          startsAt: f.kind === 'once' ? day(f.startsAt, Date.now()) : undefined,
          endsAt: f.kind === 'once' ? day(f.endsAt, Date.now() + 3600000) : undefined,
          from: f.kind === 'daily' ? f.from : undefined,
          to: f.kind === 'daily' ? f.to : undefined,
          days: f.kind === 'daily' ? String(f.days || '').split(/[\s,]+/).filter(Boolean).map(Number) : undefined,
          reason: f.reason, enabled: true,
        };
      }

      await load();
    },
  });
})();
