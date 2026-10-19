# ADR-011：技术基线与依赖收敛

> 日期：2026-10-19；任务：S0-03；状态：选定用于后续实施，最小构建已验证。
>
> 实际工程依赖尚未按此 ADR 改动。数据库运行、迁移、业务事务和生产部署验收不在本次构建结论内。
>
> 输入：[架构 §3.2](../enterprise_hotel_pms_architecture.md)、[开发规划](../企业级酒店%20PMS%20开发任务规划.md)、[状态机](../domain/state-machines.md)。证据：[版本矩阵](../architecture/dependency-version-matrix.md)、[验证报告](../verification/S0-03/2026-10-19-compatibility-report.md)。

## 1. 背景与决策目标

现有后端是 Spring Boot 4.1.1 / Java 21 的初始化工程，已经声明 Kafka、Spring Cloud Function、Web Services 和 WebSocket，但尚无相关业务。前端是 Vue 默认页，尚未接入后台组件库。架构列出的组件是候选方向，需要收敛为单人可以维护、能逐项验证的实施基线。

本次优先保留已能构建且符合方向的基础，明确 SQL、数据库迁移、接口文档与前端组件选择。阶段 1 使用模块化单体和 PostgreSQL 单一事务边界，阶段 2 再接入可靠消息发布消费。业务 DEC-01—DEC-16 的 OPEN 状态保持不变；技术决定不代替业务签核。

## 2. 选定方案

| 领域 | 决定 | 原因与实施边界 |
| --- | --- | --- |
| 后端平台 | Java 21；Spring Boot 4.1.1；Maven Wrapper 3.9.11 | 保留当前可构建基线。Spring/Security/JDBC/Flyway 等优先由 Boot BOM 管理，避免手动拼版本 |
| 应用形态 | 单 Maven 工程、按领域包隔离、Spring MVC | 保留现有目录与包名到 S1-01；命名调整必须覆盖主类、测试、配置、制品和引用，不额外拆微服务 |
| 数据库 | PostgreSQL 17，当前候选补丁 17.11 | 使用真实 PostgreSQL 验证范围、排斥约束、行锁和事务；镜像摘要、数据库权限及扩展安装尚未验证 |
| 数据访问 | MyBatis starter 4.1.0，单一持久化方案 | 库存关键 SQL、受影响行数及锁顺序显式可审查；动态 SQL 值参数化，租户条件进入查询和约束；不额外接入 JPA/jOOQ |
| 迁移 | Boot Flyway starter + PostgreSQL 专用 Flyway 模块 | 跟随 Boot BOM，本次解析到 12.4.0；迁移只追加，独立受控执行身份与应用身份的分工在 S1 明确 |
| API | springdoc 3.1.1，OpenAPI 3.x | 生成文档不能代替 S0-09 的命令、错误、幂等与事件契约；文档入口权限在 S1 配置 |
| 基础能力 | Security、Validation、Actuator、JDBC | 保留安全入口，新增参数校验与健康能力。默认安全配置不能视为租户隔离、登录或 RBAC 已完成 |
| 后端测试 | Boot 管理的 JUnit Jupiter 6.0.3；后续引入真实 PostgreSQL 测试 | 架构初稿列 JUnit 5，本次跟随现有 BOM 的 JUnit 6，不强制降级。Testcontainers 的具体版本与容器启动留 S1-01 验证 |
| 管理前端 | Vue 3.5.44 / TypeScript 6.0.3 / Vite 8.3.4 / Element Plus 2.14.7 | 前三者来自现有锁文件，组件库通过最小按钮探针；暂不增加自动导入、状态库或新构建插件 |
| 前端运行环境 | Node 24 LTS；本机验证 24.15.0 / npm 11.12.1 | 遵循项目 engines；提交 lockfile，CI 使用 npm ci，升级走独立变更 |
| 消息 | 只选 RabbitMQ；阶段 2 接入 | 阶段 1 同事务写 Outbox；阶段 2 实现发布、确认、Inbox、重试和恢复。不能以引入 Broker 代替数据库幂等 |
| Redis | 首期不引入 | 仅在缓存/限流等明确需求后引入；不承担库存权威、数据库约束或锁房正确性职责 |
| 日志 | 先结构化应用日志，集中日志优先 Loki | 有实际采集需求后再锁定版本；不同时部署 Loki 与 OpenSearch；指标与链路工具在运维阶段验证 |

Boot 4.1.1 的 Java 与 Maven 要求允许当前环境；Cloud 2025.1 自 2025.1.2 起覆盖 Boot 4.1，因此当前 2025.1.3 不能被判为“不兼容”。移除 Cloud 是因为没有实际调用需求。[Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Cloud 支持矩阵](https://spring.io/projects/spring-cloud/)。

MyBatis 4.1.0 的发布记录对应 Boot 4.1 适配；jOOQ 也有可用于 Java 21 的版本。本次选择 MyBatis 的原因是明确 SQL 与较少工具链维护，不是 jOOQ 无法支持当前 Java。[MyBatis 发布记录](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-4.1.0)、[jOOQ 发行说明与版本选择](https://www.jooq.org/download/)。

## 3. 现有依赖的实施清单

| 动作 | 依赖或配置 | 实施任务 | 需同步处理 |
| --- | --- | --- | --- |
| 保留 | Boot parent、Java 21、webmvc、security、PostgreSQL driver | S1-01 | 验证构建、显式安全配置、配置分环境 |
| 保留 | webmvc-test、security-test、Maven Wrapper | S1-01 | 将只有 contextLoads 的测试扩展为有业务断言的测试 |
| 移除 | kafka 及 kafka-test starter | S1-01 | 确认无业务引用；不在初期同时维护两种 Broker |
| 移除 | spring-cloud-function-web、spring-cloud-starter、Cloud BOM 与对应版本属性 | S1-01 | 检查配置、文档和依赖树，防止留下不可用入口 |
| 移除 | webservices、websocket 及对应 test starter | S1-01 | 未来确有 SOAP/实时推送协议时再用独立 ADR 引入 |
| 新增 | validation、actuator、jdbc、flyway starter、flyway-database-postgresql | S1-01/02 | 数据源、健康检查与迁移配置；确认数据库模块存在 |
| 新增 | MyBatis starter 4.1.0、springdoc 3.1.1、jdbc-test | S1-01 | Mapper 扫描、事务回滚、API schema 和访问权限集成测试 |
| 新增 | Element Plus 2.14.7 | S3-01 | 正式页面的样式、交互、无障碍及浏览器检查；当前只验证按钮构建 |
| 延后 | RabbitMQ、Redis、Testcontainers、观测/日志后端的具体部署配置 | 对应 S1/S2/S6 任务 | 每项在引入时重新核验版本、许可证、支持周期及真实运行 |

可参考[已编译候选 POM](../verification/S0-03/candidate-backend-pom.xml)逐项修改；它是验证快照，不是可以直接替代数据源配置、安全配置和测试设计的完整工程模板。

## 4. 与架构候选方案的关系

1. Boot 从架构举例的 3.5 维护线收敛到现有 4.1.1，符合“实施时选定受支持稳定线”的原则。实际构建成功不等于未来发布时仍受支持；发布前复核维护状态。
2. JUnit 从初稿的 5 调整为 Boot BOM 管理的 6。影响测试扩展、插件及第三方测试库兼容性；新增库必须在 S1 的测试套件中实际执行。
3. 数据访问选 MyBatis，消息选 RabbitMQ，日志方向选 Loki；保留可替换边界，不同时安装备选方案。
4. Redis 没有首期版本承诺。Redis 不同版本的许可选择不同，引入时核对具体版本与部署方式，不能把初稿“Redis 7”直接作为已审核依赖。[Redis 许可证说明](https://redis.io/legal/licenses/)。
5. S0-03 只冻结技术选择和最低构建证据；S0-01/02 关键业务分支未冻结，不据此提前实施最终业务 DDL。

## 5. 风险、验证门禁与回退

- **数据库未运行：** 本机 Docker daemon 不可用，未验证 PostgreSQL、Flyway 空库/升级、Mapper SQL、Testcontainers、排斥约束或竞态。S1-01/02 先补环境及连接测试；S1-05 及交易任务验证真实约束和并发。
- **候选后端仅编译打包：** 显式跳过测试。新增 JDBC/Flyway 需要数据源；不能将原工程的 contextLoads 成功移用为候选工程启动成功。
- **生命周期：** PostgreSQL 17 当前支持到 2029-11-08；RabbitMQ 4.3 当前社区维护窗口到 2027-01-31，后者可能早于项目最终上线，阶段 2 必须重选届时受支持补丁/维护线。[PostgreSQL 支持策略](https://www.postgresql.org/support/versioning/)、[RabbitMQ 发布与支持时间](https://www.rabbitmq.com/release-information)。
- **安全：** 本次 npm 审计和有限 Spring 公告核对不能代替全量依赖、镜像及配置扫描。发布门禁需附依赖清单、许可证、漏洞扫描与修复/接受记录。
- **回退顺序：** S1 的组件调整使用独立提交；若候选集成失败，先保留当前工程并修复候选。确需换维护线时整体重做 BOM、MyBatis、springdoc 和测试矩阵，不独立降级某个底层 Spring jar。
- **有业务数据后的回退：** 只回滚到能读取当前 schema 的应用；不可删除 Flyway 历史或回滚已确认订单。数据库使用扩展、回填、收缩和必要的前向修复。

## 6. 下一步

S0-04 先完成 L/A/S/G 数据模型候选、租户复合外键、占用与维修统一互斥、操作级幂等与流水行唯一键、事务锁顺序和测试映射。对未决业务分支明确备选及冻结条件。阶段 0 的 G0 通过后，再按本 ADR 实施工程依赖调整。
