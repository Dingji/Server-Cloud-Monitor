/* NoteBox share page: fetch a shared note by code and render it. */
(function () {
  const $ = (s) => document.querySelector(s);
  const esc = (s) =>
    String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])
    );

  function show(id) {
    ["share-loading", "share-error", "share-article"].forEach((k) => {
      const el = document.getElementById(k);
      if (el) el.classList.toggle("hidden", k !== id);
    });
  }

  async function boot() {
    const code = (location.pathname.split("/").pop() || "").trim();
    let data;
    try {
      const res = await fetch("/api/share/" + encodeURIComponent(code), { headers: { Accept: "application/json" } });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.error || "分享链接无效或已失效");
      data = body;
    } catch (err) {
      $("#share-error-msg").textContent = err.message || "分享链接无效或已失效";
      show("share-error");
      return;
    }

    const title = data.title || "无标题笔记";
    document.title = title + " · NoteBox 分享";
    $("#share-title").textContent = title;
    $("#share-time").textContent =
      "更新于 " +
      new Date((data.updated_at || 0) * 1000).toLocaleString("zh-CN", { hour12: false });
    const tags = String(data.tags || "")
      .split(",")
      .map((t) => t.trim())
      .filter(Boolean);
    $("#share-tags").innerHTML = tags.map((t) => '<span class="chip">' + esc(t) + "</span>").join(" ");
    $("#share-body").innerHTML = md.render(data.content || "");
    show("share-article");
    // hydrate any icons that ended up inside rendered content
    if (window.hydrateIcons) window.hydrateIcons();
  }

  boot();
})();
