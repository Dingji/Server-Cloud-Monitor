#!/usr/bin/env python
"""Find Rows that emit buttons/chips in a loop without horizontalScroll or FlowRow.

That is the handheld overflow pattern: on a 360dp screen the trailing items are neither
visible nor reachable (the relay tab strip did exactly this to 忙时策略).

The block extractor must wait for the body's opening brace before it starts counting --
counting from the `Row(` line breaks out after two lines and reports nothing at all,
which is how this scan first passed a known-broken file.

  python tools/row_overflow_scan.py <dir> [<dir> ...]
"""
import os
import re
import sys


def row_blocks(lines):
    """Yield (start_line_no, block_text) for each `Row(` composable call."""
    for i, l in enumerate(lines):
        if not re.search(r'(?<![\w.])Row\(', l):
            continue
        started = False
        depth = 0
        body = []
        for k in range(i, min(i + 60, len(lines))):
            line = lines[k]
            body.append(line)
            if not started:
                # the argument list usually closes on a `) {` line; a one-liner Row
                # opens and closes on the same line
                if re.search(r'\)\s*\{\s*$', line) or (line.rstrip().endswith('{') and k > i):
                    started = True
                    depth = line.count('{') - line.count('}')
                elif line.count('{') and line.count('{') == line.count('}'):
                    started = True
                    depth = 0
            else:
                depth += line.count('{') - line.count('}')
            if started and depth <= 0:
                break
        if started:
            yield i + 1, '\n'.join(body)


def scan(path):
    out = []
    lines = open(path, encoding='utf-8').read().split('\n')
    for no, blk in row_blocks(lines):
        if 'horizontalScroll' in blk or 'FlowRow' in blk:
            continue
        if 'forEach' not in blk and 'map {' not in blk and 'mapIndexed' not in blk:
            continue
        n = len(re.findall(r'(PrimaryBtn|GhostBtn|Chip)\(', blk))
        if n:
            out.append((no, n, lines[no - 1].strip()[:60]))
    return out


def main():
    roots = sys.argv[1:] or ['.']
    total = 0
    for root in roots:
        for dp, _, fs in os.walk(root):
            for fn in sorted(fs):
                if not fn.endswith('.kt'):
                    continue
                p = os.path.join(dp, fn)
                hits = scan(p)
                for no, n, s in hits:
                    total += 1
                    print("  %s:%d  循环发出 %d 个按钮/徽章且不可横滚  %s" % (p, no, n, s))
    print("共 %d 处" % total)
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main())
