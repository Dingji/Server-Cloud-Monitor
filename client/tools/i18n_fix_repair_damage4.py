#!/usr/bin/env python3
"""Fix strings where repair merged '+ if (' inside the literal."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def fix(s: str) -> str:
    # "text + if ( -> "text" + if (
    s = re.sub(r'"([^"\n]+?) \+ if \(', r'"\1" + if (', s)
    # merged sub/icon params: sub = "text, icon = "wlan"
    s = re.sub(
        r'(sub = )"([^"]+?), icon = "([^"]+)"',
        r'\1"\2", icon = "\3"',
        s,
    )
    # ) else "" missing quote before else (CJK closing paren only — avoid encodeUrl() etc.)
    s = re.sub(r'(）)( else "")', r'\1" else ""', s)
    s = re.sub(r'(）)( else L\[)', r'\1" else L[', s)
    # Modal.confirm("title, expr + if
    s = re.sub(
        r'Modal\.confirm\("([^"]+?), ([a-zA-Z_]+) \+ if',
        r'Modal.confirm("\1", \2 + if',
        s,
    )
    # "执行 $act ？ + if -> "执行 $act ？" + if
    s = s.replace('"执行 $act ？ + if', '"执行 $act ？" + if')
    s = s.replace('"子任务 + if', '"子任务" + if')
    s = s.replace('"写入 $p？ + if', '"写入 $p？" + if')
    s = s.replace('sub = "总成功率  + if', 'sub = "总成功率 " + if')
    # OpsChannels broken TableText
    s = s.replace('L["已存_it_secretset_jointo_371806c69d"],")}",', 'L["已存_it_secretset_jointo_371806c69d"])')
    return s


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        orig = path.read_text(encoding="utf-8")
        new = fix(orig)
        if new != orig:
            path.write_text(new, encoding="utf-8")
            n += 1
    print(f"Fixed {n} files")


if __name__ == "__main__":
    main()
