#!/usr/bin/env python
"""修掉上一次用内嵌 heredoc 改源文件时被转义弄坏的 mdNormalizeNewlines。

内嵌 shell 里的 \\r \\n 会被吃掉一层，写进 Kotlin 就成了真换行 —— 这正是
「不要用 shell 工具重写中文源文件」那条老坑的又一次现场。这里按行号整段替换。
  python tools/fix_md_normalize_line.py
"""
import io

P = "composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/NoteMarkdownBlocks.kt"

NEW = [
    "/**",
    " * 换行归一：CRLF 与孤立 CR 都收成 LF。",
    " * 编辑缓冲与解析器必须用同一份文本，否则块偏移与光标偏移会逐块错位；",
    " * 从 Windows 贴进来的笔记带 CRLF 是常态。",
    " */",
    "internal fun mdNormalizeNewlines(src: String): String =",
    "    if (!src.contains('\\r')) src else src.replace(\"\\r\\n\", \"\\n\").replace('\\r', '\\n')",
    "",
]

lines = io.open(P, encoding="utf-8").read().split("\n")
start = None
anchor = None
for i, l in enumerate(lines):
    if start is None and l.startswith(" * 换行归一"):
        start = i - 1                       # 该块的 /** 行
    if l.startswith("/** Parse markdown"):
        anchor = i                          # 下一个函数的注释起点，替换到此为止
        break
if start is None or anchor is None or anchor <= start:
    raise SystemExit("没定位到 mdNormalizeNewlines 那段：start=%s anchor=%s" % (start, anchor))

out = lines[:start] + NEW + lines[anchor:]
io.open(P, "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("替换了第 %d–%d 行" % (start + 1, anchor))
