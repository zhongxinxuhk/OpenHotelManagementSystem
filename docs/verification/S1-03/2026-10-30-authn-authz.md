# S1-03 验证记录：最小认证、授权与租户隔离

> 日期：2026-10-30（AEST）；轮次：`R-20261030-04`；决策：[ADR-016](../adr/ADR-016-authn-authz-tenant-isolation.md)。
>
> 结论：**本任务通过**。认证、授权、租户隔离与撤权失效均有真实 PostgreSQL 证据；未覆盖项见 §5，不计入通过范围。

## 1. 环境

| 项 | 值 |
| --- | --- |
| JDK | 21（`java.version=21`） |
| Spring Boot | 4.1.1（Jackson 3） |
| PostgreSQL | 17，独立一次性集群（`scripts/dev-db.sh`，端口 55432，trust 认证） |
| 数据库账号 | 迁移 `pms_migrator`（DDL） / 应用 `pms_app`（仅 DML） |
| 迁移版本 | V1（组织基线）+ V2（身份基线，本轮新增） |
| 档案 | `integration`（真实库，`PMS_IT_DB=true`）与 `test`（无库，跳过集成用例） |

## 2. 门禁命令与实际输出

```bash
# ① 主源码编译
cd openbackupsaas && ./mvnw -o -B -ntp -q -DskipTests compile
# → 退出码 0（无输出即无编译错误）

# ② 全量测试（真实 PostgreSQL）
PMS_IT_DB=true ./mvnw -B -ntp test
# → Tests run: 52, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
#   其中本轮新增 32 个：
#     AuthIntegrationTest            15
#     AuthApiTest                     6
#     SessionTokensTest               5
#     TenantContextHolderTest         6

# ③ 无数据库环境下不得伪造通过
./mvnw -B -ntp test
# → Tests run: 52, Failures: 0, Errors: 0, Skipped: 33 → BUILD SUCCESS
#   33 = 两个集成测试类整类跳过（15+6） + OrganizationIntegrationTest 12 例

# ④ 交付门禁
PMS_IT_DB=true ./mvnw -o -B -ntp verify
# → Tests run: 52, Failures: 0, Errors: 0 → BUILD SUCCESS（10.7s）
```

## 3. 运行态冒烟（真实 HTTP，非 MockMvc）

`local` 档案 + 一次性集群，端口 18081；用手工造的租户/门店/账号（口令 `password`）走完整链路：

| # | 请求 | 期望 | 实际 |
| --- | --- | --- | --- |
| 1 | `GET /actuator/health` | 200 | `200` |
| 2 | `GET /api/v1/auth/me`（无令牌） | 401 | `401`（`title=未认证`，带 `X-Request-Id`） |
| 3 | `POST /api/v1/auth/login`（正确口令） | 200 + token | `200`，返回 43 字符令牌、`expiresAt`（+8h）、`hotelIds`、`permissions` |
| 4 | `GET /api/v1/auth/me`（带令牌） | 200 | `200`，`tenantId` 与会话一致 |
| 5 | `GET /api/v1/identity/users/{本人}/hotel-scopes` | 200 | `200 ["22222222-…"]` |
| 6 | 同上，换不存在的 userId | 404 | `404` |
| 7 | `POST /api/v1/auth/logout` 后再访问 `/me` | 204 → 401 | `204` → `401` |
| 8 | `SELECT token_hash, revoke_reason FROM user_session` | 64 位十六进制、`LOGOUT` | `len=64`、`revoke_reason=LOGOUT` |

冒烟产生的数据已在结束后清除（租户编码 `smoke` 及其下全部行）。

## 4. 验收项对照

| 规划 S1-03 验收项 | 证据 | 结论 |
| --- | --- | --- |
| 伪造 `tenant_id` 被拒 | 上下文中租户只由服务端会话推导；`Authz.tenantId()` 不读取任何请求参数（`forgedTenantIdInRequestCannotWidenScope`） | 通过（服务层；尚无接受 `tenant_id` 的业务接口，HTTP 层越权回归集归 B-08 / S1-13） |
| 跨店对象 ID 被拒 | `hotelScopeDeniesForeignHotel`、`permittedUserReadsOwnTenantScopes` | 通过 |
| 批量混入其他租户对象被拒 | `batchWithOneForeignHotelIsRejectedWholesale`（整体拒绝，且空集合不等于全部） | 通过 |
| 连接复用不串租户 | `threadReuseDoesNotCarryTenantContext`、`explicitTaskContextDoesNotLeakIntoCaller`、`TenantContextHolderTest`（6 例，含异常路径恢复） | 通过（应用上下文层面；未启用 RLS，故无会话变量清理问题，理由见 ADR-016 §3） |
| 撤权后旧会话按约定失效 | 登出：`logoutRevokesOnlyCurrentSession`；改密：`changePasswordRevokesOtherSessionsButKeepsCurrent`；账号停用：`disabledUserCannotLoginAndLosesExistingSession`；租户停用：`suspendedTenantInvalidatesLiveSessions`；角色停用：`revokedRoleLosesPermissionsImmediately`；过期：`expiredSessionIsRejected` | 通过（约定：撤销/停用＝下一个请求即失效；改密保留当前会话，其余立即失效） |
| 默认拒绝未授权写入 | `missingPermissionGets403`（`@PreAuthorize` 生效）、`unauthenticatedRequestGetsProblemDetail401`；安全链 `anyRequest().authenticated()` | 通过 |

## 5. 未覆盖（不得据此宣称通过）

| 项 | 原因 | 归属任务 |
| --- | --- | --- |
| 并发下同账号多会话、同会话并发请求 | 需要并发夹具与竞争注入 | S1-13 |
| 连接池归还后租户残留 | 未启用 RLS，当前无会话变量可残留；若启用 RLS 需重新验证 | ADR-016 §3.1 触发条件 |
| 登录失败锁定 / 限流 / 验证码 | 未实现，阈值属安全策略 | S6-05 |
| 审计落库（登录、越权、改密事件） | 当前只写应用日志，`audit_log` 在 S1-06 | S1-06 / B-05 |
| 前端登录页与会话保持 | 前端未接入（S3-01a 依赖安装仍 BLOCKED） | S3-01、S3-02 |
| 会话过期行归档与清理 | 未做 | S6-03 |
| CI 实跑 | 本地全绿，GitHub Actions 未观测 | 遗留（S1-01 同一缺口） |

## 6. 本轮发现（已处理 / 转下轮）

1. **`GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 会吞掉 `AccessDeniedException`。** 若不加显式 403 处理器，`@PreAuthorize` 拒绝会被当成未预期异常返回 500——权限失败被误报为服务故障，前端 401/403 分支永远走不到。已补 `AccessDeniedException` / `UnauthenticatedException` / `NotFoundException` 三个处理器。
2. **过滤器注册成 `@Component` 会执行两次。** Boot 会把容器内每个 `Filter` Bean 自动注册到 Servlet 容器，同时又 `addFilterBefore` 进安全链。已改为在 `SecurityConfig` 内手动构造，只用安全链装载。
3. **`ck_user_session_expiry`（`expires_at > issued_at`）会挡住"只改过期时间"的测试写法。** 该约束本身是对的（不允许签发即过期的畸形会话），测试改为同时前移 `issued_at`。
4. **BCrypt 陪跑哈希只用于抹平耗时，不代表任何真实口令。** 冒烟时曾误以为它是 `password` 的哈希，实际不匹配；正确做法是用 `BCryptPasswordEncoder.encode` 现算（本轮冒烟即如此）。
5. `user_session.last_seen_at` 未做逐请求更新：逐请求写会让每个 GET 变成一次写事务。闲置超时定案前不实现（见 ADR-016 §4）。
6. 每个已认证请求 3 次查询（会话联表 + 权限点 + 门店范围）。未加缓存，等 S2-06 监控有 P95 数据后再决定，避免用"先加缓存"掩盖问题。
