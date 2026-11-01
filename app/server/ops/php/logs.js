'use strict';
/*
 * Per-site logs and traffic.
 *
 * nginx writes the combined format, so the stats are parsed with one awk pass
 * over the tail of the file — no log pipeline, nothing to install, bounded cost
 * even when an access log has run to a few hundred MB.
 */
const fs = require('fs');
const C = require('./common');

const ok = C.ok;
const S = C.S;
const TYPES = ['access', 'error', 'deploy', 'slow'];
const US = '\u001f';

const fileFor = (rec, type) => ({
  access: C.logsDir(rec.slug) + '/access.log',
  error: C.logsDir(rec.slug) + '/error.log',
  deploy: C.logsDir(rec.slug) + '/deploy.log',
  slow: C.logsDir(rec.slug) + '/php-slow.log',
}[type] || null);

async function sizes(rec) {
  return Promise.all(TYPES.map(async (t) => {
    const f = fileFor(rec, t);
    let bytes = 0, mtime = null;
    try { const st = fs.statSync(f); bytes = st.size; mtime = st.mtime.toISOString(); } catch (_) {}
    return { type: t, path: f, bytes: bytes, mtime: mtime };
  }));
}

async function tail(rec, type, linesRaw, grep) {
  if (!TYPES.includes(type)) C.bad('未知日志类型：' + type + '（可选 ' + TYPES.join('/') + '）');
  const file = fileFor(rec, type);
  const lines = Math.max(20, Math.min(5000, Number(linesRaw) || 200));
  if (!fs.existsSync(file)) return { ok: true, type: type, path: file, exists: false, lines: [], bytes: 0 };
  const filter = grep ? ' | grep -i ' + S.shq(String(grep).slice(0, 120)) + ' || true' : '';
  const r = await C.sh('tail -n ' + lines + ' ' + S.shq(file) + filter + ' 2>&1',
    { timeout: 20000, maxBuffer: 32 * 1024 * 1024 });
  return {
    ok: true, type: type, path: file, exists: true,
    bytes: (function () { try { return fs.statSync(file).size; } catch (_) { return 0; } })(),
    lines: String(r.stdout).split('\n').slice(-lines),
  };
}

/**
 * One awk pass over the last N lines of the combined log.
 * Returns totals, status classes, per-hour series, top URLs / referrers / agents / IPs.
 */
async function stats(rec, opts) {
  const o = opts || {};
  const file = fileFor(rec, 'access');
  if (!fs.existsSync(file)) {
    return { ok: true, exists: false, requests: 0, note: '还没有访问日志（站点尚未被访问）' };
  }
  const scan = Math.max(1000, Math.min(400000, Number(o.scan) || 100000));
  /* combined format, split on the quote character:
       $1 = 'ip - user [time] '   $2 = 'METHOD path PROTO'
       $3 = ' status bytes '      $4 = referer   $6 = user agent
   Only POSIX-mawk features are used — gawk's 3-arg match() is not available here. */
  const awk = [
    'BEGIN{FS="\\""}',
    'NF>=3 {',
    '  split($1,a," "); ip=a[1];',
    '  t3=$3; sub(/^ +/,"",t3); split(t3,c," "); code=c[1]+0; bts=c[2]+0;',
    '  path=$2; sub(/^[A-Z]+ /,"",path); sub(/ HTTP.*/,"",path); sub(/\\?.*/,"",path);',
    '  n++; bytes+=bts;',
    '  if(code>=500) s5++; else if(code>=400) s4++; else if(code>=300) s3++; else if(code>0) s2++;',
    '  ipn[ip]++; urln[path]++;',
    '  if($4!="" && $4!="-") { ref=$4; sub(/^https?:\\/\\/[^\\/]*/,"",ref); refn[ref]++ }',
    '  if($6!="" && $6!="-") agn[$6]++;',
    '  t=$1; if(match(t,/\\[/)) { h=substr(t,RSTART+13,2); if(h ~ /[0-9][0-9]/) hrn[h]++ }',
    '}',
    'END{',
    '  printf "TOTAL' + US + '%d' + US + '%d' + US + '%d' + US + '%d' + US + '%d' + US + '%d\\n", n, s2, s3, s4, s5, bytes;',
    '  for(k in hrn) printf "H' + US + '%s' + US + '%d\\n", k, hrn[k];',
    '  for(k in urln) printf "U' + US + '%s' + US + '%d\\n", k, urln[k];',
    '  for(k in ipn) printf "I' + US + '%s' + US + '%d\\n", k, ipn[k];',
    '  for(k in refn) printf "R' + US + '%s' + US + '%d\\n", k, refn[k];',
    '  for(k in agn) printf "A' + US + '%s' + US + '%d\\n", k, agn[k];',
    '}',
  ].join('\n');
  const tmp = '/tmp/panel-php-awk-' + rec.slug.replace(/[^a-z0-9-]/gi, '') + '.awk';
  const cmd = 'printf %s ' + S.shq(awk) + ' > ' + S.shq(tmp) +
    ' && tail -n ' + scan + ' ' + S.shq(file) + ' | awk -f ' + S.shq(tmp) + ' 2>&1 | head -4000';
  const r = await C.sh(cmd, { timeout: 40000, maxBuffer: 32 * 1024 * 1024 });
  await C.sh('rm -f ' + S.shq(tmp), { timeout: 5000 }).catch(() => {});
  const out = {
    ok: true, exists: true, path: file, scanned: scan,
    requests: 0, byClass: { ok: 0, redirect: 0, clientError: 0, serverError: 0 }, bytes: 0,
    hours: [], topUrls: [], topIps: [], topRefs: [], topAgents: [],
  };
  for (const line of String(r.stdout).split('\n')) {
    const i = line.indexOf(US);
    if (i < 0) continue;
    const tag = line.slice(0, i);
    const rest = line.slice(i + 1);
    const parts = rest.split(US);
    if (tag === 'TOTAL') {
      out.requests = Number(parts[0]) || 0;
      out.byClass = {
        ok: Number(parts[1]) || 0, redirect: Number(parts[2]) || 0,
        clientError: Number(parts[3]) || 0, serverError: Number(parts[4]) || 0,
      };
      out.bytes = Number(parts[5]) || 0;
    } else if (tag === 'H') out.hours.push({ hour: parts[0], count: Number(parts[1]) || 0 });
    else if (tag === 'U') out.topUrls.push({ path: parts[0], count: Number(parts[1]) || 0 });
    else if (tag === 'I') out.topIps.push({ ip: parts[0], count: Number(parts[1]) || 0 });
    else if (tag === 'R') out.topRefs.push({ ref: parts[0], count: Number(parts[1]) || 0 });
    else if (tag === 'A') out.topAgents.push({ agent: parts[0], count: Number(parts[1]) || 0 });
  }
  const rank = (arr) => arr.slice().sort((a, b) => b.count - a.count).slice(0, 10);
  out.topUrls = rank(out.topUrls);
  out.topIps = rank(out.topIps);
  out.topRefs = rank(out.topRefs);
  out.topAgents = rank(out.topAgents);
  out.hours.sort((a, b) => String(a.hour).localeCompare(String(b.hour)));
  out.errorRate = out.requests ? +(((out.byClass.clientError + out.byClass.serverError) / out.requests) * 100).toFixed(2) : 0;
  const errs = await tail(rec, 'error', 40).catch(() => ({ lines: [] }));
  out.recentErrors = errs.lines.slice(-12);
  return out;
}

/** Rotate: gzip what we have, ask nginx to reopen, keep the last N archives. */
async function rotate(body) {
  const rec = C.get(body.slug);
  const type = String(body.type || 'access');
  if (!['access', 'error', 'slow'].includes(type)) C.bad('只允许轮转 access/error/slow 日志');
  const file = fileFor(rec, type);
  if (!fs.existsSync(file)) C.bad('日志文件不存在：' + file, 404);
  const st = fs.statSync(file);
  if (st.size < 1024) return ok('日志不足 1KB，无需轮转');
  const stamp = C.tsName();
  const r = await C.sh([
    'mv ' + S.shq(file) + ' ' + S.shq(file + '.' + stamp + '.old'),
    'gzip -9 ' + S.shq(file + '.' + stamp + '.old') + ' &',
    'systemctl reopen nginx 2>/dev/null || nginx -s reopen 2>/dev/null || kill -USR1 $(cat /run/nginx.pid 2>/dev/null) 2>/dev/null || true',
    'sleep 1',
    'ls -1 ' + S.shq(C.logsDir(rec.slug)) + '/*.gz 2>/dev/null | head -n -20 | while read f; do rm -f "$f"; done',
  ].join('; '), { timeout: 60000 });
  C.logEvent({ kind: 'logs.rotate', site: rec.slug, type: type, bytes: st.size });
  return ok('已轮转 ' + type + ' 日志（原 ' + Math.round(st.size / 1024) + ' KB → ' +
    fileFor(rec, type) + '.' + stamp + '.gz），nginx 已重新打开日志文件' + (r.text ? '\n' + r.text : ''));
}

async function truncate(body) {
  const rec = C.get(body.slug);
  const type = String(body.type || 'access');
  const file = fileFor(rec, type);
  if (!file) C.bad('未知日志类型');
  if (!fs.existsSync(file)) C.bad('文件不存在：' + file, 404);
  const before = fs.statSync(file).size;
  await C.sh(': > ' + S.shq(file), { timeout: 20000 });
  C.logEvent({ kind: 'logs.truncate', site: rec.slug, type: type, bytes: before });
  return ok('已清空 ' + type + ' 日志（释放 ' + Math.round(before / 1024) + ' KB）');
}

/** `nginx -T` style view of what is actually loaded for this site. */
async function effectiveConf(rec) {
  const r = await C.sh('nginx -T 2>/dev/null | awk \'/panel-php-' + rec.slug.replace(/[^a-z0-9-]/gi, '') +
    '\\.conf/,/^# configuration file/\' | head -300', { timeout: 30000 })
    .catch(() => ({ stdout: '' }));
  return String(r.stdout);
}

module.exports = { TYPES, fileFor, sizes, tail, stats, rotate, truncate, effectiveConf };
