'use strict';
/*
 * PHP hosting module — route registration.
 *
 * Mounted from the panel's index.js exactly like the other ops modules:
 *   try { require('./ops').mount(api, { wrap, audited }); } catch (e) { ... }
 *
 * The panel's own `audited()` dumps the request body into the audit log verbatim.
 * Several endpoints here carry database passwords and deploy keys, so every PHP
 * route goes through `aud()` below, which audits a redacted copy instead.
 */
const fs = require('fs');
const C = require('./common');
const P = require('./presets');
const env = require('./env');
const site = require('./site');
const deploy = require('./deploy');
const dbMod = require('./db');
const ssl = require('./ssl');
const logs = require('./logs');
const jobs = require('./jobs');

const S = C.S;
const ok = C.ok;

const SECRET_FIELD = /^(password|passwd|pass|db_pass|token|privatekey|private_key|secret|content|sql)$/i;

function redact(body) {
  const out = {};
  for (const k of Object.keys(body || {})) {
    const v = body[k];
    if (SECRET_FIELD.test(k)) {
      out[k] = v == null ? null : '<' + String(v).length + ' chars>';
    } else if (k === 'env' && v && typeof v === 'object') {
      out[k] = Object.keys(v);
    } else if (k === 'values' && v && typeof v === 'object') {
      out[k] = Object.keys(v);
    } else if (Array.isArray(v)) {
      out[k] = v.length > 12 ? '[' + v.length + ' items]' : v;
    } else if (v && typeof v === 'object') {
      out[k] = Object.keys(v);
    } else {
      out[k] = v;
    }
  }
  return out;
}

const q = (v) => String(v == null ? '' : v);

function register(api, wrap) {
  /* The panel's audited() would dump `password`/`privateKey` into the audit log,
     so PHP routes always use this redacting variant. */
  const auditSec = (name, fn) => wrap(async (req, res) => {
    const ip = (req.headers && req.headers['x-real-ip']) || (req.socket && req.socket.remoteAddress) || '?';
    try { if (S.util && typeof S.util.audit === 'function') S.util.audit(ip, name, redact(req.body || {})); } catch (_) {}
    res.json(await fn(req));
  });
  const aud = auditSec;
  const g = (name, fn) => api.get('/php/' + name, wrap(async (req, res) => res.json(await fn(req.query || {}))));
  const gsite = (name, fn) => api.get('/php/' + name, wrap(async (req, res) => res.json(await fn(C.get(q(req.query.slug))))));

  /* ------------------------------------------------------------ overview */
  g('overview', async () => {
    const o = await env.overview();
    o.ssl = await ssl.all();
    o.renewTimer = await ssl.renewTimer();
    o.jobs = loadJobs();
    return o;
  });
  g('env', () => env.overview());
  g('ext', async (query) => {
    const v = q(query.version) || (await site.defaultVersion());
    const loaded = await env.loadedExtensions(v);
    return {
      version: v, loaded: loaded,
      catalog: env.EXT_CATALOG.map((e) => ({
        ext: e.ext, label: e.label,
        installed: loaded.indexOf(e.ext.toLowerCase()) >= 0 ||
          loaded.indexOf(e.ext.toLowerCase() === 'opcache' ? 'zend opcache' : e.ext.toLowerCase()) >= 0,
        package: 'php' + v + '-' + e.ext,
      })),
    };
  });
  api.post('/php/env/install', aud('php.env.install', async (req) => env.installJob(req.body || {})));
  api.post('/php/env/ext/remove', aud('php.env.ext.remove', async (req) => env.removeExt(req.body || {})));
  g('ini', (query) => env.readIni(query.version));
  api.post('/php/ini', aud('php.ini.save', async (req) => env.saveIni(req.body || {})));
  api.post('/php/fpm/action', aud('php.fpm.action', async (req) => env.fpmAction(req.body || {})));
  api.post('/php/opcache/reset', aud('php.opcache.reset', async (req) => env.resetOpcache(req.body || {})));
  g('phpinfo', (query) => env.phpinfoSlim(query.version));

  /* --------------------------------------------------------------- sites */
  g('sites', () => site.listSites());
  g('presets', async () => ({ presets: P.list(), sharedDefaults: deploy.SHARED_DEFAULTS, keepReleases: deploy.KEEP_RELEASES }));
  g('site', async (query) => {
    const rec = C.get(q(query.slug));
    const st = await site.statusOf(rec);
    const [dbInfo, sslInfo, sizes, releases] = await Promise.all([
      dbMod.infoFor(rec), ssl.statusFor(rec), logs.sizes(rec), Promise.resolve(deploy.listReleases(rec)),
    ]);
    return {
      ok: true, site: st, record: publicRecord(rec), db: dbInfo, ssl: sslInfo,
      logs: sizes, releases: releases, webhook: deploy.webhookFor(rec),
      files: { root: C.htdocsLink(rec.slug), release: C.releasesDir(rec.slug) + '/' + (rec.activeRelease || '') },
    };
  });
  api.post('/php/site/create', aud('php.site.create', async (req) => site.create(req.body || {})));
  api.post('/php/site/update', aud('php.site.update', async (req) => site.update(req.body || {})));
  api.post('/php/site/delete', aud('php.site.delete', async (req) => site.destroy(req.body || {})));
  api.post('/php/site/toggle', aud('php.site.toggle', async (req) => site.toggle(q(req.body.slug), req.body.enabled !== false)));
  api.post('/php/site/env', aud('php.site.env', async (req) => saveEnv(req.body || {})));
  api.post('/php/site/hooks', aud('php.site.hooks', async (req) => saveHooks(req.body || {})));
  api.post('/php/site/git', aud('php.site.git', async (req) => saveGit(req.body || {})));
  api.post('/php/site/webhook/rotate', aud('php.site.webhook.rotate', async (req) => rotateWebhook(req.body || {})));
  g('site/conf', async (query) => site.setRaw({ slug: q(query.slug) }));
  api.post('/php/site/conf/write', aud('php.site.conf.write', async (req) => site.writeRawNginx(req.body || {})));

  /* -------------------------------------------------------------- deploy */
  api.post('/php/upload', (req, res) => {
    try {
      deploy.streamUpload(req, res, {
        name: q(req.query.name), maxBytes: Number(req.query.maxBytes) || 2 * 1024 * 1024 * 1024,
      });
    } catch (e) { res.status(500).json({ error: e.message }); }
  });
  api.post('/php/deploy', aud('php.deploy', async (req) => deploy.startDeploy(req.body || {})));
  api.post('/php/rollback', aud('php.rollback', async (req) => deploy.rollback(req.body || {})));
  g('releases', (query) => deploy.listReleases(C.get(q(query.slug))));
  g('jobs', (query) => ({ jobs: loadJobs(q.slug || query.site) }));
  api.get('/php/job', wrap(async (req, res) => {
    const id = q(req.query.id);
    const slug = req.query.slug ? C.get(q(req.query.slug)).slug : null;
    const j = id ? jobs.byId(id) : jobs.latest(slug);
    res.json(jobs.view(j, Number(req.query.lines) || 400));
  }));
  g('preview', async (query) => {
    const rec = C.get(q(query.slug));
    return await deploy.probeSite({ log() {} }, rec);
  });

  /* ------------------------------------------------------------------ db */
  api.get('/php/db/list', wrap(async (req, res) => res.json(await dbMod.listDbs())));
  gsite('db', async (rec) => dbMod.infoFor(rec));
  api.post('/php/db/create', aud('php.db.create', async (req) => dbMod.createFor(C.get(q(req.body.slug)), req.body || {})));
  api.post('/php/db/password', aud('php.db.password', async (req) => dbMod.setPassword(C.get(q(req.body.slug)), req.body || {})));
  api.post('/php/db/dump', aud('php.db.dump', async (req) => dbMod.dumpJob(C.get(q(req.body.slug)), req.body || {})));
  api.post('/php/db/drop', aud('php.db.drop', async (req) => dbMod.dropFor(C.get(q(req.body.slug)))));
  gsite('db/tables', (rec) => dbMod.tablePeek(rec, {}));
  gsite('db/backups', async (rec) => ({ backups: await dbMod.listBackups(rec) }));

  /* ----------------------------------------------------------------- ssl */
  /* must not be '/php/ssl': express ignores the query string, so that path
     would shadow the per-site lookup below and only ever return the global list */
  api.get('/php/ssl/all', wrap(async (req, res) => res.json(await ssl.all())));
  gsite('ssl', async (rec) => ssl.statusFor(rec));
  api.post('/php/ssl/issue', aud('php.ssl.issue', async (req) => ssl.issueJob(req.body || {})));
  api.post('/php/ssl/renew', aud('php.ssl.renew', async () => ssl.renew()));
  api.post('/php/ssl/disable', aud('php.ssl.disable', async (req) => ssl.disable(req.body || {})));
  g('ssl/check', async (query) => ({ dns: await ssl.resolveCheck(C.domains(query.domains)) }));

  /* ---------------------------------------------------------------- logs */
  g('logs', async (query) => logs.tail(C.get(q(query.slug)), q(query.type) || 'access', query.lines, query.grep));
  g('stats', async (query) => logs.stats(C.get(q(query.slug)), { scan: query.scan }));
  api.post('/php/logs/rotate', aud('php.logs.rotate', async (req) => logs.rotate(req.body || {})));
  api.post('/php/logs/truncate', aud('php.logs.truncate', async (req) => logs.truncate(req.body || {})));
  gsite('logs/conf', async (rec) => ({ text: await logs.effectiveConf(rec) }));

  /* ------------------------------------------------------------- webhook */
  api.post('/php/webhook/:slug/:token', wrap(async (req, res) => {
    res.json(await deploy.handleWebhook(req.params));
  }));

  /* -------------------------------------------------------------- helper */
  function loadJobs(siteSlug) {
    const idx = S.readJson(jobs.INDEX, []);
    const list = (Array.isArray(idx) ? idx : []).slice().reverse()
      .filter((j) => !siteSlug || j.site === siteSlug).slice(0, 30);
    return list.map((j) => Object.assign({}, j, { active: j.state === 'running' }));
  }
}

/* ------------------------------------------------------------ record IO */
function publicRecord(rec) {
  const out = Object.assign({}, rec);
  if (out.db) out.db = { name: out.db.name, user: out.db.user, host: out.db.host, hasPassword: !!out.db.password };
  if (out.git) out.git = { url: out.git.url, branch: out.git.branch, subdir: out.git.subdir,
    depth: out.git.depth || 1, autoDeploy: !!out.git.autoDeploy, hasToken: !!out.git.token, hasKey: !!out.git.privateKey };
  out.webhookToken = undefined;
  return out;
}

async function saveEnv(body) {
  const rec = C.get(body.slug);
  const envObj = body.env && typeof body.env === 'object' ? body.env : null;
  if (envObj) {
    const clean = {};
    for (const k of Object.keys(envObj)) {
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(k)) C.bad('非法环境变量名：' + k);
      const v = String(envObj[k] == null ? '' : envObj[k]);
      if (v.length > 2000) C.bad('变量 ' + k + ' 的值过长');
      clean[k] = v;
    }
    rec.env = clean;
  }
  if (typeof body.envFile === 'string') {
    const f = C.sharedDir(rec.slug) + '/.env';
    if (body.envFile.length > 200000) C.bad('.env 过大');
    C.mkdirp(C.sharedDir(rec.slug));
    fs.writeFileSync(f, body.envFile, 'utf8');
    try { fs.chmodSync(f, 0o640); } catch (_) {}
    await C.fixOwnership(rec.slug, { path: C.sharedDir(rec.slug) });
  }
  rec.updatedAt = new Date().toISOString();
  C.put(rec);
  C.saveMeta(rec);
  const reload = body.envObjApplied === false ? null : await C.reloadFpm(rec.php || await site.defaultVersion());
  return ok('站点 ' + rec.slug + ' 的环境变量已保存' +
    (typeof body.envFile === 'string' ? '（shared/.env 已更新）' : '') +
    (reload ? '\n' + reload.service + ' = ' + reload.active : ''),
  { env: C.maskEnv(rec.env, false), reload: reload });
}

async function saveHooks(body) {
  const rec = C.get(body.slug);
  const hooks = body.hooks || {};
  const out = {};
  for (const phase of ['pre_deploy', 'post_deploy', 'on_rollback']) {
    const list = Array.isArray(hooks[phase]) ? hooks[phase] : (hooks[phase] ? [hooks[phase]] : []);
    out[phase] = list.map((x) => String(x).slice(0, 2000)).filter((x) => x.trim()).slice(0, 20);
  }
  rec.hooks = out;
  rec.updatedAt = new Date().toISOString();
  C.put(rec);
  C.saveMeta(rec);
  return ok('部署钩子已保存');
}

async function saveGit(body) {
  const rec = C.get(body.slug);
  const g = body.git || {};
  const url = String(g.url || '').trim();
  if (url && !/^(https?:\/\/|ssh:\/\/|git@)[\w.:@\/~.-]{3,300}$/i.test(url)) C.bad('Git 地址格式不正确：' + url);
  const cur = rec.git || {};
  rec.git = {
    url: url || cur.url || '',
    branch: String(g.branch || cur.branch || 'main').slice(0, 100),
    subdir: String(g.subdir || cur.subdir || '').slice(0, 120),
    depth: Math.max(1, Math.min(200, Number(g.depth || cur.depth) || 1)),
    autoDeploy: g.autoDeploy === undefined ? !!cur.autoDeploy : !!g.autoDeploy,
    token: g.token === undefined ? cur.token : String(g.token).slice(0, 400),
    tokenUser: g.tokenUser === undefined ? cur.tokenUser : String(g.tokenUser).slice(0, 80),
    privateKey: g.privateKey === undefined ? cur.privateKey : String(g.privateKey).slice(0, 8000),
  };
  rec.updatedAt = new Date().toISOString();
  C.put(rec);
  C.saveMeta(rec);
  try { fs.chmodSync(C.metaPath(rec.slug), 0o600); } catch (_) {}
  return ok('Git 配置已保存', { webhook: deploy.webhookFor(rec) });
}

async function rotateWebhook(body) {
  const rec = C.get(body.slug);
  rec.webhookToken = C.genSecret(16);
  C.put(rec);
  C.saveMeta(rec);
  return ok('webhook 令牌已重置（旧令牌立即失效）', { webhook: deploy.webhookFor(rec) });
}

module.exports = { register, redact, publicRecord, SECRET_FIELD };
