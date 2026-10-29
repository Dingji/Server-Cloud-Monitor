'use strict';
/*
 * Optional relay-stats integration.
 *
 * Proxies an external "relay collector" HTTP API (e.g. an OpenCode relay
 * dashboard) that listens on a local port. This is OPTIONAL: when no
 * collector is present the panel still runs fine and the UI hides the entry.
 * Configure with relay.host / relay.port in config.json.
 */
const http = require('http');
const { logErr } = require('./util');

function relayTarget() {
  let cfg = {};
  try { cfg = require('./config').get() || {}; } catch (_) {}
  const r = (cfg && cfg.relay) || {};
  return {
    host: String(r.host || process.env.CM_RELAY_HOST || '127.0.0.1'),
    port: parseInt(r.port || process.env.CM_RELAY_PORT || 8787, 10) || 8787,
  };
}
const T = relayTarget();
const RELAY_HOST = T.host;
const RELAY_PORT = T.port;

/* per-endpoint cache TTL (ms) */
const TTL = {
  '/api/summary': 2000,
  '/api/keys': 3000,
  '/api/key': 3000,
  '/api/quota': 2000,
  '/api/providers': 3000,
  '/api/daily': 15000,
  '/api/models': 5000,
  '/api/models-daily': 15000,
  '/api/tokens': 5000,
  '/api/model-status': 20000,
  '/api/health': 2000,
  '/api/selfheal': 10000,
  /* analytics endpoints (cheap, but keep a short cache) */
  '/api/hourly': 5000,
  '/api/weekday': 15000,
  '/api/latency': 5000,
  '/api/ttfb': 5000,
  '/api/streaming': 5000,
  '/api/sizes': 5000,
  '/api/status': 5000,
  '/api/routes': 5000,
  '/api/methods': 5000,
  '/api/endpoints': 5000,
  '/api/failover': 5000,
  '/api/errors': 3000,
  '/api/recent': 2000,
  '/api/sessions': 5000,
  '/api/session-memory': 10000,
  '/api/useragents': 5000,
  /* policy editor: must not show a stale policy right after a save.
     action() clears the whole cache on any mutation, so this stays short. */
  '/api/busy-hours': 2000,
  /* device list: a slow-moving table, and the label write clears the cache anyway */
  '/api/client-origins': 5000,
};
const cache = new Map(); // path?query -> { exp, payload }

function raw(pathQ, method, body, headers) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: RELAY_HOST, port: RELAY_PORT, path: pathQ, method: method || 'GET',
      headers: Object.assign({ Accept: 'application/json' }, headers || {}),
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
    });
    req.setTimeout(20000, () => req.destroy(new Error('中继服务响应超时')));
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

function parse(buf) {
  const text = buf.toString('utf8');
  try { return JSON.parse(text); } catch (_) { return { raw: text.slice(0, 4000) }; }
}

/* GET /api/<name>?query — cached per endpoint */
async function getApi(name, query) {
  const base = '/api/' + String(name || '').replace(/^\/+/, '');
  const qs = query && Object.keys(query).length ? '?' + new URLSearchParams(query).toString() : '';
  const key = base + qs;
  const hit = cache.get(key);
  if (hit && hit.exp > Date.now()) return hit.payload;
  let r;
  try { r = await raw(key, 'GET'); }
  catch (e) { throw Object.assign(new Error('中继统计服务不可达: ' + e.message), { status: 502 }); }
  const payload = { status: r.status, data: parse(r.body) };
  if (r.status === 200) cache.set(key, { exp: Date.now() + (TTL[base] || 5000), payload });
  return payload;
}

/* POST /api/<action> with X-Relay-Action: 1 (relay management actions) */
async function action(name, payload) {
  const a = String(name || '').replace(/^\/+/, '');
  if (!/^[a-z][a-z0-9\/_-]{0,40}$/.test(a)) bad('非法操作名');
  let r;
  try {
    r = await raw('/api/' + a, 'POST', JSON.stringify(payload || {}),
      { 'Content-Type': 'application/json', 'X-Relay-Action': '1' });
  } catch (e) { throw Object.assign(new Error('中继服务不可达: ' + e.message), { status: 502 }); }
  cache.clear(); /* mutations invalidate everything */
  const data = parse(r.body);
  if (r.status >= 400) {
    throw Object.assign(new Error(data.detail || data.error || data.message || ('中继返回 HTTP ' + r.status)), { status: 400 });
  }
  return data;
}

function bad(msg) { const e = new Error(msg); e.status = 400; throw e; }

/* health probe used by the relay page banner + feature detection */
async function probe() {
  try {
    const r = await raw('/api/health', 'GET');
    return { ok: r.status === 200, data: parse(r.body), host: RELAY_HOST, port: RELAY_PORT };
  } catch (e) {
    return { ok: false, error: e.message, host: RELAY_HOST, port: RELAY_PORT };
  }
}

/* Cached availability so the UI can hide the whole entry on stock Linux. */
let availCache = { exp: 0, ok: false };
async function available(maxAgeMs) {
  const now = Date.now();
  if (availCache.exp > now) return availCache.ok;
  const r = await probe();
  availCache = { exp: now + (maxAgeMs || 30000), ok: !!r.ok };
  return availCache.ok;
}

module.exports = { getApi, action, probe, available, RELAY_HOST, RELAY_PORT };
