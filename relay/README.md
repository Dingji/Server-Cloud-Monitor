# OpenCode 中继服务端

`/opt/relay` 与 `/etc/relay` 上那套中继服务的全部承重文件，此前只存在于运行中的服务器上。
这里收的是**能跑的那一份**：字节与线上一致（逐文件 md5 比对过），不是重写或简化版。

面板的 *OpenCode 中继* 页、`relay.js` 的忙时策略页签和 Compose 客户端读到的
`phase` / `calendar` 字段，都由 `server/collector.py` 产出。

## 目录与部署位置

| 仓库路径 | 部署到 | 说明 |
| --- | --- | --- |
| `server/usagetap.py` | `/opt/relay/usagetap.py` | `relay-usagetap.service`，:8790。**在请求链路上**：改写与白名单在这里发生 |
| `server/collector.py` | `/opt/relay/collector.py` | `relay-stats.service`，:8787。统计 API + 忙时策略读写 |
| `server/keystore.py` | `/opt/relay/keystore.py` | collector 的硬依赖（多密钥、配额感知选键） |
| `server/relay_guard.py` | `/opt/relay/relay_guard.py` | `relay-guard.service`，完整性与流式金丝雀自检 |
| `server/selfheal.py` | `/opt/relay/selfheal.py` | `relay-selfheal.service` |
| `server/modelcatalog.py` `modelcheck.py` `daily_report.py` `relay_alert.py` | `/opt/relay/` | 各自的 timer 驱动 |
| `server/{db,disk}_guard.sh` `disk_hygiene.sh` `backup_relay.sh` `backup_verify.sh` | `/opt/relay/` | 备份与水位守护 |
| `nginx/opencode-relay` | `/etc/nginx/sites-available/opencode-relay` | 以 nginx 实际生效的那份为准（**不是** `/opt/relay/opencode-relay`，那份是 222 行的陈旧副本） |
| `nginx/{opencode,glm,mimo}-upstream.conf` | `/opt/relay/` | 上游定义 |
| `systemd/relay-*.service` `relay-*.timer` | `/etc/systemd/system/` | 13 个服务 + 11 个 timer |
| `config/busy-hours.json` | `/etc/relay/busy-hours.json` | 忙时策略；tap 按 mtime 自动重载，改它不需要重启 |
| `config/go-allowlist.json` | `/etc/relay/go-allowlist.json` | Go 套餐可用模型白名单，缺失即 fail-closed 403 |

## 这里刻意没有的东西

**`/etc/relay/upstream-auth.conf` 不入库。** 它被 nginx `include` 三次，内容是
`proxy_set_header ... "Bearer <真 token>"` —— 上游凭据。新机器必须自己创建它，否则
nginx 起不来。同理不入库的还有 `/etc/nginx/.htpasswd-relay`（`/stats/` 的 basic auth）。

`install_tap*.sh`、`install_timer_and_verify.sh`、`retention_tune.py`、`patch_*.py`
这些一次性迁移与验证脚本也不入库：它们的 curl 行里嵌着真 token，且只对当次变更有意义。

## golden 与守护

`relay_guard.py` 会在检测到篡改时复原 `/opt/relay/golden/` 下的
`usagetap.py`、`opencode-relay.nginx`、`go-allowlist.json` 三份，用
`/opt/relay/update_golden.sh` 刷新基准。**改 `usagetap.py` 或 nginx 站点后必须刷 golden**，
否则 180 秒的 `recently_edited` 保护窗口一过就被复原；`collector.py` 不在守护名单内。

## 忙时策略的判定归属

窗口是否生效只有一处答案：`usagetap.py` 的 `busy_window_active()`。「仅工作日」走
`chinese_calendar.is_workday()`，因此认得法定节假日与周末调休补班；库覆盖 2004–2026，
超出范围的年份回落成周一到周五（会把该年法定假日当工作日）。

`collector.py` 的 `busy_state()` 把同一份判定连同 `calendar`（与周一到周五不一致的日子）
和 `phase`（忙时/闲时及原因）一起下发，网页与客户端只渲染、**不各自再判一遍**。
临时停用是标记文件 `/var/lib/relay/busy-hours.off`，不动配置本身。

## 还差一步

`install.sh` 目前覆盖面板、`--with-php`、`--with-relayhub`，**还没有 `--with-relay`**：
新机器 clone 之后能拿到这里的全部文件，但需要照上表手工放置并 `systemctl enable --now`。
