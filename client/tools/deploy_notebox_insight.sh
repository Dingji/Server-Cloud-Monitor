#!/usr/bin/env bash
# 部署「笔记与待办 + 内网中继洞察」到公网面板。
#
#   bash tools/deploy_notebox_insight.sh --dry     # 只比对、不落盘
#   bash tools/deploy_notebox_insight.sh --apply   # 备份 -> 校验语法 -> 安装 -> 重启 -> 探测
#   bash tools/deploy_notebox_insight.sh --rollback <stamp>
#
# 备份命名：<原路径>.bak.nb.<stamp>，回滚就是把这一组原样放回去。
set -u

HOST=root@8.153.153.176
KEY="$HOME/.ssh/panel_ed25519"
APP=/opt/cloud-monitor
SSH="ssh -i $KEY -o StrictHostKeyChecking=no -o ConnectTimeout=15 $HOST"
SCP="scp -q -i $KEY -o StrictHostKeyChecking=no"
STAMP=${DEPLOY_STAMP:-$($SSH "TZ=Asia/Shanghai date +%Y%m%d-%H%M%S" 2>/dev/null | tail -1)}
[ -n "$STAMP" ] || die "取不到服务器时间做备份戳"

# 本地源 -> 远端目标（相对 $APP）
SRCS=(
  "panel-server/index.js"
  "panel-server/notebox.js"
  "panel-server/relayhubmon.js"
  "ops-server/alerting.js"
  "panel-web/app.js"
  "panel-web/index.html"
  "panel-web/notebox.js"
  "panel-web/relayhub.js"
  "upstream/public/js/core.js"
  "upstream/public/js/md.js"
)
DSTS=(
  "server/index.js"
  "server/notebox.js"
  "server/relayhubmon.js"
  "server/ops/alerting.js"
  "public/js/app.js"
  "public/index.html"
  "public/js/pages/notebox.js"
  "public/js/pages/relayhub.js"
  "public/js/core.js"
  "public/js/md.js"
)

die() { echo "FAIL: $*" >&2; exit 1; }

if [ "${1:-}" = "--rollback" ]; then
  [ -n "${2:-}" ] || die "用法: --rollback <stamp>"
  echo "== 回滚到 stamp $2 =="
  for d in "${DSTS[@]}"; do
    $SSH "test -f '$APP/$d.bak.nb.$2' && cp -a '$APP/$d.bak.nb.$2' '$APP/$d' && echo restored $d || echo skip $d"
  done
  $SSH "systemctl restart cloud-monitor && sleep 3 && systemctl is-active cloud-monitor"
  exit 0
fi

MODE=${1:-}
case "$MODE" in --dry|--apply) ;; *) die "用法: --dry | --apply | --rollback <stamp>" ;; esac

echo "== 模式 $MODE / stamp $STAMP =="
echo "-- 本地语法 --"
for s in "${SRCS[@]}"; do
  case "$s" in
    panel-web/index.html) test -f "$s" || die "缺文件 $s"; echo "ok(html) $s" ;;
    *) node --check "$s" >/dev/null 2>&1 || die "语法不通过 $s"; echo "ok(js) $s" ;;
  esac
done

echo "-- 远端当前状态 --"
for d in "${DSTS[@]}"; do
  $SSH "if [ -f '$APP/$d' ]; then md5sum '$APP/$d'; else echo 'ABSENT $APP/$d'; fi" || die "远端不可达"
done

if [ "$MODE" = "--dry" ]; then
  echo "== dry 结束，没有写入 =="
  exit 0
fi

echo "-- 备份 --"
for d in "${DSTS[@]}"; do
  $SSH "if [ -f '$APP/$d' ]; then cp -a '$APP/$d' '$APP/$d.bak.nb.$STAMP' && echo 'bak $d'; else echo 'new $d (无需备份)'; fi"
done
$SSH "ls -1 $APP/server/*.bak.nb.$STAMP $APP/public/js/*.bak.nb.$STAMP 2>/dev/null | wc -l" | sed 's/^/备份份数: /'

echo "-- 上传到 /var/tmp 并按真实后缀校验语法 --"
i=0
while [ $i -lt ${#SRCS[@]} ]; do
  s=${SRCS[$i]}; d=${DSTS[$i]}
  base=$(basename "$d")
  ext="${base##*.}"
  tmp="/var/tmp/nb.$STAMP.$base"
  $SCP "$s" "$HOST:$tmp" || die "上传失败 $s"
  if [ "$ext" = "js" ]; then
    $SSH "node --check '$tmp'" || die "远端语法校验失败 $d"
  fi
  $SSH "install -d \$(dirname '$APP/$d') && mv '$tmp' '$APP/$d' && echo 'installed $d'" || die "安装失败 $d"
  i=$((i + 1))
done

echo "-- 重启 cloud-monitor --"
$SSH "systemctl restart cloud-monitor; sleep 3; systemctl is-active cloud-monitor" || die "服务没起来"

echo "-- 探测 --"
$SSH "journalctl -u cloud-monitor --no-pager -n 15 --since '-40 sec'" | tail -15
$SSH "curl -s -o /dev/null -w 'GET /panel/api/notebox/probe -> %{http_code}\n' http://127.0.0.1:9000/panel/api/notebox/probe || true"
$SSH "curl -s -o /dev/null -w 'GET /panel/api/relayhub/insight -> %{http_code}\n' http://127.0.0.1:9000/panel/api/relayhub/insight || true"
$SSH "curl -s -o /dev/null -w 'GET /panel/public/js/pages/notebox.js -> %{http_code}\n' http://127.0.0.1:9000/panel/public/js/pages/notebox.js || true"
$SSH "grep -c relayhubmon $APP/server/ops/alerting.js | sed 's/^/alerting.js 引用 relayhubmon 次数: /'"
echo "== 完成，回滚口令: bash tools/deploy_notebox_insight.sh --rollback $STAMP =="
