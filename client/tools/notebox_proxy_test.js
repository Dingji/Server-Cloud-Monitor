'use strict';
/* notebox 代理的本地集成测试：起一个"假 notebox"回显它收到的请求，
   再用真实的 Express 路由跑 panel-server/notebox.js。
   线上部署前先在这里把路径拼接、GET 带体、cookie 改写、raw 上传四类问题过一遍。
     node .scratch/notebox_proxy_test.js */
const http = require('http');
const path = require('path');
const express = require(path.resolve('deploy/cloud-monitor-panel/app/node_modules/express'));

const NB_PORT = 14345;
const PANEL_PORT = 14346;
process.env.CM_NOTEBOX_URL = 'http://127.0.0.1:' + NB_PORT;

const seen = [];

const fake = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    seen.push({
      method: req.method, url: req.url,
      cookie: req.headers.cookie || '', ctype: req.headers['content-type'] || '',
      inm: req.headers['if-none-match'] || '',
      bytes: body.length, head: body.slice(0, 60).toString('utf8'),
    });
    if (req.url.indexOf('/login') >= 0) {
      res.writeHead(200, {
        'Content-Type': 'application/json',
        'Set-Cookie': ['session=abc123; Path=/; HttpOnly; Secure; SameSite=Lax', 'other=1; Path=/'],
      });
      res.end(JSON.stringify({ ok: true, user: 'me@x' }));
      return;
    }
    if (req.url === '/api/missing-route') { res.writeHead(404, { 'Content-Type': 'application/json' }); res.end('{"error":"接口不存在"}'); return; }
    /* 静态附件：二进制 + ETag，命中条件请求回 304 无体 */
    if (req.url.indexOf('/uploads/') === 0) {
      const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 7, 6, 5, 4]);
      if (req.headers['if-none-match'] === '"v1"') {
        res.writeHead(304, { ETag: '"v1"' });
        res.end();
        return;
      }
      res.writeHead(200, { 'Content-Type': 'image/png', ETag: '"v1"', 'Cache-Control': 'public, max-age=604800', 'X-Content-Type-Options': 'nosniff' });
      res.end(PNG);
      return;
    }
    if (req.url.indexOf('/api/share/') === 0) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ title: '分享的笔记', content: '# hi', tags: 'a', shared_at: '2026-10-31 08:00:00' }));
      return;
    }
    res.writeHead(401, { 'Content-Type': 'application/json' });
    res.end('{"error":"未登录或会话已过期"}');
  });
});

const notebox = require('../panel-server/notebox');

function request(method, p, body, headers) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? null : (Buffer.isBuffer(body) ? body : JSON.stringify(body));
    const req = http.request({
      host: '127.0.0.1', port: PANEL_PORT, method, path: p,
      headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {},
        data ? { 'Content-Length': data.length } : {}),
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({
        status: res.statusCode, body: Buffer.concat(chunks).toString('utf8'), buf: Buffer.concat(chunks),
        setCookie: res.headers['set-cookie'] || [], ctype: res.headers['content-type'] || '',
        etag: res.headers['etag'] || '', nosniff: res.headers['x-content-type-options'] || '',
        cache: res.headers['cache-control'] || '',
      }));
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

let fails = 0;
function check(name, cond, got) {
  console.log((cond ? 'ok   ' : 'FAIL ') + name + (cond ? '' : '  -> ' + got));
  if (!cond) fails++;
}

(async () => {
  await new Promise((r) => fake.listen(NB_PORT, '127.0.0.1', r));
  const app = express();
  app.use(express.json());
  const api = express.Router();
  notebox.mount(api, express);
  app.use('/panel/api', api);
  await new Promise((r) => app.listen(PANEL_PORT, '127.0.0.1', r));

  /* 1. GET 的转发路径与"不带体" */
  seen.length = 0;
  let r = await request('GET', '/panel/api/notebox/bootstrap');
  check('GET /notebox/bootstrap -> 假 notebox 收到 /api/bootstrap', seen[0] && seen[0].url === '/api/bootstrap', JSON.stringify(seen[0]));
  check('GET 不挂 JSON 体', seen[0] && seen[0].method === 'GET' && seen[0].bytes === 0, JSON.stringify(seen[0]));
  check('401 原样透传', r.status === 401 && /未登录/.test(r.body), r.status + ' ' + r.body);

  /* 2. 深层路径 + query */
  seen.length = 0;
  await request('GET', '/panel/api/notebox/notes/12');
  check('GET /notebox/notes/12 -> /api/notes/12', seen[0] && seen[0].url === '/api/notes/12', JSON.stringify(seen[0]));
  seen.length = 0;
  await request('GET', '/panel/api/notebox/notes?q=%E5%B7%A5%E4%BD%9C&sort=title');
  check('query 原样带上', seen[0] && seen[0].url.indexOf('?q=') > 0 && /sort=title/.test(seen[0].url), JSON.stringify(seen[0]));

  /* 3. DELETE 不带体 */
  seen.length = 0;
  await request('DELETE', '/panel/api/notebox/todos/7');
  check('DELETE -> /api/todos/7 且无体', seen[0] && seen[0].method === 'DELETE' && seen[0].url === '/api/todos/7' && seen[0].bytes === 0, JSON.stringify(seen[0]));

  /* 4. POST 带体 + cookie 透传与改写 */
  seen.length = 0;
  r = await request('POST', '/panel/api/notebox/login', { email: 'a@b.c', password: 'x' }, { Cookie: 'cm=keepme' });
  check('POST 体到达 notebox', seen[0] && /"email":"a@b\.c"/.test(seen[0].head), JSON.stringify(seen[0]));
  check('浏览器 cookie 透传', seen[0] && seen[0].cookie === 'cm=keepme', JSON.stringify(seen[0] && seen[0].cookie));
  const rewritten = (r.setCookie || []).join(' | ');
  check('Set-Cookie 路径收到 /panel/api/notebox', /Path=\/panel\/api\/notebox/.test(rewritten) && !/Path=\/;/.test(rewritten), rewritten);
  check('Secure 抹掉、SameSite 收窄', !/Secure/.test(rewritten) && /SameSite=Lax/.test(rewritten), rewritten);

  /* 5. 未知路径 -> notebox 的 404，而不是面板把它变 502 */
  r = await request('GET', '/panel/api/notebox/missing-route');
  check('notebox 404 原样回', r.status === 404 && /接口不存在/.test(r.body), r.status + ' ' + r.body);

  /* 6. raw 上传：multipart 字节不动 */
  const boundary = '----qoder';
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);
  const raw = Buffer.concat([
    Buffer.from('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="a.png"\r\n'
      + 'Content-Type: image/png\r\n\r\n', 'utf8'),
    png,
    Buffer.from('\r\n--' + boundary + '--\r\n', 'utf8'),
  ]);
  seen.length = 0;
  await request('POST', '/panel/api/notebox/assets', raw, {
    'Content-Type': 'multipart/form-data; boundary=' + boundary,
  });
  check('assets 走 /api/assets', seen[0] && seen[0].url === '/api/assets', JSON.stringify(seen[0]));
  check('multipart 字节数不丢', seen[0] && seen[0].bytes === raw.length, (seen[0] || {}).bytes + ' vs ' + raw.length);
  check('content-type 保留', seen[0] && /multipart\/form-data/.test(seen[0].ctype), seen[0] && seen[0].ctype);

  /* 7. 静态附件：走 /uploads/ 前缀（notebox 的那个路由不在 /api 下），二进制不能变形 */
  seen.length = 0;
  const img = await request('GET', '/panel/api/notebox/uploads/a.png');
  check('uploads 路径不被加 /api 前缀', seen[0] && seen[0].url === '/uploads/a.png', JSON.stringify(seen[0]));
  check('uploads 回 200 且 content-type 原样', img.status === 200 && img.ctype === 'image/png', img.status + ' ' + img.ctype);
  check('uploads 字节不损', img.buf.length === 12 && img.buf[0] === 0x89 && img.buf[1] === 0x50, img.buf.length);
  check('uploads 的 ETag/nosniff/缓存头带回来', img.etag === '"v1"' && img.nosniff === 'nosniff' && /max-age=604800/.test(img.cache),
    JSON.stringify([img.etag, img.nosniff, img.cache]));

  /* 8. 条件请求：If-None-Match 要带过去，304 不能变成挂死的空响应 */
  seen.length = 0;
  const not304 = await request('GET', '/panel/api/notebox/uploads/a.png', undefined, { 'If-None-Match': '"v1"' });
  check('If-None-Match 透传到 notebox', seen[0] && seen[0].inm === '"v1"', JSON.stringify(seen[0] && seen[0].inm));
  check('304 原样回且无体', not304.status === 304 && not304.buf.length === 0, not304.status + ' len=' + not304.buf.length);

  /* 9. 只读分享 API（无 notebox 会话也能读，但仍在面板鉴权之后） */
  seen.length = 0;
  const sh = await request('GET', '/panel/api/notebox/share/AB12cd34');
  check('share/<code> -> /api/share/<code>', seen[0] && seen[0].url === '/api/share/AB12cd34', JSON.stringify(seen[0]));
  check('share 响应可读', sh.status === 200 && /分享的笔记/.test(sh.body), sh.status + ' ' + sh.body.slice(0, 60));

  /* 10. probe 未被通配吞掉（没有转发） */
  seen.length = 0;
  r = await request('GET', '/panel/api/notebox/probe');
  check('probe 未被通配吞掉（没有转发）', r.status === 200 && seen.length === 0 && /not-installed|"ok"/.test(r.body),
    r.status + ' seen=' + JSON.stringify(seen) + ' body=' + r.body);

  fake.close();
  console.log(fails ? '\n失败 ' + fails + ' 项' : '\n全部通过');
  process.exit(fails ? 1 : 0);
})().catch((e) => { console.log('HARNESS ERROR', e.stack); process.exit(2); });
