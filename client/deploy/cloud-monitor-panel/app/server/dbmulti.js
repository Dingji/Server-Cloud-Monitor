'use strict';
/*
 * Multi-engine database administration: SQLite, IBM Db2, Redis, MongoDB.
 *
 * PostgreSQL & MySQL live in dbadmin.js; this module mirrors the same public
 * API for the extra engines and is reached through the dispatcher at the
 * bottom of dbadmin.js (only when the request targets one of these engines).
 *
 * Engines are grouped by kind:
 *   sql  -> sqlite, db2   : full relational admin (databases/tables/data/DDL)
 *   kv   -> redis         : keyspace admin (keys/config/acl/backup)
 *   doc  -> mongo         : document admin (databases/collections/docs/users)
 */
const path = require('path');
const fs = require('fs');
const config = require('./config');
const { getAgent } = require('./agent');
const { audit } = require('./util');
const { DB_BACKUP_DIR, UPLOAD_DIR } = require('./paths');

const ENGINES = ['sqlite', 'db2', 'redis', 'mongo'];
const MAX_ROWS = 500;
const SQLITE_NULL = '__CM_NULL__';

/* ================= helpers ================= */
function bad(msg) { const e = new Error(msg); e.status = 400; throw e; }
function ok(output, extra) { return Object.assign({ ok: true, output: output == null ? '' : String(output) }, extra || {}); }
function shq(s) { return "'" + String(s == null ? '' : s).replace(/'/g, "'\\''") + "'"; }
function b64(s) { return Buffer.from(String(s), 'utf8').toString('base64'); }

async function run(cmd, opts = {}) {
  return getAgent().run(cmd, { timeout: opts.timeout || 60000, maxBuffer: opts.maxBuffer || 64 * 1024 * 1024 });
}
async function cmdExists(bin) {
  try { const r = await run(`command -v ${bin} 2>/dev/null`, { timeout: 8000 }); return String(r.stdout || '').trim().length > 0; }
  catch (_) { return false; }
}
/* cached binary probe so repeated calls don't shell out every time */
const binCache = new Map();
async function binExists(bin) {
  const now = Date.now();
  const c = binCache.get(bin);
  if (c && c.exp > now) return c.v;
  const v = await cmdExists(bin);
  binCache.set(bin, { v, exp: now + 60000 });
  return v;
}
async function isActive(svc) {
  if (!svc) return false;
  const r = await run(`systemctl is-active ${svc} 2>/dev/null || true`, { timeout: 8000 });
  return String(r.stdout || '').trim() === 'active';
}
function nz(v) { return v === undefined || v === null ? '' : String(v); }

/* ---- result parsers ---- */
function parseCsv(text, nullSentinel) {
  const rows = [];
  let row = [], field = '', inQ = false, quoted = false, i = 0;
  const s = String(text);
  const push = () => { row.push(quoted ? field : (field === '' && !quoted ? null : field)); field = ''; quoted = false; };
  while (i < s.length) {
    const ch = s[i];
    if (inQ) {
      if (ch === '"') { if (s[i + 1] === '"') { field += '"'; i += 2; continue; } inQ = false; i++; continue; }
      field += ch; i++; continue;
    }
    if (ch === '"') { inQ = true; quoted = true; i++; continue; }
    if (ch === ',') { push(); i++; continue; }
    if (ch === '\n') { push(); rows.push(row); row = []; i++; continue; }
    if (ch === '\r') { i++; continue; }
    field += ch; i++;
  }
  if (field !== '' || quoted || row.length) { push(); rows.push(row); }
  const out = rows.filter((r) => !(r.length === 1 && r[0] === null));
  if (nullSentinel) for (const r of out) for (let k = 0; k < r.length; k++) if (r[k] === nullSentinel) r[k] = null;
  return out;
}
function toObjects(matrix) {
  if (!matrix.length) return { columns: [], rows: [] };
  const columns = matrix[0].map((c) => String(c).trim());
  const rows = matrix.slice(1).map((r) => { const o = {}; columns.forEach((c, i) => { o[c] = r[i] === undefined ? null : r[i]; }); return o; });
  return { columns, rows };
}

/* ================= engine registry / meta ================= */
const META = {
  sqlite: {
    id: 'sqlite', name: 'SQLite', kind: 'sql', bin: 'sqlite3', service: null,
    packages: ['sqlite3'],
    caps: { databases: true, tables: true, data: true, ddl: true, console: true, backups: true, importExport: true, insights: true, objects: true, maintenance: true, explain: true, roles: false, sessions: false, schemas: false, sequences: false, extensions: false },
  },
  db2: {
    id: 'db2', name: 'IBM Db2', kind: 'sql', bin: 'db2', service: null,
    packages: [],
    caps: { databases: true, tables: true, data: true, ddl: true, console: true, backups: true, importExport: true, insights: true, objects: true, maintenance: true, explain: true, roles: true, sessions: true, schemas: true, sequences: true, extensions: false },
  },
  redis: {
    id: 'redis', name: 'Redis', kind: 'kv', bin: 'redis-cli', service: 'redis-server',
    packages: ['redis-server'],
    caps: { databases: true, keys: true, console: true, backups: true, insights: true, roles: true, config: true, maintenance: true },
  },
  mongo: {
    id: 'mongo', name: 'MongoDB', kind: 'doc', bin: 'mongosh', service: 'mongod',
    packages: ['mongodb-org'],
    caps: { databases: true, collections: true, documents: true, console: true, backups: true, insights: true, roles: true, indexes: true, maintenance: true },
  },
};

function metaFor(engine) {
  const m = META[engine];
  if (!m) bad('不支持的数据库引擎: ' + engine);
  return m;
}
function checkSqlEngine(engine) {
  if (engine !== 'sqlite' && engine !== 'db2') bad('该操作仅支持关系型引擎 (SQLite / Db2)，当前: ' + engine);
}

/* ================= SQLite: paths ================= */
function sqliteRoots() {
  const raw = String((config.get().db || {}).sqlite && config.get().db.sqlite.roots || '/opt:/srv:/var/www:/www:/root:/home');
  return raw.split(':').map((s) => s.trim()).filter(Boolean);
}
function sqliteManagedDir() { return path.join(DB_BACKUP_DIR, '..', 'sqlite'); }
/* Resolve a user supplied database name/path to an absolute file path. */
function sqlitePath(name, opts = {}) {
  const raw = String(name == null ? '' : name).trim();
  if (!raw) bad('缺少 SQLite 数据库路径');
  if (raw.length > 300 || /[\0\r\n]/.test(raw)) bad('非法的数据库路径');
  let p = raw;
  if (!p.startsWith('/')) p = path.join(sqliteManagedDir(), p);
  p = path.normalize(p);
  if (p.includes('..')) bad('非法路径');
  const base = path.basename(p);
  if (!base || base === '.' || base === '..' || base === '/') bad('非法的数据库文件名');
  const managed = sqliteManagedDir();
  const roots = sqliteRoots().concat([managed]);
  const allowed = roots.some((r) => { const rn = path.normalize(r); return p === rn || p.startsWith(rn.endsWith('/') ? rn : rn + '/'); });
  if (!allowed) bad('数据库路径不在允许的目录内（可在配置中调整 sqlite.roots）: ' + p);
  if (!/\.(db|sqlite|sqlite3|db3)$/i.test(base)) bad('SQLite 数据库文件必须以 .db/.sqlite/.sqlite3/.db3 结尾');
  return p;
}

/* ================= relational (sqlite / db2) command builders ================= */
function relQid(engine, name) {
  if (typeof name !== 'string' || !name.trim() || name.length > 200) bad('无效标识符');
  return String(name).split('.').map((p) => {
    if (!/^[^"\0\r\n]{1,128}$/.test(p)) bad('非法标识符: ' + p);
    return '"' + p.replace(/"/g, '""') + '"';
  }).join('.');
}
function relLit(engine, v) {
  if (v === null || v === undefined) return 'NULL';
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : 'NULL';
  if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
  const s = String(v);
  if (engine === 'sqlite') return "'" + s.replace(/'/g, "''") + "'";
  /* db2: 单引号转义；二进制用 0x 十六进制；其余按字符 */
  if (/^0x[0-9A-Fa-f]*$/.test(s)) return s;
  return "'" + s.replace(/'/g, "''") + "'";
}

function sqliteClient(dbfile) {
  return `sqlite3 -csv -header -bail -cmd ${shq('.nullvalue ' + SQLITE_NULL)} ${shq(dbfile)}`;
}
function db2Instance() { return nz(((config.get().db || {}).db2 || {}).instance) || 'db2inst1'; }
async function db2InstanceExists(inst) {
  const r = await run(`id -u ${shq(inst)} 2>/dev/null || true`, { timeout: 8000 });
  return String(r.stdout || '').trim().length > 0;
}
/* Db2 CLI target database name from request or config */
function db2Database(database) {
  const c = (config.get().db || {}).db2 || {};
  const d = nz(database).trim() || nz(c.database).trim();
  if (!d) bad('请在配置中设置 Db2 数据库名');
  if (!/^[A-Za-z0-9_$\-]{1,40}$/.test(d)) bad('非法的 Db2 数据库名');
  return d;
}
/* Build a command that runs `sql` for db2 through the instance profile. */
function db2SqlFile(sql) {
  /* returns [writeCmd, runCmd, cleanup] pieces embedded by caller */
  return b64(sql);
}

/* ================= generic SQL execution ================= */
async function sqliteRun(dbfile, sql, opts = {}) {
  const full = `echo ${b64(sql)} | base64 -d | ` + sqliteClient(dbfile);
  const r = await run(full, { timeout: opts.timeout || 60000, maxBuffer: opts.maxBuffer || 64 * 1024 * 1024 });
  const errText = String(r.stderr || '').trim();
  const outText = String(r.stdout || '');
  if (r.code !== 0 && !opts.ignoreError) {
    const e = new Error((errText || outText || `sqlite3 执行失败 (exit ${r.code})`).slice(0, 4000));
    e.status = 400; e.output = errText + '\n' + outText;
    throw e;
  }
  let parsed = { columns: [], rows: [] };
  if (opts.parse !== false && outText.trim()) {
    try { parsed = toObjects(parseCsv(outText, SQLITE_NULL)); } catch (_) {}
  }
  const text = parsed.columns.length ? parsed.rows.length + ' 行' : (outText.trim() || errText || 'OK');
  return { ok: true, output: text, columns: parsed.columns, rows: parsed.rows, raw: outText, stderr: errText };
}

async function db2Run(database, sql, opts = {}) {
  const inst = db2Instance();
  if (!(await binExists('db2'))) bad('IBM Db2 未安装（可在「数据库」页点击「一键安装」）');
  if (!(await db2InstanceExists(inst))) bad(`Db2 实例用户 ${inst} 不存在（可在配置中修改 db.db2.instance）`);
  const db = opts.noConnect ? null : db2Database(database);
  const tmp = `/tmp/cmdb2-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6)}.sql`;
  const body = (db ? `CONNECT TO ${db};\n` : '') + String(sql).replace(/;\s*$/, '') + ';\n';
  await run(`echo ${b64(body)} | base64 -d > ${tmp} && chmod 644 ${tmp}`);
  try {
    const r = await run(`su - ${shq(inst)} -c ${shq(`db2 -tvmf ${tmp}`)}`, { timeout: opts.timeout || 120000, maxBuffer: 64 * 1024 * 1024 });
    const outText = String(r.stdout || '');
    const errText = String(r.stderr || '');
    const combined = (outText + '\n' + errText).trim();
    const failed = r.code !== 0 || /SQL\d{3,5}N?\s/.test(combined) && /SQLSTATE=/.test(combined) && !/SQL\d{5}W/.test(combined);
    if (failed && !opts.ignoreError) {
      const msg = combined.split('\n').filter((l) => /SQL\d{5}|SQLSTATE|error/i.test(l)).slice(0, 12).join('\n') || combined.slice(0, 2000);
      const e = new Error('Db2 执行失败:\n' + msg);
      e.status = 400; e.output = combined.slice(0, 6000);
      throw e;
    }
    let parsed = { columns: [], rows: [] };
    if (opts.parse !== false) { try { parsed = toObjects(db2ParseMatrix(outText)); } catch (_) {} }
    return { ok: true, output: (parsed.columns.length ? parsed.rows.length + ' 行' : (combined || 'OK')), columns: parsed.columns, rows: parsed.rows, raw: outText, stderr: errText };
  } finally {
    await run(`rm -f ${tmp}`).catch(() => {});
  }
}
/* Db2 -tvmf prints ASCII tables; parse pipe/space aligned columns into a matrix. */
function db2ParseMatrix(text) {
  const lines = String(text).split('\n').map((l) => l.replace(/\r$/, ''));
  const idx = [];
  lines.forEach((l, i) => { if (/^\s*[A-Za-z0-9_ ]+\s*$/.test(l) === false && /^-{2,}/.test(l)) idx.push(i); });
  /* find a row of dashes that separates header from body */
  let sep = -1;
  for (let i = 1; i < lines.length; i++) { if (/^\s*[-+ ]{4,}\s*$/.test(lines[i]) && lines[i - 1].trim() && !/^-+$/.test(lines[i - 1].trim())) { sep = i; break; } }
  if (sep < 1) return [];
  const header = lines[sep - 1].trim();
  /* column boundaries derived from dash runs */
  const dash = lines[sep];
  const bounds = [];
  let start = -1;
  for (let i = 0; i <= dash.length; i++) {
    const ch = dash[i] || ' ';
    if (ch !== ' ' && start < 0) start = i;
    else if ((ch === ' ' || i === dash.length) && start >= 0) { bounds.push([start, i]); start = -1; }
  }
  const cut = (line) => bounds.map(([a, b]) => line.slice(a, b).trim());
  const matrix = [cut(header)];
  for (let i = sep + 1; i < lines.length; i++) {
    const l = lines[i];
    if (!l.trim()) break;
    if (/^-+\s*$/.test(l.trim())) continue;
    if (/^\s*\(?\d+ record\(s\) selected/.test(l) || /DB20000I|SQLSTATE=/.test(l)) break;
    matrix.push(cut(l));
  }
  return matrix;
}

/* run SQL against a relational engine */
async function relRun(engine, database, sql, opts = {}) {
  if (engine === 'sqlite') return sqliteRun(sqlitePath(database), sql, opts);
  if (engine === 'db2') return db2Run(database, sql, opts);
  bad('非关系型引擎: ' + engine);
}

/* ================= SQL engine: status / overview ================= */
async function sqlStatus(engine, database) {
  if (engine === 'sqlite') {
    const p = sqlitePath(database || (await firstSqliteDb()));
    const info = await sqliteRun(p, [
      "SELECT sqlite_version() AS version;",
      "PRAGMA page_count;", "PRAGMA page_size;", "PRAGMA freelist_count;",
      "PRAGMA journal_mode;", "PRAGMA encoding;",
      "SELECT COUNT(*) AS tables FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';",
      "SELECT COUNT(*) AS indexes FROM sqlite_master WHERE type='index';",
      "SELECT COUNT(*) AS views FROM sqlite_master WHERE type='view';",
      "SELECT COUNT(*) AS triggers FROM sqlite_master WHERE type='trigger';",
    ].join('\n'), { parse: true }).catch((e) => ({ columns: [], rows: [], raw: '', error: e.message }));
    const sizeStat = await run(`stat -c %s ${shq(p)} 2>/dev/null || echo 0`, { timeout: 15000 });
    const fileSize = parseInt(String(sizeStat.stdout).trim(), 10) || 0;
    const ver = (info.rows && info.rows[0]) || {};
    const pragma = {};
    /* re-run pragmatic reads individually so we get labelled values */
    for (const [k, q] of [['page_count', 'PRAGMA page_count;'], ['page_size', 'PRAGMA page_size;'], ['freelist_count', 'PRAGMA freelist_count;'], ['journal_mode', 'PRAGMA journal_mode;'], ['encoding', 'PRAGMA encoding;']]) {
      try { const r = await sqliteRun(p, q); pragma[k] = (r.rows[0] && Object.values(r.rows[0])[0]) || ''; } catch (_) {}
    }
    const counts = {};
    try {
      const c = await sqliteRun(p, "SELECT type, count(*) AS n FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' GROUP BY type;");
      for (const r of c.rows) counts[r.type] = r.n;
    } catch (_) {}
    return {
      engine, database: p,
      version: { version: 'SQLite ' + (ver.version || pragma.sqlite_version || ''), file: p, size: fileSize, page_count: pragma.page_count, page_size: pragma.page_size, freelist_count: pragma.freelist_count, journal_mode: pragma.journal_mode, encoding: pragma.encoding },
      stats: { connections: 1, max_connections: '-', active: '-', databases: 1, tables: counts.table || 0, indexes: counts.index || 0, views: counts.view || 0, triggers: counts.trigger || 0 },
      processes: [], variables: [], capabilities: Object.assign({}, META.sqlite.caps),
    };
  }
  /* db2 */
  const db = db2Database(database);
  if (!(await binExists('db2'))) bad('IBM Db2 未安装（可在「数据库」页点击「一键安装」）');
  if (!(await db2InstanceExists(db2Instance()))) bad(`Db2 实例用户 ${db2Instance()} 不存在`);
  const info = await db2Run(db, "SELECT SERVICE_LEVEL AS version FROM TABLE(SYSPROC.ENV_GET_INST_INFO()) AS T;", { ignoreError: true }).catch(() => ({ rows: [] }));
  const stats = await db2Run(db, "SELECT (SELECT COUNT(*) FROM SYSCAT.TABLES WHERE TABSCHEMA NOT LIKE 'SYS%') AS tables, (SELECT COUNT(*) FROM SYSCAT.VIEWS WHERE VIEWSCHEMA NOT LIKE 'SYS%') AS views, (SELECT COUNT(*) FROM SYSCAT.SCHEMATA) AS schemas, (SELECT COUNT(*) FROM SYSCAT.SEQUENCES) AS sequences FROM SYSIBM.SYSDUMMY1;").catch(() => ({ rows: [] }));
  const conns = await db2Run(db, "SELECT COUNT(*) AS connections FROM TABLE(SYSPROC.MON_GET_CONNECTION(NULL,-2)) AS T;", { ignoreError: true }).catch(() => ({ rows: [] }));
  let apps = [];
  try {
    const a = await db2Run(db, "SELECT APPLICATION_HANDLE AS pid, AUTHID AS \"user\", APPL_NAME AS client, UOW_STATE AS state, ELAPSED_TIME_SEC AS seconds, 'db2' AS db FROM TABLE(SYSPROC.MON_GET_APPLICATION(NULL,-2)) AS T;", { ignoreError: true });
    apps = (a.rows || []).map((r) => ({ pid: r.pid, user: r.user, db, state: r.state, seconds: r.seconds, client: r.client, query: '' }));
  } catch (_) {}
  const sc = parseInt(String(((stats.rows || [])[0] || {}).schemas || '0'), 10) || 0;
  return {
    engine, database: db,
    version: { version: ((info.rows || [])[0] || {}).version || 'IBM Db2', database: db, instance: db2Instance() },
    stats: Object.assign({ connections: ((conns.rows || [])[0] || {}).connections || '0', max_connections: '-', active: '-', databases: sc }, (stats.rows || [])[0] || {}),
    processes: apps, variables: [], capabilities: Object.assign({}, META.db2.caps),
  };
}

async function firstSqliteDb() {
  const dbs = await listSqliteFiles().catch(() => []);
  if (dbs.length) return dbs[0].name;
  bad('没有可用的 SQLite 数据库，请先新建一个');
}

/* discover sqlite files under configured roots (bounded depth) */
async function listSqliteFiles() {
  const roots = sqliteRoots().concat([sqliteManagedDir()]).filter(Boolean);
  const dirs = roots.map(shq).join(' ');
  const r = await run(`for d in ${dirs}; do [ -d "$d" ] && find "$d" -maxdepth 4 -type f \\( -name '*.db' -o -name '*.sqlite' -o -name '*.sqlite3' -o -name '*.db3' \\) -printf '%p\\t%s\\n' 2>/dev/null; done | sort -u | head -400`, { timeout: 30000 });
  const items = [];
  for (const line of String(r.stdout || '').split('\n')) {
    const [p, sz] = line.split('\t');
    if (!p) continue;
    items.push({ name: p, path: p, size: parseInt(sz, 10) || 0 });
  }
  return items;
}

/* ================= SQL engine: databases ================= */
async function sqliteDatabases() {
  const files = await listSqliteFiles();
  const out = [];
  for (const f of files.slice(0, 200)) {
    let tables = null, ver = '';
    try {
      const r = await sqliteRun(f.path, "SELECT count(*) AS n FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';", { timeout: 15000 });
      tables = (r.rows[0] || {}).n;
    } catch (_) {}
    out.push({ name: f.path, path: f.path, owner: '-', size_bytes: f.size, size: String(f.size), tables, encoding: 'UTF-8', connections: '-', is_template: false });
  }
  return out;
}

async function sqlListDatabases(engine, database) {
  if (engine === 'sqlite') return sqliteDatabases();
  /* db2: database directory + catalogs of the connected db */
  const inst = db2Instance();
  if (!(await db2InstanceExists(inst))) return [];
  const dir = await run(`su - ${shq(inst)} -c 'db2 list db directory' 2>/dev/null`, { timeout: 30000 });
  const out = [];
  const lines = String(dir.stdout || '').split('\n');
  let cur = null;
  for (const raw of lines) {
    const line = raw.trim();
    let m = /^Database\s+\d+\s+entry:/i.exec(line);
    if (m) { cur = {}; continue; }
    m = /^Database name\s*=\s*(.+)$/i.exec(line); if (m && cur) { cur.name = m[1].trim(); continue; }
    m = /^Database alias\s*=\s*(.+)$/i.exec(line); if (m && cur) { cur.alias = m[1].trim(); continue; }
    m = /^Database directory\s*=\s*(.+)$/i.exec(line); if (m && cur) { cur.dir = m[1].trim(); continue; }
    if (!line && cur && cur.name) { out.push({ name: cur.alias || cur.name, owner: inst, size_bytes: 0, size: cur.dir || '-', tables: null, encoding: '-', connections: '-', is_template: false }); cur = null; }
  }
  return out;
}

async function sqlCreateDatabase(engine, { name, owner, encoding, collation, charset }) {
  if (engine === 'sqlite') {
    const p = sqlitePath(name);
    await run(`mkdir -p ${shq(path.dirname(p))}`);
    if (fs.existsSync && (await run(`test -e ${shq(p)} && echo 1 || true`)).stdout.trim() === '1') bad('数据库文件已存在: ' + p);
    const r = await run(`echo ${b64('PRAGMA user_version = 0;\n')} | base64 -d | ${sqliteClient(p)}`, { timeout: 30000 });
    if (r.code !== 0) bad('创建失败: ' + String(r.stderr || r.stdout).slice(0, 600));
    await run(`chmod 660 ${shq(p)} 2>/dev/null || true`);
    audit('db', 'sqlite.create', { name: p });
    return ok('已创建 SQLite 数据库: ' + p, { path: p });
  }
  /* db2: CREATE DATABASE (as instance) */
  const inst = db2Instance();
  const db = String(name || '').trim();
  if (!/^[A-Za-z0-9_$\-]{1,40}$/.test(db)) bad('非法的 Db2 数据库名');
  const enc = encoding && /^[A-Za-z0-9_]+$/.test(encoding) ? encoding : 'UTF-8';
  const cmd = `CREATE DATABASE ${db} USING CODESET UTF-8 TERRITORY CN COLLATE USING SYSTEM`;
  const r = await run(`su - ${shq(inst)} -c ${shq(`db2 ${shq(cmd)}`)}`, { timeout: 900000, maxBuffer: 16 * 1024 * 1024 });
  const out = (String(r.stdout || '') + String(r.stderr || '')).trim();
  if (r.code !== 0 || /SQL\d{5}N/.test(out)) throw Object.assign(new Error('创建 Db2 数据库失败:\n' + out.slice(0, 2000)), { status: 400, output: out });
  audit('db', 'db2.create', { name: db });
  return ok('已创建 Db2 数据库: ' + db + '\n' + out.slice(0, 1000));
}

async function sqlDropDatabase(engine, { name, force }) {
  if (engine === 'sqlite') {
    const p = sqlitePath(name);
    const base = path.basename(p);
    if (['main.db', 'cloud-panel.db'].includes(base)) bad('该数据库受保护，不能删除');
    if (p === sqliteManagedDir()) bad('非法路径');
    await run(`rm -f ${shq(p)} ${shq(p + '-wal')} ${shq(p + '-shm')} ${shq(p + '-journal')}`);
    audit('db', 'sqlite.drop', { name: p });
    return ok('已删除 SQLite 数据库文件: ' + p);
  }
  const inst = db2Instance();
  const db = db2Database(name);
  const r = await run(`su - ${shq(inst)} -c ${shq(`db2 ${shq('DROP DATABASE ' + db)}`)}`, { timeout: 900000, maxBuffer: 16 * 1024 * 1024 });
  const out = (String(r.stdout || '') + String(r.stderr || '')).trim();
  if (r.code !== 0 || /SQL\d{5}N/.test(out)) throw Object.assign(new Error('删除 Db2 数据库失败:\n' + out.slice(0, 2000)), { status: 400, output: out });
  audit('db', 'db2.drop', { name: db });
  return ok('已删除 Db2 数据库: ' + db);
}

async function sqlRenameDatabase(engine, { from, to }) {
  if (engine === 'sqlite') {
    const src = sqlitePath(from), dst = sqlitePath(to);
    const r = await run(`mv ${shq(src)} ${shq(dst)} 2>&1 && echo OK`);
    if (r.code !== 0 || !/OK/.test(String(r.stdout))) bad('文件重命名失败: ' + String(r.stdout || r.stderr).slice(0, 400));
    audit('db', 'sqlite.rename', { from: src, to: dst });
    return ok('已重命名: ' + src + ' -> ' + dst);
  }
  bad('Db2 不支持直接重命名数据库（需新建后迁移）');
}

/* ================= SQL engine: tables ================= */
async function sqlListTables(engine, database) {
  if (engine === 'sqlite') {
    const p = sqlitePath(database);
    const r = await sqliteRun(p, "SELECT name, CASE type WHEN 'table' THEN 'table' WHEN 'view' THEN 'view' ELSE type END AS kind FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name;");
    return (r.rows || []).map((x) => ({ schema: 'main', name: x.name, kind: x.kind, rows: null, size: '-' }));
  }
  const db = db2Database(database);
  const r = await db2Run(db, "SELECT TABSCHEMA, TABNAME, CASE TYPE WHEN 'T' THEN 'table' WHEN 'V' THEN 'view' WHEN 'A' THEN 'alias' ELSE LOWER(TYPE) END AS kind, CARD FROM SYSCAT.TABLES WHERE TYPE IN ('T','V','A') AND TABSCHEMA NOT LIKE 'SYS%' ORDER BY TABSCHEMA, TABNAME;");
  return (r.rows || []).map((x) => ({ schema: x.TABSCHEMA, name: x.TABNAME, kind: x.KIND, rows: x.CARD, size: '-' }));
}

async function sqlTableSchema(engine, { database, table }) {
  const parts = String(table || '').split('.');
  const tbl = parts.pop();
  const schema = parts.length ? parts.pop() : (engine === 'sqlite' ? 'main' : undefined);
  if (!tbl) bad('缺少表名');
  if (engine === 'sqlite') {
    const p = sqlitePath(database);
    const colsR = await sqliteRun(p, `PRAGMA table_info(${relQid('sqlite', tbl)});`);
    const idxR = await sqliteRun(p, `PRAGMA index_list(${relQid('sqlite', tbl)});`);
    const fkR = await sqliteRun(p, `PRAGMA foreign_key_list(${relQid('sqlite', tbl)});`).catch(() => ({ rows: [] }));
    const ddlR = await sqliteRun(p, `SELECT sql FROM sqlite_master WHERE name=${relLit('sqlite', tbl)};`).catch(() => ({ rows: [] }));
    const pkCols = (colsR.rows || []).filter((c) => Number(c.pk) > 0).map((c) => c.name);
    const indexes = [];
    for (const ix of (idxR.rows || [])) {
      let cols = [];
      try { const ci = await sqliteRun(p, `PRAGMA index_info(${relQid('sqlite', ix.name)});`); cols = (ci.rows || []).map((c) => c.name); } catch (_) {}
      indexes.push({ name: ix.name, cols: cols.join(', '), uniq: !!Number(ix.unique), type: ix.origin || 'c', def: '' });
    }
    return {
      engine, database: p, table: tbl, schema: 'main',
      columns: (colsR.rows || []).map((c) => ({ pos: c.cid, name: c.name, type: c.type || '', base_type: c.type || '', nullable: Number(c.notnull) ? 'NO' : 'YES', default: c.dflt_value, extra: Number(c.pk) ? 'PRIMARY KEY' : '', pk: Number(c.pk) > 0 })),
      indexes, primaryKey: pkCols, foreignKeys: fkR.rows || [],
      ddl: (ddlR.rows[0] || {}).sql || '',
    };
  }
  /* db2 */
  const db = db2Database(database);
  const sch = schema ? String(schema).toUpperCase() : currentDb2Schema(db);
  const cols = await db2Run(db, `SELECT COLNO+1 AS pos, COLNAME AS name, TYPENAME AS type, NULLS AS nullable, DEFAULT AS default, KEYSEQ AS keyseq, IDENTITY AS identity FROM SYSCAT.COLUMNS WHERE TABSCHEMA=${relLit('db2', sch)} AND TABNAME=${relLit('db2', tbl.toUpperCase())} ORDER BY COLNO;`);
  const pk = await db2Run(db, `SELECT COLNAME FROM SYSCAT.KEYCOLUSE WHERE TABSCHEMA=${relLit('db2', sch)} AND TABNAME=${relLit('db2', tbl.toUpperCase())} AND CONSTNAME IN (SELECT CONSTNAME FROM SYSCAT.TABCONST WHERE TABSCHEMA=${relLit('db2', sch)} AND TABNAME=${relLit('db2', tbl.toUpperCase())} AND TYPE='P') ORDER BY COLSEQ;`).catch(() => ({ rows: [] }));
  const idx = await db2Run(db, `SELECT i.INDNAME AS name, LISTAGG(ic.COLNAME, ', ') WITHIN GROUP (ORDER BY ic.COLSEQ) AS cols, CASE WHEN i.UNIQUERULE='D' THEN 1 ELSE 0 END AS uniq, i.INDEXTYPE AS type FROM SYSCAT.INDEXES i JOIN SYSCAT.INDEXCOLUSE ic ON ic.INDSCHEMA=i.INDSCHEMA AND ic.INDNAME=i.INDNAME WHERE i.TABSCHEMA=${relLit('db2', sch)} AND i.TABNAME=${relLit('db2', tbl.toUpperCase())} GROUP BY i.INDNAME, i.UNIQUERULE, i.INDEXTYPE ORDER BY i.INDNAME;`).catch(() => ({ rows: [] }));
  const pkCols = (pk.rows || []).map((r) => r.COLNAME);
  return {
    engine, database: db, table: tbl.toUpperCase(), schema: sch,
    columns: (cols.rows || []).map((c) => ({ pos: c.POS, name: c.NAME, type: c.TYPE, base_type: c.TYPE, nullable: c.NULLABLE === 'Y' ? 'YES' : 'NO', default: c.DEFAULT, extra: c.IDENTITY === 'Y' ? 'IDENTITY' : '', pk: pkCols.includes(c.NAME) })),
    indexes: (idx.rows || []).map((x) => ({ name: x.NAME, cols: x.COLS, uniq: x.UNIQ === '1' || x.UNIQ === 1, type: x.TYPE, def: '' })),
    primaryKey: pkCols, foreignKeys: [], ddl: '',
  };
}
async function currentDb2Schema(db) {
  const r = await db2Run(db, 'VALUES CURRENT SCHEMA;', { ignoreError: true }).catch(() => ({ rows: [] }));
  const v = (r.rows[0] && Object.values(r.rows[0])[0]) || 'DB2INST1';
  return String(v).trim().toUpperCase();
}

/* ================= SQL engine: table data ================= */
function sqlOrderClause(engine, orderBy, orderDir) {
  if (!orderBy) return '';
  const cols = String(orderBy).split(',').map((c) => relQid(engine, c.trim())).join(', ');
  return ' ORDER BY ' + cols + (String(orderDir).toLowerCase() === 'desc' ? ' DESC' : ' ASC');
}
function safeWhere(w) {
  const s = String(w == null ? '' : w).trim();
  if (!s) return '';
  if (/[;\0]/.test(s)) bad('WHERE 条件不允许分号或控制字符');
  if (s.length > 2000) bad('WHERE 条件过长');
  return s;
}
async function sqlGetTableData(engine, { database, table, limit, offset, orderBy, orderDir, where }) {
  const lim = Math.max(1, Math.min(MAX_ROWS, parseInt(limit, 10) || 100));
  const off = Math.max(0, parseInt(offset, 10) || 0);
  const t = relQid(engine, table);
  const w = safeWhere(where);
  const base = `SELECT * FROM ${t}${w ? ' WHERE ' + w : ''}`;
  const q = engine === 'sqlite'
    ? `${base}${sqlOrderClause(engine, orderBy, orderDir)} LIMIT ${lim} OFFSET ${off};`
    : `${base}${sqlOrderClause(engine, orderBy, orderDir)} OFFSET ${off} ROWS FETCH NEXT ${lim} ROWS ONLY;`;
  const r = await relRun(engine, database, q, { timeout: 120000 });
  let total = null;
  try { const c = await relRun(engine, database, `SELECT count(*) AS total FROM ${t}${w ? ' WHERE ' + w : ''};`); total = Number((c.rows[0] || {}).total ?? Object.values(c.rows[0] || {})[0]); } catch (_) {}
  return { columns: r.columns, rows: r.rows, total, durationMs: 0, raw: r.raw };
}

function rowValues(engine, values, allowed) {
  const keys = Object.keys(values || {}).filter((k) => !allowed || allowed.includes(k));
  if (!keys.length) bad('没有要写入的字段');
  return { keys, cols: keys.map((k) => relQid(engine, k)).join(', '), vals: keys.map((k) => relLit(engine, values[k])).join(', ') };
}
function setClause(engine, values, allowed) {
  const keys = Object.keys(values || {}).filter((k) => !allowed || allowed.includes(k));
  if (!keys.length) bad('没有要更新的字段');
  return keys.map((k) => `${relQid(engine, k)} = ${relLit(engine, values[k])}`).join(', ');
}
async function sqlInsertRow(engine, { database, table, values }) {
  const { cols, vals } = rowValues(engine, values);
  const sql = `INSERT INTO ${relQid(engine, table)} (${cols}) VALUES (${vals});`;
  const r = await relRun(engine, database, sql, { timeout: 120000 });
  return ok(sql + '\n' + (r.raw || '').trim(), { affected: 1 });
}
async function sqlUpdateRow(engine, { database, table, pk, values, where }) {
  let w = safeWhere(where);
  if (!w && pk && pk.column) w = `${relQid(engine, pk.column)} = ${relLit(engine, pk.value)}`;
  if (!w) bad('缺少定位条件（WHERE 或主键）');
  const sql = `UPDATE ${relQid(engine, table)} SET ${setClause(engine, values)} WHERE ${w};`;
  const r = await relRun(engine, database, sql, { timeout: 120000 });
  return ok(sql + '\n' + (r.raw || '').trim(), { affected: null });
}
async function sqlDeleteRow(engine, { database, table, pk, where }) {
  let w = safeWhere(where);
  if (!w && pk && pk.column) w = `${relQid(engine, pk.column)} = ${relLit(engine, pk.value)}`;
  if (!w) bad('缺少定位条件（WHERE 或主键）');
  const sql = `DELETE FROM ${relQid(engine, table)} WHERE ${w};`;
  const r = await relRun(engine, database, sql, { timeout: 300000 });
  return ok(sql + '\n' + (r.raw || '').trim(), { affected: null });
}
async function sqlTableAction(engine, { database, table, action, newName, cascade }) {
  const t = relQid(engine, table);
  let sql;
  switch (action) {
    case 'truncate': sql = engine === 'sqlite' ? `DELETE FROM ${t};` : `TRUNCATE TABLE ${t} IMMEDIATE;`; break;
    case 'drop': sql = `DROP TABLE ${t};`; break;
    case 'rename': if (!newName) bad('缺少新表名'); sql = `ALTER TABLE ${t} RENAME TO ${relQid(engine, newName)};`; break;
    case 'empty': sql = `DELETE FROM ${t};`; break;
    default: bad('未知表操作: ' + action);
  }
  const r = await relRun(engine, database, sql, { timeout: 600000 });
  return ok(sql + '\n' + (r.raw || '').trim());
}
async function sqlCountRows(engine, { database, table, where }) {
  const w = safeWhere(where);
  const r = await relRun(engine, database, `SELECT count(*) AS total FROM ${relQid(engine, table)}${w ? ' WHERE ' + w : ''};`, { timeout: 600000 });
  const total = r.rows[0] ? Number(r.rows[0].total ?? Object.values(r.rows[0])[0]) : 0;
  return ok(`精确行数: ${total}`, { total });
}

/* ================= SQL engine: table maintenance / bulk ================= */
function sqlBulkStmt(engine, t, action) {
  const id = relQid(engine, t);
  if (engine === 'sqlite') {
    switch (action) {
      case 'analyze': return `ANALYZE ${id};`;
      case 'reindex': return `REINDEX ${id};`;
      case 'vacuum': return `VACUUM;`;
      case 'check': return `SELECT 'integrity', integrity_check FROM pragma_integrity_check(${relLit('sqlite', t)});`;
      case 'truncate': return `DELETE FROM ${id};`;
      case 'drop': return `DROP TABLE ${id};`;
      default: bad('SQLite 不支持该操作: ' + action);
    }
  }
  switch (action) {
    case 'analyze': case 'reindex': return `RUNSTATS ON TABLE ${id} WITH DISTRIBUTION AND DETAILED INDEXES ALL;`;
    case 'vacuum': return `REORG TABLE ${id};`;
    case 'optimize': return `REORG TABLE ${id};`;
    case 'check': return `REORGCHK UPDATE STATISTICS ON TABLE ${id};`;
    case 'repair': return `REORG TABLE ${id};`;
    case 'truncate': return `TRUNCATE TABLE ${id} IMMEDIATE;`;
    case 'drop': return `DROP TABLE ${id};`;
    default: bad('Db2 不支持该操作: ' + action);
  }
}
async function sqlBulkTableAction(engine, { database, tables, action }) {
  const list = (Array.isArray(tables) ? tables : String(tables || '').split(/[\s,]+/)).map((x) => String(x).trim()).filter(Boolean);
  if (!list.length) bad('请选择至少一张表');
  if (list.length > 200) bad('一次最多 200 张表');
  const results = [];
  for (const t of list) {
    try { await relRun(engine, database, sqlBulkStmt(engine, t, action), { timeout: 1800000 }); results.push({ table: t, ok: true, out: '' }); }
    catch (e) { results.push({ table: t, ok: false, err: String(e.message || e).replace(/\s+/g, ' ').slice(0, 300) }); }
  }
  const succ = results.filter((r) => r.ok), fail = results.filter((r) => !r.ok);
  if (!succ.length) throw Object.assign(new Error(`${action} 全部失败:\n` + fail.slice(0, 10).map((f) => f.table + ' — ' + f.err).join('\n')), { status: 400 });
  return ok(`成功 ${succ.length} / ${list.length} 张表${fail.length ? '，失败 ' + fail.length + ' 张' : ''}` + (fail.length ? '\n失败:\n' + fail.slice(0, 20).map((f) => '  ' + f.table + ' — ' + f.err).join('\n') : ''), { total: list.length, success: succ.length, failed: fail.length, results });
}
async function sqlMaintenance(engine, { database, table, action }) {
  let sql;
  if (engine === 'sqlite') {
    switch (action) {
      case 'vacuum': sql = 'VACUUM;'; break;
      case 'vacuum-full': sql = 'VACUUM;'; break;
      case 'analyze': case 'analyze-mysql': sql = 'ANALYZE;'; break;
      case 'reindex': sql = 'REINDEX;'; break;
      case 'check': case 'checkpoint': sql = 'PRAGMA integrity_check;'; break;
      case 'optimize': sql = 'PRAGMA optimize;'; break;
      case 'wal-checkpoint': sql = 'PRAGMA wal_checkpoint(TRUNCATE);'; break;
      default: bad('SQLite 不支持维护操作: ' + action);
    }
  } else {
    switch (action) {
      case 'vacuum': sql = table ? `REORG TABLE ${relQid('db2', table)};` : `CALL SYSPROC.ADMIN_CMD('REORGCHK UPDATE STATISTICS ON SCHEMA ' + CURRENT SCHEMA);`; break;
      case 'analyze': case 'analyze-mysql': sql = table ? `RUNSTATS ON TABLE ${relQid('db2', table)} WITH DISTRIBUTION AND DETAILED INDEXES ALL;` : `CALL SYSPROC.ADMIN_CMD('RUNSTATS ON SCHEMA ' || CURRENT SCHEMA || ' AND DETAILED INDEXES ALL');`; break;
      case 'reindex': sql = table ? `REORG TABLE ${relQid('db2', table)};` : `CALL SYSPROC.ADMIN_CMD('REORGCHK UPDATE STATISTICS ON SCHEMA ' || CURRENT SCHEMA);`; break;
      case 'check': case 'optimize': case 'repair': sql = table ? `REORGCHK UPDATE STATISTICS ON TABLE ${relQid('db2', table)};` : `CALL SYSPROC.ADMIN_CMD('REORGCHK UPDATE STATISTICS ON SCHEMA ' || CURRENT SCHEMA);`; break;
      default: bad('Db2 不支持维护操作: ' + action);
    }
  }
  const r = await relRun(engine, database, sql, { timeout: 1800000 });
  return ok(sql + '\n' + (r.raw || r.output || '').trim().slice(0, 4000));
}

/* ================= SQL engine: schemas (db2) / objects / insights ================= */
async function sqlListSchemas(engine, database) {
  if (engine === 'sqlite') return { rows: [{ name: 'main', owner: '-', tables: 0, views: null, functions: null, size: '-', user_schema: true }] };
  const db = db2Database(database);
  const r = await db2Run(db, `SELECT s.SCHEMANAME AS name, s.OWNER AS owner, (SELECT COUNT(*) FROM SYSCAT.TABLES t WHERE t.TABSCHEMA=s.SCHEMANAME) AS tables, (SELECT COUNT(*) FROM SYSCAT.VIEWS v WHERE v.VIEWSCHEMA=s.SCHEMANAME) AS views, (SELECT COUNT(*) FROM SYSCAT.ROUTINES ro WHERE ro.ROUTINESCHEMA=s.SCHEMANAME) AS functions FROM SYSCAT.SCHEMATA s ORDER BY s.SCHEMANAME;`);
  return { rows: (r.rows || []).map((x) => ({ name: x.NAME, owner: x.OWNER, tables: x.TABLES, views: x.VIEWS, functions: x.FUNCTIONS, size: '-', user_schema: !/^SYS/.test(String(x.NAME)) })) };
}

async function sec(title, fn, note) {
  try { const r = await fn(); const rows = r.rows || []; return { title, columns: r.columns && r.columns.length ? r.columns : (rows[0] ? Object.keys(rows[0]) : []), rows, note: note || '' }; }
  catch (e) { return { title, columns: [], rows: [], error: String(e.message || e).slice(0, 400), note: note || '' }; }
}

async function sqlInsights(engine, database) {
  const out = [];
  if (engine === 'sqlite') {
    const p = sqlitePath(database);
    out.push(await sec('数据库概况', () => sqliteRun(p, "SELECT (SELECT count(*) FROM sqlite_master WHERE type='table') AS tables, (SELECT count(*) FROM sqlite_master WHERE type='index') AS indexes, (SELECT count(*) FROM sqlite_master WHERE type='trigger') AS triggers, (SELECT count(*) FROM sqlite_master WHERE type='view') AS views;")));
    out.push(await sec('页/空间', () => sqliteRun(p, "SELECT (SELECT * FROM pragma_page_count()) AS page_count, (SELECT * FROM pragma_page_size()) AS page_size, (SELECT * FROM pragma_freelist_count()) AS freelist, (SELECT * FROM pragma_page_count()) * (SELECT * FROM pragma_page_size()) AS total_bytes;")));
    out.push(await sec('完整性检查 (integrity_check)', () => sqliteRun(p, 'PRAGMA integrity_check;')));
    out.push(await sec('外键检查 (foreign_key_check)', () => sqliteRun(p, 'PRAGMA foreign_key_check;')));
    out.push(await sec('编译选项', () => sqliteRun(p, 'PRAGMA compile_options;')));
    out.push(await sec('自动索引', () => sqliteRun(p, "SELECT * FROM sqlite_master WHERE type='index' AND sql IS NULL;"), 'sql 为空的索引是 SQLite 自动创建的'));
  } else {
    const db = db2Database(database);
    out.push(await sec('表空间使用', () => db2Run(db, "SELECT TBSP_NAME AS tbsp, TBSP_USED_PAGES AS used_pages, TBSP_FREE_PAGES AS free_pages, TBSP_TOTAL_PAGES AS total_pages, TBSP_PAGE_TOP AS page_top FROM TABLE(SYSPROC.MON_GET_TABLESPACE('',-2)) ORDER BY TBSP_NAME;").catch(() => ({ rows: [] }))));
    out.push(await sec('缓冲池', () => db2Run(db, "SELECT BP_NAME AS bp, POOL_DATA_L_READS AS logic_reads, POOL_DATA_P_READS AS phys_reads, POOL_DATA_WRITES AS writes, POOL_HIT_RATIO_PERCENT AS hit_pct FROM TABLE(SYSPROC.MON_GET_BUFFERPOOL('',-2)) ORDER BY BP_NAME;").catch(() => ({ rows: [] }))));
    out.push(await sec('表统计 (SYSCAT.TABLES)', () => db2Run(db, "SELECT TABSCHEMA AS schema, TABNAME AS name, CARD AS rows, NPAGES, FPAGES, OVERFLOW, STATUS FROM SYSCAT.TABLES WHERE TABSCHEMA NOT LIKE 'SYS%' AND TYPE='T' ORDER BY CARD DESC FETCH FIRST 50 ROWS ONLY;")));
    out.push(await sec('未使用索引 (可能)', () => db2Run(db, "SELECT i.TABSCHEMA AS schema, i.TABNAME AS table, i.INDNAME AS index FROM SYSCAT.INDEXES i LEFT JOIN SYSCAT.INDEXCOMPRESSION c ON c.INDSCHEMA=i.INDSCHEMA AND c.INDNAME=i.INDNAME WHERE i.TABSCHEMA NOT LIKE 'SYS%' FETCH FIRST 50 ROWS ONLY;")));
    out.push(await sec('锁等待 / 应用', () => db2Run(db, "SELECT APPLICATION_HANDLE AS app, AUTHID AS authid, APPL_STATUS AS status, LOCK_WAIT_START_TIME, LOCK_TIMEOUTS, DEADLOCKS FROM TABLE(SYSPROC.MON_GET_APPLICATION(NULL,-2)) AS T FETCH FIRST 50 ROWS ONLY;").catch(() => ({ rows: [] }))));
  }
  return { engine, database, sections: out };
}

async function sqlListObjects(engine, database) {
  const out = [];
  if (engine === 'sqlite') {
    const p = sqlitePath(database);
    out.push(await sec('视图', () => sqliteRun(p, "SELECT name, sql FROM sqlite_master WHERE type='view' ORDER BY name;")));
    out.push(await sec('触发器', () => sqliteRun(p, "SELECT name, tbl_name AS table_name, sql FROM sqlite_master WHERE type='trigger' ORDER BY name;")));
    out.push(await sec('索引', () => sqliteRun(p, "SELECT name, tbl_name AS table_name, CASE WHEN sql IS NULL THEN '(自动)' ELSE sql END AS sql FROM sqlite_master WHERE type='index' ORDER BY name;")));
    out.push(await sec('虚拟表 (FTS 等)', () => sqliteRun(p, "SELECT name, sql FROM sqlite_master WHERE type='table' AND sql LIKE '%VIRTUAL TABLE%';"), 'FTS/rtree 等扩展表'));
  } else {
    const db = db2Database(database);
    out.push(await sec('视图', () => db2Run(db, "SELECT VIEWSCHEMA AS schema, VIEWNAME AS name, 'view' AS kind FROM SYSCAT.VIEWS WHERE VIEWSCHEMA NOT LIKE 'SYS%' ORDER BY VIEWSCHEMA, VIEWNAME;")));
    out.push(await sec('存储过程 / 函数', () => db2Run(db, "SELECT ROUTINESCHEMA AS schema, ROUTINENAME AS name, ROUTINETYPE AS type, LANGUAGE AS lang FROM SYSCAT.ROUTINES WHERE ROUTINESCHEMA NOT LIKE 'SYS%' ORDER BY ROUTINESCHEMA, ROUTINENAME;")));
    out.push(await sec('触发器', () => db2Run(db, "SELECT TRIGSCHEMA AS schema, TRIGNAME AS name, TABSCHEMA AS table_schema, TABNAME AS table_name, TRIGTIME AS timing, TRIGEVENT AS event FROM SYSCAT.TRIGGERS WHERE TRIGSCHEMA NOT LIKE 'SYS%' ORDER BY TRIGSCHEMA, TRIGNAME;")));
    out.push(await sec('序列', () => db2Run(db, "SELECT SEQSCHEMA AS schema, SEQNAME AS name, START AS start, INCREMENT AS increment, CYCLE, CACHE, SEQTYPE AS type FROM SYSCAT.SEQUENCES WHERE SEQSCHEMA NOT LIKE 'SYS%' ORDER BY SEQSCHEMA, SEQNAME;")));
    out.push(await sec('模块 / 全局变量', () => db2Run(db, "SELECT MODULESCHEMA AS schema, MODULENAME AS name FROM SYSCAT.MODULES WHERE MODULESCHEMA NOT LIKE 'SYS%' ORDER BY 1,2 FETCH FIRST 100 ROWS ONLY;").catch(() => ({ rows: [] }))));
  }
  return { engine, database, sections: out };
}

async function sqlStatements(engine, database) {
  if (engine === 'sqlite') {
    const p = sqlitePath(database);
    const r = await sec('慢查询 / 热点语句', () => sqliteRun(p, "SELECT * FROM sqlite_stat1 LIMIT 40;"), 'SQLite 无内置慢查询视图；此处展示 ANALYZE 收集的统计');
    return { available: r.rows.length > 0, rows: r.rows, columns: r.columns, section: r, hint: r.error || 'SQLite 不提供语句级统计（可开启 SQLITE_ENABLE_STMT_SCANSTATUS 的发行版才有）' };
  }
  const db = db2Database(database);
  const r = await sec('热点语句 (MON_GET_PKG_CACHE_STMT)', () => db2Run(db, "SELECT EXECUTABLE_ID, NUM_EXECUTIONS AS execs, TOTAL_ACT_TIME AS total_ms, TOTAL_CPU_TIME AS cpu_ms, ROWS_READ, ROWS_RETURNED, STMT_TEXT AS stmt FROM TABLE(SYSPROC.MON_GET_PKG_CACHE_STMT(NULL,NULL,NULL,-2)) AS T ORDER BY TOTAL_ACT_TIME DESC FETCH FIRST 40 ROWS ONLY;").catch(() => ({ rows: [] })), '需要 MON_ACT_METRICS 采集开关');
  return { available: r.rows.length > 0, rows: r.rows, columns: r.columns, section: r, hint: r.error || '' };
}

/* ================= SQL engine: DDL ================= */
async function sqlSchemaAction(engine, { database, table, action, params = {} }) {
  const p = params || {};
  const t = relQid(engine, table);
  const col = (n) => relQid(engine, n);
  const colList = (arr) => {
    const list = Array.isArray(arr) ? arr : String(arr || '').split(',').map((x) => x.trim());
    const clean = list.filter(Boolean).map(String);
    if (!clean.length) bad('缺少列名');
    return clean.map(col).join(', ');
  };
  const stmts = [];
  if (engine === 'sqlite') {
    switch (action) {
      case 'column-add': { if (!p.name) bad('缺少列名'); let s = `ALTER TABLE ${t} ADD COLUMN ${col(p.name)} ${p.type || 'TEXT'}`; if (p.nullable === false) s += ' NOT NULL'; if (p.default) s += ' DEFAULT ' + String(p.default); stmts.push(s + ';'); break; }
      case 'column-rename': { if (!p.name || !p.newName) bad('缺少列名'); stmts.push(`ALTER TABLE ${t} RENAME COLUMN ${col(p.name)} TO ${col(p.newName)};`); break; }
      case 'column-drop': { if (!p.name) bad('缺少列名'); stmts.push(`ALTER TABLE ${t} DROP COLUMN ${col(p.name)};`); break; }
      case 'index-create': { if (!p.name) bad('缺少索引名'); stmts.push(`CREATE ${p.unique ? 'UNIQUE ' : ''}INDEX ${col(p.name)} ON ${t} (${colList(p.columns)});`); break; }
      case 'index-drop': { if (!p.name) bad('缺少索引名'); stmts.push(`DROP INDEX ${col(p.name)};`); break; }
      case 'table-copy': { if (!p.newName) bad('缺少新表名'); stmts.push(`CREATE TABLE ${col(p.newName)} AS SELECT * FROM ${t};`); break; }
      case 'table-truncate': stmts.push(`DELETE FROM ${t};`); break;
      default: bad('SQLite 不支持该 DDL 操作: ' + action);
    }
  } else {
    const typeCheck = (ty) => { const s = String(ty || '').trim(); if (!/^[A-Za-z][A-Za-z0-9_ ]*(\([0-9, ]+\))?$/.test(s) || s.length > 60) bad('无效类型: ' + s); return s; };
    switch (action) {
      case 'column-add': { if (!p.name) bad('缺少列名'); let s = `ALTER TABLE ${t} ADD COLUMN ${col(p.name)} ${typeCheck(p.type)}`; if (p.nullable === false) s += ' NOT NULL'; if (p.default) s += ' DEFAULT ' + String(p.default); stmts.push(s + ';'); break; }
      case 'column-modify': { if (!p.name) bad('缺少列名'); if (p.type) stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} SET DATA TYPE ${typeCheck(p.type)};`); if (p.default) stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} SET DEFAULT ${String(p.default)};`); if (p.dropDefault) stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} DROP DEFAULT;`); if (p.nullable !== false) stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} DROP NOT NULL;`); else stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} SET NOT NULL;`); break; }
      case 'column-drop': { if (!p.name) bad('缺少列名'); stmts.push(`ALTER TABLE ${t} DROP COLUMN ${col(p.name)};`); break; }
      case 'column-rename': { if (!p.name || !p.newName) bad('缺少列名'); stmts.push(`ALTER TABLE ${t} RENAME COLUMN ${col(p.name)} TO ${col(p.newName)};`); break; }
      case 'index-create': { if (!p.name) bad('缺少索引名'); stmts.push(`CREATE ${p.unique ? 'UNIQUE ' : ''}INDEX ${col(p.name)} ON ${t} (${colList(p.columns)});`); break; }
      case 'index-drop': { if (!p.name) bad('缺少索引名'); stmts.push(`DROP INDEX ${col(p.name)};`); break; }
      case 'constraint-add-pk': stmts.push(`ALTER TABLE ${t} ADD PRIMARY KEY (${colList(p.columns)});`); break;
      case 'constraint-add-unique': { if (!p.name) bad('缺少约束名'); stmts.push(`ALTER TABLE ${t} ADD CONSTRAINT ${col(p.name)} UNIQUE (${colList(p.columns)});`); break; }
      case 'constraint-add-fk': { if (!p.name || !p.refTable) bad('缺少外键名或引用表'); const onDel = /^(CASCADE|SET NULL|SET DEFAULT|RESTRICT|NO ACTION)$/i.test(String(p.onDelete || '')) ? String(p.onDelete).toUpperCase() : 'NO ACTION'; stmts.push(`ALTER TABLE ${t} ADD CONSTRAINT ${col(p.name)} FOREIGN KEY (${colList(p.columns)}) REFERENCES ${relQid('db2', p.refTable)} (${colList(p.refColumns)}) ON DELETE ${onDel};`); break; }
      case 'constraint-drop': { if (!p.name) bad('缺少约束名'); stmts.push(`ALTER TABLE ${t} DROP CONSTRAINT ${col(p.name)};`); break; }
      case 'table-comment': { if (!p.comment) bad('缺少注释'); stmts.push(`COMMENT ON TABLE ${t} IS ${relLit('db2', p.comment)};`); break; }
      case 'column-comment': { if (!p.name) bad('缺少列名'); stmts.push(`COMMENT ON COLUMN ${t}.${col(p.name)} IS ${relLit('db2', p.comment || '')};`); break; }
      default: bad('Db2 不支持该 DDL 操作: ' + action);
    }
  }
  const sql = stmts.join('\n');
  const r = await relRun(engine, database, sql, { timeout: 600000 });
  audit('db', 'schema.' + action, { engine, database, table });
  return ok(sql + '\n\n' + ((r.raw || '').trim() || '执行成功'), { sql, columns: r.columns, rows: r.rows });
}

/* ================= SQL engine: console ================= */
async function relConsole(engine, { database, sql, explain, transaction, dryRun }) {
  const body = String(sql || '').trim();
  if (!body) bad('SQL 为空');
  if (body.length > 500000) bad('SQL 过长');
  if (dryRun) {
    /* wrap in a transaction and roll back: only meaningful for engines that support it */
    if (engine === 'db2') {
      const r = await relRun(engine, database, body.replace(/;\s*$/, '') + ';\nROLLBACK;', { timeout: 300000, ignoreError: true });
      return ok('（已回滚）\n' + (r.raw || r.output || ''), { columns: r.columns, rows: r.rows, dryRun: true });
    }
    const r = await relRun(engine, database, 'BEGIN;\n' + body + '\nROLLBACK;', { timeout: 300000, ignoreError: true });
    return ok('（已回滚）\n' + (r.raw || r.output || ''), { columns: r.columns, rows: r.rows, dryRun: true });
  }
  if (explain) {
    if (engine === 'sqlite') {
      const r = await sqliteRun(sqlitePath(database), 'EXPLAIN QUERY PLAN ' + body.replace(/;\s*$/, '') + ';');
      return ok(r.rows.map((x) => (x.detail || '')).join('\n') || (r.raw || ''), { columns: r.columns, rows: r.rows });
    }
    bad('Db2 的 EXPLAIN 需先创建 EXPLAIN 表，请使用「执行计划」入口或直接查询');
  }
  const r = await relRun(engine, database, body, { timeout: 600000 });
  return ok(r.output, { columns: r.columns, rows: r.rows, durationMs: 0, raw: r.raw });
}

/* ================= SQL engine: exports / imports ================= */
function csvField(v) { if (v === null || v === undefined) return ''; const s = String(v); return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; }
function toCsv(columns, rows) { const lines = [columns.map(csvField).join(',')]; for (const r of rows) lines.push(columns.map((c) => csvField(r[c])).join(',')); return lines.join('\n') + '\n'; }
async function sqlExportData(engine, { database, sql, format, table, filename }) {
  const q = String(sql || '').trim();
  if (!q) bad('缺少查询语句');
  if (!/^\s*(select|with|values)\b/i.test(q)) bad('仅支持 SELECT / WITH / VALUES 查询导出');
  const r = await relRun(engine, database, q, { timeout: 600000 });
  if (!r.columns.length) bad('查询没有返回结果集');
  if (r.rows.length > 200000) bad('结果集超过 20 万行，请缩小范围');
  const fmt = ['csv', 'json'].includes(format) ? format : 'csv';
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
  const base = String(filename || (table ? 'table-' + table : 'export')).replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 60);
  const name = `ex-${engine}-${base}-${stamp}.${fmt}`;
  const content = fmt === 'csv' ? toCsv(r.columns, r.rows) : JSON.stringify(r.rows, null, 1) + '\n';
  await run(`mkdir -p ${DB_BACKUP_DIR}`);
  await getAgent().writeFile(DB_BACKUP_DIR + '/' + name, content);
  audit('db', 'export', { engine, database, format: fmt, rows: r.rows.length, name });
  return ok(`已导出 ${r.rows.length} 行 -> ${name} (${(Buffer.byteLength(content) / 1024).toFixed(1)} KB)`, { name, size: Buffer.byteLength(content), rows: r.rows.length, columns: r.columns });
}
async function sqlImportCsv(engine, { database, table, csv, hasHeader, nullEmpty }) {
  if (!table) bad('缺少目标表');
  const text = String(csv || '');
  if (!text.trim()) bad('CSV 内容为空');
  if (text.length > 80 * 1024 * 1024) bad('CSV 过大（>80MB），请拆分');
  const matrix = parseCsv(text);
  if (!matrix.length) bad('无法解析 CSV');
  const sch = await sqlTableSchema(engine, { database, table });
  const tableCols = sch.columns.map((c) => ({ name: c.name, lower: String(c.name).toLowerCase() }));
  const header = hasHeader === false ? null : matrix[0];
  const dataRows = hasHeader === false ? matrix : matrix.slice(1);
  let cols, map;
  if (header) {
    cols = header.map((h) => { const t = tableCols.find((c) => c.lower === String(h || '').trim().toLowerCase()); return t ? t.name : null; }).filter(Boolean);
    map = header.map((h) => { const t = tableCols.find((c) => c.lower === String(h || '').trim().toLowerCase()); return t ? header.indexOf(h) : -1; }).filter((i) => i >= 0);
  } else { cols = tableCols.slice(0, matrix[0].length).map((c) => c.name); map = cols.map((_, i) => i); }
  if (!cols.length) bad('无法匹配任何列（请检查 CSV 表头与表结构列名）');
  const CHUNK = 200;
  const statements = [];
  let inserted = 0;
  for (let i = 0; i < dataRows.length; i += CHUNK) {
    const chunk = dataRows.slice(i, i + CHUNK).filter((r) => r.length && !(r.length === 1 && r[0] === null));
    if (!chunk.length) continue;
    const values = chunk.map((r) => '(' + map.map((ci) => { const v = r[ci]; if (v === null) return 'NULL'; if (typeof v === 'string' && v.trim() === '' && nullEmpty) return 'NULL'; return relLit(engine, v); }).join(', ') + ')').join(',\n  ');
    statements.push(`INSERT INTO ${relQid(engine, table)} (${cols.map((c) => relQid(engine, c)).join(', ')}) VALUES\n  ${values};`);
    inserted += chunk.length;
  }
  if (!statements.length) bad('没有可导入的数据行');
  const sql = statements.join('\n');
  await relRun(engine, database, sql, { timeout: 1800000 });
  audit('db', 'import', { engine, database, table, rows: inserted });
  return ok(`导入完成：${inserted} 行 -> ${table}\n列映射: ${cols.join(', ')}`, { inserted, cols });
}

/* ================= SQL engine: backup / restore ================= */
function dbEngineBackupPath(name) {
  const n = String(name || '');
  if (n.includes('..') || n.includes('/')) return null;
  if (/^db-[A-Za-z0-9._-]+\.(sql\.gz|sql|dump|zip|rdb)$/.test(n)) return DB_BACKUP_DIR + '/' + n;
  if (/^up-[A-Za-z0-9._-]+\.(sql|gz|csv|dump|zip|rdb)$/.test(n)) return UPLOAD_DIR + '/' + n;
  if (/^ex-[A-Za-z0-9._-]+\.(csv|json|sql)$/.test(n)) return DB_BACKUP_DIR + '/' + n;
  return null;
}
async function sqlListDbBackups(engine) {
  const r = await run(`ls -la ${DB_BACKUP_DIR}/*.sql.gz ${DB_BACKUP_DIR}/*.sql ${DB_BACKUP_DIR}/*.dump ${DB_BACKUP_DIR}/*.csv ${DB_BACKUP_DIR}/*.json ${DB_BACKUP_DIR}/*.rdb ${DB_BACKUP_DIR}/*.zip ${UPLOAD_DIR}/* 2>/dev/null | grep -v '^total' || true`, { timeout: 20000 });
  const items = [];
  for (const line of String(r.stdout).split('\n')) {
    const m = /^-\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+\S+\s+(\S+)$/.exec(line.trim());
    if (!m) continue;
    const full = m[2]; const name = full.split('/').pop();
    const em = /^db-(sqlite|db2|redis|mongo|postgres|mysql)-/.exec(name);
    items.push({ name, size: parseInt(m[1], 10), uploaded: full.includes('/uploads/'), export: name.startsWith('ex-'), engine: em ? em[1] : '', database: '', mode: /\.dump$/.test(name) ? 'custom' : (/\.rdb$/.test(name) ? 'rdb' : 'full'), mtime: line.trim().split(/\s+/).slice(5, 8).join(' ') });
  }
  const wanted = items.filter((i) => !engine || !i.engine || i.engine === engine || i.uploaded || i.export);
  return { dir: DB_BACKUP_DIR, items: wanted.reverse() };
}
async function sqlCreateDbBackup(engine, { database, mode, name: _name }) {
  const mode2 = ['full', 'schema', 'data'].includes(mode) ? mode : 'full';
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
  if (engine === 'sqlite') {
    const p = sqlitePath(database);
    const safe = path.basename(p).replace(/[^A-Za-z0-9_.-]/g, '_');
    const name = `db-sqlite-${safe}-${mode2}-${stamp}.sql.gz`;
    const out = `${DB_BACKUP_DIR}/${name}`;
    const r = await run(`set -o pipefail; mkdir -p ${DB_BACKUP_DIR} && echo ${b64('.dump')} | base64 -d | sqlite3 ${shq(p)} | gzip -1 > ${shq(out)} && gzip -t ${shq(out)}`, { timeout: 3600000 });
    if (r.code !== 0) throw Object.assign(new Error('备份失败: ' + String(r.stderr || r.stdout).slice(0, 1500)), { status: 400 });
    const st = await run(`stat -c %s ${shq(out)} 2>/dev/null || echo 0`);
    const size = parseInt(String(st.stdout).trim(), 10) || 0;
    if (!size) throw Object.assign(new Error('备份文件为空'), { status: 400 });
    audit('db', 'backup.create', { engine, database: p, name });
    return ok(`备份完成: ${name} (${(size / 1048576).toFixed(2)} MB)`, { name, size });
  }
  /* db2: online backup to the backup dir */
  const inst = db2Instance();
  const db = db2Database(database);
  const name = `db-db2-${db}-${mode2}-${stamp}`;
  await run(`mkdir -p ${DB_BACKUP_DIR} && chmod 777 ${DB_BACKUP_DIR}`);
  const cmd = `BACKUP DATABASE ${db} ${mode2 === 'schema' ? 'ONLINE' : 'ONLINE'} TO ${DB_BACKUP_DIR} WITHOUT PROMPTING`;
  const r = await run(`su - ${shq(inst)} -c ${shq(`db2 ${shq(cmd)}`)}`, { timeout: 7200000, maxBuffer: 16 * 1024 * 1024 });
  const out = (String(r.stdout || '') + String(r.stderr || '')).trim();
  if (r.code !== 0 || /SQL\d{5}N/.test(out)) throw Object.assign(new Error('Db2 备份失败:\n' + out.slice(0, 2000)), { status: 400, output: out });
  audit('db', 'backup.create', { engine, database: db });
  return ok('Db2 备份完成（输出到 ' + DB_BACKUP_DIR + '）:\n' + out.slice(0, 1500), { name });
}
async function sqlRestoreDbBackup(engine, { database, file, createIfMissing }) {
  const p = dbEngineBackupPath(file) || null;
  if (!p) bad('非法备份文件名');
  if (engine === 'sqlite') {
    const target = sqlitePath(database);
    const isGz = /\.sql\.gz$/.test(file);
    if (isGz || /\.sql$/.test(file)) {
      if (String((await run(`test -e ${shq(target)} && echo 1 || echo 0`)).stdout).trim() === '0' && !createIfMissing) bad('目标数据库不存在（可勾选自动创建）');
      await run(`mkdir -p ${shq(path.dirname(target))}`);
      const src = isGz ? `gunzip -c ${shq(p)}` : `cat ${shq(p)}`;
      const cmd = `set -o pipefail; ${src} | sqlite3 ${shq(target)}`;
      const r = await run(cmd, { timeout: 3600000, maxBuffer: 32 * 1024 * 1024 });
      if (r.code !== 0) throw Object.assign(new Error('恢复失败: ' + String(r.stderr || r.stdout).slice(0, 2000)), { status: 400, output: r.stderr });
      audit('db', 'backup.restore', { engine, database: target, file });
      return ok('恢复完成 -> ' + target);
    }
    bad('SQLite 仅支持恢复 .sql / .sql.gz 备份');
  }
  /* db2 restore */
  const inst = db2Instance();
  let db = String(database || '').trim();
  if (!db) bad('缺少目标数据库名');
  const cmd = `RESTORE DATABASE ${db} FROM ${DB_BACKUP_DIR} TAKEN AT ${String(file).replace(/[^0-9]/g, '').slice(0, 14) || '00000000000000'} WITHOUT PROMPTING`;
  if (!/^\d{14}$/.test(cmd.split('TAKEN AT ')[1].split(' ')[0])) bad('Db2 恢复需要备份时间戳文件名（包含 14 位 TAKEN AT）');
  const r = await run(`su - ${shq(inst)} -c ${shq(`db2 ${shq(cmd)}`)}`, { timeout: 7200000, maxBuffer: 16 * 1024 * 1024 });
  const out = (String(r.stdout || '') + String(r.stderr || '')).trim();
  if (r.code !== 0 || /SQL\d{5}N/.test(out)) throw Object.assign(new Error('Db2 恢复失败:\n' + out.slice(0, 2000)), { status: 400, output: out });
  return ok('Db2 恢复完成:\n' + out.slice(0, 1500));
}
async function sqlDeleteDbBackup({ name }) {
  const p = dbEngineBackupPath(name);
  if (!p) bad('非法文件名');
  return ok(await run(`rm -f ${shq(p)} && echo 已删除 ${name}`));
}

/* ================= SQL engine: roles / sessions (db2) ================= */
async function sqlListRoles(engine) {
  if (engine === 'sqlite') return { roles: [], unsupported: 'SQLite 是单文件数据库，没有用户/角色概念（访问控制由操作系统的文件权限决定）' };
  const db = db2Database();
  const users = await db2Run(db, "SELECT USERID AS name, DBADMAUTH AS dbadm, DATAACCESSAUTH AS data_access, ACCESSCTRLAUTH AS access_ctrl FROM SYSIBM.SYSUSERAUTH WHERE USERID NOT LIKE 'SYS%' ORDER BY USERID;", { ignoreError: true }).catch(() => ({ rows: [] }));
  if ((users.rows || []).length) {
    return { roles: users.rows.map((x) => ({ name: x.NAME, dbadm: x.DBADM, data_access: x.DATA_ACCESS, access_ctrl: x.ACCESS_CTRL })), unsupported: '' };
  }
  const r = await db2Run(db, "SELECT AUTHORITY AS name, 'system' AS kind FROM (VALUES 'SYSADM','SYSCTRL','SYSMAINT','SYSMON','DBADM','SECADM','DATAACCESS','ACCESSCTRL') AS T(AUTHORITY);").catch(() => ({ rows: [] }));
  return { roles: (r.rows || []).map((x) => ({ name: x.NAME, kind: x.KIND })), unsupported: 'Db2 通过 GRANT 授予权限；用户需先在实例上创建。' };
}
async function sqlRoleAction(engine, body) {
  if (engine === 'sqlite') bad('SQLite 不支持用户/角色管理');
  const db = db2Database();
  const auth = /^(ALL|DBADM|DATAACCESS|ACCESSCTRL|SECADM|SYSADM|CONNECT)$/i.test(String(body.privilege || '')) ? String(body.privilege).toUpperCase() : 'DBADM';
  const verb = /^revoke/i.test(String(body.action || '')) ? 'REVOKE' : 'GRANT';
  let sql;
  if (body.action === 'grant-db' || body.action === 'revoke-db') {
    if (!body.name) bad('缺少用户');
    const clause = auth === 'CONNECT' ? 'CONNECT ON DATABASE' : `${auth} ON DATABASE`;
    sql = `${verb} ${clause} ${verb === 'GRANT' ? 'TO' : 'FROM'} USER ${relQid('db2', body.name)};`;
  } else if (body.action === 'grant-table' || body.action === 'revoke-table') {
    if (!body.name) bad('缺少用户');
    const tbl = body.params && body.params.table;
    if (!tbl) bad('缺少表名');
    const privs = (body.params && body.params.privileges || ['SELECT']).map((x) => String(x).toUpperCase()).join(', ');
    sql = `${verb} ${privs} ON TABLE ${relQid('db2', tbl)} ${verb === 'GRANT' ? 'TO' : 'FROM'} USER ${relQid('db2', body.name)};`;
  } else bad('Db2 不支持该角色操作: ' + body.action);
  const r = await relRun(engine, db, sql, { timeout: 120000 });
  return ok(sql + '\n' + (r.raw || r.output || '').trim());
}
async function sqlKillProcess(engine, { pid }) {
  if (engine === 'sqlite') bad('SQLite 没有会话可终止');
  const db = db2Database();
  const id = parseInt(pid, 10);
  if (!Number.isFinite(id)) bad('PID 无效');
  const r = await db2Run(db, `CALL SYSPROC.ADMIN_CMD('FORCE APPLICATION (${id})');`, { timeout: 60000 }).catch((e) => ({ output: e.message }));
  return ok('已尝试强制结束应用 ' + id + '\n' + (r.output || ''));
}
async function sqlSessionManage(engine) { if (engine === 'sqlite') bad('SQLite 没有会话'); bad('会话批量清理暂仅支持 PostgreSQL / MySQL'); }

/* ================= SQL engine: explain tree ================= */
async function sqlExplainTree(engine, { database, sql, analyze }) {
  if (engine === 'sqlite') {
    const p = sqlitePath(database);
    /* sqlite3 CLI renders EXPLAIN QUERY PLAN as an indented tree in a single
       "QUERY PLAN" column, so parse the tree text rather than a column matrix. */
    const r = await sqliteRun(p, 'EXPLAIN QUERY PLAN ' + String(sql).replace(/;\s*$/, '') + ';', { parse: false });
    const lines = String(r.raw || '').split('\n').map((l) => l.replace(/\r$/, '')).filter((l) => l.trim() !== '');
    const dataLines = lines.filter((l) => !/^QUERY PLAN\s*$/i.test(l.trim()));
    const nodes = dataLines.map((l, i) => {
      const indent = l.match(/^\s*/)[0].length;
      const text = l.replace(/^[\s`|+-]+/, '').trim();
      return { depth: Math.floor(indent / 3), id: i, node: text, relation: (/SCAN\s+(\S+)/i.exec(text) || [])[1] || '', cost: null, rows: null, actual_rows: null, actual_time: null, loop: null, detail: text, filter: '', sort: '' };
    });
    return { engine, analyze: false, nodes, raw: JSON.stringify(r.raw || '').slice(0, 20000) };
  }
  const db = db2Database(database);
  const r = await db2Run(db, 'EXPLAIN ALL FOR ' + String(sql).replace(/;\s*$/, '') + ';', { ignoreError: true }).catch(() => ({ raw: '' }));
  const raw = String(r.raw || r.output || '').slice(0, 20000);
  if (!raw.trim()) bad('Db2 无法生成执行计划（需先创建 EXPLAIN 表：CALL SYSPROC.SYSINSTALLOBJECTS(\'EXPLAIN\',\'C\',NULL,CURRENT SCHEMA)）');
  const nodes = raw.split('\n').filter(Boolean).slice(0, 400).map((line, i) => ({ depth: 0, id: i, node: line.slice(0, 200), detail: line, cost: null }));
  return { engine, analyze: false, nodes, raw };
}

/* ================= SQL engine: value search ================= */
async function sqlSearchValue(engine, { database, table, value, limit }) {
  const v = String(value == null ? '' : value);
  if (!v || v.length > 200) bad('请输入 1-200 字符的搜索值');
  const lim = Math.max(1, Math.min(200, parseInt(limit, 10) || 50));
  const like = '%' + v.replace(/([!%_])/g, '!$1') + '%';
  let tables = [];
  if (table) tables = [String(table)];
  else { const t = await sqlListTables(engine, database); tables = t.filter((x) => x.kind === 'table').slice(0, 40).map((x) => (engine === 'sqlite' ? x.name : x.schema + '.' + x.name)); }
  const out = []; const errors = [];
  for (const t of tables) {
    try {
      const sch = await sqlTableSchema(engine, { database, table: t });
      const textCols = sch.columns.filter((c) => /char|text|clob|xml|varchar|graphic/i.test(String(c.type)));
      if (!textCols.length) continue;
      const cond = textCols.map((c) => `${relQid(engine, c.name)} LIKE ${relLit(engine, like)} ESCAPE '!'`).join(' OR ');
      const r = await relRun(engine, database, `SELECT * FROM ${relQid(engine, t)} WHERE ${cond}`, { timeout: 30000 });
      if (r.rows.length) out.push({ table: t, matched: r.rows.length, columns: r.columns, rows: r.rows.slice(0, Math.min(lim, 20)) });
    } catch (e) { errors.push({ table: t, error: String(e.message || e).replace(/\s+/g, ' ').slice(0, 200) }); }
    if (out.length >= 15) break;
  }
  if (table && errors.length) throw Object.assign(new Error('搜索失败: ' + errors[0].error), { status: 400 });
  return { value: v, scanned: tables.length, hits: out, errors, skipped: [], truncated: false };
}

/* ================= SQL engine: extra monitor / maintenance plan ================= */
async function sqlExtraMonitor(engine, database) {
  const out = [];
  if (engine === 'sqlite') {
    const p = sqlitePath(database);
    const s = async (title, q, note) => out.push(await sec(title, () => sqliteRun(p, q), note));
    await s('页缓存 / 日志', "SELECT (SELECT * FROM pragma_page_count()) AS page_count, (SELECT * FROM pragma_freelist_count()) AS freelist, (SELECT * FROM pragma_journal_mode()) AS journal_mode, (SELECT * FROM pragma_auto_vacuum()) AS auto_vacuum, (SELECT * FROM pragma_synchronous()) AS synchronous;");
    await s('缓存参数', "SELECT (SELECT * FROM pragma_cache_size()) AS cache_size, (SELECT * FROM pragma_mmap_size()) AS mmap_size, (SELECT * FROM pragma_temp_store()) AS temp_store;");
    await s('编译选项', 'PRAGMA compile_options;');
    await s('完整性', 'PRAGMA integrity_check;');
    await s('外键一致性', 'PRAGMA foreign_key_check;');
  } else {
    const db = db2Database(database);
    const s = async (title, q, note) => out.push(await sec(title, () => db2Run(db, q).catch((e) => ({ rows: [], columns: [], error: e.message })), note));
    await s('数据库信息', "SELECT DB_NAME AS db, DB_STATUS AS status, DB_PATH AS path, TOTAL_PAGES AS total_pages, APPLS_CUR_CONS AS connections FROM SYSIBMADM.SNAPDB FETCH FIRST 5 ROWS ONLY;", '需要 SYSIBMADM 视图');
    await s('表空间', "SELECT TBSP_NAME AS tbsp, TBSP_STATE AS state, TBSP_USED_PAGES AS used_pages, TBSP_FREE_PAGES AS free_pages FROM SYSIBMADM.TBSP_UTILIZATION FETCH FIRST 20 ROWS ONLY;");
    await s('锁等待', "SELECT * FROM SYSIBMADM.LOCKWAITS FETCH FIRST 20 ROWS ONLY;");
    await s('长时间运行语句', "SELECT APPLICATION_HANDLE AS app, ELAPSED_TIME_SEC AS secs, STMT_TEXT FROM TABLE(SYSPROC.MON_GET_ACTIVITY(NULL,-2)) AS T WHERE ELAPSED_TIME_SEC > 5 FETCH FIRST 20 ROWS ONLY;");
    await s('缓冲池命中率', "SELECT BP_NAME AS bp, POOL_HIT_RATIO_PERCENT AS hit_pct, POOL_DATA_L_READS AS l_reads, POOL_DATA_P_READS AS p_reads FROM SYSIBMADM.BP_HITRATIO FETCH FIRST 20 ROWS ONLY;");
  }
  return { engine, sections: out };
}
async function sqlMaintenancePlan(engine, { action, schedule }) {
  const sch = String(schedule || '0 4 * * *');
  if (!/^[0-9*,\-\/ ]{1,64}$/.test(sch)) bad('无效 cron 表达式');
  let command;
  if (engine === 'sqlite') {
    command = action === 'backup-all'
      ? `find / -maxdepth 4 -name '*.db' -exec sh -c 'gzip -c "$1" > ${DB_BACKUP_DIR}/db-sqlite-$(basename "$1")-$(date +\\%Y-\\%m-\\%d).sql.gz' _ {} \\; 2>/dev/null`
      : `true  # SQLite 维护请在面板中按库执行`;
  } else {
    command = `su - ${db2Instance()} -c 'db2 RUNSTATS ON DATABASE'`;
  }
  const name = `dbmaint-${engine}-${action}`.replace(/[^A-Za-z0-9_-]/g, '-').slice(0, 40);
  await run('mkdir -p /etc/cron.d');
  await getAgent().writeFile('/etc/cron.d/cm-' + name, `# managed by cloud-monitor (db maintenance plan)\n${sch} root ${command}\n`);
  await run(`chmod 644 /etc/cron.d/cm-${name}`);
  return ok(`已创建维护计划 /etc/cron.d/cm-${name}\n${sch} root ${command}`, { name });
}

/* ======================================================================
 *  REDIS (kv)
 * ====================================================================== */
function redisBase(db) {
  const c = (config.get().db || {}).redis || {};
  const args = ['--no-auth-warning', '-h', shq(c.host || '127.0.0.1'), '-p', shq(c.port || '6379')];
  if (c.password) args.push('-a', shq(c.password));
  const idx = (db === undefined || db === null || db === '') ? (c.db || '0') : db;
  if (idx !== undefined && idx !== null && idx !== '') args.push('-n', shq(idx));
  return 'redis-cli ' + args.join(' ');
}
const redisClient = redisBase;
async function redisCmd(db, command, opts = {}) {
  if (!(await binExists('redis-cli'))) bad('Redis 未安装（可在「数据库」页点击「一键安装」）');
  const full = `echo ${b64(command)} | base64 -d | ${redisBase(db)}`;
  return run(full, { timeout: opts.timeout || 30000, maxBuffer: 16 * 1024 * 1024 });
}
/* run redis-cli with arguments that are not a command read from stdin (e.g. --scan) */
async function redisRaw(db, argsString, opts = {}) {
  if (!(await binExists('redis-cli'))) bad('Redis 未安装（可在「数据库」页点击「一键安装」）');
  return run(`${redisBase(db)} ${argsString}`, { timeout: opts.timeout || 30000, maxBuffer: 16 * 1024 * 1024 });
}
function redisBuildCmd(parts) {
  return (Array.isArray(parts) ? parts : String(parts).split(/\s+/)).map((t) => shq(t)).join(' ');
}
function parseInfo(text) {
  const out = {};
  let section = '';
  for (const line of String(text).split('\n')) {
    const t = line.replace(/\r$/, '');
    if (!t || t.startsWith('#')) { const m = /^#\s*(.+)$/.exec(t); if (m) section = m[1].trim(); continue; }
    const i = t.indexOf(':');
    if (i < 0) continue;
    out[section ? section + ':' + t.slice(0, i) : t.slice(0, i)] = t.slice(i + 1);
  }
  return out;
}
async function redisStatus() {
  const ping = await redisCmd(undefined, 'PING').catch((e) => ({ code: -1, stdout: '', stderr: e.message }));
  if (!/PONG/.test(String(ping.stdout || ''))) bad('Redis 未运行或连接失败：' + String(ping.stderr || ping.stdout || 'no PONG').slice(0, 300));
  const [info, dbsize, conf, dbsConf] = await Promise.all([
    redisCmd(undefined, 'INFO'),
    redisCmd(undefined, 'DBSIZE'),
    redisCmd(undefined, 'CONFIG GET maxmemory').catch(() => ({ stdout: '' })),
    redisCmd(undefined, 'CONFIG GET databases').catch(() => ({ stdout: '' })),
  ]);
  const configuredDbs = parseInt(String(String(dbsConf.stdout || '').split('\n')[1] || '0').trim(), 10) || 16;
  const parsed = parseInfo(info.stdout);
  const server = {}; const clients = {}; const memory = {}; const stats = {}; const keyspace = {};
  for (const [k, v] of Object.entries(parsed)) {
    if (k.startsWith('Server:')) server[k.slice(7)] = v;
    else if (k.startsWith('Clients:')) clients[k.slice(8)] = v;
    else if (k.startsWith('Memory:')) memory[k.slice(7)] = v;
    else if (k.startsWith('Stats:')) stats[k.slice(6)] = v;
    else if (k.startsWith('Keyspace:')) keyspace[k.slice(9)] = v;
  }
  const processes = [];
  try {
    const cl = await redisCmd(undefined, 'CLIENT LIST');
    for (const line of String(cl.stdout).split('\n')) {
      if (!line.trim()) continue;
      const kv = {};
      for (const pair of line.split(' ')) { const i = pair.indexOf('='); if (i > 0) kv[pair.slice(0, i)] = pair.slice(i + 1); }
      processes.push({ pid: kv.id, user: kv.user || kv.name || '-', db: kv.db, state: kv.cmd, seconds: kv.age, client: kv.addr, query: String(kv.cmd || '') + ' ' + String(kv.args || '').slice(0, 120) });
    }
  } catch (_) {}
  const dbs = Object.entries(keyspace).map(([k, v]) => ({ name: k, size: v.split(',')[0].replace('keys=', '') + ' 键', keys: Number((/keys=(\d+)/.exec(v) || [])[1] || 0) }));
  return {
    engine: 'redis',
    version: { version: 'Redis ' + (server.redis_version || ''), mode: server.redis_mode, os: server.os, uptime: server.uptime_in_seconds ? server.uptime_in_seconds + 's' : '' },
    stats: { connections: clients.connected_clients || dbsize.stdout.trim(), max_connections: clients.maxclients || '-', active: '-', databases: configuredDbs, used_dbs: dbs.length, commands: stats.total_commands_processed, hits: stats.keyspace_hits, misses: stats.keyspace_misses, memory: memory.used_memory_human || '', maxmemory: memory.maxmemory_human || '0' },
    processes, variables: Object.entries(parsed).slice(0, 400).map(([name, value]) => ({ name, value, unit: '', category: name.split(':')[0] })),
    capabilities: Object.assign({}, META.redis.caps),
    databases: dbs,
  };
}
async function redisDatabases() {
  const info = await redisCmd(undefined, 'INFO keyspace');
  const ks = {};
  for (const line of String(info.stdout).split('\n')) { const m = /^db(\d+):keys=(\d+),expires=(\d+),avg_ttl=(\d+)/.exec(line.trim()); if (m) ks[Number(m[1])] = { keys: Number(m[2]), expires: Number(m[3]), avg_ttl: Number(m[4]) }; }
  const dbs = [];
  for (let i = 0; i < 16; i++) dbs.push({ name: 'db' + i, index: i, keys: ks[i] ? ks[i].keys : 0, expires: ks[i] ? ks[i].expires : 0, avg_ttl: ks[i] ? ks[i].avg_ttl : 0, size_bytes: 0, size: (ks[i] ? ks[i].keys : 0) + ' keys', is_template: false });
  return dbs;
}
async function redisKeys({ database, pattern, limit, cursor }) {
  const dbIdx = String(database || '0').replace(/[^0-9]/g, '') || '0';
  const lim = Math.max(1, Math.min(1000, parseInt(limit, 10) || 200));
  const pat = pattern ? String(pattern) : '*';
  const scan = await redisRaw(dbIdx, `--scan --pattern ${shq(pat)} | head -n ${lim}`);
  const keys = String(scan.stdout).split('\n').map((s) => s.trim()).filter(Boolean);
  const details = {};
  if (keys.length) {
    /* 单次 shell 批量取 type/ttl/size，避免每个 key 一次远程调用 */
    const script = keys.map((k) => `printf '%s\\t' ${shq(k)}; ${redisBase(dbIdx)} TYPE ${shq(k)} | tr -d '\\n'; printf '\\t'; ${redisBase(dbIdx)} TTL ${shq(k)} | tr -d '\\n'; printf '\\t'; ${redisBase(dbIdx)} MEMORY USAGE ${shq(k)} | tr -d '\\n'; printf '\\n'`).join('\n');
    const dr = await run(script, { timeout: 60000, maxBuffer: 32 * 1024 * 1024 });
    for (const line of String(dr.stdout).split('\n')) {
      const p = line.split('\t');
      if (p.length >= 4) details[p[0]] = { type: p[1], ttl: parseInt(p[2], 10), size: parseInt(p[3], 10) || null };
    }
  }
  return { keys: keys.map((k) => ({ key: k, type: (details[k] || {}).type || 'unknown', ttl: (details[k] || {}).ttl ?? -1, size: (details[k] || {}).size ?? null })), cursor: '0', db: dbIdx, pattern: pat };
}
async function redisKeyValue({ database, key }) {
  if (!key) bad('缺少 key');
  const dbIdx = String(database || '0').replace(/[^0-9]/g, '') || '0';
  const typeR = await redisCmd(dbIdx, redisBuildCmd(['TYPE', key]));
  const type = typeR.stdout.trim();
  let value = [];
  if (type === 'string') { const r = await redisCmd(dbIdx, redisBuildCmd(['GET', key])); value = [r.stdout.replace(/\n$/, '')]; }
  else if (type === 'hash') { const r = await redisCmd(dbIdx, redisBuildCmd(['HGETALL', key])); value = String(r.stdout).split('\n').filter((x, i) => i % 2 === 1); const fields = String(r.stdout).split('\n').filter((x, i) => i % 2 === 0); return { type, key, rows: fields.map((f, i) => ({ field: f, value: value[i] })) }; }
  else if (type === 'list') { const r = await redisCmd(dbIdx, redisBuildCmd(['LRANGE', key, '0', '200'])); value = String(r.stdout).split('\n').slice(0, -1); }
  else if (type === 'set') { const r = await redisCmd(dbIdx, redisBuildCmd(['SMEMBERS', key])); value = String(r.stdout).split('\n').slice(0, -1); }
  else if (type === 'zset') { const r = await redisCmd(dbIdx, redisBuildCmd(['ZRANGE', key, '0', '200', 'WITHSCORES'])); const l = String(r.stdout).split('\n').slice(0, -1); const rows = []; for (let i = 0; i < l.length; i += 2) rows.push({ member: l[i], score: l[i + 1] }); return { type, key, rows }; }
  else if (type === 'stream') { const r = await redisCmd(dbIdx, redisBuildCmd(['XRANGE', key, '-', '+', 'COUNT', '100'])); value = String(r.stdout).split('\n').slice(0, -1); }
  const ttlR = await redisCmd(dbIdx, redisBuildCmd(['TTL', key]));
  return { type, key, value, ttl: parseInt(ttlR.stdout.trim(), 10) };
}
async function redisSetValue({ database, key, value, ttl, type }) {
  if (!key) bad('缺少 key');
  const dbIdx = String(database || '0').replace(/[^0-9]/g, '') || '0';
  const t = type || 'string';
  const r = await redisCmd(dbIdx, redisBuildCmd(t === 'string' ? ['SET', key, String(value == null ? '' : value)] : ['SET', key, String(value == null ? '' : value)]));
  if (ttl && Number(ttl) > 0) await redisCmd(dbIdx, redisBuildCmd(['EXPIRE', key, String(parseInt(ttl, 10))]));
  return ok(r.stdout.trim() || 'OK');
}
async function redisDeleteKeys({ database, keys }) {
  const list = (Array.isArray(keys) ? keys : String(keys || '').split('\n')).map((x) => String(x).trim()).filter(Boolean);
  if (!list.length) bad('没有要删除的 key');
  const dbIdx = String(database || '0').replace(/[^0-9]/g, '') || '0';
  let n = 0;
  for (const k of list) { const r = await redisCmd(dbIdx, redisBuildCmd(['DEL', k])); n += parseInt(r.stdout.trim(), 10) || 0; }
  return ok(`已删除 ${n} 个 key`, { deleted: n });
}
async function redisConsole({ database, command, sql }) {
  /* the shared /db/console route sends `sql`; accept both names */
  const cmd = String(command || sql || '').trim();
  if (!cmd) bad('命令为空');
  if (/[\n;]/.test(cmd) && !/^(EVAL|EVALSHA)\b/i.test(cmd)) {
    /* allow multi-token single command only */
  }
  const r = await redisCmd(String(database || '0').replace(/[^0-9]/g, '') || '0', redisBuildCmd(cmd.split(/\s+/)));
  const out = String(r.stdout).replace(/\n$/, '');
  if (/^\(error\)/i.test(out) || r.code !== 0) throw Object.assign(new Error(out || 'Redis 执行失败'), { status: 400, output: out });
  return ok(out || '(nil)', { raw: out });
}
async function redisInfo() {
  const r = await redisCmd(undefined, 'INFO all');
  const parsed = parseInfo(r.stdout);
  return { sections: Object.entries(parsed).map(([name, value]) => ({ title: name, columns: ['value'], rows: [{ value }] })) };
}
async function redisConfigList() {
  if (!(await binExists('redis-cli'))) return ok('', { rows: [] });
  try {
    const r = await redisCmd(undefined, 'CONFIG GET *');
    const lines = String(r.stdout).split('\n').filter((x) => x !== '');
    const rows = [];
    for (let i = 0; i < lines.length; i += 2) rows.push({ name: lines[i], value: lines[i + 1] });
    return ok('', { rows });
  } catch (_) {
    return ok('', { rows: [] });
  }
}
async function redisConfigSet({ name, value }) {
  if (!name) bad('缺少配置项');
  const r = await redisCmd(undefined, redisBuildCmd(['CONFIG', 'SET', String(name), String(value == null ? '' : value)]));
  const out = r.stdout.trim();
  if (/error/i.test(out)) throw Object.assign(new Error(out), { status: 400 });
  return ok(out || 'OK');
}
async function redisSlowlog({ limit }) {
  const r = await redisCmd(undefined, redisBuildCmd(['SLOWLOG', 'GET', String(Math.max(1, Math.min(200, parseInt(limit, 10) || 50)))]));
  const lines = String(r.stdout).split('\n').filter(Boolean);
  return { sections: [{ title: '慢查询日志 (SLOWLOG)', columns: ['entry'], rows: lines.map((l) => ({ entry: l })) }] };
}
async function redisAclList() {
  const r = await redisCmd(undefined, 'ACL LIST');
  return { roles: String(r.stdout).split('\n').filter(Boolean).map((line) => ({ name: (line.split(' ')[1] || '').replace(/^user=/i, ''), rule: line })), unsupported: '' };
}
async function redisAclAction({ action, name, password, rule }) {
  if (!name) bad('缺少用户名');
  let cmd;
  if (action === 'create') cmd = ['ACL', 'SETUSER', name, 'on'].concat(password ? ['>'.concat(String(password))] : []).concat(['~*', '+@all']);
  else if (action === 'drop') cmd = ['ACL', 'DELUSER', name];
  else if (action === 'password') cmd = ['ACL', 'SETUSER', name, '>'.concat(String(password))];
  else if (action === 'rule') cmd = ['ACL', 'SETUSER', name].concat(String(rule || '').split(/\s+/).filter(Boolean));
  else bad('未知 ACL 操作: ' + action);
  const r = await redisCmd(undefined, redisBuildCmd(cmd));
  const out = r.stdout.trim();
  if (/error/i.test(out)) throw Object.assign(new Error(out), { status: 400 });
  return ok(out || 'OK');
}
async function redisBackup({ mode }) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
  const name = `db-redis-ALL-${mode || 'rdb'}-${stamp}.rdb`;
  const dir = await redisCmd(undefined, 'CONFIG GET dir');
  const dbfile = await redisCmd(undefined, 'CONFIG GET dbfilename');
  const lines = String(dir.stdout).split('\n');
  const dirPath = String(lines[1] || '/var/lib/redis').trim();
  const fileName = String(String(dbfile.stdout).split('\n')[1] || 'dump.rdb').trim();
  await redisCmd(undefined, 'BGSAVE');
  await new Promise((res) => setTimeout(res, 1500));
  const r = await run(`mkdir -p ${DB_BACKUP_DIR} && cp ${shq(dirPath + '/' + fileName)} ${shq(DB_BACKUP_DIR + '/' + name)} 2>&1 && stat -c %s ${shq(DB_BACKUP_DIR + '/' + name)}`);
  const size = parseInt(String(r.stdout).trim().split('\n').pop(), 10) || 0;
  if (r.code !== 0 || !size) throw Object.assign(new Error('Redis 备份失败: ' + String(r.stdout || r.stderr).slice(0, 1000)), { status: 400 });
  audit('db', 'backup.create', { engine: 'redis', name });
  return ok(`Redis 备份完成: ${name} (${(size / 1048576).toFixed(2)} MB)`, { name, size });
}
async function redisRestore({ file }) {
  const p = dbEngineBackupPath(file);
  if (!p) bad('非法备份文件名');
  const dir = await redisCmd(undefined, 'CONFIG GET dir');
  const dbfile = await redisCmd(undefined, 'CONFIG GET dbfilename');
  const dirPath = String(String(dir.stdout).split('\n')[1] || '/var/lib/redis').trim();
  const fileName = String(String(dbfile.stdout).split('\n')[1] || 'dump.rdb').trim();
  const r = await run(`cp ${shq(p)} ${shq(dirPath + '/' + fileName)} 2>&1 && chown redis:redis ${shq(dirPath + '/' + fileName)} 2>/dev/null; echo copied`);
  if (r.code !== 0) throw Object.assign(new Error('恢复失败: ' + String(r.stdout || r.stderr).slice(0, 600)), { status: 400 });
  return ok('已将备份文件复制到 Redis 数据目录 (' + dirPath + '/' + fileName + ')。请重启 Redis 服务使数据生效。');
}
async function redisMaintenance({ action }) {
  let cmd;
  if (action === 'bgsave') cmd = 'BGSAVE';
  else if (action === 'bgrewriteaof') cmd = 'BGREWRITEAOF';
  else if (action === 'save') cmd = 'SAVE';
  else if (action === 'flushdb') cmd = 'FLUSHDB';
  else if (action === 'flushall') cmd = 'FLUSHALL';
  else bad('未知 Redis 维护操作: ' + action);
  const r = await redisCmd(undefined, cmd);
  return ok(r.stdout.trim() || 'OK');
}
async function redisExtraMonitor() {
  const r = await redisCmd(undefined, 'INFO all');
  const parsed = parseInfo(r.stdout);
  const groups = {};
  for (const [k, v] of Object.entries(parsed)) { const [sec2] = k.split(':'); (groups[sec2] = groups[sec2] || []).push({ key: k.split(':').slice(1).join(':'), value: v }); }
  return { engine: 'redis', sections: Object.entries(groups).map(([title, rows]) => ({ title, columns: ['key', 'value'], rows })) };
}

/* ======================================================================
 *  MONGODB (doc)
 * ====================================================================== */
function mongoClient() {
  const c = (config.get().db || {}).mongo || {};
  const args = ['--quiet', '--norc', '--host', shq(c.host || '127.0.0.1'), '--port', shq(c.port || '27017')];
  if (c.user) { args.push('-u', shq(c.user)); if (c.password) args.push('-p', shq(c.password)); args.push('--authenticationDatabase', shq(c.authdb || 'admin')); }
  return 'mongosh ' + args.join(' ');
}
/* Execute a script through --file so statements never get mangled by the REPL. */
async function mongoRun(js, opts = {}) {
  if (!(await binExists('mongosh')) && !(await binExists('mongo'))) bad('MongoDB 未安装（mongosh 缺失，可在「数据库」页点击「一键安装」）');
  const f = `/tmp/cm-mongo-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6)}.js`;
  await run(`echo ${b64(js)} | base64 -d > ${shq(f)} && chmod 644 ${shq(f)}`);
  let r;
  try {
    r = await run(`${mongoClient()} --file ${shq(f)}`, { timeout: opts.timeout || 60000, maxBuffer: 32 * 1024 * 1024 });
  } finally {
    await run(`rm -f ${shq(f)}`).catch(() => {});
  }
  const out = String(r.stdout || '');
  const err = String(r.stderr || '');
  if (r.code !== 0 && !opts.ignoreError) {
    throw Object.assign(new Error((err || out || 'mongosh 执行失败').slice(0, 4000)), { status: 400, output: err + '\n' + out });
  }
  return { out, err };
}
function mongoSafety(db) {
  const n = String(db || '');
  if (!/^[A-Za-z0-9_.\-]{1,120}$/.test(n)) bad('非法的数据库/集合名');
  return n;
}
async function mongoStatus() {
  const { out } = await mongoRun("const s=db.adminCommand({serverStatus:1}); print(EJSON.stringify({version:s.version,uptime:s.uptime,mongoHost:s.host,connections:s.connections,opcounters:s.opcounters,mem:s.mem,db:s.catalogStats},null,0))");
  let s = {};
  try { s = JSON.parse(out.trim()); } catch (_) {}
  const list = await mongoDatabases();
  return {
    engine: 'mongo',
    version: { version: s.version || '', host: s.mongoHost || '', uptime: s.uptime ? s.uptime + 's' : '' },
    stats: { connections: (s.connections || {}).current || '-', max_connections: (s.connections || {}).available || '-', active: '-', databases: list.length, objects: (s.db || {}).objects, dataSize: (s.db || {}).dataSize },
    processes: [], variables: [], capabilities: Object.assign({}, META.mongo.caps),
  };
}
async function mongoDatabases() {
  const { out } = await mongoRun("const r=db.adminCommand({listDatabases:1}); r.databases.forEach(d=>print([d.name,d.sizeOnDisk,d.empty].join('|')))");
  const items = [];
  for (const line of String(out).split('\n')) {
    const p = line.split('|');
    if (p.length >= 3) items.push({ name: p[0], owner: '-', size_bytes: parseInt(p[1], 10) || 0, size: String(p[1]), tables: null, encoding: '-', connections: '-', is_template: p[0] === 'admin' || p[0] === 'local' || p[0] === 'config' });
  }
  return items;
}
async function mongoCollections({ database }) {
  const d = mongoSafety(database);
  const { out } = await mongoRun(`const c=db.getSiblingDB(${JSON.stringify(d)}).getCollectionNames(); c.forEach(n=>{const st=db.getSiblingDB(${JSON.stringify(d)}).getCollection(n).stats();print([n,st.count||0,st.size||0,st.nindexes||0].join('|'))})`);
  const items = [];
  for (const line of String(out).split('\n')) { const p = line.split('|'); if (p.length >= 4) items.push({ schema: d, name: p[0], kind: 'collection', rows: parseInt(p[1], 10) || 0, size: p[2], indexes: parseInt(p[3], 10) || 0 }); }
  return items;
}
/* Build a JS filter expression from the UI input:
   - 24-hex string              -> {_id: ObjectId(...)}
   - JSON object                -> used verbatim (EJSON $oid/$date converted)
   - field=value                -> {"field":"value"}                        */
function mongoFilterExpr(where) {
  const w = String(where == null ? '' : where).trim();
  if (!w) return '{}';
  if (/^[0-9a-fA-F]{24}$/.test(w)) return `{_id: ObjectId(${JSON.stringify(w)})}`;
  const oidExact = /^\{\s*"\$oid"\s*:\s*"([0-9a-fA-F]{24})"\s*\}$/.exec(w);
  if (oidExact) return `{_id: ObjectId(${JSON.stringify(oidExact[1])})}`;
  if (w.startsWith('{')) {
    return w
      .replace(/\{\s*"\$oid"\s*:\s*"([0-9a-fA-F]{24})"\s*\}/g, 'ObjectId("$1")')
      .replace(/\{\s*"\$date"\s*:\s*([0-9]+)\s*\}/g, 'new Date($1)');
  }
  const m = /^([A-Za-z0-9_.]+)\s*=\s*([\s\S]+)$/.exec(w);
  if (m) return JSON.stringify({ [m[1]]: m[2] });
  bad('过滤条件应为 JSON（如 {"a":1}）或 field=value');
}
async function mongoFind({ database, table, limit, offset, where }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  if (!coll) bad('缺少集合名');
  const lim = Math.max(1, Math.min(MAX_ROWS, parseInt(limit, 10) || 50));
  const off = Math.max(0, parseInt(offset, 10) || 0);
  const filter = mongoFilterExpr(where);
  const js = `const c=db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)});` +
    `const docs=c.find(${filter}).skip(${off}).limit(${lim}).toArray();` +
    `for (const x of docs) { if (x && x._id !== undefined) x._id = String(x._id); for (const k in x) { if (x[k] instanceof Date) x[k] = x[k].toISOString(); } }` +
    `print(EJSON.stringify(docs)); print('__TOTAL__'); print(c.countDocuments(${filter}))`;
  const { out } = await mongoRun(js);
  const parts = out.split('__TOTAL__');
  let rows = [];
  try { rows = JSON.parse(parts[0].trim() || '[]'); } catch (_) {}
  const total = parseInt((parts[1] || '').trim(), 10);
  const columns = [];
  for (const r of rows.slice(0, 50)) for (const k of Object.keys(r)) if (!columns.includes(k)) columns.push(k);
  const flat = rows.map((r) => { const o = {}; for (const k of columns) o[k] = r[k] === undefined ? null : (typeof r[k] === 'object' && r[k] !== null ? JSON.stringify(r[k]) : r[k]); return o; });
  return { columns, rows: flat, raw: rows, total: Number.isFinite(total) ? total : null, durationMs: 0 };
}
async function mongoInsert({ database, table, values }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  let doc;
  try { doc = typeof values === 'string' ? JSON.parse(values) : values; } catch (_) { bad('文档必须是合法 JSON'); }
  const { out } = await mongoRun(`const r=db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)}).insertOne(${JSON.stringify(doc)}); print(JSON.stringify({inserted:r.insertedId}))`);
  return ok('已插入 1 个文档\n' + out.trim(), { affected: 1 });
}
async function mongoUpdate({ database, table, pk, values, where }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  const filter = mongoFilterExpr(where || (pk && pk.value !== undefined ? String(pk.value) : ''));
  let update;
  try { update = typeof values === 'string' ? JSON.parse(values) : values; } catch (_) { bad('更新内容必须是合法 JSON'); }
  if (update && typeof update === 'object' && update.$set && typeof update.$set === 'object') delete update.$set._id;
  if (update && typeof update === 'object' && !update.$set && !update.$inc && !update.$push && !update.$pull && !update.$unset) update = { $set: update };
  const { out } = await mongoRun(`const r=db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)}).updateMany(${filter},${JSON.stringify(update)}); print(JSON.stringify({matched:r.matchedCount,modified:r.modifiedCount}))`);
  let j = {}; try { j = JSON.parse(out.trim()); } catch (_) {}
  return ok('更新完成: ' + out.trim(), { affected: j.modified });
}
async function mongoDelete({ database, table, values, where }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  const filter = mongoFilterExpr(where);
  const { out } = await mongoRun(`const r=db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)}).deleteMany(${filter}); print(JSON.stringify({deleted:r.deletedCount}))`);
  let j = {}; try { j = JSON.parse(out.trim()); } catch (_) {}
  return ok('删除完成: ' + out.trim(), { affected: j.deleted });
}
async function mongoDropCollection({ database, table }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  const { out } = await mongoRun(`db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)}).drop(); print('dropped')`);
  return ok('已删除集合 ' + coll);
}
async function mongoCreateDatabase({ database, name }) {
  const d = mongoSafety(database || name);
  if (!d) bad('缺少数据库名');
  await mongoRun(`db.getSiblingDB(${JSON.stringify(d)}).createCollection('_cm_init'); print('created')`);
  return ok('已创建数据库 ' + d + '（MongoDB 首次写入集合时自动创建）');
}
async function mongoDropDatabase({ database, name }) {
  const d = mongoSafety(database || name);
  if (!d) bad('缺少数据库名');
  if (['admin', 'local', 'config'].includes(d)) bad('系统数据库不可删除');
  const { out } = await mongoRun(`db.getSiblingDB(${JSON.stringify(d)}).dropDatabase(); print('dropped')`);
  return ok('已删除数据库 ' + d);
}
async function mongoCreateCollection({ database, table }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  await mongoRun(`db.getSiblingDB(${JSON.stringify(d)}).createCollection(${JSON.stringify(coll)}); print('ok')`);
  return ok('已创建集合 ' + coll);
}
async function mongoShell({ database, command, sql }) {
  const d = database ? mongoSafety(database) : 'test';
  const cmd = String(command || sql || '').trim();
  if (!cmd) bad('命令为空');
  /* IIFE injects `db` bound to the chosen database without redeclaring it */
  const js = `(function (db) {\n${cmd}\n})(db.getSiblingDB(${JSON.stringify(d)}));\n`;
  const { out, err } = await mongoRun(js, { ignoreError: true });
  const combined = (out + '\n' + err).trim();
  if (err && !out.trim()) throw Object.assign(new Error(combined.slice(0, 2000)), { status: 400, output: combined });
  return ok(combined || '(无输出)', { raw: out });
}
async function mongoUsers({ database }) {
  const d = mongoSafety(database || 'admin');
  const { out } = await mongoRun(`const u=db.getSiblingDB(${JSON.stringify(d)}).getUsers(); const arr=Array.isArray(u)?u:((u&&u.users)||[]); arr.forEach(x=>print(JSON.stringify({name:x.user,roles:(x.roles||[]).map(r=>r.role+(r.db?'@'+r.db:''))})))`);
  return { roles: String(out).split('\n').filter(Boolean).map((l) => { try { const j = JSON.parse(l); return { name: j.name, roles: (j.roles || []).join(',') }; } catch (_) { return { name: l, roles: '' }; } }), unsupported: '' };
}
async function mongoUserAction({ action, database, name, password, roles }) {
  const d = mongoSafety(database || 'admin');
  if (!name) bad('缺少用户名');
  const roleArr = String(roles || 'readWrite').split(',').map((r) => r.trim()).filter(Boolean).map((r) => { const [role, rdb] = r.split('@'); return { role, db: rdb || d }; });
  let js;
  if (action === 'create') js = `db.getSiblingDB(${JSON.stringify(d)}).createUser({user:${JSON.stringify(name)},pwd:${JSON.stringify(String(password || ''))},roles:${JSON.stringify(roleArr)}}); print('created')`;
  else if (action === 'drop') js = `db.getSiblingDB(${JSON.stringify(d)}).dropUser(${JSON.stringify(name)}); print('dropped')`;
  else if (action === 'password') js = `db.getSiblingDB(${JSON.stringify(d)}).updateUser(${JSON.stringify(name)},{pwd:${JSON.stringify(String(password || ''))}}); print('updated')`;
  else if (action === 'grant') js = `db.getSiblingDB(${JSON.stringify(d)}).grantRolesToUser(${JSON.stringify(name)},${JSON.stringify(roleArr)}); print('granted')`;
  else bad('未知用户操作: ' + action);
  const { out } = await mongoRun(js, { ignoreError: true });
  return ok(out.trim() || 'OK');
}
async function mongoIndexes({ database, table }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  const { out } = await mongoRun(`db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)}).getIndexes().forEach(i=>print(JSON.stringify(i)))`);
  let rows = [];
  for (const l of String(out).split('\n')) { if (!l.trim()) continue; try { const j = JSON.parse(l); rows.push({ name: j.name, key: JSON.stringify(j.key), unique: !!j.unique, sparse: !!j.sparse }); } catch (_) {} }
  return { indexes: rows, table: coll };
}
async function mongoCreateIndex({ database, table, keys, unique }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  let keyObj;
  try { keyObj = typeof keys === 'string' ? JSON.parse(keys) : keys; } catch (_) { bad('索引键必须是 JSON，如 {"field":1}'); }
  await mongoRun(`db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)}).createIndex(${JSON.stringify(keyObj)}${unique ? ',{unique:true}' : ''}); print('ok')`);
  return ok('索引已创建');
}
async function mongoDropIndex({ database, table, name }) {
  const d = mongoSafety(database); const coll = mongoSafety(table);
  if (!name) bad('缺少索引名');
  await mongoRun(`db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)}).dropIndex(${JSON.stringify(name)}); print('ok')`);
  return ok('索引已删除');
}
async function mongoInsights({ database }) {
  const out = [];
  const d = mongoSafety(database);
  out.push(await sec('数据库统计', () => mongoRun(`const db2=db.getSiblingDB(${JSON.stringify(d)}); const s=db2.stats(); print(EJSON.stringify({collections:s.collections,objects:s.objects,dataSize:s.dataSize,storageSize:s.storageSize,indexes:s.indexes,indexSize:s.indexSize},null,1))`).then((r) => ({ columns: ['stats'], rows: [{ stats: r.out.trim() }] }))));
  out.push(await sec('集合列表', () => mongoCollections({ database: d }).then((rows) => ({ columns: ['name', 'rows', 'size', 'indexes'], rows }))));
  out.push(await sec('当前操作 (currentOp)', () => mongoRun("db.adminCommand({currentOp:1,active:true}).inprog.slice(0,30).forEach(o=>print(JSON.stringify({opid:o.opid,op:o.op,ns:o.ns,secs:o.secs_running,desc:o.desc})))").then((r) => ({ columns: ['op'], rows: String(r.out).split('\n').filter(Boolean).map((l) => ({ op: l })) }))));
  out.push(await sec('副本集状态', () => mongoRun("try{const s=db.adminCommand({replSetGetStatus:1}); print(EJSON.stringify({set:s.set,myState:s.myState,members:(s.members||[]).map(m=>m.name+':'+m.stateStr)}))}catch(e){print('非副本集')}").then((r) => ({ columns: ['repl'], rows: [{ repl: r.out.trim() }] }))));
  return { engine: 'mongo', database: d, sections: out };
}
async function mongoBackup({ database, _mode }) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
  const name = `db-mongo-${database || 'ALL'}-full-${stamp}.dump`.replace(/[^A-Za-z0-9._-]/g, '_');
  const out = DB_BACKUP_DIR + '/' + name;
  const dbArg = database && database !== 'ALL' ? `--db ${shq(database)}` : '';
  const c = (config.get().db || {}).mongo || {};
  const auth = c.user ? `--username ${shq(c.user)} --password ${shq(c.password || '')} --authenticationDatabase ${shq(c.authdb || 'admin')}` : '';
  const r = await run(`command -v mongodump >/dev/null 2>&1 || { echo NO_TOOL; exit 3; }; mkdir -p ${DB_BACKUP_DIR} && mongodump --archive=${shq(out)} --gzip --host ${shq(c.host || '127.0.0.1')} --port ${shq(c.port || '27017')} ${dbArg} ${auth} 2>&1`, { timeout: 7200000 });
  if (/NO_TOOL/.test(r.stdout) || r.code === 3) bad('未安装 mongodump（mongodb-database-tools），请先安装');
  if (r.code !== 0) throw Object.assign(new Error('MongoDB 备份失败: ' + String(r.stdout || r.stderr).slice(0, 1500)), { status: 400 });
  const st = await run(`stat -c %s ${shq(out)} 2>/dev/null || echo 0`);
  const size = parseInt(String(st.stdout).trim(), 10) || 0;
  audit('db', 'backup.create', { engine: 'mongo', database: database || 'ALL', name });
  return ok(`MongoDB 备份完成: ${name} (${(size / 1048576).toFixed(2)} MB)`, { name, size });
}
async function mongoRestore({ file, database }) {
  const p = dbEngineBackupPath(file);
  if (!p) bad('非法备份文件名');
  const c = (config.get().db || {}).mongo || {};
  const auth = c.user ? `--username ${shq(c.user)} --password ${shq(c.password || '')} --authenticationDatabase ${shq(c.authdb || 'admin')}` : '';
  const dbArg = database ? `--nsInclude ${shq(database + '.*')}` : '';
  const r = await run(`mongorestore --archive=${shq(p)} --gzip --drop --host ${shq(c.host || '127.0.0.1')} --port ${shq(c.port || '27017')} ${auth} ${dbArg} 2>&1`, { timeout: 7200000 });
  const out = String(r.stdout || '') + String(r.stderr || '');
  if (r.code !== 0) throw Object.assign(new Error('MongoDB 恢复失败:\n' + out.slice(0, 1500)), { status: 400, output: out });
  return ok('MongoDB 恢复完成:\n' + out.slice(0, 1000));
}
async function mongoMaintenance({ action, database }) {
  const d = mongoSafety(database || 'admin');
  let js;
  if (action === 'compact') js = `db.getSiblingDB(${JSON.stringify(d)}).runCommand({compact: db.getSiblingDB(${JSON.stringify(d)}).getCollectionNames()[0]||'x'}); print('ok')`;
  else if (action === 'validate') js = `const c=db.getSiblingDB(${JSON.stringify(d)}).getCollectionNames(); c.forEach(n=>print(n+': '+EJSON.stringify(db.getSiblingDB(${JSON.stringify(d)}).getCollection(n).validate())))`;
  else if (action === 'stats') js = `print(EJSON.stringify(db.getSiblingDB(${JSON.stringify(d)}).stats(),null,1))`;
  else if (action === 'reindex') js = `db.getSiblingDB(${JSON.stringify(d)}).getCollectionNames().forEach(n=>db.getSiblingDB(${JSON.stringify(d)}).getCollection(n).reIndex()); print('reindexed')`;
  else bad('未知 MongoDB 维护操作: ' + action);
  const { out } = await mongoRun(js);
  return ok(out.trim() || 'OK');
}
async function mongoStatements({ database }) { return { available: false, rows: [], columns: [], hint: 'MongoDB 通过 db.setProfilingLevel(1) 开启慢查询剖析，可在控制台执行后用 system.profile 查询' }; }

/* ================= engine availability / install ================= */
/* ---- systemd service control (start/stop is always explicit) ---- */
const SERVICE_CANDIDATES = { postgres: ['postgresql'], mysql: ['mysql', 'mariadb'], redis: ['redis-server'], mongo: ['mongod'], sqlite: [], db2: [] };
const SERVICE_LABEL = { postgres: 'PostgreSQL', mysql: 'MySQL', sqlite: 'SQLite', db2: 'IBM Db2', redis: 'Redis', mongo: 'MongoDB' };
async function resolveService(engine) {
  for (const c of (SERVICE_CANDIDATES[engine] || [])) {
    try { const r = await run(`systemctl cat ${shq(c)} >/dev/null 2>&1 && echo yes || echo no`, { timeout: 10000 }); if (/yes/.test(r.stdout)) return c; } catch (_) {}
  }
  return null;
}
async function engineService({ engine, action }) {
  const e = String(engine || '');
  const label = SERVICE_LABEL[e];
  if (!label) bad('未知引擎: ' + engine);
  const allowed = ['start', 'stop', 'restart', 'enable', 'disable', 'status'];
  if (allowed.indexOf(String(action)) < 0) bad('未知服务操作: ' + action);
  const svc = await resolveService(e);
  if (!svc) {
    if (e === 'sqlite') bad('SQLite 是文件型数据库，没有服务可启动（选择数据库文件即可使用）');
    if (e === 'db2') bad('Db2 使用 db2start/db2stop 实例管理，暂未通过 systemd 托管');
    bad(label + ' 没有可用的 systemd 服务单元');
  }
  if (action !== 'status') {
    const r = await run(`systemctl ${action} ${shq(svc)} 2>&1; sleep 1`, { timeout: 90000 });
    if (r.code !== 0 && /not loaded|Failed to|error/i.test(String(r.stdout) + String(r.stderr))) {
      throw Object.assign(new Error((String(r.stdout) + String(r.stderr)).trim().slice(0, 800)), { status: 400 });
    }
  }
  const st = await run(`echo -n "active="; systemctl is-active ${shq(svc)} 2>/dev/null || true; echo; echo -n "enabled="; systemctl is-enabled ${shq(svc)} 2>/dev/null || true`, { timeout: 15000 });
  const m = /active=(\S*).*(?:\n|\r)*enabled=(\S*)/m.exec(String(st.stdout || ''));
  const active = (m && m[1]) || String(st.stdout || '').split('\n')[0].replace('active=', '').trim();
  const enabled = (m && m[2]) || '';
  audit('db', 'engine.service.' + action, { engine: e, service: svc, active });
  return ok(`${label} (${svc}) 已执行 ${action}：状态 ${active || '未知'}${enabled ? '，开机 ' + enabled : ''}`, { engine: e, service: svc, action, active, enabled });
}

async function enginesOverview() {
  const [sqliteBin, db2Inst, redisBin, mongoBin] = await Promise.all([
    cmdExists('sqlite3'), (async () => db2InstanceExists(db2Instance()))(), cmdExists('redis-cli'), cmdExists('mongosh').then((x) => x ? x : cmdExists('mongo')),
  ]);
  const [redisActive, mongoActive] = await Promise.all([isActive('redis-server'), isActive('mongod')]);
  return [
    { id: 'sqlite', name: 'SQLite', kind: 'sql', installed: sqliteBin, active: sqliteBin, service: null, packages: ['sqlite3'] },
    { id: 'db2', name: 'IBM Db2', kind: 'sql', installed: db2Inst, active: db2Inst, service: null, packages: [], needsRepo: 'ibm' },
    { id: 'redis', name: 'Redis', kind: 'kv', installed: redisBin, active: redisActive, service: 'redis-server', packages: ['redis-server'] },
    { id: 'mongo', name: 'MongoDB', kind: 'doc', installed: mongoBin, active: mongoActive, service: 'mongod', packages: ['mongodb-org'], needsRepo: 'mongo' },
  ];
}

/* Install one of the extra engines. Only the engines declared in META may be
   requested; package names are never taken from the client verbatim.
   Installed services are intentionally NOT started — PostgreSQL (already
   running) and SQLite (file based) are the only engines that run by default. */
async function installEngine({ engine }) {
  const m = META[String(engine || '')];
  if (!m) bad('不支持的安装目标: ' + engine);
  if (m.id === 'db2') return installDb2();
  if (m.id === 'mongo') return installMongo();
  const pkgs = m.packages.map(shq).join(' ');
  const out = await run(`apt-get update -qq 2>&1 | tail -2; DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y ${pkgs} 2>&1 | tail -25; echo '=== 校验 ==='; ${m.bin ? 'command -v ' + m.bin : 'true'} 2>/dev/null || true; [ -n ${shq(m.service || '')} ] && systemctl disable ${shq(m.service)} >/dev/null 2>&1; systemctl is-active ${m.service || 'x'} 2>/dev/null || true`, { timeout: 1800000, maxBuffer: 16 * 1024 * 1024 });
  audit('db', 'engine.install', { engine });
  return ok('安装 ' + m.name + ' 完成（默认未启动，需要时在引擎栏点击「启动」）：\n' + String(out.stdout || '').slice(-4000));
}
async function installMongo() {
  /* 8.0 refuses to start on kernels >= 6.19 (SERVER-121912); install the current
     stable line instead. The signing key is only published at server-8.0.asc. */
  const VER = '8.3';
  const KEY = 'https://pgp.mongodb.com/server-8.0.asc';
  const KEYRING = '/usr/share/keyrings/mongodb-server.gpg';
  const LIST = '/etc/apt/sources.list.d/mongodb-org.list';
  const steps = [
    `if command -v mongosh >/dev/null 2>&1 && grep -q 'mongodb-org/${VER}' ${LIST} 2>/dev/null; then echo 'MongoDB 已安装'; exit 0; fi`,
    `command -v curl >/dev/null 2>&1 || apt-get install -y curl gnupg`,
    `install -d -m 0755 /usr/share/keyrings`,
    `curl -fsSL ${KEY} -o /tmp/mongodb-asc && gpg --dearmor -o ${KEYRING} /tmp/mongodb-asc && rm -f /tmp/mongodb-asc`,
    `echo "deb [signed-by=${KEYRING}] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/${VER} multiverse" > ${LIST}`,
    `rm -f /etc/apt/sources.list.d/mongodb-org-8.0.list /etc/apt/sources.list.d/mongodb-org-8.3.list 2>/dev/null || true`,
    `rm -f /usr/share/keyrings/mongodb-server-8.3.gpg /usr/share/keyrings/mongodb-server-8.0.gpg 2>/dev/null || true`,
    `apt-get update -qq 2>&1 | tail -5`,
    `DEBIAN_FRONTEND=noninteractive apt-get install -y --allow-change-held-packages mongodb-org 2>&1 | tail -30`,
    /* Mongo's unit sets GLIBC_TUNABLES=glibc.pthread.rseq=0; on kernels >= 6.19
       that makes mongod refuse to start (SERVER-121912). Unsetting it lets
       mongod start normally, so ship a drop-in as part of the install. */
    `mkdir -p /etc/systemd/system/mongod.service.d`,
    `printf '[Service]\\nUnsetEnvironment=GLIBC_TUNABLES\\n' > /etc/systemd/system/mongod.service.d/10-kernel-compat.conf`,
    `systemctl daemon-reload`,
    `systemctl disable mongod >/dev/null 2>&1`,
    `chown -R mongodb:mongodb /var/log/mongodb /var/lib/mongodb 2>/dev/null || true`,
    `echo '=== 状态 ==='; systemctl is-enabled mongod 2>/dev/null || true; systemctl is-active mongod 2>/dev/null || true`,
  ].join('\n');
  const r = await run(steps, { timeout: 2400000, maxBuffer: 16 * 1024 * 1024 });
  audit('db', 'engine.install', { engine: 'mongo' });
  return ok('MongoDB 已安装（默认未启动，需要时在引擎栏点击「启动」）：\n' + String(r.stdout || '').slice(-4000));
}
/* IBM Db2 (LUW). Unlike the other engines there is no guaranteed public apt
   repo for every Ubuntu release, so probe first: install directly when a repo
   exists, otherwise return a copy-pasteable guide instead of failing silently. */
async function installDb2({ installer } = {}) {
  const rel = await run(`. /etc/os-release; echo "$VERSION_CODENAME"`, { timeout: 8000 });
  const codename = String(rel.stdout || '').trim() || 'jammy';
  const roots = [
    `https://public.dhe.ibm.com/software/ibmdb2/ubuntu/prod`,
    `https://public.dhe.ibm.com/software/data/db2/linux/amd64/packages`,
  ];
  const probeCmd = roots.map((r) => `printf '%s -> ' ${shq(r)}; curl -s -o /dev/null -m 20 -w '%{http_code}\\n' ${shq(r + '/dists/' + codename + '/Release')}`).join('\n');
  const probe = await run(probeCmd, { timeout: 90000, maxBuffer: 8 * 1024 * 1024 });
  const out = String(probe.stdout || '');
  const okRoot = roots.find((r) => new RegExp(r.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ' -> 200').test(out));

  if (!okRoot) {
    const guide = [
      '未找到可用的 IBM Db2 官方 apt 源（探测结果：）', out.trim(),
      '',
      'IBM Db2 Community Edition 需要从 IBM 官网下载安装包（需接受许可），步骤如下：',
      '  1) 访问 https://www.ibm.com/products/db2-database 下载 Linux x86_64 安装包（v11.5.x，约 2GB）',
      '  2) 上传到服务器并解压：  tar -xf v11.5_linuxx64_client.tar.gz',
      '  3) 以 root 运行：        ./db2_install        （安装服务端）',
      '  4) 创建实例：            /opt/ibm/db2/V11.5/instance/db2icrt db2inst1',
      '  5) 创建 SAMPLE 库：      su - db2inst1 -c "db2sampl"',
      '  6) 完成后在「面板设置 → IBM Db2 连接」中填写实例用户名与数据库名即可使用本页全部功能',
      '',
      '提示：也可以用官方容器替代（docker run -d --name db2 -p 50000:50000 -e DB2INSTANCE_TO_SET=db2inst1 -e DB2_PWD=... ibmcom/db2:11.5.9.0），',
      '     容器内安装后，把面板的实例/数据库配置指向对应端口即可。',
    ].join('\n');
    audit('db', 'engine.install.db2', { result: 'no-public-repo' });
    return ok(guide, { installed: false, reason: 'no-public-repo', probe: out.trim() });
  }

  const steps = [
    `command -v db2 >/dev/null 2>&1 && { echo 'Db2 已安装'; exit 0; }`,
    `command -v curl >/dev/null 2>&1 || apt-get install -y curl gnupg`,
    `install -d -m 0755 /usr/share/keyrings`,
    `curl -fsSL ${okRoot}/GPG-KEY-IBM 2>/dev/null | gpg --dearmor -o /usr/share/keyrings/ibm_db2.gpg || true`,
    `printf "deb [signed-by=/usr/share/keyrings/ibm_db2.gpg] %s %s main\\n" ${shq(okRoot)} ${shq(codename)} > /etc/apt/sources.list.d/ibm_db2.list`,
    `apt-get update -qq 2>&1 | tail -5`,
    `DEBIAN_FRONTEND=noninteractive apt-get install -y ibm-db2 2>&1 | tail -30`,
    `echo '=== 校验 ==='; command -v db2 || ls -d /opt/ibm/db2/* 2>/dev/null || echo '未检测到 db2 命令，请按下方说明完成实例创建'`,
  ].join('\n');
  const r = await run(steps, { timeout: 3600000, maxBuffer: 32 * 1024 * 1024 });
  audit('db', 'engine.install', { engine: 'db2' });
  return ok('IBM Db2 安装流程已执行：\n' + (String(r.stdout || '') + String(r.stderr || '')).slice(-5000) +
    '\n\n后续：创建实例后在「面板设置 → IBM Db2 连接」填写实例用户与数据库名。', { installed: true });
}
async function engineLog(bin, service) {
  return {};
}

/* ======================================================================
 *  public API (mirrors dbadmin.js)
 * ====================================================================== */
async function getEngineStatus(engine) {
  engine = String(engine);
  if (engine === 'sqlite' || engine === 'db2') return sqlStatus(engine);
  if (engine === 'redis') return redisStatus();
  if (engine === 'mongo') return mongoStatus();
  bad('未知引擎: ' + engine);
}

async function listDatabases(engine) {
  engine = String(engine);
  if (engine === 'sqlite' || engine === 'db2') return sqlListDatabases(engine);
  if (engine === 'redis') return redisDatabases();
  if (engine === 'mongo') return mongoDatabases();
  bad('未知引擎: ' + engine);
}
async function createDatabase(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlCreateDatabase(e, body);
  if (e === 'mongo') return mongoCreateDatabase(body);
  if (e === 'redis') bad('Redis 的库（db0-db15）无需创建');
  bad('未知引擎: ' + e);
}
async function dropDatabase(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlDropDatabase(e, body);
  if (e === 'mongo') return mongoDropDatabase(body);
  bad('Redis 的库不可删除');
}
async function renameDatabase(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlRenameDatabase(e, body);
  bad('该引擎不支持重命名数据库');
}
async function maintenance(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlMaintenance(e, body);
  if (e === 'redis') return redisMaintenance(body);
  if (e === 'mongo') return mongoMaintenance(body);
  bad('未知引擎: ' + e);
}

async function listTables(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlListTables(e, body.database);
  if (e === 'mongo') return mongoCollections(body);
  if (e === 'redis') bad('Redis 没有表；请使用「键空间」浏览');
  bad('未知引擎: ' + e);
}
async function getTableSchema(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlTableSchema(e, body);
  if (e === 'mongo') { const idx = await mongoIndexes(body); return { columns: [], indexes: idx.indexes, primaryKey: ['_id'], ddl: '', engine: 'mongo', table: body.table }; }
  bad('该引擎不支持表结构');
}
async function getTableData(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlGetTableData(e, body);
  if (e === 'mongo') return mongoFind(body);
  bad('该引擎不支持表数据浏览');
}
async function insertRow(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlInsertRow(e, body);
  if (e === 'mongo') return mongoInsert(body);
  bad('该引擎不支持插入');
}
async function updateRow(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlUpdateRow(e, body);
  if (e === 'mongo') return mongoUpdate(body);
  bad('该引擎不支持更新');
}
async function deleteRow(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlDeleteRow(e, body);
  if (e === 'mongo') return mongoDelete(body);
  bad('该引擎不支持删除');
}
async function tableAction(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlTableAction(e, body);
  if (e === 'mongo') { if (body.action === 'drop') return mongoDropCollection(body); bad('MongoDB 仅支持删除集合'); }
  bad('该引擎不支持表操作');
}
async function listRoles(engine) {
  engine = String(engine);
  if (engine === 'sqlite' || engine === 'db2') return sqlListRoles(engine);
  if (engine === 'redis') return redisAclList();
  if (engine === 'mongo') return mongoUsers({ database: 'admin' });
  bad('未知引擎: ' + engine);
}
async function roleAction(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlRoleAction(e, body);
  if (e === 'redis') return redisAclAction(body);
  if (e === 'mongo') return mongoUserAction(body);
  bad('未知引擎: ' + e);
}
async function killProcess(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlKillProcess(e, body);
  if (e === 'redis') { const r = await redisCmd(undefined, redisBuildCmd(['CLIENT', 'KILL', 'ID', String(body.pid)])); return ok(r.stdout.trim() || 'OK'); }
  bad('该引擎不支持终止会话');
}
async function sqlConsole(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return relConsole(e, body);
  if (e === 'redis') return redisConsole(body);
  if (e === 'mongo') return mongoShell(body);
  bad('未知引擎: ' + e);
}

async function listDbBackups(body) {
  return sqlListDbBackups(String((body && body.engine) || ''));
}
async function createDbBackup(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlCreateDbBackup(e, body);
  if (e === 'redis') return redisBackup(body);
  if (e === 'mongo') return mongoBackup(body);
  bad('未知引擎: ' + e);
}
async function restoreDbBackup(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlRestoreDbBackup(e, body);
  if (e === 'redis') return redisRestore(body);
  if (e === 'mongo') return mongoRestore(body);
  bad('未知引擎: ' + e);
}
async function deleteDbBackup(body) { return sqlDeleteDbBackup(body); }

async function insights(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlInsights(e, body.database);
  if (e === 'mongo') return mongoInsights(body);
  if (e === 'redis') { const r = await redisExtraMonitor(); return { engine: 'redis', database: body.database, sections: r.sections }; }
  bad('未知引擎: ' + e);
}
async function listObjects(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlListObjects(e, body.database);
  if (e === 'mongo') { const idx = body.table ? await mongoIndexes(body) : { indexes: [] }; return { engine: 'mongo', database: body.database, sections: [{ title: '索引', columns: ['name', 'key', 'unique', 'sparse'], rows: idx.indexes }] }; }
  if (e === 'redis') { const r = await redisInfo(); return { engine: 'redis', database: body.database, sections: r.sections }; }
  bad('未知引擎: ' + e);
}
async function statements(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlStatements(e, body.database);
  if (e === 'mongo') return mongoStatements(body);
  if (e === 'redis') { const r = await redisSlowlog({ limit: 100 }); return { available: r.sections[0].rows.length > 0, rows: r.sections[0].rows, columns: r.sections[0].columns, section: r.sections[0], hint: '' }; }
  bad('未知引擎: ' + e);
}
async function schemaAction(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlSchemaAction(e, body);
  if (e === 'mongo') {
    if (body.action === 'table-copy') bad('MongoDB 不支持复制集合到此处');
    bad('MongoDB 请使用控制台执行 DDL');
  }
  bad('该引擎不支持 DDL');
}
async function databaseAlter(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') { if (body.action === 'rename') return sqlRenameDatabase(e, body); bad('该引擎不支持此数据库设置: ' + body.action); }
  bad('该引擎不支持数据库设置');
}
async function exportData(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlExportData(e, body);
  bad('该引擎不支持 SQL 导出（Redis 请用备份，MongoDB 请用备份）');
}
async function countRows(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlCountRows(e, body);
  if (e === 'mongo') { const r = await mongoFind({ database: body.database, table: body.table, limit: 1, where: body.where }); return ok(`精确行数: ${r.total}`, { total: r.total }); }
  bad('该引擎不支持精确计数');
}
async function importCsv(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlImportCsv(e, body);
  bad('Redis / MongoDB 不支持 CSV 导入（请使用备份恢复）');
}
async function cleanupBackups() { return ok('请使用 Redis/MongoDB 备份列表中的删除按钮逐个清理'); }
async function createExtension(body) { bad('扩展管理仅 PostgreSQL 支持'); }

async function listSchemas(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlListSchemas(e, body.database);
  return { rows: [] };
}
async function schemaManage(body) {
  const e = String(body.engine);
  if (e === 'db2') {
    const n = String(body.name || '');
    if (!/^[A-Za-z_][\w$]{0,30}$/.test(n)) bad('无效模式名');
    let sql;
    if (body.action === 'create') sql = `CREATE SCHEMA ${relQid('db2', n)};`;
    else if (body.action === 'drop') sql = `DROP SCHEMA ${relQid('db2', n)} RESTRICT;`;
    else bad('未知模式操作: ' + body.action);
    const r = await relRun('db2', body.database, sql, { timeout: 120000 });
    return ok(sql);
  }
  bad('模式管理仅 Db2 / PostgreSQL 支持');
}
async function tableStorage(body) {
  const e = String(body.engine);
  if (e === 'sqlite') {
    const p = sqlitePath(body.database);
    const r = await sqliteRun(p, `SELECT ${relLit('sqlite', body.table)} AS name, (SELECT count(*) FROM ${relQid('sqlite', body.table)}) AS rows;`).catch(() => ({ rows: [] }));
    return { size: (r.rows[0] || {}), activity: { note: 'SQLite 无表级活动统计' }, indexes: [] };
  }
  if (e === 'db2') {
    const db = db2Database(body.database);
    const r = await db2Run(db, `SELECT CARD AS rows, NPAGES AS pages, FPAGES AS full_pages, OVERFLOW AS overflow FROM SYSCAT.TABLES WHERE TABSCHEMA=CURRENT SCHEMA AND TABNAME=${relLit('db2', String(body.table).toUpperCase())};`).catch(() => ({ rows: [] }));
    return { size: (r.rows[0] || {}), activity: { note: 'Db2 表级统计需 RUNSTATS' }, indexes: [] };
  }
  bad('该引擎不支持表存储分析');
}
async function missingIndexes(body) { return { rows: [], note: '该引擎不提供自动缺失索引分析（可参考性能洞察）' }; }
async function searchValue(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlSearchValue(e, body);
  if (e === 'mongo') {
    const d = mongoSafety(body.database); const v = String(body.value || '').replace(/'/g, "\\'");
    const colls = body.table ? [body.table] : (await mongoCollections({ database: d })).map((c) => c.name);
    const hits = [];
    for (const c of colls.slice(0, 20)) {
      try {
        const { out } = await mongoRun(`const docs=db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(c)}).find({$where:function(){for(const k in this){if(String(this[k]).indexOf(${JSON.stringify(String(body.value || ''))})>=0)return true}return false}}).limit(20).toArray(); print(EJSON.stringify(docs))`);
        const rows = JSON.parse(out.trim() || '[]');
        if (rows.length) hits.push({ table: c, matched: rows.length, columns: [], rows });
      } catch (_) {}
    }
    return { value: v, scanned: colls.length, hits, errors: [], skipped: [], truncated: false };
  }
  bad('该引擎不支持数据搜索');
}
async function explainTree(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlExplainTree(e, body);
  bad('该引擎不支持执行计划');
}
async function bulkTableAction(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlBulkTableAction(e, body);
  bad('该引擎不支持批量表操作');
}
async function copyTableToDb(body) {
  const e = String(body.engine);
  if (e === 'mongo') {
    const d = mongoSafety(body.database); const coll = mongoSafety(body.table); const target = mongoSafety(body.targetDb);
    await mongoRun(`db.getSiblingDB(${JSON.stringify(d)}).getCollection(${JSON.stringify(coll)}).aggregate([{$match:{}},{$out:{db:${JSON.stringify(target)},coll:${JSON.stringify(body.newName || coll)}}}])`);
    return ok(`已复制集合 ${coll} -> ${target}.${body.newName || coll}`);
  }
  bad('跨库复制仅支持 PostgreSQL / MySQL / MongoDB');
}
async function tableBackup(body) {
  const e = String(body.engine);
  if (e === 'sqlite') {
    const p = sqlitePath(body.database);
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
    const name = `db-sqlite-${String(body.table).replace(/[^A-Za-z0-9_.-]/g, '_')}-table-${stamp}.sql.gz`;
    const out = `${DB_BACKUP_DIR}/${name}`;
    const r = await run(`set -o pipefail; mkdir -p ${DB_BACKUP_DIR} && echo ${b64(`.dump ` + relQid('sqlite', body.table))} | base64 -d | sqlite3 ${shq(p)} | gzip -1 > ${shq(out)} && gzip -t ${shq(out)}`, { timeout: 1800000 });
    if (r.code !== 0) throw Object.assign(new Error('单表备份失败: ' + String(r.stderr || r.stdout).slice(0, 800)), { status: 400 });
    const st = await run(`stat -c %s ${shq(out)} || echo 0`);
    return ok('单表备份完成: ' + name, { name, size: parseInt(String(st.stdout).trim(), 10) || 0 });
  }
  bad('单表备份仅支持 PostgreSQL / MySQL / SQLite');
}
async function sequenceAction(body) {
  const e = String(body.engine);
  if (e === 'db2') {
    const p = body.params || {}; const name = String(p.name || '');
    if (!/^[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)?$/.test(name)) bad('无效序列名');
    let sql;
    if (body.action === 'create') sql = `CREATE SEQUENCE ${relQid('db2', name)} START WITH ${parseInt(p.start, 10) || 1} INCREMENT BY ${parseInt(p.increment, 10) || 1};`;
    else if (body.action === 'drop') sql = `DROP SEQUENCE ${relQid('db2', name)};`;
    else if (body.action === 'nextval') sql = `VALUES NEXT VALUE FOR ${relQid('db2', name)};`;
    else if (body.action === 'alter') sql = `ALTER SEQUENCE ${relQid('db2', name)} RESTART WITH ${parseInt(p.restart, 10) || 1};`;
    else bad('未知序列操作: ' + body.action);
    const r = await relRun('db2', body.database, sql, { timeout: 120000 });
    return ok(sql + '\n' + (r.raw || r.output || ''));
  }
  bad('序列管理仅支持 PostgreSQL / Db2');
}
async function objectManage(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') {
    const p = body.params || {};
    const name = String(p.name || '');
    if (!/^[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)?$/.test(name)) bad('无效对象名');
    let sql = null;
    if (body.kind === 'view') {
      if (body.action === 'create') { if (!p.sql) bad('缺少 SELECT 语句'); sql = `CREATE VIEW ${relQid(e, name)} AS ${String(p.sql).replace(/;\s*$/, '')};`; }
      else if (body.action === 'drop') sql = `DROP VIEW ${relQid(e, name)};`;
    } else if (body.kind === 'trigger' && body.action === 'drop') {
      sql = `DROP TRIGGER ${relQid(e, name)};`;
    }
    if (!sql) bad('该引擎暂不支持此对象操作');
    const r = await relRun(e, body.database, sql, { timeout: 120000 });
    return ok(sql + '\n' + (r.raw || r.output || ''));
  }
  bad('对象管理不支持该引擎');
}
async function sessionManage(body) {
  const e = String(body.engine);
  if (e === 'redis') {
    const r = await redisCmd(undefined, redisBuildCmd(['CLIENT', 'LIST']));
    return ok(String(r.stdout).split('\n').filter(Boolean).length + ' 个客户端连接');
  }
  bad('会话批量清理暂仅支持 PostgreSQL / MySQL');
}
async function extraMonitor(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlExtraMonitor(e, body.database);
  if (e === 'redis') return redisExtraMonitor();
  if (e === 'mongo') return mongoInsights(body);
  bad('未知引擎: ' + e);
}
async function maintenancePlan(body) {
  const e = String(body.engine);
  if (e === 'sqlite' || e === 'db2') return sqlMaintenancePlan(e, body);
  bad('维护计划暂支持 PostgreSQL / MySQL / SQLite / Db2');
}

/* ================= extra (redis/mongo) endpoints ================= */
async function kvKeys(body) { return redisKeys(body); }
async function kvValue(body) { return redisKeyValue(body); }
async function kvSet(body) { return redisSetValue(body); }
async function kvDelete(body) { return redisDeleteKeys(body); }
async function kvConfig(body) { if (body && body.action === 'set') return redisConfigSet(body); return redisConfigList(); }
async function docFind(body) { return mongoFind(body); }
async function docInsert(body) { return mongoInsert(body); }
async function docUpdate(body) { return mongoUpdate(body); }
async function docDelete(body) { return mongoDelete(body); }
async function docCreateCollection(body) { return mongoCreateCollection(body); }
async function docIndexes(body) { return mongoIndexes(body); }
async function docCreateIndex(body) { return mongoCreateIndex(body); }
async function docDropIndex(body) { return mongoDropIndex(body); }
async function docShell(body) { return mongoShell(body); }
async function engines() { return enginesOverview(); }

module.exports = {
  ENGINES, META, engines, installEngine, enginesOverview, engineService, resolveService,
  getEngineStatus, listDatabases, createDatabase, dropDatabase, renameDatabase, maintenance,
  listTables, getTableSchema, getTableData, insertRow, updateRow, deleteRow, tableAction,
  listRoles, roleAction, killProcess, sqlConsole,
  listDbBackups, createDbBackup, restoreDbBackup, deleteDbBackup,
  insights, listObjects, statements, schemaAction, databaseAlter, exportData, countRows, importCsv,
  cleanupBackups, createExtension,
  listSchemas, schemaManage, tableStorage, missingIndexes, searchValue, explainTree,
  bulkTableAction, copyTableToDb, tableBackup, sequenceAction, objectManage, sessionManage,
  extraMonitor, maintenancePlan,
  /* dedicated redis / mongo endpoints */
  kvKeys, kvValue, kvSet, kvDelete, kvConfig,
  docFind, docInsert, docUpdate, docDelete, docCreateCollection, docIndexes, docCreateIndex, docDropIndex, docShell,
  /* helpers reused by the download route */
  dbEngineBackupPath,
};
