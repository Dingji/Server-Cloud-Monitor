/* 运维增强 · 通知渠道：6 类渠道 CRUD（凭据回显脱敏）、测试发送、发送记录。
   凭据永不同显：服务端把 secret 换成 ••••••，保存时带 •••••• 的字段会被剔除（不覆盖）。 */
(function () {
  'use strict';

  const TYPE_FIELDS = {
    wecom: [
      { name: 'cfg.webhook', label: '机器人 webhook 地址', mono: true },
      { name: 'cfg.msgtype', label: '消息格式', type: 'select', options: ['markdown', 'text'], value: 'markdown' },
    ],
    dingtalk: [
      { name: 'cfg.webhook', label: '机器人 webhook 地址', mono: true },
      { name: 'cfg.secret', label: '加签密钥（sec 开头，已存则留空=不改）', secret: true },
      { name: 'cfg.atMobiles', label: '@的手机号，逗号分隔（可空）', mono: true },
    ],
    feishu: [
      { name: 'cfg.webhook', label: '机器人 webhook 地址', mono: true },
      { name: 'cfg.secret', label: '加签密钥（可空）', secret: true },
    ],
    telegram: [
      { name: 'cfg.botToken', label: 'bot token', secret: true },
      { name: 'cfg.chatId', label: 'chat id 或频道名', mono: true },
      { name: 'cfg.apiBase', label: 'API 基础地址（走代理时填）', mono: true },
    ],
    email: [
      { name: 'cfg.host', label: 'SMTP 服务器', mono: true },
      { name: 'cfg.port', label: '端口', mono: true, value: '465' },
      { name: 'cfg.security', label: '加密方式', type: 'select', options: ['ssl', 'starttls'], value: 'ssl' },
      { name: 'cfg.user', label: '登录账号', mono: true },
      { name: 'cfg.password', label: '密码 / 授权码（已存则留空=不改）', secret: true },
      { name: 'cfg.from', label: '发件人显示名（可空）' },
      { name: 'cfg.to', label: '收件人，逗号分隔' },
    ],
    webhook: [
      { name: 'cfg.url', label: '回调 URL', mono: true },
      { name: 'cfg.secret', label: 'HMAC 签名密钥（可空）', secret: true },
      { name: 'cfg.secretHeader', label: '签名头名称（默认 x-cloud-monitor-signature）', mono: true },
    ],
  };
  const typeLabel = (types, key) => (types.find((t) => t.key === key) || {}).label || key;

  registerPage({
    id: 'ops-channels',
    title: '通知渠道',
    desc: 'Webhook(HMAC) · 企微 · 钉钉(加签) · 飞书(加签) · Telegram · 邮件 SMTP · 失败退避重试',
    async mount(root) {
      root.innerHTML = '<div class="card">' + ui.skeleton(5) + '</div>';
      let data = {};

      async function load() {
        const out = {};
        await Promise.all(['/ops/alerts/channels', '/ops/notify/log?limit=60'].map(async (p) => {
          const key = p.split('?')[0].split('/').pop();
          try { out[key] = await CM.api.get(p, 0); } catch (e) { out[key] = { __error: e.message }; }
        }));
        data = out;
        render();
      }

      function render() {
        const ch = data.channels || {}, log = data.log || {};
        if (ch.__error) { root.innerHTML = `<div class="card"><div class="muted">读取失败：${fmt.esc(ch.__error)}（需部署 ops 模块）</div></div>`; return; }
        const types = ch.types || [];
        const items = (ch.items || []).map((x) => Object.assign({}, x, {
          _type: typeLabel(types, x.type),
          _last: x.lastSend ? new Date(x.lastSend).toLocaleString('zh-CN', { hour12: false }) : '—',
          _lastOk: x.lastSend ? (x.lastOk ? ui.badge('成功', 'ok') : `<span title="${fmt.esc(x.lastError || '')}">${ui.badge('失败', 'bad')}</span>`) : '<span class="muted">未发送</span>',
          _sec: Object.keys(x.secretHints || {}).length ? '已存（不回显）' : '无',
        }));
        const logRows = (log.items || []).map((x) => Object.assign({}, x, {
          _t: new Date(x.ts).toLocaleString('zh-CN', { hour12: false }),
          _title: x.title || '',
        }));

        root.innerHTML = `
        <div class="grid g4 mb">
          <div class="card stat"><div class="stat-label">渠道</div><div class="stat-value">${items.length}</div><div class="stat-sub">启用 ${items.filter((x) => x.enabled).length}</div></div>
          <div class="card stat"><div class="stat-label">24h 发送</div><div class="stat-value">${ch.summary ? (ch.summary.sent24h || 0) : '—'}</div><div class="stat-sub">失败 ${ch.summary ? (ch.summary.failed24h || 0) : '—'}</div></div>
          <div class="card stat"><div class="stat-label">渠道类型</div><div class="stat-value">${types.length}</div><div class="stat-sub">${types.map((t) => t.label.split('（')[0]).join(' · ') || '—'}</div></div>
          <div class="card stat"><div class="stat-label">手动群发</div><div class="stat-value" style="font-size:16px">测试 / 通知</div><div class="stat-sub">发送到选定渠道</div></div>
        </div>

        <div class="card mb">
          <div class="card-title">通知渠道 (${items.length})
            <div class="right" style="display:flex;gap:6px">
              <button class="btn btn-sm" id="nc-send">${icon('export', { size: 12 })} 手动通知</button>
              <button class="btn btn-sm btn-primary" id="nc-add">${icon('plus', { size: 12 })} 新建渠道</button>
              <button class="btn btn-sm" id="nc-refresh">${icon('refresh', { size: 12 })} 刷新</button>
            </div>
          </div>
          ${ui.table([
            { k: 'name', label: '名称' },
            { k: '_type', label: '类型' },
            { k: 'enabled', label: '启用', render: (v) => v ? ui.badge('启用', 'ok') : '<span class="muted">停用</span>' },
            { k: '_sec', label: '凭据', cls: 'small' },
            { k: '_lastOk', label: '最近发送' },
            { k: '_last', label: '时间', cls: 'mono small' },
            { k: '', label: '操作', render: (v, x) => `
              <div style="display:flex;gap:4px;flex-wrap:wrap">
                <button class="btn btn-sm" data-act="test" data-id="${fmt.esc(x.id)}">测试</button>
                <button class="btn btn-sm" data-act="toggle" data-id="${fmt.esc(x.id)}">${x.enabled ? '停用' : '启用'}</button>
                <button class="btn btn-sm" data-act="edit" data-id="${fmt.esc(x.id)}">编辑</button>
                <button class="btn btn-sm btn-danger" data-act="del" data-id="${fmt.esc(x.id)}">删除</button>
              </div>` },
          ], items)}
          <div class="small muted" style="padding:8px 16px">凭据保存后永不同显（服务端脱敏为 ••••••）；编辑时留空表示不修改。发送失败自动退避重试 3 次（30s/2min/5min）。</div>
        </div>

        <div class="card">
          <div class="card-title">发送记录（最近 ${logRows.length} 条）</div>
          ${ui.table([
            { k: '_t', label: '时间', cls: 'mono' },
            { k: 'channelName', label: '渠道' },
            { k: 'channelType', label: '类型', cls: 'mono small' },
            { k: 'level', label: '级别', render: (v) => ui.badge(v, v === 'critical' ? 'bad' : v === 'warn' ? 'warn' : '') },
            { k: '_title', label: '内容', cls: 'small', render: (v) => fmt.esc(String(v).slice(0, 90)) },
            { k: 'ok', label: '结果', render: (v, x) => v ? ui.badge('成功', 'ok') : `<span title="${fmt.esc(x.error || '')}">${ui.badge('失败', 'bad')}</span>` },
            { k: 'source', label: '来源', cls: 'mono small' },
          ], logRows, { style: 'max-height:360px' })}
        </div>`;
        bind(types);
      }

      function channelForm(existing, types) {
        const type = existing ? existing.type : (types[0] || {}).key || 'webhook';
        return (async () => {
          const base = [
            { name: 'name', label: '渠道名称', value: (existing && existing.name) || '' },
            { name: 'type', label: '类型', type: 'select', options: types.map((t) => t.key), value: type },
          ];
          const f = await modal.prompt(existing ? '编辑渠道' : '新建渠道', base, { okText: '下一步', width: '560px' });
          if (!f) return null;
          const chosen = f.type;
          const fields = (TYPE_FIELDS[chosen] || TYPE_FIELDS.webhook).map((fd) => ({
            ...fd,
            value: existing && existing.cfg ? (existing.cfg[fd.name.slice(4)] || fd.value || '') : (fd.value || ''),
          }));
          const f2 = await modal.prompt(`渠道配置 · ${typeLabel(types, chosen)}${existing && Object.keys(existing.secretHints || {}).length ? '（已存密钥不回显，留空=不修改）' : ''}`,
            fields, { okText: '保存', width: '560px' });
          if (!f2) return null;
          return { name: f.name, type: f.type, cfg: f2, id: existing ? existing.id : undefined, enabled: existing ? existing.enabled : true };
        })();
      }

      function bind(types) {
        const byId = (id) => (data.channels.items || []).find((x) => x.id === id);
        root.querySelectorAll('[data-act]').forEach((b) => {
          b.onclick = async () => {
            const id = b.dataset.id;
            const item = byId(id);
            try {
              if (b.dataset.act === 'test') {
                const txt = await modal.prompt('测试发送', [{ name: 'text', label: '测试文案（可空=默认）', value: 'Cloud Monitor 测试通知' }], { okText: '发送' });
                if (!txt) return;
                toast('发送中…', 'info');
                const r = await CM.api.post('/ops/alerts/channel/test', { id, text: txt.text });
                const okc = r && r.ok;
                const err = r && r.result && r.result.error;
                toast(okc ? '发送成功' : '发送失败：' + (err || '未知原因'), okc ? 'ok' : 'err');
                await load();
              } else if (b.dataset.act === 'toggle') {
                await CM.api.post('/ops/alerts/channel/save', { id, enabled: !item.enabled }); await load();
              } else if (b.dataset.act === 'del') {
                if (await modal.confirm('删除渠道', `删除 <b class="mono">${fmt.esc(item && item.name || id)}</b>？引用它的规则将退回「仅面板铃铛」。`, { danger: true, okText: '删除' })) {
                  await CM.api.post('/ops/alerts/channel/delete', { id }); await load();
                }
              } else if (b.dataset.act === 'edit') {
                const built = await channelForm(item, types);
                if (!built) return;
                await CM.api.post('/ops/alerts/channel/save', built);
                toast('已保存', 'ok'); await load();
              }
            } catch (e) { toast(e.message, 'err'); }
          };
        });
        const add = document.getElementById('nc-add');
        if (add) add.onclick = async () => {
          const built = await channelForm(null, types);
          if (!built) return;
          try { await CM.api.post('/ops/alerts/channel/save', built); toast('已创建', 'ok'); await load(); }
          catch (e) { toast(e.message, 'err'); }
        };
        const send = document.getElementById('nc-send');
        if (send) send.onclick = async () => {
          const f = await modal.prompt('手动通知', [
            { name: 'level', label: '级别', type: 'select', options: ['info', 'warn', 'critical'], value: 'warn' },
            { name: 'title', label: '标题', value: 'Cloud Monitor 手动通知' },
            { name: 'text', label: '正文（支持多行）' },
            { name: 'channelIds', label: '渠道 id（逗号分隔，空=全部启用渠道）' },
          ], { okText: '发送', width: '560px' });
          if (!f) return;
          try {
            await CM.api.post('/ops/notify/send', {
              level: f.level, title: f.title, text: f.text,
              channelIds: String(f.channelIds || '').split(/[\s,]+/).filter(Boolean),
            });
            toast('已提交发送', 'ok'); await load();
          } catch (e) { toast(e.message, 'err'); }
        };
        const rf = document.getElementById('nc-refresh');
        if (rf) rf.onclick = () => load();
      }

      await load();
    },
  });
})();
