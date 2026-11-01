# ADR-020：匿名入口的限流与防刷

- 状态：已接受（2026-11-01）
- 决策人：本项目（依据 ADR-017 §2.6「`/api/v1/public/**` 是唯一匿名前缀，
  这条规则存在的目的之一就是用一条规则做限流/WAF/监控/开关」）
- 关联：[ADR-011](./ADR-011-technology-baseline.md)（Redis 暂缓）、
  [ADR-017](./ADR-017-multi-surface-and-identity-planes.md)（多端与身份平面）、
  [ADR-019](./ADR-019-plane-isolation-and-manual-verification.md)（平面隔离）

## 1. 背景

到 R-20261101-33 为止，匿名可写的入口已经有六个：

| 入口 | 一次请求的代价 |
| --- | --- |
| `POST /api/v1/guest/auth/register` | 一次 BCrypt + 一行账户数据 |
| `POST /api/v1/guest/auth/login` | 一次 BCrypt + 一次会话写 |
| `POST /api/v1/guest/auth/bindings/challenge` | 一条**要花钱的**短信/邮件 + 挑战行 |
| `POST /api/v1/guest/auth/bindings/confirm` | 一次验证码比对（5 次上限在挑战行上） |
| `POST /api/v1/auth/login` / `POST /api/v1/platform/auth/login` | 一次 BCrypt + 会话写 |
| `GET /api/v1/public/{tenantCode}/hotels/{hotelCode}/room-types` | 一次房态查询（默认关闭） |

`application.properties` 里"开启公开面前必须完成 J-05"这句已经挂了两轮。
在没有闸门的情况下开放匿名面，等价于：

- **短信账单可被外部驱动**——发码接口每被调用一次就花一次钱；
- **库存与价格可被爬成一张表**——房态查询天然是"把全站房态导出来"的接口；
- **数据库在被打时最忙**——上面每一个入口背后都有一次写或一次重查询。

需求方对匿名面的要求只有一句"匿名入口重点防护"，没有给出具体额度。
本轮把机制与档位定下来，额度是可覆盖的配置项。

## 2. 决策

### 2.1 令牌桶（惰性补充），不用固定窗口

额度的语义是「`window` 内最多 `permits` 次」。实现用令牌桶：
容量 = `permits`，补充速率 = `permits / window`，每次取用时按
「经过时间 × 速率」补令牌，**没有后台线程**。

拒绝固定窗口计数的理由很具体：它允许**边界双倍**——第 59 秒打满 10 次、
第 61 秒再打满 10 次，中间只隔两秒。对登录接口来说，那等于把
"10 次/分钟"变成"10 次/两秒"，而这正好是暴力破解最需要的形状。

没有后台线程的理由有两条：桶数量上来后全表扫描是纯浪费；
定时器会让"这一秒补了多少"变得无法断言——测试只能靠 `sleep`，最后一定被调宽。

### 2.2 闸门放在安全链**之前**

`RateLimitFilter` 的 order 是 `HIGHEST_PRECEDENCE + 10`，排在 `RequestIdFilter`
之后、Spring Security 之前。原因不是"更早更安全"，而是**省成本**：
带令牌的每个请求都要查一次会话表（权限还是每请求实时查库），
登录请求还要跑一次 BCrypt。放在它们后面，被刷时数据库照旧全速运转，
限流只挡住了业务代码那一小段。

### 2.3 路径必须用 MVC 的视角取，不能用 `getRequestURI()`

`rateLimitFilter` 通过 `UrlPathHelper#getPathWithinApplication` 取路径，
与 MVC 走同一套「解码 + 去矩阵参数 + 去上下文路径」规则。

**这是一条绕过防线**：`getRequestURI()` 返回的是未解码的原始 URI。
用它做匹配时，`/api/v1/guest/auth/%6Cogin` 在 MVC 里命中登录接口（解码后是 `login`），
在限流器眼里却是个陌生路径 → 不限流、不报错、日志里也看不出异常。
单元测试 `RateLimitFilterTest#pathVariantsThatMvcWouldStillRouteAreAlsoLimited`
把这个形状钉死了。

### 2.4 客户端身份默认只信 TCP 对端

`X-Forwarded-For` 是客户端可随便写的头。**无条件采信它，限流等于没做**：
每个请求换一个假地址就得到无限额度，而日志里每一"个"来源都只出现一两次，
事后排查还会得出"没被打"的结论。

规则：

1. 默认 `pms.rate-limit.trusted-proxies` 为空 → **谁都不信**，只用 `remote_addr`；
2. 只有对端命中可信代理 CIDR 时才读转发头，且**从右往左**走
   （最右侧是离服务端最近的一跳），跳过可信代理，第一个不可信地址才是客户端；
3. 地址一律规范化为 `InetAddress#getHostAddress`，`::1` 与 `0:0:0:0:0:0:0:1`
   是同一个键——否则改个写法就换一个新桶；
4. 可信代理配置只接受 **IP 字面量或 CIDR**，主机名在启动期直接失败。
   `InetAddress#getByName` 会为主机名做 DNS，写错一个字母的表现是"启动卡住"，
   与"配置写错了"看起来毫无关系。

部署在本机反代后面时必须显式登记（`127.0.0.1,::1`）；这一项配错的后果是
"所有人都自带无限额度"，属于**安全参数**而非性能参数，因此写在
`application.properties` 的注释里、而不是只写在运维手册。

### 2.5 档位：越贵的动作越严

| 档位 | 匹配 | 默认额度 |
| --- | --- | --- |
| `guest.register` | `POST /api/v1/guest/auth/register` | 5 / 10 分钟 |
| `guest.login` | `POST /api/v1/guest/auth/login` | 10 / 5 分钟 |
| `guest.binding.challenge` | `POST …/bindings/challenge` | 5 / 10 分钟 |
| `guest.binding.confirm` | `POST …/bindings/confirm` | 10 / 10 分钟 |
| `guest.binding.username` | `POST …/bindings/username` | 10 / 10 分钟 |
| `guest.auth` | 其余 `/api/v1/guest/auth/**` | 60 / 1 分钟 |
| `staff.login` | `POST /api/v1/auth/login` | 20 / 5 分钟 |
| `platform.login` | `POST /api/v1/platform/auth/login` | 5 / 5 分钟 |
| `public.catalog` | `GET /api/v1/public/**` | 60 / 1 分钟 |

三条定档理由：

- **发码最严（5/10 分钟）**：这条挡的不是"刷页面"，是短信账单与对第三方的骚扰。
- **员工登录比住客松（20 对 10）**：同一出口 IP 后面往往是一整个前台（NAT），
  换班、多终端同时登录是正常现象。把员工登录按住客的额度卡，
  第一个投诉会来自"早上交接班时前台登不上"。
- **平台登录最严（5/5 分钟）**：低频特权账号，限额没有误伤风险。

**已登录接口本轮不定档**。理由不是"以后再说"，而是：有身份就有可追责的账号，
真正的处置手段是停用账号而不是限速；而批量导出、夜审重算这类业务
天然是"一分钟里打很多次"，拍一条全局限额只会让人在生产上调大它——
一条注定要被调大的限额，等于没有限额。这块留给风控（依赖验证码通道与真实渠道）。

### 2.6 拒绝形态：429 + ProblemDetail + 可执行的重试时间

```
HTTP/1.1 429 Too Many Requests
Retry-After: 12
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 0
Content-Type: application/problem+json

{ "status": 429, "title": "请求过于频繁", "code": "RATE_LIMITED",
  "outcome": "REJECTED", "retryAfterSeconds": 12, "requestId": "…" }
```

- `Retry-After` **最小 1 秒**：`Retry-After: 0` 会让部分客户端原地重试，等于帮对方放大攻击。
- `outcome = REJECTED`（而不是 `UNKNOWN`）：这是"调用方该退避"，
  客户端不应去"查询原交易结果"。
- `X-RateLimit-*` 在**放行时也返回**：客户端可以提前退避，而不是撞上 429 才停。

### 2.7 键空间必须有界

桶的键含客户端地址。**若可信代理配错**（把不可信网段当代理），
攻击者每次伪造转发头就得到一个全新桶，无界 Map 直接把内存吃掉——
限流器自己变成拒绝服务的入口。两层保护：

1. 桶满（已完全补充 = 状态为零）即视为空闲可丢，丢弃无信息损失，每 5 秒扫一次；
2. 扫完仍超 `max-keys`（默认 50000），按迭代序淘汰活跃桶到 90% 容量并 WARN。
   代价是一小撮客户端被重置额度——这是有意选的：另一种结局是 OOM，
   服务对所有人不可用。

告警本身做了 60 秒节流：被刷时会持续越限，不限流的告警日志会成为新的洪水。

### 2.8 指标只有低基数标签

`pms.security.rate_limited{rule="guest.login"}`。**标签里不放客户端地址**：
一个 IP 一条时间序列，被刷时采集端先崩，而这张图本来要看的是"哪个入口被打"。
定位到具体来源看 WARN 日志（带 client 与 requestId）。

## 3. 已知边界（写在这里，不靠注释兜）

1. **计数不在进程间共享**。N 个实例的实际限额是配置值的 N 倍。
   当前部署形态是单实例（compose 中间件 + 裸机后端，见 `deploy/app.sh`），
   所以无差异；**横向扩容前必须先换实现**（Redis 在 ADR-011 里是暂缓项），
   或把限额按实例数均摊。`RateLimiter` 这个接口就是为这一步留的。
2. **重启即清零**。滚动重启期间限额短暂失效——对"防刷"可接受，
   对"配额"（按门店/按账号计费类）不可接受，后者不该建在这套机制上。
3. **改档位需要重启**（构造期解析）。事故期要立刻收紧，改环境变量 + 滚动重启；
   真正需要热更新的场景属于网关层，不在应用内做。
4. **只按来源地址一个维度**。同一 NAT 后的正常用户会共享额度，
   同一攻击者用地址池可以摊薄。前者靠"档位留余量"缓解，后者只能靠网关/WAF。
5. **公开面仍未开启**。IP 维度闸门是开启的前置条件之一，不是全部（还缺按门店配额、
   WAF 规则、被刷时的降级流程）。`pms.public-api.enabled` 保持 `false`。

## 4. 被否决的方案

| 方案 | 否决理由 |
| --- | --- |
| 用 PostgreSQL 做计数（每请求 upsert） | 在最需要限流的时刻（被打）给数据库加写压力，与限流的目的直接冲突 |
| 用 Redis 做计数 | ADR-011 把 Redis 列为暂缓；为了限流单独引入一个中间件，部署面与运维面都要扩，收益只在多实例时体现 |
| 网关层限流（nginx `limit_req`） | 现网拓扑里网关是可选组件（裸机直连也能跑），只做在网关层等于"部署方式决定有没有防护" |
| 固定窗口计数 | 边界双倍（见 §2.1） |
| 采信 `X-Forwarded-For` | 客户端可伪造，限流形同虚设（见 §2.4） |
| 给所有 `/api/**` 加一条全局限额 | 会误伤批量导出与夜审，最后一定被调大；且已登录接口的处置手段是停用账号 |

## 5. 契约与前端

本轮**不改动** `docs/api/openapi-v1.json`：429 是过滤器施加的横向规则，
不属于任何一个操作自身的响应形状；把它写进 64 个操作的契约需要
`OpenApiCustomizer` + 重新导出基线，收益不抵风险（一次基线重导会让
"契约变了"这件事失去信号）。429 的行为写在本文与 `docs/operations/` 的运维说明里。

前端处理约定（`merchant-web`）：429 与 401/403 分开——不跳登录页、不清令牌，
显示"操作太频繁"+ 剩余秒数。把它当 500 处理会让人以为服务挂了。

## 6. 落地清单

- 代码：`shared/ratelimit/`（`RateLimitFilter`、`RateLimitRules`、`TokenBucketRateLimiter`、
  `ClientIpResolver`、`IpRange`、`RateLimitSettings`、`RateLimitConfig`）
- 指标：`PmsMetrics#rateLimited`
- 配置：`pms.rate-limit.enabled` / `trusted-proxies` / `max-keys` /
  `rules.<名>.permits` / `rules.<名>.window`
- 测试：`TokenBucketRateLimiterTest`（7）、`ClientIpResolverTest`（9）、
  `RateLimitFilterTest`（9）、`RateLimitEnforcementTest`（5，真实库 + 真实安全链）
- 运维：`docs/operations/rate-limiting.md`
