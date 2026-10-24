#!/usr/bin/env python3
"""Fix recurring syntax damage not covered by other repair scripts."""
import re
from pathlib import Path

PANEL = Path(__file__).resolve().parents[1] / "composeApp/src/commonMain/kotlin/com/servermaintance/panel"


def fix(s: str) -> str:
    s = s.replace('}", icon =', '}, icon =')
    s = re.sub(r'onClick = onRetry", icon =', r'onClick = onRetry, icon =', s)
    s = re.sub(r'toast\(err", ', r'toast(err, ', s)
    s = re.sub(r'enabled = query\.isNotBlank\(\)", icon', r'enabled = query.isNotBlank(), icon', s)
    s = re.sub(r'else "失败：\) \+', r'else "失败：") +', s)
    s = re.sub(r'\.take\((\d+)\)" \+', r'.take(\1) +', s)
    s = re.sub(r'r\.counts\[warn"\]', r'r.counts["warn"]', s)
    s = re.sub(r'r\.counts\[info"\]', r'r.counts["info"]', s)
    s = re.sub(
        r'FormField\.Password\("password", "([^"]+), value = if \(h\.hasPassword"\)',
        r'FormField.Password("password", "\1", value = if (h.hasPassword)',
        s,
    )
    s = s.replace('(ACL), "', '(ACL)", "')
    s = re.sub(
        r'"dbbackup-\$\{st\.engineId\}, "schedule"',
        '"dbbackup-${st.engineId}", "schedule"',
        s,
    )
    s = s.replace('listOf("关闭 (0), "仅慢查询 (1)"', 'listOf("关闭 (0)", "仅慢查询 (1)"')
    s = re.sub(r'text = "([^"]+), onClick', r'text = "\1", onClick', s)
    s = re.sub(r'"([\u4e00-\u9fff]+) -> L\[', r'"\1" -> L[', s)
    s = re.sub(r'InfoRow\("([^"]+), ', r'InfoRow("\1", ', s)
    s = s.replace('labelText()" + if', 'labelText() + if')
    s = s.replace('.replace("\\n, "\\r\\n")', '.replace("\\n", "\\r\\n")')
    s = re.sub(r'\)\)", sub =', r')), sub =', s)
    s = re.sub(r'title = "([^"]+), weight =', r'title = "\1", weight =', s)
    s = re.sub(r'会话平均  \+ \(if', r'会话平均 " + (if', s)
    s = s.replace('folderColorNames", "', 'folderColorNames, "')
    s = s.replace('src.replace("\\r\\n, "\\n")', 'src.replace("\\r\\n", "\\n")')
    s = re.sub(r'setOf\("true, "false, "null"\)', 'setOf("true", "false", "null")', s)
    s = re.sub(r'("\$\w+), "([a-z]+)"', r'\1", "\2"', s)
    s = re.sub(r'"true, "1"', '"true", "1"', s)
    s = re.sub(r'\.arr\(([a-zA-Z_]+)"\)', r'.arr("\1")', s)
    s = re.sub(
        r'Modal\.confirm\("([^"]*?), "([^"])',
        r'Modal.confirm("\1", "\2',
        s,
    )
    s = re.sub(
        r'\+ if \(danger\) "\n([^"]+)" else ""',
        lambda m: '+ if (danger) "\\n' + m.group(1).replace("\n", "") + '" else ""',
        s,
        flags=re.MULTILINE,
    )
    s = s.replace(
        'Regex("[\\\\[\\\\]() ]), "")',
        'Regex("[\\\\[\\\\]() ]"), "")',
    )
    s = s.replace(
        'insertAt("\\n```\\n\\n```\\n, "\\n```\\n".length + 1)',
        'insertAt("\\n```\\n\\n```\\n", "\\n```\\n".length + 1)',
    )
    s = re.sub(
        r'InstallTarget\("([^"]+)", "([^"]+?), "(apt[^"]*)"\)',
        r'InstallTarget("\1", "\2", "\3")',
        s,
    )
    s = re.sub(
        r'InstallTarget\("([^"]+)", "([^"]+?), "(添加[^"]*)"\)',
        r'InstallTarget("\1", "\2", "\3")',
        s,
    )
    s = s.replace("substringBeforeLast('/'", "substringBeforeLast('/',")
    s = re.sub(r'truncate\(l\.path\)", "', r'truncate(l.path), "', s)
    s = re.sub(r'value = st\.where", hint', 'value = st.where, hint', s)
    # listOf timezone / IANA ids
    s = re.sub(
        r'"([A-Za-z][A-Za-z0-9_/]*), "([A-Za-z][^"]*)"',
        r'"\1", "\2"',
        s,
    )
    s = re.sub(r'== "([^"]+)\) ""', r'== "\1") ""', s)
    s = re.sub(
        r'ifEmpty \{ "([^"]+) \}\}',
        r'ifEmpty { "\1" }}',
        s,
    )
    s = re.sub(r'setOf\("true, "false, "null"\)', 'setOf("true", "false", "null")', s)
    s = re.sub(r'\)", "', '), "', s)
    s = re.sub(r' to (\w+)", "', r' to \1, "', s)
    s = re.sub(r'(\w+\.\w+)", "', r'\1, "', s)
    s = re.sub(r'> (\d+)"\)', r'> \1)', s)
    s = s.replace('Regex("\\\\W+")", "_")', 'Regex("\\\\W+"), "_")')
    s = s.replace('](https://), "链接文字")', '](https://)", "链接文字")')
    s = s.replace('ToolbarItem(", "", sep = true)', 'ToolbarItem("|", "", sep = true)')
    s = s.replace(
        'Modal.confirm("跨库复制", "将 $db.$tbl 复制到 $target + (if (withData") L["含数据_6aedaad68f"] else "（仅结构）) + "？")',
        'Modal.confirm("跨库复制", "将 $db.$tbl 复制到 $target" + (if (withData) L["含数据_6aedaad68f"] else "（仅结构）") + "？")',
    )
    s = re.sub(
        r'\$\{if \(([^)]+)\) ([\u4e00-\u9fff]+)" else',
        r'${if (\1) "\2" else',
        s,
    )
    s = s.replace('                            "),', '                            ),')
    s = re.sub(
        r'PaletteCmd\("([^"]+), L\.fmt\(',
        r'PaletteCmd("\1", L.fmt(',
        s,
    )
    s = s.replace('listOf("全部) +', 'listOf("全部") +')
    s = re.sub(
        r'"dbbackup-\$\{st\.engineId\}, "schedule"',
        '"dbbackup-${st.engineId}", "schedule"',
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
