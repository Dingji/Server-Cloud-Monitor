#!/bin/bash
# 磁盘水位守卫：超过阈值自动清理，避免写满导致服务停摆
set -uo pipefail
LOG=/var/log/relay-backup.log
say(){ echo "[$(date '+%F %T')] [diskguard] $*" >> "$LOG"; }
pct(){ df -P / | awk 'NR==2{gsub("%","");print $5}'; }

NOW=$(pct)
if [ "$NOW" -lt 90 ]; then
  printf '{"ok":true,"ts":"%s","pct":%s,"action":"none"}\n' "$(date -Is)" "$NOW" > /var/lib/relay/diskguard.state
  echo "disk ${NOW}%"
  exit 0
fi

say "disk ${NOW}% >= 90% -> cleanup"
echo "disk ${NOW}% -> cleaning"

journalctl --vacuum-size=100M >/dev/null 2>&1 || true
apt-get clean >/dev/null 2>&1 || true
find /var/log -name '*.gz' -mtime +14 -delete 2>/dev/null || true
find /tmp -maxdepth 1 \( -name 'relay-*' -o -name 'cm-e2e*' \) -mmin +120 -delete 2>/dev/null || true
find /root -maxdepth 1 -type f \( -name 'stage_*.sh' -o -name 'probe_*.sh' \) -delete 2>/dev/null || true
rm -f /opt/cloud-monitor/logs/pkg-*.log 2>/dev/null || true
find /opt/cloud-monitor/logs -name 'audit.log.*' -mtime +30 -delete 2>/dev/null || true

python3 - <<'PY'
import sqlite3, sys
L = "/var/log/relay-backup.log"
try:
    c = sqlite3.connect("/var/lib/relay/relay.db", timeout=25)
    c.execute("PRAGMA busy_timeout=20000")
    c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    free = c.execute("PRAGMA freelist_count").fetchone()[0]
    pages = c.execute("PRAGMA page_count").fetchone()[0]
    if pages and free * 4 > pages:
        c.execute("VACUUM")
        open(L, "a").write("    vacuumed relay.db (freelist %d/%d)\n" % (free, pages))
    c.commit(); c.close()
except Exception as e:
    open(L, "a").write("    db cleanup skipped: %s\n" % e)
PY

AFTER=$(pct)
say "cleanup done: ${NOW}% -> ${AFTER}%"
if [ "$AFTER" -ge 95 ]; then
  say "WARNING: disk still ${AFTER}% after cleanup"
  printf '{"ok":false,"ts":"%s","pct":%s,"action":"cleanup","detail":"still >=95%% after cleanup"}\n' \
    "$(date -Is)" "$AFTER" > /var/lib/relay/diskguard.state
  echo "WARN disk ${AFTER}% (仍 >=95%)"
  exit 1
fi
printf '{"ok":true,"ts":"%s","pct":%s,"action":"cleanup","from":%s}\n' \
  "$(date -Is)" "$AFTER" "$NOW" > /var/lib/relay/diskguard.state
echo "disk ${NOW}% -> ${AFTER}%"
