'use strict';
if (process.platform === 'win32') {
  module.exports = require('./svc-win');
  return;
}
/*
 * Service control that works on systemd and on non-systemd init
 * (OpenRC / SysV / plain containers where systemctl may be absent).
 *
 * Every helper returns a shell command string; nothing is executed here.
 */
const { getAgent } = require('./agent');

let mode = { exp: 0, systemd: null, serviceBin: null };

async function detect() {
  if (mode.exp > Date.now()) return mode;
  const agent = getAgent();
  const has = async (c) => {
    try { const r = await agent.run(`command -v ${c} 2>/dev/null`, { timeout: 8000 }); return !!String(r.stdout || '').trim(); }
    catch (_) { return false; }
  };
  const [systemd, service, rc] = await Promise.all([has('systemctl'), has('service'), has('rc-service')]);
  mode = {
    exp: Date.now() + 300000,
    systemd,
    serviceBin: service ? 'service' : (rc ? 'rc-service' : null),
    init: rc ? 'openrc' : (systemd ? 'systemd' : 'sysvinit'),
  };
  return mode;
}

/** single-quote escape for shell */
function q(s) { return "'" + String(s == null ? '' : s).replace(/'/g, "'\\''") + "'"; }

/** Validate a systemd unit name; must be conservative (no shell metachars). */
const UNIT_RE = /^[A-Za-z0-9@_.\-:]{1,120}$/;

function svcCmd(action, unit, opts = {}) {
  const u = q(unit);
  if (!UNIT_RE.test(String(unit))) return 'echo "invalid unit name" >&2; false';
  const isActive = opts.systemd === false
    ? `(${opts.init === 'openrc' ? `rc-status ${u} 2>/dev/null | grep -q "started"` : `service ${u} status 2>&1 | grep -q -E "running|active"`})`
    : `(systemctl is-active ${u} 2>/dev/null)`;
  const isEnabled = opts.systemd === false
    ? `(${opts.init === 'openrc' ? `rc-update show 2>/dev/null | grep -q "^ *${unit} "` : `ls /etc/rc*.d/*${unit} >/dev/null 2>&1`})`
    : `(systemctl is-enabled ${u} 2>/dev/null)`;

  const S = (verb) => `systemctl ${verb} ${u} 2>&1; ${isActive} && echo OK || echo FAILED`;
  const B = (verb, bin) => `${bin} ${u} ${verb} 2>&1; ${isActive} && echo OK || echo FAILED`;

  switch (action) {
    case 'start': return opts.systemd === false ? B('start') : S('start');
    case 'stop': return opts.systemd === false ? B('stop') : S('stop');
    case 'restart': return opts.systemd === false ? B('restart') : S('restart');
    case 'reload': return opts.systemd === false ? B('reload') : `(systemctl reload ${u} 2>/dev/null || systemctl restart ${u} 2>&1); ${isActive} && echo OK || echo FAILED`;
    case 'enable': return opts.systemd === false ? `update-rc.d ${u} defaults 2>/dev/null; update-rc.d ${u} enable 2>/dev/null; echo OK` : `(systemctl enable ${u} 2>&1; ${isEnabled} && echo OK || echo FAILED)`;
    case 'disable': return opts.systemd === false ? `update-rc.d ${u} disable 2>/dev/null; echo OK` : `(systemctl disable ${u} 2>&1; ! ${isEnabled} && echo OK || echo FAILED)`;
    case 'status': return isActive;
    default: return 'echo "unsupported service action" >&2; false';
  }
}

/** mask / unmask are systemd-only; degrade to a no-op success elsewhere */
function maskCmd(action, unit) {
  const u = q(unit);
  if (!UNIT_RE.test(String(unit))) return 'echo "invalid unit name" >&2; false';
  return `(systemctl ${action === 'mask' ? 'mask' : 'unmask'} ${u} 2>&1) || echo "not supported on this init system"`;
}

function timersCmd() {
  return '(systemctl list-timers --all --no-pager --no-legend 2>/dev/null | head -30) || echo ""';
}

function unitsCmd(type) {
  const t = q(type || 'service');
  return `(systemctl list-units --type=${t} --all --no-pager --plain --no-legend 2>/dev/null) || (service --status-all 2>&1 | head -60) || echo ""`;
}

module.exports = { detect, svcCmd, maskCmd, timersCmd, unitsCmd, UNIT_RE };