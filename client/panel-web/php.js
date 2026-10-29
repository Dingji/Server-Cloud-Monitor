/* PHP 站点：部署与管理（面板托管 nginx vhost + PHP-FPM 池 + MariaDB + 原子发布） */
(function () {
  'use strict';

  /* ---------- tiny form modal (core.js only ships confirm/prompt/output) ---------- */
  function form(title, html, opts) {
    opts = opts || {};

    return new Promise((resolve) => {
      const mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.innerHTML = `<div class="modal" style="width:${opts.width || '620px'}">
        <div class="modal-head">${fmt.esc(title)}</div>
        <div class="modal-body">${html}</div>
        <div class="modal-foot">
          <span class="muted small" style="margin-right:auto">${opts.hint || ''}</span>
          <button class="btn" data-a="cancel">取消</button>
          <button class="btn ${opts.danger ? 'btn-danger' : 'btn-primary'}" data-a="ok">${fmt.esc(opts.okText || '确认')}</button>
        </div></div>`;
      document.getElementById('modal-root').appendChild(mask);
      const close = (v) => {
        mask.querySelector('.modal').classList.add('closing');
        mask.classList.add('closing');
        setTimeout(() => { mask.remove(); resolve(v); }, 190);
      };
      mask.querySelector('[data-a=cancel]').onclick = () => close(null);
      mask.querySelector('[data-a=ok]').onclick = () => {
        if (typeof opts.beforeClose === 'function' && opts.beforeClose(mask) === false) return;
        const out = {};
        mask.querySelectorAll('[name]').forEach((el) => {
          if (el.type === 'checkbox') out[el.name] = el.checked;
          else if (el.dataset.multi) out[el.name] = el.value.split(/[\s,]+/).filter(Boolean);
          else out[el.name] = el.value;
        });
        close(out);
      };
      mask.addEventListener('click', (e) => { if (e.target === mask) close(null); });
      mask.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(null); });
      const first = mask.querySelector('[name]');
      if (first) setTimeout(() => first.focus(), 60);
      /* a <input type=file> dies with this modal, so hand the live node back
         to the caller before it is torn down */
      if (typeof opts.onOpen === 'function') opts.onOpen(mask);
    });
  }

  const fld = (name, label, value, ph) =>
    `<label class="fld mb">${fmt.esc(label)}<input class="inp mono" name="${name}" value="${fmt.esc(value == null ? '' : value)}" placeholder="${fmt.esc(ph || '')}"></label>`;
  const sel = (name, label, options, value) =>
    `<label class="fld mb">${fmt.esc(label)}<select class="inp" name="${name}">${
      options.map((o) => `<option value="${fmt.esc(o[0])}" ${String(o[0]) === String(value) ? 'selected' : ''}>${fmt.esc(o[1])}</option>`).join('')}</select></label>`;
  const area = (name, label, value, rows, ph) =>
    `<label class="fld mb">${fmt.esc(label)}<textarea class="inp mono" name="${name}" rows="${rows || 4}" placeholder="${fmt.esc(ph || '')}">${fmt.esc(value || '')}</textarea></label>`;
  const chk = (name, label, on) =>
    `<label class="fld mb" style="display:flex;gap:8px;align-items:center">
      <input type="checkbox" name="${name}" ${on ? 'checked' : ''} style="width:auto"> ${fmt.esc(label)}</label>`;
  const pair = (a, b) => `<div class="row" style="gap:10px;flex:1;min-width:0">${a}<div style="flex:1;min-width:0">${b}</div></div>`;

  /* ---------- job progress (self-contained; core.js's is wired to /pkg) ---------- */
  const jobModal = {
    current: null,
    run(startPromise, title) {
      return startPromise.then((r) => {
        const id = ((r || {}).job || {}).id;
        if (!id) { window.toast((r && r.output) || '已执行', 'ok'); return r; }
        return this.watch(id, title || ((r.job && r.job.title) || '任务'));
      });
    },
    watch(id, title) {
      return new Promise((resolve) => {
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.innerHTML = `<div class="modal" style="width:820px">
          <div class="modal-head"><span>${fmt.esc(title)}</span><span class="badge info" data-r="st">执行中</span></div>
          <div class="modal-body">
            <div class="row" style="gap:10px;align-items:center">
              <b data-r="pct" style="min-width:44px">0%</b>
              <div style="flex:1"><div class="bar"><div data-r="fill" style="width:0%"></div></div></div>
            </div>
            <div class="muted small mt" data-r="detail">排队中…</div>
            <pre class="pre tall mono mt" data-r="log" style="max-height:46vh;overflow:auto">（等待输出…）</pre>
          </div>
          <div class="modal-foot">
            <span class="muted small" style="margin-right:auto">关闭窗口不会中断任务</span>
            <button class="btn" data-a="hide">后台运行</button>
            <button class="btn btn-primary" data-a="done" disabled>完成</button>
          </div></div>`;
        document.getElementById('modal-root').appendChild(mask);
        const el = (k) => mask.querySelector('[data-r=' + k + ']');
        const prev = this.current;
        if (prev) this.stop(prev);
        const ctx = { mask: mask, timer: null, id: id, resolve: resolve };
        let scrolled = true;
        el('log').addEventListener('scroll', () => {
          const L = el('log');
          scrolled = L.scrollTop + L.clientHeight >= L.scrollHeight - 40;
        });
        const tick = async () => {
          let j;
          try { j = await CM.api.request('GET', '/php/job?id=' + encodeURIComponent(id)); }
          catch (e) { return; }
          if (!j || j.id !== id) return;
          el('pct').textContent = (j.pct || 0) + '%';
          el('fill').style.width = (j.pct || 0) + '%';
          el('detail').textContent = (j.phase || '') + (j.detail ? ' · ' + j.detail : '');
          const st = el('st');
          st.className = 'badge ' + (j.state === 'done' ? 'ok' : j.state === 'failed' ? 'bad' : 'info');
          st.textContent = j.state === 'done' ? '完成' : j.state === 'failed' ? '失败' : '执行中';
          const text = (j.log || []).join('\n') || '（暂无输出）';
          if (el('log').textContent !== text) {
            el('log').textContent = text;
            if (scrolled) el('log').scrollTop = el('log').scrollHeight;
          }
          if (!j.active) {
            this.stop(ctx);
            mask.querySelector('[data-a=done]').disabled = false;
            mask.querySelector('[data-a=hide]').textContent = '关闭';
            if (j.state === 'done') window.toast(j.title + ' 完成', 'ok');
            else window.toast('任务失败：' + (j.error || '未知原因'), 'err', 8000);
            resolve(j);
          }
        };
        ctx.timer = setInterval(tick, 1200);
        tick();
        mask.querySelector('[data-a=hide]').onclick = () => { this.stop(ctx); mask.remove(); resolve(null); };
        mask.querySelector('[data-a=done]').onclick = () => { this.stop(ctx); mask.remove(); resolve(null); };
        this.current = ctx;
      });
    },
    stop(ctx) { if (ctx && ctx.timer) clearInterval(ctx.timer); if (this.current === ctx) this.current = null; },
  };

  /* ---------- shared bits ---------- */
  const byteFmt = (n) => {
    if (!n) return '0';
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0; let v = Number(n);
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (v >= 100 ? Math.round(v) : v.toFixed(1)) + u[i];
  };
  const ageOf = (iso) => {
    if (!iso) return '-';
    const d = (Date.now() - new Date(iso).getTime()) / 1000;
    if (d < 90) return '刚刚';
    if (d < 5400) return Math.round(d / 60) + ' 分钟前';
    if (d < 172800) return Math.round(d / 3600) + ' 小时前';
    return Math.round(d / 86400) + ' 天前';
  };
  const accessUrl = (s) => {
    if (s.domains && s.domains.length) return (s.ssl ? 'https://' : 'http://') + s.domains[0];
    const p = (s.ports || [])[0];
    return 'http://' + location.hostname + (p && p !== 80 ? ':' + p : '');
  };

  registerPage({
    id: 'php',
    title: 'PHP 站点',
    async mount(root) {
      let data = null;
      let detail = null;      /* currently expanded site slug */
      let busy = false;

      /* ------------------------------ load ------------------------------ */
      async function load() {
        if (busy) return;
        busy = true;
        try {
          data = await CM.api.get('/php/overview');
        } catch (e) {
          root.innerHTML = `<div class="card"><div class="card-title">PHP 站点</div>
            <div class="muted">读取失败：${fmt.esc(e.message || String(e))}</div>
            <div class="mt"><button class="btn btn-sm" id="p-retry">重试</button></div></div>`;
          document.getElementById('p-retry').onclick = load;
          busy = false;
          return;
        }
        await render();
        busy = false;
      }

      /* ------------------------------ render ---------------------------- */
      async function render() {
        const env = data.versions || [];
        const tools = data.tools || {};
        const res = data.resources || {};
        const sites = data.sites || [];
        const counts = data.counts || {};
        const memPct = res.mem ? Math.round((res.mem.usedMb / Math.max(1, res.mem.totalMb)) * 100) : 0;

        root.innerHTML = `
        <div class="row" style="gap:8px;flex-wrap:wrap;margin-bottom:12px">
          <button class="btn btn-primary btn-sm" id="p-new">${icon('plus', { size: 13 })} 新建 PHP 站点</button>
          <button class="btn btn-sm" id="p-refresh">${icon('refresh', { size: 13 })} 刷新</button>
          <button class="btn btn-sm" id="p-env">${icon('gear', { size: 13 })} PHP 环境 / php.ini</button>
          <button class="btn btn-sm" id="p-ext">${icon('cube', { size: 13 })} 扩展管理</button>
          <button class="btn btn-sm" id="p-jobs">${icon('list', { size: 13 })} 任务记录</button>
          <div style="flex:1"></div>
          <a class="btn btn-sm" href="#web">网站 / Nginx</a>
        </div>

        <div class="grid g4">
          <div class="card stat">
            <div class="stat-label">PHP 运行时</div>
            <div class="stat-value" style="font-size:20px;color:${env.length ? '#4ade80' : '#f87171'}">${env.length || '0'} 个版本</div>
            <div class="stat-sub mono">${fmt.esc(env.map((v) => v.version + (v.full ? ' (' + v.full + ')' : '') + ' · ' + (v.serviceState || '?')).join(' | ') || '未安装')}</div>
          </div>
          <div class="card stat">
            <div class="stat-label">托管站点</div>
            <div class="stat-value" style="font-size:20px">${sites.length}</div>
            <div class="stat-sub">启用 ${counts.enabled || 0} · HTTPS ${counts.ssl || 0} · 带库 ${counts.withDb || 0} · Git ${counts.withGit || 0}</div>
          </div>
          <div class="card stat">
            <div class="stat-label">磁盘（站点根 ${fmt.esc(data.base || '/srv/php')}）</div>
            <div class="stat-value" style="font-size:20px">${byteFmt((res.disk || {}).free)}</div>
            <div class="stat-sub">可用 / 已用 ${byteFmt((res.disk || {}).used)}${ui.bar(res.disk ? (res.disk.used / Math.max(1, res.disk.total)) * 100 : 0)}</div>
          </div>
          <div class="card stat">
            <div class="stat-label">内存 / CPU</div>
            <div class="stat-value" style="font-size:20px;color:${fmt.pctColor(memPct)}">${res.mem ? res.mem.availMb + ' MB 可用' : '-'}</div>
            <div class="stat-sub">${res.cpu || '?'} 核 · 负载 ${fmt.esc(res.load || '-')} · 单池建议 ≤ ${res.poolCeiling || '-'} 子进程</div>
          </div>
        </div>

        ${data.missing && data.missing.length ? `<div class="card">
          <div class="card-title">运行环境缺件
            <div class="right"><button class="btn btn-sm" id="p-installall">${icon('download', { size: 12 })} 一键安装缺失</button></div>
          </div>
          <div class="muted small">缺失：${fmt.esc(data.missing.join(', '))}。PHP 站点功能需要 php-fpm；Composer 用于 Git/框架部署；certbot 用于 HTTPS；MariaDB 用于「建站点顺便建库」。</div>
        </div>` : ''}

        <div class="card">
          <div class="card-title">站点列表 (${sites.length})
            <div class="right"><span class="muted small">配置写入 /etc/nginx/sites-enabled/panel-php-*.conf，与「网站 / Nginx」页共用</span></div>
          </div>
          <div id="p-sites"></div>
        </div>

        <div id="p-detail"></div>

        <div class="card">
          <div class="card-title">使用说明</div>
          <div class="muted small">
            <ul style="margin:0;padding-left:18px;line-height:1.85">
              <li>每个站点是<b>独立系统用户 + 独立 PHP-FPM 池 + 独立 unix 套接字</b>，站点之间互不影响，也互不可读（默认 open_basedir 锁在站点目录内）。</li>
              <li>代码布局 <code>/srv/php/&lt;站点&gt;/releases/&lt;版本&gt;</code> + <code>current</code>/<code>htdocs</code> 软链；nginx 根指向软链，切换是原子的，任意历史版本可一键回滚。</li>
              <li>可写目录（<code>wp-content/uploads</code>、<code>storage</code>、<code>.env</code> 等）落在 <code>shared/</code>，重新部署与回滚都不会丢。</li>
              <li>纯 IP 访问需选一个 1024 以上的端口；<b>云上还需在云控制台安全组放行该端口</b>。有已解析域名的话直接用 80/443，不受此限制。</li>
              <li>在线改文件请用「文件管理」，根目录 <code>/var/www</code> 或站点 <code>current/</code> 均可达；改动落在当前版本内。</li>
              <li>面板服务启用了 <code>PrivateTmp</code>：宿主机 <code>/tmp</code> 下的文件面板读不到，压缩包请放 <code>${fmt.esc(data.staging || '/srv/php/uploads')}</code> 或直接上传。</li>
            </ul>
          </div>
        </div>`;

        bindTop();
        renderSites(sites);
        if (detail) await renderDetail(detail);
      }

      function bindTop() {
        document.getElementById('p-refresh').onclick = load;
        document.getElementById('p-new').onclick = () => createSite();
        document.getElementById('p-env').onclick = () => envPanel();
        document.getElementById('p-ext').onclick = () => extPanel();
        document.getElementById('p-jobs').onclick = () => jobsPanel();
        const ia = document.getElementById('p-installall');
        if (ia) ia.onclick = async () => {
          for (const k of data.missing || []) {
            const action = k === 'php-fpm' ? 'stack' : k === 'mariadb-server' ? 'mariadb' : k;
            await jobModal.run(CM.api.post('/php/env/install', { action: action }), '安装 ' + k);
          }
          load();
        };
      }

      /* ------------------------------ sites table ----------------------- */
      function renderSites(sites) {
        const el = document.getElementById('p-sites');
        if (!sites.length) {
          el.innerHTML = '<div class="muted small">还没有 PHP 站点。点上方「新建 PHP 站点」开始 —— 建完就是一个可直接部署的独立 vhost + FPM 池。</div>';
          return;
        }
        el.innerHTML = ui.table([
          {
            k: 'slug', label: '站点', render: (v, r) => `<div><b>${fmt.esc(r.name || v)}</b>
              <div class="muted small mono">${fmt.esc(v)} · ${fmt.esc((r.domains || []).join(', ') || '仅端口')}
                ${r.htdocs ? `<div class="muted small" style="word-break:break-all">${fmt.esc(r.htdocs)}</div>` : ''}</div></div>`,
          },
          {
            k: 'ports', label: '访问', render: (v, r) => {
              const url = accessUrl(r);
              return `<a class="mono small" href="${fmt.esc(url)}" target="_blank" rel="noreferrer">${fmt.esc(url)}</a>
                <div class="muted small">PHP ${fmt.esc(r.php || '-')} · ${fmt.esc((r.preset || 'generic'))}</div>`;
            },
          },
          {
            k: 'enabled', label: '状态', render: (v, r) => (
              (v && r.linked ? ui.badge('运行中', 'ok') : ui.badge('已停用', 'gray'))
              + (r.ssl ? ' ' + ui.badge('HTTPS', 'ok') : '')
              + (r.db ? ' ' + ui.badge('库', 'info') : '')
              + (r.git && r.git.url ? ' ' + ui.badge('Git', 'info') : '')
            ),
          },
          {
            k: 'fileCount', label: '代码', render: (v, r) => `<div class="small mono">${fmt.esc(byteFmt(r.docSize))}</div>
              <div class="muted small">${v || 0} 个文件 · ${r.releases || 0} 个版本</div>`,
          },
          { k: 'updatedAt', label: '更新', render: (v) => `<span class="small">${fmt.esc(ageOf(v))}</span>` },
          {
            k: 'slug', label: '操作', render: (v, r) => `<div class="row" style="gap:4px;flex-wrap:wrap">
              <button class="btn btn-sm" data-act="detail" data-slug="${fmt.esc(v)}">${detail === v ? '收起' : '管理'}</button>
              <button class="btn btn-sm" data-act="deploy" data-slug="${fmt.esc(v)}">${icon('upload', { size: 12 })} 部署</button>
              <button class="btn btn-sm" data-act="toggle" data-slug="${fmt.esc(v)}" data-on="${r.enabled && r.linked ? '0' : '1'}">${r.enabled && r.linked ? '停用' : '启用'}</button>
            </div>`,
          },
        ], sites);

        el.querySelectorAll('[data-act]').forEach((b) => {
          b.onclick = async () => {
            const act = b.dataset.act; const slug = b.dataset.slug;
            if (act === 'detail') { detail = detail === slug ? null : slug; await render(); return; }
            if (act === 'deploy') return deployDialog(slug);
            if (act === 'toggle') {
              const on = b.dataset.on === '1';
              if (!on) {
                const okc = await modal.confirm('停用站点',
                  `停用 <b class="mono">${fmt.esc(slug)}</b> 会移除它的 nginx 站点链接并重载 nginx，访问该站点的域名/端口会立即返回面板默认的 404/默认站点。`
                  + '<div class="muted small mt">面板自身与 relay 站点不受影响。</div>', { danger: true, okText: '停用' });
                if (!okc) return;
              }
              try { await runAction('/php/site/toggle', { slug: slug, enabled: on }, { showOutput: false }); await load(); } catch (_) {}
            }
          };
        });
      }

      /* ------------------------------ create ---------------------------- */
      async function createSite() {
        const presets = (data.presets || []).map((p) => [p.id, p.label]);
        const vers = (data.versions || []).map((v) => [v.version, 'PHP ' + v.version + (v.full ? ' (' + v.full + ')' : '')]);
        if (!vers.length) {
          window.toast('还没有可用的 PHP-FPM 版本，请先安装运行栈', 'err', 6000);
          return envPanel();
        }
        const v = await form('新建 PHP 站点', `
          <div class="row" style="gap:10px;flex-wrap:wrap">
            ${pair(fld('slug', '站点标识（小写字母/数字/连字符）', '', 'my-shop'),
              sel('preset', '框架预设', presets, 'generic'))}
            ${pair(sel('php', 'PHP 版本', vers, vers[0][0]),
              fld('name', '显示名称（可选）', '', '默认同站点标识'))}
            ${pair(fld('domains', '域名（多个用空格或逗号分隔）', '', 'a.com www.a.com'),
              fld('ports', '监听端口', '80', '80 / 443 / 或 1024+ 单端口'))}
          </div>
          <div class="muted small">不填域名就只能用「1024 以上的独立端口」访问；云上还需在安全组放行该端口。填了域名并解析到本机，就能直接用 80/443。</div>
          <div class="mt">${chk('withDb', '同时创建配套的 MariaDB 数据库与用户', true)}</div>
          <div class="mt row" style="gap:10px;flex-wrap:wrap">
            ${pair(fld('docRoot', '代码内的公开目录（留空=根）', '', 'laravel/thinkphp 填 public'),
              chk('ssl', '立即尝试申请 HTTPS 证书', false))}
          </div>`, { okText: '创建', width: '700px' });
        if (!v) return;

        const preset = (data.presets || []).find((p) => p.id === v.preset) || {};
        const body = {
          slug: v.slug, name: v.name, preset: v.preset, php: v.php,
          domains: v.domains, ports: (v.ports || '80').split(/[\s,]+/).filter(Boolean),
          docRoot: v.docRoot || preset.publicDir || '', ssl: !!v.ssl,
        };
        if (!body.slug) { window.toast('站点标识不能为空', 'err'); return; }
        let r;
        try { r = await CM.api.post('/php/site/create', body); }
        catch (e) {
          window.toast(e.message, 'err', 8000);
          if (e.output) modal.output('创建失败', e.output, { error: true });
          return;
        }
        window.toast(r.output || '站点已创建', 'ok', 7000);
        if (v.withDb) {
          try {
            const d = await CM.api.post('/php/db/create', { slug: body.slug });
            window.toast(d.output || '数据库已创建', 'ok', 7000);
          } catch (e) { window.toast('数据库创建失败：' + e.message, 'err', 8000); }
        }
        detail = body.slug;
        await load();
      }

      /* ------------------------------ deploy ---------------------------- */
      async function deployDialog(slug) {
        const d = await CM.api.get('/php/site?slug=' + encodeURIComponent(slug));
        const rel = d.releases || {};
        let picked = null;
        const v = await form('部署到 ' + slug, `
          ${sel('channel', '部署方式', [
            ['archive', '上传压缩包（zip / tar.gz / tar.xz / 7z）'],
            ['url', '从下载链接拉取压缩包'],
            ['path', '用服务器上已有的路径'],
            ['git', 'Git 拉取（含 Composer 安装）'],
            ['composer', '仅重新 composer install 当前代码'],
            ['scaffold', '一键安装框架/应用'],
            ['reload', '不改动代码，只重载 nginx / FPM 配置'],
          ], 'archive')}
          <div data-ch="archive" class="mt">
            ${fld('name', '压缩包文件名', '', 'site.zip')}
            <div class="muted small">选文件后直接上传到面板暂存目录 ${fmt.esc(d.staging || '/srv/php/uploads')}，任务完成后自动清理。</div>
            <input type="file" class="inp mt" data-file="1" accept=".zip,.tar,.gz,.tgz,.tar.bz2,.tar.xz,.7z,.rar">
            <div class="muted small mt" data-r="fname">未选择文件</div>
          </div>
          <div data-ch="url" class="mt" hidden>${fld('url', '压缩包下载地址', '', 'https://example.com/site.tar.gz')}</div>
          <div data-ch="path" class="mt" hidden>${fld('path', '服务器上的绝对路径', '', (d.staging || '/srv/php/uploads') + '/site.tar.gz')}
            <div class="muted small">注意：面板服务有 PrivateTmp，宿主机 /tmp 下的路径读不到。</div></div>
          <div data-ch="git" class="mt" hidden>
            ${fld('url', '仓库地址', (d.record.git || {}).url || '', 'https://github.com/me/site.git 或 git@github.com:me/site.git')}
            ${fld('branch', '分支', (d.record.git || {}).branch || 'main')}
            ${fld('subdir', '仓库内子目录（可选）', (d.record.git || {}).subdir || '')}
          </div>
          <div data-ch="scaffold" class="mt" hidden>
            ${sel('app', '要安装的应用', (data.presets || []).filter((p) => p.install)
              .map((p) => [p.id, p.label + '（' + p.install.kind + '）']), 'wordpress')}
            <div class="muted small">会下载/生成到当前版本目录，并自动写入配套配置（如 wp-config.php 的数据库与密钥）。</div>
          </div>
          <div class="mt">${fld('docRoot', '公开目录（留空=沿用 ' + (rel.docRoot || '站点根') + '）', '', 'public')}</div>
          <div class="mt row" style="gap:10px;flex-wrap:wrap">
            ${pair(fld('note', '版本备注', '', '本次发布说明'), chk('autoRollback', '部署后探测失败自动回滚', true))}
          </div>
          <div class="mt muted small">每次部署生成一个新的 <code>releases/&lt;时间戳&gt;</code>，切换软链是原子的；旧版本保留 ${rel.keep || 8} 个。</div>
        `, {
          okText: '开始部署', width: '700px',
          onOpen: (m) => {
            const inp = m.querySelector('[data-file]');
            const lbl = m.querySelector('[data-r=fname]');
            if (inp) inp.onchange = () => {
              picked = (inp.files && inp.files[0]) || null;
              if (lbl) lbl.textContent = picked ? (picked.name + ' · ' + byteFmt(picked.size)) : '未选择文件';
            };
          },
          beforeClose: (m) => {
            const which = (m.querySelector('[name=channel]') || {}).value;
            if (which === 'archive') {
              const inp = m.querySelector('[data-file]');
              if (!(inp && inp.files && inp.files[0])) {
                window.toast('请先选择要上传的压缩包', 'err');
                return false;
              }
            }
            return true;
          },
        });
        if (!v) return;

        const body = { slug: slug, channel: v.channel, note: v.note, autoRollback: v.autoRollback };
        if (v.docRoot) body.docRoot = v.docRoot;

        if (v.channel === 'archive') {
          const file = picked;
          if (!file) { window.toast('请先选择压缩包', 'err'); return; }
          if (file.size > 2 * 1024 * 1024 * 1024) { window.toast('压缩包超过 2GB', 'err'); return; }
          window.toast('正在上传 ' + file.name + '（' + byteFmt(file.size) + '）…', 'ok', 5000);
          let up;
          try {
            up = await fetch(CM.apiBase + '/php/upload?name=' + encodeURIComponent(file.name), {
              method: 'POST',
              headers: { 'Authorization': 'Bearer ' + CM.token, 'Content-Type': 'application/octet-stream' },
              body: file,
            });
          } catch (e) { window.toast('上传失败：' + e.message, 'err', 8000); return; }
          if (!up.ok) {
            let t = ''; try { t = (await up.json()).error || ''; } catch (_) { t = await up.text(); }
            window.toast('上传失败：' + (t || ('HTTP ' + up.status)), 'err', 9000);
            return;
          }
          const ur = await up.json();
          body.source = 'upload'; body.uploadId = ur.id;
        } else if (v.channel === 'git') {
          body.git = { url: v.url, branch: v.branch, subdir: v.subdir, remember: true };
        } else if (v.channel === 'url') {
          body.source = 'url'; body.url = v.url;
        } else if (v.channel === 'path') {
          body.source = 'path'; body.path = v.path;
        } else if (v.channel === 'scaffold') {
          body.app = v.app;
        }

        try {
          await jobModal.run(CM.api.post('/php/deploy', body), slug + ' · 部署');
          await load();
        } catch (e) { /* job modal already surfaced it */ }
      }

      /* ------------------------------ detail panel ---------------------- */
      async function renderDetail(slug) {
        const host = document.getElementById('p-detail');
        let d;
        try { d = await CM.api.get('/php/site?slug=' + encodeURIComponent(slug)); }
        catch (e) { host.innerHTML = '<div class="card"><div class="muted">读取站点失败：' + fmt.esc(e.message) + '</div></div>'; return; }
        const s = d.site || {}; const rec = d.record || {}; const db = d.db || {}; const ssl = d.ssl || {};
        const rels = (d.releases || {}).releases || [];

        host.innerHTML = `
        <div class="card">
          <div class="card-title">站点管理 · ${fmt.esc(s.name || s.slug)}
            <div class="right"><button class="btn btn-sm" data-d="close">收起</button></div>
          </div>
          <div class="grid g3">
            <div>
              <div class="stat-label">访问入口</div>
              ${(s.urls || []).map((u) => `<div><a class="mono small" href="${fmt.esc(u)}" target="_blank" rel="noreferrer">${fmt.esc(u)}</a></div>`).join('') || '<span class="muted">—</span>'}
              <div class="stat-label mt">健康探测</div>
              <div class="row" style="gap:6px"><button class="btn btn-sm" data-d="probe">${icon('flask', { size: 12 })} 探测</button>
                <span class="muted small mono" data-r="probe">未探测</span></div>
            </div>
            <div>
              <div class="stat-label">运行配置</div>
              <div class="small mono">PHP ${fmt.esc(s.php || '-')}${s.sockExists ? ' · 套接字 OK' : ' · 套接字缺失'}
                <br>池 ${s.poolExists ? '已写入' : '缺失'} · nginx ${s.linked ? '已启用' : '未启用'}
                <br>代码 ${fmt.esc(byteFmt(s.docSize))} / ${s.fileCount || 0} 文件
                <br>当前版本 ${fmt.esc((s.current || '-'))}</div>
              <div class="row mt" style="gap:6px;flex-wrap:wrap">
                <button class="btn btn-sm" data-d="opcacheclear">清 OPcache</button>
                <button class="btn btn-sm" data-d="edit">编辑配置</button>
              </div>
            </div>
            <div>
              <div class="stat-label">HTTPS / 数据库</div>
              <div class="small">${ssl.ssl ? (ssl.cert ? '已启用，到期 ' + fmt.esc(ssl.cert.expiry || '-') + '（剩 ' + ssl.cert.daysLeft + ' 天）' : '已启用但读不到证书') : '未启用'}
                ${ssl.reason ? '<div class="muted">' + fmt.esc(ssl.reason) + '</div>' : ''}</div>
              <div class="row mt" style="gap:6px;flex-wrap:wrap">
                <button class="btn btn-sm" data-d="ssl">${ssl.ssl ? '续期/重签' : '申请证书'}</button>
                ${ssl.ssl ? '<button class="btn btn-sm btn-danger" data-d="ssloff">关闭 HTTPS</button>' : ''}
                <button class="btn btn-sm" data-d="db">${db.bound ? '数据库详情' : '建数据库'}</button>
              </div>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-title">发布历史与回滚 (${rels.length})
            <div class="right"><button class="btn btn-sm" data-d="deploy">${icon('upload', { size: 12 })} 部署新版本</button></div>
          </div>
          ${rels.length ? ui.table([
            { k: 'id', label: '版本', cls: 'mono', render: (v, r) => `${fmt.esc(v)}${r.active ? ' ' + ui.badge('当前', 'ok') : ''}` },
            { k: 'kind', label: '方式' },
            { k: 'at', label: '时间', render: (v) => fmt.esc(ageOf(v)) },
            { k: 'note', label: '备注', render: (v) => fmt.esc(String(v || '').slice(0, 40)) },
            { k: 'sha', label: 'Git', cls: 'mono', render: (v, r) => v ? fmt.esc(String(v).slice(0, 10)) + '<div class="muted small">' + fmt.esc(String(r.subject || '').slice(0, 40)) + '</div>' : '—' },
            { k: 'id', label: '', render: (v, r) => r.active ? '' : `<button class="btn btn-sm" data-rb="${fmt.esc(v)}">回滚到此版本</button>` },
          ], rels) : '<div class="muted small">还没有发布记录。</div>'}
          <div class="muted small mt">shared/ 里的 <code>.env</code>、<code>${fmt.esc(((d.releases || {}).shared || []).join('、') || '（无）')}</code> 不随版本切换丢失。</div>
        </div>

        <div class="grid g2">
          <div class="card">
            <div class="card-title">Git 与自动部署</div>
            <div class="small mono">${fmt.esc((d.record.git || {}).url || '未配置仓库')} ${fmt.esc((d.record.git || {}).branch || '')}</div>
            <div class="row mt" style="gap:6px;flex-wrap:wrap">
              <button class="btn btn-sm" data-d="git">配置仓库</button>
              <button class="btn btn-sm" data-d="webhook">Webhook</button>
            </div>
            <div class="muted small mt">deploy key / token 只留在站点记录（0600）里，接口与审计日志都不会回传明文。</div>
          </div>
          <div class="card">
            <div class="card-title">流量与日志</div>
            <div id="d-stats" class="muted small">加载中…</div>
            <div class="row mt" style="gap:6px;flex-wrap:wrap">
              <button class="btn btn-sm" data-d="logs" data-t="access">访问日志</button>
              <button class="btn btn-sm" data-d="logs" data-t="error">错误日志</button>
              <button class="btn btn-sm" data-d="logs" data-t="deploy">部署日志</button>
              <button class="btn btn-sm" data-d="logs" data-t="slow">PHP 慢日志</button>
              <button class="btn btn-sm" data-d="rotate">轮转日志</button>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-title">nginx / PHP-FPM 配置
            <div class="right"><span class="muted small">改完会先 <code>nginx -t</code>，不通过自动回滚</span></div>
          </div>
          <div class="row" style="gap:6px;flex-wrap:wrap">
            <button class="btn btn-sm" data-d="conf" data-t="nginx">编辑 nginx 配置</button>
            <button class="btn btn-sm" data-d="conf" data-t="pool">查看 FPM 池配置</button>
            <button class="btn btn-sm" data-d="phpini">本站点 PHP 参数覆盖</button>
            <button class="btn btn-sm" data-d="hooks">部署钩子</button>
            <button class="btn btn-sm" data-d="envfile">shared/.env</button>
          </div>
        </div>

        <div class="card">
          <div class="card-title" style="color:#f87171">危险操作</div>
          <div class="row" style="gap:6px;flex-wrap:wrap">
            <button class="btn btn-sm" data-d="toggle" data-on="${s.enabled && s.linked ? '0' : '1'}">${s.enabled && s.linked ? '停用站点' : '启用站点'}</button>
            <button class="btn btn-sm btn-danger" data-d="del">${icon('trash', { size: 12 })} 删除站点</button>
          </div>
          <div class="muted small mt">删除会移除 nginx 站点、FPM 池、系统用户与配套数据库；站点目录（代码+日志+备份）移动到
            <code class="mono">${fmt.esc((d.trash || '/var/lib/cloud-panel/ops/php_deleted'))}</code> 保留，可手工恢复。</div>
        </div>`;

        host.querySelector('[data-d=close]').onclick = () => { detail = null; render(); };
        host.querySelectorAll('[data-d]').forEach((b) => { b.onclick = () => onDetail(b.dataset.d, b.dataset, slug, d); });
        host.querySelectorAll('[data-rb]').forEach((b) => {
          b.onclick = async () => {
            const okc = await modal.confirm('回滚版本',
              '把 <b class="mono">' + fmt.esc(slug) + '</b> 切回 <b class="mono">' + fmt.esc(b.dataset.rb) + '</b>。<br>'
              + '<span class="muted small">该版本之后的代码改动不再对外生效（版本目录仍保留，可再切回）；shared/ 内的数据不受影响。</span>',
              { danger: true, okText: '回滚' });
            if (!okc) return;
            await jobModal.run(CM.api.post('/php/rollback', { slug: slug, release: b.dataset.rb }), slug + ' · 回滚');
            await load();
          };
        });
        loadStats(slug);
      }

      async function onDetail(act, ds, slug, d) {
        if (act === 'deploy') return deployDialog(slug);
        if (act === 'probe') {
          const el = document.querySelector('[data-r=probe]');
          el.textContent = '探测中…';
          try {
            const r = await CM.api.get('/php/preview?slug=' + encodeURIComponent(slug));
            el.textContent = (r.code || '?') + ' · ' + (r.detail || '') + ' → ' + (r.url || '');
          } catch (e) { el.textContent = '探测失败：' + e.message; }
          return;
        }
        if (act === 'opcacheclear') {
          try { await runAction('/php/opcache/reset', { slug: slug }, { showOutput: true }); } catch (_) {}
          return;
        }
        if (act === 'edit') return editSite(slug);
        if (act === 'toggle') {
          const on = ds.on === '1';
          if (!on) {
            const okc = await modal.confirm('停用站点', '移除 <b class="mono">' + fmt.esc(slug) + '</b> 的 nginx 链接并重载 nginx。', { danger: true, okText: '停用' });
            if (!okc) return;
          }
          await runAction('/php/site/toggle', { slug: slug, enabled: on });
          return load();
        }
        if (act === 'del') {
          const okc = await modal.confirm('删除站点',
            '将移除 <b class="mono">' + fmt.esc(slug) + '</b> 的 nginx 站点、PHP-FPM 池、系统用户'
            + (d.db && d.db.bound ? ' 及其 MariaDB 数据库/用户' : '')
            + '。<div class="muted small mt">站点目录（代码/日志/数据库备份）会移动到回收位置保留，不是直接 rm -rf。</div>',
            { danger: true, okText: '确认删除' });
          if (!okc) return;
          try { await runAction('/php/site/delete', { slug: slug }, { showOutput: true }); detail = null; await load(); } catch (_) {}
          return;
        }
        if (act === 'ssl') return sslDialog(slug, d);
        if (act === 'ssloff') { await runAction('/php/ssl/disable', { slug: slug }); return load(); }
        if (act === 'db') return dbDialog(slug, d);
        if (act === 'git') return gitDialog(slug, d);
        if (act === 'webhook') return webhookDialog(slug, d);
        if (act === 'conf') return confDialog(slug, ds.t);
        if (act === 'phpini') return phpIniDialog(slug, d);
        if (act === 'hooks') return hooksDialog(slug, d);
        if (act === 'envfile') return envFileDialog(slug);
        if (act === 'logs') return logDialog(slug, ds.t);
        if (act === 'rotate') {
          const v = await modal.prompt('轮转日志', [{ label: '日志类型', name: 'type', value: 'access' }]);
          if (!v) return;
          await runAction('/php/logs/rotate', { slug: slug, type: v.type }, { showOutput: true });
          return;
        }
      }

      /* ------------------------------ per-site edit --------------------- */
      async function editSite(slug) {
        const d = await CM.api.get('/php/site?slug=' + encodeURIComponent(slug));
        const r = d.record || {};
        const vers = (data.versions || []).map((v) => [v.version, 'PHP ' + v.version]);
        const v = await form('编辑站点 ' + slug, `
          <div class="row" style="gap:10px;flex-wrap:wrap">
            ${pair(fld('name', '显示名称', r.name || ''), sel('preset', '框架预设', (data.presets || []).map((p) => [p.id, p.label]), r.preset))}
            ${pair(sel('php', 'PHP 版本', vers, r.php), fld('docRoot', '公开目录', r.docRoot || '', 'public'))}
            ${pair(fld('domains', '域名（空格/逗号分隔）', (r.domains || []).join(' ')),
              fld('ports', '监听端口', (r.ports || [80]).join(',')))}
          </div>
          <div class="row" style="gap:10px;flex-wrap:wrap">
            ${pair(chk('ssl', '启用 HTTPS', r.ssl), chk('openBasedir', 'open_basedir 锁定站点目录', r.openBasedir))}
            ${pair(chk('denyUploadExec', '可写目录禁止执行 PHP', (r.hardening || {}).denyUploadExec),
              fld('proxyPort', '反向代理端口（仅 proxy 类预设）', r.proxyPort || ''))}
          </div>
          <div class="mt">${area('uploadDirs', '可写/上传目录（每行一个，禁止其中执行 PHP）', (r.uploadDirs || []).join('\n'), 3, 'uploads\nwp-content/uploads')}</div>
          <div class="mt">${area('disableFunctions', 'disable_functions（留空=用面板安全默认）', r.disableFunctions, 2)}</div>
          <div class="mt">${area('extraNginx', '附加 nginx 指令（原样注入 server 块）', r.extraNginx, 4, 'location = /healthz { return 200 "ok"; }')}</div>
          <div class="mt">${area('notes', '备注', r.notes, 2)}</div>
          <div class="mt">${sel('pm', '进程管理模式（内存有限时 ondemand 最省）',
            [['ondemand', 'ondemand（按需拉起，省内存）'], ['dynamic', 'dynamic（常驻）'], ['static', 'static（固定，最快但最吃内存）']], (r.pool || {}).pm || 'ondemand')}</div>
          <div class="row mt" style="gap:10px;flex-wrap:wrap">
            ${pair(fld('maxChildren', '子进程上限 pm.max_children', (r.pool || {})['pm.max_children'] || 12, String(data.resources.poolCeiling || 8) + ' 是本机内存下的建议值'),
              fld('memoryLimit', 'memory_limit', (r.phpOverrides || {}).memory_limit || '256M'))}
            ${pair(fld('uploadMax', 'upload_max_filesize', (r.phpOverrides || {}).upload_max_filesize || '64M'),
              fld('maxExec', 'max_execution_time（秒）', (r.phpOverrides || {}).max_execution_time || '60'))}
          </div>`, { okText: '保存并重载', width: '760px' });
        if (!v) return;
        const body = {
          slug: slug, name: v.name, preset: v.preset, php: v.php, docRoot: v.docRoot,
          domains: v.domains, ports: v.ports, ssl: !!v.ssl, openBasedir: !!v.openBasedir,
          uploadDirs: String(v.uploadDirs || '').split(/[\n,]+/).map((x) => x.trim()).filter(Boolean),
          disableFunctions: v.disableFunctions, extraNginx: v.extraNginx,
          notes: v.notes,
          hardening: Object.assign({}, r.hardening, { denyUploadExec: !!v.denyUploadExec }),
          pool: { pm: v.pm, 'pm.max_children': Number(v.maxChildren) || 12 },
          phpOverrides: { memory_limit: v.memoryLimit, upload_max_filesize: v.uploadMax, max_execution_time: v.maxExec },
        };
        if (v.proxyPort) body.proxyPort = Number(v.proxyPort);
        try {
          const v2 = await CM.api.post('/php/site/update', body);
          window.toast(v2.output || '已保存', 'ok', 6000);
          await load();
        } catch (e) {
          window.toast(e.message, 'err', 8000);
          if (e.output) modal.output('保存失败（配置已自动回滚）', e.output, { error: true });
        }
      }

      /* ------------------------------ dialogs --------------------------- */
      async function sslDialog(slug, d) {
        const cur = d.ssl || {};
        if (!(d.site.domains || []).length) {
          window.toast('该站点没有域名：Let\'s Encrypt 不为纯 IP 或本机端口签发证书，请先绑定一个已解析到本机的域名。', 'err', 9000);
          return;
        }
        const v = await form('HTTPS 证书 · ' + slug, `
          <div class="muted small">当前域名：${fmt.esc((d.site.domains || []).join(', '))}</div>
          <div class="mt">${fld('domains', '要签入的域名（默认全部）', (d.site.domains || []).join(' '))}</div>
          ${pair(fld('email', '联系邮箱（可选，用于到期提醒）', cur.cert ? '' : ''), chk('force', '已存在证书也重新申请', false))}
          <div class="muted small mt">流程：先确认域名已解析到本机 → 用 webroot 做 HTTP-01 验证（站点已内置 <code>/.well-known/acme-challenge/</code>）→ 写入 443 监听并把 80 改为 301 跳转 → 放行 443。
            <br>Let's Encrypt <b>不给纯 IP 和 .test/.local 这类域名签发</b>。</div>`, { okText: '申请证书', width: '640px' });
        if (!v) return;
        try {
          await jobModal.run(CM.api.post('/php/ssl/issue', { slug: slug, domains: v.domains, email: v.email }), slug + ' · 申请证书');
          await load();
        } catch (e) { window.toast(e.message || '申请失败', 'err', 8000); }
      }

      async function dbDialog(slug, d) {
        const db = d.db || {};
        if (!db.bound) {
          const okc = await modal.confirm('创建数据库',
            '为 <b class="mono">' + fmt.esc(slug) + '</b> 建一个同名 MariaDB 库与专用用户（随机强密码，只授权该库，仅允许 localhost 连接），并把连接信息写进站点的 <code>shared/.env</code>。');
          if (!okc) return;
          try { const r = await CM.api.post('/php/db/create', { slug: slug }); showCreds(r); await load(); }
          catch (e) { window.toast(e.message, 'err', 8000); if (e.output) modal.output('建库失败', e.output, { error: true }); }
          return;
        }
        const info = await CM.api.get('/php/db?slug=' + encodeURIComponent(slug));
        const v = await form('数据库 · ' + (info.db || {}).name, `
          <div class="mono small">库名 ${fmt.esc((info.db || {}).name || '-')}<br>用户 ${fmt.esc((info.db || {}).user || '-')}@${fmt.esc((info.db || {}).host || 'localhost')}<br>
          表数 ${info.tables || 0} · 占用 ${byteFmt(info.bytes)} · 连接${info.connected ? '正常' : '（无表）'}</div>
          <div class="mt muted small">明文密码只在创建/重置的那一刻显示一次，接口与站点记录都不会回传。</div>`,
          { okText: '关闭', width: '560px' });
        if (!v) return;
      }

      function showCreds(r) {
        const db = (r.db || {});
        form('数据库已创建 · 请立刻保存密码', `
          <div class="mono small">
            主机 host: <b>localhost</b><br>
            库名 name: <b>${fmt.esc(db.name)}</b><br>
            用户 user: <b>${fmt.esc(db.user)}</b><br>
            密码 pass: <b style="color:#fbbf24">${fmt.esc(db.passwordShown || '')}</b>
          </div>
          <div class="muted small mt">该密码已写入站点记录与 shared/.env，关掉本窗口后接口不再返回明文。如需更换可在「数据库」里重置。</div>`,
          { okText: '我已记录', width: '560px' });
      }

      async function gitDialog(slug, d) {
        const g = (d.record || {}).git || {};
        const v = await form('Git 仓库 · ' + slug, `
          ${fld('url', '仓库地址', g.url || '', 'https://github.com/me/site.git 或 git@github.com:me/site.git')}
          <div class="row" style="gap:10px;flex-wrap:wrap">
            ${pair(fld('branch', '分支', g.branch || 'main'), fld('subdir', '仓库内子目录', g.subdir || ''))}
            ${pair(fld('depth', '浅克隆深度', g.depth || 1), chk('autoDeploy', '开启 Webhook 自动部署', !!g.autoDeploy))}
          </div>
          ${area('token', 'HTTPS Token（留空=不修改；建议用只读 Deploy Token）', '', 2, 'ghp_…')}
          ${area('privateKey', 'Deploy 私钥（PEM，留空=不修改）', '', 5, '-----BEGIN OPENSSH PRIVATE KEY-----')}
          <div class="muted small mt">SSH 地址需把公钥加到仓库的 Deploy Keys；私钥写入 <code>/srv/php/${fmt.esc(slug)}/deploy_key</code>（0600，属主为站点用户）。
            Token 会拼进 remote URL，仅本机使用，不出现在任务日志里。</div>`, { okText: '保存', width: '700px' });
        if (!v) return;
        const git = { url: v.url, branch: v.branch, subdir: v.subdir, depth: Number(v.depth) || 1, autoDeploy: !!v.autoDeploy };
        if (v.token) git.token = v.token;
        if (v.privateKey) git.privateKey = v.privateKey;
        try { await runAction('/php/site/git', { slug: slug, git: git }, { showOutput: true }); await load(); } catch (_) {}
      }

      async function webhookDialog(slug, d) {
        const w = d.webhook || {};
        const v = await form('部署 Webhook · ' + slug, `
          <div class="small">回调地址（GitHub / Gitee 的 Webhook 里填它，Content type 选 application/json）：</div>
          ${area('url', 'URL', (location.origin + w.path) || '', 2)}
          <div class="muted small">触发等价于一次 Git 部署；需在「配置仓库」里勾选自动部署。两次触发间隔需大于 20 秒。</div>
          <div class="mt">${chk('rotate', '重置令牌（旧地址立即失效）', false)}</div>`, { okText: '复制用地址已选中', width: '640px' });
        if (v && v.rotate) {
          await runAction('/php/site/webhook/rotate', { slug: slug }, { showOutput: true });
          await load();
        }
      }

      async function confDialog(slug, kind) {
        const c = await CM.api.get('/php/site/conf?slug=' + encodeURIComponent(slug));
        if (kind === 'pool') {
          modal.output('PHP-FPM 池（只读，改参数请用「PHP 参数覆盖」）',
            (c.pool || {}).content || '（无池文件）');
          return;
        }
        const v = await form('编辑 nginx 配置 · ' + slug, `
          <div class="muted small mono">${fmt.esc(c.file)}</div>
          ${area('content', 'server 块内容', c.content || '', 22)}
          <div class="muted small">保存流程：写文件 → <code>nginx -t</code> → 通过才 reload；不通过自动还原旧版本并把错误原样回显。
            禁止声明 <code>default_server</code>（会抢掉 relay 与面板自身的默认站点）。</div>`,
          { okText: '校验并生效', width: '860px' });
        if (!v) return;
        try {
          await runAction('/php/site/conf/write', { slug: slug, content: v.content }, { showOutput: true });
          await load();
        } catch (e) { /* runAction showed the nginx output */ }
      }

      async function phpIniDialog(slug, d) {
        const r = d.record || {};
        const keys = data.iniKeys || [];
        const cur = r.phpOverrides || {};
        const v = await form('本站点 PHP 参数覆盖 · ' + slug, `
          <div class="muted small">写进该站点自己的 FPM 池（<code>php_value[…]</code>），只影响这一个站点。</div>
          <div class="mt row" style="gap:10px;flex-wrap:wrap">
            ${keys.slice(0, 12).map((k) => fld(k.key, k.label + (k.unit ? '（' + k.unit + '）' : ''), cur[k.key] || '')).join('')}
          </div>
          ${area('extra', '其他指令（每行 key = value）',
            Object.keys(cur).filter((x) => !keys.slice(0, 12).some((k) => k.key === x)).map((x) => x + ' = ' + cur[x]).join('\n'), 4)}
          <div class="muted small">留空的项会移除覆盖，回落到面板默认。PHP_INI_SYSTEM 类指令（如 cgi.fix_pathinfo）不能按站点设置。</div>`,
          { okText: '保存并重载池', width: '760px' });
        if (!v) return;
        const ov = {};
        for (const k of keys.slice(0, 12)) if (String(v[k.key] || '').trim()) ov[k.key] = String(v[k.key]).trim();
        for (const line of String(v.extra || '').split('\n')) {
          const m = /^\s*([a-z0-9_.]+)\s*=\s*(.+)$/i.exec(line);
          if (m) ov[m[1]] = m[2];
        }
        try { await runAction('/php/site/update', { slug: slug, phpOverrides: ov }, { showOutput: true }); await load(); } catch (_) {}
      }

      async function hooksDialog(slug, d) {
        const h = (d.releases || {}).hooks || (d.record || {}).hooks || {};
        const v = await form('部署钩子 · ' + slug, `
          ${area('pre', 'pre_deploy（切软链之前，在版本目录里执行）', (h.pre_deploy || []).join('\n'), 4, 'php artisan down')}
          ${area('post', 'post_deploy（切软链之后执行）', (h.post_deploy || []).join('\n'), 4,
            'php artisan migrate --force\nphp artisan config:cache')}
          <div class="muted small">钩子以<b>站点用户</b>身份在当前版本目录内执行，单个最长 15 分钟；任一条失败即整个部署失败（不会切换软链）。</div>`,
          { okText: '保存', width: '700px' });
        if (!v) return;
        await runAction('/php/site/hooks', {
          slug: slug,
          hooks: { pre_deploy: v.pre, post_deploy: v.post },
        }, { showOutput: true });
      }

      async function envFileDialog(slug) {
        const cur = await CM.api.get('/ops/files/preview?path=' + encodeURIComponent('/srv/php/' + slug + '/shared/.env'))
          .catch(() => ({ content: '' }));
        const v = await form('shared/.env · ' + slug, `
          <div class="muted small mono">/srv/php/${fmt.esc(slug)}/shared/.env</div>
          ${area('content', '内容', cur.content || cur.output || '', 16, 'APP_KEY=…')}
          <div class="muted small">这份文件在 shared/ 下，重新部署与回滚都不会被覆盖；权限 0640，属主为站点用户。</div>`,
          { okText: '写入', width: '760px' });
        if (!v) return;
        try {
          await runAction('/php/site/env', { slug: slug, envFile: v.content }, { showOutput: true });
        } catch (e) { window.toast(e.message, 'err', 8000); }
      }

      async function logDialog(slug, type) {
        const v = await form('查看日志 · ' + slug + ' · ' + type, `
          ${pair(fld('lines', '行数', 200, '最多 5000'), fld('grep', '关键字过滤（可选）', ''))}
          <div class="muted small mt">日志文件位于 <code>/srv/php/${fmt.esc(slug)}/logs/</code>，可用「轮转日志」压缩归档（保留最近 20 份）。</div>`,
          { okText: '查看', width: '560px' });
        if (!v) return;
        const r = await CM.api.get('/php/logs?slug=' + encodeURIComponent(slug) + '&type=' + encodeURIComponent(type) + '&lines=' + encodeURIComponent(v.lines || 200) + '&grep=' + encodeURIComponent(v.grep || ''));
        modal.output(type + ' 日志 · ' + slug + '（' + (r.exists ? byteFmt(r.bytes) : '文件尚不存在') + '）',
          (r.lines || []).join('\n') || '（空）');
      }

      async function loadStats(slug) {
        const el = document.getElementById('d-stats');
        if (!el) return;
        try {
          const st = await CM.api.get('/php/stats?slug=' + encodeURIComponent(slug));
          if (!st.exists) { el.textContent = '还没有访问日志。'; return; }
          const cls = st.byClass || {};
          const top = (st.topUrls || []).slice(0, 5).map((u) => `${u.path} (${u.count})`).join('\n');
          el.innerHTML = `<div class="mono small">请求 ${st.requests} · 2xx ${cls.ok || 0} · 3xx ${cls.redirect || 0}
            · 4xx ${cls.clientError || 0} · 5xx ${cls.serverError || 0} · 流量 ${fmt.esc(byteFmt(st.bytes))} · 错误率 ${st.errorRate}%</div>
            <pre class="pre mono small mt" style="max-height:120px;overflow:auto">${fmt.esc('热门路径:\n' + top)}</pre>`;
        } catch (e) { el.textContent = '统计读取失败：' + e.message; }
      }

      /* --------------------------- environment panels ------------------ */
      async function envPanel() {
        const e = await CM.api.get('/php/env');
        const vers = e.versions || [];
        const html = `
          <div class="muted small">站点根 <code>${fmt.esc(e.base)}</code> · 上传暂存 <code>${fmt.esc(e.staging)}</code> · 默认版本 ${fmt.esc(e.defaultVersion || '-')}</div>
          <div class="mt">
          ${vers.map((v) => `<div class="card" style="margin:0 0 8px">
            <div class="card-title">PHP ${fmt.esc(v.version)} ${v.full ? '(' + fmt.esc(v.full) + ')' : ''}
              <div class="right"><span class="muted small">${fmt.esc(v.fpmService || '')} ${fmt.esc(v.serviceState || '')} · 池 ${(v.pools || []).length} 个</span></div>
            </div>
            <div class="row" style="gap:6px;flex-wrap:wrap">
              <button class="btn btn-sm" data-e="reload" data-v="${fmt.esc(v.version)}">重载 FPM</button>
              <button class="btn btn-sm" data-e="restart" data-v="${fmt.esc(v.version)}">重启 FPM</button>
              <button class="btn btn-sm" data-e="ini" data-v="${fmt.esc(v.version)}">全局 php.ini 覆盖</button>
              <button class="btn btn-sm" data-e="info" data-v="${fmt.esc(v.version)}">运行时详情</button>
            </div>
          </div>`).join('') || '<div class="muted">未检测到 PHP-FPM 版本。</div>'}
          </div>
          <div class="mt"><b class="small">工具链</b>
            <div class="mono small">composer ${fmt.esc(e.tools.composer || '未安装')} · git ${fmt.esc(e.tools.git || '未安装')}
              · certbot ${fmt.esc(e.tools.certbot || '未安装')} · MariaDB ${fmt.esc(e.tools.mariadbState || '未安装')}
              · redis ${fmt.esc(e.tools.redisState || '未安装')}</div></div>
          <div class="mt row" style="gap:6px;flex-wrap:wrap">
            <button class="btn btn-sm" data-e="install" data-a="composer">装 Composer</button>
            <button class="btn btn-sm" data-e="install" data-a="certbot">装 certbot</button>
            <button class="btn btn-sm" data-e="install" data-a="mariadb">装 MariaDB</button>
            <button class="btn btn-sm" data-e="install" data-a="extras">装 unzip/rsync/git</button>
            <button class="btn btn-sm" data-e="renew">立即检查证书续期</button>
          </div>
          <div class="muted small mt">内存提示：本机可用约 ${((e.resources || {}).mem || {}).availMb || 0} MB，
            单个 PHP 子进程约 40–70 MB，所有站点池的 max_children 之和建议不超过约 ${(e.resources || {}).poolCeiling || 8} × 站点数。</div>`;
        const mask = await openPanel('PHP 环境', html, { okText: '关闭', width: '760px' });
        bindEnv(mask);
      }

      function bindEnv(mask) {
        mask.querySelectorAll('[data-e]').forEach((b) => {
          b.onclick = async () => {
            const a = b.dataset.e; const v = b.dataset.v;
            if (a === 'reload' || a === 'restart') {
              await runAction('/php/fpm/action', { version: v, action: a === 'reload' ? 'reload' : 'restart' }, { showOutput: true });
            } else if (a === 'ini') {
              const ini = await CM.api.get('/php/ini?version=' + encodeURIComponent(v));
              const keys = data.iniKeys || [];
              const fv = await form('PHP ' + v + ' 全局覆盖', `
                <div class="muted small">写入 <code>${fmt.esc(ini.scanFile)}</code>（不动发行版的 php.ini）；影响该版本所有未单独覆盖的池。</div>
                <div class="mt row" style="gap:10px;flex-wrap:wrap">
                  ${keys.map((k) => fld(k.key, k.label + (k.unit ? '（' + k.unit + '）' : ''),
                    (ini.overrides || {})[k.key] || (ini.effectiveCli || {})[k.key] || '')).join('')}
                </div>`, { okText: '保存并重载', width: '760px' });
              if (!fv) return;
              const values = {};
              for (const k of keys) if (String(fv[k.key] || '').trim()) values[k.key] = String(fv[k.key]).trim();
              await runAction('/php/ini', { version: v, values: values }, { showOutput: true });
            } else if (a === 'info') {
              const pi = await CM.api.get('/php/phpinfo?version=' + encodeURIComponent(v));
              modal.output('PHP ' + v + ' 运行时', JSON.stringify(pi, null, 2));
            } else if (a === 'install') {
              await jobModal.run(CM.api.post('/php/env/install', { action: b.dataset.a }), '安装组件');
            } else if (a === 'renew') {
              await runAction('/php/ssl/renew', {}, { showOutput: true, title: 'certbot renew' });
            }
            load();
          };
        });
      }

      async function extPanel() {
        const vers = (data.versions || []).map((v) => [v.version, 'PHP ' + v.version]);
        if (!vers.length) { window.toast('还没有 PHP 版本', 'err'); return; }
        let list = await CM.api.get('/php/ext?version=' + vers[0][0]);
        const paint = () => ui.table([
          { k: 'ext', label: '扩展', cls: 'mono' },
          { k: 'label', label: '用途', render: (v) => fmt.esc(String(v || '').slice(0, 60)) },
          { k: 'installed', label: '状态', render: (v) => v ? ui.badge('已装', 'ok') : ui.badge('未装', 'gray') },
          { k: 'package', label: '软件包', cls: 'mono' },
          { k: 'ext', label: '', render: (v, r) => `<button class="btn btn-sm" data-x="${fmt.esc(v)}" data-i="${r.installed ? '0' : '1'}">${r.installed ? '移除' : '安装'}</button>` },
        ], list.catalog || []);
        const html = `
          <div class="row" style="gap:10px;align-items:end;flex-wrap:wrap">
            <label class="fld">PHP 版本<select class="inp" id="xv">${vers.map((x) => `<option value="${fmt.esc(x[0])}">${fmt.esc(x[1])}</option>`).join('')}</select></label>
            <span class="muted small" id="xc">已装 ${((list.catalog || []).filter((x) => x.installed)).length} / ${(list.catalog || []).length}</span>
          </div>
          <div class="mt" id="xb">${paint()}</div>
          <div class="muted small mt">扩展是<b>按 PHP 版本全局</b>安装的 apt 软件包，装完会平滑重载该版本的所有 FPM 池。
            opcache 通常随 php-common 内置，无需单独安装。</div>`;
        const mask = await openPanel('PHP 扩展管理', html, { okText: '关闭', width: '760px' });
        const body = mask.querySelector('#xb');
        const verSel = mask.querySelector('#xv');
        verSel.onchange = async () => {
          list = await CM.api.get('/php/ext?version=' + encodeURIComponent(verSel.value));
          body.innerHTML = paint();
          mask.querySelector('#xc').textContent = '已装 ' + (list.catalog || []).filter((x) => x.installed).length + ' / ' + (list.catalog || []).length;
          bindX();
        };
        function bindX() {
          body.querySelectorAll('[data-x]').forEach((b) => {
            b.onclick = async () => {
              const ext = b.dataset.x; const install = b.dataset.i === '1';
              if (!install) {
                const okc = await modal.confirm('移除扩展', '移除 <code>' + fmt.esc(ext) + '</code> 后，用到它的站点会报「undefined function」。确定继续？', { danger: true });
                if (!okc) return;
              }
              ui.busy(b, true);
              try {
                if (install) await jobModal.run(CM.api.post('/php/env/install', { action: 'ext', version: verSel.value, extension: ext }), '安装扩展 ' + ext);
                else await runAction('/php/env/ext/remove', { version: verSel.value, extension: ext }, { showOutput: true });
                list = await CM.api.get('/php/ext?version=' + encodeURIComponent(verSel.value));
                body.innerHTML = paint(); bindX();
              } finally { ui.busy(b, false); }
            };
          });
        }
        bindX();
      }

      async function jobsPanel() {
        const j = await CM.api.get('/php/jobs');
        const rows = (j.jobs || []).map((x) => Object.assign({}, x, {
          state: x.state === 'running' ? ui.badge('执行中', 'info')
            : x.state === 'done' ? ui.badge('完成', 'ok') : ui.badge('失败', 'bad'),
          startedAt: ageOf(x.startedAt ? new Date(x.startedAt).toISOString() : null),
        }));
        const html = rows.length ? ui.table([
          { k: 'title', label: '任务' },
          { k: 'site', label: '站点', cls: 'mono' },
          { k: 'state', label: '状态' },
          { k: 'startedAt', label: '开始' },
          { k: 'error', label: '错误', render: (v) => fmt.esc(String(v || '').slice(0, 80)) },
          { k: 'id', label: '', render: (v) => `<button class="btn btn-sm" data-j="${fmt.esc(v)}">日志</button>` },
        ], rows) : '<div class="muted small">还没有 PHP 相关任务记录。</div>';
        const mask = await openPanel('任务记录', html, { okText: '关闭', width: '820px' });
        mask.querySelectorAll('[data-j]').forEach((b) => {
          b.onclick = async () => {
            const one = await CM.api.get('/php/job?id=' + encodeURIComponent(b.dataset.j));
            modal.output('任务 ' + b.dataset.j, (one.log || []).join('\n') || '（无日志）');
          };
        });
      }

      /* A panel-style modal with only a close button (for list views). */
      function openPanel(title, html, opts) {
        return new Promise((resolve) => {
          const mask = document.createElement('div');
          mask.className = 'modal-mask';
          mask.innerHTML = `<div class="modal" style="width:${opts.width || '700px'}">
            <div class="modal-head">${fmt.esc(title)}</div>
            <div class="modal-body" style="max-height:68vh;overflow:auto">${html}</div>
            <div class="modal-foot"><button class="btn btn-primary" data-a="close">${fmt.esc(opts.okText || '关闭')}</button></div>
          </div>`;
          document.getElementById('modal-root').appendChild(mask);
          const done = () => { mask.remove(); resolve(mask); };
          mask.querySelector('[data-a=close]').onclick = done;
          mask.addEventListener('click', (e) => { if (e.target === mask) done(); });
          resolve(mask);
        });
      }

      await load();
    },
  });
})();
