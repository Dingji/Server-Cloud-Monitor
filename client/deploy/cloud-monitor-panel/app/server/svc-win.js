'use strict';
/* Windows Service control (sc.exe / PowerShell). */
const { getAgent } = require('./agent');

let mode = { exp: 0, systemd: false, serviceBin: 'sc', init: 'sc' };

async function detect() {
  if (mode.exp > Date.now()) return mode;
  mode = { exp: Date.now() + 300000, systemd: false, serviceBin: 'sc', init: 'sc' };
  return mode;
}

const UNIT_RE = /^[A-Za-z0-9@_.\-:]{1,120}$/;

function q(s) { return '"' + String(s == null ? '' : s).replace(/"/g, '\\"') + '"'; }

function svcCmd(action, unit, opts = {}) {
  const u = q(unit);
  if (!UNIT_RE.test(String(unit))) return 'echo invalid service name & exit /b 1';
  switch (action) {
    case 'start': return `sc start ${u}`;
    case 'stop': return `sc stop ${u}`;
    case 'restart': return `sc stop ${u} & timeout /t 2 /nobreak >nul & sc start ${u}`;
    case 'reload': return `sc stop ${u} & timeout /t 2 /nobreak >nul & sc start ${u}`;
    case 'enable': return `sc config ${u} start= auto`;
    case 'disable': return `sc config ${u} start= disabled`;
    case 'status': return `sc query ${u}`;
    default: return 'echo unsupported & exit /b 1';
  }
}

function maskCmd(action, unit) {
  return svcCmd(action === 'mask' ? 'disable' : 'enable', unit);
}

function timersCmd() {
  return 'schtasks /query /fo LIST /v 2>nul';
}

function unitsCmd() {
  return 'Get-Service | Select-Object Name,Status,DisplayName,StartType | ConvertTo-Json -Compress';
}

module.exports = { detect, svcCmd, maskCmd, timersCmd, unitsCmd, UNIT_RE };
