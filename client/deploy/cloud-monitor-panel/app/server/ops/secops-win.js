'use strict';
/* Windows security baseline (subset of secops.js API). */
const S = require('./store');
const ex = require('./exec');

const SCAN = 'secops_scan.json';
const HISTORY = 'secops_history.jsonl';
const FIXLOG = 'secops_fixes.jsonl';
const LEVELS = { ok: '合规', warn: '建议处理', bad: '存在风险', info: '未覆盖' };
const CATS = ['访问控制', '网络暴露', '系统更新', '账号与权限', '日志与时间', '运行健康'];

const CHECKS = [
  {
    id: 'fw.state', label: 'Windows 防火墙', cat: '网络暴露', weight: 8,
    async run() {
      const r = await ex.run('netsh advfirewall show allprofiles state', { timeout: 12000 });
      const on = /STATE\s+ON/i.test(r.stdout);
      return { level: on ? 'ok' : 'bad', detail: on ? '各配置文件已启用' : '防火墙未全开', fix: on ? null : 'fw.enable' };
    },
  },
  {
    id: 'defender.realtime', label: 'Microsoft Defender 实时保护', cat: '运行健康', weight: 8,
    async run() {
      const r = await ex.run('powershell -NoProfile -Command "(Get-MpComputerStatus).RealTimeProtectionEnabled"', { timeout: 15000, powershell: true });
      const on = /True/i.test(r.stdout);
      return { level: on ? 'ok' : 'warn', detail: on ? '实时保护已开启' : '实时保护关闭或未安装 Defender' };
    },
  },
  {
    id: 'updates.service', label: 'Windows Update 服务', cat: '系统更新', weight: 6,
    async run() {
      const r = await ex.run('sc query wuauserv', { timeout: 10000 });
      const run = /RUNNING/i.test(r.stdout);
      return { level: run ? 'ok' : 'warn', detail: run ? 'wuauserv 运行中' : 'Windows Update 服务未运行' };
    },
  },
  {
    id: 'rdp.exposed', label: '远程桌面 (RDP)', cat: '访问控制', weight: 6,
    async run() {
      const r = await ex.run('reg query "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Terminal Server" /v fDenyTSConnections', { timeout: 10000 });
      const enabled = /fDenyTSConnections\s+REG_DWORD\s+0x0/.test(r.stdout);
      if (enabled) return { level: 'warn', detail: 'RDP 已启用，请限制来源 IP 并使用强口令/NLA' };
      return { level: 'ok', detail: 'RDP 未启用或已拒绝连接' };
    },
  },
  {
    id: 'guest.disabled', label: 'Guest 账户', cat: '账号与权限', weight: 5,
    async run() {
      const r = await ex.run('net user guest', { timeout: 8000 });
      const off = /account active\s+no/i.test(r.stdout) || /帐户启用\s+No/i.test(r.stdout);
      return { level: off ? 'ok' : 'bad', detail: off ? 'Guest 已禁用' : 'Guest 账户仍可能启用' };
    },
  },
  {
    id: 'uac.level', label: 'UAC', cat: '账号与权限', weight: 4,
    async run() {
      const r = await ex.run('reg query HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Policies\\System /v EnableLUA', { timeout: 8000 });
      const on = /0x1/.test(r.stdout);
      return { level: on ? 'ok' : 'bad', detail: on ? 'UAC 已启用' : 'UAC 被关闭' };
    },
  },
  {
    id: 'auto.login', label: '自动登录', cat: '访问控制', weight: 6,
    async run() {
      const r = await ex.run('reg query "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon" /v AutoAdminLogon', { timeout: 8000 });
      const on = /0x1/.test(r.stdout);
      return { level: on ? 'bad' : 'ok', detail: on ? '检测到自动登录' : '未启用自动登录' };
    },
  },
  {
    id: 'pwd.policy', label: '密码最短长度策略', cat: '账号与权限', weight: 4,
    async run() {
      const r = await ex.run('net accounts', { timeout: 8000 });
      const m = /Minimum password length:\s*(\d+)/i.exec(r.stdout) || /最短密码长度:\s*(\d+)/.exec(r.stdout);
      const n = m ? Number(m[1]) : null;
      if (n == null) return { level: 'info', detail: '无法读取密码策略' };
      if (n >= 8) return { level: 'ok', detail: `最短长度 ${n}` };
      return { level: 'warn', detail: `最短长度仅 ${n}，建议 ≥8` };
    },
  },
];

const FIXES = {
  'fw.enable': {
    label: '启用 Windows 防火墙（所有配置文件）',
    risk: '将拒绝未允许的入站连接。',
    async run() {
      const r = await ex.run('netsh advfirewall set allprofiles state on', { timeout: 20000 });
      return { ok: r.code === 0, output: (r.stdout + r.stderr).trim() };
    },
  },
};

async function scanAll(ids) {
  const want = ids && ids.length ? new Set(ids) : null;
  const out = [];
  for (const c of CHECKS) {
    if (want && !want.has(c.id)) continue;
    const t0 = S.now();
    let level = 'info'; let detail = '';
    try {
      const p = await c.run();
      level = p.level || 'info';
      detail = p.detail || '';
    } catch (e) {
      level = 'info';
      detail = e.message || String(e);
    }
    out.push({ id: c.id, label: c.label, cat: c.cat, weight: c.weight, level, detail, ms: S.now() - t0, fix: null });
  }
  return out;
}

function score(checks) {
  const counts = { ok: 0, warn: 0, bad: 0, info: 0 };
  let penalty = 0;
  for (const c of checks) {
    counts[c.level] = (counts[c.level] || 0) + 1;
    if (c.level === 'bad') penalty += c.weight;
    else if (c.level === 'warn') penalty += c.weight / 2;
  }
  const s = Math.max(0, Math.min(100, Math.round(100 - penalty)));
  return {
    score: s,
    grade: s >= 90 ? 'A' : s >= 75 ? 'B' : s >= 60 ? 'C' : s >= 40 ? 'D' : 'E',
    penalty, counts,
    byCat: CATS.map((cat) => {
      const items = checks.filter((c) => c.cat === cat);
      return { cat, total: items.length, bad: items.filter((c) => c.level === 'bad').length, warn: items.filter((c) => c.level === 'warn').length, score: 100 };
    }),
  };
}

async function runScan() {
  const checks = await scanAll();
  const at = S.now();
  const sum = score(checks);
  const record = { at, durationMs: S.now() - at, checks, ...sum };
  S.writeJson(SCAN, record);
  S.appendLine(HISTORY, { at, score: sum.score, grade: sum.grade, bad: sum.counts.bad, warn: sum.counts.warn });
  return record;
}

function latest() { return S.readJson(SCAN, null); }
function trend(days) { return S.readLines(HISTORY, Math.max(1, Math.min(90, Number(days) || 30)) * 4); }

async function applyFix(id, ip) {
  const f = FIXES[id];
  if (!f) { const e = new Error('没有这个修复项：' + id); e.status = 404; throw e; }
  const res = await f.run();
  S.appendLine(FIXLOG, { at: S.now(), fix: id, label: f.label, ok: !!res.ok, ip: ip || '', output: (res.output || '').slice(0, 1200) });
  return { ok: !!res.ok, fix: id, output: res.output, recheck: null };
}

function register(api, wrap, audited) {
  api.get('/ops/sec/report', wrap(async (req, res) => {
    const l = latest();
    if (!l || req.query.fresh === '1') return res.json(await runScan());
    res.json(Object.assign({ ok: true }, l));
  }));
  api.get('/ops/sec/checks', wrap(async (req, res) => res.json({
    ok: true,
    levels: Object.entries(LEVELS).map(([k, v]) => ({ key: k, label: v })),
    categories: CATS,
    items: CHECKS.map((c) => ({ id: c.id, label: c.label, cat: c.cat, weight: c.weight, bin: '', fixable: !!FIXES[c.id] })),
    fixes: Object.entries(FIXES).map(([k, v]) => ({ id: k, label: v.label, risk: v.risk || '', requires: '' })),
    runner: ex.mode(),
  })));
  api.get('/ops/sec/trend', wrap(async (req, res) => res.json({ ok: true, days: Number(req.query.days) || 30, items: trend(req.query.days), fixLog: S.readLines(FIXLOG, 100) })));
  api.post('/ops/sec/run', audited('ops.sec.run', async () => runScan()));
  api.post('/ops/sec/fix', audited('ops.sec.fix', async (req) => applyFix(String((req.body || {}).fix || ''), req.clientIp)));
}

module.exports = { register, runScan, scanAll, score, latest, trend, FIXES, applyFix, CHECKS, CATS, LEVELS, SCAN, HISTORY, FIXLOG };
