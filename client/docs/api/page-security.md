# page-security — 安全管理（security）

## 概览

- `registerPage({ id:'security', title:'安全管理' })`（`upstream/public/js/pages/security.js:L5-L7`）。
- **一次性加载**（无自动轮询）：mount 并行拉 `GET /security` 与 `GET /relayops/ratelimit`（后者 `.catch(()=>null)`，失败则限流区块整体不渲染）（L9-L12）。多数动作成功后 `location.reload()` 整页重载。
- 数据来源混合：主机安全态（/security）+ nginx 限流/CC 防护统计（/relayops/ratelimit）。

## 接口调用

### GET /security（mount）
- 响应（样本 `api-dump/security.json`）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `ufw` | `{installed:bool, active:bool, rules:[{num:int,rule:string,action:"ALLOW"\|"DENY",from:string,comment:string}], raw:string[]}` | UFW 状态与规则（`collectors.js:L404-L409`） |
| `fail2ban` | `{active:bool, status:string, banned:string}` | fail2ban 活动状态、`fail2ban-client status` 输出、`sshd` jail 当前封禁文本 |
| `sshd` | `{port, maxauthtries, permitrootlogin, pubkeyauthentication, passwordauthentication, kbdinteractiveauthentication, permitemptypasswords}` | `sshd -T` 有效配置（键为小写） |
| `users[]` | `{name, uid:int, home, shell}` | 可登录用户（shell 非 nologin/false） |
| `sudoers[]` | string[] | `/etc/sudoers*` 非注释行 |
| `recentFailed[]` | string[] | `lastb -n 25` 输出（≤20 行） |
| `recentLogins[]` | string[] | `last -n 15 -w` 输出（≤12 行） |
| `sysctl` | `{<17 个内核参数键>: string\|null}` | 见 `collectors.js:L380-L387` 的 `SECURITY_SYSCTL_KEYS`（tcp_syncookies、rp_filter×2、accept_redirects、send_redirects、accept_source_route、icmp_echo_ignore_broadcasts、log_martians、kptr_restrict、dmesg_restrict、randomize_va_space、protected_symlinks、protected_hardlinks、suid_dumpable、ipv6 accept_redirects、tcp_fin_timeout、somaxconn） |
| `apparmor` | string | `systemctl is-active apparmor` + `aa-status` 输出 |

### GET /relayops/ratelimit（mount，可失败）
- 响应（样本 `api-dump/relayops_ratelimit.json`）：`{present:bool, zones:[{type,name,size,rate?}], applied:[{context,kind,zone,value,burst?,nodelay?}], configRaw:string, windowLines:int, limited429:int, byHour:[{hour,count}], dist:[{code,count}], errorLimitCount:int, fail2banActive:bool, jails:[{name,currentlyBanned,totalBanned,ips[]}], logFiles:{access,error,conf}}`。
- 页面用到：`limited429`、`windowLines`、`errorLimitCount`、`zones`（过滤 `type!=='global'` 计数）、`jails`（求和 currentlyBanned）、`present`、`applied`、`logFiles.conf/access`、`byHour`（≤24 条）。

### POST /security/action
| action | 请求体 | 服务端行为（`actions.js:L120-L188`） | 错误 |
|---|---|---|---|
| `fail2ban-install` | `{action}` | apt 安装 + `systemctl enable --now fail2ban`（180s 超时） | – |
| `fail2ban-start` / `fail2ban-stop` | `{action}` | systemctl start/stop | – |
| `fail2ban-unban` | `{action, params:{ip}}` | `fail2ban-client unban <ip>` | 400 `{"error":"无效 IP"}` |
| `sysctl-set` | `{action, params:{key,value}}` | `sysctl -w` + 持久化 `/etc/sysctl.d/99-cloud-monitor.conf` | 400 `{"error":"无效 sysctl 键"}` / `{"error":"sysctl 键不存在: <key>"}` |
| `sshd-apply` | `{action, params:{port,maxAuthTries,permitRootLogin,passwordAuthentication,pubkeyAuthentication,permitEmptyPasswords}}` | 写 `/etc/ssh/sshd_config.d/99-cloud-monitor.conf` → `sshd -t` 校验 → reload ssh | 400 `{"error":"无效端口"}` / `{"error":"<key> 无效值: <v>"}` / `{"error":"sshd 配置校验失败，未应用:\n…"}` / `{"error":"没有要应用的配置"}` |

审计名均为 `security.action`（`index.js:L227`）。

### POST /user（用户与 SSH 密钥）
| action | 请求体 | 服务端行为（`actions.js:L463-L526`） | 错误 |
|---|---|---|---|
| `create` | `{action, params:{name,password,sudo}}` | `useradd -m -s /bin/bash` + chpasswd；sudo=true 再 `usermod -aG sudo` | 400 `用户名仅允许小写字母开头的 [a-z0-9_-]` / `用户已存在: …` / `密码至少 6 位` |
| `delete` | `{action, params:{name}}` | `userdel -r` | 400 `不能删除 root` / `用户不存在` / `拒绝通过面板删除系统用户 (UID<1000)` |
| `passwd` | `{action, params:{name,password}}` | chpasswd | 400 `密码至少 6 位` |
| `sudo-grant` / `sudo-revoke` | `{action, params:{name}}` | `usermod -aG sudo` / 重设组列表移除 sudo | – |
| `sshkey-list` | `{action}` | `cat /root/.ssh/authorized_keys`（**POST**，响应 `{ok,output}`，前端按行解析） | – |
| `sshkey-add` | `{action, params:{key}}` | 校验公钥格式 + 去重后追加 | 400 `公钥格式无效` / `该公钥已存在` |
| `sshkey-del` | `{action, params:{key:<指纹/子串>}}` | 按包含匹配删除行 | 400 `无效指纹` |

审计名 `user.action`（`index.js:L395`）。

## 用户动作 → 请求

| 控件（源码文案） | 请求 | 说明 |
|---|---|---|
| `应用并重载 SSH`（L22/L193-L208） | 确认框 `应用 SSH 配置`（danger，正文含参数 JSON 与红色警告「将关闭密码认证…」当 passwordAuthentication=no）→ `POST /security/action {action:'sshd-apply', params}`，`showOutput:true, title:'SSH 配置应用'` | – |
| sysctl 每行 `应用` 按钮（L176/L178-L186） | 确认框 `应用 sysctl` / `设置 <code>key=value</code> 并持久化？` → `sysctl-set` | – |
| `安装并启动`（fail2ban，L48/L212-L218） | 确认框 `安装 Fail2ban`（正文 apt 安装说明）→ `fail2ban-install`，成功 `location.reload()`（reloadDelay 1500） | busy 态 |
| `启动服务`（L48/L220） | `fail2ban-start` + reload | – |
| `停止`（L47/L222-L226） | 确认框 `停止 Fail2ban` / `停止后将失去 SSH 防爆破保护。`（danger）→ `fail2ban-stop` + reload | – |
| `解封 IP`（L54/L227-L231） | 空值 toast `请输入 IP`；否则 `fail2ban-unban {params:{ip}}`（不 reload） | – |
| 限流卡 `刷新`（L77/L190-L191） | `location.reload()` | – |
| `创建用户`（L127/L234-L242） | 空校验 toast `请填写用户名和密码`；确认框 `创建用户` → `POST /user {action:'create', params:{name,password,sudo}}` + reload | – |
| 用户行 `改密`（L135/L250-L256） | `modal.prompt('修改密码 - <user>', [{name:'password',label:'新密码',type:'password'}], {okText:'修改'})` → `{action:'passwd', params:{name,password}}` | – |
| 用户行 `sudo`（L136/L257-L263） | 确认框 `授予 sudo`（danger）→ `{action:'sudo-grant'}` | – |
| 用户行 `删除`（L137/L243-L249） | 确认框 `删除用户`（danger, okText `删除`）→ `{action:'delete'}` + reload | root 行不显示操作（L134） |
| `添加公钥`（L147/L287-L292） | 空校验 toast `请粘贴公钥`；`{action:'sshkey-add', params:{key}}`，成功清空输入并 `loadKeys()` | – |
| 公钥行 `删除`（L274/L277-L283） | 确认框 `删除公钥` / `删除后持有该密钥的客户端将无法登录。`（danger）→ `{action:'sshkey-del', params:{key:<整段公钥>}}` + `loadKeys()` | – |

`loadKeys()` = `POST /user {action:'sshkey-list'}`（L266-L286）：按行拆成 `{type, key, comment}`（`l.trim().split(/\s+/)`，L275）。

## UI 分区

1. **姿态四联卡**（L164-L168）：`主机防火墙 UFW`（`已启用`绿/`未启用`黄，副 `N 条规则`）、`Root 密码登录`（值 = `permitrootlogin`，`yes` 红，其余绿，副 `PermitRootLogin`）、`SSH 密码认证`（`yes` 黄，否则绿）、`Fail2ban`（`防护中`/`未防护`）。
2. **SSH 服务配置 (sshd 有效配置) 卡**（L20-L41）：6 个表单控件（SSH 端口、MaxAuthTries、PermitRootLogin 下拉 4 项、PasswordAuthentication、PubkeyAuthentication、PermitEmptyPasswords 下拉）+ 右上 `应用并重载 SSH`；底部说明 `修改会写入 /etc/ssh/sshd_config.d/99-cloud-monitor.conf，先执行 sshd -t 校验，失败自动放弃。建议先配好密钥再关闭密码认证。`
3. **Fail2ban 防爆破 卡**（L43-L57）：标题徽章 `运行中`(ok)/`未运行`(bad)；状态 pre（空显示 `fail2ban 未安装/未运行`）；`要解封的 IP` 输入 + `解封 IP`；底部 `最近封禁：<banned>`。
4. **限流统计四联卡**（仅 ratelimit 成功时，L68-L73）：`429 拦截`（副 `最近 N 条请求中`）、`限流日志`（副 `error.log 累计`）、`限流区域`（非 global zones 数，副 `limit_req / limit_conn`）、`当前封禁 IP`（>0 红，副 `N 个 jail`）。
5. **限流 / CC 防护 卡**（L75-L112）：标题徽章 `已启用`(ok)/`未配置`(bad) + `刷新`；左「限流区域 (N)」表（`类型`：req→`速率`、conn→`并发`、global→`全局`(灰)；`名称 / 值`：`name · rate` 或 `name · size`）；右「应用位置 (N)」表（`上下文` 去掉尾部 `{`；`区域`：req → `zone burst=N [nodelay]`，否则 `zone ×value`）；`Fail2ban jail` 表（`Jail/当前封禁/累计封禁/被封 IP`）；底部 `配置：<conf> · 访问日志：<access>`；若有 byHour 表 `小时/429 次数`（≤24）；说明 `修改限流阈值：编辑 00-limits.conf，然后 nginx -t && systemctl reload nginx。`
6. **内核/系统安全参数 (sysctl) 卡**（L115-L118、L171-L177）：两列表单网格：17 个键各一行 `<key> [输入框] [应用]`。
7. **用户管理 (N) 卡**（L121-L141）：创建表单（用户名/密码/`sudo 权限` 复选框/`创建用户`）；表列 `用户/UID/家目录/Shell/操作`（root 显示 `—`）；底部 `sudoers：` pre（空 `(无)`）。
8. **SSH 授权密钥 (authorized_keys) 卡**（L142-L149）：密钥表（`类型/公钥(截60字…)/备注/删除`），空态 `暂无授权密钥（使用密钥登录更安全）`；textarea（placeholder `粘贴公钥 (ssh-ed25519 AAAA... user@host)`）+ `添加公钥`。
9. **最近登录与失败尝试 卡**（L152-L159）：`成功登录 (last)` pre（空 `(无)`）、`失败尝试 (lastb)` pre（空 `(无记录)`）。

## 实现提示与边界

- 本页所有写操作最终都建议 reload（原版大量 `location.reload()`），KMP 端等价做法：动作成功后重新拉 `/security` + `/relayops/ratelimit` 并重绘。
- `sshd` 键名是**小写**（`permitrootlogin` 等），而请求 params 用驼峰（`permitRootLogin`）—— 两侧命名不同，别映射错。
- 用户列表来自 `users[]`（仅可登录用户），删除保护：UID<1000、root。
- `sshkey-del` 的 `params.key` 传的是**整段公钥文本**（前端把 key 列整段传过去，服务端按 includes 匹配行，`actions.js:L515-L524`）。
- 限流区块依赖 `/relayops/ratelimit`；失败时整块不渲染（前端 `${rl ? … : ''}`，L60/L113）。
- UFW 规则表在本页不渲染（属于 network 页），本页只统计条数。
