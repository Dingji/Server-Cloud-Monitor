'use strict';
/*
 * Actions: every mutating operation, validated & audited.
 * Each handler returns { ok, output } or throws.
 */
const { getAgent } = require('./agent');
const { audit, logErr } = require('./util');
const collectors = require('./collectors');
const svc = require('./svc');        /* systemd / OpenRC / SysV service control */
const distro = require('./distro');  /* distribution + package manager abstraction */

const UNIT_RE = /^[a-zA-Z0-9@_.\-]+\.(service|timer|socket|target)$/;
const DEV_RE = /^[a-zA-Z0-9._\-]+$/;
const USER_RE = /^[a-z_][a-z0-9_-]{0,31}$/;
const IP_RE = /^(\d{1,3}\.){3}\d{1,3}$/;
const UFW_SPEC_RE = /^(\d{1,5}(:\d{1,5})?\/(tcp|udp)|\d{1,5}(:\d{1,5})?|(any|Anywhere))$/i;

function bad(msg) { const e = new Error(msg); e.status = 400; throw e; }
function ok(output, extra) { return Object.assign({ ok: true, output: output == null ? '' : String(output) }, extra || {}); }

async function run(cmd, opts) {
  const agent = getAgent();
  const r = await agent.run(cmd, opts);
  const out = (r.stdout + (r.stderr ? '\n[stderr] ' + r.stderr : '')).trim();
  if (r.code !== 0 && !r.timedOut) {
    const e = new Error(`命令失败 (exit ${r.code}):\n${out || '(无输出)'}`);
    e.status = 400; e.output = out; throw e;
  }
  return out;
}

/* ============ processes ============ */
async function killProcess({ pid, signal }) {
  pid = parseInt(pid, 10);
  if (!Number.isFinite(pid) || pid <= 1) bad('无效 PID');
  const sig = { TERM: 'TERM', KILL: 'KILL', HUP: 'HUP', INT: 'INT', USR1: 'USR1', STOP: 'STOP' }[String(signal || 'TERM').toUpperCase()] || 'TERM';
  const out = await run(`kill -${sig} ${pid} 2>&1 || true`);
  return ok(`已向 PID ${pid} 发送 SIG${sig}\n${out}`);
}

/* ============ services ============ */
const SVC_ACTIONS = { start: 'start', stop: 'stop', restart: 'restart', enable: 'enable', disable: 'disable', reload: 'reload', mask: 'mask', unmask: 'unmask' };
async function serviceAction({ unit, action }) {
  if (!unit || !UNIT_RE.test(unit)) bad('无效的服务单元名');
  const a = SVC_ACTIONS[String(action || '')];
  if (!a) bad('无效操作: ' + action);
  /* systemd 与 OpenRC/SysV 通吃 */
  const m = await svc.detect();
  const opts = { systemd: m.systemd, init: m.init };
  const cmd = (a === 'mask' || a === 'unmask') ? svc.maskCmd(a, unit) : svc.svcCmd(a, unit, opts);
  const out = await run(cmd, { timeout: 30000 });
  return ok(out, { state: out.split('\n').pop() });
}

/* ============ disk ============ */
const DISK_ACTIONS = {
  'pkg-clean': { cmd: '', desc: '清理软件包管理器缓存（自动适配发行版）', dynamic: 'clean' },
  'pkg-update': { cmd: '', desc: '刷新软件包索引', dynamic: 'update' },
  'pkg-autoremove': { cmd: '', desc: '卸载不再需要的软件包', timeout: 300000, dynamic: 'autoremove' },
  'apt-clean': { cmd: 'apt-get clean -y 2>&1', desc: '清理 apt 包缓存' },
  'apt-autoremove': { cmd: 'DEBIAN_FRONTEND=noninteractive apt-get -y autoremove --purge 2>&1 | tail -30', desc: '卸载不再需要的软件包', timeout: 300000 },
  'journal-vacuum': { cmd: 'journalctl --vacuum-size=200M 2>&1 | tail -5', desc: '将系统日志裁剪到 200MB' },
  'fstrim': { cmd: 'fstrim -av 2>&1', desc: '对所有挂载点执行 TRIM', timeout: 120000 },
  'drop-caches': { cmd: 'sync; echo 3 > /proc/sys/vm/drop_caches; echo 缓存已释放', desc: '释放页缓存/目录项/inode 缓存' },
  'docker-prune': { cmd: 'docker system prune -af 2>&1 | tail -20', desc: '清理无容器使用的镜像/网络/构建缓存', timeout: 300000 },
  'analyze': {
    cmd: "echo '== / 一级目录 =='; du -xhd1 / 2>/dev/null | sort -rh | head -15; echo; echo '== /var 二级 =='; du -xhd2 /var 2>/dev/null | sort -rh | head -15; echo; echo '== /root 二级 =='; du -xhd2 /root 2>/dev/null | sort -rh | head -10; echo; echo '== 大于 100MB 的文件 =='; find / -xdev -type f -size +100M -exec ls -lh {} \\; 2>/dev/null | awk '{print $5, $9}' | sort -rh | head -20",
    desc: '磁盘空间分析', timeout: 180000,
  },
};
async function diskAction({ action }) {
  const a = DISK_ACTIONS[action];
  if (!a) bad('未知磁盘操作: ' + action);
  let cmd = a.cmd;
  /* 发行版无关的包管理动作 */
  if (a.dynamic) {
    const env = await distro.detect();
    if (!env.pm) bad('未检测到受支持的包管理器（apt/dnf/yum/zypper/apk/pacman）');
    cmd = distro.PM.action(env, a.dynamic, []);
    if (!cmd) bad('当前包管理器不支持该操作: ' + a.dynamic);
  }
  const out = await run(cmd, { timeout: a.timeout || 60000 });
  return ok(out || '(无输出)');
}

/* ============ network / firewall ============ */
async function networkAction({ action, params = {} }) {
  switch (action) {
    case 'ufw-allow':
    case 'ufw-deny': {
      const spec = String(params.spec || '').trim();
      if (!UFW_SPEC_RE.test(spec)) bad('无效的端口/规则格式 (示例: 8080/tcp, 3306): ' + spec);
      const verb = action === 'ufw-allow' ? 'allow' : 'deny';
      const out = await run(`ufw --force ${verb} ${spec} 2>&1; ufw status numbered | head -40`);
      return ok(out);
    }
    case 'ufw-delete': {
      const n = parseInt(params.num, 10);
      if (!Number.isFinite(n) || n < 1 || n > 10000) bad('无效规则编号');
      const out = await run(`ufw --force delete ${n} 2>&1; ufw status numbered | head -40`, { timeout: 20000 });
      return ok(out);
    }
    case 'ufw-reload': return ok(await run('ufw reload 2>&1'));
    case 'ufw-enable': return ok(await run('ufw --force enable 2>&1'));
    case 'ufw-disable': return ok(await run('ufw --force disable 2>&1'));
    case 'iface-down':
    case 'iface-up': {
      const dev = String(params.dev || '');
      if (!DEV_RE.test(dev)) bad('无效网卡名');
      const st = await getAgent().run(`ip -o link show ${dev} 2>/dev/null`);
      if (st.code !== 0) bad('网卡不存在: ' + dev);
      const dir = action === 'iface-up' ? 'up' : 'down';
      return ok(await run(`ip link set dev ${dev} ${dir} 2>&1 && echo ${dev} -> ${dir} OK`));
    }
    default: bad('未知网络操作: ' + action);
  }
}

/* ============ security ============ */
async function securityAction({ action, params = {} }) {
  switch (action) {
    case 'fail2ban-install':
      return ok(await run('DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y fail2ban && systemctl enable --now fail2ban && systemctl is-active fail2ban', { timeout: 300000 }));
    case 'fail2ban-start': return ok(await run('systemctl start fail2ban; systemctl is-active fail2ban'));
    case 'fail2ban-stop': return ok(await run('systemctl stop fail2ban; systemctl is-active fail2ban || true'));
    case 'fail2ban-unban': {
      const ip = String(params.ip || '');
      if (!IP_RE.test(ip)) bad('无效 IP');
      return ok(await run(`fail2ban-client unban ${ip} 2>&1 || echo "未在封禁列表中: ${ip}"`));
    }
    case 'sysctl-set': {
      const key = String(params.key || '');
      const value = String(params.value ?? '');
      if (!/^[a-z0-9_.\-\/]+$/.test(key)) bad('无效 sysctl 键');
      if (!/^[\w\d.\-/ ]*$/.test(value)) bad('无效 sysctl 值');
      const procPath = '/proc/sys/' + key.split('.').join('/');
      const exists = await getAgent().run(`test -e ${procPath} && echo yes || echo no`);
      if (!/yes/.test(exists.stdout)) bad('sysctl 键不存在: ' + key);
      const out = await run(`sysctl -w ${key}=${value} 2>&1`);
      await run(`mkdir -p /etc/sysctl.d && grep -qF '${key}' /etc/sysctl.d/99-cloud-monitor.conf 2>/dev/null && sed -i 's|^${key}=.*|${key}=${value}|' /etc/sysctl.d/99-cloud-monitor.conf || echo '${key}=${value}' >> /etc/sysctl.d/99-cloud-monitor.conf`);
      return ok(out + '\n已持久化到 /etc/sysctl.d/99-cloud-monitor.conf');
    }
    case 'sshd-apply': {
      const p = params || {};
      const lines = ['# 由 Cloud Monitor 面板生成'];
      if (p.port) {
        const port = parseInt(p.port, 10);
        if (!(port >= 1 && port <= 65535)) bad('无效端口');
        lines.push('Port ' + port);
      }
      const bools = { permitRootLogin: ['yes', 'no', 'prohibit-password', 'without-password'], passwordAuthentication: ['yes', 'no'], permitEmptyPasswords: ['yes', 'no'], pubkeyAuthentication: ['yes', 'no'] };
      for (const [k, allowed] of Object.entries(bools)) {
        if (p[k] !== undefined && p[k] !== '') {
          const v = String(p[k]);
          if (!allowed.includes(v)) bad(`${k} 无效值: ${v}`);
          const name = k.replace(/[A-Z]/g, (c) => c.toLowerCase());
          lines.push(name.replace('permitrootlogin', 'PermitRootLogin').replace('passwordauthentication', 'PasswordAuthentication').replace('permitemptypasswords', 'PermitEmptyPasswords').replace('pubkeyauthentication', 'PubkeyAuthentication'));
          lines[lines.length - 1] += ' ' + v;
        }
      }
      if (p.maxAuthTries) {
        const n = parseInt(p.maxAuthTries, 10);
        if (!(n >= 1 && n <= 100)) bad('MaxAuthTries 无效');
        lines.push('MaxAuthTries ' + n);
      }
      if (lines.length === 1) bad('没有要应用的配置');
      const agent = getAgent();
      await run('mkdir -p /etc/ssh/sshd_config.d');
      await agent.writeFile('/etc/ssh/sshd_config.d/99-cloud-monitor.conf', lines.join('\n') + '\n');
      const test = await agent.run('sshd -t 2>&1', { timeout: 10000 });
      if (test.code !== 0) {
        throw Object.assign(new Error('sshd 配置校验失败，未应用:\n' + (test.stdout + test.stderr)), { status: 400 });
      }
      const out = await run('systemctl reload ssh sshd 2>&1 || systemctl reload ssh 2>&1 || systemctl restart ssh 2>&1');
      return ok(lines.join('\n') + '\n\nsshd -t: OK\n' + out);
    }
    case 'user-lock':
    case 'user-unlock': {
      const user = String(params.user || '');
      if (!USER_RE.test(user)) bad('无效用户名');
      const ex = await getAgent().run(`id -u ${user} 2>/dev/null`);
      if (ex.code !== 0) bad('用户不存在: ' + user);
      if (user === 'root' && action === 'user-lock') bad('不能锁定 root');
      return ok(await run(`usermod ${action === 'user-lock' ? '-L' : '-U'} ${user} 2>&1 && echo 用户 ${user} 已${action === 'user-lock' ? '锁定' : '解锁'}`));
    }
    default: bad('未知安全操作: ' + action);
  }
}

/* ============ databases ============ */
async function dbAction({ db, action }) {
  if (!/^(mysql|postgres|redis|mongo)$/.test(String(db || ''))) bad('未知数据库: ' + db);
  const serviceMap = { mysql: 'mysql', postgres: 'postgresql', redis: 'redis-server', mongo: 'mongod' };
  const svc = serviceMap[db];
  if (['start', 'stop', 'restart'].includes(action)) {
    const out = await run(`systemctl ${action} ${svc} 2>&1; sleep 1; systemctl is-active ${svc} 2>/dev/null || true`, { timeout: 60000 });
    return ok(out, { state: out.split('\n').pop() });
  }
  if (action === 'enable') return ok(await run(`systemctl enable ${svc} 2>&1`));
  if (action === 'disable') return ok(await run(`systemctl disable ${svc} 2>&1`));
  bad('未知数据库操作: ' + action);
}

async function dbQuery({ db, sql, database }) {
  const sqlStr = String(sql || '').trim();
  if (!sqlStr) bad('SQL 为空');
  if (sqlStr.length > 100000) bad('SQL 过长');
  if (!/^(mysql|postgres|redis)$/.test(String(db || ''))) bad('该数据库不支持查询控制台: ' + db);
  const agent = getAgent();
  const b64 = Buffer.from(sqlStr, 'utf8').toString('base64');
  const dbArg = database && /^[A-Za-z0-9_$\-]+$/.test(database) ? ` ${database}` : '';
  if (db === 'mysql') {
    const r = await agent.run(`echo ${b64} | base64 -d | mysql -t${dbArg} 2>&1 | head -200`, { timeout: 30000 });
    if (r.code !== 0) throw Object.assign(new Error('MySQL 查询失败:\n' + (r.stdout + r.stderr).trim()), { status: 400 });
    return ok(r.stdout.trim() || '(无结果)');
  }
  if (db === 'postgres') {
    const r = await agent.run(`echo ${b64} | base64 -d | su - postgres -c "psql -X -A -F' | ' -f -${dbArg.replace(/^-/, ' ')}" 2>&1 | head -200`, { timeout: 30000 });
    const combined = (r.stdout + r.stderr).trim();
    if (r.code !== 0 && /ERROR/i.test(combined)) throw Object.assign(new Error('PostgreSQL 查询失败:\n' + combined), { status: 400 });
    return ok(combined || '(无结果)');
  }
  if (db === 'redis') {
    const r = await agent.run(`echo ${b64} | base64 -d | redis-cli 2>&1 | head -200`, { timeout: 15000 });
    return ok(r.stdout.trim() || '(无结果)');
  }
  bad('不支持');
}

/* ============ web servers ============ */
async function webAction({ action, params = {} }) {
  switch (action) {
    case 'nginx-reload': return ok(await run('nginx -t 2>&1 && systemctl reload nginx && echo nginx 已重载', { timeout: 15000 }));
    case 'nginx-start': return ok(await run('systemctl start nginx; systemctl is-active nginx'));
    case 'nginx-stop': return ok(await run('systemctl stop nginx; systemctl is-active nginx || true'));
    case 'nginx-test': return ok(await run('nginx -t 2>&1', { timeout: 10000 }));
    case 'site-toggle': {
      const site = String(params.site || '');
      if (!/^[a-zA-Z0-9._\-]+$/.test(site)) bad('无效站点名');
      const target = `/etc/nginx/sites-available/${site}`;
      const link = `/etc/nginx/sites-enabled/${site}`;
      const ex = await getAgent().run(`test -e ${target} && echo yes || echo no`);
      if (!/yes/.test(ex.stdout)) bad('站点不存在: ' + site);
      let out;
      if (params.enable) {
        out = await run(`ln -sf ${target} ${link} 2>&1`);
      } else {
        if (site === 'opencode-relay') bad('拒绝停用 opencode-relay（生产中继服务）');
        out = await run(`rm -f ${link} 2>&1`);
      }
      const test = await getAgent().run('nginx -t 2>&1', { timeout: 10000 });
      if (test.code !== 0) {
        // rollback
        if (params.enable) await getAgent().run(`rm -f ${link}`);
        else await getAgent().run(`ln -sf ${target} ${link}`);
        throw Object.assign(new Error('nginx -t 校验失败，已回滚:\n' + (test.stdout + test.stderr)), { status: 400 });
      }
      await run('systemctl reload nginx');
      return ok(`${site} 已${params.enable ? '启用' : '停用'}，nginx 已重载\n${out}`);
    }
    case 'cert-renew': return ok(await run('certbot renew --non-interactive 2>&1 | tail -20', { timeout: 180000 }));
    default: bad('未知网站操作: ' + action);
  }
}

/* ============ file editor (config files) ============ */
const FILE_DENY = [/\/etc\/shadow/, /\/etc\/gshadow/, /ssh_host_.*_key$/, /\.pem$/, /\/etc\/sudoers$/];
const FILE_ROOTS = ['/etc/', '/var/www/', '/opt/', '/srv/', '/root/'];

function checkFileAllowed(p, forWrite) {
  if (typeof p !== 'string' || !p.startsWith('/')) bad('路径必须为绝对路径');
  if (p.includes('..')) bad('路径不允许 ..');
  if (!FILE_ROOTS.some((r) => p.startsWith(r))) bad('仅允许编辑: ' + FILE_ROOTS.join(', '));
  for (const re of FILE_DENY) if (re.test(p)) bad('出于安全考虑禁止编辑该文件');
  if (forWrite && /\.pem$|\.key$|authorized_keys$/.test(p)) bad('禁止通过面板写入密钥类文件');
  return true;
}

async function fileRead({ path: p }) {
  checkFileAllowed(p, false);
  const agent = getAgent();
  const st = await agent.run(`test -f ${JSON.stringify(p)} && stat -c '%s|%a|%U|%y' ${JSON.stringify(p)} 2>/dev/null || echo MISSING`, { timeout: 8000 });
  if (/^MISSING/.test(st.stdout.trim())) bad('文件不存在: ' + p);
  const [size, mode, owner, mtime] = st.stdout.trim().split('|');
  if (parseInt(size, 10) > 2 * 1024 * 1024) bad('文件超过 2MB，不支持在线编辑');
  const r = await agent.run(`cat ${JSON.stringify(p)} 2>&1`, { timeout: 10000 });
  return { content: r.stdout, size: parseInt(size, 10), mode, owner, mtime };
}

async function fileWrite({ path: p, content, validate }) {
  checkFileAllowed(p, true);
  if (typeof content !== 'string') bad('内容必须为字符串');
  if (Buffer.byteLength(content, 'utf8') > 2 * 1024 * 1024) bad('内容超过 2MB');
  const agent = getAgent();
  const st = await agent.run(`stat -c '%a|%U|%G' ${JSON.stringify(p)} 2>/dev/null || echo "644|root|root"`, { timeout: 8000 });
  const [mode, owner, group] = st.stdout.trim().split('|');
  const tmp = p + '.cm-tmp';
  await agent.writeFile(tmp, content);
  await run(`chown ${owner}:${group} ${JSON.stringify(tmp)} && chmod ${mode} ${JSON.stringify(tmp)} && mv -f ${JSON.stringify(tmp)} ${JSON.stringify(p)}`);
  let validation = null;
  const rules = [
    { re: /^\/etc\/nginx\//, cmd: 'nginx -t 2>&1' },
    { re: /^\/etc\/ssh\/sshd_config/, cmd: 'sshd -t 2>&1' },
    { re: /^\/etc\/fstab$/, cmd: 'findmnt --verify --tab-file /etc/fstab 2>&1 | tail -10' },
    { re: /^\/etc\/crontab$/, cmd: 'true' },
    { re: /^\/etc\/fail2ban\//, cmd: 'fail2ban-client -t 2>&1 || true' },
  ];
  const rule = rules.find((r) => r.re.test(p));
  if (rule && validate !== false) {
    const v = await agent.run(rule.cmd, { timeout: 15000 });
    validation = { cmd: rule.cmd, ok: v.code === 0, output: (v.stdout + v.stderr).trim() };
    if (v.code !== 0) {
      throw Object.assign(new Error(`文件已保存，但配置校验失败（${rule.cmd}）:\n${(v.stdout + v.stderr).trim()}\n请尽快修正，必要时可在终端回退。`), { status: 400, output: (v.stdout + v.stderr).trim() });
    }
  }
  return ok('已保存' + (validation ? '，校验通过: ' + validation.cmd : ''), { validation });
}

async function fileListing({ dir }) {
  if (typeof dir !== 'string' || !FILE_ROOTS.some((r) => dir.startsWith(r)) || dir.includes('..')) bad('目录不在允许范围');
  const r = await getAgent().run(`ls -la ${JSON.stringify(dir)} 2>/dev/null | tail -n +2`, { timeout: 8000 });
  const entries = [];
  for (const line of r.stdout.split('\n')) {
    const m = /^(\S+)\s+\d+\s+\S+\s+\S+\s+\S+\s+\S+\s+(.*)$/.exec(line.trim());
    if (!m) continue;
    let name = m[2];
    let target = null;
    const lm = /^(.*?)\s+->\s+(.*)$/.exec(name);
    if (lm) { name = lm[1].trim(); target = lm[2].trim(); }
    entries.push({ mode: m[1], name, isDir: m[1].startsWith('d'), isLink: m[1].startsWith('l'), target });
  }
  return { dir, entries };
}

/* ============ panel ============ */
async function restartPanel() {
  if (process.env.CM_UNDER_SYSTEMD === '1') {
    setTimeout(() => { getAgent().run('systemctl restart cloud-monitor >/dev/null 2>&1 &'); }, 800);
    return ok('面板重启指令已发送');
  }
  bad('仅在 systemd 服务模式下支持重启');
}

/* ============ docker ============ */
async function dockerAction({ action, params = {} }) {
  const idRe = /^[a-f0-9]{6,64}$/i;
  const nameRe = /^[a-zA-Z0-9][a-zA-Z0-9_.\-]{0,62}$/;
  switch (action) {
    case 'start': case 'stop': case 'restart': {
      const target = String(params.target || '');
      if (!idRe.test(target) && !nameRe.test(target)) bad('无效容器 ID/名称');
      return ok(await run(`docker ${action} ${target} 2>&1`, { timeout: 60000 }));
    }
    case 'rm': {
      const target = String(params.target || '');
      if (!idRe.test(target) && !nameRe.test(target)) bad('无效容器 ID/名称');
      return ok(await run(`docker rm -f ${target} 2>&1`, { timeout: 60000 }));
    }
    case 'img-rm': {
      const ref = String(params.ref || '');
      if (!/^[a-zA-Z0-9][a-zA-Z0-9_.\-\/:]{0,120}$/.test(ref)) bad('无效镜像名');
      return ok(await run(`docker rmi ${ref} 2>&1`, { timeout: 120000 }));
    }
    case 'daemon-start': return ok(await run('systemctl start docker; systemctl is-active docker', { timeout: 60000 }));
    case 'daemon-stop': return ok(await run('systemctl stop docker; systemctl is-active docker || true', { timeout: 60000 }));
    case 'daemon-enable': return ok(await run('systemctl enable docker 2>&1'));
    case 'prune': return ok(await run('docker system prune -af 2>&1 | tail -20', { timeout: 300000 }));
    default: bad('未知 Docker 操作: ' + action);
  }
}

/* ============ cron ============ */
async function cronAction({ action, params = {} }) {
  if (action === 'add') {
    const name = String(params.name || '');
    const schedule = String(params.schedule || '').trim();
    const command = String(params.command || '').trim();
    if (!/^[A-Za-z0-9_-]{1,40}$/.test(name)) bad('任务名仅允许字母数字_-(1-40位)');
    if (!/^[0-9*,\-\/ ]{1,64}$/.test(schedule)) bad('定时表达式含非法字符');
    if (!command || command.length > 500 || /[\n\r]/.test(command)) bad('命令无效（≤500字符，单行）');
    const content = `# managed by cloud-monitor\n${schedule} root ${command}\n`;
    const agent = getAgent();
    await run('mkdir -p /etc/cron.d');
    await agent.writeFile('/etc/cron.d/cm-' + name, content);
    await run(`chmod 644 /etc/cron.d/cm-${name} && chown root:root /etc/cron.d/cm-${name}`);
    /* verify crond can parse */
    const v = await agent.run(`grep -c . /etc/cron.d/cm-${name} >/dev/null && echo ok`);
    return ok(`已创建定时任务 /etc/cron.d/cm-${name}\n${schedule} root ${command}`);
  }
  if (action === 'delete') {
    const name = String(params.name || '');
    if (!/^[A-Za-z0-9_-]{1,40}$/.test(name)) bad('无效任务名');
    const f = `/etc/cron.d/cm-${name}`;
    const ex = await getAgent().run(`test -f ${f} && echo yes || echo no`);
    if (!/yes/.test(ex.stdout)) bad('任务不存在: ' + name);
    return ok(await run(`rm -f ${f} && echo 已删除 ${f}`));
  }
  bad('未知定时任务操作: ' + action);
}

/* ============ package install ============ */
const PKG_WHITELIST = {
  'docker.io': 'Docker 容器引擎',
  'mysql-server': 'MySQL 数据库',
  'postgresql': 'PostgreSQL 数据库',
  'redis-server': 'Redis 缓存',
  'certbot': 'Let\'s Encrypt 证书工具',
  'fail2ban': '防爆破守护',
  'htop': '增强版 top',
  'net-tools': 'netstat 等网络工具',
  'jq': 'JSON 命令行工具',
  'unzip': 'ZIP 解压',
  'build-essential': '编译工具链(GCC/make)',
  'vim': 'Vim 编辑器',
  'git': 'Git 版本控制',
};
async function pkgInstall({ pkg }) {
  const p = String(pkg || '');
  if (!PKG_WHITELIST[p]) bad('不在允许安装列表: ' + p + '（可在网页终端自行安装）');
  const out = await run(
    `apt-get update -qq 2>&1 | tail -2; DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y ${p} 2>&1 | tail -15; echo "=== 安装结果 ==="; command -v ${p.split('-')[0]} || dpkg -l ${p} 2>/dev/null | tail -2 || true`,
    { timeout: 600000 }
  );
  return ok(out);
}

/* ============ backups ============ */
const { BACKUP_DIR } = require('./paths');
const BACKUP_SOURCES = ['/etc', '/root', '/var/www', '/opt', '/srv'];

async function backupList() {
  const r = await run(`mkdir -p ${BACKUP_DIR}; ls -la ${BACKUP_DIR}/*.tar.gz 2>/dev/null || echo EMPTY`, { timeout: 15000 });
  const items = [];
  for (const line of String(r).split('\n')) {
    const m = /^-\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+\S+\s+(\S+)$/.exec(line.trim());
    if (m) items.push({ size: parseInt(m[1], 10), name: m[2].split('/').pop(), mtime: line.trim().split(/\s+/).slice(5, 8).join(' ') });
  }
  /* relay 状态备份：/opt/relay/backup_relay.sh，每日由 relay-backup.timer 触发 */
  let relay = null;
  try { relay = await require('./relayops').relayBackups(); } catch (e) { relay = { error: e.message, dir: null, items: [] }; }
  return { dir: BACKUP_DIR, items: items.reverse(), relay };
}
async function backupCreate({ source }) {
  const src = String(source || '');
  if (!BACKUP_SOURCES.includes(src)) bad('备份源必须是: ' + BACKUP_SOURCES.join(', '));
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const name = `cm-${src.replace(/[\/]/g, '_')}-${stamp}.tar.gz`;
  const out = await run(`mkdir -p ${BACKUP_DIR}; tar -czf ${BACKUP_DIR}/${name} -C / ${src.replace(/^\//, '')} 2>&1 | tail -3; ls -lh ${BACKUP_DIR}/${name}`, { timeout: 600000 });
  return ok(`备份完成: ${name}\n${out}`, { name });
}
async function backupDelete({ name }) {
  const n = String(name || '');
  if (!/^cm-[A-Za-z0-9._\-]+\.tar\.gz$/.test(n)) bad('非法备份文件名');
  return ok(await run(`rm -f ${BACKUP_DIR}/${n} && echo 已删除 ${n}`));
}
function backupPath(name) {
  const n = String(name || '');
  if (!/^cm-[A-Za-z0-9._\-]+\.tar\.gz$/.test(n)) return null;
  return BACKUP_DIR + '/' + n;
}

/* ============ users & ssh keys ============ */
async function userAction({ action, params = {} }) {
  const name = String(params.name || '');
  if (action === 'create') {
    if (!USER_RE.test(name)) bad('用户名仅允许小写字母开头的 [a-z0-9_-]');
    const ex = await getAgent().run(`id -u ${name} 2>/dev/null`);
    if (ex.code === 0) bad('用户已存在: ' + name);
    const pw = String(params.password || '');
    if (pw.length < 6) bad('密码至少 6 位');
    const b64 = Buffer.from(`${name}:${pw}`).toString('base64');
    let out = await run(`useradd -m -s /bin/bash ${name} 2>&1 && echo ${b64} | base64 -d | chpasswd && echo 用户创建成功`);
    if (params.sudo) out += '\n' + await run(`usermod -aG sudo ${name} && echo 已加入 sudo 组`);
    return ok(out);
  }
  if (action === 'delete') {
    if (!USER_RE.test(name)) bad('无效用户名');
    if (name === 'root') bad('不能删除 root');
    const uid = await getAgent().run(`id -u ${name} 2>/dev/null`);
    if (uid.code !== 0) bad('用户不存在');
    if (parseInt(uid.stdout.trim(), 10) < 1000) bad('拒绝通过面板删除系统用户 (UID<1000)');
    return ok(await run(`userdel -r ${name} 2>&1 && echo 用户 ${name} 已删除`, { timeout: 30000 }));
  }
  if (action === 'passwd') {
    if (!USER_RE.test(name)) bad('无效用户名');
    const pw = String(params.password || '');
    if (pw.length < 6) bad('密码至少 6 位');
    const b64 = Buffer.from(`${name}:${pw}`).toString('base64');
    return ok(await run(`echo ${b64} | base64 -d | chpasswd && echo 用户 ${name} 密码已更新`));
  }
  if (action === 'sudo-grant' || action === 'sudo-revoke') {
    if (!USER_RE.test(name)) bad('无效用户名');
    const grp = action === 'sudo-grant' ? '-aG' : '-G';
    if (action === 'sudo-revoke') {
      const cur = await getAgent().run(`id -nG ${name} 2>/dev/null`);
      const groups = cur.stdout.trim().split(/\s+/).filter((g) => g && g !== 'sudo').join(',');
      return ok(await run(`usermod ${grp} ${groups || 'users'} ${name} && echo 已移除 ${name} 的 sudo 权限`));
    }
    return ok(await run(`usermod -aG sudo ${name} && echo 已授予 ${name} sudo 权限`));
  }
  if (action === 'sshkey-list') {
    return ok((await run('cat /root/.ssh/authorized_keys 2>/dev/null || echo "(空)"')).trim());
  }
  if (action === 'sshkey-add') {
    const key = String(params.key || '').trim();
    if (!/^ssh-(rsa|ed25519|ecdsa[-\w]*)\s+[A-Za-z0-9+\/=]{40,}(\s+\S+)?$/.test(key)) bad('公钥格式无效');
    const agent = getAgent();
    await run('mkdir -p /root/.ssh && chmod 700 /root/.ssh');
    const cur = await run('cat /root/.ssh/authorized_keys 2>/dev/null || true');
    if (cur.stdout.includes(key.split(/\s+/).slice(0, 2).join(' '))) bad('该公钥已存在');
    await agent.writeFile('/root/.ssh/authorized_keys.tmp', cur.stdout + (cur.stdout && !cur.stdout.endsWith('\n') ? '\n' : '') + key + '\n');
    await run('chmod 600 /root/.ssh/authorized_keys && mv -f /root/.ssh/authorized_keys.tmp /root/.ssh/authorized_keys');
    return ok('公钥已添加到 /root/.ssh/authorized_keys');
  }
  if (action === 'sshkey-del') {
    const fingerprint = String(params.key || '').trim();
    if (!fingerprint || fingerprint.length < 10 || /[\n\r]/.test(fingerprint)) bad('无效指纹');
    const agent = getAgent();
    const cur = await run('cat /root/.ssh/authorized_keys 2>/dev/null || true');
    const lines = cur.stdout.split('\n').filter((l) => l.trim() && !l.includes(fingerprint));
    await agent.writeFile('/root/.ssh/authorized_keys.tmp', lines.join('\n') + (lines.length ? '\n' : ''));
    await run('chmod 600 /root/.ssh/authorized_keys && mv -f /root/.ssh/authorized_keys.tmp /root/.ssh/authorized_keys');
    return ok('已删除匹配的公钥');
  }
  bad('未知用户操作: ' + action);
}

module.exports = {
  killProcess, serviceAction, diskAction, networkAction, securityAction,
  dbAction, dbQuery, webAction, fileRead, fileWrite, fileListing, restartPanel,
  dockerAction, cronAction, pkgInstall, backupList, backupCreate, backupDelete, backupPath,
  userAction, DISK_ACTIONS, PKG_WHITELIST, BACKUP_SOURCES,
};
