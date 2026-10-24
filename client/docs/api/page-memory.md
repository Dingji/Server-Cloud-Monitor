# page-memory — 内存管理（memory）

## 概览

- `registerPage({ id:'memory', title:'内存管理' })`（`upstream/public/js/pages/memory.js:L6-L8`）。
- **REST 轮询** `CM.autoRefresh(load, {pageId:'memory'})`（L100），间隔 = `max(500, CM.me.refreshMs)`；`show()/hide()` 启停（L105-L110）。
- 页面复用「进程」数据：`/memory` 响应内嵌一份完整 `metrics` 对象 + RSS Top15。

## 接口调用

### GET /memory（轮询/刷新）
- 触发：mount（L98）、`刷新`（L85）、自动刷新（L100）。
- 响应（样本 `api-dump/memory.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `metrics` | object | 与 `GET /metrics` 完全相同（`ts/cpu/memory/net/diskio`，字段表见 `page-dashboard.md` 响应节） |
| `swappiness` | number | `/proc/sys/vm/swappiness` 当前值 |
| `topRss[]` | array | RSS 最大的 15 个进程（元素 = `/processes` 的 list 元素：`pid,ppid,user,cpu,mem,rss,vsz,stat,etime,args`，`collectors.js:L339`） |
| `procTotal` | number | 进程总数 |

### POST /security/action（设置 swappiness）
- 请求体：`{action:"sysctl-set", params:{key:"vm.swappiness", value:"<用户输入>"}}`（`memory.js:L90`）。
- 服务端（`actions.js:L131-L141`）：校验键存在 → `sysctl -w` + 追加/替换 `/etc/sysctl.d/99-cloud-monitor.conf`；成功 `{ok:true, output:"…\n已持久化到 /etc/sysctl.d/99-cloud-monitor.conf"}`；键不存在 400 `{"error":"sysctl 键不存在: vm.swappiness"}`；非法键/值 400。审计名 `security.action`。

### POST /disk/action（释放缓存）
- 请求体：`{action:"drop-caches"}`（`memory.js:L95`）→ `sync; echo 3 > /proc/sys/vm/drop_caches; echo 缓存已释放`（`actions.js:L64`）；成功 `{ok:true, output:"缓存已释放"}`。审计名 `disk.action`。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `自动刷新`（L20）/ `刷新`（L22） | `GET /memory` | – |
| `应用`（Swap 控制卡，L35/L86-L91） | 确认框 `应用 swappiness` / `将 vm.swappiness 设置为 <b>{v}</b> 并持久化？` → `POST /security/action {action:'sysctl-set', params:{key:'vm.swappiness', value:v}}` | `runAction` 默认 toast `r.output`；**不自动刷新页面**（无 reload 选项） |
| `释放缓存 (drop_caches)`（L42/L92-L96，danger） | 确认框 `释放内存缓存` / `将执行 <code>drop_caches=3</code>，系统可能短暂变慢。继续？` → `POST /disk/action {action:'drop-caches'}` | – |

swappiness 输入框只在**首次**填充当前值（`swappinessFilled` 标志，L76-L80），用户编辑不会被轮询覆盖。

## UI 分区

1. **统计三联卡**（`grid g3`，L11/L50-L53）：`物理内存`（`usedPct` 1 位小数 + 进度条，副 `used / total 可用 available`）、`交换分区`（`swapPct` + 进度条，副 `swapUsed / swapTotal`）、`缓存 / 缓冲`（`cached+buffers`，副 `cached … · buffers …`）。
2. **内存详情 (/proc/meminfo) 卡**（L13-L16、L55-L66）：键值对依次为 `MemTotal`、`MemFree`、`MemAvailable`、`Buffers`、`Cached`、`SReclaimable`、`Shmem`、`Dirty (待写盘)`、`SwapTotal`、`SwapFree`（= swapTotal−swapUsed，**不是直接字段**）、`进程总数`（= `procTotal`）。
3. **内存占用 Top 15 (RSS) 卡**（L17-L26、表 L68-L74）：列 `PID`(num)、`用户`、`RSS`(num, fmt.bytes)、`MEM%`(num)、`进程`(mono，截 46 字 + title 全文)；标题右侧放 `自动刷新`、间隔文本、`刷新`。
4. **Swap 控制卡**（L29-L38）：`vm.swappiness (0-100)` 数字输入（0–100）+ `应用` 主按钮 + 说明 `值越小越倾向使用物理内存；数据库服务器常用 10。`
5. **内存维护卡**（L39-L45）：`释放缓存 (drop_caches)` 红按钮 + 说明 `清空页缓存、目录项与 inode 缓存，内存紧张时可临时回收。`

## 实现提示与边界

- 内存卡的“已用”口径是 `usedPct`（(total−available−buffers)/total），与仪表盘卡片同口径；而 sparkline/告警用 `actualUsedPct`。两个口径并存，别混。
- `SwapFree` 是前端算出来的（L65），`/memory` 响应里没有该字段。
- 本页两个动作都不带 `reload`，动作成功后页面数据要等下一拍轮询才更新（原版行为）。
- `topRss` 直接用 `d.topRss`（已按 RSS 降序），无需再排序。
