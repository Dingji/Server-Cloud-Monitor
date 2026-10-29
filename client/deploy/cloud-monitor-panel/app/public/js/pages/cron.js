/* Cron / scheduled tasks page */
(function () {
  'use strict';

  registerPage({
    id: 'cron',
    title: '定时任务',
    async mount(root) {
      root.innerHTML = `<div class="card">${ui.skeleton(5)}</div><div class="card">${ui.skeleton(5)}</div>`;
      let d;
      try { d = await CM.api.get('/cron'); }
      catch (e) { root.innerHTML = `<div class="card"><div class="muted">${fmt.esc(e.message)}</div></div>`; return; }

      const isWin = CM.isWindows();
      const etcJobs = Array.isArray(d.etcJobs) ? d.etcJobs : [];
      const rootJobs = Array.isArray(d.rootJobs) ? d.rootJobs : [];
      const timers = Array.isArray(d.timers) ? d.timers : [];
      const dailyHourly = Array.isArray(d.dailyHourly) ? d.dailyHourly : [];
      const cmJobs = etcJobs.filter((l) => /cm-/i.test(String(l)));
      const otherJobs = etcJobs.filter((l) => !/cm-/i.test(String(l)));

      root.innerHTML = `
      <div class="grid g3 mb">
        <div class="card stat"><div class="stat-label">面板管理的任务</div><div class="stat-value">${cmJobs.length}</div><div class="stat-sub">${isWin ? '任务计划程序 cm-*' : '/etc/cron.d/cm-*'}</div></div>
        <div class="card stat"><div class="stat-label">${isWin ? '其它计划任务' : 'Root Crontab'}</div><div class="stat-value">${rootJobs.length}</div><div class="stat-sub">${isWin ? 'schtasks 摘要' : 'crontab -l 条目'}</div></div>
        <div class="card stat"><div class="stat-label">${isWin ? '已注册服务触发器' : 'Systemd 定时器'}</div><div class="stat-value">${timers.length}</div><div class="stat-sub">${isWin ? '服务控制管理器' : 'systemctl list-timers'}</div></div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">${icon('plus', { size: 13 })} 新建定时任务（${isWin ? '当前用户权限' : 'root 权限执行'}）
            <div class="right"><button class="btn btn-sm btn-primary" id="cr-add">创建</button></div>
          </div>
          <div class="grid g3" style="gap:10px">
            <label class="fld">任务名<input class="inp mono" id="cr-name" placeholder="backup-www"></label>
            <label class="fld">Cron 表达式<input class="inp mono" id="cr-sched" placeholder="0 3 * * *"></label>
            <label class="fld" style="grid-column:span 2">执行命令<input class="inp mono" id="cr-cmd" placeholder="${isWin ? 'powershell.exe -File C:\\\\scripts\\\\backup.ps1' : '/usr/local/bin/backup.sh'}"></label>
          </div>
          <div class="muted small mt">${isWin
            ? '使用与 Linux cron 相同的五段表达式，面板会转换为 Windows「任务计划程序」中的 <code>cm-&lt;名&gt;</code> 任务。'
            : '表达式格式：<code>分 时 日 月 周</code>，例：<code>0 3 * * *</code> 每天3点 · <code>*/5 * * * *</code> 每5分钟。任务写入 <code>/etc/cron.d/cm-&lt;名&gt;</code>，以 root 执行。'}</div>
        </div>
        <div class="card">
          <div class="card-title">说明</div>
          <div class="muted small" style="line-height:2">
            · 此处创建的任务带 <code>cm-</code> 前缀，可在下方表格中直接删除<br>
            ${isWin
              ? '· 复杂计划任务可在「任务计划程序」或 Web 终端用 <code>schtasks</code> 管理<br>· 下方「其它」列表为系统其它摘要条目（只读）<br>'
              : '· 手动编辑的 root crontab 请通过 Web 终端操作（<code>crontab -e</code>）<br>· Systemd 定时器由 systemd 管理，仅展示；<br>· <code>/etc/cron.daily</code>、<code>cron.hourly</code> 脚本：' + (dailyHourly.length ? fmt.esc(dailyHourly.slice(0, 8).join(', ')) : '(空)') + '<br>'}
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">面板管理的定时任务 (${cmJobs.length})</div>
        <div id="cr-cmjobs"></div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">${isWin ? '其它计划任务' : 'Root Crontab'}</div>
          <pre class="pre" style="max-height:240px">${fmt.esc(rootJobs.join('\n') || '(空)')}</pre>
        </div>
        <div class="card">
          <div class="card-title">${isWin ? '任务计划摘要' : '/etc/cron.d 其它任务'}</div>
          <pre class="pre" style="max-height:240px">${fmt.esc(otherJobs.join('\n').slice(0, 4000) || '(空)')}</pre>
        </div>
      </div>

      <div class="card">
        <div class="card-title">${isWin ? '服务相关触发器' : 'Systemd 定时器'} (${timers.length})</div>
        ${ui.table([
          { k: 'next', label: '下次执行', cls: 'mono' },
          { k: 'left', label: '倒计时', cls: 'mono' },
          { k: 'unit', label: '定时器', cls: 'mono' },
          { k: 'activates', label: '执行单元', cls: 'mono' },
          { k: 'last', label: '上次执行', cls: 'mono' },
          { k: 'passed', label: '已过去', cls: 'mono' },
        ], timers, { style: 'max-height:340px' })}
      </div>`;

      const renderCm = () => {
        const rows = cmJobs.map((l) => {
          const m = /^\/etc\/cron\.d\/(cm-[A-Za-z0-9_-]+):\s*(.+)$/.exec(l) || /^(.+)$/.exec(l);
          return { file: (m[1] || l).replace(/^\/etc\/cron\.d\//, ''), line: m[2] || l };
        });
        document.getElementById('cr-cmjobs').innerHTML = rows.length ? ui.table([
          { k: 'file', label: '任务文件', cls: 'mono' },
          { k: 'line', label: '内容', cls: 'mono wrap' },
          { k: 'file', label: '', render: (v) => `<button class="btn btn-sm btn-danger" data-del="${fmt.esc(String(v).replace(/^cm-/, ''))}" data-file="${fmt.esc(v)}">删除</button>` },
        ], rows) : '<div class="muted small">暂无面板创建的任务</div>';
        document.querySelectorAll('[data-del]').forEach((b) => {
          b.onclick = async () => {
            const okc = await modal.confirm('删除定时任务', `删除 <b class="mono">${fmt.esc(b.dataset.file)}</b>？`, { danger: true });
            if (!okc) return;
            runAction('/cron/action', { action: 'delete', params: { name: b.dataset.del } }, { reload: () => location.reload() }).catch(() => {});
          };
        });
      };
      renderCm();

      document.getElementById('cr-add').onclick = async () => {
        const name = document.getElementById('cr-name').value.trim();
        const schedule = document.getElementById('cr-sched').value.trim();
        const command = document.getElementById('cr-cmd').value.trim();
        if (!name || !schedule || !command) return window.toast('请填写完整', 'warn');
        const who = isWin ? '' : ' root ';
        const okc = await modal.confirm('创建定时任务', `<b class="mono">${fmt.esc(schedule)}${who}${fmt.esc(command)}</b><br>任务名: cm-${fmt.esc(name)}`, { okText: '创建' });
        if (!okc) return;
        runAction('/cron/action', { action: 'add', params: { name, schedule, command } }, { reload: () => location.reload() }).catch(() => {});
      };
    },
  });
})();
