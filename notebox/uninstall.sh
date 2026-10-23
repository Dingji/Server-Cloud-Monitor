#!/usr/bin/env bash
set -Eeuo pipefail

PREFIX=/opt/notebox
SERVICE=notebox
RUN_USER=notebox
PURGE_DATA=0
PURGE_USER=0

while [ $# -gt 0 ]; do
  case "$1" in
    --prefix)      PREFIX="$2"; shift 2 ;;
    --service)     SERVICE="$2"; shift 2 ;;
    --purge-data)  PURGE_DATA=1; shift ;;
    --purge-user)  PURGE_USER=1; shift ;;
    *) echo "Unknown: $1" >&2; exit 1 ;;
  esac
done

UNIT="/etc/systemd/system/${SERVICE}.service"
systemctl stop "$SERVICE" 2>/dev/null || true
systemctl disable "$SERVICE" 2>/dev/null || true
rm -f "$UNIT"
systemctl daemon-reload 2>/dev/null || true

if [ "$PURGE_DATA" = 1 ]; then
  rm -rf "${PREFIX}/data"
fi
if [ "$PURGE_USER" = 1 ] && id "$RUN_USER" &>/dev/null; then
  userdel "$RUN_USER" 2>/dev/null || true
fi

echo "Stopped ${SERVICE}. Application tree ${PREFIX} left in place (use --purge-data to remove SQLite)."
