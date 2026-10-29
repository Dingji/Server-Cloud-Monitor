#!/bin/bash
# Relay 备份：加密 + 校验清单
set -euo pipefail

DEST=/var/backups/relay
STAMP=$(date +%Y%m%d-%H%M%S)
KEEP=30
LOG=/var/log/relay-backup.log
STATE=/var/lib/relay/backup.state
TMP=$(mktemp -d /tmp/relay-bk.XXXXXX)
trap 'rm -rf "$TMP"' EXIT

# 备份密码：首次生成，之后固定使用
ENVF=/etc/relay/backup.env
PASSF=/etc/relay/backup.pass
if [ ! -f "$PASSF" ]; then
  umask 077
  head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32 > "$PASSF"
  chmod 600 "$PASSF"
  grep -q '^BACKUP_PASSPHRASE_FILE=' "$ENVF" 2>/dev/null || \
    printf '\nBACKUP_PASSPHRASE_FILE=/etc/relay/backup.pass\n' >> "$ENVF"
  echo "[$(date '+%F %T')] generated new backup passphrase" >> "$LOG"
fi

say(){ echo "[$(date '+%F %T')] $*" >> "$LOG"; }
fail(){ say "FAILED: $*"; echo "{\"ok\":false,\"error\":\"$*\",\"ts\":\"$(date -Is)\"}" > "$STATE"; exit 1; }

mkdir -p "$DEST"
say "=== backup start ==="

# 1. SQLite 在线热备份 + 完整性校验
export BK_TMP="$TMP"
python3 - <<'PY' || fail "sqlite hot backup"
import os, sqlite3, sys
src = "/var/lib/relay/relay.db"
dst = os.path.join(os.environ["BK_TMP"], "relay.db")
s = sqlite3.connect(src, timeout=30)
s.execute("PRAGMA busy_timeout=30000")
d = sqlite3.connect(dst)
s.backup(d)
d.close(); s.close()
c = sqlite3.connect(dst)
res = c.execute("PRAGMA integrity_check").fetchone()[0]
n = c.execute("SELECT COUNT(*) FROM requests").fetchone()[0]
c.close()
print("integrity=%s requests=%d" % (res, n))
sys.exit(0 if res == "ok" else 1)
PY

# 2. 密钥与配置
install -m 600 /var/lib/relay/metrics.db "$TMP/metrics.db" 2>/dev/null || true
install -m 600 /var/lib/relay/keys.json "$TMP/keys.json" 2>/dev/null || say "WARN: keys.json missing"
mkdir -p "$TMP/etc-relay" "$TMP/nginx" "$TMP/fail2ban" "$TMP/ssh" "$TMP/relay-src" "$TMP/pricing"
cp -a /etc/relay/. "$TMP/etc-relay/" 2>/dev/null || true
cp -a /etc/nginx/sites-available/opencode-relay "$TMP/nginx/" 2>/dev/null || true
cp -a /etc/nginx/conf.d/00-limits.conf "$TMP/nginx/" 2>/dev/null || true
cp -a /etc/nginx/nginx.conf "$TMP/nginx/" 2>/dev/null || true
cp -a /etc/fail2ban/jail.local "$TMP/fail2ban/" 2>/dev/null || true
cp -a /etc/fail2ban/filter.d/relay-panel-brute.conf "$TMP/fail2ban/" 2>/dev/null || true
cp -a /etc/ssh/sshd_config.d/ "$TMP/ssh/" 2>/dev/null || true
cp -a /etc/relay/pricing.json "$TMP/pricing/" 2>/dev/null || true
cp -a /var/lib/relay/public/. "$TMP/" 2>/dev/null || true
find /opt/relay -maxdepth 1 -type f \( -name '*.py' -o -name '*.sh' -o -name 'opencode-relay' \) \
     ! -name '*.before-*' ! -name '*.bak.*' -exec cp -a {} "$TMP/relay-src/" \; 2>/dev/null || true

# 3. 清单（每个文件的 SHA256）
( cd "$TMP" && find . -type f ! -name MANIFEST.sha256 -print0 | sort -z | xargs -0 sha256sum ) > "$TMP/MANIFEST.sha256" \
  || say "WARN: manifest generation failed"

# 4. 打包 → 加密 → 摘要
PLAIN="$DEST/relay-backup-$STAMP.tar.gz"
tar -czf "$PLAIN" -C "$TMP" . || fail "tar create"
tar -tzf "$PLAIN" >/dev/null 2>&1 || fail "tar verify"
openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 \
  -pass "file:$PASSF" -in "$PLAIN" -out "$PLAIN.enc" || fail "encrypt"
rm -f "$PLAIN"
( cd "$DEST" && sha256sum "relay-backup-$STAMP.tar.gz.enc" > "relay-backup-$STAMP.sha256" ) || fail "checksum"
SIZE=$(stat -c%s "$PLAIN.enc")
FILES=$(tar -tzf "$PLAIN.enc" 2>/dev/null | wc -l || echo 0)

# 5. 保留策略
shopt -s nullglob
enc=( "$DEST"/relay-backup-*.tar.gz.enc )
n=${#enc[@]}
if [ "$n" -gt "$KEEP" ]; then
  for ((i=0; i<n-KEEP; i++)); do
    base=$(basename "${enc[i]}" .tar.gz.enc)
    rm -f "${enc[i]}" "$DEST/$base.sha256"
    say "pruned $base"
  done
fi
shopt -u nullglob
TOTAL=$(du -sh "$DEST" | cut -f1)

# 6. 可选远端推送
REMOTE_STATUS="skipped"
if [ -f "$ENVF" ]; then
  . "$ENVF"
  if [ -n "${REMOTE_RCLONE:-}" ] && command -v rclone >/dev/null 2>&1; then
    if rclone copy "$PLAIN.enc" "$REMOTE_RCLONE" --retries 3 --low-level-retries 3 >> "$LOG" 2>&1 \
       && rclone copy "$DEST/relay-backup-$STAMP.sha256" "$REMOTE_RCLONE" >> "$LOG" 2>&1; then
      REMOTE_STATUS="pushed"
    else
      REMOTE_STATUS="remote-failed"
    fi
  fi
fi

# 7. 配额快照（SLO 页据此计算消耗速率与耗尽预测）
python3 - <<'PY'
import json, os, time
from datetime import datetime, timezone, timedelta
src = "/var/lib/relay/public/quota.json"
hist = "/var/lib/relay/quota-history.jsonl"
try:
    q = json.load(open(src))
    day = datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m-%d")
    rec = {"day": day, "ts": time.time()}
    for w in ("rolling", "weekly", "monthly"):
        u = (q.get("usage") or {}).get(w) or {}
        rec[w] = {"percent": u.get("percent"), "resetsAt": u.get("resetsAt"), "status": u.get("status")}
    lines = []
    if os.path.exists(hist):
        for ln in open(hist).read().splitlines():
            if not ln.strip():
                continue
            try:
                if json.loads(ln).get("day") != day:
                    lines.append(ln)
            except Exception:
                pass
    lines.append(json.dumps(rec))
    open(hist, "w").write("\n".join(lines[-60:]) + "\n")
    print("quota snapshot %s%% weekly" % rec["weekly"].get("percent"))
except Exception as e:
    print("quota snapshot skipped: %s" % e)
PY

say "ok encrypted size=${SIZE} files=${FILES} total=${TOTAL} remote=${REMOTE_STATUS}"
printf '{"ok":true,"ts":"%s","size":%s,"encrypted":true,"remote":"%s"}\n' \
  "$(date -Is)" "$SIZE" "$REMOTE_STATUS" > "$STATE"
echo "backup OK (encrypted) -> $PLAIN.enc (${SIZE} bytes, ${TOTAL} on disk, remote=${REMOTE_STATUS})"
