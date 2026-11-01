# 后端开发说明（Spring Boot 4.1.1 / Java 21）

> 版本：1.0；建立日期：2026-10-30（AEST）；对应任务：S1-01；技术基线：[ADR-011](../adr/ADR-011-technology-baseline.md)。
>
> 本文只写"怎么跑、怎么验、现在有什么、明确没有什么"。业务规则见 `docs/requirements` 与 `docs/domain`。

## 1. 环境要求

| 项 | 要求 | 本机实测 |
| --- | --- | --- |
| JDK | 21（Temurin） | 21.0.13 |
| Maven | 用 `./mvnw`，不装全局 Maven | Wrapper 3.9.x |
| PostgreSQL | 17.x（本地起服务与集成测试需要） | 尚未安装/未验证 |
| 端口 | 默认 8080（`PMS_SERVER_PORT` 可覆盖） | — |

## 2. 配置档案

| 档案 | 用途 | 数据库 | OpenAPI |
| --- | --- | --- | --- |
| `local`（默认） | 本地开发 | 需要，读环境变量 | 开启 |
| `test` | 单元/上下文测试 | **不需要**（排除 DataSource/Flyway/MyBatis 自动配置） | 关闭 |

环境变量（本地档案）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PMS_DB_HOST` | `localhost` | 数据库主机 |
| `PMS_DB_PORT` | `5432` | 端口 |
| `PMS_DB_NAME` | `pms` | 库名 |
| `PMS_DB_USER` | `pms_app` | 应用账号（**不得使用超级用户**） |
| `PMS_DB_PASSWORD` | 空 | 空密码会连接失败：不用假凭据掩盖环境缺失 |
| `PMS_DB_POOL_SIZE` | `10` | Hikari 最大连接 |
| `PMS_OPENAPI_ENABLED` | `true`（本地） | 生产必须为 `false`，文档入口不对外 |
| `PMS_SESSION_TTL` | `PT8H` | 会话有效期（ISO-8601 时长）；不做滑动续期，见 ADR-016 |
| `PMS_SERVER_PORT` | `8080` | HTTP 端口 |

版本库里**不允许**出现明文密码、令牌或生产连接串；秘密由部署环境注入（S6-05 复核）。

## 3. 本地启动

### 3.1 一句话起库（推荐）

```bash
cd openbackupsaas
./scripts/dev-db.sh start     # 一次性集群：/tmp/pms-pgdata，端口 55432，幂等
./scripts/dev-db.sh status
./scripts/dev-db.sh stop
./scripts/dev-db.sh reset     # 破坏性：删数据目录，需显式执行
```

脚本只操作独立数据目录与非默认端口，**不触碰系统里既有的 PostgreSQL 集群**。
角色与授权来自 `scripts/db-bootstrap.sql`（迁移账号 `pms_migrator` / 应用账号 `pms_app`，均非超级用户）。

### 3.2 起服务与冒烟

```bash
./mvnw spring-boot:run                    # 默认 local 档案，连 127.0.0.1:55432/pms
curl -s localhost:8080/actuator/health    # {"groups":["liveness","readiness"],"status":"UP"}
curl -s localhost:8080/actuator/info
curl -si localhost:8080/v3/api-docs | head -1   # 200（仅 local 档案）
```

Flyway 在启动时用 `pms_migrator` 执行迁移，应用连接用 `pms_app`（只有 DML）。

### 3.3 只跑单元测试（不需要数据库）

```bash
./mvnw -B test                  # 20 个用例中 12 个集成用例被跳过，0 失败
PMS_IT_DB=true ./mvnw -B test   # 连真实库，20/20 通过
```

## 4. 代码结构

```text
cn.sec.hn.openbackupsaas
├── openFrameworksApplication.java     启动类（排除 UserDetailsServiceAutoConfiguration）
├── shared/
│   ├── config/SecurityConfig.java     默认拒绝的安全基线（无任何可用登录方式）
│   ├── config/OpenApiConfig.java      OpenAPI 元信息（可开关）
│   ├── config/ClockConfig.java        单一时钟源（可测/可复现的时间）
│   ├── config/MyBatisConfig.java      注册 UuidTypeHandler（MyBatis 3.5.19 已移除内置实现）
│   ├── mybatis/UuidTypeHandler.java   uuid 列读写
│   ├── error/ConflictException.java   业务冲突 → 409
│   └── web/RequestIdFilter.java       请求关联 ID → MDC + X-Request-Id 响应头
│   └── web/GlobalExceptionHandler.java RFC 9457 ProblemDetail 统一错误（含 409）
├── tenant/domain/                     Tenant / HotelGroup / Hotel / 状态枚举（不可变 record）
├── tenant/mapper/OrganizationMapper   XML 显式 SQL，查询强制带 tenantId
├── tenant/service/OrganizationService 组织建立与读取，状态前置校验
├── identity/domain/                   AppUser / Role / UserSession / SessionView / UserStatus
├── identity/mapper/IdentityMapper     身份 SQL；**没有不带 tenantId 的查询方法**
├── identity/service/AuthenticationService 登录、登出、改密、每请求会话解析
├── identity/web/                      AuthController（4 个接口）、IdentityController、
│                                      BearerSessionAuthenticationFilter（Bearer → 身份 + 租户上下文）
├── room/domain/ mapper/ service/ web/  room_type / physical_room；同店归属、启停规则、只读清洁状态
├── reservation/domain/ mapper/ service/  订单、订单行、统一占用事实；`StayRange`（半开区间）、统一锁序与冲突翻译
├── journal/domain/ mapper/ service/   库存效果流水、关键审计、Outbox 事件；与业务写入同事务，三表只追加
├── command/domain/ mapper/ service/   幂等记录、规范化请求摘要、命令入口；`GET /api/v1/operations/*` 结果查询
├── reservation/domain/ mapper/ service/ web/  订单、订单行、统一占用事实；`ReservationCommandService`（原子建单 + 自动选房 + 统一锁序）、`POST /api/v1/hotels/{hotelId}/reservations`
├── shared/security/                   TenantContext / TenantContextHolder / Authz / PmsPrincipal / SessionTokens / HotelAccess
├── room/ reservation/ inventory/ pricing/
├── frontdesk/ payment/ channel/ integration/ audit/ reporting/
│       └── 各自 package-info.java 写明职责、边界与依据任务；除 tenant / identity / room / reservation / journal / command 外仍为占位
```

## 4.1 已开放接口

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | 匿名 | 体里必须给 `tenantCode`（登录是唯一允许从请求体读租户的地方） |
| POST | `/api/v1/auth/logout` | Bearer | 只撤销当前会话，返回 204 |
| GET | `/api/v1/auth/me` | Bearer | 当前身份、门店范围与权限点 |
| POST | `/api/v1/auth/password` | Bearer | 校验原口令；成功后回收**其他**会话 |
| GET | `/api/v1/identity/users/{userId}/hotel-scopes` | Bearer + `identity:read` | 目标账号不在当前租户时返回 404（不是 403） |
| POST/GET | `/api/v1/hotels/{hotelId}/room-types` | Bearer + `room:write` / `room:read` | 房型创建与列表；编码同店唯一（自动小写） |
| GET | `/api/v1/hotels/{hotelId}/room-types/{id}` | Bearer + `room:read` | 房型详情 |
| POST | `/api/v1/hotels/{hotelId}/room-types/{id}/status` | Bearer + `room:write` | 启停房型；仍有在用房间时 409 |
| POST/GET | `/api/v1/hotels/{hotelId}/rooms` | Bearer + `room:write` / `room:read` | 房间创建与列表（可按 `roomTypeId` / `status` / `floor` 过滤） |
| GET | `/api/v1/hotels/{hotelId}/rooms/{id}` | Bearer + `room:read` | 房间详情（含只读 `housekeepingStatus`） |
| PATCH | `/api/v1/hotels/{hotelId}/rooms/{id}` | Bearer + `room:write` | 改房型归属/楼层/备注；**不收清洁状态** |
| POST | `/api/v1/hotels/{hotelId}/rooms/{id}/status` | Bearer + `room:write` | 启停房间 |
| POST | `/api/v1/hotels/{hotelId}/reservations` | Bearer + `reservation:write` | 创建订单（原子建单 + 自动选房，幂等键 `Idempotency-Key` 由 `X-Caller-Namespace` 划定作用域） |
| GET | `/api/v1/operations/{operationId}` | Bearer + `operation:read` | 按操作标识查结果（提交成功但响应丢失的恢复路径） |
| GET | `/api/v1/operations/lookup?hotelId&namespace&command&key` | Bearer + `operation:read` | 按幂等键查结果 |

令牌放在 `Authorization: Bearer <token>`；明文只在登录响应出现一次，库内是 SHA-256。**不要**把令牌放进 URL 查询串。

包边界规则：模块之间只通过服务接口与领域事件交互；`shared` 只放稳定的公共值对象与技术设施，不放领域模型（详见 S0-09 交付的 `docs/architecture/module-boundaries.md`）。

## 5. 当前具备 / 明确不具备

| 具备（已实现并验证） | 不具备（勿误读为已完成） |
| --- | --- |
| 依赖基线按 ADR-011 收敛：webmvc/security/validation/actuator/jdbc/mybatis/flyway/springdoc | 房源、订单、库存、支付等业务模型（阶段 1 其余任务） |
| 默认拒绝的安全链：除健康检查、文档路径与登录接口外一律 401/403 | 前端登录页与会话保持（S3-01；S3-01a 依赖安装仍 BLOCKED） |
| `X-Request-Id` 贯穿日志与错误响应，可对账排查 | 没有结构化日志后端/Loki（S2-06） |
| 统一错误响应 ProblemDetail（400/401/403/404/409/500，不泄露堆栈/SQL） | 错误码体系与"失败/冲突/待核实"三分类未冻结（S1-11） |
| 分环境配置 + 环境变量注入，无明文秘密 | 没有秘密管理服务与轮换（S6-05） |
| **Flyway V1：tenant / hotel_group / hotel，含跨租户复合外键、编码唯一、状态 CHECK** | 营业日字段（等 S0-05） |
| **Flyway V2：app_user / role / permission / role_permission / user_role / user_hotel_scope / user_session** | 员工与角色 CRUD 接口（S3-02）；审计表（S1-06） |
| **Flyway V3：room_type / physical_room**（同店编码唯一、房间→房型按 `(room_type_id, hotel_id)` 复合外键、清洁与运营状态分列） | 维修区间 `room_block`（S1-05）、房态投影（S1-12）、房务任务（S3-06） |
| **房型与房间接口**：创建/列表/详情/属性修改/启停，经 `@PreAuthorize` + `HotelAccess`（租户→404→403）| 房型/房间页面（S3-01a 阻塞）；"有有效预订时停用"的处置（S1-08 后） |
| **占用事实与排斥**：`room_occupancy` 统一预订/封房，GiST 排斥 + 半开区间 CHECK + 统一锁序（`OccupancyService`）| 订单命令与接口（S1-08/S1-09）、超时释放 Worker（S1-10）、库存流水与审计（S1-06）、房态投影（S1-12） |
| **认证与授权**：不透明 Bearer 令牌 + 服务端会话（库内只存 SHA-256）、RBAC 权限点、门店数据范围断言、租户上下文（ADR-016） | 登录失败锁定/限流（S6-05）、闲置超时与滑动续期（未定案） |
| **迁移账号与应用账号分权**（`pms_migrator` DDL / `pms_app` 仅 DML，均非超级用户） | 生产环境的账号与密钥管理制度（S6-05） |
| **110 个测试：19 单元/上下文 + 91 真实 PostgreSQL 集成** | 并发与竞态测试（S1-13）、故障注入（S2-08） |
| CI：后端 `verify`（含 postgres 17 service）、前端 `tsc/lint/build` | CI 尚未实际执行过（未推送） |

## 6. 门禁命令与期望

```bash
cd openbackupsaas
./mvnw -B -ntp test        # 无库：20 run / 12 skipped / 0 failure → BUILD SUCCESS
# 住客实名密钥是启动硬前提（ADR-018 §2.4），缺了上下文起不来
export PMS_GUEST_PII_KEY="$(openssl rand -base64 32)"
export PMS_GUEST_PII_HMAC_KEY="$(openssl rand -base64 32)"
PMS_IT_DB=true ./mvnw -B -ntp test   # 连真实库
./mvnw -B -ntp verify      # 含打包
```

离线（依赖已在 `~/.m2` 时）：`./mvnw -o -B test`。

## 7. 已知坑（已在本轮踩过，勿重复）

1. **Boot 4 用 Jackson 3**：`spring.jackson.serialization.write-dates-as-timestamps` 已不存在，写了会在启动时抛属性绑定失败。要注入 Mapper 用 `tools.jackson.databind.ObjectMapper`（不是 `com.fasterxml...`）。
2. **`test` 档案靠 `spring.autoconfigure.exclude` 排除三个自动配置**：`org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`、`org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`、`org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration`。Boot 4 里这些类的包名已经迁移，不能照抄 Boot 3。
3. **MyBatis 3.5.19 没有内置 `UUIDTypeHandler`**：jar 里已无该类，uuid 列会在解析期抛 `No typehandler found for property null`。本项目用 `shared/mybatis/UuidTypeHandler` + `MyBatisConfig` 全局注册解决；新建 Mapper 不需要再单独指定。
4. **record + `<constructor>` 映射要求类型精确匹配**：数据库 `BIGINT` 对应 `java.lang.Long`，若 record 用 `long` 会 `NoSuchMethodException`；同理不要用 `javaType="_long"`（基本类型没有 TypeHandler）。
5. **`test` 档案下没有 Mapper 实现**：服务类注入会失败，用 `support/NoDatabaseStubConfig` 补位；它的方法一被调用就抛异常，避免"用假实现测业务"。
6. **默认 401 是正确的**：除健康检查、文档路径与 `/api/v1/auth/login` 外，任何业务路径都返回 401 + `application/problem+json`，不要为了"能调通"就把 `anyRequest` 改成 `permitAll`。
7. **`@PreAuthorize` 拒绝会变成 500，除非显式处理**：`GlobalExceptionHandler` 有 `@ExceptionHandler(Exception.class)` 兜底，会先吃掉 `AccessDeniedException`。S1-03 已补 403/401/404 三个处理器，**新增异常类型时要检查是否会掉进兜底 500**。
8. **安全过滤器不要标 `@Component`**：Boot 会把容器里的每个 `Filter` Bean 自动注册到 Servlet 容器，与 `addFilterBefore` 叠加后同一请求跑两遍（症状是每请求两条会话查询日志）。本项目在 `SecurityConfig` 里手动 `new`。
9. **前端联调不做 CORS**：`frontend/merchant-web/config/proxy.ts` 已把 `/api` 代理到后端；跨域配置一旦打开就等于放宽浏览器侧边界，需单独评审。
10. **CSRF 当前关闭**（Bearer 头，无 Cookie 会话，见 ADR-016）。若改用 Cookie 会话，必须同步开启 CSRF 并补同步令牌。
11. **造测试账号别手写 BCrypt 哈希**：网上流传的固定哈希未必对应你以为的明文。用 `new BCryptPasswordEncoder().encode("...")` 现算；`ck_app_user_password_hash` 会检查 `$2` 前缀与 60 位长度。
12. **别用 `UUID.compareTo` 断言数据库排序**：它按 `mostSigBits` 有符号比较，PostgreSQL 的 `uuid` 按字节排序，两者在高位为 1 的 uuid 上结论相反（本轮测试就踩到）。跨层排序（锁序、分页游标）用十六进制文本比。
13. **daterange 参数要走文本绑定 + SQL 侧 `::daterange` 转换**：驱动是 `runtime` 作用域，编译期拿不到 `PGobject`。另外 PG 会把 `daterange` 规范化为 `[)`，所以"闭区间"根本存不进去——相邻可续住是类型自带的保证。
14. **record 里别用 `int` / `long` 接数据库列**：MyBatis 构造映射要求与 JDBC 类型精确匹配（`Integer`/`Long`），否则读取时 `NoSuchMethodException`（S1-02 与 S1-06 各踩一次）。
15. **jsonb 与 daterange 一样走文本绑定 + SQL 侧 `::jsonb`**（驱动 runtime 作用域）。
16. **不要靠捕获唯一键异常判断"已存在"**：PostgreSQL 报错后整个事务被标记中止，后面的语句全挂。用 `INSERT ... ON CONFLICT DO NOTHING` 看影响行数。
17. **给已有表加外键前先确认存量数据**：本地集群残留的旧数据会让迁移失败，症状却是 Spring 上下文加载失败，极难定位。
18. **本地集群是一次性的**：`/tmp/pms-pgdata` 重启机器后丢失，跑 `./scripts/dev-db.sh start` 重建；不要把本地数据当证据，证据以 `docs/verification/` 下的记录为准。
