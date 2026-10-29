/* Disks page */
(function () {
  'use strict';
  let els = {}, ioTimer = null, auto = null;

  /* Linux 与 Windows 的维护动作一一对应：同名 action id 由服务端按平台分发 */
  const ACTIONS_LINUX = [
    { id: 'apt-clean', label: '清理 apt 缓存', ic: 'trash', danger: false, desc: '删除 /var/cache/apt/archives 下的已下载安装包' },
    { id: 'apt-autoremove', label: '自动卸载无用包', ic: 'trash', danger: true, desc: '卸载不再被任何软件包依赖的软件包' },
    { id: 'journal-vacuum', label: '裁剪系统日志', ic: 'logs', danger: false, desc: '将 journald 日志体积限制到 200MB' },
    { id: 'fstrim', label: '执行 TRIM', ic: 'disk', danger: false, desc: '对所有支持的挂载点执行 fstrim，优化 SSD' },
    { id: 'drop-caches', label: '释放内存缓存', ic: 'memory', danger: true, desc: '写入 drop_caches=3，清空页缓存/目录项/inode 缓存（短暂变慢）' },
    { id: 'docker-prune', label: 'Docker 清理', ic: 'docker', danger: true, desc: 'docker system prune -af：删除未使用镜像/网络/停止的容器' },
    { id: 'analyze', label: '磁盘占用分析', ic: 'search', danger: false, desc: '扫描根目录一级/二级目录及大于 100MB 的文件' },
  ];
  const ACTIONS_WIN = [
    { id: 'apt-clean', label: '清理下载缓存', ic: 'trash', danger: false, desc: '清理 winget 安装包缓存（%TEMP%\\WinGet）与 Windows Update 下载缓存' },
    { id: 'journal-vacuum', label: '组件存储清理', ic: 'logs', danger: false, desc: 'DISM StartComponentCleanup：清除被取代的旧组件，可释放数 GB（需管理员，耗时数分钟）' },
    { id: 'fstrim', label: 'SSD TRIM', ic: 'disk', danger: false, desc: '对各固定磁盘执行 Optimize-Volume -ReTrim（仅 SSD 生效，需管理员；HDD 自动跳过）' },
    { id: 'drop-caches', label: '收缩进程工作集', ic: 'memory', danger: true, desc: '对所有可访问进程调用 EmptyWorkingSet，收缩物理内存占用（Windows 按需自动管理内存，不影响已提交内存）' },
    { id: 'docker-prune', label: 'Docker 清理', ic: 'docker', danger: true, desc: 'docker system prune -af：删除未使用镜像/网络/停止的容器（需 Docker Desktop）' },
    { id: 'analyze', label: '磁盘占用分析', ic: 'search', danger: false, desc: '扫描各固定磁盘一级目录占用（Top 20，跳过符号链接）' },
  ];
  const ACTIONS = () => (CM.isWindows() ? ACTIONS_WIN : ACTIONS_LINUX);

  registerPage({
    id: 'disks',
    title: '磁盘管理',
    async mount(root) {
      root.innerHTML = `
      <div class="grid g4" id="d-stats"></div>
      <div class="card">
        <div class="card-title">分区与挂载点 <div class="right">
          <label class="row small muted"><input type="checkbox" id="d-auto" checked> 自动刷新</label>
          <span class="mono small" id="d-rate"></span>
          <button class="btn btn-sm" id="d-refresh">${icon('refresh', { size: 13 })} 刷新</button>
        </div></div>
        <div id="d-table"><span class="muted small">加载中…</span></div>
      </div>
      <div class="grid g2">
        <div class="card">
          <div class="card-title">块设备 (lsblk)</div>
          <div id="d-lsblk"><span class="muted small">加载中…</span></div>
        </div>
        <div class="card">
          <div class="card-title">实时磁盘 I/O</div>
          <dl class="kv" id="d-io"><dt>读取速率</dt><dd>—</dd><dt>写入速率</dt><dd>—</dd><dt>读取 IOPS</dt><dd>—</dd><dt>写入 IOPS</dt><dd>—</dd></dl>
        </div>
      </div>
      <div class="card">
        <div class="card-title">磁盘维护操作</div>
        <div class="row" id="d-actions"></div>
        <div class="muted small mt">危险操作会弹出确认框。所有操作都会写入审计日志。</div>
      </div>`;

      els = { io: root.querySelector('#d-io') };

      const load = async () => {
        const d = await CM.api.get('/disks');
        const rootD = d.mounts.find((m) => m.mount === '/') || d.mounts[0] || {};
        document.getElementById('d-stats').innerHTML = `
          <div class="card stat"><div class="stat-label">根分区使用率</div><div class="stat-value">${rootD.pct || 0}%</div><div data-b>${ui.bar(rootD.pct || 0)}</div><div class="stat-sub">${fmt.bytes(rootD.used || 0)} / ${fmt.bytes(rootD.total || 0)}</div></div>
          <div class="card stat"><div class="stat-label">可用空间</div><div class="stat-value">${fmt.bytes(rootD.avail || 0)}</div><div class="stat-sub">/ 分区</div></div>
          <div class="card stat"><div class="stat-label">inode 使用率</div><div class="stat-value">${(d.mounts.find((m) => m.mount === '/') || {}).ipct || 0}%</div><div class="stat-sub">小文件数量占比</div></div>
          <div class="card stat"><div class="stat-label">分区数量</div><div class="stat-value">${d.mounts.length}</div><div class="stat-sub">物理挂载点</div></div>`;

        document.getElementById('d-table').innerHTML = ui.table([
          { k: 'dev', label: '设备', cls: 'mono' },
          { k: 'mount', label: '挂载点', cls: 'mono' },
          { k: 'total', label: '总容量', cls: 'num', render: (v) => fmt.bytes(v) },
          { k: 'used', label: '已用', cls: 'num', render: (v) => fmt.bytes(v) },
          { k: 'avail', label: '可用', cls: 'num', render: (v) => fmt.bytes(v) },
          { k: 'pct', label: '使用率', render: (v) => `${ui.bar(v)}<span class="small mono">${v}%</span>` },
          { k: 'ipct', label: 'inode', cls: 'num', render: (v) => v != null ? `<span style="color:${v > 90 ? '#f87171' : ''}">${v}%</span>` : '-' },
        ], d.mounts);

        const lb = document.getElementById('d-lsblk');
        if (d.blocks && d.blocks.length) {
          lb.innerHTML = ui.table([
            { k: 'name', label: '设备', cls: 'mono' },
            { k: 'size', label: '大小', cls: 'num' },
            { k: 'type', label: '类型' },
            { k: 'fstype', label: '文件系统' },
            { k: 'mountpoint', label: '挂载点', cls: 'mono' },
          ], d.blocks);
        } else lb.innerHTML = '<span class="muted small">无块设备信息</span>';
      };

      document.getElementById('d-refresh').onclick = () => load().catch((e) => window.toast(e.message, 'err'));

      const actBox = document.getElementById('d-actions');
      const ACTIONS_NOW = ACTIONS();
      actBox.innerHTML = ACTIONS_NOW.map((a) => `<button class="btn ${a.danger ? 'btn-danger' : ''}" data-a="${a.id}" title="${fmt.esc(a.desc)}">${icon(a.ic, { size: 14 })}${a.label}</button>`).join('');
      actBox.querySelectorAll('[data-a]').forEach((b) => {
        b.onclick = async () => {
          const a = ACTIONS_NOW.find((x) => x.id === b.dataset.a);
          const okc = await modal.confirm(a.label + '？', fmt.esc(a.desc), { danger: a.danger, okText: a.danger ? '我明白风险，执行' : '执行' });
          if (!okc) return;
          ui.busy(b, true);
          try {
            const r = await CM.api.post('/disk/action', { action: a.id });
            if (a.id === 'analyze') modal.output('磁盘占用分析', r.output);
            else { window.toast('操作完成', 'ok'); modal.output(a.label, r.output); }
            load();
          } catch (e) { window.toast(e.message, 'err', 6000); if (e.output) modal.output(a.label + ' 失败', e.output, { error: true }); }
          ui.busy(b, false);
        };
      });

      await load();
      if (!CM._diskIoSub) {
        CM._diskIoSub = true;
        CM.hub.on('metrics', (msg) => {
          if (!els.io || !CM.router.current || CM.router.current.id !== 'disks') return;
          const io = msg.data && msg.data.diskio ? msg.data.diskio : {};
          const ro = Number(io.readOps);
          const wo = Number(io.writeOps);
          els.io.innerHTML = `<dt>读取速率</dt><dd>${fmt.bps(io.readBps)}</dd><dt>写入速率</dt><dd>${fmt.bps(io.writeBps)}</dd><dt>读取 IOPS</dt><dd>${Number.isFinite(ro) ? ro.toFixed(1) : '-'}</dd><dt>写入 IOPS</dt><dd>${Number.isFinite(wo) ? wo.toFixed(1) : '-'}</dd>`;
        });
      }

      auto = CM.autoRefresh(() => load().catch(() => {}), { pageId: 'disks' });
      document.getElementById('d-auto').onchange = (e) => auto.setEnabled(e.target.checked);
      document.getElementById('d-rate').textContent = CM.refreshLabel(auto.ms());
      auto.start();
    },
    show() {
      const r = document.getElementById('d-rate');
      if (r && auto) r.textContent = CM.refreshLabel(auto.ms());
      if (auto && auto.isEnabled()) auto.start();
    },
    hide() { if (auto) auto.stop(); els = {}; },
  });
})();
