#!/usr/bin/env python
"""Fill the English locale for the 分发 Key page (and seed the translate cache).

The i18n binder writes a Chinese placeholder for every key it has no machine
translation for -- which is all 35 keys this page added. This script replaces the
`en` placeholders with real English and records the same pairs in
`tools/i18n_translate_cache.json` so a future bind does not lose them again.

  python tools/i18n_fill_en_distkeys.py           # report what would change
  python tools/i18n_fill_en_distkeys.py --write   # write it

Placeholders ({0} / {1}) are asserted to survive the translation: a format string
that drops an argument would render a literal "{0}" in the UI.
"""
import io
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
I18N = os.path.join(REPO, "composeApp", "src", "%s", "resources", "i18n")
CACHE = os.path.join(REPO, "tools", "i18n_translate_cache.json")

EN = {
    "{0}上限（token，0=不限 · {1}）": "{0} cap (tokens, 0 = unlimited · {1})",
    "{0}已用": "{0} used",
    "1 号 00:00 重置": "Resets on the 1st, 00:00",
    "上限合计 {0}": "Total cap {0}",
    "不限": "Unlimited",
    "主令牌 · 不受额度限制": "Master token · exempt from quotas",
    "例：张三的笔记本": "e.g. Bob's laptop",
    "分发 Key": "Distribution keys",
    "创建于 {0}": "Created {0}",
    "删除 key": "Delete key",
    "删除后这枚 key 立刻不可用，且无法恢复（历史用量仍保留）。确认删除？":
        "This key stops working immediately and cannot be restored (usage history is kept). Delete it?",
    "剪贴板不可用，请改用「新建 key」重新生成一枚":
        "Clipboard unavailable - use \"New key\" to generate a fresh one",
    "名单文件 {0}，仓库更新 {1}": "Credential list {0}, store updated {1}",
    "启用中": "Enabled",
    "周一 00:00 重置": "Resets Monday 00:00",
    "响应里没有 key 字段": "No key field in the response",
    "均未设上限": "No caps configured",
    "复制完整 key": "Copy full key",
    "完整 key 已复制到剪贴板": "Full key copied to clipboard",
    "客户端把 OPENCODE_RELAY_KEY 填成这里的 key 即可；官方 key 由中继替换，不落客户端。":
        "A client just sets OPENCODE_RELAY_KEY to one of these keys; the relay swaps in the "
        "official key, so it never reaches the client.",
    "客户端持有的内部 key": "Internal keys held by clients",
    "已生成": "Created",
    "  已超": "  over",
    "改额度 / 标签": "Edit caps / label",
    "新建 key": "New key",
    "新建分发 Key —— 给谁用": "New distribution key - who is it for?",
    "生成": "Generate",
    "用途 / 使用人": "Purpose / user",
    "请填写用途标签": "Please enter a purpose label",
    "调整额度 - {0}": "Adjust caps - {0}",
    "超额": "over quota",
    "还没有分发 key，点右上「新建 key」生成第一枚。":
        "No distribution keys yet - use \"New key\" at the top right to create the first one.",
    "这枚 key 只显示这一次，请立刻复制保存":
        "This key is shown only once - copy and save it now",
    "重新下发名单": "Re-push credential list",
    "额度口径：时=滚动 1 小时，周=本自然周（周一起），月=本自然月（1 号起），0 为不限。":
        "Quota windows: hour = rolling 1 h, week = calendar week (from Monday), "
        "month = calendar month (from the 1st); 0 means unlimited.",
}

PLACE = re.compile(r"\{(\d+)\}")


def placeholders(s):
    return sorted(PLACE.findall(str(s)))


def main():
    write = "--write" in sys.argv
    touched = 0
    for tree in ("commonMain", "desktopMain"):
        path = os.path.join(I18N % tree, "en.json")
        with io.open(path, encoding="utf-8") as f:
            table = json.load(f)
        zh_path = os.path.join(I18N % tree, "zh.json")
        with io.open(zh_path, encoding="utf-8") as f:
            zh = json.load(f)
        changed = []
        for src, english in EN.items():
            key = next((k for k, v in zh.items() if v == src), None)
            if key is None:
                print("  跳过（zh 里没有这条原文）:", src[:40])
                continue
            if placeholders(src) != placeholders(english):
                raise SystemExit("占位符不匹配: %r -> %r" % (src, english))
            if table.get(key) != english:
                changed.append((key, table.get(key), english))
                if write:
                    table[key] = english
        print("%s: %d 条待改" % (tree, len(changed)))
        for key, old, new in changed[:3]:
            print("   %-38s %s -> %s" % (key, (old or "")[:28], new[:38]))
        if write and changed:
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(table, f, ensure_ascii=False, indent=2)
                f.write("\n")
            touched += 1
    if not write:
        print("\n只报告。加 --write 生效。")
        return
    with io.open(CACHE, encoding="utf-8") as f:
        cache = json.load(f)
    for src, english in EN.items():
        cache["zh-CN|en|%s" % src] = english
    with io.open(CACHE, "w", encoding="utf-8", newline="\n") as f:
        json.dump(cache, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("已写入 %d 份 en.json，并把 %d 条配对进翻译缓存" % (touched, len(EN)))


if __name__ == "__main__":
    main()
