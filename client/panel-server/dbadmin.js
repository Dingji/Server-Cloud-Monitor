'use strict';
/*
 * Database administration: PostgreSQL & MySQL/MariaDB.
 * Full DDL/DML management, data browsing, roles, maintenance, backup/restore.
 * SQL is always passed to the client via base64 stdin to avoid quoting issues;
 * results are parsed from psql --csv / mysql --batch TSV.
 */
const path = require('path');
const fs = require('fs');
const config = require('./config');
const { getAgent } = require('./agent');
const { audit } = require('./util');

const { DB_BACKUP_DIR } = require('./paths');
const UPLOAD_DIR = DB_BACKUP_DIR + '/uploads';
const MAX_ROWS = 500;

/* ================= helpers ================= */
function bad(msg) { const e = new Error(msg); e.status = 400; throw e; }
function ok(output, extra) { return Object.assign({ ok: true, output: output == null ? '' : String(output) }, extra || {}); }

/* shell single-quote escaping */
function shq(s) { return "'" + String(s == null ? '' : s).replace(/'/g, "'\\''") + "'"; }
function b64(s) { return Buffer.from(String(s), 'utf8').toString('base64'); }

/* SQL identifier quoting */
function qid(engine, name) {
  if (typeof name !== 'string' || !name.trim() || name.length > 200) bad('无效标识符');
  return String(name).split('.').map((p) => {
    if (!/^[^"\0\r\n]{1,64}$/.test(p)) bad('非法标识符: ' + p);
    return engine === 'mysql' ? '`' + p.replace(/`/g, '``') + '`' : '"' + p.replace(/"/g, '""') + '"';
  }).join('.');
}
/* SQL literal */
function lit(engine, v) {
  if (v === null || v === undefined) return 'NULL';
  if (typeof v === 'number') return Number.isFinite(v) ? String(v) : 'NULL';
  if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
  const s = String(v);
  if (engine === 'mysql') return `CONVERT(UNHEX('${Buffer.from(s, 'utf8').toString('hex')}') USING utf8mb4)`;
  return "'" + s.replace(/'/g, "''") + "'";
}

/* ---- client command builders ---- */
function pgClient(database, quiet, noCsv) {
  const c = (config.get().db || {}).postgres || {};
  const flags = ['-X'];
  if (quiet !== false) flags.push('-q');
  if (noCsv) flags.push('-A', '-t'); else flags.push('--csv');
  flags.push('-v', 'ON_ERROR_STOP=1');
  if (database) flags.push('-d', shq(database));
  flags.push('-f', '-');
  let inner, wrap = false;
  if (c.host) {
    inner = `psql -U ${shq(c.user || 'postgres')} -h ${shq(c.host)}${c.port ? ' -p ' + shq(c.port) : ''} ${flags.join(' ')}`;
    if (c.password) inner = `PGPASSWORD=${shq(c.password)} ` + inner;
  } else {
    inner = `psql ${flags.join(' ')}`;
    wrap = true;
  }
  return wrap ? `su - postgres -c ${shq(inner)}` : inner;
}
function myClient(database, noHeader) {
  const c = (config.get().db || {}).mysql || {};
  const auth = [];
  if (c.host) auth.push('-h', shq(c.host));
  if (c.port) auth.push('-P', shq(c.port));
  auth.push('-u', shq(c.user || 'root'));
  if (c.password) auth.push('-p' + shq(c.password));
  const flags = ['--batch', '--default-character-set=utf8mb4'];
  if (noHeader) flags.push('--skip-column-names');
  if (database) flags.push('-D', shq(database));
  return `mysql ${auth.join(' ')} ${flags.join(' ')}`;
}

/* ---- 只读单语句校验（用于 EXPLAIN 等"只读"入口） ----
 * 1) 词法剥离注释与字符串/标识符字面量
 * 2) 剥离后若仍含分号 => 多条语句（psql 会逐条提交，等价于任意 SQL 执行）
 * 3) 扫描 token，禁止写操作关键词（拦截 WITH ... DELETE/INSERT/UPDATE 这类数据修改 CTE）
 */
const WRITE_TOKENS = new Set(['DELETE', 'INSERT', 'UPDATE', 'TRUNCATE', 'CREATE', 'DROP', 'ALTER', 'GRANT', 'REVOKE',
  'COPY', 'VACUUM', 'ANALYZE', 'REINDEX', 'CLUSTER', 'DO', 'CALL', 'LOCK', 'SET', 'RESET', 'REFRESH', 'COMMENT',
  'SECURITY', 'IMPORT', 'BEGIN', 'COMMIT', 'ROLLBACK', 'SAVEPOINT', 'PREPARE', 'EXECUTE', 'DEALLOCATE', 'LISTEN',
  'NOTIFY', 'UNLISTEN', 'DECLARE', 'FETCH', 'MOVE', 'CLOSE', 'REASSIGN', 'CHECKPOINT', 'LOAD', 'DISCARD', 'EXPLAIN']);

function stripLiterals(sql) {
  const s = String(sql);
  let out = '';
  let i = 0;
  while (i < s.length) {
    const ch = s[i];
    if (ch === '-' && s[i + 1] === '-') { while (i < s.length && s[i] !== '\n') i++; continue; }
    /* 块注释一律按"遇到第一个结束符即结束"处理（不跟随 PG 的嵌套语义）：
       MySQL 不支持嵌套注释，若按嵌套剥离会与 MySQL 的解析结果不一致，
       从而把 DELETE 等写操作藏在校验器看不见的地方。宁可误报，不可漏报。 */
    if (ch === '/' && s[i + 1] === '*') {
      i += 2;
      const end = s.indexOf('*/', i);
      i = end >= 0 ? end + 2 : s.length;
      out += ' '; continue;
    }
    if (ch === "'" || ch === '"') {
      const q = ch; i++;
      while (i < s.length) {
        if (s[i] === '\\') { i += 2; continue; }
        if (s[i] === q) { if (s[i + 1] === q) { i += 2; continue; } i++; break; }
        i++;
      }
      out += ' '; continue;
    }
    if (ch === '$') {
      const m = /^\$[A-Za-z_0-9]*\$/.exec(s.slice(i));
      if (m) { const tag = m[0]; const end = s.indexOf(tag, i + tag.length); i = end >= 0 ? end + tag.length : s.length; out += ' '; continue; }
    }
    out += ch; i++;
  }
  return out;
}

function assertReadOnlySingleStatement(sql, label) {
  const raw = String(sql == null ? '' : sql).trim().replace(/;+\s*$/, '');
  if (!raw) bad((label || 'SQL') + ' 为空');
  const code = stripLiterals(raw);
  if (code.indexOf(';') >= 0) {
    bad((label || 'SQL') + ' 只允许单条语句：检测到分号分隔的多条 SQL（分号之后的内容会被数据库逐条执行）');
  }
  const toks = code.toUpperCase().match(/[A-Z_][A-Z0-9_$]*/g) || [];
  for (const t of toks) {
    if (WRITE_TOKENS.has(t)) bad((label || 'SQL') + ' 中包含不允许的写操作: ' + t + '（此入口仅允许只读查询）');
  }
  if (!/^\s*(SELECT|WITH|TABLE|VALUES)\b/i.test(code)) bad((label || 'SQL') + ' 仅支持 SELECT / WITH / TABLE / VALUES');
  return raw;
}

/* ---- result parsers ---- */
function parseCsv(text) {
  const rows = [];
  let row = [], field = '', inQ = false, quoted = false, i = 0;
  const push = () => { row.push(quoted ? field : (field === '' ? null : field)); field = ''; quoted = false; };
  const s = String(text);
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
  return rows.filter((r) => !(r.length === 1 && r[0] === null));
}
function parseTsv(text) {
  const unesc = (s) => s.replace(/\\(.)/g, (m, c) => (c === 'n' ? '\n' : c === 't' ? '\t' : c === '0' ? '\0' : c === 'r' ? '\r' : c === 'Z' ? '\x1a' : c));
  return String(text).split('\n').filter((l) => l !== '').map((line) => line.split('\t').map((f) => {
    if (f === '\\N') return null;           /* MySQL NULL */
    return unesc(f);
  }));
}
function toObjects(matrix) {
  if (!matrix.length) return { columns: [], rows: [] };
  const columns = matrix[0].map((c) => String(c).trim());
  const rows = matrix.slice(1).map((r) => { const o = {}; columns.forEach((c, i) => { o[c] = r[i] === undefined ? null : r[i]; }); return o; });
  return { columns, rows };
}

/* ---- low-level execute ---- */
async function run(cmd, opts = {}) {
  const agent = getAgent();
  return agent.run(cmd, { timeout: opts.timeout || 60000, maxBuffer: opts.maxBuffer || 64 * 1024 * 1024 });
}

async function runSql(engine, database, sql, opts = {}) {
  checkEngine(engine);
  const t0 = Date.now();
  const cmd = (engine === 'postgres' ? pgClient(database, opts.quiet, opts.noCsv) : myClient(database, opts.noCsv));
  const full = `echo ${b64(sql)} | base64 -d | ` + cmd;
  const r = await run(full, { timeout: opts.timeout || 60000 });
  const durationMs = Date.now() - t0;
  const errText = (r.stderr || '').trim();
  const outText = (r.stdout || '');
  if (r.code !== 0 && !opts.ignoreError) {
    const e = new Error((errText || outText || `命令失败 (exit ${r.code})`).slice(0, 4000));
    e.status = 400; e.output = errText + '\n' + outText; e.durationMs = durationMs;
    throw e;
  }
  let parsed = { columns: [], rows: [] };
  if (opts.parse !== false && outText.trim()) {
    try {
      parsed = engine === 'postgres' ? toObjects(parseCsv(outText)) : toObjects(parseTsv(outText));
    } catch (_) {}
  }
  const text = (parsed.columns.length ? parsed.rows.length + ' 行' : (outText.trim() || errText || 'OK'));
  return { ok: true, output: text, columns: parsed.columns, rows: parsed.rows, durationMs, raw: outText, stderr: errText };
}

function checkEngine(engine) {
  if (engine !== 'postgres' && engine !== 'mysql') bad('仅支持 PostgreSQL / MySQL(MariaDB)，当前: ' + engine);
}

/* ================= overview ================= */
async function getEngineStatus(engine) {
  checkEngine(engine);
  if (engine === 'postgres') {
    const [ver, stats, procs, vars] = await Promise.all([
      runSql('postgres', null, "SELECT version() AS version, current_setting('data_directory') AS data_dir, pg_postmaster_start_time() AS started, pg_size_pretty(pg_database_size(current_database())) AS cur_db_size;"),
      runSql('postgres', null, `SELECT
          (SELECT count(*) FROM pg_stat_activity) AS connections,
          (SELECT setting FROM pg_settings WHERE name='max_connections') AS max_connections,
          (SELECT count(*) FROM pg_stat_activity WHERE state='active') AS active,
          (SELECT count(*) FROM pg_database WHERE datistemplate=false) AS databases,
          (SELECT round(100.0*sum(blks_hit)/nullif(sum(blks_hit)+sum(blks_read),0),2) FROM pg_stat_database) AS cache_hit_pct,
          (SELECT pg_size_pretty(sum(pg_database_size(datname))) FROM pg_database WHERE datistemplate=false) AS total_size;`),
      runSql('postgres', null, "SELECT pid, usename AS \"user\", datname AS db, state, client_addr AS client, to_char(query_start,'HH24:MI:SS') AS started, round(extract(epoch from now()-query_start)::numeric,1) AS seconds, left(regexp_replace(query,'\\s+',' ','g'),160) AS query FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND usename IS NOT NULL ORDER BY query_start DESC NULLS LAST LIMIT 200;"),
      runSql('postgres', null, "SELECT name, setting AS value, COALESCE(unit,'') AS unit, category FROM pg_settings ORDER BY category, name;"),
    ]);
    return { engine, version: ver.rows[0] || {}, stats: stats.rows[0] || {}, processes: procs.rows, variables: vars.rows, capabilities: { vacuum: true, reindex: true, checkpoint: true, analyze: true, vacuumFull: true, roles: true } };
  }
  // mysql
  const [ver, stats, procs, vars] = await Promise.all([
    runSql('mysql', null, "SELECT VERSION() AS version, @@datadir AS data_dir, @@hostname AS host;"),
    runSql('mysql', null, `SELECT (SELECT VARIABLE_VALUE FROM information_schema.global_status WHERE VARIABLE_NAME='Threads_connected') AS connections, @@max_connections AS max_connections, (SELECT VARIABLE_VALUE FROM information_schema.global_status WHERE VARIABLE_NAME='Uptime') AS uptime_sec, (SELECT VARIABLE_VALUE FROM information_schema.global_status WHERE VARIABLE_NAME='Questions') AS questions, (SELECT VARIABLE_VALUE FROM information_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_bytes_data') AS buffer_pool_bytes, (SELECT count(*) FROM information_schema.schemata WHERE schema_name NOT IN ('mysql','information_schema','performance_schema','sys')) AS databases;`),
    runSql('mysql', null, 'SHOW FULL PROCESSLIST;'),
    runSql('mysql', null, 'SHOW GLOBAL VARIABLES;'),
  ]);
  return {
    engine,
    version: ver.rows[0] || {}, stats: stats.rows[0] || {},
    processes: procs.rows.map((r) => ({ pid: r.Id, user: r.User, db: r.db, state: r.Command, client: r.Host, seconds: r.Time, started: '', query: String(r.Info || '').slice(0, 160) })),
    variables: vars.rows.map((r) => ({ name: r.Variable_name, value: r.Value, unit: '', category: 'global' })),
    capabilities: { optimize: true, analyze: true, check: true, repair: true, roles: true },
  };
}

/* ================= databases ================= */
async function listDatabases(engine) {
  checkEngine(engine);
  if (engine === 'postgres') {
    const r = await runSql('postgres', null, `SELECT d.datname AS name, pg_get_userbyid(d.datdba) AS owner,
        pg_size_pretty(pg_database_size(d.datname)) AS size, pg_database_size(d.datname) AS size_bytes,
        pg_encoding_to_char(d.encoding) AS encoding, d.datcollate AS collation, d.datistemplate AS is_template,
        '' AS created,
        (SELECT count(*) FROM pg_stat_activity a WHERE a.datname=d.datname) AS connections
      FROM pg_database d ORDER BY pg_database_size(d.datname) DESC;`);
    return r.rows.map((d) => ({ ...d, tables: null }));
  }
  const r = await runSql('mysql', null, `SELECT s.SCHEMA_NAME AS name, COALESCE(u.owner,'-') AS owner,
      ROUND(COALESCE(SUM(t.data_length+t.index_length),0)/1024,1) AS size_kb,
      CONCAT(UPPER(LEFT(s.DEFAULT_CHARACTER_SET_NAME,1)),LOWER(SUBSTRING(s.DEFAULT_CHARACTER_SET_NAME,2))) AS charset,
      s.DEFAULT_COLLATION_NAME AS collation, COUNT(t.TABLE_NAME) AS tables, '' AS created
    FROM information_schema.SCHEMATA s
    LEFT JOIN information_schema.TABLES t ON t.TABLE_SCHEMA=s.SCHEMA_NAME
    LEFT JOIN (SELECT DISTINCT GRANTEE AS owner, TABLE_SCHEMA FROM information_schema.SCHEMA_PRIVILEGES) u ON u.TABLE_SCHEMA=s.SCHEMA_NAME
    GROUP BY s.SCHEMA_NAME, s.DEFAULT_CHARACTER_SET_NAME, s.DEFAULT_COLLATION_NAME ORDER BY (SUM(t.data_length+t.index_length)) DESC;`);
  return r.rows.map((d) => ({
    name: d.name, owner: d.owner, size: (Number(d.size_kb || 0) / 1024).toFixed(2) + ' MB',
    size_bytes: Number(d.size_kb || 0) * 1024, encoding: d.charset, collation: d.collation,
    tables: Number(d.tables || 0), created: d.created, connections: null,
  }));
}

async function createDatabase({ engine, name, owner, encoding, collation, charset, template }) {
  checkEngine(engine);
  const id = qid(engine, name);
  let sql;
  if (engine === 'postgres') {
    const parts = [];
    if (owner) parts.push('OWNER ' + qid(engine, owner));
    if (encoding) parts.push('ENCODING ' + lit(engine, encoding));
    if (collation) parts.push('LC_COLLATE ' + lit(engine, collation), 'LC_CTYPE ' + lit(engine, collation));
    if (template) parts.push('TEMPLATE ' + qid(engine, template));
    sql = `CREATE DATABASE ${id}${parts.length ? ' ' + parts.join(' ') : ''};`;
  } else {
    sql = `CREATE DATABASE ${id}${charset ? ' CHARACTER SET ' + qid(engine, charset) : ''}${collation ? ' COLLATE ' + qid(engine, collation) : ''};`;
  }
  const r = await runSql(engine, null, sql);
  audit('db', 'db.create', { engine, name });
  return ok('数据库 ' + name + ' 已创建');
}

async function dropDatabase({ engine, name, force }) {
  checkEngine(engine);
  if (!name) bad('缺少数据库名');
  if (['postgres', 'template0', 'template1', 'mysql', 'information_schema', 'performance_schema', 'sys'].includes(name)) bad('系统数据库不可删除');
  const id = qid(engine, name);
  const sql = `DROP DATABASE ${force ? 'IF EXISTS ' : ''}${id}${engine === 'postgres' && force ? ' WITH (FORCE)' : ''};`;
  await runSql(engine, null, sql, { timeout: 120000 });
  audit('db', 'db.drop', { engine, name, force: !!force });
  return ok('数据库 ' + name + ' 已删除');
}

async function renameDatabase({ engine, from, to }) {
  checkEngine(engine);
  if (engine === 'mysql') bad('MySQL 不支持直接重命名数据库；请新建库并导出导入数据');
  await runSql(engine, null, `ALTER DATABASE ${qid(engine, from)} RENAME TO ${qid(engine, to)};`);
  return ok(`数据库 ${from} 已重命名为 ${to}`);
}

async function maintenance({ engine, database, table, action }) {
  checkEngine(engine);
  const sqls = {
    vacuum: `VACUUM ${table ? qid(engine, table) : ''};`,
    'vacuum-full': `VACUUM FULL ${table ? qid(engine, table) : ''};`,
    analyze: `ANALYZE ${table ? qid(engine, table) : ''};`,
    reindex: table ? `REINDEX TABLE ${qid(engine, table)};` : `REINDEX DATABASE ${qid(engine, database)};`,
    checkpoint: 'CHECKPOINT;',
    optimize: `OPTIMIZE TABLE ${qid(engine, table)};`,
    check: `CHECK TABLE ${qid(engine, table)};`,
    repair: `REPAIR TABLE ${qid(engine, table)};`,
    flush: 'FLUSH PRIVILEGES;',
    'analyze-mysql': `ANALYZE TABLE ${qid(engine, table)};`,
  };
  const sql = sqls[action];
  if (!sql) bad('未知维护操作: ' + action);
  if (engine === 'postgres' && ['optimize', 'check', 'repair', 'flush'].includes(action)) bad('该操作仅适用于 MySQL');
  if (engine === 'mysql' && ['vacuum', 'vacuum-full', 'reindex', 'checkpoint'].includes(action)) bad('该操作仅适用于 PostgreSQL');
  if (['vacuum', 'vacuum-full', 'analyze', 'optimize', 'check', 'repair', 'analyze-mysql'].includes(action) && !table) bad('该操作需要指定表名');
  if (action === 'reindex' && !table && !database) bad('需要指定数据库或表');
  const r = await runSql(engine, engine === 'postgres' && action === 'reindex' ? null : database, sql, { timeout: 1800000, parse: false });
  return ok((r.raw || '').trim() || '维护操作完成');
}

/* ================= tables ================= */
async function listTables({ engine, database }) {
  checkEngine(engine);
  if (!database) bad('缺少数据库名');
  if (engine === 'postgres') {
    const r = await runSql('postgres', database, `
      SELECT n.nspname AS schema, c.relname AS name,
        CASE c.relkind WHEN 'r' THEN 'table' WHEN 'p' THEN 'partitioned' WHEN 'v' THEN 'view' WHEN 'm' THEN 'matview' WHEN 'f' THEN 'foreign' ELSE c.relkind::text END AS kind,
        COALESCE(s.n_live_tup,0) AS rows, pg_size_pretty(pg_total_relation_size(c.oid)) AS size, pg_total_relation_size(c.oid) AS size_bytes
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      LEFT JOIN pg_stat_user_tables s ON s.relid=c.oid
      WHERE c.relkind IN ('r','p','v','m','f') AND n.nspname NOT IN ('pg_catalog','information_schema')
      ORDER BY n.nspname, pg_total_relation_size(c.oid) DESC NULLS LAST;`);
    return r.rows;
  }
  const r = await runSql('mysql', null, `SELECT TABLE_SCHEMA AS \`schema\`, TABLE_NAME AS name, LOWER(TABLE_TYPE) AS kind,
      TABLE_ROWS AS \`rows\`, ROUND((DATA_LENGTH+INDEX_LENGTH)/1024,1) AS size_kb, (DATA_LENGTH+INDEX_LENGTH) AS size_bytes,
      ENGINE AS engine, TABLE_COLLATION AS collation
    FROM information_schema.TABLES WHERE TABLE_SCHEMA=${lit('mysql', database)} ORDER BY (DATA_LENGTH+INDEX_LENGTH) DESC;`);
  return r.rows.map((t) => ({ schema: t.schema, name: t.name, kind: String(t.kind || '').replace('base table', 'table'), rows: Number(t.rows || 0), size: (Number(t.size_bytes || 0) / 1048576).toFixed(2) + ' MB', size_bytes: Number(t.size_bytes || 0), engine: t.engine, collation: t.collation }));
}

async function getTableSchema({ engine, database, table }) {
  checkEngine(engine);
  const parts = String(table).split('.');
  const tbl = parts.pop();
  const schema = parts.length ? parts.pop() : (engine === 'postgres' ? 'public' : database);
  if (engine === 'postgres') {
    const cols = await runSql('postgres', database, `
      SELECT c.column_name AS name, c.data_type AS type, c.udt_name AS base_type, c.is_nullable AS nullable,
        COALESCE(c.column_default,'') AS "default", c.character_maximum_length AS maxlen, c.numeric_precision AS precision,
        c.ordinal_position AS pos, '' AS extra, '' AS comment
      FROM information_schema.columns c
      WHERE c.table_schema=${lit('postgres', schema)} AND c.table_name=${lit('postgres', tbl)} ORDER BY c.ordinal_position;`);
    const pk = await runSql('postgres', database, `SELECT a.attname AS name FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=ANY(i.indkey) WHERE i.indrelid=(SELECT oid FROM pg_class WHERE relname=${lit('postgres', tbl)} AND relnamespace=(SELECT oid FROM pg_namespace WHERE nspname=${lit('postgres', schema)})) AND i.indisprimary;`);
    const idx = await runSql('postgres', database, `SELECT indexname AS name, indexdef AS def FROM pg_indexes WHERE schemaname=${lit('postgres', schema)} AND tablename=${lit('postgres', tbl)};`);
    const ddlR = await run(`su - postgres -c ${shq(`pg_dump --schema-only -t ${schema}.${tbl} -d ${database}`)}`, { timeout: 60000 });
    const pkCols = pk.rows.map((r) => r.name);
    return {
      columns: cols.rows.map((c) => ({ ...c, pk: pkCols.includes(c.name), extra: '', comment: c.comment || '' })),
      indexes: idx.rows, primaryKey: pkCols, ddl: ddlR.stdout.trim(),
    };
  }
  const cols = await runSql('mysql', database, `SELECT COLUMN_NAME AS name, COLUMN_TYPE AS type, DATA_TYPE AS base_type, IS_NULLABLE AS nullable,
      COALESCE(COLUMN_DEFAULT,'') AS \`default\`, CHARACTER_MAXIMUM_LENGTH AS maxlen, NUMERIC_PRECISION AS precision,
      ORDINAL_POSITION AS pos, COLUMN_KEY AS pk, EXTRA AS extra, COLUMN_COMMENT AS comment
    FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=${lit('mysql', database)} AND TABLE_NAME=${lit('mysql', tbl)} ORDER BY ORDINAL_POSITION;`);
  const idx = await runSql('mysql', database, `SELECT INDEX_NAME AS name, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, IF(NON_UNIQUE=0,'UNIQUE','') AS uniq, INDEX_TYPE AS type FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=${lit('mysql', database)} AND TABLE_NAME=${lit('mysql', tbl)} GROUP BY INDEX_NAME, NON_UNIQUE, INDEX_TYPE;`);
  const ddlR = await runSql('mysql', database, `SHOW CREATE TABLE ${qid('mysql', tbl)};`, { parse: true });
  return {
    columns: cols.rows.map((c) => ({ ...c, pk: String(c.pk || '').includes('PRI') })),
    indexes: idx.rows, primaryKey: cols.rows.filter((c) => String(c.pk || '').includes('PRI')).map((c) => c.name),
    ddl: (ddlR.rows[0] && ddlR.rows[0]['Create Table']) || (ddlR.raw || ''),
  };
}

function safeWhere(w) {
  const s = String(w || '').trim();
  if (!s) return '';
  if (s.length > 800) bad('WHERE 条件过长');
  if (s.includes(';')) bad('WHERE 条件不允许包含分号');
  if (/\b(drop|delete|update|insert|alter|create|truncate|grant|revoke|copy|call|do|vacuum|analyze|reindex|attach)\b/i.test(s)) bad('WHERE 条件包含不允许的关键字');
  return s;
}

async function getTableData({ engine, database, table, limit, offset, orderBy, orderDir, where }) {
  checkEngine(engine);
  const lim = Math.max(1, Math.min(MAX_ROWS, parseInt(limit, 10) || 50));
  const off = Math.max(0, parseInt(offset, 10) || 0);
  const dir = /^desc$/i.test(String(orderDir || 'asc')) ? 'DESC' : 'ASC';
  const perm = (await listTables({ engine, database })).find((t) => t.name === table || `${t.schema}.${t.name}` === table);
  const tname = engine === 'postgres' && perm ? qid('postgres', perm.schema + '.' + perm.name) : qid(engine, table);
  const w = safeWhere(where);
  let sql = `SELECT * FROM ${tname}${w ? ' WHERE ' + w : ''}`;
  if (orderBy) {
    /* validate order column exists in schema */
    const sch = await getTableSchema({ engine, database, table: perm ? perm.schema + '.' + perm.name : table });
    const col = sch.columns.find((c) => c.name.toLowerCase() === String(orderBy).toLowerCase());
    if (!col) bad('排序列不存在: ' + orderBy);
    sql += ` ORDER BY ${qid(engine, col.name)} ${dir}`;
  }
  sql += ` LIMIT ${lim} OFFSET ${off};`;
  const countSql = `SELECT count(*) AS total FROM ${tname}${w ? ' WHERE ' + w : ''};`;
  const [data, cnt] = await Promise.all([
    runSql(engine, database, sql, { timeout: 120000 }),
    runSql(engine, database, countSql, { timeout: 120000 }).catch(() => ({ rows: [{ total: null }] })),
  ]);
  return {
    columns: data.columns, rows: data.rows, limit: lim, offset: off,
    total: cnt.rows[0] ? Number(cnt.rows[0].total ?? cnt.rows[0].count ?? Object.values(cnt.rows[0])[0]) : null,
    durationMs: data.durationMs,
  };
}

async function insertRow({ engine, database, table, values }) {
  checkEngine(engine);
  const vals = values && typeof values === 'object' ? values : bad('缺少数据');
  const keys = Object.keys(vals);
  if (!keys.length) bad('没有要插入的字段');
  const sql = `INSERT INTO ${qid(engine, table)} (${keys.map((k) => qid(engine, k)).join(', ')}) VALUES (${keys.map((k) => lit(engine, vals[k])).join(', ')});`;
  const r = await runSql(engine, database, sql, { timeout: 60000 });
  audit('db', 'row.insert', { engine, database, table, cols: keys });
  return ok('已插入 1 行', { sql });
}

async function updateRow({ engine, database, table, pk, values, where }) {
  checkEngine(engine);
  const vals = values && typeof values === 'object' ? values : bad('缺少数据');
  const keys = Object.keys(vals);
  if (!keys.length) bad('没有要更新的字段');
  let cond;
  if (pk && pk.column) cond = `${qid(engine, pk.column)} = ${lit(engine, pk.value)}`;
  else if (where) cond = safeWhere(where);
  else bad('缺少定位条件（主键或 WHERE）');
  const sql = `UPDATE ${qid(engine, table)} SET ${keys.map((k) => `${qid(engine, k)} = ${lit(engine, vals[k])}`).join(', ')} WHERE ${cond};`;
  const wrapped = engine === 'postgres'
    ? `WITH affected AS (UPDATE ${qid(engine, table)} SET ${keys.map((k) => `${qid(engine, k)} = ${lit(engine, vals[k])}`).join(', ')} WHERE ${cond} RETURNING 1) SELECT count(*)::int AS affected FROM affected;`
    : `${sql} SELECT ROW_COUNT() AS affected;`;
  const r = await runSql(engine, database, wrapped, { timeout: 60000 });
  audit('db', 'row.update', { engine, database, table, cols: keys, cond });
  return ok((r.raw || '').trim() || '更新完成', { sql, affected: r.rows[0] ? Number(r.rows[0].affected) : null, columns: r.columns, rows: r.rows });
}

async function deleteRow({ engine, database, table, pk, where }) {
  checkEngine(engine);
  let cond;
  if (pk && pk.column) cond = `${qid(engine, pk.column)} = ${lit(engine, pk.value)}`;
  else if (where) cond = safeWhere(where);
  else bad('缺少定位条件（主键或 WHERE）');
  const sql = `DELETE FROM ${qid(engine, table)} WHERE ${cond};`;
  const wrapped = engine === 'postgres'
    ? `WITH affected AS (DELETE FROM ${qid(engine, table)} WHERE ${cond} RETURNING 1) SELECT count(*)::int AS affected FROM affected;`
    : `${sql} SELECT ROW_COUNT() AS affected;`;
  const r = await runSql(engine, database, wrapped, { timeout: 60000 });
  audit('db', 'row.delete', { engine, database, table, cond });
  return ok((r.raw || '').trim() || '删除完成', { sql, affected: r.rows[0] ? Number(r.rows[0].affected) : null });
}
async function tableAction({ engine, database, table, action, newName }) {
  checkEngine(engine);
  const id = qid(engine, table);
  let sql;
  if (action === 'truncate') sql = `TRUNCATE TABLE ${id}${engine === 'postgres' ? ' RESTART IDENTITY CASCADE' : ''};`;
  else if (action === 'drop') sql = `DROP TABLE ${id};`;
  else if (action === 'rename') { if (!newName) bad('缺少新表名'); sql = `ALTER TABLE ${id} RENAME TO ${qid(engine, newName)};`; }
  else bad('未知表操作: ' + action);
  await runSql(engine, database, sql, { timeout: 300000 });
  audit('db', 'table.' + action, { engine, database, table, newName });
  return ok('表操作完成: ' + action);
}

/* ================= roles / privileges ================= */
async function listRoles(engine) {
  checkEngine(engine);
  if (engine === 'postgres') {
    const r = await runSql('postgres', null, `SELECT r.rolname AS name, r.rolsuper AS super, r.rolcanlogin AS can_login, r.rolcreatedb AS createdb,
        r.rolcreaterole AS createrole, COALESCE(to_char(r.rolvaliduntil,'YYYY-MM-DD'),'') AS valid_until,
        COALESCE(string_agg(m.rolname, ', ') FILTER (WHERE m.rolname IS NOT NULL), '') AS member_of
      FROM pg_roles r
      LEFT JOIN pg_auth_members am ON am.member=r.oid
      LEFT JOIN pg_roles m ON m.oid=am.roleid
      GROUP BY r.rolname, r.rolsuper, r.rolcanlogin, r.rolcreatedb, r.rolcreaterole, r.rolvaliduntil ORDER BY r.rolname;`);
    const privs = await runSql('postgres', null, `SELECT grantee, table_schema AS schema, privilege_type AS privilege, '' AS object FROM information_schema.table_privileges WHERE grantee NOT IN ('PUBLIC','postgres') GROUP BY grantee, table_schema, privilege_type ORDER BY grantee LIMIT 300;`).catch(() => ({ rows: [] }));
    return { roles: r.rows, privileges: privs.rows };
  }
  const r = await runSql('mysql', null, `SELECT User AS name, Host AS host, IF(Super_priv='Y','yes','') AS super, IF(plugin='auth_socket','socket','password') AS auth_plugin, COALESCE(authentication_string,'') <> '' AS has_password FROM mysql.user ORDER BY User, Host;`);
  const privs = await runSql('mysql', null, `SHOW GRANTS;`, { parse: false }).catch(() => ({ raw: '' }));
  return { roles: r.rows, privileges: [], grants: (privs.raw || '').split('\n').filter(Boolean), hosts: r.rows.map((x) => x.host) };
}

async function roleAction({ engine, action, name, password, host, privileges, database, member, grantee, params = {} }) {
  checkEngine(engine);
  const p2s = (arr, v) => Array.isArray(arr) && arr.includes(v);
  if (!name || !/^[^"\0\r\n'` ]{1,64}$/.test(name)) bad('无效角色名');
  if (engine === 'postgres') {
    if (action === 'create') {
      const parts = [`CREATE ROLE ${qid(engine, name)}`];
      parts.push(password ? `LOGIN PASSWORD ${lit(engine, password)}` : 'LOGIN');
      if (privileges && privileges.includes('superuser')) parts.push('SUPERUSER');
      if (privileges && privileges.includes('createdb')) parts.push('CREATEDB');
      if (privileges && privileges.includes('createrole')) parts.push('CREATEROLE');
      await runSql(engine, null, parts.join(' ') + ';');
      return ok('角色 ' + name + ' 已创建');
    }
    if (action === 'drop') { await runSql(engine, null, `DROP ROLE ${qid(engine, name)};`); return ok('角色已删除'); }
    if (action === 'password') { await runSql(engine, null, `ALTER ROLE ${qid(engine, name)} PASSWORD ${lit(engine, password)};`); return ok('密码已修改'); }
    if (action === 'grant-db') {
      if (!database) bad('缺少数据库');
      await runSql(engine, null, `GRANT ALL PRIVILEGES ON DATABASE ${qid(engine, database)} TO ${qid(engine, name)};`);
      await runSql('postgres', database, `GRANT ALL ON SCHEMA public TO ${qid(engine, name)};`).catch(() => {});
      return ok(`已授予 ${name} 对 ${database} 的全部权限`);
    }
    if (action === 'revoke-db') { await runSql(engine, null, `REVOKE ALL PRIVILEGES ON DATABASE ${qid(engine, database)} FROM ${qid(engine, name)};`); return ok('权限已回收'); }
    if (action === 'add-member') { await runSql(engine, null, `GRANT ${qid(engine, member)} TO ${qid(engine, name)};`); return ok('已加入角色组'); }
    if (action === 'remove-member') { await runSql(engine, null, `REVOKE ${qid(engine, member)} FROM ${qid(engine, name)};`); return ok('已移出角色组'); }
    if (action === 'alter') {
      const parts = [];
      if (p2s(privileges, 'superuser')) parts.push('SUPERUSER');
      if (p2s(privileges, 'nosuperuser')) parts.push('NOSUPERUSER');
      if (p2s(privileges, 'createdb')) parts.push('CREATEDB');
      if (p2s(privileges, 'nocreatedb')) parts.push('NOCREATEDB');
      if (p2s(privileges, 'createrole')) parts.push('CREATEROLE');
      if (p2s(privileges, 'nocreaterole')) parts.push('NOCREATEROLE');
      if (p2s(privileges, 'login')) parts.push('LOGIN');
      if (p2s(privileges, 'nologin')) parts.push('NOLOGIN');
      if (params.connlimit !== undefined && params.connlimit !== '') parts.push('CONNECTION LIMIT ' + (parseInt(params.connlimit, 10) || -1));
      if (params.validUntil) parts.push(`VALID UNTIL ${lit('postgres', params.validUntil)}`);
      if (!parts.length) bad('没有要修改的属性');
      await runSql(engine, null, `ALTER ROLE ${qid(engine, name)} ${parts.join(' ')};`);
      return ok('角色属性已更新: ' + parts.join(' '));
    }
    if (action === 'grant-table' || action === 'revoke-table') {
      if (!database || !params.table) bad('缺少数据库或表名');
      const privs = (Array.isArray(params.privileges) && params.privileges.length ? params.privileges : ['SELECT'])
        .map((x) => String(x).toUpperCase()).filter((x) => /^(SELECT|INSERT|UPDATE|DELETE|TRUNCATE|REFERENCES|TRIGGER|ALL)$/.test(x));
      if (!privs.length) bad('无效权限');
      const verb = action === 'grant-table' ? 'GRANT' : 'REVOKE';
      await runSql(engine, database, `${verb} ${privs.join(', ')} ON TABLE ${qid(engine, params.table)} ${action === 'grant-table' ? 'TO' : 'FROM'} ${qid(engine, name)};`);
      /* also sequences for serial columns */
      if (action === 'grant-table') await runSql(engine, database, `GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${qid(engine, name)};`).catch(() => {});
      return ok(`${action === 'grant-table' ? '已授予' : '已回收'} ${privs.join(', ')} on ${params.table}`);
    }
    if (action === 'grant-schema') {
      if (!database) bad('缺少数据库');
      await runSql(engine, database, `GRANT USAGE ON SCHEMA public TO ${qid(engine, name)}; GRANT ALL ON ALL TABLES IN SCHEMA public TO ${qid(engine, name)}; GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO ${qid(engine, name)};`);
      return ok('已授予 public schema 全表/序列权限');
    }
  } else {
    const h = host && /^[\w.%:_-]{1,64}$/.test(host) ? host : 'localhost';
    const acct = `'${name}'@'${h}'`;
    if (action === 'create') {
      const sql = password
        ? `CREATE USER ${acct} IDENTIFIED BY ${lit('mysql', password)};`
        : `CREATE USER ${acct};`;
      await runSql('mysql', null, sql);
      if (privileges && privileges.includes('superuser')) await runSql('mysql', null, `GRANT ALL PRIVILEGES ON *.* TO ${acct} WITH GRANT OPTION;`);
      await runSql('mysql', null, 'FLUSH PRIVILEGES;');
      return ok('用户 ' + name + '@' + h + ' 已创建');
    }
    if (action === 'drop') { await runSql('mysql', null, `DROP USER ${acct};`); return ok('用户已删除'); }
    if (action === 'password') { await runSql('mysql', null, `ALTER USER ${acct} IDENTIFIED BY ${lit('mysql', password)};`); return ok('密码已修改'); }
    if (action === 'grant-db') {
      if (!database) bad('缺少数据库');
      await runSql('mysql', null, `GRANT ALL PRIVILEGES ON ${qid('mysql', database)}.* TO ${acct}; FLUSH PRIVILEGES;`);
      return ok(`已授予 ${name} 对 ${database} 的全部权限`);
    }
    if (action === 'revoke-db') { await runSql('mysql', null, `REVOKE ALL PRIVILEGES ON ${qid('mysql', database)}.* FROM ${acct}; FLUSH PRIVILEGES;`); return ok('权限已回收'); }
    if (action === 'grant-super') { await runSql('mysql', null, `GRANT ALL PRIVILEGES ON *.* TO ${acct} WITH GRANT OPTION; FLUSH PRIVILEGES;`); return ok('已授予全局权限'); }
    if (action === 'lock') { await runSql('mysql', null, `ALTER USER ${acct} ACCOUNT LOCK;`); return ok('账户已锁定'); }
    if (action === 'unlock') { await runSql('mysql', null, `ALTER USER ${acct} ACCOUNT UNLOCK;`); return ok('账户已解锁'); }
    if (action === 'expire') { await runSql('mysql', null, `ALTER USER ${acct} PASSWORD EXPIRE;`); return ok('已设置密码过期（下次登录需改密）'); }
    if (action === 'grant-table' || action === 'revoke-table') {
      if (!database || !params.table) bad('缺少数据库或表名');
      const privs = (Array.isArray(params.privileges) && params.privileges.length ? params.privileges : ['SELECT'])
        .map((x) => String(x).toUpperCase()).filter((x) => /^(SELECT|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|INDEX|REFERENCES|ALL)$/.test(x));
      if (!privs.length) bad('无效权限');
      const verb = action === 'grant-table' ? 'GRANT' : 'REVOKE';
      await runSql('mysql', null, `${verb} ${privs.join(', ')} ON ${qid('mysql', database)}.${qid('mysql', params.table)} ${action === 'grant-table' ? 'TO' : 'FROM'} ${acct}; FLUSH PRIVILEGES;`);
      return ok(`${action === 'grant-table' ? '已授予' : '已回收'} ${privs.join(', ')} on ${params.table}`);
    }
    if (action === 'grants-list') {
      const r = await runSql('mysql', null, `SHOW GRANTS FOR ${acct};`, { parse: false });
      return ok((r.raw || '').trim() || '(无)');
    }
  }
  bad('未知角色操作: ' + action);
}

/* ================= connections ================= */
async function killProcess({ engine, pid }) {
  checkEngine(engine);
  const id = parseInt(pid, 10);
  if (!Number.isFinite(id) || id <= 0) bad('无效进程 ID');
  if (engine === 'postgres') {
    const r = await runSql('postgres', null, `SELECT pg_terminate_backend(${id}) AS terminated;`);
    return ok('已终止连接 #' + id, { terminated: r.rows[0] && r.rows[0].terminated });
  }
  await runSql('mysql', null, `KILL ${id};`, { ignoreError: true });
  return ok('已终止连接 #' + id);
}

/* ================= SQL console ================= */
async function sqlConsole({ engine, database, sql, explain, transaction, dryRun }) {
  checkEngine(engine);
  let s = String(sql || '').trim();
  if (!s) bad('SQL 为空');
  if (s.length > 500000) bad('SQL 过长');
  /* dry run: execute inside a transaction and roll back — nothing is committed */
  if (dryRun) {
    const body = s.replace(/;+\s*$/, ';');
    const wrapped = (engine === 'postgres' ? 'BEGIN;\n' : 'START TRANSACTION;\n') + body + '\n' + 'ROLLBACK;';
    const r = await runSql(engine, database, wrapped, { timeout: 600000, parse: false, quiet: false });
    return {
      ok: true, dryRun: true, durationMs: r.durationMs, columns: [], rows: [],
      output: '【试运行】已在事务中执行并回滚，没有数据被修改。' +
        (engine === 'postgres' ? '\n下面是各语句的影响行数（UPDATE/DELETE/INSERT n）：' : '\n（MySQL 不返回影响行数，仅验证语法与约束是否通过）') +
        '\n\n' + ((r.raw || '').trim() || '(无输出)'),
    };
  }
  /* EXPLAIN wrapping */
  if (explain) {
    const stripped = s.replace(/;+\s*$/, '');
    s = engine === 'postgres' ? `EXPLAIN (ANALYZE, VERBOSE, BUFFERS) ${stripped};` : `EXPLAIN FORMAT=JSON ${stripped};`;
    const r = await runSql(engine, database, s, { timeout: 600000 });
    return { ok: true, output: (r.raw || '').trim() || '执行成功（无输出）', columns: [], rows: [], durationMs: r.durationMs, explain: true };
  }
  if (transaction && !/^\s*(begin|start)\b/i.test(s)) {
    s = `BEGIN;\n${s}\nCOMMIT;`;
  }
  const r = await runSql(engine, database, s, { timeout: 600000 });
  return r;
}

/* ================= backup / restore ================= */
function dbBackupPath(name) {
  const n = String(name || '');
  if (!/^(db|up)-[A-Za-z0-9._\-]+\.(sql\.gz|dump|sql)$/.test(n) || n.includes('..')) return null;
  return DB_BACKUP_DIR + '/' + n;
}
async function listDbBackups({ engine }) {
  const r = await run(`ls -la ${DB_BACKUP_DIR}/*.sql.gz ${DB_BACKUP_DIR}/*.dump ${DB_BACKUP_DIR}/*.sql ${DB_BACKUP_DIR}/*.csv ${DB_BACKUP_DIR}/*.json ${UPLOAD_DIR}/* 2>/dev/null | grep -v '^total' || true`, { timeout: 20000 });
  const items = [];
  for (const line of String(r.stdout).split('\n')) {
    const m = /^-\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+\S+\s+(\S+)$/.exec(line.trim());
    if (!m) continue;
    const full = m[2];
    const name = full.split('/').pop();
    const isUpload = full.includes('/uploads/');
    const isExport = name.startsWith('ex-');
    const em = /^db-(postgres|mysql)-/.exec(name);
    const db = /^db-(?:postgres|mysql)-(.+?)-\d{4}-\d{2}-\d{2}/.exec(name);
    items.push({
      name, size: parseInt(m[1], 10), uploaded: isUpload, export: isExport,
      engine: em ? em[1] : '',
      database: db ? db[1].replace(/_/g, ' ') : '',
      mode: /-(schema|data)-/.test(name) ? (/-schema-/.test(name) ? 'schema' : 'data') : (/\.dump$/.test(name) ? 'custom' : 'full'),
      mtime: line.trim().split(/\s+/).slice(5, 8).join(' '),
    });
  }
  const wanted = items.filter((i) => !engine || !i.engine || i.engine === engine || i.uploaded || i.export);
  return { dir: DB_BACKUP_DIR, items: wanted.reverse() };
}

async function createDbBackup({ engine, database, format, mode, exclude }) {
  checkEngine(engine);
  const isCustom = format === 'custom';
  if (isCustom && engine !== 'postgres') bad('自定义格式(--format=c)仅 PostgreSQL 支持');
  const dumpMode = ['full', 'schema', 'data'].includes(mode) ? mode : 'full';
  const all = !database || database === 'ALL';
  const excludeList = (Array.isArray(exclude) ? exclude : String(exclude || '').split(/[\s,]+/)).map((x) => String(x).trim()).filter(Boolean);
  for (const t of excludeList) if (!/^[A-Za-z_][A-Za-z0-9_$.]*$/.test(t)) bad('排除表名非法: ' + t);
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
  const safe = String(all ? 'ALL' : database).replace(/[^A-Za-z0-9_.-]/g, '_');
  const ext = isCustom ? 'dump' : 'sql.gz';
  const name = `db-${engine}-${safe}-${dumpMode}-${stamp}.${ext}`;
  const out = `${DB_BACKUP_DIR}/${name}`;
  let cmd;
  if (engine === 'postgres') {
    const flags = [];
    if (isCustom) flags.push('-Fc');
    else flags.push('-Fp');
    if (dumpMode === 'schema') flags.push('-s');
    if (dumpMode === 'data') flags.push('-a');
    for (const t of excludeList) flags.push('-T', shq(t));
    const base = all
      ? `pg_dumpall${dumpMode === 'data' ? ' -a' : dumpMode === 'schema' ? ' -s' : ''}`
      : `pg_dump ${flags.join(' ')}${all ? '' : ' -d ' + shq(database)}`;
    cmd = isCustom
      ? `su - postgres -c ${shq(base)} > ${out}`
      : `su - postgres -c ${shq(base)} | gzip -1 > ${out}`;
  } else {
    const c = (config.get().db || {}).mysql || {};
    const auth = [];
    if (c.host) auth.push('-h', shq(c.host));
    if (c.port) auth.push('-P', shq(c.port));
    auth.push('-u', shq(c.user || 'root'));
    if (c.password) auth.push('-p' + shq(c.password));
    const flags = ['--single-transaction', '--routines', '--triggers', '--events', '--default-character-set=utf8mb4'];
    if (dumpMode === 'schema') flags.push('--no-data');
    if (dumpMode === 'data') flags.push('--no-create-info');
    if (!all) for (const t of excludeList) flags.push('--ignore-table=' + shq(`${database}.${t}`));
    cmd = `mysqldump ${flags.join(' ')} ${auth.join(' ')} ${all ? '--all-databases' : shq(database)} | gzip -1 > ${out}`;
  }
  if (engine === 'postgres') cmd = `mkdir -p ${DB_BACKUP_DIR} && ` + cmd;
  else cmd = `mkdir -p ${DB_BACKUP_DIR} && ` + cmd;
  const r = await run(cmd, { timeout: 3600000, maxBuffer: 8 * 1024 * 1024 });
  if (r.code !== 0) throw Object.assign(new Error('备份失败: ' + ((r.stderr || r.stdout || '').trim().slice(0, 1500))), { status: 400 });
  const st = await run(`ls -l ${out} 2>/dev/null | awk '{print $5}'`, { timeout: 15000 });
  const size = parseInt((st.stdout || '0').trim(), 10) || 0;
  if (!size) throw Object.assign(new Error('备份文件为空，请检查数据库权限/连接配置'), { status: 400 });
  audit('db', 'backup.create', { engine, database: all ? 'ALL' : database, name, mode: dumpMode });
  return ok(`备份完成: ${name} (${(size / 1048576).toFixed(2)} MB)`, { name, size });
}

async function restoreDbBackup({ engine, database, file, dropFirst, createIfMissing }) {
  checkEngine(engine);
  if (!database) bad('缺少目标数据库名');
  const p = dbBackupPath(file) || dbFilePath(file);
  if (!p) bad('非法备份文件名');
  /* optionally create the target database */
  let created = false;
  const exists = await runSql(engine, null,
    engine === 'postgres' ? `SELECT 1 FROM pg_database WHERE datname=${lit('postgres', database)};`
      : `SELECT 1 FROM information_schema.schemata WHERE schema_name=${lit('mysql', database)};`,
    { ignoreError: true }).catch(() => ({ rows: [] }));
  if (!exists.rows.length) {
    if (!createIfMissing) bad(`目标数据库 ${database} 不存在（可勾选“自动创建”）`);
    await createDatabase({ engine, name: database });
    created = true;
  }
  const isGz = /\.sql\.gz$/.test(file);
  const isDump = /\.dump$/.test(file);
  const isCsv = /\.csv$/.test(file) || /\.json$/.test(file);
  if (isCsv) bad('CSV/JSON 文件请使用「导入 CSV」功能，而不是恢复');
  let cmd;
  if (engine === 'postgres') {
    if (isDump) {
      cmd = `su - postgres -c ${shq(`pg_restore ${dropFirst ? '--clean --if-exists' : ''} --no-owner -d ${database} ${p}`)}`;
    } else {
      const src = isGz ? `gunzip -c ${p}` : `cat ${p}`;
      cmd = `${src} | su - postgres -c ${shq(`psql -X -q -d ${database} -f -`)}`;
    }
  } else {
    const src = isGz ? `gunzip -c ${p}` : `cat ${p}`;
    const c = (config.get().db || {}).mysql || {};
    const auth = [];
    if (c.host) auth.push('-h', shq(c.host));
    if (c.port) auth.push('-P', shq(c.port));
    auth.push('-u', shq(c.user || 'root'));
    if (c.password) auth.push('-p' + shq(c.password));
    cmd = `${src} | mysql ${auth.join(' ')} -D ${shq(database)}`;
  }
  const r = await run(cmd, { timeout: 3600000, maxBuffer: 32 * 1024 * 1024 });
  const errOut = ((r.stderr || '') + (r.stdout || '')).trim();
  if (r.code !== 0) throw Object.assign(new Error('恢复失败 (exit ' + r.code + ')：\n' + errOut.slice(0, 2000)), { status: 400, output: errOut });
  audit('db', 'backup.restore', { engine, database, file, dropFirst: !!dropFirst, created });
  return ok(`恢复完成 -> ${database}${created ? '（已自动创建数据库）' : ''}` + (errOut ? '\n' + errOut.slice(0, 1500) : ''));
}

async function deleteDbBackup({ name }) {
  const p = dbBackupPath(name);
  if (!p) bad('非法文件名');
  return ok(await run(`rm -f ${p} && echo 已删除 ${name}`));
}

function uploadPath(name) {
  const n = String(name || '').replace(/[^A-Za-z0-9._\-]/g, '_');
  if (!n || n.length > 120 || n.includes('..')) bad('非法上传文件名');
  return UPLOAD_DIR + '/up-' + n;
}

async function saveUpload(name, buffer) {
  const p = uploadPath(name);
  const agent = getAgent();
  await run(`mkdir -p ${UPLOAD_DIR}`);
  if (agent.mode === 'local') {
    fs.writeFileSync(p, buffer);
  } else {
    /* ssh mode: stream through base64 in chunks */
    const b = buffer.toString('base64');
    const chunk = 512 * 1024;
    await run(`rm -f ${p}.b64`);
    for (let i = 0; i < b.length; i += chunk) {
      await run(`printf %s ${shq(b.slice(i, i + chunk))} >> ${p}.b64`);
    }
    await run(`base64 -d ${p}.b64 > ${p} && rm -f ${p}.b64`);
  }
  const st = await run(`ls -l ${p} | awk '{print $5}'`);
  audit('db', 'backup.upload', { name, size: parseInt(st.stdout, 10) });
  return ok('已上传: ' + p.split('/').pop(), { path: p, size: parseInt(st.stdout, 10) || 0 });
}

/* ================= insights ================= */
async function sec(title, fn, note) {
  try {
    const r = await fn();
    const rows = r.rows || [];
    return { title, columns: r.columns && r.columns.length ? r.columns : (rows[0] ? Object.keys(rows[0]) : []), rows, note: note || '' };
  } catch (e) {
    return { title, columns: [], rows: [], error: String(e.message || e).slice(0, 400), note: note || '' };
  }
}

async function insights({ engine, database }) {
  checkEngine(engine);
  const out = [];
  if (engine === 'postgres') {
    out.push(await sec('数据库统计 (pg_stat_database)', () => runSql('postgres', database, `SELECT datname, numbackends AS conns, xact_commit AS commits, xact_rollback AS rollbacks, deadlocks,
        round(100.0*blks_hit/nullif(blks_hit+blks_read,0),2) AS cache_hit_pct, tup_returned AS tup_read, tup_inserted AS ins, tup_updated AS upd, tup_deleted AS del,
        temp_files, pg_size_pretty(temp_bytes) AS temp_size, stats_reset FROM pg_stat_database WHERE datname = current_database();`)));
    out.push(await sec('表膨胀 / 死元组 (前 30)', () => runSql('postgres', database, `SELECT schemaname AS schema, relname AS name, n_live_tup AS live, n_dead_tup AS dead,
        round(100.0*n_dead_tup/nullif(n_live_tup+n_dead_tup,1),1) AS dead_pct, n_tup_ins AS ins, n_tup_upd AS upd, n_tup_del AS del,
        COALESCE(to_char(GREATEST(last_vacuum,last_autovacuum),'MM-DD HH24:MI'),'-') AS last_vacuum,
        COALESCE(to_char(GREATEST(last_analyze,last_autoanalyze),'MM-DD HH24:MI'),'-') AS last_analyze,
        pg_size_pretty(pg_total_relation_size(relid)) AS size FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 30;`)));
    out.push(await sec('未使用的索引', () => runSql('postgres', database, `SELECT schemaname AS schema, relname AS table, indexrelname AS idx, idx_scan AS scans, pg_size_pretty(pg_relation_size(indexrelid)) AS size
        FROM pg_stat_user_indexes WHERE idx_scan = 0 AND indexrelname NOT LIKE '%pkey' ORDER BY pg_relation_size(indexrelid) DESC LIMIT 30;`)));
    out.push(await sec('索引使用率 Top', () => runSql('postgres', database, `SELECT schemaname AS schema, relname AS table, indexrelname AS idx, idx_scan AS scans, idx_tup_read AS tup_read, pg_size_pretty(pg_relation_size(indexrelid)) AS size
        FROM pg_stat_user_indexes ORDER BY idx_scan DESC LIMIT 30;`)));
    out.push(await sec('锁与阻塞', () => runSql('postgres', database, `SELECT l.pid, a.usename AS "user", l.locktype, l.mode, l.granted, c.relname AS relation,
        a.state, round(extract(epoch from now()-a.query_start)::numeric,1) AS secs, pg_blocking_pids(l.pid) AS blockers, left(regexp_replace(a.query,'\\s+',' ','g'),120) AS query
        FROM pg_locks l LEFT JOIN pg_stat_activity a ON a.pid=l.pid LEFT JOIN pg_class c ON c.oid=l.relation
        WHERE l.pid <> pg_backend_pid() ORDER BY l.granted, secs DESC NULLS LAST LIMIT 60;`)));
    out.push(await sec('长事务 / 活动查询', () => runSql('postgres', database, `SELECT pid, usename AS "user", state, round(extract(epoch from now()-xact_start)::numeric,1) AS xact_secs,
        round(extract(epoch from now()-query_start)::numeric,1) AS query_secs, wait_event_type AS wait_type, left(regexp_replace(query,'\\s+',' ','g'),140) AS query
        FROM pg_stat_activity WHERE xact_start IS NOT NULL AND pid <> pg_backend_pid() ORDER BY xact_secs DESC NULLS LAST LIMIT 30;`)));
    out.push(await sec('复制状态', () => runSql('postgres', database, `SELECT client_addr, state, sync_state, sent_lsn, replay_lsn, write_lag, replay_lag FROM pg_stat_replication;`), 'None 表示没有从库'));
    out.push(await sec('连接分布', () => runSql('postgres', database, `SELECT COALESCE(datname,'(none)') AS database, COALESCE(usename,'(none)') AS "user", COALESCE(state,'(none)') AS state, count(*) AS n FROM pg_stat_activity GROUP BY 1,2,3 ORDER BY n DESC LIMIT 30;`)));
  } else {
    out.push(await sec('库容量统计', () => runSql('mysql', null, `SELECT table_schema AS db, COUNT(*) AS tables, ROUND(SUM(data_length)/1048576,2) AS data_mb, ROUND(SUM(index_length)/1048576,2) AS index_mb,
        ROUND(SUM(data_free)/1048576,2) AS free_mb, SUM(table_rows) AS est_rows FROM information_schema.tables GROUP BY table_schema ORDER BY SUM(data_length+index_length) DESC;`)));
    out.push(await sec('表碎片 / 空间回收建议', () => runSql('mysql', database, `SELECT table_name AS name, engine, table_rows AS est_rows, ROUND(data_length/1048576,2) AS data_mb, ROUND(data_free/1048576,2) AS free_mb
        FROM information_schema.tables WHERE table_schema=${lit('mysql', database)} ORDER BY data_free DESC LIMIT 30;`)));
    out.push(await sec('空表 / 小表检测', () => runSql('mysql', database, `SELECT table_name AS name, table_rows AS est_rows, ROUND((data_length+index_length)/1024,1) AS size_kb
        FROM information_schema.tables WHERE table_schema=${lit('mysql', database)} AND table_rows = 0 ORDER BY table_name LIMIT 40;`)));
    out.push(await sec('锁与事务', () => runSql('mysql', null, `SELECT trx_id, trx_state, trx_started, trx_mysql_thread_id AS thread_id, trx_rows_locked AS rows_locked, trx_rows_modified AS rows_modified, LEFT(trx_query,120) AS query FROM information_schema.innodb_trx ORDER BY trx_started LIMIT 40;`)));
    out.push(await sec('活动查询', () => runSql('mysql', null, `SELECT ID AS pid, USER AS user, DB AS db, COMMAND AS state, TIME AS secs, STATE AS info_state, LEFT(INFO,120) AS query FROM information_schema.processlist WHERE COMMAND <> 'Sleep' ORDER BY TIME DESC LIMIT 40;`)));
    out.push(await sec('未使用索引', () => runSql('mysql', null, `SELECT object_schema AS db, object_name AS table, index_name AS idx FROM sys.schema_unused_indexes LIMIT 40;`), '需要 MySQL 8.0+ (sys schema)'));
    out.push(await sec('累积统计 (关键指标)', () => runSql('mysql', null, `SELECT VARIABLE_NAME AS name, VARIABLE_VALUE AS value FROM information_schema.global_status WHERE VARIABLE_NAME IN
        ('Uptime','Threads_connected','Threads_running','Queries','Slow_queries','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Innodb_row_lock_waits','Innodb_deadlocks','Aborted_connects','Created_tmp_disk_tables','Table_locks_waited') ORDER BY name;`)));
    out.push(await sec('InnoDB 缓冲池', () => runSql('mysql', null, `SELECT ROUND(@@innodb_buffer_pool_size/1048576) AS pool_mb, ROUND((SELECT VARIABLE_VALUE FROM information_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_bytes_data')/1048576) AS data_mb, @@innodb_buffer_pool_instances AS instances, @@innodb_flush_log_at_trx_commit AS flush_at_commit;`)));
  }
  return { engine, database, sections: out };
}

/* ================= objects browser ================= */
async function listObjects({ engine, database }) {
  checkEngine(engine);
  const out = [];
  if (engine === 'postgres') {
    out.push(await sec('视图', () => runSql('postgres', database, `SELECT table_schema AS schema, table_name AS name, 'view' AS kind FROM information_schema.views WHERE table_schema NOT IN ('pg_catalog','information_schema') ORDER BY 1,2;`)));
    out.push(await sec('物化视图', () => runSql('postgres', database, `SELECT n.nspname AS schema, c.relname AS name, pg_size_pretty(pg_total_relation_size(c.oid)) AS size, COALESCE(to_char(s.last_analyze,'MM-DD HH24:MI'),'-') AS last_analyze FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_stat_user_tables s ON s.relid=c.oid WHERE c.relkind='m' ORDER BY 1,2;`)));
    out.push(await sec('序列', () => runSql('postgres', database, `SELECT schemaname AS schema, sequencename AS name, last_value, start_value, increment_by, max_value, cycle FROM pg_sequences WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2;`)));
    out.push(await sec('函数 / 存储过程', () => runSql('postgres', database, `SELECT n.nspname AS schema, p.proname AS name, pg_get_function_arguments(p.oid) AS args, pg_get_function_result(p.oid) AS returns, l.lanname AS lang, CASE p.prokind WHEN 'p' THEN 'procedure' ELSE 'function' END AS kind FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND l.lanname <> 'internal' ORDER BY 1,2 LIMIT 200;`)));
    out.push(await sec('触发器', () => runSql('postgres', database, `SELECT n.nspname AS schema, c.relname AS table, t.tgname AS name, pg_get_triggerdef(t.oid) AS def FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT t.tgisinternal ORDER BY 1,2 LIMIT 100;`)));
    out.push(await sec('已安装扩展', () => runSql('postgres', database, `SELECT e.extname AS name, e.extversion AS version, n.nspname AS schema, obj_description(e.oid, 'pg_extension') AS comment FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace ORDER BY 1;`)));
    out.push(await sec('可用扩展(前 40)', () => runSql('postgres', database, `SELECT name, default_version, installed_version, comment FROM pg_available_extensions WHERE name NOT LIKE 'pg_%' OR name IN ('pg_stat_statements','pgcrypto','pg_trgm') ORDER BY name LIMIT 40;`)));
  } else {
    out.push(await sec('视图', () => runSql('mysql', database, `SELECT table_schema AS db, table_name AS name, definer, security_type, '' AS def FROM information_schema.views WHERE table_schema=${lit('mysql', database)} ORDER BY name;`)));
    out.push(await sec('存储过程 / 函数', () => runSql('mysql', database, `SELECT routine_schema AS db, routine_name AS name, routine_type AS kind, definer, data_type AS returns FROM information_schema.routines WHERE routine_schema=${lit('mysql', database)} ORDER BY routine_type, name;`)));
    out.push(await sec('触发器', () => runSql('mysql', database, `SELECT trigger_schema AS db, trigger_name AS name, event_object_table AS table_name, action_timing AS timing, event_manipulation AS event FROM information_schema.triggers WHERE trigger_schema=${lit('mysql', database)} ORDER BY trigger_name;`)));
    out.push(await sec('事件调度', () => runSql('mysql', database, `SELECT event_schema AS db, event_name AS name, event_type AS kind, status, interval_value, interval_field, last_executed FROM information_schema.events WHERE event_schema=${lit('mysql', database)} ORDER BY name;`)));
    out.push(await sec('存储引擎', () => runSql('mysql', null, `SHOW ENGINES;`)));
  }
  return { engine, database, sections: out };
}

/* ================= statements (slow query insights) ================= */
async function statements({ engine, database }) {
  checkEngine(engine);
  if (engine === 'postgres') {
    const has = await runSql('postgres', database, `SELECT count(*) AS n FROM pg_extension WHERE extname='pg_stat_statements';`, { ignoreError: true }).catch(() => ({ rows: [{ n: '0' }] }));
    if (Number((has.rows[0] || {}).n || 0) === 0) {
      return { available: false, hint: '未安装 pg_stat_statements 扩展。安装后需要重启 PostgreSQL 才能生效。', rows: [], columns: [] };
    }
    const r = await sec('慢查询 / 热点语句 (pg_stat_statements)', () => runSql('postgres', database, `SELECT round(total_exec_time) AS total_ms, calls, round(mean_exec_time,2) AS mean_ms, rows AS rows_ret,
        round(100.0*shared_blks_hit/nullif(shared_blks_hit+shared_blks_read,0),1) AS hit_pct, left(regexp_replace(query,'\\s+',' ','g'),180) AS query
        FROM pg_stat_statements WHERE query NOT LIKE '%pg_stat_statements%' ORDER BY total_exec_time DESC LIMIT 40;`));
    return { available: r.rows.length > 0, rows: r.rows, columns: r.columns, section: r, hint: r.error || '' };
  }
  const r = await sec('慢查询 / 热点语句 (sys.statement_analysis)', () => runSql('mysql', null, `SELECT query, exec_count, total_latency, avg_latency, rows_sent_avg, rows_examined_avg FROM sys.statement_analysis ORDER BY total_latency DESC LIMIT 40;`), '需要 MySQL 8.0+ (performance_schema + sys)');
  return { available: r.rows.length > 0, rows: r.rows, columns: r.columns, section: r, hint: r.error || '' };
}

/* ================= DDL form actions ================= */
async function schemaAction({ engine, database, table, action, params = {} }) {
  checkEngine(engine);
  const p = params || {};
  const t = qid(engine, table);
  const col = (n) => qid(engine, n);
  const stmts = [];
  const typeCheck = (ty) => {
    const s = String(ty || '').trim();
    if (!/^[A-Za-z][A-Za-z0-9_ ]*(\([0-9, ]+\))?(\[\])?$/.test(s) || s.length > 60) bad('无效类型: ' + s);
    return s;
  };
  const colList = (arr) => {
    const list = Array.isArray(arr) ? arr : String(arr || '').split(',').map((x) => x.trim());
    const clean = list.filter(Boolean).map(String);
    if (!clean.length) bad('缺少列名');
    return clean.map(col).join(', ');
  };

  switch (action) {
    case 'column-add': {
      if (!p.name) bad('缺少列名');
      let s = `ALTER TABLE ${t} ADD COLUMN ${col(p.name)} ${typeCheck(p.type)}`;
      if (p.nullable === false) s += ' NOT NULL';
      if (p.default) s += ' DEFAULT ' + String(p.default);
      if (engine === 'mysql' && p.after) s += ' AFTER ' + col(p.after);
      stmts.push(s + ';');
      break;
    }
    case 'column-modify': {
      if (!p.name) bad('缺少列名');
      if (engine === 'mysql') {
        let s = `ALTER TABLE ${t} MODIFY COLUMN ${col(p.name)} ${typeCheck(p.type)}`;
        s += p.nullable === false ? ' NOT NULL' : ' NULL';
        if (p.default) s += ' DEFAULT ' + String(p.default);
        if (p.extra) s += ' ' + String(p.extra).replace(/[^A-Za-z_ ]/g, '');
        stmts.push(s + ';');
      } else {
        if (p.type) stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} TYPE ${typeCheck(p.type)};`);
        if (p.default) stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} SET DEFAULT ${String(p.default)};`);
        if (p.dropDefault) stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} DROP DEFAULT;`);
        if (p.nullable !== undefined) stmts.push(`ALTER TABLE ${t} ALTER COLUMN ${col(p.name)} ${p.nullable ? 'DROP NOT NULL' : 'SET NOT NULL'};`);
      }
      if (!stmts.length) bad('没有要修改的内容');
      break;
    }
    case 'column-rename': {
      if (!p.name || !p.newName) bad('缺少列名');
      stmts.push(`ALTER TABLE ${t} RENAME COLUMN ${col(p.name)} TO ${col(p.newName)};`);
      break;
    }
    case 'column-drop': {
      if (!p.name) bad('缺少列名');
      stmts.push(`ALTER TABLE ${t} DROP COLUMN ${col(p.name)}${p.cascade ? ' CASCADE' : ''};`);
      break;
    }
    case 'column-comment': {
      if (!p.name) bad('缺少列名');
      if (engine === 'postgres') stmts.push(`COMMENT ON COLUMN ${t}.${col(p.name)} IS ${lit('postgres', p.comment || '')};`);
      else {
        const sch = await getTableSchema({ engine, database, table });
        const c = sch.columns.find((x) => x.name === p.name);
        if (!c) bad('列不存在');
        stmts.push(`ALTER TABLE ${t} MODIFY COLUMN ${col(p.name)} ${c.type}${String(c.nullable).toUpperCase() === 'NO' ? ' NOT NULL' : ' NULL'}${c.default ? ' DEFAULT ' + c.default : ''} COMMENT ${lit('mysql', p.comment || '')};`);
      }
      break;
    }
    case 'table-comment': {
      if (engine === 'postgres') stmts.push(`COMMENT ON TABLE ${t} IS ${lit('postgres', p.comment || '')};`);
      else stmts.push(`ALTER TABLE ${t} COMMENT = ${lit('mysql', p.comment || '')};`);
      break;
    }
    case 'table-owner': {
      if (!p.owner) bad('缺少所有者');
      stmts.push(`ALTER TABLE ${t} OWNER TO ${col(p.owner)};`);
      break;
    }
    case 'index-create': {
      if (!p.name) bad('缺少索引名');
      const cols = Array.isArray(p.columns) ? p.columns : String(p.columns || '').split(',').map((x) => x.trim());
      if (!cols.filter(Boolean).length) bad('缺少索引列');
      const colExpr = cols.filter(Boolean).map((c) => {
        const m = /^(.+?)\s+(asc|desc)$/i.exec(String(c).trim());
        if (m) return col(m[1]) + ' ' + m[2].toUpperCase();
        return col(String(c).trim());
      }).join(', ');
      const method = (p.method && /^(btree|hash|gin|gist|brin|spgist)$/i.test(p.method)) ? ' USING ' + p.method.toUpperCase() : '';
      stmts.push(`CREATE ${p.unique ? 'UNIQUE ' : ''}INDEX ${col(p.name)} ON ${t}${method} (${colExpr});`);
      break;
    }
    case 'index-drop': {
      if (!p.name) bad('缺少索引名');
      if (engine === 'mysql') stmts.push(`DROP INDEX ${col(p.name)} ON ${t};`);
      else stmts.push(`DROP INDEX ${qid('postgres', (p.schema ? p.schema + '.' : '') + p.name)};`);
      break;
    }
    case 'constraint-add-pk': stmts.push(`ALTER TABLE ${t} ADD PRIMARY KEY (${colList(p.columns)});`); break;
    case 'constraint-add-unique': {
      if (!p.name) bad('缺少约束名');
      stmts.push(`ALTER TABLE ${t} ADD CONSTRAINT ${col(p.name)} UNIQUE (${colList(p.columns)});`);
      break;
    }
    case 'constraint-add-fk': {
      if (!p.name || !p.refTable) bad('缺少外键名或引用表');
      const onDel = /^(CASCADE|SET NULL|SET DEFAULT|RESTRICT|NO ACTION)$/i.test(String(p.onDelete || '')) ? String(p.onDelete).toUpperCase() : 'NO ACTION';
      stmts.push(`ALTER TABLE ${t} ADD CONSTRAINT ${col(p.name)} FOREIGN KEY (${colList(p.columns)}) REFERENCES ${qid(engine, p.refTable)} (${colList(p.refColumns)}) ON DELETE ${onDel};`);
      break;
    }
    case 'constraint-drop': {
      if (!p.name) bad('缺少约束名');
      if (engine === 'mysql') {
        if (p.kind === 'foreign') stmts.push(`ALTER TABLE ${t} DROP FOREIGN KEY ${col(p.name)};`);
        else if (p.kind === 'primary') stmts.push(`ALTER TABLE ${t} DROP PRIMARY KEY;`);
        else stmts.push(`ALTER TABLE ${t} DROP INDEX ${col(p.name)};`);
      } else stmts.push(`ALTER TABLE ${t} DROP CONSTRAINT ${col(p.name)}${p.cascade ? ' CASCADE' : ''};`);
      break;
    }
    case 'table-copy': {
      if (!p.newName) bad('缺少新表名');
      const nt = qid(engine, p.newName);
      if (engine === 'postgres') {
        stmts.push(`CREATE TABLE ${nt} (LIKE ${t} INCLUDING ALL);`);
        if (p.withData) stmts.push(`INSERT INTO ${nt} SELECT * FROM ${t};`);
      } else {
        stmts.push(`CREATE TABLE ${nt} LIKE ${t};`);
        if (p.withData) stmts.push(`INSERT INTO ${nt} SELECT * FROM ${t};`);
      }
      break;
    }
    case 'sequence-restart': {
      if (engine !== 'postgres') bad('序列操作仅 PostgreSQL 支持');
      if (!p.name) bad('缺少序列名');
      const v = parseInt(p.value, 10);
      if (!Number.isFinite(v)) bad('无效数值');
      stmts.push(`ALTER SEQUENCE ${qid('postgres', p.name)} RESTART WITH ${v};`);
      break;
    }
    default: bad('未知表结构操作: ' + action);
  }

  const sql = stmts.join('\n');
  const r = await runSql(engine, database, sql, { timeout: 300000 });
  audit('db', 'schema.' + action, { engine, database, table, params: Object.keys(p) });
  return ok(sql + '\n\n' + ((r.raw || '').trim() || '执行成功'), { sql, columns: r.columns, rows: r.rows });
}

/* ================= database alter ================= */
async function databaseAlter({ engine, database, action, params = {} }) {
  checkEngine(engine);
  const p = params || {};
  const d = qid(engine, database);
  let sql;
  if (action === 'owner') { if (engine !== 'postgres') bad('仅 PostgreSQL 支持修改所有者'); sql = `ALTER DATABASE ${d} OWNER TO ${qid(engine, p.owner)};`; }
  else if (action === 'connlimit') { if (engine !== 'postgres') bad('仅 PostgreSQL 支持连接限制'); sql = `ALTER DATABASE ${d} CONNECTION LIMIT ${parseInt(p.value, 10) || -1};`; }
  else if (action === 'set-param') {
    if (!/^[a-zA-Z0-9_.]+$/.test(String(p.key || ''))) bad('无效参数名');
    sql = `ALTER DATABASE ${d} SET ${p.key} = ${lit(engine, String(p.value))};`;
  }
  else if (action === 'reset-param') { sql = `ALTER DATABASE ${d} RESET ${String(p.key || 'ALL')};`; }
  else if (action === 'rename') { sql = engine === 'postgres' ? `ALTER DATABASE ${d} RENAME TO ${qid(engine, p.to)};` : null; if (!sql) bad('MySQL 不支持重命名数据库'); }
  else bad('未知数据库操作: ' + action);
  await runSql(engine, null, sql, { timeout: 60000 });
  audit('db', 'database.' + action, { engine, database });
  return ok(sql);
}

/* ================= export / import ================= */
function csvField(v) {
  if (v === null || v === undefined) return '';
  const s = String(v);
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}
function toCsv(columns, rows) {
  const lines = [columns.map(csvField).join(',')];
  for (const r of rows) lines.push(columns.map((c) => csvField(r[c])).join(','));
  return lines.join('\n') + '\n';
}
function toSqlInserts(engine, table, columns, rows) {
  const t = qid(engine, table);
  const cols = columns.map((c) => qid(engine, c)).join(', ');
  return rows.map((r) => `INSERT INTO ${t} (${cols}) VALUES (${columns.map((c) => lit(engine, r[c])).join(', ')});`).join('\n') + '\n';
}
async function exportData({ engine, database, sql, format, table, filename }) {
  checkEngine(engine);
  const q = String(sql || '').trim();
  if (!q) bad('缺少查询语句');
  if (!/^\s*(select|with|table|show)\b/i.test(q)) bad('仅支持 SELECT / WITH / TABLE / SHOW 查询导出');
  const r = await runSql(engine, database, q, { timeout: 600000, parse: true });
  if (!r.columns.length) bad('查询没有返回结果集');
  if (r.rows.length > 200000) bad('结果集超过 20 万行，请缩小范围');
  const fmt = ['csv', 'json', 'sql'].includes(format) ? format : 'csv';
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
  const base = String(filename || (table ? 'table-' + table : 'export')).replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 60);
  const ext = { csv: 'csv', json: 'json', sql: 'sql' }[fmt];
  const name = `ex-${engine}-${base}-${stamp}.${ext}`;
  let content;
  if (fmt === 'csv') content = toCsv(r.columns, r.rows);
  else if (fmt === 'json') content = JSON.stringify(r.rows, null, 1) + '\n';
  else content = `-- 导出 ${engine} ${database || ''} ${new Date().toISOString()}\n` + toSqlInserts(engine, table || 'target_table', r.columns, r.rows);
  await run(`mkdir -p ${DB_BACKUP_DIR}`);
  const agent = getAgent();
  await agent.writeFile(DB_BACKUP_DIR + '/' + name, content);
  const size = Buffer.byteLength(content, 'utf8');
  audit('db', 'export', { engine, database, table, format: fmt, rows: r.rows.length, name });
  return ok(`已导出 ${r.rows.length} 行 -> ${name} (${(size / 1024).toFixed(1)} KB)`, { name, size, rows: r.rows.length, columns: r.columns });
}

async function countRows({ engine, database, table, where }) {
  checkEngine(engine);
  const w = safeWhere(where);
  const r = await runSql(engine, database, `SELECT count(*) AS total FROM ${qid(engine, table)}${w ? ' WHERE ' + w : ''};`, { timeout: 600000 });
  const total = r.rows[0] ? Number(r.rows[0].total ?? Object.values(r.rows[0])[0]) : 0;
  return ok(`精确行数: ${total}`, { total });
}

async function importCsv({ engine, database, table, csv, hasHeader, nullEmpty, mode }) {
  checkEngine(engine);
  if (!table) bad('缺少目标表');
  const text = String(csv || '');
  if (!text.trim()) bad('CSV 内容为空');
  if (text.length > 80 * 1024 * 1024) bad('CSV 过大（>80MB），请拆分');
  const matrix = parseCsv(text);
  if (!matrix.length) bad('无法解析 CSV');
  const header = hasHeader === false ? null : matrix[0];
  const dataRows = hasHeader === false ? matrix : matrix.slice(1);
  const sch = await getTableSchema({ engine, database, table });
  const tableCols = sch.columns.map((c) => ({ name: c.name, lower: c.name.toLowerCase(), type: c.type }));
  let cols, map;
  if (header) {
    cols = header.map((h) => {
      const t = tableCols.find((c) => c.lower === String(h || '').trim().toLowerCase());
      return t ? t.name : null;
    }).filter(Boolean);
    map = header.map((h) => {
      const t = tableCols.find((c) => c.lower === String(h || '').trim().toLowerCase());
      return t ? header.indexOf(h) : -1;
    }).filter((i) => i >= 0);
  } else {
    cols = tableCols.slice(0, matrix[0].length).map((c) => c.name);
    map = cols.map((_, i) => i);
  }
  if (!cols.length) bad('无法匹配任何列（请检查 CSV 表头与表结构列名）');
  const CHUNK = 200;
  const statements = [];
  let inserted = 0;
  for (let i = 0; i < dataRows.length; i += CHUNK) {
    const chunk = dataRows.slice(i, i + CHUNK).filter((r) => r.length && !(r.length === 1 && r[0] === null));
    if (!chunk.length) continue;
    const values = chunk.map((r) => '(' + map.map((ci) => {
      let v = r[ci];
      if (v === null) return 'NULL';
      if (typeof v === 'string' && v.trim() === '' && nullEmpty) return 'NULL';
      return lit(engine, v);
    }).join(', ') + ')').join(',\n  ');
    statements.push(`INSERT INTO ${qid(engine, table)} (${cols.map((c) => qid(engine, c)).join(', ')}) VALUES\n  ${values};`);
    inserted += chunk.length;
  }
  if (!statements.length) bad('没有可导入的数据行');
  const sql = (engine === 'mysql' ? 'START TRANSACTION;\n' : 'BEGIN;\n') + statements.join('\n') + '\n' + (engine === 'mysql' ? 'COMMIT;' : 'COMMIT;');
  await runSql(engine, database, sql, { timeout: 1800000 });
  audit('db', 'import', { engine, database, table, rows: inserted, cols });
  return ok(`导入完成：${inserted} 行 -> ${table}\n列映射: ${cols.join(', ')}`, { inserted, cols });
}

/* ================= extensions ================= */
async function createExtension({ engine, name, schema }) {
  if (engine !== 'postgres') bad('扩展管理仅 PostgreSQL 支持');
  if (!/^[a-z0-9_]{1,64}$/.test(String(name || ''))) bad('无效扩展名');
  const sql = `CREATE EXTENSION IF NOT EXISTS ${qid('postgres', name)}${schema ? ' SCHEMA ' + qid('postgres', schema) : ''};`;
  await runSql('postgres', null, sql, { timeout: 120000 });
  audit('db', 'extension.create', { name });
  const note = name === 'pg_stat_statements'
    ? '\n提示：pg_stat_statements 需要 shared_preload_libraries 包含它并重启 PostgreSQL 才会统计。可在终端执行：\n  ALTER SYSTEM SET shared_preload_libraries = \'pg_stat_statements\'; 然后重启数据库。'
    : '';
  return ok(sql + note);
}

/* ================= backup extras ================= */
async function cleanupBackups({ keep }) {
  const k = Math.max(1, Math.min(200, parseInt(keep, 10) || 10));
  const r = await run(`ls -1t ${DB_BACKUP_DIR}/db-* 2>/dev/null | tail -n +${k + 1}`, { timeout: 20000 });
  const files = String(r.stdout).split('\n').map((s) => s.trim()).filter(Boolean);
  if (!files.length) return ok('没有需要清理的备份');
  for (const f of files) {
    const n = f.split('/').pop();
    if (/^db-[A-Za-z0-9._-]+\.(sql\.gz|dump|sql)$/.test(n)) await run(`rm -f ${DB_BACKUP_DIR}/${n}`);
  }
  audit('db', 'backup.cleanup', { keep: k, removed: files.length });
  return ok(`已清理 ${files.length} 个旧备份，保留最近 ${k} 个`);
}

/* generic file path resolver: db-* backups, up-* uploads, ex-* exports */
function dbFilePath(name) {
  const n = String(name || '');
  if (n.includes('..') || n.includes('/')) return null;
  if (/^db-[A-Za-z0-9._-]+\.(sql\.gz|dump|sql|rdb|zip)$/.test(n)) return DB_BACKUP_DIR + '/' + n;
  if (/^up-[A-Za-z0-9._-]+\.(sql|gz|csv|dump|rdb|zip)$/.test(n)) return UPLOAD_DIR + '/' + n;
  if (/^ex-[A-Za-z0-9._-]+\.(csv|json|sql)$/.test(n)) return DB_BACKUP_DIR + '/' + n;
  return null;
}

/* ================= 高级功能 ================= */

/* ---- 模式(命名空间) ---- */
async function listSchemas({ engine, database }) {
  checkEngine(engine);
  if (engine === 'postgres') {
    const r = await runSql('postgres', database, `SELECT n.nspname AS name, pg_get_userbyid(n.nspowner) AS owner,
        (SELECT count(*) FROM pg_class c WHERE c.relnamespace=n.oid AND c.relkind IN ('r','p')) AS tables,
        (SELECT count(*) FROM pg_class c WHERE c.relnamespace=n.oid AND c.relkind IN ('v','m')) AS views,
        (SELECT count(*) FROM pg_proc p WHERE p.pronamespace=n.oid) AS functions,
        pg_size_pretty((SELECT COALESCE(SUM(pg_total_relation_size(c.oid)),0) FROM pg_class c WHERE c.relnamespace=n.oid AND c.relkind IN ('r','p'))) AS size,
        n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%' AS user_schema
      FROM pg_namespace n ORDER BY 7 DESC, 1;`);
    return { rows: r.rows };
  }
  const r = await runSql('mysql', null, `SELECT s.SCHEMA_NAME AS name, s.DEFAULT_CHARACTER_SET_NAME AS charset, s.DEFAULT_COLLATION_NAME AS collation,
      (SELECT count(*) FROM information_schema.TABLES t WHERE t.TABLE_SCHEMA=s.SCHEMA_NAME) AS tables,
      ROUND(COALESCE((SELECT SUM(data_length+index_length) FROM information_schema.TABLES t WHERE t.TABLE_SCHEMA=s.SCHEMA_NAME),0)/1048576,2) AS size_mb
    FROM information_schema.SCHEMATA s ORDER BY s.SCHEMA_NAME;`);
  return { rows: r.rows.map((x) => ({ name: x.name, owner: '-', tables: x.tables, views: null, functions: null, size: (x.size_mb || 0) + ' MB', user_schema: true, charset: x.charset, collation: x.collation })) };
}

async function schemaManage({ engine, database, action, name, owner }) {
  checkEngine(engine);
  if (engine !== 'postgres') bad('模式管理仅 PostgreSQL 支持（MySQL 请使用数据库管理）');
  const n = String(name || '');
  if (!/^[A-Za-z_][\w$]{0,62}$/.test(n)) bad('无效模式名');
  if (['pg_catalog', 'information_schema', 'public'].includes(n) && action === 'drop') bad('系统模式不可删除');
  let sql;
  if (action === 'create') sql = `CREATE SCHEMA IF NOT EXISTS ${qid('postgres', n)}${owner ? ' AUTHORIZATION ' + qid('postgres', owner) : ''};`;
  else if (action === 'drop') sql = `DROP SCHEMA ${qid('postgres', n)} ${'CASCADE'};`;
  else if (action === 'rename') { if (!owner) bad('缺少新名称'); sql = `ALTER SCHEMA ${qid('postgres', n)} RENAME TO ${qid('postgres', owner)};`; }
  else bad('未知模式操作: ' + action);
  await runSql('postgres', database, sql, { timeout: 120000 });
  audit('db', 'schema.' + action, { database, name });
  return ok(sql);
}

/* ---- 表存储与活动明细 ---- */
async function tableStorage({ engine, database, table }) {
  checkEngine(engine);
  const raw = String(table == null ? '' : table).trim();
  if (!raw) bad('缺少表名');
  if (raw.length > 200) bad('表名过长');
  if (engine === 'postgres') {
    const parts = raw.split('.');
    const tbl = parts.pop();
    const schema = parts.length ? parts.pop() : 'public';
    if (!schema || !tbl) bad('非法表名: ' + raw.slice(0, 60));
    const size = await runSql('postgres', database, `SELECT
        c.relkind AS kind,
        pg_size_pretty(pg_total_relation_size(c.oid)) AS total,
        pg_size_pretty(pg_relation_size(c.oid)) AS heap,
        pg_size_pretty(pg_indexes_size(c.oid)) AS indexes,
        pg_size_pretty(COALESCE(pg_total_relation_size(c.reltoastrelid),0)) AS toast,
        pg_total_relation_size(c.oid) AS total_bytes,
        c.reltuples::bigint AS est_rows,
        pg_size_pretty((SELECT COALESCE(SUM(pg_relation_size(i.indexrelid)),0) FROM pg_index i WHERE i.indrelid=c.oid)) AS index_bytes_pretty
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=${lit('postgres', schema)} AND c.relname=${lit('postgres', tbl)};`);
    if (!(size.rows || []).length) bad('表不存在: ' + schema + '.' + tbl);
    const kind = String((size.rows[0] || {}).kind || '');
    if (['v', 'm'].indexOf(kind) >= 0) bad(`${schema}.${tbl} 是${kind === 'v' ? '视图' : '物化视图'}，没有表级存储明细`);
    if (!'rpf'.includes(kind)) bad(`${schema}.${tbl} 不是普通表（relkind=${kind}）`);
    const act = await runSql('postgres', database, `SELECT seq_scan, seq_tup_read, idx_scan, idx_tup_fetch,
        n_tup_ins, n_tup_upd, n_tup_del, n_tup_hot_upd, n_live_tup, n_dead_tup, n_mod_since_analyze,
        COALESCE(to_char(last_vacuum,'MM-DD HH24:MI'),'-') AS last_vacuum,
        COALESCE(to_char(last_autovacuum,'MM-DD HH24:MI'),'-') AS last_autovacuum,
        COALESCE(to_char(last_analyze,'MM-DD HH24:MI'),'-') AS last_analyze,
        COALESCE(to_char(last_autoanalyze,'MM-DD HH24:MI'),'-') AS last_autoanalyze,
        vacuum_count, autovacuum_count, analyze_count, autoanalyze_count
      FROM pg_stat_user_tables WHERE schemaname=${lit('postgres', schema)} AND relname=${lit('postgres', tbl)};`);
    const idx = await runSql('postgres', database, `SELECT indexrelname AS name, idx_scan AS scans, idx_tup_read AS tup_read, idx_tup_fetch AS tup_fetch,
        pg_size_pretty(pg_relation_size(indexrelid)) AS size,
        (SELECT indexdef FROM pg_indexes pi WHERE pi.schemaname=${lit('postgres', schema)} AND pi.indexname=i.indexrelname LIMIT 1) AS def
      FROM pg_stat_user_indexes i WHERE schemaname=${lit('postgres', schema)} AND relname=${lit('postgres', tbl)} ORDER BY pg_relation_size(indexrelid) DESC;`);
    return { size: (size.rows[0] || {}), activity: (act.rows[0] || {}), indexes: idx.rows };
  }
  const r = await runSql('mysql', database, `SELECT table_name AS name, engine, table_rows AS est_rows,
      ROUND(data_length/1048576,2) AS data_mb, ROUND(index_length/1048576,2) AS index_mb, ROUND(data_free/1048576,2) AS free_mb,
      ROUND((data_length+index_length)/1048576,2) AS total_mb, avg_row_length AS avg_row, auto_increment,
      table_collation AS collation, create_time, update_time, table_comment AS comment
    FROM information_schema.TABLES WHERE table_schema=${lit('mysql', database)} AND table_name=${lit('mysql', table)};`);
  const st = await runSql('mysql', database, `SHOW INDEX FROM ${qid('mysql', table)};`).catch(() => ({ rows: [] }));
  return {
    size: r.rows[0] || {},
    activity: { note: 'MySQL 不提供每表扫描统计（可用 performance_schema 开启 table_io_waits / table_lock_waits）' },
    indexes: (st.rows || []).map((x) => ({ name: x.Key_name, scans: null, tup_read: null, size: '-', def: '列 ' + x.Column_name + (x.Non_unique === '0' ? ' (唯一)' : '') })),
  };
}

/* ---- 缺失索引建议 (PG) ---- */
async function missingIndexes({ engine, database }) {
  checkEngine(engine);
  if (engine !== 'postgres') {
    const r = await runSql('mysql', null, `SELECT object_schema AS schema, object_name AS name, index_name AS idx FROM sys.schema_unused_indexes LIMIT 50;`).catch(() => ({ rows: [] }));
    return { rows: [], unused: r.rows, note: 'MySQL 使用 sys.schema_unused_indexes 展示未被使用的索引' };
  }
  const r = await runSql('postgres', database, `SELECT schemaname AS schema, relname AS name, seq_scan, seq_tup_read,
      COALESCE(idx_scan,0) AS idx_scan, n_live_tup AS rows, n_dead_tup AS dead,
      pg_size_pretty(pg_total_relation_size(relid)) AS size,
      round(seq_tup_read::numeric / GREATEST(seq_scan,1)) AS avg_rows_per_scan,
      (SELECT string_agg(a.attname, ', ' ORDER BY a.attnum) FROM pg_attribute a
         WHERE a.attrelid=relid AND a.attnum>0 AND NOT a.attisdropped AND a.atttypid IN (23,25,1043,20,16,1082,1114,1700)) AS candidate_cols
    FROM pg_stat_user_tables
    WHERE seq_scan > 20 AND n_live_tup > 500 AND (COALESCE(idx_scan,0) = 0 OR seq_scan > COALESCE(idx_scan,0) * 3)
    ORDER BY seq_tup_read DESC LIMIT 30;`);
  return { rows: r.rows, note: '序列扫描多且几乎不走索引的表 → 考虑对常用 WHERE/JOIN 列建索引' };
}

/* ---- 数据值搜索 ---- */
const SEARCH_DEADLINE_MS = 45000;

async function searchValue({ engine, database, table, value, limit }) {
  checkEngine(engine);
  const v = String(value == null ? '' : value);
  if (!v || v.length > 200) bad('请输入 1-200 字符的搜索值');
  const lim = Math.max(1, Math.min(200, parseInt(limit, 10) || 50));
  /* 转义 LIKE 元字符：用 '!' 作转义符（PG/MySQL 均支持 ESCAPE '!'，避开反斜杠差异） */
  const like = '%' + v.replace(/([!%_])/g, '!$1') + '%';
  let tables = [];
  if (table) tables = [String(table)];
  else {
    const t = await listTables({ engine, database });
    tables = t.filter((x) => x.kind === 'table').slice(0, 60).map((x) => x.schema + '.' + x.name);
  }
  const out = [];
  const errors = [];
  const skipped = [];
  const deadline = Date.now() + SEARCH_DEADLINE_MS;
  for (const t of tables) {
    if (Date.now() > deadline) { skipped.push({ table: '(未扫描)', reason: '超过 ' + SEARCH_DEADLINE_MS / 1000 + ' 秒扫描上限' }); break; }
    try {
      const sch = await getTableSchema({ engine, database, table: t });
      const textCols = sch.columns.filter((c) => /char|text|uuid|json|inet|citext/i.test(String(c.type)) || /text|char|uuid|json/i.test(String(c.base_type)));
      if (!textCols.length) {
        /* 显式指定表时，"没有文本列"通常意味着表根本不存在，必须报错而不是伪装成无结果 */
        if (table) throw new Error('表不存在，或该表没有可搜索的文本列: ' + t);
        continue;
      }
      const cond = textCols.map((c) => engine === 'mysql'
        ? `\`${c.name}\` LIKE ${lit('mysql', like)} ESCAPE '!'`
        : `"${c.name}"::text ILIKE ${lit('postgres', like)} ESCAPE '!'`).join(' OR ');
      const r = await runSql(engine, database, `SELECT count(*) AS n FROM ${qid(engine, t)} WHERE ${cond};`, { timeout: 30000 });
      const n = r.rows[0] ? Number(r.rows[0].n ?? 0) : 0;
      if (n > 0) {
        const rows = await runSql(engine, database, `SELECT * FROM ${qid(engine, t)} WHERE ${cond} LIMIT ${lim};`, { timeout: 30000 });
        out.push({ table: t, matched: n, columns: rows.columns, rows: rows.rows.slice(0, Math.min(lim, 20)) });
      }
    } catch (e) {
      /* 故障必须可见：不能把"表不存在/无权限/连接失败"伪装成"无结果" */
      errors.push({ table: t, error: String(e.message || e).replace(/\s+/g, ' ').slice(0, 200) });
    }
    if (out.length >= 15) { skipped.push({ table: '(未扫描)', reason: '已命中 15 张表，达到展示上限' }); break; }
  }
  /* 显式指定表时，出错必须直接报错 */
  if (table && errors.length) throw Object.assign(new Error('搜索失败: ' + errors[0].error), { status: 400 });
  audit('db', 'search', { engine, database, table: table || '(all)', value: v.slice(0, 40), hits: out.length, errors: errors.length });
  return { value: v, scanned: tables.length, hits: out, errors, skipped, truncated: skipped.length > 0 };
}

/* ---- EXPLAIN 可视化 ---- */
const EXPLAIN_MAX_INFLIGHT = 2;
let explainInflight = 0;

async function explainTree({ engine, database, sql, analyze }) {
  checkEngine(engine);
  const q = assertReadOnlySingleStatement(sql, 'SQL');
  if (explainInflight >= EXPLAIN_MAX_INFLIGHT) {
    throw Object.assign(new Error('执行计划请求过多，请等待当前分析完成后再试（同时最多 ' + EXPLAIN_MAX_INFLIGHT + ' 个）'), { status: 429 });
  }
  explainInflight++;
  try {
    return await explainTreeInner({ engine, database, q, analyze: !!analyze });
  } finally {
    explainInflight--;
  }
}

async function explainTreeInner({ engine, database, q, analyze }) {
  const timeout = analyze ? 180000 : 60000;
  if (engine === 'postgres') {
    const flags = analyze ? 'ANALYZE, BUFFERS, VERBOSE, FORMAT JSON' : 'VERBOSE, FORMAT JSON';
    const r = await runSql('postgres', database, `EXPLAIN (${flags}) ${q};`, { timeout, parse: false, noCsv: true });
    let plan = null;
    try { plan = JSON.parse(r.raw); } catch (_) { plan = null; }
    if (!plan) bad('无法解析执行计划:\n' + String(r.raw || '').slice(0, 800));
    const root = plan[0];
    const flat = [];
    const walk = (node, depth, parent) => {
      const info = {
        depth, parent,
        node: node['Node Type'] + (node['Join Type'] ? ' (' + node['Join Type'] + ')' : '') + (node['Index Name'] ? ' [' + node['Index Name'] + ']' : ''),
        relation: node['Relation Name'] ? node['Schema'] + '.' + node['Relation Name'] : (node['Index Name'] || ''),
        cost: node['Total Cost'], startup: node['Startup Cost'], rows: node['Plan Rows'], width: node['Plan Width'],
        actual_rows: node['Actual Rows'], actual_time: node['Actual Total Time'], loops: node['Actual Loops'],
        rows_removed: node['Rows Removed by Filter'] || node['Rows Removed by Join Filter'],
        filter: node['Filter'] || node['Index Cond'] || node['Hash Cond'] || node['Join Filter'] || node['Recheck Cond'] || '',
        sort: node['Sort Key'] ? String(node['Sort Key']).slice(0, 120) : '',
        parallel: !!node['Parallel Aware'], detail: '',
      };
      const biggest = (node['Plan Rows'] || 0);
      info.detail = 'cost=' + Number(node['Startup Cost'] || 0).toFixed(1) + '..' + Number(node['Total Cost'] || 0).toFixed(1) +
        ' rows=' + info.rows + ' width=' + info.width +
        (analyze ? ' | 实际 ' + info.actual_rows + ' 行 / ' + Number(info.actual_time || 0).toFixed(2) + 'ms ×' + info.loops : '');
      flat.push(Object.assign({ id: flat.length, biggest }, info));
      (node['Plans'] || []).forEach((child) => walk(child, depth + 1, flat[flat.length - 1].id));
    };
    walk(root.Plan, 0, null);
    const total = root['Plan'] ? root['Plan']['Actual Total Time'] || root['Execution Time'] : null;
    return { engine, analyze: !!analyze, execution_ms: root['Execution Time'], planning_ms: root['Planning Time'], nodes: flat, raw: JSON.stringify(plan[0]).slice(0, 20000) };
  }
  /* MySQL: EXPLAIN ANALYZE 只支持 TREE 文本格式，不能与 FORMAT=JSON 组合 */
  const planRes = await runSql('mysql', database, `EXPLAIN FORMAT=JSON ${q};`, { timeout, parse: false, noCsv: true });
  let plan = null;
  try { const j = JSON.parse(planRes.raw); plan = j[0] ? j[0].EXPLAIN : null; } catch (_) {}
  if (!plan) bad('无法解析执行计划:\n' + String(planRes.raw || '').slice(0, 800));
  let analyzeText = '';
  if (analyze) {
    const ar = await runSql('mysql', database, `EXPLAIN ANALYZE ${q};`, { timeout, parse: false, noCsv: true }).catch(() => ({ raw: '' }));
    analyzeText = String(ar.raw || '').slice(0, 8000);
  }
  const flat = [];
  const walk = (node, depth) => {
    if (!node) return;
    if (node.table_name) {
      flat.push({ depth, id: flat.length, node: (node.access_type || 'table') + ' [' + node.table_name + ']',
        relation: node.table_name, rows: node.rows_examined_per_scan, cost: node.cost_info ? node.cost_info.query_cost : null,
        detail: 'type=' + node.access_type + ' rows=' + node.rows_examined_per_scan + (node.used_key_parts ? ' key=' + JSON.stringify(node.used_key_parts) : '') + (node.attached_condition ? ' cond=' + String(node.attached_condition).slice(0, 80) : '') });
    }
    Object.keys(node).forEach((k) => {
      const v = node[k];
      if (Array.isArray(v)) v.forEach((c) => walk(c, depth + 1));
      else if (v && typeof v === 'object' && (v.nested_loop || v.table_name || v.grouping_operation || v.ordering_operation)) walk(v, depth + 1);
    });
  };
  walk(plan, 0);
  return { engine, analyze: !!analyze, nodes: flat, analyze_text: analyzeText, raw: String(planRes.raw).slice(0, 20000) };
}

/* ---- 批量表操作 ---- */
function bulkStmt(engine, t, action) {
  const id = qid(engine, t);
  switch (action) {
    case 'truncate': return `TRUNCATE TABLE ${id}${engine === 'postgres' ? ' RESTART IDENTITY' : ''};`;
    case 'drop': return `DROP TABLE ${id};`;
    case 'analyze': return engine === 'postgres' ? `ANALYZE ${id};` : `ANALYZE TABLE ${id};`;
    case 'vacuum': return `VACUUM ${id};`;
    case 'vacuum-full': return `VACUUM FULL ${id};`;
    case 'reindex': return engine === 'postgres' ? `REINDEX TABLE ${id};` : `ALTER TABLE ${id} ENGINE=InnoDB;`;
    case 'optimize': return `OPTIMIZE TABLE ${id};`;
    case 'check': return `CHECK TABLE ${id};`;
    case 'repair': return `REPAIR TABLE ${id};`;
    default: bad('未知批量操作: ' + action);
  }
}

async function bulkTableAction({ engine, database, tables, action }) {
  checkEngine(engine);
  const list = (Array.isArray(tables) ? tables : String(tables || '').split(/[\s,]+/)).map((x) => String(x).trim()).filter(Boolean);
  if (!list.length) bad('请选择至少一张表');
  if (list.length > 200) bad('一次最多 200 张表');
  for (const t of list) if (!/^[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)?$/.test(t)) bad('非法表名: ' + t);
  const stmts = list.map((t) => bulkStmt(engine, t, action));
  /* 逐条执行（并发 4）：VACUUM/CREATE INDEX 等不可在事务块内运行，
     且必须逐条捕获错误，绝不能像批处理那样把全部失败报告成成功 */
  const CONCURRENCY = 4;
  const results = new Array(list.length);
  let cursor = 0;
  const worker = async () => {
    for (;;) {
      const i = cursor++;
      if (i >= list.length) return;
      try {
        const r = await runSql(engine, database, stmts[i], { timeout: 1800000 });
        results[i] = { table: list[i], ok: true, out: String(r.raw || '').trim().slice(0, 300) };
      } catch (e) {
        results[i] = { table: list[i], ok: false, err: String(e.message || e).replace(/\s+/g, ' ').slice(0, 300) };
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, list.length) }, worker));
  const succ = results.filter((r) => r.ok);
  const fail = results.filter((r) => !r.ok);
  audit('db', 'bulk.' + action, { engine, database, total: list.length, success: succ.length, failed: fail.length });
  const detail = fail.length ? '\n失败明细:\n' + fail.slice(0, 25).map((f) => '  ' + f.table + ' — ' + f.err).join('\n') + (fail.length > 25 ? '\n  …另有 ' + (fail.length - 25) + ' 条失败' : '') : '';
  if (!succ.length) {
    throw Object.assign(new Error(`${action} 全部失败（${fail.length} 张表）:\n` + fail.slice(0, 10).map((f) => f.table + ' — ' + f.err).join('\n')), { status: 400 });
  }
  return ok(`成功 ${succ.length} / ${list.length} 张表${fail.length ? '，失败 ' + fail.length + ' 张' : ''}${detail}`,
    { total: list.length, success: succ.length, failed: fail.length, results });
}

/* ---- 跨库复制表 ---- */
async function copyTableToDb({ engine, database, table, targetDb, newName, withData }) {
  checkEngine(engine);
  if (!targetDb) bad('缺少目标数据库');
  const t = String(table == null ? '' : table).trim();
  if (!t) bad('缺少表名');
  const plain = t.includes('.') ? t.split('.').pop() : t;
  const nt = (newName && String(newName).trim()) ? String(newName).trim() : plain;
  if (!/^[A-Za-z_][\w$]*$/.test(nt)) bad('无效目标表名');
  if (nt === plain && database === targetDb) bad('源库与目标库相同，且未指定新的目标表名');
  /* 目标库必须存在 */
  const ex = await runSql(engine, null, engine === 'postgres'
    ? `SELECT 1 FROM pg_database WHERE datname=${lit('postgres', targetDb)};`
    : `SELECT 1 FROM information_schema.schemata WHERE schema_name=${lit('mysql', targetDb)};`, { ignoreError: true }).catch(() => ({ rows: [] }));
  if (!ex.rows.length) bad('目标数据库不存在: ' + targetDb);
  let cmd;
  if (engine === 'postgres') {
    /* pg_dump -t schema.tbl 的输出不含 CREATE SCHEMA，目标库需先建好该模式 */
    const parts = t.split('.');
    if (parts.length >= 2) {
      const schema = parts[parts.length - 2];
      await runSql('postgres', targetDb, `CREATE SCHEMA IF NOT EXISTS ${qid('postgres', schema)};`, { timeout: 60000 }).catch(() => {});
    }
    const dump = `pg_dump -Fp ${withData ? '' : '-s'} -t ${shq(t)} -d ${shq(database)}`;
    cmd = `set -o pipefail; su - postgres -c ${shq(dump)} | su - postgres -c ${shq(`psql -X -q -v ON_ERROR_STOP=1 -d ${shq(targetDb)} -f -`)}`;
  } else {
    cmd = `set -o pipefail; mysqldump ${withData ? '' : '--no-data'} ${shq(database)} ${shq(plain)} | mysql ${shq(targetDb)}`;
  }
  const r = await run(cmd, { timeout: 1800000, maxBuffer: 16 * 1024 * 1024 });
  const out = ((r.stderr || '') + (r.stdout || '')).trim();
  const errs = out.split('\n').filter((l) => /error|ERROR|already exists/i.test(l));
  if (r.code !== 0 || errs.length) {
    throw Object.assign(new Error('复制失败:\n' + (errs.join('\n') || out).slice(0, 1200) + (errs.some((l) => /already exists/i.test(l)) ? '\n（目标库中已存在同名表，可填写新的目标表名）' : '')), { status: 400 });
  }
  /* 需要重命名时再执行 ALTER / RENAME（必须带原模式，否则解析不到 app.victim） */
  let renamed = false;
  if (nt !== plain) {
    const rs = engine === 'postgres'
      ? `ALTER TABLE ${qid('postgres', t)} RENAME TO ${qid('postgres', nt)};`
      : `RENAME TABLE ${qid('mysql', plain)} TO ${qid('mysql', nt)};`;
    await runSql(engine, targetDb, rs, { timeout: 60000 });
    renamed = true;
  }
  audit('db', 'table.copyDb', { engine, from: database, to: targetDb, table: plain, as: nt, withData: !!withData });
  return ok(`已复制 ${database}.${plain} → ${targetDb}.${nt}${withData ? '（含数据）' : '（仅结构）'}${renamed ? ' [已重命名]' : ''}`, { table: nt, renamed });
}

/* ---- 单表备份 ---- */
async function tableBackup({ engine, database, table }) {
  checkEngine(engine);
  const t = String(table == null ? '' : table).trim();
  if (!t) bad('缺少表名');
  if (t.length > 200 || !/^[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)?$/.test(t)) bad('非法表名: ' + t.slice(0, 60));
  const plain = t.includes('.') ? t.split('.').pop() : t;
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 16);
  const name = `db-${engine}-${plain}-table-${stamp}.sql.gz`;
  const outFile = `${DB_BACKUP_DIR}/${name}`;
  /* pipefail 必需：否则管道退出码取自 gzip，pg_dump 失败也会"成功"并留下垃圾文件 */
  const dump = engine === 'postgres'
    ? `su - postgres -c ${shq(`pg_dump -Fp -t ${shq(t)} -d ${shq(database)}`)} | gzip -1 > ${outFile}`
    : `mysqldump --single-transaction ${shq(database)} ${shq(plain)} | gzip -1 > ${outFile}`;
  const cmd = `set -o pipefail; mkdir -p ${DB_BACKUP_DIR} && ${dump} && gzip -t ${outFile}`;
  const r = await run(cmd, { timeout: 1800000 });
  const st = await run(`ls -l ${outFile} 2>/dev/null | awk '{print $5}'`);
  const size = parseInt(String(st.stdout || '').trim(), 10) || 0;
  if (r.code !== 0 || size < 100) {
    await run(`rm -f ${outFile}`).catch(() => {});
    const why = r.code !== 0 ? String((r.stderr || r.stdout || '').trim()).replace(/\s+/g, ' ').slice(0, 500) : `产物仅 ${size} 字节，判定为无效备份`;
    throw Object.assign(new Error('单表备份失败: ' + why), { status: 400 });
  }
  audit('db', 'backup.table', { engine, database, table: plain, name, size });
  return ok(`单表备份完成: ${name} (${(size / 1024).toFixed(1)} KB)`, { name, size });
}

/* ---- 序列 / 视图 / 触发器管理 ---- */
async function sequenceAction({ engine, database, action, params = {} }) {
  if (engine !== 'postgres') bad('序列管理仅 PostgreSQL 支持');
  const p = params || {};
  const name = String(p.name || '');
  if (!/^[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)?$/.test(name)) bad('无效序列名');
  const id = qid('postgres', name);
  let sql;
  /* 严格整数校验：parseInt 会把 '5abc' 静默截断成 5 */
  const num = (v, label) => {
    if (v === undefined || v === null || v === '') return null;
    const s = String(v).trim();
    if (!/^-?\d+$/.test(s)) bad(`${label} 必须为整数，收到: ${s.slice(0, 24)}`);
    const n = parseInt(s, 10);
    if (!Number.isFinite(n)) bad(`${label} 数值无效`);
    return n;
  };
  const opt = (v, label, clause) => { const n = num(v, label); return n === null ? '' : ' ' + clause + ' ' + n; };
  if (action === 'create') sql = `CREATE SEQUENCE ${id}${opt(p.start, '起始值', 'START WITH')}${opt(p.increment, '步长', 'INCREMENT BY')}${opt(p.minvalue, '最小值', 'MINVALUE')}${opt(p.maxvalue, '最大值', 'MAXVALUE')}${p.cycle ? ' CYCLE' : ''};`;
  else if (action === 'alter') sql = `ALTER SEQUENCE ${id}${opt(p.increment, '步长', 'INCREMENT BY')}${opt(p.restart, 'RESTART 值', 'RESTART WITH')};`;
  else if (action === 'drop') sql = `DROP SEQUENCE ${id};`;
  else if (action === 'nextval') sql = `SELECT nextval(${lit('postgres', name)}) AS next_value;`;
  else if (action === 'setval') {
    const v = num(p.value, '设值');
    if (v === null) bad('缺少设值');
    sql = `SELECT setval(${lit('postgres', name)}, ${v}, ${p.called === false || p.called === 'false' ? 'false' : 'true'}) AS set_value;`;
  } else bad('未知序列操作: ' + action);
  const r = await runSql('postgres', database, sql, { timeout: 120000 });
  audit('db', 'sequence.' + action, { database, name });
  return ok(sql + '\n' + (r.raw ? r.raw.trim() : 'OK'));
}

async function objectManage({ engine, database, kind, action, params = {} }) {
  checkEngine(engine);
  const p = params || {};
  let sql = null;
  if (kind === 'view') {
    const name = String(p.name || '');
    if (!/^[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)?$/.test(name)) bad('无效视图名');
    if (action === 'create') { if (!p.sql) bad('缺少 SELECT 语句'); sql = `CREATE OR REPLACE VIEW ${qid(engine, name)} AS ${String(p.sql).replace(/;+\s*$/, '')};`; }
    else if (action === 'drop') sql = `DROP VIEW ${qid(engine, name)};`;
  } else if (kind === 'trigger') {
    const name = String(p.name || '');
    if (!/^[A-Za-z_][\w$]*$/.test(name)) bad('无效触发器名');
    if (!p.table) bad('缺少表名');
    if (action === 'drop') {
      sql = engine === 'postgres' ? `DROP TRIGGER ${qid(engine, name)} ON ${qid(engine, p.table)};` : `DROP TRIGGER ${qid(engine, name)};`;
    } else if (action === 'enable' || action === 'disable') {
      if (engine !== 'postgres') bad('触发器启停仅 PostgreSQL 支持');
      sql = `ALTER TABLE ${qid(engine, p.table)} ${action === 'enable' ? 'ENABLE' : 'DISABLE'} TRIGGER ${qid(engine, name)};`;
    } else if (action === 'create') {
      const timing = /^(before|after|instead of)$/i.test(p.timing || '') ? String(p.timing).toUpperCase() : 'AFTER';
      const events = (Array.isArray(p.events) && p.events.length ? p.events : ['INSERT']).map((x) => String(x).toUpperCase()).filter((x) => /^(INSERT|UPDATE|DELETE|TRUNCATE)$/.test(x)).join(' OR ');
      if (!events) bad('缺少事件类型');
      if (engine === 'postgres') {
        if (!p.function) bad('缺少触发的函数名');
        const forEach = /^(row|statement)$/i.test(p.forEach || '') ? String(p.forEach).toUpperCase() : 'ROW';
        sql = `CREATE TRIGGER ${qid(engine, name)} ${timing} ${events} ON ${qid(engine, p.table)} FOR EACH ${forEach} EXECUTE FUNCTION ${qid(engine, p.function)}();`;
      } else {
        if (!p.body) bad('缺少触发器体（BEGIN ... END）');
        const timingM = /^(before|after)$/i.test(p.timing || '') ? String(p.timing).toUpperCase() : 'AFTER';
        const body = String(p.body).trim();
        if (/[\r]/.test(body)) bad('触发器体不允许换行符 CR');
        sql = `CREATE TRIGGER ${qid(engine, name)} ${timingM} ${events.split(' OR ')[0]} ON ${qid(engine, p.table)} FOR EACH ROW ${body}`;
      }
    }
  }
  if (!sql) bad('未知对象操作: ' + kind + '/' + action);
  const r = await runSql(engine, database, sql, { timeout: 120000 });
  audit('db', kind + '.' + action, { database, name: p.name });
  return ok(sql + '\n' + (r.raw ? r.raw.trim() : 'OK'));
}

/* ---- 会话批量清理 ---- */
const SESSION_KILL_CAP = 200;

async function sessionManage({ engine, database, action, filter = {} }) {
  checkEngine(engine);
  if (action !== 'kill') bad('仅支持 kill');
  const f = Object.assign({}, filter || {});
  /* 严格校验时长：非法值必须报错，不能静默降级为"不过滤"（否则等于放开杀全部） */
  let minSeconds = 0;
  if (f.minSeconds !== undefined && f.minSeconds !== null && f.minSeconds !== '') {
    const n = Number(f.minSeconds);
    if (!Number.isFinite(n) || !Number.isInteger(n) || n <= 0) bad('时长必须为正整数秒');
    minSeconds = n;
  }
  /* 必须至少命中一个筛选维度，否则等同于"杀掉所有匹配会话" */
  if (!f.database && !f.user && !minSeconds) bad('请至少选择一个筛选条件（数据库 / 用户 / 时长），避免误终止全部会话');
  if (engine === 'postgres') {
    const conds = ['pid <> pg_backend_pid()'];
    if (f.state === 'idle') conds.push("state = 'idle'");
    else if (f.state === 'active') conds.push("state = 'active'");
    else if (f.state === 'idle_in_transaction') conds.push("state = 'idle in transaction'");
    if (f.database) conds.push('datname = ' + lit('postgres', String(f.database)));
    if (f.user) conds.push('usename = ' + lit('postgres', String(f.user)));
    if (minSeconds) conds.push(`COALESCE(extract(epoch from now()-query_start),0) > ${minSeconds}`);
    const where = conds.join(' AND ');
    const cnt = await runSql('postgres', null, `SELECT count(*) AS n FROM pg_stat_activity WHERE ${where};`, { timeout: 60000 });
    const n = Number((cnt.rows[0] || {}).n || 0);
    if (!n) return ok('没有匹配的会话', { killed: 0 });
    if (n > SESSION_KILL_CAP) bad(`匹配到 ${n} 个会话，超过安全上限 ${SESSION_KILL_CAP}，请缩小筛选范围`);
    const r = await runSql('postgres', null, `SELECT count(*) AS killed FROM (SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE ${where}) x;`, { timeout: 120000 });
    audit('db', 'session.kill', { engine, filter: Object.assign({}, f, { minSeconds: minSeconds || null }), killed: n });
    return ok('已终止 ' + ((r.rows[0] || {}).killed || 0) + ' 个会话', { killed: Number((r.rows[0] || {}).killed || 0) });
  }
  const conds = ['id <> connection_id()'];
  if (f.state === 'idle') conds.push("command = 'Sleep'");
  else if (f.state === 'active') conds.push("command <> 'Sleep'");
  if (f.database) conds.push('db = ' + lit('mysql', String(f.database)));
  if (f.user) conds.push('user = ' + lit('mysql', String(f.user)));
  if (minSeconds) conds.push('time > ' + minSeconds);
  const sel = await runSql('mysql', null, `SELECT id FROM information_schema.processlist WHERE ${conds.join(' AND ')};`, { timeout: 60000 });
  const ids = (sel.rows || []).map((x) => Number(x.id)).filter((n) => Number.isFinite(n) && n > 0);
  if (!ids.length) return ok('没有匹配的会话', { killed: 0 });
  if (ids.length > SESSION_KILL_CAP) bad(`匹配到 ${ids.length} 个会话，超过安全上限 ${SESSION_KILL_CAP}，请缩小筛选范围`);
  await runSql('mysql', null, ids.map((i) => `KILL ${i};`).join('\n'), { timeout: 60000, ignoreError: true });
  audit('db', 'session.kill', { engine, filter: Object.assign({}, f, { minSeconds: minSeconds || null }), count: ids.length });
  return ok('已终止 ' + ids.length + ' 个会话', { killed: ids.length });
}

/* ---- 扩展监控 (WAL / BGWriter / InnoDB / 复制槽) ---- */
async function extraMonitor({ engine, database }) {
  checkEngine(engine);
  const out = [];
  const sec = async (title, fn, note) => {
    try { const r = await fn(); out.push({ title, columns: r.columns || [], rows: r.rows || [], note: note || '' }); }
    catch (e) { out.push({ title, columns: [], rows: [], error: String(e.message).slice(0, 300), note: note || '' }); }
  };
  if (engine === 'postgres') {
    /* 用 SELECT * 以兼容各 PG 版本（17+ 把 checkpoint 列移到了 pg_stat_checkpointer） */
    await sec('后台写进程 (pg_stat_bgwriter)', () => runSql('postgres', database, 'SELECT * FROM pg_stat_bgwriter;'));
    await sec('检查点 (pg_stat_checkpointer)', () => runSql('postgres', database, 'SELECT * FROM pg_stat_checkpointer;'), 'PostgreSQL 17+');
    await sec('WAL 统计 (pg_stat_wal)', () => runSql('postgres', database, 'SELECT * FROM pg_stat_wal;'), 'PostgreSQL 14+');
    await sec('后端 I/O (pg_stat_io)', () => runSql('postgres', database,
      "SELECT backend_type, object, context, reads, writes, writebacks, extends, hits, evictions FROM pg_stat_io WHERE writes > 0 OR reads > 0 ORDER BY writes DESC LIMIT 20;"), 'PostgreSQL 16+');
    await sec('归档统计 (pg_stat_archiver)', () => runSql('postgres', database, 'SELECT * FROM pg_stat_archiver;'));
    await sec('复制槽 (pg_replication_slots)', () => runSql('postgres', database,
      'SELECT * FROM pg_replication_slots;'), '为空表示没有逻辑/物理复制槽');
    await sec('死锁与冲突', () => runSql('postgres', database,
      `SELECT datname, deadlocks, conflicts, temp_files, pg_size_pretty(temp_bytes) AS temp_bytes, xact_commit, xact_rollback FROM pg_stat_database WHERE datname IS NOT NULL ORDER BY deadlocks DESC, conflicts DESC LIMIT 15;`));
    await sec('表级 I/O (pg_statio_user_tables)', () => runSql('postgres', database,
      `SELECT schemaname AS schema, relname AS name, heap_blks_read, heap_blks_hit, idx_blks_read, idx_blks_hit, toast_blks_read, toast_blks_hit FROM pg_statio_user_tables ORDER BY heap_blks_read DESC LIMIT 20;`));
    await sec('长事务与空闲事务', () => runSql('postgres', database,
      `SELECT pid, usename AS "user", datname, state, round(extract(epoch from now()-xact_start)::numeric,1) AS xact_secs, round(extract(epoch from now()-state_change)::numeric,1) AS idle_secs, left(regexp_replace(query,'\\s+',' ','g'),120) AS query FROM pg_stat_activity WHERE (state LIKE 'idle in transaction%' OR (state='active' AND now()-xact_start > interval '1 minute')) AND pid <> pg_backend_pid() ORDER BY xact_secs DESC NULLS LAST LIMIT 20;`));
    await sec('连接池/后端类型', () => runSql('postgres', database,
      `SELECT COALESCE(backend_type,'?') AS backend_type, count(*) AS n, COALESCE(state,'-') AS state FROM pg_stat_activity GROUP BY 1,3 ORDER BY n DESC LIMIT 20;`));
  } else {
    await sec('InnoDB 引擎状态', () => runSql('mysql', null, `SHOW ENGINE INNODB STATUS;`, { parse: false }).then((r) => ({ columns: ['status'], rows: [{ status: String(r.raw || '').slice(0, 1500) }] })));
    await sec('InnoDB 缓冲池指标', () => runSql('mysql', null,
      `SELECT VARIABLE_NAME AS name, VARIABLE_VALUE AS value FROM information_schema.global_status WHERE VARIABLE_NAME IN
        ('Innodb_buffer_pool_pages_total','Innodb_buffer_pool_pages_free','Innodb_buffer_pool_pages_dirty','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Innodb_buffer_pool_wait_free','Innodb_data_reads','Innodb_data_writes','Innodb_log_waits','Innodb_os_log_written','Innodb_row_lock_time_avg','Innodb_row_lock_waits','Innodb_deadlocks','Innodb_page_size') ORDER BY name;`));
    await sec('二进制日志 / 复制', () => runSql('mysql', null, 'SHOW MASTER STATUS;').then((r) => r).catch(() => ({ columns: [], rows: [] })), '需开启 binlog');
    await sec('慢查询配置', () => runSql('mysql', null,
      `SELECT @@slow_query_log AS slow_log_on, @@long_query_time AS long_query_time, @@log_queries_not_using_indexes AS log_no_index, @@slow_query_log_file AS slow_log_file;`));
    await sec('死锁 / 锁等待', () => runSql('mysql', null,
      `SELECT * FROM performance_schema.events_statements_summary_by_digest WHERE sum_lock_time > 0 ORDER BY sum_lock_time DESC LIMIT 15;`).catch(() => runSql('mysql', null, `SELECT trx_id, trx_state, trx_started, trx_rows_locked, LEFT(trx_query,80) AS query FROM information_schema.innodb_trx LIMIT 20;`)));
  }
  return { engine, sections: out };
}

/* ---- 维护计划 (写入系统 cron) ---- */
async function maintenancePlan({ engine, database, action, schedule }) {
  checkEngine(engine);
  const sch = String(schedule || '0 4 * * *');
  if (!/^[0-9*,\-\/ ]{1,64}$/.test(sch)) bad('无效 cron 表达式');
  const s = [];
  if (engine === 'postgres') {
    if (action === 'vacuum-analyze') s.push(`su - postgres -c 'vacuumdb --all --analyze --verbose'`);
    else if (action === 'vacuum-full') s.push(`su - postgres -c 'vacuumdb --all --full --analyze'`);
    else if (action === 'reindex') s.push(`su - postgres -c 'reindexdb --all'`);
    else if (action === 'backup-all') s.push(`su - postgres -c 'pg_dumpall' | gzip -1 > ${DB_BACKUP_DIR}/db-postgres-ALL-$(date +\\%Y-\\%m-\\%d).sql.gz`);
    else bad('未知维护动作: ' + action);
  } else {
    if (action === 'optimize') s.push('mysqlcheck -o --all-databases');
    else if (action === 'analyze') s.push('mysqlcheck -a --all-databases');
    else if (action === 'check') s.push('mysqlcheck -c --all-databases');
    else if (action === 'backup-all') s.push('mysqldump --single-transaction --routines --triggers --all-databases | gzip -1 > ${DB_BACKUP_DIR}/db-mysql-ALL-$(date +\\%Y-\\%m-\\%d).sql.gz');
    else bad('未知维护动作: ' + action);
  }
  const name = `dbmaint-${engine}-${action}`.replace(/[^A-Za-z0-9_-]/g, '-').slice(0, 40);
  const content = `# managed by cloud-monitor (db maintenance plan)\n${sch} root ${s[0]}\n`;
  const agent = getAgent();
  await run('mkdir -p /etc/cron.d');
  await agent.writeFile('/etc/cron.d/cm-' + name, content);
  await run(`chmod 644 /etc/cron.d/cm-${name} && chown root:root /etc/cron.d/cm-${name}`);
  audit('db', 'maintenance.plan', { engine, action, schedule: sch, name });
  return ok(`已创建维护计划 /etc/cron.d/cm-${name}\n${sch} root ${s[0]}`, { name });
}

/* ================= multi-engine dispatch =================
 * PostgreSQL & MySQL are handled by the implementations above; the extra
 * engines (SQLite / Db2 / Redis / MongoDB) are delegated to dbmulti.js.
 * Wrapping happens here so every existing route keeps working unchanged and
 * the pg/mysql code paths are not touched at all. */
const dbmulti = require('./dbmulti');

const _api = {
  getEngineStatus, listDatabases, createDatabase, dropDatabase, renameDatabase, maintenance,
  listTables, getTableSchema, getTableData, insertRow, updateRow, deleteRow, tableAction,
  listRoles, roleAction, killProcess, sqlConsole,
  listDbBackups, createDbBackup, restoreDbBackup, deleteDbBackup,
  insights, listObjects, statements, schemaAction, databaseAlter, exportData, countRows, importCsv,
  cleanupBackups, createExtension,
  listSchemas, schemaManage, tableStorage, missingIndexes, searchValue, explainTree,
  bulkTableAction, copyTableToDb, tableBackup, sequenceAction, objectManage, sessionManage,
  extraMonitor, maintenancePlan,
};

function dispatch(name, fn) {
  return function (...args) {
    const a = args[0];
    const engine = (a && typeof a === 'object') ? String(a.engine || '') : String(a == null ? '' : a);
    if (dbmulti.ENGINES.indexOf(engine) >= 0 && typeof dbmulti[name] === 'function') {
      return dbmulti[name].apply(null, args);
    }
    return fn.apply(null, args);
  };
}
const routed = {};
for (const n of Object.keys(_api)) routed[n] = dispatch(n, _api[n]);

module.exports = Object.assign(routed, {
  dbBackupPath, uploadPath, saveUpload, dbFilePath,
  /* 供单元测试使用 */
  _internal: { assertReadOnlySingleStatement, stripLiterals },
  DB_BACKUP_DIR, UPLOAD_DIR,
  /* extra engine plumbing for index.js */
  EXTRA_ENGINES: dbmulti.ENGINES,
  dbMulti: dbmulti,
});
