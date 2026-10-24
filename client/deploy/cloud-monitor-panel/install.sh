#!/usr/bin/env bash
#
# Cloud Monitor 面板一键安装脚本（目标服务器上以 root 执行）
#
#   ./install.sh                          # 装到 /opt/cloud-monitor，监听 127.0.0.1:9000，路径 /panel
#   ./install.sh --port 9100 --prefix /opt/cm   # 换端口/换目录（同机跑第二实例时用）
#   ./install.sh --direct                 # 不开 nginx，直接绑 0.0.0.0（需自己用安全组/防火墙限源）
#   ./install.sh --nginx-standalone 8091  # 另起一个 nginx server 块，避免与站点已有 :80 冲突
#   ./install.sh --dry-run                # 只打印将要做什么
#
# 幂等：重复执行 = 升级。config.json / keys / 数据目录都会保留。
set -Eeuo pipefail

PREFIX=/opt/cloud-monitor
DATA_DIR=/var/lib/cloud-panel
PORT=9000
BIND=127.0.0.1
BASE_PATH=/panel
SERVICE=cloud-monitor
PASSWORD=""
NODE_MODE=auto            # auto | skip
SYSTEMD=1
NGINX_MODE=auto           # auto(只放 snippet，不改站点) | standalone | off
NGINX_PORT=80
START=1
SMOKE=1
DRY=0
YES=0

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TS="$(date -u +%Y%m%d-%H%M%S)"
RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; CLR=$'\033[0m'
[ -t 1 ] || { RED=""; GRN=""; YLW=""; CLR=""; }

say()  { printf '%s\n' "$*"; }
info() { printf '%s==>%s %s\n' "$GRN" "$CLR" "$*"; }
warn() { printf '%s!!%s %s\n' "$YLW" "$CLR" "$*" >&2; }
die()  { printf '%sxx%s %s\n' "$RED" "$CLR" "$*" >&2; exit 1; }
run()  { if [ "$DRY" = 1 ]; then printf '   [dry] %s\n' "$*"; else "$@"; fi; }
cap()  { if [ "$DRY" = 1 ]; then printf '   [dry] %s\n' "$*" >&2; else eval "$@" >&2; fi; }

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --prefix)        PREFIX="$2"; shift 2 ;;
    --data-dir)      DATA_DIR="$2"; shift 2 ;;
    --port)          PORT="$2"; shift 2 ;;
    --bind)          BIND="$2"; shift 2 ;;
    --base-path)     BASE_PATH="$2"; shift 2 ;;
    --service)       SERVICE="$2"; shift 2 ;;
    --password)      PASSWORD="$2"; shift 2 ;;
    --node)          NODE_MODE="$2"; shift 2 ;;
    --direct)        BIND=0.0.0.0; NGINX_MODE=off; shift ;;
    --nginx-standalone) NGINX_MODE=standalone; NGINX_PORT="$2"; shift 2 ;;
    --no-nginx)      NGINX_MODE=off; shift ;;
    --no-systemd)    SYSTEMD=0; shift ;;
    --no-start)      START=0; shift ;;
    --no-smoke)      SMOKE=0; shift ;;
    --dry-run)       DRY=1; shift ;;
    --yes|-y)        YES=1; shift ;;
    -h|--help)       usage ;;
    *) die "未知参数：$1（--help 看用法）" ;;
  esac
done

case "$PORT" in (''|*[!0-9]*) die "--port 需要数字"; esac
case "$BASE_PATH" in /*) : ;; *) die "--base-path 必须以 / 开头，例如 /panel"; esac
BASE_PATH="${BASE_PATH%/}"
[ -n "$BASE_PATH" ] || BASE_PATH=/panel

ENV_FILE="/etc/${SERVICE}.env"
UNIT_FILE="/etc/systemd/system/${SERVICE}.service"
NGINX_SNIPPET="/etc/nginx/snippets/${SERVICE}-location.conf"
NGINX_CONF="/etc/nginx/conf.d/${SERVICE}.conf"

# ---------- 0. 预检 ----------
info "预检"
[ "$DRY" = 1 ] || [ "$(id -u)" = 0 ] || die "需要 root（安装到 systemd/nginx 都要特权）。演示可用 --dry-run。"
command -v tar >/dev/null || die "缺 tar"
[ -d "$HERE/app/server" ] || die "找不到 $HERE/app/server —— 请在解压出来的包目录内执行本脚本"
[ -f "$HERE/app/server/index.js" ] || die "包不完整：缺 app/server/index.js"
OS_ID=$(. /etc/os-release 2>/dev/null && echo "${ID:-unknown}" || echo unknown)
say "   主机 $(hostname -s 2>/dev/null || echo ?) / ${OS_ID} / 内核 $(uname -m)"
say "   目标目录 $PREFIX · 数据目录 $DATA_DIR · 监听 $BIND:$PORT · 路径前缀 $BASE_PATH · 服务名 $SERVICE"
if [ "$DRY" = 1 ]; then say "   （dry-run：以下每一步只打印，不执行）"; fi

# ---------- 1. Node.js 22+（metricsstore 用内置 node:sqlite） ----------
need_node() {
  command -v node >/dev/null 2>&1 || return 0
  local major; major=$(node -v 2>/dev/null | sed 's/v\([0-9]*\).*/\1/')
  [ "${major:-0}" -ge 22 ] && return 1 || return 0
}
if [ "$NODE_MODE" = skip ]; then
  info "Node：按 --node skip 跳过安装检查"
  command -v node >/dev/null || warn "PATH 里没有 node，稍后启动大概率失败"
elif need_node; then
  have=$(command -v node >/dev/null 2>&1 && node -v || echo 无)
  info "Node：当前 ${have}，需要 >= 22（node:sqlite 指标存储）"
  if [ "$OS_ID" = ubuntu ] || [ "$OS_ID" = debian ]; then
    run apt-get update -qq
    run apt-get install -y -qq curl ca-certificates gnupg
    cap "curl -fsSL https://deb.nodesource.com/setup_22.x -o /tmp/nodesource22.sh && bash /tmp/nodesource22.sh"
    run env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs
  else
    warn "${OS_ID} 不在自动安装范围，请手动装 Node 22 后重跑（或 --node skip）"
  fi
else
  info "Node：$(node -v) 已满足要求"
fi
if command -v node >/dev/null 2>&1; then
  say "   node $(node -v) @ $(command -v node)"
  # node:sqlite 会往 stderr 打 ExperimentalWarning，只看退出码
  if node -e 'require("node:sqlite").DatabaseSync' >/dev/null 2>&1; then
    say "   node:sqlite 可用（历史指标入库正常）"
  else
    warn "   node:sqlite 不可用 —— 面板照常跑，只是历史指标不入库（需 Node >= 22）"
  fi
fi

# ---------- 2. 落代码（升级则备份旧 server/） ----------
info "部署面板本体到 $PREFIX"
run mkdir -p "$PREFIX"
if [ -f "$PREFIX/server/index.js" ]; then
  BK="$PREFIX/.backup-$TS"
  say "   已存在旧版本，代码备份到 $BK（config.json/keys 不动）"
  run mkdir -p "$BK"
  run cp -a "$PREFIX/server" "$BK/" 2>/dev/null || true
fi
run cp -a "$HERE/app/." "$PREFIX/"
run chown -R root:root "$PREFIX"
run chmod 700 "$PREFIX"

# ---------- 3. 依赖：优先离线 vendor，回落 npm ----------
if [ -d "$PREFIX/node_modules/express" ]; then
  info "依赖：已存在 node_modules，跳过"
elif [ -f "$HERE/vendor/node_modules.tar.gz" ]; then
  info "依赖：解包 vendor/node_modules.tar.gz（离线，含 ssh2 纯 JS 回退）"
  run tar -xzf "$HERE/vendor/node_modules.tar.gz" -C "$PREFIX"
else
  info "依赖：包内无 vendor，改用 npm ci（需要外网）"
  command -v npm >/dev/null || die "既没有 vendor/node_modules.tar.gz，也没有 npm —— 无法装依赖"
  cap "cd $PREFIX && npm ci --omit=dev --no-audit --no-fund"
fi

# ---------- 4. 目录与终端宿主密钥 ----------
info "数据目录与密钥"
run mkdir -p "$DATA_DIR"
run chmod 700 "$DATA_DIR"
run mkdir -p "$PREFIX/keys" "$PREFIX/logs"
if [ ! -f "$PREFIX/keys/pty_key" ] && command -v ssh-keygen >/dev/null 2>&1; then
  say "   生成 Web 终端用的宿主密钥 keys/pty_key"
  run ssh-keygen -q -t ed25519 -N '' -f "$PREFIX/keys/pty_key"
  run chmod 600 "$PREFIX/keys/pty_key"
fi

# ---------- 5. 登录口令（首次启动写入哈希，明文只留在临时 env 文件） ----------
if [ -f "$PREFIX/config.json" ]; then
  info "口令：沿用已有 config.json 里的密码哈希"
  PASSWORD_KEEP=1
else
  PASSWORD_KEEP=0
  if [ -z "$PASSWORD" ]; then
    PASSWORD="$(head -c 64 /dev/urandom | base64 | tr -d '=+/' | cut -c1-20)"
    [ "${#PASSWORD}" -ge 12 ] || PASSWORD="Cm$(date +%s%N | md5sum | cut -c1-16)"
    say "   已随机生成登录口令（稍后打印，请立即在面板设置里改）"
  fi
fi

# ---------- 6. systemd 单元 ----------
write_unit() {
  cat > "$UNIT_FILE" <<UNIT
[Unit]
Description=Cloud Monitor web panel (monitoring & configuration)
After=network.target ssh.service

[Service]
Type=simple
WorkingDirectory=$PREFIX
ExecStart=$(command -v node || echo /usr/bin/node) --max-old-space-size=256 --max-semi-space-size=8 server/index.js
Environment=CM_UNDER_SYSTEMD=1
Environment=NODE_ENV=production
Environment=CM_DATA_DIR=$DATA_DIR
Environment=CM_PORT=$PORT
Environment=CM_BIND=$BIND
EnvironmentFile=-$ENV_FILE
Restart=always
RestartSec=3
StandardOutput=journal
StandardError=journal
MemoryHigh=256M
MemoryMax=400M
TasksMax=100
TimeoutStopSec=20
LimitNOFILE=65535
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT
}
write_env() {
  if [ "$PASSWORD_KEEP" = 1 ]; then
    run rm -f "$ENV_FILE"
  else
    umask 077
    printf 'CM_PASSWORD=%s\n' "$PASSWORD" > "$ENV_FILE"
    umask 022
    run chmod 600 "$ENV_FILE"
  fi
}
if [ "$SYSTEMD" = 1 ]; then
  info "systemd 单元 $UNIT_FILE"
  if [ "$DRY" = 1 ]; then printf '   [dry] write %s\n' "$UNIT_FILE"; printf '   [dry] write %s\n' "$ENV_FILE"
  else write_unit; write_env; fi
else
  info "按 --no-systemd 跳过单元（稍后用 --start-cmd 手动跑）"
  say "   前台启动命令：CM_DATA_DIR=$DATA_DIR CM_PORT=$PORT CM_BIND=$BIND$( [ "$PASSWORD_KEEP" = 1 ] || printf ' CM_PASSWORD=%s' "$PASSWORD" ) node $PREFIX/server/index.js"
fi

# ---------- 7. nginx ----------
NGINX_DONE=0
if [ "$NGINX_MODE" != off ] && command -v nginx >/dev/null 2>&1; then
  info "nginx：$NGINX_MODE"
  run mkdir -p /etc/nginx/snippets
  if [ "$DRY" = 1 ]; then
    printf '   [dry] write %s（location %s/ 反代片段）\n' "$NGINX_SNIPPET" "$BASE_PATH"
    [ "$NGINX_MODE" = standalone ] && printf '   [dry] write %s（listen %s 独立 server 块）\n' "$NGINX_CONF" "$NGINX_PORT"
  else
    cat > "$NGINX_SNIPPET" <<SNIP
# 由 ${SERVICE} 安装脚本生成。把它 include 进你的 server{} 即可。
location = ${BASE_PATH} { return 301 ${BASE_PATH}/; }
location ${BASE_PATH}/ {
    proxy_pass http://127.0.0.1:${PORT};
    proxy_http_version 1.1;
    proxy_set_header Host              \$host;
    proxy_set_header X-Real-IP         \$remote_addr;
    proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    # WebSocket（实时指标与 Web 终端）
    proxy_set_header Upgrade    \$http_upgrade;
    proxy_set_header Connection \$connection_upgrade;
    proxy_read_timeout  3600s;
    proxy_send_timeout  3600s;
    proxy_buffering     off;
}
SNIP
    if [ "$NGINX_MODE" = standalone ]; then
      cat > "$NGINX_CONF" <<CONF
# 由 ${SERVICE} 安装脚本生成：独立入口，端口 ${NGINX_PORT}，不与现有 :80 站点冲突。
map \$http_upgrade \$connection_upgrade_${SERVICE} {
    default upgrade;
    ''    close;
}
server {
    listen ${NGINX_PORT};
    listen [::]:${NGINX_PORT};
    server_name _;
    set \$connection_upgrade \$connection_upgrade_${SERVICE};
    include ${NGINX_SNIPPET};
}
CONF
    fi
    if nginx -t >/dev/null 2>&1; then
      nginx -s reload >/dev/null 2>&1 || systemctl reload nginx >/dev/null 2>&1 || true
      NGINX_DONE=1
      if [ "$NGINX_MODE" = standalone ]; then
        say "   已写入 $NGINX_CONF 并 reload —— 入口 http://<服务器IP>:${NGINX_PORT}${BASE_PATH}/"
      else
        say "   片段已写到 $NGINX_SNIPPET"
        say "   在你现有站点的 server{} 里加一行： include ${NGINX_SNIPPET};"
        say "   同时确认 http{} 里有 map（WebSocket 需要）：map \$http_upgrade \$connection_upgrade { default upgrade; '' close; }"
      fi
    else
      warn "nginx -t 未通过，已回退：不 reload，保留原配置"
      [ -f "$NGINX_CONF" ] && rm -f "$NGINX_CONF"
      warn "片段仍在 $NGINX_SNIPPET，可手工 include；当前只能直连 $BIND:$PORT"
    fi
  fi
elif [ "$NGINX_MODE" != off ]; then
  warn "本机没有 nginx —— 用直连模式（面板监听 $BIND:$PORT）或装 nginx 后重跑"
fi

# ---------- 8. 启动 ----------
if [ "$SYSTEMD" = 1 ] && [ "$START" = 1 ]; then
  info "启动服务 $SERVICE"
  if [ "$DRY" = 1 ]; then
    printf '   [dry] systemctl daemon-reload && systemctl enable --now %s\n' "$SERVICE"
  else
    systemctl daemon-reload
    systemctl enable "$SERVICE" >/dev/null 2>&1 || true
    systemctl restart "$SERVICE"
  fi
fi

# ---------- 9. 冒烟：等端口起来 → 登录 → 取概览 ----------
smoke() {
  local url="http://127.0.0.1:${PORT}${BASE_PATH}/api"
  local i tok body
  for i in $(seq 1 40); do
    curl -fs -o /dev/null "http://127.0.0.1:${PORT}${BASE_PATH}/" 2>/dev/null && break
    sleep 0.5
  done
  tok=$(curl -fs -X POST "$url/login" -H 'Content-Type: application/json' \
        -d "{\"password\":\"${PASSWORD}\"}" 2>/dev/null \
        | sed -n 's/.*"token":"\([a-f0-9]*\)".*/\1/p')
  [ -n "$tok" ] || { warn "登录拿不到 token —— 查看日志：journalctl -u $SERVICE -n 50"; return 1; }
  say "   登录 OK（token ${tok:0:8}…）"
  body=$(curl -fs -H "Authorization: Bearer $tok" "$url/ops/overview" 2>/dev/null || echo '')
  if printf '%s' "$body" | grep -q '"ok":true'; then
    say "   ops 模块 OK（拨测/规则/渠道/文件/巡检/磁盘/看护/远程 已挂载）"
  else
    warn "ops/overview 无响应 —— ops 模块可能没挂上：journalctl -u $SERVICE | grep 'ops modules'"
  fi
  curl -fs -o /dev/null "http://127.0.0.1:${PORT}${BASE_PATH}/public/index.html" \
    && say "   Web 壳 OK（静态页可取）" || warn "静态页取不到：public/index.html 缺失？"
  # 实时指标与 Web 终端都走 WebSocket，握手必须是 101
  local wsk ws_code
  wsk=$(head -c 16 /dev/urandom | base64)
  ws_code=$(curl -s --max-time 4 -o /dev/null -w '%{http_code}' \
            -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
            -H 'Sec-WebSocket-Version: 13' -H "Sec-WebSocket-Key: $wsk" \
            "http://127.0.0.1:${PORT}${BASE_PATH}/ws?token=${tok}")
  [ "$ws_code" = 101 ] && say "   WebSocket OK（/ws 握手 101）" \
    || warn "   /ws 握手返回 $ws_code（期望 101）—— 实时指标与终端会不可用"
  # 密码哈希已落 config.json，明文不再需要
  if [ "${PASSWORD_KEEP:-0}" = 0 ] && [ -f "$PREFIX/config.json" ]; then
    run rm -f "$ENV_FILE"
    say "   已删除临时口令文件 $ENV_FILE（密码哈希已写入 config.json）"
  fi
  return 0
}
if [ "$SMOKE" = 1 ] && [ "$DRY" = 0 ] && [ "$SYSTEMD" = 1 ] && [ "$START" = 1 ]; then
  info "冒烟自检（本机 127.0.0.1）"
  smoke || die "冒烟未通过，安装未完成"
elif [ "$SMOKE" = 1 ] && [ "$DRY" = 0 ]; then
  info "冒烟自检"
  info "   未走 systemd —— 请手动启动：CM_DATA_DIR=$DATA_DIR CM_PORT=$PORT CM_BIND=$BIND$( [ "$PASSWORD_KEEP" = 1 ] || printf ' CM_PASSWORD=%s' "$PASSWORD" ) node $PREFIX/server/index.js &"
  info "   启动后重跑本脚本加 --systemd 或执行：curl -X POST http://127.0.0.1:$PORT$BASE_PATH/api/login -d '{\"password\":\"…\"}'"
fi

# ---------- 10. 收尾打印 ----------
PWLINE=""
if [ "${PASSWORD_KEEP:-0}" = 0 ]; then PWLINE="登录口令：$PASSWORD"; fi
cat <<EOF

$(info '安装完成')
EOF
if [ "$DRY" = 1 ]; then
  say "dry-run 结束：未做任何改动。去掉 --dry-run 即按上面步骤执行。"
else
  say "  代码目录   $PREFIX（0700，root）"
  say "  数据目录   $DATA_DIR（ops 状态、日志、备份）"
  if [ "$SYSTEMD" = 1 ]; then
    say "  服务       ${SERVICE}.service · systemctl status ${SERVICE} · journalctl -u ${SERVICE} -f"
  else
    say "  启动方式   未装 systemd 单元 —— 前台命令："
    say "             CM_DATA_DIR=$DATA_DIR CM_PORT=$PORT CM_BIND=$BIND$( [ "$PASSWORD_KEEP" = 1 ] || printf ' CM_PASSWORD=%s' "$PASSWORD" ) node $PREFIX/server/index.js"
    say "             之后重跑 ./install.sh（不带 --no-systemd）即可注册服务"
  fi
  if [ "$NGINX_DONE" = 1 ] && [ "$NGINX_MODE" = standalone ]; then
    say "  访问入口   http://$(hostname -I 2>/dev/null | awk '{print $1}'):${NGINX_PORT}${BASE_PATH}/"
  elif [ "$BIND" = 0.0.0.0 ]; then
    say "  访问入口   http://$(hostname -I 2>/dev/null | awk '{print $1}'):${PORT}${BASE_PATH}/ （公网可达，务必用安全组限源）"
  else
    say "  本机入口   http://127.0.0.1:${PORT}${BASE_PATH}/ （只回环；远程请用 nginx 或 ssh -L 8080:127.0.0.1:${PORT}）"
  fi
  [ -n "$PWLINE" ] && say "  $PWLINE"
  say "  客户端对接 KMP 客户端把服务器地址改成上面入口的 scheme://host 即可（登录页可改），无需重新编译"
  say "  改密码     登录后「面板设置」；或 rm $PREFIX/config.json 里的 passwordHash 并重启"
  say "  卸载       ./uninstall.sh（保留数据）· ./uninstall.sh --purge-data"
fi
