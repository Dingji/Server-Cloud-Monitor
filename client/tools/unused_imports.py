"""扫描 Kotlin 源文件里「导入了但正文再没出现过」的符号。

kotlin-cli 不会为未使用的 import 报警告（IDE 才会），所以「0 warning」要真做到位，
得自己扫一遍。判定很保守：只按标识符是否在 import 行之外出现过一次来算，
命中后仍需人工确认（运算符 / 注解 / 仅用于 KDoc 的引用都可能被误判）。

用法: python tools/unused_imports.py [src目录 ...]
"""
import os
import re
import sys

IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$")

# `var x by mutableStateOf(...)` 需要这对属性委托操作符在作用域里，
# 但代码里永远不会写出 `getValue` 这个标识符 —— 按文本判重就是误报，直接放行。
DELEGATE_ACCESSORS = {"getValue", "setValue", "provideDelegate", "delegate"}


def scan_file(path):
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    imports = []
    body = []
    for ln in lines:
        m = IMPORT_RE.match(ln.strip())
        if m:
            imports.append((m.group(1), m.group(2)))
        else:
            body.append(ln)
    text = "\n".join(body)
    out = []
    for dotted, alias in imports:
        sym = alias or dotted.rsplit(".", 1)[-1]
        if sym == "*" or sym in DELEGATE_ACCESSORS:
            continue
        if not re.search(r"\b" + re.escape(sym) + r"\b", text):
            out.append(dotted if not alias else f"{dotted} as {alias}")
    return out


def fix_file(path, doomed):
    """删掉判定为未使用的 import 行。newline='' 读写，保住 Windows CRLF。"""
    with open(path, encoding="utf-8", newline="") as fh:
        lines = fh.readlines()
    kept, removed = [], 0
    for ln in lines:
        m = IMPORT_RE.match(ln.strip())
        if m:
            dotted, alias = m.group(1), m.group(2)
            label = dotted if not alias else f"{dotted} as {alias}"
            if label in doomed:
                removed += 1
                continue
        kept.append(ln)
    if removed:
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.writelines(kept)
    return removed


def main():
    argv = sys.argv[1:]
    do_fix = "--fix" in argv
    roots = [a for a in argv if not a.startswith("--")] or ["composeApp/src"]
    hits = 0
    files_changed = 0
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for f in sorted(files):
                if not f.endswith(".kt"):
                    continue
                p = os.path.join(dirpath, f)
                dead = scan_file(p)
                if not dead:
                    continue
                hits += len(dead)
                if do_fix:
                    n = fix_file(p, set(dead))
                    files_changed += 1
                    print(f"fixed {p}: -{n} import")
                else:
                    for imp in dead:
                        print(f"{p}: {imp}")
    print(("removed %d unused imports from %d files" % (hits, files_changed))
          if do_fix else f"total: {hits}")


if __name__ == "__main__":
    main()
