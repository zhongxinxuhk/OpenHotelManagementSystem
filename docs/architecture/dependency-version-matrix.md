# PMS 依赖版本与验证矩阵

> 核验日期：2026-10-19；对应 [ADR-011](../adr/ADR-011-technology-baseline.md)。版本是本次快照，不承诺为未来发布时的最新或受支持版本。

## 1. 证据等级

- **原工程已测**：在当前工程执行过相应构建/已有测试，仅覆盖表中动作。
- **候选构建**：在隔离副本解析、编译或打包成功，不代表运行集成成功。
- **官方核验**：仅有官方版本或支持信息，没有本地服务测试。
- **延后**：尚未选择最终版本，不计作验证通过。

## 2. 后端与数据库

| 组件 | 当前声明或实际解析 | 选定实施基线 | 证据与剩余工作 |
| --- | --- | --- | --- |
| Java | 声明 21；本机 Temurin 21.0.12.1+1 LTS | Java 21 | 原工程已测；本机发行版信息不等于厂商支持合同 |
| Maven | Wrapper 3.9.11 | 保留 3.9.11 | 原工程已测；无需依赖全局 mvn |
| Spring Boot | 4.1.1 | 保留 4.1.1 | 原工程 verify；候选 package |
| Spring Framework | 7.0.9 | Boot BOM 管理 | 两套依赖树实际解析；不单独钉版本 |
| Spring Security | 7.1.1 | Boot BOM 管理 | 原工程上下文测试；鉴权、会话和租户权限未验收 |
| Spring Cloud | BOM 2025.1.3；commons/context/starter 5.0.3 | 移除 | 官方矩阵兼容当前 Boot；移除理由是无实际用途 |
| Cloud Function | 5.0.4 | 移除 | 当前依赖树有此项，未见函数业务 |
| Kafka clients | 4.2.1 | 移除对应 starter | 不转化为 RabbitMQ 已完成；阶段 2 单独接入 |
| MyBatis Boot starter | 原工程无 | 4.1.0 | 候选构建；MyBatis 3.5.19、mybatis-spring 4.1.0 |
| HikariCP | 候选解析 7.0.2 | Boot BOM 管理 | 候选构建；连接池容量及超时未验证 |
| Flyway core / PostgreSQL 模块 | 原工程无；候选 12.4.0 | Boot BOM 管理，两个模块配套 | 候选构建；没有执行迁移 |
| PostgreSQL JDBC | 42.7.13 | Boot BOM 管理 | 两套依赖树解析；未建立数据库连接 |
| PostgreSQL server | 未安装/启动验证 | 17 系列；当前补丁候选 17.11 | 官方核验；镜像 digest、扩展、备份与升级未测 |
| springdoc MVC UI | 原工程无 | 3.1.1 | 候选构建；API 文档端点未请求验证 |
| JUnit Jupiter | 6.0.3 | Boot BOM 管理 | 原工程运行 1 个 contextLoads；候选跳过测试 |
| Testcontainers | 未接入 | S1-01 选择匹配测试栈的版本 | 延后；Docker daemon 当前不可连接 |

官方依据：Boot 4.1.1 的最低 Java/Maven 要求是 17/3.6.3，当前工具满足；MyBatis 选择对应发布版本；Flyway 的 PostgreSQL 支持需数据库专用模块。[Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[MyBatis 4.1.0](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-4.1.0)、[Boot 数据初始化说明](https://docs.spring.io/spring-boot/how-to/data-initialization.html)、[springdoc 文档](https://springdoc.org/)。

Cloud 2025.1.x 从 2025.1.2 开始支持 Boot 4.1.x，不能把当前依赖简单判成错配。PostgreSQL 17 的支持截止为 2029-11-08；具体补丁在部署前复核。[Cloud 兼容表](https://spring.io/projects/spring-cloud/)、[PostgreSQL 版本支持](https://www.postgresql.org/support/versioning/)。

## 3. 前端

| 组件 | 当前声明 | 锁文件/本机实际 | 实施决定与证据 |
| --- | --- | --- | --- |
| Node | ^22.18.0 或 >=24.12.0 | 24.15.0 | 采用 24 LTS；满足项目约束；全新安装与构建通过 |
| npm | 未声明固定 packageManager | 11.12.1 | 本次工具版本；S1/CI 明确工具版本，不默认为所有 npm 版本都通过 |
| Vue | ^3.5.42 | 3.5.44 | 保留锁文件；基线与组件探针构建通过 |
| Vite | ^8.2.2 | 8.3.4 | 保留锁文件；生产构建通过 |
| TypeScript | ~6.0.0 | 6.0.3 | vue-tsc 类型检查通过 |
| vue-tsc | ^3.3.11 | 3.3.13 | 同上 |
| @vitejs/plugin-vue | ^6.0.8 | 6.0.10 | 保留 |
| @vue/tsconfig | ^0.9.1 | 0.9.1 | 保留 |
| vite-plugin-vue-devtools | ^8.2.1 | 8.2.1 | 保留当前配置，生产包行为后续检查 |
| Element Plus | 未声明 | 隔离副本 2.14.7 | 选定；ElButton 与组件样式导入后类型检查、构建通过 |
| 路由、状态管理、前端测试工具 | 当前业务未实现 | 未选定 | S3-01 根据页面和测试需求引入，避免为矩阵完整提前添加 |

Vite 的通用 Node 最低要求与项目 engines 不同，执行时取项目更严格约束；Node 24 在本次核验时为 LTS。Element Plus 使用官方安装方式，2.14.7 的包元数据要求 Vue ^3.3.7，当前 Vue 满足。[Vite 入门](https://vite.dev/guide/)、[Node 发行状态](https://nodejs.org/en/about/previous-releases)、[Element Plus 安装](https://element-plus.org/en-US/guide/installation.html)。

## 4. 延后部署的组件

| 组件 | 当前决定 | 引入/重验条件 |
| --- | --- | --- |
| RabbitMQ | 选择 RabbitMQ，当前参考 4.3.6 | 阶段 2 重选受支持维护线及镜像；验证持久化、确认、重投递、死信、恢复；当前没有 Broker 运行证据 |
| Redis | 首期不引入，不冻结版本 | 有缓存/限流需求后检查许可、容量、失效与数据库降级路径 |
| Loki | 集中日志优先方案，未冻结版本 | 有采集需求后确定版本、敏感字段脱敏、保留期、查询与告警 |
| Prometheus / Grafana / OpenTelemetry | 保留架构方向，未冻结版本 | 观测任务中验证采集、导出器、告警、关联 ID 和开销 |
| Testcontainers / PostgreSQL 镜像 | 后端真实数据库测试所需 | Docker 可用后选版本并记录不可变镜像引用；不能用 H2 替代排斥约束和锁竞争验收 |

RabbitMQ 4.3 的社区支持截止为 2027-01-31，不能把今天选出的补丁直接承诺给较晚的上线日期；Redis 8 提供多种许可证选择，引入前核对具体分发与使用条件。[RabbitMQ 生命周期](https://www.rabbitmq.com/release-information)、[Redis 许可证](https://redis.io/legal/licenses/)。

## 5. 安全与升级规则

本次原前端及 Element Plus 候选的 npm audit 均返回 0 条已知漏洞，详见保存的 JSON；这只是当时 npm 数据库覆盖范围的结果。后端仅抽查 Spring 官方公告：CVE-2026-41707、CVE-2026-47842、CVE-2026-47841、CVE-2026-47877 在 7.1 系列的修复版本均为 7.1.1，当前解析到该版本。没有完成全量后端 SCA、许可证或镜像扫描。

公告：[DPoP 重放](https://spring.io/security/cve-2026-41707/)、[加密问题](https://spring.io/security/cve-2026-47842/)、[WebAuthn](https://spring.io/security/cve-2026-47841/)、[授权页 XSS](https://spring.io/security/cve-2026-47877/)。适用性仍需结合后续使用功能分析。

每次引入组件或发布前：重新核验维护状态及漏洞；保存精确依赖树/锁文件；运行相应构建、测试、真实数据库和接口检查；在 ADR 记录变化与回退。不得因为本次构建通过就免除后续版本变更验证。
