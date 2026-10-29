'use strict';
/* Windows mutating operations — same API as actions.js */
const fs = require('fs');
const path = require('path');
const os = require('os');
const { getAgent } = require('./agent');
const distro = require('./distro');
const svc = require('./svc');

const UNIT_RE = /^[a-zA-Z0-9@_.\-]+\.(service|timer|socket|target)$/;
const DEV_RE = /^[a-zA-Z0-9._\-]+$/;
const USER_RE = /^[a-z_][a-z0-9_-]{0,31}$/i;

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

async function killProcess({ pid, signal }) {
  pid = parseInt(pid, 10);
  if (!Number.isFinite(pid) || pid <= 4) bad('无效 PID');
  const force = String(signal || 'TERM').toUpperCase() === 'KILL' ? '/F' : '';
  return ok(await run(`taskkill /PID ${pid} ${force} 2>&1`, { timeout: 15000 }));
}

const SVC_ACTIONS = { start: 'start', stop: 'stop', restart: 'restart', enable: 'enable', disable: 'disable', reload: 'reload', mask: 'mask', unmask: 'unmask' };

async function serviceAction({ unit, action }) {
  if (!unit || !UNIT_RE.test(unit)) bad('无效的服务单元名');
  const a = SVC_ACTIONS[String(action || '')];
  if (!a) bad('无效操作: ' + action);
  const name = String(unit).replace(/\.service$/i, '');
  const m = await svc.detect();
  const cmd = (a === 'mask' || a === 'unmask') ? svc.maskCmd(a, name) : svc.svcCmd(a, name, m);
  return ok(await run(cmd, { timeout: 30000 }));
}

const DISK_ACTIONS = {
  'pkg-clean': { desc: '清理 winget/choco 缓存', dynamic: 'clean' },
  'pkg-update': { desc: '刷新软件包索引', dynamic: 'update' },
  'pkg-autoremove': { desc: '卸载不再需要的软件包', dynamic: 'autoremove', timeout: 300000 },
};

async function diskAction({ action }) {
  const a = DISK_ACTIONS[action];
  if (!a) bad('未知磁盘操作: ' + action);
  let cmd = a.cmd;
  if (a.dynamic) {
    const env = await distro.detect();
    if (!env.pm) bad('未检测到 winget 或 choco');
    cmd = distro.PM.action(env, a.dynamic, []);
    if (!cmd) bad('当前包管理器不支持该操作: ' + a.dynamic);
  }
  return ok(await run(cmd, { timeout: a.timeout || 60000 }) || '(无输出)');
}

async function networkAction({ action, params = {} }) {
  switch (action) {
    case 'ufw-allow':
    case 'ufw-deny': {
      const spec = String(params.spec || '').trim();
      const m = /^(\d{1,5})\/(tcp|udp)$/i.exec(spec) || /^(\d{1,5})$/.exec(spec);
      if (!m) bad('无效的端口格式');
      const port = m[1];
      const proto = (m[2] || 'tcp').toUpperCase();
      const verb = action === 'ufw-allow' ? 'allow' : 'block';
      return ok(await run(`netsh advfirewall firewall add rule name="CloudMonitor-${port}" dir=in action=${verb} protocol=${proto} localport=${port}`, { timeout: 20000 }));
    }
    case 'ufw-reload': return ok('Windows 防火墙规则即时生效');
    case 'ufw-enable': return ok(await run('netsh advfirewall set allprofiles state on'));
    case 'ufw-disable': return ok(await run('netsh advfirewall set allprofiles state off'));
    default: bad('未知网络操作: ' + action);
  }
}

async function securityAction({ action }) {
  bad('Windows 上请使用防火墙与本地安全策略: ' + action);
}

async function dbAction({ db, action }) {
  if (!/^(mysql|postgres|redis|mongo)$/.test(String(db || ''))) bad('未知数据库: ' + db);
  const serviceMap = { mysql: 'MySQL', postgres: 'postgresql-x64-16', redis: 'Redis', mongo: 'MongoDB' };
  if (['start', 'stop', 'restart'].includes(action)) {
    return serviceAction({ unit: serviceMap[db] + '.service', action });
  }
  bad('未知数据库操作: ' + action);
}

async function dbQuery() { bad('请在数据库页使用引擎专用客户端'); }

async function webAction({ action }) {
  if (action === 'nginx-reload') return ok(await run('nginx -s reload 2>&1', { timeout: 15000 }));
  if (action === 'iis-reset') return ok(await run('iisreset 2>&1', { timeout: 60000 }));
  bad('未知网站操作: ' + action);
}

function resolvePanelPath(p) {
  const raw = String(p || '').trim();
  if (raw === '/etc/hostname') return { kind: 'hostname' };
  if (raw === '/etc/' || raw === '/etc') {
    return { kind: 'path', path: path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'drivers', 'etc') };
  }
  if (raw.startsWith('/')) {
    return { kind: 'path', path: path.join('C:\\', raw.replace(/^\//, '').replace(/\//g, path.sep)) };
  }
  return { kind: 'path', path: raw };
}

async function fileRead({ path: p }) {
  const target = resolvePanelPath(p);
  if (target.kind === 'hostname') {
    const content = os.hostname() + '\n';
    return { content, size: Buffer.byteLength(content, 'utf8'), mode: '644', owner: 'SYSTEM', mtime: new Date().toISOString() };
  }
  const agent = getAgent();
  const data = await agent.readFileBuffer(target.path);
  const text = data.toString('utf8');
  if (text.includes('\u0000')) bad('二进制文件不可预览');
  const st = fs.statSync(target.path);
  return { content: text.slice(0, 2 * 1024 * 1024), size: st.size, mode: (st.mode || 0).toString(8).slice(-4), owner: 'SYSTEM', mtime: st.mtime.toISOString() };
}

async function fileWrite({ path: p, content }) {
  await getAgent().writeFile(p, String(content ?? ''));
  return ok('已保存');
}

async function fileListing({ dir }) {
  const target = resolvePanelPath(dir || '/etc/');
  const listPath = target.kind === 'path' ? target.path : path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'drivers', 'etc');
  let names = [];
  try { names = fs.readdirSync(listPath); } catch (e) { bad('目录不存在或不可读：' + listPath, 404); }
  const entries = names.map((name) => {
    const full = path.join(listPath, name);
    let isDir = false, isLink = false;
    try { const st = fs.lstatSync(full); isDir = st.isDirectory(); isLink = st.isSymbolicLink(); } catch (_) {}
    return { mode: isDir ? 'd' : '-', name, isDir, isLink, target: null };
  });
  return { dir: String(dir || '/etc/'), entries };
}

async function restartPanel() {
  return ok('请通过 Windows 服务或 install.ps1 重启 Cloud Monitor');
}

async function dockerAction({ action, params = {} }) {
  const name = String(params.name || params.container || '');
  switch (action) {
    case 'start': return ok(await run(`docker start ${name}`, { timeout: 60000 }));
    case 'stop': return ok(await run(`docker stop ${name}`, { timeout: 60000 }));
    case 'restart': return ok(await run(`docker restart ${name}`, { timeout: 60000 }));
    case 'prune': return ok(await run('docker system prune -af 2>&1', { timeout: 300000 }));
    default: bad('未知 Docker 操作: ' + action);
  }
}

async function cronAction() { bad('请使用任务计划程序'); }

const PKG_WHITELIST = {
  'Git.Git': 'Git',
  'OpenSSH.OpenSSH': 'OpenSSH',
};

async function pkgInstall({ pkg }) {
  const p = String(pkg || '');
  const env = await distro.detect();
  if (!env.pm) bad('请安装 winget 或 Chocolatey');
  const cmd = distro.PM.action(env, 'install', [p]);
  return ok(await run(cmd, { timeout: 600000 }));
}

const { backupDir: BACKUP_DIR } = require('./paths');
const BACKUP_SOURCES = ['C:\\ProgramData', 'C:\\inetpub', 'C:\\CloudMonitor'];

async function backupList() {
  try { fs.mkdirSync(BACKUP_DIR, { recursive: true }); } catch (_) {}
  const items = [];
  for (const f of fs.readdirSync(BACKUP_DIR)) {
    if (!f.endsWith('.zip')) continue;
    const st = fs.statSync(path.join(BACKUP_DIR, f));
    items.push({ size: st.size, name: f, mtime: st.mtime.toISOString() });
  }
  let relay = { items: [] };
  try { relay = await require('./relayops').relayBackups(); } catch (_) {}
  return { dir: BACKUP_DIR, items: items.reverse(), relay };
}

async function backupCreate({ source }) {
  const src = String(source || '');
  if (!BACKUP_SOURCES.some((s) => src.toLowerCase().startsWith(s.toLowerCase()))) {
    bad('备份源必须是: ' + BACKUP_SOURCES.join(', '));
  }
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const name = `cm-${src.replace(/[:\\]/g, '_')}-${stamp}.zip`;
  const dest = path.join(BACKUP_DIR, name);
  const esc = (x) => x.replace(/'/g, "''");
  await run(`powershell -NoProfile -Command "Compress-Archive -Path '${esc(src)}\\*' -DestinationPath '${esc(dest)}' -Force"`, { timeout: 600000 });
  return ok(`备份完成: ${name}`, { name });
}

async function backupDelete({ name }) {
  const n = String(name || '');
  if (!/^cm-[A-Za-z0-9._\-]+\.zip$/.test(n)) bad('非法备份文件名');
  fs.unlinkSync(path.join(BACKUP_DIR, n));
  return ok(`已删除 ${n}`);
}

function backupPath(name) {
  const n = String(name || '');
  if (!/^cm-[A-Za-z0-9._\-]+\.zip$/.test(n)) return null;
  return path.join(BACKUP_DIR, n);
}

async function userAction({ action, params = {} }) {
  const name = String(params.name || '');
  if (action === 'create') {
    const pw = String(params.password || '');
    if (pw.length < 6) bad('密码至少 6 位');
    return ok(await run(`net user ${name} ${pw} /add`, { timeout: 20000 }));
  }
  if (action === 'delete') return ok(await run(`net user ${name} /delete`, { timeout: 20000 }));
  if (action === 'passwd') return ok(await run(`net user ${name} ${String(params.password || '')}`, { timeout: 20000 }));
  bad('未知用户操作: ' + action);
}

module.exports = {
  killProcess, serviceAction, diskAction, networkAction, securityAction,
  dbAction, dbQuery, webAction, fileRead, fileWrite, fileListing, restartPanel,
  dockerAction, cronAction, pkgInstall, backupList, backupCreate, backupDelete, backupPath,
  userAction, DISK_ACTIONS, PKG_WHITELIST, BACKUP_SOURCES,
};
