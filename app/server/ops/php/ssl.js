'use strict';
/*
 * HTTPS via Let's Encrypt.
 *
 * We use `certbot certonly --webroot` rather than `--nginx` so the panel stays
 * the single author of every nginx file it manages; certbot never edits a config
 * it does not own. The HTTP-01 challenge path is served straight from the
 * filesystem in the generated server block (see site.js).
 */
const fs = require('fs');
const C = require('./common');
const site = require('./site');
const jobs = require('./jobs');

const S = C.S;
const ok = C.ok;
const LIVE = '/etc/letsencrypt/live';

async function haveCertbot() {
  const r = await C.sh('command -v certbot >/dev/null 2>&1 && echo YES || echo NO', { timeout: 8000 });
  return /YES/.test(r.stdout);
}

/** All certificates on the machine, parsed from `certbot certificates`. */
async function all() {
  if (!await haveCertbot()) return { certbotInstalled: false, certificates: [] };
  const r = await C.sh('certbot certificates 2>/dev/null', { timeout: 60000 }).catch(() => ({ stdout: '' }));
  const certs = [];
  let cur = null;
  const FIELD = /^ {3}(Serial|Domain\(s\)|Certificate Path|Private Key Path|Expiry Date): (.*)$/;
  for (const line of String(r.stdout).split('\n')) {
    const name = /^ - Name: (.*)$/.exec(line);
    if (name) { if (cur) certs.push(cur); cur = { name: name[1].trim() }; continue; }
    if (!cur) continue;
    const m = FIELD.exec(line);
    if (!m) continue;
    const value = m[2].trim();
    if (m[1] === 'Serial') cur.serial = value;
    else if (m[1] === 'Domain(s)') cur.domains = value.split(/\s+/).filter(Boolean);
    else if (m[1] === 'Certificate Path') cur.certPath = value;
    else if (m[1] === 'Private Key Path') cur.keyPath = value;
    else if (m[1] === 'Expiry Date') {
      cur.expiryText = value;
      const d = /\((\d{4}-\d{2}-\d{2})/.exec(value);
      if (d) {
        cur.expiry = d[1];
        cur.daysLeft = Math.floor((new Date(d[1] + 'T23:59:59').getTime() - Date.now()) / 86400000);
      }
    }
  }
  if (cur) certs.push(cur);
  return { certbotInstalled: true, certificates: certs };
}

async function statusFor(rec) {
  const { certbotInstalled, certificates } = await all();
  const host = (rec.domains || [])[0];
  const cert = host ? certificates.find((c) => (c.domains || []).includes(host)) : null;
  return {
    certbotInstalled: certbotInstalled,
    ssl: !!rec.ssl,
    cert: cert ? { name: cert.name, expiry: cert.expiry, daysLeft: cert.daysLeft, domains: cert.domains } : null,
    /* the panel expects the line age to match the contract used by collectors.getWebServers() */
    expiry: cert ? cert.expiry : null,
    daysLeft: cert ? cert.daysLeft : null,
    canIssue: !!(rec.domains || []).length && certbotInstalled,
    reason: !certbotInstalled ? '未安装 certbot'
      : !(rec.domains || []).length ? '站点没有域名（纯 IP/端口无法签发 Let\'s Encrypt 证书）'
        : null,
  };
}

/** HTTP-01 needs the domain to actually resolve to this machine. */
async function resolveCheck(domains) {
  const out = {};
  const localIp = await C.sh('curl -fsS --max-time 8 https://ifconfig.me 2>/dev/null || ' +
    'curl -fsS --max-time 8 http://100.100.100.200/latest/meta-data/eipv4 2>/dev/null || hostname -I | cut -d" " -f1',
    { timeout: 30000 }).then((r) => String(r.stdout).trim()).catch(() => '');
  for (const d of domains) {
    if (!/^[a-z0-9._-]{1,253}$/i.test(d)) { out[d] = { ok: false, error: '域名格式不正确' }; continue; }
    const r = await C.sh('getent ahostsv4 ' + S.shq(d) + ' 2>/dev/null | awk \'{print $1}\' | sort -u | head -4',
      { timeout: 20000 }).catch(() => ({ stdout: '' }));
    const ips = String(r.stdout).split('\n').map((x) => x.trim()).filter(Boolean);
    out[d] = {
      resolves: ips.length > 0,
      ips: ips,
      matches: localIp ? ips.includes(localIp) : null,
      publicIp: localIp || null,
    };
  }
  return out;
}

function issueJob(body) {
  const rec = C.get(body.slug);
  const domains = C.domains(body.domains && body.domains.length ? body.domains : rec.domains);
  if (!domains.length) C.bad('站点没有域名，无法签发证书。纯 IP 访问请只用 HTTP，或先绑定已解析的域名。');
  const email = String(body.email || '').trim();
  if (email && !/^[^@\s]{1,64}@[^@\s.]{1,200}$/.test(email)) C.bad('邮箱格式不正确：' + email);

  const job = jobs.start({
    kind: 'ssl.issue', site: rec.slug, title: rec.slug + ' — 签发 HTTPS 证书',
    run: async (j) => {
      j.set(5, 'check', '检查 certbot 与域名解析');
      if (!await haveCertbot()) {
        j.log('certbot 未安装，先安装');
        await j.drain('DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y certbot python3-certbot-nginx 2>&1',
          { timeout: 12 * 60000, failMsg: 'certbot 安装失败' });
      }
      const dns = await resolveCheck(domains);
      for (const d of domains) j.log('DNS ' + d + ' -> ' + (dns[d].ips.join(', ') || '解析失败') +
        (dns[d].matches === false ? '  ⚠ 与本机公网 IP ' + dns[d].publicIp + ' 不一致' : ''));
      const unresolved = domains.filter((d) => !dns[d].resolves);
      if (unresolved.length) {
        throw new Error('以下域名无法解析，HTTP-01 验证必然失败：' + unresolved.join(', ') +
          '\n请先在 DNS 服务商处把 A 记录指到本机公网 IP。');
      }

      j.set(20, 'prepare', '确保 80 端口与 ACME 路径可用');
      if (!(rec.ports || []).includes(80)) {
        rec.ports = [80].concat(rec.ports || []);
        C.put(rec);
        await site.apply(rec);
        j.log('已为站点补上 80 监听（验证需要）');
      } else {
        await site.apply(rec);
      }
      const webroot = C.htdocsLink(rec.slug);
      await C.sh('mkdir -p ' + S.shq(webroot + '/.well-known/acme-challenge'), { timeout: 10000 });

      j.set(35, 'issue', '向 Let\'s Encrypt 申请证书');
      const args = domains.map((d) => ' -d ' + S.shq(d)).join('');
      const contact = email ? '--email ' + S.shq(email) : '--no-eff-email';
      const r = await j.drain('certbot certonly --webroot -w ' + S.shq(webroot) + args +
        ' --non-interactive --agree-tos ' + contact +
        ' --keep-until-expiring 2>&1',
        { timeout: 5 * 60000, failMsg: 'certbot 签发失败' });
      j.log(String(r.stdout || '').split('\n').slice(-30).join('\n'));

      j.set(70, 'apply', '写入 443 监听与 HTTP→HTTPS 跳转');
      rec.ssl = true;
      rec.sslDomains = domains;
      rec.sslEmail = email || rec.sslEmail;
      rec.updatedAt = new Date().toISOString();
      C.put(rec);
      C.saveMeta(rec);
      const applied = await site.apply(rec);
      j.log('nginx=' + applied.nginx + ' reload=' + (applied.reload || '').slice(0, 80));

      j.set(85, 'firewall', '开放 443');
      await j.drain('ufw status 2>/dev/null | grep -q "443/tcp" || ufw allow 443/tcp comment "panel-php https" 2>&1 | tail -2',
        { timeout: 30000 }).catch((e) => j.log('ufw 放行 443 失败（若为云安全组需在控制台放行）：' + e.message));

      j.set(92, 'renewal', '确认自动续期任务');
      const timer = await C.sh('systemctl is-enabled certbot.timer 2>/dev/null || echo unknown; ' +
        'ls /etc/cron.d/certbot 2>/dev/null || true', { timeout: 15000 }).catch(() => ({ stdout: '' }));
      j.log('续期：' + String(timer.stdout).trim().replace(/\n/g, ' | '));

      const st = await statusFor(rec);
      j.set(100, 'done', '完成');
      return '证书已就绪：' + domains.join(', ') +
        (st.cert ? '\n到期 ' + st.cert.expiry + '（剩 ' + st.cert.daysLeft + ' 天）' : '') +
        '\n访问地址 https://' + domains[0] + '/';
    },
  });
  return ok('签发任务已启动', { job: jobs.view(job) });
}

async function renew(body) {
  if (!await haveCertbot()) C.bad('未安装 certbot');
  const r = await C.sh('certbot renew --non-interactive 2>&1 | tail -25', { timeout: 10 * 60000 });
  const text = String(r.text || '');
  if (/nginx: \[error\]|Error|Traceback/.test(text) && !/no renewals/.test(text)) {
    const e = new Error('certbot renew 报错');
    e.output = text;
    throw e;
  }
  // reload once so any renewed cert is picked up by the live workers
  await C.sh('systemctl reload nginx 2>&1 || nginx -s reload 2>&1', { timeout: 20000 }).catch(() => {});
  const { certificates } = await all();
  const soon = certificates.filter((c) => c.daysLeft !== undefined && c.daysLeft < 30);
  return ok('续期检查完成' + (soon.length ? '\n仍将在 30 天内到期：' + soon.map((c) => c.name + ' (' + c.daysLeft + '天)').join(', ')
    : '\n所有证书到期时间均 > 30 天') + '\n' + text);
}

async function disable(body) {
  const rec = C.get(body.slug);
  if (!rec.ssl) return ok('站点 ' + rec.slug + ' 本来就未启用 HTTPS');
  rec.ssl = false;
  rec.updatedAt = new Date().toISOString();
  C.put(rec);
  C.saveMeta(rec);
  const applied = await site.apply(rec);
  return ok('站点 ' + rec.slug + ' 已关闭 HTTPS（证书文件保留在 /etc/letsencrypt，未删除）\n' + applied.nginx);
}

/** Only called when a site is deleted, and only for a cert the panel issued. */
async function forgetDomain(rec) {
  if (!rec.ssl || !await haveCertbot()) return;
  const host = (rec.sslDomains && rec.sslDomains[0]) || (rec.domains || [])[0];
  if (!host) return;
  const { certificates } = await all();
  if (!certificates.some((c) => (c.domains || []).includes(host))) return;
  await C.sh('certbot delete --non-interactive --cert-name ' + S.shq(host) + ' 2>&1 | tail -5',
    { timeout: 60000 }).catch(() => {});
  // if another site still uses that name, the reload below would 500 — re-test
  await C.sh('nginx -t 2>&1 >/dev/null || true', { timeout: 20000 }).catch(() => {});
}

async function renewTimer() {
  const r = await C.sh('systemctl list-timers --no-pager 2>/dev/null | grep -i certbot | head -2', { timeout: 15000 })
    .catch(() => ({ stdout: '' }));
  const line = String(r.stdout).trim();
  if (line) return { enabled: true, detail: line.split('\t')[0] || line.slice(0, 120) };
  const cron = C.exists('/etc/cron.d/certbot');
  return { enabled: cron, detail: cron ? '/etc/cron.d/certbot' : '未发现 certbot 定时器' };
}

module.exports = { haveCertbot, all, statusFor, resolveCheck, issueJob, renew, disable, forgetDomain, renewTimer, LIVE };
