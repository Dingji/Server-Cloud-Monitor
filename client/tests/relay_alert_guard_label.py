#!/usr/bin/env python
"""Prove c_relay_guard() labels each degradation kind with the right check name.

The 2026-11-01 incident reported a stale models report as "relay guard: canary" on a
host where canaries are disabled by a drop-in -- so the row the panel showed pointed
somewhere other than the fault. alert.state is never pruned, so a name is also a
promise: whatever key a branch returns is the key that will live in the panel.

Run against the repo copy, and against the live copy after deploying:
  python tests/relay_alert_guard_label.py upstream/opt/relay/relay_alert.py
  ssh root@host python3 /tmp/relay_alert.new.py ...   # see tools/ deploy steps
"""
import importlib.util
import json
import os
import sys
import tempfile

CASES = [
    # (label, guard.state content, expected check name, expected ok)
    ("integrity-degraded, canaries skipped",
     {"ok": False, "problems": ["models_report:stale 47.6h"], "checks": {"canaries": {}}},
     "relay guard", False),
    ("canary actually failing",
     {"ok": False, "problems": ["canary:mimo failed"],
      "checks": {"canaries": {"mimo-v2.6-flash": {"ok": False}}}},
     "relay guard: canary", False),
    ("healthy with canaries run",
     {"ok": True, "problems": [],
      "checks": {"canaries": {"a": {"ok": True}, "b": {"ok": True}}}},
     "relay guard: canary", True),
    ("healthy, canaries skipped by drop-in",
     {"ok": True, "problems": [], "checks": {"canaries": {}}},
     "relay guard", True),
]


def load(path):
    spec = importlib.util.spec_from_file_location("relay_alert_under_test", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "upstream/opt/relay/relay_alert.py"
    m = load(src)
    bad = 0
    d = tempfile.mkdtemp(prefix="guardstate-")
    for label, payload, want_name, want_ok in CASES:
        p = os.path.join(d, "guard.state")
        with open(p, "w", encoding="utf-8") as f:
            json.dump(payload, f)
        name, ok, detail = m.c_relay_guard(p)
        good = (name == want_name and ok == want_ok)
        bad += 0 if good else 1
        print("%-4s %-38s -> %-22r ok=%-5s %s"
              % ("ok" if good else "FAIL", label, name, ok, (detail or "")[:48]))
    # the missing-file branch must not claim a canary fault either
    name, ok, _ = m.c_relay_guard(os.path.join(d, "nope.state"))
    good = (name == "relay guard" and ok is False)
    bad += 0 if good else 1
    print("%-4s %-38s -> %-22r ok=%-5s" % ("ok" if good else "FAIL", "guard never ran", name, ok))
    print("\n%s: %d/%d branches labelled correctly" % (src, len(CASES) + 1 - bad, len(CASES) + 1))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
