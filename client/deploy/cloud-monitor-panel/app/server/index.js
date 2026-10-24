'use strict';
/* Cloud Monitor panel - main entry. */
const http = require('http');
const path = require('path');
const express = require('express');

const config = require('./config');
const auth = require('./auth');
const collectors = require('./collectors');
const actions = require('./actions');
const hub = require('./hub');
const { getAgent } = require('./agent');
const { log, logErr, audit, fmtUptime } = require('./util');
const { listSources } = require('./logsources');

const cfg = config.get();
const BASE = (cfg.basePath || '').replace(/\/$/, '');
const PUBLIC_DIR = path.resolve(__dirname, '..', 'public');
const app = express();
app.disable('x-powered-by');
app.set('trust proxy', true);
app.use(express.json({ limit: '4mb' }));

/* security headers */
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws: wss:; font-src 'self' data:; object-src 'none'; frame-ancestors 'none'");
  next();
});

/* lightweight gzip for JSON/text responses (skips anything already streamed,
   e.g. static files and backup downloads, so they are never corrupted) */
const zlib = require('zlib');
app.use((req, res, next) => {
  if (!/\bgzip\b/i.test(String(req.headers['accept-encoding'] || ''))) return next();
  const origEnd = res.end.bind(res);
  const origWrite = res.write.bind(res);
  let streamed = false;
  res.write = function (...a) { streamed = true; return origWrite(...a); };
  res.end = function (chunk, ...rest) {
    res.write = origWrite; res.end = origEnd;
    try {
      const type = String(res.getHeader('Content-Type') || '');
      const enc = res.getHeader('Content-Encoding');
      const buf = Buffer.isBuffer(chunk) ? chunk : (chunk ? Buffer.from(chunk) : null);
      if (!streamed && !enc && buf && buf.length > 1024 && buf.length < 512 * 1024 &&
          /(json|javascript|css|html|svg|xml|text\/plain)/i.test(type)) {
        const gz = zlib.gzipSync(buf, { level: 5 });
        if (gz.length < buf.length) {
          res.setHeader('Content-Encoding', 'gzip');
          res.setHeader('Vary', 'Accept-Encoding');
          res.removeHeader('Content-Length');
          res.setHeader('Content-Length', gz.length);
          return origEnd(gz, ...rest);
        }
      }
    } catch (_) {}
    return origEnd(chunk, ...rest);
  };
  next();
});

/* ============ vendor libs + static assets (served from memory for speed;
   buffered responses are then gzipped by the middleware above) ============ */
const fs = require('fs');
const VENDOR = {
  '/vendor/chart.js': ['../node_modules/chart.js/dist/chart.umd.js', 'application/javascript'],
  '/vendor/xterm.js': ['../node_modules/@xterm/xterm/lib/xterm.js', 'application/javascript'],
  '/vendor/xterm.css': ['../node_modules/@xterm/xterm/css/xterm.css', 'text/css'],
  '/vendor/addon-fit.js': ['../node_modules/@xterm/addon-fit/lib/addon-fit.js', 'application/javascript'],
};
const MEM_MAX = 1024 * 1024; // keep files <= 1MB in memory
const memCache = new Map();  // absPath -> { buf, mtime }
function mimeFor(ext) {
  return { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css',
    '.svg': 'image/svg+xml', '.json': 'application/json', '.map': 'application/json' }[ext] || 'application/octet-stream';
}
function sendCached(req, res, abs, mime, maxAge) {
  let e = memCache.get(abs);
  let st;
  try { st = fs.statSync(abs); } catch (_) { return res.status(404).end('Not Found'); }
  if (!e || e.mtime !== st.mtimeMs) {
    const buf = st.size <= MEM_MAX ? fs.readFileSync(abs) : null;
    e = { buf, mtime: st.mtimeMs };
    memCache.set(abs, e);
  }
  /* validators: let browsers revalidate (304) instead of blindly reusing a
     stale copy for max-age — without these a deploy can take up to an hour
     to reach clients that already cached the old file */
  const etag = '"' + st.mtimeMs.toString(16) + '-' + st.size.toString(16) + '"';
  res.setHeader('ETag', etag);
  res.setHeader('Last-Modified', new Date(st.mtime).toUTCString());
  res.setHeader('Cache-Control', 'public, max-age=' + maxAge + ', must-revalidate');

  const inm = String(req.headers['if-none-match'] || '');
  const ims = req.headers['if-modified-since'];
  const etags = inm.split(',').map((t) => t.trim()).filter(Boolean);
  const notModified = (etags.length && (etags.includes(etag) || etags.includes('*'))) ||
    (!etags.length && ims && new Date(ims).getTime() >= Math.floor(st.mtime / 1000) * 1000);
  if (notModified) {
    res.removeHeader('Content-Type'); res.removeHeader('Content-Length'); res.removeHeader('Content-Encoding');
    return res.status(304).end();
  }

  res.type(mime);
  if (e.buf) return res.end(e.buf);
  /* large file: stream (no gzip, but rare) */
  fs.createReadStream(abs).pipe(res);
}

app.use(BASE + '/public', (req, res, next) => {
  const rel = decodeURIComponent(req.path).replace(/^\/+/, '');
  if (!rel || rel.includes('..')) return next();
  const abs = path.join(PUBLIC_DIR, rel);
  if (!abs.startsWith(PUBLIC_DIR)) return next();
  /* the html shell revalidates on every load; code/css can live 5 min */
  const isHtml = rel.endsWith('.html');
  sendCached(req, res, abs, mimeFor(path.extname(abs)), isHtml ? 0 : 300);
});
for (const [p, [rel, mime]] of Object.entries(VENDOR)) {
  app.get(BASE + p, (req, res) => sendCached(req, res, path.resolve(__dirname, rel), mime, 604800));
}
app.get([BASE, BASE + '/', BASE + '/index.html'], (req, res) => res.redirect(BASE + '/public/index.html'));
app.get('/', (req, res) => res.redirect(BASE + '/public/index.html'));/* ============ api ============ */
const api = express.Router();

api.get('/health', (req, res) => res.json({ ok: true, name: 'cloud-monitor', version: '1.0.0', ts: Date.now() }));

api.post('/login', (req, res) => {
  const ip = req.headers['x-real-ip'] || req.socket.remoteAddress || '?';
  const out = auth.login(ip, (req.body || {}).password);
  res.json(out);
});

api.use(auth.middleware);
  /* API 速率限制：单 IP 每分钟 240 次，超出返回 429（防止并发重查询压垮数据库） */
  api.use((req, res, next) => {
    if (auth.apiLimited(req.clientIp)) {
      return res.status(429).json({ error: '请求过于频繁（单 IP 每分钟上限 240 次），请稍后再试' });
    }
    next();
  });

const wrap = (fn) => (req, res) => Promise.resolve(fn(req, res)).catch((e) => {
  logErr('api error:', req.method, req.originalUrl, '-', e.message);
  if (e.output) return res.status(e.status || 500).json({ error: e.message, output: e.output });
  res.status(e.status || 500).json({ error: e.message || '服务器内部错误' });
});

api.post('/logout', (req, res) => {
  auth.logout(auth.extract(req), req.socket.remoteAddress);
  res.json({ ok: true });
});

api.get('/me', wrap(async (req, res) => {
  const info = await collectors.getSystemInfo();
  /* runtime facts the UI needs, so the frontend never hardcodes paths itself */
  let envInfo = null;
  try {
    const d = await require('./distro').detect();
    const s = await require('./svc').detect();
    const P = require('./paths');
    envInfo = {
      distro: d.name, family: d.family, pm: d.pm || null,
      systemd: !!s.systemd, init: s.init, webserver: d.webserver || null, tools: d.tools,
      paths: { data: P.dataDir, logs: P.logDir, backups: P.backupDir, dbBackups: P.dbBackupDir },
    };
  } catch (_) { envInfo = null; }
  res.json({
    ok: true, info, env: envInfo,
    refreshMs: cfg.refreshMs,
    netLimitBps: cfg.netLimitBps || 0,
    uptimeStr: fmtUptime(process.uptime()),
    panelPid: process.pid,
  });
}));

api.get('/metrics', wrap(async (req, res) => res.json(await collectors.getMetrics())));
api.get('/overview', wrap(async (req, res) => res.json(await collectors.snapshot())));
api.get('/processes', wrap(async (req, res) => {
  const p = await collectors.getProcesses({ force: true, limit: 500 });
  let list = p.list;
  const q = (req.query.q || '').toString().toLowerCase();
  if (q) list = list.filter((x) => (x.args + ' ' + x.user + ' ' + x.pid).toLowerCase().includes(q));
  const sort = (req.query.sort || 'cpu').toString();
  const key = { cpu: 'cpu', mem: 'rss', pid: 'pid', user: 'user' }[sort] || 'cpu';
  list = list.slice().sort((a, b) => (key === 'user' ? String(a.user).localeCompare(b.user) : (b[key] || 0) - (a[key] || 0)));
  res.json({ total: p.total, statCounts: p.statCounts, list: list.slice(0, 300) });
}));
api.get('/services', wrap(async (req, res) => res.json(await collectors.getServices(true))));
api.get('/disks', wrap(async (req, res) => res.json(await collectors.getDisks(true))));
api.get('/memory', wrap(async (req, res) => res.json(await collectors.getMemoryDetail())));
api.get('/network', wrap(async (req, res) => res.json(await collectors.getNetworkDetail(true))));
api.get('/security', wrap(async (req, res) => res.json(await collectors.getSecurity())));
api.get('/databases', wrap(async (req, res) => res.json(await collectors.getDatabases())));
api.get('/web', wrap(async (req, res) => res.json(await collectors.getWebServers())));
api.get('/docker', wrap(async (req, res) => res.json(await collectors.getDocker())));
api.get('/cron', wrap(async (req, res) => res.json(await collectors.getCron())));
api.get('/history', wrap(async (req, res) => res.json(hub.getHistory(String(req.query.range || '10m')))));
api.get('/alerts', (req, res) => res.json({ alerts: hub.getAlerts(), config: config.get().alerts }));
api.get('/backups', wrap(async (req, res) => res.json(await actions.backupList())));
api.get('/logs/sources', (req, res) => res.json(listSources()));

api.post('/logs/tail', wrap(async (req, res) => {
  const { resolveSource } = require('./logsources');
  const src = resolveSource((req.body || {}).source);
  if (!src) return res.status(400).json({ error: '未知日志源' });
  const r = await getAgent().run(src.tail, { timeout: 10000 });
  res.json({ output: r.stdout + r.stderr });
}));

const audited = (name, fn) => wrap(async (req, res) => {
  const ip = req.headers['x-real-ip'] || req.socket.remoteAddress || '?';
  const body = Object.assign({}, req.body || {});
  if (body.content) body.content = `<${String(body.content).length} bytes>`;
  if (body.sql) body.sql = String(body.sql).slice(0, 120);
  audit(ip, name, body);
  res.json(await fn(req));
});

api.post('/process/kill', audited('process.kill', (req) => actions.killProcess(req.body || {})));
api.post('/service', audited('service.action', (req) => actions.serviceAction(req.body || {})));
api.post('/disk/action', audited('disk.action', (req) => actions.diskAction(req.body || {})));
api.post('/network/action', audited('network.action', (req) => actions.networkAction(req.body || {})));
api.post('/security/action', audited('security.action', (req) => actions.securityAction(req.body || {})));
api.post('/db/action', audited('db.action', (req) => actions.dbAction(req.body || {})));
api.post('/db/query', audited('db.query', (req) => actions.dbQuery(req.body || {})));

/* ============ database administration (advanced) ============ */
const dbadmin = require('./dbadmin');
const dbR = (name, fn, auditName) => api.post('/db/' + name, audited(auditName || ('db.' + name), async (req) => fn(req.body || {})));
const dbG = (name, fn) => api.get('/db/' + name, wrap(async (req, res) => res.json(await fn(req.query))));

dbG('status', (q) => dbadmin.getEngineStatus(String(q.engine)));
dbG('databases', (q) => dbadmin.listDatabases(String(q.engine)));
dbG('tables', (q) => dbadmin.listTables({ engine: String(q.engine), database: String(q.database) }));
dbG('table/schema', (q) => dbadmin.getTableSchema({ engine: String(q.engine), database: String(q.database), table: String(q.table) }));
dbG('roles', (q) => dbadmin.listRoles(String(q.engine)));
dbG('backups', (q) => dbadmin.listDbBackups({ engine: String(q.engine || '') }));
dbG('insights', (q) => dbadmin.insights({ engine: String(q.engine), database: q.database ? String(q.database) : null }));
dbG('objects', (q) => dbadmin.listObjects({ engine: String(q.engine), database: q.database ? String(q.database) : null }));
dbG('statements', (q) => dbadmin.statements({ engine: String(q.engine), database: q.database ? String(q.database) : null }));
dbG('schemas', (q) => dbadmin.listSchemas({ engine: String(q.engine), database: q.database ? String(q.database) : null }));
dbG('table/storage', (q) => dbadmin.tableStorage({ engine: String(q.engine), database: String(q.database), table: String(q.table) }));
dbG('missing-indexes', (q) => dbadmin.missingIndexes({ engine: String(q.engine), database: q.database ? String(q.database) : null }));
dbG('extra-monitor', (q) => dbadmin.extraMonitor({ engine: String(q.engine), database: q.database ? String(q.database) : null }));

api.post('/db/table/data', wrap(async (req, res) => res.json(await dbadmin.getTableData(req.body || {}))));
api.post('/db/table/insert', audited('db.row.insert', (req) => dbadmin.insertRow(req.body || {})));
api.post('/db/table/update', audited('db.row.update', (req) => dbadmin.updateRow(req.body || {})));
api.post('/db/table/delete', audited('db.row.delete', (req) => dbadmin.deleteRow(req.body || {})));
api.post('/db/table/action', audited('db.table.action', (req) => dbadmin.tableAction(req.body || {})));
dbR('database/create', (b) => dbadmin.createDatabase(b));
dbR('database/drop', (b) => dbadmin.dropDatabase(b));
dbR('database/rename', (b) => dbadmin.renameDatabase(b));
dbR('maintenance', (b) => dbadmin.maintenance(b));
dbR('role', (b) => dbadmin.roleAction(b));
dbR('kill', (b) => dbadmin.killProcess(b));
dbR('console', (b) => dbadmin.sqlConsole(b));
dbR('backup', (b) => dbadmin.createDbBackup(b));
dbR('backup/cleanup', (b) => dbadmin.cleanupBackups(b));
dbR('restore', (b) => dbadmin.restoreDbBackup(b));
dbR('backup/delete', (b) => dbadmin.deleteDbBackup(b));
dbR('schema/action', (b) => dbadmin.schemaAction(b));
dbR('schema/manage', (b) => dbadmin.schemaManage(b));
dbR('table/bulk', (b) => dbadmin.bulkTableAction(b));
dbR('table/copy-db', (b) => dbadmin.copyTableToDb(b));
dbR('table/backup', (b) => dbadmin.tableBackup(b));
dbR('table/search', (b) => dbadmin.searchValue(b));
dbR('explain/tree', (b) => dbadmin.explainTree(b));
dbR('sequence', (b) => dbadmin.sequenceAction(b));
dbR('object', (b) => dbadmin.objectManage(b));
dbR('session/kill', (b) => dbadmin.sessionManage(b));
dbR('maintenance/plan', (b) => dbadmin.maintenancePlan(b));
dbR('database/alter', (b) => dbadmin.databaseAlter(b));
dbR('export', (b) => dbadmin.exportData(b));
dbR('count', (b) => dbadmin.countRows(b));
dbR('import', (b) => dbadmin.importCsv(b));
dbR('extension', (b) => dbadmin.createExtension(b));

/* ============ extra engines (SQLite / Db2 / Redis / MongoDB) ============ */
const dbmulti = require('./dbmulti');
api.get('/db/engines', wrap(async (req, res) => res.json({ engines: await dbmulti.engines() })));
dbR('engine/install', (b) => dbmulti.installEngine(b), 'db.engine.install');
dbR('engine/service', (b) => dbmulti.engineService(b), 'db.engine.service');
/* redis keyspace */
api.get('/db/kv/keys', wrap(async (req, res) => res.json(await dbmulti.kvKeys({
  database: req.query.database, pattern: req.query.pattern, limit: req.query.limit, cursor: req.query.cursor,
}))));
api.get('/db/kv/value', wrap(async (req, res) => res.json(await dbmulti.kvValue({ database: req.query.database, key: req.query.key }))));
api.get('/db/kv/config', wrap(async (req, res) => res.json(await dbmulti.kvConfig({}))));
dbR('kv/set', (b) => dbmulti.kvSet(b), 'db.kv.set');
dbR('kv/delete', (b) => dbmulti.kvDelete(b), 'db.kv.delete');
dbR('kv/config', (b) => dbmulti.kvConfig(Object.assign({ action: 'set' }, b)), 'db.kv.config');
/* mongo documents */
dbR('doc/find', (b) => dbmulti.docFind(b), 'db.doc.find');
dbR('doc/insert', (b) => dbmulti.docInsert(b), 'db.doc.insert');
dbR('doc/update', (b) => dbmulti.docUpdate(b), 'db.doc.update');
dbR('doc/delete', (b) => dbmulti.docDelete(b), 'db.doc.delete');
dbR('doc/collection', (b) => dbmulti.docCreateCollection(b), 'db.doc.collection');
dbR('doc/indexes', (b) => dbmulti.docIndexes(b), 'db.doc.indexes');
dbR('doc/index', (b) => dbmulti.docCreateIndex(b), 'db.doc.index');
dbR('doc/index/drop', (b) => dbmulti.docDropIndex(b), 'db.doc.index.drop');
dbR('doc/shell', (b) => dbmulti.docShell(b), 'db.doc.shell');

api.get('/db/backup/download', wrap(async (req, res) => {
  const p = dbadmin.dbFilePath(String(req.query.name || ''));
  if (!p) return res.status(400).json({ error: '非法文件名' });
  audit(req.socket.remoteAddress || '?', 'db.backup.download', { name: req.query.name });
  try {
    const buf = await getAgent().readFileBuffer(p);
    res.setHeader('Content-Disposition', `attachment; filename="${path.basename(p)}"`);
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Content-Length', buf.length);
    res.end(buf);
  } catch (e) { res.status(404).json({ error: '文件不存在' }); }
}));

/* raw SQL dump upload for restore (up to 500MB) */
api.post('/db/upload', express.raw({ type: '*/*', limit: '500mb' }), audited('db.upload', async (req) => {
  const name = String((req.query && req.query.name) || 'upload.sql');
  if (!Buffer.isBuffer(req.body) || !req.body.length) { const e = new Error('未收到文件内容'); e.status = 400; throw e; }
  return dbadmin.saveUpload(name, req.body);
}));

/* ============ optional relay integration ============ */
const relay = require('./relay');
/* every endpoint the original /stats/ dashboard uses */
const RELAY_GET = [
  'summary', 'keys', 'key', 'quota', 'providers', 'daily', 'hourly', 'weekday',
  'models', 'models-daily', 'tokens', 'model-status', 'health', 'selfheal',
  'latency', 'ttfb', 'streaming', 'sizes', 'status', 'routes', 'methods',
  'endpoints', 'failover', 'errors', 'recent', 'sessions', 'session-memory',
  'useragents', 'ips',
];
for (const name of RELAY_GET) {
  api.get('/relay/' + name, wrap(async (req, res) => {
    const r = await relay.getApi(name, req.query || {});
    res.status(r.status).json(r.data);
  }));
}
/* Feature detection: lets the UI hide the relay entry when no collector runs. */
api.get('/relay/available', wrap(async (req, res) => {
  res.json(await relay.probe());
}));
api.post('/relay/action', audited('relay.action', async (req) => {
  const b = req.body || {};
  return relay.action(b.name, b.payload);
}));
api.post('/web/action', audited('web.action', (req) => actions.webAction(req.body || {})));
api.post('/docker/action', audited('docker.action', (req) => actions.dockerAction(req.body || {})));
api.post('/cron/action', audited('cron.action', (req) => actions.cronAction(req.body || {})));
api.post('/pkg/install', audited('pkg.install', (req) => actions.pkgInstall(req.body || {})));

/* ============ package manager (apt/dpkg) ============ */
const pkgmgr = require('./pkgmgr');
const pkgG = (name, fn) => api.get('/pkg/' + name, wrap(async (req, res) => res.json(await fn(req.query))));
pkgG('summary', () => pkgmgr.summary());
pkgG('search', (q) => pkgmgr.search({ q: q.q, limit: q.limit }));
pkgG('detail', (q) => pkgmgr.detail({ name: q.name }));
pkgG('installed', (q) => pkgmgr.installed({ q: q.q, section: q.section, sort: q.sort, dir: q.dir, limit: q.limit, offset: q.offset }));
pkgG('upgradable', () => pkgmgr.upgradable());
pkgG('autoremove', () => pkgmgr.autoremoveList());
pkgG('recommended', () => pkgmgr.recommended());
pkgG('sources', () => pkgmgr.listSources());
pkgG('source-backups', () => pkgmgr.sourceBackups());
pkgG('search-in-source', (q) => pkgmgr.searchInSource({ q: q.q, uri: q.uri, limit: q.limit }));
pkgG('test-mirror', (q) => pkgmgr.testMirror({ url: q.url }));
api.post('/pkg/switch-mirror', audited('pkg.mirror.switch', (req) => pkgmgr.switchMirror(req.body || {})));
api.post('/pkg/preview', audited('pkg.preview', (req) => pkgmgr.preview(req.body || {})));
api.post('/pkg/action', audited('pkg.action', (req) => pkgmgr.act(req.body || {})));
/* 变更任务：POST 启动并立即返回任务 id，GET 轮询进度（进度条 + 实时日志）。
   不带 id 的 GET 返回最近一个任务，便于页面刷新后接上进度。 */
api.post('/pkg/job', audited('pkg.job', (req) => pkgmgr.startJob(req.body || {})));
pkgG('job', (q) => pkgmgr.jobState(q.id));
api.post('/backups', audited('backup.create', (req) => actions.backupCreate(req.body || {})));
api.post('/backups/delete', audited('backup.delete', (req) => actions.backupDelete(req.body || {})));
api.get('/backups/download', wrap(async (req, res) => {
  const p = actions.backupPath(String(req.query.name || ''));
  if (!p) return res.status(400).json({ error: '非法文件名' });
  audit(req.socket.remoteAddress || '?', 'backup.download', { name: req.query.name });
  const agent = getAgent();
  try {
    const buf = await agent.readFileBuffer(p);
    res.setHeader('Content-Disposition', `attachment; filename="${path.basename(p)}"`);
    res.setHeader('Content-Type', 'application/gzip');
    res.setHeader('Content-Length', buf.length);
    res.end(buf);
  } catch (e) {
    res.status(404).json({ error: '文件不存在或读取失败' });
  }
}));
api.post('/user', audited('user.action', (req) => actions.userAction(req.body || {})));

/* ============ relay operations: rate limiting, audit trail, health alerts,
   daily relay backups (relay_alert.py / backup_relay.sh / auditd) ============ */
const relayops = require('./relayops');
api.get('/relayops/ratelimit', wrap(async (req, res) => res.json(await relayops.rateLimit())));
const metricsStore = require('./metricsstore');
api.get('/metrics/history', wrap(async (req, res) => res.json(metricsStore.query(String(req.query.range || '24h'), req.query.compare === '1'))));
api.get('/metrics/store', wrap(async (req, res) => res.json(metricsStore.stats())));
api.get('/metrics/trend', wrap(async (req, res) => res.json({
  cpu: metricsStore.trend(Number(req.query.days) || 7, 'cpu'),
  mem: metricsStore.trend(Number(req.query.days) || 7, 'mem'),
  disk: metricsStore.trend(Number(req.query.days) || 7, 'disk'),
})));
const relaymetrics = require('./relaymetrics');
api.get('/relayops/slo', wrap(async (req, res) => res.json(await relaymetrics.sloStats({ range: req.query.range || '24h' }))));
api.get('/relayops/hitrate', wrap(async (req, res) => res.json(await relaymetrics.hitRate({ range: req.query.range || '24h' }))));
api.get('/relayops/tokenhit', wrap(async (req, res) => res.json(await relaymetrics.tokenHit({ range: req.query.range || '24h' }))));
api.get('/relayops/usage', wrap(async (req, res) => res.json(await relaymetrics.usageCost({ range: req.query.range || '24h' }))));
api.get('/relayops/slo-prev', wrap(async (req, res) => res.json(await relaymetrics.sloPrev({ range: req.query.range || '24h' }))));
api.get('/relayops/slo-budget', wrap(async (req, res) => res.json(await relaymetrics.sloBudget())));
api.get('/relayops/correlate', wrap(async (req, res) => {
  const hours = Math.max(1, Math.min(24 * 30, Number(req.query.hours) || 24));
  const win = Math.max(5, Math.min(180, Number(req.query.window) || 30));
  const [anom, chg] = await Promise.all([
    relaymetrics.errorBuckets({ hours }),
    relayops.auditSearch({ range: hours <= 24 ? '24h' : 'all', key: '', limit: 300 }),
  ]);
  const changes = (chg.events || []).map((e) => ({ ts: e.ts, key: e.key, comm: e.comm, cmd: e.cmd, files: e.files }));
  const links = relaymetrics.correlate(anom, changes, win);
  res.json({
    hours, windowMin: win,
    summary: relaymetrics.correlateSummary(links),
    changeCount: changes.length,
    links: links.slice(0, 40),
  });
}));
api.get('/relayops/audit', wrap(async (req, res) => res.json(await relayops.auditSearch({
  range: req.query.range || '24h', key: req.query.key || '', limit: req.query.limit,
}))));
api.get('/relayops/health', wrap(async (req, res) => res.json(await relayops.healthAlerts())));
api.post('/relayops/alert/test', audited('relay.alert.test', () => relayops.alertTestMail()));
api.post('/relayops/backup/run', audited('relay.backup.run', () => relayops.relayBackupRun()));
api.post('/relayops/backup/delete', audited('relay.backup.delete', (req) => relayops.relayBackupDelete((req.body || {}).name)));
api.get('/relayops/backup/download', wrap(async (req, res) => {
  const p = relayops.relayBackupPath(String(req.query.name || ''));
  if (!p) return res.status(400).json({ error: '非法文件名' });
  audit(req.socket.remoteAddress || '?', 'relay.backup.download', { name: req.query.name });
  try {
    const buf = await getAgent().readFileBuffer(p);
    res.setHeader('Content-Disposition', `attachment; filename="${path.basename(p)}"`);
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Content-Length', buf.length);
    res.end(buf);
  } catch (e) {
    res.status(404).json({ error: '文件不存在或读取失败' });
  }
}));
api.post('/alerts/config', audited('alerts.config', (req) => { config.patch({ alerts: req.body || {} }); return { ok: true, output: '告警阈值已保存', config: config.get().alerts }; }));
api.post('/file', audited('file.write', (req) => actions.fileWrite(req.body || {})));

api.get('/file', wrap(async (req, res) => res.json(await actions.fileRead({ path: req.query.path }))));
api.get('/files', wrap(async (req, res) => res.json(await actions.fileListing({ dir: req.query.dir || '/etc/' }))));

api.get('/config', (req, res) => {
  const c = config.get();
  res.json({
    port: c.port, bind: c.bind, basePath: c.basePath, refreshMs: c.refreshMs,
    netLimitBps: c.netLimitBps || 0,
    sloTarget: c.sloTarget || 0.999,
    sloPeriodDays: c.sloPeriodDays || 30,
    tokenTtlHours: c.tokenTtlHours,
    ssh: { host: c.ssh.host, port: c.ssh.port, user: c.ssh.user, hasPassword: !!c.ssh.password, hasKey: !!(c.ssh.privateKeyFile) },
    db: {
      postgres: { user: c.db.postgres.user, host: c.db.postgres.host, port: c.db.postgres.port, hasPassword: !!c.db.postgres.password },
      mysql: { user: c.db.mysql.user, host: c.db.mysql.host, port: c.db.mysql.port, hasPassword: !!c.db.mysql.password },
      db2: { instance: c.db.db2.instance, database: c.db.db2.database, user: c.db.db2.user, host: c.db.db2.host, port: c.db.db2.port, hasPassword: !!c.db.db2.password },
      redis: { host: c.db.redis.host, port: c.db.redis.port, db: c.db.redis.db, hasPassword: !!c.db.redis.password },
      mongo: { host: c.db.mongo.host, port: c.db.mongo.port, user: c.db.mongo.user, authdb: c.db.mongo.authdb, hasPassword: !!c.db.mongo.password },
      sqlite: { roots: c.db.sqlite.roots },
    },
    underSystemd: process.env.CM_UNDER_SYSTEMD === '1',
  });
});
api.post('/config', audited('config.update', async (req) => {
  const p = req.body || {};
  config.patch({ refreshMs: p.refreshMs, netLimitBps: p.netLimitBps, sloTarget: p.sloTarget, sloPeriodDays: p.sloPeriodDays, ssh: p.ssh, db: p.db });
  hub.applyRefresh();
  return { ok: true, output: '配置已保存' + (p.ssh ? '（SSH 连接设置将在新终端会话中生效）' : '') };
}));
api.post('/password', wrap(async (req, res) => {
  const { oldPassword, newPassword } = req.body || {};
  const c = config.get();
  if (!config.verifyPassword(oldPassword, c.passwordHash)) return res.status(400).json({ error: '当前密码不正确' });
  if (typeof newPassword !== 'string' || newPassword.length < 8) return res.status(400).json({ error: '新密码至少 8 位' });
  config.patch({ password: newPassword });
  audit(req.socket.remoteAddress || '?', 'password-change', {});
  res.json({ ok: true, output: '密码已更新，其它设备的登录将会失效' });
}));
api.post('/restart', audited('panel.restart', (req) => actions.restartPanel()));

/* ============ ops modules: uptime probing, alert rules, notify channels ============ */
try { require('./ops').mount(api, { wrap, audited }); }
catch (e) { logErr('ops modules not mounted:', e.message); }

app.use(BASE + '/api', api);
app.use('/api', (req, res) => res.status(404).json({ error: 'not found' }));
app.use((req, res) => res.status(404).send('Not Found'));

/* error handler */
app.use((err, req, res, next) => {
  logErr('unhandled:', err.stack || err.message);
  res.status(500).json({ error: '服务器内部错误' });
});

/* ============ start ============ */
const server = http.createServer(app);
hub.init(server);

server.listen(cfg.port, cfg.bind, () => {
  log(`Cloud Monitor listening on http://${cfg.bind}:${cfg.port}${BASE}`);
  log(`agent mode: ${getAgent().mode}`);
});
server.on('error', (e) => {
  if (e && (e.code === 'EADDRINUSE' || e.code === 'EACCES')) {
    logErr('');
    logErr('无法监听 ' + cfg.bind + ':' + cfg.port + ' —— ' + e.code);
    if (e.code === 'EADDRINUSE') {
      logErr('该端口已被占用。查看占用进程: ss -ltnp | grep :' + cfg.port);
      logErr('修改端口: 编辑 config.json 的 "port"，或设置环境变量 CM_PORT');
    } else {
      logErr('权限不足。若使用 <1024 端口，请改用 1024 以上端口或授予 CAP_NET_BIND_SERVICE。');
    }
    logErr('');
    process.exit(1);
  }
  logErr('server error:', e && e.message);
  process.exit(1);
});

process.on('uncaughtException', (e) => logErr('uncaughtException:', e.stack || e.message));
process.on('unhandledRejection', (e) => logErr('unhandledRejection:', (e && e.stack) || e));
function shutdown(sig) {
  log(sig + ' received, shutting down…');
  hub.shutdown();
  try { server.close(); } catch (_) {}
  setTimeout(() => process.exit(0), 500);
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
