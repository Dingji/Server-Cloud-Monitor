'use strict';
/*
 * notebox（笔记 / 待办后端）代理。
 *
 * notebox 是独立的 Flask 服务（本机 127.0.0.1:<NB_PORT>），自带 SPA，但那个 SPA
 * 用的是根绝对路径（42 处 /api/…、/static/…），前缀反代到 /panel/notebox/ 会全部打错，
 * 而把它另起一个端口对外暴露又要处理跨源 cookie 与 CSP。所以这里只代理 API，
 * 界面由面板自己实现（public/js/pages/notebox.js）：面板登录态守门，notebox 的
 * 账号会话用 cookie 原样透传，谁登录的 notebox 就看到谁的数据，面板不越权。
 */
const fs = require('fs');
const http = require('http');
const { logErr } = require('./util');

const UNIT = '/etc/systemd/system/notebox.service';
const DEFAULT_PORT = 12246;
/* 面板把 notebox 会话 cookie 收在这个路径下，浏览器只在该前缀回传，
   不会串到面板自己的接口上。 */
const COOKIE_PATH = '/panel/api/notebox';

function target() {
  if (process.env.CM_NOTEBOX_URL) return process.env.CM_NOTEBOX_URL.replace(/\/+$/, '');
  try {
    const t = fs.readFileSync(UNIT, 'utf8');
    const m = /Environment\s*=\s*NB_PORT\s*=\s*(\d{2,5})/.exec(t);
    if (m) return 'http://127.0.0.1:' + m[1];
  } catch (_) { /* 没装 notebox：下面的请求会直接失败，页面按"未部署"降级 */ }
  const h = process.env.NB_HOST || '127.0.0.1';
  return 'http://' + (h === '0.0.0.0' ? '127.0.0.1' : h) + ':' + (process.env.NB_PORT || DEFAULT_PORT);
}

function installed() {
  try { return fs.existsSync(UNIT); } catch (_) { return false; }
}

/**
 * 转发一次请求。rawBody 用于 multipart 上传（req.body 是 express.raw 给的 Buffer）。
 * 只有写方法才带体：express.json 会把空请求体补成 {}，GET/DELETE 照原样序列化
 * 就变成"GET/DELETE 挂了个 JSON 体"。
 */
function forward(req, res, subPath, opts) {
  const o = opts || {};
  const base = target();
  const host = /^https?:\/\/([^:/]+)(?::(\d+))?/.exec(base);
  const method = o.method || req.method || 'GET';
  const writes = method === 'POST' || method === 'PUT' || method === 'PATCH';
  const body = o.rawBody ? (Buffer.isBuffer(req.body) ? req.body : null)
    : (writes && req.body && typeof req.body === 'object' && Object.keys(req.body).length
      ? Buffer.from(JSON.stringify(req.body), 'utf8') : null);

  const headers = {
    Accept: o.accept || 'application/json',
    'Content-Type': o.rawBody ? (req.headers['content-type'] || 'application/octet-stream') : 'application/json',
    Host: host[1] + (host[2] ? ':' + host[2] : ''),
  };
  if (req.headers.cookie) headers.Cookie = req.headers.cookie;
  /* 静态件（/uploads/*）带条件请求才有 304，别每次重传几十 KB 图片 */
  if (o.cond) {
    if (req.headers['if-none-match']) headers['If-None-Match'] = req.headers['if-none-match'];
    if (req.headers['if-modified-since']) headers['If-Modified-Since'] = req.headers['if-modified-since'];
  }
  if (body) headers['Content-Length'] = body.length;

  const upstream = http.request({
    host: host[1], port: host[2] || 80, method: method,
    path: subPath + (req._nbQuery || ''), headers, timeout: o.timeout || 20000,
  }, (ur) => {
    /* 会话 cookie 改写：notebox 发的是 Path=/，收到 /panel/api/notebox 前缀下，
       这样它不会覆盖面板其它接口的任何东西，也只在这些请求上回传。 */
    try {
      const sc = ur.headers['set-cookie'];
      const out = Object.assign({}, ur.headers);
      delete out['set-cookie'];
      delete out['transfer-encoding'];
      if (Array.isArray(sc)) {
        out['Set-Cookie'] = sc.map((c) => (/Path=/i.test(c)
          ? c.replace(/Path=[^;]*/i, 'Path=' + COOKIE_PATH)
          : c + '; Path=' + COOKIE_PATH)
          .replace(/;\s*Secure\b/gi, '')      // 面板是 http，带 Secure 就存不下
          .replace(/;\s*SameSite=[^;]*/gi, '; SameSite=Lax'));
      }
      res.writeHead(ur.statusCode, out);
      /* 304/204 本来就没有体，别去 pipe 一个永远不来的 end 之外的东西 */
      if (ur.statusCode === 304 || ur.statusCode === 204) { ur.resume(); res.end(); }
      else ur.pipe(res);
    } catch (e) {
      /* 头改写失败不能把异常抛回 HTTP parser：那会变成 "Parse Error: User callback
         error"，前端只会看到一次莫名的超时。就地回 502，并记下真实原因。 */
      logErr('notebox response:', subPath, e.message);
      ur.resume();
      if (!res.headersSent) res.status(502).json({ error: 'notebox 响应转写失败：' + e.message, noteboxDown: true });
      else res.end();
    }
  });
  upstream.on('timeout', () => upstream.destroy(new Error('notebox 响应超时')));
  upstream.on('error', (e) => {
    logErr('notebox proxy:', subPath, e.message);
    if (!res.headersSent) {
      res.status(e.code === 'ECONNREFUSED' ? 503 : 502)
        .json({ error: 'notebox 服务不可达：' + e.message, noteboxDown: true });
    } else res.end();
  });
  if (body) upstream.write(body);
  upstream.end();
}

/** 把 /notebox/xxx（可带 ?query）转成 notebox 的 /api/xxx。 */
function mount(api, express) {
  const handler = (req, res) => {
    /* `/notebox/*` 的捕获组不含前导斜杠：/notebox/bootstrap -> params[0]="bootstrap"
       拼成 /apibootstrap 会 404，斜杠必须自己补上。 */
    const sub = '/api/' + String(req.params[0] || '');
    req._nbQuery = req.originalUrl.indexOf('?') >= 0
      ? req.originalUrl.slice(req.originalUrl.indexOf('?')) : '';
    forward(req, res, sub, { rawBody: false });
  };
  /* 图片/附件上传是 multipart，express.json 不解析，这里单独收 raw */
  api.post('/notebox/assets', express.raw({ type: '*/*', limit: '210mb' }), (req, res) => {
    req._nbQuery = '';
    forward(req, res, '/api/assets', { rawBody: true });
  });
  /* 探测路由必须排在通配之前：Express 按注册顺序匹配，否则会被下面那条
     /notebox/* 吞掉、当成 notebox 的 API 转发出去。 */
  api.get('/notebox/probe', (req, res) => {
    if (!installed()) return res.json({ ok: false, reason: 'not-installed' });
    const base = target();
    const host = /^https?:\/\/([^:/]+)(?::(\d+))?/.exec(base);
    /* 未登录时 notebox 回 401 —— 这恰好证明服务活着，所以只看 <500 */
    const r = http.request({ host: host[1], port: host[2] || 80, method: 'GET', path: '/api/bootstrap', timeout: 3000 },
      (u) => { u.resume(); res.json({ ok: u.statusCode < 500, status: u.statusCode }); });
    r.on('error', () => res.json({ ok: false, reason: 'down' }));
    r.on('timeout', () => { r.destroy(); res.json({ ok: false, reason: 'timeout' }); });
    r.end();
  });

  /* 笔记正文里的图片是 notebox 自己存的 /uploads/<名>（不在 /api 下），
     面板 origin 够不着，所以单独代理一条；名字与后缀由 notebox 自己白名单校验。 */
  api.get('/notebox/uploads/*', (req, res) => {
    req._nbQuery = '';
    forward(req, res, '/uploads/' + String(req.params[0] || ''), { accept: '*/*', cond: true });
  });

  api.all('/notebox/*', handler);
}

module.exports = { mount, target, installed };
