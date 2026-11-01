'use strict';
/*
 * PHP runtime environment: which versions exist, which extensions are loaded,
 * what the ini says, and how to change any of it. Everything mutating runs as
 * a job so a 3-minute apt install never blocks the panel.
 */
const fs = require('fs');
const C = require('./common');
const jobs = require('./jobs');

const S = C.S;
const ok = C.ok;

/* Extensions offered in the UI. `pkg` is the Ubuntu package for the running
 * version, resolved at request time so a PHP version bump does not rot the list. */
const EXT_CATALOG = [
  { ext: 'curl', label: 'cURL — HTTP 请求' },
  { ext: 'mbstring', label: 'Multibyte String — 中文/编码处理' },
  { ext: 'xml', label: 'XML / dom / simplexml' },
  { ext: 'zip', label: 'Zip 压缩' },
  { ext: 'gd', label: 'GD 图像处理' },
  { ext: 'imagick', label: 'ImageMagick（比 GD 强，需 php-imagick）' },
  { ext: 'intl', label: 'ICU 国际化' },
  { ext: 'bcmath', label: 'BCMath 高精度数学' },
  { ext: 'gmp', label: 'GMP 大整数' },
  { ext: 'mysql', label: 'mysqli / pdo_mysql — 连 MySQL/MariaDB' },
  { ext: 'pgsql', label: 'postgresql' },
  { ext: 'sqlite3', label: 'SQLite' },
  { ext: 'redis', label: 'Redis 客户端' },
  { ext: 'apcu', label: 'APCu 用户态缓存' },
  { ext: 'memcached', label: 'Memcached 客户端' },
  { ext: 'sodium', label: 'Sodium 加密' },
  { ext: 'xsl', label: 'XSL 转换' },
  { ext: 'soap', label: 'SOAP' },
  { ext: 'ldap', label: 'LDAP' },
  { ext: 'imap', label: 'IMAP' },
  { ext: 'odbc', label: 'ODBC' },
  { ext: 'opcache', label: 'OPcache（通常内置，无需单独装）' },
  { ext: 'cli', label: 'CLI（php-fpm 依赖，通常已装）' },
  { ext: 'common', label: 'common（基础）' },
];

const INI_KEYS = [
  { key: 'memory_limit', label: '单进程内存上限', unit: '如 256M' },
  { key: 'max_execution_time', label: '最长执行时间（秒）' },
  { key: 'max_input_time', label: '最长输入解析（秒）' },
  { key: 'upload_max_filesize', label: '单文件上传上限', unit: '如 64M' },
  { key: 'post_max_size', label: 'POST 整体上限', unit: '如 64M' },
  { key: 'max_file_uploads', label: '单次最多文件数' },
  { key: 'default_socket_timeout', label: 'socket 超时（秒）' },
  { key: 'date.timezone', label: '时区', unit: 'Asia/Shanghai' },
  { key: 'display_errors', label: '是否在页面显示错误', warn: '生产环境应为 Off' },
  { key: 'log_errors', label: '记录错误日志' },
  { key: 'expose_php', label: '暴露 PHP 版本头', warn: '生产环境应为 Off' },
  { key: 'cgi.fix_pathinfo', label: 'PATH_INFO 修正', warn: '为 1 时有解析越权风险，nginx 侧已用 try_files 兜底' },
  { key: 'opcache.enable', label: 'OPcache 开关' },
  { key: 'opcache.memory_consumption', label: 'OPcache 内存（MB）' },
  { key: 'opcache.max_accelerated_files', label: 'OPcache 最大缓存文件数' },
  { key: 'opcache.validate_timestamps', label: '校验文件时间戳', warn: '部署后不生效可临时置 0，需手动清缓存' },
];

/* ---------------------------------------------------------------- probe */
async function probeVersions() {
  const vs = await C.installedVersions(true);
  const out = [];
  for (const v of vs) {
    const fpmDir = C.PHP_ROOT + '/' + v + '/fpm';
    const [exts, service, pools] = await Promise.all([
      loadedExtensions(v),
      C.sh('systemctl is-active php' + v + '-fpm 2>/dev/null || echo unknown', { timeout: 8000 })
        .then((r) => String(r.stdout).trim()).catch(() => 'unknown'),
      C.sh('ls -1 ' + S.shq(fpmDir + '/pool.d') + ' 2>/dev/null || true', { timeout: 8000 })
        .then((r) => String(r.stdout).split('\n').filter(Boolean)).catch(() => []),
    ]);
    const verStr = await C.sh('php' + v + ' -r "echo PHP_VERSION;" 2>/dev/null', { timeout: 8000 })
      .then((r) => r.stdout.trim()).catch(() => '');
    out.push({
      version: v,
      full: verStr,
      cli: C.exists('/usr/bin/php' + v),
      fpm: C.exists(fpmDir),
      fpmService: 'php' + v + '-fpm',
      serviceState: service,
      pools: pools,
      panelPools: pools.filter((p) => p.startsWith('panel-')),
      extensions: exts,
      iniFile: fpmDir + '/php.ini',
      scanFile: fpmDir + '/conf.d/99-panel.ini',
    });
  }
  return out;
}

async function loadedExtensions(v) {
  const bin = v ? 'php' + v : 'php';
  const r = await C.sh(bin + ' -m 2>/dev/null | tr "\\n" ","', { timeout: 10000 }).catch(() => ({ stdout: '' }));
  return String(r.stdout).split(',').map((x) => x.trim().toLowerCase()).filter(Boolean);
}

async function tools() {
  const one = async (label, cmd) => C.sh(cmd, { timeout: 10000 })
    .then((r) => String(r.stdout || '').trim().split('\n')[0])
    .catch(() => null);
  const [composer, git, certbot, mariadb, redis, nginx] = await Promise.all([
    one('composer', 'COMPOSER_ALLOW_SUPERUSER=1 composer --version 2>/dev/null | head -1'),
    one('git', 'git --version 2>/dev/null | head -1'),
    one('certbot', 'certbot --version 2>/dev/null | head -1'),
    one('mariadb', 'mariadb -e "SELECT VERSION();" 2>/dev/null | tail -1'),
    one('redis', 'redis-server --version 2>/dev/null | head -1'),
    one('nginx', 'nginx -v 2>&1 | head -1'),
  ]);
  const active = async (svc) => C.sh('systemctl is-active ' + S.shq(svc) + ' 2>/dev/null || echo inactive', { timeout: 8000 })
    .then((r) => String(r.stdout).trim()).catch(() => 'inactive');
  const [mariadbState, redisState] = await Promise.all([active('mariadb'), active('redis-server')]);
  return { composer, git, certbot, mariadb, mariadbState, redis, redisState, nginx };
}

async function resources() {
  const r = await C.sh(
    'df -kP / | tail -1 | awk \'{print $2","$3","$4}\'; echo ---; free -m | awk \'/^Mem/{print $2","$3","$7}\'; echo ---; nproc',
    { timeout: 10000 });
  const parts = String(r.stdout).split('---').map((x) => x.trim());
  const [dTotal, dUsed, dFree] = (parts[0] || '').split(',').map(Number);
  const [mTotal, mUsed, mAvail] = (parts[1] || '').split(',').map(Number);
  const cpu = Number((parts[2] || '').trim()) || 1;
  const load = await C.sh('cat /proc/loadavg 2>/dev/null | cut -d" " -f1-3', { timeout: 8000 })
    .then((x) => String(x.stdout).trim()).catch(() => '');
  return {
    disk: { total: (dTotal || 0) * 1024, used: (dUsed || 0) * 1024, free: (dFree || 0) * 1024 },
    mem: { totalMb: mTotal || 0, usedMb: mUsed || 0, availMb: mAvail || 0 },
    cpu: cpu, load: load,
    /* sizing hint that the UI repeats verbatim: ondry workers are ~40-70MB each */
    poolCeiling: Math.max(2, Math.floor(((mAvail || 512) * 0.5) / 60)),
  };
}

async function overview() {
  const [versions, toolset, res, sites] = await Promise.all([
    probeVersions(), tools(), resources(), require('./site').listSites(),
  ]);
  const missing = [];
  if (!versions.length) missing.push('php-fpm');
  if (!toolset.composer) missing.push('composer');
  if (!toolset.certbot) missing.push('certbot');
  if (!toolset.mariadb) missing.push('mariadb-server');
  return {
    ok: true,
    base: C.BASE(),
    staging: C.STAGE(),
    ready: versions.length > 0,
    versions: versions,
    defaultVersion: versions.length ? versions[versions.length - 1].version : null,
    tools: toolset,
    resources: res,
    sites: sites,
    counts: {
      sites: sites.length,
      enabled: sites.filter((s) => s.enabled && s.linked).length,
      ssl: sites.filter((s) => s.ssl).length,
      withDb: sites.filter((s) => s.db).length,
      withGit: sites.filter((s) => s.git && s.git.url).length,
    },
    missing: missing,
    iniKeys: INI_KEYS,
    extCatalog: EXT_CATALOG,
    presets: require('./presets').list(),
  };
}

/* ---------------------------------------------------------------- mutate */
function installJob(body) {
  const kind = String(body.action || '');
  const ver = body.version ? String(body.version) : null;
  const ext = body.extension ? String(body.extension) : null;
  if (ext && !/^[a-z0-9_-]{1,30}$/i.test(ext)) C.bad('非法扩展名：' + ext);
  if (ver && !/^\d\.\d$/.test(ver)) C.bad('非法 PHP 版本：' + ver);

  let pkgs, title;
  if (kind === 'stack') { pkgs = ['php' + (ver || '8.5') + '-fpm', 'php' + (ver || '8.5') + '-cli',
    'php' + (ver || '8.5') + '-common', 'php' + (ver || '8.5') + '-curl', 'php' + (ver || '8.5') + '-mbstring',
    'php' + (ver || '8.5') + '-xml', 'php' + (ver || '8.5') + '-zip', 'php' + (ver || '8.5') + '-mysql']; title = '安装 PHP ' + (ver || '8.5') + ' 运行栈'; }
  else if (kind === 'ext') { pkgs = ['php' + (ver || '') + '-' + ext]; title = '安装扩展 ' + ext; }
  else if (kind === 'exts') { pkgs = (body.extensions || []).map((e) => 'php' + (ver || '') + '-' + e); title = '批量安装扩展'; }
  else if (kind === 'composer') { pkgs = ['composer']; title = '安装 Composer'; }
  else if (kind === 'certbot') { pkgs = ['certbot', 'python3-certbot-nginx']; title = '安装 certbot'; }
  else if (kind === 'mariadb') { pkgs = ['mariadb-server']; title = '安装 MariaDB'; }
  else if (kind === 'extras') { pkgs = ['unzip', 'rsync', 'git']; title = '安装部署工具链'; }
  else C.bad('未知的安装动作：' + kind);
  for (const p of pkgs) if (!/^[a-z0-9.+-]{2,60}$/i.test(p)) C.bad('非法包名：' + p);
  if (!pkgs.length) C.bad('没有要安装的包');

  const job = jobs.start({
    kind: 'install', site: null, title: title,
    run: async (j) => {
      j.set(5, 'plan', '解析依赖');
      const sim = await C.sh('DEBIAN_FRONTEND=noninteractive apt-get -s install -y ' +
        pkgs.map(S.shq).join(' ') + ' 2>&1 | grep -E "newly installed|not upgraded|^E:" | head -5',
        { timeout: 90000 });
      j.log('模拟安装：' + (sim.stdout || '无新增'));
      j.set(15, 'download', '开始下载并安装');
      await j.drain('DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y ' +
        pkgs.map(S.shq).join(' '), { timeout: 15 * 60000 });
      j.set(90, 'configure', '校验运行环境');
      const v = await probeVersions();
      j.log('当前 PHP 版本：' + (v.map((x) => x.version + (x.full ? ' (' + x.full + ')' : '')).join(', ') || '无'));
      const t = await tools();
      j.log('composer=' + (t.composer || '无') + ' certbot=' + (t.certbot || '无') + ' mariadb=' + (t.mariadbState || '无'));
      j.set(100, 'done', '完成');
      return title + ' 完成';
    },
  });
  return ok('任务已启动：' + title, { job: jobs.view(job) });
}

/**
 * Remove an extension. `apt-get remove` on a php ext never restarts the panel,
 * but it does need an fpm reload to take effect.
 */
async function removeExt(body) {
  const ver = String(body.version || await require('./site').defaultVersion());
  const ext = String(body.extension || '');
  if (!/^[a-z0-9_-]{1,30}$/.test(ext)) C.bad('非法扩展名：' + ext);
  const pkg = 'php' + ver + '-' + ext;
  const r = await C.sh('DEBIAN_FRONTEND=noninteractive apt-get remove -y ' + S.shq(pkg) + ' 2>&1 | tail -8',
    { timeout: 300000 });
  await C.reloadFpm(ver);
  return ok('已移除 ' + pkg + '\n' + r.stdout);
}

/* ----------------------------------------------------------------- ini */
const INI_SAFE = /^[A-Za-z0-9_.]{1,60}$/;
const INI_VALUE_SAFE = /^[^\n\r]{0,200}$/;

async function readIni(ver) {
  const v = String(ver || await require('./site').defaultVersion());
  if (!/^\d\.\d$/.test(v)) C.bad('非法版本');
  const f = C.PHP_ROOT + '/' + v + '/fpm/php.ini';
  const scan = C.PHP_ROOT + '/' + v + '/fpm/conf.d/99-panel.ini';
  const text = C.readIf(f) || '';
  const eff = {};
  const r = await C.sh('php' + v + ' -i 2>/dev/null | grep -E "^(memory_limit|upload_max_filesize|post_max_size|' +
    'max_execution_time|max_input_time|display_errors|log_errors|date.timezone|expose_php|cgi.fix_pathinfo|' +
    'opcache.enable|opcache.memory_consumption|opcache.max_accelerated_files|opcache.validate_timestamps|' +
    'max_file_uploads|default_socket_timeout|disable_functions) " | head -40', { timeout: 15000 })
    .catch(() => ({ stdout: '' }));
  for (const line of String(r.stdout).split('\n')) {
    const m = /^(\S+)\s+=>\s+(.*)$/.exec(line.trim());
    if (m) eff[m[1]] = m[2];
  }
  return {
    version: v, iniFile: f, scanFile: scan,
    exists: C.exists(f),
    /* `php -i` reports the CLI SAPI; a site's real limits come from its own pool,
       so the UI must never present these as the per-site values. */
    effectiveCli: eff,
    siteDefaults: require('./site').siteIni({ phpOverrides: {} }),
    systemOnlyKeys: require('./site').INI_SYSTEM_KEYS,
    overrides: parsePairs(C.readIf(scan) || ''),
    sizeBytes: text.length,
    hint: '逐项覆盖写入 conf.d/99-panel.ini（不改动发行版原始的 php.ini），升级 PHP 后该文件由面板继续维护。',
  };
}

function parsePairs(text) {
  const out = {};
  for (const line of text.split('\n')) {
    const m = /^\s*(?:php_value\[)?([A-Za-z0-9_.]+)(?:\])?\s*=\s*(.*)$/.exec(line);
    if (!m || line.trim().startsWith(';') || line.trim().startsWith('[')) continue;
    out[m[1]] = m[2].trim().replace(/^"|"$/g, '');
  }
  return out;
}

async function saveIni(body) {
  const v = String(body.version || await require('./site').defaultVersion());
  if (!/^\d\.\d$/.test(v)) C.bad('非法版本');
  const values = body.values || {};
  const scan = C.PHP_ROOT + '/' + v + '/fpm/conf.d/99-panel.ini';
  const lines = ['; Managed by Cloud Monitor panel — per-value overrides for PHP ' + v,
    '; Do not edit the distro php.ini for these; they are re-applied here.', '[PHP]'];
  for (const k of Object.keys(values)) {
    const val = String(values[k] == null ? '' : values[k]);
    if (!INI_SAFE.test(k)) C.bad('非法指令名：' + k);
    if (!INI_VALUE_SAFE.test(val)) C.bad('指令 ' + k + ' 的值不合法');
    if (val === '') continue;
    lines.push(k + ' = ' + val);
  }
  const w = C.writeVersioned(scan, lines.join('\n') + '\n');
  const reload = await C.reloadFpm(v);
  return ok('PHP ' + v + ' 全局覆盖已保存（' + (w.changed ? '已更新' : '无变化') + '）\n' +
    'fpm: ' + reload.service + ' = ' + reload.active);
}

/* ------------------------------------------------------------------ fpm */
async function fpmAction(body) {
  const v = String(body.version || await require('./site').defaultVersion());
  if (!/^\d\.\d$/.test(v)) C.bad('非法版本');
  const svc = 'php' + v + '-fpm';
  const action = String(body.action || '');
  if (!['start', 'stop', 'restart', 'reload', 'enable', 'disable', 'status'].includes(action)) {
    C.bad('未知服务动作：' + action);
  }
  if (action === 'stop') {
    const linked = await C.sh('ls -1 ' + S.shq(C.NGINX_ENABL) + ' 2>/dev/null | grep -c "^panel-php-" || echo 0', { timeout: 8000 })
      .then((r) => Number(String(r.stdout).trim()) || 0).catch(() => 0);
    if (linked > 0) C.bad('仍有 ' + linked + ' 个面板托管站点在启用中，停用 ' + svc + ' 会让它们全部 502。请先停用这些站点。');
  }
  const out = await C.sh('systemctl ' + S.shq(action) + ' ' + S.shq(svc) + ' 2>&1; systemctl is-active ' + S.shq(svc),
    { timeout: 60000 });
  return ok(action + ' ' + svc + ': ' + out.text.trim());
}

/** Reload a site's pool (this is also how OPcache is cleared for that site). */
async function resetOpcache(body) {
  const rec = C.get(body.slug);
  const reload = await C.reloadFpm(rec.php || await require('./site').defaultVersion());
  return ok('站点 ' + rec.slug + ' 的 PHP 进程池已平滑重载（OPcache 随之清空）\n' +
    reload.service + ' = ' + reload.active);
}

/** `php -i` style details, without exposing the whole page. */
async function phpinfoSlim(ver) {
  const v = String(ver || await require('./site').defaultVersion());
  if (!/^\d\.\d$/.test(v)) C.bad('非法版本');
  const r = await C.sh('php' + v + ' -r ' + S.shq(
    'echo json_encode(["version"=>PHP_VERSION,"sapi"=>php_sapi_name(),"oz"=>ini_get("memory_limit"),' +
    '"upload"=>ini_get("upload_max_filesize"),"post"=>ini_get("post_max_size"),' +
    '"tz"=>date_default_timezone_get(),"opcode"=>function_exists("opcache_get_status")?' +
    'json_encode(@opcache_get_status(false)):"none","exts"=>count(get_loaded_extensions()),' +
    '"zend"=>zend_version()]);') + ' 2>&1', { timeout: 15000 });
  let data = null;
  try { data = JSON.parse(String(r.stdout).trim()); } catch (_) {}
  const fpm = await C.sh('php' + v + ' -r ' + S.shq('echo phpversion();') + ' 2>&1', { timeout: 10000 });
  const status = await C.sh('ps -eo comm=,args= 2>/dev/null | grep "php' + v.replace(/\./g, '\\.') +
    '-fpm" | grep -v grep | head -30', { timeout: 10000 }).catch(() => ({ stdout: '' }));
  return {
    version: v, info: data,
    fpmWorkers: String(status.stdout).split('\n').filter(Boolean).length,
    workerSample: String(status.stdout).split('\n').filter(Boolean).slice(0, 8),
    cli: fpm.stdout.trim(),
    loadedExtensions: await loadedExtensions(v),
  };
}

module.exports = {
  EXT_CATALOG, INI_KEYS, probeVersions, loadedExtensions, tools, resources, overview,
  installJob, removeExt, readIni, saveIni, fpmAction, resetOpcache, phpinfoSlim, parsePairs,
};
