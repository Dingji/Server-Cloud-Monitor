'use strict';
/*
 * Offline generator check: prints the nginx server block and PHP-FPM pool that
 * the panel would write for a few representative sites, and asserts the static
 * invariants (balanced braces, no default_server, socket/pool name agreement).
 *
 *   node tools/php_gen_test.js            # assertions only
 *   node tools/php_gen_test.js --show     # also dump the generated text
 */
process.env.CM_OPS_DIR = process.env.CM_OPS_DIR || (require('os').tmpdir() + '/panel-php-gen-test');
delete process.env.CM_DATA_DIR;

const path = require('path');
const OPS = path.resolve(__dirname, '..', 'ops-server');
const C = require(path.join(OPS, 'php', 'common'));
const site = require(path.join(OPS, 'php', 'site'));
const P = require(path.join(OPS, 'php', 'presets'));

const SHOW = process.argv.includes('--show');
let fails = 0;
function check(name, cond, detail) {
  if (cond) console.log('  ok   ' + name);
  else { fails++; console.log('  FAIL ' + name + (detail ? '\n       ' + detail : '')); }
}
function balanced(text) {
  let depth = 0, inStr = false, esc = false;
  for (const ch of text) {
    if (esc) { esc = false; continue; }
    if (ch === '\\') { esc = true; continue; }
    if (ch === "'") inStr = !inStr;
    if (inStr) continue;
    if (ch === '{') depth++;
    if (ch === '}') depth--;
    if (depth < 0) return 'closed too early';
  }
  return depth === 0 ? true : 'depth=' + depth;
}

function locationDupes(text) {
  /* locations only collide inside the same server{} — the HTTPS server and the
     port-80 redirect server legitimately repeat /.well-known and /. */
  const blocks = text.split(/\nserver \{/).slice(1);
  const dupes = [];
  for (const b of blocks) {
    const seen = new Map();
    const re = /^\s*location\s+(.+?)\s*\{.*$/gm;
    let m;
    while ((m = re.exec(b))) {
      const key = m[1].replace(/\s+/g, ' ').trim();
      if (!key || key.startsWith('#')) continue;
      seen.set(key, (seen.get(key) || 0) + 1);
    }
    for (const [k, n] of seen) if (n > 1) dupes.push(k + ' x' + n);
  }
  return dupes;
}

function serverNameCollisions(text) {
  /* two server blocks claiming the same name on the same port make nginx warn and
     silently ignore the second one */
  const blocks = text.split(/\nserver \{/).slice(1);
  const claims = [];
  for (const b of blocks) {
    const ports = [...new Set([...b.matchAll(/listen\s+(?:\[[^\]]+\]:)?(\d+)/g)].map((m) => m[1]))];
    const sn = (/server_name\s+(.+);/.exec(b) || [, ''])[1].trim();
    for (const p of ports) claims.push(sn + '@' + p);
  }
  const seen = new Set(), dup = new Set();
  for (const c of claims) { if (seen.has(c)) dup.add(c); seen.add(c); }
  return [...dup];
}

const CASES = [
  {
    name: 'WordPress 域名站点 + SSL',
    body: { slug: 'myshop', preset: 'wordpress', domains: 'shop.example.com www.shop.example.com', php: '8.5', ports: [80, 443], ssl: true },
    expect: (out) => [
      ['包含 uploads 拒绝执行', /location ~\* \^\/wp-content\/uploads\/\.\*\\./.test(out)],
      ['包含 ssl_certificate', /ssl_certificate\s+\/etc\/letsencrypt\/live\/shop\.example\.com\/fullchain\.pem/.test(out)],
      ['包含 80→443 跳转块', /return 301 https:\/\/\$host\$request_uri/.test(out)],
      ['包含 fastcgi 池套接字', /fastcgi_pass unix:\/run\/php\/panel-myshop\.sock/.test(out)],
    ],
  },
  {
    name: 'Laravel 域名站点（public 文档根）',
    body: { slug: 'api-lara', preset: 'laravel', domains: 'lara.example.com', php: '8.5' },
    expect: (out) => [
      ['root 指向 htdocs 软链', /root \/srv\/php\/api-lara\/htdocs;/.test(out)],
      ['query_string 传递', /try_files \$uri \$uri\/ \/index\.php\?\$query_string/.test(out)],
      ['storage 禁止执行', /location ~\* \^\/storage\/\.\*\\./.test(out)],
      ['池名与站点一致', /^\[panel-api-lara\]/m.test(site.poolConf({ slug: 'api-lara', preset: 'laravel', php: '8.5' }, {}))],
    ],
  },
  {
    name: '纯 IP + 自定义端口 8088',
    body: { slug: 'demo8088', preset: 'generic', domains: '', ports: [8088], php: '8.5' },
    expect: (out) => [
      ['监听 8088', /listen 8088;/.test(out)],
      ['server_name 兜底为 _', /server_name _;/.test(out)],
    ],
  },
  {
    name: '静态站（不解析 PHP）',
    body: { slug: 'pure-static', preset: 'static', domains: 'st.example.com', php: '8.5', type: 'static' },
    expect: (out) => [
      ['不含 fastcgi_pass', !/fastcgi_pass/.test(out)],
      ['SPA 回落 index.html', /try_files \$uri \$uri\/ \/index\.html/.test(out)],
    ],
  },
  {
    name: 'Hyperf 反向代理预设',
    body: { slug: 'swoole-app', preset: 'hyperf', domains: 'hs.example.com', type: 'proxy', proxyPort: 9501, php: '8.5' },
    expect: (out) => [
      ['proxy_pass 到 9501', /proxy_pass http:\/\/127\.0\.0\.1:9501;/.test(out)],
      ['WebSocket Upgrade 头', /proxy_set_header Upgrade \$http_upgrade/.test(out)],
    ],
  },
];

console.log('=== 生成器断言 ===');
for (const c of CASES) {
  const rec = C.normalize(c.body);
  if (!rec.domains.length && rec.preset !== 'generic') rec.domains = C.domains(c.body.domains);
  const out = site.nginxConf(Object.assign({}, rec, { enabled: true }));
  console.log('\n[' + c.name + '] slug=' + rec.slug);
  check('nginx 花括号平衡', balanced(out.replace(/#.*$/gm, '')) === true, balanced(out));
  check('未声明 default_server', !/default_server/.test(out));
  check('未落在 relay 站点上', !/opencode-relay/.test(out));
  check('包含面板标识头', /Managed by Cloud Monitor panel/.test(out));
  const dupes = locationDupes(out);
  check('无重复 location（nginx 会直接拒绝）', dupes.length === 0, dupes.join(' | '));
  const collide = serverNameCollisions(out);
  check('无同端口重名 server', collide.length === 0, collide.join(' | '));
  if (rec.ssl) check('HTTPS 时 80 只在跳转 server 里', (out.match(/listen 80;/g) || []).length === 1);
  const denyIdx = out.search(/^\s*location ~\* \^\//m);
  const phpIdx = out.search(/^\s*location ~ \\\.php/m);
  if (denyIdx >= 0 && phpIdx >= 0) {
    check('拒绝执行规则排在 PHP 处理器之前', denyIdx < phpIdx, 'deny@' + denyIdx + ' php@' + phpIdx);
  }
  for (const [label, pass] of (c.expect ? c.expect(out) : [])) check(label, pass);
  if (SHOW) {
    console.log('---- nginx ----\n' + out);
    console.log('---- pool ----\n' + site.poolConf(rec, {}));
  }
}

console.log('\n=== 池配置断言 ===');
const poolRec = C.normalize({ slug: 'pooltest', preset: 'wordpress', domains: 'p.example.com', php: '8.5',
  openBasedir: true, phpOverrides: { memory_limit: '512M' }, pool: { pm: 'dynamic', 'pm.max_children': 20 } });
const pool = site.poolConf(poolRec, {});
check('池花括号平衡', balanced(pool) === true, balanced(pool));
/* php-fpm's pool parser only knows `;` comments and rejects unknown directives. */
const hashLines = pool.split('\n').filter((l) => /^\s*#/.test(l));
check('池文件不含 # 注释（php-fpm 会当指令解析并报错）', hashLines.length === 0, hashLines.join(' | ').slice(0, 160));
const KNOWN_POOL = ['user', 'group', 'listen', 'listen.owner', 'listen.group', 'listen.mode', 'pm',
  'pm.max_children', 'pm.start_servers', 'pm.min_spare_servers', 'pm.max_spare_servers',
  'pm.process_idle_timeout', 'pm.max_requests', 'pm.status_path', 'ping.path', 'catch_workers_output',
  'decorate_workers_output', 'clear_env', 'slowlog', 'request_slowlog_timeout', 'request_terminate_timeout'];
const poolKeys = pool.split('\n').filter((l) => /^[a-z_]+(\.[a-z_]+)?\s*=/.test(l)).map((l) => l.split('=')[0].trim());
const unknownPool = poolKeys.filter((k) => !KNOWN_POOL.includes(k));
check('池指令全部是 php-fpm 已知参数', unknownPool.length === 0, unknownPool.join(', '));
check('独立套接字', /listen = \/run\/php\/panel-pooltest\.sock/.test(pool));
check('站点用户', /user = php-pooltest/.test(pool));
check('open_basedir 限定在站点目录', /open_basedir\] = \/srv\/php\/pooltest:/.test(pool));
check('phpOverrides 生效', /php_value\[memory_limit\] = 512M/.test(pool));
check('dynamic 池自动补齐 spare', /pm.min_spare_servers = \d+/.test(pool));
check('禁用了命令执行函数', /disable_functions\] = .*(exec|system)/.test(pool));

console.log('\n=== 预设完整性 ===');
for (const k of Object.keys(P.PRESETS)) {
  const pre = P.PRESETS[k];
  check('预设 ' + k + ' 有 label', !!pre.label);
  if (pre.locationBlock) check('预设 ' + k + ' location 块花括号平衡', balanced(pre.locationBlock) === true, balanced(pre.locationBlock));
}

console.log('\n=== 输入校验 ===');
const badInputs = [
  ['slug 空白', () => C.normalize({ slug: '' })],
  ['slug 保留字', () => C.normalize({ slug: 'panel' })],
  ['端口冲突 3306', () => C.normalize({ slug: 'ok1', port: 3306 })],
  ['端口冲突 9000', () => C.normalize({ slug: 'ok2', port: 9000 })],
  ['特权端口 81', () => C.normalize({ slug: 'ok3', port: 81 })],
  ['relayhub 端口池', () => C.normalize({ slug: 'ok4', port: 36001 })],
  ['域名注入', () => C.domains('evil;rm -rf /')],
  ['超长域名', () => C.domains('a'.repeat(300) + '.com')],
];
for (const [label, fn] of badInputs) {
  let threw = false;
  try { fn(); } catch (_) { threw = true; }
  check('拒绝：' + label, threw);
}

console.log('\n=== 脱敏 ===');
const red = C.maskEnv({ DB_PASSWORD: 'supersecret1', APP_KEY: 'abcdef', APP_URL: 'http://x' });
check('DB_PASSWORD 被遮蔽', red.DB_PASSWORD.indexOf('supersecret1') < 0);
check('APP_URL 保持明文', red.APP_URL === 'http://x');
const idx = require(path.join(OPS, 'php', 'index'));
const r2 = idx.redact({ slug: 'x', password: 'p@ss', privateKey: 'KEY MATERIAL', git: { url: 'u', token: 't' } });
check('审计脱敏 password', r2.password === '<4 chars>');
check('审计脱敏 privateKey', r2.privateKey === '<12 chars>');
check('审计脱敏嵌套 git', Array.isArray(r2.git) || typeof r2.git === 'object');

console.log(fails ? '\n失败 ' + fails + ' 项' : '\n全部通过');
process.exit(fails ? 1 : 0);
