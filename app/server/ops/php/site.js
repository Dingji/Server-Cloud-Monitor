'use strict';
/*
 * Site lifecycle: generate nginx + PHP-FPM pool config, apply with a validation
 * gate, and expose CRUD/enable/disable/delete.
 */
const fs = require('fs');
const path = require('path');
const C = require('./common');
const P = require('./presets');

const S = C.S;
const ok = C.ok;

const HEADER = (slug) => `# ─────────────────────────────────────────────────────────────
# Managed by Cloud Monitor panel — PHP site "${slug}"
# Generated file. Edits made in the panel survive reloads; edits made directly
# here are overwritten the next time the site is saved.
# To hand this file back to the operator, delete it and disable the site.
# ─────────────────────────────────────────────────────────────
`;

/** php-fpm's parser only accepts `;` comments — a `#` line is a fatal parse error. */
const POOL_HEADER = (slug) => `; ─────────────────────────────────────────────────────────────
; Managed by Cloud Monitor panel — PHP-FPM pool for site "${slug}"
; Generated file; edits made in the panel survive reloads.
; ─────────────────────────────────────────────────────────────
`;

/* Directives PHP refuses to accept per-pool (PHP_INI_SYSTEM). Only the site's
   open_basedir / disable_functions / sys_temp_dir are set here, via
   php_admin_value; anything else must go through the ini editor. */
const INI_SYSTEM_KEYS = ['cgi.fix_pathinfo', 'doc_root', 'user_dir', 'extension_dir',
  'enable_dl', 'engine', 'short_open_tag', 'asp_tags', 'allow_url_include'];

/** Effective per-site ini: panel defaults, then the operator's overrides. */
function siteIni(rec) {
  const ini = Object.assign({
    memory_limit: '256M',
    max_execution_time: '60',
    max_input_time: '60',
    post_max_size: '64M',
    upload_max_filesize: '64M',
    max_file_uploads: '60',
    default_socket_timeout: '60',
    'date.timezone': 'Asia/Shanghai',
    display_errors: 'Off',
    log_errors: 'On',
    expose_php: 'Off',
    'session.cookie_httponly': '1',
    'session.cookie_samesite': 'Lax',
  }, rec.phpOverrides || {});
  const out = {};
  for (const k of Object.keys(ini)) {
    if (INI_SYSTEM_KEYS.includes(k)) {
      if (rec.phpOverrides && k in rec.phpOverrides) {
        throw new Error('指令 ' + k + ' 是 PHP_INI_SYSTEM，不能按站点设置，请改用「PHP 环境 → 全局覆盖」');
      }
      continue;
    }
    out[k] = ini[k];
  }
  return out;
}

/* ------------------------------------------------------------------ pool */
function poolConf(rec) {
  const s = rec.slug;
  const dir = C.siteDir(s);
  const ini = siteIni(rec);

  const pool = Object.assign({
    pm: 'ondemand',
    'pm.max_children': 12,
    'pm.process_idle_timeout': '10s',
    'pm.max_requests': 1000,
    'request_terminate_timeout': 0,
    'slowlog': dir + '/logs/php-slow.log',
    'request_slowlog_timeout': '10s',
  }, rec.pool || {});
  if (pool.pm === 'dynamic') {
    const max = Number(pool['pm.max_children']) || 12;
    pool['pm.start_servers'] = pool['pm.start_servers'] || Math.max(1, Math.min(4, Math.floor(max / 3)));
    pool['pm.min_spare_servers'] = pool['pm.min_spare_servers'] || Math.max(1, Math.min(2, Math.floor(max / 6)));
    pool['pm.max_spare_servers'] = pool['pm.max_spare_servers'] || Math.max(1, Math.min(4, Math.floor(max / 3)));
  }

  const basedir = rec.openBasedir
    ? `php_admin_value[open_basedir] = ${dir}:${C.RUN_PHP}:/tmp:/proc/meminfo`
    : '; php_admin_value[open_basedir] = (disabled)';
  const disabled = String(rec.disableFunctions || '').trim() ||
    'exec,passthru,shell_exec,system,proc_open,popen,curl_multi_exec,show_source,pcntl_alarm,pcntl_fork,pcntl_waitpid';

  const lines = [];
  lines.push(POOL_HEADER(s));
  lines.push('[' + 'panel-' + s + ']');
  lines.push('user = ' + C.siteUser(s));
  lines.push('group = www-data');
  lines.push('listen = ' + C.sockPath(s));
  lines.push('listen.owner = www-data');
  lines.push('listen.group = www-data');
  lines.push('listen.mode = 0660');
  lines.push('');
  lines.push('; ---- process management (' + pool.pm + ') ----');
  for (const k of ['pm', 'pm.max_children', 'pm.start_servers', 'pm.min_spare_servers',
    'pm.max_spare_servers', 'pm.process_idle_timeout', 'pm.max_requests']) {
    if (pool[k] !== undefined && pool[k] !== null && pool[k] !== '') lines.push(k + ' = ' + pool[k]);
  }
  lines.push('pm.status_path = /fpm-status');
  lines.push('ping.path = /fpm-ping');
  lines.push('');
  lines.push('; ---- logging ----');
  lines.push('catch_workers_output = yes');
  lines.push('decorate_workers_output = no');
  lines.push('clear_env = no');
  lines.push('slowlog = ' + pool.slowlog);
  lines.push('request_slowlog_timeout = ' + pool.request_slowlog_timeout);
  if (pool.request_terminate_timeout && pool.request_terminate_timeout !== 0) {
    lines.push('request_terminate_timeout = ' + pool.request_terminate_timeout);
  }
  lines.push('');
  lines.push('; ---- isolation ----');
  lines.push(basedir);
  lines.push('php_admin_value[disable_functions] = ' + disabled);
  lines.push('php_admin_value[sys_temp_dir] = ' + dir + '/shared/tmp');
  lines.push('php_value[session.save_path] = ' + dir + '/shared/sessions');
  lines.push('');
  lines.push('; ---- php.ini overrides for this site ----');
  for (const k of Object.keys(ini).sort()) {
    const v = ini[k];
    if (v === '' || v === null || v === undefined) continue;
    lines.push('php_value[' + k + '] = ' + v);
  }
  if (rec.env && Object.keys(rec.env).length) {
    lines.push('');
    lines.push('; ---- environment (visible to getenv()) ----');
    for (const k of Object.keys(rec.env)) {
      if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(k)) continue;
      lines.push('env[' + k + '] = "' + String(rec.env[k]).replace(/"/g, '\\"') + '"');
    }
  }
  lines.push('');
  return lines.join('\n');
}

/* --------------------------------------------------------------- nginx */
/** Largest upload the site accepts, as an nginx size (never smaller than the ini). */
function nginxBodyLimit(rec) {
  const raw = String((rec.phpOverrides && (rec.phpOverrides.upload_max_filesize ||
    rec.phpOverrides.post_max_size)) || '64M').toUpperCase();
  const n = Number(raw.replace(/[^0-9.]/g, '')) || 64;
  const mb = /G/.test(raw) ? n * 1024 : /K/.test(raw) ? Math.max(1, n / 1024) : n;
  return Math.max(1, Math.ceil(mb)) + 'm';
}

/** Strip the leading newline of a template literal without eating its indent. */
function frag(t) { return String(t).replace(/^\s*\n/, '').replace(/\s+$/, ''); }

/**
 * Ports split by protocol. With HTTPS on, port 80 must live in the redirect
 * server only — declaring it twice for the same server_name makes nginx warn
 * about a conflicting server name and the redirect never runs.
 */
function portPlan(rec, opts) {
  const o = opts || {};
  const sslOn = o.ssl === undefined ? !!rec.ssl : !!o.ssl;
  const all = (o.ports || rec.ports || [80]).slice();
  if (!sslOn) return { sslOn: false, main: all, redirectFrom: [] };
  const main = all.filter((p) => p !== 80);
  return {
    sslOn: true,
    main: main.length ? main : [443],
    redirectFrom: all.includes(80) ? [80] : [],
  };
}

function listenLines(ports, useSsl) {
  const out = [];
  for (const p of ports) {
    const suffix = useSsl ? ' ssl' : '';
    out.push('    listen ' + p + suffix + ';');
    out.push('    listen [::]:' + p + suffix + ';');
  }
  /* `http2 on` is one switch per server, not per listen line. */
  if (useSsl) out.push('    http2 on;');
  return out.join('\n');
}

function serverBlock(rec, opts) {
  const o = opts || {};
  const s = rec.slug;
  const pre = P.presetOf(rec.preset);
  const root = C.htdocsLink(s);
  const sock = C.sockPath(s);
  const isPhp = (rec.type === 'php' || !rec.type) && !pre.noPhp;
  const doms = (rec.domains && rec.domains.length) ? rec.domains : ['_'];
  const plan = portPlan(rec, o);

  const body = [];
  body.push(listenLines(plan.main, plan.sslOn));
  body.push('    server_name ' + doms.join(' ') + ';');
  body.push('');
  body.push('    root ' + root + ';');
  body.push('    index ' + (pre.index || 'index.php index.html index.htm') + ';');
  body.push('    access_log ' + C.logsDir(s) + '/access.log;');
  body.push('    error_log  ' + C.logsDir(s) + '/error.log;');
  body.push('    client_max_body_size ' + nginxBodyLimit(rec) + ';');
  body.push('');
  body.push(frag(P.SECURITY_HEADERS));

  if (plan.sslOn) {
    const certHost = (rec.domains && rec.domains[0]) || s;
    body.push('');
    body.push([
      '    ssl_certificate     ' + certDir(rec, certHost) + '/fullchain.pem;',
      '    ssl_certificate_key ' + certDir(rec, certHost) + '/privkey.pem;',
      '    ssl_protocols TLSv1.2 TLSv1.3;',
      '    ssl_prefer_server_ciphers off;',
      '    ssl_session_cache shared:SSL_' + s + ':8m;',
      '    ssl_session_timeout 1d;',
      '    ssl_stapling on;',
      '    ssl_stapling_verify on;',
    ].join('\n'));
  }

  if (rec.hardening && rec.hardening.dotfiles) {
    body.push('');
    body.push(frag(P.DOTFILE_GUARD));
  }

  // ACME challenge must always resolve from the filesystem, never via PHP
  body.push('');
  body.push([
    '    location ^~ /.well-known/acme-challenge/ {',
    '        root ' + root + ';',
    '        default_type "text/plain";',
    '        try_files $uri =404;',
    '    }',
  ].join('\n'));

  /* Deny rules must precede the PHP handler: among regex locations nginx takes
     the first match in file order, so a later `~ \.php` would win otherwise. */
  const denyDirs = [].concat(pre.denyPhpIn || [], rec.uploadDirs || []);
  if (rec.hardening && rec.hardening.denyUploadExec && isPhp && denyDirs.length) {
    body.push('');
    body.push('    # 可写目录禁止执行 PHP（面板按预设与站点配置生成）');
    for (const d of denyDirs) {
      const clean = String(d).replace(/^\/+|\/+$/g, '').replace(/[^\w./-]/g, '');
      if (!clean) continue;
      body.push('    location ~* ^/' + clean.replace(/\./g, '\\.') + '/.*\\.(php|phar|phtml|p[lm]5?)$ { deny all; }');
    }
  }

  if (pre.hardening) {
    body.push('');
    body.push(frag(pre.hardening));
  }

  if (rec.type === 'proxy') {
    const proxyBlock = (pre.locationBlock || '').replace(/@PROXY_PORT@/g, rec.proxyPort || 9501);
    body.push('');
    body.push(frag(proxyBlock));
  } else {
    let loc = rec.preset === 'custom' && rec.rewrite ? rec.rewrite : (pre.locationBlock || '');
    loc = loc.replace(/@SOCK@/g, sock).replace(/@ROOT@/g, root);
    if (loc.trim()) { body.push(''); body.push(frag(loc)); }
    if (isPhp) {
      body.push('');
      body.push(frag(P.PHP_BLOCK(sock, { readTimeout: rec.fastcgiReadTimeout })));
    }
    body.push('');
    body.push(frag(P.STATIC_CACHE));
    if (isPhp) {
      /* loopback-only pool introspection; the panel reads it for pool health */
      body.push('');
      body.push([
        '    location ~ ^/(fpm-status|fpm-ping)$ {',
        '        access_log off;',
        '        allow 127.0.0.1;',
        '        deny all;',
        '        include fastcgi_params;',
        '        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;',
        '        fastcgi_pass unix:' + sock + ';',
        '    }',
      ].join('\n'));
    }
  }

  if (rec.extraNginx && String(rec.extraNginx).trim()) {
    body.push('');
    body.push('    # ---- operator-supplied directives ----');
    body.push(String(rec.extraNginx).trim());
  }

  return 'server {\n' + body.join('\n').replace(/\n{3,}/g, '\n\n') + '\n}';
}

/** Certificate directory, honouring certbot's `-2` suffix for a second domain. */
function certDir(rec, fallback) {
  const name = (rec.sslCertName || rec.sslDomains && rec.sslDomains[0] || fallback || rec.slug);
  return '/etc/letsencrypt/live/' + String(name).replace(/[^a-z0-9.*_-]/gi, '');
}

function redirectBlock(rec) {
  const doms = rec.domains || [];
  if (!doms.length) return '';
  const root = C.htdocsLink(rec.slug);
  return `server {
    listen 80;
    listen [::]:80;
    server_name ${doms.join(' ')};

    # ACME 续期必须继续走 HTTP
    location ^~ /.well-known/acme-challenge/ {
        root ${root};
        default_type "text/plain";
        try_files $uri =404;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}`;
}

function nginxConf(rec, opts) {
  const o = opts || {};
  const plan = portPlan(rec, o);
  let out = HEADER(rec.slug) + serverBlock(rec, o) + '\n';
  if (plan.sslOn && plan.redirectFrom.length && !o.noRedirect) {
    const r = redirectBlock(rec);
    if (r) out += '\n# ── HTTP → HTTPS 跳转（同一站点的 80 端口） ──\n' + r + '\n';
  }
  return out;
}

/* --------------------------------------------------------------- apply */
/** Write pool + nginx files and activate them, rolling back on any failure. */
async function apply(rec, opts) {
  const o = opts || {};
  const s = rec.slug;
  const ver = rec.php || (await defaultVersion());
  const results = { php: ver, pool: null, nginx: null, reload: null, fpmReload: null };

  const pool = poolConf(rec);
  const pf = C.poolFile(ver, s);
  const check = await C.checkPool(ver, pool, s);
  if (!check.ok) {
    const e = new Error('PHP-FPM 池配置校验未通过：' + s);
    e.output = check.output;
    throw e;
  }
  const pw = C.writeVersioned(pf, pool);
  results.pool = pw.changed ? 'updated' : 'unchanged';

  const conf = nginxConf(rec, { ssl: !!rec.ssl });
  const nw = C.writeVersioned(C.nginxFile(s), conf);
  results.nginx = nw.changed ? 'updated' : 'unchanged';

  const wantLink = rec.enabled !== false;
  const hasLink = C.exists(C.nginxLink(s));
  if (wantLink === hasLink && !nw.changed) {
    results.reload = 'skipped';
  } else {
    const act = await C.withNginxGuard(
      () => C.sh(wantLink
        ? 'ln -sf ' + S.shq(C.nginxFile(s)) + ' ' + S.shq(C.nginxLink(s))
        : 'rm -f ' + S.shq(C.nginxLink(s))),
      () => (wantLink
        ? C.sh('rm -f ' + S.shq(C.nginxLink(s)))
        : (hasLink ? C.sh('ln -sf ' + S.shq(C.nginxFile(s)) + ' ' + S.shq(C.nginxLink(s))) : Promise.resolve()))
    );
    results.reload = act.output;
  }

  // open the firewall for non-80/443 listener ports (additive; never removes a rule)
  for (const p of rec.ports || []) {
    if (p === 80 || p === 443) continue;
    if (o.skipUfw) continue;
    const r = await C.sh('ufw status 2>/dev/null | grep -q ' + S.shq(p + '/tcp') +
      ' || ufw allow ' + S.shq(p + '/tcp') + ' comment "panel-php ' + s + '" 2>&1 | tail -2',
      { timeout: 20000 }).catch(() => ({ text: 'ufw 不可用，端口 ' + p + ' 需手动放行' }));
    if (String(r.text || '').trim()) results['ufw' + p] = String(r.text).trim();
  }

  const fr = await C.reloadFpm(ver);
  results.fpmReload = fr;
  return results;
}

/* ------------------------------------------------------------ versions */
const installedVersions = (force) => C.installedVersions(force);
const defaultVersion = (force) => C.defaultVersion(force);

/* ------------------------------------------------------------- views */
async function statusOf(rec) {
  const s = rec.slug;
  const out = {
    slug: s, name: rec.name || s, preset: rec.preset, type: rec.type,
    php: rec.php, domains: rec.domains || [], ports: rec.ports || [],
    ssl: !!rec.ssl, enabled: rec.enabled !== false,
    linked: C.exists(C.nginxLink(s)),
    confExists: C.exists(C.nginxFile(s)),
    poolExists: rec.php ? C.exists(C.poolFile(rec.php, s)) : false,
    sockExists: C.exists(C.sockPath(s)),
    current: null, htdocs: null, releases: 0,
    docSize: 0, fileCount: 0,
    urls: [],
    createdAt: rec.createdAt || null, updatedAt: rec.updatedAt || null,
    db: rec.db ? { name: rec.db.name, user: rec.db.user } : null,
    git: rec.git ? { url: rec.git.url, branch: rec.git.branch, autoDeploy: !!rec.git.autoDeploy } : null,
    notes: rec.notes || '',
  };
  try { out.current = fs.readlinkSync(C.currentLink(s)); } catch (_) {}
  try { out.htdocs = fs.realpathSync(C.htdocsLink(s)); } catch (_) {}
  const rl = await C.sh('ls -1 ' + S.shq(C.releasesDir(s)) + ' 2>/dev/null | wc -l', { timeout: 10000 })
    .catch(() => ({ stdout: '0' }));
  out.releases = Number(String(rl.stdout).trim()) || 0;
  if (out.htdocs) {
    out.docSize = await C.dirSize(out.htdocs);
    out.fileCount = await C.fileCount(out.htdocs);
  }
  const scheme = rec.ssl ? 'https' : 'http';
  for (const d of (rec.domains || []).slice(0, 3)) out.urls.push(scheme + '://' + d + '/');
  for (const p of (rec.ports || []).filter((x) => x !== 80 && x !== 443)) out.urls.push('http://<本机IP>:' + p + '/');
  return out;
}

async function listSites() {
  const recs = Object.values(C.all());
  const out = [];
  for (const r of recs) out.push(await statusOf(r));
  out.sort((a, b) => String(a.slug).localeCompare(String(b.slug)));
  return out;
}

/* --------------------------------------------------------------- CRUD */
async function create(body) {
  const rec = C.normalize(body);
  if (C.peek(rec.slug)) C.bad('站点已存在：' + rec.slug, 409);
  if (!rec.domains.length && (rec.ports || []).every((p) => p === 80 || p === 443)) {
    C.bad('纯 IP 访问必须指定一个 1024 以上的监听端口（80/443 由域名匹配区分）');
  }
  if (!rec.php) rec.php = await defaultVersion();

  rec.createdAt = new Date().toISOString();
  rec.updatedAt = rec.createdAt;
  rec.webhookToken = C.genSecret(16);

  C.mkdirp(C.releasesDir(rec.slug), 0o755);
  C.mkdirp(C.sharedDir(rec.slug) + '/tmp', 0o775);
  C.mkdirp(C.sharedDir(rec.slug) + '/sessions', 0o775);
  C.mkdirp(C.logsDir(rec.slug));

  const initial = C.releasesDir(rec.slug) + '/' + C.tsName();
  C.mkdirp(initial + '/htdocs-placeholder');
  try { fs.writeFileSync(initial + '/index.php', defaultIndex(rec), 'utf8'); } catch (_) {}
  try { fs.unlinkSync(initial + '/htdocs-placeholder'); fs.rmdirSync(initial + '/htdocs-placeholder'); } catch (_) {}

  rec.releases = [{ id: path.basename(initial), at: rec.createdAt, kind: 'init', note: '面板创建站点时的初始版本' }];
  rec.activeRelease = path.basename(initial);
  C.put(rec);
  await C.ensureUser(rec.slug);

  await linkHtdocs(rec.slug, path.basename(initial), rec.docRoot);
  await C.fixOwnership(rec.slug, { path: C.siteDir(rec.slug) });
  C.saveMeta(rec);

  const applied = await apply(rec).catch(async (e) => {
    /* A half-created site is worse than none: undo the record, the user and the
       directory so the operator can retry without a "already exists" clash. */
    C.drop(rec.slug);
    await C.sh('rm -rf ' + S.shq(C.siteDir(rec.slug)) + ' ' + S.shq(C.nginxFile(rec.slug)) +
      ' ' + S.shq(C.poolFile(rec.php, rec.slug)), { timeout: 30000 }).catch(() => {});
    await C.removeUser(rec.slug);
    C.logEvent({ kind: 'site.create.rollback', site: rec.slug, error: e.message });
    throw e;
  });
  C.logEvent({ kind: 'site.create', site: rec.slug, by: 'panel' });
  const custom = rec.ports.filter((x) => x !== 80 && x !== 443);
  return Object.assign({ ok: true, site: await statusOf(rec), apply: applied },
    { output: '站点 ' + rec.slug + ' 已创建（PHP ' + rec.php + '，监听 ' + rec.ports.join('/') + '）'
      + (custom.length ? '\n自定义端口已在本机防火墙放行；若本机在云上，还需在云控制台的安全组里放行 '
        + custom.join('/') + ' 才能从公网访问。' : '') });
}

function defaultIndex(rec) {
  if (rec.type === 'static' || P.presetOf(rec.preset).noPhp) {
    return '<!doctype html>\n<html lang="zh-CN"><meta charset="utf-8">\n<title>' + (rec.name || rec.slug) +
      '</title>\n<style>body{font-family:system-ui,sans-serif;background:#0b1220;color:#e6edf7;display:grid;place-items:center;height:100vh;margin:0}' +
      'div{text-align:center}code{background:#16223a;padding:2px 8px;border-radius:4px}</style>\n' +
      '<div><h1>' + (rec.name || rec.slug) + '</h1><p>站点骨架已由 Cloud Monitor 面板创建，等待部署内容。</p>' +
      '<p><code>' + rec.slug + '</code></p></div>\n';
  }
  return '<?php\n// 面板生成的占位入口，部署后被覆盖\nphpinfo();\n';
}

/** current -> releases/<id>, htdocs -> current/<docRoot> */
async function linkHtdocs(slugName, releaseId, docRoot) {
  const rel = C.releasesDir(slugName) + '/' + releaseId;
  const targetRoot = docRoot ? (rel + '/' + String(docRoot).replace(/^\/+/, '')) : rel;
  const cmds = [
    'ln -sfn ' + S.shq(rel) + ' ' + S.shq(C.currentLink(slugName)),
    'ln -sfn ' + S.shq(targetRoot) + ' ' + S.shq(C.htdocsLink(slugName)),
  ];
  await C.sh(cmds.join(' && '), { failMsg: '创建 releases/current/htdocs 软链失败' });
}

async function update(body) {
  const existing = C.get(body.slug);
  const rec = Object.assign({}, normalizeForUpdate(body, existing));
  rec.updatedAt = new Date().toISOString();
  rec.createdAt = existing.createdAt;
  rec.releases = existing.releases;
  rec.activeRelease = existing.activeRelease;
  rec.db = body.keepDb === false ? null : existing.db;
  rec.git = existing.git;
  rec.webhookToken = existing.webhookToken;
  C.put(rec);
  C.saveMeta(rec);
  const applied = await apply(rec);
  return Object.assign({ ok: true, site: await statusOf(rec), apply: applied },
    { output: '站点 ' + rec.slug + ' 配置已更新' });
}

function normalizeForUpdate(body, existing) {
  const b = Object.assign({}, existing, body || {}, {
    slug: existing.slug,
    domains: body.domains === undefined ? existing.domains : domainsOr(body.domains),
    ports: body.ports === undefined && body.port === undefined ? existing.ports
      : (Array.isArray(body.ports) ? body.ports : [body.port || 80]),
    phpOverrides: Object.assign({}, existing.phpOverrides || {}, body.phpOverrides || {}),
    pool: Object.assign({}, existing.pool || {}, body.pool || {}),
    env: Object.assign({}, existing.env || {}, body.env || {}),
    hardening: Object.assign({}, existing.hardening || {}, body.hardening || {}),
  });
  if (b.env) {
    for (const k of Object.keys(b.env)) if (String(b.env[k]).indexOf('•') >= 0) delete b.env[k];
  }
  return C.normalize(b);
}
function domainsOr(v) { return v === undefined ? [] : v; }

async function toggle(slugName, enable) {
  const rec = C.get(slugName);
  rec.enabled = !!enable;
  rec.updatedAt = new Date().toISOString();
  C.put(rec);
  const hasLink = C.exists(C.nginxLink(rec.slug));
  if (hasLink !== rec.enabled) {
    const act = await C.withNginxGuard(
      () => C.sh(rec.enabled
        ? 'ln -sf ' + S.shq(C.nginxFile(rec.slug)) + ' ' + S.shq(C.nginxLink(rec.slug))
        : 'rm -f ' + S.shq(C.nginxLink(rec.slug))),
      () => C.sh(rec.enabled
        ? 'rm -f ' + S.shq(C.nginxLink(rec.slug))
        : 'ln -sf ' + S.shq(C.nginxFile(rec.slug)) + ' ' + S.shq(C.nginxLink(rec.slug)))
    );
    return ok('站点 ' + rec.slug + (rec.enabled ? ' 已启用' : ' 已停用') + '\n' + act.output);
  }
  return ok('站点 ' + rec.slug + (rec.enabled ? ' 已处于启用状态' : ' 已处于停用状态'));
}

/** Delete a site. Code goes to the ops trash dir, configs are removed then nginx reloaded. */
async function destroy(body) {
  const rec = C.get(body.slug);
  const s = rec.slug;
  const backup = path.join(S.root(), 'php_deleted', s + '.' + C.tsName());
  C.mkdirp(path.dirname(backup));
  const moved = await C.sh('mv ' + S.shq(C.siteDir(s)) + ' ' + S.shq(backup) + ' 2>&1', { timeout: 60000 })
    .catch(() => null);

  /* The pool must go, and fpm must reload, before userdel: live workers running
     as php-<slug> hold the uid and userdel silently fails, leaking the user. */
  const vers = await installedVersions();
  const applyRemove = async () => {
    await C.sh('rm -f ' + S.shq(C.nginxLink(s)));
    await C.sh('rm -f ' + S.shq(C.nginxFile(s)));
    for (const v of vers) await C.sh('rm -f ' + S.shq(C.poolFile(v, s)));
  };
  if (moved) {
    await C.withNginxGuard(applyRemove, async () => {
      await C.sh('ln -sf ' + S.shq(C.nginxFile(s)) + ' ' + S.shq(C.nginxLink(s)) + ' 2>/dev/null || true');
    });
  } else {
    await applyRemove();
  }
  for (const v of vers) await C.reloadFpm(v);
  await C.sh('rm -f ' + S.shq(C.sockPath(s)), { timeout: 10000 }).catch(() => {});

  if (rec.db) await require('./db').dropFor(rec).catch(() => {});
  const gone = await C.removeUserChecked(s);
  if (rec.ssl) await require('./ssl').forgetDomain(rec).catch(() => {});
  C.drop(s);
  C.logEvent({ kind: 'site.delete', site: s, backup: backup });
  return ok('站点 ' + s + ' 已删除。' + (moved ? '站点目录已移动到 ' + backup + '（含代码与日志，可手工恢复）。'
    : '站点目录不存在，无需保留。') + (gone ? '' : '\n警告：系统用户 ' + C.siteUser(s) +
    ' 未能自动删除（可能仍有进程占用），请稍后手工执行 userdel ' + C.siteUser(s)));
}

/** Replace the whole raw nginx file for a site, guarded by nginx -t + backup. */
async function writeRawNginx(body) {
  const rec = C.get(body.slug);
  const content = String(body.content || '');
  if (!content.trim()) C.bad('配置内容不能为空');
  if (content.length > 200000) C.bad('配置过大（上限 200KB）');
  if (/default_server/.test(content)) C.bad('面板托管的站点不允许声明 default_server（会与 relay 站点的默认块冲突）');
  if (/^\s*(http|events|upstream)\s*\{/m.test(content)) C.bad('只允许写 server{} 及其内部指令，不能写 http/events/upstream 层');
  if (!/server\s*\{/.test(content)) C.bad('内容里必须包含 server { } 块');

  const f = C.nginxFile(rec.slug);
  const prev = C.readIf(f);
  C.writeVersioned(f, content, true);
  const act = await C.withNginxGuard(
    () => C.sh('ln -sf ' + S.shq(f) + ' ' + S.shq(C.nginxLink(rec.slug))),
    () => C.sh(prev == null ? 'rm -f ' + S.shq(f) : 'printf %s ' + S.shq(b64(prev)) + ' | base64 -d > ' + S.shq(f))
  );
  rec.customConf = true;
  rec.updatedAt = new Date().toISOString();
  C.put(rec);
  return ok('已写入并生效\n' + act.output + (prev == null ? '' : '\n（旧版本已备份为 ' + f + '.bak.*）'));
}
function b64(s) { return Buffer.from(s, 'utf8').toString('base64'); }

async function setRaw(body) {
  const rec = C.get(body.slug);
  return { ok: true, file: C.nginxFile(rec.slug), content: C.readIf(C.nginxFile(rec.slug)) || '',
    pool: rec.php ? { file: C.poolFile(rec.php, rec.slug), content: C.readIf(C.poolFile(rec.php, rec.slug)) || '' } : null };
}

module.exports = {
  poolConf, serverBlock, nginxConf, apply, linkHtdocs,
  installedVersions, defaultVersion, siteIni, INI_SYSTEM_KEYS, statusOf, listSites,
  create, update, toggle, destroy, writeRawNginx, setRaw, defaultIndex,
};
