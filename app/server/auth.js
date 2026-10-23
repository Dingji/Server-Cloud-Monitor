'use strict';
/* Auth: password login, tokens, per-IP rate limiting. */
const crypto = require('crypto');
const config = require('./config');
const { audit } = require('./util');

const tokens = new Map(); // token -> { exp, ip, createdAt }
const loginFails = new Map(); // ip -> { count, windowStart, lockedUntil }
const apiHits = new Map(); // ip -> { count, windowStart }

const WINDOW_MS = 10 * 60 * 1000;
const MAX_FAILS = 8;
const LOCK_MS = 10 * 60 * 1000;

/* API 速率限制（防止单个会话发起并发重查询拖垮数据库） */
const API_WINDOW_MS = 60 * 1000;
/* 每个 IP 每分钟最多 API_MAX_PER_WINDOW 次 API 调用，超出返回 429 */
const API_MAX_PER_WINDOW = 1800;

function rateLimited(ip) {
  const rec = loginFails.get(ip);
  if (!rec) return false;
  if (rec.lockedUntil && Date.now() < rec.lockedUntil) return true;
  if (Date.now() - rec.windowStart > WINDOW_MS) { loginFails.delete(ip); return false; }
  return false;
}

function recordFail(ip) {
  let rec = loginFails.get(ip);
  if (!rec || Date.now() - rec.windowStart > WINDOW_MS) rec = { count: 0, windowStart: Date.now(), lockedUntil: 0 };
  rec.count++;
  if (rec.count >= MAX_FAILS) rec.lockedUntil = Date.now() + LOCK_MS;
  loginFails.set(ip, rec);
}

function login(ip, password) {
  if (rateLimited(ip)) {
    const e = new Error('尝试次数过多，请 10 分钟后再试');
    e.status = 429; throw e;
  }
  const cfg = config.get();
  if (!config.verifyPassword(password, cfg.passwordHash)) {
    recordFail(ip);
    audit(ip, 'login-fail', {});
    const e = new Error('密码错误');
    e.status = 401; throw e;
  }
  loginFails.delete(ip);
  const token = crypto.randomBytes(32).toString('hex');
  const exp = Date.now() + (cfg.tokenTtlHours || 168) * 3600 * 1000;
  tokens.set(token, { exp, ip, createdAt: Date.now() });
  prune();
  audit(ip, 'login-ok', {});
  return { token, expiresAt: new Date(exp).toISOString() };
}

function logout(token, ip) {
  if (token && tokens.delete(token)) audit(ip, 'logout', {});
}

function verify(token) {
  if (!token || typeof token !== 'string') return false;
  const rec = tokens.get(token);
  if (!rec) return false;
  if (Date.now() > rec.exp) { tokens.delete(token); return false; }
  return rec;
}

function prune() {
  const now = Date.now();
  for (const [t, rec] of tokens) if (now > rec.exp) tokens.delete(t);
  for (const [ip, rec] of apiHits) if (now - rec.windowStart > API_WINDOW_MS) apiHits.delete(ip);
}
setInterval(prune, 60 * 60 * 1000).unref();

/* 每个 IP 每分钟最多 API_MAX_PER_WINDOW 次 API 调用，超出返回 429 */
function apiLimited(ip) {
  const now = Date.now();
  let rec = apiHits.get(ip);
  if (!rec || now - rec.windowStart > API_WINDOW_MS) rec = { count: 0, windowStart: now };
  rec.count++;
  apiHits.set(ip, rec);
  return rec.count > API_MAX_PER_WINDOW;
}

function extract(req) {
  const h = req.headers.authorization || '';
  if (h.startsWith('Bearer ')) return h.slice(7).trim();
  if (req.query && req.query.token) return String(req.query.token);
  return null;
}

function middleware(req, res, next) {
  const rec = verify(extract(req));
  if (!rec) return res.status(401).json({ error: '未登录或会话过期' });
  req.clientIp = req.headers['x-real-ip'] || req.socket.remoteAddress || '?';
  next();
}

module.exports = { login, logout, verify, extract, middleware, rateLimited, apiLimited };
