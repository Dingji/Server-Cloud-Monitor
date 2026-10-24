#!/usr/bin/env python3
"""Close broken string literals before else L["key"] (repair_broken_L / bind damage)."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"

# ) "text else L["key"] -> ) "text" else L["key"]
P1 = re.compile(r'\) "([^"]+?) else L\["([^"]+)"\]')
# "text else L["key"] at end of arg (no leading paren)
P2 = re.compile(r'([,=(\s])"([^"]+?) else L\["([^"]+)"\]')
# value = if (...) "已配置 else L["key"],
P3 = re.compile(r'(if \([^)]+\) )"([^"]+?) else L\["([^"]+)"\]')
# + if (...) "… else ""
P4 = re.compile(
    r'"([^"]+?) \+ if \(([^)]+)\) "([^"]*)" else "([^"]*)"',
)
P5 = re.compile(r'if \(([^)]+)\) "([^"]+?) else "([^"]+)"\)')
P6 = re.compile(r'value = "\$\{([^}]+)\}, sub = ')
P7 = re.compile(r'(\$\{[^}]+\}) ([^"]+?) else "')
P8 = re.compile(r'if \(([^)]+)\) "([^"]+?) else ""')


def fix(s: str) -> str:
    s = P1.sub(lambda m: f') "{m.group(1)}" else L["{m.group(2)}"]', s)
    s = P2.sub(lambda m: f'{m.group(1)}"{m.group(2)}" else L["{m.group(3)}"]', s)
    s = P3.sub(lambda m: f'{m.group(1)}"{m.group(2)}" else L["{m.group(3)}"]', s)
    s = P4.sub(
        lambda m: f'"{m.group(1)}" + if ({m.group(2)}) "{m.group(3)}" else "{m.group(4)}"',
        s,
    )
    s = P5.sub(lambda m: f'if ({m.group(1)}) "{m.group(2)}" else "{m.group(3)}")', s)
    s = P6.sub(lambda m: f'value = "${{{m.group(1)}}}", sub = ', s)
    s = P7.sub(lambda m: f'{m.group(1)} {m.group(2)}" else "', s)
    s = P8.sub(lambda m: f'if ({m.group(1)}) "{m.group(2)}" else ""', s)
    s = s.replace('joinToString(", ")" +', 'joinToString(", ") +')
    s = s.replace('revoke")" + if', 'revoke") + if')
    s = s.replace(
        '+ if (pinned) "（已收藏，长按取消） else ""',
        '+ if (pinned) L["nav.pinned_hint"] else ""',
    )
    s = s.replace(
        'Modal.confirm("创建角色, "创建 $n" + if (superU) " （超级用户） else "")',
        'Modal.confirm(L["创建角色_24cf247879"], "创建 $n" + if (superU) " （超级用户）" else "")',
    )
    s = re.sub(
        r'if \(creating\) "([^"]+?) else user\?\.str\("email"\)',
        r'if (creating) L["创建_notebox_账号并分配角色_2911541ceb"] else user?.str("email")',
        s,
    )
    return s


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        t = path.read_text(encoding="utf-8")
        nt = fix(t)
        if nt != t:
            path.write_text(nt, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
