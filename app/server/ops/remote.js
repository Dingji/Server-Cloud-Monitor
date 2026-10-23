'use strict';
/*
 * Remote host metrics (多主机远程采集). The panel only ever watched its own box;
 * this collects a fixed read-only command bundle from the machines in the host
 * ledger over SSH (ssh2 is already a panel dependency) and stores per-host
 * history so trends survive restarts.
 *
 * Credentials live in the ops state dir (0600). Only the fixed command list is
 * ever executed — no operator-supplied shell string reaches the remote host here.
 */
const fs = require('fs');
const path = require('path');
const S = require('./store');
const hosts = require('./hosts');

const ssh2 = S.soft('ssh2');
const CRED = 'remote_creds.json';
const STATE = 'remote_state.json';
const HISTORY = 'remote_history.jsonl';

const BUNDLE = [
  'cat /proc/loadavg',
  'free -b | head -3',
  'df -Pk | tail -n +2',
  "ps -eo pid,pcpu,pmem,rss,comm --sort=-pcpu | head -11",
  "ss -ltnH 2>/dev/null | awk '{print $4}' | grep -vE '^(127\\.|\\[?::1)' | sort -u | head -30",
  'uptime',
  'head -2 /etc/os-release',
  'nproc',
  "systemctl is-active ssh sshd nginx docker fail2ban 2>/dev/null | paste -sd' ' -",
].join(' ; echo "----8<----"; ');

function credList() { return S.readJson(CRED, { items: [] }).items; }
function credOf(id) { return credList().find((c) => c.id === id) || null; }

function maskCred(c) {
  if (!c) return null;
  return {
    id: c.id, user: c.user, port: c.port, authMode: c.authMode, keyPath: c.keyPath,
    hasPassword: !!c.password, updatedAt: c.updatedAt,
  };
}

function saveCred(body) {
  if (!body.id) { const e = new Error('缺少主机 id'); e.status = 400; throw e; }
  const host = hosts.list().find((h) => h.id === body.id);
  if (!host) { const e = new Error('主机不存在：' + body.id); e.status = 404; throw e; }
  const items = credList();
  const existing = items.find((c) => c.id === body.id);
  const merged = {
    id: body.id,
    user: String(body.user || (existing && existing.user) || 'root').trim().slice(0, 40),
    port: Math.round(S.clampNum(body.port || (existing && existing.port) || host.sshPort || 22, 1, 65535, 22)),
    authMode: ['key', 'password', 'both'].includes(body.authMode) ? body.authMode : ((existing && existing.authMode) || 'key'),
    keyPath: String(body.keyPath != null ? body.keyPath : ((existing && existing.keyPath) || '')).trim().slice(0, 200),
    /* empty string or the mask placeholder means "leave the stored secret alone" */
    password: body.password === undefined || body.password === '' || body.password === '••••••' ? ((existing && existing.password) || '') : String(body.password).slice(0, 200),
    updatedAt: S.now(),
  };
  if (existing) items.splice(items.indexOf(existing), 1, merged); else items.push(merged);
  S.writeJson(CRED, { items });
  return { ok: true, cred: maskCred(merged) };
}

function dropCred(id) {
  const items = credList();
  const idx = items.findIndex((c) => c.id === id);
  if (idx < 0) return { ok: true, removed: null };
  items.splice(idx, 1);
  S.writeJson(CRED, { items });
  return { ok: true, removed: id };
}

function sshExec(cred, cmd, timeoutMs) {
  return new Promise((resolve) => {
    if (!ssh2 || !ssh2.Client) { resolve({ ok: false, error: '服务器缺少 ssh2 依赖，无法远程采集' }); return; }
    const conn = new ssh2.Client();
    let out = '';
    let err = '';
    let done = false;
    const finish = (r) => { if (!done) { done = true; try { conn.end(); } catch (_) {} resolve(r); } };
    const opts = {
      host: cred.host, port: cred.port || 22, username: cred.user || 'root',
      readyTimeout: Math.min(15000, timeoutMs), keepaliveInterval: 5000,
    };
    if (cred.keyPath && fs.existsSync(cred.keyPath)) {
      try { opts.privateKey = fs.readFileSync(cred.keyPath); } catch (e) { return finish({ ok: false, error: '私钥读取失败：' + e.message }); }
    }
    if (cred.password) opts.password = cred.password;
    if (!opts.privateKey && !opts.password) return finish({ ok: false, error: '未配置 SSH 凭据（私钥路径或密码）' });
    const kill = setTimeout(() => finish({ ok: false, error: 'SSH 连接或执行超时' }), timeoutMs + 2000);
    conn.on('error', (e) => { clearTimeout(kill); finish({ ok: false, error: (e.level === 'client-authentication' ? '认证失败（凭据不对）' : e.message) }); });
    conn.on('ready', () => {
      conn.exec(`bash -lc ${JSON.stringify(cmd)}`, (e2, stream) => {
        if (e2) { clearTimeout(kill); return finish({ ok: false, error: e2.message }); }
        stream.on('data', (d) => { out += d.toString('utf8'); });
        stream.stderr.on('data', (d) => { err += d.toString('utf8'); });
        stream.on('close', (code) => {
          clearTimeout(kill);
          finish({ ok: code === 0 || !!out, code, stdout: out, stderr: err.slice(0, 400) });
        });
      });
    });
    conn.connect(opts);
  });
}

const splitSections = (out) => String(out || '').split('----8<----').map((s) => s.trim());

function parseBundle(sections) {
  const [load, mem, df, ps, ports, up, osr, nprocV, units] = sections;
  const loadAvg = (load || '').split(/\s+/).slice(0, 3).map(Number);
  const memLines = (mem || '').split('\n');
  const memRow = (memLines[1] || '').split(/\s+/).map(Number);
  const total = memRow[1] || 0;
  const avail = memRow[6] || 0;
  const disks = [];
  for (const line of (df || '').split('\n')) {
    const c = line.split(/\s+/);
    if (c.length < 6) continue;
    const totalKb = Number(c[1]);
    if (!Number.isFinite(totalKb) || totalKb < 1024 * 1024) continue; /* skips header + tmpfs/efi */
    disks.push({ filesystem: c[0], totalKb, usedKb: Number(c[2]), availKb: Number(c[3]), usedPct: Number(String(c[4]).replace('%', '')), mount: c.slice(5).join(' ') });
  }
  const top = (ps || '').split('\n').slice(1).map((l) => {
    const c = l.trim().split(/\s+/);
    return { pid: Number(c[0]) || 0, cpu: Number(c[1]) || 0, mem: Number(c[2]) || 0, rssKb: Number(c[3]) || 0, cmd: (c[4] || '').slice(0, 40) };
  }).filter((x) => x.pid);
  return {
    loadAvg, memTotal: total, memAvail: avail,
    memPct: total ? Math.round((1 - avail / total) * 1000) / 10 : null,
    disks, top,
    ports: (ports || '').split('\n').map((s) => s.trim()).filter(Boolean).slice(0, 30),
    uptimeLine: (up || '').trim().slice(0, 200),
    prettyName: ((osr || '').match(/PRETTY_NAME="?([^"\n]+)/) || [])[1] || '',
    cores: Number(nprocV) || 0,
    units: (units || '').split(' ').filter(Boolean),
  };
}

function state() { return S.readJson(STATE, { hosts: {} }); }

async function collect(id, opts) {
  const h = hosts.list().find((x) => x.id === id);
  if (!h) { const e = new Error('主机不存在：' + id); e.status = 404; throw e; }
  if (!h.addr) { const e = new Error('该主机没有填写地址'); e.status = 400; throw e; }
  const cred = credOf(id);
  const started = S.now();
  const r = await sshExec({
    host: h.addr, port: (cred && cred.port) || h.sshPort || 22,
    user: cred && cred.user, keyPath: cred && cred.keyPath, password: cred && cred.password,
  }, BUNDLE, (opts && opts.timeoutMs) || 20000);
  const st = state();
  const rec = {
    id, at: S.now(), ok: !!r.ok, ms: S.now() - started, error: r.error || (r.code ? (r.stderr || '').slice(0, 200) : ''),
  };
  if (r.ok) Object.assign(rec, parseBundle(splitSections(r.stdout)), { label: h.label, addr: h.addr });
  st.hosts[id] = rec;
  S.writeJson(STATE, st);
  if (r.ok) {
    S.appendLine(HISTORY, { ts: rec.at, id, load1: rec.loadAvg && rec.loadAvg[0], memPct: rec.memPct, diskMaxPct: rec.disks && rec.disks.length ? Math.max.apply(null, rec.disks.map((d) => d.usedPct)) : null, ms: rec.ms });
    S.trimLineFile(HISTORY, 4 * 1024 * 1024);
    const hs = hosts.list();
    const one = hs.find((x) => x.id === id);
    if (one) { one.lastSeenAt = S.now(); one.lastCheckOk = true; one.lastCheckError = ''; hosts.saveList(hs); }
  }
  return { ok: !!r.ok, host: rec };
}

let timer = null;
const intervalSec = () => Number(process.env.CM_OPS_REMOTE_SEC || 300);

function due() {
  const st = state().hosts || {};
  return credList().map((c) => c.id).filter((id) => {
    const h = hosts.list().find((x) => x.id === id);
    if (!h || !h.addr) return false;
    const last = st[id] && st[id].at ? st[id].at : 0;
    return S.now() - last >= intervalSec() * 1000;
  });
}

async function sweep() {
  const out = [];
  for (const id of due()) {
    try { const r = await collect(id); out.push({ id, ok: r.ok }); } catch (e) { out.push({ id, ok: false, error: e.message }); }
  }
  return { ok: true, collected: out.length, results: out };
}

function start() {
  stop();
  timer = setInterval(() => { sweep().catch(() => {}); }, 60 * 1000);
  if (timer.unref) timer.unref();
}
function stop() { if (timer) { clearInterval(timer); timer = null; } }

function view() {
  const st = state().hosts || {};
  return hosts.list().map((h) => {
    const cred = credOf(h.id);
    const last = st[h.id];
    return {
      id: h.id, label: h.label, addr: h.addr, role: h.role, env: h.env,
      cred: maskCred(cred) || null,
      last: last || null,
      online: !!(last && last.ok && S.now() - last.at < intervalSec() * 3000),
    };
  });
}

function history(id, hours) {
  const n = Math.max(1, Math.min(720, Number(hours) || 6));
  return S.readLines(HISTORY, 4000).filter((r) => (!id || r.id === id) && r.ts > S.now() - n * 3600000);
}

function register(api, wrap, audited) {
  api.get('/ops/remote', wrap(async (req, res) => res.json({
    ok: true, items: view(), intervalSec: intervalSec(), runner: ssh2 ? 'ssh2' : 'missing',
    due: due().length, scheduled: !!timer,
  })));
  api.get('/ops/remote/history', wrap(async (req, res) => res.json({ ok: true, items: history(req.query.id, req.query.hours) })));
  api.post('/ops/remote/cred', audited('ops.remote.cred', async (req) => saveCred(req.body || {})));
  api.post('/ops/remote/cred/delete', audited('ops.remote.cred.delete', async (req) => dropCred((req.body || {}).id)));
  api.post('/ops/remote/collect', audited('ops.remote.collect', async (req) => {
    const b = req.body || {};
    return b.id ? await collect(String(b.id), b) : await sweep();
  }));
}

module.exports = { register, collect, sweep, start, stop, view, history, saveCred, credList, maskCred, parseBundle, splitSections, due, intervalSec, BUNDLE, CRED, STATE, HISTORY };
