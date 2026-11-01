/* NoteBox Markdown renderer — XSS-safe (escape first), with:
   - fenced code blocks: syntax highlighting, language auto-detect, copy button
   - media: images, direct video/audio links, YouTube & Bilibili embeds
   - tables, task lists, quotes, nested lists … */
(function () {
  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function safeUrl(url) {
    const u = String(url || "").trim();
    if (/^(https?:|mailto:|tel:)/i.test(u)) return u;
    if (/^(\/|#|\.{1,2}\/)/.test(u)) return u;
    if (/^[\w.\- %]+$/.test(u)) return u;
    return "#";
  }

  /* ---------------- syntax highlighting ---------------- */

  const KW = {
    js: "const let var function return if else for while do class new await async import from export default try catch finally throw typeof instanceof of in this null undefined true false switch case break continue yield static get set extends super void delete interface enum implements readonly private public as from",
    python: "def class return if elif else for while import from as with try except finally raise pass break continue lambda global nonlocal yield assert in not and or is None True False self async await del print range len str int float list dict set tuple open enumerate zip map filter sorted any all",
    sql: "select from where insert into update delete create table alter drop index join left right inner outer full on group by order having limit offset union all distinct as values set primary key foreign references not null default and or like between exists count sum avg min max desc asc",
    bash: "if then else fi for do done while case esac function echo cd ls mkdir rm cp mv cat grep sed awk export local return exit source alias sudo apt git curl wget chmod chown python3 pip node npm docker systemctl journalctl printf read test",
    json: "true false null",
    css: "",
    html: "",
    text: "",
  };

  const RULES = {
    js: {
      pattern: "(\\/\\/[^\\n]*|\\/\\*[\\s\\S]*?\\*\\/)|(`(?:\\\\.|[^`\\\\])*`|'(?:\\\\.|[^'\\\\\\n])*'|\"(?:\\\\.|[^\"\\\\\\n])*\")",
      classes: ["c", "s"],
      keywords: KW.js, numbers: true, funcs: true,
    },
    python: {
      pattern: "(#[^\\n]*)|([rbfRBF]{0,2}(?:\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'))|(@[\\w.]+)",
      classes: ["c", "s", "a"],
      keywords: KW.python, numbers: true, funcs: true,
    },
    html: {
      pattern: "(<!--[\\s\\S]*?-->)|(<\\/?[A-Za-z][\\w:-]*|\\/?>)",
      classes: ["c", "k"],
      keywords: "", numbers: false, funcs: false, attrs: true,
    },
    css: {
      pattern: "(\\/\\*[\\s\\S]*?\\*\\/)|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|(@[\\w-]+|#[0-9a-fA-F]{3,8}\\b)",
      classes: ["c", "s", "k"],
      keywords: "", numbers: true, funcs: false, attrs: true,
    },
    json: {
      pattern: "(\"(?:\\\\.|[^\"\\\\])*\")\\s*(:)|(\"(?:\\\\.|[^\"\\\\])*\")|(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)",
      classes: ["a", null, "s", "n"],
      rawNumbers: true,
      keywords: KW.json, numbers: false, funcs: false,
    },
    bash: {
      pattern: "(#[^\\n]*)|(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*')|(\\$\\{?[\\w@#?!*]+\\}?)",
      classes: ["c", "s", "v"],
      keywords: KW.bash, numbers: true, funcs: true,
    },
    sql: {
      pattern: "(--[^\\n]*|\\/\\*[\\s\\S]*?\\*\\/)|('(?:''|[^'])*'|\"(?:\"\"|[^\"])*\")",
      classes: ["c", "s"],
      keywords: KW.sql, numbers: true, funcs: true,
    },
    text: null,
  };

  const LANG_ALIAS = {
    javascript: "js", jsx: "js", mjs: "js", typescript: "js", ts: "js", tsx: "js",
    node: "js", vue: "html", xml: "html", htm: "html", svg: "html",
    py: "python", python3: "python",
    sh: "bash", shell: "bash", zsh: "bash", console: "bash", terminal: "bash",
    yml: "text", yaml: "text", toml: "text", ini: "text", diff: "text", patch: "text",
  };
  const LANG_SET = new Set(["js", "python", "html", "css", "json", "bash", "sql", "text"]);

  function detectLang(code) {
    const s = String(code).replace(/^\s+/, "");
    if (/^#!.*\bpython/.test(s)) return "python";
    if (/^#!\s*(\/bin\/|\/usr\/bin\/)?(ba|z|k)?sh/.test(s)) return "bash";
    if (/^\s*<(!doctype html|html|head|body|div|span|table|\?xml)/i.test(s)) return "html";
    if (/\b(SELECT|INSERT|UPDATE|DELETE)\b[\s\S]*\b(FROM|INTO|SET|TABLE)\b/i.test(s) && s.length < 4000)
      return "sql";
    try { JSON.parse(s); return "json"; } catch (e) { /* not json */ }
    if (/^\s*(const|let|var|function)\s|console\.log|=>|\bimport\s+.*\bfrom\b|\bexport\b/.test(s)) return "js";
    if (/^\s*(def\s+\w+\(|from\s+[\w.]+\s+import|import\s+\w+|elif\s|print\s*\(|\s{4}\S)/m.test(s)) return "python";
    if (/@media|:[\w-]+\s*;|\.[\w-]+\s*\{[^}]*:[^}]*\}/.test(s) && !/[;{]\s*}/.test(s.slice(0, 200)))
      return "css";
    return "text";
  }

  function stashFactory() {
    const ph = [];
    return {
      put(cls, txt) {
        ph.push('<span class="tok-' + cls + '">' + txt + "</span>");
        return "\u0000" + (ph.length - 1) + "\u0000";
      },
      restore(s) {
        return s.replace(/\u0000(\d+)\u0000/g, (_, i) => ph[+i] || "");
      },
    };
  }

  function highlightPlain(escaped, rules, st) {
    let out = escaped;
    if (rules.numbers)
      out = out.replace(
        /(?<![#&\w.])\d+(?:\.\d+)?(?:px|em|rem|vw|vh|vmin|vmax|ms|s|fr|deg|pt|ch|%|kb|mb|gb)?(?![\w.])/g,
        (m) => st.put("n", m)
      );
    if (rules.keywords) {
      const list = rules.keywords.trim().split(/\s+/).filter(Boolean);
      if (list.length)
        out = out.replace(new RegExp("\\b(" + list.join("|") + ")\\b", "g"), (m) => st.put("k", m));
    }
    if (rules.attrs) out = out.replace(/\b([a-zA-Z][\w-]*)(?==|:)/g, (m) => st.put("a", m));
    if (rules.funcs) out = out.replace(/\b([A-Za-z_$][\w$]*)(?=\s*\()/g, (m) => st.put("f", m));
    return out;
  }

  function highlight(code, lang) {
    const rules = RULES[lang];
    if (!rules) return escapeHtml(code);
    const st = stashFactory();
    const re = new RegExp(rules.pattern, "gm");
    let out = "", last = 0, m;
    while ((m = re.exec(code)) !== null) {
      if (m[0] === "") { re.lastIndex++; continue; }
      if (m.index > last) out += highlightPlain(escapeHtml(code.slice(last, m.index)), rules, st);
      // pick the first defined capture group; its index is the class index
      let clsIdx = -1;
      for (let g = 1; g < m.length; g++) {
        if (m[g] !== undefined) { clsIdx = g - 1; break; }
      }
      const cls = clsIdx >= 0 ? rules.classes[clsIdx] : null;
      const esc = escapeHtml(m[0]);
      out += cls ? '<span class="tok-' + cls + '">' + esc + "</span>" : esc;
      last = m.index + m[0].length;
    }
    out += highlightPlain(escapeHtml(code.slice(last)), rules, st);
    return st.restore(out);
  }

  function preBlock(code, explicitLang) {
    let lang = explicitLang ? (LANG_ALIAS[explicitLang] || explicitLang) : "";
    if (!LANG_SET.has(lang)) lang = detectLang(code);
    const label = explicitLang || (lang !== "text" ? lang : "code");
    return (
      '<div class="codeblock"><div class="code-head">' +
      '<span class="lang-badge">' + escapeHtml(label) + "</span>" +
      '<button type="button" class="copy-btn">复制</button></div>' +
      '<pre class="code"><code>' + highlight(code, lang) + "</code></pre></div>"
    );
  }

  /* ---------------- media embedding ---------------- */

  const VIDEO_RE = /\.(mp4|webm|ogv|mov|m4v|mkv)(\?|#|$)/i;
  const AUDIO_RE = /\.(mp3|wav|ogg|flac|m4a|aac|opus)(\?|#|$)/i;
  const IMAGE_RE = /\.(png|jpe?g|gif|webp|svg|avif|bmp|ico|apng)(\?|#|$)/i;

  function mediaEmbed(raw) {
    const url = String(raw || "").trim().replace(/&amp;/g, "&");
    if (!/^https?:\/\//i.test(url) && !/^\//.test(url)) return null;
    let m;
    if ((m = url.match(/(?:youtube\.com\/watch\?(?:.*&)?v=|youtu\.be\/|youtube\.com\/embed\/)([\w-]{6,})/i)))
      return '<div class="embed"><iframe src="https://www.youtube-nocookie.com/embed/' + m[1] +
        '" title="YouTube video" allowfullscreen loading="lazy"></iframe></div>';
    if ((m = url.match(/bilibili\.com\/video\/(BV[0-9A-Za-z]+)/i)))
      return '<div class="embed"><iframe src="//player.bilibili.com/player.html?bvid=' + m[1] +
        '&autoplay=0&high_quality=1" title="Bilibili video" scrolling="no" frameborder="0" allowfullscreen loading="lazy"></iframe></div>';
    if (VIDEO_RE.test(url))
      return '<video controls preload="metadata" playsinline src="' + safeUrl(url) + '"></video>';
    if (AUDIO_RE.test(url))
      return '<audio controls preload="metadata" src="' + safeUrl(url) + '"></audio>';
    if (IMAGE_RE.test(url))
      return '<a href="' + safeUrl(url) + '" target="_blank" rel="noopener noreferrer"><img src="' + safeUrl(url) + '" alt=""></a>';
    return null;
  }

  /* ---------------- inline ---------------- */

  function inline(text) {
    const codes = [];
    text = text.replace(/`([^`]+)`/g, function (m, c) {
      codes.push(c);
      return "\u0000C" + (codes.length - 1) + "\u0000";
    });
    text = text.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, function (m, alt, url) {
      return '<img src="' + safeUrl(url) + '" alt="' + alt + '">';
    });
    text = text.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (m, txt, url) {
      const media = VIDEO_RE.test(url) || AUDIO_RE.test(url) ? mediaEmbed(url) : null;
      if (media) return media;
      return '<a href="' + safeUrl(url) + '" target="_blank" rel="noopener noreferrer">' + txt + "</a>";
    });
    text = text.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    text = text.replace(/__([^_]+)__/g, "<strong>$1</strong>");
    text = text.replace(/(^|[^*\w])\*([^*\n]+)\*/g, "$1<em>$2</em>");
    text = text.replace(/(^|[^_\w])_([^_\n]+)_/g, "$1<em>$2</em>");
    text = text.replace(/~~([^~]+)~~/g, "<del>$1</del>");
    text = text.replace(/==([^=]+)==/g, "<mark>$1</mark>");
    text = text.replace(/\u0000C(\d+)\u0000/g, function (m, i) {
      return "<code>" + codes[+i] + "</code>";
    });
    return text;
  }

  /* ---------------- block parse ---------------- */

  const LIST_RE = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/;

  function render(src) {
    if (src === null || src === undefined) return "";
    let text = String(src).replace(/\r\n/g, "\n");

    // fenced code (raw) — captured before escaping
    const fences = [];
    text = text.replace(/```([\w+-]*)[ \t]*\n([\s\S]*?)```/g, function (m, lang, code) {
      fences.push(preBlock(code.replace(/\n$/, ""), lang.toLowerCase()));
      return "\u0000F" + (fences.length - 1) + "\u0000";
    });
    text = text.replace(/~~~([\s\S]*?)~~~/g, function (m, code) {
      fences.push(preBlock(code.replace(/^\n|\n$/g, ""), ""));
      return "\u0000F" + (fences.length - 1) + "\u0000";
    });

    text = escapeHtml(text);

    const lines = text.split("\n");
    const out = [];
    const stack = [];
    let para = [];
    let quote = false;

    function flushPara() {
      if (!para.length) return;
      const joined = para.join("<br>");
      const single = para.length === 1 ? para[0].trim() : "";
      if (single) {
        const media = mediaEmbed(single);
        if (media) { out.push(media); para = []; return; }
      }
      out.push("<p>" + inline(joined) + "</p>");
      para = [];
    }
    function closeQuote() {
      if (quote) { out.push("</blockquote>"); quote = false; }
    }
    function closeLists(toDepth) {
      while (stack.length > toDepth) {
        const e = stack.pop();
        if (e.openLi) out.push("</li>");
        out.push("</" + e.tag + ">");
      }
    }
    function closeAll() { flushPara(); closeLists(0); closeQuote(); }

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      const fm = line.match(/^\u0000F(\d+)\u0000$/);
      if (fm) { closeAll(); out.push(fences[+fm[1]]); continue; }

      if (!line.trim()) { flushPara(); closeLists(0); if (quote) closeQuote(); continue; }

      const qm = line.match(/^&gt;\s?(.*)$/);
      if (qm) {
        flushPara(); closeLists(0);
        if (!quote) { out.push("<blockquote>"); quote = true; }
        out.push("<p>" + inline(qm[1]) + "</p>");
        continue;
      } else if (quote) {
        closeQuote();
      }

      const hm = line.match(/^(#{1,6})\s+(.*)$/);
      if (hm) {
        closeAll();
        const lv = hm[1].length;
        out.push("<h" + lv + ">" + inline(hm[2]) + "</h" + lv + ">");
        continue;
      }

      if (/^([-*_])(?: *\1){2,}$/.test(line.trim())) { closeAll(); out.push("<hr>"); continue; }

      const isTableSep = (l) => {
        const t = l.trim();
        return /^[\s|:-]+$/.test(t) && t.includes("|") && t.includes("-");
      };
      if (line.includes("|") && i + 1 < lines.length && isTableSep(lines[i + 1])) {
        closeAll();
        const cells = function (l) {
          return l.trim().replace(/^\||\|$/g, "").split("|").map(function (c) { return inline(c.trim()); });
        };
        const head = cells(line);
        i += 2;
        const rows = [];
        while (i < lines.length && lines[i].trim().includes("|")) { rows.push(cells(lines[i])); i++; }
        i--;
        out.push("<table><thead><tr>" + head.map(function (c) { return "<th>" + c + "</th>"; }).join("") + "</tr></thead><tbody>");
        rows.forEach(function (r) { out.push("<tr>" + r.map(function (c) { return "<td>" + c + "</td>"; }).join("") + "</tr>"); });
        out.push("</tbody></table>");
        continue;
      }

      // indented code block (4 spaces / tab; list items keep list syntax)
      if (/^( {4}|\t)/.test(line) && !LIST_RE.test(line)) {
        flushPara();
        closeLists(0);
        if (quote) closeQuote();
        const buf = [];
        while (i < lines.length && lines[i].trim() && /^( {4}|\t)/.test(lines[i]) && !LIST_RE.test(lines[i])) {
          buf.push(lines[i].replace(/^( {4}|\t)/, ""));
          i++;
        }
        i--;
        if (buf.length) out.push(preBlock(buf.join("\n"), ""));
        continue;
      }

      const lm = line.match(LIST_RE);
      if (lm) {
        flushPara();
        if (quote) closeQuote();
        const depth = Math.min(2, Math.floor(lm[1].length / 2));
        const ordered = /\d/.test(lm[2]);
        const tag = ordered ? "ol" : "ul";
        let rest = lm[3];

        while (stack.length > depth + 1) closeLists(stack.length - 1);
        if (stack.length === depth + 1 && stack[stack.length - 1].tag !== tag) closeLists(stack.length);
        while (stack.length < depth) {
          const t = stack.length && stack[stack.length - 1].tag === "ol" ? "ol" : "ul";
          out.push("<" + t + ">");
          stack.push({ tag: t, depth: stack.length, openLi: false });
        }
        if (stack.length) {
          const top = stack[stack.length - 1];
          if (top.openLi) { out.push("</li>"); top.openLi = false; }
        } else {
          out.push("<" + tag + ">");
          stack.push({ tag: tag, depth: 0, openLi: false });
        }
        if (stack.length === depth) {
          out.push("<" + tag + ">");
          stack.push({ tag: tag, depth: depth, openLi: false });
        }

        let checkbox = "";
        const cm = rest.match(/^\[([ xX])\]\s+(.*)$/);
        if (cm) {
          const checked = cm[1].toLowerCase() === "x";
          checkbox = '<input type="checkbox" disabled' + (checked ? " checked" : "") + "> ";
          rest = cm[2];
        }
        const top2 = stack[stack.length - 1];
        top2.openLi = true;
        out.push("<li>" + checkbox + inline(rest));
        continue;
      }

      flushPara();
      if (stack.length) {
        const top = stack[stack.length - 1];
        if (top.openLi) { out.push(" " + inline(line.trim())); continue; }
      }
      para.push(line);
    }
    closeAll();

    let html = out.join("\n");
    html = html.replace(/\u0000F(\d+)\u0000/g, function (m, i) { return fences[+i] || ""; });
    return html;
  }

  /* ---------------- copy button (delegated for every page) ---------------- */
  if (typeof document !== "undefined" && !window.__noteboxCopyBound) {
    window.__noteboxCopyBound = true;
    document.addEventListener("click", function (e) {
      const btn = e.target && e.target.closest ? e.target.closest(".copy-btn") : null;
      if (!btn) return;
      const block = btn.closest(".codeblock");
      const code = block && block.querySelector("code");
      if (!code) return;
      const text = code.textContent || "";
      const done = function () {
        btn.textContent = "已复制";
        btn.classList.add("ok");
        setTimeout(function () { btn.textContent = "复制"; btn.classList.remove("ok"); }, 1600);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
      } else {
        fallbackCopy(text);
        done();
      }
    });
    function fallbackCopy(text) {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand("copy"); } catch (err) { /* ignore */ }
      document.body.removeChild(ta);
    }
  }

  window.md = { render: render, escapeHtml: escapeHtml, highlight: highlight, detectLang: detectLang, mediaEmbed: mediaEmbed };
})();
