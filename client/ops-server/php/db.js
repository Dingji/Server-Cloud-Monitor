'use strict';
/*
 * MariaDB provisioning, bound to a site.
 *
 * The panel talks to the server as root over the unix socket (the Ubuntu
 * default for a freshly installed mariadb-server), so no admin password is
 * stored. Site credentials are handed to mysql through a 0600 defaults file —
 * never as a command-line argument, where `ps` would leak them.
 */
const fs = require('fs');
const path = require('path');
const C = require('./common');

const S = C.S;
const ok = C.ok;

const NAME_RE = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const TMP = '/tmp';

function checkName(n, label) {
  const s = String(n || '');
  if (!NAME_RE.test(s)) C.bad((label || '名称') + '只能由字母开头、字母数字下划线组成，长度 2-64：' + s);
  return s;
}

async function available() {
  const r = await C.sh('command -v mariadb >/dev/null 2>&1 && echo YES || echo NO', { timeout: 8000 });
  if (!/YES/.test(r.stdout)) return false;
  const st = await C.sh('systemctl is-active mariadb 2>/dev/null || echo inactive', { timeout: 8000 })
    .catch(() => ({ stdout: 'inactive' }));
  return String(st.stdout).trim() === 'active';
}

async function sql(stmt, defaultsFile) {
  const file = defaultsFile || await adminDefaults();
  const r = await C.sh('mariadb --defaults-extra-file=' + S.shq(file) + ' -N -B -e ' + S.shq(stmt) + ' 2>&1',
    { timeout: 40000 });
  const text = String(r.stdout).trim();
  if (/ERROR \d+/.test(text)) {
    const e = new Error('数据库执行失败');
    e.output = text;
    throw e;
  }
  return text;
}

let adminFile = null;
/** root-authenticated defaults file; mysql's unix_socket plugin needs no password. */
async function adminDefaults() {
  if (adminFile && fs.existsSync(adminFile)) return adminFile;
  adminFile = TMP + '/panel-php-db-admin.cnf';
  fs.writeFileSync(adminFile, '[client]\nsocket=/run/mysqld/mysqld.sock\nuser=root\n', { mode: 0o600 });
  try { fs.chmodSync(adminFile, 0o600); } catch (_) {}
  return adminFile;
}

function credDefaults(host, user, pass) {
  const f = TMP + '/panel-php-db-' + C.genId('cred') + '.cnf';
  fs.writeFileSync(f, '[client]\nuser=' + user + '\npassword=' + pass + '\nhost=' + host + '\n', { mode: 0o600 });
  try { fs.chmodSync(f, 0o600); } catch (_) {}
  return f;
}

async function listDbs() {
  if (!await available()) return { available: false, databases: [] };
  const out = await sql(
    "SELECT table_schema, COUNT(*), COALESCE(SUM(data_length+index_length),0) " +
    "FROM information_schema.tables GROUP BY table_schema ORDER BY table_schema").catch(() => '');
  const databases = String(out).split('\n').filter(Boolean).map((line) => {
    const [name, tables, bytes] = line.split('\t');
    return { name: name, tables: Number(tables) || 0, bytes: Number(bytes) || 0 };
  });
  return { available: true, databases: databases, reserved: ['mysql', 'information_schema', 'performance_schema', 'sys'] };
}

async function createFor(rec, body) {
  if (!await available()) {
    const e = new Error('本机 MariaDB 未安装或未运行，请先在「PHP 环境」里安装 mariadb-server');
    e.status = 409;
    throw e;
  }
  const b = body || {};
  const dbName = checkName(b.name || (rec.slug.replace(/-/g, '_') + '_db'), '数据库名');
  const user = checkName(b.user || (rec.slug.replace(/-/g, '_')).slice(0, 32), '数据库用户');
  const pass = String(b.password || C.genSecret(20));
  if (pass.length < 10 || pass.length > 64) C.bad('密码长度需在 10-64');
  const host = b.host === '%' ? '%' : 'localhost';

  await sql('CREATE DATABASE IF NOT EXISTS `' + dbName + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;');
  await sql("CREATE USER IF NOT EXISTS " + quote(user) + '@' + quote(host) + " IDENTIFIED BY " + quote(pass) + ';');
  await sql("ALTER USER " + quote(user) + '@' + quote(host) + " IDENTIFIED BY " + quote(pass) + ';');
  await sql('GRANT ALL PRIVILEGES ON `' + dbName + '`.* TO ' + quote(user) + '@' + quote(host) + ';');
  await sql('FLUSH PRIVILEGES;');

  rec.db = { name: dbName, user: user, host: host, password: pass, createdAt: new Date().toISOString() };
  C.put(rec);
  C.saveMeta(rec);
  return ok('数据库 ' + dbName + ' 与用户 ' + user + ' 已就绪（密码已生成并写入站点配置）', {
    db: { name: dbName, user: user, host: host, passwordShown: pass },
  });
}

function quote(s) { return "'" + String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'"; }

async function setPassword(rec, body) {
  if (!rec.db) C.bad('站点未绑定数据库', 404);
  const pass = String((body && body.password) || C.genSecret(20));
  if (pass.length < 10 || pass.length > 64) C.bad('密码长度需在 10-64');
  await sql("ALTER USER " + quote(rec.db.user) + '@' + quote(rec.db.host) + " IDENTIFIED BY " + quote(pass) + ';');
  await sql('FLUSH PRIVILEGES;');
  rec.db.password = pass;
  rec.db.updatedAt = new Date().toISOString();
  C.put(rec);
  C.saveMeta(rec);
  // keep the shared .env in sync if it was panel-generated
  await refreshEnvPassword(rec, pass);
  return ok('数据库密码已重置' + (rec.preset === 'wordpress' ? '（wp-config.php 需手动同步：站点详情 → 配置）' : ''),
    { password: pass });
}

async function refreshEnvPassword(rec, pass) {
  const envFile = C.sharedDir(rec.slug) + '/.env';
  try {
    const text = fs.readFileSync(envFile, 'utf8');
    if (/DB_PASSWORD=/.test(text)) {
      fs.writeFileSync(envFile, text.replace(/DB_PASSWORD=.*/g, 'DB_PASSWORD=' + pass), 'utf8');
      return true;
    }
  } catch (_) {}
  return false;
}

async function dropFor(rec) {
  if (!rec.db || !await available()) return ok('无需删除数据库');
  const db = rec.db;
  try {
    await sql('DROP DATABASE IF EXISTS `' + db.name + '`;');
    await sql('DROP USER IF EXISTS ' + quote(db.user) + '@' + quote(db.host) + ';');
  } catch (e) {
    return ok('数据库删除失败（可手工执行）：' + e.message + '\n' + (e.output || ''));
  }
  return ok('数据库 ' + db.name + ' 与用户 ' + db.user + ' 已删除');
}

async function infoFor(rec) {
  if (!rec.db) return { bound: false };
  if (!await available()) return { bound: true, serverDown: true, db: { name: rec.db.name, user: rec.db.user } };
  const row = await sql(
    "SELECT COUNT(*), COALESCE(SUM(data_length+index_length),0) FROM information_schema.tables WHERE table_schema=" +
    quote(rec.db.name)).catch(() => '');
  const [tables, bytes] = String(row).split('\t');
  const users = await sql("SELECT user, host FROM mysql.user WHERE user=" + quote(rec.db.user)).catch(() => '');
  return {
    bound: true,
    db: { name: rec.db.name, user: rec.db.user, host: rec.db.host, password: undefined },
    tables: Number(tables) || 0,
    bytes: Number(bytes) || 0,
    users: String(users).split('\n').filter(Boolean).map((l) => l.split('\t')),
    connected: Number(tables) > 0,
  };
}

async function tablePeek(rec, body) {
  if (!rec.db) C.bad('站点未绑定数据库', 404);
  const limit = Math.max(1, Math.min(50, Number(body && body.limit) || 20));
  const out = await sql('SHOW TABLES FROM `' + checkName(rec.db.name, '数据库名') + '`');
  const names = String(out).split('\n').filter(Boolean);
  const detail = [];
  for (const t of names.slice(0, limit)) {
    if (!/^[A-Za-z0-9_$]{1,64}$/.test(t)) continue;
    const r = await sql("SELECT table_rows, data_length+index_length FROM information_schema.tables " +
      "WHERE table_schema=" + quote(rec.db.name) + " AND table_name=" + quote(t)).catch(() => '');
    const [rows, bytes] = String(r).split('\t');
    detail.push({ table: t, rows: Number(rows) || 0, bytes: Number(bytes) || 0 });
  }
  return { ok: true, database: rec.db.name, total: names.length, tables: detail };
}

function dumpJob(rec, body) {
  if (!rec.db) C.bad('站点未绑定数据库', 404);
  const dir = path.join(C.siteDir(rec.slug), 'backups');
  const stamp = C.tsName();
  const file = dir + '/db-' + rec.db.name + '-' + stamp + '.sql.gz';
  const job = require('./jobs').start({
    kind: 'db.dump', site: rec.slug, title: rec.slug + ' — 备份数据库 ' + rec.db.name,
    run: async (j) => {
      j.set(10, 'dump', '导出 ' + rec.db.name);
      C.mkdirp(dir, 0o750);
      const f = credDefaults('localhost', rec.db.user, rec.db.password);
      try {
        await j.drain('mariadb-dump --defaults-extra-file=' + S.shq(f) +
          ' --single-transaction --routines --triggers ' + S.shq(rec.db.name) +
          ' | gzip -6 > ' + S.shq(file),
          { timeout: 30 * 60000, failMsg: '数据库导出失败' });
        /* A 0-byte archive still exits 0 (gzip is happy with nothing). An empty
           "backup" is worse than no backup, so treat it as a failure. */
        const sz = await j.drain('test -s ' + S.shq(file) + ' && wc -c < ' + S.shq(file),
          { timeout: 20000, failMsg: '导出结果为空文件，备份未成功' });
        j.log('归档大小 ' + String(sz.stdout).trim() + ' 字节');
      } finally {
        try { fs.rmSync(f, { force: true }); } catch (_) {}
      }
      const size = await C.sh('du -sk ' + S.shq(file) + ' 2>/dev/null | cut -f1', { timeout: 10000 })
        .then((r) => Number(String(r.stdout).trim()) * 1024).catch(() => 0);
      await C.fixOwnership(rec.slug, { path: dir });
      j.set(100, 'done', '完成');
      return '已导出 ' + file + '（' + Math.round((size || 0) / 1024) + ' KB）';
    },
  });
  return ok('备份任务已启动', { job: require('./jobs').view(job), file: file, keep: Number(body && body.keep) || 10 });
}

async function listBackups(rec) {
  const dir = C.siteDir(rec.slug) + '/backups';
  let files = [];
  try { files = fs.readdirSync(dir).filter((f) => f.endsWith('.sql.gz')); } catch (_) {}
  return files.sort().reverse().map((f) => {
    let size = 0, mtime = null;
    try { const st = fs.statSync(dir + '/' + f); size = st.size; mtime = st.mtime.toISOString(); } catch (_) {}
    return { name: f, path: dir + '/' + f, bytes: size, at: mtime };
  });
}

module.exports = {
  available, sql, listDbs, createFor, dropFor, setPassword, infoFor, tablePeek,
  dumpJob, listBackups, refreshEnvPassword, checkName, NAME_RE,
};
