#!/usr/bin/env python
"""把 tools/tests 里「口令作为默认值写死」的六处改成必须从环境变量给。

口令本身就是生产凭据，放进可提交的脚本等于把它交给仓库历史；
这里只删默认值并加一句显式报错，不改任何调用方式。

  python tools/strip_password_defaults.py --check
  python tools/strip_password_defaults.py --apply
"""
import io
import re
import sys

FILES = [
    # 只走面板 HTTP 登录的脚本：口令缺失就没法跑，模块级直接退出最清楚。
    ("tools/deploy_files_preview.py", "SM_PANEL_PW", "PASSWORD"),
    ("tools/ops_smoke.py", "SM_PANEL_PW", "PASSWORD"),
    ("tests/api_contract.py", "SM_PANEL_PASSWORD", "PASSWORD"),
]
# SSH 类脚本（build_panel_package / test_panel_package / sh）先试密钥、口令只是兜底，
# 模块级退出会把密钥路径一起打死，所以在连接点各自加判空，不在这里改。
SSH = [
    ("tools/build_panel_package.py", "SM_PASS", "PASS"),
    ("tools/test_panel_package.py", "SM_PASS", "PASS"),
    ("tools/sh.py", "SM_PASS", "PASS"),
]

PAT = re.compile(r'^(\s*)?(\w+) = os\.environ\.get\("([A-Z_]+)",\s*"[^"]*"\)(.*)$', re.M)


def patch(path, want_var, want_name, apply):
    src = io.open(path, encoding="utf-8").read()
    out = []
    n = 0
    for m in PAT.finditer(src):
        indent, name, var, tail = m.group(1), m.group(2), m.group(3), m.group(4)
        if name != want_name or var != want_var:
            continue
        n += 1
        new = '%s%s = os.environ.get("%s", "")%s\n' % (indent, name, var, tail)
        new += ('%sif not %s:\n' % (indent, name)
                + '%s    sys.exit("需要 %s（面板登录口令）：故意不留默认值，口令不进仓库")\n' % (indent, var))
        out.append((m.start(), m.end(), new))
    if not out:
        print("  未匹配 %s" % path)
        return 0
    if apply:
        s = src
        for start, end, new in reversed(out):
            s = s[:start] + new + s[end:]
        io.open(path, "w", encoding="utf-8", newline="\n").write(s)
        print("  已改 %s（%d 处）" % (path, n))
    else:
        print("  将改 %s（%d 处）" % (path, n))
    return n


def strip_only(path, want_var, want_name, apply):
    """SSH 类脚本：只把默认值清空，判空由各自连接点负责（密钥能登时不该被打死）。"""
    src = io.open(path, encoding="utf-8").read()
    out = []
    for m in PAT.finditer(src):
        indent, name, var, tail = m.group(1), m.group(2), m.group(3), m.group(4)
        if name != want_name or var != want_var:
            continue
        new = '%s%s = os.environ.get("%s", "")%s\n' % (indent, name, var, tail)
        out.append((m.start(), m.end(), new))
    if not out:
        print("  未匹配 %s" % path)
        return 0
    if apply:
        s = src
        for start, end, new in reversed(out):
            s = s[:start] + new + s[end:]
        io.open(path, "w", encoding="utf-8", newline="\n").write(s)
    print("  %s %s（%d 处，只清默认值）" % ("已改" if apply else "将改", path, len(out)))
    return len(out)


def main():
    apply = "--apply" in sys.argv
    total = 0
    for f, var, name in FILES:
        try:
            total += patch(f, var, name, apply)
        except IOError as e:
            print("  跳过 %s: %s" % (f, e))
    for f, var, name in SSH:
        try:
            total += strip_only(f, var, name, apply)
        except IOError as e:
            print("  跳过 %s: %s" % (f, e))
    print("合计 %d 处%s" % (total, "（已写入）" if apply else "（--check，未写入）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
