# page-databases — 数据库（databases）

> 本页是复刻难点之最：前端 2700 行、`dbadmin.js` 1787 行、`dbmulti.js` 2023 行，覆盖 **6 种引擎 × 9 个页签**。所有请求体字段来自 `databases.js` 实际调用，响应字段来自 `dbadmin.js`/`dbmulti.js` 源码与 `api-dump` 样本。

## 概览

- `registerPage({ id:'databases', title:'数据库', desc:'PostgreSQL / MySQL / SQLite / Db2 / Redis / MongoDB 的库表管理、SQL 控制台、性能洞察与备份恢复' })`（`databases.js:L82-L85`）。
- **无自动轮询**：进入页签才加载；动作完成后 `renderTab()` 或 `CM.api.invalidate()` 后重取。
- 引擎常量：`ENGINE_LABEL`（L5-L8）、`ENGINE_KIND`（sql/kv/doc，L9）、`ENGINE_ORDER = [postgres, mysql, sqlite, db2, redis, mongo]`（L10）。
- 页面状态 `S`（L35-L42）：`engine/tab/database/table/page/pageSize(50)/orderBy/orderDir/where/schema/tableView/kvKey/kvPattern/kvDb('0')/docDb/docColl`；能力标志 `isPg/isMy/isSqlite/isDb2/isSql`（L45-L52）。

### 引擎发现（mount，L86-L101）

1. `GET /databases`（缓存 10s）→ 采集器探测结果 `{ts, engines:[{id,name,installed,service,active,version,databases,stats}], bins}`（样本 `api-dump/databases.json`）。
2. `GET /db/engines`（缓存 10s）→ `{engines:[{id,name,kind,installed,active,service,packages[],needsRepo?}]}`（本机 4 项 sqlite/db2/redis/mongo；样本 `api-dump/db_engines.json`）。
3. `buildEngineList()`（L56-L67）合并两个来源、过滤 `installed=false`、按 `ENGINE_ORDER` 排序 → `S.engines`。
4. 无任何引擎 → `renderInstallAll()`（L105-L142）安装引导页。
5. 有引擎 → `renderShell()`（引擎切换条 + 页签条）+ `renderTab()`。

### 页签集合（按引擎 kind，L198-L201）

- **SQL 类**（postgres/mysql/sqlite/db2）：`总览与监控`(overview)、`数据库管理`(databases)、`表与数据`(tables)、`SQL 控制台`(console)、`性能洞察`(insights)、`对象浏览器`(objects)、`用户与权限`(roles)、`备份与恢复`(backups)、`维护与高级`(advanced)。
- **KV（redis）**：`总览`、`键空间`、`命令控制台`、`用户与权限 (ACL)`、`备份与恢复`、`配置与维护`。
- **DOC（mongo）**：`总览`、`数据库`、`集合与文档`、`Shell 控制台`、`用户与权限`、`备份与恢复`、`索引与维护`。

### 统一错误兜底（renderTab catch，L295-L321）

任何页签加载失败 → `<引擎名> 无法加载` 卡：徽章 `未安装`（匹配 `/未安装|未找到/`）/ `未运行`（匹配 `/未运行|ECONNREFUSED|连接失败|无法连接|No such file|…/`）/ `错误`；pre 显示原始错误文本；按钮 `启动 <引擎> 服务`（仅未运行）、`安装 <引擎>`（仅未安装）、`重新加载`；底部说明服务单元名或「该引擎无 systemd 服务（文件型数据库）」。
**这正是本服务器（postgres 未启动 / mysql 未安装）会看到的常态界面**——`GET /db/status?engine=postgres` 400 `{"error":"psql: error: connection to server on socket …","output":…}`（样本 `api-dump/db_status_engine-postgres.json`）会命中 `未运行` 分支并提供「启动 PostgreSQL 服务」按钮。

## 接口调用（按页签；端点均带 `Authorization: Bearer`）

### 顶部壳（每次引擎切换/刷新）
| 端点 | 触发 | 行号 |
|---|---|---|
| `GET /databases`（缓存10s）、`GET /db/engines`（缓存10s） | mount / `刷新引擎信息` / 动作后 invalidate | L72-L73、L90-L91 |
| `POST /db/engine/service {engine, action:"start"\|"stop"}` | 引擎按钮「启动/停止」（L179-L196）、错误卡「启动服务」（L316） | 审计 `db.engine.service` |
| `POST /db/engine/install {engine}` | 「安装引擎」对话框 / 错误卡「安装」（L135、L253、L318） | 审计 `db.engine.install` |
| `POST /pkg/install {pkg:"postgresql"\|"mysql-server"}` | 同上（pg/mysql 走 apt 白名单）（L133、L252） | 审计 `pkg.install` |

引擎按钮文案：`<引擎名> [KV|DOC 徽章] [运行中|未运行]` + 独立 `启动/停止` 按钮（`SVC` 映射：postgres→postgresql、mysql→mysql、redis→redis-server、mongo→mongod；sqlite/db2 无，L163-L174）。

### 总览与监控（tabOverview，L328-L444）
| 端点 | 参数 | 说明 |
|---|---|---|
| `GET /db/status?engine=` | engine | 并行 L329-L332 |
| `GET /db/databases?engine=`（缓存10s） | engine | L331 |
| `GET /db/tables?engine=&database=` | 维护按钮选表时（L426） | – |
| `POST /db/kill {engine, pid}` | 活动连接「终止」（L412） | 审计 `db.kill` |
| `POST /db/maintenance {engine, database, table, action}` | 维护操作按钮（L438，`toast:false` + `modal.output('维护结果')`） | 审计 `db.maintenance`；action 见下 |

- 维护按钮按引擎（L339-L347）：pg `vacuum/vacuum-full/analyze/reindex/checkpoint`；mysql `analyze-mysql/optimize/check/repair/flush`；sqlite `vacuum/analyze/reindex/optimize/check/wal-checkpoint`；db2 `analyze(RUNSTATS)/vacuum(REORG)/check(REORGCHK)`。
- UI：四联统计卡 `引擎状态`(绿`运行中` + version)、`连接数`(`connections`，副 `上限 max_connections · 活动 active`)、`数据库数量`（副 `总大小`）、第四卡按引擎（pg `缓存命中率` / mysql `累计查询` / sqlite `表 / 索引` / 其它 `对象数量`）；`维护操作`卡；`服务器信息`卡（`version` 与 `stats` 全部键值对，L364-L370）；`活动连接 (N)`表（列 `PID/用户/数据库/状态/时长(s)/客户端/SQL[截80]/终止`，最大高 320px，L373-L385）；`配置变量 (N)`卡（搜索框 + 表 `变量/值/分类`，L387-L401）。

`GET /db/status` 响应（`dbadmin.js:L205-L236`）：`{engine, version:{}, stats:{}, processes:[…], variables:[…], capabilities:{…}}`；pg 的 `stats` 含 `connections/max_connections/active/databases/cache_hit_pct/total_size`，`version` 含 `version/data_dir/started/cur_db_size`；mysql `stats` 含 `connections/max_connections/uptime_sec/questions/buffer_pool_bytes/databases`。进程行：pg `{pid,user,db,state,client,started,seconds,query}`；mysql `{pid,user,db,state,client,seconds,started,query}`。

### 数据库管理（tabDatabases，L446-L531）
| 端点 | 触发 |
|---|---|
| `GET /db/databases?engine=`（缓存10s） | L447 |
| `POST /db/database/create {engine, name, owner?, encoding?, collation?, charset?, template?}` | 「新建数据库」卡 `创建`（L494） |
| `POST /db/backup {engine, database, format:'plain'}` | 行 `导出`（L501，toast `r.output`） |
| `POST /db/database/rename {engine, from, to}` | 行 `重命名`（L508） |
| `POST /db/database/drop {engine, name, force}` | 行 `删除`（L514，带 force 确认） |
| `POST /db/database/alter {engine:'postgres', database, action, params}` | pg 行 `维护` 菜单：`set-param`/`reset-param`/`owner`/`connlimit`（L525-L528） |

响应：`/db/databases` 数组元素（`dbadmin.js:L239-L263`）：pg `{name,owner,size,size_bytes,encoding,collation,is_template,created,connections,tables:null}`；mysql `{name,owner,size,size_bytes,encoding,collation,tables,created,connections:null}`；sqlite/db2/redis/mongo 由 dbmulti 提供（redis 行含 `keys/expires/avg_ttl`，见 L2114-L2121）。
UI：`新建数据库`卡（字段随引擎）+ `数据库列表 (N)`表：`数据库[+模板徽章]/所有者/大小/表/编码/连接/操作(打开/导出/重命名/删除/pg维护)`（L466-L475）。

### 表与数据（tabTables，L533-L1024）
| 端点 | 请求体 | 触发/行号 |
|---|---|---|
| `GET /db/databases?engine=` | – | 未选库时先选库（L535、L423 prompt） |
| `GET /db/tables?engine=&database=` | – | 表列表（L544） |
| `GET /db/table/schema?engine=&database=&table=` | – | 打开表（L581） |
| `POST /db/table/data {engine,database,table,limit,offset,orderBy,orderDir,where}` | 分页/排序/过滤 | 数据视图（L778-L786） |
| `POST /db/schema/action {engine,database,table,action,params}` | `add-column/drop-column/…/index-create/table-copy` 等 | 可视化 DDL（L734）、建索引（L1851）、复制表（L928）；审计 `db.schema.action` |
| `POST /db/console {engine,database,sql}` | DDL 文本执行（L763） | 审计 `db.console` |
| `POST /db/count {engine,database,table,where}` | 统计行数（L869） | 审计 `db.count` |
| `POST /db/table/delete {engine,database,table,pk?,where}` | 批量删除（L882）、行删除（L963） | 响应 `{ok,output,affected:number}`（`dbadmin.js:L449-L462`） |
| `POST /db/export {engine,database,sql,format,table}` | 导出（L892） | 审计 `db.export` |
| `POST /db/import {engine,database,table,csv,hasHeader,nullEmpty,mode}` | CSV 导入（L913） | 审计 `db.import` |
| `POST /db/table/action {engine,database,table,action,newName}` | `truncate`(L934)/`drop`(L940)/`rename` | 响应 `{ok,output:"表操作完成: <action>"}`（L463-L474） |
| `POST /db/table/insert {engine,database,table,values}` | 行编辑器新增（L1018） | `{ok,output:"已插入 1 行",sql}`（L420-L429） |
| `POST /db/table/update {engine,database,table,pk,values,where}` | 行编辑器保存（L1017） | `{ok,output,sql,affected,columns,rows}`（L431-L447） |

`POST /db/table/data` 响应（`dbadmin.js:L391-L418`）：`{columns:string[], rows:object[], limit, offset, total:number|null, durationMs}`（limit clamp 1–500，默认 50；排序列必须存在否则 400 `排序列不存在: <col>`；`where` 走 `safeWhere()` 校验）。

UI：`选择数据库`卡 → `表 / 视图`表（列 `Schema/名称/类型[table→info徽章,view→purple]/行数/大小/操作(打开)` + `搜索…`框，L555-L566）→ 打开表后三视图切换（`数据/结构/DDL`，`S.tableView`）：
- **结构**（`renderStructure` L606+）：`列结构`表（`#/列名[+PK]/类型/可空(YES灰,NO红)/默认值/额外`）、`索引`表（`索引名/列/UNIQUE/类型`）、`表结构操作（可视化 DDL）`卡（L650+）。
- **DDL**（`renderDdl` L740+）：`DDL 定义（可编辑后执行）`卡 + 执行按钮（走 `/db/console`）。
- **数据**（`renderData` L777+）：数据表（主键列前 N，行内 `编辑/删除`）；工具条：分页（`page/pageSize=50`）、排序（`orderBy/orderDir`）、`WHERE 条件`、`统计行数`、`批量删除`（prompt `WHERE 条件（必填，禁止分号）`）、`导出`（格式 csv/sql/json…）、`导入 CSV`（prompt `首行是否为列名`/`空字段视为 NULL`）、`复制表`（prompt `新表名`/`同时复制数据`）、`清空表`(truncate)、`删表`(drop)、`刷新`。
- 行编辑器（`rowEditor` L969+）：prompt 定位条件（`WHERE 条件（必填）`，主键默认拼 `col = 'v'`）→ insert/update。

### SQL 控制台（tabConsole，L1026-L1178）
| 端点 | 请求体 | 说明 |
|---|---|---|
| `GET /db/databases?engine=`（缓存10s） | – | 库下拉（L1058） |
| `POST /db/console {engine, database, sql, explain?, transaction?, dryRun?}` | 执行/试运行/EXPLAIN（L1084） | 响应 = `runSql()` 结果 `{ok, output, columns[], rows[], durationMs, raw, stderr}`；`dryRun` → `{ok:true, dryRun:true, durationMs, columns:[], rows:[], output:"【试运行】…"}`；`explain` → `{ok, output, columns:[], rows:[], durationMs, explain:true}`（`dbadmin.js:L607-L636`）；错误 400 `{"error":"SQL 为空"}`/`{"error":"SQL 过长"}`（>500000 字符） |
| `POST /db/export {engine,database,sql,format,table:''}` | 控制台导出按钮（L1101） | – |
| `POST /db/explain/tree {engine,database,sql,analyze}` | 执行计划树（L1138） | 审计 `db.explain.tree` |

UI：`SQL 控制台`卡（库选择、模板下拉 `TEMPLATES` L20-L33：SELECT/CREATE TABLE/ALTER ADD COLUMN/CREATE INDEX/INSERT/UPDATE/DELETE/GRANT/CREATE VIEW/统计表大小/统计慢查询、按钮 `执行`/`试运行`/`EXPLAIN`/`导出`）、`历史记录`卡（`时间/库/SQL[截90]` + `清空`，本地 localStorage）；结果区：表格（有 columns 时）或输出 pre。

### 性能洞察（tabInsights，L1500-L1554）
- `GET /db/databases?engine=`（L1501）→ `GET /db/insights?engine=&database=` + `GET /db/statements?engine=&database=`（并行，L1523-L1524）。
- `/db/insights` 响应：`{engine, database, sections:[{title, columns:[], rows:[], error, note}]}`（**分节容错**，样本 `docs/findings/extra-api/db_insights_engine-postgres.json` 8 节、mysql 8 节均带 `mysql: command not found` 的 section.error）。
- `/db/statements` 响应：`{available:bool, hint, rows:[], columns:[]}`（本机 pg：`available:false` + `未安装 pg_stat_statements 扩展。安装后需要重启 PostgreSQL 才能生效。`）；未启用 UI 显示 `慢查询 / 热点语句 未启用` 卡（L1529）+ `安装扩展` 按钮 → `POST /db/extension {engine:'postgres', name:'pg_stat_statements'}`（L1538）；启用后 `慢查询 / 热点语句 N 条` 表（L1544）。
- UI：`renderSections()`（L1484-L1498）把每个 section 渲染成卡片：标题、`error` 红字、`note` 提示、`columns/rows` 表格。

### 对象浏览器（tabObjects，L1556-L1629）
- `GET /db/objects?engine=&database=` + `GET /db/schemas?engine=&database=`（并行，L1582-L1583；schemas 失败回落 `{rows:[]}`）。
- `/db/objects` 响应 = `{engine, database, sections:[{title,columns,rows,error,note}]}`（样本 `db_objects_engine-postgres.json` 7 节）。
- `GET /db/schemas` 在本机 400 `{error,output}`（postgres 未运行）→ 前端 `.catch(()=>({rows:[]}))`。
- 动作：`POST /db/schema/manage {engine,database,action:"create"|"drop",name}`（建/删 schema，L1604/L1613）、`POST /db/extension {engine:'postgres', name}`（L1624，`modal.output('扩展安装')`）。
- UI：`模式 / 命名空间 (N)`表（`模式/类型/所有者/表/视图/函数/大小`，L1591-L1598）+ objects 各分节卡片 + `新建 schema` 输入。

### 用户与权限（tabRoles，L1181-L1308）
- `GET /db/roles?engine=`（L1188）：pg → `{roles:[{name,super,can_login,createdb,createrole,valid_until,member_of}], privileges:[{grantee,schema,privilege,object}]}`；mysql → `{roles:[{name,host,super,auth_plugin,has_password}], privileges:[], grants:string[], hosts:[]}`（`dbadmin.js:L477-L493`）。**本机 400**（引擎不可用）→ 走错误兜底卡。
- `GET /db/databases?engine=`（L1190）供授权下拉。
- `POST /db/role {engine, action, name, …}`，action 全集（`dbadmin.js:L495+`）：
  - pg：`create{name,password,privileges[]}`、`drop{name}`、`password{name,password}`、`grant-db{name,database}`、`revoke-db`、`add-member{name,member}`、`remove-member`、`alter{name,privileges[],params:{connlimit,validUntil,login,superuser,createdb,createrole}}`（L1294）。
  - mysql：`create{name,password,host}`、`drop`、`password`、`grant-db`、`grants-list{name}`（L1301，输出 `modal.output('权限清单 - <name>')`）、`lock/unlock/expire` 等（L1297-L1305）。
  - 表级授权 prompt：`{database, table, privs:'SELECT|SELECT,INSERT|…|ALL', op:'授予|回收'}` → `grant-table/revoke-table`（L1258-L1270）。
- UI：`创建用户/角色`卡（L1193+）、`角色列表 (N)`表（pg 列 `名称/SUPER/LOGIN/所属角色组/操作(改密/授权/删除/编辑)`；mysql 额外 `Host/密码`，L1204-L1221）、`当前用户权限 (SHOW GRANTS)`卡（mysql）、`表级授权概览`卡（`角色/Schema/权限`）。
- Db2 分支 `db2Roles`（L2043-L2095）：`GET /db/roles?engine=db2`、`GET /db/databases?engine=db2`（均 catch）→ `Db2 授权（GRANT / REVOKE）`卡 + `用户 / 权限 (N)`表（`用户/DBADM/DATAACCESS/ACCESSCTRL`）；动作 `POST /db/role {engine:'db2', action, name, privilege, params:{table,privileges}}`（L2090）。

### 备份与恢复（tabBackups，L1311-L1482）
| 端点 | 请求体 | 触发 |
|---|---|---|
| `GET /db/backups?engine=` + `GET /db/databases?engine=` | 并行（L1313-L1314） | 加载 |
| `POST /db/backup {engine, database, format, mode, exclude?}` | `新建备份 / 导出`（L1401，`toast:false` → `toast(r.output, 'ok', 6000)`） | 审计 `db.backup`；`format:'custom'` 仅 pg；`mode∈full/schema/data` |
| `POST /db/import {engine,database,table,csv…}` / `POST /db/upload?name=` | `导入 SQL 文件（用于恢复）`（L1355） | 上传走 raw body（`index.js:L322-L326`），导入走 csv/sql 解析 |
| `POST /db/backup/cleanup {keep}` | `清理旧备份` prompt `保留最近 N 个`（L1383-L1385） | 审计 `db.backup.cleanup` |
| `POST /db/restore {engine, file, database?, dropFirst?, createIfMissing?}` | 恢复（L1444/L1455/L1472） | 三种模式：pg_dumpall（`限定数据库（留空=恢复全部）`）、单库恢复、目标库（`导入方式：合并导入/清空后恢复（--clean，可能丢数据）`、`目标库不存在时：自动创建/报错退出`）；审计 `db.restore` |
| `POST /db/backup/delete {name}` | 行 `删除`（L1480） | 审计 `db.backup.delete` |
| `GET /db/backup/download?name=` | 行 `下载`（直链 + `&token=`） | 二进制 |
| `POST /cron/action {action:'add', params:{name:'dbbackup-<engine>', schedule:'0 3 * * *', command}}` | `每日定时备份`（L1417） | 复用 cron 页接口，审计 `cron.action` |

`GET /db/backups` 响应：`{dir, items:[{name, size, uploaded:bool, export:bool, engine, database, mode:"full"|"schema"|"data"|"custom"|"rdb", mtime}]}`（样本 `api-dump/db_backups.json`、`dbadmin.js:L644-L666`）。
文件名规则：`db-<engine>-<db|ALL>-<mode>-<时间戳>.sql.gz|.dump`、导出 `ex-*`、上传 `uploaded:true`。
UI：`新建备份 / 导出`卡（库/格式/模式/排除表）、`导入 SQL 文件（用于恢复）`卡、`备份文件 (N)`表（`文件名/类型[导出紫|上传灰|引擎蓝]/内容[完整|仅结构|仅数据|自定义格式|RDB快照]/大小/时间/操作(下载/恢复/删除)`，L1362-L1378）。

### 维护与高级（tabAdvanced，L1631-L2041）
子区块与端点：
| 区块（card-title） | 端点 | 行号 |
|---|---|---|
| `批量表操作` | `GET /db/tables` + `POST /db/table/bulk {engine,database,tables[],action}`（`modal.output('批量操作结果')`） | L1660-L1706 |
| `表存储与活动明细` | `GET /db/table/storage?engine=&database=&table=`（索引表 `索引/扫描次数/读取行/大小/定义`） | L1711-L1757 |
| `跨库复制表` | `POST /db/table/copy-db {engine,database,table,targetDb,newName,withData}` | L1763-L1795 |
| `定时维护计划` | `POST /db/maintenance/plan {engine,action,schedule}`（`showOutput`） | L1807-L1824 |
| `缺失索引建议` | `GET /db/missing-indexes?engine=&database=`（本机 400）+ 建索引 `POST /db/schema/action {action:'index-create',params:{name,columns}}`；`未使用索引` 表 | L1828-L1857 |
| `会话批量清理` | `GET /db/status?engine=`（进程列表）+ `POST /db/session/kill {engine, action:'kill', filter}` | L1864-L1906 |
| `全库数据搜索` | `POST /db/table/search {engine,database,value,table?,limit:20}` | L1912-L1930 |
| `序列管理` | `POST /db/sequence {engine,database,action,params}` | L1950-L1984 |
| `视图管理` | `POST /db/object {engine,database,kind:'view',action:'create'\|'drop',params:{name,sql}}` | L1964-L1993 |
| `触发器管理` | `POST /db/object {kind:'trigger', action:'create'\|'enable'\|'disable'\|'drop', params}` | L1970-L2004 |
| `扩展监控（WAL / BGWriter / InnoDB / 复制槽）` | `GET /db/extra-monitor?engine=&database=`（缓存10s，样本 `{engine,sections:[…]}` 10 节） | L2009-L2022 |

### Redis（kv）分支
| 页签 | 端点 | 行号 |
|---|---|---|
| 总览 | `GET /db/status?engine=redis` + `GET /db/databases?engine=redis` | L2097-L2100 |
| 总览-客户端 | `POST /db/kill {engine:'redis', pid}` | L2167 |
| 键空间 | `GET /db/kv/keys?database=&pattern=&limit=300`（响应 `{keys[],cursor,db,pattern}`）、`GET /db/kv/value?database=&key=`（`{type,key,value,ttl}`）、`POST /db/kv/set {database,key,value,ttl}`、`POST /db/kv/delete {database,keys[]}` | L2194-L2232 |
| 命令控制台 | `POST /db/console {engine:'redis', database, sql:<命令>}` | L2260 |
| ACL | `GET /db/roles?engine=redis` + `POST /db/role {engine:'redis', action:'create'\|'password'\|'drop', name, password}` | L2274-L2310 |
| 配置与维护 | `GET /db/kv/config`（`{ok,output,rows}`）、`POST /db/kv/config {name,value}`、`GET /db/statements?engine=redis`、`POST /db/maintenance {engine:'redis', action}`、`GET /db/insights?engine=redis` | L2314-L2375 |

UI：四联卡 `引擎状态/已连接客户端/键总数/已用内存`；`逻辑库 (db0-db15)`表（`库/键数/带过期/平均 TTL(ms)/操作(浏览)`）；`服务器信息`；`连接的客户端 (N)`表（`ID/用户/库/最近命令/存活(s)/客户端`）；`INFO (N)`表；键空间表 `键/类型/TTL(永久|Ns)/内存/操作(查看/编辑/删除)`；`Redis 命令控制台` + `常用命令`；`创建 ACL 用户`、`ACL 用户 (N)`表；`维护操作`、`慢查询 (SLOWLOG)`、`运行时配置`表（`配置项/值`）。

### MongoDB（doc）分支
| 页签 | 端点 | 行号 |
|---|---|---|
| 总览 | `GET /db/status?engine=mongo` + `GET /db/databases?engine=mongo`；删除库 `POST /db/database/drop {engine:'mongo',name}` | L2382-L2411 |
| 数据库 | `GET /db/databases?engine=mongo`；`POST /db/database/create {engine:'mongo',name}`、`drop` | L2415-L2445 |
| 集合与文档 | `GET /db/tables?engine=mongo&database=`（集合）、`POST /db/table/action {engine:'mongo',action:'drop'}`、`POST /db/doc/collection {database,name}`、`POST /db/doc/indexes {database,table}`、`POST /db/table/data {engine:'mongo',database,table,limit:100,offset:0,where}`、`POST /db/doc/update {database,table,where,values}`、`POST /db/doc/delete {database,table,where}`、`POST /db/doc/insert {database,table,values}`、`POST /db/doc/index {database,table,keys,unique}`、`POST /db/doc/index/drop {database,table,name}` | L2449-L2576 |
| Shell 控制台 | `POST /db/console {engine:'mongo',database,sql}` | L2595 |
| 用户与权限 | `GET /db/roles?engine=mongo` + `POST /db/role {engine:'mongo', action:'create'\|'password'\|'drop', name, password, database, roles}` | L2607-L2644 |
| 备份与恢复 | 与 SQL 共用 `tabBackups`（engine=mongo） | L282 |
| 索引与维护 | `GET /db/databases?engine=mongo`、`POST /db/maintenance {engine:'mongo',database,action}`、`POST /db/console {engine:'mongo',database:'admin',sql:'db.setProfilingLevel(...)'}`、`GET /db/extra-monitor?engine=mongo` | L2648-L2696 |

UI：四联卡 `引擎状态/连接/数据库/主机 · 运行时间`；`数据库列表`表（`数据库[+系统徽章]/大小/操作`）；`集合`表（`集合/文档数/大小/索引/操作(查看/删除)`）；`索引 (N)`表（`索引/键/唯一`）+ `说明`卡；文档表（`_id` 与各字段列，行操作 `编辑/删除`，工具 `插入文档` prompt JSON）；`MongoDB Shell`；`创建用户`、`用户列表 (N)`（`用户/角色/操作`）；`维护操作`。

## 响应字段结构（关键端点速查）

| 端点 | 顶层结构 | 依据 |
|---|---|---|
| `GET /databases` | `{ts, engines:[{id,name,installed,service,active,version,databases:[{name,sizeMb,tables}\|{name,size,sizeMb}],stats:{…}\|null}], bins:{<bin>:path}}` | `collectors.js:L437-L527` + 样本 |
| `GET /db/engines` | `{engines:[{id,name,kind,installed,active,service,packages[],needsRepo?}]}` | `dbmulti.js:L1550-L1561` |
| `GET /db/status?engine=` | `{engine, version{}, stats{}, processes[], variables[], capabilities{}}`；错误 400 `{error,output}` | `dbadmin.js:L205-L236` |
| `GET /db/databases?engine=` | 见上（数组） | `dbadmin.js:L239-L263` |
| `GET /db/tables?engine=&database=` | `[{schema,name,kind:"table"\|"view",rows,size,…}]`（sqlite/db2 由 dbmulti 处理，sqlite 参数错 400 `SQLite 数据库文件必须以 .db/.sqlite/.sqlite3/.db3 结尾`） | `dbadmin.js:L327+` + 样本 |
| `GET /db/table/schema?engine=&database=&table=` | `{columns:[{name,type,nullable,default,extra,pk,def}], indexes:[{name,cols,uniq,type,def}], …}` | `dbadmin.js:getTableSchema` + UI L627-L644 |
| `GET /db/roles?engine=` | pg/mysql 见上；db2/redis/mongo 分支各自 `{roles[], …}` | `dbadmin.js:L477-L493` |
| `GET /db/backups?engine=` | `{dir, items[]}` | 样本 |
| `GET /db/insights\|objects\|extra-monitor` | `{engine,database?,sections:[{title,columns,rows,error,note}]}` | 样本 |
| `GET /db/statements?engine=` | `{available,hint,rows,columns}` | 样本 |
| `POST /db/table/data` | `{columns,rows,limit,offset,total,durationMs}` | `dbadmin.js:L413-L417` |
| `POST /db/console` | `{ok,output,columns,rows,durationMs,raw,stderr}` / dryRun / explain 变体 | `dbadmin.js:L607-L636` |
| `POST /db/*`（写操作） | `{ok:true, output:"…", …额外字段(sql/affected/columns/rows)}` | `ok()` helper |
| `POST /db/export` | `{ok, output, file?, …}`（导出文件落 DB_BACKUP_DIR，出现在备份列表） | `dbadmin.js:L1071+` |
| `POST /db/count` | `{ok, output:"N 行"}` | `dbadmin.js:L1096-L1102` |

**通用错误形态**：所有执行类失败 400 `{"error":"<首行消息>","output":"<stderr+stdout>"}`（`runSql`，`dbadmin.js:L185-L189`）→ 前端 `renderTab` catch 显示错误卡，或 `modal.output` 展示 output。

## 用户动作 → 请求（汇总）

顶部：切换引擎（无请求，重置 tab/database/table）· `安装引擎`（对话框，`POST /db/engine/install` 或 `POST /pkg/install`）· `刷新引擎信息`（invalidate + 重取）。
每页签的按钮见上表；通用约定：**危险动作必弹 `modal.confirm`**（删库/删表/清空/恢复/删备份），**命令输出类动作用 `modal.output`**（维护/批量/DDL/恢复/导出），**表单类用 `modal.prompt`**（建库/改名/授权/导入参数/行编辑）。

审计名全集（index.js dbR/dbG 段）：`db.action`、`db.query`、`db.row.insert`、`db.row.update`、`db.row.delete`、`db.table.action`、`db.database.create|drop|rename|alter`、`db.maintenance`、`db.maintenance.plan`、`db.role`、`db.kill`、`db.console`、`db.backup`、`db.backup.cleanup`、`db.backup.delete`、`db.restore`、`db.schema.action`、`db.schema.manage`、`db.table.bulk`、`db.table.copy-db`、`db.table/backup`、`db.table.search`、`db.explain.tree`、`db.sequence`、`db.object`、`db.session.kill`、`db.export`、`db.count`、`db.import`、`db.extension`、`db.engine.install`、`db.engine.service`、`db.kv.set|delete|config`、`db.doc.*`、`db.upload`、`db.backup.download`。审计体里 `content` 记 `<N bytes>`、`sql` 截 120 字符（`index.js:L214-L221`）。

## 实现提示与边界（本机实测）

1. **引擎不可用是常态**：postgres 服务停、mysql 未装 → SQL 类页签几乎全部落到「无法加载」卡（带启动/安装按钮）。KMP 必须把这张错误卡做完整（徽章 + 原始错误 pre + 三个按钮 + 服务单元说明）。
2. `/db/engines` **不含 postgres/mysql**（只有 sqlite/db2/redis/mongo），pg/mysql 状态来自 `GET /databases` 的 engines —— 合并逻辑必须按 `buildEngineList()`（L56-L67）。
3. Redis 服务未启动时 `/db/kv/keys`、`/db/kv/value` 仍 200 返回空结构（实测），不会 500；但 `/db/status?engine=redis` 会 400。
4. 页签按引擎 kind 切换（sql/kv/doc 三套 TABS），复刻时用三套不同的页面模型，不要用一套硬套。
5. `GET /db/schemas`、`/db/table/storage`、`/db/missing-indexes` 在本机均 400（postgres 未运行）—— 高级页签要有 `.catch` 兜底（源码部分 catch、部分走错误卡）。
6. 每次引擎启停/安装后必须 `CM.api.invalidate('/databases')` + `invalidate('/db/')` 再 `refreshEngines()`（L191-L193、L211-L212、L314）——KMP 端等价于清缓存（`PanelApi.invalidate` 已有）。
7. 下载备份文件名校验：`^(db|up)-[A-Za-z0-9._\-]+\.(sql\.gz|dump|sql)$`（`dbadmin.js:L639-L643`），非法 400 `非法文件名`。
8. SQL 控制台 `dryRun`（试运行）与 `explain` 是两个独立布尔参数；`transaction` 会自动 `BEGIN/COMMIT` 包裹。
9. 本页对 429 限流敏感（一次页签切换可能并发 2–3 个查询）；1800/min 通常够用，但 KMP 端不要加自动轮询。
