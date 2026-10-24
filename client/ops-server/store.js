'use strict';
/* Shared storage helpers for the ops modules. Same conventions as the panel:
 * atomic 0600 JSON files under <dataDir>/ops, plus append-only JSONL trails. */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function soft(m) { try { return require(m); } catch (_) { return null; } }
const util = soft('../util') || { log() {}, logErr() {}, audit() {}, ts: () => new Date().toISOString() };
const paths = soft('../paths');

function root() {
  if (process.env.CM_OPS_DIR) return process.env.CM_OPS_DIR;
  const base = paths && paths.dataDir ? paths.dataDir : path.resolve(__dirname, '..', 'data');
  return path.join(base, 'ops');
}
const file = (n) => path.join(root(), n);

function ensure() { try { fs.mkdirSync(root(), { recursive: true }); } catch (_) {} }

function readJson(name, dflt) {
  try { return JSON.parse(fs.readFileSync(file(name), 'utf8')); } catch (_) { return dflt; }
}
function writeJson(name, obj) {
  ensure();
  const tmp = file(name + '.tmp');
  fs.writeFileSync(tmp, JSON.stringify(obj, null, 2), 'utf8');
  fs.renameSync(tmp, file(name));
  try { fs.chmodSync(file(name), 0o600); } catch (_) {}
  return obj;
}
function appendLine(name, obj) {
  ensure();
  try { fs.appendFileSync(file(name), JSON.stringify(obj) + '\n', 'utf8'); }
  catch (e) { if (util.logErr) util.logErr('ops append failed:', name, e.message); }
}
function readLines(name, limit) {
  let raw = '';
  try { raw = fs.readFileSync(file(name), 'utf8'); } catch (_) { return []; }
  const max = limit || 200;
  const out = [];
  for (const l of raw.split('\n').filter(Boolean).slice(-max).reverse()) {
    try { out.push(JSON.parse(l)); } catch (_) {}
  }
  return out.reverse();
}
/** JSONL files grow unbounded on a busy probe schedule; halve them past the cap. */
function trimLineFile(name, maxBytes) {
  try {
    const st = fs.statSync(file(name));
    if (st.size <= (maxBytes || 4 * 1024 * 1024)) return;
    const lines = fs.readFileSync(file(name), 'utf8').split('\n').filter(Boolean);
    fs.writeFileSync(file(name), lines.slice(-Math.floor(lines.length / 2)).join('\n') + '\n', 'utf8');
  } catch (_) {}
}
/** Delete JSONL entries older than days (per-file retention the panel never had). */
function pruneLineFile(name, days) {
  const cutoff = Date.now() - days * 86400000;
  try {
    const keep = fs.readFileSync(file(name), 'utf8').split('\n').filter(Boolean)
      .filter((l) => { try { return JSON.parse(l).ts >= cutoff; } catch (_) { return false; } });
    fs.writeFileSync(file(name), keep.length ? keep.join('\n') + '\n' : '', 'utf8');
  } catch (_) {}
}

const now = () => Date.now();
const dayKey = (t) => new Date(t || now()).toISOString().slice(0, 10);
const genId = (prefix) => prefix + crypto.randomBytes(4).toString('hex');

function clampNum(v, a, b, dflt) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.max(a, Math.min(b, n)) : dflt;
}
/** POSIX single-quote; every value that reaches a shell must go through this. */
function shq(s) { return "'" + String(s).replace(/'/g, `'\\''`) + "'" }

module.exports = {
  root, file, ensure, readJson, writeJson, appendLine, readLines, trimLineFile, pruneLineFile,
  now, dayKey, genId, clampNum, shq, util, paths, soft,
};
