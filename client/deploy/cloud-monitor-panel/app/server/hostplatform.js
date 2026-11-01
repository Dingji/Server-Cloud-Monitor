'use strict';
/* Shared helpers for running on Windows Server / Windows desktop hosts. */
const cp = require('child_process');
const os = require('os');

const IS_WIN = process.platform === 'win32';

function runPowerShell(script, opts = {}) {
  const timeout = opts.timeout || 20000;
  const maxBuffer = opts.maxBuffer || 24 * 1024 * 1024;
  const encoded = Buffer.from(String(script), 'utf16le').toString('base64');
  const cmd = `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ${encoded}`;
  return new Promise((resolve) => {
    cp.exec(cmd, { timeout, maxBuffer, windowsHide: true }, (err, stdout, stderr) => {
      if (err && err.killed) {
        return resolve({
          code: -1,
          stdout: String(stdout || ''),
          stderr: (String(stderr || '') + `\n[timeout after ${timeout}ms]`).trim(),
          timedOut: true,
        });
      }
      resolve({
        code: err && typeof err.code === 'number' ? err.code : (err ? 1 : 0),
        stdout: String(stdout || ''),
        stderr: String(stderr || ''),
        timedOut: false,
      });
    });
  });
}

/** Parse JSON from PowerShell stdout (may include CLIXML noise). */
function parsePsJson(text) {
  const s = String(text || '').trim();
  if (!s) return null;
  const startObj = s.indexOf('{');
  const startArr = s.indexOf('[');
  let start = -1;
  if (startObj >= 0 && startArr >= 0) start = Math.min(startObj, startArr);
  else start = startObj >= 0 ? startObj : startArr;
  if (start < 0) return null;
  const slice = s.slice(start);
  try { return JSON.parse(slice); } catch (_) {
    const endObj = slice.lastIndexOf('}');
    const endArr = slice.lastIndexOf(']');
    const end = Math.max(endObj, endArr);
    if (end > 0) {
      try { return JSON.parse(slice.slice(0, end + 1)); } catch (_2) { return null; }
    }
    return null;
  }
}

async function psJson(script, opts) {
  const r = await runPowerShell(script, opts);
  const data = parsePsJson(r.stdout);
  return { ...r, data };
}

function runCmd(cmd, opts = {}) {
  const timeout = opts.timeout || 15000;
  const maxBuffer = opts.maxBuffer || 16 * 1024 * 1024;
  return new Promise((resolve) => {
    cp.exec(cmd, { timeout, maxBuffer, windowsHide: true, shell: true }, (err, stdout, stderr) => {
      if (err && err.killed) {
        return resolve({ code: -1, stdout: String(stdout || ''), stderr: String(stderr || ''), timedOut: true });
      }
      resolve({
        code: err && typeof err.code === 'number' ? err.code : (err ? 1 : 0),
        stdout: String(stdout || ''),
        stderr: String(stderr || ''),
        timedOut: false,
      });
    });
  });
}

module.exports = {
  IS_WIN,
  runPowerShell,
  psJson,
  parsePsJson,
  runCmd,
  hostname: () => os.hostname(),
};
