#!/usr/bin/env python
"""Probe which notebox base URL the apps can actually reach, and whether the
seed admin credential still logs in.

Why the password is read out of db.py instead of being typed here: hand-copying
it once produced a wrong string and I wrote a false memory from the failed
login. The source is the oracle.

  python tools/notebox_login_probe.py
"""
import io
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from http.cookiejar import CookieJar
from urllib.request import HTTPCookieProcessor

REPO = Path(__file__).resolve().parent.parent
DB_PY = REPO / "notebox-server/notebox/db.py"

HOST = "8.153.153.176"
# candidate bases: direct notebox port, and the two panel-proxy spellings
BASES = [
    "http://%s:13345" % HOST,
    "http://%s/panel/notebox" % HOST,
    "http://%s/panel/api/notebox" % HOST,
]
EMAIL = "dingji_007@live.com"


def seed_password():
    src = io.open(DB_PY, encoding="utf-8").read()
    m = re.search(r'os\.environ\.get\("NB_PASS",\s*"([^"]+)"\)', src)
    if not m:
        raise SystemExit("FAIL: cannot read the NB_PASS seed out of db.py")
    return m.group(1)


def opener():
    return HTTPCookieProcessor(CookieJar())


def probe(base, email, pw):
    """Return (label, detail). Login path follows the Kotlin client: base + /api/login."""
    url = base.rstrip("/") + "/api/login"
    body = json.dumps({"email": email, "password": pw}).encode()
    req = urllib.request.Request(url, data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    cp = opener()
    op = urllib.request.build_opener(cp)
    try:
        with op.open(req, timeout=12) as r:
            txt = r.read().decode("utf-8", "replace")
            cookies = [c.name for c in cp.cookiejar]
            return "OK", "http=%d session_cookie=%s body=%s" % (
                r.status, ",".join(cookies) or "none", txt[:120])
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8", "replace")
        return "HTTP%d" % e.code, txt[:120]
    except Exception as e:  # noqa: BLE001 - a probe must report, not raise
        return "ERR", "%s: %s" % (e.__class__.__name__, str(e)[:120])


def main():
    pw = seed_password()
    print("seed password read from db.py: %d chars, ends %r" % (len(pw), pw[-3:]))
    print("account under test: %s\n" % EMAIL)
    for base in BASES:
        label, detail = probe(base, EMAIL, pw)
        print("%-40s %-7s %s" % (base, label, detail))
    return 0


if __name__ == "__main__":
    sys.exit(main())
