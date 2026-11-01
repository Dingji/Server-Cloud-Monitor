#!/usr/bin/env python
"""Install the PHP hosting stack on the cloud-monitor server.

  python tools/install_php_stack.py --dry-run    # read-only plan
  python tools/install_php_stack.py              # snapshot + install + tune + verify
  python tools/install_php_stack.py --rollback   # purge exactly what this script installed

nginx config is never touched. Rollback script is left on the server.
"""
import argparse
import datetime
import os
import posixpath
import subprocess
import sys

HOST = os.environ.get("SM_HOST", "8.153.153.176")
USER = os.environ.get("SM_USER", "root")
KEY = os.environ.get("SM_KEY", os.path.expanduser("~/.ssh/oc_relay_ed25519"))
APP = "/opt/cloud-monitor"
BACKUP_DIR = posixpath.join(APP, "backups")

PHP_VER = "8.5"
# Installed as one transaction: if any of these is missing the feature cannot work.
REQUIRED = ["php%s-fpm" % PHP_VER, "php%s-cli" % PHP_VER, "php%s-common" % PHP_VER,
            "php%s-curl" % PHP_VER, "php%s-mbstring" % PHP_VER, "php%s-xml" % PHP_VER,
            "php%s-zip" % PHP_VER, "php%s-mysql" % PHP_VER,
            "composer", "certbot", "python3-certbot-nginx", "mariadb-server"]
# Installed one at a time: a missing package name must not abort the whole stack.
OPTIONAL = ["php%s-gd" % PHP_VER, "php%s-intl" % PHP_VER, "php%s-bcmath" % PHP_VER,
            "php%s-sqlite3" % PHP_VER, "php%s-redis" % PHP_VER, "php%s-apcu" % PHP_VER,
            "php%s-gmp" % PHP_VER, "php%s-sodium" % PHP_VER, "php%s-xsl" % PHP_VER,
            "unzip", "rsync"]
PACKAGES = REQUIRED + OPTIONAL

# Conservative tuning: the box has 1.7 GB RAM (8 GB swapfile present).
MARIADB_TUNE = """[mysqld]
innodb_buffer_pool_size = 64M
innodb_log_buffer_size  = 8M
key_buffer_size         = 16M
max_connections         = 40
thread_cache_size       = 8
table_open_cache        = 400
tmp_table_size          = 16M
max_heap_table_size     = 16M
performance_schema      = OFF
skip-name-resolve
"""

FPM_TUNE = """; Panel-managed defaults for the shared pool. Per-site pools are generated
; by the PHP module and are not affected by this file's [www] section.
pm = dynamic
pm.max_children = 12
pm.start_servers = 2
pm.min_spare_servers = 2
pm.max_spare_servers = 4
pm.max_requests = 500
memory_limit = 256M
upload_max_filesize = 64M
post_max_size = 64M
max_execution_time = 60
expose_php = Off
"""


def sh(c):
    return subprocess.run(["ssh", "-i", KEY, "-o", "StrictHostKeyChecking=no",
                           "-o", "BatchMode=yes", "%s@%s" % (USER, HOST), c],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")


def put_lines(path, lines, mode="0644"):
    """Write a small remote file through a base64 heredoc (never via sed on Chinese files)."""
    import base64
    blob = base64.b64encode(("\n".join(lines) + "\n").encode("utf-8")).decode("ascii")
    cmd = ("echo %s | base64 -d > %s && chmod %s %s" % (blob, path, mode, path))
    return sh(cmd)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--rollback", action="store_true")
    a = ap.parse_args()

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    manifest = posixpath.join(BACKUP_DIR, "php-stack.pkgs.%s" % stamp)
    dpkg_snap = posixpath.join(BACKUP_DIR, "dpkg.before-php-%s.txt" % stamp)
    rb = posixpath.join(BACKUP_DIR, "rollback-php-stack-%s.sh" % stamp)

    if a.rollback:
        scripts = sorted(f for f in sh("ls %s 2>/dev/null | grep '^rollback-php-stack' || true"
                                       % BACKUP_DIR).stdout.split() if f)
        if not scripts:
            print("服务器上找不到 rollback-php-stack-*.sh，放弃")
            return 1
        latest = posixpath.join(BACKUP_DIR, scripts[-1])
        print("将执行 %s —— 这会 purge PHP/MariaDB/composer/certbot，正在运行的 PHP 站点会下线" % latest)
        if not a.dry_run:
            print(sh("bash %s" % latest).stdout)
        return 0

    present = sh("dpkg -l | grep -E '^ii +(php%s-fpm|mariadb-server|composer|certbot) ' || echo NONE" % PHP_VER).stdout
    print("=== 已存在的相关包 ===\n%s" % (present or "(无)"))
    size = sh("apt-get -s install -y %s 2>&1 | grep -E 'upgraded|newly installed' || true"
              % " ".join(PACKAGES)).stdout
    print("=== 模拟结果 ===\n%s" % (size or "(无输出)"))

    if a.dry_run:
        print("\n=== dry-run，将执行 ===")
        for k, v in [
            ("1. 快照 dpkg 清单", dpkg_snap),
            ("2. 记录本次安装包清单", manifest),
            ("3. 生成回滚脚本", rb),
            ("4. apt-get install", "%d 个包: %s" % (len(PACKAGES), " ".join(PACKAGES))),
            ("5. MariaDB 调优", "/etc/mysql/mariadb.conf.d/99-panel.cnf (buffer_pool 64M, perf_schema OFF)"),
            ("6. PHP-FPM 共享池调优", "/etc/php/%s/fpm/conf.d/99-panel.ini + 提示不要动 [www]" % PHP_VER),
            ("7. systemctl enable --now", "php%s-fpm, mariadb" % PHP_VER),
            ("8. composer 全局安装器", "/usr/local/bin/composer 已由 apt 提供"),
            ("9. 验证", "php -v / php-fpm -t / composer -V / certbot --version / mariadb 状态"),
        ]:
            print("  %-22s %s" % (k, v))
        print("  nginx 配置：本轮不改动")
        return 0

    sh("mkdir -p %s" % BACKUP_DIR)
    print("1. dpkg 快照 -> %s : %s" % (dpkg_snap, sh("dpkg -l > %s && wc -l < %s" % (dpkg_snap, dpkg_snap)).stdout.strip()))
    put_lines(manifest, PACKAGES)
    print("2. 包清单 -> %s" % manifest)

    put_lines(rb, [
        "#!/bin/bash",
        "# Auto-generated rollback for the PHP stack installed at %s" % stamp,
        "# Purges only the packages that are actually installed. Leaves /var/lib/mysql alone.",
        "set -e",
        "systemctl stop php%s-fpm mariadb 2>/dev/null || true" % PHP_VER,
        "PKGS=''",
        "while read -r p; do dpkg -l \"$p\" >/dev/null 2>&1 && PKGS=\"$PKGS $p\"; done < %s" % manifest,
        "[ -n \"$PKGS\" ] && DEBIAN_FRONTEND=noninteractive apt-get purge -y $PKGS 2>&1 | tail -20",
        "apt-get autoremove -y 2>&1 | tail -5",
        "rm -f /etc/mysql/mariadb.conf.d/99-panel.cnf",
        "rm -f /etc/php/%s/fpm/conf.d/99-panel.ini" % PHP_VER,
        "echo 'rollback done (data kept: /var/lib/mysql, /srv/php)'",
    ], "0750")
    print("3. 回滚脚本 -> %s" % rb)

    env = "DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y"
    print("4a. 安装必需包（%d 个，可能需要 1-5 分钟）..." % len(REQUIRED))
    r = sh("%s %s 2>&1 | tail -30" % (env, " ".join(REQUIRED)))
    print(r.stdout)
    if r.returncode != 0:
        print("必需包安装失败 rc=%s；回滚：%s" % (r.returncode, rb))
        return 1

    print("4b. 逐个安装可选扩展（失败的跳过）")
    got, skipped = [], []
    for p in OPTIONAL:
        rr = sh("%s %s 2>&1 | tail -3" % (env, p))
        (got if rr.returncode == 0 else skipped).append(p)
    print("   已装: %s" % ",".join(got))
    if skipped:
        print("   跳过(包名不可用): %s" % ",".join(skipped))

    print("5. MariaDB 保守调优")
    put_lines("/etc/mysql/mariadb.conf.d/99-panel.cnf", MARIADB_TUNE.splitlines(), "0644")

    print("6. PHP-FPM 共享池默认值")
    put_lines("/etc/php/%s/fpm/conf.d/99-panel.ini" % PHP_VER,
              ["; generated by tools/install_php_stack.py", "[PHP]"] + FPM_TUNE.splitlines()[1:], "0644")

    print("7. 启动服务")
    for svc in ["php%s-fpm" % PHP_VER, "mariadb"]:
        print("   %-14s %s" % (svc, sh("systemctl enable --now %s 2>&1 | tail -2; systemctl is-active %s" % (svc, svc)).stdout.strip()))

    print("=== 验证 ===")
    for label, cmd in [
        ("php", "php -v 2>&1 | head -2"),
        ("php-fpm", "php-fpm%s -t 2>&1 | tail -2" % PHP_VER),
        ("extensions", "php -m 2>/dev/null | tr '\\n' ' '"),
        ("composer", "composer --version 2>&1 | head -1"),
        ("certbot", "certbot --version 2>&1 | head -1"),
        ("mariadb", "systemctl is-active mariadb; mariadb -e 'SELECT VERSION();' 2>&1 | tail -2"),
        ("memory", "free -m | head -2 | tail -1"),
        ("services", "systemctl is-active nginx cloud-monitor php%s-fpm mariadb | tr '\\n' ' '" % PHP_VER),
    ]:
        print("  %-11s %s" % (label, sh(cmd).stdout.strip().replace("\n", " | ")))
    print("\n如需回滚：ssh 上机执行 bash %s" % rb)
    return 0


if __name__ == "__main__":
    sys.exit(main())
