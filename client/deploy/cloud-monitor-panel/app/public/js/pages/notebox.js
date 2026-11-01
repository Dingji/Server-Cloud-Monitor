/* 笔记与待办 —— notebox 后端的面板原生前端
   ------------------------------------------------------------------
   notebox 自带的 SPA 用的是根绝对路径（42 处 /api/、/static/），前缀反代到
   /panel/notebox/ 会全部打错，所以这里不嵌 iframe：走面板自己的
   /panel/api/notebox/*（panel-server/notebox.js 代理），界面用面板的组件与主题。

   会话是两套：面板登录守这道门，notebox 账号决定谁能看到哪些笔记。
   notebox 回 401 只表示"没登录笔记服务"，core.js 已特判不会把面板会话一起清掉。 */
(function () {
  'use strict';

  const PRI = { 0: '低', 1: '中', 2: '高', 3: '紧急' };
  const PRI_TONE = { 0: 'gray', 1: 'info', 2: 'warn', 3: 'bad' };
  const REPEAT = { none: '不重复', daily: '每天', weekdays: '工作日', weekly: '每周', monthly: '每月' };
  /* modal.prompt 的 select 用同一个串当 value 和 label，所以显示中文、回读再映射回 key */
  const REPEAT_KEY = Object.fromEntries(Object.entries(REPEAT).map(([k, v]) => [v, k]));
  const PRI_OPTS = ['0 低', '1 中', '2 高', '3 紧急'];
  const COLORS = ['', '#4370f0', '#22c55e', '#eab308', '#ef4444', '#8b5cf6'];
  /* notebox 的 color 是十六进制串；弹窗 select 用中文当 value，回读再映射回去 */
  const COLOR_CN = { '': '默认', '#4370f0': '蓝', '#22c55e': '绿', '#eab308': '黄', '#ef4444': '红', '#8b5cf6': '紫' };
  const TABS = [['notes', '笔记'], ['todos', '待办'], ['trash', '回收站'], ['share', '分享'],
    ['transfer', '导入导出'], ['stats', '统计'], ['mail', '邮件通知'], ['settings', '设置'], ['users', '用户']];
  /* notebox 那边 /api/emails 与 /api/admin/users 是 @admin_required，
     非管理员点了只会拿到一句 403，索性页签也不出示。 */
  const ADMIN_TABS = ['mail', 'users'];
  const isAdmin = () => !!(S.session && S.session.role === 'admin');
  const visibleTabs = () => TABS.filter((t) => ADMIN_TABS.indexOf(t[0]) < 0 || isAdmin());

  const S = {
    tab: 'notes', poll: null, session: undefined,
    notes: [], todos: [], trash: [], folders: [], tfolders: [], tags: [], ttags: [],
    q: '', folder: '', tag: '', sort: 'updated', status: 'pending', scope: '',
    shareCode: '', settings: null,
    busy: false, err: '',
  };

  /* notebox 的列表接口返回裸数组，CM.api 原样给出 */
  async function nb(path, body, method) {
    try {
      return await CM.api.request(method || (body === undefined ? 'GET' : 'POST'), '/notebox' + path, body);
    } catch (e) {
      if (e && e.noteboxAuth) { S.session = null; render(); }
      else if (e && e.payload && e.payload.noteboxDown) { S.err = e.message; }
      throw e;
    }
  }

  /* ---------------------------------------------------- Markdown 渲染
   * notebox 自带的 md.js（escape-first，URL 走白名单）直接复用：
   * 面板里的笔记观感和 notebox 自己那边一致，也不必再写第二个渲染器。
   * 但它是"别人的代码往我们的 DOM 里灌 HTML"，所以过一遍结构性消毒：
   * 剥 script/style/object/embed/form/input/link/meta、所有 on* 事件属性、
   * javascript: 协议；iframe 只留渲染器自己生成的那两个视频域名。 */
  const MD_OK_IFRAME = /^https?:\/\/(www\.youtube-nocookie\.com\/embed\/|player\.bilibili\.com\/player\.html)/i;
  let mdLoading = null;
  function ensureMd() {
    if (window.md) return Promise.resolve(true);
    if (mdLoading) return mdLoading;
    mdLoading = new Promise((resolve) => {
      const s = document.createElement('script');
      s.src = CM.base + '/public/js/md.js' + (CM.BUILD ? '?v=' + CM.BUILD : '');
      s.onload = () => resolve(!!window.md);
      s.onerror = () => { mdLoading = null; resolve(false); };
      document.head.appendChild(s);
    });
    return mdLoading;
  }
  function ensureMdCss() {
    if (document.getElementById('nb-md-css')) return;
    const st = document.createElement('style');
    st.id = 'nb-md-css';
    st.textContent = [
      '.nb-md{font-size:13px;line-height:1.65;color:var(--text);word-break:break-word}',
      '.nb-md h1,.nb-md h2,.nb-md h3{margin:14px 0 6px;font-weight:600;line-height:1.3}',
      '.nb-md h1{font-size:17px}.nb-md h2{font-size:15.5px}.nb-md h3{font-size:14px}',
      '.nb-md p{margin:7px 0}.nb-md ul,.nb-md ol{margin:7px 0;padding-left:22px}',
      '.nb-md li{margin:2px 0}',
      '.nb-md code{font-family:var(--mono);font-size:12px;background:var(--inset);padding:1px 5px;border-radius:3px}',
      '.nb-md pre{background:var(--code-block,#0b1017);border:1px solid var(--border);border-radius:4px;padding:10px;overflow:auto}',
      '.nb-md pre code{background:none;padding:0}',
      '.nb-md blockquote{margin:8px 0;padding:4px 10px;border-left:3px solid var(--accent);color:var(--muted);background:var(--inset)}',
      '.nb-md table{border-collapse:collapse;margin:8px 0;font-size:12.5px}',
      '.nb-md th,.nb-md td{border:1px solid var(--border);padding:4px 8px}',
      '.nb-md img{max-width:100%;height:auto;border-radius:4px}',
      '.nb-md .embed{position:relative;padding-bottom:56.25%;height:0;overflow:hidden;margin:8px 0}',
      '.nb-md .embed iframe{position:absolute;left:0;top:0;width:100%;height:100%;border:0}',
      '.nb-md a{color:var(--accent)}',
      '.nb-md .task-item input{margin-right:6px}',
    ].join('\n');
    document.head.appendChild(st);
  }
  function sanitizeMd(html) {
    const tpl = document.createElement('template');
    tpl.innerHTML = html;
    /* 任务清单用的是 disabled 的 checkbox，别把它一起剥掉 */
    tpl.content.querySelectorAll('script,style,object,embed,form,link,meta,base,svg,math').forEach((el) => el.remove());
    tpl.content.querySelectorAll('input:not([type=checkbox])').forEach((el) => el.remove());
    tpl.content.querySelectorAll('a[href]').forEach((a) => {
      if (a.getAttribute('target') === '_blank') a.setAttribute('rel', 'noopener noreferrer');
    });
    tpl.content.querySelectorAll('*').forEach((el) => {
      Array.from(el.attributes).forEach((a) => {
        const n = a.name.toLowerCase();
        if (n.indexOf('on') === 0) el.removeAttribute(a.name);
        if ((n === 'href' || n === 'src' || n === 'xlink:href')
          && /^(javascript|vbscript|data:text\/html)/i.test(a.value.replace(/^\s+/, ''))) el.setAttribute(n, '#');
      });
      if (el.tagName === 'IFRAME' && !MD_OK_IFRAME.test(el.getAttribute('src') || '')) el.remove();
    });
    /* 笔记里的图片是 notebox 存的 /uploads/<名>，面板 origin 够不着：
       换成面板的代理路径，并带上 ?token=（img 请求没法加 Authorization 头）。 */
    tpl.content.querySelectorAll('img').forEach((im) => {
      const src = im.getAttribute('src') || '';
      const m = /^\/uploads\/([\w.\-]+)$/.exec(src);
      if (m) im.setAttribute('src', CM.apiBase + '/notebox/uploads/' + m[1]
        + '?token=' + encodeURIComponent(CM.token || ''));
    });
    return tpl.innerHTML;
  }
  /** 把 markdown 渲染进某个容器；渲染器没加载成功就退回纯文本（不白屏）。返回 Promise，调用方要在渲染完成后才能量锚点。 */
  function renderMd(el, text) {
    if (!el) return Promise.resolve(false);
    ensureMdCss();
    return ensureMd().then((ok) => {
      if (!ok || !window.md) { el.textContent = text || ''; return false; }
      try { el.innerHTML = sanitizeMd(window.md.render(text || '')); }
      catch (e) { el.textContent = text || ''; return false; }
      return true;
    });
  }

  /**
   * 源码规范化：丢掉空白与 Markdown 记号，同时记下每个保留字符的原位置。
   * 渲染后的块没法用「第几块对第几块」配 —— 一个源码块（标题下面直接跟列表、
   * 中间不空行）会渲染成好几个元素，按索引 zip 会一路越配越偏（实测双击第 5 段
   * 跳到第 9 段的位置）。只能拿渲染文本的开头回源码里找，找得到就是它。
   */
  const MARKDROP = /[\s#*_`>\[\]()|!~\-：:，,。.、]/g;
  const normChunk = (s, n) => String(s == null ? '' : s).replace(MARKDROP, '').slice(0, n || 20);
  function normSource(text) {
    const s = String(text || '');
    const chars = [], offs = [];
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      if (/[\s#*_`>\[\]()|!~\-：:，,。.、]/.test(c)) continue;
      chars.push(c); offs.push(i);
    }
    return { s: chars.join(''), offs };
  }

  registerPage({
    id: 'notebox',
    title: '笔记与待办',
    desc: 'notebox 后端的笔记、待办、文件夹与分享（面板登录 + notebox 账号两级会话）',
    async mount(root) {
      root.innerHTML = '<div id="nb-root"></div>';
      /* 笔记不是指标页：30 秒足够，且用 CM.autoRefresh 自带「弹窗打开/
         输入框聚焦时跳过」，避免刷新把人正在写的正文冲掉。 */
      S.poll = CM.autoRefresh(async () => {
        if (S.session === null) return;              // 停在登录卡上不空转
        await load(); render();
      }, { pageId: 'notebox', ms: () => 30000 });
      S.poll.start();
      await boot();
    },
    hide() { if (S.poll) S.poll.stop(); },
    show() { if (S.poll) S.poll.start(); if (S.session) { load().then(render).catch(() => {}); } },
  });

  const esc = (v) => fmt.esc(v == null ? '' : String(v));

  /**
   * notebox 服务端的时间列（created_at / updated_at / deleted_at / last_login…）
   * 是 **epoch 秒**，不是 ISO 串；直接 Date.parse("1792582872") 得到 NaN，
   * 于是页面上就露出一串数字。两种形态都要认，秒/毫秒按量级判。
   */
  function toMs(v) {
    if (v == null || v === '') return null;
    if (typeof v === 'number' || /^\d{9,13}$/.test(String(v).trim())) {
      const n = Number(String(v).trim());
      if (!Number.isFinite(n)) return null;
      return n < 1e11 ? n * 1000 : n;
    }
    const t = Date.parse(String(v).replace(' ', 'T'));
    return Number.isFinite(t) ? t : null;
  }
  const fmtDateTime = (v) => {
    const ms = toMs(v);
    if (ms == null) return String(v || '—');
    const d = new Date(ms);
    const p = (x) => String(x).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
      + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  };
  const ago = (iso) => {
    const t = toMs(iso);
    if (t == null) return iso ? esc(iso) : '—';
    const m = Math.round((Date.now() - t) / 60000);
    if (m < 1) return '刚刚';
    if (m < 60) return m + ' 分钟前';
    if (m < 1440) return Math.round(m / 60) + ' 小时前';
    if (m < 43200) return Math.round(m / 1440) + ' 天前';
    return fmtDateTime(iso);
  };
  const dayOf = (n) => {
    const t = n.due_at ? Number(n.due_at) * 1000 : Date.parse((n.due_date || '') + 'T00:00:00');
    return Number.isFinite(t) && t > 0 ? new Date(t) : null;
  };
  const isOverdue = (t) => t.status !== 'done' && !!dayOf(t) && dayOf(t).getTime() < Date.now();

  /**
   * 卡片摘要：把 Markdown 记号先抹平再截断。
   * 直接 slice 原文会让列表里满屏的 `#`、`**`、`| --- |`，扫一眼看不出这篇讲什么。
   */
  function mdPreview(raw, max) {
    const cap = max || 170;
    const s = String(raw || '')
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/`([^`]*)`/g, '$1')
      .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/^\s{0,3}#{1,6}\s*/gm, '')
      .replace(/^\s{0,3}>\s?/gm, '')
      .replace(/^\s*[-*+]\s+\[[ xX]\]\s*/gm, '')
      .replace(/^\s*[-*+]\s+/gm, '')
      .replace(/^\s*\d+\.\s+/gm, '')
      .replace(/^\s*\|[-\s|:]+\|\s*$/gm, ' ')
      .replace(/\*\*|__|~~/g, '')
      .replace(/[*_]/g, '')
      .replace(/\s+/g, ' ')
      .trim();
    return s.length > cap ? s.slice(0, cap) + '…' : s;
  }

  async function boot() {
    S.err = '';
    try {
      const b = await nb('/bootstrap');
      S.session = b || {};
      await load();
    } catch (e) {
      if (e && e.noteboxAuth) S.session = null;
      else { S.session = S.session || null; S.err = e.message || String(e); }
    }
    render();
  }

  /* notebox 的 /api/todos 只认 status=pending|done（其它值等于不过滤），
     「已逾期」是我们在前端挑出来的。 */
  async function load() {
    S.err = '';                       // 一次成功就把「后端不可达」清掉，别一直挂着
    const folderQ = S.folder ? '&folder=' + encodeURIComponent(S.folder) : '';
    const [notes, todos, folders, tfolders, tags, ttags] = await Promise.all([
      nb('/notes?sort=' + encodeURIComponent(S.sort) + (S.q ? '&q=' + encodeURIComponent(S.q) : '')
        + folderQ + (S.tag ? '&tag=' + encodeURIComponent(S.tag) : '')
        + (S.scope ? '&scope=' + encodeURIComponent(S.scope) : '')),
      nb('/todos?status=' + encodeURIComponent(S.status) + (S.q ? '&q=' + encodeURIComponent(S.q) : '')
        + folderQ + (S.tag ? '&tag=' + encodeURIComponent(S.tag) : '')),
      nb('/folders?kind=note'),
      nb('/folders?kind=todo'),
      nb('/tags'),
      nb('/todos/tags').catch(() => []),
    ]);
    S.notes = Array.isArray(notes) ? notes : [];
    S.todos = (Array.isArray(todos) ? todos : []).filter((t) => S.status !== 'overdue' || isOverdue(t));
    S.folders = Array.isArray(folders) ? folders : [];
    S.tfolders = Array.isArray(tfolders) ? tfolders : [];
    S.tags = Array.isArray(tags) ? tags : [];
    S.ttags = Array.isArray(ttags) ? ttags : [];
  }

  async function loadTrash() {
    const t = await nb('/notes?trash=1');
    S.trash = Array.isArray(t) ? t : [];
  }

  /* ---------------------------------------------------------------- 渲染 */

  function render() {
    const root = document.getElementById('nb-root');
    if (!root) return;
    if (S.session === undefined) { root.innerHTML = '<div class="card">' + ui.skeleton(5) + '</div>'; return; }
    if (S.session === null) { root.innerHTML = loginCard(); wireLogin(); return; }

    const u = S.session.user || '', role = S.session.role || 'user';
    const st = S.session.stats || {};
    root.innerHTML =
      '<div class="card mb" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">'
      + '<b>笔记与待办</b>'
      + '<span class="chip">' + esc(String(u || '?')) + (role === 'admin' ? ' · 管理员' : '') + '</span>'
      + '<span class="chip">笔记 ' + (st.notes != null ? st.notes : S.notes.length) + '</span>'
      + '<span class="chip">待办 ' + (st.pending != null ? st.pending : '–') + ' 未完成</span>'
      + (Number(st.overdue) > 0 ? '<span class="badge bad">逾期 ' + st.overdue + '</span>' : '')
      + (Number(st.today) > 0 ? '<span class="badge info">今日到期 ' + st.today + '</span>' : '')
      + '<span style="flex:1"></span>'
      + '<button class="btn btn-sm" id="nb-newfolder">' + icon('folder', { size: 12 }) + ' 归类管理</button>'
      + '<button class="btn btn-sm" id="nb-reload">' + icon('refresh', { size: 12 }) + ' 刷新</button>'
      + '<button class="btn btn-sm" id="nb-logout">退出笔记</button>'
      + '</div>'
      + '<div class="range-btns" id="nb-tabs" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px">'
      + visibleTabs().map(([k, l]) => '<button class="btn btn-sm ' + (S.tab === k ? 'active btn-primary' : '') + '" data-t="' + k + '">' + l + '</button>').join('')
      + '</div>'
      + '<div id="nb-body"></div>';

    /* 笔记与待办的文件夹/标签是两套 id 空间，切页签必须清掉筛选 */
    root.querySelectorAll('[data-t]').forEach((b) => (b.onclick = () => {
      S.tab = b.dataset.t; S.folder = ''; S.tag = ''; render();
    }));
    root.querySelector('#nb-reload').onclick = () => { CM.api.invalidate('/notebox/'); boot(); };
    root.querySelector('#nb-logout').onclick = async () => {
      try { await nb('/logout', {}); } catch (_) {}
      S.session = null; render();
    };
    root.querySelector('#nb-newfolder').onclick = () => folderManager(S.tab === 'todos' ? 'todo' : 'note');
    if (S.err) {
      const b = root.querySelector('#nb-body');
      b.innerHTML = '<div class="card"><div class="badge bad">后端不可达</div><div class="muted small mt">' + esc(S.err) + '</div></div>';
      return;
    }
    if (ADMIN_TABS.indexOf(S.tab) >= 0 && !isAdmin()) S.tab = 'notes';
    if (S.tab === 'notes') renderNotes();
    else if (S.tab === 'todos') renderTodos();
    else if (S.tab === 'trash') renderTrash();
    else if (S.tab === 'transfer') renderTransfer();
    else if (S.tab === 'share') renderShare();
    else if (S.tab === 'settings') renderSettings();
    else if (S.tab === 'mail') renderMail();
    else if (S.tab === 'users') renderUsers();
    else renderStats();
  }

  function toolbar(kind) {
    const tags = kind === 'note' ? S.tags : S.ttags;
    const folders = kind === 'note' ? S.folders : S.tfolders;
    return '<div class="row mb" style="gap:8px;flex-wrap:wrap">'
      + '<input class="inp" id="nb-q" placeholder="搜索标题与正文…" style="width:220px" value="' + esc(S.q) + '">'
      + '<select class="inp" id="nb-folder" style="width:170px">'
      + '<option value="">全部文件夹</option><option value="none"' + (S.folder === 'none' ? ' selected' : '') + '>未归类</option>'
      + folders.map((f) => '<option value="' + f.id + '"' + (String(S.folder) === String(f.id) ? ' selected' : '') + '>'
        + esc(f.path || f.name) + ' (' + (f.total_count != null ? f.total_count : (f.count || 0)) + ')</option>').join('')
      + '</select>'
      + (kind === 'note'
        ? '<select class="inp" id="nb-scope" style="width:130px">'
          + [['', '全部来源'], ['mine', '我写的'], ['shared', '共享给我的']].map(([v, l]) =>
            '<option value="' + v + '"' + (S.scope === v ? ' selected' : '') + '>' + l + '</option>').join('')
          + '</select>' : '')
      + '<select class="inp" id="nb-tag" style="width:150px"><option value="">全部标签</option>'
      + tags.map((t) => '<option value="' + esc(t.name || t) + '"' + (S.tag === (t.name || t) ? ' selected' : '') + '>'
        + esc(t.name || t) + '</option>').join('') + '</select>'
      + (kind === 'note'
        ? '<select class="inp" id="nb-sort" style="width:130px">'
          + [['updated', '按更新'], ['created', '按创建'], ['title', '按标题']].map(([v, l]) =>
            '<option value="' + v + '"' + (S.sort === v ? ' selected' : '') + '>' + l + '</option>').join('') + '</select>'
        : '<select class="inp" id="nb-status" style="width:130px">'
          + [['pending', '未完成'], ['done', '已完成'], ['overdue', '已逾期'], ['all', '全部']].map(([v, l]) =>
            '<option value="' + v + '"' + (S.status === v ? ' selected' : '') + '>' + l + '</option>').join('') + '</select>')
      + '<span style="flex:1"></span>'
      + '<button class="btn btn-sm btn-primary" id="nb-add">' + icon('plus', { size: 12 }) + ' '
      + (kind === 'note' ? '新建笔记' : '添加待办') + '</button>'
      + '</div>';
  }

  function wireToolbar(kind) {
    const q = document.getElementById('nb-q');
    let t = null;
    if (q) q.oninput = () => { clearTimeout(t); t = setTimeout(() => { S.q = q.value; reload(kind); }, 350); };
    const f = document.getElementById('nb-folder');
    if (f) f.onchange = () => { S.folder = f.value; reload(kind); };
    const sc = document.getElementById('nb-scope');
    if (sc) sc.onchange = () => { S.scope = sc.value; reload(kind); };
    const tg = document.getElementById('nb-tag');
    if (tg) tg.onchange = () => { S.tag = tg.value; reload(kind); };
    const so = document.getElementById('nb-sort');
    if (so) so.onchange = () => { S.sort = so.value; reload(kind); };
    const ss = document.getElementById('nb-status');
    if (ss) ss.onchange = () => { S.status = ss.value; reload(kind); };
    const add = document.getElementById('nb-add');
    if (add) add.onclick = () => (kind === 'note' ? editNote(null) : editTodo(null));
  }

  function reload(kind) {
    const box = document.getElementById('nb-body');
    if (box) box.innerHTML = '<div class="card">' + ui.skeleton(4) + '</div>';
    load().then(render).catch((e) => { S.err = e.message; render(); });
    void kind;
  }

  /* ---------------------------- 笔记 ---------------------------- */

  function renderNotes() { renderToolbarInto('note'); S.notes.forEach((n) => noteCard(n)); }

  function renderToolbarInto(kind) {
    const b = document.getElementById('nb-body');
    b.innerHTML = toolbar(kind) + '<div id="nb-list" class="grid" style="gap:10px"></div>';
    wireToolbar(kind);
  }

  /* notebox 的权限分三档：owner / admin / edit（共享可编辑）。
     归类、颜色、置顶、删除在服务端只对 owner（或管理员）开放，
     所以按钮必须按同一规则 gating，否则点下去只会拿到一句 403。 */
  function notePerms(n) {
    const isOwner = !!n.is_owner || n.my_permission == null;
    const admin = !!(S.session && S.session.role === 'admin');
    return {
      canEdit: isOwner || admin || n.my_permission === 'edit',
      canOwn: isOwner || admin,
    };
  }

  /**
   * 阅读视图：markdown 渲染 + 「看原文」切换。
   * core.js 的 modal 只有 confirm/prompt/output（output 是转义后的 <pre>），
   * 所以要自己搭遮罩——但必须挂进 #modal-root，那里的自动刷新守卫按子元素数量
   * 判断"有弹窗就别重画"，挂在别处会让人一边读一遍被刷掉。
   */
  function readNote(n, perms) {
    const root = document.getElementById('modal-root');
    if (!root) return;
    const p = perms || notePerms(n);
    const mask = document.createElement('div');
    mask.className = 'modal-mask';
    mask.innerHTML = '<div class="modal" style="width:860px;max-width:94vw">'
      + '<div class="modal-head">' + esc(n.title || '（无标题）')
      + (n.pinned ? ' <span class="badge info">置顶</span>' : '')
      + (n.folder_path ? ' <span class="chip">' + esc(n.folder_path) + '</span>' : '')
      + '</div>'
      + '<div class="modal-body" style="max-height:64vh;overflow:auto"><div class="nb-md" data-md></div></div>'
      + '<div class="modal-foot">'
      + '<button class="btn" data-a="raw">看原文</button>'
      + '<button class="btn" data-a="copy">复制全文</button>'
      + (p.canEdit ? '<button class="btn" data-a="edit">编辑</button>' : '')
      + '<button class="btn btn-primary" data-a="close">关闭</button>'
      + '</div></div>';
    root.appendChild(mask);
    const body = mask.querySelector('[data-md]');
    let raw = false;
    const paint = () => {
      if (raw) {
        body.style.whiteSpace = 'pre-wrap';
        body.style.fontFamily = 'var(--mono)';
        body.style.fontSize = '12px';
        body.textContent = String(n.content || '（空）');
      } else {
        body.style.whiteSpace = '';
        body.style.fontFamily = '';
        body.style.fontSize = '';
        renderMd(body, String(n.content || ''));
      }
    };
    paint();
    const close = () => mask.remove();
    mask.querySelector('[data-a=raw]').onclick = () => {
      raw = !raw;
      mask.querySelector('[data-a=raw]').textContent = raw ? '看渲染' : '看原文';
      paint();
    };
    mask.querySelector('[data-a=copy]').onclick = async () => {
      if (await ui.copy(String(n.content || ''))) window.toast('已复制到剪贴板', 'ok');
      else window.toast('浏览器不让脚本复制，请用「看原文」选中后按 Ctrl+C', 'err');
    };
    const ed = mask.querySelector('[data-a=edit]');
    if (ed) ed.onclick = () => { close(); editNote(n); };
    mask.querySelector('[data-a=close]').onclick = close;
    mask.addEventListener('click', (e) => { if (e.target === mask) close(); });
  }

  function noteCard(n) {
    const host = document.getElementById('nb-list');
    const p = notePerms(n);
    const div = document.createElement('div');
    div.className = 'card';
    div.style.cssText = 'padding:11px 13px;border-left:3px solid '
      + (/^#[0-9a-fA-F]{6}$/.test(String(n.color || '')) ? n.color : 'var(--border)');
    div.innerHTML =
      '<div class="row" style="gap:8px;align-items:baseline;flex-wrap:wrap">'
      + (n.pinned ? '<span class="badge info">置顶</span>' : '')
      + '<b>' + esc(n.title || '（无标题）') + '</b>'
      + (n.is_owner ? '' : '<span class="chip">' + esc('共享 · ' + (n.my_permission === 'edit' ? '可编辑' : '只读')) + '</span>')
      + '<span style="flex:1"></span>'
      + '<span class="muted small" title="' + esc(fmtDateTime(n.updated_at || n.created_at)) + '">' + esc(ago(n.updated_at || n.created_at)) + '</span>'
      + '</div>'
      + '<div class="muted small mt" data-full style="max-height:44px;overflow:hidden;white-space:pre-wrap;cursor:pointer" title="点击阅读全文">'
      + esc(mdPreview(n.content)) + '</div>'
      + '<div class="row mt" style="gap:6px;flex-wrap:wrap">'
      + (n.folder_path || n.folder_name ? '<span class="chip">' + esc(n.folder_path || n.folder_name) + '</span>' : '')
      + (n.share_code ? '<span class="chip">已分享</span>' : '')
      + String(n.tags || '').split(',').filter(Boolean).map((t) => '<span class="chip">#' + esc(t.trim()) + '</span>').join('')
      + (n.owner_email ? '<span style="flex:1"></span><span class="muted small">' + esc(n.owner_email) + '</span>' : '')
      + '<button class="btn btn-sm" data-e' + (p.canEdit ? '' : ' disabled') + '>编辑</button>'
      + (p.canOwn ? '<button class="btn btn-sm" data-share>' + (n.share_code ? '重新分享' : '分享') + '</button>' : '')
      + (p.canOwn && n.share_code ? '<button class="btn btn-sm" data-unshare>取消分享</button>' : '')
      + '<button class="btn btn-sm" data-dup>副本</button>'
      + (p.canOwn ? '<button class="btn btn-sm btn-danger" data-trash>删除</button>' : '')
      + '</div>';
    const on = (sel, fn) => { const el = div.querySelector(sel); if (el) el.onclick = fn; };
    /* 双击卡片直接进编辑器：读→改是同一篇笔记上最常见的一跳 */
    if (p.canEdit) div.ondblclick = () => editNote(n);
    on('[data-full]', () => readNote(n, p));
    on('[data-e]', () => editNote(n));
    on('[data-share]', () => shareNote(n));
    on('[data-unshare]', () => unshareNote(n));
    on('[data-dup]', async () => { try { await nb('/notes/' + n.id + '/duplicate', {}); await load(); render(); } catch (e) { window.toast(e.message, 'err'); } });
    on('[data-trash]', async () => {
      if (!(await modal.confirm('移入回收站', '笔记「' + esc(n.title || '') + '」进入回收站，可随时恢复。', { danger: true, okText: '删除' }))) return;
      try { await nb('/notes/' + n.id + '/trash', {}); await load(); render(); } catch (e) { window.toast(e.message, 'err'); }
    });
    host.appendChild(div);
  }

  const folderOpt = (f) => f.id + ' ' + (f.path || f.name) + '（' + (f.total_count != null ? f.total_count : (f.count || 0)) + '）';
  const folderIdOf = (s) => { const m = /^(\d+)/.exec(String(s || '')); return m ? Number(m[1]) : null; };

  /* ---------------- 笔记编辑器 ----------------
   * 原来那串 modal.prompt 用不了：正文是一个 88px 高的小框，改一段要拖半天，
   * 没有预览、没有 Markdown 快捷键、关了就丢内容。这里换成整屏编辑器：
   * 标题写在头部、正文占满中间、元数据收在底栏，右侧实时渲染，编辑/分栏/预览三档，
   * 脏值有提示、关闭要确认、Ctrl+S 保存、Ctrl+B/I 加粗斜体、可插图（走 /notebox/assets）。
   */
  const ED_CSS = [
    '.nb-ed{width:min(1180px,96vw);height:88vh;max-height:88vh}',
    '.nb-ed .modal-head{display:flex;gap:10px;align-items:center;padding:6px 12px;border-bottom:1px solid var(--border)}',
    '.nb-ed-title{flex:1;min-width:0;background:transparent;border:0;outline:none;color:var(--text);font-size:15px;font-weight:600;padding:4px 0}',
    '.nb-ed-title::placeholder{color:#4d5a69}',
    '.nb-ed-dot{width:7px;height:7px;border-radius:50%;background:var(--muted);flex:0 0 7px}',
    '.nb-ed-dot.on{background:var(--warn)}',
    /* 工具栏/元数据条固定单行、窄了就横向滚：让它们换行会把正文压扁
       （实测 454px 宽的窗口里正文只剩 50px，编辑器根本没法用） */
    '.nb-ed-tool{display:flex;gap:4px;flex-wrap:wrap;align-items:center;padding:6px 10px;border-bottom:1px solid var(--border);background:var(--panel2);min-height:36px}',
    '.nb-ed-tool > *{flex:0 0 auto}',
    '.nb-ed-tool .sep{width:1px;height:18px;background:var(--border2);margin:0 4px}',
    '.nb-ed-tool .grow{flex:1}',
    /* 分栏用 flex 不用 grid：grid 的隐式行不拉伸，实测两栏一个 81px 一个 142px，
       都填不满 223px 的容器，正文缩成一条缝。flex 交叉轴的 stretch 才靠得住。 */
    /* 只有正文栏可以被压缩；标题/工具/元数据/页脚必须保住自然高度，
       否则窄窗口里它们换行后被 flex-shrink 挤扁，第二行直接叠到正文底下看不见。 */
    '.nb-ed .modal-head,.nb-ed-tool,.nb-ed-meta,.nb-ed-foot{flex:0 0 auto}',
    '.nb-ed-body{flex:1 1 auto;display:flex;min-height:min(180px,38vh);min-width:0}',
    '.nb-ed-meta{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:7px 12px;border-top:1px solid var(--border);background:var(--panel2);font-size:11.5px;color:var(--muted)}',
    '.nb-ed-meta > *{flex:0 0 auto}',
    '.nb-ed.mode-edit [data-pane=prev]{display:none}',
    '.nb-ed.mode-prev [data-pane=edit]{display:none}',
    '.nb-ed [data-pane=edit]{flex:1 1 50%;display:flex;min-width:0;min-height:0}',
    '.nb-ed textarea{flex:1 1 auto;width:100%;height:100%;min-height:0;border:0;outline:none;resize:none;background:var(--bg2);padding:14px 16px;font-family:var(--mono);font-size:13px;line-height:1.75;color:var(--text)}',
    '.nb-ed [data-pane=prev]{flex:1 1 50%;overflow:auto;padding:14px 18px;border-left:1px solid var(--border);background:var(--bg);min-width:0;min-height:0}',
    /* 量尺：和 textarea 同宽同字体，用来把「第几个字符」换成「距顶多少像素」。
       textarea 里软换行的行高不等于行号×行高，只能量不能算。 */
    '.nb-ed-mirror{position:fixed;left:-11999px;top:0;visibility:hidden;pointer-events:none;'
    + 'white-space:pre-wrap;word-wrap:break-word;overflow:visible;z-index:-1}',
    '.nb-ed-meta select,.nb-ed-meta input{background:var(--input,#0b1017);border:1px solid var(--border2);border-radius:var(--radius);color:var(--text);padding:3px 6px;font-size:11.5px;outline:none}',
    '.nb-sw{width:15px;height:15px;border-radius:50%;border:1px solid var(--border2);cursor:pointer;padding:0;line-height:0}',
    '.nb-sw.on{box-shadow:0 0 0 2px var(--accent)}',
    '.nb-tagbtn{border:1px solid var(--border2);background:var(--panel3);color:var(--muted);border-radius:10px;padding:1px 7px;font-size:11px;cursor:pointer}',
    '.nb-tagbtn:hover{color:var(--text);border-color:var(--accent)}',
    '.nb-ed-foot{padding:8px 14px;border-top:1px solid var(--border);display:flex;gap:8px;align-items:center;flex-wrap:wrap;background:var(--panel)}'
    + '.nb-ed-foot .nb-ed-info{flex:1 1 140px;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.nb-ed-foot .grow{flex:1 1 auto}',
    '.nb-ed-err{color:var(--bad);font-size:11.5px;max-width:46vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '@media (max-width:860px){.nb-ed{height:94vh;max-height:94vh;width:100vw;border-radius:0;border-left:0;border-right:0}'
    + '.nb-ed.mode-split .nb-ed-body{flex-direction:column}'
    + '.nb-ed [data-pane=prev]{border-left:0;border-top:1px solid var(--border)}.nb-ed textarea{font-size:15px}.nb-ed-err{max-width:40vw}}',
  ].join('\n');

  function ensureEdCss() {
    if (document.getElementById('nb-ed-css')) return;
    const st = document.createElement('style');
    st.id = 'nb-ed-css';
    st.textContent = ED_CSS;
    document.head.appendChild(st);
  }

  /** 归类按树序展开成带缩进的选项（notebox 的 list 是 position,name 序，不一定连着）。 */
  function folderOptions(kind) {
    const list = (kind === 'todo' ? S.tfolders : S.folders) || [];
    const kids = {};
    list.forEach((f) => {
      const k = f.parent_id || 0;
      (kids[k] = kids[k] || []).push(f);
    });
    Object.values(kids).forEach((a) => a.sort((x, y) => (x.position || 0) - (y.position || 0) || String(x.name).localeCompare(String(y.name))));
    const out = [];
    const walk = (pid, depth) => {
      (kids[pid] || []).forEach((f) => {
        if (depth > 8) return;
        const c = f.total_count != null ? f.total_count : (f.count || 0);
        out.push({ id: f.id, depth, label: f.name, count: c });
        walk(f.id, depth + 1);
      });
    };
    walk(0, 0);
    /* 挂在更深层级（父级没在列表里）的也兜出来，别让人找不到自己的归类 */
    list.forEach((f) => { if (!out.some((o) => String(o.id) === String(f.id))) out.push({ id: f.id, depth: 0, label: f.name, count: f.count || 0 }); });
    return out;
  }

  function editNote(n) {
    const p = n ? notePerms(n) : { canOwn: true, canEdit: true };
    const root = document.getElementById('modal-root');
    if (!root) return;
    ensureEdCss();

    const narrow = window.matchMedia('(max-width: 860px)').matches;
    const st = {
      id: n ? n.id : null,
      title: (n && n.title) || '',
      tags: (n && n.tags) || '',
      content: (n && n.content) || '',
      color: (n && n.color) || '',
      pinned: !!(n && n.pinned),
      folder_id: n ? (n.folder_id || null)
        : (/^\d+$/.test(String(S.folder)) ? Number(S.folder) : null),
      shareCode: (n && n.share_code) || '',
      snapshot: '', dirty: false, saving: false, auto: false,
      mode: narrow ? 'edit' : 'split',
    };
    const sig = () => JSON.stringify([st.title, st.tags, st.content, st.color, st.pinned, st.folder_id]);

    const mask = document.createElement('div');
    mask.className = 'modal-mask';
    const fopts = folderOptions('note');
    const esc2 = (s) => fmt.esc(String(s == null ? '' : s));
    mask.innerHTML = '<div class="modal nb-ed mode-' + st.mode + '" data-modal>'
      + '<div class="modal-head">'
      + '<span class="nb-ed-dot" data-dot title="未保存"></span>'
      + '<input class="nb-ed-title" data-title placeholder="笔记标题" value="' + esc2(st.title) + '">'
      + '<span class="badge muted" data-state>' + (n ? '编辑' : '新建') + '</span>'
      + '<button class="btn btn-sm btn-ghost" data-a="close" title="关闭 (Esc)">✕</button>'
      + '</div>'
      + '<div class="nb-ed-tool">'
      + '<button class="btn btn-sm" data-md="bold" title="加粗 Ctrl+B">B</button>'
      + '<button class="btn btn-sm" data-md="italic" title="斜体 Ctrl+I"><i>I</i></button>'
      + '<button class="btn btn-sm" data-md="strike" title="删除线"><s>S</s></button>'
      + '<button class="btn btn-sm" data-md="code" title="行内代码">&lt;/&gt;</button>'
      + '<span class="sep"></span>'
      + '<button class="btn btn-sm" data-md="h2" title="标题">H</button>'
      + '<button class="btn btn-sm" data-md="ul" title="无序列表">•</button>'
      + '<button class="btn btn-sm" data-md="ol" title="有序列表">1.</button>'
      + '<button class="btn btn-sm" data-md="task" title="任务清单">☑</button>'
      + '<button class="btn btn-sm" data-md="quote" title="引用">❝</button>'
      + '<button class="btn btn-sm" data-md="table" title="插入表格">▦</button>'
      + '<button class="btn btn-sm" data-md="hr" title="分隔线">—</button>'
      + '<span class="sep"></span>'
      + '<button class="btn btn-sm" data-md="link" title="链接 Ctrl+K">🔗</button>'
      + '<button class="btn btn-sm" data-md="image" title="图片链接">🖼</button>'
      + '<button class="btn btn-sm" data-a="upload" title="上传并插入图片">上传</button>'
      + '<input type="file" data-file accept="image/*,.pdf,.zip,.txt,.md" style="display:none">'
      + '<span class="grow"></span>'
      + '<button class="btn btn-sm" data-mode="edit">编辑</button>'
      + '<button class="btn btn-sm" data-mode="split">分栏</button>'
      + '<button class="btn btn-sm" data-mode="prev">预览</button>'
      + '<span class="sep"></span>'
      + '<label class="row" style="gap:5px"><input type="checkbox" data-sync checked><span class="small">滚动同步</span></label>'
      + '<label class="row" style="gap:5px"><input type="checkbox" data-auto><span class="small">自动保存</span></label>'
      + '</div>'
      + '<div class="nb-ed-body">'
      + '<div data-pane="edit"><textarea data-ta spellcheck="false" placeholder="支持 Markdown：# 标题、**粗体**、- 列表、- [ ] 任务、| 表格 |、``` 代码块 ```"></textarea></div>'
      + '<div data-pane="prev" title="双击某一段，光标会跳回正文对应位置"><div class="nb-md" data-prev></div></div>'
      + '</div>'
      + '<div class="nb-ed-meta">'
      + (p.canOwn
        ? '<span>归类</span><select data-folder><option value="">未归类</option>'
          + fopts.map((f) => '<option value="' + f.id + '"' + (String(st.folder_id) === String(f.id) ? ' selected' : '') + '>'
            + esc2('　'.repeat(f.depth) + f.label) + (f.count ? ' (' + f.count + ')' : '') + '</option>').join('')
          + '</select>'
        : '')
      + '<span>标记</span>' + COLORS.map((c) => '<button class="nb-sw" data-color="' + esc2(c) + '" title="' + esc2(COLOR_CN[c]) + '" style="background:' + (c || 'transparent') + '"></button>').join('')
      + (p.canOwn ? '<label class="row" style="gap:5px"><input type="checkbox" data-pin' + (st.pinned ? ' checked' : '') + '><span>置顶</span></label>' : '')
      + '<span>标签</span><input data-tags style="width:170px" placeholder="逗号分隔" value="' + esc2(st.tags) + '">'
      + (S.tags || []).slice(0, 8).map((t) => {
        const nm = (t && t.name) || t;
        return '<button class="nb-tagbtn" data-tag="' + esc2(nm) + '">#' + esc2(nm) + '</button>';
      }).join('')
      + '</div>'
      + '<div class="nb-ed-foot">'
      + '<span class="small muted nb-ed-info" data-info></span>'
      + '<span class="nb-ed-err" data-err></span>'
      + '<span class="grow"></span>'
      + (n && p.canOwn ? '<button class="btn btn-sm" data-a="dup">副本</button>'
        + '<button class="btn btn-sm" data-a="share">' + (st.shareCode ? '取消分享' : '分享') + '</button>' : '')
      + (n && p.canOwn ? '<button class="btn btn-sm btn-danger" data-a="trash">移入回收站</button>' : '')
      + '<button class="btn" data-a="close2">关闭</button>'
      + '<button class="btn btn-primary" data-a="save">保存 <span class="muted">Ctrl+S</span></button>'
      + '</div></div>';
    root.appendChild(mask);

    const q = (s) => mask.querySelector(s);
    const ta = q('[data-ta]'), prev = q('[data-prev]'), errEl = q('[data-err]'), info = q('[data-info]');
    /* 能滚的是外面那一栏，[data-prev] 只是它里面的内容容器：
       往 prev 上写 scrollTop 是静默无效操作，同步会假装工作。 */
    const pane = prev.parentElement;
    ta.value = st.content;

    /* ---------- 脏值 / 状态 ---------- */
    st.snapshot = sig();
    const paintState = () => {
      st.dirty = sig() !== st.snapshot;
      q('[data-dot]').className = 'nb-ed-dot' + (st.dirty ? ' on' : '');
      q('[data-state]').textContent = st.saving ? '保存中' : (st.dirty ? '未保存' : '已保存');
      const chars = ta.value.length;
      const lines = ta.value ? ta.value.split('\n').length : 0;
      info.textContent = chars + ' 字 · ' + lines + ' 行'
        + (n && n.updated_at ? ' · 上次更新 ' + fmtDateTime(n.updated_at) : '');
      q('[data-a=save]').disabled = st.saving || (!st.dirty && !!n);
    };
    const setErr = (m) => { errEl.textContent = m || ''; };

    /* ---------- 预览（防抖，编辑大笔记时不卡） ---------- */
    let pv = null;
    const schedulePreview = (follow) => {
      if (st.mode === 'edit') return;
      clearTimeout(pv);
      pv = setTimeout(() => {
        renderMd(prev, ta.value).then(() => { buildAnchors(); if (follow !== false) followCaret(); });
      }, 160);
    };

    /* ---------- 左右滚动同步 ----------
     * 按「源码块 ↔ 渲染块」建锚点，段内线性插值：
     * 纯比例同步在长代码块/大表格那里会一路漂，读到的是完全不对应的段落。
     * textarea 里软换行的行高度不等于「行号 × 行高」，所以源码侧的位置只能量，不能算。 */
    let mirror = null, anchors = [], elOff = [], lock = 0, syncOn = true;
    const lineHOf = () => parseFloat(window.getComputedStyle(ta).lineHeight) || 20;
    const padTopOf = () => parseFloat(window.getComputedStyle(ta).paddingTop) || 0;

    function mirrorEl() {
      if (!mirror) {
        mirror = document.createElement('div');
        mirror.className = 'nb-ed-mirror';
        document.body.appendChild(mirror);
      }
      const cs = window.getComputedStyle(ta);
      mirror.style.width = ta.clientWidth + 'px';
      mirror.style.fontFamily = cs.fontFamily;
      mirror.style.fontSize = cs.fontSize;
      mirror.style.lineHeight = cs.lineHeight;
      mirror.style.letterSpacing = cs.letterSpacing;
      mirror.style.wordBreak = cs.wordBreak;
      return mirror;
    }
    /** 某个字符位置在正文里距内容顶端的像素（落在该行的行首） */
    function srcPxAt(offset) {
      const text = ta.value.slice(0, Math.max(0, Math.min(offset, ta.value.length)));
      const m = mirrorEl();
      m.textContent = text;
      let h = m.getBoundingClientRect().height;
      if (text && text.charAt(text.length - 1) !== '\n') h -= lineHOf();
      return Math.max(0, padTopOf() + h);
    }
    /** 渲染块相对预览栏内容顶的位置（offsetTop 的基准不一定是预览栏） */
    function prevPxAt(el) {
      return el.getBoundingClientRect().top - pane.getBoundingClientRect().top + pane.scrollTop;
    }
    const clampTo = (v, box) => Math.max(0, Math.min(v, box.scrollHeight - box.clientHeight));

    function buildAnchors() {
      anchors = [];
      elOff = [];
      if (st.mode === 'edit' || !prev.isConnected) return;
      const kids = Array.from(prev.children);
      if (!kids.length) return;
      const text = ta.value;
      /* 超大正文每次重渲染都要 O(n) 规范化，不划算：退回端点线性同步 */
      if (text.length > 60000) {
        elOff = kids.map((el, i) => Math.round(text.length * (i / Math.max(1, kids.length - 1))));
        anchors = [{ src: 0, prev: 0, off: 0 }, { src: srcPxAt(text.length), prev: prevPxAt(kids[kids.length - 1]), off: text.length }];
        return;
      }
      const src = normSource(text);
      let cursor = 0, lastOff = 0;
      kids.forEach((el, i) => {
        const key = normChunk(el.textContent, 20);
        /* 光标单调前推，所以 2 个字的短标题也能安全地找下一个出现点 */
        let at = key.length >= 2 ? src.s.indexOf(key, cursor) : -1;
        if (at < 0 && key.length > 8) at = src.s.indexOf(key.slice(0, 8), cursor);
        const off = at >= 0 ? src.offs[at] : lastOff;   // 分隔线/视频嵌入这类没文本的元素沿用上一段
        if (at >= 0) { cursor = at + 1; lastOff = off; }
        elOff[i] = off;
        anchors.push({ src: srcPxAt(off), prev: prevPxAt(el), off });
      });
      /* 锚点必须单调不减：两段渲染结果配到同一段源码时，丢掉回头的 */
      anchors = anchors.filter((a, i) => i === 0 || a.src >= anchors[i - 1].src);
      if (!anchors.length) anchors.push({ src: 0, prev: 0, off: 0 });
      const endSrc = srcPxAt(text.length);
      const last = anchors[anchors.length - 1];
      if (endSrc > last.src) anchors.push({ src: endSrc, prev: pane.scrollHeight, off: text.length });
      if (anchors[0].src !== 0) anchors.unshift({ src: 0, prev: 0, off: anchors[0].off });
    }

    function mapPos(v, from, to) {
      if (anchors.length < 2) return v;
      const a = anchors[0][from], b = anchors[anchors.length - 1][from];
      if (b === a) return anchors[anchors.length - 1][to];
      if (v <= a) return anchors[0][to];
      for (let i = 1; i < anchors.length; i++) {
        if (v <= anchors[i][from]) {
          const p = anchors[i - 1], q = anchors[i];
          const span = Math.max(1, q[from] - p[from]);
          return p[to] + (v - p[from]) / span * (q[to] - p[to]);
        }
      }
      return anchors[anchors.length - 1][to];
    }
    /** 程序滚动时压掉对侧的 scroll 回调，否则两边互相触发 */
    const guarded = (fn) => { if (lock || !syncOn) return; lock = 1; try { fn(); } finally { requestAnimationFrame(() => { lock = 0; }); } };
    /* 刚打开时可能还没排版完（量出来全是 0），所以第一次真正滚动时补算一次 */
    const ensureAnchors = () => { if (anchors.length < 2) buildAnchors(); return anchors.length >= 2; };
    const taToPrev = () => { if (!ensureAnchors()) return; guarded(() => { pane.scrollTop = clampTo(mapPos(ta.scrollTop, 'src', 'prev'), pane); }); };
    const prevToTa = () => { if (!ensureAnchors()) return; guarded(() => { ta.scrollTop = clampTo(mapPos(pane.scrollTop, 'prev', 'src'), ta); }); };

    /** 光标跟随：打字/移动光标时，只有当对应段落跑到可视区外才把预览挪过去，避免一路抖 */
    function followCaret() {
      if (!syncOn || st.mode === 'edit' || !pane.isConnected) return;
      const target = mapPos(srcPxAt(ta.selectionStart || 0), 'src', 'prev');
      const top = pane.scrollTop, bot = top + pane.clientHeight;
      if (target < top + 6 || target > bot - Math.min(160, pane.clientHeight * 0.4)) {
        guarded(() => { pane.scrollTop = clampTo(target - 12, pane); });
      }
    }
    /** 反向跳：双击预览里的某一段，光标落到源码里对应那段的开头 */
    function jumpFromPreview(node) {
      let el = node;
      while (el && el.parentNode && el.parentNode !== prev) el = el.parentNode;
      if (!el || el.parentNode !== prev) return;
      const i = Array.prototype.indexOf.call(prev.children, el);
      if (i < 0 || elOff[i] == null) return;
      const off = elOff[i];
      ta.focus();
      ta.setSelectionRange(off, off);
      ta.scrollTop = clampTo(srcPxAt(off) - 24, ta);
      followCaret();
    }

    /* ---------- 保存 ---------- */
    async function save(quiet) {
      if (st.saving) return false;
      const title = st.title = q('[data-title]').value;
      const content = st.content = ta.value;
      st.tags = q('[data-tags]').value;
      if (p.canOwn) {
        const f = q('[data-folder]');
        st.folder_id = f && f.value ? Number(f.value) : null;
        st.pinned = !!(q('[data-pin]') && q('[data-pin]').checked);
      }
      if (!String(title).trim() && !String(content).trim()) {
        if (!quiet) setErr('标题和正文都是空的，没保存');
        return false;
      }
      const body = { title, content, tags: st.tags };
      if (p.canOwn) { body.folder_id = st.folder_id; body.color = st.color; body.pinned = st.pinned; }
      st.saving = true; paintState(); setErr('');
      try {
        const out = st.id ? await nb('/notes/' + st.id, body, 'PUT') : await nb('/notes', body, 'POST');
        if (out && out.id) st.id = out.id;
        if (out && out.share_code != null) st.shareCode = out.share_code;
        if (out && out.updated_at) n = Object.assign(n || {}, out);
        st.snapshot = sig();
        paintState();
        await load();                 // 让列表/归类计数立刻跟上
        if (!quiet) window.toast(st.id && n ? '已保存' : '笔记已创建', 'ok');
        return true;
      } catch (e) {
        setErr(e.message || String(e));
        if (!quiet) window.toast(e.message || '保存失败', 'err', 8000);
        return false;
      } finally {
        st.saving = false;
        paintState();
      }
    }

    /* ---------- 选区操作 ---------- */
    function applyMd(kind) {
      const s = ta.selectionStart, e = ta.selectionEnd, val = ta.value;
      const sel = val.slice(s, e);
      const lineStart = val.lastIndexOf('\n', s - 1) + 1;
      const put = (txt, back, af) => {
        ta.setRangeText(txt, s, e, 'end');
        if (back != null) { ta.selectionStart = s + back; ta.selectionEnd = s + back + (af || 0); }
        after();
      };
      const prefixLines = (make) => {
        const end = val.indexOf('\n', e - 1);
        const block = val.slice(lineStart, end < 0 ? val.length : end);
        const next = block.split('\n').map(make).join('\n');
        ta.setRangeText(next, lineStart, end < 0 ? val.length : end, 'end');
        after();
      };
      const wrap = (b, a, ph) => {
        const text = sel || ph;
        put(b + text + a, b.length, text.length);
      };
      switch (kind) {
        case 'bold': wrap('**', '**', '粗体'); break;
        case 'italic': wrap('*', '*', '斜体'); break;
        case 'strike': wrap('~~', '~~', '删除'); break;
        case 'code': sel.indexOf('\n') >= 0 ? put('```\n' + sel + '\n```') : wrap('`', '`', 'code'); break;
        case 'h2': prefixLines((l) => ('## ' + l.replace(/^#{1,6}\s*/, ''))); break;
        case 'ul': prefixLines((l) => ('- ' + l.replace(/^[-*]\s*/, '').replace(/^\d+\.\s*/, '').replace(/^\[[ x]\]\s*/, ''))); break;
        case 'ol': {
          let i = 0;
          prefixLines((l) => (++i + '. ' + l.replace(/^[-*]\s*/, '').replace(/^\d+\.\s*/, '').replace(/^\[[ x]\]\s*/, '')));
          break;
        }
        case 'task': prefixLines((l) => ('- [ ] ' + l.replace(/^[-*]\s*/, '').replace(/^\d+\.\s*/, '').replace(/^\[[ x]\]\s*/, ''))); break;
        case 'quote': prefixLines((l) => ('> ' + l.replace(/^>\s*/, ''))); break;
        case 'hr': put('\n\n---\n\n'); break;
        case 'table': put('\n| 列一 | 列二 |\n| --- | --- |\n|  |  |\n'); break;
        case 'link': put('[' + (sel || '链接文字') + '](https://)', 1, (sel || '链接文字').length); break;
        case 'image': put('![' + (sel || '说明') + '](/uploads/文件名.png)'); break;
        default: break;
      }
      function after() { ta.focus(); st.content = ta.value; paintState(); schedulePreview(); }
    }

    /* ---------- 图片上传：multipart 不能走 CM.api（它会 JSON 化），也加不了 FormData 的边界 ---------- */
    async function upload(file) {
      const btn = q('[data-a=upload]');
      btn.disabled = true;
      const old = btn.textContent;
      btn.textContent = '上传中…';
      try {
        const fd = new FormData();
        fd.append('file', file, file.name);
        const r = await fetch(CM.apiBase + '/notebox/assets', {
          method: 'POST',
          headers: { Authorization: 'Bearer ' + CM.token },
          body: fd,
        });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
        const url = j.url || ('/uploads/' + (j.name || ''));
        const s = ta.selectionStart;
        const ins = (s ? '\n\n' : '') + '![' + (j.name || file.name) + '](' + url + ')\n';
        ta.setRangeText(ins, ta.selectionStart, ta.selectionEnd, 'end');
        st.content = ta.value;
        paintState();
        schedulePreview();
        window.toast('图片已插入（' + fmt.bytes(j.size || file.size) + '）', 'ok');
      } catch (e) {
        setErr('上传失败：' + (e.message || e));
        window.toast(e.message || '上传失败', 'err', 8000);
      } finally {
        btn.disabled = false;
        btn.textContent = old;
      }
    }

    /* ---------- 视图模式 ---------- */
    function setMode(m) {
      st.mode = m;
      mask.querySelector('.nb-ed').className = 'modal nb-ed mode-' + m;
      mask.querySelectorAll('[data-mode]').forEach((b) => b.classList.toggle('btn-primary', b.dataset.mode === m));
      if (m === 'edit') { anchors = []; return; }
      renderMd(prev, ta.value).then(() => {
        buildAnchors();
        /* 等一帧再量一次：弹窗刚插入 DOM 时布局可能还没稳定 */
        requestAnimationFrame(() => { buildAnchors(); followCaret(); });
      });
    }

    /* ---------- 关闭（脏值要问一句） ---------- */
    let closed = false;
    async function close(force) {
      if (!force && sig() !== st.snapshot) {
        const go = await modal.confirm('放弃未保存的修改？',
          '这篇笔记有改动还没保存（' + ta.value.length + ' 字）。关闭后内容就丢了。',
          { danger: true, okText: '放弃并关闭' });
        if (!go) return;
      }
      if (closed) return;
      closed = true;
      document.removeEventListener('keydown', onKey, true);
      document.removeEventListener('selectionchange', onSelectionChange);
      window.removeEventListener('resize', onResize);
      if (mirror && mirror.parentNode) mirror.parentNode.removeChild(mirror);
      mirror = null;
      mask.remove();
      render();
    }

    /* ---------- 光标与尺寸变化 ---------- */
    function onSelectionChange() {
      if (closed || document.activeElement !== ta) return;
      followCaret();
    }
    let rz = null;
    function onResize() {
      clearTimeout(rz);
      rz = setTimeout(() => { buildAnchors(); }, 200);
    }

    /* ---------- 快捷键：只在编辑器还开着时挂，Esc/Ctrl+S 不能影响别处 ---------- */
    function onKey(ev) {
      if (closed || !mask.isConnected) return;
      const mod = ev.ctrlKey || ev.metaKey;
      if (mod && (ev.key === 's' || ev.key === 'S')) { ev.preventDefault(); save(false); return; }
      if (mod && ev.shiftKey && (ev.key === 'S')) { ev.preventDefault(); save(true); return; }
      if (mod && (ev.key === 'b' || ev.key === 'B') && ev.target === ta) { ev.preventDefault(); applyMd('bold'); return; }
      if (mod && (ev.key === 'i' || ev.key === 'I') && ev.target === ta) { ev.preventDefault(); applyMd('italic'); return; }
      if (mod && (ev.key === 'k' || ev.key === 'K') && ev.target === ta) { ev.preventDefault(); applyMd('link'); return; }
      if (ev.key === 'Escape') {
        /* 上面还压着别的弹窗（确认框/粘贴框）时别抢这一下 */
        const mr = document.getElementById('modal-root');
        if (mr && mr.childElementCount > 1) return;
        ev.preventDefault(); ev.stopPropagation(); close(false);
      }
    }
    document.addEventListener('keydown', onKey, true);

    /* ---------- 绑定 ---------- */
    q('[data-title]').oninput = () => { st.title = q('[data-title]').value; paintState(); };
    ta.oninput = () => {
      st.content = ta.value;
      paintState();
      schedulePreview();
      if (st.auto) queueAuto();
    };
    let autoT = null;
    function queueAuto() {
      clearTimeout(autoT);
      autoT = setTimeout(() => { if (st.auto && sig() !== st.snapshot) save(true); }, 2200);
    }
    q('[data-auto]').onchange = (e) => {
      st.auto = e.target.checked;
      if (st.auto && sig() !== st.snapshot) queueAuto();
      window.toast(st.auto ? '开启后停止输入约 2 秒自动保存' : '已关闭自动保存', 'info');
    };
    /* 滚动同步：源码侧滚 → 预览侧跟；预览侧滚 → 源码侧跟；光标动 → 预览走到对应段落 */
    ta.addEventListener('scroll', taToPrev, { passive: true });
    pane.addEventListener('scroll', prevToTa, { passive: true });
    prev.addEventListener('dblclick', (e) => jumpFromPreview(e.target));
    document.addEventListener('selectionchange', onSelectionChange);
    window.addEventListener('resize', onResize);
    ta.addEventListener('keyup', (e) => { if (e.key && e.key.indexOf('Arrow') === 0) followCaret(); });
    ta.addEventListener('click', followCaret);
    const syncBox = q('[data-sync]');
    if (syncBox) syncBox.onchange = (e) => {
      syncOn = e.target.checked;
      if (syncOn) { buildAnchors(); followCaret(); }
    };
    q('[data-tags]').oninput = () => { st.tags = q('[data-tags]').value; paintState(); };
    if (q('[data-folder]')) q('[data-folder]').onchange = () => paintState();
    if (q('[data-pin]')) q('[data-pin]').onchange = () => paintState();
    mask.querySelectorAll('[data-md]').forEach((b) => (b.onclick = () => applyMd(b.dataset.md)));
    mask.querySelectorAll('[data-mode]').forEach((b) => (b.onclick = () => setMode(b.dataset.mode)));
    mask.querySelectorAll('[data-color]').forEach((b) => {
      b.onclick = () => {
        st.color = st.color === b.dataset.color ? '' : b.dataset.color;
        mask.querySelectorAll('[data-color]').forEach((x) => x.classList.toggle('on', x.dataset.color === st.color));
        paintState();
      };
      b.classList.toggle('on', b.dataset.color === st.color);
    });
    mask.querySelectorAll('[data-tag]').forEach((b) => {
      b.onclick = () => {
        const inp = q('[data-tags]');
        const cur = inp.value.split(',').map((x) => x.trim()).filter(Boolean);
        if (cur.indexOf(b.dataset.tag) < 0) cur.push(b.dataset.tag);
        inp.value = cur.join(',');
        st.tags = inp.value;
        paintState();
      };
    });
    q('[data-a=upload]').onclick = () => q('[data-file]').click();
    q('[data-file]').onchange = (e) => {
      const f = e.target.files && e.target.files[0];
      e.target.value = '';
      if (f) upload(f);
    };
    q('[data-a=save]').onclick = () => save(false);
    mask.querySelectorAll('[data-a=close],[data-a=close2]').forEach((b) => (b.onclick = () => close(false)));
    const dup = q('[data-a=dup]');
    if (dup) dup.onclick = async () => {
      if (sig() !== st.snapshot && !(await save(true))) return;
      try { await nb('/notes/' + st.id + '/duplicate', {}); window.toast('已生成副本', 'ok'); await close(true); await load(); render(); }
      catch (e) { setErr(e.message); }
    };
    const sh = q('[data-a=share]');
    if (sh) sh.onclick = async () => {
      try {
        if (st.shareCode) {
          await nb('/notes/' + st.id + '/unshare', {}, 'POST');
          st.shareCode = '';
          sh.textContent = '分享';
          window.toast('已取消分享', 'ok');
        } else {
          if (sig() !== st.snapshot && !(await save(true))) return;
          const r = await nb('/notes/' + st.id + '/share', {}, 'POST');
          st.shareCode = (r && (r.share_code || r.code)) || '';
          sh.textContent = '取消分享';
          S.shareCode = st.shareCode;
          window.toast(st.shareCode ? '分享码 ' + st.shareCode + '（可在「分享」页预览）' : '已开启分享', 'ok');
        }
        await load();
      } catch (e) { setErr(e.message); }
    };
    const tr = q('[data-a=trash]');
    if (tr) tr.onclick = async () => {
      if (!(await modal.confirm('移入回收站', '笔记「' + fmt.esc(st.title || '') + '」进入回收站，可随时恢复。', { danger: true, okText: '删除' }))) return;
      try { await nb('/notes/' + st.id + '/trash', {}); window.toast('已移入回收站', 'ok'); await close(true); await load(); render(); }
      catch (e) { setErr(e.message); }
    };

    setMode(st.mode);
    paintState();
    q('[data-title]').focus();
    /* 老笔记从列表进来时正文可能很长：光标放开头，别停在结尾的换行上 */
    ta.setSelectionRange(0, 0);
  }

  async function shareNote(n) {
    try {
      const r = await nb('/notes/' + n.id + '/share', {}, 'POST');
      const code = (r && (r.share_code || r.code)) || n.share_code || '';
      if (!code) { window.toast('已开启分享', 'ok'); await load(); render(); return; }
      /* 与其只丢一句"分享码是 XXX"，不如直接把只读预览打开给人看效果 */
      S.shareCode = code;
      S.tab = 'share';
      await load();
      render();
      window.toast('分享码 ' + code + '，已在分享页预览', 'ok');
    } catch (e) { window.toast(e.message, 'err', 8000); }
  }

  async function unshareNote(n) {
    if (!(await modal.confirm('取消分享', '取消后外部链接立刻失效。', { danger: true, okText: '取消分享' }))) return;
    try { await nb('/notes/' + n.id + '/unshare', {}, 'POST'); window.toast('已取消分享', 'ok'); await load(); render(); }
    catch (e) { window.toast(e.message, 'err', 8000); }
  }

  /* ---------------------------- 待办 ---------------------------- */

  function renderTodos() {
    renderToolbarInto('todo');
    const host = document.getElementById('nb-list');
    if (!S.todos.length) { host.innerHTML = '<div class="card muted small">没有符合条件的待办。</div>'; return; }
    host.innerHTML = S.todos.map((t) => {
      const d = dayOf(t), overdue = isOverdue(t);
      const subs = Array.isArray(t.subtasks) ? t.subtasks : [];
      const doneSubs = t.subtasks_done != null ? t.subtasks_done : subs.filter((x) => x && x.done).length;
      const totalSubs = t.subtasks_total != null ? t.subtasks_total : subs.length;
      return '<div class="card" style="padding:10px 12px">'
        + '<div class="row" style="gap:9px;align-items:flex-start">'
        + '<input type="checkbox" data-tg="' + t.id + '"' + (t.status === 'done' ? ' checked' : '') + ' style="margin-top:3px">'
        + '<div style="flex:1;min-width:0">'
        + '<div class="' + (t.status === 'done' ? 'muted' : '') + '" style="' + (t.status === 'done' ? 'text-decoration:line-through' : '') + '">'
        + esc(t.title) + '</div>'
        + (t.detail ? '<div class="muted small">' + esc(String(t.detail).slice(0, 160)) + '</div>' : '')
        + '<div class="row" style="gap:6px;margin-top:5px;flex-wrap:wrap">'
        + '<span class="badge ' + (PRI_TONE[t.priority] || 'gray') + '">' + (PRI[t.priority] || '中') + '</span>'
        + (overdue ? '<span class="badge bad">已逾期</span>' : '')
        + (d ? '<span class="chip">截止 ' + esc(ymd(d)) + '</span>' : '')
        + (t.repeat && t.repeat !== 'none' ? '<span class="chip">重复 · ' + esc(t.repeat_label || REPEAT[t.repeat] || t.repeat) + '</span>' : '')
        + (totalSubs ? '<span class="chip">子任务 ' + doneSubs + '/' + totalSubs + '</span>' : '')
        + (t.folder_name ? '<span class="chip">' + esc(t.folder_name) + '</span>' : '')
        + String(t.tags || '').split(',').filter(Boolean).map((x) => '<span class="chip">#' + esc(x.trim()) + '</span>').join('')
        + '</div></div>'
        + '<button class="btn btn-sm" data-e="' + t.id + '">编辑</button>'
        + (t.status === 'done' ? '' : '<button class="btn btn-sm" data-notify="' + t.id + '">发提醒邮件</button>')
        + '<button class="btn btn-sm btn-danger" data-x="' + t.id + '">删除</button>'
        + '</div></div>';
    }).join('')
      + '<div class="row mt" style="gap:8px"><button class="btn btn-sm" id="nb-cleardone">清除已完成</button></div>';

    host.querySelectorAll('[data-tg]').forEach((c) => {
      c.onchange = async () => {
        try { const r = await nb('/todos/' + c.dataset.tg + '/toggle', {}, 'POST'); await load(); render(); void r; }
        catch (e) { window.toast(e.message, 'err'); await load(); render(); }
      };
    });
    host.querySelectorAll('[data-e]').forEach((b) => (b.onclick = () => editTodo(S.todos.find((x) => String(x.id) === b.dataset.e))));
    /* 手动发提醒：notebox 那边限流 6 次/10 分钟，且要求站点已配 SMTP */
    host.querySelectorAll('[data-notify]').forEach((b) => {
      b.onclick = async () => {
        b.disabled = true;
        try { await nb('/todos/' + b.dataset.notify + '/notify', {}, 'POST'); window.toast('提醒邮件已发出', 'ok'); }
        catch (e) { window.toast(e.message, 'err', 8000); }
        b.disabled = false;
      };
    });
    host.querySelectorAll('[data-x]').forEach((b) => {
      b.onclick = async () => {
        if (!(await modal.confirm('删除待办', '确认删除这条待办？', { danger: true, okText: '删除' }))) return;
        try { await nb('/todos/' + b.dataset.x, undefined, 'DELETE'); await load(); render(); } catch (e) { window.toast(e.message, 'err'); }
      };
    });
    const cd = document.getElementById('nb-cleardone');
    if (cd) cd.onclick = async () => {
      if (!(await modal.confirm('清除已完成', '删除所有已完成待办？该操作不可撤销。', { danger: true, okText: '清除' }))) return;
      try { const r = await nb('/todos/clear-done', {}, 'POST'); window.toast('已清除 ' + ((r && r.deleted) || 0) + ' 条', 'ok'); await load(); render(); }
      catch (e) { window.toast(e.message, 'err'); }
    };
  }

  /* 待办的 due_at 是秒级时间戳，用 toISOString 会按 UTC 切日（北京 +8 早一天），
     所以按本地日历取年月日。 */
  const ymd = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')
    + '-' + String(d.getDate()).padStart(2, '0');
  const subLine = (s) => ((s && s.done) ? '✓ ' : '') + ((s && (s.title || s.name)) || '');

  async function editTodo(t) {
    const d = t ? dayOf(t) : null;
    const opts = ['未归类'].concat(S.tfolders.map(folderOpt));
    const inList = S.tfolders.find((f) => String(f.id) === String(t ? t.folder_id : ''));
    const subsSrc = (t && Array.isArray(t.subtasks)) ? t.subtasks : [];
    const v = await modal.prompt(t ? '编辑待办' : '添加待办', [
      { name: 'title', label: '事项', value: (t && t.title) || '' },
      { name: 'detail', label: '说明（可选）', value: (t && t.detail) || '', type: 'textarea', rows: 3 },
      { name: 'due_date', label: '截止日期 YYYY-MM-DD（留空=无截止）',
        value: (t && t.due_date) || (d ? ymd(d) : '') },
      { name: 'priority', label: '优先级', type: 'select',
        value: PRI_OPTS[t && t.priority != null ? t.priority : 1], options: PRI_OPTS },
      { name: 'repeat', label: '重复（需先有截止日期）', type: 'select',
        value: REPEAT[(t && t.repeat) || 'none'], options: Object.values(REPEAT) },
      { name: 'folder_id', label: '文件夹', type: 'select',
        value: inList ? folderOpt(inList) : '未归类', options: opts },
      { name: 'tags', label: '标签（逗号分隔）', value: (t && t.tags) || '' },
      { name: 'subtasks', label: '子任务（每行一条，行首 ✓ 表示已完成）',
        value: subsSrc.map(subLine).join('\n'), type: 'textarea', rows: 4 },
    ], { okText: '保存', width: '620px' });
    if (!v || !String(v.title || '').trim()) return;
    const pri = Math.max(0, Math.min(3, parseInt(v.priority, 10) || 0));
    const subs = String(v.subtasks || '').split('\n').map((x) => x.trim()).filter(Boolean)
      .map((x) => (x[0] === '✓' ? { title: x.slice(1).trim(), done: true } : { title: x, done: false }));
    const due = String(v.due_date || '').trim();
    const repeat = REPEAT_KEY[v.repeat] || 'none';
    /* 服务端会拒绝「重复但没有截止时间」，提前拦住比拿一句 400 清楚 */
    if (repeat !== 'none' && !due) { window.toast('重复事项需要设置截止日期', 'err'); return; }
    if (due && !/^\d{4}-\d{2}-\d{2}$/.test(due)) { window.toast('截止日期格式应为 YYYY-MM-DD', 'err'); return; }
    const body = {
      title: v.title, detail: v.detail || '', priority: pri,
      due_at: null, due_date: due || null,
      remind: !!(t ? t.remind : true), tags: v.tags || '', subtasks: subs,
      repeat: repeat, folder_id: v.folder_id === '未归类' ? null : folderIdOf(v.folder_id),
    };
    try {
      if (t) await nb('/todos/' + t.id, Object.assign({ status: t.status || 'pending' }, body), 'PUT');
      else await nb('/todos', body, 'POST');
      window.toast('已保存', 'ok');
      await load(); render();
    } catch (e) { window.toast(e.message, 'err', 8000); }
  }

  /* ---------------------------- 回收站 / 统计 ---------------------------- */

  async function renderTrash() {
    const b = document.getElementById('nb-body');
    b.innerHTML = '<div class="card">' + ui.skeleton(3) + '</div>';
    let rows = [];
    try { await loadTrash(); rows = S.trash; } catch (e) { b.innerHTML = '<div class="card muted small">' + esc(e.message) + '</div>'; return; }
    b.innerHTML = rows.length ? '<div class="grid" style="gap:10px">' + rows.map((n) =>
      '<div class="card" style="padding:10px 12px"><div class="row" style="gap:8px;align-items:center">'
      + '<b style="flex:1">' + esc(n.title || '（无标题）') + '</b>'
      + '<span class="muted small">删除于 ' + esc(ago(n.deleted_at)) + '</span>'
      + '<button class="btn btn-sm" data-r="' + n.id + '">恢复</button>'
      + '<button class="btn btn-sm btn-danger" data-p="' + n.id + '">彻底删除</button>'
      + '</div></div>').join('') + '</div>'
      : '<div class="card muted small">回收站是空的。</div>';
    b.querySelectorAll('[data-r]').forEach((x) => {
      x.onclick = async () => { try { await nb('/notes/' + x.dataset.r + '/restore', {}, 'POST'); await loadTrash(); render(); } catch (e) { window.toast(e.message, 'err'); } };
    });
    b.querySelectorAll('[data-p]').forEach((x) => {
      x.onclick = async () => {
        if (!(await modal.confirm('彻底删除', '不可恢复。确认？', { danger: true, okText: '彻底删除' }))) return;
        try { await nb('/notes/' + x.dataset.p, undefined, 'DELETE'); await loadTrash(); render(); } catch (e) { window.toast(e.message, 'err'); }
      };
    });
  }

  /* ---------------------------- 导入 / 导出 ---------------------------- */

  /* 面板 API 认 ?token=（auth.js 里 query.token 优先），导出又是浏览器直接下载，
     所以这里用链接而不是 fetch：notebox 的会话 cookie 路径本来就覆盖这个前缀。 */
  const dl = (sub, name) => '<a class="btn btn-sm" download="' + esc(name) + '" href="'
    + esc(CM.apiBase + '/notebox/' + sub + '?token=' + encodeURIComponent(CM.token || '')) + '">'
    + icon('download', { size: 12 }) + ' ' + esc(name) + '</a>';

  function renderTransfer() {
    const b = document.getElementById('nb-body');
    b.innerHTML = '<div class="card"><div class="card-title">导出</div>'
      + '<div class="row mt" style="gap:8px;flex-wrap:wrap">'
      + dl('export', 'notebox-backup.json')
      + dl('export/markdown', 'notebox-notes.md')
      + dl('export/csv', 'notebox-todos.csv')
      + '</div><div class="muted small mt">备份 JSON 是完整数据（笔记 + 待办），可以直接用它把数据搬到另一台 notebox。</div></div>'
      + '<div class="card mt"><div class="card-title">导入备份 JSON</div>'
      + '<div class="row mt" style="gap:8px;flex-wrap:wrap;align-items:center">'
      + '<input class="inp" type="file" id="nb-file" accept=".json,application/json" style="max-width:320px">'
      + '<select class="inp" id="nb-mode" style="width:190px">'
      + '<option value="merge">合并（保留现有）</option><option value="replace">覆盖（先删自己的）</option></select>'
      + '<button class="btn btn-sm btn-primary" id="nb-import">导入</button>'
      + '<span id="nb-impmsg" class="muted small"></span>'
      + '</div><div class="muted small mt">「覆盖」会删除当前 notebox 账号下的全部笔记与待办，再按文件内容重建。</div></div>';

    const msg = (t, bad) => {
      const el = document.getElementById('nb-impmsg');
      if (el) { el.textContent = t; el.className = 'small ' + (bad ? 'badge bad' : 'muted small'); }
    };
    document.getElementById('nb-import').onclick = async () => {
      const f = document.getElementById('nb-file').files && document.getElementById('nb-file').files[0];
      if (!f) { msg('先选择一个 JSON 文件', true); return; }
      const mode = document.getElementById('nb-mode').value;
      if (mode === 'replace'
        && !(await modal.confirm('覆盖导入', '当前账号的笔记与待办会先被全部删除，再用文件内容重建。确认继续？',
          { danger: true, okText: '覆盖导入' }))) return;
      msg('导入中…');
      try {
        const r = await nb('/import/backup', { mode, content: await f.text() }, 'POST');
        msg('已导入笔记 ' + ((r && r.notes) || 0) + ' 篇、待办 ' + ((r && r.todos) || 0) + ' 条');
        await load(); render();
      } catch (e) { msg(e.message || '导入失败', true); }
    };
  }

  /* ---------------------------- 分享 ---------------------------- */

  /* notebox 的只读分享是 GET /api/share/<码>（无会话也能读，但面板这道门仍然要登录）。
     notebox 自带的可视化分享页在 /s/<码>，它引用根绝对 /static/*，面板前缀反代不出来，
     所以这里用面板自己的渲染器显示同一份数据。 */
  function renderShare() {
    const b = document.getElementById('nb-body');
    b.innerHTML = '<div class="card"><div class="card-title">按分享码阅读</div>'
      + '<div class="row mt" style="gap:8px;flex-wrap:wrap">'
      + '<input class="inp" id="nb-share-code" placeholder="分享码（笔记「分享」按钮给出的那串）" style="width:260px" value="' + esc(S.shareCode) + '">'
      + '<button class="btn btn-sm btn-primary" id="nb-share-go">预览</button>'
      + '<span style="flex:1"></span>'
      + '<span class="muted small">分享是只读的，看不到作者与其余笔记</span>'
      + '</div>'
      + '<div class="muted small mt">notebox 自己的公开分享页 <span class="mono">/s/' + esc(S.shareCode || '分享码')
      + '</span> 走的是根绝对路径，没经面板反代；要给外部直接点开，需要在 nginx 单独加一条 location（属于对外暴露面，要不要开由你定）。</div>'
      + '</div>'
      + '<div id="nb-share-out" class="card mt" style="display:none"></div>';
    const go = async () => {
      const code = (document.getElementById('nb-share-code').value || '').trim();
      const out = document.getElementById('nb-share-out');
      if (!code) { window.toast('先填分享码', 'err'); return; }
      S.shareCode = code;
      out.style.display = '';
      out.innerHTML = '<div class="muted small">读取中…</div>';
      try {
        const n = await nb('/share/' + encodeURIComponent(code));
        out.innerHTML = '<div class="card-title">' + esc(n.title || '（无标题）') + '</div>'
          + '<div class="muted small mt">' + esc(String(n.tags || '')) + (n.shared_at ? ' · 更新于 ' + esc(n.shared_at) : '') + '</div>'
          + '<hr style="border:none;border-top:1px solid var(--border);margin:10px 0">'
          + '<div class="nb-md" data-share-md></div>';
        renderMd(out.querySelector('[data-share-md]'), String(n.content || ''));
      } catch (e) {
        out.innerHTML = '<div class="badge bad">读取失败</div><div class="muted small mt">' + esc(e.message || String(e)) + '</div>';
      }
    };
    document.getElementById('nb-share-go').onclick = go;
    document.getElementById('nb-share-code').onkeydown = (e) => { if (e.key === 'Enter') go(); };
    if (S.shareCode) go();
  }

  /* ---------------------------- 文件夹管理 ---------------------------- */

  /* notebox 的归类是一棵树：改名 / 换上级 / 删除都在同一层语义里做，
     删除不毁数据 —— 子归类与条目都上移到被删归类的父级（服务端返回 items/children）。 */
  function folderManager(kind) {
    const root = document.getElementById('modal-root');
    if (!root) return;
    const list = kind === 'note' ? S.folders : S.tfolders;
    const label = kind === 'note' ? '笔记归类' : '待办归类';
    const mask = document.createElement('div');
    mask.className = 'modal-mask';
    const parentName = (pid) => {
      if (!pid) return '（顶层）';
      const p = list.find((f) => String(f.id) === String(pid));
      return p ? (p.name || String(p.id)) : '（顶层）';
    };
    mask.innerHTML = '<div class="modal" style="width:680px;max-width:94vw">'
      + '<div class="modal-head">' + esc(label) + ' <span class="chip">' + list.length + ' 个</span></div>'
      + '<div class="modal-body" style="max-height:56vh;overflow:auto">'
      + (list.length ? '<table class="tbl"><thead><tr><th>名称</th><th>上级</th><th>直属/含子</th><th></th></tr></thead><tbody>'
        + list.map((f) => '<tr><td>' + esc(f.name) + '</td>'
          + '<td class="muted">' + esc(parentName(f.parent_id)) + '</td>'
          + '<td class="num">' + esc(f.count != null ? f.count : 0) + ' / ' + esc(f.total_count != null ? f.total_count : 0) + '</td>'
          + '<td><button class="btn btn-sm" data-ren="' + f.id + '">改名</button> '
          + '<button class="btn btn-sm" data-mv="' + f.id + '">移动</button> '
          + '<button class="btn btn-sm btn-danger" data-del="' + f.id + '">删除</button></td></tr>').join('')
        + '</tbody></table>'
        : '<div class="muted small">还没有' + esc(label) + '。</div>')
      + '</div>'
      + '<div class="modal-foot"><button class="btn" data-a="new">新建</button>'
      + '<button class="btn btn-primary" data-a="close">关闭</button></div></div>';
    root.appendChild(mask);
    const close = () => mask.remove();
    const parentOptions = (exceptId) => ['（顶层）'].concat(
      list.filter((f) => String(f.id) !== String(exceptId)).map((f) => f.id + ' ' + f.name));
    const pickParent = async (exceptId, current) => {
      const opts = parentOptions(exceptId);
      const cur = opts.find((o) => String(o).indexOf(String(current) + ' ') === 0) || '（顶层）';
      const v = await modal.prompt('选择上级归类', [{ name: 'parent', label: label + '层级', type: 'select', value: cur, options: opts }], { okText: '确定' });
      if (!v) return undefined;
      return v.parent === '（顶层）' ? null : Number(String(v.parent).split(' ')[0]);
    };
    mask.querySelector('[data-a=close]').onclick = close;
    mask.querySelector('[data-a=new]').onclick = async () => {
      const v = await modal.prompt('新建' + label, [
        { name: 'name', label: '名称' },
        { name: 'parent', label: '上级归类（名称，留空=顶层）' },
      ], { okText: '创建' });
      if (!v || !String(v.name || '').trim()) return;
      const parent = (String(v.parent || '').trim());
      const hit = list.find((f) => f.name === parent);
      try {
        await nb('/folders', { name: v.name.trim(), kind, parent_id: hit ? hit.id : null }, 'POST');
        window.toast('归类已创建', 'ok');
        close(); await load(); render();
      } catch (e) { window.toast(e.message, 'err', 8000); }
    };
    mask.querySelectorAll('[data-ren]').forEach((btn) => {
      btn.onclick = async () => {
        const f = list.find((x) => String(x.id) === btn.dataset.ren);
        const v = await modal.prompt('改名', [{ name: 'name', label: '名称', value: f ? f.name : '' }], { okText: '保存' });
        if (!v || !String(v.name || '').trim()) return;
        try { await nb('/folders/' + f.id, { name: v.name.trim(), parent_id: f.parent_id || null }, 'PUT'); window.toast('已改名', 'ok'); close(); await load(); render(); }
        catch (e) { window.toast(e.message, 'err', 8000); }
      };
    });
    mask.querySelectorAll('[data-mv]').forEach((btn) => {
      btn.onclick = async () => {
        const f = list.find((x) => String(x.id) === btn.dataset.mv);
        const pid = await pickParent(f.id, f.parent_id);
        if (pid === undefined) return;
        try { await nb('/folders/' + f.id, { name: f.name, parent_id: pid }, 'PUT'); window.toast('已移动', 'ok'); close(); await load(); render(); }
        catch (e) { window.toast(e.message, 'err', 8000); }
      };
    });
    mask.querySelectorAll('[data-del]').forEach((btn) => {
      btn.onclick = async () => {
        const f = list.find((x) => String(x.id) === btn.dataset.del);
        if (!(await modal.confirm('删除归类',
          '删除「' + esc(f.name) + '」不会删掉里面的条目：条目与子归类都会上移到「' + esc(parentName(f.parent_id)) + '」。确认删除？',
          { danger: true, okText: '删除归类' }))) return;
        try {
          const r = await nb('/folders/' + f.id, undefined, 'DELETE');
          window.toast('已删除，上移条目 ' + ((r && r.items) || 0) + ' 个、子归类 ' + ((r && r.children) || 0) + ' 个', 'ok');
          close(); await load(); render();
        } catch (e) { window.toast(e.message, 'err', 8000); }
      };
    });
    mask.addEventListener('click', (e) => { if (e.target === mask) close(); });
  }

  function renderStats() {
    const st = (S.session && S.session.stats) || {};
    const b = document.getElementById('nb-body');
    const cell = (l, v, sub) => '<div class="card stat"><div class="stat-label">' + l + '</div>'
      + '<div class="stat-value" style="font-size:22px">' + esc(v == null ? '–' : v) + '</div>'
      + (sub ? '<div class="stat-sub">' + esc(sub) + '</div>' : '') + '</div>';
    /* 数字全部取自 bootstrap.stats（服务端算好的口径），前端不自己数 */
    b.innerHTML = '<div class="grid g4" style="gap:12px">'
      + cell('笔记', st.notes, '回收站 ' + S.trash.length + ' 篇')
      + cell('待办未完成', st.pending, '已完成 ' + (st.done != null ? st.done : '–'))
      + cell('今日到期', st.today, '逾期 ' + (st.overdue || 0) + ' 条')
      + cell('库大小', fmt.bytes(st.db_bytes), '邮件成功（今日） ' + (st.mail_ok_today != null ? st.mail_ok_today : '–'))
      + '</div>'
      + '<div class="card mt"><div class="card-title">账号</div><div class="muted small">'
      + 'notebox 用户：' + esc(String((S.session && S.session.user) || '–'))
      + ' · 角色 ' + esc((S.session && S.session.role) || 'user')
      + ' · 服务端时间 ' + esc((S.session && S.session.stats && S.session.stats.server_time) || '–')
      + (S.tags.length ? '<br>标签：' + esc(S.tags.map((t) => (t && t.name) || t).join('、')) : '')
      + (S.ttags.length ? '<br>待办标签：' + esc(S.ttags.map((t) => (t && t.name) || t).join('、')) : '')
      + '<br>面板只代理 API；用户管理、SMTP 通知、注册验证等仍需在 notebox 自己的界面或客户端「笔记设置」里完成。</div></div>';
  }

  /* ---------------------------- 设置 / 邮件 / 用户 ---------------------------- */

  /* notebox 的 GET /api/settings 会把 smtp_pass 原文带回来（它自己的 SPA 就预填）。
     这边故意不回显、不写进 DOM，保存时也只有用户真改了才提交这一项 —— 
     面板页面上随处可见的输入框容易被截图/录屏，口令没必要出现在那里。 */
  const SETTING_FIELDS = [
    ['smtp_host', 'SMTP 服务器', 'text'],
    ['smtp_port', '端口', 'number'],
    ['smtp_ssl', 'SSL/TLS（465 用隐式 TLS）', 'bool'],
    ['smtp_user', '账号', 'text'],
    ['smtp_pass', '授权码 / 密码', 'secret'],
    ['mail_from', '发件人', 'text'],
    ['mail_to', '收件人（逗号分隔）', 'text'],
    ['notify_enabled', '启用到期提醒邮件', 'bool'],
    ['remind_before_min', '提前多少分钟提醒', 'number'],
    ['digest_enabled', '启用每日摘要', 'bool'],
    ['digest_time', '摘要发送时间 HH:MM', 'text'],
    ['digest_tz', '时区', 'text'],
    ['base_url', '站点基址（邮件里的链接前缀）', 'text'],
  ];

  async function renderSettings() {
    const b = document.getElementById('nb-body');
    b.innerHTML = '<div class="card">' + ui.skeleton(4) + '</div>';
    let s;
    try { s = await nb('/settings'); } catch (e) { b.innerHTML = '<div class="card muted small">' + esc(e.message) + '</div>'; return; }
    S.settings = s || {};
    const ready = s.smtp_ready;
    const row = (key, label, kind) => {
      const v = s[key];
      if (kind === 'bool') {
        const on = v === '1' || v === 1 || v === true;
        return '<label class="row" style="gap:8px;align-items:center;margin:6px 0">'
          + '<input type="checkbox" data-set="' + key + '"' + (on ? ' checked' : '') + (isAdmin() ? '' : ' disabled') + '>'
          + '<span>' + esc(label) + '</span><span class="muted small">' + esc(on ? '开' : '关') + '</span></label>';
      }
      if (kind === 'secret') {
        return '<label class="fld" style="margin:6px 0">' + esc(label)
          + '<input class="inp" type="password" data-set="' + key + '" value="" autocomplete="new-password" '
          + 'placeholder="' + (v ? '已配置（留空=不修改）' : '未配置') + '"' + (isAdmin() ? '' : ' disabled') + '></label>';
      }
      return '<label class="fld" style="margin:6px 0">' + esc(label)
        + '<input class="inp" type="' + (kind === 'number' ? 'number' : 'text') + '" data-set="' + key + '" '
        + 'value="' + esc(v == null ? '' : v) + '"' + (isAdmin() ? '' : ' disabled') + '></label>';
    };
    b.innerHTML = '<div class="card mb" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">'
      + '<b>笔记服务设置</b>'
      + '<span class="chip">' + esc(String(s.username || (S.session && S.session.user) || '–')) + '</span>'
      + (ready ? '<span class="badge ok">SMTP 已配置</span>' : '<span class="badge warn">SMTP 未配置</span>')
      + (isAdmin() ? '' : '<span class="chip">只读（notebox 里只有管理员能改）</span>')
      + '<span style="flex:1"></span>'
      + (isAdmin() ? '<button class="btn btn-sm" id="nb-testmail">发测试邮件</button>' : '')
      + '<button class="btn btn-sm" id="nb-chpw">修改我的口令</button>'
      + (isAdmin() ? '<button class="btn btn-sm btn-primary" id="nb-saveset">保存设置</button>' : '')
      + '</div>'
      + '<div class="card"><div class="grid" style="gap:4px 14px">'
      + SETTING_FIELDS.map(([k, l, t]) => row(k, l, t)).join('')
      + '</div>'
      + '<div class="muted small mt">提醒/摘要按 notebox 自己的调度器发送；面板只读写这份配置，不代发。</div></div>';

    const save = document.getElementById('nb-saveset');
    if (save) save.onclick = async () => {
      const body = {};
      SETTING_FIELDS.forEach(([k, , kind]) => {
        const el = b.querySelector('[data-set="' + k + '"]');
        if (!el) return;
        if (kind === 'bool') body[k] = el.checked ? '1' : '0';
        else if (kind === 'secret') { if (el.value) body[k] = el.value; }   // 留空 = 不动它
        else body[k] = el.value;
      });
      try {
        const r = await nb('/settings', body, 'PUT');
        window.toast('设置已保存', 'ok');
        S.settings = r || S.settings;
        renderSettings();
      } catch (e) { window.toast(e.message, 'err', 8000); }
    };
    const tm = document.getElementById('nb-testmail');
    if (tm) tm.onclick = async () => {
      tm.disabled = true;
      try { const r = await nb('/settings/test-email', {}, 'POST'); window.toast((r && r.ok) ? '测试邮件已发出' : '发送失败', (r && r.ok) ? 'ok' : 'err'); }
      catch (e) { window.toast(e.message, 'err', 8000); }
      tm.disabled = false;
    };
    document.getElementById('nb-chpw').onclick = async () => {
      const v = await modal.prompt('修改 notebox 口令', [
        { name: 'old', label: '当前口令', placeholder: '' },
        { name: 'new', label: '新口令', placeholder: 'notebox 有自己的强度要求' },
      ], { okText: '修改' });
      if (!v || !v.new) return;
      try { await nb('/settings/password', { old: v.old || '', new: v.new }, 'POST'); window.toast('口令已修改', 'ok'); }
      catch (e) { window.toast(e.message, 'err', 8000); }
    };
  }

  const MAIL_HEAD = ['时间', '类型', '收件人', '主题', '状态', '详情'];
  async function renderMail() {
    const b = document.getElementById('nb-body');
    b.innerHTML = '<div class="card">' + ui.skeleton(4) + '</div>';
    let rows;
    try { rows = await nb('/emails'); } catch (e) { b.innerHTML = '<div class="card muted small">' + esc(e.message) + '</div>'; return; }
    rows = Array.isArray(rows) ? rows : [];
    b.innerHTML = '<div class="card"><div class="card-title">最近 ' + rows.length + ' 封信</div>'
      + '<div style="overflow:auto" class="mt"><table class="tbl"><thead><tr>'
      + MAIL_HEAD.map((h) => '<th>' + h + '</th>').join('')
      + '</tr></thead><tbody>'
      + (rows.length ? rows.map((m) => {
        const ok = m.status === 'ok';
        return '<tr><td class="mono">' + esc(rTmish(m.sent_at)) + '</td>'
          + '<td>' + esc(m.kind || '–') + '</td>'
          + '<td class="wrap">' + esc(m.to || '–') + '</td>'
          + '<td class="wrap">' + esc(m.subject || '–') + '</td>'
          + '<td><span class="badge ' + (ok ? 'ok' : 'bad') + '">' + esc(m.status || '–') + '</span></td>'
          + '<td class="wrap muted small">' + esc(String(m.error || '').slice(0, 160)) + '</td></tr>';
      }).join('') : '<tr><td colspan="6" class="muted">还没有发信记录</td></tr>')
      + '</tbody></table></div></div>';
  }

  /* email_log.sent_at 是 epoch 秒 */
  function rTmish(sec) {
    const n = Number(sec);
    if (!Number.isFinite(n) || n <= 0) return '–';
    const d = new Date(n * 1000);
    const p = (x) => String(x).padStart(2, '0');
    return (d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
  }

  async function renderUsers() {
    const b = document.getElementById('nb-body');
    b.innerHTML = '<div class="card">' + ui.skeleton(4) + '</div>';
    let rows;
    try { rows = await nb('/admin/users'); } catch (e) { b.innerHTML = '<div class="card muted small">' + esc(e.message) + '</div>'; return; }
    rows = Array.isArray(rows) ? rows : [];
    b.innerHTML = '<div class="card mb" style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">'
      + '<b>notebox 用户</b><span class="chip">' + rows.length + ' 个</span>'
      + '<span style="flex:1"></span><button class="btn btn-sm btn-primary" id="nb-newuser">新建用户</button></div>'
      + '<div class="card"><div style="overflow:auto"><table class="tbl"><thead><tr>'
      + ['邮箱', '角色', '状态', '笔记', '待办', '最近登录', '操作'].map((h) => '<th>' + h + '</th>').join('')
      + '</tr></thead><tbody>' + rows.map(userRow).join('') + '</tbody></table></div>'
      + '<div class="muted small mt">停用/删除不会影响别人的笔记；删除会连带清掉该用户的笔记与待办，notebox 服务端不允许删掉自己或最后一个启用的管理员。</div></div>';
    wireUsers(rows);
  }

  function userRow(u) {
    const me = !!(S.session && S.session.user && String(u.email) === String(S.session.user));
    const st = u.status || (u.active ? 'active' : 'pending');
    const tone = st === 'active' ? 'ok' : st === 'pending' ? 'warn' : 'bad';
    return '<tr><td class="wrap">' + esc(u.email) + (me ? ' <span class="chip">本人</span>' : '') + '</td>'
      + '<td>' + esc(u.role) + '</td>'
      + '<td><span class="badge ' + tone + '">' + esc(st) + '</span></td>'
      + '<td class="num">' + esc(u.notes) + '</td>'
      + '<td class="num">' + esc(u.todos) + '</td>'
      + '<td class="mono">' + esc(rTmish(u.last_login)) + '</td>'
      + '<td>'
      + '<button class="btn btn-sm" data-act="edit" data-id="' + u.id + '">编辑</button> '
      + '<button class="btn btn-sm" data-act="toggle" data-id="' + u.id + '" data-active="' + (u.active ? '1' : '0') + '">'
      + (u.active ? '停用' : '启用') + '</button> '
      + (st === 'pending' ? '<button class="btn btn-sm" data-act="resend" data-id="' + u.id + '">重发验证信</button> ' : '')
      + '<button class="btn btn-sm btn-danger" data-act="del" data-id="' + u.id + '" data-email="' + esc(u.email) + '">删除</button>'
      + '</td></tr>';
  }

  function wireUsers(rows) {
    const b = document.getElementById('nb-body');
    const byId = (id) => rows.find((r) => String(r.id) === String(id));
    document.getElementById('nb-newuser').onclick = async () => {
      const v = await modal.prompt('新建 notebox 用户', [
        { name: 'email', label: '邮箱' },
        { name: 'password', label: '初始口令' },
        { name: 'role', label: '角色', type: 'select', value: 'user', options: ['user', 'admin'] },
      ], { okText: '创建' });
      if (!v || !v.email) return;
      try { await nb('/admin/users', { email: v.email, password: v.password || '', role: v.role }, 'POST'); window.toast('用户已创建', 'ok'); renderUsers(); }
      catch (e) { window.toast(e.message, 'err', 8000); }
    };
    b.querySelectorAll('[data-act]').forEach((btn) => {
      btn.onclick = async () => {
        const id = btn.dataset.id, u = byId(id);
        if (!u) return;
        const act = btn.dataset.act;
        try {
          if (act === 'edit') {
            const v = await modal.prompt('编辑用户 ' + u.email, [
              { name: 'email', label: '邮箱', value: u.email },
              { name: 'role', label: '角色', type: 'select', value: u.role, options: ['user', 'admin'] },
              { name: 'active', label: '状态', type: 'select', value: u.active ? '启用' : '停用', options: ['启用', '停用'] },
              { name: 'password', label: '重置口令（留空=不改）' },
            ], { okText: '保存' });
            if (!v) return;
            const body = { email: v.email, role: v.role === 'admin' ? 'admin' : 'user', active: v.active === '启用' };
            if (v.password) body.password = v.password;
            await nb('/admin/users/' + id, body, 'PUT');
            window.toast('已保存', 'ok');
          } else if (act === 'toggle') {
            const on = btn.dataset.active === '1';
            await nb('/admin/users/' + id, { active: !on }, 'PUT');
            window.toast(on ? '已停用' : '已启用', 'ok');
          } else if (act === 'resend') {
            await nb('/admin/users/' + id + '/resend', {}, 'POST');
            window.toast('验证信已重发', 'ok');
          } else if (act === 'del') {
            if (!(await modal.confirm('删除用户',
              '删除 ' + esc(btn.dataset.email) + ' 会连带删除其全部笔记与待办，不可恢复。确认？',
              { danger: true, okText: '删除用户' }))) return;
            const r = await nb('/admin/users/' + id, undefined, 'DELETE');
            window.toast('已删除 ' + ((r && r.deleted) || ''), 'ok');
          }
          render();
        } catch (e) { window.toast(e.message, 'err', 8000); }
      };
    });
  }

  /* ---------------------------- 登录卡 / 文件夹 ---------------------------- */

  function loginCard() {
    return '<div class="card" style="max-width:420px;margin:24px auto;padding:20px">'
      + '<div class="card-title">登录笔记服务</div>'
      + (S.err ? '<div class="badge bad mb">' + esc(S.err) + '</div>' : '')
      + '<label class="fld mb">邮箱或用户名<input class="inp" id="nb-u" autocomplete="username"></label>'
      + '<label class="fld mb">密码<input class="inp" id="nb-p" type="password" autocomplete="current-password"></label>'
      + '<div class="row" style="gap:8px"><button class="btn btn-primary" id="nb-login">登录</button>'
      + '<button class="btn" id="nb-reload2">重试探测</button></div>'
      + '<div class="muted small mt">笔记服务（notebox）有独立的账号体系，与面板登录口令无关。</div></div>';
  }

  function wireLogin() {
    const go = async () => {
      const u = document.getElementById('nb-u').value.trim();
      const p = document.getElementById('nb-p').value;
      if (!u || !p) { window.toast('请填写账号与密码', 'err'); return; }
      try {
        await nb('/login', { email: u, password: p }, 'POST');
        S.err = '';
        await boot();
      } catch (e) { window.toast(e.message || '登录失败', 'err', 8000); }
    };
    const b = document.getElementById('nb-login');
    if (b) b.onclick = go;
    const r = document.getElementById('nb-reload2');
    if (r) r.onclick = () => { S.err = ''; boot(); };
    const p = document.getElementById('nb-p');
    if (p) p.onkeydown = (e) => { if (e.key === 'Enter') go(); };
  }
})();
