'use strict';
/*
 * Windows collectors — same JSON shapes as collectors.js for the panel UI.
 */
const os = require('os');
const { getAgent } = require('./agent');
const { psJson, parsePsJson } = require('./hostplatform');
const { parseInt10, toFloat } = require('./util');

let prevCpuPct = null;
let prevNet = null;
let prevDisk = null;
let prevTs = 0;

const SECURITY_SYSCTL_KEYS = [];

let diskCache = { ts: 0, data: null };
let procCache = { ts: 0, data: null };
let infoCache = { ts: 0, data: null };
let netCache = { ts: 0, data: null };
let svcCache = { ts: 0, data: null };
let tempCache = { ts: 0, data: [] };

async function getMetrics() {
  const now = Date.now();
  const dt = prevTs ? (now - prevTs) / 1000 : 0;
  prevTs = now;

  const { data, stdout } = await psJson(`
$cpu = (Get-Counter '\\Processor(_Total)\\% Processor Time' -ErrorAction SilentlyContinue).CounterSamples.CookedValue
$os = Get-CimInstance Win32_OperatingSystem
$memTotal = [uint64]$os.TotalVisibleMemorySize * 1024
$memAvail = [uint64]$os.FreePhysicalMemory * 1024
$uptime = (Get-Date) - $os.LastBootUpTime
$procs = Get-Process -ErrorAction SilentlyContinue
$net = Get-NetAdapterStatistics -ErrorAction SilentlyContinue | ForEach-Object {
  @{ n = $_.Name; rx = [uint64]$_.ReceivedBytes; tx = [uint64]$_.SentBytes }
}
$disk = Get-CimInstance Win32_PerfFormattedData_PerfDisk_PhysicalDisk -Filter "Name='_Total'" -ErrorAction SilentlyContinue
@{
  cpuPct = [math]::Round($cpu, 1)
  memTotal = $memTotal
  memAvail = $memAvail
  uptimeSec = [math]::Round($uptime.TotalSeconds, 0)
  running = @($procs | Where-Object { $_.Responding }).Count
  totalProcs = $procs.Count
  net = @($net)
  diskRead = [uint64]($disk.DiskReadBytesPersec)
  diskWrite = [uint64]($disk.DiskWriteBytesPersec)
} | ConvertTo-Json -Compress -Depth 4
`, { timeout: 12000 });

  const m = data || parsePsJson(stdout) || {};
  const cpuPct = m.cpuPct != null ? +m.cpuPct : (prevCpuPct ?? 0);
  prevCpuPct = cpuPct;

  const memTotal = m.memTotal || 0;
  const memAvail = m.memAvail || 0;
  const memUsed = memTotal > 0 ? memTotal - memAvail : 0;
  const usedPct = memTotal > 0 ? +((memUsed / memTotal) * 100).toFixed(1) : 0;

  const netIfaces = {};
  const netCur = {};
  for (const row of m.net || []) {
    netCur[row.n] = { rx: row.rx || 0, tx: row.tx || 0 };
  }
  if (prevNet && dt > 0) {
    for (const [iface, cur] of Object.entries(netCur)) {
      const prev = prevNet[iface] || { rx: 0, tx: 0 };
      netIfaces[iface] = {
        rxBps: Math.max(0, Math.round((cur.rx - prev.rx) / dt)),
        txBps: Math.max(0, Math.round((cur.tx - prev.tx) / dt)),
      };
    }
  } else {
    for (const k of Object.keys(netCur)) netIfaces[k] = { rxBps: 0, txBps: 0 };
  }
  prevNet = netCur;

  let readBps = 0;
  let writeBps = 0;
  if (prevDisk && dt > 0) {
    readBps = Math.max(0, Math.round(((m.diskRead || 0) - prevDisk.read) / dt));
    writeBps = Math.max(0, Math.round(((m.diskWrite || 0) - prevDisk.write) / dt));
  }
  prevDisk = { read: m.diskRead || 0, write: m.diskWrite || 0 };

  const cores = os.cpus().length || 1;
  return {
    ts: now,
    cpu: {
      totalPct: cpuPct,
      cores: Array(cores).fill(cpuPct),
      loadAvg: [0, 0, 0],
      uptimeSec: m.uptimeSec || 0,
      procs: { running: m.running || 0, total: m.totalProcs || 0 },
      times: { user: 0, system: 0, idle: 0, iowait: 0, steal: 0 },
      coresCount: cores,
    },
    memory: {
      total: memTotal,
      available: memAvail,
      actualUsedPct: usedPct,
      usedPct,
      swapTotal: 0,
      swapPct: 0,
    },
    net: { ifaces: netIfaces },
    diskio: { readBps, writeBps },
  };
}

async function getDisks(force) {
  const now = Date.now();
  if (!force && diskCache.data && now - diskCache.ts < 5000) return diskCache.data;
  const { data } = await psJson(`
Get-Volume | Where-Object { $_.DriveLetter } | ForEach-Object {
  $total = [uint64]($_.Size)
  $free = [uint64]($_.SizeRemaining)
  $used = $total - $free
  $pct = if ($total -gt 0) { [math]::Round(100 * $used / $total, 1) } else { 0 }
  @{
    dev = $_.DriveLetter + ':'
    total = $total
    used = $used
    avail = $free
    pct = $pct
    mount = $_.DriveLetter + ':\\'
    fstype = $_.FileSystemType
  }
} | ConvertTo-Json -Compress
`, { timeout: 15000 });
  const rows = Array.isArray(data) ? data : (data ? [data] : []);
  const mounts = rows.map((r) => ({
    dev: r.dev,
    total: r.total || 0,
    used: r.used || 0,
    avail: r.avail || 0,
    pct: r.pct || 0,
    mount: r.mount,
    fstype: r.fstype || 'NTFS',
  }));
  const root = mounts.find((x) => /^C:/i.test(x.mount)) || mounts[0];
  const out = { ts: now, mounts, blocks: [], overallPct: root ? root.pct : null };
  diskCache = { ts: now, data: out };
  return out;
}

async function getProcesses(opts = {}) {
  const now = Date.now();
  const limit = opts.limit || 150;
  if (!opts.force && procCache.data && now - procCache.ts < 2000) return procCache.data;
  const { data } = await psJson(`
Get-Process | Sort-Object CPU -Descending | Select-Object -First ${Math.min(limit, 500)} Id,Parent,ProcessName,CPU,WorkingSet,VirtualMemorySize,Responding,StartTime | ForEach-Object {
  @{
    pid = $_.Id
    ppid = $_.Parent.Id
    user = $env:USERNAME
    cpu = [math]::Round($_.CPU, 2)
    mem = 0
    rss = [uint64]$_.WorkingSet64
    vsz = [uint64]$_.VirtualMemorySize64
    stat = if ($_.Responding) { 'R' } else { 'T' }
    etime = ''
    args = $_.ProcessName
  }
} | ConvertTo-Json -Compress
`, { timeout: 20000 });
  const list = (Array.isArray(data) ? data : (data ? [data] : [])).map((p) => ({
    pid: p.pid,
    ppid: p.ppid || 0,
    user: p.user || 'SYSTEM',
    cpu: toFloat(p.cpu),
    mem: toFloat(p.mem),
    rss: parseInt10(p.rss),
    vsz: parseInt10(p.vsz),
    stat: p.stat || 'R',
    etime: p.etime || '',
    args: String(p.args || '').slice(0, 300),
  }));
  const statCounts = { running: 0, sleeping: 0, zombie: 0, stopped: 0, other: 0 };
  for (const p of list) {
    if (p.stat === 'R') statCounts.running++;
    else if (p.stat === 'T') statCounts.stopped++;
    else statCounts.sleeping++;
  }
  const out = { ts: now, total: list.length, statCounts, list };
  procCache = { ts: now, data: out };
  return out;
}

let extIpCache = { ts: 0, ip: null };

async function getSystemInfo(force) {
  const now = Date.now();
  if (!force && infoCache.data && now - infoCache.ts < 30000) return infoCache.data;
  const agent = getAgent();
  const { data } = await psJson(`
$cs = Get-CimInstance Win32_ComputerSystem
$os = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
@{
  hostname = $env:COMPUTERNAME
  osName = $os.Caption
  osVersion = $os.Version
  kernel = $os.Version
  cpuModel = $cpu.Name
  cores = ($cpu | Measure-Object).Count
  memTotal = [uint64]$os.TotalVisibleMemorySize * 1024
} | ConvertTo-Json -Compress
`, { timeout: 12000 });
  if (!extIpCache.ip || now - extIpCache.ts > 600000) {
    const r = await agent.run('powershell -NoProfile -Command "(Invoke-WebRequest -UseBasicParsing -Uri https://api.ipify.org -TimeoutSec 6).Content.Trim()"', { timeout: 10000 });
    const ip = (r.stdout || '').trim().split(/\s+/)[0];
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) extIpCache = { ts: now, ip };
    else extIpCache.ts = now;
  }
  const out = {
    hostname: data?.hostname || os.hostname(),
    osName: data?.osName || 'Windows',
    osVersion: data?.osVersion || os.release(),
    kernel: data?.kernel || os.release(),
    arch: process.arch,
    cpuModel: data?.cpuModel || 'unknown',
    cores: data?.cores || os.cpus().length,
    memTotal: data?.memTotal || 0,
    bootTimeApprox: null,
    publicIp: extIpCache.ip,
    panelMode: agent.mode,
    nodeVersion: process.version,
    ts: now,
  };
  infoCache = { ts: now, data: out };
  return out;
}

async function getNetworkDetail(force) {
  const now = Date.now();
  if (!force && netCache.data && now - netCache.ts < 3000) return netCache.data;
  const { data } = await psJson(`
$ifaces = Get-NetIPConfiguration | Where-Object { $_.NetAdapter.Status -eq 'Up' } | ForEach-Object {
  @{
    name = $_.InterfaceAlias
    state = $_.NetAdapter.Status
    operstate = $_.NetAdapter.Status
    mtu = $_.NetAdapter.MtuSize
    mac = $_.NetAdapter.MacAddress
    ipv4 = @($_.IPv4Address | ForEach-Object { $_.IPAddress + '/' + $_.PrefixLength })
    ipv6 = @($_.IPv6Address | Where-Object { $_.PrefixOrigin -ne 'WellKnown' } | ForEach-Object { $_.IPAddress })
  }
}
$routes = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Select-Object -First 1
$listen = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Select-Object -First 80 LocalAddress,LocalPort,OwningProcess
$estab = Get-NetTCPConnection -State Established -ErrorAction SilentlyContinue | Select-Object -First 80 LocalAddress,LocalPort,RemoteAddress,RemotePort,OwningProcess
@{
  interfaces = @($ifaces)
  defaultRoute = if ($routes) { @{ gw = $routes.NextHop; dev = $routes.InterfaceAlias; proto = 'static' } } else { $null }
  listening = @($listen | ForEach-Object { @{ local = ($_.LocalAddress + ':' + $_.LocalPort); process = ('pid:' + $_.OwningProcess) } })
  established = @($estab | ForEach-Object { @{ local = ($_.LocalAddress + ':' + $_.LocalPort); peer = ($_.RemoteAddress + ':' + $_.RemotePort); process = ('pid:' + $_.OwningProcess) } })
} | ConvertTo-Json -Compress -Depth 5
`, { timeout: 25000 });
  const out = {
    ts: now,
    interfaces: data?.interfaces || [],
    defaultRoute: data?.defaultRoute || null,
    dns: '',
    listening: data?.listening || [],
    established: data?.established || [],
    summary: '',
  };
  netCache = { ts: now, data: out };
  return out;
}

async function getMemoryDetail() {
  const procs = await getProcesses({ limit: 400 });
  const byRss = procs.list.slice().sort((a, b) => b.rss - a.rss).slice(0, 15);
  const metrics = await getMetrics();
  return { metrics, swappiness: 0, topRss: byRss, procTotal: procs.total };
}

async function getServices(force) {
  const now = Date.now();
  if (!force && svcCache.data && now - svcCache.ts < 5000) return svcCache.data;
  const { data } = await psJson(`
Get-Service | Select-Object Name,Status,DisplayName,StartType | ForEach-Object {
  @{
    unit = $_.Name
    load = ''
    active = $_.Status.ToString()
    sub = ''
    desc = $_.DisplayName
    enabled = $_.StartType.ToString()
  }
} | ConvertTo-Json -Compress
`, { timeout: 20000 });
  const list = (Array.isArray(data) ? data : (data ? [data] : [])).map((s) => ({
    unit: s.unit + '.service',
    load: s.load || '',
    active: /Running/i.test(s.active) ? 'active' : 'inactive',
    sub: s.sub || '',
    description: s.desc || s.unit,
    enabled: /Automatic/i.test(s.enabled) ? 'enabled' : 'disabled',
  }));
  const out = {
    ts: now,
    list,
    counts: {
      running: list.filter((x) => x.active === 'active').length,
      failed: list.filter((x) => /failed/i.test(x.active)).length,
      total: list.length,
    },
  };
  svcCache = { ts: now, data: out };
  return out;
}

async function getSecurity() {
  const agent = getAgent();
  const [fw, ssh, users, failed] = await Promise.all([
    agent.run('netsh advfirewall show allprofiles state', { timeout: 10000 }),
    agent.run('powershell -NoProfile -Command "Get-ItemProperty -Path HKLM:\\SOFTWARE\\OpenSSH -ErrorAction SilentlyContinue | Out-String"', { timeout: 8000 }),
    psJson('Get-LocalUser | Where-Object { $_.Enabled } | Select-Object Name,SID | ConvertTo-Json -Compress', { timeout: 10000 }),
    psJson('Get-EventLog -LogName Security -Newest 15 -ErrorAction SilentlyContinue | Where-Object { $_.EventID -eq 4625 } | Select-Object TimeGenerated,Message | ConvertTo-Json -Compress', { timeout: 12000 }),
  ]);
  const fwActive = /STATE\s+ON/i.test(fw.stdout);
  const userList = (Array.isArray(users.data) ? users.data : (users.data ? [users.data] : [])).map((u) => ({
    name: u.Name,
    uid: u.SID,
    home: '',
    shell: '',
  }));
  return {
    ufw: { installed: true, active: fwActive, rules: [], raw: fw.stdout.trim().split('\n').slice(0, 15) },
    fail2ban: { active: false, status: 'Windows 无 fail2ban', banned: '' },
    sshd: { port: '22', note: ssh.stdout.trim().slice(0, 200) },
    users: userList,
    sudoers: [],
    recentFailed: failed.data ? JSON.stringify(failed.data).split('\n').slice(0, 20) : [],
    recentLogins: [],
    sysctl: {},
    apparmor: 'N/A on Windows',
  };
}

async function getDatabases() {
  const agent = getAgent();
  const engines = [];
  const check = async (id, name, svc, cmd) => {
    const r = await agent.run(`where ${cmd} 2>nul`, { timeout: 5000 });
    if (!String(r.stdout).trim()) return;
    const st = await agent.run(`${cmd} --version 2>nul`, { timeout: 8000 }).catch(() => ({ stdout: '' }));
    let active = false;
    if (svc) {
      const s = await agent.run(`sc query ${svc} 2>nul`, { timeout: 5000 });
      active = /RUNNING/i.test(s.stdout);
    }
    engines.push({
      id, name, installed: true, service: svc, active,
      version: (st.stdout || '').trim().split('\n')[0] || null,
      databases: [], stats: null,
    });
  };
  await Promise.all([
    check('mysql', 'MySQL', 'MySQL', 'mysql'),
    (async () => {
      const svcs = await agent.run('powershell -NoProfile -Command "(Get-Service postgresql* -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Name)"', { timeout: 8000 });
      const svc = String(svcs.stdout || '').trim() || 'postgresql-x64-16';
      await check('postgres', 'PostgreSQL', svc, 'psql');
    })(),
    check('redis', 'Redis', 'Redis', 'redis-cli'),
    check('mongo', 'MongoDB', 'MongoDB', 'mongosh'),
  ]);
  return { ts: Date.now(), engines, bins: {} };
}

async function getWebServers() {
  const agent = getAgent();
  const iis = await agent.run('powershell -NoProfile -Command "Get-Service W3SVC -ErrorAction SilentlyContinue | Select-Object Status"', { timeout: 8000 });
  const nginx = await agent.run('nginx -v 2>&1', { timeout: 5000 });
  return {
    ts: Date.now(),
    nginx: {
      installed: !/not found/i.test(nginx.stdout + nginx.stderr),
      version: (nginx.stdout + nginx.stderr).trim(),
      active: false,
      testOk: true,
      testOutput: '',
      sites: [],
    },
    apache: { installed: false, active: false, info: '' },
    certs: [],
    certbotInstalled: false,
    iis: { installed: /Status/i.test(iis.stdout), active: /Running/i.test(iis.stdout), info: iis.stdout.trim() },
  };
}

async function getTemps() {
  const now = Date.now();
  if (now - tempCache.ts < 60000) return tempCache.data;
  const { data } = await psJson(`
Get-CimInstance MSAcpi_ThermalZoneTemperature -Namespace root/wmi -ErrorAction SilentlyContinue | ForEach-Object {
  @{ name = $_.InstanceName; celsius = [math]::Round(($_.CurrentTemperature / 10) - 273.15, 1) }
} | ConvertTo-Json -Compress
`, { timeout: 8000 });
  const out = Array.isArray(data) ? data : (data ? [data] : []);
  tempCache = { ts: now, data: out };
  return out;
}

async function getDocker(force) {
  const agent = getAgent();
  const det = await agent.run('docker version 2>nul', { timeout: 10000 });
  if (det.code !== 0 && !/Version/i.test(det.stdout)) {
    return { ts: Date.now(), installed: false };
  }
  const active = true;
  const out = { ts: Date.now(), installed: true, active, version: null, containers: [], images: [], stats: [] };
  const [ver, ps, imgs] = await Promise.all([
    agent.run('docker version --format "{{.Server.Version}}" 2>nul', { timeout: 10000 }),
    agent.run('docker ps -a --format "{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}" 2>nul', { timeout: 15000 }),
    agent.run('docker images --format "{{.Repository}}:{{.Tag}}|{{.Size}}|{{.CreatedSince}}" 2>nul', { timeout: 15000 }),
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
  return out;
}

async function getCron() {
  const { data } = await psJson(`
$tasks = Get-ScheduledTask -ErrorAction SilentlyContinue | Select-Object -First 80 TaskName,TaskPath,State | ForEach-Object {
  $_.TaskPath + $_.TaskName + ' [' + $_.State + ']'
}
@{
  rootJobs = @()
  etcJobs = @($tasks)
  cronFiles = @()
  timers = @()
  dailyHourly = @()
} | ConvertTo-Json -Compress
`, { timeout: 20000 });
  return {
    ts: Date.now(),
    rootJobs: data?.rootJobs || [],
    etcJobs: data?.etcJobs || [],
    cronFiles: data?.cronFiles || [],
    timers: data?.timers || [],
    dailyHourly: data?.dailyHourly || [],
  };
}

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
