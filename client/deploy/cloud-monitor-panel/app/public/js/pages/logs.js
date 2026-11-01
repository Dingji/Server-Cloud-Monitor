/* Logs page */
(function () {
  'use strict';
  let following = false, autoscroll = true, maxLines = 3000;

  registerPage({
    id: 'logs',
    title: '日志中心',
    async mount(root) {
      const src = await CM.api.get('/logs/sources');
      root.innerHTML = `
      <div class="card">
        <div class="card-title">日志查看器
          <div class="right">
            <select class="inp" id="lg-source">
              ${src.files.map((f) => `<option value="${f.id}">${fmt.esc(f.label)}</option>`).join('')}
              ${src.special.map((f) => `<option value="${f.id}">${fmt.esc(f.label)}</option>`).join('')}
            </select>
            <input class="inp mono" id="lg-unit" placeholder="或 systemd 单元: nginx.service" style="width:220px">
            <button class="btn btn-sm" id="lg-unit-add">加载单元日志</button>
            <label class="row small muted"><input type="checkbox" id="lg-follow"> 实时跟随</label>
            <label class="row small muted"><input type="checkbox" id="lg-scroll" checked> 自动滚动</label>
            <input class="inp" id="lg-filter" placeholder="过滤关键字…" style="width:150px">
            <button class="btn btn-sm" id="lg-clear">${icon('trash', { size: 13 })} 清屏</button>
          </div>
        </div>
        <div class="logbox" id="lg-box" style="height:calc(100vh - 220px);min-height:300px"><span class="muted">选择日志源</span></div>
      </div>`;

      const box = document.getElementById('lg-box');
      let lines = [];

      const renderBox = () => {
        const f = document.getElementById('lg-filter').value.trim().toLowerCase();
        const show = f ? lines.filter((l) => l.toLowerCase().includes(f)) : lines;
        const tail = show.slice(-maxLines);
        box.innerHTML = tail.map((l) => `<div class="ln ${/error|fail|refused|critical/i.test(l) ? 'err' : /warn/i.test(l) ? 'warn' : ''}">${fmt.esc(l)}</div>`).join('') || '<span class="muted">(空)</span>';
        if (autoscroll) box.scrollTop = box.scrollHeight;
      };

      const unsub = () => {
        if (following) { CM.hub.send({ type: 'unsubscribe-logs', source: document.getElementById('lg-source')._cur || '' }); following = false; }
      };

      const loadTail = async (source) => {
        try {
          const r = await CM.api.post('/logs/tail', { source });
          lines = String(r.output || '').split('\n');
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
      document.getElementById('lg-unit-add').onclick = () => {
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
        /* buffer incoming lines and flush at most every 220ms (avoids
           per-message DOM rebuild when a stream is very chatty) */
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
      /* resume the follow stream if it was active before switching away */
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
