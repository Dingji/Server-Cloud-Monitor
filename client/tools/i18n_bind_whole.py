#!/usr/bin/env python3
"""Replace whole UI string literals with L[\"key\"] when value matches zh.json exactly (safe)."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"
ZH = json.loads((ROOT / "composeApp/src/commonMain/resources/i18n/zh.json").read_text(encoding="utf-8"))

rev: dict[str, str] = {}
for k, v in ZH.items():
    if not v or len(v) > 160 or "${" in v:
        continue
    if not re.search(r"[\u4e00-\u9fff]", v):
        continue
    rev.setdefault(v, k)

STR = r'"(?P<body>(?:\\.|[^"\\])*)"'

PARAM = re.compile(
    r"(?P<prefix>(?:title|label|hint|placeholder|text|sub|okText|cancelText|emptyText|desc)\s*=\s*)"
    + STR
)
PANELCARD = re.compile(r"PanelCard\s*\(\s*title\s*=\s*" + STR)
EMPTY = re.compile(r"EmptyState\s*\(\s*" + STR)
BTN = re.compile(r"(?P<prefix>(?:GhostBtn|PrimaryBtn)\(text\s*=\s*)" + STR)
FORM_LABEL = re.compile(
    r'(?P<prefix>FormField\.(?:Text|Password|Select)\("[^"]+",\s*)' + STR
)
TABLECOL = re.compile(r"(?P<prefix>TableCol<[^>]+>\(\s*)" + STR)
TOAST_OK = re.compile(r"(?P<prefix>toast\(\s*[^,]+,\s*)" + STR)
MODAL_TITLE = re.compile(r"(?P<prefix>Modal\.confirm\(\s*)" + STR + r"\s*,\s*")
MODAL_PROMPT = re.compile(r"(?P<prefix>Modal\.prompt\(\s*)" + STR + r"\s*,\s*")
CHIP = re.compile(r"(?P<prefix>Chip\(\s*(?:text\s*=\s*)?)" + STR)
ROW_LABEL = re.compile(r"(?P<prefix>(?:RemoteRow|InfoRow)\(\s*)" + STR)
RCOL = re.compile(r"(?P<prefix>RCol\([^,]+,\s*)" + STR)
RELAY_SECTION = re.compile(r"(?P<prefix>RelaySection\(\s*title\s*=\s*)" + STR)

SKIP_LINE = ("L[\"", "tr(", "trf(")


def decode_body(raw: str) -> str:
    if "\\" in raw:
        return raw.encode().decode("unicode_escape").replace('\\"', '"')
    return raw


def bind(prefix: str, body: str) -> str | None:
    if "${" in body:
        return None
    key = rev.get(decode_body(body))
    if not key:
        return None
    return f'{prefix}L["{key}"]'


def fix_line(line: str) -> str:
    if any(s in line for s in SKIP_LINE):
        return line
    if "jsonObjectOf" in line:
        return line

    def run(pat: re.Pattern[str], s: str) -> str:
        def repl(m: re.Match[str]) -> str:
            out = bind(m.group("prefix"), m.group("body"))
            return out if out else m.group(0)

        return pat.sub(repl, s)

    line = run(PARAM, line)
    line = run(BTN, line)
    line = run(FORM_LABEL, line)
    line = run(TABLECOL, line)
    line = run(TOAST_OK, line)
    line = run(CHIP, line)
    line = run(ROW_LABEL, line)
    line = run(RCOL, line)
    line = run(RELAY_SECTION, line)

    def repl_modal_title(m: re.Match[str]) -> str:
        key = rev.get(decode_body(m.group("body")))
        if not key:
            return m.group(0)
        return f'{m.group("prefix")}L["{key}"], '

    if "Modal.confirm" in line and "Modal.confirm(L[" not in line:
        line = MODAL_TITLE.sub(repl_modal_title, line)

    if "Modal.prompt" in line and "${" not in line and "Modal.prompt(L[" not in line:
        line = MODAL_PROMPT.sub(repl_modal_title, line)

    def repl_card(m: re.Match[str]) -> str:
        key = rev.get(decode_body(m.group("body")))
        return f'PanelCard(title = L["{key}"]' if key else m.group(0)

    line = PANELCARD.sub(repl_card, line)

    def repl_empty(m: re.Match[str]) -> str:
        key = rev.get(decode_body(m.group("body")))
        return f'EmptyState(L["{key}"]' if key else m.group(0)

    return EMPTY.sub(repl_empty, line)


def main() -> None:
    n = 0
    for path in PANEL.rglob("*.kt"):
        if "/i18n/" in path.as_posix():
            continue
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        new_lines = [fix_line(ln) for ln in lines]
        new = "".join(new_lines)
        old = "".join(lines)
        if new != old:
            path.write_text(new, encoding="utf-8")
            n += 1
    print(n)


if __name__ == "__main__":
    main()
