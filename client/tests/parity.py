#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""功能对账（parity）——「所有功能都要有，一个不能少」的机器化初筛 + 人工复核载体。

输入：
  docs/api/feature-matrix.md   （research 产出：20 页 × 功能点 × 端点 × upstream 行号）
  composeApp/src/**            （Kotlin 实现）

做法：
  1. 解析 feature-matrix 每一行为 (页面, 编号, 功能点, 端点, upstream 行号)；
  2. 从「端点」列抽出 REST 路径片段（支持「同上」继承），在 Kotlin 源码里搜字面量，
     命中即给出 文件:行 证据；WS 行按消息关键词搜 core/Hub.kt、pages/*；
  3. 端点列为「–/本地/全部」的行，自动判定不了 → 标 🔎 待人工核对；
  4. 人工复核结论写进 tests/parity-overrides.json（{编号: {status, note}}），重新生成时保留。

输出：tests/parity.md

状态：✅ 已实现 / ⚠️ 部分 / ❌ 未见实现证据 / 🔎 待人工核对 / N/A 不适用

用法：python tests/parity.py
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MATRIX = os.path.join(ROOT, "docs", "api", "feature-matrix.md")
SRC = os.path.join(ROOT, "composeApp", "src")
TESTS = os.path.join(ROOT, "tests")
OVERRIDES = os.path.join(TESTS, "parity-overrides.json")

# WS / 本地能力的自动检索关键词（row id -> 检索词，命中任一即算有证据）
WS_KEYWORDS = {
    "V1": ["isAuth", "401", "未登录或会话过期"],
    "V2": ["429", "请求过于频繁"],
    "V3": ["ApiException", "output"],
    "V4": ["subscribe-logs", "refresh-interval", "hello"],
    "V5": ["attach", "resize", "/term"],
    "V6": ["download", "Content-Disposition"],
    "V7": ["upload"],
    "V8": ["/pkg/job"],
    "V9": ["AccentState", "cm_collapsed", "命令面板"],
    "V10": ["audited"],
    "V11": ["engine", "引擎"],
    "V12": ["relay/available"],
    # 明确的消息类型（比流名更特异）
    "T4": ["resize", "onData"],
    "R6": ["log-error", "log-end"],
}


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def collect_sources():
    """返回 {相对路径: [行文本…]}，以及一个 (文件, 行号, 行文本) 扁平列表用于搜索。"""
    files, flat = {}, []
    for dirpath, _dirs, names in os.walk(SRC):
        for n in names:
            if not n.endswith(".kt"):
                continue
            p = os.path.join(dirpath, n)
            rel = os.path.relpath(p, ROOT).replace("\\", "/")
            try:
                lines = read(p).splitlines()
            except Exception:
                continue
            files[rel] = lines
            for i, ln in enumerate(lines, 1):
                flat.append((rel, i, ln))
    return files, flat


def parse_matrix(text):
    rows = []
    section = None
    for line in text.splitlines():
        m = re.match(r"^##\s+([A-Z])\.\s+(.*)$", line)
        if m:
            section = (m.group(1), m.group(2).strip())
            continue
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 4:
            continue
        if not re.match(r"^[A-V]\d+$", cells[0]):
            continue  # 表头/分隔行
        rows.append({
            "section": section,
            "id": cells[0],
            "feature": cells[1],
            "ui": cells[2] if len(cells) > 2 else "",
            "endpoint": cells[3] if len(cells) > 3 else "",
            "ref": cells[4] if len(cells) > 4 else (cells[3] if len(cells) == 4 else ""),
        })
    return rows


METHOD_RE = re.compile(r"\b(?:GET|POST|PUT|DELETE)\b")
BRACE_RE = re.compile(r"\{[^}]*\}")           # {password} 请求体说明不是路径
FRAG_SPLIT = re.compile(r"[,\s、；;|]+")
ASCII_PATH = re.compile(r"^[a-zA-Z0-9_\-./]+$")


def _extract_paths(cell):
    """从端点列抽路径：支持方法前缀、逗号列表后缀（/relay/summary,health）、
    查询串（?…）、请求体花括号、无前导斜杠的 relayops/slo.byRoute。"""
    cell = BRACE_RE.sub(" ", cell)
    cell = METHOD_RE.sub(" ", cell)
    out = []
    for frag in FRAG_SPLIT.split(cell):
        frag = (frag or "").strip().strip("`'\"")
        if not frag or frag.startswith("?"):
            continue
        frag = frag.split("?")[0]
        if not frag:
            continue
        if frag.startswith("."):
            # 「同上.rules」形式：附着到上一条路径，交由点号分支处理
            if out:
                out.append(out[-1] + frag)
            continue
        if frag.startswith("/") and ASCII_PATH.match(frag):
            out.append(frag)
        elif "/" in frag and ASCII_PATH.match(frag):
            out.append(frag)                          # relayops/slo.byRoute
        elif out and ASCII_PATH.match(frag):
            parent = out[-1].rsplit("/", 1)[0] if "/" in out[-1] else ""
            cand = (parent + "/" + frag) if parent else frag
            if cand not in out:
                out.append(cand)                      # 逗号列表后缀 health,selfheal
    seen, res = set(), []
    for p in out:
        if p not in seen:
            seen.add(p)
            res.append(p)
    return res


def endpoints_of(cell, prev):
    """从端点列抽 REST 路径片段；处理 同上/全部/–/WS/本地。"""
    cell = (cell or "").strip()
    if cell.startswith("同上"):
        paths = list(prev) + _extract_paths(cell[2:])
        seen, res = set(), []
        for p in paths:
            if p not in seen:
                seen.add(p)
                res.append(p)
        return res, ("same" if cell[2:].strip() in ("", "…", "...") else "same+")
    out = _extract_paths(cell)
    if out:
        # 「WS data/resize」「WS subscribe-logs/…」这类是消息类型不是 REST 路径：
        # 含 WS 且没有任何以 / 开头的真路径时，按 WS 处理
        if "WS" in cell and not any(p.startswith("/") for p in out):
            return [], "ws"
        return out, "rest"
    if "WS" in cell:
        return [], "ws"
    if cell in ("–", "-", "本地", "本地存储", "全部", ""):
        return [], "local"
    if re.search(r"[a-zA-Z0-9]/[a-zA-Z0-9]", cell):
        return [], "subkey"
    return [], "none"


def search_eps(flat, ep):
    """ep 形如 '/relayops/slo' → 搜 'relayops/slo'；单段路径（如 /me /web）用引号/斜杠
    边界正则，避免 'me' 之类短词满屏误命中。返回前 3 个 文件:行。"""
    needle = ep.lstrip("/")
    hits = []
    if "/" in needle:                     # 多段路径：子串搜索足够特异
        for rel, i, ln in flat:
            if needle in ln:
                hits.append("%s:%d" % (rel.split("/")[-1], i))
                if len(hits) >= 3:
                    break
        return hits
    pat = re.compile(r"[\"'`/(]" + re.escape(needle) + r"(?![a-zA-Z0-9_-])")
    for rel, i, ln in flat:
        if pat.search(ln):
            hits.append("%s:%d" % (rel.split("/")[-1], i))
            if len(hits) >= 3:
                break
    return hits


def page_file_for(section):
    """section 标题如 'B. dashboard 仪表盘' → pages/DashboardPage.kt（或路由/其它）。"""
    letter, title = section
    token = title.split()[0].lower() if title else ""
    if letter == "A":
        return "core/** + screens/** + nav/**"
    if letter == "V":
        return "（跨页）"
    cands = []
    for dirpath, _d, names in os.walk(os.path.join(SRC, "commonMain")):
        for n in names:
            if n.endswith(".kt") and token and token in n.lower():
                cands.append(os.path.relpath(os.path.join(dirpath, n), ROOT).replace("\\", "/"))
    if cands:
        return cands[0].split("/")[-1]
    return "**未找到页面文件**"


def page_paths_for(section):
    """返回该节对应的 Kotlin 源文件完整相对路径列表；V（跨页）返回 None 表示「全源码」。"""
    if not section:
        return []
    letter, title = section
    if letter == "V":
        return None
    token = title.split()[0].lower() if title else ""
    out = []
    for dirpath, _d, names in os.walk(os.path.join(SRC, "commonMain")):
        for n in names:
            if not n.endswith(".kt"):
                continue
            if letter == "A":
                if any(seg in os.path.relpath(dirpath, SRC).replace("\\", "/")
                       for seg in ("core", "screens", "nav")):
                    out.append(os.path.relpath(os.path.join(dirpath, n), ROOT).replace("\\", "/"))
            elif token and token in n.lower():
                out.append(os.path.relpath(os.path.join(dirpath, n), ROOT).replace("\\", "/"))
    return out


def is_stub(section):
    """页面 Page 文件总字节数 < 1.2KB 视为「6 行骨架」→ 🚧 进行中。"""
    paths = page_paths_for(section)
    if not paths:
        return False
    total = 0
    for p in paths:
        if p.endswith("Page.kt"):
            try:
                total += os.path.getsize(os.path.join(ROOT, p))
            except OSError:
                pass
    return 0 < total < 1200


def cn_fragments(text):
    """从中文功能点里切出可搜索的片段（≥3 字），去掉括号注释与标点。"""
    base = re.split(r"[（(：:]", text)[0]
    parts = re.split(r"[、，,\s/]+", base)
    frags = []
    for p in parts:
        p = re.sub(r"[^\u4e00-\u9fa5A-Za-z0-9]", "", p)
        if len(p) >= 3:
            frags.append(p)
    if base and len(base) >= 3 and base not in frags:
        frags.append(base)
    return frags[:4]


def search_lines(lines, needle):
    return [i for i, ln in enumerate(lines, 1) if needle in ln]


def main():
    text = read(MATRIX)
    rows = parse_matrix(text)
    files, flat = collect_sources()
    overrides = {}
    if os.path.exists(OVERRIDES):
        try:
            overrides = json.load(open(OVERRIDES, encoding="utf-8"))
        except Exception:
            overrides = {}

    prev_eps, prev_id = [], None
    results = []
    for r in rows:
        eps, kind = endpoints_of(r["endpoint"], prev_eps)
        if kind in ("rest", "same", "same+", "subkey"):
            prev_eps = eps if eps else prev_eps
        evidence, missing = [], []
        section_paths = page_paths_for(r["section"])
        if kind in ("rest", "same", "same+"):
            for ep in eps:
                ep_path = ep
                if "." in ep_path:                 # 形如 relayops/slo.errors/timeline（父端点+子字段）
                    head, rest = ep_path.split(".", 1)
                    parent, sub = head, rest.split("/")[0]
                    phits = search_eps(flat, parent)
                    if phits:
                        sub_evi = []
                        for p in (section_paths or []):
                            if p in files:
                                li = search_lines(files[p], sub)
                                if li:
                                    sub_evi.append("%s:%d" % (p.split("/")[-1], li[0]))
                        if sub_evi:
                            evidence.append("%s → 父端点 %s + 子键 `%s` %s" % (
                                ep, ", ".join(phits), sub, ", ".join(sub_evi)))
                        else:
                            missing.append("%s（父端点在 %s，但子键 `%s` 未在页面找到）" % (
                                ep, ", ".join(phits), sub))
                    else:
                        missing.append(ep)
                    continue
                hits = search_eps(flat, ep)
                if hits:
                    evidence.append("%s → %s" % (ep, ", ".join(hits)))
                else:
                    missing.append(ep)
        elif kind == "ws" or r["id"] in WS_KEYWORDS:
            # 优先用具名 WS 关键词；否则用单元格里的流名（metrics/log/status/hello…）
            kws = WS_KEYWORDS.get(r["id"], [])
            if not kws:
                stream = re.sub(r"[^a-zA-Z0-9_\-]", " ", r["endpoint"].replace("WS", " ")).split()
                kws = stream[:2]
            ws_targets = section_paths if section_paths else None   # None = 全源码
            for kw in kws:
                if ws_targets is None:
                    hits = [(rel, i) for rel, i, ln in flat if kw in ln][:3]
                else:
                    hits = []
                    for p in ws_targets:
                        if p in files:
                            for li in search_lines(files[p], kw):
                                hits.append((p, li))
                                if len(hits) >= 3:
                                    break
                        if len(hits) >= 3:
                            break
                if hits:
                    evidence.append("`%s` → %s" % (kw, ", ".join("%s:%d" % (h[0].split("/")[-1], h[1]) for h in hits[:3])))
        elif kind == "subkey":
            # 形如 relayops/slo.byRoute —— 搜父端点 + 子键
            base = r["endpoint"].split(".")[0]
            hits = search_eps(flat, "/" + base.lstrip("/"))
            if hits:
                evidence.append("父端点 %s → %s（子字段需人工核对）" % (base, ", ".join(hits)))

        # 本地/WS/未命中行：尝试用功能点中文/英文片段在本页源码里找实现位置。
        # 片段必须「够特异」（全目标文件命中 ≤8 行）才算证据，避免 token/刷新 这类泛词误判。
        if not evidence and kind in ("local", "ws", "none", "subkey"):
            targets = section_paths if section_paths else [rel for rel, _i, _ln in flat]
            for frag in cn_fragments(r["feature"]):
                allhits = []
                for p in targets:
                    if p not in files:
                        continue
                    for li in search_lines(files[p], frag):
                        allhits.append("%s:%d" % (p.split("/")[-1], li))
                if 0 < len(allhits) <= 8:
                    evidence.append("文案 `%s` → %s" % (frag, ", ".join(allhits[:3])))
                    break

        # 判定
        if evidence and not missing:
            status = "✅"
        elif evidence and missing:
            status = "⚠️"
        elif kind in ("rest", "same", "same+"):
            status = "❌"
        else:
            status = "🔎"

        # 页面骨架（<1.2KB）→ 🚧 进行中（不误判为缺失）
        if r["section"] and is_stub(r["section"]) and r["section"][0] not in ("A", "V"):
            status = "🚧"
            evidence = evidence or ["页面文件为骨架（<1.2KB），实现进行中"]
            missing = []

        # 页面文件存在性（页面节）
        pagefile = page_file_for(r["section"]) if r["section"] else "?"
        if isinstance(pagefile, str) and "未找到" in pagefile and status == "✅":
            status = "⚠️"

        ov = overrides.get(r["id"])
        if ov:
            status = ov.get("status", status)
            note_ov = ov.get("note", "")
        else:
            note_ov = ""

        results.append({
            "id": r["id"], "section": r["section"], "feature": r["feature"],
            "endpoint": r["endpoint"], "kind": kind, "eps": eps,
            "status": status, "evidence": evidence, "missing": missing,
            "page": pagefile, "ref": r["ref"], "override_note": note_ov,
        })

    # ---------------- 报告 ----------------
    lines = []
    a = lines.append
    a("# 功能对账 tests/parity.md —— feature-matrix × Kotlin 实现")
    a("")
    a("> 自动生成：`python tests/parity.py`（testing 角色）。基准：`docs/api/feature-matrix.md`。")
    a("> 人工复核结论写入 `tests/parity-overrides.json` 后重新生成即可固化（不会被覆盖）。")
    a("")
    sec_counts = {}
    for r in results:
        k = r["section"][0] + " " + r["section"][1] if r["section"] else "?"
        sec_counts.setdefault(k, {"✅": 0, "⚠️": 0, "❌": 0, "🔎": 0, "🚧": 0, "N/A": 0})
        sec_counts[k][r["status"]] = sec_counts[k].get(r["status"], 0) + 1
    total = {"✅": 0, "⚠️": 0, "❌": 0, "🔎": 0, "🚧": 0, "N/A": 0}
    for v in sec_counts.values():
        for kk, vv in v.items():
            total[kk] = total.get(kk, 0) + vv
    a("## 总览")
    a("")
    a("| 状态 | 数量 | 含义 |")
    a("|---|---|---|")
    a("| ✅ 已实现 | %d | 端点/关键词在 Kotlin 源码中有字面量证据（或已人工确认） |" % total.get("✅", 0))
    a("| ⚠️ 部分 | %d | 多端点只命中一部分 / 页面文件缺失 |" % total.get("⚠️", 0))
    a("| 🚧 进行中 | %d | 页面仍是骨架文件（<1.2KB），对应功能尚未开始（不计为缺失） |" % total.get("🚧", 0))
    a("| ❌ 未见实现证据 | %d | 应调用的端点在 Kotlin 源码里搜不到 —— 重点核对 |" % total.get("❌", 0))
    a("| 🔎 待人工核对 | %d | 端点列为「–/本地/WS/子字段」且文案检索无果 |" % total.get("🔎", 0))
    a("| 功能项总数 | %d | |" % len(results))
    a("")
    a("按节：")
    a("")
    a("| 节 | 页面 | ✅ | ⚠️ | 🚧 | ❌ | 🔎 |")
    a("|---|---|---|---|---|---|---|")
    for k in sorted(sec_counts):
        v = sec_counts[k]
        a("| %s | %s | %d | %d | %d | %d | %d |" % (k.split(" ")[0], k.split(" ", 1)[1],
                                                     v.get("✅", 0), v.get("⚠️", 0), v.get("🚧", 0),
                                                     v.get("❌", 0), v.get("🔎", 0)))
    a("")

    if total.get("❌", 0):
        a("## ❌ 未见实现证据（必须逐条处理：真缺失 → 补实现；实际已实现 → 查找方式要更新）")
        a("")
        a("| 编号 | 功能点 | 应调用端点 | 页面文件 |")
        a("|---|---|---|---|")
        for r in results:
            if r["status"] == "❌":
                a("| %s | %s | %s | %s |" % (r["id"], r["feature"].replace("|", "\\|"),
                                             ", ".join(r["missing"]) or r["endpoint"], r["page"]))
        a("")

    # 按节明细
    cur = None
    for r in results:
        sec = r["section"]
        key = sec[0] + "." + sec[1] if sec else "?"
        if key != cur:
            cur = key
            a("")
            a("## %s" % key)
            a("")
            a("页面文件：%s" % r["page"])
            a("")
            a("| # | 功能点 | 端点 | 状态 | 证据（Kotlin 文件:行） | upstream 依据 |")
            a("|---|---|---|---|---|---|")
        ev = "<br>".join(r["evidence"]) if r["evidence"] else "—"
        if r["override_note"]:
            ev += "<br>📝 " + r["override_note"].replace("|", "\\|")
        if r["missing"]:
            ev += "<br>❓未搜到: " + ", ".join(r["missing"])
        a("| %s | %s | %s | %s | %s | %s |" % (
            r["id"], r["feature"].replace("|", "\\|"), (r["endpoint"] or "–").replace("|", "\\|"),
            r["status"], ev, r["ref"]))
    a("")
    a("---")
    a("")
    a("## 说明与限制")
    a("")
    a("- 自动证据 = 在 `composeApp/src/**/*.kt` 里搜端点路径字面量（如 `relayops/slo`）。"
      "若 Kotlin 侧把路径拼接/常量化导致搜不到，会误报 ❌ —— **❌ 项必须人工复核后用 "
      "`tests/parity-overrides.json` 固化结论**（`{\"B7\": {\"status\": \"✅\", \"note\": \"已实现于 DashboardPage.kt:123\"}}`）。")
    a("- 🔎 项（本地功能、WS 消息、子字段）需人工按 upstream 行号逐条点验。")
    a("- 本表与 `tests/endpoints.md`（端点清单）、`tests/report-api.md`（实测契约）互为印证："
      "端点在后端存在且契约正确 + Kotlin 有实现证据 = 该项闭环。")
    out = os.path.join(TESTS, "parity.md")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print("rows=%d  done=%d partial=%d miss=%d review=%d" % (
        len(results), total.get("✅", 0), total.get("⚠️", 0), total.get("❌", 0), total.get("🔎", 0)), flush=True)
    for r in results:
        if r["status"] == "❌":
            print("  MISS %s [%s] %s" % (r["id"], r["feature"][:24], ",".join(r["missing"])), flush=True)
    print("written: %s" % out, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
