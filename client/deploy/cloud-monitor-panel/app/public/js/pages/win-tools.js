/* Windows admin utilities (API-driven, no remote desktop launch)
   说明：服务/计划任务/端口/网络/用户/防火墙/IIS 等与顶层页面重复的能力
   已并入对应入口（系统服务、定时任务、网络管理、安全管理、网站/IIS），
   这里只保留 Windows 独有的工具。 */
(function () {
  'use strict';
  const esc = (s) => fmt.esc(s);
  const CHART_COLORS = ['#4370f0', '#22c55e', '#eab308', '#ef4444', '#8b5cf6', '#06b6d4', '#f97316'];

  async function act(op, extra) {
    return CM.api.post('/windows/action', Object.assign({ op }, extra || {}));
  }

  registerPage({
    id: 'wintools',
    title: 'Windows 工具',
    desc: '主机纳管、性能监控、更新与安全等 Windows 独有工具（服务/端口/用户/防火墙/IIS 请用对应页面）',
    async mount(root) {
      if (!CM.isWindows()) {
        root.innerHTML = '<div class="card muted">此页面仅适用于 Windows 面板主机。</div>';
        return;
      }
      const S = { perfSel: ['cpu', 'mem'], rdp: { sock: null, connected: false, keyGrab: false, listeners: [] } };
      /* [id, label, icon, 一句话描述] —— 概览页据此渲染功能入口网格 */
      const TABS = [
        ['overview', '概览', 'dashboard', '系统状态总览与全部功能入口'],
        ['hosts-mgmt', '主机纳管', 'server', '添加多台 Windows 主机，WinRM 探测与远程桌面'],
        ['rdp', '远程桌面', 'monitor', '浏览器内直接操作远程 Windows 桌面（RDP，无需客户端）'],
        ['perf', '性能监控', 'chart', 'CPU / 内存 / 磁盘 / 网络 / TCP 实时曲线'],
        ['update', '更新与安全', 'download', 'Windows 更新、更新策略、安全基线、BitLocker'],
        ['hosts', 'Hosts', 'edit', 'hosts 文本编辑（自动备份）'],
        ['startup', '启动项', 'play', '注册表 Run 启动项查看与删除'],
        ['env', '环境变量', 'sliders', '计算机 / 用户环境变量'],
        ['sys', '系统信息', 'list', 'Hotfix、证书到期、问题设备、共享、存储、Hyper-V'],
      ];

      root.innerHTML = '<div class="card mb" style="padding:10px 12px"><div class="row wt-tabs" id="wt-tabs"></div></div><div id="wt-body"></div>';
      const body = root.querySelector('#wt-body');

      const renderTabs = () => {
        const host = root.querySelector('#wt-tabs');
        host.innerHTML = TABS.map(([id, label]) =>
          '<button type="button" class="btn btn-sm ' + (S.tab === id ? 'btn-primary' : '') + '" data-tab="' + id + '">' + esc(label) + '</button>'
        ).join('');
        host.querySelectorAll('[data-tab]').forEach((b) => {
          b.onclick = () => { S.tab = b.dataset.tab; renderTabs(); render(); };
        });
      };
      if (S.tab === undefined) S.tab = 'overview';

      /* 通用动作按钮：执行 → toast → 输出显示到指定 pre */
      const bindAct = (id, op, extra, done) => {
        const btn = document.getElementById(id);
        if (!btn) return;
        btn.onclick = async () => {
          ui.busy(btn, true);
          try {
            const r = await act(op, extra);
            if (r.output) showMaintOut(String(r.output));
            window.toast(r.output ? String(r.output).split('\n')[0] : '操作完成', 'ok');
            if (done) done();
          } catch (e) { window.toast(e.message, 'err', 6000); }
          ui.busy(btn, false);
        };
      };
      const showMaintOut = (text) => {
        const el = document.getElementById('wt-maint-out');
        if (!el) return;
        el.style.display = 'block';
        el.textContent = text;
      };

      /* ============ 概览：状态卡 + 快捷维护 + 功能入口网格 ============ */
      async function renderOverview() {
        const o = await CM.api.get('/windows/overview', 20000);
        const fw = o.firewall || {};
        body.innerHTML = `
          <div class="grid3 mb">
            <div class="card stat"><div class="stat-label">运行时间</div><div class="stat-value">${esc(o.uptimeText || '-')}</div><div class="stat-sub">${esc(o.edition || o.os || '')}</div></div>
            <div class="card stat"><div class="stat-label">重启</div><div class="stat-value">${o.pendingReboot ? '待重启' : '正常'}</div><div class="stat-sub">Defender ${o.defenderOn ? '开' : '关'} · RDP ${o.rdpEnabled === true ? '开' : o.rdpEnabled === false ? '关' : '?'}</div></div>
            <div class="card stat"><div class="stat-label">防火墙</div><div class="stat-value">${fw.active ? '已启用' : '未全开'}</div><div class="stat-sub mono small">${esc((fw.raw || '').split('\n')[0] || '')}</div></div>
          </div>
          <div class="card mb"><div class="card-title">功能入口</div>
            <div class="grid g4" style="gap:9px">
              ${TABS.filter(([id]) => id !== 'overview').map(([id, label, ic, desc]) => `
                <div class="card" style="padding:11px 12px;margin:0;cursor:pointer" data-go="${id}">
                  <div class="row" style="gap:8px"><span style="color:var(--accent2);display:flex">${icon(ic, { size: 15 })}</span>
                    <b style="font-size:12.5px">${esc(label)}</b></div>
                  <div class="muted small mt" style="min-height:28px">${esc(desc)}</div>
                </div>`).join('')}
              <div class="card" style="padding:11px 12px;margin:0;cursor:pointer" data-nav="services">
                <div class="row" style="gap:8px"><span style="color:var(--accent2);display:flex">${icon('gear', { size: 15 })}</span><b style="font-size:12.5px">系统服务 →</b></div>
                <div class="muted small mt" style="min-height:28px">Windows 服务启停/重启（服务器分组）</div>
              </div>
              <div class="card" style="padding:11px 12px;margin:0;cursor:pointer" data-nav="network">
                <div class="row" style="gap:8px"><span style="color:var(--accent2);display:flex">${icon('network', { size: 15 })}</span><b style="font-size:12.5px">网络管理 →</b></div>
                <div class="muted small mt" style="min-height:28px">适配器 / 监听端口 / 诊断（服务器分组）</div>
              </div>
              <div class="card" style="padding:11px 12px;margin:0;cursor:pointer" data-nav="security">
                <div class="row" style="gap:8px"><span style="color:var(--accent2);display:flex">${icon('shield', { size: 15 })}</span><b style="font-size:12.5px">安全管理 →</b></div>
                <div class="muted small mt" style="min-height:28px">防火墙 / 本地用户 / 会话（服务器分组）</div>
              </div>
              <div class="card" style="padding:11px 12px;margin:0;cursor:pointer" data-nav="web">
                <div class="row" style="gap:8px"><span style="color:var(--accent2);display:flex">${icon('browser', { size: 15 })}</span><b style="font-size:12.5px">网站 / IIS →</b></div>
                <div class="muted small mt" style="min-height:28px">IIS 站点与应用池管理（应用分组）</div>
              </div>
            </div>
          </div>
          <div class="card"><div class="card-title">快捷维护</div>
            <div class="row" style="flex-wrap:wrap;gap:8px">
              <button type="button" class="btn btn-sm" id="wt-flush">刷新 DNS 缓存</button>
              <button type="button" class="btn btn-sm" id="wt-gp">组策略更新 (gpupdate)</button>
              <button type="button" class="btn btn-sm" id="wt-cleantemp">清理临时文件 (24h 前)</button>
              <button type="button" class="btn btn-sm" id="wt-recycle">清空回收站</button>
              <button type="button" class="btn btn-sm" id="wt-timesync">时间同步 (w32tm)</button>
              <button type="button" class="btn btn-sm" id="wt-wuscan">触发更新扫描</button>
            </div>
            <pre class="pre small mt" id="wt-maint-out" style="max-height:140px;display:none"></pre>
          </div>
          <div class="card mt"><div class="card-title">电源操作</div>
            <div class="muted small mb">重启/关机会在 60 秒倒计时后执行，倒计时内可用「取消」中止。</div>
            <div class="row" style="flex-wrap:wrap;gap:8px">
              <button type="button" class="btn btn-sm" id="wt-pw-cancel">取消倒计时</button>
              <button type="button" class="btn btn-sm btn-danger" id="wt-pw-restart">${icon('restart', { size: 13 })} 重启 (60s)</button>
              <button type="button" class="btn btn-sm btn-danger" id="wt-pw-shutdown">${icon('power', { size: 13 })} 关机 (60s)</button>
            </div>
            <pre class="pre small mt" id="wt-power-out" style="max-height:120px;display:none"></pre>
          </div>`;
        body.querySelectorAll('[data-go]').forEach((el) => { el.onclick = () => { S.tab = el.dataset.go; renderTabs(); render(); }; });
        /* 跳转到其它顶层页面（重复能力已并入的入口） */
        body.querySelectorAll('[data-nav]').forEach((el) => { el.onclick = () => CM.router.navigate(el.dataset.nav); });
        bindAct('wt-flush', 'flush-dns');
        bindAct('wt-gp', 'gpupdate');
        bindAct('wt-cleantemp', 'clean-temp');
        bindAct('wt-recycle', 'empty-recyclebin');
        bindAct('wt-timesync', 'time-sync');
        bindAct('wt-wuscan', 'wu-scan');
        const bindPower = (id, mode, title, warn) => {
          const btn = document.getElementById(id);
          if (!btn) return;
          btn.onclick = async () => {
            if (!await modal.confirm(title, warn, { danger: mode !== 'cancel', okText: title === '取消倒计时' ? '取消' : '确认' })) return;
            ui.busy(btn, true);
            try {
              const r = await act('power', { mode });
              const out = document.getElementById('wt-power-out');
              out.style.display = 'block';
              out.textContent = r.output || '已执行';
              window.toast(r.output ? String(r.output).split('\n')[0] : '已执行', 'ok');
            } catch (e) { window.toast(e.message, 'err', 6000); }
            ui.busy(btn, false);
          };
        };
        bindPower('wt-pw-cancel', 'cancel', '取消倒计时', '取消当前挂起的关机/重启倒计时？');
        bindPower('wt-pw-restart', 'restart', '重启服务器', '将在 <b>60 秒后重启</b>这台服务器，SSH/RDP 会话会中断。确认执行？');
        bindPower('wt-pw-shutdown', 'shutdown', '关闭服务器', '将在 <b>60 秒后关闭</b>这台服务器，需要现场或带外方式才能重新开机。确认执行？');
      }

      /* ============ 主机纳管（WinRM 探测 + .rdp 下载） ============ */
      async function renderHostsMgmt() {
        const d = await CM.api.get('/windows/managed-hosts', 15000);
        const hosts = d.hosts || [];
        body.innerHTML = `
          <div class="card mb">
            <div class="card-title">纳管主机 (${hosts.length})
              <div class="right"><button type="button" class="btn btn-sm btn-primary" id="wt-hm-add">${icon('plus', { size: 12 })} 添加主机</button>
                <button type="button" class="btn btn-sm" id="wt-hm-probeall">全部探测</button></div>
            </div>
            <p class="muted small">目标机需开启 WinRM（管理员 PowerShell：<code>Enable-PSRemoting -Force</code>，防火墙放行 5985）。
              留空凭据则使用面板服务账户；探测成功显示主机名 / 系统 / CPU / 内存。「远程桌面」下载 .rdp 文件，双击即连。</p>
            ${ui.table([
              { k: 'name', label: '名称', cls: 'mono' },
              { k: 'address', label: '地址', cls: 'mono small' },
              { k: 'username', label: '凭据', cls: 'small', render: (v) => v ? esc(v) : '<span class="muted">服务账户</span>' },
              { k: 'note', label: '备注', cls: 'small', render: (v) => esc(v || '-') },
              { k: 'name', label: '操作', render: (v) => `<div class="row" style="gap:5px;flex-wrap:nowrap">
                <button class="btn btn-sm btn-ok" data-hprobe="${esc(v)}">探测</button>
                <button class="btn btn-sm btn-primary" data-hdesk="${esc(v)}">Web 桌面</button>
                <button class="btn btn-sm" data-hrdp="${esc(v)}">.rdp 文件</button>
                <button class="btn btn-sm btn-danger" data-hdel="${esc(v)}">移除</button></div>` },
            ], hosts, { style: 'max-height:40vh' })}
          </div>
          <div class="card"><div class="card-title">探测结果</div>
            <pre class="pre" id="wt-hm-out" style="max-height:40vh">（点击「探测」后，这里显示对应主机的远程信息）</pre>
          </div>`;
        const out = document.getElementById('wt-hm-out');
        document.getElementById('wt-hm-add').onclick = async () => {
          const r = await modal.prompt('添加纳管主机', [
            { name: 'name', label: '名称（自定义标识）' },
            { name: 'address', label: '地址（IP 或主机名）' },
            { name: 'username', label: '用户名（可留空=服务账户）' },
            { name: 'password', label: '密码（可留空）' },
            { name: 'note', label: '备注（可留空）' },
          ], { okText: '保存' });
          if (!r || !r.name || !r.address) return window.toast('名称与地址必填', 'warn');
          try {
            await CM.api.post('/windows/managed-hosts', { op: 'add', name: r.name.trim(), address: r.address.trim(), username: r.username.trim(), password: r.password, note: r.note });
            window.toast('主机已保存', 'ok');
            renderHostsMgmt();
          } catch (e) { window.toast(e.message, 'err'); }
        };
        const probeOne = async (name, btn) => {
          if (btn) ui.busy(btn, true);
          try {
            const r = await CM.api.post('/windows/managed-hosts/probe', { name });
            if (r.ok) {
              out.textContent = `[${name}] ${r.hostname} · ${r.os}\nCPU ${r.cpuLoad}% · 内存 ${r.memFreeMb} / ${r.memTotalMb} MB 可用 · 最近启动 ${r.boot}`;
              window.toast(name + ' 探测成功', 'ok');
            } else {
              out.textContent = `[${name}] 探测失败：${r.error}`;
              window.toast(name + ' 探测失败', 'err', 7000);
            }
          } catch (e) { out.textContent = `[${name}] 探测失败：${e.message}`; }
          if (btn) ui.busy(btn, false);
        };
        body.querySelectorAll('[data-hprobe]').forEach((btn) => { btn.onclick = () => probeOne(btn.dataset.hprobe, btn); });
        document.getElementById('wt-hm-probeall').onclick = async (e) => {
          ui.busy(e.currentTarget, true);
          for (const h of hosts) await probeOne(h.name, null);
          ui.busy(e.currentTarget, false);
        };
        body.querySelectorAll('[data-hrdp]').forEach((btn) => {
          btn.onclick = () => {
            window.location.href = CM.apiBase + '/windows/rdp-file?name=' + encodeURIComponent(btn.dataset.hrdp);
            window.toast('已开始下载 .rdp 文件，双击打开远程桌面', 'ok', 5000);
          };
        });
        body.querySelectorAll('[data-hdesk]').forEach((btn) => {
          btn.onclick = () => {
            const h = hosts.find((x) => x.name === btn.dataset.hdesk) || {};
            S.rdpPrefill = { host: h.address || '', username: h.username || '', managedName: h.name || '' };
            window.toast('已带入主机信息，保存过密码则可留空', 'info', 4000);
            goTab('rdp');
          };
        });
        body.querySelectorAll('[data-hdel]').forEach((btn) => {
          btn.onclick = async () => {
            if (!await modal.confirm('移除主机', `从纳管清单移除 <b class="mono">${esc(btn.dataset.hdel)}</b>？`, { danger: true })) return;
            try { await CM.api.post('/windows/managed-hosts', { op: 'remove', name: btn.dataset.hdel }); window.toast('已移除', 'ok'); renderHostsMgmt(); }
            catch (e) { window.toast(e.message, 'err'); }
          };
        });
      }

      /* ============ 性能监控（Chart.js 实时曲线） ============ */
      async function renderPerf() {
        const catalog = [
          ['cpu', 'CPU 使用率', '%'], ['mem', '可用内存', 'MB'], ['diskq', '磁盘队列', ''],
          ['diskt', '磁盘活动', '%'], ['tcp', 'TCP 已建立', ''], ['sysq', '处理器队列', ''], ['net', '网络吞吐', 'KB/s'],
        ];
        body.innerHTML = `
          <div class="card mb">
            <div class="card-title">选择计数器</div>
            <div class="row" style="flex-wrap:wrap;gap:10px">
              ${catalog.map(([id, label]) => `<label class="row" style="gap:5px;cursor:pointer">
                <input type="checkbox" data-pf="${id}" ${S.perfSel.includes(id) ? 'checked' : ''}> <span class="small">${esc(label)}</span></label>`).join('')}
              <span class="muted small">每 2 秒采样，勾选后立即生效（最多同时 4 条曲线）</span>
            </div>
          </div>
          <div class="card"><div class="card-title">实时曲线</div>
            <div class="chart-box lg"><canvas id="wt-perf-chart"></canvas></div>
          </div>
          <div class="card"><div class="card-title">当前值</div>
            <div id="wt-perf-now" class="muted small">采样中…</div>
          </div>`;
        body.querySelectorAll('[data-pf]').forEach((cb) => {
          cb.onchange = () => {
            const id = cb.dataset.pf;
            if (cb.checked) { if (!S.perfSel.includes(id)) S.perfSel.push(id); }
            else S.perfSel = S.perfSel.filter((x) => x !== id);
            rebuild();
          };
        });

        const meta = Object.fromEntries(catalog.map(([id, label, unit]) => [id, { label, unit }]));
        let chart = null;
        const rebuild = () => {
          if (chart) chart.destroy();
          const sel = S.perfSel.slice(0, 4);
          if (!sel.length) { chart = null; return; }
          chart = ui.chart(document.getElementById('wt-perf-chart'), {
            series: sel.map((id, i) => ({ label: meta[id].label, color: CHART_COLORS[i], fill: i === 0 })),
            points: 150,
          });
        };
        rebuild();

        const tick = async () => {
          if (!chart) return;
          const d = await CM.api.get('/windows/perf', 0);
          const now = fmt.ts(Date.now());
          const byId = {};
          (d.items || []).forEach((it) => { byId[it.id] = it; });
          const sel = S.perfSel.slice(0, 4);
          chart.push(now, sel.map((id) => (byId[id] ? byId[id].value : null)));
          const nowEl = document.getElementById('wt-perf-now');
          if (nowEl) nowEl.innerHTML = (d.items || []).map((it) =>
            `<span class="chip" style="margin-right:6px">${esc(it.label)} <b>${it.value}${esc(it.unit)}</b></span>`).join('') || '（无可用计数器）';
        };
        await tick();
        CM.autoRefresh(tick, { pageId: 'wintools', ms: () => 2000 }).start();
      }

      /* ============ 更新与安全（WU + 策略 + 基线 + BitLocker） ============ */
      async function renderUpdate() {
        const [wu, pol, base] = await Promise.all([
          CM.api.get('/windows/wu', 180000).catch(() => ({ total: 0, updates: [], error: '加载失败' })),
          CM.api.get('/windows/wu-policy', 15000).catch(() => ({})),
          CM.api.get('/windows/sec-baseline', 120000).catch(() => ({})),
        ]);
        const updates = wu.updates || [];
        const bl = base.bitlocker || [];
        body.innerHTML = `
          <div class="card mb">
            <div class="card-title">${icon('download', { size: 13 })} Windows 更新
              <div class="right">
                ${wu.error ? '' : `<span class="muted small">待安装 ${wu.total} 项</span>`}
                <button type="button" class="btn btn-sm" id="wt-wu-scan">扫描</button>
                <button type="button" class="btn btn-sm" id="wt-wu-dl">下载</button>
                <button type="button" class="btn btn-sm btn-primary" id="wt-wu-inst">安装</button>
              </div>
            </div>
            ${wu.error ? `<div class="muted small">无法枚举更新：${esc(wu.error)}。仍可使用扫描/下载/安装按钮触发系统更新服务。</div>`
          : updates.length ? ui.table([
              { k: 'title', label: '更新', cls: 'small', render: (v) => '<span class="cmdline-clip" title="' + esc(v) + '">' + esc(String(v).slice(0, 72)) + '</span>' },
              { k: 'kb', label: 'KB', cls: 'mono small' },
              { k: 'size_mb', label: '大小', cls: 'num', render: (v) => v ? v + ' MB' : '-' },
              { k: 'categories', label: '类别', cls: 'small' },
            ], updates, { style: 'max-height:26vh', hint: false })
          : '<div class="muted small">没有待安装的更新，系统已是最新。</div>'}
          </div>
          <div class="grid g2">
            <div class="card">
              <div class="card-title">${icon('clock', { size: 13 })} 更新策略（组策略）</div>
              <div class="small mb" style="line-height:2">
                <label class="row" style="gap:6px;cursor:pointer"><input type="checkbox" id="wt-up-nar" ${pol.noAutoReboot ? 'checked' : ''}> 有用户登录时不自动重启</label>
                <div class="row mt">
                  <span class="muted small">功能更新延迟</span><input class="inp" id="wt-up-df" type="number" min="0" max="365" value="${pol.deferFeatureDays ?? 0}" style="width:80px"><span class="muted small">天</span>
                </div>
                <div class="row">
                  <span class="muted small">质量更新延迟</span><input class="inp" id="wt-up-dq" type="number" min="0" max="30" value="${pol.deferQualityDays ?? 0}" style="width:80px"><span class="muted small">天</span>
                </div>
                <div class="row">
                  <span class="muted small">使用时段</span><input class="inp" id="wt-up-as" type="number" min="0" max="23" value="${pol.activeStart ?? 8}" style="width:64px"><span class="muted small">点 →</span>
                  <input class="inp" id="wt-up-ae" type="number" min="0" max="23" value="${pol.activeEnd ?? 18}" style="width:64px"><span class="muted small">点（该时段内不自动重启）</span>
                </div>
              </div>
              <button type="button" class="btn btn-sm btn-primary" id="wt-up-save">保存策略</button>
              <div class="muted small mt">写入注册表组策略区，即时生效；Windows Update 在下次检测时应用。</div>
            </div>
            <div class="card">
              <div class="card-title">${icon('shield', { size: 13 })} 安全基线</div>
              <dl class="kv">
                <dt>密码最小长度</dt><dd>${base.policyJson?.minPwd ?? '?'}</dd>
                <dt>密码历史</dt><dd>${base.policyJson?.hist ?? '?'}</dd>
                <dt>锁定阈值</dt><dd>${base.policyJson?.lockoutThreshold ?? '?'} 次</dd>
                <dt>锁定时长</dt><dd>${base.policyJson?.lockoutDurationMin ?? '?'} 分钟</dd>
                <dt>RDP NLA</dt><dd>${base.nla == null ? '?' : (base.nla ? ui.badge('已启用', 'ok') : ui.badge('未启用', 'warn'))}</dd>
                <dt>UAC</dt><dd>${base.uac == null ? '?' : (base.uac ? ui.badge('已启用', 'ok') : ui.badge('禁用', 'bad'))}</dd>
                <dt>SMB1</dt><dd>${base.smb1 == null ? '?' : (base.smb1 ? ui.badge('启用（有风险）', 'bad') : ui.badge('已禁用', 'ok'))}</dd>
              </dl>
              <div class="row mt" style="flex-wrap:wrap;gap:8px">
                <button type="button" class="btn btn-sm" id="wt-sec-lockout">调整锁定策略</button>
                <button type="button" class="btn btn-sm" id="wt-sec-nla">NLA ${base.nla ? '禁用' : '启用'}</button>
                <button type="button" class="btn btn-sm ${base.smb1 ? 'btn-danger' : ''}" id="wt-sec-smb1">SMB1 ${base.smb1 ? '禁用' : '启用'}</button>
              </div>
            </div>
          </div>
          <div class="card mt"><div class="card-title">${icon('lock', { size: 13 })} BitLocker (${bl.length})</div>
            ${base.bitlockerError ? `<div class="muted small">无法读取：${esc(base.bitlockerError)}</div>`
          : bl.length ? ui.table([
              { k: 'mount', label: '卷', cls: 'mono' },
              { k: 'status', label: '状态', cls: 'small' },
              { k: 'protection', label: '保护', render: (v) => /on/i.test(String(v)) ? ui.badge('开', 'ok') : ui.badge(String(v || '-'), 'warn') },
              { k: 'pct', label: '加密', cls: 'num', render: (v) => v + '%' },
            ], bl, { style: 'max-height:24vh' }) : '<div class="muted small">没有 BitLocker 卷数据（未启用或功能不可用）。</div>'}
          </div>`;

        bindAct('wt-wu-scan', 'wu-scan');
        bindAct('wt-wu-dl', 'wu-download');
        bindAct('wt-wu-inst', 'wu-install');
        document.getElementById('wt-up-save').onclick = async () => {
          try {
            await CM.api.post('/windows/wu-policy', {
              noAutoReboot: document.getElementById('wt-up-nar').checked,
              deferFeatureDays: parseInt(document.getElementById('wt-up-df').value, 10) || 0,
              deferQualityDays: parseInt(document.getElementById('wt-up-dq').value, 10) || 0,
              activeStart: parseInt(document.getElementById('wt-up-as').value, 10) || 0,
              activeEnd: parseInt(document.getElementById('wt-up-ae').value, 10) || 0,
            });
            window.toast('更新策略已保存', 'ok');
          } catch (e) { window.toast(e.message, 'err', 7000); }
        };
        document.getElementById('wt-sec-lockout').onclick = async () => {
          const r = await modal.prompt('账户锁定策略', [
            { name: 'threshold', label: '锁定阈值（失败次数，0=不锁定）', value: String(base.policyJson?.lockoutThreshold ?? 5) },
            { name: 'durationMin', label: '锁定时长（分钟）', value: String(base.policyJson?.lockoutDurationMin ?? 30) },
            { name: 'windowMin', label: '观察窗口（分钟）', value: String(base.policyJson?.lockoutWindowMin ?? 30) },
          ], { okText: '保存' });
          if (!r) return;
          try { await CM.api.post('/windows/sec-baseline', { action: 'lockout-set', threshold: parseInt(r.threshold, 10) || 0, durationMin: parseInt(r.durationMin, 10) || 30, windowMin: parseInt(r.windowMin, 10) || 30 }); window.toast('锁定策略已保存', 'ok'); renderUpdate(); }
          catch (e) { window.toast(e.message, 'err', 7000); }
        };
        document.getElementById('wt-sec-nla').onclick = async () => {
          const enable = !base.nla;
          if (!await modal.confirm('RDP NLA', `${enable ? '启用' : '禁用'}网络级身份验证（NLA）？`, { danger: !enable })) return;
          try { const r = await CM.api.post('/windows/sec-baseline', { action: 'nla-set', enable }); window.toast(r.output || '已保存', 'ok'); renderUpdate(); }
          catch (e) { window.toast(e.message, 'err', 7000); }
        };
        document.getElementById('wt-sec-smb1').onclick = async () => {
          const enable = !base.smb1;
          if (enable) {
            if (!await modal.confirm('启用 SMB1', 'SMB1 存在已知严重漏洞（ WannaCry 等），仅在兼容旧设备时启用。确认？', { danger: true })) return;
          } else if (!await modal.confirm('禁用 SMB1', '禁用 SMB1 并提示重启系统后完全生效。继续？')) return;
          window.toast('正在修改 Windows 功能（最长 5 分钟）…', 'info', 6000);
          try { const r = await CM.api.post('/windows/sec-baseline', { action: 'smb1-set', enable }); window.toast(r.output || '已保存', 'ok', 8000); renderUpdate(); }
          catch (e) { window.toast(e.message, 'err', 8000); }
        };
      }

      /* ============ Hosts ============ */
      async function renderHosts() {
        const h = await CM.api.get('/windows/hosts', 15000);
        body.innerHTML = `
          <div class="card">
            <div class="card-title">hosts 文件 <span class="muted small mono">${esc(h.path || '')}</span></div>
            <p class="muted small">保存前会自动备份为 hosts.cm.bak。请勿写入超过 96KB 内容。</p>
            <textarea class="inp mono" id="wt-hosts" style="width:100%;min-height:320px;font-size:12px">${esc(h.content || '')}</textarea>
            <div class="row mt">
              <button type="button" class="btn btn-primary" id="wt-hosts-save">保存</button>
              <button type="button" class="btn btn-sm" id="wt-hosts-reload">重新加载</button>
            </div>
          </div>`;
        document.getElementById('wt-hosts-reload').onclick = () => renderHosts();
        document.getElementById('wt-hosts-save').onclick = async () => {
          const content = document.getElementById('wt-hosts').value;
          if (!await modal.confirm('保存 hosts', '确认写入 hosts 文件？')) return;
          try {
            await act('hosts-save', { content });
            window.toast('hosts 已保存', 'ok');
          } catch (e) { window.toast(e.message, 'err'); }
        };
      }

      /* ============ 启动项 ============ */
      async function renderStartup() {
        const o = await CM.api.get('/windows/overview', 20000);
        const rows = (o.startup || []).map((s) => Object.assign({}, s, {
          _btn: '<button type="button" class="btn btn-sm btn-danger" data-rm="' + esc(s.scope) + '|' + esc(s.name) + '">删除</button>',
        }));
        body.innerHTML = `
          <div class="card">
            <div class="card-title">Run 启动项</div>
            <p class="muted small">从注册表 Run 键删除（不影响已安装程序本身）。</p>
            ${ui.table([
              { k: 'scope', label: '范围', cls: 'mono small' },
              { k: 'name', label: '名称' },
              { k: 'command', label: '命令', cls: 'mono small proc-cmdline', render: (v) => '<span class="cmdline-clip" title="' + esc(v) + '">' + esc(String(v).slice(0, 72)) + '</span>' },
              { k: '_btn', label: '操作', render: (v) => v },
            ], rows, { style: 'max-height:60vh' })}
          </div>`;
        body.querySelectorAll('[data-rm]').forEach((btn) => {
          btn.onclick = async () => {
            const [scope, name] = btn.dataset.rm.split('|');
            if (!await modal.confirm('删除启动项', '删除 <b>' + esc(name) + '</b> (' + esc(scope) + ')？', { danger: true })) return;
            try {
              await act('startup-remove', { scope, name });
              window.toast('已删除', 'ok');
              renderStartup();
            } catch (e) { window.toast(e.message, 'err'); }
          };
        });
      }

      /* ============ 环境变量 ============ */
      async function renderEnv() {
        const d = await CM.api.get('/windows/env?scope=' + encodeURIComponent(S.envScope || 'machine'), 30000);
        const list = (S.envScope || 'machine') === 'user' ? (d.user || []) : (d.machine || []);
        body.innerHTML = `
          <div class="card">
            <div class="card-title">环境变量
              <div class="right">
                <button type="button" class="btn btn-sm ${(S.envScope || 'machine') === 'machine' ? 'btn-primary' : ''}" data-sc="machine">计算机</button>
                <button type="button" class="btn btn-sm ${S.envScope === 'user' ? 'btn-primary' : ''}" data-sc="user">当前用户</button>
              </div>
            </div>
            ${ui.table([
              { k: 'name', label: '名称', cls: 'mono' },
              { k: 'value', label: '值', cls: 'mono small', render: (v) => '<span class="cmdline-clip" title="' + esc(v) + '">' + esc(String(v).slice(0, 100)) + '</span>' },
            ], list, { style: 'max-height:65vh' })}
          </div>`;
        body.querySelectorAll('[data-sc]').forEach((b) => {
          b.onclick = () => { S.envScope = b.dataset.sc; renderEnv(); };
        });
      }

      /* ============ 系统信息 ============ */
      async function renderSys() {
        const [hf, dv, ce, sh, st, hv] = await Promise.all([
          CM.api.get('/windows/hotfixes', 60000).catch(() => ({ hotfixes: [] })),
          CM.api.get('/windows/devices', 60000).catch(() => ({ devices: [] })),
          CM.api.get('/windows/certs', 60000).catch(() => ({ certs: [] })),
          CM.api.get('/windows/shares', 60000).catch(() => ({ shares: [] })),
          CM.api.get('/windows/storage', 60000).catch(() => ({})),
          CM.api.get('/windows/hyperv', 60000).catch(() => ({})),
        ]);
        body.innerHTML = `
          <div class="grid g2">
            <div class="card">
              <div class="card-title">已安装更新 / Hotfix (${(hf.hotfixes || []).length})</div>
              ${ui.table([
                { k: 'id', label: '补丁', cls: 'mono' },
                { k: 'desc', label: '类型', cls: 'small' },
                { k: 'installedOn', label: '安装时间', cls: 'small' },
                { k: 'installedBy', label: '安装者', cls: 'mono small' },
              ], hf.hotfixes || [], { style: 'max-height:34vh' })}
            </div>
            <div class="card">
              <div class="card-title">${icon('warning', { size: 13 })} 问题设备 (${(dv.devices || []).length})</div>
              ${(dv.devices || []).length ? ui.table([
                { k: 'name', label: '设备', cls: 'small', render: (v) => '<span class="cmdline-clip" title="' + esc(v) + '">' + esc(String(v).slice(0, 44)) + '</span>' },
                { k: 'problem', label: '问题', render: (v) => ui.badge(String(v), 'bad') },
              ], dv.devices, { style: 'max-height:34vh' }) : '<div class="muted small">所有设备工作正常，没有驱动错误。</div>'}
            </div>
            <div class="card">
              <div class="card-title">${icon('lock', { size: 13 })} 证书到期（180 天内）(${(ce.certs || []).length})</div>
              ${(ce.certs || []).length ? ui.table([
                { k: 'subject', label: '主题', cls: 'mono small', render: (v) => '<span class="cmdline-clip" title="' + esc(v) + '">' + esc(String(v).slice(0, 40)) + '</span>' },
                { k: 'notAfter', label: '到期', cls: 'mono small' },
                { k: 'daysLeft', label: '剩余', cls: 'num', render: (v, r) => r.expired ? ui.badge('已过期', 'bad') : ui.badge(v + ' 天', v <= 30 ? 'bad' : v <= 90 ? 'warn' : 'ok') },
              ], ce.certs, { style: 'max-height:34vh' }) : '<div class="muted small">180 天内没有到期的机器证书。</div>'}
            </div>
            <div class="card">
              <div class="card-title">${icon('folder', { size: 13 })} 共享文件夹 (${(sh.shares || []).length})</div>
              ${ui.table([
                { k: 'name', label: '共享名', cls: 'mono' },
                { k: 'path', label: '路径', cls: 'mono small' },
                { k: 'type', label: '类型', cls: 'small' },
              ], sh.shares || [], { style: 'max-height:34vh' })}
            </div>
            <div class="card">
              <div class="card-title">${icon('disk', { size: 13 })} 存储池 / 物理磁盘</div>
              ${st.available ? `
                ${st.pools?.length ? ui.table([
                  { k: 'name', label: '存储池', cls: 'mono' },
                  { k: 'health', label: '健康', render: (v) => /healthy/i.test(String(v)) ? ui.badge('健康', 'ok') : ui.badge(String(v), 'warn') },
                  { k: 'sizeGb', label: '容量', cls: 'num', render: (v) => v + ' GB' },
                ], st.pools, { style: 'max-height:20vh', hint: false }) : ''}
                ${st.disks?.length ? ui.table([
                  { k: 'name', label: '磁盘', cls: 'mono' },
                  { k: 'media', label: '介质', cls: 'small' },
                  { k: 'bus', label: '总线', cls: 'small' },
                  { k: 'health', label: '健康', render: (v) => /healthy/i.test(String(v)) ? ui.badge('健康', 'ok') : ui.badge(String(v), 'warn') },
                  { k: 'sizeGb', label: '容量', cls: 'num', render: (v) => v + ' GB' },
                ], st.disks, { style: 'max-height:24vh', hint: false }) : '<div class="muted small">没有物理磁盘数据。</div>'}`
            : `<div class="muted small">${esc(st.message || '存储管理模块不可用。')}</div>`}
            </div>
            <div class="card">
              <div class="card-title">${icon('server', { size: 13 })} Hyper-V 虚拟机</div>
              ${hv.available ? (hv.vms?.length ? ui.table([
                { k: 'name', label: '虚拟机', cls: 'mono' },
                { k: 'state', label: '状态', render: (v) => /running/i.test(String(v)) ? ui.badge('运行', 'ok') : /off/i.test(String(v)) ? ui.badge('已关机', 'gray') : ui.badge(String(v || '-'), 'warn') },
                { k: 'cpu', label: 'CPU%', cls: 'num' },
                { k: 'memGb', label: '内存', cls: 'num', render: (v) => v + ' GB' },
                { k: 'uptime', label: '运行时长', cls: 'small' },
                { k: 'name', label: '操作', render: (v, r) => `<div class="row" style="gap:4px;flex-wrap:nowrap">
                  ${/running/i.test(r.state)
                  ? `<button class="btn btn-sm" data-vm="restart|${esc(v)}">重启</button><button class="btn btn-sm btn-danger" data-vm="stop|${esc(v)}">关机</button>`
                  : `<button class="btn btn-sm btn-ok" data-vm="start|${esc(v)}">开机</button>`}</div>` },
              ], hv.vms, { style: 'max-height:34vh' }) : '<div class="muted small">Hyper-V 角色可用，当前没有虚拟机。</div>')
            : `<div class="muted small">${esc(hv.message || 'Hyper-V 模块不可用。')}</div>`}
            </div>
          </div>`;
        body.querySelectorAll('[data-vm]').forEach((btn) => {
          btn.onclick = async () => {
            const [action, name] = btn.dataset.vm.split('|');
            const label = action === 'start' ? '开机' : action === 'stop' ? '关机' : '重启';
            if (!await modal.confirm(label + '虚拟机', `对虚拟机 <b class="mono">${esc(name)}</b> 执行「${label}」？`, { danger: action === 'stop' })) return;
            ui.busy(btn, true);
            try { await CM.api.post('/windows/hyperv/action', { name, action }); window.toast('操作完成', 'ok'); renderSys(); }
            catch (e) { window.toast(e.message, 'err', 6000); ui.busy(btn, false); }
          };
        });
      }

      /* ============ 远程桌面（Web 内 RDP：JPEG 串流 + 键鼠注入） ============ */
      function rdpCleanup() {
        const R = S.rdp;
        if (R.sock) { try { R.sock.close(); } catch (_) {} R.sock = null; }
        R.connected = false; R.keyGrab = false;
        R.listeners.forEach(([t, fn]) => window.removeEventListener(t, fn));
        R.listeners = [];
        if (R.watchdog) { clearTimeout(R.watchdog); R.watchdog = null; }
        if (R.frameTimer) { clearInterval(R.frameTimer); R.frameTimer = null; }
        if (R.diagTimer) { clearTimeout(R.diagTimer); R.diagTimer = null; }
      }

      async function renderRdp() {
        const hostsRes = await CM.api.get('/windows/managed-hosts', 15000).catch(() => ({ hosts: [] }));
        const hosts = hostsRes.hosts || [];
        const prefill = S.rdpPrefill || {};
        S.rdpPrefill = null;
        body.innerHTML = `
          <div class="card mb">
            <div class="card-title">连接设置</div>
            <div class="row" style="flex-wrap:wrap;gap:8px">
              <select class="inp" id="rdp-host" style="width:170px">
                <option value="">手动输入主机</option>
                ${hosts.map((h) => `<option value="${esc(h.name)}" ${prefill.managedName === h.name ? 'selected' : ''}>${esc(h.name)}</option>`).join('')}
              </select>
              <input class="inp mono" id="rdp-addr" placeholder="地址（IP / 主机名）" style="width:180px" value="${esc(prefill.host || '')}">
              <input class="inp mono" id="rdp-user" placeholder="用户名" style="width:140px" value="${esc(prefill.username || '')}">
              <input class="inp" id="rdp-pass" type="password" placeholder="密码" style="width:140px">
              <select class="inp" id="rdp-res" style="width:130px">
                <option value="1280x720" selected>1280 × 720</option>
                <option value="1600x900">1600 × 900</option>
                <option value="1920x1080">1920 × 1080</option>
              </select>
              <select class="inp" id="rdp-q" style="width:110px">
                <option value="60" selected>画质 标准</option>
                <option value="80">画质 高</option>
                <option value="40">画质 低</option>
              </select>
              <button type="button" class="btn" id="rdp-test">测试</button>
              <button type="button" class="btn btn-primary" id="rdp-connect">${icon('play', { size: 13 })} 连接</button>
              <button type="button" class="btn btn-danger" id="rdp-disconnect" style="display:none">${icon('stop', { size: 13 })} 断开</button>
            </div>
            <div class="muted small mt">浏览器内直接操作远程 Windows 桌面。选择纳管主机且保存过凭据时密码可留空（服务端读取）。连接前可先「测试」检查目标 3389 端口。</div>
          </div>
          <div class="card">
            <div class="card-title">远程桌面 <span id="rdp-status" class="muted small">未连接</span> <span id="rdp-frames" class="muted small mono"></span>
              <div class="right">
                <button type="button" class="btn btn-sm" id="rdp-full" style="display:none">全屏</button>
              </div>
            </div>
            <div id="rdp-stage" style="text-align:center;background:#04070b;border:1px solid var(--border);border-radius:3px;min-height:220px;display:flex;align-items:center;justify-content:center">
              <span class="muted small" id="rdp-placeholder">输入连接信息并点击「连接」</span>
              <canvas id="rdp-canvas" width="1280" height="720" tabindex="0" style="display:none;max-width:100%;outline:none;cursor:default"></canvas>
            </div>
            <div class="muted small mt">点击画面捕获键盘（Esc 释放）；滚轮已映射。</div>
          </div>
          <div class="card"><div class="card-title">连接日志（排查用）</div>
            <pre class="pre small" id="rdp-log" style="max-height:150px">（连接过程中的状态会显示在这里）</pre>
          </div>`;

        const R = S.rdp;
        const statusEl = document.getElementById('rdp-status');
        const framesEl = document.getElementById('rdp-frames');
        const logEl = document.getElementById('rdp-log');
        const cv = document.getElementById('rdp-canvas');
        const stage = document.getElementById('rdp-stage');
        const placeholder = document.getElementById('rdp-placeholder');
        const connectBtn = document.getElementById('rdp-connect');
        const testBtn = document.getElementById('rdp-test');
        const discBtn = document.getElementById('rdp-disconnect');
        const fullBtn = document.getElementById('rdp-full');
        const ctx2d = cv.getContext('2d');
        let imgBusy = false;
        let frameCount = 0;
        let lastFrameAt = 0;

        const setStatus = (t) => { statusEl.textContent = t; log(t); };
        const log = (t) => {
          const line = `[${new Date().toLocaleTimeString('zh-CN', { hour12: false })}] ${String(t).replace(/\n/g, ' ')}`;
          logEl.textContent = (logEl.textContent.trim() === '（连接过程中的状态会显示在这里）' ? '' : logEl.textContent) + line + '\n';
          logEl.scrollTop = logEl.scrollHeight;
        };
        const drawFrame = (b64) => {
          frameCount++;
          lastFrameAt = Date.now();
          framesEl.textContent = frameCount + ' 帧';
          if (imgBusy) return;
          imgBusy = true;
          const img = new Image();
          img.onload = () => { try { ctx2d.drawImage(img, 0, 0); } catch (_) {} imgBusy = false; };
          img.onerror = () => { imgBusy = false; };
          img.src = 'data:image/jpeg;base64,' + b64;
        };

        const currentHost = () => {
          const managed = document.getElementById('rdp-host').value;
          const h = hosts.find((x) => x.name === managed);
          return { managed, host: (h && h.address) || document.getElementById('rdp-addr').value.trim() };
        };

        /* 预检：3389 可达性 + 面板运行模式提示 */
        testBtn.onclick = async () => {
          const { host } = currentHost();
          if (!host) return window.toast('请先选择主机或输入地址', 'warn');
          ui.busy(testBtn, true);
          setStatus('正在探测 ' + host + ':3389 …');
          try {
            const r = await CM.api.post('/windows/rdp-preflight', { host });
            setStatus((r.ok ? '✓ ' : '✗ ') + r.message);
            if (r.hint) log('提示：' + r.hint);
            if (r.ok) window.toast(r.message, 'ok');
            else window.toast(r.message, 'err', 8000);
          } catch (e) { setStatus('预检失败：' + e.message); }
          ui.busy(testBtn, false);
        };

        const sendMouse = (e, down) => {
          if (!R.connected || !R.sock) return;
          const r = cv.getBoundingClientRect();
          if (!r.width || !r.height) return;
          const x = Math.max(0, Math.min(cv.width - 1, Math.round((e.clientX - r.left) * cv.width / r.width)));
          const y = Math.max(0, Math.min(cv.height - 1, Math.round((e.clientY - r.top) * cv.height / r.height)));
          const msg = { type: 'input', kind: 'mouse', x, y, buttons: e.buttons || 0 };
          if (down !== undefined) {
            msg.btn = e.button === 2 ? 'right' : e.button === 1 ? 'mid' : 'left';
            msg.down = !!down;
          }
          R.sock.send(JSON.stringify(msg));
        };
        const onKeyDown = (e) => {
          if (!R.connected || !R.keyGrab) return;
          if (e.key === 'Escape') { R.keyGrab = false; setStatus('已连接（键盘已释放，点击画面重新捕获）'); return; }
          e.preventDefault();
          R.sock.send(JSON.stringify({ type: 'input', kind: 'key', vk: e.keyCode || e.which || 0, down: true }));
        };
        const onKeyUp = (e) => {
          if (!R.connected || !R.keyGrab) return;
          e.preventDefault();
          R.sock.send(JSON.stringify({ type: 'input', kind: 'key', vk: e.keyCode || e.which || 0, down: false }));
        };
        const onBlur = () => { /* 注入按键时焦点会短暂离开页面，不能因此丢掉键盘捕获 */ };

        const cleanup = () => {
          rdpCleanup();
          connectBtn.style.display = ''; connectBtn.disabled = false;
          discBtn.style.display = 'none'; fullBtn.style.display = 'none';
        };
        R.cleanup = cleanup;
        CM._rdpCleanup = cleanup;

        document.getElementById('rdp-host').onchange = (e) => {
          const h = hosts.find((x) => x.name === e.target.value);
          if (h) { document.getElementById('rdp-addr').value = h.address || ''; document.getElementById('rdp-user').value = h.username || ''; }
        };

        connectBtn.onclick = () => {
          const { managed, host } = currentHost();
          if (!managed && !host) return window.toast('请选择纳管主机或输入地址', 'warn');
          const [w, h2] = document.getElementById('rdp-res').value.split('x').map(Number);
          const quality = Number(document.getElementById('rdp-q').value);
          cv.width = w; cv.height = h2;
          placeholder.style.display = 'none';
          cv.style.display = 'block';
          frameCount = 0; framesEl.textContent = '';
          setStatus('正在建立 WebSocket 通道 …');
          connectBtn.disabled = true;

          /* 前端看门狗：30 秒内未收到任何状态消息 → 判定通道故障（多为反代未转发 WS） */
          let gotAny = false;
          if (R.watchdog) clearTimeout(R.watchdog);
          R.watchdog = setTimeout(() => {
            if (!gotAny) {
              setStatus('30 秒无响应：WebSocket 通道可能不通。若经 nginx 反代访问，请为 /panel/wsrdp 配置 Upgrade 转发（与 /panel/ws 相同）。');
              window.toast('远程桌面通道无响应，详见连接日志', 'err', 9000);
              cleanup();
            }
          }, 30_000);

          const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
          let sock;
          try {
            sock = new WebSocket(`${proto}//${location.host}${CM.base}/wsrdp?token=${encodeURIComponent(CM.token)}`);
          } catch (err) {
            setStatus('WebSocket 创建失败：' + err.message);
            connectBtn.disabled = false;
            return;
          }
          R.sock = sock;
          sock.onopen = () => {
            setStatus('通道已建立，正在连接 ' + (host || managed) + ' …');
            sock.send(JSON.stringify({
              type: 'connect',
              managedName: managed || undefined,
              host: host || undefined,
              username: document.getElementById('rdp-user').value.trim() || undefined,
              password: document.getElementById('rdp-pass').value || undefined,
              width: w, height: h2, quality,
            }));
          };
          sock.onmessage = (ev) => {
            gotAny = true;
            let m; try { m = JSON.parse(ev.data); } catch (_) { return; }
            if (m.type === 'status') {
              if (m.state === 'diag') {
                /* 诊断消息单独记录（setStatus 已统一 log，这里不再重复） */
                window.toast((m.detail || '').split('。')[0], 'warn', 10000);
                if (R.diagTimer) { clearTimeout(R.diagTimer); R.diagTimer = null; }
                cleanup();
              } else {
                setStatus((m.state === 'error' ? '✗ ' : m.state === 'closed' ? '' : '') + (m.detail || m.state));
              if (m.state === 'connected' || m.state === 'streaming') {
                R.connected = true;
                connectBtn.style.display = 'none'; discBtn.style.display = ''; fullBtn.style.display = '';
                connectBtn.disabled = false;
                cv.focus(); R.keyGrab = true;
                if (R.watchdog) { clearTimeout(R.watchdog); R.watchdog = null; }
                /* 已连接但 8 秒无帧 → 渲染受限提示 */
                if (R.frameTimer) clearInterval(R.frameTimer);
                R.frameTimer = setInterval(() => {
                  if (R.connected && lastFrameAt && Date.now() - lastFrameAt > 8000) {
                    clearInterval(R.frameTimer); R.frameTimer = null;
                    log('画面已停止输出超过 8 秒：可尝试断开后降低分辨率/画质重连；若目标主机负载过高也可能出现短暂停帧。');
                  }
                }, 4000);
              } else if (m.state === 'error') {
                window.toast(m.detail || '连接失败', 'err', 9000);
                cleanup();
              } else if (m.state === 'closed') {
                R.connected = false;
                /* 服务端可能 4 秒内补发自动诊断（diag），稍等再清理通道 */
                if (R.diagTimer) clearTimeout(R.diagTimer);
                R.diagTimer = setTimeout(() => cleanup(), 5500);
              }
              }
            } else if (m.type === 'frame') drawFrame(m.jpg);
          };
          sock.onclose = () => {
            if (R.connected) setStatus('连接已断开');
            else if (!gotAny) setStatus('WebSocket 被关闭（端点不通或鉴权失败）：检查 /panel/wsrdp 是否可达；直接在浏览器打开该地址可看到提示文本。');
            R.connected = false;
            cleanup();
          };
          sock.onerror = () => { setStatus('WebSocket 连接失败：' + location.host + CM.base + '/wsrdp 不可达'); };
        };
        discBtn.onclick = () => {
          if (R.sock && R.sock.readyState === 1) R.sock.send(JSON.stringify({ type: 'disconnect' }));
          rdpCleanup();
          setStatus('未连接（已手动断开）');
          connectBtn.style.display = ''; connectBtn.disabled = false;
          discBtn.style.display = 'none'; fullBtn.style.display = 'none';
        };
        fullBtn.onclick = () => {
          if (document.fullscreenElement) document.exitFullscreen();
          else stage.requestFullscreen && stage.requestFullscreen();
        };
        cv.addEventListener('mousemove', (e) => sendMouse(e));
        cv.addEventListener('mousedown', (e) => { e.preventDefault(); cv.focus(); R.keyGrab = true; sendMouse(e, true); });
        cv.addEventListener('mouseup', (e) => sendMouse(e, false));
        window.addEventListener('mouseup', (e) => { if (e.target !== cv) sendMouse(e, false); });
        cv.addEventListener('wheel', (e) => {
          e.preventDefault();
          if (!R.connected || !R.sock) return;
          const r = cv.getBoundingClientRect();
          const x = Math.max(0, Math.min(cv.width - 1, Math.round((e.clientX - r.left) * cv.width / Math.max(1, r.width))));
          const y = Math.max(0, Math.min(cv.height - 1, Math.round((e.clientY - r.top) * cv.height / Math.max(1, r.height))));
          R.sock.send(JSON.stringify({ type: 'input', kind: 'mouse', x, y, wheel: e.deltaY > 0 ? 1 : -1 }));
        }, { passive: false });
        cv.addEventListener('contextmenu', (e) => e.preventDefault());
        window.addEventListener('keydown', onKeyDown);
        window.addEventListener('keyup', onKeyUp);
        window.addEventListener('blur', onBlur);
        R.listeners.push(['keydown', onKeyDown], ['keyup', onKeyUp], ['blur', onBlur]);
      }

      async function render() {
        if (S.tab !== 'rdp') rdpCleanup();
        body.innerHTML = '<div class="ui-skeleton"></div>';
        try {
          if (S.tab === 'hosts-mgmt') await renderHostsMgmt();
          else if (S.tab === 'rdp') await renderRdp();
          else if (S.tab === 'perf') await renderPerf();
          else if (S.tab === 'update') await renderUpdate();
          else if (S.tab === 'hosts') await renderHosts();
          else if (S.tab === 'startup') await renderStartup();
          else if (S.tab === 'env') await renderEnv();
          else if (S.tab === 'sys') await renderSys();
          else await renderOverview();
        } catch (e) {
          body.innerHTML = '<div class="card"><div class="muted">' + esc(e.message) + '</div></div>';
        }
      }

      S.tab = S.tab || 'overview';
      renderTabs();
      render();
    },
    hide() {
      /* 离开页面时释放远程桌面通道（后台页面不再需要串流） */
      if (CM._rdpCleanup) { CM._rdpCleanup(); }
    },
  });
})();
