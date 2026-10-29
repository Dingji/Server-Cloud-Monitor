'use strict';
/* Log sources: safe resolution of log tail/follow commands. */
const UNIT_RE = /^[a-zA-Z0-9@_.\-]+\.(service|timer|socket)$/;

const FILE_SOURCES = [
  { id: 'syslog', label: '系统日志 /var/log/syslog', file: '/var/log/syslog' },
  { id: 'auth', label: '认证日志 /var/log/auth.log', file: '/var/log/auth.log' },
  { id: 'kern', label: '内核日志 /var/log/kern.log', file: '/var/log/kern.log' },
  { id: 'dpkg', label: '软件包日志 /var/log/dpkg.log', file: '/var/log/dpkg.log' },
  { id: 'nginx-access', label: 'Nginx 访问日志', file: '/var/log/nginx/access.log' },
  { id: 'nginx-error', label: 'Nginx 错误日志', file: '/var/log/nginx/error.log' },
  { id: 'cron', label: '计划任务日志 /var/log/cron.log', file: '/var/log/cron.log' },
];

function resolveSource(id) {
  id = String(id || '');
  if (process.platform === 'win32') {
    if (id === 'dmesg' || id === 'journal') {
      return {
        tail: 'powershell -NoProfile -Command "Get-EventLog -LogName System -Newest 300 -ErrorAction SilentlyContinue | Format-List"',
        follow: 'powershell -NoProfile -Command "while($true){Get-EventLog -LogName System -Newest 5 -After (Get-Date).AddSeconds(-5) -ErrorAction SilentlyContinue | Format-List; Start-Sleep -Seconds 2}"',
      };
    }
    const wf = WIN_FILE_SOURCES.find((s) => s.id === id);
    if (wf) {
      const log = wf.file;
      return {
        tail: `powershell -NoProfile -Command "Get-EventLog -LogName ${log} -Newest 300 -ErrorAction SilentlyContinue | Format-List"`,
        follow: `powershell -NoProfile -Command "while($true){Get-EventLog -LogName ${log} -Newest 5 -After (Get-Date).AddSeconds(-5) -ErrorAction SilentlyContinue | Format-List; Start-Sleep -Seconds 2}"`,
      };
    }
    const d = /^docker:([a-f0-9]{6,64})$/i.exec(id);
    if (d) {
      const cid = d[1];
      return { tail: `docker logs --tail 300 ${cid} 2>&1`, follow: `docker logs -f --tail 100 ${cid} 2>&1` };
    }
    return null;
  }
  if (id === 'dmesg') {
    return { tail: 'dmesg -T --color=never 2>/dev/null | tail -n 300', follow: 'dmesg -T --color=never -w 2>/dev/null' };
  }
  const f = FILE_SOURCES.find((s) => s.id === id);
  if (f) return { tail: `tail -n 300 ${f.file} 2>/dev/null`, follow: `tail -n 100 -F ${f.file} 2>/dev/null` };
  const m = /^journal:(.+)$/.exec(id);
  if (m && UNIT_RE.test(m[1])) {
    const unit = m[1];
    return { tail: `journalctl -u ${unit} -n 300 --no-pager -o short-iso 2>/dev/null`, follow: `journalctl -u ${unit} -f -n 100 --no-pager -o short-iso 2>/dev/null` };
  }
  if (id === 'journal') {
    return { tail: 'journalctl -n 300 --no-pager -o short-iso 2>/dev/null', follow: 'journalctl -f -n 100 --no-pager -o short-iso -p warning 2>/dev/null' };
  }
  const d = /^docker:([a-f0-9]{6,64})$/i.exec(id);
  if (d) {
    const cid = d[1];
    return { tail: `docker logs --tail 300 ${cid} 2>&1`, follow: `docker logs -f --tail 100 ${cid} 2>&1` };
  }
  return null;
}

const WIN_FILE_SOURCES = [
  { id: 'app', label: '应用程序日志', file: 'Application' },
  { id: 'system', label: '系统日志', file: 'System' },
  { id: 'security', label: '安全日志', file: 'Security' },
];

function listSources() {
  if (process.platform === 'win32') {
    return {
      files: WIN_FILE_SOURCES,
      special: [{ id: 'dmesg', label: '事件查看器 (最近错误)' }, { id: 'journal', label: 'Windows 系统日志 (warning+)' }],
    };
  }
  return { files: FILE_SOURCES, special: [{ id: 'dmesg', label: '内核环形缓冲 (dmesg)' }, { id: 'journal', label: '系统 journal (warning+)' }] };
}

module.exports = { resolveSource, listSources };
