#!/usr/bin/env python3
"""i18n binding pass 2 — conservative, template-aware, round-trip consistent.

Modes:
  report  — classify every unbound user-facing literal, print stats + samples
  apply   — rewrite display-context literals to L["key"], templates to trf("key", args),
            adding new keys to zh.json (and all locale files as zh copies pending translation).

Round-trip model: Chinese option labels (dropdowns) are compared later via
when/startsWith/contains against the SAME client-side string. Binding is keyed by
value, so binding every occurrence of such a value keeps comparisons consistent.
Values compared against SERVER data (e.message / .output / .typeLabel / Regex …)
never appear as display options and are left hardcoded by the S∪(C∩S) rule.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kt_scan import KEY_LIKE, STRUCT_LIKE, Literal, scan  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PANEL_SRC = [
    ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel",
    ROOT / "composeApp/src/androidMain/kotlin/com/servermaintance/panel",
    ROOT / "composeApp/src/desktopMain/kotlin/com/servermaintance/panel",
    ROOT / "composeApp/src/iosMain/kotlin/com/servermaintance/panel",
]
I18N = ROOT / "composeApp/src/commonMain/resources/i18n"
I18N_DESK = ROOT / "composeApp/src/desktopMain/resources/i18n"
LOCALES = ["zh", "en", "de", "it", "fr", "ru", "es", "pt", "ja", "ko", "vi", "lo", "th", "ms", "hi", "tr", "el", "ar"]

CJK = re.compile(r"[一-鿿]")

DISPLAY_PRE = re.compile(
    r"(?:\bText\s*\(|\btitle\s*=|\blabel\s*=|\bhint\s*=|\bplaceholder\s*=|\btext\s*=|\bsub\s*=|\bdesc\s*=|"
    r"\bokText\s*=|\bcancelText\s*=|\bcontentDescription\s*=|\btoast\s*\(|\bToast\s*\(|EmptyState\s*\(|"
    r"ErrorState\s*\(|LoadingState\s*\(|SectionTitle\s*\(|PanelCard\s*\(|Chip\s*\(|Badge\s*\(|"
    r"Modal\.(?:confirm|prompt|alert|output)\s*\(|\bmessage\s*=|\bbanner|\bLevelBadge\s*\(|StatTile\s*\(|"
    r"DangerBtn\s*\(|PrimaryBtn\s*\(|GhostBtn\s*\(|CheckRow\s*\(|SearchField\s*\(|SelectField\s*\(|"
    r"FormField\.|RemoteRow\s*\(|InfoRow\s*\(|RCol\s*\(|RelaySection\s*\(|\btitle:|\blabel:|\btext:|"
    r"\bplaceholder:|\bsubtitle\s*=|\bheadline\s*=|\bcaption\s*=|\bEmptyState\b|\bErrorState\b|"
    r"\btoasts?\s*\(|"
    # local widget components whose string args are labels
    r"\b(?:DbBtn|Muted|TableCol|KVRow|KvRow|HeadKV|KVLine|KeyValueRow|TableText|NbMiniStat|trendRow|"
    r"deltaRow|memKv|BarSer|ToolbarItem|PanelCardLocal|NavSectionLabel|TodoMiniIcon|PaletteCmd|RadioRow|"
    r"fireError|OpsLabelValue|DiskAction|InstallTarget|Chip|FormField|Dot|Pill|Warn|Note|Hdr|TinyIcon|"
    r"NbLoginField|wrapSel|Triple|PreBox|Mono12|Mono11)(?:<[^>]+>)?\s*\(|"
    # named display params
    r"\w*[Tt]oast\s*=|\boutputTitle\s*=|\bconfirmTitle\s*=|\bconfirmText\s*=|\bconfirmMsg\s*=|"
    r"\bdoneMsg\s*=|\btestMsg\s*=|\bstatusText\s*=|\bddlText\s*=|\ballLabel\s*=|\bnotesStatus\s*=|"
    r"\btodosStatus\s*=|\bbackupStatus\s*=|\bempty\s*=|\bemptyText\s*=|\bleading\s*=|\berror\s*=|"
    # exceptions surfaced via toast
    r"\w*Exception\s*\(|"
    # function default params (foo: String = "文案")
    r":\s*String\s*=\s*$|"
    # transient message state (resolved when set; re-set on next event)
    r"banner\.value\s*=\s*$|lastError\.value\s*=\s*$|"
    # option lists / state defaults / builders
    r"\blistOf\s*\(|\bmutableStateOf\s*\(|\.(?:add|append|appendLine)\s*\(|\b(?:add|append|appendLine)\s*\()",
    re.I,
)
# strong suffixes: the literal is unambiguously a display expression result
SUFFIX_PRE = re.compile(r"(?:\?\?|\?\.|\?:|->|return|\+|else)$")
# loose suffixes: previous argument of a widget call on an adjacent line
LOOSE_PRE = re.compile(r"(?:,|\()\s*$")
# then-branch / default-lambda contexts (checked on uncut pre-context)
SUFFIX_PRE_FULL = re.compile(
    r"(?:ifBlank|ifEmpty|getOrElse|ifNull|orElse|map|joinToString)\s*\{\s*$"
    r"|if\s*\(.*\)\s*$"
    r"|=\s*if\s*\(.*\)\s*$"
)
FORBID_PRE = re.compile(r"(?:==|!=|when\s*\(|!|&&|\|\||\bnot\s+in|\bin)$")
FORBID_POST = re.compile(
    r"^\s*(?:==|!=|->|in\s|!in\s|\.contains\(|\.(?:startsWith|endsWith|removePrefix|removeSuffix|substringBefore|substringAfter)\s*\(|to\s|&&|\|\||\?\s*:)"
)
# literals that are string-transform args (data pipeline); values starting with
# these roots must not be translated (option labels get parsed with them)
TRANSFORM_PRE = re.compile(
    r"\.(?:removePrefix|removeSuffix|removeRange|substringBefore|substringAfter|split|startsWith|endsWith|contains|equals)\s*\($"
    r"|(?:removePrefix|removeSuffix|substringBefore|substringAfter|split|startsWith|endsWith|contains|equals)\s*\($"
)
# pair RHS guard: never bind `to` values inside HTTP payload builders
PAYLOAD_LINE = re.compile(r"jsonObjectOf|jsonArray|jsonArrayOf|httpBody|body\s*=|params\s*=|query\s*=")

# (file basename, exact decoded value) — never bind: matched against server data /
# file contents / persisted values (see tools/i18n_revert_server_strings.py)
NEVER_BIND: set[tuple[str, str]] = {
    ("LogsPage.kt", "error|fail|refused|critical"),
    ("DbModel.kt", "未安装|未找到"),
    ("SettingsPage.kt", "nginx|sshd|fstab"),
    ("SettingsPage.kt", "失败"),
    ("SettingsPage.kt", "通过"),
    ("PackagesPage.kt", "\\d+ to remove|REMOVED"),
    ("OpsDiskPage.kt", "\\.(gz|xz|bz2|zst|old|\\d)$"),
    ("TodosPage.kt", "^\\d{4}-\\d{2}-\\d{2}$"),
    ("NoteMarkdownBlocks.kt", "^[\\s|:-]+$"),
    ("NoteMarkdownBlocks.kt", "^!\\[([^\\]]*)]\\(([^)\\s]+)\\)"),
    ("NotesPage.kt", "[\\[\\]() ]"),
    ("DbTablesTab.kt", ".*\\((.*)\\).*"),
    ("AuditPage.kt", "\\s+"),
    ("SecurityPage.kt", "\\s+"),
    ("DbAdvancedTab.kt", "\\W+"),
    ("DbAdvancedTab.kt", "_"),
    ("DbConsoleTab.kt", "\\b"),
    # env labels are persisted via POST "env" — raw values must round-trip
    ("OpsHostsPage.kt", "生产"),
    ("OpsHostsPage.kt", "预发"),
    ("OpsHostsPage.kt", "测试"),
    ("OpsHostsPage.kt", "开发"),
    ("OpsHostsPage.kt", "其它"),
    # compared against server-generated text — must keep matching raw server copy
    ("NoteCommon.kt", "验证"),
    ("OpsFilesPage.kt", "已存在"),
    ("PackagesPage.kt", "受保护"),
    ("NoteTransferPage.kt", "失败"),
}

# values — verified client-side UI vocabulary used in round-trip families: the label is
# both displayed and compared (when/startsWith/contains) against client state. Binding
# every occurrence by value keeps display and comparison consistent in all languages.
BIND_FORCE_VALUES: set[str] = {
    "空闲 (idle)", "活动", "空闲事务", "导入中", "恢复中", "仅结构", "仅数据", "自定义",
    "设置参数", "重置", "修改所有者", "校验", "重建", "压缩", "显示", "不指定", "明天",
    "清空", "内存%", "磁盘%", "内存", "下行", "缓存读", "缓存写", "输入", "输出",
    "统计：表大小", "统计：慢查询", "-- 模板 --", "失败", "并发", "速率", "无响应",
    "服务器", "发件人名", "发件账号", "收件人", "推理", "按分组", "按大小", "按版本",
    "创建时间", "降序", "升序", "全局", "每天固定时段", "不通知", "仅停止", "保存中…",
    "文件内条目", "状态文件", "有问题", "＋ 新建归类…", "管理", "紧张",
    "402 额度", "403 权限", "429 限流", "journal + APT + 轮转日志", "下行 KB/s",
    "检查脚本", "远端推送", "按名称", "全部分组", "全部源（全局搜索）", "自动创建",
    "全部标签", "全部角色", "全部状态", "管理员", "允许 NULL", "已保存", "只读",
    "可编辑", "未归类", "回收站", "共享给我", "全部笔记", "全部清单", "高", "中", "低",
    "正常", "耗尽", "预计耗尽", "已过期", "全部", "是", "否",
}


def slug_key(text: str) -> str:
    h = hashlib.sha1(text.encode("utf-8")).hexdigest()[:10]
    prefix = re.sub(r"[^\w一-鿿]+", "_", text[:24].strip().lower(), flags=re.UNICODE)
    prefix = re.sub(r"_+", "_", prefix).strip("_") or "s"
    return f"{prefix}_{h}"


def is_trivial(value: str) -> bool:
    v = value.strip()
    if not v or v in ("—", "→", "✓", "/", "?", "??", "\\n", "\\t", " "):
        return True
    if KEY_LIKE.search(v) or STRUCT_LIKE.match(v):
        return True
    if re.fullmatch(r"[a-z][a-z0-9_./:-]*", v):  # routes / ids / enum values
        return True
    if re.fullmatch(r"[A-Za-z][A-Za-z0-9_.:/-]*", v):  # identifiers / product names / paths
        return True
    if "/" in v and " " not in v and len(v) < 40 and not CJK.search(v):
        return True  # path-like, but never when it carries Chinese copy (e.g. 创建/替换)
    if v.startswith(("#", "http://", "https://", "?", "&", "=", "@", "%")):
        return True
    if re.fullmatch(r"\$[A-Za-z_][\w.]*", v):  # bare $variable reference
        return True
    if re.fullmatch(r"[0-9.:%\s]+", v):
        return True
    if re.fullmatch(r"[-—–:.]+", v):  # placeholders like "-", "--:--"
        return True
    if re.fullmatch(r"[\\^$.*+?()\[\]{}|/\s-]+", v):  # pure regex/junk fragments
        return True
    return False


def template_to_format(value: str, lit: Literal) -> str | None:
    """Convert decoded template value with ${expr}/$ident to {i} format string."""
    out: list[str] = []
    interp_texts = [x.expr for x in lit.interps]
    markers: list[tuple[int, int, int]] = []
    j = 0
    kidx = 0
    while j < len(value):
        c = value[j]
        if c == "\\" and j + 1 < len(value) and value[j + 1] == "$":
            j += 2
            continue
        if c == "$" and j + 1 < len(value):
            if value[j + 1] == "{":
                depth = 1
                q = j + 2
                while q < len(value) and depth:
                    if value[q] == "{":
                        depth += 1
                    elif value[q] == "}":
                        depth -= 1
                        if depth == 0:
                            break
                    elif value[q] == '"':
                        q += 1
                        while q < len(value) and value[q] != '"':
                            if value[q] == "\\":
                                q += 1
                            q += 1
                    q += 1
                markers.append((j, q + 1, kidx))
                kidx += 1
                j = q + 1
                continue
            if value[j + 1].isalpha() or value[j + 1] == "_":
                q = j + 1
                while q < len(value) and (value[q].isalnum() or value[q] == "_"):
                    q += 1
                markers.append((j, q, kidx))
                kidx += 1
                j = q
                continue
        j += 1
    if kidx != len(interp_texts):
        return None
    pos = 0
    for start, end, _expr_i in markers:
        out.append(value[pos:start])
        out.append("{%d}" % _expr_i)
        pos = end
    out.append(value[pos:])
    return "".join(out)


def line_is_comment_only(line: str) -> bool:
    s = line.lstrip()
    return s.startswith("//") or s.startswith("*")


def context_for(text: str, lit: Literal) -> tuple[str, str, str, int, str]:
    """Return (pre_cut, pre_full, post, col, two_line_window)."""
    pre_full = text[max(0, lit.start - 300) : lit.start]
    cut = max(pre_full.rfind(";"), pre_full.rfind("{"), pre_full.rfind("}"))
    pre = pre_full[cut + 1 :] if cut != -1 else pre_full
    post = text[lit.end : lit.end + 80]
    line_start = text.rfind("\n", 0, lit.start) + 1
    col = lit.start - line_start
    # window spanning the current line prefix + up to 2 previous lines
    p1 = text.rfind("\n", 0, lit.start)
    p2 = text.rfind("\n", 0, p1) if p1 > 0 else -1
    p3 = text.rfind("\n", 0, p2) if p2 > 0 else -1
    window = text[p3 + 1 : lit.start]
    return pre, pre_full, post, col, window


def classify_site(
    lit: Literal,
    pre: str,
    pre_full: str,
    post: str,
    col: int,
    window: str,
    display_values: set[str],
    protected: list[str],
    basename: str = "",
) -> str:
    """Return bind | trf | cond | transform | review | skip."""
    v = lit.value
    if is_trivial(v):
        return "skip"
    if (basename, v) in NEVER_BIND:
        return "skip"
    has_cjk = bool(CJK.search(v))
    if v in BIND_FORCE_VALUES:
        pass  # verified client-side round-trip family — bind display & compare alike
    elif any(v == p or (p.endswith(" ") and v.startswith(p)) for p in protected):
        return "transform"  # parsed from an option label; keep in sync manually
    if re.search(r"(?:\btrf?|\bfmt|\bL|\bresolve|\bformat)\s*(?:\(\s*|\[\s*)$", pre.rstrip()):
        return "skip"  # already bound (key argument position)
    if FORBID_PRE.search(pre.rstrip()):
        # ==/!=//in operands: round-trip option value or server data — decide in phase 2
        return "cond" if has_cjk else "skip"

    # transform/strip args (option label parsing) — data pipeline
    if TRANSFORM_PRE.search(pre.rstrip()):
        return "cond" if has_cjk else "skip"

    strong_suffix = bool(SUFFIX_PRE.search(pre.rstrip()))
    window_hit = bool(DISPLAY_PRE.search(window[-300:])) or bool(DISPLAY_PRE.search(pre[-220:]))
    loose = bool(LOOSE_PRE.search(pre))
    full_hit = bool(SUFFIX_PRE_FULL.search(pre_full))
    concat_lhs = bool(re.match(r"^\s*\+", post))
    # continuation argument of a widget call: loose suffix + widget name within the
    # uncut pre-context (covers multi-line arg lists)
    cont_hit = loose and (window_hit or bool(DISPLAY_PRE.search(pre_full)))
    display = strong_suffix or window_hit or full_hit or cont_hit or concat_lhs

    local_assign = bool(ASSIGN_PRE_PATTERN.search(pre.rstrip())) and col > 0
    pair_rhs = bool(re.search(r"\bto\s*$", pre.rstrip())) and not PAYLOAD_LINE.search(window)
    line_has_cjk_context = True

    if lit.is_template:
        if not has_cjk:
            return "skip"
        if display or local_assign or (pair_rhs and has_cjk):
            return "trf"
        if FORBID_POST.match(post):
            return "cond" if has_cjk else "skip"
        return "review"
    # `label to value` pairs in chart/KV display structures: the left side is the label
    if re.match(r"^\s+to\s", post) and has_cjk and not PAYLOAD_LINE.search(window):
        return "bind"
    if FORBID_POST.match(post):
        return "cond" if has_cjk else "skip"
    if has_cjk or display:
        if display or local_assign or (pair_rhs and has_cjk):
            return "bind"
        return "review"
    return "skip"


ASSIGN_PRE_PATTERN = re.compile(
    r"(?:\bval\s+\w+\s*(?::[^=]+)?=\s*|\bvar\s+\w+\s*(?::[^=]+)?=\s*|\bval\s+\w+\s+by\b.*=\s*)$"
)


def load_zh() -> dict[str, str]:
    return json.loads((I18N / "zh.json").read_text(encoding="utf-8"))


def iter_sources():
    for base in PANEL_SRC:
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.kt")):
            if "/i18n/" in path.as_posix():
                continue
            yield path


def collect_protected_roots() -> list[str]:
    """Chinese string literals passed to string-transform functions."""
    roots: set[str] = set()
    for path in iter_sources():
        text = path.read_text(encoding="utf-8")
        for lit in scan(text):
            if not CJK.search(lit.value):
                continue
            pre = text[max(0, lit.start - 120) : lit.start]
            if TRANSFORM_PRE.search(pre.rstrip()):
                roots.add(lit.value)
    return sorted(roots)


def process(apply: bool) -> None:
    zh = load_zh()
    rev: dict[str, str] = {}
    for k, v in zh.items():
        rev.setdefault(v, k)
    new_entries: dict[str, str] = {}
    stats: Counter = Counter()
    samples: dict[str, list[str]] = {"bind": [], "trf": [], "review": [], "skip_cjk": [], "cond": []}
    review_sites: list[str] = []
    cond_bound_sites: list[str] = []
    cond_only_values: set[str] = set()
    display_values: set[str] = set()
    protected = collect_protected_roots()

    sites: list[tuple[Path, str, int, Literal, str]] = []  # (path, text, line, lit, cat)

    # phase 1: scan + classify with empty display set (display detection is local)
    for path in iter_sources():
        text = path.read_text(encoding="utf-8")
        for lit in scan(text):
            pre, pre_full, post, col, window = context_for(text, lit)
            cat = classify_site(
                lit, pre, pre_full, post, col, window, display_values, protected, path.name
            )
            line_no = text[: lit.start].count("\n") + 1
            sites.append((path, text, line_no, lit, cat))
            if cat in ("bind", "trf"):
                if lit.is_template:
                    fmt = template_to_format(lit.value, lit)
                    if fmt is not None:
                        display_values.add(fmt)
                else:
                    display_values.add(lit.value)
            elif cat == "cond":
                cond_only_values.add(lit.value if not lit.is_template else lit.value)

    # phase 2: decide final action; condition sites bind iff their value is a display value
    edits_by_file: dict[Path, list[tuple[int, int, str]]] = {}

    def key_for(value: str) -> str:
        existing = rev.get(value) or new_entries.get(value)
        if existing:
            return existing
        key = slug_key(value)
        while key in zh or key in new_entries:
            key = key[:-1] + hashlib.sha1((value + key).encode()).hexdigest()[:1]
        new_entries[value] = key
        rev[value] = key
        return key

    for path, text, line_no, lit, cat in sites:
        if cat == "skip":
            if CJK.search(lit.value):
                stats["skip_cjk"] += 1
            continue
        value_for_key = lit.value
        if lit.is_template:
            fmt = template_to_format(lit.value, lit)
            if fmt is None:
                stats["review"] += 1
                rel = path.relative_to(ROOT).as_posix()
                review_sites.append(f"{rel}:{line_no}  [tpl-complex] {lit.raw[:120]}")
                continue
            value_for_key = fmt
        if cat == "cond":
            if value_for_key in display_values and not lit.is_template:
                cat = "bind"  # round-trip: same value shown in options
                cond_bound_sites.append(
                    f"{path.relative_to(ROOT).as_posix()}:{line_no}  {lit.raw[:80]}"
                )
            else:
                stats["cond_left"] += 1
                continue
        if cat == "transform":
            stats["transform"] += 1
            continue
        if cat == "review":
            stats["review"] += 1
            rel = path.relative_to(ROOT).as_posix()
            line_start = text.rfind("\n", 0, lit.start) + 1
            line_end = text.find("\n", lit.start)
            line_txt = text[line_start : line_end if line_end != -1 else len(text)].strip()[:170]
            review_sites.append(f"{rel}:{line_no}  {line_txt}")
            continue
        # bind or trf
        key = key_for(value_for_key)
        if lit.is_template:
            args = ", ".join(x.expr for x in lit.interps)
            repl = f'trf("{key}", {args})' if args else f'trf("{key}")'
        else:
            repl = f'L["{key}"]'
        stats[cat] += 1
        if len(samples[cat]) < 40:
            samples[cat].append(f"{path.name}:{line_no}  {lit.raw[:100]}  ->  {repl[:100]}")
        edits_by_file.setdefault(path, []).append((lit.start, lit.end, repl))

    print("== classification ==")
    for k in ("bind", "trf", "review", "cond_left", "transform", "skip_cjk"):
        print(f"  {k:16s} {stats[k]}")
    for cat in ("bind", "trf", "review"):
        print(f"\n-- samples[{cat}] --")
        for s in samples[cat][:20]:
            print("   " + s)
    print(f"\nnew zh entries to add: {len(new_entries)}")
    dump = ROOT / "build-bind2-review.txt"
    dump.write_text("\n".join(review_sites) + ("\n" if review_sites else ""), encoding="utf-8")
    print(f"review sites dumped -> {dump.name} ({len(review_sites)})")
    cond_dump = ROOT / "build-bind2-cond.txt"
    cond_dump.write_text(
        "\n".join(sorted(v for v in cond_only_values if CJK.search(v))) + "\n", encoding="utf-8"
    )
    print(f"condition-only CJK values (left hardcoded) -> {cond_dump.name}")
    cb = ROOT / "build-bind2-condbound.txt"
    cb.write_text("\n".join(cond_bound_sites) + ("\n" if cond_bound_sites else ""), encoding="utf-8")
    print(f"round-trip condition sites (bound) -> {cb.name} ({len(cond_bound_sites)})")

    if not apply:
        return

    changed = 0
    import re as _re

    for path, edits in edits_by_file.items():
        text = path.read_text(encoding="utf-8")
        orig = text
        needs_trf = any("trf(" in r for _, _, r in edits)
        needs_l = any('L["' in r for _, _, r in edits)
        for start, end, repl in sorted(edits, key=lambda e: -e[0]):
            text = text[:start] + repl + text[end:]
        if needs_trf or needs_l:
            imports = []
            if needs_l and "import com.servermaintance.panel.i18n.L" not in text:
                imports.append("import com.servermaintance.panel.i18n.L")
            if needs_trf and "import com.servermaintance.panel.i18n.trf" not in text:
                imports.append("import com.servermaintance.panel.i18n.trf")
            if imports and "package com.servermaintance" in text:
                m = _re.search(r"^package .+$", text, _re.M)
                text = text[: m.end()] + "\n" + "\n".join(imports) + text[m.end() :]
        if text != orig:
            path.write_text(text, encoding="utf-8")
            changed += 1

    for value, key in new_entries.items():
        zh[key] = value
    (I18N / "zh.json").write_text(
        json.dumps(zh, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    for tag in LOCALES:
        if tag == "zh":
            continue
        p = I18N / f"{tag}.json"
        loc = json.loads(p.read_text(encoding="utf-8"))
        added = 0
        for value, key in new_entries.items():
            if key not in loc:
                loc[key] = value
                added += 1
        if added:
            p.write_text(json.dumps(loc, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    I18N_DESK.mkdir(parents=True, exist_ok=True)
    for f in I18N.glob("*.json"):
        (I18N_DESK / f.name).write_text(f.read_text(encoding="utf-8"), encoding="utf-8")

    print(f"rewrote {changed} files; added {len(new_entries)} keys; zh.json total {len(zh)}")


def main() -> None:
    mode = sys.argv[1] if len(sys.argv) > 1 else "report"
    process(apply=(mode == "apply"))


if __name__ == "__main__":
    main()
