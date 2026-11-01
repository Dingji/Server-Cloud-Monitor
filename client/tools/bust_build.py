#!/usr/bin/env python
"""Point the panel's cache-bust token at a new value, in both places that hold it.

  python tools/bust_build.py <app.js> <index.html> <new-token>

The token lives twice: `CM.BUILD = '...'` in js/app.js and `?v=...` on every
script tag in index.html. They are rewritten by pattern, not by matching the old
value across both files -- the two can legitimately disagree (deploying an
app.js that carries an older token does exactly that), and a replace-keyed-on-old
then silently changes nothing in index.html, leaving browsers on a cached copy of
the file that was just replaced.
"""
import io
import re
import sys

BUILD_RE = re.compile(r"""(CM\.BUILD\s*=\s*['"])([^'"]+)(['"])""")
QUERY_RE = re.compile(r"(\?v=)([0-9]{8}-[0-9]{4})")


def read(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    app_js, index_html, new = sys.argv[1:4]

    app = read(app_js)
    m = BUILD_RE.search(app)
    if not m:
        print("FAIL: %s 里找不到 CM.BUILD token，拒绝猜测" % app_js)
        return 1
    old_app = m.group(2)
    if old_app == new:
        print("app.js 已是 %s" % new)
    write(app_js, BUILD_RE.sub(lambda g: g.group(1) + new + g.group(3), app, count=1))

    idx = read(index_html)
    # QUERY_RE has two groups, so findall yields tuples: take the token, not the "?v="
    old_idx = sorted({g[1] for g in QUERY_RE.findall(idx)})
    n = len(old_idx)
    write(index_html, QUERY_RE.sub(lambda g: g.group(1) + new, idx))

    print("app.js %s -> %s ; index.html %s -> %s (%d 种旧 token 被统一)"
          % (old_app, new, "|".join(old_idx) or "-", new, n))
    if old_app != new and not old_idx:
        print("注意：index.html 里没有 ?v= 参数，静态资源可能不受 token 控制")
    return 0


if __name__ == "__main__":
    sys.exit(main())
