/* Memory page */
(function () {
  'use strict';
  let auto = null, swappinessFilled = false;

  registerPage({
    id: 'memory',
    title: '内存管理',
    async mount(root) {
      root.innerHTML = `
      <div class="grid g3" id="m-stats"></div>
      <div class="grid g2">
        <div class="card">
          <div class="card-title">内存详情 (/proc/meminfo)</div>
          <dl class="kv" id="m-detail"></dl>
        </div>
        <div class="card">
          <div class="card-title">内存占用 Top 15 (RSS)
            <div class="right">
              <label class="row small muted"><input type="checkbox" id="m-auto" checked> 自动刷新</label>
              <span class="mono small" id="m-rate"></span>
              <button class="btn btn-sm" id="m-refresh">${icon('refresh', { size: 13 })} 刷新</button>
            </div>
          </div>
          <div id="m-top"><span class="muted small">加载中…</span></div>
        </div>
      </div>
      <div class="grid g2">
        <div class="card">
          <div class="card-title">Swap 控制</div>
          <div class="row">
            <label class="fld">vm.swappiness (0-100)
              <input class="inp mono" id="m-swappiness" type="number" min="0" max="100" value="" style="width:110px">
            </label>
            <button class="btn btn-primary" id="m-apply-swap">${icon('check', { size: 13 })} 应用</button>
            <div class="muted small">值越小越倾向使用物理内存；数据库服务器常用 10。</div>
          </div>
        </div>
        <div class="card">
          <div class="card-title">内存维护</div>
          <div class="row">
            <button class="btn btn-danger" id="m-drop">${icon('memory', { size: 13 })} 释放缓存 (drop_caches)</button>
            <div class="muted small">清空页缓存、目录项与 inode 缓存，内存紧张时可临时回收。</div>
          </div>
        </div>
      </div>`;

      const render = (d) => {
        const m = d.metrics.memory;
        root.querySelector('#m-stats').innerHTML = `
          <div class="card stat"><div class="stat-label">物理内存</div><div class="stat-value">${m.usedPct.toFixed(1)}%</div><div data-b>${ui.bar(m.usedPct)}</div><div class="stat-sub">${fmt.bytes(m.total - m.available)} / ${fmt.bytes(m.total)} 可用 ${fmt.bytes(m.available)}</div></div>
          <div class="card stat"><div class="stat-label">交换分区</div><div class="stat-value">${m.swapPct.toFixed(1)}%</div><div data-b>${ui.bar(m.swapPct)}</div><div class="stat-sub">${fmt.bytes(m.swapUsed)} / ${fmt.bytes(m.swapTotal)}</div></div>
          <div class="card stat"><div class="stat-label">缓存 / 缓冲</div><div class="stat-value">${fmt.bytes(m.cached + m.buffers)}</div><div class="stat-sub">cached ${fmt.bytes(m.cached)} · buffers ${fmt.bytes(m.buffers)}</div></div>`;

        root.querySelector('#m-detail').innerHTML = `
          <dt>MemTotal</dt><dd>${fmt.bytes(m.total)}</dd>
          <dt>MemFree</dt><dd>${fmt.bytes(m.free)}</dd>
          <dt>MemAvailable</dt><dd>${fmt.bytes(m.available)}</dd>
          <dt>Buffers</dt><dd>${fmt.bytes(m.buffers)}</dd>
          <dt>Cached</dt><dd>${fmt.bytes(m.cached)}</dd>
          <dt>SReclaimable</dt><dd>${fmt.bytes(m.sReclaimable)}</dd>
          <dt>Shmem</dt><dd>${fmt.bytes(m.shmem)}</dd>
          <dt>Dirty (待写盘)</dt><dd>${fmt.bytes(m.dirty)}</dd>
          <dt>SwapTotal</dt><dd>${fmt.bytes(m.swapTotal)}</dd>
          <dt>SwapFree</dt><dd>${fmt.bytes(m.swapTotal - m.swapUsed)}</dd>
          <dt>进程总数</dt><dd>${d.procTotal}</dd>`;

        root.querySelector('#m-top').innerHTML = ui.table([
          { k: 'pid', label: 'PID', cls: 'num' },
          { k: 'user', label: '用户' },
          { k: 'rss', label: 'RSS', cls: 'num', render: (v) => fmt.bytes(v) },
          { k: 'mem', label: 'MEM%', cls: 'num' },
          { k: 'args', label: '进程', render: (v) => `<span class="mono small" title="${fmt.esc(v)}">${fmt.esc(String(v).slice(0, 46))}</span>` },
        ], d.topRss);

        /* swappiness 只在首次填充 */
        if (!swappinessFilled) {
          const el = document.getElementById('m-swappiness');
          if (el) { el.value = d.swappiness; swappinessFilled = true; }
        }
      };

      const load = async () => { render(await CM.api.get('/memory')); };

      document.getElementById('m-refresh').onclick = () => load().catch((e) => window.toast(e.message, 'err'));
      document.getElementById('m-apply-swap').onclick = async () => {
        const v = document.getElementById('m-swappiness').value;
        const okc = await modal.confirm('应用 swappiness', `将 vm.swappiness 设置为 <b>${v}</b> 并持久化？`);
        if (!okc) return;
        runAction('/security/action', { action: 'sysctl-set', params: { key: 'vm.swappiness', value: v } }).catch(() => {});
      };
      document.getElementById('m-drop').onclick = async () => {
        const okc = await modal.confirm('释放内存缓存', '将执行 <code>drop_caches=3</code>，系统可能短暂变慢。继续？', { danger: true });
        if (!okc) return;
        runAction('/disk/action', { action: 'drop-caches' }).catch(() => {});
      };

      await load();

      auto = CM.autoRefresh(() => load().catch(() => {}), { pageId: 'memory' });
      document.getElementById('m-auto').onchange = (e) => auto.setEnabled(e.target.checked);
      document.getElementById('m-rate').textContent = CM.refreshLabel(auto.ms());
      auto.start();
    },
    show() {
      const r = document.getElementById('m-rate');
      if (r && auto) r.textContent = CM.refreshLabel(auto.ms());
      if (auto && auto.isEnabled()) auto.start();
    },
    hide() { if (auto) auto.stop(); },
  });
})();
