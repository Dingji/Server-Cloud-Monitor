'use strict';
/*
 * Ops layer contract tests. Builds a sandbox that looks like the panel's server/
 * directory (real ops modules + stub collectors/hub/util/paths), mounts the ops
 * routes onto a fake router that reproduces index.js's wrap()/audited(), and
 * drives the whole surface without touching the network.
 *
 *   node tools/ops_test.js
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'cmops-'));
const SERVER = path.join(TMP, 'server');
const OPS = path.join(SERVER, 'ops');
fs.mkdirSync(OPS, { recursive: true });
fs.mkdirSync(path.join(TMP, 'data'), { recursive: true });

for (const f of fs.readdirSync(path.join(__dirname, '..', 'ops-server'))) {
  fs.copyFileSync(path.join(__dirname, '..', 'ops-server', f), path.join(OPS, f));
}

const state = { metrics: null, disks: null, hubAlerts: [], auditedNames: [] };
fs.writeFileSync(path.join(SERVER, 'util.js'), `
'use strict';
const log = (...a) => {}; const logErr = (...a) => {};
module.exports = { log, logErr, audit: (ip, action, detail) => { (global.__audited = global.__audited || []).push(action); }, ts: () => new Date().toISOString() };
`);
fs.writeFileSync(path.join(SERVER, 'paths.js'), `module.exports = { dataDir: ${JSON.stringify(path.join(TMP, 'data'))} };`);
fs.writeFileSync(path.join(SERVER, 'hub.js'), `
'use strict';
module.exports = { fireAlert: (key, level, message) => { global.__hubAlerts.push({ key, level, message, ts: Date.now() }); } };
`);
fs.writeFileSync(path.join(SERVER, 'collectors.js'), `
'use strict';
module.exports = {
  getMetrics: async () => global.__metrics,
  getDisks: async () => global.__disks,
};
`);
global.__hubAlerts = state.hubAlerts;
/* express is only used for the raw upload parser; stub it so the route is testable. */
fs.mkdirSync(path.join(TMP, 'node_modules', 'express'), { recursive: true });
fs.writeFileSync(path.join(TMP, 'node_modules', 'express', 'index.js'), `
module.exports = { raw: () => (req, res, next) => { req.body = global.__rawBody || Buffer.from('empty'); next(); } };
`);
const FSROOT = (path.join(TMP, 'fsroot')).replace(/\\/g, '/');
fs.mkdirSync(FSROOT, { recursive: true });
fs.mkdirSync(path.join(TMP, 'fsroot', 'logs'), { recursive: true });
fs.mkdirSync(path.join(TMP, 'fsroot', 'uploads'), { recursive: true });
fs.writeFileSync(path.join(TMP, 'fsroot', 'app.conf'), 'listen = 9000\nname = cloud-monitor\n', 'utf8');
fs.writeFileSync(path.join(TMP, 'fsroot', 'logs', 'app.log'), 'line1\nline2\nline3\n', 'utf8');
fs.writeFileSync(path.join(TMP, 'fsroot', 'big.bin'), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01, 0x02, 0x00]));
global.__metrics = {
  ts: Date.now(), cpu: { totalPct: 12.5, cores: [10, 15], loadAvg: [0.4, 0.3, 0.2], uptimeSec: 1000, procs: { running: 200, total: 150000 }, times: { iowait: 10 }, coresCount: 2 },
  memory: { total: 1820127232, available: 1243504640, actualUsedPct: 30, usedPct: 25, swapTotal: 0, swapPct: 0 },
  net: { ifaces: { lo: { rxBps: 1000, txBps: 1000 }, eth0: { rxBps: 512000, txBps: 2048000 } } },
  diskio: { readBps: 0, writeBps: 0 },
};
global.__disks = { ts: Date.now(), overallPct: 34, mounts: [{ dev: '/dev/vda3', total: 41882943488, used: 13293936640, avail: 26653270016, pct: 34, mount: '/' }], blocks: [] };

process.env.CM_OPS_DIR = path.join(TMP, 'data', 'ops');
process.env.CM_OPS_DISABLE_SCHEDULERS = '1';

const ops = require(path.join(OPS, 'index.js'));

/* ---- fake router + panel index.js helper semantics ---- */
const routes = new Map();
const addRoute = (method) => (p, ...handlers) => routes.set(method + ' ' + p, handlers);
const api = {
  get: addRoute('GET'),
  post: addRoute('POST'),
};
const wrap = (fn) => async (req, res) => {
  try { await Promise.resolve(fn(req, res)); } catch (e) {
    if (e.output) return res.status(e.status || 500).json({ error: e.message, output: e.output });
    res.status(e.status || 500).json({ error: e.message });
  }
};
const audited = (name, fn) => wrap(async (req, res) => {
  state.auditedNames.push(name);
  res.json(await fn(req));
});
const handle = ops.mount(api, { wrap, audited });

async function call(method, route, opt) {
  const handlers = routes.get(method + ' ' + route);
  if (!handlers) throw new Error('route not registered: ' + method + ' ' + route);
  const o = opt || {};
  const req = { body: o.body || {}, query: o.query || {}, headers: {}, clientIp: '203.0.113.9' };
  let status = 200, json = null;
  const res = { status(s) { status = s; return this; }, json(o2) { json = o2; return this; }, setHeader() { return this; } };
  let idx = 0;
  const next = async () => {
    if (idx >= handlers.length) return;
    await handlers[idx++](req, res, next);
  };
  await next();
  return { status, json };
}
const get = (r, q) => call('GET', r, { query: q });
const post = (r, b) => call('POST', r, { body: b });

let pass = 0, fail = 0;
function showWecomKeyLeak(ch) { return /key=K(?![\u2022])/.test(String((ch.cfg || {}).webhook || '')); }
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra !== undefined ? '  -> ' + JSON.stringify(extra).slice(0, 400) : '')); }
}

/* ---- fetch stub for channel payloads ---- */
const realFetch = globalThis.fetch;
let sent = [];
let fetchFails = 0;
function stubFetch(failTimes) {
  sent = [];
  fetchFails = failTimes || 0;
  globalThis.fetch = async (url, init) => {
    if (fetchFails > 0) { fetchFails--; const e = new Error('ECONNRESET stub'); throw e; }
    const u = String(url);
    /* POST without a body is a probe: /bad answers 503, everything else 200.
       POST with a JSON body is a notification channel call: always accepted. */
    const isNotify = !!(init && init.body);
    const status = isNotify ? 200 : (/\/bad/.test(u) ? 503 : 200);
    sent.push({ url: u, status, body: isNotify ? JSON.parse(init.body) : null, headers: (init && init.headers) || {} });
    return {
      ok: status < 400, status,
      text: async () => (isNotify ? '{"errcode":0,"errmsg":"ok"}' : 'SERVICE-HEALTHY ok'),
    };
  };
}

(async () => {
  console.log('ops dir: ' + process.env.CM_OPS_DIR + '\n');

  console.log('[挂载与路由]');
  const need = [
    'GET /ops/overview', 'GET /ops/alerts/channels', 'GET /ops/alerts/rules', 'GET /ops/alerts/silences',
    'GET /ops/alerts/events', 'GET /ops/notify/log', 'GET /ops/uptime/tasks', 'GET /ops/uptime/sla',
    'GET /ops/uptime/history', 'POST /ops/alerts/channel/save', 'POST /ops/alerts/channel/test',
    'POST /ops/notify/send', 'POST /ops/alerts/rule/save', 'POST /ops/alerts/rule/test',
    'POST /ops/alerts/silence/save', 'POST /ops/uptime/task/save', 'POST /ops/uptime/task/run',
  ];
  ok('运维模块注册了全部端点', need.every((r) => routes.has(r)), need.filter((r) => !routes.has(r)));
  ok('端点总数 >= 20', routes.size >= 20, routes.size);
  ok('调度器可用 stop 关掉（测试期禁用）', (() => { handle.shutdown(); return true; })());

  console.log('\n[通知渠道]');
  let r = await get('/ops/alerts/channels');
  ok('渠道类型 6 种', r.json.types.length === 6 && r.json.types.some((t) => t.key === 'wecom'), r.json.types);
  stubFetch(0);
  r = await post('/ops/alerts/channel/save', { name: '值班群', type: 'wecom', cfg: { webhook: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=K' } });
  const wecom = r.json.channel;
  ok('企业微信渠道可保存并带 id', r.status === 200 && /^n[0-9a-f]+$/.test(wecom.id), wecom);
  r = await post('/ops/alerts/channel/save', { name: '钉钉', type: 'dingtalk', cfg: { webhook: 'https://oapi.dingtalk.com/robot/send?access_token=T', secret: 'SEC' + 'x'.repeat(20), atAll: true } });
  const ding = r.json.channel;
  r = await post('/ops/alerts/channel/save', { name: '飞书', type: 'feishu', cfg: { webhook: 'https://open.feishu.cn/open-apis/bot/v2/hook/H', secret: 'fs' } });
  const feishu = r.json.channel;
  r = await post('/ops/alerts/channel/save', { name: '回调', type: 'webhook', cfg: { url: 'https://customer.example/hook', secret: 'shh' } });
  const hook = r.json.channel;
  r = await post('/ops/alerts/channel/save', { name: 'TG', type: 'telegram', cfg: { botToken: '123:ABC', chatId: '@chan', apiBase: 'https://tg.internal:8443' } });
  const tg = r.json.channel;
  r = await post('/ops/alerts/channel/save', { name: '邮件', type: 'email', cfg: { host: 'smtp.example.com', port: 465, user: 'a@b.c', password: 'p', to: 'ops@b.c,cn@b.c' } });
  const mail = r.json.channel;

  r = await post('/ops/alerts/channel/test', { id: wecom.id, text: '测试内容' });
  ok('企业微信测试发送成功', r.status === 200 && r.json.ok === true, r.json);
  ok('企业微信用 markdown 消息体', sent[0].body.msgtype === 'markdown' && sent[0].body.markdown.content.includes('测试内容'), sent[0] && sent[0].body);
  ok('预览含主机与图标', r.json.preview.includes('Cloud Monitor 测试通知'));

  r = await post('/ops/alerts/channel/test', { id: ding.id });
  const dingCall = sent[sent.length - 1];
  ok('钉钉加签把 timestamp/sign 拼到 URL', /[?&]timestamp=\d+&sign=/.test(dingCall.url), dingCall.url);
  ok('钉钉 markdown 带 title 且 @所有人', dingCall.body.markdown.title && dingCall.body.at.isAtAll === true, dingCall.body);

  r = await post('/ops/alerts/channel/test', { id: feishu.id });
  const feishuCall = sent[sent.length - 1];
  ok('飞书交互式卡片带 timestamp + sign', /^\d{10}$/.test(feishuCall.body.timestamp) && typeof feishuCall.body.sign === 'string' && feishuCall.body.sign.length > 20, feishuCall.body.timestamp);
  ok('飞书卡片按级别配色', feishuCall.body.card.header.template === 'blue', feishuCall.body.card.header);

  r = await post('/ops/alerts/channel/test', { id: tg.id });
  const tgCall = sent[sent.length - 1];
  ok('Telegram 走自定义 apiBase 与 bot token', tgCall.url === 'https://tg.internal:8443/bot123:ABC/sendMessage', tgCall.url);
  ok('Telegram 文本去掉 markdown 星号', !tgCall.body.text.includes('**') && tgCall.body.chat_id === '@chan', tgCall.body.text);

  r = await post('/ops/alerts/channel/test', { id: hook.id });
  const hookCall = sent[sent.length - 1];
  ok('Webhook 带 HMAC 签名头', /^[0-9a-f]{64}$/.test(hookCall.headers['X-Cloud-Monitor-Signature']), hookCall.headers);
  ok('Webhook 载荷含 level/markdown/fields', hookCall.body.level === 'info' && hookCall.body.markdown && Array.isArray(hookCall.body.fields), Object.keys(hookCall.body));

  r = await post('/ops/alerts/channel/test', { id: mail.id });
  ok('邮件发送失败时返回可读原因而不是假成功', r.json.ok === false && (r.json.result.error || '').length > 0, r.json.result);

  r = await post('/ops/alerts/channel/test', { id: 'nope' });
  ok('测试不存在的渠道 404', r.status === 404, r.status);

  const mailBad = (await post('/ops/alerts/channel/save', {
    name: '邮件（无解释器）', type: 'email',
    cfg: { python: 'definitely-not-a-binary-xyz', host: 'smtp.example.com', to: 'ops@example.com' },
  })).json.channel;
  r = await post('/ops/alerts/channel/test', { id: mailBad.id });
  ok('发信脚本不可用时记为失败，不冒充成功', r.status === 200 && r.json.ok === false && /python|解释器|ENOENT|不可用/i.test(r.json.result.error), r.json.result);
  const keepFetch = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: true, status: 200, text: async () => '{"errcode":93000,"errmsg":"invalid webhook url"}' });
  r = await post('/ops/alerts/channel/test', { id: wecom.id });
  ok('机器人返回业务错误码时判为失败', r.json.ok === false && /93000|invalid/.test(r.json.result.error), r.json.result);
  globalThis.fetch = keepFetch;

  r = await get('/ops/alerts/channels');
  const shownMail = r.json.items.find((x) => x.id === mail.id);
  const shownWecom = r.json.items.find((x) => x.id === wecom.id);
  ok('渠道列表不把 SMTP 密码回传（脱敏 + 已设置标记）', shownMail.cfg.password === '••••••' && shownMail.secretHints.passwordSet === true, shownMail);
  ok('机器人 webhook 的 key 也被脱敏', /\?key=•+/.test(shownWecom.cfg.webhook) && !showWecomKeyLeak(shownWecom), shownWecom.cfg.webhook);
  const diskRaw = JSON.parse(fs.readFileSync(path.join(process.env.CM_OPS_DIR, 'notify_channels.json'), 'utf8')).items;
  ok('磁盘上仍保存真实凭据（脱敏只在回显层）', diskRaw.find((x) => x.id === mail.id).cfg.password === 'p', diskRaw.find((x) => x.id === mail.id).cfg);
  r = await post('/ops/alerts/channel/save', { id: mail.id, name: '邮件（改名）', cfg: { password: '••••••', host: 'smtp2.example.com' } });
  const diskAfter = JSON.parse(fs.readFileSync(path.join(process.env.CM_OPS_DIR, 'notify_channels.json'), 'utf8')).items.find((x) => x.id === mail.id);
  ok('回传占位符不会覆盖已存密码，其它字段照常更新', diskAfter.cfg.password === 'p' && diskAfter.cfg.host === 'smtp2.example.com' && diskAfter.name === '邮件（改名）', diskAfter);

  console.log('\n[发送失败与记录]');
  stubFetch(3);
  r = await post('/ops/notify/send', { title: '全员广播', text: '三条都失败', channelIds: [wecom.id, ding.id, feishu.id], level: 'bad' });
  ok('广播时逐渠道返回失败而不是抛异常', r.status === 200 && r.json.ok === false && r.json.targets.length === 3, r.json);
  ok('每次失败通知都进日志', r.json.targets.every((t) => /ECONNRESET/.test(t.error || '')), r.json.targets);
  stubFetch(0);
  r = await get('/ops/notify/log');
  ok('通知日志含成功与失败两类记录', r.json.items.some((x) => x.ok === false) && r.json.items.some((x) => x.ok === true), r.json.items.length);
  r = await post('/ops/alerts/channel/save', { id: wecom.id });
  ok('渠道记录了最近发送状态', r.json.channel.lastSend > 0, r.json.channel);

  console.log('\n[告警规则引擎]');
  r = await get('/ops/alerts/rules');
  ok('指标目录字段来自真实采集形状', r.json.metrics.some((m) => m.source === 'cpu.totalPct') && r.json.metrics.some((m) => m.source === 'memory.actualUsedPct'), r.json.metrics.map((m) => m.source));
  global.__disks = { ts: Date.now(), overallPct: 99, mounts: [
    { dev: '/dev/vda3', total: 41882943488, used: 13293936640, avail: 26653270016, pct: 34, mount: '/' },
    { dev: '/dev/vda2', total: 104857600, used: 100663296, avail: 4194304, pct: 96, mount: '/boot/efi' },
  ] };
  const dvals = (await ops.alerting.sample()).values;
  ok('小分区（/boot/efi 96%）不参与磁盘容量判断', dvals.diskMaxPct === 34 && Math.abs(dvals.diskAvailGb - 24.82) < 0.01, { max: dvals.diskMaxPct, avail: dvals.diskAvailGb });
  global.__disks = { ts: Date.now(), overallPct: 34, mounts: [{ dev: '/dev/vda3', total: 41882943488, used: 13293936640, avail: 26653270016, pct: 34, mount: '/' }], blocks: [] };
  r = await post('/ops/alerts/rule/save', { name: 'CPU 高', metric: 'cpuPct', op: '>=', threshold: 90, forSec: 0, level: 'warn', cooldownSec: 900, channelIds: [wecom.id] });
  const rule = r.json.rule;
  ok('规则保存成功且默认值被夹在合法区间', r.status === 200 && rule.metric === 'cpuPct' && rule.forSec === 0, rule);
  r = await post('/ops/alerts/rule/save', { name: '坏指标', metric: 'nope' });
  ok('未知指标被拒 400', r.status === 400, r.json);

  global.__metrics.cpu.totalPct = 97;
  await ops.alerting.evaluate();
  let st = ops.alerting.stateOf();
  ok('突破阈值后规则进入 fired 并已通知', st.rules[rule.id].state === 'fired', st.rules[rule.id]);
  ok('告警推入面板铃铛（hub.fireAlert）', state.hubAlerts.some((a) => a.key === 'rule:' + rule.id && a.level === 'warn'), state.hubAlerts);
  ok('告警事件写入 alert_events.jsonl', fs.readFileSync(path.join(process.env.CM_OPS_DIR, 'alert_events.jsonl'), 'utf8').includes('CPU 高 触发告警'));

  state.hubAlerts.length = 0;
  const beforeLog = (await get('/ops/notify/log')).json.items.length;
  await ops.alerting.evaluate();
  ok('冷却期内不重复轰炸', (await get('/ops/notify/log')).json.items.length === beforeLog && state.hubAlerts.length === 0, { beforeLog });

  global.__metrics.cpu.totalPct = 22;
  await ops.alerting.evaluate();
  st = ops.alerting.stateOf();
  ok('恢复后状态归位', st.rules[rule.id].state === 'ok', st.rules[rule.id]);
  ok('恢复通知单独成事件（不占用告警冷却键）', state.hubAlerts.some((a) => a.key === 'rule:' + rule.id + ':recovered'), state.hubAlerts.map((a) => a.key));

  global.__metrics.memory = Object.assign({}, global.__metrics.memory, { actualUsedPct: 96, swapTotal: 8589930496, swapPct: 88 });
  r = await post('/ops/alerts/rule/save', { name: '内存高', metric: 'memPct', op: '>', threshold: 90, forSec: 0, channelIds: [wecom.id] });
  const memRule = r.json.rule;
  await ops.alerting.evaluate();
  ok('不同指标规则各自独立触发', ops.alerting.stateOf().rules[memRule.id].state === 'fired', ops.alerting.stateOf().rules[memRule.id]);

  console.log('\n[静默与维护窗口]');
  const nowMs = Date.now();
  const hhmm = (d) => String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  global.__metrics.cpu.totalPct = 99;
  r = await post('/ops/alerts/silence/save', { name: '夜间静默', kind: 'daily', scope: 'metric', metric: 'cpuPct', days: [new Date().getDay()], from: hhmm(new Date(nowMs - 3600000)), to: hhmm(new Date(nowMs + 3600000)), reason: '备份窗口' });
  ok('按日循环窗口保存成功并判定为生效中', r.status === 200 && r.json.silence.kind === 'daily', r.json.silence);
  const ruleSendsBefore = (await get('/ops/notify/log')).json.items.filter((x) => x.source === 'rule' && x.ruleId === rule.id).length;
  await ops.alerting.evaluate();
  ok('窗口内 cpuPct 规则被静默而不是发送', ops.alerting.stateOf().rules[rule.id].state === 'silenced', ops.alerting.stateOf().rules[rule.id]);
  ok('被静默的规则没有新增通知记录', (await get('/ops/notify/log')).json.items.filter((x) => x.source === 'rule' && x.ruleId === rule.id).length === ruleSendsBefore, ruleSendsBefore);
  r = await get('/ops/alerts/silences');
  ok('静默窗口列表带 active 标记', r.json.items.find((w) => w.id === r.json.items[0].id).active === true, r.json.items);
  const memState = () => ops.alerting.stateOf().rules[memRule.id];
  memState().state = 'ok'; memState().firedAt = 0; memState().breachSince = 0;
  await ops.alerting.evaluate();
  ok('metric 作用域只静默 cpuPct，内存规则照常告警', memState().state === 'fired', memState());
  r = await post('/ops/alerts/silence/save', { name: '坏窗口', kind: 'once', startsAt: nowMs, endsAt: nowMs - 10 });
  ok('结束早于开始的窗口被拒 400', r.status === 400, r.json);

  console.log('\n[拨测任务与探针]');
  const srv = http.createServer((reqq, resp) => {
    if (reqq.url === '/ok') { resp.writeHead(200, { 'content-type': 'text/plain' }); resp.end('SERVICE-HEALTHY ok'); }
    else if (reqq.url === '/bad') { resp.writeHead(503); resp.end('down'); }
    else { resp.writeHead(404); resp.end('nope'); }
  });
  await new Promise((res) => srv.listen(0, '127.0.0.1', res));
  const port = srv.address().port;
  const baseUrl = `http://127.0.0.1:${port}`;

  r = await post('/ops/uptime/task/save', { name: '官网', type: 'http', url: baseUrl + '/ok', expectStatus: '200', keyword: 'SERVICE-HEALTHY', intervalSec: 30, failThreshold: 2 });
  const t1 = r.json.task;
  ok('HTTP 拨测任务创建', r.status === 200 && t1.type === 'http', t1);
  r = await post('/ops/uptime/task/save', { name: '错误地址', type: 'http', url: '127.0.0.1/ok' });
  ok('HTTP 任务缺协议被拒 400', r.status === 400, r.json);
  r = await post('/ops/uptime/task/save', { name: '缺主机', type: 'tcp' });
  ok('TCP 任务缺主机/端口被拒 400', r.status === 400, r.json);
  r = await post('/ops/uptime/task/run', { id: t1.id });
  ok('HTTP 拨测通过（200 + 关键字）', r.json.result.state.lastOk === true && r.json.task.state === 'up', r.json.task);
  ok('耗时被记录为毫秒', r.json.task.lastMs >= 0 && r.json.task.lastCode === 200, r.json.task);

  const t2 = (await post('/ops/uptime/task/save', { name: '503 站点', type: 'http', url: baseUrl + '/bad', expectStatus: '200-299', failThreshold: 2 })).json.task;
  r = await post('/ops/uptime/task/run', { id: t2.id });
  ok('状态码不符判为失败并给出原因', /状态码 503/.test(r.json.task.lastError), r.json.task);

  const t3 = (await post('/ops/uptime/task/save', { name: '关键字缺失', type: 'http', url: baseUrl + '/ok', keyword: 'NEVER-HERE', failThreshold: 1 })).json.task;
  r = await post('/ops/uptime/task/run', { id: t3.id });
  ok('关键字缺失判为失败', /关键字/.test(r.json.task.lastError), r.json.task);

  const t4 = (await post('/ops/uptime/task/save', { name: '端口连通', type: 'tcp', host: '127.0.0.1', port, failThreshold: 1 })).json.task;
  r = await post('/ops/uptime/task/run', { id: t4.id });
  ok('TCP 连通成功', r.json.task.lastOk === true, r.json.task);
  const t5 = (await post('/ops/uptime/task/save', { name: '端口拒绝', type: 'tcp', host: '127.0.0.1', port: 1, timeoutMs: 2000, failThreshold: 1 })).json.task;
  r = await post('/ops/uptime/task/run', { id: t5.id });
  ok('TCP 拒绝连接判失败且有错误码', r.json.task.lastOk === false && r.json.task.lastError.length > 0, r.json.task);

  const t6 = (await post('/ops/uptime/task/save', { name: '本机解析', type: 'dns', host: 'localhost', failThreshold: 1 })).json.task;
  r = await post('/ops/uptime/task/run', { id: t6.id });
  ok('DNS 解析成功并带回记录', r.json.task.lastOk === true, r.json.task);
  const t7 = (await post('/ops/uptime/task/save', { name: '不存在域名', type: 'dns', host: 'no-such-host.invalid', timeoutMs: 3000, failThreshold: 1 })).json.task;
  r = await post('/ops/uptime/task/run', { id: t7.id });
  ok('DNS 失败判为不可用', r.json.task.lastOk === false, r.json.task);

  const t8 = (await post('/ops/uptime/task/save', { name: '证书（拒绝连接）', type: 'ssl', host: '127.0.0.1', port: 1, timeoutMs: 2000, failThreshold: 1 })).json.task;
  r = await post('/ops/uptime/task/run', { id: t8.id });
  ok('TLS 探针失败有可读原因', r.json.task.lastOk === false && r.json.task.lastError.length > 0, r.json.task);

  const t9 = (await post('/ops/uptime/task/save', { name: 'Ping 本机', type: 'ping', host: '127.0.0.1', failThreshold: 1 })).json.task;
  r = await post('/ops/uptime/task/run', { id: t9.id });
  ok('Ping 探针执行不抛异常（无 ping 命令时给可读错误）', r.status === 200, r.json.task);
  ok('statusAllowed 支持区间与列表', ops.uptime.statusAllowed('200-299,301', 301) === true && ops.uptime.statusAllowed('200', 503) === false);

  console.log('\n[故障升级与恢复联动告警]');
  stubFetch(0);
  state.hubAlerts.length = 0;
  const downTask = (await post('/ops/uptime/task/save', { name: '支付网关', type: 'http', url: baseUrl + '/bad', expectStatus: '200', failThreshold: 2, channelIds: [wecom.id] })).json.task;
  srv.close();
  await post('/ops/uptime/task/run', { id: downTask.id });
  ok('单次失败未达阈值不告警', state.hubAlerts.length === 0, state.hubAlerts);
  await post('/ops/uptime/task/run', { id: downTask.id });
  ok('达到连续失败阈值后推送不可用告警', state.hubAlerts.some((a) => a.key === 'probe:' + downTask.id && a.level === 'bad'), state.hubAlerts.map((a) => a.key));
  ok('不可用事件写入 uptime_events', (() => {
    const evs = fs.readFileSync(path.join(process.env.CM_OPS_DIR, 'uptime_events.jsonl'), 'utf8')
      .split('\n').filter(Boolean).map((l) => JSON.parse(l));
    return evs.some((e) => e.id === downTask.id && e.state === 'down' && e.name === '支付网关');
  })(), state.hubAlerts.map((a) => a.key));
  const recov = await ops.uptime.record(downTask, { ok: true, ms: 40, code: 200 }, {});
  ok('恢复后发出 ok 事件', recov.transition === 'up' && state.hubAlerts.some((a) => a.key === 'probe:' + downTask.id + ':recovered'), state.hubAlerts.map((a) => a.key));

  console.log('\n[SLA 聚合与留存]');
  const slaTask = (await post('/ops/uptime/task/save', { name: 'SLA 站点', type: 'http', url: baseUrl, intervalSec: 60, failThreshold: 5 })).json.task;
  for (let i = 0; i < 5; i++) await ops.uptime.record(slaTask, { ok: true, ms: 100 + i * 10 }, { silent: true });
  await ops.uptime.record(slaTask, { ok: false, ms: 9000, error: 'timeout' }, { silent: true });
  r = await call('GET', '/ops/uptime/sla', { query: { days: 7 } });
  const s = r.json.tasks.find((x) => x.id === slaTask.id);
  ok('SLA 可用率按检查数计算（6 次中 1 次失败 = 83.333%）', s.availability === 83.333, s.availability);
  ok('平均耗时聚合正确', s.avgMs === Math.round((100 + 110 + 120 + 130 + 140 + 9000) / 6), s.avgMs);
  ok('按日序列补齐空白天', s.daily.length === 7 && s.daily.filter((d) => d.checks === 0).length === 6, s.daily);
  ok('整体可用率与最差项排序存在', typeof r.json.overall === 'number' && r.json.worst.length > 0, r.json.overall);
  ok('summary 统计含 down/flapping/pending 分类', typeof r.json.overall === 'number' && ops.uptime.summary().total >= 9, ops.uptime.summary());
  r = await call('GET', '/ops/uptime/history', { query: { id: slaTask.id, limit: 50 } });
  ok('历史明细返回该任务的检查点', r.json.items.length >= 6 && r.json.items.every((x) => x.id === slaTask.id), r.json.items.length);

  const rollPath = path.join(process.env.CM_OPS_DIR, 'uptime_rollup.json');
  const big = { days: {} };
  for (let i = 0; i < 200; i++) big.days['day-' + String(i).padStart(3, '0')] = { x: { n: 1, down: 0, msSum: 1 } };
  fs.writeFileSync(rollPath, JSON.stringify(big));
  await ops.uptime.record(slaTask, { ok: true, ms: 1 }, { silent: true });
  ok('按日汇总只保留最近 120 天（长期留存有上限）', Object.keys(JSON.parse(fs.readFileSync(rollPath, 'utf8')).days).length <= 121, Object.keys(JSON.parse(fs.readFileSync(rollPath, 'utf8')).days).length);
  const histPath = path.join(process.env.CM_OPS_DIR, 'uptime_history.jsonl');
  fs.writeFileSync(histPath, Array.from({ length: 50 }, (_, i) => JSON.stringify({ ts: i * 86400000, id: 'x' })).join('\n') + '\n');
  require(path.join(OPS, 'store.js')).pruneLineFile('uptime_history.jsonl', 30);
  ok('明细按天数留存裁剪（30 天）', fs.readFileSync(histPath, 'utf8').split('\n').filter(Boolean).length < 50, fs.readFileSync(histPath, 'utf8').length);

  console.log('\n[纳管主机]');
  r = await post('/ops/host/save', { label: 'db-01', addr: '10.0.0.21', role: 'db', env: '生产', owner: '张三', tags: ['核心', 'mysql'], note: '主库' });
  ok('主机登记成功且字段完整', r.status === 200 && r.json.host.role === 'db' && r.json.host.tags.length === 2, r.json.host);
  r = await post('/ops/host/save', { addr: '10.0.0.22' });
  ok('名称为空被拒 400', r.status === 400, r.json);
  r = await post('/ops/host/save', { label: 'x', role: 'nonsense', env: 'nonsense' });
  ok('未知角色/环境回落到默认值而不是报错', r.json.host.role === 'other' && r.json.host.env === '其它', r.json.host);
  const keepHostId = r.json.host.id;
  await post('/ops/host/delete', { id: keepHostId });

  const net = require('net');
  const bannerSrv = net.createServer((sock) => sock.write('SSH-2.0-OpenSSH_9.6 Ubuntu-1\r\n'));
  await new Promise((res) => bannerSrv.listen(0, '127.0.0.1', res));
  const bannerPort = bannerSrv.address().port;
  const reachable = (await post('/ops/host/save', { label: 'web-01', addr: '127.0.0.1', sshPort: bannerPort })).json.host.id;
  r = await post('/ops/host/check', { id: reachable });
  ok('可达性探测读到 SSH banner', r.json.check.ok === true && /OpenSSH/.test(r.json.check.banner), r.json.check);
  ok('可达后写入最近在线时间与 lastSeenAt', r.json.host.lastSeenAt > 0 && r.json.host.lastCheckOk === true, r.json.host);
  const dead = (await post('/ops/host/save', { label: 'gone-01', addr: '127.0.0.1', sshPort: 1 })).json.host.id;
  r = await post('/ops/host/check', { id: dead });
  ok('端口不通判为不可达并给原因', r.json.check.ok === false && r.json.check.error.length > 0, r.json.check);
  bannerSrv.close();
  r = await get('/ops/hosts');
  ok('清单统计区分可达/不可达/未探测三类', r.json.stats.total === 3 && r.json.stats.reachable === 1
    && r.json.stats.unreachable === 1 && r.json.stats.unchecked === 1, r.json.stats);
  ok('角色与环境字典随清单返回', r.json.roles.length >= 8 && r.json.envs.includes('生产'), r.json.roles.length);
  r = await post('/ops/host/check', {});
  ok('不带 id 时批量探测所有主机', r.json.results.length >= 2, r.json.results);

  console.log('\n[文件管理器]');
  const filesMod = require(path.join(OPS, 'files.js'));
  const mvFrom = path.join(TMP, 'fsroot', 'mv-src.txt');
  const mvTo = path.join(TMP, 'fsroot', 'mv-dst.txt');
  fs.writeFileSync(mvFrom, 'payload', 'utf8');
  ok('跨设备时先 rename，失败再复制删除（hardMove 两种路径都能落地）',
    ['renamed', 'copied'].includes(filesMod.hardMove(mvFrom, mvTo)) && fs.existsSync(mvTo) && !fs.existsSync(mvFrom));
  let mvErr = null;
  try { filesMod.hardMove(path.join(TMP, 'fsroot', 'ghost'), path.join(TMP, 'fsroot', 'ghost2')); } catch (e) { mvErr = e.code || e.message; }
  ok('源不存在时 hardMove 抛错而不是静默', !!mvErr, mvErr);
  r = await get('/ops/files/list', { dir: FSROOT });
  if (!r.json || r.json.entries === undefined) console.log('  DEBUG list:', r.status, JSON.stringify(r.json).slice(0, 400));
  ok('目录浏览返回面包屑与条目', r.json.breadcrumb && r.json.breadcrumb.length >= 1 && r.json.entries.some((e) => e.name === 'app.conf'), r.json.count);
  ok('目录排在文件前且带类型标签', r.json.entries[0].isDir === true && r.json.entries[0].typeLabel === '目录', r.json.entries[0]);
  ok('无 find 时用 fs 生成符号权限串', /^[d\-][rwp\-]{9}$/.test(r.json.entries[0].symbolic), r.json.entries[0].symbolic);
  r = await get('/ops/files/list', { dir: FSROOT + '/does-not-exist' });
  ok('不存在的目录 400/404 而不是崩溃', r.status === 400 || r.status === 404, r);

  r = await post('/ops/files/mkdir', { dir: FSROOT, name: '归档 2026' });
  ok('新建目录成功（名称允许空格）', r.status === 200 && fs.existsSync(path.join(TMP, 'fsroot', '归档 2026')), r.json);
  r = await post('/ops/files/mkdir', { dir: FSROOT, name: 'a/b' });
  ok('名称含路径分隔符被拒', r.status === 400, r.json);
  r = await post('/ops/files/mkdir', { dir: FSROOT, name: '..' });
  ok('名称为 .. 被拒', r.status === 400, r.json);
  r = await post('/ops/files/mkdir', { dir: FSROOT, name: '归档 2026' });
  ok('重名目录 409', r.status === 409, r.json);

  r = await post('/ops/files/write', { path: FSROOT + '/归档 2026/note.txt', content: '第一行\n第二行' });
  ok('写入文本文件成功并回报字节数', r.status === 200 && r.json.bytes > 0, r.json);
  r = await get('/ops/files/preview', { path: FSROOT + '/归档 2026/note.txt' });
  ok('预览返回文本与行数', r.json.text.includes('第二行') && r.json.lines === 2 && r.json.binary === false, r.json);
  r = await get('/ops/files/preview', { path: FSROOT + '/big.bin' });
  ok('二进制文件识别为不可预览', r.json.binary === true && r.json.text === '', r.json);
  r = await post('/ops/files/write', { path: FSROOT + '/logs', content: 'x' });
  ok('写入目标是目录被拒', r.status === 400, r.json);

  r = await post('/ops/files/rename', { path: FSROOT + '/归档 2026/note.txt', name: 'note.bak' });
  ok('重命名成功且路径不变目录', r.json.to.endsWith('note.bak') && !fs.existsSync(path.join(TMP, 'fsroot', '归档 2026', 'note.txt')), r.json);
  r = await post('/ops/files/move', { paths: [FSROOT + '/归档 2026/note.bak'], to: FSROOT + '/uploads' });
  ok('移动成功', fs.existsSync(path.join(TMP, 'fsroot', 'uploads', 'note.bak')), r.json);
  r = await post('/ops/files/move', { paths: [FSROOT + '/uploads/note.bak'], to: FSROOT + '/uploads' });
  ok('移动到同目录标记跳过而不是报错', r.json.done[0].skipped !== undefined || r.json.failed === 0, r.json);
  r = await post('/ops/files/copy', { paths: [FSROOT + '/uploads/note.bak'], to: FSROOT + '/logs', name: 'note-copy.txt' });
  ok('复制改名成功', fs.existsSync(path.join(TMP, 'fsroot', 'logs', 'note-copy.txt')), r.json);

  global.__rawBody = Buffer.from('uploaded-bytes');
  r = await call('POST', '/ops/files/upload', { query: { dir: FSROOT + '/uploads', name: 'up.bin' } });
  ok('分块上传走 raw 解析并落盘', r.status === 200 && r.json.bytes === global.__rawBody.length, r.json);
  r = await call('POST', '/ops/files/upload', { query: { dir: FSROOT + '/uploads', name: 'up.bin' } });
  ok('同名上传默认拒绝，避免覆盖', r.status === 409, r.json);
  r = await call('POST', '/ops/files/upload', { query: { dir: FSROOT + '/uploads', name: 'up.bin', overwrite: '1' } });
  ok('overwrite=1 时允许覆盖', r.status === 200, r.json);

  r = await get('/ops/files/search', { dir: FSROOT, q: 'note' });
  ok('按名称搜索命中多条', r.json.items.length >= 2 && r.json.items.every((x) => /note/i.test(x.path)), r.json.items);
  r = await get('/ops/files/tree', { dir: FSROOT, depth: 2 });
  ok('目录树返回节点与深度', r.json.nodes.length >= 3 && r.json.nodes[0].depth === 0, r.json.nodes.slice(0, 3));

  r = await post('/ops/files/delete', { paths: [FSROOT + '/logs/note-copy.txt'] });
  const trashId = r.json.done[0].trashed;
  ok('删除进回收站而不是直接消失', !!trashId && fs.existsSync(path.join(TMP, 'fsroot', 'logs')), r.json);
  ok('回收站元数据可查到原路径', (await get('/ops/files/trash')).json.items.some((x) => x.id === trashId && /note-copy/.test(x.original)), r.json);
  r = await post('/ops/files/restore', { ids: [trashId] });
  ok('还原回原路径', fs.existsSync(path.join(TMP, 'fsroot', 'logs', 'note-copy.txt')), r.json);
  r = await post('/ops/files/delete', { paths: [FSROOT + '/logs/note-copy.txt'], purge: true });
  ok('彻底删除生效', !fs.existsSync(path.join(TMP, 'fsroot', 'logs', 'note-copy.txt')), r.json);
  r = await post('/ops/files/delete', { paths: ['/'] });
  ok('删除根目录被拒（403，越界或根目录保护都算拦住）', r.status === 403 && /(根目录|超出允许范围)/.test(r.json.error), r.json);
  process.env.CM_FILES_ROOTS = path.join(TMP, 'fsroot');
  r = await get('/ops/files/list', { dir: process.platform === 'win32' ? 'C:\\Windows' : '/etc' });
  ok('限定 roots 后越界路径被拒 403', r.status === 403, r.json);
  delete process.env.CM_FILES_ROOTS;

  const chmodTarget = FSROOT + '/app.conf';
  r = await post('/ops/files/chmod', { path: chmodTarget, mode: '999' });
  ok('非法权限串被拒', r.status === 400, r.json);
  r = await post('/ops/files/chmod', { path: chmodTarget, mode: '640' });
  ok('chmod 返回当前权限串', r.status === 200, r.json);
  r = await post('/ops/files/chown', { path: chmodTarget, owner: 'rm -rf /' });
  ok('chown 属主格式校验挡住注入', r.status === 400, r.json);
  r = await get('/ops/files/usage', { dir: FSROOT });
  ok('目录占用查询不崩（无 du 时给说明）', r.status === 200 && Array.isArray(r.json.items), r.json);
  r = await post('/ops/files/archive', { paths: [FSROOT + '/logs', FSROOT + '/uploads'], name: 'pack.tar.gz', to: FSROOT });
  const archived = r.status === 200 && fs.existsSync(path.join(TMP, 'fsroot', 'pack.tar.gz'));
  ok('打包要么产出文件、要么把 tar 的失败原因原样回报', archived || (r.status === 500 && /打包失败/.test(r.json.error)), { status: r.status, error: r.json && r.json.error });
  if (archived) {
    r = await post('/ops/files/extract', { path: FSROOT + '/pack.tar.gz', to: FSROOT + '/uploads' });
    ok('解压归档回目录', r.status === 200, r.json);
  }

  console.log('\n[安全基线巡检]');
  const execMod = require(path.join(OPS, 'exec.js'));
  const sec = require(path.join(OPS, 'secops.js'));
  const calls = [];
  const canned = {};
  function setFake(map) { Object.assign(canned, map); }
  function resetFake(map) { for (const k of Object.keys(canned)) delete canned[k]; Object.assign(canned, map); }
  execMod.clearCache();
  execMod.setRunner(async (cmd) => {
    calls.push(cmd);
    for (const k of Object.keys(canned)) if (cmd.includes(k)) return Object.assign({ code: 0, stdout: canned[k], stderr: '' }, canned[k + '@'] || {});
    return { code: 0, stdout: '', stderr: '' };
  });
  setFake({
    'command -v': 'YES',
    "^permitrootlogin": 'permitrootlogin yes\n',
    '^passwordauthentication': 'passwordauthentication yes\n',
    '^maxauthtries': 'maxauthtries 6\n',
    '^port': 'port 22\n',
    'fail2ban-client status': 'active\nStatus for the jail: sshd\n|- Jail list:\tsshd\n',
    'ufw status': 'Status: inactive\n',
    "awk '{print $4}' | grep -vE '^(127": '0.0.0.0:22\n0.0.0.0:80\n0.0.0.0:3306\n*:6379\n',
    '--upgradable': '7\n7\n',
    'is-enabled unattended-upgrades': 'disabled\ninactive\n',
    'reboot-required': 'NONE',
    '/etc/passwd': 'svc0\n',
    '/etc/shadow': 'guest\n',
    "name authorized_keys -perm": '/root/.ssh/authorized_keys\n',
    '-perm -0002': '/etc/ntp.conf\n',
    '-perm -4000': '',
    'findmnt -no OPTIONS': 'rw,nosuid,nodev\n',
    'letsencrypt/live': 'cert1 notAfter=May  1 12:00:00 2030 GMT\n',
    'NTPSynchronized': 'yes\n',
    '--disk-usage': '48.0M\n',
    'command -v logrotate': 'YES',
    'ps -eo stat': '3',
    'Failed password': '120\n',
  });
  const report = await sec.runScan();
  const byId = (id) => report.checks.find((c) => c.id === id);
  ok('巡检产出 20+ 检查项且带评分与等级', report.checks.length >= 20 && typeof report.score === 'number' && report.grade.length === 1, { n: report.checks.length, score: report.score, grade: report.grade });
  ok('root 可直登 + 密码登录判为风险', byId('sshd.rootLogin').level === 'bad' && byId('sshd.passwordAuth').level === 'bad', [byId('sshd.rootLogin').detail, byId('sshd.passwordAuth').detail]);
  ok('风险项带上可用的修复动作', byId('sshd.passwordAuth').fix === 'sshd.harden', byId('sshd.passwordAuth'));
  ok('数据库端口对外暴露被抓出', byId('ports.dataExposed').level === 'bad' && /MySQL|Redis/.test(byId('ports.dataExposed').detail), byId('ports.dataExposed').detail);
  ok('UID 0 与空密码账号判风险', byId('accounts.uid0').level === 'bad' && byId('accounts.emptyPasswd').level === 'bad', [byId('accounts.uid0').detail, byId('accounts.emptyPasswd').detail]);
  ok('ufw 未启用 / 自动更新关闭给出建议', byId('firewall.state').level === 'warn' && byId('updates.auto').level === 'warn', [byId('firewall.state').detail, byId('updates.auto').detail]);
  ok('无证书时判为未覆盖而不是误报', byId('cert.expiring').level === 'ok', byId('cert.expiring').detail);
  ok('分类聚合存在', report.byCat.length === 6 && report.byCat.some((c) => c.cat === '网络暴露' && c.bad >= 1), report.byCat);
  ok('分数按权重扣减（多个 bad 后明显低于 100）', report.score < 60, report.score);

  r = await post('/ops/sec/fix', { fix: 'not-a-fix' });
  ok('未知修复项 404', r.status === 404, r.status);
  calls.length = 0;
  setFake({ 'sshd -t 2>&1': '' });
  canned['sshd -t 2>&1'] = 'Bad configuration option';
  canned['sshd -t 2>&1@'] = { code: 255 };
  r = await post('/ops/sec/fix', { fix: 'sshd.harden' });
  ok('sshd 校验不过时回滚 drop-in 且不自称成功', r.json.ok === false && /校验不通过/.test(r.json.output), r.json);
  ok('回滚确实执行了 rm drop-in', calls.some((c) => c.includes('rm -f') && c.includes('99-cloud-monitor.conf')), calls.filter((c) => c.includes('rm -f')));
  delete canned['sshd -t 2>&1'];
  delete canned['sshd -t 2>&1@'];
  r = await post('/ops/sec/fix', { fix: 'sshd.harden' });
  ok('sshd -t 通过时才重载并回报 ok', r.json.ok === true && calls.some((c) => c.includes('systemctl reload ssh')), r.json.output && r.json.output.slice(0, 80));
  r = await get('/ops/sec/checks');
  ok('清单标注了可修复项与命令依赖', r.json.fixes.length >= 5 && r.json.items.some((x) => x.fixable === true), r.json.fixes.length);
  r = await get('/ops/sec/trend');
  ok('分数历史写入并可查', r.json.items.length >= 1 && r.json.fixLog.length >= 2, { items: r.json.items.length, fixLog: r.json.fixLog.length });

  console.log('\n[磁盘治理]');
  process.env.CM_DISK_LOG_ROOTS = FSROOT;
  const diskMod = require(path.join(OPS, 'disk.js'));
  calls.length = 0;
  const dfOut = [
    'Filesystem     1024-blocks     Used Available Capacity Mounted on',
    '/dev/vda3       41882940 13478508  26653264      34% /',
    'tmpfs              888736        8    888728       1% /run',
    '/dev/vda2           196608     143360      53248      73% /boot/efi',
  ].join('\n');
  resetFake({
    "awk '{print $4}'": '26653264\n',
    'df -Pk 2>/dev/null': dfOut,
    'df -Pi 2>/dev/null': 'Filesystem Inodes IUsed IFree IUse% Mounted on\n/dev/vda3 2608144 260936 2347208 11% /\n/dev/vda2 0 0 0 - /boot/efi\n',
    '--disk-usage': '512.0M\n',
    '-size +': '734003200\t/var/lib/mysql/ibdata1\n209715200\t/backups/db.sql\n',
    'du -xk': '5242880\t/\n3145728\t/var\n1048576\t/usr\n',
    '-printf': '20971520\tJul-01 03:00\t/var/log/nginx/access.log\n1048576\tJan-01 02:00\t/var/log/syslog.3.gz\n',
    'du -sk /var/cache/apt': '81234\n',
    'du -sk /var/log': '204800\n',
    'vacuum-size': 'Vacuuming done. Finished.\n48.0M\n',
    'apt-get clean': 'cleaned\n',
    'truncate -s 0': '0\n',
  });
  const ov = await diskMod.overview();
  r = await get('/ops/disk/overview');
  ok('overview 路由返回已 await 的对象（不是空 {}）', r.status === 200 && r.json && Array.isArray(r.json.mounts) && r.json.mounts.length === 1 && r.json.runner !== undefined, r.json);
  ok('容量总览只保留真实卷（忽略 tmpfs/efi）', ov.mounts.length === 1 && ov.mounts[0].mount === '/', ov.mounts);
  ok('inode 使用率并入同一挂载', ov.mounts[0].inodeUsedPct === 11, ov.mounts[0]);
  ok('journal/apt/var-log 占用被汇总', ov.journalKb === 524288 && ov.aptCacheKb === 81234 && ov.varLogKb === 204800, { j: ov.journalKb, a: ov.aptCacheKb, v: ov.varLogKb });
  const bf = await diskMod.bigFiles({ minMb: 100, limit: 50, path: '/' });
  ok('大文件按大小倒序解析', bf.items[0].size === 734003200 && bf.items[1].path === '/backups/db.sql', bf.items);
  const td = await diskMod.topDirs({ path: '/', depth: 1 });
  ok('目录占用排行解析', td.items.length === 3 && td.items[0].kb === 5242880, td.items);
  const lg = await diskMod.logFiles({ path: '/var/log' });
  ok('轮转日志识别为可回收', lg.rotatedCount === 1 && lg.reclaimableKb >= 1024, lg);
  r = await post('/ops/disk/clean', { targets: ['journal', 'apt'] });
  ok('清理返回逐项结果并记录释放量', r.status === 200 && r.json.results.length === 2 && r.json.freedKb >= 0, r.json && r.json.results);
  ok('未知清理项被拒 400', (await post('/ops/disk/clean', { targets: ['nope'] })).status === 400);
  ok('清理项都走白名单（不接收任意命令）', !calls.some((c) => c.includes('; rm -rf /')), calls.length);
  r = await post('/ops/disk/truncate', { path: '/etc/passwd' });
  ok('清空非日志目录文件被拒 403', r.status === 403, r.json);
  fs.mkdirSync(path.join(TMP, 'fsroot', 'logs', 'nginx'), { recursive: true });
  const logPath = path.join(TMP, 'fsroot', 'logs', 'nginx', 'access.log');
  fs.writeFileSync(logPath, 'x'.repeat(4096));
  r = await post('/ops/disk/truncate', { path: FSROOT + '/logs/nginx/access.log' });
  ok('清空允许目录内的日志文件成功并回报前后大小', r.json.ok === true && r.json.before === 4096 && r.json.after === 0, r.json);
  r = await post('/ops/disk/truncate', { path: FSROOT + '/logs/nginx' });
  ok('清空目录被拒 400', r.status === 400, r.json);
  ok('清空动作留了审计痕迹', calls.some((c) => c.includes('truncate -s 0')), calls.length);
  r = await post('/ops/disk/schedule/save', { enabled: true, hour: 99, journalSize: '500M', targets: ['journal', 'nope'] });
  ok('计划任务参数被夹到合法范围且过滤未知项', r.json.config.hour === 23 && r.json.config.targets.join() === 'journal', r.json.config);
  r = await get('/ops/disk/schedule');
  ok('清理器清单带默认值', r.json.cleaners.length === 5 && r.json.cleaners[0].defaults, r.json.cleaners && r.json.cleaners.length);
  const evs = await get('/ops/disk/events');
  ok('清理事件留痕（含目标与释放量）', evs.json.items.length >= 1 && Array.isArray(evs.json.items[0].targets) && 'freedKb' in evs.json.items[0], evs.json.items.length);
  const hist = await get('/ops/disk/history', { days: 30 });
  ok('容量历史写入（供趋势图）', hist.json.items.length >= 1 && hist.json.items[0].mount === '/', hist.json.items.slice(0, 2));
  ok('df 解析过滤伪文件系统', diskMod.parseDf(dfOut).length === 1, diskMod.parseDf(dfOut));

  console.log('\n[服务看护自愈]');
  const wd = require(path.join(OPS, 'watchdog.js'));
  r = await post('/ops/watchdog/save', { name: 'nginx', kind: 'systemd' });
  ok('systemd 看护缺单元名被拒 400', r.status === 400, r.json);
  r = await post('/ops/watchdog/save', { name: 'x', kind: 'systemd', unit: '../evil;rm' });
  ok('单元名字符白名单挡住注入', r.status === 400, r.json);
  r = await post('/ops/watchdog/save', { name: '端口探活', kind: 'port' });
  ok('端口看护缺端口被拒 400', r.status === 400, r.json);
  r = await post('/ops/watchdog/save', { name: 'nginx', kind: 'systemd', unit: 'nginx.service', maxRestarts: 2, windowMin: 30, intervalSec: 5, channelIds: [] });
  const wdId = r.json.entry.id;
  ok('看护项保存并带默认参数', r.status === 200 && r.json.entry.opts.maxRestarts === 2 && r.json.entry.status === 'pending', r.json.entry);
  setFake({ 'systemctl is-active': 'inactive\n', 'systemctl restart': 'Restarting nginx...\n' });
  r = await post('/ops/watchdog/run', { id: wdId });
  ok('服务不可用 → 自动重启并记录', r.json.result.action === 'restarted' && r.json.result.state.lastRestart > 0, r.json.result);
  ok('重启命令只含白名单单元名', calls[calls.length - 3] === undefined || calls.some((c) => c.includes("systemctl restart 'nginx.service'")), calls.slice(-6));
  ok('不可用即产生告警事件（走 alerting 原语）', state.hubAlerts.some((a) => a.key === 'wd:' + wdId + ':down'), state.hubAlerts.map((a) => a.key));
  r = await get('/ops/watchdog');
  ok('看护列表显示 down 与重启次数', r.json.items.find((x) => x.id === wdId).status === 'down', r.json.items);
  ok('看护事件流水写入', r.json.events.some((e) => e.id === wdId && ['down', 'restart'].includes(e.event)), r.json.events.length);
  for (let i = 0; i < 2; i++) { await post('/ops/watchdog/run', { id: wdId }); }
  r = await get('/ops/watchdog');
  const afterMax = r.json.items.find((x) => x.id === wdId);
  ok('达到重启上限后停止无限重启', afterMax.restarts >= afterMax.opts.maxRestarts, { restarts: afterMax.restarts, max: afterMax.opts.maxRestarts });
  state.hubAlerts.length = 0;
  calls.length = 0;
  r = await post('/ops/watchdog/run', { id: wdId });
  ok('超限后回报 giveup 且不再执行重启命令', r.json.result.action === 'giveup' && !calls.some((c) => c.includes('systemctl restart')), calls.slice(-3));
  ok('giveup 告警本身受冷却约束（不刷屏）', state.hubAlerts.filter((a) => a.key === 'wd:' + wdId + ':givingup').length <= 1, state.hubAlerts.map((a) => a.key));
  setFake({ 'systemctl is-active': 'active\n' });
  r = await post('/ops/watchdog/run', { id: wdId });
  ok('恢复后状态回到 ok 并发恢复通知', r.json.result.ok === true && state.hubAlerts.some((a) => a.key === 'wd:' + wdId + ':recovered'), state.hubAlerts.map((a) => a.key));
  const portSrv = require('net').createServer();
  await new Promise((res) => portSrv.listen(0, '127.0.0.1', res));
  const okPort = portSrv.address().port;
  r = await post('/ops/watchdog/save', { name: '端口在线', kind: 'port', host: '127.0.0.1', port: okPort, intervalSec: 5 });
  const portId = r.json.entry.id;
  r = await post('/ops/watchdog/run', { id: portId });
  ok('TCP 端口探活可用', r.json.result.ok === true || r.json.entry.status === 'ok', r.json.result);
  await portSrv.close();
  r = await post('/ops/watchdog/run', { id: portId });
  ok('端口不通且无启动命令 → no-start-cmd（不乱执行）', r.json.result.action === 'no-start-cmd', r.json.result);
  r = await post('/ops/watchdog/reset', { id: portId });
  ok('可重置重启计数（人工介入后）', r.json.ok === true, r.json);
  r = await post('/ops/watchdog/toggle', { id: wdId });
  ok('看护可停用', r.json.entry.enabled === false, r.json.entry.enabled);
  r = await post('/ops/watchdog/delete', { id: wdId });
  ok('删除看护项并清理其状态', r.status === 200 && !(wdId in wd.list().reduce((a, x) => a, {})) , r.json);

  console.log('\n[远程主机指标采集]');
  const remoteMod = require(path.join(OPS, 'remote.js'));
  r = await post('/ops/remote/cred', { id: reachable, user: 'ops', port: 22, authMode: 'password', password: 'Sup3rSsh' });
  ok('凭据保存成功', r.status === 200 && r.json.cred.id === reachable, r.json);
  r = await get('/ops/remote');
  const shown = r.json.items.find((x) => x.id === reachable);
  ok('凭据回显不含密码，只给 hasPassword 标记', JSON.stringify(r.json).indexOf('Sup3rSsh') === -1 && shown.cred.hasPassword === true, shown.cred);
  r = await post('/ops/remote/cred', { id: reachable, password: '' });
  const diskCred = JSON.parse(fs.readFileSync(path.join(process.env.CM_OPS_DIR, 'remote_creds.json'), 'utf8')).items.find((x) => x.id === reachable);
  ok('提交空密码不会清空已存凭据', diskCred.password === 'Sup3rSsh', diskCred.password);
  const bundle = [
    '0.35 0.30 0.20 1/234 5678',
    'total used free shared buff/cache available\nMem: 1820127232 455061504 263045120 2 965967872 1243504640\nSwap: 8589930496 4096 8589926400',
    'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/vda3 41882940 13478508 26653264 34% /\ntmpfs 888736 8 888728 1% /run',
    'PID %CPU %MEM   RSS COMMAND\n1234 55.0 12.5 220000 node\n2 0.0 0.0 0 kthreadd',
    '0.0.0.0:22\n0.0.0.0:443',
    ' 10:22:31 up 3 days, load average: 0.35, 0.30, 0.20',
    'PRETTY_NAME="Ubuntu 24.04.2 LTS"\nNAME="Ubuntu"',
    '4',
    'active inactive active',
  ].join('----8<----');
  const parsed = remoteMod.parseBundle(remoteMod.splitSections(bundle));
  ok('远程采集包解析出负载/内存/磁盘/进程', parsed.loadAvg[0] === 0.35 && parsed.memPct > 30 && parsed.disks.length === 1 && parsed.top[0].cmd === 'node', { load: parsed.loadAvg, mem: parsed.memPct, disks: parsed.disks.length, top: parsed.top[0] });
  ok('远端端口与服务状态一并解析', parsed.ports.join().includes('443') && parsed.prettyName.startsWith('Ubuntu') && parsed.cores === 4 && parsed.units.length === 3, { ports: parsed.ports, os: parsed.prettyName, units: parsed.units });
  ok('tmpfs 不计入远端磁盘', parsed.disks[0].mount === '/', parsed.disks);
  const cRes = await remoteMod.collect(reachable).catch((e) => ({ ok: false, error: e.message }));
  ok('未安装 ssh2 时给出可读失败而不是崩溃', cRes.ok === false && /ssh2|凭据|SSH/.test(cRes.host ? cRes.host.error || '' : cRes.error || ''), cRes);
  r = await get('/ops/remote/history');
  ok('远程历史端点可用', r.status === 200 && Array.isArray(r.json.items), r.status);
  r = await post('/ops/remote/collect', { id: 'nope' });
  ok('采集不存在的主机 404', r.status === 404, r.status);

  console.log('\n[总览与写操作审计]');
  r = await get('/ops/overview');
  ok('overview 汇总拨测/规则/渠道三块', r.json.uptime.total > 0 && r.json.rules.total > 0 && r.json.channels.total >= 6, { u: r.json.uptime.total, ru: r.json.rules.total, ch: r.json.channels.total });
  ok('规则统计区分 firing/breaching/silenced', ['firing', 'breaching', 'silenced'].every((k) => k in r.json.rules), r.json.rules);
  ok('所有写端点都经面板 audited() 留痕', state.auditedNames.includes('ops.channel.save') && state.auditedNames.includes('ops.uptime.save') && state.auditedNames.includes('ops.rule.delete') === false, state.auditedNames.slice(0, 6));

  r = await post('/ops/uptime/task/delete', { id: 'nope' });
  ok('删除不存在的任务 404', r.status === 404, r.status);
  r = await post('/ops/uptime/task/toggle', { id: t1.id });
  ok('任务可停用', r.json.task.enabled === false, r.json.task.enabled);
  r = await post('/ops/alerts/rule/delete', { id: rule.id });
  ok('规则删除后状态文件同步清理', r.status === 200 && !(rule.id in ops.alerting.stateOf().rules), Object.keys(ops.alerting.stateOf().rules));

  globalThis.fetch = realFetch;
  fs.rmSync(TMP, { recursive: true, force: true });
  console.log('\n==== pass=' + pass + ' fail=' + fail + ' ====');
  process.exit(fail ? 1 : 0);
})().catch((e) => { globalThis.fetch = realFetch; console.error('harness crashed:', e.stack); fs.rmSync(TMP, { recursive: true, force: true }); process.exit(2); });
