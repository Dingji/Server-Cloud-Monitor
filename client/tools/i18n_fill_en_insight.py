#!/usr/bin/env python
"""Fill the English catalogue for the relayhub 运维洞察 card.

The binder seeds every locale (18 of them) with the Chinese source when the
translation cache has no entry; en.json is the one we keep hand-curated because
it is also what the screenshots and the docs get compared against.

  python tools/i18n_fill_en_insight.py           # write
  python tools/i18n_fill_en_insight.py --check   # report only
"""
import io
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
EN = REPO / "composeApp/src/commonMain/resources/i18n/en.json"
EN2 = REPO / "composeApp/src/desktopMain/resources/i18n/en.json"

# {0}/{1} 等占位符必须与 zh.json 保持一致，缺一个就会在运行时留下裸括号
TEXT = {
    "运维洞察_0d605390b1": "Ops insight",
    "读取中_b21b631cd5": "Loading",
    "中继洞察不可用_76967ba19f": "Insight unavailable",
    "中继服务未响应_b597560763": "Relay service not responding",
    "快照_0_1_38111bbc1f": "Snapshot {0}{1}",
    "秒前_9132a8b22c": "s ago",
    "已过期_500ca81c2d": " (stale)",
    "刷新_38108eaa1d": "Refresh",
    "端口池_a16ed2a7ad": "Port pool",
    "余量充足_4fba79c0f1": "Plenty left",
    "偏紧_6b13e3c506": "Running tight",
    "见底_4ea988cea4": "Nearly exhausted",
    "0_1_空闲_已用_2_3_f68d168702": "{0} / {1} free - {2} in use ({3})",
    "空闲连续段_0_处_最长可分配_1_09597cc122": "{0} free runs - longest assignable {1}",
    "范围过大未统计碎片度_e651b8c259": "Range too wide, fragmentation not counted",
    "配置漂移_87e1a7f644": "Config drift",
    "frps_与面板一致_e437f1b1e9": "frps matches the panel",
    "frps_未覆盖_73ea2eb0e7": "Not covered by frps",
    "期望_0_5021b13ba5": "Expected {0}",
    "缺段_0_这些端口里的隧道会被_frp_fc8fb25b88": "Missing ranges {0} - tunnels in them are rejected by frps outright",
    "frps_单机上限_0_面板策略_1_b3ce606d62": "frps per-client cap {0} < panel policy {1}; extra tunnels are rejected too",
    "ufw_托管规则_0_条_受保护端口_8c582b79b5": "{0} managed ufw rules - {1} protected ports",
    "未设置_55a04b58cd": "Not set",
    "隧道健康_1b58079e23": "Tunnel health",
    "无异常_0020abec9d": "No issues",
    "0_条报错_e93727172f": "{0} with errors",
    "0_条抖动_2222b0d0ed": "{0} flapping",
    "0_1_在线_活动连接_2_5505e8ddbf": "{0} / {1} online - active conns {2} - today down {3} up {4}",
    "最近掉线_0_42cb059e7e": "Recently closed: {0}",
    "0_1_2_分钟前_e9972adaae": "{0}/{1} closed {2} min ago",
    "离线设备_0_896f42ca22": "Offline devices: {0}",
    "在线但无隧道_0_frpc_活着_映射是空的_51870d25d7": "Online but no tunnels: {0} (frpc alive, mappings empty)",
    "公网探测_6cc512434f": "Public probes",
    "全部可达_c988faee86": "All reachable",
    "0_条不可达_a234a3fb8f": "{0} unreachable",
    "覆盖_0_1_台_平均_2_933b010e86": "Covers {0} / {1} devices - avg {2} ms",
    "不可达_358801edaa": "Unreachable",
    "探测只能按超时推断_被拦_云安全组是否放行需以_cd839a2266": "A timeout only implies \"blocked\"; check the cloud security group in its own console.",
}


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def main():
    check = "--check" in sys.argv
    zh = load(REPO / "composeApp/src/commonMain/resources/i18n/zh.json")
    bad = [k for k in TEXT if k not in zh]
    if bad:
        print("FAIL: 这些键在 zh.json 里不存在（先跑 binder）:", ", ".join(bad))
        return 2
    for path in (EN, EN2):
        if not path.exists():
            print("skip (no such catalogue):", path)
            continue
        d = load(path)
        changed = 0
        for k, v in TEXT.items():
            if d.get(k) != v:
                if check:
                    print("  %s: %r -> %r" % (k, d.get(k), v))
                else:
                    d[k] = v
                changed += 1
        missing = [k for k in TEXT if k not in d]
        if missing:
            print("FAIL: %s 缺键 %s" % (path.name, ", ".join(missing)))
            return 3
        if check:
            print("%s: 待改写 %d 条" % (path, changed))
            continue
        if changed:
            with io.open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(d, f, ensure_ascii=False, indent=2, sort_keys=True)
                f.write("\n")
        print("%s: 写入 %d 条" % (path, changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
