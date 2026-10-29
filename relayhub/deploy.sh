#!/usr/bin/env bash
# RelayHub 服务端部署脚本(在目标服务器上运行)
# 前置: /opt/relayhub/ 下已有 panel/ ; downloads/ 缺包时按 FRP_VER 自动下载
#
# 环境变量:
#   RELAYHUB_SKIP_NGINX=1   不动 nginx(由 cloud-monitor install.sh 自己写 location 片段)
#   RELAYHUB_NGINX_CONF=…   要改的站点文件, 缺省 /etc/nginx/sites-enabled/opencode-relay
#   RELAYHUB_PANEL_PORT=…   面板后端端口, 缺省 8100
#   RELAYHUB_FRP_VER=…      downloads/ 为空时下载哪个 frp 版本
set -euo pipefail

HUB=/opt/relayhub
PUB_IP=$(curl -fsSL -m 8 http://ifconfig.me 2>/dev/null \
         || curl -fsSL -m 8 https://api.ipify.org 2>/dev/null \
         || hostname -I 2>/dev/null | awk '{print $1}' \
         || echo 127.0.0.1)
PANEL_PORT=${RELAYHUB_PANEL_PORT:-8100}
FRPS_PORT=7000
DASH_PORT=7500
NGINX_TARGET="${RELAYHUB_NGINX_CONF:-/etc/nginx/sites-enabled/opencode-relay}"
FRP_VER=$(ls "$HUB/downloads" 2>/dev/null | head -1 | sed -n 's/^frp_\([0-9.]*\)_.*/\1/p')
[ -n "$FRP_VER" ] || FRP_VER="${RELAYHUB_FRP_VER:-0.71.1}"
say() { echo -e "\033[1;36m[deploy]\033[0m $*"; }

say "公网 IP: $PUB_IP  frp 版本: $FRP_VER"

# ---------- 端口池参数(分发可配置, 不再写死; 面板内可继续图形化调整) ----------
PORT_RANGES=()    # --port-range 36000-36999 (可重复)
EXCLUDE=()        # --exclude 36000 (可重复 / 逗号分隔)
ALLOC="sequential"
MAX_PORTS=16
SYNC_UFW=1
POOL_ARGS=0
usage() {
  cat <<'USAGE'
用法: deploy.sh [选项]
  --port-range A-B   公网端口段, 可重复(缺省 6000-6999)
                     例: --port-range 36000-36999 --port-range 12838
  --exclude P[,Q]    池内排除端口(不分配、不放行), 可重复
  --alloc POLICY     分配策略 sequential|random|least_used(缺省 sequential)
  --max-ports N      每客户端代理数上限, 同步 frps maxPortsPerClient(缺省 16)
  --no-ufw           不自动改 ufw 防火墙规则
  -h, --help         显示本帮助
不带池参数重跑时, 沿用 config.json 已有的 port_pool(面板改过的配置不会被打回)。
USAGE
}
while [ $# -gt 0 ]; do
  case "$1" in
    --port-range) PORT_RANGES+=("${2:?缺参数}"); POOL_ARGS=1; shift 2 ;;
    --exclude)    IFS=',' read -r -a _ex <<< "${2:?缺参数}"
                  for _e in ${_ex[@]+"${_ex[@]}"}; do EXCLUDE+=("$_e"); done
                  POOL_ARGS=1; shift 2 ;;
    --alloc)      ALLOC="${2:?缺参数}"; POOL_ARGS=1; shift 2 ;;
    --max-ports)  MAX_PORTS="${2:?缺参数}"; POOL_ARGS=1; shift 2 ;;
    --no-ufw)     SYNC_UFW=0; shift ;;
    -h|--help)    usage; exit 0 ;;
    *) say "未知参数: $1"; usage; exit 1 ;;
  esac
done
[ ${#PORT_RANGES[@]} -gt 0 ] || PORT_RANGES=("6000-6999")
EXCLUDE_CSV=""
for _e in ${EXCLUDE[@]+"${EXCLUDE[@]}"}; do EXCLUDE_CSV="${EXCLUDE_CSV:+$EXCLUDE_CSV,}$_e"; done

# 带池参数时按参数构建;否则沿用 config.json 已有的 port_pool(面板改的不回退)
POOL_JSON=""
if [ "$POOL_ARGS" = 0 ] && [ -f "$HUB/panel/config.json" ]; then
  POOL_JSON=$(CFG_PATH="$HUB/panel/config.json" python3 -X utf8 - <<'PY'
import json, os, sys
try:
    p = json.load(open(os.environ["CFG_PATH"], encoding="utf-8")).get("port_pool")
    if isinstance(p, dict) and p.get("ranges"):
        sys.stdout.write(json.dumps(p, ensure_ascii=False))
except Exception:
    pass
PY
)
  [ -n "$POOL_JSON" ] && say "沿用 config.json 已有端口池"
fi
if [ -z "$POOL_JSON" ]; then
  POOL_JSON=$(PORT_RANGES="${PORT_RANGES[*]}" EXCLUDE_CSV="$EXCLUDE_CSV" \
    ALLOC="$ALLOC" MAX_PORTS="$MAX_PORTS" SYNC_UFW="$SYNC_UFW" python3 -X utf8 - <<'PY'
import json, os
ranges = []
for spec in (os.environ.get("PORT_RANGES") or "").split():
    a, b = spec.split("-", 1) if "-" in spec else (spec, spec)
    ranges.append({"label": "段%d" % (len(ranges) + 1), "start": int(a), "end": int(b)})
exc = sorted({int(x) for x in (os.environ.get("EXCLUDE_CSV") or "").split(",") if x.strip()})
print(json.dumps({"ranges": ranges, "exclude": exc,
                  "alloc_policy": os.environ.get("ALLOC") or "sequential",
                  "max_per_client": max(1, min(256, int(os.environ.get("MAX_PORTS") or 16))),
                  "sync_ufw": os.environ.get("SYNC_UFW", "1") == "1",
                  "protected": [22, 80, 443, 7000, 7500, 8100, 13345]},
                 ensure_ascii=False))
PY
)
fi

# 派生 frps allowPorts / ufw 规则 / 文案(扣除 exclude, 合并相邻段)
POOL_DERIVED=$(POOL_JSON="$POOL_JSON" python3 -X utf8 - <<'PY'
import json, os, sys
p = json.loads(os.environ["POOL_JSON"])
exc = sorted({int(e) for e in (p.get("exclude") or [])})
eff = []
for r in sorted(p.get("ranges") or [], key=lambda r: int(r["start"])):
    a, b, cur = int(r["start"]), int(r["end"]), int(r["start"])
    for e in exc:
        if e < cur:
            continue
        if e > b:
            break
        if cur <= e - 1:
            eff.append((cur, e - 1))
        cur = e + 1
    if cur <= b:
        eff.append((cur, b))
merged = []
for a, b in sorted(eff):
    if merged and a <= merged[-1][1] + 1:
        merged[-1] = (merged[-1][0], max(merged[-1][1], b))
    else:
        merged.append((a, b))
if not merged:
    sys.exit("端口池为空(检查 --port-range / --exclude)")
specs = ["%d/tcp" % a if a == b else "%d:%d/tcp" % (a, b) for a, b in merged]
print(json.dumps(p, ensure_ascii=False))
print("[" + ", ".join("{ start = %d, end = %d }" % (a, b) for a, b in merged) + "]")
print(" ".join(specs))
print(", ".join("%d-%d" % (a, b) if a != b else str(a) for a, b in merged))
PY
)
POOL_JSON=$(sed -n '1p' <<<"$POOL_DERIVED")
ALLOW_TOML=$(sed -n '2p' <<<"$POOL_DERIVED")
UFW_SPECS=$(sed -n '3p' <<<"$POOL_DERIVED")
POOL_TEXT=$(sed -n '4p' <<<"$POOL_DERIVED")
MAX_PORTS=$(POOL_JSON="$POOL_JSON" python3 -X utf8 -c 'import json,os;print(int(json.loads(os.environ["POOL_JSON"]).get("max_per_client") or 16))')
say "端口池: ${POOL_TEXT} · 每客户端 ${MAX_PORTS} 个代理 · ufw=${SYNC_UFW}"

# ---------- 1. frps ----------
case "$(uname -m)" in
  x86_64)         FRP_ARCH=amd64 ;;
  aarch64|arm64)  FRP_ARCH=arm64 ;;
  riscv64)        FRP_ARCH=riscv64 ;;
  *) echo "不支持的架构: $(uname -m)"; exit 1 ;;
esac
FRP_TGZ="frp_${FRP_VER}_linux_${FRP_ARCH}.tar.gz"
if [ ! -f "$HUB/downloads/$FRP_TGZ" ]; then
  say "downloads/ 缺 $FRP_TGZ —— 从 GitHub 取 frp ${FRP_VER}"
  mkdir -p "$HUB/downloads"
  curl -fsSL -m 300 -o "$HUB/downloads/$FRP_TGZ" \
    "https://github.com/fatedier/frp/releases/download/v${FRP_VER}/${FRP_TGZ}" \
    || { echo "frp 下载失败,请手工放好 $HUB/downloads/$FRP_TGZ 后重跑"; exit 1; }
fi
if [ ! -x "$HUB/frps" ]; then
  TMP=$(mktemp -d)
  tar xzf "$HUB/downloads/$FRP_TGZ" -C "$TMP"
  install -m 0755 "$TMP/frp_${FRP_VER}_linux_${FRP_ARCH}/frps" "$HUB/frps"
  rm -rf "$TMP"
fi
[ -x "$HUB/frps" ] || { echo "frps 安装失败"; exit 1; }

# 若已有 token 沿用,保证可重复执行
TOKEN=$(grep -oP 'token = "\K[^"]+' "$HUB/frps.toml" 2>/dev/null || true)
[ -n "$TOKEN" ] || TOKEN=$(python3 -c "import secrets;print(secrets.token_hex(16))")
DASH_PW=$(grep -oP '#DASHPW=\K.*' "$HUB/frps.toml" 2>/dev/null || true)
[ -n "$DASH_PW" ] || DASH_PW=$(python3 -c "import secrets;print(secrets.token_urlsafe(14))")

mkdir -p /var/log/relayhub
cat > "$HUB/frps.toml" <<EOF
# RelayHub frps config (由 deploy.sh 生成)
bindAddr = "0.0.0.0"
bindPort = ${FRPS_PORT}

auth.token = "${TOKEN}"

# 每个客户端最多注册的代理数(由 --max-ports / port_pool.max_per_client 派生)
maxPortsPerClient = ${MAX_PORTS}
# 允许客户端申请的公网端口段(由端口池派生, 已扣除 exclude)
allowPorts = ${ALLOW_TOML}

# 面板后端通过本机回环地址读取 frps 指标
webServer.addr = "127.0.0.1"
webServer.port = ${DASH_PORT}
webServer.user = "relayhub"
webServer.password = "${DASH_PW}" #DASHPW=${DASH_PW}

log.to = "/var/log/relayhub/frps.log"
log.level = "info"
log.maxDays = 7

transport.maxPoolCount = 20
EOF
chmod 600 "$HUB/frps.toml"

cat > /etc/systemd/system/relayhub-frps.service <<'EOF'
[Unit]
Description=RelayHub frps (frp relay server)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/relayhub/frps -c /opt/relayhub/frps.toml
Restart=always
RestartSec=3
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# ---------- 2. 面板配置 ----------
ADMIN_PW=$(python3 - <<'PY'
import json, pathlib
p = pathlib.Path("/opt/relayhub/panel/config.json")
if p.exists():
    try:
        old = json.loads(p.read_text())
        if old.get("admin_password"):
            print(old["admin_password"]); raise SystemExit
    except SystemExit: raise
    except Exception: pass
import secrets, string
print("".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(10)))
PY
)
# 合并写入 config.json: 只更新部署字段与端口池, 保留既有字段
# (internal_key / session_secret / 口令哈希 / 面板改过的配置), 绝不整文件覆盖
POOL_JSON="$POOL_JSON" PANEL_PORT="$PANEL_PORT" PUB_IP="$PUB_IP" FRPS_PORT="$FRPS_PORT" \
FRP_VER="$FRP_VER" TOKEN="$TOKEN" DASH_PORT="$DASH_PORT" DASH_PW="$DASH_PW" \
ADMIN_PW="$ADMIN_PW" python3 -X utf8 - <<'PY'
import json, os, pathlib
p = pathlib.Path("/opt/relayhub/panel/config.json")
try:
    cfg = json.loads(p.read_text("utf-8"))
    assert isinstance(cfg, dict)
except Exception:
    cfg = {}
pool = json.loads(os.environ["POOL_JSON"])
ranges = [[int(r["start"]), int(r["end"])] for r in pool["ranges"]]
cfg.update({
    "bind_host": "127.0.0.1",
    "bind_port": int(os.environ["PANEL_PORT"]),
    "public_addr": os.environ["PUB_IP"],
    "panel_base": "http://%s/relayhub" % os.environ["PUB_IP"],
    "frps_bind_port": int(os.environ["FRPS_PORT"]),
    "frp_version": os.environ["FRP_VER"],
    "frps_token": os.environ["TOKEN"],
    "frps": {"host": "127.0.0.1", "port": int(os.environ["DASH_PORT"]),
             "user": "relayhub", "password": os.environ["DASH_PW"]},
    "admin_password": cfg.get("admin_password") or os.environ["ADMIN_PW"],
    # 端口池: 单一可信源(旧键由面板应用时镜像)
    "port_pool": pool,
    "remote_port_ranges": ranges,
    "port_range_start": min(a for a, b in ranges),
    "port_range_end": max(b for a, b in ranges),
})
p.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), "utf-8")
os.chmod(p, 0o600)
print("config.json 已合并(保留既有字段)")
PY

cat > /etc/systemd/system/relayhub-panel.service <<'EOF'
[Unit]
Description=RelayHub management panel
After=network-online.target relayhub-frps.service
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/relayhub/panel
ExecStart=/usr/bin/python3 app.py
Restart=always
RestartSec=3
MemoryMax=200M
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

# ---------- 3. nginx 子路径 ----------
# RELAYHUB_SKIP_NGINX=1 时完全交给调用方(cloud-monitor install.sh 用片段文件挂 /relayhub/)
if [ "${RELAYHUB_SKIP_NGINX:-0}" = 1 ]; then
  say "nginx: 按 RELAYHUB_SKIP_NGINX 跳过(由调用方挂 /relayhub/)"
elif [ ! -f "$NGINX_TARGET" ]; then
  say "nginx: 目标站点 $NGINX_TARGET 不存在 —— 跳过,请手工把 /relayhub/ 反代到 127.0.0.1:${PANEL_PORT}"
else
  rm -f "$(dirname "$NGINX_TARGET")"/*.bak.* 2>/dev/null || true
  if ! grep -q "RelayHub panel" "$NGINX_TARGET"; then
    RH_BAK="/etc/nginx/relayhub-deploy.bak.$(date +%s)"
    cp "$NGINX_TARGET" "$RH_BAK"
    NGINX_TARGET="$NGINX_TARGET" PANEL_PORT="$PANEL_PORT" python3 - <<'PY'
import os, pathlib
p = pathlib.Path(os.environ["NGINX_TARGET"])
text = p.read_text()
block = """    # ==== RelayHub panel (managed block, do not edit by hand) ====
    location = /relayhub { return 301 /relayhub/; }
    location /relayhub/ {
        proxy_pass http://127.0.0.1:%s/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        client_max_body_size 20m;
        gzip on;
        gzip_types text/css application/javascript application/json image/svg+xml;
        add_header X-Robots-Tag "noindex, nofollow" always;
    }
    # ==== end RelayHub panel ====

""" % os.environ.get("PANEL_PORT", "8100")
anchor = "    location / {"
assert anchor in text, "anchor not found"
p.write_text(text.replace(anchor, block + anchor, 1))
print("nginx block inserted")
PY
    if nginx -t; then
      systemctl reload nginx
      say "nginx 已加载 /relayhub/"
    else
      cp "$RH_BAK" "$NGINX_TARGET"
      say "nginx -t 未通过,已回滚到 $RH_BAK,未 reload"
      exit 1
    fi
  else
    say "nginx 配置已存在,跳过"
  fi
fi

# ---------- 4. 防火墙 ----------
ufw allow ${FRPS_PORT}/tcp comment 'frp bind' >/dev/null || true
if [ "$SYNC_UFW" = 1 ]; then
  # 回收历史端口池规则(按注释标记识别), 避免改范围后残留旧放行
  ( ufw status numbered | sed -n \
      's/^ *\[\([0-9]*\)\].*# \(relayhub-pool\|frp proxy ports\|frp relay port\).*/\1/p' |
      sort -rn | while read -r _n; do ufw --force delete "$_n" >/dev/null || true; done ) || true
  for _s in $UFW_SPECS; do ufw allow "$_s" comment 'relayhub-pool' >/dev/null; done
  say "ufw 已放行 ${FRPS_PORT} 与端口池 ${POOL_TEXT}"
else
  say "ufw: 按 --no-ufw 跳过端口池放行(池 ${POOL_TEXT})"
fi

# ---------- 5. 启动 ----------
systemctl daemon-reload
systemctl enable --now relayhub-frps relayhub-panel >/dev/null 2>&1
sleep 2

say "--- 状态 ---"
systemctl is-active relayhub-frps relayhub-panel nginx
curl -s -m 5 "http://127.0.0.1:${DASH_PORT}/api/serverinfo" -u "relayhub:${DASH_PW}" | head -c 200; echo
echo
say "面板地址:   http://${PUB_IP}/relayhub/"
say "管理密码:   ${ADMIN_PW}"
say "frp token:  ${TOKEN}"
