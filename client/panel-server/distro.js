'use strict';
/*
 * Distribution / package-manager abstraction.
 *
 * The panel must run on any Linux, so nothing may assume Debian's apt.
 * This module detects the environment once (cached, ~2 min TTL) and exposes
 * command builders + capability flags. Read-only features degrade gracefully
 * instead of erroring when a tool is missing.
 */
const { getAgent } = require('./agent');

let cache = { exp: 0, env: null };

/* ---- capability probes ---- */
async function exists(cmd) {
  try {
    const r = await getAgent().run(`command -v ${cmd} 2>/dev/null`, { timeout: 8000 });
    return String(r.stdout || '').trim().length > 0;
  } catch (_) { return false; }
}
async function firstExists(cmds) {
  for (const c of cmds) { if (await exists(c)) return c; }
  return null;
}

async function detect() {
  if (cache.env && cache.exp > Date.now()) return cache.env;
  const agent = getAgent();
  let rel = {}, osr = '';
  try {
    const f = await agent.readFiles(['/etc/os-release', '/etc/redhat-release', '/etc/alpine-release']);
    osr = f['/etc/os-release'] || '';
    rel = {};
    for (const line of String(osr).split('\n')) {
      const m = /^([A-Za-z_]+)=(?:"([^"]*)"|(.*))$/.exec(line.trim());
      if (m) rel[m[1]] = (m[2] !== undefined ? m[2] : m[3] || '').trim();
    }
  } catch (_) {}

  const [pm, svc, webSrv, dbPg, dbMy] = await Promise.all([
    firstExists(['apt-get', 'dnf', 'yum', 'zypper', 'apk', 'pacman']),
    firstExists(['systemctl', 'service']),
    firstExists(['nginx', 'caddy', 'httpd', 'apache2']),
    exists('psql'),
    exists('mysql'),
  ]);
  const docker = await exists('docker');

  /* family determines install flags */
  const id = String(rel.ID || '').toLowerCase();
  const like = String(rel.ID_LIKE || '').toLowerCase();
  let family = 'unknown';
  if (pm === 'apt-get') family = 'debian';
  else if (pm === 'dnf' || pm === 'yum') family = 'redhat';
  else if (pm === 'zypper') family = 'suse';
  else if (pm === 'apk') family = 'alpine';
  else if (pm === 'pacman') family = 'arch';

  /* service manager unit generation */
  const systemd = await exists('systemctl');

  const env = {
    id: id || 'unknown',
    idLike: like,
    name: rel.PRETTY_NAME || rel.NAME || id || 'Linux',
    version: rel.VERSION_ID || '',
    codename: rel.VERSION_CODENAME || '',
    kernel: rel.PRETTY_NAME && /Linux/i.test(rel.PRETTY_NAME) ? '' : '',
    family,
    pm,                    // primary package manager binary
    systemd,               // systemd present?
    serviceMgr: systemd ? 'systemd' : (svc ? 'sysvinit' : 'none'),
    webserver: webSrv,     // nginx | caddy | httpd | apache2 | null
    tools: { psql: dbPg, mysql: dbMy, docker },
  };
  cache = { exp: Date.now() + 120000, env };
  return env;
}

/* ---- package manager command builders ---- */
function shq(s) { return "'" + String(s == null ? '' : s).replace(/'/g, "'\\''") + "'"; }

const PM = {
  /* search: returns "name<TAB>description" per line */
  searchQuery(env, q) {
    const s = shq(q);
    switch (env.pm) {
      case 'apt-get': return `apt-cache search ${s} 2>/dev/null | head -400`;
      case 'dnf': case 'yum': return `dnf -q list --available --showduplicates ${s} 2>/dev/null | head -200; dnf -q search ${s} 2>/dev/null | head -200`;
      case 'zypper': return `zypper --non-interactive search --details ${s} 2>/dev/null | grep -E '^(i|S|\\|)' | head -200`;
      case 'apk': return `apk search -x ${s} 2>/dev/null | head -200; apk search ${s} 2>/dev/null | head -200`;
      case 'pacman': return `pacman -Ss ${s} 2>/dev/null | head -400`;
      default: return '';
    }
  },
  detail(env, name) {
    const n = shq(name);
    switch (env.pm) {
      case 'apt-get': return `apt-cache show ${n} 2>/dev/null`;
      case 'dnf': return `dnf -q info ${n} 2>/dev/null`;
      case 'yum': return `yum -q info ${n} 2>/dev/null`;
      case 'zypper': return `zypper --non-interactive info ${n} 2>/dev/null`;
      case 'apk': return `apk info -a ${n} 2>/dev/null`;
      case 'pacman': return `pacman -Si ${n} 2>/dev/null`;
      default: return '';
    }
  },
  installedList(env, limit) {
    switch (env.pm) {
      case 'apt-get': return `dpkg-query -W -f='${'${Package}'}\\t${'${Version}'}\\t${'${Installed-Size}'}\\t${'${Section}'}\\t${'${db:Status-Abbrev}'}\\n' 2>/dev/null | head -${limit}`;
      case 'dnf': case 'yum': return `rpm -qa --queryformat '%{NAME}\\t%{VERSION}-%{RELEASE}\\t%{SIZE}\\t%{GROUP}\\tii\\n' 2>/dev/null | head -${limit}`;
      case 'zypper': return `rpm -qa --queryformat '%{NAME}\\t%{VERSION}-%{RELEASE}\\t%{SIZE}\\t%{GROUP}\\tii\\n' 2>/dev/null | head -${limit}`;
      case 'apk': return `apk info 2>/dev/null | head -${limit}`;
      case 'pacman': return `pacman -Q 2>/dev/null | head -${limit}`;
      default: return '';
    }
  },
  upgradable(env) {
    switch (env.pm) {
      case 'apt-get': return 'apt list --upgradable 2>/dev/null';
      case 'dnf': return 'dnf -q check-update 2>/dev/null || true';
      case 'yum': return 'yum -q check-update 2>/dev/null || true';
      case 'zypper': return 'zypper --non-interactive list-updates 2>/dev/null';
      case 'apk': return 'apk list --upgradable 2>/dev/null';
      case 'pacman': return 'pacman -Qu 2>/dev/null';
      default: return '';
    }
  },
  action(env, verb, names) {
    const l = names.map(shq).join(' ');
    const envPrefix = 'DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a ';
    switch (env.pm) {
      case 'apt-get':
        return envPrefix + ({
          install: `apt-get install -y ${l}`,
          remove: `apt-get remove -y ${l}`,
          purge: `apt-get purge -y ${l}`,
          update: `${envPrefix}apt-get update`,
          upgrade: `${envPrefix}apt-get upgrade -y`,
          'full-upgrade': `${envPrefix}apt-get full-upgrade -y`,
          autoremove: `${envPrefix}apt-get autoremove -y --purge`,
          clean: `${envPrefix}apt-get clean && apt-get autoclean`,
        }[verb] || '');
      case 'dnf':
        return ({
          install: `dnf install -y ${l}`,
          remove: `dnf remove -y ${l}`,
          purge: `dnf remove -y ${l}`,
          update: 'dnf makecache',
          upgrade: 'dnf upgrade -y',
          'full-upgrade': 'dnf upgrade -y --allowerasing',
          autoremove: 'dnf autoremove -y',
          clean: 'dnf clean all',
        }[verb] || '');
      case 'yum':
        return ({
          install: `yum install -y ${l}`,
          remove: `yum remove -y ${l}`,
          purge: `yum remove -y ${l}`,
          update: 'yum makecache',
          upgrade: 'yum update -y',
          'full-upgrade': 'yum update -y',
          autoremove: 'yum autoremove -y',
          clean: 'yum clean all',
        }[verb] || '');
      case 'zypper':
        return ({
          install: `zypper --non-interactive install -y ${l}`,
          remove: `zypper --non-interactive remove -y ${l}`,
          purge: `zypper --non-interactive remove -y ${l}`,
          update: 'zypper --non-interactive refresh',
          upgrade: 'zypper --non-interactive update -y',
          'full-upgrade': 'zypper --non-interactive dist-upgrade -y',
          autoremove: 'zypper --non-interactive autoremove -y',
          clean: 'zypper --non-interactive clean --all',
        }[verb] || '');
      case 'apk':
        return ({
          install: `apk add ${l}`,
          remove: `apk del ${l}`,
          purge: `apk del --purge ${l}`,
          update: 'apk update',
          upgrade: 'apk upgrade',
          'full-upgrade': 'apk upgrade --available',
          autoremove: 'apk autoremove',
          clean: 'apk cache clean',
        }[verb] || '');
      case 'pacman':
        return ({
          install: `pacman -S --needed --noconfirm ${l}`,
          remove: `pacman -R --noconfirm ${l}`,
          purge: `pacman -R --noconfirm ${l}`,
          update: 'pacman -Sy --noconfirm',
          upgrade: 'pacman -Syu --noconfirm',
          'full-upgrade': 'pacman -Syu --noconfirm',
          autoremove: 'pacman -Rns --noconfirm $(pacman -Qtdq)',
          clean: 'pacman -Sc --noconfirm',
        }[verb] || '');
      default: return '';
    }
  },
  hold(env, verb, names) {
    const l = names.map(shq).join(' ');
    if (env.pm === 'apt-get') return `apt-mark ${verb === 'hold' ? 'hold' : 'unhold'} ${l}`;
    if (env.pm === 'dnf') return verb === 'hold' ? `dnf versionlock add ${l}` : `dnf versionlock delete ${l}`;
    return '';   /* not supported */
  },
  /** distro-specific mirrors for the built-in mirror list */
  supportedSources: {
    debian: true, ubuntu: true, apt: true,
  },
};

module.exports = { detect, PM, shq, exists, cacheReset() { cache.exp = 0; } };