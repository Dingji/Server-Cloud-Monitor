#!/usr/bin/env python3
"""Convert object/top-level `val` properties whose initializer uses L[...]/tr(...)/trf(...)
into `val ... get() = ...` so the text resolves on every read (language switch applies
immediately instead of freezing at class-load time).

Local vals inside functions are left alone (they already re-evaluate per call).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kt_scan import scan  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PANEL_SRC = [
    ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel",
    ROOT / "composeApp/src/androidMain/kotlin/com/servermaintance/panel",
    ROOT / "composeApp/src/desktopMain/kotlin/com/servermaintance/panel",
    ROOT / "composeApp/src/iosMain/kotlin/com/servermaintance/panel",
]

VAL_RE = re.compile(
    r"^(\s*)((?:(?:private|internal|public|protected|actual|expect)\s+)*)(val)\s+"
    r"(<[^>]+>\s+)?([\w?]+)(\s*:\s*[^=]+?)?(\s*)=(?!=)"
)
I18N_USE = re.compile(r'\bL\[|\btr\(|\btrf\(|\bStringRepository\.')


def mask(text: str) -> str:
    """Replace string literals & comments with spaces (same length) for brace counting."""
    out = list(text)
    for lit in scan(text):
        for i in range(lit.start, lit.end):
            out[i] = " "
    # comments
    i = 0
    n = len(text)
    while i < n - 1:
        if text[i] == "/" and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        elif text[i] == "/" and text[i + 1] == "*":
            depth = 0
            while i < n - 1:
                if text[i] == "/" and text[i + 1] == "*":
                    depth += 1
                    out[i] = out[i + 1] = " "
                    i += 2
                    continue
                if text[i] == "*" and text[i + 1] == "/":
                    depth -= 1
                    out[i] = out[i + 1] = " "
                    i += 2
                    if depth == 0:
                        break
                    continue
                if text[i] != "\n":
                    out[i] = " "
                i += 1
        else:
            i += 1
    return "".join(out)


def process(apply: bool) -> None:
    total = 0
    files_changed = 0
    for base in PANEL_SRC:
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.kt")):
            if "/i18n/" in path.as_posix():
                continue
            text = path.read_text(encoding="utf-8")
            masked = mask(text)
            lines = text.splitlines(keepends=True)
            mlines = masked.splitlines()
            # depth before each line
            depth = 0
            depths: list[int] = []
            top_ctx = ""
            top_ctx_at: dict[int, str] = {}
            for ln in mlines:
                depths.append(depth)
                opened = ln.count("{") - ln.count("}")
                if depth == 0 and opened > 0:
                    top_ctx_at[len(depths)] = ln.strip()  # context for depth-1 lines
                depth += opened
            edits: list[tuple[int, int, str]] = []
            i = 0
            while i < len(lines):
                m = VAL_RE.match(lines[i])
                if m and I18N_USE.search("".join(lines[i : i + 40])):
                    d = depths[i]
                    ok = d == 0
                    if d == 1:
                        ctx = ""
                        for j in range(i, -1, -1):
                            if depths[j] == 0 and j < len(depths):
                                # find the top-level construct that opened depth 1
                                pass
                        # locate enclosing top-level declaration line
                        for j in range(i, -1, -1):
                            if depths[j] == 0:
                                ctx = lines[j]
                                break
                        ok = bool(re.search(r"\b(?:object|class|interface)\b", ctx)) and not re.search(
                            r"\bfun\b", ctx
                        )
                    if ok:
                        # statement extent: from this line until parens/braces balance
                        extent = []
                        bal = 0
                        k = i
                        while k < len(lines) and k < i + 200:
                            extent.append(lines[k])
                            bal += lines[k].count("(") - lines[k].count(")")
                            bal += lines[k].count("{") - lines[k].count("}")
                            k += 1
                            last = lines[k - 1].rstrip()
                            if bal <= 0 and not last.endswith(("=", "+", ",", "(", "{", "(", "\"")):
                                break
                        body = "".join(extent)
                        if I18N_USE.search(body) and " get() =" not in body and " get()=" not in body:
                            line = lines[i]
                            # group 7 is the whitespace right before '='; replace '=' with 'get() ='
                            new_line = line[: m.start(7)] + " get() =" + line[m.end(7) + 1 :]
                            edits.append((i, i + 1, new_line))
                            total += 1
                            rel = path.relative_to(ROOT).as_posix()
                            print(f"{rel}:{i + 1}  {line.strip()[:120]}")
                i += 1
            if edits and apply:
                for start, end, new_line in reversed(edits):
                    lines[start : end] = [new_line]
                path.write_text("".join(lines), encoding="utf-8")
                files_changed += 1
    print(f"\ntotal properties to convert: {total}; files: {files_changed} (apply={apply})")


if __name__ == "__main__":
    process(apply="apply" in sys.argv)
