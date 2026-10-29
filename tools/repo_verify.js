#!/usr/bin/env node
/*
 * Repo self-consistency gate for Server-Cloud-Monitor.
 * Answers "can a fresh box boot the panel from this checkout?":
 *   1. every shipped .js parses
 *   2. every require() resolves (relative -> file exists, bare -> in vendor tarball)
 *   3. every nav/page id in app.js has a lazy page file on disk
 *   4. every ops module the panel mounts is present
 * Usage: node tools/repo_verify.js [repoRoot]
 */
const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(process.argv[2] || '.');
const APP = path.join(ROOT, 'app');
const failures = [];
const notes = [];
let checked = 0;

function walk(dir, exts, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === '.git' || e.name === '__pycache__') continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, exts, out);
    else if (exts.some((x) => e.name.endsWith(x))) out.push(p);
  }
  return out;
}

/* ---------- 1. syntax ---------- */
const jsFiles = walk(APP, ['.js'], []);
for (const f of jsFiles) {
  checked++;
  try {
    cp.execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
  } catch (e) {
    failures.push('SYNTAX ' + path.relative(ROOT, f) + ': ' + String(e.stderr || e.message).split('\n')[0]);
  }
}
notes.push('js files syntax-checked: ' + jsFiles.length);

/* ---------- 2. requires ---------- */
const BUILTIN = new Set(['fs', 'path', 'os', 'http', 'https', 'url', 'zlib', 'crypto', 'child_process',
  'net', 'tls', 'dns', 'stream', 'util', 'events', 'buffer', 'querystring', 'readline', 'assert',
  'string_decoder', 'tty', 'dgram', 'cluster', 'worker_threads', 'perf_hooks', 'v8', 'constants', 'vm', 'module', 'timers',
  'node:sqlite', 'node:fs', 'node:path', 'node:crypto', 'node:http', 'node:https', 'node:zlib',
  'node:os', 'node:net', 'node:child_process', 'node:util', 'node:stream', 'node:events']);
const vendorList = (() => {
  const tgz = path.join(ROOT, 'vendor', 'node_modules.tar.gz');
  if (!fs.existsSync(tgz)) { notes.push('vendor/node_modules.tar.gz MISSING -> bare requires unchecked'); return null; }
  try {
    // Git Bash tar cannot resolve a drive-letter path; run it from ROOT with a relative arg
    return cp.execFileSync('tar', ['-tzf', 'vendor/node_modules.tar.gz'],
      { encoding: 'utf8', maxBuffer: 64 << 20, cwd: ROOT });
  } catch (e) {
    failures.push('VENDOR cannot list vendor/node_modules.tar.gz: ' + e.message);
    return null;
  }
})();
const vendorPkgs = new Set();
if (vendorList) {
  for (const line of vendorList.split('\n')) {
    const m = /^(?:\.\/)?node_modules\/((?:@[^/]+\/)?[^/]+)/.exec(line);
    if (m) vendorPkgs.add(m[1]);
  }
  notes.push('vendor packages: ' + vendorPkgs.size);
}
const requireRe = /require\(\s*['"]([^'"]+)['"]\s*\)/g;
/** true when the byte offset sits on a comment line (doc blocks quote requires) */
function isCommentLine(src, index) {
  const lineStart = src.lastIndexOf('\n', index) + 1;
  const t = src.slice(lineStart, index).trimStart();
  return t.startsWith('*') || t.startsWith('//') || t.startsWith('/*');
}
for (const f of jsFiles) {
  const src = fs.readFileSync(f, 'utf8');
  let m;
  while ((m = requireRe.exec(src))) {
    if (isCommentLine(src, m.index)) continue;
    const spec = m[1];
    if (BUILTIN.has(spec) || spec.startsWith('node:')) continue;
    if (spec.startsWith('.')) {
      const base = path.resolve(path.dirname(f), spec);
      const cands = [base + '.js', base + '.json', path.join(base, 'index.js'), base];
      if (!cands.some((c) => { try { return fs.statSync(c).isFile(); } catch (_) { return false; } })) {
        failures.push('REQUIRE ' + path.relative(ROOT, f) + ' -> ' + spec + ' not found');
      }
    } else if (vendorPkgs && !vendorPkgs.has(spec.split('/').slice(0, spec.startsWith('@') ? 2 : 1).join('/'))) {
      failures.push('REQUIRE ' + path.relative(ROOT, f) + ' -> bare "' + spec + '" not in vendor tarball');
    }
  }
}

/* ---------- 3. nav ids vs page files ---------- */
const appjs = path.join(APP, 'public', 'js', 'app.js');
if (fs.existsSync(appjs)) {
  const src = fs.readFileSync(appjs, 'utf8');
  const ids = [...src.matchAll(/\{\s*id:\s*'([a-z0-9-]+)'/g)].map((x) => x[1]);
  const lazy = {};
  const lazyBlock = /CM\.lazyPages\s*=\s*\{([\s\S]*?)\n\s*\};/.exec(src);
  if (lazyBlock) {
    for (const mm of lazyBlock[1].matchAll(/([A-Za-z0-9_-]+)\s*:\s*'([^']+)'/g)) lazy[mm[1]] = mm[2];
  }
  for (const id of ids) {
    const file = lazy[id];
    if (!file) { failures.push('NAV id "' + id + '" has no lazyPages entry'); continue; }
    if (!fs.existsSync(path.join(APP, 'public', file))) failures.push('PAGE ' + file + ' (nav "' + id + '") missing');
  }
  for (const [id, file] of Object.entries(lazy)) {
    if (!ids.includes(id)) notes.push('lazyPages "' + id + '" not referenced by NAV (may be routed elsewhere)');
    if (!fs.existsSync(path.join(APP, 'public', file))) failures.push('LAZY ' + file + ' missing');
  }
  notes.push('nav ids: ' + ids.length + ', lazy pages: ' + Object.keys(lazy).length);
} else {
  failures.push('public/js/app.js missing');
}

/* ---------- 4. ops modules mounted ---------- */
const opsIdx = path.join(APP, 'server', 'ops', 'index.js');
if (fs.existsSync(opsIdx)) {
  const src = fs.readFileSync(opsIdx, 'utf8');
  for (const mm of src.matchAll(/require\('\.\/([a-z-]+)'\)/g)) {
    if (isCommentLine(src, mm.index)) continue;
    const p = path.join(APP, 'server', 'ops', mm[1]);
    if (!(fs.existsSync(p + '.js') || fs.existsSync(path.join(p, 'index.js')))) {
      failures.push('OPS module ./' + mm[1] + ' required by ops/index.js but absent');
    }
  }
  for (const mm of src.matchAll(/([a-z]+)\.register\(api/g)) {
    if (isCommentLine(src, mm.index)) continue;
    if (!new RegExp("require\\('\\./" + mm[1] + "'\\)").test(src) && !new RegExp("require\\('\\./" + mm[1] + "/index'\\)").test(src)) {
      failures.push('OPS ' + mm[1] + '.register(api) without a require');
    }
  }
  notes.push('ops modules registered: ' + (src.match(/\.register\(api/g) || []).length);
}
const phpDir = path.join(APP, 'server', 'ops', 'php');
notes.push('php module files: ' + (fs.existsSync(phpDir) ? fs.readdirSync(phpDir).length : 'ABSENT'));
notes.push('agent.py present: ' + fs.existsSync(path.join(APP, 'agent.py')));
notes.push('devmetrics.js present: ' + fs.existsSync(path.join(APP, 'server', 'devmetrics.js')));

/* ---------- report ---------- */
for (const n of notes) console.log('  . ' + n);
if (failures.length) {
  console.log('\nFAIL (' + failures.length + ')');
  for (const f of failures) console.log('  x ' + f);
  process.exit(1);
}
console.log('\nOK - ' + checked + ' js files, requires resolved, nav wired, ops mounted');
