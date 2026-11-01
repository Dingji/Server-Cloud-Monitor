# 给别的机器接上这台 AI 中继 —— 两步

配套文件：[`opencode.json`](opencode.json)。它是从已验证可用的本机配置里**筛出来**的，
只保留中继的两条线路，并且把当前**必然失败**的模型预先 `disabled`（Zen 线路未开通，逐模型 402）。

> 完整排障手册见 [docs/guide/opencode-relay-client.md](../../docs/guide/opencode-relay-client.md)。
> 那份是"交给 AI 直接执行"的版本；这里只讲最小安装。

---

## 1. 放配置文件

路径规则 Windows 与 Linux/macOS 相同：

```
~/.config/opencode/opencode.json
```

**如果目标机器上这个文件已经存在且有别人写的设置，不要整文件覆盖** —— 只把
`providers.relay-go` / `providers.relay-zen` 两段合并进去，并保留
`"disabled_providers": ["opencode-go"]` 与 `"model": "relay-go/mimo-v2.6-flash"`。

## 2. 设分发 key（每台一枚，别共用）

key 由发放人在面板 **OpenCode 中继 → 分发 Key** 里生成，明文只在生成那一次显示。
**key 不写进配置文件，也不入库、不进 shell 历史之外的地方。**

Windows（PowerShell，只影响当前用户，不需要管理员）：

```powershell
[Environment]::SetEnvironmentVariable('OPENCODE_RELAY_KEY','rk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx','User')
```

Linux / macOS（写进 shell 配置，别只在当前 shell `export`）：

```bash
printf '\nexport OPENCODE_RELAY_KEY=%q\n' 'rk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' >> ~/.bashrc
```

环境变量只对**新开的进程**生效。但"关掉 opencode 窗口"不等于退干净 —— Windows 上
`opencode.exe` 的后台进程会残留并继续拿着旧 key 出门（2026-10-31 实测：窗口关了，
两个 daemon 还活着，换 key 后所有流量照旧记在旧 key 名下）。设完 key 先确认进程清零：

```powershell
tasklist | findstr /i opencode    # 必须为空；不为空就 taskkill /F /IM opencode.exe
```

Linux / macOS：`pkill -f opencode`。

## 3. 验证（四条都过才算装好）

```bash
opencode --version                                   # 期望 v2.1.4（中继按这个版本验证过）
opencode models | grep -E '^relay-(go|zen)/'          # 期望只有 relay-go 两行可选
curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST http://8.153.153.176/v1/chat/completions \
  -H "Authorization: Bearer $OPENCODE_RELAY_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"mimo-v2.6-flash","messages":[{"role":"user","content":"say ok"}],"max_tokens":8}'
```

最后让使用方在 opencode 里选 `relay-go/mimo-v2.6-flash` 发一句话，看到回答后**还要做第 4 条**。

**4. 记账归属核验（最关键，前三条都抓不住它）**：发放人在面板 **分发 Key** 里确认
这枚 key 的用量在涨。原因：旧的全局令牌（`ck_master`）不限额且一直有效 —— 哪怕
opencode 实际还在用旧 key，curl 探测、模型列表、正常回答三条也全是绿的。唯一能
暴露"账记在谁头上"的就是面板里的用量数字（实测：三绿，但一周 2700 万 token 全记在
master 名下）。用量不动时按这个顺序找残留：后台进程（上面第 2 步）→
opencode 的凭据库 `~/.local/share/opencode/opencode.db` 的 `credential` 表
（integration `opencode-go`，存过 `opencode auth login` 的机器会压着一枚旧 key）。

状态码含义：`200` 正常；`401` 这枚 key 不存在或已被停用；`403` 模型不在 Go 套餐白名单；
`429` 这枚 key 的时/周/月额度到顶（响应体的 `error.message` 会写哪个窗口、何时恢复，
**这不是故障，别去改客户端配置**）；`503` 上游套餐 key 全部超额，要找发放人补 key。

> 注意：`ck_master`（内部兜底令牌）在面板里永远显示不出用量 —— 不限额的 key 压根不查
> `used`，这是设计行为，不是显示坏了。看到"有流量但面板没数字"，先查客户端是不是还在用 master。

---

## 装完之后会看到的两个现象（都是正常的）

1. **忙时/闲时会自动换模型。** 工作日北京时间 09:00–12:00、14:00–18:00 把 deepseek 请求改到
   mimo；其余时间这条线路的请求改到 deepseek-v4.1-flash。客户端界面显示的仍是用户选的模型，
   改写发生在服务端。要确认就看响应头 `X-Relay-Rewritten-From / -To / -Policy`。
2. **额度按 key 计 token**，时=滚动 1 小时、周=本周（周一 00:00 起）、月=本月（1 号 00:00 起），
   命中缓存的输入不计。发放人在面板里能实时看到每枚 key 用了多少。

## 要接 Zen / GLM / MiMo 线路时

`relay-zen` 的 6 个模型已在配置里，只是全部 `disabled`（上游逐模型返回 `402`，账户未开通）。
套餐开通后把 `"disabled": true` 去掉即可，`canonical: "opencode"` 必须保留。
GLM 与 MiMo 两条线路（`/glm/v1`、`/mimo/v1`）当前上游配额耗尽，需要时再按手册 §4 的写法加 provider。
