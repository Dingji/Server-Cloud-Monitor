/* Package manager page — apt search / install / remove / upgrade */
(function () {
  'use strict';

  const esc = (s) => fmt.esc(s);
  const kb = (n) => (n == null ? '-' : fmt.bytes(n * 1024));
  const ACTION_TITLE = {
    install: '安装', remove: '卸载', purge: '彻底卸载', upgrade: '升级', 'full-upgrade': '完整升级',
    autoremove: '清理孤立依赖', hold: '锁定版本', unhold: '解除锁定', update: '更新软件源索引', clean: '清理缓存',
  };
  const RISKY = ['remove', 'purge', 'autoremove', 'full-upgrade'];

  registerPage({
    id: 'packages',
    title: '软件包管理',
    async mount(root) {
      root.innerHTML = `<div class="card">${ui.skeleton(4)}</div>`;
      let sum;
      try { sum = await CM.api.get('/pkg/summary', 30000); }
      catch (e) { root.innerHTML = `<div class="card"><div class="muted">${esc(e.message)}</div></div>`; return; }

      const TABS = [['search', '搜索安装'], ['installed', '已安装'], ['upgradable', '可升级'], ['recommended', '常用推荐'], ['maintain', '维护操作'], ['sources', '软件源']];
      /* sel: 批量勾选集合，key=包名 value=是否已安装（决定批量动作是安装还是卸载） */
      const S = { tab: 'search', busy: false, q: '', page: 0, iq: '', section: '', sort: 'name', dir: 'asc', srcUri: '', srcCache: null, sel: new Map() };

      root.innerHTML = `
        <div class="grid g4 mb" id="pk-stats"></div>
        <div class="card" style="padding:10px 12px">
          <div class="row between">
            <div class="row" id="pk-tabs"></div>
            <div class="row">
              <span class="muted small" id="pk-idx"></span>
              <button class="btn btn-sm" id="pk-refresh">${icon('refresh', { size: 13 })} 刷新</button>
            </div>
          </div>
        </div>
        <div id="pk-detail"></div>
        <div id="pk-body" style="margin-top:12px"></div>`;

      const body = root.querySelector('#pk-body');
      const detailHost = root.querySelector('#pk-detail');

      /* ---------------- 顶部统计 ---------------- */
      const renderStats = () => {
        const idx = sum.index_updated ? new Date(String(sum.index_updated).replace(' ', 'T')).toLocaleString('zh-CN', { hour12: false }) : '未知';
        root.querySelector('#pk-stats').innerHTML = `
          <div class="card stat"><div class="stat-label">已安装</div><div class="stat-value">${sum.installed_active}</div><div class="stat-sub">共 ${sum.installed_total} 条记录</div></div>
          <div class="card stat"><div class="stat-label">可升级</div><div class="stat-value" style="color:${sum.upgradable ? 'var(--warn)' : 'var(--ok)'}">${sum.upgradable}</div><div class="stat-sub">${sum.upgradable ? '建议尽快更新' : '已是最新'}</div></div>
          <div class="card stat"><div class="stat-label">版本锁定</div><div class="stat-value">${sum.held}</div><div class="stat-sub">apt-mark hold</div></div>
          <div class="card stat"><div class="stat-label">根分区可用</div><div class="stat-value">${esc(sum.disk.avail || '-')}</div><div class="stat-sub">缓存 ${esc(sum.apt_cache_size || '?')}</div></div>`;
        root.querySelector('#pk-idx').textContent = sum.lock.busy ? 'apt 任务进行中：' + sum.lock.label : '索引更新于 ' + idx;
      };
      renderStats();

      /* ---------------- 页签 ---------------- */
      const renderTabs = () => {
        const host = root.querySelector('#pk-tabs');
        host.innerHTML = TABS.map(([id, label]) => `<button class="btn btn-sm ${S.tab === id ? 'btn-primary' : ''}" data-tab="${id}">${esc(label)}</button>`).join('');
        host.querySelectorAll('[data-tab]').forEach((b) => (b.onclick = () => { S.tab = b.dataset.tab; S.sel.clear(); detailHost.innerHTML = ''; renderTabs(); render(); }));
      };
      renderTabs();

      /* ---------------- 变更执行（统一入口，带进度条） ---------------- */
      async function doAct(action, names, opts) {
        opts = opts || {};
        if (S.busy) return window.toast('已有 apt 任务在执行，请等待完成', 'warn');
        S.busy = true;
        renderStats();
        const title = ACTION_TITLE[action] || action;
        const count = (names || []).length;
        /* 受保护包被拦下且用户确认强制时，把重试放到 finally 之后发起，
           否则外层 finally 会在重试进行中把 S.busy 复位，导致可并发发起第二个 apt 任务 */
        let retry = null;
        try {
          /* CM.pkg.run：POST /pkg/job 启动任务 → 弹出进度窗（进度条/阶段/实时日志）
             → 轮询到完成；受保护包的拦截错误在这里照常抛出，走下方强制分支 */
          const r = await CM.pkg.run({
            action, names: names || [], force: !!opts.force,
            title: count > 1 ? `${title} · ${count} 个包` : title,
          });
          const secs = r.result ? r.result.seconds : r.seconds;
          window.toast(title + ' 完成' + (secs != null ? '（' + secs + 's）' : ''), 'ok', 4000);
          modal.output(title + ' 结果', r.output || '');
        } catch (e) {
          /* 受保护包：首次被服务端拦截后，允许用户显式强制 */
          if (/受保护/.test(String(e.message)) && !opts.force) {
            const okc = await modal.confirm('该操作已被安全策略拦截',
              `<div class="small mb">${esc(String(e.message).split('\n')[0])}</div>
               <div class="small">服务端已阻止该操作。继续执行可能<b style="color:#f87171">导致系统无法启动、SSH 断开或面板失效</b>。</div>`,
              { danger: true, okText: '我已知晓风险，强制执行' });
            if (okc) retry = { action, names, opts: Object.assign({}, opts, { force: true }) };
          } else {
            window.toast(String(e.message).split('\n')[0], 'err', 8000);
            if (e.job) modal.output(title + ' 失败', String(e.message), { error: true });
          }
        } finally {
          S.busy = false;
          S.recData = null;
          S.srcCache = null;
          S.sel.clear();
          sum = await CM.api.get('/pkg/summary', 30000).catch(() => sum);
          renderStats();
          render();
        }
        if (retry) return doAct(retry.action, retry.names, retry.opts);
      }

      /* 预览 → 展示变更 → 确认 → 执行 */
      async function previewAndAct(action, names, opts) {
        opts = opts || {};
        let pv;
        try { pv = await CM.api.post('/pkg/preview', { action, names: names || [] }, 180000); }
        catch (e) { return window.toast(String(e.message).split('\n')[0], 'err', 7000); }
        const lines = String(pv.output || '').trim();
        const plan = /(\d+) upgraded, (\d+) newly installed, (\d+) to remove/.exec(lines);
        const html = `
          ${(names || []).length ? `<div class="small mono mb">${esc((names || []).join(', '))}</div>` : ''}
          ${plan ? `<div class="row mb" style="gap:16px"><span>${plan[1]} 升级</span><span>${plan[2]} 新装</span>
            <span><b style="color:${plan[3] ? '#f87171' : 'var(--ok)'}">${plan[3]} 移除</b></span></div>` : ''}
          ${opts.warn ? `<div class="small mb" style="border:1px solid rgba(234,179,8,.4);background:rgba(234,179,8,.07);color:var(--warn);border-radius:3px;padding:7px 9px">${opts.warn}</div>` : ''}
          <pre class="pre" style="max-height:44vh">${esc(lines || '(无变更)')}</pre>`;
        const okc = await modal.confirm(ACTION_TITLE[action] + ' · 变更预览', html, { danger: RISKY.includes(action), okText: '执行' });
        if (!okc) return;
        return doAct(action, names, opts);
      }

      async function removeFlow(names, action, rdeps) {
        const warn = rdeps && rdeps.length
          ? '以下已安装包依赖它，卸载可能连带移除或导致异常：<br>' + esc(rdeps.slice(0, 14).join(', '))
          : '';
        return previewAndAct(action, names, { warn });
      }

      /* ---------------- 批量勾选（安装 / 卸载） ----------------
         S.sel: Map<包名, 是否已安装>；选择条按已装状态给出「批量安装/批量卸载」 */
      function renderSelBar(hostEl, mode) {
        const bar = hostEl.querySelector('[data-selbar]');
        if (!bar) return;
        const n = S.sel.size;
        let actions = '';
        if (mode === 'installed') {
          if (n) actions = `<button class="btn btn-sm btn-danger" data-sel-act="remove">批量卸载 (${n})</button>`;
        } else {
          const ins = [...S.sel].filter(([, v]) => !v).length;
          const rem = n - ins;
          if (ins) actions += `<button class="btn btn-sm btn-primary" data-sel-act="install">批量安装 (${ins})</button>`;
          if (rem) actions += `<button class="btn btn-sm btn-danger" data-sel-act="remove">批量卸载 (${rem})</button>`;
        }
        bar.innerHTML = n
          ? `<span class="selbar-count">已选 <b>${n}</b> 个包</span>${actions}
             <button class="btn btn-sm" data-sel-act="clear">取消选择</button>`
          : `<span class="muted small">勾选后可批量操作：执行前照常预览变更，过程中显示总进度条与实时日志（单次最多 500 个包，自动分批）</span>`;
        bar.querySelectorAll('[data-sel-act]').forEach((b) => {
          b.onclick = () => {
            const a = b.dataset.selAct;
            if (a === 'clear') {
              S.sel.clear();
              hostEl.querySelectorAll('input[data-cb]').forEach((cb) => { cb.checked = false; });
              const all = hostEl.querySelector('input[data-cb-all]');
              if (all) all.checked = false;
              renderSelBar(hostEl, mode);
              return;
            }
            const names = a === 'install' ? [...S.sel].filter(([, v]) => !v).map(([k]) => k)
              : a === 'remove' ? [...S.sel].filter(([, v]) => v).map(([k]) => k)
                : [...S.sel.keys()];
            if (!names.length) return window.toast('没有可执行该操作的包', 'warn');
            if (names.length > 500) return window.toast('一次最多 500 个包，请分批选择', 'warn');
            previewAndAct(a, names);
          };
        });
      }

      /* 绑定当前容器内的复选框与全选框，mode 同 renderSelBar */
      function bindSel(hostEl, mode) {
        hostEl.querySelectorAll('input[data-cb]').forEach((cb) => {
          cb.onchange = () => {
            if (cb.checked) S.sel.set(cb.dataset.cb, cb.dataset.inst !== '0');
            else S.sel.delete(cb.dataset.cb);
            renderSelBar(hostEl, mode);
          };
        });
        const all = hostEl.querySelector('input[data-cb-all]');
        if (all) all.onchange = () => {
          hostEl.querySelectorAll('input[data-cb]').forEach((cb) => {
            cb.checked = all.checked;
            if (all.checked) S.sel.set(cb.dataset.cb, cb.dataset.inst !== '0');
            else S.sel.delete(cb.dataset.cb);
          });
          renderSelBar(hostEl, mode);
        };
        renderSelBar(hostEl, mode);
      }

      /* ---------------- 详情面板（内联，避免操作 modal DOM） ---------------- */
      async function showDetail(name) {
        let d;
        try { d = await CM.api.get('/pkg/detail?name=' + encodeURIComponent(name), 40000); }
        catch (e) { return window.toast(e.message, 'err'); }
        S.detail = d;
        const prot = d.protected === 'critical' ? ui.badge('系统关键组件', 'bad') : d.protected === 'protected' ? ui.badge('面板依赖', 'warn') : '';
        detailHost.innerHTML = `<div class="card mb">
          <div class="card-title">${icon('package', { size: 14 })} ${esc(d.name)} ${prot}
            <div class="right">
              ${d.installed ? `<button class="btn btn-sm" data-da="hold">锁定版本</button><button class="btn btn-sm" data-da="unhold">解除锁定</button><button class="btn btn-sm btn-danger" data-da="remove">卸载</button>`
            : `<button class="btn btn-sm btn-primary" data-da="install">安装</button>`}
              <button class="btn btn-sm btn-ghost" data-da="close">关闭</button>
            </div>
          </div>
          <div class="grid g2" style="gap:10px">
            <div>
              <dl class="kv">
                <dt>版本</dt><dd class="mono">${esc(d.version || '-')}</dd>
                <dt>候选</dt><dd class="mono">${esc(d.candidate || '-')}${d.pinned ? ui.badge('已固定', 'info') : ''}</dd>
                <dt>已装版本</dt><dd class="mono">${d.installed ? esc(d.installed_version) + (d.upgradable ? ' <span style="color:var(--warn)">有新版本</span>' : '') : '<span class="muted">未安装</span>'}</dd>
                <dt>分组</dt><dd>${esc(d.section || '-')}</dd>
                <dt>安装大小</dt><dd>${esc(d.size)}${d.file_count ? ' · ' + d.file_count + ' 个文件' : ''}</dd>
                <dt>主页</dt><dd class="wrap">${d.homepage ? esc(d.homepage) : '-'}</dd>
              </dl>
            </div>
            <div>
              <div class="muted small mb">摘要</div>
              <div class="small" style="line-height:1.7">${esc(d.summary || '-')}</div>
              <div class="muted small mt mb">依赖 ${d.depends.length} · 推荐依赖 ${d.recommends.length} · 反向依赖 ${d.reverse_deps.length}</div>
              ${d.reverse_deps.length ? `<div class="muted small mb">被依赖：${esc(d.reverse_deps.slice(0, 10).join(', '))}${d.reverse_deps.length > 10 ? ' …' : ''}</div>` : ''}
              <details><summary class="muted small" style="cursor:pointer">完整说明与依赖列表</summary>
                <pre class="pre mt" style="max-height:280px">${esc((d.description || '') + '\n\n--- 依赖 ---\n' + (d.depends.join('\n') || '-') + '\n\n--- 推荐 ---\n' + (d.recommends.slice(0, 30).join('\n') || '-'))}</pre>
              </details>
            </div>
          </div>
        </div>`;
        detailHost.querySelectorAll('[data-da]').forEach((b) => (b.onclick = () => {
          const a = b.dataset.da;
          if (a === 'close') { detailHost.innerHTML = ''; return; }
          if (a === 'remove') return removeFlow([d.name], 'remove', d.reverse_deps);
          if (a === 'install') return previewAndAct('install', [d.name]);
          return doAct(a, [d.name]);
        }));
        detailHost.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      }

      /* ---------------- 各页签渲染 ---------------- */
      /* 渲染序号：任何一次重新渲染都会让先前的 await 回调失效，
         避免"切页签后旧请求回来操作已销毁的 DOM"导致 null 报错 */
      let renderSeq = 0;
      async function render() {
        const seq = ++renderSeq;
        detailHost.innerHTML = '';
        if (S.tab === 'search') return renderSearch(seq);
        if (S.tab === 'installed') return renderInstalled(seq);
        if (S.tab === 'upgradable') return renderUpgradable(seq);
        if (S.tab === 'recommended') return renderRecommended(seq);
        if (S.tab === 'maintain') return renderMaintain(seq);
        if (S.tab === 'sources') return renderSources(seq);
      }
      /* 安全取元素：元素可能已被后续渲染替换 */
      function pick(sel) { return body.querySelector(sel); }
      /* 安全绑定事件：元素缺失时静默跳过，不再抛 "Cannot set properties of null" */
      function bind(sel, evt, fn) { const e = body.querySelector(sel); if (e) e[evt] = fn; return !!e; }

      /* ---- 搜索安装（支持指定源） ---- */
      async function sourceOptions() {
        if (S.srcCache) return S.srcCache;
        try {
          const d = await CM.api.get('/pkg/sources', 30000);
          /* 收集所有启用源（主仓库 + 安全仓库），按镜像名去重 */
          const map = new Map();
          for (const f of d.files || []) {
            for (const s of f.stanzas || []) {
              if (!s.enabled || !s.uris || !s.uris.length) continue;
              const uri = s.uris[0];
              if (!map.has(uri)) map.set(uri, { uri, suites: s.suites || [], components: s.components || [], file: f.path });
            }
          }
          S.srcCache = { codename: d.codename, current: d.current, list: [...map.values()] };
        } catch (_) { S.srcCache = { codename: '', current: null, list: [] }; }
        return S.srcCache;
      }

      async function renderSearch(seq) {
        const so = await sourceOptions();
        if (seq !== renderSeq) return;
        body.innerHTML = `
          <div class="card mb">
            <div class="row mb">
              <input class="inp" id="pk-q" placeholder="搜索软件包：名称或描述，如 nginx / htop / python3" style="flex:1" value="${esc(S.q)}">
              <select class="inp" id="pk-src" style="width:290px">
                <option value="">全部源（全局搜索）</option>
                ${(so.list || []).map((x) => `<option value="${esc(x.uri)}" ${x.uri === S.srcUri ? 'selected' : ''}>${esc(x.uri.replace(/^https?:\/\//, ''))}</option>`).join('')}
              </select>
              <button class="btn btn-primary" id="pk-go">${icon('search', { size: 14 })} 搜索</button>
            </div>
            <div class="muted small" id="pk-srcinfo"></div>
          </div>
          <div id="pk-results"></div>`;
        const q = body.querySelector('#pk-q');
        const sel = body.querySelector('#pk-src');
        const info = body.querySelector('#pk-srcinfo');
        const paintInfo = () => {
          if (!S.srcUri) {
            info.innerHTML = `搜索范围：<b>全部已启用源</b>（共 ${(so.list || []).length} 个源），按名称与描述匹配。到「软件源」页可切换国内镜像。`;
          } else {
            const one = (so.list || []).find((x) => x.uri === S.srcUri) || {};
            info.innerHTML = `仅搜索源 <b class="mono">${esc(S.srcUri)}</b>${one.suites && one.suites.length ? '（' + esc(one.suites.join(' ')) + '）' : ''} 的软件包。若结果为空，请到「软件源」页更新该源索引。`;
          }
        };
        sel.value = S.srcUri;
        paintInfo();
        const run = () => { S.q = q.value.trim(); doSearch(); };
        bind('#pk-go', 'onclick', run);
        sel.onchange = () => { S.srcUri = sel.value; paintInfo(); if (S.q) doSearch(); else paintInfo(); };
        q.onkeydown = (e) => { if (e.key === 'Enter') run(); };
        q.oninput = CM.debounce(run, 520);
        await doSearch();

        async function doSearch() {
          if (seq !== renderSeq) return;
          const host = pick('#pk-results');
          if (!host) return;
          if (!S.q) {
            host.innerHTML = `<div class="card"><div class="card-title">开始搜索</div><div class="muted small">输入关键词查找软件包，或指定某个源后仅在该源内搜索。</div></div>`;
            return;
          }
          host.innerHTML = `<div class="card">${ui.skeleton(6)}</div>`;
          let d;
          try {
            d = S.srcUri
              ? await CM.api.get('/pkg/search-in-source?q=' + encodeURIComponent(S.q) + '&uri=' + encodeURIComponent(S.srcUri) + '&limit=100', 120000)
              : await CM.api.get('/pkg/search?q=' + encodeURIComponent(S.q) + '&limit=100', 60000);
          } catch (e) {
            if (seq !== renderSeq) return;
            host.innerHTML = `<div class="card"><div class="muted">${esc(e.message)}</div></div>`;
            return;
          }
          /* 等待期间可能已切换页签/重渲染，宿主节点可能已被替换 */
          if (seq !== renderSeq || host !== pick('#pk-results')) return;
          if (!d.total) {
            host.innerHTML = `<div class="card"><div class="muted small">${S.srcUri ? '该源中' : ''}未找到与 “${esc(S.q)}” 匹配的软件包。</div></div>`;
            return;
          }
          const scanned = d.scanned_files ? ` · 已扫描 ${d.scanned_files} 个索引文件` : '';
          host.innerHTML = `<div class="card"><div class="card-title">搜索结果 (${d.total})${S.srcUri ? ' · 指定源' : ''}${scanned}${d.truncated ? ' · 已截断' : ''}</div>
            <div class="selbar" data-selbar></div>
            ${ui.table([
            { k: 'name', label: ' ', cls: 'col-cb', render: (v, r) => `<input type="checkbox" data-cb="${esc(v)}" data-inst="${r.installed ? '1' : '0'}" ${S.sel.has(v) ? 'checked' : ''}>` },
            { k: 'name', label: '包名', cls: 'mono' },
            { k: 'version', label: '版本', cls: 'mono small', render: (v) => esc(v || '-') },
            { k: 'description', label: '描述', cls: 'wrap', render: (v) => esc(String(v || '').slice(0, 140)) },
            S.srcUri ? { k: 'section', label: '分组', cls: 'small', render: (v) => esc(v || '-') } : { k: 'size_kb', label: '已占', cls: 'num', render: (v) => kb(v) },
            { k: 'installed', label: '状态', render: (v) => (v ? ui.badge('已安装', 'ok') : ui.badge('未安装', 'gray')) },
            {
              k: 'name', label: '操作', render: (v, r) => `<div class="row" style="gap:5px;flex-wrap:nowrap">
                <button class="btn btn-sm" data-det="${esc(v)}">详情</button>
                ${r.installed ? `<button class="btn btn-sm btn-danger" data-rm="${esc(v)}">卸载</button>`
            : `<button class="btn btn-sm btn-primary" data-in="${esc(v)}">安装</button>`}</div>`,
            },
          ], d.results, { style: 'max-height:62vh' })}</div>`;
          const sth = host.querySelector('table.tbl thead th');
          if (sth) { sth.innerHTML = '<input type="checkbox" data-cb-all title="全选本页">'; sth.classList.add('col-cb'); }
          bindSel(host, 'mixed');
          host.querySelectorAll('[data-det]').forEach((b) => (b.onclick = () => showDetail(b.dataset.det)));
          host.querySelectorAll('[data-in]').forEach((b) => (b.onclick = () => previewAndAct('install', [b.dataset.in])));
          host.querySelectorAll('[data-rm]').forEach((b) => (b.onclick = async () => {
            let rd = [];
            try { rd = (await CM.api.get('/pkg/detail?name=' + encodeURIComponent(b.dataset.rm), 40000)).reverse_deps; } catch (_) { }
            removeFlow([b.dataset.rm], 'remove', rd);
          }));
        }
      }

      /* ---- 已安装 ---- */
      async function renderInstalled(seq) {
        body.innerHTML = `
          <div class="card mb">
            <div class="row">
              <input class="inp" id="pi-q" placeholder="过滤已安装包（名称或分组）" style="flex:1" value="${esc(S.iq)}">
              <select class="inp" id="pi-sec" style="width:180px"><option value="">全部分组</option></select>
              <select class="inp" id="pi-sort" style="width:130px"><option value="name">按名称</option><option value="size_kb">按大小</option><option value="section">按分组</option><option value="version">按版本</option></select>
              <select class="inp" id="pi-dir" style="width:100px"><option value="asc">升序</option><option value="desc">降序</option></select>
            </div>
          </div>
          <div id="pi-list"></div>`;
        const host = body.querySelector('#pi-list');
        body.querySelector('#pi-sec').value = S.section;
        body.querySelector('#pi-sort').value = S.sort;
        body.querySelector('#pi-dir').value = S.dir;
        const load = async () => {
          if (seq !== renderSeq || !host.isConnected) return;
          host.innerHTML = `<div class="card">${ui.skeleton(8)}</div>`;
          let d;
          try {
            d = await CM.api.get(`/pkg/installed?q=${encodeURIComponent(S.iq)}&section=${encodeURIComponent(S.section)}&sort=${S.sort}&dir=${S.dir}&limit=50&offset=${S.page * 50}`, 60000);
          } catch (e) {
            if (seq !== renderSeq) return;
            host.innerHTML = `<div class="card"><div class="muted">${esc(e.message)}</div></div>`;
            return;
          }
          /* 等待期间可能已切换页签：此时宿主节点已脱离文档，不再写 DOM */
          if (seq !== renderSeq || !host.isConnected) return;
          const secSel = body.querySelector('#pi-sec');
          secSel.innerHTML = '<option value="">全部分组</option>' + d.sections.map((s) => `<option ${s === S.section ? 'selected' : ''}>${esc(s)}</option>`).join('');
          const pages = Math.max(1, Math.ceil(d.total / d.limit));
          host.innerHTML = `<div class="card">
            <div class="card-title">已安装 (${d.total}) <div class="right muted small">第 ${d.total ? d.offset + 1 : 0}-${Math.min(d.offset + d.limit, d.total)} 条</div></div>
            <div class="selbar" data-selbar></div>
            ${ui.table([
            { k: 'name', label: ' ', cls: 'col-cb', render: (v) => `<input type="checkbox" data-cb="${esc(v)}" data-inst="1" ${S.sel.has(v) ? 'checked' : ''}>` },
            { k: 'name', label: '包名', cls: 'mono' },
            { k: 'version', label: '版本', cls: 'mono small' },
            { k: 'section', label: '分组', cls: 'small' },
            { k: 'size', label: '大小', cls: 'num' },
            { k: 'status', label: '状态', render: (v) => (String(v || '').indexOf('ii') === 0 ? ui.badge('已装', 'ok') : ui.badge(String(v || '').trim() || '?', 'gray')) },
            {
              k: 'name', label: '操作', render: (v) => `<div class="row" style="gap:5px;flex-wrap:nowrap">
                <button class="btn btn-sm" data-det2="${esc(v)}">详情</button>
                <button class="btn btn-sm btn-danger" data-rm2="${esc(v)}">卸载</button></div>`,
            },
          ], d.rows, { style: 'max-height:56vh' })}
            <div class="row mt" style="justify-content:flex-end">
              <button class="btn btn-sm" id="pi-prev">上一页</button>
              <span class="muted small">${S.page + 1} / ${pages}</span>
              <button class="btn btn-sm" id="pi-next">下一页</button>
            </div></div>`;
          /* 表头复选框：ui.table 会对 label 转义，这里渲染后注入 */
          const pth = host.querySelector('table.tbl thead th');
          if (pth) { pth.innerHTML = '<input type="checkbox" data-cb-all title="全选本页">'; pth.classList.add('col-cb'); }
          bindSel(host, 'installed');
          /* 上一页/下一页：用 pick 兜底，防止 DOM 已被替换时抛 null 错误 */
          const prevBtn = host.querySelector('#pi-prev') || pick('#pi-prev');
          const nextBtn = host.querySelector('#pi-next') || pick('#pi-next');
          if (prevBtn) prevBtn.onclick = () => { if (S.page > 0) { S.page--; load(); } };
          if (nextBtn) nextBtn.onclick = () => { if (S.page + 1 < pages) { S.page++; load(); } };
          host.querySelectorAll('[data-det2]').forEach((b) => (b.onclick = () => showDetail(b.dataset.det2)));
          host.querySelectorAll('[data-rm2]').forEach((b) => (b.onclick = async () => {
            let rd = [];
            try { rd = (await CM.api.get('/pkg/detail?name=' + encodeURIComponent(b.dataset.rm2), 40000)).reverse_deps; } catch (_) { }
            removeFlow([b.dataset.rm2], 'remove', rd);
          }));
        };
        const q = body.querySelector('#pi-q');
        bind('#pi-sec', 'onchange', (e) => { S.section = e.target.value; S.page = 0; load(); });
        bind('#pi-sort', 'onchange', (e) => { S.sort = e.target.value; load(); });
        bind('#pi-dir', 'onchange', (e) => { S.dir = e.target.value; load(); });
        q.onkeydown = (e) => { if (e.key === 'Enter') { S.iq = q.value.trim(); S.page = 0; load(); } };
        q.oninput = CM.debounce(() => { S.iq = q.value.trim(); S.page = 0; load(); }, 420);
        await load();
      }

      /* ---- 可升级 ---- */
      async function renderUpgradable(seq) {
        body.innerHTML = `<div class="card">${ui.skeleton(6)}</div>`;
        let d;
        try { d = await CM.api.get('/pkg/upgradable', 60000); }
        catch (e) {
          if (seq !== renderSeq) return;
          body.innerHTML = `<div class="card"><div class="muted">${esc(e.message)}</div></div>`;
          return;
        }
        if (seq !== renderSeq) return;
        body.innerHTML = `<div class="card">
          <div class="card-title">可升级 (${d.total})
            <div class="right">
              <button class="btn btn-sm btn-primary" id="pu-all">${icon('upload', { size: 13 })} 全部安全升级</button>
              <button class="btn btn-sm" id="pu-full">完整升级（含移除）</button>
            </div>
          </div>
          ${d.total ? ui.table([
          { k: 'name', label: '包名', cls: 'mono' },
          { k: 'current', label: '当前版本', cls: 'mono small' },
          { k: 'candidate', label: '目标版本', cls: 'mono small', render: (v) => '<span style="color:var(--ok)">' + esc(v) + '</span>' },
          { k: 'suite', label: '来源', cls: 'small' },
          { k: 'name', label: '操作', render: (v) => `<button class="btn btn-sm btn-primary" data-up="${esc(v)}">升级</button>` },
        ], d.rows) : '<div class="muted small">所有软件包均为最新版本。</div>'}
        </div>
        <div class="card mt"><div class="muted small">「安全升级」只做向上兼容的升级，不卸载其它包；「完整升级」允许移除冲突包，风险更高，执行前会展示变更预览。</div></div>`;
        bind('#pu-all', 'onclick', () => previewAndAct('upgrade', []));
        const fullBtn = body.querySelector('#pu-full');
        if (fullBtn) fullBtn.onclick = () => previewAndAct('full-upgrade', [], { warn: '完整升级可能移除与新版本冲突的软件包，请仔细核对预览内容。' });
        body.querySelectorAll('[data-up]').forEach((b) => (b.onclick = () => previewAndAct('upgrade', [b.dataset.up])));
      }

      /* ---- 常用推荐（按分类） ---- */
      async function renderRecommended(seq) {
        body.innerHTML = `<div class="card">${ui.skeleton(8)}</div>`;
        let d;
        try {
          /* 分类数据较静态，切换分类时复用缓存；变更操作后会失效 */
          if (!S.recData) S.recData = await CM.api.get('/pkg/recommended', 40000);
          d = S.recData;
        } catch (e) {
          if (seq !== renderSeq) return;
          body.innerHTML = `<div class="card"><div class="muted">${esc(e.message)}</div></div>`;
          return;
        }
        if (seq !== renderSeq) return;
        const cats = d.categories || [];
        if (S.recCat === undefined) S.recCat = 'all';
        const cur = cats.find((c) => c.id === S.recCat);
        const shown = S.recCat === 'all' ? cats : (cur ? [cur] : cats);

        body.innerHTML = `
          <div class="card mb" style="padding:11px 13px">
            <div class="row between">
              <div class="row">
                <span class="muted small">共 <b>${d.total}</b> 个常用包 · 已装 <b style="color:var(--ok)">${d.installed_total}</b> · ${d.category_count} 个分类</span>
              </div>
              <div class="row">
                <input class="inp" id="pr-q" placeholder="在推荐中过滤" style="width:180px">
              </div>
            </div>
          </div>
          <div class="card mb" style="padding:10px 12px">
            <div class="row" id="pr-cats">
              <button class="btn btn-sm ${S.recCat === 'all' ? 'btn-primary' : ''}" data-cat="all">全部 (${d.total})</button>
              ${cats.map((c) => `<button class="btn btn-sm ${S.recCat === c.id ? 'btn-primary' : ''}" data-cat="${esc(c.id)}">${esc(c.label)} <span class="muted">${c.installed_count}/${c.total}</span></button>`).join('')}
            </div>
          </div>
          <div class="selbar mb" data-selbar></div>
          <div id="pr-list"></div>`;

        const list = body.querySelector('#pr-list');
        const paint = () => {
          const box = pick('#pr-q');
          if (!box || !list.isConnected) return;
          const kw = (box.value || '').trim().toLowerCase();
          const groups = shown.map((c) => {
            const rows = kw ? c.rows.filter((r) => r.name.toLowerCase().includes(kw) || String(r.desc).toLowerCase().includes(kw)) : c.rows;
            if (!rows.length) return '';
            return `<div class="card mb">
              <div class="card-title">${icon(c.icon || 'cube', { size: 13 })} ${esc(c.label)}
                <div class="right muted small">${rows.length} 个 · 已装 ${rows.filter((r) => r.installed).length}</div>
              </div>
              <div class="grid g3" style="gap:8px">
                ${rows.map((r) => `<div class="card" style="padding:10px 11px">
                  <div class="row between">
                    <span class="row" style="gap:7px">
                      <input type="checkbox" data-cb="${esc(r.name)}" data-inst="${r.installed ? '1' : '0'}" ${S.sel.has(r.name) ? 'checked' : ''}>
                      <span class="mono" style="font-size:12.5px">${esc(r.name)}</span>
                    </span>
                    ${r.held ? ui.badge('已锁定', 'warn') : r.installed ? ui.badge('已安装', 'ok') : ui.badge('未安装', 'gray')}
                  </div>
                  <div class="muted small mt" style="min-height:30px">${esc(r.desc)}</div>
                  <div class="row mt">
                    ${r.installed
              ? `<button class="btn btn-sm" data-det3="${esc(r.name)}">详情</button><button class="btn btn-sm btn-danger" data-rm3="${esc(r.name)}">卸载</button>`
              : `<button class="btn btn-sm btn-primary" data-in3="${esc(r.name)}">${icon('download', { size: 12 })} 安装</button><button class="btn btn-sm" data-det3="${esc(r.name)}">详情</button>`}
                  </div>
                </div>`).join('')}
              </div>
            </div>`;
          }).join('');
          list.innerHTML = groups || '<div class="card"><div class="muted small">没有匹配的包</div></div>';
          list.querySelectorAll('[data-in3]').forEach((b) => (b.onclick = () => previewAndAct('install', [b.dataset.in3])));
          list.querySelectorAll('[data-rm3]').forEach((b) => (b.onclick = () => removeFlow([b.dataset.rm3], 'remove')));
          list.querySelectorAll('[data-det3]').forEach((b) => (b.onclick = () => showDetail(b.dataset.det3)));
          bindSel(body, 'mixed');
        };
        paint();

        const catsHost = pick('#pr-cats');
        const qBox = pick('#pr-q');
        if (catsHost) catsHost.querySelectorAll('[data-cat]').forEach((b) => (b.onclick = () => {
          S.recCat = b.dataset.cat;
          renderRecommended(++renderSeq);
        }));
        if (qBox) qBox.oninput = CM.debounce(paint, 260);
      }

      /* ---- 维护操作 ---- */
      async function renderMaintain(seq) {
        const holds = (sum.hold_list || []).join('、');
        /* 日志目录由服务端下发，前端不硬编码路径 */
        const logDir = (CM.me && CM.me.env && CM.me.env.paths && CM.me.env.paths.logs) || '服务端日志目录';
        body.innerHTML = `
          <div class="grid g2">
            <div class="card"><div class="card-title">${icon('refresh', { size: 13 })} 软件源索引</div>
              <div class="muted small mb">从镜像站拉取最新软件包列表，安装新包前建议先刷新。</div>
              <div class="muted small mb">当前索引更新于 <b>${esc(sum.index_updated || '未知')}</b></div>
              <button class="btn btn-sm btn-primary" id="pm-update">更新索引</button>
            </div>
            <div class="card"><div class="card-title">${icon('trash', { size: 13 })} 缓存清理</div>
              <div class="muted small mb">删除已下载的 .deb 安装包，释放 <b>${esc(sum.apt_cache_size || '?')}</b>。不影响已安装的软件。</div>
              <button class="btn btn-sm" id="pm-clean">清理 apt 缓存</button>
            </div>
            <div class="card"><div class="card-title">孤立依赖清理</div>
              <div class="muted small mb">移除不再被需要的自动安装依赖（apt autoremove），执行前会展示预览。</div>
              <button class="btn btn-sm btn-danger" id="pm-auto">查看可清理项</button>
            </div>
            <div class="card"><div class="card-title">版本锁定</div>
              <div class="muted small mb">锁定的包不会被自动升级。当前 ${sum.held} 个${holds ? '：' + esc(holds) : ''}。</div>
              ${sum.hold_list && sum.hold_list.length ? `<button class="btn btn-sm" id="pm-unhold">全部解除锁定</button>` : '<div class="muted small">当前没有锁定的包</div>'}
            </div>
          </div>
          <div class="card mt"><div class="card-title">操作日志</div>
            <div class="muted small">每次变更的完整 apt 输出保存在服务器 <code>${esc(logDir)}/pkg-*.log</code>。</div></div>`;
        bind('#pm-update', 'onclick', () => doAct('update', []));
        bind('#pm-clean', 'onclick', () => doAct('clean', []));
        bind('#pm-auto', 'onclick', async () => {
          let d;
          try { d = await CM.api.get('/pkg/autoremove', 150000); } catch (e) { return window.toast(e.message, 'err'); }
          modal.output('可清理的孤立依赖（apt-get -s autoremove）', d.output);
          const hasWork = /\d+ to remove|REMOVED/.test(d.output);
          if (hasWork) {
            const okc = await modal.confirm('执行清理', '确认执行 <b>apt autoremove --purge</b>？', { danger: true, okText: '执行' });
            if (okc) doAct('autoremove', []);
          }
        });
        const uh = pick('#pm-unhold');
        if (uh) uh.onclick = async () => {
          const okc = await modal.confirm('解除锁定', `解除 <b>${esc(holds)}</b> 的版本锁定？这些包将恢复自动升级。`, { okText: '解除' });
          if (okc) doAct('unhold', sum.hold_list);
        };
      }

      /* ---- 软件源（镜像切换中心） ---- */
      async function renderSources(seq) {
        body.innerHTML = `<div class="card">${ui.skeleton(6)}</div>`;
        let d, bk;
        try {
          [d, bk] = await Promise.all([CM.api.get('/pkg/sources', 30000), CM.api.get('/pkg/source-backups', 20000).catch(() => ({ total: 0, items: [] }))]);
        } catch (e) {
          if (seq !== renderSeq) return;
          body.innerHTML = `<div class="card">
            <div class="card-title">${icon('warning', { size: 13 })} 当前系统不支持软件源切换</div>
            <div class="muted small mb">${esc(e.message)}</div>
            <div class="muted small">镜像切换是 Debian/Ubuntu 专有功能（apt sources.list）。其它发行版请使用系统自带的软件源工具。</div>
          </div>`;
          return;
        }
        if (seq !== renderSeq) return;
        const cur = d.current || {};
        const lat = {};
        body.innerHTML = `
          <div class="card mb">
            <div class="card-title">${icon('link', { size: 13 })} 当前镜像
              <div class="right"><button class="btn btn-sm" id="ps-refresh">${icon('refresh', { size: 12 })} 刷新状态</button></div>
            </div>
            <div class="grid g2">
              <div>
                <dl class="kv">
                  <dt>主仓库</dt><dd>${esc(cur.name || '未识别')} <span class="mono muted">${esc(cur.uri || '-')}</span></dd>
                  <dt>发行版</dt><dd class="mono">${esc(d.codename || '-')}</dd>
                  <dt>安全更新</dt><dd>${esc(cur.security_mirror || '同主仓库' || '-')}${cur.security_uri ? ' <span class="mono muted">' + esc(cur.security_uri) + '</span>' : ''}</dd>
                  <dt>源文件</dt><dd class="mono small">${esc(cur.file || '-')}</dd>
                </dl>
              </div>
              <div class="muted small" style="line-height:1.9">
                · 切换镜像会<b>先备份</b>源文件，改写后立即执行 <code>apt-get update</code>；<b>若刷新失败会自动回滚</b>到原镜像。<br>
                · 切换成功后会清理旧镜像残留索引，避免搜索结果串源。<br>
                · 阿里云 ECS 建议使用「阿里云(内网源)」，走内网不消耗公网流量。
              </div>
            </div>
          </div>

          <div class="card mb">
            <div class="card-title">可选镜像 <div class="right">
              <button class="btn btn-sm" id="ps-testall">${icon('activity', { size: 12 })} 测速全部</button>
            </div></div>
            <div class="grid g3" id="ps-mirrors" style="gap:9px"></div>
          </div>

          <div class="grid g2">
            <div class="card">
              <div class="card-title">已启用的源文件 (${(d.files || []).length})</div>
              ${(d.files || []).map((f) => `<div class="mb">
                <div class="mono small mb">${esc(f.name)}</div>
                ${f.stanzas.map((s, i) => `<div class="small mb" style="border-left:2px solid ${s.enabled ? 'var(--ok)' : 'var(--muted)'};padding-left:8px">
                  <div>${esc(s.types)} · <span class="mono">${esc(s.uris.join(' '))}</span></div>
                  <div class="muted">${esc(s.suites.join(' '))} [${esc(s.components.join(' '))}]${s.signed_by ? ' · 签名 ' + esc(s.signed_by.split('/').pop()) : ''}</div>
                </div>`).join('')}
              </div>`).join('') || '<div class="muted small">未发现源文件</div>'}
            </div>
            <div class="card">
              <div class="card-title">源文件备份 (${bk.total || 0})</div>
              <div class="muted small mb">每次切换镜像前自动备份，路径 <code>${esc(bk.dir || '-')}</code></div>
              ${(bk.items || []).length ? `<pre class="pre" style="max-height:220px">${esc(bk.items.join('\n'))}</pre>` : '<div class="muted small">暂无备份</div>'}
              <div class="muted small mt">如需回滚，可通过 Web 终端把备份文件复制回 <code>/etc/apt/sources.list.d/</code> 后执行「更新索引」。</div>
            </div>
          </div>`;

        /* 镜像卡片 */
        const mhost = body.querySelector('#ps-mirrors');
        const paint = () => {
          mhost.innerHTML = (d.mirrors || []).map((m) => {
            const isCur = cur.mirror_id === m.id;
            const l = lat[m.id];
            return `<div class="card" style="padding:11px 12px;${isCur ? 'border-color:var(--accent)' : ''}">
              <div class="row between"><span style="font-size:12.5px">${esc(m.name)}</span>
                ${isCur ? ui.badge('当前', 'ok') : ''}</div>
              <div class="muted small mono mt" style="word-break:break-all">${esc(m.url)}</div>
              <div class="muted small mt">${esc(m.region)} · ${esc(m.desc)}</div>
              <div class="row mt">
                <button class="btn btn-sm" data-lat="${esc(m.id)}">${l != null ? l + ' ms' : '测速'}</button>
                ${isCur ? '' : `<button class="btn btn-sm btn-primary" data-sw="${esc(m.id)}">切换到此源</button>`}
              </div>
            </div>`;
          }).join('');
          mhost.querySelectorAll('[data-lat]').forEach((b) => (b.onclick = async () => {
            ui.busy(b, true);
            const m = (d.mirrors || []).find((x) => x.id === b.dataset.lat);
            try {
              const r = await CM.api.get('/pkg/test-mirror?url=' + encodeURIComponent(m.url), 30000);
              lat[m.id] = r.reachable ? r.latency_ms : '不可达';
            } catch (_) { lat[m.id] = '失败'; }
            paint();
          }));
          mhost.querySelectorAll('[data-sw]').forEach((b) => (b.onclick = async () => {
            const m = (d.mirrors || []).find((x) => x.id === b.dataset.sw);
            const okc = await modal.confirm('切换软件源',
              `从 <b class="mono">${esc(cur.uri || '当前')}</b> 切换到：<br><br>
               <b>${esc(m.name)}</b> <span class="mono small">${esc(m.url)}</span><br>
               <span class="muted small">${esc(m.region)} · ${esc(m.desc)}</span><br><br>
               将自动备份当前源文件并执行 <code>apt-get update</code>；若刷新失败会<b>自动回滚</b>。此过程约需 30-120 秒。`,
              { okText: '切换并刷新索引' });
            if (!okc) return;
            ui.busy(b, true);
            try {
              const r = await CM.api.post('/pkg/switch-mirror', { mirror: m.id }, 1200000);
              window.toast('已切换到 ' + m.name, 'ok', 5000);
              modal.output('切换软件源', (r.output || '') + (r.backup ? '\n\n原配置备份：' + r.backup : ''));
              S.srcCache = null;
              await renderSources();
            } catch (e) {
              window.toast(String(e.message).split('\n')[0], 'err', 8000);
              modal.output('切换失败（已回滚）', String(e.message));
              ui.busy(b, false);
            }
          }));
        };
        paint();
        const testAllBtn = body.querySelector('#ps-testall');
        if (testAllBtn) testAllBtn.onclick = async (e) => {
          ui.busy(e.currentTarget, true);
          for (const m of d.mirrors || []) {
            if (seq !== renderSeq) { ui.busy(e.currentTarget, false); return; }
            try {
              const r = await CM.api.get('/pkg/test-mirror?url=' + encodeURIComponent(m.url), 25000);
              lat[m.id] = r.reachable ? r.latency_ms : '不可达';
            } catch (_) { lat[m.id] = '失败'; }
            paint();
          }
          ui.busy(e.currentTarget, false);
        };
        const psRefresh = body.querySelector('#ps-refresh');
        if (psRefresh) psRefresh.onclick = () => { S.srcCache = null; renderSources(++renderSeq); };
      }

      /* #pk-refresh 位于页面头部(root 下)，不在 #pk-body 内 */
      const refreshBtn = root.querySelector('#pk-refresh');
      if (refreshBtn) refreshBtn.onclick = async () => {
        S.srcCache = null;
        S.recData = null;
        sum = await CM.api.get('/pkg/summary', 30000).catch(() => sum);
        renderStats();
        render();
      };

      /* 页面加载时若仍有进行中的 apt 任务（例如刚刷新过页面），接上它的进度窗 */
      CM.pkg.resume({ title: (v) => (ACTION_TITLE[v.action] || v.action) + ' · 进行中' })
        .then(async (r) => {
          if (!r) return;
          S.recData = null; S.srcCache = null;
          sum = await CM.api.get('/pkg/summary', 30000).catch(() => sum);
          renderStats(); render();
        }).catch(() => {});

      await render();
    },
  });
})();