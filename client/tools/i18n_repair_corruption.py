#!/usr/bin/env python3
"""Repair common corruption patterns from over-aggressive i18n apply."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"

REPLACEMENTS = [
    (r'\.str\("([a-zA-Z0-9_]+)L\["ifempty_eab27c0cb0"\]([^"]*)"\)', r'.str("\1").ifEmpty { "\2" }'),
    (r'\.str\("([a-zA-Z0-9_]+)L\["ifempty_e74ff11527"\]([^"]*)"\)', r'.str("\1")?.ifEmpty { "\2" }'),
    (r'r\.arr\("hitsL\["size_张_c482640b75"\]', r'r.arr("hits").size} 张"'),
    (r'h\.str\("matchedL\["行匹配_cc40c77c63"\]', r'h.str("matched")} 行匹配"'),
    (r'str\("logL\["s_41025dde12"\]', r'str("logs")'),
    (r'str\("confL\["s_41025dde12"\]', r'str("conf")'),
    (r'str\("bannedL\["ifempty_e74ff11527"\]无数据L\["s_58a35a778c"\]无数据"', r'str("banned")?.ifEmpty { "无数据" } ?: "无数据"'),
    (r'str\("uriL\["ifempty_eab27c0cb0"\]当前" \}\}', r'str("uri").ifEmpty { "当前" }'),
    (r'str\("macL\["ifempty_eab27c0cb0"\]-\" \}\}', r'str("mac").ifEmpty { "-" }'),
    (r'str\("cache_hit_pctL\["ifempty_eab27c0cb0"\]-\" \}\}', r'str("cache_hit_pct").ifEmpty { "-" }'),
    (r'str\("totalL\["个文档_41511704e0"\]', r'str("total")} 个文档"'),
    (r'tr\("已切换到_m_str_cff01393d0"\)name"\}\}"', r'"已切换到 ${m.str("name")}"'),
    (r'tr\("已删除_res_str_40feedb5ac"\)affectedtr\("行_8434b68b5f"\)', r'"已删除 ${res.str("affected")} 行"'),
    (r'tr\("已恢复_note_title_ifempty_76ec77831b"\)未命名笔记" \}\}」"', r'"已恢复「${note.title.ifEmpty { "未命名笔记" }}」"'),
    (r'tr\("操作失败_e_message_51f0ed8d89"\)未知错误"\}\}"', r'"操作失败: ${e.message ?: "未知错误"}"'),
]


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        orig = text
        for pat, repl in REPLACEMENTS:
            text = re.sub(pat, repl, text)
        if text != orig:
            path.write_text(text, encoding="utf-8")
            n += 1
    print(f"Repaired {n} files")


if __name__ == "__main__":
    main()
