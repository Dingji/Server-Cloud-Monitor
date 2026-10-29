'use strict';
/*
 * File manager: browse, edit, create, rename, move, copy, delete-to-trash,
 * chmod/chown, upload/download, tar/zip, search and per-directory usage.
 *
 * Path handling is the risky part, so every path goes through abs(): control
 * characters rejected, `..` collapsed, result must stay inside an allowed root,
 * and names may never contain a separator. Directory listings use GNU find when
 * available (owner names, symbolic mode) and fall back to pure fs so the module
 * still works where those binaries do not exist.
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const { exec } = require('child_process');
const S = require('./store');
const shq = S.shq;

const agentMod = S.soft('../agent');
const express = S.soft('express');

const MAX_TEXT = 2 * 1024 * 1024;       /* edit / preview size guard */
/* 上传上限（MB）。流式落盘后内存占用与文件大小无关，唯一约束是磁盘；
   可用环境变量 CM_MAX_UPLOAD_MB 调整，改完 systemctl restart cloud-monitor。 */
const MAX_UPLOAD_MB = Math.max(1, Number(process.env.CM_MAX_UPLOAD_MB || 1024) || 1024);
const PREVIEW_BYTES = 64 * 1024;
const LIST_CAP = 3000;
const TRASH_DAYS = 30;

const BOOKMARKS = [
  { label: '网站根目录', path: '/var/www' },
  { label: '应用目录', path: '/opt' },
  { label: '配置', path: '/etc' },
  { label: '日志', path: '/var/log' },
  { label: '数据', path: '/var/lib' },
  { label: '备份', path: '/var/backups' },
  { label: '家目录', path: '/root' },
  { label: '临时', path: '/tmp' },
];

const trashRoot = () => path.join(S.root(), 'files_trash');
const trashMeta = () => path.join(trashRoot(), 'index.json');

function roots() {
  if (process.env.CM_FILES_ROOTS) return process.env.CM_FILES_ROOTS.split(':').filter(Boolean).map((r) => path.resolve(r));
  return [path.resolve(process.platform === 'win32' ? (process.env.CM_FILES_HOME || 'C:\\') : (process.env.CM_FILES_HOME || '/'))];
}

function bad(msg, code) { const e = new Error(msg); e.status = code || 400; throw e; }

/** Containment by relative path: correct for both `C:\\` drive roots and POSIX `/`. */
function within(rootAbs, target) {
  const rel = path.relative(rootAbs, target);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

function abs(p, label) {
  const raw = String(p == null ? '' : p);
  if (/[\u0000-\u001f]/.test(raw)) bad((label || '路径') + '包含非法控制字符');
  /* POSIX panels send /var/www/...; a Windows host (tests, dev box) sends C:/... —
   * mixing the two through path.posix.join would corrupt the drive form. */
  const windowsNative = /^[a-zA-Z]:[\\/]/.test(raw) || raw.startsWith('\\\\');
  const normalized = windowsNative ? raw : path.posix.normalize(raw.startsWith('/') ? raw : path.posix.join('/', raw));
  const resolved = path.resolve(normalized);
  if (!roots().some((r) => within(r, resolved))) bad((label || '路径') + '超出允许范围：' + resolved, 403);
  return resolved;
}

function safeName(name) {
  const n = String(name == null ? '' : name).trim();
  if (!n) bad('名称不能为空');
  if (n === '.' || n === '..') bad('非法名称：' + n);
  if (/[\\/:*?"<>|\u0000-\u001f]/.test(n) || n.includes('/')) bad('名称不能包含路径分隔符或特殊字符：' + n);
  if (n.length > 200) bad('名称过长（上限 200 字符）');
  return n;
}

function shell(cmd, timeoutMs) {
  return new Promise((resolve) => {
    if (agentMod && typeof agentMod.getAgent === 'function') {
      agentMod.getAgent().run(cmd, { timeout: timeoutMs || 20000, maxBuffer: 8 * 1024 * 1024 })
        .then((r) => resolve({ code: r.code === undefined ? 0 : r.code, stdout: String(r.stdout || ''), stderr: String(r.stderr || '') }))
        .catch((e) => resolve({ code: 1, stdout: '', stderr: e.message || String(e) }));
      return;
    }
    exec(cmd, { timeout: timeoutMs || 20000, maxBuffer: 8 * 1024 * 1024, windowsHide: true }, (err, stdout, stderr) => {
      resolve({ code: err ? (typeof err.code === 'number' ? err.code : 1) : 0, stdout: String(stdout || ''), stderr: String(stderr || (err && err.message) || '') });
    });
  });
}

const hasUnixTools = () => process.platform !== 'win32';

const TYPE_LABEL = { f: '文件', d: '目录', l: '符号链接', s: 'socket', p: '管道', c: '字符设备', b: '块设备' };

function fromStat(st, name, p, isLink) {
  return {
    name, path: p,
    isDir: st.isDirectory(), isFile: st.isFile(), isLink: !!isLink,
    type: st.isDirectory() ? 'd' : st.isSymbolicLink() ? 'l' : st.isFile() ? 'f' : 'o',
    typeLabel: st.isDirectory() ? '目录' : st.isSymbolicLink() ? '符号链接' : st.isFile() ? '文件' : '其它',
    size: st.size || 0, mode: (st.mode || 0).toString(8).slice(-4),
    symbolic: symbolicMode(st.mode || 0, st.isDirectory()),
    uid: st.uid == null ? '' : String(st.uid), gid: st.gid == null ? '' : String(st.gid),
    owner: '', group: '', mtimeMs: st.mtimeMs ? Math.round(st.mtimeMs) : 0,
  };
}

/** 0644 -> rw-r--r--（与 ls -l 一致），用于无 find 时的展示。 */
function symbolicMode(mode, isDir) {
  const t = (v, on, off) => (mode & v ? on : off);
  return (isDir ? 'd' : '-') + t(0o400, 'r', '-') + t(0o200, 'w', '-') + t(0o100, 'x', '-') +
    t(0o040, 'r', '-') + t(0o020, 'w', '-') + t(0o010, 'x', '-') + t(0o004, 'r', '-') + t(0o002, 'w', '-') + t(0o001, 'x', '-');
}

function parseFindLine(line) {
  const c = line.split('\t');
  if (c.length < 6) return null;
  const p = c[0], y = c[1], perms = c[2], own = c[3], size = Number(c[4]), mtime = c.slice(5).join(' ').trim();
  const name = path.basename(p) || (p === '/' ? '/' : '');
  const ms = Date.parse(mtime.replace(' ', 'T'));
  return {
    name, path: p,
    isDir: y === 'd', isFile: y === 'f', isLink: y === 'l',
    type: y, typeLabel: TYPE_LABEL[y] || y,
    size: Number.isFinite(size) ? size : 0,
    mode: '', symbolic: perms,
    owner: own.split(':')[0] || '', group: own.split(':')[1] || '',
    uid: '', gid: '',
    mtimeMs: Number.isFinite(ms) ? ms : 0,
  };
}

function scanWithFs(dir) {
  const out = [];
  const names = fs.readdirSync(dir);
  for (const name of names.slice(0, LIST_CAP)) {
    const p = path.join(dir, name);
    let st;
    try { st = fs.lstatSync(p); } catch (_) { continue; }
    const e = fromStat(st, name, p, st.isSymbolicLink());
    if (st.isSymbolicLink()) { try { e.linkTarget = fs.readlinkSync(p); } catch (_) {} }
    out.push(e);
  }
  out.sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name));
  return { entries: out, names: names.length, truncated: names.length > LIST_CAP };
}

async function listDir(dirRaw) {
  const dir = abs(dirRaw || '/');
  let st;
  try { st = fs.statSync(dir); } catch (e) { bad('目录不存在或不可读：' + dir, 404); }
  if (!st.isDirectory()) bad('不是目录：' + dir);

  let entries = [], self = null, usedShell = false;
  if (hasUnixTools()) {
    const r = await shell(`find ${shq(dir)} -maxdepth 1 -printf '%p\\t%y\\t%M\\t%u:%g\\t%s\\t%TY-%Tm-%Td %TH:%TM:%TS\\n' 2>/dev/null | head -n ${LIST_CAP + 1}`, 20000);
    const lines = r.stdout.split('\n').filter(Boolean);
    if (r.code === 0 && lines.length > 1) {
      for (const l of lines) {
        const e = parseFindLine(l);
        if (!e) continue;
        if (path.resolve(e.path) === path.resolve(dir)) { self = e; continue; }
        entries.push(e);
      }
      usedShell = true;
    }
  }
  if (!usedShell) {
    const scanned = scanWithFs(dir);
    entries = scanned.entries;
    self = fromStat(fs.lstatSync(dir), path.basename(dir) || dir, dir, false);
    self.entryCount = scanned.names;
    self.truncated = scanned.truncated;
  }
  entries.sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name));

  let df = null;
  if (hasUnixTools()) {
    const r = await shell(`df -Pk ${shq(dir)} 2>/dev/null | tail -1`, 8000);
    const c = r.stdout.trim().split(/\s+/);
    if (c.length >= 6) df = { totalKb: Number(c[1]), usedKb: Number(c[2]), availKb: Number(c[3]), pct: c[4], filesystem: c[0] };
  }
  const parts = path.resolve(dir).split(path.sep).filter(Boolean);
  return {
    ok: true,
    dir: path.resolve(dir),
    parent: parts.length ? path.sep + parts.slice(0, -1).join(path.sep) || path.sep : null,
    breadcrumb: parts.map((p, i) => ({ label: p, path: path.sep + parts.slice(0, i + 1).join(path.sep) })),
    self, entries, count: entries.length, usedShell, df,
    roots: roots(), bookmarks: BOOKMARKS.filter((b) => { try { return fs.statSync(b.path).isDirectory(); } catch (_) { return false; } }),
    os: `${os.platform()} ${os.release()}`,
  };
}

async function statOne(p) {
  const abs_ = abs(p);
  let st;
  try { st = fs.lstatSync(abs_); } catch (_) { bad('路径不存在：' + abs_, 404); }
  const e = fromStat(st, path.basename(abs_) || abs_, abs_, st.isSymbolicLink());
  if (st.isSymbolicLink()) { try { e.linkTarget = fs.readlinkSync(abs_); } catch (_) {} }
  if (e.isDir) {
    try { e.entryCount = fs.readdirSync(abs_).length; } catch (_) {}
    if (hasUnixTools()) {
      const r = await shell(`du -xsk ${shq(abs_)} 2>/dev/null | cut -f1`, 15000);
      const kb = Number(r.stdout.trim());
      if (Number.isFinite(kb)) e.sizeKb = kb;
    }
  }
  return { ok: true, entry: e };
}

async function tree(dirRaw, depth) {
  const dir = abs(dirRaw || '/');
  const d = Math.max(1, Math.min(4, Number(depth) || 2));
  if (hasUnixTools()) {
    const r = await shell(`find ${shq(dir)} -maxdepth ${d} -type d -printf '%p\\t%d\\n' 2>/dev/null | head -n 400`, 15000);
    const nodes = r.stdout.split('\n').filter(Boolean).map((l) => { const c = l.split('\t'); return { path: c[0], depth: Number(c[1]) }; });
    if (nodes.length) return { ok: true, nodes, usedShell: true };
  }
  const nodes = [];
  (function walk(p, level) {
    if (level > d || nodes.length > 400) return;
    nodes.push({ path: p, depth: level });
    let st;
    try { st = fs.readdirSync(p, { withFileTypes: true }); } catch (_) { return; }
    st.filter((x) => x.isDirectory()).slice(0, 60).forEach((x) => walk(path.join(p, x.name), level + 1));
  })(dir, 0);
  return { ok: true, nodes, usedShell: false };
}

async function preview(p) {
  const abs_ = abs(p);
  let st;
  try { st = fs.statSync(abs_); } catch (_) { bad('文件不存在：' + abs_, 404); }
  if (st.isDirectory()) bad('这是目录，请用浏览：' + abs_);
  if (st.size > MAX_TEXT) return { ok: true, binary: false, tooLarge: true, path: abs_, size: st.size, limit: MAX_TEXT };
  const fd = fs.openSync(abs_, 'r');
  const buf = Buffer.alloc(Math.min(PREVIEW_BYTES, st.size));
  let read = 0;
  try { read = fs.readSync(fd, buf, 0, buf.length, 0); } finally { fs.closeSync(fd); }
  const slice = buf.subarray(0, read);
  let binary = false;
  for (let i = 0; i < slice.length; i++) if (slice[i] === 0) { binary = true; break; }
  return {
    ok: true, path: abs_, size: st.size, mtimeMs: Math.round(st.mtimeMs), binary,
    truncated: st.size > read,
    text: binary ? '' : slice.toString('utf8'),
    ext: path.extname(abs_).slice(1, 12),
    lines: binary ? 0 : slice.toString('utf8').split('\n').length,
  };
}

function readBodyText(body) {
  if (typeof body === 'string') return body;
  if (body && typeof body.content === 'string') return body.content;
  bad('缺少文件内容');
}
function writeText(p, content) {
  const abs_ = abs(p);
  if (Buffer.byteLength(content, 'utf8') > MAX_TEXT) bad('内容过大（上限 2MB），请改用上传', 413);
  if (fs.existsSync(abs_) && fs.statSync(abs_).isDirectory()) bad('目标是目录：' + abs_);
  const dir = path.dirname(abs_);
  if (!fs.existsSync(dir)) bad('目录不存在：' + dir, 404);
  let before = null;
  try { before = fs.readFileSync(abs_); } catch (_) {}
  fs.writeFileSync(abs_, content, 'utf8');
  return { ok: true, path: abs_, bytes: Buffer.byteLength(content, 'utf8'), previousBytes: before ? before.length : 0 };
}

function resolveTarget(targetDir, name) {
  const dir = abs(targetDir, '目标目录');
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) bad('目标目录不存在：' + dir, 404);
  return path.join(dir, safeName(name));
}

function guardSource(p, action) {
  const abs_ = abs(p, '源路径');
  if (path.resolve(abs_) === path.parse(path.resolve(abs_)).root) bad(`不允许对${action}根目录`, 403);
  if (abs_.startsWith(trashRoot())) bad(`不允许${action}回收站内部路径`, 403);
  try { fs.lstatSync(abs_); } catch (_) { bad('路径不存在：' + abs_, 404); }
  return abs_;
}

async function eachPaths(body, fn) {
  const arr = Array.isArray(body.paths) ? body.paths : [body.path];
  const done = [];
  let failed = 0, lastError = '', lastStatus = 400;
  for (const p of arr) {
    if (p == null || p === '') continue;
    try { done.push(await fn(p)); } catch (e) { failed++; lastError = e.message; lastStatus = e.status || 400; }
  }
  if (!done.length && failed) bad(lastError || '没有可处理的条目', lastStatus);
  return { ok: true, done, failed, lastError };
}

/**
 * rename() is atomic and cheap, but fails with EXDEV when the trash and the
 * target live on different filesystems (/tmp is tmpfs on many clouds). Fall
 * back to copy-then-remove so "delete to trash" never becomes a hard error.
 */
function hardMove(src, dst) {
  try { fs.renameSync(src, dst); return 'renamed'; }
  catch (e) {
    if (e.code !== 'EXDEV') throw e;
    fs.cpSync(src, dst, { recursive: true });
    fs.rmSync(src, { recursive: true, force: true });
    return 'copied';
  }
}

function addToTrash(abs_) {
  fs.mkdirSync(trashRoot(), { recursive: true });
  const id = S.genId('t');
  const stored = path.join(trashRoot(), id + '__' + (path.basename(abs_) || 'item').replace(/[^\w.\-+@]/g, '_'));
  const how = hardMove(abs_, stored);
  const idx = readTrashIndex();
  idx.items.unshift({ id, original: abs_, stored, name: path.basename(abs_), size: sizeOf(stored), isDir: isDirSafe(stored), via: how, at: S.now() });
  idx.items = idx.items.slice(0, 2000);
  fs.writeFileSync(trashMeta(), JSON.stringify(idx, null, 2), 'utf8');
  return { id, stored };
}

function readTrashIndex() {
  try { return JSON.parse(fs.readFileSync(trashMeta(), 'utf8')); } catch (_) { return { items: [] }; }
}
const isDirSafe = (p) => { try { return fs.statSync(p).isDirectory(); } catch (_) { return false; } };
function sizeOf(p) {
  try {
    const st = fs.statSync(p);
    if (!st.isDirectory()) return st.size;
    let sum = st.size;
    for (const name of fs.readdirSync(p).slice(0, 2000)) {
      try { sum += fs.statSync(path.join(p, name)).size; } catch (_) {}
    }
    return sum;
  } catch (_) { return 0; }
}

function pruneTrash() {
  const idx = readTrashIndex();
  const cutoff = S.now() - TRASH_DAYS * 86400000;
  const keep = [];
  for (const it of idx.items) {
    if (it.at < cutoff) {
      try { fs.rmSync(it.stored, { recursive: true, force: true }); } catch (_) {}
    } else keep.push(it);
  }
  idx.items = keep;
  try { fs.writeFileSync(trashMeta(), JSON.stringify(idx, null, 2), 'utf8'); } catch (_) {}
  return keep.length;
}

/* ---------------- routes ---------------- */

function register(api, wrap, audited) {
  api.get('/ops/files/list', wrap(async (req, res) => res.json(await listDir(String(req.query.dir || '/')))));
  api.get('/ops/files/stat', wrap(async (req, res) => res.json(await statOne(String(req.query.path || '/')))));
  api.get('/ops/files/tree', wrap(async (req, res) => res.json(await tree(String(req.query.dir || '/'), req.query.depth))));
  api.get('/ops/files/preview', wrap(async (req, res) => res.json(await preview(String(req.query.path || '/')))));
  api.get('/ops/files/trash', wrap(async (req, res) => {
    const n = pruneTrash();
    res.json({ ok: true, days: TRASH_DAYS, items: readTrashIndex().items, count: n });
  }));
  api.get('/ops/files/download', wrap(async (req, res) => {
    const p = abs(String(req.query.path || ''));
    let st;
    try { st = fs.statSync(p); } catch (_) { bad('文件不存在：' + p, 404); }
    if (st.isDirectory()) bad('不能下载目录：' + p);
    res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Content-Length', String(st.size));
    res.setHeader('Content-Disposition', `attachment; filename*=UTF-8''${encodeURIComponent(path.basename(p))}`);
    fs.createReadStream(p).on('error', (e) => { try { res.status(500).json({ error: '读取失败：' + e.message }); } catch (_) {} }).pipe(res);
  }));
  api.get('/ops/files/search', wrap(async (req, res) => {
    const dir = abs(String(req.query.dir || '/'));
    const q = String(req.query.q || '').trim().slice(0, 60);
    const depth = Math.max(1, Math.min(8, Number(req.query.depth) || 4));
    const onlyDirs = req.query.type === 'dir';
    if (!q) bad('请输入搜索关键字');
    if (hasUnixTools()) {
      const cmd = `find ${shq(dir)} -maxdepth ${depth} ${onlyDirs ? '-type d' : ''} -iname ${shq('*' + q + '*')} -printf '%p\\t%y\\t%s\\t%TY-%Tm-%Td %TH:%TM\\n' 2>/dev/null | head -n 300`;
      const r = await shell(cmd, 25000);
      const items = r.stdout.split('\n').filter(Boolean).map((l) => {
        const c = l.split('\t');
        return { path: c[0], type: TYPE_LABEL[c[1]] || c[1], size: Number(c[2]) || 0, mtimeText: c.slice(3).join(' ') };
      });
      return res.json({ ok: true, q, dir, depth, items, usedShell: true, stderr: r.code ? r.stderr.slice(0, 200) : '' });
    }
    const hits = [];
    (function walk(p, level) {
      if (level > depth || hits.length >= 300) return;
      let names = [];
      try { names = fs.readdirSync(p); } catch (_) { return; }
      for (const n of names) {
        const c = path.join(p, n);
        if (n.toLowerCase().includes(q.toLowerCase())) {
          let st = null;
          try { st = fs.lstatSync(c); } catch (_) {}
          hits.push({ path: c, type: st && st.isDirectory() ? '目录' : '文件', size: st ? st.size : 0, mtimeText: st ? new Date(st.mtimeMs).toISOString().slice(0, 16).replace('T', ' ') : '' });
        }
        if (stIsDirSafe(c)) walk(c, level + 1);
      }
    })(dir, 0);
    res.json({ ok: true, q, dir, depth, items: hits, usedShell: false });
  }));
  api.get('/ops/files/usage', wrap(async (req, res) => {
    const dir = abs(String(req.query.dir || '/'));
    if (hasUnixTools()) {
      const r = await shell(`du -xk --max-depth=1 ${shq(dir)} 2>/dev/null | sort -rn | head -n 40`, 30000);
      const items = r.stdout.split('\n').filter(Boolean).map((l) => {
        const c = l.split('\t');
        return { path: c[1] || '', kb: Number(c[0]) || 0 };
      }).filter((x) => x.path);
      return res.json({ ok: true, dir, items, unit: 'KB', usedShell: items.length > 0 });
    }
    res.json({ ok: true, dir, items: [], unit: 'KB', usedShell: false, note: '该环境没有 du 命令' });
  }));

  api.post('/ops/files/mkdir', audited('ops.files.mkdir', async (req) => {
    const b = req.body || {};
    const p = resolveTarget(b.dir || b.path, b.name);
    if (fs.existsSync(p)) bad('同名条目已存在：' + p, 409);
    fs.mkdirSync(p, { recursive: false });
    return { ok: true, path: p };
  }));
  api.post('/ops/files/write', audited('ops.files.write', async (req) => {
    const b = req.body || {};
    return writeText(b.path, readBodyText(b));
  }));
  api.post('/ops/files/touch', audited('ops.files.touch', async (req) => {
    const b = req.body || {};
    if (b.dir) { const t = resolveTarget(b.dir, b.name); fs.writeFileSync(t, '', 'utf8'); return { ok: true, path: t }; }
    return eachPaths(b, (p) => {
      const a = guardSource(p, '操作');
      const now = new Date();
      fs.utimesSync(a, now, now);
      return { path: a };
    });
  }));
  api.post('/ops/files/rename', audited('ops.files.rename', async (req) => {
    const b = req.body || {};
    const from = guardSource(b.path, '重命名');
    const to = path.join(path.dirname(from), safeName(b.name));
    if (fs.existsSync(to)) bad('目标名称已存在：' + to, 409);
    fs.renameSync(from, to);
    return { ok: true, from, to };
  }));
  api.post('/ops/files/move', audited('ops.files.move', async (req) => {
    const b = req.body || {};
    const toDir = abs(b.to, '目标目录');
    if (!fs.existsSync(toDir) || !fs.statSync(toDir).isDirectory()) bad('目标目录不存在：' + toDir, 404);
    return eachPaths(b, (p) => {
      const a = guardSource(p, '移动');
      if (path.dirname(a) === path.resolve(toDir)) return { path: a, skipped: '已在目标目录' };
      const target = path.join(toDir, path.basename(a));
      if (fs.existsSync(target)) bad('目标已存在同名条目：' + target, 409);
      hardMove(a, target);
      return { from: a, to: target };
    });
  }));
  api.post('/ops/files/copy', audited('ops.files.copy', async (req) => {
    const b = req.body || {};
    return eachPaths(b, (p) => {
      const a = guardSource(p, '复制');
      const target = b.name ? resolveTarget(b.to || path.dirname(a), b.name) : path.join(abs(b.to || path.dirname(a), '目标目录'), path.basename(a));
      if (path.resolve(target) === path.resolve(a)) bad('复制目标与源相同', 409);
      if (fs.existsSync(target)) bad('目标已存在：' + target, 409);
      fs.cpSync(a, target, { recursive: true });
      return { from: a, to: target };
    });
  }));
  api.post('/ops/files/delete', audited('ops.files.delete', async (req) => {
    const b = req.body || {};
    const purge = b.purge === true;
    return eachPaths(b, (p) => {
      const a = guardSource(p, '删除');
      if (purge) { fs.rmSync(a, { recursive: true, force: true }); return { path: a, purged: true }; }
      const t = addToTrash(a);
      return { path: a, trashed: t.id };
    });
  }));
  api.post('/ops/files/restore', audited('ops.files.restore', async (req) => {
    const b = req.body || {};
    const idx = readTrashIndex();
    const ids = Array.isArray(b.ids) ? b.ids : [b.id];
    const done = [];
    for (const id of ids.filter(Boolean)) {
      const it = idx.items.find((x) => x.id === id);
      if (!it) continue;
      if (fs.existsSync(it.original)) bad('原位置已存在同名条目，先重命名或清理：' + it.original, 409);
      try { fs.mkdirSync(path.dirname(it.original), { recursive: true }); } catch (_) {}
      hardMove(it.stored, it.original);
      idx.items.splice(idx.items.indexOf(it), 1);
      done.push({ id, path: it.original });
    }
    fs.writeFileSync(trashMeta(), JSON.stringify(idx, null, 2), 'utf8');
    if (!done.length) bad('没有可恢复的条目（可能已被清理）', 404);
    return { ok: true, restored: done };
  }));
  api.post('/ops/files/trash/delete', audited('ops.files.trashDelete', async (req) => {
    const b = req.body || {};
    const idx = readTrashIndex();
    const ids = Array.isArray(b.ids) ? b.ids : [b.id];
    const done = [];
    for (const id of ids.filter(Boolean)) {
      const it = idx.items.find((x) => x.id === id);
      if (!it) continue;
      try { fs.rmSync(it.stored, { recursive: true, force: true }); } catch (_) {}
      idx.items.splice(idx.items.indexOf(it), 1);
      done.push(id);
    }
    fs.writeFileSync(trashMeta(), JSON.stringify(idx, null, 2), 'utf8');
    return { ok: true, purged: done };
  }));
  api.post('/ops/files/trash/empty', audited('ops.files.trashEmpty', async () => {
    const idx = readTrashIndex();
    let n = 0;
    for (const it of idx.items) { try { fs.rmSync(it.stored, { recursive: true, force: true }); n++; } catch (_) {} }
    fs.writeFileSync(trashMeta(), JSON.stringify({ items: [] }, null, 2), 'utf8');
    return { ok: true, purged: n };
  }));
  api.post('/ops/files/chmod', audited('ops.files.chmod', async (req) => {
    const b = req.body || {};
    const mode = String(b.mode || '').trim();
    if (!/^[0-7]{3,4}$/.test(mode)) bad('权限需为 3–4 位八进制，如 644 / 0755');
    if (hasUnixTools()) {
      return eachPaths(b, (p) => {
        const a = guardSource(p, '改权限');
        return shell(`chmod ${b.recursive ? '-R ' : ''}${shq(mode)} ${shq(a)} && ls -ld ${shq(a)} | awk '{print $1}'`, 10000)
          .then((r) => ({ path: a, mode, now: r.stdout.trim() || r.stderr.slice(0, 120) }));
      });
    }
    fs.chmodSync(guardSource(b.path, '改权限'), parseInt(mode, 8));
    return { ok: true, path: b.path, mode };
  }));
  api.post('/ops/files/chown', audited('ops.files.chown', async (req) => {
    const b = req.body || {};
    const owner = String(b.owner || '').trim();
    if (!/^[a-zA-Z0-9_.\-]+(:[a-zA-Z0-9_.\-]+)?$/.test(owner)) bad('属主格式应为 user 或 user:group');
    return eachPaths(b, (p) => {
      const a = guardSource(p, '改属主');
      return shell(`chown ${b.recursive ? '-R ' : ''}${shq(owner)} ${shq(a)} && ls -ld ${shq(a)} | awk '{print $3":"$4}'`, 10000)
        .then((r) => ({ path: a, owner, now: r.stdout.trim() || r.stderr.slice(0, 120) }));
    });
  }));
  api.post('/ops/files/archive', audited('ops.files.archive', async (req) => {
    const b = req.body || {};
    const toDir = abs(b.to || '/', '输出目录');
    const name = safeName(b.name || ('archive-' + S.dayKey() + (b.kind === 'zip' ? '.zip' : '.tar.gz')));
    const out = path.join(toDir, name);
    if (fs.existsSync(out)) bad('归档文件已存在：' + out, 409);
    const srcs = (Array.isArray(b.paths) ? b.paths : [b.path]).filter(Boolean).map((p) => abs(p, '源路径'));
    if (!srcs.length) bad('请选择要打包的条目');
    const list = srcs.map((p) => shq(p)).join(' ');
    if (b.kind === 'zip') {
      const r = await shell(`cd ${shq(path.dirname(srcs[0]))} && zip -qr ${shq(out)} ${srcs.map((p) => shq(path.basename(p))).join(' ')}`, 120000);
      if (r.code !== 0) bad('打包失败：' + (r.stderr || 'zip 不可用').slice(0, 200), 500);
    } else {
      const r = await shell(`tar -czf ${shq(out)} ${list}`, 120000);
      if (r.code !== 0) bad('打包失败：' + (r.stderr || 'tar 不可用').slice(0, 200), 500);
    }
    return { ok: true, path: out, size: sizeOf(out), sources: srcs.length };
  }));
  api.post('/ops/files/extract', audited('ops.files.extract', async (req) => {
    const b = req.body || {};
    const a = abs(b.path, '压缩包');
    const to = abs(b.to || path.dirname(a), '解压目标');
    if (!fs.existsSync(to) || !fs.statSync(to).isDirectory()) bad('目标目录不存在：' + to, 404);
    const lower = a.toLowerCase();
    const cmd = lower.endsWith('.zip') ? `unzip -o -q ${shq(a)} -d ${shq(to)}` : `tar -xzf ${shq(a)} -C ${shq(to)}`;
    const r = await shell(cmd, 180000);
    if (r.code !== 0) bad('解压失败：' + (r.stderr || '对应命令不可用').slice(0, 240), 500);
    return { ok: true, archive: a, to, output: r.stdout.slice(0, 500) };
  }));

  /* 流式上传：整个 body 不再进内存（旧实现 express.raw 会把文件整体缓冲，而面板进程
     堆上限只有 256MB）。边收边写同目录的 .part 临时文件，收完原子改名——失败或中断
     都不会留下半个文件，也不会覆盖同名文件。上限见 MAX_UPLOAD_MB。 */
  const mkErr = (msg, code) => { const e = new Error(msg); e.status = code || 400; return e; };
  api.post('/ops/files/upload', audited('ops.files.upload', (req) => new Promise((resolve, reject) => {
    let target = null, tmp = null, out = null, bytes = 0, done = false;
    const cleanup = () => { if (tmp) { const t = tmp; tmp = null; fs.unlink(t, () => {}); } };
    const fail = (e) => {
      if (done) return;
      done = true;
      if (out) out.destroy();
      cleanup();
      if (req.readable) req.resume();          /* 把余量排掉，让 413 能送达客户端 */
      reject(e);
    };
    let dir;
    try {
      dir = abs(String((req.query && req.query.dir) || '/'), '上传目录');
      if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) bad('目录不存在：' + dir, 404);
      const name = safeName(String((req.query && req.query.name) || req.headers['x-file-name'] || 'upload.bin'));
      target = path.join(dir, name);
      if (fs.existsSync(target) && String((req.query && req.query.overwrite) || '') !== '1')
        bad('同名文件已存在（加 overwrite=1 覆盖）：' + target, 409);
      if (req._body) bad('请求体已被当作 JSON 解析，无法按文件接收（请勿用 application/json 上传）', 400);
      const declared = Number(req.headers['content-length'] || 0);
      if (declared > MAX_UPLOAD_MB * 1024 * 1024) bad(`文件超过上限 ${MAX_UPLOAD_MB}MB`, 413);
    } catch (e) { reject(e); return; }

    tmp = target + '.upload-' + process.pid + '-' + Date.now() + '.part';
    try { out = fs.createWriteStream(tmp); } catch (e) { tmp = null; reject(e); return; }
    out.on('error', (e) => fail(mkErr('写入失败：' + (e.message || e), 500)));
    req.on('error', (e) => fail(mkErr('读取请求体失败：' + (e.message || e), 400)));
    req.on('aborted', () => fail(mkErr('客户端中断上传', 400)));
    req.on('data', (c) => {
      bytes += c.length;
      if (bytes > MAX_UPLOAD_MB * 1024 * 1024) fail(mkErr(`文件超过上限 ${MAX_UPLOAD_MB}MB`, 413));
    });
    out.on('finish', () => {
      if (done) return;
      done = true;
      try {
        if (!bytes) { const t = tmp; tmp = null; fs.unlinkSync(t); throw mkErr('上传内容为空'); }
        fs.renameSync(tmp, target);
        tmp = null;
        resolve({ ok: true, path: target, bytes });
      } catch (e) { cleanup(); reject(e); }
    });
    req.pipe(out);
  })));
}

function stIsDirSafe(p) { try { return fs.statSync(p).isDirectory(); } catch (_) { return false; } }

module.exports = {
  register, listDir, statOne, tree, preview, writeText, abs, safeName, symbolicMode, addToTrash,
  readTrashIndex, pruneTrash, sizeOf, hardMove, BOOKMARKS, TRASH_DAYS, MAX_TEXT, roots, trashRoot,
};
