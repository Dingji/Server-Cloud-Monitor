#!/usr/bin/env python
"""Parse build-log.txt (PowerShell wraps long lines) into clean error records."""
import re, json, collections

raw = open('build-log.txt', encoding='utf-8', errors='replace').read()

drop_prefixes = ('所在位置', '+ CategoryInfo', '    + CategoryInfo',
                 '    + FullyQualifiedErrorId', '+ FullyQualifiedErrorId',
                 '所在位置 行:', '+ & powershell', '    + PSChildName')
lines = []
for ln in raw.splitlines():
    if ln.startswith(drop_prefixes):
        continue
    if re.match(r'^\s*\+\s+(CategoryInfo|FullyQualifiedErrorId|PSChildName)', ln):
        continue
    if ln.startswith(('powershell.exe : ', 'gradle.bat : ')):
        ln = ln.split(' : ', 1)[1]
    lines.append(ln)

# Rejoin PowerShell's ~160-col wrapping: keep text flat, then split on the marker.
flat = ''.join(lines)
parts = flat.split('e: file:///')

recs = []
for chunk in parts[1:]:
    m = re.match(r'([^\s]*?/([^/\s]+\.kt)):(\d+):\d+\s*(.*)', chunk, re.S)
    if not m:
        continue
    msg = re.sub(r'\s+', ' ', m.group(4)).strip()
    recs.append({'file': m.group(2), 'line': int(m.group(3)), 'msg': msg})

print(f'PARSED {len(recs)} errors')
by_file = collections.Counter(r['file'] for r in recs)
print('\n=== BY FILE ===')
for f, c in by_file.most_common():
    print(f'{c:4d}  {f}')

print('\n=== BY MESSAGE KIND ===')
def kind(m):
    m = re.sub(r"'[^']*'", "'X'", m)
    m = re.sub(r'\b\d+\b', 'N', m)
    return m[:80]
for k, c in collections.Counter(kind(r['msg']) for r in recs).most_common(18):
    print(f'{c:4d}  {k}')

print('\n=== ERRORS IN FILES OWNED BY LEAD (core + my pages) ===')
mine = {'Api.kt', 'Fmt.kt', 'Hub.kt', 'Json.kt', 'AppState.kt', 'Config.kt',
        'Platform.kt', 'Platform.android.kt', 'Platform.desktop.kt', 'Actions.kt',
        'TerminalPage.kt', 'BackupsPage.kt', 'LogsPage.kt', 'AuditPage.kt',
        'SettingsPage.kt', 'TerminalView.kt', 'AnsiScreen.kt'}
for r in sorted(recs, key=lambda x: (x['file'], x['line'])):
    if r['file'] in mine:
        print(f"{r['file']}:{r['line']}  {r['msg'][:110]}")

json.dump(recs, open('errors.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
