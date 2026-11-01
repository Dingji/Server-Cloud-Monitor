/* Settings page: panel config, password, ssh, file editor */
(function () {
  'use strict';
  let curDir = '/etc/', curFile = null;

  registerPage({
    id: 'settings',
    title: '面板设置',
    async mount(root) {
      const c = await CM.api.get('/config');
      /* 兼容旧后端：任一新引擎配置缺失时补空对象，避免模板报错 */
      c.db = c.db || {};
      ['postgres', 'mysql', 'db2', 'redis', 'mongo', 'sqlite'].forEach((k) => { if (!c.db[k] || typeof c.db[k] !== 'object') c.db[k] = {}; });
      let al = { enabled: true, cpu: 90, mem: 90, disk: 95, cooldownMin: 10 };
      try { al = (await CM.api.get('/alerts')).config || al; } catch (_) {}
      root.innerHTML = `
      <div class="grid g2">
        <div class="card">
          <div class="card-title">告警阈值（仪表盘右上角铃铛）</div>
          <div class="row mb">
            <label class="row small muted"><input type="checkbox" id="al-enabled" ${al.enabled ? 'checked' : ''}> 启用告警</label>
            <label class="fld">CPU ≥ %<input class="inp mono" id="al-cpu" type="number" min="10" max="100" value="${al.cpu}" style="width:90px"></label>
            <label class="fld">内存 ≥ %<input class="inp mono" id="al-mem" type="number" min="10" max="100" value="${al.mem}" style="width:90px"></label>
            <label class="fld">磁盘 ≥ %<input class="inp mono" id="al-disk" type="number" min="10" max="100" value="${al.disk}" style="width:90px"></label>
            <label class="fld">冷却(分钟)<input class="inp mono" id="al-cd" type="number" min="1" max="1440" value="${al.cooldownMin}" style="width:100px"></label>
            <button class="btn btn-primary" id="al-apply" style="align-self:flex-end">保存阈值</button>
          </div>
          <div class="muted small">超过阈值时页面右上角铃铛会弹出告警；同一指标在冷却时间内不重复提醒。</div>
        </div>
        <div class="card">
          <div class="card-title">界面主题色</div>
          <div class="row" id="th-colors"></div>
          <div class="muted small mt">选择后立即生效并记住偏好。</div>
        </div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">面板参数</div>
          <div class="row mb">
            <label class="fld">数据刷新间隔
              <select class="inp" id="st-rate">
                ${[500, 1000, 2000, 3000, 5000, 10000].map((v) => `<option value="${v}" ${c.refreshMs === v ? 'selected' : ''}>${CM.refreshLabel(v)}</option>`).join('')}
              </select>
            </label>
            <button class="btn btn-primary" id="st-rate-apply" style="align-self:flex-end">应用</button>
          </div>
          <div class="row mb">
            <label class="fld">出口带宽上限（Mbps，0 = 不限）
              <input class="inp mono" id="st-netlimit" type="number" min="0" max="10000" step="0.1"
                     value="${c.netLimitBps ? +(c.netLimitBps / 1e6).toFixed(1) : 0}" style="width:150px">
            </label>
            <button class="btn btn-primary" id="st-netlimit-apply" style="align-self:flex-end">应用</button>
          </div>
          <div class="muted small mb">用于仪表盘网络吞吐进度条，按真实上限显示占用比例；未设置时沿用 1 Mbps 基准。</div>
          <dl class="kv mb">
            <dt>监听地址</dt><dd>${fmt.esc(c.bind)}:${c.port}</dd>
            <dt>访问路径</dt><dd>${fmt.esc(c.basePath)}/</dd>
            <dt>运行模式</dt><dd>${c.underSystemd ? 'systemd 服务' : '手动进程'}</dd>
          </dl>
          <div class="row">
            <button class="btn btn-danger" id="st-restart">重启面板服务</button>
            <span class="muted small">修改端口/路径需编辑 config.json 后重启</span>
          </div>
        </div>

        <div class="card">
          <div class="card-title">修改面板密码</div>
          <label class="fld mb">当前密码<input type="password" class="inp" id="st-oldpw" autocomplete="current-password"></label>
          <label class="fld mb">新密码（至少 8 位）<input type="password" class="inp" id="st-newpw" autocomplete="new-password"></label>
          <button class="btn btn-primary" id="st-pw-apply">更新密码</button>
        </div>
      </div>

      <div class="card">
        <div class="card-title">SSH 连接（用于 Web 终端的 PTY）</div>
        <div class="row mb">
          <label class="fld">主机<input class="inp mono" id="st-ssh-host" value="${fmt.esc(c.ssh.host)}" style="width:180px"></label>
          <label class="fld">端口<input class="inp mono" id="st-ssh-port" value="${c.ssh.port}" style="width:90px"></label>
          <label class="fld">用户<input class="inp mono" id="st-ssh-user" value="${fmt.esc(c.ssh.user)}" style="width:120px"></label>
          <label class="fld">密码（留空保持不变）<input type="password" class="inp" id="st-ssh-pw" style="width:180px"></label>
          <button class="btn btn-primary" id="st-ssh-apply" style="align-self:flex-end">保存</button>
        </div>
        <div class="muted small">终端优先使用密钥认证（<code>keys/pty_key</code>），密码为备用。修改后新终端会话生效。</div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">PostgreSQL 连接（默认使用 peer 认证 su - postgres）</div>
          <div class="row">
            <label class="fld">用户<input class="inp mono" id="st-pg-user" value="${fmt.esc(c.db.postgres.user)}" style="width:130px"></label>
            <label class="fld">TCP 主机<input class="inp mono" id="st-pg-host" value="${fmt.esc(c.db.postgres.host)}" placeholder="留空=本地 socket" style="width:160px"></label>
            <label class="fld">端口<input class="inp mono" id="st-pg-port" value="${fmt.esc(c.db.postgres.port)}" placeholder="5432" style="width:90px"></label>
            <label class="fld">密码<input type="password" class="inp" id="st-pg-pw" placeholder="${c.db.postgres.hasPassword ? '已设置(留空不变)' : '无'}" style="width:150px"></label>
          </div>
          <div class="muted small mt">留空主机则走本地 socket + peer 认证（Ubuntu 默认，无需密码）。</div>
        </div>
        <div class="card">
          <div class="card-title">MySQL / MariaDB 连接（默认 root + socket）</div>
          <div class="row">
            <label class="fld">用户<input class="inp mono" id="st-my-user" value="${fmt.esc(c.db.mysql.user)}" style="width:130px"></label>
            <label class="fld">TCP 主机<input class="inp mono" id="st-my-host" value="${fmt.esc(c.db.mysql.host)}" placeholder="留空=本地 socket" style="width:160px"></label>
            <label class="fld">端口<input class="inp mono" id="st-my-port" value="${fmt.esc(c.db.mysql.port)}" placeholder="3306" style="width:90px"></label>
            <label class="fld">密码<input type="password" class="inp" id="st-my-pw" placeholder="${c.db.mysql.hasPassword ? '已设置(留空不变)' : '无'}" style="width:150px"></label>
          </div>
          <div class="row mt"><button class="btn btn-primary btn-sm" id="st-db-save">保存数据库连接</button></div>
        </div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">IBM Db2 连接</div>
          <div class="row">
            <label class="fld">实例用户<input class="inp mono" id="st-db2-inst" value="${fmt.esc(c.db.db2.instance)}" style="width:140px"></label>
            <label class="fld">数据库<input class="inp mono" id="st-db2-name" value="${fmt.esc(c.db.db2.database)}" placeholder="SAMPLE" style="width:130px"></label>
            <label class="fld">端口<input class="inp mono" id="st-db2-port" value="${fmt.esc(c.db.db2.port)}" placeholder="50000" style="width:90px"></label>
          </div>
          <div class="muted small mt">SQL 通过 <code>su - 实例用户</code> 执行，需该用户存在且已创建实例。</div>
        </div>
        <div class="card">
          <div class="card-title">Redis 连接</div>
          <div class="row">
            <label class="fld">主机<input class="inp mono" id="st-rd-host" value="${fmt.esc(c.db.redis.host)}" style="width:170px"></label>
            <label class="fld">端口<input class="inp mono" id="st-rd-port" value="${fmt.esc(c.db.redis.port)}" style="width:90px"></label>
            <label class="fld">默认库<input class="inp mono" id="st-rd-db" value="${fmt.esc(c.db.redis.db)}" style="width:80px"></label>
            <label class="fld">密码<input type="password" class="inp" id="st-rd-pw" placeholder="${c.db.redis.hasPassword ? '已设置(留空不变)' : '无'}" style="width:150px"></label>
          </div>
        </div>
      </div>

      <div class="grid g2">
        <div class="card">
          <div class="card-title">MongoDB 连接</div>
          <div class="row">
            <label class="fld">主机<input class="inp mono" id="st-mo-host" value="${fmt.esc(c.db.mongo.host)}" style="width:170px"></label>
            <label class="fld">端口<input class="inp mono" id="st-mo-port" value="${fmt.esc(c.db.mongo.port)}" style="width:90px"></label>
            <label class="fld">用户<input class="inp mono" id="st-mo-user" value="${fmt.esc(c.db.mongo.user)}" placeholder="留空=不认证" style="width:130px"></label>
            <label class="fld">认证库<input class="inp mono" id="st-mo-authdb" value="${fmt.esc(c.db.mongo.authdb)}" style="width:110px"></label>
            <label class="fld">密码<input type="password" class="inp" id="st-mo-pw" placeholder="${c.db.mongo.hasPassword ? '已设置(留空不变)' : '无'}" style="width:150px"></label>
          </div>
        </div>
        <div class="card">
          <div class="card-title">SQLite 搜索目录</div>
          <div class="row">
            <label class="fld" style="flex:1">允许搜索的根目录（冒号分隔）<input class="inp mono" id="st-sq-roots" value="${fmt.esc(c.db.sqlite.roots)}" placeholder="/opt:/srv:/root:/home" style="width:100%"></label>
          </div>
          <div class="muted small mt">面板只会在此目录范围内列出/打开 .db、.sqlite 文件（防止误开系统文件），新增目录后立即生效。</div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">配置文件编辑器 <span class="muted small">（/etc、/var/www、/opt、/srv、/root）</span></div>
        <div class="row mb">
          <input class="inp mono" id="fe-dir" value="${fmt.esc(curDir)}" style="width:340px">
          <button class="btn" id="fe-browse">${icon('folder', { size: 13 })} 浏览</button>
          <span class="muted small">nginx/sshd/fstab 配置保存后会自动校验</span>
        </div>
        <div id="fe-list" class="mb"></div>
        <div class="row mb"><span class="mono small" id="fe-path" style="flex:1">未选择文件</span><button class="btn btn-primary btn-sm" id="fe-save" disabled>保存文件</button></div>
        <textarea class="inp mono" id="fe-editor" style="min-height:260px" placeholder="点击上方文件加载内容…"></textarea>
        <div id="fe-result"></div>
      </div>

      <div class="card">
        <div class="card-title">关于</div>
        <div class="muted small">Cloud Monitor v1.0.0 · Node ${fmt.esc(processVersion())} · 数据每 ${CM.refreshLabel(c.refreshMs)} 从 /proc 实时采集 · 所有管理操作均写入审计日志 (logs/audit.log)</div>
      </div>`;

      function processVersion() { return (CM.me && CM.me.info && CM.me.info.nodeVersion) || ''; }

      /* theme colors */
      const curAccent = localStorage.getItem('cm_accent') || 'blue';
      const colorBox = document.getElementById('th-colors');
      colorBox.innerHTML = CM.ACCENT_NAMES.map((n) =>
        `<div class="th-color" data-th="${n}" title="${n}" style="width:30px;height:30px;border-radius:50%;cursor:pointer;border:2px solid ${n === curAccent ? '#fff' : 'transparent'};background:var(--th-${n})"></div>`).join('');
      const thStyles = document.createElement('style');
      thStyles.textContent = CM.ACCENT_NAMES.map((n) => `.th-color[data-th="${n}"]{background:var(--c-${n})}`).join('');
      document.head.appendChild(thStyles);
      const thMap = { blue: '#4370f0', green: '#10b981', purple: '#8b5cf6', orange: '#f59e0b', red: '#ef4444', cyan: '#06b6d4' };
      colorBox.querySelectorAll('.th-color').forEach((el) => { el.style.background = thMap[el.dataset.th]; });
      colorBox.querySelectorAll('[data-th]').forEach((el) => {
        el.onclick = () => { CM.applyAccent(el.dataset.th); colorBox.querySelectorAll('.th-color').forEach((x) => x.style.borderColor = x === el ? '#fff' : 'transparent'); window.toast('主题色已更新', 'ok'); };
      });

      /* alerts config */
      document.getElementById('al-apply').onclick = async () => {
        try {
          await CM.api.post('/alerts/config', {
            enabled: document.getElementById('al-enabled').checked,
            cpu: Number(document.getElementById('al-cpu').value),
            mem: Number(document.getElementById('al-mem').value),
            disk: Number(document.getElementById('al-disk').value),
            cooldownMin: Number(document.getElementById('al-cd').value),
          });
          window.toast('告警阈值已保存', 'ok');
        } catch (e) { window.toast(e.message, 'err'); }
      };

      /* database credentials */
      document.getElementById('st-db-save').onclick = async () => {
        try {
          const val = (id) => { const el = document.getElementById(id); return el ? el.value : undefined; };
          const db = {
            postgres: {
              user: val('st-pg-user'), host: val('st-pg-host'), port: val('st-pg-port'), password: val('st-pg-pw'),
            },
            mysql: {
              user: val('st-my-user'), host: val('st-my-host'), port: val('st-my-port'), password: val('st-my-pw'),
            },
            db2: { instance: val('st-db2-inst'), database: val('st-db2-name'), port: val('st-db2-port'), password: undefined },
            redis: { host: val('st-rd-host'), port: val('st-rd-port'), db: val('st-rd-db'), password: val('st-rd-pw') },
            mongo: { host: val('st-mo-host'), port: val('st-mo-port'), user: val('st-mo-user'), authdb: val('st-mo-authdb'), password: val('st-mo-pw') },
            sqlite: { roots: val('st-sq-roots') },
          };
          /* 清掉不存在的输入框传来的 undefined，避免覆盖服务端配置 */
          for (const k of Object.keys(db)) for (const k2 of Object.keys(db[k])) if (db[k][k2] === undefined) delete db[k][k2];
          await CM.api.post('/config', { db });
          window.toast('数据库连接已保存', 'ok');
          ['st-pg-pw', 'st-my-pw', 'st-rd-pw', 'st-mo-pw'].forEach((id) => { const el = document.getElementById(id); if (el) el.value = ''; });
        } catch (e) { window.toast(e.message, 'err'); }
      };

      document.getElementById('st-rate-apply').onclick = async () => {
        const v = Number(document.getElementById('st-rate').value);
        try {
          await CM.api.post('/config', { refreshMs: v });
          CM.hub.send({ type: 'refresh-interval', ms: v });
          CM.me = CM.me || {};
          CM.me.refreshMs = v;
          window.toast('刷新间隔已更新为 ' + CM.refreshLabel(v), 'ok');
        } catch (e) { window.toast(e.message, 'err'); }
      };
      document.getElementById('st-netlimit-apply').onclick = async () => {
        const mbps = Number(document.getElementById('st-netlimit').value) || 0;
        const bps = Math.round(mbps * 1e6);
        try {
          await CM.api.post('/config', { netLimitBps: bps });
          CM.me = CM.me || {};
          CM.me.netLimitBps = bps;
          window.toast(mbps ? '出口带宽上限已设为 ' + mbps + ' Mbps' : '已取消带宽上限', 'ok');
        } catch (e) { window.toast(e.message, 'err'); }
      };
      document.getElementById('st-restart').onclick = async () => {
        const okc = await modal.confirm('重启面板', '面板服务将重启（约 2-5 秒不可用），继续？', { danger: true });
        if (!okc) return;
        try { await CM.api.post('/restart'); window.toast('重启中…', 'warn'); setTimeout(() => location.reload(), 5000); }
        catch (e) { window.toast(e.message, 'err'); }
      };
      document.getElementById('st-pw-apply').onclick = async () => {
        try {
          const r = await CM.api.post('/password', {
            oldPassword: document.getElementById('st-oldpw').value,
            newPassword: document.getElementById('st-newpw').value,
          });
          window.toast(r.output, 'ok');
          document.getElementById('st-oldpw').value = '';
          document.getElementById('st-newpw').value = '';
        } catch (e) { window.toast(e.message, 'err'); }
      };
      document.getElementById('st-ssh-apply').onclick = async () => {
        try {
          await CM.api.post('/config', {
            ssh: {
              host: document.getElementById('st-ssh-host').value.trim(),
              port: Number(document.getElementById('st-ssh-port').value),
              user: document.getElementById('st-ssh-user').value.trim(),
              password: document.getElementById('st-ssh-pw').value,
            },
          });
          window.toast('SSH 设置已保存', 'ok');
          document.getElementById('st-ssh-pw').value = '';
        } catch (e) { window.toast(e.message, 'err'); }
      };

      /* ---------- file editor ---------- */
      const browse = async () => {
        const dir = document.getElementById('fe-dir').value.trim();
        try {
          const d = await CM.api.get('/files?dir=' + encodeURIComponent(dir));
          curDir = d.dir;
          document.getElementById('fe-dir').value = d.dir;
          const list = document.getElementById('fe-list');
          list.innerHTML = `<div class="table-wrap" style="max-height:220px"><table class="tbl"><tbody>` +
            d.entries.map((e) => {
              const full = (d.dir.endsWith('/') ? d.dir : d.dir + '/') + e.name;
              if (e.name === '.' || e.name === '..') return '';
              return `<tr>
                <td class="mono" style="width:110px">${fmt.esc(e.mode)}</td>
                <td class="mono"><a href="#" data-${e.isDir && !e.isLink ? 'dir' : 'file'}="${fmt.esc(full)}" style="color:${e.isDir ? '#7a9bff' : '#d9e3ef'}">${fmt.esc(e.name)}${e.isLink ? ' ' + icon('link', { size: 11 }) + ' ' + fmt.esc(e.target || '') : ''}</a></td>
              </tr>`;
            }).join('') + `</tbody></table></div>`;
          list.querySelectorAll('[data-dir]').forEach((a) => a.onclick = (ev) => { ev.preventDefault(); document.getElementById('fe-dir').value = a.dataset.dir; browse(); });
          list.querySelectorAll('[data-file]').forEach((a) => a.onclick = async (ev) => {
            ev.preventDefault();
            try {
              const f = await CM.api.get('/file?path=' + encodeURIComponent(a.dataset.file));
              curFile = a.dataset.file;
              document.getElementById('fe-path').textContent = `${curFile} · ${fmt.bytes(f.size)} · mode ${f.mode} · ${f.owner}`;
              document.getElementById('fe-editor').value = f.content;
              document.getElementById('fe-save').disabled = false;
              document.getElementById('fe-result').innerHTML = '';
            } catch (e) { window.toast(e.message, 'err'); }
          });
        } catch (e) { window.toast(e.message, 'err'); }
      };
      document.getElementById('fe-browse').onclick = browse;
      document.getElementById('fe-dir').addEventListener('keydown', (e) => { if (e.key === 'Enter') browse(); });
      document.getElementById('fe-save').onclick = async () => {
        if (!curFile) return;
        const okc = await modal.confirm('保存文件', `写入 <b class="mono">${fmt.esc(curFile)}</b>？${/nginx|sshd|fstab/.test(curFile) ? '（会自动执行配置校验）' : ''}`, { danger: true, okText: '保存' });
        if (!okc) return;
        try {
          const r = await CM.api.post('/file', { path: curFile, content: document.getElementById('fe-editor').value });
          document.getElementById('fe-result').innerHTML = `<div class="mt small">${ui.badge('已保存', 'ok')} ${r.validation ? (r.validation.ok ? ui.badge('配置校验通过', 'ok') : ui.badge('校验失败', 'bad')) : ''}</div>`;
          window.toast('文件已保存', 'ok');
        } catch (e) {
          document.getElementById('fe-result').innerHTML = `<pre class="pre mt" style="max-height:150px">${fmt.esc(e.output || e.message)}</pre>`;
          window.toast('保存失败（查看校验输出）', 'err');
        }
      };
      browse().catch(() => {});
    },
  });
})();
