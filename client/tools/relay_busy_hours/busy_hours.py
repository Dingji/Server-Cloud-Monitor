#!/usr/bin/env python3
"""忙时路由的开关、状态与预览（在服务器上以 root 运行）。

  python3 /opt/relay/busy_hours.py status          # 当前策略 + 是否生效 + tap 计数
  python3 /opt/relay/busy_hours.py off             # 临时停用（不重启、不改配置文件）
  python3 /opt/relay/busy_hours.py on              # 恢复
  python3 /opt/relay/busy_hours.py check 10:30     # 预览北京时间 10:30 会发生什么
  python3 /opt/relay/busy_hours.py windows         # 窗口与今天的边界

策略本体在 /opt/relay/usagetap.py 的 BUSY_HOURS_MARK 段里；本脚本只是读同一段
的函数，绝不另写一份判断，避免两边漂移。配置文件 /etc/relay/busy-hours.json
由 tap 按 mtime 自动重载，改完不用重启 relay-usagetap。
"""
import importlib.util
import json
import os
import sys
from datetime import datetime, timedelta, timezone

TAP_PATH = "/opt/relay/usagetap.py"
CONF_PATH = "/etc/relay/busy-hours.json"


def load_tap():
    spec = importlib.util.spec_from_file_location("usagetap", TAP_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def local_now(tap):
    doc = tap.busy_conf()
    return datetime.now(timezone(timedelta(minutes=int(doc.get("utc_offset_minutes", 480)))))


def parse_at(text, tap):
    """'HH:MM' today, or 'YYYY-MM-DD HH:MM', in the policy's own offset."""
    doc = tap.busy_conf()
    tz = timezone(timedelta(minutes=int(doc.get("utc_offset_minutes", 480))))
    text = (text or "").strip()
    for fmt in ("%Y-%m-%d %H:%M", "%H:%M"):
        try:
            d = datetime.strptime(text, fmt)
            if fmt == "%H:%M":
                d = d.replace(year=local_now(tap).year, month=local_now(tap).month,
                              day=local_now(tap).day)
            return d.replace(tzinfo=tz)
        except ValueError:
            continue
    raise SystemExit("时间格式应为 HH:MM 或 YYYY-MM-DD HH:MM，收到：%r" % text)


def status(tap):
    doc = tap.busy_conf()
    now = local_now(tap)
    allowed = sorted(tap.go_allowlist())
    active = tap.busy_window_active(doc, now)
    print("配置文件      : %s %s" % (CONF_PATH, "(存在)" if os.path.exists(CONF_PATH) else "(缺失，用内置默认)"))
    print("enabled       : %s" % doc.get("enabled"))
    print("临时开关      : %s" % ("已停用 " + tap.BUSY_OFF_FLAG if tap.busy_off() else "未停用"))
    print("作用线路      : route=%s（其余线路完全不动）" % doc.get("route"))
    print("改写前缀      : %s" % ", ".join(doc.get("rewrite_prefixes") or []))
    print("目标模型      : %s" % doc.get("target_model"))
    print("仅工作日      : %s（今天 %s，weekday=%d）" % (doc.get("weekdays_only"), now.strftime("%Y-%m-%d"), now.weekday()))
    print("窗口          : %s" % ", ".join("%s-%s" % (w.get("start"), w.get("end")) for w in doc.get("windows") or []))
    print("当前时间      : %s（偏移 %+d 分钟）" % (now.strftime("%Y-%m-%d %H:%M:%S"), doc.get("utc_offset_minutes", 480)))
    print("现在是否忙时  : %s" % ("是" if active else "否"))
    print("Go 白名单     : %s" % (", ".join(allowed) or "(空：tap 会拒绝所有 Go 请求，改写也不会发生)"))
    if doc.get("target_model") and allowed and doc["target_model"] not in allowed:
        print("!! 目标模型不在白名单里 —— 忙时也不会改写（否则会把自己变成 403）")
    health = {}
    try:
        health = json.load(open("/var/lib/relay/public/usagetap.json", encoding="utf-8"))
    except Exception:
        pass
    bh = health.get("busy_hours") or {}
    print("tap 累计改写  : %s 次；跳过原因 %s" % (bh.get("rewrites", "?"), json.dumps(bh.get("skips") or {}, ensure_ascii=False)))
    if bh.get("last"):
        print("最近一次改写  : %s -> %s" % (bh["last"].get("from"), bh["last"].get("to")))
    print("请求处理在哪  : 只有流量经过 tap(127.0.0.1:8790) 时策略才生效；"
          "watchdog 判定 tap 挂了会把 nginx 直连上游，那时不改写")


def check(tap, at):
    doc = tap.busy_conf()
    allowed = tap.go_allowlist()
    models = sorted(allowed) or sorted(
        set(tap.BUSY_DEFAULTS["rewrite_prefixes"]) | {tap.BUSY_DEFAULTS["target_model"]})
    for model in models:
        to, why = tap.busy_rewrite(doc.get("route"), model, allowed, at)
        print("  %-26s %s" % (model, "-> %s (%s)" % (to, why) if to else "不动 (%s)" % why))


def windows(tap):
    doc = tap.busy_conf()
    now = local_now(tap)
    day = now.replace(hour=0, minute=0, second=0, microsecond=0)
    print("今天 %s（weekday=%d，%s）的边界：" % (
        now.strftime("%Y-%m-%d"), now.weekday(),
        "工作日" if now.weekday() <= 4 else "周末"))
    marks = []
    for w in doc.get("windows") or []:
        for key, label in (("start", "起"), ("end", "止")):
            mins = tap._hhmm(w.get(key))
            if mins is not None:
                marks.append((day + timedelta(minutes=mins), "%s%s" % (w.get(key), label)))
    for when, label in sorted(marks):
        rel = "已过" if when <= now else "还有 %.0f 分钟" % ((when - now).total_seconds() / 60)
        print("  %s  %-10s %s" % (when.strftime("%H:%M"), label, rel))


def main():
    if os.geteuid() != 0:
        print("需要 root（读写 /etc/relay 与 /var/lib/relay）", file=sys.stderr)
        return 1
    tap = load_tap()
    cmd = (sys.argv[1] if len(sys.argv) > 1 else "status")
    if cmd == "off":
        os.makedirs(os.path.dirname(tap.BUSY_OFF_FLAG), exist_ok=True)
        open(tap.BUSY_OFF_FLAG, "w").write("disabled by busy_hours.py at %s\n" % datetime.now().isoformat(timespec="seconds"))
        print("已临时停用忙时改写：%s 已创建" % tap.BUSY_OFF_FLAG)
        print("恢复用 busy_hours.py on；tap 不用重启")
        return 0
    if cmd == "on":
        try:
            os.remove(tap.BUSY_OFF_FLAG)
            print("已恢复忙时改写：%s 已删除" % tap.BUSY_OFF_FLAG)
        except FileNotFoundError:
            print("开关本来就是开的（%s 不存在）" % tap.BUSY_OFF_FLAG)
        return 0
    if cmd == "status":
        status(tap)
        return 0
    if cmd == "check":
        at = parse_at(sys.argv[2] if len(sys.argv) > 2 else "", tap)
        print("预览 %s：" % at.strftime("%Y-%m-%d %H:%M %a"))
        check(tap, at)
        return 0
    if cmd == "windows":
        windows(tap)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
