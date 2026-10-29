#!/bin/bash
# Periodic disk hygiene for the relay host (40G disk).
set -u
echo "=== $(date '+%F %T') relay disk hygiene ==="
df -h / | sed 's/^/  /'

echo "-- apt cache --"
apt-get clean 2>/dev/null
rm -rf /var/lib/apt/lists/partial 2>/dev/null

echo "-- relay database --"
python3 - <<'PY'
import os, sqlite3
p = "/var/lib/relay/relay.db"
try:
    c = sqlite3.connect(p, timeout=25)
    c.execute("PRAGMA busy_timeout=20000")
    c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    free = c.execute("PRAGMA freelist_count").fetchone()[0]
    pages = c.execute("PRAGMA page_count").fetchone()[0]
    print(f"  pages={pages} freelist={free}")
    if pages and free * 4 > pages:
        c.execute("VACUUM")
        print("  vacuumed")
    c.commit(); c.close()
except Exception as e:
    print("  skipped:", e)
PY

echo "-- old reports / logs / backups --"
find /var/lib/relay/public/reports -name 'report-*.html' -mtime +30 -delete 2>/dev/null
find /var/log/nginx -name 'opencode-relay.*.gz' -mtime +7 -delete 2>/dev/null
find /var/log/nginx -name 'opencode-relay.*.log' -mtime +7 ! -name 'opencode-relay.access.log' \
     ! -name 'opencode-relay.error.log' -delete 2>/dev/null
# keep the 3 newest of each *.before-* generation, drop anything older than 21 days
find /opt/relay /etc/relay /etc/nginx -name '*.before-*' -mtime +21 -delete 2>/dev/null
echo "-- config history --"
# Patterns MUST be quoted: otherwise the shell expands them while building the
# `for` list, $pat becomes a single file and `ls | tail -n +4` never matches.
# Keep only the 3 newest of each class; real recovery points live in
# /var/backups/relay now.
for pat in '/opt/relay/opencode-relay.bak.*' \
           '/opt/relay/*.before-*' \
           '/etc/nginx/sites-available/opencode-relay.bak.*' \
           '/etc/nginx/sites-available/*.before-*' \
           '/etc/relay/*.before-*' \
           '/etc/relay/*.bak'; do
  ls -1t $pat 2>/dev/null | tail -n +4 | xargs -r rm -f
done

echo "-- journal --"
journalctl --vacuum-size=500M 2>/dev/null | tail -1

echo "-- final --"
df -h / | sed 's/^/  /'
du -sh /var/lib/relay /var/log/nginx 2>/dev/null | sed 's/^/  /'
