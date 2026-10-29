/* Security page */
(function () {
  'use strict';

  registerPage({
    id: 'security',
    title: '安全管理',
    async mount(root) {
      const isWin = CM.isWindows();
      if (isWin) return this.mountWin(root);
      const [d, rl] = await Promise.all([
        CM.api.get('/security'),
        CM.api.get('/relayops/ratelimit').catch(() => null),
      ]);
      const s = d.sshd || {};
      const ufwOn = d.ufw.active;

      root.innerHTML = `
      ${isWin ? '<div class="card mb"><div class="muted small">Windows：使用 <b>高级防火墙 (netsh)</b> 与本地安全策略；OpenSSH 为可选功能。下方 Linux 专用区块在 Windows 上仅作说明。</div></div>' : ''}
      <div class="grid g4" id="s-cards"></div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">${isWin ? 'OpenSSH 服务（可选）' : 'SSH 服务配置 (sshd 有效配置)'}
            <div class="right"><button class="btn btn-sm btn-primary" id="s-sshd-apply">${icon('save', { size: 13 })} 应用并重载 SSH</button></div>
          </div>
          <div class="grid g2" style="gap:10px">
            <label class="fld">SSH 端口<input class="inp mono" id="s-port" value="${fmt.esc(s.port || '22')}" style="width:110px"></label>
            <label class="fld">MaxAuthTries<input class="inp mono" id="s-mauth" value="${fmt.esc(s.maxauthtries || '6')}" style="width:110px"></label>
            <label class="fld">PermitRootLogin
              <select class="inp" id="s-rootlogin">${['yes', 'prohibit-password', 'without-password', 'no'].map((o) => `<option ${s.permitrootlogin === o ? 'selected' : ''}>${o}</option>`).join('')}</select>
            </label>
            <label class="fld">PasswordAuthentication
              <select class="inp" id="s-pwauth">${['yes', 'no'].map((o) => `<option ${s.passwordauthentication === o ? 'selected' : ''}>${o}</option>`).join('')}</select>
            </label>
            <label class="fld">PubkeyAuthentication
              <select class="inp" id="s-pubkey">${['yes', 'no'].map((o) => `<option ${s.pubkeyauthentication === o ? 'selected' : ''}>${o}</option>`).join('')}</select>
            </label>
            <label class="fld">PermitEmptyPasswords
              <select class="inp" id="s-emptypw">${['no', 'yes'].map((o) => `<option ${s.permitemptypasswords === o ? 'selected' : ''}>${o}</option>`).join('')}</select>
            </label>
          </div>
          <div class="muted small mt">${isWin ? 'Windows 不通过面板改写 sshd_config；请在「设置 → 应用 → 可选功能」安装 OpenSSH 服务器后使用组策略或配置文件。' : '修改会写入 <code>/etc/ssh/sshd_config.d/99-cloud-monitor.conf</code>，先执行 <code>sshd -t</code> 校验，失败自动放弃。建议先配好密钥再关闭密码认证。'}</div>
        </div>

        <div class="card">
          <div class="card-title">Fail2ban 防爆破 ${d.fail2ban.active ? ui.badge('运行中', 'ok') : ui.badge('未运行', 'bad')}
            <div class="right">
              ${d.fail2ban.active
                ? `<button class="btn btn-sm btn-danger" id="s-f2b-stop">停止</button>`
                : `<button class="btn btn-sm btn-ok" id="s-f2b-install">安装并启动</button><button class="btn btn-sm" id="s-f2b-start">启动服务</button>`}
            </div>
          </div>
          <pre class="pre" style="max-height:130px">${fmt.esc(d.fail2ban.status || 'fail2ban 未安装/未运行')}</pre>
          <div class="row mt">
            <input class="inp mono" id="s-f2b-ip" placeholder="要解封的 IP" style="width:170px">
            <button class="btn btn-sm" id="s-f2b-unban">解封 IP</button>
          </div>
          <div class="muted small mt">最近封禁：<span class="mono">${fmt.esc(d.fail2ban.banned || '无数据')}</span></div>
        </div>
      </div>

      ${rl ? (() => {
        const zoneRows = rl.zones || [];
        const appliedRows = rl.applied || [];
        const jailRows = rl.jails || [];
        const banned = jailRows.reduce((a, j) => a + (j.currentlyBanned || 0), 0);
        const rateZones = zoneRows.filter((z) => z.type !== 'global');
        const globals = zoneRows.filter((z) => z.type === 'global');
        return `
      <div class="grid g4 mb">
        <div class="card stat"><div class="stat-label">429 拦截</div><div class="stat-value">${rl.limited429}</div><div class="stat-sub">最近 ${fmt.esc(rl.windowLines)} 条请求中</div></div>
        <div class="card stat"><div class="stat-label">限流日志</div><div class="stat-value">${rl.errorLimitCount}</div><div class="stat-sub">error.log 累计</div></div>
        <div class="card stat"><div class="stat-label">限流区域</div><div class="stat-value">${rateZones.length}</div><div class="stat-sub">limit_req / limit_conn</div></div>
        <div class="card stat"><div class="stat-label">当前封禁 IP</div><div class="stat-value" style="color:${banned ? 'var(--danger,#ef4444)' : 'inherit'}">${banned}</div><div class="stat-sub">${jailRows.length} 个 jail</div></div>
      </div>

      <div class="card mb">
        <div class="card-title">${icon('firewall', { size: 14 })} 限流 / CC 防护 ${rl.present ? ui.badge('已启用', 'ok') : ui.badge('未配置', 'bad')}
          <div class="right"><button class="btn btn-sm" id="s-rl-refresh">刷新</button></div>
        </div>
        <div class="grid g2" style="gap:14px">
          <div>
            <div class="muted small mb">限流区域 (${rateZones.length})</div>
            ${ui.table([
              { k: 'type', label: '类型', render: (v) => ui.badge(v === 'req' ? '速率' : (v === 'conn' ? '并发' : '全局'), v === 'global' ? 'gray' : 'ok') },
              { k: 'name', label: '名称 / 值', cls: 'mono', render: (v, r) => fmt.esc(v) + (r.rate ? ` · ${fmt.esc(r.rate)}` : (r.size ? ` · ${fmt.esc(r.size)}` : '')) },
            ], rateZones.concat(globals.map((g) => ({ type: 'global', name: g.name, value: g.value }))))}
          </div>
          <div>
            <div class="muted small mb">应用位置 (${appliedRows.length})</div>
            ${ui.table([
              { k: 'context', label: '上下文', cls: 'mono wrap', render: (v) => fmt.esc(String(v).replace(/\s*\{$/, '')) },
              { k: 'zone', label: '区域', cls: 'mono', render: (v, r) => fmt.esc(v) + (r.kind === 'req' ? ` burst=${fmt.esc(r.burst || '-')}${r.nodelay ? ' nodelay' : ''}` : ` ×${fmt.esc(r.value || '')}`) },
            ], appliedRows)}
          </div>
        </div>

        <div class="muted small mt mb">Fail2ban jail (${jailRows.length})</div>
        ${ui.table([
          { k: 'name', label: 'Jail', cls: 'mono' },
          { k: 'currentlyBanned', label: '当前封禁', cls: 'num' },
          { k: 'totalBanned', label: '累计封禁', cls: 'num' },
          { k: 'ips', label: '被封 IP', cls: 'mono wrap', render: (v) => (v && v.length ? fmt.esc(v.join(', ')) : '<span class="muted">无</span>') },
        ], jailRows)}

        <div class="muted small mt">配置：<code>${fmt.esc(rl.logFiles.conf)}</code> · 访问日志：<code>${fmt.esc(rl.logFiles.access)}</code></div>
        ${rl.byHour && rl.byHour.length ? `
          <div class="muted small mt mb">最近按小时的 429 拦截</div>
          ${ui.table([
            { k: 'hour', label: '小时', cls: 'mono' },
            { k: 'count', label: '429 次数', cls: 'num' },
          ], rl.byHour.slice(-24), { style: 'max-height:240px' })}` : ''}
        <div class="muted small mt">修改限流阈值：编辑 <code>00-limits.conf</code>，然后 <code>nginx -t && systemctl reload nginx</code>。</div>
      </div>`;
      })() : ''}

      <div class="card">
        <div class="card-title">内核/系统安全参数 (sysctl)</div>
        <div id="s-sysctl"></div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">用户管理 (${d.users.length})</div>
          <div class="row mb">
            <input class="inp mono" id="u-name" placeholder="用户名" style="width:120px">
            <input class="inp" id="u-pw" type="password" placeholder="密码" style="width:140px">
            <label class="row small muted"><input type="checkbox" id="u-sudo"> sudo 权限</label>
            <button class="btn btn-sm btn-ok" id="u-create">${icon('user', { size: 12 })} 创建用户</button>
          </div>
          ${ui.table([
            { k: 'name', label: '用户', cls: 'mono' },
            { k: 'uid', label: 'UID', cls: 'num' },
            { k: 'home', label: '家目录', cls: 'mono' },
            { k: 'shell', label: 'Shell', cls: 'mono' },
            { k: 'name', label: '操作', render: (v) => v === 'root' ? '<span class="muted small">—</span>' : `<span class="row" style="gap:4px">
                <button class="btn btn-sm" data-upw="${fmt.esc(v)}">改密</button>
                <button class="btn btn-sm" data-usudo="${fmt.esc(v)}">sudo</button>
                <button class="btn btn-sm btn-danger" data-udel="${fmt.esc(v)}">删除</button></span>` },
          ], d.users)}
          <div class="muted small mt">sudoers：</div>
          <pre class="pre" style="max-height:110px">${fmt.esc(d.sudoers.join('\n') || '(无)')}</pre>
        </div>
        <div class="card">
          <div class="card-title">SSH 授权密钥 (authorized_keys)</div>
          <div id="sk-list"><span class="muted small">加载中…</span></div>
          <div class="mt">
            <textarea class="inp mono" id="sk-key" placeholder="粘贴公钥 (ssh-ed25519 AAAA... user@host)" style="min-height:64px"></textarea>
            <button class="btn btn-sm btn-ok mt" id="sk-add">${icon('key', { size: 12 })} 添加公钥</button>
          </div>
        </div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">最近登录与失败尝试</div>
          <div class="muted small">成功登录 (last)</div>
          <pre class="pre" style="max-height:150px">${fmt.esc(d.recentLogins.join('\n') || '(无)')}</pre>
          <div class="muted small mt">失败尝试 (lastb)</div>
          <pre class="pre" style="max-height:180px">${fmt.esc(d.recentFailed.join('\n') || '(无记录)')}</pre>
        </div>
      </div>`;

      /* posture cards */
      const fwRules = (d.ufw && Array.isArray(d.ufw.rules)) ? d.ufw.rules : [];
      const fwLabel = isWin ? 'Windows 防火墙' : '主机防火墙 UFW';
      const fwSub = isWin ? (d.ufw && d.ufw.raw ? fmt.esc(String(d.ufw.raw).slice(0, 48)) : 'netsh advfirewall') : `${fwRules.length} 条规则`;
      document.getElementById('s-cards').innerHTML = `
        <div class="card stat"><div class="stat-label">${fwLabel}</div><div class="stat-value" style="font-size:22px;color:${ufwOn ? '#4ade80' : '#facc15'}">${ufwOn ? '已启用' : '未启用'}</div><div class="stat-sub">${fwSub}</div></div>
        <div class="card stat"><div class="stat-label">Root 密码登录</div><div class="stat-value" style="font-size:22px;color:${s.permitrootlogin === 'yes' ? '#f87171' : '#4ade80'}">${fmt.esc(s.permitrootlogin || '?')}</div><div class="stat-sub">PermitRootLogin</div></div>
        <div class="card stat"><div class="stat-label">SSH 密码认证</div><div class="stat-value" style="font-size:22px;color:${s.passwordauthentication === 'yes' ? '#facc15' : '#4ade80'}">${fmt.esc(s.passwordauthentication || '?')}</div><div class="stat-sub">PasswordAuthentication</div></div>
        <div class="card stat"><div class="stat-label">Fail2ban</div><div class="stat-value" style="font-size:22px;color:${d.fail2ban.active ? '#4ade80' : '#f87171'}">${d.fail2ban.active ? '防护中' : '未防护'}</div><div class="stat-sub">SSH 防爆破</div></div>`;

      /* sysctl editable grid */
      const sc = document.getElementById('s-sysctl');
      sc.innerHTML = `<div class="grid g2" style="gap:8px">` + Object.entries(d.sysctl).map(([k, v]) => `
        <div class="row" style="gap:8px">
          <span class="mono small" style="flex:1;min-width:220px">${fmt.esc(k)}</span>
          <input class="inp mono" data-sck="${fmt.esc(k)}" value="${fmt.esc(v == null ? '' : v)}" style="width:130px">
          <button class="btn btn-sm" data-scapply="${fmt.esc(k)}">应用</button>
        </div>`).join('') + `</div>`;
      sc.querySelectorAll('[data-scapply]').forEach((b) => {
        b.onclick = async () => {
          const key = b.dataset.scapply;
          const val = sc.querySelector(`[data-sck="${key}"]`).value;
          const okc = await modal.confirm('应用 sysctl', `设置 <code>${fmt.esc(key)}=${fmt.esc(val)}</code> 并持久化？`);
          if (!okc) return;
          runAction('/security/action', { action: 'sysctl-set', params: { key, value: val } }).catch(() => {});
        };
      });

      /* sshd apply */
      /* CSP 为 script-src 'self'，内联 onclick 会被拦截，事件统一在此绑定 */
      const rlRefresh = document.getElementById('s-rl-refresh');
      if (rlRefresh) rlRefresh.onclick = () => location.reload();

      document.getElementById('s-sshd-apply').onclick = async () => {
        const params = {
          port: document.getElementById('s-port').value,
          maxAuthTries: document.getElementById('s-mauth').value,
          permitRootLogin: document.getElementById('s-rootlogin').value,
          passwordAuthentication: document.getElementById('s-pwauth').value,
          pubkeyAuthentication: document.getElementById('s-pubkey').value,
          permitEmptyPasswords: document.getElementById('s-emptypw').value,
        };
        const warn = params.passwordAuthentication === 'no'
          ? ' <span style="color:#f87171"><b>警告：将关闭密码认证，请确认已配置 SSH 密钥，否则可能无法再登录！</b></span>' : '';
        const okc = await modal.confirm('应用 SSH 配置', `将写入并重载 sshd：\n<pre class="pre" style="margin:8px 0">${fmt.esc(JSON.stringify(params, null, 2))}</pre>${warn}`, { danger: true });
        if (!okc) return;
        try { const r = await runAction('/security/action', { action: 'sshd-apply', params }, { showOutput: true, title: 'SSH 配置应用' }); }
        catch (_) {}
      };

      /* fail2ban buttons */
      const f2bInstall = document.getElementById('s-f2b-install');
      if (f2bInstall) f2bInstall.onclick = async () => {
        const okc = await modal.confirm('安装 Fail2ban', '将执行 apt-get install fail2ban 并启动服务（需要外网连接，可能需要 1-2 分钟）。');
        if (!okc) return;
        ui.busy(f2bInstall, true);
        try { await runAction('/security/action', { action: 'fail2ban-install' }, { reload: () => location.reload(), reloadDelay: 1500 }); }
        catch (_) { ui.busy(f2bInstall, false); }
      };
      const f2bStart = document.getElementById('s-f2b-start');
      if (f2bStart) f2bStart.onclick = () => runAction('/security/action', { action: 'fail2ban-start' }, { reload: () => location.reload() }).catch(() => {});
      const f2bStop = document.getElementById('s-f2b-stop');
      if (f2bStop) f2bStop.onclick = async () => {
        const okc = await modal.confirm('停止 Fail2ban', '停止后将失去 SSH 防爆破保护。', { danger: true });
        if (!okc) return;
        runAction('/security/action', { action: 'fail2ban-stop' }, { reload: () => location.reload() }).catch(() => {});
      };
      document.getElementById('s-f2b-unban').onclick = () => {
        const ip = document.getElementById('s-f2b-ip').value.trim();
        if (!ip) return window.toast('请输入 IP', 'warn');
        runAction('/security/action', { action: 'fail2ban-unban', params: { ip } }).catch(() => {});
      };

      /* user management (Linux only) */
      if (isWin) {
        const sk = document.getElementById('sk-list');
        if (sk) sk.innerHTML = '<div class="muted small">Windows 上请通过「可选功能 → OpenSSH 服务器」或本地用户管理配置登录与密钥。</div>';
        const uc = document.getElementById('u-create');
        if (uc) { uc.disabled = true; uc.title = 'Windows 面板不支持 Linux 用户管理'; }
      } else {
      document.getElementById('u-create').onclick = async () => {
        const name = document.getElementById('u-name').value.trim();
        const password = document.getElementById('u-pw').value;
        const sudo = document.getElementById('u-sudo').checked;
        if (!name || !password) return window.toast('请填写用户名和密码', 'warn');
        const okc = await modal.confirm('创建用户', `创建用户 <b class="mono">${fmt.esc(name)}</b>${sudo ? ' 并授予 sudo 权限' : ''}？`);
        if (!okc) return;
        runAction('/user', { action: 'create', params: { name, password, sudo } }, { reload: () => location.reload() }).catch(() => {});
      };
      root.querySelectorAll('[data-udel]').forEach((b) => {
        b.onclick = async () => {
          const okc = await modal.confirm('删除用户', `将删除用户 <b class="mono">${fmt.esc(b.dataset.udel)}</b> 及其家目录（userdel -r），不可恢复！`, { danger: true, okText: '删除' });
          if (!okc) return;
          runAction('/user', { action: 'delete', params: { name: b.dataset.udel } }, { reload: () => location.reload() }).catch(() => {});
        };
      });
      root.querySelectorAll('[data-upw]').forEach((b) => {
        b.onclick = async () => {
          const r = await modal.prompt('修改密码 - ' + b.dataset.upw, [{ name: 'password', label: '新密码', type: 'password' }], { okText: '修改' });
          if (!r || !r.password) return;
          runAction('/user', { action: 'passwd', params: { name: b.dataset.upw, password: r.password } }).catch(() => {});
        };
      });
      root.querySelectorAll('[data-usudo]').forEach((b) => {
        b.onclick = async () => {
          const okc = await modal.confirm('授予 sudo', `将 <b class="mono">${fmt.esc(b.dataset.usudo)}</b> 加入 sudo 组？`, { danger: true });
          if (!okc) return;
          runAction('/user', { action: 'sudo-grant', params: { name: b.dataset.usudo } }).catch(() => {});
        };
      });

      /* ssh keys */
      const loadKeys = async () => {
        try {
          const r = await CM.api.post('/user', { action: 'sshkey-list' });
          const lines = (r.output || '').split('\n').filter((l) => l.trim());
          document.getElementById('sk-list').innerHTML = lines.length ? ui.table([
            { k: 'type', label: '类型', cls: 'mono', render: (v) => fmt.esc(v) },
            { k: 'key', label: '公钥', cls: 'mono wrap', render: (v) => fmt.esc(v.slice(0, 60)) + '…' },
            { k: 'comment', label: '备注' },
            { k: 'key', label: '', render: (v) => `<button class="btn btn-sm btn-danger" data-skdel="${fmt.esc(v)}">删除</button>` },
          ], lines.map((l) => { const p = l.trim().split(/\s+/); return { type: p[0], key: p[1] || l, comment: p[2] || '' }; }))
            : '<div class="muted small mb">暂无授权密钥（使用密钥登录更安全）</div>';
          document.querySelectorAll('[data-skdel]').forEach((btn) => {
            btn.onclick = async () => {
              const okc = await modal.confirm('删除公钥', '删除后持有该密钥的客户端将无法登录。', { danger: true });
              if (!okc) return;
              runAction('/user', { action: 'sshkey-del', params: { key: btn.dataset.skdel } }, { reload: loadKeys }).catch(() => {});
            };
          });
        } catch (e) { document.getElementById('sk-list').innerHTML = `<div class="muted small">${fmt.esc(e.message)}</div>`; }
      };
      loadKeys();
      document.getElementById('sk-add').onclick = async () => {
        const key = document.getElementById('sk-key').value.trim();
        if (!key) return window.toast('请粘贴公钥', 'warn');
        try { await runAction('/user', { action: 'sshkey-add', params: { key } }); document.getElementById('sk-key').value = ''; loadKeys(); }
        catch (_) {}
      };
      }
    },

    /* ============ Windows：防火墙 / 本地用户 / 组 / 登录会话（原 Windows 工具页并入） ============ */
    async mountWin(root) {
      const esc = fmt.esc;
      const act = (op, extra) => CM.api.post('/windows/action', Object.assign({ op }, extra || {}));
      const [fw, us, gr, se] = await Promise.all([
        CM.api.get('/windows/firewall', 120000).catch(() => ({ rules: [] })),
        CM.api.get('/windows/users', 60000).catch(() => ({ users: [] })),
        CM.api.get('/windows/groups', 90000).catch(() => ({ groups: [] })),
        CM.api.get('/windows/sessions', 60000).catch(() => ({ sessions: [] })),
      ]);
      const rules = (fw.rules || []).slice(0, 80);
      root.innerHTML = `
        <div class="card mb">
          <div class="card-title">${icon('firewall', { size: 13 })} 防火墙
            <div class="right">
              <button type="button" class="btn btn-sm btn-ok" id="s-fw-on">全部启用</button>
              <button type="button" class="btn btn-sm btn-danger" id="s-fw-off">全部禁用</button>
            </div>
          </div>
          <pre class="pre small" style="max-height:90px">${esc(fw.profiles?.raw || fw.profiles || '')}</pre>
          <div class="row mt">
            <input class="inp mono" id="s-fw-spec" placeholder="8080/tcp 或 53/udp" style="width:200px">
            <button type="button" class="btn btn-sm btn-ok" id="s-fw-allow">允许</button>
            <button type="button" class="btn btn-sm btn-danger" id="s-fw-deny">阻止</button>
          </div>
          <div class="mt">${ui.table([
            { k: 'name', label: '规则名称' },
            { k: 'enabled', label: '启用', render: (v) => /true|yes|是|启用/i.test(String(v)) ? ui.badge('是', 'ok') : ui.badge(String(v || '-'), 'gray') },
            { k: 'action', label: '动作', render: (v) => /allow|允许/i.test(String(v)) ? ui.badge('允许', 'ok') : /block|阻止|deny/i.test(String(v)) ? ui.badge('阻止', 'bad') : esc(v || '-') },
            { k: 'direction', label: '方向' },
            { k: 'protocol', label: '协议' },
            { k: 'localPort', label: '端口', cls: 'mono' },
          ], rules, { style: 'max-height:32vh' })}</div>
        </div>
        <div class="card mb">
          <div class="card-title">${icon('users', { size: 13 })} 本地用户 (${(us.users || []).length})
            <div class="right"><button type="button" class="btn btn-sm btn-primary" id="s-user-add">${icon('plus', { size: 12 })} 新建用户</button></div>
          </div>
          ${ui.table([
            { k: 'name', label: '用户名', cls: 'mono' },
            { k: 'disabled', label: '状态', render: (v) => v ? ui.badge('已禁用', 'gray') : ui.badge('启用', 'ok') },
            { k: 'name', label: '操作', render: (v, r) => `<div class="row" style="gap:4px;flex-wrap:nowrap">
              <button class="btn btn-sm" data-uact="${r.disabled ? 'enable' : 'disable'}" data-uname="${esc(v)}">${r.disabled ? '启用' : '禁用'}</button>
              <button class="btn btn-sm" data-uact="setpass" data-uname="${esc(v)}">改密</button>
              <button class="btn btn-sm btn-danger" data-uact="delete" data-uname="${esc(v)}">删除</button>
            </div>` },
          ], us.users || [], { style: 'max-height:36vh' })}
        </div>
        <div class="grid g2">
          <div class="card">
            <div class="card-title">${icon('shield', { size: 13 })} 本地组 (${(gr.groups || []).length})</div>
            ${(gr.groups || []).map((g) => `<details class="mb" style="border-left:2px solid var(--border2);padding-left:8px">
              <summary class="small" style="cursor:pointer"><b class="mono">${esc(g.name)}</b> <span class="muted small">${g.memberCount} 名成员</span></summary>
              <div class="muted small mt">${esc((g.members || []).join('、') || '(空)')}</div>
            </details>`).join('') || '<div class="muted small">无数据</div>'}
          </div>
          <div class="card">
            <div class="card-title">${icon('monitor', { size: 13 })} 登录会话 (${(se.sessions || []).length})</div>
            ${ui.table([
              { k: 'name', label: '会话', cls: 'mono small' },
              { k: 'user', label: '用户', cls: 'mono small' },
              { k: 'id', label: 'ID', cls: 'num' },
              { k: 'state', label: '状态', render: (v) => /active/i.test(String(v)) ? ui.badge('活动', 'ok') : ui.badge(String(v || '-'), 'gray') },
              { k: 'id', label: '', render: (v) => `<button class="btn btn-sm btn-danger" data-logoff="${v}">注销</button>` },
            ], se.sessions || [], { style: 'max-height:36vh' })}
          </div>
        </div>`;

      const rerender = () => this.mountWin(root);
      document.getElementById('s-fw-on').onclick = async () => {
        if (!await modal.confirm('启用防火墙', '启用所有配置文件上的 Windows 防火墙？')) return;
        try { await act('firewall-toggle', { enable: true }); window.toast('已启用', 'ok'); rerender(); } catch (e) { window.toast(e.message, 'err'); }
      };
      document.getElementById('s-fw-off').onclick = async () => {
        if (!await modal.confirm('禁用防火墙', '禁用防火墙会降低安全性，确认？', { danger: true })) return;
        try { await act('firewall-toggle', { enable: false }); window.toast('已禁用', 'ok'); rerender(); } catch (e) { window.toast(e.message, 'err'); }
      };
      const portAct = async (deny) => {
        const spec = document.getElementById('s-fw-spec').value.trim();
        if (!spec) return window.toast('请输入端口', 'warn');
        try { await act('firewall-port', { spec, deny }); window.toast('规则已添加', 'ok'); rerender(); } catch (e) { window.toast(e.message, 'err'); }
      };
      document.getElementById('s-fw-allow').onclick = () => portAct(false);
      document.getElementById('s-fw-deny').onclick = () => portAct(true);

      const userAct = async (action, name, password) => {
        try {
          const r = await act('user-manage', Object.assign({ action, name }, password ? { password } : {}));
          window.toast(r.output || '操作成功', 'ok');
          rerender();
        } catch (e) { window.toast(e.message, 'err', 7000); }
      };
      root.querySelectorAll('[data-uact]').forEach((btn) => {
        btn.onclick = async () => {
          const action = btn.dataset.uact, name = btn.dataset.uname;
          if (action === 'enable') { if (await modal.confirm('启用账户', `启用账户 <b class="mono">${esc(name)}</b>？`)) userAct('enable', name); }
          else if (action === 'disable') { if (await modal.confirm('禁用账户', `禁用账户 <b class="mono">${esc(name)}</b>？当前登录会话不受影响。`, { danger: true })) userAct('disable', name); }
          else if (action === 'delete') { if (await modal.confirm('删除账户', `删除账户 <b class="mono">${esc(name)}</b>？该操作不可撤销。`, { danger: true })) userAct('delete', name); }
          else if (action === 'setpass') {
            const r = await modal.prompt('设置新密码 — ' + name, [{ name: 'password', label: '新密码（至少 6 位）' }], { okText: '修改' });
            if (r && r.password) userAct('setpass', name, r.password);
          }
        };
      });
      document.getElementById('s-user-add').onclick = async () => {
        const r = await modal.prompt('新建本地用户', [
          { name: 'name', label: '用户名' },
          { name: 'password', label: '初始密码（至少 6 位）' },
        ], { okText: '创建' });
        if (r && r.name && r.password) userAct('create', r.name.trim(), r.password);
      };
      root.querySelectorAll('[data-logoff]').forEach((btn) => {
        btn.onclick = async () => {
          if (!await modal.confirm('注销会话', `注销登录会话 <b>${esc(btn.dataset.logoff)}</b>？该会话上未保存的工作会丢失。`, { danger: true })) return;
          try { await act('session-logoff', { id: parseInt(btn.dataset.logoff, 10) }); window.toast('已注销', 'ok'); rerender(); }
          catch (e) { window.toast(e.message, 'err'); }
        };
      });
    },
  });
})();
