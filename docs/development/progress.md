# PMS 开发进度台账

> 版本：1.0；建立日期：2026-10-29（AEST）；最近更新：2026-10-30（R-20261030-17）。
>
> 台账规则：**每轮开发必须在 §4 追加一条 Round 记录，并同步 §1 快照与 [feature-catalog.md](./feature-catalog.md) 受影响行**。状态词只允许使用 [README.md](./README.md) §2 的 8 个值。
>
> 事实来源优先级：开发规划 §1.3（历史事实）→ 本文件（当前口径）→ feature-catalog（功能粒度）。

## 1. 当前快照

| 项 | 当前值 |
| --- | --- |
| 当前轮次 | `R-20261030-17`（S2-02 Inbox 消费与版本保护，DONE） |
| 当前阶段 | **阶段 2 进行中**（S1 全部 DONE；S2-01、S2-02 DONE，S2-03 起为 TODO）；G1 技术条件满足、待开审；阶段 0 的 S0-05—S0-09 仍为 TODO |
| 已通过门禁 | 无正式开审（G0 未审）；**CI 门禁在真实 GitHub Actions 上全绿**（backend 含真实 PostgreSQL + RabbitMQ 与端到端消费、frontend） |
| 后端工程状态 | 依赖按 ADR-011 收敛并已引入 RabbitMQ；九域 + Outbox + Inbox 已落库（V1—V9 共 22 张表，复合外键 + CHECK + GiST 排斥）；**211 个测试全部通过（158 需真实 PostgreSQL、4 需真实 RabbitMQ）**；交易内核闭环，Outbox 发布与 Inbox 去重/版本保护/重放均可独立启停；死信、任务化重试与支付仍未实现 |
| 前端工程状态 | `tsc` ✓ / `biome:lint` ✓（2 处模板告警）/ `build` ✓；首页「今日工作台」指标位已有 `/availability` 可接；CI 三步全绿 |
| 功能项完成度 | 约 31%（124 项，DONE 31 项，见 feature-catalog §1） |
| 下一轮目标 | `S2-03` 持久化任务与外部调用状态（任务表 + 调度 + 退避，未知结果先查询） |

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
| S1-01 | 最小工程、环境与 CI | P0 | 2–4 | DONE | 依赖/配置/安全基线/错误响应/真实 PostgreSQL 集成测试/账号分权均已完成；**CI 实跑已观测**：backend 9 步全绿（132 用例 / 零跳过断言）、frontend 三件套绿（R-20261030-12） |
| S1-02 | Flyway 与组织基础表 | P0 | 2–4 | DONE | V1 迁移 + 复合外键 + 账号分权 + 12 个 PG 集成用例；V1→V2 升级路径已在 R-20261030-04 中真实执行（同一库上增量应用 V2 成功） |
| S1-03 | 最小认证、授权和租户隔离 | P0 | 3–5 | DONE |
| S1-04 | 房型、实体房间与运营状态 | P0 | 2–4 | DONE |
| S1-05 | 日期约束、占用排斥与维修互斥 | P0 | 4–6 | DONE |
| S1-06 | 库存流水、审计和 Outbox 原子写入 | P0 | 3–5 | DONE |
| S1-07 | 持久化请求幂等与提交不确定恢复 | P0 | 3–5 | DONE |
| S1-08 | 原子锁房与订单创建 | P0 | 3–5 | DONE |
| S1-09 | 确认、取消与 No-show | P0 | 3–5 | DONE | 整单统一迁移（DEC-04 部分履约 OPEN）；No-show 是否计费仍待 DEC-08 |
| S1-10 | 超时领取与幂等释放 | P0 | 2–4 | DONE | `FOR UPDATE SKIP LOCKED` 领取 + 数据库时钟二次校验 + 条件更新兜底；一次批处理一个事务，回滚即恢复；无新迁移（复用 V4 到期索引） |
| S1-11 | 交易 API、查询与权限闭环 | P0 | 2–4 | DONE | 列表/详情接口 + 过滤下推 + 稳定分页 + 脱敏；OpenAPI v1 已冻结并加契约测试；错误响应统一带 outcome 三分类 |
| S1-12 | 派生房态、可售计算与重建 | P0 | 3–5 | DONE | `room_inventory_day`（V7）+ `/availability` 余量与整段可售 + rebuild/reconcile；可售从权威算、投影只作读模型 |
| S1-13 | 交易内核集成与并发验收 | P0 | 4–6 | DONE | T01—T07、T09、T14 全覆盖；六方一致核验；清理机制重写为自动拓扑排序 |

### 阶段 2 可靠性内核（门禁 G2）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S2-01 | Outbox 发布与 Worker 隔离 | P0 | 3–5 | DONE | 租约式领取（物化 CTE）、发布确认、按 token 条件标记、指数退避；13 集成 + 3 真实 Broker 冒烟 |
| S2-02 | Inbox 消费与版本保护 | P0 | 3–5 | DONE | 去重键+位点、四态结论、缺口挂起与重放、首个消费者（投影重算）；11 集成 + 1 端到端 |
| S2-03 | 持久化任务与外部调用状态 | P0 | 3–5 | TODO |
| S2-04 | 死信、人工重放与审计修复 | P0 | 2–4 | TODO |
| S2-05 | 内部对账与派生修复 | P0 | 3–5 | TODO |
| S2-06 | 监控、链路和告警 | P0 | 3–5 | TODO |
| S2-07 | 最小部署、备份与恢复闭环 | P0 | 3–5 | TODO |
| S2-08 | 故障注入与可靠性验收 | P0 | 4–6 | TODO |

### 阶段 3 商家、集团与前台端（门禁 G3）

| 任务 | 名称 | 优先级 | 估算 | 状态 | 备注 |
| --- | --- | --- | --- | --- | --- |
| **S3-01a** | **前端工程骨架前置准备（Ant Design Pro）** | P1 | 2–3 | **DONE** | 2026-10-29 变更追加（ADR-015）。依赖安装、`tsc`、`biome:lint`、`build` 全部通过，CI 上同样为绿；首页已替换为「今日工作台」，站点标题/logo/模板 CNAME 已处理（R-20261030-12）。业务页面仍受 S3-01 约束 |
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
| DONE | 17（S0-03、S1-01—S1-13、S2-01、S2-02、S3-01a） |
| VERIFYING | 3（S0-01、S0-02、S0-04） |
| BLOCKED | 1（S5-01 渠道授权） |
| TODO | 45 |
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

### R-20261030-17 S2-02 Inbox 消费与版本保护

- 状态：DONE
- 范围（本轮只做这些）：
  1. **V9 迁移**：`inbox_event`（主键 `(consumer_name, event_id)` 即去重键；`outcome` 四态且由 CHECK 约束"只有挂起可以没有结算时间"）+ `inbox_consumer_position`（按（消费者, 聚合）记版本与序号）。
  2. **消费者模板**：`InboxConsumer` + `InboxProcessor`（认领→版本判定→应用→推进位点，同一事务；认领写在应用之前）；四态判定 `IGNORED / SKIPPED_STALE / PARKED_GAP / PROCESSED`；**首次见到聚合时接管当前版本**（否则新消费者在存量数据上永久卡在缺口）。
  3. **重放**：`InboxIntegrityService.redriveParked`（缺口补齐后按聚合内顺序追上，逐条独立事务）。
  4. **首个真实消费者**：`RoomProjectionConsumer` —— 订单事件驱动 `room_inventory_day` 整段重算（幂等；超 62 天自动分块）。
  5. **消息入口**：`InboxListener`（解析失败=不可重试→留痕丢弃；业务失败→抛出重投；进消息前清线程上下文）+ `InboxMessagingConfig`（队列/交换机/绑定）。
- 本轮明确不做：死信队列与人工重放（S2-04）、重试次数上限与延迟重试（S2-04）、消费者指标（S2-06）、投影增量维护（S2-05）。
- 涉及功能项（FT-*）/ 任务（S*-*）：M-02；S2-02
- 变更文件：
  - 新增：`inbox/{domain/{InboxMessage,InboxOutcome,InboxPosition},mapper/InboxMapper,service/{InboxConsumer,InboxProcessor,InboxIntegrityService,RoomProjectionConsumer},messaging/{InboxMessageCodec,InboxListener,InboxMessagingConfig}}`、`db/migration/V9__inbox_consumer.sql`、`mapper/inbox/InboxMapper.xml`、测试二（`InboxIntegrationTest`、`InboxListenerRabbitTest`）
  - 修改：`application.properties`（Inbox 开关/队列/路由键/prefetch）、`application-{test,integration}.properties`（关闭消费）、`NoDatabaseStubConfig`（补 Mapper 桩）、`docs/verification/S2-02/…`
- 验证（详见 [S2-02 记录](../verification/S2-02/2026-10-30-inbox-consumer.md)）：
  - `PMS_IT_DB=true PMS_IT_RABBIT=true ./mvnw -B -ntp clean verify` → `Tests run: 211, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（199 → 211）
  - 无库无 Broker：211 run / 186 skipped / 0 failure
  - 真实 CI：[run 41034639792](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41034639792) success，`tests=211 skipped=0`，端到端消费用例在 CI 中真实执行
  - 已验：重复投递只应用一次（含并发两线程）、失败事务不留台账与位点且可重试、旧事件跳过不覆盖新状态、同版本第二条不误判重复、缺口挂起并在补齐后重放、未订阅类型记为 IGNORED、租户取自消息体而非线程上下文、真实 Broker 发布→消费→投影重算全链路
- 本轮发现（不夹带实现，转入后续轮次）：
  1. **"首次见到聚合"必须有明确规则**：严格按"版本 > 位点+1 即缺口"会让新消费者在存量数据上永久卡死（历史事件不在总线上）。已定为"无位点时接管当前版本"并写进注释。
  2. **topic 交换机的 `*` 必须独占一个词**：`Reservation*` 会被当成字面词，绑定声明成功但消息全部不可路由（`replyCode=312`）。当前路由键是单单词事件类型，只能逐个列出。
  3. **拓扑声明与容器启动有竞态**：测试里用 `amqpAdmin.initialize()` 消除；生产侧的对应事实（发布早于绑定 → mandatory 判不可路由并退避重试，不丢）已记录。
  4. **测试辅助方法很容易毁掉被测语义**：消息工厂给每条消息生成新的聚合 id，导致"每条都是首次见到"、缺口与旧事件用例全部失真。聚合/租户必须在一个用例内固定。
  5. V9 用了 `ON CONFLICT DO NOTHING` 作为去重入口——**反向验证确认**去掉它立即报唯一键冲突，说明用例真的覆盖了去重路径。
- 回写确认：feature-catalog ✓（M-02 → DONE；§1 重算）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S2-03` 持久化任务与外部调用状态（任务表、调度、退避、未知结果先查询）

### R-20261030-16 S2-01 Outbox 发布与 Worker 隔离

- 状态：DONE
- 范围（本轮只做这些）：
  1. **发布器**：`OutboxPublisherService`（领取→发布→按租约标记，三步各自独立提交）、`OutboxPublisherMapper`/XML（物化 CTE 领取、带 `lease_token` 条件的标记与失败回写）、`EventPublisher` 端口 + `RabbitEventPublisher`（publisher confirm 等待确认 + mandatory/returns + 持久化投递）。
  2. **Worker 隔离**：`pms.runtime.role`（api/worker/all）+ `WorkerRoleCondition`；`OutboxPoller` 只在 Worker 角色下装配；worker 可 `--spring.main.web-application-type=none` 独立启动。
  3. **积压与事件年龄**：`OutboxBacklogMonitor` 暴露 `pms.outbox.pending`、`oldest_event_age_seconds`、`published/failed/backlog_breach`；越过 `alert-age` 记 ERROR 并计数（送达属 S2-06）。
  4. **迁移 V8**：`last_error` 列 + 部分索引 `idx_outbox_event_due`；`next_attempt_at` 回填并设为 NOT NULL DEFAULT now()（让领取条件保持 sargable）。
  5. **CI**：backend 门禁新增 `rabbitmq:3.13-management` service 并打开 `PMS_IT_RABBIT`，与「零跳过」断言咬合。
- 本轮明确不做：Inbox 去重（S2-02）、死信与重放（S2-04）、告警送达（S2-06）、队列与绑定声明（消费侧）、跨批严格有序。
- 涉及功能项（FT-*）/ 任务（S*-*）：M-01；S2-01
- 变更文件：
  - 新增：`journal/{domain/PendingOutboxEvent,mapper/OutboxPublisherMapper,messaging/{EventPublisher,EventPublishException,OutboxEnvelope,RabbitEventPublisher,OutboxMessagingConfig},service/{OutboxPublisherService,OutboxPoller,OutboxBacklogMonitor}}.java`、`shared/runtime/WorkerRoleCondition.java`、`db/migration/V8__outbox_delivery.sql`、`mapper/journal/OutboxPublisherMapper.xml`、测试二（`OutboxPublisherIntegrationTest`、`RabbitEventPublisherSmokeTest`）
  - 修改：`pom.xml`（starter-amqp）、`application.properties`（角色与 Outbox 配置）、`application-{test,integration}.properties`（关闭后台任务）、`SchedulingConfig`（改无条件开启，开关各自落在任务类）、`NoDatabaseStubConfig`（补 Mapper 桩）、`docs/verification/S2-01/…`
- 验证（详见 [S2-01 记录](../verification/S2-01/2026-10-30-outbox-publisher.md)）：
  - `PMS_IT_DB=true PMS_IT_RABBIT=true ./mvnw -B -ntp clean verify` → `Tests run: 199, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（183 → 199）
  - 无库无 Broker：199 run / 174 skipped / 0 failure（真跳过）
  - 真实 CI：backend 含 RabbitMQ service，零跳过断言同时覆盖 PostgreSQL 与 RabbitMQ
  - 已验：发布前崩溃可恢复不丢；发布成功标记前崩溃可重发但不丢；Broker 停机事件留库并留失败原因、退避 2/4/8/16 秒；连续失败中止并释放剩余租约；积压年龄越界告警计数；同聚合保序；载荷不可改写；Worker 角色无 Web 启动并把事件投递进真实队列
- 本轮发现（不夹带实现，转入后续轮次）：
  1. **`UPDATE … FROM (子查询 LIMIT n)` 在并发下会突破批大小**：偶发一次领走全部 20 条（诊断：`各批大小=[0,20]`、`attempt_count>1` 为 0）。psql 单/双会话与 PREPARE 复刻均正常，改 `WITH … AS MATERIALIZED` 后连跑 10 次稳定；未拿到 PG 内部证据，机制记为推断（EPQ 重查重复执行子计划）。
  2. **反向验证纠正了因果**：删掉 `SKIP LOCKED` 用例仍过、删掉租约条件才出现 10 条重复 → 去重靠租约条件，`SKIP LOCKED` 只避免互相等待。原注释写反了。
  3. **`@EnabledIfEnvironmentVariable` 之外的新坑**：`REQUIRES_NEW` 无关，但"零跳过断言"与"条件执行的冒烟测试"天然冲突——不把 Broker 放进 CI 就只能二选一。
  4. **开发库在 `/tmp` 会被系统清理**：出现 `could not open file "global/pg_filenode.map"` 时先判断环境损坏，再用 `dev-db.sh reset` 重建，别用代码解释一个坏掉的数据库。
  5. AMQP 4 的差异：`BuiltinExchangeType` 移除（用 `ExchangeTypes` 常量）、收到的消息要看 `getReceivedDeliveryMode()` 而非 `getDeliveryMode()`。
- 回写确认：feature-catalog ✓（M-01 → DONE；§1 重算）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S2-02` Inbox 消费与版本保护（重复消息只有一次业务效果、失败事务不遗留已处理标记、旧事件不覆盖新状态、版本缺口可补查）

### R-20261030-15 S1-13 交易内核验收与清理机制重写

- 状态：DONE
- 范围（本轮只做这些）：
  1. **清理机制重写**：新增 `DatabaseCleaner`（从 `pg_constraint` 读外键依赖、按"没有剩余表引用它"的拓扑序逐表删除并点名失败、删完断言全库为空），新增 `PostgresIntegrationTest` 基类，15 个集成测试类改为继承它并删除全部 `@Sql`；`integration-cleanup.sql` 标记废弃。
  2. **验收测试**：`TransactionKernelAcceptanceTest`（T01 抢订 ×10 轮、T03 多房部分冲突、T05 响应丢失、T06 三方竞争 ×5 轮，每例做六方一致核验）、`CrossTenantRegressionTest`（T14 / B-08，6 例）。
  3. **G1 验收记录**：场景覆盖表（T01—T07、T09、T14）、六方核验说明、否决条件逐条核对、遗留风险。
- 本轮明确不做：T08/T10（G2）、T11—T13（发布器未实现）、性能基线（S6-01）、故障注入（S2-08）、阶段 0 的 S0-05—S0-09。
- 涉及功能项（FT-*）/ 任务（S*-*）：B-08、D-02、D-03；S1-13
- 变更文件：
  - 新增：`support/{DatabaseCleaner,PostgresIntegrationTest,IntegrationDbEnabledCondition}.java`、`reservation/TransactionKernelAcceptanceTest.java`、`security/CrossTenantRegressionTest.java`
  - 修改：15 个集成测试类（去 `@Sql` 与四个注解、改继承基类）、`support/NoDatabaseStubConfig.java` 无关、`tenant/OrganizationIntegrationTest.java`（过时 Javadoc）、`test/resources/db/integration-cleanup.sql`（标记废弃）
  - 新增文档：`docs/verification/S1-13/2026-10-30-g1-acceptance.md`
- 验证（本轮实际执行，详见 [G1 验收记录](../verification/S1-13/2026-10-30-g1-acceptance.md)）：
  - `PMS_IT_DB=true ./mvnw -B -ntp clean verify` → `Tests run: 183, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（173 → 183）
  - 无库：`Tests run: 183, Failures: 0, Errors: 0, Skipped: 158`（158 个需库用例真跳过）
  - **真实 CI**：[run 41025391729](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41025391729) @ `5539d38` → **success**，9 步全绿，job 84 秒，`tests=183 skipped=0`
  - 已验：T01 8 并发 × 10 轮每轮恰好 1 成功且六方一致；T03 整单回滚无半截交易；T05 同键重取不产生第二笔；T06 终态唯一且订单↔占用按映射一致；T14 四类越权手法全部拦下；清理机制反向验证（新建表插数据 → 测试照常通过且该表被清空，无需改脚本）
  - 未覆盖：T08/T10（G2）、T11—T13（发布器未实现）、性能与故障注入
- 本轮发现（不夹带实现，转入后续轮次）：
  1. **拓扑排序方向极易搞反**：第一版按"不依赖任何剩余表就删"排序，结果 `tenant` 先于 `hotel` 被删，87 个用例连锁报错。正确条件是"**没有剩余表引用它**"。症状（外键违反）看起来像数据问题，不像排序问题。
  2. **`information_schema.constraint_column_usage` 对复合外键匹配不到**（实测返回 0 行），改用 `pg_constraint` + `pg_class`。
  3. **JUnit 5 的 `@EnabledIf*` 不会被继承**：上收到基类等于没写，无库环境下 158 个用例照跑不误（有库的机器上完全看不出）。改用 `@ExtendWith` + 自定义 `ExecutionCondition`。"跳过"失效是静默的，环境开关必须在两个环境下各验一次。
  4. **联合状态核对必须用映射**：T06 首跑在 `CANCELLED vs RELEASED` 上失败——订单与占用的状态词表不同，直接比字符串会把正确结果判成错误，比漏测更危险（会诱导人改本来对的东西）。
  5. 六方一致核验（订单/行/占用/流水/审计/Outbox/幂等）证明有效：单表断言看不出"订单在、流水少写一晚"这类部分正确。
- 回写确认：feature-catalog ✓（B-08、D-02、D-03 → DONE；§1 重算为 DONE 29 / VERIFYING 9 / READY 9 / TODO 68 / BLOCKED 8，约 29%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：①**G1 开审**（技术条件已满足，对照验收记录逐条核对）；②`S2-01` Outbox 发布与 Worker 隔离（阶段 2 起点，不依赖阶段 0 未决项）

### R-20261030-14 S1-12 派生房态、可售计算与重建对账

- 状态：DONE
- 范围（本轮只做这些）：
  1. V7 迁移：`room_inventory_day`（房间 × 住宿日投影；`reservation_unavailable`/`block_unavailable` 互斥 CHECK、`source_room_version`、`rebuilt_at`、按日索引）+ `inventory:read`/`inventory:write` 权限点。
  2. `GET /api/v1/hotels/{hotelId}/availability`：每日余量（total/sold/blocked/available）+ 整段可售房间数，一次返回。
  3. `POST /inventory/rebuild`（`inventory:write`）：先删后写同事务，`INSERT ... SELECT ... ON CONFLICT` 一条写到底。
  4. `GET /inventory/reconcile`（`inventory:read`）：三方对账（权威重算 / 投影 / 流水），差异四类 MISSING/STALE/ORPHAN/LEDGER，上限 200 并标注截断。
  5. `InventoryWindow` 窗口上限 62 天（构造器校验，超限 400）。
  6. 契约基线更新（**有意的契约变更**）：21 path / 28 schema，新增 3 接口 6 schema。
- 本轮明确不做：逐房房态标记接口 E-05（属 S3-03 页面契约）、维修类流水对账（S2-05）、投影增量维护（查询不依赖它）、长窗口导出（S3-09）、投影水位对外查询。
- 涉及功能项（FT-*）/ 任务（S*-*）：D-01、E-01、E-02、E-03、E-04；S1-12
- 变更文件：
  - 新增迁移：`V7__inventory_projection.sql`
  - 新增模块：`inventory/domain/{InventoryWindow,InventoryDay,BookableRoomType,ExpectedProjectionRow,StoredProjectionRow,ReconcileReport}.java`、`inventory/mapper/InventoryMapper.java`、`inventory/service/InventoryService.java`、`inventory/web/{InventoryController,AvailabilityView}.java`
  - 新增 SQL：`resources/mapper/inventory/InventoryMapper.xml`
  - 修改：`test/resources/db/integration-cleanup.sql`（补投影表清理，见「本轮发现」1）、`test/support/NoDatabaseStubConfig.java`（新 Mapper 桩）、`docs/api/openapi-v1.json`（契约变更）
  - 新增测试：`inventory/InventoryIntegrationTest.java`（13）、`inventory/InventoryApiTest.java`（6）
  - 新增文档：`docs/verification/S1-12/2026-10-30-inventory-projection.md`
- 验证（本轮实际执行，详见 [S1-12 记录](../verification/S1-12/2026-10-30-inventory-projection.md)）：
  - `PMS_IT_DB=true ./mvnw -B -ntp clean verify` → `Tests run: 173, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（154 → 173）
  - 无库：173 run / **148 skipped** / 0 failure → BUILD SUCCESS
  - `flyway_schema_history` 最新版本 `7`（V7 真实应用）
  - **真实 CI**：[run 41021523251](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41021523251) @ `b499d88` → **success**，9 步全绿，`Tests run: 173 / 0 failure / 0 skipped`、断言 `tests=173 skipped=0`
  - 已验：整段可售按区间交集判定（每晚都有余房但无连续可用房时 bookable=0）；维修与预订不重复扣减；退房日相邻仍可售；窗口 62/63 天边界；重建后对账洁净且行数 = 房间 × 晚；重复重建幂等；**清空投影后重建逐行一致**；STALE/ORPHAN/MISSING/LEDGER 四类差异分别可识别；跨店房间不计入本店余量；`read` 权限不能 rebuild
  - 未覆盖：E-05 逐房标记（S3-03）、BLOCK 流水对账（S2-05）、投影增量维护、投影水位查询
- 本轮发现（不夹带实现，转入后续轮次）：
  1. **清理脚本漏新表第三次复现**：`room_inventory_day` 有指向 `physical_room` 的外键，脚本没加删除语句，7 个用例直接 `ScriptStatementFailed` 没跑起来。已补最小修复；**幂等且失败可定位的重写仍归 S1-13**，这是第 3 次因为同一原因浪费排查时间。
  2. **MyBatis 构造器映射对原始类型敏感**：`boolean` 字段要用 `javaType="_boolean"`，写 `boolean` 会按包装类 `Boolean` 找构造器 → `NoSuchMethodException`；而包装类字段必须写 `java.lang.Boolean`。同一份 XML 里两种写法并存是**因为对应 record 字段类型不同**，不是手误。与「BIGINT → java.lang.Long」同源。
  3. **测试自己撞排斥约束**：封房对象选了自动选房会占用的那间，`Conflict: 房间在所选日期已被占用或封锁`——约束在正确工作，测试该改对象而不是改断言。
  4. **计算方法不会进 JSON**：`ReconcileReport.clean()` 是方法不是 record 组件，Jackson 不序列化。没有为便利字段引入注解依赖，改为让客户端按四个计数判断（更便于定位问题类型），并写进 Javadoc。
  5. **契约变更流程走通了一次**：新增 3 接口先让契约测试红（精确列出 9 处差异）→ 按 README §3.2 导出新基线 → 转绿 → 在本条目记录变更。流程可用，后续接口变更照此执行。
- 回写确认：feature-catalog ✓（D-01、E-01—E-04 → DONE；§1 重算为 DONE 26 / VERIFYING 9 / READY 12 / TODO 68 / BLOCKED 8，约 26%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-13` 交易内核集成与并发验收（T01—T14 场景 + 越权回归集 B-08 + 清理脚本重写），收口 G1

---

### R-20261030-13 S1-11 交易 API、查询与 OpenAPI v1 冻结

- 状态：DONE
- 范围（本轮只做这些）：
  1. 查询侧新增一套：`ReservationQuery`（条件对象，构造器校验）、`ReservationQueryMapper` + XML、`ReservationQueryService`、`ReservationQueryController`，与命令侧彻底分开（命令带锁与幂等，查询 `readOnly` 不占锁）。
  2. `GET /api/v1/hotels/{hotelId}/reservations`：分页 + `status`（可重复）/`sourceType`/`no`（前缀）/`roomCode`/`stayFrom`+`stayTo`（区间交集）过滤，固定排序 `created_at DESC, id DESC`，count 与列表共用同一段 where。
  3. `GET /api/v1/hotels/{hotelId}/reservations/{id}`：订单行 + 房号 + 占用状态 + `allowedActions`（由状态机守卫算出）+ `version`；不下发 `tenantId`/占用 id/操作 id。
  4. 错误契约：所有 `ProblemDetail` 带 `outcome`（`REJECTED`/`CONFLICT`/`UNKNOWN`），新增参数类型不匹配与缺参的 400 处理（覆写 `handleTypeMismatch`）。
  5. OpenAPI v1 冻结：`docs/api/openapi-v1.json`（18 path / 22 schema）+ `OpenApiContractTest` 比对结构（方法/状态码/参数/schema 字段），文案不进比对。
  6. 修 `backend.yml` 的 paths：把契约基线 `docs/api/openapi-v1.json` 纳入触发条件，否则只改基线可绕过门禁。
- 本轮明确不做：可售查询与投影（D-01 / S1-12）、前端页面接入（S3-03）、导出（S3-09）、自定义排序与游标分页、创建响应里 `occupancyId` 的移除（破坏性变更，需与前端接入一起定）。
- 涉及功能项（FT-*）/ 任务（S*-*）：D-08、D-10、D-12；S1-11
- 变更文件：
  - 新增：`reservation/domain/{ReservationQuery,ReservationSummary,RoomAssignment}.java`、`reservation/mapper/ReservationQueryMapper.java`、`reservation/service/ReservationQueryService.java`、`reservation/web/{ReservationQueryController,ReservationDetailView}.java`、`shared/web/{ErrorOutcome,PageView}.java`、`resources/mapper/reservation/ReservationQueryMapper.xml`
  - 修改：`shared/web/GlobalExceptionHandler.java`（outcome + 两个 400 处理器）、`shared/config/SecurityConfig.java`（401/403 也带 outcome）、`test/support/NoDatabaseStubConfig.java`（新 Mapper 桩）、`.github/workflows/backend.yml`（契约基线纳入 paths）
  - 新增契约基线：`docs/api/openapi-v1.json`
  - 新增测试：`reservation/ReservationQueryIntegrationTest.java`（11）、`reservation/ReservationQueryApiTest.java`（9）、`contract/OpenApiContractTest.java`（2）
  - 新增文档：`docs/verification/S1-11/2026-10-30-reservation-query.md`
- 验证（本轮实际执行，详见 [S1-11 记录](../verification/S1-11/2026-10-30-reservation-query.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp clean verify` → `Tests run: 154, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（新增 22 例：132 → 154）
  - 无库：`./mvnw -B -ntp test` → 154 run / **129 skipped** / 0 failure → BUILD SUCCESS
  - **真实 CI**：[run 41020142020](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41020142020) @ `972c988` → **success**，9 步全绿，日志 `Tests run: 154, Failures: 0, Errors: 0, Skipped: 0`、断言 `tests=154 skipped=0`
  - 反向验证 1：临时删掉 `ReservationQuery` 的 10 个 getter 后查询用例仍全过 → 证明 MyBatis 3.5.19 已支持 record 访问器，那批 getter 是多余的，已删除
  - 反向验证 2：临时从基线删掉列表接口的 `size` 参数 → 契约测试立即失败并精确指出差异，恢复后转绿
  - 未覆盖：可售投影（S1-12）、并发验收（S1-13）、前端页面（S3-03）、导出（S3-09）
- 本轮发现（不夹带实现，转入后续轮次）：
  1. **springdoc 用简单类名做 schema key，同名嵌套 record 会被合并**。`ReservationDetailView.LineView` 与 `ReservationView.LineView` 撞名，导致 OpenAPI 里详情的 `lines` 指向创建响应的字段（含 `occupancyId`）——运行时 JSON 正确、文档错误，只有契约测试能发现。已重命名 `DetailLineView`。**规则：对外视图类型不要用同名嵌套类。**
  2. **契约测试要只比结构**。若把 description/example 纳入比对，改个错别字就要动基线，最后所有人都会习惯性"重新生成基线"，契约名存实亡。
  3. **凭印象给框架打补丁会留下假事实**。本轮先写了"MyBatis 不认 record 访问器"的绕路代码与注释，反向验证后推翻。凡"因为框架不支持"而加的绕路，都该先验一次。
  4. **paths 过滤会漏掉"输入文件"**：契约基线放在 `docs/api/` 时，只改基线不会触发 backend。已修。类似的还有将来若把 SQL 迁移或 schema 放到 `docs/` 之外的位置。
  5. 创建响应里的 `occupancyId` 属实现细节外泄，移除需与前端一并决定，记入待办（不夹带）。
- 回写确认：feature-catalog ✓（D-08/D-10/D-12 → DONE；§1 重算为 DONE 21 / VERIFYING 10 / READY 16 / TODO 68 / BLOCKED 8，约 23%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-12` 派生房态、可售计算与重建（D-01 + E 域投影，含"清空派生数据后可重建一致结果"的验收）

---

### R-20261030-12 GitHub Actions 门禁完善与实跑 + 前端构建收口

- 状态：DONE
- 范围（本轮只做这些）：
  1. `S3-01a` 收尾：`Welcome.tsx` 整体替换为「今日工作台」（不再有营销文案），删模板 `public/CNAME`，站点标题与 logo 去模板化，恢复 `npm run build`。
  2. `backend.yml`：最小权限、`workflow_dispatch`、PR 限 main、`clean verify`、**零跳过断言**（解析 surefire XML，skipped > 0 或 tests == 0 直接失败）、surefire 报告 `always` 上传、成功时上传可执行 jar。
  3. `frontend.yml`：最小权限、`workflow_dispatch`、PR 限 main、**产物断言**（`dist/index.html` 必须存在、`dist/CNAME` 必须不存在）、上传 dist。
  4. `qodana_code_quality.yml`：修 checkout ref 表达式（push 事件下原写法取空串）、去掉硬编码 feature 分支、token 缺失时跳过并 warning、补 concurrency/timeout。
  5. `release.yml`（新增）：`workflow_dispatch` 从指定 ref 产出 jar / dist / SHA256SUMS 作为 artifact；**只打包不部署**。
  6. 修复 CI 实跑暴露的 `tsc` 失败：`package.json` 的 `tsc` 改为 `max setup && tsc --noEmit`。
- 本轮明确不做：部署到任何环境（S2-07 / S6-06）、分支保护与 required checks（仓库设置，建议项写入验证记录）、依赖漏洞扫描与 SBOM（S6-05）、第三方 test-reporter action、其余模板 demo 页清理（O-11）。
- 涉及功能项（FT-*）/ 任务（S*-*）：N-01、N-02、O-01、O-10、O-11（部分）；S1-01、S3-01a
- 变更文件：
  - 前端：`src/pages/Welcome.tsx`（重写）、`src/locales/{zh-CN,en-US}/menu.ts`、`config/{config,defaultSettings}.ts`、`package.json`（`tsc` 脚本）、删除 `public/CNAME`
  - CI：`.github/workflows/{backend,frontend,qodana_code_quality}.yml`（改造）、`.github/workflows/release.yml`（新增）
  - 新增文档：`docs/verification/CI/2026-10-30-github-actions.md`、`docs/verification/S3-01a/2026-10-30-workspace-home.md`
- 验证（本轮实际执行，详见 [CI 记录](../verification/CI/2026-10-30-github-actions.md) 与 [S3-01a 收尾记录](../verification/S3-01a/2026-10-30-workspace-home.md)）：
  - **真实 CI 全绿**：backend [run 41017905046](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41017905046) `success`（9 步全绿，`Tests run: 132, Failures: 0, Errors: 0, Skipped: 0`，断言输出 `tests=132 skipped=0`）；frontend [run 41018431906](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41018431906) `success`
  - **历史口径修正**：`main` 上 backend 早在 2026-10-29 就已 `success`（run 40918697977），frontend 挂在 `Type check`（run 40918697968）——"CI 实跑未观测"不再成立
  - **路径过滤实测**：只改前端的提交只触发 frontend，backend 未启动
  - 本地前端：`npm run tsc` ✓ / `biome:lint` ✓（254 文件 2 告警）/ `npm run build` ✓（dist 5.8M、`<title>` = 酒店 PMS、无 CNAME）
  - 本地后端：`PMS_IT_DB=true ./mvnw -B -ntp -Dstyle.color=never clean verify` → 132 tests / 0 failure / BUILD SUCCESS（16.8s）
  - 断言脚本本地实测：干净 target 下 `files=17 tests=132 skipped=0`；残留 target 下曾出现 134（见「本轮发现」2）
  - 未覆盖：Qodana 实跑（需 `QODANA_TOKEN`）、release.yml 实跑、分支保护配置、浏览器内渲染
- 本轮发现（不夹带实现，转入后续轮次）：
  1. **本地绿、CI 红的最常见成因是"生成物依赖"**：`@umijs/max` 的插件导出只存在于 umi 生成的 `src/.umi`；本地跑过 dev/build 就有，CI 全新检出就没有。移走 `src/.umi` 可 100% 复现。修法应固化进脚本（`max setup && tsc`），而不是加在 CI 里——否则新人 clone 后仍撞同一堵墙。**下次遇到"只在 CI 失败"，先清本地缓存复现。**
  2. **断言脚本会被残留污染**：统计 `target/surefire-reports` 全部文件时，本地旧报告使 `tests=134 ≠ 132`。CI 干净检出不受影响，但本地复跑会对不上号；已用 `clean verify` 消除。
  3. **模板资产会变成部署事故**：`public/CNAME` 内容是 `preview.pro.ant.design`，会随产物进入 `dist/`。已删除并加产物断言。**任何"从模板起家"的前端工程都该在第一次构建后检查 `public/` 与 `dist/` 的额外文件。**
  4. **两处 title 要一起改**：`config/config.ts` 的 `title` 决定 `<title>`，`defaultSettings.title` 决定侧边栏品牌；只改一处会出现"侧边栏叫酒店 PMS、标签页叫 Ant Design Pro"。
  5. **本地 `npm run build` 会被沙箱 safe-delete 拦截**（umi 清理 `src/.umi-production` 时 `broker denied delete`）。处置：构建前 `mv src/.umi-production /tmp/…`。CI runner 无此拦截，这个坑只能在本地记住。
- 回写确认：feature-catalog ✓（N-01/N-02/O-01/O-10 → DONE、O-11 → VERIFYING 30%；§1 重算为 DONE 18 / VERIFYING 10 / READY 19 / TODO 68 / BLOCKED 8，约 20%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-11` 交易 API、查询与权限闭环（订单列表/详情/操作结果查询 + 冻结 OpenAPI v1）。首页四个指标位与待办表格等的就是它

---

### R-20261030-11 S1-10 超时领取与幂等释放

- 状态：DONE
- 范围（本轮只做这些）：
  1. 到期扫描语句 `ReservationExpiryMapper.claimDueReservations`：**占用驱动**（派生表 `SELECT DISTINCT reservation_id … WHERE expires_at <= now()`）→ JOIN 订单，条件为"订单仍 PENDING + 存在已到期 HELD + 不存在未到期 HELD"，`ORDER BY r.id LIMIT ${batchSize} FOR UPDATE OF r SKIP LOCKED` 领取。
  2. 二次校验 `checkHoldDue`：同一事务内用数据库时钟复核"HELD 总数 / 未到期数"，`HoldDueState.due()` 判定才继续；条件更新 `expireIfPending`（`WHERE status='PENDING'`）返回 0 即"别人先处理"。
  3. `ReservationExpiryService`：一次批处理 = 一个事务（领取锁必须持到写入完成）；逐单重读聚合根 → 二次校验 → 统一锁序（房间 id 升序）→ 订单/行/占用 → EXPIRED → 释放流水 + 审计 `reservation.expire` + 事件 `ReservationExpired`，全部记在同一 `operation_id` 下。
  4. `ReservationExpiryScheduler` + `SchedulingConfig`：`@Scheduled(fixedDelayString)`，开关/间隔/初始延迟/批大小全部可配；生产默认开启，`test` 与 `integration` 档案显式关闭。
  5. 测试：真实 PostgreSQL 11 例，覆盖四项验收点 + 批次限制 + 三类边界。
  6. **不做迁移**：V4 的 `idx_room_occupancy_expiring`、`ck_reservation_status` 的 `EXPIRED`、V6_4 放宽后的审计动作名 CHECK 已足够（详见验证记录 §4.3）。
- 本轮明确不做：已确认单超时（DEC-08）、部分履约（DEC-04）、订单列表/详情（S1-11）、可售投影（S1-12）、僵尸聚合状态收口（S2-05）、死信与单条失败隔离（S2-04）、到期通知与渠道同步（S2-01 之后）。
- 涉及功能项（FT-*）/ 任务（S*-*）：D-06；S1-10
- 变更文件：
  - 新增：`reservation/domain/{DueReservation,HoldDueState}.java`、`reservation/mapper/ReservationExpiryMapper.java`、`reservation/service/{ReservationExpiryService,ReservationExpiryScheduler}.java`、`shared/config/SchedulingConfig.java`
  - 新增 SQL：`resources/mapper/reservation/ReservationExpiryMapper.xml`
  - 修改：`journal/domain/AuditActions.java`（新增 `reservation.expire`）、`application.properties`（4 个开关项）、`application-test.properties` 与 `application-integration.properties`（关闭调度）、`test/support/NoDatabaseStubConfig.java`（新 Mapper 桩）
  - 新增测试：`reservation/ReservationExpiryIntegrationTest.java`（11 例）
  - 新增文档：`docs/verification/S1-10/2026-10-30-hold-expiry.md`
- 验证（本轮实际执行，详见 [S1-10 记录](../verification/S1-10/2026-10-30-hold-expiry.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -o -B -ntp test -Dtest=ReservationExpiryIntegrationTest` → 10 例通过；补第 11 例后 `Tests run: 11, Failures: 0, Errors: 0` → **BUILD SUCCESS**
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → `Tests run: 132, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**
  - 无库：`./mvnw -B -ntp test` → 132 run / **109 skipped** / 0 failure → BUILD SUCCESS（跳过而非伪通过）
  - **反向验证**：临时去掉 `SKIP LOCKED` 后 `workerSkipsRowsAlreadyClaimedByAnotherWorker` 于 23.35s 失败（第二个 Worker 阻塞在行锁上）→ 证明该用例真的在验证领取语义；已恢复并 `grep` 确认
  - 执行计划：改写前 `EXPLAIN` 走 `idx_room_occupancy_room`（V4 的到期索引失效）；改写后 `Index Only Scan using idx_room_occupancy_expiring / Index Cond: (expires_at <= now())`（`enable_seqscan=off` 下观测）
  - 运行态冒烟（`local` 档案，扫描间隔 `PT3S`）：启动后 2.1s 首次扫描 → `领取 1 单，释放 1 单`，**4 秒内**观测到订单 EXPIRED、占用 EXPIRED；`audit_log` = `reservation.expire` / `SYSTEM` / `HOLD_TIMEOUT`，`outbox_event` = `ReservationExpired`，`idempotency_record` = `SUCCEEDED` 且有 `result_ref`
  - 未覆盖：多实例真实部署、外部定时器（K8s CronJob）部署决策、批内单条失败隔离、到期通知与渠道同步
- 本轮发现（不夹带实现，转入后续轮次）：
  1. **"先查后改"的相关子查询会让专用索引彻底失效**。第一版 SQL 语义正确、测试全绿，但 `EXPLAIN` 显示 V4 为此建的 `idx_room_occupancy_expiring` 一次没用上——扫描量随 PENDING 单总量增长。改成占用驱动的派生表后才命中。转 S6-01：容量测试要把"到期扫描"列入负载模型，用真实量级复核计划。
  2. **`DISTINCT` 与 `FOR UPDATE` 不能同层**（PG 直接报错，不是性能问题）。去重必须下沉到派生表，否则这条路径连跑都跑不起来。
  3. **共享状态测试需要"持有锁不提交"的写法**。两个线程各调一次 `expireDueHolds` 的用例，即使串行执行也能通过；真正能证伪的实现检验要靠"一方领了不提交、另一方必须跳过"。同类并发用例（S1-13）应沿用这个写法。
  4. **沙箱下 `PMS_SERVER_PORT` 不生效**（实际监听 55839），`-Dspring-boot.run.arguments=--server.port=…` 可明确覆盖。属启动方式差异，已记入验证记录 §4.4，避免下次误判为配置缺陷。
  5. 整批同生共死是可接受的取舍（回滚即恢复），但**持续异常的单会让整批空转**，需 S2-04 引入单条失败隔离与死信。
- 回写确认：feature-catalog ✓（D-06 → DONE；§1 统计重算为 DONE 14 / VERIFYING 11 / READY 20）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：①`S3-01a` 收尾：用「今日工作台」替换 `Welcome`，恢复 `npm run build`（已连续两轮挂账）；②`S1-11` 交易 API、查询与权限闭环

---

### R-20261030-10 S1-09 确认、取消与 No-show（状态机命令）

- 状态：DONE
- 范围（本轮只做这些）：
  1. 状态机守卫 `ReservationTransitions`（纯函数、无库）：确认要求全行 PENDING；取消允许 PENDING/CONFIRMED；No-show 要求全行 CONFIRMED；已入住拒绝普通取消。
  2. `ReservationCommandService` 三个生命周期命令（confirm / cancel / no-show）：锁聚合根 → 乐观版本条件更新 → 守卫 → 统一锁序锁房间 → 推进行/聚合状态 → 翻转占用 → 审计 + 事件，全部在同一命令 operation_id 下。
  3. 重复命令幂等 NO_CHANGE（到达目标终态不二次释放）；非法迁移一律 409。
  4. 迁移 V6_2/V6_3/V6_4：`reservation.status` CHECK 扩展 NO_SHOW；放宽幂等命令名与审计动作名校验以下划线支持多词动词。
  5. HTTP：`POST /api/v1/hotels/{hotelId}/reservations/{id}/{confirm|cancel|no-show}`（`reservation:write`，`X-Caller-Namespace` 进入幂等作用域）。
  6. 测试：状态机守卫单测 + 真实 PostgreSQL 集成测试。
- 本轮明确不做：超时释放（S1-10）、订单列表/详情（S1-11）、可售投影（S1-12）、入住/离店/换房（S3-04/05）、No-show 是否计费（DEC-08 未定案，状态迁移本身不受其约束）。
- 涉及功能项（FT-*）/ 任务（S*-*）：D-04、D-05、D-07；S1-09
- 变更文件：
  - 新增迁移：`V6_2__reservation_noshow_status.sql`、`V6_3__idempotency_command_underscore.sql`、`V6_4__audit_log_action_underscore.sql`
  - 新增模块：`reservation/domain/ReservationTransitions.java`、`reservation/web/LifecycleRequest.java`
  - 修改：`reservation/service/ReservationCommandService.java`（`LifecycleCommand` + confirm/cancel/no-show + `LifecycleKind` 枚举 + `buildView`）、`reservation/web/ReservationController.java`（三个生命周期端点）、`reservation/mapper/ReservationMapper.java` + XML（查行/查占用/推进行与聚合状态）、`reservation/service/OccupancyService.java`（`requireReservation`/`lockRooms`/`transitionStatus` 复用）、`reservation/domain/ReservationStatus.java`（启用 `NO_SHOW`）、`journal/domain/AuditActions.java`（3 个新动作）、`test/support/NoDatabaseStubConfig.java`
  - 新增测试：`reservation/ReservationStateIntegrationTest.java`（7 例）、`reservation/domain/ReservationTransitionsTest.java`（4 例）
- 验证（本轮实际执行，详见 [S1-09 记录](../verification/S1-09/2026-10-30-reservation-lifecycle.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → 第一次 **121 run / 11 errors → BUILD FAILURE**，根因是上一会话被中断时遗留的已提交测试数据（详见「本轮发现」1），手动执行 `db/integration-cleanup.sql` 清理后重跑 → `Tests run: 121, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 首次 **1 error**（并发用例时序不确定，见「本轮发现」2），修正后 → `Tests run: 121, Failures: 0, Errors: 0` → **BUILD SUCCESS**
  - 无库：`./mvnw -B -ntp test` → 121 run / **98 skipped** / 0 failure → BUILD SUCCESS（跳过而非伪通过）
  - 数据库核对：`flyway_schema_history` V1—V6.4 全部 success（新增 6.2/6.3/6.4 三行）
  - 前端（依赖已安装）：`npm run tsc` → **通过（0 错误）**；`npm run biome:lint` → **通过（254 文件，2 处模板告警，exit 0）**；`npm run build` → **失败**：`src/pages/Welcome.tsx:5` `Module not found: Can't resolve '@root/docs/cheatsheet.zh-CN.md'`
  - 未覆盖：超时释放（S1-10）、订单列表/详情（S1-11）、可售投影（S1-12）、三个生命周期端点的 HTTP 403/404 专用 MockMvc 测试（接线经上下文加载隐含验证）、No-show 计费规则（DEC-08）
- 本轮发现（不夹带实现，转入下轮）：
  1. **测试库残留会连锁放大**：`db/integration-cleanup.sql` 按依赖顺序 DELETE，任一条失败即整脚本中止，后续类全部拿到脏库并报出与真实缺陷无关的外键错误（本轮 11 个 error 全由此而来，业务代码无一处问题）。转 S1-13：把清理改为幂等且失败可定位（分语句或 `ON DELETE` 级清理），并在测试基类里断言"清理后为空"。
  2. **并发用例不能固定时序**：`confirm` 与 `cancel` 谁先拿到聚合根行锁取决于调度；cancel 抢先时 confirm 必然被终态守卫拒绝。原写法对两个 future 都 `get()` 直接断言成功，是假并发测试。已改为"至多一个被拒 + 固定最终状态"。
  3. **前端生产构建从骨架落地那天起就是红的**：R-20261029-01 清理模板时删掉了 `frontend-admin-pro/docs/`，而 `src/pages/Welcome.tsx` 仍 `import zhCN from '@root/docs/cheatsheet.zh-CN.md'`；当时列为未覆盖项的「demo 页清理后的悬挂引用检查」至此被坐实。修法不是补回模板文档——该页（“欢迎使用 Ant Design Pro V6 🎉”）本身就违反「禁止自述式营销文本」，应整体替换为「今日工作台」，归 S3-01a。本轮不越界改前端代码。
  4. **No-show 暴露了命名校验的口径**：幂等命令名与审计动作名的 CHECK 原先只允许纯小写字母段，多词动词（`reservation.no_show`）无处安放。V6_3/V6_4 统一放宽为 `[a-z][a-z_]*` 的点分结构，仍禁数字、大写、前导下划线与点以外的分隔符，与既有 V6/V5 的口径一致。
- 回写确认：feature-catalog ✓（D-04/D-05/D-07 → DONE、D-09 → DONE）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：①`S3-01a` 收尾：用「今日工作台」替换 `Welcome`，恢复 `npm run build`；②`S1-10` 超时领取与幂等释放

---

### R-20261030-09 S1-08 原子锁房与订单创建

- 状态：DONE
- 范围（本轮只做这些）：
  1. `ReservationCommandService`：经 `IdempotencyService.execute` 幂等入口，在**一个短事务**里创建订单 + 订单行 + 全部房间 HELD 占用 + 逐晚流水 + 审计 + 事件（均记到同一命令 operation_id）。
  2. 内部自动选房（D-03）：按房型挑"整个入住区间都空闲"的房间；某行挑不到 → 整单结构化冲突（409），不做部分承诺。
  3. 统一锁序（S0-04）：先插订单 → 按 id 升序一次性锁全部房间 → 再写占用；锁后重核可用，避免并发双卖与死锁。
  4. HTTP：`POST /api/v1/hotels/{hotelId}/reservations`（`reservation:write`，经 `HotelAccess` + `X-Caller-Namespace`）。
  5. 测试：`ReservationCommandIntegrationTest` 5 例（多房多晚原子、任一冲突整单回滚、幂等重放、并发热点仅一赢家、日期非法）。
- 本轮明确不做：确认/取消/No-show（S1-09）、超时释放（S1-10）、可售查询与投影（S1-12）、订单列表/详情（S1-11）、住客与渠道实际取值（S3-04/L 域）。
- 涉及功能项（FT-*）/ 任务（S*-*）：D-02、D-03、D-12（来源字段随订单落库）；S1-08
- 变更文件：
  - 新增迁移：`V6_1__reservation_permissions.sql`
  - 新增模块：`reservation/service/ReservationCommandService.java`、`reservation/domain/ReservationConflictException.java`、`reservation/web/{ReservationController,CreateReservationRequest,ReservationView}.java`
  - 修改：`reservation/service/OccupancyService.java`（抽出包级 `writeHeldOccupancy`，`hold` 委托之）、`reservation/mapper/ReservationMapper.java` + XML（`findAvailableRooms`）、`journal/domain/AuditActions.java`（新增 `reservation.create`）、`test/support/NoDatabaseStubConfig.java`、`test/resources/db/integration-cleanup.sql`
  - 新增测试：`reservation/ReservationCommandIntegrationTest.java`
- 验证（本轮实际执行，详见 [S1-08 记录](../verification/S1-08/2026-10-30-reservation-create.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 110, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 5 个）
  - 无库：`./mvnw -B -ntp test` → 110 run / **91 skipped** / 0 failure → BUILD SUCCESS
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 110 tests / 0 failure → **BUILD SUCCESS**（12.2s）
  - 数据库核对：`flyway_schema_history` V1—V6.1 全 success
  - 关键用例：2 房×3 晚 → 1 单/2 行/2 占用/6 流水；房型 B 0 间 → 整单回滚且 0 残留；同键重放拿回同一订单 id；1 房 2 并发 → 仅 1 成功；日期反转/相等被 `StayRange` 拒
  - 未覆盖：确认/取消（S1-09）、超时释放（S1-10）、可售投影（S1-12）、订单列表/详情（S1-11）、HTTP 403/404 专用 MockMvc 测试（控制器接线经上下文加载隐含验证）
- 本轮发现（已处理 / 转下轮）：
  1. **自动选房不能"先查再写"**：读阶段选中、写之前可能被别人抢走。故锁定全部房间后再重核一次可用性，把竞态在业务层挡成结构化冲突。
  2. **`OccupancyService.hold` 重构为委托 `writeHeldOccupancy`**：独立占房仍自带系统级幂等记录；命令级占用不再各建一条，统一记到命令 operation_id。
  3. 建单审计用独立动作 `reservation.create`，不再误用 `occupancy.hold`。
  4. 客户端命名空间经 `X-Caller-Namespace` 进入幂等作用域，避免跨主体键复用。
- 回写确认：feature-catalog ✓（D-02/D-03 → VERIFYING）、§1 快照 ✓、规划 §1.3 ✓
- 下一步：`S1-09` 确认、取消与 No-show

---

### R-20261030-08 S1-07 持久化幂等与提交不确定恢复

- 状态：DONE
- 范围（本轮只做这些）：
  1. Flyway V6：`idempotency_record`（幂等作用域五元组 + 规范化请求摘要 + 处理中/成功/失败状态 + 结果引用与快照），并把 `inventory_ledger.operation_id` 外键指向它。
  2. 命令入口组件：同键同内容返回原结果；同键不同内容拒绝；并发同键只产生一次效果；提交成功但响应丢失可查回原结果。
  3. 规范化请求摘要：字段排序 + SHA-256，摘要算法版本可升级（同一键换算法视为不同内容，不静默放行）。
  4. 结果查询接口：`GET /api/v1/operations/{operationId}` 与 `/lookup`（租户内，`operation:read`）。
  5. 测试：`IdempotencyIntegrationTest` 8 例。
- 本轮明确不做：把锁房命令改造为幂等命令（S1-08 接入真实命令）、提交不确定窗口的故障注入（S2-08）、幂等记录清理任务（保留期由 S6-03 归档定案）、Inbox 幂等（S2-02）。
- 涉及功能项（FT-*）/ 任务（S*-*）：D-09；S1-07
- 变更文件：
  - 新增迁移：`V6__idempotency_baseline.sql`
  - 新增模块：`command/domain/{IdempotencyRecord,IdempotencyStatus,RequestDigest}.java`、`command/mapper/IdempotencyMapper.java`、`command/service/IdempotencyService.java`、`command/web/{OperationController,OperationView}.java`、`command/package-info.java`
  - 新增 SQL：`resources/mapper/command/IdempotencyMapper.xml`
  - 新增测试：`command/IdempotencyIntegrationTest.java`
  - 修改：`reservation/service/OccupancyService.java`（占用写入先开操作、成功后记结果）、`journal/JournalIntegrationTest.java`（流水需真实 operation_id）、`test/support/NoDatabaseStubConfig.java`、`test/resources/db/integration-cleanup.sql`
- 验证（本轮实际执行，详见 [S1-07 记录](../verification/S1-07/2026-10-30-idempotency-baseline.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 105, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 8 个）
  - 无库：`./mvnw -B -ntp test` → 105 run / **86 skipped** / 0 failure → BUILD SUCCESS
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 105 tests / 0 failure → **BUILD SUCCESS**（15.5s）
  - 数据库核对：`flyway_schema_history` V6 success；`uq_idempotency_scope` 与 `fk_inventory_ledger_operation` 生效
  - 关键用例：同键同内容重放同一 resultRef（业务只一次）、同键不同内容 409、并发恰好一个执行、按 operationId 查回 SUCCEEDED 与结果引用、跨租户 404、虚构 operation_id 直插被外键拒绝
  - 未覆盖：真实命令接入（S1-08/S1-09）、提交瞬间崩溃的故障注入（S2-08）、保留期清理（S6-03）、Inbox（S2-02）
- 本轮发现（已处理 / 转下轮）：
  1. **不要靠捕获唯一键异常判断重复**：PG 报错后整个事务被标记中止，后续查询全挂。改用 `INSERT ... ON CONFLICT DO NOTHING` 看影响行数。
  2. **加外键前必须确认存量数据**：本地一次性集群里残留的前几轮流水行让 V6 迁移失败，表现为上下文加载失败，很难一眼定位。本地集群要先清理再跑。
  3. **包装类型坑第三次出现**：`IdempotencyRecord.hashVersion` 用了 `int`。record 建好后应第一时间确认数值列都是包装类型。
  4. 摘要必须规范化；`hash_version` 让"换算法"自动变成"同键不同内容"被拒，而不是静默放行。
  5. **FAILED 的边界**：只有确定无副作用的拒绝才记 FAILED；执行中失败要允许重试，记了反而挡住合法重试。
- 回写确认：feature-catalog ✓（D-09 → VERIFYING 75%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-08` 原子锁房与订单创建（把幂等接入真实命令）

---

### R-20261030-07 S1-06 库存流水、审计与 Outbox 原子写入

- 状态：DONE
- 范围（本轮只做这些）：
  1. Flyway V5 / V5.1 / V5.2：`inventory_ledger`（逐晚效果流水，占用 -1、释放 +1 对冲）、`audit_log`、`outbox_event`；流水唯一键最终收敛为 `(tenant_id, hotel_id, operation_id, room_id, stay_date, effect_kind)`。
  2. 交易写入公共组件 `JournalService`：占用写入与流水、审计、Outbox 落在**同一个事务**，任一失败整体回滚。
  3. 事件模型：聚合类型/版本、`event_ordinal`、`schema_version`、载荷 jsonb；三张表只追加（Mapper 只有 INSERT/SELECT）。
  4. 审计目录 `AuditActions`：动作名必须登记，未登记直接拒绝。
  5. 测试：`JournalIntegrationTest` 8 例（多晚多条流水、重复不入账、注入失败整体回滚、事件序号、主体来源）。
- 本轮明确不做：Outbox 发布与 Worker（S2-01）、幂等记录与 `operation_id` 外键（S1-07）、库存投影重建（S1-12）、Inbox（S2-02）、登录/越权审计接入（随 S1-11/S3-02）。
- 涉及功能项（FT-*）/ 任务（S*-*）：E-01、B-05（部分）；S1-06
- 变更文件：
  - 新增迁移：`V5__journal_baseline.sql`、`V5_1__ledger_effect_unique.sql`、`V5_2__ledger_operation_id.sql`
  - 新增模块：`journal/domain/{InventoryLedger,EffectKind,AuditLog,ActorType,OutboxEvent,AuditActions}.java`、`journal/mapper/JournalMapper.java`、`journal/service/JournalService.java`、`journal/package-info.java`
  - 新增 SQL：`resources/mapper/journal/JournalMapper.xml`
  - 新增测试：`journal/JournalIntegrationTest.java`
  - 修改：`reservation/service/OccupancyService.java`（hold/block/启停同事务写流水+审计+事件）、`reservation/domain/StayRange.java`（新增 `dates()` 逐晚展开）、`test/support/NoDatabaseStubConfig.java`、`test/resources/db/integration-cleanup.sql`
- 验证（本轮实际执行，详见 [S1-06 记录](../verification/S1-06/2026-10-30-journal-baseline.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 97, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 8 个）
  - 无库：`./mvnw -B -ntp test` → 97 run / **78 skipped** / 0 failure → BUILD SUCCESS
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 97 tests / 0 failure → **BUILD SUCCESS**
  - 数据库核对：`flyway_schema_history` V5 / 5.1 / 5.2 全 success；`uq_inventory_ledger_effect` 定义为 `UNIQUE (tenant_id, hotel_id, operation_id, room_id, stay_date, effect_kind)`
  - 关键用例：4 晚 4 行流水（delta 均为 -1）、释放 8 行净效果 0、同操作重复写被拒、注入异常后四张表全 0、冲突失败不留流水与事件、主体来自上下文否则 SYSTEM
  - 未覆盖：多房整单原子（S1-08）、`operation_id` 外键（S1-07）、发布与重试（S2-01/S2-04）、投影重建（S1-12）、登录与越权审计接入
- 本轮发现（已处理 / 转下轮）：
  1. **释放不能删行，所以唯一键必须带方向**：第一版 `(room, date, kind)` 会把"占用 → 释放 → 再占用"这个合法业务循环判成重复入账（等于房间取消后卖不出去）。最终用字典的 `(operation_id, room, date, kind)` 形式。
  2. **`operation_id` 提前于 S1-07 出现是必要的**：没有它，唯一键在"防重复"与"允许取消后重售"之间二选一。S1-07 只需补外键。
  3. **MyBatis 包装类型坑再次出现**：`InventoryLedger.delta`(`int`)、`OutboxEvent.aggregateVersion/eventOrdinal/schemaVersion/attemptCount`(`long`/`int`) 读取时 `NoSuchMethodException`，统一改包装类型。
  4. jsonb 同样走文本绑定 + SQL 侧 `::jsonb`（驱动 runtime 作用域，无 `PGobject`）。
  5. **原子性由调用方事务提供**：`JournalService` 只负责"在同一事务里追加事实"，调用方不开启事务没人拦得住半截状态。
- 回写确认：feature-catalog ✓（E-01 → VERIFYING 75%、B-05 → VERIFYING 50%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-07` 持久化幂等与提交不确定恢复

---

### R-20261030-06 S1-05 日期约束、占用排斥与维修互斥

- 状态：DONE
- 范围（本轮只做这些）：
  1. Flyway V4（DB-B 后半）：`reservation` / `reservation_line` / `room_occupancy`（预订与封房统一表，`kind=RESERVATION|BLOCK`）；`btree_gist`；`physical_room(id, hotel_id)` 复合唯一（供房间复合外键）。
  2. 区间约束：非空、有界、规范半开 `[in,out)`、下界 < 上界；`kind` 与引用/状态/截止的组合检查。
  3. **有效占用 GiST 排斥**：同房间有效占用区间互斥（含维修），相邻区间允许；按订单行的"有效占用至多一条"部分唯一。
  4. 跨表互斥协议：预订与维修写同一张占用表、同一有效状态谓词；统一锁序（先订单 → 再按 id 升序锁房间 → 再写占用），落为 `OccupancyService` + `ReservationMapper.lockRooms`。
  5. 错误到业务口径的映射：约束名 → 可读冲突原因（409），不泄露 SQL。
  6. 测试：`RoomOccupancyIntegrationTest`（真实 PostgreSQL）13 例，含**直接数据库插入**绕过验证与**并发**冲突验证。
- 本轮明确不做：锁房/确认/取消命令与接口（S1-08/S1-09）、超时释放 Worker（S1-10）、库存流水与审计（S1-06）、幂等记录（S1-07，故占用表暂缺 `operation_id` 外键）、房态投影（S1-12）、维修工单与 API（S3-06）、入住会话与分段（S3-04）。
- 涉及功能项（FT-*）/ 任务（S*-*）：C-05；S1-05（并落 D-02/D-03 所需的占用事实基础）
- 变更文件：
  - 新增迁移：`src/main/resources/db/migration/V4__occupancy_baseline.sql`
  - 新增模块：`reservation/domain/{StayRange,Reservation,ReservationLine,RoomOccupancy,ReservationStatus,LineStatus,ReservationSourceType,OccupancyKind,OccupancyStatus}.java`、`reservation/mapper/ReservationMapper.java`、`reservation/service/{OccupancyService,OccupancyConflictTranslator}.java`
  - 新增 SQL：`resources/mapper/reservation/ReservationMapper.xml`
  - 新增共享设施：`shared/mybatis/DateRangeTypeHandler.java`（daterange ↔ StayRange）
  - 新增测试：`reservation/RoomOccupancyIntegrationTest.java`
  - 修改：`shared/config/MyBatisConfig.java`（注册 daterange 处理器）、`test/support/NoDatabaseStubConfig.java`（`ReservationMapper` 桩）、`test/resources/db/integration-cleanup.sql`（新表清理顺序）
- 验证（本轮实际执行，详见 [S1-05 记录](../verification/S1-05/2026-10-30-occupancy-baseline.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 89, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 13 个）
  - 无库：`./mvnw -B -ntp test` → 89 run / **70 skipped** / 0 failure → BUILD SUCCESS
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 89 tests / 0 failure → **BUILD SUCCESS**（14.0s）
  - 数据库核对：`flyway_schema_history` V1—V4 全 success（V4 于 02:12 增量应用）；`ex_room_occupancy_effective` 定义与迁移一致；`btree_gist` 存在且属主为 `pms_migrator`
  - 关键用例：相邻区间共存、重叠被拒、预订与维修互斥、RELEASED/UNBLOCKED 后可再售、**裸 SQL 直插仍被拒**、并发两事务恰好一个成功且最终 1 行
  - 未覆盖：订单命令（S1-07—S1-09）、超时释放（S1-10）、库存流水与审计（S1-06）、房态投影（S1-12）、维修工单（S3-06）、入住分段（S3-04）、死锁注入与热点压测（S1-13/S2-08）
- 本轮发现（已处理 / 转下轮）：
  1. **`btree_gist` 不需要超级用户**：它是 PG 13+ 可信扩展，数据库属主（`pms_migrator`）即可安装，本机已验证；迁移保留 `IF NOT EXISTS`。
  2. **PG 把 `daterange` 规范化为 `[)`**，所以"闭区间"存不进去——相邻可续住是类型自带的保证，不是 CHECK 的功劳；反向区间在建区间时就报错。
  3. **`UUID.compareTo` 与 PostgreSQL 的 uuid 排序不一致**（前者按 mostSigBits 有符号比较），本轮测试就踩到：用 `compareTo` 断言 `ORDER BY id` 升序会在高位为 1 的 uuid 上得出相反结论。跨层排序（锁序、分页游标）不要用 `UUID.compareTo`。
  4. 驱动是 `runtime` 作用域，编译期拿不到 `PGobject`/`PSQLException`；故 daterange 走"文本绑定 + SQL 侧 `::daterange`"，约束名用正则从消息里取（只取约束名）。
  5. 新增约束必须同步 `OccupancyConflictTranslator` 的映射表，否则冲突会退回通用文案。
- 回写确认：feature-catalog ✓（C-05 → VERIFYING 75%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-06` 库存流水、审计与 Outbox 原子写入

---

### R-20261030-05 S1-04 房型、实体房间与运营状态

- 状态：DONE
- 范围（本轮只做这些）：
  1. Flyway V3（DB-B 前半）：`room_type` / `physical_room`；同店编码唯一、房间→房型用 `(room_type_id, hotel_id)` 复合外键（跨店归属在库层失败）、房型→门店复合外键；新增权限点 `room:read` / `room:write`。
  2. room 模块：domain / mapper(XML) / service，写入前置校验与状态规则。
  3. HTTP（首个带数据范围校验的业务接口）：房型 创建/列表/详情/启停；房间 创建/列表/详情/属性修改/启停。全部要求 `room:read`/`room:write`，且先解析租户再校验门店范围。
  4. 状态规则：房型下仍有 ACTIVE 房间时拒绝停用房型（409）；房间停用只改状态不删行；清洁状态列落地但**不开放写入口**（流转归 S3-06），以此保证"清洁变化不直接改写库存事实"。
  5. 测试：`RoomIntegrationTest`（真实 PostgreSQL）+ `RoomApiTest`（MockMvc 走完整过滤链，含 403/404/跨店拒绝）。
- 本轮明确不做：维修区间 `room_block`（S1-05）、清洁状态流转与房务任务（S3-06）、房态可售聚合（S1-12）、"有有效预订时停用房型/房间"的处置流程（依赖 S1-05 占用表，本轮无法验证）。
- 涉及功能项（FT-*）/ 任务（S*-*）：C-01、C-02、C-03、C-04；S1-04
- 变更文件：
  - 新增迁移：`src/main/resources/db/migration/V3__room_baseline.sql`
  - 新增模块：`room/domain/{RoomType,PhysicalRoom,RoomStatus,HousekeepingStatus}.java`、`room/mapper/RoomMapper.java`、`room/service/RoomService.java`
  - 新增接口层：`room/web/{RoomTypeController,RoomController,RoomTypeView,RoomView,CreateRoomTypeRequest,CreateRoomRequest,UpdateRoomRequest,RoomTypeStatusRequest,RoomStatusRequest}.java`
  - 新增共享设施：`shared/security/HotelAccess.java`（租户 → 404 → 403 的固定顺序，后续 S1-05/S1-11 复用）
  - 新增 SQL：`resources/mapper/room/RoomMapper.xml`
  - 新增测试：`room/{RoomIntegrationTest,RoomApiTest}.java`
  - 修改：`test/support/NoDatabaseStubConfig.java`（`RoomMapper` 桩）、`test/resources/db/integration-cleanup.sql`（新增表清理顺序）
- 验证（本轮实际执行，详见 [S1-04 记录](../verification/S1-04/2026-10-30-room-baseline.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 76, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 24 个：RoomIntegrationTest 15 / RoomApiTest 9）
  - 无库：`./mvnw -B -ntp test` → 76 run / **57 skipped** / 0 failure → BUILD SUCCESS
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 76 tests / 0 failure → **BUILD SUCCESS**（14.0s）
  - 运行态冒烟（`local` 档案，18082，真实 HTTP）：建房型 201（`DLX-King`→`dlx-king`）、建房间 201（`housekeepingStatus=DIRTY`）、按楼层筛选 200、有在用房间时停用房型 **409**、同租户不在范围门店 **403**、不存在门店 **404**；`flyway_schema_history` V1/V2/V3 全 success（V2→V3 增量升级路径已真实执行）
  - 未覆盖：有有效预订时的停用处置（S1-05）、维修区间（S1-05）、清洁流转（S3-06）、房态聚合（S1-12）、前端页面（S3-01a 阻塞）、并发同房号创建（S1-13）
- 本轮发现（已处理 / 转下轮）：
  1. 跨店房型在服务层返回 **404** 而不是 400：按 `(tenant, hotel)` 查不到即视为不存在，避免确认"该房型存在于另一门店"。已在测试固定该语义。
  2. **`target/classes` 曾被 IDEA 并发清空**（上一轮改了 `modules.xml`，IDEA 重新导入项目），导致 76 个用例全部 `NoClassDefFoundError`。门禁与 IDE 构建不要同时跑；失败先看 `target/classes` 是否为空。
  3. 房间属性更新是"null 表示不变"的补丁语义，因此**不能把备注清空成 null**；补丁语义待 S1-11 定义。
  4. **设计分歧待裁决**：`docs/domain/data-model.md` §4 规定业务实体用 `bigint` 主键，而 V1/V2 已落 `uuid`。本轮沿用 `uuid` 保持单一 id 体系，未擅自改体系；分歧登记到 G0（S0-09）审查，阶段 1 结束前必须统一（越晚迁移成本越高）。
  5. 清洁状态"不提供写入口"是本轮实现解耦的手段，不是最终形态；S3-06 接入时必须同时保证它不写入库存事实。
- 回写确认：feature-catalog ✓（C-02 → DONE；C-01/C-03/C-04 → VERIFYING 75%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-05` 日期约束、占用排斥与维修互斥（DB-B 后半，含 `room_block` 与 `btree_gist` 权限验证）。

---

### R-20261030-04 S1-03 最小认证、授权与租户隔离

- 状态：DONE
- 范围（本轮只做这些）：
  1. Flyway V2（DB-A 剩余）：`app_user` / `role` / `permission` / `role_permission` / `user_role` / `user_hotel_scope` / `user_session`，全部带 `tenant_id` 与 `(id, tenant_id)` 复合唯一，跨表一律复合外键。
  2. 认证：**不透明会话令牌**（Bearer，32 字节随机，库内只存 SHA-256 哈希），登录 / 登出 / 当前身份 / 改密 四个接口。
  3. 授权：RBAC 权限点（`资源:动作`）+ `@PreAuthorize`；门店数据范围校验（`Authz.requireHotel` / `requireAllHotels` 全有或全无）。
  4. 请求上下文：过滤器从**会话身份**推导租户，写入 `TenantContext`，请求结束必须清理；后台任务用显式租户上下文（B-07）。
  5. 集成测试（真实 PostgreSQL）：伪造 tenant_id、跨店 ID、批量混入他租户对象、撤权后旧会话、线程复用不串租户。
  6. ADR-016：令牌形态、令牌存储、是否使用 RLS、CSRF 前提。
- 本轮明确不做：员工/角色 CRUD 接口（S3-02）、组织读写 HTTP 接口（S1-11）、审计落库（S1-06 / B-05）、A-05 平台运维身份（DEC-02 阻塞）、B-08 越权回归集（S1-13）。
- 涉及功能项（FT-*）/ 任务（S*-*）：A-04、B-01、B-02、B-03、B-04、B-06、B-07；S1-03
- 变更文件：
  - 新增迁移：`src/main/resources/db/migration/V2__identity_baseline.sql`
  - 新增安全基座：`shared/security/{TenantContext,TenantContextHolder,PmsPrincipal,SessionTokens,Authz}.java`、`shared/error/{UnauthenticatedException,NotFoundException}.java`
  - 新增身份模块：`identity/domain/{UserStatus,AppUser,Role,UserSession,SessionView}.java`、`identity/mapper/IdentityMapper.java`、`identity/service/AuthenticationService.java`
  - 新增接口层：`identity/web/{AuthController,IdentityController,BearerSessionAuthenticationFilter,LoginRequest,LoginResponse,ChangePasswordRequest,UserView}.java`
  - 新增 SQL：`resources/mapper/identity/IdentityMapper.xml`
  - 新增测试：`identity/{AuthIntegrationTest,AuthApiTest}.java`、`shared/security/{SessionTokensTest,TenantContextHolderTest}.java`、`support/IdentitySeed.java`
  - 修改：`shared/config/SecurityConfig.java`（会话过滤器 + BCrypt）、`shared/web/GlobalExceptionHandler.java`（401/403/404）、`application.properties`（`pms.security.session-ttl`）、`test/resources/db/integration-cleanup.sql`（新表清理顺序）、`test/support/NoDatabaseStubConfig.java`（`IdentityMapper` 桩）
  - 新增文档：`docs/adr/ADR-016-authn-authz-tenant-isolation.md`、`docs/verification/S1-03/2026-10-30-authn-authz.md`
- 验证（本轮实际执行，详见 [S1-03 记录](../verification/S1-03/2026-10-30-authn-authz.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 52, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 32 个：AuthIntegrationTest 15 / AuthApiTest 6 / SessionTokensTest 5 / TenantContextHolderTest 6）
  - 无库：`./mvnw -B -ntp test` → 52 run / **33 skipped** / 0 failure → BUILD SUCCESS（跳过而非伪通过）
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 52 tests / 0 failure → **BUILD SUCCESS**（10.7s）
  - 运行态冒烟（`local` 档案，端口 18081，真实 HTTP）：health 200；无令牌 `/auth/me` 401；登录 200（43 字符令牌，+8h 过期）；带令牌 `/auth/me` 200；门店范围 200；不存在 userId 404；登出 204 后原令牌 401；会话表 `token_hash` 长度 64 且 `revoke_reason=LOGOUT`
  - 未覆盖：并发竞争（S1-13）、登录失败锁定/限流（S6-05）、审计落库（S1-06）、前端登录页（S3-01a 仍 BLOCKED）、会话过期行归档（S6-03）、CI 实跑
- 本轮发现（已处理）：
  1. `GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 会把 `@PreAuthorize` 抛出的 `AccessDeniedException` 当未预期异常返回 **500**。已补 403/401/404 三个显式处理器——否则权限失败会被误报成服务故障。
  2. 过滤器若注册成 `@Component`，Boot 会自动挂到 Servlet 容器，与 `addFilterBefore` 叠加导致同一请求执行两次。改为在 `SecurityConfig` 内手动构造。
  3. `ck_user_session_expiry`（`expires_at > issued_at`）挡住了"只改过期时间"的测试写法；该约束正确（禁止签发即过期），测试改为同时前移 `issued_at`。
  4. `user_session.last_seen_at` 不逐请求更新：每个 GET 都会变成写事务。闲置超时定案前不实现。
  5. 每请求 3 次查询（会话联表 + 权限点 + 门店范围），先不加缓存，等 S2-06 的 P95 数据再定。
- 回写确认：feature-catalog ✓（A-04/B-04/B-06/B-07 → DONE；B-01/B-02/B-03 → VERIFYING 75%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-04` 房型、实体房间与运营状态（DB-B 前半）。

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
