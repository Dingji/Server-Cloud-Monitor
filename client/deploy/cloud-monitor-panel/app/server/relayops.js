'use strict';
/*
 * Relay ops: rate limiting, audit trail, health alerts, daily backups.
 */
const { getAgent } = require('./agent');
const { parseInt10 } = require('./util');

const RELAY_BACKUP_DIR = '/var/backups/relay';
const RELAY_BACKUP_RE = /^relay-backup-\d{8}-\d{6}\.tar\.gz\.enc$/;
const AUDIT_LOG = '/var/log/audit/audit.log';

/* /etc/audit/rules.d/90-relay.rules 的 key，同时作为白名单 */
const AUDIT_KEYS = [
  'relay_conf', 'relay_db', 'relay_keys', 'relay_public',
  'nginx_site', 'nginx_conf', 'fail2ban_conf',
  'sshd_config', 'ssh_authorized_keys', 'relay_scripts',
  'local_admin', 'ufw_conf', 'cron_conf',
];

function bad(msg) { const e = new Error(msg); e.status = 400; throw e; }
function ok(output, extra) { return Object.assign({ ok: true, output: output == null ? '' : String(output) }, extra || {}); }

function jsonOr(text, fallback) {
  try { const v = JSON.parse(String(text || '').trim()); return v == null ? fallback : v; }
  catch (_) { return fallback; }
}

/* =============== rate limiting / CC protection =============== */
async function rateLimit() {
  const agent = getAgent();
  const AWK = `
nginx -T 2>/dev/null | awk '
  /^[ \\t]*(server|location)[ \\t]/ { ctx=$0 }
  /^[ \\t]*limit_(req|conn)[ \\t]/   { sub(/^[ \\t]+/,"",ctx); sub(/^[ \\t]+/,"",$0); print ctx " ||| " $0 }
'`;
  const [confR, zonelineR, ctxR, hourR, distR, errR, jailsR, banR] = await Promise.all([
    agent.run('cat /etc/nginx/conf.d/00-limits.conf 2>/dev/null || echo MISSING', { timeout: 8000 }),
    agent.run('grep -E "^\\s*limit_(conn_zone|req_zone|req_status|conn_status|req_log_level|conn_log_level)\\b" /etc/nginx/conf.d/00-limits.conf 2>/dev/null || true', { timeout: 8000 }),
    agent.run(AWK, { timeout: 12000 }),
    agent.run(`tail -n 30000 /var/log/nginx/opencode-relay.access.log 2>/dev/null | awk -F'|' '$3==429{print substr($1,1,13)}' | sort | uniq -c`, { timeout: 15000 }),
    agent.run(`tail -n 30000 /var/log/nginx/opencode-relay.access.log 2>/dev/null | awk -F'|' '{c[$3]++} END{for(k in c) print k, c[k]}' | sort -k2 -rn`, { timeout: 15000 }),
    agent.run(`grep -c 'limiting requests' /var/log/nginx/opencode-relay.error.log 2>/dev/null || true`, { timeout: 8000 }),
    agent.run("fail2ban-client status 2>/dev/null | sed -n 's/.*Jail list:[[:space:]]*//p' | tr ',' ' '", { timeout: 10000 }),
    agent.run(`
JAILS=$(fail2ban-client status 2>/dev/null | sed -n 's/.*Jail list:[[:space:]]*//p' | tr ',' ' ')
for j in $JAILS; do
  echo "##JAIL=$j"
  echo "CUR=$(fail2ban-client status "$j" 2>/dev/null | sed -n 's/.*Currently banned:[[:space:]]*//p' | head -1)"
  echo "TOT=$(fail2ban-client status "$j" 2>/dev/null | sed -n 's/.*Total banned:[[:space:]]*//p' | head -1)"
  echo "IPS=$(fail2ban-client get "$j" banned 2>/dev/null | tr '\\n' ' ')"
done`, { timeout: 20000 }),
  ]);

  /* zone declarations */
  const zones = [];
  for (const line of zonelineR.stdout.split('\n')) {
    let m = /limit_conn_zone\s+\S+\s+zone=([^;]+);/.exec(line);
    if (m) { const p = m[1].split(':'); zones.push({ type: 'conn', name: p[0], size: p[1] || '' }); continue; }
    m = /limit_req_zone\s+\S+\s+zone=([^;]+)\s+rate=([^;]+);/.exec(line);
    if (m) { const p = m[1].split(':'); zones.push({ type: 'req', name: p[0], size: p[1] || '', rate: m[2].trim() }); continue; }
    m = /limit_(req|conn)_(status|log_level)\s+([^;]+);/.exec(line);
    if (m) zones.push({ type: 'global', name: 'limit_' + m[1] + '_' + m[2], value: m[3].trim() });
  }

  /* where each directive is applied (nginx -T, tracked through server/location) */
  const applied = [];
  for (const line of ctxR.stdout.split('\n')) {
    const i = line.indexOf(' ||| ');
    if (i < 0) continue;
    const ctx = line.slice(0, i).trim();
    const dir = line.slice(i + 5).trim();
    let m = /limit_conn\s+(\S+)\s+(\S+);/.exec(dir);
    if (m) { applied.push({ context: ctx, kind: 'conn', zone: m[1], value: m[2] }); continue; }
    m = /limit_req\s+zone=(\S+)\s+burst=(\S+)(\s+nodelay)?;?/.exec(dir);
    if (m) applied.push({ context: ctx, kind: 'req', zone: m[1], burst: m[2], nodelay: !!m[3] });
  }

  /* 429 volume, per hour */
  const byHour = [];
  for (const line of hourR.stdout.split('\n')) {
    const m = /^\s*(\d+)\s+(\S+)$/.exec(line);
    if (m) byHour.push({ hour: m[2], count: parseInt10(m[1]) || 0 });
  }
  byHour.sort((a, b) => (a.hour < b.hour ? -1 : 1));

  /* status-code distribution over the same tail window */
  const dist = [];
  let windowTotal = 0;
  for (const line of distR.stdout.split('\n')) {
    const m = /^(\S+)\s+(\d+)$/.exec(line.trim());
    if (!m) continue;
    const code = /^\d{3}$/.test(m[1]) ? m[1] : 'other';
    const n = parseInt10(m[2]) || 0;
    windowTotal += n;
    const hit = dist.find((d) => d.code === code);
    if (hit) hit.count += n; else dist.push({ code, count: n });
  }
  dist.sort((a, b) => b.count - a.count);
  const limited = (dist.find((d) => d.code === '429') || {}).count || 0;

  /* fail2ban jails */
  const jails = [];
  for (const line of banR.stdout.split('##JAIL=')) {
    if (!line.trim()) continue;
    const nl = line.split('\n');
    const name = nl.shift().trim();
    if (!name) continue;
    const get = (p) => { const h = nl.find((l) => l.startsWith(p + '=')); return h ? h.slice(p.length + 1).trim() : ''; };
    const ips = get('IPS').split(/\s+/).filter(Boolean);
    jails.push({
      name,
      currentlyBanned: parseInt10(get('CUR')) || 0,
      totalBanned: parseInt10(get('TOT')) || 0,
      ips,
    });
  }
  if (!jails.length) {
    /* fail2ban 未运行时仍显示 jail 名 */
    for (const n of jailsR.stdout.split(/[,\s]+/).filter(Boolean)) jails.push({ name: n, currentlyBanned: 0, totalBanned: 0, ips: [], unknown: true });
  }

  return {
    present: !/MISSING/.test(confR.stdout),
    zones,
    applied,
    configRaw: confR.stdout.trim(),
    windowLines: windowTotal,
    limited429: limited,
    byHour,
    dist,
    errorLimitCount: parseInt10(errR.stdout) || 0,
    fail2banActive: /active/.test(banR.stdout) || jails.length > 0,
    jails,
    logFiles: {
      access: '/var/log/nginx/opencode-relay.access.log',
      error: '/var/log/nginx/opencode-relay.error.log',
      conf: '/etc/nginx/conf.d/00-limits.conf',
    },
  };
}

/* =============== auditd file-integrity events =============== */
const AUDIT_RANGES = { '1h': 3600, '6h': 6 * 3600, '24h': 24 * 3600, all: 0 };
const AUDIT_TAIL = { '1h': 40000, '6h': 120000, '24h': 300000, all: 600000 };

function decodeProctitle(hex) {
  if (!hex || hex.length % 2) return '';
  try {
    return Buffer.from(hex, 'hex').toString('utf8').split('\0').filter(Boolean).join(' ');
  } catch (_) { return ''; }
}

function parseAuditEvents(text, cutoff, wantKey, limit) {
  const groups = new Map();
  const counts = {};
  for (const line of String(text || '').split('\n')) {
    const mi = /msg=audit\((\d+(?:\.\d+)?):(\d+)\)/.exec(line);
    if (!mi) continue;
    const ts = Math.floor(parseFloat(mi[1]));
    if (cutoff && ts < cutoff) continue;
    const serial = mi[2];
    const ty = (/^type=(\w+)/.exec(line) || [])[1] || '';

    let g = groups.get(serial);
    if (!g) { g = { serial, ts, key: '', comm: '', exe: '', uid: '', pid: '', success: '', cmd: '', cwd: '', files: [] }; groups.set(serial, g); }

    if (ty === 'SYSCALL') {
      g.key = (/ key="([^"]*)"/.exec(line) || [])[1] || '';
      g.comm = (/ comm="([^"]*)"/.exec(line) || [])[1] || '';
      g.exe = (/ exe="([^"]*)"/.exec(line) || [])[1] || '';
      g.uid = (/(?:^|\s)uid=(\d+)/.exec(line) || [])[1] || '';
      g.pid = (/(?:^|\s)pid=(\d+)/.exec(line) || [])[1] || '';
      g.success = (/success=(\w+)/.exec(line) || [])[1] || '';
    } else if (ty === 'PATH') {
      const nm = (/ name="([^"]*)"/.exec(line) || [])[1];
      const nt = (/ nametype=(\w+)/.exec(line) || [])[1] || '';
      if (nm && nt !== 'PARENT' && g.files.indexOf(nm) < 0) g.files.push(nm);
    } else if (ty === 'CWD') {
      if (!g.cwd) g.cwd = (/cwd="([^"]*)"/.exec(line) || [])[1] || '';
    } else if (ty === 'PROCTITLE') {
      const h = (/proctitle=([0-9a-fA-F]+)/.exec(line) || [])[1];
      if (h && !g.cmd) g.cmd = decodeProctitle(h);
    }
  }

  const out = [];
  for (const g of groups.values()) {
    /* keep only events tagged by our rules; untagged system noise is dropped */
    if (!g.key || AUDIT_KEYS.indexOf(g.key) < 0) continue;
    counts[g.key] = (counts[g.key] || 0) + 1;
    if (wantKey && g.key !== wantKey) continue;
    out.push(g);
  }
  out.sort((a, b) => b.ts - a.ts || (a.serial > b.serial ? -1 : 1));
  const total = out.length;
  return { events: out.slice(0, limit), total, counts };
}

async function auditSearch({ range, key, limit } = {}) {
  range = String(range || '24h');
  key = String(key || '');
  if (!(range in AUDIT_RANGES)) bad('未知时间范围: ' + range);
  if (key && AUDIT_KEYS.indexOf(key) < 0) bad('未知审计规则: ' + key);
  const lim = Math.max(1, Math.min(1000, parseInt10(limit) || 300));

  const agent = getAgent();
  const present = await agent.run(`[ -r ${AUDIT_LOG} ] && echo yes || echo no`, { timeout: 8000 });
  if (!/yes/.test(present.stdout)) {
    return { available: false, reason: '审计日志不可读或 auditd 未运行', keys: AUDIT_KEYS, events: [], counts: {}, total: 0 };
  }

  /* 两遍过滤：先取匹配 SYSCALL 的 serial，再取该事件的全部行 */
  const keyAlt = (key ? [key] : AUDIT_KEYS).join('|');
  const cmd = [
    `LOG=${AUDIT_LOG}`,
    'T=$(mktemp)',
    `tail -n ${AUDIT_TAIL[range]} "$LOG" > "$T"`,
    `grep -a "type=SYSCALL" "$T" | grep -aE 'key="(${keyAlt})"' | grep -aoE 'msg=audit\\([0-9.]+:[0-9]+\\)' | sed 's/.*://; s/)//' | sort -u > "$T.s"`,
    'if [ -s "$T.s" ]; then sed \'s/^/:/; s/$/)/\' "$T.s" > "$T.p"; grep -aF -f "$T.p" "$T"; fi',
    'rm -f "$T" "$T.s" "$T.p"',
  ].join('\n');
  const raw = await agent.run(cmd, { timeout: 30000 });
  const cutoff = AUDIT_RANGES[range] ? Math.floor(Date.now() / 1000) - AUDIT_RANGES[range] : 0;
  const parsed = parseAuditEvents(raw.stdout, cutoff, key, lim);

  const rules = await agent.run('auditctl -l 2>/dev/null | head -40 || true', { timeout: 8000 });
  const disk = await agent.run('du -sh /var/log/audit 2>/dev/null | cut -f1; ls /var/log/audit 2>/dev/null | tr "\\n" " "', { timeout: 8000 });

  return {
    available: true,
    range, key, limit: lim,
    keys: AUDIT_KEYS,
    logFile: AUDIT_LOG,
    rules: rules.stdout.trim().split('\n').filter(Boolean),
    storage: disk.stdout.trim(),
    ...parsed,
  };
}

/* =============== health alerts (relay_alert.py) =============== */
async function healthAlerts() {
  const agent = getAgent();
  const [files, logR, timersR, unitR] = await Promise.all([
    agent.readFiles([
      '/var/lib/relay/alert.state',
      '/var/lib/relay/backup.state',
      '/var/lib/relay/verify.state',
      '/var/lib/relay/dbguard.state',
      '/var/lib/relay/diskguard.state',
      '/etc/relay/report.json',
    ]),
    agent.run('tail -80 /var/log/relay-alert.log 2>/dev/null || true', { timeout: 10000 }),
    agent.run('systemctl list-timers relay-alert.timer relay-backup.timer relay-disk-hygiene.timer --no-pager --output=json 2>/dev/null || echo "[]"', { timeout: 10000 }),
    agent.run("systemctl is-active relay-alert.timer relay-backup.timer 2>/dev/null || true", { timeout: 8000 }),
  ]);

  const state = jsonOr(files['/var/lib/relay/alert.state'], {}) || {};
  const checks = Object.keys(state).map((name) => ({
    name,
    ok: !!(state[name] && state[name].ok),
    since: (state[name] && state[name].since) || '',
    lastSent: (state[name] && state[name].last_sent) || 0,
  }));
  checks.sort((a, b) => (a.ok === b.ok ? (a.name < b.name ? -1 : 1) : (a.ok ? 1 : -1)));

  const report = jsonOr(files['/etc/relay/report.json'], {}) || {};
  const e = report.EmailConfigruation || report.EmailConfiguration || {};
  const smtp = {
    to: report.to || [],
    host: e.SMTPHost || '',
    port: e.SMTPPort || 0,
    ssl: !!e.SMTPUseSSL,
    user: e.EmailAccount || '',
    from: e.EmailAccountDisplayName || '',
  };

  const timers = jsonOr(timersR.stdout, []).map((t) => ({
    unit: t.unit || '',
    activates: t.activates || '',
    next: t.next ? new Date(t.next / 1000).toLocaleString('zh-CN', { hour12: false }) : '—',
    leftUs: t.next || 0,
    last: t.last ? new Date(t.last / 1000).toLocaleString('zh-CN', { hour12: false }) : '从未',
  }));
  const nowUs = Date.now() * 1000;
  for (const t of timers) t.left = humanDur(t.leftUs - nowUs);

  const failing = checks.filter((c) => !c.ok);
  return {
    available: checks.length > 0,
    reason: checks.length ? null : '未找到 /var/lib/relay/alert.state（relay_alert.py 尚未运行过）',
    checks,
    summary: { total: checks.length, failing: failing.length, ok: checks.length - failing.length },
    failingNames: failing.map((c) => c.name),
    log: logR.stdout.trim().split('\n').filter(Boolean),
    backup: jsonOr(files['/var/lib/relay/backup.state'], null),
    verify: jsonOr(files['/var/lib/relay/verify.state'], null),
    dbguard: jsonOr(files['/var/lib/relay/dbguard.state'], null),
    diskguard: jsonOr(files['/var/lib/relay/diskguard.state'], null),
    smtp,
    timers,
    units: unitR.stdout.trim().split('\n').filter(Boolean),
    sources: { state: '/var/lib/relay/alert.state', log: '/var/log/relay-alert.log', script: '/opt/relay/relay_alert.py' },
  };
}

function humanDur(us) {
  if (!us || us <= 0) return '—';
  const s = Math.floor(us / 1e6);
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (d) return `${d}天${h}小时`;
  if (h) return `${h}小时${m}分`;
  if (m) return `${m}分${sec}秒`;
  return `${sec}秒`;
}

/* =============== relay daily backups =============== */
async function relayBackups() {
  const agent = getAgent();
  const [lsR, stR] = await Promise.all([
    agent.run(`ls -la --time-style=long-iso ${RELAY_BACKUP_DIR}/relay-backup-*.tar.gz.enc 2>/dev/null || echo EMPTY`, { timeout: 15000 }),
    agent.readFiles(['/var/lib/relay/backup.state', '/var/lib/relay/verify.state',
      '/var/lib/relay/dbguard.state', '/var/lib/relay/diskguard.state', '/etc/relay/backup.env']),
  ]);
  const items = [];
  for (const line of lsR.stdout.split('\n')) {
    const m = /^(\S+)\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\s+(\S+)$/.exec(line.trim());
    if (m) items.push({ name: m[4].split('/').pop(), size: parseInt10(m[2]) || 0, mtime: m[3] });
  }
  /* 只认未注释的赋值（文件里带一行注释示例） */
  const env = stR['/etc/relay/backup.env'] || '';
  const remote = env.split('\n')
    .map((l) => l.trim())
    .filter((l) => l && l.charAt(0) !== '#')
    .map((l) => /^REMOTE_RCLONE="([^"]*)"$/.exec(l))
    .filter(Boolean)
    .map((m) => m[1].trim())
    .filter(Boolean)[0] || '';
  return {
    dir: RELAY_BACKUP_DIR,
    items: items.reverse(),
    state: jsonOr(stR['/var/lib/relay/backup.state'], null),
    verify: jsonOr(stR['/var/lib/relay/verify.state'], null),
    dbguard: jsonOr(stR['/var/lib/relay/dbguard.state'], null),
    diskguard: jsonOr(stR['/var/lib/relay/diskguard.state'], null),
    encrypted: true,
    remote,
    retention: 30,
    timer: 'relay-backup.timer',
  };
}

/* =============== mutating actions =============== */
async function alertTestMail() {
  const r = await getAgent().run('python3 /opt/relay/relay_alert.py --test 2>&1', { timeout: 120000 });
  const out = (r.stdout || '').trim();
  if (/TEST MAIL SENT/i.test(out)) return ok(out);
  const e = new Error('测试邮件发送失败');
  e.status = 502;
  e.output = out || '(无输出)';
  throw e;
}

async function relayBackupRun() {
  const agent = getAgent();
  const r = await agent.run('/opt/relay/backup_relay.sh 2>&1', { timeout: 300000 });
  const out = (r.stdout || '').trim();
  const st = jsonOr((await agent.readFiles(['/var/lib/relay/backup.state']))['/var/lib/relay/backup.state'], null);
  if (st && st.ok) return ok(out || '备份完成', { state: st });
  const e = new Error('备份失败');
  e.status = 500;
  e.output = out || '(无输出)';
  throw e;
}

function relayBackupPath(name) {
  const n = String(name || '');
  if (!RELAY_BACKUP_RE.test(n)) return null;
  return RELAY_BACKUP_DIR + '/' + n;
}

async function relayBackupDelete(name) {
  const p = relayBackupPath(name);
  if (!p) bad('非法备份文件名');
  const sum = p.replace(/\.enc$/, '.sha256');
  const r = await getAgent().run(`rm -f '${p}' '${sum}' && echo 已删除`, { timeout: 20000 });
  return ok(r.stdout.trim() || '已删除');
}

module.exports = {
  rateLimit, auditSearch, healthAlerts, relayBackups,
  alertTestMail, relayBackupRun, relayBackupDelete, relayBackupPath,
  AUDIT_KEYS, RELAY_BACKUP_DIR,
};
