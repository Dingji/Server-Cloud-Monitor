#!/usr/bin/env python
"""Compare the README blob on the remote branch with the local commit."""
import base64
import os
import json
import subprocess
import urllib.request

URL = ("https://api.github.com/repos/Dingji/Server-Cloud-Monitor/contents/README.md"
       "?ref=Developing_20261024")
REPO = r"D:\Project\ms\_sync\Server-Cloud-Monitor"

d = json.loads(urllib.request.urlopen(URL, timeout=40).read().decode("utf-8"))
text = base64.b64decode(d["content"]).decode("utf-8")

local_blob = subprocess.check_output(
    ["git", "-C", REPO, "rev-parse", "HEAD:README.md"], text=True).strip()

print("remote blob sha :", d["sha"])
print("local  blob sha :", local_blob)
print("IDENTICAL       :", d["sha"] == local_blob)
print()
print("remote size      =", d["size"], "bytes")
for probe, label in [("### Panel feature inventory", "inventory section"),
                     ("### Feature stacks", "feature stacks section"),
                     ("### RelayHub", "relayhub section"),
                     ("devmetrics.js", "device-metrics row"),
                     ("ops/php/", "php row")]:
    print("%-22s: %s" % (label, "YES" if probe in text else "NO"))

rows = [l for l in text.splitlines() if l.startswith("| `") and ".js` |" in l]
print("ops module rows   =", len(rows))
needle = os.environ.get("LEAKED", "")
print("secret literal    =", ("not checked (set LEAKED)" if not needle
                              else ("PRESENT" if needle in text else "absent")))
