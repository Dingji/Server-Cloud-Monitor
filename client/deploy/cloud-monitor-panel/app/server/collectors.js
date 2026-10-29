'use strict';
/*
 * Collectors: read system state cheaply.
 * Core metrics come from /proc (no subprocess per tick); heavier details are
 * collected on demand and cached briefly.
 */
if (process.platform === 'win32') {
  module.exports = require('./collectors-win');
  return;
}
const { getAgent } = require('./agent');
const { parseInt10, toFloat, logErr } = require('./util');

/* =============== state for rate computation =============== */
let prevCpu = null;      // { total:{...numbers}, cores:[[...]] }
let prevNet = null;      // { iface: {rx, tx} }
let prevDisk = null;     // { readSectors, writeSectors }
let prevTs = 0;

/* =============== /proc parsing =============== */
function parseProcStat(text) {
  if (!text) return null;
  const lines = text.split('\n');
  const out = { total: null, cores: [] };
  for (const line of lines) {
    if (!/^cpu/.test(line)) continue;
    const parts = line.trim().split(/\s+/);
    const label = parts.shift();
    const nums = parts.map(parseInt10);
    const idle = (nums[3] || 0) + (nums[4] || 0); // idle + iowait
    const busy = (nums[0] || 0) + (nums[1] || 0) + (nums[2] || 0) + (nums[5] || 0) + (nums[6] || 0) + (nums[7] || 0);
    const rec = { user: nums[0] || 0, nice: nums[1] || 0, system: nums[2] || 0, idle, iowait: nums[4] || 0, irq: nums[5] || 0, softirq: nums[6] || 0, steal: nums[7] || 0, total: idle + busy };
    if (label === 'cpu') out.total = rec; else out.cores.push(rec);
  }
  return out.total ? out : null;
}

function cpuDelta(prev, cur) {
  if (!prev || !cur) return null;
  const dTotal = cur.total - prev.total;
  if (dTotal <= 0) return null;
  const dIdle = cur.idle - prev.idle;
  return Math.max(0, Math.min(100, +(((dTotal - dIdle) / dTotal) * 100).toFixed(1)));
}

function parseMeminfo(text) {
  const m = {};
  if (!text) return m;
  for (const line of text.split('\n')) {
    const mm = /^(\w+):\s+(\d+)\s*kB/.exec(line);
    if (mm) m[mm[1]] = parseInt10(mm[2]) * 1024;
  }
  return m;
}

function parseNetDev(text) {
  const out = {};
  if (!text) return out;
  const lines = text.split('\n').slice(2); // skip headers
  for (const line of lines) {
    const i = line.indexOf(':');
    if (i < 0) continue;
    const iface = line.slice(0, i).trim();
    const p = line.slice(i + 1).trim().split(/\s+/);
    out[iface] = { rx: parseInt10(p[0]), tx: parseInt10(p[8]) };
  }
  return out;
}

const WHOLE_DISK_RE = /^(sd[a-z]+|vd[a-z]+|xvd[a-z]+|nvme\d+n\d+|hd[a-z]+|mmcblk\d+|dm-\d+|md\d+)$/;

function parseDiskstats(text) {
  let readSectors = 0, writeSectors = 0, readOps = 0, writeOps = 0;
  if (text) {
    for (const line of text.split('\n')) {
      const p = line.trim().split(/\s+/);
      if (p.length < 14) continue;
      const dev = p[2];
      if (!WHOLE_DISK_RE.test(dev)) continue;
      readOps += parseInt10(p[3]); readSectors += parseInt10(p[5]);
      writeOps += parseInt10(p[7]); writeSectors += parseInt10(p[9]);
    }
  }
  return { readSectors, writeSectors, readOps, writeOps };
}

function parseLoadavg(text) {
  if (!text) return [0, 0, 0];
  return text.trim().split(/\s+/).slice(0, 3).map(toFloat);
}

/* =============== main metrics tick =============== */
async function getMetrics(opts = {}) {
  const agent = getAgent();
  const files = await agent.readFiles(['/proc/stat', '/proc/meminfo', '/proc/loadavg', '/proc/uptime', '/proc/net/dev', '/proc/diskstats']);
  const now = Date.now();
  const dt = prevTs ? (now - prevTs) / 1000 : 0;
  prevTs = now;

  const stat = parseProcStat(files['/proc/stat']);
  const mem = parseMeminfo(files['/proc/meminfo']);
  const load = parseLoadavg(files['/proc/loadavg']);
  const uptimeSec = toFloat((files['/proc/uptime'] || '').split(/\s+/)[0]);
  const netCur = parseNetDev(files['/proc/net/dev']);
  const diskCur = parseDiskstats(files['/proc/diskstats']);

  /* CPU */
  const cpu = { totalPct: null, cores: [], loadAvg: load, uptimeSec, procs: null };
  if (stat) {
    const pct = cpuDelta(prevCpu && prevCpu.total, stat.total);
    cpu.totalPct = pct == null ? 0 : pct;
    cpu.cores = stat.cores.map((c, i) => cpuDelta(prevCpu && prevCpu.cores[i], c) ?? 0);
    cpu.times = { user: stat.total.user, system: stat.total.system, idle: stat.total.idle, iowait: stat.total.iowait, steal: stat.total.steal };
    cpu.coresCount = stat.cores.length;
    prevCpu = stat;
  } else if (prevCpu) {
    cpu.totalPct = 0; cpu.cores = prevCpu.cores.map(() => 0);
  }
  const rp = /(\d+)\s+(\d+)\s*$/m.exec(files['/proc/loadavg'] || '');
  if (rp) cpu.procs = { running: parseInt10(rp[1]), total: parseInt10(rp[2]) };

  /* memory */
  const memTotal = mem.MemTotal || 0;
  const memAvail = mem.MemAvailable || 0;
  const memUsed = Math.max(0, memTotal - memAvail - (mem.Buffers || 0));
  const swapTotal = mem.SwapTotal || 0;
  const swapUsed = Math.max(0, swapTotal - (mem.SwapFree || 0));
  const memory = {
    total: memTotal, free: mem.MemFree || 0, available: memAvail,
    buffers: mem.Buffers || 0, cached: mem.Cached || 0, sReclaimable: mem.SReclaimable || 0,
    used: memUsed, usedPct: memTotal ? +((memUsed / memTotal) * 100).toFixed(1) : 0,
    actualUsedPct: memTotal ? +(((memTotal - memAvail) / memTotal) * 100).toFixed(1) : 0,
    swapTotal, swapUsed, swapPct: swapTotal ? +((swapUsed / swapTotal) * 100).toFixed(1) : 0,
    dirty: mem.Dirty || 0, writeback: mem.Writeback || 0, shmem: mem.Shmem || 0, huge: mem.HugePagesTotal || 0,
  };

  /* network rates */
  const net = { ifaces: {} };
  for (const iface of Object.keys(netCur)) {
    const cur = netCur[iface];
    const prev = prevNet && prevNet[iface];
    let rxBps = 0, txBps = 0;
    if (prev && dt > 0) {
      const drx = cur.rx - prev.rx, dtx = cur.tx - prev.tx;
      if (drx >= 0) rxBps = drx / dt;
      if (dtx >= 0) txBps = dtx / dt;
    }
    net.ifaces[iface] = { rxBps: +rxBps.toFixed(1), txBps: +txBps.toFixed(1), rxTotal: cur.rx, txTotal: cur.tx };
  }
  prevNet = netCur;

  /* disk io rates */
  const io = { readBps: 0, writeBps: 0, readOps: 0, writeOps: 0 };
  if (prevDisk && dt > 0) {
    const dr = diskCur.readSectors - prevDisk.readSectors;
    const dw = diskCur.writeSectors - prevDisk.writeSectors;
    if (dr >= 0) io.readBps = (dr * 512) / dt;
    if (dw >= 0) io.writeBps = (dw * 512) / dt;
    const or_ = diskCur.readOps - prevDisk.readOps, ow = diskCur.writeOps - prevDisk.writeOps;
    if (or_ >= 0) io.readOps = or_ / dt;
    if (ow >= 0) io.writeOps = ow / dt;
  }
  prevDisk = diskCur;

  return { ts: now, cpu, memory, net, diskio: io };
}

/* =============== disk usage (cached 5s) =============== */
let diskCache = { ts: 0, data: null };
async function getDisks(force) {
  const now = Date.now();
  if (!force && diskCache.data && now - diskCache.ts < 5000) return diskCache.data;
  const agent = getAgent();
  const [df, dfi, lsblk] = await Promise.all([
    agent.run("df -B1 -P -x tmpfs -x devtmpfs -x squashfs -x overlay -x efivarfs 2>/dev/null | tail -n +2"),
    agent.run("df -i -P -x tmpfs -x devtmpfs -x squashfs -x overlay 2>/dev/null | tail -n +2"),
    agent.run("lsblk -J -o NAME,PATH,SIZE,TYPE,FSTYPE,MOUNTPOINTS 2>/dev/null", { timeout: 8000 }),
  ]);
  let blocks = [];
  try {
    const flat = [];
    const walk = (arr, depth) => { for (const n of arr || []) { flat.push({ name: '  '.repeat(depth) + n.name, size: n.size, type: n.type, fstype: n.fstype || '', mountpoint: (n.mountpoints || []).filter(Boolean).join(', ') || '' }); if (n.children) walk(n.children, depth + 1); } };
    walk(JSON.parse(lsblk.stdout).blockdevices, 0);
    blocks = flat;
  } catch (_) {}
  const mounts = [];
  for (const line of df.stdout.split('\n')) {
    const p = line.trim().split(/\s+/);
    if (p.length < 6) continue;
    const total = parseInt10(p[1]), used = parseInt10(p[2]), avail = parseInt10(p[3]);
    mounts.push({ dev: p[0], total, used, avail, pct: toFloat(p[4]), mount: p[5] });
  }
  const inodes = {};
  for (const line of dfi.stdout.split('\n')) {
    const p = line.trim().split(/\s+/);
    if (p.length < 7) continue;
    inodes[p[5]] = { itotal: parseInt10(p[1]), iused: parseInt10(p[2]), iavail: parseInt10(p[3]), ipct: toFloat(p[4]) };
  }
  for (const m of mounts) Object.assign(m, inodes[m.mount] || {});
  let overall = null;
  const root = mounts.find((m) => m.mount === '/') || mounts[0];
  if (root) overall = root.pct;
  const data = { ts: now, mounts, blocks, overallPct: overall };
  diskCache = { ts: now, data };
  return data;
}

/* =============== top processes (cached 2s) =============== */
let procCache = { ts: 0, data: null };
const PS_RE = /^(\d+)\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\S+)\s+(.*)$/;

async function getProcesses(opts = {}) {
  const now = Date.now();
  const limit = opts.limit || 150;
  if (!opts.force && procCache.data && now - procCache.ts < 2000) return procCache.data;
  const agent = getAgent();
  const r = await agent.run("ps -eo pid=,ppid=,user=,pcpu=,pmem=,rss=,vsz=,stat=,etime=,args= --sort=-%cpu 2>/dev/null");
  const list = [];
  const statCounts = { running: 0, sleeping: 0, zombie: 0, stopped: 0, other: 0 };
  for (const line of r.stdout.split('\n')) {
    const m = PS_RE.exec(line.trim());
    if (!m) continue;
    const st = m[8];
    if (/^R/.test(st)) statCounts.running++;
    else if (/^S/.test(st)) statCounts.sleeping++;
    else if (/^Z/.test(st)) statCounts.zombie++;
    else if (/^T/.test(st)) statCounts.stopped++;
    else statCounts.other++;
    list.push({ pid: parseInt10(m[1]), ppid: parseInt10(m[2]), user: m[3], cpu: toFloat(m[4]), mem: toFloat(m[5]), rss: parseInt10(m[6]) * 1024, vsz: parseInt10(m[7]) * 1024, stat: st, etime: m[9], args: m[10].slice(0, 300) });
  }
  const data = { ts: now, total: list.length, statCounts, list: list.slice(0, limit) };
  procCache = { ts: now, data };
  return data;
}

/* =============== system info (cached 30s) =============== */
let infoCache = { ts: 0, data: null };
let extIpCache = { ts: 0, ip: null };

async function getSystemInfo(force) {
  const now = Date.now();
  if (!force && infoCache.data && now - infoCache.ts < 30000) return infoCache.data;
  const agent = getAgent();
  const files = await agent.readFiles(['/etc/os-release', '/proc/cpuinfo', '/proc/meminfo', '/etc/hostname', '/proc/cmdline']);
  let osName = 'Linux', osVersion = '';
  if (files['/etc/os-release']) {
    const kv = {};
    for (const line of files['/etc/os-release'].split('\n')) {
      const m = /^([A-Z_]+)="?(.*?)"?$/.exec(line.trim());
      if (m) kv[m[1]] = m[2];
    }
    osName = `${kv.PRETTY_NAME || kv.NAME || 'Linux'}`;
    osVersion = kv.VERSION_ID || '';
  }
  let cpuModel = 'unknown', cores = 0;
  if (files['/proc/cpuinfo']) {
    const mm = /model name\s*:\s*(.+)/.exec(files['/proc/cpuinfo']);
    if (mm) cpuModel = mm[1].trim();
    cores = (files['/proc/cpuinfo'].match(/^processor\s*:/gm) || []).length;
  }
  const mem = parseMeminfo(files['/proc/meminfo']);
  const [uname, hostname] = await Promise.all([agent.run('uname -r'), Promise.resolve((files['/etc/hostname'] || '').trim())]);

  /* external ip, cached 10 min, best effort */
  if (!extIpCache.ip || now - extIpCache.ts > 600000) {
    const r = await agent.run("curl -4 -s --max-time 6 https://ifconfig.me 2>/dev/null || curl -4 -s --max-time 6 https://api.ipify.org 2>/dev/null", { timeout: 8000 });
    const ip = r.stdout.trim().split('\n')[0] || '';
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) extIpCache = { ts: now, ip };
    else extIpCache.ts = now;
  }

  const data = {
    hostname: hostname || 'unknown',
    osName, osVersion,
    kernel: uname.stdout.trim(),
    arch: process.arch,
    cpuModel, cores,
    memTotal: mem.MemTotal || 0,
    bootTimeApprox: null,
    publicIp: extIpCache.ip,
    panelMode: agent.mode,
    nodeVersion: process.version,
    ts: now,
  };
  infoCache = { ts: now, data };
  return data;
}

/* =============== network detail (cached 3s) =============== */
let netCache = { ts: 0, data: null };

async function getNetworkDetail(force) {
  const now = Date.now();
  if (!force && netCache.data && now - netCache.ts < 3000) return netCache.data;
  const agent = getAgent();
  const [addrs, routes, resolv, listen, estab, conns] = await Promise.all([
    agent.run('ip -j addr show 2>/dev/null'),
    agent.run('ip -j route show 2>/dev/null'),
    agent.run('cat /etc/resolv.conf 2>/dev/null; resolvectl status 2>/dev/null | grep -E "Current DNS Server|DNS Servers" | head -10'),
    agent.run('ss -lntupH 2>/dev/null'),
    agent.run("ss -H -tnp state established 2>/dev/null | head -80"),
    agent.run('ss -s 2>/dev/null'),
  ]);
  let interfaces = [];
  try {
    interfaces = JSON.parse(addrs.stdout).map((a) => ({
      name: a.ifname, state: a.addr_info && a.addr_info.length ? a.operstate : a.operstate,
      operstate: a.operstate, mtu: a.mtu, mac: a.address,
      ipv4: (a.addr_info || []).filter((x) => x.family === 'inet').map((x) => x.local + '/' + x.prefixlen),
      ipv6: (a.addr_info || []).filter((x) => x.family === 'inet6' && x.scope === 'global').map((x) => x.local + '/' + x.prefixlen),
    }));
  } catch (_) {}
  let defaultRoute = null;
  try {
    for (const r of JSON.parse(routes.stdout)) if (r.dst === 'default') defaultRoute = { gw: r.gateway, dev: r.dev, proto: r.protocol };
  } catch (_) {}
  const listening = listen.stdout.split('\n').filter(Boolean).map((line) => {
    const p = line.trim().split(/\s+/);
    if (p.length < 5) return null;
    return { netid: p[0], state: p[1], recv: p[2], send: p[3], local: p[4], peer: p[5] || '', process: (line.match(/users:\(\(.*\)\)/) || [''])[0] };
  }).filter(Boolean);
  const established = estab.stdout.split('\n').filter(Boolean).map((line) => {
    const p = line.trim().split(/\s+/);
    if (p.length < 5) return null;
    return { local: p[3], peer: p[4], process: (line.match(/users:\(\(.*\)\)/) || [''])[0] };
  }).filter(Boolean);
  const data = {
    ts: now, interfaces, defaultRoute, dns: resolv.stdout.trim(),
    listening, established,
    summary: conns.stdout.trim(),
  };
  netCache = { ts: now, data };
  return data;
}

/* =============== memory detail =============== */
async function getMemoryDetail() {
  const agent = getAgent();
  const [swappiness, procs] = await Promise.all([
    agent.run('cat /proc/sys/vm/swappiness 2>/dev/null'),
    getProcesses({ limit: 400 }),
  ]);
  const byRss = procs.list.slice().sort((a, b) => b.rss - a.rss).slice(0, 15);
  const metrics = await getMetrics();
  return { metrics, swappiness: parseInt10(swappiness.stdout.trim()), topRss: byRss, procTotal: procs.total };
}

/* =============== services (cached 5s) =============== */
let svcCache = { ts: 0, data: null };

async function getServices(force) {
  const now = Date.now();
  if (!force && svcCache.data && now - svcCache.ts < 5000) return svcCache.data;
  const agent = getAgent();
  const [units, files] = await Promise.all([
    agent.run('systemctl list-units --type=service --all --no-pager --plain --no-legend 2>/dev/null'),
    agent.run('systemctl list-unit-files --type=service --no-pager --plain --no-legend 2>/dev/null'),
  ]);
  const enabledMap = {};
  for (const line of files.stdout.split('\n')) {
    const p = line.trim().split(/\s+/);
    if (p.length >= 2) enabledMap[p[0]] = p[1];
  }
  const list = [];
  for (const line of units.stdout.split('\n')) {
    const p = line.trim().split(/\s+/);
    if (p.length < 4) continue;
    const unit = p[0];
    list.push({ unit, load: p[1], active: p[2], sub: p[3], description: p.slice(4).join(' ').slice(0, 160), enabled: enabledMap[unit] || '-' });
  }
  const data = {
    ts: now, list,
    counts: {
      running: list.filter((s) => s.active === 'active').length,
      failed: list.filter((s) => s.active === 'failed').length,
      total: list.length,
    },
  };
  svcCache = { ts: now, data };
  return data;
}

/* =============== security =============== */
const SECURITY_SYSCTL_KEYS = [
  'net.ipv4.tcp_syncookies', 'net.ipv4.conf.all.rp_filter', 'net.ipv4.conf.default.rp_filter',
  'net.ipv4.conf.all.accept_redirects', 'net.ipv4.conf.all.send_redirects', 'net.ipv4.conf.all.accept_source_route',
  'net.ipv4.icmp_echo_ignore_broadcasts', 'net.ipv4.conf.all.log_martians',
  'kernel.kptr_restrict', 'kernel.dmesg_restrict', 'kernel.randomize_va_space',
  'fs.protected_symlinks', 'fs.protected_hardlinks', 'fs.suid_dumpable',
  'net.ipv6.conf.all.accept_redirects', 'net.ipv4.tcp_fin_timeout', 'net.core.somaxconn',
];

async function getSecurity() {
  const agent = getAgent();
  const sysctlPaths = SECURITY_SYSCTL_KEYS.map((k) => '/proc/sys/' + k.split('.').join('/'));
  const [ufwR, fail2banR, jailR, sshdT, lastbR, lastR, passwdF, sudoersR, sysctlFiles, apparmorR] = await Promise.all([
    agent.run('ufw status verbose 2>/dev/null || echo "ufw-not-installed"'),
    agent.run('systemctl is-active fail2ban 2>/dev/null; fail2ban-client status 2>/dev/null | head -20', { timeout: 10000 }),
    agent.run("fail2ban-client status sshd 2>/dev/null | grep -E 'Currently banned|List of banned' -A1 | head -6"),
    agent.run('sshd -T 2>/dev/null | grep -E "^(port|permitrootlogin|passwordauthentication|maxauthtries|permitemptypasswords|pubkeyauthentication|kbdinteractiveauthentication|allowusers|allowgroups)"'),
    agent.run('lastb -n 25 2>/dev/null | head -26'),
    agent.run('last -n 15 -w 2>/dev/null | head -16'),
    agent.run("getent passwd | awk -F: '$7 !~ /(nologin|false)/ {print $1\":\"$3\":\"$6\":\"$7}'"),
    agent.run("grep -rHE '^[^#\\s]' /etc/sudoers /etc/sudoers.d 2>/dev/null | head -25"),
    agent.readFiles(sysctlPaths),
    agent.run('systemctl is-active apparmor 2>/dev/null; aa-status 2>/dev/null | head -5'),
  ]);
  const ufwActive = /Status:\s*active/i.test(ufwR.stdout);
  const ufwRules = [];
  for (const line of ufwR.stdout.split('\n')) {
    const m = /^\s*(\d+)\s+(\S+)\s+(ALLOW|DENY|REJECT)\s+(\S+)\s*(.*)$/.exec(line);
    if (m) ufwRules.push({ num: parseInt10(m[1]), rule: m[2], action: m[3], from: m[4], comment: m[5] || '' });
  }
  const sshd = {};
  for (const line of sshdT.stdout.split('\n')) {
    const i = line.indexOf(' ');
    if (i > 0) sshd[line.slice(0, i)] = line.slice(i + 1).trim();
  }
  const users = passwdF.stdout.split('\n').filter(Boolean).map((l) => {
    const [name, uid, home, shell] = l.split(':');
    return { name, uid: parseInt10(uid), home, shell };
  });
  const sysctl = {};
  for (let i = 0; i < SECURITY_SYSCTL_KEYS.length; i++) {
    const v = sysctlFiles[sysctlPaths[i]];
    sysctl[SECURITY_SYSCTL_KEYS[i]] = v == null ? null : v.trim();
  }
  const fbActive = /active/.test(fail2banR.stdout.split('\n')[0] || '');
  return {
    ufw: { installed: !/ufw-not-installed/.test(ufwR.stdout), active: ufwActive, rules: ufwRules, raw: ufwR.stdout.trim().split('\n').slice(0, 15) },
    fail2ban: { active: fbActive, status: fail2banR.stdout.trim(), banned: jailR.stdout.trim() },
    sshd, users, sudoers: sudoersR.stdout.trim().split('\n').filter(Boolean),
    recentFailed: lastbR.stdout.trim().split('\n').slice(0, 20),
    recentLogins: lastR.stdout.trim().split('\n').slice(0, 12),
    sysctl,
    apparmor: apparmorR.stdout.trim(),
  };
}

/* =============== databases =============== */
async function getDatabases() {
  const agent = getAgent();
  const detect = await agent.run(`
for b in mysql mariadb psql redis-cli mongosh mongo redis-server mysqld mongod sqlite3 db2; do p=$(command -v $b 2>/dev/null) && echo "BIN:$b:$p"; done
for s in mysql mariadb postgresql redis-server mongod db2; do echo "SVC:$s:$(systemctl is-active $s 2>/dev/null):$(systemctl is-enabled $s 2>/dev/null)"; done
`);
  const bins = {}, svcs = {};
  for (const line of detect.stdout.split('\n')) {
    const m = /^BIN:(\S+):(\S+)$/.exec(line.trim());
    if (m) bins[m[1]] = m[2];
    const s = /^SVC:(\S+):(\S+):(\S+)$/.exec(line.trim());
    if (s) svcs[s[1]] = { active: s[2], enabled: s[3] };
  }
  const engines = [];

  /* MySQL / MariaDB */
  if (bins.mysql || bins.mariadb) {
    const cli = bins.mysql ? 'mysql' : 'mariadb';
    const st = await agent.run(`${cli} -NBe "SELECT VERSION();" 2>/dev/null`, { timeout: 8000 });
    const entry = { id: 'mysql', name: bins.mariadb && !bins.mysql ? 'MariaDB' : 'MySQL', installed: true, service: 'mysql', active: (svcs.mysql && svcs.mysql.active === 'active') || (svcs.mariadb && svcs.mariadb.active === 'active'), version: st.stdout.trim() || null, databases: [], stats: null };
    if (entry.active && st.code === 0) {
      const [dbs, stat] = await Promise.all([
        agent.run(`${cli} -NBe "SELECT table_schema, ROUND(SUM(data_length+index_length)/1024/1024,1), COUNT(*) FROM information_schema.tables GROUP BY table_schema ORDER BY 2 DESC;" 2>/dev/null`, { timeout: 10000 }),
        agent.run(`${cli} -NBe "SHOW STATUS WHERE Variable_name IN ('Uptime','Threads_connected','Questions','Connections','Max_used_connections');" 2>/dev/null`, { timeout: 8000 }),
      ]);
      for (const line of dbs.stdout.split('\n')) {
        const p = line.split('\t');
        if (p.length >= 3) entry.databases.push({ name: p[0], sizeMb: toFloat(p[1]), tables: parseInt10(p[2]) });
      }
      const stats = {};
      for (const line of stat.stdout.split('\n')) { const p = line.split('\t'); if (p.length === 2) stats[p[0]] = p[1]; }
      entry.stats = stats;
    }
    engines.push(entry);
  }

  /* PostgreSQL */
  if (bins.psql) {
    const st = await agent.run("su - postgres -c 'psql -Atc \"SELECT version();\"' 2>/dev/null", { timeout: 8000 });
    const entry = { id: 'postgres', name: 'PostgreSQL', installed: true, service: 'postgresql', active: svcs.postgresql && svcs.postgresql.active === 'active', version: (st.stdout.trim().split('\n')[0] || '').slice(0, 80) || null, databases: [], stats: null };
    if (entry.active) {
      const dbs = await agent.run("su - postgres -c 'psql -Atc \"SELECT datname, pg_size_pretty(pg_database_size(datname)), pg_database_size(datname) FROM pg_database ORDER BY pg_database_size(datname) DESC;\"' 2>/dev/null", { timeout: 10000 });
      for (const line of dbs.stdout.split('\n')) {
        const p = line.split('|');
        if (p.length >= 3) entry.databases.push({ name: p[0], size: p[1], sizeMb: parseInt10(p[2]) / 1048576 });
      }
      const con = await agent.run("su - postgres -c 'psql -Atc \"SELECT count(*) FROM pg_stat_activity;\"' 2>/dev/null", { timeout: 8000 });
      entry.stats = { connections: con.stdout.trim() };
    }
    engines.push(entry);
  }

  /* Redis */
  if (bins['redis-cli']) {
    const st = await agent.run("redis-cli INFO server 2>/dev/null | tr -d '\\r' | grep -E 'redis_version|uptime_in_seconds' ; redis-cli INFO clients 2>/dev/null | tr -d '\\r' | grep connected_clients", { timeout: 8000 });
    const entry = { id: 'redis', name: 'Redis', installed: true, service: 'redis-server', active: svcs['redis-server'] && svcs['redis-server'].active === 'active', version: null, databases: [], stats: null };
    const stats = {};
    for (const line of st.stdout.split('\n')) { const p = line.split(':'); if (p.length === 2) stats[p[0]] = p[1]; }
    entry.stats = stats;
    entry.version = stats.redis_version || null;
    if (entry.active) {
      const mem = await agent.run("redis-cli INFO memory 2>/dev/null | tr -d '\\r' | grep -E 'used_memory_human|maxmemory_human'", { timeout: 8000 });
      for (const line of mem.stdout.split('\n')) { const p = line.split(':'); if (p.length === 2) stats[p[0]] = p[1]; }
    }
    engines.push(entry);
  }

  /* MongoDB */
  if (bins.mongosh || bins.mongo || bins.mongod) {
    let version = null;
    const cli = bins.mongosh ? 'mongosh' : 'mongo';
    const st = await agent.run(`${cli} --quiet --eval 'db.version()' 2>/dev/null`, { timeout: 10000 }).catch(() => ({ stdout: '' }));
    if (String(st.stdout || '').trim() && !/^\s*$/.test(st.stdout)) version = String(st.stdout).trim().split('\n').pop();
    engines.push({ id: 'mongo', name: 'MongoDB', installed: true, service: 'mongod', active: svcs.mongod && svcs.mongod.active === 'active', version, databases: [], stats: null });
  }

  /* SQLite (file based; count db files found under common roots) */
  if (bins.sqlite3) {
    const st = await agent.run("sqlite3 --version 2>/dev/null | awk '{print $1}'", { timeout: 8000 }).catch(() => ({ stdout: '' }));
    const cnt = await agent.run("find /var/lib /opt /srv /www /home /root -maxdepth 4 -type f \\( -name '*.db' -o -name '*.sqlite' -o -name '*.sqlite3' -o -name '*.db3' \\) 2>/dev/null | head -500 | wc -l", { timeout: 20000 }).catch(() => ({ stdout: '0' }));
    engines.push({ id: 'sqlite', name: 'SQLite', installed: true, service: null, active: true, version: (String(st.stdout || '').trim() ? 'SQLite ' + String(st.stdout).trim() : null), databases: [], stats: { files: parseFloat(String(cnt.stdout || '0').trim()) || 0 } });
  }

  /* IBM Db2 (LUW) */
  if (bins.db2) {
    const st = await agent.run("su - db2inst1 -c 'db2level' 2>/dev/null | head -3", { timeout: 10000 }).catch(() => ({ stdout: '' }));
    engines.push({ id: 'db2', name: 'IBM Db2', installed: true, service: null, active: true, version: String(st.stdout || '').trim().split('\n')[0] || null, databases: [], stats: null });
  }

  return { ts: Date.now(), engines, bins };
}

/* =============== web servers =============== */
async function getWebServers() {
  const agent = getAgent();
  const [nginxVer, nginxActive, nginxTest, sites, apacheActive, apacheV, certs, certbin] = await Promise.all([
    agent.run('nginx -v 2>&1'),
    agent.run('systemctl is-active nginx 2>/dev/null'),
    agent.run('nginx -t 2>&1 | tail -2', { timeout: 8000 }),
    agent.run('ls -la /etc/nginx/sites-enabled/ 2>/dev/null'),
    agent.run('systemctl is-active apache2 2>/dev/null'),
    agent.run('apache2ctl -v 2>/dev/null | head -1; apache2ctl -S 2>/dev/null | head -30', { timeout: 10000 }),
    agent.run('for d in /etc/letsencrypt/live/*; do [ -f "$d/cert.pem" ] && echo "$d $(openssl x509 -enddate -noout -in "$d/cert.pem" 2>/dev/null)"; done 2>/dev/null; certbot certificates 2>/dev/null | grep -E "Certificate Name|Expiry|Domains" | head -20', { timeout: 15000 }),
    agent.run('command -v certbot 2>/dev/null || echo none'),
  ]);

  const sitesOut = [];
  for (const line of sites.stdout.split('\n')) {
    const t = line.trim();
    if (!t || t.startsWith('total')) continue;
    const link = /^\S+\s+\d+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\S+)\s+->\s+(\S+)$/.exec(t);
    if (link) { sitesOut.push({ name: link[1], path: link[2] }); continue; }
    const regular = /^\S+\s+\d+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\S+)$/.exec(t);
    if (regular && !regular[1].startsWith('.')) sitesOut.push({ name: regular[1], path: '/etc/nginx/sites-enabled/' + regular[1] });
  }
  /* parse server blocks of each site file (best effort) */
  for (const s of sitesOut.slice(0, 20)) {
    const r = await agent.run(`cat ${JSON.stringify(s.path)} 2>/dev/null | head -200`, { timeout: 8000 });
    const blocks = [];
    const re = /server\s*\{/g;
    let m2;
    while ((m2 = re.exec(r.stdout))) {
      const start = m2.index + m2[0].length;
      let depth = 1, i = start;
      while (i < r.stdout.length && depth > 0) {
        if (r.stdout[i] === '{') depth++;
        else if (r.stdout[i] === '}') depth--;
        i++;
      }
      const body = r.stdout.slice(start, i - 1);
      const listen = [...body.matchAll(/listen\s+([^;]+);/g)].map((x) => x[1].trim());
      const serverName = [...body.matchAll(/server_name\s+([^;]+);/g)].map((x) => x[1].trim()).join(' ');
      const root = /root\s+([^;]+);/.exec(body);
      const proxy = /proxy_pass\s+([^;]+);/.exec(body);
      const ssl = /ssl_certificate\s/.test(body);
      if (listen.length || serverName) blocks.push({ listen: listen.slice(0, 6), serverName: serverName || '(default)', root: root ? root[1].trim() : null, proxy: proxy ? proxy[1].trim() : null, ssl });
      if (blocks.length >= 10) break;
    }
    s.blocks = blocks;
  }

  const certList = [];
  for (const line of certs.stdout.split('\n')) {
    const m = /^\/etc\/letsencrypt\/live\/(\S+)\s+notAfter=(.+)$/.exec(line.trim());
    if (m) {
      const exp = new Date(m[2].trim());
      certList.push({ domain: m[1], expiry: exp.toISOString(), daysLeft: Math.round((exp - Date.now()) / 86400000) });
    }
  }

  return {
    ts: Date.now(),
    nginx: {
      installed: !/not found/i.test(nginxVer.stdout + nginxVer.stderr),
      version: (nginxVer.stdout + nginxVer.stderr).trim().replace('nginx version: ', ''),
      active: /active/.test(nginxActive.stdout),
      testOk: /syntax is ok/i.test(nginxTest.stdout),
      testOutput: nginxTest.stdout.trim() + nginxTest.stderr.trim(),
      sites: sitesOut,
    },
    apache: { installed: /Apache/.test(apacheV.stdout), active: /active/.test(apacheActive.stdout), info: apacheV.stdout.trim() },
    certs: certList,
    certbotInstalled: certbin.stdout.trim() !== 'none' && !!certbin.stdout.trim(),
  };
}

/* =============== temps (cached 60s) =============== */
let tempCache = { ts: 0, data: [] };
async function getTemps() {
  const now = Date.now();
  if (now - tempCache.ts < 60000) return tempCache.data;
  const agent = getAgent();
  const r = await agent.run("for z in /sys/class/thermal/thermal_zone*; do [ -f \"$z/temp\" ] && echo \"$(cat \"$z/type\" 2>/dev/null) $(cat \"$z/temp\" 2>/dev/null)\"; done 2>/dev/null", { timeout: 5000 });
  const out = [];
  for (const line of r.stdout.split('\n')) {
    const p = line.trim().split(/\s+/);
    if (p.length === 2) {
      const t = parseInt10(p[1]);
      if (t > 0) out.push({ name: p[0], celsius: t > 1000 ? +(t / 1000).toFixed(1) : t });
    }
  }
  tempCache = { ts: now, data: out };
  return out;
}

/* =============== docker =============== */
async function getDocker(force) {
  const agent = getAgent();
  const det = await agent.run('command -v docker >/dev/null 2>&1 && echo HASDOCKER || echo NODOCKER; systemctl is-active docker 2>/dev/null', { timeout: 10000 });
  if (!/HASDOCKER/.test(det.stdout)) return { ts: Date.now(), installed: false };
  const active = /active/.test(det.stdout);
  const out = { ts: Date.now(), installed: true, active, version: null, containers: [], images: [], stats: [] };
  if (!active) return out;
  const [ver, ps, imgs, stats] = await Promise.all([
    agent.run('docker version --format "{{.Server.Version}}" 2>/dev/null', { timeout: 10000 }),
    agent.run("docker ps -a --format '{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}' 2>/dev/null | head -100", { timeout: 15000 }),
    agent.run("docker images --format '{{.Repository}}:{{.Tag}}|{{.Size}}|{{.CreatedSince}}' 2>/dev/null | head -60", { timeout: 15000 }),
    agent.run("docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}' 2>/dev/null | head -60", { timeout: 20000 }),
  ]);
  out.version = ver.stdout.trim() || null;
  for (const line of ps.stdout.split('\n')) {
    const p = line.split('|');
    if (p.length >= 4) out.containers.push({ id: p[0], name: p[1], image: p[2], status: p[3], ports: p[4] || '', running: /Up/i.test(p[3]) });
  }
  for (const line of imgs.stdout.split('\n')) {
    const p = line.split('|');
    if (p.length >= 3) out.images.push({ repo: p[0], size: p[1], created: p[2] });
  }
  for (const line of stats.stdout.split('\n')) {
    const p = line.split('|');
    if (p.length >= 4) out.stats.push({ name: p[0], cpu: p[1], mem: p[2], memPct: p[3], net: p[4] || '' });
  }
  return out;
}

/* =============== cron / timers =============== */
function fmtTimerDur(us) {
  if (!us || us <= 0) return '—';
  const s = Math.floor(us / 1e6);
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (d) return `${d}天${h}小时`;
  if (h) return `${h}小时${m}分`;
  if (m) return `${m}分${sec}秒`;
  return `${sec}秒`;
}

function parseTimers(raw) {
  const fmtTs = (us) => (us ? new Date(us / 1000).toLocaleString('zh-CN', { hour12: false }) : '从未');
  try {
    const arr = JSON.parse(String(raw || '').trim() || '[]');
    if (!Array.isArray(arr)) throw new Error('not array');
    const nowUs = Date.now() * 1000;
    return arr.map((t) => ({
      next: fmtTs(t.next), left: fmtTimerDur((t.next || 0) - nowUs),
      unit: t.unit || '', activates: t.activates || '',
      last: fmtTs(t.last), passed: fmtTimerDur(t.passed || 0),
    }));
  } catch (_) {
    /* fallback: text layout is  Day YYYY-MM-DD HH:MM:SS TZ left... last... ago <timer> <service> */
    return String(raw || '').split('\n').filter((l) => l.trim() && !/^\[/.test(l.trim())).map((l) => {
      const p = l.trim().split(/\s+/);
      const n = p.length;
      return {
        next: p.slice(0, 4).join(' '), left: p.slice(4, 6).join(' '),
        unit: n >= 2 ? p[n - 2] : '', activates: n >= 1 ? p[n - 1] : '',
        last: '—', passed: '—',
      };
    });
  }
}

async function getCron() {
  const agent = getAgent();
  const r = await agent.run(`
echo '##ROOT##'
crontab -l 2>/dev/null | grep -v '^#' | grep -v '^$' || true
echo '##ETC##'
grep -rHE '^[^#[:space:]]' /etc/crontab /etc/cron.d/* 2>/dev/null | head -80 || true
echo '##FILES##'
ls /etc/cron.d 2>/dev/null || true
echo '##TIMERS##'
systemctl list-timers --all --no-pager --output=json 2>/dev/null || echo '[]'
echo '##DIRS##'
ls /etc/cron.daily /etc/cron.hourly 2>/dev/null | head -30 || true
`, { timeout: 15000 });
  const sec = (name) => {
    const m = new RegExp('##' + name + '##\\n([\\s\\S]*?)(?=\\n##|$)').exec(r.stdout);
    return m ? m[1].trim() : '';
  };
  /* systemd 以微秒时间戳返回；文本解析列不稳定，统一用 JSON */
  const timers = parseTimers(sec('TIMERS'));
  const cronFiles = sec('FILES').split('\n').filter(Boolean);
  const dailyHourly = sec('DIRS').split('\n').filter(Boolean);
  return {
    ts: Date.now(),
    rootJobs: sec('ROOT').split('\n').filter(Boolean),
    etcJobs: sec('ETC').split('\n').filter(Boolean),
    cronFiles, timers, dailyHourly,
  };
}

/* =============== one-shot full snapshot (dashboard initial load) =============== */
async function snapshot() {
  const [metrics, disks, info, procs, temps] = await Promise.all([
    getMetrics(), getDisks(), getSystemInfo(), getProcesses({ limit: 12 }), getTemps(),
  ]);
  return { metrics, disks, info, topProcesses: procs.list, temps };
}

module.exports = {
  getMetrics, getDisks, getProcesses, getSystemInfo, getNetworkDetail, getMemoryDetail,
  getServices, getSecurity, getDatabases, getWebServers, getTemps, getDocker, getCron, snapshot,
  SECURITY_SYSCTL_KEYS,
};
