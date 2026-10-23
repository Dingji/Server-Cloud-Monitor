'use strict';
/*
 * Disk hygiene (磁盘治理): capacity + inode overview, biggest files/dirs, log usage,
 * one-click cleaners that report how much they actually freed, an opt-in daily
 * schedule, and a per-mount usage history so growth can be trended over time.
 *
 * Every mutating action measures free space before/after and is audited; the log
 * truncator only accepts regular files under a small allowlist of log roots.
 */
const path = require('path');
const S = require('./store');
const ex = require('./exec');

const EVENTS = 'disk_events.jsonl';
const CONFIG = 'disk_schedule.json';
const HISTORY = 'disk_history.jsonl';
const LAST_SAMPLE = 'disk_last_sample.json';

/* read at call time so operators can narrow the list without a restart race */
function truncateRoots() {
  return (process.env.CM_DISK_LOG_ROOTS || '/var/log,/opt,/srv,/www,/data,/home')
    .split(',').map((s) => s.trim()).filter(Boolean);
}
const KB = 1024;

const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null; };

function parseDf(out, percent) {
  const rows = [];
  for (const line of (out || '').split('\n').slice(1)) {
    const c = line.trim().split(/\s+/);
    if (c.length < 6) continue;
    const totalKb = num(c[1]), usedKb = num(c[2]), availKb = num(c[3]);
    if (totalKb == null || totalKb < 1024 * 1024) continue; /* skip tmpfs-sized noise like /boot/efi */
    rows.push({
      filesystem: c[0], totalKb, usedKb, availKb,
      usedPct: num((c[4] || '').replace('%', '')),
      mount: c.slice(5).join(' '),
      inodes: percent == null ? null : { total: num(c[1]), used: num(c[2]), free: num(c[3]), usedPct: num((c[4] || '').replace('%', '')) },
    });
  }
  return rows;
}

function parseInodeDf(out) {
  const rows = [];
  for (const line of (out || '').split('\n').slice(1)) {
    /* Filesystem Inodes IUsed IFree IUse% Mounted on — six columns */
    const c = line.trim().split(/\s+/);
    if (c.length < 6) continue;
    rows.push({ filesystem: c[0], mount: c.slice(5).join(' '), inodes: num(c[1]), used: num(c[2]), free: num(c[3]), usedPct: num((c[4] || '').replace('%', '')) });
  }
  return rows;
}

function toKb(s) {
  /* journalctl reports "512.0M" (no trailing B); df-style output may say "1.5G"/"2GiB" */
  const m = /^([0-9.]+)\s*([KMGT]?i?B?)$/i.exec(String(s || '').trim());
  if (!m) return null;
  const v = Number(m[1]);
  const u = (m[2] || 'B').toUpperCase().replace('I', '');
  const mult = { B: 1 / KB, K: 1, M: KB, G: KB * 1024, T: KB * 1024 * 1024 }[(u[0] || 'B')] || 1;
  return Math.round(v * mult);
}

async function freeKb(mount) {
  const r = await ex.run(`df -Pk ${S.shq(mount || '/')} 2>/dev/null | tail -1 | awk '{print $4}'`, { timeout: 8000 });
  return num((r.stdout || '').trim());
}

async function sampleHistory() {
  const last = S.readJson(LAST_SAMPLE, { at: 0 });
  if (S.now() - last.at < 30 * 60 * 1000) return { ok: true, skipped: true };
  const d = await ex.run('df -Pk 2>/dev/null | tail -n +2', { timeout: 10000 });
  const seen = new Set();
  for (const line of (d.stdout || '').split('\n')) {
    const c = line.trim().split(/\s+/);
    if (c.length < 6) continue;
    const totalKb = num(c[1]);
    if (!totalKb || totalKb < 1024 * 1024) continue;
    const mount = c.slice(5).join(' ');
    if (seen.has(mount)) continue;
    seen.add(mount);
    S.appendLine(HISTORY, { ts: S.now(), mount, totalKb, usedKb: num(c[2]), availKb: num(c[3]), usedPct: num((c[4] || '').replace('%', '')) });
  }
  /* keep ~180 days of hourly-ish samples */
  const cutoff = S.now() - 180 * 86400000;
  try {
    const keep = require('fs').readFileSync(S.file(HISTORY), 'utf8').split('\n').filter(Boolean)
      .filter((l) => { try { return JSON.parse(l).ts >= cutoff; } catch (_) { return false; } });
    require('fs').writeFileSync(S.file(HISTORY), keep.length ? keep.join('\n') + '\n' : '', 'utf8');
  } catch (_) {}
  S.writeJson(LAST_SAMPLE, { at: S.now() });
  return { ok: true, sampled: seen.size };
}

async function overview() {
  const [df, idf, journal, apt, logdir] = await Promise.all([
    ex.run('df -Pk 2>/dev/null', { timeout: 10000 }),
    ex.run('df -Pi 2>/dev/null', { timeout: 10000 }),
    ex.run("journalctl --disk-usage 2>/dev/null | grep -oE '[0-9.]+[KMGT]?i?B?' | head -1", { timeout: 12000 }),
    ex.run('du -sk /var/cache/apt 2>/dev/null | cut -f1', { timeout: 20000 }),
    ex.run('du -sk /var/log 2>/dev/null | cut -f1', { timeout: 20000 }),
  ]);
  const mounts = parseDf(df.stdout);
  const inodes = parseInodeDf(idf.stdout);
  for (const m of mounts) {
    const i = inodes.find((x) => x.mount === m.mount);
    if (i) m.inodeUsedPct = i.usedPct;
  }
  sampleHistory().catch(() => {});
  return {
    ok: true, at: S.now(), mounts,
    journalKb: toKb((journal.stdout || '').trim()),
    aptCacheKb: num((apt.stdout || '').trim()),
    varLogKb: num((logdir.stdout || '').trim()),
    trashKb: sizeKbOf(S.file('files_trash')),
    schedule: getConfig(),
    runner: ex.mode(),
  };
}

function sizeKbOf(p) {
  try {
    const fs = require('fs');
    let sum = 0;
    const walk = (d, depth) => {
      if (depth > 4) return;
      for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const f = path.join(d, e.name);
        try {
          if (e.isDirectory()) walk(f, depth + 1);
          else sum += fs.statSync(f).size;
        } catch (_) {}
      }
    };
    if (fs.existsSync(p)) walk(p, 0);
    return Math.round(sum / KB);
  } catch (_) { return null; }
}

async function bigFiles(q) {
  const minMb = Math.max(10, Math.min(100000, Number(q.minMb) || 100));
  const limit = Math.max(5, Math.min(200, Number(q.limit) || 60));
  const root = String(q.path || '/');
  const cmd = `find ${S.shq(root)} -xdev -type f -size +${minMb}M -printf '%s\\t%p\\n' 2>/dev/null | sort -rn | head -n ${limit}`;
  const r = await ex.run(cmd, { timeout: 120000 });
  const items = (r.stdout || '').split('\n').filter(Boolean).map((l) => {
    const i = l.indexOf('\t');
    return { size: num(l.slice(0, i)) || 0, path: l.slice(i + 1) };
  });
  return { ok: true, path: root, minMb, limit, items, totalSize: items.reduce((a, b) => a + b.size, 0), stderr: r.code ? (r.stderr || '').slice(0, 200) : '' };
}

async function topDirs(q) {
  const dir = String(q.path || '/');
  const depth = Math.max(1, Math.min(3, Number(q.depth) || 1));
  const limit = Math.max(5, Math.min(60, Number(q.limit) || 25));
  const r = await ex.run(`du -xk --max-depth=${depth} ${S.shq(dir)} 2>/dev/null | sort -rn | head -n ${limit}`, { timeout: 180000 });
  const items = (r.stdout || '').split('\n').filter(Boolean).map((l) => {
    const c = l.split('\t');
    return { kb: num(c[0]) || 0, path: c[1] || '' };
  }).filter((x) => x.path);
  return { ok: true, path: dir, depth, items };
}

async function logFiles(q) {
  const dir = String(q.path || '/var/log');
  const r = await ex.run(`find ${S.shq(dir)} -xdev -type f -printf '%s\\t%TY-%Tm-%Td %TH:%TM\\t%p\\n' 2>/dev/null | sort -rn | head -n 30`, { timeout: 60000 });
  const items = (r.stdout || '').split('\n').filter(Boolean).map((l) => {
    const c = l.split('\t');
    return { size: num(c[0]) || 0, mtimeText: c[1] || '', path: c[2] || '' };
  });
  const rotated = items.filter((x) => /\.(gz|xz|bz2|zst|old|\d)$/.test(x.path));
  const reclaimableKb = Math.round(items.reduce((a, b) => a + (/\.(gz|xz|bz2|zst|old|\d)$/.test(b.path) ? b.size : b.size > 20 * 1024 * 1024 ? b.size - 1024 * 1024 : 0), 0) / KB);
  return { ok: true, path: dir, items, rotatedCount: rotated.length, reclaimableKb, journalKb: null };
}

/* ---------------- cleaners ---------------- */

const CLEANERS = {
  journal: {
    label: 'journald 日志压缩',
    defaults: { size: '200M', days: 14 },
    async run(p) {
      const size = /^[0-9]+[KMGT]?$/i.test(String(p.size || '')) ? p.size : '200M';
      const days = Math.max(1, Math.min(3650, Number(p.days) || 14));
      const a = await ex.run(`journalctl --disk-usage 2>/dev/null | grep -oE '[0-9.]+[KMGT]?i?B?' | head -1`, { timeout: 15000 });
      const r = await ex.run(`journalctl --vacuum-size=${S.shq(size)} 2>&1 | tail -3; journalctl --vacuum-time=${days}d 2>&1 | tail -2; journalctl --disk-usage 2>/dev/null | grep -oE '[0-9.]+[KMGT]?i?B?' | head -1`, { timeout: 120000 });
      return { ok: r.code === 0, output: `清理前 ${a.stdout.trim() || '?'} → 现在 ${r.stdout.trim().split('\n').pop()}`, detail: r.stdout.slice(0, 600) };
    },
  },
  apt: {
    label: 'APT 缓存与孤立依赖',
    async run() {
      const r = await ex.run('apt-get clean -y 2>&1 | tail -2; apt-get autoclean -y 2>&1 | tail -2; apt-get autoremove --purge -y 2>&1 | tail -4', { timeout: 300000 });
      return { ok: r.code === 0, output: r.stdout.slice(-500) };
    },
  },
  rotlogs: {
    label: '30 天前的轮转日志（.gz/.1/.old）',
    defaults: { days: 30 },
    async run(p) {
      const days = Math.max(1, Math.min(3650, Number(p.days) || 30));
      const r = await ex.run(`find /var/log -xdev -type f \\( -name '*.gz' -o -name '*.[0-9]' -o -name '*.old' -o -name '*.xz' \\) -mtime +${days} -printf '%s\\t%p\\n' 2>/dev/null | tee /tmp/cm-rotlogs.txt | wc -l; du -ch $(awk -F'\\t' '{print $2}' /tmp/cm-rotlogs.txt) 2>/dev/null | tail -1; awk -F'\\t' '{print $2}' /tmp/cm-rotlogs.txt | xargs -r rm -f; rm -f /tmp/cm-rotlogs.txt`, { timeout: 120000 });
      const lines = (r.stdout || '').split('\n');
      return { ok: true, output: `删除 ${lines[0] || '0'} 个旧轮转日志；${lines[1] || ''}` };
    },
  },
  tmp: {
    label: '/tmp 长期未访问文件',
    defaults: { days: 10 },
    async run(p) {
      const days = Math.max(1, Math.min(365, Number(p.days) || 10));
      const r = await ex.run(`find /tmp -xdev -mindepth 1 -atime +${days} -not -path '*/systemd-private-*' -not -path '*/snap-private-tmp*' -printf '%s %p\\n' 2>/dev/null | awk '{s+=$1; print} END {print "TOTALKB", int(s/1024)}' | tail -20; find /tmp -xdev -mindepth 1 -atime +${days} -not -path '*/systemd-private-*' -not -path '*/snap-private-tmp*' -delete 2>&1 | tail -3`, { timeout: 120000 });
      return { ok: true, output: r.stdout.slice(-600) };
    },
  },
  docker: {
    label: 'Docker 悬空镜像与构建缓存（不动卷）',
    async run() {
      const has = await ex.hasBin('docker');
      if (!has) return { ok: false, output: '未安装 docker，跳过' };
      const before = await ex.run('docker system df 2>/dev/null | head -6', { timeout: 30000 });
      const r = await ex.run('docker image prune -f 2>&1 | tail -2; docker builder prune -f 2>&1 | tail -2', { timeout: 300000 });
      return { ok: r.code === 0, output: `清理前：${before.stdout.trim()}\\n清理：${r.stdout.trim()}` };
    },
  },
};

async function clean(targets, params, ip) {
  const list = (Array.isArray(targets) && targets.length ? targets : []).filter((t) => CLEANERS[t]);
  if (!list.length) { const e = new Error('没有可执行的清理项（可选：' + Object.keys(CLEANERS).join('/') + '）'); e.status = 400; throw e; }
  const before = await freeKb('/');
  const results = [];
  for (const t of list) {
    const started = S.now();
    let r;
    try { r = await CLEANERS[t].run((params || {})[t] || {}); }
    catch (e) { r = { ok: false, output: e.message }; }
    results.push({ target: t, label: CLEANERS[t].label, ok: !!r.ok, output: String(r.output || '').slice(0, 900), ms: S.now() - started });
  }
  const after = await freeKb('/');
  const freedKb = before != null && after != null ? Math.max(0, after - before) : null;
  const rec = { at: S.now(), targets: list, results, freedKb, beforeKb: before, afterKb: after, ip: ip || '', source: (params && params.source) || 'manual' };
  S.appendLine(EVENTS, rec);
  S.trimLineFile(EVENTS, 2 * 1024 * 1024);
  return { ok: results.every((x) => x.ok), ...rec };
}

function getConfig() {
  return S.readJson(CONFIG, {
    enabled: false, hour: 4, journalSize: '200M', journalDays: 14, rotDays: 30, tmpDays: 10,
    targets: ['journal', 'apt', 'rotlogs'], lastRunDay: '',
  });
}

async function runScheduled() {
  const cfg = getConfig();
  if (!cfg.enabled) return { ok: true, skipped: 'disabled' };
  const day = S.dayKey();
  const hour = new Date().getHours();
  if (cfg.lastRunDay === day || hour !== cfg.hour) return { ok: true, skipped: 'not-due' };
  cfg.lastRunDay = day;
  S.writeJson(CONFIG, cfg);
  return clean(cfg.targets, {
    journal: { size: cfg.journalSize, days: cfg.journalDays },
    rotlogs: { days: cfg.rotDays },
    tmp: { days: cfg.tmpDays },
    source: 'schedule',
  }, 'scheduler');
}

let timer = null;
function start() {
  stop();
  timer = setInterval(() => { runScheduled().catch(() => {}); }, 10 * 60 * 1000);
  if (timer.unref) timer.unref();
}
function stop() { if (timer) { clearInterval(timer); timer = null; } }

function events(limit) { return S.readLines(EVENTS, limit || 60); }

function history(mount, days) {
  const n = Math.max(1, Math.min(180, Number(days) || 30));
  const cutoff = S.now() - n * 86400000;
  return S.readLines(HISTORY, 6000).filter((r) => r.ts >= cutoff && (!mount || r.mount === mount));
}

/* ---------------- routes ---------------- */

function register(api, wrap, audited) {
  api.get('/ops/disk/overview', wrap(async (req, res) => res.json(await overview())));
  api.get('/ops/disk/bigfiles', wrap(async (req, res) => res.json(await bigFiles(req.query || {}))));
  api.get('/ops/disk/topdirs', wrap(async (req, res) => res.json(await topDirs(req.query || {}))));
  api.get('/ops/disk/logs', wrap(async (req, res) => res.json(await logFiles(req.query || {}))));
  api.get('/ops/disk/events', wrap(async (req, res) => res.json({ ok: true, items: events(req.query.limit) })));
  api.get('/ops/disk/history', wrap(async (req, res) => res.json({ ok: true, mount: req.query.mount || null, days: Number(req.query.days) || 30, items: history(req.query.mount, req.query.days) })));
  api.get('/ops/disk/schedule', wrap(async (req, res) => res.json({ ok: true, config: getConfig(), cleaners: Object.entries(CLEANERS).map(([k, v]) => ({ key: k, label: v.label, defaults: v.defaults || {} })) })));
  api.post('/ops/disk/schedule/save', audited('ops.disk.schedule', async (req) => {
    const b = req.body || {};
    const cfg = getConfig();
    const next = {
      enabled: b.enabled === undefined ? cfg.enabled : !!b.enabled,
      hour: Math.round(S.clampNum(b.hour, 0, 23, cfg.hour)),
      journalSize: /^[0-9]+[KMGT]?$/i.test(String(b.journalSize || '')) ? b.journalSize : cfg.journalSize,
      journalDays: Math.round(S.clampNum(b.journalDays, 1, 3650, cfg.journalDays)),
      rotDays: Math.round(S.clampNum(b.rotDays, 1, 3650, cfg.rotDays)),
      tmpDays: Math.round(S.clampNum(b.tmpDays, 1, 365, cfg.tmpDays)),
      targets: Array.isArray(b.targets) ? b.targets.filter((t) => CLEANERS[t]) : cfg.targets,
      lastRunDay: cfg.lastRunDay,
    };
    S.writeJson(CONFIG, next);
    return { ok: true, config: next };
  }));
  api.post('/ops/disk/clean', audited('ops.disk.clean', async (req) => {
    const b = req.body || {};
    return clean(b.targets, b.params || b, req.clientIp);
  }));
  api.post('/ops/disk/run', audited('ops.disk.run', async () => runScheduled()));
  api.post('/ops/disk/truncate', audited('ops.disk.truncate', async (req) => {
    const b = req.body || {};
    const fs = require('fs');
    const p = String(b.path || '');
    const isAbs = p.startsWith('/') || /^[a-zA-Z]:[\\/]/.test(p);
    if (!isAbs) { const e = new Error('必须是绝对路径'); e.status = 400; throw e; }
    const roots = truncateRoots();
    if (!roots.some((r) => p.startsWith(r + '/') || p === r)) {
      const e = new Error('只允许清空这些目录下的日志：' + roots.join(', ')); e.status = 403; throw e;
    }
    let st;
    try { st = fs.lstatSync(p); } catch (_) { const e = new Error('文件不存在：' + p); e.status = 404; throw e; }
    if (st.isSymbolicLink()) { const e = new Error('拒绝清空符号链接'); e.status = 403; throw e; }
    if (!st.isFile()) { const e = new Error('不是普通文件：' + p); e.status = 400; throw e; }
    const before = st.size;
    const r = b.mode === 'copytruncate'
      ? await ex.run(`cp -a ${S.shq(p)} ${S.shq(p + '.cm-backup')} 2>/dev/null; truncate -s 0 ${S.shq(p)}; ls -l ${S.shq(p)} | awk '{print $5}'`, { timeout: 60000 })
      : await ex.run(`truncate -s 0 ${S.shq(p)} 2>&1; stat -c %s ${S.shq(p)}`, { timeout: 30000 });
    const after = num((r.stdout || '').trim().split('\n').pop());
    const rec = { at: S.now(), targets: ['truncate'], results: [{ target: 'truncate', ok: r.code === 0, output: `${p}: ${before} → ${after} 字节` }], freedKb: Math.max(0, Math.round((before - (after || 0)) / KB)), ip: req.clientIp || '', source: 'truncate', path: p };
    S.appendLine(EVENTS, rec);
    return { ok: r.code === 0, before, after: after == null ? null : after, output: r.stdout.slice(0, 400) };
  }));
}

module.exports = { register, overview, bigFiles, topDirs, logFiles, clean, events, history, getConfig, start, stop, runScheduled, sampleHistory, parseDf, parseInodeDf, toKb, truncateRoots, CLEANERS, EVENTS, CONFIG, HISTORY };
