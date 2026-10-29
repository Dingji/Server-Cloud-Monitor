'use strict';
/* winget / choco package manager surface for the panel UI */
const { getAgent } = require('./agent');
const distro = require('./distro');

const PKG_RE = /^[a-zA-Z0-9][a-zA-Z0-9._\-]{0,120}$/;
const CRITICAL_PKGS = new Set(['CloudMonitor']);
const PROTECTED_PKGS = new Set(['nodejs', 'Node.js']);
const MIRRORS = [];
const SOURCE_FILE_RE = /^$/;
const RECOMMENDED = ['Git.Git', 'OpenSSH.OpenSSH', '7zip.7zip'];
const ACTION_PHASES = ['prepare', 'download', 'install', 'configure', 'done'];

let jobState = { id: null, phase: 'idle', log: '', done: true, ok: true };

async function sh(cmd, opts) {
  const agent = getAgent();
  const r = await agent.run(cmd, Object.assign({ timeout: 120000 }, opts || {}));
  return { code: r.code, out: (r.stdout + r.stderr).trim() };
}

async function summary() {
  const env = await distro.detect();
  return {
    installed_total: 0,
    installed_active: 0,
    removed_config: 0,
    upgradable: 0,
    held: 0,
    hold_list: [],
    index_updated: null,
    apt_cache_size: null,
    disk: { line: '', avail: '', total: '', used_pct: '' },
    pm: env.pm || 'none',
    family: env.family,
    name: env.name,
    mirrors: [],
    supported: !!env.pm,
  };
}

async function search(q) {
  const env = await distro.detect();
  if (!env.pm) return { items: [], raw: '未安装 winget 或 choco' };
  const cmd = distro.PM.searchQuery(env, String(q || '').slice(0, 80));
  const r = await sh(cmd);
  const items = r.out.split('\n').filter(Boolean).slice(0, 200).map((line) => {
    const p = line.trim().split(/\s+/);
    return { name: p[0] || line, version: p[1] || '', description: line.trim() };
  });
  return { items, raw: r.out };
}

async function detail(name) {
  const env = await distro.detect();
  const cmd = distro.PM.detail(env, name);
  const r = await sh(cmd);
  return { name, raw: r.out };
}

async function installed() {
  const env = await distro.detect();
  const cmd = distro.PM.installedList(env, 500);
  const r = await sh(cmd);
  const items = r.out.split('\n').filter(Boolean).map((line) => ({ name: line.trim(), version: '', section: '' }));
  return { items, raw: r.out };
}

async function upgradable() {
  const env = await distro.detect();
  const r = await sh(distro.PM.upgradable(env));
  return { raw: r.out, items: [] };
}

async function autoremoveList() { return { items: [] }; }
async function recommended() { return { items: RECOMMENDED.map((n) => ({ name: n })) }; }
async function preview(body) { return act(Object.assign({}, body, { simulate: true })); }

async function act(body) {
  const env = await distro.detect();
  const verb = body.action || body.verb || 'install';
  const names = Array.isArray(body.packages) ? body.packages : [body.package || body.name].filter(Boolean);
  if (!names.length) throw Object.assign(new Error('缺少包名'), { status: 400 });
  const cmd = distro.PM.action(env, verb, names);
  if (!cmd) throw Object.assign(new Error('不支持的操作'), { status: 400 });
  const r = await sh(cmd, { timeout: 600000 });
  return { ok: r.code === 0, output: r.out };
}

async function lockState() { return { held: [] }; }
function humanSize(n) { return String(n); }

async function startJob(body) {
  jobState = { id: String(Date.now()), phase: 'install', log: '', done: false, ok: true };
  try {
    const res = await act(body);
    jobState.log = res.output;
    jobState.ok = res.ok;
  } catch (e) {
    jobState.log = e.message;
    jobState.ok = false;
  }
  jobState.done = true;
  jobState.phase = 'done';
  return { id: jobState.id };
}

function jobStateFn() { return jobState; }

async function listSources() { return { supported: false, message: 'Windows 软件源请在 winget settings 中配置' }; }
async function searchInSource() { return { items: [] }; }
async function testMirror() { return { ok: false, message: 'N/A' }; }
async function switchMirror() { throw Object.assign(new Error('Windows 不支持镜像切换'), { status: 400 }); }
async function sourceBackups() { return { items: [] }; }
async function resolveMirror() { return null; }

module.exports = {
  summary, search, detail, installed, upgradable, autoremoveList, recommended,
  preview, act, lockState, humanSize,
  startJob, jobState: jobStateFn, ACTION_PHASES,
  listSources, searchInSource, testMirror, switchMirror, sourceBackups, resolveMirror,
  CRITICAL_PKGS, PROTECTED_PKGS, PKG_RE, MIRRORS, SOURCE_FILE_RE, RECOMMENDED,
};
