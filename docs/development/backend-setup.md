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
├── identity/ room/ reservation/ inventory/ pricing/
├── frontdesk/ payment/ channel/ integration/ audit/ reporting/
│       └── 各自 package-info.java 写明职责、边界与依据任务；除 tenant 外仍为占位
```

包边界规则：模块之间只通过服务接口与领域事件交互；`shared` 只放稳定的公共值对象与技术设施，不放领域模型（详见 S0-09 交付的 `docs/architecture/module-boundaries.md`）。

## 5. 当前具备 / 明确不具备

| 具备（已实现并验证） | 不具备（勿误读为已完成） |
| --- | --- |
| 依赖基线按 ADR-011 收敛：webmvc/security/validation/actuator/jdbc/mybatis/flyway/springdoc | **没有登录、会话、RBAC、门店数据范围**（S1-03） |
| 默认拒绝的安全链：除健康检查与文档路径外一律 401/403 | 没有房源、订单、库存、支付等业务模型（阶段 1 其余任务） |
| `X-Request-Id` 贯穿日志与错误响应，可对账排查 | 没有结构化日志后端/Loki（S2-06） |
| 统一错误响应 ProblemDetail（400/409/500，不泄露堆栈/SQL） | 错误码体系与"失败/冲突/待核实"三分类未冻结（S1-11） |
| 分环境配置 + 环境变量注入，无明文秘密 | 没有秘密管理服务与轮换（S6-05） |
| **Flyway V1：tenant / hotel_group / hotel，含跨租户复合外键、编码唯一、状态 CHECK** | 账号/角色/门店范围表（S1-03）；营业日字段（等 S0-05） |
| **迁移账号与应用账号分权**（`pms_migrator` DDL / `pms_app` 仅 DML，均非超级用户） | 生产环境的账号与密钥管理制度（S6-05） |
| **20 个测试：8 单元/上下文 + 12 真实 PostgreSQL 集成** | 并发与竞态测试（S1-13）、故障注入（S2-08） |
| CI：后端 `verify`（含 postgres 17 service）、前端 `tsc/lint/build` | CI 尚未实际执行过（未推送） |

## 6. 门禁命令与期望

```bash
cd openbackupsaas
./mvnw -B -ntp test        # 无库：20 run / 12 skipped / 0 failure → BUILD SUCCESS
PMS_IT_DB=true ./mvnw -B -ntp test   # 连真实库：20/20 通过
./mvnw -B -ntp verify      # 含打包
```

离线（依赖已在 `~/.m2` 时）：`./mvnw -o -B test`。

## 7. 已知坑（已在本轮踩过，勿重复）

1. **Boot 4 用 Jackson 3**：`spring.jackson.serialization.write-dates-as-timestamps` 已不存在，写了会在启动时抛属性绑定失败。要注入 Mapper 用 `tools.jackson.databind.ObjectMapper`（不是 `com.fasterxml...`）。
2. **`test` 档案靠 `spring.autoconfigure.exclude` 排除三个自动配置**：`org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`、`org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`、`org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration`。Boot 4 里这些类的包名已经迁移，不能照抄 Boot 3。
3. **MyBatis 3.5.19 没有内置 `UUIDTypeHandler`**：jar 里已无该类，uuid 列会在解析期抛 `No typehandler found for property null`。本项目用 `shared/mybatis/UuidTypeHandler` + `MyBatisConfig` 全局注册解决；新建 Mapper 不需要再单独指定。
4. **record + `<constructor>` 映射要求类型精确匹配**：数据库 `BIGINT` 对应 `java.lang.Long`，若 record 用 `long` 会 `NoSuchMethodException`；同理不要用 `javaType="_long"`（基本类型没有 TypeHandler）。
5. **`test` 档案下没有 Mapper 实现**：服务类注入会失败，用 `support/NoDatabaseStubConfig` 补位；它的方法一被调用就抛异常，避免"用假实现测业务"。
6. **默认 401 是正确的**：在 S1-03 完成前，任何业务路径都返回 401 + `application/problem+json`，不要为了"能调通"就把 `anyRequest` 改成 `permitAll`。
7. **前端联调不做 CORS**：`frontend-admin-pro/config/proxy.ts` 已把 `/api` 代理到后端；跨域配置一旦打开就等于放宽浏览器侧边界，需单独评审。
8. **CSRF 当前关闭**（无 Cookie 会话）。若 S1-03 改用 Cookie 会话，必须同步开启 CSRF 并补令牌。
9. **本地集群是一次性的**：`/tmp/pms-pgdata` 重启机器后丢失，跑 `./scripts/dev-db.sh start` 重建；不要把本地数据当证据，证据以 `docs/verification/` 下的记录为准。
