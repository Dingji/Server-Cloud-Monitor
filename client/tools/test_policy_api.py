#!/usr/bin/env python
"""Offline tests for the collector's busy-hours policy API.

Imports the pulled collector.py and usagetap.py with every host path redirected to
a temp directory, so validation and state reads are exercised without a server.
"""
import importlib.util
import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def load(name, relpath):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, relpath))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


col = load("collector_under_test", os.path.join("upstream", "relay-20261029", "collector.py"))
tap = load("usagetap_for_col", os.path.join("upstream", "relay-20261029", "usagetap.py"))

TMP = tempfile.mkdtemp(prefix="policy-test-")
CONF = os.path.join(TMP, "busy-hours.json")
OFF = os.path.join(TMP, "busy-hours.off")
ALLOW = os.path.join(TMP, "go-allowlist.json")
TARGET = os.path.join(TMP, "upstream-target-go.conf")
HEALTH = os.path.join(TMP, "usagetap.json")
TAPFILE = os.path.join(TMP, "usagetap.py")

col.BUSY_CONF_PATH = CONF
col.BUSY_OFF_FLAG = OFF
col.GO_ALLOW_PATH = ALLOW
col.TAP_TARGET_GO = TARGET
col.TAP_HEALTH_PATH = HEALTH
col.BUSY_TAP_PATH = TAPFILE
col.DATA_DIR = os.path.join(TMP, "data")
os.makedirs(col.DATA_DIR, exist_ok=True)

# the collector imports usagetap by path; point it at a copy that reads our temp config
src = open(os.path.join(ROOT, "upstream", "relay-20261029", "usagetap.py"), encoding="utf-8").read()
open(TAPFILE, "w", encoding="utf-8").write(src)

# force the import once, then retarget the loaded tap module at the temp files too:
# busy_state() asks the tap what it would do, so the tap must read the same paths
_ = col.tap_policy()
_tapm = col._tap_mod["mod"]
_tapm.BUSY_CONF_PATH = CONF
_tapm.BUSY_OFF_FLAG = OFF
_tapm.GO_ALLOWLIST_PATH = ALLOW
_tapm._busy_conf["mtime"] = None

with open(ALLOW, "w", encoding="utf-8") as f:
    json.dump({"models": ["mimo-v2.6-flash", "deepseek-v4.1-flash", "longcat-2.5-preview-free"]}, f)
with open(TARGET, "w", encoding="utf-8") as f:
    f.write("proxy_pass http://127.0.0.1:8790;\n")
with open(HEALTH, "w", encoding="utf-8") as f:
    json.dump({"ok": True, "busy_hours": {"rewrites": 4, "skips": {}, "last": {"from": "x"}}}, f)

FAILS = []
RAN = 0


def check(label, got, want):
    global RAN
    RAN += 1
    if got != want:
        FAILS.append("%-56s got=%r want=%r" % (label, got, want))


def err_has(label, payload, needle):
    global RAN
    RAN += 1
    _, errors, _ = col.busy_validate(payload)
    if not any(needle in e for e in errors):
        FAILS.append("%-56s errors=%r (expected one containing %r)" % (label, errors, needle))


GOOD = {"enabled": True, "weekdays_only": True, "route": "go",
        "target_model": "mimo-v2.6-flash", "rewrite_prefixes": ["deepseek"],
        "windows": [{"start": "09:00", "end": "12:00"}, {"start": "14:00", "end": "18:00"}],
        "utc_offset_minutes": 480}

# ---- happy path ----
doc, errors, empty = col.busy_validate(GOOD)
check("valid input has no errors", errors, [])
check("valid input version normalised", doc["version"], 1)
check("valid input windows kept", len(doc["windows"]), 2)
check("valid input prefixes normalised", doc["rewrite_prefixes"], ["deepseek"])
check("windows present -> empty flag false", empty, False)

# ---- each rule rejects ----
err_has("enabled must be bool", dict(GOOD, enabled="yes"), "enabled")
err_has("weekdays must be bool", dict(GOOD, weekdays_only=1), "weekdays_only")
err_has("route must be known", dict(GOOD, route="nope"), "route")
err_has("route empty rejected", dict(GOOD, route=""), "route")
err_has("target off the allowlist rejected", dict(GOOD, target_model="gpt-6-luna"), "白名单")
err_has("target bad chars rejected", dict(GOOD, target_model="Bad Model"), "target_model")
err_has("no prefixes rejected", dict(GOOD, rewrite_prefixes=[]), "rewrite_prefixes")
err_has("duplicate prefix rejected", dict(GOOD, rewrite_prefixes=["deepseek", "DEEPSEEK"]), "重复")
err_has("malformed window rejected", dict(GOOD, windows=[{"start": "9am", "end": "12:00"}]), "HH:MM")
err_has("out-of-range hour rejected", dict(GOOD, windows=[{"start": "25:00", "end": "26:00"}]), "HH:MM")
err_has("out-of-range minute rejected", dict(GOOD, windows=[{"start": "09:70", "end": "12:00"}]), "HH:MM")
err_has("zero-length window rejected", dict(GOOD, windows=[{"start": "09:00", "end": "09:00"}]), "起止相同")
err_has("duplicate window rejected", dict(GOOD, windows=[{"start": "09:00", "end": "12:00"},
                                                          {"start": "09:00", "end": "12:00"}]), "重复")
err_has("too many windows rejected", dict(GOOD, windows=[{"start": "%02d:00" % h, "end": "%02d:30" % h}
                                                         for h in range(10)]), "最多")
err_has("offset too large rejected", dict(GOOD, utc_offset_minutes=9999), "utc_offset_minutes")
err_has("offset non-int rejected", dict(GOOD, utc_offset_minutes="abc"), "utc_offset_minutes")
err_has("windows not a list rejected", dict(GOOD, windows="09:00"), "windows")
err_has("window item not object rejected", dict(GOOD, windows=["09:00-12:00"]), "对象")

# ---- accepted edge cases ----
_, errors, _ = col.busy_validate(dict(GOOD, windows=[{"start": "22:00", "end": "02:00"}]))
check("midnight-crossing window accepted", errors, [])
doc, errors, empty = col.busy_validate(dict(GOOD, windows=[]))
check("empty windows accepted", errors, [])
check("empty windows flagged", empty, True)
_, errors, _ = col.busy_validate(dict(GOOD, target_model="deepseek-v4.1-flash"))
check("target equal to a prefix source accepted", errors, [])

# ---- writing ----
doc, _, _ = col.busy_validate(GOOD)
check("first write has no backup", col.busy_write(doc), None)
check("file written is valid json", isinstance(json.load(open(CONF, encoding="utf-8")), dict), True)
prev = col.busy_write(dict(doc, enabled=False))
check("second write makes a backup", bool(prev) and os.path.exists(prev), True)
for i in range(6):
    col.busy_write(dict(doc, enabled=(i % 2 == 0)))
baks = [p for p in os.listdir(col.DATA_DIR) if p.startswith("busy-hours.json.bak-")]
check("backups rotate at 5", len(baks) <= 5, True)

# ---- state read ----
st = col.busy_state()
check("state reads enabled back", st["config"]["enabled"], False)
check("state has 2 windows", len(st["config"]["windows"]), 2)
check("state switch off initially", st["switch_off"], False)
check("state sees tap in path", st["tap"]["in_request_path"], True)
check("state reads tap rewrites", st["tap"]["rewrites"], 4)
check("state exposes allowlist", len(st["go_allowlist"]), 3)
check("state module available", st["tap"]["module_available"], True)
check("probe model is a real allowlisted one", st["probe"]["model"], "deepseek-v4.1-flash")

# re-enable before exercising the switch: busy_rewrite reports config first,
# so an already-disabled policy would mask the switch reason
col.busy_write(doc)
col._tap_mod["mod"]._busy_conf["mtime"] = None
check("policy enabled again", col.busy_state()["config"]["enabled"], True)
col.busy_switch(True)
check("switch on recorded", col.busy_state()["switch_off"], True)
check("switch on blocks rewrite", col.busy_state()["probe"]["reason"], "switch_off")
col.busy_switch(False)
check("switch restored", os.path.exists(OFF), False)
check("enabled=true rewrites on a workday morning",
      col.busy_state("2026-10-29 10:30")["probe"]["reason"], "rewritten")
col.busy_write(dict(doc, enabled=False))
col._tap_mod["mod"]._busy_conf["mtime"] = None
check("enabled=false reason", col.busy_state()["probe"]["reason"], "disabled_in_config")
col.busy_write(doc)
col._tap_mod["mod"]._busy_conf["mtime"] = None

doc2, _, _ = col.busy_validate(GOOD)
col.busy_write(doc2)
always = col.busy_state("2026-11-01 10:30")   # Sunday
check("preview on a future Sunday", always["probe"]["reason"], "outside_window")
workday = col.busy_state("2026-10-29 10:30")  # Thursday
check("preview on a Thursday morning", workday["probe"]["would_rewrite_to"], "mimo-v2.6-flash")
gap = col.busy_state("2026-10-29 13:00")
check("preview in the lunch gap", gap["probe"]["reason"], "outside_window")
# a malformed preview time is an input error: _parse_at must reject it so the
# route can answer 400 rather than silently degrading
try:
    col._parse_at(_tapm, doc2, "not a time")
    check("bad preview time raises", False, True)
except ValueError:
    check("bad preview time raises ValueError", True, True)

# ---- degraded inputs must not raise ----
open(CONF, "w", encoding="utf-8").write("{ broken json")
st = col.busy_state()
check("broken config flagged", st["config_file_invalid"], True)
check("broken config still answers", st["ok"], True)
os.remove(CONF)
st = col.busy_state()
check("missing config flagged", st["config_file_missing"], True)
open(TARGET, "w", encoding="utf-8").write("proxy_pass https://opencode.ai;\n")
check("tap bypassed detected", col.busy_state()["tap"]["in_request_path"], False)
os.remove(TARGET)
check("missing target file -> not in path", col.busy_state()["tap"]["in_request_path"], False)
# a fresh process that cannot import the tap must degrade, not die
col.BUSY_TAP_PATH = os.path.join(TMP, "does-not-exist.py")
col._tap_mod = {"mod": None, "error": None, "tried": False}
st = col.busy_state()
check("unimportable tap reported, not fatal", st["tap"]["module_available"], False)
check("unimportable tap gives a reason", bool(st["tap"]["module_error"]), True)
check("state still answers without the tap", st["ok"], True)
check("config still readable without the tap", "windows" in st["config"], True)

print("\nran %d checks" % RAN)
if FAILS:
    print("FAILURES (%d):" % len(FAILS))
    for f in FAILS:
        print("  x " + f)
    sys.exit(1)
print("ALL PASS")
