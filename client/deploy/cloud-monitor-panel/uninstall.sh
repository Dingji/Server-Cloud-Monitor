#!/usr/bin/env bash
# 卸载 Cloud Monitor 面板。默认保留代码与数据，只摘掉服务与 nginx。
#   ./uninstall.sh                # 停服务 + 删单元 + 删本脚本生成的 nginx 文件
#   ./uninstall.sh --purge-app    # 再删安装目录（默认 /opt/cloud-monitor）
#   ./uninstall.sh --purge-data   # 再删数据目录（默认 /var/lib/cloud-panel）
#   ./uninstall.sh --yes          # 不询问
set -Eeuo pipefail

SERVICE=cloud-monitor
PREFIX=/opt/cloud-monitor
DATA_DIR=/var/lib/cloud-panel
ENV_FILE="/etc/${SERVICE}.env"
UNIT_FILE="/etc/systemd/system/${SERVICE}.service"
NGINX_SNIPPET="/etc/nginx/snippets/${SERVICE}-location.conf"
NGINX_CONF="/etc/nginx/conf.d/${SERVICE}.conf"
PURGE_APP=0
PURGE_DATA=0
YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --service)     SERVICE="$2"; ENV_FILE="/etc/${SERVICE}.env"; UNIT_FILE="/etc/systemd/system/${SERVICE}.service"
                   NGINX_SNIPPET="/etc/nginx/snippets/${SERVICE}-location.conf"
                   NGINX_CONF="/etc/nginx/conf.d/${SERVICE}.conf"; shift 2 ;;
    --prefix)      PREFIX="$2"; shift 2 ;;
    --data-dir)    DATA_DIR="$2"; shift 2 ;;
    --purge-app)   PURGE_APP=1; shift ;;
    --purge-data)  PURGE_DATA=1; shift ;;
    --yes|-y)      YES=1; shift ;;
    -h|--help)     sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

[ "$(id -u)" = 0 ] || { echo "需要 root" >&2; exit 1; }

echo "将要执行："
echo "  systemctl disable --now ${SERVICE}（若存在）"
echo "  删除 ${UNIT_FILE} 与 ${ENV_FILE}"
echo "  删除 nginx 片段 ${NGINX_SNIPPET} / ${NGINX_CONF}（仅本脚本生成的这两份）"
[ "$PURGE_APP" = 1 ] && echo "  删除安装目录 ${PREFIX}"
[ "$PURGE_DATA" = 1 ] && echo "  删除数据目录 ${DATA_DIR}（拨测/规则/巡检历史/回收站全部丢失）"
echo "  注意：如果你把 ${NGINX_SNIPPET} include 进了站点，卸载后请自行删掉那行 include"

if [ "$YES" != 1 ]; then
  printf '确认执行？输入 yes 继续： '
  read -r ans
  [ "$ans" = yes ] || { echo "已取消"; exit 0; }
fi

if systemctl list-unit-files 2>/dev/null | grep -q "^${SERVICE}.service"; then
  systemctl disable --now "$SERVICE" || true
fi
rm -f "$UNIT_FILE" "$ENV_FILE"
systemctl daemon-reload || true
rm -f "$NGINX_SNIPPET"
CONF_REMOVED=0
if [ -f "$NGINX_CONF" ]; then rm -f "$NGINX_CONF"; CONF_REMOVED=1; fi
if command -v nginx >/dev/null 2>&1; then
  if nginx -t >/dev/null 2>&1; then
    [ "$CONF_REMOVED" = 1 ] && nginx -s reload || true
  else
    echo "!! nginx -t 未通过（可能站点里还 include 了刚删掉的片段），已不 reload，请手工检查" >&2
  fi
fi
[ "$PURGE_APP" = 1 ] && rm -rf "$PREFIX"
[ "$PURGE_DATA" = 1 ] && rm -rf "$DATA_DIR"
echo "完成。"
