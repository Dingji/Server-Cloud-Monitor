/* Logs page */
(function () {
  'use strict';
  let following = false, autoscroll = true, maxLines = 3000;

  function buildSourceOptions(src, extraDocker) {
    const groups = [
      ['files', '经典事件日志'],
      ['special', '汇总视图'],
      ['services', 'Windows 服务'],
      ['processes', '进程 / 应用'],
      ['channels', '其他事件通道'],
      ['panel', '面板与本地文件'],
    ];
    let html = '';
    for (const [key, label] of groups) {
      const arr = src[key];
      if (!arr || !arr.length) continue;
      html += `<optgroup label="${fmt.esc(label)}">${arr.map((f) => `<option value="${fmt.esc(f.id)}">${fmt.esc(f.label)}</option>`).join('')}</optgroup>`;
    }
    if (extraDocker && extraDocker.length) {
      html += `<optgroup label="Docker 容器">${extraDocker.map((c) => {
        const id = c.id || c.Id || c.ID;
        const name = c.name || c.Names || id;
        return `<option value="docker:${fmt.esc(id)}">${fmt.esc(String(name).replace(/^\//, ''))}</option>`;
      }).join('')}</optgroup>`;
    }
    return html || '<option value="app">应用程序</option>';
  }

  function winLevelParam() {
    const ids = ['lg-lv-crit', 'lg-lv-err', 'lg-lv-warn', 'lg-lv-info'];
    const map = { 'lg-lv-crit': 'critical', 'lg-lv-err': 'error', 'lg-lv-warn': 'warning', 'lg-lv-info': 'information' };
    const parts = ids.filter((id) => document.getElementById(id)?.checked).map((id) => map[id]);
    return parts.length ? parts.join(',') : '';
  }

  function tailBody(source) {
    const body = { source, count: CM.isWindows() ? 250 : 500 };
    if (CM.isWindows()) {
      const levels = winLevelParam();
      if (levels) body.levels = levels;
      const prov = document.getElementById('lg-provider');
      if (prov && prov.value.trim()) body.provider = prov.value.trim();
      const kw = document.getElementById('lg-server-kw');
      if (kw && kw.value.trim()) body.keyword = kw.value.trim();
      const eid = document.getElementById('lg-event-ids');
      if (eid && eid.value.trim()) body.eventIds = eid.value.trim();
    }
    return body;
  }

  function addSourceOption(sel, id, label) {
    if ([...sel.options].some((o) => o.value === id)) return;
    sel.appendChild(new Option(label, id));
  }

  function pickSource(id) {
    const sel = document.getElementById('lg-source');
    addSourceOption(sel, id, id.replace(/^evtx:/, ''));
    sel.value = id;
    sel.dispatchEvent(new Event('change'));
  }

  registerPage({
    id: 'logs',
    title: '日志中心',
    async mount(root) {
      const isWin = CM.isWindows();
      const srcP = CM.api.get('/logs/sources');
      const dockerP = isWin ? CM.api.get('/docker').catch(() => null) : Promise.resolve(null);
      const [src, dk] = await Promise.all([srcP, dockerP]);
      const dockerOpts = dk ? (dk.containers || dk.list || []) : [];
      root.innerHTML = `
      <div class="card">
        <div class="card-title">日志查看器
          <div class="right" style="flex-wrap:wrap;gap:6px">
            <select class="inp" id="lg-source" style="max-width:min(420px,45vw)">
              ${buildSourceOptions(src, dockerOpts)}
            </select>
            ${isWin ? `
            <span class="row small muted" style="gap:4px">
              <label><input type="checkbox" id="lg-lv-crit" checked> 严重</label>
              <label><input type="checkbox" id="lg-lv-err" checked> 错误</label>
              <label><input type="checkbox" id="lg-lv-warn" checked> 警告</label>
              <label><input type="checkbox" id="lg-lv-info"> 信息</label>
            </span>
            <input class="inp mono" id="lg-event-ids" placeholder="事件 ID 1000,1001 或 1000-1005" style="width:200px" title="多个用逗号，连续范围用连字符">
            <input class="inp mono" id="lg-provider" placeholder="提供程序/来源" style="width:150px">
            <input class="inp" id="lg-server-kw" placeholder="服务端关键字" style="width:120px">
            <button class="btn btn-sm" id="lg-reload">刷新</button>
            ` : `
            <input class="inp mono" id="lg-unit" placeholder="或 systemd 单元: nginx.service" style="width:220px">
            <button class="btn btn-sm" id="lg-unit-add">加载单元日志</button>`}
            <label class="row small muted"><input type="checkbox" id="lg-follow"> 实时跟随</label>
            <label class="row small muted"><input type="checkbox" id="lg-scroll" checked> 自动滚动</label>
            <input class="inp" id="lg-filter" placeholder="本地过滤…" style="width:130px">
            <button class="btn btn-sm" id="lg-clear">${icon('trash', { size: 13 })} 清屏</button>
          </div>
        </div>
        <div class="muted small mb" style="padding:0 2px">${isWin
          ? 'Windows 事件日志：可按服务、进程、通道、事件 ID 筛选；安全日志需管理员权限。'
          : 'Linux：文件日志与 journalctl 单元。'}</div>
        ${isWin ? `<div class="row mb" style="flex-wrap:wrap;gap:8px;align-items:flex-end">
          <div>
            <div class="muted small">搜索 evtx 通道</div>
            <div class="row" style="gap:6px">
              <input class="inp mono" id="lg-ch-q" placeholder="如 TerminalServices / Dhcp" style="width:260px">
              <button class="btn btn-sm" id="lg-ch-search">搜索</button>
            </div>
          </div>
          <div>
            <div class="muted small">自定义通道（完整名称）</div>
            <div class="row" style="gap:6px">
              <input class="inp mono" id="lg-ch-custom" placeholder="Microsoft-Windows-…/Operational" style="width:320px">
              <button class="btn btn-sm btn-primary" id="lg-ch-open">打开通道</button>
            </div>
          </div>
          <div id="lg-ch-results" class="small" style="flex:1;min-width:200px"></div>
        </div>` : ''}
        <div class="logbox" id="lg-box" style="height:calc(100vh - ${isWin ? '300' : '240'}px);min-height:300px"><span class="muted">选择日志源</span></div>
      </div>`;

      const box = document.getElementById('lg-box');
      let lines = [];

      const renderBox = () => {
        const f = document.getElementById('lg-filter').value.trim().toLowerCase();
        const show = f ? lines.filter((l) => l.toLowerCase().includes(f)) : lines;
        const tail = show.slice(-maxLines);
        box.innerHTML = tail.map((l) => `<div class="ln ${/error|fail|refused|critical|严重/i.test(l) ? 'err' : /warn|警告/i.test(l) ? 'warn' : ''}">${fmt.esc(l)}</div>`).join('') || '<span class="muted">(空)</span>';
        if (autoscroll) box.scrollTop = box.scrollHeight;
      };

      const unsub = () => {
        if (following) { CM.hub.send({ type: 'unsubscribe-logs', source: document.getElementById('lg-source')._cur || '' }); following = false; }
      };

      const loadTail = async (source) => {
        try {
          const r = await CM.api.post('/logs/tail', tailBody(source));
          lines = String(r.output || '').split('\n').filter((l, i, a) => l.length || i < a.length - 1);
          renderBox();
        } catch (e) { window.toast(e.message, 'err'); }
      };

      const setSource = (source) => {
        unsub();
        document.getElementById('lg-source')._cur = source;
        loadTail(source);
        if (document.getElementById('lg-follow').checked) {
          following = true;
          CM.hub.send({ type: 'subscribe-logs', source });
        }
      };

      document.getElementById('lg-source').onchange = (e) => setSource(e.target.value);
      const reloadBtn = document.getElementById('lg-reload');
      if (reloadBtn) reloadBtn.onclick = () => setSource(document.getElementById('lg-source').value);
      if (isWin) {
        ['lg-lv-crit', 'lg-lv-err', 'lg-lv-warn', 'lg-lv-info'].forEach((id) => {
          const el = document.getElementById(id);
          if (el) el.onchange = () => setSource(document.getElementById('lg-source').value);
        });
        const sk = document.getElementById('lg-server-kw');
        if (sk) sk.onkeydown = (e) => { if (e.key === 'Enter') setSource(document.getElementById('lg-source').value); };
        const pv = document.getElementById('lg-provider');
        if (pv) pv.onkeydown = (e) => { if (e.key === 'Enter') setSource(document.getElementById('lg-source').value); };
        const eid = document.getElementById('lg-event-ids');
        if (eid) eid.onkeydown = (e) => { if (e.key === 'Enter') setSource(document.getElementById('lg-source').value); };
        const chSearch = document.getElementById('lg-ch-search');
        const chResults = document.getElementById('lg-ch-results');
        if (chSearch && chResults) {
          chSearch.onclick = async () => {
            const q = document.getElementById('lg-ch-q').value.trim();
            chResults.innerHTML = '<span class="muted">搜索中…</span>';
            try {
              const r = await CM.api.get('/logs/channels?q=' + encodeURIComponent(q));
              const list = r.channels || [];
              if (!list.length) { chResults.innerHTML = '<span class="muted">无匹配通道</span>'; return; }
              chResults.innerHTML = list.slice(0, 12).map((c) =>
                `<button type="button" class="btn btn-sm" data-ch="${fmt.esc(c.id)}" style="margin:2px">${fmt.esc(c.label)}</button>`
              ).join('') + (list.length > 12 ? `<span class="muted"> …共 ${list.length} 条</span>` : '');
              chResults.querySelectorAll('[data-ch]').forEach((b) => {
                b.onclick = () => pickSource(b.getAttribute('data-ch'));
              });
            } catch (e) { chResults.innerHTML = `<span class="muted">${fmt.esc(e.message)}</span>`; }
          };
          document.getElementById('lg-ch-q').onkeydown = (e) => { if (e.key === 'Enter') chSearch.click(); };
        }
        const chOpen = document.getElementById('lg-ch-open');
        if (chOpen) chOpen.onclick = () => {
          const name = document.getElementById('lg-ch-custom').value.trim();
          if (!name) return window.toast('请输入通道完整名称', 'warn');
          const id = name.startsWith('evtx:') ? name : ('evtx:' + name.replace(/^channel:/, ''));
          pickSource(id);
        };
      }
      const unitAdd = document.getElementById('lg-unit-add');
      if (unitAdd) unitAdd.onclick = () => {
        const u = document.getElementById('lg-unit').value.trim();
        if (!u) return window.toast('请输入 systemd 单元名', 'warn');
        const sel = document.getElementById('lg-source');
        let opt = [...sel.options].find((o) => o.value === 'journal:' + u);
        if (!opt) { opt = new Option(' journal: ' + u, 'journal:' + u); sel.appendChild(opt); }
        sel.value = 'journal:' + u;
        setSource('journal:' + u);
      };
      document.getElementById('lg-follow').onchange = (e) => {
        const source = document.getElementById('lg-source')._cur || document.getElementById('lg-source').value;
        if (e.target.checked) { following = true; CM.hub.send({ type: 'subscribe-logs', source }); window.toast('已开启实时跟随', 'ok'); }
        else { CM.hub.send({ type: 'unsubscribe-logs', source }); following = false; }
      };
      document.getElementById('lg-scroll').onchange = (e) => { autoscroll = e.target.checked; };
      document.getElementById('lg-filter').oninput = renderBox;
      document.getElementById('lg-clear').onclick = () => { lines = []; renderBox(); };

      if (!CM._logSubscribed2) {
        CM._logSubscribed2 = true;
        let pending = [];
        let flushTimer = null;
        const flush = () => {
          flushTimer = null;
          if (!pending.length) return;
          lines = lines.concat(pending.splice(0));
          if (lines.length > maxLines * 2) lines = lines.slice(-maxLines);
          renderBox();
        };
        CM.hub.on('log', (msg) => {
          if (!CM.router.current || CM.router.current.id !== 'logs') return;
          pending = pending.concat(msg.lines);
          if (!flushTimer) flushTimer = setTimeout(flush, 220);
        });
        CM.hub.on('log-error', (msg) => window.toast('日志流: ' + msg.message, 'err'));
        CM.hub.on('log-end', (msg) => { window.toast('日志流已结束: ' + msg.source, 'warn'); });
      }

      await loadTail(document.getElementById('lg-source').value);
      document.getElementById('lg-source')._cur = document.getElementById('lg-source').value;
    },
    show() {
      const cb = document.getElementById('lg-follow');
      const src = document.getElementById('lg-source');
      if (cb && cb.checked && !following && src) {
        following = true;
        CM.hub.send({ type: 'subscribe-logs', source: src._cur || src.value });
      }
    },
    hide() {
      if (following) { CM.hub.send({ type: 'unsubscribe-logs', source: document.getElementById('lg-source')._cur || '' }); following = false; }
    },
  });
})();
