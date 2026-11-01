/* NoteBox frontend */
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

  const state = {
    view: "notes",
    role: "user",
    notes: [],
    currentNoteId: null,
    noteMode: "edit",
    noteSaveTimer: null,
    todos: [],
    todoFilter: "today",
    settings: {},
    stats: {},
    user: "",
    editingTodoId: null,
    sharePanelOpen: false,
    noteShares: [],
    users: [],
    editingUserId: null,
  };

  /* ---------------- helpers ---------------- */

  function nbPath(path) {
    if (!path || path[0] !== "/") return path;
    const meta = document.querySelector('meta[name="notebox-base"]');
    const base = meta ? String(meta.getAttribute("content") || "").replace(/\/$/, "") : "";
    return base + path;
  }

  async function api(path, opts = {}) {
    const init = Object.assign({ credentials: "same-origin" }, opts);
    init.headers = Object.assign({}, init.headers);
    if (init.body instanceof FormData) {
      // let the browser set multipart boundary
    } else if (init.body && typeof init.body !== "string") {
      init.body = JSON.stringify(init.body);
      init.headers["Content-Type"] = "application/json";
    } else if (typeof init.body === "string") {
      init.headers["Content-Type"] = "application/json";
    }
    const res = await fetch(nbPath(path), init);
    const ct = res.headers.get("content-type") || "";
    const data = ct.includes("application/json") ? await res.json() : null;
    if (res.status === 401 && path !== "/api/login") {
      showLogin();
      const e = new Error("登录已过期，请重新登录");
      e.auth = true;
      throw e;
    }
    if (!res.ok) {
      throw new Error((data && data.error) || "请求失败 (" + res.status + ")");
    }
    return data;
  }

  let toastTimer = null;
  function toast(msg, type) {
    const el = $("#toast");
    el.innerHTML = msg;
    // restart entrance animation even for repeated messages
    el.classList.remove("show");
    void el.offsetWidth;
    el.className = "toast show " + (type || "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove("show"), 3200);
  }
  function fail(e) {
    console.error(e);
    toast(esc(e.message || String(e)), "err");
  }

  function localDateStr(d) {
    const p = (n) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
  }
  function fmtTime(ts) {
    return new Date(ts * 1000).toLocaleString("zh-CN", { hour12: false });
  }
  function fmtShortTime(ts) {
    return new Date(ts * 1000).toLocaleString("zh-CN", { hour12: false, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
  }
  function stripMd(s) {
    return String(s || "")
      .replace(/```[\s\S]*?```/g, " 〔代码〕 ")
      .replace(/!\[[^\]]*\]\([^)]*\)/g, " 〔图片〕 ")
      .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
      .replace(/[#>*_`~|-]+/g, " ")
      .replace(/\s+/g, " ")
      .trim();
  }
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }
  function debounce(fn, ms) {
    let t = null;
    return function () {
      const args = arguments;
      clearTimeout(t);
      t = setTimeout(() => fn.apply(null, args), ms);
    };
  }

  /* ---------------- auth & boot ---------------- */

  function showLogin() {
    $("#app").classList.add("hidden");
    $("#login-screen").classList.remove("hidden");
  }
  function showApp() {
    $("#login-screen").classList.add("hidden");
    $("#app").classList.remove("hidden");
  }

  async function initApp(bootstrap) {
    state.user = bootstrap.user;
    state.role = bootstrap.role || "user";
    state.settings = bootstrap.settings;
    state.stats = bootstrap.stats;
    $("#who").textContent = state.user + " @ NoteBox";
    applyMailState();
    applyRoleUI();
    showApp();
    await Promise.all([loadNotes(), loadTodos()]);
    setView(state.view);
  }

  /* admins see mail + users; everyone else gets a reduced shell */
  function applyRoleUI() {
    const isAdmin = state.role === "admin";
    $$("[data-role='admin']").forEach((el) => el.classList.toggle("hidden", !isAdmin));
    if (!isAdmin && (state.view === "mail" || state.view === "users")) {
      state.view = "notes";
    }
  }

  /* ---- auth screen: login <-> register <-> done ---- */
  function showAuthPane(which) {
    $("#login-form").classList.toggle("hidden", which !== "login");
    $("#register-form").classList.toggle("hidden", which !== "register");
    $("#register-done").classList.toggle("hidden", which !== "done");
    $("#auth-banner").classList.add("hidden");
    $("#login-error").classList.add("hidden");
    $("#reg-error").classList.add("hidden");
    $("#btn-resend").classList.add("hidden");
  }
  $("#link-to-register").addEventListener("click", (e) => { e.preventDefault(); showAuthPane("register"); });
  $("#link-to-login").addEventListener("click", (e) => { e.preventDefault(); showAuthPane("login"); });
  $("#btn-back-login").addEventListener("click", () => showAuthPane("login"));

  async function resendVerification(email) {
    try {
      const r = await api("/api/resend-verification", { method: "POST", body: { email: email } });
      toast(icon("mail") + " " + (r.message || "已尝试重新发送"), "ok");
    } catch (err) { fail(err); }
  }
  $("#btn-resend").addEventListener("click", () => resendVerification($("#login-user").value.trim()));
  $("#btn-resend2").addEventListener("click", () => resendVerification($("#reg-email").value.trim() || state.lastRegisterEmail || ""));

  $("#register-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const email = $("#reg-email").value.trim();
    const pass = $("#reg-pass").value;
    const pass2 = $("#reg-pass2").value;
    const err = $("#reg-error");
    if (pass !== pass2) {
      err.textContent = "两次输入的密码不一致";
      err.classList.remove("hidden");
      const card = $("#register-form");
      card.classList.remove("shake");
      void card.offsetWidth;
      card.classList.add("shake");
      return;
    }
    if (pass.length < 8) {
      err.textContent = "密码至少需要 8 位";
      err.classList.remove("hidden");
      return;
    }
    const btn = e.target.querySelector("button[type=submit]");
    const oldHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = icon("spinner") + "注册中…";
    try {
      const r = await api("/api/register", { method: "POST", body: { email: email, password: pass } });
      state.lastRegisterEmail = email;
      $("#done-msg").textContent = r.message || "请查收验证邮件";
      $("#reg-pass").value = "";
      $("#reg-pass2").value = "";
      showAuthPane("done");
      $("#auth-banner").classList.add("hidden");
    } catch (err2) {
      err.textContent = err2.message;
      err.classList.remove("hidden");
    } finally {
      btn.disabled = false;
      btn.innerHTML = oldHtml;
    }
  });

  /* verified / verify_error deep-link banners */
  (function authBanner() {
    const qp = new URLSearchParams(location.search);
    const banner = $("#auth-banner");
    if (qp.has("verified")) {
      banner.textContent = "邮箱验证成功，账号已启用，请登录。";
      banner.className = "auth-banner ok";
    } else if (qp.has("verify_error")) {
      banner.textContent = "验证链接无效或已过期（有效期 24 小时），请重新发送验证邮件。";
      banner.className = "auth-banner err";
    } else {
      return;
    }
    showAuthPane("login");
    $("#auth-banner").classList.remove("hidden");
    history.replaceState({}, "", location.pathname);
  })();

  $("#login-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = e.target.querySelector("button[type=submit]");
    const oldHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = icon("spinner") + "登录中…";
    try {
      await api("/api/login", {
        method: "POST",
        body: { username: $("#login-user").value.trim(), password: $("#login-pass").value },
      });
      $("#login-error").classList.add("hidden");
      $("#btn-resend").classList.add("hidden");
      const boot = await api("/api/bootstrap");
      $("#login-pass").value = "";
      initApp(boot);
    } catch (err) {
      const el = $("#login-error");
      el.textContent = err.message;
      el.classList.remove("hidden");
      // unverified mailbox -> offer to resend the verification mail
      $("#btn-resend").classList.toggle("hidden", !/验证/.test(err.message || ""));
      const card = $("#login-form");
      card.classList.remove("shake");
      void card.offsetWidth; // replay animation
      card.classList.add("shake");
    } finally {
      btn.disabled = false;
      btn.innerHTML = oldHtml;
    }
  });

  $("#btn-logout").addEventListener("click", async () => {
    try { await api("/api/logout", { method: "POST" }); } catch (e) {}
    showLogin();
    showAuthPane("login");
  });

  /* ---------------- view router ---------------- */

  function setView(name) {
    if ((name === "mail" || name === "users") && state.role !== "admin") name = "notes";
    state.view = name;
    $$(".nav-item").forEach((b) => b.classList.toggle("active", b.dataset.view === name));
    $$(".view").forEach((v) => v.classList.toggle("hidden", v.id !== "view-" + name));
    if (name === "mail") {
      fillMailForm();
      loadMailLog();
    }
    if (name === "settings") loadServerInfo();
    if (name === "todos") updateTabSlider();
    if (name === "users") loadUsers();
  }

  /* sliding indicator under the active todo tab */
  function updateTabSlider() {
    const wrap = $("#todo-tabs");
    const active = $("#todo-tabs .tab.active");
    const slider = $("#tab-slider");
    if (!wrap || !active || !slider) return;
    const w = active.offsetWidth;
    if (w === 0 || active.offsetTop - wrap.offsetTop > 2) {
      slider.classList.remove("ready"); // hidden view or wrapped rows
      return;
    }
    slider.style.width = w - 6 + "px";
    slider.style.transform = "translateX(" + (active.offsetLeft + 3) + "px)";
    slider.classList.add("ready");
  }
  window.addEventListener("resize", debounce(updateTabSlider, 120));
  $$(".nav-item").forEach((b) => b.addEventListener("click", () => setView(b.dataset.view)));

  async function refreshStats() {
    try {
      state.stats = await api("/api/stats");
      renderBadges();
    } catch (e) {}
  }
  function renderBadges() {
    const s = state.stats || {};
    $("#badge-notes").textContent = s.notes != null ? s.notes : "";
    const ob = $("#badge-overdue");
    if (s.overdue > 0) {
      ob.textContent = s.overdue + " 过期";
      ob.classList.remove("hidden");
    } else {
      ob.classList.add("hidden");
    }
  }
  function applyMailState() {
    const ready = state.settings && state.settings.smtp_ready;
    $("#mail-dot").classList.toggle("on", !ready);
    const pill = $("#smtp-state");
    if (pill) {
      pill.innerHTML = ready ? icon("checkCircle") + " SMTP 已配置" : icon("warn") + " SMTP 未配置（先填账号授权码）";
      pill.className = "pill " + (ready ? "ok" : "warn");
    }
  }

  /* ---------------- notes ---------------- */

  async function loadNotes() {
    try {
      const q = $("#note-search").value.trim();
      const tag = $("#note-tag").value;
      state.notes = await api("/api/notes?q=" + encodeURIComponent(q) + "&tag=" + encodeURIComponent(tag));
      renderNoteList();
      if (state.currentNoteId != null && !state.notes.some((n) => n.id === state.currentNoteId)) {
        state.currentNoteId = null;
        renderNoteEditor();
      } else if (state.currentNoteId != null) {
        renderNoteEditor();
      }
      await loadTags();
      renderBadgesFromNotes();
    } catch (e) { if (!e.auth) fail(e); }
  }
  function renderBadgesFromNotes() {
    $("#badge-notes").textContent = state.notes.length;
  }
  async function loadTags() {
    try {
      const tags = await api("/api/tags");
      const sel = $("#note-tag");
      const cur = sel.value;
      sel.innerHTML = '<option value="">全部标签</option>' + tags.map((t) => '<option value="' + esc(t) + '">' + esc(t) + "</option>").join("");
      if (tags.includes(cur)) sel.value = cur;
    } catch (e) {}
  }

  /* ---- share helpers ---- */
  const PERM_TEXT = { read: "只读", edit: "可编辑", admin: "管理" };

  function noteAccess(n) {
    if (!n) return { perm: null, canEdit: false, canManage: false };
    const perm = n.my_permission || null; // null => owner (or admin role)
    return {
      perm,
      canEdit: !perm || perm !== "read",
      canManage: !perm || perm === "admin",
    };
  }

  function renderNoteList() {
    const box = $("#note-list");
    if (!state.notes.length) {
      box.innerHTML = '<div class="empty-list">还没有笔记<br>点右上角「新建笔记」开始</div>';
      return;
    }
    box.innerHTML = state.notes
      .map((n) => {
        const acc = noteAccess(n);
        const ownerChip = n.is_owner
          ? ""
          : '<span class="chip owner-chip" title="共享来源">' + esc(n.owner_email || "?") +
            (acc.perm ? " · " + PERM_TEXT[acc.perm] : "") + "</span>";
        return (
          '<div class="note-item' + (n.id === state.currentNoteId ? " active" : "") + (n.is_owner ? "" : " shared") + '" data-id="' + n.id + '">' +
          '<div class="n-title">' + (n.pinned && n.is_owner ? icon("pin", "on") : "") + "<span>" + esc(n.title || "未命名笔记") + "</span></div>" +
          '<div class="n-snippet">' + esc(stripMd(n.content).slice(0, 140) || "（空笔记）") + "</div>" +
          '<div class="n-meta">' + ownerChip +
          (n.tags ? n.tags.split(",").filter(Boolean).map((t) => '<span class="chip">' + esc(t.trim()) + "</span>").join("") : "") +
          '<span class="time">' + fmtShortTime(n.updated_at) + "</span>" +
          "</div></div>"
        );
      })
      .join("");
    $$(".note-item", box).forEach((el) => el.addEventListener("click", () => selectNote(Number(el.dataset.id))));
  }

  function currentNote() {
    return state.notes.find((n) => n.id === state.currentNoteId) || null;
  }

  function renderNoteEditor() {
    const n = currentNote();
    $("#note-empty").classList.toggle("hidden", !!n);
    $("#note-form").classList.toggle("hidden", !n);
    if (!n) return;
    if (document.activeElement !== $("#note-title")) $("#note-title").value = n.title || "";
    if (document.activeElement !== $("#note-content")) $("#note-content").value = n.content || "";
    if (document.activeElement !== $("#note-tags")) $("#note-tags").value = n.tags || "";

    const acc = noteAccess(n);
    $("#note-title").readOnly = !acc.canEdit;
    $("#note-content").readOnly = !acc.canEdit;
    $("#note-tags").readOnly = !acc.canEdit;
    $("#note-form").classList.toggle("readonly", !acc.canEdit);
    $("#editor-toolbar").classList.toggle("hidden", !acc.canEdit);
    $("#note-pin").classList.toggle("hidden", !acc.canManage);
    $("#note-pin").classList.toggle("on", !!n.pinned);
    $("#note-delete").classList.toggle("hidden", !acc.canManage);
    $("#note-share").classList.toggle("hidden", !acc.canManage);
    const badge = $("#share-badge");
    if (acc.perm) {
      badge.classList.remove("hidden");
      badge.textContent = "来自 " + (n.owner_email || "?") + " · " + PERM_TEXT[acc.perm];
      badge.className = "pill share-badge perm-" + acc.perm;
    } else {
      badge.classList.add("hidden");
      badge.className = "pill share-badge hidden";
    }
    if (!acc.canManage) {
      state.sharePanelOpen = false;
      state.noteShares = [];
    }
    renderShareBar();
    if (acc.canManage && n) loadShares(n.id);
    applyNoteMode();
    $("#note-saved").textContent = acc.canEdit ? "已保存" : "只读";
    $("#note-saved").className = "saved-ind";
  }

  function selectNote(id, mode) {
    state.currentNoteId = id;
    state.noteMode = mode || "edit";
    renderNoteList();
    renderNoteEditor();
  }

  function applyNoteMode() {
    const editing = state.noteMode === "edit";
    $("#note-content").classList.toggle("hidden", !editing);
    $("#note-preview").classList.toggle("hidden", editing);
    $("#note-mode").textContent = editing ? "预览" : "编辑";
    if (!editing) {
      const n = currentNote();
      $("#note-preview").innerHTML = md.render(n ? n.content : "");
    }
  }

  async function newNote() {
    try {
      const n = await api("/api/notes", { method: "POST", body: { title: "未命名笔记", content: "", tags: "" } });
      state.notes.unshift(n);
      $("#note-search").value = "";
      $("#note-tag").value = "";
      state.currentNoteId = n.id;
      state.noteMode = "edit";
      renderNoteList();
      renderNoteEditor();
      const t = $("#note-title");
      t.focus();
      t.select();
      refreshStats();
    } catch (e) { fail(e); }
  }

  async function saveCurrentNote() {
    const n = currentNote();
    if (!n) return;
    if (!noteAccess(n).canEdit) return; // read-only shares never save
    const ind = $("#note-saved");
    ind.innerHTML = icon("spinner") + "保存中…";
    ind.className = "saved-ind saving";
    try {
      const payload = {
        title: $("#note-title").value,
        content: $("#note-content").value,
        tags: $("#note-tags").value,
        pinned: n.pinned,
      };
      const updated = await api("/api/notes/" + n.id, { method: "PUT", body: payload });
      Object.assign(n, updated);
      ind.textContent = "已保存";
      ind.className = "saved-ind";
      const item = $('.note-item[data-id="' + n.id + '"]');
      if (item) {
        item.querySelector(".n-title").innerHTML = (n.pinned ? icon("pin", "on") : "") + "<span>" + esc(n.title || "未命名笔记") + "</span>";
        item.querySelector(".n-snippet").textContent = stripMd(n.content).slice(0, 140) || "（空笔记）";
      }
    } catch (e) {
      ind.innerHTML = icon("crossCircle") + "保存失败";
      ind.className = "saved-ind err";
      fail(e);
    }
  }
  const saveNoteDebounced = debounce(saveCurrentNote, 900);

  ["input"].forEach((ev) => {
    $("#note-title").addEventListener(ev, saveNoteDebounced);
    $("#note-content").addEventListener(ev, saveNoteDebounced);
    $("#note-tags").addEventListener(ev, saveNoteDebounced);
  });
  $("#note-content").addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "s") {
      e.preventDefault();
      clearTimeout(state.noteSaveTimer);
      saveCurrentNote();
    }
  });
  $("#btn-new-note").addEventListener("click", newNote);
  $("#note-mode").addEventListener("click", () => {
    state.noteMode = state.noteMode === "edit" ? "preview" : "edit";
    if (state.noteMode === "preview") saveCurrentNote();
    applyNoteMode();
  });
  $("#note-pin").addEventListener("click", async () => {
    const n = currentNote();
    if (!n) return;
    try {
      const updated = await api("/api/notes/" + n.id, { method: "PUT", body: { pinned: n.pinned ? 0 : 1 } });
      Object.assign(n, updated);
      renderNoteList();
      renderNoteEditor();
      toast(n.pinned ? "已置顶" : "已取消置顶");
    } catch (e) { fail(e); }
  });
  $("#note-delete").addEventListener("click", async () => {
    const n = currentNote();
    if (!n) return;
    if (!confirm("删除笔记「" + n.title + "」？此操作不可恢复。")) return;
    try {
      await api("/api/notes/" + n.id, { method: "DELETE" });
      state.notes = state.notes.filter((x) => x.id !== n.id);
      state.currentNoteId = state.notes.length ? state.notes[0].id : null;
      renderNoteList();
      renderNoteEditor();
      refreshStats();
      toast("已删除笔记");
    } catch (e) { fail(e); }
  });
  $("#note-search").addEventListener("input", debounce(loadNotes, 300));
  $("#note-tag").addEventListener("change", loadNotes);

  /* ---------------- editor toolbar: formatting, media, share ---------------- */

  function ta() {
    return $("#note-content");
  }
  function insertAtCursor(text, cursorOffset) {
    const el = ta();
    const s = el.selectionStart;
    el.setRangeText(text, s, el.selectionEnd, "end");
    if (cursorOffset != null) el.setSelectionRange(s + cursorOffset, s + cursorOffset);
    el.dispatchEvent(new Event("input", { bubbles: true }));
  }
  function wrapSel(pre, post, ph) {
    const el = ta();
    const s = el.selectionStart, e = el.selectionEnd;
    const sel = el.value.slice(s, e) || ph;
    el.setRangeText(pre + sel + post, s, e, "end");
    el.setSelectionRange(s + pre.length, s + pre.length + sel.length);
    el.dispatchEvent(new Event("input", { bubbles: true }));
  }
  function toggleLinePrefix(prefix) {
    const el = ta();
    const s = el.selectionStart;
    const val = el.value;
    const lineStart = val.lastIndexOf("\n", s - 1) + 1;
    const nl = val.indexOf("\n", s);
    const lineEnd = nl === -1 ? val.length : nl;
    const line = val.slice(lineStart, lineEnd);
    let next, cursor;
    if (line.startsWith(prefix)) {
      next = val.slice(0, lineStart) + line.slice(prefix.length) + val.slice(lineEnd);
      cursor = Math.max(lineStart, s - prefix.length);
    } else {
      next = val.slice(0, lineStart) + prefix + line + val.slice(lineEnd);
      cursor = s + prefix.length;
    }
    el.value = next;
    el.setSelectionRange(cursor, cursor);
    el.dispatchEvent(new Event("input", { bubbles: true }));
  }

  async function copyText(text) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (e) { /* fall through */ }
    const t = document.createElement("textarea");
    t.value = text;
    t.style.position = "fixed";
    t.style.opacity = "0";
    document.body.appendChild(t);
    t.select();
    let ok = false;
    try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
    document.body.removeChild(t);
    return ok;
  }

  async function uploadAsset(file) {
    const fd = new FormData();
    fd.append("file", file);
    return api("/api/assets", { method: "POST", body: fd });
  }

  async function insertFiles(files) {
    const list = Array.from(files || []).filter((f) => f.size > 0);
    if (!list.length) return;
    const n0 = currentNote();
    if (!n0 || !noteAccess(n0).canEdit) return;
    toast(icon("spinner") + " 上传中…（" + list.length + " 个文件）");
    for (const f of list) {
      try {
        const r = await uploadAsset(f);
        const label = (f.name || "media").replace(/[[\]()]/g, "");
        if (r.kind === "image") insertAtCursor("\n![" + label + "](" + r.url + ")\n");
        else insertAtCursor("\n[" + label + "](" + r.url + ")\n");
      } catch (e) {
        fail(e);
        return;
      }
    }
    toast(icon("checkCircle") + " 已插入 " + list.length + " 个文件", "ok");
    saveNoteDebounced();
  }

  $("#editor-toolbar").addEventListener("click", (e) => {
    const btn = e.target.closest(".tb");
    if (!btn || !currentNote()) return;
    const cmd = btn.dataset.cmd;
    switch (cmd) {
      case "h1": toggleLinePrefix("# "); break;
      case "h2": toggleLinePrefix("## "); break;
      case "ul": toggleLinePrefix("- "); break;
      case "ol": toggleLinePrefix("1. "); break;
      case "task": toggleLinePrefix("- [ ] "); break;
      case "quote": toggleLinePrefix("> "); break;
      case "bold": wrapSel("**", "**", "加粗"); break;
      case "italic": wrapSel("*", "*", "斜体"); break;
      case "strike": wrapSel("~~", "~~", "删除线"); break;
      case "inline-code": wrapSel("`", "`", "代码"); break;
      case "hr": insertAtCursor("\n\n---\n\n"); break;
      case "table": insertAtCursor("\n| 列A | 列B |\n| --- | --- |\n|  |  |\n"); break;
      case "codeblock": insertAtCursor("\n```\n\n```\n", "\n```\n".length + 1); break;
      case "link": {
        const url = prompt("链接地址", "https://");
        if (url) wrapSel("[", "](" + url + ")", "链接文字");
        break;
      }
      case "image": $("#asset-image").click(); break;
      case "video": $("#asset-video").click(); break;
    }
  });
  $("#asset-image").addEventListener("change", (e) => {
    insertFiles(e.target.files);
    e.target.value = "";
  });
  $("#asset-video").addEventListener("change", (e) => {
    insertFiles(e.target.files);
    e.target.value = "";
  });

  // paste / drop media straight into the note; rich text (HTML) becomes Markdown
  const noteTa = $("#note-content");
  async function convertPastedHtml(html) {
    try {
      toast(icon("spinner") + " 正在转换粘贴的富文本…");
      const r = await api("/api/convert/html", { method: "POST", body: { html: html } });
      const md = (r.markdown || "").trim();
      if (!md) return;
      insertAtCursor("\n" + md + "\n");
      toast(icon("checkCircle") + " 已转换为可编辑的 Markdown", "ok");
      saveNoteDebounced();
    } catch (e) {
      fail(e);
    }
  }
  noteTa.addEventListener("paste", (e) => {
    const files = e.clipboardData && e.clipboardData.files;
    if (files && files.length) {
      e.preventDefault();
      insertFiles(files);
      return;
    }
    const html = e.clipboardData && e.clipboardData.getData("text/html");
    if (html && /<(p|div|ul|ol|table|h[1-6]|blockquote|pre|strong|em|b|i|a|img|section|article|span)\b/i.test(html)) {
      e.preventDefault();
      convertPastedHtml(html);
    }
  });
  noteTa.addEventListener("dragover", (e) => e.preventDefault());
  noteTa.addEventListener("drop", (e) => {
    e.preventDefault();
    if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) insertFiles(e.dataTransfer.files);
  });

  /* share: public link + per-user grants (read / edit / admin) */
  function shareUrlOf(code) {
    return location.origin + "/s/" + code;
  }

  function renderShareBar() {
    const n = currentNote();
    const panel = $("#share-panel");
    const acc = noteAccess(n);
    const show = !!(n && acc.canManage && state.sharePanelOpen);
    panel.classList.toggle("hidden", !show);
    $("#note-share").classList.toggle("on", show);
    if (!n) return;
    const hasCode = !!n.share_code;
    $("#public-row").classList.toggle("hidden", !hasCode);
    $("#public-on").classList.toggle("hidden", hasCode);
    if (hasCode) {
      const url = shareUrlOf(n.share_code);
      const a = $("#share-link");
      a.textContent = url;
      a.href = url;
    }
    renderShareUserList();
  }

  function renderShareUserList() {
    const box = $("#share-user-list");
    const list = state.noteShares || [];
    if (!list.length) {
      box.innerHTML = '<div class="share-empty">还没有共享给其他用户</div>';
      return;
    }
    const n = currentNote();
    box.innerHTML = list
      .map(
        (s) =>
          '<div class="share-row" data-sid="' + s.id + '">' +
          '<span class="ico-slot" data-ico="user"></span><span class="share-email">' + esc(s.email) + "</span>" +
          '<select class="input select share-perm-sel">' +
          ["read", "edit", "admin"]
            .map((p) => '<option value="' + p + '"' + (s.permission === p ? " selected" : "") + ">" + PERM_TEXT[p] + "</option>")
            .join("") +
          "</select>" +
          '<button class="btn ghost small danger share-del" type="button">移除</button>' +
          "</div>"
      )
      .join("");
    if (window.hydrateIcons) window.hydrateIcons(box);
    $$(".share-perm-sel", box).forEach((sel) =>
      sel.addEventListener("change", async () => {
        const sid = Number(sel.closest(".share-row").dataset.sid);
        try {
          await api("/api/notes/" + n.id + "/shares/" + sid, { method: "PUT", body: { permission: sel.value } });
          toast(icon("checkCircle") + " 权限已改为「" + PERM_TEXT[sel.value] + "」", "ok");
          loadShares(n.id);
        } catch (e) { fail(e); }
      })
    );
    $$(".share-del", box).forEach((btn) =>
      btn.addEventListener("click", async () => {
        const sid = Number(btn.closest(".share-row").dataset.sid);
        if (!confirm("取消对该用户的共享？")) return;
        try {
          await api("/api/notes/" + n.id + "/shares/" + sid, { method: "DELETE" });
          toast("已移除共享");
          loadShares(n.id);
        } catch (e) { fail(e); }
      })
    );
  }

  async function loadShares(nid) {
    try {
      const r = await api("/api/notes/" + nid + "/shares");
      if (state.currentNoteId === nid) {
        state.noteShares = r;
        renderShareUserList();
      }
    } catch (e) {
      if (!e.auth) console.warn(e);
    }
  }

  $("#note-share").addEventListener("click", () => {
    const n = currentNote();
    if (!n || !noteAccess(n).canManage) return;
    state.sharePanelOpen = !state.sharePanelOpen;
    renderShareBar();
  });

  $("#public-on").addEventListener("click", async () => {
    const n = currentNote();
    if (!n) return;
    try {
      const r = await api("/api/notes/" + n.id + "/share", { method: "POST" });
      n.share_code = r.share_code;
      renderShareBar();
      const copied = await copyText(shareUrlOf(r.share_code));
      toast(icon("checkCircle") + " 公开链接已开启" + (copied ? "，已复制" : ""), "ok");
    } catch (e) { fail(e); }
  });

  $("#share-copy").addEventListener("click", async () => {
    const n = currentNote();
    if (!n || !n.share_code) return;
    const copied = await copyText(shareUrlOf(n.share_code));
    toast(copied ? icon("checkCircle") + " 链接已复制" : icon("crossCircle") + " 复制失败，请手动复制", copied ? "ok" : "err");
  });

  $("#share-off").addEventListener("click", async () => {
    const n = currentNote();
    if (!n || !n.share_code) return;
    try {
      await api("/api/notes/" + n.id + "/unshare", { method: "POST" });
      n.share_code = null;
      renderShareBar();
      toast("已停止公开分享");
    } catch (e) { fail(e); }
  });

  $("#share-add").addEventListener("click", async () => {
    const n = currentNote();
    if (!n) return;
    const email = $("#share-email").value.trim();
    if (!email) {
      toast(icon("warn") + " 请填写对方邮箱", "err");
      return;
    }
    const perm = $("#share-perm").value;
    try {
      const r = await api("/api/notes/" + n.id + "/shares", { method: "POST", body: { email: email, permission: perm } });
      state.noteShares = r.shares;
      $("#share-email").value = "";
      renderShareUserList();
      toast(icon("checkCircle") + " 已共享给 " + email + "（" + PERM_TEXT[perm] + "）", "ok");
    } catch (e) { fail(e); }
  });

  /* ---------------- todos ---------------- */

  async function loadTodos() {
    try {
      const q = $("#todo-search").value.trim();
      state.todos = await api("/api/todos?q=" + encodeURIComponent(q));
      renderTodos();
      refreshStats();
    } catch (e) { if (!e.auth) fail(e); }
  }

  function dueInfo(t) {
    const now = Math.floor(Date.now() / 1000);
    const today = localDateStr(new Date());
    const tomorrow = localDateStr(new Date(Date.now() + 86400000));
    if (t.due_at) {
      const d = new Date(t.due_at * 1000);
      const ds = localDateStr(d);
      const hm = d.getHours().toString().padStart(2, "0") + ":" + d.getMinutes().toString().padStart(2, "0");
      let label;
      if (ds === today) label = "今天 " + hm;
      else if (ds === tomorrow) label = "明天 " + hm;
      else label = (d.getMonth() + 1) + "-" + d.getDate().toString().padStart(2, "0") + " " + hm;
      return { late: t.status === "pending" && t.due_at < now, today: ds === today, label: label, key: ds, hasDue: true };
    }
    if (t.due_date) {
      let label = t.due_date;
      if (t.due_date === today) label = "今天";
      else if (t.due_date === tomorrow) label = "明天";
      return { late: t.status === "pending" && t.due_date < today, today: t.due_date === today, label: label, key: t.due_date, hasDue: true };
    }
    return { late: false, today: false, label: "", key: "", hasDue: false };
  }

  function todoMatchesFilter(t, f) {
    const info = dueInfo(t);
    if (f === "done") return t.status === "done";
    if (t.status === "done") return f === "all";
    if (f === "inbox" || f === "all") return true;
    if (f === "today") return info.late || info.today;
    if (f === "upcoming") return info.hasDue && !info.late && !info.today;
    return true;
  }

  function groupOf(t, f) {
    const info = dueInfo(t);
    if (t.status === "done") return "已完成";
    if (info.late) return "已过期";
    if (f === "today") return "今天";
    if (info.today) return "今天";
    if (!info.hasDue) return "未安排";
    if (f === "upcoming") return info.key;
    if (info.key === localDateStr(new Date(Date.now() + 86400000))) return "明天";
    return "以后";
  }

  const PRI_LABEL = { 3: "高", 2: "中", 1: "低", 0: "无" };

  function renderTodos() {
    const f = state.todoFilter;
    const list = state.todos.filter((t) => todoMatchesFilter(t, f));
    const box = $("#todo-list");
    if (!list.length) {
      box.innerHTML = '<div class="empty-list">' + icon("sparkle", "big") + "<br>" +
        (f === "done" ? "还没有完成的事项" : "这里空空如也") + "</div>";
    } else {
      const order = ["已过期", "今天", "明天", "未安排", "以后", "已完成"];
      const groups = {};
      list.forEach((t) => {
        const g = groupOf(t, f);
        (groups[g] = groups[g] || []).push(t);
      });
      let html = "";
      const keys = Object.keys(groups).sort((a, b) => {
        const ia = order.indexOf(a), ib = order.indexOf(b);
        if (ia !== -1 && ib !== -1) return ia - ib;
        if (ia !== -1) return -1;
        if (ib !== -1) return 1;
        return a < b ? -1 : 1;
      });
      keys.forEach((g) => {
        html += '<div class="group-head' + (g === "已过期" ? " red" : "") + '">' + esc(g) + " · " + groups[g].length + "</div>";
        groups[g].forEach((t) => {
          const info = dueInfo(t);
          const dueCls = info.late ? " late" : info.today ? " today" : "";
          html +=
            '<div class="todo-item' + (t.status === "done" ? " done" : "") + '" data-id="' + t.id + '">' +
            '<input type="checkbox" class="cb" ' + (t.status === "done" ? "checked" : "") + ">" +
            '<div class="todo-body"><div class="todo-title">' + esc(t.title) + "</div>" +
            '<div class="todo-meta">' +
            (info.hasDue ? '<span class="due' + dueCls + '">' + icon("calendar") + ' ' + esc(info.label) + "</span>" : "") +
            '<span class="pri pri-' + t.priority + '">' + PRI_LABEL[t.priority] + "</span>" +
            (t.remind && info.hasDue && t.status === "pending" ? '<span class="mail-ic" title="到期邮件提醒">' + icon("mail") + '</span>' : "") +
            (t.detail ? '<span class="mail-ic" title="' + esc(t.detail.slice(0, 200)) + '">' + icon("note") + '</span>' : "") +
            "</div></div>" +
            '<button class="btn ghost small danger todo-del" title="删除">' + icon("trash") + '</button>' +
            "</div>";
        });
      });
      box.innerHTML = html;
      $$(".todo-item", box).forEach((el) => {
        const id = Number(el.dataset.id);
        el.querySelector(".cb").addEventListener("change", () => toggleTodo(id));
        el.querySelector(".todo-body").addEventListener("click", () => openEditTodo(id));
        el.querySelector(".todo-del").addEventListener("click", () => deleteTodo(id));
      });
    }
    const pending = state.todos.filter((t) => t.status === "pending").length;
    const done = state.todos.filter((t) => t.status === "done").length;
    $("#todo-counts").textContent = "未完成 " + pending + " · 已完成 " + done;
  }

  $$("#todo-tabs .tab").forEach((tab) =>
    tab.addEventListener("click", () => {
      state.todoFilter = tab.dataset.filter;
      $$("#todo-tabs .tab").forEach((t) => t.classList.toggle("active", t === tab));
      updateTabSlider();
      renderTodos();
    })
  );
  $("#todo-search").addEventListener("input", debounce(loadTodos, 300));

  function quickDuePayload() {
    const date = $("#qa-date").value;
    const time = $("#qa-time").value;
    if (date && time) {
      return { due_at: Math.floor(new Date(date + "T" + time).getTime() / 1000), due_date: null };
    }
    if (date) return { due_at: null, due_date: date };
    if (time) {
      // time only -> today at that time
      const today = localDateStr(new Date());
      return { due_at: Math.floor(new Date(today + "T" + time).getTime() / 1000), due_date: null };
    }
    return { due_at: null, due_date: null };
  }

  $("#quick-add").addEventListener("submit", async (e) => {
    e.preventDefault();
    const title = $("#qa-title").value.trim();
    if (!title) return;
    try {
      const due = quickDuePayload();
      const t = await api("/api/todos", {
        method: "POST",
        body: {
          title: title,
          priority: Number($("#qa-priority").value),
          remind: $("#qa-remind").checked ? 1 : 0,
          due_at: due.due_at,
          due_date: due.due_date,
        },
      });
      state.todos.unshift(t);
      $("#qa-title").value = "";
      $("#qa-date").value = "";
      $("#qa-time").value = "";
      renderTodos();
      refreshStats();
      $("#qa-title").focus();
    } catch (err) { fail(err); }
  });

  async function toggleTodo(id) {
    const t = state.todos.find((x) => x.id === id);
    if (!t) return;
    try {
      const updated = await api("/api/todos/" + id + "/toggle", { method: "POST" });
      Object.assign(t, updated);
      renderTodos();
      refreshStats();
      if (updated.status === "done") toast(icon("check") + " 已完成", "ok");
    } catch (e) { fail(e); }
  }
  async function deleteTodo(id) {
    const t = state.todos.find((x) => x.id === id);
    if (!t) return;
    if (!confirm("删除事项「" + t.title + "」？")) return;
    try {
      await api("/api/todos/" + id, { method: "DELETE" });
      state.todos = state.todos.filter((x) => x.id !== id);
      renderTodos();
      refreshStats();
      toast("已删除");
    } catch (e) { fail(e); }
  }
  $("#btn-clear-done").addEventListener("click", async () => {
    const n = state.todos.filter((t) => t.status === "done").length;
    if (!n) return toast("没有已完成的事项");
    if (!confirm("删除全部 " + n + " 条已完成事项？")) return;
    try {
      await api("/api/todos/clear-done", { method: "POST" });
      state.todos = state.todos.filter((t) => t.status !== "done");
      renderTodos();
      refreshStats();
      toast("已清理 " + n + " 条");
    } catch (e) { fail(e); }
  });

  /* edit modal */
  function openEditTodo(id) {
    const t = state.todos.find((x) => x.id === id);
    if (!t) return;
    state.editingTodoId = id;
    $("#ed-title").value = t.title;
    $("#ed-detail").value = t.detail || "";
    $("#ed-priority").value = String(t.priority);
    $("#ed-remind").checked = !!t.remind;
    $("#ed-done").checked = t.status === "done";
    if (t.due_at) {
      const d = new Date(t.due_at * 1000);
      const p = (n) => String(n).padStart(2, "0");
      $("#ed-date").value = d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
      $("#ed-time").value = p(d.getHours()) + ":" + p(d.getMinutes());
    } else if (t.due_date) {
      $("#ed-date").value = t.due_date;
      $("#ed-time").value = "";
    } else {
      $("#ed-date").value = "";
      $("#ed-time").value = "";
    }
    $("#modal").classList.remove("hidden");
    setTimeout(() => $("#ed-title").focus(), 30);
  }
  function closeEditTodo() {
    $("#modal").classList.add("hidden");
    state.editingTodoId = null;
  }
  $("#ed-cancel").addEventListener("click", closeEditTodo);
  $("#modal").addEventListener("click", (e) => { if (e.target === $("#modal")) closeEditTodo(); });
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeEditTodo(); });
  $("#ed-save").addEventListener("click", async () => {
    const id = state.editingTodoId;
    if (id == null) return;
    const date = $("#ed-date").value;
    const time = $("#ed-time").value;
    let due_at = null, due_date = null;
    if (date && time) due_at = Math.floor(new Date(date + "T" + time).getTime() / 1000);
    else if (date) due_date = date;
    try {
      const updated = await api("/api/todos/" + id, {
        method: "PUT",
        body: {
          title: $("#ed-title").value,
          detail: $("#ed-detail").value,
          priority: Number($("#ed-priority").value),
          remind: $("#ed-remind").checked ? 1 : 0,
          status: $("#ed-done").checked ? "done" : "pending",
          due_at: due_at,
          due_date: due_date,
        },
      });
      const t = state.todos.find((x) => x.id === id);
      if (t) Object.assign(t, updated);
      closeEditTodo();
      renderTodos();
      refreshStats();
      toast("已保存");
    } catch (e) { fail(e); }
  });
  $("#ed-delete").addEventListener("click", () => {
    const id = state.editingTodoId;
    if (id == null) return;
    closeEditTodo();
    deleteTodo(id);
  });

  /* ---------------- import / export ---------------- */

  function readFile(file) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result || ""));
      r.onerror = () => reject(new Error("读取文件失败"));
      r.readAsText(file, "utf-8");
    });
  }

  $("#imp-notes").addEventListener("change", async (e) => {
    const files = Array.from(e.target.files || []);
    if (!files.length) return;
    const st = $("#imp-notes-status");
    st.className = "status";
    st.innerHTML = icon("spinner") + "导入中…";
    try {
      const payload = { split: $("#imp-notes-split").checked, files: [] };
      for (const f of files) payload.files.push({ name: f.name, content: await readFile(f) });
      const res = await api("/api/import/notes", { method: "POST", body: payload });
      st.innerHTML = icon("checkCircle") + "成功导入 " + res.imported + " 条笔记";
      await loadNotes();
      refreshStats();
      toast("已导入 " + res.imported + " 条笔记");
    } catch (err) {
      st.className = "status err";
      st.innerHTML = icon("crossCircle") + " " + esc(err.message);
    }
    e.target.value = "";
  });

  $("#imp-todos").addEventListener("change", async (e) => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    const st = $("#imp-todos-status");
    st.className = "status";
    st.innerHTML = icon("spinner") + "导入中…";
    try {
      const content = await readFile(file);
      const res = await api("/api/import/todos", {
        method: "POST",
        body: { name: file.name, content: content, format: "auto" },
      });
      st.innerHTML = icon("checkCircle") + "成功导入 " + res.imported + " 条待办";
      await loadTodos();
      toast("已导入 " + res.imported + " 条待办");
    } catch (err) {
      st.className = "status err";
      st.innerHTML = icon("crossCircle") + " " + esc(err.message);
    }
    e.target.value = "";
  });

  $("#imp-backup").addEventListener("change", async (e) => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    const st = $("#imp-backup-status");
    const mode = ($$('input[name="bk-mode"]').find((r) => r.checked) || { value: "merge" }).value;
    if (mode === "replace" && !confirm("覆盖模式会清空现有全部笔记和待办，确定？")) {
      e.target.value = "";
      return;
    }
    st.className = "status";
    st.innerHTML = icon("spinner") + "恢复中…";
    try {
      const content = await readFile(file);
      const res = await api("/api/import/backup", { method: "POST", body: { content: content, mode: mode } });
      st.innerHTML = icon("checkCircle") + "恢复完成：笔记 " + res.notes + " 条，待办 " + res.todos + " 条";
      await Promise.all([loadNotes(), loadTodos()]);
      toast("备份恢复成功");
    } catch (err) {
      st.className = "status err";
      st.innerHTML = icon("crossCircle") + " " + esc(err.message);
    }
    e.target.value = "";
  });

  /* ---------------- mail settings ---------------- */

  function fillMailForm() {
    const s = state.settings || {};
    $("#set-host").value = s.smtp_host || "";
    $("#set-port").value = s.smtp_port || "465";
    $("#set-ssl").checked = s.smtp_ssl === "1";
    $("#set-user").value = s.smtp_user || "";
    $("#set-pass").value = s.smtp_pass || "";
    $("#set-from").value = s.mail_from || "";
    $("#set-to").value = s.mail_to || "";
    $("#set-notify").checked = s.notify_enabled === "1";
    $("#set-digest").checked = s.digest_enabled === "1";
    $("#set-digest-time").value = s.digest_time || "08:30";
    $("#set-tz").value = s.digest_tz || "Asia/Shanghai";
    $("#set-baseurl").value = s.base_url || "";
    applyMailState();
  }

  async function saveSettings(payload) {
    state.settings = await api("/api/settings", { method: "PUT", body: payload });
    applyMailState();
  }

  $("#smtp-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      await saveSettings({
        smtp_host: $("#set-host").value.trim(),
        smtp_port: $("#set-port").value.trim(),
        smtp_ssl: $("#set-ssl").checked,
        smtp_user: $("#set-user").value.trim(),
        smtp_pass: $("#set-pass").value,
        mail_from: $("#set-from").value.trim(),
        mail_to: $("#set-to").value.trim(),
      });
      toast(icon("checkCircle") + " SMTP 设置已保存", "ok");
    } catch (err) { fail(err); }
  });

  $("#notify-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      await saveSettings({
        notify_enabled: $("#set-notify").checked,
        digest_enabled: $("#set-digest").checked,
        digest_time: $("#set-digest-time").value,
        digest_tz: $("#set-tz").value,
        base_url: $("#set-baseurl").value.trim(),
      });
      toast(icon("checkCircle") + " 通知规则已保存", "ok");
    } catch (err) { fail(err); }
  });

  $("#btn-test-mail").addEventListener("click", async () => {
    const btn = $("#btn-test-mail");
    btn.disabled = true;
    btn.innerHTML = icon("spinner") + "发送中…";
    try {
      // save current form first so the test uses what's on screen
      await saveSettings({
        smtp_host: $("#set-host").value.trim(),
        smtp_port: $("#set-port").value.trim(),
        smtp_ssl: $("#set-ssl").checked,
        smtp_user: $("#set-user").value.trim(),
        smtp_pass: $("#set-pass").value,
        mail_from: $("#set-from").value.trim(),
        mail_to: $("#set-to").value.trim(),
      });
      await api("/api/settings/test-email", { method: "POST" });
      toast(icon("mail") + " 测试邮件已发送，请查收", "ok");
      loadMailLog();
    } catch (err) {
      fail(err);
      loadMailLog();
    } finally {
      btn.disabled = false;
      btn.innerHTML = "发送测试邮件";
    }
  });

  async function loadMailLog() {
    try {
      const log = await api("/api/emails");
      const tb = $("#mail-log tbody");
      tb.innerHTML = log.length
        ? log
            .map(
              (l) =>
                "<tr><td>" + fmtTime(l.sent_at) + "</td><td>" + esc(l.kind) + "</td><td>" + esc(l.subject) +
                "</td><td>" + (l.status === "ok" ? icon("checkCircle") + " 成功" : l.status === "skipped" ? icon("skip") + " 跳过" : icon("crossCircle") + " " + esc(l.error || "失败")) + "</td></tr>"
            )
            .join("")
        : '<tr><td colspan="4" class="muted">还没有发送记录</td></tr>';
    } catch (e) { if (!e.auth) fail(e); }
  }

  /* ---------------- settings ---------------- */

  $("#pw-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      await api("/api/settings/password", {
        method: "POST",
        body: { old: $("#pw-old").value, new: $("#pw-new").value },
      });
      $("#pw-old").value = "";
      $("#pw-new").value = "";
      toast(icon("checkCircle") + " 密码修改成功", "ok");
    } catch (err) { fail(err); }
  });

  async function loadServerInfo() {
    try {
      const s = await api("/api/stats");
      state.stats = s;
      const rows = [
        ["版本", "NoteBox 1.0"],
        ["服务器时间", s.server_time || "-"],
        ["数据库大小", ((s.db_bytes || 0) / 1024).toFixed(1) + " KB"],
        ["笔记数", s.notes],
        ["待办（未完成 / 已完成）", s.pending + " / " + s.done],
        ["已过期 / 今天到期", s.overdue + " / " + s.today],
        ["今日邮件发送成功", s.mail_ok_today],
        ["SMTP 状态", state.settings && state.settings.smtp_ready ? icon("checkCircle") + " 已配置" : icon("warn") + " 未配置"],
      ];
      $("#server-info").innerHTML = rows.map((r) => "<tr><td>" + esc(r[0]) + "</td><td>" + r[1] + "</td></tr>").join("");
      renderBadges();
    } catch (e) { if (!e.auth) fail(e); }
  }

  /* ---------------- user management (admin only) ---------------- */

  async function loadUsers() {
    try {
      state.users = await api("/api/admin/users");
      renderUsers();
      const badge = $("#badge-users");
      if (badge) {
        badge.textContent = state.users.length;
        badge.classList.remove("hidden");
      }
    } catch (e) { if (!e.auth) fail(e); }
  }

  function renderUsers() {
    const tb = $("#users-table tbody");
    tb.innerHTML = state.users
      .map((u) => {
        const rolePill = u.role === "admin"
          ? '<span class="pill role-admin">管理员</span>'
          : '<span class="pill">普通用户</span>';
        const st =
          u.status === "active"
            ? '<span class="pill ok">已启用</span>'
            : u.status === "pending"
            ? '<span class="pill warn">待邮箱验证</span>'
            : '<span class="pill">已停用</span>';
        const isMe = u.email === state.user;
        return (
          '<tr data-id="' + u.id + '">' +
          "<td>" + esc(u.email) + (isMe ? ' <span class="chip">我</span>' : "") + "</td>" +
          "<td>" + rolePill + "</td>" +
          "<td>" + st + "</td>" +
          "<td>" + (u.notes || 0) + " · " + (u.todos || 0) + "</td>" +
          "<td>" + (u.created_at ? fmtShortTime(u.created_at) : "—") + "</td>" +
          "<td>" + (u.last_login ? fmtShortTime(u.last_login) : "—") + "</td>" +
          '<td class="row-actions">' +
          '<button class="btn small u-edit">编辑</button>' +
          (u.status === "pending" ? '<button class="btn small u-resend">重发验证</button>' : "") +
          (u.status !== "pending" ? '<button class="btn small u-toggle">' + (u.active ? "停用" : "启用") + "</button>" : "") +
          '<button class="btn small ghost danger u-del">删除</button>' +
          "</td></tr>"
        );
      })
      .join("");
    $$("#users-table .u-edit").forEach((b) =>
      b.addEventListener("click", () => openUserModal(Number(b.closest("tr").dataset.id))));
    $$("#users-table .u-resend").forEach((b) =>
      b.addEventListener("click", async () => {
        const id = Number(b.closest("tr").dataset.id);
        try {
          await api("/api/admin/users/" + id + "/resend", { method: "POST" });
          toast(icon("mail") + " 验证邮件已重新发送", "ok");
        } catch (e) { fail(e); }
      }));
    $$("#users-table .u-toggle").forEach((b) =>
      b.addEventListener("click", async () => {
        const id = Number(b.closest("tr").dataset.id);
        const u = state.users.find((x) => x.id === id);
        if (!u) return;
        if (u.active && !confirm("停用 " + u.email + "？其会话将立即失效。")) return;
        try {
          await api("/api/admin/users/" + id, { method: "PUT", body: { active: !u.active } });
          toast(u.active ? "已停用 " + u.email : "已启用 " + u.email);
          loadUsers();
        } catch (e) { fail(e); }
      }));
    $$("#users-table .u-del").forEach((b) =>
      b.addEventListener("click", async () => {
        const id = Number(b.closest("tr").dataset.id);
        const u = state.users.find((x) => x.id === id);
        if (!u) return;
        if (!confirm("删除用户 " + u.email + "？其全部笔记、待办与共享将一并删除，不可恢复！")) return;
        try {
          await api("/api/admin/users/" + id, { method: "DELETE" });
          toast("已删除 " + u.email);
          loadUsers();
        } catch (e) { fail(e); }
      }));
  }

  function openUserModal(id) {
    state.editingUserId = id || null;
    const u = id ? state.users.find((x) => x.id === id) : null;
    $("#um-title").textContent = u ? "编辑用户" : "新建用户";
    $("#um-email").value = u ? u.email : "";
    $("#um-pw").value = "";
    $("#um-pw").placeholder = u ? "留空则不修改密码" : "至少 8 位";
    $("#um-role").value = u ? u.role : "user";
    $("#um-active").checked = u ? !!u.active : true;
    $("#um-error").classList.add("hidden");
    $("#user-modal").classList.remove("hidden");
    setTimeout(() => $("#um-email").focus(), 30);
  }
  $("#btn-new-user").addEventListener("click", () => openUserModal(null));
  $("#um-cancel").addEventListener("click", () => $("#user-modal").classList.add("hidden"));
  $("#user-modal").addEventListener("click", (e) => {
    if (e.target === $("#user-modal")) $("#user-modal").classList.add("hidden");
  });
  $("#um-save").addEventListener("click", async () => {
    const id = state.editingUserId;
    const email = $("#um-email").value.trim();
    const pw = $("#um-pw").value;
    const body = { email: email, role: $("#um-role").value, active: $("#um-active").checked };
    const err = $("#um-error");
    err.classList.add("hidden");
    try {
      if (id) {
        if (pw) body.password = pw;
        await api("/api/admin/users/" + id, { method: "PUT", body });
      } else {
        if (!pw || pw.length < 8) {
          throw new Error("新建用户需要至少 8 位的密码");
        }
        body.password = pw;
        await api("/api/admin/users", { method: "POST", body });
      }
      $("#user-modal").classList.add("hidden");
      toast(icon("checkCircle") + " 已保存 " + email, "ok");
      loadUsers();
    } catch (e) {
      err.textContent = e.message;
      err.classList.remove("hidden");
    }
  });

  /* ---------------- boot ---------------- */

  (async function boot() {
    try {
      const bootData = await api("/api/bootstrap");
      await initApp(bootData);
      // deep links: ?view=<view-id> and ?note=<id> (e.g. from shared context)
      const qp = new URLSearchParams(location.search);
      const v = qp.get("view");
      const noteId = parseInt(qp.get("note"), 10);
      if (v && document.getElementById("view-" + v)) setView(v);
      if (noteId && state.notes.some((n) => n.id === noteId)) {
        selectNote(noteId, qp.get("preview") === "1" ? "preview" : "edit");
      }
      if (v || noteId) history.replaceState({}, "", location.pathname);
    } catch (e) {
      if (!e.auth) console.warn(e);
      showLogin();
    }
  })();
})();
