/* 运维增强 · 磁盘治理：容量+inode、大文件 top、目录占用、轮转日志与截断、五类清理（回报真实释放量）、每日计划。 */
(function () {
  'use strict';

  registerPage({
    id: 'ops-disk',
    title: '磁盘治理',
    desc: '容量与 inode · 大文件与目录占用 · 轮转日志清理与截断 · 五类清理器 · 每日自动维护计划',
    async mount(root) {
      root.innerHTML = '<div class="card">' + ui.skeleton(6) + '</div>';
      let data = {};
      let path = '/var';
      let minMb = 100;

      async function load() {
        const out = {};
        await Promise.all([
          '/ops/disk/overview', '/ops/disk/schedule', '/ops/disk/events?limit=20',
          `/ops/disk/bigfiles?path=${encodeURIComponent(path)}&minMb=${minMb}&limit=40`,
          `/ops/disk/topdirs?path=${encodeURIComponent(path)}&depth=1&limit=15`,
          `/ops/disk/logs?path=${encodeURIComponent(path)}`,
        ].map(async (p) => {
          const key = p.split('?')[0].split('/').pop();
          try { out[key] = await CM.api.get(p, 90000); } catch (e) { out[key] = { __error: e.message }; }
        }));
        data = out;
        render();
      }

      function render() {
        const ov = data.overview || {}, bf = data.bigfiles || {}, td = data.topdirs || {}, lg = data.logs || {}, sch = data.schedule || {}, ev = data.events || {};
        if (ov.__error) { root.innerHTML = `<div class="card"><div class="muted">读取失败：${fmt.esc(ov.__error)}（需部署 ops 模块）</div></div>`; return; }
        const mounts = (ov.mounts || []).map((m) => Object.assign({}, m, { _pct: m.usedPct || 0 }));

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">真实卷</div><div class="stat-value">${mounts.length}</div><div class="stat-sub">≥1GiB 才参与统计</div></div>
          <div class="card stat"><div class="stat-label">journal 占用</div><div class="stat-value" style="font-size:18px">${fmt.bytes((ov.journalKb || 0) * 1024)}</div><div class="stat-sub">APT ${fmt.bytes((ov.aptCacheKb || 0) * 1024)} · /var/log ${fmt.bytes((ov.varLogKb || 0) * 1024)}</div></div>
          <div class="card stat"><div class="stat-label">面板回收站</div><div class="stat-value" style="font-size:18px">${fmt.bytes((ov.trashKb || 0) * 1024)}</div><div class="stat-sub">30 天自动过期</div></div>
          <div class="card stat"><div class="stat-label">每日计划</div><div class="stat-value" style="font-size:18px">${ov.scheduleOn || sch.config && sch.config.enabled ? '开启' : '关闭'}</div><div class="stat-sub">${sch.config ? '每天 ' + sch.config.hour + ':00 · 上次 ' + (sch.config.lastRunDay || '—') : ''}</div></div>
        </div>

        <div class="card mb">
          <div class="card-title">容量与 inode</div>
          ${ui.table([
            { k: 'mount', label: '挂载点', cls: 'mono' },
            { k: 'filesystem', label: '设备', cls: 'mono small' },
            { k: 'usedPct', label: '容量', render: (v, m) => `${ui.bar(v, m.usedPct >= 90 ? 'bad' : m.usedPct >= 75 ? 'warn' : 'ok')} <span class="small mono">${v}% · ${fmt.bytes(m.usedKb * 1024)} / ${fmt.bytes(m.totalKb * 1024)}</span>` },
            { k: 'inodeUsedPct', label: 'inode', render: (v) => `${ui.bar(v, v >= 90 ? 'bad' : v >= 75 ? 'warn' : 'ok')} <span class="small mono">${v}%</span>` },
            { k: 'availKb', label: '可用', cls: 'mono', render: (v) => fmt.bytes(v * 1024) },
          ], mounts)}
        </div>

        <div class="card mb">
          <div class="card-title">占用分析
            <div class="right" style="display:flex;gap:6px;align-items:center">
              <input class="inp mono" id="dk-path" style="width:220px" value="${fmt.esc(path)}" placeholder="目录路径">
              <select class="inp" id="dk-minmb" style="width:110px">${[10, 50, 100, 500, 1024].map((m) => `<option value="${m}" ${m === minMb ? 'selected' : ''}>≥${m}MB</option>`).join('')}</select>
              <button class="btn btn-sm btn-primary" id="dk-go">${icon('search', { size: 12 })} 分析</button>
            </div>
          </div>
          <div class="grid g2">
            <div>
              <div class="small muted" style="padding:8px 16px 0">大文件 Top（${fmt.esc(path)}）</div>
              ${ui.table([
                { k: 'path', label: '文件', cls: 'mono small wrap' },
                { k: 'size', label: '大小', cls: 'mono', render: (v) => fmt.bytes(v) },
                { k: '', label: '', render: (v, r) => `<button class="btn btn-sm btn-danger" data-trunc="${fmt.esc(r.path)}">截断</button>` },
              ], bf.items || [], { style: 'max-height:300px' })}
            </div>
            <div>
              <div class="small muted" style="padding:8px 16px 0">目录占用 Top（du，一层）</div>
              ${ui.table([
                { k: 'path', label: '目录', cls: 'mono small wrap' },
                { k: 'kb', label: '占用', cls: 'mono', render: (v, r) => {
                  const top = ((td.items || [])[0] || { kb: 1 }).kb || 1;
                  return `${ui.bar(Math.max(1, Math.round(v / top * 100)))} <span class="small mono">${fmt.bytes(v * 1024)}</span>`;
                } },
              ], td.items || [], { style: 'max-height:300px' })}
              <div class="small muted" style="padding:0 16px">轮转日志（可安全清理的旧归档）</div>
              ${ui.table([
                { k: 'path', label: '文件', cls: 'mono small wrap' },
                { k: 'size', label: '大小', cls: 'mono', render: (v) => fmt.bytes(v) },
                { k: 'mtimeText', label: '时间', cls: 'mono small' },
              ], lg.items || [], { style: 'max-height:200px' })}
            </div>
          </div>
          <div class="small muted" style="padding:8px 16px">截断只允许白名单目录内的普通文件（拒绝软链与目录），截断前后字节数都会进审计。</div>
        </div>

        <div class="grid g2 mb">
          <div class="card">
            <div class="card-title">清理器（执行前确认，回报真实释放量）
              <div class="right"><button class="btn btn-sm btn-danger" id="dk-clean">${icon('trash', { size: 12 })} 执行清理</button></div>
            </div>
            <div style="padding:4px 16px 12px">
              ${(sch.cleaners || []).map((c) => `
                <label class="fld mb" style="display:flex;gap:8px;align-items:center">
                  <input type="checkbox" data-cleaner="${fmt.esc(c.key)}" checked style="width:auto">
                  <span>${fmt.esc(c.label)} <span class="muted small mono">${fmt.esc(c.key)}</span></span>
                </label>`).join('') || '<div class="muted small">未枚举到清理器</div>'}
            </div>
          </div>
          <div class="card">
            <div class="card-title">每日自动维护计划
              <div class="right"><button class="btn btn-sm btn-primary" id="dk-sched-save">保存计划</button></div>
            </div>
            <div class="grid g2" style="padding:4px 16px 12px">
              <label class="fld">启用<select class="inp" id="dk-en"><option value="1" ${sch.config && sch.config.enabled ? 'selected' : ''}>开启</option><option value="0" ${sch.config && sch.config.enabled ? '' : 'selected'}>关闭</option></select></label>
              <label class="fld">执行时刻（0-23 点）<input class="inp mono" id="dk-hour" value="${sch.config ? sch.config.hour : 4}"></label>
              <label class="fld">journal 上限<input class="inp mono" id="dk-jsize" value="${sch.config ? sch.config.journalSize : '200M'}"></label>
              <label class="fld">journal 保留天数<input class="inp mono" id="dk-jdays" value="${sch.config ? sch.config.journalDays : 14}"></label>
              <label class="fld">轮转日志保留天数<input class="inp mono" id="dk-rotd" value="${sch.config ? sch.config.rotDays : 14}"></label>
              <label class="fld">/tmp 过期天数<input class="inp mono" id="dk-tmpd" value="${sch.config ? sch.config.tmpDays : 7}"></label>
            </div>
            <div class="small muted" style="padding:0 16px 12px">计划在每日固定时刻执行勾选的清理项；参数会被服务端夹在合法范围。</div>
          </div>
        </div>

        <div class="card">
          <div class="card-title">清理 / 维护事件</div>
          ${ui.table([
            { k: 'at', label: '时间', cls: 'mono', render: (v) => new Date(v).toLocaleString('zh-CN', { hour12: false }) },
            { k: 'targets', label: '清理项', cls: 'mono small', render: (v) => fmt.esc((v || []).join(',')) },
            { k: 'ok', label: '结果', render: (v) => v ? ui.badge('成功', 'ok') : ui.badge('失败', 'bad') },
            { k: 'freedKb', label: '释放', cls: 'mono', render: (v) => v ? fmt.bytes(v * 1024) : '—' },
            { k: 'source', label: '来源', cls: 'mono small' },
          ], ev.items || [], { style: 'max-height:280px' })}
        </div>`;
        bind();
      }

      function bind() {
        const go = document.getElementById('dk-go');
        if (go) go.onclick = () => {
          path = document.getElementById('dk-path').value.trim() || path;
          minMb = Number(document.getElementById('dk-minmb').value) || 100;
          load();
        };
        root.querySelectorAll('[data-trunc]').forEach((b) => {
          b.onclick = async () => {
            const p = b.dataset.trunc;
            if (!await modal.confirm('清空日志文件', `把 <b class="mono">${fmt.esc(p)}</b> 截断为 0 字节？（保留文件句柄，用于轮转中的日志）`, { danger: true, okText: '截断' })) return;
            try { const r = await CM.api.post('/ops/disk/truncate', { path: p }); toast('已截断', 'ok'); await load(); }
            catch (e) { toast(e.message, 'err'); }
          };
        });
        const clean = document.getElementById('dk-clean');
        if (clean) clean.onclick = async () => {
          const targets = [...root.querySelectorAll('[data-cleaner]')].filter((c) => c.checked).map((c) => c.dataset.cleaner);
          if (!targets.length) { toast('请至少勾选一个清理项', 'warn'); return; }
          if (!await modal.confirm('执行清理', `执行：${targets.map(fmt.esc).join('、')}<br>清理动的是日志/缓存/悬空镜像，不动数据卷。`, { danger: true, okText: '我明白，执行' })) return;
          toast('清理中…', 'info');
          try {
            const r = await CM.api.post('/ops/disk/clean', { targets });
            modal.output('清理结果', JSON.stringify(r, null, 2), !r || r.ok === false);
            await load();
          } catch (e) { toast(e.message, 'err'); }
        };
        const save = document.getElementById('dk-sched-save');
        if (save) save.onclick = async () => {
          try {
            await CM.api.post('/ops/disk/schedule/save', {
              enabled: document.getElementById('dk-en').value === '1',
              hour: Number(document.getElementById('dk-hour').value) || 4,
              journalSize: document.getElementById('dk-jsize').value.trim() || '200M',
              journalDays: Number(document.getElementById('dk-jdays').value) || 14,
              rotDays: Number(document.getElementById('dk-rotd').value) || 14,
              tmpDays: Number(document.getElementById('dk-tmpd').value) || 7,
              targets: [...root.querySelectorAll('[data-cleaner]')].filter((c) => c.checked).map((c) => c.dataset.cleaner),
            });
            toast('计划已保存', 'ok'); await load();
          } catch (e) { toast(e.message, 'err'); }
        };
        const rf = document.getElementById('sec-refresh') || document.getElementById('dk-refresh');
      }

      await load();
    },
  });
})();
