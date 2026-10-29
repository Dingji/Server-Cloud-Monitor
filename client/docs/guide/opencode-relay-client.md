# 把本机 opencode 接到 AI 中继 —— 交给 AI 直接执行的配置手册

> **这份文档是给 AI 读的执行脚本。** 按顺序做，每一步都有"预期结果"；任何一步的实际结果
> 和预期不符，**停下来告诉人类**，不要凭猜测继续改配置。
> 三个位置标了 **STOP**：那三处需要人类提供信息或做决定，AI 不能自己编。

---

## 0. 目标与现状

配置完成后，本机 `opencode` 通过自建中继调用 AI 模型，而不是直连 `opencode.ai`：

```
opencode  ──HTTP──►  http://8.153.153.176/v1      (OpenCode Go 套餐，白名单模型)
              ──►    http://8.153.153.176/zen/v1   (OpenCode Zen，按模型计费)
              ──►    http://8.153.153.176/glm/v1   (智谱 GLM Coding Plan)
              ──►    http://8.153.153.176/mimo/v1  (小米 MiMo Token Plan CN)
                        │
                        └─ nginx → usagetap(127.0.0.1:8790) → 上游
```

中继做三件事：替换凭据（客户端持有的是**中继令牌**，不是官方 key）、记录 token 用量、
按套餐白名单拦掉不能用的模型。**忙时策略**（见 §7）在工作日高峰把 deepseek 请求改写到 mimo。

已验证的版本与路径（截至 2026-10-29）：

| 项 | 值 |
|---|---|
| opencode | `v2.1.4`（`opencode --version`） |
| 配置文件 | `%USERPROFILE%\.config\opencode\opencode.json`（Windows 与 Linux 同一路径规则：`~/.config/opencode/opencode.json`） |
| 中继令牌环境变量 | `OPENCODE_RELAY_KEY`（Windows 上是 **User 级**环境变量） |
| 数据目录 | `~/.local/share/opencode/`（含 `opencode.db`、`config-backups/`、`log/`） |
| 中继地址 | `http://8.153.153.176`（**HTTP，无 TLS**，见 §8 安全提示） |

---

## 1. 【STOP】开始前向人类要两样东西

1. **中继令牌**（relay token）。不要猜测、不要生成、不要从别处复制。
   人类可以从服务器上取：`ssh root@8.153.153.176 "sed -n 's/.*set \$relay_token *\"\([^\"]*\)\".*/\1/p' /etc/relay/relay.env"`
2. **要接哪几条线路**。默认建议：`relay-go`（套餐内免费额度）+ `relay-zen`（按量，模型多）。
   只有人类明确要求时才加 `relay-glm` / `relay-mimo`。

拿到令牌后立刻验证它有效，再往下做：

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST http://8.153.153.176/v1/chat/completions \
  -H "Authorization: Bearer <RELAY_TOKEN>" -H 'Content-Type: application/json' \
  -d '{"model":"mimo-v2.6-flash","messages":[{"role":"user","content":"say ok"}],"max_tokens":8}'
```

**预期：`200`。** 得到 `401` 说明令牌不对；`403` 说明模型不在白名单（见 §3 的白名单）；
`503` 是套餐 key 全部超额，此时**不要继续配置**，先让人类补 key。

---

## 2. 设置令牌环境变量（持久）

Windows（PowerShell，只影响当前用户，不需要管理员）：

```powershell
[Environment]::SetEnvironmentVariable('OPENCODE_RELAY_KEY','<RELAY_TOKEN>','User')
```

Linux / macOS：写进 shell 配置文件，不要只在当前 shell `export`：

```bash
printf '\nexport OPENCODE_RELAY_KEY=%q\n' '<RELAY_TOKEN>' >> ~/.bashrc
```

**验证（关键，AI 常漏）**：环境变量是**新进程**才生效的。必须重开一个终端再查：

```powershell
# Windows
[Environment]::GetEnvironmentVariable('OPENCODE_RELAY_KEY','User')
```
```bash
# Linux/macOS（新开 shell 后）
echo "${OPENCODE_RELAY_KEY:+set}"
```

**预期：Windows 返回那串令牌；POSIX 返回 `set`。** 如果为空，opencode 会在本地就报
`Invalid credential`，与中继无关 —— 先修好这一步再往下。

---

## 3. 先读当前配置，再决定怎么改

```bash
python -c "import json,os;d=json.load(open(os.path.expanduser('~/.config/opencode/opencode.json'),encoding='utf-8'));print(list(d.get('providers',{}).keys()));print(d.get('model'));print(d.get('disabled_providers'))"
```

三种情况：

- **已经有 `relay-go`** → 不要重写整个文件。只按 §5 补 `env` / 模型开关。
- **没有 relay provider** → 按 §4 新增。
- **配置坏了 / 是空文件** → 【STOP】问人类是否恢复备份：
  `~/.local/share/opencode/config-backups/` 里按时间存着历史版本。

当前服务器上白名单里的 Go 模型（中继会拦掉不在名单里的）：

| 模型 id | 说明 | 2026-10-29 实测 |
|---|---|---|
| `mimo-v2.6-flash` | 主力，忙时的兜底目标 | `200` ✅ |
| `deepseek-v4.1-flash` | 高峰会被改写到 mimo（§7） | `200` ✅ |
| `longcat-2.5-preview-free` | 在白名单内，但上游当前拒绝 | `400` ⚠️ 别设成默认模型 |

---

## 4. 新增 relay provider（最小可用）

把下面这段**合并**进 `opencode.json` 的 `providers` 对象。不要覆盖整个文件，
文件里通常还有人类自己写的其它设置。

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "disabled_providers": ["opencode-go"],
  "model": "relay-go/mimo-v2.6-flash",
  "providers": {
    "relay-go": {
      "name": "OpenCode Go (relay)",
      "env": ["OPENCODE_RELAY_KEY"],
      "package": "@opencode/ai/providers/openai-compatible",
      "settings": { "baseURL": "http://8.153.153.176/v1" },
      "models": {
        "mimo-v2.6-flash": {
          "name": "MiMo v2.6 Flash",
          "headers": { "X-Relay-Model": "mimo-v2.6-flash" },
          "limit": { "context": 262144 }
        },
        "deepseek-v4.1-flash": {
          "name": "DeepSeek v4.1 Flash",
          "headers": { "X-Relay-Model": "deepseek-v4.1-flash" },
          "limit": { "context": 1000000, "output": 384000 }
        }
      }
    }
  }
}
```

### 约束

1. **provider id 不能叫 `opencode-go` 或 `opencode`。** 撞上 opencode 内置目录的 id 时，
   它会去解析内置 provider 的 OAuth 认证，请求还没发出去就在本地失败：
   `Upstream request failed: Invalid credential`。所以要 `relay-go` 这种不撞的名字。
2. **`disabled_providers: ["opencode-go"]` 必须保留。** 不禁用内置 Go，两条 Go 线路会同时
   出现在选择里，选了内置那条就绕开中继、直接吃官方配额。
3. **每个 model 都要写 `headers: {"X-Relay-Model": "<id>"}`。** 中继的访问日志和用量统计靠这个
   头辨认模型；没有它，`/stats/` 面板上你的用量会是空的。
4. **要接 Zen 线路时，`relay-zen` 必须带 `canonical: "opencode"`**，同时保留 `env` 指向
   `OPENCODE_RELAY_KEY`；缺 `canonical` 会拿不到 opencode 目录里的模型元数据。

---

## 5. 关闭不该用的模型

opencode 会把配置里所有 provider 的模型混在一个列表里。已经不能用的模型留在那里，
只会让人类选到一个必然失败的模型。对不能用的模型加 `"disabled": true`：

```jsonc
"deepseek-v4-pro": { "name": "DeepSeek V4 Pro", "disabled": true }
```

**不要删条目**，只加 `disabled`：这个文件由 §6 的同步脚本按中继上报重新生成，
保留结构才能对比出变化。

---

## 6. 有同步脚本时优先用它，别手改

如果本机已经存在这两个脚本，说明这台机器已经纳管，**按脚本走，不要手工编辑配置**：

```
~/.config/opencode/relay/sync_models.py     # 按中继能力上报重写 opencode.json
~/.config/opencode/relay/client_guard.py    # 自愈：修陈旧模型引用/配置漂移/卡死的服务
~/.config/opencode/relay/verify.json        # 每个模型的实测结论
```

手动跑一次同步与校验：

```bash
python ~/.config/opencode/relay/sync_models.py
python ~/.config/opencode/relay/client_guard.py
```

脚本行为要点（：

- 只有**流式实测通过**的模型才会被启用；中继上报里带 per-protocol 结果，
  仅支持 `/responses` 或 `/messages` 的模型不会出现在 chat 列表里。
- **备份一律写在配置目录之外**（`~/.local/share/opencode/config-backups/`）。
  配置目录是被 watch 的，往里放新文件会触发一次配置重载，可能把正在跑的 CLI 直接带走。
- 配置是**原子替换**（写临时文件再 rename），中途断电不会留下半个 JSON。
- `client_guard.py` 每 10 分钟和每次登录时跑；它的结论写在 `relay/client-guard.state.json`。

【STOP】脚本不存在或跑失败时，问人类是否要按 §4 手工配置，不要自己造一个同步脚本。

---

## 7. 忙时改写

中继在工作日高峰把 deepseek 请求改到 mimo 上，因为那个时段 deepseek 容量会退化。

| 项 | 值 |
|---|---|
| 生效时段 | **北京时间 周一~周五 09:00–12:00、14:00–18:00**（`12:00`/`18:00` 不含） |
| 生效线路 | 只有 `relay-go`（`/v1`）。Zen / GLM / MiMo 三条线路**不改写** |
| 改写规则 | 请求的 model 以 `deepseek` 开头 → 改为 `mimo-v2.6-flash` |
| 其它模型 | 原样透传，包括 `longcat-*`、`mimo-*` |
| 时段外 / 周末 | 完全不改写 |

AI 需要知道的三个表现：

1. **响应会多两个头**：`X-Relay-Rewritten-From: deepseek-v4.1-flash`、
   `X-Relay-Rewritten-To: mimo-v2.6-flash`。HTTP 状态仍是 `200`，正文正常。
2. **opencode 界面上显示的仍然是用户选的那个模型**（改写发生在服务端）。
   所以"显示 deepseek、行为像 mimo"不是 bug。要确认就看头。
3. **用量统计里记的是实际服务的那个模型**，`raw` 字段带 `rewritten_from`。
   账单/统计对不上时，先看这个字段。

4. **改写的前提是 tap 能解析请求体。** 请求体不是合法 JSON（或含非法 UTF-8 字节）时，
   `model` 取不到，**改写和白名单校验都会被跳过，请求原样透传**。这是有意的 fail-open：
   中继宁可放过一条策略，也不该把用户的正常请求打挂。
   实际影响：**Windows 的命令行会把 `-d` 里的中文按控制台代码页转成非法 UTF-8 字节**，
   于是同一条 curl 在 Git Bash 里"看起来没触发改写"，而 ASCII 请求体正常触发。
   做验证时用 ASCII 提示词，或把请求体写成文件再 `-d @body.json`。

命令行验证（在服务器上，人类执行）：

```bash
python3 /opt/relay/busy_hours.py status      # 现在是不是忙时、策略是什么
python3 /opt/relay/busy_hours.py check 10:30 # 预览那个时刻会不会改写
```

临时停用（不重启服务、不改配置文件）：`python3 /opt/relay/busy_hours.py off`，恢复 `on`。
策略文件 `/etc/relay/busy-hours.json` 改完自动重载，无需重启 `relay-usagetap`。

---

## 8. 验证清单（全部做完才算配好）

按顺序跑，逐条对比预期。

```bash
# 1. 配置是合法 JSON
python -c "import json,os;json.load(open(os.path.expanduser('~/.config/opencode/opencode.json'),encoding='utf-8'));print('JSON OK')"

# 2. 中继线路的模型出现在列表里
opencode models | grep -E '^relay-(go|zen)/'

# 3. 直连中继（不带 opencode）。提示词保持 ASCII：见 §7 第 4 点的 UTF-8 陷阱
curl -s -D - -o /tmp/relay-check.json -X POST http://8.153.153.176/v1/chat/completions \
  -H "Authorization: Bearer $OPENCODE_RELAY_KEY" -H 'Content-Type: application/json' \
  -H 'X-Relay-Model: deepseek-v4.1-flash' \
  -d '{"model":"deepseek-v4.1-flash","messages":[{"role":"user","content":"reply with one word: ok"}],"max_tokens":8,"stream":false}'
```

| # | 预期 | 不对时怎么办 |
|---|---|---|
| 1 | `JSON OK` | 你在写 JSON 时漏了逗号/多写了注释。`opencode.json` 允许 `//` 注释，但 `json.load` 不允许 —— 这条只对**纯 JSON**成立；若文件确有注释，改用 `opencode` 自身报错为准 |
| 2 | 至少两行 `relay-go/...` | provider 名字或 `models` 结构写错了；见 §4 约束 1 |
| 3 | `HTTP 200` + 响应体里有 `choices` | 用 §1 的状态码表定位：401 令牌 / 403 白名单 / 503 超额 |

第 3 步在**忙时时段内**（§7）还应看到这两个响应头，它们是策略生效的唯一外部证据：

```
X-Relay-Rewritten-From: deepseek-v4.1-flash
X-Relay-Rewritten-To: mimo-v2.6-flash
```

且响应体里的 `"model"` 是 `mimo-v2.6-flash`。时段外这两个头不出现、`"model"` 是 deepseek ——
**那也是正确行为**，别把"没改写"当故障。看到 Cloudflare 的 `error code: 1010` 是被上游
按客户端指纹拦了，用 curl 的默认 UA 重试即可，与中继策略无关。

最后让**人类**在 opencode 里实际发一句话（`/models` 选 `relay-go/mimo-v2.6-flash`）。
看到回答即完成。

> **安全提示**：中继是明文 HTTP，令牌在网络上裸跑。这条链路只适合你控制的机器；
> 换机器或加公网链路前先给 `/v1` 套 TLS（`install.sh --nginx-standalone` + certbot）。

---

## 9. 常见故障对照

| 现象 | 原因 | 处理 |
|---|---|---|
| `Invalid credential`，且**没有请求发到服务器** | provider id 撞了内置 `opencode-go`；或 `OPENCODE_RELAY_KEY` 在新终端里仍为空 | 见 §4 约束 1、§2 的重开终端验证 |
| 打开某个历史会话就整个 CLI 退出（"闪现"） | opencode 按会话存了模型引用，provider 改名/套餐收窄后那个引用解析不了 | 跑 `client_guard.py`（它就是修这个的） |
| 所有请求本地失败 | 同步脚本按一份空/坏的上报重写了配置，provider 里没有可用模型 | 从 `config-backups/` 恢复，再重跑同步 |
| `Managed service port ... already in use` | 后台服务占着端口但客户端连不上 | `client_guard.py` 会重启卡死的服务 |
| `403 model_not_allowed` | 模型不在 Go 套餐白名单 | 换 `mimo-v2.6-flash` / `deepseek-v4.1-flash`，或改用 Zen/GLM/MiMo 线路 |
| `503 relay_all_keys_exhausted` | 中继侧所有 key 的 5 小时/周/月额度用尽 | 人类在中继 dashboard 补 key，或等窗口重置。这不是配置错误 |
| 统计页 `/stats/` 用量为空 | 模型条目没写 `X-Relay-Model` 头 | 见 §4 约束 3 |

---

## 10. AI 在本文档里的行为边界

- **不得**修改服务器上的任何东西。本文档只动本机配置。
- **不得**把令牌写进仓库、日志、命令行历史以外的地方，也不得回显给对话。
- **不得**为了让 `§8` 的 `json.load` 通过而删掉用户写的 `//` 注释。
- **不得**自行扩大白名单、改 `/etc/relay/go-allowlist.json`、或改忙时策略。
- 遇到本文档没覆盖的报错，**报告原文并停下**，不要试式修改生产配置。
