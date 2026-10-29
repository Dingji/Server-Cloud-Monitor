'use strict';
/*
 * Shared command runner for the ops modules. Prefers the panel's agent (local mode
 * on the server, ssh mode when developing from another OS, hardened PATH/env), and
 * falls back to child_process so the modules can still be unit-tested standalone.
 * Tests can inject a runner with setRunner() — that is how the check parsers get
 * verified without touching a real system.
 */
const { exec } = require('child_process');
const S = require('./store');

const agentMod = S.soft('../agent');
let injected = null;
let agent = null;
let agentFailed = false;

function getAgent() {
  if (agent || agentFailed) return agent;
  if (!agentMod || typeof agentMod.getAgent !== 'function') { agentFailed = true; return null; }
  try { agent = agentMod.getAgent(); } catch (e) { agentFailed = true; agent = null; }
  return agent;
}

function setRunner(fn) { injected = fn; }

async function run(cmd, opts) {
  const o = opts || {};
  if (injected) return injected(cmd, o);
  const a = getAgent();
  if (a && typeof a.run === 'function') {
    try {
      const r = await a.run(cmd, Object.assign({ timeout: 15000 }, o));
      return {
        code: r.code === undefined ? (r.timedOut ? -1 : 0) : r.code,
        stdout: String(r.stdout || ''), stderr: String(r.stderr || ''), timedOut: !!r.timedOut,
      };
    } catch (e) {
      return { code: 1, stdout: '', stderr: e.message || String(e), timedOut: false };
    }
  }
  return new Promise((resolve) => {
    exec(cmd, { timeout: o.timeout || 15000, maxBuffer: o.maxBuffer || 8 * 1024 * 1024, windowsHide: true },
      (err, stdout, stderr) => {
        resolve({
          code: err ? (typeof err.code === 'number' ? err.code : 1) : 0,
          stdout: String(stdout || ''), stderr: String(stderr || ''), timedOut: !!(err && err.killed),
        });
      });
  });
}

/** `command -v x` cache — many checks skip cleanly when a binary is absent. */
const binCache = new Map();
async function hasBin(name) {
  if (binCache.has(name)) return binCache.get(name);
  let yes;
  if (process.platform === 'win32') {
    const r = await run(`where ${name} 2>nul`, { timeout: 8000 });
    yes = String(r.stdout || '').trim().length > 0;
  } else {
    const r = await run(`command -v ${S.shq(name)} >/dev/null 2>&1 && echo YES || echo NO`, { timeout: 8000 });
    yes = /YES/.test(r.stdout);
  }
  binCache.set(name, yes);
  return yes;
}

function mode() { const a = getAgent(); return a && a.mode ? a.mode : 'child_process'; }

module.exports = { run, hasBin, setRunner, mode, clearCache: () => binCache.clear() };
