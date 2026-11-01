#!/usr/bin/env python
"""Place the pulled relay deployment into the Server-Cloud-Monitor repo under relay/.

Byte-exact copies only (shutil), no text rewriting: these are the files that run on the
server, and the point of the commit is that a new machine can deploy the same thing.
Secrets were already scanned out of the set -- notably /etc/relay/upstream-auth.conf
(real Bearer tokens, included by nginx three times) and the install_*/retention_tune
scripts that embed tokens in curl lines; none of those are pulled here.
"""
import hashlib
import os
import shutil

SRC = 'D:/Project/ms/ServerMaintance/upstream'
REPO = 'D:/Project/ms/_sync/Server-Cloud-Monitor'
NGINX_LIVE = SRC + '/opt/relay/opencode-relay'
NGINX_ALT = SRC + '/opt/relay/opencode-relay.sites-available'

SERVER = ['collector.py', 'usagetap.py', 'keystore.py', 'relay_guard.py', 'selfheal.py',
          'modelcatalog.py', 'modelcheck.py', 'daily_report.py', 'relay_alert.py',
          'db_guard.sh', 'disk_guard.sh', 'disk_hygiene.sh', 'backup_relay.sh', 'backup_verify.sh']
NGINX = ['opencode-relay', 'glm-upstream.conf', 'mimo-upstream.conf', 'opencode-upstream.conf']


def md5(p):
    return hashlib.md5(open(p, 'rb').read()).hexdigest()


def place(rel_src, rel_dst):
    s = SRC + '/' + rel_src
    d = REPO + '/relay/' + rel_dst
    os.makedirs(os.path.dirname(d), exist_ok=True)
    shutil.copyfile(s, d)
    ok = md5(s) == md5(d)
    print('  %-46s %7d bytes  identical=%s' % ('relay/' + rel_dst, os.path.getsize(d), ok))
    return ok


print('=== server/ (the 13 files the systemd units reference, plus collector\'s import) ===')
bad = 0
for f in SERVER:
    bad += 0 if place('opt/relay/' + f, 'server/' + f) else 1

print('=== nginx/ ===')
for f in NGINX:
    src = 'opt/relay/' + f
    if f == 'opencode-relay' and os.path.exists(NGINX_ALT):
        same = md5(NGINX_LIVE) == md5(NGINX_ALT)
        print('  /opt/relay/opencode-relay vs sites-available copy: %s' % ('same' if same else 'DIFFERENT'))
        src = 'opt/relay/opencode-relay.sites-available'
    bad += 0 if place(src, 'nginx/' + f) else 1

print('=== systemd/ ===')
units_dir = SRC + '/etc/systemd/system'
units = sorted(f for f in os.listdir(units_dir) if f.startswith('relay-'))
for f in units:
    bad += 0 if place('etc/systemd/system/' + f, 'systemd/' + f) else 1

print('=== config/ ===')
bad += 0 if place('etc/relay/busy-hours.json', 'config/busy-hours.json') else 1

print('\nfiles placed: %d, byte mismatches: %d' % (len(SERVER) + len(NGINX) + len(units) + 1, bad))
