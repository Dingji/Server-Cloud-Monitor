#!/usr/bin/env python
"""Bind Chinese UI literals to i18n keys in ONE Kotlin file.

`tools/i18n_bind2.py` walks the whole tree and, as of this writing, also wants to
bind wire-format strings it should not touch (`{"t":"data","d":…}` in the terminal
page). This tool is deliberately scoped to a single file so the blast radius is one
page, and it keeps a byte copy next to the source so a bad rewrite is one command
away from being undone.

  python tools/i18n_bind_one.py --check   composeApp/.../PhpSitesPage.kt
  python tools/i18n_bind_one.py --apply   composeApp/.../PhpSitesPage.kt
  python tools/i18n_bind_one.py --restore composeApp/.../PhpSitesPage.kt

Key derivation and the "is this trivial?" rule are imported from i18n_bind2 so the
generated keys match the rest of the codebase exactly.
"""
import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))
from i18n_bind2 import slug_key, is_trivial  # noqa: E402  (same rules as the tree-wide tool)

COMMON = REPO / "composeApp/src/commonMain/resources/i18n"
DESKTOP = REPO / "composeApp/src/desktopMain/resources/i18n"
# rehearsal override so a probe run cannot touch the real locale files
COMMON = Path(os.environ.get("I18N_COMMON_OUT", str(COMMON)))
DESKTOP = Path(os.environ.get("I18N_DESKTOP_OUT", str(DESKTOP)))
LANGS = [p.stem for p in sorted(COMMON.glob("*.json")) if p.stem != "template"]
CACHE_FILE = REPO / "tools/i18n_translate_cache.json"
CJK = re.compile(r"[\u4e00-\u9fff]")

# Kotlin text that must never become a translation key.
SKIP_HINTS = (
    "TODO", "FIXME", "import ", "package ", "com.servermaintance",
    "/php", "/ops", "/srv/php", "/etc/", "/var/", "nginx", "PHP-FPM", "php-fpm",
)


def split_literals(src: str):
    """Yield (start, end, inner, is_template) for every double-quoted Kotlin literal.

    Walks the source so that `"..."` inside a line comment or inside another
    literal's `${}` is not mistaken for a UI string.
    """
    out = []
    i = 0
    n = len(src)
    depth_stack = []          # brace depth per open template interpolation
    in_line_comment = False
    in_block_comment = False
    while i < n:
        ch = src[i]
        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            if ch == "*" and i + 1 < n and src[i + 1] == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "/":
            in_line_comment = True
            i += 2
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "*":
            in_block_comment = True
            i += 2
            continue
        if ch == '"':
            # raw strings """...""" are left alone (multi-line docs / wire formats)
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                if j < 0:
                    break
                i = j + 3
                continue
            start = i
            j = i + 1
            inner = []
            template = False
            ok = False
            while j < n:
                c = src[j]
                if c == "\\":
                    inner.append(src[j:j + 2])
                    j += 2
                    continue
                if c == '"':
                    ok = True
                    break
                if c == "$":
                    template = True
                    if j + 1 < n and src[j + 1] == "{":
                        # skip the whole interpolation so its inner literals are not
                        # collected as standalone UI strings
                        bal = 0
                        k = j + 1
                        while k < n:
                            if src[k] == "{":
                                bal += 1
                            elif src[k] == "}":
                                bal -= 1
                                if bal == 0:
                                    break
                            k += 1
                        inner.append(src[j:k + 1])
                        j = k + 1
                        continue
                    inner.append(c)
                    j += 1
                    continue
                inner.append(c)
                j += 1
            if not ok:
                i += 1
                continue
            out.append((start, j + 1, "".join(inner), template))
            i = j + 1
            continue
        i += 1
    return out


_KOTLIN_ESCAPES = {"n": chr(10), "t": chr(9), "r": chr(13), chr(34): chr(34),
                   chr(39): chr(39), chr(92): chr(92), '$': '$', 'b': chr(8), '0': chr(0)}


def unescape_kotlin(inner: str) -> str:
    """Decode Kotlin escapes without latin-1 round-tripping, which mangles CJK."""
    out = []
    i = 0
    while i < len(inner):
        c = inner[i]
        if c == "\\" and i + 1 < len(inner):
            nxt = inner[i + 1]
            if nxt in _KOTLIN_ESCAPES:
                out.append(_KOTLIN_ESCAPES[nxt])
                i += 2
                continue
            if nxt == "u" and len(inner) >= i + 6:
                try:
                    out.append(chr(int(inner[i + 2:i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
        out.append(c)
        i += 1
    return "".join(out)


def load_cache():
    try:
        raw = json.load(io.open(CACHE_FILE, encoding="utf-8"))
    except Exception:
        return {}
    by_lang = {}
    for k, v in raw.items():
        parts = k.split("|", 2)
        if len(parts) != 3:
            continue
        _, lang, text = parts
        by_lang.setdefault(lang, {})[text] = v
    return by_lang


def bind(path: Path, apply: bool):
    src = path.read_text(encoding="utf-8")
    lits = split_literals(src)
    sites = []
    for start, end, inner, template in lits:
        if not CJK.search(inner):
            continue
        if any(h in inner for h in SKIP_HINTS):
            continue
        value = unescape_kotlin(inner)
        if is_trivial(value):
            continue
        sites.append((start, end, value, template, inner))

    if not sites:
        print("没有需要绑定的中文字面量")
        return 0

    print("=== 将绑定 %d 处 ===" % len(sites))
    entries = {}
    edits = []
    deferred = []
    for start, end, value, template, inner in sites:
        line = src[:start].count("\n") + 1
        if template and (chr(92) in inner or chr(10) in value):
            # escapes + interpolation need a hand-written format string
            deferred.append((line, value))
            continue
        if template:
            fmt = []
            args = []
            lit = []
            i = 0
            while i < len(inner):
                if inner[i] == "$" and i + 1 < len(inner) and inner[i + 1] == "{":
                    bal, k = 0, i + 1
                    while k < len(inner):
                        if inner[k] == "{":
                            bal += 1
                        elif inner[k] == "}":
                            bal -= 1
                            if bal == 0:
                                break
                        k += 1
                    if lit:
                        fmt.append("".join(lit))
                        lit = []
                    expr = inner[i + 2:k]
                    if expr in args:
                        fmt.append("{%d}" % args.index(expr))
                    else:
                        args.append(expr)
                        fmt.append("{%d}" % (len(args) - 1))
                    i = k + 1
                    continue
                if inner[i] == "$":
                    m = re.match(r"[A-Za-z_][A-Za-z0-9_]*", inner[i + 1:])
                    if m:
                        if lit:
                            fmt.append("".join(lit))
                            lit = []
                        expr = m.group(0)
                        if expr in args:
                            fmt.append("{%d}" % args.index(expr))
                        else:
                            args.append(expr)
                            fmt.append("{%d}" % (len(args) - 1))
                        i += 1 + len(expr)
                        continue
                lit.append(inner[i])
                i += 1
            if lit:
                fmt.append("".join(lit))
            fmtText = "".join(fmt)
            key = slug_key(fmtText)
            entries[key] = fmtText
            edits.append((start, end, 'trf("%s", %s)' % (key, ", ".join(args))))
            continue
        key = slug_key(value)
        entries[key] = value
        edits.append((start, end, 'L["%s"]' % key))
        print("  L%-5d %s" % (line, key))

    if deferred:
        print("\n=== 跳过 %d 处带插值的模板（需手工改成 trf(key, args…)）===" % len(deferred))
        for line, value in deferred:
            print("  L%-5d %s" % (line, value[:90]))

    if not edits:
        print("没有可安全自动绑定的字面量")
        return 0

    if not apply:
        print("\n--check 模式：未写入。加 --apply 生效。")
        return 0

    bak = Path(str(path) + ".before-i18n")
    shutil.copyfile(path, bak)
    out = src
    for start, end, repl in sorted(edits, key=lambda e: -e[0]):
        out = out[:start] + repl + out[end:]
    if "import com.servermaintance.panel.i18n.L" not in out:
        m = re.search(r"^package .+$", out, re.M)
        if not m:
            print("找不到 package 行，放弃（文件已备份在 %s）" % bak)
            return 1
        out = out[:m.end()] + "\nimport com.servermaintance.panel.i18n.L" + out[m.end():]
    path.write_text(out, encoding="utf-8", newline="\n")
    print("已写入 %s（备份 %s）" % (path.name, bak))

    cache = load_cache()
    added = 0
    for lang in LANGS:
        p = COMMON / (lang + ".json")
        if not p.exists():
            print("  跳过不存在的 %s" % p)
            continue
        data = json.loads(p.read_text(encoding="utf-8"))
        before = len(data)
        for key, value in entries.items():
            if key in data:
                continue
            if lang == "zh":
                data[key] = value
            else:
                hit = cache.get(lang, {}).get(value)
                data[key] = hit if hit else value
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                     encoding="utf-8", newline="\n")
        added += len(data) - before
        missing = [k for k in entries if k not in cache.get(lang, {}) and lang != "zh"]
        note = ""
        if missing and lang != "zh":
            note = "  (%d 个词条没有机器译文，暂用中文占位)" % len(missing)
        print("  %-8s +%-4d%s" % (lang, len(data) - before, note))
    DESKTOP.mkdir(parents=True, exist_ok=True)
    for f in COMMON.glob("*.json"):
        shutil.copyfile(f, DESKTOP / f.name)
    print("已镜像到 desktopMain；本次新增 %d 条词条值" % added)
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--restore", action="store_true")
    a = ap.parse_args()
    path = Path(a.file)
    if not path.is_absolute():
        path = REPO / path
    if a.restore:
        bak = Path(str(path) + ".before-i18n")
        if not bak.exists():
            print("没有备份 %s" % bak)
            return 1
        shutil.copyfile(bak, path)
        print("已从 %s 还原" % bak.name)
        return 0
    if not path.exists():
        print("文件还不存在：%s" % path)
        return 1
    return bind(path, apply=a.apply)


if __name__ == "__main__":
    raise SystemExit(main())
