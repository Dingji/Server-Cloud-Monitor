'use strict';
/*
 * Deployment channels + atomic releases.
 *
 * Every deploy materialises a fresh immutable directory
 *   /srv/php/<slug>/releases/<ts>
 * and only then flips the two symlinks (current, htdocs). Flipping a symlink is
 * atomic on POSIX, so a visitor never sees a half-unpacked site. If the
 * post-switch health probe fails we flip straight back.
 */
const fs = require('fs');
const path = require('path');
const C = require('./common');
const P = require('./presets');
const jobs = require('./jobs');
const site = require('./site');

const S = C.S;
const ok = C.ok;

const KEEP_RELEASES = 8;

/* ----------------------------------------------------------- raw upload */
/**
 * Stream a request body straight into a staging file. Used by POST /php/upload;
 * never buffers the archive in memory, so a 1 GB site zip does not OOM a 1.7 GB
 * box. `req` must not have been consumed by express.json (octet-stream is not).
 */
function streamUpload(req, res, opts) {
  const o = opts || {};
  const maxBytes = o.maxBytes || 2 * 1024 * 1024 * 1024;
  const id = S.genId('up');
  const dir = C.STAGE();
  C.mkdirp(dir, 0o775);
  const file = dir + '/' + id + '.bin';
  const name = decodeURIComponent(String(o.name || req.query.name || 'upload.bin')).replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').slice(0, 200);
  const out = fs.createWriteStream(file, { mode: 0o640 });
  let bytes = 0;
  let aborted = false;
  req.on('data', (chunk) => {
    bytes += chunk.length;
    if (bytes > maxBytes) {
      aborted = true;
      req.unpipe(out);
      out.end();
      try { fs.rmSync(file, { force: true }); } catch (_) {}
      if (!res.headersSent) res.status(413).json({ error: '上传超过上限 ' + Math.round(maxBytes / 1048576) + ' MB' });
      req.destroy();
    }
  });
  req.on('error', () => { if (!aborted) { aborted = true; out.destroy(); try { fs.rmSync(file, { force: true }); } catch (_) {} } });
  req.on('end', () => {
    if (aborted) return;
    out.on('error', () => { if (!res.headersSent) res.status(500).json({ error: '写入临时文件失败' }); });
    out.on('close', () => {
      if (!res.headersSent) res.json({ ok: true, id: id, file: file, bytes: bytes, name: name });
    });
    out.end();
  });
  out.on('error', () => { if (!res.headersSent) res.status(500).json({ error: '写入临时文件失败' }); });
}

const ARCHIVE_RE = /\.(zip|tar\.gz|tgz|tar\.bz2|tar\.xz|tar|7z|rar|gz)$/i;

function isArchive(name) { return ARCHIVE_RE.test(String(name || '')); }

/** Extract an archive into `dest`, flattening a single top-level directory. */
async function unpack(j, archive, dest) {
  const name = String(archive).toLowerCase();
  C.mkdirp(dest);
  if (name.endsWith('.zip')) {
    await j.drain('unzip -q -o ' + S.shq(archive) + ' -d ' + S.shq(dest), { timeout: 20 * 60000, failMsg: 'unzip 解压失败（确认已安装 unzip 且压缩包未损坏）' });
  } else if (/\.(tar\.gz|tgz|gz)$/.test(name)) {
    await j.drain('tar -xzf ' + S.shq(archive) + ' -C ' + S.shq(dest), { timeout: 20 * 60000, failMsg: 'tar -xzf 解压失败' });
  } else if (name.endsWith('.tar.bz2')) {
    await j.drain('tar -xjf ' + S.shq(archive) + ' -C ' + S.shq(dest), { timeout: 20 * 60000, failMsg: 'tar -xjf 解压失败' });
  } else if (name.endsWith('.tar.xz')) {
    await j.drain('tar -xJf ' + S.shq(archive) + ' -C ' + S.shq(dest), { timeout: 20 * 60000, failMsg: 'tar -xJf 解压失败' });
  } else if (name.endsWith('.tar')) {
    await j.drain('tar -xf ' + S.shq(archive) + ' -C ' + S.shq(dest), { timeout: 20 * 60000, failMsg: 'tar 解压失败' });
  } else if (name.endsWith('.7z')) {
    await j.drain('7z x -y -o' + S.shq(dest) + ' ' + S.shq(archive) + ' 2>&1', { timeout: 20 * 60000, failMsg: '7z 解压失败（需安装 p7zip-full）' });
  } else if (name.endsWith('.rar')) {
    await j.drain('unrar x -o+ ' + S.shq(archive) + ' ' + S.shq(dest) + '/ 2>&1', { timeout: 20 * 60000, failMsg: 'unrar 解压失败（需安装 unrar-free）' });
  } else {
    C.bad('不支持的压缩格式。支持：zip, tar.gz/tgz, tar.bz2, tar.xz, tar, 7z, rar');
  }
  return flattenSingleDir(dest);
}

/** GitHub-style archives wrap everything in `project-main/`; unwrap that. */
async function flattenSingleDir(dest) {
  let entries = [];
  try { entries = fs.readdirSync(dest); } catch (_) { return dest; }
  if (entries.length !== 1) return dest;
  const only = path.join(dest, entries[0]);
  let st = null;
  try { st = fs.statSync(only); } catch (_) {}
  if (!st || !st.isDirectory()) return dest;
  const tmp = dest + '.inner';
  try { fs.renameSync(only, tmp); } catch (_) { return dest; }
  try {
    for (const f of fs.readdirSync(tmp)) fs.renameSync(path.join(tmp, f), path.join(dest, f));
    fs.rmdirSync(tmp);
  } catch (e) {
    try { fs.renameSync(tmp, only); } catch (_) {}
    return dest;
  }
  return dest;
}

/* ------------------------------------------------------------- shared/ */
const SHARED_DEFAULTS = {
  wordpress: ['wp-content/uploads'],
  laravel: ['storage', 'bootstrap/cache', '.env'],
  thinkphp: ['runtime', '.env'],
  codeigniter: ['writable', '.env'],
  symfony: ['var', '.env'],
  drupal: ['sites/default/files', '.env'],
  hyperf: ['runtime', '.env'],
  generic: [],
};

/** Link the writeable paths out of the release so they survive a rollback. */
async function linkShared(j, rec, releaseDir) {
  const s = rec.slug;
  const names = (rec.shared && rec.shared.length ? rec.shared : SHARED_DEFAULTS[rec.preset] || []);
  const made = [];
  for (const raw of names) {
    const rel = String(raw).replace(/^\/+|\/+$/g, '');
    if (!rel || rel.includes('..')) continue;
    const shared = C.sharedDir(s) + '/' + rel;
    const inRelease = releaseDir + '/' + rel;
    C.mkdirp(path.dirname(shared), 0o775);
    C.mkdirp(path.dirname(inRelease));
    // a directory already present in the release is merged into shared, then replaced by a link
    if (fs.existsSync(inRelease) && !fs.lstatSync(inRelease).isSymbolicLink()) {
      try {
        const stat = fs.lstatSync(shared);
        if (stat.isDirectory()) await j.drain('cp -an ' + S.shq(inRelease) + '/. ' + S.shq(shared) + '/ 2>/dev/null || true');
      } catch (_) {
        await j.drain('mv ' + S.shq(inRelease) + ' ' + S.shq(shared) + ' 2>/dev/null || true');
      }
      await j.drain('rm -rf ' + S.shq(inRelease));
    } else if (fs.existsSync(inRelease)) {
      await j.drain('rm -f ' + S.shq(inRelease));
    }
    if (!fs.existsSync(shared)) {
      const isFile = rel === '.env' || rel === '.env.local';
      if (isFile) {
        C.mkdirp(path.dirname(shared));
        try { fs.writeFileSync(shared, envTemplate(rec), 'utf8'); } catch (_) {}
      } else C.mkdirp(shared, 0o775);
    }
    await j.drain('ln -sfn ' + S.shq(shared) + ' ' + S.shq(inRelease));
    made.push(rel);
  }
  return made;
}

function envTemplate(rec) {
  const db = rec.db || {};
  const lines = ['# 由面板生成，站点回滚/重新部署后仍然保留（存放在 shared/ 下）'];
  lines.push('APP_ENV=production');
  lines.push('APP_DEBUG=false');
  lines.push('APP_URL=' + (rec.domains && rec.domains.length
    ? (rec.ssl ? 'https://' : 'http://') + rec.domains[0] : 'http://' + rec.slug));
  if (db.name) {
    lines.push('DB_CONNECTION=mysql');
    lines.push('DB_HOST=127.0.0.1');
    lines.push('DB_PORT=3306');
    lines.push('DB_DATABASE=' + db.name);
    lines.push('DB_USERNAME=' + (db.user || ''));
    lines.push('DB_PASSWORD=' + (db.password || ''));
  }
  return lines.join('\n') + '\n';
}

/* ------------------------------------------------------------- hooks */
async function runHooks(j, rec, releaseDir, phase) {
  const list = (rec.hooks && rec.hooks[phase]) || [];
  const user = C.siteUser(rec.slug);
  for (const cmdRaw of list) {
    const cmd = String(cmdRaw || '').trim();
    if (!cmd) continue;
    j.log('$ [' + phase + '] ' + cmd);
    const wrapped = 'cd ' + S.shq(releaseDir) + ' && ' + cmd;
    if (user) {
      await j.drain('su -s /bin/bash -c ' + S.shq(wrapped) + ' ' + S.shq(user),
        { timeout: 15 * 60000, failMsg: '钩子执行失败：' + cmd });
    } else {
      await j.drain(wrapped, { timeout: 15 * 60000, failMsg: '钩子执行失败：' + cmd });
    }
  }
}

/* ------------------------------------------------------------- composer */
async function runComposer(j, rec, releaseDir, mode) {
  const cmd = 'COMPOSER_ALLOW_SUPERUSER=1 composer ' + mode +
    ' --no-ansi --no-interaction' + (mode === 'install' ? ' --no-dev --prefer-dist --optimize-autoloader --classmap-authoritative' : '') +
    ' --working-dir=' + S.shq(releaseDir);
  j.log('$ composer ' + mode);
  await j.drain('su -s /bin/bash -c ' + S.shq(cmd) + ' ' + S.shq(C.siteUser(rec.slug)),
    { timeout: 20 * 60000, failMsg: 'composer ' + mode + ' 失败（检查 composer.json 与网络）' });
}

/* ------------------------------------------------------------ channels */
async function channelArchive(j, rec, releaseDir, body) {
  const src = await resolveArchive(j, body);
  j.set(30, 'unpack', '解压到 ' + path.basename(releaseDir));
  const before = releaseDir;
  await unpack(j, src, releaseDir);
  if (src.startsWith(C.STAGE() + '/')) {
    await j.drain('rm -f ' + S.shq(src) + ' 2>/dev/null || true');
    j.log('已清理临时上传文件');
  }
  return before;
}

async function resolveArchive(j, body) {
  const channel = body.source || 'upload';
  if (channel === 'upload') {
    const file = C.STAGE() + '/' + String(body.uploadId || '').replace(/[^a-z0-9_]/gi, '') + '.bin';
    if (!fs.existsSync(file)) C.bad('上传文件不存在或已过期，请重新上传：' + path.basename(file));
    const st = fs.statSync(file);
    if (st.size < 22) C.bad('上传文件过小（' + st.size + ' 字节），像是空文件');
    j.log('已接收上传文件：' + file + ' (' + Math.round(st.size / 1024) + ' KB)');
    return file;
  }
  if (channel === 'url') {
    const url = String(body.url || '');
    if (!/^https?:\/\//i.test(url)) C.bad('只允许 http(s) 下载地址');
    const tmp = C.STAGE() + '/' + S.genId('dl') + '.bin';
    C.mkdirp(C.STAGE(), 0o775);
    j.log('$ curl -L ' + url);
    await j.drain('curl -fL --max-time 900 --retry 2 -o ' + S.shq(tmp) + ' ' + S.shq(url) + ' 2>&1',
      { timeout: 16 * 60000, failMsg: '下载失败' });
    return tmp;
  }
  if (channel === 'path') {
    const p = path.resolve(String(body.path || ''));
    if (!fs.existsSync(p)) {
      const hint = (p.startsWith('/tmp') || p.startsWith('/var/tmp'))
        ? '\n面板服务启用了 PrivateTmp，运维在宿主机 /tmp 下放的文件面板读不到。'
          + '\n请改用「上传压缩包」，或把文件放到 ' + C.STAGE() + '/ 再按路径部署。'
        : '\n请确认路径拼写，或先把压缩包放入 ' + C.STAGE() + '/ 再按路径部署。';
      C.bad('面板读不到该文件：' + p + hint);
    }
    let st = null;
    try { st = fs.statSync(p); } catch (_) {}
    if (!st || !st.isFile()) C.bad('该路径不是面板可读的普通文件：' + p);
    if (!isArchive(p)) C.bad('该文件不是可识别的压缩包：' + p);
    return p;
  }
  C.bad('未知的压缩包来源：' + channel + '（可选 upload / url / path）');
}

async function channelGit(j, rec, releaseDir, body) {
  const cfg = Object.assign({}, rec.git || {}, body.git || {});
  const url = String(cfg.url || '').trim();
  if (!url) C.bad('未配置 Git 仓库地址');
  if (!/^(https?:\/\/|git@|ssh:\/\/)/.test(url)) C.bad('Git 地址需以 https:// / ssh:// / git@ 开头');
  const branch = String(cfg.branch || 'main').trim() || 'main';
  if (!/^[\w./-]{1,100}$/.test(branch)) C.bad('非法分支名：' + branch);
  const sub = String(cfg.subdir || '').trim();
  const depth = Math.max(1, Math.min(200, Number(cfg.depth) || 1));

  const s = rec.slug;
  const cache = C.siteDir(s) + '/repo-cache';
  C.mkdirp(cache);
  j.set(10, 'git', '拉取 ' + branch);

  const keyFile = await prepareDeployKey(j, rec, cfg);
  const authUrl = gitUrlWithToken(j, url, cfg);
  const sshCmd = 'ssh -i ' + S.shq(keyFile || (process.env.HOME || '/root') + '/.ssh/id_ed25519') +
    ' -o StrictHostKeyChecking=accept-new -o BatchMode=yes -o IdentitiesOnly=yes';
  const cmd = [
    'set -e',
    'export GIT_TERMINAL_PROMPT=0',
    'export GIT_SSH_COMMAND=' + S.shq(sshCmd),
    'if [ ! -d ' + S.shq(cache + '/.git') + ' ]; then git init -q ' + S.shq(cache) + '; fi',
    'cd ' + S.shq(cache),
    'git remote remove origin 2>/dev/null || true',
    'git remote add origin ' + S.shq(authUrl),
    'git fetch --depth ' + depth + ' origin ' + S.shq(branch),
    'git checkout -q -B ' + S.shq(branch) + ' FETCH_HEAD',
    'git clean -ffd',
    'git submodule sync --recursive 2>/dev/null || true',
    'git submodule update --init --recursive --depth ' + depth + ' 2>/dev/null || true',
  ].join('\n');
  await j.drain(cmd, { timeout: 15 * 60000, failMsg: 'Git 拉取失败（检查地址/分支/凭据）' });

  const sha = await C.sh('git -C ' + S.shq(cache) + ' rev-parse --short HEAD 2>&1', { timeout: 15000 })
    .then((r) => r.stdout.trim()).catch(() => '');
  const subject = await C.sh('git -C ' + S.shq(cache) + ' log -1 --pretty=%s 2>&1 | head -1', { timeout: 15000 })
    .then((r) => r.stdout.trim()).catch(() => '');
  j.log('HEAD = ' + sha + '  ' + subject);

  j.set(35, 'export', '导出工作副本');
  const srcRoot = sub ? (cache + '/' + sub.replace(/^\/+|\/+$/g, '')) : cache;
  // copy without the .git dir so each release is plain code
  await j.drain('rsync -a --exclude=.git --exclude=.github ' + S.shq(srcRoot) + '/ ' + S.shq(releaseDir) + '/ 2>&1' +
    ' || cp -a ' + S.shq(srcRoot) + '/. ' + S.shq(releaseDir) + '/ 2>&1',
    { timeout: 10 * 60000, failMsg: '从缓存导出到发布目录失败' });
  return { sha: sha, subject: subject };
}

/**
 * Credentials are stored on the site record (0600 file in the ops store) and
 * materialised at deploy time — never echoed into a command line where `ps`
 * would expose them.
 */
async function prepareDeployKey(j, rec, cfg) {
  if (!cfg.privateKey) return null;
  const keyFile = C.siteDir(rec.slug) + '/deploy_key';
  C.mkdirp(C.siteDir(rec.slug));
  fs.writeFileSync(keyFile, String(cfg.privateKey).replace(/\r/g, '').trimEnd() + '\n', { mode: 0o600 });
  try { fs.chmodSync(keyFile, 0o600); } catch (_) {}
  await C.sh('chown ' + S.shq(C.siteUser(rec.slug)) + ' ' + S.shq(keyFile) + ' 2>/dev/null || true');
  j.log('已写入 deploy key：' + keyFile);
  return keyFile;
}

function gitUrlWithToken(j, url, cfg) {
  if (!cfg.token || !/^https:\/\//i.test(url)) return url;
  j.log('使用 HTTPS Token 认证（token 不会出现在日志里）');
  let u;
  try { u = new URL(url); } catch (_) { C.bad('Git 地址无法解析：' + url); }
  const cred = cfg.tokenUser || 'x-access-token';
  return u.protocol + '//' + encodeURIComponent(cred) + ':' + encodeURIComponent(cfg.token) +
    '@' + u.host + u.pathname + (u.search || '');
}

/* ------------------------------------------------------------- deploy */
async function probeSite(j, rec) {
  const hosts = rec.domains && rec.domains.length ? [rec.domains[0]] : [];
  const port = (rec.ports && rec.ports.includes(443)) ? 443 : ((rec.ports || [80])[0] || 80);
  const scheme = port === 443 ? 'https' : 'http';
  const target = hosts.length ? scheme + '://' + hosts[0] + (port !== 80 && port !== 443 ? ':' + port : '') + '/'
    : scheme + '://127.0.0.1:' + port + '/';
  const hostHeader = hosts.length ? '' : ' -H ' + S.shq('Host: ' + (rec.domains[0] || rec.slug));
  const cmd = 'curl -sS -o /dev/null -w "%{http_code} %{time_total}s %{size_download}B" -L --max-time 25 -k' +
    hostHeader + ' ' + S.shq(target) + ' 2>&1';
  const r = await C.sh(cmd, { timeout: 40000 }).catch((e) => ({ text: 'curl 失败: ' + (e.message || '') }));
  const text = String(r.text || '').trim();
  j.log('健康探测 ' + target + ' -> ' + text);
  const code = Number((text.match(/^(\d{3})/) || [])[1]) || 0;
  return { url: target, code: code, detail: text, healthy: code > 0 && code < 500 };
}

function startDeploy(body) {
  const rec = C.get(body.slug);
  const channel = String(body.channel || 'archive');
  if (!['archive', 'git', 'composer', 'scaffold', 'reload'].includes(channel)) C.bad('未知部署通道：' + channel);

  const title = { archive: '压缩包部署', git: 'Git 部署', composer: 'Composer 更新', scaffold: '初始化应用', reload: '仅重载配置' }[channel];
  const job = jobs.start({
    kind: 'deploy:' + channel, site: rec.slug, title: rec.slug + ' — ' + title,
    run: async (j) => {
      const ver = rec.php || await site.defaultVersion();
      const prevRelease = rec.activeRelease || null;
      const id = C.tsName();
      const releaseDir = C.releasesDir(rec.slug) + '/' + id;
      C.mkdirp(releaseDir);
      C.mkdirp(C.logsDir(rec.slug));
      j.set(5, 'prepare', '创建发布目录 releases/' + id);

      const meta = { kind: channel, note: String(body.note || '').slice(0, 300), by: 'panel' };

      if (channel === 'archive') {
        await channelArchive(j, rec, releaseDir, body);
      } else if (channel === 'git') {
        const g = await channelGit(j, Object.assign({}, rec, { slug: rec.slug }), releaseDir, body);
        meta.sha = g.sha;
        meta.subject = g.subject;
        if (body.git && body.git.remember) {
          rec.git = Object.assign({}, rec.git || {}, {
            url: String(body.git.url || rec.git && rec.git.url || ''),
            branch: String(body.git.branch || rec.git && rec.git.branch || 'main'),
            subdir: String(body.git.subdir || (rec.git && rec.git.subdir) || ''),
          });
        }
      } else if (channel === 'composer') {
        await j.drain('cp -a ' + S.shq(C.currentLink(rec.slug) + '/.') + ' ' + S.shq(releaseDir) + '/ 2>&1 || true',
          { timeout: 10 * 60000 });
        j.log('以当前版本为基线执行 composer update');
      } else if (channel === 'scaffold') {
        await scaffold(j, rec, releaseDir, body);
      } else if (channel === 'reload') {
        fs.rmSync(releaseDir, { recursive: true, force: true });
        j.set(60, 'apply', '仅重新生成并应用配置');
        const applied = await site.apply(rec);
        j.log(JSON.stringify(applied.reload || applied.fpmReload || {}));
        j.set(100, 'done', '完成');
        return title + ' 完成';
      }

      if (fs.existsSync(releaseDir) && channel !== 'reload') {
        j.set(50, 'shared', '链接 shared/ 可写目录');
        await linkShared(j, rec, releaseDir);

        const hasComposer = fs.existsSync(releaseDir + '/composer.json') && channel !== 'archive'
          ? true : fs.existsSync(releaseDir + '/composer.json');
        if (hasComposer) {
          j.set(58, 'composer', '安装 PHP 依赖');
          await runComposer(j, rec, releaseDir, channel === 'composer' ? 'update' : 'install');
        }

        await runHooks(j, rec, releaseDir, 'pre_deploy');

        j.set(78, 'perm', '切换 current/htdocs 软链');
        const docRoot = String(body.docRoot != null ? body.docRoot : rec.docRoot || P.presetOf(rec.preset).publicDir || '');
        await site.linkHtdocs(rec.slug, id, docRoot);
        rec.docRoot = docRoot;

        await C.fixOwnership(rec.slug, { path: C.siteDir(rec.slug) });
        for (const d of (rec.uploadDirs || []).concat(P.presetOf(rec.preset).denyPhpIn || [])) {
          const p = releaseDir + '/' + String(d).replace(/^\/+/, '');
          await C.sh('chmod -R g+rwX ' + S.shq(p) + ' 2>/dev/null || true', { timeout: 30000 }).catch(() => {});
        }

        await runHooks(j, rec, releaseDir, 'post_deploy');

        j.set(86, 'apply', '应用 nginx / PHP-FPM 配置');
        const applied = await site.apply(rec);
        j.log('nginx: ' + applied.nginx + ' | pool: ' + applied.pool + ' | fpm: ' +
          (applied.fpmReload ? applied.fpmReload.service + '=' + applied.fpmReload.active : '-'));

        j.set(92, 'probe', '健康探测');
        const probe = await probeSite(j, rec);
        meta.probe = probe;
        if (!probe.healthy && body.autoRollback !== false) {
          j.log('探测到 HTTP ' + probe.code + '，自动回滚到 ' + (prevRelease || '(无)'));
          if (prevRelease) {
            await site.linkHtdocs(rec.slug, prevRelease, rec.docRoot || P.presetOf(rec.preset).publicDir || '');
            await site.apply(rec);
            fs.rmSync(releaseDir, { recursive: true, force: true });
            j.set(100, 'rolledback', '已回滚，发布目录已删除');
            const e = new Error('部署后健康检查失败（HTTP ' + probe.code + '），已自动回滚到 ' + prevRelease);
            e.output = j.lines.slice(-60).join('\n');
            throw e;
          }
        }

        j.set(96, 'prune', '清理历史版本');
        const keep = await prune(rec, id);
        if (keep.length) j.log('已删除旧版本：' + keep.join(', '));

        rec.releases = (rec.releases || []).concat([{
          id: id, at: new Date().toISOString(), kind: channel,
          note: meta.note, sha: meta.sha || null, subject: meta.subject || null, size: null,
        }]).slice(-KEEP_RELEASES);
        rec.activeRelease = id;
        rec.updatedAt = new Date().toISOString();
        if (rec.docRootChanged) delete rec.docRootChanged;
        C.put(rec);
        C.saveMeta(rec);
      }

      j.set(100, 'done', '完成');
      await j.drain('echo -n "" 2>/dev/null || true', { timeout: 5000 }).catch(() => {});
      const summary = [
        '站点 ' + rec.slug + ' ' + title + '完成',
        '版本 releases/' + id,
        'PHP ' + ver,
        meta.probe ? '探测 ' + meta.probe.url + ' -> ' + meta.probe.detail : '',
      ].filter(Boolean).join('\n');
      fs.appendFileSync(C.logsDir(rec.slug) + '/deploy.log',
        new Date().toISOString() + ' ' + summary.replace(/\n/g, ' | ') + '\n', 'utf8');
      return summary;
    },
  });
  return ok('部署任务已启动：' + title, { job: jobs.view(job), site: rec.slug });
}

/** Keep the newest KEEP_RELEASES; never remove the active one. */
async function prune(rec, activeId) {
  let ids = [];
  try { ids = fs.readdirSync(C.releasesDir(rec.slug)).filter((x) => !x.startsWith('.')); } catch (_) { return []; }
  ids.sort();
  const doomed = ids.filter((x) => x !== activeId).slice(0, Math.max(0, ids.length - KEEP_RELEASES));
  for (const d of doomed) {
    await C.sh('rm -rf ' + S.shq(C.releasesDir(rec.slug) + '/' + d), { timeout: 60000 }).catch(() => {});
    rec.releases = (rec.releases || []).filter((r) => r.id !== d);
  }
  return doomed;
}

/* ------------------------------------------------------------ rollback */
function rollback(body) {
  const rec = C.get(body.slug);
  const target = String(body.release || '');
  const ids = (rec.releases || []).map((r) => r.id);
  if (!/^[A-Za-z0-9._-]{1,60}$/.test(target)) C.bad('非法版本号：' + target);
  if (!C.exists(C.releasesDir(rec.slug) + '/' + target)) C.bad('该版本目录不存在：releases/' + target);

  const prev = rec.activeRelease;
  const job = jobs.start({
    kind: 'rollback', site: rec.slug, title: rec.slug + ' — 回滚到 ' + target,
    run: async (j) => {
      j.set(20, 'switch', '切换 current -> releases/' + target);
      await site.linkHtdocs(rec.slug, target, rec.docRoot || P.presetOf(rec.preset).publicDir || '');
      j.set(50, 'apply', '重载 PHP-FPM（同时清空 OPcache）');
      await C.fixOwnership(rec.slug, { path: C.siteDir(rec.slug) });
      const applied = await site.apply(rec);
      j.log('nginx=' + applied.nginx + ' fpm=' + (applied.fpmReload || {}).active);
      j.set(75, 'probe', '健康探测');
      const probe = await probeSite(j, rec);
      rec.rollbackHistory = (rec.rollbackHistory || []).concat([{
        at: new Date().toISOString(), from: prev, to: target, probe: probe.code,
      }]).slice(-20);
      rec.activeRelease = target;
      rec.updatedAt = new Date().toISOString();
      C.put(rec);
      j.set(100, 'done', '完成');
      return '已回滚到 releases/' + target + '\n探测 ' + probe.url + ' -> ' + probe.detail +
        '\n（原版本 releases/' + (prev || '-') + ' 仍保留，可再次切换）';
    },
  });
  return ok('回滚任务已启动', { job: jobs.view(job) });
}

function listReleases(body) {
  const rec = C.get(body.slug || body);
  const s = rec.slug;
  let ids = [];
  try { ids = fs.readdirSync(C.releasesDir(s)).filter((x) => !x.startsWith('.')).sort().reverse(); } catch (_) {}
  let current = null;
  try { current = path.basename(fs.readlinkSync(C.currentLink(s))); } catch (_) {}
  return {
    slug: s,
    active: rec.activeRelease || current,
    currentLink: current,
    docRoot: rec.docRoot || '',
    releases: ids.map((id) => {
      const rec2 = (rec.releases || []).find((r) => r.id === id) || {};
      const dir = C.releasesDir(s) + '/' + id;
      let mtime = null;
      try { mtime = fs.statSync(dir).mtime.toISOString(); } catch (_) {}
      return {
        id: id, active: id === (rec.activeRelease || current),
        kind: rec2.kind || 'unknown', note: rec2.note || '', sha: rec2.sha || null,
        at: rec2.at || mtime, sizeKb: null,
      };
    }),
    history: (rec.rollbackHistory || []).slice(-10).reverse(),
    hooks: rec.hooks || {},
    shared: rec.shared || SHARED_DEFAULTS[rec.preset] || [],
  };
}

/* ---------------------------------------------------------- webhooks */
function webhookFor(rec) {
  const cfg = rec.git || {};
  return {
    enabled: !!cfg.autoDeploy,
    token: rec.webhookToken || null,
    path: '/panel/api/php/webhook/' + rec.slug + '/' + (rec.webhookToken || '?'),
    curl: 'curl -X POST http://<服务器IP>/panel/api/php/webhook/' + rec.slug + '/' + (rec.webhookToken || '<token>'),
  };
}

async function handleWebhook(params) {
  const rec = C.get(params.slug);
  if (!rec.webhookToken || String(params.token) !== rec.webhookToken) {
    C.bad('webhook 令牌不正确', 403);
  }
  if (!(rec.git && rec.git.url)) C.bad('站点未配置 Git 仓库');
  if (!rec.git.autoDeploy) C.bad('该站点未开启自动部署');
  const last = rec.lastHookAt || 0;
  if (Date.now() - Number(last) < 20000) C.bad('两次自动部署间隔需大于 20 秒（去抖）');
  rec.lastHookAt = Date.now();
  C.put(rec);
  const r = startDeploy({ slug: rec.slug, channel: 'git', note: 'webhook 触发', autoRollback: true });
  C.logEvent({ kind: 'deploy.webhook', site: rec.slug, job: r.job && r.job.id });
  return r;
}

/* ----------------------------------------------------------- scaffolding */
async function scaffold(j, rec, releaseDir, body) {
  const which = String(body.app || rec.preset || '');
  const pre = P.presetOf(which);
  const inst = pre.install;
  if (!inst) C.bad('预设 ' + which + ' 没有一键安装方式，请改用压缩包或 Git 部署');
  if (inst.kind === 'download') {
    j.set(15, 'download', '获取 ' + inst.name + ' 最新版本');
    const info = await C.sh('curl -fsS --max-time 25 ' + S.shq(inst.versionUrl) + ' 2>&1 | head -c 4000', { timeout: 40000 })
      .catch(() => ({ stdout: '' }));
    let url = '';
    let version = '';
    try {
      const data = JSON.parse(String(info.stdout));
      url = (data.downloads && data.downloads[0] && data.downloads[0].url) || '';
      version = (data.downloads && data.downloads[0] && data.downloads[0].current) || '';
    } catch (_) {}
    if (!url && /wordpress/i.test(inst.name)) {
      url = 'https://wordpress.org/latest.tar.gz';
      const r2 = await C.sh('curl -fsS --max-time 15 https://api.wordpress.org/core/version-check/1.7/ 2>/dev/null | ' +
        'grep -o \'"current":"[^"]*"\' | head -1 | cut -d\'"\' -f4', { timeout: 30000 }).catch(() => ({ stdout: '' }));
      version = String(r2.stdout).trim();
    }
    if (!url) C.bad('无法取得 ' + inst.name + ' 下载地址（检查服务器出网与 api.wordpress.org 可达性）');
    j.log('版本 ' + (version || '?') + '  来源 ' + url);
    const tmp = C.STAGE() + '/' + S.genId('sc') + '.bin';
    C.mkdirp(C.STAGE(), 0o775);
    await j.drain('curl -fL --max-time 600 -o ' + S.shq(tmp) + ' ' + S.shq(url) + ' && ' +
      'tar -xzf ' + S.shq(tmp) + ' -C ' + S.shq(releaseDir) + ' && rm -f ' + S.shq(tmp),
      { timeout: 15 * 60000, failMsg: '下载或解压失败' });
    const flat = await flattenSingleDir(releaseDir);
    if (flat !== releaseDir) C.bad('解压结构异常');
    if (inst.configTemplate === 'wp-config.php') {
      j.set(45, 'config', '生成 wp-config.php');
      const sample = releaseDir + '/wp-config-sample.php';
      if (fs.existsSync(sample)) {
        let text = fs.readFileSync(sample, 'utf8');
        const db = rec.db || {};
        const salts = ['put your unique phrase here'];
        const repl = {
          'database_name_here': db.name || '',
          'username_here': db.user || '',
          'password_here': db.password || '',
          'localhost': 'localhost',
        };
        for (const k of Object.keys(repl)) text = text.split(k).join(repl[k]);
        for (let i = 0; i < 8; i++) {
          const key = ['AUTH_KEY', 'SECURE_AUTH_KEY', 'LOGGED_IN_KEY', 'NONCE_KEY',
            'AUTH_SALT', 'SECURE_AUTH_SALT', 'LOGGED_IN_SALT', 'NONCE_SALT'][i];
          text = text.replace(new RegExp("define\\( *'" + key + "', *'[^']*'\\s*\\)"),
            "define('" + key + "', '" + wpSalt() + "')");
        }
        text = text.replace("table_prefix = 'wp_'", "table_prefix = '" + (rec.tablePrefix || 'wp_') + "'");
        text = text.replace(/define\('WP_DEBUG', true\);/, "define('WP_DEBUG', false);");
        text = text.replace(/\?>\s*$/, '') +
          "\n/* 面板追加 */\ndefine('FS_METHOD', 'direct');\nif (isset($_SERVER['HTTP_X_FORWARDED_PROTO']) && $_SERVER['HTTP_X_FORWARDED_PROTO'] === 'https') { $_SERVER['HTTPS'] = 'on'; }\n";
        fs.writeFileSync(releaseDir + '/wp-config.php', text, 'utf8');
        j.log('wp-config.php 已生成（数据库 ' + (db.name || '未绑定') + '）');
      }
    }
    rec.appVersion = version;
  } else if (inst.kind === 'composer') {
    j.set(20, 'composer', 'composer create-project ' + inst.name);
    const cmd = 'COMPOSER_ALLOW_SUPERUSER=1 composer create-project ' + S.shq(inst.name) + ' ' +
      S.shq(releaseDir) + ' --no-ansi --no-interaction --prefer-dist' +
      (inst.args ? ' ' + inst.args : '');
    await j.drain(cmd, { timeout: 20 * 60000, failMsg: 'composer create-project 失败' });
  }
}

function wpSalt() {
  const c = require('crypto');
  return c.randomBytes(32).toString('base64').replace(/[^a-zA-Z0-9]/g, '').slice(0, 32) || C.genSecret(32);
}

module.exports = {
  KEEP_RELEASES, SHARED_DEFAULTS, streamUpload, isArchive, unpack, flattenSingleDir,
  linkShared, envTemplate, runHooks, runComposer, probeSite,
  startDeploy, rollback, listReleases, prune, webhookFor, handleWebhook, scaffold,
};
