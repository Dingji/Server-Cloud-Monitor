#!/usr/bin/env python3
"""RelayHub nginx 配置工具:向 opencode-relay 站点注入面板与 WS 路由(幂等)。
用法: python3 relayhub-nginx.py check|apply|remove
"""
import pathlib
import re
import subprocess
import sys

SITE = pathlib.Path("/etc/nginx/sites-available/opencode-relay")

PANEL_BLOCK = """    # ==== RelayHub panel (managed block, do not edit by hand) ====
    location = /relayhub { return 301 /relayhub/; }
    location /relayhub/ {
        limit_conn conn_panel 30;
        limit_req  zone=req_panel burst=40 nodelay;
        proxy_pass http://127.0.0.1:8100/;
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

"""

WS_BLOCK = """    # ==== RelayHub ws (managed block, do not edit by hand) ====
    # frpc(transport=websocket) 使用专用路径 /~!frp,经 80 端口接入本机 frps
    location = /~!frp {
        proxy_pass http://127.0.0.1:7000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
    # ==== end RelayHub ws ====

"""

MARKER_RE = re.compile(r"[ \t]*# relayhub-marker-\d+\s*")
# 原始默认 location 块(还原用)
WS_ORIGINAL = """    location / {
        default_type application/json;
        return 404 '{"error":"not found"}';
    }"""
# 旧版(rewrite 方式)的块,若存在则还原为原始默认块
WS_OLD_FULL_RE = re.compile(
    r"    location / \{\n"
    r"        # ==== RelayHub ws \(managed block, do not edit by hand\) ====\n"
    r".*?# ==== end RelayHub ws upstream ====\n",
    re.S,
)


def clean(text):
    # 去掉实验标记(可能粘在行尾)
    text = MARKER_RE.sub("", text)
    return text.rstrip() + "\n"


def apply(text):
    changed = False
    # 自愈: 默认 location / 块缺失(如被旧版 remove 误删)时,补回 server 块末尾
    if "    location / {" not in text:
        idx = text.rstrip().rfind("\n}")
        assert idx > 0, "server block ending not found"
        text = text.rstrip()[:idx + 1] + "\n" + WS_ORIGINAL + "}\n"
        changed = True
        print("default location restored")
    if "RelayHub panel" not in text:
        anchor = "    location / {"
        assert anchor in text, "panel anchor not found"
        text = text.replace(anchor, PANEL_BLOCK + anchor, 1)
        changed = True
        print("panel block inserted")
    if "RelayHub ws" not in text:
        anchor = "    location / {"
        assert anchor in text, "ws anchor not found"
        text = text.replace(anchor, WS_BLOCK + anchor, 1)
        changed = True
        print("ws block inserted")
    return text, changed


def remove(text):
    if "RelayHub panel" in text:
        text = PANEL_BLOCK_RE.sub("", text)
    text = WS_OLD_FULL_RE.sub(WS_ORIGINAL, text)
    text = WS_BLOCK_RE.sub("", text)
    return text.rstrip() + "\n", True


PANEL_BLOCK_RE = re.compile(re.escape(PANEL_BLOCK))
WS_BLOCK_RE = re.compile(re.escape(WS_BLOCK))


def nginx_ok():
    r = subprocess.run(["nginx", "-t"], capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        return False
    return True


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "check"
    text = clean(SITE.read_text())
    if action == "check":
        print("panel:", "RelayHub panel" in text, " ws:", "RelayHub ws upstream" in text)
        return
    if action == "apply":
        new, changed = apply(text)
    elif action == "remove":
        new, changed = remove(text)
    else:
        print("unknown action")
        return
    if not changed:
        print("nothing to do")
        return
    old_copy = SITE.read_text()
    SITE.write_text(new)
    if not nginx_ok():
        SITE.write_text(old_copy)  # 回滚
        print("nginx -t failed, rolled back")
        sys.exit(1)
    subprocess.run(["systemctl", "reload", "nginx"], check=True)
    print("applied & nginx reloaded")


if __name__ == "__main__":
    main()
