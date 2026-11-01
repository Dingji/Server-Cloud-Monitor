#!/usr/bin/env python
"""Offline tests for the relay tap's busy-hour routing policy.

Runs against the pulled copy of usagetap.py; no server contact. The pure window
function is tested against fixed datetimes, and the full decision path is tested
against windows built around the real current instant.
"""
import importlib.util
import json
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
TAP = os.path.join(HERE, "..", "upstream", "relay-20261029", "usagetap.py")

spec = importlib.util.spec_from_file_location("usagetap", os.path.abspath(TAP))
tap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tap)

TZ8 = timezone(timedelta(hours=8))
FAILS = []
RAN = 0


def check(label, got, want):
    global RAN
    RAN += 1
    if got != want:
        FAILS.append("%-58s got=%r want=%r" % (label, got, want))


def win(a, b):
    return [{"start": a, "end": b}]


def dt(y, mo, d, h, mi, wd_note=""):
    return datetime(y, mo, d, h, mi, tzinfo=TZ8)


# ---- window boundaries (2026-10-29 is a Thursday) ----
D = win("09:00", "12:00") + win("14:00", "18:00")
base = dict(tap.BUSY_DEFAULTS)
base["windows"] = D

cases = [
    ("08:59 before morning window", dt(2026, 10, 29, 8, 59), False),
    ("09:00 morning start inclusive", dt(2026, 10, 29, 9, 0), True),
    ("11:59 morning end-1", dt(2026, 10, 29, 11, 59), True),
    ("12:00 morning end exclusive", dt(2026, 10, 29, 12, 0), False),
    ("13:59 lunch gap", dt(2026, 10, 29, 13, 59), False),
    ("14:00 afternoon start", dt(2026, 10, 29, 14, 0), True),
    ("17:59 afternoon end-1", dt(2026, 10, 29, 17, 59), True),
    ("18:00 afternoon end exclusive", dt(2026, 10, 29, 18, 0), False),
    ("19:30 evening", dt(2026, 10, 29, 19, 30), False),
    ("02:00 night", dt(2026, 10, 29, 2, 0), False),
    ("Fri 10:00 weekday", dt(2026, 10, 30, 10, 0), True),
    ("Sat 10:00 weekdays_only", dt(2026, 10, 31, 10, 0), False),
    ("Sun 15:00 weekdays_only", dt(2026, 11, 1, 15, 0), False),
]
for label, when, want in cases:
    check(label, tap.busy_window_active(base, when), want)

# weekends off by configuration
alldays = dict(base)
alldays["weekdays_only"] = False
check("Sat 10:00 with weekdays_only=false", tap.busy_window_active(alldays, dt(2026, 10, 31, 10, 0)), True)

# midnight-crossing window expressed as one entry
night = dict(base)
night["windows"] = win("22:00", "02:00")
check("23:30 inside crossing window", tap.busy_window_active(night, dt(2026, 10, 29, 23, 30)), True)
check("01:00 inside crossing window", tap.busy_window_active(night, dt(2026, 10, 30, 1, 0)), True)
check("03:00 outside crossing window", tap.busy_window_active(night, dt(2026, 10, 30, 3, 0)), False)

# malformed config must not raise and must not rewrite
for bad in [{"windows": []}, {"windows": [{"start": "9am", "end": "12"}]},
            {"windows": [{"start": "25:00", "end": "26:00"}]}, {"windows": None}]:
    d = dict(tap.BUSY_DEFAULTS)
    d.update(bad)
    check("malformed %r stays inactive" % (bad,), tap.busy_window_active(d, dt(2026, 10, 29, 10, 0)), False)

# offset applied: 02:00 UTC == 10:00 Beijing
check("respects utc_offset_minutes", tap.busy_window_active(base, datetime(2026, 10, 29, 2, 0, tzinfo=timezone.utc)), True)

# ---- full decision path: busy_rewrite ----
tmp = tempfile.mkdtemp(prefix="busy-test-")
CONF = os.path.join(tmp, "busy-hours.json")
OFF = os.path.join(tmp, "busy-hours.off")
tap.BUSY_CONF_PATH = CONF
tap.BUSY_OFF_FLAG = OFF
ALLOWED = {"mimo-v2.6-flash", "deepseek-v4.1-flash", "longcat-2.5-preview-free"}


def load(doc):
    with open(CONF, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False)
    tap._busy_conf["mtime"] = None   # force reload


def now_in_window_doc(active):
    """Build a one-hour window that does/does not contain the current instant."""
    local = datetime.now(TZ8)
    if active:
        start = local.replace(minute=0, second=0, microsecond=0)
        end = start + timedelta(hours=1)
    else:
        start = (local + timedelta(hours=2)).replace(minute=0, second=0, microsecond=0)
        end = start + timedelta(hours=1)
    doc = dict(tap.BUSY_DEFAULTS)
    doc["windows"] = [{"start": start.strftime("%H:%M"), "end": end.strftime("%H:%M")}]
    doc["weekdays_only"] = False       # keep the test deterministic on any day
    return doc, local.weekday() <= 4 and not active  # may be inactive for weekday reasons


load(now_in_window_doc(True)[0])
check("deepseek during busy -> mimo",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED)[0], "mimo-v2.6-flash")
check("any deepseek* prefix matches",
      tap.busy_rewrite("go", "deepseek-v4-pro", ALLOWED)[0], "mimo-v2.6-flash")
check("longcat untouched",
      tap.busy_rewrite("go", "longcat-2.5-preview-free", ALLOWED), (None, "not_a_match"))
check("mimo untouched (not a rewrite source)",
      tap.busy_rewrite("go", "mimo-v2.6-flash", ALLOWED), (None, "not_a_match"))
check("glm route untouched",
      tap.busy_rewrite("glm", "deepseek-v4.1-flash", ALLOWED), (None, "other_route"))
check("zen route untouched",
      tap.busy_rewrite("zen", "deepseek-v4.1-flash", ALLOWED), (None, "other_route"))
check("empty allowlist blocks rewrite",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", set()), (None, "target_not_allowed"))

# target not offerable -> keep the original rather than manufacture a 403
load(now_in_window_doc(True)[0])
check("target missing from allowlist -> no rewrite",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", {"deepseek-v4.1-flash"}),
      (None, "target_not_allowed"))

# out of window
doc_o, _ = now_in_window_doc(False)
load(doc_o)
check("outside window -> no rewrite",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED), (None, "outside_window"))

# switches
load(now_in_window_doc(True)[0])
open(OFF, "w").close()
check("temp switch off", tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED), (None, "switch_off"))
os.remove(OFF)
check("switch removed -> active again",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED)[0], "mimo-v2.6-flash")

d_off = dict(tap.BUSY_DEFAULTS)
d_off.update(now_in_window_doc(True)[0])
d_off["enabled"] = False
load(d_off)
check("enabled=false -> no rewrite",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED), (None, "disabled_in_config"))

# a bad edit must not disable a policy that is already loaded, and a missing
# file must fall back to the defaults rather than raising
load(now_in_window_doc(True)[0])
before = tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED)
with open(CONF, "w", encoding="utf-8") as f:
    f.write("{ this is not json")
check("broken config retains last good policy doc",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED), before)
os.remove(CONF)
missing = tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED)
check("missing config falls back to defaults (no raise)",
      missing[1] in ("rewritten", "outside_window"), True)

# the ops CLI previews a time without waiting for it: nowdt must drive the decision
load({"version": 1, "enabled": True, "route": "go", "target_model": "mimo-v2.6-flash",
      "rewrite_prefixes": ["deepseek"], "windows": [{"start": "09:00", "end": "12:00"}],
      "weekdays_only": True, "utc_offset_minutes": 480})
check("nowdt inside configured window",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED, dt(2026, 10, 29, 10, 0))[0],
      "mimo-v2.6-flash")
check("nowdt between windows",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED, dt(2026, 10, 29, 13, 0))[1],
      "outside_window")
check("nowdt Saturday with weekdays_only",
      tap.busy_rewrite("go", "deepseek-v4.1-flash", ALLOWED, dt(2026, 10, 31, 10, 0))[1],
      "outside_window")

# ---- body rewriting ----
load(now_in_window_doc(True)[0])
original = json.dumps({"model": "deepseek-v4.1-flash", "stream": True,
                       "max_tokens": 4096, "messages": [{"role": "user", "content": "你好 — keep me"}],
                       "temperature": 0.25}, ensure_ascii=False).encode("utf-8")
nb = tap.busy_rewritten_body(original, "mimo-v2.6-flash")
rt = json.loads(nb.decode("utf-8"))
check("body model replaced", rt["model"], "mimo-v2.6-flash")
check("body stream preserved", rt["stream"], True)
check("body max_tokens preserved", rt["max_tokens"], 4096)
check("body temperature preserved", rt["temperature"], 0.25)
check("body messages preserved", rt["messages"][0]["content"], "你好 — keep me")
check("body length matches bytes sent upstream", len(nb), len(nb.decode("utf-8").encode("utf-8")))
check("non-dict body rejected", tap.busy_rewritten_body(b"[1,2,3]", "mimo-v2.6-flash"), None)
check("garbage body rejected", tap.busy_rewritten_body(b"not json", "mimo-v2.6-flash"), None)
check("body without model rejected", tap.busy_rewritten_body(b'{"messages":[]}', "mimo-v2.6-flash"), None)

# health writer must survive the new keys
tap.HEALTH_PATH = os.path.join(tmp, "usagetap.json")
print("\nran %d checks" % RAN)
if FAILS:
    print("FAILURES (%d):" % len(FAILS))
    for f in FAILS:
        print("  x " + f)
    sys.exit(1)
print("ALL PASS")
