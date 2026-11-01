/* Databases page - full DBA console (PostgreSQL / MySQL) */
(function () {
  'use strict';

  const ENGINE_LABEL = {
    postgres: 'PostgreSQL', mysql: 'MySQL / MariaDB', sqlite: 'SQLite', db2: 'IBM Db2',
    redis: 'Redis', mongo: 'MongoDB',
  };
  const ENGINE_KIND = { postgres: 'sql', mysql: 'sql', sqlite: 'sql', db2: 'sql', redis: 'kv', mongo: 'doc' };
  const ENGINE_ORDER = ['postgres', 'mysql', 'sqlite', 'db2', 'redis', 'mongo'];
  /* 一键安装目标：apt 引擎直接装包；mongo/db2 由后端添加官方源后安装 */
  const INSTALL_TARGETS = [
    { engine: 'postgres', label: 'PostgreSQL', hint: 'apt install postgresql' },
    { engine: 'mysql', label: 'MySQL / MariaDB', hint: 'apt install mysql-server' },
    { engine: 'sqlite', label: 'SQLite', hint: 'apt install sqlite3' },
    { engine: 'redis', label: 'Redis', hint: 'apt install redis-server' },
    { engine: 'mongo', label: 'MongoDB', hint: '添加 MongoDB 官方源并安装 mongodb-org' },
    { engine: 'db2', label: 'IBM Db2', hint: '添加 IBM 官方源并安装 ibm-db2（体积较大，需接受许可）' },
  ];
  const TEMPLATES = [
    ['-- 模板 --', ''],
    ['SELECT * FROM table LIMIT 100;', 'SELECT * FROM table LIMIT 100;'],
    ['CREATE TABLE', 'CREATE TABLE new_table (\n  id bigserial PRIMARY KEY,\n  name text NOT NULL,\n  created_at timestamptz DEFAULT now()\n);'],
    ['ALTER TABLE ADD COLUMN', 'ALTER TABLE mytable ADD COLUMN new_col text;'],
    ['CREATE INDEX', 'CREATE INDEX idx_mytable_col ON mytable (col);'],
    ['INSERT', 'INSERT INTO mytable (col1, col2) VALUES (1, \'value\');'],
    ['UPDATE', 'UPDATE mytable SET col1 = \'x\' WHERE id = 1;'],
    ['DELETE', 'DELETE FROM mytable WHERE id = 1;'],
    ['GRANT', 'GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO appuser;'],
    ['CREATE VIEW', 'CREATE VIEW v AS SELECT ...;'],
    ['统计：表大小', "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC;"],
    ['统计：慢查询', "SELECT pid, now()-query_start AS duration, left(query,120) FROM pg_stat_activity WHERE state='active' AND query_start IS NOT NULL ORDER BY duration DESC;"],
  ];

  const S = {
    engines: [], engine: null, tab: 'overview',
    database: null, table: null,
    page: 0, pageSize: 50, orderBy: '', orderDir: 'asc', where: '',
    schema: null, tableView: 'data',
    kvKey: null, kvPattern: '*', kvDb: '0',
    docDb: null, docColl: null,
  };

  /* active-engine capability flags (computed per engine switch) */
  let isPg = false, isMy = false, isSqlite = false, isDb2 = false, isSql = false;
  /* the page's own container (the router mounts us inside #view's .page > .page-body) */
  let ROOT = null;
  function computeFlags() {
    const e = S.engine;
    isPg = e === 'postgres'; isMy = e === 'mysql'; isSqlite = e === 'sqlite'; isDb2 = e === 'db2';
    isSql = ENGINE_KIND[e] === 'sql';
  }
  const kindOf = (e) => ENGINE_KIND[e] || 'sql';

  /* merge collector detection with /db/engines into a single installed list */
  function buildEngineList(fromCollector, fromExtra) {
    const byId = {};
    for (const e of (fromCollector || [])) {
      if (e.installed === false) continue;
      byId[e.id] = { id: e.id, name: ENGINE_LABEL[e.id] || e.name || e.id, active: !!e.active, version: e.version || null, service: e.service || null };
    }
    for (const e of (fromExtra || [])) {
      if (!e.installed) continue;
      byId[e.id] = Object.assign(byId[e.id] || {}, { id: e.id, name: ENGINE_LABEL[e.id] || e.name, kind: e.kind, active: !!e.active, service: e.service || null, packages: e.packages });
    }
    return ENGINE_ORDER.map((id) => byId[id]).filter(Boolean);
  }

  /* re-read both engine sources (collector + extra) and refresh S.engines */
  async function refreshEngines() {
    let d = {}, ex = { engines: [] };
    try { d = await CM.api.get('/databases', 10000); } catch (_) {}
    try { ex = await CM.api.get('/db/engines', 10000); } catch (_) {}
    S.host = d;
    S.engineHints = ex.engines || [];
    S.engines = buildEngineList(d.engines || [], ex.engines || []);
    if (!S.engines.find((e) => e.id === S.engine)) S.engine = (S.engines[0] || {}).id || null;
    computeFlags();
    return S.engines;
  }

  registerPage({
    id: 'databases',
    title: '数据库',
    desc: 'PostgreSQL / MySQL / SQLite / Db2 / Redis / MongoDB 的库表管理、SQL 控制台、性能洞察与备份恢复',
    async mount(root) {
      ROOT = root;
      root.innerHTML = `<div class="card">${ui.skeleton(6)}</div>`;
      let d = {}, ex = { engines: [] };
      try { d = await CM.api.get('/databases', 10000); } catch (e) { /* 采集失败不致命 */ }
      try { ex = await CM.api.get('/db/engines', 10000); } catch (_) {}
      S.host = d;
      S.engineHints = ex.engines || [];
      S.engines = buildEngineList(d.engines || [], ex.engines || []);

      if (!S.engines.length) { renderInstallAll(root); return; }
      if (!S.engine || !S.engines.find((e) => e.id === S.engine)) S.engine = S.engines[0].id;
      computeFlags();
      renderShell(root);
      await renderTab();
    },
  });

  /* ================= install (when nothing is installed / add more) ================= */
  function renderInstallAll(root) {
    const known = {};
    (S.engineHints || []).forEach((e) => { known[e.id] = e; });
    (S.host && S.host.engines ? S.host.engines : []).forEach((e) => { known[e.id] = Object.assign(known[e.id] || {}, e); });
    const installed = new Set(S.engines.map((e) => e.id));
    const cards = INSTALL_TARGETS.map((t) => {
      const info = known[t.engine] || {};
      const isInstalled = installed.has(t.engine) || info.installed;
      return `<div class="card" style="display:flex;flex-direction:column;gap:8px">
        <div class="row between"><b>${fmt.esc(t.label)}</b>${isInstalled ? ui.badge('已安装', 'ok') : ui.badge('未安装', 'gray')}</div>
        <div class="muted small">${fmt.esc(t.hint)}</div>
        <button class="btn ${isInstalled ? '' : 'btn-primary'}" data-install="${t.engine}" ${isInstalled ? 'disabled' : ''}>${icon('download', { size: 13 })} 安装 ${fmt.esc(t.label)}</button>
      </div>`;
    }).join('');
    root.innerHTML = `<div class="card">
        <div class="card-title">数据库引擎总览</div>
        <div class="muted small mb">未检测到任何可用的数据库引擎。可在此一键安装常见数据库（安装后需刷新页面）。</div>
      </div>
      <div class="grid g3">${cards}</div>`;
    root.querySelectorAll('[data-install]').forEach((b) => {
      b.onclick = async () => {
        const engine = b.dataset.install;
        const label = ENGINE_LABEL[engine] || engine;
        const okc = await modal.confirm('安装 ' + label, `将在服务器上安装 <b>${fmt.esc(label)}</b>，可能需要数分钟（Db2/MongoDB 更久）。<br><span class="muted small">安装过程会添加所需软件源并自动启动服务。</span>`, { okText: '开始安装' });
        if (!okc) return;
        ui.busy(b, true);
        try {
          if (engine === 'postgres' || engine === 'mysql') {
            await runAction('/pkg/install', { pkg: engine === 'postgres' ? 'postgresql' : 'mysql-server' }, { showOutput: true, title: label + ' 安装日志' });
          } else {
            await runAction('/db/engine/install', { engine }, { showOutput: true, title: label + ' 安装日志' });
          }
          window.toast(label + ' 安装完成，正在刷新…', 'ok', 6000);
          setTimeout(() => location.reload(), 1500);
        } catch (_) { ui.busy(b, false); }
      };
    });
  }


  /* ================= shell ================= */
  function renderShell(root) {
    root.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row" id="db-engines"></div>
          <div class="row">
            <span class="muted small">引擎</span>
            <button class="btn btn-sm" id="db-add-engine">${icon('plus', { size: 12 })} 安装引擎</button>
            <button class="btn btn-sm" id="db-refresh">${icon('refresh', { size: 12 })} 刷新引擎信息</button>
          </div>
        </div>
      </div>
      <div class="range-btns" id="db-tabs" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:14px"></div>
      <div id="db-tab"></div>`;

    const eb = document.getElementById('db-engines');
    /* systemd unit per engine (server always resolves it; this is only for deciding whether to show a toggle) */
    const SVC = { postgres: 'postgresql', mysql: 'mysql', sqlite: null, db2: null, redis: 'redis-server', mongo: 'mongod' };
    eb.innerHTML = S.engines.map((e) => {
      const badge = e.active ? ui.badge('运行中', 'ok') : ui.badge('未运行', 'bad');
      const cls = 'btn btn-sm' + (e.id === S.engine ? ' active btn-primary' : '');
      const k = kindOf(e.id);
      const tag = k === 'kv' ? ui.badge('KV', 'purple') : k === 'doc' ? ui.badge('DOC', 'purple') : '';
      const unit = e.service || SVC[e.id] || null;
      const svc = unit
        ? `<button class="btn btn-sm ${e.active ? '' : 'btn-ok'}" data-svc="${fmt.esc(e.id)}" data-act="${e.active ? 'stop' : 'start'}" title="${e.active ? '停止 ' + fmt.esc(unit) : '启动 ' + fmt.esc(unit)}">${e.active ? icon('stop', { size: 11 }) + ' 停止' : icon('play', { size: 11 }) + ' 启动'}</button>`
        : '';
      return `<span class="row" style="gap:5px">
        <button class="${cls}" data-eng="${e.id}" style="${e.id === S.engine ? '' : 'opacity:.8'}">${fmt.esc(e.name)} ${tag} ${badge}</button>${svc}</span>`;
    }).join('');
    eb.querySelectorAll('[data-eng]').forEach((b) => {
      b.onclick = () => { S.engine = b.dataset.eng; S.database = null; S.table = null; S.page = 0; S.tab = 'overview'; computeFlags(); renderShell(ROOT); renderTab(); };
    });
    eb.querySelectorAll('[data-svc]').forEach((b) => {
      b.onclick = async () => {
        const engine = b.dataset.svc, act = b.dataset.act;
        const label = ENGINE_LABEL[engine] || engine;
        const okc = await modal.confirm(act === 'stop' ? '停止 ' + label : '启动 ' + label,
          act === 'stop' ? `将停止 <b>${fmt.esc(label)}</b> 服务（不会删除数据）。` : `将启动 <b>${fmt.esc(label)}</b> 服务。`,
          { danger: act === 'stop', okText: act === 'stop' ? '停止' : '启动' });
        if (!okc) return;
        ui.busy(b, true);
        try { await runAction('/db/engine/service', { engine, action: act }, { showOutput: true, title: '服务操作' }); }
        catch (_) {}
        ui.busy(b, false);
        CM.api.invalidate('/databases');
        CM.api.invalidate('/db/');
        await refreshEngines();
        renderShell(ROOT); renderTab();
      };
    });

    const SQL_TABS = [['overview', '总览与监控'], ['databases', '数据库管理'], ['tables', '表与数据'], ['console', 'SQL 控制台'], ['insights', '性能洞察'], ['objects', '对象浏览器'], ['roles', '用户与权限'], ['backups', '备份与恢复'], ['advanced', '维护与高级']];
    const KV_TABS = [['overview', '总览'], ['keys', '键空间'], ['console', '命令控制台'], ['roles', '用户与权限 (ACL)'], ['backups', '备份与恢复'], ['advanced', '配置与维护']];
    const DOC_TABS = [['overview', '总览'], ['databases', '数据库'], ['tables', '集合与文档'], ['console', 'Shell 控制台'], ['roles', '用户与权限'], ['backups', '备份与恢复'], ['advanced', '索引与维护']];
    const TABS = kindOf(S.engine) === 'kv' ? KV_TABS : kindOf(S.engine) === 'doc' ? DOC_TABS : SQL_TABS;
    const tb = document.getElementById('db-tabs');
    tb.innerHTML = TABS.map(([k, l]) => `<button class="btn btn-sm ${S.tab === k ? 'active btn-primary' : ''}" data-tab="${k}">${l}</button>`).join('');
    tb.querySelectorAll('[data-tab]').forEach((b) => {
      b.onclick = () => { S.tab = b.dataset.tab; tb.querySelectorAll('[data-tab]').forEach((x) => x.classList.toggle('active', x === b)); tb.querySelectorAll('[data-tab]').forEach((x) => x.classList.toggle('btn-primary', x === b)); renderTab(); };
    });

    document.getElementById('db-add-engine').onclick = () => openInstallDialog();
    document.getElementById('db-refresh').onclick = async () => {
      ui.busy(document.getElementById('db-refresh'), true);
      CM.api.invalidate('/databases');
      CM.api.invalidate('/db/');
      await refreshEngines();
      ui.busy(document.getElementById('db-refresh'), false);
      renderShell(ROOT); renderTab();
    };
  }

  /* dialog listing every engine with install / status */
  function openInstallDialog() {
    const known = {};
    (S.engineHints || []).forEach((e) => { known[e.id] = e; });
    const installed = new Set(S.engines.map((e) => e.id));
    const rows = INSTALL_TARGETS.map((t) => {
      const info = known[t.engine] || {};
      const isInstalled = installed.has(t.engine) || info.installed;
      return { label: t.label, engine: t.engine, hint: t.hint, status: isInstalled, _installed: isInstalled };
    });
    const mask = document.createElement('div');
    mask.className = 'modal-mask';
    mask.innerHTML = `<div class="modal" style="width:620px">
      <div class="modal-head">数据库引擎安装</div>
      <div class="modal-body">${ui.table([
        { k: 'label', label: '引擎', cls: 'mono' },
        { k: 'status', label: '状态', render: (v) => v ? ui.badge('已安装', 'ok') : ui.badge('未安装', 'gray') },
        { k: 'hint', label: '安装方式', cls: 'muted small' },
        { k: 'engine', label: '操作', render: (v, r) => r._installed ? '<span class="muted small">—</span>' : `<button class="btn btn-sm btn-primary" data-do="${fmt.esc(v)}">安装</button>` },
      ], rows)}</div>
      <div class="modal-foot"><button class="btn btn-primary" data-a="close">关闭</button></div></div>`;
    document.getElementById('modal-root').appendChild(mask);
    const close = () => { mask.querySelector('.modal').classList.add('closing'); mask.classList.add('closing'); setTimeout(() => mask.remove(), 190); };
    mask.querySelector('[data-a=close]').onclick = close;
    mask.addEventListener('click', (e) => { if (e.target === mask) close(); });
    mask.querySelectorAll('[data-do]').forEach((b) => {
      b.onclick = async () => {
        const engine = b.dataset.do;
        const label = ENGINE_LABEL[engine] || engine;
        const okc = await modal.confirm('安装 ' + label, `在服务器上安装 <b>${fmt.esc(label)}</b>？可能需要数分钟。`, { okText: '开始安装' });
        if (!okc) return;
        ui.busy(b, true);
        try {
          if (engine === 'postgres' || engine === 'mysql') await runAction('/pkg/install', { pkg: engine === 'postgres' ? 'postgresql' : 'mysql-server' }, { showOutput: true, title: label + ' 安装日志' });
          else await runAction('/db/engine/install', { engine }, { showOutput: true, title: label + ' 安装日志' });
          window.toast(label + ' 安装完成，正在刷新…', 'ok', 6000);
          setTimeout(() => location.reload(), 1500);
        } catch (_) { ui.busy(b, false); }
      };
    });
  }

  async function renderTab() {
    const box = document.getElementById('db-tab');
    if (!box) return;
    box.innerHTML = `<div class="card">${ui.skeleton(5)}</div>`;
    try {
      const kind = kindOf(S.engine);
      if (kind === 'kv') {
        if (S.tab === 'overview') await kvOverview(box);
        else if (S.tab === 'keys') await kvKeys(box);
        else if (S.tab === 'console') kvConsole(box);
        else if (S.tab === 'roles') await kvRoles(box);
        else if (S.tab === 'backups') await tabBackups(box);
        else if (S.tab === 'advanced') await kvAdvanced(box);
        return;
      }
      if (kind === 'doc') {
        if (S.tab === 'overview') await docOverview(box);
        else if (S.tab === 'databases') await docDatabases(box);
        else if (S.tab === 'tables') await docCollections(box);
        else if (S.tab === 'console') docConsole(box);
        else if (S.tab === 'roles') await docRoles(box);
        else if (S.tab === 'backups') await tabBackups(box);
        else if (S.tab === 'advanced') await docAdvanced(box);
        return;
      }
      if (S.tab === 'overview') await tabOverview(box);
      else if (S.tab === 'databases') await tabDatabases(box);
      else if (S.tab === 'tables') await tabTables(box);
      else if (S.tab === 'console') tabConsole(box);
      else if (S.tab === 'insights') await tabInsights(box);
      else if (S.tab === 'objects') await tabObjects(box);
      else if (S.tab === 'roles') await tabRoles(box);
      else if (S.tab === 'backups') await tabBackups(box);
      else if (S.tab === 'advanced') await tabAdvanced(box);
    } catch (e) {
      const msg = String(e.message || e);
      const notInstalled = /未安装|未找到|未安装（/i.test(msg);
      const notRunning = /未运行|ECONNREFUSED|连接失败|无法连接|No such file|does not exist.*running|not found/i.test(msg);
      const label = ENGINE_LABEL[S.engine] || S.engine;
      const SVC = { postgres: 'postgresql', mysql: 'mysql', redis: 'redis-server', mongo: 'mongod' };
      const unit = (S.engines.find((x) => x.id === S.engine) || {}).service || SVC[S.engine] || null;
      box.innerHTML = `<div class="card">
        <div class="card-title">${fmt.esc(label)} 无法加载
          ${notInstalled ? ui.badge('未安装', 'gray') : notRunning ? ui.badge('未运行', 'bad') : ui.badge('错误', 'bad')}
        </div>
        <pre class="pre">${fmt.esc(msg)}</pre>
        <div class="row mt">
          ${notRunning && unit ? `<button class="btn btn-ok" id="db-fix-start">${icon('play', { size: 12 })} 启动 ${fmt.esc(label)} 服务</button>` : ''}
          ${notInstalled ? `<button class="btn btn-primary" id="db-fix-install">${icon('download', { size: 12 })} 安装 ${fmt.esc(label)}</button>` : ''}
          <button class="btn" id="db-fix-refresh">${icon('refresh', { size: 12 })} 重新加载</button>
        </div>
        <div class="muted small mt">${unit ? `服务单元 <code>${fmt.esc(unit)}</code> · 安装/启动都是显式操作，默认不会自动启动。` : '该引擎无 systemd 服务（文件型数据库），安装后即可直接使用。'}</div>
      </div>`;
      const reload = async () => { CM.api.invalidate('/databases'); CM.api.invalidate('/db/'); await refreshEngines(); renderShell(ROOT); renderTab(); };
      const sb = document.getElementById('db-fix-start');
      if (sb) sb.onclick = async () => { ui.busy(sb, true); try { await runAction('/db/engine/service', { engine: S.engine, action: 'start' }, { showOutput: true, title: '启动服务' }); } catch (_) {} ui.busy(sb, false); await reload(); };
      const ib = document.getElementById('db-fix-install');
      if (ib) ib.onclick = async () => { ui.busy(ib, true); try { await runAction('/db/engine/install', { engine: S.engine }, { showOutput: true, title: '安装 ' + label }); window.toast(label + ' 安装完成', 'ok'); } catch (_) {} ui.busy(ib, false); await reload(); };
      const rb = document.getElementById('db-fix-refresh');
      if (rb) rb.onclick = () => reload();
    }
  }

  const esc = (v) => v === null || v === undefined ? '<span class="muted">NULL</span>' : fmt.esc(String(v));
  const num = (v) => Number(v || 0).toLocaleString('zh-CN');

  /* ================= 1) overview ================= */
  async function tabOverview(box) {
    const [st, dbs] = await Promise.all([
      CM.api.get('/db/status?engine=' + S.engine),
      CM.api.get('/db/databases?engine=' + S.engine, 10000),
    ]);
    const v = st.version || {}, s = st.stats || {};
    const totalSize = dbs.reduce((a, b) => a + Number(b.size_bytes || 0), 0);
    const stat4 = isPg ? { label: '缓存命中率', value: fmt.esc(s.cache_hit_pct || '-') + '%', sub: 'shared buffers' }
      : isMy ? { label: '累计查询', value: fmt.esc(s.questions || '-'), sub: 'Questions' }
        : isSqlite ? { label: '表 / 索引', value: `${fmt.esc(s.tables || 0)} / ${fmt.esc(s.indexes || 0)}`, sub: 'sqlite_master' }
          : { label: '对象数量', value: fmt.esc(s.tables || s.databases || '-'), sub: 'catalog' };
    const maintButtons = isPg ? [
      ['vacuum', 'VACUUM'], ['vacuum-full', 'VACUUM FULL'], ['analyze', 'ANALYZE'], ['reindex', 'REINDEX DATABASE'], ['checkpoint', 'CHECKPOINT'],
    ] : isMy ? [
      ['analyze-mysql', 'ANALYZE TABLE'], ['optimize', 'OPTIMIZE TABLE'], ['check', 'CHECK TABLE'], ['repair', 'REPAIR TABLE'], ['flush', 'FLUSH PRIVILEGES'],
    ] : isSqlite ? [
      ['vacuum', 'VACUUM'], ['analyze', 'ANALYZE'], ['reindex', 'REINDEX'], ['optimize', 'PRAGMA optimize'], ['check', '完整性检查'], ['wal-checkpoint', 'WAL 检查点'],
    ] : isDb2 ? [
      ['analyze', 'RUNSTATS'], ['vacuum', 'REORG'], ['check', 'REORGCHK'],
    ] : [];
    box.innerHTML = `
      <div class="grid g4 mb">
        <div class="card stat"><div class="stat-label">引擎状态</div><div class="stat-value" style="font-size:20px;color:#4ade80">运行中</div><div class="stat-sub">${fmt.esc(String(v.version || '').slice(0, 46))}</div></div>
        <div class="card stat"><div class="stat-label">连接数</div><div class="stat-value">${fmt.esc(s.connections || '0')}</div><div class="stat-sub">上限 ${fmt.esc(s.max_connections || '-')} · 活动 ${fmt.esc(s.active || '-')}</div></div>
        <div class="card stat"><div class="stat-label">数据库数量</div><div class="stat-value">${dbs.length}</div><div class="stat-sub">总大小 ${fmt.bytes(totalSize)}</div></div>
        <div class="card stat"><div class="stat-label">${stat4.label}</div><div class="stat-value">${stat4.value}</div><div class="stat-sub">${stat4.sub}</div></div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">维护操作</div>
          <div class="row" id="db-maint">
            ${maintButtons.map(([a, l]) => `<button class="btn" data-mt="${a}">${l}</button>`).join('')}
          </div>
          <div class="muted small mt">${isSqlite ? '针对当前数据库文件执行；VACUUM 会重建文件、VACUUM FULL 等价于 VACUUM。' : isDb2 ? 'RUNSTATS/REORG 针对整库或所选表执行，大表可能耗时较长。' : '表级操作需先选择数据库（部分操作可对整库执行）。'}</div>
        </div>
        <div class="card">
          <div class="card-title">服务器信息</div>
          <dl class="kv">
            ${Object.entries(v).map(([k, val]) => `<dt>${fmt.esc(k)}</dt><dd title="${fmt.esc(String(val))}">${fmt.esc(String(val).slice(0, 60))}</dd>`).join('')}
            ${Object.entries(s).map(([k, val]) => `<dt>${fmt.esc(k)}</dt><dd>${fmt.esc(String(val))}</dd>`).join('')}
          </dl>
        </div>
      </div>

      <div class="card">
        <div class="card-title">活动连接 (${st.processes.length}) <div class="right"><button class="btn btn-sm" id="db-reload-proc">刷新</button></div></div>
        ${ui.table([
          { k: 'pid', label: 'PID', cls: 'num' },
          { k: 'user', label: '用户', cls: 'mono' },
          { k: 'db', label: '数据库', cls: 'mono' },
          { k: 'state', label: '状态' },
          { k: 'seconds', label: '时长(s)', cls: 'num' },
          { k: 'client', label: '客户端', cls: 'mono' },
          { k: 'query', label: 'SQL', cls: 'wrap', render: (x) => `<span class="mono small" title="${fmt.esc(x)}">${fmt.esc(String(x).slice(0, 80))}</span>` },
          { k: 'pid', label: '', render: (x) => `<button class="btn btn-sm btn-danger" data-kill="${x}">终止</button>` },
        ], st.processes, { style: 'max-height:320px' })}
      </div>

      <div class="card">
        <div class="card-title">配置变量 (${st.variables.length})
          <div class="right"><input class="inp" id="db-var-q" placeholder="搜索变量名…" style="width:200px"></div>
        </div>
        <div id="db-vars"></div>
      </div>`;

    const varsBox = document.getElementById('db-vars');
    const drawVars = (q) => {
      let rows = st.variables;
      if (q) rows = rows.filter((x) => (x.name + ' ' + x.value).toLowerCase().includes(q.toLowerCase()));
      varsBox.innerHTML = ui.table([
        { k: 'name', label: '变量', cls: 'mono' },
        { k: 'value', label: '值', cls: 'mono wrap' },
        { k: 'category', label: '分类', cls: 'muted small' },
      ], rows.slice(0, 400), { style: 'max-height:380px' });
    };
    drawVars('');
    document.getElementById('db-var-q').oninput = (e) => drawVars(e.target.value);

    document.getElementById('db-reload-proc').onclick = () => renderTab();
    box.querySelectorAll('[data-kill]').forEach((b) => {
      b.onclick = async () => {
        const okc = await modal.confirm('终止连接', `强制终止后端进程 <b class="mono">${b.dataset.kill}</b>？`, { danger: true });
        if (!okc) return;
        runAction('/db/kill', { engine: S.engine, pid: Number(b.dataset.kill) }, { reload: () => renderTab() }).catch(() => {});
      };
    });
    box.querySelectorAll('[data-mt]').forEach((b) => {
      b.onclick = async () => {
        const action = b.dataset.mt;
        const needTable = (isPg || isMy) && ['vacuum', 'vacuum-full', 'analyze', 'analyze-mysql', 'optimize', 'check', 'repair'].includes(action);
        let database = S.database, table = S.table;
        if (needTable) {
          if (!database) {
            const dbs2 = await CM.api.get('/db/databases?engine=' + S.engine, 10000);
            const r = await modal.prompt('选择数据库', [{ name: 'database', label: '数据库', type: 'select', options: dbs2.map((x) => x.name), value: S.database || '' }]);
            if (!r) return;
            database = r.database;
            const tl = await CM.api.get(`/db/tables?engine=${S.engine}&database=${encodeURIComponent(database)}`);
            if (!tl.length) return window.toast('该库没有表', 'warn');
            const r2 = await modal.prompt('选择表', [{ name: 'table', label: '表', type: 'select', options: tl.map((x) => x.schema + '.' + x.name), value: tl[0].schema + '.' + tl[0].name }]);
            if (!r2) return;
            table = r2.table;
          } else if (!table) {
            return window.toast('请先到「表与数据」选择一个表', 'warn');
          }
        }
        const okc = await modal.confirm('维护操作', `对 ${database ? '<b>' + fmt.esc(database) + '</b>' : '当前实例'}${table ? ' / <b>' + fmt.esc(table) + '</b>' : ''} 执行 <b>${action}</b>？`, { danger: true });
        if (!okc) return;
        ui.busy(b, true);
        try { const r = await runAction('/db/maintenance', { engine: S.engine, database, table, action }, { toast: false }); modal.output('维护结果', r.output); }
        catch (_) {}
        ui.busy(b, false);
      };
    });
  }

  /* ================= 2) databases ================= */
  async function tabDatabases(box) {
    const dbs = await CM.api.get('/db/databases?engine=' + S.engine, 10000);
    box.innerHTML = `
      <div class="card">
        <div class="card-title">新建数据库
          <div class="right"><button class="btn btn-sm btn-primary" id="ndb-create">创建</button></div>
        </div>
        <div class="row">
          <label class="fld">${isSqlite ? '数据库文件路径/名称' : '数据库名'}<input class="inp mono" id="ndb-name" placeholder="${isSqlite ? 'mydb.db' : 'mydb'}" style="width:${isSqlite ? 300 : 180}px"></label>
          ${isPg ? `
            <label class="fld">所有者<input class="inp mono" id="ndb-owner" placeholder="postgres" style="width:150px"></label>
            <label class="fld">编码<input class="inp mono" id="ndb-enc" placeholder="UTF8" style="width:120px"></label>` : isMy ? `
            <label class="fld">字符集<input class="inp mono" id="ndb-charset" placeholder="utf8mb4" style="width:140px"></label>
            <label class="fld">排序规则<input class="inp mono" id="ndb-coll" placeholder="utf8mb4_general_ci" style="width:220px"></label>` : isDb2 ? `
            <label class="fld">编码<input class="inp mono" id="ndb-enc" placeholder="UTF-8" style="width:120px"></label>` : `
            <span class="muted small">SQLite 为单文件数据库：不存在则创建新文件，文件名需以 .db/.sqlite 结尾。</span>`}
        </div>
      </div>

      <div class="card">
        <div class="card-title">数据库列表 (${dbs.length}) <div class="right"><button class="btn btn-sm" id="db-list-refresh">刷新</button></div></div>
        ${ui.table([
          { k: 'name', label: '数据库', cls: 'mono', render: (v, r) => `${fmt.esc(v)} ${r.is_template === 't' || r.is_template === true ? ui.badge('模板', 'gray') : ''}` },
          { k: 'owner', label: '所有者', cls: 'mono' },
          { k: 'size', label: '大小', cls: 'num' },
          { k: 'tables', label: '表', cls: 'num', render: (v) => v == null ? '<span class="muted">-</span>' : v },
          { k: 'encoding', label: '编码' },
          { k: 'connections', label: '连接', cls: 'num' },
          { k: 'name', label: '操作', render: (v, r) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm" data-use="${fmt.esc(v)}">进入</button>
              <button class="btn btn-sm" data-exp="${fmt.esc(v)}">导出</button>
              ${isPg ? `<button class="btn btn-sm" data-dbalter="${fmt.esc(v)}">设置</button>` : ''}
              ${(isPg || isSqlite) ? `<button class="btn btn-sm" data-ren="${fmt.esc(v)}">重命名</button>` : ''}
              <button class="btn btn-sm btn-danger" data-dropdb="${fmt.esc(v)}">删除</button></span>` },
        ], dbs, { style: 'max-height:420px' })}
      </div>`;

    document.getElementById('db-list-refresh').onclick = () => renderTab();
    document.getElementById('ndb-create').onclick = async () => {
      const name = document.getElementById('ndb-name').value.trim();
      if (!name) return window.toast('请输入数据库名', 'warn');
      const body = { engine: S.engine, name };
      const owner = document.getElementById('ndb-owner'); if (owner) body.owner = owner.value.trim();
      const enc = document.getElementById('ndb-enc'); if (enc) body.encoding = enc.value.trim();
      const cs = document.getElementById('ndb-charset'); if (cs) body.charset = cs.value.trim();
      const coll = document.getElementById('ndb-coll'); if (coll) body.collation = coll.value.trim();
      const okc = await modal.confirm('创建数据库', `<b class="mono">${fmt.esc(name)}</b>`, { okText: '创建' });
      if (!okc) return;
      try { await runAction('/db/database/create', body); renderTab(); } catch (_) {}
    };
    box.querySelectorAll('[data-use]').forEach((b) => b.onclick = () => { S.database = b.dataset.use; S.table = null; S.tab = 'tables'; renderShell(ROOT); renderTab(); });
    box.querySelectorAll('[data-exp]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('导出数据库', `导出 <b class="mono">${fmt.esc(b.dataset.exp)}</b> 为 SQL 压缩包？`, { okText: '开始导出' });
      if (!okc) return;
      ui.busy(b, true);
      try { const r = await runAction('/db/backup', { engine: S.engine, database: b.dataset.exp, format: 'plain' }, { toast: false }); window.toast(r.output, 'ok'); }
      catch (_) {}
      ui.busy(b, false);
    });
    box.querySelectorAll('[data-ren]').forEach((b) => b.onclick = async () => {
      const r = await modal.prompt('重命名数据库', [{ name: 'to', label: '新名称', value: b.dataset.ren }]);
      if (!r || !r.to) return;
      runAction('/db/database/rename', { engine: S.engine, from: b.dataset.ren, to: r.to }, { reload: () => renderTab() }).catch(() => {});
    });
    box.querySelectorAll('[data-dropdb]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('删除数据库', `将永久删除 <b class="mono">${fmt.esc(b.dataset.dropdb)}</b> 及其全部数据，不可恢复！`, { danger: true, okText: '确认删除' });
      if (!okc) return;
      const force = await modal.confirm('强制断开连接？', '如果有活动连接，删除会失败。是否强制终止这些连接后删除？', { danger: true, okText: '强制删除' });
      runAction('/db/database/drop', { engine: S.engine, name: b.dataset.dropdb, force: !!force }, { reload: () => renderTab() }).catch(() => {});
    });
    box.querySelectorAll('[data-dbalter]').forEach((b) => b.onclick = async () => {
      const database = b.dataset.dbalter;
      const r = await modal.prompt('数据库设置 - ' + database, [
        { name: 'action', label: '操作', type: 'select', options: ['设置参数 (SET)', '重置参数 (RESET)', '修改所有者', '设置连接限制'], value: '设置参数 (SET)' },
        { name: 'key', label: '参数名（如 work_mem / search_path）', value: '' },
        { name: 'value', label: '参数值 / 所有者 / 连接数', value: '' },
      ], { okText: '执行' });
      if (!r) return;
      const a = r.action;
      if (a.startsWith('设置参数')) return runAction('/db/database/alter', { engine: 'postgres', database, action: 'set-param', params: { key: r.key, value: r.value } }).catch(() => {});
      if (a.startsWith('重置')) return runAction('/db/database/alter', { engine: 'postgres', database, action: 'reset-param', params: { key: r.key || 'ALL' } }).catch(() => {});
      if (a.startsWith('修改所有者')) return runAction('/db/database/alter', { engine: 'postgres', database, action: 'owner', params: { owner: r.value } }).catch(() => {});
      return runAction('/db/database/alter', { engine: 'postgres', database, action: 'connlimit', params: { value: r.value } }).catch(() => {});
    });
  }

  /* ================= 3) tables & data ================= */
  async function tabTables(box) {
    if (!S.database) {
      const dbs = await CM.api.get('/db/databases?engine=' + S.engine, 10000);
      box.innerHTML = `<div class="card">
        <div class="card-title">选择数据库</div>
        <div class="row">${dbs.map((d) => `<button class="btn" data-pick="${fmt.esc(d.name)}">${fmt.esc(d.name)} <span class="muted small">${fmt.esc(d.size)}</span></button>`).join('')}</div>
      </div>`;
      box.querySelectorAll('[data-pick]').forEach((b) => b.onclick = () => { S.database = b.dataset.pick; S.table = null; renderTab(); });
      return;
    }

    const tables = await CM.api.get(`/db/tables?engine=${S.engine}&database=${encodeURIComponent(S.database)}`);

    if (!S.table) {
      box.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row"><span class="muted small">当前数据库</span><b class="mono">${fmt.esc(S.database)}</b><span class="chip">${tables.length} 个对象</span></div>
          <div class="row"><button class="btn btn-sm" id="tb-switch">切换数据库</button><button class="btn btn-sm" id="tb-newtable">新建表(DDL)</button></div>
        </div>
      </div>
      <div class="card">
        <div class="card-title">表 / 视图 <div class="right"><input class="inp" id="tb-q" placeholder="搜索…" style="width:180px"></div></div>
        <div id="tb-list"></div>
      </div>`;
      const draw = (q) => {
        const rows = q ? tables.filter((t) => (t.name + t.schema).toLowerCase().includes(q.toLowerCase())) : tables;
        document.getElementById('tb-list').innerHTML = ui.table([
          { k: 'schema', label: 'Schema', cls: 'mono' },
          { k: 'name', label: '名称', cls: 'mono' },
          { k: 'kind', label: '类型', render: (v) => ui.badge(v, v === 'table' ? 'info' : 'purple') },
          { k: 'rows', label: '行数', cls: 'num', render: (v) => v == null ? '-' : Number(v).toLocaleString() },
          { k: 'size', label: '大小', cls: 'num' },
          { k: 'name', label: '操作', render: (v, r) => `<button class="btn btn-sm btn-primary" data-open="${fmt.esc(r.schema + '.' + r.name)}">打开</button>` },
        ], rows, { style: 'max-height:56vh' });
        document.querySelectorAll('[data-open]').forEach((b) => b.onclick = () => { S.table = b.dataset.open; S.schema = null; S.page = 0; S.orderBy = ''; S.where = ''; S.tableView = 'data'; renderTab(); });
      };
      draw('');
      document.getElementById('tb-q').oninput = (e) => draw(e.target.value);
      document.getElementById('tb-switch').onclick = () => { S.database = null; renderTab(); };
      document.getElementById('tb-newtable').onclick = () => {
        S.tab = 'console'; renderShell(ROOT); renderTab();
        setTimeout(() => { const ta = document.getElementById('sql-text'); if (ta) ta.value = 'CREATE TABLE new_table (\n  id bigserial PRIMARY KEY,\n  name text NOT NULL\n);'; }, 50);
      };
      return;
    }

    /* table detail */
    const sch = await CM.api.get(`/db/table/schema?engine=${S.engine}&database=${encodeURIComponent(S.database)}&table=${encodeURIComponent(S.table)}`);
    S.schema = sch;
    box.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row">
            <button class="btn btn-sm" id="td-back">${icon('chevronLeft', { size: 13 })} 返回表列表</button>
            <b class="mono">${fmt.esc(S.database)}.${fmt.esc(S.table)}</b>
            <span class="chip">${sch.columns.length} 列</span><span class="chip">${sch.indexes.length} 索引</span>
          </div>
          <div class="row range-btns">
            ${['data:数据', 'structure:结构', 'ddl:DDL'].map(([k, l]) => `<button class="btn btn-sm ${S.tableView === k ? 'active btn-primary' : ''}" data-tv="${k}">${l}</button>`).join('')}
          </div>
        </div>
      </div>
      <div id="td-body"></div>`;

    document.getElementById('td-back').onclick = () => { S.table = null; renderTab(); };
    box.querySelectorAll('[data-tv]').forEach((b) => b.onclick = () => { S.tableView = b.dataset.tv; renderTab(); });

    if (S.tableView === 'structure') renderStructure();
    else if (S.tableView === 'ddl') renderDdl();
    else await renderData();
  }

  function renderStructure() {
    const sch = S.schema;
    const typeList = isPg
      ? ['text', 'varchar(255)', 'integer', 'bigint', 'numeric(10,2)', 'boolean', 'timestamptz', 'date', 'jsonb', 'uuid', 'bytea', 'text[]']
      : isMy
        ? ['varchar(255)', 'text', 'int', 'bigint', 'decimal(10,2)', 'tinyint(1)', 'datetime', 'timestamp', 'date', 'json', 'blob', "enum('a','b')"]
        : isSqlite
          ? ['TEXT', 'INTEGER', 'REAL', 'BLOB', 'NUMERIC', 'BOOLEAN', 'DATETIME', 'DATE', 'VARCHAR(255)']
          : ['VARCHAR(255)', 'INTEGER', 'BIGINT', 'DECIMAL(10,2)', 'CHAR(10)', 'CLOB', 'DATE', 'TIMESTAMP', 'BLOB', 'XML'];
    const colOpts = sch.columns.map((c) => c.name);
    const idxNames = sch.indexes.map((i) => i.name);
    /* 可用的可视化 DDL 操作按引擎方言裁剪（服务端还会二次校验） */
    const DDL_PG_MY = [['column-add', '添加列'], ['column-modify', '修改列(类型/默认值/可空)'], ['column-rename', '重命名列'], ['column-drop', '删除列'], ['column-comment', '列注释'], ['index-create', '创建索引'], ['index-drop', '删除索引'], ['constraint-add-pk', '添加主键'], ['constraint-add-unique', '添加唯一约束'], ['constraint-add-fk', '添加外键'], ['constraint-drop', '删除约束'], ['table-comment', '表注释'], ['table-copy', '复制表']];
    const ddlOpts = isSqlite
      ? [['column-add', '添加列'], ['column-rename', '重命名列'], ['column-drop', '删除列'], ['index-create', '创建索引'], ['index-drop', '删除索引'], ['table-copy', '复制表']]
      : isDb2
        ? DDL_PG_MY
        : DDL_PG_MY.concat(isPg ? [['table-owner', '修改所有者'], ['sequence-restart', '重置序列']] : []);
    document.getElementById('td-body').innerHTML = `
      <div class="grid g2">
        <div class="card">
          <div class="card-title">列结构</div>
          ${ui.table([
            { k: 'pos', label: '#', cls: 'num' },
            { k: 'name', label: '列名', cls: 'mono', render: (v, r) => `${fmt.esc(v)} ${r.pk ? ui.badge('PK', 'ok') : ''}` },
            { k: 'type', label: '类型', cls: 'mono' },
            { k: 'nullable', label: '可空', render: (v) => String(v).toUpperCase() === 'YES' || v === 't' ? ui.badge('YES', 'gray') : ui.badge('NO', 'bad') },
            { k: 'default', label: '默认值', cls: 'mono', render: (v) => v ? fmt.esc(v) : '<span class="muted">-</span>' },
            { k: 'extra', label: '额外', cls: 'muted small' },
          ], sch.columns)}
        </div>
        <div class="card">
          <div class="card-title">索引</div>
          ${ui.table([
            { k: 'name', label: '索引名', cls: 'mono' },
            { k: 'cols', label: '列', cls: 'mono', render: (v, r) => fmt.esc(v || (r.def || '').replace(/.*\((.*)\).*/, '$1')) },
            { k: 'uniq', label: '唯一', render: (v) => v ? ui.badge('UNIQUE', 'ok') : '' },
            { k: 'type', label: '类型', cls: 'muted small' },
          ], sch.indexes)}
          <div class="muted small mt">主键: <span class="mono">${fmt.esc((sch.primaryKey || []).join(', ') || '(无)')}</span></div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">表结构操作（可视化 DDL）
          <div class="right"><span class="muted small" id="ddl-hint"></span><button class="btn btn-sm btn-primary" id="ddl-apply">执行 DDL</button></div>
        </div>
        <div class="grid g4" style="gap:10px">
          <label class="fld">操作
            <select class="inp" id="f-act">
              ${ddlOpts.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}
            </select>
          </label>
          <label class="fld">列名 / 目标列<input class="inp mono" id="f-col" list="f-collist" placeholder="column_name"></label>
          <label class="fld">类型<input class="inp mono" id="f-type" list="f-typelist" placeholder="text"></label>
          <label class="fld">新名称 / 索引或约束名 / 所有者<input class="inp mono" id="f-name" list="f-idxlist" placeholder="new_name"></label>
          <label class="fld">默认值 / 注释 / 序列值<input class="inp mono" id="f-val" placeholder="默认值或注释文本"></label>
          <label class="fld">列列表（逗号分隔）<input class="inp mono" id="f-cols" placeholder="col1, col2"></label>
          <label class="fld">可空
            <select class="inp" id="f-nullable"><option value="">不变</option><option value="yes">允许 NULL</option><option value="no">NOT NULL</option></select>
          </label>
          <label class="fld">引用表<input class="inp mono" id="f-ref" placeholder="other_table"></label>
          <label class="fld">引用列<input class="inp mono" id="f-refcols" placeholder="id"></label>
          <label class="fld">ON DELETE
            <select class="inp" id="f-ondel"><option value="">NO ACTION</option><option>CASCADE</option><option>SET NULL</option><option>RESTRICT</option><option>SET DEFAULT</option></select>
          </label>
          <label class="fld">索引方法
            <select class="inp" id="f-method"><option value="">btree</option><option>hash</option><option>gin</option><option>gist</option><option>brin</option></select>
          </label>
          <div class="fld">选项</div>
        </div>
        <div class="row mt">
          <label class="row small muted" style="gap:4px"><input type="checkbox" id="f-uniq"> 唯一索引</label>
          <label class="row small muted" style="gap:4px"><input type="checkbox" id="f-cascade"> 级联删除(CASCADE)</label>
          <label class="row small muted" style="gap:4px"><input type="checkbox" id="f-withdata"> 复制表时包含数据</label>
        </div>
        <datalist id="f-collist">${colOpts.map((c) => `<option>${fmt.esc(c)}</option>`).join('')}</datalist>
        <datalist id="f-typelist">${typeList.map((t) => `<option>${fmt.esc(t)}</option>`).join('')}</datalist>
        <datalist id="f-idxlist">${idxNames.map((t) => `<option>${fmt.esc(t)}</option>`).join('')}</datalist>
      </div>`;

    const v = (id) => (document.getElementById(id) || {}).value || '';
    const ck = (id) => !!(document.getElementById(id) || {}).checked;
    const hints = {
      'column-add': '填「列名 + 类型」，可选默认值/非空',
      'column-modify': '填「列名」(必填) + 新类型或默认值/可空',
      'column-rename': '填「列名 + 新名称」',
      'column-drop': '填「列名」',
      'column-comment': '填「列名 + 默认值/注释输入框」',
      'index-create': '填「索引或约束名 + 列列表」，可勾选唯一',
      'index-drop': '填索引名到「新名称/索引名」',
      'constraint-add-pk': '填「列列表」',
      'constraint-add-unique': '填「约束名 + 列列表」',
      'constraint-add-fk': '填「约束名 + 列列表 + 引用表 + 引用列」',
      'constraint-drop': '填约束名到「新名称/索引名」',
      'table-comment': '在「默认值/注释」中填表注释',
      'table-owner': '填新所有者到「新名称」',
      'table-copy': '填新表名到「新名称」',
      'sequence-restart': '填序列名到「列名」+ 新值到「默认值/序列值」',
    };
    const hintEl = document.getElementById('ddl-hint');
    const actEl = document.getElementById('f-act');
    const upd = () => { hintEl.textContent = hints[actEl.value] || ''; };
    actEl.onchange = upd; upd();

    document.getElementById('ddl-apply').onclick = async () => {
      const act = actEl.value;
      const params = {};
      let danger = false;
      if (act === 'column-add') { params.name = v('f-col'); params.type = v('f-type'); const nv = v('f-nullable'); if (nv === 'no') params.nullable = false; if (v('f-val')) params.default = v('f-val'); }
      else if (act === 'column-modify') { params.name = v('f-col'); if (v('f-type')) params.type = v('f-type'); if (v('f-val')) params.default = v('f-val'); const nv = v('f-nullable'); if (nv === 'no') params.nullable = false; else if (nv === 'yes') params.nullable = true; }
      else if (act === 'column-rename') { params.name = v('f-col'); params.newName = v('f-name'); }
      else if (act === 'column-drop') { params.name = v('f-col'); danger = true; }
      else if (act === 'column-comment') { params.name = v('f-col'); params.comment = v('f-val'); }
      else if (act === 'index-create') { params.name = v('f-name'); params.columns = v('f-cols'); params.unique = ck('f-uniq'); params.method = v('f-method'); }
      else if (act === 'index-drop') { params.name = v('f-name'); danger = true; }
      else if (act === 'constraint-add-pk') { params.columns = v('f-cols'); }
      else if (act === 'constraint-add-unique') { params.name = v('f-name'); params.columns = v('f-cols'); }
      else if (act === 'constraint-add-fk') { params.name = v('f-name'); params.columns = v('f-cols'); params.refTable = v('f-ref'); params.refColumns = v('f-refcols'); params.onDelete = v('f-ondel'); }
      else if (act === 'constraint-drop') { params.name = v('f-name'); params.cascade = ck('f-cascade'); danger = true; }
      else if (act === 'table-comment') { params.comment = v('f-val'); }
      else if (act === 'table-owner') { params.owner = v('f-name'); }
      else if (act === 'table-copy') { params.newName = v('f-name'); params.withData = ck('f-withdata'); }
      else if (act === 'sequence-restart') { params.name = v('f-col'); params.value = v('f-val'); }
      const okc = await modal.confirm('执行 DDL 操作', `对 <b class="mono">${fmt.esc(S.table)}</b> 执行 <b>${fmt.esc(actEl.options[actEl.selectedIndex].text)}</b>？${danger ? '<br><span style="color:#f87171">该操作可能造成数据丢失或结构破坏。</span>' : ''}`, { danger, okText: '执行' });
      if (!okc) return;
      const btn = document.getElementById('ddl-apply');
      ui.busy(btn, true);
      try { const r = await CM.api.post('/db/schema/action', { engine: S.engine, database: S.database, table: S.table, action: act, params }); modal.output('DDL 执行结果', r.output); renderTab(); }
      catch (e) { window.toast(e.message, 'err', 6000); if (e.output) modal.output('DDL 失败', e.output, { error: true }); }
      ui.busy(btn, false);
    };
  }

  function renderDdl() {
    document.getElementById('td-body').innerHTML = `
      <div class="card">
        <div class="card-title">DDL 定义（可编辑后执行）
          <div class="right">
            <button class="btn btn-sm" id="ddl-copy">复制</button>
            <button class="btn btn-sm btn-primary" id="ddl-run">执行 DDL</button>
          </div>
        </div>
        <textarea class="inp mono" id="ddl-text" style="min-height:320px">${fmt.esc(S.schema.ddl || '')}</textarea>
        <div class="row mt">
          <span class="muted small">快捷模板：</span>
          <button class="btn btn-sm" data-tpl="addcol">+ 加列</button>
          <button class="btn btn-sm" data-tpl="addidx">+ 索引</button>
          <button class="btn btn-sm" data-tpl="renametbl">改表名</button>
          <button class="btn btn-sm btn-danger" data-tpl="droptbl">删除表</button>
        </div>
      </div>`;
    const ta = document.getElementById('ddl-text');
    document.getElementById('ddl-copy').onclick = async () => { try { await navigator.clipboard.writeText(ta.value); window.toast('已复制', 'ok'); } catch (_) {} };
    document.getElementById('ddl-run').onclick = async () => {
      const okc = await modal.confirm('执行 DDL', `在 <b class="mono">${fmt.esc(S.database)}</b> 上执行以下 SQL？<pre class="pre mt">${fmt.esc(ta.value.slice(0, 600))}</pre>`, { danger: true, okText: '执行' });
      if (!okc) return;
      try { const r = await runAction('/db/console', { engine: S.engine, database: S.database, sql: ta.value }, { toast: false }); modal.output('DDL 执行结果', r.output); }
      catch (_) {}
    };
    const tname = S.table;
    const q = isMy ? (n) => '`' + n + '`' : (n) => '"' + n + '"';
    ta.parentElement.querySelectorAll('[data-tpl]').forEach((b) => b.onclick = () => {
      const t = b.dataset.tpl;
      if (t === 'addcol') ta.value = `ALTER TABLE ${q(tname)} ADD COLUMN new_col text;`;
      if (t === 'addidx') ta.value = `CREATE INDEX idx_${tname.replace(/\W/g, '_')}_newcol ON ${q(tname)} (new_col);`;
      if (t === 'renametbl') ta.value = `ALTER TABLE ${q(tname)} RENAME TO new_name;`;
      if (t === 'droptbl') ta.value = `DROP TABLE ${q(tname)};`;
    });
  }

  async function renderData() {
    const d = await CM.api.post('/db/table/data', {
      engine: S.engine, database: S.database, table: S.table,
      limit: S.pageSize, offset: S.page * S.pageSize, orderBy: S.orderBy, orderDir: S.orderDir, where: S.where,
    });
    const pkCols = (S.schema.primaryKey || []);
    const total = d.total;
    const maxPage = total != null ? Math.max(0, Math.ceil(total / S.pageSize) - 1) : null;
    const host = document.getElementById('td-body');
    host.innerHTML = `
      <div class="card">
        <div class="card-title">
          <span>数据浏览 ${total != null ? `<span class="chip">共 ${total.toLocaleString()} 行</span>` : ''} <span class="chip">${d.durationMs}ms</span></span>
          <div class="right">
            <input class="inp mono" id="dt-where" placeholder="WHERE 条件, 如 id > 10" value="${fmt.esc(S.where)}" style="width:220px">
            <button class="btn btn-sm" id="dt-filter">筛选</button>
            <button class="btn btn-sm" id="dt-clear">清除</button>
            <button class="btn btn-sm btn-ok" id="dt-insert">${icon('plus', { size: 12 })} 新增行</button>
            <button class="btn btn-sm" id="dt-reload">${icon('refresh', { size: 12 })} 刷新</button>
          </div>
        </div>
        <div id="dt-table"></div>
        <div class="row mt" style="gap:8px;border-top:1px solid var(--border);padding-top:12px">
          <span class="muted small">工具:</span>
          <button class="btn btn-sm" id="dt-count">${icon('scan', { size: 12 })} 精确计数</button>
          <button class="btn btn-sm" id="dt-bulkdel">${icon('trash', { size: 12 })} 批量删除</button>
          <button class="btn btn-sm" id="dt-export-csv">${icon('download', { size: 12 })} CSV</button>
          <button class="btn btn-sm" id="dt-export-json">${icon('download', { size: 12 })} JSON</button>
          <button class="btn btn-sm" id="dt-export-sql">${icon('download', { size: 12 })} SQL</button>
          <label class="btn btn-sm" style="cursor:pointer">${icon('upload', { size: 12 })} 导入 CSV<input type="file" id="dt-import" accept=".csv,text/csv" style="display:none"></label>
          <button class="btn btn-sm" id="dt-copy">${icon('copy', { size: 12 })} 复制表</button>
          <button class="btn btn-sm btn-danger" id="dt-truncate">${icon('minus', { size: 12 })} 清空表</button>
          <button class="btn btn-sm btn-danger" id="dt-droptable">${icon('trash', { size: 12 })} 删除表</button>
        </div>
        <div class="row between mt">
          <div class="row">
            <button class="btn btn-sm" id="dt-prev" ${S.page <= 0 ? 'disabled' : ''}>${icon('chevronLeft', { size: 13 })} 上一页</button>
            <span class="muted small mono">第 ${S.page + 1}${maxPage != null ? ' / ' + (maxPage + 1) : ''} 页</span>
            <button class="btn btn-sm" id="dt-next" ${maxPage != null && S.page >= maxPage ? 'disabled' : ''}>下一页 ${icon('chevronRight', { size: 13 })}</button>
            <select class="inp" id="dt-size" style="width:110px">
              ${[20, 50, 100, 200, 500].map((n) => `<option value="${n}" ${n === S.pageSize ? 'selected' : ''}>每页 ${n}</option>`).join('')}
            </select>
          </div>
          <div class="muted small">点击表头可排序 · 编辑/删除需要主键${pkCols.length ? '（' + fmt.esc(pkCols.join(',')) + '）' : '：<span style="color:#facc15">该表无主键，编辑需手写 WHERE</span>'}</div>
        </div>
      </div>`;

    const cols = d.columns.length ? d.columns : S.schema.columns.map((c) => c.name);
    const tableCols = cols.map((c) => ({
      k: c, label: c, cls: 'mono', render: (v) => {
        if (v === null || v === undefined) return '<span class="muted">NULL</span>';
        const s = String(v);
        return `<span title="${fmt.esc(s)}">${fmt.esc(s.length > 60 ? s.slice(0, 60) + '…' : s)}</span>`;
      },
    }));
    tableCols.push({
      k: cols[0], label: '操作', render: (v, r) => `<span class="row" style="gap:4px">
        <button class="btn btn-sm" data-edit='${fmt.esc(JSON.stringify(r))}'>编辑</button>
        <button class="btn btn-sm btn-danger" data-del='${fmt.esc(JSON.stringify(r))}'>删除</button></span>`,
    });
    document.getElementById('dt-table').innerHTML = ui.table(tableCols, d.rows, { style: 'max-height:52vh' });

    /* header click sorting */
    document.querySelectorAll('#dt-table th').forEach((th) => {
      const idx = [...th.parentElement.children].indexOf(th);
      const col = cols[idx];
      if (!col) return;
      th.style.cursor = 'pointer';
      th.onclick = () => {
        if (S.orderBy === col) S.orderDir = S.orderDir === 'asc' ? 'desc' : 'asc';
        else { S.orderBy = col; S.orderDir = 'asc'; }
        S.page = 0; renderData();
      };
      if (S.orderBy === col) th.innerHTML = fmt.esc(col) + ' ' + icon(S.orderDir === 'asc' ? 'chevronUp' : 'chevronDown', { size: 11 });
    });

    document.getElementById('dt-filter').onclick = () => { S.where = document.getElementById('dt-where').value.trim(); S.page = 0; renderData(); };
    document.getElementById('dt-clear').onclick = () => { S.where = ''; S.page = 0; renderData(); };
    document.getElementById('dt-reload').onclick = () => renderData();
    document.getElementById('dt-prev').onclick = () => { S.page = Math.max(0, S.page - 1); renderData(); };
    document.getElementById('dt-next').onclick = () => { S.page++; renderData(); };
    document.getElementById('dt-size').onchange = (e) => { S.pageSize = Number(e.target.value); S.page = 0; renderData(); };
    document.getElementById('dt-insert').onclick = () => rowEditor(null, d);

    /* ---------- tool row ---------- */
    const qi = (n) => isMy ? '`' + n + '`' : '"' + n + '"';
    const tableSql = S.table.split('.').map(qi).join('.');
    const baseSql = `SELECT * FROM ${tableSql}` + (S.where ? ' WHERE ' + S.where : '');

    document.getElementById('dt-count').onclick = async () => {
      const b = document.getElementById('dt-count');
      ui.busy(b, true);
      try { const r = await runAction('/db/count', { engine: S.engine, database: S.database, table: S.table, where: S.where }, { toast: false }); window.toast(r.output, 'ok');
        const chip = document.querySelector('#td-body .card-title .chip');
        if (chip) chip.textContent = '共 ' + r.total.toLocaleString() + ' 行';
      } catch (_) {}
      ui.busy(b, false);
    };
    document.getElementById('dt-bulkdel').onclick = async () => {
      const r = await modal.prompt('批量删除', [
        { name: 'where', label: 'WHERE 条件（必填，禁止分号）', value: S.where, placeholder: "如 created_at < '2024-01-01'" },
      ], { danger: true, okText: '下一步' });
      if (!r || !r.where) return;
      const okc = await modal.confirm('确认批量删除', `将删除 <b class="mono">${fmt.esc(S.table)}</b> 中满足条件的数据：<pre class="pre mt">${fmt.esc(r.where)}</pre>此操作不可恢复。`, { danger: true, okText: '删除' });
      if (!okc) return;
      try { const res = await runAction('/db/table/delete', { engine: S.engine, database: S.database, table: S.table, where: r.where }, { toast: false }); window.toast('已删除 ' + (res.affected ?? '') + ' 行', 'ok'); renderData(); }
      catch (_) {}
    };
    for (const fmt2 of ['csv', 'json', 'sql']) {
      const btn = document.getElementById('dt-export-' + fmt2);
      if (!btn) continue;
      btn.onclick = async () => {
        ui.busy(btn, true);
        const sql = fmt2 === 'sql' ? baseSql + (S.where ? '' : '') + ' LIMIT 5000' : baseSql;
        try {
          const r = await runAction('/db/export', { engine: S.engine, database: S.database, sql, format: fmt2, table: S.table }, { toast: false });
          window.toast(r.output + ' 可到「备份与恢复」页下载', 'ok', 6000);
          const url = `${CM.apiBase}/db/backup/download?name=${encodeURIComponent(r.name)}&token=${encodeURIComponent(CM.token)}`;
          const a = document.createElement('a'); a.href = url; a.download = r.name; a.click();
        } catch (_) {}
        ui.busy(btn, false);
      };
    }
    document.getElementById('dt-import').onchange = async (e) => {
      const f = e.target.files[0];
      if (!f) return;
      if (f.size > 60 * 1024 * 1024) return window.toast('CSV 超过 60MB', 'err');
      const text = await f.text();
      const opts = await modal.prompt('导入 CSV 到 ' + S.table, [
        { name: 'hasHeader', label: '首行是否为列名', type: 'select', options: ['是', '否'], value: '是' },
        { name: 'nullEmpty', label: '空字段视为 NULL', type: 'select', options: ['是', '否'], value: '是' },
      ], { okText: '开始导入' });
      if (!opts) return;
      ui.busy(e.target, false);
      window.toast('导入中，请稍候…', 'info');
      try {
        const r = await runAction('/db/import', {
          engine: S.engine, database: S.database, table: S.table, csv: text,
          hasHeader: opts.hasHeader === '是', nullEmpty: opts.nullEmpty === '是',
        }, { toast: false });
        modal.output('CSV 导入结果', r.output);
        renderData();
      } catch (_) {}
      e.target.value = '';
    };
    document.getElementById('dt-copy').onclick = async () => {
      const r = await modal.prompt('复制表', [
        { name: 'newName', label: '新表名', value: S.table.split('.').pop() + '_copy' },
        { name: 'withData', label: '同时复制数据', type: 'select', options: ['否', '是'], value: '否' },
      ]);
      if (!r || !r.newName) return;
      try { await runAction('/db/schema/action', { engine: S.engine, database: S.database, table: S.table, action: 'table-copy', params: { newName: r.newName, withData: r.withData === '是' } }); renderTab(); }
      catch (_) {}
    };
    document.getElementById('dt-truncate').onclick = async () => {
      const okc = await modal.confirm('清空表', `清空 <b class="mono">${fmt.esc(S.table)}</b> 的所有数据（TRUNCATE）？不可恢复！`, { danger: true, okText: '清空' });
      if (!okc) return;
      try { await runAction('/db/table/action', { engine: S.engine, database: S.database, table: S.table, action: 'truncate' }); renderData(); }
      catch (_) {}
    };
    document.getElementById('dt-droptable').onclick = async () => {
      const okc = await modal.confirm('删除表', `永久删除表 <b class="mono">${fmt.esc(S.table)}</b> 及全部数据？`, { danger: true, okText: '删除表' });
      if (!okc) return;
      try { await runAction('/db/table/action', { engine: S.engine, database: S.database, table: S.table, action: 'drop' }); S.table = null; renderTab(); }
      catch (_) {}
    };

    document.querySelectorAll('[data-edit]').forEach((b) => b.onclick = () => {
      const row = JSON.parse(b.dataset.edit);
      const pk = pkCols.length === 1 ? { column: pkCols[0], value: row[pkCols[0]] } : null;
      if (!pk && !pkCols.length) {
        /* composite pk or no pk: ask for WHERE */
        modal.prompt('编辑行 - 定位条件', [{ name: 'where', label: 'WHERE 条件（必填）', value: pkCols.map((c) => `${c} = ${row[c] === null ? 'NULL' : "'" + row[c] + "'"}`).join(' AND ') }]).then((r) => {
          if (r) rowEditor(row, d, null, r.where);
        });
        return;
      }
      rowEditor(row, d, pk);
    });
    document.querySelectorAll('[data-del]').forEach((b) => b.onclick = async () => {
      const row = JSON.parse(b.dataset.del);
      const pk = pkCols.length ? { column: pkCols[0], value: row[pkCols[0]] } : null;
      const okc = await modal.confirm('删除行', `删除该行？${pk ? `<br>定位: <span class="mono">${fmt.esc(pk.column)} = ${fmt.esc(String(pk.value))}</span>` : ''}`, { danger: true, okText: '删除' });
      if (!okc) return;
      const where = pk ? null : pkCols.map((c) => `${c} = ${row[c] === null ? 'NULL' : "'" + String(row[c]).replace(/'/g, "''") + "'"}`).join(' AND ');
      if (!pk && !where) return window.toast('无法定位该行（无主键）', 'err');
      try { const r = await runAction('/db/table/delete', { engine: S.engine, database: S.database, table: S.table, pk, where }, { toast: false }); window.toast('已删除 ' + (r.affected ?? '') + ' 行', 'ok'); renderData(); }
      catch (_) {}
    });
  }

  /* row insert / update modal */
  function rowEditor(row, dataRes, pk, forcedWhere) {
    const cols = S.schema.columns;
    const isUpdate = !!row;
    const fields = cols.filter((c) => {
      if (isUpdate) return true;
      const dflt = String(c.default || '');
      return !/nextval|now\(\)|CURRENT_TIMESTAMP|uuid_generate/i.test(dflt) || c.pk === false;
    });
    const body = `<div style="max-height:56vh;overflow:auto">` + fields.map((c) => {
      const v = row ? row[c.name] : null;
      const special = row && (pk && c.name === pk.column);
      return `<div class="row" style="margin-bottom:7px">
        <span class="mono small" style="width:170px">${fmt.esc(c.name)}<span class="muted"> ${fmt.esc(String(c.type).slice(0, 22))}${c.pk ? ' PK' : ''}</span></span>
        <input class="inp mono" name="f_${fmt.esc(c.name)}" value="${v === null || v === undefined ? '' : fmt.esc(String(v))}" style="flex:1" ${special ? 'disabled title="主键，不可修改"' : ''}>
        <label class="row small muted" style="gap:4px"><input type="checkbox" name="n_${fmt.esc(c.name)}" ${v === null && row ? 'checked' : ''}> NULL</label>
      </div>`;
    }).join('') + `</div>${forcedWhere ? `<div class="muted small mt">定位条件: <span class="mono">${fmt.esc(forcedWhere)}</span></div>` : ''}`;

    const mask = document.createElement('div');
    mask.className = 'modal-mask';
    mask.innerHTML = `<div class="modal" style="width:640px">
      <div class="modal-head">${isUpdate ? '编辑行' : '新增行'} · ${fmt.esc(S.database)}.${fmt.esc(S.table)}</div>
      <div class="modal-body">${body}</div>
      <div class="modal-foot">
        <button class="btn" data-a="cancel">取消</button>
        <button class="btn btn-primary" data-a="ok">${isUpdate ? '保存修改' : '插入'}</button>
      </div></div>`;
    document.getElementById('modal-root').appendChild(mask);
    const close = () => { mask.querySelector('.modal').classList.add('closing'); mask.classList.add('closing'); setTimeout(() => mask.remove(), 190); };
    mask.querySelector('[data-a=cancel]').onclick = close;
    mask.addEventListener('click', (e) => { if (e.target === mask) close(); });
    mask.querySelector('[data-a=ok]').onclick = async () => {
      const values = {};
      for (const c of fields) {
        const inp = mask.querySelector(`[name="f_${CSS.escape(c.name)}"]`);
        const nul = mask.querySelector(`[name="n_${CSS.escape(c.name)}"]`);
        if (!inp || inp.disabled) continue;
        if (nul && nul.checked) { values[c.name] = null; continue; }
        if (isUpdate) {
          const oldVal = row[c.name] === null || row[c.name] === undefined ? '' : String(row[c.name]);
          if (inp.value !== oldVal) values[c.name] = inp.value;
        } else if (inp.value !== '') {
          values[c.name] = inp.value;
        }
      }
      if (!Object.keys(values).length) { window.toast('没有修改任何字段', 'warn'); return close(); }
      try {
        const r = isUpdate
          ? await CM.api.post('/db/table/update', { engine: S.engine, database: S.database, table: S.table, pk, where: forcedWhere || (pk ? null : undefined), values })
          : await CM.api.post('/db/table/insert', { engine: S.engine, database: S.database, table: S.table, values });
        window.toast((isUpdate ? '更新 ' : '插入 ') + (r.affected ?? 1) + ' 行', 'ok');
        close(); renderData();
      } catch (e) { window.toast(e.message, 'err', 6000); }
    };
  }

  /* ================= 4) console ================= */
  function tabConsole(box) {
    const hist = JSON.parse(localStorage.getItem('cm_sql_history') || '[]');
    box.innerHTML = `
      <div class="card">
        <div class="card-title">SQL 控制台
          <div class="right">
            <select class="inp" id="sql-tpl" style="width:170px">${TEMPLATES.map(([l, v]) => `<option value="${fmt.esc(v)}">${fmt.esc(l)}</option>`).join('')}</select>
            <button class="btn btn-sm" id="sql-format">格式化</button>
            <button class="btn btn-sm" id="sql-dry">${icon('flask', { size: 13 })} 试运行(回滚)</button>
            <button class="btn btn-sm" id="sql-explain">EXPLAIN 分析</button>
            <button class="btn btn-sm" id="sql-plan">${icon('layers', { size: 13 })} 执行计划树</button>
            <label class="row small muted" style="gap:4px" title="ANALYZE 会真实执行该语句；仅在确知是只读查询时勾选">
              <input type="checkbox" id="sql-plan-analyze"> <span>ANALYZE</span>
            </label>
            <label class="row small muted" style="gap:4px"><input type="checkbox" id="sql-tx"> 事务</label>
            <button class="btn btn-sm btn-primary" id="sql-run">执行 (Ctrl+Enter)</button>
          </div>
        </div>
        <div class="row mb">
          <label class="fld">数据库
            <select class="inp mono" id="sql-db" style="width:200px"><option value="">(默认)</option></select>
          </label>
          <span class="muted small">以 ${fmt.esc(ENGINE_LABEL[S.engine])} 执行 · 结果支持多语句 · ${isPg ? '支持 $ 变量/函数体' : isMy ? 'MySQL 语法' : isSqlite ? 'SQLite 语法（支持 .tables/.schema 等点命令）' : 'Db2 SQL（以实例用户执行）'}</span>
        </div>
        <textarea class="inp mono" id="sql-text" spellcheck="false" style="min-height:220px" placeholder="SELECT * FROM table LIMIT 100;">${S.table ? `SELECT * FROM ${isMy ? '`' + S.table + '`' : '"' + S.table + '"\nLIMIT 100;'}`
          : isSqlite ? '.tables' : isDb2 ? 'SELECT * FROM SYSCAT.TABLES FETCH FIRST 20 ROWS ONLY;' : 'SELECT version();'}</textarea>
        <div id="sql-result" class="mt"></div>
      </div>
      <div class="card">
        <div class="card-title">历史记录 <div class="right"><button class="btn btn-sm" id="sql-hist-clear">清空</button></div></div>
        <div id="sql-hist"></div>
      </div>`;
    CM.api.get('/db/databases?engine=' + S.engine, 10000).then((dbs) => {
      const sel = document.getElementById('sql-db');
      sel.innerHTML = '<option value="">(默认)</option>' + dbs.filter((d) => !d.is_template).map((d) => `<option value="${fmt.esc(d.name)}" ${d.name === S.database ? 'selected' : ''}>${fmt.esc(d.name)}</option>`).join('');
    }).catch(() => {});

    const drawHist = () => {
      const h = JSON.parse(localStorage.getItem('cm_sql_history') || '[]');
      document.getElementById('sql-hist').innerHTML = h.length ? ui.table([
        { k: 'ts', label: '时间', cls: 'mono small' },
        { k: 'db', label: '库', cls: 'mono' },
        { k: 'sql', label: 'SQL', cls: 'wrap', render: (v) => `<a href="#" data-h='${fmt.esc(v)}' class="mono small">${fmt.esc(String(v).slice(0, 90))}</a>` },
      ], h) : '<div class="muted small">暂无历史</div>';
      document.querySelectorAll('[data-h]').forEach((a) => a.onclick = (e) => { e.preventDefault(); document.getElementById('sql-text').value = a.dataset.h; });
    };
    drawHist();
    document.getElementById('sql-hist-clear').onclick = () => { localStorage.removeItem('cm_sql_history'); drawHist(); };
    document.getElementById('sql-tpl').onchange = (e) => { if (e.target.value) document.getElementById('sql-text').value = e.target.value; };

    const run = async (explain, dryRun) => {
      const sql = document.getElementById('sql-text').value;
      if (!sql.trim()) return window.toast('请输入 SQL', 'warn');
      const btn = explain ? document.getElementById('sql-explain') : dryRun ? document.getElementById('sql-dry') : document.getElementById('sql-run');
      ui.busy(btn, true);
      const res = document.getElementById('sql-result');
      res.innerHTML = `<div class="skel line" style="width:40%"></div>`;
      try {
        const r = await CM.api.post('/db/console', {
          engine: S.engine, database: document.getElementById('sql-db').value || null,
          sql, explain: !!explain, transaction: document.getElementById('sql-tx').checked, dryRun: !!dryRun,
        });
        const meta = [`耗时 ${r.durationMs}ms`, r.columns && r.columns.length ? `${r.rows.length} 行` : '', r.dryRun ? '已回滚' : ''].filter(Boolean).join(' · ');
        const actions = `<span class="row" style="gap:6px">
            <button class="btn btn-sm" id="sql-copy">复制结果</button>
            ${r.columns && r.columns.length ? `<button class="btn btn-sm" data-exp="csv">导出 CSV</button><button class="btn btn-sm" data-exp="json">导出 JSON</button><button class="btn btn-sm" data-exp="sql">导出 SQL</button>` : ''}
          </span>`;
        res.innerHTML = `<div class="row between mb"><span class="muted small">${meta}</span>${actions}</div>` +
          (r.columns && r.columns.length ? ui.table(r.columns.map((c) => ({ k: c, label: c, cls: 'mono', render: (v) => v === null ? '<span class="muted">NULL</span>' : fmt.esc(String(v).slice(0, 80)) })), r.rows, { style: 'max-height:46vh' })
            : `<pre class="pre tall">${fmt.esc(r.output || '(无输出)')}</pre>`);
        const cp = document.getElementById('sql-copy');
        if (cp) cp.onclick = async () => { try { await navigator.clipboard.writeText(r.output || ''); window.toast('已复制', 'ok'); } catch (_) {} };
        res.querySelectorAll('[data-exp]').forEach((eb) => eb.onclick = async () => {
          ui.busy(eb, true);
          try {
            const ex = await runAction('/db/export', { engine: S.engine, database: document.getElementById('sql-db').value || null, sql, format: eb.dataset.exp, table: '' }, { toast: false });
            window.toast(ex.output + ' 可到「备份与恢复」页下载', 'ok', 6000);
            const a = document.createElement('a');
            a.href = `${CM.apiBase}/db/backup/download?name=${encodeURIComponent(ex.name)}&token=${encodeURIComponent(CM.token)}`;
            a.download = ex.name; a.click();
          } catch (_) {}
          ui.busy(eb, false);
        });
        if (!explain && !dryRun) {
          const h = JSON.parse(localStorage.getItem('cm_sql_history') || '[]');
          h.unshift({ ts: new Date().toLocaleString('zh-CN', { hour12: false }), db: document.getElementById('sql-db').value || '', sql: sql.slice(0, 500) });
          localStorage.setItem('cm_sql_history', JSON.stringify(h.slice(0, 30)));
          drawHist();
        }
      } catch (e) {
        res.innerHTML = `<div class="mb">${ui.badge('执行失败', 'bad')}</div><pre class="pre tall">${fmt.esc(e.message)}</pre>`;
      }
      ui.busy(btn, false);
    };
    document.getElementById('sql-run').onclick = () => run(false, false);
    document.getElementById('sql-explain').onclick = () => run(true, false);
    document.getElementById('sql-dry').onclick = () => run(false, true);
    /* 执行计划树：EXPLAIN (FORMAT JSON) 解析为可读树；ANALYZE 默认关闭（会真实执行语句） */
    document.getElementById('sql-plan').onclick = async (e) => {
      const sql = document.getElementById('sql-text').value;
      if (!sql.trim()) return window.toast('请输入 SQL', 'warn');
      const analyze = document.getElementById('sql-plan-analyze').checked;
      if (analyze) {
        const okc = await modal.confirm('执行计划（ANALYZE）',
          'ANALYZE 会<strong>真实执行</strong>该语句以采集实际行数与耗时。<br><span class="muted small">请确认这是只读查询；含写操作的语句请到 SQL 控制台执行。执行后端会再次校验语句是否只读。</span>',
          { danger: true, okText: '确认执行' });
        if (!okc) return;
      }
      ui.busy(e.currentTarget, true);
      const res = document.getElementById('sql-result');
      res.innerHTML = ui.skeleton(4);
      try {
        const r = await CM.api.post('/db/explain/tree', { engine: S.engine, database: document.getElementById('sql-db').value || null, sql, analyze });
        const nodes = r.nodes || [];
        const maxCost = Math.max.apply(null, nodes.map((n) => Number(n.cost || 0)).concat([1]));
        const maxTime = Math.max.apply(null, nodes.map((n) => Number(n.actual_time || 0)).concat([1]));
        const rows = nodes.map((n) => {
          const costPct = Number(n.cost || 0) / maxCost * 100;
          const timePct = Number(n.actual_time || 0) / maxTime * 100;
          const hot = costPct > 60 || timePct > 60;
          return '<tr>' +
            '<td style="padding-left:' + (8 + n.depth * 18) + 'px" class="mono">' + (n.depth ? '└─ ' : '') + fmt.esc(n.node) + '</td>' +
            '<td class="mono small">' + fmt.esc(n.relation || '') + '</td>' +
            '<td class="num">' + Number(n.cost || 0).toFixed(1) + '</td>' +
            '<td class="num">' + num(n.rows) + '</td>' +
            '<td class="num">' + (n.actual_rows == null ? '-' : num(n.actual_rows)) + '</td>' +
            '<td class="num" style="' + (hot ? 'color:#f87171;font-weight:700' : '') + '">' + (n.actual_time == null ? '-' : Number(n.actual_time).toFixed(2) + 'ms') + '</td>' +
            '<td style="width:90px">' + ui.bar(timePct, timePct > 60 ? 'bad' : timePct > 30 ? 'warn' : 'ok') + '</td>' +
            '<td class="mono small" style="white-space:normal">' + fmt.esc(String(n.filter || n.sort || '').slice(0, 60)) + '</td>' +
            '</tr>';
        }).join('');
        res.innerHTML = '<div class="row between mb"><span class="muted small">' + (analyze ? '实际执行分析' : '预估计划（未执行 ANALYZE）') +
          ' · 执行 ' + (r.execution_ms != null ? Number(r.execution_ms).toFixed(2) + ' ms' : '-') +
          ' · 规划 ' + (r.planning_ms != null ? Number(r.planning_ms).toFixed(2) + ' ms' : '-') + ' · ' + nodes.length + ' 个节点（红色=耗时最高）</span>' +
          '<span class="row" style="gap:6px"><button class="btn btn-sm" id="plan-raw">查看原始 JSON</button></span></div>' +
          '<div style="overflow:auto;max-height:52vh"><table class="tbl"><thead><tr><th>节点</th><th>对象</th><th class="num">代价</th><th class="num">预估行</th><th class="num">实际行</th><th class="num">实际耗时</th><th>占比</th><th>条件/排序</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
        const rb = document.getElementById('plan-raw');
        if (rb) rb.onclick = () => modal.output('执行计划 JSON', r.raw || '');
      } catch (e2) { res.innerHTML = '<pre class="pre tall">' + fmt.esc(e2.message) + '</pre>'; }
      ui.busy(e.currentTarget, false);
    };
    document.getElementById('sql-format').onclick = () => {
      const ta = document.getElementById('sql-text');
      const kw = ['select', 'from', 'where', 'group by', 'order by', 'having', 'limit', 'offset', 'insert into', 'values', 'update', 'set', 'delete from', 'create table', 'create index', 'create unique index', 'alter table', 'add column', 'drop column', 'drop table', 'truncate', 'returning', 'left join', 'right join', 'inner join', 'outer join', 'join', 'on', 'union all', 'union', 'grant', 'revoke', 'comment on', 'begin', 'commit', 'rollback', 'with'];
      let out = ta.value.replace(/\s+/g, ' ').trim();
      for (const k of kw.sort((a, b) => b.length - a.length)) {
        out = out.replace(new RegExp('\\b' + k.replace(/ /g, '\\s+') + '\\b', 'gi'), (m) => '\n' + m.toUpperCase());
      }
      ta.value = out.replace(/\n+/g, '\n').replace(/^\n/, '').trim();
      window.toast('已格式化（简单模式，注意字符串内关键词也会被换行）', 'info', 5000);
    };
    document.getElementById('sql-text').onkeydown = (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); run(false, false); } };
  }

  /* ================= 5) roles ================= */
  async function tabRoles(box) {
    if (isSqlite) {
      box.innerHTML = `<div class="card"><div class="card-title">用户与权限</div>
        <div class="muted">SQLite 是单文件嵌入式数据库，没有用户/角色概念，访问控制由操作系统的文件权限决定。</div></div>`;
      return;
    }
    if (isDb2) { return db2Roles(box); }
    const r = await CM.api.get('/db/roles?engine=' + S.engine);
    const roles = r.roles || [];
    const dbs = await CM.api.get('/db/databases?engine=' + S.engine, 10000).catch(() => []);
    box.innerHTML = `
      <div class="card">
        <div class="card-title">创建用户/角色</div>
        <div class="row">
          <label class="fld">名称<input class="inp mono" id="rl-name" style="width:160px" placeholder="appuser"></label>
          ${isMy ? `<label class="fld">Host<input class="inp mono" id="rl-host" value="localhost" style="width:140px"></label>` : ''}
          <label class="fld">密码<input type="password" class="inp" id="rl-pw" style="width:160px" placeholder="留空则无密码"></label>
          <label class="row small muted" style="gap:4px"><input type="checkbox" id="rl-super"> 超级用户</label>
          <button class="btn btn-primary" id="rl-create" style="align-self:flex-end">创建</button>
        </div>
      </div>

      <div class="card">
        <div class="card-title">角色列表 (${roles.length}) <div class="right"><button class="btn btn-sm" id="rl-refresh">刷新</button></div></div>
        ${ui.table([
          { k: 'name', label: '名称', cls: 'mono' },
          ...(isMy ? [{ k: 'host', label: 'Host', cls: 'mono' }, { k: 'has_password', label: '密码', render: (v) => v === '1' || v === 'true' || v === true ? ui.badge('已设置', 'ok') : ui.badge('无', 'gray') }] : []),
          ...(isPg ? [
            { k: 'super', label: '超级', render: (v) => v === 't' || v === true ? ui.badge('SUPER', 'bad') : '' },
            { k: 'can_login', label: '可登录', render: (v) => v === 't' || v === true ? ui.badge('LOGIN', 'ok') : ui.badge('NOLOGIN', 'gray') },
            { k: 'member_of', label: '所属角色组', cls: 'muted small' },
          ] : [{ k: 'super', label: '权限', render: (v) => v ? ui.badge('SUPER', 'bad') : '' }]),
          { k: 'name', label: '操作', render: (v) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm" data-pw="${fmt.esc(v)}">改密</button>
              <button class="btn btn-sm btn-ok" data-grant="${fmt.esc(v)}">授权库</button>
              <button class="btn btn-sm" data-tgrant="${fmt.esc(v)}">表级授权</button>
              ${isPg ? `<button class="btn btn-sm" data-alter="${fmt.esc(v)}">属性</button>` : `<button class="btn btn-sm" data-alter="${fmt.esc(v)}">账户</button>`}
              <button class="btn btn-sm btn-danger" data-droprole="${fmt.esc(v)}">删除</button></span>` },
        ], roles, { style: 'max-height:44vh' })}
      </div>

      ${r.grants && r.grants.length ? `<div class="card"><div class="card-title">当前用户权限 (SHOW GRANTS)</div><pre class="pre" style="max-height:220px">${fmt.esc(r.grants.join('\n'))}</pre></div>` : ''}
      ${r.privileges && r.privileges.length ? `<div class="card"><div class="card-title">表级授权概览</div>${ui.table([
        { k: 'grantee', label: '角色', cls: 'mono' }, { k: 'schema', label: 'Schema', cls: 'mono' }, { k: 'privilege', label: '权限', cls: 'mono' },
      ], r.privileges, { style: 'max-height:300px' })}</div>` : ''}`;

    document.getElementById('rl-refresh').onclick = () => renderTab();
    document.getElementById('rl-create').onclick = async () => {
      const name = document.getElementById('rl-name').value.trim();
      if (!name) return window.toast('请输入名称', 'warn');
      const privileges = document.getElementById('rl-super').checked ? ['superuser'] : [];
      const password = document.getElementById('rl-pw').value;
      const host = document.getElementById('rl-host') ? document.getElementById('rl-host').value : '';
      const okc = await modal.confirm('创建角色', `<b class="mono">${fmt.esc(name)}</b>${privileges.length ? ' （超级用户）' : ''}`);
      if (!okc) return;
      runAction('/db/role', { engine: S.engine, action: 'create', name, password, privileges, host }, { reload: () => renderTab() }).catch(() => {});
    };
    box.querySelectorAll('[data-pw]').forEach((b) => b.onclick = async () => {
      const r2 = await modal.prompt('修改密码 - ' + b.dataset.pw, [{ name: 'password', label: '新密码', type: 'password' }], { okText: '修改' });
      if (!r2 || !r2.password) return;
      runAction('/db/role', { engine: S.engine, action: 'password', name: b.dataset.pw, password: r2.password }).catch(() => {});
    });
    box.querySelectorAll('[data-grant]').forEach((b) => b.onclick = async () => {
      const r2 = await modal.prompt('授予数据库权限', [{ name: 'database', label: '数据库', type: 'select', options: dbs.map((d) => d.name) }], { okText: '授权' });
      if (!r2 || !r2.database) return;
      runAction('/db/role', { engine: S.engine, action: 'grant-db', name: b.dataset.grant, database: r2.database }, { reload: () => renderTab() }).catch(() => {});
    });
    box.querySelectorAll('[data-droprole]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('删除角色', `删除 <b class="mono">${fmt.esc(b.dataset.droprole)}</b>？该角色拥有的对象可能变为无主。`, { danger: true, okText: '删除' });
      if (!okc) return;
      runAction('/db/role', { engine: S.engine, action: 'drop', name: b.dataset.droprole }, { reload: () => renderTab() }).catch(() => {});
    });

    /* table-level privileges */
    box.querySelectorAll('[data-tgrant]').forEach((b) => b.onclick = async () => {
      const name = b.dataset.tgrant;
      const r1 = await modal.prompt('表级授权 - ' + name, [
        { name: 'database', label: '数据库', type: 'select', options: dbs.map((d) => d.name), value: S.database || (dbs[0] || {}).name },
      ], { okText: '下一步' });
      if (!r1 || !r1.database) return;
      let tables = [];
      try { tables = (await CM.api.get(`/db/tables?engine=${S.engine}&database=${encodeURIComponent(r1.database)}`)).map((t) => t.schema + '.' + t.name); } catch (_) {}
      const r2 = await modal.prompt('选择表与权限', [
        { name: 'table', label: '表', type: 'select', options: tables.length ? tables : [''], value: tables[0] || '' },
        { name: 'privs', label: '权限', type: 'select', options: ['SELECT', 'SELECT,INSERT', 'SELECT,INSERT,UPDATE,DELETE', 'ALL'], value: 'SELECT' },
        { name: 'op', label: '操作', type: 'select', options: ['授予', '回收'], value: '授予' },
      ], { okText: '执行' });
      if (!r2 || !r2.table) return;
      const privileges = r2.privs.split(',').map((x) => x.trim()).filter(Boolean);
      runAction('/db/role', {
        engine: S.engine, action: r2.op === '授予' ? 'grant-table' : 'revoke-table', name,
        database: r1.database, params: { table: r2.table, privileges },
      }).catch(() => {});
    });

    /* role attributes (PG) / account (MySQL) */
    box.querySelectorAll('[data-alter]').forEach((b) => b.onclick = async () => {
      const name = b.dataset.alter;
      if (isPg) {
        const r = await modal.prompt('角色属性 - ' + name, [
          { name: 'login', label: 'LOGIN（可登录）', type: 'select', options: ['不变', 'LOGIN', 'NOLOGIN'], value: '不变' },
          { name: 'superuser', label: 'SUPERUSER', type: 'select', options: ['不变', 'SUPERUSER', 'NOSUPERUSER'], value: '不变' },
          { name: 'createdb', label: 'CREATEDB', type: 'select', options: ['不变', 'CREATEDB', 'NOCREATEDB'], value: '不变' },
          { name: 'createrole', label: 'CREATEROLE', type: 'select', options: ['不变', 'CREATEROLE', 'NOCREATEROLE'], value: '不变' },
          { name: 'connlimit', label: '连接数限制（空=不变，-1=无限）', value: '' },
          { name: 'validUntil', label: '密码有效期 (如 2030-01-01，空=不变)', value: '' },
        ]);
        if (!r) return;
        const privileges = [];
        if (r.login === 'LOGIN') privileges.push('login'); else if (r.login === 'NOLOGIN') privileges.push('nologin');
        if (r.superuser === 'SUPERUSER') privileges.push('superuser'); else if (r.superuser === 'NOSUPERUSER') privileges.push('nosuperuser');
        if (r.createdb === 'CREATEDB') privileges.push('createdb'); else if (r.createdb === 'NOCREATEDB') privileges.push('nocreatedb');
        if (r.createrole === 'CREATEROLE') privileges.push('createrole'); else if (r.createrole === 'NOCREATEROLE') privileges.push('nocreaterole');
        runAction('/db/role', { engine: 'postgres', action: 'alter', name, privileges, params: { connlimit: r.connlimit, validUntil: r.validUntil } }, { reload: () => renderTab() }).catch(() => {});
      } else {
        const r = await modal.prompt('账户管理 - ' + name, [
          { name: 'op', label: '操作', type: 'select', options: ['ACCOUNT LOCK(锁定)', 'ACCOUNT UNLOCK(解锁)', 'PASSWORD EXPIRE(强制改密)', '显示权限清单'], value: 'ACCOUNT LOCK(锁定)' },
        ], { danger: true, okText: '执行' });
        if (!r) return;
        if (r.op.startsWith('显示')) {
          try { const res = await CM.api.post('/db/role', { engine: 'mysql', action: 'grants-list', name }); modal.output('权限清单 - ' + name, res.output); } catch (e) { window.toast(e.message, 'err'); }
          return;
        }
        const act = r.op.startsWith('ACCOUNT LOCK') ? 'lock' : r.op.startsWith('ACCOUNT UNLOCK') ? 'unlock' : 'expire';
        runAction('/db/role', { engine: 'mysql', action: act, name }).catch(() => {});
      }
    });
  }

  /* ================= 6) backups ================= */
  async function tabBackups(box) {
    const [bks, dbs] = await Promise.all([
      CM.api.get('/db/backups?engine=' + S.engine),
      CM.api.get('/db/databases?engine=' + S.engine, 10000).catch(() => []),
    ]);
    const kind = kindOf(S.engine);
    const sqlMode = kind === 'sql';
    const modeOpt = !sqlMode ? '' : `
          <label class="fld">内容
            <select class="inp" id="bk-mode" style="width:200px">
              <option value="full">完整（结构 + 数据）</option>
              <option value="schema">仅结构（不含数据）</option>
              <option value="data">仅数据（不含建表）</option>
            </select>
          </label>
          <label class="fld">格式
            <select class="inp" id="bk-format" style="width:220px">
              <option value="plain">SQL 文本 + gzip（通用，可编辑）</option>
              ${isPg ? '<option value="custom">PostgreSQL 自定义格式 -Fc（更快恢复）</option>' : ''}
            </select>
          </label>
          <label class="fld">排除表（逗号分隔，可选）
            <input class="inp mono" id="bk-exclude" placeholder="logs, tmp_table" style="width:220px">
          </label>`;
    box.innerHTML = `
      <div class="card">
        <div class="card-title">新建备份 / 导出
          <div class="right"><button class="btn btn-sm btn-primary" id="bk-create">${icon('download', { size: 13 })} 开始备份</button></div>
        </div>
        <div class="row">
          <label class="fld">数据库
            <select class="inp mono" id="bk-db" style="width:260px">
              ${kind === 'kv' ? '<option value="ALL">当前库 (db0-db15) 全量 RDB</option>' : '<option value="ALL">全部数据库（服务器级全量）</option>'}
              ${dbs.filter((d) => !d.is_template).map((d) => `<option value="${fmt.esc(d.name)}" ${d.name === S.database ? 'selected' : ''}>${fmt.esc(d.name)} (${fmt.esc(d.size)})</option>`).join('')}
            </select>
          </label>
          ${modeOpt}
          <button class="btn btn-sm" id="bk-cron" style="align-self:flex-end" title="创建每天凌晨 3 点的自动备份任务">${icon('clock', { size: 13 })} 每日自动备份</button>
        </div>
        <div class="muted small mt">备份保存在服务器 <code>${fmt.esc(bks.dir)}</code>，可直接下载。恢复时可勾选自动创建目标库。
        ${kind === 'kv' ? '· Redis 会执行 BGSAVE 并复制 RDB 文件；恢复会覆盖数据目录，需重启 Redis 生效。' : kind === 'doc' ? '· MongoDB 使用 mongodump 生成 gzip 归档（需 mongodump）。' : isSqlite ? '· SQLite 生成 SQL 文本 + gzip，可直接恢复到新文件。' : ''}</div>
      </div>

      <div class="card">
        <div class="card-title">导入 SQL 文件（用于恢复）
          <div class="right"><input type="file" id="bk-file" class="inp" style="padding:5px"></div>
        </div>
        <div class="muted small">${kind === 'doc' ? '可上传 .dump（mongodump 归档）' : kind === 'kv' ? '可上传 .rdb 文件' : '支持 .sql / .sql.gz / .dump（PG 自定义格式）'}，上传后可在下方列表中选择文件恢复。</div>
      </div>

      <div class="card">
        <div class="card-title">备份文件 (${bks.items.length})
          <div class="right">
            <button class="btn btn-sm" id="bk-cleanup">${icon('trash', { size: 13 })} 清理旧备份</button>
            <button class="btn btn-sm" id="bk-refresh">${icon('refresh', { size: 13 })} 刷新</button>
          </div>
        </div>
        ${ui.table([
          { k: 'name', label: '文件名', cls: 'mono wrap' },
          { k: 'engine', label: '类型', render: (v, r) => r.export ? ui.badge('导出', 'purple') : r.uploaded ? ui.badge('上传', 'gray') : ui.badge(v || '-', 'info') },
          { k: 'mode', label: '内容', render: (v) => ({ full: ui.badge('完整', 'ok'), schema: ui.badge('仅结构', 'info'), data: ui.badge('仅数据', 'warn'), custom: ui.badge('自定义格式', 'purple'), rdb: ui.badge('RDB 快照', 'purple') }[v] || (v ? fmt.esc(v) : '')) },
          { k: 'size', label: '大小', cls: 'num', render: (v) => fmt.bytes(v) },
          { k: 'mtime', label: '时间', cls: 'mono small' },
          { k: 'name', label: '操作', render: (v, r) => `<span class="row" style="gap:4px">
              <a class="btn btn-sm" href="${CM.apiBase}/db/backup/download?name=${encodeURIComponent(v)}&token=${encodeURIComponent(CM.token)}">${icon('download', { size: 11 })} 下载</a>
              ${r.export ? '' : `<button class="btn btn-sm btn-ok" data-restore="${fmt.esc(v)}">${icon('import', { size: 11 })} 恢复</button>`}
              <button class="btn btn-sm btn-danger" data-delfile="${fmt.esc(v)}">${icon('trash', { size: 11 })} 删除</button></span>` },
        ], bks.items, { style: 'max-height:42vh' })}
      </div>`;

    document.getElementById('bk-refresh').onclick = () => renderTab();
    document.getElementById('bk-cleanup').onclick = async () => {
      const r = await modal.prompt('清理旧备份', [{ name: 'keep', label: '保留最近 N 个', value: '10' }], { danger: true, okText: '清理' });
      if (!r || !r.keep) return;
      try { await runAction('/db/backup/cleanup', { keep: Number(r.keep) }); renderTab(); } catch (_) {}
    };
    document.getElementById('bk-create').onclick = async () => {
      const database = document.getElementById('bk-db').value;
      const fmtEl = document.getElementById('bk-format');
      const modeEl = document.getElementById('bk-mode');
      const excEl = document.getElementById('bk-exclude');
      const format = fmtEl ? fmtEl.value : 'plain';
      const mode = modeEl ? modeEl.value : 'full';
      const exclude = excEl ? excEl.value.trim() : '';
      const modeLabel = { full: '完整', schema: '仅结构', data: '仅数据' }[mode];
      const fmtLabel = kind === 'kv' ? 'RDB 快照' : kind === 'doc' ? 'gzip 归档' : format === 'custom' ? 'PG 自定义格式' : 'SQL + gzip';
      const okc = await modal.confirm('创建备份', `备份 <b class="mono">${fmt.esc(database)}</b>（${modeLabel} / ${fmtLabel}）？大库可能需要数分钟。`, { okText: '开始备份' });
      if (!okc) return;
      const b = document.getElementById('bk-create');
      ui.busy(b, true);
      try { const r = await runAction('/db/backup', { engine: S.engine, database, format, mode, exclude }, { toast: false }); window.toast(r.output, 'ok', 6000); renderTab(); }
      catch (_) { ui.busy(b, false); }
    };
    document.getElementById('bk-cron').onclick = async () => {
      /* 备份目录由服务端下发（CM.me.env.paths），前端不硬编码任何路径 */
      const dir = CM.me && CM.me.env && CM.me.env.paths && CM.me.env.paths.dbBackups;
      if (!dir) return window.toast('未能获取服务器数据目录，请刷新页面后重试', 'warn');
      let cmd;
      if (kind === 'kv') cmd = `D=$(redis-cli CONFIG GET dir | tail -1); F=$(redis-cli CONFIG GET dbfilename | tail -1); redis-cli BGSAVE; sleep 5; cp "$D/$F" ${dir}/db-redis-ALL-$(date +\\%Y-\\%m-\\%d).rdb`;
      else if (kind === 'doc') cmd = `mongodump --archive=${dir}/db-mongo-ALL-$(date +\\%Y-\\%m-\\%d).dump --gzip`;
      else if (isSqlite) cmd = `find /opt /srv /var/www -maxdepth 4 -name '*.db' -exec sh -c 'gzip -c "$1" > ${dir}/db-sqlite-$(basename "$1")-$(date +\\%Y-\\%m-\\%d).sql.gz' _ {} \\; 2>/dev/null || true`;
      else if (isDb2) cmd = `su - db2inst1 -c 'db2 backup database SAMPLE online to ${dir}'`;
      else if (isPg) cmd = `su - postgres -c 'pg_dumpall' | gzip -1 > ${dir}/db-postgres-ALL-$(date +\\%Y-\\%m-\\%d).sql.gz`;
      else cmd = `mysqldump --single-transaction --routines --triggers --all-databases | gzip -1 > ${dir}/db-mysql-ALL-$(date +\\%Y-\\%m-\\%d).sql.gz`;
      const okc = await modal.confirm('创建每日自动备份', `将添加 root 定时任务，每天 03:00 执行：<pre class="pre mt">${fmt.esc(cmd)}</pre>`, { okText: '创建任务' });
      if (!okc) return;
      runAction('/cron/action', { action: 'add', params: { name: 'dbbackup-' + S.engine, schedule: '0 3 * * *', command: cmd } }).catch(() => {});
    };
    document.getElementById('bk-file').onchange = async (e) => {
      const f = e.target.files[0];
      if (!f) return;
      if (f.size > 500 * 1024 * 1024) return window.toast('文件超过 500MB 限制', 'err');
      const el = document.getElementById('bk-file');
      el.disabled = true;
      try {
        const res = await fetch(CM.apiBase + '/db/upload?name=' + encodeURIComponent(f.name), {
          method: 'POST',
          headers: { 'Authorization': 'Bearer ' + CM.token, 'Content-Type': 'application/octet-stream' },
          body: f,
        });
        const j = await res.json();
        if (!res.ok) throw new Error(j.error || 'HTTP ' + res.status);
        window.toast('上传成功: ' + j.output, 'ok');
        renderTab();
      } catch (err) { window.toast('上传失败: ' + err.message, 'err'); }
      el.disabled = false;
    };
    box.querySelectorAll('[data-restore]').forEach((b) => b.onclick = async () => {
      const file = b.dataset.restore;
      if (kind === 'kv') {
        const okc = await modal.confirm('恢复 Redis', `用 <b class="mono">${fmt.esc(file)}</b> 覆盖 Redis 当前数据？<br><span style="color:#f87171">会覆盖数据目录中的 dump.rdb，重启 Redis 后生效，现有数据将丢失。</span>`, { danger: true, okText: '开始恢复' });
        if (!okc) return;
        ui.busy(b, true);
        try { const r = await runAction('/db/restore', { engine: S.engine, file }, { toast: false }); modal.output('恢复结果', r.output); }
        catch (_) {}
        ui.busy(b, false);
        return;
      }
      if (kind === 'doc') {
        const r = await modal.prompt('恢复 MongoDB 归档', [
          { name: 'database', label: '限定数据库（留空=恢复全部）', value: S.database || '', placeholder: 'mydb' },
        ], { okText: '下一步' });
        if (!r) return;
        ui.busy(b, true);
        try { const out = await runAction('/db/restore', { engine: S.engine, file, database: r.database || '' }, { toast: false }); modal.output('恢复结果', out.output); }
        catch (_) {}
        ui.busy(b, false);
        return;
      }
      const target = await modal.prompt('恢复备份到目标数据库', [
        { name: 'database', label: '目标数据库（可输入新名称，配合“自动创建”即可）', value: (dbs.find((d) => !d.is_template) || {}).name || '', placeholder: 'mydb' },
      ], { okText: '下一步' });
      if (!target || !target.database) return;
      const mode = await modal.prompt('恢复方式', [
        { name: 'mode', label: '导入方式', type: 'select', options: ['合并导入（保留现有对象）', '清空后恢复（--clean，可能丢数据）'], value: '合并导入（保留现有对象）' },
        { name: 'target', label: '目标库不存在时', type: 'select', options: ['自动创建', '报错退出'], value: '自动创建' },
      ], { danger: true, okText: '开始恢复' });
      if (!mode) return;
      const dropFirst = String(mode.mode).startsWith('清空');
      ui.busy(b, true);
      try {
        const r = await runAction('/db/restore', { engine: S.engine, database: target.database, file, dropFirst, createIfMissing: mode.target === '自动创建' }, { toast: false });
        modal.output('恢复结果', r.output);
      } catch (_) {}
      ui.busy(b, false);
    });
    box.querySelectorAll('[data-delfile]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('删除备份文件', `删除 <b class="mono">${fmt.esc(b.dataset.delfile)}</b>？`, { danger: true, okText: '删除' });
      if (!okc) return;
      runAction('/db/backup/delete', { name: b.dataset.delfile }, { reload: () => renderTab() }).catch(() => {});
    });
  }
  /* ================= 7) insights ================= */
  function renderSections(host, sections) {
    host.innerHTML = sections.map((s) => `
      <div class="card">
        <div class="card-title">${fmt.esc(s.title)}
          <div class="right">${s.rows.length ? `<span class="chip">${s.rows.length} 行</span>` : ''}${s.note ? `<span class="muted small">${fmt.esc(s.note)}</span>` : ''}</div>
        </div>
        ${s.error ? `<div class="mb">${ui.badge('不可用', 'gray')} <span class="muted small">${fmt.esc(s.error)}</span></div>`
          : (s.rows.length
            ? ui.table(s.columns.map((c) => ({
              k: c, label: c, cls: 'mono',
              render: (v) => v === null || v === undefined ? '<span class="muted">NULL</span>' : fmt.esc(String(v).slice(0, 120)),
            })), s.rows, { style: 'max-height:38vh' })
            : '<div class="muted small">无数据</div>')}
      </div>`).join('');
  }

  async function tabInsights(box) {
    const dbs = await CM.api.get('/db/databases?engine=' + S.engine, 10000).catch(() => []);
    const usable = dbs.filter((d) => !d.is_template);
    const cur = S.database && usable.find((d) => d.name === S.database) ? S.database : (usable[0] || {}).name;
    box.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row">
            <span class="muted small">分析数据库</span>
            <select class="inp mono" id="in-db" style="width:200px">${usable.map((d) => `<option ${d.name === cur ? 'selected' : ''}>${fmt.esc(d.name)}</option>`).join('')}</select>
            <span class="badge info">${fmt.esc(ENGINE_LABEL[S.engine])}</span>
          </div>
          <div class="row"><button class="btn btn-sm" id="in-refresh">刷新分析</button></div>
        </div>
      </div>
      <div id="in-stmts"></div>
      <div id="in-sections"><div class="card">${ui.skeleton(6)}</div></div>`;

    const load = async () => {
      const database = document.getElementById('in-db').value;
      const sectionsHost = document.getElementById('in-sections');
      sectionsHost.innerHTML = `<div class="card">${ui.skeleton(6)}</div>`;
      const [ins, st] = await Promise.all([
        CM.api.get(`/db/insights?engine=${S.engine}&database=${encodeURIComponent(database)}`),
        CM.api.get(`/db/statements?engine=${S.engine}&database=${encodeURIComponent(database)}`).catch(() => ({ available: false })),
      ]);
      const stHost = document.getElementById('in-stmts');
      if (!st.available) {
        stHost.innerHTML = `<div class="card">
          <div class="card-title">慢查询 / 热点语句 <span class="badge warn">未启用</span></div>
          <div class="muted small mb">${fmt.esc(st.hint || '统计功能未启用')}</div>
          ${isPg ? `<button class="btn btn-sm btn-ok" id="in-enable-pgss">启用 pg_stat_statements 扩展</button>` : ''}
        </div>`;
        const b = document.getElementById('in-enable-pgss');
        if (b) b.onclick = async () => {
          const okc = await modal.confirm('安装扩展', '执行 <code>CREATE EXTENSION pg_stat_statements</code>？<br>启用后还需将其加入 shared_preload_libraries 并重启数据库才会开始统计。', { okText: '安装' });
          if (!okc) return;
          try {
            const r = await runAction('/db/extension', { engine: 'postgres', name: 'pg_stat_statements' }, { toast: false });
            modal.output('扩展安装', r.output);
          } catch (_) {}
        };
      } else {
        stHost.innerHTML = `<div class="card">
          <div class="card-title">慢查询 / 热点语句 <span class="chip">${st.rows.length} 条</span></div>
          ${ui.table((st.columns || []).map((c) => ({ k: c, label: c, cls: 'mono', render: (v) => v === null ? '<span class="muted">NULL</span>' : fmt.esc(String(v).slice(0, 140)) })), st.rows, { style: 'max-height:36vh' })}
        </div>`;
      }
      renderSections(sectionsHost, ins.sections);
    };
    document.getElementById('in-refresh').onclick = load;
    document.getElementById('in-db').onchange = load;
    await load();
  }

  /* ================= 8) objects ================= */
  async function tabObjects(box) {
    const dbs = await CM.api.get('/db/databases?engine=' + S.engine, 10000).catch(() => []);
    const usable = dbs.filter((d) => !d.is_template);
    const cur = S.database && usable.find((d) => d.name === S.database) ? S.database : (usable[0] || {}).name;
    box.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row">
            <span class="muted small">数据库</span>
            <select class="inp mono" id="ob-db" style="width:200px">${usable.map((d) => `<option ${d.name === cur ? 'selected' : ''}>${fmt.esc(d.name)}</option>`).join('')}</select>
          </div>
          <div class="row">
            ${(isPg || isDb2) ? `<input class="inp mono" id="ob-schema" placeholder="新模式名" style="width:150px"><button class="btn btn-sm btn-ok" id="ob-schema-add">${icon('plus', { size: 12 })} 建模式</button>` : ''}
            ${isPg ? `<input class="inp mono" id="ob-ext" placeholder="扩展名 如 pgcrypto" style="width:170px"><button class="btn btn-sm btn-ok" id="ob-ext-install">安装扩展</button>` : ''}
            <button class="btn btn-sm" id="ob-refresh">刷新</button>
          </div>
        </div>
      </div>
      <div id="ob-schemas"></div>
      <div id="ob-sections"><div class="card">${ui.skeleton(6)}</div></div>`;

    const load = async () => {
      const database = document.getElementById('ob-db').value;
      const host = document.getElementById('ob-sections');
      host.innerHTML = `<div class="card">${ui.skeleton(6)}</div>`;
      const [d, sc] = await Promise.all([
        CM.api.get(`/db/objects?engine=${S.engine}&database=${encodeURIComponent(database)}`),
        CM.api.get(`/db/schemas?engine=${S.engine}&database=${encodeURIComponent(database)}`).catch(() => ({ rows: [] })),
      ]);
      const rows = (sc.rows || []).map((x) => Object.assign({}, x, {
        _user: x.user_schema === true || x.user_schema === 't' ? ui.badge('用户', 'ok') : ui.badge('系统', 'gray'),
        _name: fmt.esc(x.name),
      }));
      const sb = document.getElementById('ob-schemas');
      if (sb) {
        sb.innerHTML = rows.length ? `<div class="card"><div class="card-title">模式 / 命名空间 (${rows.length})</div>${ui.table([
          { k: '_name', label: '模式', cls: 'mono' },
          { k: '_user', label: '类型' },
          { k: 'owner', label: '所有者', cls: 'mono' },
          { k: 'tables', label: '表', cls: 'num' },
          { k: 'views', label: '视图', cls: 'num', render: (v) => v == null ? '-' : v },
          { k: 'functions', label: '函数', cls: 'num', render: (v) => v == null ? '-' : v },
          { k: 'size', label: '大小', cls: 'num', render: (v) => fmt.esc(String(v || '-')) },
          { k: 'name', label: '', render: (v, x) => (x.user_schema === true || x.user_schema === 't') && !['public'].includes(v) ? `<button class="btn btn-sm btn-danger" data-scdrop="${fmt.esc(v)}">删除</button>` : '' },
        ], rows, { style: 'max-height:280px' })}</div>` : '';
        sb.querySelectorAll('[data-scdrop]').forEach((b) => (b.onclick = async () => {
          const okc = await modal.confirm('删除模式', `删除模式 <b class="mono">${fmt.esc(b.dataset.scdrop)}</b> 及其全部对象（CASCADE）？`, { danger: true, okText: '删除' });
          if (!okc) return;
          runAction('/db/schema/manage', { engine: S.engine, database, action: 'drop', name: b.dataset.scdrop }, { reload: () => load() }).catch(() => {});
        }));
      }
      renderSections(host, d.sections);
    };
    const addSchema = document.getElementById('ob-schema-add');
    if (addSchema) addSchema.onclick = () => {
      const n = document.getElementById('ob-schema').value.trim();
      if (!n) return window.toast('请输入模式名', 'warn');
      runAction('/db/schema/manage', { engine: S.engine, database: document.getElementById('ob-db').value, action: 'create', name: n }, { reload: () => load() }).catch(() => {});
    };
    document.getElementById('ob-refresh').onclick = load;
    document.getElementById('ob-db').onchange = load;
    const ei = document.getElementById('ob-ext-install');
    if (ei) ei.onclick = async () => {
      const name = document.getElementById('ob-ext').value.trim();
      if (!name) return window.toast('请输入扩展名', 'warn');
      const okc = await modal.confirm('安装扩展', `执行 <code>CREATE EXTENSION IF NOT EXISTS ${fmt.esc(name)}</code>？`);
      if (!okc) return;
      ui.busy(ei, true);
      try { const r = await runAction('/db/extension', { engine: 'postgres', name }, { toast: false }); modal.output('扩展安装', r.output); load(); }
      catch (_) {}
      ui.busy(ei, false);
    };
    await load();
  }
  /* ================= 9) 维护与高级 ================= */
  async function tabAdvanced(box) {
    const eng = S.engine;
    const dbs = (await CM.api.get('/db/databases?engine=' + eng, 10000).catch(() => [])).filter((d) => !d.is_template);
    const dbNames = dbs.map((d) => d.name);
    const cur = S.database && dbNames.indexOf(S.database) >= 0 ? S.database : (dbNames[0] || '');
    const opt = (n) => `<option ${n === cur ? 'selected' : ''}>${fmt.esc(n)}</option>`;

    box.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row"><span class="muted small">工作数据库</span>
            <select class="inp mono" id="adv-db" style="width:220px">${dbNames.map(opt).join('')}</select>
            <span class="badge info">${fmt.esc(ENGINE_LABEL[eng])}</span>
          </div>
          <div class="row"><button class="btn btn-sm" id="adv-refresh">${icon('refresh', { size: 13 })} 重新加载全部</button></div>
        </div>
      </div>
      <div id="adv-bulk"></div>
      <div id="adv-storage"></div>
      <div class="grid g2"><div id="adv-copy"></div><div id="adv-plan"></div></div>
      <div class="grid g2"><div id="adv-missing"></div><div id="adv-sessions"></div></div>
      <div id="adv-search"></div>
      <div id="adv-objects"></div>
      <div id="adv-monitor"></div>`;

    const tablesOf = (db) => CM.api.get(`/db/tables?engine=${eng}&database=${encodeURIComponent(db)}`).catch(() => []);
    const tblNames = (tl) => tl.filter((t) => t.kind === 'table' || t.kind === 'partitioned' || t.kind === 'partitioned table').map((t) => t.schema + '.' + t.name);

    /* ---------- 批量表操作 ---------- */
    async function renderBulk() {
      const db = box.querySelector('#adv-db').value;
      const tl = await tablesOf(db);
      const names = tblNames(tl);
      const bulkOpts = isPg
        ? [['analyze', 'ANALYZE（更新统计）'], ['vacuum', 'VACUUM（回收空间）'], ['vacuum-full', 'VACUUM FULL（重写表）'], ['reindex', 'REINDEX / 重建索引'], ['truncate', 'TRUNCATE（清空数据）'], ['drop', 'DROP TABLE（删除表）']]
        : isSqlite
          ? [['analyze', 'ANALYZE（更新统计）'], ['vacuum', 'VACUUM（重建文件）'], ['reindex', 'REINDEX（重建索引）'], ['check', '完整性检查'], ['truncate', '清空数据'], ['drop', 'DROP TABLE（删除表）']]
          : isDb2
            ? [['analyze', 'RUNSTATS（更新统计）'], ['vacuum', 'REORG（重组表）'], ['reindex', 'REORG（重建索引）'], ['check', 'REORGCHK（校验）'], ['truncate', 'TRUNCATE（清空数据）'], ['drop', 'DROP TABLE（删除表）']]
            : [['analyze', 'ANALYZE（更新统计）'], ['optimize', 'OPTIMIZE（MySQL）'], ['check', 'CHECK（校验）'], ['repair', 'REPAIR（修复）'], ['truncate', 'TRUNCATE（清空数据）'], ['drop', 'DROP TABLE（删除表）']];
      box.querySelector('#adv-bulk').innerHTML = `<div class="card">
        <div class="card-title">批量表操作 <div class="right">
          <button class="btn btn-sm" id="adv-bulk-all">全选</button>
          <button class="btn btn-sm" id="adv-bulk-none">清空</button>
          <span class="muted small" id="adv-bulk-count">0 / ${names.length}</span>
        </div></div>
        <div class="row mb">
          <select class="inp" id="adv-bulk-action" style="width:230px">
            ${bulkOpts.map(([v, l]) => `<option value="${v}">${l}</option>`).join('')}
          </select>
          <button class="btn btn-sm btn-primary" id="adv-bulk-run">执行</button>
          <span class="muted small">多选 · 危险操作二次确认 · 一次最多 200 张</span>
        </div>
        <div style="max-height:280px;overflow:auto;border:1px solid var(--border);border-radius:3px;padding:9px">
          ${names.length ? `<div class="grid g3" style="gap:6px">${names.map((n) => `<label class="row small" style="gap:5px"><input type="checkbox" class="adv-tbl" value="${fmt.esc(n)}"><span class="mono">${fmt.esc(n)}</span></label>`).join('')}</div>` : '<div class="muted small">该库没有表</div>'}
        </div>
      </div>`;
      const cnt = () => {
        const n = box.querySelectorAll('.adv-tbl:checked').length;
        box.querySelector('#adv-bulk-count').textContent = n + ' / ' + names.length;
        return n;
      };
      box.querySelectorAll('.adv-tbl').forEach((c) => (c.onchange = cnt));
      box.querySelector('#adv-bulk-all').onclick = () => { box.querySelectorAll('.adv-tbl').forEach((c) => (c.checked = true)); cnt(); };
      box.querySelector('#adv-bulk-none').onclick = () => { box.querySelectorAll('.adv-tbl').forEach((c) => (c.checked = false)); cnt(); };
      box.querySelector('#adv-bulk-run').onclick = async (e) => {
        const tables = [...box.querySelectorAll('.adv-tbl:checked')].map((c) => c.value);
        if (!tables.length) return window.toast('请先勾选表', 'warn');
        const action = box.querySelector('#adv-bulk-action').value;
        const danger = ['truncate', 'drop', 'vacuum-full', 'repair'].indexOf(action) >= 0;
        const okc = await modal.confirm('批量操作', `对 <b>${tables.length}</b> 张表执行 <b>${fmt.esc(action)}</b>？${danger ? '<br><span style="color:#f87171">该操作不可逆或影响较大。</span>' : ''}`, { danger, okText: '执行' });
        if (!okc) return;
        ui.busy(e.currentTarget, true);
        try { const r = await runAction('/db/table/bulk', { engine: eng, database: db, tables, action }, { toast: false }); modal.output('批量操作结果', r.output); renderBulk(); }
        catch (_) {}
        ui.busy(e.currentTarget, false);
      };
    }

    /* ---------- 表存储与活动明细 ---------- */
    async function renderStorage() {
      const db = box.querySelector('#adv-db').value;
      const tl = await tablesOf(db);
      const names = tblNames(tl);
      box.querySelector('#adv-storage').innerHTML = `<div class="card">
        <div class="card-title">表存储与活动明细</div>
        <div class="row mb">
          <select class="inp mono" id="adv-st-tbl" style="width:300px">${names.map((n) => `<option>${fmt.esc(n)}</option>`).join('')}</select>
          <button class="btn btn-sm btn-primary" id="adv-st-run">分析</button>
          <button class="btn btn-sm" id="adv-st-backup">${icon('download', { size: 12 })} 单表备份</button>
        </div>
        <div id="adv-st-body"><div class="muted small">选择表后点击「分析」</div></div>
      </div>`;
      const run = async () => {
        const t = box.querySelector('#adv-st-tbl').value;
        if (!t) return;
        const body = box.querySelector('#adv-st-body');
        body.innerHTML = ui.skeleton(5);
        try {
          const r = await CM.api.get(`/db/table/storage?engine=${eng}&database=${encodeURIComponent(db)}&table=${encodeURIComponent(t)}`);
          const sz = r.size || {}, ac = r.activity || {};
          const kv = Object.entries(sz).filter(([k]) => k !== 'total_bytes' && k !== 'index_bytes_pretty').map(([k, v]) => `<dt>${fmt.esc(k)}</dt><dd>${fmt.esc(String(v))}</dd>`).join('');
          const akv = Object.entries(ac).map(([k, v]) => `<dt>${fmt.esc(k)}</dt><dd>${fmt.esc(String(v))}</dd>`).join('');
          body.innerHTML = `<div class="grid g2">
            <div><div class="muted small mb">存储占用</div><dl class="kv">${kv}</dl></div>
            <div><div class="muted small mb">活动统计</div><dl class="kv">${akv}</dl></div>
          </div>
          <div class="muted small mt mb">索引（${(r.indexes || []).length}）</div>
          ${ui.table([
            { k: 'name', label: '索引', cls: 'mono' },
            { k: 'scans', label: '扫描次数', cls: 'num', render: (v) => v == null ? '-' : v },
            { k: 'tup_read', label: '读取行', cls: 'num', render: (v) => v == null ? '-' : v },
            { k: 'size', label: '大小', cls: 'num' },
            { k: 'def', label: '定义', cls: 'mono wrap', render: (v) => fmt.esc(String(v || '').slice(0, 90)) },
          ], r.indexes || [], { style: 'max-height:240px' })}`;
        } catch (e) { body.innerHTML = `<pre class="pre">${fmt.esc(e.message)}</pre>`; }
      };
      box.querySelector('#adv-st-run').onclick = run;
      box.querySelector('#adv-st-backup').onclick = async () => {
        const t = box.querySelector('#adv-st-tbl').value;
        if (!t) return;
        const okc = await modal.confirm('单表备份', `备份 <b class="mono">${fmt.esc(t)}</b> 为独立的 SQL 压缩包？`);
        if (!okc) return;
        ui.busy(box.querySelector('#adv-st-backup'), true);
        try { const r = await runAction('/db/table/backup', { engine: eng, database: db, table: t }, { toast: false }); window.toast(r.output, 'ok', 6000); }
        catch (_) {}
        ui.busy(box.querySelector('#adv-st-backup'), false);
      };
      if (names.length) run();
    }

    /* ---------- 跨库复制表 ---------- */
    async function renderCopy() {
      const db = box.querySelector('#adv-db').value;
      const tl = await tablesOf(db);
      const names = tblNames(tl);
      box.querySelector('#adv-copy').innerHTML = `<div class="card">
        <div class="card-title">跨库复制表</div>
        <div class="row mb">
          <select class="inp mono" id="adv-cp-tbl" style="width:250px">${names.map((n) => `<option>${fmt.esc(n)}</option>`).join('')}</select>
          <span class="muted small">→</span>
          <select class="inp mono" id="adv-cp-db" style="width:180px">${dbNames.map((n) => `<option ${n !== db ? '' : ''}>${fmt.esc(n)}</option>`).join('')}</select>
        </div>
        <div class="row mb">
          <input class="inp mono" id="adv-cp-name" placeholder="目标表名（留空同名）" style="width:220px">
          <label class="row small muted" style="gap:4px"><input type="checkbox" id="adv-cp-data" checked> 包含数据</label>
          <button class="btn btn-sm btn-primary" id="adv-cp-run">开始复制</button>
        </div>
        <div class="muted small">结构 + 数据整表复制到另一个数据库（PG 走 pg_dump 管道，MySQL 走 mysqldump 管道）。</div>
      </div>`;
      box.querySelector('#adv-cp-run').onclick = async (e) => {
        const table = box.querySelector('#adv-cp-tbl').value;
        const targetDb = box.querySelector('#adv-cp-db').value;
        const newName = box.querySelector('#adv-cp-name').value.trim();
        const withData = box.querySelector('#adv-cp-data').checked;
        if (!table) return window.toast('请选择表', 'warn');
        if (targetDb === db && !newName) return window.toast('目标库与源库相同，请填写新的表名', 'warn');
        const okc = await modal.confirm('跨库复制', `将 <b class="mono">${fmt.esc(db)}.${fmt.esc(table)}</b> 复制到 <b class="mono">${fmt.esc(targetDb)}</b>${withData ? '（含数据）' : '（仅结构）'}？`);
        if (!okc) return;
        ui.busy(e.currentTarget, true);
        try { const r = await runAction('/db/table/copy-db', { engine: eng, database: db, table, targetDb, newName, withData }, { toast: false }); modal.output('跨库复制结果', r.output); }
        catch (_) {}
        ui.busy(e.currentTarget, false);
      };
    }

    /* ---------- 维护计划 ---------- */
    function renderPlan() {
      const acts = isPg
        ? [['vacuum-analyze', 'VACUUM ANALYZE 全部库'], ['vacuum-full', 'VACUUM FULL 全部库'], ['reindex', 'REINDEX 全部库'], ['backup-all', '全库备份 (pg_dumpall)']]
        : isSqlite
          ? [['backup-all', '全库备份 (.db -> sql.gz)']]
          : isDb2
            ? [['analyze', 'RUNSTATS 全库统计'], ['backup-all', '全库备份 (db2 backup)']]
            : [['optimize', 'mysqlcheck -o 优化'], ['analyze', 'mysqlcheck -a 统计'], ['check', 'mysqlcheck -c 校验'], ['backup-all', '全库备份 (mysqldump)']];
      box.querySelector('#adv-plan').innerHTML = `<div class="card">
        <div class="card-title">定时维护计划</div>
        <div class="row mb">
          <select class="inp" id="adv-pl-act" style="width:250px">${acts.map((a) => `<option value="${a[0]}">${fmt.esc(a[1])}</option>`).join('')}</select>
          <input class="inp mono" id="adv-pl-sch" value="0 4 * * *" style="width:150px">
          <button class="btn btn-sm btn-primary" id="adv-pl-run">创建计划</button>
        </div>
        <div class="muted small">写入系统定时任务（<code>/etc/cron.d/cm-dbmaint-*</code>）。示例：<code>0 4 * * *</code> 每天 4 点 · <code>0 4 * * 0</code> 每周日 4 点。可在「定时任务」页查看/删除。</div>
      </div>`;
      box.querySelector('#adv-pl-run').onclick = async (e) => {
        const action = box.querySelector('#adv-pl-act').value;
        const schedule = box.querySelector('#adv-pl-sch').value.trim();
        const okc = await modal.confirm('创建维护计划', `按 <b class="mono">${fmt.esc(schedule)}</b> 执行 <b>${fmt.esc(action)}</b>？`);
        if (!okc) return;
        ui.busy(e.currentTarget, true);
        try { await runAction('/db/maintenance/plan', { engine: eng, action, schedule }, { showOutput: true, title: '维护计划' }); }
        catch (_) {}
        ui.busy(e.currentTarget, false);
      };
    }

    /* ---------- 缺失索引建议 ---------- */
    async function renderMissing() {
      const db = box.querySelector('#adv-db').value;
      box.querySelector('#adv-missing').innerHTML = `<div class="card"><div class="card-title">缺失索引建议</div><div id="adv-mi-body">${ui.skeleton(4)}</div></div>`;
      const body = box.querySelector('#adv-mi-body');
      try {
        const r = await CM.api.get(`/db/missing-indexes?engine=${eng}&database=${encodeURIComponent(db)}`);
        if (isPg) {
          body.innerHTML = (r.note ? `<div class="muted small mb">${fmt.esc(r.note)}</div>` : '') + ((r.rows || []).length ? ui.table([
            { k: 'name', label: '表', cls: 'mono', render: (v, x) => fmt.esc(x.schema + '.' + v) },
            { k: 'seq_scan', label: '顺序扫描', cls: 'num' },
            { k: 'idx_scan', label: '索引扫描', cls: 'num' },
            { k: 'rows', label: '行数', cls: 'num' },
            { k: 'avg_rows_per_scan', label: '每次扫描行数', cls: 'num', render: (v) => v == null ? '-' : num(v) },
            { k: 'size', label: '大小', cls: 'num' },
            { k: 'candidate_cols', label: '候选列', cls: 'mono wrap', render: (v) => fmt.esc(String(v || '').slice(0, 70)) },
            { k: 'name', label: '', render: (v, x) => `<button class="btn btn-sm" data-mkidx="${fmt.esc(x.schema + '.' + v)}">+ 建索引</button>` },
          ], (r.rows || []).slice(0, 20), { style: 'max-height:280px' }) : '<div class="muted small">没有发现明显缺失索引的表 </div>');
          body.querySelectorAll('[data-mkidx]').forEach((b) => (b.onclick = async () => {
            const r2 = await modal.prompt('创建索引', [
              { name: 'name', label: '索引名', value: 'idx_' + String(b.dataset.mkidx).replace(/\W+/g, '_') },
              { name: 'columns', label: '列（逗号分隔）', placeholder: 'col1, col2' },
            ], { okText: '创建' });
            if (!r2 || !r2.name || !r2.columns) return;
            runAction('/db/schema/action', { engine: eng, database: db, table: b.dataset.mkidx, action: 'index-create', params: { name: r2.name, columns: r2.columns } }).catch(() => {});
          }));
        } else {
          body.innerHTML = (r.note ? `<div class="muted small mb">${fmt.esc(r.note)}</div>` : '') + ((r.unused || []).length ? ui.table([
            { k: 'object_name', label: '表', cls: 'mono' }, { k: 'index_name', label: '未使用索引', cls: 'mono' },
          ], r.unused.slice(0, 20)) : `<div class="muted small">${isMy ? '无数据（需 MySQL 8 + sys schema）' : '该引擎不提供自动缺失索引分析，请参考上方「性能洞察」。'}</div>`);
        }
      } catch (e) { body.innerHTML = `<pre class="pre">${fmt.esc(e.message)}</pre>`; }
    }

    /* ---------- 会话批量清理 ---------- */
    function renderSessions() {
      box.querySelector('#adv-sessions').innerHTML = `<div class="card">
        <div class="card-title">会话批量清理</div>
        <div class="row mb">
          <label class="fld">状态<select class="inp" id="adv-ss-state"><option value="">全部</option><option value="idle">空闲 (idle)</option><option value="active">活动 (active)</option>${isPg ? '<option value="idle_in_transaction">空闲事务</option>' : ''}</select></label>
          <label class="fld">数据库<select class="inp" id="adv-ss-db"><option value="">全部</option>${dbNames.map((n) => `<option>${fmt.esc(n)}</option>`).join('')}</select></label>
          <label class="fld">用户<input class="inp mono" id="adv-ss-user" placeholder="可选" style="width:120px"></label>
          <label class="fld">时长 > 秒<input class="inp mono" id="adv-ss-sec" placeholder="0" style="width:90px"></label>
        </div>
        <div class="row">
          <button class="btn btn-sm" id="adv-ss-view">查看匹配会话</button>
          <button class="btn btn-sm btn-danger" id="adv-ss-run">终止匹配会话</button>
        </div>
        <div class="muted small mt">出于安全考虑，必须至少指定 <b>数据库</b> / <b>用户</b> / <b>时长</b> 之一；单次匹配上限 200 个会话。</div>
        <div id="adv-ss-out" class="mt"></div>
      </div>`;
      const filter = () => ({ state: box.querySelector('#adv-ss-state').value, database: box.querySelector('#adv-ss-db').value, user: box.querySelector('#adv-ss-user').value.trim(), minSeconds: box.querySelector('#adv-ss-sec').value });
      box.querySelector('#adv-ss-view').onclick = async () => {
        const f = filter();
        try {
          const r = await CM.api.get(`/db/status?engine=${eng}`);
          const procs = (r.processes || []).filter((p) => {
            if (f.database && p.db !== f.database) return false;
            if (f.user && p.user !== f.user) return false;
            if (f.state === 'idle' && !/idle/i.test(p.state || '')) return false;
            if (f.state === 'active' && !/active/i.test(p.state || '')) return false;
            if (f.minSeconds && Number(p.seconds || 0) < Number(f.minSeconds)) return false;
            return true;
          });
          box.querySelector('#adv-ss-out').innerHTML = procs.length ? ui.table([
            { k: 'pid', label: 'PID', cls: 'num' }, { k: 'user', label: '用户', cls: 'mono' }, { k: 'db', label: '库', cls: 'mono' },
            { k: 'state', label: '状态' }, { k: 'seconds', label: '时长(s)', cls: 'num' },
            { k: 'query', label: 'SQL', cls: 'wrap', render: (v) => fmt.esc(String(v || '').slice(0, 70)) },
          ], procs.slice(0, 60), { style: 'max-height:220px' }) : '<div class="muted small">没有匹配的会话</div>';
        } catch (e) { window.toast(e.message, 'err'); }
      };
      box.querySelector('#adv-ss-run').onclick = async (e) => {
        const f = filter();
        const okc = await modal.confirm('终止会话', `终止所有匹配的会话？<br><span class="muted small">筛选：状态=${fmt.esc(f.state || '全部')} · 库=${fmt.esc(f.database || '全部')} · 用户=${fmt.esc(f.user || '全部')}${f.minSeconds ? ' · 时长>' + fmt.esc(f.minSeconds) + 's' : ''}</span>`, { danger: true, okText: '终止' });
        if (!okc) return;
        ui.busy(e.currentTarget, true);
        try { const r = await runAction('/db/session/kill', { engine: eng, action: 'kill', filter: f }, { toast: false }); window.toast(r.output, 'ok'); }
        catch (_) {}
        ui.busy(e.currentTarget, false);
      };
    }

    /* ---------- 数据值搜索 ---------- */
    function renderSearch() {
      box.querySelector('#adv-search').innerHTML = `<div class="card">
        <div class="card-title">全库数据搜索</div>
        <div class="row mb">
          <input class="inp mono" id="adv-sv-val" placeholder="要搜索的值，如错误码 / 订单号" style="width:280px">
          <input class="inp mono" id="adv-sv-tbl" placeholder="限定表（留空=全库，最多 60 张表）" style="width:300px">
          <button class="btn btn-sm btn-primary" id="adv-sv-run">搜索</button>
        </div>
        <div id="adv-sv-out"><div class="muted small">在文本/字符串列中查找指定值（区分大小写按数据库默认）</div></div>
      </div>`;
      box.querySelector('#adv-sv-run').onclick = async (e) => {
        const value = box.querySelector('#adv-sv-val').value.trim();
        if (!value) return window.toast('请输入搜索值', 'warn');
        ui.busy(e.currentTarget, true);
        const out = box.querySelector('#adv-sv-out');
        out.innerHTML = ui.skeleton(4);
        try {
          const r = await CM.api.post('/db/table/search', { engine: eng, database: box.querySelector('#adv-db').value, value, table: box.querySelector('#adv-sv-tbl').value.trim() || null, limit: 20 });
          const hitsHtml = r.hits.map((h) => {
            const head = '<div class="mb"><div class="mono small">' + fmt.esc(h.table) + ' <span class="badge warn">' + h.matched + ' 行匹配</span></div>';
            const tbl = ui.table(h.columns.map((c) => ({ k: c, label: c, cls: 'mono', render: (v) => (v === null ? '<span class="muted">NULL</span>' : fmt.esc(String(v).slice(0, 60))) })), h.rows, { style: 'max-height:180px' });
            return head + tbl + '</div>';
          }).join('');
          /* 故障与截断必须显式提示，不能让用户误以为"库里没有" */
          const warns = []
            .concat((r.errors || []).map((x) => '表 ' + x.table + ' 查询失败：' + fmt.esc(x.error)))
            .concat((r.skipped || []).map((x) => (x.table || '') + ' 未扫描：' + fmt.esc(x.reason)));
          out.innerHTML = '<div class="muted small mb">扫描 ' + r.scanned + ' 张表 · 命中 ' + r.hits.length + ' 张</div>' +
            (warns.length ? '<div class="small mb" style="border:1px solid rgba(234,179,8,.4);background:rgba(234,179,8,.07);color:var(--warn);border-radius:3px;padding:7px 9px">' + warns.slice(0, 6).join('<br>') + (warns.length > 6 ? '<br>…另有 ' + (warns.length - 6) + ' 条' : '') + '</div>' : '') +
            (r.hits.length ? hitsHtml : (warns.length ? '' : '<div class="muted small">未找到匹配</div>'));
        } catch (e2) { out.innerHTML = `<pre class="pre">${fmt.esc(e2.message)}</pre>`; }
        ui.busy(e.currentTarget, false);
      };
    }

    /* ---------- 序列 / 视图 / 触发器 ---------- */
    function renderObjects() {
      if (!isPg) { box.querySelector('#adv-objects').innerHTML = ''; return; }
      box.querySelector('#adv-objects').innerHTML = `<div class="grid g3">
        <div class="card">
          <div class="card-title">序列管理</div>
          <div class="row mb">
            <select class="inp" id="adv-sq-act" style="width:130px"><option value="create">创建</option><option value="nextval">取下一个值</option><option value="setval">设值</option><option value="alter">修改</option><option value="drop">删除</option></select>
            <input class="inp mono" id="adv-sq-name" placeholder="名称(可带模式)" style="flex:1">
          </div>
          <div class="grid g2" style="gap:8px">
            <input class="inp mono" id="adv-sq-start" placeholder="起始值">
            <input class="inp mono" id="adv-sq-inc" placeholder="步长">
            <input class="inp mono" id="adv-sq-restart" placeholder="RESTART 值">
            <input class="inp mono" id="adv-sq-value" placeholder="setval 值">
          </div>
          <button class="btn btn-sm btn-primary mt" id="adv-sq-run">执行</button>
        </div>
        <div class="card">
          <div class="card-title">视图管理</div>
          <input class="inp mono mb" id="adv-vw-name" placeholder="视图名(可带模式)">
          <textarea class="inp mono" id="adv-vw-sql" placeholder="SELECT ..." style="min-height:70px"></textarea>
          <div class="row mt"><button class="btn btn-sm btn-primary" id="adv-vw-create">创建/替换</button><button class="btn btn-sm btn-danger" id="adv-vw-drop">删除</button></div>
        </div>
        <div class="card">
          <div class="card-title">触发器管理</div>
          <input class="inp mono mb" id="adv-tg-name" placeholder="触发器名">
          <input class="inp mono mb" id="adv-tg-table" placeholder="表名">
          <div class="row mb">
            <select class="inp" id="adv-tg-timing" style="width:110px"><option>AFTER</option><option>BEFORE</option></select>
            <select class="inp" id="adv-tg-event" style="width:110px"><option>INSERT</option><option>UPDATE</option><option>DELETE</option></select>
            <select class="inp" id="adv-tg-foreach" style="width:110px"><option>ROW</option><option>STATEMENT</option></select>
          </div>
          <input class="inp mono mb" id="adv-tg-func" placeholder="触发函数，如 audit.log_change()">
          <div class="row"><button class="btn btn-sm btn-primary" id="adv-tg-create">创建</button><button class="btn btn-sm" id="adv-tg-enable">启用</button><button class="btn btn-sm" id="adv-tg-disable">禁用</button><button class="btn btn-sm btn-danger" id="adv-tg-drop">删除</button></div>
        </div>
      </div>`;
      const db = () => box.querySelector('#adv-db').value;
      box.querySelector('#adv-sq-run').onclick = () => runAction('/db/sequence', {
        engine: eng, database: db(), action: box.querySelector('#adv-sq-act').value,
        params: { name: box.querySelector('#adv-sq-name').value.trim(), start: box.querySelector('#adv-sq-start').value, increment: box.querySelector('#adv-sq-inc').value, restart: box.querySelector('#adv-sq-restart').value, value: box.querySelector('#adv-sq-value').value },
      }, { showOutput: true, title: '序列操作' }).catch(() => {});
      box.querySelector('#adv-vw-create').onclick = () => runAction('/db/object', { engine: eng, database: db(), kind: 'view', action: 'create', params: { name: box.querySelector('#adv-vw-name').value.trim(), sql: box.querySelector('#adv-vw-sql').value } }, { showOutput: true, title: '视图创建' }).catch(() => {});
      box.querySelector('#adv-vw-drop').onclick = async () => {
        const n = box.querySelector('#adv-vw-name').value.trim();
        if (!n) return window.toast('请填写视图名', 'warn');
        const okc = await modal.confirm('删除视图', `删除 <b class="mono">${fmt.esc(n)}</b>？`, { danger: true, okText: '删除' });
        if (!okc) return;
        runAction('/db/object', { engine: eng, database: db(), kind: 'view', action: 'drop', params: { name: n } }).catch(() => {});
      };
      const tgParams = () => ({ name: box.querySelector('#adv-tg-name').value.trim(), table: box.querySelector('#adv-tg-table').value.trim(), timing: box.querySelector('#adv-tg-timing').value, events: [box.querySelector('#adv-tg-event').value], forEach: box.querySelector('#adv-tg-foreach').value, function: box.querySelector('#adv-tg-func').value.trim() });
      box.querySelector('#adv-tg-create').onclick = () => runAction('/db/object', { engine: eng, database: db(), kind: 'trigger', action: 'create', params: tgParams() }, { showOutput: true, title: '触发器创建' }).catch(() => {});
      box.querySelector('#adv-tg-enable').onclick = () => runAction('/db/object', { engine: eng, database: db(), kind: 'trigger', action: 'enable', params: tgParams() }).catch(() => {});
      box.querySelector('#adv-tg-disable').onclick = () => runAction('/db/object', { engine: eng, database: db(), kind: 'trigger', action: 'disable', params: tgParams() }).catch(() => {});
      box.querySelector('#adv-tg-drop').onclick = async () => {
        const p = tgParams();
        if (!p.name || !p.table) return window.toast('请填写触发器名与表名', 'warn');
        const okc = await modal.confirm('删除触发器', `删除 <b class="mono">${fmt.esc(p.name)}</b> on ${fmt.esc(p.table)}？`, { danger: true, okText: '删除' });
        if (!okc) return;
        runAction('/db/object', { engine: eng, database: db(), kind: 'trigger', action: 'drop', params: p }).catch(() => {});
      };
    }

    /* ---------- 扩展监控 ---------- */
    async function renderMonitor() {
      const db = box.querySelector('#adv-db').value;
      box.querySelector('#adv-monitor').innerHTML = `<div class="card"><div class="card-title">扩展监控（WAL / BGWriter / InnoDB / 复制槽）</div><div id="adv-mo-body">${ui.skeleton(6)}</div></div>`;
      const body = box.querySelector('#adv-mo-body');
      try {
        const r = await CM.api.get(`/db/extra-monitor?engine=${eng}&database=${encodeURIComponent(db)}`, 10000);
        body.innerHTML = (r.sections || []).map((s) => `
          <div class="mb">
            <div class="row between mb"><span class="mono small">${fmt.esc(s.title)}</span>${s.note ? `<span class="muted small">${fmt.esc(s.note)}</span>` : ''}</div>
            ${s.error ? `<div class="muted small">${ui.badge('不可用', 'gray')} ${fmt.esc(s.error)}</div>`
              : (s.rows && s.rows.length ? ui.table((s.columns || []).map((c) => ({ k: c, label: c, cls: 'mono', render: (v) => v === null ? '<span class="muted">NULL</span>' : fmt.esc(String(v).slice(0, 70)) })), s.rows, { style: 'max-height:200px' }) : '<div class="muted small">无数据</div>')}
          </div>`).join('') || '<div class="muted small">无数据</div>';
      } catch (e) { body.innerHTML = `<pre class="pre">${fmt.esc(e.message)}</pre>`; }
    }

    async function reloadAll() {
      await renderBulk();
      await renderStorage();
      await renderCopy();
      renderPlan();
      await renderMissing();
      renderSessions();
      renderSearch();
      renderObjects();
      await renderMonitor();
    }
    box.querySelector('#adv-db').onchange = () => { S.database = box.querySelector('#adv-db').value; CM.api.invalidate('/db/'); reloadAll(); };
    box.querySelector('#adv-refresh').onclick = () => { CM.api.invalidate('/db/'); reloadAll(); };
    await reloadAll();
  }

  /* =====================================================================
   *  Db2: roles / grants
   * =================================================================== */
  async function db2Roles(box) {
    const [r, dbs] = await Promise.all([
      CM.api.get('/db/roles?engine=db2').catch(() => ({ roles: [], unsupported: '' })),
      CM.api.get('/db/databases?engine=db2', 10000).catch(() => []),
    ]);
    const roles = r.roles || [];
    box.innerHTML = `
      <div class="card">
        <div class="card-title">Db2 授权（GRANT / REVOKE）</div>
        <div class="row">
          <label class="fld">用户/组<input class="inp mono" id="db2-user" placeholder="APPUSER" style="width:150px"></label>
          <label class="fld">作用范围
            <select class="inp" id="db2-scope"><option value="database">数据库级</option><option value="table">表级</option></select>
          </label>
          <label class="fld">权限<select class="inp" id="db2-auth">
            <option>DBADM</option><option>DATAACCESS</option><option>ACCESSCTRL</option><option>SECADM</option><option>CONNECT</option>
          </select></label>
          <label class="fld">表级权限<select class="inp" id="db2-privs">
            <option>SELECT</option><option>SELECT,INSERT</option><option>SELECT,INSERT,UPDATE,DELETE</option><option>ALL</option>
          </select></label>
          <label class="fld">表 (SCHEMA.TABLE)<input class="inp mono" id="db2-table" placeholder="APP.TBL" style="width:170px"></label>
          <label class="fld">操作<select class="inp" id="db2-op"><option>授予</option><option>回收</option></select></label>
          <button class="btn btn-primary" id="db2-grant" style="align-self:flex-end">执行</button>
        </div>
        <div class="muted small mt">Db2 用 GRANT/REVOKE 管理权限；用户本身需先在实例上创建（db2 CREATE USER 或操作系统账号）。</div>
      </div>
      <div class="card">
        <div class="card-title">用户 / 权限 (${roles.length}) <div class="right"><button class="btn btn-sm" id="db2-refresh">刷新</button></div></div>
        ${ui.table([
          { k: 'name', label: '用户', cls: 'mono' },
          { k: 'dbadm', label: 'DBADM', render: (v) => (v === 'Y' || v === 'true' || v === true) ? ui.badge('Y', 'bad') : ui.badge('N', 'gray') },
          { k: 'data_access', label: 'DATAACCESS', render: (v) => (v === 'Y' || v === 'true' || v === true) ? ui.badge('Y', 'bad') : ui.badge('N', 'gray') },
          { k: 'access_ctrl', label: 'ACCESSCTRL', render: (v) => (v === 'Y' || v === 'true' || v === true) ? ui.badge('Y', 'warn') : ui.badge('N', 'gray') },
        ], roles, { style: 'max-height:40vh' })}
        ${r.unsupported ? `<div class="muted small mt">${fmt.esc(r.unsupported)}</div>` : ''}
      </div>`;
    document.getElementById('db2-refresh').onclick = () => renderTab();
    document.getElementById('db2-grant').onclick = () => {
      const name = document.getElementById('db2-user').value.trim();
      if (!name) return window.toast('请输入用户/组名', 'warn');
      const auth = document.getElementById('db2-auth').value;
      const scope = document.getElementById('db2-scope').value;
      const op = document.getElementById('db2-op').value;
      const table = document.getElementById('db2-table').value.trim();
      const privs = document.getElementById('db2-privs').value.split(',').map((x) => x.trim()).filter(Boolean);
      if (scope === 'table' && !table) return window.toast('表级操作需填写 SCHEMA.TABLE', 'warn');
      const action = (op === '授予' ? 'grant' : 'revoke') + (scope === 'table' ? '-table' : '-db');
      runAction('/db/role', { engine: 'db2', action, name, privilege: auth, params: { table, privileges: privs } }, { reload: () => renderTab() }).catch(() => {});
    };
  }

  /* =====================================================================
   *  Redis (kv)
   * =================================================================== */
  async function kvOverview(box) {
    const [st, dbs] = await Promise.all([
      CM.api.get('/db/status?engine=redis'),
      CM.api.get('/db/databases?engine=redis', 10000).catch(() => []),
    ]);
    const v = st.version || {}, s = st.stats || {};
    const totalKeys = dbs.reduce((a, b) => a + Number(b.keys || 0), 0);
    box.innerHTML = `
      <div class="grid g4 mb">
        <div class="card stat"><div class="stat-label">引擎状态</div><div class="stat-value" style="font-size:20px;color:#4ade80">运行中</div><div class="stat-sub">${fmt.esc(String(v.version || '').slice(0, 46))}</div></div>
        <div class="card stat"><div class="stat-label">已连接客户端</div><div class="stat-value">${fmt.esc(s.connections || '0')}</div><div class="stat-sub">上限 ${fmt.esc(s.max_connections || '-')}</div></div>
        <div class="card stat"><div class="stat-label">键总数</div><div class="stat-value">${num(totalKeys)}</div><div class="stat-sub">${dbs.length} 个逻辑库</div></div>
        <div class="card stat"><div class="stat-label">已用内存</div><div class="stat-value">${fmt.esc(s.memory || '-')}</div><div class="stat-sub">命中 ${fmt.esc(s.hits || '-')}</div></div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">逻辑库 (db0-db15)</div>
          ${ui.table([
            { k: 'name', label: '库', cls: 'mono' },
            { k: 'keys', label: '键数', cls: 'num', render: (v2) => num(v2) },
            { k: 'expires', label: '带过期', cls: 'num' },
            { k: 'avg_ttl', label: '平均 TTL(ms)', cls: 'num' },
            { k: 'name', label: '操作', render: (v2) => `<button class="btn btn-sm" data-kvdb="${fmt.esc(v2.replace('db', ''))}">浏览</button>` },
          ], dbs)}
        </div>
        <div class="card">
          <div class="card-title">服务器信息</div>
          <dl class="kv">
            ${Object.entries(v).map(([k, val]) => `<dt>${fmt.esc(k)}</dt><dd title="${fmt.esc(String(val))}">${fmt.esc(String(val).slice(0, 60))}</dd>`).join('')}
            ${Object.entries(s).map(([k, val]) => `<dt>${fmt.esc(k)}</dt><dd>${fmt.esc(String(val))}</dd>`).join('')}
          </dl>
        </div>
      </div>

      <div class="card">
        <div class="card-title">连接的客户端 (${st.processes.length}) <div class="right"><button class="btn btn-sm" id="kv-reload">刷新</button></div></div>
        ${ui.table([
          { k: 'pid', label: 'ID', cls: 'num' },
          { k: 'user', label: '用户', cls: 'mono' },
          { k: 'db', label: '库', cls: 'mono' },
          { k: 'state', label: '最近命令', cls: 'mono' },
          { k: 'seconds', label: '存活(s)', cls: 'num' },
          { k: 'client', label: '客户端', cls: 'mono' },
          { k: 'pid', label: '', render: (x) => `<button class="btn btn-sm btn-danger" data-kvkill="${fmt.esc(String(x))}">断开</button>` },
        ], st.processes, { style: 'max-height:320px' })}
      </div>

      <div class="card">
        <div class="card-title">INFO (${st.variables.length})
          <div class="right"><input class="inp" id="kv-var-q" placeholder="搜索…" style="width:200px"></div>
        </div>
        <div id="kv-vars"></div>
      </div>`;

    const draw = (q) => {
      let rows = st.variables;
      if (q) rows = rows.filter((x) => (x.name + ' ' + x.value).toLowerCase().includes(q.toLowerCase()));
      document.getElementById('kv-vars').innerHTML = ui.table([
        { k: 'name', label: '项', cls: 'mono' },
        { k: 'value', label: '值', cls: 'mono wrap' },
      ], rows.slice(0, 400), { style: 'max-height:380px' });
    };
    draw('');
    document.getElementById('kv-var-q').oninput = (e) => draw(e.target.value);
    document.getElementById('kv-reload').onclick = () => renderTab();
    box.querySelectorAll('[data-kvdb]').forEach((b) => b.onclick = () => { S.kvDb = b.dataset.kvdb; S.tab = 'keys'; renderShell(ROOT); renderTab(); });
    box.querySelectorAll('[data-kvkill]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('断开客户端', `断开客户端 <b class="mono">${fmt.esc(b.dataset.kvkill)}</b>？`, { danger: true });
      if (!okc) return;
      runAction('/db/kill', { engine: 'redis', pid: Number(b.dataset.kvkill) }, { reload: () => renderTab() }).catch(() => {});
    });
  }

  async function kvKeys(box) {
    box.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row">
            <span class="muted small">库</span>
            <select class="inp mono" id="kv-select-db" style="width:90px">${Array.from({ length: 16 }, (_, i) => `<option value="${i}" ${String(i) === String(S.kvDb) ? 'selected' : ''}>db${i}</option>`).join('')}</select>
            <input class="inp mono" id="kv-pattern" value="${fmt.esc(S.kvPattern || '*')}" placeholder="模式，如 user:*" style="width:260px">
            <button class="btn btn-sm btn-primary" id="kv-scan">扫描</button>
            <span class="chip" id="kv-count">-</span>
          </div>
          <div class="row"><button class="btn btn-sm btn-ok" id="kv-new">${icon('plus', { size: 12 })} 新建键</button></div>
        </div>
      </div>
      <div class="card"><div id="kv-list">${ui.skeleton(6)}</div></div>`;

    const load = async () => {
      const dbv = document.getElementById('kv-select-db').value;
      const pattern = document.getElementById('kv-pattern').value.trim() || '*';
      S.kvDb = dbv; S.kvPattern = pattern;
      const host = document.getElementById('kv-list');
      host.innerHTML = ui.skeleton(6);
      try {
        const r = await CM.api.get(`/db/kv/keys?database=${encodeURIComponent(dbv)}&pattern=${encodeURIComponent(pattern)}&limit=300`);
        document.getElementById('kv-count').textContent = r.keys.length + ' 个键';
        host.innerHTML = ui.table([
          { k: 'key', label: '键', cls: 'mono', render: (v) => fmt.esc(String(v).slice(0, 90)) },
          { k: 'type', label: '类型', render: (v) => ui.badge(v || '-', 'info') },
          { k: 'ttl', label: 'TTL', cls: 'num', render: (v) => (v == null || v < 0) ? '<span class="muted">永久</span>' : v + 's' },
          { k: 'size', label: '内存', cls: 'num', render: (v) => v == null ? '-' : fmt.bytes(v) },
          { k: 'key', label: '操作', render: (v) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm btn-primary" data-view="${fmt.esc(v)}">查看</button>
              <button class="btn btn-sm" data-ed="${fmt.esc(v)}">改值</button>
              <button class="btn btn-sm btn-danger" data-del="${fmt.esc(v)}">删除</button></span>` },
        ], r.keys, { style: 'max-height:64vh' });
        host.querySelectorAll('[data-view]').forEach((b) => b.onclick = async () => {
          try { const val = await CM.api.get(`/db/kv/value?database=${encodeURIComponent(dbv)}&key=${encodeURIComponent(b.dataset.view)}`);
            modal.output('键 ' + b.dataset.view + '（类型 ' + val.type + '）', val.rows ? JSON.stringify(val.rows, null, 1) : String((val.value || []).join('\n')));
          } catch (e) { window.toast(e.message, 'err'); }
        });
        host.querySelectorAll('[data-ed]').forEach((b) => b.onclick = () => {
          modal.prompt('编辑键值 - ' + b.dataset.ed, [
            { name: 'value', label: '字符串值', value: '' },
            { name: 'ttl', label: '过期秒数（0=不过期）', value: '0' },
          ], { okText: '保存' }).then((res) => { if (!res) return; runAction('/db/kv/set', { database: dbv, key: b.dataset.ed, value: res.value, ttl: Number(res.ttl) || 0 }, { reload: () => load() }).catch(() => {}); });
        });
        host.querySelectorAll('[data-del]').forEach((b) => b.onclick = async () => {
          const okc = await modal.confirm('删除键', `删除 <b class="mono">${fmt.esc(b.dataset.del)}</b>？不可恢复！`, { danger: true, okText: '删除' });
          if (!okc) return;
          runAction('/db/kv/delete', { database: dbv, keys: [b.dataset.del] }, { reload: () => load() }).catch(() => {});
        });
      } catch (e) { host.innerHTML = `<pre class="pre">${fmt.esc(e.message)}</pre>`; }
    };
    document.getElementById('kv-scan').onclick = load;
    document.getElementById('kv-pattern').onkeydown = (e) => { if (e.key === 'Enter') load(); };
    document.getElementById('kv-select-db').onchange = load;
    document.getElementById('kv-new').onclick = () => {
      modal.prompt('新建字符串键', [
        { name: 'key', label: '键名', placeholder: 'user:1:name' },
        { name: 'value', label: '值' },
        { name: 'ttl', label: '过期秒数（0=不过期）', value: '0' },
      ], { okText: '写入' }).then((res) => { if (!res || !res.key) return; runAction('/db/kv/set', { database: S.kvDb, key: res.key, value: res.value, ttl: Number(res.ttl) || 0 }, { reload: () => load() }).catch(() => {}); });
    };
    await load();
  }

  function kvConsole(box) {
    box.innerHTML = `
      <div class="card">
        <div class="card-title">Redis 命令控制台
          <div class="right">
            <select class="inp" id="rd-db" style="width:90px">${Array.from({ length: 16 }, (_, i) => `<option value="${i}" ${String(i) === String(S.kvDb) ? 'selected' : ''}>db${i}</option>`).join('')}</select>
            <button class="btn btn-sm btn-primary" id="rd-run">执行 (Ctrl+Enter)</button>
          </div>
        </div>
        <div class="muted small mb">输入 Redis 命令（自动分词），例如 <code>GET user:1:name</code>、<code>LRANGE list:1 0 10</code>、<code>INFO memory</code></div>
        <textarea class="inp mono" id="rd-text" spellcheck="false" style="min-height:140px">INFO server</textarea>
        <div id="rd-result" class="mt"></div>
      </div>
      <div class="card">
        <div class="card-title">常用命令</div>
        <div class="row" style="flex-wrap:wrap">
          ${['INFO all', 'DBSIZE', 'CLIENT LIST', 'CONFIG GET maxmemory', 'MEMORY STATS', 'SLOWLOG GET 10', 'TTL your:key', 'TYPE your:key'].map((c) => `<button class="btn btn-sm" data-rd="${fmt.esc(c)}">${fmt.esc(c)}</button>`).join('')}
        </div>
      </div>`;
    const run = async (cmd) => {
      const res = document.getElementById('rd-result');
      res.innerHTML = ui.skeleton(3);
      try {
        const r = await CM.api.post('/db/console', { engine: 'redis', database: document.getElementById('rd-db').value, sql: cmd });
        res.innerHTML = `<div class="row between mb"><span class="muted small">执行成功</span><button class="btn btn-sm" id="rd-copy">复制结果</button></div><pre class="pre tall" style="max-height:60vh">${fmt.esc(r.output || '(nil)')}</pre>`;
        const cp = document.getElementById('rd-copy');
        if (cp) cp.onclick = async () => { try { await navigator.clipboard.writeText(r.output || ''); window.toast('已复制', 'ok'); } catch (_) {} };
      } catch (e) {
        res.innerHTML = `<div class="mb">${ui.badge('执行失败', 'bad')}</div><pre class="pre tall">${fmt.esc(e.message)}</pre>`;
      }
    };
    document.getElementById('rd-run').onclick = () => { const c = document.getElementById('rd-text').value.trim(); if (c) run(c); };
    document.getElementById('rd-text').onkeydown = (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); document.getElementById('rd-run').onclick(); } };
    box.querySelectorAll('[data-rd]').forEach((b) => b.onclick = () => { document.getElementById('rd-text').value = b.dataset.rd; run(b.dataset.rd); });
  }

  async function kvRoles(box) {
    const r = await CM.api.get('/db/roles?engine=redis').catch(() => ({ roles: [] }));
    const roles = r.roles || [];
    box.innerHTML = `
      <div class="card">
        <div class="card-title">创建 ACL 用户</div>
        <div class="row">
          <label class="fld">用户名<input class="inp mono" id="acl-name" placeholder="appuser" style="width:150px"></label>
          <label class="fld">密码<input type="password" class="inp" id="acl-pw" style="width:170px"></label>
          <button class="btn btn-primary" id="acl-create" style="align-self:flex-end">创建</button>
        </div>
        <div class="muted small mt">创建的用户拥有 <code>~* +@all</code>（全部键 + 全部命令），可到 ACL SETUSER 细化授权。</div>
      </div>
      <div class="card">
        <div class="card-title">ACL 用户 (${roles.length}) <div class="right"><button class="btn btn-sm" id="acl-refresh">刷新</button></div></div>
        ${ui.table([
          { k: 'name', label: '用户', cls: 'mono' },
          { k: 'rule', label: '规则', cls: 'mono wrap', render: (v) => fmt.esc(String(v).slice(0, 140)) },
          { k: 'name', label: '操作', render: (v) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm" data-aclpw="${fmt.esc(v)}">改密</button>
              <button class="btn btn-sm btn-danger" data-acldrop="${fmt.esc(v)}">删除</button></span>` },
        ], roles, { style: 'max-height:50vh' })}
      </div>`;
    document.getElementById('acl-refresh').onclick = () => renderTab();
    document.getElementById('acl-create').onclick = () => {
      const name = document.getElementById('acl-name').value.trim();
      const pw = document.getElementById('acl-pw').value;
      if (!name) return window.toast('请输入用户名', 'warn');
      runAction('/db/role', { engine: 'redis', action: 'create', name, password: pw }, { reload: () => renderTab() }).catch(() => {});
    };
    box.querySelectorAll('[data-aclpw]').forEach((b) => b.onclick = () => {
      modal.prompt('修改密码 - ' + b.dataset.aclpw, [{ name: 'password', label: '新密码', type: 'password' }], { okText: '修改' })
        .then((res) => { if (!res || !res.password) return; runAction('/db/role', { engine: 'redis', action: 'password', name: b.dataset.aclpw, password: res.password }, { reload: () => renderTab() }).catch(() => {}); });
    });
    box.querySelectorAll('[data-acldrop]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('删除 ACL 用户', `删除 <b class="mono">${fmt.esc(b.dataset.acldrop)}</b>？`, { danger: true, okText: '删除' });
      if (!okc) return;
      runAction('/db/role', { engine: 'redis', action: 'drop', name: b.dataset.acldrop }, { reload: () => renderTab() }).catch(() => {});
    });
  }

  async function kvAdvanced(box) {
    box.innerHTML = `
      <div class="grid g2">
        <div class="card">
          <div class="card-title">维护操作</div>
          <div class="row" id="kv-maint">
            <button class="btn" data-kvact="bgsave">BGSAVE（后台快照）</button>
            <button class="btn" data-kvact="save">SAVE（同步快照）</button>
            <button class="btn" data-kvact="bgrewriteaof">BGREWRITEAOF</button>
            <button class="btn btn-danger" data-kvact="flushdb">FLUSHDB（清空当前库）</button>
            <button class="btn btn-danger" data-kvact="flushall">FLUSHALL（清空全部）</button>
          </div>
          <div class="muted small mt">BGSAVE 会在后台生成 RDB 文件；FLUSHDB/ALL 会删除所有数据，需二次确认。</div>
        </div>
        <div class="card">
          <div class="card-title">慢查询 (SLOWLOG) <div class="right"><button class="btn btn-sm" id="kv-slow">重新加载</button></div></div>
          <div id="kv-slow-out">${ui.skeleton(4)}</div>
        </div>
      </div>
      <div class="card">
        <div class="card-title">运行时配置 <div class="right"><button class="btn btn-sm" id="kv-cfg-reload">刷新</button></div></div>
        <div id="kv-cfg">${ui.skeleton(6)}</div>
      </div>
      <div id="kv-info"></div>`;

    const loadCfg = async () => {
      try {
        const r = await CM.api.get('/db/kv/config');
        const rows = (r.rows || []).filter((x) => x.name);
        document.getElementById('kv-cfg').innerHTML = ui.table([
          { k: 'name', label: '配置项', cls: 'mono' },
          { k: 'value', label: '值', cls: 'mono wrap' },
          { k: 'name', label: '', render: (v) => `<button class="btn btn-sm" data-cfg="${fmt.esc(v)}">修改</button>` },
        ], rows, { style: 'max-height:44vh' });
        document.querySelectorAll('[data-cfg]').forEach((b) => b.onclick = () => {
          const cur = rows.find((x) => x.name === b.dataset.cfg);
          modal.prompt('CONFIG SET ' + b.dataset.cfg, [{ name: 'value', label: '新值', value: cur ? cur.value : '' }], { okText: '设置' })
            .then((res) => { if (!res) return; runAction('/db/kv/config', { name: b.dataset.cfg, value: res.value }, { reload: () => loadCfg() }).catch(() => {}); });
        });
      } catch (e) { document.getElementById('kv-cfg').innerHTML = `<pre class="pre">${fmt.esc(e.message)}</pre>`; }
    };
    const loadSlow = async () => {
      try {
        const st = await CM.api.get('/db/statements?engine=redis');
        document.getElementById('kv-slow-out').innerHTML = st.rows && st.rows.length
          ? ui.table((st.columns || []).map((c) => ({ k: c, label: c, cls: 'mono wrap' })), st.rows, { style: 'max-height:300px' })
          : '<div class="muted small">没有慢查询记录</div>';
      } catch (e) { document.getElementById('kv-slow-out').innerHTML = `<pre class="pre">${fmt.esc(e.message)}</pre>`; }
    };
    box.querySelectorAll('[data-kvact]').forEach((b) => b.onclick = async () => {
      const act = b.dataset.kvact;
      const danger = act === 'flushdb' || act === 'flushall' || act === 'save';
      const okc = await modal.confirm('Redis 维护', `执行 <b>${fmt.esc(act)}</b>？${danger ? '<br><span style="color:#f87171">该操作可能删除所有数据或短暂阻塞服务。</span>' : ''}`, { danger, okText: '执行' });
      if (!okc) return;
      try { const r = await runAction('/db/maintenance', { engine: 'redis', action: act }, { toast: false }); modal.output('执行结果', r.output); }
      catch (_) {}
    });
    document.getElementById('kv-cfg-reload').onclick = loadCfg;
    document.getElementById('kv-slow').onclick = loadSlow;
    loadCfg(); loadSlow();
    const infoHost = document.getElementById('kv-info');
    try { const ins = await CM.api.get('/db/insights?engine=redis'); renderSections(infoHost, ins.sections || []); }
    catch (e) { infoHost.innerHTML = `<div class="card"><pre class="pre">${fmt.esc(e.message)}</pre></div>`; }
  }

  /* =====================================================================
   *  MongoDB (doc)
   * =================================================================== */
  async function docOverview(box) {
    const [st, dbs] = await Promise.all([
      CM.api.get('/db/status?engine=mongo'),
      CM.api.get('/db/databases?engine=mongo', 10000).catch(() => []),
    ]);
    const v = st.version || {}, s = st.stats || {};
    const totalSize = dbs.reduce((a, b) => a + Number(b.size_bytes || 0), 0);
    box.innerHTML = `
      <div class="grid g4 mb">
        <div class="card stat"><div class="stat-label">引擎状态</div><div class="stat-value" style="font-size:20px;color:#4ade80">运行中</div><div class="stat-sub">${fmt.esc(String(v.version || '').slice(0, 46))}</div></div>
        <div class="card stat"><div class="stat-label">连接</div><div class="stat-value">${fmt.esc(s.connections || '0')}</div><div class="stat-sub">上限 ${fmt.esc(s.max_connections || '-')}</div></div>
        <div class="card stat"><div class="stat-label">数据库</div><div class="stat-value">${dbs.length}</div><div class="stat-sub">占用 ${fmt.bytes(totalSize)}</div></div>
        <div class="card stat"><div class="stat-label">主机 / 运行时间</div><div class="stat-value" style="font-size:16px">${fmt.esc(v.host || '-')}</div><div class="stat-sub">${fmt.esc(v.uptime || '')}</div></div>
      </div>
      <div class="card">
        <div class="card-title">数据库列表 <div class="right"><button class="btn btn-sm" id="mo-refresh">${icon('refresh', { size: 12 })} 刷新</button></div></div>
        ${ui.table([
          { k: 'name', label: '数据库', cls: 'mono' },
          { k: 'size_bytes', label: '大小', cls: 'num', render: (x) => fmt.bytes(x) },
          { k: 'name', label: '操作', render: (x, r) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm" data-modb="${fmt.esc(x)}">进入</button>
              ${r.is_template ? '' : `<button class="btn btn-sm btn-danger" data-modrop="${fmt.esc(x)}">删除</button>`}</span>` },
        ], dbs, { style: 'max-height:44vh' })}
      </div>`;
    document.getElementById('mo-refresh').onclick = () => renderTab();
    box.querySelectorAll('[data-modb]').forEach((b) => b.onclick = () => { S.database = b.dataset.modb; S.table = null; S.tab = 'tables'; renderShell(ROOT); renderTab(); });
    box.querySelectorAll('[data-modrop]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('删除数据库', `删除 <b class="mono">${fmt.esc(b.dataset.modrop)}</b> 及其全部集合？不可恢复！`, { danger: true, okText: '删除' });
      if (!okc) return;
      runAction('/db/database/drop', { engine: 'mongo', name: b.dataset.modrop }, { reload: () => renderTab() }).catch(() => {});
    });
  }

  async function docDatabases(box) {
    const dbs = await CM.api.get('/db/databases?engine=mongo', 10000).catch(() => []);
    box.innerHTML = `
      <div class="card">
        <div class="card-title">新建数据库<div class="right"><button class="btn btn-sm btn-primary" id="mo-create">创建</button></div></div>
        <div class="row">
          <label class="fld">数据库名<input class="inp mono" id="mo-name" placeholder="mydb" style="width:200px"></label>
          <span class="muted small">MongoDB 在首次写入集合时才真正创建数据库，这里会创建 <code>_cm_init</code> 集合占位。</span>
        </div>
      </div>
      <div class="card">
        <div class="card-title">数据库 (${dbs.length}) <div class="right"><button class="btn btn-sm" id="mo-refresh">刷新</button></div></div>
        ${ui.table([
          { k: 'name', label: '数据库', cls: 'mono', render: (v, r) => `${fmt.esc(v)} ${r.is_template ? ui.badge('系统', 'gray') : ''}` },
          { k: 'size_bytes', label: '大小', cls: 'num', render: (v) => fmt.bytes(v) },
          { k: 'name', label: '操作', render: (v, r) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm" data-use="${fmt.esc(v)}">进入</button>
              <button class="btn btn-sm btn-danger" data-drop="${fmt.esc(v)}" ${r.is_template ? 'disabled' : ''}>删除</button></span>` },
        ], dbs, { style: 'max-height:52vh' })}
      </div>`;
    document.getElementById('mo-refresh').onclick = () => renderTab();
    document.getElementById('mo-create').onclick = () => {
      const name = document.getElementById('mo-name').value.trim();
      if (!name) return window.toast('请输入数据库名', 'warn');
      runAction('/db/database/create', { engine: 'mongo', name }, { reload: () => renderTab() }).catch(() => {});
    };
    box.querySelectorAll('[data-use]').forEach((b) => b.onclick = () => { S.database = b.dataset.use; S.table = null; S.tab = 'tables'; renderShell(ROOT); renderTab(); });
    box.querySelectorAll('[data-drop]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('删除数据库', `删除 <b class="mono">${fmt.esc(b.dataset.drop)}</b> 及其全部数据？不可恢复！`, { danger: true, okText: '删除' });
      if (!okc) return;
      runAction('/db/database/drop', { engine: 'mongo', name: b.dataset.drop }, { reload: () => renderTab() }).catch(() => {});
    });
  }

  async function docCollections(box) {
    if (!S.database) {
      const dbs = await CM.api.get('/db/databases?engine=mongo', 10000).catch(() => []);
      box.innerHTML = `<div class="card"><div class="card-title">选择数据库</div>
        <div class="row">${dbs.map((d) => `<button class="btn" data-pick="${fmt.esc(d.name)}">${fmt.esc(d.name)} <span class="muted small">${fmt.esc(fmt.bytes(d.size_bytes))}</span></button>`).join('')}</div></div>`;
      box.querySelectorAll('[data-pick]').forEach((b) => b.onclick = () => { S.database = b.dataset.pick; S.table = null; renderTab(); });
      return;
    }
    const colls = await CM.api.get(`/db/tables?engine=mongo&database=${encodeURIComponent(S.database)}`);

    if (!S.table) {
      box.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row"><span class="muted small">当前数据库</span><b class="mono">${fmt.esc(S.database)}</b><span class="chip">${colls.length} 个集合</span></div>
          <div class="row"><button class="btn btn-sm" id="mo-switch">切换数据库</button><button class="btn btn-sm btn-ok" id="mo-newcoll">${icon('plus', { size: 12 })} 新建集合</button></div>
        </div>
      </div>
      <div class="card">
        <div class="card-title">集合 <div class="right"><input class="inp" id="mo-q" placeholder="搜索…" style="width:180px"></div></div>
        <div id="mo-list"></div>
      </div>`;
      const draw = (q) => {
        const rows = q ? colls.filter((c) => c.name.toLowerCase().includes(q.toLowerCase())) : colls;
        document.getElementById('mo-list').innerHTML = ui.table([
          { k: 'name', label: '集合', cls: 'mono' },
          { k: 'rows', label: '文档数', cls: 'num', render: (v) => v == null ? '-' : Number(v).toLocaleString() },
          { k: 'size', label: '大小', cls: 'num', render: (v) => fmt.bytes(v) },
          { k: 'indexes', label: '索引', cls: 'num' },
          { k: 'name', label: '操作', render: (v) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm btn-primary" data-open="${fmt.esc(v)}">打开</button>
              <button class="btn btn-sm btn-danger" data-dropcoll="${fmt.esc(v)}">删除</button></span>` },
        ], rows, { style: 'max-height:56vh' });
        document.querySelectorAll('[data-open]').forEach((b) => b.onclick = () => { S.table = b.dataset.open; renderTab(); });
        document.querySelectorAll('[data-dropcoll]').forEach((b) => b.onclick = async () => {
          const okc = await modal.confirm('删除集合', `删除集合 <b class="mono">${fmt.esc(b.dataset.dropcoll)}</b> 及其全部文档？`, { danger: true, okText: '删除' });
          if (!okc) return;
          runAction('/db/table/action', { engine: 'mongo', database: S.database, table: b.dataset.dropcoll, action: 'drop' }, { reload: () => renderTab() }).catch(() => {});
        });
      };
      draw('');
      document.getElementById('mo-q').oninput = (e) => draw(e.target.value);
      document.getElementById('mo-switch').onclick = () => { S.database = null; renderTab(); };
      document.getElementById('mo-newcoll').onclick = () => {
        modal.prompt('新建集合', [{ name: 'name', label: '集合名', placeholder: 'users' }], { okText: '创建' })
          .then((r) => { if (!r || !r.name) return; runAction('/db/doc/collection', { database: S.database, name: r.name }, { reload: () => renderTab() }).catch(() => {}); });
      };
      return;
    }

    /* ---- documents ---- */
    const idx = await CM.api.post('/db/doc/indexes', { database: S.database, table: S.table }).catch(() => ({ indexes: [] }));
    box.innerHTML = `
      <div class="card" style="padding:12px 14px">
        <div class="row between">
          <div class="row"><button class="btn btn-sm" id="mo-back">${icon('chevronLeft', { size: 13 })} 返回集合列表</button>
            <b class="mono">${fmt.esc(S.database)}.${fmt.esc(S.table)}</b></div>
          <div class="row">
            <input class="inp mono" id="mo-filter" placeholder='过滤，如 {"status":"active"} 或 id=5' style="width:300px">
            <button class="btn btn-sm btn-primary" id="mo-find">查询</button>
            <button class="btn btn-sm btn-ok" id="mo-insert">${icon('plus', { size: 12 })} 插入</button>
          </div>
        </div>
      </div>
      <div class="card"><div id="mo-docs">${ui.skeleton(6)}</div></div>
      <div class="grid g2">
        <div class="card">
          <div class="card-title">索引 (${(idx.indexes || []).length})</div>
          ${ui.table([
            { k: 'name', label: '索引', cls: 'mono' },
            { k: 'key', label: '键', cls: 'mono' },
            { k: 'unique', label: '唯一', render: (v) => v ? ui.badge('Y', 'ok') : '' },
            { k: 'name', label: '', render: (v) => v === '_id_' ? '' : `<button class="btn btn-sm btn-danger" data-dropidx="${fmt.esc(v)}">删除</button>` },
          ], idx.indexes || [])}
          <div class="row mt"><input class="inp mono" id="mo-newidx" placeholder='{"field":1}' style="width:200px"><label class="row small muted" style="gap:4px"><input type="checkbox" id="mo-uniq"> 唯一</label><button class="btn btn-sm btn-primary" id="mo-idx-add">建索引</button></div>
        </div>
        <div class="card">
          <div class="card-title">说明</div>
          <div class="muted small">MongoDB 的过滤条件支持 <code>{"字段":值}</code> 形式（内部转为查询条件）或 <code>字段=值</code> 简写。文档以 JSON 展示，_id 为文档标识。</div>
        </div>
      </div>`;

    document.getElementById('mo-back').onclick = () => { S.table = null; renderTab(); };
    const load = async () => {
      const where = document.getElementById('mo-filter').value.trim();
      const host = document.getElementById('mo-docs');
      host.innerHTML = ui.skeleton(6);
      try {
        const r = await CM.api.post('/db/table/data', { engine: 'mongo', database: S.database, table: S.table, limit: 100, offset: 0, where: where || null });
        const cols = r.columns || [];
        const rawRows = r.raw || r.rows || [];
        host.innerHTML = `<div class="row between mb"><span class="muted small">${r.total != null ? r.total + ' 个文档' : ''}</span></div>` +
          ui.table([
            ...cols.map((c) => ({ k: c, label: c, cls: 'mono', render: (v) => { const s2 = v == null ? 'NULL' : String(v); return `<span title="${fmt.esc(s2)}">${fmt.esc(s2.length > 60 ? s2.slice(0, 60) + '…' : s2)}</span>`; } })),
            { k: '_id', label: '操作', render: (v, row) => `<span class="row" style="gap:4px">
                <button class="btn btn-sm" data-editdoc="${fmt.esc(v == null ? '' : String(v))}">编辑</button>
                <button class="btn btn-sm btn-danger" data-deldoc="${fmt.esc(v == null ? '' : String(v))}">删除</button></span>` },
          ], r.rows || [], { style: 'max-height:56vh' });
        document.querySelectorAll('[data-editdoc]').forEach((b) => b.onclick = () => {
          const id = b.dataset.editdoc;
          const doc = rawRows.find((x) => String(x._id) === id) || {};
          modal.prompt('更新文档（JSON，可用 $set）', [{ name: 'value', label: '更新内容', value: JSON.stringify({ $set: doc }) }], { okText: '更新' })
            .then((res) => { if (!res || !res.value) return; runAction('/db/doc/update', { database: S.database, table: S.table, where: id, values: res.value }, { reload: () => load() }).catch(() => {}); });
        });
        document.querySelectorAll('[data-deldoc]').forEach((b) => b.onclick = async () => {
          const okc = await modal.confirm('删除文档', `删除 _id = <b class="mono">${fmt.esc(b.dataset.deldoc)}</b>？`, { danger: true, okText: '删除' });
          if (!okc) return;
          runAction('/db/doc/delete', { database: S.database, table: S.table, where: b.dataset.deldoc }, { reload: () => load() }).catch(() => {});
        });
      } catch (e) { host.innerHTML = `<pre class="pre">${fmt.esc(e.message)}</pre>`; }
    };
    document.getElementById('mo-find').onclick = load;
    document.getElementById('mo-filter').onkeydown = (e) => { if (e.key === 'Enter') load(); };
    document.getElementById('mo-insert').onclick = () => {
      modal.prompt('插入文档', [{ name: 'value', label: 'JSON 文档', value: '{"name":"example"}' }], { okText: '插入' })
        .then((r) => { if (!r || !r.value) return; runAction('/db/doc/insert', { database: S.database, table: S.table, values: r.value }, { reload: () => load() }).catch(() => {}); });
    };
    document.getElementById('mo-idx-add').onclick = () => {
      const k = document.getElementById('mo-newidx').value.trim();
      if (!k) return window.toast('请输入索引键', 'warn');
      runAction('/db/doc/index', { database: S.database, table: S.table, keys: k, unique: document.getElementById('mo-uniq').checked }, { reload: () => renderTab() }).catch(() => {});
    };
    document.querySelectorAll('[data-dropidx]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('删除索引', `删除索引 <b class="mono">${fmt.esc(b.dataset.dropidx)}</b>？`, { danger: true, okText: '删除' });
      if (!okc) return;
      runAction('/db/doc/index/drop', { database: S.database, table: S.table, name: b.dataset.dropidx }, { reload: () => renderTab() }).catch(() => {});
    });
    await load();
  }

  function docConsole(box) {
    box.innerHTML = `
      <div class="card">
        <div class="card-title">MongoDB Shell
          <div class="right"><button class="btn btn-sm btn-primary" id="mo-run">执行 (Ctrl+Enter)</button></div>
        </div>
        <div class="muted small mb">输入 mongosh 脚本（当前作用域内已有 <code>db</code>，已绑定到所选数据库），例如 <code>db.getCollectionNames()</code>、<code>db.users.countDocuments()</code></div>
        <textarea class="inp mono" id="mo-text" spellcheck="false" style="min-height:200px">${S.database ? `db.getCollectionNames()` : `db.adminCommand('ping')`}</textarea>
        <div id="mo-result" class="mt"></div>
      </div>`;
    const run = async () => {
      const cmd = document.getElementById('mo-text').value;
      if (!cmd.trim()) return window.toast('请输入命令', 'warn');
      const res = document.getElementById('mo-result');
      res.innerHTML = ui.skeleton(4);
      try {
        const r = await CM.api.post('/db/console', { engine: 'mongo', database: S.database, sql: cmd });
        res.innerHTML = `<div class="row between mb"><span class="muted small">执行成功</span><button class="btn btn-sm" id="mo-copy">复制结果</button></div><pre class="pre tall" style="max-height:60vh">${fmt.esc(r.output || '(无输出)')}</pre>`;
        const cp = document.getElementById('mo-copy');
        if (cp) cp.onclick = async () => { try { await navigator.clipboard.writeText(r.output || ''); window.toast('已复制', 'ok'); } catch (_) {} };
      } catch (e) {
        res.innerHTML = `<div class="mb">${ui.badge('执行失败', 'bad')}</div><pre class="pre tall">${fmt.esc(e.message)}</pre>`;
      }
    };
    document.getElementById('mo-run').onclick = run;
    document.getElementById('mo-text').onkeydown = (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); run(); } };
  }

  async function docRoles(box) {
    const r = await CM.api.get('/db/roles?engine=mongo').catch(() => ({ roles: [] }));
    const roles = r.roles || [];
    box.innerHTML = `
      <div class="card">
        <div class="card-title">创建用户</div>
        <div class="row">
          <label class="fld">用户名<input class="inp mono" id="mo-user" style="width:150px"></label>
          <label class="fld">密码<input type="password" class="inp" id="mo-pw" style="width:170px"></label>
          <label class="fld">库<input class="inp mono" id="mo-userdb" value="admin" style="width:110px"></label>
          <label class="fld">角色<input class="inp mono" id="mo-roles" value="readWrite" placeholder="readWrite, dbOwner" style="width:180px"></label>
          <button class="btn btn-primary" id="mo-user-create" style="align-self:flex-end">创建</button>
        </div>
      </div>
      <div class="card">
        <div class="card-title">用户列表 (${roles.length}) <div class="right"><button class="btn btn-sm" id="mo-user-refresh">刷新</button></div></div>
        ${ui.table([
          { k: 'name', label: '用户', cls: 'mono' },
          { k: 'roles', label: '角色', cls: 'mono' },
          { k: 'name', label: '操作', render: (v) => `<span class="row" style="gap:4px">
              <button class="btn btn-sm" data-upw="${fmt.esc(v)}">改密</button>
              <button class="btn btn-sm btn-danger" data-udrop="${fmt.esc(v)}">删除</button></span>` },
        ], roles, { style: 'max-height:50vh' })}
      </div>`;
    document.getElementById('mo-user-refresh').onclick = () => renderTab();
    document.getElementById('mo-user-create').onclick = () => {
      const name = document.getElementById('mo-user').value.trim();
      if (!name) return window.toast('请输入用户名', 'warn');
      runAction('/db/role', { engine: 'mongo', action: 'create', name, password: document.getElementById('mo-pw').value, database: document.getElementById('mo-userdb').value.trim(), roles: document.getElementById('mo-roles').value }, { reload: () => renderTab() }).catch(() => {});
    };
    box.querySelectorAll('[data-upw]').forEach((b) => b.onclick = () => {
      modal.prompt('修改密码 - ' + b.dataset.upw, [{ name: 'password', label: '新密码', type: 'password' }], { okText: '修改' })
        .then((res) => { if (!res || !res.password) return; runAction('/db/role', { engine: 'mongo', action: 'password', name: b.dataset.upw, password: res.password, database: 'admin' }, { reload: () => renderTab() }).catch(() => {}); });
    });
    box.querySelectorAll('[data-udrop]').forEach((b) => b.onclick = async () => {
      const okc = await modal.confirm('删除用户', `删除 <b class="mono">${fmt.esc(b.dataset.udrop)}</b>？`, { danger: true, okText: '删除' });
      if (!okc) return;
      runAction('/db/role', { engine: 'mongo', action: 'drop', name: b.dataset.udrop, database: 'admin' }, { reload: () => renderTab() }).catch(() => {});
    });
  }

  async function docAdvanced(box) {
    const dbs = await CM.api.get('/db/databases?engine=mongo', 10000).catch(() => []);
    const dbNames = dbs.map((d) => d.name);
    const cur = S.database && dbNames.includes(S.database) ? S.database : (dbNames[0] || 'admin');
    box.innerHTML = `
      <div class="grid g2">
        <div class="card">
          <div class="card-title">维护操作</div>
          <div class="row mb">
            <select class="inp" id="mo-ma-db" style="width:180px">${dbNames.map((n) => `<option ${n === cur ? 'selected' : ''}>${fmt.esc(n)}</option>`).join('')}</select>
            <select class="inp" id="mo-ma-act" style="width:180px">
              <option value="stats">数据库统计</option>
              <option value="validate">校验全部集合</option>
              <option value="reindex">重建全部索引</option>
              <option value="compact">压缩集合</option>
            </select>
            <button class="btn btn-sm btn-primary" id="mo-ma-run">执行</button>
          </div>
          <div class="muted small">MongoDB 不做空间回收，validate/reindex 是常用的例行维护。</div>
        </div>
        <div class="card">
          <div class="card-title">慢查询（剖析）</div>
          <div class="row mb">
            <select class="inp" id="mo-prof" style="width:220px">
              <option value="0">关闭 (0)</option><option value="1">仅慢查询 (1)</option><option value="2">全部 (2)</option>
            </select>
            <button class="btn btn-sm btn-primary" id="mo-prof-run">设置 setProfilingLevel</button>
          </div>
          <div class="muted small">开启后可用 <code>db.system.profile.find().limit(20)</code> 查看慢操作。</div>
        </div>
      </div>
      <div id="mo-out"></div>`;

    document.getElementById('mo-ma-run').onclick = async () => {
      const dbv = document.getElementById('mo-ma-db').value;
      const act = document.getElementById('mo-ma-act').value;
      try { const r = await runAction('/db/maintenance', { engine: 'mongo', database: dbv, action: act }, { toast: false }); modal.output('MongoDB 维护结果', r.output); }
      catch (_) {}
    };
    document.getElementById('mo-prof-run').onclick = async () => {
      const lvl = document.getElementById('mo-prof').value;
      try {
        const r = await runAction('/db/console', { engine: 'mongo', database: 'admin', sql: `db.setProfilingLevel(${lvl}, {slowms: 100})` }, { toast: false });
        modal.output('剖析设置', r.output);
      } catch (_) {}
    };
    const outHost = document.getElementById('mo-out');
    try {
      const ins = await CM.api.get('/db/extra-monitor?engine=mongo');
      renderSections(outHost, ins.sections || []);
    } catch (e) { outHost.innerHTML = `<div class="card"><pre class="pre">${fmt.esc(e.message)}</pre></div>`; }
  }
})();
