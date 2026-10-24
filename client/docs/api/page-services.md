# page-services — 系统服务（services）

## 概览

- `registerPage({ id:'services', title:'系统服务' })`（`upstream/public/js/pages/services.js:L7-L9`）。
- **REST 轮询**：`CM.autoRefresh(load, { pageId:'services' })`（L73），间隔 = `max(500, CM.me.refreshMs)`；`show()`/`hide()` 启停（L78-L83）。
- **本地过滤**：拉一次 `/services` 后缓存在 `cache`，搜索/状态筛选只在前端过滤（`render()` L33-L39、防抖 160ms L69）—— 与 processes 页不同（那页走服务端 q）。

## 接口调用

### GET /services（每次轮询/刷新）
- 触发：mount（L72）、`刷新`（L67）、自动刷新（L73）、动作完成后（`runAction(..., {reload: load})` L61，失败也 load L62）。
- 响应（样本 `api-dump/services.json`，本机 224 个单元）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `ts` | number(ms) | 采集时间 |
| `list[]` | array | 服务列表，元素见下 |
| `counts` | `{running,failed,total}` | 统计徽章数据 |

`list[]` 元素（`collectors.js:L365`）：`unit:string`（如 `nginx.service`）、`load:string`（`loaded`/`not-found`…）、`active:string`（`active`/`inactive`/`failed`/`activating`…）、`sub:string`（`running`/`dead`…）、`description:string`（截 160 字）、`enabled:string`（`enabled`/`disabled`/`static`/`indirect`，取自 list-unit-files，缺失为 `-`）。

### POST /service（动作）
- 请求体：`{unit, action}`，`action` ∈ `start|stop|restart|enable|disable|reload|mask|unmask`（`actions.js:L42-L53`）。
- 成功：`{ok:true, output:"<命令输出>", state:"<输出最后一行>"}`。
- 错误：400 `{"error":"无效的服务单元名"}` / `{"error":"无效操作: <action>"}`；命令失败 400 `{error:"命令失败 (exit N):\n…", output:"…"}`。
- 审计名 `service.action`（`index.js:L224`）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| 搜索框 `搜索服务名…`（L16） | 无请求（本地过滤 `unit+description`，160ms 防抖） | – |
| 状态下拉 `全部状态` / `仅运行` / `仅失败` / `仅停止`（L17） | 无请求（本地过滤 `active === state`） | – |
| `自动刷新` 复选框（L18） | 无 | – |
| `刷新`（L20） | `GET /services` | – |
| 行内 `启动` / `重启` / `停止`（L50-L52） | `modal.confirm('<action> 服务', '对 <b class="mono">{unit}</b> 执行 <b>systemctl {action}</b>？', {danger: action==='stop'})` → `POST /service {unit, action}`（L56-L64） | 成功 toast `r.output`，700ms 后 reload；停止为红色确认 |
| 行内 `开自启` / `关自启`（L53，按当前 `enabled==='enabled'` 切换） | 同上，action = `enable` / `disable` | – |

## UI 分区

单卡片「systemd 服务」（L12-L24）：

- 标题右侧：徽章 `运行 N`(ok)、`失败 N`(bad，为 0 不显示)、`共 N`(info)（L40-L43）；搜索框、状态下拉、`自动刷新`、间隔文本、`刷新`。
- 表格列（L44-L55）：`单元`(mono)、`状态`（`ui.svcBadge(active, sub)` —— active/sub 组合着色）、`开机启动`（`enabled`→绿徽章、`static`→灰徽章、其它→黄徽章含 `-`）、`描述`（截 70 字）、`操作`（启动/重启/停止/开自启或关自启 四个按钮）。
- **行数上限 400**（`list.slice(0,400)`，L55）。
- 错误态：整表显示 `e.message`（L30）。

## 实现提示与边界

- 搜索/筛选是纯前端的：KMP 端可一次性拿全量再本地过滤（注意服务端返回 224+ 行，渲染建议 lazy 列表）。
- `ui.svcBadge` 规则见 `upstream/public/js/ui.js`（active=active→ok、failed→bad、inactive→gray、其它→warn；sub=running 加亮）。复刻时按此着色。
- 确认弹窗标题用 `act + ' 服务'`（如 `stop 服务`、`enable 服务`），正文含 `systemctl <act>` 字样 —— 文案照抄。
- 动作后 `runAction` 的 `reload: load` 延迟 700ms（core.js L397 `reloadDelay` 默认 700），给 systemctl 生效时间。
- `mask`/`unmask` 后端支持但**页面未暴露按钮**；复刻按页面口径（不出这两个按钮）。
