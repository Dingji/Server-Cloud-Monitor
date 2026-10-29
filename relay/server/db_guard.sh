#!/bin/bash
# relay.db 完整性守护：优先自愈，其次回退到最近的加密备份
set -uo pipefail
DB=/var/lib/relay/relay.db
PASSF=/etc/relay/backup.pass
DEST=/var/backups/relay
LOG=/var/log/relay-backup.log
STATE=/var/lib/relay/dbguard.state
STAMP=$(date +%Y%m%d%H%M%S)

say(){ echo "[$(date '+%F %T')] [dbguard] $*" >> "$LOG"; }
state(){ printf '{"ok":%s,"ts":"%s","action":"%s","detail":"%s"}\n' "$1" "$(date -Is)" "$2" "$3" > "$STATE"; }

RES=$(python3 -c "
import sqlite3
c = sqlite3.connect('file:$DB?mode=ro', uri=True, timeout=15)
print(c.execute('PRAGMA integrity_check').fetchone()[0])
" 2>&1) || RES="open-failed:$RES"

if [ "$RES" = "ok" ]; then
  state true none "integrity ok"
  echo "relay.db OK"
  exit 0
fi

say "INTEGRITY FAILED: $RES -> starting recovery"

# 1. 停止写入方，避免恢复期间继续写坏
systemctl stop relay-usagetap relay-stats 2>/dev/null || true
say "stopped relay-stats / relay-usagetap"

# 2. 保留损坏文件供事后分析
if [ -f "$DB" ]; then
  mv "$DB" "$DB.corrupt-$STAMP"
  say "preserved corrupt db -> $DB.corrupt-$STAMP"
fi
rm -f "$DB-shm" "$DB-wal" 2>/dev/null || true

# 3. 尝试 sqlite3 .recover（能保住尽可能多的数据）
if command -v sqlite3 >/dev/null 2>&1; then
  say "trying sqlite3 .recover"
  if sqlite3 "$DB.corrupt-$STAMP" .recover 2>>"$LOG" | sqlite3 "$DB.recovered" 2>>"$LOG"; then
    if python3 -c "
import sqlite3, sys
c = sqlite3.connect('$DB.recovered')
ok = c.execute('PRAGMA integrity_check').fetchone()[0]
n = c.execute('SELECT COUNT(*) FROM requests').fetchone()[0]
sys.exit(0 if ok == 'ok' and n > 0 else 1)
" 2>/dev/null; then
      mv "$DB.recovered" "$DB"
      chown --reference="$DB.corrupt-$STAMP" "$DB" 2>/dev/null || true
      systemctl start relay-stats relay-usagetap 2>/dev/null || true
      say "RECOVERED via sqlite3 .recover"
      state true recovered "sqlite3 .recover"
      echo "RECOVERED via .recover"
      exit 0
    fi
  fi
  say ".recover did not yield a usable db"
  rm -f "$DB.recovered" 2>/dev/null || true
fi

# 4. 从最近的加密备份恢复
LATEST=$(ls -1t "$DEST"/relay-backup-*.tar.gz.enc 2>/dev/null | head -1 || true)
if [ -n "$LATEST" ] && [ -f "$PASSF" ]; then
  BASE=$(basename "$LATEST" .tar.gz.enc)
  say "falling back to backup $BASE"
  WORK=$(mktemp -d /tmp/relay-restore.XXXXXX)
  if openssl enc -d -aes-256-cbc -salt -pbkdf2 -iter 200000 -pass "file:$PASSF" \
       -in "$LATEST" -out "$WORK/b.tar.gz" 2>>"$LOG" \
     && tar -xzf "$WORK/b.tar.gz" -C "$WORK" ./relay.db 2>>"$LOG"; then
    if python3 -c "
import sqlite3, sys
c = sqlite3.connect('$WORK/relay.db')
sys.exit(0 if c.execute('PRAGMA integrity_check').fetchone()[0] == 'ok' else 1)
" 2>/dev/null; then
      install -m 600 "$WORK/relay.db" "$DB"
      systemctl start relay-stats relay-usagetap 2>/dev/null || true
      rm -rf "$WORK"
      say "RESTORED from backup $BASE"
      state true restored "from $BASE"
      echo "RESTORED from backup $BASE"
      exit 0
    fi
  fi
  rm -rf "$WORK" 2>/dev/null || true
  say "backup restore failed"
fi

# 5. 全部失败：保持停止状态并明确告警，不留下半坏的服务
say "RECOVERY FAILED - relay-stats/usagetap left stopped"
state false failed "integrity=$RES"
echo "RECOVERY FAILED: $RES"
exit 1
