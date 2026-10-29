# PMS 开发进度台账

> 版本：1.0；建立日期：2026-10-29（AEST）；最近更新：2026-10-29（R-20261029-01）。
>
> 台账规则：**每轮开发必须在 §4 追加一条 Round 记录，并同步 §1 快照与 [feature-catalog.md](./feature-catalog.md) 受影响行**。状态词只允许使用 [README.md](./README.md) §2 的 8 个值。
>
> 事实来源优先级：开发规划 §1.3（历史事实）→ 本文件（当前口径）→ feature-catalog（功能粒度）。

## 1. 当前快照

| 项 | 当前值 |
| --- | --- |
| 当前轮次 | `R-20261030-03`（合并到主项目目录 + 启动类/测试类命名同步，chore） |
| 当前阶段 | 阶段 1 进行中（S1-01 VERIFYING、S1-02 DONE）；阶段 0 的 S0-05—S0-09 仍待完成 |
| 已通过门禁 | 无（G0 未开审） |
| 后端工程状态 | 依赖按 ADR-011 收敛；组织域已落库（3 张表 + 复合外键 + CHECK）；**20 个测试全部通过（含 12 个真实 PostgreSQL 集成用例）**；认证、租户隔离、库存与订单仍未实现 |
| 前端工程状态 | `frontend-admin-pro/` 工程文件已就位；`npm ci` 受本机代理阻塞，见 [S3-01a 记录](../verification/S3-01a/2026-10-30-dependency-install.md) |
| 功能项完成度 | 约 4.2%（124 项，DONE 4 项，见 feature-catalog §1） |
| 下一轮目标 | ①`S1-03` 最小认证/授权/租户隔离（DB-A 剩余：账号、角色、门店范围）；②`S1-04` 房型与实体房（DB-B 前半） |

## 2. 任务状态总表（66 项 = 规划 65 项 + 变更追加 S3-01a）

### 阶段 0 需求与领域设计（门禁 G0）

| 任务 | 名称 | 优先级 | 估算 | 状态 | 备注 |
| --- | --- | --- | --- | --- | --- |
| S0-01 | 范围、角色与业务术语 | P0 | 2–3 | VERIFYING | 文档已交付，业务选项待确认 |
| S0-02 | 订单、占用与入住状态机 | P0 | 3–5 | VERIFYING | 状态机与 28 场景已交付，DEC 未冻结 |
| S0-03 | 技术栈收敛与兼容性验证 | P0 | 2–4 | DONE | 后端/前端构建证据齐备；前端部分已被 ADR-015 取代 |
| S0-04 | 交易不变量与数据模型 | P0 | 3–5 | VERIFYING | ER/字典/事务协议/30 场景已交付，未做数据库实测 |
| S0-05 | 日期、营业日与未分房销售规则 | P0 | 2–4 | TODO | 下一步主线（ADR-012/013） |
| S0-06 | OTA 准入与协议调查 | P0 | 2–3 | TODO | 与商务并行，不阻塞编码 |
| S0-07 | 支付与账务业务边界 | P0 | 2–4 | TODO | ADR-014 待出 |
| S0-08 | 容量、安全、SLO 与值守边界 | P0 | 2–4 | TODO | ADR-007/008 |
| S0-09 | 模块、契约、任务与设计基线审查 | P0 | 2–3 | TODO | G0 审查入口 |

### 阶段 1 数据库与交易内核（门禁 G1）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S1-01 | 最小工程、环境与 CI | P0 | 2–4 | VERIFYING | 依赖/配置/安全基线/错误响应/真实 PostgreSQL 集成测试/账号分权均已完成；**仅剩 CI 实跑未观测** |
| S1-02 | Flyway 与组织基础表 | P0 | 2–4 | DONE | V1 迁移 + 复合外键 + 账号分权 + 12 个 PG 集成用例；V1→V2 升级验证留到出现 V2 时 |
| S1-03 | 最小认证、授权和租户隔离 | P0 | 3–5 | TODO |
| S1-04 | 房型、实体房间与运营状态 | P0 | 2–4 | TODO |
| S1-05 | 日期约束、占用排斥与维修互斥 | P0 | 4–6 | TODO |
| S1-06 | 库存流水、审计和 Outbox 原子写入 | P0 | 3–5 | TODO |
| S1-07 | 持久化请求幂等与提交不确定恢复 | P0 | 3–5 | TODO |
| S1-08 | 原子锁房与订单创建 | P0 | 3–5 | TODO |
| S1-09 | 确认、取消与 No-show | P0 | 3–5 | TODO |
| S1-10 | 超时领取与幂等释放 | P0 | 2–4 | TODO |
| S1-11 | 交易 API、查询与权限闭环 | P0 | 2–4 | TODO |
| S1-12 | 派生房态、可售计算与重建 | P0 | 3–5 | TODO |
| S1-13 | 交易内核集成与并发验收 | P0 | 4–6 | TODO |

### 阶段 2 可靠性内核（门禁 G2）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S2-01 | Outbox 发布与 Worker 隔离 | P0 | 3–5 | TODO |
| S2-02 | Inbox 消费与版本保护 | P0 | 3–5 | TODO |
| S2-03 | 持久化任务与外部调用状态 | P0 | 3–5 | TODO |
| S2-04 | 死信、人工重放与审计修复 | P0 | 2–4 | TODO |
| S2-05 | 内部对账与派生修复 | P0 | 3–5 | TODO |
| S2-06 | 监控、链路和告警 | P0 | 3–5 | TODO |
| S2-07 | 最小部署、备份与恢复闭环 | P0 | 3–5 | TODO |
| S2-08 | 故障注入与可靠性验收 | P0 | 4–6 | TODO |

### 阶段 3 商家、集团与前台端（门禁 G3）

| 任务 | 名称 | 优先级 | 估算 | 状态 | 备注 |
| --- | --- | --- | --- | --- | --- |
| **S3-01a** | **前端工程骨架前置准备（Ant Design Pro）** | P1 | 2–3 | **BLOCKED** | **2026-10-29 变更追加（ADR-015）。工程文件已就位；`npm ci` 受本机代理阻塞（`ECONNREFUSED broker.sock`），构建未验证。允许在 G2 前准备，业务页面仍受 S3-01 约束** |
| S3-01 | 后台前端基础与契约接入 | P1 | 3–5 | TODO | 依赖 S2-08；骨架已由 S3-01a 先行 |
| S3-02 | 集团组织、员工与 RBAC 管理 | P0 | 3–5 | TODO | |
| S3-03 | 房态日历与预订工作台 | P1 | 5–8 | TODO | |
| S3-04 | 排房、入住与离店 | P0 | 4–6 | TODO | |
| S3-05 | 续住、换房和部分操作 | P0 | 4–7 | TODO | 部分操作受 DEC-04 阻塞 |
| S3-06 | 维修、停用与清洁工作流 | P0 | 2–4 | TODO | |
| S3-07 | 价格日历、价规与报价快照 | P0 | 4–6 | TODO | 税费口径受 DEC-09 阻塞 |
| S3-08 | 基础账务、收款与押金 | P0 | 4–7 | TODO | |
| S3-09 | 审计、经营报表、夜审与异步导出 | P1 | 5–8 | TODO | 报表口径受 DEC-07 阻塞 |
| S3-10 | 员工端端到端与业务验收 | P0 | 3–5 | TODO | |

### 阶段 4 用户端与支付（门禁 G4）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S4-01 | 住客身份与预订公共 API | P0 | 3–5 | TODO |
| S4-02 | H5 预订流程 | P1 | 4–7 | TODO |
| S4-03 | 微信小程序预订流程 | P1 | 5–8 | TODO |
| S4-04 | 支付创建、查询与回调 | P0 | 4–7 | TODO |
| S4-05 | 支付、取消和锁房超时协调 | P0 | 3–5 | TODO |
| S4-06 | 退款、支付对账与账务联动 | P0 | 4–7 | TODO |
| S4-07 | 两端支付联调与隐私检查 | P1 | 3–5 | TODO |
| S4-08 | 支付与自有渠道门禁 | P0 | 3–5 | TODO |

### 阶段 5 首个 OTA 渠道（门禁 G5）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S5-01 | 渠道账户、适配器与安全边界 | P0 | 3–5 | BLOCKED（授权未到位） |
| S5-02 | 门店、房型与价格计划映射 | P0 | 2–4 | TODO |
| S5-03 | 订单接收、确认与更新 | P0 | 4–7 | TODO |
| S5-04 | 取消、变更与乱序通知 | P0 | 3–5 | TODO |
| S5-05 | 库存与价格可靠推送 | P0 | 3–6 | TODO |
| S5-06 | 渠道对账与人工补偿 | P0 | 3–5 | TODO |
| S5-07 | 模拟器、契约与渠道联合验收 | P0 | 4–7 | TODO |
| S5-08 | 首渠道试运行方案与开关演练 | P1 | 2–4 | TODO |

### 阶段 6 规模化与生产验收（门禁 G6）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S6-01 | 容量基线、负载模型与调优 | P0 | 4–7 | TODO |
| S6-02 | 生产拓扑与数据库高可用 | P0 | 4–7 | TODO |
| S6-03 | 异地备份、归档与 PITR | P0 | 3–5 | TODO |
| S6-04 | 故障切换、网络分区与 RPO/RTO 验证 | P0 | 4–7 | TODO |
| S6-05 | 安全、隐私与供应链验收 | P0 | 4–7 | TODO |
| S6-06 | 发布、迁移兼容与回滚演练 | P0 | 3–5 | TODO |
| S6-07 | 数据初始化、门店培训与运营准备 | P1 | 3–5 | TODO |
| S6-08 | 生产门禁与分批上线 | P0 | 3–5 | TODO |
| S6-09 | 稳定性观察与阶段复盘 | P0 | 2–4 | TODO |

### 汇总

| 状态 | 任务数 |
| --- | --- |
| DONE | 2（S0-03、S1-02） |
| VERIFYING | 4（S0-01、S0-02、S0-04、S1-01） |
| BLOCKED | 2（S3-01a 环境阻塞、S5-01 渠道授权） |
| TODO | 58 |
| 合计 | 66 |

## 3. 阶段门禁状态

| 门禁 | 状态 | 已具备 | 缺口 |
| --- | --- | --- | --- |
| G0 设计 | 未开审 | S0-01/02/03/04 交付物 | S0-05—S0-09；DEC 未冻结；ADR-005/007/008/009/010/012/013/014 未写 |
| G1 交易 | 未开始 | — | S1 全部；T01—T07/T09 并发证据 |
| G2 可靠性 | 未开始 | — | S2 全部；T08/T10—T13 |
| G3 员工闭环 | 未开始 | 前端骨架（S3-01a） | S3-01 起全部；T14—T16 |
| G4 自有渠道 | 未开始 | — | S4 全部；T17—T19 |
| G5 首 OTA | 未开始 | — | 渠道授权（DEC-11）与 S5 全部 |
| G6 生产 | 未开始 | — | S6 全部；T23—T28 |

## 4. 开发轮次记录（按轮追加，最新在上）

### 模板

```markdown
### R-YYYYMMDD-NN <一句话目标>

- 状态：DOING / VERIFYING / DONE
- 范围（本轮只做这些）：
- 涉及功能项（FT-*）/ 任务（S*-*）：
- 变更文件：
- 验证：命令 → 结果 → 未覆盖项
- 本轮发现（不夹带实现，转入下轮）：
- 回写确认：feature-catalog ✓ / §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：
```

---

### R-20261030-03 合并到主项目目录 + 命名同步（chore）

- 状态：DONE
- 范围：把工作树分支 `workbuddy/main-aed5b5c8` 合并进 `main`（主项目目录 `/Users/xuzhongxin/IdeaProjects/OpenHotelManagementSystem`）；启动类保留主目录的改名 `openFrameworksApplication`，并把 S1-01 的 `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)` 与说明注释移植过去；测试类 `OpenbackupsaasApplicationTests` → `ApplicationContextTests`。
- 涉及功能项/任务：无（不改变任何功能项状态）
- 变更文件：`.gitignore`（忽略 `.DS_Store` 与 `.workbuddy/`）、`docs/development/backend-setup.md`（启动类名）、规划 §2 后端测试行、`ApplicationContextTests.java`（rename）
- 验证：
  - 合并后 `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 20, Failures: 0, Errors: 0` → **BUILD SUCCESS**
  - 冲突仅 1 处（启动类 rename vs 注解改动），已人工合并，无 `<<<<<<<` 残留，旧文件已删除
- 本轮发现：主目录原有的未提交改名会挡住合并——此后凡在 IDE 里改动，建议先提交或告知，避免工作树分支与主目录各自演进。
- 回写确认：feature-catalog 无变化（一致）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-03` 最小认证/授权/租户隔离。

---

### R-20261030-02 S1-02 组织基线：Flyway V1 + 租户/集团/门店 + 账号分权

- 状态：DONE
- 范围：
  1. 本地开发数据库脚本（独立集群、可丢弃、幂等）：`scripts/dev-db.sh` + `scripts/db-bootstrap.sql`。
  2. Flyway 迁移 V1：`tenant` / `hotel_group` / `hotel`，含租户内编码唯一、`(id, tenant_id)` 复合唯一、跨租户复合外键、状态与编码 CHECK、注释即字典。
  3. 组织数据访问与服务：record 领域对象、`OrganizationMapper`（XML，查询强制带 tenantId）、`OrganizationService`（状态前置校验、编码/时区校验）。
  4. 统一错误补 409：`ConflictException` 与数据库约束冲突映射，均不泄露 SQL。
  5. 集成测试 12 例（真实 PostgreSQL），CI 增加 postgres 17 service + 角色引导步骤。
- 涉及功能项/任务：A-01、A-02、A-03、A-06；S1-02（S1-01 剩余项一并收口）
- 变更文件：
  - 新增：`openbackupsaas/scripts/dev-db.sh`、`scripts/db-bootstrap.sql`、`src/main/resources/db/migration/V1__org_baseline.sql`
  - 新增：`tenant/domain/{Tenant,TenantStatus,HotelGroup,Hotel,OrgStatus}.java`、`tenant/mapper/OrganizationMapper.java`、`tenant/service/OrganizationService.java`
  - 新增：`resources/mapper/tenant/OrganizationMapper.xml`、`shared/error/ConflictException.java`、`shared/config/{ClockConfig,MyBatisConfig}.java`、`shared/mybatis/UuidTypeHandler.java`
  - 新增：`application-integration.properties`、`src/test/resources/db/integration-cleanup.sql`、`tenant/OrganizationIntegrationTest.java`、`support/NoDatabaseStubConfig.java`
  - 修改：`application.properties`（迁移/MyBatis 公共项上移）、`application-local.properties`（Flyway 独立账号）、`GlobalExceptionHandler`（409）、`OpenbackupsaasApplicationTests`、`.github/workflows/backend.yml`、`db/migration/README.md`
- 验证（本轮实际执行，详见 [S1-02 记录](../verification/S1-02/2026-10-30-org-baseline.md)）：
  - `./scripts/dev-db.sh start` → PostgreSQL 17 就绪（55432），重复执行幂等
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**
  - 无库时：`./mvnw -B -ntp test` → 20 run / 12 skipped / 0 failure → BUILD SUCCESS（跳过而非伪通过）
  - `psql` 核对：`flyway_schema_history` 1 行成功；4 张表 owner 均为 `pms_migrator`；`hotel` 有 `fk_hotel_group_same_tenant`（复合外键）、`uq_hotel_code`、4 个 CHECK；`pms_app` `can_create=f / can_insert=t / is_super=f`
  - 未执行：V1→V2 升级路径（暂无 V2）、并发冲突时机（S1-13）、CI 实跑
- 本轮发现（转入后续轮次）：
  1. **MyBatis 3.5.19 不再内置 `UUIDTypeHandler`**（jar 内已无该类），uuid 列会抛 `No typehandler found for property null`。已加 `UuidTypeHandler` 并在 `MyBatisConfig` 全局注册，本地验证通过。
  2. 构造参数映射要求类型精确匹配：`BIGINT` → `java.lang.Long`，record 用 `long` 会 `NoSuchMethodException`。三个 record 的 `version` 已改为 `Long` 并注明原因。
  3. `test` 档案排除 MyBatis 后，服务类会因缺少 Mapper 失败；用 `NoDatabaseStubConfig`（调用即抛异常）补位，避免"假实现测业务"。
  4. 门店营业日字段故意未加：S0-05/ADR-012 未定案，先不加字段，等定案后追加迁移。
- 回写确认：feature-catalog ✓（A-01/A-02/A-03/A-06 → DONE）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-03` 最小认证/授权/租户隔离（DB-A 剩余：账号、角色、门店范围、撤权），或 `S1-04` 房型与实体房。

---

### R-20261030-01 S1-01 后端工程基线：依赖收敛 + 默认拒绝 + 统一错误 + CI

- 状态：VERIFYING（代码与本地测试已通过；真实 PostgreSQL 集成测试与账号分权未做，故不标 DONE）
- 范围：
  1. `pom.xml` 按 ADR-011 收敛（移除 Kafka/Cloud Function/Cloud BOM/WebServices/WebSocket，引入 validation/actuator/jdbc/flyway/mybatis 4.1.0/springdoc 3.1.1）。
  2. 分环境配置：公共 `application.properties` + `local`（需要 DB）+ `test`（排除 DataSource/Flyway/MyBatis），秘密全部走环境变量。
  3. 安全基线：默认拒绝、无任何可用登录方式、401/403 返回 ProblemDetail。
  4. 统一错误（RFC 9457）+ `X-Request-Id` 贯穿 MDC/响应头/错误体 + 结构化日志（UTC 单行）。
  5. 12 个领域模块包占位（含边界注释）+ `db/migration` 单源说明。
  6. CI：`.github/workflows/backend.yml`（JDK 21 + `./mvnw verify`）、`frontend.yml`（`npm ci` + tsc/lint/build）。
- 涉及功能项/任务：N-01、N-02；S1-01
- 变更文件：
  - 修改：`openbackupsaas/pom.xml`、`src/main/resources/application.properties`、`OpenbackupsaasApplication.java`、`src/test/.../OpenbackupsaasApplicationTests.java`
  - 新增：`application-local.properties`、`application-test.properties`、`logback-spring.xml`、`db/migration/README.md`
  - 新增：`shared/config/SecurityConfig.java`、`shared/config/OpenApiConfig.java`、`shared/web/RequestIdFilter.java`、`shared/web/GlobalExceptionHandler.java`
  - 新增：12 个模块 `package-info.java`、`RequestIdFilterTest`、`GlobalExceptionHandlerTest`
  - 新增：`.github/workflows/backend.yml`、`.github/workflows/frontend.yml`、`docs/development/backend-setup.md`
- 验证（本轮实际执行）：
  - `./mvnw -o -B test` → `Tests run: 8, Failures: 0, Errors: 0` → **BUILD SUCCESS**（3 个 RequestIdFilter 用例、3 个错误处理用例、2 个上下文用例）
  - `./mvnw -o -q -B -DskipTests compile` → 成功（新依赖离线可解析）
  - 运行态冒烟（`test` 档案，端口 18080）：`/actuator/health` → `200 {"groups":["liveness","readiness"],"status":"UP"}`；`/actuator/info` → `{"app":{"name":"pms","version":"0.0.1-SNAPSHOT"}}`；`/api/v1/reservations` → **401 + `application/problem+json`**，响应头与错误体 `requestId` 一致；`/v3/api-docs` → 404（test 档案关闭，符合预期）；启动耗时 1.48s
  - 未执行：真实 PostgreSQL 连接、Flyway 空库/升级、MyBatis SQL、集成测试基类、CI 实跑、前端 `npm ci`
- 本轮发现（转入后续轮次）：
  1. **Boot 4 = Jackson 3**：`spring.jackson.serialization.write-dates-as-timestamps` 会导致启动失败；注入需用 `tools.jackson.databind.ObjectMapper`。已写入 backend-setup §7。
  2. Boot 4 自动配置类包名迁移：`org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`、`org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`；照抄 Boot 3 包名会静默失效。
  3. 已排除 `UserDetailsServiceAutoConfiguration`，避免启动日志出现"随机密码内存用户"被误读为已有登录。
  4. `springdoc` 仅靠属性开关控制，生产必须 `PMS_OPENAPI_ENABLED=false`；该开关的有效性尚未在 `local` 档案下实测（本地无 PG）。
- 回写确认：feature-catalog ✓（N-01/N-02 → VERIFYING）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-02` Flyway 与组织基础表（DB-A）；同时补 `PostgresIntegrationTest` 基类 + CI service 容器，收口 S1-01。

---

### R-20261029-01 建立开发文档体系并把前端基线切换为 Ant Design Pro

- 状态：VERIFYING（文档与决策部分已完成；前端依赖安装转 BLOCKED，见「验证」）
- 范围：
  1. 新建开发执行文档体系（总入口、功能清单、进度台账、前端规范）。
  2. 决策：前端框架改为 Ant Design Pro 并深度使用，落 ADR-015。
  3. 落地 `frontend-admin-pro/` 工程骨架（Umi Max 4 / React 19 / antd 6 / pro-components 3），原 Vue 骨架冻结不删除。
  4. 长期约束入册：前端页面禁止自述式营销文本；每轮开发必须标记进度。
- 涉及功能项/任务：O-01（VERIFYING）、O-09、O-10；S3-01a（新增）、S0-03（前端部分被 ADR-015 取代）
- 变更文件：
  - 新增：`docs/development/README.md`、`feature-catalog.md`、`progress.md`、`frontend-antd-pro.md`、`docs/adr/ADR-015-frontend-ant-design-pro.md`
  - 新增：`frontend-admin-pro/`（Ant Design Pro 6.0.3 模板，去除 .github/.husky/docs/e2e 等仓库元文件，包名改为 `hotel-pms-admin`）
  - 修改：`docs/adr/ADR-011-technology-baseline.md`（前端行标为被 ADR-015 取代）、`docs/enterprise_hotel_pms_architecture.md`（§3.2 增补说明）、`docs/企业级酒店 PMS 开发任务规划.md`（§1.3、§2 前端行、§4.4 S3-01a、§5.1 目录）
- 验证（本轮实际执行，完整记录见 [S3-01a 报告](../verification/S3-01a/2026-10-30-dependency-install.md)）：
  - `npm ci`（frontend-admin-pro）→ **失败 ×6**，共同根因为本机代理在安装高峰断开：`connect ECONNREFUSED .../T/cbb-*/broker.sock`（errno -61）。已试：沙箱内/关闭隔离、`--prefer-offline`、`--offline`、`--maxsockets=3`、移至临时目录后重装。
  - 对照 `npm view react version` → `19.3.1`（registry 可达），确认故障只在大量并行下载阶段。
  - `npm run tsc` / `npm run biome:lint` / `npm run build` → **未执行**（依赖不完整，`node_modules/.bin` 不存在）。
  - 后端 `./mvnw -q -B verify` → **未执行**（本轮未改后端）。
  - 未覆盖：模板在 antd 6 / TS 7 下的编译与构建、页面渲染、demo 页清理后的悬挂引用检查。
- 本轮发现（转入后续轮次）：
  1. 模板自带 `Welcome` 页含产品介绍式文案，违反「禁止自述式营销文本」，须在 O-11 整体替换为「今日房态/待办」工作台。
  2. 启动脚本语义易混：`npm start` 带 mock，`npm run dev` 与 `start:no-mock` 均为 `MOCK=none`。联调与验收固定用 `start:no-mock`，避免 mock 数据掩盖契约错误。
  3. `frontend-engineering-vue/` 已被 ADR-015 停用，是否物理删除待确认（按非破坏原则先冻结）。
  4. **环境约束**：Ant Design Pro 全量依赖约 1.3–1.7 GB / 1200+ 包，本机 Agent 沙箱代理无法承载，今后前端安装类操作一律在宿主终端执行，Agent 侧只做静态检查与文档同步。
- 回写确认：feature-catalog ✓ / §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：①宿主终端 `npm ci` → `tsc`/`biome:lint`/`build`，结果回填 S3-01a 报告 §5；②`S1-01` 后端基线（移除 Kafka/Cloud/SOAP/WebSocket，引入 validation/actuator/jdbc/flyway/mybatis/springdoc + CI）。

## 5. 统计口径说明

- 任务数 66 = 规划 65 项 + 变更追加 S3-01a（不重算原 200–342 人日总量，S3-01a 的 2–3 人日单列）。
- 「完成度」只用于趋势判断；对外排期仍以规划文档 §6 的人日区间和门禁为准。
- 阶段 0 的 VERIFYING 任务不计入 G0 通过；G0 仍需 S0-05—S0-09 完成并经 S0-09 审查。
