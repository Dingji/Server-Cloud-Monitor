'use strict';
/*
 * Notification channels for the ops layer. The panel had none: 4 hard-coded
 * thresholds and no way to reach a human. Everything here uses node builtins +
 * global fetch (node >= 21); mail shells out to send_mail.py because the box
 * has python3/smtplib but no MTA.
 */
const crypto = require('crypto');
const path = require('path');
const { spawn } = require('child_process');
const S = require('./store');

const TYPES = {
  webhook: 'Webhook（自定义 JSON）',
  wecom: '企业微信群机器人',
  dingtalk: '钉钉群机器人（加签）',
  feishu: '飞书群机器人（加签）',
  telegram: 'Telegram Bot',
  email: '邮件 SMTP',
};

const LOG = 'notify_log.jsonl';
const MAX_RETRY = 3;
const BACKOFF_MS = [30000, 120000, 300000];

const SECRET_KEYS = /password|passwd|secret|token|api_?key|auth/i;
const MASK = '••••••';

/** URL secrets live in the query string (wecom `?key=`, dingtalk `?access_token=`). */
function maskUrl(u) {
  const s = String(u || '');
  if (!s) return s;
  return s.replace(/([?&](?:key|access_token|token)=)([^&]+)/gi, (m, p1) => p1 + MASK);
}

function maskCfg(cfg) {
  const out = {};
  for (const k of Object.keys(cfg || {})) {
    const v = cfg[k];
    if (typeof v === 'string' && /webhook|^url$|^apiBase$/i.test(k)) out[k] = maskUrl(v);
    else if (SECRET_KEYS.test(k) && v) out[k] = MASK;
    else out[k] = v;
  }
  return out;
}

/** What the UI sees: never echo stored credentials back. */
function publicView(ch) {
  const cfg = ch.cfg || {};
  const hints = {};
  for (const k of Object.keys(cfg)) if (SECRET_KEYS.test(k) && cfg[k]) hints[k + 'Set'] = true;
  return Object.assign({}, ch, { cfg: maskCfg(cfg), secretHints: hints });
}

function stripMask(b) {
  const cfg = Object.assign({}, b.cfg || {});
  for (const k of Object.keys(cfg)) if (cfg[k] === MASK || (typeof cfg[k] === 'string' && cfg[k].includes(MASK))) delete cfg[k];
  return cfg;
}

function listRaw() { return S.readJson('notify_channels.json', { items: [] }).items; }
function list() { return listRaw().map(publicView); }
function get(id) { return listRaw().find((c) => c.id === id) || null; }

function normalizeChannel(b, existing) {
  const type = TYPES[b.type] ? b.type : (existing && existing.type) || 'webhook';
  const cfg = Object.assign({}, (existing && existing.cfg) || {}, stripMask(b));
  const clean = {
    id: (existing && existing.id) || S.genId('n'),
    name: String(b.name || '').trim().slice(0, 40) || TYPES[type],
    type,
    enabled: b.enabled === undefined ? (existing ? existing.enabled !== false : true) : !!b.enabled,
    cfg,
    createdAt: (existing && existing.createdAt) || S.now(),
    updatedAt: S.now(),
    lastSend: (existing && existing.lastSend) || 0,
    lastOk: existing ? existing.lastOk === true : false,
    lastError: (existing && existing.lastError) || '',
  };
  if (b.name === undefined && existing) clean.name = existing.name;
  return clean;
}

function save(body) {
  const items = listRaw();
  const existing = body.id ? items.find((c) => c.id === body.id) : null;
  if (body.id && !existing) { const e = new Error('通知渠道不存在：' + body.id); e.status = 404; throw e; }
  const ch = normalizeChannel(body, existing);
  if (existing) items.splice(items.indexOf(existing), 1, ch); else items.push(ch);
  S.writeJson('notify_channels.json', { items });
  return { ok: true, channel: publicView(ch) };
}

function remove(body) {
  const items = listRaw();
  const idx = items.findIndex((c) => c.id === body.id);
  if (idx < 0) { const e = new Error('通知渠道不存在：' + body.id); e.status = 404; throw e; }
  const [gone] = items.splice(idx, 1);
  S.writeJson('notify_channels.json', { items });
  return { ok: true, removed: gone.id };
}

/* ---------------- message shape ---------------- */

function buildMessage(o) {
  const level = ['ok', 'warn', 'bad', 'info'].includes(o.level) ? o.level : 'info';
  return {
    title: String(o.title || 'Cloud Monitor 告警').slice(0, 120),
    text: String(o.text || '').slice(0, 4000),
    level,
    host: String(o.host || '').slice(0, 80),
    link: String(o.link || '').slice(0, 300),
    fields: Array.isArray(o.fields) ? o.fields.slice(0, 12).map((f) => ({ k: String(f.k).slice(0, 40), v: String(f.v).slice(0, 200) })) : [],
    ts: S.now(),
  };
}

function asMarkdown(m) {
  const icon = m.level === 'bad' ? '🔴' : m.level === 'warn' ? '🟠' : m.level === 'ok' ? '🟢' : '🔵';
  const lines = [`${icon} **${m.title}**`];
  if (m.host) lines.push(`> 主机：${m.host}`);
  if (m.text) lines.push('', m.text);
  m.fields.forEach((f) => lines.push(`- ${f.k}：${f.v}`));
  lines.push('', `_Cloud Monitor · ${new Date(m.ts).toLocaleString('zh-CN', { hour12: false })}_`);
  if (m.link) lines.push('', `[查看详情](${m.link})`);
  return lines.join('\n');
}

const urlencode = (s) => encodeURIComponent(s);

/* ---------------- per-type senders ---------------- */

async function httpJson(url, body, headers, timeoutMs) {
  const res = await fetch(url, {
    method: 'POST',
    headers: Object.assign({ 'content-type': 'application/json' }, headers || {}),
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs || 8000),
  });
  const text = (await res.text() || '').slice(0, 500);
  let parsed = null;
  try { parsed = JSON.parse(text); } catch (_) {}
  const errcode = parsed && (parsed.errcode !== undefined ? parsed.errcode : parsed.code);
  if (!res.ok || (typeof errcode === 'number' && errcode !== 0 && errcode !== 200)) {
    const e = new Error(`HTTP ${res.status} ${parsed ? (parsed.errmsg || parsed.msg || parsed.message || JSON.stringify(parsed)) : text}`);
    e.detail = text;
    throw e;
  }
  return text;
}

function dingtalkUrl(webhook, secret) {
  if (!secret) return webhook;
  const ts = Date.now();
  const sign = urlencode(crypto.createHmac('sha256', secret).update(`${ts}\n${secret}`).digest('base64'));
  return webhook + (webhook.includes('?') ? '&' : '?') + `timestamp=${ts}&sign=${sign}`;
}

function feishuSign(secret) {
  if (!secret) return null;
  const ts = Math.floor(Date.now() / 1000);
  return { timestamp: String(ts), sign: crypto.createHmac('sha256', `${ts}\n${secret}`).update('').digest('base64') };
}

function sendMail(cfg, m) {
  return new Promise((resolve) => {
    const script = path.join(__dirname, 'send_mail.py');
    const payload = JSON.stringify({
      host: cfg.host || '', port: Number(cfg.port) || 465,
      security: cfg.security || 'ssl', user: cfg.user || '', password: cfg.password || '',
      from: cfg.from || cfg.user || '', to: String(cfg.to || '').split(/[,;\s]+/).filter(Boolean),
      cc: String(cfg.cc || '').split(/[,;\s]+/).filter(Boolean),
      subject: `${m.level === 'bad' ? '[告警]' : m.level === 'warn' ? '[警告]' : '[通知]'} ${m.title}`,
      text: asMarkdown(m).replace(/[*_>#`\[\]()]/g, ''),
    });
    let out = '', err = '';
    let child;
    try { child = spawn(cfg.python || 'python3', [script], { timeout: 30000 }); }
    catch (e) { resolve({ ok: false, error: '无法启动 python3：' + e.message }); return; }
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    child.on('error', (e) => resolve({ ok: false, error: e.code === 'ENOENT' ? '服务器缺少 python3，邮件通道不可用' : e.message }));
    child.on('close', (code) => {
      if (code === 0) resolve({ ok: true, detail: out.trim() });
      else resolve({ ok: false, error: (err || out || ('退出码 ' + code)).trim().slice(0, 300) });
    });
    child.stdin.end(payload);
  });
}

async function sendOne(ch, m) {
  const c = ch.cfg || {};
  switch (ch.type) {
    case 'wecom': {
      if (!c.webhook) throw new Error('缺少机器人 webhook 地址');
      const body = c.msgtype === 'text'
        ? { msgtype: 'text', text: { content: asMarkdown(m) } }
        : { msgtype: 'markdown', markdown: { content: asMarkdown(m) } };
      return httpJson(c.webhook, body, null, c.timeoutMs);
    }
    case 'dingtalk': {
      if (!c.webhook) throw new Error('缺少机器人 webhook 地址');
      const body = { msgtype: 'markdown', markdown: { title: m.title, text: asMarkdown(m) } };
      if (c.atMobiles || c.atAll) {
        body.at = { atMobiles: String(c.atMobiles || '').split(/[,;\s]+/).filter(Boolean), isAtAll: !!c.atAll };
      }
      return httpJson(dingtalkUrl(c.webhook, c.secret), body, null, c.timeoutMs);
    }
    case 'feishu': {
      if (!c.webhook) throw new Error('缺少机器人 webhook 地址');
      const sig = feishuSign(c.secret);
      const body = { msg_type: 'interactive', card: {
        header: { title: { tag: 'plain_text', content: m.title }, template: m.level === 'bad' ? 'red' : m.level === 'warn' ? 'orange' : 'blue' },
        elements: [{ tag: 'markdown', content: asMarkdown(m) }],
      } };
      if (sig) { body.timestamp = sig.timestamp; body.sign = sig.sign; }
      return httpJson(c.webhook, body, null, c.timeoutMs);
    }
    case 'telegram': {
      if (!c.botToken || !c.chatId) throw new Error('缺少 Bot Token 或 Chat ID');
      const base = (c.apiBase || 'https://api.telegram.org').replace(/\/$/, '');
      const url = `${base}/bot${c.botToken}/sendMessage`;
      return httpJson(url, { chat_id: c.chatId, text: asMarkdown(m).replace(/[*_`#]/g, ''), disable_web_page_preview: true }, null, c.timeoutMs);
    }
    case 'email': {
      if (!c.host || !c.to) return { ok: false, error: '缺少 SMTP 服务器或收件人' };
      return sendMail(c, m);
    }
    case 'webhook':
    default: {
      if (!c.url) throw new Error('缺少回调 URL');
      const headers = {};
      if (c.secret) headers[c.secretHeader || 'X-Cloud-Monitor-Signature'] =
        crypto.createHmac('sha256', c.secret).update(JSON.stringify(m)).digest('hex');
      return httpJson(c.url, {
        source: 'cloud-monitor', title: m.title, level: m.level, text: m.text,
        markdown: asMarkdown(m), host: m.host, link: m.link, fields: m.fields, ts: m.ts,
      }, headers, c.timeoutMs);
    }
  }
}

/** Send on one channel, record the result, update the channel's last-send state. */
async function send(ch, m, opts) {
  const started = S.now();
  let res;
  try {
    const r = await sendOne(ch, m);
    /* senders that resolve with {ok:false} (e.g. the mail helper's exit code)
     * must not be recorded as a success — a silently lost alert is worse than a
     * logged failure. */
    if (r && r.ok === false) res = { ok: false, error: String(r.error || '发送被拒绝').slice(0, 300) };
    else res = { ok: true, detail: (typeof r === 'string' ? r : String((r && r.detail) || 'ok')).slice(0, 200) };
  }
  catch (e) { res = { ok: false, error: (e.message || String(e)).slice(0, 300) }; }
  const rec = {
    ts: S.now(), ms: S.now() - started, channelId: ch.id, channelName: ch.name, channelType: ch.type,
    level: m.level, title: m.title, ok: res.ok, error: res.error || '', source: (opts && opts.source) || 'manual',
    ruleId: (opts && opts.ruleId) || null,
  };
  S.appendLine(LOG, rec);
  S.trimLineFile(LOG, 6 * 1024 * 1024);
  if (!opts || !opts.noRetry) {
    if (!res.ok) scheduleRetry(ch, m, opts);
  }
  touchChannel(ch.id, res.ok, res.error || '');
  return res;
}

/* Chinese clouds retry webhooks; a 3-backoff retry loop keeps alerts from vanishing
 * on a transient network blip. Backoff 30s / 2min / 5min, max 3 tries. */
const pending = new Map();
function scheduleRetry(ch, m, opts) {
  const key = ch.id + '|' + m.title + '|' + m.ts;
  const st = pending.get(key) || { tries: 0 };
  if (st.tries >= MAX_RETRY) { pending.delete(key); return; }
  st.tries += 1;
  pending.set(key, st);
  const t = setTimeout(async () => {
    pending.delete(key);
    const fresh = get(ch.id);
    if (!fresh || fresh.enabled === false) return;
    const r = await send(fresh, m, Object.assign({}, opts, { noRetry: true, retry: st.tries }));
    if (!r.ok) scheduleRetry(fresh, m, opts);
  }, BACKOFF_MS[st.tries - 1]);
  if (t.unref) t.unref();
}

function touchChannel(id, ok, error) {
  const items = listRaw();
  const ch = items.find((c) => c.id === id);
  if (!ch) return;
  ch.lastSend = S.now(); ch.lastOk = ok; ch.lastError = error;
  S.writeJson('notify_channels.json', { items });
}

/**
 * Fan out. `channelIds` omitted (null) means every enabled channel; an explicit
 * empty array means "panel alert only, do not notify anyone" — that is what a
 * freshly created rule/task defaults to, so nothing spams every channel.
 */
async function dispatch(m, channelIds, opts) {
  const all = list();
  const picked = Array.isArray(channelIds)
    ? all.filter((c) => channelIds.includes(c.id))
    : all.filter((c) => c.enabled !== false);
  const out = [];
  for (const ch of picked) {
    if (ch.enabled === false) { out.push({ channelId: ch.id, channelName: ch.name, ok: false, error: '渠道已停用' }); continue; }
    const r = await send(ch, m, opts);
    out.push({ channelId: ch.id, channelName: ch.name, channelType: ch.type, ...r });
  }
  return { ok: out.every((x) => x.ok), targets: out, sent: out.length };
}

function recent(limit) { return S.readLines(LOG, limit || 100); }
function summary() {
  const items = list();
  const last = recent(50);
  return {
    total: items.length, enabled: items.filter((c) => c.enabled !== false).length,
    byType: Object.keys(TYPES).map((t) => ({ type: t, label: TYPES[t], count: items.filter((c) => c.type === t).length })),
    sent24h: last.filter((r) => r.ts > S.now() - 86400000).length,
    failed24h: last.filter((r) => r.ts > S.now() - 86400000 && !r.ok).length,
  };
}

function register(api, wrap, audited) {
  api.get('/ops/alerts/channels', wrap(async (req, res) => res.json({
    ok: true, types: Object.entries(TYPES).map(([k, v]) => ({ key: k, label: v })), items: list(),
  })));
  api.get('/ops/notify/log', wrap(async (req, res) => res.json({ ok: true, items: recent(Number(req.query.limit) || 200) })));
  api.post('/ops/alerts/channel/save', audited('ops.channel.save', async (req) => save(req.body || {})));
  api.post('/ops/alerts/channel/delete', audited('ops.channel.delete', async (req) => remove(req.body || {})));
  api.post('/ops/alerts/channel/test', audited('ops.channel.test', async (req) => {
    const b = req.body || {};
    const ch = b.id ? get(b.id) : normalizeChannel(b, null);
    if (!ch) { const e = new Error('渠道不存在：' + b.id); e.status = 404; throw e; }
    const m = buildMessage({
      title: 'Cloud Monitor 测试通知', level: 'info', host: require('os').hostname(),
      text: String(b.text || '这是一条来自面板运维模块的测试消息。'),
      fields: [{ k: '渠道', v: `${ch.name}（${TYPES[ch.type] || ch.type}）` }],
    });
    const r = await send(ch, m, { source: 'test' });
    return { ok: r.ok, result: r, preview: asMarkdown(m) };
  }));
  api.post('/ops/notify/send', audited('ops.notify.send', async (req) => {
    const b = req.body || {};
    return dispatch(buildMessage(b), Array.isArray(b.channelIds) ? b.channelIds : null, { source: 'manual' });
  }));
}

module.exports = { TYPES, list, get, save, remove, buildMessage, asMarkdown, dispatch, send, recent, summary, register, normalizeChannel };
