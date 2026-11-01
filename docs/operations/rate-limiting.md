# 限流与防刷运维说明

> 建立日期：2026-11-01（AEST）；对应轮次：R-20261101-34（J-05）；
> 设计决策见 [ADR-020](../adr/ADR-020-rate-limiting-and-anti-abuse.md)。

## 1. 一句话

匿名入口（注册、登录、发码、确认码、公开面查询）有额度上限，超了返回
**429 + `Retry-After`**。这不是故障，是闸门在工作。

## 2. 生效档位（启动日志里也有一份）

`RateLimitRules` 在启动时打印：

```
限流档位生效（9 条）：[guest.register=5/PT10M, guest.login=10/PT5M, ...]
```

| 档位 | 路径 | 默认 |
| --- | --- | --- |
| `guest.register` | `POST /api/v1/guest/auth/register` | 5 / 10 分钟 |
| `guest.login` | `POST /api/v1/guest/auth/login` | 10 / 5 分钟 |
| `guest.binding.challenge` | `POST /api/v1/guest/auth/bindings/challenge` | 5 / 10 分钟 |
| `guest.binding.confirm` | `POST /api/v1/guest/auth/bindings/confirm` | 10 / 10 分钟 |
| `guest.binding.username` | `POST /api/v1/guest/auth/bindings/username` | 10 / 10 分钟 |
| `guest.auth` | 其余 `/api/v1/guest/auth/**` | 60 / 1 分钟 |
| `staff.login` | `POST /api/v1/auth/login` | 20 / 5 分钟 |
| `platform.login` | `POST /api/v1/platform/auth/login` | 5 / 5 分钟 |
| `public.catalog` | `GET /api/v1/public/**` | 60 / 1 分钟 |

**已登录的业务接口不限流**，理由见 ADR-020 §2.5（批量导出/夜审会被误伤，
且已登录侧的处置手段是停用账号）。若确实需要，属于风控范畴，另立一轮。

## 3. 三个开关

| 配置 | 默认 | 用途 |
| --- | --- | --- |
| `pms.rate-limit.enabled` | `true` | 总开关。事故时关（例如限额被误配成 1，全站 429） |
| `pms.rate-limit.trusted-proxies` | 空 | **安全参数**，见 §4 |
| `pms.rate-limit.max-keys` | `50000` | 令牌桶数量上限（内存护栏） |

单条覆盖（改完需重启）：

```
pms.rate-limit.rules.guest.login.permits=10
pms.rate-limit.rules.guest.login.window=PT5M
```

或用环境变量：`PMS_RATE_LIMIT_RULES_GUEST_LOGIN_PERMITS=10`。

## 4. 部署在反代后面：必须配 `trusted-proxies`

默认**谁都不信** `X-Forwarded-For`，只用 TCP 对端地址。这在后端直连时是对的；
但挂在 nginx / Caddy / 云负载均衡后面时，对端永远是反代的地址——
**全站会被当成同一个客户端**，一个人打满额度，所有人都登录不了。

配置（本机反代）：

```
PMS_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1,::1
```

多实例 + 内网负载均衡：

```
PMS_RATE_LIMIT_TRUSTED_PROXIES=127.0.0.1,::1,10.0.0.0/8
```

规则：只填**你自己的代理**，绝不填 `0.0.0.0/0` 或客户端所在网段。
填错的方向性后果：

- 该配不配 → 全站共用一个桶（表现为"别人被限流，我也被限流"）；
- 配得过宽 → 客户端可伪造来源，**限额等于没有**（日志里每"个"来源只出现一两次）。

另外确认反代自己**覆盖**而不是追加这个头：

```nginx
proxy_set_header X-Forwarded-For $remote_addr;   # 覆盖
# proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;  # 追加也可以，
# 但前提是本机地址已在 trusted-proxies 里登记（服务端会从右往左跳过可信跳）
```

## 5. 被刷时怎么处置

1. **确认是限流在起作用**：`pms.security.rate_limited` 按 `rule` 分组的曲线在涨。
   涨幅集中在哪个档位，就说明被打的是哪个入口。
2. **定位来源**：日志里搜 `命中限流`，每条带 `rule` / `client` / `path` / `retryAfter` / `requestId`。
   注意 `client` 是否可信取决于 §4 的配置——如果 `trusted-proxies` 没配，
   这里全是反代地址，没有定位价值。
3. **临时收紧**：改 `pms.rate-limit.rules.<档位>.permits` → 滚动重启。
   收紧只影响新进程，已存在的桶在重启后清零（这也意味着收紧前被打的那一波不会"记忆"）。
4. **仍被打穿**（换地址池）：应用层限流到这一步就结束了，接下来是网关/WAF 的活
   （按 ASN/地理/IP 段封禁、人机校验）。应用层不该做 IP 黑名单——
   它需要一个分布式黑名单存储，那正是 ADR-011 里暂缓的东西。
5. **需要全站放行**（误配导致大面积 429）：`PMS_RATE_LIMIT_ENABLED=false` 重启，
   先恢复服务，再回来修档位。

## 6. 常见误判

| 现象 | 实际原因 |
| --- | --- |
| 本地反复登录被 429 | `application-local.properties` 已把登录档位放大到 100–200/5 分钟；仍不够就 `PMS_RATE_LIMIT_ENABLED=false` |
| 集成测试里 429 | 只在 `RateLimitEnforcementTest` 里应出现（它自带 `@TestPropertySource` 压小额度）。其它集成档案显式关闭限流——见 `application-integration.properties` 的注释 |
| 前端提示"操作过于频繁"但人只点了一次 | 检查是否有一批并发请求同时打到同一个档位；也可能 §4 把全站算成了一个客户端 |
| 429 之后 "登录状态失效" | 不该发生：前端 429 分支不清会话、不跳登录页（`requestErrorConfig.ts`） |

## 7. 不要做的事

- **不要为了让端到端脚本跑通而调大生产档位**。E2E 的登录次数远高于真人；
  该改的是脚本（复用会话）或本地档案（`application-local.properties`）。
- **不要把限流当配额**。它是防刷，不是计费：重启清零、实例间不共享
  （ADR-020 §3）。按门店/按账号的用量控制要另建。
- **不要在限流里放高基数标签**。指标 `pms.security.rate_limited` 只有 `rule` 一个标签；
  想按来源看趋势请看日志，直接加 `client` 标签会让 Prometheus 先被打爆。
