'use strict';
/* Config load/save. Stored next to the app: <root>/config.json (mode 600). */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { APP_ROOT, log } = require('./util');

const CONFIG_PATH = path.join(APP_ROOT, 'config.json');

/* Generate a random initial password so no shipped default is ever a known secret. */
function randomPassword(len) {
  const alphabet = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#%^*-_=+';
  const n = len || 20;
  let out = '';
  const bytes = crypto.randomBytes(n);
  for (let i = 0; i < n; i++) out += alphabet[bytes[i] % alphabet.length];
  return out;
}

const DEFAULTS = {
  port: parseInt(process.env.CM_PORT, 10) || 9000,
  bind: process.env.CM_BIND || '127.0.0.1',
  basePath: '/panel',
  refreshMs: 2000,
  netLimitBps: 0,          /* 出口带宽上限（bps）；0 = 未设置，进度条沿用 1Mbps 基准 */
  sloTarget: 0.999,        /* SLO 目标可用性（0.999 = 99.9%） */
  sloPeriodDays: 30,       /* 错误预算周期（天） */
  alerts: { enabled: true, cpu: 90, mem: 90, disk: 95, cooldownMin: 10 },
  password: process.env.CM_PASSWORD || randomPassword(20), // hashed on first run, never persisted
  passwordHash: null,
  tokenTtlHours: 168,
  /* optional relay collector (entry hidden in the UI when unreachable) */
  relay: { host: '127.0.0.1', port: 8787 },
  ssh: {
    host: '127.0.0.1',
    port: 22,
    user: 'root',
    password: '',
    privateKeyFile: path.join(APP_ROOT, 'keys', 'pty_key'),
  },
  db: {
    postgres: { user: 'postgres', password: '', host: '', port: '' },
    mysql: { user: 'root', password: '', host: '', port: '' },
    /* IBM Db2 (LUW): run SQL as this instance owner through its CLI profile */
    db2: { instance: 'db2inst1', database: 'SAMPLE', user: '', password: '', host: '', port: '' },
    /* Redis: plain redis-cli target */
    redis: { host: '127.0.0.1', port: '6379', password: '', db: '0' },
    /* MongoDB: mongosh target */
    mongo: { host: '127.0.0.1', port: '27017', user: '', password: '', authdb: 'admin' },
    /* SQLite is file based: allowed search roots (colon separated) */
    sqlite: { roots: '/opt:/srv:/var/www:/www:/root:/home' },
  },
};

let state = null;

function hashPassword(pw) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(pw), salt, 32).toString('hex');
  return `scrypt$${salt}$${hash}`;
}

function verifyPassword(pw, stored) {
  try {
    const [algo, salt, hash] = String(stored || '').split('$');
    if (algo !== 'scrypt' || !salt || !hash) return false;
    const calc = crypto.scryptSync(String(pw), salt, 32).toString('hex');
    const a = Buffer.from(calc, 'hex'), b = Buffer.from(hash, 'hex');
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  } catch (_) { return false; }
}

function load() {
  if (state) return state;
  let raw = null;
  try { raw = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8')); } catch (_) {}
  state = Object.assign({}, DEFAULTS, raw || {});
  state.ssh = Object.assign({}, DEFAULTS.ssh, (raw && raw.ssh) || {});
  state.db = Object.assign({}, DEFAULTS.db, (raw && raw.db) || {});
  state.db.postgres = Object.assign({}, DEFAULTS.db.postgres, (raw && raw.db && raw.db.postgres) || {});
  state.db.mysql = Object.assign({}, DEFAULTS.db.mysql, (raw && raw.db && raw.db.mysql) || {});
  for (const eng of ['db2', 'redis', 'mongo', 'sqlite']) {
    state.db[eng] = Object.assign({}, DEFAULTS.db[eng], (raw && raw.db && raw.db[eng]) || {});
  }
  state.relay = Object.assign({}, DEFAULTS.relay, (raw && raw.relay) || {});
  if (!state.passwordHash) {
    state.passwordHash = hashPassword(state.password);
    save();
    /* 首次运行：把生成的随机密码记入日志，方便运维取用 */
    try {
      log('首次启动，已生成随机初始密码: ' + state.password);
      log('（建议登录后在「面板设置」中立即修改）');
    } catch (_) {}
  }
  try { fs.chmodSync(CONFIG_PATH, 0o600); } catch (_) {}
  return state;
}

function save() {
  const out = Object.assign({}, state);
  delete out.password; // never persist plaintext
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(out, null, 2));
  try { fs.chmodSync(CONFIG_PATH, 0o600); } catch (_) {}
  return out;
}

function get() { return load(); }

function patch(p) {
  const s = load();
  if (p.refreshMs) s.refreshMs = Math.max(500, Math.min(60000, parseInt(p.refreshMs, 10) || 2000));
  if (p.netLimitBps !== undefined && p.netLimitBps !== null) {
    s.netLimitBps = Math.max(0, Math.min(10000000000, parseInt(p.netLimitBps, 10) || 0));
  }
  if (p.sloTarget !== undefined && p.sloTarget !== null) {
    const v = parseFloat(p.sloTarget);
    if (v > 0 && v <= 1) s.sloTarget = v;
  }
  if (p.sloPeriodDays !== undefined && p.sloPeriodDays !== null) {
    const v = parseInt(p.sloPeriodDays, 10);
    if (v >= 1 && v <= 365) s.sloPeriodDays = v;
  }
  if (p.alerts && typeof p.alerts === 'object') {
    s.alerts = s.alerts || {};
    if (p.alerts.enabled !== undefined) s.alerts.enabled = !!p.alerts.enabled;
    for (const k of ['cpu', 'mem', 'disk']) {
      if (p.alerts[k] !== undefined) s.alerts[k] = Math.max(10, Math.min(100, parseInt(p.alerts[k], 10) || s.alerts[k]));
    }
    if (p.alerts.cooldownMin !== undefined) s.alerts.cooldownMin = Math.max(1, Math.min(1440, parseInt(p.alerts.cooldownMin, 10) || 10));
  }
  if (p.port) s.port = Math.max(1, Math.min(65535, parseInt(p.port, 10) || s.port));
  if (typeof p.password === 'string' && p.password.length >= 6) s.passwordHash = hashPassword(p.password);
  if (p.ssh && typeof p.ssh === 'object') {
    if (p.ssh.host) s.ssh.host = String(p.ssh.host);
    if (p.ssh.port) s.ssh.port = Math.max(1, Math.min(65535, parseInt(p.ssh.port, 10) || 22));
    if (p.ssh.user) s.ssh.user = String(p.ssh.user);
    if (typeof p.ssh.password === 'string' && p.ssh.password.length > 0) s.ssh.password = p.ssh.password;
  }
  if (p.db && typeof p.db === 'object') {
    for (const eng of ['postgres', 'mysql', 'db2', 'redis', 'mongo', 'sqlite']) {
      const src = p.db[eng];
      if (!src || typeof src !== 'object') continue;
      s.db[eng] = s.db[eng] || {};
      for (const k of Object.keys(src)) {
        if (k === 'password') { if (typeof src[k] === 'string') s.db[eng].password = src[k]; continue; }
        if (src[k] !== undefined && src[k] !== null) s.db[eng][k] = String(src[k]);
      }
    }
  }
  save();
  return s;
}

module.exports = { get, save, patch, hashPassword, verifyPassword, randomPassword, CONFIG_PATH };
