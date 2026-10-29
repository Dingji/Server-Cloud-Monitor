'use strict';
/*
 * Shared foundation for the PHP hosting module.
 *
 * Layout of every managed site (identical for all deploy channels, so rollback
 * is always available):
 *
 *   <base>/<slug>/
 *     releases/<ts>/        immutable checkout of the code
 *     current      -> releases/<ts>
 *     htdocs       -> current/<public dir>   (what nginx points at)
 *     shared/               .env, uploads, storage... linked into each release
 *     logs/                 access.log error.log deploy.log
 *     meta.json             authoritative per-site record
 *
 * nginx:  /etc/nginx/sites-available/panel-php-<slug>.conf  (+ sites-enabled symlink)
 * fpm:    /etc/php/<ver>/fpm/pool.d/panel-<slug>.conf       (own unix socket)
 *
 * Keeping these in sites-enabled means the existing 网站/Nginx page lists them
 * too; nothing here writes inside the relay site that carries /panel/.
 */
const fs = require('fs');
const path = require('path');

const S = require('../store');
const ex = require('../exec');

const PANEL = 'panel-php-';
const NGINX_AVAIL = '/etc/nginx/sites-available';
const NGINX_ENABL = '/etc/nginx/sites-enabled';
const PHP_ROOT = '/etc/php';
const RUN_PHP = '/run/php';

const BASE = () => process.env.CM_PHP_BASE || '/srv/php';
/* Upload staging lives under BASE, never in /tmp: cloud-monitor.service runs
   with PrivateTmp=true, so the panel cannot see files the operator drops in
   the host's /tmp. */
const STAGE = () => BASE() + '/uploads';
const FILE = 'php-sites.json';
const AUDIT_LOG = 'php-deploy.jsonl';

/* ---------------------------------------------------------------- errors */
function bad(msg, code) { const e = new Error(msg); e.status = code || 400; throw e; }
function ok(output, extra) { return Object.assign({ ok: true, output: String(output || '') }, extra || {}); }

/** Run a command, throwing an Error that carries the full output for the UI. */
async function sh(cmd, opts) {
  const o = opts || {};
  const r = await ex.run(cmd, Object.assign({ timeout: 20000 }, o));
  const text = String(r.stdout || '') + (r.stderr ? (r.stdout ? '\n' : '') + r.stderr : '');
  if (r.timedOut) {
    const e = new Error('命令超时（' + (o.timeout || 20000) + 'ms）: ' + cmd.split('\n')[0].slice(0, 90));
    e.output = text; e.status = 504; throw e;
  }
  if (r.code !== 0) {
    const e = new Error(o.failMsg || ('命令失败 (exit ' + r.code + ')'));
    e.output = text || e.message; e.status = o.status || 400; e.code = r.code; throw e;
  }
  return { code: r.code, stdout: String(r.stdout || ''), stderr: String(r.stderr || ''), text };
}

/* ------------------------------------------------------------- validation */
const SLUG_RE = /^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$|^[a-z0-9]$/;
const DOMAIN_RE = /^[a-z0-9]([a-z0-9._-]{0,249}[a-z0-9])?$/i;
const VER_RE = /^\d\.\d$/;

function slug(v) {
  const s = String(v || '').trim().toLowerCase();
  if (!SLUG_RE.test(s)) bad('站点标识只能用小写字母、数字和连字符，长度 2-40，且不能以连字符开头或结尾：' + s);
  if (s === 'panel' || s.startsWith('opencode') || s === 'default') bad('该标识被面板自身占用：' + s);
  return s;
}

function domains(list) {
  const out = [];
  const arr = Array.isArray(list) ? list : String(list || '').split(/[,\s]+/);
  for (const raw of arr) {
    const d = String(raw || '').trim().toLowerCase().replace(/\.$/, '');
    if (!d) continue;
    if (!DOMAIN_RE.test(d) || d.length > 253) bad('非法域名：' + raw);
    if (/[<>{};"'\\`$|]/.test(d)) bad('域名包含不允许的字符：' + raw);
    if (!out.includes(d)) out.push(d);
  }
  if (out.length > 20) bad('单站点域名上限 20 个');
  return out;
}

function port(v) {
  const n = Number(v);
  if (!Number.isInteger(n)) bad('端口必须是整数：' + v);
  if (n === 80 || n === 443) return n;
  if (n < 1024) bad('端口需在 1024-65535（特权端口只开放 80/443）：' + n);
  if ([22, 25, 53, 465, 587, 3306, 3389, 5432, 6379, 7000, 8787, 8790, 9000, 11211, 13345, 12838].includes(n)) {
    bad('该端口已被本机其他服务占用：' + n);
  }
  if (n >= 36000 && n <= 36999) bad('36000-36999 是 relayhub 端口池区间：' + n);
  return n;
}

/** Relative path inside a site, with traversal and symlink-escape rejected. */
function inSite(s, rel) {
  const clean = String(rel == null ? '' : rel).replace(/\\/g, '/');
  if (/[\u0000-\u001f]/.test(clean)) bad('路径包含非法字符');
  const abs = path.posix.resolve(path.posix.join('/', clean));
  if (abs === '/' || abs.startsWith('/..')) bad('路径越界：' + rel);
  return BASE() + '/' + s.slug + abs;
}

/* ------------------------------------------------------------------ meta */
function all() {
  const m = S.readJson(FILE, {});
  return m && typeof m === 'object' ? m : {};
}
function saveMap(m) { S.writeJson(FILE, m); return m; }
function get(s) {
  const rec = all()[slug(s)];
  if (!rec) bad('站点不存在：' + s, 404);
  return rec;
}
function peek(s) { try { return all()[String(s).toLowerCase()]; } catch (_) { return null; } }
function put(rec) { const m = all(); m[rec.slug] = rec; saveMap(m); return rec; }
function drop(s) { const m = all(); delete m[s]; saveMap(m); }

/** Per-site side copy of the record, 0600 because it holds db credentials. */
function saveMeta(rec) {
  try {
    mkdirp(siteDir(rec.slug));
    fs.writeFileSync(metaPath(rec.slug), JSON.stringify(rec, null, 2), 'utf8');
    fs.chmodSync(metaPath(rec.slug), 0o600);
  } catch (_) {}
  return rec;
}

const SITE_DEFAULTS = {
  php: null,
  type: 'php',            // php | static | proxy
  docRoot: '',            // public subdir inside the release, e.g. "public"
  preset: 'generic',
  domains: [],
  ports: [80],
  ssl: false,
  enabled: true,
  atomic: true,
  rewrite: null,          // custom rewrite block (preset === 'custom')
  phpOverrides: {},
  pool: {},
  hardening: { dotfiles: true, denyUploadExec: true, serverTokens: true },
  uploadDirs: [],
  openBasedir: true,
  disableFunctions: '',
  env: {},
  hooks: { pre_deploy: [], post_deploy: [], on_rollback: [] },
  db: null,               // { name, user, host }
  git: null,              // { url, branch, key, webhookToken, autoDeploy }
  notes: '',
  extraNginx: '',
};

/* Ops-level fields that update() must not silently reset. */
const PASS_THROUGH = ['proxyPort', 'fastcgiReadTimeout', 'shared', 'hooks', 'tablePrefix',
  'appVersion', 'customConf', 'sslEmail'];

/** Real php.ini directives are lowercase and dot-namespaced; camelCase means the
 *  caller sent a panel option by mistake, which php-fpm -t would then reject. */
const INI_KEY_RE = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)?$/;
function cleanIniKeys(obj, label) {
  const out = {};
  for (const k of Object.keys(obj || {})) {
    if (!INI_KEY_RE.test(k)) bad((label || 'ini') + '指令名不合法（应为小写，如 upload_max_filesize / opcache.enable）：' + k);
    out[k] = obj[k];
  }
  return out;
}

function normalize(body) {
  const b = body || {};
  const s = slug(b.slug);
  const ports = (Array.isArray(b.ports) ? b.ports : [b.port || 80])
    .map(port).filter((p, i, a) => a.indexOf(p) === i);
  if (!ports.length) bad('至少要监听一个端口');
  const passthrough = {};
  for (const k of PASS_THROUGH) {
    if (b[k] === undefined) continue;
    if (k === 'proxyPort') passthrough[k] = port(b[k] === 80 ? 9501 : b[k]);
    else if (k === 'fastcgiReadTimeout') passthrough[k] = S.clampNum(b[k], 1, 3600, 60);
    else if (Array.isArray(b[k])) passthrough[k] = b[k].slice(0, 30).map((x) => String(x).slice(0, 400));
    else passthrough[k] = b[k];
  }
  return Object.assign({}, SITE_DEFAULTS, b, passthrough, {
    slug: s,
    name: String(b.name || s).slice(0, 80),
    type: ['php', 'static', 'proxy'].includes(b.type) ? b.type : 'php',
    php: b.php ? String(b.php) : null,
    docRoot: String(b.docRoot || '').replace(/^\/+|\/+$/g, '').slice(0, 120),
    preset: String(b.preset || 'generic').slice(0, 40),
    domains: domains(b.domains),
    ports: ports,
    ssl: !!b.ssl,
    enabled: b.enabled === undefined ? true : !!b.enabled,
    rewrite: b.rewrite == null ? null : String(b.rewrite).slice(0, 8000),
    phpOverrides: cleanIniKeys(b.phpOverrides, 'php 覆盖值'),
    pool: cleanPoolKeys(b.pool),
    hardening: Object.assign({}, SITE_DEFAULTS.hardening, b.hardening || {}),
    uploadDirs: (Array.isArray(b.uploadDirs) ? b.uploadDirs : []).map((x) => String(x).slice(0, 120)).filter(Boolean),
    openBasedir: b.openBasedir === undefined ? true : !!b.openBasedir,
    disableFunctions: String(b.disableFunctions || '').slice(0, 600),
    env: (b.env && typeof b.env === 'object') ? b.env : {},
    notes: String(b.notes || '').slice(0, 500),
    extraNginx: String(b.extraNginx || '').slice(0, 20000),
  });
}

/* Pool directives the panel is willing to emit — anything else is php-fpm's own
   business and a typo would fail `php-fpm -t` for every site on the version. */
const POOL_KEYS = ['pm', 'pm.max_children', 'pm.start_servers', 'pm.min_spare_servers',
  'pm.max_spare_servers', 'pm.process_idle_timeout', 'pm.max_requests', 'pm.status_path',
  'request_terminate_timeout', 'request_slowlog_timeout', 'slowlog', 'rlimit_files',
  'rlimit_core', 'chroot', 'chdir', 'catch_workers_output', 'security.limit_extensions'];
function cleanPoolKeys(obj) {
  const out = {};
  for (const k of Object.keys(obj || {})) {
    if (!POOL_KEYS.includes(k)) bad('不支持的池参数：' + k + '（可用：' + POOL_KEYS.join(', ') + '）');
    const v = obj[k];
    if (typeof v === 'number' && (v < 0 || v > 1000000)) bad('池参数 ' + k + ' 数值超出范围');
    if (typeof v === 'string' && /[\r\n]/.test(v)) bad('池参数 ' + k + ' 含换行');
    out[k] = v;
  }
  if (out.pm && !['static', 'dynamic', 'ondemand'].includes(out.pm)) bad('pm 只能是 static / dynamic / ondemand');
  return out;
}

/* --------------------------------------------------------------- paths */
const siteDir = (s) => BASE() + '/' + s;
const releasesDir = (s) => siteDir(s) + '/releases';
const currentLink = (s) => siteDir(s) + '/current';
const htdocsLink = (s) => siteDir(s) + '/htdocs';
const sharedDir = (s) => siteDir(s) + '/shared';
const logsDir = (s) => siteDir(s) + '/logs';
const metaPath = (s) => siteDir(s) + '/meta.json';
const nginxFile = (s) => NGINX_AVAIL + '/' + PANEL + s + '.conf';
const nginxLink = (s) => NGINX_ENABL + '/' + PANEL + s + '.conf';
const poolFile = (ver, s) => PHP_ROOT + '/' + ver + '/fpm/pool.d/panel-' + s + '.conf';
const sockPath = (s) => RUN_PHP + '/panel-' + s + '.sock';
const siteUser = (s) => 'php-' + s;

/* ------------------------------------------------------------ fs utils */
function mkdirp(p, mode) {
  try { fs.mkdirSync(p, { recursive: true, mode: mode || 0o755 }); } catch (_) {}
}

/** Write only when content differs, keeping a timestamped copy of the old one. */
function writeVersioned(p, content, keepBackups) {
  let prev = null;
  try { prev = fs.readFileSync(p, 'utf8'); } catch (_) {}
  if (prev === content) return { changed: false, prev: prev };
  if (prev != null && keepBackups !== false) {
    try { fs.writeFileSync(p + '.bak.' + Date.now(), prev, 'utf8'); } catch (_) {}
  }
  mkdirp(path.dirname(p));
  fs.writeFileSync(p, content, 'utf8');
  return { changed: true, prev: prev };
}

function readIf(p) { try { return fs.readFileSync(p, 'utf8'); } catch (_) { return null; } }
function exists(p) { try { fs.statSync(p); return true; } catch (_) { return false; } }

/** Recursive size, bounded so a huge tree cannot stall the panel. */
async function dirSize(p) {
  const r = await sh('du -sxk ' + S.shq(p) + ' 2>/dev/null | cut -f1', { timeout: 15000 }).catch(() => ({ stdout: '' }));
  return Number(String(r.stdout).trim()) * 1024 || 0;
}

async function fileCount(p) {
  const r = await sh('find ' + S.shq(p) + ' -type f 2>/dev/null | head -200000 | wc -l', { timeout: 20000 })
    .catch(() => ({ stdout: '0' }));
  return Number(String(r.stdout).trim()) || 0;
}

/* ------------------------------------------------------------ ownership */
async function fixOwnership(s, opts) {
  const o = opts || {};
  const user = o.user || siteUser(s);
  const root = o.path || siteDir(s);
  // files 640 / dirs 750, group www-data so nginx can read through the socket owner only
  await sh('chown -R ' + S.shq(user) + ':www-data ' + S.shq(root) + ' 2>/dev/null || true', { timeout: 60000 });
  await sh('find ' + S.shq(root) + ' -type d -exec chmod 750 {} + 2>/dev/null || true', { timeout: 60000 });
  await sh('find ' + S.shq(root) + ' -type f -exec chmod 640 {} + 2>/dev/null || true', { timeout: 60000 });
}

async function ensureUser(s) {
  const u = siteUser(s);
  const r = await sh('id -u ' + S.shq(u) + ' 2>/dev/null || echo MISSING');
  if (String(r.stdout).trim() !== 'MISSING') return { created: false, user: u };
  await sh('useradd -r -M -d ' + S.shq(siteDir(s)) + ' -g www-data -s /usr/sbin/nologin ' + S.shq(u) + ' 2>&1',
    { failMsg: '创建站点系统用户失败（检查 useradd 是否存在）' });
  return { created: true, user: u };
}

async function removeUser(s) {
  await sh('userdel -r ' + S.shq(siteUser(s)) + ' 2>/dev/null || true', { timeout: 15000 }).catch(() => {});
}

/** Delete the site user and report whether it actually went away. */
async function removeUserChecked(s) {
  const u = siteUser(s);
  await sh('userdel -r ' + S.shq(u) + ' 2>&1', { timeout: 20000 }).catch(() => {});
  const r = await sh('id -u ' + S.shq(u) + ' 2>/dev/null || echo GONE', { timeout: 10000 })
    .catch(() => ({ stdout: 'GONE' }));
  return /GONE/.test(String(r.stdout));
}

/* ------------------------------------------------------- nginx handling */
/**
 * Reload nginx only if `nginx -t` passes; otherwise restore the previous state.
 * `apply()` performs the link/unlink right before the test so the check covers
 * the exact configuration that is about to go live.
 */
async function withNginxGuard(apply, undo) {
  const before = await sh('nginx -t 2>&1', { timeout: 20000 }).catch(() => null);
  await apply();
  const t = await sh('nginx -t 2>&1', { timeout: 20000 });
  if (t.code !== 0) {
    if (undo) { try { await undo(); } catch (_) {} }
    const e = new Error('nginx 配置校验未通过，改动已自动回滚');
    e.output = t.text + (before ? '\n\n（改动前的校验输出）\n' + before.text : '');
    e.status = 400;
    throw e;
  }
  const reload = await sh('systemctl reload nginx 2>&1 || nginx -s reload 2>&1', { timeout: 20000 });
  if (reload.code !== 0) {
    const e = new Error('nginx 校验通过但重载失败');
    e.output = reload.text;
    throw e;
  }
  return { output: t.stderr || t.stdout || 'nginx: configuration file /etc/nginx/nginx.conf test is successful' };
}

async function reloadFpm(ver) {
  const svc = 'php' + ver + '-fpm';
  const r = await sh('systemctl reload ' + S.shq(svc) + ' 2>&1 || systemctl restart ' + S.shq(svc) + ' 2>&1',
    { timeout: 30000 }).catch((e) => ({ text: e.output || e.message }));
  const active = await sh('systemctl is-active ' + S.shq(svc) + ' 2>&1');
  return { service: svc, output: String(r.text || '').trim(), active: String(active.stdout).trim() === 'active' };
}

async function testFpmConfig(ver) {
  return sh('php-fpm' + S.shq(ver) + ' -t 2>&1', { timeout: 20000 });
}

/**
 * Validate a pool file without touching the live service: point php-fpm at a
 * temporary global config that includes only this pool. `-y` selects that
 * config — a bare positional argument is a usage error (exit 64).
 */
async function checkPool(ver, content, slugName) {
  const tmp = '/tmp/panel-php-pool-' + slugName + '-' + Date.now();
  try {
    mkdirp(tmp + '/pool.d');
    fs.writeFileSync(tmp + '/php-fpm.conf',
      '[global]\npid = ' + tmp + '/fpm.pid\nerror_log = ' + tmp + '/fpm.log\nlog_level = warning\n' +
      'include = ' + tmp + '/pool.d/*.conf\n');
    fs.writeFileSync(tmp + '/pool.d/site.conf', content, 'utf8');
    const r = await sh('php-fpm' + S.shq(ver) + ' -t -y ' + S.shq(tmp + '/php-fpm.conf') + ' 2>&1', { timeout: 20000 });
    return { ok: r.code === 0, output: r.text };
  } finally {
    try { fs.rmSync(tmp, { recursive: true, force: true }); } catch (_) {}
  }
}

/* ------------------------------------------------------------- versions */
let verCache = null;
async function installedVersions(force) {
  if (verCache && !force) return verCache;
  const r = await sh('ls -1 ' + S.shq(PHP_ROOT) + ' 2>/dev/null', { timeout: 10000 }).catch(() => ({ stdout: '' }));
  verCache = String(r.stdout).split('\n').map((x) => x.trim()).filter((x) => /^\d\.\d+$/.test(x)).sort();
  return verCache;
}

async function defaultVersion(force) {
  const vs = await installedVersions(force);
  if (!vs.length) {
    const e = new Error('本机尚未安装任何 PHP-FPM 版本，请先在「PHP 环境」里安装');
    e.status = 409;
    throw e;
  }
  return vs[vs.length - 1];
}

/* ------------------------------------------------------------- utilities */
function tsName(d) {
  const t = d || new Date();
  const p = (n, w) => String(n).padStart(w || 2, '0');
  return '' + t.getFullYear() + p(t.getMonth() + 1) + p(t.getDate()) + '-' + p(t.getHours()) + p(t.getMinutes()) + p(t.getSeconds());
}

function genSecret(len) {
  const chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const crypto = require('crypto');
  const buf = crypto.randomBytes(len || 24);
  let s = '';
  for (let i = 0; i < (len || 24); i++) s += chars[buf[i] % chars.length];
  return s;
}

/** Mask anything that looks like a credential before it reaches the API/UI. */
const SECRET_RE = /(password|passwd|secret|token|salt|apikey|authkey|privatekey|accesskey|sessionkey|dbpass|pwd|crypt)/i;
function isSecretKey(k) {
  const n = String(k).toLowerCase().replace(/[^a-z0-9]/g, '');
  return SECRET_RE.test(n) || n === 'key' || n.endsWith('key');
}
function maskEnv(env, showSecrets) {
  const out = {};
  for (const k of Object.keys(env || {})) {
    const v = String(env[k] == null ? '' : env[k]);
    out[k] = showSecrets || !isSecretKey(k) ? v : '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022' + v.slice(-2);
  }
  return out;
}

function logEvent(obj) { S.appendLine(AUDIT_LOG, Object.assign({ ts: new Date().toISOString() }, obj)); }

module.exports = {
  S, ex, BASE, STAGE, PANEL, NGINX_AVAIL, NGINX_ENABL, PHP_ROOT, RUN_PHP, FILE, SITE_DEFAULTS,
  bad, ok, sh, slug, domains, port, inSite, normalize, isSecretKey,
  all, saveMap, get, peek, put, drop, saveMeta,
  siteDir, releasesDir, currentLink, htdocsLink, sharedDir, logsDir, metaPath,
  nginxFile, nginxLink, poolFile, sockPath, siteUser,
  mkdirp, writeVersioned, readIf, exists, dirSize, fileCount,
  fixOwnership, ensureUser, removeUser, removeUserChecked, withNginxGuard, reloadFpm, testFpmConfig, checkPool,
  installedVersions, defaultVersion, genId: S.genId,
  tsName, genSecret, maskEnv, logEvent,
};
