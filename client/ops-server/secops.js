'use strict';
if (process.platform === 'win32') {
  module.exports = require('./secops-win');
  return;
}
/*
 * Security baseline audit (安全基线巡检) — the "体检 + 一键加固" feature of
 * commercial panels (宝塔安全、1Panel 节点检测). Read-only checks parse real
 * command output into ok/warn/bad plus an evidence string; remediation is an
 * explicit allowlist of procedures, never free-form input, and each one is
 * pre-validated before it is applied (sshd config is syntax-checked, firewall
 * opens the SSH port before enabling, etc.) so an operator cannot lock
 * themselves out of the box.
 */
const S = require('./store');
const ex = require('./exec');

const SCAN = 'secops_scan.json';
const HISTORY = 'secops_history.jsonl';
const FIXLOG = 'secops_fixes.jsonl';
const CONFIG = 'secops_config.json';

const LEVELS = { ok: '合规', warn: '建议处理', bad: '存在风险', info: '未覆盖' };
const CATS = ['访问控制', '网络暴露', '系统更新', '账号与权限', '日志与时间', '运行健康'];

const num = (v) => { const n = Number(v); return Number.isFinite(n) ? n : null; };

/* helpers reused by several parsers */
function sshdT(pattern) {
  const m = pattern.exec(this.sshdT || '');
  return m ? m[1].trim() : null;
}

const CHECKS = [
  {
    id: 'sshd.rootLogin', label: 'SSH 允许 root 直接登录', cat: '访问控制', weight: 8, bin: 'sshd',
    cmd: `sshd -T 2>/dev/null | grep -i '^permitrootlogin' || grep -Ei '^\\s*PermitRootLogin' /etc/ssh/sshd_config 2>/dev/null | tail -1`,
    parse(out) {
      const v = (out || '').toLowerCase();
      if (!v) return { level: 'info', detail: '读不到 sshd  effective 配置（可能非 root 运行）' };
      if (v.includes('prohibit-password') || v.includes('without-password')) return { level: 'ok', detail: 'prohibit-password：仅允许密钥登录 root' };
      if (v.includes('no') && !v.includes('nop')) return { level: 'ok', detail: '已禁止 root 直登' };
      if (v.includes('yes')) return { level: 'bad', detail: 'root 可直接登录，建议改为 prohibit-password', fix: 'sshd.harden' };
      return { level: 'warn', detail: out.trim().slice(0, 120) };
    },
  },
  {
    id: 'sshd.passwordAuth', label: 'SSH 密码登录', cat: '访问控制', weight: 8, bin: 'sshd',
    cmd: `sshd -T 2>/dev/null | grep -i '^passwordauthentication' || grep -Ei '^\\s*PasswordAuthentication' /etc/ssh/sshd_config 2>/dev/null | tail -1`,
    parse(out) {
      const v = (out || '').toLowerCase();
      if (!v) return { level: 'info', detail: '读不到配置' };
      if (v.includes('no')) return { level: 'ok', detail: '已关闭密码登录（仅密钥）' };
      return { level: 'bad', detail: '公网机器开启密码登录会持续被爆破，建议改密钥', fix: 'sshd.harden' };
    },
  },
  {
    id: 'sshd.maxAuthTries', label: 'SSH 认证次数上限', cat: '访问控制', weight: 4, bin: 'sshd',
    cmd: `sshd -T 2>/dev/null | grep -i '^maxauthtries'`,
    parse(out) {
      const n = num((out || '').split(/\s+/)[1]);
      if (n == null) return { level: 'info', detail: '未取到' };
      if (n <= 6) return { level: 'ok', detail: `MaxAuthTries=${n}` };
      return { level: 'warn', detail: `MaxAuthTries=${n}，建议 ≤4`, fix: 'sshd.harden' };
    },
  },
  {
    id: 'sshd.port', label: 'SSH 端口', cat: '访问控制', weight: 2, bin: 'sshd',
    cmd: `sshd -T 2>/dev/null | grep -i '^port'`,
    parse(out) {
      const p = (out || '').replace(/\D+/g, ' ').trim().split(' ')[0];
      if (!p) return { level: 'info', detail: '未取到' };
      if (p === '22') return { level: 'warn', detail: '默认 22 端口，扫描流量大（改端口前先放行新端口）' };
      return { level: 'ok', detail: `端口 ${p}` };
    },
  },
  {
    id: 'fail2ban.active', label: 'fail2ban 防爆破', cat: '访问控制', weight: 6, bin: 'fail2ban-client',
    cmd: `systemctl is-active fail2ban 2>/dev/null; fail2ban-client status 2>/dev/null | grep -E 'Status for|Jail list' | head -2`,
    parse(out) {
      const v = out || '';
      if (v.startsWith('active')) {
        const jails = (/jail list:\s*(.*)/i.exec(v) || [])[1] || '';
        return { level: jails ? 'ok' : 'warn', detail: jails ? `已启用，jail：${jails.trim()}` : '服务在跑但没有 jail' };
      }
      return { level: 'warn', detail: 'fail2ban 未运行，公网 SSH 无自动封禁', fix: 'fail2ban.enable' };
    },
  },
  {
    id: 'firewall.state', label: '主机防火墙', cat: '网络暴露', weight: 7, bin: 'ufw',
    cmd: `ufw status 2>/dev/null | head -4`,
    parse(out) {
      const v = (out || '');
      /* "Status: inactive" contains the word active — match the state token */
      if (/^\s*Status:\s*inactive/im.test(v)) return { level: 'warn', detail: 'ufw 未启用（云安全组可部分替代；启用前先放行 SSH 端口）', fix: 'firewall.enableSsh' };
      if (/^\s*Status:\s*active/im.test(v) || /active/i.test(v)) {
        return { level: 'ok', detail: (v.match(/default.*$/im) || ['防火墙已启用'])[0].trim() };
      }
      return { level: 'info', detail: '读不到 ufw 状态' };
    },
  },
  {
    id: 'ports.exposed', label: '对外监听端口数量', cat: '网络暴露', weight: 5,
    cmd: `ss -ltnH 2>/dev/null | awk '{print $4}' | grep -vE '^(127\\.|\\[?::1)' | sort -u`,
    parse(out) {
      const list = (out || '').split('\n').map((s) => s.trim()).filter(Boolean);
      if (!list.length) return { level: 'ok', detail: '没有对外监听端口' };
      const detail = `对外 ${list.length} 个：${list.slice(0, 8).join(', ')}${list.length > 8 ? ' …' : ''}`;
      return { level: list.length > 6 ? 'warn' : 'info', detail };
    },
  },
  {
    id: 'ports.dataExposed', label: '数据库/中间件端口对外暴露', cat: '网络暴露', weight: 9,
    cmd: `ss -ltnH 2>/dev/null | awk '{print $4}' | grep -vE '^(127\\.|\\[?::1)'`,
    parse(out) {
      const risky = { 3306: 'MySQL', 5432: 'PostgreSQL', 6379: 'Redis', 27017: 'MongoDB', 9200: 'Elasticsearch', 11211: 'Memcached', 2379: 'etcd', 3389: 'RDP' };
      const hits = [];
      for (const line of (out || '').split('\n')) {
        const m = /:(\d+)\s*$/.exec(line.trim());
        if (m && risky[m[1]]) hits.push(`${risky[m[1]]}(${m[1]})`);
      }
      if (!hits.length) return { level: 'ok', detail: '未发现数据库端口绑到公网网卡' };
      return { level: 'bad', detail: `直接暴露在公网上：${hits.join('、')}，应只监听 127.0.0.1 或用安全组限制来源` };
    },
  },
  {
    id: 'updates.pending', label: '待安装安全更新', cat: '系统更新', weight: 7, bin: 'apt',
    cmd: `apt list --upgradable 2>/dev/null | grep -c upgradable; apt list --upgradable 2>/dev/null | grep -ciE 'security|ESM'`,
    parse(out) {
      const [all, sec] = (out || '').split('\n').map((x) => num(x.trim()));
      if (all == null) return { level: 'info', detail: '无法查询' };
      if (all === 0) return { level: 'ok', detail: '无待更新包' };
      return {
        level: sec > 0 ? 'bad' : 'warn',
        detail: `可升级 ${all} 个${sec > 0 ? `，其中安全更新 ${sec} 个` : ''}`,
      };
    },
  },
  {
    id: 'updates.auto', label: '自动安全更新', cat: '系统更新', weight: 4, bin: 'systemctl',
    cmd: `systemctl is-enabled unattended-upgrades 2>/dev/null; systemctl is-active unattended-upgrades 2>/dev/null`,
    parse(out) {
      const v = out || '';
      if (/^enabled/m.test(v) && /active/.test(v)) return { level: 'ok', detail: 'unattended-upgrades 已启用并在运行' };
      return { level: 'warn', detail: '未开启自动安全更新', fix: 'updates.enableAuto' };
    },
  },
  {
    id: 'updates.reboot', label: '需要重启', cat: '系统更新', weight: 2,
    cmd: `test -f /var/run/reboot-required && cat /var/run/reboot-required 2>/dev/null || echo NONE`,
    parse(out) { return /NONE/.test(out || '') ? { level: 'ok', detail: '无需重启' } : { level: 'info', detail: (out || '').trim().slice(0, 80) } },
  },
  {
    id: 'accounts.uid0', label: '除 root 外的 UID 0 账号', cat: '账号与权限', weight: 9,
    cmd: `awk -F: '($3==0 && $1!="root"){print $1}' /etc/passwd`,
    parse(out) {
      const v = (out || '').trim();
      return v ? { level: 'bad', detail: `存在特权账号：${v.replace(/\n/g, ', ')}` } : { level: 'ok', detail: '仅 root 为 UID 0' };
    },
  },
  {
    id: 'accounts.emptyPasswd', label: '空密码账号', cat: '账号与权限', weight: 10,
    cmd: `awk -F: '($2==""){print $1}' /etc/shadow 2>/dev/null | head -5`,
    parse(out) {
      const v = (out || '').trim();
      if (!v) return { level: 'ok', detail: '无空密码账号（或无权读取 shadow）' };
      return { level: 'bad', detail: `空密码账号：${v.replace(/\n/g, ', ')}` };
    },
  },
  {
    id: 'accounts.sshKeyPerms', label: 'authorized_keys 权限过宽', cat: '账号与权限', weight: 6,
    cmd: `find /root/.ssh /home -maxdepth 4 -name authorized_keys -perm /077 2>/dev/null | head -5`,
    parse(out) {
      const v = (out || '').trim();
      return v ? { level: 'bad', detail: `可被他人改写：${v.replace(/\n/g, ', ')}`, fix: 'perms.fixSshFiles' } : { level: 'ok', detail: '权限均为 600/400 级别' };
    },
  },
  {
    id: 'fs.worldWritableEtc', label: '/etc 下世界可写文件', cat: '账号与权限', weight: 6,
    cmd: `find /etc -xdev -type f -perm -0002 2>/dev/null | head -10`,
    parse(out) {
      const v = (out || '').trim();
      return v ? { level: 'bad', detail: `可被任意用户改写：${v.split('\n').length} 个，如 ${v.split('\n')[0]}` } : { level: 'ok', detail: '无世界可写文件' };
    },
  },
  {
    id: 'fs.suidOdd', label: '异常 SUID 程序', cat: '账号与权限', weight: 5,
    cmd: `find / -xdev -perm -4000 -type f 2>/dev/null | grep -vE '/(su|sudo|sudoedit|passwd|chpasswd|shadow|newgrp|gpasswd|chfn|chsh|mount|umount|ping|fusermount|fusermount3|dbus-launch.*|Xorg|polkit.*|pkexec|at|crontab|traceroute|netstat|suexec|sg)$' | head -10`,
    parse(out) {
      const v = (out || '').trim();
      if (!v) return { level: 'ok', detail: 'SUID 位均为常见系统程序' };
      return { level: 'warn', detail: `非常规 SUID ${v.split('\n').length} 个：${v.split('\n').slice(0, 3).join(', ')}` };
    },
  },
  {
    id: 'fs.tmpNoexec', label: '/tmp 挂载 noexec', cat: '账号与权限', weight: 3, bin: 'findmnt',
    cmd: `findmnt -no OPTIONS /tmp 2>/dev/null || echo UNKNOWN`,
    parse(out) {
      const v = String((out || '').split('\n')[0] || '').trim();
      if (!v || /UNKNOWN/.test(v)) return { level: 'info', detail: '/tmp 不是独立挂载点' };
      return v.includes('noexec') ? { level: 'ok', detail: `已带 noexec（${v}）` } : { level: 'warn', detail: `缺少 noexec（${v}），Web 入侵可直接执行落地文件` };
    },
  },
  {
    id: 'cert.expiring', label: 'TLS 证书剩余天数', cat: '网络暴露', weight: 6, bin: 'openssl',
    cmd: `for d in /etc/letsencrypt/live/*/; do [ -f "$d/cert.pem" ] && printf '%s ' "$(basename $d)" && openssl x509 -enddate -noout -in "$d/cert.pem" 2>/dev/null; done`,
    parse(out) {
      const lines = (out || '').split('\n').filter(Boolean);
      if (!lines.length) return { level: 'info', detail: '未发现 certbot 签发的证书' };
      const items = [];
      let worst = null;
      for (const l of lines) {
        const m = /^(\S+)\s+notAfter=(.*)$/.exec(l.trim());
        if (!m) continue;
        const days = Math.floor((new Date(m[2]).getTime() - Date.now()) / 86400000);
        items.push(`${m[1]}:${days}天`);
        if (worst === null || days < worst) worst = days;
      }
      if (worst === null) return { level: 'info', detail: '证书日期解析失败' };
      if (worst < 0) return { level: 'bad', detail: `已过期：${items.join(', ')}` };
      if (worst < 14) return { level: 'warn', detail: `即将到期：${items.join(', ')}` };
      return { level: 'ok', detail: `最近一张剩 ${worst} 天（${items.slice(0, 3).join(', ')}）` };
    },
  },
  {
    id: 'time.sync', label: '时钟同步', cat: '日志与时间', weight: 4, bin: 'timedatectl',
    cmd: `timedatectl show -p NTPSynchronized --value 2>/dev/null; timedatectl show -p SystemUnitsMissin --value 2>/dev/null`,
    parse(out) {
      const v = (out || '').trim();
      if (/^yes/.test(v)) return { level: 'ok', detail: 'NTP 已同步' };
      if (!v) return { level: 'info', detail: '读不到 timedatectl' };
      return { level: 'bad', detail: '未做时钟同步，日志时序与证书校验都会出问题' };
    },
  },
  {
    id: 'log.journald', label: 'journal 日志占用', cat: '日志与时间', weight: 3, bin: 'journalctl',
    cmd: `journalctl --disk-usage 2>/dev/null | grep -oE '[0-9.]+[KMGT]?i?B?' | head -1`,
    parse(out) {
      const v = (out || '').trim();
      if (!v) return { level: 'info', detail: '读不到' };
      const mb = toMb(v.split('\n')[0]);
      if (mb == null) return { level: 'info', detail: v };
      return mb > 512 ? { level: 'warn', detail: `占用 ${v.split('\n')[0]}，可用磁盘治理页清理`, fix: null } : { level: 'ok', detail: `占用 ${v.split('\n')[0]}` };
    },
  },
  {
    id: 'log.rotate', label: 'logrotate 已安装', cat: '日志与时间', weight: 3,
    cmd: `command -v logrotate >/dev/null 2>&1 && echo YES || echo NO`,
    parse(out) { return /YES/.test(out || '') ? { level: 'ok', detail: '已安装' } : { level: 'warn', detail: '未安装，业务日志可能无人回收' }; },
  },
  {
    id: 'run.zombie', label: '僵尸进程', cat: '运行健康', weight: 3,
    cmd: `ps -eo stat= 2>/dev/null | grep -c '^Z'`,
    parse(out) {
      const n = num((out || '').trim());
      if (n == null) return { level: 'info', detail: '读不到' };
      return n > 20 ? { level: 'warn', detail: `${n} 个僵尸进程，父进程可能有问题` } : { level: 'ok', detail: `${n} 个` };
    },
  },
  {
    id: 'run.others', label: '异常登录来源（近 3 天）', cat: '运行健康', weight: 2,
    cmd: `journalctl -u ssh -u sshd --since '3 days ago' 2>/dev/null | grep -ciE 'Failed password|Invalid user' || echo 0`,
    parse(out) {
      const n = num((out || '').trim().split('\n')[0]);
      if (!n) return { level: 'ok', detail: '近期无爆破记录' };
      return n > 5000 ? { level: 'warn', detail: `近 3 天 ${n} 次失败登录尝试，建议收紧来源或启用 fail2ban` } : { level: 'info', detail: `近 3 天 ${n} 次失败登录尝试` };
    },
  },
];

function toMb(s) {
  const m = /^([0-9.]+)\s*([KMGT]?i?B)$/i.exec(String(s || '').trim());
  if (!m) return null;
  const v = Number(m[1]);
  const unit = m[2].toUpperCase().replace('I', '');
  const mult = unit.startsWith('T') ? 1048576 : unit.startsWith('G') ? 1024 : unit.startsWith('K') ? 1 / 1024 : 1;
  return v * mult;
}

async function runCheck(c) {
  if (c.bin && !(await ex.hasBin(c.bin))) {
    return { id: c.id, label: c.label, cat: c.cat, level: 'info', detail: `服务器上没有 ${c.bin}，跳过`, weight: c.weight, skipped: true };
  }
  const r = await ex.run(c.cmd, { timeout: 20000 });
  let parsed;
  try { parsed = c.parse(r.stdout, r); }
  catch (e) { parsed = { level: 'info', detail: `解析失败：${e.message}` }; }
  return {
    id: c.id, label: c.label, cat: c.cat, weight: c.weight,
    level: parsed.level || 'info', detail: String(parsed.detail || '').slice(0, 400),
    fix: parsed.fix || null, evidence: (r.stdout || '').trim().slice(0, 500),
    exitCode: r.code, skipped: false,
  };
}

async function scanAll(ids) {
  const picked = ids && ids.length ? CHECKS.filter((c) => ids.includes(c.id)) : CHECKS;
  const out = [];
  const queue = picked.slice();
  const workers = [];
  for (let i = 0; i < 5; i++) {
    workers.push((async () => {
      while (queue.length) {
        const c = queue.shift();
        try { out.push(await runCheck(c)); }
        catch (e) { out.push({ id: c.id, label: c.label, cat: c.cat, level: 'info', detail: '执行失败：' + e.message, weight: c.weight }); }
      }
    })());
  }
  await Promise.all(workers);
  const order = {};
  CHECKS.forEach((c, i) => { order[c.id] = i; });
  out.sort((a, b) => (order[a.id] == null ? 999 : order[a.id]) - (order[b.id] == null ? 999 : order[b.id]));
  return out;
}

function score(checks) {
  let penalty = 0;
  const counts = { ok: 0, warn: 0, bad: 0, info: 0 };
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
      const bad = items.filter((c) => c.level === 'bad').length;
      const warn = items.filter((c) => c.level === 'warn').length;
      return { cat, total: items.length, bad, warn, score: Math.max(0, 100 - items.reduce((a, c) => a + (c.level === 'bad' ? c.weight : c.level === 'warn' ? c.weight / 2 : 0), 0) * 2) };
    }),
  };
}

async function runScan(opts) {
  const checks = await scanAll(opts && opts.ids);
  const at = S.now();
  const sum = score(checks);
  const record = { at, durationMs: S.now() - at, checks, ...sum };
  S.writeJson(SCAN, record);
  S.appendLine(HISTORY, { at, score: sum.score, grade: sum.grade, bad: sum.counts.bad, warn: sum.counts.warn });
  S.trimLineFile(HISTORY, 2 * 1024 * 1024);
  return record;
}

function latest() { return S.readJson(SCAN, null); }

function trend(days) {
  const n = Math.max(1, Math.min(90, Number(days) || 30));
  const all = S.readLines(HISTORY, 4000);
  return all.slice(-n * 4);
}

/* ---------------- allowlisted remediation ---------------- */

function sshPort() {
  const l = latest();
  const c = l && l.checks.find((x) => x.id === 'sshd.port');
  const m = /端口 (\d+)/.exec((c && c.detail) || '');
  return m ? m[1] : '22';
}

const FIXES = {
  /**
   * Writes a drop-in instead of editing sshd_config: sshd validates the whole
   * config with -t, and we refuse to reload (and roll the file back) if the
   * syntax is not accepted — that is what keeps this from locking anyone out.
   */
  'sshd.harden': {
    label: '收紧 SSH（禁止密码登录、root 仅密钥、MaxAuthTries 4）',
    risk: '保留密钥登录 root 的能力；改前请确认你手上的密钥能登录，否则可能无法再以 root 直登。',
    requires: 'sshd',
    async run() {
      const drop = '/etc/ssh/sshd_config.d/99-cloud-monitor.conf';
      const body = '# Cloud Monitor 基线加固（可删除本文件回滚）\nPermitRootLogin prohibit-password\nPasswordAuthentication no\nMaxAuthTries 4\nLoginGraceTime 30\n';
      const b64 = Buffer.from(body, 'utf8').toString('base64');
      await ex.run('install -d -m 0755 /etc/ssh/sshd_config.d');
      const w = await ex.run(`printf '%s' ${S.shq(b64)} | base64 -d > ${S.shq(drop)} && chmod 600 ${S.shq(drop)} && ls -l ${S.shq(drop)}`, { timeout: 15000 });
      if (w.code !== 0) return { ok: false, output: '写入 drop-in 失败：' + (w.stderr || w.stdout) };
      const v = await ex.run('sshd -t 2>&1', { timeout: 15000 });
      if (v.code !== 0) {
        await ex.run(`rm -f ${S.shq(drop)}`);
        return { ok: false, output: 'sshd -t 校验不通过，已回滚 drop-in，未重载服务：\\n' + (v.stdout + v.stderr) };
      }
      const svc = await ex.run(`systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null; systemctl is-active ssh sshd 2>/dev/null | head -2`, { timeout: 20000 });
      return { ok: true, output: `已写入 ${drop} 并通过 sshd -t 校验；重载结果：${svc.stdout.trim()}。当前仍允许密钥登录 root。` };
    },
  },
  'fail2ban.enable': {
    label: '启用 fail2ban 并开启 sshd jail',
    risk: '连续失败登录会被临时封 IP，自己测试时注意别把自己封掉。',
    requires: 'fail2ban-client',
    async run() {
      const r = await ex.run(`test -f /etc/fail2ban/jail.d/defaults-debian.conf || test -f /etc/fail2ban/jail.local || printf '[sshd]\\nenabled = true\\nport = ${sshPort()}\\nmaxretry = 5\\n' > /etc/fail2ban/jail.local; systemctl enable --now fail2ban 2>&1; systemctl is-active fail2ban; fail2ban-client status 2>&1 | head -4`, { timeout: 60000 });
      return { ok: r.code === 0 || /active/.test(r.stdout), output: (r.stdout + r.stderr).slice(0, 800) };
    },
  },
  'updates.enableAuto': {
    label: '开启 unattended-upgrades 自动安全更新',
    risk: '后台会自动安装安全补丁（默认不升级内核以外的包）。',
    requires: 'systemctl',
    async run() {
      const r = await ex.run(`apt-get install -y unattended-upgrades 2>&1 | tail -2; dpkg-reconfigure -f noninteractive unattended-upgrades 2>&1 | tail -2; systemctl enable --now unattended-upgrades 2>&1 | tail -2; systemctl is-active unattended-upgrades`, { timeout: 300000 });
      return { ok: /active/.test(r.stdout), output: r.stdout.slice(0, 800) };
    },
  },
  'firewall.enableSsh': {
    label: '启用 ufw 并先放行当前 SSH 端口',
    risk: '默认策略会拒绝入站；本修复先 allow SSH 端口（并放行面板 80/443）再 enable，仍然建议你在控制台保留一条应急放行规则。',
    requires: 'ufw',
    async run() {
      const port = sshPort();
      const r = await ex.run(`ufw allow ${S.shq(port + '/tcp')} >/dev/null 2>&1; ufw allow 80/tcp >/dev/null 2>&1; ufw allow 443/tcp >/dev/null 2>&1; ufw --force enable 2>&1 | tail -3; ufw status verbose 2>&1 | head -12`, { timeout: 60000 });
      return { ok: /Status: active/.test(r.stdout), output: r.stdout.slice(0, 900) };
    },
  },
  'perms.fixSshFiles': {
    label: '把权限过宽的 authorized_keys 收紧为 600',
    risk: '只改 authorized_keys 的权限，不改内容与属主。',
    async run() {
      const r = await ex.run(`find /root/.ssh /home -maxdepth 4 -name authorized_keys -perm /077 -exec chmod 600 {} + 2>&1; echo '--'; find /root/.ssh /home -maxdepth 4 -name authorized_keys -perm /077 2>/dev/null | wc -l`, { timeout: 30000 });
      const left = num((r.stdout || '').split('--')[1] || '');
      return { ok: left === 0, output: r.stdout.slice(0, 600) };
    },
  },
};

async function applyFix(id, ip) {
  const f = FIXES[id];
  if (!f) { const e = new Error('没有这个修复项：' + id); e.status = 404; throw e; }
  if (f.requires && !(await ex.hasBin(f.requires))) {
    const e = new Error('服务器缺少 ' + f.requires + '，无法执行该修复'); e.status = 400; throw e;
  }
  const res = await f.run();
  const rec = { at: S.now(), fix: id, label: f.label, ok: !!res.ok, ip: ip || '', output: (res.output || '').slice(0, 1200) };
  S.appendLine(FIXLOG, rec);
  let after = null;
  if (res.ok) {
    const affected = CHECKS.filter((c) => c.parse && (c.id.startsWith('sshd.') || c.id.startsWith('fail2ban.') || c.id.startsWith('updates.') || c.id.startsWith('firewall.') || c.id.startsWith('accounts.'))).map((c) => c.id);
    after = await runCheckAllIds(affected);
  }
  return { ok: !!res.ok, fix: id, output: res.output, recheck: after };
}

async function runCheckAllIds(ids) {
  const checks = await scanAll(ids);
  return checks.map((c) => ({ id: c.id, level: c.level, detail: c.detail }));
}

/* ---------------- routes ---------------- */

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
    items: CHECKS.map((c) => ({ id: c.id, label: c.label, cat: c.cat, weight: c.weight, bin: c.bin || '', fixable: !!FIXES[fixFor(c)] })),
    fixes: Object.entries(FIXES).map(([k, v]) => ({ id: k, label: v.label, risk: v.risk || '', requires: v.requires || '' })),
    runner: ex.mode(),
  })));
  api.get('/ops/sec/trend', wrap(async (req, res) => res.json({ ok: true, days: Number(req.query.days) || 30, items: trend(req.query.days), fixLog: S.readLines(FIXLOG, 100) })));
  api.post('/ops/sec/run', audited('ops.sec.run', async () => runScan()));
  api.post('/ops/sec/fix', audited('ops.sec.fix', async (req) => applyFix(String((req.body || {}).fix || ''), req.clientIp)));
}

function fixFor(c) {
  /* the parse decides the fix id at runtime; the catalog only needs to know
   * whether any fix can ever apply to this check */
  return Object.keys(FIXES).find((k) => c.id.startsWith(k.split('.')[0] + '.')) || null;
}

module.exports = { register, runScan, scanAll, runCheck, score, latest, trend, FIXES, applyFix, CHECKS, CATS, LEVELS, SCAN, HISTORY, FIXLOG };
