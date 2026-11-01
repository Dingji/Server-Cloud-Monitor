#!/bin/bash
# 备份恢复演练：定期验证备份确实可恢复
set -uo pipefail
DEST=/var/backups/relay
PASSF=/etc/relay/backup.pass
STATE=/var/lib/relay/verify.state
LOG=/var/log/relay-backup.log
WORK=$(mktemp -d /tmp/relay-drill.XXXXXX)
trap 'rm -rf "$WORK"' EXIT

say(){ echo "[$(date '+%F %T')] [drill] $*" >> "$LOG"; }
result(){ printf '{"ok":%s,"ts":"%s","backup":"%s","step":%s,"detail":"%s"}\n' \
  "$1" "$(date -Is)" "${2:-}" "${3:-0}" "${4:-}" > "$STATE"; }

LATEST=$(ls -1t "$DEST"/relay-backup-*.tar.gz.enc 2>/dev/null | head -1 || true)
if [ -z "$LATEST" ]; then say "no backup to verify"; result false "" 0 "no backup found"; exit 1; fi
BASE=$(basename "$LATEST" .tar.gz.enc)
say "start backup=$BASE"

# 1. 校验和
if ! ( cd "$DEST" && sha256sum -c "$BASE.sha256" >/dev/null 2>&1 ); then
  say "FAIL sha256 mismatch"; result false "$BASE" 1 "sha256 mismatch"; echo "DRILL FAIL: checksum"; exit 1
fi
say "step1 sha256 ok"

# 2. 解密
if ! openssl enc -d -aes-256-cbc -salt -pbkdf2 -iter 200000 -pass "file:$PASSF" \
     -in "$LATEST" -out "$WORK/b.tar.gz" 2>>"$LOG"; then
  say "FAIL decrypt"; result false "$BASE" 2 "decrypt failed (passphrase?)"; echo "DRILL FAIL: decrypt"; exit 1
fi
say "step2 decrypt ok"

# 3. 压缩包完整性
if ! tar -tzf "$WORK/b.tar.gz" >/dev/null 2>&1; then
  say "FAIL tar corrupt"; result false "$BASE" 3 "tar corrupt"; echo "DRILL FAIL: tar"; exit 1
fi
say "step3 tar ok"

# 4. 解出数据库并做完整性校验
tar -xzf "$WORK/b.tar.gz" -C "$WORK" ./relay.db 2>/dev/null || true
if [ ! -f "$WORK/relay.db" ]; then
  say "FAIL relay.db missing"; result false "$BASE" 4 "relay.db missing in backup"; echo "DRILL FAIL: no db"; exit 1
fi
DBRES=$(python3 - "$WORK/relay.db" <<'PY'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
ok = c.execute("PRAGMA integrity_check").fetchone()[0]
req = c.execute("SELECT COUNT(*) FROM requests").fetchone()[0]
use = c.execute("SELECT COUNT(*) FROM usage_events").fetchone()[0]
c.close()
print("%s %d %d" % (ok, req, use))
PY
)
OK=$(echo "$DBRES" | awk '{print $1}')
if [ "$OK" != "ok" ]; then
  say "FAIL integrity: $DBRES"; result false "$BASE" 5 "integrity=$OK"; echo "DRILL FAIL: integrity $OK"; exit 1
fi
say "step4 integrity ok ($DBRES)"

# 5. 与线上对比，报告恢复会回退多少数据
LIVE=$(python3 -c "
import sqlite3
c = sqlite3.connect('/var/lib/relay/relay.db')
print('%d %d' % (c.execute('SELECT COUNT(*) FROM requests').fetchone()[0],
                 c.execute('SELECT COUNT(*) FROM usage_events').fetchone()[0]))
" 2>/dev/null || echo "0 0")
BK_REQ=$(echo "$DBRES" | awk '{print $2}')
LIVE_REQ=$(echo "$LIVE" | awk '{print $1}')
LOST=$((LIVE_REQ - BK_REQ)); [ "$LOST" -lt 0 ] && LOST=0
say "step5 done restored_req=$BK_REQ live_req=$LIVE_REQ loss=$LOST"
result true "$BASE" 5 "integrity ok"

echo "DRILL OK: $BASE"
echo "  备份内 requests=$BK_REQ usage_events=$(echo "$DBRES" | awk '{print $3}')"
echo "  线上 requests=$LIVE_REQ  恢复将回退约 $LOST 条请求记录（约 $(python3 -c "import sys;h=$LOST/12753.0*24;print('%.1f' % (h if h>0 else 0))" 2>/dev/null || echo '?') 小时）"
