/* File manager page: browse / typed preview / edit / upload / download /
   rename / move / copy / chmod / chown / archive+extract / search / usage /
   trash with restore. Backed by /ops/files* (ops-server/files.js).
   Preview is type-aware: text(edit) · image · video · audio · pdf ·
   archive(内部条目可预览/可单独下载). */
(function () {
  'use strict';

  /* 与服务端 kindOf() 对齐，用于决定按钮显隐；最终以 /preview 返回的 kind 为准 */
  const ARCHIVE_RE = /\.(zip|tar|tar\.gz|tgz|tar\.bz2|tar\.xz|txz)$/i;
  function kindOf(name) {
    const lower = String(name).toLowerCase();
    if (ARCHIVE_RE.test(lower)) return 'archive';
    if (/\.(png|jpe?g|gif|webp|svg|bmp|ico|avif)$/.test(lower)) return 'image';
    if (/\.(mp4|webm|mov|m4v|mkv|avi)$/.test(lower)) return 'video';
    if (/\.(mp3|wav|ogg|oga|m4a|flac|aac|opus)$/.test(lower)) return 'audio';
    if (lower.endsWith('.pdf')) return 'pdf';
    return 'other';
  }
  const isArchive = (name) => ARCHIVE_RE.test(String(name).toLowerCase());
  const baseName = (p) => String(p).split('/').pop() || '/';
  const parentOf = (p) => {
    const i = String(p).lastIndexOf('/');
    return i <= 0 ? '/' : String(p).slice(0, i);
  };
  const enc = encodeURIComponent;
  const iconColored = (name, size, color) => `<span style="color:${color};display:inline-flex">${icon(name, { size: size || 13 })}</span>`;

  /* ---------- authed blob fetch with progress ---------- */
  async function authedBlob(url, onprogress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', url);
      xhr.setRequestHeader('Authorization', 'Bearer ' + CM.token);
      xhr.responseType = 'blob';
      xhr.onprogress = (e) => { if (onprogress) onprogress(e.loaded, e.total || 0); };
      xhr.onload = async () => {
        if (xhr.status === 200) { resolve(URL.createObjectURL(xhr.response)); return; }
        let msg = 'HTTP ' + xhr.status;
        try { const j = JSON.parse(await xhr.response.text()); if (j.error) msg = j.error; } catch (_) {}
        reject(new Error(msg));
      };
      xhr.onerror = () => reject(new Error('网络错误'));
      xhr.send();
    });
  }

  /* ---------- transfers strip ---------- */
  let transfers = [];
  function addTransfer(name, kind, total) {
    const t = { name, kind, sent: 0, total: total || 0, done: false, error: null };
    transfers.push(t);
    if (transfers.length > 8) transfers = transfers.slice(-8);
    return t;
  }
  function drawTransfers() {
    const box = document.getElementById('fm-transfers');
    if (!box) return;
    if (!transfers.length) { box.style.display = 'none'; return; }
    box.style.display = '';
    box.innerHTML = transfers.map((t) => {
      const pct = t.total ? Math.min(100, Math.round(t.sent / t.total * 100)) : 0;
      const label = (t.kind === 'up' ? '上传' : '下载') + ' · ' + fmt.esc(t.name) + ' · ' + fmt.bytes(t.sent) + (t.total ? ' / ' + fmt.bytes(t.total) : '');
      if (t.error) return `<div class="small" style="color:#cf3b3b">${icon('warning', { size: 12 })} ${label} · ${fmt.esc(t.error)}</div>`;
      if (t.done) return `<div class="small muted">${icon('check', { size: 12 })} ${label} · 完成</div>`;
      return `<div class="small muted">${label}</div><div class="bar" style="margin:3px 0 6px"><div style="width:${pct}%"></div></div>`;
    }).join('');
  }

  /* 带进度的下载 → 浏览器另存 */
  function triggerDownload(url, name) {
    return new Promise((resolve, reject) => {
      const t = addTransfer(name, 'down');
      const xhr = new XMLHttpRequest();
      xhr.open('GET', url);
      xhr.setRequestHeader('Authorization', 'Bearer ' + CM.token);
      xhr.responseType = 'blob';
      xhr.onprogress = (e) => { t.sent = e.loaded; t.total = e.total || 0; drawTransfers(); };
      xhr.onload = async () => {
        if (xhr.status !== 200) {
          t.error = 'HTTP ' + xhr.status;
          try { const j = JSON.parse(await xhr.response.text()); if (j.error) t.error = j.error; } catch (_) {}
          drawTransfers(); reject(new Error(t.error)); return;
        }
        const url2 = URL.createObjectURL(xhr.response);
        const a = document.createElement('a');
        a.href = url2; a.download = name;
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(url2), 30000);
        t.done = true; t.sent = t.total = xhr.response.size; drawTransfers();
        resolve(xhr.response.size);
      };
      xhr.onerror = () => { t.error = '网络错误'; drawTransfers(); reject(new Error('网络错误')); };
      xhr.send();
    });
  }

  /* 带进度的流式上传；resolve({ok,status,error})，覆盖确认交给调用方 */
  function uploadFile(dir, file, overwrite) {
    return new Promise((resolve) => {
      const t = addTransfer(file.name, 'up', file.size);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', CM.apiBase + '/ops/files/upload?dir=' + enc(dir) + '&name=' + enc(file.name) + (overwrite ? '&overwrite=1' : ''));
      xhr.setRequestHeader('Authorization', 'Bearer ' + CM.token);
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.upload.onprogress = (e) => { t.sent = e.loaded; t.total = e.total || file.size; drawTransfers(); };
      xhr.onload = () => {
        let j = {};
        try { j = JSON.parse(xhr.responseText); } catch (_) {}
        if (xhr.status === 200) { t.done = true; drawTransfers(); resolve({ ok: true }); }
        else { t.error = j.error || ('HTTP ' + xhr.status); t.status = xhr.status; drawTransfers(); resolve({ ok: false, status: xhr.status, error: t.error }); }
      };
      xhr.onerror = () => { t.error = '网络错误'; drawTransfers(); resolve({ ok: false, status: 0, error: '网络错误' }); };
      xhr.send(file);
    });
  }

  function btnSm(act, path, label, cls) {
    return `<button class="btn btn-sm ${cls || ''}" data-act="${act}" data-path="${fmt.esc(path == null ? '' : path)}">${label}</button>`;
  }

  registerPage({
    id: 'files',
    title: '文件管理',
    desc: '浏览 · 分类型预览（文本/图片/音视频/PDF/压缩包内部）· 在线编辑 · 上传下载 · 权限属主 · 打包解压 · 回收站',
    async mount(root) {
      const S = {
        dir: localStorage.getItem('cm_files_dir') || '/etc',
        l: null, selected: new Set(),
        showTrash: false, trash: null,
        hits: null, query: '',
        usage: null, usageFor: '',
      };

      let inflight = false;
      async function load(dir) {
        if (inflight) return;
        inflight = true;
        try {
          const d = await CM.api.get('/ops/files/list?dir=' + enc(dir || S.dir), 0);
          S.l = d;
          S.dir = d.dir;
          S.selected.clear();
          S.hits = null;
          localStorage.setItem('cm_files_dir', d.dir);
          render();
        } catch (e) {
          /* 目录不存在（如被删掉）时保留可导航的最小界面，而不是整页锁死 */
          renderError(e.message);
        } finally { inflight = false; }
      }

      function renderError(msg) {
        root.innerHTML = `
        <div id="fm-transfers" class="card mb" style="display:none;padding:12px 16px"></div>
        <div class="card mb">
          <div class="card-title">位置</div>
          <div style="padding:0 16px 16px;display:flex;gap:8px;flex-wrap:wrap">
            <input class="inp mono" id="fm-goto" style="flex:1;min-width:260px" value="${fmt.esc(S.dir)}" placeholder="绝对路径">
            <button class="btn" data-act="goto">前往</button>
            <button class="btn btn-primary" data-act="home">回到 /etc</button>
          </div>
        </div>
        <div class="card"><div class="muted">读取目录失败：${fmt.esc(msg)}</div></div>`;
        drawTransfers();
        bindEvents();
      }

      async function loadTrash() {
        try { S.trash = await CM.api.get('/ops/files/trash', 0); } catch (_) { S.trash = null; }
      }

      /* ---------------- main render ---------------- */
      function render() {
        const l = S.l;
        const crumbs = l.breadcrumb.map((c) =>
          `<button class="btn btn-sm" data-act="go" data-path="${fmt.esc(c.path)}">${fmt.esc(c.label)}</button>`).join(' <span class="muted">/</span> ');
        const df = l.df ? `
          <div class="small muted" style="margin-top:10px">文件系统 <span class="mono">${fmt.esc(l.df.filesystem)}</span> · 已用 ${fmt.esc(l.df.pct)} · 剩余 ${fmt.bytes(l.df.availKb * 1024)} / 共 ${fmt.bytes(l.df.totalKb * 1024)}</div>
          <div class="bar ${fmt.pctColor(parseFloat(l.df.pct))}" style="margin-top:5px"><div style="width:${parseFloat(l.df.pct) || 0}%"></div></div>` : '';

        const bookmarks = (l.bookmarks || []).map((b) =>
          `<button class="btn btn-sm" data-act="go" data-path="${fmt.esc(b.path)}">${iconColored('folder', 12, 'var(--accent)')} ${fmt.esc(b.label)}</button>`).join(' ');

        const selBar = S.selected.size ? `
        <div class="card mb">
          <div class="card-title">已选 ${S.selected.size} 项
            <div class="right" style="display:flex;gap:6px;flex-wrap:wrap">
              ${btnSm('move', '', '移动到…')}
              ${btnSm('copy', '', '复制到…')}
              ${btnSm('archTgz', '', '打包 .tar.gz')}
              ${btnSm('archZip', '', '打包 .zip')}
              ${btnSm('chmod', '', '改权限')}
              ${btnSm('chown', '', '改属主')}
              ${btnSm('del', '', '移入回收站', 'btn-danger')}
              ${btnSm('clearSel', '', '取消选择')}
            </div>
          </div>
          <div class="small muted mono" style="padding:0 16px 12px;word-break:break-all">${[...S.selected].map(fmt.esc).join(' · ')}</div>
        </div>` : '';

        const rows = l.entries.slice(0, 800);
        const table = ui.table([
          { k: '', label: '', render: (v, e) => `<input type="checkbox" data-act="sel" data-path="${fmt.esc(e.path)}" ${S.selected.has(e.path) ? 'checked' : ''} style="width:auto">` },
          {
            k: 'name', label: '名称', render: (v, e) => {
              const ic = e.isDir ? ['folder', 'var(--accent)'] : isArchive(e.name) ? ['archive', '#c79209'] : kindOf(e.name) === 'image' ? ['eye', '#2fa84f'] : ['logs', 'var(--muted)'];
              const link = `<a href="javascript:void 0" data-act="${e.isDir ? 'go' : 'preview'}" data-path="${fmt.esc(e.path)}" style="color:inherit;text-decoration:none">${fmt.esc(e.name)}</a>`;
              const linkTag = e.isLink && e.linkTarget ? `<div class="small muted mono">→ ${fmt.esc(e.linkTarget)}</div>` : '';
              return `<div style="display:flex;gap:7px;align-items:center">${iconColored(ic[0], 13, ic[1])}<div>${link}${linkTag}</div></div>`;
            },
          },
          { k: 'typeLabel', label: '类型', render: (v, e) => `<span class="${e.isDir ? '' : 'muted'}">${fmt.esc(v)}</span>` },
          { k: 'size', label: '大小', cls: 'mono', render: (v, e) => e.isDir ? `<span class="muted">${e.entryCount ? e.entryCount + ' 项' : '—'}</span>` : fmt.bytes(v) },
          { k: 'symbolic', label: '权限', cls: 'mono', render: (v, e) => fmt.esc(v || e.mode || '—') },
          {
            k: 'owner', label: '属主', cls: 'mono', render: (v, e) =>
              fmt.esc([e.owner, e.group].filter(Boolean).join(':') || [e.uid, e.gid].filter(Boolean).join(':') || '—'),
          },
          { k: 'mtimeMs', label: '修改时间', cls: 'mono', render: (v) => `<span class="muted">${v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—'}</span>` },
          {
            k: '', label: '操作', render: (v, e) => {
              const acts = [];
              if (e.isDir) acts.push(btnSm('go', e.path, '进入'));
              else {
                acts.push(btnSm('preview', e.path, '预览'));
                if (!e.isLink) acts.push(btnSm('edit', e.path, '编辑'));
                acts.push(btnSm('download', e.path, '下载'));
                if (isArchive(e.name)) acts.push(btnSm('extract', e.path, '解压'));
              }
              acts.push(btnSm('rename', e.path, '重命名'));
              acts.push(btnSm('delOne', e.path, '删除', 'btn-danger'));
              return `<div style="display:flex;gap:4px;flex-wrap:wrap">${acts.join('')}</div>`;
            },
          },
        ], rows, { style: 'max-height:540px' }) +
          (l.entries.length > 800 ? `<div class="small muted" style="padding:8px 16px">仅显示前 800 项（共 ${l.entries.length}），可用搜索缩小范围</div>` : '');

        const trashCard = !S.showTrash ? '' : `
        <div class="card mb">
          <div class="card-title">回收站（服务器保留 ${S.trash ? S.trash.days : 30} 天，按原路径还原）
            <div class="right">${btnSm('emptyTrash', '', '清空回收站', 'btn-danger')}</div>
          </div>
          <div id="fm-trash">${trashTable()}</div>
        </div>`;

        const hitsCard = !S.hits ? '' : `
        <div class="card mb">
          <div class="card-title">搜索 “${fmt.esc(S.query)}” · ${S.hits.length} 项
            <div class="right">${btnSm('clearHits', '', '清除')}</div>
          </div>
          ${ui.table([
            { k: 'path', label: '路径', cls: 'mono wrap' },
            { k: 'type', label: '类型' },
            { k: 'size', label: '大小', cls: 'mono', render: (v) => fmt.bytes(v) },
            { k: 'mtimeText', label: '修改时间', cls: 'mono' },
            { k: 'path', label: '', render: (v) => btnSm('openDir', parentOf(v), '打开所在目录') },
          ], S.hits.slice(0, 200), { style: 'max-height:360px' })}
        </div>`;

        const usageCard = !S.usage ? '' : `
        <div class="card mb">
          <div class="card-title">目录占用 · ${fmt.esc(S.usageFor)}</div>
          <div style="padding:0 16px 14px">${S.usage.slice(0, 20).map((u) => {
            const top = S.usage[0].kb || 1;
            return `<div style="display:flex;gap:10px;align-items:center;padding:2px 0">
              <span class="small mono" style="width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${fmt.esc(u.path)}">${fmt.esc(u.path.split('/').pop() || u.path)}</span>
              <div class="bar" style="flex:1"><div style="width:${Math.max(1, Math.round(u.kb / top * 100))}%"></div></div>
              <span class="small mono muted" style="width:84px;text-align:right">${fmt.bytes(u.kb * 1024)}</span>
            </div>`;
          }).join('')}</div>
        </div>`;

        root.innerHTML = `
        <div id="fm-transfers" class="card mb" style="display:none;padding:12px 16px"></div>

        <div class="card mb">
          <div class="card-title" style="display:flex;flex-wrap:wrap;gap:6px;align-items:center">
            <span style="margin-right:4px">位置</span> ${crumbs}
            <div class="right" style="display:flex;gap:6px;flex-wrap:wrap">
              ${l.parent ? btnSm('go', l.parent, '上级') : ''}
              ${btnSm('refresh', '', '刷新')}
              ${btnSm('toggleTrash', '', '回收站' + (S.trash && S.trash.items.length ? ' (' + S.trash.items.length + ')' : ''))}
              ${btnSm('usage', S.dir, '占用分析')}
            </div>
          </div>
          <div style="padding:0 16px 16px">
            <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
              <input class="inp mono" id="fm-goto" style="flex:1;min-width:260px" value="${fmt.esc(S.dir)}" placeholder="绝对路径，如 /var/www/html">
              <button class="btn" data-act="goto">${icon('external', { size: 13 })} 前往</button>
              <button class="btn btn-primary" data-act="mkdir">${icon('plus', { size: 13 })} 新建目录</button>
              <button class="btn" data-act="touch">${icon('plus', { size: 13 })} 新建文件</button>
              <button class="btn" data-act="upload">${icon('upload', { size: 13 })} 上传</button>
              <button class="btn btn-primary" data-act="doSearch">${icon('search', { size: 13 })} 搜索</button>
            </div>
            <div class="small muted" style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap;align-items:center">
              <span>搜索：</span>
              <input class="inp mono" id="fm-q" placeholder="按名称搜索（当前目录递归 4 层）" style="width:260px">
            </div>
            <div style="margin-top:10px;display:flex;gap:6px;flex-wrap:wrap;align-items:center">
              <span class="small muted">常用：</span>${bookmarks}
            </div>
            ${df}
          </div>
        </div>

        ${selBar}
        ${trashCard}
        ${hitsCard}
        ${usageCard}

        <div class="card">
          <div class="card-title">${fmt.esc(l.dir)} · ${l.count} 项
            <div class="right" style="display:flex;gap:6px">
              ${btnSm('selAll', '', '全选')}
              ${btnSm('selDirs', '', '只选目录')}
            </div>
          </div>
          ${table}
          <div class="small muted" style="padding:10px 16px">允许范围由服务器 CM_FILES_ROOTS 限定；所有写操作进面板审计日志。点击文件名即分类型预览：文本可直接编辑保存，图片/音视频/PDF 在线查看，压缩包可浏览内部条目并单独下载。</div>
        </div>`;

        drawTransfers();
        bindEvents();
      }

      function trashTable() {
        const items = (S.trash && S.trash.items) || [];
        if (!items.length) return '<div class="muted small" style="padding:4px 16px 12px">回收站是空的</div>';
        return ui.table([
          { k: 'name', label: '名称', cls: 'mono' },
          { k: 'original', label: '原位置', cls: 'mono wrap' },
          { k: 'size', label: '大小', cls: 'mono', render: (v) => fmt.bytes(v) },
          { k: 'at', label: '删除时间', cls: 'mono', render: (v) => new Date(v).toLocaleString('zh-CN', { hour12: false }) },
          { k: '', label: '操作', render: (v, it) => btnSm('restore', it.id, '还原') + ' ' + btnSm('purge', it.id, '彻底删除', 'btn-danger') },
        ], items.slice(0, 100), { style: 'max-height:340px' });
      }

      /* ---------------- actions ---------------- */
      async function act(a, path, ev) {
        switch (a) {
          case 'go': await load(path); return;
          case 'home': await load('/etc'); return;
          case 'refresh': await load(); return;
          case 'goto': {
            const v = document.getElementById('fm-goto').value.trim();
            if (v) await load(v);
            return;
          }
          case 'toggleTrash':
            S.showTrash = !S.showTrash;
            if (S.showTrash && !S.trash) await loadTrash();
            render();
            return;
          case 'usage': {
            try {
              const u = await CM.api.get('/ops/files/usage?dir=' + enc(path || S.dir), 30000);
              S.usage = u.items; S.usageFor = u.dir; render();
            } catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'clearHits': S.hits = null; render(); return;
          case 'openDir': S.hits = null; await load(path); return;
          case 'selAll': S.l.entries.forEach((e) => S.selected.add(e.path)); render(); return;
          case 'selDirs': S.l.entries.filter((e) => e.isDir).forEach((e) => S.selected.add(e.path)); render(); return;
          case 'clearSel': S.selected.clear(); render(); return;
          case 'sel':
            if (ev.target.checked) S.selected.add(path); else S.selected.delete(path);
            render();
            return;
          case 'doSearch': {
            const q = document.getElementById('fm-q').value.trim();
            if (!q) { toast('请输入搜索关键字', 'warn'); return; }
            try {
              const r = await CM.api.get('/ops/files/search?dir=' + enc(S.dir) + '&q=' + enc(q) + '&depth=4', 0);
              S.hits = r.items; S.query = q; render();
            } catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'mkdir': {
            const v = await modal.prompt('新建目录', [{ name: 'name', label: '目录名称' }], { okText: '创建' });
            if (!v || !v.name) return;
            try { await CM.api.post('/ops/files/mkdir', { dir: S.dir, name: v.name }); toast('目录已创建', 'ok'); await load(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'touch': {
            const v = await modal.prompt('新建空文件', [{ name: 'name', label: '文件名' }], { okText: '创建' });
            if (!v || !v.name) return;
            try { await CM.api.post('/ops/files/touch', { dir: S.dir, name: v.name }); toast('文件已创建，可点击「编辑」写入内容', 'ok'); await load(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'upload': {
            const inp = document.createElement('input');
            inp.type = 'file'; inp.multiple = true;
            inp.onchange = async () => {
              for (const f of inp.files) {
                let r = await uploadFile(S.dir, f, false);
                if (r.status === 409) {
                  if (await modal.confirm('同名文件已存在', `覆盖 <b class="mono">${fmt.esc(f.name)}</b>？`, { danger: true, okText: '覆盖' })) {
                    r = await uploadFile(S.dir, f, true);
                  }
                }
                if (r.ok) toast('已上传 ' + f.name, 'ok');
                else if (r.error) toast(f.name + '：' + r.error, 'err');
              }
              await load();
            };
            inp.click();
            return;
          }
          case 'download': {
            try {
              const n = await triggerDownload(CM.apiBase + '/ops/files/download?path=' + enc(path), baseName(path));
              toast('已保存 ' + baseName(path) + '（' + fmt.bytes(n) + '）', 'ok');
            } catch (e) { toast('下载失败：' + e.message, 'err'); }
            return;
          }
          case 'preview': await openPreview(path); return;
          case 'edit': await openEditor(path); return;
          case 'extract': {
            if (!await modal.confirm('解压压缩包', `把 <b class="mono">${fmt.esc(baseName(path))}</b> 解压到当前目录？（同名文件会被覆盖）`, { okText: '解压' })) return;
            try {
              await CM.api.post('/ops/files/extract', { path, to: S.dir });
              toast('已解压到 ' + S.dir, 'ok'); await load();
            } catch (e) { toast('解压失败：' + e.message, 'err'); }
            return;
          }
          case 'rename': {
            const name = baseName(path);
            const v = await modal.prompt('重命名', [{ name: 'name', label: '新名称', value: name }], { okText: '确定' });
            if (!v || !v.name || v.name === name) return;
            try { await CM.api.post('/ops/files/rename', { path, name: v.name }); toast('已重命名', 'ok'); await load(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'delOne': {
            const days = S.trash ? S.trash.days : 30;
            if (!await modal.confirm('移入回收站', `<b class="mono">${fmt.esc(baseName(path))}</b> 将进入回收站（保留 ${days} 天，可还原）。`, { danger: true, okText: '移入回收站' })) return;
            try { await CM.api.post('/ops/files/delete', { paths: [path] }); toast('已移入回收站', 'ok'); S.trash = null; await loadTrash(); await load(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'del': {
            if (!S.selected.size) return;
            const days = S.trash ? S.trash.days : 30;
            if (!await modal.confirm('移入回收站', `把所选 ${S.selected.size} 项移入回收站（保留 ${days} 天，可还原）？`, { danger: true, okText: '移入回收站' })) return;
            try { await CM.api.post('/ops/files/delete', { paths: [...S.selected] }); toast('已移入回收站', 'ok'); S.selected.clear(); await loadTrash(); await load(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'restore': {
            try { await CM.api.post('/ops/files/restore', { ids: [path] }); toast('已还原到原位置', 'ok'); await loadTrash(); await load(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'purge': {
            if (!await modal.confirm('彻底删除', '该条目将无法恢复，确定？', { danger: true, okText: '彻底删除' })) return;
            try { await CM.api.post('/ops/files/trash/delete', { ids: [path] }); await loadTrash(); render(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'emptyTrash': {
            const n = S.trash ? S.trash.items.length : 0;
            if (!await modal.confirm('清空回收站', `彻底删除 ${n} 项，无法恢复！`, { danger: true, okText: '清空' })) return;
            try { await CM.api.post('/ops/files/trash/empty', {}); await loadTrash(); render(); toast('回收站已清空', 'ok'); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'move':
          case 'copy': {
            const v = await modal.prompt(a === 'move' ? '移动到' : '复制到',
              [{ name: 'to', label: '目标目录（绝对路径）', value: S.dir }]);
            if (!v || !v.to) return;
            try {
              await CM.api.post('/ops/files/' + a, { paths: [...S.selected], to: v.to });
              toast(a === 'move' ? '已移动' : '已复制', 'ok');
              S.selected.clear(); await load();
            } catch (e) { toast(e.message, 'err'); }
            return;
          }
          case 'archTgz':
          case 'archZip': {
            const kind = a === 'archZip' ? 'zip' : 'tgz';
            const def = 'archive-' + new Date().toISOString().slice(0, 10).replace(/-/g, '') + (kind === 'zip' ? '.zip' : '.tar.gz');
            const v = await modal.prompt('打包 ' + (kind === 'zip' ? 'zip' : 'tar.gz'), [{ name: 'name', label: '归档文件名（保存在当前目录）', value: def }], { okText: '开始打包' });
            if (!v || !v.name) return;
            try {
              await CM.api.post('/ops/files/archive', { name: v.name, paths: [...S.selected], to: S.dir, kind });
              toast('打包完成：' + v.name, 'ok'); S.selected.clear(); await load();
            } catch (e) { toast('打包失败：' + e.message, 'err'); }
            return;
          }
          case 'chmod':
          case 'chown': {
            const isMode = a === 'chmod';
            const v = await modal.prompt(isMode ? '修改权限' : '修改属主', [
              { name: 'val', label: isMode ? '八进制权限，如 644 / 755' : 'user 或 user:group', value: isMode ? '644' : 'www-data:www-data' },
              { name: 'rec', label: '作用范围', type: 'select', options: ['仅所选', '递归子项'], value: '仅所选' },
            ], { okText: '应用' });
            if (!v || !v.val) return;
            const body = { paths: [...S.selected], recursive: v.rec === '递归子项' };
            body[isMode ? 'mode' : 'owner'] = v.val;
            try { await CM.api.post('/ops/files/' + a, body); toast('已更新', 'ok'); await load(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
        }
      }

      /* ---------------- typed preview ---------------- */
      function previewMask(title, bodyHtml, opts) {
        opts = opts || {};
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.innerHTML = `<div class="modal" style="width:${opts.width || '880px'};max-width:94vw">
          <div class="modal-head" style="display:flex;align-items:center;gap:10px;justify-content:space-between">
            <div style="min-width:0"><span>${fmt.esc(title)}</span><span class="muted small" data-pvsub style="margin-left:10px">${opts.sub || ''}</span></div>
            <div style="display:flex;gap:6px;flex-shrink:0">${opts.actions || ''}
              <button class="btn btn-sm" data-a="close">${icon('close', { size: 12 })} 关闭</button></div>
          </div>
          <div class="modal-body" data-pvbody style="max-height:76vh;overflow:auto">${bodyHtml}</div>
        </div>`;
        document.getElementById('modal-root').appendChild(mask);
        mask.querySelector('[data-a=close]').onclick = () => closePreview(mask);
        mask.addEventListener('click', (e) => { if (e.target === mask) closePreview(mask); });
        return mask;
      }
      function closePreview(mask) {
        mask.querySelectorAll('[data-blob]').forEach((el) => URL.revokeObjectURL(el.dataset.blob));
        const m = mask.querySelector('.modal'); if (m) m.classList.add('closing');
        mask.classList.add('closing');
        setTimeout(() => mask.remove(), 190);
      }
      const failBody = (e) => `<div class="small" style="color:#cf3b3b">预览失败：${fmt.esc(e.message)}</div>`;

      async function openPreview(path) {
        const name = baseName(path);
        const mask = previewMask('预览 · ' + name, '<div class="muted small">加载中…</div>');
        try {
          const p = await CM.api.get('/ops/files/preview?path=' + enc(path), 0);
          mask.querySelector('[data-pvsub]').textContent =
            `${fmt.bytes(p.size)} · ${new Date(p.mtimeMs).toLocaleString('zh-CN', { hour12: false })} · ${p.mime || 'application/octet-stream'}`;
          const body = mask.querySelector('[data-pvbody]');
          const kind = p.kind || kindOf(name);

          if (kind === 'archive') { renderArchiveViewer(body, mask, path); return; }
          if (kind === 'image') {
            try {
              const url = await authedBlob(CM.apiBase + '/ops/files/raw?path=' + enc(path));
              body.innerHTML = `<div style="text-align:center;background:#0b1118;border-radius:8px;padding:12px">
                <img data-blob="${url}" src="${url}" style="max-width:100%;max-height:64vh;border-radius:6px" alt="${fmt.esc(name)}"></div>`;
            } catch (e) { body.innerHTML = failBody(e); }
            return;
          }
          if (kind === 'video' || kind === 'audio') {
            try {
              const url = await authedBlob(CM.apiBase + '/ops/files/raw?path=' + enc(path) + '&maxMB=200');
              const tag = kind === 'video'
                ? `<video data-blob="${url}" src="${url}" controls style="max-width:100%;max-height:62vh"></video>`
                : `<audio data-blob="${url}" src="${url}" controls style="width:100%"></audio>`;
              body.innerHTML = `<div style="text-align:center;background:#0b1118;border-radius:8px;padding:12px">${tag}</div>
                <div class="small muted" style="margin-top:8px">浏览器不支持该编码时，请下载后用本地播放器打开。</div>`;
            } catch (e) { body.innerHTML = failBody(e); }
            return;
          }
          if (kind === 'pdf') {
            try {
              const url = await authedBlob(CM.apiBase + '/ops/files/raw?path=' + enc(path) + '&maxMB=200');
              body.innerHTML = `<embed data-blob="${url}" src="${url}" type="application/pdf" style="width:100%;height:64vh;border:0;border-radius:8px;background:#0b1118">`;
            } catch (e) { body.innerHTML = failBody(e); }
            return;
          }
          /* text / other */
          if (p.tooLarge) {
            body.innerHTML = `<div class="muted small">文件 ${fmt.bytes(p.size)} 超过预览上限 ${fmt.bytes(p.limit || 2097152)}，请下载后查看。</div>
              <div style="margin-top:10px">${btnSm('download', path, '下载文件', 'btn-primary')}</div>`;
            bindActs(body);
            return;
          }
          if (p.binary) {
            body.innerHTML = `<div class="muted small">二进制文件（${fmt.bytes(p.size)}），不做文本预览，可下载后用本地程序打开。</div>
              <div style="margin-top:10px">${btnSm('download', path, '下载文件', 'btn-primary')}</div>`;
            bindActs(body);
            return;
          }
          body.innerHTML = `
            <textarea class="inp mono" data-pvtext spellcheck="false"
              style="width:100%;min-height:56vh;font-size:12.5px;line-height:1.55;white-space:pre;overflow:auto"
              ${p.truncated ? 'readonly' : ''}>${fmt.esc(p.text || '')}</textarea>
            <div class="small muted" style="margin-top:8px">
              ${p.lines} 行 · 扩展名 ${fmt.esc(p.ext || '无')}${p.truncated
                ? ` · <span style="color:#c79209">仅预览前 64KB（共 ${fmt.bytes(p.size)}），在线编辑会丢失未预览部分，请用「编辑」外的终端方式处理大文件</span>`
                : ' · 可直接修改后保存'}</div>`;
          bindActs(body);
        } catch (e) {
          mask.querySelector('[data-pvbody]').innerHTML = failBody(e);
        }
      }

      /* ---------------- archive inner viewer ---------------- */
      async function renderArchiveViewer(body, mask, archivePath, prefix) {
        prefix = prefix || '';
        body.innerHTML = '<div class="muted small">读取压缩包清单…</div>';
        let list;
        try { list = await CM.api.get('/ops/files/archive/list?path=' + enc(archivePath), 0); }
        catch (e) { body.innerHTML = failBody(e); return; }

        const dirs = new Map(), files = [];
        for (const it of list.entries) {
          let n = it.name;
          if (n.startsWith('./')) n = n.slice(2);
          if (prefix && !n.startsWith(prefix)) continue;
          let rest = n.slice(prefix.length);
          if (rest.startsWith('/')) rest = rest.slice(1);
          if (!rest) continue;
          const slash = rest.indexOf('/');
          if (slash >= 0) dirs.set(rest.slice(0, slash + 1), true);
          else files.push({ name: rest, size: it.size, mtimeMs: it.mtimeMs, isLink: it.isLink });
        }

        const segs = prefix ? prefix.replace(/\/$/, '').split('/') : [];
        const crumb = `<button class="btn btn-sm" data-sub="">${fmt.esc(baseName(archivePath))}</button>` +
          segs.map((s, i) => ` <span class="muted">/</span> <button class="btn btn-sm" data-sub="${fmt.esc(segs.slice(0, i + 1).join('/') + '/')}">${fmt.esc(s)}</button>`).join('');

        const dirRows = [...dirs.keys()].map((n) => ({ name: n.replace(/\/$/, ''), fullName: n, isDir: true, size: 0 }));
        const rowBtns = (entry) => {
          const full = entry.fullName || (prefix + entry.name);
          if (entry.isDir) return btnSm('sub', full.endsWith('/') ? full : full + '/', '进入');
          return btnSm('subPreview', full, '预览') + ' ' + btnSm('subDownload', full, '下载');
        };

        body.innerHTML = `
          <div style="display:flex;flex-wrap:wrap;gap:6px;align-items:center;margin-bottom:10px">
            <span class="small muted">压缩包内部：</span>${crumb}
            <div style="margin-left:auto;display:flex;gap:6px">
              <button class="btn btn-sm btn-primary" data-act="subExtract">${icon('box', { size: 12 })} 全部解压到所在目录</button>
            </div>
          </div>
          ${ui.table([
            { k: 'name', label: '名称', cls: 'mono', render: (v, e) => `${e.isDir ? iconColored('folder', 12, 'var(--accent)') : iconColored('logs', 12, 'var(--muted)')} ${fmt.esc(v)}${e.isLink ? ' <span class="muted small">链接</span>' : ''}` },
            { k: 'size', label: '大小', cls: 'mono', render: (v, e) => e.isDir ? '<span class="muted">—</span>' : fmt.bytes(v) },
            { k: '', label: '操作', render: (v, e) => rowBtns(e) },
          ], dirRows.concat(files),
            { style: 'max-height:52vh' })}
          <div class="small muted" style="margin-top:8px">共 ${list.count} 个条目${list.truncated ? '（仅显示前 2 万个）' : ''} · ${list.kind === 'zip' ? 'zip' : 'tar 系'}压缩包。内部条目直接从压缩包流式读出，不解包落盘。</div>`;

        body.querySelectorAll('[data-act=sub]').forEach((b) => { b.onclick = () => renderArchiveViewer(body, mask, archivePath, b.dataset.path); });
        body.querySelectorAll('[data-sub]').forEach((b) => { b.onclick = () => renderArchiveViewer(body, mask, archivePath, b.dataset.sub); });
        body.querySelectorAll('[data-act=subExtract]').forEach((b) => {
          b.onclick = async () => {
            if (!await modal.confirm('解压压缩包', `把 <b class="mono">${fmt.esc(baseName(archivePath))}</b> 全部解压到它所在目录？（同名文件会被覆盖）`, { okText: '解压' })) return;
            try {
              await CM.api.post('/ops/files/extract', { path: archivePath, to: parentOf(archivePath) });
              toast('已解压', 'ok'); closePreview(mask); await load();
            } catch (e) { toast('解压失败：' + e.message, 'err'); }
          };
        });
        body.querySelectorAll('[data-act=subPreview]').forEach((b) => {
          b.onclick = async () => {
            const entry = b.dataset.path;
            try {
              const p = await CM.api.get('/ops/files/archive/entry?path=' + enc(archivePath) + '&entry=' + enc(entry), 0);
              if (p.binary && kindOf(entry) === 'image') {
                const pv = await CM.api.get('/ops/files/archive/entry?path=' + enc(archivePath) + '&entry=' + enc(entry) + '&base64=1', 0);
                if (pv.base64) {
                  previewMask('压缩包内预览 · ' + entry,
                    `<div style="text-align:center;background:#0b1118;border-radius:8px;padding:12px"><img src="data:${p.mime || 'image/png'};base64,${pv.base64}" style="max-width:100%;max-height:60vh"></div>`);
                  return;
                }
              }
              if (p.binary) {
                const m2 = previewMask('压缩包内预览 · ' + entry, `<div class="muted small">二进制条目（${fmt.bytes(p.size)}），请下载后查看。</div>
                  <div style="margin-top:10px"><button class="btn btn-primary btn-sm" data-dlsub>${icon('download', { size: 13 })} 下载该条目</button></div>`);
                m2.querySelector('[data-dlsub]').onclick = () => subDownload(archivePath, entry);
                return;
              }
              previewMask('压缩包内预览 · ' + entry, `<pre class="pre" style="max-height:62vh">${fmt.esc(p.text || '')}</pre>
                ${p.truncated ? '<div class="small muted" style="margin-top:6px">仅预览前 64KB，完整内容请下载。</div>' : ''}`);
            } catch (e) { toast('条目预览失败：' + e.message, 'err'); }
          };
        });
        body.querySelectorAll('[data-act=subDownload]').forEach((b) => { b.onclick = () => subDownload(archivePath, b.dataset.path); });
      }

      async function subDownload(archivePath, entry) {
        try {
          const n = await triggerDownload(CM.apiBase + '/ops/files/archive/download?path=' + enc(archivePath) + '&entry=' + enc(entry), baseName(entry));
          toast('已保存（' + fmt.bytes(n) + '）', 'ok');
        } catch (e) { toast('下载失败：' + e.message, 'err'); }
      }

      /* ---------------- editor ---------------- */
      async function openEditor(path) {
        const name = baseName(path);
        const mask = previewMask('编辑 · ' + name, '<div class="muted small">读取中…</div>', {
          actions: '<button class="btn btn-sm btn-primary" data-act="saveEdit">' + icon('save', { size: 12 }) + ' 保存</button>',
        });
        try {
          const p = await CM.api.get('/ops/files/preview?path=' + enc(path), 0);
          const body = mask.querySelector('[data-pvbody]');
          if (p.binary || p.tooLarge) {
            body.innerHTML = `<div class="small" style="color:#c79209">${p.binary ? '二进制文件不能在线编辑，请下载后处理。' : '文件超过可编辑上限（' + fmt.bytes(p.limit || 2097152) + '），请用 Web 终端（vi/nano）或下载后编辑。'}</div>`;
            mask.querySelector('[data-act=saveEdit]').style.display = 'none';
            return;
          }
          if (p.truncated) {
            body.innerHTML = `<div class="small" style="color:#c79209">文件共 ${fmt.bytes(p.size)}，在线编辑上限 2MB 且预览只有前 64KB——为避免丢失内容已锁定编辑。请用 Web 终端（vi/nano）处理。</div>`;
            mask.querySelector('[data-act=saveEdit]').style.display = 'none';
            return;
          }
          body.innerHTML = `<textarea class="inp mono" data-editspell spellcheck="false"
            style="width:100%;min-height:58vh;font-size:12.5px;line-height:1.55;white-space:pre;overflow:auto">${fmt.esc(p.text || '')}</textarea>
            <div class="small muted" style="margin-top:8px">${p.lines} 行 · 保存为整文件覆盖写并写入审计日志，改动即生效。</div>`;
          mask.querySelector('[data-act=saveEdit]').onclick = async () => {
            const content = mask.querySelector('textarea').value;
            try {
              await CM.api.post('/ops/files/write', { path, content });
              toast('已保存 ' + name, 'ok');
              closePreview(mask);
              await load();
            } catch (e) { toast('保存失败：' + e.message, 'err'); }
          };
        } catch (e) {
          mask.querySelector('[data-pvbody]').innerHTML = failBody(e);
        }
      }

      /* ---------------- event binding ---------------- */
      function bindActs(scope) {
        (scope || root).querySelectorAll('[data-act]').forEach((b) => {
          if (b.dataset.bound) return;
          b.dataset.bound = '1';
          b.onclick = (ev) => {
            if (b.type !== 'checkbox') { ev.preventDefault(); ev.stopPropagation(); }
            act(b.dataset.act, b.dataset.path, ev);
          };
        });
      }

      function bindEvents() {
        bindActs();
        const goto = document.getElementById('fm-goto');
        if (goto) goto.onkeydown = (e) => { if (e.key === 'Enter') act('goto'); };
        const q = document.getElementById('fm-q');
        if (q) q.onkeydown = (e) => { if (e.key === 'Enter') act('doSearch'); };
      }

      await loadTrash();
      await load();
    },
  });
})();
