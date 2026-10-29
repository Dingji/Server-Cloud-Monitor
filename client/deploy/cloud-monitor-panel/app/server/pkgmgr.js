'use strict';
if (process.platform === 'win32') {
  module.exports = require('./pkgmgr-win');
  return;
}
/*
 * Package manager (APT/dpkg) — search / detail / install / remove / upgrade.
 *
 * Safety model (this is a root-level feature):
 *  - every package name is validated against Debian policy charset before use
 *  - "simulate" preview (-s) is available for every mutating action
 *  - tier-1 critical packages cannot be removed without an explicit force flag
 *  - tier-2 protected packages (the panel's own dependencies) require force too
 *  - one apt operation at a time (mutex), long timeouts, full log to disk
 */
const fs = require('fs');
const path = require('path');
const { getAgent } = require('./agent');
const { audit } = require('./util');
const distro = require('./distro');

/* Only Debian-family has a real "source list" concept; others report unsupported. */
async function requireDebianFamily() {
  const env = await distro.detect();
  if (env.family !== 'debian') {
    throw Object.assign(new Error(
      `当前系统为 ${env.name}（包管理器: ${env.pm || '未检测到'}），软件源切换功能仅支持 Debian/Ubuntu 系列。`
      + '其它发行版请使用系统自带的包管理工具。'), { status: 400 });
  }
  return env;
}

const PKG_RE = /^[a-z0-9][a-z0-9+._-]{0,62}(:[a-z0-9-]+)?$/;
const MAX_NAMES = 50;
const { LOG_DIR, APT_BACKUP_DIR, tmpDir } = require('./paths');
const APT_ENV = 'DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a APT_LISTCHANGES_FRONTEND=none ';

/* tier 1: 卸载后可能导致系统无法启动 / 无法远程管理的包 */
const CRITICAL_PKGS = new Set([
  'apt', 'apt-utils', 'aptitude', 'dpkg', 'dpkg-dev', 'base-files', 'base-passwd', 'bash', 'dash', 'sh',
  'coreutils', 'debianutils', 'diffutils', 'findutils', 'grep', 'gzip', 'sed', 'tar', 'bzip2', 'xz-utils',
  'util-linux', 'mount', 'login', 'passwd', 'adduser', 'adduser', 'debconf', 'ncurses-base', 'ncurses-bin',
  'perl-base', 'python3', 'python3-minimal', 'libc6', 'libgcc-s1', 'libstdc++6', 'zlib1g', 'libssl3t64',
  'systemd', 'systemd-sysv', 'libsystemd0', 'udev', 'dbus', 'dbus-bin', 'sudo', 'polkitd',
  'openssh-server', 'openssh-client', 'netplan.io', 'network-manager', 'iptables', 'nftables', 'apparmor',
  'ca-certificates', 'gnupg', 'apt-transport-https', 'ubuntu-keyring', 'cloud-init', 'cloud-guest-utils',
  'cron', 'rsyslog', 'chrony', 'systemd-timesyncd', 'linux-base', 'ubuntu-minimal', 'ubuntu-standard',
  'init', 'init-system-helpers', 'systemd-resolved', 'network-manager-ppp',
]);
/* tier 2: 面板自身依赖，卸载会直接导致面板功能失效 */
const PROTECTED_PKGS = new Set([
  'nginx', 'nginx-core', 'nginx-common', 'docker.io', 'containerd', 'runc',
  'postgresql', 'postgresql-client', 'postgresql-common', 'mysql-server', 'mysql-client',
  'redis-server', 'nodejs', 'python3-pip', 'python3-venv', 'openssl', 'curl', 'wget', 'git', 'vim', 'htop',
]);

function bad(msg) { const e = new Error(msg); e.status = 400; throw e; }
function ok(output, extra) { return Object.assign({ ok: true, output: output == null ? '' : String(output) }, extra || {}); }

async function run(cmd, opts = {}) {
  const r = await getAgent().run(cmd, { timeout: opts.timeout || 60000, maxBuffer: opts.maxBuffer || 32 * 1024 * 1024 });
  return { code: r.code, stdout: r.stdout || '', stderr: r.stderr || '', timedOut: !!r.timedOut };
}
/* 只读命令：非零退出码不算失败（例如 grep 无匹配 / apt 提示已最新） */
async function ro(cmd, timeout) {
  const r = await run(cmd, { timeout: timeout || 60000 });
  return r.stdout;
}
async function sh(cmd, timeout) { return ro(cmd, timeout).catch(() => ''); }

function checkNames(names, max) {
  const lim = Math.max(1, parseInt(max, 10) || MAX_NAMES);
  const list = (Array.isArray(names) ? names : String(names == null ? '' : names).split(/[\s,]+/))
    .map((x) => String(x).trim()).filter(Boolean);
  if (!list.length) bad('请至少指定一个包名');
  if (list.length > lim) bad(`一次最多操作 ${lim} 个包（当前 ${list.length} 个）`);
  for (const n of list) {
    if (!PKG_RE.test(n)) bad('非法包名: ' + n.slice(0, 60));
  }
  return list;
}

function classify(n) {
  const base = String(n).split(':')[0];
  if (CRITICAL_PKGS.has(base) || /^linux-(image|headers|modules|generic)/.test(base)) return 'critical';
  if (PROTECTED_PKGS.has(base)) return 'protected';
  return null;
}
function guardRemovable(list, force) {
  if (force) return [];
  const blocked = list.map((n) => ({ name: n, level: classify(n) })).filter((x) => x.level);
  if (blocked.length) {
    const detail = blocked.map((b) => b.name + (b.level === 'critical' ? '（系统关键组件）' : '（面板依赖）')).join('、');
    const e = new Error('以下包属于受保护范围，已阻止操作：' + detail +
      '\n卸载它们可能导致系统无法启动、SSH 断开或面板失效。\n如确有必要，请在请求中显式传入 force=true 并自行承担风险。');
    e.status = 400; e.blocked = blocked; throw e;
  }
  return [];
}

/* ---------- apt 操作互斥（dpkg 锁只挡并发 apt，挡不住本面板的重复提交） ---------- */
let chain = Promise.resolve();
let busyLabel = '';
function withLock(label, fn) {
  const p = chain.then(async () => {
    busyLabel = label;
    try { return await fn(); } finally { busyLabel = ''; }
  });
  chain = p.then(() => {}, () => {});
  return p;
}
function lockState() { return { busy: !!busyLabel, label: busyLabel }; }

/* ============ 任务（带实时进度） ============
 * apt 是"跑完才知道结果"的长任务（安装可能长达数分钟），过去前端只能
 * 干等一个 /pkg/action 的 HTTP 请求，用户看不到任何进展。
 * 这里把变更封装成 job：逐行消费 apt 输出 → 解析阶段与百分比 →
 * 前端轮询 /pkg/job 渲染进度条与实时日志。
 *   - 批量任务按 CHUNK_SIZE 拆成多次 apt 调用顺序执行，整体进度按批次均分
 *   - 日志实时落盘（与旧版 pkg-*.log 兼容），面板重启也不丢
 *   - 内存保留最近 JOB_KEEP 个任务，页面刷新后可接上进度
 */
const CHUNK_SIZE = 50;        /* 单次 apt 调用最多携带的包名数（批量分批） */
const MAX_JOB_NAMES = 500;    /* 单个任务最多包含的包名总数 */
const JOB_KEEP = 24;          /* 内存保留的任务数 */
const JOB_LINES_MAX = 400;    /* 单个任务在内存保留的输出行数 */

const jobs = new Map();
const jobOrder = [];
let jobCounter = 0;
let lastJobId = null;

const PHASE_LABEL = {
  queued: '排队中', resolve: '解析依赖', plan: '制定变更方案', download: '下载软件包',
  unpack: '解包', configure: '安装配置', remove: '移除软件包',
  update: '更新软件源索引', finish: '收尾', done: '完成', failed: '失败',
};
/* 各操作在前端展示的阶段条（按顺序，当前阶段高亮、之前阶段打勾） */
const ACTION_PHASES = {
  install: ['resolve', 'plan', 'download', 'unpack', 'configure', 'finish'],
  upgrade: ['resolve', 'plan', 'download', 'unpack', 'configure', 'finish'],
  'full-upgrade': ['resolve', 'plan', 'download', 'unpack', 'configure', 'finish'],
  remove: ['resolve', 'plan', 'remove', 'finish'],
  purge: ['resolve', 'plan', 'remove', 'finish'],
  autoremove: ['resolve', 'plan', 'remove', 'finish'],
  update: ['update', 'finish'],
  clean: ['finish'],
  hold: ['finish'],
  unhold: ['finish'],
};

function newJob(meta) {
  const id = 'pkg' + Date.now().toString(36) + '-' + (++jobCounter);
  const job = Object.assign({
    id, state: 'queued', percent: 0, phase: 'queued', phase_label: PHASE_LABEL.queued,
    detail: '', action: '', names: [], force: false, cmds: [], chunks: 1, chunk: 0,
    timeout: 900000, label: '', lines: [], startedAt: Date.now(), endedAt: null,
    seconds: 0, log: '', error: '', tail: '', output: '', result: null,
  }, meta);
  jobs.set(id, job);
  jobOrder.push(id);
  while (jobOrder.length > JOB_KEEP) {
    const drop = jobOrder.shift();
    if (drop !== lastJobId) jobs.delete(drop);
  }
  lastJobId = id;
  return job;
}

function jobView(job) {
  if (!job) return null;
  return {
    id: job.id, action: job.action, names: job.names, state: job.state,
    percent: Math.round(job.percent), phase: job.phase, phase_label: job.phase_label,
    detail: job.detail, chunks: job.chunks, chunk: job.chunk,
    startedAt: job.startedAt, endedAt: job.endedAt, seconds: job.seconds,
    log: job.log, error: job.error, tail: job.tail, output: job.output,
    result: job.result, active: job.state === 'queued' || job.state === 'running',
    steps: ACTION_PHASES[job.action] || ['finish'],
    lines: job.lines.slice(-160),
  };
}

function finishJob(job) {
  if (job._resolve) { try { job._resolve(jobView(job)); } catch (_) { } job._resolve = null; }
}
/* 等待任务结束（act() 用它把任务语义还原成同步接口） */
function jobPromise(job) {
  if (!job) return Promise.resolve(null);
  if (job.state === 'done' || job.state === 'failed') return Promise.resolve(jobView(job));
  if (!job._donePromise) job._donePromise = new Promise((res) => { job._resolve = res; });
  return job._donePromise;
}

/* 查询任务状态：不带 id 时返回最近一个任务（用于页面刷新后接上进度） */
function jobState(id) {
  if (id == null || id === '') return jobView(lastJobId ? jobs.get(lastJobId) : null);
  const job = jobs.get(String(id));
  if (!job) { const e = new Error('任务不存在或已被清理'); e.status = 404; throw e; }
  return jobView(job);
}

/* ---------- apt 输出解析：把逐行日志换算成阶段 + 百分比 ---------- */
function toBytes(num, unit) {
  const n = parseFloat(String(num).replace(/,/g, '')) || 0;
  const mul = { B: 1, kB: 1000, MB: 1e6, GB: 1e9, TB: 1e12 }[unit] || 1;
  return n * mul;
}
function fmtBytes(b) {
  const n = Number(b) || 0;
  if (n < 1000) return n + ' B';
  if (n < 1e6) return (n / 1000).toFixed(n < 1e5 ? 1 : 0) + ' kB';
  if (n < 1e9) return (n / 1e6).toFixed(1) + ' MB';
  return (n / 1e9).toFixed(2) + ' GB';
}
function stripAnsi(s) {
  return String(s == null ? '' : s).replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '').replace(/\r/g, '');
}

/* hint: 包名个数，在 apt 未打印变更汇总时作为分母兜底 */
function makeTracker(action, hint) {
  const isInstall = ['install', 'upgrade', 'full-upgrade'].indexOf(action) >= 0;
  const isRemove = ['remove', 'purge', 'autoremove'].indexOf(action) >= 0;
  const isUpdate = action === 'update';
  /* 初始阶段取该操作阶段条的第一项（hold/clean 这类没有解析阶段的操作直接进收尾） */
  const firstPhase = (ACTION_PHASES[action] && ACTION_PHASES[action][0]) || 'resolve';
  const hasResolve = firstPhase === 'resolve';
  const t = {
    action, pct: 0, phase: firstPhase, detail: '',
    dlTotal: 0, dlBytes: 0, dlCount: 0,
    planTotal: 0, removeTotal: 0, unpacked: 0, configured: 0, removed: 0,
    triggers: 0, idx: 0, hint: Math.max(0, hint | 0), warned: false,
  };
  const raise = (v) => { if (v > t.pct) t.pct = Math.min(99, v); };
  const phase = (p) => { t.phase = p; };
  /* 分母未知时用渐近曲线，保证进度条仍在动但不会虚报到 100% */
  const ratio = (count, total, soft) => (total > 0 ? Math.min(1, count / total) : 1 - Math.exp(-count / (soft || 6)));

  t.feed = function (raw) {
    const line = stripAnsi(raw);
    if (!line.trim()) return;
    let m;

    /* apt 自带的百分比（如 apt-get update 打印 "0% [Working]"）最准确，直接采用 */
    m = /^\s*(\d+)%\s*\[/.exec(line);
    if (m) { raise(parseInt(m[1], 10)); return; }

    /* ---- 解析/方案阶段 ---- */
    if (/^(Reading package lists|Building dependency tree|Reading state information|Solving dependencies|Collecting changes|Calculating upgrade)/.test(line)) {
      /* 只有阶段条里有 resolve 的动作才进入该阶段；update 在此处代表索引拉取完成 */
      if (hasResolve) { phase('resolve'); raise(6); t.detail = line.trim().slice(0, 70); }
      else if (isUpdate) { phase('finish'); raise(96); t.detail = '索引更新完成'; }
      return;
    }
    m = /^(\d+) upgraded, (\d+) newly installed, (\d+) to remove/.exec(line);
    if (m) {
      t.planTotal = (parseInt(m[1], 10) || 0) + (parseInt(m[2], 10) || 0);
      t.removeTotal = parseInt(m[3], 10) || 0;
      phase('plan');
      t.detail = `${m[1]} 升级 · ${m[2]} 新装 · ${m[3]} 移除`;
      if (t.planTotal === 0 && t.removeTotal === 0) { t.detail = '无变更需要执行'; raise(95); }
      else raise(isRemove ? 15 : 12);
      return;
    }
    if (/^The following packages will be REMOVED/.test(line)) { phase('plan'); raise(isRemove ? 13 : 11); return; }
    if (/^The following (NEW )?packages will be/.test(line)) { phase('plan'); raise(10); return; }

    /* ---- 索引拉取（apt-get update）与包下载（install 等）共用 Get:/Hit: 前缀 ---- */
    if (/^(Get|Hit|Ign|Err):\d+/.test(line)) {
      if (isUpdate) {
        t.idx++;
        phase('update');
        raise(Math.min(92, 8 + 84 * (1 - Math.exp(-t.idx / 16))));
        t.detail = `已拉取 ${t.idx} 条索引`;
        if (/^Err:\d+/.test(line)) t.warned = true;
        return;
      }
      if (/^Get:\d+/.test(line)) {
        t.dlCount++;
        const sz = /\[([\d.,]+) (kB|MB|B|GB)\]\s*$/.exec(line) || /\(([\d.,]+) (kB|MB|B|GB)\)\s*$/.exec(line);
        if (sz) t.dlBytes += toBytes(sz[1], sz[2]);
        phase('download');
        if (t.dlTotal > 0) raise(15 + 40 * Math.min(1, t.dlBytes / t.dlTotal));
        else raise(Math.min(52, 15 + t.dlCount * 3));
        t.detail = t.dlTotal > 0
          ? `下载 ${fmtBytes(t.dlBytes)} / ${fmtBytes(t.dlTotal)} · ${t.dlCount} 个包`
          : `下载中 · ${t.dlCount} 个包`;
      }
      return;
    }
    m = /^Need to get ([\d.,]+) (kB|MB|B|GB|TB) of archives/.exec(line);
    if (m) {
      t.dlTotal = toBytes(m[1], m[2]);
      phase('download');
      if (t.dlTotal > 0) { raise(15); t.detail = `需下载 ${m[1]} ${m[2]}`; }
      else { raise(55); t.detail = '无需下载（已在本地缓存）'; }
      return;
    }
    m = /^Fetched ([\d.,]+) (kB|MB|B|GB)/.exec(line);
    if (m) {
      raise(55);
      t.detail = (isUpdate ? '已拉取 ' : '已下载 ') + m[1] + ' ' + m[2];
      if (isUpdate) { phase('finish'); raise(95); }
      return;
    }

    /* ---- 解包 / 配置 / 移除 / 触发器 ---- */
    if (/^Unpacking /.test(line)) {
      t.unpacked++; phase('unpack');
      const total = t.planTotal || t.hint;
      raise(55 + 20 * ratio(t.unpacked, total));
      t.detail = `解包 ${t.unpacked}/${total || '?'}`;
      return;
    }
    if (/^Setting up /.test(line)) {
      t.configured++; phase('configure');
      const total = t.planTotal || t.hint;
      raise(75 + 21 * ratio(t.configured, total));
      t.detail = `配置 ${t.configured}/${total || '?'}`;
      return;
    }
    if (/^Removing /.test(line)) {
      t.removed++; phase('remove');
      const total = t.removeTotal || t.hint;
      raise(15 + 73 * ratio(t.removed, total));
      t.detail = `移除 ${t.removed}/${total || '?'}`;
      return;
    }
    if (/^Processing triggers for /.test(line)) {
      t.triggers++; phase('finish'); raise(96);
      t.detail = '处理触发器 ' + (line.split(/\s+/)[3] || '');
      return;
    }
    if (/^(Preparing to unpack|Selecting previously unselected package|Reading database|\(Reading database)/.test(line)) return;

    /* ---- 错误信号（真正的失败由退出码判定，这里只是让状态显示更准确） ---- */
    if (/^(E:|dpkg: (error|dependency problems)|Errors were encountered)/i.test(line)) { t.warned = true; return; }

    /* ---- 兜底：不认识的行也把进度缓慢往前推，避免长时间停在同一点 ---- */
    raise(t.pct + (90 - t.pct) * 0.015);
  };
  return t;
}

/* 任务进度 = (已完成批次 + 当前批次进度) / 批次数，天然单调递增 */
function applyProgress(job, tracker, chunkIdx, chunkCount) {
  job.percent = ((chunkIdx + tracker.pct / 100) / Math.max(1, chunkCount)) * 100;
  job.phase = tracker.phase;
  job.phase_label = PHASE_LABEL[tracker.phase] || '执行中';
  const batch = chunkCount > 1 ? `批次 ${chunkIdx + 1}/${chunkCount}` : '';
  job.detail = tracker.detail ? (batch ? batch + ' · ' + tracker.detail : tracker.detail) : batch;
}

/* 命令是否使用包名参数：upgrade/clean 等忽略 names，不能按包名分批 */
function buildUsesNames(build) {
  try { return build(['cm-probe-a']) !== build(['cm-probe-b']); } catch (_) { return false; }
}
function buildCmds(build, list) {
  if (!list.length) return [build(list)];
  if (!buildUsesNames(build) || list.length <= CHUNK_SIZE) return [build(list)];
  const cmds = [];
  for (let i = 0; i < list.length; i += CHUNK_SIZE) cmds.push(build(list.slice(i, i + CHUNK_SIZE)));
  return cmds;
}

/* ---------- 任务执行主体 ---------- */
async function runJob(job) {
  const t0 = Date.now();
  job.state = 'running';
  let fd = null;
  try {
    fs.mkdirSync(LOG_DIR, { recursive: true });
    job.log = `${LOG_DIR}/pkg-${job.action}-${Date.now()}.log`;
    fd = fs.openSync(job.log, 'w');
  } catch (_) { job.log = ''; fd = null; }
  const w = (s) => { if (fd != null) { try { fs.writeSync(fd, s); } catch (_) {} } };
  let failCode = null, failTimeout = false, exc = null;
  try {
    w(`# Cloud Monitor job ${job.id} — ${job.action} ${job.names.join(' ')}\n`);
    for (let i = 0; i < job.cmds.length; i++) {
      job.chunk = i + 1;
      const tracker = makeTracker(job.action, job.names.length);
      w(`\n$ ${job.cmds[i]}\n`);
      const r = await getAgent().runStream(job.cmds[i], {
        timeout: job.timeout,
        onLine: (line) => {
          job.lines.push(line);
          if (job.lines.length > JOB_LINES_MAX) job.lines.splice(0, job.lines.length - JOB_LINES_MAX);
          w(line + '\n');
          tracker.feed(line);
          applyProgress(job, tracker, i, job.cmds.length);
        },
      });
      w(`\n[exit ${r.code}${r.timedOut ? ' timeout' : ''}]\n`);
      if (r.code !== 0) { failCode = r.code; failTimeout = !!r.timedOut; break; }
    }
  } catch (e) { exc = e; }
  finally { if (fd != null) { try { fs.closeSync(fd); } catch (_) {} } }

  const secs = Math.round((Date.now() - t0) / 1000);
  job.seconds = secs;
  job.endedAt = Date.now();
  const tail = job.lines.slice(-40).join('\n');
  if (exc) {
    job.state = 'failed'; job.phase = 'failed'; job.phase_label = PHASE_LABEL.failed;
    job.error = `${job.action} 执行异常，耗时 ${secs}s：\n${exc.message || exc}` + (job.log ? `\n完整日志：${job.log}` : '');
    job.output = job.error;
    audit('pkg.' + job.action, { action: job.action, names: job.names, force: job.force, secs, ok: false, error: String(exc.message || exc).slice(0, 200) });
  } else if (failCode !== null) {
    job.state = 'failed'; job.phase = 'failed'; job.phase_label = PHASE_LABEL.failed;
    job.error = `${job.action} 执行失败 (exit ${failCode}${failTimeout ? '，命令超时' : ''})，耗时 ${secs}s：\n${tail}`
      + (job.log ? `\n完整日志：${job.log}` : '');
    job.tail = tail; job.output = job.error;
    audit('pkg.' + job.action, { action: job.action, names: job.names, force: job.force, secs, ok: false, exit: failCode });
  } else {
    const tailLines = job.lines.slice(-30).join('\n');
    job.state = 'done'; job.phase = 'done'; job.percent = 100; job.phase_label = PHASE_LABEL.done;
    job.detail = '已完成';
    job.tail = tailLines;
    job.output = `${job.action} 完成，耗时 ${secs}s\n${tailLines}` + (job.log ? `\n\n完整日志：${job.log}` : '');
    job.result = { action: job.action, names: job.names, seconds: secs, log: job.log, tail: tailLines, output: job.output };
    audit('pkg.' + job.action, { action: job.action, names: job.names, force: job.force, secs, ok: true });
  }
  finishJob(job);
}

/* 启动任务：同步完成入参校验（受保护包等错误立即返回给前端），随后异步执行 */
async function startJob(args) {
  const a = args || {};
  const actName = String(a.action || '');
  const build = APT_ACTION_CMD[actName];
  if (!build) bad('不支持的操作: ' + actName);
  const needsNames = ['install', 'remove', 'purge', 'hold', 'unhold'].indexOf(actName) >= 0;
  let list = [];
  if (needsNames) {
    list = checkNames(a.names, MAX_JOB_NAMES);
    if (actName === 'remove' || actName === 'purge') guardRemovable(list, a.force);
  } else if (a.names && (Array.isArray(a.names) ? a.names.length : String(a.names).trim())) {
    /* update/clean/upgrade/autoremove 允许不带包名；空数组等价于"无包名"，
       不能交给 checkNames（它会把空列表当参数错误拒绝） */
    list = checkNames(a.names, MAX_JOB_NAMES);
  }
  /* 包名已通过 PKG_RE 白名单（仅 [a-z0-9+._:-]），不含 shell 元字符，可直接拼接 */
  const cmds = buildCmds(build, list);
  const label = `${actName}:${list.join(',') || '(全部)'}`;
  const job = newJob({
    action: actName, names: list, force: !!a.force, cmds, chunks: cmds.length,
    timeout: APT_TIMEOUT[actName] || 900000, label,
  });
  job._promise = withLock(label, () => runJob(job)).catch((e) => {
    /* runJob 内部已兜底，这里只防未处理的 Promise 拒绝 */
    if (job.state !== 'failed') { job.state = 'failed'; job.error = String(e && e.message || e); finishJob(job); }
  });
  return jobView(job);
}

/* ---------- 解析 ---------- */
function parseInstalled(stdout) {
  const rows = [];
  for (const line of String(stdout).split('\n')) {
    if (!line.trim()) continue;
    const [name, version, size, section, status] = line.split('\t');
    if (!name) continue;
    rows.push({
      name, version: version || '', section: section || 'unknown',
      size_kb: parseInt(size, 10) || 0,
      status: (status || '').trim(),
      installed: (status || '').trim().startsWith('ii'),
    });
  }
  return rows;
}
/* dpkg-query 格式串用普通字符串拼接，避免模板字面量对 ${} 的插值解析 */
const DPKG_FMT = "'" + ['${Package}', '${Version}', '${Installed-Size}', '${Section}', '${db:Status-Abbrev}'].join('\t') + "\\n'";
async function installedMap() {
  const out = await sh('dpkg-query -W -f=' + DPKG_FMT + ' 2>/dev/null', 60000);
  const map = new Map();
  for (const r of parseInstalled(out)) map.set(r.name, r);
  return map;
}
function humanSize(kb) {
  const k = Number(kb) || 0;
  if (k < 1024) return k + ' KB';
  if (k < 1024 * 1024) return (k / 1024).toFixed(1) + ' MB';
  return (k / 1048576).toFixed(2) + ' GB';
}
/* apt-cache show 对同一包可能输出多个版本的多段记录，这里只取第一段，
   否则 Version/Description 会被后续段的字段污染（多出换行） */
function parseAptShow(stdout) {
  const out = {};
  let key = null;
  for (const line of String(stdout).split('\n')) {
    const m = /^([A-Za-z0-9-]+):\s?(.*)$/.exec(line);
    if (m) {
      if (m[1] === 'Package' && key !== null) break;   /* 第二段记录开始，停止 */
      key = m[1];
      if (!(key in out)) out[key] = m[2];
      continue;
    }
    if (key && /^\s+/.test(line)) out[key] += ' ' + line.trim();
  }
  return out;
}
function parsePolicy(stdout) {
  const res = { installed: null, candidate: null, pinned: false };
  for (const line of String(stdout).split('\n')) {
    let m = /^\s*Installed:\s*(.+)$/.exec(line);
    if (m) { res.installed = m[1].trim(); continue; }
    m = /^\s*Candidate:\s*(.+)$/.exec(line);
    if (m) { res.candidate = m[1].trim(); continue; }
    if (/pinned/i.test(line)) res.pinned = true;
  }
  return res;
}
function parseUpgradable(stdout) {
  const rows = [];
  for (const line of String(stdout).split('\n')) {
    if (/^Listing/.test(line) || !line.trim()) continue;
    const m = /^(\S+?)\/(\S+)\s+(\S+)\s+(\S+)\s+\[upgradable from:\s*([^\]]+)\]/.exec(line.trim());
    if (m) rows.push({ name: m[1], suite: m[2], candidate: m[3], arch: m[4], current: m[5].trim() });
    else if (line.trim()) {
      const p = line.trim().split(/\s+/);
      if (p.length >= 2 && p[0].includes('/')) rows.push({ name: p[0].split('/')[0], suite: p[0].split('/')[1], candidate: p[1], arch: p[2] || '', current: '' });
    }
  }
  return rows;
}

/* ============ 读接口 ============ */
async function summary() {
  const [inst, upg, holds, df, idxOut, cacheOut] = await Promise.all([
    sh('dpkg-query -W -f=' + DPKG_FMT + ' 2>/dev/null', 60000),
    sh('apt list --upgradable 2>/dev/null'),
    sh('apt-mark showhold 2>/dev/null'),
    sh("df -h /var/cache/apt 2>/dev/null | tail -1"),
    sh("ls -1t /var/lib/apt/lists/*Packages* 2>/dev/null | head -1 | xargs -r stat -c '%y'"),
    sh('du -sh /var/cache/apt/archives 2>/dev/null | cut -f1'),
  ]);
  const all = parseInstalled(inst);
  const upgradable = parseUpgradable(upg);
  const holdList = holds.split('\n').map((s) => s.trim()).filter(Boolean);
  return {
    installed_total: all.length,
    installed_active: all.filter((x) => x.installed).length,
    removed_config: all.filter((x) => !x.installed && x.status.startsWith('rc')).length,
    upgradable: upgradable.length,
    held: holdList.length,
    hold_list: holdList.slice(0, 30),
    index_updated: (idxOut || '').trim() || null,
    apt_cache_size: (cacheOut || '').trim() || null,
    disk: (function () {
      const parts = String(df).trim().split(/\s+/);
      const nums = (parts.filter((p) => /^[\d.]+[A-Z%]*$/.test(p)));
      return { line: String(df).trim(), avail: nums.length >= 3 ? nums[2] : '', total: nums.length >= 2 ? nums[1] : '', used_pct: nums.length >= 4 ? nums[3] : '' };
    }()),
    lock: lockState(),
  };
}

async function search({ q, limit }) {
  const query = String(q == null ? '' : q).trim();
  if (query.length < 2) bad('请输入至少 2 个字符的搜索词');
  if (query.length > 100) bad('搜索词过长');
  if (/[\r\n\x00]/.test(query)) bad('搜索词含非法字符');
  /* 严格拒绝而非静默改写：让用户知道自己输入了什么，而不是搜了个"被清洗过"的词 */
  if (!/^[A-Za-z0-9._+ -]{2,100}$/.test(query)) bad('搜索词只能包含字母、数字、空格与 . _ + -');
  const lim = Math.max(1, Math.min(200, parseInt(limit, 10) || 80));
  const safe = query.trim();
  const isName = PKG_RE.test(safe);
  const [raw, inst] = await Promise.all([
    sh(`apt-cache search ${isName ? '--names-only ' : ''}${shq(safe)} 2>/dev/null | head -400`, 45000),
    installedMap(),
  ]);
  const results = [];
  const seen = new Set();
  for (const line of raw.split('\n')) {
    const m = /^(\S+)\s+-\s+(.*)$/.exec(line.trim());
    if (!m) continue;
    const name = m[1];
    if (seen.has(name)) continue;
    seen.add(name);
    const iv = inst.get(name);
    results.push({
      name, description: m[2].slice(0, 220),
      installed: !!iv, version: iv ? iv.version : '', size_kb: iv ? iv.size_kb : null,
      section: iv ? iv.section : '',
    });
    if (results.length >= lim) break;
  }
  /* 精确匹配置顶（即使 apt-cache search 没返回） */
  if (isName && !seen.has(safe)) {
    const pol = parsePolicy(await sh(`apt-cache policy ${shq(safe)} 2>/dev/null`, 20000));
    if (pol.candidate) {
      const iv = inst.get(safe);
      results.unshift({ name: safe, description: '(精确匹配)', installed: !!iv, version: iv ? iv.version : '', size_kb: iv ? iv.size_kb : null, section: iv ? iv.section : '', exact: true });
    }
  }
  results.sort((a, b) => (b.exact ? 1 : 0) - (a.exact ? 1 : 0) || Number(b.installed) - Number(a.installed) || a.name.localeCompare(b.name));
  return { query, total: results.length, truncated: results.length >= lim, results };
}

async function detail({ name }) {
  const n = String(name == null ? '' : name).trim();
  if (!PKG_RE.test(n)) bad('非法包名: ' + n.slice(0, 60));
  const [showRaw, polRaw, inst, rd, files, ch, meta] = await Promise.all([
    sh(`apt-cache show ${shq(n)} 2>/dev/null`, 30000),
    sh(`apt-cache policy ${shq(n)} 2>/dev/null`, 20000),
    installedMap(),
    sh(`apt-cache rdepends --installed ${shq(n)} 2>/dev/null | head -40`, 30000),
    sh(`dpkg -L ${shq(n)} 2>/dev/null | wc -l`, 20000),
    sh(`apt-cache show ${shq(n)} 2>/dev/null | grep '^Changelog:' | head -1`, 20000),
    sh(`apt-cache show ${shq(n)} 2>/dev/null | grep -E '^(Task|Section|Priority|Origin|Source):' | head -6`, 20000),
  ]);
  if (!showRaw.trim() && !polRaw.trim()) bad('未找到软件包: ' + n);
  const s = parseAptShow(showRaw);
  const pol = parsePolicy(polRaw);
  const iv = inst.get(n);
  const reverse = rd.split('\n').map((x) => x.trim()).filter((x) => x && !/^Reverse Depends:/.test(x) && x !== n);
  /* Debian 13 / Ubuntu 26.04 起 apt 输出本地化字段名 Description-en，需兼容旧式 Description */
  const descText = s['Description-en'] || s.Description || '';
  return {
    name: n,
    summary: descText ? String(descText).split('\n')[0].slice(0, 400) : '',
    description: descText.slice(0, 2500),
    version: s.Version || '', section: s.Section || '', priority: s.Priority || '',
    homepage: (s.Homepage || '').split(',')[0].trim(), origin: s.Origin || '',
    maintainer: (s.Maintainer || s['Original-Maintainer'] || '').slice(0, 160),
    bugs_url: (s.Bugs || '').trim(),
    size_kb: parseInt(s['Installed-Size'], 10) || 0, size: humanSize(parseInt(s['Installed-Size'], 10) || 0),
    depends: (s.Depends || '').split(',').map((x) => x.trim()).filter(Boolean),
    recommends: (s.Recommends || '').split(',').map((x) => x.trim()).filter(Boolean),
    installed_version: pol.installed, candidate: pol.candidate, pinned: pol.pinned,
    installed: !!iv, installed_size_kb: iv ? iv.size_kb : null,
    upgradable: !!(pol.installed && pol.candidate && pol.installed !== pol.candidate),
    protected: classify(n), file_count: parseInt((files || '0').trim(), 10) || 0,
    changelog_url: (ch || '').replace(/^Changelog:\s*/, '').trim(),
    reverse_deps: reverse.slice(0, 30),
    meta: (meta || '').split('\n').filter(Boolean),
  };
}

async function installed({ q, section, sort, dir, limit, offset }) {
  const all = await installedMap();
  let rows = [...all.values()];
  const query = String(q || '').trim().toLowerCase();
  if (query) rows = rows.filter((r) => r.name.toLowerCase().includes(query) || r.section.toLowerCase().includes(query));
  if (section) rows = rows.filter((r) => r.section === section);
  const sk = ['name', 'size_kb', 'section', 'version'].includes(String(sort)) ? String(sort) : 'name';
  const mul = String(dir).toLowerCase() === 'desc' ? -1 : 1;
  rows.sort((a, b) => {
    const x = a[sk], y = b[sk];
    if (x === y) return 0;
    if (x === undefined || x === null) return 1;
    if (y === undefined || y === null) return -1;
    return x > y ? mul : -mul;
  });
  const total = rows.length;
  const lim = Math.max(1, Math.min(500, parseInt(limit, 10) || 50));
  const off = Math.max(0, parseInt(offset, 10) || 0);
  const sections = [...new Set([...all.values()].map((r) => r.section))].sort();
  return {
    total, offset: off, limit: lim, sections,
    rows: rows.slice(off, off + lim).map((r) => Object.assign({}, r, { size: humanSize(r.size_kb) })),
  };
}

async function upgradable() {
  const [raw, inst] = await Promise.all([sh('apt list --upgradable 2>/dev/null'), installedMap()]);
  const rows = parseUpgradable(raw).map((r) => Object.assign(r, { section: (inst.get(r.name) || {}).section || '' }));
  return { total: rows.length, rows };
}

async function autoremoveList() {
  const out = await sh('apt-get -s autoremove 2>&1', 90000);
  return { output: String(out).split('\n').slice(-60).join('\n'), raw_lines: String(out).split('\n').length };
}

/* 精选常用包（按分类）
 * 每个包名都已用 tools/validate-recommended.js 对照当前源实际校验存在性，
 * 避免"一键安装"因包不存在而失败。修改本清单后请重跑该校验脚本。
 * 结构: [{ id, label, icon, pkgs: [[包名, 说明], ...] }, ...]
 */
const RECOMMENDED = [
  /* 监控诊断 */ { id: 'monitor', label: '监控诊断', icon: 'gauge', pkgs: [
    ['htop', '交互式进程监控，替代 top'],
    ['btop', '图形化终端监控，资源占用一目了然'],
    ['atop', '长期性能记录，可回溯历史负载'],
    ['glances', '全能监控，Web/API 多端输出'],
    ['nmon', 'Nagios 经典监控，CSV/终端输出'],
    ['sysstat', 'sa/sar 历史性能统计，配合 cron'],
    ['iotop', '按进程查看磁盘 IO 占用'],
    ['powertop', '分析电源消耗，优化省电'],
    ['bmon', '网卡流量实时曲线'],
    ['nload', '终端带宽监控'],
    ['ncdu', '交互式磁盘占用分析（比 du 直观）'],
    ['lsof', '查看端口占用与打开的文件句柄'],
    ['psmisc', 'pstree/killall 等进程工具集'],
    ['strace', '系统调用追踪，排查疑难故障'],
    ['ltrace', '库函数调用追踪'],
    ['bpftrace', '内核级探针，性能分析利器'],
    ['linux-tools-common', 'perf 等内核性能分析工具（通用部分）'],
    ['stress-ng', 'CPU/内存/IO 压测'],
    ['s-tui', '终端压力测试与状态可视化'],
    ['gdb', 'C/C++ 调试器'],
    ['procps', 'ps/top/free 等进程工具'],
  ] },
  /* 网络工具 */ { id: 'network', label: '网络工具', icon: 'network', pkgs: [
    ['curl', 'HTTP 客户端，脚本调接口首选'],
    ['wget', '文件下载与镜像同步'],
    ['net-tools', 'netstat/ifconfig/route 传统网络工具'],
    ['iputils-ping', 'ping 工具'],
    ['traceroute', '路由追踪，定位网络断点'],
    ['mtr-tiny', 'ping+traceroute 合体，持续探测丢包'],
    ['mtr', '完整版 MTR'],
    ['nmap', '端口与服务扫描'],
    ['tcpdump', '抓包分析，网络取证基础'],
    ['tshark', 'Wireshark 命令行版，命令行抓包分析'],
    ['wireshark', '图形化抓包分析'],
    ['socat', '万能端口转发/中继/伪终端'],
    ['netcat-openbsd', 'nc，端口连通性测试'],
    ['iperf3', '网络带宽吞吐测试'],
    ['iftop', '实时按连接显示流量'],
    ['nethogs', '按进程统计网络流量'],
    ['vnstat', '网卡流量历史统计（持久化）'],
    ['ethtool', '网卡协商/速率/双工配置'],
    ['arp-scan', '局域网设备发现'],
    ['whois', '域名与 IP 归属查询'],
    ['mosh', '比 SSH 更抗断线的远程终端'],
    ['autossh', 'SSH 自动重连隧道'],
    ['sshpass', '非交互式 SSH 密码登录（脚本用）'],
    ['hping3', '网络探测与压力测试工具'],
    ['wrk', '高性能 HTTP 压测'],
    ['hey', 'HTTP 压测（更易用的 wrk）'],
  ] },
  /* 文件与压缩 */ { id: 'file', label: '文件与压缩', icon: 'folder', pkgs: [
    ['tree', '目录树状显示'],
    ['unzip', '解压 ZIP'],
    ['zip', '打包 ZIP'],
    ['7zip', '7-Zip 命令行版（Ubuntu 26.04 新名称）'],
    ['unar', '解包 rar/7z 等多格式'],
    ['xz-utils', 'xz 压缩（Linux 内核镜像常用）'],
    ['zstd', 'Zstandard 高压缩比压缩'],
    ['bzip2', 'bz2 压缩'],
    ['file', '识别文件类型'],
    ['duf', '磁盘占用分析（比 ncdu 直观）'],
    ['plocate', 'locate 快速文件查找（updatedb.db）'],
    ['fd-find', '更好用的 find 替代品（命令 fd）'],
    ['ripgrep', '递归极速全文搜索 rg'],
    ['fzf', '命令行模糊查找神器'],
    ['bat', '带语法高亮的 cat'],
    ['eza', '现代 ls 替代品'],
    ['mc', 'Midnight Commander 双面板文件管理器'],
    ['ranger', '命令行文件管理器'],
    ['jdupes', '查找并删除重复文件'],
    ['rclone', '云存储/多网盘同步与备份'],
    ['restic', '去重加密备份工具'],
    ['borgbackup', '高效去重备份，支持远程仓库'],
    ['rsync', '增量同步'],
    ['coreutils', '基础命令集（GNU coreutils）'],
    ['unrar', '解包 rar'],
    ['xmlstarlet', '命令行处理 XML'],
  ] },
  /* 编辑与开发 */ { id: 'dev', label: '编辑与开发', icon: 'code', pkgs: [
    ['vim', 'Vim 编辑器'],
    ['neovim', '现代 Vim 分支，性能更好'],
    ['nano', '轻量文本编辑器'],
    ['micro', '现代终端编辑器，界面友好'],
    ['emacs-nox', '无图形界面 Emacs'],
    ['git', '版本控制'],
    ['build-essential', 'GCC/G++/make 编译工具链'],
    ['make', '构建工具'],
    ['cmake', 'CMake 构建系统'],
    ['python3-pip', 'Python 包管理器'],
    ['python3-venv', 'Python 虚拟环境'],
    ['pipx', '隔离安装 Python CLI 工具'],
    ['nodejs', 'Node.js 运行时'],
    ['npm', 'Node 包管理器'],
    ['golang-go', 'Go 语言工具链'],
    ['rustc', 'Rust 编译器'],
    ['default-jdk', 'OpenJDK 开发套件'],
    ['maven', 'Java 构建工具'],
    ['zsh', 'Zsh Shell'],
    ['tmux', '终端复用器，SSH 断线不丢会话'],
    ['screen', '经典终端复用器'],
    ['shellcheck', 'Shell 脚本静态检查'],
    ['shfmt', 'Shell 脚本格式化'],
    ['bats', 'Shell 单元测试框架'],
    ['git-lfs', 'Git 大文件存储'],
  ] },
  /* 系统运维 */ { id: 'ops', label: '系统运维', icon: 'gear', pkgs: [
    ['chrony', 'NTP 时间同步，替代 systemd-timesyncd'],
    ['logrotate', '日志轮转，防止日志撑爆磁盘'],
    ['rsyslog', '系统日志守护进程'],
    ['cron', '定时任务'],
    ['at', '一次性定时任务'],
    ['anacron', '补跑错过的定时任务'],
    ['supervisor', 'Python 进程守护与自动重启'],
    ['lm-sensors', '读取主板/硬盘温度传感器'],
    ['smartmontools', '硬盘 SMART 健康检测'],
    ['hdparm', '硬盘参数与电源管理'],
    ['usbutils', 'USB 设备信息（lsusb）'],
    ['pciutils', 'PCI 设备信息（lspci）'],
    ['dmidecode', '读取硬件 DMI/BIOS 信息'],
    ['lshw', '详细硬件清单'],
    ['nvme-cli', 'NVMe 固态硬盘信息与健康'],
    ['bash-completion', '命令行 Tab 补全增强'],
    ['needrestart', '检查重启后需要处理的库/服务'],
    ['debsums', '校验已安装文件是否被篡改'],
    ['bc', '命令行计算器'],
    ['apt-utils', 'apt 交互工具（apt-utils 显示已安装状态）'],
    ['debian-goodies', 'apt-utils 增强脚本'],
    ['gnupg', 'GPG 加密与签名'],
    ['ca-certificates', 'CA 根证书（apt/https 必需）'],
    ['acpi', '电源管理事件（笔记本）'],
    ['irqbalance', '多核 CPU 中断均衡'],
    ['numactl', 'NUMA 内存访问控制'],
    ['unattended-upgrades', '自动安装安全更新'],
    ['apparmor', '强制访问控制框架'],
  ] },
  /* 安全加固 */ { id: 'security', label: '安全加固', icon: 'shield', pkgs: [
    ['fail2ban', 'SSH 防爆破，自动封禁 IP'],
    ['ufw', '简单易用的防火墙'],
    ['apparmor-utils', 'AppArmor 配置工具'],
    ['auditd', 'Linux 审计子系统'],
    ['lynis', '系统安全加固扫描与建议'],
    ['rkhunter', 'Rootkit/后门检测'],
    ['chkrootkit', 'Rootkit 检测（脚本式）'],
    ['yara', '恶意样本规则匹配引擎'],
    ['clamav', '杀毒引擎（资源占用较高）'],
    ['john', '密码哈希破解检测（用于审计强度）'],
    ['aide', '文件完整性校验'],
    ['acl', 'POSIX ACL 细粒度权限'],
    ['libpam-pwquality', '密码强度策略'],
    ['openssl', '加密/证书/TLS 工具'],
    ['openvpn', 'VPN 服务端/客户端'],
    ['wireguard-tools', '现代高性能 VPN 工具'],
    ['john-data', '弱口令字典'],
    ['testssl.sh', 'SSL/TLS 配置安全扫描（命令行）'],
  ] },
  /* 数据库运维 */ { id: 'dbops', label: '数据库运维', icon: 'database', pkgs: [
    ['postgresql-client', 'PostgreSQL 客户端（psql/pg_dump）'],
    ['mariadb-client', 'MariaDB/MySQL 客户端'],
    ['default-mysql-client', '默认 MySQL 客户端'],
    ['redis-tools', 'Redis 命令行工具'],
    ['sqlite3', 'SQLite 命令行'],
    ['percona-toolkit', 'Percona 运维工具集（pt-query 等）'],
    ['pgbouncer', 'PostgreSQL 连接池'],
    ['postgresql-common', 'PostgreSQL 集群管理脚本'],
  ] },
  /* 容器云原生 */ { id: 'cloud', label: '容器云原生', icon: 'cube', pkgs: [
    ['docker.io', 'Docker 容器引擎'],
    ['docker-compose-v2', 'Docker Compose v2 插件'],
    ['podman', '无守护进程容器引擎，兼容 Docker 命令'],
    ['buildah', '无守护进程镜像构建'],
    ['skopeo', '镜像复制/检查工具'],
    ['containerd', '容器运行时'],
    ['runc', 'OCI 运行时'],
    ['caddy', '自动 HTTPS 的 Web 服务器'],
    ['containernetworking-plugins', 'CNI 网络插件'],
  ] },
  /* 日志与观测 */ { id: 'log', label: '日志与观测', icon: 'logs', pkgs: [
    ['lnav', '终端日志查看器，多文件合并与过滤'],
    ['jq', '命令行 JSON 处理'],
    ['yq', '命令行 YAML 处理'],
    ['prometheus-node-exporter', 'Prometheus 主机指标采集器'],
    ['prometheus', 'Prometheus 监控与告警'],
    ['zabbix-agent', 'Zabbix 采集端'],
    ['kcat', 'Kafka 命令行生产/消费工具'],
    ['kafkacat', 'Kafka 命令行工具（旧名）'],
    ['logtop', '终端实时日志滚动（类 top）'],
    ['gojq', '更快的 jq 替代'],
    ['prometheus-blackbox-exporter', 'HTTP/TCP/ICMP 主动探测'],
  ] },
  /* Web 与服务 */ { id: 'web', label: 'Web 与服务', icon: 'browser', pkgs: [
    ['certbot', 'Let\'s Encrypt 证书签发与自动续期'],
    ['apache2-utils', 'ab 压测、htpasswd 密码文件生成'],
    ['httpie', '比 curl 更友好的 HTTP 客户端'],
    ['lynx', '纯文本浏览器，测试页面可访问性'],
    ['w3m', '终端浏览器'],
    ['elinks', '终端浏览器'],
    ['haproxy', '反向代理与负载均衡'],
    ['python3-flask', '轻量 Web 框架'],
    ['gunicorn', 'Python WSGI 服务器'],
    ['uwsgi', 'Python 应用服务器'],
    ['siege', 'HTTP 压测工具'],
  ] },
];
async function recommended() {
  const inst = await installedMap();
  const holdRaw = await sh('apt-mark showhold 2>/dev/null');
  const holds = new Set(holdRaw.split('\n').map((s) => s.trim()).filter(Boolean));
  const categories = RECOMMENDED.map((c) => ({
    id: c.id, label: c.label, icon: c.icon || 'cube',
    total: c.pkgs.length,
    installed_count: c.pkgs.filter(([n]) => inst.has(n)).length,
    rows: c.pkgs.map(([name, desc]) => ({
      name, desc, installed: inst.has(name), held: holds.has(name), protected: classify(name),
    })),
  }));
  return {
    total: categories.reduce((a, c) => a + c.total, 0),
    installed_total: categories.reduce((a, c) => a + c.installed_count, 0),
    category_count: categories.length,
    categories,
  };
}

/* ============ 模拟预览（不改动系统） ============ */
const SIM_FLAG = { install: 'install', remove: 'remove', purge: 'purge', upgrade: 'upgrade', 'full-upgrade': 'full-upgrade', autoremove: 'autoremove' };
async function preview({ action, names }) {
  const act = String(action || '');
  if (act === 'update') return { action: act, output: await sh('apt-get -s update 2>&1', 120000) };
  if (act === 'autoremove') {
    const out = await sh('apt-get -s autoremove 2>&1', 90000);
    return { action: act, output: String(out).split('\n').slice(-40).join('\n') };
  }
  const flag = SIM_FLAG[act];
  if (!flag) bad('不支持的操作: ' + act);
  const list = (act === 'upgrade' || act === 'full-upgrade') ? [] : checkNames(names, MAX_JOB_NAMES);
  /* 批量预览：与执行侧同样的分批粒度，逐批模拟后拼接，避免一次命令过长 */
  const chunks = [];
  if (!list.length) chunks.push([]);
  else for (let i = 0; i < list.length; i += CHUNK_SIZE) chunks.push(list.slice(i, i + CHUNK_SIZE));
  const parts = [];
  for (let i = 0; i < chunks.length; i++) {
    const target = chunks[i].length ? ' ' + chunks[i].map(shq).join(' ') : '';
    const out = await sh(`apt-get -s ${flag}${target} 2>&1`, 120000);
    const text = String(out);
    parts.push(chunks.length > 1
      ? `--- 批次 ${i + 1}/${chunks.length}（${chunks[i].length} 个包）---\n` + text.split('\n').slice(-40).join('\n')
      : text.split('\n').slice(-60).join('\n'));
  }
  return { action: act, names: list, output: parts.join('\n') };
}

/* ============ 变更操作 ============ */
const APT_ACTION_CMD = {
  install: (l) => `${APT_ENV}apt-get install -y ${l.join(' ')}`,
  remove: (l) => `${APT_ENV}apt-get remove -y ${l.join(' ')}`,
  purge: (l) => `${APT_ENV}apt-get purge -y ${l.join(' ')}`,
  upgrade: () => `${APT_ENV}apt-get upgrade -y`,
  'full-upgrade': () => `${APT_ENV}apt-get full-upgrade -y`,
  autoremove: () => `${APT_ENV}apt-get autoremove -y --purge`,
  update: () => `${APT_ENV}apt-get update`,
  clean: () => `${APT_ENV}apt-get clean && apt-get autoclean`,
  hold: (l) => `apt-mark hold ${l.join(' ')}`,
  unhold: (l) => `apt-mark unhold ${l.join(' ')}`,
};
const APT_TIMEOUT = { update: 600000, install: 1800000, remove: 1800000, purge: 1800000, upgrade: 2400000, 'full-upgrade': 2400000, autoremove: 1800000, clean: 300000, hold: 120000, unhold: 120000 };

/* 同步语义的旧接口（POST /pkg/action）：内部转成任务后等待完成。
   返回结构与历史版本完全一致，脚本/旧前端无需改动即可继续使用；
   需要进度的新前端请改用 POST /pkg/job + GET /pkg/job?id= */
async function act(args) {
  const view = await startJob(args);
  const job = jobs.get(view.id);
  const done = await jobPromise(job);
  if (!done || done.state !== 'done') {
    const e = new Error((done && done.error) || '任务执行失败');
    e.status = 400;
    if (done) e.output = done.tail || done.output || '';
    throw e;
  }
  return ok(done.result.output, done.result);
}

/* shell 单引号转义 */
function shq(s) { return "'" + String(s == null ? '' : s).replace(/'/g, "'\\''") + "'"; }

/* ============ 软件源 / 镜像 ============ */

/* 内置镜像（2026-10 实测连通性后收录；已下线的镜像不收录） */
const MIRRORS = [
  { id: 'aliyun', name: '阿里云', url: 'https://mirrors.aliyun.com/ubuntu', region: '华东', desc: '阿里云用户首选，实测响应最快' },
  { id: 'aliyun-intranet', name: '阿里云(内网源)', url: 'http://mirrors.cloud.aliyuncs.com/ubuntu', region: '阿里云 ECS 内网', desc: '仅在阿里云 ECS 上可用，走内网不消耗公网流量' },
  { id: 'tencent', name: '腾讯云', url: 'https://mirrors.cloud.tencent.com/ubuntu', region: '华北', desc: '腾讯云用户优选' },
  { id: 'ustc', name: '中国科学技术大学', url: 'https://mirrors.ustc.edu.cn/ubuntu', region: '安徽', desc: '高校源，北方访问快' },
  { id: 'huawei', name: '华为云', url: 'https://repo.huaweicloud.com/ubuntu', region: '华北', desc: '华为云用户优选' },
  { id: 'nju', name: '南京大学', url: 'https://mirrors.nju.edu.cn/ubuntu', region: '江苏', desc: '高校源，实测响应很快' },
  { id: 'bfsu', name: '北京外国语大学', url: 'https://mirrors.bfsu.edu.cn/ubuntu', region: '北京', desc: '高校源' },
  { id: 'zju', name: '浙江大学', url: 'https://mirrors.zju.edu.cn/ubuntu', region: '浙江', desc: '华东高校源' },
  { id: 'tuna', name: '清华大学 TUNA', url: 'https://mirrors.tuna.tsinghua.edu.cn/ubuntu', region: '北京', desc: '知名高校源，资料最全' },
  { id: 'netease', name: '网易', url: 'https://mirrors.163.com/ubuntu', region: '杭州', desc: '老牌镜像' },
  { id: 'official', name: 'Ubuntu 官方', url: 'http://archive.ubuntu.com/ubuntu', region: '海外', desc: '官方源，国内访问较慢' },
];
const MIRROR_BY_ID = new Map(MIRRORS.map((m) => [m.id, m]));

const SOURCES_DIR = '/etc/apt/sources.list.d';
/* 只允许操作 apt 自己的源目录内的常规文件 */
const SOURCE_FILE_RE = new RegExp('^/etc/apt/(sources\\.list|sources\\.list\\.d/[A-Za-z0-9._-]+\\.(list|sources))$');

function resolveMirror(ref) {
  const r = String(ref == null ? '' : ref).trim();
  if (!r) bad('请指定镜像');
  if (MIRROR_BY_ID.has(r)) return MIRROR_BY_ID.get(r);
  /* 自定义 URL：严格白名单，杜绝 shell 元字符 */
  if (!/^https?:\/\/[A-Za-z0-9.\-]+(:\d{1,5})?(\/[A-Za-z0-9._~%\-/]*)?$/.test(r)) bad('镜像地址不合法，仅支持 http(s)://host[:port]/path');
  if (/\.\.|@/.test(r.replace(/^https?:\/\//, ''))) bad('镜像地址不合法');
  return { id: 'custom', name: '自定义镜像', url: r.replace(/\/+$/, ''), region: '-', desc: '自定义地址' };
}

async function codename() {
  const out = await sh(`grep -E '^(VERSION_CODENAME|VERSION_CODENAME=|UBUNTU_CODENAME)' /etc/os-release 2>/dev/null | head -1`);
  const m = /=\s*"?([\w.\-]+)"?/.exec(out || '');
  return (m && m[1]) || 'jammy';
}

/* 解析 deb822(.sources) / 传统(.list) 源文件 */
function parseSourceText(path, text) {
  const stanzas = [];
  if (/\.sources$/.test(path)) {
    let cur = null;
    for (const raw of String(text).split('\n')) {
      const line = raw.replace(/\r$/, '');
      if (!line.trim() || line.trim().startsWith('#')) continue;
      const m = /^([A-Za-z][A-Za-z0-9-]*):\s*(.*)$/.exec(line);
      if (!m) continue;
      const k = m[1].toLowerCase(), v = m[2].trim();
      if (k === 'types') { cur = { types: v, uris: [], suites: [], components: [], signed_by: null, enabled: true }; stanzas.push(cur); continue; }
      if (!cur) continue;
      if (k === 'uris') cur.uris = v.split(/\s+/).filter(Boolean);
      else if (k === 'suites') cur.suites = v.split(/\s+/).filter(Boolean);
      else if (k === 'components') cur.components = v.split(/\s+/).filter(Boolean);
      else if (k === 'signed-by') cur.signed_by = v;
      else if (k === 'enabled') cur.enabled = String(v).toLowerCase() !== 'no';
    }
  } else {
    let disabled = false, cur = null;
    for (const raw of String(text).split('\n')) {
      const line = raw.replace(/\r$/, '').trim();
      if (!line || line.startsWith('#')) continue;
      if (line.startsWith('deb-')) { disabled = true; continue; }
      const m = /^(deb(?:-src)?)\s+(\S+)\s+(\S+)(.*)$/.exec(line);
      if (!m) continue;
      cur = { types: m[1], uris: [m[2]], suites: [m[3]], components: m[4].trim().split(/\s+/).filter(Boolean), signed_by: null, enabled: !disabled };
      stanzas.push(cur);
      disabled = false;
    }
  }
  return stanzas;
}

async function listSources() {
  await requireDebianFamily();
  /* 主源列表文件 + sources.list.d 下的所有 .list/.sources */
  const paths = ['/etc/apt/sources.list'];
  const listing = await sh(`ls -1 ${SOURCES_DIR} 2>/dev/null`);
  for (const n of listing.split('\n').map((s) => s.trim()).filter(Boolean)) paths.push(SOURCES_DIR + '/' + n);
  const files = [];
  for (const path of paths) {
    if (!SOURCE_FILE_RE.test(path)) continue;
    const text = await sh(`cat ${shq(path)} 2>/dev/null`);
    if (!text) continue;
    const stanzas = parseSourceText(path, text);
    if (stanzas.length) files.push({ path, name: path.split('/').pop(), stanzas });
  }
  /* 当前生效的主镜像 */
  const cn = await codename();
  let current = null;
  for (const f of files) {
    for (const s of f.stanzas) {
      if (!s.enabled || !s.suites.some((x) => x === cn)) continue;
      const uri = s.uris[0];
      if (!uri) continue;
      const known = MIRRORS.find((m) => m.url.replace(/\/+$/, '') === uri.replace(/\/+$/, ''));
      current = { uri, file: f.path, suite: s.suites.find((x) => x === cn), name: known ? known.name : '自定义', mirror_id: known ? known.id : 'custom', security_uri: null };
    }
  }
  /* 安全更新源用的镜像 */
  for (const f of files) {
    for (const s of f.stanzas) {
      if (!s.enabled || !s.suites.some((x) => /-security$/.test(x))) continue;
      const uri = s.uris[0];
      const known = MIRRORS.find((m) => m.url.replace(/\/+$/, '') === uri.replace(/\/+$/, ''));
      if (uri) current = Object.assign(current || {}, { security_uri: uri, security_mirror: known ? known.name : '自定义' });
    }
  }
  return { codename: cn, current, files, mirrors: MIRRORS };
}

/* apt 索引文件名与源地址的映射：host/path -> host_path_ */
function indexPrefix(uri) {
  const m = /^(https?):\/\/([^/]+)(\/.*)?$/.exec(String(uri).trim());
  if (!m) return null;
  const host = m[2].replace(/:/g, '_');
  const path = (m[3] || '/').replace(/^\//, '').replace(/\/+$/, '').replace(/[/:]/g, '_');
  return (host + (path ? '_' + path : '') + '_').replace(/_+$/, '_');
}
async function sourceIndexFiles(uri) {
  const prefix = indexPrefix(uri);
  if (!prefix) return [];
  const out = await sh(`ls -1 /var/lib/apt/lists/ 2>/dev/null | grep -F ${shq(prefix)} | grep -E '_Packages$|_Contents' | head -60`);
  return out.split('\n').map((s) => s.trim()).filter(Boolean).map((f) => '/var/lib/apt/lists/' + f);
}

/* 按源搜索：直接扫描该源自己的 Packages 索引（apt-cache 无法按源过滤） */
const AWK_PATH = path.join(tmpDir, 'pkg-search.awk');
const AWK_PROG = [
  'BEGIN { q = tolower(Q) }',
  '{',
  '  name=""; ver=""; isz=""; sz=""; desc=""; sec="";',
  '  n = split($0, L, "\\n");',
  '  for (i = 1; i <= n; i++) {',
  '    line = L[i];',
  '    if (substr(line,1,9) == "Package: ") name = substr(line,10);',
  '    else if (substr(line,1,9) == "Version: ") ver = substr(line,10);',
  '    else if (substr(line,1,16) == "Installed-Size: ") isz = substr(line,17);',
  '    else if (substr(line,1,6) == "Size: " && sz == "") sz = substr(line,7);',
  '    else if (substr(line,1,8) == "Section: ") sec = substr(line,9);',
  '    else if (desc == "" && substr(line,1,13) == "Description: ") desc = substr(line,14);',
  '    else if (desc == "" && substr(line,1,16) == "Description-en: ") desc = substr(line,17);',
  '  }',
  '  if (name == "") next;',
  '  nl = tolower(name); dl = tolower(desc);',
  '  if (index(nl, q) > 0 || index(dl, q) > 0) printf "%s\\t%s\\t%s\\t%s\\t%s\\n", name, ver, (isz != "" ? isz : sz), sec, desc;',
  '}',
].join('\n');

async function searchInSource({ q, uri, limit }) {
  await requireDebianFamily();
  const query = String(q == null ? '' : q).trim().toLowerCase();
  if (query.length < 2) bad('请输入至少 2 个字符的搜索词');
  if (query.length > 100) bad('搜索词过长');
  if (!/^[A-Za-z0-9._+ -]{2,100}$/.test(query)) bad('搜索词只能包含字母、数字、空格与 . _ + -');
  if (!uri) bad('请指定软件源');
  const lim = Math.max(1, Math.min(300, parseInt(limit, 10) || 100));
  const files = await sourceIndexFiles(uri);
  if (!files.length) bad('该源的索引尚未下载，请先「更新索引」');
  /* awk 辅助脚本必须写到「目标主机」，用 agent.writeFile 而非本地 fs
     （本地 fs 在 ssh 模式下会写到本机，远程 awk 找不到文件） */
  try {
    await sh(`mkdir -p ${shq(tmpDir)}`);
    const existing = await sh(`cat ${shq(AWK_PATH)} 2>/dev/null`);
    if (existing.trim() !== AWK_PROG.trim()) {
      await getAgent().writeFile(AWK_PATH, AWK_PROG);
    }
  } catch (e) { bad('无法写入搜索辅助文件到目标主机: ' + e.message); }
  const out = await sh(`awk -v RS='' -v Q=${shq(query)} -f ${shq(AWK_PATH)} ${files.map(shq).join(' ')} 2>/dev/null | head -${lim * 3}`, 120000);
  const inst = await installedMap();
  const seen = new Set();
  const rows = [];
  for (const line of String(out).split('\n')) {
    if (!line.trim()) continue;
    const [name, ver, size, sec, desc] = line.split('\t');
    if (!name || seen.has(name)) continue;
    seen.add(name);
    const iv = inst.get(name);
    rows.push({
      name, version: ver || '', size_kb: parseInt(size, 10) || 0, section: sec || '',
      description: String(desc || '').split('\n')[0].slice(0, 200),
      installed: !!iv, installed_version: iv ? iv.version : '', source: uri,
    });
    if (rows.length >= lim) break;
  }
  /* 相关度排序：精确名 > 前缀匹配 > 名称包含 > 仅描述命中 */
  rows.sort((a, b) => {
    const rank = (r) => (r.name.toLowerCase() === query ? 0 : r.name.toLowerCase().indexOf(query) === 0 ? 1 : r.name.toLowerCase().indexOf(query) >= 0 ? 2 : 3);
    return rank(a) - rank(b) || a.name.localeCompare(b.name);
  });
  return { query, uri, total: rows.length, scanned_files: files.length, results: rows };
}

async function testMirror({ url }) {
  const m = resolveMirror(url);
  const cn = await codename();
  const probe = `${m.url}/dists/${cn}/Release`;
  const r = await run(`curl -o /dev/null -s -w '%{http_code} %{time_total} %{speed_download}' --max-time 10 ${shq(probe)}`, { timeout: 20000 });
  const [code, secs, speed] = String(r.stdout || '').trim().split(/\s+/);
  return {
    id: m.id, name: m.name, url: m.url,
    reachable: code === '200',
    http_code: code || '-',
    latency_ms: secs ? Math.round(parseFloat(secs) * 1000) : null,
    release: probe,
    ok: code === '200',
  };
}

/* 切换主镜像：备份 → 改写 URIs → apt-get update → 失败自动回滚 */
async function switchMirror({ mirror }) {
  await requireDebianFamily();
  const target = resolveMirror(mirror);
  const cn = await codename();
  const info = await listSources();
  if (!info.current || !info.current.file) bad('未找到定义发行版主仓库的源文件');
  const file = info.current.file;
  const original = await sh(`cat ${shq(file)} 2>/dev/null`);
  if (!original) bad('无法读取源文件: ' + file);
  /* 只有当文件确实声明了本发行版才允许改写 */
  if (!new RegExp('^Suites:.*\\b' + cn.replace(/[-.]/g, '\\$&') + '\\b', 'm').test(original)) {
    bad('源文件未以 deb822 形式声明 ' + cn + '，为避免破坏系统已拒绝改写');
  }
  try { fs.mkdirSync(APT_BACKUP_DIR, { recursive: true }); } catch (_) { }
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const backup = `${APT_BACKUP_DIR}/${file.split('/').pop()}.${stamp}`;
  try { fs.writeFileSync(backup, original); } catch (e) { bad('备份源文件失败: ' + e.message); }
  await sh(`chmod 644 ${shq(backup)}`);
  /* 替换所有 URIs 行 */
  const updated = original.replace(/^URIs:.*$/gm, 'URIs: ' + target.url);
  const agent = getAgent();
  return withLock('switch:' + target.id, async () => {
    await agent.writeFile(file, updated);
    const r = await run(`${APT_ENV}apt-get update`, { timeout: 900000 });
    const out = ((r.stdout || '') + (r.stderr || ''));
    /* apt-get update 在"部分/全部源不可达"时可能仍返回退出码 0，
       因此必须同时校验：退出码 + Err: 行 + 新源索引是否真的落地 */
    const errLines = out.split('\n').filter((l) => /^(Err|E):/i.test(l.trim()));
    const prefix = indexPrefix(target.url) || '';
    const landed = prefix ? await sh(`ls -1 /var/lib/apt/lists/ 2>/dev/null | grep -c -F ${shq(prefix)}`) : '0';
    const landedN = parseInt(String(landed).trim(), 10) || 0;
    if (r.code !== 0 || errLines.length || landedN === 0) {
      await agent.writeFile(file, original);
      await run(`${APT_ENV}apt-get update`, { timeout: 900000 }).catch(() => { });
      const tail = out.trim().split('\n').filter((l) => l.trim()).slice(-8).join('\n');
      const why = landedN === 0 ? '新源索引未下载成功（镜像不可达或仓库路径不存在）' : (errLines.length ? errLines.slice(0, 3).join('; ') : 'apt-get update 失败');
      const e = new Error(`切换到 ${target.name} 失败：${why}\n已自动回滚到原镜像 ${info.current.uri}\n${tail}\n（原文件备份：${backup}）`);
      e.status = 400; e.output = tail; throw e;
    }
    /* 清掉不再对应任何已配置源的残留索引，否则按源搜索会串源 */
    await pruneStaleIndexes();
    audit('pkg.mirror.switch', { from: info.current.uri, to: target.url, backup, indexes: landedN });
    const tail = out.trim().split('\n').filter((l) => l.trim()).slice(-14).join('\n');
    return ok(`已切换到 ${target.name}（${target.url}）并刷新索引（新增索引 ${landedN} 个）\n${tail}`, { mirror: target, backup, from: info.current.uri, indexes: landedN });
  });
}

/* 删除不属于任何已启用源的 Packages 索引 */
async function pruneStaleIndexes() {
  try {
    const info = await listSources();
    const prefixes = [];
    for (const f of info.files) {
      for (const s of f.stanzas) {
        if (!s.enabled) continue;
        for (const u of s.uris) { const p = indexPrefix(u); if (p && prefixes.indexOf(p) < 0) prefixes.push(p); }
      }
    }
    if (!prefixes.length) return { removed: 0 };
    const patterns = prefixes.map((p) => `'${p}*'`).join(' ');
    const out = await run(`cd /var/lib/apt/lists && ls -1 2>/dev/null | grep '_Packages$' | grep -v -e ${patterns} | while read -r f; do rm -f -- "$f" && echo "$f"; done`, { timeout: 60000 });
    const removed = String(out.stdout || '').split('\n').filter((s) => s.trim()).length;
    return { removed, prefixes };
  } catch (_) { return { removed: 0 }; }
}

async function sourceBackups() {
  const out = await sh(`ls -1t ${APT_BACKUP_DIR} 2>/dev/null | head -20`);
  const items = out.split('\n').map((s) => s.trim()).filter(Boolean);
  return { dir: APT_BACKUP_DIR, total: items.length, items };
}

module.exports = {
  summary, search, detail, installed, upgradable, autoremoveList, recommended,
  preview, act, lockState, humanSize,
  startJob, jobState, ACTION_PHASES,
  listSources, searchInSource, testMirror, switchMirror, sourceBackups, resolveMirror,
  CRITICAL_PKGS, PROTECTED_PKGS, PKG_RE, MIRRORS, SOURCE_FILE_RE, RECOMMENDED,
  /* 测试辅助：执行只读 shell + 用真实 apt 日志回放进度解析 */
  _shForTest: sh,
  _makeTrackerForTest: makeTracker,
  _applyProgressForTest: applyProgress,
};