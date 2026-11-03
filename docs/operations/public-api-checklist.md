# 匿名公开面开启 Checklist（J-02，R-20261103-02）

> 适用接口：`GET /api/v1/public/{tenantCode}/hotels/{hotelCode}/room-types?from=&to=`
> （全系统唯一匿名前缀 `/api/v1/public/**`，ADR-017 §2.6）。
> 开关：`pms.public-api.enabled`（环境变量 `PMS_PUBLIC_API_ENABLED`，默认 **false**；
> local 档案默认 true 供 C 端联调）。

## 0. 为什么默认关闭

一个可被无限刷的匿名查询接口就是一台免费的库存探测机。关闭时控制器不注册，
路径 404——对扫描者来说这个接口不存在，比"开着但 503"干净。

## 1. 开启前置（逐项确认，缺一不开）

| # | 项 | 状态 | 说明 |
| --- | --- | --- | --- |
| 1 | 应用层限流（IP 维度） | **已落地** | `public.catalog` 60/min（默认）；`pms.rate-limit.rules.public.catalog.*` 可覆盖 |
| 2 | **按门店配额** | **已落地**（R-20261103-02） | `public.catalog.hotel` 3000/min，桶键 = tenantCode/hotelCode **不含 IP**——对抗分布式换源抓取；与 IP 档位叠加（任一拒绝即 429） |
| 3 | 反代场景的客户端来源 | **部署时确认** | 部署在 nginx/LB 之后必须配 `PMS_RATE_LIMIT_TRUSTED_PROXIES`（只填自己的反代；填 `0.0.0.0/0` 等于让客户端伪造来源，限额失效） |
| 4 | 监控与告警接线 | **部署时确认** | `pms.security.rate_limited{rule}` 指标已暴露于 `/actuator/prometheus`；把 `rate_limited{rule="public.catalog.hotel"}` 与 `public.catalog` 接进告警（持续高位 = 正在被刷） |
| 5 | WAF / 网关侧封禁 | **部署侧实施** | 应用层限流挡"刷"，网关挡"打"（L7 洪水、恶意 UA、已知攻击源段）。建议至少：该前缀限速（低于应用层额度，如 30 req/s/IP）、异常 UA 拦截 |
| 6 | 档位校准 | **上线后校准** | 当前 60/min（IP）与 3000/min（店）是设计值；首周按真实流量复核——单店正常峰值应远低于店级额度 |

## 2. 开启步骤

```bash
# 1) 按上表逐项确认（3、4、5 是部署侧动作，不是代码）
# 2) 在 .env / 环境中开启（滚动重启生效）：
PMS_PUBLIC_API_ENABLED=true
# 3) 验证（预期 200 + X-RateLimit-* 头）：
curl -si "https://<host>/api/v1/public/<tenantCode>/hotels/<hotelCode>/room-types?from=2026-11-10&to=2026-11-11"
```

## 3. 被刷降级流程（按严重度递进）

1. **收紧档位**（不停服务）：`PMS_RATE_LIMIT_RULES_PUBLIC_CATALOG_PERMITS=<更小>`
   / `..._PUBLIC_CATALOG_HOTEL_PERMITS=<更小>`，重启应用。适合"持续高位但可服务"。
2. **关闭公开面**（应急）：`PMS_PUBLIC_API_ENABLED=false`，重启。路径回到 404，
   C 端房型浏览暂不可用——降级是**有意付出的代价**：匿名面宁可不可用，不可被刷。
   恢复同样只改开关。
3. **网关封禁**（配合 1/2）：对攻击源段在 WAF/nginx 层封禁——应用层的键空间
   不该被海量伪造来源撑大（`max-keys` 有界清理是兜底，不是常态手段）。

处置原则：**应用层限流是闸门，网关是盾牌**——被刷事件中先收紧闸门保住服务，
盾牌的封禁在网关侧做（应用不维护封禁名单，那是另一个状态空间）。

## 4. 回滚

`PMS_PUBLIC_API_ENABLED=false` + 重启。数据库无公开面专属状态，无需迁移回滚；
限流桶是进程内状态，重启即清零。

## 5. 已知边界（如实）

- 应用层限流是**单实例**的：多实例部署时额度按实例数放大（ADR-020 §5）；
  网关侧统一限速可部分收敛。
- 被拒请求仍会消耗前序档位的额度（分层限流独立计数尝试）——设计如此，宁可多算。
- 门店发现（匿名门店清单）尚未提供：C 端需通过带参数链接进入
  （`?tenant=&hotel=`，商家分享/二维码形态）。

> AI生成
