'use strict';
/* Windows distribution / package-manager (winget / choco) abstraction. */
const { getAgent } = require('./agent');

let cache = { exp: 0, env: null };

async function exists(cmd) {
  try {
    const r = await getAgent().run(`where ${cmd} 2>nul`, { timeout: 8000 });
    return String(r.stdout || '').trim().length > 0;
  } catch (_) { return false; }
}

async function detect() {
  if (cache.env && cache.exp > Date.now()) return cache.env;
  const [winget, choco, docker, psql, mysql] = await Promise.all([
    exists('winget'), exists('choco'), exists('docker'), exists('psql'), exists('mysql'),
  ]);
  const pm = winget ? 'winget' : (choco ? 'choco' : null);
  const os = require('os');
  const rel = os.release();
  const env = {
    id: 'windows',
    idLike: 'windows',
    name: `Windows ${rel}`,
    version: rel,
    codename: '',
    kernel: rel,
    family: 'windows',
    pm,
    systemd: false,
    serviceMgr: 'sc',
    webserver: null,
    tools: { psql, mysql, docker },
  };
  try {
    const r = await getAgent().run(
      'Get-Service W3SVC -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Status',
      { powershell: true, timeout: 8000 },
    );
    if (/Running/i.test(r.stdout)) env.webserver = 'iis';
    else {
      const nginx = await exists('nginx');
      if (nginx) env.webserver = 'nginx';
    }
  } catch (_) {}
  cache = { exp: Date.now() + 120000, env };
  return env;
}

function shq(s) { return '"' + String(s == null ? '' : s).replace(/"/g, '\\"') + '"'; }

const PM = {
  searchQuery(env, q) {
    const s = shq(q);
    if (env.pm === 'winget') return `winget search ${s} --accept-source-agreements 2>nul`;
    if (env.pm === 'choco') return `choco search ${s} --limit-output 2>nul`;
    return '';
  },
  detail(env, name) {
    const n = shq(name);
    if (env.pm === 'winget') return `winget show ${n} --accept-source-agreements 2>nul`;
    if (env.pm === 'choco') return `choco info ${n} 2>nul`;
    return '';
  },
  installedList(env, limit) {
    if (env.pm === 'winget') return `winget list --accept-source-agreements 2>nul | more +2 | more +0`;
    if (env.pm === 'choco') return `choco list --limit-output 2>nul`;
    return `wmic product get name,version 2>nul | findstr /v /b "Name" | more`;
  },
  upgradable(env) {
    if (env.pm === 'winget') return `winget upgrade 2>nul`;
    if (env.pm === 'choco') return `choco outdated 2>nul`;
    return '';
  },
  action(env, verb, names) {
    const l = names.map(shq).join(' ');
    if (env.pm === 'winget') {
      return ({
        install: `winget install ${l} --accept-package-agreements --accept-source-agreements`,
        remove: `winget uninstall ${l} --accept-source-agreements`,
        purge: `winget uninstall ${l} --accept-source-agreements`,
        update: 'winget source update',
        upgrade: 'winget upgrade --all --accept-package-agreements --accept-source-agreements',
        'full-upgrade': 'winget upgrade --all --accept-package-agreements --accept-source-agreements',
        autoremove: 'echo autoremove-not-supported-on-windows',
        clean: 'winget source reset --force',
      }[verb] || '');
    }
    if (env.pm === 'choco') {
      return ({
        install: `choco install ${l} -y`,
        remove: `choco uninstall ${l} -y`,
        purge: `choco uninstall ${l} -y --remove-dependencies`,
        update: 'choco outdated',
        upgrade: 'choco upgrade all -y',
        'full-upgrade': 'choco upgrade all -y',
        autoremove: 'choco autoremove -y',
        clean: 'choco cache remove --all',
      }[verb] || '');
    }
    return '';
  },
  hold() { return ''; },
  supportedSources: {},
};

module.exports = { detect, PM, shq, exists, cacheReset() { cache.exp = 0; } };
