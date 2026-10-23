#!/usr/bin/env bash
#
# NoteBox install (run as root on Ubuntu/Debian).
#
#   ./install.sh
#   ./install.sh --port 13346 --prefix /opt/notebox-staging
#   ./install.sh --dry-run
#
# Idempotent: re-run upgrades application files; data under NB_DATA_DIR is kept.
set -Eeuo pipefail

PREFIX=/opt/notebox
APP_DIR="${PREFIX}/app"
DATA_DIR="${PREFIX}/data"
VENV="${PREFIX}/venv"
PORT=13345
HOST=0.0.0.0
SERVICE=notebox
RUN_USER=notebox
START=1
DRY=0

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_APP="${HERE}/app"

RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; CLR=$'\033[0m'
[ -t 1 ] || { RED=""; GRN=""; YLW=""; CLR=""; }
info() { printf '%s==>%s %s\n' "$GRN" "$CLR" "$*"; }
warn() { printf '%s!!%s %s\n' "$YLW" "$CLR" "$*" >&2; }
die()  { printf '%sxx%s %s\n' "$RED" "$CLR" "$*" >&2; exit 1; }
run()  { if [ "$DRY" = 1 ]; then printf '   [dry] %s\n' "$*"; else "$@"; fi; }

usage() {
  sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --prefix)   PREFIX="$2"; APP_DIR="${PREFIX}/app"; DATA_DIR="${PREFIX}/data"; VENV="${PREFIX}/venv"; shift 2 ;;
    --port)     PORT="$2"; shift 2 ;;
    --host)     HOST="$2"; shift 2 ;;
    --service)  SERVICE="$2"; shift 2 ;;
    --user)     RUN_USER="$2"; shift 2 ;;
    --no-start) START=0; shift ;;
    --dry-run)  DRY=1; shift ;;
    -h|--help)  usage ;;
    *) die "Unknown option: $1" ;;
  esac
done

[ -d "$SRC_APP" ] || die "Missing ${SRC_APP} (clone the full notebox/ tree)"
[ -f "$SRC_APP/run.py" ] || die "Missing ${SRC_APP}/run.py"
command -v python3 >/dev/null 2>&1 || die "python3 not found"

UNIT="/etc/systemd/system/${SERVICE}.service"

info "NoteBox → prefix=${PREFIX} port=${PORT} user=${RUN_USER}"

run mkdir -p "$APP_DIR" "$DATA_DIR"
if [ "$DRY" = 1 ]; then
  printf '   [dry] rsync %s/ -> %s/\n' "$SRC_APP" "$APP_DIR"
else
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete --exclude '__pycache__' --exclude '*.pyc' "$SRC_APP/" "$APP_DIR/"
  else
    find "$APP_DIR" -mindepth 1 -maxdepth 1 ! -name 'data' -exec rm -rf {} + 2>/dev/null || true
    cp -a "$SRC_APP/." "$APP_DIR/"
    find "$APP_DIR" -type d -name __pycache__ -prune -exec rm -rf {} + 2>/dev/null || true
  fi
fi

if ! id "$RUN_USER" &>/dev/null; then
  info "Creating system user ${RUN_USER}"
  run useradd --system --home "$PREFIX" --shell /usr/sbin/nologin "$RUN_USER"
fi

if [ ! -x "${VENV}/bin/python" ]; then
  info "Creating venv at ${VENV}"
  run python3 -m venv "$VENV"
fi

info "Installing Python dependencies"
if [ "$DRY" = 1 ]; then
  printf '   [dry] %s/bin/pip install -r %s/requirements.txt\n' "$VENV" "$APP_DIR"
else
  "${VENV}/bin/pip" install -q --upgrade pip
  "${VENV}/bin/pip" install -q -r "${APP_DIR}/requirements.txt"
fi

run chown -R "${RUN_USER}:${RUN_USER}" "$PREFIX"
run chmod 0750 "$PREFIX"
run chmod 0750 "$DATA_DIR"

info "Writing ${UNIT}"
if [ "$DRY" = 1 ]; then
  printf '   [dry] write systemd unit %s\n' "$UNIT"
else
  cat >"$UNIT" <<EOF
[Unit]
Description=NoteBox personal notes and todo server
After=network.target

[Service]
Type=simple
User=${RUN_USER}
Group=${RUN_USER}
WorkingDirectory=${APP_DIR}
Environment=NB_HOST=${HOST}
Environment=NB_PORT=${PORT}
Environment=NB_DATA_DIR=${DATA_DIR}
Environment=TZ=Asia/Shanghai
Environment=PYTHONUNBUFFERED=1
ExecStart=${VENV}/bin/python ${APP_DIR}/run.py
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
fi

if [ "$START" = 1 ]; then
  run systemctl enable "$SERVICE"
  run systemctl restart "$SERVICE"
  sleep 1
  if [ "$DRY" != 1 ]; then
    if systemctl is-active --quiet "$SERVICE"; then
      info "Service ${SERVICE} is active"
    else
      warn "Service not active; check: journalctl -u ${SERVICE} -n 40 --no-pager"
    fi
  fi
fi

info "Done."
say_url="${HOST}"
[ "$say_url" = "0.0.0.0" ] && say_url="<server-ip>"
printf '\n  Web UI:  http://%s:%s/\n' "$say_url" "$PORT"
printf '  API:     http://%s:%s/api/...\n' "$say_url" "$PORT"
printf '  Data:    %s\n' "$DATA_DIR"
printf '\n  Register the first user via the web UI or API; set public base URL in admin settings for email links.\n'
printf '  KMP client: point NoteBox URL to http://<host>:%s in the Notes section.\n\n' "$PORT"
