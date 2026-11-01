#!/usr/bin/env bash
# ============================================================
# RelayHub 内网设备客户端 · 一键安装脚本
# 在内网 Linux 设备(树莓派/NAS/任意服务器)上以 root 运行:
#   curl -fsSL <面板地址>/install.sh | bash -s -- --name 设备名 --token <令牌>
# 可选:
#   --ssh-port N        SSH 映射的公网端口(缺省自动分配)
#   --map 本地:公网      映射额外服务,可重复多次
#   --uninstall         卸载
# ============================================================
set -euo pipefail

PANEL_URL="__PANEL_URL__"
SERVER_ADDR="__SERVER_ADDR__"
SERVER_PORT="__SERVER_PORT__"
FRP_VER="__FRP_VER__"

NAME="" TOKEN="" SSH_PORT=""
MAPS=()
UNINSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:-}"; shift 2 ;;
    --token) TOKEN="${2:-}"; shift 2 ;;
    --ssh-port) SSH_PORT="${2:-}"; shift 2 ;;
    --map) MAPS+=("${2:-}"); shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,2\}//'; exit 0 ;;
    *) echo "未知参数: $1(查看 --help)"; exit 1 ;;
  esac
done

B="\033[1;36m"; G="\033[1;32m"; R="\033[0;31m"; N="\033[0m"
say()  { echo -e "${B}[relayhub]${N} $*"; }
good() { echo -e "${G}[relayhub]${N} $*"; }
die()  { echo -e "${R}[relayhub] $*${N}"; exit 1; }

if [ "$UNINSTALL" -eq 1 ]; then
  systemctl disable --now relay-client 2>/dev/null || true
  rm -f /etc/systemd/system/relay-client.service /etc/frp/frpc.toml /usr/local/bin/frpc
  systemctl daemon-reload
  good "已卸载 relay-client(日志保留在 /var/log/relay-client.log)"
  exit 0
fi

[ "$(id -u)" -eq 0 ] || die "请以 root 运行(sudo)"
[ -n "$NAME" ]  || die "缺少 --name(设备名,小写字母/数字/-)"
[ -n "$TOKEN" ] || die "缺少 --token(在面板『接入指南』页查看)"
echo "$NAME" | grep -Eq '^[a-z0-9][a-z0-9-]{0,30}$' || die "设备名仅允许小写字母、数字与 -,且以字母或数字开头"
command -v systemctl >/dev/null || die "未检测到 systemd"
command -v curl >/dev/null || die "缺少 curl,请先安装"

case "$(uname -m)" in
  x86_64)         ARCH=amd64 ;;
  aarch64|arm64)  ARCH=arm64 ;;
  armv6l|armv7l|armhf) ARCH=arm ;;
  riscv64)        ARCH=riscv64 ;;
  *) die "不支持的架构: $(uname -m)" ;;
esac

# ---------- 1. 安装 frpc ----------
say "安装 frpc v${FRP_VER} (${ARCH}) ..."
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
if curl -fsSL -m 120 -o "$TMP/frpc.tgz" "$PANEL_URL/dl/frp_${FRP_VER}_linux_${ARCH}.tar.gz"; then
  say "已从面板分发点下载"
else
  say "面板分发点不可用,回退 GitHub ..."
  curl -fsSL -m 180 -o "$TMP/frpc.tgz" \
    "https://github.com/fatedier/frp/releases/download/v${FRP_VER}/frp_${FRP_VER}_linux_${ARCH}.tar.gz" \
    || die "frpc 下载失败"
fi
tar xzf "$TMP/frpc.tgz" -C "$TMP"
install -m 0755 "$TMP/frp_${FRP_VER}_linux_${ARCH}/frpc" /usr/local/bin/frpc
good "frpc -> $(/usr/local/bin/frpc -v)"

# ---------- 2. 分配 SSH 公网端口 ----------
if [ -z "$SSH_PORT" ]; then
  SSH_PORT=$(curl -fsSL -m 10 "$PANEL_URL/api/alloc-port" | sed -n 's/.*"port": *\([0-9]*\).*/\1/p')
  [ -n "$SSH_PORT" ] || die "公网端口自动分配失败,请用 --ssh-port 手工指定(__PORT_RANGE__)"
fi
say "SSH 公网端口: ${SSH_PORT}"

# ---------- 3. 生成配置 ----------
mkdir -p /etc/frp
{
  cat <<EOF
# RelayHub client config (由 install.sh 生成,设备: ${NAME})
serverAddr = "${SERVER_ADDR}"
serverPort = ${SERVER_PORT}
user = "${NAME}"
auth.token = "${TOKEN}"
loginFailExit = false

# 80 端口复用 nginx,必须以 WebSocket 接入
transport.protocol = "websocket"

log.to = "/var/log/relay-client.log"
log.level = "info"
log.maxDays = 3

[[proxies]]
name = "ssh"
type = "tcp"
localIP = "127.0.0.1"
localPort = 22
remotePort = ${SSH_PORT}
EOF
  for m in "${MAPS[@]:-}"; do
    [ -z "$m" ] && continue
    lp=${m%%:*}; rp=${m##*:}
    [ "$lp" = "$rp" ] && die "--map 格式应为 本地端口:公网端口"
    cat <<EOF

[[proxies]]
name = "port-${lp}"
type = "tcp"
localIP = "127.0.0.1"
localPort = ${lp}
remotePort = ${rp}
EOF
  done
} > /etc/frp/frpc.toml
chmod 600 /etc/frp/frpc.toml
good "配置 -> /etc/frp/frpc.toml"

# ---------- 4. 注册 systemd 服务 ----------
cat > /etc/systemd/system/relay-client.service <<'EOF'
[Unit]
Description=RelayHub client (frpc tunnel)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/frpc -c /etc/frp/frpc.toml
Restart=always
RestartSec=5
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now relay-client >/dev/null 2>&1
sleep 2
systemctl is-active --quiet relay-client || { journalctl -u relay-client -n 20 --no-pager; die "服务启动失败,请检查 token/网络"; }

# 等待真实连上中继(以 frpc 日志的登录成功为准,而非仅服务运行)
CONNECTED=0
for i in $(seq 1 15); do
  if grep -q "login to server success" /var/log/relay-client.log 2>/dev/null; then CONNECTED=1; break; fi
  sleep 1
done
[ $CONNECTED -eq 1 ] || { journalctl -u relay-client -n 10 --no-pager; die "服务已运行但未能连上中继(检查 token 与网络后重启: systemctl restart relay-client)"; }

echo
good "安装完成!设备『${NAME}』已接入中继。"
echo -e "  从公网 SSH 连回本机: ${G}ssh -p ${SSH_PORT} <用户>@${SERVER_ADDR}${N}"
echo -e "  服务管理: systemctl {status|restart} relay-client"
echo -e "  查看日志: journalctl -u relay-client -f 或 tail -f /var/log/relay-client.log"
