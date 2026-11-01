#!/usr/bin/env python
"""Sync this session's KMP policy-tab change into the Server-Cloud-Monitor repo.

Deliberately narrow: the page source is copied whole, but the locale JSONs get a
text-level append of exactly the keys this file uses and the repo lacks. The workspace
carries hundreds of uncommitted i18n lines from other work -- overwriting the repo's
copies would push changes this task never made.
"""
import json
import os
import re
import shutil

REPO = 'D:/Project/ms/_sync/Server-Cloud-Monitor'
WS = 'D:/Project/ms/ServerMaintance'
KT = 'composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/relay/RelayPolicyTab.kt'
# the handheld layout pass also touched these two shared relay helpers
FILES = [
    KT,
    'composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/relay/RelayCommon.kt',
    'composeApp/src/commonMain/kotlin/com/servermaintance/panel/pages/relay/RelayHitrate.kt',
]

src = open(WS + '/' + KT, encoding='utf-8').read()
used = set(re.findall(r'L\[\"([^\"]+)\"\]', src)) | set(re.findall(r'trf\(\"([^\"]+)\"', src))
print('keys this page needs: %d' % len(used))

print('\n=== 1. page sources ===')
for rel in FILES:
    dst = REPO + '/client/' + rel
    shutil.copyfile(WS + '/' + rel, dst)
    same = open(dst, 'rb').read() == open(WS + '/' + rel, 'rb').read()
    print('   %-22s %6d bytes  byte-identical: %s' % (
        os.path.basename(rel), os.path.getsize(dst), same))
    if not same:
        raise SystemExit('copy of %s did not land byte-identical' % rel)

print('\n=== 2. locale keys (append-only) ===')
total = 0
for root in ('commonMain', 'desktopMain'):
    ddir = '%s/client/composeApp/src/%s/resources/i18n' % (REPO, root)
    langs = sorted(p[:-5] for p in os.listdir(ddir) if p.endswith('.json') and p != 'template.json')
    added = {}
    for lg in langs:
        wpath = '%s/composeApp/src/%s/resources/i18n/%s.json' % (WS, root, lg)
        rpath = '%s/%s.json' % (ddir, lg)
        want = json.load(open(wpath, encoding='utf-8'), object_pairs_hook=lambda p: p)  # keep order
        have = json.load(open(rpath, encoding='utf-8'))
        miss = [(k, v) for k, v in want if k in used and k not in have]
        if not miss:
            continue
        txt = open(rpath, encoding='utf-8').read()
        i = txt.rstrip().rfind('}')
        head = txt[:i].rstrip()
        lines = ',\n'.join('  %s: %s' % (json.dumps(k, ensure_ascii=False),
                                         json.dumps(v, ensure_ascii=False)) for k, v in miss)
        out = head + ',\n' + lines + '\n}\n'
        json.loads(out)                      # never write a file that will not parse
        with open(rpath, 'w', encoding='utf-8', newline='\n') as f:
            f.write(out)
        added[lg] = len(miss)
        total += len(miss)
    print('   %s: %d locales touched, %d entries added%s' % (
        root, len(added), sum(added.values()),
        '' if not added else ' (e.g. %s)' % list(added.items())[:2]))

print('\n=== 3. re-check the repo copy resolves every key ===')
bad = 0
for root in ('commonMain', 'desktopMain'):
    ddir = '%s/client/composeApp/src/%s/resources/i18n' % (REPO, root)
    for lg in sorted(p[:-5] for p in os.listdir(ddir) if p.endswith('.json') and p != 'template.json'):
        d = json.load(open('%s/%s.json' % (ddir, lg), encoding='utf-8'))
        miss = [k for k in sorted(used) if k not in d]
        if miss:
            bad += 1
            print('   MISSING %s/%s: %s' % (root, lg, miss[:4]))
print('   unresolved key sites: %d' % bad)
print('   total entries added: %d' % total)
