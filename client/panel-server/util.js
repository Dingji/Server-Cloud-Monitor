'use strict';
/* Generic utilities: logging, audit trail, parsing helpers. */
const fs = require('fs');
const path = require('path');

const APP_ROOT = path.resolve(__dirname, '..');
const LOG_DIR = path.join(APP_ROOT, 'logs');

function ensureDirs() {
  try { fs.mkdirSync(LOG_DIR, { recursive: true }); } catch (_) {}
}

function ts() { return new Date().toISOString(); }

function log(...args) {
  console.log(`[${ts()}]`, ...args);
}
function logErr(...args) {
  console.error(`[${ts()}]`, ...args);
}

/* ---- audit log (rotating, small) ---- */
const AUDIT_FILE = path.join(LOG_DIR, 'audit.log');
const AUDIT_MAX = 2 * 1024 * 1024;

function audit(ip, action, detail) {
  try {
    ensureDirs();
    try {
      const st = fs.statSync(AUDIT_FILE);
      if (st.size > AUDIT_MAX) {
        fs.renameSync(AUDIT_FILE, AUDIT_FILE + '.1');
      }
    } catch (_) {}
    let line = detail;
    try { line = JSON.stringify(detail); } catch (_) { line = String(detail); }
    fs.appendFileSync(AUDIT_FILE, `${ts()} ip=${ip} action=${action} ${line}\n`);
  } catch (e) { logErr('audit write failed:', e.message); }
}

/* ---- parsing helpers ---- */
function parseKeyVal(text, sep = ':') {
  const out = {};
  if (!text) return out;
  for (const line of String(text).split('\n')) {
    const i = line.indexOf(sep);
    if (i < 0) continue;
    const k = line.slice(0, i).trim();
    const v = line.slice(i + sep.length).trim();
    if (k) out[k] = v;
  }
  return out;
}

function parseInt10(v) { const n = parseInt(v, 10); return Number.isFinite(n) ? n : 0; }
function toFloat(v, dflt = 0) { const n = parseFloat(v); return Number.isFinite(n) ? n : dflt; }
function clamp(n, a, b) { return Math.max(a, Math.min(b, n)); }

function fmtBytes(n) {
  if (!Number.isFinite(n)) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let i = 0, v = Math.abs(n);
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (n < 0 ? '-' : '') + (i === 0 ? v.toFixed(0) : v.toFixed(v >= 100 ? 0 : v >= 10 ? 1 : 2)) + ' ' + units[i];
}

function fmtBps(n) {
  if (!Number.isFinite(n)) return '-';
  return fmtBytes(n) + '/s';
}

function fmtUptime(sec) {
  sec = Math.floor(sec || 0);
  const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600),
        m = Math.floor((sec % 3600) / 60), s = sec % 60;
  if (d > 0) return `${d}天 ${h}时`;
  if (h > 0) return `${h}时 ${m}分`;
  if (m > 0) return `${m}分 ${s}秒`;
  return `${s}秒`;
}

module.exports = { APP_ROOT, LOG_DIR, ensureDirs, log, logErr, audit, parseKeyVal, parseInt10, toFloat, clamp, fmtBytes, fmtBps, fmtUptime, ts };
