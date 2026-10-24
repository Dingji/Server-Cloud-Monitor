'use strict';
/*
 * Host inventory (纳管主机): which machines this deployment is responsible for.
 * Pure operations register — no quotas, no licensing. Reachability is checked by
 * opening the SSH port and reading the banner, which is useful on its own
 * (it tells you the OpenSSH version of every managed box).
 */
const net = require('net');
const S = require('./store');

const FILE = 'hosts.json';

const ROLES = {
  self: '本机面板', web: 'Web 服务', db: '数据库', cache: '缓存/队列', proxy: '网关/反代',
  storage: '存储/备份', worker: '任务/计算', monitor: '监控', other: '其它',
};
const ENVS = ['生产', '预发', '测试', '开发', '其它'];

const list = () => S.readJson(FILE, { items: [] }).items;
const save = (items) => S.writeJson(FILE, { items, updatedAt: S.now() });

function normalize(b, existing) {
  const pick = (k, d) => (b[k] !== undefined && b[k] !== null && b[k] !== '' ? b[k] : (existing && existing[k] !== undefined ? existing[k] : d));
  const label = String(pick('label', '')).trim().slice(0, 60);
  if (!label) { const e = new Error('主机名称不能为空'); e.status = 400; throw e; }
  const addr = String(pick('addr', '')).trim().slice(0, 160);
  const role = ROLES[pick('role', 'other')] ? pick('role', 'other') : 'other';
  const env = ENVS.includes(pick('env', '其它')) ? pick('env', '其它') : '其它';
  return {
    id: (existing && existing.id) || S.genId('h'),
    label, addr,
    sshPort: Math.round(S.clampNum(pick('sshPort', 22), 1, 65535, 22)),
    role, env,
    owner: String(pick('owner', '')).trim().slice(0, 40),
    tags: Array.isArray(pick('tags', [])) ? pick('tags', []).map((t) => String(t).trim().slice(0, 20)).filter(Boolean).slice(0, 12) : [],
    note: String(pick('note', '')).slice(0, 400),
    panelUrl: String(pick('panelUrl', '')).trim().slice(0, 200),
    addedAt: (existing && existing.addedAt) || S.now(),
    updatedAt: S.now(),
    addedBy: (existing && existing.addedBy) || '',
    lastSeenAt: (existing && existing.lastSeenAt) || 0,
    lastCheckOk: existing ? !!existing.lastCheckOk : false,
    lastCheckError: (existing && existing.lastCheckError) || '',
    banner: (existing && existing.banner) || '',
  };
}

/** 只探测端口可达性并读取 SSH banner，不发送任何凭据。 */
function checkReachability(host, timeoutMs) {
  return new Promise((resolve) => {
    if (!host.addr) { resolve({ ok: false, error: '未填写地址' }); return; }
    const started = S.now();
    let banner = '';
    let settled = false;
    let sock;
    const finish = (ok, error) => {
      if (settled) return;
      settled = true;
      try { if (sock) sock.destroy(); } catch (_) {}
      resolve({ ok, error: error || '', banner: banner.trim().slice(0, 120), ms: S.now() - started });
    };
    try {
      sock = net.connect({ host: host.addr, port: host.sshPort || 22 });
    } catch (e) { finish(false, e.message); return; }
    sock.setTimeout(Math.max(1000, timeoutMs || 5000));
    sock.once('connect', () => {});
    sock.on('data', (d) => {
      banner += d.toString('utf8');
      if (banner.includes('\n')) finish(true, '');
    });
    sock.once('timeout', () => finish(banner.length > 0, banner.length ? '' : '连接超时'));
    sock.once('error', (e) => finish(false, e.code || e.message));
  });
}

async function upsert(body, ip) {
  const items = list();
  const existing = body.id ? items.find((h) => h.id === body.id) : null;
  if (body.id && !existing) { const e = new Error('主机不存在：' + body.id); e.status = 404; throw e; }
  const h = normalize(body, existing);
  h.addedBy = (existing && existing.addedBy) || ip || '';
  if (existing) items.splice(items.indexOf(existing), 1, h); else items.push(h);
  save(items);
  return { ok: true, host: h, count: items.length };
}

function remove(body) {
  const items = list();
  const idx = items.findIndex((h) => h.id === (body || {}).id);
  if (idx < 0) { const e = new Error('主机不存在：' + (body || {}).id); e.status = 404; throw e; }
  const [gone] = items.splice(idx, 1);
  save(items);
  return { ok: true, removed: gone.id, count: items.length };
}

async function checkAll() {
  const items = list();
  const results = [];
  for (const h of items) {
    if (!h.addr) { results.push({ id: h.id, label: h.label, skipped: true }); continue; }
    const r = await checkReachability(h);
    h.lastCheckOk = r.ok;
    h.lastCheckError = r.error;
    h.banner = r.banner || h.banner;
    if (r.ok) h.lastSeenAt = S.now();
    results.push({ id: h.id, label: h.label, ok: r.ok, ms: r.ms, banner: r.banner, error: r.error });
  }
  if (results.some((x) => x.ms !== undefined)) save(items);
  return { ok: true, results };
}

function stats() {
  const items = list();
  const byRole = {}, byEnv = {};
  items.forEach((h) => { byRole[ROLES[h.role] || h.role] = (byRole[ROLES[h.role] || h.role] || 0) + 1; byEnv[h.env] = (byEnv[h.env] || 0) + 1; });
  return {
    total: items.length,
    reachable: items.filter((h) => h.lastCheckOk).length,
    unreachable: items.filter((h) => h.addr && !h.lastCheckOk && h.lastCheckError !== '').length,
    unchecked: items.filter((h) => h.addr && !h.lastCheckOk && h.lastCheckError === '').length,
    withoutAddr: items.filter((h) => !h.addr).length,
    byRole, byEnv,
  };
}

function register(api, wrap, audited) {
  api.get('/ops/hosts', wrap(async (req, res) => res.json({
    ok: true,
    items: list(),
    roles: Object.entries(ROLES).map(([k, v]) => ({ key: k, label: v })),
    envs: ENVS,
    stats: stats(),
  })));
  api.post('/ops/host/save', audited('ops.host.save', async (req) => upsert(req.body || {}, req.clientIp)));
  api.post('/ops/host/delete', audited('ops.host.delete', async (req) => remove(req.body || {})));
  api.post('/ops/host/check', audited('ops.host.check', async (req) => {
    const b = req.body || {};
    if (b.id) {
      const items = list();
      const h = items.find((x) => x.id === b.id);
      if (!h) { const e = new Error('主机不存在：' + b.id); e.status = 404; throw e; }
      const r = await checkReachability(h, b.timeoutMs);
      h.lastCheckOk = r.ok; h.lastCheckError = r.error; h.banner = r.banner || h.banner;
      if (r.ok) h.lastSeenAt = S.now();
      h.updatedAt = S.now();
      save(items);
      return { ok: true, host: h, check: r };
    }
    return checkAll();
  }));
}

module.exports = { register, list, save, saveList: save, upsert, remove, checkAll, checkReachability, stats, normalize, ROLES, ENVS, FILE };
