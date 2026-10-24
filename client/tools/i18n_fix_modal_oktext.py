#!/usr/bin/env python3
"""Fix Modal.confirm(..., "body?, okText -> "body", okText"""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def fix(s: str) -> str:
    s = re.sub(r"([？！。])(, okText)", r'\1", okText', s)
    s = re.sub(
        r'(Modal\.confirm\("执行 DDL 操作), text,',
        r'Modal.confirm("执行 DDL 操作", text,',
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
