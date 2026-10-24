#!/usr/bin/env bash
# 从本机把安装包推到目标服务器并在远端执行 install.sh。
#
#   ./deploy-remote.sh root@203.0.113.9
#   ./deploy-remote.sh root@203.0.113.9 --nginx-standalone 8091
#   ./deploy-remote.sh deploy@203.0.113.9 --sudo --port 9100
#   SM_PASS='...' ./deploy-remote.sh root@203.0.113.9      # 仅口令登录（本机需装 sshpass）
#
# 目标机之后的参数原样转给 install.sh（--dry-run、--direct、--password … 都可以）。
# 上传走 tar-over-ssh，目标机不需要 rsync。
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ $# -ge 1 ] || { echo "用法：$0 user@host [install.sh 的参数…]" >&2; exit 1; }

TARGET="$1"; shift
SSH_PORT="${SM_PORT:-22}"
SSH_HOST="${TARGET#*@}"
REMOTE_DIR="/tmp/cloud-monitor-panel-$(date -u +%Y%m%d-%H%M%S)"
USE_PASS="${SM_PASS:-}"
SUDO=""
ARGS=()
for a in "$@"; do
  [ "$a" = "--sudo" ] && SUDO="sudo -n " || ARGS+=("$a")
done

if [ -n "$USE_PASS" ] && ! command -v sshpass >/dev/null 2>&1; then
  echo "指定了 SM_PASS 但本机没有 sshpass；请改用密钥登录或先装 sshpass" >&2
  exit 1
fi

SSH_COMMON=(-p "$SSH_PORT" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
# 非默认路径的私钥用 SM_KEY 指定（ssh 只会自动试 id_* 那几个名字）
if [ -n "${SM_KEY:-}" ]; then
  [ -f "$SM_KEY" ] || { echo "SM_KEY 指向的文件不存在：$SM_KEY" >&2; exit 1; }
  SSH_COMMON+=(-i "$SM_KEY" -o IdentitiesOnly=yes)
fi
if [ -n "$USE_PASS" ]; then
  SSH=(sshpass -p "$USE_PASS" ssh "${SSH_COMMON[@]}" -o PreferredAuthentications=password -o PubkeyAuthentication=no)
else
  SSH=(ssh "${SSH_COMMON[@]}" -o BatchMode=yes)
fi

[ -d "$HERE/app/server" ] || { echo "包不完整：$HERE/app/server 不存在（先跑 tools/build_panel_package.py）" >&2; exit 1; }
[ -f "$HERE/install.sh" ] || { echo "包不完整：缺 install.sh" >&2; exit 1; }

q() { printf '%q' "$1"; }

echo "==> 目标 ${TARGET} (ssh ${SSH_PORT}) · 远端暂存 ${REMOTE_DIR}"
if [ ${#ARGS[@]} -eq 0 ]; then
  echo "==> 转给 install.sh 的参数：（无，全默认）"
else
  printf '==> 转给 install.sh 的参数：'; printf ' %q' "${ARGS[@]}"; printf '\n'
fi

"${SSH[@]}" "$TARGET" "echo '    连通 OK'; command -v tar >/dev/null || { echo '    远端缺 tar' >&2; exit 3; }" \
  || { echo "连不上 ${TARGET}（口令/密钥/端口 22 检查一下）" >&2; exit 1; }

echo "==> 上传 app + vendor + 脚本（约 4 MB）"
tar -C "$HERE" -czf - app vendor install.sh uninstall.sh README.md MANIFEST.remote-md5.txt \
  | "${SSH[@]}" "$TARGET" "mkdir -p $(q "$REMOTE_DIR") && tar -xzf - -C $(q "$REMOTE_DIR") && echo '    上传完成'" \
  || { echo "上传失败（远端 ssh 提前退出/磁盘满/没有解压权限，都可能是这个报错）" >&2; exit 1; }

CMD="cd $(q "$REMOTE_DIR") && ${SUDO}bash ./install.sh"
if [ ${#ARGS[@]} -gt 0 ]; then
  for a in "${ARGS[@]}"; do CMD="$CMD $(q "$a")"; done
fi
echo "==> 远端执行：${CMD}"

RC=0
if [ -z "$USE_PASS" ] && [ -t 0 ]; then
  "${SSH[@]}" -t "$TARGET" "$CMD" || RC=$?
else
  "${SSH[@]}" "$TARGET" "$CMD" || RC=$?
fi

echo "==> 远端暂存留在 ${REMOTE_DIR}（升级/排查用），确认无误后可：ssh ${TARGET} rm -rf ${REMOTE_DIR}"
exit $RC
